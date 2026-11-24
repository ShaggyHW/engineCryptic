package com.projectx.traversal.hops

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/**
 * The machine-local hop store under [HopLearning.storeDir]; only a learning client calls the writers.
 * - `knowledge.jsonl`: a header `{"meta":"hops","model":N}`, then one [Evidence] line per key. Every client folds its
 *   evidence in under `knowledge.jsonl.lock`: re-read, merge, write a pid temp file, atomic move. A file that does not
 *   parse is never overwritten.
 * - `observations/<yyyy-mm-dd>-<pid>.jsonl`: every hop as a [HopObservation], append-only, one file per client per
 *   day so no lock is needed; kept [KEEP_DAYS] days and [KEEP_BYTES] bytes, so a new [HopModel.VERSION] can
 *   re-derive the evidence ([rebuildIfModelChanged]).
 *
 * Data from a model below [HopModel.DISCARD_BELOW] is not re-derived: [discardIfOld] moves it to
 * `<store>-reset-v<model>-<UTC stamp>/`.
 */
internal object HopStore {
    const val KNOWLEDGE = "knowledge.jsonl"
    const val LOCK = "knowledge.jsonl.lock"
    const val OBSERVATIONS = "observations"
    const val SCHEDULE = "schedule.jsonl"
    const val KEEP_DAYS = 90L
    const val KEEP_BYTES = 200L * 1024 * 1024
    private const val LOCK_WAIT_MS = 2_000L
    private const val LOCK_RETRY_MS = 25L

    fun interface Change {
        /** Mutates the store's evidence; false when there is nothing to write. */
        fun apply(current: MutableMap<String, Evidence>): Boolean
    }

    fun today(): Long = LocalDate.now(ZoneOffset.UTC).toEpochDay()

