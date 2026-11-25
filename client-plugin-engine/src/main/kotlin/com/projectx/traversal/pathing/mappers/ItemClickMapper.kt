package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.nodes.SpiritTreeRerooterNode
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/**
 * An item click: by option name when the step has one, else by option number; by id over name. The Spirit tree
 * re-rooter becomes a [SpiritTreeRerooterNode], which activates an inactive one first.
 */
object ItemClickMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val item = step.expect<Step.ItemClick>("ItemClickMapper")
        val destination = context.options.toArea(item.destinationArea)
        val reached = context.reached
        if (item.id != null && item.id == SpiritTreeRerooterNode.activeId()) {
            builder.spiritTreeRerooter(destination, reached)
            return
        }
        val option = item.option?.takeIf { it.isNotBlank() }
        val optionNum = item.optionNum
        val name = item.name?.takeIf { it.isNotBlank() }
        when {
            option != null && item.id != null -> when {
                reached != null -> builder.clickItem(item.id, option, reached)
                destination != null -> builder.clickItem(item.id, option, destination)
                else -> builder.clickItem(item.id, option)
            }
            option != null && name != null -> when {
                reached != null -> builder.clickItem(name, option, reached)
                destination != null -> builder.clickItem(name, option, destination)
                else -> builder.clickItem(name, option)
            }
            option == null && optionNum != null && item.id != null -> when {
                reached != null -> builder.clickItem(item.id, optionNum, reached)
                destination != null -> builder.clickItem(item.id, optionNum, destination)
                else -> builder.clickItem(item.id, optionNum)
            }
            option == null && optionNum != null && name != null -> when {
                reached != null -> builder.clickItem(name, optionNum, reached)
                destination != null -> builder.clickItem(name, optionNum, destination)
                else -> builder.clickItem(name, optionNum)
            }
            option == null && optionNum == null ->
                throw IllegalArgumentException("item_click step missing option/optionNum; parser should have validated this")
            else -> throw IllegalArgumentException("item_click step missing id or name; parser should have validated this")
        }
    }
}
