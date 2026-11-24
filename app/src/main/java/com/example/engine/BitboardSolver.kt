package com.example.engine

import com.example.data.ComboState
import com.example.data.ComboTracker
import kotlin.math.max
import kotlin.math.min
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
 * High-speed, zero-allocation 64-bit bitboard engine featuring:
 * 1. Pure bitwise topology & hole/roughness/bulky evaluation (O(1) per board).
 * 2. Precomputed piece placement masks & mid-turn transposition deduplication across permutations.
 * 3. Adaptive millisecond budgeting (fast on open boards, grandmaster deep when constrained).
 * 4. Live combo-streak timing & 2-turn quality-graded lookahead rollouts + killer stress checks.
 */
object BitboardSolver {

    private const val COL_0_MASK = 0x0101010101010101L
    private const val COL_7_MASK = -0x7F7F7F7F7F7F7F80L // 0x8080808080808080L
    private const val NOT_COL_0 = COL_0_MASK.inv()        // 0xFEFEFEFEFEFEFEFEL
    private const val NOT_COL_7 = COL_7_MASK.inv()        // 0x7F7F7F7F7F7F7F7FL
    private const val ROW_0_MASK = 0x00000000000000FFL
    private const val ROW_7_MASK = -0x0100000000000000L // 0xFF00000000000000L
    private const val NOT_ROW_7 = 0x00FFFFFFFFFFFFFFL
    private const val CENTER_4X4_MASK = 0x00003C3C3C3C0000L
    private const val BORDER_RING_MASK = ROW_0_MASK or ROW_7_MASK or COL_0_MASK or COL_7_MASK

    private val ROW_MASKS = LongArray(8) { r -> 0xFFL shl (r * 8) }
    private val COL_MASKS = LongArray(8) { c -> COL_0_MASK shl c }

    private val SQ_3X3 = PieceCatalog.BY_ID.getValue("sq_3x3")
    private val BAR_1X5 = PieceCatalog.BY_ID.getValue("bar_1x5")
    private val BAR_5X1 = PieceCatalog.BY_ID.getValue("bar_5x1")
    private val SQ_2X2 = PieceCatalog.BY_ID.getValue("sq_2x2")
    private val C3_TL = PieceCatalog.BY_ID.getValue("c3_tl")
    private val C3_TR = PieceCatalog.BY_ID.getValue("c3_tr")
    private val C3_BL = PieceCatalog.BY_ID.getValue("c3_bl")
    private val C3_BR = PieceCatalog.BY_ID.getValue("c3_br")
    private val RECT_2X3 = PieceCatalog.BY_ID.getValue("rect_2x3")
    private val RECT_3X2 = PieceCatalog.BY_ID.getValue("rect_3x2")

    data class PlaceResult(val boardAfterClear: Long, val linesCleared: Int)

    private enum class BoardTier { OPEN, CONTESTED, CRITICAL }

    private class CandidatePlan(
        val steps: Array<PlannedPlacement>,
        val endBoard: Long,
        val totalLinesCleared: Int,
        val multiClearBonus: Float,
        val endComboStreak: Int,
        val endPlacementsSinceClear: Int,
        val comboLostInTurn: Boolean,
        val lastMoveClearedLine: Boolean,
        val heuristicScore: Float
    )

    private class DedupKey(
        val slotIndex: Int,
        val row: Int,
        val col: Int,
        val endBoard: Long
    ) {
        override fun equals(other: Any?): Boolean =
            other is DedupKey &&
                slotIndex == other.slotIndex &&
                row == other.row &&
                col == other.col &&
                endBoard == other.endBoard

        override fun hashCode(): Int {
            var h = slotIndex * 31 + row
            h = h * 31 + col
            return h * 31 + endBoard.hashCode()
        }
    }

    /** Zero-boxing open-addressing primitive table storing best heuristic score seen per board state. */
    private class PrimitiveTranspositionTable(capacityPow2: Int = 512) {
        private val mask = capacityPow2 - 1
        private val keys = LongArray(capacityPow2)
        private val values = FloatArray(capacityPow2)
        private val used = BooleanArray(capacityPow2)

        fun shouldExpand(board: Long, scoreSoFar: Float): Boolean {
            var h = mix64(board).toInt() and mask
            var probes = 0
            while (used[h]) {
                if (keys[h] == board) {
                    return if (scoreSoFar > values[h] + 1.5f) {
                        values[h] = scoreSoFar
                        true
                    } else {
                        false
                    }
                }
                probes++
                if (probes >= 12) return true
                h = (h + 1) and mask
            }
            keys[h] = board
            values[h] = scoreSoFar
            used[h] = true
            return true
        }

