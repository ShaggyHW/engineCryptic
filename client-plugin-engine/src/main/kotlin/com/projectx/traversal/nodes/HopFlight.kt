package com.projectx.traversal.nodes

import com.projectx.quest.runtime.RecentChat
import com.projectx.traversal.hops.HopExplainer
import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.hops.HopRecorder
import com.projectx.traversal.local.LocalNav
import com.projectx.traversal.local.ServerWalk
import org.projectx.core.game.combat.Ability
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile

/**
 * One walker's client hop until it is seen to land: a hop moves the server tile [HeadingTrack.HOP_JUMP_TILES]+ in one
 * observation, walking 1-2. Settled (logged next to its prediction, and recorded when learning) by the walker's passes
 * or, should the walker never step again, by a watch thread. A same-tick pair's second hop rides along until the
 * landing says whether both went in one server update.
 *
 * The hop in the air is shared across walkers ([flying]): no walker casts or clicks around another's probe.
 */
internal class HopFlight(val owner: String, private val heading: HeadingTrack) {
    @Volatile
    var kind: String? = null
        private set
    private var from: Tile? = null
    private var last: Tile? = null

    var predicted: Tile? = null
        private set
    var at = 0L
        private set
    private var spentSeenAt = 0L

    @Volatile
    var record: HopRecorder.Pending? = null
        private set

    @Volatile
    var probe = false
        private set
    private var fireTiles: List<Tile> = emptyList()
    private var fireLandings: List<Tile?> = emptyList()
    var tickMs = -1L

    @Volatile
    private var boosted = false

    /** The pending surge is to be followed by a dive from its landing: no walk click until it lands. */
    @Volatile
    var chainAfterSurge = false

    /** The pending dive is to be followed by a surge: no walk click until it lands. */
    @Volatile
    var chainAfterDive = false

    @Volatile
    var pairSecond: String? = null
        private set
    private var pairFrom: Tile? = null
    private var pairAim: Tile? = null
    private var pairLanding: Tile? = null
    private var pairDirection: Direction? = null
    private var pairTickMs = -1L

    private var refusal: String? = null
    private var nextRefusalCheck = 0L

    val pending: Boolean get() = kind != null

    /** A hop of [kind] was just cast from [castFrom]; the walker has already marked the ability spent. */
    fun begin(kind: String, castFrom: Tile?, predicted: Tile?, direction: Direction?, aim: Tile?, probeEdge: Long) {
        from = castFrom
        fireTiles = emptyList()
        fireLandings = emptyList()
        tickMs = TraversalSupport.msIntoTick()
        boosted = AccelerationPlanner.active()
        ServerWalk.shared.hopCast()
        val rec = HopRecorder.begin(kind, castFrom, direction, aim, probeEdge)
        record = rec
        // A dive is two clicks: the walk may have moved the player since the decision, so its landing is watched from here.
        val here = TraversalSupport.serverTileOrNull()
        val walked = here != null && castFrom != null && here.plane == castFrom.plane && here != castFrom &&
            here.getLongestDelta(castFrom) <= 2 * ServerWalk.TICK_STEPS
        if (walked) {
            println("[$owner] client $kind: the player walked from $castFrom to $here before the cast went out; its landing is watched from there")
            rec?.walked(here)
        }
        ArrivalWatch.hopSent(kind)
        synchronized(FLIGHT) {
            probe = probeEdge >= 0
            this.kind = kind
            last = if (walked) here else castFrom
            this.predicted = predicted
            at = System.currentTimeMillis()
            spentSeenAt = 0L
            flying = this
        }
        watchLanding()
    }

    fun noteFires(tiles: List<Tile>, landings: List<Tile?>) {
        fireTiles = tiles
        fireLandings = landings
    }

    /** The combo sent [second] with the hop about to begin: from [mid] facing [direction] (surge) or toward [aim] (dive), landing on [end]. */
    fun notePair(second: String, mid: Tile?, direction: Direction?, aim: Tile?, end: Tile?, secondAt: Long) {
        synchronized(FLIGHT) {
            pairSecond = second
            pairFrom = mid
            pairDirection = direction
            pairAim = aim
            pairLanding = end
            pairTickMs = secondAt
        }
    }

    /** True once the pending hop's jump has been seen. */
    fun landed(server: Tile?): Boolean = synchronized(FLIGHT) { landedLocked(server) }

    /** How long the pending hop gets to land: shorter when cast during the burst. */
    fun giveUpMs(): Long = if (boosted) BOOSTED_LANDING_MS else LANDING_LOG_MS

    private fun landedLocked(server: Tile?): Boolean {
        if (kind == null || server == null) return false
        val before = last
        // A probe holds the walk with the player standing still: any move is the hop, even a short one into its wall.
        val jump = if (probe) 1 else HeadingTrack.HOP_JUMP_TILES
        if (before != null && (server.plane != before.plane || server.getLongestDelta(before) >= jump)) return true
        last = server
        record?.observe(server)
        return false
    }

