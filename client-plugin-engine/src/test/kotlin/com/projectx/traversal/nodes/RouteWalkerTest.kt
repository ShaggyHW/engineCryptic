package com.projectx.traversal.nodes

import com.projectx.traversal.hops.HopKnowledge
import com.projectx.traversal.hops.HopLearning
import com.projectx.traversal.hops.HopMap
import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.hops.ScheduleStore
import com.projectx.traversal.local.CollisionHopMap
import com.projectx.traversal.local.LiveMapFixture
import com.projectx.traversal.local.LiveMapFixture.Companion.OX
import com.projectx.traversal.local.LiveMapFixture.Companion.OY
import com.projectx.traversal.local.LiveMapFixture.Companion.t
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The route walker's click choice, its pair and line-up rules, what sent the player to a walk, and the burst replay. */
class RouteWalkerTest {

    @BeforeTest
    fun setUp() {
        LocalNav.resetForTests()
        HopLearning.installForTests(null, null)
        HopKnowledge.installForTests(null, null)
        ScheduleStore.installForTests(emptyMap(), emptyMap())
        HopMap.installForTests(CollisionHopMap)
    }

    @AfterTest
    fun tearDown() {
        HopKnowledge.installForTests(null, null)
        ScheduleStore.installForTests(null, null)
        HopMap.installForTests(null)
        LiveMapFixture.uninstall()
        LocalNav.resetForTests()
    }

    private fun open() = LiveMapFixture().install()

    /** A wall along y = 60|61 from x = 40 to 140; the route runs east under it, up past its end, and back west above it. */
    private fun uTurnRoute(): List<Tile> {
        val r = ArrayList<Tile>()
        for (x in 50..145) r += t(x, 58)
        for (y in 59..63) r += t(145, y)
        for (x in 144 downTo 60) r += t(x, 63)
        return r
    }

    @Test
    fun `the click never takes a tile across a wall that is near in a straight line but far by walking`() {
        val wall = LiveMapFixture()
        for (x in 40..140) wall.wallNorth(x, 60)
        wall.install()
        val route = uTurnRoute()
        val server = t(132, 58)
        val pick = assertNotNull(RoutePicks.choose(route, server, 40, 0))
        val p = assertNotNull(pick.tile)
        val walk = LocalNav.distance(server, p, 200)
        assertTrue(walk in 0..40 + RoutePicks.SLACK, "$p is $walk steps away")
        assertTrue(pick.index > pick.nearestIndex)
        val early = assertNotNull(RoutePicks.choose(route, t(90, 58), 40, 0))
        assertEquals(OY + 58, early.tile?.y)
    }

    @Test
    fun `a blocked route tile is skipped for the furthest open one`() {
        LiveMapFixture().block(80, 58).install()
        val route = (50..100).map { t(it, 58) }
        val pick = RoutePicks.choose(route, t(60, 58), 20, 0)
        assertEquals(t(79, 58), pick?.tile)
    }

    @Test
    fun `on the last tile nothing is clicked and three short the last tile is`() {
        open()
        val route = (50..60).map { t(it, 58) }
        val at = assertNotNull(RoutePicks.choose(route, t(60, 58), 30, 0))
        assertTrue(at.tile == null && at.atEnd)
        assertEquals(t(60, 58), RoutePicks.choose(route, t(57, 58), 30, 0)?.tile)
    }

    @Test
    fun `the click sent with a hop is planned from its landing, and one the landing leaves behind is dropped`() {
        open()
        val route = ArrayList<Tile>()
        for (x in 30..100) route += t(x, 59)
        for (y in 60..63) route += t(100 + y - 59, y)
        val end = route.last()
        val cast = t(54, 55)
        val landing = t(66, 64)
        val before = assertNotNull(RoutePicks.choose(route, cast, 30, 0))
        assertNotNull(before.tile)
        val after = assertNotNull(RoutePicks.clickAfterHop(route, before, landing, 30, 0, false))
        val afterTile = assertNotNull(after.tile)
        assertTrue(after.index > before.index, "${before.tile} #${before.index} -> $afterTile #${after.index}")
        assertTrue(landing.getLongestDelta(afterTile) <= 30)
        assertSame(before, RoutePicks.clickAfterHop(route, before, null, 30, 0, false))
        assertNull(RoutePicks.clickAfterHop(route, before, end, 30, 0, false))

        val fresh = RoutePicks.choose(route, landing, 30, 0)
        assertTrue(RoutePicks.clickBehindLanding(route, before.tile, fresh))
        val planned = assertNotNull(RoutePicks.clickAfterHop(route, before, t(64, 59), 30, 0, false))
        assertNotNull(planned.tile)
        assertFalse(RoutePicks.clickBehindLanding(route, planned.tile, RoutePicks.choose(route, t(66, 59), 30, 0)))
        assertTrue(RoutePicks.clickBehindLanding(route, t(66, 59), RoutePicks.choose(route, t(70, 59), 30, 0)))
        assertFalse(RoutePicks.clickBehindLanding(route, t(60, 70), fresh))
    }

