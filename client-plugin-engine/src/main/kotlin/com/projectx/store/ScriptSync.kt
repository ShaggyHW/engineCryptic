package com.projectx.store

import com.projectx.script.ScriptExecutor
import com.projectx.ui.ScriptLibrary
import com.projectx.ui.compose.GameThread
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

internal data class StoreJar(
    val internalName: String,
    val version: String,
    val url: String,
    val sha256: String?,
    val fileName: String?,
    val free: Boolean = false,
)

/**
 * Brings in the current build of every store script the account owns. Paid scripts arrive encrypted and are held in
 * memory only ([EncryptedScripts]), so nothing of them is saved on the PC and they download again at every start; free
 * ones are plain jars in the scripts folder.
 */
internal object ScriptSync {

    // Must match the launcher's naming, so whichever of the two gets there first, the other sees it done.
    fun jarName(internalName: String, version: String): String = "${stem(internalName, version)}.jar"

    fun pxeName(internalName: String, version: String): String = "${stem(internalName, version)}${EncryptedScripts.EXTENSION}"

    private fun stem(internalName: String, version: String) = "${safe(internalName)}-${safe(version).trimStart('.')}"

    private fun safe(text: String): String =
        text.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._+-") it else '-' }.joinToString("")

    private val BUILD_SUFFIXES = listOf(".jar", EncryptedScripts.EXTENSION, PxeRecord.fileFor(EncryptedScripts.EXTENSION))

    // Paid scripts the store answered with a plain jar this session; asking again every refresh would get the same.
    private val servedPlain = ConcurrentHashMap.newKeySet<String>()

    // A version starts with a digit, so `Agility-` does not claim `Agility-Course-1.0.jar`.
    fun isBuildOf(file: String, jar: StoreJar): Boolean {
        if (BUILD_SUFFIXES.none { file.endsWith(it) }) return false
        if (jar.fileName != null && file == jar.fileName) return true
        val rest = file.removePrefix("${jar.internalName}-")
        return rest != file && rest.firstOrNull()?.isDigit() == true
    }

    private fun hasEncrypted(jar: StoreJar): Boolean = EncryptedScripts.holds(jar.internalName, jar.version)

    // A paid script's plain jar from an older launcher or engine is replaced by its encrypted build.
    fun isCurrent(jar: StoreJar, present: Set<String>): Boolean =
        hasEncrypted(jar) ||
            jarName(jar.internalName, jar.version) in present && (jar.free || plainKey(jar) in servedPlain)

    private fun plainKey(jar: StoreJar) = "${jar.internalName}|${jar.version}"

    fun missing(owned: Set<String>, catalogue: List<StoreJar>, present: Set<String>): List<StoreJar> =
        catalogue.filter { it.internalName in owned && !isCurrent(it, present) }

    /** Every build on disk of a script whose current encrypted build is held in memory, plaintext jars included. */
    fun superseded(catalogue: List<StoreJar>, present: Set<String>): Set<String> =
        catalogue.filter { hasEncrypted(it) }.flatMapTo(HashSet()) { jar -> present.filter { isBuildOf(it, jar) } }

    fun sync(
        owned: Set<String>,
        catalogue: List<StoreJar>,
        token: String,
        http: HttpClient,
        dir: Path,
    ): List<StoreJar> {
        Files.createDirectories(dir)
        // Builds an older engine or launcher saved go into memory first, so they are not downloaded again.
        EncryptedScripts.adopt(dir.toFile())
        EncryptedScripts.retainOwned(owned)
        val present = listed(dir)
        val installed = missing(owned, catalogue, present).filter { install(it, token, http, dir, present) }
        val communityInstalled = needsCommunityJar(owned, catalogue, present) && installCommunityJar(http, dir)
        superseded(catalogue, listed(dir)).forEach { runCatching { Files.deleteIfExists(dir.resolve(it)) } }

        // Also catches a build the launcher installed: present, owned, but not yet in the script list.
        val available = ownedPresent(owned, catalogue, listed(dir))
        GameThread.post {
            val registered = ScriptExecutor.scripts.values.mapTo(HashSet()) { it.scriptClass.simpleName }
            // A rescan only rebuilds the list of available scripts; running ones are untouched.
            if (communityInstalled || needsRescan(available, registered)) ScriptExecutor.loadScripts()
            ScriptLibrary.addOwned(owned)
        }
        return installed
    }

    /** Owned scripts whose current build is here: held in memory, or a plain jar in the folder. */
    fun ownedPresent(owned: Set<String>, catalogue: List<StoreJar>, present: Set<String>): Set<String> =
        catalogue
            .filter {
                it.internalName in owned &&
                    (hasEncrypted(it) || jarName(it.internalName, it.version) in present)
            }
            .mapTo(HashSet()) { it.internalName }

