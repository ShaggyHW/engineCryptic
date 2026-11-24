package com.projectx.webwalker

import com.projectx.traversal.pathing.NavMacro
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap

/**
 * What our measured links know that navpath's map does not, for the mappers and nodes that perform a route: the answer
 * to a prompt an object puts up, the action a ring object takes, and the edges set aside after a misroute.
 */
internal object MeasuredLinks {
    /** How long a fairy ring that landed somewhere else stays out of every route. */
    const val MISROUTE_MEMORY_MS = 30 * 60_000L

    private val setAsideUntil = ConcurrentHashMap<NavMacro, Long>()

    /** The measured link whose object, used with [action], asks where to go on the way to [landing]. */
    fun promptLink(objectId: Int?, action: String, landing: Tile): WebLink? = WebLinks.all.firstOrNull {
        it.kind == WebLinkKind.OBJECT && it.choice != null && it.objectId == objectId &&
            it.action.equals(action, ignoreCase = true) && it.to.contains(landing.x, landing.y, landing.plane)
    }

    /** The destination to pick in that prompt (Kharid-et's "Choose destination."), or null when there is none. */
    fun promptAnswer(objectId: Int?, action: String, landing: Tile): String? = promptLink(objectId, action, landing)?.choice

    /** The action our measured links use on the fairy ring object [objectId]. */
    fun ringAction(objectId: Int): String? =
        WebLinks.all.firstOrNull { it.kind == WebLinkKind.FAIRY_RING && !it.portable && it.objectId == objectId }?.action

    /** Leaves [macro] out of every route for [forMs]. */
    fun setAside(macro: NavMacro, forMs: Long = MISROUTE_MEMORY_MS) {
        setAsideUntil[macro] = System.currentTimeMillis() + forMs
    }

    fun setAsideNow(): Set<NavMacro> {
        val now = System.currentTimeMillis()
        setAsideUntil.values.removeIf { it <= now }
        return setAsideUntil.keys.toSet()
    }
}
