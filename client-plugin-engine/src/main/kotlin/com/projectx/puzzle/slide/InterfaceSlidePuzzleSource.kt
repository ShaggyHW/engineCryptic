package com.projectx.puzzle.slide

import com.projectx.puzzle.PuzzleClient

/** A puzzle whose arrangement is held in consecutive client varbits, one per slot in row-major order. */
class InterfaceSlidePuzzleSource(
    override val id: String,
    val interfaceId: Int,
    val gridComponentId: Int,
    override val rows: Int,
    override val cols: Int,
    private val firstTileVarbit: Int,
    private val blankTileId: Int,
) : SlidePuzzleSource {

    override fun isActive(): Boolean = PuzzleClient.isOpen(interfaceId)

    override fun readBoard(): SlideBoard? {
        val cells = rows * cols
        if (blankTileId !in 0 until cells) return null
        val placed = BooleanArray(cells)
        val pieces = IntArray(cells)
        for (slot in 0 until cells) {
            val piece = PuzzleClient.clientVarbit(firstTileVarbit + slot) ?: return null
            if (piece !in 0 until cells || placed[piece]) return null
            placed[piece] = true
            pieces[slot] = piece
        }
        return SlideBoard(rows, cols, pieces, blankTileId)
    }

    override fun goal(): IntArray = SlideBoard.identityGoal(rows * cols)
}
