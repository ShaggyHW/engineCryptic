package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.nodes.DiveNode
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/**
 * A planner-placed Dive, kept only by a route with `clientPlacedAbilities` off: a [DiveNode] casts it. With
 * client-placed abilities the hop is walked and the walker places its own.
 */
object DiveMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val dive = step.expect<Step.Dive>("DiveMapper")
        if (context.options.clientPlacedAbilities) {
            MoveMapper.mapSequence(MoveMapper.abilityStepsAsMoves(listOf(dive)).filterIsInstance<Step.Move>(), builder, context.options)
            return
        }
        builder.dive(dive.to, context.options.dive.enabled, context.reached)
    }
}
