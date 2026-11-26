package com.projectx.script.api

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.inventories.Inventory
import com.projectx.script.Script
import com.projectx.util.gaussian
import world.gregs.voidps.gameval.Gameval

private const val CLUE_CARRIER = "Charos' clue carrier"
private val carrierWindow by lazy { Gameval.requireId(Gameval.INTERFACE, "quest_charos_trail_reward") }
private val carrierSlots by lazy { Gameval.requireComponentHash("quest_charos_trail_reward:inventory_layer") }
private val carrierClose by lazy { Gameval.requireComponentHash("quest_charos_trail_reward:mainmodal_window_close_button") }
private val carrierInventory by lazy { Gameval.requireId(Gameval.INV, "quest_charos_trail_reward_inv") }

/**
 * What Charos' clue carrier holds: sealed clue scrolls and reward caskets of every tier. Reads as empty until the
 * carrier has been opened once this session.
 */
val clueCarrier: Inventory
    get() = Bootstrap.client.inventoryManager.getWithInterface(carrierInventory, carrierSlots ushr 16, carrierSlots and 0xFFFF)

/**
 * Takes one [itemName] (such as "Sealed clue scroll (hard)") out of the Charos' clue carrier in the backpack, opening
 * and closing its window as needed. Returns true once one more is in the backpack. Java: `Wait.takeFromClueCarrier`.
 */
@JvmSynthetic
suspend fun Script.takeFromClueCarrier(itemName: String): Boolean {
    val before = inventory.count(itemName)
    if (!interfaces.isOpen(carrierWindow)) {
        val carrier = inventory.getItem(CLUE_CARRIER) ?: return false
        if (!carrier.click("Open")) return false
        delayUntil(gaussian(3000L, 360_000L)) { interfaces.isOpen(carrierWindow) }
        if (!interfaces.isOpen(carrierWindow)) return false
        delay(260, 9000)
    }
    val slot = clueCarrier.firstOrNull { it.amount > 0 && it.name == itemName }?.slot?.slotId
    if (slot != null && IFSlot(carrierSlots ushr 16, carrierSlots and 0xFFFF, slot).click(1)) {
        delayUntil(gaussian(2400L, 360_000L)) { inventory.count(itemName) > before }
    }
    delay(220, 6400)
    IFSlot(carrierClose ushr 16, carrierClose and 0xFFFF).click(1)
    delayUntil(gaussian(2000L, 250_000L)) { !interfaces.isOpen(carrierWindow) }
    return inventory.count(itemName) > before
}
