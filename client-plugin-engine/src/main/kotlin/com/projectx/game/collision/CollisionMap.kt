package com.projectx.game.collision

import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.sign

/**
 * The live collision map: the 192x192 tiles (3x3 map squares, all four planes) around the player, composed from the
 * client's own memory by a [CollisionWorker] on its own thread and published as immutable [CollisionSnapshot]s.
 *
 * Reads are free and safe from any thread (a volatile swap, no copying). The game thread only keeps the worker told
 * where the local player is ([feed]); the worker reads the client through [MemoryLiveWorld], whose reads cannot
 * fault, so nothing it does can stall or crash the game thread.
 *
 * The grids are for size-1 movers. A step that would leave the window is never allowed: ask [CollisionSnapshot.contains]
 * to tell "edge" from "blocked".
 */
internal object CollisionMap {
    const val DIM = CollisionGrid.DIM
    const val PLANES = CollisionGrid.PLANES

    private const val ENTITY_STALE_MS = 2_000L

    @Volatile
    private var published = CollisionSnapshot.EMPTY

    @Volatile
    private var testSnapshot: CollisionSnapshot? = null

    @Volatile
    private var testJump: CollisionJump? = null

    @Volatile
    private var worker: CollisionWorker? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var localEntity = 0L

    @Volatile
    private var localEntityAt = 0L

    /** This client's offset table carries what the reader needs (or a test map is installed). */
    val supported: Boolean get() = testSnapshot != null || MemoryLiveWorld.supported

    /** The latest published map; [CollisionSnapshot.EMPTY] before the first. Grab one for a burst of queries. */
    fun snapshot(): CollisionSnapshot = testSnapshot ?: published

    fun header(): CollisionHeader = snapshot().header

    /** The last time the worker saw the player's tile jump (a teleport's real landing), or null. */
    fun lastJump(): CollisionJump? {
        testJump?.let { return it }
        if (testSnapshot != null) return null
        return worker?.lastJump
    }

    /** Ask the worker to load the static squares around ([x], [y]) now: call it before a teleport whose landing is known. */
    fun prewarm(x: Int, y: Int) {
        worker?.prewarm(x, y)
    }

    /**
     * The locs that set walk bits on the cells of the step from ([x], [y], [plane]) by ([dx], [dy]), each -1..1: the
     * from cell, the to cell and a diagonal's two side cells. Null when there is no map or the step is not one step.
     */
    fun blockers(x: Int, y: Int, plane: Int, dx: Int, dy: Int): List<CollisionBlocker>? {
        if (dx !in -1..1 || dy !in -1..1 || dx == 0 && dy == 0) return null
        val snapshot = snapshot()
        if (!snapshot.isAvailable) return null
        return CollisionWorker.blockers(snapshot, x, y, plane, dx, dy)
    }

    /** A projectile line from [from] to [to] on the projectile layer; false off the window or across planes. */
    fun lineOfSight(from: Tile, to: Tile): Boolean {
        if (from.plane != to.plane) return false
        val snapshot = snapshot()
        val plane = from.plane
        if (!snapshot.contains(from.x, from.y, plane) || !snapshot.contains(to.x, to.y, plane)) return false
        var x = from.x
        var y = from.y
        val dx = abs(to.x - x)
        val dy = abs(to.y - y)
        val sx = (to.x - x).sign
        val sy = (to.y - y).sign
        var err = dx - dy
        while (x != to.x || y != to.y) {
            val e2 = 2 * err
            var stepX = 0
            var stepY = 0
            if (e2 > -dy) {
                err -= dy
                stepX = sx
            }
            if (e2 < dx) {
                err += dx
                stepY = sy
            }
            if (!projectileStep(snapshot, x, y, plane, stepX, stepY)) return false
            x += stepX
            y += stepY
        }
        return true
    }

    private fun projectileStep(snapshot: CollisionSnapshot, x: Int, y: Int, plane: Int, dx: Int, dy: Int): Boolean {
        if (dx != 0 && dy != 0) {
            if (projectile(snapshot, x + dx, y + dy, plane) and CollisionFlags.BLOCK_LOC != 0) return false
            return projectileStep(snapshot, x, y, plane, dx, 0) && projectileStep(snapshot, x + dx, y, plane, 0, dy) ||
                projectileStep(snapshot, x, y, plane, 0, dy) && projectileStep(snapshot, x, y + dy, plane, dx, 0)
        }
        val wall = when {
            dx < 0 -> CollisionFlags.WALL_W
            dx > 0 -> CollisionFlags.WALL_E
            dy < 0 -> CollisionFlags.WALL_S
            else -> CollisionFlags.WALL_N
        }
        if (projectile(snapshot, x, y, plane) and wall != 0) return false
        return projectile(snapshot, x + dx, y + dy, plane) and CollisionFlags.BLOCK_LOC == 0
    }

    private fun projectile(snapshot: CollisionSnapshot, x: Int, y: Int, plane: Int): Int =
        CollisionFlags.projectileBits(snapshot.flags(x, y, plane))

    /** Game thread: where the local player's entity is, and the worker started on first call. */
    fun feed(clientAddress: Long, entityAddress: Long) {
        localEntity = entityAddress
        localEntityAt = System.currentTimeMillis()
        if (thread == null && clientAddress != 0L && MemoryLiveWorld.supported) start(clientAddress)
    }

    private fun currentEntity(): Long =
        if (System.currentTimeMillis() - localEntityAt > ENTITY_STALE_MS) 0L else localEntity

    @Synchronized
    private fun start(clientAddress: Long) {
        if (thread != null) return
        val live = MemoryLiveWorld(clientAddress, ::currentEntity)
        val started = CollisionWorker(live, CacheStaticWorld, { published = it })
        worker = started
        thread = Thread({ run(started, live) }, "projectx-collision").apply {
            isDaemon = true
            start()
        }
        println("[CollisionMap] collision worker started (3x3 window, ${CollisionWorker.PASS_MS} ms cadence)")
    }

    private fun run(worker: CollisionWorker, live: LiveWorld) {
        var next = 0L
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(CollisionWorker.SLICE_MS)
                val tile = live.localTile()
                worker.noteTile(tile)
                val now = System.nanoTime() / 1_000_000
                worker.servePrewarm(now)
                if (!worker.movedSquare(tile) && now < next && !worker.hasStaticPending) continue
                next = now + CollisionWorker.PASS_MS
                worker.pass(now, tile)
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                println("[CollisionMap] pass failed: $t")
            }
        }
    }

    /** Engine teardown: stop the worker so this classloader can go. */
    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        worker = null
        published = CollisionSnapshot.EMPTY
    }

    /** Tests: serve these grids (`[plane][ly * DIM + lx]`, all four planes) as the published map; null masks go back to live. */
    fun installForTests(
        originX: Int,
        originY: Int,
        version: Int,
        status: CollisionStatus,
        masks: Array<ByteArray>?,
        flags: Array<IntArray>?,
        squaresUnloaded: Int = 0,
        squaresStatic: Int = 0,
        squares: List<SquareLocs> = emptyList(),
    ) {
        testSnapshot = if (masks == null) null else CollisionSnapshot(
            CollisionHeader(originX, originY, version, status, squaresUnloaded = squaresUnloaded, squaresStatic = squaresStatic),
            masks,
            flags,
            squares,
        )
    }

    /** Tests: the jump [lastJump] reports; null goes back to the worker's. */
    fun installJumpForTests(jump: CollisionJump?) {
        testJump = jump
    }
}
