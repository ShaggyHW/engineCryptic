package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.player.Player
import com.projectx.script.api.ServerTick
import com.projectx.script.api.localPlayer
import com.projectx.traversal.local.ServerWalk
import com.projectx.script.api.walkTo as clickWalk
import com.projectx.util.nonProfiledGaussian
import kotlinx.coroutines.delay
import world.gregs.voidps.type.Tile
import java.util.concurrent.ThreadLocalRandom
import com.projectx.script.api.Area as WorldArea
import com.projectx.util.Area as FlatArea

/** Pacing constants and player helpers shared by the traversal nodes. */
internal object TraversalSupport {
    const val TICK_MS = 600L
    const val WALK_START_TIMEOUT_MS = 2_000L

    const val FUTURE_PATH_STEP_MIN = 10
    const val FUTURE_PATH_STEP_MAX = 20
    const val MINIMAP_WALK_PERC = 70
    const val WALK_PATH_CLICK_TIME = 600L

    /** How long a node waits for a clicked walk leg to end before it may click again. */
    const val WALK_LEG_TIMEOUT_MS = 8_000L

    /** No click or hop goes this long after a teleport landing: the game drops it. */
    const val LANDED_HOLD_MS = 1_200L

    /** How many following nodes a walk may aim through. */
    const val MAX_THROUGH_NODES = 4

    /** A longer jump between two observations is a teleport, as LocalNav's arrival gate has it. */
    const val TELEPORT_JUMP_TILES = 12

    private const val POLL_MS = 50L
    private const val ITEM_EFFECT_MAX_MS = 15_000L
    private const val ANIMATION_GAP_MS = 1_200L
    private const val TELEPORT_MAX_MS = 15_000L
    private const val WALK_CLICK_RANGE = 40

