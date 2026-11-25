package com.projectx.game.nxt.interfaces
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.NativeAccess.readShort
import com.projectx.game.memory.NativeAccess.toMemorySegment
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.hooks.impl.InterfaceComponentRectCapture
import com.projectx.game.memory.eastl.EastlString
import com.projectx.game.nxt.OChildSlot
import com.projectx.game.nxt.OComponentKind
import com.projectx.game.nxt.OComponentPage
import com.projectx.game.nxt.OInterfaceComponent
import com.projectx.game.nxt.OInterfaceComponent.ITEM_ID
import com.projectx.game.nxt.OInterfaceComponent.PARENT_REL_X
import com.projectx.game.nxt.OInterfaceComponent.PARENT_REL_Y
import com.projectx.game.nxt.OInterfaceComponent.SCREEN_HEIGHT
import com.projectx.game.nxt.OInterfaceComponent.SCREEN_WIDTH
import com.projectx.game.nxt.OInterfaceComponent.STACK_SIZE
import com.projectx.game.nxt.types.Vector
import com.projectx.game.platform.Platform
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap

class InterfaceComponent(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OInterfaceComponent.extent)
    val interfaceId: Int
        get() = ptr.readShort(OInterfaceComponent.INTERFACE_ID).toInt()
    val componentId: Int
        get() = ptr.readShort(OInterfaceComponent.COMPONENT_ID).toInt()
    // Graphic ids are unsigned 16-bit; a raw signed read sign-extends ids above 0x7FFF into negatives.
    // Read unsigned, but keep the client's 0xFFFF "no graphic" value as -1 so sentinel checks still hold.
    val graphicId: Int
        get() {
            val raw = ptr.readShort(OInterfaceComponent.GRAPHIC_ID).toInt() and 0xFFFF
            return if (raw == 0xFFFF) -1 else raw
        }
    val slotId: Int
        get() = ptr.readShort(OInterfaceComponent.SLOT_ID).toInt()
    /** Component id of the layer this component sits in, or -1 when it hangs off the interface root. */
    val parentLayerId: Int
        get() {
            val raw = ptr.readShort(OInterfaceComponent.PARENT_LAYER_ID).toInt() and 0xFFFF
            return if (raw == 0xFFFF) -1 else raw
        }
    /**
     * The FLAGS bit 0x20 false-negatives for some dialog interfaces (the game leaves it
     * unset even while the component is on screen). Non-zero width/height after the
     * layout pass is the reliable signal we previously used.
     */
    val visible: Boolean
        get() = ptr.readInt(SCREEN_WIDTH) != 0 || ptr.readInt(SCREEN_HEIGHT) != 0
    /** Parent-local X. NOT screen-absolute - see [screenRect] for the live screen position. */
    val parentRelX: Int
        get() = ptr.readInt(PARENT_REL_X)
    /** Parent-local Y. NOT screen-absolute - see [screenRect] for the live screen position. */
    val parentRelY: Int
        get() = ptr.readInt(PARENT_REL_Y)
    val screenWidth: Int
        get() = ptr.readInt(SCREEN_WIDTH)
    val screenHeight: Int
        get() = ptr.readInt(SCREEN_HEIGHT)
    /** Absolute screen rect captured during the most recent draw pass, or null if not laid out this frame. */
    val screenRect: ScreenRect?
        get() = InterfaceComponentRectCapture.lookup(ptr)
    val xOriginOffset: Int
        get() = ptr.readInt(OInterfaceComponent.X_ORIGIN_OFFSET)
    val yOriginOffset: Int
        get() = ptr.readInt(OInterfaceComponent.Y_ORIGIN_OFFSET)
    val rawWidth: Int
        get() = ptr.readInt(OInterfaceComponent.RAW_WIDTH)
    val rawHeight: Int
        get() = ptr.readInt(OInterfaceComponent.RAW_HEIGHT)
    val parent: InterfaceComponent?
        get() = ptr.deref(OInterfaceComponent.PARENT_SHAREDPTR + 0x8L, OInterfaceComponent.extent).getOrNull?.let { InterfaceComponent(it) }
    val text: String
        get() = EastlString(ptr.asSlice(OInterfaceComponent.TEXT, EASTL_STRING_SIZE)).toString()
    val itemId
        get() = ptr.readInt(ITEM_ID)
    val stackSize
        get() = ptr.readInt(STACK_SIZE)
    /** The client's own kind for this component, answered by its class; -1 when it cannot be read. */
    val kind: Int
        get() = ComponentKind.of(ptr)

    /** The component's own op mask: bit n set while op n is enabled. Bits above 10 are unrelated widget state. */
    val opMask: Int
        get() = ptr.readInt(OInterfaceComponent.ACTIVE_PROPS)

    /**
     * The mask the client tests before sending an interface op: a server-pushed if_setevents override for this
     * component and slot when there is one, else [opMask]. Grid slots read 0 in [opMask] and live on the override.
     */
    val eventMask: Int
        get() = ComponentEvents.maskOf(this) ?: opMask

    fun isOpEnabled(option: Int): Boolean = option in 0..31 && eventMask and (1 shl option) != 0

    /** Whether any op is enabled, which tells a clickable widget from a label drawn over it. */
    val isClickable: Boolean
        get() = opMask and CLICKABLE_OPS != 0

    val opCount: Int
        get() {
            val begin = ptr.readLong(OInterfaceComponent.OPS_BEGIN)
            val end = ptr.readLong(OInterfaceComponent.OPS_END)
            if (begin == 0L || end <= begin) return 0
            return ((end - begin) / OInterfaceComponent.OPS_STRIDE).coerceAtMost(MAX_OPS.toLong()).toInt()
        }

    /** The op name at [index], or "" when there is none. Index 0 is what `IFSlot.click(1)` fires. */
    fun op(index: Int): String {
        if (index !in 0..<opCount) return ""
        val begin = ptr.readLong(OInterfaceComponent.OPS_BEGIN)
        if (begin == 0L) return ""
        return EastlString((begin + index * OInterfaceComponent.OPS_STRIDE).toMemorySegment(EASTL_STRING_SIZE)).toString()
    }

    val ops: List<String>
        get() = List(opCount, ::op)

    /** The 1-based option that fires the op named [name], ignoring case; -1 when there is none. */
    fun optionIndexOf(name: String): Int {
        val count = opCount
        for (i in 0..<count) if (op(i).equals(name, ignoreCase = true)) return i + 1
        return -1
    }

    /** Whether this component is drawn right now: it has geometry, and it and every ancestor are drawn by their parent. */
    val onScreen: Boolean
        get() = runCatching { hasGeometry && ComponentEvents.drawnChain(this) }.getOrDefault(false)

    private val hasGeometry: Boolean
        get() = screenWidth > 0 || screenHeight > 0 || parentRelX != 0 || parentRelY != 0

    /**
     * Whether this component's class owns the child vectors. Every other kind keeps its own fields in those bytes, so
     * reading children off one walks garbage pointers.
     */
    val hasChildVectors: Boolean
        get() = kind.let { it in 0..31 && (OComponentKind.CHILD_VECTOR_KINDS ushr it) and 1 != 0 }

    private val isPagedLayer: Boolean
        get() = Platform.current == Platform.WINDOWS && kind == OInterfaceComponent.PAGED_LAYER_KIND

    /** Whether this component owns children at all: a layer, or on Windows a paged carousel. */
    val isLayer: Boolean
        get() = hasChildVectors || isPagedLayer

    /** The children laid out by the interface file. */
    val staticChildren: List<InterfaceComponent>
        get() = ownVector(OInterfaceComponent.STATIC_CHILDREN)

    /** The slot children vector, the one `cc_create` fills: item grids, list rows, buttons built by script. */
    val createdChildren: List<InterfaceComponent>
        get() = ownVector(OInterfaceComponent.SLOT_CHILDREN)

    /** Drawn instead of [staticChildren] while it is non-empty. */
    val dynamicChildren: List<InterfaceComponent>
        get() = ownVector(OInterfaceComponent.DYNAMIC_CHILDREN)

    /** Drawn instead of [createdChildren] while it is non-empty. */
    val dynamicCreatedChildren: List<InterfaceComponent>
        get() = ownVector(OInterfaceComponent.DYNAMIC_SLOT_CHILDREN)

    /** Every child: [staticChildren] then [createdChildren], or a Windows paged carousel's active page. */
    val slotChildren: List<InterfaceComponent>
        get() = when {
            hasChildVectors -> walkChildVector(ptr.address(), OInterfaceComponent.STATIC_CHILDREN) +
                walkChildVector(ptr.address(), OInterfaceComponent.SLOT_CHILDREN)
            isPagedLayer -> activePageChildren()
            else -> emptyList()
        }

    /**
     * Whether this component's draw pass reaches [child]. For each pair the client draws the dynamic list while it is
     * non-empty, else the static one. Fails open when neither pair has a list.
     */
    fun draws(child: InterfaceComponent): Boolean {
        if (!hasChildVectors) return true
        val base = ptr.address()
        val target = child.ptr.address()
        val drawn = listOfNotNull(
            span(base, OInterfaceComponent.DYNAMIC_CHILDREN) ?: span(base, OInterfaceComponent.STATIC_CHILDREN),
            span(base, OInterfaceComponent.DYNAMIC_SLOT_CHILDREN) ?: span(base, OInterfaceComponent.SLOT_CHILDREN),
        )
        return drawn.isEmpty() || drawn.any { it.holds(target) }
    }

    private fun ownVector(offset: Long): List<InterfaceComponent> =
        if (hasChildVectors) walkChildVector(ptr.address(), offset) else emptyList()

    private fun activePageChildren(): List<InterfaceComponent> {
        val pages = ptr.readLong(OInterfaceComponent.PAGES)
        val pagesEnd = ptr.readLong(OInterfaceComponent.PAGES_END)
        if (pages == 0L || pagesEnd <= pages) {
            return walkChildVector(ptr.address(), OInterfaceComponent.STATIC_CHILDREN) +
                walkChildVector(ptr.address(), OInterfaceComponent.SLOT_CHILDREN)
        }
        val page = ptr.readByte(OInterfaceComponent.ACTIVE_PAGE).toLong() and 0xFF
        if (page >= (pagesEnd - pages) / OInterfaceComponent.PAGE_STRIDE) return emptyList()
        val pageAddr = pages + page * OInterfaceComponent.PAGE_STRIDE
        return walkChildVector(pageAddr, OComponentPage.STATIC_CHILDREN) + walkChildVector(pageAddr, OComponentPage.DYNAMIC_CHILDREN)
    }

    private fun walkChildVector(base: Long, vectorOffset: Long): List<InterfaceComponent> {
        val span = span(base, vectorOffset) ?: return emptyList()
        val out = mutableListOf<InterfaceComponent>()
        span.forEachLive(MAX_VECTOR_SLOTS) { out += InterfaceComponent(it.toMemorySegment(OInterfaceComponent.extent)) }
        return out
    }
}

