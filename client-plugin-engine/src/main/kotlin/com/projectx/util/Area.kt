package com.projectx.util

import world.gregs.voidps.type.Tile
import kotlin.math.max
import kotlin.math.min

data class Area(val minX: Short, val maxX: Short, val minY: Short, val maxY: Short) {

    constructor(cornerX1: Int, cornerY1: Int, cornerX2: Int, cornerY2: Int) : this(
        min(cornerX1, cornerX2).toShort(),
        max(cornerX1, cornerX2).toShort(),
        min(cornerY1, cornerY2).toShort(),
        max(cornerY1, cornerY2).toShort(),
    )

    constructor(t1: Tile, t2: Tile) : this(t1.x, t1.y, t2.x, t2.y)

    fun inside(tile: Tile): Boolean = tile.x in minX..maxX && tile.y in minY..maxY

    fun toTiles(): Array<Tile> {
        val columns = maxX - minX + 1
        val rows = maxY - minY + 1
        if (columns <= 0 || rows <= 0) return emptyArray()
        return Array(columns * rows) { Tile(minX + it / rows, minY + it % rows, 0) }
    }

    fun toCopyString(): String = "new Area(new WorldTile($minX, $minY), new WorldTile($maxX, $maxY))"

    override fun toString(): String = "[$minX, $minY] x [$maxX, $maxY]"
}
