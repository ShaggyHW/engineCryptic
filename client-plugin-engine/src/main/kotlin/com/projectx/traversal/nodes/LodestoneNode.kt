package com.projectx.traversal.nodes

import com.projectx.script.Script
import com.projectx.script.api.AttackMemory
import com.projectx.script.api.Lodestone
import com.projectx.script.api.LodestoneResult
import com.projectx.script.api.lodestoneTeleport
import com.projectx.traversal.TraversalNode

/** Teleports to a lodestone; the teleport blocks through the landing, a broken cast or a stop. */
class LodestoneNode(
    private val lodestone: Lodestone,
    private val customReached: (() -> Boolean)? = null
) : TraversalNode() {

    private var nextClick: Long = 0

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (System.currentTimeMillis() < nextClick) return true

        if (atLodestone()) {
            // Landed: let the arrival animation finish rather than teleport again.
            TraversalSupport.delayUntil(ARRIVAL_ANIMATION_MS) { traversalStopRequested() || !TraversalSupport.isAnimating() }
        } else if (script.lodestoneTeleport(lodestone) { traversalStopRequested() } == LodestoneResult.INTERRUPTED &&
            AttackMemory.attackedRecently()
        ) {
            // Casting again a moment later walks into the same hits; the caller deals with the attacker. The combat
            // flag alone does not count: a lodestone casts in combat.
            failTraversal("Lodestone teleport to ${lodestone.name} interrupted while under attack")
            return false
        }
        nextClick = System.currentTimeMillis() + RETRY_PACING_MS
        return true
    }

    private fun atLodestone(): Boolean = TraversalSupport.within(TraversalSupport.serverTileOrNull(), lodestone.tile, ARRIVED_TILES)

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        return atLodestone() && !TraversalSupport.isAnimating()
    }

    override fun copy(): TraversalNode = LodestoneNode(lodestone, customReached)

    override fun toString(): String = "[Lodestone: ${lodestone.name}]"

    private companion object {
        private const val ARRIVED_TILES = 3
        private const val ARRIVAL_ANIMATION_MS = 5_000L
        private const val RETRY_PACING_MS = 1_200L
    }
}
