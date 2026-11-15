package com.projectx.store

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.script.InMemoryJar
import com.projectx.script.InMemoryJarClassLoader
import com.projectx.script.Script
import com.projectx.script.ScriptExecutor
import com.projectx.ui.compose.GameThread
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.CodeSource
import java.security.MessageDigest
import java.security.cert.Certificate
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The PXE1 container a paid script arrives in: magic, a 12-byte nonce, then AES-256-GCM ciphertext with its tag. The
 * AAD names the game, script and version, so a blob cannot be passed off as another script's or another release's.
 */
internal object Pxe1 {
    const val FORMAT = "pxe1"
    const val FORMAT_HEADER = "X-ProjectX-Jar-Format"
    private const val GAME = "RS3"
    private val MAGIC = "PXE1".toByteArray(Charsets.US_ASCII)
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    fun isBlob(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size + NONCE_BYTES + TAG_BITS / 8 && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun aad(internalName: String, version: String, game: String = GAME): ByteArray =
        "PXE1|$game|$internalName|$version".toByteArray(Charsets.UTF_8)

    /** Throws [AEADBadTagException] when the key is not this blob's or the blob was altered. */
    fun decrypt(blob: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        require(isBlob(blob)) { "not a PXE1 blob" }
        require(key.size == KEY_BYTES) { "a PXE1 key is $KEY_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob, MAGIC.size, NONCE_BYTES))
        cipher.updateAAD(aad)
        val body = MAGIC.size + NONCE_BYTES
        return cipher.doFinal(blob, body, blob.size - body)
    }

    fun withFormat(url: String): String = url + (if ('?' in url) "&" else "?") + "format=$FORMAT"

    fun keyUrl(storeBase: String, internalName: String, version: String): String =
        "$storeBase/api/v1/hub/jar-keys/${segment(internalName)}/${segment(version)}?game=rs3"

    private fun segment(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    sealed interface KeyAnswer {
        class Key(val bytes: ByteArray) : KeyAnswer
        data class Refused(val status: Int) : KeyAnswer
        data class Failed(val reason: String) : KeyAnswer
    }

    fun fetchKey(http: HttpClient, storeBase: String, token: String, internalName: String, version: String): KeyAnswer =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(keyUrl(storeBase, internalName, version)))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (val status = response.statusCode()) {
                401, 403 -> KeyAnswer.Refused(status)
                !in 200..299 -> KeyAnswer.Failed("the store answered $status")
                else -> keyIn(response.body())
            }
        }.getOrElse { KeyAnswer.Failed(it.message ?: it.javaClass.simpleName) }

    private fun keyIn(body: String): KeyAnswer {
        val reply = JsonParser.parseString(body).asJsonObject
        val format = reply.get("format")?.takeUnless { it.isJsonNull }?.asString
        if (format != null && format != FORMAT) return KeyAnswer.Failed("the store sent a $format key")
        val key = reply.get("key")?.takeUnless { it.isJsonNull }?.asString?.let { Base64.getDecoder().decode(it) }
        if (key == null || key.size != KEY_BYTES) return KeyAnswer.Failed("the store sent no usable key")
        return KeyAnswer.Key(key)
    }
}

/**
 * Which script and release an encrypted build holds: the AAD and key URL need the real names. A `.pxe` saved by an
 * older engine or launcher has it beside it as `<name>.pxe.json`.
 */
internal data class PxeRecord(val internalName: String, val version: String, val sha256: String?) {

    fun encode(): String = JsonObject().apply {
        addProperty("game", "RS3")
        addProperty("internalName", internalName)
        addProperty("version", version)
        sha256?.let { addProperty("sha256", it) }
    }.toString()

