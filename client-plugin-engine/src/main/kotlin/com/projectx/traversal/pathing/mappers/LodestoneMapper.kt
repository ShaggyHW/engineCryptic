package com.projectx.traversal.pathing.mappers

import com.projectx.script.api.Lodestone
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/** A lodestone by its name; one this engine does not know is left out. */
object LodestoneMapper {
    /** navpath's spelling where it differs from the enum's. */
    private val ALIASES = mapOf("BURTHORPE" to Lodestone.BURTHOPE)

    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val teleport = step.expect<Step.Lodestone>("LodestoneMapper")
        val lodestone = resolve(teleport.value) ?: return
        builder.useLodestone(lodestone, context.reached)
    }

    internal fun resolve(raw: String?): Lodestone? {
        val key = raw?.trim()?.uppercase() ?: return null
        return ALIASES[key] ?: Lodestone.entries.firstOrNull { it.name == key }
    }
}
