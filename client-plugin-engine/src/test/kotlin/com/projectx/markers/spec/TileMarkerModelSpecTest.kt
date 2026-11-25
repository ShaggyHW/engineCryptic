package com.projectx.markers.spec

import com.projectx.markers.MarkerGroup
import com.projectx.markers.TileMarker
import com.projectx.markers.TileMarkerState
import com.projectx.markers.TileMarkerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TileMarkerModelSpecTest {

    @Test
    fun `a marker key is x, y and plane joined by colons`() {
        assertEquals("3200:3201:0", TileMarker.key(3200, 3201, 0))
        assertEquals("-5:7:3", TileMarker.key(-5, 7, 3))
        assertEquals("3200:3201:2", TileMarker(3200, 3201, 2).key)
    }

    @Test
    fun `a marker inherits its group colour and has no label by default`() {
        val marker = TileMarker(1, 2, 3)
        assertNull(marker.color)
        assertEquals("", marker.label)
        assertEquals(TileMarker(1, 2, 3, null, ""), marker)
    }

    @Test
    fun `markers are equal by every field, label and colour included`() {
        assertEquals(TileMarker(1, 2, 0, 5, "a"), TileMarker(1, 2, 0, 5, "a"))
        assertEquals(false, TileMarker(1, 2, 0, 5, "a") == TileMarker(1, 2, 0, 5, "b"))
        assertEquals(TileMarker(1, 2, 0, 9, "x"), TileMarker(1, 2, 0).copy(color = 9, label = "x"))
    }

    @Test
    fun `a new group is enabled and empty`() {
        val group = MarkerGroup("Route", 0x11223344)
        assertEquals(true, group.enabled)
        assertEquals(emptyList(), group.markers)
        val (name, color, enabled, markers) = group
        assertEquals("Route", name)
        assertEquals(0x11223344, color)
        assertEquals(true, enabled)
        assertEquals(emptyList(), markers)
    }

    @Test
    fun `a rendered marker carries its resolved colour`() {
        val rendered = TileMarkerStore.Rendered(10, 20, 1, 0x7F00FF00, "here")
        assertEquals(listOf(10, 20, 1, 0x7F00FF00), listOf(rendered.x, rendered.y, rendered.plane, rendered.color))
        assertEquals("here", rendered.label)
        assertEquals(rendered, rendered.copy())
    }

    @Test
    fun `tool state defaults to off, no group and cyan`() {
        assertEquals(false, TileMarkerState.toolActive.value)
        assertEquals("", TileMarkerState.newGroupName.value)
        assertEquals(listOf(0, 0, 0, 0), listOf(TileMarkerState.bulkX1, TileMarkerState.bulkY1, TileMarkerState.bulkX2, TileMarkerState.bulkY2).map { it.value })
        assertEquals(listOf(0f, 1f, 1f), listOf(TileMarkerState.colorR.value, TileMarkerState.colorG.value, TileMarkerState.colorB.value))
        assertEquals(0xFFFFFF00.toInt(), TileMarkerState.activeColor())
    }

    @Test
    fun `the active colour packs opaque RGB with red in the low byte`() {
        val r = TileMarkerState.colorR.value
        val g = TileMarkerState.colorG.value
        val b = TileMarkerState.colorB.value
        try {
            TileMarkerState.colorR.value = 1f
            TileMarkerState.colorG.value = 0f
            TileMarkerState.colorB.value = 0f
            assertEquals(0xFF0000FF.toInt(), TileMarkerState.activeColor())
            TileMarkerState.colorR.value = 0.5f
            TileMarkerState.colorG.value = 2f
            TileMarkerState.colorB.value = -1f
            assertEquals(0xFF00FF7F.toInt(), TileMarkerState.activeColor())
        } finally {
            TileMarkerState.colorR.value = r
            TileMarkerState.colorG.value = g
            TileMarkerState.colorB.value = b
        }
    }

    @Test
    fun `loading a packed colour into the picker drops its alpha and round trips`() {
        val r = TileMarkerState.colorR.value
        val g = TileMarkerState.colorG.value
        val b = TileMarkerState.colorB.value
        try {
            TileMarkerState.loadPickerColor(0x33804020)
            assertEquals(0x20 / 255f, TileMarkerState.colorR.value)
            assertEquals(0x40 / 255f, TileMarkerState.colorG.value)
            assertEquals(0x80 / 255f, TileMarkerState.colorB.value)
            assertEquals(0xFF804020.toInt(), TileMarkerState.activeColor())
            for (channel in 0..255) {
                val packed = (channel shl 16) or ((255 - channel) shl 8) or channel
                TileMarkerState.loadPickerColor(packed)
                assertEquals(packed or (0xFF shl 24), TileMarkerState.activeColor(), "channel $channel")
            }
        } finally {
            TileMarkerState.colorR.value = r
            TileMarkerState.colorG.value = g
            TileMarkerState.colorB.value = b
        }
    }
}