    companion object {
        fun fileFor(pxe: String): String = "$pxe.json"

        fun decode(text: String): PxeRecord? = runCatching {
            val obj = JsonParser.parseString(text).asJsonObject
            PxeRecord(
                internalName = obj.get("internalName").asString,
                version = obj.get("version").asString,
                sha256 = obj.get("sha256")?.takeUnless { it.isJsonNull }?.asString,
            )
        }.getOrNull()
    }
}

/** A paid script's encrypted build, held in memory only: it is never written to disk. */
internal class HeldBuild(val record: PxeRecord, val blob: ByteArray)

/**
 * Holds the encrypted paid scripts in memory and loads them. Nothing of a paid script is kept on disk: [ScriptSync]
 * downloads each build straight into memory at every start, and a `.pxe` an older engine or launcher saved in the
 * scripts folder is moved into memory and deleted ([adopt]). The key is fetched afresh for each load and lives only in
 * memory, as does the decrypted jar. Fetching runs off the game thread: a build joins the script list on the rescan its
 * load posts, so a scan never waits on the network.
 */
internal object EncryptedScripts {
    const val EXTENSION = ".pxe"
    private val RETRY_NANOS = TimeUnit.SECONDS.toNanos(60)

    class Loaded(val loader: ClassLoader, val classNames: List<String>)

    sealed interface Outcome {
        class Ready(val loaded: Loaded) : Outcome
        data class Skipped(val message: String) : Outcome
        data class Discarded(val message: String) : Outcome
    }

    // By internal name: one build of a script at a time.
    private val held = ConcurrentHashMap<String, HeldBuild>()
    private val ready = ConcurrentHashMap<String, Loaded>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val rescanPending = AtomicBoolean(false)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    // Its thread ends when idle, so it cannot pin this engine's classloader across a hot reload.
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "projectx-encrypted-scripts").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    fun hold(record: PxeRecord, blob: ByteArray): HeldBuild = HeldBuild(record, blob).also { held[record.internalName] = it }

    internal fun heldBuild(internalName: String): HeldBuild? = held[internalName]

    internal fun heldRecord(internalName: String): PxeRecord? = held[internalName]?.record

    fun holds(internalName: String, version: String): Boolean = held[internalName]?.record?.version == version

    fun holdsAny(internalName: String): Boolean = held.containsKey(internalName)

    /** Forgets the builds of scripts the account no longer owns: not even an encrypted copy of them stays. */
    fun retainOwned(owned: Set<String>) {
        held.keys.retainAll(owned)
    }

    /** The plain jar names of the builds held, so a leftover plain jar of the same release is not loaded beside one. */
    fun heldJarNames(): Set<String> = held.values.mapTo(HashSet()) { ScriptSync.jarName(it.record.internalName, it.record.version) }

    /** Drops every held build, as a hot reload does. For tests. */
    internal fun forgetAll() {
        held.clear()
        ready.clear()
        failedAt.clear()
        reported.clear()
    }

