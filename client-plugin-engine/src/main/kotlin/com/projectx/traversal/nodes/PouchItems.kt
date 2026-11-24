package com.projectx.traversal.nodes

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.script.Script
import com.projectx.script.api.interfaces
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval

/**
 * Items shown in the backpack's currency pouch row. Memory strands live there, not in an inventory the engine reads,
 * and their "Teleport" op is the row's slot child's own. The row is only drawn while the pouch is expanded, so an item
 * not drawn counts as not held: its click would go nowhere. [openIfHolding] expands a collapsed pouch that holds it.
 *
 * A hit is clicked through its slot by op name: the backpack's op numbering in the item click is for the item grid
 * and would mis-number these. The slot id is the child's own, not its index in the vector.
 */
object PouchItems {
    // The Currencies button's op; the game leaves it blank while the mobile layout is on. It toggles the pouch on the
    // client alone, so it works although the button's event mask is clear.
    private const val TOGGLE_OP = "Check Currency Pouch"
    private const val RETRY_AFTER_FAIL_MS = 30_000L
    private const val OPEN_WAIT_MS = 1_200L

    private class Ids(val memoryStrand: Int, val row: IFSlot, val buttonBar: IFSlot)

    private val ids = FeatureIds("PouchItems") {
        Ids(
            memoryStrand = Gameval.requireId(Gameval.OBJ, "hog_mote"),
            row = slot("toplevel_v2_inventory:premium_currency_item_layer"),
            buttonBar = slot("toplevel_v2_inventory:button_layer"),
        )
    }

    private fun slot(name: String): IFSlot = Gameval.requireComponentHash(name).let { IFSlot(it ushr 16, it and 0xFFFF) }

    @Volatile
    private var nextOpenAttempt = 0L

    /** The memory strand's item id, or -1 when its name did not resolve. */
    val memoryStrand: Int get() = ids.get()?.memoryStrand ?: -1

    /** A drawn pouch item: its slot and stack. */
    data class Hit(val slot: IFSlot, val itemId: Int, val amount: Int)

    /** The drawn pouch slot holding [itemId], or null when the row is hidden or lacks it. */
    fun find(itemId: Int): Hit? {
        val known = ids.get() ?: return null
        val row = row() ?: return null
        if (itemId < 0 || !row.onScreen) return null
        val child = row.createdChildren.firstOrNull { it.itemId == itemId && it.onScreen } ?: return null
        return Hit(IFSlot(known.row.interfaceId, known.row.componentId, child.slotId), itemId, child.stackSize)
    }

    /** True when [itemId] is drawn in the pouch row with a stack and, when given, the op [option]. */
    fun has(itemId: Int, option: String? = null): Boolean {
        val hit = find(itemId) ?: return false
        return hit.amount > 0 && (option == null || hit.slot.optionIndexOf(option) > 0)
    }

    fun isExpanded(): Boolean = row()?.onScreen == true

    /** True when the row holds [itemId] with a stack, drawn or not: a collapsed pouch keeps its children, hidden. */
    fun holds(itemId: Int): Boolean {
        if (itemId < 0) return false
        val row = row() ?: return false
        return row.createdChildren.any { it.itemId == itemId && it.stackSize > 0 }
    }

    /**
     * Expands the currency pouch when it is collapsed and holds [itemId], with one click on the backpack's Currencies
     * button. Never clicks an expanded pouch, since the button toggles; after a click that did not expand it, waits
     * [RETRY_AFTER_FAIL_MS] before trying again. Script thread only. True when the pouch is expanded.
     */
    suspend fun openIfHolding(script: Script, itemId: Int): Boolean {
        try {
            if (isExpanded()) return true
            if (!holds(itemId) || System.currentTimeMillis() < nextOpenAttempt) return false
            val toggle = toggleButton()
            if (toggle == null) {
                nextOpenAttempt = System.currentTimeMillis() + RETRY_AFTER_FAIL_MS
                println("[PouchItems] currency pouch is collapsed but the backpack has no '$TOGGLE_OP' button to open it")
                return false
            }
            if (!toggle.slot.click(toggle.option)) return false
            script.delayUntil(OPEN_WAIT_MS) { isExpanded() }
            if (isExpanded()) {
                println("[PouchItems] opened the currency pouch (item $itemId in it)")
                return true
            }
            nextOpenAttempt = System.currentTimeMillis() + RETRY_AFTER_FAIL_MS
            println("[PouchItems] clicked '$TOGGLE_OP' but the currency pouch did not open")
        } catch (e: Exception) {
            println("[PouchItems] opening the currency pouch failed: $e")
        }
        return false
    }

    /** Before a route is planned: memory strands only count while the pouch is expanded. */
    suspend fun prepare(script: Script): Boolean = openIfHolding(script, memoryStrand)

    private fun row(): InterfaceComponent? {
        val known = ids.get() ?: return null
        if (!interfaces.isOpen(known.row.interfaceId)) return null
        return interfaces.getComponent(known.row.interfaceId, known.row.componentId)
            ?: interfaces.getComponentRaw(known.row.interfaceId, known.row.componentId)
    }

    /**
     * The Currencies button's op layer, found by its op name among the bar's buttons' nested children rather than by
     * slot, since the bar is built from rows that can be reordered. Null when the backpack is closed or no button has
     * the op.
     */
    private fun toggleButton(): Toggle? {
        val bar = ids.get()?.buttonBar ?: return null
        val root = interfaces.getComponent(bar.interfaceId, bar.componentId)
            ?: interfaces.getComponentRaw(bar.interfaceId, bar.componentId) ?: return null
        for (button in created(root)) {
            for (layer in created(button)) {
                val option = layer.optionIndexOf(TOGGLE_OP)
                if (option > 0) return Toggle(IFSlot(bar.interfaceId, bar.componentId, layer.slotId), option)
            }
        }
        return null
    }

    private fun created(component: InterfaceComponent): List<InterfaceComponent> =
        component.createdChildren.ifEmpty { component.dynamicCreatedChildren.ifEmpty { component.dynamicChildren } }

    // The option is read off the op layer itself: IFSlot resolves a slot among the bar's direct children only, and the
    // layer is a grandchild.
    private class Toggle(val slot: IFSlot, val option: Int)
}
