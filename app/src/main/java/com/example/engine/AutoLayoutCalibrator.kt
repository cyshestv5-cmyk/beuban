package com.example.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Automatically detects the exact 8x8 board bounds and 3-slot tray bounds from a live screenshot
 * on any phone screen aspect ratio or notch/nav-bar layout (16:9, 19.5:9, 20:9, 21:9, foldables).
 */
object AutoLayoutCalibrator {

    private data class MarginCandidate(val margin: Float, val score: Float)

    /**
     * Scans [frame] ([width] x [height]) for the square 8x8 Block Blast grid and tray below it.
     * Returns an updated [AdaptiveRoiConfig] stamped with ([fullScreenWidthPx] x [fullScreenHeightPx]),
     * or `null` if the frame does not contain a recognizable active 8x8 grid (e.g., Menu/Splash).
     */
    fun detectBoardAndTrayLayout(
        frame: PixelFrame,
        width: Int,
        height: Int,
        baseConfig: AdaptiveRoiConfig,
        fullScreenWidthPx: Int = width,
        fullScreenHeightPx: Int = height
    ): AdaptiveRoiConfig? {
        if (width < 120 || height < 180) return null

        val aspect = width.toFloat() / height.toFloat()
        // On portrait phones left margin is typically ~0.04..0.09; on wider tablets/foldables up to 0.28.
        val minMargin = 0.032f
        val maxMargin = if (aspect > 0.65f) 0.28f else 0.115f

        // Step 1: Find the top horizontal margin candidates by vertical divider + border contrast
        val marginScores = ArrayList<MarginCandidate>(32)

        var m = minMargin
        while (m <= maxMargin + 0.0001f) {
            val score = scoreHorizontalSpan(frame, width, height, m)
            marginScores.add(MarginCandidate(m, score))
            m += 0.005f
        }
        marginScores.sortByDescending { it.score }
        val topMargins = marginScores.take(5)

        // Step 2: For top horizontal candidates, enforce square-cell gridHeightNorm and scan gridTop
        var bestMargin = baseConfig.gridLeft
        var bestTop = baseConfig.gridTop
        var bestGridScore = Float.NEGATIVE_INFINITY

        for (mc in topMargins) {
            val margin = mc.margin
            val gridWidthNorm = 1f - 2f * margin
            val gridHeightNorm = gridWidthNorm * aspect
            if (gridHeightNorm < 0.22f || gridHeightNorm > 0.62f) continue

            val minTop = 0.12f
            val maxTop = min(0.42f, 0.78f - gridHeightNorm)
            var t = minTop
            while (t <= maxTop + 0.0001f) {
                val totalScore = mc.score * 0.45f + scoreVerticalGridPlacement(
                    frame = frame,
                    width = width,
                    height = height,
                    gridLeft = margin,
                    gridRight = 1f - margin,
                    gridTop = t,
                    gridBottom = t + gridHeightNorm
                )
                if (totalScore > bestGridScore) {
                    bestGridScore = totalScore
                    bestMargin = margin
                    bestTop = t
                }
                t += 0.006f
            }
        }

        // Fine refinement around bestMargin (+/- 0.008) & bestTop (+/- 0.012)
        val refineMarginCenter = bestMargin
        val refineTopCenter = bestTop
        bestGridScore = Float.NEGATIVE_INFINITY

        var rm = (refineMarginCenter - 0.008f).coerceIn(minMargin, maxMargin)
        while (rm <= (refineMarginCenter + 0.008f).coerceAtMost(maxMargin)) {
            val gridWidthNorm = 1f - 2f * rm
            val gridHeightNorm = gridWidthNorm * aspect
            val hScore = scoreHorizontalSpan(frame, width, height, rm)
            var rt = (refineTopCenter - 0.012f).coerceAtLeast(0.11f)
            val rtEnd = min(0.44f, min(0.78f - gridHeightNorm, refineTopCenter + 0.012f))
            while (rt <= rtEnd) {
                val total = hScore * 0.45f + scoreVerticalGridPlacement(
                    frame = frame,
                    width = width,
                    height = height,
                    gridLeft = rm,
                    gridRight = 1f - rm,
                    gridTop = rt,
                    gridBottom = rt + gridHeightNorm
                )
                if (total > bestGridScore) {
                    bestGridScore = total
                    bestMargin = rm
                    bestTop = rt
                }
                rt += 0.002f
            }
            rm += 0.002f
        }

        val gridRight = 1f - bestMargin
        val gridWidthNorm = gridRight - bestMargin
        val gridHeightNorm = gridWidthNorm * aspect
        val gridBottom = bestTop + gridHeightNorm
        val cellHeightNorm = gridHeightNorm / 8f

        // Verify true 8x8 grid dividers actually exist (prevents false calibration on menus)
        val dividerHits = countStrongGridDividers(
            frame = frame,
            width = width,
            height = height,
            gridLeft = bestMargin,
            gridRight = gridRight,
            gridTop = bestTop,
            gridBottom = gridBottom
        )
        if (dividerHits < 20) return null

        // Step 3: Locate the 3-slot tray band below gridBottom
        val (trayTop, trayBottom) = detectTrayBand(
            frame = frame,
            width = width,
            height = height,
            gridBottom = gridBottom,
            cellHeightNorm = cellHeightNorm
        )

        val miniPitch = (gridWidthNorm * 0.054f).coerceIn(0.032f, 0.068f)

        val candidate = baseConfig.copy(
            gridLeft = bestMargin,
            gridTop = bestTop,
            gridRight = gridRight,
            gridBottom = gridBottom,
            trayTop = trayTop,
            trayBottom = trayBottom,
            trayLeftMargin = max(0.02f, bestMargin - 0.011f),
            trayRightMargin = min(0.98f, gridRight + 0.011f),
            expectedMiniTileWidthFraction = miniPitch,
            calibratedScreenWidthPx = fullScreenWidthPx,
            calibratedScreenHeightPx = fullScreenHeightPx
        )

        val verified = AdaptiveScreenParser().parse(
            frame = frame,
            width = width,
            height = height,
            config = candidate,
            requireStability = false
        )
        return if (verified is ParsedFrame.ActiveBoard) candidate else null
    }