/** A child-slot vector's [begin, end) span; null when the vector is empty or not initialised. */
private fun span(base: Long, vectorOffset: Long): SlotSpan? {
    val header = (base + vectorOffset).toMemorySegment(Vector.OVector.VECTOR_CAPACITY)
    val begin = header.readLong(Vector.OVector.VECTOR_BEGIN)
    val end = header.readLong(Vector.OVector.VECTOR_END)
    return if (begin == 0L || end <= begin) null else SlotSpan(begin, end)
}

private class SlotSpan(val begin: Long, val end: Long) {
    val count: Long get() = (end - begin) / OChildSlot.SIZE

    inline fun forEachLive(limit: Long, action: (Long) -> Unit) {
        val stride = OChildSlot.SIZE
        for (i in 0L..<minOf(count, limit)) {
            val slot = (begin + i * stride).toMemorySegment(stride)
            if (slot.readByte(OChildSlot.REMOVED) != 0.toByte()) continue
            val component = slot.readLong(OChildSlot.COMPONENT)
            if (component != 0L) action(component)
        }
    }

    fun holds(component: Long): Boolean {
        if (count > MAX_DRAWN_SLOTS) return false
        forEachLive(count) { if (it == component) return true }
        return false
    }
}

/** An inline eastl::string: 23 short-string bytes plus the size byte. */
private const val EASTL_STRING_SIZE = 0x18L
private const val MAX_OPS = 16
private const val CLICKABLE_OPS = 0x7FF
private const val MAX_VECTOR_SLOTS = 65_536L
private const val MAX_DRAWN_SLOTS = 8_192L

