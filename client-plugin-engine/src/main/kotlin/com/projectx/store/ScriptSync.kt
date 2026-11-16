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
        val communityRemoved = removeCommunityJars(dir).isNotEmpty()
        superseded(catalogue, listed(dir)).forEach { runCatching { Files.deleteIfExists(dir.resolve(it)) } }

        // Also catches a build the launcher installed: present, owned, but not yet in the script list.
        val available = ownedPresent(owned, catalogue, listed(dir))
        GameThread.post {
            val registered = ScriptExecutor.scripts.values.mapTo(HashSet()) { it.scriptClass.simpleName }
            // A rescan only rebuilds the list of available scripts; running ones are untouched.
            if (communityRemoved || needsRescan(available, registered)) ScriptExecutor.loadScripts()
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

    internal fun isCommunityJar(file: String): Boolean =
        file.endsWith(".jar") && file.removePrefix("$COMMUNITY_PREFIX-").let { it != file && it.firstOrNull()?.isDigit() == true }

    /**
     * The community-scripts jar the launcher and older engines installed. Its scripts are on the store now, so it would
     * run a second copy of them. A jar still in use stays and is tried again at the next sync.
     */
    internal fun removeCommunityJars(dir: Path): List<String> {
        val removed = listed(dir)
            .filter(::isCommunityJar)
            .filter { runCatching { Files.deleteIfExists(dir.resolve(it)) }.getOrDefault(false) }
        if (removed.isNotEmpty()) println("[store] removed ${removed.joinToString()}: community scripts come from the store now.")
        return removed
    }

    private const val COMMUNITY_PREFIX = "community-scripts"
}
