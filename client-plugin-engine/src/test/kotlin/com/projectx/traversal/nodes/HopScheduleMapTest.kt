package com.projectx.traversal.nodes

import com.projectx.game.collision.CollisionMap
import com.projectx.traversal.hops.Evidence
import com.projectx.traversal.hops.HopEdges
import com.projectx.traversal.hops.HopKnowledge
import com.projectx.traversal.hops.HopLearning
import com.projectx.traversal.hops.HopMap
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
import kotlin.test.assertTrue

/** Hop landings, gains toward the walk's end and the schedule's spot scans on a synthetic live map. */
class HopScheduleMapTest {

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

    private fun line(xs: IntProgression, y: Int) = xs.map { t(it, y) }

    private val cycle = HopSchedule.cycleSteps(10_200L)

    @Test
    fun `a surge stops at a wall and runs its full range in the open`() {
        LiveMapFixture.wallWithGap(-1).install()
        assertEquals(t(100, 90), MovePrediction.surgeLanding(t(95, 90), 90.0))
        assertEquals(t(95, 100), MovePrediction.surgeLanding(t(95, 90), 0.0))
        assertEquals(t(95, 90), MovePrediction.surgeLanding(t(95, 90), null as Direction?))
    }

    @Test
    fun `the walk layer decides a dive`() {
        val fence = LiveMapFixture()
        for (y in 80..120) fence.wallEast(100, y, projectile = false)
        fence.install()
        assertFalse(MovePrediction.diveClear(t(96, 100), t(104, 100)))
        assertTrue(MovePrediction.diveClear(t(96, 100), t(96, 108)))
        LiveMapFixture().block(98, 90).install()
        assertFalse(MovePrediction.diveClear(t(95, 90), t(101, 90)))
    }

    @Test
    fun `a dive aimed past its reach lands ten tiles along the line`() {
        LiveMapFixture().block(70, 60).install()
        val from = t(55, 60)
        assertEquals(t(65, 60), MovePrediction.diveLanding(from, t(75, 60)))
        assertEquals(t(61, 60), MovePrediction.diveLanding(from, t(61, 60)))
        LiveMapFixture().block(67, 60).install()
        assertEquals(t(65, 60), MovePrediction.diveLanding(from, t(75, 60)), "a block beyond the reach does not shorten it")
        LiveMapFixture().block(60, 60).install()
        assertEquals(t(59, 60), MovePrediction.diveLanding(from, t(75, 60)))
        assertNull(MovePrediction.diveLanding(from, t(62, 60)))
    }

    @Test
    fun `hops slide round corners`() {
        LiveMapFixture().block(52, 52).install()
        assertEquals(t(60, 60), MovePrediction.surgeLanding(t(50, 50), 45.0))
        assertEquals(t(60, 60), MovePrediction.surgeLanding(t(50, 50), 30.0))
        LiveMapFixture().block(52, 53).install()
        assertTrue(MovePrediction.diveClear(t(50, 50), t(54, 56)))
        assertEquals(t(54, 56), MovePrediction.diveLanding(t(50, 50), t(54, 56)))
        assertEquals(t(60, 60), MovePrediction.surgeLanding(t(50, 50), 30.0))
        val wall = LiveMapFixture()
        for (x in 40..70) wall.wallNorth(x, 55)
        wall.install()
        assertEquals(t(50, 55), MovePrediction.surgeLanding(t(50, 50), 0.0))
        assertNull(MovePrediction.diveLanding(t(50, 50), t(50, 58)))
    }

    @Test
    fun `a landing helps when near the target or five steps closer`() {
        open()
        assertTrue(MovePrediction.helps(t(50, 50), t(58, 50), t(59, 50)))
        assertTrue(MovePrediction.helps(t(50, 50), t(55, 50), t(80, 50)))
        assertFalse(MovePrediction.helps(t(50, 50), t(53, 50), t(80, 50)))
        assertFalse(MovePrediction.helps(t(50, 50), t(50, 50), t(80, 50)))
    }

    @Test
    fun `a surge up a parallel line counts as progress toward the route ahead`() {
        val m = LiveMapFixture()
        for (y in 40..80) m.wallEast(110, y, projectile = false)
        m.install()
        val from = t(104, 50)
        val ahead = t(100, 75)
        assertTrue(RouteProgress.gain(from, MovePrediction.surgeLanding(from, 0.0), ahead) >= HopRules.SURGE_MIN_GAIN)
        val east = MovePrediction.surgeLanding(from, 90.0)
        assertEquals(t(110, 50), east)
        assertTrue(RouteProgress.gain(from, east, ahead) < HopRules.SURGE_MIN_GAIN)
    }

