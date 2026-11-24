package com.projectx.traversal.pathing.mappers

import com.projectx.script.api.isDialogOpen
import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.nodes.ObjectIdentifier
import com.projectx.traversal.nodes.TraversalSupport
import com.projectx.traversal.pathing.MappingContext
import com.projectx.traversal.pathing.NavArea
import com.projectx.traversal.pathing.NavOptions
import com.projectx.traversal.pathing.Step
import com.projectx.traversal.pathing.anyOf
import com.projectx.traversal.pathing.playerTile
import com.projectx.webwalker.MeasuredLinks
import world.gregs.voidps.type.Tile
import com.projectx.script.api.Area as WorldArea

/**
 * An object interaction, anchored on the area the route requested it from. Reached once the player has stopped inside
 * the step's destination: a forced move puts the server tile on the landing as it starts, and an object clicked
 * mid-slide is dropped. A prompt the map has no answer for (Kharid-et's "Choose destination.") is answered from the
 * measured links.
 */
object ObjectMapper {
    private const val DEFAULT_SEARCH_RADIUS = 30

    /**
     * The rows' own search radius is a blanket 20, which let a same-id ladder 20 tiles off the step win before the
     * right one had loaded; nearly every row's object lies within 10 of its origin area.
     */
    internal const val ORIGIN_RADIUS = 10

    fun map(step: Step, builder: TraversalBuilder<*>, context: MappingContext) {
        val loc = step.expect<Step.Loc>("ObjectMapper")
        val options = context.options
        val origin = originArea(loc.originArea, options)
        val radius = (loc.searchRadius ?: DEFAULT_SEARCH_RADIUS).let { if (origin != null) minOf(it, ORIGIN_RADIUS) else it }
        val destArea = loc.destinationArea
        val settled = destArea?.let { area -> { playerTile()?.let(area::inside) == true && !TraversalSupport.isMoving() } }
        val answer = promptAnswer(loc, options)
        val prompted = answer?.let { { isDialogOpen() } }
        val reached = anyOf(context.reached, settled, prompted)
        val identifier = when {
            loc.id != null -> ObjectIdentifier.IdRef(loc.id)
            !loc.name.isNullOrBlank() -> ObjectIdentifier.NameRef(loc.name)
            else -> throw IllegalArgumentException("object step missing id or name; parser should have validated this")
        }
        builder.interactObj(identifier, loc.action, radius, options.toArea(destArea), origin, reached, options.skipPassableObjects)
        if (answer != null) builder.answerPrompt(answer, anyOf(context.reached, settled))
    }

    /** The area selection is anchored on; an unset plane is the player's, or the goal's when not logged in. */
    private fun originArea(origin: NavArea?, options: NavOptions): WorldArea.Rectangular? {
        origin ?: return null
        val plane = origin.plane ?: playerTile()?.plane ?: options.goal?.plane ?: 0
        return WorldArea.Rectangular(origin.minX, origin.minY, origin.maxX, origin.maxY, plane)
    }

    /** The answer our measured links give for the prompt this object puts up on the way to the step's landing. */
    private fun promptAnswer(loc: Step.Loc, options: NavOptions): String? {
        val landing = loc.to ?: loc.destinationArea?.let { Tile.of(it.minX, it.minY, it.plane ?: options.goal?.plane ?: 0) } ?: return null
        return MeasuredLinks.promptAnswer(loc.id, loc.action, landing)
    }
}
