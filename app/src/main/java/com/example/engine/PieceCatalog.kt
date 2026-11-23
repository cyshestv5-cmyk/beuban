package com.example.engine

/**
 * Polyomino piece definition for Block Blast Lite with precomputed 64-bit placement lookup tables
 * so the solver avoids shift & boundary loops in hot search nodes.
 */
data class PieceShape(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val mask: Long,
    val cellCount: Int,
    val spawnWeight: Float,
    val isBulky: Boolean = false
) {
    val maxRow: Int = 8 - height
    val maxCol: Int = 8 - width
    val spanCols: Int = maxCol + 1
    val placementCount: Int = (maxRow + 1) * spanCols

    val placementMasks: LongArray = LongArray(placementCount)
    val placementRows: IntArray = IntArray(placementCount)
    val placementCols: IntArray = IntArray(placementCount)
    val placementWallCounts: IntArray = IntArray(placementCount)

    init {
        val edgeRowsMask = 0x00000000000000FFL or (0xFFL shl 56)
        val edgeColsMask = 0x0101010101010101L or (-0x7F7F7F7F7F7F7F80L)
        var idx = 0
        for (r in 0..maxRow) {
            for (c in 0..maxCol) {
                val pm = mask shl (r * 8 + c)
                placementMasks[idx] = pm
                placementRows[idx] = r
                placementCols[idx] = c
                placementWallCounts[idx] =
                    java.lang.Long.bitCount(pm and edgeRowsMask) +
                        java.lang.Long.bitCount(pm and edgeColsMask)
                idx++
            }
        }
    }

    /** Returns the 64-bit board mask placed at (row, col), using O(1) precomputed table. */
    fun maskAt(row: Int, col: Int): Long {
        if (row in 0..maxRow && col in 0..maxCol) {
            return placementMasks[row * spanCols + col]
        }
        return mask shl (row * 8 + col)
    }

    fun cells(): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>(cellCount)
        for (r in 0 until height) {
            for (c in 0 until width) {
                if (((mask ushr (r * 8 + c)) and 1L) == 1L) {
                    result.add(r to c)
                }
            }
        }
        return result
    }
}

object PieceCatalog {

    private fun buildMask(vararg rows: String): Long {
        var m = 0L
        for (r in rows.indices) {
            val rowStr = rows[r]
            for (c in rowStr.indices) {
                if (rowStr[c] == '#') {
                    m = m or (1L shl (r * 8 + c))
                }
            }
        }
        return m
    }

    private fun piece(
        id: String,
        label: String,
        weight: Float,
        isBulky: Boolean = false,
        vararg rows: String
    ): PieceShape {
        val height = rows.size
        val width = rows.maxOf { it.length }
        val mask = buildMask(*rows)
        val count = java.lang.Long.bitCount(mask)
        return PieceShape(
            id = id,
            label = label,
            width = width,
            height = height,
            mask = mask,
            cellCount = count,
            spawnWeight = weight,
            isBulky = isBulky
        )
    }

