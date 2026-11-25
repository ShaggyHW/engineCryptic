package com.projectx.traversal.nodes

import com.projectx.traversal.TraversalNode
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile

/**
 * A teleport, boat or charter landing, watched until the player first moves off its tile. A landing holds the player a
 * while: a walk or hop sent meanwhile is dropped. How long, per what sent the player ([key]), is learned
 * ([ScheduleLearning.lockMs]) once a walk clicked at least [DROPPED_CLICK_MS] before the move shows they were held.
 *
 * It also times the three gates that hold the first hop after an arrival (the map, the landing hold, the rest) and
 * logs which released last.
 */
internal object ArrivalWatch {
    /** A walk click that has not moved the player in this long was dropped: a free player moves on the next tick. */
    const val DROPPED_CLICK_MS = 900L

    /** A landing watched longer than this is given up (a dialog, a fight). */
    const val WATCH_MAX_MS = 15_000L

    /** Walker passes further apart than this cannot tell when the player moved off a landing. */
    const val PASS_GAP_MS = 1_000L

    /** Arrivals watched longer than this without a hop are logged as such and dropped. */
    const val GATE_WATCH_MS = 20_000L

    private const val MAX_KEY_NODES = 8
    private const val TELEPORT_NODE_SLACK_MS = 150L
    private const val OTHER_GATE_MS = 700L

    private var arrivalAt = 0L
    private var arrivalSeen = 0L
    private var arrivalClickAt = 0L
    private var arrivalReclickAt = 0L
    private var arrivalPassAt = 0L
    private var arrivalTile: Tile? = null
    private var arrivalKey: String? = null
    private var arrivalClicks = 0

    private var gateArrival = 0L
    private var gateHopsAt = 0L
    private var gateLiveAt = 0L
    private var gateHoldAt = 0L
    private var gateStatic = false
    private var gateLogged = false
    private var gateKey = "teleport"

    /**
     * Feeds the watch with this pass's tile; true when the last walk click was dropped (sent [DROPPED_CLICK_MS] ago with
     * the player still on the landing), once per such click, so the walker clicks again.
     */
    @Synchronized
    fun watch(node: TraversalNode?, server: Tile?): Boolean {
        val t = LocalNav.lastTeleportAt
        val now = System.currentTimeMillis()
        if (t != 0L && t != arrivalSeen) {
            arrivalSeen = t
            arrivalAt = t
            arrivalTile = LocalNav.lastTeleportTile
            arrivalKey = key(node)
            arrivalClickAt = 0
            arrivalReclickAt = 0
            arrivalPassAt = 0
            arrivalClicks = 0
        }
        val tile = arrivalTile
        if (arrivalAt == 0L || server == null || tile == null) return false
        val lastPass = arrivalPassAt
        arrivalPassAt = now
        if (lastPass != 0L && now - lastPass > PASS_GAP_MS) {
            arrivalAt = 0
            return false
        }
        val clicked = TraversalSupport.lastWalkClickAt()
        if (clicked > arrivalAt && clicked != arrivalReclickAt) {
            if (arrivalClickAt == 0L) arrivalClickAt = clicked
            arrivalReclickAt = clicked
            arrivalClicks++
        }
        if (server != tile) {
            val held = now - arrivalAt
            if (lastPass != 0L && arrivalClickAt != 0L && now - arrivalClickAt >= DROPPED_CLICK_MS) {
                val key = arrivalKey ?: "teleport"
                val was = ScheduleLearning.lockMs(key, TraversalSupport.LANDED_HOLD_MS)
                ScheduleLearning.lock(key, held)
                println(
                    "[hops] $key held the player $held ms after landing (first walk click after ${arrivalClickAt - arrivalAt} ms, " +
                        "$arrivalClicks sent); estimate $was -> ${ScheduleLearning.lockMs(key, TraversalSupport.LANDED_HOLD_MS)} ms",
                )
            }
            arrivalAt = 0
            return false
        }
        if (now - arrivalAt > WATCH_MAX_MS) {
            arrivalAt = 0
            return false
        }
        if (arrivalReclickAt != 0L && now - arrivalReclickAt >= DROPPED_CLICK_MS) {
            arrivalReclickAt = now
            return true
        }
        return false
    }

