package com.projectx.script.api

import com.projectx.game.target
import com.projectx.script.Script
import org.projectx.core.game.combat.Ability
import world.gregs.voidps.type.Tile

/**
 * The gap between the clicks of a Dive / Surge combo: about a frame, so the game has handled the dive's selection
 * before its tile click, and nothing like a paced human gap or a single dive's 50-300 ms.
 */
const val COMBO_GAP_MIN_MS = 15L
const val COMBO_GAP_MAX_MS = 35L

/** Dives to [tile] with [ability], waiting [minDelayMs]..[maxDelayMs] between selecting it and clicking the tile. */
@JvmOverloads
suspend fun Script.dive(tile: Tile, ability: Ability, minDelayMs: Long = 50, maxDelayMs: Long = 300): Boolean {
    if (!ability.offCdIgnoreGCD) return false
    val slot = actionbarAbilities[ability.type] ?: return false
    if (!slot.select()) return false
    delay(HumanTiming.between(minDelayMs, maxDelayMs).toInt())
    return tile.target()
}

/**
 * Dives to [tile] with [dive], then Surges, as one motion: the dive's selection is paced like any action, its tile
 * click and the surge follow at once. Sent in one tick, the server dives, then surges along the dive's line. Returns
 * the hops sent: 0 none, 1 the dive only, 2 both.
 */
suspend fun Script.diveThenSurge(tile: Tile, dive: Ability): Int {
    val diveSlot = actionbarAbilities[dive.type] ?: return 0
    if (!dive.offCdIgnoreGCD) return 0
    ActionPacer.ACTIONS.await()
    if (!diveSlot.select()) return 0
    comboGap()
    if (!ActionPacer.ACTIONS.followUp { tile.target() }) return 0
    val surgeSlot = actionbarAbilities[Ability.SURGE.type] ?: return 1
    comboGap()
    return if (ActionPacer.ACTIONS.followUp { surgeSlot.click(1) }) 2 else 1
}

/**
 * Surges, then Dives to [tile] with [dive], as one motion: the surge is paced like any action, the dive's selection
 * and tile click follow at once. Sent in one tick, the server surges, then dives from the surge's landing. Returns
 * the hops sent: 0 none, 1 the surge only, 2 both.
 */
suspend fun Script.surgeThenDive(tile: Tile, dive: Ability): Int {
    val surgeSlot = actionbarAbilities[Ability.SURGE.type] ?: return 0
    if (!Ability.SURGE.offCdIgnoreGCD) return 0
    ActionPacer.ACTIONS.await()
    if (!surgeSlot.click(1)) return 0
    val diveSlot = actionbarAbilities[dive.type]
    if (diveSlot == null || !dive.offCdIgnoreGCD) return 1
    comboGap()
    if (!ActionPacer.ACTIONS.followUp { diveSlot.select() }) return 1
    comboGap()
    return if (ActionPacer.ACTIONS.followUp { tile.target() }) 2 else 1
}

private suspend fun Script.comboGap() = delay(HumanTiming.between(COMBO_GAP_MIN_MS, COMBO_GAP_MAX_MS).toInt())