    val ALL: List<PieceShape> = listOf(
        // Singles & Lines
        piece("dot_1x1", "1×1", 1.1f, false, "#"),
        piece("bar_1x2", "1×2", 1.5f, false, "##"),
        piece("bar_2x1", "2×1", 1.5f, false, "#", "#"),
        piece("bar_1x3", "1×3", 1.7f, false, "###"),
        piece("bar_3x1", "3×1", 1.7f, false, "#", "#", "#"),
        piece("bar_1x4", "1×4", 1.3f, false, "####"),
        piece("bar_4x1", "4×1", 1.3f, false, "#", "#", "#", "#"),
        piece("bar_1x5", "1×5", 0.95f, true, "#####"),
        piece("bar_5x1", "5×1", 0.95f, true, "#", "#", "#", "#", "#"),

        // Squares & Rectangles
        piece("sq_2x2", "2×2", 1.9f, false, "##", "##"),
        piece("rect_2x3", "2×3", 1.15f, false, "##", "##", "##"),
        piece("rect_3x2", "3×2", 1.15f, false, "###", "###"),
        piece("sq_3x3", "3×3", 0.95f, true, "###", "###", "###"),

        // Small 2x2 Corners (3 cells)
        piece("c2_tl", "2×2 Corner TL", 1.35f, false, "##", "#."),
        piece("c2_tr", "2×2 Corner TR", 1.35f, false, "##", ".#"),
        piece("c2_bl", "2×2 Corner BL", 1.35f, false, "#.", "##"),
        piece("c2_br", "2×2 Corner BR", 1.35f, false, ".#", "##"),

        // Big 3x3 Corners (5 cells)
        piece("c3_tl", "3×3 L TL", 0.85f, true, "###", "#..", "#.."),
        piece("c3_tr", "3×3 L TR", 0.85f, true, "###", "..#", "..#"),
        piece("c3_bl", "3×3 L BL", 0.85f, true, "#..", "#..", "###"),
        piece("c3_br", "3×3 L BR", 0.85f, true, "..#", "..#", "###"),

        // 3x2 & 2x3 L / J Tetrominoes (4 cells)
        piece("l_3x2_1", "L 3×2 A", 1.25f, false, "###", "#.."),
        piece("l_3x2_2", "L 3×2 B", 1.25f, false, "###", "..#"),
        piece("l_3x2_3", "L 3×2 C", 1.25f, false, "#..", "###"),
        piece("l_3x2_4", "L 3×2 D", 1.25f, false, "..#", "###"),
        piece("l_2x3_1", "L 2×3 A", 1.25f, false, "##", "#.", "#."),
        piece("l_2x3_2", "L 2×3 B", 1.25f, false, "##", ".#", ".#"),
        piece("l_2x3_3", "L 2×3 C", 1.25f, false, "#.", "#.", "##"),
        piece("l_2x3_4", "L 2×3 D", 1.25f, false, ".#", ".#", "##"),

        // T Tetrominoes (4 cells)
        piece("t_down", "T Down", 1.15f, false, "###", ".#."),
        piece("t_up", "T Up", 1.15f, false, ".#.", "###"),
        piece("t_right", "T Right", 1.15f, false, "#.", "##", "#."),
        piece("t_left", "T Left", 1.15f, false, ".#", "##", ".#"),

        // S / Z Tetrominoes (4 cells)
        piece("s_horiz", "S Horiz", 1.05f, false, ".##", "##."),
        piece("z_horiz", "Z Horiz", 1.05f, false, "##.", ".##"),
        piece("s_vert", "S Vert", 1.05f, false, "#.", "##", ".#"),
        piece("z_vert", "Z Vert", 1.05f, false, ".#", "##", "#."),

        // Diagonal 2-cell & 3-cell (occasional Block Blast variants)
        piece("diag_2a", "Diag 2 A", 0.30f, false, "#.", ".#"),
        piece("diag_2b", "Diag 2 B", 0.30f, false, ".#", "#."),
        piece("diag_3a", "Diag 3 A", 0.25f, false, "#..", ".#.", "..#"),
        piece("diag_3b", "Diag 3 B", 0.25f, false, "..#", ".#.", "#..")
    )

    val BY_ID: Map<String, PieceShape> = ALL.associateBy { it.id }

    val BULKY_PIECES: List<PieceShape> = listOf(
        BY_ID.getValue("sq_3x3"),
        BY_ID.getValue("bar_1x5"),
        BY_ID.getValue("bar_5x1"),
        BY_ID.getValue("c3_tl"),
        BY_ID.getValue("c3_tr"),
        BY_ID.getValue("c3_bl"),
        BY_ID.getValue("c3_br")
    )

    val STANDARD_NON_DIAG: List<PieceShape> = ALL.filter { !it.id.startsWith("diag_") }
    val SMALL_MEDIUM_PIECES: List<PieceShape> = STANDARD_NON_DIAG.filter { it.cellCount <= 4 && !it.isBulky }
    val LARGE_CHALLENGE_PIECES: List<PieceShape> = STANDARD_NON_DIAG.filter { it.isBulky || it.cellCount >= 5 }

    val TOTAL_WEIGHT: Float = ALL.sumOf { it.spawnWeight.toDouble() }.toFloat()
}