    /** Settles the pending hop when [server] shows its landing, its refusal, or that it never went. */
    fun settle(server: Tile?) {
        if (kind == null || server == null) return
        synchronized(FLIGHT) {
            settleLocked(server)
            if (kind == null && pairSecond != null) {
                println("[$owner] the $pairSecond sent with it is not watched further")
                clearPair()
            }
            if (kind == null && flying === this) flying = null
        }
    }

    private fun settleLocked(server: Tile) {
        val pendingKind = kind ?: return
        if (pairSecond != null && landedLocked(server)) {
            settlePair(server)
            return
        }
        val now = System.currentTimeMillis()
        when {
            landedLocked(server) -> {
                val start = from
                var hopDirection: Direction? = null
                if (start != null && start != server) {
                    hopDirection = HopModel.facing(server.x - start.x, server.y - start.y)
                    heading.hopLanded(start, server, hopDirection)
                }
                ServerWalk.shared.hopSettled(server, hopDirection)
                hopLandedAt = now
                record?.finish(server, HopExplainer.LANDED)
                record = null
                probe = false
                if (chainAfterDive || chainAfterSurge) chainUntil = now + CHAIN_WAIT_MS
                if (chainAfterSurge) diveChainUntil = chainUntil
                chainAfterDive = false
                chainAfterSurge = false
                val p = predicted
                val off = if (p != null && server.plane == p.plane) "${server.getLongestDelta(p)} tiles off" else "other plane"
                println("[$owner] client $pendingKind predicted $p, landed $server ($off${firedFrom(server)})")
                kind = null
            }
            refusedEarly(pendingKind) -> {
                chainAfterDive = false
                chainAfterSurge = false
                ServerWalk.shared.hopSettled(null, null)
                println("[$owner] client $pendingKind predicted $predicted: refused (\"$refusal\") at $server")
                if (pendingKind == HopExplainer.DIVE) record?.finish(null, HopExplainer.REFUSED, refusal) else record?.finish(from, HopExplainer.LANDED, refusal)
                record = null
                probe = false
                // The spent flag stays: the walker clears it once the readout shows the cooldown, or after it expires.
                kind = null
            }
            now - at >= MISS_CHECK_MS && castNeverStarted(pendingKind) -> {
                chainAfterDive = false
                chainAfterSurge = false
                ServerWalk.shared.hopSettled(null, null)
                println("[$owner] client $pendingKind predicted $predicted: not cast (no hop and no cooldown within $MISS_CHECK_MS ms, at $server)")
                record?.finish(null, HopExplainer.NO_CAST)
                record = null
                probe = false
                if (pendingKind == HopExplainer.SURGE) HopCharges.surgeSpent = false else HopCharges.diveSpent = false
                missedFrom = server
                missedAt = now
                kind = null
            }
            pendingKind == HopExplainer.DIVE && shortDiveSettled(pendingKind) && RecentChat.findSince(DIVE_REFUSED_MSG, at) == null -> {
                chainAfterDive = false
                chainAfterSurge = false
                println("[$owner] client dive predicted $predicted: cooldown spent $SHORT_DIVE_SETTLE_MS ms with no hop seen - a short dive, settled at $server")
                ServerWalk.shared.hopSettled(server, null)
                hopLandedAt = now
                record?.finish(server, HopExplainer.LANDED)
                record = null
                probe = false
                kind = null
            }
            now - at >= giveUpMs() -> {
                chainAfterDive = false
                chainAfterSurge = false
                ServerWalk.shared.hopSettled(null, null)
                println("[$owner] client $pendingKind predicted $predicted: no hop seen within ${giveUpMs()} ms (at $server)")
                val rec = record
                if (rec != null) {
                    val refused = RecentChat.findSince(if (pendingKind == HopExplainer.DIVE) DIVE_REFUSED_MSG else SURGE_REFUSED_MSG, at)
                    val spent = if (pendingKind == HopExplainer.SURGE) !Ability.SURGE.offCdIgnoreGCD else !diveReadoutReady()
                    when {
                        refused == null && spent -> rec.finish(server, HopExplainer.LANDED)
                        refused == null -> rec.finish(null, HopExplainer.NO_CAST)
                        pendingKind == HopExplainer.DIVE -> rec.finish(null, HopExplainer.REFUSED, refused)
                        else -> rec.finish(from, HopExplainer.LANDED, refused)
                    }
                    record = null
                }
                probe = false
                // No hop, and the readout never left "ready": the cast did not happen, so the ability is not spent.
                if (pendingKind == HopExplainer.SURGE && Ability.SURGE.offCdIgnoreGCD) HopCharges.surgeSpent = false
                if (pendingKind == HopExplainer.DIVE && diveReadoutReady()) HopCharges.diveSpent = false
                kind = null
            }
        }
    }