    fun needsRescan(ownedPresent: Set<String>, registered: Set<String>): Boolean = !registered.containsAll(ownedPresent)

    private fun listed(dir: Path): Set<String> =
        Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList().toSet() }

    private fun install(jar: StoreJar, token: String, http: HttpClient, dir: Path, present: Set<String>): Boolean =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(Pxe1.withFormat(jar.url)))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                println("[store] could not download ${jar.internalName} ${jar.version} (${response.statusCode()}).")
                return false
            }
            val bytes = response.body()
            val encrypted = Pxe1.isBlob(bytes)
            val declared = response.headers().firstValue(Pxe1.FORMAT_HEADER).orElse(null)
            if (declared.equals(Pxe1.FORMAT, ignoreCase = true) && !encrypted) {
                println("[store] ${jar.internalName} ${jar.version} did not arrive as an encrypted build; not installed.")
                return false
            }

            val kept = if (encrypted) {
                // Memory only; every build of it on disk goes below.
                EncryptedScripts.hold(PxeRecord(jar.internalName, jar.version, jar.sha256), bytes)
                emptySet()
            } else {
                if (jar.sha256 != null && !jar.sha256.equals(hex(bytes), ignoreCase = true)) {
                    println("[store] ${jar.internalName} ${jar.version} did not match its published checksum; not installed.")
                    return false
                }
                if (!jar.free) servedPlain += plainKey(jar)
                val name = jarName(jar.internalName, jar.version)
                writeAtomically(dir, name, bytes)
                setOf(name)
            }

            // Two builds of one script put every class on the scan path twice.
            present.filter { it !in kept && isBuildOf(it, jar) }.forEach { stale ->
                runCatching { Files.deleteIfExists(dir.resolve(stale)) }
            }
            println("[store] ${if (encrypted) "loaded" else "installed"} ${jar.internalName} ${jar.version}; it is in your Scripts tab.")
            true
        }.getOrElse {
            println("[store] could not install ${jar.internalName} ${jar.version}: ${it.message}")
            false
        }

    private fun writeAtomically(dir: Path, name: String, bytes: ByteArray) {
        val temp = dir.resolve(".$name.engine.part")
        Files.write(temp, bytes)
        Files.move(temp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Community scripts come in one jar the launcher only installs for players who opt into it, so owning one on the
     * store would otherwise put nothing on disk. True when an owned script has no jar of its own and none is there.
     */
    internal fun needsCommunityJar(owned: Set<String>, catalogue: List<StoreJar>, present: Set<String>): Boolean {
        val ownJars = catalogue.mapTo(HashSet()) { it.internalName }
        return owned.any { it !in ownJars } && present.none { isCommunityJar(it) }
    }

    internal fun isCommunityJar(file: String) = file.startsWith("$COMMUNITY_PREFIX-") && file.endsWith(".jar")

    /** The latest community-scripts release, checked against its published sha256, written as the launcher names it. */
    private fun installCommunityJar(http: HttpClient, dir: Path): Boolean = runCatching {
        val latest = http.send(
            HttpRequest.newBuilder(URI.create("$COMMUNITY_RELEASES/latest")).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        val tag = latest.headers().firstValue("location").orElse(null)?.substringAfterLast("/tag/") ?: return false
        val name = "$COMMUNITY_PREFIX-${tag.removePrefix("v")}.jar"
        val base = "$COMMUNITY_RELEASES/download/$tag/$name"
        val bytes = fetch(base) ?: return false
        val expected = fetch("$base.sha256")?.decodeToString()?.trim()?.substringBefore(' ')
        if (expected == null || !expected.equals(hex(bytes), ignoreCase = true)) {
            println("[store] the community scripts download did not match its published checksum; not installed.")
            return false
        }
        writeAtomically(dir, name, bytes)
        println("[store] installed community scripts ${tag.removePrefix("v")} for the community scripts you own.")
        true
    }.getOrElse {
        println("[store] could not install the community scripts: ${it.message}")
        false
    }

    // Release downloads redirect to GitHub's file storage; the store's own client deliberately doesn't follow redirects.
    private val downloads: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    private fun fetch(url: String): ByteArray? {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build()
        val response = downloads.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return response.body().takeIf { response.statusCode() in 200..299 }
    }

    private const val COMMUNITY_PREFIX = "community-scripts"
    private const val COMMUNITY_RELEASES = "https://github.com/iEasyScript/community-scripts/releases"
}
