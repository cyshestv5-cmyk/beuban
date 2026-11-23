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

/**
 * Normalized (0f..1f) screen coordinates for Block Blast Lite portrait layouts,
 * calibrated from reference gameplay screenshots and editable by the user.
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
     * Vertical touch offset (in board cell heights) applied during Accessibility Autoplay drags
     * to compensate for Block Blast Lite lifting a dragged block slightly above the user's finger.
     */
    val dragLiftOffsetCells: Float = 1.0f
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
}

data class TraySlotBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
