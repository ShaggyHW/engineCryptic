package com.projectx.game.collision

/** Bits of a cell's flag word. Walls and corners are stamped on both sides of the edge they sit on. */
internal object CollisionFlags {
    const val WALL_W = 1 shl 0
    const val WALL_S = 1 shl 1
    const val WALL_E = 1 shl 2
    const val WALL_N = 1 shl 3
    const val CORNER_NW = 1 shl 4
    const val CORNER_SW = 1 shl 5
    const val CORNER_SE = 1 shl 6
    const val CORNER_NE = 1 shl 7
    const val BLOCK_LOC = 1 shl 8
    const val BLOCK_FLOOR = 1 shl 9
    const val BLOCK_DECOR = 1 shl 10
    const val BLOCK_ANY = BLOCK_LOC or BLOCK_FLOOR or BLOCK_DECOR
    const val WALL_ANY = WALL_W or WALL_S or WALL_E or WALL_N
    const val CORNER_ANY = CORNER_NW or CORNER_SW or CORNER_SE or CORNER_NE
    const val WALK_MASK = 0x7FF

    /** The projectile (line of sight) layer is the walk layer shifted up by this. */
    const val PROJ_SHIFT = 12

    /** The cell's square is served from static data: the client has not loaded it yet. */
    const val STATIC = 1 shl 27
    const val CHANGED = 1 shl 28
    const val TERRAIN_UNKNOWN = 1 shl 29
    const val BRIDGE = 1 shl 30

    fun projectileBits(flags: Int): Int = (flags ushr PROJ_SHIFT) and WALK_MASK
}

/** A set bit lets a size-1 mover step that way. A step that would leave the window is never set. */
internal object MoveBits {
    const val W = 1 shl 0
    const val S = 1 shl 1
    const val E = 1 shl 2
    const val N = 1 shl 3
    const val NW = 1 shl 4
    const val SW = 1 shl 5
    const val SE = 1 shl 6
    const val NE = 1 shl 7

    fun of(dx: Int, dy: Int): Int = when {
        dx == -1 && dy == 0 -> W
        dx == 0 && dy == -1 -> S
        dx == 1 && dy == 0 -> E
        dx == 0 && dy == 1 -> N
        dx == -1 && dy == 1 -> NW
        dx == -1 && dy == -1 -> SW
        dx == 1 && dy == -1 -> SE
        dx == 1 && dy == 1 -> NE
        else -> 0
    }
}

/** One placed loc with its collision properties resolved; [plane] is the collision plane, bridge shift applied. */
internal data class CollisionLoc(
    val x: Int,
    val y: Int,
    val plane: Int,
    val shape: Int,
    val rotation: Int,
    val sizeX: Int,
    val sizeY: Int,
    val clipType: Int,
    val blocksProjectile: Boolean,
)

/** Terrain settings of one map square, `[plane][localX][localY]`: bit 1 blocked, bit 2 bridge (meaningful on plane 1). */
internal class SquareTerrain(val settings: ByteArray = ByteArray(PLANES * SQ * SQ)) {
    fun at(plane: Int, localX: Int, localY: Int): Int = settings[(plane * SQ + localX) * SQ + localY].toInt() and 0xFF

    fun set(plane: Int, localX: Int, localY: Int, value: Int) {
        settings[(plane * SQ + localX) * SQ + localY] = value.toByte()
    }

    override fun equals(other: Any?): Boolean = other is SquareTerrain && settings.contentEquals(other.settings)

    override fun hashCode(): Int = settings.contentHashCode()

    companion object {
        const val BLOCKED = 0x1
        const val BRIDGE = 0x2
        const val SQ = 64
        const val PLANES = 4
    }
}

/**
 * Flag words and move masks of a square window of all four planes, `[plane][ly * dim + lx]` with y north. Nothing here
 * ever clears a bit: a change is a reset and a re-stamp of every current loc, since two locs can share an edge.
 */
