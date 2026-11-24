package com.projectx.traversal.pathing.mappers

import com.projectx.script.api.Lodestone
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step
import com.projectx.webwalker.WebLinks

/** A lodestone by its name; one this engine does not know is left out. */
object LodestoneMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val teleport = step.expect<Step.Lodestone>("LodestoneMapper")
        val lodestone = resolve(teleport.value) ?: return
        builder.useLodestone(lodestone, context.reached)
    }

    internal fun resolve(raw: String?): Lodestone? {
        val value = raw?.trim() ?: return null
        return Lodestone.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WebLinks.lodestoneNamed(value)
    }
}
