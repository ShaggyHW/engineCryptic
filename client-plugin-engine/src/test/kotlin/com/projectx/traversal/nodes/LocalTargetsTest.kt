package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.projectx.script.api.Area as WorldArea

/** Which of several matching objects a step means: the one at its own tile, and of twins there the one its landing lies behind. */
class LocalTargetsTest {

    private fun pick(anchored: Boolean, vararg candidates: IntArray) = LocalTargets.pick(candidates.toList(), anchored)

    @Test
    fun `an anchored step takes the object at its tile, not one nearer the player`() {
        val ladders = arrayOf(intArrayOf(0, 14), intArrayOf(12, 5))
        assertEquals(0, pick(true, *ladders))
        assertEquals(1, pick(false, *ladders))
        assertEquals(0, pick(true, intArrayOf(1, 9), intArrayOf(0, 12), intArrayOf(9, 2)))
        assertEquals(0, pick(true, intArrayOf(0, -1), intArrayOf(12, 5)))
        assertEquals(-1, pick(true))
    }

    @Test
    fun `an object is near the origin only on its plane and within the radius of the whole area`() {
        val step = WorldArea.Rectangular(Tile.of(2715, 3471, 0), Tile.of(2715, 3471, 0))
        assertTrue(LocalTargets.nearOrigin(2715, 3470, 0, 1, 1, step, 10))
        assertFalse(LocalTargets.nearOrigin(2728, 3491, 0, 1, 1, step, 10))
        assertFalse(LocalTargets.nearOrigin(2715, 3470, 1, 1, 1, step, 10))
        val ring = WorldArea.Rectangular(Tile.of(3200, 3400, 0), Tile.of(3203, 3403, 0))
        assertTrue(LocalTargets.nearOrigin(3213, 3413, 0, 1, 1, ring, 10))
        assertTrue(LocalTargets.nearOrigin(3188, 3400, 0, 3, 1, ring, 10))
    }

    @Test
    fun `twins are told apart by the destination behind them`() {
        assertEquals(1, pick(true, intArrayOf(1, 0, 5), intArrayOf(1, 0, 2)))
        assertEquals(1, pick(true, intArrayOf(0, 9, 2), intArrayOf(1, 4, 3)))
        assertEquals(1, pick(true, intArrayOf(0, 9, 400), intArrayOf(1, 4, 404)))
    }

    @Test
    fun `a crossing takes its reachable end, not the far piece nearer the landing`() {
        assertEquals(0, pick(true, intArrayOf(1, 0, 3), intArrayOf(3, -1, 1)))
        assertEquals(1, pick(true, intArrayOf(3, -1, 1), intArrayOf(1, 0, 3)))
        assertEquals(1, pick(true, intArrayOf(1, -1, 5), intArrayOf(0, -1, 2)))
    }

    @Test
    fun `a walking tie goes to the one the destination lies behind`() {
        assertEquals(2, pick(true, intArrayOf(0, 0, 2), intArrayOf(1, 0, 2), intArrayOf(1, 0, 1)))
        assertEquals(1, pick(true, intArrayOf(1, 0, 2), intArrayOf(1, 0, 1), intArrayOf(1, 0, 3)))
    }

    @Test
    fun `rectangle gaps are chebyshev and zero when touching`() {
        assertEquals(1, LocalTargets.rectDistance(2722, 5270, 2, 2, 2722, 5272, 1, 1))
        assertEquals(2, LocalTargets.rectDistance(2722, 5273, 2, 2, 2722, 5276, 2, 1))
        assertEquals(0, LocalTargets.rectDistance(10, 10, 2, 2, 11, 11, 1, 1))
    }
}
