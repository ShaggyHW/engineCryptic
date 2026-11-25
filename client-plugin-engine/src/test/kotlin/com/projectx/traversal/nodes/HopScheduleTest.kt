package com.projectx.traversal.nodes

import com.projectx.traversal.hops.HopLearning
import com.projectx.traversal.hops.ScheduleStore
import com.projectx.traversal.local.LiveMapFixture
import com.projectx.traversal.local.LiveMapFixture.Companion.t
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hop schedule's dynamic program on hand-made spots: holds, the run-tick smoothing, pairs, combos, legs past interactions. */
class HopScheduleTest {

    @BeforeTest
    fun setUp() {
        HopLearning.installForTests(null, null)
        ScheduleStore.installForTests(emptyMap(), emptyMap())
    }

    @AfterTest
    fun tearDown() {
        ScheduleStore.installForTests(null, null)
        LiveMapFixture.uninstall()
        LocalNav.resetForTests()
    }

    private val cycle = HopSchedule.cycleSteps(10_200L)

    private fun spots(horizon: Int, tailEnd: Int, now: Int, base: Int, hop: Int, vararg spotAndGain: Int): HopSchedule.Spots {
        val gain = IntArray(horizon)
        val land = IntArray(horizon)
        for (k in 0 until horizon) {
            gain[k] = if (k == 0) now else base
            land[k] = k + if (gain[k] > 0) hop else 0
        }
        for (i in 0 until spotAndGain.size - 1 step 2) {
            gain[spotAndGain[i]] = spotAndGain[i + 1]
            land[spotAndGain[i]] = spotAndGain[i] + hop
        }
        return HopSchedule.Spots(gain, land, tailEnd)
    }

    private fun combos(horizon: Int, hop: Int, diveFirst: Boolean, vararg spotAndGain: Int): HopSchedule.Combos {
        val gain = IntArray(horizon)
        val land = IntArray(horizon) { it }
        val order = BooleanArray(horizon)
        for (i in 0 until spotAndGain.size - 1 step 2) {
            gain[spotAndGain[i]] = spotAndGain[i + 1]
            land[spotAndGain[i]] = spotAndGain[i] + hop
            order[spotAndGain[i]] = diveFirst
        }
        return HopSchedule.Combos(gain, land, order)
    }

    private fun runSpots(raw: IntArray, land: IntArray): HopSchedule.Spots {
        val s = HopSchedule.runTicks(raw.clone(), land, raw.size - 1)
        s.gain[0] = raw[0]
        s.land[0] = land[0]
        return s
    }

    @Test
    fun `a cooldown becomes running steps and plain straights cast at once`() {
        assertTrue(cycle in 34..38, "a 10.2 s cooldown is ~36 running steps ($cycle)")
        assertEquals(0, HopSchedule.cycleSteps(0))
        assertTrue(HopSchedule.decide(spots(120, 119, 10, 10, 10), cycle).cast)
    }

    @Test
    fun `a bigger spot inside a cycle is kept for, one past it or under the margin is not`() {
        val corner = HopSchedule.decide(spots(120, 119, 10, 10, 10, 15, 25), cycle)
        assertTrue(!corner.cast && corner.keptAt == 15 && corner.keptGain == 25, "$corner")
        assertTrue(HopSchedule.decide(spots(120, 119, 10, 10, 10, 60, 25), cycle).cast)
        val two = HopSchedule.decide(spots(120, 119, 8, 0, 10, 10, 20, 20, 30), cycle)
        assertTrue(!two.cast && two.keptAt == 20, "$two")
        assertTrue(HopSchedule.decide(spots(40, 39, 10, 0, 10, 5, 13), cycle).cast)
    }

    @Test
    fun `nothing is kept for the off-map tail alone`() {
        assertTrue(HopSchedule.decide(spots(20, 300, 6, 0, 10), cycle).cast)
        assertEquals(0.0, HopSchedule.tail(40, 39, cycle))
        assertTrue(HopSchedule.tail(40, 200, cycle) > 0)
    }

