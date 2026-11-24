package com.projectx.traversal.pathing

import com.projectx.traversal.nodes.TraversalSupport
import world.gregs.voidps.type.Tile

/** What a step mapper gets besides its step: the route's options and the "reached" signal the route built for it. */
class MappingContext(val options: NavOptions, val reached: (() -> Boolean)? = null)

/** True when any of [candidates] holds; null when there are none. */
internal fun anyOf(candidates: List<() -> Boolean>): (() -> Boolean)? = when (candidates.size) {
    0 -> null
    1 -> candidates[0]
    else -> { -> candidates.any { it() } }
}

internal fun anyOf(vararg candidates: (() -> Boolean)?): (() -> Boolean)? = anyOf(candidates.filterNotNull())

/** The local player's server tile, or the drawn one before the server tile is known; null when not logged in. */
internal fun playerTile(): Tile? = TraversalSupport.serverTileOrNull()

internal fun insideArea(area: NavArea?): (() -> Boolean)? = area?.let { { playerTile()?.let(it::inside) == true } }
