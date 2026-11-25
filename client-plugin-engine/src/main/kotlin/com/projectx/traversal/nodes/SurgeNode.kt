package com.projectx.traversal.nodes

import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.surge
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.hops.HopExplainer
import com.projectx.traversal.hops.HopModel
import com.projectx.traversal.hops.HopRecorder
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile
import kotlin.math.abs

/**
 * A planner-placed Surge toward [target], walking when Surge is unusable or would not help. A cast's result (a move, or
 * the blocked chat) is read on later passes, not waited for. A blocked cast means the route is wrong about the terrain,
 * so the walk that replaces it ends in a re-route.
 *
 * With a trusted live map the landing is predicted from the player's facing first: cast only when it lands near the
 * target or well along the way; when a step toward the target, or onto the planner's cast tile [from], would line the
 * surge up, that step is walked first. A cast that left the player away from the target is a miss, and the node walks.
 */
class SurgeNode(
    private val from: Tile?,
    private val target: Tile,
    private val useAbility: Boolean,
    private val customReached: (() -> Boolean)? = null,
) : TraversalNode(), WalkEquivalent {

    constructor(target: Tile, useAbility: Boolean, customReached: (() -> Boolean)? = null) : this(null, target, useAbility, customReached)

    private var nextAttempt = 0L
    private var disabledUntil = 0L
    private var castTime = 0L
    private var castFrom: Tile? = null
    private var predicted: Tile? = null
    private var deadCasts = 0
    private var faceSteps = 0
    private var repathAfterWalk = false
    private var loggedWalk = false
    private var hopRecord: HopRecorder.Pending? = null

    private enum class Kind { CAST, FACE, WALK }

    private class Plan(val kind: Kind, val landing: Tile? = null, val faceTile: Tile? = null)

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        val now = System.currentTimeMillis()
        if (now < nextAttempt) return true

        if (castTime != 0L) {
            val blocked = RecentChat.findSince(BLOCKED_MSG, castTime) != null
            val moved = !blocked && TraversalSupport.serverMovedFrom(castFrom)
            if (!blocked && !moved && now - castTime < CAST_RESULT_TIMEOUT_MS) return true
            castTime = 0
            hopRecord?.let {
                when {
                    moved -> it.finish(TraversalSupport.serverTileOrNull(), HopExplainer.LANDED)
                    blocked -> it.finish(castFrom, HopExplainer.LANDED, BLOCKED_MSG)
                    else -> it.finish(null, HopExplainer.NO_CAST)
                }
                hopRecord = null
            }
            if (moved) {
                val landed = TraversalSupport.serverTileOrNull()
                if (predicted != null) println("[SurgeNode] predicted landing $predicted, landed $landed (target $target)")
                if (landed == null || landed.plane == target.plane && landed.getLongestDelta(target) <= MISS_DISTANCE) return false
                println("[SurgeNode] surge missed $target - walking on from $landed")
                disabledUntil = now + DISABLE_MS
            } else {
                if (!blocked && ++deadCasts < MAX_DEAD_CASTS) {
                    nextAttempt = now + TraversalSupport.TICK_MS
                    return true
                }
                deadCasts = 0
                disabledUntil = now + DISABLE_MS
                if (blocked) repathAfterWalk = true
            }
        } else if (useAbility && now >= disabledUntil) {
            val plan = LocalNav.withStatic { plan() }
            if (plan.kind == Kind.CAST) {
                castFrom = TraversalSupport.serverTileOrNull()
                predicted = plan.landing
                if (surge()) {
                    castTime = now
                    val bearing = MovePrediction.facingBearing()
                    hopRecord = HopRecorder.begin(HopExplainer.SURGE, castFrom, if (bearing < 0) null else HopModel.fromBearing(bearing.toDouble()), null)
                    return true
                }
            } else if (plan.kind == Kind.FACE && faceSteps < MAX_FACE_STEPS) {
                faceSteps++
                val face = plan.faceTile
                if (face != null && TraversalSupport.walkTo(face, false)) {
                    println("[SurgeNode] lining up toward $target first: stepping to $face")
                    nextAttempt = now + TraversalSupport.TICK_MS + FACE_SETTLE_MS
                    return true
                }
            } else if (!loggedWalk) {
                loggedWalk = true
                println("[SurgeNode] surge would land at ${plan.landing}, not toward $target on the live map - walking instead")
            }
        }

        if (!TraversalSupport.walkTo(target, false)) {
            nextAttempt = now + 2 * TraversalSupport.TICK_MS
            return true
        }
        val started = TraversalSupport.awaitWalk(TraversalSupport.WALK_START_TIMEOUT_MS, WALK_STOP_TIMEOUT_MS) {
            traversalStopRequested() || reached(script)
        }
        if (traversalStopRequested()) return false
        if (!started) return true
        if (repathAfterWalk) {
            repathAfterWalk = false
            requestTraversalRepath()
        }
        return false
    }

    /** Untrusted map or unknown facing: cast as before, the chat check still catches a block. */
    private fun plan(): Plan {
        val s = TraversalSupport.serverTileOrNull()
        val facing = MovePrediction.facingBearing()
        if (s == null || facing < 0 || s.plane != target.plane || !LocalNav.trusted(s)) return Plan(Kind.CAST)
        val landing = MovePrediction.surgeLanding(s, facing.toDouble())
        if (MovePrediction.helps(s, landing, target)) return Plan(Kind.CAST, landing)

        // The planner's own cast tile, a few steps off: the player may stand beside the lane the surge was planned along.
        if (from != null && from != s && from.plane == s.plane) {
            val d = LocalNav.distance(s, from, MAX_LINE_UP_STEPS)
            val fromLanding = MovePrediction.surgeLanding(from, MovePrediction.bearing(from, target))
            if (d > 0 && MovePrediction.helps(from, fromLanding, target)) return Plan(Kind.FACE, fromLanding, from)
        }

        val want = HopModel.fromBearing(MovePrediction.bearing(s, target)) ?: return Plan(Kind.WALK, landing)
        val step = Tile.of(s.x + want.delta.x, s.y + want.delta.y, s.plane)
        val wantBearing = HopModel.bearing(want)
        val off = abs(((wantBearing - facing) % 360 + 540) % 360 - 180)
        if (off > FACE_TOLERANCE_DEG && LocalNav.rule().canStep(s.x, s.y, s.plane, want)) {
            val after = MovePrediction.surgeLanding(step, wantBearing)
            if (MovePrediction.helps(step, after, target)) return Plan(Kind.FACE, after, step)
        }
        return Plan(Kind.WALK, landing)
    }

    override fun reached(script: Script): Boolean =
        customReached?.invoke() ?: TraversalSupport.within(TraversalSupport.serverTileOrNull(), target, 2)

    override fun routeTiles(): List<Tile> = listOf(target)

    override fun passableNow(): Boolean = true

    override fun walkThrough(): Boolean = false

    override fun copy(): TraversalNode = SurgeNode(from, target, useAbility, customReached)

    override fun toString(): String = "[Surge -> $target]"

    private companion object {
        const val BLOCKED_MSG = HopFlight.SURGE_REFUSED_MSG
        const val DISABLE_MS = 10_000L
        const val CAST_RESULT_TIMEOUT_MS = 3_500L
        const val WALK_STOP_TIMEOUT_MS = 8_000L

        /** Bounds re-casting when the blocked chat never arrives. */
        const val MAX_DEAD_CASTS = 3
        const val MAX_FACE_STEPS = 2
        const val MAX_LINE_UP_STEPS = 3
        const val MISS_DISTANCE = 3
        const val FACE_SETTLE_MS = 150L
        const val FACE_TOLERANCE_DEG = 22.0
    }
}
