package com.projectx.traversal.nodes

import com.projectx.game.interfaces.IFSlot
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.hops.HopLearning
import com.projectx.traversal.hops.ScheduleStore
import com.projectx.traversal.local.LiveMapFixture
import com.projectx.traversal.local.LiveMapFixture.Companion.OX
import com.projectx.traversal.local.LiveMapFixture.Companion.OY
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.projectx.script.api.Area as WorldArea
import com.projectx.util.Area as FlatArea

/** The shared node helpers: pacing and walk-click memory, the menu guard, and the interaction timings the schedule reads. */
class InteractionNodesTest {
    private var now = 1_000_000L

    @BeforeTest
    fun setUp() {
        HopLearning.installForTests(null, null)
        ScheduleStore.installForTests(null, null)
        MenuGuard.resetForTests()
        MenuGuard.clock = { now }
        TraversalSupport.resetWalkClickForTests()
        LocalNav.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        ScheduleStore.installForTests(null, null)
        MenuGuard.resetForTests()
        TraversalSupport.resetWalkClickForTests()
        LocalNav.resetForTests()
        LiveMapFixture.uninstall()
    }

    private fun chain(vararg nodes: TraversalNode): List<TraversalNode> {
        for (i in 1 until nodes.size) {
            nodes[i - 1].next = nodes[i]
            nodes[i].prev = nodes[i - 1]
        }
        return nodes.toList()
    }

    @Test
    fun `click gaps vary around their mean and never collapse`() {
        val gaps = (1..2_000).map { TraversalSupport.clickGapMs(600) }
        assertTrue(gaps.all { it >= 150 })
        assertTrue(gaps.toSet().size > 50)
        assertTrue(gaps.average() in 550.0..650.0)
    }

    @Test
    fun `within and inside never count a tile on another floor, the flat area aside`() {
        assertTrue(TraversalSupport.within(Tile.of(3200, 3200, 0), Tile.of(3203, 3200, 0), 5))
        assertFalse(TraversalSupport.within(Tile.of(3200, 3200, 1), Tile.of(3200, 3200, 0), 5))
        assertFalse(TraversalSupport.within(null, Tile.of(3200, 3200, 0), 5))
        val upstairs = WorldArea.Rectangular(Tile.of(3200, 3200, 1), Tile.of(3205, 3205, 1))
        assertFalse(TraversalSupport.inside(upstairs, Tile.of(3201, 3201, 0)))
        assertTrue(TraversalSupport.inside(upstairs, Tile.of(3201, 3201, 1)))
        assertTrue(TraversalSupport.inside(FlatArea(3200, 3200, 3205, 3205), Tile.of(3201, 3201, 1)))
    }

    @Test
    fun `the last walk click is remembered until a hop replaces it`() {
        val goal = Tile.of(3200, 3200, 0)
        assertFalse(TraversalSupport.walkClickSinceHop())
        TraversalSupport.noteWalkClick(goal)
        assertTrue(TraversalSupport.recentlyClicked(goal, 2_000))
        assertFalse(TraversalSupport.recentlyClicked(Tile.of(3201, 3200, 0), 2_000))
        assertEquals(goal, TraversalSupport.lastWalkClickOrNull())
        val at = TraversalSupport.lastWalkClickAt()
        TraversalSupport.forgetWalkClick()
        assertNull(TraversalSupport.lastWalkClickOrNull())
        assertEquals(at, TraversalSupport.lastWalkClickAt())
    }

    @Test
    fun `the menu's opener is the node before its run of clicks`() {
        val opener = ObjectNode(ObjectIdentifier.IdRef(1), "Teleport", null as WorldArea?)
        val (_, first, second) = chain(opener, IFSlotNode(IFSlot(1188, 1)), IFSlotNode(IFSlot(720, 2)))
        assertTrue(MenuGuard.opensMenu(opener))
        assertFalse(MenuGuard.opensMenu(second))
        assertSame(opener, MenuGuard.opener(second))
        assertSame(opener, MenuGuard.opener(first))
    }

