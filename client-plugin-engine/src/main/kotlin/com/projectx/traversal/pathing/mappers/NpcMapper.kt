package com.projectx.traversal.pathing.mappers

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step

/** An NPC step as an NPC interaction: by id when it has one, else by name. */
object NpcMapper {
    private const val DEFAULT_SEARCH_RADIUS = 30

    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val npc = step.expect<Step.Npc>("NpcMapper")
        val radius = npc.searchRadius ?: DEFAULT_SEARCH_RADIUS
        val reached = context.reached
        when {
            npc.id != null -> if (reached != null) builder.interactNpc(npc.id, npc.action, radius, reached) else builder.interactNpc(npc.id, npc.action, radius)
            !npc.name.isNullOrBlank() -> if (reached != null) builder.interactNpc(npc.name, npc.action, radius, reached) else builder.interactNpc(npc.name, npc.action, radius)
            else -> throw IllegalArgumentException("npc step missing id or name; parser should have validated this")
        }
    }
}
