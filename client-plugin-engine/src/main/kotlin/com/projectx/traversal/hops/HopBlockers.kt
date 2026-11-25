package com.projectx.traversal.hops

import world.gregs.voidps.cache.Cache
import java.util.concurrent.ConcurrentHashMap

/**
 * Which locs block an edge: the locs whose own collision sets walk bits on the step's cells. Lets one learned crossing
 * count for the loc type, and keeps doors out of probing. The answer comes from [source], which may block, so it is
 * only asked off the hot path (explaining a hop, vetting a probe); answers are cached per edge for the session.
 */
internal object HopBlockers {
    data class Blocker(
        val locId: Int,
        val shape: Int,
        val rotation: Int,
        val clipType: Int,
        val blocksProjectile: Boolean,
        val bitsFrom: Int,
        val bitsTo: Int,
        val bitsSides: Int,
    ) {
        val typeKey: String get() = HopEdges.typeKey(locId, shape)
    }

    /** The blockers of the step from (x, y, plane) by ([dx], [dy]); null when they cannot be told. */
    fun interface Source {
        fun blockers(x: Int, y: Int, plane: Int, dx: Int, dy: Int): List<Blocker>?
    }

    private val UNKNOWN = Source { _, _, _, _, _ -> null }

    @Volatile
    var source: Source = UNKNOWN

    private val cache = ConcurrentHashMap<Long, List<Blocker>>()

    @Volatile
    var doorForTests: ((Int) -> Boolean)? = null

    /** The blockers of [edge], or null when the source could not say. May block. */
    fun resolve(edge: Long): List<Blocker>? {
        cache[edge]?.let { return it }
        val direction = HopEdges.direction(edge)
        val found = runCatching {
            source.blockers(HopEdges.x(edge), HopEdges.y(edge), HopEdges.plane(edge), direction.delta.x, direction.delta.y)
        }.getOrNull() ?: return null
        return found.toList().also { cache[edge] = it }
    }

    /** Hot path: every cached blocker of [edge] is a usable type; never asks the source. */
    fun coveredByTypes(edge: Long, usableTypes: Set<String>): Boolean {
        val blockers = cache[edge]
        if (blockers.isNullOrEmpty()) return false
        return blockers.all { it.typeKey in usableTypes }
    }

    /** A door, gate or anything else opened by hand: its state is live, never a hop fact. Unknown locs count as doors. */
    fun isDoor(blockers: List<Blocker>): Boolean {
        doorForTests?.let { test -> return blockers.any { test(it.locId) } }
        for (blocker in blockers) {
            val type = runCatching { Cache.loc(blocker.locId) }.getOrNull() ?: return true
            val name = type.name.lowercase()
            if ("door" in name || "gate" in name) return true
            val opens = type.options?.any { option ->
                val o = option?.lowercase() ?: return@any false
                o == "open" || o == "close" || o.startsWith("pick-lock") || o == "unlock"
            } ?: false
            if (opens) return true
        }
        return false
    }

    fun putForTests(edge: Long, blockers: List<Blocker>) {
        cache[edge] = blockers.toList()
    }

    fun clearForTests() {
        cache.clear()
    }
}