    @Test
    fun `a tile nothing enters counts its steps through the step off it`() {
        LiveMapFixture().block(60, 60).install()
        val field = LocalNav.field(t(70, 60), HopRules.PROGRESS_DEPTH)!!
        assertEquals(LocalNav.UNREACHABLE, field.distanceTo(t(60, 60)))
        assertEquals(10, RouteProgress.stepsOff(field, t(60, 60)))
        assertEquals(5, RouteProgress.gain(t(60, 60), t(65, 60), t(70, 60)))
        assertEquals(8, RouteProgress.stepsOff(field, t(62, 60)))
    }

    @Test
    fun `at the route's end a surge may overshoot or stop a little short`() {
        open()
        val from = t(20, 100)
        val eight = line(20..28, 100)
        val landing = MovePrediction.surgeLanding(from, 90.0)
        val gain = RouteProgress.gain(from, landing, eight.last())
        assertEquals(2, HopRules.endSteps(eight, landing))
        assertEquals(HopRules.SURGE_END_MIN_GAIN, HopRules.surgeNeed(eight, 0, from, landing))
        assertTrue(gain >= HopRules.SURGE_END_MIN_GAIN && gain < HopRules.SURGE_MIN_GAIN)
        assertEquals(HopRules.SURGE_MIN_GAIN, HopRules.surgeNeed(eight.subList(0, 8), 0, from, landing))
        assertEquals(HopRules.SURGE_MIN_GAIN, HopRules.surgeNeed(line(20..60, 100), 0, from, landing))
        assertEquals(HopRules.SURGE_END_MIN_GAIN, HopRules.surgeNeed(line(20..33, 100), 0, from, landing))
        assertEquals(HopRules.SURGE_MIN_GAIN, HopRules.surgeNeed(line(20..35, 100), 0, from, landing))

        val walled = LiveMapFixture()
        for (y in 90..110) walled.wallEast(28, y, projectile = false)
        walled.install()
        assertEquals(HopRules.SURGE_MIN_GAIN, HopRules.surgeNeed(eight, 0, from, t(30, 100)))

        LiveMapFixture().block(31, 100).block(31, 99).block(31, 101).install()
        val round = line(20..32, 100)
        val back = HopRules.endSteps(round, landing)
        assertEquals(back <= HopRules.SURGE_END_SHORT_MAX, HopRules.surgeNeed(round, 0, from, landing) == HopRules.SURGE_END_MIN_GAIN, "round a block: $back")
    }

    @Test
    fun `a dive onto the goal needs only the end need`() {
        open()
        val route = line(20..40, 100)
        assertEquals(HopRules.DIVE_END_MIN_GAIN, HopRules.diveNeed(route, 15, t(40, 100)))
        assertEquals(HopRules.DIVE_MIN_GAIN, HopRules.diveNeed(route, 10, t(36, 100)))
        assertEquals(HopRules.DIVE_MIN_GAIN, HopRules.diveNeed(line(20..120, 100), 10, t(30, 100)))
        assertTrue(HopRules.END_GATE_TILES <= 5)
    }

    @Test
    fun `a dive is checked from the tiles it fires from`() {
        open()
        val arming = HopRules.armingTiles(t(50, 60), t(70, 60))
        assertTrue(t(51, 60) in arming && t(52, 60) in arming && t(52, 62) in arming, "$arming")
        assertTrue(t(49, 60) !in arming && t(50, 61) !in arming && t(53, 60) !in arming)
        assertTrue(HopRules.armingTiles(t(70, 60), t(70, 60)).isEmpty())
        assertEquals(listOf(t(70, 60)), HopRules.armingTiles(t(69, 60), t(70, 60)))

        LiveMapFixture().block(61, 60).install()
        val fires = HopRules.armingTiles(t(50, 60), t(70, 60))
        val toward = RouteProgress.toward(t(70, 60))
        assertNotNull(MovePrediction.diveLanding(t(50, 60), t(62, 60)))
        assertNull(MovePrediction.diveLanding(t(52, 60), t(62, 60)))
        assertNotNull(HopRules.armedShort(fires, t(62, 60), toward, HopRules.DIVE_MIN_GAIN))
        assertNull(HopRules.armedShort(fires, t(60, 60), toward, HopRules.DIVE_MIN_GAIN - 2))
        assertNotNull(HopRules.armedShort(fires, t(60, 60), toward, HopRules.DIVE_MIN_GAIN), "round the block it gains 7 from (52,58)")
        assertNull(HopRules.armedShort(emptyList(), t(62, 60), toward, HopRules.DIVE_MIN_GAIN))
    }