        private fun mix64(z0: Long): Long {
            var z = z0 xor (z0 ushr 30)
            z *= -0x40a7b892e31b1a47L
            z = z xor (z ushr 27)
            z *= -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }

    fun solve(
        boardMask: Long,
        tray: List<TraySlotState>,
        mode: SolverMode = SolverMode.BALANCED_SURVIVAL,
        depth: ThinkingDepth = ThinkingDepth.ULTRA_DEEP,
        profile: GlobalProfile? = null,
        comboStreak: Int = 0,
        placementsSinceClear: Int = 0
    ): MoveRecommendation? {
        val startNs = System.nanoTime()
        val activeSlots = tray.filterIsInstance<TraySlotState.Recognized>()
        if (activeSlots.isEmpty()) return null

        val effectiveProfile = profile ?: when {
            depth == ThinkingDepth.BALANCED_FAST -> GlobalProfile.FASTEST_SPEED
            mode == SolverMode.COMBO_MAXIMIZER -> GlobalProfile.MAX_COMBOS_SCORE
            else -> GlobalProfile.MAX_SURVIVAL
        }
        val effectiveMode = profile?.solverMode ?: mode

        // Determine board tightness tier for adaptive compute budgeting
        val empty = boardMask.inv()
        val occupied = java.lang.Long.bitCount(boardMask)
        val sq3Open = countOpen3x3(empty)
        val bar1x5Open = countOpen1x5(empty)
        val bar5x1Open = countOpen5x1(empty)

        val tier = when {
            occupied > 35 || sq3Open <= 1 || bar1x5Open == 0 || bar5x1Open == 0 -> BoardTier.CRITICAL
            occupied > 21 || sq3Open <= 3 || (effectiveMode == SolverMode.COMBO_MAXIMIZER && comboStreak >= 2) ->
                BoardTier.CONTESTED
            else -> BoardTier.OPEN
        }

        val budgetMs = when (tier) {
            BoardTier.OPEN -> effectiveProfile.openBudgetMs
            BoardTier.CONTESTED -> effectiveProfile.contestedBudgetMs
            BoardTier.CRITICAL -> effectiveProfile.criticalBudgetMs
        }
        val budgetNs = budgetMs * 1_000_000L
        val searchBudgetNs = (budgetNs * 52L) / 100L
        val totalDeadlineNs = startNs + budgetNs

        val branchCapDepth0 = when (tier) {
            BoardTier.CRITICAL -> 44
            BoardTier.CONTESTED -> 34
            BoardTier.OPEN -> if (effectiveProfile == GlobalProfile.FASTEST_SPEED) 22 else 28
        }
        val branchCapDepth1 = when (tier) {
            BoardTier.CRITICAL -> 30
            BoardTier.CONTESTED -> 24
            BoardTier.OPEN -> if (effectiveProfile == GlobalProfile.FASTEST_SPEED) 16 else 20
        }

        val initialCombo = ComboState(comboStreak = comboStreak, placementsSinceClear = placementsSinceClear)
        val permutations = generatePermutations(activeSlots)
        val candidates = ArrayList<CandidatePlan>(512)
        // Transposition tables indexed by 3-bit placed-slot mask (0..7)
        val transTables = Array(8) { PrimitiveTranspositionTable(512) }

        // Always give every permutation its dedicated slice so piece orderings are never skipped
        val perPermBudgetNs = max(8_000_000L, searchBudgetNs / max(1, permutations.size))
        for (perm in permutations) {
            val permDeadlineNs = System.nanoTime() + perPermBudgetNs
            val stepBuf = arrayOfNulls<PlannedPlacement>(perm.size)
            searchPermutation(
                board = boardMask,
                perm = perm,
                depth = 0,
                placedMask = 0,
                steps = stepBuf,
                totalLines = 0,
                multiBonus = 0f,
                comboState = initialCombo,
                comboLost = false,
                lastCleared = false,
                mode = effectiveMode,
                branchCapD0 = branchCapDepth0,
                branchCapD1 = branchCapDepth1,
                deadlineNs = permDeadlineNs,
                transTables = transTables,
                outCandidates = candidates
            )
        }

        // Rescue fallback if placing all 3 pieces is impossible
        if (candidates.isEmpty() && activeSlots.size >= 2) {
            searchPartialTurns(
                boardMask = boardMask,
                activeSlots = activeSlots,
                targetLength = activeSlots.size - 1,
                initialCombo = initialCombo,
                mode = effectiveMode,
                outCandidates = candidates
            )
        }
        if (candidates.isEmpty()) {
            searchPartialTurns(
                boardMask = boardMask,
                activeSlots = activeSlots,
                targetLength = 1,
                initialCombo = initialCombo,
                mode = effectiveMode,
                outCandidates = candidates
            )
        }
        if (candidates.isEmpty()) return null

        // Deduplicate by (first move + endBoard) so rollouts spend cycles on distinct outcomes
        candidates.sortByDescending { it.heuristicScore }
        val maxRolloutCandidates = when (tier) {
            BoardTier.CRITICAL -> if (effectiveProfile == GlobalProfile.FASTEST_SPEED) 14 else 28
            BoardTier.CONTESTED -> if (effectiveProfile == GlobalProfile.FASTEST_SPEED) 12 else 20
            BoardTier.OPEN -> if (effectiveProfile == GlobalProfile.FASTEST_SPEED) 8 else 14
        }
        val uniqueCandidates = ArrayList<CandidatePlan>(maxRolloutCandidates)
        val seenKeys = HashSet<DedupKey>(maxRolloutCandidates * 2)
        for (cand in candidates) {
            val first = cand.steps[0]
            val key = DedupKey(first.slotIndex, first.row, first.col, cand.endBoard)
            if (seenKeys.add(key)) {
                uniqueCandidates.add(cand)
                if (uniqueCandidates.size >= maxRolloutCandidates) break
            }
        }

        val rolloutDraws = when (tier) {
            BoardTier.OPEN -> effectiveProfile.rolloutDrawsOpen
            BoardTier.CONTESTED -> (effectiveProfile.rolloutDrawsOpen + effectiveProfile.rolloutDrawsTight) / 2
            BoardTier.CRITICAL -> effectiveProfile.rolloutDrawsTight
        }

        var bestPlan = uniqueCandidates[0]
        var bestFinalScore = Float.NEGATIVE_INFINITY
        var bestMcRate = 0f
        var bestBulkyCount = 0

        for ((index, cand) in uniqueCandidates.withIndex()) {
            if (index >= 4 && System.nanoTime() >= totalDeadlineNs) break

            val bulkyCount = countBulkyPlacementsFast(cand.endBoard)
            val killerRate = if (effectiveProfile != GlobalProfile.FASTEST_SPEED || tier != BoardTier.OPEN) {
                evaluateKillerReserveRate(cand.endBoard)
            } else {
                0.5f
            }
            val mcEval = runDeepLookaheadRollout(
                endBoard = cand.endBoard,
                endCombo = ComboState(cand.endComboStreak, cand.endPlacementsSinceClear),
                mode = effectiveMode,
                draws = rolloutDraws,
                includeQualityFollowup = (effectiveProfile != GlobalProfile.FASTEST_SPEED || tier != BoardTier.OPEN)
            )

            val survivalWeight = when (effectiveMode) {
                SolverMode.BALANCED_SURVIVAL -> if (tier == BoardTier.CRITICAL) 340f else 245f
                SolverMode.COMBO_MAXIMIZER -> if (tier == BoardTier.CRITICAL) 260f else 170f
            }
            val killerWeight = when (effectiveMode) {
                SolverMode.BALANCED_SURVIVAL -> if (tier == BoardTier.CRITICAL) 135f else 95f
                SolverMode.COMBO_MAXIMIZER -> 70f
            }
            val cleanFollowWeight = if (effectiveMode == SolverMode.BALANCED_SURVIVAL) 75f else 55f

            val finalScore = cand.heuristicScore +
                mcEval.survivalRate * survivalWeight +
                mcEval.followUpQuality * cleanFollowWeight +
                killerRate * killerWeight

            if (finalScore > bestFinalScore) {
                bestFinalScore = finalScore
                bestPlan = cand
                bestMcRate = mcEval.survivalRate
                bestBulkyCount = bulkyCount
            }
        }

        val firstMove = bestPlan.steps[0]
        val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)

