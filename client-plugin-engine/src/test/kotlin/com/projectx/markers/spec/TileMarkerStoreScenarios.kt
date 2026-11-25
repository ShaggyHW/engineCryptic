package com.projectx.markers.spec

import com.projectx.markers.MarkerGroup
import com.projectx.markers.TileMarker
import com.projectx.markers.TileMarkerStore
import com.projectx.markers.TileMarkerStore.Rendered
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Run only through IsolatedHome, which gives every call a fresh store over its own user home. */
class TileMarkerStoreScenarios : AutoCloseable {

    private val store = TileMarkerStore
    private val file = File(System.getProperty("user.home"), ".projectx/tile-markers.json")

    private val red = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFFFF0000.toInt()

    override fun close() = store.stopFlusher()

    private fun names() = store.groups().map { it.name }
    private fun group(name: String) = assertNotNull(store.groups().firstOrNull { it.name == name })
    private fun markers(name: String) = group(name).markers

    fun emptyStoreWritesNothing() {
        assertEquals(emptyList(), store.groups())
        assertEquals(emptyList(), store.visibleOnPlane(0))
        assertFalse(store.canUndo())
        assertFalse(store.canRedo())
        assertFalse(store.undo())
        assertFalse(store.redo())
        store.flush()
        store.stopFlusher()
        assertFalse(file.exists())
    }

    fun groupsAreAddedTrimmedAndUnique() {
        assertTrue(store.addGroup("  Route  ", red))
        assertFalse(store.addGroup("Route", green))
        assertFalse(store.addGroup(" Route", green))
        assertFalse(store.addGroup("", green))
        assertFalse(store.addGroup("   ", green))
        assertTrue(store.addGroup("Bank", green))
        assertTrue(store.addGroup("Altar", blue))
        assertEquals(listOf("Route", "Bank", "Altar"), names())
        assertEquals(MarkerGroup("Route", red, true, emptyList()), group("Route"))
        assertEquals(green, group("Bank").color)
    }

    fun groupsAreRemovedAndCleared() {
        assertFalse(store.clearAll())
        store.addGroup("A", red)
        store.addGroup("B", green)
        store.toggleTile("A", 1, 1, 0, red)
        assertFalse(store.removeGroup("Missing"))
        assertFalse(store.clearGroup("Missing"))
        assertTrue(store.clearGroup("A"))
        assertEquals(emptyList(), markers("A"))
        assertTrue(store.clearGroup("A"))
        assertTrue(store.removeGroup("B"))
        assertEquals(listOf("A"), names())
        assertTrue(store.clearAll())
        assertEquals(emptyList(), store.groups())
        assertFalse(store.clearAll())
    }

    fun enablingAndColouringGroups() {
        store.addGroup("G", red)
        store.toggleTile("G", 1, 1, 0, green)
        store.addRect("G", 2, 2, 2, 2, 0, null)
        assertFalse(store.setEnabled("Missing", false))
        assertFalse(store.setGroupColor("Missing", blue))
        assertFalse(store.recolorGroup("Missing", blue))

        assertTrue(store.setEnabled("G", false))
        assertFalse(group("G").enabled)
        assertEquals(emptyList(), store.visibleOnPlane(0))
        assertTrue(store.setEnabled("G", false))
        assertTrue(store.setEnabled("G", true))

        assertTrue(store.setGroupColor("G", blue))
        assertEquals(blue, group("G").color)
        assertEquals(listOf<Int?>(green, null), markers("G").map { it.color })
        assertEquals(listOf(green, blue), store.visibleOnPlane(0).map { it.color })

        assertTrue(store.recolorGroup("G", red))
        assertEquals(red, group("G").color)
        assertEquals(listOf<Int?>(null, null), markers("G").map { it.color })
    }