    /**
     * Moves the `.pxe` builds in [dir] into memory and deletes them, with their records. They come from engines before
     * memory-only loading, or from a launcher that still saves them; either way they are this account's encrypted
     * builds, so nothing is downloaded again. One without a usable record is deleted and fetched by the next sync.
     */
    fun adopt(dir: File) {
        val pxes = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }.orEmpty()
        for (pxe in pxes) {
            val recordFile = File(dir, PxeRecord.fileFor(pxe.name))
            val record = recordFile.takeIf { it.isFile }?.let { runCatching { PxeRecord.decode(it.readText()) }.getOrNull() }
            val blob = runCatching { pxe.readBytes() }.getOrNull()
            if (record != null && blob != null && Pxe1.isBlob(blob)) {
                held.putIfAbsent(record.internalName, HeldBuild(record, blob))
            }
            pxe.delete()
            recordFile.delete()
        }
        // A record whose build is gone says nothing.
        dir.listFiles { f -> f.isFile && f.name.endsWith(PxeRecord.fileFor(EXTENSION)) }.orEmpty()
            .filter { !File(dir, it.name.removeSuffix(PxeRecord.fileFor(""))).exists() }
            .forEach { it.delete() }
    }

    /** The held scripts already decrypted; any others are queued and arrive with a later rescan. */
    fun loaded(dir: File): List<Loaded> {
        val builds = held.values.associateBy(::identity)
        ready.keys.retainAll(builds.keys)
        val now = System.nanoTime()
        builds.forEach { (key, build) ->
            if (ready.containsKey(key)) return@forEach
            val lastFailure = failedAt[key]
            if (lastFailure != null && now - lastFailure < RETRY_NANOS) return@forEach
            if (loading.add(key)) worker.execute { loadInBackground(key, build, dir) }
        }
        return builds.keys.mapNotNull { ready[it] }
    }

    // A new download of the same release is a new array, so it is decrypted and checked again.
    private fun identity(build: HeldBuild) =
        "${build.record.internalName}|${build.record.version}|${build.record.sha256}|${System.identityHashCode(build.blob)}"

    private fun loadInBackground(key: String, build: HeldBuild, dir: File) {
        val label = "${build.record.internalName} ${build.record.version}"
        try {
            val token = Entitlements.token()
            val outcome = if (token == null) {
                Outcome.Skipped("[store] $label was not loaded: sign in on the launcher's Store tab to run your store scripts.")
            } else {
                load(build, dir, token, http, Entitlements.storeBase(), Script::class.java.classLoader)
            }
            when (outcome) {
                is Outcome.Ready -> {
                    failedAt.remove(key)
                    ready[key] = outcome.loaded
                    rescanPending.set(true)
                }
                is Outcome.Skipped -> {
                    failedAt[key] = System.nanoTime()
                    if (reported.add("$key|${outcome.message}")) println(outcome.message)
                }
                is Outcome.Discarded -> println(outcome.message)
            }
        } catch (e: Throwable) {
            failedAt[key] = System.nanoTime()
            println("[store] $label was not loaded: ${e.message}")
        } finally {
            loading.remove(key)
            // One rescan once the queue drains, not one per script: each scan takes seconds.
            if (worker.queue.isEmpty() && rescanPending.getAndSet(false)) GameThread.post { ScriptExecutor.loadScripts() }
        }
    }

    /**
     * Decrypts a held build into a class loader. [dir] only names its code source, `<dir>/<name>-<version>.pxe`, which
     * does not exist: the script library reads a script's origin from that file name.
     */
    fun load(build: HeldBuild, dir: File, token: String, http: HttpClient, storeBase: String, parent: ClassLoader): Outcome {
        val record = build.record
        val label = "${record.internalName} ${record.version}"
        if (!Pxe1.isBlob(build.blob)) return discard(build, "[store] $label is not an encrypted build; it will download again.")

        val key = when (val answer = Pxe1.fetchKey(http, storeBase, token, record.internalName, record.version)) {
            is Pxe1.KeyAnswer.Key -> answer.bytes
            is Pxe1.KeyAnswer.Refused -> return Outcome.Skipped(
                "[store] $label was not loaded: the store refused its key (${answer.status}). " +
                    "Check your access on the store, or sign in again on the launcher's Store tab."
            )
            is Pxe1.KeyAnswer.Failed -> return Outcome.Skipped("[store] $label was not loaded this time: could not fetch its key (${answer.reason}).")
        }
        val plain = try {
            Pxe1.decrypt(build.blob, key, Pxe1.aad(record.internalName, record.version))
        } catch (e: AEADBadTagException) {
            return discard(build, "[store] $label could not be decrypted for this account; it will download again.")
        } finally {
            key.fill(0)
        }
        try {
            if (record.sha256 != null && !record.sha256.equals(sha256(plain), ignoreCase = true)) {
                return discard(build, "[store] $label did not match its published checksum; it will download again.")
            }
            val jar = InMemoryJar.read(plain)
            val location = File(dir, ScriptSync.pxeName(record.internalName, record.version)).toURI().toURL()
            val codeSource = CodeSource(location, null as Array<Certificate>?)
            return Outcome.Ready(Loaded(InMemoryJarClassLoader(jar, parent, codeSource), jar.classNames))
        } finally {
            plain.fill(0)
        }
    }

    // Only this build: a newer download that replaced it meanwhile stays.
    private fun discard(build: HeldBuild, message: String): Outcome {
        held.remove(build.record.internalName, build)
        return Outcome.Discarded(message)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