        return MoveRecommendation(
            slotIndex = firstMove.slotIndex,
            piece = firstMove.piece,
            targetRow = firstMove.row,
            targetCol = firstMove.col,
            linesCleared = firstMove.linesCleared,
            fullPlan = bestPlan.steps.toList(),
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
        initialCombo: ComboState,
        mode: SolverMode,
        outCandidates: MutableList<CandidatePlan>
    ) {
        if (targetLength <= 0) return
        val perms = if (targetLength == 1) {
            activeSlots.map { listOf(it) }
        } else {
            generatePermutations(activeSlots).map { it.take(targetLength) }.distinct()
        }
        val deadline = System.nanoTime() + 40_000_000L
        val transTables = Array(8) { PrimitiveTranspositionTable(256) }
        for (perm in perms) {
            searchPermutation(
                board = boardMask,
                perm = perm,
                depth = 0,
                placedMask = 0,
                steps = arrayOfNulls(perm.size),
                totalLines = 0,
                multiBonus = 0f,
                comboState = initialCombo,
                comboLost = false,
                lastCleared = false,
                mode = mode,
                branchCapD0 = 32,
                branchCapD1 = 24,
                deadlineNs = deadline,
                transTables = transTables,
                outCandidates = outCandidates
            )
        }
    }

    private fun searchPermutation(
        board: Long,
        perm: List<TraySlotState.Recognized>,
        depth: Int,
        placedMask: Int,
        steps: Array<PlannedPlacement?>,
        totalLines: Int,
        multiBonus: Float,
        comboState: ComboState,
        comboLost: Boolean,
        lastCleared: Boolean,
        mode: SolverMode,
        branchCapD0: Int,
        branchCapD1: Int,
        deadlineNs: Long,
        transTables: Array<PrimitiveTranspositionTable>,
        outCandidates: MutableList<CandidatePlan>
    ) {
        if (depth == perm.size) {
            val topo = evaluateBoardTopology(board, mode)
            val isComboMode = mode == SolverMode.COMBO_MAXIMIZER
            val comboFactor = if (isComboMode) 1.95f else 1.0f

            // Reward maintaining or building a live combo streak; penalize dropping an active streak
            val streakValue = if (isComboMode) {
                comboState.comboStreak * 14f +
                    (if (lastCleared) 34f else 0f) +
                    (if (comboState.placementsSinceClear == 0) 26f else if (comboState.placementsSinceClear == 1) 8f else -24f) -
                    (if (comboLost) 110f else 0f)
            } else {
                (if (lastCleared) 18f else 0f)
            }

            val lineScore = totalLines * 20f * comboFactor + multiBonus * comboFactor
            val score = topo + lineScore + streakValue

            val copied = Array(steps.size) { i -> steps[i]!! }
            outCandidates.add(
                CandidatePlan(
                    steps = copied,
                    endBoard = board,
                    totalLinesCleared = totalLines,
                    multiClearBonus = multiBonus,
                    endComboStreak = comboState.comboStreak,
                    endPlacementsSinceClear = comboState.placementsSinceClear,
                    comboLostInTurn = comboLost,
                    lastMoveClearedLine = lastCleared,
                    heuristicScore = score
                )
            )
            return
        }

        val slot = perm[depth]
        val piece = slot.piece
        val masks = piece.placementMasks
        val rows = piece.placementRows
        val cols = piece.placementCols
        val wallCounts = piece.placementWallCounts
        val pCount = piece.placementCount

        val isLeaf = depth == perm.lastIndex
        val cap = if (depth == 0) branchCapD0 else branchCapD1

        // Primitive parallel arrays eliminate Branch object & ArrayList<Branch> allocations
        val validIdx = IntArray(pCount)
        val validBoards = LongArray(pCount)
        val validLines = IntArray(pCount)
        val validScores = FloatArray(pCount)
        var nValid = 0

        for (i in 0 until pCount) {
            val pm = masks[i]
            if ((board and pm) == 0L) {
                val res = placeAndClear(board, pm)
                val nextBoard = res.boardAfterClear
                val lines = res.linesCleared
                val qScore = if (!isLeaf) {
                    val neighbors = countNeighborsFast(board, pm)
                    val walls = wallCounts[i]
                    val nextOcc = java.lang.Long.bitCount(nextBoard)
                    val clearBoost = if (lines > 0) lines * lines * 22f else 0f
                    val saveStreakBoost = if (lines > 0 && comboState.placementsSinceClear >= 2) 45f else 0f
                    clearBoost + saveStreakBoost - nextOcc * 1.4f + neighbors * 2.5f + walls * 1.9f
                } else {
                    if (lines > 0) lines * 24f else 0f
                }
                validIdx[nValid] = i
                validBoards[nValid] = nextBoard
                validLines[nValid] = lines
                validScores[nValid] = qScore
                nValid++
            }
        }

        if (nValid == 0) return

        // Partial selection sort top-K when branching exceeds cap
        val limit = if (!isLeaf && nValid > cap) cap else nValid
        if (limit < nValid) {
            for (k in 0 until limit) {
                var best = k
                var bestS = validScores[k]
                for (j in k + 1 until nValid) {
                    if (validScores[j] > bestS) {
                        bestS = validScores[j]
                        best = j
                    }
                }
                if (best != k) {
                    val ti = validIdx[k]; validIdx[k] = validIdx[best]; validIdx[best] = ti
                    val tb = validBoards[k]; validBoards[k] = validBoards[best]; validBoards[best] = tb
                    val tl = validLines[k]; validLines[k] = validLines[best]; validLines[best] = tl
                    val ts = validScores[k]; validScores[k] = validScores[best]; validScores[best] = ts
                }
            }
        }

        val nextPlacedMask = placedMask or (1 shl slot.slotIndex)
        for (k in 0 until limit) {
            if (outCandidates.isNotEmpty() && System.nanoTime() > deadlineNs) break

            val pIdx = validIdx[k]
            val nextBoard = validBoards[k]
            val lines = validLines[k]
            val multi = if (lines >= 2) lines * lines * 22f else 0f
            val nextCombo = ComboTracker.onPlacementCleared(comboState, lines)
            val lostStreak = comboLost || (comboState.comboStreak > 0 && nextCombo.comboStreak == 0)

            // Transposition check on intermediate depths (avoid re-expanding identical board + placed-slot sets)
            if (!isLeaf && nextPlacedMask in 1..6) {
                val comboTerm = (if (lostStreak) -140f else 0f) +
                    (if (nextCombo.placementsSinceClear == 0) 24f else 0f)
                val runningScore = (totalLines + lines) * 20f + multiBonus + multi +
                    nextCombo.comboStreak * 8f + comboTerm -
                    java.lang.Long.bitCount(nextBoard) * 1.2f
                if (!transTables[nextPlacedMask].shouldExpand(nextBoard, runningScore)) {
                    continue
                }
            }

            steps[depth] = PlannedPlacement(
                slotIndex = slot.slotIndex,
                piece = piece,
                row = rows[pIdx],
                col = cols[pIdx],
                linesCleared = lines,
                resultingBoard = nextBoard
            )

            searchPermutation(
                board = nextBoard,
                perm = perm,
                depth = depth + 1,
                placedMask = nextPlacedMask,
                steps = steps,
                totalLines = totalLines + lines,
                multiBonus = multiBonus + multi,
                comboState = nextCombo,
                comboLost = lostStreak,
                lastCleared = lines > 0,
                mode = mode,
                branchCapD0 = branchCapD0,
                branchCapD1 = branchCapD1,
                deadlineNs = deadlineNs,
                transTables = transTables,
                outCandidates = outCandidates
            )
        }
    }

