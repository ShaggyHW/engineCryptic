package com.projectx.traversal.nodes

import com.projectx.script.Script
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile

/**
 * Walks to an exact tile with a world or minimap click. Without [customReached], arrival is the server tile on the tile.
 * A tile the live map shows unreachable while the player stands still asks for a new route; a tile too far to click
 * right after the node before it waits for that teleport to land instead of ending the node.
 */
class TileExactNode(
    private val tile: Tile,
    private val minimap: Boolean = false,
    private var customReached: (() -> Boolean)? = null
) : TraversalNode(), WalkEquivalent {
    private var nextClick = 0L
    private var loggedBlocked = false
    private var firstProcessAt = 0L
    private var loggedLandingWait = false

    /** The last pass could not click the far tile and waits for a teleport to land: no stuck check meanwhile. */
    private var waitingForLanding = false
    private val stuck = StuckDetector()

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (!loggedBlocked && LocalNav.trusted(tile) && !LocalNav.walkable(tile)) {
            loggedBlocked = true
            println("[TileExactNode] $tile is blocked on the live map")
        }
        // Standing still at a teleport's take-off the goal reads unreachable; re-routing then clicks the teleport again.
        if (waitingForLanding && System.currentTimeMillis() - firstProcessAt < LANDING_GRACE_MS) {
            stuck.reset()
        } else if (stuck.stuck(TraversalSupport.serverTileOrNull(), TraversalSupport.isMoving()) { LocalNav.distance(it, tile, Int.MAX_VALUE) == LocalNav.UNREACHABLE }) {
            println("[TileExactNode] $tile is unreachable from ${TraversalSupport.serverTileOrNull()} on the live map")
            stuck.reset()
            if (requestTraversalRepath()) return true
        }
        if (System.currentTimeMillis() < nextClick) return true
        if (alreadyWalkingHere()) return true
        val now = System.currentTimeMillis()
        if (firstProcessAt == 0L) firstProcessAt = now
        if (!TraversalSupport.walkTo(tile, minimap)) {
            // Only a far tile can be a teleport in flight; a close one that won't take a click ends the node.
            val me = TraversalSupport.serverTileOrNull()
            val far = me == null || me.plane != tile.plane || me.getLongestDelta(tile) > FAR_TILES
            if (far && now - firstProcessAt < LANDING_GRACE_MS) {
                waitingForLanding = true
                if (!loggedLandingWait) {
                    loggedLandingWait = true
                    println("[TileExactNode] can't click $tile from $me - waiting up to $LANDING_GRACE_MS ms for a teleport to land")
                }
                TraversalSupport.delayUntil(TraversalSupport.TICK_MS) { traversalStopRequested() || reached(script) }
                return true
            }
            waitingForLanding = false
            return false
        }
        waitingForLanding = false
        TraversalSupport.noteWalkClick(tile)
        TraversalSupport.awaitWalk(TraversalSupport.WALK_START_TIMEOUT_MS, WALK_STOP_TIMEOUT_MS) { traversalStopRequested() || reached(script) }
        nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
        return true
    }

    /** The walk the player is on already goes here (the last walk node clicked this tile): a second click adds nothing. */
    private fun alreadyWalkingHere(): Boolean {
        if (tile != TraversalSupport.lastWalkClickOrNull()) return false
        if (TraversalSupport.recentlyClicked(tile, TraversalSupport.WALK_START_TIMEOUT_MS)) return true
        return TraversalSupport.isMoving()
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        return TraversalSupport.serverTileOrNull() == tile
    }

    override fun routeTiles(): List<Tile> = listOf(tile)

    /** Passable only when standing on the tile is all it asks; a custom predicate may want more. */
    override fun passableNow(): Boolean = customReached == null

    override fun walkThrough(): Boolean = false

    override fun copy(): TraversalNode = TileExactNode(tile, minimap, customReached)

    override fun toString() = "[$tile, minimap=$minimap]"

    companion object {
        /** How long a walk click that can't be sent waits for a teleport to land; casts take ~3-5 s. */
        const val LANDING_GRACE_MS = 10_000L
        private const val FAR_TILES = 40
        private const val WALK_STOP_TIMEOUT_MS = 15_000L
    }
}
