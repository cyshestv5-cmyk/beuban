package com.example

import com.example.engine.AdaptiveRoiConfig
import com.example.engine.AdaptiveScreenParser
import com.example.engine.BitboardSolver
import com.example.engine.IntArrayPixelFrame
import com.example.engine.ParsedFrame
import com.example.engine.PieceCatalog
import com.example.engine.SolverMode
import com.example.engine.TraySlotState
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