    /**
     * Why no hop goes yet after a landing, else null: within the landing's learned hold (at least
     * [TraversalSupport.LANDED_HOLD_MS]) while the player has not moved off it, or the first walk clicked since has not
     * moved the player in [DROPPED_CLICK_MS].
     */
    @Synchronized
    fun hold(now: Long): String? {
        val since = now - LocalNav.lastTeleportAt
        if (since < TraversalSupport.LANDED_HOLD_MS) return "landed from a teleport $since ms ago"
        if (arrivalAt == 0L) return null
        val key = arrivalKey ?: "teleport"
        val hold = ScheduleLearning.lockMs(key, TraversalSupport.LANDED_HOLD_MS)
        if (since < hold) return "landed $since ms ago: $key holds the player ~$hold ms"
        if (arrivalClickAt != 0L && now - arrivalClickAt >= DROPPED_CLICK_MS) {
            return "landed $since ms ago: the walk clicked ${now - arrivalClickAt} ms ago has not moved the player ($key holds it longer than $hold ms)"
        }
        return null
    }

    /** Feeds the gate watch with this pass. */
    @Synchronized
    fun watchGates(server: Tile?) {
        val t = LocalNav.lastTeleportAt
        val now = System.currentTimeMillis()
        if (t == 0L || server == null) return
        if (t != gateArrival) {
            gateArrival = t
            gateHopsAt = 0
            gateLiveAt = 0
            gateHoldAt = 0
            gateStatic = false
            gateLogged = false
            gateKey = arrivalKey ?: "teleport"
        }
        if (gateLogged) return
        if (now - t > GATE_WATCH_MS) {
            gateLogged = true
            println(
                "[arrival] $gateKey: no hop within $GATE_WATCH_MS ms; map usable ${rel(gateHopsAt)}${if (gateStatic) " (static)" else ""}, " +
                    "live ${rel(gateLiveAt)}, landing hold released ${rel(gateHoldAt)}",
            )
            return
        }
        if (gateHopsAt == 0L && LocalNav.usableForHops(server)) {
            gateHopsAt = now
            gateStatic = !LocalNav.trustedLive(server)
        }
        if (gateLiveAt == 0L && LocalNav.trustedLive(server)) gateLiveAt = now
        if (gateHoldAt == 0L && hold(now) == null) gateHoldAt = now
    }

    /** The first hop after an arrival went out: log the gates. */
    @Synchronized
    fun hopSent(kind: String) {
        if (gateArrival == 0L || gateLogged) return
        val now = System.currentTimeMillis()
        if (now - gateArrival > GATE_WATCH_MS) return
        gateLogged = true
        val map = if (gateHopsAt != 0L) gateHopsAt else now
        val held = if (gateHoldAt != 0L) gateHoldAt else now
        var last = if (map >= held) (if (map == gateArrival) "none" else "map") else "landing hold"
        // A teleport node that waits out its own arrival hands over late: everything releases on the walker's first pass.
        val seen = LocalNav.lastTeleportSeenAt
        if (seen > gateArrival && maxOf(map, held) <= seen + TELEPORT_NODE_SLACK_MS) {
            last = "the teleport node (it handed over +${seen - gateArrival} ms after the landing)"
        }
        if (now - maxOf(map, held) > OTHER_GATE_MS) last = "other (cooldown, route, pacing): ${now - maxOf(map, held)} ms after the map and the hold"
        println(
            "[arrival] $gateKey: first hop ($kind) +${now - gateArrival} ms; map usable ${rel(gateHopsAt)}${if (gateStatic) " (static)" else ""}, " +
                "live ${rel(gateLiveAt)}, landing hold released ${rel(gateHoldAt)}; last gate: $last",
        )
    }

    private fun rel(at: Long): String = if (at == 0L) "never" else "+${at - gateArrival} ms"

    /**
     * What sent the player to this walk: the first interaction node after the walk before it ("NpcNode:Charter"), by
     * [Interactions.key]; "teleport" when none is in this node list.
     */
    fun key(node: TraversalNode?): String {
        var key: String? = null
        var k = node?.prev
        var n = 0
        while (k != null && n < MAX_KEY_NODES) {
            if (k is ChebychevNode || k is PathNode || k is TileExactNode) break
            key = Interactions.key(k)
            k = k.prev
            n++
        }
        return key ?: "teleport"
    }

    internal fun resetForTests() {
        arrivalAt = 0
        arrivalSeen = 0
        gateArrival = 0
        gateLogged = false
    }
}
