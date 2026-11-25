package com.projectx.puzzle.slide

class SlideBoard(val rows: Int, val cols: Int, val tiles: IntArray, val blankId: Int) {

    init {
        require(rows > 0 && cols > 0) { "a slide board needs positive dimensions, got ${rows}x$cols" }
        require(tiles.size == rows * cols) { "a ${rows}x$cols board holds ${rows * cols} pieces, got ${tiles.size}" }
    }

    val size: Int get() = rows * cols

    fun rowOf(slot: Int): Int = slot / cols

    fun colOf(slot: Int): Int = slot % cols

    fun slotOf(row: Int, col: Int): Int = row * cols + col

    fun blankSlot(): Int {
        val slot = tiles.indexOf(blankId)
        require(slot >= 0) { "piece $blankId is not on the board" }
        return slot
    }

    fun isSolved(goal: IntArray): Boolean = goal.contentEquals(tiles)

    fun applied(move: SlideMove): SlideBoard {
        val after = tiles.copyOf()
        after[move.blankSlot] = after[move.tileSlot]
        after[move.tileSlot] = blankId
        return SlideBoard(rows, cols, after, blankId)
    }

    fun signature(): Long {
        var hash = FNV_OFFSET
        for (piece in tiles) {
            var bits = piece
            repeat(Int.SIZE_BYTES) {
                hash = (hash xor (bits and 0xFF).toLong()) * FNV_PRIME
                bits = bits ushr 8
            }
        }
        return hash
    }

    companion object {
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        fun identityGoal(size: Int): IntArray = IntArray(size) { slot -> slot }
    }
}
