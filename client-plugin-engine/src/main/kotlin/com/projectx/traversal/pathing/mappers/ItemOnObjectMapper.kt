package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step
import com.projectx.traversal.pathing.anyOf
import com.projectx.traversal.pathing.insideArea

/**
 * navpath's `use_on` edge, a backpack item used on an object, by id over name on both sides. The node picks the object
 * closest to the player, which is enough because the route walks to the step's origin area first.
 */
object ItemOnObjectMapper {
    private const val DEFAULT_SEARCH_RADIUS = 20

    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val use = step.expect<Step.ItemOnObject>("ItemOnObjectMapper")
        val radius = use.searchRadius ?: DEFAULT_SEARCH_RADIUS
        val reached = anyOf(context.reached, insideArea(use.destinationArea))
        val destination = context.options.toArea(use.destinationArea)
        val itemName = use.itemName?.takeIf { it.isNotBlank() }
        val objectName = use.objectName?.takeIf { it.isNotBlank() }
        if (use.itemId == null && itemName == null) throw IllegalArgumentException("use_on step missing item id or name; parser should have validated this")
        if (use.objectId == null && objectName == null) throw IllegalArgumentException("use_on step missing object id or name; parser should have validated this")
        if (destination != null) {
            when {
                use.itemId != null && use.objectId != null -> builder.useItemOnObj(use.itemId, use.objectId, radius, destination, reached)
                use.itemId != null -> builder.useItemOnObj(use.itemId, objectName!!, radius, destination, reached)
                use.objectId != null -> builder.useItemOnObj(itemName!!, use.objectId, radius, destination, reached)
                else -> builder.useItemOnObj(itemName!!, objectName!!, radius, destination, reached)
            }
        } else {
            when {
                use.itemId != null && use.objectId != null -> builder.useItemOnObj(use.itemId, use.objectId, radius, reached)
                use.itemId != null -> builder.useItemOnObj(use.itemId, objectName!!, radius, reached)
                use.objectId != null -> builder.useItemOnObj(itemName!!, use.objectId, radius, reached)
                else -> builder.useItemOnObj(itemName!!, objectName!!, radius, reached)
            }
        }
    }
}
