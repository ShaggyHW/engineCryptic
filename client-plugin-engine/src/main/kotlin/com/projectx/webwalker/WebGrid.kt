package com.projectx.webwalker

/** Tile keys, step costs and wall sides the walker and its link set share. */
internal object WebGrid {
    const val STEP_COST = 1000
    const val CARDINALS = 4

    // West, north, east, south first: their index doubles as the wall side a door occupies.
    val DX = intArrayOf(-1, 0, 1, 0, -1, 1, 1, -1)
    val DY = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)

    // x and y each fit in 14 bits, leaving room for the plane; the key stays a positive Int.
    fun key(x: Int, y: Int, plane: Int): Int = (plane shl 28) or (x shl 14) or y

    fun tileX(key: Int): Int = (key shr 14) and 0x3FFF

    fun tileY(key: Int): Int = key and 0x3FFF

    fun tilePlane(key: Int): Int = (key ushr 28) and 0x3

    /** The wall side between ([x], [y]) and its neighbour in cardinal [dir], the same from either tile. */
    fun doorEdge(x: Int, y: Int, plane: Int, dir: Int): Long {
        val nx = x + DX[dir]
        val ny = y + DY[dir]
        val a = key(x, y, plane).toLong()
        val b = key(nx, ny, plane).toLong()
        return if (a < b) (a shl 32) or b else (b shl 32) or a
    }
}
