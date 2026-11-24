package com.projectx.traversal.nodes

import com.projectx.game.collision.CollisionMap
import com.projectx.pathfinder.WalkMap
import com.projectx.script.Script
import com.projectx.script.api.AttackMemory
import com.projectx.script.api.actionbarAbilities
import com.projectx.script.api.dive
import com.projectx.script.api.diveThenSurge
import com.projectx.script.api.localPlayer
import com.projectx.script.api.surge
import com.projectx.script.api.surgeThenDive
import com.projectx.traversal.EarlyStart
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.TraversalOutcome
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.WalkOutlook
import com.projectx.traversal.hops.HopEdges
import com.projectx.traversal.hops.HopExplainer
import com.projectx.traversal.hops.HopExplorer
import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.local.LocalNav
import com.projectx.traversal.local.ServerWalk
import com.projectx.util.nonProfiledGaussian
import kotlinx.coroutines.delay
import org.projectx.core.game.combat.Ability
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.util.Objects
import kotlin.math.max
import kotlin.math.min

/**
 * The live-map walking step shared by [ChebychevNode] and [PathNode]; the node calls [step] from `process` and acts on
 * the answer.
 *
 * - Clicks are the furthest route tile within the look-ahead whose live walking distance matches its route distance
 *   ([RoutePicks.choose]): never a blocked or U-turn tile, re-clicked off the server tile, never the same tile twice,
 *   aimed on through open doors ([extend]).
 * - A ready [EarlyStart] next node takes over without walking up to its tile.
 * - Stuck with nothing reachable ahead: a closed door just ahead takes over, else the server's own pathing tries once,
 *   else the route is re-planned.
 * - Surge and Dive are placed by the client where the live map shows a hop toward the walk's end ([castAlongRoute]),
 *   kept for a better spot by [HopSchedule], paired into one-tick combos, and probes cast on a learning client.
 * - A Powerburst of acceleration is drunk where [AccelerationPlanner] says the burst pays.
 *
 * Untrusted map: [Step.FALLBACK], and the node walks its keypoints as it did without one.
 */
internal class RouteWalker(private val owner: String, abilities: ClientAbilities? = ClientAbilities.NONE) {
    enum class Step { CONTINUE, ADVANCE, FALLBACK }

    private enum class Cast { SENT, FAILED, HELD }

    private val abilities = abilities ?: ClientAbilities.NONE
    private val heading = HeadingTrack()
    private val flight = HopFlight(owner, heading)
    private val stuck = StuckDetector()

    /** The walk node's look-ahead from the last [step]: the clicks the burst replay walks to. */
    private var stepLimit = RoutePicks.MAX_CLICK
    private var nextAbilityCheck = 0L
    private var progress = 0
    private var lastClick: Tile? = null
    private var lastClickAt = 0L
    private var nextClick = 0L
    private var nextEarlyCheck = 0L
    private var lastThrough: String? = null
    private var farHoldLogged: Tile? = null
    private var farHoldSince = 0L
    private var farHoldLastPass = 0L
    private var farHoldAt: Tile? = null
    private var hopHoldUntil = 0L
    private var nextBurstCheck = 0L
    private var nextBurstLog = 0L
    private var lastBurstWhy = ""
    private var serverTriedFrom: Tile? = null
    private var nextHoldLog = 0L
    private var lastWalkWhy: String? = null
    private var nextWalkWhyLog = 0L
    private var lastCastWhy = "never checked"
    private var lastCastWhyAt = 0L
    private var hopsIdleSince = 0L
    private var nextIdleCheck = 0L
    private var nextIdleLog = 0L
    private var plannedReach = 0
    private var restepping = false
    private var landingChecked = 0L
    private var probeWalked = false
    private var nextPrime = 0L
    private var pairHeldAt = 0L
    private var pairTickHeld = false

    private var seenTile: Tile? = null
    private var seenAt = 0L
    private var jumpTile: Tile? = null
    private var jumpSince = 0L

    private var stepNode: TraversalNode? = null
    private var routeCut = false
    private var steppedBefore = false

    private var jointGo = false
    private var jointLogged: Tile? = null
    private var jointNextLog = 0L
    private var combo0: HopSchedule.ComboAt? = null
    private var comboAbs: Array<HopSchedule.ComboAt?>? = null
    private var comboDone = BooleanArray(0)
    private var comboWide = BooleanArray(0)
    private var comboCacheKey = 0

    private var comboRef = -1
    private val comboBonusCache = HashMap<Tile, Int>()

    private var surgeSpots: HopSchedule.Spots? = null
    private var diveSpots: HopSchedule.Spots? = null
    private var surgeProbeMark: BooleanArray? = null
    private var diveProbeMark: BooleanArray? = null
    private var surgeSpotsKey = -1
    private var diveSpotsKey = -1
    private var surgeSpotsProgress: RouteProgress? = null
    private var diveSpotsProgress: RouteProgress? = null
    private var lastPlanSpots: HopSchedule.Spots? = null
    private var lastPlanProbe: BooleanArray? = null
    private val surgeProbeAt = HashMap<Int, Int>()
    private val diveProbeAt = HashMap<Int, Int>()
    private var probeAtKey = 0
    private val legSpotsCache = HashMap<String, HopSchedule.Spots>()
    private var tickLogged: String? = null
    private var diveTickOn: (() -> Int)? = null
    private var missingLogged: Tile? = null

    private var lineUpDirection: Direction? = null
    private var lineUpTo: Tile? = null
    private var lineUpUntil = 0L

    private var progressCache: RouteProgress? = null
    private var progressKeySize = -1
    private var progressKeyNearest = -1
    private var progressKeyVersion = -1
    private var progressKeyLast: Tile? = null

    /**
     * One pass of the walk over [own] (the node's route tiles) with look-ahead [stepLimit]. [done] is the node's reached
     * or stop check polled while a leg is walked, [stop] the traversal's latched stop, [repath] asks for a re-route.
     * In a tour the route ends at the first open stop of [stops]; with [exactEnd] its last tile must be stood on.
     */
    suspend fun step(
        script: Script,
        node: TraversalNode,
        own: List<Tile>?,
        stepLimit: Int,
        done: () -> Boolean,
        stop: () -> Boolean,
        repath: () -> Boolean,
        stops: TourStops? = null,
        exactEnd: Boolean = false,
    ): Step = LocalNav.withStaticSuspending { stepScoped(script, node, own, stepLimit, done, stop, repath, stops, exactEnd) }

    private suspend fun stepScoped(
        script: Script,
        node: TraversalNode,
        own: List<Tile>?,
        stepLimit: Int,
        done: () -> Boolean,
        stop: () -> Boolean,
        repath: () -> Boolean,
        stops: TourStops?,
        exactEnd: Boolean,
    ): Step {
        this.stepLimit = stepLimit
        noteWalkerStep(node, own?.lastOrNull())
        val server = TraversalSupport.serverTileOrNull()
        if (server != null) LocalNav.observe(server)
        if (positionJumped(server)) return Step.CONTINUE
        flight.settle(server)
        heading.add(server)
        if (ArrivalWatch.watch(node, server)) {
            lastClick = null
            nextClick = 0
            TraversalSupport.forgetWalkClick()
        }
        watchIdleHops(server)
        ArrivalWatch.watchGates(server)
        if (server == null) return fallback("no server tile", "")
        if (own.isNullOrEmpty()) return fallback("no route tiles", "")
        LocalNav.untrustedReason(server)?.let { return fallback("map untrusted: $it", "at $server") }

        // The Powerburst decision reads the route through a tour's stops; clicks and hops stop at them.
        val burstRoute = extend(own, node)
        val route = TourStops.untilStop(burstRoute, stops, server)
        val stopAhead = stops != null && stops.barrier(route) >= 0
        routeCut = stopAhead

        if (!stopAhead && earlyStartReady(node, server)) {
            println("[$owner] early start: ${node.next} can begin from $server")
            return Step.ADVANCE
        }

        val pick = RoutePicks.choose(route, server, stepLimit, progress, exactEnd)
            ?: return if (LocalNav.field(server, WalkMap.DEFAULT_MAX_DEPTH) == null) {
                fallback("no walking field", "from $server (map version ${CollisionMap.snapshot().version})")
            } else {
                fallback("no route tile on plane ${server.plane}", "from $server, route of ${route.size} from #$progress ${route[min(progress, route.size - 1)]}")
            }
        progress = RoutePicks.advance(progress, pick, route, server, stepLimit)

        if (pick.tile == null) return noClick(node, route, pick, server, repath)
        stuck.reset()
        lastWalkWhy?.let {
            println("[$owner] live-map walk back on at $server (was off: $it)")
            lastWalkWhy = null
        }
        if (pick.index >= own.size) {
            val through = describeThrough(node, pick.index - own.size)
            if (through != lastThrough) {
                lastThrough = through
                println("[$owner] walking through $through (passable on the live map)")
            }
        }

        if (maybeDrinkAcceleration(burstRoute, pick.nearestIndex, server)) {
            lastClick = null
            TraversalSupport.forgetWalkClick()
            nextClick = 0
        }
        // A stop latched since the node polled: nothing is cast for a walk the traversal has abandoned.
        if (stop()) return Step.CONTINUE
        probeWalked = false
        val hopped = castAlongRoute(script, node, route, pick.nearestIndex, server, pick.tile, stop)
        if (probeWalked || stop()) return Step.CONTINUE
        val inFlight = HopFlight.flying
        if (inFlight != null && inFlight.probe) return Step.CONTINUE
        if (hopped) {
            lastClick = null
            TraversalSupport.forgetWalkClick()
            nextClick = 0
        }
        if (inFlight != null && (inFlight.chainAfterDive || inFlight.chainAfterSurge || inFlight.pairSecond != null)) return Step.CONTINUE
        if (!hopped && System.currentTimeMillis() < HopFlight.chainUntil) return Step.CONTINUE

        // The walk click that goes with a hop is planned from where the hop lands, not from the tile it was cast on.
        var walkPick = pick
        if (hopped) {
            val landing = hopLandingPredicted()
            walkPick = RoutePicks.clickAfterHop(route, pick, landing, stepLimit, progress, exactEnd) ?: return Step.CONTINUE
            if (walkPick !== pick && walkPick.tile != pick.tile) {
                println(
                    "[$owner] walk click with the hop planned from its predicted landing $landing: ${walkPick.tile} (#${walkPick.index}; " +
                        "from $server it was ${pick.tile}, #${pick.index})",
                )
            }
        } else {
            val previous = lastClick
            val landedAt = HopFlight.hopLandedAt
            if (previous != null && lastClickAt < landedAt && landingChecked != landedAt) {
                landingChecked = landedAt
                if (RoutePicks.clickBehindLanding(route, previous, pick)) {
                    println(
                        "[$owner] the click $previous sent before the hop landed is behind $server on the route " +
                            "(#${RoutePicks.routeIndexOf(route, pick.nearestIndex, previous)}, the player at #${pick.nearestIndex}, " +
                            "the pick from here #${pick.index}): clicking on from the landing",
                    )
                    lastClick = null
                    TraversalSupport.forgetWalkClick()
                }
            }
        }

        val click = walkPick.tile ?: return Step.CONTINUE
        val moving = !hopped && TraversalSupport.isMoving()
        val now = System.currentTimeMillis()
        val previous = lastClick
        if (previous != null && moving && (click == previous || server.getLongestDelta(previous) > RoutePicks.LEG_NEAR)) return Step.CONTINUE
        // Clicked, but the walk starts on the next server tick: the same click again is a duplicate.
        if (previous != null && !moving && click == previous && now - lastClickAt < DUPLICATE_CLICK_MS) return Step.CONTINUE
        if (now < nextClick) return Step.CONTINUE
        if (TraversalSupport.recentlyClicked(click, if (moving) SHARED_CLICK_MOVING_MS else DUPLICATE_CLICK_MS)) {
            lastClick = click
            lastClickAt = now
            return Step.CONTINUE
        }
        nextClick = now + TraversalSupport.clickGapMs(TraversalSupport.WALK_PATH_CLICK_TIME / 2)
        if (!TraversalSupport.walkTo(click, TraversalSupport.rollMinimap())) return Step.CONTINUE
        TraversalSupport.noteWalkClick(click)
        HopFlight.flying?.record?.noteWalkClick()
        lastClick = click
        lastClickAt = now
        val inAir = flight.pending
        TraversalSupport.awaitWalk(TraversalSupport.WALK_START_TIMEOUT_MS, TraversalSupport.WALK_LEG_TIMEOUT_MS) {
            stop() || done() || nearServer(click) || earlyStartReadyThrottled(node) || hopDue() || pendingHopLandedThrottled() ||
                observeWhileWaiting(burstRoute)
        }
        // During the burst the next hop goes on the landing: one more pass now, not on the node's next one.
        if (inAir && !restepping && AccelerationPlanner.active() && !stop() && !done()) {
            restepping = true
            try {
                return stepScoped(script, node, own, stepLimit, done, stop, repath, stops, exactEnd)
            } finally {
                restepping = false
            }
        }
        return Step.CONTINUE
    }

