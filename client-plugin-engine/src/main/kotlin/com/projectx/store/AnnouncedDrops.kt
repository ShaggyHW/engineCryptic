package com.projectx.store

/**
 * Drops already reported from a chat announcement, so the same item seen later in a reward chest or on the floor is
 * not reported twice. A chest can sit unopened for a while, hence the long hold.
 */
internal class AnnouncedDrops(private val holdMillis: Long = 30 * 60_000L) {
    private class Held(var quantity: Int, val at: Long)

    private val held = HashMap<String, MutableList<Held>>()

    fun announce(itemName: String, quantity: Int, now: Long) {
        held.getOrPut(itemName.lowercase()) { ArrayList() } += Held(quantity, now)
    }

    /** How many of [quantity] were not already announced; those that were are used up. */
    fun unannounced(itemName: String, quantity: Int, now: Long): Int {
        val entries = held[itemName.lowercase()] ?: return quantity
        entries.removeIf { now - it.at > holdMillis }
        var left = quantity
        for (entry in entries) {
            val taken = minOf(entry.quantity, left)
            entry.quantity -= taken
            left -= taken
            if (left == 0) break
        }
        entries.removeIf { it.quantity == 0 }
        return left
    }
}
