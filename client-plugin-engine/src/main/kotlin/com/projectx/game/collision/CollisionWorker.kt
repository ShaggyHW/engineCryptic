package com.projectx.game.collision

import com.projectx.game.collision.CollisionGrid.Companion.SQ
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Composes the live collision map of the 3x3 map squares around the player from what the client holds: every placed
 * loc, the locs it spawned and the ones it hid or deleted, its terrain and its loc types. The walked block is 4x4
 * squares, the window plus the W, S and SW squares whose big locs reach into it.
 *
 * Single-threaded: [pass], [noteTile] and [servePrewarm] run on one thread (the worker). What it publishes is
 * immutable and handed to [publish]; [lastJump] and [prewarm] are safe from any thread.
 *
 * A square the client has not loaded yet (a teleport landing still streaming in) is served from [statics] until it
 * does, its cells marked [CollisionFlags.STATIC].
 */
internal class CollisionWorker(
    private val live: LiveWorld,
    private val statics: StaticWorld,
    private val publish: (CollisionSnapshot) -> Unit,
    private val log: (String) -> Unit = { println("[CollisionMap] $it") },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val micros: () -> Long = { System.nanoTime() / 1_000 },
) {
    var locTypeReads = 0
        private set
    var definitionReads = 0
        private set
    var placementWalks = 0
        private set
    var dynamicWalks = 0
        private set
    var memoryTerrainReads = 0
        private set
    var cacheTerrainReads = 0
        private set
    var staticLoads = 0
        private set

    // ---- loc type properties ----

    private class PointerMemo(var id: Int, var epoch: Int, var seenMs: Long, var props: LocProps?)

    private class DefinitionMemo(var props: LocProps?, var retryMs: Long)

    private class RetryAt(var ms: Long = Long.MAX_VALUE) {
        fun lower(at: Long) {
            if (at < ms) ms = at
        }
    }

    private val pointerMemo = HashMap<Long, PointerMemo>()
    private val definitionMemo = HashMap<Int, DefinitionMemo>()
    private var epoch = 0
    private var rereadOlderEpochs = false
    private var otherIdReads = 0

    private fun pointerProps(pointer: Long, id: Int, now: Long): LocProps? {
        if (pointer == 0L) return null
        var memo = pointerMemo[pointer]
        if (memo != null && memo.id == id && (!rereadOlderEpochs || memo.epoch == epoch) && now - memo.seenMs < POINTER_STALE_MS &&
            (memo.props != null || now - memo.seenMs < RETRY_MS)
        ) {
            if (memo.props != null) memo.seenMs = now
            return memo.props
        }
        if (memo == null) {
            if (pointerMemo.size >= POINTER_MEMO_MAX) pointerMemo.clear()
            memo = PointerMemo(id, epoch, now, null)
            pointerMemo[pointer] = memo
        }
        locTypeReads++
        val read = live.locType(pointer, id)
        if (read == LocTypeRead.OtherId) otherIdReads++
        memo.props = (read as? LocTypeRead.Read)?.props
        memo.id = id
        memo.epoch = epoch
        memo.seenMs = now
        return memo.props
    }

    private fun definitionProps(id: Int, now: Long, retry: RetryAt?): LocProps? {
        if (id < 0) return null
        var memo = definitionMemo[id]
        if (memo != null) {
            memo.props?.let { return it }
            if (now < memo.retryMs) {
                retry?.lower(memo.retryMs)
                return null
            }
        } else {
            if (definitionMemo.size >= DEFINITION_MEMO_MAX) definitionMemo.clear()
            memo = DefinitionMemo(null, 0L)
            definitionMemo[id] = memo
        }
        definitionReads++
        val props = statics.locProps(id)
        if (props == null) {
            memo.retryMs = now + RETRY_MS
            retry?.lower(memo.retryMs)
            return null
        }
        memo.props = props
        return props
    }

    /** The loc's own type first, the cache definition by id second. */
    private fun props(loc: LiveLoc, now: Long, retry: RetryAt): LocProps? {
        pointerProps(loc.locType, loc.id, now)?.let { return it }
        definitionProps(loc.id, now, retry)?.let { return it }
        if (loc.id < 0) retry.lower(now + RETRY_MS)
        return null
    }

    // ---- per-square slots ----

    private class Slot {
        var squareX = 0
        var squareY = 0
        var valid = false

        var stamp = 0L
        var built = false
        var placed: List<CollisionLoc> = emptyList()
        var placedKeys: List<LiveLoc> = emptyList()
        var placedTruncated = false
        var placedUnresolved = 0
        var placedRetry = Long.MAX_VALUE
        var builtMs = 0L
        var rechecks = 0

        var dynamicPrint = 0L
        var dynamicValid = false
        var dynamicTruncated = false
        var dynamicUnresolved = 0
        var dynamicRetry = Long.MAX_VALUE

        var locs: List<CollisionLoc> = emptyList()
        var locIds: IntArray = IntArray(0)
        var changed = false

        var fromStatic = false
        var staticGeneration = 0

        fun assign(x: Int, y: Int) {
            squareX = x
            squareY = y
            valid = true
            built = false
            stamp = 0L
            placed = emptyList()
            placedKeys = emptyList()
            placedTruncated = false
            placedUnresolved = 0
            builtMs = 0L
            rechecks = 0
            dynamicValid = false
            dynamicPrint = 0L
            dynamicTruncated = false
            dynamicUnresolved = 0
            locs = emptyList()
            locIds = IntArray(0)
            fromStatic = false
            staticGeneration = 0
        }
    }

    private val slots = Array(SLOTS) { Slot() }
    private val window = arrayOfNulls<Slot>(SLOTS)

    private fun buildPlaced(slot: Slot, walk: List<LiveLoc>?, now: Long) {
        slot.placedUnresolved = 0
        val retry = RetryAt()
        val placed = ArrayList<CollisionLoc>()
        val keys = ArrayList<LiveLoc>()
        for (loc in walk.orEmpty()) {
            if (loc.plane >= CollisionGrid.PLANES) continue
            val props = props(loc, now, retry)
            if (props == null) {
                slot.placedUnresolved++
                continue
            }
            placed += loc.toCollision(props)
            keys += loc
        }
        slot.placed = placed
        slot.placedKeys = keys
        slot.placedRetry = retry.ms
    }

    /** The placed loc a removal marker takes out: an exact match first, else one that differs only in id. */
    private fun matchRemoval(slot: Slot, removed: BooleanArray, marker: LiveLoc): Int {
        var loose = -1
        for ((i, key) in slot.placedKeys.withIndex()) {
            if (removed[i] || key.x != marker.x || key.y != marker.y || key.plane != marker.plane ||
                key.shape != marker.shape || key.rotation != marker.rotation
            ) continue
            if (key.id == marker.id) return i
            if (loose < 0) loose = i
        }
        return loose
    }

    private fun compose(slot: Slot, dynamic: List<LiveLoc>, now: Long) {
        val removed = BooleanArray(slot.placed.size)
        for (marker in dynamic) {
            if (marker.source != LiveLocSource.REMOVED) continue
            val match = matchRemoval(slot, removed, marker)
            if (match >= 0) removed[match] = true
        }
        val locs = ArrayList<CollisionLoc>(slot.placed.size)
        val ids = ArrayList<Int>(slot.placed.size)
        for (i in slot.placed.indices) {
            if (removed[i]) continue
            locs += slot.placed[i]
            ids += slot.placedKeys[i].id
        }
        slot.dynamicUnresolved = 0
        val retry = RetryAt()
        for (spawn in dynamic) {
            if (spawn.source != LiveLocSource.SPAWN || spawn.plane >= CollisionGrid.PLANES) continue
            val props = props(spawn, now, retry)
            if (props == null) {
                slot.dynamicUnresolved++
                continue
            }
            locs += spawn.toCollision(props)
            ids += spawn.id
        }
        slot.dynamicRetry = retry.ms
        slot.locs = locs
        slot.locIds = ids.toIntArray()
    }

    private fun assignSlots(blockX: Int, blockY: Int) {
        val used = BooleanArray(SLOTS)
        for (k in 0 until SLOTS) {
            val x = blockX + k % BLOCK
            val y = blockY + k / BLOCK
            window[k] = null
            for (j in 0 until SLOTS) {
                if (!used[j] && slots[j].valid && slots[j].squareX == x && slots[j].squareY == y) {
                    used[j] = true
                    window[k] = slots[j]
                    break
                }
            }
        }
        var j = 0
        for (k in 0 until SLOTS) {
            if (window[k] != null) continue
            while (used[j]) j++
            used[j] = true
            slots[j].assign(blockX + k % BLOCK, blockY + k / BLOCK)
            window[k] = slots[j]
        }
    }

    // ---- terrain ----

    private enum class TerrainSource { NONE, MEMORY, CACHE }

    private class TerrainEntry {
        var squareX = 0
        var squareY = 0
        var used = false
        var source = TerrainSource.NONE
        var cacheResult: CacheTerrainResult? = null
        var keyStamp = 0L
        var memoryRetryMs = 0L
        var cacheRetryMs = 0L
        var lru = 0L
        var terrain: SquareTerrain? = null
    }

    private class TerrainRead(val terrain: SquareTerrain?, val changed: Boolean, val degraded: Boolean)

    private val terrainCache = Array(TERRAIN_LRU) { TerrainEntry() }
    private var terrainClock = 0L
    private var noCacheLogged = false

    private fun terrain(squareX: Int, squareY: Int, stamp: Long, instance: Boolean, now: Long): TerrainRead {
        var entry: TerrainEntry? = null
        var victim: TerrainEntry? = null
        for (candidate in terrainCache) {
            if (candidate.used && candidate.squareX == squareX && candidate.squareY == squareY) {
                entry = candidate
                break
            }
            if (victim == null || victim.used && (!candidate.used || candidate.lru < victim.lru)) victim = candidate
        }
        var fresh = false
        if (entry == null) {
            entry = victim!!
            entry.squareX = squareX
            entry.squareY = squareY
            entry.used = true
            entry.source = TerrainSource.NONE
            entry.cacheResult = null
            entry.keyStamp = 0L
            entry.memoryRetryMs = 0L
            entry.cacheRetryMs = 0L
            entry.terrain = null
            fresh = true
        }
        entry.lru = ++terrainClock

        val oldSource = entry.source
        val memoryDue = fresh || if (entry.source == TerrainSource.MEMORY) stamp != entry.keyStamp else now >= entry.memoryRetryMs
        var got = TerrainSource.NONE
        var read: SquareTerrain? = null
        if (memoryDue) {
            memoryTerrainReads++
            read = live.terrain(squareX, squareY)
            if (read != null) {
                got = TerrainSource.MEMORY
                entry.keyStamp = stamp
            } else {
                entry.memoryRetryMs = now + RETRY_MS
                if (entry.source == TerrainSource.MEMORY) entry.source = TerrainSource.NONE
            }
        }
        if (instance && entry.source == TerrainSource.CACHE) entry.source = TerrainSource.NONE
        if (got == TerrainSource.NONE && entry.source == TerrainSource.NONE && !instance &&
            (entry.cacheResult == null || now >= entry.cacheRetryMs)
        ) {
            read = cacheTerrain(entry, now)
            if (read != null) got = TerrainSource.CACHE
        }
        var changed = false
        if (got != TerrainSource.NONE) {
            if (oldSource == TerrainSource.NONE || entry.terrain != read) changed = true
            entry.terrain = read
            entry.source = got
        } else if (oldSource != TerrainSource.NONE && entry.source == TerrainSource.NONE) {
            changed = true
        }
        if (entry.source != TerrainSource.NONE) return TerrainRead(entry.terrain, changed, false)
        val degraded = !instance && entry.cacheResult != null && entry.cacheResult != CacheTerrainResult.MISSING
        return TerrainRead(null, changed, degraded)
    }

    private fun cacheTerrain(entry: TerrainEntry, now: Long): SquareTerrain? {
        val previous = entry.cacheResult
        cacheTerrainReads++
        val (result, terrain) = statics.terrain(entry.squareX, entry.squareY)
        entry.cacheResult = result
        entry.cacheRetryMs = now + if (result == CacheTerrainResult.MISSING) MISSING_RETRY_MS else RETRY_MS
        if (result == CacheTerrainResult.CORRUPT && previous != CacheTerrainResult.CORRUPT) {
            log("terrain of square (${entry.squareX},${entry.squareY}): memory unreadable and cache corrupt; floor unknown")
        }
        if (result == CacheTerrainResult.NO_CACHE && !noCacheLogged) {
            noCacheLogged = true
            log("memory terrain unreadable and no cache; floor unknown (retrying)")
        }
        return if (result == CacheTerrainResult.OK) terrain else null
    }

    /** A square the cache says does not exist (ocean, padding) never loads, so it never counts as unloaded. */
    private fun terrainVoid(squareX: Int, squareY: Int): Boolean =
        terrainCache.any { it.used && it.squareX == squareX && it.squareY == squareY && it.cacheResult == CacheTerrainResult.MISSING }

    // ---- static squares ----

    private class StaticEntry {
        var squareX = 0
        var squareY = 0
        var used = false
        var square: StaticSquare? = null
        var generation = 0
        var retryMs = 0L
        var lru = 0L
    }

    private val staticCache = Array(STATIC_LRU) { StaticEntry() }
    private var staticClock = 0L
    private var staticGeneration = 0
    private var staticPending = false
    private var staticSpentUs = 0L
    private var staticBudgetUs = STATIC_BUDGET_US

    private fun findStatic(squareX: Int, squareY: Int): StaticEntry? =
        staticCache.firstOrNull { it.used && it.squareX == squareX && it.squareY == squareY }

    private fun staticSquare(squareX: Int, squareY: Int, now: Long): StaticEntry? {
        if (squareX < 0 || squareY < 0 || squareX * SQ >= INSTANCE_MIN_X) return null
        var entry = findStatic(squareX, squareY)
        if (entry != null) {
            entry.lru = ++staticClock
            if (entry.square != null) return entry
            if (now < entry.retryMs) return null
        } else {
            entry = staticCache.reduce { victim, candidate -> if (victim.used && (!candidate.used || candidate.lru < victim.lru)) candidate else victim }
            entry.squareX = squareX
            entry.squareY = squareY
            entry.used = true
            entry.square = null
            entry.retryMs = 0L
            entry.lru = ++staticClock
        }
        if (staticSpentUs >= staticBudgetUs) {
            staticPending = true
            entry.retryMs = 0L
            return null
        }
        val started = micros()
        staticLoads++
        val square = statics.square(squareX, squareY)
        val spent = micros() - started
        staticSpentUs += spent
        if (square == null) {
            entry.retryMs = now + STATIC_MISS_MS
            return null
        }
        log("static square ($squareX,$squareY): decoded from the cache, ${square.locs.size} locs in $spent us")
        entry.square = square
        entry.generation = ++staticGeneration
        return entry
    }

    private fun useStatic(slot: Slot, entry: StaticEntry) {
        val square = entry.square!!
        slot.locs = square.locs
        slot.locIds = square.ids
        slot.placed = emptyList()
        slot.placedKeys = emptyList()
        slot.built = false
        slot.stamp = 0L
        slot.placedTruncated = false
        slot.placedUnresolved = 0
        slot.dynamicValid = false
        slot.dynamicTruncated = false
        slot.dynamicUnresolved = 0
        slot.fromStatic = true
        slot.staticGeneration = entry.generation
    }

    private val prewarmRequest = AtomicLong(0L)

    /** Load the static squares around ([x], [y]) on the next slice: a teleport about to land there. */
    fun prewarm(x: Int, y: Int) {
        if (x < 0 || y < 0 || x >= INSTANCE_MIN_X) return
        prewarmRequest.set(PREWARM_SET or (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL))
    }

    fun servePrewarm(now: Long) {
        val request = prewarmRequest.getAndSet(0L)
        if (request and PREWARM_SET == 0L) return
        val x = ((request ushr 32) and 0x7FFFFFFF).toInt()
        val y = request.toInt()
        val centreX = x shr 6
        val centreY = y shr 6
        staticSpentUs = 0L
        staticBudgetUs = PREWARM_BUDGET_US
        val started = micros()
        var ready = 0
        for (squareY in centreY - 2..centreY + 1) for (squareX in centreX - 2..centreX + 1) {
            if (staticSquare(squareX, squareY, now) != null) ready++
        }
        staticBudgetUs = STATIC_BUDGET_US
        log("prewarm ($x,$y): $ready/16 static squares ready in ${micros() - started} us")
    }

    // ---- jumps ----

    @Volatile
    var lastJump: CollisionJump? = null
        private set

    private var previousTile: LocalTile? = null

    /** Stamps a jump of the local tile: more than [JUMP_TILES] between two samples, or a plane change with any move. */
    fun noteTile(tile: LocalTile?) {
        if (tile == null || tile.x == 0 && tile.y == 0) return
        val previous = previousTile
        if (previous != null) {
            val moved = maxOf(abs(tile.x - previous.x), abs(tile.y - previous.y))
            if (moved > JUMP_TILES || tile.plane != previous.plane && moved > 0) {
                lastJump = CollisionJump(wallClock(), tile.x, tile.y, tile.plane)
            }
        }
        previousTile = tile
    }

    // ---- the pass ----

    private val work = CollisionGrid()
    private var centreX = Int.MIN_VALUE
    private var centreY = Int.MIN_VALUE
    private var ready = false
    private var status = CollisionStatus.NOT_READY
    private var staticCount = 0
    private var version = 0
    private var published: CollisionSnapshot? = null

    val hasStaticPending: Boolean get() = staticPending

    fun movedSquare(tile: LocalTile?): Boolean =
        tile != null && (tile.x != 0 || tile.y != 0) && (tile.x shr 6 != centreX || tile.y shr 6 != centreY)

    private fun notReady(): Boolean {
        if (!ready) return false
        ready = false
        centreX = Int.MIN_VALUE
        centreY = Int.MIN_VALUE
        val last = published ?: return false
        version++
        val snapshot = last.withHeader(last.header.copy(version = version, status = CollisionStatus.NOT_READY, builtAtMs = wallClock()))
        published = snapshot
        publish(snapshot)
        status = CollisionStatus.NOT_READY
        log("no local player or world; map frozen (NOT_READY)")
        return true
    }

    /** One pass on the local [tile]; true when it published. */
    fun pass(now: Long, tile: LocalTile?): Boolean {
        if (tile == null || tile.x == 0 && tile.y == 0) return notReady()
        val cx = tile.x shr 6
        val cy = tile.y shr 6
        val instance = tile.x >= INSTANCE_MIN_X
        staticSpentUs = 0L
        staticPending = false
        val centreStamp = live.placementStamp(cx, cy)
        if (centreStamp == 0L && (instance || staticSquare(cx, cy, now) == null)) return notReady()

        val recentre = cx != centreX || cy != centreY
        if (recentre) assignSlots(cx - 2, cy - 2)
        var changed = recentre
        if (!ready) {
            ready = true
            changed = true
        }

        val walkStarted = micros()
        var rebuilt = 0
        var rebuiltLocs = 0
        val rebuildLog = StringBuilder()
        val otherIdBefore = otherIdReads
        for (k in 0 until SLOTS) {
            val slot = window[k]!!
            slot.changed = false
            val inWorld = slot.squareX >= 0 && slot.squareY >= 0
            val stamp = when {
                !inWorld -> 0L
                slot.squareX == cx && slot.squareY == cy -> centreStamp
                else -> live.placementStamp(slot.squareX, slot.squareY)
            }

            if (stamp == 0L && inWorld && !instance) {
                val entry = staticSquare(slot.squareX, slot.squareY, now)
                if (entry != null) {
                    if (!slot.fromStatic || slot.staticGeneration != entry.generation) {
                        useStatic(slot, entry)
                        slot.changed = true
                        changed = true
                    }
                    continue
                }
            }
            if (slot.fromStatic) {
                slot.fromStatic = false
                slot.staticGeneration = 0
                slot.built = false
                slot.dynamicValid = false
                slot.locs = emptyList()
                slot.locIds = IntArray(0)
                slot.changed = true
                changed = true
            }

            var placedDirty = false
            val same = slot.built && stamp == slot.stamp
            val retry = same && slot.placedUnresolved > 0 && now >= slot.placedRetry
            val recheck = same && slot.rechecks < RECHECKS_MS.size && now >= slot.builtMs + RECHECKS_MS[slot.rechecks]
            if (!same || retry || recheck) {
                val before = if (same) slot.placed else null
                if (recheck) {
                    epoch++
                    rereadOlderEpochs = true
                    slot.rechecks++
                }
                var walk: List<LiveLoc>? = null
                var truncated = false
                if (stamp != 0L) {
                    placementWalks++
                    val read = live.placements(slot.squareX, slot.squareY)
                    walk = read.locs
                    truncated = read.locs != null && read.truncated
                }
                buildPlaced(slot, walk, now)
                rereadOlderEpochs = false
                slot.built = true
                if (!same) {
                    slot.stamp = stamp
                    slot.builtMs = now
                    slot.rechecks = 0
                }
                slot.placedTruncated = truncated
                placedDirty = !same || slot.placed != before
                if (recheck && placedDirty) {
                    log("square (${slot.squareX},${slot.squareY}): loc types changed on settle recheck ${slot.rechecks}/${RECHECKS_MS.size}, restamped (${slot.placed.size} locs)")
                }
                if (!same) {
                    rebuilt++
                    rebuiltLocs += slot.placed.size
                    rebuildLog.append(" (${slot.squareX},${slot.squareY})=${slot.placed.size}${if (walk == null) "?" else ""}")
                }
            }

            var dynamic: List<LiveLoc> = emptyList()
            var dynamicTruncated = false
            var dynamicLoaded = false
            if (inWorld) {
                dynamicWalks++
                val read = live.dynamic(slot.squareX, slot.squareY)
                if (read.locs != null) {
                    dynamic = read.locs
                    dynamicTruncated = read.truncated
                    dynamicLoaded = true
                }
            }
            val print = fingerprint(dynamic, dynamicLoaded, dynamicTruncated)
            val printChanged = !slot.dynamicValid || print != slot.dynamicPrint
            val dynamicRetry = !printChanged && slot.dynamicUnresolved > 0 && now >= slot.dynamicRetry
            if (!placedDirty && !printChanged && !dynamicRetry) continue
            val locsBefore = slot.locs.size
            compose(slot, dynamic, now)
            slot.dynamicValid = true
            slot.dynamicPrint = print
            slot.dynamicTruncated = dynamicTruncated
            if (!placedDirty && !printChanged && slot.locs.size == locsBefore) continue
            slot.changed = true
            changed = true
        }
        val walkEnded = micros()
        if (rebuilt > 0) log("static rebuild: $rebuilt square(s), $rebuiltLocs locs:$rebuildLog")
        if (otherIdReads > otherIdBefore) {
            log("${otherIdReads - otherIdBefore} loc type pointer(s) held another loc id (freed and reused); used the cache definition")
        }

        val terrains = arrayOfNulls<SquareTerrain>(9)
        var missing = 0
        var degraded = 0
        var unloaded = 0
        var staticSquares = 0
        var staticMask = 0
        for (k in 0 until 9) {
            val squareX = cx - 1 + k % 3
            val squareY = cy - 1 + k / 3
            val slot = window[(squareY - (cy - 2)) * BLOCK + (squareX - (cx - 2))]!!
            val ownTerrain = if (slot.fromStatic) findStatic(squareX, squareY)?.square?.terrain else null
            if (ownTerrain != null) {
                terrains[k] = ownTerrain
            } else if (squareX >= 0 && squareY >= 0) {
                val read = terrain(squareX, squareY, slot.stamp, instance, now)
                terrains[k] = read.terrain
                if (read.degraded) degraded++
                if (read.changed) {
                    slot.changed = true
                    changed = true
                }
            }
            if (terrains[k] == null) missing++
            if (squareX >= 0 && squareY >= 0 && slot.stamp == 0L && !instance && !terrainVoid(squareX, squareY)) unloaded++
            if (slot.fromStatic) {
                staticSquares++
                staticMask = staticMask or (1 shl k)
            }
        }

        if (!changed) return false

        val originX = (cx - 1) * SQ
        val originY = (cy - 1) * SQ
        work.reset(originX, originY)
        for (k in 0 until 9) work.applyTerrain(cx - 1 + k % 3, cy - 1 + k / 3, terrains[k])
        var locs = 0
        var truncated = 0
        var unresolved = 0
        for (k in 0 until SLOTS) {
            val slot = window[k]!!
            work.stampLocs(slot.locs)
            locs += slot.locs.size
            if (slot.placedTruncated || slot.dynamicTruncated) truncated++
            unresolved += slot.placedUnresolved + slot.dynamicUnresolved
        }
        for (k in 0 until SLOTS) {
            val slot = window[k]!!
            if (slot.changed) work.markBits(slot.squareX, slot.squareY, CollisionFlags.CHANGED)
            if (slot.fromStatic) work.markBits(slot.squareX, slot.squareY, CollisionFlags.STATIC)
        }
        work.deriveMasks()
        val builtEnded = micros()

        val newStatus = when {
            instance -> CollisionStatus.INSTANCE
            degraded > 0 || truncated > 0 -> CollisionStatus.DEGRADED
            else -> CollisionStatus.OK
        }
        version++
        val header = CollisionHeader(
            originX = originX,
            originY = originY,
            version = version,
            status = newStatus,
            buildMicros = (builtEnded - walkEnded).toInt(),
            walkMicros = (walkEnded - walkStarted).toInt(),
            locCount = locs,
            squaresMissingTerrain = missing,
            squaresTruncated = truncated,
            unresolvedDefs = unresolved,
            builtAtMs = wallClock(),
            squaresUnloaded = unloaded,
            squaresStatic = staticSquares,
            staticMask = staticMask,
        )
        val snapshot = CollisionSnapshot(
            header,
            Array(CollisionGrid.PLANES) { work.masks[it].copyOf() },
            Array(CollisionGrid.PLANES) { work.flags[it].copyOf() },
            window.map { SquareLocs(it!!.locs, it.locIds) },
        )
        published = snapshot
        publish(snapshot)

        if (recentre || newStatus != status || staticSquares != staticCount) {
            log(
                "${if (recentre) "centre" else "status at"} square ($cx,$cy): $locs locs, walk ${header.walkMicros} us, " +
                    "build ${header.buildMicros} us, $newStatus (terrain unknown $missing, truncated $truncated, " +
                    "unresolved $unresolved, unloaded $unloaded, static $staticSquares)"
            )
        }
        staticCount = staticSquares
        centreX = cx
        centreY = cy
        status = newStatus
        return true
    }

    private fun fingerprint(locs: List<LiveLoc>, loaded: Boolean, truncated: Boolean): Long {
        var sum = 0L
        for (loc in locs) sum += mix64(loc.hashCode().toLong() * PRIME xor loc.locType)
        return mix64(sum xor (locs.size.toLong() shl 32) xor (if (loaded) 2L else 0L) xor (if (truncated) 1L else 0L))
    }

    companion object {
        const val SLICE_MS = 100L
        const val PASS_MS = 600L
        const val JUMP_TILES = 12
        const val INSTANCE_MIN_X = 6400

        private const val BLOCK = 4
        private const val SLOTS = BLOCK * BLOCK
        private const val RETRY_MS = 5_000L
        private const val MISSING_RETRY_MS = 60_000L
        private const val POINTER_STALE_MS = 30_000L
        private val RECHECKS_MS = longArrayOf(2_000L, 6_000L, 15_000L)
        private const val POINTER_MEMO_MAX = 12_288
        private const val DEFINITION_MEMO_MAX = 24_576
        private const val TERRAIN_LRU = 16
        private const val STATIC_LRU = 40
        private const val STATIC_MISS_MS = 30_000L
        private const val STATIC_BUDGET_US = 150_000L
        private const val PREWARM_BUDGET_US = 400_000L
        private const val PREWARM_SET = 1L shl 63
        private const val PRIME = 0x100000001b3L

        private fun mix64(value: Long): Long {
            var x = value
            x = x xor (x ushr 30)
            x *= -0x40a7b892e31b1a47L
            x = x xor (x ushr 27)
            x *= -0x6b2fb644ecceee15L
            return x xor (x ushr 31)
        }

        private fun LiveLoc.toCollision(props: LocProps) =
            CollisionLoc(x, y, plane, shape, rotation, props.sizeX, props.sizeY, props.clipType, props.blocksProjectile)

        /** The locs of [snapshot] that stamp walk bits on the cells of the step from ([x], [y]) by ([dx], [dy]). */
        fun blockers(snapshot: CollisionSnapshot, x: Int, y: Int, plane: Int, dx: Int, dy: Int, cap: Int = BLOCKERS_CAP): List<CollisionBlocker> {
            if (plane !in 0 until CollisionGrid.PLANES) return emptyList()
            val grid = CollisionGrid(BLOCKERS_DIM)
            val toX = x + dx
            val toY = y + dy
            val diagonal = dx != 0 && dy != 0
            val out = ArrayList<CollisionBlocker>()
            for (square in snapshot.squares) {
                for ((i, loc) in square.locs.withIndex()) {
                    if (out.size >= cap) return out
                    if (loc.plane != plane || abs(loc.x - x) > BLOCKERS_REACH || abs(loc.y - y) > BLOCKERS_REACH) continue
                    grid.reset(x - BLOCKERS_DIM / 2, y - BLOCKERS_DIM / 2)
                    grid.stamp(loc)
                    val from = grid.flagsAt(x, y, plane) and CollisionFlags.WALK_MASK
                    val to = grid.flagsAt(toX, toY, plane) and CollisionFlags.WALK_MASK
                    val sides = if (diagonal) (grid.flagsAt(toX, y, plane) or grid.flagsAt(x, toY, plane)) and CollisionFlags.WALK_MASK else 0
                    if (from == 0 && to == 0 && sides == 0) continue
                    out += CollisionBlocker(square.ids[i], loc.shape, loc.rotation, loc.clipType, loc.blocksProjectile, from, to, sides)
                }
            }
            return out
        }

        const val BLOCKERS_CAP = 16
        private const val BLOCKERS_REACH = 8
        private const val BLOCKERS_DIM = 5
    }
}