    fun renamingKeepsPositionAndMarkers() {
        store.addGroup("A", red)
        store.addGroup("B", green)
        store.addGroup("C", blue)
        store.toggleTile("B", 5, 6, 0, green)
        assertFalse(store.renameGroup("Missing", "D"))
        assertFalse(store.renameGroup("B", "   "))
        assertFalse(store.renameGroup("B", "B"))
        assertFalse(store.renameGroup("B", "C"))
        assertFalse(store.renameGroup("B", " C "))
        assertTrue(store.renameGroup("B", "  Middle "))
        assertEquals(listOf("A", "Middle", "C"), names())
        assertEquals(listOf(TileMarker(5, 6, 0, green, "")), markers("Middle"))
        assertEquals("Middle", group("Middle").name)
    }

    fun markAtPlacesRecoloursAndRemoves() {
        assertNull(store.markAt("Missing", 1, 1, 0, red))
        assertFalse(store.canUndo())
        store.addGroup("G", green)

        assertEquals(TileMarker(10, 20, 0, red, "door"), store.markAt("G", 10, 20, 0, red, "door"))
        assertEquals(TileMarker(10, 20, 0, blue, "door"), store.markAt("G", 10, 20, 0, blue))
        assertEquals(listOf(TileMarker(10, 20, 0, blue, "door")), markers("G"))
        assertNull(store.markAt("G", 10, 20, 0, blue))
        assertEquals(emptyList(), markers("G"))

        assertEquals(TileMarker(1, 1, 0, red, ""), store.markAt("G", 1, 1, 0, red))
        assertEquals(TileMarker(1, 1, 1, red, ""), store.markAt("G", 1, 1, 1, red))
        assertEquals(2, markers("G").size)
    }

    fun markAtComparesAgainstTheInheritedColour() {
        store.addGroup("G", green)
        store.addRect("G", 1, 1, 2, 1, 0, null)
        assertNull(store.markAt("G", 1, 1, 0, green))
        assertEquals(listOf(TileMarker(2, 1, 0)), markers("G"))
        assertEquals(TileMarker(2, 1, 0, red, ""), store.markAt("G", 2, 1, 0, red))
    }

    fun markAtMovesAnEditedTileToTheEnd() {
        store.addGroup("G", green)
        store.markAt("G", 1, 0, 0, red)
        store.markAt("G", 2, 0, 0, red)
        store.markAt("G", 1, 0, 0, blue)
        assertEquals(listOf(TileMarker(2, 0, 0, red), TileMarker(1, 0, 0, blue)), markers("G"))
    }

    fun toggleAddsAndRemoves() {
        assertFalse(store.toggleTile("Missing", 1, 1, 0, red))
        store.addGroup("G", green)
        assertTrue(store.toggleTile("G", 3200, 3200, 0, red))
        assertEquals(listOf(TileMarker(3200, 3200, 0, red, "")), markers("G"))
        assertTrue(store.toggleTile("G", 3200, 3200, 1, blue))
        assertTrue(store.toggleTile("G", 3200, 3200, 0, blue))
        assertEquals(listOf(TileMarker(3200, 3200, 1, blue, "")), markers("G"))
    }

    fun markersAreEditedByKey() {
        store.addGroup("G", green)
        store.toggleTile("G", 4, 5, 0, red)
        val key = TileMarker.key(4, 5, 0)

        assertFalse(store.setLabel("G", "9:9:9", "x"))
        assertFalse(store.setLabel("Missing", key, "x"))
        assertTrue(store.setLabel("G", key, "Fish here"))
        assertEquals("Fish here", markers("G").single().label)
        assertTrue(store.setLabel("G", key, ""))

        assertFalse(store.recolorMarker("G", "9:9:9", blue))
        assertFalse(store.recolorMarker("Missing", key, blue))
        assertTrue(store.recolorMarker("G", key, blue))
        assertEquals(blue, markers("G").single().color)

        assertFalse(store.removeMarker("G", "9:9:9"))
        assertFalse(store.removeMarker("Missing", key))
        assertTrue(store.removeMarker("G", key))
        assertEquals(emptyList(), markers("G"))
    }

    fun removeTileClearsItFromEveryGroup() {
        store.addGroup("A", red)
        store.addGroup("B", green)
        store.toggleTile("A", 7, 7, 0, red)
        store.toggleTile("B", 7, 7, 0, green)
        store.toggleTile("B", 8, 7, 0, green)
        assertFalse(store.removeTile(7, 7, 1))
        assertTrue(store.removeTile(7, 7, 0))
        assertEquals(emptyList(), markers("A"))
        assertEquals(listOf(TileMarker(8, 7, 0, green)), markers("B"))
        assertTrue(store.undo())
        assertEquals(1, markers("A").size)
        assertEquals(2, markers("B").size)
    }

