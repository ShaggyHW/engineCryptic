package com.projectx.store

/**
 * Loot that lands in reward containers: whatever a container gains between looks. A container that closes and opens
 * again shows its loot afresh, as a chest does; what is already there on the first look is not loot.
 */
internal class ContainerDrops {
    private val last = HashMap<Int, Map<Int, Int>>()
    private var primed = false

    /** Container id to its item counts, or null when the client has no such container open. Returns item id to count gained. */
    fun observe(contents: Map<Int, Map<Int, Int>?>): Map<Int, Int> {
        val gained = HashMap<Int, Int>()
        for ((container, items) in contents) {
            if (items == null) {
                last.remove(container)
                continue
            }
            if (primed) RareDropRules.gained(last[container].orEmpty(), items).forEach { (id, n) -> gained.merge(id, n, Int::plus) }
            last[container] = items
        }
        primed = true
        return gained
    }
}
