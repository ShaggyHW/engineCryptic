package com.projectx.quest.solver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SolverLogicTest {

    @Test
    fun `dice roll the fewest dice, preferring the lowest-numbered ones`() {
        assertEquals(listOf(0), diceToRoll(listOf(1, 1, 1, 1, 1, 1), 11))
        assertEquals(listOf<Int>(), diceToRoll(listOf(6, 1, 1, 1, 1, 1), 11))
        assertEquals(listOf(0, 1), diceToRoll(listOf(1, 1, 1, 1, 1, 1), 16))
        assertNull(diceToRoll(listOf(1, 1, 1, 1, 1, 1), 5))
        assertEquals(listOf(1), diceToRoll(listOf(6, 1, 1, 1, 1, 1), 16))
    }

    @Test
    fun `number patterns continue, finish or stay unclear`() {
        assertEquals(listOf(7, 9), assertIs<PatternContinuation.Clicks>(continuationOf(listOf(1, 3, 5))).digits)
        assertEquals(listOf(1, 1, 4, 5, 1), assertIs<PatternContinuation.Clicks>(continuationOf(listOf(1, 1, 2, 3))).digits)
        assertEquals(PatternContinuation.Unclear, continuationOf(listOf(1, 1, 2)))
        assertEquals(PatternContinuation.Unclear, continuationOf(listOf(5, 5, 5)))
        assertEquals(PatternContinuation.Finished, continuationOf(listOf(8, 6, 4, 2, 0)))
    }

    @Test
    fun `tzhaar numerals are base twelve letters`() {
        assertEquals(3, tzhaarNumeral("c"))
        assertEquals(12, tzhaarNumeral("l"))
        assertEquals(18, tzhaarNumeral("a f"))
        assertEquals(25, tzhaarNumeral("b a"))
        assertNull(tzhaarNumeral("m"))
        assertNull(tzhaarNumeral(""))
        assertNull(tzhaarNumeral("ab"))
    }

    @Test
    fun `the scale equation reads left to right`() {
        val reading = scaleReading(listOf("a f", "c", "b", "l"), listOf("Xo", " zi ", "XO"))!!
        assertEquals("18 + 3 - 2 + 12", reading.written)
        assertEquals(31, reading.total)
        assertNull(scaleReading(listOf("a f", "c", "b", "l"), listOf("Xo", "Qq", "Xo")))
    }

    @Test
    fun `a burst extends, replaces or leaves the remembered sequence`() {
        assertEquals(listOf(2), foldBurst(listOf(), listOf(2)))
        assertEquals(listOf(2, 4), foldBurst(listOf(2), listOf(2, 4)))
        assertEquals(listOf(2, 4, 1), foldBurst(listOf(2, 4, 1), listOf(2, 4)))
        assertEquals(listOf(3, 1), foldBurst(listOf(2, 4, 1), listOf(3, 1)))
        assertEquals(listOf(2, 4, 1), foldBurst(listOf(2, 4, 1), listOf(3)))
        assertEquals(listOf(2, 4), foldBurst(listOf(2, 4), listOf(2, 4)))
    }

    private val redCircle = 9597
    private val yellowSquare = 9607

    @Test
    fun `missing discs come from exchanging spares, lowest value first`() {
        val advice = discAdvice(listOf(DiscSlot("Slot 2", 12, 0, 2)), mapOf(redCircle to 1, yellowSquare to 1))
        assertEquals(
            listOf(
                "Inventory (2 discs): RC=1×1, YS=12×1",
                "Slot 2 (2 discs): target 12 -> need 2 discs summing to 12.  Exchange (Oaknock's exchanger): " +
                    "Red circle (1) -> Orange circle (2), Yellow square (12) -> Orange pentagon (10).",
            ),
            advice,
        )
    }

    @Test
    fun `an owned disc is placed directly and a finished slot says so`() {
        val advice = discAdvice(
            listOf(DiscSlot("Slot 1", 12, 0, 1), DiscSlot("Slot 2", 5, 5, 2), DiscSlot("Slot 3", 99, 4, 3)),
            mapOf(yellowSquare to 1),
        )
        assertEquals("Slot 1 (1 disc): target 12 -> Place 1 disc: Yellow square (12)  (sum=12)", advice[1])
        assertEquals("Slot 2 [x] done (sum=5 = 5)", advice[2])
        assertEquals(
            "Slot 3 (3 discs): target 99  [reads 4 - too low, replace discs] - no 3-disc combination sums to 99. Re-check the displayed number.",
            advice[3],
        )
    }

    @Test
    fun `discs nobody carries are fetched from Brimstail`() {
        val advice = discAdvice(listOf(DiscSlot("Phase 1 (tutorial)", 35, 0, 1)), mapOf())
        assertEquals("Inventory: (no discs)", advice[0])
        assertEquals(
            "Phase 1 (tutorial) (1 disc): target 35 -> need 1 disc summing to 35.  Get more discs from Brimstail, then exchange to: Violet pentagon (35).",
            advice[1],
        )
    }
}
