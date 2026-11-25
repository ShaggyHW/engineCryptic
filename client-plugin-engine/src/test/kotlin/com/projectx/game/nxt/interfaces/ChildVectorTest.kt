package com.projectx.game.nxt.interfaces

import com.projectx.game.nxt.OChildSlot
import com.projectx.game.nxt.OInterfaceComponent
import com.projectx.game.platform.Platform
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Child lists read through the class gate, and the draw check, over a synthetic address space. */
class ChildVectorTest {
    // Never freed: the kind is remembered per vtable address, and a reused address would answer a stale kind.
    private val arena = Arena.global()

    @Test
    fun `the kind is decoded from the class's constant getter`() {
        assumeTrue(Platform.current != Platform.WINDOWS)
        assertEquals(LAYER, component(movEax(LAYER)).kind)
        assertEquals(0, component(XOR_EAX_RET).kind)
        assertEquals(LAYER, component(byteArrayOf(0xF3.toByte(), 0x0F, 0x1E, 0xFA.toByte()) + movEax(LAYER)).kind)
        assertEquals(-1, component(byteArrayOf(0x55, 0x48, 0x89.toByte(), 0xE5.toByte())).kind)
    }

    @Test
    fun `only a class that owns the child vectors reports children`() {
        assumeTrue(Platform.current != Platform.WINDOWS)
        val laid = component(XOR_EAX_RET)
        val created = component(XOR_EAX_RET)
        val layer = component(movEax(LAYER))
        vector(layer, OInterfaceComponent.STATIC_CHILDREN, laid)
        vector(layer, OInterfaceComponent.SLOT_CHILDREN, created)
        assertTrue(layer.hasChildVectors)
        assertEquals(listOf(laid.ptr.address()), layer.staticChildren.map { it.ptr.address() })
        assertEquals(listOf(created.ptr.address()), layer.createdChildren.map { it.ptr.address() })
        assertEquals(listOf(laid.ptr.address(), created.ptr.address()), layer.slotChildren.map { it.ptr.address() })

        val widget = component(XOR_EAX_RET)
        vector(widget, OInterfaceComponent.STATIC_CHILDREN, laid)
        assertFalse(widget.hasChildVectors)
        assertTrue(widget.staticChildren.isEmpty())
        assertTrue(widget.slotChildren.isEmpty())
    }

    @Test
    fun `a non-empty dynamic list is drawn instead of the static one`() {
        assumeTrue(Platform.current != Platform.WINDOWS)
        val shown = component(XOR_EAX_RET)
        val covered = component(XOR_EAX_RET)
        val parent = component(movEax(LAYER))
        assertTrue(parent.draws(covered), "no list at all fails open")

        vector(parent, OInterfaceComponent.STATIC_CHILDREN, covered)
        assertTrue(parent.draws(covered))
        assertFalse(parent.draws(shown))

        vector(parent, OInterfaceComponent.DYNAMIC_CHILDREN, shown)
        assertTrue(parent.draws(shown))
        assertFalse(parent.draws(covered))
        assertEquals(listOf(covered.ptr.address()), parent.staticChildren.map { it.ptr.address() })
    }

    @Test
    fun `a removed slot is neither listed nor drawn`() {
        assumeTrue(Platform.current != Platform.WINDOWS)
        val child = component(XOR_EAX_RET)
        val parent = component(movEax(LAYER))
        vector(parent, OInterfaceComponent.SLOT_CHILDREN, child, removed = true)
        assertTrue(parent.createdChildren.isEmpty())
        assertFalse(parent.draws(child))
    }

    private fun component(getterCode: ByteArray): InterfaceComponent {
        val code = arena.allocate(16L).also { MemorySegment.copy(getterCode, 0, it, JAVA_BYTE, 0L, getterCode.size) }
        val vtable = arena.allocate(0x40L).also { it.set(JAVA_LONG_UNALIGNED, OInterfaceComponent.KIND_GETTER, code.address()) }
        val component = arena.allocate(0x1000L).also { it.set(JAVA_LONG_UNALIGNED, 0L, vtable.address()) }
        return InterfaceComponent(component)
    }

    private fun vector(owner: InterfaceComponent, offset: Long, child: InterfaceComponent, removed: Boolean = false) {
        val slot = arena.allocate(OChildSlot.SIZE)
        if (removed) slot.set(JAVA_BYTE, OChildSlot.REMOVED, 1)
        slot.set(JAVA_LONG_UNALIGNED, OChildSlot.COMPONENT, child.ptr.address())
        val header = owner.ptr.reinterpret(0x1000L)
        header.set(JAVA_LONG_UNALIGNED, offset, slot.address())
        header.set(JAVA_LONG_UNALIGNED, offset + 8, slot.address() + OChildSlot.SIZE)
    }

    private companion object {
        const val LAYER = 20
        val XOR_EAX_RET = byteArrayOf(0x31, 0xC0.toByte(), 0xC3.toByte())

        fun movEax(value: Int) = byteArrayOf(
            0xB8.toByte(), value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte(), 0xC3.toByte(),
        )
    }
}