    /** Nothing ahead to click: at the end, off the route, far from it (a teleport still landing), or stuck. */
    private fun noClick(node: TraversalNode, route: List<Tile>, pick: RoutePicks.Pick, server: Tile, repath: () -> Boolean): Step {
        if (pick.atEnd) return Step.CONTINUE
        val ahead = route[min(pick.nearestIndex + 1, route.size - 1)]
        val toAhead = LocalNav.distance(server, ahead, Int.MAX_VALUE)
        if (toAhead != LocalNav.UNREACHABLE) {
            stuck.reset()
            val near = route[pick.nearestIndex]
            return fallback(
                "no click within the look-ahead",
                "from $server, nearest route tile #${pick.nearestIndex}/${route.size} $near (${server.getLongestDelta(near)} away), next $ahead " +
                    (if (toAhead == LocalNav.UNKNOWN) "unknown on the live map" else "$toAhead steps") +
                    ", look-ahead ${min(stepLimit, RoutePicks.MAX_CLICK)} (+${RoutePicks.SLACK})",
            )
        }
        // Far off the route: the step before this walk is a teleport still casting, not a wall.
        if (server.plane != ahead.plane || server.getLongestDelta(ahead) > FAR_OFF_ROUTE) {
            stuck.reset()
            if (ahead != farHoldLogged) {
                farHoldLogged = ahead
                println("[$owner] $ahead is far from $server (a teleport still landing?) - holding instead of the stuck check")
            }
            if (farHoldGaveUp(server, ahead)) repath()
            return Step.CONTINUE
        }
        if (!stuck.stuck(ahead)) return Step.CONTINUE
        stuck.reset()
        println("[$owner] stuck: $ahead is unreachable from $server on the live map")
        val door = node.next as? DoorNode
        if (door != null && !door.passableNow() && atDoor(ahead, door)) {
            println("[$owner] stuck before a closed door - handing over to $door")
            return Step.ADVANCE
        }
        // The live map can be wrong where the route is not: a repath returns the same route, so the server's own
        // pathing gets one keypoint click past the spot first.
        if (!serverTriedAt(server)) {
            serverTriedFrom = server
            println("[$owner] trying the server's own pathing past $ahead")
            return Step.FALLBACK
        }
        serverTriedFrom = null
        if (repath()) return Step.CONTINUE
        return fallback("stuck, repath refused", "at $server before $ahead")
    }

    /**
     * The server tile read with no waypoint queued while the drawn model lags jumps back to the drawn tile. True (no hop,
     * no click) while such a jump is unconfirmed: further than running goes since the last pass, onto the drawn tile,
     * with no hop, probe or teleport to explain it. Confirmed once the player moves on, or after [JUMP_SETTLE_MS] on it.
     */
    private fun positionJumped(server: Tile?): Boolean {
        val now = System.currentTimeMillis()
        val prev = seenTile
        val prevAt = seenAt
        seenTile = server
        seenAt = now
        if (server == null) return false
        val bad = jumpTile
        if (bad != null) {
            if (server == bad) {
                if (now - jumpSince < JUMP_SETTLE_MS) return true
                println("[$owner] position $bad held ${now - jumpSince} ms: taken as real")
            } else if (server.plane != bad.plane || server.getLongestDelta(bad) >= HeadingTrack.HOP_JUMP_TILES) {
                println("[$owner] position read back at $server after ${now - jumpSince} ms on $bad: that was the drawn tile, not the player's")
            }
            jumpTile = null
            return false
        }
        if (prev == null || flight.pending || HopFlight.flying != null || prev.plane != server.plane || now - prevAt > JUMP_PASS_GAP_MS ||
            now - heading.lastHopAt < HeadingTrack.HOP_FACING_MS || now - LocalNav.lastTeleportSeenAt < JUMP_PASS_GAP_MS
        ) {
            return false
        }
        val jump = prev.getLongestDelta(server)
        if (jump <= ServerWalk.TICK_STEPS * (1 + ((now - prevAt) / TraversalSupport.TICK_MS).toInt())) return false
        val drawn = runCatching { localPlayer.tile }.getOrNull()
        if (drawn == null || drawn.x != server.x || drawn.y != server.y) return false
        jumpTile = server
        jumpSince = now
        println("[$owner] position read jumped $jump tiles from $prev to $server in ${now - prevAt} ms with no hop, onto the drawn tile: no hop or click from it until it settles")
        return true
    }

    /**
     * One pass of a far hold: true once the player has stood still past [FAR_HOLD_IDLE_MS] ([FAR_HOLD_ATTACKED_MS] under
     * attack, whatever animates): the teleport before the walk is not coming. Once per stretch.
     */
    private fun farHoldGaveUp(server: Tile, ahead: Tile): Boolean {
        val now = System.currentTimeMillis()
        val attacked = AttackMemory.attackedWithin(ATTACKED_WITHIN_MS)
        val fresh = now - farHoldLastPass > FAR_HOLD_PASS_GAP_MS
        farHoldLastPass = now
        if (fresh || server != farHoldAt || !attacked && (TraversalSupport.isAnimating() || TraversalSupport.isMoving())) {
            farHoldAt = server
            farHoldSince = now
            return false
        }
        if (farHoldSince == 0L) return false
        val idle = now - farHoldSince
        if (idle < if (attacked) FAR_HOLD_ATTACKED_MS else FAR_HOLD_IDLE_MS) return false
        farHoldSince = 0
        println("[$owner] no teleport came: still at $server $idle ms" + (if (attacked) " under attack" else ", not animating") + ", $ahead far off - re-routing")
        return true
    }

    private fun hopLandingPredicted(): Tile? = synchronized(HopFlight.FLIGHT) {
        val f = HopFlight.flying ?: flight
        if (f.pending) f.predicted else null
    }

    // ---- the cast check ----

    /**
     * Surge and Dive placed by the client: each goes once off cooldown past a human reaction ([usable]) where the live
     * map shows a hop toward the walk's end that the schedule does not keep for a better spot. Surge along the predicted
     * server walk ([surgeOnWalk]) or the last step ([surgeByLastStep]); Dive at the route tile whose landing gains the
     * most ([HopRules.pickDive]). Both ready, the joint plan may keep both for a combo ahead or send one now; on a
     * learning client a probe may go first. True when a hop was cast.
     */
    private suspend fun castAlongRoute(
        script: Script,
        node: TraversalNode,
        route: List<Tile>,
        nearest: Int,
        server: Tile,
        walkClick: Tile?,
        stop: () -> Boolean,
    ): Boolean {
        if (!abilities.any()) return castHeld("Surge and Dive are off for this walk")
        if (flight.pending) {
            primeReactions()
            return castHeld("own ${flight.kind} not seen landing yet")
        }
        val inAir = HopFlight.flying
        if (inAir != null) return castHeld("a ${inAir.kind} from ${inAir.owner} is in the air")
        val now = System.currentTimeMillis()
        ArrivalWatch.hold(now)?.let { return castHeld(it) }
        if (server == HopFlight.missedFrom && now - HopFlight.missedAt < MISSED_RETRY_MS) return castHeld("the last cast from $server did not happen")
        if (now < nextAbilityCheck) return false
        pairTickHeld = false
        jointGo = false
        val boosted = AccelerationPlanner.active()
        nextAbilityCheck = now + if (boosted) BOOSTED_CHECK_MS else ABILITY_CHECK_MS
        if (route.size - nearest < HopRules.END_GATE_TILES) return castHeld("the route ends in ${route.size - nearest} tiles")
        val ahead = route[min(route.size - 1, nearest + HopRules.SURGE_PROGRESS_AHEAD)]
        val progress = progressFor(route, nearest, server)
        val surgeReady = abilities.surge && surgeOnBar() && usable(Ability.SURGE, surge = true)
        val diver = HopCharges.diveOnBar()
        val diveReady = abilities.dive && diver != null && usable(diver, surge = false)
        val direction = if (surgeReady) heading.surgeDirection() else null
        var surgeWhy: String? = if (surgeReady) "ready, no hop" else "not usable"
        var diveWhy = if (diveReady) "ready, no hop" else "not usable"
        // Surge -> Dive: the surge landed with no walk click since, and the dive goes from there now.
        val diveChained = now < HopFlight.diveChainUntil && TraversalSupport.lastWalkClickOrNull() == null
        // A dive fires from the server tile or up to a tick on along the predicted walk; chained, from the landing.
        val onWalk = if (diveChained) null else ServerWalk.shared.ahead(server)
        val arming = if (diveChained) listOf(server) else onWalk ?: HopRules.armingTiles(server, walkClick)
        val diveNow = if (diveReady) HopRules.pickDive(route, nearest, server, progress, arming) else null
        // Straight after a surge a dive goes any way: a surge is worth what the dive from its landing adds over the dive's
        // best use without it.
        comboRef = if (surgeReady && diveReady && !diveChained && !boosted) max(HopRules.DIVE_MIN_GAIN, if (diveNow?.aim == null) 0 else diveNow.gain) else -1
        comboBonusCache.clear()

        // Both ready: the joint plan is weighed first, so a probe never takes a hop a combo needs.
        val jointAsk = surgeReady && diveReady && !diveChained && !boosted && !pairTickHeld && now >= HopFlight.chainUntil
        val jointPlan = if (jointAsk) joint(route, nearest, progress, diver, server) else null

        val comboPlanned = jointPlan != null && jointPlan.kind != HopSchedule.Joint.Kind.SPLIT
        if ((surgeReady || diveReady) && abilities.exploreHops && !boosted && !comboPlanned && HopExplorer.allowedNow(server)) {
            val surgeBeat = if (surgeReady) surgeGainNow(route, nearest, progress) else 0
            val diveBeat = if (diveNow?.aim != null) max(0, diveNow.gain) else 0
            if (probe(script, route, nearest, server, direction, if (diveReady) diver else null, stop, surgeBeat, diveBeat)) return true
        }
        // A probe walked the player to its stand and cast nothing: this pass's tile and plans are stale.
        if (probeWalked) return false

        var jointHold = false
        var diveFirstNow = false
        var jointAhead = -1
        if (jointAsk) {
            val j = jointPlan
            when {
                j == null || j.kind == HopSchedule.Joint.Kind.SPLIT -> {
                    jointHoldSince = 0
                    comboHoldGain = 0
                }
                j.kind == HopSchedule.Joint.Kind.COMBO_AHEAD -> {
                    if (jointHoldSince == 0L) jointHoldSince = now
                    val cap = max(HopCharges.fullCooldownMs(Ability.SURGE), HopCharges.fullCooldownMs(diver))
                    if (now - jointHoldSince > cap) {
                        jointGreedy = true
                        println("[$owner] combo hold dropped: kept ${now - jointHoldSince} ms, a whole cooldown, without reaching its spot - each hop plans on its own until one goes")
                        if (comboHoldGain > 0) ScheduleLearning.comboHoldDone(comboHoldGain, 0, owner)
                        comboHoldGain = 0
                    } else {
                        jointHold = true
                        jointAhead = j.at
                        if (comboHoldGain == 0) comboHoldGain = j.gain
                        val spot = route[min(route.size - 1, nearest + j.at)]
                        val order = if (j.diveFirst) "dive->surge" else "surge->dive"
                        val why = "kept with the " + (if (j.diveFirst) "surge" else "dive") + " for a $order combo at $spot (+${j.gain})"
                        surgeWhy = why
                        diveWhy = why
                        if (spot != jointLogged || now >= jointNextLog) {
                            jointLogged = spot
                            jointNextLog = now + HOLD_LOG_MS
                            println(
                                "[$owner] Surge and Dive kept for a $order combo at $spot (+${j.gain}; plan ${HopRules.fmt(j.holdValue)} vs " +
                                    "${HopRules.fmt(j.nowValue)} steps toward $progress; ${j.detail})",
                            )
                        }
                    }
                }
                else -> {
                    jointHoldSince = 0
                    jointGo = true
                    diveFirstNow = j.diveFirst
                    if (comboHoldGain > 0) ScheduleLearning.comboHoldDone(comboHoldGain, j.gain, owner)
                    comboHoldGain = 0
                    println("[$owner] " + (if (diveFirstNow) "dive->surge" else "surge->dive") + " combo from here (+${j.gain}): it beats each hop on its own (${j.detail})")
                    if (diveFirstNow) surgeWhy = "goes after the dive (dive->surge combo)"
                }
            }
        }

        if (surgeReady && !diveChained && !jointHold && !diveFirstNow) {
            // Chained to a dive with no walk click since: nothing turns the player before it fires.
            val chained = now < HopFlight.chainUntil && TraversalSupport.lastWalkClickOrNull() == null
            plannedReach = surgeReach()
            var walk = if (chained) null else ServerWalk.shared.fires(server, direction, plannedReach)
            var held = walk?.fires?.let { surgeOnWalk(script, it, route, nearest, server, progress) }
            if (held == TICK_RAN_ON) {
                plannedReach = surgeReach()
                walk = ServerWalk.shared.fires(server, direction, plannedReach)
                held = walk.fires?.let { surgeOnWalk(script, it, route, nearest, server, progress) }
            }
            if (walk?.fires != null) {
                if (held == null) return true
                surgeWhy = held
            } else if (walk != null && walk.hold) {
                surgeWhy = walk.why
            } else {
                if (walk != null) heading.source += "; ${walk.why}"
                surgeWhy = surgeByLastStep(script, direction, chained, route, nearest, server, progress) ?: return true
            }
        }

        // Surge first on a straight line: held for want of a clean facing, it is lined up and the dive waits.
        var lining = false
        if (surgeReady && !diveChained && !boosted && !pairTickHeld && !jointHold && !diveFirstNow && surgeWhy != null && surgeWhy != CAST_FAILED) {
            val lined = lineUpSurge(route, nearest, server, progress)
            if (lined != null) {
                surgeWhy = lined
                lining = true
            }
        }

        if (diveReady && !lining && !pairTickHeld && !jointHold && diveNow != null) {
            val cast = castDive(script, route, nearest, server, walkClick, progress, diveNow, diveChained, diveFirstNow, onWalk, diver)
            if (cast == null) return true
            if (cast == DIVE_PAIR_HELD) return false
            diveWhy = cast
        }
        castHeld("Surge $surgeWhy; Dive $diveWhy")
        if (surgeReady) HopFlight.chainUntil = 0
        if (diveChained) {
            if (!surgeReady) println("[$owner] no dive from the surge's landing $server: $diveWhy")
            HopFlight.diveChainUntil = 0
            HopFlight.chainUntil = 0
        }
        val tried = surgeReady || diveReady
        if (tried) hopHoldUntil = now + if (boosted) BOOSTED_CHECK_MS else HOP_HOLD_MS
        // A spot kept a tile or two on is passed within the usual wait: look again as the player reaches it.
        if (tried && (keptSoon(route, nearest) || jointHold && jointAhead <= KEPT_SOON_TILES)) {
            hopHoldUntil = now + BOOSTED_CHECK_MS
            nextAbilityCheck = now + BOOSTED_CHECK_MS
        }
        jointGo = false
        return false
    }

