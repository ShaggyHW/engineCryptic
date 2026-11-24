package com.projectx.webwalker

/** Tile keys and the step cost the link set is indexed and priced in. */
internal object WebGrid {
    const val STEP_COST = 1000

    // x and y each fit in 14 bits, leaving room for the plane; the key stays a positive Int.
    fun key(x: Int, y: Int, plane: Int): Int = (plane shl 28) or (x shl 14) or y

    fun tileX(key: Int): Int = (key shr 14) and 0x3FFF

    fun tileY(key: Int): Int = key and 0x3FFF

    fun tilePlane(key: Int): Int = (key ushr 28) and 0x3
}
