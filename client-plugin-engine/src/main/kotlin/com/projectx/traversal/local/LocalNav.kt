package com.projectx.traversal.local

import com.projectx.game.collision.CollisionFlags
import com.projectx.game.collision.CollisionHeader
import com.projectx.game.collision.CollisionMap
import com.projectx.game.collision.CollisionSnapshot
import com.projectx.game.collision.CollisionStatus
import com.projectx.game.collision.MoveBits
import com.projectx.pathfinder.CacheWalkMap
import com.projectx.pathfinder.StepRule
import com.projectx.pathfinder.WalkField
import com.projectx.pathfinder.WalkMap
import com.projectx.traversal.hops.HopKnowledge
import com.projectx.traversal.nodes.TraversalSupport
import world.gregs.voidps.type.Tile
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Walking distances on the live collision map ([CollisionMap]) for the traversal: is this door open right now, can I
 * walk to that tile and in how many steps, which of two staircases is nearer.
 *
 * Every answer first asks [trusted]: the tile is inside the window, the map status is OK, and the player has not just
 * teleported. After a teleport nothing is trusted until the window has re-centred on the player and every window square
 * has loaded (or the count still unloaded has held for [ARRIVAL_STABLE_MS], or [ARRIVAL_MAX_MS] have passed). An
 * untrusted question answers [UNKNOWN] or null, and the caller does what it did without a map.
 *
 * While the client has not loaded a window square the worker serves it from the cache, doors in their default state.
 * That never ends an arrival on its own: walk and hop planning opt in with [allowStatic], inside which an arrival is
 * trusted once every square is live or static ([staticReady]). Door and object reachability stays outside: an open
 * door reads closed in static data.
 *
 * A client whose offset table lacks the reader falls back to [CacheWalkMap].
 */
object LocalNav : WalkMap({ StepRule { _, _, _, _ -> false } }) {
    const val UNREACHABLE = WalkMap.UNREACHABLE
    const val UNKNOWN = WalkMap.UNKNOWN

    private const val TELEPORT_JUMP_TILES = 12
    private const val RUN_TILES_PER_TICK = 2.0
    private const val TICK_MS = 600.0
    private const val ARRIVAL_MAX_MS = 5_000L
    private const val ARRIVAL_STABLE_MS = 1_800L
    private const val LANDING_MATCH_TILES = 12

    private val live: Boolean get() = CollisionMap.supported

    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    internal fun resetForTests() {
        lastObserved = null
        lastObservedAt = 0L
        arrivingSince = 0L
        arrivalUnloaded = -1
        arrivalStaticAt = 0L
        lastTeleportAt = 0L
        lastTeleportSeenAt = 0L
        lastTeleportTile = null
        newest = null
        older = null
    }

    // ---- static opt-in (per thread, nestable) ----

    private val staticDepth = ThreadLocal.withInitial { IntArray(1) }

    /** Closes one [allowStatic] level. */
    internal class StaticScope internal constructor() : AutoCloseable {
        override fun close() {
            val depth = staticDepth.get()
            if (depth[0] > 0) depth[0]--
        }
    }

    private val scope = StaticScope()

    /** Opens a scope (`use { }`) in which this thread's questions may be answered from static squares during an arrival. */
    internal fun allowStatic(): StaticScope {
        staticDepth.get()[0]++
        return scope
    }

    internal inline fun <T> withStatic(block: () -> T): T = allowStatic().use { block() }

    /**
     * [withStatic] for a block that suspends: the opt-in follows the coroutine onto whichever thread resumes it and
     * leaves the thread whenever it suspends, so another coroutine on the same thread never inherits it.
     */
    internal suspend fun <T> withStaticSuspending(block: suspend () -> T): T =
        withContext(staticDepth.asContextElement(IntArray(1) { 1 })) { block() }

    private fun staticAllowed(): Boolean = staticDepth.get()[0] > 0

    // ---- arrival gate ----

    @Volatile
    private var lastObserved: Tile? = null

    @Volatile
    private var lastObservedAt = 0L

    @Volatile
    private var arrivingSince = 0L

    @Volatile
    private var arrivalUnloaded = -1

    @Volatile
    private var arrivalUnloadedSince = 0L

    @Volatile
    private var arrivalStaticAt = 0L

    /** Wall-clock ms the last teleport landed (the worker's jump time when known), 0 before the first. */
    @Volatile
    var lastTeleportAt = 0L
        private set

    /** Wall-clock ms [observe] first saw the last teleport, 0 before the first. */
    @Volatile
    internal var lastTeleportSeenAt = 0L
        private set

    /** The first tile of the last teleport, null before the first. */
    @Volatile
    var lastTeleportTile: Tile? = null
        private set

    val lastTeleportX: Int get() = lastTeleportTile?.x ?: -1

