package com.projectx.traversal.nodes

import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.dive
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.hops.HopExplainer
import com.projectx.traversal.hops.HopRecorder
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile

/**
 * A planner-placed Dive or Bladed Dive toward [target], on [SurgeNode]'s flow. With a trusted live map the landing is
 * checked first ([MovePrediction.diveClear]); when the planned tile fails, the furthest tile of the next walk that
 * passes and helps is aimed at instead, and when none does the node walks without casting.
 */
class DiveNode(
    private val target: Tile,
    private val useAbility: Boolean,
    private val customReached: (() -> Boolean)? = null,
) : TraversalNode(), WalkEquivalent {
    private var nextAttempt = 0L
    private var disabledUntil = 0L
    private var castTime = 0L
    private var castFrom: Tile? = null

    /** Where the last cast aimed: the planned target, or a replacement. */
    private var aimed: Tile? = null
    private var deadCasts = 0
    private var repathAfterWalk = false
    private var loggedWalk = false
    private var hopRecord: HopRecorder.Pending? = null

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        val now = System.currentTimeMillis()
        if (now < nextAttempt) return true

        if (castTime != 0L) {
            val blocked = RecentChat.findSince(BLOCKED_MSG, castTime) != null
            if (!blocked) {
                if (TraversalSupport.serverMovedFrom(castFrom)) {
                    hopRecord?.finish(TraversalSupport.serverTileOrNull(), HopExplainer.LANDED)
                    hopRecord = null
                    println("[DiveNode] aimed $aimed, landed ${TraversalSupport.serverTileOrNull()} (target $target)")
                    return false
                }
                if (now - castTime < CAST_RESULT_TIMEOUT_MS) return true
            }
            castTime = 0
            hopRecord?.let {
                if (blocked) it.finish(null, HopExplainer.REFUSED, BLOCKED_MSG) else it.finish(null, HopExplainer.NO_CAST)
                hopRecord = null
            }
            if (!blocked && ++deadCasts < MAX_DEAD_CASTS) {
                nextAttempt = now + TraversalSupport.TICK_MS
                return true
            }
            deadCasts = 0
            disabledUntil = now + DISABLE_MS
            if (blocked) {
                println("[DiveNode] blocked diving to $aimed - the live map said it was clear")
                repathAfterWalk = true
            }
        } else if (useAbility && now >= disabledUntil) {
            val aim = LocalNav.withStatic { aimTile() }
            val diver = HopCharges.diveOnBar()
            if (aim != null && diver != null) {
                castFrom = TraversalSupport.serverTileOrNull()
                aimed = aim
                if (script.dive(aim, diver)) {
                    castTime = now
                    hopRecord = HopRecorder.begin(HopExplainer.DIVE, castFrom, null, aim)
                    return true
                }
            } else if (aim == null && !loggedWalk) {
                loggedWalk = true
                println("[DiveNode] no clear dive toward $target on the live map - walking instead")
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

    /** The tile to dive to, or null to walk. Untrusted map: the planned target, as before. */
    private fun aimTile(): Tile? {
        val s = TraversalSupport.serverTileOrNull()
        if (s == null || !LocalNav.trusted(s)) return target
        if (MovePrediction.diveClear(s, target)) return target
        val ahead = (next as? WalkEquivalent)?.routeTiles() ?: return null
        for (c in ahead.asReversed()) {
            if (MovePrediction.diveClear(s, c) && MovePrediction.helps(s, c, target)) {
                println("[DiveNode] $target is not a clear dive on the live map - diving to $c instead")
                return c
            }
        }
        return null
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        val t = TraversalSupport.serverTileOrNull()
        return TraversalSupport.within(t, target, 1) || TraversalSupport.within(t, aimed, 1)
    }

    override fun routeTiles(): List<Tile> = listOf(target)

    override fun passableNow(): Boolean = true

    override fun walkThrough(): Boolean = false

    override fun copy(): TraversalNode = DiveNode(target, useAbility, customReached)

    override fun toString(): String = "[Dive -> $target]"

    private companion object {
        const val BLOCKED_MSG = HopFlight.DIVE_REFUSED_MSG
        const val DISABLE_MS = 10_000L
        const val CAST_RESULT_TIMEOUT_MS = 3_500L
        const val WALK_STOP_TIMEOUT_MS = 8_000L
        const val MAX_DEAD_CASTS = 3
    }
}
