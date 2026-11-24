package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.findClosestObjectToTile
import com.projectx.script.api.isType
import com.projectx.traversal.EarlyStart
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max
import com.projectx.script.api.Area as WorldArea
import com.projectx.util.Area as FlatArea

/**
 * Uses a nearby object, picked by reference, name or id.
 *
 * Of several matches the one nearest by walking is used; with an origin (the route step's own area) only those at it,
 * and of twins there the one the destination lies behind. The click goes from a few steps away and the server walks the
 * rest. With [skipWhenPassable] a same-plane obstacle whose far side is already a short walk away is walked past.
 * Unreachable, or answered "You can't reach that.", it asks for a new route. After a click that moved the player it
 * looks again from the new side. A travel object may stand the player still before carrying them off. A stop never
 * cuts short an interaction under way. No click goes while a teleport landing would drop it, and an animation playing
 * at the click is not the interaction starting.
 */
class ObjectNode private constructor(
    private val objIdentifier: ObjectIdentifier,
    internal val action: String?,
    private val destination: NodeDestination?,
    private val objectSearchRadius: Int,
    private val customReached: (() -> Boolean)?,
    private val origin: WorldArea.Rectangular?,
    private val skipWhenPassable: Boolean,
) : TraversalNode(), WalkEquivalent, EarlyStart {

    constructor(
        obj: SceneObject,
        action: String? = null,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(ObjectIdentifier.ObjectRef(obj), action, NodeDestination.of(destination), objectSearchRadius, customReached, null, false)

    constructor(
        objectName: String,
        action: String? = null,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(ObjectIdentifier.NameRef(objectName), action, NodeDestination.of(destination), objectSearchRadius, customReached, null, false)

    constructor(
        objectId: Int,
        action: String? = null,
        destination: FlatArea? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(ObjectIdentifier.IdRef(objectId), action, NodeDestination.of(destination), objectSearchRadius, customReached, null, false)

    /** [origin] is the area the step was asked from: only objects within [objectSearchRadius] of it are candidates. */
    constructor(
        objIdentifier: ObjectIdentifier,
        action: String?,
        destination: WorldArea?,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null,
        origin: WorldArea.Rectangular? = null,
        skipWhenPassable: Boolean = false,
    ) : this(objIdentifier, action, NodeDestination.of(destination), objectSearchRadius, customReached, origin, skipWhenPassable)

    constructor(
        objIdentifier: ObjectIdentifier,
        action: String?,
        destination: WorldArea?,
        objectSearchRadius: Int,
        customReached: (() -> Boolean)?,
        requestTile: Tile,
        skipWhenPassable: Boolean = false,
    ) : this(objIdentifier, action, destination, objectSearchRadius, customReached, WorldArea.Rectangular(requestTile, requestTile), skipWhenPassable)

    private val requestTile: Tile? = origin?.getBottomLeft()

    private var nextClick = 0L
    private var target: SceneObject? = null
    private var targetUnreachable = false
    private var fired = false
    private var fireTile: Tile? = null
    private var fireMs = 0L
    private var crossed = false
    private var loggedSkip = false
    private var loggedLandingHold = false
    private val stuck = StuckDetector()

    private fun predicate(): ((SceneObject) -> Boolean)? = when (objIdentifier) {
        is ObjectIdentifier.NameRef -> { obj -> obj.name().contains(objIdentifier.name) && offersAction(obj) }
        is ObjectIdentifier.IdRef -> { obj -> (objIdentifier.id == -1 || obj.isType(objIdentifier.id)) && offersAction(obj) }
        is ObjectIdentifier.ObjectRef -> null
    }

    private fun offersAction(obj: SceneObject): Boolean = action == null || obj.hasOption(action)

    private fun getTarget(): SceneObject? {
        targetUnreachable = false
        if (objIdentifier is ObjectIdentifier.ObjectRef) return objIdentifier.obj
        val anchor = requestTile ?: TraversalSupport.serverTileOrNull() ?: return null
        val predicate = predicate() ?: return null
        val dest = destination?.rectangle
        val choice = if (origin != null) {
            LocalTargets.nearestObjectNear(origin, objectSearchRadius, predicate, dest)
        } else {
            LocalTargets.nearestObject(anchor, objectSearchRadius, predicate, false, dest)
        }
        if (choice != null) {
            targetUnreachable = !choice.reachable
            return choice.target
        }
        val fallback: (SceneObject) -> Boolean =
            if (origin != null) { o -> predicate(o) && LocalTargets.nearOrigin(o, origin, objectSearchRadius) } else predicate
        return findClosestObjectToTile(anchor, objectSearchRadius + FALLBACK_RANGE_PAD, false, fallback)
    }

    override suspend fun process(script: Script): Boolean {
        if (reached(script)) return false
        if (passableNow()) {
            if (!loggedSkip) {
                loggedSkip = true
                println("[ObjectNode] skip $this - its far side is a short walk away on the live map")
            }
            return false
        }
        if (System.currentTimeMillis() < nextClick) return true
        if (fired && cantReachSinceClick()) return true

        // Picked again until a click fires: an earlier pick can predate the scene right after a teleport.
        if (!fired || target?.exists != true) target = getTarget()
        val picked = target
        if (picked == null) {
            script.delay(100, 100)
            return true
        }

        if (targetUnreachable &&
            stuck.stuck(TraversalSupport.serverTileOrNull(), TraversalSupport.isMoving()) { LocalTargets.objectDistance(picked) == LocalNav.UNREACHABLE }
        ) {
            println("[ObjectNode] $this is unreachable from ${TraversalSupport.serverTileOrNull()} on the live map")
            stuck.reset()
            if (requestTraversalRepath()) return true
        }

        if (MenuGuard.beforeOpening(this, ::requestTraversalRepath)) return true

        val held = landingHold()
        if (held != null) {
            if (!loggedLandingHold) {
                loggedLandingHold = true
                println("[ObjectNode] $this: holding the click, $held")
            }
            script.delay(100, 100)
            return true
        }

        val from = TraversalSupport.serverTileOrNull()
        val clicked = if (action != null && picked.hasOption(action)) picked.interact(action) else picked.interact(0)
        if (!clicked) {
            nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
            return true
        }
        fired = true
        fireTile = from
        fireMs = System.currentTimeMillis()
        awaitInteraction(script)
        nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
        if (traversalStopRequested() || reached(script)) return true
        val here = TraversalSupport.serverTileOrNull()
        println(
            "[ObjectNode] $this interaction ended at $here (clicked from $from, ${System.currentTimeMillis() - fireMs} ms), destination not reached"
        )
        if (cantReachSinceClick()) return true
        if (here != null && from != null && here != from) return afterMove(here)
        return true
    }

    /** Why a click now would be dropped by a teleport landing, else null. */
    private fun landingHold(): String? {
        val at = LocalNav.lastTeleportAt
        if (at == 0L) return null
        val since = System.currentTimeMillis() - at
        return if (since < TraversalSupport.LANDED_HOLD_MS) "landed $since ms ago" else null
    }

    /**
     * Waits for the interaction to start, then while it runs: until reached, a stop that may end it, the "can't reach"
     * reply, or a while with the player neither moving nor animating; capped at [INTERACTION_MAX_MS].
     */
    private suspend fun awaitInteraction(script: Script) {
        val stop = StopState()
        val done = { stopEnds(stop) || reached(script) || RecentChat.containsSince(CANT_REACH_MSG, fireMs) }
        val animAtClick = TraversalSupport.animationId()
        val started = {
            TraversalSupport.isMoving() || TraversalSupport.animationId().let { it != -1 && it != animAtClick }
        }
        val startMs = if (near(LocalTargets.objectDistance(target))) NEAR_START_MS else START_MS
        if (!TraversalSupport.delayUntil(startMs) { done() || started() }) return
        val travel = isTravel()
        val take = fireTile
        var idleSince = 0L
        TraversalSupport.delayUntil(INTERACTION_MAX_MS) {
            if (done()) return@delayUntil true
            if (TraversalSupport.isMoving() || TraversalSupport.isAnimating()) {
                idleSince = 0L
                return@delayUntil false
            }
            if (travel) {
                val here = TraversalSupport.serverTileOrNull()
                // Carried off: only reaching the destination, or the cap, ends it.
                if (here != null && take != null && (here.plane != take.plane || here.getLongestDelta(take) >= TRAVEL_MIN_TILES)) {
                    return@delayUntil false
                }
            }
            val now = System.currentTimeMillis()
            if (idleSince == 0L) idleSince = now
            now - idleSince >= if (travel) TRAVEL_SETTLE_MS else INTERACTION_SETTLE_MS
        }
    }

    /** When a stop was first seen, when the player was last busy after it, and what the click's animation has shown. */
    private class StopState {
        var stopAt = 0L
        var busyAt = 0L
        var underWay = false
        var stillSinceClick = false
    }

    /**
     * A stop ends the wait unless the interaction is under way: it has animated since the click and the player still
     * animates or moves (it then ends a tick after both stop), or the player stands a step from the object, where it
     * starts within a tick. An animation counts only once one has been seen not playing since the click, or a step from
     * the object: a hop's tail is no interaction.
     */
    private fun stopEnds(state: StopState): Boolean {
        val anim = TraversalSupport.isAnimating()
        var d = Int.MIN_VALUE
        if (!anim) {
            state.stillSinceClick = true
        } else if (!state.underWay && (state.stillSinceClick || near(LocalTargets.objectDistance(target).also { d = it }))) {
            state.underWay = true
        }
        if (!traversalStopRequested()) return false
        val now = System.currentTimeMillis()
        if (state.stopAt == 0L) {
            state.stopAt = now
            val detail = when {
                state.underWay -> ", the interaction is under way: waiting for it to end"
                anim -> " ($d steps off, still in an animation from before it)"
                else -> ""
            }
            println("[ObjectNode] $this: stop requested ${now - fireMs} ms after the click$detail")
        }
        if (state.underWay) {
            if (anim || TraversalSupport.isMoving()) {
                state.busyAt = now
                return false
            }
            return now - max(state.stopAt, state.busyAt) >= STOP_SETTLE_MS
        }
        if (d == Int.MIN_VALUE) d = LocalTargets.objectDistance(target)
        return !(near(d) && now - state.stopAt < STOP_START_MS)
    }

    /** The destination is far enough from the clicked object to make it a travel (trapdoor, cart), not a crossing. */
    private fun isTravel(): Boolean {
        val t = target ?: return false
        val dest = destination ?: return false
        return max(abs(t.tileX - dest.centreX), abs(t.tileY - dest.centreY)) >= TRAVEL_MIN_TILES
    }

    /** The game said "can't reach" after the last click: one repath per reply. */
    private fun cantReachSinceClick(): Boolean {
        if (!RecentChat.containsSince(CANT_REACH_MSG, fireMs)) return false
        println("[ObjectNode] $this: \"$CANT_REACH_MSG\" from ${TraversalSupport.serverTileOrNull()} - asking for a new path")
        fireMs = Long.MAX_VALUE
        return requestTraversalRepath()
    }

    /**
     * The click moved the player to [here]. The destination a short walk away counts as crossed; otherwise the target
     * is picked again from this side, and one that can't be reached asks for a repath instead of another click.
     */
    private fun afterMove(here: Tile): Boolean {
        val dest = destinationTile() ?: return true
        val d = LocalNav.distance(here, dest, CROSSED_WALK_STEPS)
        if (d >= 0) {
            println("[ObjectNode] $this crossed: $dest is $d step(s) from $here")
            if (customReached != null) return false
            crossed = true
            return true
        }
        target = getTarget()
        if (target == null || targetUnreachable) {
            println("[ObjectNode] $this has no reachable target from $here after the click - asking for a new path")
            requestTraversalRepath()
        }
        return true
    }

    override fun reached(script: Script): Boolean {
        customReached?.let { return it() }
        if (crossed) return true
        if (destination != null) return destination.contains(TraversalSupport.serverTileOrNull())
        return fired && movedByInteraction()
    }

    /** With nothing to arrive at, done once the interaction has visibly carried the player: another floor, or the next walk. */
    private fun movedByInteraction(): Boolean {
        if (TraversalSupport.isMoving() || TraversalSupport.isAnimating()) return false
        val here = TraversalSupport.serverTileOrNull() ?: return false
        val from = fireTile ?: return false
        if (here.plane != from.plane) return true
        if (here == from) return false
        val nextTiles = (next as? WalkEquivalent)?.routeTiles() ?: return false
        return nextTiles.any { TraversalSupport.within(here, it, NEXT_WALK_NEAR) }
    }

    private fun destinationTile(): Tile? = destination?.southWest

    override fun routeTiles(): List<Tile> {
        val start = requestTile ?: return emptyList()
        val dest = destinationTile() ?: return emptyList()
        return listOf(start, dest)
    }

    /** Only with [skipWhenPassable], on one plane, and when the live map walks across in a straight line plus a little. */
    override fun passableNow(): Boolean {
        if (!skipWhenPassable) return false
        val start = requestTile ?: return false
        val dest = destinationTile() ?: return false
        if (start.plane != dest.plane) return false
        val straight = start.getLongestDelta(dest)
        return LocalNav.distance(start, dest, straight + PASSABLE_SLACK) >= 0
    }

    /** Fresh every check: the walker asks from the first step of its leg, before the scene here has necessarily loaded. */
    override fun readyFrom(serverTile: Tile): Boolean {
        if (passableNow()) return false
        target = getTarget()
        val picked = target ?: return false
        if (targetUnreachable || picked.plane != serverTile.plane) return false
        val d = LocalTargets.objectDistance(picked)
        return d in 0..EARLY_START_STEPS
    }

    override fun copy(): TraversalNode =
        ObjectNode(objIdentifier, action, destination, objectSearchRadius, customReached, origin, skipWhenPassable)

    override fun toString() = "[$objIdentifier - $action - $destination]"

    companion object {
        const val EARLY_START_STEPS = EarlyStart.MAX_STEPS

        /** The far side counts as reachable when the walk is at most this much over a straight line. */
        const val PASSABLE_SLACK = 3

        /** The untrusted-map query is centred on the origin's south-west tile: padded to cover the whole area. */
        const val FALLBACK_RANGE_PAD = 8

        /** The interaction is over once the player has neither moved nor animated for this long. */
        const val INTERACTION_SETTLE_MS = 1_800L
        const val INTERACTION_MAX_MS = 20_000L

        /** A moved player within this many walking steps of the destination has crossed. */
        const val CROSSED_WALK_STEPS = 4
        const val CANT_REACH_MSG = "You can't reach that."

        /** A destination at least this far from the object makes it a travel, not a crossing. */
        const val TRAVEL_MIN_TILES = 64

        /** How long a travel object may stand the player still before it moves them. */
        const val TRAVEL_SETTLE_MS = 6_000L

        /** How long a click from beside the object gets to start the interaction; from further off, the walk there. */
        const val NEAR_START_MS = 1_500L
        const val START_MS = 2_000L

        /** A stopped wait ends once the interaction has been over this long. */
        const val STOP_SETTLE_MS = 600L

        /** How long a stopped wait gives an interaction one step off to start. */
        const val STOP_START_MS = 1_200L

        private const val NEXT_WALK_NEAR = 2

        private fun near(steps: Int): Boolean = steps in 0..1
    }
}

sealed class ObjectIdentifier {
    data class ObjectRef(val obj: SceneObject) : ObjectIdentifier()
    data class NameRef(val name: String) : ObjectIdentifier()

    /** -1 matches any object. */
    data class IdRef(val id: Int) : ObjectIdentifier()
}