    @Test
    fun `on a straight every dive spot gains ten and the ready dive goes now`() {
        open()
        val route = line(20..150, 100)
        val p = RouteProgress.of(route, 0, t(20, 100))
        assertTrue(p.end && p.rootIndex == route.size - 1, "$p")
        assertEquals(10, p.gain(t(20, 100), t(30, 100)))
        val dives = HopSchedule.scanDive(route, 0, p.rootIndex + 1, p, route.size - 1)
        assertTrue((1 until dives.horizon - 12).all { dives.gain[it] == 10 })
        dives.gain[0] = 10
        dives.land[0] = 10
        assertTrue(HopSchedule.decide(dives, cycle).cast)
        val surges = HopSchedule.scanSurge(route, 0, p.rootIndex + 1, p, route.size - 1)
        assertTrue(surges.gain[20] == 10 && surges.land[20] == 30)
        assertEquals(10, RoutePicks.routeIndexOf(route, 40, t(30, 100)))
    }

    @Test
    fun `a learned hop-passable edge makes the dive across it worth holding for`() {
        val m = LiveMapFixture()
        for (y in 60..140) m.wallEast(60, y)
        m.install()
        val route = ArrayList<Tile>()
        route += line(20..59, 100)
        for (y in 101..141) route += t(59, y)
        route += t(60, 141)
        route += t(61, 141)
        for (y in 140 downTo 100) route += t(61, y)
        route += line(62..90, 100)
        val at = 5
        val p = RouteProgress.of(route, at, route[at])
        val plain = HopSchedule.scanDive(route, at, p.rootIndex - at + 1, p, route.size - 1 - at)
        assertTrue(plain.gain.max() < 20)
        plain.gain[0] = 10
        plain.land[0] = 10
        assertTrue(HopSchedule.decide(plain, cycle).cast)

        val edge = HopEdges.pack(OX + 60, OY + 100, 0, Direction.EAST)
        val strong = Evidence().apply {
            success(true, 1, "950-1")
            success(true, 1, "950-1")
        }
        HopKnowledge.installForTests(null, mapOf(HopEdges.key(HopEdges.PASS, edge) to strong))
        val learned = HopSchedule.scanDive(route, at, p.rootIndex - at + 1, p, route.size - 1 - at)
        val bestAt = (1 until learned.horizon).maxBy { learned.gain[it] }
        assertTrue(learned.gain[bestAt] >= 45 && route[at + bestAt].x >= OX + 50, "${learned.gain[bestAt]} from ${route[at + bestAt]}")
        learned.gain[0] = 10
        learned.land[0] = 10
        val with = HopSchedule.decide(learned, cycle)
        assertTrue(!with.cast && route[at + with.keptAt].x >= OX + 50 && with.keptGain >= 45, "$with")
    }

    @Test
    fun `off the map the root is the last loaded route tile and the rest is the tail`() {
        open()
        val route = line(100..320, 100)
        val p = RouteProgress.of(route, 0, t(100, 100))
        assertTrue(!p.end && p.root?.x == OX + CollisionMap.DIM - 1, "$p")
        val tailEnd = route.size - 1
        val s = HopSchedule.scanDive(route, 0, p.rootIndex + 1, p, tailEnd)
        s.gain[0] = 10
        s.land[0] = 10
        assertTrue(HopSchedule.tail(s.horizon, tailEnd, cycle) > 0)
        assertTrue(HopSchedule.decide(s, cycle).cast)
    }

    @Test
    fun `a walled-in route tile is no spot and landings map to the leg they lie by`() {
        LiveMapFixture().wallEast(59, 100).wallEast(60, 100).wallNorth(60, 100).wallNorth(60, 99).install()
        val route = line(20..150, 100)
        val p = RouteProgress.of(route, 0, t(20, 100))
        assertTrue(!p.counts(t(60, 100)) && p.counts(t(59, 100)))
        val dives = HopSchedule.scanDive(route, 0, p.rootIndex + 1, p, route.size - 1)
        assertTrue(dives.gain[40] == 0 && dives.gain.max() <= 12)
        assertEquals(10, HopSchedule.landIndex(route, 0, t(30, 100), p))

        val u = LiveMapFixture()
        for (y in 80..129) u.wallEast(42, y)
        u.install()
        val turn = ArrayList<Tile>()
        for (y in 100..130) turn += t(40, y)
        turn += line(41..45, 130)
        for (y in 129 downTo 100) turn += t(45, y)
        val q = RouteProgress.of(turn, 0, t(40, 100))
        assertEquals(OX + 40, turn[HopSchedule.landIndex(turn, 0, t(41, 110), q)].x)
        val down = turn[HopSchedule.landIndex(turn, 0, t(44, 110), q)]
        assertTrue(down.x == OX + 45 && abs(down.y - (OY + 110)) <= 1, "$down")
    }

