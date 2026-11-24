package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.findClosestNPC
import com.projectx.traversal.EarlyStart
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile
import com.projectx.script.api.Area as WorldArea
import com.projectx.util.Area as FlatArea

/**
 * Talks to or uses an NPC picked by reference, name or id: the one nearest by walking on the live map, clicked from up
 * to a few steps away. When none is reachable while the player stands still it waits a couple of ticks (NPCs move) and
 * asks for a new route.
 */
class NpcNode private constructor(
    private val npcIdentifier: NpcIdentifier,
    /** The menu action ("Charter", "Travel"...), for the timings the walker learns; null for the default one. */
    val action: String?,
    private val destination: NodeDestination?,
    private val npcSearchRadius: Int,
    private val customReached: (() -> Boolean)?,
) : TraversalNode(), EarlyStart {

    constructor(npc: NPC, action: String? = null, destination: FlatArea? = null, npcSearchRadius: Int = 20, customReached: (() -> Boolean)? = null) :
        this(NpcIdentifier.NpcRef(npc), action, NodeDestination.of(destination), npcSearchRadius, customReached)

    constructor(npcName: String, action: String? = null, destination: FlatArea? = null, npcSearchRadius: Int = 20, customReached: (() -> Boolean)? = null) :
        this(NpcIdentifier.NameRef(npcName), action, NodeDestination.of(destination), npcSearchRadius, customReached)

    constructor(npcId: Int, action: String? = null, destination: FlatArea? = null, npcSearchRadius: Int = 20, customReached: (() -> Boolean)? = null) :
        this(NpcIdentifier.IdRef(npcId), action, NodeDestination.of(destination), npcSearchRadius, customReached)

    constructor(
        npcIdentifier: NpcIdentifier,
        action: String?,
        destination: WorldArea?,
        npcSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null,
    ) : this(npcIdentifier, action, NodeDestination.of(destination), npcSearchRadius, customReached)

    private var nextClick = 0L
    private var targetUnreachable = false
    private val stuck = StuckDetector()

    private fun predicate(): ((NPC) -> Boolean)? = when (npcIdentifier) {
        is NpcIdentifier.NameRef -> { npc -> npc.name().contains(npcIdentifier.name, ignoreCase = true) && offersAction(npc) }
        // The id shown in game is the resolved type of a transforming NPC, not its spawn id.
        is NpcIdentifier.IdRef -> { npc ->
            (npcIdentifier.id == -1 || npc.id == npcIdentifier.id || npc.typeId == npcIdentifier.id) && offersAction(npc)
        }
        is NpcIdentifier.NpcRef -> null
    }

    private fun offersAction(npc: NPC): Boolean = action == null || npc.hasOption(action)

    private fun getTarget(): NPC? {
        targetUnreachable = false
        if (npcIdentifier is NpcIdentifier.NpcRef) return npcIdentifier.npc
        val predicate = predicate() ?: return null
        val choice = LocalTargets.nearestNpc(npcSearchRadius, predicate)
        if (choice != null) {
            targetUnreachable = !choice.reachable
            return choice.target
        }
        return findClosestNPC(npcSearchRadius, false, predicate)
    }

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true
        val target = getTarget()
        if (target == null) {
            script.delay(100, 100)
            return true
        }
        // Asked again every pass, so an NPC that walks into reach clears it.
        if (targetUnreachable &&
            stuck.stuck(TraversalSupport.serverTileOrNull(), TraversalSupport.isMoving()) { LocalTargets.npcDistance(target) == LocalNav.UNREACHABLE }
        ) {
            println("[NpcNode] $this is unreachable from ${TraversalSupport.serverTileOrNull()} on the live map")
            stuck.reset()
            if (requestTraversalRepath()) return true
        }
        if (MenuGuard.beforeOpening(this, ::requestTraversalRepath)) return true
        val clicked = if (action != null) target.interact(action) else target.interact(0)
        if (!clicked) return false
        TraversalSupport.awaitWalk(TraversalSupport.WALK_START_TIMEOUT_MS, WALK_STOP_TIMEOUT_MS) { traversalStopRequested() || reached(script) }
        nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
        return true
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        return destination?.contains(TraversalSupport.serverTileOrNull()) == true
    }

    override fun readyFrom(serverTile: Tile): Boolean {
        val target = getTarget() ?: return false
        if (targetUnreachable || target.tile.plane != serverTile.plane) return false
        return LocalTargets.npcDistance(target) in 0..EARLY_START_STEPS
    }

    override fun copy(): TraversalNode = NpcNode(npcIdentifier, action, destination, npcSearchRadius, customReached)

    override fun toString() = "[$npcIdentifier - $action]"

    companion object {
        const val EARLY_START_STEPS = EarlyStart.MAX_STEPS
        private const val WALK_STOP_TIMEOUT_MS = 15_000L
    }
}

sealed class NpcIdentifier {
    data class NpcRef(val npc: NPC) : NpcIdentifier()
    data class NameRef(val name: String) : NpcIdentifier()

    /** -1 matches any NPC; matched against both the spawn id and the resolved type id. */
    data class IdRef(val id: Int) : NpcIdentifier()
}
