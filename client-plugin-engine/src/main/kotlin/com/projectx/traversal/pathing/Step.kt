package com.projectx.traversal.pathing

import world.gregs.voidps.type.Tile
import kotlin.math.max

/** A rectangle navpath reports; [plane] is null when it gave none. */
data class NavArea(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val plane: Int?) {

    fun inside(tile: Tile): Boolean =
        (plane == null || tile.plane == plane) && tile.x in minX..maxX && tile.y in minY..maxY

    fun isNear(tile: Tile, radius: Int): Boolean {
        if (plane != null && tile.plane != plane) return false
        val dx = if (tile.x < minX) minX - tile.x else if (tile.x > maxX) tile.x - maxX else 0
        val dy = if (tile.y < minY) minY - tile.y else if (tile.y > maxY) tile.y - maxY else 0
        return max(dx, dy) <= radius
    }

    companion object {
        fun of(tile: Tile) = NavArea(tile.x, tile.y, tile.x, tile.y, tile.plane)
    }
}

/** One edge of navpath's graph, by its table kind and first node: what `exclude_macros` takes back. */
data class NavMacro(val kind: String, val id: Long)

/** What a door row knows about its door; any field may be missing. */
data class DoorInfo(
    val realIdOpen: Int? = null,
    val realIdClosed: Int? = null,
    val locationOpen: Tile? = null,
    val locationClosed: Tile? = null,
    val tileInside: Tile? = null,
    val tileOutside: Tile? = null,
    val openAction: String? = null,
)

/**
 * One action of a navpath route. Top-level steps carry the [macro] edge they came from; a chain's children carry
 * none. Chained steps also keep the tile the action is taken from and the tile it lands on ([HasChain.from],
 * [HasChain.to]), which the route mapper anchors its links on.
 */
sealed interface Step {
    val type: String
    val macro: NavMacro?

    sealed interface HasChain : Step {
        val chain: List<Step>

        /** The `teleports_requirements` ids of the rows behind this step. */
        val requirements: List<Int>
        val from: Tile?
        val to: Tile?
    }

    data class Move(val tile: Tile, override val macro: NavMacro? = null) : Step {
        override val type get() = "move"
    }

    /** [walked]: the tiles the route's geometry walks in the hop's place, [from] exclusive, [to] inclusive. */
    data class Surge(
        val from: Tile?,
        val to: Tile,
        val tilesCovered: Int?,
        val walked: List<Tile> = emptyList(),
        override val macro: NavMacro? = null,
    ) : Step {
        override val type get() = "surge"
    }

    data class Dive(
        val from: Tile?,
        val to: Tile,
        val tilesCovered: Int?,
        val walked: List<Tile> = emptyList(),
        override val macro: NavMacro? = null,
    ) : Step {
        override val type get() = "dive"
    }

    data class FairyRing(
        val code: String,
        val objectId: Int,
        val action: String,
        val from: Tile?,
        val to: Tile,
        val portable: Boolean,
        override val macro: NavMacro? = null,
    ) : Step {
        override val type get() = "fairy_ring"
    }

    data class TileExact(val tile: Tile, val minimap: Boolean?, override val macro: NavMacro? = null) : Step {
        override val type get() = "tile_exact"
    }

    data class Lodestone(
        val value: String,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "lodestone"
    }

    data class Npc(
        val id: Int?,
        val name: String?,
        val action: String,
        val searchRadius: Int?,
        val destinationArea: NavArea?,
        val originArea: NavArea?,
        val firstInterfaceId: Int?,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "npc"
    }

    /** A teleport spell by its ability id; [action] is the book slot op for another destination, null for "Cast". */
    data class Spell(
        val spell: Int,
        val name: String?,
        val action: String?,
        val destinationArea: NavArea?,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "spell"
    }

    data class IFSlot(
        val interfaceId: Int,
        val component: Int,
        val slot: Int,
        val clickId: Int,
        val destinationArea: NavArea?,
        val originArea: NavArea?,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "ifslot"
    }

    data class Loc(
        val id: Int?,
        val name: String?,
        val action: String,
        val searchRadius: Int?,
        val destinationArea: NavArea?,
        val originArea: NavArea?,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "object"
    }

    /** [direction] is "IN" or "OUT", upper-cased. */
    data class Door(
        val direction: String,
        val info: DoorInfo?,
        val tileInside: Tile?,
        val tileOutside: Tile?,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "door"
        val realIdOpen: Int? get() = info?.realIdOpen
        val realIdClosed: Int? get() = info?.realIdClosed
        val openAction: String? get() = info?.openAction
    }

    data class ItemClick(
        val id: Int?,
        val name: String?,
        val option: String?,
        val optionNum: Int?,
        val destinationArea: NavArea?,
        val originArea: NavArea?,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "item_click"
    }

    data class POAItem(
        val id: Int?,
        val name: String?,
        val action: String,
        val action2: String?,
        val action3: String?,
        val destinationArea: NavArea?,
        val originArea: NavArea?,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "poa_item"
    }

    /** navpath's `use_on` edge: a backpack item used on a scene object. */
    data class ItemOnObject(
        val itemId: Int?,
        val itemName: String?,
        val objectId: Int?,
        val objectName: String?,
        val searchRadius: Int?,
        val destinationArea: NavArea?,
        val originArea: NavArea?,
        override val chain: List<Step> = emptyList(),
        override val requirements: List<Int> = emptyList(),
        override val from: Tile? = null,
        override val to: Tile? = null,
        override val macro: NavMacro? = null,
    ) : HasChain {
        override val type get() = "use_on"
    }
}

/** The step with [macro] as the edge it came from. */
internal fun Step.withMacro(macro: NavMacro?): Step = when (this) {
    is Step.Move -> copy(macro = macro)
    is Step.Surge -> copy(macro = macro)
    is Step.Dive -> copy(macro = macro)
    is Step.FairyRing -> copy(macro = macro)
    is Step.TileExact -> copy(macro = macro)
    is Step.Lodestone -> copy(macro = macro)
    is Step.Npc -> copy(macro = macro)
    is Step.Spell -> copy(macro = macro)
    is Step.IFSlot -> copy(macro = macro)
    is Step.Loc -> copy(macro = macro)
    is Step.Door -> copy(macro = macro)
    is Step.ItemClick -> copy(macro = macro)
    is Step.POAItem -> copy(macro = macro)
    is Step.ItemOnObject -> copy(macro = macro)
}

/** The destination area of an area-bearing step, or null. */
internal val Step.destinationArea: NavArea?
    get() = when (this) {
        is Step.Npc -> destinationArea
        is Step.Loc -> destinationArea
        is Step.ItemClick -> destinationArea
        is Step.POAItem -> destinationArea
        is Step.ItemOnObject -> destinationArea
        is Step.IFSlot -> destinationArea
        is Step.Spell -> destinationArea
        else -> null
    }

/** A parsed `/route` answer. [geometry] is the route's tiles when it was asked for, else empty. */
data class NavRoute(
    val found: Boolean,
    val cost: Double,
    val path: List<Int>?,
    val lengthTiles: Int?,
    val durationMs: Int?,
    val steps: List<Step>,
    val geometry: List<Tile> = emptyList(),
)

/** A parsed `/route_area` answer: [goalReached] is the area tile the route ends on, absent when none was found. */
data class NavAreaRoute(
    val route: NavRoute,
    val goalReached: Tile?,
    val goalTilesResolved: Int?,
    val goalTilesSearched: Int?,
)
