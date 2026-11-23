package com.example.engine

import kotlin.math.abs

data class CalibrationUpdate(
    val updatedConfig: AdaptiveRoiConfig,
    val rowError: Int,
    val colError: Int,
    val summary: String
)

sealed interface DropVerificationResult {
    /** The dragged tray slot cleared and the board updated cleanly. */
    data class Verified(val calibrationUpdate: CalibrationUpdate? = null) : DropVerificationResult

    /** All 3 tray slots refreshed to a fresh 3-piece turn right after placing the last piece. */
    data object NewTurnSpawned : DropVerificationResult

    /** The dragged piece is still sitting untouched in the same tray slot. */
    data object NotApplied : DropVerificationResult
}

/**
 * Pure-Kotlin closed-loop post-drop verification and real-time LMS movement calibration helper.
 * Compares the pre-drag board/tray state against the post-drop settled frame and calibrates both
 * vertical/horizontal gain multipliers and static finger-lift offset for the exact phone display.
 */
object AutoplayMoveVerifier {

    fun verifyDrop(
        config: AdaptiveRoiConfig,
        screenWidthPx: Int,
        screenHeightPx: Int,
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
            val update = calibrateFromLandedPlacement(
                config = config,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                preBoardMask = preBoardMask,
                postBoardMask = postBoardMask,
                executed = executed
            )
            return DropVerificationResult.Verified(update)
        }

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
     * Measures exact landing error (rowError, colError) across the entire 8x8 board when no lines cleared,
     * updating both static lift offset and upward vertical gain multiplier to eliminate multi-block overshoots.
     */
    fun calibrateFromLandedPlacement(
        config: AdaptiveRoiConfig,
        screenWidthPx: Int,
        screenHeightPx: Int,
        preBoardMask: Long,
        postBoardMask: Long,
        executed: MoveRecommendation
    ): CalibrationUpdate? {
        val expectedAfterTarget = BitboardSolver.placeAndClear(
            preBoardMask,
            executed.piece.maskAt(executed.targetRow, executed.targetCol)
        ).boardAfterClear

        val newSamples = config.dragCalibrationSamples + 1

        // Exact placement match! Record confirmation sample and lock device screen size.
        if (postBoardMask == expectedAfterTarget) {
            val confirmed = config.copy(
                calibratedScreenWidthPx = screenWidthPx,
                calibratedScreenHeightPx = screenHeightPx,
                dragCalibrationSamples = newSamples
            )
            return CalibrationUpdate(
                updatedConfig = confirmed,
                rowError = 0,
                colError = 0,
                summary = "Locked exact (${formatF(confirmed.dragLiftOffsetCells)}c, ${formatF(confirmed.dragVerticalGain)}x)"
            )
        }

        // Identify exact landed coordinates when no line clear occurred
        val delta = postBoardMask and preBoardMask.inv()
        if ((preBoardMask and postBoardMask) != preBoardMask) return null
        if (java.lang.Long.bitCount(delta) != executed.piece.cellCount) return null

        val maxR = 8 - executed.piece.height
        val maxC = 8 - executed.piece.width
        var actualRow = -1
        var actualCol = -1
        for (r in 0..maxR) {
            for (c in 0..maxC) {
                if (executed.piece.maskAt(r, c) == delta) {
                    actualRow = r
                    actualCol = c
                    break
                }
            }
            if (actualRow >= 0) break
        }
        if (actualRow < 0) return null

        val rowError = actualRow - executed.targetRow // negative => landed TOO HIGH
        val colError = actualCol - executed.targetCol

        val endpoints = config.computeDragEndpoints(
            slotIndex = executed.slotIndex,
            targetRow = executed.targetRow,
            targetCol = executed.targetCol,
            pieceWidth = executed.piece.width,
            pieceHeight = executed.piece.height,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )
        val boardHeightPx = endpoints.cellHeightPx * 8f
        val upwardTravelNorm = ((endpoints.startY - endpoints.baseTargetY) / boardHeightPx.coerceAtLeast(1f))
            .coerceAtLeast(0.20f)

        var newLift = config.dragLiftOffsetCells
        var newGainY = config.dragVerticalGain
        var newGainX = config.dragHorizontalGain

        if (rowError != 0) {
            // NLMS split between static lift offset and upward vertical travel gain so both short and tall drags converge
            val liftCorrection = (-rowError) * 0.52f
            val gainCorrection = (-rowError) * upwardTravelNorm * 0.095f * config.dragVerticalGain
            newLift = (config.dragLiftOffsetCells + liftCorrection).coerceIn(-1.0f, 4.8f)
            newGainY = (config.dragVerticalGain + gainCorrection).coerceIn(0.75f, 1.95f)
        }

        if (colError != 0) {
            val targetCenterX = (config.gridLeft + (executed.targetCol + executed.piece.width * 0.5f) * config.cellWidth()) * screenWidthPx
            val dx = targetCenterX - endpoints.startX
            if (abs(dx) > endpoints.cellHeightPx * 1.1f) {
                val cellErrorX = colError * endpoints.cellHeightPx
                val gainXRatio = (dx - cellErrorX) / dx
                val targetGainX = config.dragHorizontalGain / gainXRatio.coerceIn(0.65f, 1.45f)
                newGainX = (config.dragHorizontalGain * 0.6f + targetGainX * 0.4f).coerceIn(0.75f, 1.35f)
            }
        }

        val updated = config.copy(
            dragLiftOffsetCells = newLift,
            dragVerticalGain = newGainY,
            dragHorizontalGain = newGainX,
            calibratedScreenWidthPx = screenWidthPx,
            calibratedScreenHeightPx = screenHeightPx,
            dragCalibrationSamples = newSamples
        )
        return CalibrationUpdate(
            updatedConfig = updated,
            rowError = rowError,
            colError = colError,
            summary = "Calibrated Δr=$rowError → ${formatF(newLift)}c, ${formatF(newGainY)}x"
        )
    }

    /**
     * When a drag did not register (NotApplied), e.g., an upper-board placement overshot off Row 0,
     * backs off upward travel so the retry lands cleanly inside the board.
     */
    fun recoverFromFailedDrop(
        config: AdaptiveRoiConfig,
        executed: MoveRecommendation
    ): AdaptiveRoiConfig {
        return if (executed.targetRow <= 4) {
            config.copy(
                dragVerticalGain = (config.dragVerticalGain + 0.09f).coerceIn(0.75f, 1.95f),
                dragLiftOffsetCells = (config.dragLiftOffsetCells + 0.55f).coerceIn(-1.0f, 4.8f)
            )
        } else if (config.dragLiftOffsetCells > 2.6f) {
            config.copy(
                dragLiftOffsetCells = (config.dragLiftOffsetCells - 0.35f).coerceIn(-1.0f, 4.8f)
            )
        } else {
            config.copy(
                dragVerticalGain = (config.dragVerticalGain + 0.05f).coerceIn(0.75f, 1.95f)
            )
        }
    }

    private fun formatF(v: Float): String {
        val rounded = kotlin.math.round(v * 100f) / 100f
        return rounded.toString()
    }
}
