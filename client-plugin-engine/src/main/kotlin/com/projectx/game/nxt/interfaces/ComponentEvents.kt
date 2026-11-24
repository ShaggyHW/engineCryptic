package com.projectx.game.nxt.interfaces

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.NativeAccess.toMemorySegment
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.OEventMaskNode
import com.projectx.game.nxt.OEventMaskSlot
import com.projectx.game.nxt.OInterfaceManager
import com.projectx.game.nxt.extent

internal object ComponentEvents {
    private const val MAX_CHAIN = 64
    private const val MAX_DEPTH = 64
    private const val NONE = 0xFFFF
    private const val UINT = 0xFFFFFFFFL

    /** The if_setevents override for [component]'s (component, slot), or null when there is none or it cannot be read. */
    fun maskOf(component: InterfaceComponent): Int? = runCatching {
        val manager = Bootstrap.client.ptr.deref(OClient.INTERFACE_MANAGER, OInterfaceManager.extent).getOrNull ?: return null
        val iface = component.interfaceId and NONE
        val comp = component.componentId and NONE
        val key = if (comp == NONE) UINT else ((if (iface == NONE) 0xFFFF0000L else iface.toLong() shl 16) or comp.toLong())
        val node = lookup(
            manager.readLong(OInterfaceManager.EVENTMASK_BUCKETS),
            manager.readLong(OInterfaceManager.EVENTMASK_COUNT) and UINT,
            key, OEventMaskNode.KEY, OEventMaskNode.NEXT,
        ) ?: return null
        val slot = component.slotId and NONE
        val slotKey = if (slot == NONE) UINT else slot.toLong()
        val entry = lookup(
            peek(node + OEventMaskNode.SLOT_BUCKETS),
            peek(node + OEventMaskNode.SLOT_COUNT) and UINT,
            slotKey, OEventMaskSlot.KEY, OEventMaskSlot.NEXT,
        ) ?: return null
        entry.toMemorySegment(OEventMaskSlot.VALUE + Int.SIZE_BYTES).readInt(OEventMaskSlot.VALUE)
    }.getOrNull()

    private fun lookup(buckets: Long, count: Long, key: Long, keyOffset: Long, nextOffset: Long): Long? {
        if (buckets == 0L || count == 0L) return null
        var node = peek(buckets + (key % count) * Long.SIZE_BYTES)
        var steps = 0
        while (node != 0L && (node.toMemorySegment(keyOffset + Int.SIZE_BYTES).readInt(keyOffset).toLong() and UINT) != key) {
            if (++steps > MAX_CHAIN) return null
            node = peek(node + nextOffset)
        }
        val endSentinel = peek(buckets + count * Long.SIZE_BYTES)
        return node.takeIf { it != 0L && it != endSentinel }
    }

    /** Whether [component] and every ancestor are shown: none hidden in its interface, each reached by its parent. */
    fun drawnChain(component: InterfaceComponent): Boolean {
        var current: InterfaceComponent? = component
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            val componentId = current.componentId and NONE
            if (componentId != NONE && hiddenInInterface(current.interfaceId and NONE, componentId)) return false
            val parent = current.parent
            if (parent != null && !parent.draws(current)) return false
            current = parent
        }
        return true
    }

    private fun hiddenInInterface(interfaceId: Int, componentId: Int): Boolean =
        Bootstrap.client.interfaceList.getRaw(interfaceId)?.isHidden(componentId) == true

    private fun peek(address: Long): Long = address.toMemorySegment(Long.SIZE_BYTES.toLong()).readLong()
}
