package com.projectx.traversal.hops

import java.io.IOException
import java.net.JarURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

/**
 * The shared layer's files: the contributor files of a checkout folder (`RS3_HOP_KNOWLEDGE_DIR`, or
 * `-Dprojectx.hopKnowledgeDir`), read live, else the copy shipped in the engine's `hops/` resources, read once.
 * Both hold `contrib-<16 hex>.jsonl` (hop knowledge) and `schedule-<16 hex>.jsonl` (schedule sums).
 */
internal object SharedHopData {
    const val ENV_DIR = "RS3_HOP_KNOWLEDGE_DIR"
    const val PROPERTY_DIR = "projectx.hopKnowledgeDir"
    const val CONTRIB = "contrib-"
    const val SCHEDULE = "schedule-"
    private const val RESOURCE_DIR = "hops"

    @Volatile
    var dirOverride: Path? = null

    /** The checkout's contributor folder, read live; null when this install has none. */
    fun dir(): Path? {
        dirOverride?.let { return it }
        val configured = System.getenv(ENV_DIR)?.takeIf { it.isNotBlank() } ?: System.getProperty(PROPERTY_DIR)?.takeIf { it.isNotBlank() }
            ?: return null
        return Paths.get(configured).takeIf { Files.isDirectory(it) }
    }

    fun files(dir: Path, prefix: String): List<Path> = try {
        Files.list(dir).use { files ->
            files.filter { val n = it.fileName.toString(); n.startsWith(prefix) && n.endsWith(".jsonl") }.toList().sorted()
        }
    } catch (e: IOException) {
        emptyList()
    }

    /** Names, sizes and mtimes of the files: changes when a pull or an export does. */
    fun signature(dir: Path, prefix: String): String = buildString {
        for (file in files(dir, prefix)) {
            append(file.fileName).append(':')
            try {
                append(Files.size(file)).append(':').append(Files.getLastModifiedTime(file).toMillis())
            } catch (e: IOException) {
                append('?')
            }
            append(';')
        }
    }

    /** The shipped files' lines, file by file in name order. */
    fun resourceFiles(prefix: String): List<List<String>> {
        val loader = SharedHopData::class.java.classLoader
        return resourceNames(prefix).sorted().mapNotNull { name ->
            runCatching {
                loader.getResourceAsStream("$RESOURCE_DIR/$name")?.use { it.reader(StandardCharsets.UTF_8).readLines() }
            }.onFailure { println("[hops] could not read $RESOURCE_DIR/$name: $it") }.getOrNull()
        }
    }

    private fun resourceNames(prefix: String): Set<String> {
        val names = HashSet<String>()
        val loader = SharedHopData::class.java.classLoader
        val roots = runCatching { loader.getResources(RESOURCE_DIR).toList() }.getOrDefault(emptyList()).toMutableList()
        runCatching { SharedHopData::class.java.protectionDomain.codeSource?.location }.getOrNull()?.let(roots::add)
        for (root in roots) runCatching { names += listed(root) }
        return names.filterTo(HashSet()) { it.startsWith(prefix) && it.endsWith(".jsonl") }
    }

    private fun listed(url: URL): List<String> = when (url.protocol) {
        "jar" -> {
            val jar = (url.openConnection() as JarURLConnection).also { it.useCaches = false }.jarFile
            jar.use { entriesIn(it) }
        }
        "file" -> {
            val path = Paths.get(url.toURI())
            when {
                Files.isDirectory(path) && path.fileName.toString() == RESOURCE_DIR -> files(path, "").map { it.fileName.toString() }
                Files.isDirectory(path.resolve(RESOURCE_DIR)) -> files(path.resolve(RESOURCE_DIR), "").map { it.fileName.toString() }
                Files.isRegularFile(path) -> JarFile(path.toFile()).use { entriesIn(it) }
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    private fun entriesIn(jar: JarFile): List<String> = jar.entries().toList()
        .map { it.name }
        .filter { it.startsWith("$RESOURCE_DIR/") && it.indexOf('/', RESOURCE_DIR.length + 1) < 0 }
        .map { it.substring(RESOURCE_DIR.length + 1) }
        .filter { it.isNotEmpty() }
}