    /** Unrolled 64-bit bitwise line clear — zero loops, zero allocations. */
    fun placeAndClear(board: Long, placedMask: Long): PlaceResult {
        val occ = board or placedMask
        var clearMask = 0L
        var lines = 0

        val r0 = ROW_MASKS[0]; if ((occ and r0) == r0) { clearMask = clearMask or r0; lines++ }
        val r1 = ROW_MASKS[1]; if ((occ and r1) == r1) { clearMask = clearMask or r1; lines++ }
        val r2 = ROW_MASKS[2]; if ((occ and r2) == r2) { clearMask = clearMask or r2; lines++ }
        val r3 = ROW_MASKS[3]; if ((occ and r3) == r3) { clearMask = clearMask or r3; lines++ }
        val r4 = ROW_MASKS[4]; if ((occ and r4) == r4) { clearMask = clearMask or r4; lines++ }
        val r5 = ROW_MASKS[5]; if ((occ and r5) == r5) { clearMask = clearMask or r5; lines++ }
        val r6 = ROW_MASKS[6]; if ((occ and r6) == r6) { clearMask = clearMask or r6; lines++ }
        val r7 = ROW_MASKS[7]; if ((occ and r7) == r7) { clearMask = clearMask or r7; lines++ }

        val c0 = COL_MASKS[0]; if ((occ and c0) == c0) { clearMask = clearMask or c0; lines++ }
        val c1 = COL_MASKS[1]; if ((occ and c1) == c1) { clearMask = clearMask or c1; lines++ }
        val c2 = COL_MASKS[2]; if ((occ and c2) == c2) { clearMask = clearMask or c2; lines++ }
        val c3 = COL_MASKS[3]; if ((occ and c3) == c3) { clearMask = clearMask or c3; lines++ }
        val c4 = COL_MASKS[4]; if ((occ and c4) == c4) { clearMask = clearMask or c4; lines++ }
        val c5 = COL_MASKS[5]; if ((occ and c5) == c5) { clearMask = clearMask or c5; lines++ }
        val c6 = COL_MASKS[6]; if ((occ and c6) == c6) { clearMask = clearMask or c6; lines++ }
        val c7 = COL_MASKS[7]; if ((occ and c7) == c7) { clearMask = clearMask or c7; lines++ }

        return PlaceResult(occ and clearMask.inv(), lines)
    }

