package com.projectx.traversal.pathing.mappers

import com.projectx.game.interfaces.IFSlot
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/** An interface click; its destination area, when it has one, takes precedence over the route's reached signal. */
object IFSlotMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val click = step.expect<Step.IFSlot>("IFSlotMapper")
        if (click.clickId < 0) throw IllegalArgumentException("ifslot click_id must be non-negative; was ${click.clickId}")
        val slot = IFSlot(click.interfaceId, click.component, click.slot)
        val destination = context.options.toArea(click.destinationArea)
        val reached = context.reached
        when {
            destination != null -> builder.clickIFSlot(slot, click.clickId, destination)
            reached != null -> builder.clickIFSlot(slot, click.clickId, reached)
            else -> builder.clickIFSlot(slot, click.clickId)
        }
    }
}
