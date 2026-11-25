package com.projectx.game.interfaces

import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.script.api.bank
import com.projectx.script.api.interfaces
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

class Bank {
    companion object {
        const val BANK_INTERFACE_ID = 517

        private fun component(name: String) = Gameval.requireComponentHash("bank:$name") and 0xFFFF

        @JvmStatic
        val BANK_ITEMS_COMPONENT_ID = component("bank_inv")
        @JvmStatic
        val BANK_INV_COMPONENT_ID = component("inventory_click")
        @JvmStatic
        val SHARE_QUICK_COMPONENT_ID = component("share_quick_click")
        @JvmStatic
        val CLOSE_COMPONENT_ID = component("close_button_layer")
        @JvmStatic
        val CERT_COMPONENT_ID = component("bank_cert_button")
        @JvmStatic
        val TABS_ALL_COMPONENT_ID = component("tabs_all_button")
        @JvmStatic
        val PRESET_TAB_COMPONENT_ID = component("side_tab_presets")
        @JvmStatic
        val TRANSFER_TAB_COMPONENT_ID = component("side_tab_options")
        @JvmStatic
        val DEPOSIT_INVENTORY_COMPONENT_ID = component("bank_inv_button")
        @JvmStatic
        val DEPOSIT_WORN_COMPONENT_ID = component("bank_worn_button")

        @JvmStatic
        fun fetchBankArray(componentId: Int): ArrayList<InterfaceComponent> {
            val children = interfaces.getComponent(BANK_INTERFACE_ID, componentId)?.createdChildren.orEmpty()
            return children.filterTo(ArrayList()) { it.itemId > 0 && it.stackSize > 0 }
        }

        @JvmStatic
        fun fetchBankItemsArray() = fetchBankArray(BANK_ITEMS_COMPONENT_ID)
        @JvmStatic
        fun fetchBankInventoryArray() = fetchBankArray(BANK_INV_COMPONENT_ID)

        @JvmOverloads
        @JvmStatic
        fun doBankAction(componentId: Int, slotId: Int = -1, optionNum: Int = 1) =
            IFSlot(BANK_INTERFACE_ID, componentId, slotId).click(optionNum)

        internal fun doBankItemsAction(name: String, optionNum: Int): Boolean {
            for (item in fetchBankItemsArray()) {
                if (Cache.obj(item.itemId)?.name == name)
                    return doBankAction(BANK_ITEMS_COMPONENT_ID, item.slotId, optionNum)
            }
            return clickBankSlot(optionNum) { Cache.obj(it)?.name == name }
        }

        internal fun doBankItemsAction(regex: Regex, optionNum: Int): Boolean {
            for (item in fetchBankItemsArray()) {
                if (regex.matches(Cache.obj(item.itemId)?.name ?: ""))
                    return doBankAction(BANK_ITEMS_COMPONENT_ID, item.slotId, optionNum)
            }
            return clickBankSlot(optionNum) { regex.matches(Cache.obj(it)?.name ?: "") }
        }

        internal fun doBankItemsAction(itemId: Int, optionNum: Int): Boolean {
            for (item in fetchBankItemsArray()) {
                if (item.itemId == itemId)
                    return doBankAction(BANK_ITEMS_COMPONENT_ID, item.slotId, optionNum)
            }
            return clickBankSlot(optionNum) { it == itemId }
        }

        // The window holds slots for only part of a large bank; the bank inventory has every one, at the same index.
        private fun clickBankSlot(optionNum: Int, matches: (Int) -> Boolean): Boolean {
            val slot = bank.firstOrNull { it.amount > 0 && matches(it.id) }?.slot?.slotId ?: return false
            return doBankAction(BANK_ITEMS_COMPONENT_ID, slot, optionNum)
        }

        internal fun doBankInventoryAction(name: String, optionNum: Int): Boolean {
            for (item in fetchBankInventoryArray()) {
                if (Cache.obj(item.itemId)?.name == name)
                    return doBankAction(BANK_INV_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }

        internal fun doBankInventoryAction(regex: Regex, optionNum: Int): Boolean {
            for (item in fetchBankInventoryArray()) {
                if (regex.matches(Cache.obj(item.itemId)?.name ?: ""))
                    return doBankAction(BANK_INV_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }

        internal fun doBankInventoryAction(itemId: Int, optionNum: Int): Boolean {
            for (item in fetchBankInventoryArray()) {
                if (item.itemId == itemId)
                    return doBankAction(BANK_INV_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }
    }
}