    /**
     * Pure 64-bit bitwise topology evaluation:
     * 1. Exact O(1) 3x3, 1x5, 5x1, 2x2 bulky-piece capacity via shift masks.
     * 2. Exact O(1) 4-wall isolated holes, 3-wall semi-trapped dead-ends, and edge roughness.
     * 3. Connected-component cavity fragmentation + center sanctuary preservation + rescue 7/8 lines.
     */
    fun evaluateBoardTopology(board: Long, mode: SolverMode): Float {
        val empty = board.inv()
        val occupiedCount = java.lang.Long.bitCount(board)
        val emptyCount = 64 - occupiedCount

        // 1. O(1) exact bitwise open bulky counts
        val h2 = empty and ((empty ushr 1) and NOT_COL_7)
        val h3 = h2 and ((h2 ushr 1) and NOT_COL_7)
        val h4 = h3 and ((h3 ushr 1) and NOT_COL_7)
        val h5 = h4 and ((h4 ushr 1) and NOT_COL_7)

        val v2 = empty and (empty ushr 8)
        val v3 = v2 and (v2 ushr 8)
        val v4 = v3 and (v3 ushr 8)
        val v5 = v4 and (v4 ushr 8)

        val sq2Mask = h2 and (h2 ushr 8)
        val sq3Mask = h3 and (h3 ushr 8) and (h3 ushr 16)

        val sq3x3 = java.lang.Long.bitCount(sq3Mask)
        val bar1x5 = java.lang.Long.bitCount(h5)
        val bar5x1 = java.lang.Long.bitCount(v5)
        val sq2x2 = java.lang.Long.bitCount(sq2Mask)
        val lBulky = countPlacements(board, C3_TL) + countPlacements(board, C3_BR)

        val survivalFactor = if (mode == SolverMode.BALANCED_SURVIVAL) 1.55f else 1.0f
        var bulkyScore = (sq3x3 * 11f + bar1x5 * 6f + bar5x1 * 6f + sq2x2 * 2.4f + lBulky * 2.5f) * survivalFactor
        if (sq3x3 == 0) bulkyScore -= 145f * survivalFactor
        else if (sq3x3 == 1) bulkyScore -= 32f * survivalFactor
        if (bar1x5 == 0) bulkyScore -= 64f * survivalFactor
        if (bar5x1 == 0) bulkyScore -= 64f * survivalFactor
        if (sq2x2 == 0) bulkyScore -= 95f * survivalFactor

        // 2. O(1) pure bitwise 4-wall & 3-wall trapped cell + transition counts
        val topWall = (board shl 8) or ROW_0_MASK
        val botWall = (board ushr 8) or ROW_7_MASK
        val leftWall = ((board shl 1) and NOT_COL_0) or COL_0_MASK
        val rightWall = ((board ushr 1) and NOT_COL_7) or COL_7_MASK

        val ab = topWall and botWall
        val axb = topWall xor botWall
        val cd = leftWall and rightWall
        val cxd = leftWall xor rightWall

        val isolatedHoles = java.lang.Long.bitCount(empty and ab and cd)
        val semiTrapped = java.lang.Long.bitCount(empty and ((ab and cxd) or (axb and cd)))

        val roughEdges = java.lang.Long.bitCount((board xor (board ushr 1)) and NOT_COL_7) +
            java.lang.Long.bitCount((board xor (board ushr 8)) and NOT_ROW_7)

        // Edge compaction & center open territory
        val centerClutter = java.lang.Long.bitCount(board and CENTER_4X4_MASK)
        val borderCompact = java.lang.Long.bitCount(board and BORDER_RING_MASK)

        // 3. Fast 64-bit flood-fill connected empty cavity analysis
        var unvisitedEmpty = empty
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
                val left = (region and NOT_COL_0) ushr 1
                val right = (region and NOT_COL_7) shl 1
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

        // Near-clear rescue lines (rows/cols at 7/8 or 6/8 ready for instant escape)
        var nearClearBonus = 0f
        for (i in 0 until 8) {
            val rowBits = java.lang.Long.bitCount(board and ROW_MASKS[i])
            val colBits = java.lang.Long.bitCount(board and COL_MASKS[i])
            if (rowBits == 7) nearClearBonus += 8.5f else if (rowBits == 6) nearClearBonus += 3.2f
            if (colBits == 7) nearClearBonus += 8.5f else if (colBits == 6) nearClearBonus += 3.2f
        }

        val fragmentationPenalty = (tiny1Regions * 36f) +
            (tiny2Regions * 22f) +
            (small34Regions * 11f) +
            (max(0, componentCount - 1) * 9f)

        return emptyCount * 2.6f +
            largestRegion * 1.55f +
            bulkyScore +
            nearClearBonus +
            borderCompact * 0.7f -
            centerClutter * 0.9f -
            fragmentationPenalty -
            isolatedHoles * 26f -
            semiTrapped * 7.8f -
            roughEdges * 1.3f
    }