    private fun scoreHorizontalSpan(
        frame: PixelFrame,
        width: Int,
        height: Int,
        leftMargin: Float
    ): Float {
        val right = 1f - leftMargin
        val cellW = (right - leftMargin) / 8f
        val sampleRows = floatArrayOf(0.28f, 0.36f, 0.44f, 0.52f)
        val scanPx = max(2, (width * 0.004f).roundToInt())
        var score = 0f

        for (ny in sampleRows) {
            val py = (ny * height).roundToInt().coerceIn(2, height - 3)
            for (c in 0..8) {
                val divX = ((leftMargin + c * cellW) * width).roundToInt().coerceIn(2, width - 3)
                val leftCellX = ((leftMargin + (c - 0.5f) * cellW) * width).roundToInt().coerceIn(1, width - 2)
                val rightCellX = ((leftMargin + (c + 0.5f) * cellW) * width).roundToInt().coerceIn(1, width - 2)
                val cLeft = frame.pixelAt(leftCellX, py)
                val cRight = frame.pixelAt(rightCellX, py)
                var bestEdge = 0f
                for (dx in -scanPx..scanPx) {
                    val p = frame.pixelAt(divX + dx, py)
                    val edge = min(AdaptiveScreenParser.rgbDistance(p, cLeft), AdaptiveScreenParser.rgbDistance(p, cRight))
                    if (edge > bestEdge) bestEdge = edge
                }
                if (bestEdge > 9.5f) score += min(bestEdge, 45f)
            }
        }
        return score
    }

