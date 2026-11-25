package com.projectx.store

/**
 * Ground items that are this player's drops: a stack that appears where an NPC they fought stood moments ago. Another
 * player's loot turns public a minute after it falls, far from any fight of ours, so it never counts; nor does a stack
 * already seen that comes back into view.
 */
internal class GroundDrops(
    private val fightMillis: Long = 30_000L,
    private val forgetMillis: Long = 180_000L,
) {
    data class Stack(val id: Int, val amount: Int, val x: Int, val y: Int, val plane: Int)

    private data class Key(val id: Int, val x: Int, val y: Int, val plane: Int)
    private class Seen(var amount: Int, var at: Long)
    private class Fight(val x: Int, val y: Int, val size: Int, val plane: Int, val at: Long)

    private val seen = HashMap<Key, Seen>()
    private val fights = HashMap<Int, Fight>()
    private var primed = false

    /** The NPC with server index [npc] stands at ([x], [y]), [size] tiles square, and is fighting this player. */
    fun fighting(npc: Int, x: Int, y: Int, size: Int, plane: Int, now: Long) {
        fights[npc] = Fight(x, y, size.coerceAtLeast(1), plane, now)
    }

    /** The stacks in view now; returns what fell from a recent fight since the last look. The first look only learns. */
    fun observe(stacks: List<Stack>, now: Long): List<Stack> {
        fights.values.removeIf { now - it.at > fightMillis }
        val current = stacks.groupingBy { Key(it.id, it.x, it.y, it.plane) }.fold(0) { sum, s -> sum + s.amount }
        val dropped = ArrayList<Stack>()
        for ((key, amount) in current) {
            val known = seen[key]
            val grew = amount - (known?.amount ?: 0)
            if (primed && grew > 0 && fights.values.any { it.covers(key) }) dropped += Stack(key.id, grew, key.x, key.y, key.plane)
            if (known == null) seen[key] = Seen(amount, now) else {
                known.amount = maxOf(known.amount, amount)
                known.at = now
            }
        }
        seen.values.removeIf { now - it.at > forgetMillis }
        primed = true
        return dropped
    }

    private fun Fight.covers(key: Key): Boolean =
        key.plane == plane && key.x in x - MARGIN until x + size + MARGIN && key.y in y - MARGIN until y + size + MARGIN

    private companion object {
        const val MARGIN = 2
    }
}
