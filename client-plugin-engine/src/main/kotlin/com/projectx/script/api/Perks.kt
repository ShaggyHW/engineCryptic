package com.projectx.script.api

import com.projectx.game.items.Item
import org.projectx.core.game.invention.Perk
import org.projectx.core.game.invention.PerkRank
import world.gregs.voidps.cache.Cache

/** The Invention perks on an augmented item, read from its var domain: up to two perks on each of two gizmos. */
object Perks {
    private class Slot(val perk: Int, val rank: Int)

    @Volatile
    private var resolved: List<Slot>? = null

    // Object varbits are numbered apart from the cache's varbit ids, so each name is resolved to its cache def.
    private fun slots(): List<Slot>? = resolved ?: runCatching {
        listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2).map { (gizmo, perk) ->
            val name = "invent_gizmo_${gizmo}_perk_$perk"
            Slot(Cache.objectVarbit(name)!!.id, Cache.objectVarbit("${name}_rank")!!.id)
        }
    }.getOrNull()?.also { resolved = it }

    /** Every perk on [item] with its rank; empty when it is not augmented. */
    @JvmStatic
    fun of(item: Item): List<PerkRank> {
        if (!item.isAugmented) return emptyList()
        val known = slots() ?: return emptyList()
        return known.mapNotNull { slot ->
            val id = item.varDomainVarBit(slot.perk)
            Perk.entries.firstOrNull { it.id == id }?.let { PerkRank(it, item.varDomainVarBit(slot.rank)) }
        }
    }

    @JvmStatic
    fun has(item: Item, perk: Perk): Boolean = of(item).any { it.perk == perk }

    /** The rank of [perk] on [item], or 0 when it does not have it. */
    @JvmStatic
    fun rank(item: Item, perk: Perk): Int = of(item).firstOrNull { it.perk == perk }?.rank ?: 0
}

fun Item.hasPerk(perk: Perk): Boolean = Perks.has(this, perk)
