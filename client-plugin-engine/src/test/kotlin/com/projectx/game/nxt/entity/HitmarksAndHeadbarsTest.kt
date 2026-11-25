package com.projectx.game.nxt.entity

import com.projectx.game.nxt.OHit
import com.projectx.game.nxt.OHitmarksAndHeadbars
import com.projectx.game.nxt.extent
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.test.Test
import kotlin.test.assertEquals

/** Hit slots read over a synthetic block laid out as the client's AddHitmark writes it. */
class HitmarksAndHeadbarsTest {
    private val arena = Arena.ofAuto()

    @Test
    fun `reads as many slots as the block holds, never past them`() {
        for (capacity in listOf(6, 8)) {
            val hits = block(capacity, Slot(TYPELESS, damage = 10, created = 88033, duration = 60)).hits!!
            assertEquals(capacity, hits.size, "capacity $capacity")
            assertEquals(HitType.TYPELESS, hits[0].type)
            assertEquals(10, hits[0].damage)
            assertEquals(88033, hits[0].createdClientcycle)
            assertEquals(List(capacity - 1) { -1 }, hits.drop(1).map { it.typeId })
        }
    }

    @Test
    fun `a block with no slots has no hits`() {
        assertEquals(emptyList(), block(0).hits)
    }

    private class Slot(val type: Int, val damage: Int, val created: Int, val duration: Int)

    /** The slots, then bytes that are no hit at all, as the heap holds whatever comes next. */
    private fun block(capacity: Int, vararg filled: Slot): HitmarksAndHeadbars {
        val stride = OHit.STRIDE
        val slots = arena.allocate(stride * (capacity + 2))
        for (i in 0 until capacity + 2) {
            val at = i * stride
            val slot = filled.getOrNull(i)
            val junk = i >= capacity
            slots.set(JAVA_INT, at + OHit.TYPE, if (junk) 0x1010100 else slot?.type ?: -1)
            slots.set(JAVA_INT, at + OHit.DAMAGE, slot?.damage ?: -1)
            slots.set(JAVA_INT, at + OHit.CLIENTCYCLE_CREATED, slot?.created ?: 0)
            slots.set(JAVA_INT, at + OHit.UNKNEG1_1, -1)
            slots.set(JAVA_INT, at + OHit.UNKNEG1_2, -1)
            slots.set(JAVA_INT, at + OHit.DURATION_CLIENTCYLES, slot?.duration ?: 0)
        }
        val header = arena.allocate(OHitmarksAndHeadbars.extent)
        header.set(JAVA_INT, OHitmarksAndHeadbars.HIT_CAPACITY, capacity)
        header.set(ADDRESS, OHitmarksAndHeadbars.HIT_VECTOR, slots)
        return HitmarksAndHeadbars(header)
    }

    private companion object {
        const val TYPELESS = 144
    }
}
