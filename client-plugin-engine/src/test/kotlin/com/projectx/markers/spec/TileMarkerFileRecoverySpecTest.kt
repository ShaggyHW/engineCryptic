package com.projectx.markers.spec

import com.projectx.markers.MarkerGroup
import com.projectx.markers.TileMarker
import com.projectx.markers.TileMarkerStore
import com.projectx.util.spec.IsolatedHome
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileMarkerFileRecoverySpecTest {

    private val home = IsolatedHome()
    private val file get() = File(home.projectx, "tile-markers.json")

    @AfterTest
    fun cleanUp() = home.close()

    private fun writeFile(text: String) {
        home.projectx.mkdirs()
        file.writeText(text)
    }

    private fun backups() = home.projectx.listFiles { f -> f.name.startsWith("tile-markers.json.broken-") }.orEmpty().toList()

    @Test
    fun `a corrupt file is copied aside before the store saves over it`() {
        val corrupt = "[{\"name\": \"Broken\", "
        writeFile(corrupt)
        home.run(TileMarkerRecoveryScenarios::class.java.name, "editAndSaveOverUnreadableFile")
        val copies = backups()
        assertEquals(1, copies.size)
        assertEquals(corrupt, copies.single().readText())
        assertTrue(file.readText().contains("\"New\""))
    }

    @Test
    fun `an empty or null file leaves no copy behind`() {
        writeFile("")
        home.run(TileMarkerRecoveryScenarios::class.java.name, "editAndSaveOverUnreadableFile")
        writeFile("null")
        home.run(TileMarkerRecoveryScenarios::class.java.name, "editAndSaveOverUnreadableFile")
        assertEquals(emptyList(), backups())
    }

    @Test
    fun `a hand written file may leave out enabled, label and markers`() {
        writeFile(
            """
            [
              {"name": "Bare", "color": 7},
              {"name": "Tiles", "color": 9, "markers": [{"x": 1, "y": 2, "plane": 0}]}
            ]
            """.trimIndent(),
        )
        home.run(TileMarkerRecoveryScenarios::class.java.name, "omittedFieldsTakeTheirDefaults")
        assertEquals(emptyList(), backups())
    }
}

class TileMarkerRecoveryScenarios : AutoCloseable {
    private val store = TileMarkerStore

    override fun close() = store.stopFlusher()

    fun editAndSaveOverUnreadableFile() {
        assertEquals(emptyList(), store.groups())
        assertTrue(store.addGroup("New", 1))
        store.flush()
    }

    fun omittedFieldsTakeTheirDefaults() {
        val expected = listOf(
            MarkerGroup("Bare", 7, true, emptyList()),
            MarkerGroup("Tiles", 9, true, listOf(TileMarker(1, 2, 0, null, ""))),
        )
        assertEquals(expected, store.groups())
        assertEquals(listOf(TileMarkerStore.Rendered(1, 2, 0, 9, "")), store.visibleOnPlane(0))
    }
}
