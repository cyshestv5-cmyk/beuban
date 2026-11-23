package com.example

import com.example.data.ComboState
import com.example.data.ComboTracker
import com.example.engine.AdaptiveRoiConfig
import com.example.engine.AdaptiveScreenParser
import com.example.engine.AutoLayoutCalibrator
import com.example.engine.AutoplayMoveVerifier
import com.example.engine.BitboardSolver
import com.example.engine.DropVerificationResult
import com.example.engine.GlobalProfile
import com.example.engine.IntArrayPixelFrame
import com.example.engine.MoveRecommendation
import com.example.engine.ParsedFrame
import com.example.engine.PieceCatalog
import com.example.engine.PreviewCalibrationSample
import com.example.engine.PreviewHoverDetector
import com.example.engine.SolverMode
import com.example.engine.ThinkingDepth
import com.example.engine.TraySlotState
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionAndSolverTest {

    private fun resolveAttachment(filename: String): File {
        val candidates = listOf(
            File("public/assets/aistudio/$filename"),
            File("../public/assets/aistudio/$filename")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Attachment not found: $filename (cwd=${System.getProperty("user.dir")})")
    }

    private fun loadFrame(filename: String): Triple<IntArrayPixelFrame, Int, Int> {
        val img = ImageIO.read(resolveAttachment(filename))
        val w = img.width
        val h = img.height
        val pixels = img.getRGB(0, 0, w, h, null, 0, w)
        return Triple(IntArrayPixelFrame(pixels, w, h), w, h)
    }

    private fun asciiBoard(mask: Long): String {
        val sb = StringBuilder()
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val occ = ((mask ushr (r * 8 + c)) and 1L) == 1L
                sb.append(if (occ) '#' else '.')
            }
            if (r < 7) sb.append('\n')
        }
        return sb.toString()
    }

    @Test
    fun `starting screen returns IdleOrMenu`() {
        val (frame, w, h) = loadFrame("StartingScreen_09b71782.jpg")
        val parser = AdaptiveScreenParser()
        val parsed = parser.parse(frame, w, h, AdaptiveRoiConfig(), requireStability = false)
        assertTrue("Expected IdleOrMenu on starting screen, got $parsed", parsed is ParsedFrame.IdleOrMenu)
    }

    @Test
    fun `light theme gameplay screenshot parses exact board and 3 tray pieces`() {
        val (frame, w, h) = loadFrame("InGame_View_20261123_173822_com_blockblast_lite_AppActivity_d1660d76.jpg")
        val parser = AdaptiveScreenParser()
        val parsed = parser.parse(frame, w, h, AdaptiveRoiConfig(), requireStability = false)
        assertTrue("Expected ActiveBoard on light screenshot, got $parsed", parsed is ParsedFrame.ActiveBoard)
        val active = parsed as ParsedFrame.ActiveBoard

        val expectedBoard = listOf(
            "........",
            "........",
            "........",
            "........",
            "###..###",
            "#......#",
            "..#..#..",
            "........"
        ).joinToString("\n")

        println("LIGHT BOARD:\n${asciiBoard(active.boardMask)}")
        println("LIGHT TRAY: ${active.tray}")

        assertEquals(expectedBoard, asciiBoard(active.boardMask))
        assertEquals(
            listOf("s_horiz", "z_horiz", "sq_2x2"),
            active.tray.map { (it as TraySlotState.Recognized).piece.id }
        )

        val rec = BitboardSolver.solve(active.boardMask, active.tray, SolverMode.BALANCED_SURVIVAL)
        assertNotNull(rec)
        println("LIGHT SOLVE: slot=${rec!!.slotIndex} ${rec.piece.id} @ (${rec.targetRow},${rec.targetCol}) in ${rec.solveTimeMs}ms")
    }

    @Test
    fun `wood theme full tray screenshot parses exact board and 3 tray pieces`() {
        val (frame, w, h) = loadFrame("InGame_View_20261123_173946_com_blockblast_lite_AppActivity_ceab1461.jpg")
        val parser = AdaptiveScreenParser()
        val parsed = parser.parse(frame, w, h, AdaptiveRoiConfig(), requireStability = false)
        assertTrue("Expected ActiveBoard on wood screenshot, got $parsed", parsed is ParsedFrame.ActiveBoard)
        val active = parsed as ParsedFrame.ActiveBoard

        val expectedBoard = listOf(
            ".###...#",
            "...#....",
            "..###...",
            "..###...",
            "........",
            "........",
            "#.......",
            "###....."
        ).joinToString("\n")

        println("WOOD FULL BOARD:\n${asciiBoard(active.boardMask)}")
        println("WOOD FULL TRAY: ${active.tray}")

        assertEquals(expectedBoard, asciiBoard(active.boardMask))
        assertEquals(
            listOf("l_2x3_1", "l_3x2_1", "sq_3x3"),
            active.tray.map { (it as TraySlotState.Recognized).piece.id }
        )

        val rec = BitboardSolver.solve(active.boardMask, active.tray, SolverMode.BALANCED_SURVIVAL)
        assertNotNull(rec)
        println("WOOD FULL SOLVE: slot=${rec!!.slotIndex} ${rec.piece.id} @ (${rec.targetRow},${rec.targetCol}) in ${rec.solveTimeMs}ms")
    }

    @Test
    fun `wood theme partial tray screenshot parses exact board and 2 tray pieces with empty middle`() {
        val (frame, w, h) = loadFrame("InGame_View_20261123_173940_com_blockblast_lite_AppActivity_e75e6d8e.jpg")
        val parser = AdaptiveScreenParser()
        val parsed = parser.parse(frame, w, h, AdaptiveRoiConfig(), requireStability = false)
        assertTrue("Expected ActiveBoard on wood partial screenshot, got $parsed", parsed is ParsedFrame.ActiveBoard)
        val active = parsed as ParsedFrame.ActiveBoard

        val expectedBoard = listOf(
            "........",
            "........",
            "..###...",
            "..###...",
            "........",
            "........",
            "#.......",
            "###....."
        ).joinToString("\n")

        println("WOOD PARTIAL BOARD:\n${asciiBoard(active.boardMask)}")
        println("WOOD PARTIAL TRAY: ${active.tray}")

        assertEquals(expectedBoard, asciiBoard(active.boardMask))
        assertTrue(active.tray[0] is TraySlotState.Recognized)
        assertEquals("l_3x2_2", (active.tray[0] as TraySlotState.Recognized).piece.id)
        assertTrue(active.tray[1] is TraySlotState.Empty)
        assertTrue(active.tray[2] is TraySlotState.Recognized)
        assertEquals("dot_1x1", (active.tray[2] as TraySlotState.Recognized).piece.id)
    }

    @Test
    fun `solver clears simultaneous row and column and preserves bulky space`() {
        // Row 0 has 7 cells (col 0..6), Col 7 has 7 cells (row 1..7); placing 1x1 at (0,7) clears both!
        var board = 0L
        for (c in 0 until 7) board = board or (1L shl c)
        for (r in 1 until 8) board = board or (1L shl (r * 8 + 7))

        val tray = listOf(
            TraySlotState.Recognized(0, PieceCatalog.BY_ID.getValue("dot_1x1")),
            TraySlotState.Empty,
            TraySlotState.Empty
        )
        val rec = BitboardSolver.solve(board, tray, SolverMode.COMBO_MAXIMIZER)
        assertNotNull(rec)
        assertEquals(0, rec!!.targetRow)
        assertEquals(7, rec.targetCol)
        assertEquals(2, rec.linesCleared)
    }

    @Test
    fun `half resolution ByteBuffer with rowStride padding and 2-frame stability lock works`() {
        val img = ImageIO.read(resolveAttachment("InGame_View_20261123_173946_com_blockblast_lite_AppActivity_ceab1461.jpg"))
        val halfW = img.width / 2
        val halfH = img.height / 2
        val pixelStride = 4
        val rowPadding = 16
        val rowStride = halfW * pixelStride + rowPadding
        val buf = java.nio.ByteBuffer.allocateDirect(halfH * rowStride)

        for (y in 0 until halfH) {
            for (x in 0 until halfW) {
                val argb = img.getRGB(x * 2, y * 2)
                val offset = y * rowStride + x * pixelStride
                buf.put(offset, ((argb ushr 16) and 0xFF).toByte())
                buf.put(offset + 1, ((argb ushr 8) and 0xFF).toByte())
                buf.put(offset + 2, (argb and 0xFF).toByte())
                buf.put(offset + 3, 0xFF.toByte())
            }
        }

        val frame = com.example.engine.ByteBufferPixelFrame(buf, halfW, halfH, pixelStride, rowStride)
        val parser = AdaptiveScreenParser()
        val cfg = AdaptiveRoiConfig()

        // Frame 1 triggers Animating due to 2-frame stability lock
        val first = parser.parse(frame, halfW, halfH, cfg, requireStability = true)
        assertTrue("First frame should wait for stability, got $first", first is ParsedFrame.Animating)

        // Frame 2 matches hash -> ActiveBoard
        val second = parser.parse(frame, halfW, halfH, cfg, requireStability = true)
        assertTrue("Second identical frame should emit ActiveBoard, got $second", second is ParsedFrame.ActiveBoard)
        val active = second as ParsedFrame.ActiveBoard
        assertEquals(
            listOf("l_2x3_1", "l_3x2_1", "sq_3x3"),
            active.tray.map { (it as TraySlotState.Recognized).piece.id }
        )
    }

    @Test
    fun `red theme single 3x3 tray screenshot parses exact board and tray piece`() {
        val (frame, w, h) = loadFrame("Screenshot_20261123_235903_com_blockblast_lite_AppActivity_f51f6358.jpg")
        val cfg = AdaptiveRoiConfig()
        val parser = AdaptiveScreenParser()
        val parsed = parser.parse(frame, w, h, cfg, requireStability = false)
        assertTrue("Expected ActiveBoard on red screenshot, got $parsed", parsed is ParsedFrame.ActiveBoard)
        val active = parsed as ParsedFrame.ActiveBoard

        val expectedBoard = listOf(
            ".......#",
            "......##",
            "......##",
            "........",
            "#.......",
            "#.......",
            "........",
            "........"
        ).joinToString("\n")

        assertEquals(expectedBoard, asciiBoard(active.boardMask))
        assertTrue(active.tray[0] is TraySlotState.Empty)
        assertTrue(active.tray[1] is TraySlotState.Empty)
        assertTrue(active.tray[2] is TraySlotState.Recognized)
        assertEquals("sq_3x3", (active.tray[2] as TraySlotState.Recognized).piece.id)

        val balancedRec = BitboardSolver.solve(active.boardMask, active.tray, SolverMode.BALANCED_SURVIVAL)
        assertNotNull(balancedRec)
        assertEquals(2, balancedRec!!.slotIndex)

        val comboRec = BitboardSolver.solve(active.boardMask, active.tray, SolverMode.COMBO_MAXIMIZER)
        assertNotNull(comboRec)
        assertEquals(2, comboRec!!.slotIndex)
    }

    @Test
    fun `hollow outer-perimeter overlay does not alter parsed board, tray, or stability hash`() {
        val img = ImageIO.read(resolveAttachment("Screenshot_20261123_235903_com_blockblast_lite_AppActivity_f51f6358.jpg"))
        val w = img.width
        val h = img.height
        val rawPixels = img.getRGB(0, 0, w, h, null, 0, w)
        val cfg = AdaptiveRoiConfig()

        val parser = AdaptiveScreenParser()
        val baseParsed = parser.parse(IntArrayPixelFrame(rawPixels, w, h), w, h, cfg, requireStability = false)
            as ParsedFrame.ActiveBoard
        val rec = BitboardSolver.solve(baseParsed.boardMask, baseParsed.tray, SolverMode.BALANCED_SURVIVAL)!!

        // Simulate BoardHighlightOverlay rendering on captured pixels:
        // 1) Hollow outer 11% perimeter strokes around target footprint cells
        // 2) Indicator bar + NEXT badge strictly below trayBottom
        val overlaidPixels = rawPixels.copyOf()
        val mintRgb = 0xFF00E699.toInt()
        val gridLeftPx = cfg.gridLeft * w
        val gridTopPx = cfg.gridTop * h
        val cellWPx = cfg.cellWidth() * w
        val cellHPx = cfg.cellHeight() * h
        val insetX = cellWPx * 0.11f
        val insetY = cellHPx * 0.11f
        val strokeHalf = 3
        val occupied = rec.piece.cells().toSet()

        fun drawThickSegment(x0: Int, y0: Int, x1: Int, y1: Int) {
            val minX = kotlin.math.min(x0, x1) - strokeHalf
            val maxX = kotlin.math.max(x0, x1) + strokeHalf
            val minY = kotlin.math.min(y0, y1) - strokeHalf
            val maxY = kotlin.math.max(y0, y1) + strokeHalf
            for (y in minY.coerceIn(0, h - 1)..maxY.coerceIn(0, h - 1)) {
                for (x in minX.coerceIn(0, w - 1)..maxX.coerceIn(0, w - 1)) {
                    overlaidPixels[y * w + x] = mintRgb
                }
            }
        }

        for ((dr, dc) in occupied) {
            val cl = (gridLeftPx + (rec.targetCol + dc) * cellWPx).toInt()
            val ct = (gridTopPx + (rec.targetRow + dr) * cellHPx).toInt()
            val cr = (cl + cellWPx).toInt()
            val cb = (ct + cellHPx).toInt()
            if ((dr - 1 to dc) !in occupied) drawThickSegment(cl + insetX.toInt(), ct + insetY.toInt(), cr - insetX.toInt(), ct + insetY.toInt())
            if ((dr + 1 to dc) !in occupied) drawThickSegment(cl + insetX.toInt(), cb - insetY.toInt(), cr - insetX.toInt(), cb - insetY.toInt())
            if ((dr to dc - 1) !in occupied) drawThickSegment(cl + insetX.toInt(), ct + insetY.toInt(), cl + insetX.toInt(), cb - insetY.toInt())
            if ((dr to dc + 1) !in occupied) drawThickSegment(cr - insetX.toInt(), ct + insetY.toInt(), cr - insetX.toInt(), cb - insetY.toInt())
        }

        // Below-tray indicator badge strictly below trayBottom
        val trayBottomY = (cfg.trayBottom * h).toInt() + 10
        val slotBounds = cfg.traySlotBounds(rec.slotIndex)
        val slotCx = ((slotBounds.left + slotBounds.right) * 0.5f * w).toInt()
        for (y in trayBottomY.coerceIn(0, h - 1)..(trayBottomY + 28).coerceIn(0, h - 1)) {
            for (x in (slotCx - 50).coerceIn(0, w - 1)..(slotCx + 50).coerceIn(0, w - 1)) {
                overlaidPixels[y * w + x] = mintRgb
            }
        }

        // Verify 2-frame stability across raw -> overlaid transition stays locked on same ActiveBoard
        val stableParser = AdaptiveScreenParser()
        stableParser.parse(IntArrayPixelFrame(rawPixels, w, h), w, h, cfg, requireStability = true)
        val overlaidResult = stableParser.parse(IntArrayPixelFrame(overlaidPixels, w, h), w, h, cfg, requireStability = true)
        assertTrue(
            "Overlaid frame should stay stable and ActiveBoard without self-capture drift, got $overlaidResult",
            overlaidResult is ParsedFrame.ActiveBoard
        )
        val activeOverlaid = overlaidResult as ParsedFrame.ActiveBoard
        assertEquals(baseParsed.boardMask, activeOverlaid.boardMask)
        assertEquals(baseParsed.tray, activeOverlaid.tray)

        val recAfter = BitboardSolver.solve(activeOverlaid.boardMask, activeOverlaid.tray, SolverMode.BALANCED_SURVIVAL)
        assertEquals(rec.slotIndex, recAfter?.slotIndex)
        assertEquals(rec.targetRow, recAfter?.targetRow)
        assertEquals(rec.targetCol, recAfter?.targetCol)
    }

    @Test
    fun `ultra deep solver respects 250ms budget and avoids bisecting bulky 3x3 territory`() {
        // Board has an open 4x4 sanctuary in top-right (rows 0..3, cols 4..7); placing 2x2 cleanly in
        // bottom-left leaves sq_3x3 alive, whereas placing in center kills all 3x3 slots!
        val rows = listOf(
            "####....",
            "####....",
            "####....",
            "####....",
            "........",
            "........",
            "###..###",
            "###..###"
        )
        var board = 0L
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (rows[r][c] == '#') board = board or (1L shl (r * 8 + c))
            }
        }
        val tray = listOf(
            TraySlotState.Recognized(0, PieceCatalog.BY_ID.getValue("sq_2x2")),
            TraySlotState.Recognized(1, PieceCatalog.BY_ID.getValue("bar_1x3")),
            TraySlotState.Recognized(2, PieceCatalog.BY_ID.getValue("dot_1x1"))
        )
        val rec = BitboardSolver.solve(
            boardMask = board,
            tray = tray,
            mode = SolverMode.BALANCED_SURVIVAL,
            depth = ThinkingDepth.ULTRA_DEEP
        )
        assertNotNull(rec)
        assertTrue("Ultra-deep solve should respect 250ms ceiling (took ${rec!!.solveTimeMs}ms)", rec.solveTimeMs <= 350L)
        assertTrue("Bulky capacity should remain positive on resulting end-board", rec.bulkyCapacityScore > 0)
    }

    @Test
    fun `autoplay move verifier detects exact hit, drifted lift row, new turn spawn, and failed drop`() {
        val cfg = AdaptiveRoiConfig()
        val sq2x2 = PieceCatalog.BY_ID.getValue("sq_2x2")
        val bar1x3 = PieceCatalog.BY_ID.getValue("bar_1x3")
        val preBoard = 0L
        val preTray = listOf(
            TraySlotState.Recognized(0, sq2x2),
            TraySlotState.Recognized(1, bar1x3),
            TraySlotState.Empty
        )
        val rec = MoveRecommendation(
            slotIndex = 0,
            piece = sq2x2,
            targetRow = 4,
            targetCol = 3,
            linesCleared = 0,
            fullPlan = emptyList(),
            survivabilityScore = 100f,
            bulkyCapacityScore = 10,
            monteCarloSurvivalRate = 1f,
            solveTimeMs = 12L
        )

        // 1) Exact drop -> Verified with rowError == 0 and increments sample count
        val exactBoard = sq2x2.maskAt(4, 3)
        val clearedSlotTray = listOf(TraySlotState.Empty, TraySlotState.Recognized(1, bar1x3), TraySlotState.Empty)
        val exactVerdict = AutoplayMoveVerifier.verifyDrop(cfg, 1080, 2400, preBoard, preTray, rec, exactBoard, clearedSlotTray)
        assertTrue(exactVerdict is DropVerificationResult.Verified)
        val exactUpdate = (exactVerdict as DropVerificationResult.Verified).calibrationUpdate!!
        assertEquals(0, exactUpdate.rowError)
        assertEquals(1, exactUpdate.updatedConfig.dragCalibrationSamples)
        assertEquals(1080, exactUpdate.updatedConfig.calibratedScreenWidthPx)

        // 2) Landed 3 rows higher (row 1 instead of 4) -> increases lift offset & vertical gain
        val overshotBoard = sq2x2.maskAt(1, 3)
        val overshotVerdict = AutoplayMoveVerifier.verifyDrop(cfg, 1080, 2400, preBoard, preTray, rec, overshotBoard, clearedSlotTray)
        assertTrue(overshotVerdict is DropVerificationResult.Verified)
        val cal = (overshotVerdict as DropVerificationResult.Verified).calibrationUpdate!!
        assertEquals(-3, cal.rowError)
        assertTrue("Expected higher lift offset after overshoot, got ${cal.updatedConfig.dragLiftOffsetCells}",
            cal.updatedConfig.dragLiftOffsetCells > cfg.dragLiftOffsetCells)
        assertTrue("Expected higher vertical gain after overshoot, got ${cal.updatedConfig.dragVerticalGain}",
            cal.updatedConfig.dragVerticalGain > cfg.dragVerticalGain)

        // 3) Piece still in tray slot -> NotApplied + recoverFromFailedDrop backs off upward drag
        val missVerdict = AutoplayMoveVerifier.verifyDrop(cfg, 1080, 2400, preBoard, preTray, rec, preBoard, preTray)
        assertTrue(missVerdict is DropVerificationResult.NotApplied)
        val recovered = AutoplayMoveVerifier.recoverFromFailedDrop(cfg, rec.copy(targetRow = 1))
        assertTrue(recovered.dragVerticalGain > cfg.dragVerticalGain)

        // 4) Last piece spawns fresh 3-piece turn -> NewTurnSpawned
        val lastPieceTray = listOf(TraySlotState.Empty, TraySlotState.Empty, TraySlotState.Recognized(2, sq2x2))
        val newTurnTray = listOf(
            TraySlotState.Recognized(0, sq2x2),
            TraySlotState.Recognized(1, bar1x3),
            TraySlotState.Recognized(2, sq2x2)
        )
        val spawnVerdict = AutoplayMoveVerifier.verifyDrop(
            cfg,
            1080,
            2400,
            preBoard,
            lastPieceTray,
            rec.copy(slotIndex = 2),
            sq2x2.maskAt(4, 3),
            newTurnTray
        )
        assertTrue(spawnVerdict is DropVerificationResult.NewTurnSpawned)
    }

    @Test
    fun `real-time movement calibration converges from 3-block upward overshoot within 3 drops`() {
        val screenW = 1080
        val screenH = 2400
        val actualGameLiftCells = 3.0f
        val actualGameVerticalGain = 1.25f

        var currentCfg = AdaptiveRoiConfig() // Starts with default 1.8c lift, 1.15x gain
        val dot = PieceCatalog.BY_ID.getValue("dot_1x1")
        val bar = PieceCatalog.BY_ID.getValue("bar_1x2")
        val targetRows = listOf(4, 5, 2, 3)

        var lastAbsError = 99
        for ((step, targetRow) in targetRows.withIndex()) {
            val targetCol = 3
            val endpoints = currentCfg.computeDragEndpoints(
                slotIndex = 1,
                targetRow = targetRow,
                targetCol = targetCol,
                pieceWidth = dot.width,
                pieceHeight = dot.height,
                screenWidthPx = screenW,
                screenHeightPx = screenH
            )
            // Simulate Block Blast Lite upward sensitivity & finger lift physics
            val cellHPx = currentCfg.cellHeight() * screenH
            val fingerDeltaY = endpoints.endY - endpoints.startY
            val gamePieceCenterY = endpoints.startY - actualGameLiftCells * cellHPx +
                actualGameVerticalGain * fingerDeltaY
            val landedRow = ((gamePieceCenterY - currentCfg.gridTop * screenH) / cellHPx - 0.5f)
                .let { kotlin.math.round(it).toInt() }
                .coerceIn(0, 7)

            val rec = MoveRecommendation(
                slotIndex = 1,
                piece = dot,
                targetRow = targetRow,
                targetCol = targetCol,
                linesCleared = 0,
                fullPlan = emptyList(),
                survivabilityScore = 10f,
                bulkyCapacityScore = 10,
                monteCarloSurvivalRate = 1f,
                solveTimeMs = 5L
            )
            val preTray = listOf(
                TraySlotState.Recognized(0, bar),
                TraySlotState.Recognized(1, dot),
                TraySlotState.Empty
            )
            val postTray = listOf(
                TraySlotState.Recognized(0, bar),
                TraySlotState.Empty,
                TraySlotState.Empty
            )
            val landedBoard = dot.maskAt(landedRow, targetCol)
            val verdict = AutoplayMoveVerifier.verifyDrop(
                config = currentCfg,
                screenWidthPx = screenW,
                screenHeightPx = screenH,
                preBoardMask = 0L,
                preTray = preTray,
                executed = rec,
                postBoardMask = landedBoard,
                postTray = postTray
            ) as DropVerificationResult.Verified

            val update = verdict.calibrationUpdate!!
            lastAbsError = kotlin.math.abs(update.rowError)
            println("CONVERGENCE STEP $step (target=$targetRow, landed=$landedRow): ${update.summary}")
            currentCfg = update.updatedConfig
        }

        assertEquals("Expected convergence to 0 row error after calibration steps", 0, lastAbsError)
        assertEquals(screenW, currentCfg.calibratedScreenWidthPx)
        assertEquals(screenH, currentCfg.calibratedScreenHeightPx)
        assertEquals(4, currentCfg.dragCalibrationSamples)
    }

    @Test
    fun `auto layout calibrator adapts across themes, rejects menu, and detects shifted 16-9 screen`() {
        // 1) Rejects StartingScreen
        val (menuFrame, mw, mh) = loadFrame("StartingScreen_09b71782.jpg")
        assertNull(
            "StartingScreen should be rejected by AutoLayoutCalibrator",
            AutoLayoutCalibrator.detectBoardAndTrayLayout(menuFrame, mw, mh, AdaptiveRoiConfig())
        )

        // 2) Accurately detects board & tray on Red and Wood gameplay screenshots
        for (file in listOf(
            "Screenshot_20261123_235903_com_blockblast_lite_AppActivity_f51f6358.jpg",
            "InGame_View_20261123_173946_com_blockblast_lite_AppActivity_ceab1461.jpg",
            "InGame_View_20261123_173822_com_blockblast_lite_AppActivity_d1660d76.jpg"
        )) {
            val (frame, w, h) = loadFrame(file)
            val detected = AutoLayoutCalibrator.detectBoardAndTrayLayout(
                frame = frame,
                width = w,
                height = h,
                baseConfig = AdaptiveRoiConfig(),
                fullScreenWidthPx = w,
                fullScreenHeightPx = h
            )
            assertNotNull("AutoLayoutCalibrator failed on $file", detected)
            assertTrue("gridTop out of expected range: ${detected!!.gridTop}", detected.gridTop in 0.21f..0.27f)
            assertTrue("gridLeft out of expected range: ${detected.gridLeft}", detected.gridLeft in 0.03f..0.09f)
            assertEquals(w, detected.calibratedScreenWidthPx)
            assertEquals(h, detected.calibratedScreenHeightPx)

            val parsed = AdaptiveScreenParser().parse(frame, w, h, detected, requireStability = false)
            assertTrue("Adapted config should parse ActiveBoard on $file, got $parsed", parsed is ParsedFrame.ActiveBoard)
        }

        // 3) Adapts cleanly to a synthetic 16:9 screen (540x960) where default 20:9 ROI would miss the grid!
        val w169 = 540
        val h169 = 960
        val pixels = IntArray(w169 * h169) { 0xFF335599.toInt() }
        val trueLeft = 0.08f
        val trueTop = 0.17f
        val trueGridW = 1f - 2f * trueLeft // 0.84
        val trueGridH = trueGridW * (w169.toFloat() / h169.toFloat()) // 0.4725
        val cellW = trueGridW / 8f
        val cellH = trueGridH / 8f

        val gL = (trueLeft * w169).toInt()
        val gR = ((1f - trueLeft) * w169).toInt()
        val gT = (trueTop * h169).toInt()
        val gB = ((trueTop + trueGridH) * h169).toInt()
        for (y in gT until gB) {
            for (x in gL until gR) {
                pixels[y * w169 + x] = 0xFF1F273D.toInt()
            }
        }
        // Draw strong grid divider lines
        for (i in 1..7) {
            val dx = ((trueLeft + i * cellW) * w169).toInt()
            for (y in gT until gB) {
                pixels[y * w169 + dx] = 0xFF0E1320.toInt()
                pixels[y * w169 + (dx + 1).coerceAtMost(w169 - 1)] = 0xFF0E1320.toInt()
            }
            val dy = ((trueTop + i * cellH) * h169).toInt()
            for (x in gL until gR) {
                pixels[dy * w169 + x] = 0xFF0E1320.toInt()
                pixels[(dy + 1).coerceAtMost(h169 - 1) * w169 + x] = 0xFF0E1320.toInt()
            }
        }
        // Place a bright block at board cell (0,0) so board isn't blank
        for (y in (gT + 4) until (gT + (cellH * h169).toInt() - 4)) {
            for (x in (gL + 4) until (gL + (cellW * w169).toInt() - 4)) {
                pixels[y * w169 + x] = 0xFFFFB020.toInt()
            }
        }
        // Draw a tray piece in center slot (~0.70..0.80)
        for (y in (0.72f * h169).toInt() until (0.78f * h169).toInt()) {
            for (x in (0.45f * w169).toInt() until (0.55f * w169).toInt()) {
                pixels[y * w169 + x] = 0xFF44D688.toInt()
            }
        }

        val frame169 = IntArrayPixelFrame(pixels, w169, h169)
        val adapted169 = AutoLayoutCalibrator.detectBoardAndTrayLayout(
            frame = frame169,
            width = w169,
            height = h169,
            baseConfig = AdaptiveRoiConfig(),
            fullScreenWidthPx = 1080,
            fullScreenHeightPx = 1920
        )
        assertNotNull("Expected 16:9 screen to be detected", adapted169)
        assertTrue(
            "Detected gridTop (${adapted169!!.gridTop}) should be within 0.015 of $trueTop",
            kotlin.math.abs(adapted169.gridTop - trueTop) < 0.015f
        )
        assertTrue(
            "Detected gridLeft (${adapted169.gridLeft}) should be within 0.015 of $trueLeft",
            kotlin.math.abs(adapted169.gridLeft - trueLeft) < 0.015f
        )
        assertEquals(1080, adapted169.calibratedScreenWidthPx)
        assertEquals(1920, adapted169.calibratedScreenHeightPx)
    }

    @Test
    fun `combo tracker increments on clears and resets after 3 non-clearing placements`() {
        var state = ComboTracker.reset()
        state = ComboTracker.onPlacementCleared(state, 1)
        assertEquals(1, state.comboStreak)
        assertEquals(0, state.placementsSinceClear)

        state = ComboTracker.onPlacementCleared(state, 0)
        assertEquals(1, state.comboStreak)
        assertEquals(1, state.placementsSinceClear)

        state = ComboTracker.onPlacementCleared(state, 0)
        assertEquals(1, state.comboStreak)
        assertEquals(2, state.placementsSinceClear)

        // Clearing on the 3rd gap extends the combo streak!
        state = ComboTracker.onPlacementCleared(state, 2)
        assertEquals(2, state.comboStreak)
        assertEquals(0, state.placementsSinceClear)
        assertEquals(3, state.totalLinesCleared)

        // 3 consecutive non-clearing placements reset active streak
        state = ComboTracker.onPlacementCleared(state, 0)
        state = ComboTracker.onPlacementCleared(state, 0)
        assertEquals(2, state.comboStreak)
        state = ComboTracker.onPlacementCleared(state, 0)
        assertEquals(0, state.comboStreak)
    }

    @Test
    fun `global profiles adapt compute budget and protect active combo streak`() {
        // Row 7 has 7 occupied cells (cols 0..6); dot_1x1 at (7,7) saves a combo streak at placementsSinceClear=2
        var board = 0L
        for (c in 0 until 7) board = board or (1L shl (7 * 8 + c))
        val tray = listOf(
            TraySlotState.Recognized(0, PieceCatalog.BY_ID.getValue("sq_2x2")),
            TraySlotState.Recognized(1, PieceCatalog.BY_ID.getValue("dot_1x1")),
            TraySlotState.Recognized(2, PieceCatalog.BY_ID.getValue("bar_1x3"))
        )

        val comboRec = BitboardSolver.solve(
            boardMask = board,
            tray = tray,
            profile = GlobalProfile.MAX_COMBOS_SCORE,
            comboStreak = 5,
            placementsSinceClear = 2
        )
        assertNotNull(comboRec)
        assertTrue(
            "Expected Max Combos/Score with streak=5 at gap=2 to clear the saving line cleanly",
            comboRec!!.fullPlan.any { it.linesCleared > 0 }
        )

        val fastRec = BitboardSolver.solve(
            boardMask = board,
            tray = tray,
            profile = GlobalProfile.FASTEST_SPEED,
            comboStreak = 0,
            placementsSinceClear = 0
        )
        assertNotNull(fastRec)
        assertTrue(
            "Fastest Speed open-board bitwise solve should be very quick (took ${fastRec!!.solveTimeMs}ms)",
            fastRec.solveTimeMs <= 80L
        )
    }

    @Test
    fun `preview hover detector locates translucent ghost preview cells and selects 3 anchors`() {
        val img = ImageIO.read(resolveAttachment("Screenshot_20261123_235903_com_blockblast_lite_AppActivity_f51f6358.jpg"))
        val w = img.width
        val h = img.height
        val rawPixels = img.getRGB(0, 0, w, h, null, 0, w)
        val cfg = AdaptiveRoiConfig()

        val baseFrame = IntArrayPixelFrame(rawPixels, w, h)
        val parsed = AdaptiveScreenParser().parse(baseFrame, w, h, cfg, requireStability = false) as ParsedFrame.ActiveBoard
        val baselines = PreviewHoverDetector.captureCellBaselines(baseFrame, w, h, cfg)

        // Blend a translucent cyan/green ghost preview over piece sq_2x2 at (row=3, col=4)
        val hoverPixels = rawPixels.copyOf()
        val sq2x2 = PieceCatalog.BY_ID.getValue("sq_2x2")
        val targetRow = 3
        val targetCol = 4
        val cellWPx = cfg.cellWidth() * w
        val cellHPx = cfg.cellHeight() * h

        for ((dr, dc) in sq2x2.cells()) {
            val cx = (cfg.cellCenterX(targetCol + dc) * w).toInt()
            val cy = (cfg.cellCenterY(targetRow + dr) * h).toInt()
            val hw = (cellWPx * 0.35f).toInt()
            val hh = (cellHPx * 0.35f).toInt()
            for (y in (cy - hh)..(cy + hh)) {
                for (x in (cx - hw)..(cx + hw)) {
                    val orig = hoverPixels[y * w + x]
                    val nr = (((orig ushr 16) and 0xFF) * 0.5f + 220 * 0.5f).toInt()
                    val ng = (((orig ushr 8) and 0xFF) * 0.5f + 195 * 0.5f).toInt()
                    val nb = ((orig and 0xFF) * 0.5f + 75 * 0.5f).toInt()
                    hoverPixels[y * w + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }

        val match = PreviewHoverDetector.detectHoveredPreview(
            hoverFrame = IntArrayPixelFrame(hoverPixels, w, h),
            width = w,
            height = h,
            config = cfg,
            preBoardMask = parsed.boardMask,
            preCellColors = baselines,
            piece = sq2x2
        )
        assertNotNull("Expected PreviewHoverDetector to detect ghost footprint", match)
        assertEquals(targetRow, match!!.row)
        assertEquals(targetCol, match.col)
        assertTrue("Expected high confidence match, got ${match.confidence}", match.confidence >= 0.8f)

        val plan = PreviewHoverDetector.selectGuidedCalibrationPlan(parsed.boardMask, parsed.tray)
        assertNotNull(plan)
        assertEquals(3, plan!!.anchors.size)
    }

    @Test
    fun `guided 3-point OLS preview calibration recovers exact 4-param affine kinematics`() {
        val screenW = 1080
        val screenH = 2400
        val trueLift = 2.85f
        val trueGainY = 1.28f
        val trueOffX = -0.35f
        val trueGainX = 1.09f

        val baseCfg = AdaptiveRoiConfig()
        val piece = PieceCatalog.BY_ID.getValue("sq_2x2")
        val anchors = listOf(6 to 1, 4 to 5, 2 to 2)
        val samples = ArrayList<PreviewCalibrationSample>()

        val cellWPx = baseCfg.cellWidth() * screenW
        val cellHPx = baseCfg.cellHeight() * screenH

        for ((r, c) in anchors) {
            val ep = baseCfg.computeDragEndpoints(
                slotIndex = 0,
                targetRow = r,
                targetCol = c,
                pieceWidth = piece.width,
                pieceHeight = piece.height,
                screenWidthPx = screenW,
                screenHeightPx = screenH
            )
            // Forward physics of game piece center given finger (ep.endX, ep.endY)
            val obsCenterX = ep.startX + trueGainX * (ep.endX - ep.startX) - trueOffX * cellWPx
            val obsCenterY = ep.startY + trueGainY * (ep.endY - ep.startY) - trueLift * cellHPx

            val obsCol = kotlin.math.round((obsCenterX - baseCfg.gridLeft * screenW) / cellWPx - piece.width * 0.5f)
                .toInt()
                .coerceIn(0, 8 - piece.width)
            val obsRow = kotlin.math.round((obsCenterY - baseCfg.gridTop * screenH) / cellHPx - piece.height * 0.5f)
                .toInt()
                .coerceIn(0, 8 - piece.height)

            samples.add(
                PreviewCalibrationSample(
                    slotIndex = 0,
                    startX = ep.startX,
                    startY = ep.startY,
                    fingerX = ep.endX,
                    fingerY = ep.endY,
                    observedRow = obsRow,
                    observedCol = obsCol,
                    pieceWidth = piece.width,
                    pieceHeight = piece.height
                )
            )
        }

        val cal = AutoplayMoveVerifier.fitAffineFromPreviewSamples(baseCfg, screenW, screenH, samples)
        assertNotNull(cal)
        val locked = cal!!.updatedConfig
        assertTrue(locked.isPreviewLocked)

        // Verify fitted config now places arbitrary targets onto exact row/col with 0 cell error
        for ((testSlot, target) in listOf(0 to (2 to 5), 2 to (5 to 1))) {
            val ep = locked.computeDragEndpoints(
                slotIndex = testSlot,
                targetRow = target.first,
                targetCol = target.second,
                pieceWidth = piece.width,
                pieceHeight = piece.height,
                screenWidthPx = screenW,
                screenHeightPx = screenH
            )
            val landedCenterX = ep.startX + trueGainX * (ep.endX - ep.startX) - trueOffX * cellWPx
            val landedCenterY = ep.startY + trueGainY * (ep.endY - ep.startY) - trueLift * cellHPx
            val landedCol = kotlin.math.round((landedCenterX - locked.gridLeft * screenW) / cellWPx - piece.width * 0.5f).toInt()
            val landedRow = kotlin.math.round((landedCenterY - locked.gridTop * screenH) / cellHPx - piece.height * 0.5f).toInt()
            assertEquals("Row should snap exact after 3-point preview lock", target.first, landedRow)
            assertEquals("Col should snap exact after 3-point preview lock", target.second, landedCol)
        }
    }
}
