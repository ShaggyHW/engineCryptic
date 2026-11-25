package com.projectx.markers

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

data class TileMarker(
    val x: Int,
    val y: Int,
    val plane: Int,
    val color: Int? = null,
    val label: String = "",
) {
    val key: String get() = key(x, y, plane)

    companion object {
        fun key(x: Int, y: Int, plane: Int): String = "$x:$y:$plane"
    }
}

data class MarkerGroup(
    val name: String,
    val color: Int,
    val enabled: Boolean = true,
    val markers: List<TileMarker> = emptyList(),
)

object TileMarkerStore {

    data class Rendered(val x: Int, val y: Int, val plane: Int, val color: Int, val label: String)

    private const val HISTORY_DEPTH = 50
    private const val FLUSHER_TICK_MS = 500L
    private const val QUIET_BEFORE_SAVE_MS = 750L

    private val stateLock = Any()
    private val writeLock = Any()
    private val byName = LinkedHashMap<String, MarkerGroup>()
    private val undoHistory = ArrayDeque<List<MarkerGroup>>()
    private val redoHistory = ArrayDeque<List<MarkerGroup>>()
    private var diskRead = false
    private var unsaved = false
    private var lastEditAt = 0L
    private var flusher: Thread? = null

    private val storeFile: File
        get() = File(File(System.getProperty("user.home"), ".projectx"), "tile-markers.json")

    fun groups(): List<MarkerGroup> = locked { byName.values.toList() }

    fun visibleOnPlane(plane: Int): List<Rendered> = locked {
        byName.values.filter { it.enabled }.flatMap { group ->
            group.markers.filter { it.plane == plane }.map { Rendered(it.x, it.y, it.plane, it.color ?: group.color, it.label) }
        }
    }