    private fun countOpen3x3(empty: Long): Int {
        val h2 = empty and ((empty ushr 1) and NOT_COL_7)
        val h3 = h2 and ((h2 ushr 1) and NOT_COL_7)
        return java.lang.Long.bitCount(h3 and (h3 ushr 8) and (h3 ushr 16))
    }

    private fun countOpen1x5(empty: Long): Int {
        val h2 = empty and ((empty ushr 1) and NOT_COL_7)
        val h3 = h2 and ((h2 ushr 1) and NOT_COL_7)
        val h4 = h3 and ((h3 ushr 1) and NOT_COL_7)
        return java.lang.Long.bitCount(h4 and ((h4 ushr 1) and NOT_COL_7))
    }

    private fun countOpen5x1(empty: Long): Int {
        val v2 = empty and (empty ushr 8)
        val v3 = v2 and (v2 ushr 8)
        val v4 = v3 and (v3 ushr 8)
        return java.lang.Long.bitCount(v4 and (v4 ushr 8))
    }

    private fun countOpen2x2(empty: Long): Int {
        val h2 = empty and ((empty ushr 1) and NOT_COL_7)
        return java.lang.Long.bitCount(h2 and (h2 ushr 8))
    }

    private fun evaluateKillerReserveRate(endBoard: Long): Float {
        val stressSets = arrayOf(
            arrayOf(SQ_3X3, BAR_1X5, BAR_5X1),
            arrayOf(SQ_3X3, C3_TL, SQ_2X2),
            arrayOf(SQ_3X3, C3_BR, RECT_3X2),
            arrayOf(BAR_1X5, BAR_5X1, RECT_2X3)
        )
        var survived = 0
        for (set in stressSets) {
            if (canSurviveAnyOrder(endBoard, set[0], set[1], set[2])) survived++
        }
        return survived.toFloat() / stressSets.size
    }

