package com.example.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class PreviewHoverMatch(
    val row: Int,
    val col: Int,
    val confidence: Float,
    val matchedCells: Int,
    val totalChangedEmptyCells: Int
)

data class GuidedCalibrationPlan(
    val slotIndex: Int,
    val piece: PieceShape,
    val anchors: List<Pair<Int, Int>>
)

/**
 * Pure-Kotlin detector for Block Blast Lite's translucent ghost preview shadow when a dragged piece
 * hovers above the 8x8 board. Compares a live hover frame against the pre-drag 64-cell RGB baselines
 * to measure the exact cell coordinates (row, col) highlighted by the game engine.
 */
object PreviewHoverDetector {

    /**
     * Selects the best tray piece and up to 3 well-separated empty board anchors (low/mid/high across
     * distinct columns) for non-destructive Guided 3-Point Preview Calibration.
     */
    fun selectGuidedCalibrationPlan(
        boardMask: Long,
        tray: List<TraySlotState>
    ): GuidedCalibrationPlan? {
        val candidates = tray.filterIsInstance<TraySlotState.Recognized>()
            .sortedWith(compareBy<TraySlotState.Recognized> { it.piece.cellCount }.thenBy { max(it.piece.width, it.piece.height) })

        for (slot in candidates) {
            val piece = slot.piece
            val valid = ArrayList<Pair<Int, Int>>(piece.placementCount)
            for (i in 0 until piece.placementCount) {
                if ((boardMask and piece.placementMasks[i]) == 0L) {
                    valid.add(piece.placementRows[i] to piece.placementCols[i])
                }
            }
            if (valid.size < 2) continue

            // Prefer board-interior rows (2..maxRow) first so even heavy upward overshoot stays inside the grid
            val safeValid = valid.filter { it.first >= minOf(2, piece.maxRow) }
            val pool = if (safeValid.size >= 3) safeValid else valid

            // Anchor 1: lower half of board, balanced center column
            val a1 = pool.maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { -abs(it.second - 3) })!!
            // Anchor 2: mid-opposite side maximizing horizontal + vertical spread from a1
            val remaining = pool.filter { it != a1 }
            val a2 = remaining.maxWithOrNull(
                compareBy<Pair<Int, Int>> {
                    abs(it.second - a1.second) * 2 + abs(it.first - a1.first)
                }
            )!!
            if (pool.size == 2) {
                return GuidedCalibrationPlan(slot.slotIndex, piece, listOf(a1, a2))
            }

            val remaining3 = remaining.filter { it != a2 }
            val a3 = remaining3.maxWithOrNull(
                compareBy<Pair<Int, Int>> { p ->
                    val d1 = abs(p.first - a1.first) * 2 + abs(p.second - a1.second)
                    val d2 = abs(p.first - a2.first) * 2 + abs(p.second - a2.second)
                    val safeTopBonus = if (p.first in 2..3) 8 else (7 - p.first)
                    minOf(d1, d2) + safeTopBonus
                }
            )!!
            return GuidedCalibrationPlan(slot.slotIndex, piece, listOf(a1, a2, a3))
        }
        return null
    }

    private data class CellPatch(val meanRgb: Int, val maxSpread: Int)

    /**
     * Captures the 64 board cell center mean RGB values prior to starting a drag gesture.
     */
    fun captureCellBaselines(
        frame: PixelFrame,
        width: Int,
        height: Int,
        config: AdaptiveRoiConfig
    ): IntArray {
        val baselines = IntArray(64)
        val cellW = config.cellWidth() * width
        val cellH = config.cellHeight() * height
        val dx = max(1, (cellW * 0.14f).roundToInt())
        val dy = max(1, (cellH * 0.14f).roundToInt())

        for (r in 0 until 8) {
            val cy = (config.cellCenterY(r) * height).roundToInt()
            for (c in 0 until 8) {
                val cx = (config.cellCenterX(c) * width).roundToInt()
                baselines[r * 8 + c] = samplePatch(frame, width, height, cx, cy, dx, dy).meanRgb
            }
        }
        return baselines
    }

    /**
     * Matches the currently hovered ghost preview shadow on the 8x8 board against every legal
     * placement of [piece] on [preBoardMask], filtering non-uniform floating tile sprites.
     */
    fun detectHoveredPreview(
        hoverFrame: PixelFrame,
        width: Int,
        height: Int,
        config: AdaptiveRoiConfig,
        preBoardMask: Long,
        preCellColors: IntArray,
        piece: PieceShape
    ): PreviewHoverMatch? {
        if (preCellColors.size < 64 || width < 32 || height < 32) return null

        val cellW = config.cellWidth() * width
        val cellH = config.cellHeight() * height
        val dx = max(1, (cellW * 0.14f).roundToInt())
        val dy = max(1, (cellH * 0.14f).roundToInt())

        val cellSignal = FloatArray(64)
        val cellChanged = BooleanArray(64)
        var changedEmptyCount = 0

        for (r in 0 until 8) {
            val cy = (config.cellCenterY(r) * height).roundToInt()
            for (c in 0 until 8) {
                val idx = r * 8 + c
                if (((preBoardMask ushr idx) and 1L) != 0L) continue

                val cx = (config.cellCenterX(c) * width).roundToInt()
                val patch = samplePatch(hoverFrame, width, height, cx, cy, dx, dy)
                val curRgb = patch.meanRgb
                val baseRgb = preCellColors[idx]

                val rgbDist = AdaptiveScreenParser.rgbDistance(curRgb, baseRgb)
                val lumDelta = abs(
                    AdaptiveScreenParser.luminanceOf(curRgb) -
                        AdaptiveScreenParser.luminanceOf(baseRgb)
                )
                val satDelta = abs(
                    AdaptiveScreenParser.saturationOf(curRgb) -
                        AdaptiveScreenParser.saturationOf(baseRgb)
                )

                // Uniform ghost shadows vary smoothly inside cell centers; crisp dragged sprite edges have high spread
                val uniformityWeight = when {
                    patch.maxSpread <= 38 -> 1.0f
                    patch.maxSpread <= 68 -> 0.60f
                    else -> 0.20f
                }

                val combined = (rgbDist * 0.65f + lumDelta * 0.55f + satDelta * 65f) * uniformityWeight
                cellSignal[idx] = combined
                val hasDelta = rgbDist >= 15f || lumDelta >= 12f || satDelta >= 0.08f
                if (hasDelta && patch.maxSpread <= 72) {
                    cellChanged[idx] = true
                    changedEmptyCount++
                }
            }
        }

        if (changedEmptyCount == 0) return null

        var bestIdx = -1
        var bestScore = Float.NEGATIVE_INFINITY
        var bestHits = 0

        val masks = piece.placementMasks
        val rows = piece.placementRows
        val cols = piece.placementCols
        val minRequiredHits = if (piece.cellCount <= 4) piece.cellCount else max(3, piece.cellCount - 1)

        for (i in 0 until piece.placementCount) {
            val pm = masks[i]
            if ((preBoardMask and pm) != 0L) continue

            var hits = 0
            var footprintSignal = 0f
            var m = pm
            while (m != 0L) {
                val bit = java.lang.Long.numberOfTrailingZeros(m)
                m = m and (m - 1L)
                if (cellChanged[bit]) hits++
                footprintSignal += cellSignal[bit].coerceAtMost(75f) / 75f
            }

            if (hits < minRequiredHits) continue

            val outsideChanged = max(0, changedEmptyCount - hits)
            val precisionPenalty = outsideChanged * 0.72f
            val coverageBonus = (hits.toFloat() / piece.cellCount) * 1.40f
            val score = footprintSignal + coverageBonus - precisionPenalty

            if (score > bestScore) {
                bestScore = score
                bestIdx = i
                bestHits = hits
            }
        }

        if (bestIdx < 0) return null

        val expectedMinScore = piece.cellCount * 0.48f
        if (bestScore < expectedMinScore) return null

        val recall = bestHits.toFloat() / piece.cellCount
        val precision = bestHits.toFloat() / max(bestHits, changedEmptyCount)
        val confidence = ((recall * 0.6f + precision * 0.4f)).coerceIn(0f, 1f)
        if (confidence < 0.56f) return null

        return PreviewHoverMatch(
            row = rows[bestIdx],
            col = cols[bestIdx],
            confidence = confidence,
            matchedCells = bestHits,
            totalChangedEmptyCells = changedEmptyCount
        )
    }

    private fun samplePatch(
        frame: PixelFrame,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        dx: Int,
        dy: Int
    ): CellPatch {
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var minR = 255
        var maxR = 0
        var minG = 255
        var maxG = 0
        var minB = 255
        var maxB = 0
        var count = 0
        for (oy in -1..1) {
            val py = (cy + oy * dy).coerceIn(0, height - 1)
            for (ox in -1..1) {
                val px = (cx + ox * dx).coerceIn(0, width - 1)
                val rgb = frame.pixelAt(px, py)
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                sumR += r
                sumG += g
                sumB += b
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
                count++
            }
        }
        val meanRgb = (0xFF shl 24) or
            ((sumR / count) shl 16) or
            ((sumG / count) shl 8) or
            (sumB / count)
        val spread = max(maxR - minR, max(maxG - minG, maxB - minB))
        return CellPatch(meanRgb = meanRgb, maxSpread = spread)
    }
}
