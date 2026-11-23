package com.example.engine

import kotlin.math.max
import kotlin.random.Random

data class PlannedPlacement(
    val slotIndex: Int,
    val piece: PieceShape,
    val row: Int,
    val col: Int,
    val linesCleared: Int,
    val resultingBoard: Long
)

data class MoveRecommendation(
    val slotIndex: Int,
    val piece: PieceShape,
    val targetRow: Int,
    val targetCol: Int,
    val linesCleared: Int,
    val fullPlan: List<PlannedPlacement>,
    val survivabilityScore: Float,
    val bulkyCapacityScore: Int,
    val monteCarloSurvivalRate: Float,
    val solveTimeMs: Long
)

/**
 * 64-bit bitboard solver with exact turn permutation search, bulky-piece capacity checks,
 * trapped-hole topology penalties, and Top-8 1-step Monte Carlo future-draw rollouts.
 */
object BitboardSolver {

    private val ROW_MASKS = LongArray(8) { r -> 0xFFL shl (r * 8) }
    private val COL_MASKS = LongArray(8) { c ->
        var m = 0L
        for (r in 0 until 8) m = m or (1L shl (r * 8 + c))
        m
    }

    data class PlaceResult(val boardAfterClear: Long, val linesCleared: Int)

    private data class CandidatePlan(
        val steps: List<PlannedPlacement>,
        val endBoard: Long,
        val totalLinesCleared: Int,
        val multiClearBonus: Float,
        val lastMoveClearedLine: Boolean,
        val heuristicScore: Float
    )

    fun solve(
        boardMask: Long,
        tray: List<TraySlotState>,
        mode: SolverMode = SolverMode.BALANCED_SURVIVAL
    ): MoveRecommendation? {
        val startNs = System.nanoTime()
        val activeSlots = tray.filterIsInstance<TraySlotState.Recognized>()
        if (activeSlots.isEmpty()) return null

        val permutations = generatePermutations(activeSlots)
        val candidates = ArrayList<CandidatePlan>(256)

        for (perm in permutations) {
            searchPermutation(
                board = boardMask,
                perm = perm,
                depth = 0,
                steps = ArrayList(perm.size),
                totalLines = 0,
                multiBonus = 0f,
                lastCleared = false,
                mode = mode,
                outCandidates = candidates
            )
        }

        if (candidates.isEmpty()) {
            // Fallback: find best single valid placement among remaining pieces if full sequence impossible
            val fallbackSteps = ArrayList<PlannedPlacement>()
            for (slot in activeSlots) {
                val p = slot.piece
                for (r in 0..(8 - p.height)) {
                    for (c in 0..(8 - p.width)) {
                        val pm = p.maskAt(r, c)
                        if ((boardMask and pm) == 0L) {
                            val res = placeAndClear(boardMask, pm)
                            val score = evaluateBoardTopology(res.boardAfterClear, mode) + res.linesCleared * 25f
                            candidates.add(
                                CandidatePlan(
                                    steps = listOf(PlannedPlacement(slot.slotIndex, p, r, c, res.linesCleared, res.boardAfterClear)),
                                    endBoard = res.boardAfterClear,
                                    totalLinesCleared = res.linesCleared,
                                    multiClearBonus = res.linesCleared * res.linesCleared * 10f,
                                    lastMoveClearedLine = res.linesCleared > 0,
                                    heuristicScore = score
                                )
                            )
                        }
                    }
                }
            }
            if (candidates.isEmpty()) return null
        }

        // Sort by fast static heuristic and run 1-Step Monte Carlo Rollout on Top 8 end-boards
        candidates.sortByDescending { it.heuristicScore }
        val topK = candidates.take(8)

        var bestPlan = topK.first()
        var bestFinalScore = Float.NEGATIVE_INFINITY
        var bestMcRate = 0f
        var bestBulkyCount = 0

        for (cand in topK) {
            val bulkyCount = countBulkyPlacements(cand.endBoard)
            val mcRate = runMonteCarloRollout(cand.endBoard, draws = 12)
            val mcWeight = if (mode == SolverMode.BALANCED_SURVIVAL) 180f else 110f
            val finalScore = cand.heuristicScore + mcRate * mcWeight
            if (finalScore > bestFinalScore) {
                bestFinalScore = finalScore
                bestPlan = cand
                bestMcRate = mcRate
                bestBulkyCount = bulkyCount
            }
        }

        val firstMove = bestPlan.steps.first()
        val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)