    /**
     * The dive branch: [diveNow]'s aim unless the schedule keeps it (not for a dive chained to a surge); a surge chained
     * after it, sent in the same tick when its facing is sure. Null when cast, else why not.
     */
    private suspend fun castDive(
        script: Script,
        route: List<Tile>,
        nearest: Int,
        server: Tile,
        walkClick: Tile?,
        progress: RouteProgress,
        pick: HopRules.DivePick,
        diveChained: Boolean,
        diveFirstNow: Boolean,
        onWalk: List<Tile>?,
        diver: Ability,
    ): String? {
        val now = System.currentTimeMillis()
        if (pick.armedWhy != null && now >= nextHoldLog) {
            nextHoldLog = now + HOLD_LOG_MS
            println("[$owner] dive toward ${pick.armedAim} held: lands ${pick.armedLanding} from $server, but ${pick.armedWhy}")
        }
        var pickAim = pick.aim
        var pickLanding = pick.landing
        var pickGain = pick.gain
        // A dive -> surge combo planned from here dives where its surge then ends best.
        val c0 = combo0
        val comboTo = c0?.diveTo
        if (jointGo && diveFirstNow && comboTo != null && comboTo != pickLanding && comboTo == MovePrediction.diveLanding(server, comboTo)) {
            println("[$owner] the dive -> surge combo dives to $comboTo (the walk's own best dive lands $pickLanding): its surge ends +${c0.gain} on")
            pickAim = comboTo
            pickLanding = comboTo
            pickGain = progress.gain(server, comboTo)
        }
        diveTickOn = if (onWalk == null) null else ({ diveFromTickOn(route, nearest, server, progress) })
        val kept = if (pickAim == null || diveChained) null else keep(false, diver, route, nearest, pickLanding, pickGain, progress)
        diveTickOn = null
        if (pickAim == null && !diveChained) noteKeptDiveMissing(route, nearest, server, pick)
        if (kept != null) return kept
        var why: String? = null
        if (pickAim != null && pickLanding != null) {
            val aim: Tile = pickAim
            val landing: Tile = pickLanding
            val chain = chainedSurge(route, nearest, server, landing, progress)
            var unsure = if (chain == null) null else pairFacingUnsure(onWalk, server, aim, landing, chain)
            // A far aim lands a tile further fired from a tile on: aimed at its landing it lands there from every fire tile.
            var comboAim = aim
            if (chain != null && unsure != null && pairFacingUnsure(onWalk, server, landing, landing, chain) == null) {
                comboAim = landing
                unsure = null
            }
            if (chain != null && unsure == null && surgeTooSoon()) {
                unsure = "a surge went ${System.currentTimeMillis() - surgeSentAt} ms ago (Double Surge's second charge needs two ticks)"
            }
            if (chain != null && unsure == null) {
                val key = ScheduleLearning.comboKey(HopExplainer.DIVE, landing, MovePrediction.surgeLanding(landing, chain))
                if (!ScheduleLearning.comboTrusted(key)) {
                    unsure = "one-tick combos here landed right only ${ScheduleLearning.comboRecord(key)} times: the surge goes a tick later, where both hops are learned"
                }
            }
            val pairNow = SAME_TICK_PAIRS && chain != null && unsure == null
            if (pairNow && waitForTickStart(HopExplainer.DIVE, DIVE_FIRST_START_MS)) {
                castHeld("dive and its surge held for the next tick's start (sent together then, they land in one tick)")
                return DIVE_PAIR_HELD
            }
            println(
                "[$owner] client dive from $server toward $aim, predicted landing $landing" +
                    (if (onWalk != null && onWalk.size > 1) " (or from ${onWalk.subList(1, onWalk.size)} on the walk)" else "") +
                    " (gains $pickGain steps toward $progress)" + (if (diveChained) ", chained to the surge" else ""),
            )
            // Chained to a surge, the dive goes from the landing the player stands on: no walk click while it arms.
            if (!diveChained) keepMovingWhileArming(walkClick)
            val firstAt = inTick(TraversalSupport.msIntoTickAtSend())
            val sent = if (pairNow) script.diveThenSurge(comboAim, diver) else if (script.dive(aim, diver)) 1 else 0
            if (pairNow && comboAim != aim) {
                println("[$owner] the dive goes as a combo toward its landing $comboAim (not $aim): fired from any tile on the walk it lands there")
            }
            if (sent > 0) {
                if (sent == 2 && chain != null) {
                    val surgeEnd = MovePrediction.surgeLanding(landing, chain)
                    notePair(HopExplainer.SURGE, landing, chain, null, surgeEnd, progress.gain(landing, surgeEnd), firstAt, inTick(TraversalSupport.msIntoTick()))
                }
                pending(HopExplainer.DIVE, server, landing, null, aim, -1)
                if (sent == 2 && firstAt >= 0) flight.tickMs = firstAt
                ScheduleLearning.cast(false, pickGain)
                if (onWalk != null) flight.noteFires(onWalk, onWalk.map { MovePrediction.diveLanding(it, aim) })
                if (chain != null && sent == 1) {
                    flight.chainAfterDive = true
                    println("[$owner] surge to follow the dive $chain from $landing: no walk click until it is cast" + (if (unsure != null) " (not sent with it: $unsure)" else ""))
                }
                return null
            }
            why = CAST_FAILED
            println("[$owner] client dive: " + (if (pairNow) "diveThenSurge" else "dive") + " failed at $server toward $aim")
        }
        if (why == CAST_FAILED) return why
        return if (pick.aims == 0) {
            "no route tile within ${HopRules.DIVE_AIM_MAX}"
        } else {
            "${pick.aims} aim(s), ${pick.noLanding} without a landing, " + (if (pick.armHeld > 0) "${pick.armHeld} short from where it fires, " else "") +
                "best gain " + (if (pick.bestGain == Int.MIN_VALUE) "none" else "${pick.bestGain}") + " of ${HopRules.DIVE_MIN_GAIN}"
        }
    }

    /** [HopSchedule.joint] for Surge and [diver], both ready, from route index [nearest]; null when no schedule applies. */
    private fun joint(route: List<Tile>, nearest: Int, progress: RouteProgress, diver: Ability?, server: Tile): HopSchedule.Joint? {
        combo0 = null
        if (!abilities.scheduleHops || !HopSchedule.ENABLED || jointGreedy || diver == null) return null
        if (!abilities.surge || !abilities.dive) return null
        if (progress.field == null || progress.rootIndex <= nearest) return null
        if (SURGE_KEEP.greedy || DIVE_KEEP.greedy) return null
        val cs = HopSchedule.cycleSteps(HopCharges.fullCooldownMs(Ability.SURGE))
        val cd = HopSchedule.cycleSteps(HopCharges.fullCooldownMs(diver))
        if (cs <= 0 || cd <= 0) return null
        val s = HopSchedule.copy(spots(true, route, nearest, progress))
        val d = HopSchedule.copy(spots(false, route, nearest, progress))
        val from = route[nearest]
        val s0 = HopSchedule.surgeFrom(route, nearest, progress)
        val d0 = HopSchedule.diveFrom(route, nearest, progress)
        s.gain[0] = if (s0 == null) 0 else progress.gain(from, s0)
        s.land[0] = if (s0 == null) 0 else HopSchedule.landIndex(route, nearest, s0, progress) - nearest
        d.gain[0] = if (d0 == null) 0 else progress.gain(from, d0)
        d.land[0] = if (d0 == null) 0 else HopSchedule.landIndex(route, nearest, d0, progress) - nearest
        val c = combos(route, nearest, progress, min(s.head, d.head), server)
        return HopSchedule.joint(s, d, c, cs, cd, ScheduleLearning.params(true), ScheduleLearning.params(false), ScheduleLearning.comboMargin())
    }

    /** Combos per route index, cached while the route, its root and the map stay the same; index 0 from the player's own tile. */
    private fun combos(route: List<Tile>, nearest: Int, p: RouteProgress, horizon: Int, server: Tile?): HopSchedule.Combos {
        val key = Objects.hash(System.identityHashCode(route), route.size, route.last(), p.root, CollisionMap.snapshot().version)
        var abs = comboAbs
        if (key != comboCacheKey || abs == null || abs.size != route.size) {
            abs = arrayOfNulls(route.size)
            comboAbs = abs
            comboDone = BooleanArray(route.size)
            comboWide = BooleanArray(route.size)
            comboCacheKey = key
        }
        val w = arrayOfNulls<HopSchedule.ComboAt>(max(0, min(horizon, route.size - nearest)))
        for (k in w.indices) {
            val i = nearest + k
            if (k == 0 && server != null && server.plane == route[i].plane && server.getLongestDelta(route[i]) <= COMBO_HERE_MAX_OFF) {
                val here = HopSchedule.comboAt(route, i, p, server)
                combo0 = here
                w[k] = here
                continue
            }
            val wide = k <= COMBO_WIDE_AHEAD
            if (!comboDone[i] || wide && !comboWide[i]) {
                abs[i] = if (wide) HopSchedule.comboAt(route, i, p, route[i]) else HopSchedule.comboAt(route, i, p)
                comboDone[i] = true
                comboWide[i] = wide
            }
            w[k] = abs[i]
        }
        return HopSchedule.combos(w, horizon, nearest, route.size - 1 - nearest)
    }

    /**
     * The schedule's answer for a hop from route index [at] gaining [gain] and landing on [landing]; null when no
     * schedule applies (off, the burst, no root, no known cooldown, this stretch gone greedy).
     */
    private fun plan(surge: Boolean, ability: Ability?, route: List<Tile>, at: Int, landing: Tile?, gain: Int, progress: RouteProgress): HopSchedule.Decision? {
        if (!abilities.scheduleHops || !HopSchedule.ENABLED || ability == null) return null
        if (AccelerationPlanner.active() || progress.field == null || progress.rootIndex <= at) return null
        if ((if (surge) SURGE_KEEP else DIVE_KEEP).greedy) return null
        val cycle = HopSchedule.cycleSteps(HopCharges.fullCooldownMs(ability))
        if (cycle <= 0) return null
        val s = spots(surge, route, at, progress)
        s.gain[0] = gain
        s.land[0] = if (landing == null) 0 else HopSchedule.landIndex(route, at, landing, progress) - at
        lastPlanSpots = s
        lastPlanProbe = if (surge) surgeProbeMark else diveProbeMark
        return HopSchedule.decide(s, cycle, ScheduleLearning.params(surge))
    }

    private fun spots(surge: Boolean, route: List<Tile>, at: Int, progress: RouteProgress): HopSchedule.Spots {
        val key = Objects.hash(route.size, route.last(), at, CollisionMap.snapshot().version, HopExplorer.probeCount(), abilities.exploreHops)
        if (surge && key == surgeSpotsKey && progress === surgeSpotsProgress) surgeSpots?.let { return it }
        if (!surge && key == diveSpotsKey && progress === diveSpotsProgress) diveSpots?.let { return it }
        val horizon = progress.rootIndex - at + 1
        val tailEnd = route.size - 1 - at
        var s = if (surge) HopSchedule.scanSurge(route, at, horizon, progress, tailEnd) else HopSchedule.scanDive(route, at, horizon, progress, tailEnd)
        // Past the walk's end: the interactions and the walks after them.
        val node = stepNode
        if (progress.end && !routeCut && node != null) {
            val segs = HopSchedule.segs(Interactions.after(node, route.last())) { legSpots(surge, it) }
            if (segs.isNotEmpty()) s = HopSchedule.concat(s, segs)
        }
        val mark = BooleanArray(s.gain.size)
        overlayProbes(surge, route, at, s, progress, mark)
        if (surge) {
            surgeProbeMark = mark
            surgeSpots = s
            surgeSpotsKey = key
            surgeSpotsProgress = progress
        } else {
            diveProbeMark = mark
            diveSpots = s
            diveSpotsKey = key
            diveSpotsProgress = progress
        }
        return s
    }

