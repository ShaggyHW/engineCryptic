package com.projectx.traversal.hops

import com.projectx.pathfinder.StepRule
import world.gregs.voidps.type.Tile

/**
 * A synthetic live map for hop tests: a [DIM] x [DIM] window at ([OX], [OY]) on plane 0, walls and blocks set as
 * [WalkFlags], move masks derived from them the way the client derives its own.
 */
internal class SyntheticHopMap {
    private val flags = IntArray(DIM * DIM)

    fun wallEast(x: Int, y: Int) = apply {
        set(x, y, WalkFlags.WALL_E)
        set(x + 1, y, WalkFlags.WALL_W)
    }

    fun wallNorth(x: Int, y: Int) = apply {
        set(x, y, WalkFlags.WALL_N)
        set(x, y + 1, WalkFlags.WALL_S)
    }

    fun block(x: Int, y: Int) = apply { set(x, y, WalkFlags.BLOCK_LOC) }

    fun water(x: Int, y: Int) = apply { set(x, y, WalkFlags.BLOCK_FLOOR) }

    private fun set(x: Int, y: Int, bits: Int) {
        flags[y * DIM + x] = flags[y * DIM + x] or bits
    }

    fun install() {
        val masks = derive(flags)
        HopMap.installForTests(Installed(flags.copyOf(), masks))
    }

    private class Installed(private val flags: IntArray, private val masks: IntArray) : HopMap {
        override fun walk(): StepRule = StepRule { x, y, plane, d ->
            plane == 0 && inside(x, y) && inside(x + d.delta.x, y + d.delta.y) &&
                masks[(y - OY) * DIM + (x - OX)] and HopObservation.maskBit(d) != 0
        }

        override fun contains(x: Int, y: Int, plane: Int): Boolean = plane == 0 && inside(x, y)

        override fun known(x: Int, y: Int, plane: Int): Boolean = contains(x, y, plane)

        override fun flags(x: Int, y: Int, plane: Int): Int = if (contains(x, y, plane)) flags[(y - OY) * DIM + (x - OX)] else 0

        private fun inside(x: Int, y: Int) = x >= OX && y >= OY && x < OX + DIM && y < OY + DIM
    }

    companion object {
        const val OX = 3136
        const val OY = 3136
        const val DIM = 192

        private const val MOVE_W = 1 shl 0
        private const val MOVE_S = 1 shl 1
        private const val MOVE_E = 1 shl 2
        private const val MOVE_N = 1 shl 3
        private const val MOVE_NW = 1 shl 4
        private const val MOVE_SW = 1 shl 5
        private const val MOVE_SE = 1 shl 6
        private const val MOVE_NE = 1 shl 7

        fun t(lx: Int, ly: Int): Tile = Tile.of(OX + lx, OY + ly, 0)

        private fun derive(f: IntArray): IntArray {
            val any = WalkFlags.BLOCK_ANY
            val m = IntArray(DIM * DIM)
            for (y in 0 until DIM) for (x in 0 until DIM) {
                val c = f[y * DIM + x]
                var v = 0
                if (x > 0 && c and WalkFlags.WALL_W == 0 && f[y * DIM + x - 1] and any == 0) v = v or MOVE_W
                if (y > 0 && c and WalkFlags.WALL_S == 0 && f[(y - 1) * DIM + x] and any == 0) v = v or MOVE_S
                if (x < DIM - 1 && c and WalkFlags.WALL_E == 0 && f[y * DIM + x + 1] and any == 0) v = v or MOVE_E
                if (y < DIM - 1 && c and WalkFlags.WALL_N == 0 && f[(y + 1) * DIM + x] and any == 0) v = v or MOVE_N
                m[y * DIM + x] = v
            }
            for (y in 1 until DIM - 1) for (x in 1 until DIM - 1) {
                val c = f[y * DIM + x]
                val v = m[y * DIM + x]
                var d = 0
                val n = m[(y + 1) * DIM + x]
                val s = m[(y - 1) * DIM + x]
                val e = m[y * DIM + x + 1]
                val w = m[y * DIM + x - 1]
                if (v and MOVE_N != 0 && v and MOVE_E != 0 && n and MOVE_E != 0 && e and MOVE_N != 0 && c and WalkFlags.CORNER_NE == 0 &&
                    f[(y + 1) * DIM + x + 1] and (any or WalkFlags.CORNER_SW) == 0
                ) d = d or MOVE_NE
                if (v and MOVE_N != 0 && v and MOVE_W != 0 && n and MOVE_W != 0 && w and MOVE_N != 0 && c and WalkFlags.CORNER_NW == 0 &&
                    f[(y + 1) * DIM + x - 1] and (any or WalkFlags.CORNER_SE) == 0
                ) d = d or MOVE_NW
                if (v and MOVE_S != 0 && v and MOVE_E != 0 && s and MOVE_E != 0 && e and MOVE_S != 0 && c and WalkFlags.CORNER_SE == 0 &&
                    f[(y - 1) * DIM + x + 1] and (any or WalkFlags.CORNER_NW) == 0
                ) d = d or MOVE_SE
                if (v and MOVE_S != 0 && v and MOVE_W != 0 && s and MOVE_W != 0 && w and MOVE_S != 0 && c and WalkFlags.CORNER_SW == 0 &&
                    f[(y - 1) * DIM + x - 1] and (any or WalkFlags.CORNER_NE) == 0
                ) d = d or MOVE_SW
                m[y * DIM + x] = v or d
            }
            return m
        }
    }
}
