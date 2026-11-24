package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/** A Passage of the Abyss step: the item by id, then up to two more menu picks. */
object POAItemMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val poa = step.expect<Step.POAItem>("POAItemMapper")
        val id = poa.id ?: throw IllegalArgumentException("poa_item step requires an 'id'; POAItemNode only supports id selection (name='${poa.name}')")
        val destination = context.options.toArea(poa.destinationArea)
        val reached = context.reached
        when {
            reached != null -> builder.poaItem(id, poa.action, poa.action2, poa.action3, reached)
            destination != null -> builder.poaItem(id, poa.action, poa.action2, poa.action3, destination)
            else -> builder.poaItem(id, poa.action, poa.action2, poa.action3)
        }
    }
}