    private fun countBulkyPlacementsFast(board: Long): Int {
        val empty = board.inv()
        return countOpen3x3(empty) +
            countOpen1x5(empty) +
            countOpen5x1(empty) +
            countPlacements(board, C3_TL) +
            countPlacements(board, C3_TR) +
            countPlacements(board, C3_BL) +
            countPlacements(board, C3_BR)
    }

    private fun countPlacements(board: Long, piece: PieceShape): Int {
        var count = 0
        val masks = piece.placementMasks
        for (i in masks.indices) {
            if ((board and masks[i]) == 0L) count++
        }
        return count
    }

    private data class RolloutEval(val survivalRate: Float, val followUpQuality: Float)

    /**
     * 2-turn lookahead rollout:
     * Tests survival across [draws] realistic future 3-piece bags AND evaluates the cleanliness,
     * bulky reserves, and combo-continuation of the resulting Turn N+1 board.
     */
    private fun runDeepLookaheadRollout(
        endBoard: Long,
        endCombo: ComboState,
        mode: SolverMode,
        draws: Int,
        includeQualityFollowup: Boolean
    ): RolloutEval {
        if (draws <= 0) return RolloutEval(0.5f, 0.5f)
        val rng = Random(endBoard xor 0x5DEECE66DL)
        var survived = 0
        var qualitySum = 0f

        for (d in 0 until draws) {
            // Mix realistic balanced bags (2 standard + 1 varied/large) with full weighted bags
            val p1: PieceShape
            val p2: PieceShape
            val p3: PieceShape
            if ((d and 1) == 0) {
                p1 = PieceCatalog.SMALL_MEDIUM_PIECES[rng.nextInt(PieceCatalog.SMALL_MEDIUM_PIECES.size)]
                p2 = sampleWeightedPiece(rng)
                p3 = PieceCatalog.LARGE_CHALLENGE_PIECES[rng.nextInt(PieceCatalog.LARGE_CHALLENGE_PIECES.size)]
            } else {
                p1 = sampleWeightedPiece(rng)
                p2 = sampleWeightedPiece(rng)
                p3 = sampleWeightedPiece(rng)
            }

            if (!includeQualityFollowup) {
                if (canSurviveAnyOrder(endBoard, p1, p2, p3)) survived++
            } else {
                val followUp = evaluateBestFutureTurn(endBoard, p1, p2, p3, endCombo, mode)
                if (followUp >= 0f) {
                    survived++
                    qualitySum += followUp
                }
            }
        }

        val rate = survived.toFloat() / draws
        val avgQ = if (survived > 0 && includeQualityFollowup) (qualitySum / survived) else rate
        return RolloutEval(rate, avgQ)
    }

