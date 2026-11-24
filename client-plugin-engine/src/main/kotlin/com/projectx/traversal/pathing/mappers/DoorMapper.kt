package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.nodes.DoorDirection
import com.projectx.traversal.nodes.DoorNode
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step
import com.projectx.traversal.pathing.anyOf
import com.projectx.traversal.pathing.playerTile
import com.projectx.traversal.nodes.DoorInfo as DoorNodeInfo

/**
 * A door step as a [DoorNode]. Reached once the door reads open, or, when neither of its ids is known, once the player
 * stands on the far side; a missing location falls back to the side tile it stands on.
 */
object DoorMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val door = step.expect<Step.Door>("DoorMapper")
        val direction = parseDirection(door.direction)
        val inside = door.tileInside
        val outside = door.tileOutside
        if (inside == null || outside == null) {
            val target = if (direction == DoorDirection.IN) inside else outside
            if (target != null) builder.walkExact(target, false, context.reached)
            return
        }
        val info = door.info
        val doorInfo = DoorNodeInfo(
            realIdOpen = info?.realIdOpen ?: -1,
            realIdClosed = info?.realIdClosed ?: -1,
            locationOpen = info?.locationOpen ?: outside,
            locationClosed = info?.locationClosed ?: inside,
            tileInside = inside,
            tileOutside = outside,
            openAction = info?.openAction ?: DEFAULT_OPEN,
        )
        val target = if (direction == DoorDirection.IN) inside else outside
        val idKnown = doorInfo.realIdOpen != -1 || doorInfo.realIdClosed != -1
        val doorReached = { if (idKnown) DoorNode.isOpen(doorInfo) else playerTile()?.let { it == target } == true }
        val reached = anyOf(doorReached, context.reached)
        when (direction) {
            DoorDirection.IN -> builder.doorIn(doorInfo, reached)
            DoorDirection.OUT -> builder.doorOut(doorInfo, reached)
        }
    }

    private fun parseDirection(direction: String?): DoorDirection = when (direction?.trim()?.uppercase()) {
        "IN" -> DoorDirection.IN
        "OUT" -> DoorDirection.OUT
        else -> throw IllegalArgumentException("door direction must be 'IN' or 'OUT' (case-insensitive); was '$direction'")
    }

    private const val DEFAULT_OPEN = "Open"
}
