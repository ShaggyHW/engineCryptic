package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OHitmarksAndHeadbars
import com.projectx.game.nxt.ORouteWaypointManager
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.eastl.EastlString
import com.projectx.game.nxt.OPathingEntity
import com.projectx.game.nxt.OEntity
import com.projectx.game.nxt.OGraphNode
import com.projectx.game.nxt.ORouteWaypoint
import com.projectx.game.memory.NativeAccess.readFloat
import com.projectx.game.memory.NativeAccess.toMemorySegment
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment

abstract class PathingEntity(raw: MemorySegment) : Entity(raw) {
    val name: String
        get() = EastlString(ptr.pointerAtOffset(OPathingEntity.NAME, 0x24)).toString()

    val serverIndex
        get() = ptr.readInt(OPathingEntity.SERVER_INDEX)

    val routeWaypointManager
        get() = ptr.deref(OPathingEntity.ROUTE_WAYPOINT_MANAGER, ORouteWaypointManager.extent)

    val isMoving: Boolean
        get() = routeWaypointManager.readLong(OPathingEntity.WAYPOINT_COUNT) > 0

    val isInteracting
        get() = interactionSid != -1

    val isAniMoving: Boolean
        get() = isAnimating || isMoving

    val interactionSid
        get() = ptr.readInt(OPathingEntity.INTERACTING_NPC_SID)

    val hitmarksAndHeadbars
        get() = ptr.deref(OPathingEntity.HITMARKS_AND_HEADBARS, OHitmarksAndHeadbars.extent).getOrNull?.let { HitmarksAndHeadbars(it) }

    val headbars
        get() = hitmarksAndHeadbars?.headbars ?: emptyList()

    /** The current fill of this entity's headbar of [type] (a headbar gameval id), or -1 while that bar is not shown. */
    fun headbarFill(type: Int): Int = headbars.firstOrNull { it.type == type }?.toFill ?: -1

    val hits
        get() = hitmarksAndHeadbars?.hits?.filter { it.typeId > 0 && it.timeLeftMillis > 0 } ?: emptyList()

    fun interactingWith(target: PathingEntity) = target.serverIndex == interactionSid

    /**
     * Where the server has this entity this tick. [tile] is the drawn position, which trails the server by about
     * two tiles while moving and changes tile at no fixed point of the tick; time anything against server ticks with
     * this one. Equals the drawn tile when standing still; null when the entity cannot be read.
     */
    val serverTile: Tile?
        get() = runCatching {
            val drawn = tile
            val queued = queuedWaypoints()?.let(::lastQueuedTileCentre) ?: return@runCatching drawn
            Tile.of(queued.first, queued.second, drawn.plane)
        }.getOrNull()

    private fun queuedWaypoints(): FloatArray? {
        val manager = routeWaypointManager.getOrNull ?: return null
        if (manager.readInt(OPathingEntity.WAYPOINT_COUNT) <= 0) return null
        val begin = manager.readLong(ORouteWaypointManager.WAYPOINTS_BEGIN)
        val end = manager.readLong(ORouteWaypointManager.WAYPOINTS_END)
        val stride = ORouteWaypoint.STRIDE
        if (begin == 0L || end <= begin || (end - begin) % stride != 0L) return null
        val count = ((end - begin) / stride).toInt()
        if (count > MAX_QUEUED_WAYPOINTS) return null
        val queue = begin.toMemorySegment(end - begin)
        return FloatArray(count * 2) { i ->
            val at = (i / 2) * stride
            queue.readFloat(at + if (i % 2 == 0) ORouteWaypoint.FINE_X else ORouteWaypoint.FINE_Y)
        }
    }

    /** Compass bearing of the drawn model, mid-turn included: 0 N, 90 E, 180 S, 270 W; -1 when unresolved. */
    val facingAngle: Int
        get() = runCatching {
            val node = ptr.deref(OEntity.GRAPH_NODE, OGraphNode.extent).getOrNull ?: return@runCatching -1
            Bearings.ofYaw(node.readFloat(OGraphNode.MATRIX_COS), node.readFloat(OGraphNode.MATRIX_SIN))
        }.getOrDefault(-1)

    /** The nearest of the eight directions to [facingAngle]; null when that is unresolved. */
    fun facing(): Direction? = facingAngle.takeIf { it >= 0 }?.let { Bearings.direction(it.toDouble()) }

    /** Whether [target] lies within [toleranceDegrees] of [facingAngle], measured from [tile]. */
    fun isFacing(target: Tile, toleranceDegrees: Double): Boolean {
        val off = facingOffset(target)
        return off >= 0 && off <= toleranceDegrees
    }

    /** Whether [target] lies within [toleranceDegrees] of directly behind. */
    fun isFacingAway(target: Tile, toleranceDegrees: Double): Boolean {
        val off = facingOffset(target)
        return off >= 0 && off >= 180 - toleranceDegrees
    }

    private fun facingOffset(target: Tile): Int {
        val facing = facingAngle
        if (facing < 0) return -1
        val bearing = Bearings.between(tile, target)
        return if (bearing < 0) -1 else Bearings.difference(facing, bearing)
    }
}

private const val MAX_QUEUED_WAYPOINTS = 64