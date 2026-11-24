package com.projectx.traversal.nodes

import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.hops.ScheduleStore
import world.gregs.voidps.type.Tile
import java.util.Locale

/**
 * What comes after a walk, for the hop schedule: each [Leg] is an interaction (a door, stairs, a lodestone, an item or
 * fairy-ring teleport, an interface) during which no hop goes but cooldowns run, then the walk after it.
 *
 * How long an interaction keeps hops off starts from a default per node type and is measured live: the gap between one
 * walk node's last step and the next walk node's first ([record]). The samples live in [ScheduleStore] under `dead:`
 * keys, shared and exported with the rest of what the schedule learns; the default counts as one sample.
 */
internal object Interactions {
    const val MAX_LEGS = 2
    const val MAX_TILES = 250

    /** A longer gap is not one interaction (a dialog, a fight): not recorded. */
    const val MAX_GAP_MS = 30_000L

    /** A shorter gap was no interaction at all: a door open all along, walked straight through. */
    const val MIN_GAP_MS = 600L

    private const val TIMING_PRIOR_WEIGHT = 1.0
    private const val BETWEEN_LOOKAHEAD = 12

    data class Leg(val what: String, val deadMs: Long, val tiles: List<Tile>)

    // A door clicked and walked through (~3 ticks), stairs climbed (~5), an item teleport (~8), a fairy ring dialled
    // (~10), a lodestone cast with its landing (~17).
    private val DEFAULTS = mapOf(
        "DoorNode" to 1_800L,
        "ObjectNode" to 3_000L,
        "ItemOnObjectNode" to 3_000L,
        "IFSlotNode" to 3_000L,
        "ItemNode" to 4_800L,
        "POAItemNode" to 4_800L,
        "FairyRingNode" to 6_000L,
        "LodestoneNode" to 10_800L,
    )

    private fun type(node: TraversalNode): String = node::class.java.simpleName

    /** The node type, with its action for objects, doors and NPCs: a 2.8 s climb and a 5.3 s jump are not one estimate. */
    fun key(node: TraversalNode): String {
        val type = type(node)
        val action = when (node) {
            is ObjectNode -> node.action
            is DoorNode -> node.action
            is NpcNode -> node.action
            else -> null
        }
        return if (action != null) "$type:$action" else type
    }

    /** The hop-free time of an interaction node: learned for its [key], else its type, else the default; -1 when not one. */
    fun deadMs(node: TraversalNode): Long {
        val type = type(node)
        val default = DEFAULTS[type] ?: return -1L
        return deadMs(key(node), type, default)
    }

    fun deadMs(key: String, type: String, default: Long): Long {
        val k = if (ScheduleStore.get("dead:$key").w > 0) key else type
        val sums = ScheduleStore.get("dead:$k")
        return Math.round((sums.s1 + TIMING_PRIOR_WEIGHT * default) / (sums.w + TIMING_PRIOR_WEIGHT))
    }

    /** Walking alone gets the player past [node] now, and a walk may aim through it. */
    fun walkable(node: TraversalNode?): Boolean {
        val walk = node as? WalkEquivalent ?: return false
        return walk.walkThrough() && walk.passableNow() && walk.routeTiles().isNotEmpty()
    }

    /**
     * The legs after [node]'s walk: past the walk-through nodes its route already runs into, then interaction, walk,
     * interaction, walk... up to [MAX_LEGS] and [MAX_TILES]. Stops at an NPC, a node of unknown length or the end.
     */
    fun after(node: TraversalNode, from: Tile?): List<Leg> {
        var k = node.next
        var through = 0
        while (k != null && through < TraversalSupport.MAX_THROUGH_NODES && walkable(k)) {
            k = k.next
            through++
        }
        val legs = ArrayList<Leg>()
        var total = 0
        var before = from
        while (k != null && legs.size < MAX_LEGS && total < MAX_TILES) {
            var dead = deadMs(k)
            if (dead < 0) break
            val what = key(k)
            val tiles = ArrayList<Tile>()
            val route = (k as? WalkEquivalent)?.routeTiles()
            if (!route.isNullOrEmpty()) {
                before = route.first()
                tiles += farSide(route)
            }
            k = k.next
            while (k != null && walkable(k) && total + tiles.size < MAX_TILES) {
                tiles += (k as WalkEquivalent).routeTiles()
                k = k.next
            }
            if (tiles.isNotEmpty() && lands(before, tiles.first())) dead += TraversalSupport.LANDED_HOLD_MS
            legs += Leg(what, dead, tiles)
            total += tiles.size
            if (tiles.isNotEmpty()) before = tiles.last()
        }
        return legs
    }

    /** The tiles of an interaction node walked once it is passed: all but the first, where it is done from. */
    fun farSide(routeTiles: List<Tile>): List<Tile> = if (routeTiles.size <= 1) emptyList() else routeTiles.subList(1, routeTiles.size)

    /** The walk after starts with a landing when its first tile is a jump from [before] (unknown: a landing). */
    fun lands(before: Tile?, first: Tile): Boolean = before == null || before.getLongestDelta(first) > TraversalSupport.TELEPORT_JUMP_TILES

    /** A measured hop-free gap for [node]: a sample of its key and of its type, the fallback for actions not seen yet. */
    fun record(node: TraversalNode, gapMs: Long) {
        val type = type(node)
        val key = key(node)
        if (gapMs < MIN_GAP_MS || gapMs > MAX_GAP_MS || type !in DEFAULTS) return
        val was = deadMs(node)
        ScheduleStore.add("dead:$key", gapMs.toDouble())
        if (key != type) ScheduleStore.add("dead:$type", gapMs.toDouble())
        println("[hops] $key kept hops off $gapMs ms; estimate $was -> ${deadMs(node)} ms")
    }

    /** "Climb-over 2.8 s + 20 tiles, Jump-over 4.3 s + 5 tiles", for the logs. */
    fun describe(legs: List<Leg>): String {
        if (legs.isEmpty()) return "nothing the schedule looks past"
        return legs.joinToString(", ") { "${it.what} ${String.format(Locale.ROOT, "%.1f", it.deadMs / 1000.0)} s + ${it.tiles.size} tiles" }
    }

    /**
     * The one interaction node between [from]'s walk and [to], or null: none, more than one (two in one gap cannot be
     * told apart), or [to] not reached. By type, not by [walkable]: once passed, a door reads open.
     */
    fun between(from: TraversalNode, to: TraversalNode): TraversalNode? {
        var found: TraversalNode? = null
        var k = from.next
        var n = 0
        while (k != null && n < BETWEEN_LOOKAHEAD) {
            if (k === to) return found
            if (deadMs(k) >= 0) {
                if (found != null) return null
                found = k
            }
            k = k.next
            n++
        }
        return null
    }
}