    @Test
    fun `under attack the opener re-routes once per half minute`() {
        val opener = ObjectNode(ObjectIdentifier.IdRef(1), "Teleport", null as WorldArea?)
        chain(opener, IFSlotNode(IFSlot(1188, 1)))
        var asked = 0
        val repath = { asked++; true }
        MenuGuard.underAttack = { false }
        assertFalse(MenuGuard.beforeOpening(opener, repath))
        assertEquals(0, asked)
        MenuGuard.underAttack = { true }
        assertTrue(MenuGuard.beforeOpening(opener, repath))
        assertFalse(MenuGuard.beforeOpening(opener, repath))
        assertEquals(1, asked)
        now += MenuGuard.SAME_OPENER_MS + 1
        assertTrue(MenuGuard.beforeOpening(opener, repath))
        assertEquals(2, asked)
    }

    @Test
    fun `a node with no menu after it never asks`() {
        val node = ObjectNode(ObjectIdentifier.IdRef(1), "Climb-up", null as WorldArea?)
        MenuGuard.underAttack = { true }
        assertFalse(MenuGuard.beforeOpening(node) { error("no re-route expected") })
    }

    @Test
    fun `interaction timings start from the type default and learn per action`() {
        val climb = ObjectNode(ObjectIdentifier.IdRef(1), "Climb-over", null as WorldArea?)
        val jump = ObjectNode(ObjectIdentifier.IdRef(2), "Jump-over", null as WorldArea?)
        val door = DoorNode(DoorInfo(-1, 1, t(101, 100), t(100, 100), t(101, 100), t(100, 100), "Slash"), DoorDirection.IN)
        assertEquals("ObjectNode:Climb-over", Interactions.key(climb))
        assertEquals("DoorNode:Slash", Interactions.key(door))
        assertEquals(3_000L, Interactions.deadMs(climb))
        assertEquals(1_800L, Interactions.deadMs(door))
        assertEquals(-1L, Interactions.deadMs(NpcNode(1, "Talk-to")))
        ScheduleStore.installForTests(
            mapOf(
                "dead:ObjectNode:Climb-over" to ScheduleStore.Sums(1.0, 5_000.0, 25_000_000.0),
                "dead:ObjectNode" to ScheduleStore.Sums(3.0, 12_000.0, 48_000_000.0),
            ),
            null,
        )
        assertEquals(4_000L, Interactions.deadMs(climb))
        assertEquals(3_750L, Interactions.deadMs(jump))
    }

    @Test
    fun `the walk after an interaction is its far side, the open doors on it, and a landing when it jumps`() {
        wallWithGap(100).install()
        val start = TileExactNode(t(90, 100), customReached = { false })
        val climb = ObjectNode(ObjectIdentifier.IdRef(1), "Climb-over", WorldArea.Rectangular(t(98, 100), t(98, 100)), 10, null, t(95, 100))
        val door = DoorNode(DoorInfo(-1, 1, t(101, 100), t(100, 100), t(101, 100), t(100, 100)), DoorDirection.IN)
        chain(start, climb, door, NpcNode(1, "Talk-to"))
        val legs = Interactions.after(start, t(90, 100))
        assertEquals(1, legs.size)
        assertEquals("ObjectNode:Climb-over", legs[0].what)
        assertEquals(3_000L, legs[0].deadMs)
        assertEquals(listOf(t(98, 100), t(100, 100), t(101, 100)), legs[0].tiles)
        assertEquals("ObjectNode:Climb-over 3.0 s + 3 tiles", Interactions.describe(legs))
        assertSame(climb, Interactions.between(start, door))
        assertNull(Interactions.between(start, door.next!!))
        assertTrue(Interactions.lands(null, t(0, 0)))
        assertTrue(Interactions.lands(t(0, 0), t(13, 0)))
        assertFalse(Interactions.lands(t(0, 0), t(12, 0)))
        assertEquals(emptyList(), Interactions.farSide(listOf(t(0, 0))))
        val above = Tile.of(3188, 3432, 0)
        val below = Tile.of(3191, 9832, 0)
        assertEquals(listOf(below), Interactions.farSide(listOf(above, below)))
        assertTrue(Interactions.lands(above, below))
        assertFalse(Interactions.lands(t(100, 100), t(101, 100)))
        assertFalse(Interactions.lands(t(100, 100), Tile.of(OX + 101, OY + 100, 1)), "stairs up do not land")
    }
}
