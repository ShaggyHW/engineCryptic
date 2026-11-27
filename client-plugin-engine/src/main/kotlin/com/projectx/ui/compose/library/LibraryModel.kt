package com.projectx.ui.compose.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.store.Entitlements
import com.projectx.store.StoreAccess
import com.projectx.ui.ScriptLibrary
import com.projectx.ui.ScriptSource
import com.projectx.ui.UIState
import com.projectx.ui.compose.OverlayText

data class LibraryEntry(
    val meta: ScriptMetadata,
    val category: ScriptCategory,
    val source: ScriptSource,
    val running: Boolean,
    val favorite: Boolean,
    val startedAt: Long?,
    val local: Boolean = false,
) {
    // A developer's own copy can share a store script's class name, so it is told apart here.
    val id: String get() = if (local) "local:${meta.scriptClass.name}" else meta.scriptClass.name
}

/** One list of scripts in the panel, with its own selection, filter and search: the Library, or the Local scripts. */
interface ScriptShelf {
    val entries: List<LibraryEntry>
    var selectedId: String?
    var filter: LibraryFilter
    val search: OverlayText
    val local: Boolean

    val runningCount: Int get() = entries.count { it.running }
}

enum class LibraryFilter(val label: String) { All("All"), Running("Running"), Favorites("Starred") }

enum class DetailTab(val label: String) { Overview("Overview"), Settings("Settings") }

/**
 * The library as the interface sees it.
 *
 * Rebuilt on the main-logic thread, where script state changes, and published as Compose state so the render
 * thread only ever reads a finished list. Publishing only on change keeps an idle panel from recomposing.
 */
object LibraryModel : ScriptShelf {
    override var entries by mutableStateOf(emptyList<LibraryEntry>())
        internal set

    /** What the store says about each listed script in the library, keyed like [LibraryEntry.id]. */
    var access by mutableStateOf(emptyMap<String, StoreAccess>())
        internal set

    override var selectedId by mutableStateOf<String?>(null)
    override var filter by mutableStateOf(LibraryFilter.All)
    var detailTab by mutableStateOf(DetailTab.Overview)
    var sendingLogsFor by mutableStateOf<String?>(null)
    override val search = OverlayText(maxLength = 40)
    override val local = false

    fun refresh() {
        val favorites = UIState.favoriteScripts
        val running = ScriptExecutor.activeScripts.mapTo(HashSet()) { it.javaClass }
        fun entry(meta: ScriptMetadata, local: Boolean) = LibraryEntry(
            meta = meta,
            category = categoryOf(meta),
            source = ScriptLibrary.sourceOf(meta),
            running = meta.scriptClass in running,
            favorite = meta.scriptClass in favorites,
            startedAt = ScriptExecutor.startedAt(meta.scriptClass),
            local = local,
        )
        val order = compareByDescending<LibraryEntry> { it.running }.thenByDescending { it.favorite }.thenBy { it.meta.name.lowercase() }
        val (local, store) = ScriptExecutor.scripts.values.partition { ScriptExecutor.isLocal(it.scriptClass) }
        val next = store
            .filter { ScriptLibrary.contains(it) || it.scriptClass in running }
            .map { entry(it, local = false) }
            .sortedWith(order)
        if (next != entries) entries = next
        val nextLocal = local.map { entry(it, local = true) }.sortedWith(order)
        if (nextLocal != LocalScripts.entries) LocalScripts.entries = nextLocal
        val nextAccess = (next + nextLocal)
            .mapNotNull { entry -> Entitlements.accessOf(entry.meta.scriptClass.simpleName)?.let { entry.id to it } }.toMap()
        if (nextAccess != access) access = nextAccess
    }

    fun visible(entries: List<LibraryEntry>, query: String, filter: LibraryFilter): List<LibraryEntry> {
        val q = query.trim().lowercase()
        return entries.filter { entry ->
            val matchesFilter = when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Running -> entry.running
                LibraryFilter.Favorites -> entry.favorite
            }
            matchesFilter && (q.isEmpty() || entry.meta.name.lowercase().contains(q) || entry.meta.author.lowercase().contains(q))
        }
    }

    private fun categoryOf(meta: ScriptMetadata): ScriptCategory =
        meta.scriptClass.getAnnotation(ScriptDescription::class.java)?.category ?: ScriptCategory.OTHER
}

/** The developer's own scripts, from `~/.projectx/scripts/dev`, kept apart from everything the store installs. */
object LocalScripts : ScriptShelf {
    override var entries by mutableStateOf(emptyList<LibraryEntry>())
        internal set
    override var selectedId by mutableStateOf<String?>(null)
    override var filter by mutableStateOf(LibraryFilter.All)
    override val search = OverlayText(maxLength = 40)
    override val local = true
}