    /**
     * The pending hop of a same-tick pair moved the player to [server]. Nearer the pair's end than the first's landing:
     * both went in one server update and the tile between was never seen. Else the first landed on its own tick and the
     * second becomes the pending hop from where it really landed.
     */
    private fun settlePair(server: Tile) {
        val first = kind ?: return
        val second = pairSecond ?: return
        val mid = pairFrom
        val end = pairLanding ?: server
        val now = System.currentTimeMillis()
        val oneTick = server.plane == end.plane &&
            (mid == null || server.plane != mid.plane || server.getLongestDelta(end) < server.getLongestDelta(mid))
        if (oneTick) {
            val direction = if (mid == null || mid == server) null else HopModel.facing(server.x - mid.x, server.y - mid.y)
            if (direction != null && mid != null) heading.hopLanded(mid, server, direction)
            ServerWalk.shared.hopSettled(server, direction)
            hopLandedAt = now
            val recorded = recordOneTickPair(first, second, mid, pairLanding, server)
            record = null
            probe = false
            chainAfterDive = false
            chainAfterSurge = false
            val key = ScheduleLearning.comboKey(first, mid, pairLanding)
            ScheduleLearning.comboOutcome(key, server.getLongestDelta(end) <= ScheduleLearning.COMBO_OK_TILES)
            val rec = ScheduleLearning.comboRecord(key)
            println(
                "[$owner] client $first + $second in one tick: from $from via $mid (predicted), predicted $end, landed $server " +
                    "(${server.getLongestDelta(end)} tiles off; sent $tickMs and $pairTickMs ms into the tick; animation " +
                    "${TraversalSupport.animationId()}; $recorded${if (rec.isEmpty()) "" else "; this combo landed right $rec"})",
            )
            clearPair()
            kind = null
            return
        }
        val start = from
        var hopDirection: Direction? = null
        if (start != null && start != server) {
            hopDirection = HopModel.facing(server.x - start.x, server.y - start.y)
            heading.hopLanded(start, server, hopDirection)
        }
        ServerWalk.shared.hopSettled(server, hopDirection)
        hopLandedAt = now
        record?.finish(server, HopExplainer.LANDED)
        record = null
        probe = false
        println(
            "[$owner] client $first predicted $predicted, landed $server on its own tick (sent $tickMs and $pairTickMs ms into the tick; " +
                "animation ${TraversalSupport.animationId()}); the $second sent with it goes from here",
        )
        val d = pairDirection
        val aim = pairAim
        val surge = second == HopExplainer.SURGE
        val next = if (surge) MovePrediction.surgeLanding(server, d) else aim?.let { MovePrediction.diveLanding(server, it) }
        clearPair()
        kind = null
        begin(second, server, next ?: server, if (surge) d else null, if (surge) null else aim, -1)
    }

    /**
     * A one-tick pair's two hops, learned where the landing says what happened between: on the predicted end both went
     * as planned; a Dive -> Surge ending elsewhere on the surge's line went shorter or further. Otherwise neither.
     */
    private fun recordOneTickPair(first: String, second: String, mid: Tile?, end: Tile?, server: Tile): String {
        val firstRec = record ?: return "not recorded: this client does not learn hops"
        if (mid == null || end == null) return "not recorded: no tile between"
        val exact = server == end
        val surge = second == HopExplainer.SURGE
        val onLine = !exact && surge && HopRules.onSurgeLine(mid, pairDirection, server)
        if (!exact && !onLine) return "not recorded: the landing does not say where the $first ended"
        firstRec.finish(mid, HopExplainer.LANDED, "one-tick combo: landing inferred from the $second's")
        HopRecorder.begin(second, mid, if (surge) pairDirection else null, if (surge) null else pairAim, -1)
            ?.finish(server, HopExplainer.LANDED, "one-tick combo: from the $first's inferred landing")
        return "both recorded (" + (if (exact) "landed as predicted" else "on the surge's line from the dive's landing") + ")"
    }

    private fun clearPair() {
        pairSecond = null
        pairFrom = null
        pairAim = null
        pairLanding = null
        pairDirection = null
        pairTickMs = -1
    }

