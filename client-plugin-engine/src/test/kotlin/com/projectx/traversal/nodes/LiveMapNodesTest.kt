package com.projectx.traversal.nodes

import com.projectx.traversal.local.LiveMapFixture
import com.projectx.traversal.local.LiveMapFixture.Companion.t
import com.projectx.traversal.local.LiveMapFixture.Companion.wallWithGap
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.projectx.script.api.Area as WorldArea

/** The walk-up nodes' answers off the live map: doors read open or shut, obstacles walked past, exact tiles. */
class LiveMapNodesTest {

    @BeforeTest
    fun setUp() {
        LocalNav.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        LocalNav.resetForTests()
        LiveMapFixture.uninstall()
    }

    private val door = DoorInfo(-1, 1, t(101, 100), t(100, 100), t(101, 100), t(100, 100))

    @Test
    fun `a shut door is neither open on the edge nor passable`() {
        wallWithGap(-1).install()
        assertEquals(false, LocalNav.passage(t(100, 100), t(101, 100), 3))
        assertEquals(false, DoorNode.edgeOpen(door))
        assertFalse(DoorNode(door, DoorDirection.IN).passableNow())
    }

    @Test
    fun `an open door is open on the edge and passable`() {
        wallWithGap(100).install()
        assertEquals(1, LocalNav.distance(t(100, 100), t(101, 100), 3))
        assertEquals(true, DoorNode.edgeOpen(door))
        assertTrue(DoorNode(door, DoorDirection.IN).passableNow())
        assertTrue(DoorNode.isOpen(door))
    }

    @Test
    fun `a one-tile door has no edge to read and is never walked past`() {
        wallWithGap(100).install()
        val trapdoor = DoorInfo(-1, 5492, t(100, 101), t(100, 101), t(100, 100), t(100, 100), "Lockpick")
        assertNull(DoorNode.edgeOpen(trapdoor))
        assertFalse(DoorNode(trapdoor, DoorDirection.IN).passableNow())
    }

    @Test
    fun `a door's route runs from its near side to the side it goes to`() {
        assertEquals(listOf(t(100, 100), t(101, 100)), DoorNode(door, DoorDirection.IN).routeTiles())
        assertEquals(listOf(t(101, 100), t(100, 100)), DoorNode(door, DoorDirection.OUT).routeTiles())
    }

    @Test
    fun `an obstacle is walked past only when asked to and its far side is a short walk away`() {
        val landing = WorldArea.Rectangular(t(103, 100), t(103, 100))
        val skip = ObjectNode(ObjectIdentifier.IdRef(1), "Climb-over", landing, 10, null, t(99, 100), skipWhenPassable = true)
        val keep = ObjectNode(ObjectIdentifier.IdRef(1), "Climb-over", landing, 10, null, t(99, 100))
        wallWithGap(-1).install()
        assertFalse(skip.passableNow())
        wallWithGap(100).install()
        assertTrue(skip.passableNow())
        assertFalse(keep.passableNow())
        assertEquals(listOf(t(99, 100), t(103, 100)), skip.routeTiles())
    }

    @Test
    fun `an obstacle to another floor is never walked past`() {
        wallWithGap(100).install()
        val upstairs = WorldArea.Rectangular(Tile.of(t(103, 100).x, t(103, 100).y, 1), Tile.of(t(103, 100).x, t(103, 100).y, 1))
        val stairs = ObjectNode(ObjectIdentifier.IdRef(1), "Climb-up", upstairs, 10, null, t(99, 100), skipWhenPassable = true)
        assertFalse(stairs.passableNow())
    }

    @Test
    fun `an object step without an origin has no route tiles`() {
        val node = ObjectNode(ObjectIdentifier.IdRef(1), "Open", WorldArea.Rectangular(t(103, 100), t(103, 100)))
        assertTrue(node.routeTiles().isEmpty())
        assertFalse(node.passableNow())
    }

    @Test
    fun `an exact tile is passable only without its own predicate and never aimed through`() {
        val plain = TileExactNode(t(50, 50))
        assertTrue(plain.passableNow())
        assertFalse(plain.walkThrough())
        assertEquals(listOf(t(50, 50)), plain.routeTiles())
        assertFalse(TileExactNode(t(50, 50), customReached = { false }).passableNow())
    }
}
