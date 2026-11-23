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
    ULTRA_DEEP("Ultra-Deep Max Survival (250 ms)", 250L)
}

/**
 * Built-in global profiles unifying AI search posture, adaptive compute budget,
 * and Autoplay drag/settle timings into cohesive performance presets.
 */
enum class GlobalProfile(
    val label: String,
    val description: String,
    val solverMode: SolverMode,
    val openBudgetMs: Long,
    val contestedBudgetMs: Long,
    val criticalBudgetMs: Long,
    val rolloutDrawsOpen: Int,
    val rolloutDrawsTight: Int,
    val preDragPreviewMs: Long,
    val pressDurationMs: Long,
    val glideDurationMs: Long,
    val settleDurationMs: Long,
    val postDropWaitMs: Long,
    val enableLivePreviewSnap: Boolean,
    val allowOpenBoardPlanReuse: Boolean
) {
    MAX_COMBOS_SCORE(
        label = "Max Combos/Score",
        description = "Protects live combo streaks, prioritizes multi-line & turn-closing clears, and plans 2-turn chains.",
        solverMode = SolverMode.COMBO_MAXIMIZER,
        openBudgetMs = 28L,
        contestedBudgetMs = 115L,
        criticalBudgetMs = 240L,
        rolloutDrawsOpen = 12,
        rolloutDrawsTight = 36,
        preDragPreviewMs = 35L,
        pressDurationMs = 38L,
        glideDurationMs = 235L,
        settleDurationMs = 55L,
        postDropWaitMs = 200L,
        enableLivePreviewSnap = true,
        allowOpenBoardPlanReuse = false
    ),
    MAX_SURVIVAL(
        label = "Max Survival",
        description = "Zero-death longevity: guards 3×3/1×5 territory, packs edges, and runs 2-turn killer stress tests.",
        solverMode = SolverMode.BALANCED_SURVIVAL,
        openBudgetMs = 32L,
        contestedBudgetMs = 150L,
        criticalBudgetMs = 300L,
        rolloutDrawsOpen = 16,
        rolloutDrawsTight = 44,
        preDragPreviewMs = 55L,
        pressDurationMs = 42L,
        glideDurationMs = 275L,
        settleDurationMs = 68L,
        postDropWaitMs = 235L,
        enableLivePreviewSnap = true,
        allowOpenBoardPlanReuse = false
    ),
    FASTEST_SPEED(
        label = "Fastest Speed",
        description = "Sub-15ms open-board bitwise solves, zero pre-drag pause, and rapid glides with tight-board guard.",
        solverMode = SolverMode.BALANCED_SURVIVAL,
        openBudgetMs = 12L,
        contestedBudgetMs = 38L,
        criticalBudgetMs = 88L,
        rolloutDrawsOpen = 4,
        rolloutDrawsTight = 14,
        preDragPreviewMs = 0L,
        pressDurationMs = 28L,
        glideDurationMs = 165L,
        settleDurationMs = 35L,
        postDropWaitMs = 160L,
        enableLivePreviewSnap = false,
        allowOpenBoardPlanReuse = true
    )
}

data class DragEndpoints(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val baseTargetX: Float,
    val baseTargetY: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float
)

/**
 * Normalized (0f..1f) screen coordinates and persisted per-device Autoplay affine movement
 * calibration for Block Blast Lite portrait layouts.
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
    val globalProfile: GlobalProfile = GlobalProfile.MAX_SURVIVAL,
    val solverMode: SolverMode = SolverMode.BALANCED_SURVIVAL,
    val thinkingDepth: ThinkingDepth = ThinkingDepth.ULTRA_DEEP,
    val overlayOpacity: Float = 0.88f,
    /** Static vertical lift offset (in board cell heights) applied above the finger in Block Blast Lite. */
    val dragLiftOffsetCells: Float = 1.8f,
    /** Vertical drag tracking multiplier (> 1.0 compensates upward travel magnification). */
    val dragVerticalGain: Float = 1.15f,
    /** Static horizontal finger-to-piece offset (in board cell widths). */
    val dragHorizontalOffsetCells: Float = 0.0f,
    /** Horizontal drag tracking multiplier (defaults to 1.0). */
    val dragHorizontalGain: Float = 1.0f,
    val calibratedScreenWidthPx: Int = 0,
    val calibratedScreenHeightPx: Int = 0,
    val dragCalibrationSamples: Int = 0,
    /** True once Guided 3-Point Preview Calibration or multi-point preview verification locks precision. */
    val isPreviewLocked: Boolean = false
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
     * Computes exact full-screen gesture endpoints for dragging [pieceWidth]x[pieceHeight] from [slotIndex]
     * onto ([targetRow], [targetCol]) using this phone's persisted 4-parameter affine calibration.
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

        val baseTargetX = (gridLeft + (targetCol + pieceWidth * 0.5f) * cellWidth()) * screenWidthPx
        val baseTargetY = (gridTop + (targetRow + pieceHeight * 0.5f) * cellHeight()) * screenHeightPx

        val safeGainX = dragHorizontalGain.coerceIn(0.65f, 1.50f)
        val safeGainY = dragVerticalGain.coerceIn(0.65f, 2.00f)

        val desiredPieceDeltaX = (baseTargetX - startX) + (dragHorizontalOffsetCells * cellWPx)
        val compensatedX = startX + (desiredPieceDeltaX / safeGainX)

        val desiredPieceDeltaY = (baseTargetY - startY) + (dragLiftOffsetCells * cellHPx)
        val compensatedY = startY + (desiredPieceDeltaY / safeGainY)

        return DragEndpoints(
            startX = startX,
            startY = startY,
            endX = compensatedX.coerceIn(cellWPx * 0.35f, screenWidthPx - cellWPx * 0.35f),
            endY = compensatedY.coerceIn(cellHPx * 0.4f, screenHeightPx - 32f),
            baseTargetX = baseTargetX,
            baseTargetY = baseTargetY,
            cellWidthPx = cellWPx,
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
