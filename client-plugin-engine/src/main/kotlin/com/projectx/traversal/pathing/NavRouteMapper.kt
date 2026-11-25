package com.projectx.traversal.pathing

import com.projectx.script.api.TeleportSpell
import com.projectx.webwalker.MeasuredLinks
import com.projectx.webwalker.WebArea
import com.projectx.webwalker.WebGrid
import com.projectx.webwalker.WebInterfaceStep
import com.projectx.webwalker.WebItemStep
import com.projectx.webwalker.WebLink
import com.projectx.webwalker.WebLinkKind
import com.projectx.webwalker.WebLinks
import com.projectx.webwalker.WebNestedStep
import com.projectx.webwalker.WebObjectStep
import com.projectx.webwalker.WebPath
import com.projectx.webwalker.WebStep
import com.projectx.webwalker.WebTarget
import com.projectx.webwalker.WebWalkStep
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a navpath route into the [WebPath] a web walk's result describes it with: walked tiles one per step, and every
 * macro action as a [WebLink] on the tile it lands on. Planned Surges and Dives become the walk they cover, along the
 * route's geometry when it came back with the route. Walks themselves run on [PathingTraversal]'s nodes.
 *
 * A spell or lodestone this engine does not know is left out and the walk carries on, as an unknown step type is at
 * parse time. A step nothing here can perform (an interface click with no click id, a move inside a chain) refuses
 * the route.
 */
internal object NavRouteMapper {

    sealed interface Mapped {
        data class Route(val path: WebPath) : Mapped

        data class Refused(val reason: String) : Mapped
    }

    private class Refusal(val reason: String) : RuntimeException(reason, null, false, false)

    private class Builder(start: Tile) {
        val xs = arrayListOf(start.x)
        val ys = arrayListOf(start.y)
        val planes = arrayListOf(start.plane)
        val links = arrayListOf<WebLink?>(null)
        val macros = arrayListOf<NavMacro?>(null)

        val last: Tile get() = Tile.of(xs.last(), ys.last(), planes.last())

        fun walk(tile: Tile, macro: NavMacro? = null) {
            if (tile == last) return
            val from = last
            if (from.plane == tile.plane && from.getLongestDelta(tile) > 1) {
                val steps = max(abs(tile.x - from.x), abs(tile.y - from.y))
                for (k in 1 until steps) {
                    val t = k.toDouble() / steps
                    add((from.x + (tile.x - from.x) * t).roundToInt(), (from.y + (tile.y - from.y) * t).roundToInt(), from.plane, null, macro)
                }
            }
            add(tile.x, tile.y, tile.plane, null, macro)
        }

        /** A planned hop as the walk it covers: [walked] when the geometry gave one from [from], else the line. */
        fun hop(from: Tile?, to: Tile, walked: List<Tile>, macro: NavMacro?) {
            if (from != null && from.plane == last.plane) walk(from, macro)
            if (walked.isNotEmpty() && from != null && last == from) walked.forEach { walk(it, macro) } else walk(to, macro)
        }

        fun link(approach: Tile?, landing: Tile, link: WebLink, macro: NavMacro?) {
            if (approach != null && !link.isGlobal) walk(approach)
            add(landing.x, landing.y, landing.plane, link, macro)
        }

        private fun add(x: Int, y: Int, plane: Int, link: WebLink?, macro: NavMacro?) {
            xs += x
            ys += y
            planes += plane
            links += link
            macros += macro
        }

        fun build(): WebPath = WebPath(xs.toIntArray(), ys.toIntArray(), planes.toIntArray(), BooleanArray(xs.size), links.toTypedArray())
            .also { it.macros = macros.toTypedArray() }
    }

    var log: (String) -> Unit = ::println

    fun map(start: Tile, route: NavRoute): Mapped {
        if (!route.found) return Mapped.Refused("the planner found no route")
        val builder = Builder(start)
        try {
            for (step in route.steps) add(builder, step)
        } catch (refusal: Refusal) {
            return Mapped.Refused(refusal.reason)
        }
        return Mapped.Route(builder.build())
    }