    /** How far into the tick the hop was cast, and which noted fire tile its landing fits. */
    private fun firedFrom(landed: Tile): String {
        val cast = if (tickMs >= 0) "cast $tickMs ms into the tick" else "tick unknown"
        if (fireTiles.isEmpty()) return "; $cast"
        val ks = fireLandings.indices.filter { landed == fireLandings[it] }
        if (ks.isNotEmpty()) return "; $cast, fired from " + (if (ks.size == 1) "+${ks[0]}" else "one of +$ks") + " on the walk"
        // A walk click that reached the server in the hop's tick walks on from the landing in it.
        val click = ServerWalk.shared.click
        for (k in fireLandings.indices) {
            val l = fireLandings[k]
            val on = if (l == null || click == null) null else LocalNav.serverPath(l, click)
            if (on != null) {
                for (n in 1..minOf(ServerWalk.TICK_STEPS, on.size - 1)) {
                    if (landed == on[n]) return "; $cast, fired from +$k to $l, then walked $n toward $click"
                }
            }
        }
        return "; $cast, from none of $fireTiles"
    }

    /** The pending dive's cooldown has shown spent for [SHORT_DIVE_SETTLE_MS] with no hop seen: it went short. */
    private fun shortDiveSettled(pendingKind: String): Boolean {
        if (castNeverStarted(pendingKind)) {
            spentSeenAt = 0L
            return false
        }
        val now = System.currentTimeMillis()
        if (spentSeenAt == 0L) spentSeenAt = now
        return now - spentSeenAt >= SHORT_DIVE_SETTLE_MS
    }

    /** The readout still says "ready": the cast never went. Unknown (false) for a surge with a second charge. */
    private fun castNeverStarted(pendingKind: String): Boolean {
        if (pendingKind == HopExplainer.SURGE) return !HopCharges.doubleSurge() && Ability.SURGE.offCdIgnoreGCD
        return diveReadoutReady()
    }

    private fun diveReadoutReady(): Boolean = Ability.DIVE.offCdIgnoreGCD && Ability.BLADED_DIVE.offCdIgnoreGCD

    private fun refusedEarly(pendingKind: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < nextRefusalCheck) return false
        nextRefusalCheck = now + REFUSAL_CHECK_MS
        refusal = RecentChat.findSince(if (pendingKind == HopExplainer.DIVE) DIVE_REFUSED_MSG else SURGE_REFUSED_MSG, at)
        return refusal != null
    }

    /**
     * Watches the hop off the script thread until it lands or times out, so it is settled even when its walker is never
     * stepped again: the cast was the last thing before its node was reached, or the traversal stopped.
     */
    private fun watchLanding() {
        Thread.ofVirtual().name("hop-watch").start {
            val giveUp = System.currentTimeMillis() + 3 * LANDING_LOG_MS
            while (kind != null && System.currentTimeMillis() < giveUp) {
                TraversalSupport.serverTileOrNull()?.let { settle(it) }
                try {
                    Thread.sleep(WATCH_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            synchronized(FLIGHT) {
                val stale = kind
                if (stale != null) {
                    ServerWalk.shared.hopSettled(null, null)
                    println("[$owner] client $stale: no position to settle it on - dropped")
                    record?.finish(null, HopExplainer.NO_CAST, "no position")
                    record = null
                    probe = false
                    chainAfterDive = false
                    chainAfterSurge = false
                    clearPair()
                    kind = null
                }
                if (flying === this) flying = null
            }
        }
    }

    companion object {
        /** How long a client hop gets to land before its landing is logged as it stands. */
        const val LANDING_LOG_MS = 3_000L

        /** A hop not seen landing this long after its cast, its readout never off "ready", was not cast. */
        const val MISS_CHECK_MS = 1_500L

        /** During the burst a hop not seen landing this long after its cast is given up on. */
        const val BOOSTED_LANDING_MS = 1_500L

        /** A dive whose cooldown has shown this long with no jump went short into a wall: settled where the player stands. */
        const val SHORT_DIVE_SETTLE_MS = 1_200L

        /** How long the player stands on a dive's landing for its chained surge before walking on. */
        const val CHAIN_WAIT_MS = 1_200L
        const val REFUSAL_CHECK_MS = 150L
        private const val WATCH_POLL_MS = 50L

        const val DIVE_REFUSED_MSG = "You can't dive through"
        const val SURGE_REFUSED_MSG = "Destination unreachable"

        val FLIGHT = Any()

        /** The flight in the air, across walkers; guarded by [FLIGHT]. */
        @Volatile
        var flying: HopFlight? = null

        /** When a hop (any walker's) was last seen landing; 0 before the first. */
        @Volatile
        var hopLandedAt = 0L

        /** A cast that did not happen is not tried again from the same tile for a while. */
        @Volatile
        var missedFrom: Tile? = null

        @Volatile
        var missedAt = 0L

        /** Until then a chained surge may go, with no walk click meanwhile; 0 when none is waiting. */
        @Volatile
        var chainUntil = 0L

        /** Until then the dive chained to a surge may go from its landing; 0 when none is waiting. */
        @Volatile
        var diveChainUntil = 0L
    }
}
