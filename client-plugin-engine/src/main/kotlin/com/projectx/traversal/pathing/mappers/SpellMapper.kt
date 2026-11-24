package com.projectx.traversal.pathing.mappers

import com.projectx.script.api.TeleportSpell
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.Step
import com.projectx.util.Area as FlatArea

/** A teleport spell by its ability id, or its name; one this engine does not know is left out. */
object SpellMapper {
    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val cast = step.expect<Step.Spell>("SpellMapper")
        val spell = TeleportSpell.byAbilityId(cast.spell) ?: cast.name?.let { name -> runCatching { TeleportSpell.named(name) }.getOrNull() }
        if (spell == null) {
            println("[Spell] navpath spell step ${cast.spell} (${cast.name}) is not a known TeleportSpell - skipped")
            return
        }
        val destination = context.options.toArea(cast.destinationArea)
        if (destination != null) {
            builder.castSpell(spell, cast.action, destination, context.reached)
        } else {
            builder.castSpell(spell, cast.action, null as FlatArea?, context.reached)
        }
    }
}