    private fun add(builder: Builder, step: Step) {
        when (step) {
            is Step.Move -> builder.walk(step.tile, step.macro)
            is Step.TileExact -> builder.walk(step.tile, step.macro)
            is Step.Surge -> builder.hop(step.from, step.to, step.walked, step.macro)
            is Step.Dive -> builder.hop(step.from, step.to, step.walked, step.macro)
            is Step.FairyRing -> builder.link(step.from, step.to, fairyRing(step, builder.last), step.macro)
            is Step.Door -> door(builder, step)
            is Step.HasChain -> {
                val landing = landingOf(step) ?: throw Refusal("a ${step.type} step with no landing")
                val link = chained(step, step.from ?: builder.last, landing) ?: return
                builder.link(step.from, landing, link, step.macro)
            }
        }
    }

    /** A door whose ids the planner does not know is walked through; the walker opens doors it meets on its own. */
    private fun door(builder: Builder, step: Step.Door) {
        val landing = landingOf(step)
        if (step.realIdClosed == null && step.realIdOpen == null || step.tileInside == null || step.tileOutside == null) {
            val target = (if (step.direction == "OUT") step.tileOutside else step.tileInside) ?: landing ?: return
            step.from?.let { builder.walk(it, step.macro) }
            builder.walk(target, step.macro)
            return
        }
        val link = chained(step, step.from ?: builder.last, landing ?: return) ?: return
        builder.link(step.from, landing, link, step.macro)
    }

    private fun landingOf(step: Step.HasChain): Tile? {
        step.to?.let { return it }
        if (step is Step.Door) return if (step.direction == "OUT") step.tileOutside else step.tileInside
        val area = landingArea(step) ?: return null
        return Tile.of(area.minX, area.minY, area.plane ?: step.from?.plane ?: 0)
    }

    /** Where the step leaves the player: its chain's last interface pick when it has one, else its own area. */
    private fun landingArea(step: Step.HasChain): NavArea? =
        step.chain.filterIsInstance<Step.IFSlot>().lastOrNull { it.destinationArea != null }?.destinationArea ?: step.destinationArea

    /** The step as a link, or null when it is left out: a spell or lodestone this engine does not know. */
    private fun chained(step: Step.HasChain, approach: Tile, landing: Tile): WebLink? {
        if (step is Step.Loc) MeasuredLinks.promptLink(step.id, step.action, landing)?.let { return it }
        val area = landingArea(step)
        val link = when (step) {
            is Step.Door -> link(WebLinkKind.DOOR, approach, NavArea.of(landing), landing, step.openAction ?: "Open", step.realIdClosed ?: step.realIdOpen ?: -1, null)
            is Step.Loc -> link(WebLinkKind.OBJECT, approach, area, landing, step.action, step.id ?: -1, step.searchRadius)
            is Step.Npc -> link(WebLinkKind.NPC, approach, area, landing, step.action, -1, step.searchRadius)
                .using(WebTarget(step.id ?: -1, step.name))
            is Step.ItemOnObject -> link(WebLinkKind.USE_ON, approach, area, landing, "Use", step.objectId ?: throw Refusal("an item used on an object named only"), step.searchRadius)
                .using(WebTarget(step.itemId ?: -1, step.itemName))
            is Step.ItemClick -> itemLink(link(WebLinkKind.ITEM, approach, area, landing, step.option.orEmpty(), -1, null), step)
            is Step.POAItem -> link(WebLinkKind.POA, approach, area, landing, step.action, -1, null)
                .using(WebTarget(step.id ?: throw Refusal("a Passage of the Abyss step with no item id"), step.name))
                .picking(*listOfNotNull(step.action2, step.action3).toTypedArray())
            is Step.IFSlot -> {
                if (step.clickId < 0) throw Refusal("an interface click with no click id")
                return link(WebLinkKind.INTERFACE, approach, area, landing, "Click", -1, null)
                    .then(WebInterfaceStep(step.interfaceId, step.component, step.slot, step.clickId), *chain(step.chain).toTypedArray())
            }
            is Step.Lodestone -> link(WebLinkKind.LODESTONE, approach, null, landing, "Teleport", -1, null)
                .toLodestone(WebLinks.lodestoneNamed(step.value) ?: return skipped("lodestone ${step.value}"))
            is Step.Spell -> link(WebLinkKind.SPELL, approach, area, landing, step.action ?: CAST, -1, null)
                .casting(spellOf(step) ?: return skipped("spell ${step.spell} (${step.name})"))
        }
        val chain = chain(step.chain)
        return if (chain.isEmpty()) link else link.then(*chain.toTypedArray())
    }