    fun rectanglesAddOnlyNewTiles() {
        assertEquals(0, store.addRect("Missing", 0, 0, 1, 1, 0, red))
        store.addGroup("G", green)
        store.toggleTile("G", 11, 21, 0, red)
        assertEquals(5, store.addRect("G", 11, 22, 10, 20, 0, null, "zone"))
        val expected = listOf(
            TileMarker(11, 21, 0, red, ""),
            TileMarker(10, 20, 0, null, "zone"),
            TileMarker(10, 21, 0, null, "zone"),
            TileMarker(10, 22, 0, null, "zone"),
            TileMarker(11, 20, 0, null, "zone"),
            TileMarker(11, 22, 0, null, "zone"),
        )
        assertEquals(expected, markers("G"))
        assertEquals(1, store.addRect("G", 10, 20, 10, 20, 1, blue))
        assertEquals(blue, markers("G").last().color)

        store.addGroup("H", red)
        assertEquals(0, store.addRect("G", 10, 20, 11, 22, 0, red))
        assertTrue(store.undo())
        assertEquals(listOf("G"), names())
        assertEquals(7, markers("G").size)
    }

    fun rectangleAreaIsInclusive() {
        assertEquals(1L, store.rectArea(5, 5, 5, 5))
        assertEquals(6L, store.rectArea(10, 20, 11, 22))
        assertEquals(6L, store.rectArea(11, 22, 10, 20))
        assertEquals(10_000_000_000L, store.rectArea(0, 0, 99_999, 99_999))
        assertEquals(4L, store.rectArea(-1, -1, 0, 0))
    }

    fun visibleMarkersResolveColourAndFilterByPlane() {
        store.addGroup("A", red)
        store.addGroup("Hidden", blue)
        store.addGroup("B", green)
        store.addRect("A", 1, 1, 1, 1, 0, null, "a")
        store.toggleTile("A", 2, 2, 0, blue)
        store.toggleTile("A", 3, 3, 1, blue)
        store.toggleTile("Hidden", 4, 4, 0, blue)
        store.setEnabled("Hidden", false)
        store.toggleTile("B", 1, 1, 0, green)

        val expected = listOf(
            Rendered(1, 1, 0, red, "a"),
            Rendered(2, 2, 0, blue, ""),
            Rendered(1, 1, 0, green, ""),
        )
        assertEquals(expected, store.visibleOnPlane(0))
        assertEquals(listOf(Rendered(3, 3, 1, blue, "")), store.visibleOnPlane(1))
        assertEquals(emptyList(), store.visibleOnPlane(2))
    }

    fun groupsReturnsASnapshot() {
        store.addGroup("G", red)
        val before = store.groups()
        store.toggleTile("G", 1, 1, 0, red)
        store.addGroup("H", red)
        assertEquals(listOf(MarkerGroup("G", red)), before)
    }

    fun undoAndRedoStepThroughStructuralEdits() {
        store.addGroup("G", red)
        store.toggleTile("G", 1, 1, 0, red)
        store.renameGroup("G", "Renamed")
        assertTrue(store.canUndo())
        assertFalse(store.canRedo())

        assertTrue(store.undo())
        assertEquals(listOf("G"), names())
        assertTrue(store.canRedo())
        assertTrue(store.undo())
        assertEquals(emptyList(), markers("G"))
        assertTrue(store.undo())
        assertEquals(emptyList(), store.groups())
        assertFalse(store.canUndo())
        assertFalse(store.undo())

        assertTrue(store.redo())
        assertTrue(store.redo())
        assertEquals(listOf(TileMarker(1, 1, 0, red)), markers("G"))
        assertTrue(store.redo())
        assertEquals(listOf("Renamed"), names())
        assertFalse(store.redo())

        assertTrue(store.undo())
        store.addGroup("Fresh", green)
        assertFalse(store.canRedo())
        assertEquals(listOf("G", "Fresh"), names())
    }