    fun day(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

    fun knowledgeFile(): Path = HopLearning.storeDir().resolve(KNOWLEDGE)

    /** The store's evidence; empty when there is none or it does not parse. */
    fun load(): MutableMap<String, Evidence> {
        val out = TreeMap<String, Evidence>()
        return if (read(knowledgeFile(), out) == null) TreeMap() else out
    }

    /** Lines of [file] into [out]: the header's model, -1 without a header or file, null when unreadable. */
    fun read(file: Path, out: MutableMap<String, Evidence>): Int? {
        if (!Files.isRegularFile(file)) return -1
        return try {
            readLines(Files.readAllLines(file, StandardCharsets.UTF_8), out)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Throws on a line that is not a JSON object. */
    fun readLines(lines: List<String>, out: MutableMap<String, Evidence>): Int {
        var model = -1
        for (line in lines) {
            if (line.isBlank()) continue
            val o = HopJson.parse(line)
            if (o.has("meta")) {
                model = HopJson.int(o, "model") ?: -1
                continue
            }
            val (key, evidence) = Evidence.fromJson(o) ?: continue
            out[key]?.add(evidence) ?: out.put(key, evidence)
        }
        return model
    }

    /** Folds [deltas] into the store; what it holds afterwards, or null when it could not be written. */
    fun flush(deltas: Map<String, Evidence>): MutableMap<String, Evidence>? = update(HopModel.VERSION) { current ->
        for ((key, delta) in deltas) current[key]?.add(delta) ?: current.put(key, delta.copy())
        true
    }

    @Synchronized
    fun update(modelVersion: Int, change: Change): MutableMap<String, Evidence>? {
        val file = knowledgeFile()
        var lock: FileLock? = null
        try {
            Files.createDirectories(file.parent)
            lock = acquireLock(file.resolveSibling(LOCK))
            if (lock == null) println("[hops] $LOCK not acquired - writing unlocked")
            val current = TreeMap<String, Evidence>()
            if (read(file, current) == null) {
                println("[hops] $file does not parse - not overwriting it (fix or delete it)")
                return null
            }
            if (!change.apply(current)) return current
            write(file, current, modelVersion)
            return current
        } catch (e: IOException) {
            println("[hops] could not write $file: $e")
            return null
        } catch (e: RuntimeException) {
            println("[hops] could not write $file: $e")
            return null
        } finally {
            release(lock)
        }
    }

    private fun write(file: Path, evidence: Map<String, Evidence>, modelVersion: Int) {
        val lines = ArrayList<String>(evidence.size + 1)
        lines += """{"meta":"hops","model":$modelVersion}"""
        for ((key, e) in evidence) lines += e.toJson(key, null)
        val temp = file.resolveSibling("${file.fileName}.tmp.${ProcessHandle.current().pid()}")
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun observationsDir(): Path = HopLearning.storeDir().resolve(OBSERVATIONS)

    fun append(jsonLine: String) {
        try {
            val dir = observationsDir()
            Files.createDirectories(dir)
            val file = dir.resolve("${LocalDate.now(ZoneOffset.UTC)}-${ProcessHandle.current().pid()}.jsonl")
            Files.writeString(file, jsonLine + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            println("[hops] could not append an observation: $e")
        } catch (e: RuntimeException) {
            println("[hops] could not append an observation: $e")
        }
    }

    /** Every observation file, oldest first. */
    fun observationFiles(): List<Path> {
        val dir = observationsDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().endsWith(".jsonl") }.toList().sortedBy { it.fileName.toString() }
            }
        } catch (e: IOException) {
            emptyList()
        }
    }

    /** Drops observation files past [KEEP_DAYS] days, then the oldest until the rest fit in [KEEP_BYTES]. */
    fun rotate() {
        val files = observationFiles()
        val cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(KEEP_DAYS).toString()
        var total = files.sumOf(::size)
        for (file in files) {
            if (file.fileName.toString() >= cutoff && total <= KEEP_BYTES) break
            total -= size(file)
            runCatching { Files.deleteIfExists(file) }
        }
    }

    private fun size(file: Path): Long = try {
        Files.size(file)
    } catch (e: IOException) {
        0
    }

    /**
     * Moves the store and the observations to `<store>-reset-v<model>-<stamp>/` when they come from a model below
     * [HopModel.DISCARD_BELOW]; a store without a header counts as old, and without a store the observations' own
     * model decides. Under the store's lock, so two clients starting together move it once. True when it moved
     * something.
     */
    fun discardIfOld(): Boolean {
        val file = knowledgeFile()
        val observations = observationsDir()
        var lock: FileLock? = null
        try {
            if (!Files.isRegularFile(file) && !Files.isDirectory(observations)) return false
            lock = acquireLock(file.resolveSibling(LOCK))
            val model: Int
            if (Files.isRegularFile(file)) {
                model = read(file, TreeMap()) ?: return false
            } else {
                model = observationsModel()
                if (model < 0) return false
            }
            if (model >= HopModel.DISCARD_BELOW) return false
            val stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val store = file.parent
            val backup = store.resolveSibling("${store.fileName}-reset-v${maxOf(model, 0)}-$stamp")
            Files.createDirectories(backup)
            if (Files.isRegularFile(file)) Files.move(file, backup.resolve(KNOWLEDGE))
            if (Files.isDirectory(observations)) Files.move(observations, backup.resolve(OBSERVATIONS))
            val schedule = store.resolve(SCHEDULE)
            if (model < HopModel.SCHEDULE_DISCARD_BELOW && Files.isRegularFile(schedule)) Files.copy(schedule, backup.resolve(SCHEDULE))
            println("[hops] learned hops were model $model (below ${HopModel.DISCARD_BELOW}): moved to $backup, starting empty")
            return true
        } catch (e: IOException) {
            println("[hops] could not move the old learned hops aside: $e")
            return false
        } catch (e: RuntimeException) {
            println("[hops] could not move the old learned hops aside: $e")
            return false
        } finally {
            release(lock)
        }
    }

    /** The lowest model of the kept observations (first line of each file), -1 when there are none; unreadable counts as 0. */
    private fun observationsModel(): Int {
        var lowest = Int.MAX_VALUE
        for (file in observationFiles()) {
            lowest = try {
                val line = Files.newBufferedReader(file, StandardCharsets.UTF_8).use { it.readLine() }
                if (line == null || line.isBlank()) continue
                minOf(lowest, HopJson.int(HopJson.parse(line), "mv") ?: 0)
            } catch (e: IOException) {
                0
            } catch (e: RuntimeException) {
                0
            }
        }
        return if (lowest == Int.MAX_VALUE) -1 else lowest
    }

    /**
     * When the store was written by another [HopModel.VERSION], re-explains every kept observation with the current
     * model and replaces the store's evidence with the result, keeping probe days. True when it rebuilt.
     */
    fun rebuildIfModelChanged(): Boolean {
        val file = knowledgeFile()
        val model = read(file, TreeMap())
        if (model == null || !Files.isRegularFile(file) || model == HopModel.VERSION) return false
        val rebuilt = TreeMap<String, Evidence>()
        var explained = 0
        for (observationFile in observationFiles()) {
            try {
                for (line in Files.readAllLines(observationFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue
                    val o = HopObservation.fromJson(line)
                    if (o == null || o.model < HopModel.DISCARD_BELOW) continue
                    HopRecorder.applyResult(o, HopExplainer.explain(o), rebuilt)
                    explained++
                }
            } catch (e: IOException) {
                println("[hops] skipped ${observationFile.fileName} while rebuilding: $e")
            } catch (e: RuntimeException) {
                println("[hops] skipped ${observationFile.fileName} while rebuilding: $e")
            }
        }
        update(HopModel.VERSION) { current ->
            val keep = TreeMap(rebuilt)
            for ((key, e) in current) keep[key]?.let { it.pd = maxOf(it.pd, e.pd) }
            current.clear()
            current.putAll(keep)
            true
        }
        println("[hops] store was model $model, now ${HopModel.VERSION}: re-explained $explained observations into ${rebuilt.size} keys")
        return true
    }

    /** A record lock on [lockPath], waiting up to [LOCK_WAIT_MS]; null when it could not be had (the caller goes on unlocked). */
    fun acquireLock(lockPath: Path): FileLock? {
        var channel: FileChannel? = null
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val deadline = System.nanoTime() + LOCK_WAIT_MS * 1_000_000L
            while (true) {
                channel.tryLock()?.let { return it }
                if (System.nanoTime() - deadline >= 0) break
                Thread.sleep(LOCK_RETRY_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
        } catch (e: RuntimeException) {
        }
        runCatching { channel?.close() }
        return null
    }

    fun release(lock: FileLock?) {
        if (lock == null) return
        runCatching {
            lock.release()
            lock.channel().close()
        }
    }
}