    /**
     * On a learning client, the route tiles ahead where [probe] would find a probe of this ability are spots worth what
     * that probe gains if a hop crosses its wall, when that beats the ordinary hop from the tile.
     */
    private fun overlayProbes(surge: Boolean, route: List<Tile>, at: Int, s: HopSchedule.Spots, progress: RouteProgress, mark: BooleanArray) {
        if (!abilities.exploreHops || !HopExplorer.allowedAt(route[at])) return
        val lim = min(min(s.head, s.horizon), PROBE_SCAN_TILES)
        var k = PROBE_STOP_MIN_STEPS
        while (k < lim && at + k < route.size) {
            if (route[at + k].plane != route[at].plane) break
            val g = probeSpot(surge, route, at + k, progress)
            if (g > max(s.gain[k], s.raw[k])) {
                s.gain[k] = g
                s.land[k] = k
                mark[k] = true
            }
            k++
        }
    }

    /** What a probe of this ability from route index [i] gains if a hop crosses its wall, measured as [probe] does; 0 when none fits. */
    private fun probeSpot(surge: Boolean, route: List<Tile>, i: Int, p: RouteProgress): Int {
        val key = Objects.hash(System.identityHashCode(route), route.size, route.last(), p.root, CollisionMap.snapshot().version, HopExplorer.probeCount())
        if (key != probeAtKey) {
            surgeProbeAt.clear()
            diveProbeAt.clear()
            probeAtKey = key
        }
        val m = if (surge) surgeProbeAt else diveProbeAt
        m[i]?.let { return it }
        val t = route[i]
        val aheadThere = route[min(route.size - 1, i + HopRules.SURGE_PROGRESS_AHEAD)]
        var found = 0
        val gain = if (HopExplorer.standGivenUp(t)) null else RouteProgress.probeGain(t, aheadThere)
        if (gain != null) {
            val probe = if (surge) {
                HopRules.routeStep(route, i)?.let { HopExplorer.surge(t, it, gain, HopExplorer.PROBE_MIN_GAIN) }
            } else {
                HopExplorer.dive(t, route, i, HopRules.DIVE_MAX_AHEAD, HopRules.DIVE_AIM_MAX, gain, HopExplorer.PROBE_MIN_GAIN)
            }
            if (probe != null) found = max(0, probe.gain)
        }
        m[i] = found
        return found
    }

    /** Hop spots along a walk past an interaction, cached per walk and map version. */
    private fun legSpots(surge: Boolean, tiles: List<Tile>): HopSchedule.Spots {
        val n = tiles.size
        if (n == 0) return HopSchedule.uniform(0)
        val base = ScheduleLearning.baseGain(surge)
        val key = "$surge:$n:${tiles[0]}:${tiles[n - 1]}:${CollisionMap.snapshot().version}:$base"
        legSpotsCache[key]?.let { return it }
        if (legSpotsCache.size > LEG_SPOTS_CACHE_MAX) legSpotsCache.clear()
        return HopSchedule.legSpots(surge, tiles, base).also { legSpotsCache[key] = it }
    }

    /**
     * Null to cast the ready hop now, else why it is kept for a spot ahead (logged when the spot changes, else every
     * [HOLD_LOG_MS]). A kept ability goes back to the first-good-enough rule for the rest of its off-cooldown stretch once
     * the player passes the spot without casting, or it has been kept a whole cooldown and reaction.
     */
    private fun keep(surge: Boolean, ability: Ability?, route: List<Tile>, nearest: Int, landing: Tile?, gain: Int, progress: RouteProgress): String? {
        if (jointGo) return null
        val k = if (surge) SURGE_KEEP else DIVE_KEEP
        val name = if (surge) "surge" else "dive"
        val now = System.currentTimeMillis()
        val kept = k.spot
        val onRoute = if (kept == null) -1 else RoutePicks.routeIndexOf(route, nearest, kept)
        if (kept != null && k.beyond && onRoute >= 0) {
            k.beyond = false
            k.since = now
        }
        if (kept != null && !k.beyond && onRoute < 0) k.reset()
        val spot = k.spot
        if (spot != null && !k.greedy && !k.beyond) {
            val at = RoutePicks.routeIndexOf(route, nearest, spot)
            val otherCastAt = if (surge) HopCharges.diveSpentAt else HopCharges.surgeSpentAt
            // On the spot counts as ahead until the other hop goes from it: in its flight the server tile is still the spot.
            if (at > nearest || at == nearest && otherCastAt <= k.aheadAt) k.aheadAt = now
            when {
                at in 0 until nearest && otherCastAt > k.aheadAt -> {
                    println("[$owner] $name kept for $spot: the " + (if (surge) "dive" else "surge") + " cast since went past it, planning again from ${route[nearest]}")
                    k.spot = null
                    k.keptGain = 0
                    k.keptBeyond = false
                    k.keptProbe = false
                }
                at in 0 until nearest && nearest - at <= ServerWalk.TICK_STEPS -> {
                    println("[$owner] $name kept for $spot: passed it by ${nearest - at} at ${route[nearest]}, planning again from here")
                    k.spot = null
                }
                at in 0 until nearest -> {
                    println("[$owner] $name kept for $spot dropped: passed it at ${route[nearest]} without casting, cast as before until it is spent")
                    k.greedy = true
                }
                ability != null && now - k.since > HopCharges.fullCooldownMs(ability) + HopRules.READY_DELAY_MEAN_MS -> {
                    println("[$owner] $name kept for $spot dropped: kept ${now - k.since} ms, a whole cycle, cast as before until it is spent")
                    k.greedy = true
                }
            }
        }
        val past = if (surge) surgePastKeptDive(route, nearest, landing, progress) else null
        if (past != null) {
            if (now >= k.nextLog) {
                k.nextLog = now + HOLD_LOG_MS
                println("[$owner] surge ready at ${route[nearest]}, held: $past")
            }
            return past
        }
        var d = withinTick(k, route, nearest, gain, plan(surge, ability, route, nearest, landing, gain, progress))
        // Double Surge with both charges back: the other charge serves the spot this one would be kept for.
        if (d != null && !d.cast && surge && HopCharges.secondSurgeCharge()) d = HopSchedule.Decision(true, -1, 0, gain.toDouble(), 0.0, "the second charge is ready")
        val pair = if (d != null && d.cast) pairHold(surge, route, nearest, landing, gain, progress) else null
        if (pair != null) {
            if (now >= k.nextLog) {
                k.nextLog = now + HOLD_LOG_MS
                println("[$owner] $name ready at ${route[nearest]}, held: $pair")
            }
            return pair
        }
        if (d == null || d.cast) {
            val wasKept = k.spot
            if (d != null && wasKept != null) {
                val tick = d.detail.indexOf("; within a tick")
                println("[$owner] $name kept for $wasKept goes now from ${route[nearest]} (gains $gain, kept ${now - k.since} ms" + (if (tick < 0) "" else d.detail.substring(tick)) + ")")
            }
            if (ScheduleLearning.learnsMargin(k.keptGain, k.keptProbe)) ScheduleLearning.holdDone(k.keptBeyond, k.keptGain, gain, owner)
            k.keptGain = 0
            k.keptBeyond = false
            k.keptProbe = false
            k.spot = null
            return null
        }
        val ps = lastPlanSpots
        val beyond = ps != null && ps.isBeyond(d.keptAt)
        val keptSpot = if (ps == null) route[min(route.size - 1, nearest + d.keptAt)] else HopSchedule.spotTile(route, nearest, ps, d.keptAt)
        if (k.since == 0L) k.since = now
        k.spot = keptSpot
        k.aheadAt = now
        k.beyond = beyond
        k.keptGain = d.keptGain
        k.keptBeyond = k.keptBeyond || beyond
        val pm = lastPlanProbe
        k.keptProbe = pm != null && d.keptAt >= 0 && d.keptAt < pm.size && pm[d.keptAt]
        val where = if (beyond) " on the walk after the ${ps.beyondWhat?.get(d.keptAt)}, ${d.keptAt} steps on" else ", ${d.keptAt} route tiles on"
        val why = "kept for $keptSpot$where: gains ${d.keptGain}" + (if (k.keptProbe) " if a probe there crosses its wall" else "") +
            " there vs $gain now (plan ${HopRules.fmt(d.holdValue)} vs ${HopRules.fmt(d.castValue)} steps toward $progress; ${d.detail})"
        if (keptSpot != k.logged || now >= k.nextLog) {
            k.logged = keptSpot
            k.nextLog = now + HOLD_LOG_MS
            println("[$owner] $name ready at ${route[nearest]}, $why")
        }
        return why
    }

    /**
     * [d], unless the spot it keeps the hop for (or the spot kept so far, which [d] would now cast short of) is within a
     * tick on this walk: the run fires the hop from here or a tick on. Kept when a tick on gains more than here; a
     * surge cast late in the tick fires a tick on anyway and goes. A dive always waits when a tick on gains more, its
     * gain a tick on taken from where the server walk will be then ([diveTickOn]).
     */
    private fun withinTick(k: Keep, route: List<Tile>, nearest: Int, gain: Int, d: HopSchedule.Decision?): HopSchedule.Decision? {
        val ps = lastPlanSpots
        if (d == null || ps == null || ServerWalk.TICK_STEPS >= ps.head) return d
        val keptSpot = k.spot
        val target = when {
            !d.cast -> if (ps.isBeyond(d.keptAt)) -1 else d.keptAt
            keptSpot != null && !k.beyond && !k.greedy && k.keptGain > 0 -> RoutePicks.routeIndexOf(route, nearest, keptSpot) - nearest
            else -> -1
        }
        if (target < 1 || target > ServerWalk.TICK_STEPS) return d
        var next = ps.raw[ServerWalk.TICK_STEPS]
        var from = ""
        val real = if (k === DIVE_KEEP) diveTickOn?.invoke() ?: -1 else -1
        if (real >= 0 && real != next) {
            from = " ($next from the route tile)"
            next = real
        }
        val early = k === DIVE_KEEP || surgeReach() == 0
        val why = "; within a tick: +$gain from here, +$next a tick on$from" + (if (early) "" else ", cast late it fires there")
        val hold = early && next > gain
        val key = "${route[nearest + target]}@${route[nearest]}" + (if (hold) " waits" else " goes")
        if (key != tickLogged) {
            tickLogged = key
            println(
                "[$owner] " + (if (k === SURGE_KEEP) "surge" else "dive") + " for ${route[nearest + target]}, $target route tiles on, within a tick at " +
                    "${route[nearest]}${why.substring(WITHIN_TICK_PREFIX)}" + (if (hold) ": waits a tick" else ": goes now"),
            )
        }
        if (hold) return HopSchedule.Decision(false, target, if (d.cast) k.keptGain else d.keptGain, d.castValue, d.holdValue, d.detail + why)
        return HopSchedule.Decision(true, -1, 0, d.castValue, d.holdValue, d.detail + why)
    }

    private fun diveFromTickOn(route: List<Tile>, nearest: Int, server: Tile, progress: RouteProgress): Int {
        val t = ServerWalk.shared.tickOn(server)
        if (t == null || t == server) return -1
        val fires = ServerWalk.shared.ahead(t)
        val p = HopRules.pickDive(route, HopRules.routeIndexNear(route, nearest, t), t, progress, fires ?: listOf(t))
        return if (p.aim == null) 0 else p.gain
    }

    /** A dive kept for a spot the player has reached or passed with no dive from here: said once per spot. */
    private fun noteKeptDiveMissing(route: List<Tile>, nearest: Int, server: Tile, pick: HopRules.DivePick) {
        val k = DIVE_KEEP
        val spot = k.spot
        if (spot == null || k.beyond || k.greedy || spot == missingLogged) return
        val at = RoutePicks.routeIndexOf(route, nearest, spot)
        if (at < 0 || at > nearest) return
        missingLogged = spot
        println(
            "[$owner] no dive at the kept spot $spot (+${k.keptGain}), reached at $server (${pick.aims} aim(s), ${pick.noLanding} without a landing, " +
                (if (pick.armHeld > 0) "${pick.armHeld} short from where it fires (${pick.armedWhy}), " else "") +
                "best gain " + (if (pick.bestGain == Int.MIN_VALUE) "none" else "${pick.bestGain}") + ")",
        )
    }

    /** The traversal stopped: its holds were planned on its route, so they are dropped and not learned. */
    fun traversalStopped(outcome: TraversalOutcome) {
        for (k in arrayOf(SURGE_KEEP, DIVE_KEEP)) {
            val spot = k.spot
            if (spot != null) println("[$owner] " + (if (k === SURGE_KEEP) "surge" else "dive") + " kept for $spot dropped: the traversal stopped ($outcome), not learned")
            k.reset()
        }
    }

