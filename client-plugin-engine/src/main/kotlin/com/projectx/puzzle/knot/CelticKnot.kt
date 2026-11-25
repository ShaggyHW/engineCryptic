package com.projectx.puzzle.knot

import com.projectx.game.nxt.interfaces.ScreenRect
import world.gregs.voidps.gameval.Gameval
import java.util.concurrent.ConcurrentHashMap

const val EMPTY_RUNE = 0

class KnotTrack(
    val index: Int,
    val slotComponents: IntArray,
    val runeComponents: IntArray,
    val clockwiseButton: Int,
    val counterClockwiseButton: Int,
    val forwardIsClockwise: Boolean,
) {
    val size: Int get() = slotComponents.size
}

class KnotSlotRef(val track: Int, val slot: Int)

class KnotCrossing(val a: KnotSlotRef, val b: KnotSlotRef, val tileComponent: Int)

class KnotLayout(
    val interfaceId: Int,
    val windowComponent: Int,
    val tracks: List<KnotTrack>,
    val crossings: List<KnotCrossing>,
)

class KnotRotation(val track: KnotTrack, val steps: Int, val clockwise: Boolean) {
    val buttonComponent: Int get() = if (clockwise) track.clockwiseButton else track.counterClockwiseButton
}

object CelticKnotLayouts {

    private const val NAME_PREFIX = "trail_knot"
    private const val TRACK_LIMIT = 8
    private const val SHORTEST_RING = 3

    private val laidOut = ConcurrentHashMap<Int, KnotLayout>()

    val interfaceIds: IntArray by lazy {
        Gameval.entries(Gameval.INTERFACE).filterValues { it.startsWith(NAME_PREFIX) }.keys.sorted().toIntArray()
    }

    /** Built from component names plus live tile rects; only complete layouts are kept, so an unready knot is retried. */
    fun forInterface(interfaceId: Int, rectOf: (Int) -> ScreenRect?): KnotLayout? {
        laidOut[interfaceId]?.let { return it }
        val name = Gameval.interfaceName(interfaceId)?.takeIf { it.startsWith(NAME_PREFIX) } ?: return null
        val rings = ringsNamed(name)
        if (rings.size < 2) return null
        val tileRects = rings.map { ring ->
            ring.tiles.map { tile -> rectOf(tile)?.takeIf { it.width > 0 && it.height > 0 } ?: return null }
        }
        val tracks = rings.mapIndexed { index, ring ->
            KnotTrack(index, ring.tiles, ring.runes, ring.clockwise, ring.counterClockwise, windsClockwise(tileRects[index]))
        }
        val shared = sharedTiles(tracks, tileRects).ifEmpty { return null }
        val window = Gameval.componentId("$name:mainmodal_window") ?: 0
        val layout = KnotLayout(interfaceId, window, tracks, shared)
        return laidOut.putIfAbsent(interfaceId, layout) ?: layout
    }

    fun readRunes(layout: KnotLayout, graphicOf: (Int) -> Int): List<IntArray> = layout.tracks.map { ring ->
        ring.runeComponents.map(graphicOf).toIntArray()
    }

    private class RingNames(val tiles: IntArray, val runes: IntArray, val clockwise: Int, val counterClockwise: Int)

    private fun ringsNamed(name: String): List<RingNames> {
        val rings = ArrayList<RingNames>()
        for (ring in 0 until TRACK_LIMIT) {
            val clockwise = Gameval.componentId("$name:track_${ring}_cw") ?: break
            val counterClockwise = Gameval.componentId("$name:track_${ring}_ccw") ?: break
            val tileIds = ArrayList<Int>()
            val runeIds = ArrayList<Int>()
            while (true) {
                val number = tileIds.size.toString().padStart(2, '0')
                val tileId = Gameval.componentId("$name:track_${ring}_$number") ?: break
                val runeId = Gameval.componentId("$name:rune_${ring}_$number") ?: break
                tileIds += tileId
                runeIds += runeId
            }
            if (tileIds.size < SHORTEST_RING) break
            rings += RingNames(tileIds.toIntArray(), runeIds.toIntArray(), clockwise, counterClockwise)
        }
        return rings
    }