    @Test
    fun `a surge sent with a dive needs every fire tile to give the same landing and facing`() {
        open()
        val aim = t(41, 46)
        val landing = assertNotNull(MovePrediction.diveLanding(t(50, 50), aim))
        assertEquals(aim, landing)
        assertEquals(Direction.SOUTH_WEST, HopModel.facing(-9, -4))
        assertEquals(Direction.WEST, HopModel.facing(-10, -4))
        assertNull(RouteWalker.pairFacingUnsure(listOf(t(50, 50), t(49, 50)), t(50, 50), aim, landing, Direction.SOUTH_WEST))
        val unsure = RouteWalker.pairFacingUnsure(listOf(t(50, 50), t(51, 50)), t(50, 50), aim, landing, Direction.SOUTH_WEST)
        assertTrue(unsure != null && unsure.contains("WEST"), "$unsure")
        val farW = t(30, 50)
        val landW = assertNotNull(MovePrediction.diveLanding(t(50, 50), farW))
        assertEquals(t(40, 50), landW)
        assertNotNull(RouteWalker.pairFacingUnsure(listOf(t(50, 50), t(49, 50)), t(50, 50), farW, landW, Direction.WEST))
        assertNull(RouteWalker.pairFacingUnsure(listOf(t(50, 50), t(49, 50)), t(50, 50), landW, landW, Direction.WEST))
        assertNull(RouteWalker.pairFacingUnsure(null, t(50, 50), aim, landing, Direction.SOUTH_WEST))
    }

    @Test
    fun `a line-up walk runs straight one way or not at all`() {
        open()
        val se = assertNotNull(LocalNav.serverPath(t(50, 50), t(58, 42)))
        assertTrue(RouteWalker.straight(se, Direction.SOUTH_EAST), "$se")
        val off = assertNotNull(LocalNav.serverPath(t(50, 50), t(54, 47)))
        assertFalse(RouteWalker.straight(off, Direction.SOUTH_EAST))
        assertFalse(RouteWalker.straight(off, Direction.EAST))
        val wall = LiveMapFixture()
        for (y in 40..60) wall.wallEast(54, y)
        wall.install()
        assertEquals(t(54, 50), MovePrediction.surgeLanding(t(50, 50), Direction.EAST))
    }

    @Test
    fun `a walk is keyed by the first interaction since the walk before it, and a stuck tile hands over only at the door`() {
        val before = ChebychevNode(t(10, 10), listOf(t(20, 10)))
        val after = ChebychevNode(t(90, 90), listOf(t(99, 90)))
        val charter = NpcNode("Trader Crewmember", "Charter")
        val dialog = NpcNode("Trader Crewmember")
        val nodes = listOf(before, charter, dialog, after)
        for (i in 1 until nodes.size) {
            nodes[i - 1].next = nodes[i]
            nodes[i].prev = nodes[i - 1]
        }
        assertEquals("NpcNode:Charter", RouteWalker.arrivalKey(after))
        assertEquals("teleport", RouteWalker.arrivalKey(before))
        assertEquals("NpcNode:Charter", Interactions.key(charter))
        assertEquals("NpcNode", Interactions.key(dialog))
        val door = DoorNode(DoorInfo(-1, 1, t(101, 100), t(100, 100), t(101, 100), t(100, 100)), DoorDirection.IN)
        assertEquals("DoorNode:Open", Interactions.key(door))
        assertTrue(RouteWalker.atDoor(t(98, 101), door))
        assertFalse(RouteWalker.atDoor(t(144, 117), door))
        assertFalse(RouteWalker.atDoor(Tile.of(OX + 100, OY + 100, 1), door))
    }

