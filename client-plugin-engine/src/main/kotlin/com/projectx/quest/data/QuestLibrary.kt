package com.projectx.quest.data

import com.google.gson.JsonParser
import org.projectx.core.ProjectPaths
import world.gregs.voidps.cache.Cache
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Paths
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every quest the helper knows: the guides (bundled or edited, one JSON file per quest) plus, once the game cache
 * is open, a guide-less entry for each real cache quest no guide covers yet.
 */
object QuestLibrary {
    private const val REPO_GUIDES = "client-plugin-engine/src/main/resources/quest-data"
    private const val RESOURCE_GUIDES = "quest-data/quests"

    private class Listing(val ordered: List<Quest>) {
        val slugs: Map<String, Quest> = ordered.associateBy { it.slug }
        val names: Map<String, Quest> = ordered.associateBy { it.name.lowercase() }
    }

    @Volatile private var listing = Listing(listOf())
    @Volatile private var guides: List<Quest> = listOf()
    @Volatile private var cacheConsulted = false
    private val warmStarted = AtomicBoolean()
    private val cacheMergeRunning = AtomicBoolean()

    private val bundleCandidates: List<File> by lazy {
        listOfNotNull(
            System.getenv("PROJECTX_QUEST_DATA")?.let(::File),
            ProjectPaths.root?.let { File(it, "$REPO_GUIDES/quests.json") },
        )
    }

    private val directoryCandidates: List<File> by lazy {
        listOfNotNull(
            System.getenv("PROJECTX_QUEST_DIR")?.let(::File),
            ProjectPaths.root?.let { File(it, "$REPO_GUIDES/quests") },
            File(ProjectPaths.home, RESOURCE_GUIDES),
        )
    }

    @Volatile var lastLoadMs: Long = 0L
        private set

    @Volatile var lastLoadSource: String = "classpath"
        private set

    @Volatile var lastSavedPath: String? = null
        private set

    @Volatile var loading: Boolean = false
        private set

    val quests: List<Quest> get() = current().ordered

    fun bySlug(slug: String): Quest? = current().slugs[slug]

    fun byName(name: String): Quest? = current().names[name.lowercase()]

