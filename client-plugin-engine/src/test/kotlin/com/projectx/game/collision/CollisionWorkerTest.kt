package com.projectx.game.collision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollisionWorkerTest {
    private class FakeSquare(var stamp: Long) {
        val placements = mutableListOf<LiveLoc>()
        val dynamic = mutableListOf<LiveLoc>()
        var memoryTerrainFails = false
        val terrain = SquareTerrain()
    }

    private class FakeLive : LiveWorld {
        val squares = HashMap<Pair<Int, Int>, FakeSquare>()
        val locTypes = HashMap<Long, Pair<Int, LocProps>>()
        var noWorld = false

        fun square(x: Int, y: Int) = squares.getOrPut(x to y) { FakeSquare((x.toLong() shl 16) or y.toLong()) }

        override fun localTile(): LocalTile? = null

        override fun placementStamp(squareX: Int, squareY: Int): Long = if (noWorld) 0L else square(squareX, squareY).stamp

        override fun placements(squareX: Int, squareY: Int): SquareWalk {
            val square = square(squareX, squareY)
            return if (noWorld || square.stamp == 0L) SquareWalk(null, false) else SquareWalk(square.placements.toList(), false)
        }

        override fun dynamic(squareX: Int, squareY: Int): SquareWalk {
            val square = square(squareX, squareY)
            return if (noWorld || square.stamp == 0L) SquareWalk(null, false) else SquareWalk(square.dynamic.toList(), false)
        }

        override fun terrain(squareX: Int, squareY: Int): SquareTerrain? {
            val square = square(squareX, squareY)
            if (noWorld || square.memoryTerrainFails) return null
            return SquareTerrain(square.terrain.settings.copyOf())
        }

        override fun locType(locType: Long, expectId: Int): LocTypeRead {
            val (id, props) = locTypes[locType] ?: return LocTypeRead.Unreadable
            if (expectId >= 0 && id != expectId) return LocTypeRead.OtherId
            return LocTypeRead.Read(props)
        }
    }

    private class FakeStatic : StaticWorld {
        val defs = HashMap<Int, LocProps>()
        val squares = HashMap<Pair<Int, Int>, StaticSquare>()
        var terrainResult = CacheTerrainResult.OK
        val terrainResults = HashMap<Pair<Int, Int>, CacheTerrainResult>()

        override fun terrain(squareX: Int, squareY: Int): Pair<CacheTerrainResult, SquareTerrain?> {
            val result = terrainResults[squareX to squareY] ?: terrainResult
            return result to if (result == CacheTerrainResult.OK) SquareTerrain() else null
        }

        override fun locProps(id: Int): LocProps? = defs[id]

        override fun square(squareX: Int, squareY: Int): StaticSquare? = squares[squareX to squareY]
    }

    private val live = FakeLive()
    private val statics = FakeStatic()
    private var published: CollisionSnapshot? = null
    private val logs = mutableListOf<String>()
    private val worker = CollisionWorker(live, statics, { published = it }, { logs += it }, wallClock = { 1_000L })

    private val solid = LocProps(1, 1, 2, true)

    init {
        statics.defs[555] = LocProps(4, 4, 2, true)
        for (id in listOf(100, 0, 45476, 45477, 84, 85, 401, 1631)) live.locTypes[ptr(id)] = id to solid
        live.locTypes[ptr(77)] = 77 to LocProps(2, 3, 1, false)
        live.locTypes[ptr(85)] = 85 to LocProps(1, 1, 2, false)
    }

    private fun ptr(id: Int): Long = 0x10_0000L + id * 0x400L

    private fun placed(x: Int, y: Int, id: Int, shape: Int, rotation: Int, pointer: Long = ptr(id), plane: Int = 0) =
        LiveLoc(x, y, id, pointer, plane, shape, rotation, LiveLocSource.PLACEMENT)

    private fun header(): CollisionHeader = published!!.header

    private fun stamped(x: Int, y: Int): List<CollisionLoc> = published!!.squares.flatMap { it.locs }.filter { it.x == x && it.y == y }

    private val tile = LocalTile(PX, PY, 0)

    private fun firstCompose() {
        val centre = live.square(50, 50)
        centre.placements += placed(3220, 3220, 100, 10, 0)
        centre.placements += placed(3221, 3221, 77, 10, 1)
        centre.placements += placed(3222, 3222, 555, 10, 0, pointer = 0L)
        centre.placements += placed(3225, 3225, 999, 10, 0, pointer = 0xBADL)
        centre.placements += placed(3226, 3226, 0, 10, 0)
        centre.placements += placed(3227, 3227, 100, 10, 0, plane = 255)
        centre.placements += placed(3230, 3207, 45476, 0, 0)
        centre.placements += placed(3240, 3240, 84, 0, 1)
        centre.placements += placed(3240, 3240, 85, 0, 1)
        centre.placements += placed(3241, 3241, 84, 0, 1)
        live.square(48, 50).placements += placed(3135, 3200, 100, 10, 0)
        assertTrue(worker.pass(2_000, tile))
    }

    @Test
    fun `nothing is published without a player or a world`() {
        assertFalse(worker.pass(1_000, null))
        assertFalse(worker.pass(1_000, LocalTile(0, 0, 0)))
        live.noWorld = true
        assertFalse(worker.pass(1_000, tile))
        assertEquals(0, worker.placementWalks + worker.dynamicWalks + worker.memoryTerrainReads)
        assertNull(published)
    }

    @Test
    fun `the first pass composes every square`() {
        firstCompose()
        val h = header()
        assertEquals(1, h.version)
        assertEquals(3136 to 3136, h.originX to h.originY)
        assertEquals(CollisionStatus.OK, h.status)
        assertEquals(9, h.locCount)
        assertEquals(1, h.unresolvedDefs)
        assertEquals(0, h.squaresUnloaded)
        assertEquals(16, worker.placementWalks)
        assertEquals(16, worker.dynamicWalks)
        assertEquals(9, worker.memoryTerrainReads)
        assertEquals(0, worker.cacheTerrainReads)
        assertEquals(7, worker.locTypeReads, "each distinct loc type pointer read once")
        assertEquals(2, worker.definitionReads, "the cache only for the pointer-less 555 and the unreadable 999")

        val big = stamped(3221, 3221).single()
        assertEquals(LocProps(2, 3, 1, false), LocProps(big.sizeX, big.sizeY, big.clipType, big.blocksProjectile))
        assertEquals(4, stamped(3222, 3222).single().sizeX)
        assertTrue(stamped(3225, 3225).isEmpty())
        assertEquals(1, stamped(3226, 3226).size, "loc id 0 is a real loc")
        assertTrue(stamped(3227, 3227).isEmpty(), "under a bridge: dropped")
        assertEquals(2, stamped(3240, 3240).size)
        assertEquals(1, stamped(3135, 3200).size, "the west spill square's loc is stamped too")

        val s = published!!
        assertTrue(s.flags(3220, 3220, 0) and CollisionFlags.BLOCK_LOC != 0)
        assertEquals(0, s.mask(3219, 3220, 0) and MoveBits.E, "nothing steps onto the blocker")
        assertTrue(s.mask(3220, 3220, 0) and MoveBits.W != 0, "a step leads off it")
        assertEquals(0xFF, s.mask(3215, 3215, 0))
        assertFalse(s.contains(3135, 3200, 0))
        assertTrue(s.contains(3136 + 191, 3136 + 191, 3))
        assertFalse(s.contains(3136 + 192, 3200, 0))
    }

    @Test
    fun `an unchanged world walks only the dynamic layer and publishes nothing`() {
        firstCompose()
        val walks = worker.placementWalks
        val dynamic = worker.dynamicWalks
        assertFalse(worker.pass(2_600, LocalTile(PX + 1, PY, 0)))
        assertEquals(1, header().version)
        assertEquals(walks, worker.placementWalks)
        assertEquals(dynamic + 16, worker.dynamicWalks)
    }

    @Test
    fun `an opened door removes its static loc and stamps its spawn`() {
        firstCompose()
        val centre = live.square(50, 50)
        centre.dynamic += LiveLoc(3230, 3207, 45476, ptr(45476), 0, 0, 0, LiveLocSource.REMOVED)
        centre.dynamic += LiveLoc(3233, 3207, 45477, ptr(45477), 0, 0, 1, LiveLocSource.SPAWN)
        val walks = worker.placementWalks
        assertTrue(worker.pass(3_200, tile))
        assertEquals(2, header().version)
        assertEquals(9, header().locCount)
        assertEquals(walks, worker.placementWalks)
        assertTrue(stamped(3230, 3207).isEmpty())
        assertEquals(1, stamped(3233, 3207).single().rotation)
        assertTrue(published!!.flags(3233, 3207, 0) and CollisionFlags.CHANGED != 0, "the changed square is marked")
        assertTrue(published!!.flags(3135 + 1, 3200, 0) and CollisionFlags.CHANGED == 0)

        centre.dynamic.reverse()
        assertFalse(worker.pass(3_800, tile), "the same entries in another order are no change")

        centre.dynamic.clear()
        assertTrue(worker.pass(4_400, tile))
        assertEquals(1, stamped(3230, 3207).size)
        assertTrue(stamped(3233, 3207).isEmpty())
    }

    @Test
    fun `a removal takes the exact id before a loose match`() {
        firstCompose()
        val centre = live.square(50, 50)
        centre.dynamic += LiveLoc(3240, 3240, 85, ptr(85), 0, 0, 1, LiveLocSource.REMOVED)
        centre.dynamic += LiveLoc(3241, 3241, 99999, 0L, 0, 0, 1, LiveLocSource.REMOVED)
        centre.dynamic += LiveLoc(3220, 3220, 100, ptr(100), 0, 10, 3, LiveLocSource.REMOVED)
        assertTrue(worker.pass(5_000, tile))
        val left = stamped(3240, 3240).single()
        assertTrue(left.blocksProjectile, "85 (no projectile block) went, 84 stays")
        assertTrue(stamped(3241, 3241).isEmpty())
        assertEquals(1, stamped(3220, 3220).size)
        assertEquals(7, header().locCount)
    }

    @Test
    fun `placements are re-walked only when the square's stamp changes`() {
        firstCompose()
        val centre = live.square(50, 50)
        centre.placements += placed(3250, 3250, 100, 10, 0)
        assertFalse(worker.pass(2_600, tile))
        centre.stamp++
        val walks = worker.placementWalks
        val terrain = worker.memoryTerrainReads
        val types = worker.locTypeReads
        assertTrue(worker.pass(3_200, tile))
        assertEquals(walks + 1, worker.placementWalks)
        assertEquals(terrain + 1, worker.memoryTerrainReads, "a reloaded square's terrain is read again")
        assertEquals(types, worker.locTypeReads, "the pointer memo serves every loc type")
        assertEquals(1, stamped(3250, 3250).size)
    }

    @Test
    fun `unknown terrain degrades the map, a square that does not exist does not`() {
        live.square(51, 50).memoryTerrainFails = true
        statics.terrainResult = CacheTerrainResult.CORRUPT
        assertTrue(worker.pass(1_000, tile))
        assertEquals(CollisionStatus.DEGRADED, header().status)
        assertEquals(1, header().squaresMissingTerrain)
        assertTrue(published!!.flags(51 * 64, 50 * 64, 0) and CollisionFlags.TERRAIN_UNKNOWN != 0)

        assertFalse(worker.pass(2_000, tile), "nothing re-read inside the retry window")
        statics.terrainResult = CacheTerrainResult.OK
        assertTrue(worker.pass(6_100, tile), "the cache fallback arrives")
        assertEquals(CollisionStatus.OK, header().status)

        val other = CollisionWorker(live, statics, { published = it }, { })
        statics.terrainResult = CacheTerrainResult.MISSING
        assertTrue(other.pass(1_000, tile))
        assertEquals(CollisionStatus.OK, header().status)
        assertEquals(1, header().squaresMissingTerrain)
    }

    @Test
    fun `window squares the client has not loaded are counted, a void one is not`() {
        val x = 60 * 64 + 10
        listOf(60 to 61, 61 to 61, 59 to 59).forEach { (sx, sy) ->
            live.square(sx, sy).stamp = 0L
            live.square(sx, sy).memoryTerrainFails = true
        }
        statics.terrainResults[59 to 59] = CacheTerrainResult.MISSING
        assertTrue(worker.pass(1_000, LocalTile(x, x, 0)))
        assertEquals(2, header().squaresUnloaded)
        assertEquals(CollisionStatus.OK, header().status)
        live.square(60, 61).stamp = 0x6061
        assertTrue(worker.pass(1_600, LocalTile(x, x, 0)))
        assertEquals(1, header().squaresUnloaded)
    }

    @Test
    fun `an instance is published as such and never asks the cache`() {
        live.square(102, 50).memoryTerrainFails = true
        assertTrue(worker.pass(1_000, LocalTile(6500, 3218, 0)))
        assertEquals(CollisionStatus.INSTANCE, header().status)
        assertEquals(0, worker.cacheTerrainReads)
        assertEquals(1, header().squaresMissingTerrain)
    }

    @Test
    fun `logging out freezes the last grid as not ready`() {
        firstCompose()
        assertTrue(worker.pass(3_000, null))
        assertEquals(CollisionStatus.NOT_READY, header().status)
        assertEquals(2, header().version)
        assertTrue(published!!.isAvailable)
        assertFalse(worker.pass(3_600, null))
        live.noWorld = true
        assertFalse(worker.pass(4_200, tile))
        live.noWorld = false
        assertTrue(worker.pass(4_800, tile))
        assertEquals(CollisionStatus.OK, header().status)
    }

    @Test
    fun `a loc type pointer reused for another type is not served from the memo`() {
        firstCompose()
        val centre = live.square(50, 50)
        centre.placements += placed(3260, 3260, 401, 0, 1)
        centre.stamp++
        assertTrue(worker.pass(3_000, tile))
        assertEquals(2, stamped(3260, 3260).single().clipType)

        live.locTypes[ptr(401)] = 402 to LocProps(1, 1, 0, false)
        centre.placements[centre.placements.size - 1] = placed(3260, 3260, 402, 0, 1, pointer = ptr(401))
        centre.stamp++
        assertTrue(worker.pass(3_600, tile))
        assertEquals(0, stamped(3260, 3260).single().clipType)

        centre.placements += placed(3261, 3261, 555, 10, 0, pointer = ptr(401))
        centre.stamp++
        assertTrue(worker.pass(4_200, tile))
        assertEquals(4, stamped(3261, 3261).single().sizeX, "the pointer holds another id: the cache definition of 555")
        assertTrue(logs.any { "held another loc id" in it })
    }

    @Test
    fun `a settle recheck restamps a loc type read half filled in`() {
        firstCompose()
        val centre = live.square(50, 50)
        live.locTypes[ptr(1631)] = 1631 to LocProps(1, 1, 0, true)
        centre.placements += placed(3262, 3262, 1631, 0, 1)
        centre.stamp++
        assertTrue(worker.pass(10_000, tile))
        for (t in 10_600L until 30_000L step 600) worker.pass(t, tile)
        val square = live.square(50, 50)
        square.stamp++
        assertTrue(worker.pass(40_000, tile))
        assertEquals(0, stamped(3262, 3262).single().clipType)

        live.locTypes[ptr(1631)] = 1631 to LocProps(1, 1, 2, true)
        val walks = worker.placementWalks
        assertFalse(worker.pass(41_000, tile))
        assertEquals(walks, worker.placementWalks)
        assertTrue(worker.pass(42_100, tile), "the first recheck sees the change")
        assertEquals(2, stamped(3262, 3262).single().clipType)
        assertTrue(logs.any { "settle recheck 1/3" in it })
    }

    @Test
    fun `an arrival the client is still loading is published from static squares`() {
        val x = 70 * 64 + 10
        val blocker = CollisionLoc(70 * 64 + 5, 70 * 64 + 5, 0, 10, 0, 1, 1, 2, true)
        statics.squares[70 to 70] = StaticSquare(70, 70, listOf(blocker), intArrayOf(300), SquareTerrain())
        statics.squares[69 to 70] = StaticSquare(69, 70, emptyList(), IntArray(0), SquareTerrain())
        live.noWorld = true
        assertTrue(worker.pass(1_000, LocalTile(x, x, 0)))
        val h = header()
        assertEquals(69 * 64, h.originX)
        assertEquals(CollisionStatus.OK, h.status)
        assertEquals(9, h.squaresUnloaded)
        assertEquals(2, h.squaresStatic)
        assertEquals((1 shl 4) or (1 shl 3), h.staticMask)
        val flags = published!!.flags(blocker.x, blocker.y, 0)
        assertTrue(flags and CollisionFlags.BLOCK_LOC != 0 && flags and CollisionFlags.STATIC != 0)
        assertEquals(0, published!!.flags(71 * 64 + 1, 71 * 64 + 1, 0) and CollisionFlags.STATIC)

        live.noWorld = false
        live.square(70, 70).placements += placed(blocker.x, blocker.y, 100, 10, 0)
        assertTrue(worker.pass(1_600, LocalTile(x, x, 0)))
        assertEquals(0, header().squaresStatic)
        assertEquals(0, header().squaresUnloaded)
        assertEquals(0, published!!.flags(blocker.x, blocker.y, 0) and CollisionFlags.STATIC)
        assertTrue(published!!.flags(blocker.x, blocker.y, 0) and CollisionFlags.BLOCK_LOC != 0)
    }

    @Test
    fun `prewarm loads the static squares around a landing, never in an instance`() {
        statics.squares[80 to 80] = StaticSquare(80, 80, emptyList(), IntArray(0), null)
        worker.prewarm(80 * 64 + 10, 80 * 64 + 10)
        worker.servePrewarm(1_000)
        assertEquals(16, worker.staticLoads)
        assertTrue(logs.any { "1/16 static squares ready" in it })
        worker.servePrewarm(1_100)
        assertEquals(16, worker.staticLoads, "a served request is not served again")
        worker.prewarm(6500, 3200)
        worker.servePrewarm(1_200)
        assertEquals(16, worker.staticLoads)
    }

    @Test
    fun `a teleport or a plane change is a jump, a run or a hop is not`() {
        assertNull(worker.lastJump)
        worker.noteTile(LocalTile(3200, 3200, 0))
        worker.noteTile(LocalTile(3202, 3200, 0))
        worker.noteTile(LocalTile(3212, 3200, 0))
        assertNull(worker.lastJump)
        worker.noteTile(null)
        worker.noteTile(LocalTile(2900, 3500, 0))
        assertEquals(CollisionJump(1_000L, 2900, 3500, 0), worker.lastJump)
        worker.noteTile(LocalTile(2901, 3501, 1))
        assertEquals(1, worker.lastJump!!.plane)
    }

    @Test
    fun `the blockers of a step are the locs that stamp its cells`() {
        firstCompose()
        val snapshot = published!!
        val into = CollisionWorker.blockers(snapshot, 3219, 3220, 0, 1, 0)
        assertEquals(100, into.single().id)
        assertTrue(into.single().bitsTo and CollisionFlags.BLOCK_LOC != 0)
        assertEquals(0, into.single().bitsFrom)
        assertTrue(CollisionWorker.blockers(snapshot, 3220, 3220, 0, 0, 1).single().bitsFrom and CollisionFlags.BLOCK_LOC != 0)
        assertTrue(CollisionWorker.blockers(snapshot, 3219, 3219, 0, 1, 1).single().bitsTo and CollisionFlags.BLOCK_LOC != 0)
        assertTrue(CollisionWorker.blockers(snapshot, 3200, 3200, 0, 1, 0).isEmpty())
        assertTrue(CollisionWorker.blockers(snapshot, 3219, 3220, 1, 1, 0).isEmpty())
        val wall = CollisionWorker.blockers(snapshot, 3240, 3240, 0, 0, 1)
        assertEquals(setOf(84, 85), wall.map { it.id }.toSet(), "both walls on the edge are named")

        CollisionMap.installForTests(snapshot.originX, snapshot.originY, 1, CollisionStatus.OK, arrayOf(), arrayOf(), squares = snapshot.squares)
        try {
            assertNull(CollisionMap.blockers(3219, 3220, 0, 0, 0))
            assertNull(CollisionMap.blockers(3219, 3220, 0, 2, 0))
            assertNotNull(CollisionMap.blockers(3219, 3220, 0, 1, 0))
        } finally {
            CollisionMap.installForTests(0, 0, 0, CollisionStatus.NOT_READY, null, null)
        }
    }

    companion object {
        private const val PX = 50 * 64 + 10
        private const val PY = 50 * 64 + 10
    }
}