internal class CollisionGrid(val dim: Int = DIM) {
    var originX = 0
        private set
    var originY = 0
        private set
    val flags = Array(PLANES) { IntArray(dim * dim) }
    val masks = Array(PLANES) { ByteArray(dim * dim) }

    fun reset(originX: Int, originY: Int) {
        for (p in 0 until PLANES) {
            flags[p].fill(0)
            masks[p].fill(0)
        }
        this.originX = originX
        this.originY = originY
    }

    fun flagsAt(x: Int, y: Int, plane: Int): Int {
        val lx = x - originX
        val ly = y - originY
        if (plane !in 0 until PLANES || lx < 0 || ly < 0 || lx >= dim || ly >= dim) return 0
        return flags[plane][ly * dim + lx]
    }

    /** Applies one square's terrain, or marks it unknown when [terrain] is null. Apply all terrain before any loc. */
    fun applyTerrain(squareX: Int, squareY: Int, terrain: SquareTerrain?) {
        val clip = clipSquare(squareX, squareY) ?: return
        if (terrain == null) {
            for (p in 0 until PLANES) clip.forEachCell { lx, ly, _, _ -> flags[p][ly * dim + lx] = flags[p][ly * dim + lx] or CollisionFlags.TERRAIN_UNKNOWN }
            return
        }
        clip.forEachCell { lx, ly, qx, qy ->
            val bridge = if (terrain.at(1, qx, qy) and SquareTerrain.BRIDGE != 0) 1 else 0
            val cell = ly * dim + lx
            if (bridge == 1) for (p in 0 until PLANES) flags[p][cell] = flags[p][cell] or CollisionFlags.BRIDGE
            for (p in 0 until PLANES) {
                if (terrain.at(p, qx, qy) and SquareTerrain.BLOCKED == 0) continue
                val collisionPlane = p - bridge
                if (collisionPlane >= 0) flags[collisionPlane][cell] = flags[collisionPlane][cell] or CollisionFlags.BLOCK_FLOOR
            }
        }
    }

    fun stampLocs(locs: Iterable<CollisionLoc>) {
        for (loc in locs) stamp(loc)
    }

    fun stamp(loc: CollisionLoc) {
        if (loc.clipType == 0 || loc.plane !in 0 until PLANES || loc.shape !in 0..22) return
        val rotation = loc.rotation and 3
        val proj = loc.blocksProjectile
        var sizeX = loc.sizeX.coerceAtLeast(1)
        var sizeY = loc.sizeY.coerceAtLeast(1)
        if (rotation and 1 == 1) sizeX = sizeY.also { sizeY = sizeX }
        val x = loc.x
        val y = loc.y
        val plane = loc.plane
        when (loc.shape) {
            0 -> stampWall(plane, x, y, WALL_ROTATION[rotation], proj)
            2 -> {
                stampWall(plane, x, y, L_WALL_ROTATION[rotation][0], proj)
                stampWall(plane, x, y, L_WALL_ROTATION[rotation][1], proj)
            }
            1, 3 -> stampCorner(plane, x, y, CORNER_ROTATION[rotation], proj)
            9 -> stampCell(plane, x, y, CollisionFlags.BLOCK_LOC, proj)
            22 -> if (loc.clipType == 1) stampFootprint(plane, x, y, sizeX, sizeY, CollisionFlags.BLOCK_DECOR, false)
            else -> if (loc.shape >= 10) stampFootprint(plane, x, y, sizeX, sizeY, CollisionFlags.BLOCK_LOC, proj)
        }
    }

    fun markBits(squareX: Int, squareY: Int, bits: Int) {
        val clip = clipSquare(squareX, squareY) ?: return
        for (p in 0 until PLANES) clip.forEachCell { lx, ly, _, _ -> flags[p][ly * dim + lx] = flags[p][ly * dim + lx] or bits }
    }

