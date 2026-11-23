package com.example.engine

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun interface PixelFrame {
    fun pixelAt(x: Int, y: Int): Int
}

class ByteBufferPixelFrame(
    private val buffer: ByteBuffer,
    private val width: Int,
    private val height: Int,
    private val pixelStride: Int,
    private val rowStride: Int
) : PixelFrame {
    override fun pixelAt(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        val offset = cy * rowStride + cx * pixelStride
        val r = buffer.get(offset).toInt() and 0xFF
        val g = buffer.get(offset + 1).toInt() and 0xFF
        val b = buffer.get(offset + 2).toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

class IntArrayPixelFrame(
    private val pixels: IntArray,
    private val width: Int,
    private val height: Int
) : PixelFrame {
    override fun pixelAt(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return pixels[cy * width + cx]
    }
}

sealed interface TraySlotState {
    data object Empty : TraySlotState
    data class Recognized(val slotIndex: Int, val piece: PieceShape) : TraySlotState
}

sealed interface ParsedFrame {
    data object IdleOrMenu : ParsedFrame
    data object Animating : ParsedFrame
    data class ActiveBoard(
        val boardMask: Long,
        val emptyCellColorRgb: Int,
        val tray: List<TraySlotState>
    ) : ParsedFrame
}

/**
 * Pure-Kotlin ultra-fast theme-adaptive frame parser.
 * Distinguishes Menu/GameOver vs Active Gameplay on any Block Blast Lite visual theme
 * (Light, Wood, etc.) without static hardcoded RGB tables.
 */
class AdaptiveScreenParser {

    private var lastStabilityHash: Long = 0L
    private var stableStreak: Int = 0

    fun resetStability() {
        lastStabilityHash = 0L
        stableStreak = 0
    }

    /**
     * Parses a frame, optionally enforcing the 2-frame anti-drag/animation stability lock.
     * For offline testing or single-shot screenshot evaluation, pass [requireStability] = false.
     */
    fun parse(
        frame: PixelFrame,
        width: Int,
        height: Int,
        config: AdaptiveRoiConfig,
        requireStability: Boolean = true
    ): ParsedFrame {
        if (width < 32 || height < 32) return ParsedFrame.IdleOrMenu

        if (requireStability) {
            val hash = computeStabilityHash(frame, width, height, config)
            if (hash == lastStabilityHash) {
                stableStreak++
            } else {
                lastStabilityHash = hash
                stableStreak = 1
                return ParsedFrame.Animating
            }
            if (stableStreak < 2) {
                return ParsedFrame.Animating
            }
        }

        // 1. Sample all 64 cell centers (3x3 patch) + internal spread
        val cellColors = IntArray(64)
        val cellUniform = BooleanArray(64)
        val cellW = config.cellWidth() * width
        val cellH = config.cellHeight() * height
        val dx = max(1, (cellW * 0.16f).roundToInt())
        val dy = max(1, (cellH * 0.16f).roundToInt())

        for (r in 0 until 8) {
            val cy = (config.cellCenterY(r) * height).roundToInt()
            for (c in 0 until 8) {
                val cx = (config.cellCenterX(c) * width).roundToInt()
                val idx = r * 8 + c
                val stats = samplePatchStats(frame, width, height, cx, cy, dx, dy)
                cellColors[idx] = stats.meanRgb
                cellUniform[idx] = stats.maxChannelSpread <= 28
            }
        }

        // 2. Gatekeeper: verify an 8x8 grid is actually visible (not StartingScreen / gradient / ad)
        if (!hasActiveBoardStructure(frame, width, height, config, cellColors)) {
            return ParsedFrame.IdleOrMenu
        }

        // 3. Adaptive Theme Clustering: identify the empty-cell reference RGB
        val emptyRgb = detectEmptyCellReferenceColor(cellColors, cellUniform)
        val emptySat = saturationOf(emptyRgb)
        val emptyLum = luminanceOf(emptyRgb)

        // 4. Classify each of the 64 cells into a 64-bit Long bitboard
        var boardMask = 0L
        for (idx in 0 until 64) {
            val rgb = cellColors[idx]
            val dist = rgbDistance(rgb, emptyRgb)
            val satDelta = abs(saturationOf(rgb) - emptySat)
            val lumDelta = abs(luminanceOf(rgb) - emptyLum)
            val occupied = dist > 38f || (satDelta > 0.28f && dist > 26f) || (lumDelta > 34f && dist > 26f)
            if (occupied) {
                boardMask = boardMask or (1L shl idx)
            }
        }

        // 5. Extract 3 tray slots
        val tray = (0..2).map { slotIdx ->
            classifyTraySlot(frame, width, height, config, slotIdx)
        }

        // Gate: Active playable turn always has at least 1 piece in the 3-slot tray.
        // Splash/menu/transition/game-over screens without tray pieces yield IdleOrMenu.
        if (tray.all { it is TraySlotState.Empty }) {
            return ParsedFrame.IdleOrMenu
        }

        return ParsedFrame.ActiveBoard(
            boardMask = boardMask,
            emptyCellColorRgb = emptyRgb,
            tray = tray
        )
    }

    private fun computeStabilityHash(
        frame: PixelFrame,
        width: Int,
        height: Int,
        config: AdaptiveRoiConfig
    ): Long {
        var hash = -3750763034362895579L // FNV-1a offset basis
        val prime = 1099511628211L
        val yStart = config.gridTop
        val yEnd = config.trayBottom
        for (gy in 0 until 12) {
            val ny = yStart + (gy + 0.5f) / 12f * (yEnd - yStart)
            val py = (ny * height).roundToInt()
            for (gx in 0 until 12) {
                val nx = config.gridLeft + (gx + 0.5f) / 12f * (config.gridRight - config.gridLeft)
                val px = (nx * width).roundToInt()
                val rgb = frame.pixelAt(px, py)
                // Bucket each channel to multiples of 16 to ignore sub-pixel / video compression jitter
                val bucket = (((rgb ushr 16) and 0xF0) shl 4) or
                    (((rgb ushr 8) and 0xF0)) or
                    ((rgb and 0xF0) ushr 4)
                hash = (hash xor bucket.toLong()) * prime
            }
        }
        return hash
    }

    private fun hasActiveBoardStructure(
        frame: PixelFrame,
        width: Int,
        height: Int,
        config: AdaptiveRoiConfig,
        cellColors: IntArray
    ): Boolean {
        // Measure gridline / cell-edge contrast across the 7 vertical cell dividers in rows 0, 1, 6, 7.
        var dividerContrastHits = 0
        val sampleRows = intArrayOf(0, 2, 4, 6, 7)
        val scanRadiusPx = max(3, (width * 0.005f).roundToInt())
        for (r in sampleRows) {
            val cy = (config.cellCenterY(r) * height).roundToInt()
            for (c in 1..7) {
                val divX = ((config.gridLeft + c * config.cellWidth()) * width).roundToInt()
                val centerLeft = cellColors[r * 8 + (c - 1)]
                val centerRight = cellColors[r * 8 + c]
                var maxEdgeDiff = 0f
                for (dx in -scanRadiusPx..scanRadiusPx) {
                    val divRgb = frame.pixelAt(divX + dx, cy)
                    val edgeDiff = min(rgbDistance(divRgb, centerLeft), rgbDistance(divRgb, centerRight))
                    if (edgeDiff > maxEdgeDiff) maxEdgeDiff = edgeDiff
                }
                if (maxEdgeDiff > 11f) dividerContrastHits++
            }
        }

        // Also check outer background vs inner grid contrast (e.g., Wood theme dark cells vs terracotta bg)
        val outerLeftRgb = frame.pixelAt((0.015f * width).roundToInt(), (0.44f * height).roundToInt())
        val outerRightRgb = frame.pixelAt((0.985f * width).roundToInt(), (0.44f * height).roundToInt())
        var frameContrastCount = 0
        for (idx in 0 until 64) {
            if (rgbDistance(cellColors[idx], outerLeftRgb) > 14f ||
                rgbDistance(cellColors[idx], outerRightRgb) > 14f
            ) {
                frameContrastCount++
            }
        }

        // On StartingScreen blue gradient, dividerContrastHits is 0..1 and frameContrastCount is low/uniform.
        // On Light theme (white cells on white bg), dividerContrastHits >= 12 from crisp gray gridlines.
        // On Wood theme, both dividerContrastHits and frameContrastCount are high.
        return dividerContrastHits >= 12 || (dividerContrastHits >= 6 && frameContrastCount >= 16)
    }

    /**
     * Finds the empty cell background RGB dynamically without assuming Light or Wood themes.
     * Strategy:
     * 1. Cluster uniform cells (spread <= 28) by RGB similarity (< 28 distance).
     * 2. Score each cluster favoring:
     *    - Large cluster size (most boards have many empty cells)
     *    - Low saturation (Light theme empty cells are near-white/gray; Wood empty cells are muted dark brown vs vibrant blocks)
     *    - Consistency across edges
     */
    private fun detectEmptyCellReferenceColor(
        cellColors: IntArray,
        cellUniform: BooleanArray
    ): Int {
        data class Cluster(var sumR: Long, var sumG: Long, var sumB: Long, var count: Int, var repRgb: Int)

        val clusters = ArrayList<Cluster>()
        for (i in 0 until 64) {
            val rgb = cellColors[i]
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF

            var matched: Cluster? = null
            var bestDist = Float.MAX_VALUE
            for (cl in clusters) {
                val d = rgbDistance(rgb, cl.repRgb)
                if (d < 28f && d < bestDist) {
                    bestDist = d
                    matched = cl
                }
            }
            if (matched != null) {
                matched.sumR += r
                matched.sumG += g
                matched.sumB += b
                matched.count++
                val mr = (matched.sumR / matched.count).toInt()
                val mg = (matched.sumG / matched.count).toInt()
                val mb = (matched.sumB / matched.count).toInt()
                matched.repRgb = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
            } else {
                clusters.add(Cluster(r.toLong(), g.toLong(), b.toLong(), 1, rgb))
            }
        }

        // Score clusters: empty cells are typically the largest low-saturation cluster.
        // Note: a saturated block cluster (e.g. many blue blocks) has saturation > 0.55,
        // whereas light white empty cells have sat ~0.03 and wood empty cells have sat ~0.58 & very low brightness (~0.29).
        var bestCluster = clusters.first()
        var bestScore = Float.NEGATIVE_INFINITY
        for (cl in clusters) {
            val sat = saturationOf(cl.repRgb)
            val valBright = maxChannelOf(cl.repRgb) / 255f
            // Vibrant block penalty: high brightness (> 0.55) AND high saturation (> 0.45)
            val isVibrantBlock = valBright > 0.52f && sat > 0.42f
            val vibrancyPenalty = if (isVibrantBlock) 32f else 0f
            val neutralBonus = (1f - sat) * 14f
            val score = cl.count.toFloat() + neutralBonus - vibrancyPenalty
            if (score > bestScore) {
                bestScore = score
                bestCluster = cl
            }
        }
        return bestCluster.repRgb
    }

    private fun classifyTraySlot(
        frame: PixelFrame,
        width: Int,
        height: Int,
        config: AdaptiveRoiConfig,
        slotIndex: Int
    ): TraySlotState {
        val bounds = config.traySlotBounds(slotIndex)
        val x0 = (bounds.left * width).roundToInt()
        val x1 = (bounds.right * width).roundToInt()
        val y0 = (bounds.top * height).roundToInt()
        val y1 = (bounds.bottom * height).roundToInt()
        val slotW = x1 - x0
        val slotH = y1 - y0
        if (slotW < 10 || slotH < 10) return TraySlotState.Empty

        // Sample slot perimeter to get exact local background color (handles Light white, Wood terracotta, etc.)
        val bgSamples = ArrayList<Int>(32)
        for (i in 0 until 8) {
            val sx = x0 + ((i + 0.5f) / 8f * slotW).roundToInt()
            bgSamples.add(frame.pixelAt(sx, y0 + 2))
            bgSamples.add(frame.pixelAt(sx, y1 - 2))
        }
        for (i in 0 until 8) {
            val sy = y0 + ((i + 0.5f) / 8f * slotH).roundToInt()
            bgSamples.add(frame.pixelAt(x0 + 2, sy))
            bgSamples.add(frame.pixelAt(x1 - 2, sy))
        }
        val bgRgb = medianColor(bgSamples)
        val bgSat = saturationOf(bgRgb)
        val bgLum = luminanceOf(bgRgb)

        // Dense 32x32 grid sampling inside the slot
        val gridN = 32
        val fgGrid = BooleanArray(gridN * gridN)
        var fgCount = 0
        var minGx = gridN
        var maxGx = -1
        var minGy = gridN
        var maxGy = -1

        for (gy in 0 until gridN) {
            val py = y0 + ((gy + 0.5f) / gridN * slotH).roundToInt()
            for (gx in 0 until gridN) {
                val px = x0 + ((gx + 0.5f) / gridN * slotW).roundToInt()
                val rgb = frame.pixelAt(px, py)
                val dist = rgbDistance(rgb, bgRgb)
                val sat = saturationOf(rgb)
                val lum = luminanceOf(rgb)

                // Reject subtle gray/dark drop shadows while keeping vibrant block faces & bevels
                val isShadowLike = (lum < bgLum) &&
                    (abs(sat - bgSat) < 0.14f) &&
                    (sat < 0.55f) &&
                    (dist < 78f)

                val isForeground = !isShadowLike && (
                    dist > 54f ||
                        (abs(sat - bgSat) > 0.30f && dist > 32f)
                    )

                if (isForeground) {
                    fgGrid[gy * gridN + gx] = true
                    fgCount++
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                }
            }
        }

        if (fgCount < 6 || maxGx < minGx || maxGy < minGy) {
            return TraySlotState.Empty
        }

        // Convert bounding box to normalized screen-width units to match against mini-tile pitch
        val bboxWidthNorm = ((maxGx - minGx + 1).toFloat() / gridN) * (bounds.right - bounds.left)
        val bboxHeightNorm = ((maxGy - minGy + 1).toFloat() / gridN) * (bounds.bottom - bounds.top) *
            (height.toFloat() / width.toFloat()) // express height in screen-width units

        val pitch = config.expectedMiniTileWidthFraction

        var bestPiece: PieceShape? = null
        var bestScore = Float.MAX_VALUE

        for (candidate in PieceCatalog.ALL) {
            val expectedW = candidate.width * pitch
            val expectedH = candidate.height * pitch
            // Pitch tolerance: allow +/- 45% scale variation across game animations/aspect ratios
            val wRatio = bboxWidthNorm / expectedW
            val hRatio = bboxHeightNorm / expectedH
            if (wRatio !in 0.55f..1.55f || hRatio !in 0.55f..1.55f) continue

            // Compare shape aspect ratio consistency
            val scalePenalty = abs(wRatio - 1f) + abs(hRatio - 1f) + abs(wRatio - hRatio) * 1.5f

            // Sample each cell of the candidate's (width x height) grid inside the tight bounding box
            var bitError = 0f
            val spanX = (maxGx - minGx + 1).toFloat()
            val spanY = (maxGy - minGy + 1).toFloat()

            for (r in 0 until candidate.height) {
                for (c in 0 until candidate.width) {
                    val cellExpectedFilled = ((candidate.mask ushr (r * 8 + c)) and 1L) == 1L
                    // Sample 3x3 sub-points around the center of cell (r, c) inside tight bbox
                    var hits = 0
                    var total = 0
                    for (sy in -1..1) {
                        val fy = minGy + ((r + 0.5f + sy * 0.18f) / candidate.height) * spanY
                        val iy = fy.roundToInt().coerceIn(minGy, maxGy)
                        for (sx in -1..1) {
                            val fx = minGx + ((c + 0.5f + sx * 0.18f) / candidate.width) * spanX
                            val ix = fx.roundToInt().coerceIn(minGx, maxGx)
                            if (fgGrid[iy * gridN + ix]) hits++
                            total++
                        }
                    }
                    val fillFrac = hits.toFloat() / total
                    bitError += if (cellExpectedFilled) {
                        max(0f, 0.75f - fillFrac) * 2.2f
                    } else {
                        max(0f, fillFrac - 0.15f) * 2.8f
                    }
                }
            }

            val totalScore = bitError + scalePenalty * 0.65f
            if (totalScore < bestScore) {
                bestScore = totalScore
                bestPiece = candidate
            }
        }

        return if (bestPiece != null && bestScore < 2.2f) {
            TraySlotState.Recognized(slotIndex, bestPiece)
        } else {
            TraySlotState.Empty
        }
    }

    private data class PatchStats(val meanRgb: Int, val maxChannelSpread: Int)

    private fun samplePatchStats(
        frame: PixelFrame,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        dx: Int,
        dy: Int
    ): PatchStats {
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
        return PatchStats(meanRgb, spread)
    }

    private fun medianColor(samples: List<Int>): Int {
        val rs = samples.map { (it ushr 16) and 0xFF }.sorted()
        val gs = samples.map { (it ushr 8) and 0xFF }.sorted()
        val bs = samples.map { it and 0xFF }.sorted()
        val mid = samples.size / 2
        return (0xFF shl 24) or (rs[mid] shl 16) or (gs[mid] shl 8) or bs[mid]
    }

    companion object {
        fun rgbDistance(c1: Int, c2: Int): Float {
            val dr = ((c1 ushr 16) and 0xFF) - ((c2 ushr 16) and 0xFF)
            val dg = ((c1 ushr 8) and 0xFF) - ((c2 ushr 8) and 0xFF)
            val db = (c1 and 0xFF) - (c2 and 0xFF)
            return sqrt((dr * dr + dg * dg + db * db).toFloat())
        }

        fun saturationOf(rgb: Int): Float {
            val r = ((rgb ushr 16) and 0xFF) / 255f
            val g = ((rgb ushr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            return if (maxC == 0f) 0f else (maxC - minC) / maxC
        }

        fun maxChannelOf(rgb: Int): Int {
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
            return max(r, max(g, b))
        }

        fun luminanceOf(rgb: Int): Float {
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }
    }
}