    /** Shoelace sum over the tiles' top-left corners; with screen y growing downwards, positive reads as clockwise. */
    private fun windsClockwise(rects: List<ScreenRect>): Boolean {
        var twiceArea = 0L
        for (i in rects.indices) {
            val here = rects[i]
            val after = rects[(i + 1) % rects.size]
            twiceArea += here.x.toLong() * after.y - after.x.toLong() * here.y
        }
        return twiceArea > 0
    }

    private fun sharedTiles(tracks: List<KnotTrack>, tileRects: List<List<ScreenRect>>): List<KnotCrossing> {
        val occupants = LinkedHashMap<ScreenRect, MutableList<KnotSlotRef>>()
        tileRects.forEachIndexed { track, rects ->
            rects.forEachIndexed { slot, rect -> occupants.getOrPut(rect) { ArrayList() } += KnotSlotRef(track, slot) }
        }
        val crossings = ArrayList<KnotCrossing>()
        for (refs in occupants.values) {
            for (i in refs.indices) for (j in i + 1 until refs.size) {
                val lower = refs[i]
                val upper = refs[j]
                if (lower.track == upper.track) continue
                crossings += KnotCrossing(lower, upper, tracks[lower.track].slotComponents[lower.slot])
            }
        }
        return crossings
    }
}

object CelticKnotSolver {

    fun runeAt(runes: List<IntArray>, ref: KnotSlotRef, offset: Int): Int =
        runes[ref.track].let { shown -> shown[Math.floorMod(ref.slot - offset, shown.size)] }

    fun aligned(runes: List<IntArray>, crossing: KnotCrossing, offsets: IntArray): Boolean {
        val first = runeAt(runes, crossing.a, offsets[crossing.a.track])
        val second = runeAt(runes, crossing.b, offsets[crossing.b.track])
        return first == EMPTY_RUNE || second == EMPTY_RUNE || first == second
    }

    fun solve(layout: KnotLayout, runes: List<IntArray>): List<KnotRotation>? {
        val rings = layout.tracks
        val fits = runes.size == rings.size && rings.zip(runes).all { (ring, shown) -> ring.size == shown.size }
        if (!fits) return null
        val offsets = OffsetSearch(rings, layout.crossings, runes).cheapest() ?: return null
        return rings.filter { offsets[it.index] != 0 }.map { rotationFor(it, offsets[it.index]) }
    }

    private fun rotationFor(track: KnotTrack, offset: Int): KnotRotation {
        val back = track.size - offset
        return if (offset <= back) KnotRotation(track, offset, track.forwardIsClockwise)
        else KnotRotation(track, back, !track.forwardIsClockwise)
    }

    /** Exhaustive depth-first search over per-track offsets, checking each crossing once both of its tracks are set. */
    private class OffsetSearch(
        private val tracks: List<KnotTrack>,
        crossings: List<KnotCrossing>,
        private val runes: List<IntArray>,
    ) {
        private val settledBy: List<List<KnotCrossing>> =
            tracks.indices.map { t -> crossings.filter { maxOf(it.a.track, it.b.track) == t } }
        private val chosen = IntArray(tracks.size)
        private var best: IntArray? = null
        private var bestPresses = Int.MAX_VALUE

        fun cheapest(): IntArray? {
            visit(0, 0)
            return best
        }

        private fun visit(depth: Int, presses: Int) {
            if (presses >= bestPresses) return
            if (depth == tracks.size) {
                bestPresses = presses
                best = chosen.copyOf()
                return
            }
            val slots = tracks[depth].size
            for (turn in 0 until slots) {
                chosen[depth] = turn
                if (settledBy[depth].all { aligned(runes, it, chosen) }) visit(depth + 1, presses + minOf(turn, slots - turn))
            }
            chosen[depth] = 0
        }
    }
}