    private fun scoreVerticalGridPlacement(
        frame: PixelFrame,
        width: Int,
        height: Int,
        gridLeft: Float,
        gridRight: Float,
        gridTop: Float,
        gridBottom: Float
    ): Float {
        val cellW = (gridRight - gridLeft) / 8f
        val cellH = (gridBottom - gridTop) / 8f
        val scanPy = max(2, (height * 0.003f).roundToInt())
        val sampleCols = intArrayOf(1, 3, 5, 7)
        var score = 0f

        for (c in sampleCols) {
            val px = ((gridLeft + (c + 0.5f) * cellW) * width).roundToInt()

            // Measure all 9 horizontal board lines (r=0 top border, r=1..7 internal, r=8 bottom border)
            for (r in 0..8) {
                val divY = ((gridTop + r * cellH) * height).roundToInt().coerceIn(2, height - 3)
                val topCellY = ((gridTop + (r - 0.5f) * cellH) * height).roundToInt().coerceIn(1, height - 2)
                val botCellY = ((gridTop + (r + 0.5f) * cellH) * height).roundToInt().coerceIn(1, height - 2)
                val cTop = frame.pixelAt(px, topCellY)
                val cBot = frame.pixelAt(px, botCellY)
                var bestEdge = 0f
                for (dy in -scanPy..scanPy) {
                    val p = frame.pixelAt(px, divY + dy)
                    val edge = min(AdaptiveScreenParser.rgbDistance(p, cTop), AdaptiveScreenParser.rgbDistance(p, cBot))
                    if (edge > bestEdge) bestEdge = edge
                }
                val weight = if (r == 0 || r == 8) 1.45f else 1.0f
                if (bestEdge > 9.5f) score += min(bestEdge, 45f) * weight
            }

            // Anti-shift check: 1 cell above top border and 1 cell below bottom border should NOT be interior board rows
            val extBg = frame.pixelAt((0.015f * width).roundToInt(), ((gridTop + cellH * 2f) * height).roundToInt())
            val pOutTop = frame.pixelAt(px, ((gridTop - cellH * 0.45f).coerceAtLeast(0.04f) * height).roundToInt())
            val pInTop = frame.pixelAt(px, ((gridTop + cellH * 0.50f) * height).roundToInt())
            val pOutBot = frame.pixelAt(px, ((gridBottom + cellH * 0.45f).coerceAtMost(0.92f) * height).roundToInt())
            val pInBot = frame.pixelAt(px, ((gridBottom - cellH * 0.50f) * height).roundToInt())

            val topStep = min(AdaptiveScreenParser.rgbDistance(pOutTop, pInTop), 80f)
            val botStep = min(AdaptiveScreenParser.rgbDistance(pOutBot, pInBot), 80f)
            score += (topStep + botStep) * 0.65f

            // If exterior background differs clearly from interior cells, reward symmetric outside match
            val dInTopBg = AdaptiveScreenParser.rgbDistance(pInTop, extBg)
            val dInBotBg = AdaptiveScreenParser.rgbDistance(pInBot, extBg)
            val dOutTopBg = AdaptiveScreenParser.rgbDistance(pOutTop, extBg)
            val dOutBotBg = AdaptiveScreenParser.rgbDistance(pOutBot, extBg)
            if (dOutTopBg < 24f && dInTopBg > 24f) score += 35f
            if (dOutBotBg < 24f && dInBotBg > 24f) score += 35f
            if (dInTopBg < 14f && dOutTopBg > 28f) score -= 50f
            if (dInBotBg < 14f && dOutBotBg > 28f) score -= 50f
        }
        return score
    }