private object ComponentKind {
    private const val UNKNOWN = -1
    private const val THUNK_BYTES = 12L
    private val ENDBR64 = byteArrayOf(0xF3.toByte(), 0x0F, 0x1E, 0xFA.toByte())
    private const val MOV_EAX_IMM32 = 0xB8.toByte()
    private const val XOR_EAX_EAX = 0x31.toByte()
    private const val MODRM_EAX_EAX = 0xC0.toByte()
    private const val RET = 0xC3.toByte()

    private val byVtable = ConcurrentHashMap<Long, Int>()

    private val getterCall: MethodHandle by lazy {
        Linker.nativeLinker().downcallHandle(FunctionDescriptor.of(JAVA_BYTE, ADDRESS))
    }

    fun of(component: MemorySegment): Int {
        val vtable = component.readLong(0L)
        if (vtable == 0L) return UNKNOWN
        byVtable[vtable]?.let { return it }
        val getter = vtable.toMemorySegment(OInterfaceComponent.KIND_GETTER + 8).readLong(OInterfaceComponent.KIND_GETTER)
        if (getter == 0L) return UNKNOWN
        val kind = if (Platform.current == Platform.WINDOWS) called(getter, component) else decoded(getter)
        if (kind != UNKNOWN) byVtable[vtable] = kind
        return kind
    }

    private fun called(getter: Long, component: MemorySegment): Int =
        (getterCall.invokeExact(MemorySegment.ofAddress(getter), component) as Byte).toInt() and 0xFF

    // The Linux getter is a constant thunk; reading its immediate answers the same without calling into the client.
    private fun decoded(getter: Long): Int {
        val code = getter.toMemorySegment(THUNK_BYTES).toArray(JAVA_BYTE)
        val at = if (code.copyOfRange(0, ENDBR64.size).contentEquals(ENDBR64)) ENDBR64.size else 0
        return when {
            code[at] == MOV_EAX_IMM32 && code[at + 5] == RET ->
                (code[at + 1].toInt() and 0xFF) or ((code[at + 2].toInt() and 0xFF) shl 8) or
                    ((code[at + 3].toInt() and 0xFF) shl 16) or (code[at + 4].toInt() shl 24)
            code[at] == XOR_EAX_EAX && code[at + 1] == MODRM_EAX_EAX && code[at + 2] == RET -> 0
            else -> UNKNOWN
        }
    }
}
