package com.projectx.traversal.pathing

import com.projectx.script.api.equipment
import com.projectx.script.api.inventory
import com.projectx.script.api.varps
import com.projectx.traversal.nodes.FairyRingNode
import com.projectx.webwalker.WebLink
import com.projectx.webwalker.WebLinkKind
import com.projectx.webwalker.WebLinks
import com.projectx.webwalker.WebRequirement

/**
 * Which fairy rings this account may dial, decided once on the game thread. navpath's map carries no requirements on
 * its rings, so a ring on a route is checked against our measured rings in links.json: it is forbidden when every
 * measured way of using it is gated on a key the account does not meet, or needs a portable ring it cannot use. Keys
 * are valued as [RequirementsBuilder] values them; one nothing here can value counts as met.
 */
internal class RingPermissions private constructor(
    private val blocked: Set<WebRequirement>,
    private val portableUsable: Boolean,
) {
    fun allows(link: WebLink): Boolean = link.requirements.none { it in blocked } && (!link.portable || portableUsable)

    /** The first ring on [steps] this account cannot dial. */
    fun forbiddenRing(steps: List<Step>): Step.FairyRing? = steps.filterIsInstance<Step.FairyRing>().firstOrNull { ring ->
        val code = ring.code.lowercase()
        val ours = WebLinks.all.filter { it.kind == WebLinkKind.FAIRY_RING && it.fairyCode == code }
        ours.isNotEmpty() && ours.none(::allows)
    }

    companion object {
        /** Every ring allowed; for tests and for routing when game state is not available. */
        val UNRESTRICTED = RingPermissions(emptySet(), portableUsable = true)

        fun snapshot(): RingPermissions = snapshotOf(WebLinks.all.filter { it.kind == WebLinkKind.FAIRY_RING })

        /** The same judgement over any [links]; [snapshot] limits it to the rings. */
        fun snapshotOf(links: List<WebLink>): RingPermissions = runCatching { evaluate(links) }.getOrDefault(UNRESTRICTED)

        private fun evaluate(links: List<WebLink>): RingPermissions {
            val values = RequirementsBuilder.everyKey().mapKeys { it.key.lowercase() }
            val blocked = links.flatMap(WebLink::requirements).distinct().filterTo(HashSet()) { requirement ->
                val have = values[requirement.key.trim().lowercase()] ?: rawVar(requirement.key.trim()) ?: return@filterTo false
                val want = requirement.value.trim().toIntOrNull() ?: return@filterTo false
                !meets(have, requirement.comparison.trim(), want)
            }
            val backpack = runCatching { inventory.toList() }.getOrDefault(emptyList())
            val worn = runCatching { equipment.toList() }.getOrDefault(emptyList())
            return RingPermissions(blocked, FairyRingNode.portableDevice.usable(backpack, worn))
        }

        private fun meets(have: Int, comparison: String, want: Int): Boolean = when (comparison) {
            ">=" -> have >= want
            ">" -> have > want
            "<=" -> have <= want
            "<" -> have < want
            "!=" -> have != want
            else -> have == want
        }

        private fun rawVar(key: String): Int? = runCatching {
            when {
                key.startsWith("varbit_") -> key.removePrefix("varbit_").toIntOrNull()?.let(varps::getVarBit)
                key.startsWith("varp_") -> key.removePrefix("varp_").toIntOrNull()?.let(varps::getVar)
                else -> null
            }
        }.getOrNull()
    }
}
