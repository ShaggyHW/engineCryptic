package com.projectx.game.hooks.impl

import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.nxt.OInterfaceComponent
import com.projectx.game.nxt.OInterfaceParent
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.game.nxt.types.Vector
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * Remembers where each drawn child-slot component last landed on screen. The draw hook runs every frame for every
 * slot list, so it allocates nothing for a component whose rect has not moved, and forgets components it has not
 * drawn for a while instead of keeping every address it ever saw.
 */
object InterfaceComponentRectCapture {
    private const val FRESHNESS_NANOS = 50_000_000L
    private const val EVICT_INTERVAL_NANOS = 1_000_000_000L
    private const val MAX_SLOTS_PER_LIST = 8_192L

    // Reads by absolute address through one segment, so a frame's reads create no segment objects.
    private val memory: MemorySegment = MemorySegment.NULL.reinterpret(Long.MAX_VALUE)

    private class Capture(var rect: ScreenRect, var capturedAtNanos: Long)

    private val byPtr = Long2ObjectOpenHashMap<Capture>()
    private var lastEvictNanos = 0L

    fun lookup(componentPtr: MemorySegment): ScreenRect? {
        val capture = synchronized(byPtr) { byPtr.get(componentPtr.address()) ?: return null }
        synchronized(capture) {
            if (System.nanoTime() - capture.capturedAtNanos > FRESHNESS_NANOS) return null
            return capture.rect
        }
    }

    @JvmStatic
    @Hook("INTERFACEMANAGER_DRAWCOMPONENTLIST")
    fun drawSlotChildrenHook(
        ifManager: MemorySegment,
        slotVec: MemorySegment,
        slotScreenX: Int,
        slotScreenY: Int,
        origScreenX: Int,
        origScreenY: Int,
        parentW: Int,
        parentH: Int,
        dt: Float,
    ) {
        try { captureSlotRects(slotVec.address(), slotScreenX, slotScreenY) } catch (_: Throwable) {}
        HookManager.trampoline(::drawSlotChildrenHook.name)
            .invoke(ifManager, slotVec, slotScreenX, slotScreenY, origScreenX, origScreenY, parentW, parentH, dt)
    }

    private fun captureSlotRects(slotVecAddr: Long, slotScreenX: Int, slotScreenY: Int) {
        if (slotVecAddr == 0L) return
        val stride = OInterfaceParent.CHILD_SLOT_STRIDE
        val hiddenFlag = OInterfaceParent.CHILD_SLOT_HIDDEN_FLAG
        val slotPtr = OInterfaceParent.CHILD_SLOT_PTR
        val relX = OInterfaceComponent.PARENT_REL_X
        val relY = OInterfaceComponent.PARENT_REL_Y
        val width = OInterfaceComponent.SCREEN_WIDTH
        val height = OInterfaceComponent.SCREEN_HEIGHT

        val begin = memory.get(JAVA_LONG, slotVecAddr + Vector.OVector.VECTOR_BEGIN)
        val end = memory.get(JAVA_LONG, slotVecAddr + Vector.OVector.VECTOR_END)
        if (begin == 0L || end <= begin || stride <= 0L || (end - begin) / stride > MAX_SLOTS_PER_LIST) return

        val now = System.nanoTime()
        synchronized(byPtr) {
            var entry = begin
            while (entry < end) {
                // A removed slot keeps a stale shared_ptr to a torn-down component; the draw skips it too.
                if (memory.get(JAVA_BYTE, entry + hiddenFlag) == 0.toByte()) {
                    val componentAddr = memory.get(JAVA_LONG, entry + slotPtr)
                    if (componentAddr != 0L) {
                        record(
                            componentAddr,
                            memory.get(JAVA_INT, componentAddr + relX) + slotScreenX,
                            memory.get(JAVA_INT, componentAddr + relY) + slotScreenY,
                            memory.get(JAVA_INT, componentAddr + width),
                            memory.get(JAVA_INT, componentAddr + height),
                            now,
                        )
                    }
                }
                entry += stride
            }
            if (now - lastEvictNanos > EVICT_INTERVAL_NANOS) {
                lastEvictNanos = now
                byPtr.values.removeIf { now - it.capturedAtNanos > EVICT_INTERVAL_NANOS }
            }
        }
    }

    private fun record(componentAddr: Long, x: Int, y: Int, w: Int, h: Int, now: Long) {
        val existing = byPtr.get(componentAddr)
        if (existing == null) {
            byPtr.put(componentAddr, Capture(ScreenRect(x, y, w, h), now))
            return
        }
        synchronized(existing) {
            val rect = existing.rect
            if (rect.x != x || rect.y != y || rect.width != w || rect.height != h) existing.rect = ScreenRect(x, y, w, h)
            existing.capturedAtNanos = now
        }
    }
}
