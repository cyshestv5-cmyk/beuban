package com.example.engine

enum class OperatingMode(val label: String) {
    GUIDE_OVERLAY("Guide Overlay"),
    AUTOPLAY("Autoplay")
}

enum class SolverMode(val label: String) {
    BALANCED_SURVIVAL("Balanced Survival"),
    COMBO_MAXIMIZER("Combo Maximizer")
}

enum class ThinkingDepth(val label: String, val budgetMs: Long) {
    BALANCED_FAST("Balanced (~45 ms)", 45L),
    ULTRA_DEEP("Ultra-Deep Max Survival (250 ms)", 240L)
}

data class DragEndpoints(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val baseTargetY: Float,
    val cellHeightPx: Float
)

/**
 * Normalized (0f..1f) screen coordinates and persisted per-device Autoplay movement calibration
 * for Block Blast Lite portrait layouts. Automatically adapts to the running device's screen size.
 */
data class AdaptiveRoiConfig(
    val gridLeft: Float = 0.056f,
    val gridTop: Float = 0.240f,
    val gridRight: Float = 0.944f,
    val gridBottom: Float = 0.638f,
    val trayTop: Float = 0.695f,
    val trayBottom: Float = 0.825f,
    val trayLeftMargin: Float = 0.045f,
    val trayRightMargin: Float = 0.955f,
    val expectedMiniTileWidthFraction: Float = 0.048f,
    val operatingMode: OperatingMode = OperatingMode.GUIDE_OVERLAY,
    val solverMode: SolverMode = SolverMode.BALANCED_SURVIVAL,
    val thinkingDepth: ThinkingDepth = ThinkingDepth.ULTRA_DEEP,
    val overlayOpacity: Float = 0.88f,
    /**
     * Static vertical lift offset (in board cell heights) applied above the finger in Block Blast Lite.
     */
    val dragLiftOffsetCells: Float = 1.8f,
    /**
     * Vertical drag tracking multiplier used by the game (values > 1.0 compensate when upward drags
     * travel farther than the raw finger delta).
     */
    val dragVerticalGain: Float = 1.15f,
    /**
     * Horizontal drag tracking multiplier (defaults to 1.0).
     */
    val dragHorizontalGain: Float = 1.0f,
    val calibratedScreenWidthPx: Int = 0,
    val calibratedScreenHeightPx: Int = 0,
    val dragCalibrationSamples: Int = 0
) {
    fun cellWidth(): Float = (gridRight - gridLeft) / 8f
    fun cellHeight(): Float = (gridBottom - gridTop) / 8f

    fun cellCenterX(col: Int): Float = gridLeft + (col + 0.5f) * cellWidth()
    fun cellCenterY(row: Int): Float = gridTop + (row + 0.5f) * cellHeight()

    fun traySlotBounds(slotIndex: Int): TraySlotBounds {
        val totalW = trayRightMargin - trayLeftMargin
        val slotW = totalW / 3f
        val left = trayLeftMargin + slotIndex * slotW
        return TraySlotBounds(
            left = left,
            top = trayTop,
            right = left + slotW,
            bottom = trayBottom
        )
    }

    /**
     * Computes exact full-screen gesture endpoints for dragging [piece] from [slotIndex]
     * onto ([targetRow], [targetCol]) using this phone's persisted gain & lift calibration.
     */
    fun computeDragEndpoints(
        slotIndex: Int,
        targetRow: Int,
        targetCol: Int,
        pieceWidth: Int,
        pieceHeight: Int,
        screenWidthPx: Int,
        screenHeightPx: Int
    ): DragEndpoints {
        val slotBounds = traySlotBounds(slotIndex)
        val startX = ((slotBounds.left + slotBounds.right) * 0.5f) * screenWidthPx
        val startY = ((slotBounds.top + slotBounds.bottom) * 0.5f) * screenHeightPx

        val cellWPx = cellWidth() * screenWidthPx
        val cellHPx = cellHeight() * screenHeightPx

        val targetCenterX = (gridLeft + (targetCol + pieceWidth * 0.5f) * cellWidth()) * screenWidthPx
        val baseTargetY = (gridTop + (targetRow + pieceHeight * 0.5f) * cellHeight()) * screenHeightPx

        val safeGainX = dragHorizontalGain.coerceIn(0.70f, 1.50f)
        val safeGainY = dragVerticalGain.coerceIn(0.70f, 2.00f)

        val compensatedX = startX + (targetCenterX - startX) / safeGainX
        // Game maps finger delta (fingerY - startY) via: pieceY = startY - liftPx + gainY * (fingerY - startY)
        // Solving for fingerY so pieceY == baseTargetY:
        val desiredPieceDeltaY = (baseTargetY - startY) + (dragLiftOffsetCells * cellHPx)
        val compensatedY = startY + (desiredPieceDeltaY / safeGainY)

        return DragEndpoints(
            startX = startX,
            startY = startY,
            endX = compensatedX.coerceIn(cellWPx * 0.35f, screenWidthPx - cellWPx * 0.35f),
            endY = compensatedY.coerceIn(cellHPx * 0.4f, screenHeightPx - 32f),
            baseTargetY = baseTargetY,
            cellHeightPx = cellHPx
        )
    }
}

data class TraySlotBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
