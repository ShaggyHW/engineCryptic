package com.projectx.traversal.nodes

import com.projectx.script.api.actionbarAbilities
import com.projectx.script.api.diveAbility
import com.projectx.script.api.varps
import com.projectx.traversal.pathing.PathingTraversal
import com.projectx.util.FeatureIds
import org.projectx.core.game.combat.Ability
import world.gregs.voidps.gameval.Gameval

/**
 * Surge and Dive casts as every walker sees them. The cooldown readout trails a cast for a moment, so a hop just sent
 * is marked spent and counts as on cooldown for up to [SPENT_MAX_MS]; shared, so a new walk node does not cast it again.
 */
internal object HopCharges {
    /** A spent mark overrides the cooldown readout for at most this long after the cast. */
    const val SPENT_MAX_MS = 2_500L

    /** The readout's off-cooldown threshold: one tick. */
    private const val READY_LEFT_MS = 600L

    @Volatile
    var surgeSpent = false

    @Volatile
    var diveSpent = false

    @Volatile
    var surgeSpentAt = 0L

    @Volatile
    var diveSpentAt = 0L

    private val doubleSurgeVarbit = FeatureIds("HopCharges") { Gameval.requireId(Gameval.VARBIT, "dino_agility_double_surge_unlocked") }

    /** Two surge charges: back-to-back surges are real, and the first charge never shows on the readout. */
    fun doubleSurge(): Boolean = runCatching { doubleSurgeVarbit.get()?.let { varps.getVarBit(it) == 1 } == true }.getOrDefault(false)

    /** Dive or Bladed Dive, whichever is on the action bar. */
    fun diveOnBar(): Ability? = runCatching { diveAbility }.getOrNull()

    private fun onBar(ability: Ability): Boolean = runCatching { actionbarAbilities[ability.type] != null }.getOrDefault(false)

    /** The ability's cooldown outside a burst, read as the route request reads it. */
    fun fullCooldownMs(ability: Ability): Long =
        PathingTraversal.abilityCooldownMs(ability, ability.type.cooldownParamTicks * TraversalSupport.TICK_MS)

    private fun readoutMs(ability: Ability): Long = ability.type.cooldownMsIgnoreGCD()

    /** Double Surge and no surge cast for a whole cooldown: both charges are back, each recharging from its own use. */
    fun secondSurgeCharge(): Boolean =
        doubleSurge() && (surgeSpentAt == 0L || System.currentTimeMillis() - surgeSpentAt > fullCooldownMs(Ability.SURGE))

    /** Cooldown left on [ability], 0 when it can be cast; just cast, the spent mark reads as a full cooldown. */
    fun cooldownLeftMs(ability: Ability, surge: Boolean): Long {
        val spent = if (surge) surgeSpent else diveSpent
        val at = if (surge) surgeSpentAt else diveSpentAt
        val left = readoutMs(ability)
        if (spent && System.currentTimeMillis() - at < SPENT_MAX_MS && left <= READY_LEFT_MS) return Long.MAX_VALUE
        return if (left <= READY_LEFT_MS) 0 else left
    }

    /**
     * How long until every hop on the action bar is back: Surge with all its charges and Dive off cooldown; 0 when they
     * are. With Double Surge both charges are back only a whole cooldown after the last surge sent, as the readout never
     * shows the first. A hop cast by hand is seen only through the readout.
     */
    fun msUntilRecharged(): Long {
        val now = System.currentTimeMillis()
        var wait = 0L
        if (onBar(Ability.SURGE)) {
            wait = maxOf(wait, readoutMs(Ability.SURGE))
            if (surgeSpentAt > 0) {
                val charges = doubleSurge()
                val back = surgeSpentAt + if (charges) fullCooldownMs(Ability.SURGE) else SPENT_MAX_MS
                if (charges || Ability.SURGE.offCdIgnoreGCD) wait = maxOf(wait, back - now)
            }
        }
        val dive = diveOnBar()
        if (dive != null) {
            wait = maxOf(wait, readoutMs(dive))
            if (diveSpentAt > 0 && dive.offCdIgnoreGCD) wait = maxOf(wait, diveSpentAt + SPENT_MAX_MS - now)
        }
        return maxOf(0L, wait)
    }
}