    val lastTeleportY: Int get() = lastTeleportTile?.y ?: -1

    val lastTeleportPlane: Int get() = lastTeleportTile?.plane ?: -1

    /** Run from the main logic every frame, so a teleport is seen whether or not a walk is running. */
    internal fun tick() {
        TraversalSupport.serverTileOrNull()?.let(::observe)
    }

    /**
     * Feed the player's tile in, every pass. A jump of more than [TELEPORT_JUMP_TILES] plus a running tick's two tiles
     * for every tick since the last observation (they can be seconds apart while a node waits out a leg) opens an
     * arrival.
     */
    internal fun observe(player: Tile) {
        CacheWalkMap.observe(player)
        val now = clock()
        val previous = lastObserved
        val previousAt = lastObservedAt
        lastObserved = player
        lastObservedAt = now
        if (previous == null || !isTeleport(previous, player, now - previousAt)) return
        val landed = landedAt(player, previousAt, now)
        arrivalUnloaded = -1
        arrivalStaticAt = 0L
        arrivingSince = landed
        lastTeleportAt = landed
        lastTeleportSeenAt = now
        lastTeleportTile = player
    }

    internal fun isTeleport(previous: Tile, player: Tile, elapsedMs: Long): Boolean {
        val allowed = TELEPORT_JUMP_TILES + RUN_TILES_PER_TICK * ceil(elapsedMs / TICK_MS)
        val jump = previous.getLongestDelta(player)
        return previous.plane != player.plane && jump > TELEPORT_JUMP_TILES || jump > allowed
    }

    /**
     * When the teleport onto [player] landed: the worker's jump time when it falls between the two observations and
     * ended near this tile, else now. A lodestone or fairy ring node returns ~2 s after the landing.
     */
    private fun landedAt(player: Tile, previousAt: Long, now: Long): Long {
        val jump = CollisionMap.lastJump() ?: return now
        if (jump.atMs <= previousAt || jump.atMs > now || jump.plane != player.plane ||
            max(abs(jump.x - player.x), abs(jump.y - player.y)) > LANDING_MATCH_TILES
        ) return now
        return jump.atMs
    }

    /** True while a teleport arrival is open. */
    fun arriving(): Boolean = if (live) arriving(CollisionMap.header()) else CacheWalkMap.arriving()

    private fun arriving(header: CollisionHeader): Boolean {
        val since = arrivingSince
        if (since == 0L) return false
        val player = lastObserved
        if (player == null || !header.centredOn(player.x, player.y)) return true
        val now = clock()
        if (arrivalStaticAt == 0L && header.squaresStatic > 0 && header.squaresUncovered == 0 && header.status == CollisionStatus.OK) {
            arrivalStaticAt = now
        }
        val waited = now - since
        val unloaded = header.squaresUnloaded
        if (unloaded != arrivalUnloaded) {
            arrivalUnloaded = unloaded
            arrivalUnloadedSince = now
        }
        if (unloaded > 0 && waited < ARRIVAL_MAX_MS && now - arrivalUnloadedSince < ARRIVAL_STABLE_MS) return true
        if (arrivingSince == since) {
            arrivingSince = 0L
            val staticAt = arrivalStaticAt
            println(
                "[LocalNav] map caught up $waited ms after the teleport" +
                    (if (unloaded > 0) " ($unloaded square(s) still unloaded, ${header.squaresStatic} static)" else "") +
                    (if (staticAt != 0L) " (static from ${staticAt - since} ms)" else "")
            )
        }
        return false
    }

    // ---- trust ----

    override fun trusted(tile: Tile): Boolean =
        if (live) trusted(CollisionMap.snapshot(), tile.x, tile.y, tile.plane) else CacheWalkMap.trusted(tile)

    private fun trusted(snapshot: CollisionSnapshot, x: Int, y: Int, plane: Int): Boolean =
        snapshot.isAvailable && snapshot.header.status == CollisionStatus.OK && snapshot.contains(x, y, plane) &&
            (!arriving(snapshot.header) || staticAllowed() && staticReady(snapshot.header))

    /** [trusted] on live squares only, even inside an [allowStatic] scope. */
    internal fun trustedLive(tile: Tile): Boolean {
        val depth = staticDepth.get()
        val was = depth[0]
        depth[0] = 0
        try {
            return trusted(tile)
        } finally {
            depth[0] = was
        }
    }

    /** [trusted] that also accepts an arrival complete with static squares: for hop and walk planning. */
    internal fun usableForHops(tile: Tile): Boolean = withStatic { trusted(tile) }

    /** An arrival is open but the window is complete with live and static squares. */
    internal fun staticReady(): Boolean = live && staticReady(CollisionMap.header())

