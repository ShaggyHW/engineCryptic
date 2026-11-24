package com.projectx.traversal.nodes

import com.projectx.game.collision.CollisionMap
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.scene.CachedSceneObject
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.findClosestObjectToTile
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.isType
import com.projectx.traversal.EarlyStart
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.local.LocalNav
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment
import kotlin.math.abs

enum class DoorDirection {
    IN,
    OUT
}

data class DoorInfo(
    val realIdOpen: Int,
    val realIdClosed: Int,
    val locationOpen: Tile,
    val locationClosed: Tile,
    val tileInside: Tile,
    val tileOutside: Tile,
    val openAction: String? = "Open"
)

/**
 * Opens a door if it is shut, then crosses to the requested side. Open or shut is read off the live collision edge
 * where it can be - the two side tiles joining by a short walk - which is right for doors whose leaf shows open while
 * the edge stays solid; the object ids decide elsewhere. An open door is just two tiles of walking, so the walk before
 * it passes straight through; a shut one is opened from a few steps away. A locked one fails the traversal, one the
 * game says can't be reached asks for a new route, and one shut on the map with nothing to click is never passed.
 */
class DoorNode(
    private val doorInfo: DoorInfo,
    private val direction: DoorDirection,
    private val customReached: (() -> Boolean)? = null
) : TraversalNode(), WalkEquivalent, EarlyStart {

    private val targetTile: Tile = if (direction == DoorDirection.IN) doorInfo.tileInside else doorInfo.tileOutside
    private val nearTile: Tile = if (direction == DoorDirection.IN) doorInfo.tileOutside else doorInfo.tileInside

    private var nextClick = 0L
    private var arrivalWaitSince = 0L
    private var shutSince = 0L
    private var loggedVariant = -1

    /** The action that opens it ("Open", "Slash", "Lockpick"); its hop-free time is learned per action. */
    internal val action: String get() = openAction(doorInfo)

    private fun findDoor(): SceneObject? =
        find(doorInfo.realIdClosed, doorInfo.locationClosed) ?: find(doorInfo.realIdOpen, doorInfo.locationOpen) ?: closedVariant()

    /** Another closed leaf on the door's own tile that offers its open action: some gates come back under another id. */
    private fun closedVariant(): SceneObject? {
        val at = doorInfo.locationClosed
        val action = openAction(doorInfo)
        val variant = findClosestObjectToTile(at, 1) { it.tile == at && offers(it.visibleTypeId, action) } ?: return null
        if (variant.id != loggedVariant) {
            loggedVariant = variant.id
            println("[DoorNode] $this: shut as ${variant.id}@$at (the row says ${doorInfo.realIdClosed}) - opening that")
        }
        return variant
    }

    private fun onTarget(): Boolean = TraversalSupport.serverTileOrNull() == targetTile

    /** On the far side: the target tile, or beside it and nearer it than the near side, as a walk-through door leaves you. */
    private fun crossed(): Boolean {
        val tile = TraversalSupport.serverTileOrNull() ?: return false
        if (sameTile(doorInfo)) return false
        if (tile == targetTile) return true
        if (tile.plane != targetTile.plane) return false
        val toFar = tile.getLongestDelta(targetTile)
        return toFar <= 1 && toFar < tile.getLongestDelta(nearTile)
    }

    private suspend fun walkToTarget() {
        if (!onTarget() && TraversalSupport.walkTo(targetTile, false)) {
            TraversalSupport.awaitWalk(TraversalSupport.WALK_START_TIMEOUT_MS, TraversalSupport.WALK_LEG_TIMEOUT_MS, ::traversalStopRequested)
        }
    }

    /** Open on the live map with a walk after it: that walk clicks on past the door instead of stopping on its far tile. */
    private suspend fun proceed(): Boolean {
        if (edgeOpen(doorInfo) == true && (next as? WalkEquivalent)?.routeTiles()?.isNotEmpty() == true) {
            println("[DoorNode] open on the live map - continuing past $this without stopping")
            return false
        }
        walkToTarget()
        return false
    }

    override suspend fun process(script: Script): Boolean {
        if (crossed()) return false
        if (isOpen(doorInfo)) return proceed()
        var door = findDoor()
        if (door == null && waitForArrival()) return true
        val shut = edgeOpen(doorInfo) == false
        if (door == null && shut) door = shutButUnlisted()
        if (door == null) {
            if (!shut || shutSince == 0L) {
                println(
                    "[DoorNode] Door not found: ${doorInfo.realIdClosed}@${doorInfo.locationClosed} / ${doorInfo.realIdOpen}@${doorInfo.locationOpen}" +
                        " from ${TraversalSupport.serverTileOrNull()} (listed near it: ${listedNear(doorInfo.locationClosed)}" +
                        (if (shut) "; shut on the live map" else "") + ")"
                )
            }
            if (!shut) {
                walkToTarget()
                return false
            }
            val now = System.currentTimeMillis()
            if (shutSince == 0L) shutSince = now
            if (now - shutSince < SHUT_RETRY_MS) {
                script.delay(SHUT_POLL_MS)
                return true
            }
            shutSince = 0L
            println("[DoorNode] $this stayed shut with nothing to open for $SHUT_RETRY_MS ms - asking for a new path")
            requestTraversalRepath()
            return true
        }
        shutSince = 0L
        val now = System.currentTimeMillis()
        if (now < nextClick) return true
        val option = clickOption(door)
        if (!door.interact(option)) return false
        nextClick = now + TraversalSupport.clickPacingJitter()
        val picking = picksLock()
        var idleSince = now
        var last = TraversalSupport.serverTileOrNull()
        var idle = false
        TraversalSupport.delayUntil(OPEN_WAIT_MS) {
            if (traversalStopRequested() || isOpen(doorInfo) || crossed() || !picking && locked(now) || cantReach(now)) return@delayUntil true
            val here = TraversalSupport.serverTileOrNull()
            val t = System.currentTimeMillis()
            if (TraversalSupport.isMoving() || TraversalSupport.isAnimating() || here != null && here != last) {
                last = here
                idleSince = t
                return@delayUntil false
            }
            idle = t - idleSince >= IDLE_RECLICK_MS
            idle
        }
        if (traversalStopRequested()) return true
        if (!isOpen(doorInfo) && !crossed() && cantReach(now)) {
            println("[DoorNode] $this: \"${ObjectNode.CANT_REACH_MSG}\" from ${TraversalSupport.serverTileOrNull()} - asking for a new path")
            if (requestTraversalRepath()) return true
        }
        if (idle && !isOpen(doorInfo) && !crossed()) {
            println("[DoorNode] $this: nothing happened ${System.currentTimeMillis() - now} ms after $option - clicking it again")
            return true
        }
        if (!picking && locked(now)) {
            println("[DoorNode] $this is locked - navpath routed through a locked door; this row needs review")
            failTraversal("locked door $this")
            return true
        }
        if (crossed()) return false
        if (!isOpen(doorInfo)) return true
        return proceed()
    }

    /** "Quick open" skips the dialogue some gates start on "Open", which otherwise leaves the gate shut. */
    private fun clickOption(door: SceneObject): String {
        val action = openAction(doorInfo)
        if (!action.equals(DEFAULT_OPEN, ignoreCase = true)) return action
        return DOOR_OPTIONS.firstOrNull(door::hasOption) ?: action
    }

    /** Just after a teleport the scene is still loading: the door is waited for instead of walked past blind. */
    private fun waitForArrival(): Boolean {
        if (!LocalNav.arriving()) return false
        val now = System.currentTimeMillis()
        if (arrivalWaitSince == 0L) {
            arrivalWaitSince = now
            println("[DoorNode] $this not loaded yet after the teleport - waiting for the map")
        }
        return now - arrivalWaitSince < ARRIVAL_WAIT_MS
    }

    /**
     * Shut on the live map but left out of the scene list (a hidden leaf after other players opened and closed it): its
     * option is fired by id at its tile once the collision worker confirms that id closes the edge, or cannot say. Null
     * when another loc closes it.
     */
    private fun shutButUnlisted(): SceneObject? {
        val at = doorInfo.locationClosed
        var id = doorInfo.realIdClosed
        if (id < 0) return null
        val blockers = edgeBlockers(doorInfo.tileOutside, doorInfo.tileInside)
        val ids = blockers?.map { it.first }
        if (ids != null && id !in ids) {
            val variant = ids.firstOrNull { offers(it, openAction(doorInfo)) }
            if (variant == null) {
                if (shutSince == 0L) {
                    println("[DoorNode] $this is shut on the live map, not listed, and nothing that closes it offers ${openAction(doorInfo)} (edge blockers $ids)")
                }
                return null
            }
            id = variant
        }
        val shape = blockers?.firstOrNull { it.first == id }?.second ?: ObjectShape.WALL_STRAIGHT
        println("[DoorNode] $this is shut on the live map but not in the scene list (edge blockers ${ids ?: "unknown"}; listed near it: ${listedNear(at)}) - opening $id@$at by id")
        return CachedSceneObject(MemorySegment.NULL, id, id, at, shape, 0)
    }

    /** The open action picks a lock: its own chat ("...unlocked") is not a locked door. */
    private fun picksLock(): Boolean = openAction(doorInfo).contains("pick", ignoreCase = true)

    override fun reached(script: Script): Boolean = customReached?.invoke() == true || crossed()

    override fun routeTiles(): List<Tile> = listOf(nearTile, targetTile)

    /** Only the live map's say-so counts: object ids are not enough to walk through a door. */
    override fun passableNow(): Boolean = edgeOpen(doorInfo) == true

    override fun readyFrom(serverTile: Tile): Boolean {
        if (serverTile.plane != nearTile.plane) return false
        if (edgeOpen(doorInfo) != false) return false
        return LocalNav.distance(serverTile, nearTile, EARLY_START_STEPS) >= 0 && findDoor() != null
    }

    override fun copy(): TraversalNode = DoorNode(doorInfo, direction, customReached)

    override fun toString(): String = "[Door: ${doorInfo.realIdClosed}/${doorInfo.realIdOpen} - Direction: $direction]"

    companion object {
        private const val SCAN_RADIUS = 10

        /** The two side tiles count as connected (the door is open) within this many steps. */
        const val PASSAGE_STEPS = 3
        const val EARLY_START_STEPS = EarlyStart.MAX_STEPS
        private const val OPEN_WAIT_MS = 8_000L

        /** Idle this long after a click (no step, no animation) and it is clicked again. */
        const val IDLE_RECLICK_MS = 2_400L

        /** While a teleport's arrival is open, a door not found yet is waited for this long at most. */
        const val ARRIVAL_WAIT_MS = 6_000L

        /** A door shut on the live map with nothing to click is retried this long, then a new route is asked for. */
        const val SHUT_RETRY_MS = 5_000L
        private const val SHUT_POLL_MS = 300
        private const val LOCKED_MSG = "locked"
        private const val DEFAULT_OPEN = "Open"
        private const val BLOCKER_SCAN_TILES = 2
        private const val LISTED_MAX = 8
        private val DOOR_OPTIONS = listOf("Quick open", "Open", "Go-through", "Pass-through")

        /** Searched around the door's own tile, not the player's: a hop moves the server tile far ahead of the drawn one. */
        private fun find(id: Int, at: Tile): SceneObject? =
            findClosestObjectToTile(at, 1) { (it.isType(id) || it.typeId == id) && it.tile == at }

        private fun offers(id: Int, action: String): Boolean = Cache.loc(id)?.containsOp(action) == true

        /**
         * The live map's verdict: true when the two side tiles join within [PASSAGE_STEPS] steps, false when not, null
         * when it cannot say. A door opened on its own tile has no edge to read.
         */
        @JvmStatic
        fun edgeOpen(info: DoorInfo): Boolean? =
            if (sameTile(info)) null else LocalNav.passage(info.tileOutside, info.tileInside, PASSAGE_STEPS)

        /** Open per the live map when it can tell, else per the object ids. */
        @JvmStatic
        fun isOpen(info: DoorInfo): Boolean = edgeOpen(info) ?: isOpenById(info)

        private fun isOpenById(info: DoorInfo): Boolean {
            if (info.realIdOpen != -1) return find(info.realIdOpen, info.locationOpen) != null
            // Not finding the closed door only means open when it is close enough to have been found.
            val closed = find(info.realIdClosed, info.locationClosed) ?: return withinScan(info.locationClosed)
            // An undecoded stub def has no options and would read as open.
            val def = Cache.loc(closed.visibleTypeId)
            val decoded = def != null && (def.name.isNotEmpty() && def.name != "null" || def.options?.any { it != null } == true)
            return decoded && !closed.hasOption(openAction(info))
        }

        private fun withinScan(at: Tile): Boolean {
            val player = TraversalSupport.serverTileOrNull() ?: return false
            return player.plane == at.plane && player.getDistance(at) < SCAN_RADIUS
        }

        private fun openAction(info: DoorInfo): String = info.openAction ?: DEFAULT_OPEN

        /** Both side tiles are one tile: an object opened on the spot, not a door in a wall. */
        @JvmStatic
        fun sameTile(info: DoorInfo): Boolean = info.tileInside == info.tileOutside

        /** The ids and shapes of the locs whose collision closes the step [from] -> [to]; null when unknown. */
        private fun edgeBlockers(from: Tile, to: Tile): List<Pair<Int, ObjectShape>>? {
            val dx = to.x - from.x
            val dy = to.y - from.y
            if (from.plane != to.plane || abs(dx) > 1 || abs(dy) > 1 || dx == 0 && dy == 0) return null
            return CollisionMap.blockers(from.x, from.y, from.plane, dx, dy)?.map { it.id to ObjectShape.forId(it.shape) }
        }

        private fun listedNear(at: Tile): String {
            val near = getAllObjectsWithinRange(at, BLOCKER_SCAN_TILES).filter { it.plane == at.plane }
            if (near.isEmpty()) return "nothing"
            val shown = near.take(LISTED_MAX).joinToString(", ") { "${it.id}@${it.tileX},${it.tileY}" }
            return if (near.size > LISTED_MAX) "$shown ..." else shown
        }

        private fun cantReach(since: Long): Boolean = RecentChat.containsSince(ObjectNode.CANT_REACH_MSG, since)

        private fun locked(since: Long): Boolean = RecentChat.containsSince(LOCKED_MSG, since)
    }
}
