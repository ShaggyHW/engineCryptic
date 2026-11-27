package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/**
 * A fairy ring hop: dialled at its ring object, or on a portable ring when the step says so. A ring row with no
 * action is configured.
 */
object FairyRingMapper {
    const val DEFAULT_SEARCH_RADIUS = 30
    private const val DEFAULT_ACTION = "Configure"

    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val ring = step.expect<Step.FairyRing>("FairyRingMapper")
        if (ring.portable) {
            builder.portableFairyRing(ring.code, context.reached)
            return
        }
        val action = ring.action.ifBlank { DEFAULT_ACTION }
        builder.fairyRing(ring.objectId, ring.code, action, DEFAULT_SEARCH_RADIUS, context.reached)
    }
}
