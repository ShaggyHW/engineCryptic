package com.projectx.traversal.nodes

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.script.api.equipment
import com.projectx.script.api.inventory
import com.projectx.traversal.JujuSpiritbag
import com.projectx.traversal.TraversalNode
import com.projectx.util.Area

/**
 * Clicks a backpack or worn item by name or id and option name or number. By name, an item that has the option wins
 * over the first name match (a Slayer helmet must not shadow the Slayer cape), then a skillcape perk on the worn cape;
 * by id, an item missing from both falls back to the currency pouch row, where memory strands live.
 */
class ItemNode private constructor(
    private val selector: ItemSelector,
    private val optionName: String? = null,
    private val optionNum: Int? = null,
    private var destination: Area? = null,
    private var customReached: (() -> Boolean)? = null
) : TraversalNode() {

    private var nextClick: Long = 0

    // The last spiritbag landed; the restock's Trade walk may have left its landing tile.
    private var restockLanded = false

    constructor(
        itemName: String,
        optionName: String,
        destination: Area? = null,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemSelector.NameRef(itemName), optionName = optionName, destination = destination, customReached = customReached
    )

    constructor(
        itemId: Int,
        optionName: String,
        destination: Area? = null,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemSelector.IdRef(itemId), optionName = optionName, destination = destination, customReached = customReached
    )

    constructor(
        itemName: String,
        optionNum: Int,
        destination: Area? = null,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemSelector.NameRef(itemName), optionNum = optionNum, destination = destination, customReached = customReached
    )

    constructor(
        itemId: Int,
        optionNum: Int,
        destination: Area? = null,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemSelector.IdRef(itemId), optionNum = optionNum, destination = destination, customReached = customReached
    )

    /** The item and the op to click on it; a currency pouch item has no inventory item, only its row slot. */
    private class Target(val item: Item?, val option: String?, val pouch: IFSlot? = null)

    private suspend fun resolve(script: Script): Target? = when (selector) {
        is ItemSelector.NameRef -> resolveNamed(selector.name)
        is ItemSelector.IdRef -> {
            val found = backpack().firstOrNull { it.id == selector.id } ?: worn().firstOrNull { it.id == selector.id }
            if (found != null) Target(found, optionName) else pouchTarget(script, selector.id)
        }
    }

    private fun resolveNamed(name: String): Target? {
        val needle = name.lowercase()
        val named = { item: Item -> item.id != -1 && item.name.lowercase().contains(needle) }
        val option = optionName
        if (option != null) {
            val withOp = backpack().firstOrNull { named(it) && hasInvOp(it, option) }
                ?: worn().firstOrNull { named(it) && hasWornOp(it, option) }
            if (withOp != null) return Target(withOp, option)
            val perkOp = SkillcapePerks.opFor(name, option)
            val cape = perkOp?.let { SkillcapePerks.wornCape() }
            if (perkOp != null && cape != null && hasWornOp(cape, perkOp)) return Target(cape, perkOp)
        }
        val found = backpack().firstOrNull(named) ?: worn().firstOrNull(named) ?: return null
        return Target(found, option)
    }

    /** The currency pouch row's slot for [itemId] showing [optionName]; a pouch collapsed since planning is expanded. */
    private suspend fun pouchTarget(script: Script, itemId: Int): Target? {
        val option = optionName ?: return null
        PouchItems.openIfHolding(script, itemId)
        if (!PouchItems.has(itemId, option)) return null
        val hit = PouchItems.find(itemId) ?: return null
        return Target(null, option, hit.slot)
    }

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true
        val target = resolve(script)
        if (target == null) {
            // Earlier nodes or the traversal deal with pathing or waiting for the item.
            script.delay(100)
            return true
        }
        if (MenuGuard.beforeOpening(this, ::requestTraversalRepath)) return true

        val from = TraversalSupport.serverTileOrNull()
        val spiritbag = JujuSpiritbag.itemId
        val bagsBefore = if (spiritbag != null && target.item?.id == spiritbag) JujuSpiritbag.packCount() else -1
        val success = when {
            target.pouch != null && target.option != null -> target.pouch.click(target.option)
            target.option != null -> target.item?.click(target.option) == true
            optionNum != null -> target.item?.click(optionNum) == true
            else -> false
        }
        if (!success) return false
        // Not every item click teleports: one that only animates must not hold the node for the whole teleport wait.
        TraversalSupport.awaitItemEffect(from) { traversalStopRequested() || reached(script) }
        if (bagsBefore > 0 && JujuSpiritbag.restockIfLastUsed(script, bagsBefore)) restockLanded = true
        nextClick = System.currentTimeMillis() + TraversalSupport.clickPacingJitter()
        return true
    }

    override fun reached(script: Script): Boolean {
        if (restockLanded) return true
        customReached?.let { return it() }
        return TraversalSupport.inside(destination, TraversalSupport.serverTileOrNull())
    }

    override fun copy(): TraversalNode = ItemNode(
        this.selector,
        this.optionName,
        this.optionNum,
        this.destination,
        this.customReached
    )

    override fun toString(): String = buildString {
        append("[")
        append(selector)
        if (optionName != null) append(" op=\"").append(optionName).append("\"")
        if (optionNum != null) append(" op#=").append(optionNum)
        append("]")
    }

    private companion object {
        fun backpack(): List<Item> = runCatching { inventory.toList() }.getOrDefault(emptyList())

        fun worn(): List<Item> = runCatching { equipment.toList() }.getOrDefault(emptyList())

        fun hasInvOp(item: Item, op: String): Boolean = runCatching { item.getDef().getInvOpIdForName(op) >= 0 }.getOrDefault(false)

        /** The definition's worn op, else the slot's live op (perks, CS2 relabels). */
        fun hasWornOp(item: Item, op: String): Boolean = runCatching {
            item.getDef().getEquipOpIdForName(op) >= 0 || item.slot.optionIndexOf(op) > 0
        }.getOrDefault(false)
    }
}

sealed class ItemSelector {
    data class NameRef(val name: String) : ItemSelector()
    data class IdRef(val id: Int) : ItemSelector()
}
