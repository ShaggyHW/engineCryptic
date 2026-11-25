package com.projectx.game.items

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.GroundItem
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.nxt.inventories.ObjVarDomain
import com.projectx.script.api.bank
import com.projectx.script.api.equipment
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.ObjType

data class Item(
    val id: Int,
    var amount: Int = 1,
    val slot: IFSlot = IFSlot(-1, -1, -1),
    val varDomain: ObjVarDomain? = null
) {
    val name: String
        get() = Cache.obj(id)?.name ?: "null"

    val invOps: Array<String?>
        get() = Cache.obj(id)?.inventoryActions ?: arrayOfNulls(5)

    fun click(option: Int): Boolean {
        return slot.click(option)
    }

    fun useOn(target: Item): Boolean {
        if (slot.select())
            return target.slot.target()
        return false
    }

    fun useOn(target: NPC): Boolean {
        if (slot.select())
            return target.target()
        return false
    }

    fun useOn(target: SceneObject): Boolean {
        if (slot.select())
            return target.target()
        return false
    }

    fun useOn(target: GroundItem): Boolean {
        if (slot.select())
            return target.target()
        return false
    }

    fun alch(): Boolean {
        if (!interfaces.isOpen(1886)) return false
        val alch = IFSlot(1886, 1, 47)
        if (alch.select())
            return slot.target()
        return false
    }

    fun disassemble(): Boolean {
        if (!interfaces.isOpen(1886)) return false
        val alch = IFSlot(1886, 1, 189)
        if (alch.select())
            return slot.target()
        return false
    }

    fun getDef() = Cache.obj(id) ?: ObjType.EMPTY

    /**
     * Clicks the op named [option]. The slot's live op list answers first, since a CS2 can relabel or reorder an
     * item's ops on the slot; the definition's op table is the fallback.
     */
    fun click(option: String): Boolean {
        var op = -1
        if (slot.interfaceId == equipment.interfaceId) {
            op = if (option == "Remove") 1 else getDef().getEquipOpIdForName(option).let { if (it == -1) return clickLive(option) else it + 2 }
        } else
            if (slot.interfaceId == inventory.interfaceId) {
                val live = slot.optionIndexOf(option)
                if (live > 0) return click(live)
                op = getDef().getInvOpIdForName(option)
                op = when (op) {
                    0 -> 1
                    1 -> 2
                    2 -> 3
                    3 -> 7
                    4 -> 8
                    else -> -1
                }
            } else if (slot.interfaceId == bank.interfaceId) {
                val live = slot.optionIndexOf(option)
                if (live > 0) return click(live)
                op = when (option) {
                    "Withdraw-1" -> 1
                    "Withdraw-5" -> 3
                    "Withdraw-10" -> 4
                    "Withdraw-All" -> 7
                    "Withdraw-X" -> 6
                    "Withdraw-Placeholder" -> 8
                    else -> 1
                }
            } else return clickLive(option)
        return op != -1 && click(op)
    }

    private fun clickLive(option: String): Boolean {
        val live = slot.optionIndexOf(option)
        return live > 0 && click(live)
    }

    /**
     * A varbit of this item's own var domain (augment perks, charges) read from inventory [invId] at this item's
     * slot, falling back to the definition's param of the same id when the live read gives nothing.
     */
    fun varDomainVarBit(invId: Int, varbit: Int): Int {
        val live = if (slot.slotId < 0) 0 else runCatching {
            Bootstrap.client.inventoryManager.getWithInterface(invId, -1, -1)[slot.slotId]?.varDomain?.getVarBit(varbit)
        }.getOrNull() ?: 0
        if (live != 0) return live
        return getDef().params?.get(varbit) as? Int ?: 0
    }

    /** [varDomainVarBit] from the var domain this item was read with. */
    fun varDomainVarBit(varbit: Int): Int {
        val live = varDomain?.getVarBit(varbit) ?: 0
        if (live != 0) return live
        return getDef().params?.get(varbit) as? Int ?: 0
    }

    /** Whether this item carries an augment var domain (an Invention-augmented item). */
    val isAugmented: Boolean
        get() = varDomain?.let { it.isValid && it.elementCount > 0 } == true
}