    /**
     * Evaluates a sampled future 3-piece bag on [board]. Returns `-1f` if unplaceable (death),
     * or `0f..1f` normalized cleanliness + combo-continuation score of the best surviving Turn-2 board.
     */
    private fun evaluateBestFutureTurn(
        board: Long,
        a: PieceShape,
        b: PieceShape,
        c: PieceShape,
        combo: ComboState,
        mode: SolverMode
    ): Float {
        // Test the 3 diverse anchor orderings first (a,b,c), (b,c,a), (c,a,b) plus reversed when needed
        val orders = arrayOf(
            arrayOf(a, b, c),
            arrayOf(c, a, b),
            arrayOf(b, c, a),
            arrayOf(a, c, b),
            arrayOf(c, b, a),
            arrayOf(b, a, c)
        )

        var bestBoard = -1L
        var bestQuickScore = Float.NEGATIVE_INFINITY
        var bestClearedAny = false

        for (ord in orders) {
            val res = greedyBestSequenceForOrder(board, ord)
            if (res != null) {
                val postEmpty = res.board.inv()
                val q = (64 - java.lang.Long.bitCount(res.board)) * 1.4f +
                    (if (countOpen3x3(postEmpty) > 0) 18f else -25f) +
                    (if (countOpen2x2(postEmpty) > 0) 10f else -18f) +
                    (if (res.clearedLines > 0 && mode == SolverMode.COMBO_MAXIMIZER) 18f else 0f)
                if (q > bestQuickScore) {
                    bestQuickScore = q
                    bestBoard = res.board
                    bestClearedAny = res.clearedLines > 0
                }
                // Once we already found a very clean bulky survival sequence, stop scanning swaps
                if (bestQuickScore > 58f) break
            }
        }
        if (bestBoard == -1L) return -1f

        val postEmpty = bestBoard.inv()
        val emptyCells = java.lang.Long.bitCount(postEmpty)
        val has3x3 = if (countOpen3x3(postEmpty) > 0) 0.25f else 0f
        val hasBar5 = if (countOpen1x5(postEmpty) > 0 && countOpen5x1(postEmpty) > 0) 0.15f else 0f
        val comboKeep = if (bestClearedAny && (mode == SolverMode.COMBO_MAXIMIZER || combo.comboStreak > 0)) 0.20f else 0f
        val openness = (emptyCells / 52f).coerceIn(0f, 1f) * 0.40f
        return (openness + has3x3 + hasBar5 + comboKeep).coerceIn(0f, 1f)
    }

    private data class FastTurnRes(val board: Long, val clearedLines: Int)

    private fun greedyBestSequenceForOrder(board0: Long, pieces: Array<PieceShape>): FastTurnRes? {
        var cur = board0
        var totalLines = 0
        for (idx in 0..2) {
            val p = pieces[idx]
            val masks = p.placementMasks
            var bestNext = -1L
            var bestClear = 0
            var bestEval = Float.NEGATIVE_INFINITY
            var fallbackNext = -1L
            var fallbackClear = 0

            // Sample top 12 legal placements fast (prioritize clears & low bitCount)
            var checks = 0
            for (i in masks.indices) {
                val pm = masks[i]
                if ((cur and pm) == 0L) {
                    val res = placeAndClear(cur, pm)
                    if (fallbackNext == -1L) {
                        fallbackNext = res.boardAfterClear
                        fallbackClear = res.linesCleared
                    }
                    val occ = java.lang.Long.bitCount(res.boardAfterClear)
                    val s = res.linesCleared * 24f - occ * 1.2f + countNeighborsFast(cur, pm) * 1.8f
                    if (s > bestEval) {
                        bestEval = s
                        bestNext = res.boardAfterClear
                        bestClear = res.linesCleared
                    }
                    checks++
                    if (checks >= 14) break
                }
            }
            if (bestNext != -1L) {
                cur = bestNext
                totalLines += bestClear
            } else if (fallbackNext != -1L) {
                cur = fallbackNext
                totalLines += fallbackClear
            } else {
                // Greedy failed; fall back to exact recursive feasibility check
                if (canPlaceSequenceFast(board0, pieces, 0)) {
                    return FastTurnRes(board0, 0)
                }
                return null
            }
        }
        return FastTurnRes(cur, totalLines)
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
        val masks = pieces[idx].placementMasks
        for (i in masks.indices) {
            val pm = masks[i]
            if ((board and pm) == 0L) {
                val next = placeAndClear(board, pm).boardAfterClear
                if (canPlaceSequenceFast(next, pieces, idx + 1)) return true
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
        return PieceCatalog.ALL[0]
    }

    private fun countNeighborsFast(board: Long, pm: Long): Int {
        val up = (pm ushr 8) and board
        val down = (pm shl 8) and board
        val left = ((pm and NOT_COL_0) ushr 1) and board
        val right = ((pm and NOT_COL_7) shl 1) and board
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
