package com.projectx.traversal.nodes

import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.local.LiveMapFixture
import com.projectx.traversal.local.LiveMapFixture.Companion.OX
import com.projectx.traversal.local.LiveMapFixture.Companion.OY
import com.projectx.traversal.local.LiveMapFixture.Companion.t
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The walker's leaf pieces: tour stops, the click choice, the player's place on the route, facings and the ability flags. */
class RouteWalkingPiecesTest {

    @BeforeTest
    fun setUp() {
        LocalNav.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        LiveMapFixture.uninstall()
        LocalNav.resetForTests()
    }

    @Test
    fun `a tour stop ends the route until it is visited`() {
        LiveMapFixture().install()
        val route = ArrayList<Tile>()
        for (x in 99 downTo 56) route += t(x, 98)
        for (x in 57..92) route += t(x, 93)
        for (y in 92 downTo 70) route += t(92, y)
        val tip = t(56, 98)
        val tipIndex = route.indexOf(tip)

        val cut = RoutePicks.choose(route, t(96, 98), 40, 0)
        assertTrue(cut != null && cut.index > tipIndex, "without stops the click cuts the spur ($cut)")

        val stops = TourStops(listOf(tip))
        val r = TourStops.untilStop(route, stops, t(96, 98))
        assertTrue(r.size == tipIndex + 1 && r.last() == tip)
        val out = RoutePicks.choose(r, t(96, 98), 40, 0)
        assertEquals(OY + 98, out?.tile?.y)
        val between = RoutePicks.choose(TourStops.untilStop(route, stops, t(94, 95)), t(94, 95), 40, 2)
        assertTrue(between != null && between.nearestIndex <= tipIndex)
        val close = RoutePicks.choose(TourStops.untilStop(route, stops, t(60, 96)), t(60, 96), 40, 39)
        assertEquals(tip, close?.tile)

        val opened = TourStops.untilStop(route, stops, t(57, 97))
        assertEquals(route.size, opened.size)
        val back = RoutePicks.choose(opened, t(57, 97), 40, tipIndex)
        assertTrue(back?.tile != null && back.index > tipIndex + 3, "$back")

        val upstairs = TourStops(listOf(Tile.of(OX + 10, OY + 10, 1)))
        upstairs.visit(Tile.of(OX + 10, OY + 10, 0))
        assertTrue(upstairs.isOpen(Tile.of(OX + 10, OY + 10, 1)))
        assertSame(route, TourStops.untilStop(route, null, t(96, 98)))
    }

    @Test
    fun `a walk node holds the traversal while its tiles carry an open stop`() {
        val stops = TourStops(listOf(t(10, 10), null))
        val tiles = listOf(t(8, 10), t(9, 10), t(10, 10))
        assertTrue(stops.holds(tiles, t(0, 0)))
        assertFalse(stops.holds(listOf(t(20, 20)), t(0, 0)))
        assertFalse(stops.holds(tiles, t(11, 11)))
        assertEquals(-1, stops.barrier(tiles))
    }

    @Test
    fun `the keypoint walk stops at an open tour stop`() {
        val tiles = ArrayList<Tile>()
        for (x in 50..53) tiles += t(x, 80)
        for (y in 79 downTo 56) tiles += t(48, y)
        val stop = t(53, 80)
        val keys = listOf(t(50, 80), t(52, 80), t(48, 72), t(48, 64), t(48, 56))
        val stops = TourStops(listOf(stop))
        assertEquals(listOf(t(50, 80), t(52, 80), stop), TourStops.keypointsUntilStop(keys, tiles, stops, t(50, 80)))
        val withLine = listOf(t(50, 80), t(51, 77), t(48, 72), t(48, 56))
        assertEquals(listOf(t(50, 80), stop), TourStops.keypointsUntilStop(withLine, tiles, stops, t(50, 80)))
        assertSame(keys, TourStops.keypointsUntilStop(keys, tiles, stops, t(52, 80)))
        assertSame(keys, TourStops.keypointsUntilStop(keys, tiles, null, t(50, 80)))
    }

    @Test
    fun `beside a straight route the nearest tile is the one level with the player`() {
        val route = (100 downTo 40).map { t(60, it) }
        val i = RoutePicks.nearestIndex(route, t(69, 80), 0, route.size)
        assertEquals(t(60, 80), route[i])
        assertEquals(i, RoutePicks.nearestFrom(route, t(69, 80), 0))
        val back = ArrayList<Tile>()
        for (y in 40..70) back += t(60, y)
        for (x in 61..66) back += t(x, 70)
        for (y in 69 downTo 40) back += t(66, y)
        assertEquals(t(60, 50), back[RoutePicks.nearestIndex(back, t(63, 50), 0, back.size)])
    }

    @Test
    fun `progress is not latched from afar and the last node clicks its end tile`() {
        LiveMapFixture().install()
        val route = (40..100).map { t(60, it) }
        val afar = t(70, 190)
        val far = RoutePicks.choose(route, afar, 38, 0)!!
        assertEquals(route.size - 1, far.nearestIndex)
        val progress = RoutePicks.advance(0, far, route, afar, 38)
        assertEquals(0, progress)
        val on = RoutePicks.choose(route, t(60, 41), 38, progress)!!
        assertTrue(on.tile != null && on.index > on.nearestIndex)
        assertEquals(1, RoutePicks.advance(progress, on, route, t(60, 41), 38))

        val end = t(50, 50)
        val short = listOf(t(48, 51), t(49, 50), end)
        val loose = RoutePicks.choose(short, t(49, 50), 40, 0)
        assertTrue(loose != null && loose.tile == null)
        val exact = RoutePicks.choose(short, t(49, 50), 40, 0, exactEnd = true)!!
        assertTrue(exact.tile == end && !exact.atEnd)
        val standing = RoutePicks.choose(short, end, 40, 0, exactEnd = true)!!
        assertTrue(standing.tile == null && standing.atEnd)
    }