    fun warm() {
        if (!warmStarted.compareAndSet(false, true)) return
        loading = true
        Thread({
            try {
                reload()
            } catch (t: Throwable) {
                println("[QuestLibrary] background load failed: $t")
            } finally {
                loading = false
            }
        }, "projectx-quest-load").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun reload(): Int {
        val origins = ArrayList<String>()
        val bySlug = LinkedHashMap<String, Quest>()
        readBundle(origins).forEach { bySlug[it.slug] = it }
        val directory = existingDirectory()
        if (directory != null) {
            val fromFiles = readDirectory(directory)
            fromFiles.forEach { bySlug[it.slug] = it }
            if (fromFiles.isNotEmpty()) origins += "${directory.absolutePath} (${fromFiles.size} quests)"
        } else {
            val fromJar = readClasspath()
            fromJar.forEach { bySlug[it.slug] = it }
            if (fromJar.isNotEmpty()) origins += "classpath:/$RESOURCE_GUIDES (${fromJar.size} quests)"
        }
        if (bySlug.isEmpty()) println("[QuestLibrary] no quest guides found in any source")
        lastLoadSource = origins.joinToString("; ").ifEmpty { "(missing)" }
        guides = bySlug.values.toList()
        publish()
        return listing.ordered.size.also { lastLoadMs = System.currentTimeMillis() }
    }

    fun perQuestFile(slug: String): File {
        val directory = existingDirectory()
            ?: directoryCandidates.firstOrNull { it.mkdirs() || it.isDirectory }
            ?: error("no writable quest directory among $directoryCandidates")
        return File(directory, "$slug.json")
    }

    @Synchronized
    fun saveQuest(quest: Quest): File? = try {
        val file = write(quest)
        lastSavedPath = file.absolutePath
        reload()
        file
    } catch (t: Throwable) {
        println("[QuestLibrary] could not save ${quest.slug}: $t")
        t.printStackTrace()
        null
    }

    @Synchronized
    fun splitBundleToFiles(overwrite: Boolean = false): Int {
        val written = guides.count { quest -> exportGuide(quest, overwrite) }
        if (written != 0) reload()
        return written
    }

    private fun exportGuide(quest: Quest, overwrite: Boolean): Boolean = try {
        (overwrite || !perQuestFile(quest.slug).exists()).also { wanted -> if (wanted) write(quest) }
    } catch (t: Throwable) {
        println("[QuestLibrary] could not write ${quest.slug}: $t")
        false
    }

    private fun current(): Listing {
        warm()
        if (!cacheConsulted && !loading && Cache.initialised) mergeCacheInBackground()
        return listing
    }

    /** The cache usually opens after the first load, so the guide-less quests join the list as soon as it does. */
    private fun mergeCacheInBackground() {
        if (!cacheMergeRunning.compareAndSet(false, true)) return
        Thread({
            try {
                synchronized(this) { if (!cacheConsulted) publish() }
            } finally {
                cacheMergeRunning.set(false)
            }
        }, "projectx-quest-cache").apply { isDaemon = true }.start()
    }

    private fun publish() {
        val known = guides
        val extra = if (Cache.initialised) {
            cacheConsulted = true
            try {
                CacheQuestCatalog.withoutGuides(Cache.quests.toList(), known)
            } catch (t: Throwable) {
                println("[QuestLibrary] cache quests unavailable: $t")
                listOf()
            }
        } else {
            cacheConsulted = false
            listOf()
        }
        listing = Listing((known + extra).sortedBy(::sortKey))
    }

    private fun write(quest: Quest): File {
        val file = perQuestFile(quest.slug)
        file.parentFile.mkdirs()
        file.writeText(GuideFormat.print(quest), Charsets.UTF_8)
        return file
    }

    private fun existingDirectory(): File? = directoryCandidates.firstOrNull { it.isDirectory }

    private fun readBundle(origins: MutableList<String>): List<Quest> {
        val file = bundleCandidates.firstOrNull { it.isFile }
        val text = try {
            file?.readText(Charsets.UTF_8) ?: javaClass.getResource("/quest-data/quests.json")?.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            println("[QuestLibrary] could not read the quest bundle: $t")
            null
        } ?: return listOf()
        val quests = try {
            parseBundle(text)
        } catch (t: Throwable) {
            println("[QuestLibrary] ignoring unreadable quest bundle ${file ?: "classpath"}: $t")
            return listOf()
        } ?: return listOf()
        origins += file?.path ?: "classpath"
        return quests
    }

    private fun readDirectory(directory: File): List<Quest> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .sortedBy(File::getName)
            .mapNotNull { file -> decode(file.name) { file.readText(Charsets.UTF_8) } }

    private fun readClasspath(): List<Quest> {
        val loader = javaClass.classLoader ?: return listOf()
        return loader.getResources(RESOURCE_GUIDES).toList()
            .flatMap(::guideTexts)
            .toMap(TreeMap())
            .mapNotNull { (name, text) -> decode(name) { text() } }
    }

    private fun guideTexts(folder: URL): List<Pair<String, () -> String>> = when (folder.protocol) {
        "file" -> Paths.get(folder.toURI()).toFile()
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .map { file -> file.name to { file.readText(Charsets.UTF_8) } }
        "jar" -> {
            val connection = folder.openConnection() as JarURLConnection
            connection.useCaches = false
            connection.jarFile.use { jar ->
                jar.entries().toList()
                    .filter { !it.isDirectory && it.name.substringBeforeLast('/') == RESOURCE_GUIDES && it.name.endsWith(".json") }
                    .map { entry ->
                        val text = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                        entry.name.substringAfterLast('/') to { text }
                    }
            }
        }
        else -> listOf()
    }

    private inline fun decode(name: String, read: () -> String): Quest? = try {
        GuideFormat.parse(read())
    } catch (t: Throwable) {
        println("[QuestLibrary] skipping quest file $name: ${t.message ?: t}")
        null
    }
}

private fun sortKey(quest: Quest): String = quest.name.lowercase()

private fun parseBundle(text: String): List<Quest>? {
    val root = JsonParser.parseString(text).asJsonObject
    val quests = root.get("quests")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    return quests.map { GuideFormat.quest(it.asJsonObject) }
}
