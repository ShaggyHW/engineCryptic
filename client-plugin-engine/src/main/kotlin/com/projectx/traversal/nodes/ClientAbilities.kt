package com.projectx.traversal.nodes

/**
 * Which movement abilities a walk node may cast on its own, where the live map shows a clear hop along the route and
 * the ability is ready. [NONE] for walks that must only walk. [accelerationPotion]: may also drink a Powerburst of
 * acceleration where a run of hops pays for it. [exploreHops]: on a hop-learning client, may also cast probes across
 * edges the map calls blocked. [scheduleHops]: a ready hop may be kept for a better spot ahead instead of going at once.
 */
data class ClientAbilities @JvmOverloads constructor(
    val surge: Boolean,
    val dive: Boolean,
    val accelerationPotion: Boolean = false,
    val exploreHops: Boolean = true,
    val scheduleHops: Boolean = true,
) {
    fun any(): Boolean = surge || dive

    companion object {
        @JvmField
        val NONE = ClientAbilities(surge = false, dive = false, accelerationPotion = false, exploreHops = false, scheduleHops = false)

        /** Milliseconds until Surge (every charge) and Dive on the action bar are all back off cooldown; 0 when they are. */
        @JvmStatic
        fun msUntilRecharged(): Long = HopCharges.msUntilRecharged()
    }
}