    fun failedEditsLeaveNoUndoStep() {
        store.addGroup("G", red)
        store.addGroup("G", red)
        store.removeGroup("Missing")
        store.renameGroup("G", "G")
        store.removeTile(9, 9, 9)
        assertTrue(store.undo())
        assertFalse(store.canUndo())
    }

    fun labelEditsAreNotUndoSteps() {
        store.addGroup("G", red)
        store.toggleTile("G", 1, 1, 0, red)
        assertTrue(store.setLabel("G", "1:1:0", "typed"))
        assertTrue(store.undo())
        assertEquals(emptyList(), markers("G"))
        assertTrue(store.redo())
        assertEquals("typed", markers("G").single().label)
    }

    fun labelEditsKeepRedoHistory() {
        store.addGroup("G", red)
        store.toggleTile("G", 1, 1, 0, red)
        store.toggleTile("G", 2, 2, 0, red)
        store.undo()
        assertTrue(store.setLabel("G", "1:1:0", "x"))
        assertTrue(store.canRedo())
        assertTrue(store.redo())
        assertEquals(2, markers("G").size)
    }

    fun undoKeepsTheLastFiftySteps() {
        store.addGroup("G", red)
        repeat(60) { store.toggleTile("G", it, 0, 0, red) }
        var undone = 0
        while (store.undo()) undone++
        assertEquals(50, undone)
        assertEquals(10, markers("G").size)
        var redone = 0
        while (store.redo()) redone++
        assertEquals(50, redone)
        assertEquals(60, markers("G").size)
    }

    fun writeSample() {
        store.addGroup("Route", green)
        store.addRect("Route", 3200, 3201, 3200, 3201, 0, null)
        store.markAt("Route", 3202, 3203, 1, red, "bank <&> \"quoted\" ✓")
        store.addGroup("Off", blue)
        store.setEnabled("Off", false)
        assertFalse(file.exists())
        store.flush()
        assertTrue(file.isFile)
    }

    fun readSample() {
        val expected = listOf(
            MarkerGroup(
                "Route", green, true,
                listOf(TileMarker(3200, 3201, 0, null, ""), TileMarker(3202, 3203, 1, red, "bank <&> \"quoted\" ✓")),
            ),
            MarkerGroup("Off", blue, false, emptyList()),
        )
        assertEquals(expected, store.groups())
        assertFalse(store.canUndo())
    }

    fun flushWritesOnlyWhenSomethingChanged() {
        store.addGroup("G", red)
        store.flush()
        file.writeText("[]")
        store.flush()
        assertEquals("[]", file.readText())
        store.toggleTile("G", 1, 1, 0, red)
        store.flush()
        assertTrue(file.readText().contains("\"G\""))
    }

    fun loadingIsNotAnEdit() {
        assertEquals(listOf("Compact"), names())
        store.flush()
        store.stopFlusher()
    }

    fun undoIsSavedToo() {
        store.addGroup("G", red)
        store.addGroup("H", red)
        store.flush()
        store.undo()
        store.flush()
        assertFalse(file.readText().contains("\"H\""))
    }

    fun editsAreSavedOnceTheyGoIdle() {
        store.addGroup("Auto", red)
        val deadline = System.currentTimeMillis() + 10_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("\"Auto\""))
        store.stopFlusher()
    }

    fun stopFlusherSavesPendingEdits() {
        store.addGroup("Pending", red)
        store.stopFlusher()
        assertTrue(file.readText().contains("\"Pending\""))
    }

    fun handWrittenFileLoads() {
        val expected = listOf(
            MarkerGroup("Hand", 255, true, listOf(TileMarker(1, 2, 0, null, "x"), TileMarker(3, 4, 2, -1, ""))),
        )
        assertEquals(expected, store.groups())
        assertEquals(listOf(Rendered(1, 2, 0, 255, "x")), store.visibleOnPlane(0))
    }

    fun unreadableFileStartsEmpty() {
        assertEquals(emptyList(), store.groups())
        assertTrue(store.addGroup("New", red))
    }
}