    /**
     * Cardinals first: allowed with no wall on the source's side and the destination in the window and not fully
     * blocked (the source's own block is not asked: a loc spawned under the player can be walked off). Diagonals from
     * the cardinals: both L paths clear, no corner on the source or the destination's facing corner, destination open.
     */
    fun deriveMasks() {
        val d = dim
        val any = CollisionFlags.BLOCK_ANY
        for (p in 0 until PLANES) {
            val f = flags[p]
            val m = masks[p]
            for (y in 0 until d) for (x in 0 until d) {
                val c = f[y * d + x]
                var v = 0
                if (x > 0 && c and CollisionFlags.WALL_W == 0 && f[y * d + x - 1] and any == 0) v = v or MoveBits.W
                if (y > 0 && c and CollisionFlags.WALL_S == 0 && f[(y - 1) * d + x] and any == 0) v = v or MoveBits.S
                if (x < d - 1 && c and CollisionFlags.WALL_E == 0 && f[y * d + x + 1] and any == 0) v = v or MoveBits.E
                if (y < d - 1 && c and CollisionFlags.WALL_N == 0 && f[(y + 1) * d + x] and any == 0) v = v or MoveBits.N
                m[y * d + x] = v.toByte()
            }
            for (y in 0 until d) for (x in 0 until d) {
                val c = f[y * d + x]
                val v = m[y * d + x].toInt() and 0xFF
                var diagonal = 0
                if (v and MoveBits.N != 0 && v and MoveBits.E != 0 &&
                    m[(y + 1) * d + x].toInt() and MoveBits.E != 0 && m[y * d + x + 1].toInt() and MoveBits.N != 0 &&
                    c and CollisionFlags.CORNER_NE == 0 && f[(y + 1) * d + x + 1] and (any or CollisionFlags.CORNER_SW) == 0
                ) diagonal = diagonal or MoveBits.NE
                if (v and MoveBits.N != 0 && v and MoveBits.W != 0 &&
                    m[(y + 1) * d + x].toInt() and MoveBits.W != 0 && m[y * d + x - 1].toInt() and MoveBits.N != 0 &&
                    c and CollisionFlags.CORNER_NW == 0 && f[(y + 1) * d + x - 1] and (any or CollisionFlags.CORNER_SE) == 0
                ) diagonal = diagonal or MoveBits.NW
                if (v and MoveBits.S != 0 && v and MoveBits.E != 0 &&
                    m[(y - 1) * d + x].toInt() and MoveBits.E != 0 && m[y * d + x + 1].toInt() and MoveBits.S != 0 &&
                    c and CollisionFlags.CORNER_SE == 0 && f[(y - 1) * d + x + 1] and (any or CollisionFlags.CORNER_NW) == 0
                ) diagonal = diagonal or MoveBits.SE
                if (v and MoveBits.S != 0 && v and MoveBits.W != 0 &&
                    m[(y - 1) * d + x].toInt() and MoveBits.W != 0 && m[y * d + x - 1].toInt() and MoveBits.S != 0 &&
                    c and CollisionFlags.CORNER_SW == 0 && f[(y - 1) * d + x - 1] and (any or CollisionFlags.CORNER_NE) == 0
                ) diagonal = diagonal or MoveBits.SW
                m[y * d + x] = (v or diagonal).toByte()
            }
        }
    }

    private fun stampCell(plane: Int, x: Int, y: Int, bits: Int, proj: Boolean) {
        val lx = x.toLong() - originX
        val ly = y.toLong() - originY
        if (lx < 0 || ly < 0 || lx >= dim || ly >= dim) return
        val cell = ly.toInt() * dim + lx.toInt()
        flags[plane][cell] = flags[plane][cell] or bits or (if (proj) bits shl CollisionFlags.PROJ_SHIFT else 0)
    }

    private fun stampWall(plane: Int, x: Int, y: Int, edge: Int, proj: Boolean) {
        stampCell(plane, x, y, 1 shl edge, proj)
        stampCell(plane, x + EDGE_DX[edge], y + EDGE_DY[edge], 1 shl EDGE_OPPOSITE[edge], proj)
    }