    private fun countStrongGridDividers(
        frame: PixelFrame,
        width: Int,
        height: Int,
        gridLeft: Float,
        gridRight: Float,
        gridTop: Float,
        gridBottom: Float
    ): Int {
        val cellW = (gridRight - gridLeft) / 8f
        val cellH = (gridBottom - gridTop) / 8f
        val scanPx = max(3, (width * 0.005f).roundToInt())
        val scanPy = max(3, (height * 0.004f).roundToInt())
        var hits = 0

        for (r in intArrayOf(0, 2, 4, 6, 7)) {
            val cy = ((gridTop + (r + 0.5f) * cellH) * height).roundToInt()
            for (c in 1..7) {
                val divX = ((gridLeft + c * cellW) * width).roundToInt()
                val leftRgb = frame.pixelAt(((gridLeft + (c - 0.5f) * cellW) * width).roundToInt(), cy)
                val rightRgb = frame.pixelAt(((gridLeft + (c + 0.5f) * cellW) * width).roundToInt(), cy)
                var maxDiff = 0f
                for (dx in -scanPx..scanPx) {
                    val p = frame.pixelAt(divX + dx, cy)
                    val d = min(AdaptiveScreenParser.rgbDistance(p, leftRgb), AdaptiveScreenParser.rgbDistance(p, rightRgb))
                    if (d > maxDiff) maxDiff = d
                }
                if (maxDiff > 10.5f) hits++
            }
        }

        for (c in intArrayOf(1, 3, 5)) {
            val cx = ((gridLeft + (c + 0.5f) * cellW) * width).roundToInt()
            for (r in 1..7) {
                val divY = ((gridTop + r * cellH) * height).roundToInt()
                val topRgb = frame.pixelAt(cx, ((gridTop + (r - 0.5f) * cellH) * height).roundToInt())
                val botRgb = frame.pixelAt(cx, ((gridTop + (r + 0.5f) * cellH) * height).roundToInt())
                var maxDiff = 0f
                for (dy in -scanPy..scanPy) {
                    val p = frame.pixelAt(cx, divY + dy)
                    val d = min(AdaptiveScreenParser.rgbDistance(p, topRgb), AdaptiveScreenParser.rgbDistance(p, botRgb))
                    if (d > maxDiff) maxDiff = d
                }
                if (maxDiff > 10.5f) hits++
            }
        }
        return hits
    }

    private fun detectTrayBand(
        frame: PixelFrame,
        width: Int,
        height: Int,
        gridBottom: Float,
        cellHeightNorm: Float
    ): Pair<Float, Float> {
        val defaultTop = (gridBottom + cellHeightNorm * 1.15f).coerceIn(0.58f, 0.80f)
        val bandHeight = (cellHeightNorm * 2.65f).coerceIn(0.105f, 0.21f)
        val defaultBottom = (defaultTop + bandHeight).coerceAtMost(0.95f)

        val scanStart = (gridBottom + cellHeightNorm * 0.45f).coerceAtMost(0.88f)
        val scanEnd = min(0.93f, gridBottom + cellHeightNorm * 4.5f)
        if (scanEnd <= scanStart + 0.04f) return defaultTop to defaultBottom

        var minActiveY = 1f
        var maxActiveY = 0f
        var activeRows = 0

        var ny = scanStart
        while (ny <= scanEnd) {
            val py = (ny * height).roundToInt().coerceIn(0, height - 1)
            val bgRgb = frame.pixelAt((0.02f * width).roundToInt(), py)
            val bgSat = AdaptiveScreenParser.saturationOf(bgRgb)
            var rowHits = 0
            for (step in 1..18) {
                val px = ((0.06f + step * (0.88f / 19f)) * width).roundToInt()
                val p = frame.pixelAt(px, py)
                val d = AdaptiveScreenParser.rgbDistance(p, bgRgb)
                val sd = abs(AdaptiveScreenParser.saturationOf(p) - bgSat)
                if (d > 42f || (sd > 0.18f && d > 26f)) rowHits++
            }
            if (rowHits >= 1) {
                activeRows++
                if (ny < minActiveY) minActiveY = ny
                if (ny > maxActiveY) maxActiveY = ny
            }
            ny += 0.006f
        }

        if (activeRows < 3 || maxActiveY <= minActiveY) {
            return defaultTop to defaultBottom
        }

        val center = (minActiveY + maxActiveY) * 0.5f
        val detectedSpan = max(bandHeight, (maxActiveY - minActiveY) + cellHeightNorm * 0.9f)
            .coerceIn(0.105f, 0.22f)
        val top = (center - detectedSpan * 0.5f).coerceIn(gridBottom + 0.02f, 0.85f)
        val bottom = (top + detectedSpan).coerceAtMost(0.96f)
        return top to bottom
    }
}