    @Test
    fun `a probe stand is a few walking steps ahead on the route`() {
        open()
        val route = (50..90).map { t(it, 58) }
        val i = RouteWalker.stopIndexAhead(route, 0, t(50, 58))
        assertTrue(i >= 0 && LocalNav.distance(t(50, 58), route[i], 10) in RouteWalker.PROBE_STOP_MIN_STEPS..RouteWalker.PROBE_STOP_MAX_STEPS)
        assertEquals(RouteWalker.PROBE_STOP_MIN_STEPS, i)
    }

    @Test
    fun `a probe stand is three steps ahead of the player's place on the route, and none past a wall`() {
        open()
        val route = (40..70).map { t(it, 60) }
        assertEquals(5, RouteWalker.stopIndexAhead(route, 2, t(42, 60)))
        val walled = LiveMapFixture()
        for (y in 50..70) walled.wallEast(43, y)
        walled.install()
        assertEquals(-1, RouteWalker.stopIndexAhead(route, 2, t(42, 60)))
    }

    @Test
    fun `open doors after a walk extend its tiles, a shut one does not`() {
        val walk = ChebychevNode(t(90, 100), listOf(t(100, 100)))
        val door = DoorNode(DoorInfo(-1, 1, t(101, 100), t(100, 100), t(101, 100), t(100, 100)), DoorDirection.IN)
        walk.next = door
        door.prev = walk
        val own = (90..100).map { t(it, 100) }
        LiveMapFixture.wallWithGap(-1).install()
        assertSame(own, RouteWalker.extend(own, walk))
        LiveMapFixture.wallWithGap(100).install()
        assertEquals(own + door.routeTiles(), RouteWalker.extend(own, walk))
    }

    // ---- the burst replay ----

    private val straight = (20..120).map { t(it, 100) }
    private val both = AccelerationPlanner.Kit(surge = true, dive = true, surgeCharges = 2, anyway = 0.0, clickAhead = 30)

    private fun castTimes(e: AccelerationPlanner.Estimate): List<Double> = e.plan.split(" ").map { it.substringAfter('@').toDouble() }

    @Test
    fun `on a long straight the burst replays eight hops, surge first, tight and no dive in its last tick`() {
        open()
        val e = AccelerationPlanner.simulate(straight, 0, t(20, 100), Direction.EAST, both)
        assertTrue(e.hops >= 5 && e.steps >= 50, "$e")
        assertEquals(8, e.hops, "$e")
        assertTrue(e.savingS >= AccelerationPlanner.MIN_SAVING_S, "$e")
        assertTrue(e.plan.startsWith("S"), "$e")
        assertNotNull(AccelerationPlanner.evaluate(straight, 0, t(20, 100), Direction.EAST, both, StringBuilder()))
        val at = castTimes(e)
        for (k in 1 until at.size) assertTrue(at[k] - at[k - 1] <= 0.95, "$e")
        val hops = e.plan.split(" ")
        val lastDive = AccelerationPlanner.EFFECT_S + AccelerationPlanner.BURST_MS / 1000.0 - AccelerationPlanner.TICK_S + 0.05
        for (k in hops.indices) if (hops[k].startsWith("D")) assertTrue(at[k] <= lastDive, "$e")

        val one = AccelerationPlanner.simulate(straight, 0, t(20, 100), Direction.EAST, AccelerationPlanner.Kit(true, true, 1, 0.0, 30))
        assertTrue(one.hops <= e.hops, "$one vs $e")
        val diveOnly = AccelerationPlanner.simulate(straight, 0, t(20, 100), Direction.EAST, AccelerationPlanner.Kit(false, true, 0, 0.0, 30))
        assertTrue(diveOnly.hops >= 2 && diveOnly.hops < e.hops && !diveOnly.plan.contains("S"), "$diveOnly")

        val half = AccelerationPlanner.simulate(straight, 0, t(20, 100), Direction.EAST, AccelerationPlanner.Kit(true, true, 2, 0.5, 30))
        val perHop = e.steps.toDouble() / e.hops - AccelerationPlanner.HOP_COST_S * AccelerationPlanner.TILES_PER_SECOND
        assertTrue(abs(e.savingS - half.savingS - 0.5 * perHop / AccelerationPlanner.TILES_PER_SECOND) < 0.01, "$half vs $e")
    }