    private fun stampCorner(plane: Int, x: Int, y: Int, corner: Int, proj: Boolean) {
        stampCell(plane, x, y, 1 shl (4 + corner), proj)
        stampCell(plane, x + CORNER_DX[corner], y + CORNER_DY[corner], 1 shl (4 + CORNER_OPPOSITE[corner]), proj)
    }

    private fun stampFootprint(plane: Int, x: Int, y: Int, sizeX: Int, sizeY: Int, bits: Int, proj: Boolean) {
        val x0 = (x.toLong() - originX).coerceAtLeast(0)
        val y0 = (y.toLong() - originY).coerceAtLeast(0)
        val x1 = (x.toLong() - originX + sizeX).coerceAtMost(dim.toLong())
        val y1 = (y.toLong() - originY + sizeY).coerceAtMost(dim.toLong())
        val value = bits or (if (proj) bits shl CollisionFlags.PROJ_SHIFT else 0)
        for (ly in y0 until y1) for (lx in x0 until x1) {
            val cell = ly.toInt() * dim + lx.toInt()
            flags[plane][cell] = flags[plane][cell] or value
        }
    }

    private class SquareClip(val lx0: Int, val lx1: Int, val ly0: Int, val ly1: Int, val sx0: Int, val sy0: Int) {
        inline fun forEachCell(action: (lx: Int, ly: Int, qx: Int, qy: Int) -> Unit) {
            for (ly in ly0 until ly1) for (lx in lx0 until lx1) action(lx, ly, sx0 + lx - lx0, sy0 + ly - ly0)
        }
    }

    private fun clipSquare(squareX: Int, squareY: Int): SquareClip? {
        val bx = squareX.toLong() * SQ - originX
        val by = squareY.toLong() * SQ - originY
        val ax0 = bx.coerceAtLeast(0)
        val ax1 = (bx + SQ).coerceAtMost(dim.toLong())
        val ay0 = by.coerceAtLeast(0)
        val ay1 = (by + SQ).coerceAtMost(dim.toLong())
        if (ax0 >= ax1 || ay0 >= ay1) return null
        return SquareClip(ax0.toInt(), ax1.toInt(), ay0.toInt(), ay1.toInt(), (ax0 - bx).toInt(), (ay0 - by).toInt())
    }

    companion object {
        const val SQ = 64
        const val WINDOW_SQUARES = 3
        const val DIM = SQ * WINDOW_SQUARES
        const val PLANES = 4

        private const val EDGE_W = 0
        private const val EDGE_S = 1
        private const val EDGE_E = 2
        private const val EDGE_N = 3
        private val EDGE_DX = intArrayOf(-1, 0, 1, 0)
        private val EDGE_DY = intArrayOf(0, -1, 0, 1)
        private val EDGE_OPPOSITE = intArrayOf(EDGE_E, EDGE_N, EDGE_W, EDGE_S)

        private const val CORNER_NW = 0
        private const val CORNER_SW = 1
        private const val CORNER_SE = 2
        private const val CORNER_NE = 3
        private val CORNER_DX = intArrayOf(-1, -1, 1, 1)
        private val CORNER_DY = intArrayOf(1, -1, -1, 1)
        private val CORNER_OPPOSITE = intArrayOf(CORNER_SE, CORNER_NE, CORNER_NW, CORNER_SW)

        private val WALL_ROTATION = intArrayOf(EDGE_W, EDGE_N, EDGE_E, EDGE_S)
        private val L_WALL_ROTATION = arrayOf(
            intArrayOf(EDGE_W, EDGE_N), intArrayOf(EDGE_N, EDGE_E), intArrayOf(EDGE_E, EDGE_S), intArrayOf(EDGE_S, EDGE_W),
        )
        private val CORNER_ROTATION = intArrayOf(CORNER_NW, CORNER_NE, CORNER_SE, CORNER_SW)
    }
}