        return MoveRecommendation(
            slotIndex = firstMove.slotIndex,
            piece = firstMove.piece,
            targetRow = firstMove.row,
            targetCol = firstMove.col,
            linesCleared = firstMove.linesCleared,
            fullPlan = bestPlan.steps,
            survivabilityScore = bestFinalScore,
            bulkyCapacityScore = bestBulkyCount,
            monteCarloSurvivalRate = bestMcRate,
            solveTimeMs = elapsedMs
        )
    }

    private fun searchPermutation(
        board: Long,
        perm: List<TraySlotState.Recognized>,
        depth: Int,
        steps: MutableList<PlannedPlacement>,
        totalLines: Int,
        multiBonus: Float,
        lastCleared: Boolean,
        mode: SolverMode,
        outCandidates: MutableList<CandidatePlan>
    ) {
        if (depth == perm.size) {
            val topo = evaluateBoardTopology(board, mode)
            val comboFactor = if (mode == SolverMode.COMBO_MAXIMIZER) 1.75f else 1.0f
            val endComboBonus = if (lastCleared) 28f * comboFactor else 0f
            val lineScore = totalLines * 18f * comboFactor + multiBonus * comboFactor
            val score = topo + lineScore + endComboBonus

            outCandidates.add(
                CandidatePlan(
                    steps = steps.toList(),
                    endBoard = board,
                    totalLinesCleared = totalLines,
                    multiClearBonus = multiBonus,
                    lastMoveClearedLine = lastCleared,
                    heuristicScore = score
                )
            )
            return
        }

        val slot = perm[depth]
        val piece = slot.piece
        val maxR = 8 - piece.height
        val maxC = 8 - piece.width

        // Collect all valid placements at this depth
        data class Branch(val r: Int, val c: Int, val res: PlaceResult, val quickScore: Float)

        val branches = ArrayList<Branch>(32)
        for (r in 0..maxR) {
            for (c in 0..maxC) {
                val pm = piece.maskAt(r, c)
                if ((board and pm) == 0L) {
                    val res = placeAndClear(board, pm)
                    val qScore = if (depth < perm.lastIndex) {
                        res.linesCleared * 24f - java.lang.Long.bitCount(res.boardAfterClear) * 1.5f +
                            countNeighbors(board, pm) * 2.0f
                    } else 0f
                    branches.add(Branch(r, c, res, qScore))
                }
            }
        }

        if (branches.isEmpty()) return

        // Beam-cap branching at depth 0/1 to keep total solve well under 15ms on mobile CPUs
        val limited = if (depth < perm.lastIndex && branches.size > 14) {
            branches.sortedByDescending { it.quickScore }.take(14)
        } else {
            branches
        }

        for (br in limited) {
            val step = PlannedPlacement(
                slotIndex = slot.slotIndex,
                piece = piece,
                row = br.r,
                col = br.c,
                linesCleared = br.res.linesCleared,
                resultingBoard = br.res.boardAfterClear
            )
            steps.add(step)
            val multi = if (br.res.linesCleared >= 2) (br.res.linesCleared * br.res.linesCleared * 12f) else 0f
            searchPermutation(
                board = br.res.boardAfterClear,
                perm = perm,
                depth = depth + 1,
                steps = steps,
                totalLines = totalLines + br.res.linesCleared,
                multiBonus = multiBonus + multi,
                lastCleared = br.res.linesCleared > 0,
                mode = mode,
                outCandidates = outCandidates
            )
            steps.removeAt(steps.lastIndex)
        }
    }

    fun placeAndClear(board: Long, placedMask: Long): PlaceResult {
        val occupied = board or placedMask
        var clearMask = 0L
        var lines = 0
        for (r in 0 until 8) {
            val rm = ROW_MASKS[r]
            if ((occupied and rm) == rm) {
                clearMask = clearMask or rm
                lines++
            }
        }
        for (c in 0 until 8) {
            val cm = COL_MASKS[c]
            if ((occupied and cm) == cm) {
                clearMask = clearMask or cm
                lines++
            }
        }
        return PlaceResult(occupied and clearMask.inv(), lines)
    }

    /**
     * Scores resulting board topology and future bulky-piece capacity.
     */
    fun evaluateBoardTopology(board: Long, mode: SolverMode): Float {
        val occupiedCount = java.lang.Long.bitCount(board)
        val emptyCount = 64 - occupiedCount

        // 1. Bulky piece accommodation (3x3, 1x5, 5x1, 3x3 Ls)
        val sq3x3 = countPlacements(board, PieceCatalog.BY_ID.getValue("sq_3x3"))
        val bar1x5 = countPlacements(board, PieceCatalog.BY_ID.getValue("bar_1x5"))
        val bar5x1 = countPlacements(board, PieceCatalog.BY_ID.getValue("bar_5x1"))
        val lBulky = countPlacements(board, PieceCatalog.BY_ID.getValue("c3_tl")) +
            countPlacements(board, PieceCatalog.BY_ID.getValue("c3_br"))

        val survivalFactor = if (mode == SolverMode.BALANCED_SURVIVAL) 1.35f else 0.9f
        var bulkyScore = (sq3x3 * 9f + bar1x5 * 5f + bar5x1 * 5f + lBulky * 2.5f) * survivalFactor
        if (sq3x3 == 0) bulkyScore -= 85f * survivalFactor
        if (bar1x5 == 0) bulkyScore -= 35f * survivalFactor
        if (bar5x1 == 0) bulkyScore -= 35f * survivalFactor

        // 2. Penalize isolated 1x1 holes and rough transitions
        var isolatedHoles = 0
        var semiTrapped = 0
        var roughEdges = 0

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val bit = 1L shl (r * 8 + c)
                val isOcc = (board and bit) != 0L
                if (!isOcc) {
                    var wallCount = 0
                    if (r == 0 || (board and (1L shl ((r - 1) * 8 + c))) != 0L) wallCount++
                    if (r == 7 || (board and (1L shl ((r + 1) * 8 + c))) != 0L) wallCount++
                    if (c == 0 || (board and (1L shl (r * 8 + (c - 1)))) != 0L) wallCount++
                    if (c == 7 || (board and (1L shl (r * 8 + (c + 1)))) != 0L) wallCount++

                    if (wallCount == 4) isolatedHoles++
                    else if (wallCount == 3) semiTrapped++
                }
                if (c < 7) {
                    val rightOcc = (board and (1L shl (r * 8 + c + 1))) != 0L
                    if (isOcc != rightOcc) roughEdges++
                }
                if (r < 7) {
                    val downOcc = (board and (1L shl ((r + 1) * 8 + c))) != 0L
                    if (isOcc != downOcc) roughEdges++
                }
            }
        }

        return emptyCount * 2.4f +
            bulkyScore -
            isolatedHoles * 26f -
            semiTrapped * 7.5f -
            roughEdges * 1.1f
    }

    private fun countBulkyPlacements(board: Long): Int {
        var total = 0
        for (p in PieceCatalog.BULKY_PIECES) {
            total += countPlacements(board, p)
        }
        return total
    }

    private fun countPlacements(board: Long, piece: PieceShape): Int {
        var count = 0
        val maxR = 8 - piece.height
        val maxC = 8 - piece.width
        for (r in 0..maxR) {
            for (c in 0..maxC) {
                if ((board and piece.maskAt(r, c)) == 0L) count++
            }
        }
        return count
    }

    /**
     * Deterministic-seed 1-step Monte Carlo rollout sampling [draws] random 3-piece turns
     * from PieceCatalog to verify the end-board doesn't trap against common draws.
     */
    private fun runMonteCarloRollout(endBoard: Long, draws: Int): Float {
        val rng = Random(endBoard xor 0x5DEECE66DL)
        var survived = 0
        for (d in 0 until draws) {
            val p1 = sampleWeightedPiece(rng)
            val p2 = sampleWeightedPiece(rng)
            val p3 = sampleWeightedPiece(rng)
            if (canSurviveAnyOrder(endBoard, p1, p2, p3)) {
                survived++
            }
        }
        return survived.toFloat() / draws
    }

    private fun canSurviveAnyOrder(board: Long, a: PieceShape, b: PieceShape, c: PieceShape): Boolean {
        val orders = arrayOf(
            arrayOf(a, b, c),
            arrayOf(a, c, b),
            arrayOf(b, a, c),
            arrayOf(b, c, a),
            arrayOf(c, a, b),
            arrayOf(c, b, a)
        )
        for (ord in orders) {
            if (canPlaceSequenceFast(board, ord, 0)) return true
        }
        return false
    }

    private fun canPlaceSequenceFast(board: Long, pieces: Array<PieceShape>, idx: Int): Boolean {
        if (idx == pieces.size) return true
        val p = pieces[idx]
        val maxR = 8 - p.height
        val maxC = 8 - p.width
        for (r in 0..maxR) {
            for (c in 0..maxC) {
                val pm = p.maskAt(r, c)
                if ((board and pm) == 0L) {
                    val next = placeAndClear(board, pm).boardAfterClear
                    if (canPlaceSequenceFast(next, pieces, idx + 1)) return true
                }
            }
        }
        return false
    }

    private fun sampleWeightedPiece(rng: Random): PieceShape {
        var r = rng.nextFloat() * PieceCatalog.TOTAL_WEIGHT
        for (p in PieceCatalog.ALL) {
            r -= p.spawnWeight
            if (r <= 0f) return p
        }
        return PieceCatalog.ALL.first()
    }

    private fun countNeighbors(board: Long, pm: Long): Int {
        val up = (pm ushr 8) and board
        val down = (pm shl 8) and board
        val left = ((pm and 0xFEFEFEFEFEFEFEFEUL.toLong()) ushr 1) and board
        val right = ((pm and 0x7F7F7F7F7F7F7F7FL) shl 1) and board
        return java.lang.Long.bitCount(up) +
            java.lang.Long.bitCount(down) +
            java.lang.Long.bitCount(left) +
            java.lang.Long.bitCount(right)
    }

    private fun <T> generatePermutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        val result = ArrayList<List<T>>(6)
        for (i in items.indices) {
            val rest = items.filterIndexed { idx, _ -> idx != i }
            for (sub in generatePermutations(rest)) {
                result.add(listOf(items[i]) + sub)
            }
        }
        return result
    }
}
