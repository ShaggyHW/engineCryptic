package com.projectx.traversal.pathing

import com.projectx.traversal.TraversalNode
import com.projectx.traversal.TraversalNodeList
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.nodes.ChebychevNode
import world.gregs.voidps.type.Tile
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [PathingTraversal.tourNodes]: legs meeting at a waypoint are one walk through it, and routed waypoints are stops. */
class TourStitchTest {
    private val routing = NavOptions()
    private val a = Tile.of(3200, 3200, 0)
    private val b = Tile.of(3210, 3200, 0)
    private val c = Tile.of(3210, 3215, 0)

    @Test
    fun `two walking legs are one node through the waypoint`() {
        val nodes = nodes(PathingTraversal.tourNodes<Nothing>(routing, listOf(PathingTraversal.Leg(a, b, true, 10.0, moves(a, b)), PathingTraversal.Leg(b, c, true, 15.0, moves(b, c)))))
        assertEquals(1, nodes.size)
        val tiles = (nodes[0] as WalkEquivalent).routeTiles()
        assertTrue(b in tiles)
        assertEquals(a, tiles.first())
        assertEquals(c, tiles.last())
        assertEquals(tiles.indexOf(b), tiles.lastIndexOf(b))
    }

    @Test
    fun `an unrouted leg still ends on its waypoint, which is no stop`() {
        val legs = listOf(PathingTraversal.Leg(a, b, true, 10.0, moves(a, b)), PathingTraversal.Leg(b, c, false, 0.0, emptyList()))
        val node = assertIs<ChebychevNode>(nodes(PathingTraversal.tourNodes<Nothing>(routing, legs)).single())
        assertEquals(c, node.routeTiles().last())
        val stops = assertNotNull(node.stops)
        assertTrue(stops.isOpen(b))
        assertFalse(stops.isOpen(c))
    }

    @Test
    fun `a short leg is walked on to its waypoint`() {
        val shortOfB = Tile.of(3208, 3200, 0)
        val legs = listOf(PathingTraversal.Leg(a, b, true, 10.0, moves(a, shortOfB)), PathingTraversal.Leg(b, c, true, 15.0, moves(b, c)))
        val tiles = (nodes(PathingTraversal.tourNodes<Nothing>(routing, legs)).first() as WalkEquivalent).routeTiles()
        assertTrue(b in tiles)
    }

    private fun moves(from: Tile, to: Tile): List<Step> {
        val out = arrayListOf<Step>(Step.Move(from))
        var x = from.x
        var y = from.y
        while (x != to.x || y != to.y) {
            x += (to.x - x).sign
            y += (to.y - y).sign
            out += Step.Move(Tile.of(x, y, from.plane))
        }
        return out
    }

    private fun nodes(list: TraversalNodeList): List<TraversalNode> = generateSequence(list.head) { it.next }.toList()
}
