package com.projectx.puzzle.spec

import com.projectx.puzzle.invention.Advice
import com.projectx.puzzle.invention.InventionDiscoveryFeature
import com.projectx.puzzle.invention.InventionDiscoveryModel
import com.projectx.puzzle.invention.InventionDiscoveryOverlayState
import com.projectx.puzzle.invention.Optimisation
import com.projectx.puzzle.invention.OptimisationText
import com.projectx.puzzle.invention.SlotHint
import com.projectx.puzzle.invention.TRACK_SLOTS
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventionDiscoverySpecTest {

    private val all = InventionDiscoveryModel.ALL_ARRANGEMENTS

    @Test
    fun `the track has five slots and one hundred and twenty arrangements`() {
        assertEquals(5, TRACK_SLOTS)
        assertEquals(120, all.size)
        assertEquals(120, all.map { it.toList() }.toSet().size)
        assertTrue(all.all { InventionDiscoveryModel.isArrangement(it) })
    }

    @Test
    fun `an arrangement holds each of the five modules once`() {
        assertTrue(InventionDiscoveryModel.isArrangement(intArrayOf(5, 3, 1, 2, 4)))
        assertFalse(InventionDiscoveryModel.isArrangement(intArrayOf(1, 2, 3, 4)))
        assertFalse(InventionDiscoveryModel.isArrangement(intArrayOf(1, 2, 3, 4, 5, 6)))
        assertFalse(InventionDiscoveryModel.isArrangement(intArrayOf(1, 2, 3, 4, 4)))
        assertFalse(InventionDiscoveryModel.isArrangement(intArrayOf(0, 1, 2, 3, 4)))
        assertFalse(InventionDiscoveryModel.isArrangement(intArrayOf(1, 2, 3, 4, 6)))
        assertFalse(InventionDiscoveryModel.isArrangement(intArrayOf(0, 0, 0, 0, 0)))
    }

    @Test
    fun `a fresh model has seen nothing`() {
        val model = InventionDiscoveryModel()
        assertEquals(0, model.observationCount)
        assertEquals(Int.MAX_VALUE, model.bestPenalty)
        assertNull(model.bestArrangement)
        assertFalse(model.solved)
        assertNull(model.penaltyOf(intArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `scores are remembered per arrangement and the best is kept`() {
        val model = InventionDiscoveryModel()
        model.record(intArrayOf(4, 2, 5, 1, 3), 10)
        model.record(intArrayOf(2, 4, 5, 1, 3), 6)
        model.record(intArrayOf(2, 4, 5, 3, 1), 8)
        assertEquals(3, model.observationCount)
        assertEquals(10, model.penaltyOf(intArrayOf(4, 2, 5, 1, 3)))
        assertEquals(6, model.penaltyOf(intArrayOf(2, 4, 5, 1, 3)))
        assertEquals(6, model.bestPenalty)
        assertContentEquals(intArrayOf(2, 4, 5, 1, 3), model.bestArrangement)
        assertFalse(model.solved)
    }

    @Test
    fun `scoring the same arrangement again replaces its score but not the best seen`() {
        val model = InventionDiscoveryModel()
        model.record(intArrayOf(1, 3, 5, 2, 4), 4)
        model.record(intArrayOf(1, 3, 5, 2, 4), 8)
        assertEquals(1, model.observationCount)
        assertEquals(8, model.penaltyOf(intArrayOf(1, 3, 5, 2, 4)))
        assertEquals(4, model.bestPenalty)
    }

    @Test
    fun `invalid arrangements and negative scores are ignored`() {
        val model = InventionDiscoveryModel()
        model.record(intArrayOf(1, 1, 2, 3, 4), 2)
        model.record(intArrayOf(1, 2, 3, 4), 2)
        model.record(intArrayOf(1, 2, 3, 4, 5), -2)
        assertEquals(0, model.observationCount)
        assertEquals(Int.MAX_VALUE, model.bestPenalty)
    }

    @Test
    fun `the model keeps its own copy of what it was given`() {
        val model = InventionDiscoveryModel()
        val arrangement = intArrayOf(3, 1, 2, 5, 4)
        model.record(arrangement, 2)
        arrangement[0] = 4
        arrangement[3] = 3
        assertContentEquals(intArrayOf(3, 1, 2, 5, 4), model.bestArrangement)
        assertEquals(2, model.penaltyOf(intArrayOf(3, 1, 2, 5, 4)))
    }

    @Test
    fun `a perfect score solves it and reset forgets everything`() {
        val model = InventionDiscoveryModel()
        model.record(intArrayOf(5, 4, 3, 2, 1), 0)
        assertTrue(model.solved)
        assertEquals(0, model.bestPenalty)
        model.reset()
        assertEquals(0, model.observationCount)
        assertEquals(Int.MAX_VALUE, model.bestPenalty)
        assertNull(model.bestArrangement)
        assertFalse(model.solved)
        assertNull(model.penaltyOf(intArrayOf(5, 4, 3, 2, 1)))
    }

    @Test
    fun `advice on an incomplete track is neutral`() {
        val model = InventionDiscoveryModel()
        val current = intArrayOf(1, 2, 0, 4, 5)
        val advice = model.advise(current)
        assertFalse(advice.solved)
        assertEquals(-1, advice.swapFirst)
        assertEquals(-1, advice.swapSecond)
        assertEquals(List(5) { SlotHint.NEUTRAL }, advice.slotHints.toList())
        assertContentEquals(current, advice.believedBest)
        assertNotSame(current, advice.believedBest)
    }

    @Test
    fun `advice on a perfect arrangement says it is solved`() {
        val model = InventionDiscoveryModel()
        model.record(intArrayOf(2, 5, 1, 3, 4), 6)
        model.record(intArrayOf(2, 5, 4, 3, 1), 0)
        val advice = model.advise(intArrayOf(2, 5, 4, 3, 1))
        assertTrue(advice.solved)
        assertEquals(-1, advice.swapFirst)
        assertEquals(-1, advice.swapSecond)
        assertEquals(List(5) { SlotHint.NEUTRAL }, advice.slotHints.toList())
        assertContentEquals(intArrayOf(2, 5, 4, 3, 1), advice.believedBest)
    }

    @Test
    fun `with nothing learned yet the current arrangement is believed best and nothing is swapped`() {
        val advice = InventionDiscoveryModel().advise(intArrayOf(4, 1, 3, 5, 2))
        assertFalse(advice.solved)
        assertEquals(-1, advice.swapFirst)
        assertEquals(-1, advice.swapSecond)
        assertContentEquals(intArrayOf(4, 1, 3, 5, 2), advice.believedBest)
        assertEquals(List(5) { SlotHint.LIKELY }, advice.slotHints.toList())
    }

    @Test
    fun `after one bad score the next probe moves every module`() {
        val model = InventionDiscoveryModel()
        val current = intArrayOf(1, 2, 3, 4, 5)
        model.record(current, 8)
        val advice = model.advise(current)
        assertFalse(advice.solved)
        assertTrue(InventionDiscoveryModel.isArrangement(advice.believedBest))
        assertTrue((0 until 5).none { advice.believedBest[it] == current[it] })
        assertSwapIsValid(current, advice)
        assertEquals(current[advice.swapSecond], advice.believedBest[advice.swapFirst])
    }

    @Test
    fun `swap hints mark the advised pair and nothing else is locked early`() {
        val model = InventionDiscoveryModel()
        val current = intArrayOf(3, 5, 1, 4, 2)
        model.record(current, 10)
        val advice = model.advise(current)
        assertEquals(SlotHint.SWAP_FIRST, advice.slotHints[advice.swapFirst])
        assertEquals(SlotHint.SWAP_SECOND, advice.slotHints[advice.swapSecond])
        assertTrue(advice.slotHints.none { it == SlotHint.LOCKED })
    }

    @Test
    fun `slots are only locked once five arrangements have been scored`() {
        val rng = Random(1708)
        repeat(20) {
            val blueprint = Blueprint(rng)
            val model = InventionDiscoveryModel()
            var current = all[rng.nextInt(all.size)].copyOf()
            repeat(12) {
                model.record(current, blueprint.score(current))
                val advice = model.advise(current)
                if (model.observationCount < 5) assertTrue(advice.slotHints.none { it == SlotHint.LOCKED })
                for (slot in 0 until 5) {
                    val hint = advice.slotHints[slot]
                    if (hint == SlotHint.LIKELY || hint == SlotHint.LOCKED) assertEquals(advice.believedBest[slot], current[slot])
                }
                if (advice.solved || advice.swapFirst < 0) return@repeat
                current = swapped(current, advice.swapFirst, advice.swapSecond)
            }
        }
    }

    @Test
    fun `the swap toward a target is the first mismatched slot and where its module sits`() {
        val model = InventionDiscoveryModel()
        assertEquals(1 to 3, model.swapToward(intArrayOf(1, 2, 3, 4, 5), intArrayOf(1, 4, 3, 2, 5)))
        assertEquals(0 to 4, model.swapToward(intArrayOf(1, 2, 3, 4, 5), intArrayOf(5, 1, 2, 3, 4)))
        assertEquals(-1 to -1, model.swapToward(intArrayOf(2, 1, 3, 5, 4), intArrayOf(2, 1, 3, 5, 4)))
        assertEquals(-1 to -1, model.swapToward(intArrayOf(1, 2, 3, 4, 4), intArrayOf(1, 2, 3, 4, 5)))
        assertEquals(-1 to -1, model.swapToward(intArrayOf(1, 2, 3, 4, 5), intArrayOf(1, 2, 3)))
    }

    @Test
    fun `repeated swaps toward a target reach it`() {
        val model = InventionDiscoveryModel()
        for (start in all.filterIndexed { i, _ -> i % 7 == 0 }) for (target in all.filterIndexed { i, _ -> i % 11 == 0 }) {
            var current = start.copyOf()
            var swaps = 0
            while (true) {
                val (first, second) = model.swapToward(current, target)
                if (first < 0) break
                assertEquals(target[first], current[second])
                current = swapped(current, first, second)
                swaps++
                assertTrue(swaps <= 4)
            }
            assertContentEquals(target, current)
        }
    }

    @Test
    fun `following the advice on an additive blueprint reaches a perfect score`() {
        val rng = Random(30249)
        repeat(40) { seed ->
            val blueprint = Blueprint(rng)
            val swaps = playUntilPerfect(blueprint, all[rng.nextInt(all.size)].copyOf())
            assertTrue(swaps <= SWAP_LIMIT, "blueprint $seed took $swaps swaps")
        }
    }

    @Test
    fun `following the advice when every misplaced module costs the same reaches a perfect score`() {
        val rng = Random(5992)
        repeat(20) {
            val secret = all[rng.nextInt(all.size)]
            val swaps = playUntilPerfect({ arrangement -> 2 * (0 until 5).count { arrangement[it] != secret[it] } }, all[rng.nextInt(all.size)].copyOf())
            assertTrue(swaps <= SWAP_LIMIT, "secret ${secret.toList()} took $swaps swaps")
        }
    }

    @Test
    fun `server results are read from the score text`() {
        val excellent = assertNotNull(
            OptimisationText.parse("<col=ffffff>Optimisation: Excellent<br>You will gain : <col=ffffff>412</col> + <col=ffffff>1030</col> extra XP."),
        )
        assertEquals("Excellent", excellent.level)
        assertEquals(412, excellent.baseXp)
        assertEquals(1030, excellent.bonusXp)
        assertFalse(excellent.perfect)

        val perfect = assertNotNull(OptimisationText.parse("Optimisation: Perfect<br>You will gain : 412 + 1648 extra XP."))
        assertEquals("Perfect", perfect.level)
        assertTrue(perfect.perfect)
        assertEquals(1648, perfect.bonusXp)
    }

    @Test
    fun `a level without numbers or with one number still parses`() {
        val bare = assertNotNull(OptimisationText.parse("Optimisation: Good"))
        assertEquals("Good", bare.level)
        assertEquals(0, bare.baseXp)
        assertEquals(0, bare.bonusXp)
        val single = assertNotNull(OptimisationText.parse("Optimisation:   Very good  <br>You will gain 250 XP."))
        assertEquals("Very good", single.level)
        assertEquals(250, single.baseXp)
        assertEquals(250, single.bonusXp)
    }

    @Test
    fun `pending, blank and unrelated text are not results`() {
        assertTrue(OptimisationText.isPending("Analysing..."))
        assertTrue(OptimisationText.isPending("<col=ff981f>ANALYSING</col>"))
        assertFalse(OptimisationText.isPending("Optimisation: Perfect"))
        assertNull(OptimisationText.parse("Analysing..."))
        assertNull(OptimisationText.parse(""))
        assertNull(OptimisationText.parse("   "))
        assertNull(OptimisationText.parse("Place modules on the track."))
    }

    @Test
    fun `perfect is recognised without regard to case`() {
        assertTrue(Optimisation("PERFECT", 1, 2).perfect)
        assertTrue(Optimisation("perfect", 1, 2).perfect)
        assertFalse(Optimisation("Perfectly fine", 1, 2).perfect)
        val result = Optimisation("Great", 100, 300)
        assertEquals("Great", result.level)
        assertEquals(100, result.baseXp)
        assertEquals(300, result.bonusXp)
    }

    @Test
    fun `the overlay snapshot names the discovery interface`() {
        assertEquals(1708, InventionDiscoveryOverlayState.INTERFACE_ID)
        assertEquals(14, InventionDiscoveryOverlayState.FIRST_ICON_COMPONENT)
        val hints = arrayOf(SlotHint.LOCKED, SlotHint.LIKELY, SlotHint.SWAP_FIRST, SlotHint.NEUTRAL, SlotHint.SWAP_SECOND)
        val state = InventionDiscoveryOverlayState(hints, listOf("Probe: swap slot 3 <-> slot 5"), solved = false)
        val (slotHints, lines, solved) = state
        assertTrue(slotHints === hints)
        assertEquals(listOf("Probe: swap slot 3 <-> slot 5"), lines)
        assertFalse(solved)
        assertTrue(state.copy(solved = true).solved)
        assertEquals(
            listOf(SlotHint.NEUTRAL, SlotHint.LIKELY, SlotHint.LOCKED, SlotHint.SWAP_FIRST, SlotHint.SWAP_SECOND),
            SlotHint.entries,
        )
    }

    @Test
    fun `advice carries what it was built with`() {
        val hints = Array(5) { SlotHint.NEUTRAL }
        val advice = Advice(false, 1, 4, hints, intArrayOf(1, 2, 3, 4, 5))
        assertFalse(advice.solved)
        assertEquals(1, advice.swapFirst)
        assertEquals(4, advice.swapSecond)
        assertTrue(advice.slotHints === hints)
        assertContentEquals(intArrayOf(1, 2, 3, 4, 5), advice.believedBest)
    }

    @Test
    fun `the feature publishes nothing while switched off`() {
        InventionDiscoveryFeature.tick(false)
        assertNull(InventionDiscoveryFeature.overlay)
    }

    private fun assertSwapIsValid(current: IntArray, advice: Advice) {
        assertTrue(advice.swapFirst in 0 until 5)
        assertTrue(advice.swapSecond in 0 until 5)
        assertTrue(advice.swapFirst != advice.swapSecond)
    }

    private fun swapped(arrangement: IntArray, first: Int, second: Int) = arrangement.copyOf().also {
        it[first] = arrangement[second]
        it[second] = arrangement[first]
    }

    private fun playUntilPerfect(score: (IntArray) -> Int, start: IntArray): Int {
        val model = InventionDiscoveryModel()
        var current = start
        var swaps = 0
        while (swaps <= SWAP_LIMIT) {
            model.record(current, score(current))
            val advice = model.advise(current)
            if (advice.solved) {
                assertEquals(0, score(current))
                return swaps
            }
            assertSwapIsValid(current, advice)
            current = swapped(current, advice.swapFirst, advice.swapSecond)
            swaps++
        }
        return swaps
    }

    private fun playUntilPerfect(blueprint: Blueprint, start: IntArray) = playUntilPerfect(blueprint::score, start)

    private class Blueprint(rng: Random) {
        private val secret = InventionDiscoveryModel.ALL_ARRANGEMENTS[rng.nextInt(120)]
        private val cost = Array(5) { slot -> IntArray(6) { module -> if (module == secret[slot]) 0 else 1 + rng.nextInt(3) } }

        fun score(arrangement: IntArray) = (0 until 5).sumOf { cost[it][arrangement[it]] }
    }

    private companion object {
        const val SWAP_LIMIT = 40
    }
}
