package com.projectx.script.api

import com.projectx.game.magic.LodestoneSpells
import com.projectx.script.Script
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/** How a [lodestoneTeleport] ended. */
enum class LodestoneResult {
    /** Landed on the lodestone and the arrival animation finished. */
    LANDED,

    /** The network could not be opened, or no cast started after the click. */
    NOT_STARTED,

    /** The cast stopped without moving us: a hit, or a walk click, cancels it. */
    INTERRUPTED,

    /** The stop signal held before the landing. */
    STOPPED,

    /** Still casting, or stuck, after 30 s. */
    TIMED_OUT,
}

private const val ARRIVAL_RADIUS = 3
private const val JUMP_TILES = 10
private const val POLL_MILLIS = 50
private const val OPEN_TIMEOUT_MS = 5_000L
private const val CLOSE_TIMEOUT_MS = 5_000L
private const val START_TIMEOUT_MS = 6_000L
private const val LANDING_TIMEOUT_MS = 30_000L
private const val ARRIVAL_START_MS = 3_000L
private const val ARRIVAL_END_MS = 10_000L
private const val STEP_OFF_MS = 5_000L

// The cast animation plays unbroken until the landing and the drawn tile can trail the cast's end by a tick, so two
// still ticks without moving mean a hit cancelled it.
private const val INTERRUPT_MS = 1_200L

// After a book cast the client dropped, the network is used for this long rather than looping on the book.
private const val BOOK_RETRY_MS = 60_000L

@Volatile
private var bookLodestonesBlockedUntil = 0L

/**
 * Teleports to [lodestone] and says how it ended. Casts from the Magic ability book when it lists the lodestone spells,
 * else through the lodestone network; then waits for the cast to start and to land within 3 tiles of the lodestone,
 * and for the arrival animation to finish. Gives up early when the cast animation has stopped without the player
 * moving for two ticks, or when [stop] holds.
 */
@JvmSynthetic
suspend fun Script.lodestoneTeleport(lodestone: Lodestone, stop: () -> Boolean = { stopped }): LodestoneResult {
    if (stop()) return LodestoneResult.STOPPED
    var from = localPlayer.tile
    val fromBook = !isLodestoneUiOpen && System.currentTimeMillis() >= bookLodestonesBlockedUntil && LodestoneSpells.cast(lodestone)
    if (fromBook) {
        println("Casting lodestone from the ability book: ${lodestone.name}")
    } else {
        if (!isLodestoneUiOpen && openLodestoneMap()) {
            delayUntil(OPEN_TIMEOUT_MS, POLL_MILLIS) { stop() || lodestoneClickable(lodestone) }
        }
        if (stop()) return LodestoneResult.STOPPED
        if (!isLodestoneUiOpen) return LodestoneResult.NOT_STARTED
        from = localPlayer.tile
        interactComponent(1, LODESTONE_MAP_INTERFACE, lodestone.id)
        waitThenDelayUntil(600, CLOSE_TIMEOUT_MS, POLL_MILLIS) { !isLodestoneUiOpen }
    }
    val start = from
    delayUntil(START_TIMEOUT_MS, POLL_MILLIS) { stop() || localPlayer.isAnimating || jumped(start) }
    if (stop()) return LodestoneResult.STOPPED
    if (!localPlayer.isAnimating && !jumped(start)) {
        println("Lodestone teleport to ${lodestone.name} did not start")
        if (fromBook) bookLodestonesBlockedUntil = System.currentTimeMillis() + BOOK_RETRY_MS
        return LodestoneResult.NOT_STARTED
    }
    var stillSince = 0L
    var ended = false
    delayUntil(LANDING_TIMEOUT_MS, POLL_MILLIS) {
        ended = when {
            stop() -> true
            landedAt(lodestone) && !localPlayer.isAnimating -> true
            localPlayer.isAnimating || jumped(start) -> {
                stillSince = 0L
                false
            }
            else -> {
                val now = System.currentTimeMillis()
                if (stillSince == 0L) stillSince = now
                now - stillSince >= INTERRUPT_MS
            }
        }
        ended
    }
    if (landedAt(lodestone) && !localPlayer.isAnimating) {
        awaitLodestoneArrival(stop)
        if (!stop()) waitUntilNotAniMoving(STEP_OFF_MS)
        return LodestoneResult.LANDED
    }
    if (stop()) return LodestoneResult.STOPPED
    if (ended) {
        println("Lodestone teleport to ${lodestone.name} interrupted at ${localPlayer.tile}")
        return LodestoneResult.INTERRUPTED
    }
    return LodestoneResult.TIMED_OUT
}

private suspend fun Script.awaitLodestoneArrival(stop: () -> Boolean) {
    delayUntil(ARRIVAL_START_MS, POLL_MILLIS) { stop() || localPlayer.isAnimating }
    if (localPlayer.isAnimating) delayUntil(ARRIVAL_END_MS, POLL_MILLIS) { stop() || !localPlayer.isAnimating }
}

private fun landedAt(lodestone: Lodestone): Boolean = localPlayer.tile.getDistance(lodestone.tile) <= ARRIVAL_RADIUS

private fun jumped(from: Tile): Boolean {
    val now = localPlayer.tile
    return max(abs(now.x - from.x), abs(now.y - from.y)) > JUMP_TILES
}

private fun lodestoneClickable(lodestone: Lodestone): Boolean =
    interfaces.getComponentRaw(LODESTONE_MAP_INTERFACE, lodestone.id)?.let { it.onScreen && it.isOpEnabled(1) } == true