    private fun itemLink(link: WebLink, step: Step.ItemClick): WebLink {
        val used = link.using(WebTarget(step.id ?: -1, step.name))
        return if (step.option.isNullOrBlank()) used.byOption(step.optionNum ?: throw Refusal("an item step with no option")) else used
    }

    private fun spellOf(step: Step.Spell): TeleportSpell? =
        TeleportSpell.byAbilityId(step.spell) ?: step.name?.let { name -> runCatching { TeleportSpell.named(name) }.getOrNull() }

    private fun skipped(what: String): WebLink? {
        log("[WebWalk] navpath route has a $what this engine does not know; left out")
        return null
    }


    private fun link(kind: WebLinkKind, approach: Tile, area: NavArea?, landing: Tile, action: String, objectId: Int, radius: Int?): WebLink =
        WebLink(
            kind = kind,
            from = WebArea(approach.x, approach.x, approach.y, approach.y, approach.plane),
            to = area(area, landing),
            cost = WebGrid.STEP_COST,
            action = action,
            objectId = objectId,
            searchRadius = (radius ?: DEFAULT_SEARCH_RADIUS).coerceIn(1, 64),
            requirements = emptyList(),
        )

    /** The area the action lands in, kept on the landing's plane and always holding the landing tile. */
    private fun area(area: NavArea?, landing: Tile): WebArea {
        if (area == null || (area.plane != null && area.plane != landing.plane)) return WebArea(landing.x, landing.x, landing.y, landing.y, landing.plane)
        return WebArea(
            minOf(area.minX, area.maxX, landing.x), maxOf(area.minX, area.maxX, landing.x),
            minOf(area.minY, area.maxY, landing.y), maxOf(area.minY, area.maxY, landing.y),
            landing.plane,
        )
    }

    private fun fairyRing(step: Step.FairyRing, approach: Tile): WebLink {
        val from = step.from ?: approach
        val link = WebLink(
            kind = WebLinkKind.FAIRY_RING,
            from = WebArea(from.x - RING_REACH, from.x + RING_REACH, from.y - RING_REACH, from.y + RING_REACH, from.plane),
            to = WebArea(step.to.x - RING_REACH, step.to.x + RING_REACH, step.to.y - RING_REACH, step.to.y + RING_REACH, step.to.plane),
            cost = WebGrid.STEP_COST,
            action = MeasuredLinks.ringAction(step.objectId) ?: step.action.ifBlank { DEFAULT_RING_ACTION },
            objectId = step.objectId,
            searchRadius = RING_SEARCH,
            requirements = emptyList(),
        ).dialling(step.code)
        return if (step.portable) link.fromPortableRing() else link
    }

    /** The chain as the walker's own steps; a child this engine does not know is left out, as at the top level. */
    private fun chain(steps: List<Step>): List<WebStep> = steps.mapNotNull { step ->
        when (step) {
            is Step.IFSlot -> {
                if (step.clickId < 0) throw Refusal("an interface click with no click id")
                WebInterfaceStep(step.interfaceId, step.component, step.slot, step.clickId)
            }
            is Step.Loc -> WebObjectStep(step.id ?: -1, step.action, (step.searchRadius ?: DEFAULT_SEARCH_RADIUS).coerceIn(1, 64))
            is Step.ItemClick -> if (step.id != null) WebItemStep(step.id, step.option.orEmpty(), step.optionNum.takeIf { step.option.isNullOrBlank() }) else nested(step)
            is Step.TileExact -> WebWalkStep(step.tile, step.minimap == true)
            is Step.Surge -> WebWalkStep(step.to, false)
            is Step.Dive -> WebWalkStep(step.to, false)
            is Step.Move -> throw Refusal("a move inside a chain")
            is Step.FairyRing -> WebNestedStep(fairyRing(step, step.from ?: step.to))
            is Step.HasChain -> nested(step)
        }
    }

    private fun nested(step: Step.HasChain): WebStep? {
        val landing = landingOf(step) ?: step.from ?: throw Refusal("a chained ${step.type} step with no landing")
        return chained(step, step.from ?: landing, landing)?.let(::WebNestedStep)
    }

    private const val CAST = "Cast"
    private const val DEFAULT_SEARCH_RADIUS = 16
    private const val RING_REACH = 2
    private const val RING_SEARCH = 8
    private const val DEFAULT_RING_ACTION = "Configure"
}