    @Test
    fun `spots are valued as a run gets them`() {
        val land = intArrayOf(0, 11, 12, 13, 14, 15)
        val peak = HopSchedule.runTicks(intArrayOf(0, 10, 10, 18, 10, 10), land, 5)
        assertEquals(10, peak.gain[3])
        assertEquals(14, peak.land[3])
        assertEquals(18, peak.raw[3])
        val plateau = HopSchedule.runTicks(intArrayOf(0, 10, 18, 18, 10, 10), land, 5)
        assertTrue(plateau.gain[2] == 18 && plateau.gain[3] == 18 && plateau.land[2] == 12 && plateau.land[3] == 13)
        val ramp = HopSchedule.runTicks(intArrayOf(0, 10, 12, 14, 16, 18), land, 5)
        assertTrue(ramp.gain[2] == 12 && ramp.gain[3] == 14 && ramp.gain[4] == 16 && ramp.gain[5] == 16)
        assertEquals(10, HopSchedule.runTicks(intArrayOf(0, 18, 10), intArrayOf(0, 11, 12), 2).gain[1])
        val lone = HopSchedule.runTicks(intArrayOf(0, 0, 0, 15, 0, 0), land, 5)
        assertTrue(lone.gain[3] == 0 && lone.land[3] == 3)

        val g = IntArray(60) { 10 }
        val l = IntArray(60) { it + 10 }
        g[15] = 25
        assertTrue(HopSchedule.decide(runSpots(g, l), cycle).cast, "a one-tile peak is not kept for")
        g[16] = 25
        val two = HopSchedule.decide(runSpots(g, l), cycle)
        assertTrue(!two.cast && two.keptGain == 25, "a two-tile plateau is kept for ($two)")
    }

    @Test
    fun `the backward pass matches brute force over every hop set`() {
        val rnd = Random(7)
        var bad = 0
        repeat(300) {
            val n = 6 + rnd.nextInt(9)
            val c = 1 + rnd.nextInt(5)
            val gain = IntArray(n)
            val land = IntArray(n)
            for (k in 0 until n) {
                gain[k] = if (rnd.nextInt(3) == 0) 0 else 1 + rnd.nextInt(30)
                land[k] = k + rnd.nextInt(4)
            }
            val v = HopSchedule.values(HopSchedule.Spots(gain, land, n - 1), c)[0]
            if (abs(v - best(gain, land, c, 0)) > 1e-9) bad++
        }
        assertEquals(0, bad)
    }

    private fun best(gain: IntArray, land: IntArray, cycle: Int, k: Int): Double {
        if (k >= gain.size) return 0.0
        val skip = best(gain, land, cycle, k + 1)
        if (gain[k] <= 0) return skip
        return max(skip, gain[k] + best(gain, land, cycle, max(k + 1, land[k]) + cycle))
    }

    @Test
    fun `a dive waits for the surge back in a tick when surge then dive gains more`() {
        val n = 18
        val dg = IntArray(n)
        val dl = IntArray(n) { it }
        val sg = IntArray(n)
        val sl = IntArray(n) { it }
        for (k in 1..6) {
            sg[k] = 10
            sl[k] = k + 10
        }
        for (k in 1..7) {
            dg[k] = 10
            dl[k] = k + 10
        }
        for (k in 12..14) {
            dg[k] = 17 - k
            dl[k] = 17
        }
        val dives = HopSchedule.Spots(dg, dl, n - 1)
        val surges = HopSchedule.Spots(sg, sl, n - 1)
        val p = HopSchedule.pair(dives, surges, 10, 10, 3)
        assertTrue(p.hold && p.otherThenNow >= 14, "$p")
        val even = HopSchedule.Spots(IntArray(60) { if (it == 0) 0 else 10 }, IntArray(60) { if (it == 0) 0 else it + 10 }, 59)
        assertFalse(HopSchedule.pair(even, even, 10, 10, 3).hold)
        assertFalse(HopSchedule.pair(dives, HopSchedule.Spots(IntArray(n), sl, n - 1), 10, 10, 3).hold)
    }

    @Test
    fun `both ready, a same-tick combo is one move`() {
        val p = HopSchedule.PRIORS
        val straight = spots(120, 119, 10, 10, 10)
        assertEquals(HopSchedule.Joint.Kind.SPLIT, HopSchedule.joint(straight, straight, combos(120, 20, true), cycle, cycle, p, p).kind)
        val corner = HopSchedule.joint(straight, straight, combos(120, 40, true, 15, 45), cycle, cycle, p, p)
        assertTrue(corner.kind == HopSchedule.Joint.Kind.COMBO_AHEAD && corner.at == 15 && corner.diveFirst, "$corner")
        val now = HopSchedule.joint(straight, straight, combos(120, 35, false, 0, 35), cycle, cycle, p, p)
        assertTrue(now.kind == HopSchedule.Joint.Kind.COMBO_NOW && !now.diveFirst, "$now")
        val tie = HopSchedule.joint(straight, straight, combos(120, 20, true, 0, 20), cycle, cycle, p, p)
        assertTrue(tie.kind == HopSchedule.Joint.Kind.COMBO_NOW && tie.diveFirst, "$tie")
        val far = HopSchedule.joint(straight, straight, combos(120, 25, true, 70, 25), cycle, cycle, p, p)
        assertTrue(far.kind != HopSchedule.Joint.Kind.COMBO_AHEAD, "$far")
    }