    @Test
    fun `a hop's facing holds only while the walk after it runs along its line`() {
        val here = t(2350, 3512)
        assertFalse(HopRules.walkRunsAlong(Direction.NORTH_WEST, here, t(2350, 3518)))
        assertTrue(HopRules.walkRunsAlong(Direction.NORTH_WEST, here, t(2344, 3518)))
        assertFalse(HopRules.walkRunsAlong(Direction.NORTH_WEST, here, t(2347, 3518)))
        assertTrue(HopRules.walkRunsAlong(Direction.NORTH_WEST, here, null))
        assertTrue(HopRules.walkRunsAlong(Direction.NORTH, here, t(2350, 3530)))
        assertEquals(Direction.SOUTH_EAST, HopRules.snap8(135))
        assertEquals(Direction.NORTH, HopRules.snap8(352))
        assertNull(HopRules.snap8(160))
        assertNull(HopRules.snap8(-1))
    }

    @Test
    fun `after a hop the player faces the nearest of the eight ways to its move`() {
        assertEquals(Direction.NORTH_EAST, HopModel.facing(10, 5))
        assertEquals(Direction.NORTH_WEST, HopModel.facing(-10, 5))
        assertEquals(Direction.EAST, HopModel.facing(10, 4))
        assertEquals(Direction.SOUTH_EAST, HopModel.facing(4, -8))
        assertEquals(Direction.EAST, HopModel.dir8(10, 5))
        assertNull(HopModel.facing(0, 0))
    }

    @Test
    fun `a surge line runs from the dive's landing within the surge's range`() {
        val mid = t(50, 50)
        assertTrue(HopRules.onSurgeLine(mid, Direction.WEST, t(44, 50)))
        assertTrue(HopRules.onSurgeLine(mid, Direction.WEST, mid))
        assertFalse(HopRules.onSurgeLine(mid, Direction.WEST, t(44, 49)))
        assertFalse(HopRules.onSurgeLine(mid, Direction.WEST, t(39, 50)))
        assertFalse(HopRules.onSurgeLine(mid, Direction.WEST, t(53, 50)))
        assertTrue(HopRules.onSurgeLine(mid, Direction.SOUTH_WEST, t(42, 42)))
    }

    @Test
    fun `the route's steps give its heading and landings map to the nearest tile ahead`() {
        val route = listOf(t(0, 0), t(1, 0), t(2, 0), t(3, 1), t(4, 2), t(5, 3))
        assertEquals(Direction.EAST, HopRules.routeStep(route, 1))
        assertNull(HopRules.routeStep(route, 0))
        assertNull(HopRules.routeStep(route, route.size))
        assertEquals(Direction.EAST, HopRules.routeAhead(route, 0))
        assertNull(HopRules.routeAhead(route, 1))
        assertEquals(Direction.NORTH_EAST, HopRules.routeAhead(route, 2))
        assertEquals(5, HopRules.routeIndexNear(route, 0, t(4, 3)))
        assertEquals(2, HopRules.routeIndexNear(route, 2, Tile.of(OX, OY, 1)))
    }

    @Test
    fun `ability flags default as Java's constructors do`() {
        assertEquals(ClientAbilities(surge = true, dive = true, accelerationPotion = false, exploreHops = true, scheduleHops = true), ClientAbilities(true, true))
        assertTrue(ClientAbilities(true, false, true).exploreHops)
        assertFalse(ClientAbilities.NONE.any())
        assertFalse(ClientAbilities.NONE.scheduleHops)
        assertTrue(ClientAbilities(false, true).any())
    }

    @Test
    fun `stuck needs stillness and two unreachable checks apart`() {
        var now = 0L
        val stuck = StuckDetector { now }
        val here = t(1, 1)
        assertFalse(stuck.stuck(here, false) { true })
        now = StuckDetector.STILL_MS
        assertFalse(stuck.stuck(here, false) { true })
        now += StuckDetector.CONFIRM_MS
        assertTrue(stuck.stuck(here, false) { true })
        assertFalse(stuck.stuck(here, true) { true })
        assertFalse(stuck.stuck(null as Tile?))
    }

    @Test
    fun `a copied walk node drops its callbacks and keeps its route`() {
        val end = listOf(t(60, 60), t(70, 60))
        val chebychev = ChebychevNode(t(50, 60), end, { false }, { true })
        val chebychevCopy = chebychev.copy() as ChebychevNode
        assertNull(chebychevCopy.fallBack)
        assertNull(chebychevCopy.reached)
        assertEquals(chebychev.routeTiles(), chebychevCopy.routeTiles())

        val tiles = listOf(t(50, 60), t(51, 60), t(52, 60))
        val path = PathNode(t(50, 60), t(52, 60), { true }, tiles)
        val pathCopy = path.copy() as PathNode
        assertNull(pathCopy.reached)
        assertEquals(tiles, pathCopy.routeTiles())
    }

    @Test
    fun `a hand-built keypoint walk hands the walker its keypoints as given`() {
        val end = listOf(t(60, 60), t(70, 60))
        assertEquals(end, ChebychevNode(t(50, 60), end).routeTiles())
        val given = listOf(t(50, 60), t(51, 60))
        assertEquals(given, ChebychevNode(t(50, 60), end, givenRouteTiles = given).routeTiles())
    }
}
