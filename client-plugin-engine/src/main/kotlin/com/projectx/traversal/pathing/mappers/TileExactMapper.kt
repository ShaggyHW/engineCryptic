package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/** A walk onto one exact tile, on the minimap when the step asks for it. */
object TileExactMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val exact = step.expect<Step.TileExact>("TileExactMapper")
        builder.walkExact(exact.tile, exact.minimap == true, context.reached)
    }
}
