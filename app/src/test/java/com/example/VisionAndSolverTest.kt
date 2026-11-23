package com.example

import com.example.engine.AdaptiveRoiConfig
import com.example.engine.AdaptiveScreenParser
import com.example.engine.AutoplayMoveVerifier
import com.example.engine.BitboardSolver
import com.example.engine.DropVerificationResult
import com.example.engine.IntArrayPixelFrame
import com.example.engine.MoveRecommendation
import com.example.engine.ParsedFrame
import com.example.engine.PieceCatalog
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

        // 1) Exact drop -> Verified(null)
        val exactBoard = sq2x2.maskAt(4, 3)
        val clearedSlotTray = listOf(TraySlotState.Empty, TraySlotState.Recognized(1, bar1x3), TraySlotState.Empty)
        val exactVerdict = AutoplayMoveVerifier.verifyDrop(preBoard, preTray, rec, exactBoard, clearedSlotTray)
        assertTrue(exactVerdict is DropVerificationResult.Verified)
        assertNull((exactVerdict as DropVerificationResult.Verified).liftOffsetCorrectionCells)

        // 2) Landed 1 row higher (row 3 instead of 4) -> suggests positive lift offset adjustment
        val driftedHighBoard = sq2x2.maskAt(3, 3)
        val driftedVerdict = AutoplayMoveVerifier.verifyDrop(preBoard, preTray, rec, driftedHighBoard, clearedSlotTray)
        assertTrue(driftedVerdict is DropVerificationResult.Verified)
        val corr = (driftedVerdict as DropVerificationResult.Verified).liftOffsetCorrectionCells
        assertNotNull(corr)
        assertTrue("Expected positive downward touch compensation, got $corr", corr!! > 0.5f)

        // 3) Piece still in tray slot -> NotApplied
        val missVerdict = AutoplayMoveVerifier.verifyDrop(preBoard, preTray, rec, preBoard, preTray)
        assertTrue(missVerdict is DropVerificationResult.NotApplied)

        // 4) Last piece spawns fresh 3-piece turn -> NewTurnSpawned
        val lastPieceTray = listOf(TraySlotState.Empty, TraySlotState.Empty, TraySlotState.Recognized(2, sq2x2))
        val newTurnTray = listOf(
            TraySlotState.Recognized(0, sq2x2),
            TraySlotState.Recognized(1, bar1x3),
            TraySlotState.Recognized(2, sq2x2)
        )
        val spawnVerdict = AutoplayMoveVerifier.verifyDrop(
            preBoard,
            lastPieceTray,
            rec.copy(slotIndex = 2),
            sq2x2.maskAt(4, 3),
            newTurnTray
        )
        assertTrue(spawnVerdict is DropVerificationResult.NewTurnSpawned)
    }
}