    /** Why a surge landing on [landing] waits for a ready dive kept for a spot it would fly past, or this very tile; null when none. */
    private fun surgePastKeptDive(route: List<Tile>, nearest: Int, landing: Tile?, progress: RouteProgress): String? {
        val dk = DIVE_KEEP
        val spot = dk.spot
        if (spot == null || dk.greedy || dk.beyond || !abilities.scheduleHops || !HopSchedule.ENABLED || AccelerationPlanner.active() || progress.field == null) return null
        val at = RoutePicks.routeIndexOf(route, nearest, spot)
        if (at < 0 || at < nearest - ServerWalk.TICK_STEPS) return null
        if (at == nearest) return "the dive is kept for this tile ($spot): it goes first"
        if (at < nearest) return "the dive kept for $spot was passed by ${nearest - at}: it goes first"
        if (landing == null) return null
        val lands = HopSchedule.landIndex(route, nearest, landing, progress)
        if (lands < at) return null
        return "the dive is kept for $spot, ${at - nearest} route tiles on, and this surge would land $landing past it"
    }

    /**
     * Why the ready hop waits for the other ability, back within [HopSchedule.PAIR_WAIT_MS]: it first and this one after
     * it from its landing saves more ([HopSchedule.pair]). Null when the other is not coming soon or the order does not pay.
     */
    private fun pairHold(surge: Boolean, route: List<Tile>, nearest: Int, landing: Tile?, gain: Int, progress: RouteProgress): String? {
        val other = if (surge) {
            if (!abilities.dive) null else HopCharges.diveOnBar()
        } else {
            if (abilities.surge && surgeOnBar()) Ability.SURGE else null
        } ?: return null
        val otherSurge = !surge
        val k = if (surge) SURGE_KEEP else DIVE_KEEP
        val now = System.currentTimeMillis()
        val wait: Long
        if (usable(other, otherSurge)) {
            // Castable, yet not cast this pass: held a tick, then both are placed on their own.
            if (k.otherReadyAt == 0L) k.otherReadyAt = now
            if (now - k.otherReadyAt > HopSchedule.PAIR_READY_GRACE_MS) return null
            wait = 0
        } else {
            k.otherReadyAt = 0
            val left = HopCharges.cooldownLeftMs(other, otherSurge)
            if (left == Long.MAX_VALUE) return null
            wait = if (left > 0) left + HopRules.READY_DELAY_MEAN_MS else max(0L, (if (otherSurge) surgeUsableAt else diveUsableAt) - now)
        }
        if (wait > HopSchedule.PAIR_WAIT_MS) return null
        val ySteps = Math.round(wait / 1000.0 * HopRules.TILES_PER_SECOND).toInt()
        val xs = spots(surge, route, nearest, progress)
        val ys = spots(!surge, route, nearest, progress)
        val land = if (landing == null) 0 else HopSchedule.landIndex(route, nearest, landing, progress) - nearest
        xs.gain[0] = gain
        xs.land[0] = land
        val pr = HopSchedule.pair(xs, ys, gain, land, ySteps)
        if (!pr.hold) return null
        val me = if (surge) "surge" else "dive"
        val it = if (surge) "the dive" else "the surge"
        return "$it is back in $wait ms: $it from ${HopSchedule.spotTile(route, nearest, ys, pr.otherAt)} (+${pr.otherGain}) then the $me" +
            (if (pr.afterAt < 0) "" else " from ${HopSchedule.spotTile(route, nearest, xs, pr.afterAt)}") +
            " (+${pr.afterGain}) saves ${pr.otherThenNow} steps, the $me now (+$gain) then $it ${pr.nowThenOther}"
    }

    /**
     * Surge first on a straight line: of the 8 ways from [server], the best whose surge clears its need from every tile
     * it may fire from on a walk that runs straight along that way. Its landing is clicked, so the surge goes on that
     * walk next pass. Null when there is no such line, the schedule keeps the surge, or a line-up just ran out.
     */
    private fun lineUpSurge(route: List<Tile>, nearest: Int, server: Tile, progress: RouteProgress): String? {
        val now = System.currentTimeMillis()
        if (now < lineUpUntil) return "lining up $lineUpDirection toward $lineUpTo"
        if (lineUpUntil != 0L && now - lineUpUntil < LINE_UP_MS) return null
        var best: Direction? = null
        var bestLanding: Tile? = null
        var bestGain = Int.MIN_VALUE
        for (d in EIGHT_WAYS) {
            val l = MovePrediction.surgeLanding(server, d)
            if (l == server || server.getLongestDelta(l) < LINE_UP_MIN_TILES) continue
            val walk = LocalNav.serverPath(server, l)
            if (walk == null || walk.size < 2 || !straight(walk, d)) continue
            var g = Int.MAX_VALUE
            for (i in 0..min(ServerWalk.TICK_STEPS, walk.size - 1)) {
                val f = walk[i]
                val fl = MovePrediction.surgeLanding(f, d)
                val fg = progress.gain(f, fl) + comboBonus(route, nearest, fl, progress)
                if (fg < HopRules.surgeNeed(route, nearest, f, fl)) {
                    g = Int.MIN_VALUE
                    break
                }
                g = min(g, fg)
            }
            if (g == Int.MIN_VALUE || g <= bestGain) continue
            best = d
            bestLanding = l
            bestGain = g
        }
        if (best == null || bestLanding == null) return null
        if (keep(true, Ability.SURGE, route, nearest, bestLanding, bestGain, progress) != null) return null
        if (!TraversalSupport.walkTo(bestLanding, TraversalSupport.rollMinimap())) return null
        TraversalSupport.noteWalkClick(bestLanding)
        lastClick = bestLanding
        lastClickAt = now
        nextClick = now + LINE_UP_MS
        lineUpDirection = best
        lineUpTo = bestLanding
        lineUpUntil = now + LINE_UP_MS
        println("[$owner] surge lined up: walking $best from $server to $bestLanding so the surge goes along it (gains $bestGain steps toward $progress); the dive waits")
        return "lining up $best toward $bestLanding"
    }

    /** The node this walker last stepped; the gap to the next walker's first step is the interaction's hop-free time. */
    private fun noteWalkerStep(node: TraversalNode, end: Tile?) {
        val now = System.currentTimeMillis()
        val prev = lastWalker
        val prevNode = prev?.stepNode
        if (!steppedBefore && prev != null && prev !== this && prevNode != null) {
            Interactions.between(prevNode, node)?.let { Interactions.record(it, now - lastWalkerStepAt) }
        }
        if (!steppedBefore && abilities.any()) println("[$owner] after this walk: ${Interactions.describe(Interactions.after(node, end))}")
        steppedBefore = true
        stepNode = node
        lastWalker = this
        lastWalkerStepAt = now
    }

    /**
     * A surge along the walk the server is running: it may fire from any of [fires], so it goes only when they all go
     * the same way and each landing gains its need. Null when cast, else why it was held.
     */
    private suspend fun surgeOnWalk(
        script: Script,
        fires: List<ServerWalk.Fire>,
        route: List<Tile>,
        nearest: Int,
        server: Tile,
        progress: RouteProgress,
    ): String? {
        val now = System.currentTimeMillis()
        val d = fires[0].direction
        for (f in fires) {
            if (f.direction == d) continue
            val why = "the walk turns from $d to ${f.direction} at ${f.tile}"
            if (now >= nextHoldLog) {
                nextHoldLog = now + HOLD_LOG_MS
                println("[$owner] surge ready, held: $why")
            }
            return why
        }
        // Cast by the walker every surge fired from the server tile: that landing is the one logged; the gain is the least.
        val landing = MovePrediction.surgeLanding(fires[0].tile, d)
        var gain = Int.MAX_VALUE
        var need = HopRules.SURGE_MIN_GAIN
        val bonus0 = comboBonus(route, nearest, landing, progress)
        for (f in fires) {
            val l = MovePrediction.surgeLanding(f.tile, d)
            val g = progress.gain(f.tile, l) + comboBonus(route, nearest, l, progress)
            val n = HopRules.surgeNeed(route, nearest, f.tile, l)
            if (g < gain) {
                gain = g
                need = n
            }
            if (g >= n) continue
            if (now >= nextHoldLog) {
                nextHoldLog = now + HOLD_LOG_MS
                println("[$owner] surge ready, held: on the walk, facing $d from ${f.tile} it would land $l (gains $g steps toward $progress, need $n)")
            }
            return "facing $d from ${f.tile} gains $g of $n"
        }
        keep(true, Ability.SURGE, route, nearest, landing, gain, progress)?.let { return it }
        // Planned from the server tile alone, and the tick has run past EARLY_CAST_MS since: plan again with a tick's tiles.
        if (plannedReach == 0 && surgeReach() != 0) return TICK_RAN_ON
        println(
            "[$owner] client surge from $server on the walk to ${ServerWalk.shared.click} facing $d" +
                (if (fires.size > 1) " (fires from ${fires[0].tile} to ${fires.last().tile})" else "") +
                ", predicted landing $landing (gains at least $gain steps toward $progress" +
                (if (bonus0 > 0) ", $bonus0 of them by the dive from its landing" else "") +
                (if (need < HopRules.SURGE_MIN_GAIN) ", walks back ${HopRules.endSteps(route, landing)} from the route end" else "") + ")",
        )
        return when (castSurge(script, route, nearest, server, landing, d, progress, bonus0, gain - bonus0)) {
            Cast.HELD -> PAIR_HELD
            Cast.SENT -> {
                flight.noteFires(fires.map { it.tile }, fires.map { MovePrediction.surgeLanding(it.tile, d) })
                null
            }
            Cast.FAILED -> {
                println("[$owner] client surge: surge() failed at $server")
                CAST_FAILED
            }
        }
    }

    /**
     * A surge the way of the server tile's last step when no walk is predicted: it goes when the route's next two steps
     * run that way (or it is [chained] straight after a dive) and the landing gains its need. Null when cast.
     */
    private suspend fun surgeByLastStep(
        script: Script,
        direction: Direction?,
        chained: Boolean,
        route: List<Tile>,
        nearest: Int,
        server: Tile,
        progress: RouteProgress,
    ): String? {
        val now = System.currentTimeMillis()
        var surgeWhy = "ready, no hop"
        var dir = direction
        if (dir == null) {
            surgeWhy = "no 8-way direction yet"
            if (now >= nextHoldLog) {
                nextHoldLog = now + HOLD_LOG_MS
                println("[$owner] surge ready, held: no 8-way direction yet (facing ${MovePrediction.facingBearing()}; ${heading.source})")
            }
        }
        val runs = if (dir == null) null else HopRules.routeAhead(route, nearest)
        if (dir != null && runs != dir && !chained) {
            // The surge fires a tick after the click, after that tick's running: unless the route runs straight on, the facing may turn.
            val bend = runs?.toString() ?: "round a bend"
            surgeWhy = "facing $dir but the route goes $bend"
            if (now >= nextHoldLog) {
                nextHoldLog = now + HOLD_LOG_MS
                println("[$owner] surge ready, held: facing $dir (${heading.source}) but the route goes $bend next")
            }
            dir = null
        }
        if (dir == null) return surgeWhy
        val landing = MovePrediction.surgeLanding(server, dir)
        // Judged by progress, not by closeness to the route tiles: the server often walks a parallel line beside them.
        val bonus = comboBonus(route, nearest, landing, progress)
        val gain = progress.gain(server, landing) + bonus
        val need = HopRules.surgeNeed(route, nearest, server, landing)
        if (gain < need) {
            if (now >= nextHoldLog) {
                nextHoldLog = now + HOLD_LOG_MS
                println("[$owner] surge ready, held: facing $dir from $server would land $landing (gains $gain steps toward $progress, need $need)")
            }
            return "facing $dir gains $gain of $need"
        }
        keep(true, Ability.SURGE, route, nearest, landing, gain, progress)?.let { return it }
        println(
            "[$owner] client surge from $server facing $dir (${heading.source}), predicted landing $landing (gains $gain steps toward $progress" +
                (if (bonus > 0) ", $bonus of them by the dive from its landing" else "") +
                (if (need < HopRules.SURGE_MIN_GAIN) ", walks back ${HopRules.endSteps(route, landing)} from the route end" else "") + ")",
        )
        return when (castSurge(script, route, nearest, server, landing, dir, progress, bonus, gain - bonus)) {
            Cast.SENT -> null
            Cast.HELD -> PAIR_HELD
            Cast.FAILED -> {
                println("[$owner] client surge: surge() failed at $server")
                CAST_FAILED
            }
        }
    }