    fun randomInt(minInclusive: Int, maxInclusive: Int): Int =
        if (maxInclusive <= minInclusive) minInclusive else ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1)

    fun rollMinimap(): Boolean = randomInt(0, 99) < MINIMAP_WALK_PERC

    fun clickPacingJitter(): Long = clickGapMs(WALK_PATH_CLICK_TIME)

    /** A gap around [meanMs] with a standard deviation of a quarter of it, redrawn below a quarter of it. */
    fun clickGapMs(meanMs: Long): Long {
        val sd = (meanMs / 4).toInt()
        var gap: Long
        do {
            gap = nonProfiledGaussian(meanMs.toInt(), sd * sd).toLong()
        } while (gap < meanMs / 4)
        return gap
    }

    /** Milliseconds since the last server tick, -1 with no tick seen yet. */
    fun msIntoTick(): Long = ServerTick.millisIntoTick

    /** [msIntoTick] at the moment an action asked for now will be sent; past a tick when it goes in the next one. */
    fun msIntoTickAtSend(): Long = ServerTick.millisIntoTickAtSend()

    private fun playerOrNull(): Player? = runCatching { localPlayer }.getOrNull()

    /** Where the server has the player this tick, else the drawn tile; null with no player. */
    fun serverTileOrNull(): Tile? = runCatching { playerOrNull()?.let { it.serverTile ?: it.tile } }.getOrNull()

    /** Same plane and within [dist]: the distance alone reads 0 for the same x/y on another floor. */
    fun within(a: Tile?, b: Tile?, dist: Int): Boolean = a != null && b != null && a.plane == b.plane && a.getDistance(b) <= dist

    /** Plane-aware for the script area; the older flat area only knows x/y. */
    fun inside(area: WorldArea?, tile: Tile?): Boolean = area != null && tile != null && area.contains(tile)

    fun inside(area: FlatArea?, tile: Tile?): Boolean = area != null && tile != null && area.inside(tile)

    @Volatile
    private var lastWalkClick: Tile? = null

    @Volatile
    private var lastWalkClickAt = 0L

    /** A new node starting mid-leg must not click the same tile again: the last walk click any node sent. */
    fun noteWalkClick(tile: Tile) {
        lastWalkClick = tile
        lastWalkClickAt = System.currentTimeMillis()
    }

    /** Something other than a walk click (a surge, a dive) has replaced the walk queue. */
    fun forgetWalkClick() {
        lastWalkClick = null
        ServerWalk.shared.forget()
    }

    /** Wall-clock ms of the last walk click, whether or not a hop replaced it since; 0 before any. */
    fun lastWalkClickAt(): Long = lastWalkClickAt

    /** The walk click the player is on (none since the last hop), or null. */
    fun lastWalkClickOrNull(): Tile? = lastWalkClick

    fun walkClickSinceHop(): Boolean = lastWalkClick != null

    fun recentlyClicked(tile: Tile, withinMs: Long): Boolean {
        val last = lastWalkClick
        return last != null && last == tile && System.currentTimeMillis() - lastWalkClickAt <= withinMs
    }

    internal fun resetWalkClickForTests() {
        lastWalkClick = null
        lastWalkClickAt = 0L
    }

    /** True once the server has the player off [before]; the drawn tile may still be catching up from a hop. */
    fun serverMovedFrom(before: Tile?): Boolean {
        val tile = serverTileOrNull()
        return before != null && tile != null && tile != before
    }

    fun isMoving(): Boolean = runCatching { playerOrNull()?.isMoving == true }.getOrDefault(false)

    fun isAnimating(): Boolean = runCatching { playerOrNull()?.isAnimating == true }.getOrDefault(false)

    /** The local player's animation id, -1 with none or no player. */
    fun animationId(): Int = runCatching { playerOrNull()?.animationId ?: -1 }.getOrDefault(-1)

    /** One walk click at [tile] when it is within the client's click range of the drawn tile. */
    fun walkTo(tile: Tile, minimap: Boolean): Boolean {
        val drawn = runCatching { playerOrNull()?.tile }.getOrNull() ?: return false
        if (!drawn.withinDistance(tile, WALK_CLICK_RANGE) || !clickWalk(tile, minimap)) return false
        ServerWalk.shared.clicked(serverTileOrNull() ?: drawn, tile)
        return true
    }

    /** Polls [condition] every 50 ms until it holds (true) or [timeoutMs] passes (false). */
    suspend fun delayUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (condition()) return true
            val left = end - System.currentTimeMillis()
            if (left <= 0) return false
            delay(minOf(POLL_MS, left))
        }
    }

    /** Waits for a walk to start, then to stop; true when it started (or [abort] fired) inside the start window. */
    suspend fun awaitWalk(startTimeoutMs: Long, stopTimeoutMs: Long, abort: () -> Boolean): Boolean {
        val started = delayUntil(startTimeoutMs) { abort() || isMoving() }
        if (started) delayUntil(stopTimeoutMs) { abort() || !isMoving() }
        return started
    }

    /**
     * After an item click that may or may not teleport: until the player leaves [from], or an item that only animates
     * has stopped animating for 1.2 s; a teleport's cast and arrival animations sit either side of the move.
     */
    suspend fun awaitItemEffect(from: Tile?, done: () -> Boolean) {
        val arrived = { done() || serverMovedFrom(from) }
        if (!delayUntil(WALK_START_TIMEOUT_MS) { arrived() || isAnimating() }) return
        var stillSince = 0L
        delayUntil(ITEM_EFFECT_MAX_MS) {
            if (arrived()) return@delayUntil true
            val now = System.currentTimeMillis()
            if (isAnimating()) {
                stillSince = 0L
                return@delayUntil false
            }
            if (stillSince == 0L) stillSince = now
            now - stillSince >= ANIMATION_GAP_MS
        }
    }

    /** After a teleport click: waits for the cast animation, then for the player to leave [from]. */
    suspend fun awaitTeleport(from: Tile?, done: () -> Boolean) {
        val arrived = { done() || serverMovedFrom(from) }
        if (delayUntil(WALK_START_TIMEOUT_MS) { arrived() || isAnimating() }) delayUntil(TELEPORT_MAX_MS, arrived)
    }
}

/**
 * Where a node lands: a plane-aware script area, or the older flat one that only knows x/y. A flat one has no
 * [southWest], so what needs a tile on a plane (crossing, walking past) is off for it.
 */
internal class NodeDestination private constructor(private val area: WorldArea?, private val flat: FlatArea?) {
    fun contains(tile: Tile?): Boolean = if (area != null) TraversalSupport.inside(area, tile) else TraversalSupport.inside(flat, tile)

    val southWest: Tile? get() = area?.toRectangular()?.getBottomLeft()

    /** The bounding rectangle; a flat area's on plane 0, for what reads only x/y. */
    val rectangle: WorldArea.Rectangular
        get() = area?.toRectangular() ?: flat!!.let { WorldArea.Rectangular(Tile.of(it.minX.toInt(), it.minY.toInt(), 0), Tile.of(it.maxX.toInt(), it.maxY.toInt(), 0)) }

    val centreX: Int get() = rectangle.let { (it.getBottomLeft().x + it.getTopRight().x) / 2 }
    val centreY: Int get() = rectangle.let { (it.getBottomLeft().y + it.getTopRight().y) / 2 }

    override fun toString(): String = area?.toString() ?: flat.toString()

    companion object {
        fun of(area: WorldArea?): NodeDestination? = area?.let { NodeDestination(it, null) }
        fun of(area: FlatArea?): NodeDestination? = area?.let { NodeDestination(null, it) }
    }
}
