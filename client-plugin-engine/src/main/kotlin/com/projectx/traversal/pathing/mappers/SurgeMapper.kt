package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.nodes.SurgeNode
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/**
 * A planner-placed Surge, kept only by a route with `clientPlacedAbilities` off: a [SurgeNode] casts it. With
 * client-placed abilities the hop is walked and the walker places its own.
 */
object SurgeMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val surge = step.expect<Step.Surge>("SurgeMapper")
        if (context.options.clientPlacedAbilities) {
            MoveMapper.mapSequence(MoveMapper.abilityStepsAsMoves(listOf(surge)).filterIsInstance<Step.Move>(), builder, context.options)
            return
        }
        builder.surge(surge.from, surge.to, context.options.surge.enabled, context.reached)
    }
}