    /**
     * Dive -> Surge: a dive turns the player its own 8-way way, and with no walk click after it they still face that way
     * on landing. The surge's way when Surge is back by the landing and a surge on along the dive's line gains its need
     * and is not kept for later; else null.
     */
    private fun chainedSurge(route: List<Tile>, nearest: Int, server: Tile, landing: Tile?, progress: RouteProgress): Direction? {
        if (!abilities.surge || !surgeOnBar() || !surgeBackByLanding()) return null
        if (landing == null || landing == server) return null
        val d = HopModel.facing(landing.x - server.x, landing.y - server.y) ?: return null
        val at = HopRules.routeIndexNear(route, nearest, landing)
        val surgeLanding = MovePrediction.surgeLanding(landing, d)
        val gain = progress.gain(landing, surgeLanding)
        val need = HopRules.surgeNeed(route, at, landing, surgeLanding)
        if (gain < need && AccelerationPlanner.active()) {
            println("[$owner] no surge chained to the dive: $d from its landing $landing would land $surgeLanding (gains $gain of $need)")
        }
        if (gain < need) return null
        val plan = if (jointGo) null else plan(true, Ability.SURGE, route, at, surgeLanding, gain, progress)
        if (plan != null && !plan.cast) {
            val spots = lastPlanSpots
            println(
                "[$owner] no surge chained to the dive: from its landing $landing it gains $gain, kept for " +
                    (if (spots == null) "a spot ahead" else HopSchedule.spotTile(route, at, spots, plan.keptAt)) +
                    " (gains ${plan.keptGain} there; plan ${HopRules.fmt(plan.holdValue)} vs ${HopRules.fmt(plan.castValue)} steps toward $progress; ${plan.detail})",
            )
            return null
        }
        return d
    }

    /** What the dive straight after a surge landing on [landing] adds to the surge over [comboRef]; 0 when no pair applies. */
    private fun comboBonus(route: List<Tile>, nearest: Int, landing: Tile?, progress: RouteProgress): Int {
        if (comboRef < 0 || landing == null) return 0
        comboBonusCache[landing]?.let { return it }
        val p = HopRules.pickDive(route, HopSchedule.landIndex(route, nearest, landing, progress), landing, progress, listOf(landing))
        val b = if (p.aim == null) 0 else max(0, p.gain - comboRef)
        comboBonusCache[landing] = b
        return b
    }

    /** The dive to follow a surge landing on [landing]: always when the surge went for the pair ([bonus] > 0), else when its plan casts there. */
    private fun diveAfter(route: List<Tile>, nearest: Int, landing: Tile, progress: RouteProgress, bonus: Int, diver: Ability?): HopRules.DivePick? {
        if (comboRef < 0 || diver == null) return null
        val at = HopSchedule.landIndex(route, nearest, landing, progress)
        val p = HopRules.pickDive(route, at, landing, progress, listOf(landing))
        if (p.aim == null) return null
        if (bonus <= 0) {
            val d = plan(false, diver, route, at, p.landing, p.gain, progress)
            if (d != null && !d.cast) return null
        }
        return p
    }

    /**
     * Casts the surge facing [d] landing on [landing] and the dive that follows it: in one combo when its aim is within
     * the dive's reach of the landing, else chained to the landing. [Cast.HELD] when the combo's first click would go too
     * late in the tick for both.
     */
    private suspend fun castSurge(
        script: Script,
        route: List<Tile>,
        nearest: Int,
        server: Tile,
        landing: Tile,
        d: Direction,
        progress: RouteProgress,
        bonus: Int,
        ownGain: Int,
    ): Cast {
        val diver = HopCharges.diveOnBar()
        val p = diveAfter(route, nearest, landing, progress, bonus, diver)
        var comboAim = if (p == null) null else HopRules.comboDiveAim(landing, p)
        var unsure = if (p == null || !SAME_TICK_PAIRS || comboAim != null) {
            null
        } else {
            "its aim ${p.aim} is past the dive's reach from $landing and its landing ${p.landing} is not a straight dive from there"
        }
        val key = if (p == null) null else ScheduleLearning.comboKey(HopExplainer.SURGE, landing, p.landing)
        if (comboAim != null && !ScheduleLearning.comboTrusted(key)) {
            unsure = "one-tick combos here landed right only ${ScheduleLearning.comboRecord(key)} times: the dive goes a tick later, where both hops are learned"
            comboAim = null
        }
        val pairNow = SAME_TICK_PAIRS && comboAim != null && diver != null
        if (pairNow && waitForTickStart(HopExplainer.SURGE, SURGE_FIRST_START_MS)) return Cast.HELD
        val firstAt = inTick(TraversalSupport.msIntoTickAtSend())
        val sent = if (comboAim != null && diver != null && pairNow) script.surgeThenDive(comboAim, diver) else if (surge()) 1 else 0
        if (sent == 0) return Cast.FAILED
        if (sent == 2 && p != null) {
            notePair(HopExplainer.DIVE, landing, null, comboAim, p.landing, p.gain, firstAt, inTick(TraversalSupport.msIntoTick()))
        }
        pending(HopExplainer.SURGE, server, landing, d, null, -1)
        if (sent == 2 && firstAt >= 0) flight.tickMs = firstAt
        ScheduleLearning.cast(true, ownGain)
        if (p != null && sent == 1) {
            flight.chainAfterSurge = true
            println(
                "[$owner] dive to follow the surge from $landing toward ${p.aim} (lands ${p.landing}, gains ${p.gain}): no walk click until it is cast" +
                    (if (unsure != null) " (not sent with it: $unsure)" else if (pairNow) " (the combo sent only the surge)" else ""),
            )
        }
        return Cast.SENT
    }

    /**
     * On a learning client, the ability would sit idle: test a blocked edge with it. The player stands still first on a
     * route tile a few steps ahead, the probe is planned again there, and goes the way they then face. True when cast.
     */
    private suspend fun probe(
        script: Script,
        route: List<Tile>,
        nearest: Int,
        server: Tile,
        direction: Direction?,
        diver: Ability?,
        stop: () -> Boolean,
        surgeBeat: Int,
        diveBeat: Int,
    ): Boolean {
        val surgeMin = max(HopExplorer.PROBE_MIN_GAIN, surgeBeat + 1)
        val diveMin = max(HopExplorer.PROBE_MIN_GAIN, diveBeat + 1)
        val stopIndex = stopIndexAhead(route, nearest, server)
        if (stopIndex < 0) return false
        val target = route[stopIndex]
        if (HopExplorer.standGivenUp(target)) return false
        val aheadThere = route[min(route.size - 1, stopIndex + HopRules.SURGE_PROGRESS_AHEAD)]
        // Arriving, the player faces the last step onto the tile: the surge probe's likely way.
        var arriving = if (direction == null) null else HopRules.routeStep(route, stopIndex)
        var p = findProbe(route, stopIndex, target, arriving, diver, aheadThere, surgeMin, diveMin) ?: return false
        probeWalked = true
        val still = stopAt(target, stop)
        if (still == null) {
            HopExplorer.noteStand(target, false)
            return false
        }
        val stillAt = HopRules.routeIndexNear(route, nearest, still)
        val aheadStill = route[min(route.size - 1, stillAt + HopRules.SURGE_PROGRESS_AHEAD)]
        // The facing settles once the player stands: a surge probe goes the way they face now.
        if (arriving != null) {
            val faced = HopRules.snap8(MovePrediction.facingBearing())
            val planned = p.direction
            if (planned != null) {
                HopExplorer.noteSurgeFacing(p.edge, planned, faced == planned)
                if (faced != planned) {
                    println(
                        "[$owner] stopped for a probe facing " + (faced?.toString() ?: "off the 8 ways (${MovePrediction.facingBearing()})") +
                            ", not $planned (planned: the surge across ${HopEdges.describe(p.edge)})",
                    )
                }
            }
            arriving = faced
        }
        p = findProbe(route, stillAt, still, arriving, diver, aheadStill, surgeMin, diveMin) ?: run {
            println("[$owner] stopped for a probe at $still, but none fits from there")
            HopExplorer.noteStand(target, false)
            return false
        }
        if (stop()) {
            println("[$owner] probe from $still dropped: the traversal stopped while the player stood")
            HopExplorer.noteStand(target, false)
            return false
        }
        println("[$owner] $p")
        // No walk click around a probe: its start and landing should be as clean as they can be.
        val aim = p.aim
        val cast = if (p.ability == HopExplainer.SURGE) surge() else aim != null && diver != null && script.dive(aim, diver)
        HopExplorer.noteStand(target, cast)
        if (!cast) return false
        HopExplorer.noteProbe(p.edge)
        pending(p.ability, still, p.landingIfOpen, p.direction, aim, p.edge)
        return true
    }

    private fun findProbe(
        route: List<Tile>,
        nearest: Int,
        from: Tile,
        direction: Direction?,
        diver: Ability?,
        ahead: Tile,
        surgeMin: Int,
        diveMin: Int,
    ): HopExplorer.Probe? {
        val gain = { l: Tile -> RouteProgress.gain(from, l, ahead) }
        var p = if (direction != null) HopExplorer.surge(from, direction, gain, surgeMin) else null
        if (p == null && diver != null) p = HopExplorer.dive(from, route, nearest, HopRules.DIVE_MAX_AHEAD, HopRules.DIVE_AIM_MAX, gain, diveMin)
        return p
    }

