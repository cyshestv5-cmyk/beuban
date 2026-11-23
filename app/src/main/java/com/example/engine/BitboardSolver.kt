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
 * 64-bit bitboard solver with exact turn permutation search, 64-bit connected-component
 * cavity/dead-island analysis, killer-piece capacity checks, and time-budgeted (up to 240ms)
 * Monte Carlo future-draw rollouts to prevent dead-end Game Over boards.
 */
object BitboardSolver {

    private val LEFT_EDGE_CLEARED = 0xFEFEFEFEFEFEFEFEUL.toLong()
    private const val RIGHT_EDGE_CLEARED = 0x7F7F7F7F7F7F7F7FL

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

    private data class DedupKey(
        val slotIndex: Int,
        val row: Int,
        val col: Int,
        val endBoard: Long
    )

    fun solve(
        boardMask: Long,
        tray: List<TraySlotState>,
        mode: SolverMode = SolverMode.BALANCED_SURVIVAL,
        depth: ThinkingDepth = ThinkingDepth.ULTRA_DEEP
    ): MoveRecommendation? {
        val startNs = System.nanoTime()
        val budgetNs = depth.budgetMs * 1_000_000L
        val searchSoftDeadlineNs = startNs + (budgetNs * 35L) / 100L
        val totalDeadlineNs = startNs + budgetNs

        val activeSlots = tray.filterIsInstance<TraySlotState.Recognized>()
        if (activeSlots.isEmpty()) return null

        val branchCap = if (depth == ThinkingDepth.ULTRA_DEEP) 48 else 14
        val permutations = generatePermutations(activeSlots)
        val candidates = ArrayList<CandidatePlan>(512)

        for (perm in permutations) {
            if (candidates.isNotEmpty() && System.nanoTime() > searchSoftDeadlineNs) break
            searchPermutation(
                board = boardMask,
                perm = perm,
                depth = 0,
                steps = ArrayList(perm.size),
                totalLines = 0,
                multiBonus = 0f,
                lastCleared = false,
                mode = mode,
                branchCap = branchCap,
                deadlineNs = searchSoftDeadlineNs,
                outCandidates = candidates
            )
        }

        // If placing all 3 pieces is impossible, gracefully evaluate 2-piece and 1-piece rescue sequences
        if (candidates.isEmpty() && activeSlots.size >= 2) {
            searchPartialTurns(boardMask, activeSlots, targetLength = activeSlots.size - 1, mode, candidates)
        }
        if (candidates.isEmpty()) {
            searchPartialTurns(boardMask, activeSlots, targetLength = 1, mode, candidates)
        }
        if (candidates.isEmpty()) return null

        // Deduplicate by (first move + resulting endBoard) so Monte Carlo rollouts focus on distinct outcomes
        candidates.sortByDescending { it.heuristicScore }
        val uniqueCandidates = ArrayList<CandidatePlan>(32)
        val seenKeys = HashSet<DedupKey>(64)
        val maxRolloutCandidates = if (depth == ThinkingDepth.ULTRA_DEEP) 24 else 8
        for (cand in candidates) {
            val first = cand.steps.first()
            val key = DedupKey(first.slotIndex, first.row, first.col, cand.endBoard)
            if (seenKeys.add(key)) {
                uniqueCandidates.add(cand)
                if (uniqueCandidates.size >= maxRolloutCandidates) break
            }
        }

        val mcDraws = if (depth == ThinkingDepth.ULTRA_DEEP) 32 else 12
        var bestPlan = uniqueCandidates.first()
        var bestFinalScore = Float.NEGATIVE_INFINITY
        var bestMcRate = 0f
        var bestBulkyCount = 0

        for ((index, cand) in uniqueCandidates.withIndex()) {
            // Always evaluate at least Top 4 candidates; beyond that respect strict 250ms total budget
            if (index >= 4 && System.nanoTime() >= totalDeadlineNs) break

            val bulkyCount = countBulkyPlacements(cand.endBoard)
            val killerRate = if (depth == ThinkingDepth.ULTRA_DEEP) {
                evaluateKillerReserveRate(cand.endBoard)
            } else {
                0.5f
            }
            val mcRate = runMonteCarloRollout(cand.endBoard, draws = mcDraws)

            val mcWeight = if (mode == SolverMode.BALANCED_SURVIVAL) 220f else 130f
            val killerWeight = if (mode == SolverMode.BALANCED_SURVIVAL) 95f else 55f
            val finalScore = cand.heuristicScore + mcRate * mcWeight + killerRate * killerWeight

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

    private fun searchPartialTurns(
        boardMask: Long,
        activeSlots: List<TraySlotState.Recognized>,
        targetLength: Int,
        mode: SolverMode,
        outCandidates: MutableList<CandidatePlan>
    ) {
        if (targetLength <= 0) return
        val perms = if (targetLength == 1) {
            activeSlots.map { listOf(it) }
        } else {
            generatePermutations(activeSlots).map { it.take(targetLength) }.distinct()
        }
        val deadline = System.nanoTime() + 35_000_000L
        for (perm in perms) {
            searchPermutation(
                board = boardMask,
                perm = perm,
                depth = 0,
                steps = ArrayList(perm.size),
                totalLines = 0,
                multiBonus = 0f,
                lastCleared = false,
                mode = mode,
                branchCap = 24,
                deadlineNs = deadline,
                outCandidates = outCandidates
            )
        }
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
        branchCap: Int,
        deadlineNs: Long,
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

        data class Branch(val r: Int, val c: Int, val res: PlaceResult, val quickScore: Float)

        val branches = ArrayList<Branch>(48)
        for (r in 0..maxR) {
            for (c in 0..maxC) {
                val pm = piece.maskAt(r, c)
                if ((board and pm) == 0L) {
                    val res = placeAndClear(board, pm)
                    val qScore = if (depth < perm.lastIndex) {
                        res.linesCleared * 28f - java.lang.Long.bitCount(res.boardAfterClear) * 1.6f +
                            countNeighbors(board, pm) * 2.4f +
                            countWallContacts(pm) * 1.8f
                    } else 0f
                    branches.add(Branch(r, c, res, qScore))
                }
            }
        }

        if (branches.isEmpty()) return

        val limited = if (depth < perm.lastIndex && branches.size > branchCap) {
            branches.sortedByDescending { it.quickScore }.take(branchCap)
        } else {
            branches
        }

        for (br in limited) {
            if (outCandidates.size >= 32 && System.nanoTime() > deadlineNs) break

            val step = PlannedPlacement(
                slotIndex = slot.slotIndex,
                piece = piece,
                row = br.r,
                col = br.c,
                linesCleared = br.res.linesCleared,
                resultingBoard = br.res.boardAfterClear
            )
            steps.add(step)
            val multi = if (br.res.linesCleared >= 2) (br.res.linesCleared * br.res.linesCleared * 14f) else 0f
            searchPermutation(
                board = br.res.boardAfterClear,
                perm = perm,
                depth = depth + 1,
                steps = steps,
                totalLines = totalLines + br.res.linesCleared,
                multiBonus = multiBonus + multi,
                lastCleared = br.res.linesCleared > 0,
                mode = mode,
                branchCap = branchCap,
                deadlineNs = deadlineNs,
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
     * Scores resulting board topology:
     * 1. Killer/bulky piece accommodation (3x3, 1x5, 5x1, 3x3 Ls, 2x2)
     * 2. 64-bit flood-fill cavity & dead-island fragmentation penalties
     * 3. Trapped holes, rough edge transitions, and near-complete rescue lines
     */
    fun evaluateBoardTopology(board: Long, mode: SolverMode): Float {
        val occupiedCount = java.lang.Long.bitCount(board)
        val emptyCount = 64 - occupiedCount

        // 1. Bulky piece accommodation
        val sq3x3 = countPlacements(board, PieceCatalog.BY_ID.getValue("sq_3x3"))
        val bar1x5 = countPlacements(board, PieceCatalog.BY_ID.getValue("bar_1x5"))
        val bar5x1 = countPlacements(board, PieceCatalog.BY_ID.getValue("bar_5x1"))
        val sq2x2 = countPlacements(board, PieceCatalog.BY_ID.getValue("sq_2x2"))
        val lBulky = countPlacements(board, PieceCatalog.BY_ID.getValue("c3_tl")) +
            countPlacements(board, PieceCatalog.BY_ID.getValue("c3_br"))

        val survivalFactor = if (mode == SolverMode.BALANCED_SURVIVAL) 1.45f else 0.95f
        var bulkyScore = (sq3x3 * 10f + bar1x5 * 5.5f + bar5x1 * 5.5f + sq2x2 * 2.5f + lBulky * 2.5f) * survivalFactor
        if (sq3x3 == 0) bulkyScore -= 135f * survivalFactor
        else if (sq3x3 == 1) bulkyScore -= 30f * survivalFactor
        if (bar1x5 == 0) bulkyScore -= 58f * survivalFactor
        if (bar5x1 == 0) bulkyScore -= 58f * survivalFactor
        if (sq2x2 == 0) bulkyScore -= 90f * survivalFactor

        // 2. 64-bit flood-fill connected empty cavity analysis
        var unvisitedEmpty = board.inv()
        var tiny1Regions = 0
        var tiny2Regions = 0
        var small34Regions = 0
        var componentCount = 0
        var largestRegion = 0

        while (unvisitedEmpty != 0L) {
            val seed = unvisitedEmpty and (-unvisitedEmpty)
            var region = seed
            while (true) {
                val up = region ushr 8
                val down = region shl 8
                val left = (region and LEFT_EDGE_CLEARED) ushr 1
                val right = (region and RIGHT_EDGE_CLEARED) shl 1
                val next = (region or up or down or left or right) and unvisitedEmpty
                if (next == region) break
                region = next
            }
            unvisitedEmpty = unvisitedEmpty and region.inv()
            componentCount++
            val size = java.lang.Long.bitCount(region)
            if (size > largestRegion) largestRegion = size
            when (size) {
                1 -> tiny1Regions++
                2 -> tiny2Regions++
                3, 4 -> small34Regions++
            }
        }

        // 3. Local hole walls, rough transitions, and near-complete rescue lines
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

        // Near-clear rescue readiness (6 or 7 filled cells in a row or column)
        var nearClearBonus = 0f
        for (i in 0 until 8) {
            val rowBits = java.lang.Long.bitCount(board and ROW_MASKS[i])
            val colBits = java.lang.Long.bitCount(board and COL_MASKS[i])
            if (rowBits == 7) nearClearBonus += 8f else if (rowBits == 6) nearClearBonus += 3f
            if (colBits == 7) nearClearBonus += 8f else if (colBits == 6) nearClearBonus += 3f
        }

        val fragmentationPenalty = (tiny1Regions * 34f) +
            (tiny2Regions * 22f) +
            (small34Regions * 11f) +
            (max(0, componentCount - 1) * 8f)

        return emptyCount * 2.5f +
            largestRegion * 1.4f +
            bulkyScore +
            nearClearBonus -
            fragmentationPenalty -
            isolatedHoles * 24f -
            semiTrapped * 7.5f -
            roughEdges * 1.25f
    }

    /**
     * Stress-tests an end-board against the hardest possible upcoming 3-piece killer combinations.
     */
    private fun evaluateKillerReserveRate(endBoard: Long): Float {
        val p3x3 = PieceCatalog.BY_ID.getValue("sq_3x3")
        val p1x5 = PieceCatalog.BY_ID.getValue("bar_1x5")
        val p5x1 = PieceCatalog.BY_ID.getValue("bar_5x1")
        val pC3tl = PieceCatalog.BY_ID.getValue("c3_tl")
        val pC3br = PieceCatalog.BY_ID.getValue("c3_br")
        val p2x3 = PieceCatalog.BY_ID.getValue("rect_2x3")
        val p3x2 = PieceCatalog.BY_ID.getValue("rect_3x2")
        val p2x2 = PieceCatalog.BY_ID.getValue("sq_2x2")

        val stressSets = arrayOf(
            Triple(p3x3, p1x5, p5x1),
            Triple(p3x3, pC3tl, p2x2),
            Triple(p3x3, pC3br, p3x2),
            Triple(p1x5, p5x1, p2x3)
        )
        var survived = 0
        for ((a, b, c) in stressSets) {
            if (canSurviveAnyOrder(endBoard, a, b, c)) survived++
        }
        return survived.toFloat() / stressSets.size
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
     * from PieceCatalog to verify the end-board survives diverse upcoming draws.
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
        val left = ((pm and LEFT_EDGE_CLEARED) ushr 1) and board
        val right = ((pm and RIGHT_EDGE_CLEARED) shl 1) and board
        return java.lang.Long.bitCount(up) +
            java.lang.Long.bitCount(down) +
            java.lang.Long.bitCount(left) +
            java.lang.Long.bitCount(right)
    }

    private fun countWallContacts(pm: Long): Int {
        val topBottom = pm and (ROW_MASKS[0] or ROW_MASKS[7])
        val leftRight = pm and (COL_MASKS[0] or COL_MASKS[7])
        return java.lang.Long.bitCount(topBottom) + java.lang.Long.bitCount(leftRight)
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