    private fun staticReady(header: CollisionHeader): Boolean {
        val player = lastObserved
        return header.isPublished && header.status == CollisionStatus.OK && header.squaresStatic > 0 &&
            header.squaresUncovered == 0 && player != null && header.centredOn(player.x, player.y)
    }

    /** Why [trusted] is false for [tile], or null when it is trusted; for the logs. */
    internal fun untrustedReason(tile: Tile): String? {
        if (!live) return if (CacheWalkMap.trusted(tile)) null else "cache map: not loaded, an instance or arriving"
        val snapshot = CollisionMap.snapshot()
        if (!snapshot.isAvailable) return "no live map"
        val header = snapshot.header
        if (header.status != CollisionStatus.OK) return "map status ${header.status}"
        if (!snapshot.contains(tile.x, tile.y, tile.plane)) return "outside the window (origin ${header.originX},${header.originY})"
        if (arriving(header) && !(staticAllowed() && staticReady(header))) {
            val player = lastObserved
            return if (player == null || !header.centredOn(player.x, player.y)) {
                "teleport arrival: map not re-centred yet"
            } else {
                "teleport arrival: ${header.squaresUnloaded} square(s) unloaded (${header.squaresStatic} static)"
            }
        }
        return null
    }

    /** Trusted, inside the window and not fully blocked. */
    override fun walkable(tile: Tile): Boolean {
        if (!live) return CacheWalkMap.walkable(tile)
        val snapshot = CollisionMap.snapshot()
        return trusted(snapshot, tile.x, tile.y, tile.plane) && !snapshot.isBlocked(tile.x, tile.y, tile.plane)
    }

    // ---- searches ----

    private fun ruleOf(snapshot: CollisionSnapshot): StepRule =
        StepRule { x, y, plane, direction -> snapshot.canMove(x, y, plane, MoveBits.of(direction.delta.x, direction.delta.y)) }

    override fun rule(): StepRule = if (live) ruleOf(CollisionMap.snapshot()) else CacheWalkMap.rule()

    override fun hopRule(): StepRule = HopKnowledge.rule(rule(), ::trusted)

    private fun window(snapshot: CollisionSnapshot, plane: Int) =
        SearchWindow(snapshot.originX, snapshot.originY, CollisionMap.DIM, plane)

    private class Cached(val version: Int, val field: WalkField)

    @Volatile
    private var newest: Cached? = null

    @Volatile
    private var older: Cached? = null

    /** Distances from [from] out to [maxDepth] steps on one map version, or null when [from] is not trusted. */
    override fun field(from: Tile, maxDepth: Int): WalkField? {
        if (!live) return CacheWalkMap.field(from, maxDepth)
        val snapshot = CollisionMap.snapshot()
        if (!trusted(snapshot, from.x, from.y, from.plane)) return null
        val depth = maxDepth.coerceAtLeast(1)
        for (cached in arrayOf(newest, older)) {
            if (cached != null && cached.version == snapshot.version && cached.field.start == from && cached.field.maxDepth >= depth) {
                return cached.field
            }
        }
        val window = window(snapshot, from.plane)
        val field = WalkField(window, from, depth, GridSearch.fill(ruleOf(snapshot), window, from.x, from.y, depth))
        older = newest
        newest = Cached(snapshot.version, field)
        return field
    }

    /** Steps from [from] to [to]; [UNREACHABLE] beyond [maxDepth], [UNKNOWN] when either is untrusted or on another plane. */
    override fun distance(from: Tile, to: Tile, maxDepth: Int): Int {
        if (!live) return CacheWalkMap.distance(from, to, maxDepth)
        if (from.plane != to.plane) return UNKNOWN
        val snapshot = CollisionMap.snapshot()
        if (!trusted(snapshot, from.x, from.y, from.plane) || !trusted(snapshot, to.x, to.y, to.plane)) return UNKNOWN
        val d = GridSearch.distance(ruleOf(snapshot), window(snapshot, from.plane), from, to, maxDepth)
        return if (d < 0) UNREACHABLE else d
    }

    /** The tiles the server walks for a walk click on [to] from [from], [from] first; null when unknown. */
    override fun serverPath(from: Tile, to: Tile): List<Tile>? {
        if (!live) return CacheWalkMap.serverPath(from, to)
        if (from.plane != to.plane) return null
        val snapshot = CollisionMap.snapshot()
        if (!trusted(snapshot, from.x, from.y, from.plane) || !trusted(snapshot, to.x, to.y, to.plane)) return null
        return GridSearch.serverPath(ruleOf(snapshot), window(snapshot, from.plane), from, to)
    }

    /** Whether the tile's square is served from static data right now. */
    internal fun isStatic(tile: Tile): Boolean = CollisionMap.snapshot().flags(tile.x, tile.y, tile.plane) and CollisionFlags.STATIC != 0
}
