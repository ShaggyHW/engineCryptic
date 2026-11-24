package com.projectx.script.api

import com.projectx.game.nxt.entity.npc.NPC
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Whether an NPC is attacking the local player, remembered for a while after the attack stops showing. A hit breaks a
 * lodestone cast and closes a teleport menu, so those are left out of a route while this holds.
 *
 * An attack is seen while an NPC that offers Attack, alive and within [ATTACKER_RANGE] on our plane, targets us and is
 * either animating or we carry a hit-splat. That drops between swings, so each attacker is remembered for
 * [MEMORY_MS]; the memory ends early when the attacker dies or leaves the scene, or once we stand more than
 * [MEMORY_TILES] from where it was seen (a teleport the attacker cannot follow). The combat flag never counts: it
 * outlives every fight by seconds.
 */
object AttackMemory {
    const val ATTACKER_RANGE = 12
    const val MEMORY_MS = 3_000L
    const val MEMORY_TILES = 50

    private val ledger = AttackLedger()

    /** An attack now, or one seen within [MEMORY_MS] near where we stand. */
    @JvmStatic
    fun attackedRecently(): Boolean = attackedWithin(MEMORY_MS)

    /** An attack now, or one seen within the last [ms] near where we stand. */
    @JvmStatic
    fun attackedWithin(ms: Long): Boolean {
        if (isUnderAttack()) return true
        val here = runCatching { localPlayer.tile }.getOrNull()
        return ledger.seenWithin(System.currentTimeMillis(), ms, here) { index -> npcByServerIndex(index)?.let(::alive) == true }
    }

    /** Whether an NPC attacks us right now; every one seen is remembered. */
    @JvmStatic
    fun isUnderAttack(): Boolean {
        val attackers = attackersInRange()
        if (attackers.isEmpty()) return false
        ledger.remember(attackers, System.currentTimeMillis(), runCatching { localPlayer.tile }.getOrNull())
        return true
    }

    /**
     * Whether what a hit breaks (lodestones, teleports that open a menu) may be used. [assumeNotAttacked] answers as
     * if the fight were over, for a what-if route never walked now; [assumeAttacked] as if it went on, for a caller
     * that knows the attacker swings between what [isUnderAttack] sees.
     */
    @JvmStatic
    @JvmOverloads
    fun hitBreakablesUsable(assumeNotAttacked: Boolean = false, assumeAttacked: Boolean = false): Boolean =
        !assumeAttacked && (assumeNotAttacked || !attackedRecently())

    private fun attackersInRange(): List<Int> = runCatching {
        val me = localPlayer
        val here = me.tile
        val hit = me.hits.isNotEmpty()
        npcs.values.mapNotNull { npc ->
            runCatching {
                val attacking = npc.interactingWith(me) && npc.hasOption("Attack") && alive(npc) && (hit || npc.isAnimating)
                npc.serverIndex.takeIf { attacking && withinTiles(npc.tile, here, ATTACKER_RANGE) }
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun alive(npc: NPC): Boolean = npc.maxHealth <= 0 || npc.currentHealth > 0
}

/** The attackers [AttackMemory] has seen, by server index: when, and where we stood. */
internal class AttackLedger {
    private class Seen(val at: Long, val tile: Tile?)

    private val seen = ConcurrentHashMap<Int, Seen>()

    fun remember(attackers: Collection<Int>, now: Long, here: Tile?) {
        val entry = Seen(now, here)
        attackers.forEach { seen[it] = entry }
    }

    fun seenWithin(now: Long, ms: Long, here: Tile?, alive: (Int) -> Boolean): Boolean {
        var any = false
        for ((index, entry) in seen) {
            if (now - entry.at >= FORGET_MS) {
                seen.remove(index, entry)
                continue
            }
            if (any || now - entry.at >= ms || !alive(index)) continue
            val at = entry.tile
            if (at == null || here == null || withinTiles(here, at, AttackMemory.MEMORY_TILES)) any = true
        }
        return any
    }

    private companion object {
        const val FORGET_MS = 60_000L
    }
}

private fun withinTiles(a: Tile, b: Tile, tiles: Int): Boolean =
    a.plane == b.plane && max(abs(a.x - b.x), abs(a.y - b.y)) <= tiles