    @Test
    fun `a probe spot counts for what it gains if it crosses`() {
        val p = HopSchedule.PRIORS
        val straight = spots(120, 119, 10, 10, 10)
        val probe = HopSchedule.joint(straight, spots(120, 119, 10, 10, 10, 10, 4), combos(120, 40, true, 15, 45), cycle, cycle, p, p)
        assertTrue(probe.kind == HopSchedule.Joint.Kind.COMBO_AHEAD && probe.at == 15, "$probe")
        val flat = HopSchedule.joint(straight, spots(120, 119, 10, 10, 10, 10, 40), combos(120, 40, true, 15, 45), cycle, cycle, p, p)
        assertEquals(HopSchedule.Joint.Kind.SPLIT, flat.kind)
        val diveKept = HopSchedule.decide(spots(120, 119, 10, 10, 10, 10, 30), cycle)
        assertTrue(!diveKept.cast && diveKept.keptAt == 10, "$diveKept")
    }

    @Test
    fun `a dive that jumps a surge spot does not count both`() {
        val p = HopSchedule.PRIORS
        val ladder = HopSchedule.joint(
            spots(30, 25, 0, 0, 10, 11, 9), spots(30, 25, 0, 0, 11, 3, 10),
            combos(30, 20, true, 3, 18), cycle, cycle, p, p,
        )
        assertTrue(ladder.kind == HopSchedule.Joint.Kind.COMBO_AHEAD && ladder.at == 3 && ladder.diveFirst, "$ladder")
    }

    @Test
    fun `the walks after interactions are stitched on and held for`() {
        val walk = HopSchedule.uniform(25)
        assertTrue(walk.gain[0] == 10 && walk.land[0] == 10)
        assertTrue(walk.gain[16] == 8 && walk.gain[20] == 0)

        val g = IntArray(11)
        val l = IntArray(11) { it }
        g[0] = 5
        l[0] = 5
        val head = HopSchedule.Spots(g, l, 10)
        val tiles = (0 until 25).map { t(100 + it, 100) }
        val all = HopSchedule.concat(head, listOf(HopSchedule.Seg("LodestoneNode", 36, walk, tiles)))
        assertTrue(all.horizon == 72 && all.tailEnd == 71)
        assertTrue(all.gain[11] == 0 && all.gain[46] == 0)
        assertTrue(all.isBeyond(47) && all.gain[47] == 10 && all.land[47] == 57 && t(100, 100) == all.beyond!![47])
        assertEquals("LodestoneNode", all.beyondWhat!![47])

        val slow = HopSchedule.decide(all, 70)
        assertTrue(!slow.cast && all.isBeyond(slow.keptAt) && slow.keptGain == 10, "$slow")
        assertTrue(HopSchedule.decide(all, 36).cast)
        assertTrue(HopSchedule.decide(HopSchedule.concat(head, emptyList()), 70).cast)
        assertEquals(t(100, 100), HopSchedule.spotTile(listOf(t(0, 0), t(1, 0)), 0, all, 47))
        assertEquals(t(1, 0), HopSchedule.spotTile(listOf(t(0, 0), t(1, 0)), 0, all, 1))
        assertEquals(t(1, 0), HopSchedule.spotTile(listOf(t(0, 0), t(1, 0)), 0, all, 20))
    }

    @Test
    fun `before a climb a small dive waits for the walk after it unless the learned margin says otherwise`() {
        val g = IntArray(6)
        val l = IntArray(6) { it }
        g[0] = 6
        l[0] = 4
        val climb = (0 until 20).map { t(110 + it, 100) }
        val legs = listOf(Interactions.Leg("ObjectNode:Climb-over", 3_900L, climb))
        val segs = HopSchedule.segs(legs) { HopSchedule.uniform(it.size) }
        assertEquals(13, segs.single().dead)
        val yanille = HopSchedule.concat(HopSchedule.Spots(g, l, 5), segs)
        val y = HopSchedule.decide(yanille, 36)
        assertTrue(!y.cast && yanille.isBeyond(y.keptAt), "$y")
        assertTrue(HopSchedule.decide(yanille, 36, HopSchedule.Params(10, 4, 6)).cast)
        assertEquals(9, HopSchedule.uniform(20, 9).gain[0])
        assertEquals(0, HopSchedule.uniform(20, 7).gain[0], "an ordinary hop under the dive need is no spot")
    }

    @Test
    fun `a walk off the live map is ordinary hops, its first tile a dive and no surge`() {
        LiveMapFixture().install()
        val tiles = (0 until 20).map { Tile.of(5000 + it, 5000, 0) }
        val surge = HopSchedule.legSpots(true, tiles, 10)
        val dive = HopSchedule.legSpots(false, tiles, 10)
        assertTrue(surge.gain[0] == 0 && surge.raw[0] == 0 && surge.gain[1] == 10)
        assertTrue(dive.gain[0] == 10 && dive.land[0] == 10)
        assertEquals(0, HopSchedule.legSpots(true, emptyList(), 10).horizon)
    }
}