    /** Walks on to [target] and waits until the server tile has held still for [STILL_MS]; null when it did not settle or [stop] latched. */
    private suspend fun stopAt(target: Tile, stop: () -> Boolean): Tile? {
        if (!TraversalSupport.walkTo(target, false)) return null
        TraversalSupport.noteWalkClick(target)
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        var last = TraversalSupport.serverTileOrNull()
        var since = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            delay(STOP_POLL_MS)
            if (stop()) return null
            val t = TraversalSupport.serverTileOrNull() ?: return null
            heading.add(t)
            if (t != last) {
                last = t
                since = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - since >= STILL_MS && !TraversalSupport.isMoving()) {
                return t
            }
        }
        return null
    }

    /**
     * Dive arms and waits a tick before it picks its tile: standing (no walk click since the last hop) that tick is
     * spent standing, so a walk click first keeps the player running through it.
     */
    private fun keepMovingWhileArming(walkClick: Tile?) {
        if (walkClick == null || TraversalSupport.walkClickSinceHop()) return
        if (TraversalSupport.walkTo(walkClick, TraversalSupport.rollMinimap())) {
            TraversalSupport.noteWalkClick(walkClick)
            println("[$owner] walking on to $walkClick while the dive arms")
        }
    }

    /** The leg wait breaks for a hop that has become usable (throttled; not while one is held). */
    private fun hopDue(): Boolean {
        if (!abilities.any() || flight.pending || HopFlight.flying != null) return false
        val now = System.currentTimeMillis()
        if (now < hopHoldUntil || now < nextAbilityCheck) return false
        if (abilities.surge && surgeOnBar() && usable(Ability.SURGE, surge = true)) return true
        val diver = HopCharges.diveOnBar()
        return abilities.dive && diver != null && usable(diver, surge = false)
    }

    private fun pending(kind: String, from: Tile?, predicted: Tile?, direction: Direction?, aim: Tile?, probeEdge: Long) {
        markSpent(kind, probeEdge)
        flight.begin(kind, from, predicted, direction, aim, probeEdge)
    }

    /** [kind] was just cast: spent, its reaction delay and kept spot reset (a probe keeps its spot), its chain waits ended. */
    private fun markSpent(kind: String, probeEdge: Long) {
        jointHoldSince = 0
        comboHoldGain = 0
        jointGreedy = false
        val now = System.currentTimeMillis()
        if (kind == HopExplainer.SURGE) {
            HopCharges.surgeSpent = !HopCharges.doubleSurge()
            HopCharges.surgeSpentAt = now
            surgeSentAt = now
            surgeUsableAt = 0
            lineUpUntil = 0
            if (probeEdge < 0) SURGE_KEEP.reset()
            HopFlight.chainUntil = 0
        } else {
            HopCharges.diveSpent = true
            HopCharges.diveSpentAt = now
            if (HopFlight.diveChainUntil != 0L) {
                HopFlight.diveChainUntil = 0
                HopFlight.chainUntil = 0
            }
            diveUsableAt = 0
            // A refused probe leaves the ability ready for the spot; one that lands puts it on cooldown, which resets it.
            if (probeEdge < 0) DIVE_KEEP.reset()
        }
    }

    private fun notePair(kind: String, mid: Tile, d: Direction?, aim: Tile?, end: Tile?, gain: Int, firstAt: Long, secondAt: Long) {
        flight.notePair(kind, mid, d, aim, end, secondAt)
        val surge = kind == HopExplainer.SURGE
        markSpent(kind, -1)
        ScheduleLearning.cast(surge, gain)
        println(
            "[$owner] client $kind sent with the " + (if (surge) "dive" else "surge") + " (combo): from its predicted landing $mid" +
                (if (surge) " facing $d" else " toward $aim") + ", predicted landing $end (gains $gain); clicks $firstAt to $secondAt ms into the tick",
        )
    }

    /**
     * True when the pair's first click would go out later than [startByMs] into the tick: nothing is cast this pass, and
     * the next check comes just after the next tick starts. At most once per [PAIR_HOLD_GAP_MS]; never without a tick.
     */
    private fun waitForTickStart(first: String, startByMs: Long): Boolean {
        val at = TraversalSupport.msIntoTickAtSend()
        val now = System.currentTimeMillis()
        if (at < 0 || now - pairHeldAt < PAIR_HOLD_GAP_MS) return false
        val inTick = at % TraversalSupport.TICK_MS
        if (inTick <= startByMs) return false
        val cur = TraversalSupport.msIntoTick()
        if (cur < 0) return false
        pairHeldAt = now
        pairTickHeld = true
        val wait = (at - inTick) + TraversalSupport.TICK_MS - cur + PAIR_TICK_START_MS
        nextAbilityCheck = now + wait
        println("[$owner] $first pair held $wait ms: its first click would go $inTick ms into the tick, too late for both clicks to make it")
        return true
    }

    /** Leg-wait check: the pending hop landed, ran out of time, or was found not cast. */
    private fun pendingHopLandedThrottled(): Boolean {
        if (!flight.pending) return HopFlight.missedAt > lastClickAt
        primeReactions()
        return flight.landed(TraversalSupport.serverTileOrNull()) || System.currentTimeMillis() - flight.at >= flight.giveUpMs()
    }

    /** During the burst, while a hop is in the air: the next ability's reaction is drawn in flight, so it goes on the landing. */
    private fun primeReactions() {
        val now = System.currentTimeMillis()
        if (now < nextPrime || !AccelerationPlanner.active()) return
        nextPrime = now + BOOSTED_CHECK_MS
        if (abilities.surge && surgeOnBar()) usable(Ability.SURGE, surge = true)
        val diver = HopCharges.diveOnBar()
        if (abilities.dive && diver != null) usable(diver, surge = false)
    }

    private fun progressFor(route: List<Tile>, nearest: Int, server: Tile): RouteProgress {
        val version = CollisionMap.snapshot().version
        val last = route.last()
        val cached = progressCache
        if (cached != null && progressKeySize == route.size && progressKeyNearest == nearest && progressKeyVersion == version && last == progressKeyLast) return cached
        return RouteProgress.of(route, nearest, server).also {
            progressCache = it
            progressKeySize = route.size
            progressKeyNearest = nearest
            progressKeyVersion = version
            progressKeyLast = last
        }
    }

    // ---- Powerburst of acceleration ----

    /**
     * Drinks a Powerburst of acceleration when [AccelerationPlanner.evaluate] says the next 6 s of hops save enough:
     * checked once a second, with the potion in the backpack, neither the burst nor its cooldown running, walking, and
     * Surge and Dive both spent. The projection is logged either way.
     */
    private fun maybeDrinkAcceleration(route: List<Tile>, nearest: Int, server: Tile): Boolean {
        if (!abilities.accelerationPotion || flight.pending) return false
        val now = System.currentTimeMillis()
        if (now < nextBurstCheck) return false
        nextBurstCheck = now + BURST_CHECK_MS
        // A route that ends close by holds the dose unless the script says the walking goes on past it.
        val beyondMs = WalkOutlook.continuesMs()
        if (route.size - nearest < BURST_MIN_ROUTE_TILES && beyondMs < AccelerationPlanner.BURST_MS) return holdBurst("the route ends within $BURST_MIN_ROUTE_TILES tiles", false)
        val surge = abilities.surge && surgeOnBar()
        val diver = HopCharges.diveOnBar()
        val diveOk = abilities.dive && diver != null
        if (!surge && !diveOk) return holdBurst("neither Surge nor Dive is usable from the action bar", false)
        AccelerationPlanner.notDrinkable()?.let { return holdBurst(it, false) }
        if (!TraversalSupport.isMoving()) return holdBurst("not walking", false)
        // The dose resets both cooldowns: drunk with one still ready, that hop is thrown away.
        if (surge && HopCharges.cooldownLeftMs(Ability.SURGE, true) == 0L) return holdBurst("Surge is off cooldown: use it first", false)
        if (diver != null && diveOk && HopCharges.cooldownLeftMs(diver, false) == 0L) return holdBurst("${diver.name} is off cooldown: use it first", false)
        val anyway = (if (surge) anywayHops(Ability.SURGE, true) else 0.0) + (if (diver != null && diveOk) anywayHops(diver, false) else 0.0)
        var bearing = heading.heading()
        if (bearing < 0) bearing = MovePrediction.bearing(server, route[min(route.size - 1, nearest + BURST_FACING_AHEAD)])
        val charges = if (surge && HopCharges.doubleSurge()) 2 else 1
        val kit = AccelerationPlanner.Kit(surge, diveOk, charges, anyway, stepLimit, beyondMs / 1000.0)
        val why = StringBuilder()
        val e = AccelerationPlanner.evaluate(route, nearest, server, HopModel.fromBearing(bearing), kit, why) ?: return holdBurst(why.toString(), true)
        if (!AccelerationPlanner.drink()) {
            println("[$owner] Powerburst of acceleration: drink click failed at $server")
            return false
        }
        println(
            "[$owner] drank Powerburst of acceleration at $server: projected ${e.hops} hops gaining ${e.steps} steps (${e.plan}), saves ~" +
                "${HopRules.fmt(e.savingS)} s (less ${HopRules.fmt(anyway)} hops that come back anyway)",
        )
        surgeUsableAt = 0
        diveUsableAt = 0
        // The dose resets both cooldowns: a hop cast just before is not spent any more.
        HopCharges.surgeSpent = false
        HopCharges.diveSpent = false
        return true
    }

    /**
     * The hops a dose costs [ability] when it comes off cooldown inside the burst: a hop cast in the burst keeps the
     * 1.2 s cooldown, so it is ready when the burst ends anyway. That time over the full cooldown, a fraction of a hop.
     */
    private fun anywayHops(ability: Ability, surge: Boolean): Double {
        val left = HopCharges.cooldownLeftMs(ability, surge)
        if (left == Long.MAX_VALUE) return 0.0
        val back = left + HopRules.READY_DELAY_MEAN_MS
        if (back >= AccelerationPlanner.BURST_MS) return 0.0
        val full = HopCharges.fullCooldownMs(ability)
        return if (full <= 0) 1.0 else min(1.0, (AccelerationPlanner.BURST_MS - back) / full.toDouble())
    }

    /** Logs why the dose was held, at most every [BURST_LOG_MS]; a reason that does not change ([varying] false) only when new. */
    private fun holdBurst(why: String, varying: Boolean): Boolean {
        val kind = if (varying) "estimate" else why
        val now = System.currentTimeMillis()
        if ((varying || kind != lastBurstWhy) && now >= nextBurstLog) {
            nextBurstLog = now + BURST_LOG_MS
            lastBurstWhy = kind
            println("[$owner] acceleration potion held: $why")
        }
        return false
    }

    /**
     * Polled by the leg wait: keeps the teleport detector and the heading fed, and makes the Powerburst decision on the
     * move. True when a dose was drunk: the drink may stop the walk, so the next pass clicks again.
     */
    private fun observeWhileWaiting(route: List<Tile>): Boolean {
        val server = TraversalSupport.serverTileOrNull() ?: return false
        LocalNav.observe(server)
        heading.add(server)
        if (!abilities.accelerationPotion) return false
        if (!maybeDrinkAcceleration(route, RoutePicks.nearestFrom(route, server, progress), server)) return false
        lastClick = null
        TraversalSupport.forgetWalkClick()
        nextClick = 0
        return true
    }

    // ---- logs ----

    /** [Step.FALLBACK], logged when [kind] changes, else every [FALLBACK_LOG_MS]: the keypoint walk casts nothing. */
    private fun fallback(kind: String, detail: String): Step {
        val now = System.currentTimeMillis()
        if (kind != lastWalkWhy || now >= nextWalkWhyLog) {
            nextWalkWhyLog = now + FALLBACK_LOG_MS
            println("[$owner] live-map walk off ($kind" + (if (detail.isEmpty()) "" else "; $detail") + "): keypoint walk, no Surge/Dive/Powerburst")
        }
        lastWalkWhy = kind
        return Step.FALLBACK
    }

    private fun castHeld(why: String): Boolean {
        lastCastWhy = why
        lastCastWhyAt = System.currentTimeMillis()
        return false
    }

    /** Logs the hop state when an enabled Surge or Dive has read off cooldown for [IDLE_HOP_LOG_MS] while walking with no hop. */
    private fun watchIdleHops(server: Tile?) {
        if (!abilities.any() || server == null) return
        val now = System.currentTimeMillis()
        if (now < nextIdleCheck) return
        // Not stepped for a while (a leg wait): hops may have been cast or on cooldown since the last look.
        if (now - nextIdleCheck > IDLE_SAMPLE_GAP_MS) hopsIdleSince = 0
        // Any hop cast since the stretch began ends it, even with an ability still ready after it.
        if (max(HopCharges.surgeSpentAt, HopCharges.diveSpentAt) > hopsIdleSince) hopsIdleSince = 0
        nextIdleCheck = now + IDLE_CHECK_MS
        val diver = HopCharges.diveOnBar()
        val surgeIdle = abilities.surge && surgeOnBar() && Ability.SURGE.offCdIgnoreGCD
        val diveIdle = abilities.dive && diver != null && diver.offCdIgnoreGCD
        if (!(surgeIdle || diveIdle) || flight.pending || !TraversalSupport.isMoving()) {
            hopsIdleSince = 0
            return
        }
        if (hopsIdleSince == 0L) hopsIdleSince = now
        if (now - hopsIdleSince < IDLE_HOP_LOG_MS || now < nextIdleLog) return
        nextIdleLog = now + IDLE_HOP_REPEAT_MS
        val inAir = HopFlight.flying
        println(
            "[$owner] hops idle ${now - hopsIdleSince} ms while walking at $server: Surge ${abilityState(Ability.SURGE, true, abilities.surge, surgeOnBar(), now)}; " +
                "${diver?.name ?: "Dive"} ${abilityState(diver, false, abilities.dive, diver != null, now)}; walk " +
                (if (lastWalkWhy == null) "live map" else "keypoints ($lastWalkWhy)") + "; last cast check: $lastCastWhy" +
                (if (lastCastWhyAt == 0L) "" else " (${now - lastCastWhyAt} ms ago)") +
                (if (inAir == null) "" else "; in the air: ${inAir.kind} from ${inAir.owner}" + (if (inAir === flight) "" else " (another walker)")) +
                "; facing ${MovePrediction.facingBearing()} (${heading.source})",
        )
    }

    private fun abilityState(ability: Ability?, surge: Boolean, enabled: Boolean, onBar: Boolean, now: Long): String {
        if (!enabled) return "disabled for this walk"
        if (!onBar || ability == null) return "not on the action bar"
        val cd = runCatching { ability.type.cooldownMsIgnoreGCD() }.getOrDefault(0L)
        if (cd > TraversalSupport.TICK_MS) return "cooldown $cd ms"
        if (if (surge) HopCharges.surgeSpent else HopCharges.diveSpent) {
            return "ready, spent flag ${now - (if (surge) HopCharges.surgeSpentAt else HopCharges.diveSpentAt)} ms old"
        }
        val at = if (surge) surgeUsableAt else diveUsableAt
        if (at == 0L) return "ready, reaction delay not drawn"
        if (now < at) return "ready, reaction delay ${at - now} ms left"
        val k = if (surge) SURGE_KEEP else DIVE_KEEP
        val spot = k.spot
        return if (spot != null && !k.greedy) "ready, kept for $spot ${now - k.since} ms" else "ready"
    }

    private fun serverTriedAt(server: Tile): Boolean {
        val tried = serverTriedFrom
        return tried != null && tried.plane == server.plane && tried.getLongestDelta(server) <= SERVER_TRIED_TILES
    }

    private fun earlyStartReady(node: TraversalNode, server: Tile): Boolean {
        nextEarlyCheck = System.currentTimeMillis() + EARLY_CHECK_MS
        // Live only: the next node's object or door must not be judged on static data.
        val early = node.next as? EarlyStart ?: return false
        return LocalNav.trustedLive(server) && early.readyFrom(server)
    }

    private fun earlyStartReadyThrottled(node: TraversalNode): Boolean {
        if (node.next !is EarlyStart || System.currentTimeMillis() < nextEarlyCheck) return false
        if ((node as? WalkEquivalent)?.holdsStop() == true) return false
        val s = TraversalSupport.serverTileOrNull() ?: return false
        return earlyStartReady(node, s)
    }

    /** Forgets the leg state. */
    fun reset() {
        progress = 0
        lastClick = null
        nextClick = 0
        lastThrough = null
        stuck.reset()
    }

    /** One ability kept for a spot ahead, shared by every walker; reset when the ability is cast or seen on cooldown. */
    private class Keep {
        var spot: Tile? = null
        var since = 0L

        /** The plan failed this stretch (spot passed, kept a whole cycle): first-good-enough until it is spent. */
        var greedy = false
        var otherReadyAt = 0L

        /** [spot] is on a walk past an interaction. */
        var beyond = false
        var keptGain = 0
        var keptBeyond = false
        var keptProbe = false

        /** When the spot was last seen ahead of the player. */
        var aheadAt = 0L
        var logged: Tile? = null
        var nextLog = 0L

        fun reset() {
            spot = null
            since = 0
            greedy = false
            otherReadyAt = 0
            beyond = false
            keptGain = 0
            keptBeyond = false
            keptProbe = false
            aheadAt = 0
            logged = null
        }
    }

    companion object {
        const val FAR_OFF_ROUTE = 30
        const val EARLY_CHECK_MS = 300L
        const val ABILITY_CHECK_MS = 300L
        const val BOOSTED_CHECK_MS = 100L
        const val BOOSTED_DELAY_MEAN_MS = 150L
        const val BOOSTED_DELAY_MIN_MS = 60L
        const val BOOSTED_DELAY_MAX_MS = 300L
        const val BURST_CHECK_MS = 1_000L
        const val BURST_LOG_MS = 5_000L
        const val HOP_HOLD_MS = 600L
        const val HOLD_LOG_MS = 3_000L
        const val FALLBACK_LOG_MS = 10_000L
        const val IDLE_HOP_LOG_MS = 5_000L
        const val IDLE_HOP_REPEAT_MS = 10_000L
        const val IDLE_SAMPLE_GAP_MS = 1_500L
        const val MISSED_RETRY_MS = 6_000L
        const val FAR_HOLD_IDLE_MS = 8_000L
        const val FAR_HOLD_ATTACKED_MS = 4_800L
        const val JUMP_SETTLE_MS = 2_000L

        /** A stuck walk hands over to the closed door after it only when the tile it cannot reach is this near the door. */
        const val DOOR_STUCK_TILES = EarlyStart.MAX_STEPS
        const val KEPT_SOON_TILES = 4
        const val COMBO_HERE_MAX_OFF = 2

        /** Route tiles ahead whose Dive -> Surge combos are searched over every dive, so a combo a few steps on can be held for. */
        const val COMBO_WIDE_AHEAD = 6
        const val PROBE_SCAN_TILES = 40
        const val PROBE_STOP_MIN_STEPS = 3
        const val PROBE_STOP_MAX_STEPS = 6
        const val STOP_TIMEOUT_MS = 4_000L
        const val STILL_MS = 700L
        const val LINE_UP_MS = 1_200L

        /**
         * A surge whose click goes out this far into the server tick fires from the server tile; later it may fire a tick
         * on. The phase is the one at the click ([TraversalSupport.msIntoTickAtSend]).
         */
        const val EARLY_CAST_MS = 375L

        /** A dive lands this long after its cast: a surge back by then can be chained. */
        const val CHAIN_READY_MS = 800L

        /** Both hops' clicks must reach the server by about 450-480 ms into the tick: the combo's first goes out by this. */
        const val DIVE_FIRST_START_MS = 350L
        const val SURGE_FIRST_START_MS = 350L
        const val PAIR_HOLD_GAP_MS = 1_500L
        const val PAIR_TICK_START_MS = 10L

        /** Double Surge's second charge one tick after the first does not move the player: a combo's surge waits this long. */
        const val SURGE_REPEAT_MS = 1_100L

        const val PROPERTY_SAME_TICK_PAIRS = "projectx.sameTickPairs"
        const val ENV_SAME_TICK_PAIRS = "RS3_SAME_TICK_PAIRS"

        /** A pair's second hop goes straight after the first; off, the chain waits for the first's landing. */
        val SAME_TICK_PAIRS: Boolean =
            !"false".equals(System.getProperty(PROPERTY_SAME_TICK_PAIRS, "true"), ignoreCase = true) && System.getenv(ENV_SAME_TICK_PAIRS) != "0"

        private const val DUPLICATE_CLICK_MS = 1_500L
        private const val SHARED_CLICK_MOVING_MS = 3_000L
        private const val ATTACKED_WITHIN_MS = 5_000L
        private const val FAR_HOLD_PASS_GAP_MS = 1_500L
        private const val JUMP_PASS_GAP_MS = 2_000L
        private const val SERVER_TRIED_TILES = 2
        private const val IDLE_CHECK_MS = 1_000L
        private const val BURST_MIN_ROUTE_TILES = 30
        private const val BURST_FACING_AHEAD = 5
        private const val LINE_UP_MIN_TILES = 3
        private const val LEG_SPOTS_CACHE_MAX = 8
        private const val STOP_POLL_MS = 100L
        private const val READY_DELAY_SD_MS = 450
        private const val BOOSTED_DELAY_SD_MS = 60
        private const val WITHIN_TICK_PREFIX = 15
        private const val TICK_RAN_ON = "the tick ran on while it was planned"
        private const val PAIR_HELD = "held with its dive for the next tick's start (sent together then, they land in one tick)"
        private const val CAST_FAILED = "the cast failed"
        private const val DIVE_PAIR_HELD = "held with its surge for the next tick's start"

        /** The order the 8 ways are tried in: the first of equal line-ups wins. */
        private val EIGHT_WAYS = listOf(
            Direction.WEST, Direction.SOUTH, Direction.EAST, Direction.NORTH,
            Direction.NORTH_WEST, Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_EAST,
        )

        private val SURGE_KEEP = Keep()
        private val DIVE_KEEP = Keep()

        /** When the current off-cooldown stretch of each ability may first be used (0: not drawn); shared by every walker. */
        @Volatile
        private var surgeUsableAt = 0L

        @Volatile
        private var diveUsableAt = 0L

        @Volatile
        private var surgeSentAt = 0L

        @Volatile
        private var jointHoldSince = 0L

        @Volatile
        private var comboHoldGain = 0

        /** A combo hold ran a whole cooldown: no joint plan until a hop goes. */
        @Volatile
        private var jointGreedy = false

        @Volatile
        private var lastWalker: RouteWalker? = null

        @Volatile
        private var lastWalkerStepAt = 0L

        /** Milliseconds until Surge (every charge) and Dive on the action bar are all back off cooldown. */
        fun msUntilHopsRecharged(): Long = HopCharges.msUntilRecharged()

        private fun surgeOnBar(): Boolean = runCatching { actionbarAbilities[Ability.SURGE.type] != null }.getOrDefault(false)

        /**
         * True once [ability] is off cooldown and has been for its human-like delay, drawn once per off-cooldown stretch.
         * A hop just sent reads spent until the readout shows its cooldown, for at most [HopCharges.SPENT_MAX_MS].
         */
        private fun usable(ability: Ability, surge: Boolean): Boolean {
            val now = System.currentTimeMillis()
            if (if (surge) HopCharges.surgeSpent else HopCharges.diveSpent) {
                val fresh = now - (if (surge) HopCharges.surgeSpentAt else HopCharges.diveSpentAt) < HopCharges.SPENT_MAX_MS
                if (fresh && ability.offCdIgnoreGCD) return false
                if (surge) HopCharges.surgeSpent = false else HopCharges.diveSpent = false
            }
            if (!ability.offCdIgnoreGCD) {
                if (surge) surgeUsableAt = 0 else diveUsableAt = 0
                (if (surge) SURGE_KEEP else DIVE_KEEP).reset()
                return false
            }
            var at = if (surge) surgeUsableAt else diveUsableAt
            val boosted = AccelerationPlanner.active()
            // During the burst the cooldowns are 1.2 s: the reaction is short, still drawn and varied.
            if (at == 0L || boosted && at - now > BOOSTED_DELAY_MAX_MS) {
                val delay = if (boosted) {
                    nonProfiledGaussian(BOOSTED_DELAY_MEAN_MS.toInt(), BOOSTED_DELAY_SD_MS * BOOSTED_DELAY_SD_MS).toLong().coerceIn(BOOSTED_DELAY_MIN_MS, BOOSTED_DELAY_MAX_MS)
                } else {
                    nonProfiledGaussian(HopRules.READY_DELAY_MEAN_MS.toInt(), READY_DELAY_SD_MS * READY_DELAY_SD_MS).toLong()
                        .coerceIn(HopRules.READY_DELAY_MIN_MS, HopRules.READY_DELAY_MAX_MS)
                }
                at = now + delay
                if (surge) surgeUsableAt = at else diveUsableAt = at
            }
            return now >= at
        }

        /** Surge can follow a dive cast now: off cooldown, or during the burst back by the dive's landing. */
        private fun surgeBackByLanding(): Boolean {
            if (Ability.SURGE.offCdIgnoreGCD) return true
            return AccelerationPlanner.active() && runCatching { Ability.SURGE.type.cooldownMsIgnoreGCD() }.getOrDefault(Long.MAX_VALUE) <= CHAIN_READY_MS
        }

        private fun surgeTooSoon(): Boolean = System.currentTimeMillis() - surgeSentAt < SURGE_REPEAT_MS

        /** How many steps on the walk a surge cast now may fire from: 0 when its click goes early in the tick. */
        private fun surgeReach(): Int {
            val ms = TraversalSupport.msIntoTickAtSend()
            return if (ms in 0 until EARLY_CAST_MS) 0 else ServerWalk.TICK_STEPS
        }

        private fun inTick(ms: Long): Long = if (ms < 0) -1 else ms % TraversalSupport.TICK_MS

        /** Surge or Dive is kept for a spot within [KEPT_SOON_TILES] ahead on this route. */
        private fun keptSoon(route: List<Tile>, nearest: Int): Boolean {
            for (k in arrayOf(SURGE_KEEP, DIVE_KEEP)) {
                val spot = k.spot ?: continue
                if (k.greedy || k.beyond) continue
                val at = RoutePicks.routeIndexOf(route, nearest, spot)
                if (at > nearest && at <= nearest + KEPT_SOON_TILES) return true
            }
            return false
        }

        /** What a surge from route index [nearest] gains now (the route's own surge from the tile); 0 when none goes. */
        private fun surgeGainNow(route: List<Tile>, nearest: Int, progress: RouteProgress): Int {
            val s0 = HopSchedule.surgeFrom(route, nearest, progress) ?: return 0
            return max(0, progress.gain(route[nearest], s0))
        }

        private fun nearServer(t: Tile): Boolean {
            val s = TraversalSupport.serverTileOrNull()
            return s != null && s.plane == t.plane && s.getLongestDelta(t) <= RoutePicks.LEG_NEAR
        }

        /** The first route tile after [nearest] that is [PROBE_STOP_MIN_STEPS]..[PROBE_STOP_MAX_STEPS] walking steps from [server], or -1. */
        fun stopIndexAhead(route: List<Tile>, nearest: Int, server: Tile): Int {
            for (i in nearest + 1 until min(route.size, nearest + 4 * PROBE_STOP_MAX_STEPS)) {
                val t = route[i]
                if (t.plane != server.plane) return -1
                val d = LocalNav.distance(server, t, PROBE_STOP_MAX_STEPS)
                if (d in PROBE_STOP_MIN_STEPS..PROBE_STOP_MAX_STEPS) return i
            }
            return -1
        }

        /** Every step of [walk] from its first tile goes [d]. */
        fun straight(walk: List<Tile>, d: Direction): Boolean {
            for (i in 1 until walk.size) {
                val a = walk[i - 1]
                val b = walk[i]
                if (b.x - a.x != d.delta.x || b.y - a.y != d.delta.y) return false
            }
            return true
        }

        /**
         * Why a surge sent with a dive might not go [chain] from [landing]: fired from another tile of [onWalk] the dive may
         * land elsewhere or leave another 8-way facing. Null when every fire tile agrees.
         */
        fun pairFacingUnsure(onWalk: List<Tile>?, server: Tile, aim: Tile, landing: Tile, chain: Direction): String? {
            for (t in onWalk ?: listOf(server)) {
                val l = MovePrediction.diveLanding(t, aim)
                if (l == null || l != landing) return "fired from $t the dive lands $l, not $landing"
                val f = HopModel.facing(l.x - t.x, l.y - t.y)
                if (f != chain) return "fired from $t the dive leaves the player facing $f, not $chain"
            }
            return null
        }

        /** The tile a stuck walk cannot reach is at the closed door after it, so the door is what blocks it. */
        fun atDoor(ahead: Tile?, door: DoorNode): Boolean {
            if (ahead == null) return false
            return door.routeTiles().any { it.plane == ahead.plane && it.getLongestDelta(ahead) <= DOOR_STUCK_TILES }
        }

        /** What sent the player to [node]'s walk ([ArrivalWatch.key]). */
        fun arrivalKey(node: TraversalNode?): String = ArrivalWatch.key(node)

        /** [own] plus the tiles of the passable, walk-through nodes after [node]. */
        fun extend(own: List<Tile>, node: TraversalNode): List<Tile> {
            var out: MutableList<Tile>? = null
            var k = node.next
            var hops = 0
            while (k != null && hops < TraversalSupport.MAX_THROUGH_NODES) {
                val walk = k as? WalkEquivalent ?: break
                if (!walk.walkThrough() || !walk.passableNow()) break
                val tiles = walk.routeTiles()
                if (tiles.isEmpty()) break
                if (out == null) out = ArrayList(own)
                out += tiles
                k = k.next
                hops++
            }
            return out ?: own
        }

        private fun describeThrough(node: TraversalNode, extraIndex: Int): String {
            val sb = StringBuilder()
            var hops = 0
            var covered = 0
            var k = node.next
            while (k != null && hops < TraversalSupport.MAX_THROUGH_NODES && covered <= extraIndex) {
                val walk = k as? WalkEquivalent ?: break
                if (sb.isNotEmpty()) sb.append(" -> ")
                sb.append(k)
                covered += walk.routeTiles().size
                k = k.next
                hops++
            }
            return sb.toString()
        }
    }
}