    @Test
    fun `short routes and hops that come back anyway are not worth a dose`() {
        open()
        val shortRoute = straight.subList(0, 16)
        val s2 = AccelerationPlanner.simulate(shortRoute, 0, t(20, 100), Direction.EAST, both)
        assertTrue(s2.savingS < AccelerationPlanner.MIN_SAVING_S, "$s2")
        val why = StringBuilder()
        assertNull(AccelerationPlanner.evaluate(shortRoute, 0, t(20, 100), Direction.EAST, both, why))
        assertTrue(why.isNotEmpty())

        val anyway = AccelerationPlanner.simulate(straight.subList(0, 13), 0, t(20, 100), Direction.EAST, AccelerationPlanner.Kit(true, true, 2, 2.0, 30))
        assertEquals(0.0, anyway.savingS, "$anyway")
        val few = AccelerationPlanner.simulate(straight.subList(0, 36), 0, t(20, 100), Direction.EAST, both)
        val expected = (few.steps - few.hops * AccelerationPlanner.HOP_COST_S * AccelerationPlanner.TILES_PER_SECOND) / AccelerationPlanner.TILES_PER_SECOND
        assertTrue(few.hops >= 2 && abs(few.savingS - expected) < 0.01, "$few")
        assertTrue(few.savingS < AccelerationPlanner.MIN_SAVING_S, "$few")
    }

    @Test
    fun `a winding corridor saves less than a straight and a route off the eight ways still hops`() {
        open()
        val e = AccelerationPlanner.simulate(straight, 0, t(20, 100), Direction.EAST, both)
        val maze = LiveMapFixture()
        val winding = ArrayList<Tile>()
        for (col in 0 until 12) {
            val x0 = 20 + col * 4
            val up = col % 2 == 0
            for (y in 60..90) if (if (up) y != 90 else y != 60) maze.wallEast(x0 + 1, y)
            for (k in 0..30) winding += t(x0, if (up) 60 + k else 90 - k)
            for (dx in 1 until 4) winding += t(x0 + dx, if (up) 90 else 60)
        }
        maze.install()
        val w = AccelerationPlanner.simulate(winding, 0, t(20, 60), Direction.NORTH, both)
        assertTrue(w.steps < e.steps, "$w vs $e")

        open()
        val nnw = (0..160).map { t(100 - it / 2, 20 + it) }
        val a = AccelerationPlanner.simulate(nnw, 0, t(100, 20), Direction.NORTH, AccelerationPlanner.Kit(true, true, 2, 2.0, 30))
        assertTrue(a.hops >= 5, "$a")
        assertTrue(a.savingS >= AccelerationPlanner.MIN_SAVING_S, "$a")
    }

    @Test
    fun `round an elbow the surge goes north first, then the dive, then a surge on the walk a tick after its landing`() {
        val bend = LiveMapFixture()
        val elbow = (20..32).map { t(it, 100) } + (101..170).map { t(32, it) }
        for (y in 95..175) bend.wallEast(33, y)
        bend.install()
        val el = AccelerationPlanner.simulate(elbow, 0, t(20, 100), Direction.EAST, both)
        assertTrue(el.hops >= 3, "$el")
        val order = el.plan.split(" ")
        assertTrue(order[0].startsWith("S+10") && order.size > 2 && order[1].startsWith("D"), "$el")
        val diveAt = order[1].substringAfter('@').toDouble()
        val expected = AccelerationPlanner.fmt(diveAt + AccelerationPlanner.DIVE_FLIGHT_S + AccelerationPlanner.TICK_S)
        assertTrue(order[2].startsWith("S") && order[2].endsWith("@$expected"), "$el")
    }

    @Test
    fun `a kit without hops replays nothing and normalises its charges`() {
        assertEquals(0, AccelerationPlanner.Kit(false, true, 2, 0.0, 30).surgeCharges)
        assertEquals(1, AccelerationPlanner.Kit(true, false, 0, 0.0, 30).surgeCharges)
        assertEquals(0.0, AccelerationPlanner.Kit(true, true, 1, 0.0, 30, -3.0).beyondS)
        assertEquals(0, AccelerationPlanner.simulate(straight, 0, t(20, 100), Direction.EAST, AccelerationPlanner.Kit(false, false, 0, 0.0, 30)).hops)
    }
}