    fun addGroup(name: String, color: Int): Boolean = step {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed in byName) return@step false
        byName[trimmed] = MarkerGroup(trimmed, color)
        true
    }

    fun removeGroup(name: String): Boolean = step { byName.remove(name) != null }

    fun clearGroup(name: String): Boolean = step { update(name) { it.copy(markers = emptyList()) } }

    fun clearAll(): Boolean = step {
        if (byName.isEmpty()) return@step false
        byName.clear()
        true
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean = step { update(name) { it.copy(enabled = enabled) } }

    fun setGroupColor(name: String, color: Int): Boolean = step { update(name) { it.copy(color = color) } }

    fun recolorGroup(name: String, color: Int): Boolean = step {
        update(name) { group -> group.copy(color = color, markers = group.markers.map { it.copy(color = null) }) }
    }

    fun renameGroup(from: String, to: String): Boolean = step {
        val newName = to.trim()
        if (from !in byName || newName.isEmpty() || newName == from || newName in byName) return@step false
        val reordered = byName.values.map { if (it.name == from) it.copy(name = newName) else it }
        byName.clear()
        reordered.forEach { byName[it.name] = it }
        true
    }

    fun setLabel(name: String, key: String, label: String): Boolean = locked {
        val group = byName[name] ?: return@locked false
        byName[name] = group.withMarker(key) { it.copy(label = label) } ?: return@locked false
        markUnsaved()
        true
    }

    fun markAt(name: String, x: Int, y: Int, plane: Int, color: Int, label: String = ""): TileMarker? = locked {
        val group = byName[name] ?: return@locked null
        val undoPoint = current()
        val tileKey = TileMarker.key(x, y, plane)
        val present = group.markers.firstOrNull { it.key == tileKey }
        val others = group.markers.filter { it.key != tileKey }
        val result = when {
            present == null -> TileMarker(x, y, plane, color, label)
            (present.color ?: group.color) == color -> null
            else -> present.copy(color = color)
        }
        byName[name] = group.copy(markers = if (result == null) others else others + result)
        remember(undoPoint)
        result
    }

    fun toggleTile(name: String, x: Int, y: Int, plane: Int, color: Int): Boolean = step {
        val tileKey = TileMarker.key(x, y, plane)
        update(name) { group ->
            if (group.markers.any { it.key == tileKey }) group.copy(markers = group.markers.filter { it.key != tileKey })
            else group.copy(markers = group.markers + TileMarker(x, y, plane, color, ""))
        }
    }

    fun recolorMarker(name: String, key: String, color: Int): Boolean = step {
        val group = byName[name] ?: return@step false
        byName[name] = group.withMarker(key) { it.copy(color = color) } ?: return@step false
        true
    }

    fun removeMarker(name: String, key: String): Boolean = step {
        val group = byName[name] ?: return@step false
        if (group.markers.none { it.key == key }) return@step false
        byName[name] = group.copy(markers = group.markers.filter { it.key != key })
        true
    }

    fun removeTile(x: Int, y: Int, plane: Int): Boolean = step {
        val tileKey = TileMarker.key(x, y, plane)
        var removed = false
        for (group in byName.values.toList()) {
            if (group.markers.none { it.key == tileKey }) continue
            byName[group.name] = group.copy(markers = group.markers.filter { it.key != tileKey })
            removed = true
        }
        removed
    }

    fun addRect(name: String, x1: Int, y1: Int, x2: Int, y2: Int, plane: Int, color: Int?, label: String = ""): Int = locked {
        val group = byName[name] ?: return@locked 0
        val taken = group.markers.mapTo(HashSet()) { it.key }
        val fresh = ArrayList<TileMarker>()
        for (x in minOf(x1, x2)..maxOf(x1, x2)) {
            for (y in minOf(y1, y2)..maxOf(y1, y2)) {
                if (taken.add(TileMarker.key(x, y, plane))) fresh += TileMarker(x, y, plane, color, label)
            }
        }
        if (fresh.isEmpty()) return@locked 0
        val undoPoint = current()
        byName[name] = group.copy(markers = group.markers + fresh)
        remember(undoPoint)
        fresh.size
    }

    fun rectArea(x1: Int, y1: Int, x2: Int, y2: Int): Long =
        (abs(x1.toLong() - x2) + 1) * (abs(y1.toLong() - y2) + 1)

    fun canUndo(): Boolean = locked { undoHistory.isNotEmpty() }

    fun canRedo(): Boolean = locked { redoHistory.isNotEmpty() }

    fun undo(): Boolean = locked { travel(undoHistory, redoHistory) }

    fun redo(): Boolean = locked { travel(redoHistory, undoHistory) }

    fun flush() {
        synchronized(writeLock) {
            val pending = synchronized(stateLock) {
                if (!unsaved) return
                unsaved = false
                byName.values.toList()
            }
            try {
                val target = storeFile
                Files.createDirectories(target.toPath().parent)
                target.writeText(MarkerFile.encode(pending), Charsets.UTF_8)
            } catch (e: Exception) {
                synchronized(stateLock) { unsaved = true }
                println("[TileMarkerStore] save failed: ${e.message}")
            }
        }
    }

    fun stopFlusher() {
        val thread = synchronized(stateLock) { flusher.also { flusher = null } }
        thread?.interrupt()
        thread?.join(2_000)
        flush()
    }

    private inline fun <R> locked(block: () -> R): R = synchronized(stateLock) {
        loadIfNeeded()
        block()
    }

    private inline fun step(block: () -> Boolean): Boolean = locked {
        val undoPoint = current()
        val changed = block()
        if (changed) remember(undoPoint)
        changed
    }

    private inline fun update(name: String, change: (MarkerGroup) -> MarkerGroup): Boolean {
        val group = byName[name] ?: return false
        byName[name] = change(group)
        return true
    }

    private inline fun MarkerGroup.withMarker(key: String, change: (TileMarker) -> TileMarker): MarkerGroup? {
        val index = markers.indexOfFirst { it.key == key }
        if (index < 0) return null
        return copy(markers = markers.toMutableList().also { it[index] = change(it[index]) })
    }

    private fun current(): List<MarkerGroup> = byName.values.toList()

    private fun restore(state: List<MarkerGroup>) {
        byName.clear()
        state.forEach { byName[it.name] = it }
    }

    private fun remember(undoPoint: List<MarkerGroup>) {
        undoHistory.pushCapped(undoPoint)
        redoHistory.clear()
        markUnsaved()
    }

    private fun travel(from: ArrayDeque<List<MarkerGroup>>, to: ArrayDeque<List<MarkerGroup>>): Boolean {
        val target = from.removeLastOrNull() ?: return false
        to.pushCapped(current())
        restore(target)
        markUnsaved()
        return true
    }

    private fun ArrayDeque<List<MarkerGroup>>.pushCapped(state: List<MarkerGroup>) {
        addLast(state)
        while (size > HISTORY_DEPTH) removeFirst()
    }

    private fun markUnsaved() {
        unsaved = true
        lastEditAt = System.currentTimeMillis()
        if (flusher?.isAlive != true) {
            flusher = Thread(::flushWhenQuiet, "tile-marker-flush").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun flushWhenQuiet() {
        while (pause()) {
            val due = synchronized(stateLock) { unsaved && System.currentTimeMillis() - lastEditAt >= QUIET_BEFORE_SAVE_MS }
            if (due) flush()
        }
    }

    private fun pause(): Boolean = try {
        Thread.sleep(FLUSHER_TICK_MS)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun loadIfNeeded() {
        if (diskRead) return
        diskRead = true
        val source = storeFile
        if (!source.isFile) return
        MarkerFile.read(source).forEach { byName[it.name] = it }
    }
}

private object MarkerFile {
    private val pretty = GsonBuilder().setPrettyPrinting().create()

    fun read(source: File): List<MarkerGroup> = try {
        decode(source.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        println("[TileMarkerStore] load failed: ${e.message}; ${preserve(source)}")
        emptyList()
    }

    private fun preserve(source: File): String = try {
        val copy = File(source.parentFile, "${source.name}.broken-${System.currentTimeMillis()}")
        Files.copy(source.toPath(), copy.toPath())
        "kept a copy at ${copy.absolutePath}"
    } catch (e: Exception) {
        "could not keep a copy: ${e.message}"
    }

    private fun decode(text: String): List<MarkerGroup> {
        if (text.isBlank()) return emptyList()
        val root = JsonParser.parseString(text)
        if (root.isJsonNull) return emptyList()
        return root.asJsonArray.mapNotNull { it.present()?.asJsonObject?.let(::group) }
    }

    fun encode(groups: List<MarkerGroup>): String {
        val root = JsonArray()
        groups.forEach { group ->
            val markers = JsonArray()
            group.markers.forEach { marker ->
                markers.add(JsonObject().apply {
                    addProperty("x", marker.x)
                    addProperty("y", marker.y)
                    addProperty("plane", marker.plane)
                    marker.color?.let { addProperty("color", it) }
                    addProperty("label", marker.label)
                })
            }
            root.add(JsonObject().apply {
                addProperty("name", group.name)
                addProperty("color", group.color)
                addProperty("enabled", group.enabled)
                add("markers", markers)
            })
        }
        return pretty.toJson(root)
    }

    private fun group(json: JsonObject): MarkerGroup = MarkerGroup(
        name = json["name"].present()?.asString ?: throw JsonParseException("a group has no name"),
        color = json["color"].int() ?: 0,
        enabled = json["enabled"].present()?.asBoolean ?: true,
        markers = json["markers"].present()?.asJsonArray?.mapNotNull { it.present()?.asJsonObject?.let(::marker) }.orEmpty(),
    )

    private fun marker(json: JsonObject): TileMarker = TileMarker(
        x = json["x"].int() ?: 0,
        y = json["y"].int() ?: 0,
        plane = json["plane"].int() ?: 0,
        color = json["color"].int(),
        label = json["label"].present()?.asString.orEmpty(),
    )

    private fun JsonElement?.present(): JsonElement? = this?.takeUnless { it.isJsonNull }

    private fun JsonElement?.int(): Int? = present()?.asJsonPrimitive?.asBigDecimal?.intValueExact()
}
