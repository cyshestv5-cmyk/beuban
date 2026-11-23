package com.example.engine

sealed interface DropVerificationResult {
    /** The dragged tray slot cleared and the board updated cleanly. */
    data class Verified(val liftOffsetCorrectionCells: Float? = null) : DropVerificationResult

    /** All 3 tray slots refreshed to a fresh 3-piece turn right after placing the last piece. */
    data object NewTurnSpawned : DropVerificationResult

    /** The dragged piece is still sitting untouched in the same tray slot. */
    data object NotApplied : DropVerificationResult
}

/**
 * Pure-Kotlin closed-loop post-drop verification and self-calibration helper.
 * Compares the pre-drag board/tray state against the post-drop settled frame.
 */
object AutoplayMoveVerifier {

    fun verifyDrop(
        preBoardMask: Long,
        preTray: List<TraySlotState>,
        executed: MoveRecommendation,
        postBoardMask: Long,
        postTray: List<TraySlotState>
    ): DropVerificationResult {
        val preOccupiedCount = preTray.count { it is TraySlotState.Recognized }
        val postOccupiedCount = postTray.count { it is TraySlotState.Recognized }

        // If 1 piece was left and now 3 pieces are present, the drop succeeded and spawned a new turn
        if (preOccupiedCount == 1 && postOccupiedCount == 3) {
            return DropVerificationResult.NewTurnSpawned
        }

        val postSlot = postTray.getOrNull(executed.slotIndex)
        if (postSlot is TraySlotState.Empty) {
            val correction = detectVerticalDriftCorrection(
                preBoardMask = preBoardMask,
                postBoardMask = postBoardMask,
                executed = executed
            )
            return DropVerificationResult.Verified(correction)
        }

        // Handle edge case where tray signature shifted (e.g., all 3 pieces replaced with another 3)
        val preIds = preTray.map { (it as? TraySlotState.Recognized)?.piece?.id }
        val postIds = postTray.map { (it as? TraySlotState.Recognized)?.piece?.id }
        if (preIds != postIds && postOccupiedCount >= 3) {
            return DropVerificationResult.NewTurnSpawned
        }

        if (postBoardMask != preBoardMask && postSlot is TraySlotState.Recognized &&
            postSlot.piece.id != executed.piece.id
        ) {
            return DropVerificationResult.NewTurnSpawned
        }

        return DropVerificationResult.NotApplied
    }

    /**
     * When the piece landed without triggering a line clear, checks if the piece snapped
     * cleanly to a neighboring row (within +/- 2 rows) due to Block Blast drag-lift offset,
     * and returns a damped step adjustment in cell-height units.
     */
    fun detectVerticalDriftCorrection(
        preBoardMask: Long,
        postBoardMask: Long,
        executed: MoveRecommendation
    ): Float? {
        val expectedAfterTarget = BitboardSolver.placeAndClear(
            preBoardMask,
            executed.piece.maskAt(executed.targetRow, executed.targetCol)
        ).boardAfterClear

        // Exact hit!
        if (postBoardMask == expectedAfterTarget) return null

        // Only estimate geometric row drift when no lines cleared (bitCount grew by exactly cellCount)
        val delta = postBoardMask and preBoardMask.inv()
        if ((preBoardMask and postBoardMask) != preBoardMask) return null
        if (java.lang.Long.bitCount(delta) != executed.piece.cellCount) return null

        val maxR = 8 - executed.piece.height
        val maxC = 8 - executed.piece.width
        for (r in 0..maxR) {
            for (c in 0..maxC) {
                if (executed.piece.maskAt(r, c) == delta) {
                    val rowDiff = r - executed.targetRow
                    // If piece landed higher (rowDiff < 0, e.g. -1), finger must aim lower (+liftOffset)
                    // So correction = -rowDiff (damped 0.85x to avoid oscillation)
                    if (rowDiff != 0 && rowDiff in -2..2) {
                        return (-rowDiff) * 0.85f
                    }
                    return null
                }
            }
        }
        return null
    }
}
