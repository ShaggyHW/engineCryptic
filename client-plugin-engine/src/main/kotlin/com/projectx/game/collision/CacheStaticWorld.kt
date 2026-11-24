package com.projectx.game.collision

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.MapSquareType

/**
 * Static squares straight from the game cache the engine already loads: each square's placements with the bridge
 * shift applied from its own terrain, and loc properties from the cache definitions (`ignoreAltClip` counts as no
 * collision, as it does in the client). Decoded on demand; the worker keeps the few it needs.
 */
internal object CacheStaticWorld : StaticWorld {
    private const val TERRAIN_BITS = SquareTerrain.BLOCKED or SquareTerrain.BRIDGE

    override fun terrain(squareX: Int, squareY: Int): Pair<CacheTerrainResult, SquareTerrain?> {
        val square = try {
            Cache.mapSquare(squareId(squareX, squareY))
        } catch (_: Throwable) {
            return CacheTerrainResult.CORRUPT to null
        } ?: return CacheTerrainResult.MISSING to null
        return CacheTerrainResult.OK to terrainOf(square)
    }

    override fun locProps(id: Int): LocProps? {
        val type = runCatching { Cache.loc(id) }.getOrNull() ?: return null
        return LocProps(type.sizeX, type.sizeY, if (type.ignoreAltClip) 0 else type.clipType, type.blocks)
    }

    override fun square(squareX: Int, squareY: Int): StaticSquare? {
        val square = runCatching { Cache.mapSquare(squareId(squareX, squareY)) }.getOrNull() ?: return null
        val terrain = terrainOf(square)
        val locs = ArrayList<CollisionLoc>(square.objects.size)
        val ids = ArrayList<Int>(square.objects.size)
        for (obj in square.objects) {
            if (obj.shape > MAX_PLACED_SHAPE) continue
            val bridged = terrain.at(1, obj.localX, obj.localY) and SquareTerrain.BRIDGE != 0
            val plane = if (bridged) obj.plane - 1 else obj.plane
            if (plane < 0) continue
            val props = locProps(obj.id) ?: return null
            locs += CollisionLoc(
                squareX * CollisionGrid.SQ + obj.localX,
                squareY * CollisionGrid.SQ + obj.localY,
                plane,
                obj.shape,
                obj.rotation,
                props.sizeX,
                props.sizeY,
                props.clipType,
                props.blocksProjectile,
            )
            ids += obj.id
        }
        return StaticSquare(squareX, squareY, locs, ids.toIntArray(), terrain)
    }

    private fun terrainOf(square: MapSquareType): SquareTerrain {
        val terrain = SquareTerrain()
        val flags = square.surface.flags
        for (plane in 0 until SquareTerrain.PLANES) for (x in 0 until SquareTerrain.SQ) for (y in 0 until SquareTerrain.SQ) {
            terrain.set(plane, x, y, flags[plane][x][y] and TERRAIN_BITS)
        }
        return terrain
    }

    private fun squareId(squareX: Int, squareY: Int): Int = (squareX shl 8) + squareY

    private const val MAX_PLACED_SHAPE = 22
}