    @Test
    fun `combos on a straight gain both hops in one tick`() {
        open()
        val route = line(20..150, 100)
        val p = RouteProgress.of(route, 0, t(20, 100))
        val c = HopSchedule.scanCombos(route, 0, p.rootIndex + 1, p, route.size - 1)
        assertTrue(c.gain[20] == 20 && c.land[20] == 40, "${c.gain[20]} lands ${c.land[20]}")
        val at = HopSchedule.comboAt(route, 20, p)
        assertTrue(at != null && at.gain == 20 && at.land == 40, "$at")
        assertNull(HopSchedule.comboAt(route, route.size - 3, p))
    }

    @Test
    fun `from the player's tile every dive is tried for a dive then surge`() {
        open()
        val ell = ArrayList<Tile>()
        for (y in 60 downTo 51) ell += t(50, y)
        ell += line(49 downTo 32, 51)
        val pe = RouteProgress.of(ell, 0, t(50, 60))
        assertNull(HopSchedule.comboAt(ell, 0, pe), "in the open no surge from a dive's landing clears its need")
        assertNull(HopSchedule.comboAt(ell, 0, pe, t(50, 60)))

        val west = line(50 downTo 20, 60)
        val p = RouteProgress.of(west, 0, t(50, 60))
        val narrow = HopSchedule.comboAt(west, 0, p)
        val wide = HopSchedule.comboAt(west, 0, p, t(50, 60))
        assertTrue(narrow != null && narrow.diveFirst && narrow.gain == 20, "$narrow")
        assertTrue(wide != null && wide.diveFirst && wide.gain >= narrow.gain && wide.diveTo != null, "$wide")
        assertEquals(wide.diveTo, MovePrediction.diveLanding(t(50, 60), wide.diveTo!!))
    }

    @Test
    fun `a surge past a building corner sets up a dive up its far side`() {
        val m = LiveMapFixture()
        for (x in 25..29) for (y in 33..45) m.block(x, y)
        m.install()
        val route = ArrayList<Tile>()
        for (k in 0..10) route += t(20 + k / 2, 40 - k)
        route += line(25..30, 30)
        for (y in 31..60) route += t(30, y)
        val surgeLanding = MovePrediction.surgeLanding(t(20, 40), Direction.SOUTH_EAST)
        assertEquals(t(30, 30), surgeLanding)
        val p = RouteProgress.of(route, 0, t(20, 40))
        val at = HopSchedule.landIndex(route, 0, surgeLanding, p)
        val d = HopRules.pickDive(route, at, surgeLanding, p, listOf(surgeLanding))
        assertTrue(d.aim != null && d.landing == t(30, 40) && d.gain >= 10, "$d")
        val now = HopRules.pickDive(route, 0, t(20, 40), p, listOf(t(20, 40)))
        assertTrue(now.aim == null || now.landing!!.x < OX + 25 || now.landing!!.y < OY + 33, "$now")
    }

    @Test
    fun `a combo's dive aims at a landing in reach`() {
        open()
        val from = t(50, 50)
        val far = HopRules.DivePick(t(65, 70), MovePrediction.diveLanding(from, t(65, 70)), 10)
        assertEquals(t(60, 60), far.landing)
        assertEquals(t(60, 60), HopRules.comboDiveAim(from, far))
        assertEquals(t(56, 53), HopRules.comboDiveAim(from, HopRules.DivePick(t(56, 53), t(56, 53), 6)))
        val walled = LiveMapFixture()
        for (y in 40..70) walled.block(55, y)
        walled.install()
        assertNull(HopRules.comboDiveAim(from, HopRules.DivePick(t(65, 70), t(60, 60), 10)))
    }

    @Test
    fun `a probe stand the map does not walk to the tile ahead is no probe spot`() {
        open()
        val gain = RouteProgress.probeGain(t(40, 50), t(60, 50))
        assertEquals(10, gain?.invoke(t(50, 50)))
        val cut = LiveMapFixture()
        for (y in 0 until CollisionMap.DIM) cut.wallEast(50, y)
        cut.install()
        assertNull(RouteProgress.probeGain(t(40, 50), t(60, 50)))
    }

    @Test
    fun `a walk past an interaction the map holds is scanned toward its own end`() {
        open()
        val tiles = line(40..80, 60)
        val surge = HopSchedule.legSpots(true, tiles, 10)
        val dive = HopSchedule.legSpots(false, tiles, 10)
        assertTrue(surge.gain[0] == 0 && dive.gain[0] == 10)
        assertEquals(10, dive.gain[5])
        assertEquals(10, surge.gain[5])
    }
}
