package com.projectx.game.interfaces

import com.projectx.game.nxt.DoActionOpcode
import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.script.api.interfaces
import com.projectx.util.hashFromInterface

class IFSlot(val interfaceId: Int, val componentId: Int, val slotId: Int = -1) {
    val hash = hashFromInterface(interfaceId, componentId)

    // Some windows keep their first component hidden while on screen, which fails the parent's open check; the
    // component itself showing is as good a sign the window is up.
    private fun clickable(): Boolean {
        if (interfaceId < 0 || componentId < 0) return false
        if (interfaces.getComponent(interfaceId, componentId) != null) return true
        return interfaces.getComponentRaw(interfaceId, componentId)?.visible == true
    }

    @JvmOverloads
    fun click(option: Int = 1): Boolean {
        if (!clickable()) return false
        val op = if (option >= 6) DoActionOpcode.COMPONENT_SIXPLUS else (DoActionOpcode.COMPONENT)
        op.fire(option, slotId, hash)
        return true
    }

    @JvmOverloads
    /** Clicks the op named [name], ignoring case, read from the live component; false when it has no such op. */
    fun click(name: String): Boolean {
        val option = optionIndexOf(name)
        return option > 0 && click(option)
    }

    /** The 1-based option for the op named [name], ignoring case, as the live component lists it; -1 when absent. */
    fun optionIndexOf(name: String): Int = component()?.optionIndexOf(name) ?: -1

    /** The live component's op names, index 0 first; CS2-relabelled names included. */
    val ops: List<String>
        get() = component()?.ops ?: emptyList()

    /**
     * This slot's live component. For a container slot that is the slot child, which carries the per-item ops and
     * event mask; the container itself when it has no such child.
     */
    fun component(): InterfaceComponent? {
        if (interfaceId < 0 || componentId < 0) return null
        val root = interfaces.getComponent(interfaceId, componentId) ?: interfaces.getComponentRaw(interfaceId, componentId) ?: return null
        if (slotId < 0) return root
        val slots = root.createdChildren
        if (slots.isEmpty()) return root
        slots.getOrNull(slotId)?.takeIf { it.slotId == slotId }?.let { return it }
        return slots.firstOrNull { it.slotId == slotId } ?: root
    }

    @JvmOverloads
    fun dialogueContinue(param2: Int =-1): Boolean {
        if (!clickable()) return false
        DoActionOpcode.DIALOGUE.fire(0, param2, hash)
        return true
    }

    fun select(): Boolean {
        if (!clickable()) return false
        DoActionOpcode.SELECT_COMPONENT.fire(0, slotId, hash)
        return true
    }

    fun target(): Boolean {
        if (!clickable()) return false
        DoActionOpcode.SELECT_COMPONENT_ITEM.fire(0, slotId, hash)
        return true
    }
}