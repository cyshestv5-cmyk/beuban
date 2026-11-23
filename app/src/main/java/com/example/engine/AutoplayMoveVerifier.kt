package com.example.engine

import kotlin.math.abs

data class CalibrationUpdate(
    val updatedConfig: AdaptiveRoiConfig,
    val rowError: Int,
    val colError: Int,
    val summary: String
)

data class PreviewCalibrationSample(
    val slotIndex: Int,
    val startX: Float,
    val startY: Float,
    val fingerX: Float,
    val fingerY: Float,
    val observedRow: Int,
    val observedCol: Int,
    val pieceWidth: Int,
    val pieceHeight: Int
)

sealed interface DropVerificationResult {
    /** The dragged tray slot cleared and the board updated cleanly. */
    data class Verified(
        val linesCleared: Int = 0,
        val calibrationUpdate: CalibrationUpdate? = null
    ) : DropVerificationResult

    /** All 3 tray slots refreshed to a fresh 3-piece turn right after placing the last piece. */
    data class NewTurnSpawned(val linesCleared: Int = 0) : DropVerificationResult

    /** The dragged piece is still sitting untouched in the same tray slot. */
    data object NotApplied : DropVerificationResult
}

/**
 * Pure-Kotlin closed-loop calibration & post-drop verification engine.
 * Combines:
 * 1. Exact 4-parameter affine OLS regression from Guided 3-Point Preview Calibration samples.
 * 2. Live Preview-Snap single-hover offset correction prior to lifting the finger.
 * 3. Closed-loop post-drop NLMS self-calibration & combo line-clear counting.
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

        val inferredLinesCleared = inferLinesCleared(preBoardMask, postBoardMask, executed)

        // If 1 piece was left and now 3 pieces are present, the drop succeeded and spawned a new turn
        if (preOccupiedCount == 1 && postOccupiedCount == 3) {
            return DropVerificationResult.NewTurnSpawned(linesCleared = inferredLinesCleared)
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
            return DropVerificationResult.Verified(
                linesCleared = inferredLinesCleared,
                calibrationUpdate = update
            )
        }

        val preIds = preTray.map { (it as? TraySlotState.Recognized)?.piece?.id }
        val postIds = postTray.map { (it as? TraySlotState.Recognized)?.piece?.id }
        if (preIds != postIds && postOccupiedCount >= 3) {
            return DropVerificationResult.NewTurnSpawned(linesCleared = inferredLinesCleared)
        }

        if (postBoardMask != preBoardMask && postSlot is TraySlotState.Recognized &&
            postSlot.piece.id != executed.piece.id
        ) {
            return DropVerificationResult.NewTurnSpawned(linesCleared = inferredLinesCleared)
        }

        return DropVerificationResult.NotApplied
    }

    /**
     * Infers exact lines cleared by checking the executed move first, then any single legal delta placement
     * if the piece drifted slightly before settling.
     */
    private fun inferLinesCleared(
        preBoardMask: Long,
        postBoardMask: Long,
        executed: MoveRecommendation
    ): Int {
        val planned = BitboardSolver.placeAndClear(
            preBoardMask,
            executed.piece.maskAt(executed.targetRow, executed.targetCol)
        )
        if (planned.boardAfterClear == postBoardMask) {
            return planned.linesCleared
        }

        // Scan legal placements of the executed piece in case it drifted a cell and cleared a different line
        val p = executed.piece
        for (i in 0 until p.placementCount) {
            val pm = p.placementMasks[i]
            if ((preBoardMask and pm) == 0L) {
                val res = BitboardSolver.placeAndClear(preBoardMask, pm)
                if (res.boardAfterClear == postBoardMask) {
                    return res.linesCleared
                }
            }
        }
        return executed.linesCleared
    }

    /**
     * Fits the full 4-parameter affine drag model from multi-point live ghost-preview observations
     * using Ordinary Least Squares:
     *   obsY = dragVerticalGain * uY - dragLiftOffsetCells
     *   obsX = dragHorizontalGain * uX - dragHorizontalOffsetCells
     */
    fun fitAffineFromPreviewSamples(
        config: AdaptiveRoiConfig,
        screenWidthPx: Int,
        screenHeightPx: Int,
        samples: List<PreviewCalibrationSample>
    ): CalibrationUpdate? {
        if (samples.isEmpty() || screenWidthPx <= 0 || screenHeightPx <= 0) return null

        val cellWPx = (config.cellWidth() * screenWidthPx).coerceAtLeast(1f)
        val cellHPx = (config.cellHeight() * screenHeightPx).coerceAtLeast(1f)

        val uXs = FloatArray(samples.size)
        val obsXs = FloatArray(samples.size)
        val uYs = FloatArray(samples.size)
        val obsYs = FloatArray(samples.size)

        for (i in samples.indices) {
            val s = samples[i]
            val obsCenterX = (config.gridLeft + (s.observedCol + s.pieceWidth * 0.5f) * config.cellWidth()) * screenWidthPx
            val obsCenterY = (config.gridTop + (s.observedRow + s.pieceHeight * 0.5f) * config.cellHeight()) * screenHeightPx

            uXs[i] = (s.fingerX - s.startX) / cellWPx
            obsXs[i] = (obsCenterX - s.startX) / cellWPx

            uYs[i] = (s.fingerY - s.startY) / cellHPx
            obsYs[i] = (obsCenterY - s.startY) / cellHPx
        }

        val meanUX = uXs.average().toFloat()
        val meanObsX = obsXs.average().toFloat()
        val meanUY = uYs.average().toFloat()
        val meanObsY = obsYs.average().toFloat()

        var varUY = 0f
        var covUY = 0f
        var varUX = 0f
        var covUX = 0f
        for (i in samples.indices) {
            val duY = uYs[i] - meanUY
            varUY += duY * duY
            covUY += duY * (obsYs[i] - meanObsY)

            val duX = uXs[i] - meanUX
            varUX += duX * duX
            covUX += duX * (obsXs[i] - meanObsX)
        }

        // Ridge-regularized OLS absorbs +/-0.5 cell integer grid-snap quantization across 3 probes
        val lambdaY = 1.5f
        val fittedGainY = if (varUY > 0.25f) {
            ((covUY + lambdaY * config.dragVerticalGain) / (varUY + lambdaY)).coerceIn(0.65f, 1.95f)
        } else {
            config.dragVerticalGain
        }
        val fittedLift = (fittedGainY * meanUY - meanObsY).coerceIn(-1.0f, 4.8f)

        val lambdaX = 6.0f
        val fittedGainX = if (varUX > 0.25f) {
            ((covUX + lambdaX * config.dragHorizontalGain) / (varUX + lambdaX)).coerceIn(0.75f, 1.35f)
        } else {
            config.dragHorizontalGain
        }
        val fittedOffsetX = (fittedGainX * meanUX - meanObsX).coerceIn(-2.5f, 2.5f)

        val locked = config.copy(
            dragLiftOffsetCells = fittedLift,
            dragVerticalGain = fittedGainY,
            dragHorizontalOffsetCells = fittedOffsetX,
            dragHorizontalGain = fittedGainX,
            calibratedScreenWidthPx = screenWidthPx,
            calibratedScreenHeightPx = screenHeightPx,
            dragCalibrationSamples = config.dragCalibrationSamples + samples.size,
            isPreviewLocked = true
        )

        return CalibrationUpdate(
            updatedConfig = locked,
            rowError = 0,
            colError = 0,
            summary = "Preview-Locked (${formatF(fittedLift)}c, ${formatF(fittedGainY)}x, Δx=${formatF(fittedOffsetX)}c)"
        )
    }

    /**
     * Adjusts lift & horizontal offset slightly from a single live hover observation before release,
     * keeping subsequent pieces even tighter on first arrival.
     */
    fun adjustFromSingleHoverObservation(
        config: AdaptiveRoiConfig,
        screenWidthPx: Int,
        screenHeightPx: Int,
        targetRow: Int,
        targetCol: Int,
        observedRow: Int,
        observedCol: Int
    ): AdaptiveRoiConfig {
        val rowErr = observedRow - targetRow
        val colErr = observedCol - targetCol
        if (rowErr == 0 && colErr == 0) {
            return config.copy(
                calibratedScreenWidthPx = screenWidthPx,
                calibratedScreenHeightPx = screenHeightPx,
                dragCalibrationSamples = config.dragCalibrationSamples + 1
            )
        }
        val newLift = (config.dragLiftOffsetCells - rowErr * 0.65f).coerceIn(-1.0f, 4.8f)
        val newOffX = (config.dragHorizontalOffsetCells - colErr * 0.65f).coerceIn(-2.5f, 2.5f)
        return config.copy(
            dragLiftOffsetCells = newLift,
            dragHorizontalOffsetCells = newOffX,
            calibratedScreenWidthPx = screenWidthPx,
            calibratedScreenHeightPx = screenHeightPx,
            dragCalibrationSamples = config.dragCalibrationSamples + 1
        )
    }

    /**
     * Measures exact landing error (rowError, colError) across the entire 8x8 board when no lines cleared,
     * updating both static offsets and gain multipliers to eliminate multi-block overshoots.
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
                dragCalibrationSamples = newSamples,
                isPreviewLocked = config.isPreviewLocked || newSamples >= 3
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
        var newOffX = config.dragHorizontalOffsetCells
        var newGainX = config.dragHorizontalGain

        if (rowError != 0) {
            // NLMS split between static lift offset and upward vertical travel gain
            val liftCorrection = (-rowError) * 0.52f
            val gainCorrection = (-rowError) * upwardTravelNorm * 0.095f * config.dragVerticalGain
            newLift = (config.dragLiftOffsetCells + liftCorrection).coerceIn(-1.0f, 4.8f)
            newGainY = (config.dragVerticalGain + gainCorrection).coerceIn(0.65f, 1.95f)
        }

        if (colError != 0) {
            newOffX = (config.dragHorizontalOffsetCells - colError * 0.48f).coerceIn(-2.5f, 2.5f)
            val dx = endpoints.baseTargetX - endpoints.startX
            if (abs(dx) > endpoints.cellWidthPx * 1.1f) {
                val cellErrorX = colError * endpoints.cellWidthPx
                val gainXRatio = (dx - cellErrorX) / dx
                val targetGainX = config.dragHorizontalGain / gainXRatio.coerceIn(0.65f, 1.45f)
                newGainX = (config.dragHorizontalGain * 0.65f + targetGainX * 0.35f).coerceIn(0.65f, 1.45f)
            }
        }

        val updated = config.copy(
            dragLiftOffsetCells = newLift,
            dragVerticalGain = newGainY,
            dragHorizontalOffsetCells = newOffX,
            dragHorizontalGain = newGainX,
            calibratedScreenWidthPx = screenWidthPx,
            calibratedScreenHeightPx = screenHeightPx,
            dragCalibrationSamples = newSamples
        )
        return CalibrationUpdate(
            updatedConfig = updated,
            rowError = rowError,
            colError = colError,
            summary = "Calibrated Δr=$rowError,Δc=$colError → ${formatF(newLift)}c, ${formatF(newGainY)}x"
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
                dragVerticalGain = (config.dragVerticalGain + 0.09f).coerceIn(0.65f, 1.95f),
                dragLiftOffsetCells = (config.dragLiftOffsetCells + 0.55f).coerceIn(-1.0f, 4.8f)
            )
        } else if (config.dragLiftOffsetCells > 2.6f) {
            config.copy(
                dragLiftOffsetCells = (config.dragLiftOffsetCells - 0.35f).coerceIn(-1.0f, 4.8f)
            )
        } else {
            config.copy(
                dragVerticalGain = (config.dragVerticalGain + 0.05f).coerceIn(0.65f, 1.95f)
            )
        }
    }

    private fun formatF(v: Float): String {
        val rounded = kotlin.math.round(v * 100f) / 100f
        return rounded.toString()
    }
}
