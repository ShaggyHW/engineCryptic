package com.projectx.puzzle.spec

import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.puzzle.knot.CelticKnotLayouts
import com.projectx.puzzle.knot.CelticKnotSolver
import com.projectx.puzzle.knot.EMPTY_RUNE
import com.projectx.puzzle.knot.KnotCrossing
import com.projectx.puzzle.knot.KnotLayout
import com.projectx.puzzle.knot.KnotRotation
import com.projectx.puzzle.knot.KnotSlotRef
import com.projectx.puzzle.knot.KnotTrack
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CelticKnotSpecTest {

    private fun track(index: Int, size: Int, forwardIsClockwise: Boolean = true) = KnotTrack(
        index,
        IntArray(size) { 1000 + index * 100 + it },
        IntArray(size) { 2000 + index * 100 + it },
        clockwiseButton = 10 + index * 2,
        counterClockwiseButton = 11 + index * 2,
        forwardIsClockwise = forwardIsClockwise,
    )

    private fun cross(trackA: Int, slotA: Int, trackB: Int, slotB: Int) =
        KnotCrossing(KnotSlotRef(trackA, slotA), KnotSlotRef(trackB, slotB), 0)

    private fun forwardOffsets(layout: KnotLayout, moves: List<KnotRotation>): IntArray {
        val offsets = IntArray(layout.tracks.size)
        for (move in moves) {
            val forward = move.clockwise == move.track.forwardIsClockwise
            offsets[move.track.index] = Math.floorMod(if (forward) move.steps else -move.steps, move.track.size)
        }
        return offsets
    }

    private fun runeAfter(runes: List<IntArray>, ref: KnotSlotRef, offset: Int): Int {
        val ring = runes[ref.track]
        return ring[Math.floorMod(ref.slot - offset, ring.size)]
    }

    private fun matches(layout: KnotLayout, runes: List<IntArray>, offsets: IntArray) = layout.crossings.all {
        val a = runeAfter(runes, it.a, offsets[it.a.track])
        val b = runeAfter(runes, it.b, offsets[it.b.track])
        a == EMPTY_RUNE || b == EMPTY_RUNE || a == b
    }

    private fun cheapest(layout: KnotLayout, runes: List<IntArray>): Int? {
        val sizes = layout.tracks.map { it.size }
        val offsets = IntArray(sizes.size)
        var best: Int? = null
        fun visit(track: Int) {
            if (track == sizes.size) {
                if (matches(layout, runes, offsets)) {
                    val cost = sizes.indices.sumOf { minOf(offsets[it], sizes[it] - offsets[it]) }
                    if (best == null || cost < best!!) best = cost
                }
                return
            }
            for (offset in 0 until sizes[track]) {
                offsets[track] = offset
                visit(track + 1)
            }
            offsets[track] = 0
        }
        visit(0)
        return best
    }

    private fun assertSolves(layout: KnotLayout, runes: List<IntArray>, moves: List<KnotRotation>) {
        assertTrue(matches(layout, runes, forwardOffsets(layout, moves)), "rotations leave a crossing mismatched")
        assertEquals(cheapest(layout, runes), moves.sumOf { it.steps }, "not the fewest presses")
        assertEquals(moves.map { it.track.index }.distinct(), moves.map { it.track.index })
        assertEquals(moves.map { it.track.index }.sorted(), moves.map { it.track.index })
        for (move in moves) {
            assertTrue(move.steps in 1..move.track.size / 2)
            assertTrue(move.track === layout.tracks[move.track.index])
        }
    }

    @Test
    fun `a rotation presses the button for its direction`() {
        val ring = track(2, 6)
        assertEquals(6, ring.size)
        assertEquals(14, KnotRotation(ring, 2, clockwise = true).buttonComponent)
        assertEquals(15, KnotRotation(ring, 1, clockwise = false).buttonComponent)
        val rotation = KnotRotation(ring, 3, true)
        assertEquals(3, rotation.steps)
        assertTrue(rotation.clockwise)
        assertTrue(rotation.track === ring)
    }

    @Test
    fun `layout parts keep what they were built with`() {
        val ref = KnotSlotRef(1, 7)
        assertEquals(1, ref.track)
        assertEquals(7, ref.slot)
        val crossing = KnotCrossing(KnotSlotRef(0, 2), ref, 55)
        assertEquals(0, crossing.a.track)
        assertEquals(2, crossing.a.slot)
        assertTrue(crossing.b === ref)
        assertEquals(55, crossing.tileComponent)
        val tracks = listOf(track(0, 4), track(1, 4, forwardIsClockwise = false))
        val layout = KnotLayout(394, 3, tracks, listOf(crossing))
        assertEquals(394, layout.interfaceId)
        assertEquals(3, layout.windowComponent)
        assertEquals(tracks, layout.tracks)
        assertEquals(listOf(crossing), layout.crossings)
        assertContentEquals(intArrayOf(1100, 1101, 1102, 1103), tracks[1].slotComponents)
        assertContentEquals(intArrayOf(2100, 2101, 2102, 2103), tracks[1].runeComponents)
        assertEquals(12, tracks[1].clockwiseButton)
        assertEquals(13, tracks[1].counterClockwiseButton)
        assertFalse(tracks[1].forwardIsClockwise)
        assertEquals(0, EMPTY_RUNE)
    }

    @Test
    fun `rotating a ring forward carries each rune to the next slot`() {
        val runes = listOf(intArrayOf(10, 11, 12, 13, 14))
        assertEquals(10, CelticKnotSolver.runeAt(runes, KnotSlotRef(0, 0), 0))
        assertEquals(14, CelticKnotSolver.runeAt(runes, KnotSlotRef(0, 0), 1))
        assertEquals(10, CelticKnotSolver.runeAt(runes, KnotSlotRef(0, 2), 2))
        assertEquals(11, CelticKnotSolver.runeAt(runes, KnotSlotRef(0, 2), 6))
        assertEquals(13, CelticKnotSolver.runeAt(runes, KnotSlotRef(0, 2), -1))
    }

    @Test
    fun `a crossing is aligned when its two runes match or either is empty`() {
        val runes = listOf(intArrayOf(5, 6, 7), intArrayOf(7, EMPTY_RUNE, 9))
        val crossing = cross(0, 0, 1, 0)
        assertFalse(CelticKnotSolver.aligned(runes, crossing, intArrayOf(0, 0)))
        assertTrue(CelticKnotSolver.aligned(runes, crossing, intArrayOf(1, 0)))
        assertTrue(CelticKnotSolver.aligned(runes, crossing, intArrayOf(0, 2)))
        assertFalse(CelticKnotSolver.aligned(runes, crossing, intArrayOf(2, 1)))
    }

    @Test
    fun `an aligned knot needs no rotation`() {
        val layout = KnotLayout(0, 0, listOf(track(0, 4), track(1, 4)), listOf(cross(0, 1, 1, 3)))
        assertEquals(emptyList(), CelticKnotSolver.solve(layout, listOf(intArrayOf(1, 8, 2, 3), intArrayOf(4, 5, 6, 8))))
    }

    @Test
    fun `an empty rune never blocks a crossing`() {
        val layout = KnotLayout(0, 0, listOf(track(0, 3), track(1, 3)), listOf(cross(0, 0, 1, 0)))
        assertEquals(emptyList(), CelticKnotSolver.solve(layout, listOf(intArrayOf(1, 2, 3), intArrayOf(EMPTY_RUNE, 5, 6))))
    }

    @Test
    fun `a short forward shift presses the forward button`() {
        for (clockwise in listOf(true, false)) {
            val layout = KnotLayout(0, 0, listOf(track(0, 4), track(1, 4, clockwise)), listOf(cross(0, 0, 1, 0)))
            val move = assertNotNull(CelticKnotSolver.solve(layout, listOf(intArrayOf(9, 9, 9, 9), intArrayOf(1, 2, 3, 9)))).single()
            assertEquals(1, move.track.index)
            assertEquals(1, move.steps)
            assertEquals(clockwise, move.clockwise)
            assertEquals(if (clockwise) 12 else 13, move.buttonComponent)
        }
    }

    @Test
    fun `a long forward shift is made the short way back`() {
        for (clockwise in listOf(true, false)) {
            val layout = KnotLayout(0, 0, listOf(track(0, 4), track(1, 4, clockwise)), listOf(cross(0, 0, 1, 0)))
            val move = assertNotNull(CelticKnotSolver.solve(layout, listOf(intArrayOf(9, 9, 9, 9), intArrayOf(1, 9, 2, 3)))).single()
            assertEquals(1, move.steps)
            assertEquals(!clockwise, move.clockwise)
        }
    }

    @Test
    fun `half a turn goes forward`() {
        for (clockwise in listOf(true, false)) {
            val layout = KnotLayout(0, 0, listOf(track(0, 4), track(1, 4, clockwise)), listOf(cross(0, 0, 1, 0)))
            val move = assertNotNull(CelticKnotSolver.solve(layout, listOf(intArrayOf(9, 9, 9, 9), intArrayOf(1, 2, 9, 3)))).single()
            assertEquals(2, move.steps)
            assertEquals(clockwise, move.clockwise)
        }
    }

    @Test
    fun `the cheaper ring is the one that turns`() {
        val layout = KnotLayout(0, 0, listOf(track(0, 10), track(1, 10)), listOf(cross(0, 0, 1, 0), cross(0, 5, 1, 5)))
        val runes = listOf(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 1, 2))
        val moves = assertNotNull(CelticKnotSolver.solve(layout, runes))
        assertSolves(layout, runes, moves)
        assertEquals(2, moves.sumOf { it.steps })
    }

    @Test
    fun `a knot no rotation can align gives nothing`() {
        val layout = KnotLayout(0, 0, listOf(track(0, 3), track(1, 3)), listOf(cross(0, 0, 1, 0)))
        assertNull(CelticKnotSolver.solve(layout, listOf(intArrayOf(1, 2, 3), intArrayOf(4, 5, 6))))
        val twoCrossings = KnotLayout(0, 0, listOf(track(0, 4), track(1, 4)), listOf(cross(0, 0, 1, 0), cross(0, 1, 1, 1)))
        assertNull(CelticKnotSolver.solve(twoCrossings, listOf(intArrayOf(1, 2, 3, 4), intArrayOf(2, 1, 5, 6))))
    }

    @Test
    fun `runes that do not fit the layout give nothing`() {
        val layout = KnotLayout(0, 0, listOf(track(0, 4), track(1, 4)), listOf(cross(0, 0, 1, 0)))
        assertNull(CelticKnotSolver.solve(layout, listOf(intArrayOf(1, 2, 3, 4))))
        assertNull(CelticKnotSolver.solve(layout, listOf(intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 3))))
        assertNull(CelticKnotSolver.solve(layout, listOf(intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 3, 4))))
    }

    @Test
    fun `the captured trail knot 04 board is solved in thirteen presses`() {
        val layout = trailKnot04()
        val runes = listOf(
            intArrayOf(
                19129, 19127, 19132, 19142, 19133, 19138, 19142, 19133, 19124, 19141, 19127, 19138, 19139, 19131,
                19138, 19125, 19129, 19131, 19133, 19126, 19135, 19136, 19131, 19123, 19140, 19138, 19131, 19134,
            ),
            intArrayOf(19141, 19142, 19138, 19130, 19129, 19124, 19139, 19136, 19138, 19123, 19134, 19127, 19125, 19139),
            intArrayOf(19138, 19133, 19128, 19141, 19124, 19126, 19136, 19133, 19138, 19130, 19129, 19141, 19132, 19127),
        )
        assertEquals(0, layout.crossings.count { CelticKnotSolver.aligned(runes, it, IntArray(3)) })
        val moves = assertNotNull(CelticKnotSolver.solve(layout, runes))
        assertSolves(layout, runes, moves)
        assertEquals(13, moves.sumOf { it.steps })
    }

    @Test
    fun `scrambled boards on the real layouts are solved in the fewest presses`() {
        val rng = Random(526)
        for (layout in listOf(trailKnot04(), trailKnot08FromGeometry())) {
            repeat(6) {
                val solved = layout.tracks.map { t -> IntArray(t.size) { 1 + rng.nextInt(6) } }
                for (crossing in layout.crossings) {
                    solved[crossing.b.track][crossing.b.slot] = solved[crossing.a.track][crossing.a.slot]
                }
                val scramble = layout.tracks.map { rng.nextInt(it.size) }
                val runes = layout.tracks.indices.map { t ->
                    val ring = solved[t]
                    IntArray(ring.size) { ring[Math.floorMod(it + scramble[t], ring.size)] }
                }
                val moves = assertNotNull(CelticKnotSolver.solve(layout, runes))
                assertSolves(layout, runes, moves)
            }
        }
    }

    @Test
    fun `a real layout with clashing rings gives nothing`() {
        val layout = trailKnot08FromGeometry()
        val runes = layout.tracks.map { t -> IntArray(t.size) { t.index + 1 } }
        assertNull(CelticKnotSolver.solve(layout, runes))
    }

    @Test
    fun `every trail knot interface is found by name`() {
        assertContentEquals(intArrayOf(394, 519, 525, 526, 529, 1000, 1001, 1002, 1003), CelticKnotLayouts.interfaceIds)
    }

    @Test
    fun `trail knot 08 is laid out from its component names and tile geometry`() {
        val layout = trailKnot08FromGeometry()
        assertEquals(529, layout.interfaceId)
        assertEquals(0, layout.windowComponent)
        assertEquals(listOf(0, 1, 2, 3), layout.tracks.map { it.index })
        assertEquals(listOf(12, 12, 12, 12), layout.tracks.map { it.size })
        assertTrue(layout.tracks.all { it.forwardIsClockwise })
        assertEquals(listOf(10 to 11, 12 to 13, 15 to 14, 17 to 16), layout.tracks.map { it.clockwiseButton to it.counterClockwiseButton })
        assertContentEquals(intArrayOf(18, 19, 20, 21, 22, 95, 23, 61, 24, 91, 25, 58), layout.tracks[0].slotComponents)
        assertContentEquals(intArrayOf(106, 107, 108, 109, 104, 105, 72, 73, 102, 103, 70, 71), layout.tracks[0].runeComponents)
        assertContentEquals(intArrayOf(42, 43, 44, 45, 46, 77, 47, 51, 48, 75, 49, 50), layout.tracks[3].slotComponents)
        assertContentEquals(intArrayOf(118, 119, 120, 121, 88, 89, 59, 60, 84, 85, 56, 57), layout.tracks[3].runeComponents)
        assertEquals(TRAIL_KNOT_08_CROSSINGS, describe(layout))
        for (crossing in layout.crossings) {
            assertTrue(crossing.a.track < crossing.b.track)
            assertEquals(layout.tracks[crossing.a.track].slotComponents[crossing.a.slot], crossing.tileComponent)
        }
    }

    @Test
    fun `a mirrored knot winds the other way`() {
        val layout = assertNotNull(CelticKnotLayouts.forInterface(526) { slot -> trailKnot07Rects(mirror = true)[slot] })
        assertEquals(listOf(18, 16, 10, 10), layout.tracks.map { it.size })
        assertTrue(layout.tracks.none { it.forwardIsClockwise })
        assertEquals(
            setOf(
                "0:1-2:9", "0:3-1:3", "0:4-2:6", "0:5-3:1", "0:6-1:10", "0:8-3:8", "0:10-1:12",
                "0:12-1:14", "0:15-1:15", "0:17-1:1", "1:2-2:8", "1:4-2:4", "1:9-3:3", "1:11-3:9",
            ),
            describe(layout),
        )
    }

    @Test
    fun `runes are read from each slot's rune component`() {
        val layout = trailKnot08FromGeometry()
        val runes = CelticKnotLayouts.readRunes(layout) { component -> component * 10 }
        assertEquals(4, runes.size)
        layout.tracks.forEachIndexed { t, track ->
            assertContentEquals(IntArray(track.size) { track.runeComponents[it] * 10 }, runes[t])
        }
    }

    @Test
    fun `a knot that is not fully laid out yet has no layout`() {
        assertNull(CelticKnotLayouts.forInterface(1000) { null })
        assertNull(CelticKnotLayouts.forInterface(1000) { component -> if (component == 99) null else ScreenRect(component, 0, 48, 48) })
        assertNull(CelticKnotLayouts.forInterface(1001) { component -> ScreenRect(component, 0, 0, 48) })
        assertNull(CelticKnotLayouts.forInterface(1001) { component -> ScreenRect(component, 0, 48, 0) })
    }

    @Test
    fun `a knot whose rings never share a tile has no layout`() {
        assertNull(CelticKnotLayouts.forInterface(1001) { component -> ScreenRect(component * 50, component * 7, 48, 48) })
    }

    @Test
    fun `an interface that is not a trail knot has no layout`() {
        assertNull(CelticKnotLayouts.forInterface(79) { ScreenRect(0, 0, 48, 48) })
    }

    private fun describe(layout: KnotLayout) =
        layout.crossings.map { "${it.a.track}:${it.a.slot}-${it.b.track}:${it.b.slot}" }.toSet()

    private fun trailKnot08FromGeometry(): KnotLayout = assertNotNull(
        CelticKnotLayouts.forInterface(529) { component ->
            TRAIL_KNOT_08_SLOTS.withIndex().firstNotNullOfOrNull { (t, slots) ->
                val s = slots.indexOf(component)
                if (s < 0) null else ScreenRect(TRAIL_KNOT_08_XY[t][s * 2], TRAIL_KNOT_08_XY[t][s * 2 + 1], 48, 48)
            }
        },
    )

    private fun trailKnot07Rects(mirror: Boolean): Map<Int, ScreenRect> = buildMap {
        TRAIL_KNOT_07_SLOTS.forEachIndexed { t, slots ->
            slots.forEachIndexed { s, component ->
                val x = TRAIL_KNOT_07_XY[t][s * 2]
                put(component, ScreenRect(if (mirror) -x else x, TRAIL_KNOT_07_XY[t][s * 2 + 1], 48, 48))
            }
        }
    }

    private fun trailKnot04(): KnotLayout {
        val slots = listOf(
            intArrayOf(27, 28, 29, 30, 80, 31, 110, 32, 33, 34, 35, 77, 36, 108, 37, 38, 39, 40, 104, 41, 72, 42, 43, 44, 45, 100, 46, 71),
            intArrayOf(47, 98, 48, 70, 49, 50, 96, 51, 69, 52, 53, 54, 55, 56),
            intArrayOf(57, 92, 58, 59, 60, 61, 62, 63, 68, 64, 94, 65, 66, 67),
        )
        val runes = listOf(
            intArrayOf(123, 124, 125, 89, 90, 121, 122, 126, 127, 128, 87, 88, 119, 120, 129, 130, 131, 117, 118, 85, 86, 132, 133, 134, 115, 116, 83, 84),
            intArrayOf(113, 114, 81, 82, 135, 111, 112, 78, 79, 136, 137, 138, 139, 140),
            intArrayOf(101, 102, 141, 142, 143, 144, 145, 75, 76, 105, 106, 146, 73, 74),
        )
        val buttons = listOf(22 to 21, 23 to 24, 26 to 25)
        val tracks = slots.indices.map { KnotTrack(it, slots[it], runes[it], buttons[it].first, buttons[it].second, true) }
        val pairs = listOf(0 to 4 to (1 to 1), 0 to 6 to (1 to 3), 0 to 11 to (1 to 6), 0 to 13 to (1 to 8), 0 to 18 to (2 to 8), 0 to 20 to (2 to 10), 0 to 25 to (2 to 13), 0 to 27 to (2 to 1))
        val crossings = pairs.map { (a, b) ->
            KnotCrossing(KnotSlotRef(a.first, a.second), KnotSlotRef(b.first, b.second), slots[a.first][a.second])
        }
        return KnotLayout(394, 0, tracks, crossings)
    }

    private companion object {
        val TRAIL_KNOT_08_CROSSINGS = setOf("0:5-2:7", "0:7-2:5", "0:9-1:3", "0:11-1:1", "1:5-3:7", "1:7-3:5", "2:1-3:11", "2:3-3:9")

        val TRAIL_KNOT_08_SLOTS = listOf(
            intArrayOf(18, 19, 20, 21, 22, 95, 23, 61, 24, 91, 25, 58),
            intArrayOf(26, 79, 27, 52, 28, 87, 29, 55, 30, 31, 32, 33),
            intArrayOf(34, 83, 35, 54, 36, 81, 37, 53, 38, 39, 40, 41),
            intArrayOf(42, 43, 44, 45, 46, 77, 47, 51, 48, 75, 49, 50),
        )

        val TRAIL_KNOT_08_XY = listOf(
            intArrayOf(-24, 120, -48, 96, -72, 72, -96, 48, -120, 24, -96, 0, -72, -24, -48, 0, -24, 24, 0, 48, 24, 72, 0, 96),
            intArrayOf(24, 120, 0, 96, -24, 72, 0, 48, 24, 24, 48, 0, 72, -24, 96, 0, 120, 24, 96, 48, 72, 72, 48, 96),
            intArrayOf(-24, -120, 0, -96, 24, -72, 0, -48, -24, -24, -48, 0, -72, 24, -96, 0, -120, -24, -96, -48, -72, -72, -48, -96),
            intArrayOf(24, -120, 48, -96, 72, -72, 96, -48, 120, -24, 96, 0, 72, 24, 48, 0, 24, -24, 0, -48, -24, -72, 0, -96),
        )

        val TRAIL_KNOT_07_SLOTS = listOf(
            intArrayOf(18, 123, 19, 76, 75, 73, 72, 20, 44, 21, 119, 22, 90, 23, 24, 115, 25, 70),
            intArrayOf(26, 89, 87, 86, 84, 27, 28, 29, 30, 61, 59, 58, 56, 31, 100, 98),
            intArrayOf(32, 33, 34, 35, 111, 36, 62, 37, 103, 101),
            intArrayOf(38, 113, 39, 50, 40, 41, 42, 43, 49, 47),
        )

        val TRAIL_KNOT_07_XY = listOf(
            intArrayOf(-12, 96, -36, 72, -60, 48, -36, 24, -12, 0, 12, -24, 36, -48, 60, -72, 84, -48, 108, -24, 84, 0, 60, 24, 84, 48, 108, 72, 84, 96, 60, 72, 36, 48, 12, 72),
            intArrayOf(36, 96, 12, 72, -12, 48, -36, 24, -60, 0, -84, -24, -60, -48, -36, -72, -12, -96, 12, -72, 36, -48, 60, -24, 84, 0, 108, 24, 84, 48, 60, 72),
            intArrayOf(-60, 96, -84, 72, -108, 48, -84, 24, -60, 0, -36, -24, -12, 0, 12, 24, -12, 48, -36, 72),
            intArrayOf(36, 0, 12, -24, -12, -48, 12, -72, 36, -96, 60, -120, 84, -96, 108, -72, 84, -48, 60, -24),
        )
    }
}
