package com.projectx.markers.spec

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.util.spec.IsolatedHome
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileMarkerStoreSpecTest {

    private val home = IsolatedHome()
    private val file get() = File(home.projectx, "tile-markers.json")

    @AfterTest
    fun cleanUp() = home.close()

    private fun scenario(method: String) = home.run(TileMarkerStoreScenarios::class.java.name, method)

    private fun writeFile(text: String) {
        home.projectx.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `an untouched store is empty and never creates its file`() = scenario("emptyStoreWritesNothing")

    @Test
    fun `group names are trimmed, unique and kept in insertion order`() = scenario("groupsAreAddedTrimmedAndUnique")

    @Test
    fun `groups can be removed and cleared`() = scenario("groupsAreRemovedAndCleared")

    @Test
    fun `disabling hides a group and the two recolour operations differ on tile overrides`() = scenario("enablingAndColouringGroups")

    @Test
    fun `renaming keeps the group's place and tiles`() = scenario("renamingKeepsPositionAndMarkers")

    @Test
    fun `mark at places an empty tile, recolours a different colour and removes the same colour`() = scenario("markAtPlacesRecoloursAndRemoves")

    @Test
    fun `mark at compares with the colour a tile inherits from its group`() = scenario("markAtComparesAgainstTheInheritedColour")

    @Test
    fun `mark at moves a recoloured tile to the end of its group`() = scenario("markAtMovesAnEditedTileToTheEnd")

    @Test
    fun `toggle adds a missing tile and removes a present one`() = scenario("toggleAddsAndRemoves")

    @Test
    fun `labels, colours and removal address a tile by its key`() = scenario("markersAreEditedByKey")

    @Test
    fun `remove tile clears it from every group in one undo step`() = scenario("removeTileClearsItFromEveryGroup")

    @Test
    fun `a rectangle adds only the tiles not already marked`() = scenario("rectanglesAddOnlyNewTiles")

    @Test
    fun `rectangle area counts tiles inclusively`() = scenario("rectangleAreaIsInclusive")

    @Test
    fun `visible markers come from enabled groups on one plane with resolved colours`() = scenario("visibleMarkersResolveColourAndFilterByPlane")

    @Test
    fun `groups returns a snapshot`() = scenario("groupsReturnsASnapshot")

    @Test
    fun `undo and redo step through edits and a new edit clears redo`() = scenario("undoAndRedoStepThroughStructuralEdits")

    @Test
    fun `edits that change nothing leave no undo step`() = scenario("failedEditsLeaveNoUndoStep")

    @Test
    fun `label edits are not undo steps`() = scenario("labelEditsAreNotUndoSteps")

    @Test
    fun `label edits keep the redo history`() = scenario("labelEditsKeepRedoHistory")

    @Test
    fun `undo history is capped at fifty steps`() = scenario("undoKeepsTheLastFiftySteps")

    @Test
    fun `markers are saved as a JSON list of groups and read back on the next load`() {
        scenario("writeSample")
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonArray
        assertTrue(file.readText().startsWith("[\n  {\n    \"name\": \"Route\""), file.readText())
        assertEquals(2, root.size())

        val route = root[0] as JsonObject
        assertEquals(setOf("name", "color", "enabled", "markers"), route.keySet())
        assertEquals("Route", route["name"].asString)
        assertEquals(0xFF00FF00.toInt(), route["color"].asInt)
        assertTrue(route["enabled"].asBoolean)
        val markers = route["markers"].asJsonArray
        val inherited = markers[0] as JsonObject
        assertEquals(setOf("x", "y", "plane", "label"), inherited.keySet())
        assertEquals(listOf(3200, 3201, 0), listOf(inherited["x"].asInt, inherited["y"].asInt, inherited["plane"].asInt))
        assertEquals("", inherited["label"].asString)
        val coloured = markers[1] as JsonObject
        assertEquals(setOf("x", "y", "plane", "color", "label"), coloured.keySet())
        assertEquals(0xFF0000FF.toInt(), coloured["color"].asInt)
        assertEquals("bank <&> \"quoted\" ✓", coloured["label"].asString)

        val off = root[1] as JsonObject
        assertEquals("Off", off["name"].asString)
        assertFalse(off["enabled"].asBoolean)
        assertEquals(0, off["markers"].asJsonArray.size())

        scenario("readSample")
    }

    @Test
    fun `flush writes only when there are unsaved edits`() = scenario("flushWritesOnlyWhenSomethingChanged")

    @Test
    fun `loading a file does not rewrite it`() {
        val compact = """[{"name":"Compact","color":1,"enabled":true,"markers":[]}]"""
        writeFile(compact)
        scenario("loadingIsNotAnEdit")
        assertEquals(compact, file.readText())
    }

    @Test
    fun `undo and redo are saved like any edit`() = scenario("undoIsSavedToo")

    @Test
    fun `edits are saved in the background once editing goes idle`() = scenario("editsAreSavedOnceTheyGoIdle")

    @Test
    fun `stopping the flusher saves pending edits`() = scenario("stopFlusherSavesPendingEdits")

    @Test
    fun `a hand written file with omitted colours loads`() {
        writeFile(
            """
            [
              {"name": "Hand", "color": 255, "enabled": true, "markers": [
                {"x": 1, "y": 2, "plane": 0, "label": "x"},
                {"x": 3, "y": 4, "plane": 2, "color": -1, "label": ""}
              ]}
            ]
            """.trimIndent(),
        )
        scenario("handWrittenFileLoads")
    }

    @Test
    fun `a corrupt file loads as an empty store`() {
        writeFile("[{\"name\": \"Broken\", ")
        scenario("unreadableFileStartsEmpty")
    }

    @Test
    fun `an empty or null file loads as an empty store`() {
        writeFile("")
        scenario("unreadableFileStartsEmpty")
        writeFile("null")
        scenario("unreadableFileStartsEmpty")
    }
}
