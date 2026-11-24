package com.projectx.traversal.local

import com.projectx.game.collision.CollisionJump
import com.projectx.game.collision.CollisionMap
import com.projectx.game.collision.CollisionStatus
import com.projectx.pathfinder.StepRule
import com.projectx.traversal.nodes.RoutePicks
import com.projectx.traversal.local.LiveMapFixture.Companion.OX
import com.projectx.traversal.local.LiveMapFixture.Companion.OY
import com.projectx.traversal.local.LiveMapFixture.Companion.t
import com.projectx.traversal.local.LiveMapFixture.Companion.wallWithGap
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalNavTest {
    private var now = 1_000_000L

    @BeforeTest
    fun setUp() {
        LocalNav.resetForTests()
        LocalNav.clock = { now }
    }

    @AfterTest
    fun tearDown() {
        LocalNav.resetForTests()
        LocalNav.clock = System::currentTimeMillis
        LiveMapFixture.uninstall()
    }

    @Test
    fun `grid search distances are chebyshev on open ground and stop at the depth cap`() {
        val open = StepRule { _, _, _, _ -> true }
        val window = SearchWindow(0, 0, 192, 0)
        assertEquals(10, GridSearch.distance(open, window, Tile.of(50, 50, 0), Tile.of(57, 60, 0), 1000))
        val dist = GridSearch.fill(open, window, 50, 50, 5)
        assertEquals(-1, dist[window.index(56, 50)])
        assertEquals(5, dist[window.index(55, 50)])
    }

    @Test
    fun `a door's side tiles pass only while it stands open`() {
        wallWithGap(-1).install()
        assertEquals(false, LocalNav.passage(t(100, 100), t(101, 100), 3))
        wallWithGap(100).install()
        assertEquals(1, LocalNav.distance(t(100, 100), t(101, 100), 3))
    }

    @Test
    fun `a double door whose other leaf stands open passes within three steps, not two`() {
        wallWithGap(101).install()
        assertEquals(true, LocalNav.passage(t(100, 100), t(101, 100), 3))
        assertEquals(false, LocalNav.passage(t(100, 100), t(101, 100), 2))
    }

    @Test
    fun `a footprint is reached from the tiles beside it`() {
        val map = LiveMapFixture()
        for (x in 60..61) for (y in 60..61) map.block(x, y)
        map.install()
        val field = LocalNav.field(t(55, 60))
        assertNotNull(field)
        assertEquals(4, field.distanceToFootprint(OX + 60, OY + 60, 0, 2, 2))
    }

    @Test
    fun `line of sight stops at a projectile wall, not at a fence`() {
        wallWithGap(-1).install()
        assertFalse(CollisionMap.lineOfSight(t(95, 100), t(106, 100)))
        assertTrue(CollisionMap.lineOfSight(t(95, 100), t(95, 110)))
        val fence = LiveMapFixture()
        for (y in 80..120) fence.wallEast(100, y, projectile = false)
        fence.install()
        assertTrue(CollisionMap.lineOfSight(t(95, 100), t(106, 100)))
    }

    private fun tiles(path: List<Tile>?, vararg xy: Int): Boolean =
        path != null && path.size * 2 == xy.size && path.indices.all { path[it] == t(xy[2 * it], xy[2 * it + 1]) }

    @Test
    fun `the server path goes straight first, then diagonal`() {
        LiveMapFixture().install()
        assertTrue(tiles(LocalNav.serverPath(t(50, 60), t(60, 64)), 50, 60, 51, 60, 52, 60, 53, 60, 54, 60, 55, 60, 56, 60, 57, 61, 58, 62, 59, 63, 60, 64))
        assertTrue(tiles(LocalNav.serverPath(t(50, 60), t(58, 56)), 50, 60, 51, 60, 52, 60, 53, 60, 54, 60, 55, 59, 56, 58, 57, 57, 58, 56))
        assertTrue(tiles(LocalNav.serverPath(t(50, 60), t(50, 60)), 50, 60))

        val block = LiveMapFixture()
        for (x in 69..71) for (y in 59..61) block.block(x, y)
        block.install()
        assertTrue(tiles(LocalNav.serverPath(t(60, 60), t(70, 60)), 60, 60, 61, 60, 62, 60, 63, 60, 64, 60, 65, 60, 66, 60, 67, 60, 68, 60))

        val boxed = LiveMapFixture()
        for (x in 99..121) for (y in 49..71) boxed.block(x, y)
        boxed.install()
        assertNull(LocalNav.serverPath(t(60, 60), t(110, 60)))
    }

    private fun fire(x: Int, y: Int, direction: Direction) = ServerWalk.Fire(t(x, y), direction)

    @Test
    fun `a surge on the walk fires from the server tile or up to a tick on`() {
        LiveMapFixture().install()
        val walk = ServerWalk(LocalNav, { now }) { 0L }
        val idle = walk.fires(t(50, 60), Direction.EAST)
        assertTrue(idle.fires == null && !idle.hold)

        walk.clicked(t(50, 60), t(60, 64))
        val held = walk.fires(t(50, 60), Direction.NORTH)
        assertTrue(held.fires == null && held.hold)
        assertEquals(listOf(fire(52, 60, Direction.EAST), fire(53, 60, Direction.EAST), fire(54, 60, Direction.EAST)), walk.fires(t(52, 60), null).fires)
        assertEquals(listOf(t(52, 60), t(53, 60), t(54, 60)), walk.ahead(t(52, 60)))
        assertEquals(listOf(t(50, 60), t(51, 60), t(52, 60)), walk.ahead(t(50, 60)))
        assertEquals(t(54, 60), walk.tickOn(t(52, 60)))
        assertNull(walk.tickOn(t(52, 61)))
        val bend = walk.fires(t(55, 60), null).fires!!
        assertEquals(Direction.EAST, bend[0].direction)
        assertEquals(fire(57, 61, Direction.NORTH_EAST), bend[2])
        assertEquals(listOf(fire(55, 60, Direction.EAST)), walk.fires(t(55, 60), null, 0).fires)
        assertEquals(listOf(fire(60, 64, Direction.NORTH_EAST)), walk.fires(t(60, 64), null).fires)
        val off = walk.fires(t(52, 61), null)
        assertTrue(off.fires == null && !off.hold)

        walk.clicked(t(54, 60), t(54, 70))
        assertEquals(fire(54, 61, Direction.NORTH), walk.fires(t(54, 61), null).fires!![0])
        assertEquals(fire(56, 60, Direction.EAST), walk.fires(t(56, 60), null).fires!![0])
        assertEquals(t(54, 70), walk.click)

        walk.forget()
        assertNull(walk.click)
        assertNull(walk.fires(t(54, 61), null).fires)

        walk.hopCast()
        walk.clicked(t(50, 60), t(70, 60))
        walk.hopSettled(t(58, 66), Direction.NORTH_EAST)
        val landing = walk.fires(t(58, 66), null)
        assertTrue(landing.fires == null && landing.hold)
        assertEquals(fire(59, 66, Direction.EAST), walk.fires(t(59, 66), null).fires!![0])
        assertFalse(LocalNav.serverPath(t(50, 60), t(70, 60))!!.contains(t(59, 66)))

        walk.clicked(t(50, 60), t(70, 60))
        walk.hopCast()
        walk.hopSettled(t(58, 66), Direction.NORTH_EAST)
        assertNotNull(walk.fires(t(51, 60), null).fires, "a click before the cast is left alone")
    }

    @Test
    fun `another action than a walk click drops the predicted walk`() {
        LiveMapFixture().install()
        var others = 0L
        val walk = ServerWalk(LocalNav, { now }) { others }
        walk.clicked(t(50, 60), t(60, 60))
        others++
        assertNull(walk.click)
        assertNull(walk.tickOn(t(52, 60)))
    }

    @Test
    fun `a long run is not a teleport, a jump is, and the map is distrusted until it has caught up`() {
        LiveMapFixture().install()
        LocalNav.observe(t(50, 50))
        LocalNav.observe(t(60, 50))
        assertFalse(LocalNav.arriving())
        now += 1_250
        LocalNav.observe(t(75, 50))
        assertFalse(LocalNav.arriving())
        LocalNav.observe(t(140, 50))
        assertTrue(LocalNav.arriving())
        assertFalse(LocalNav.trusted(t(140, 50)))
        now += 1_600
        assertTrue(LocalNav.arriving(), "no timer while the window is centred on another square")

        LocalNav.observe(t(100, 100))
        LiveMapFixture().install(CollisionStatus.OK, 2)
        assertTrue(LocalNav.arriving())
        assertFalse(LocalNav.trusted(t(100, 100)))
        LiveMapFixture().install(CollisionStatus.OK, 0)
        assertFalse(LocalNav.arriving())
        assertTrue(LocalNav.trusted(t(100, 100)))
        LiveMapFixture().install(CollisionStatus.OK, 1)
        assertFalse(LocalNav.arriving(), "a square unloading later is not a new arrival")

        LocalNav.observe(t(140, 50))
        LocalNav.observe(t(100, 100))
        LiveMapFixture().install(CollisionStatus.OK, 3)
        assertTrue(LocalNav.arriving())
        now += 1_000
        LiveMapFixture().install(CollisionStatus.OK, 2)
        assertTrue(LocalNav.arriving(), "a square loading restarts the hold")
        now += 1_000
        assertTrue(LocalNav.arriving())
        now += 1_000
        assertFalse(LocalNav.arriving(), "the count held long enough: over before the cap")
    }

    @Test
    fun `an arrival complete with static squares is usable for walk and hop planning only`() {
        LiveMapFixture().install()
        LocalNav.observe(t(100, 100))
        LocalNav.observe(t(160, 100))
        LocalNav.observe(t(100, 100))
        LiveMapFixture().install(CollisionStatus.OK, 3, 3)
        assertTrue(LocalNav.arriving())
        assertFalse(LocalNav.trusted(t(100, 100)))
        assertTrue(LocalNav.usableForHops(t(100, 100)))
        assertTrue(LocalNav.staticReady())
        LocalNav.allowStatic().use {
            assertTrue(LocalNav.trusted(t(100, 100)))
            assertEquals(3, LocalNav.distance(t(100, 100), t(103, 100), 10))
            assertNull(LocalNav.untrustedReason(t(100, 100)))
            assertFalse(LocalNav.trustedLive(t(100, 100)))
            LocalNav.withStatic { assertTrue(LocalNav.trusted(t(100, 100))) }
            assertTrue(LocalNav.trusted(t(100, 100)), "still trusted after a nested scope closes")
        }
        assertFalse(LocalNav.trusted(t(100, 100)))
        assertEquals(LocalNav.UNKNOWN, LocalNav.distance(t(100, 100), t(103, 100), 10))

        LiveMapFixture().install(CollisionStatus.OK, 3, 2)
        assertFalse(LocalNav.usableForHops(t(100, 100)))
        val why = LocalNav.withStatic { LocalNav.untrustedReason(t(100, 100)) }
        assertTrue(why != null && "(2 static)" in why, why)
        LiveMapFixture().install(CollisionStatus.DEGRADED, 3, 3)
        assertFalse(LocalNav.usableForHops(t(100, 100)))
        LiveMapFixture().install(CollisionStatus.OK, 0, 0)
        assertTrue(LocalNav.trusted(t(100, 100)) && !LocalNav.arriving())
    }

    @Test
    fun `a teleport is dated from the worker's jump when it ended on the landing`() {
        LiveMapFixture().install()
        LocalNav.observe(t(100, 100))
        val start = now
        CollisionMap.installJumpForTests(CollisionJump(start + 50, OX + 161, OY + 100, 0))
        now += 200
        LocalNav.observe(t(160, 100))
        assertEquals(start + 50, LocalNav.lastTeleportAt)
        assertTrue(LocalNav.lastTeleportSeenAt >= start + 200)

        LocalNav.observe(t(100, 100))
        assertEquals(LocalNav.lastTeleportSeenAt, LocalNav.lastTeleportAt, "a jump before the previous observation is another move")
        CollisionMap.installJumpForTests(CollisionJump(now, OX + 40, OY + 40, 0))
        now += 20
        LocalNav.observe(t(160, 100))
        assertEquals(LocalNav.lastTeleportSeenAt, LocalNav.lastTeleportAt, "a jump that ended elsewhere is another move")
    }

    @Test
    fun `an untrusted map answers unknown`() {
        wallWithGap(-1).install(CollisionStatus.DEGRADED)
        assertFalse(LocalNav.trusted(t(95, 90)))
        assertEquals(LocalNav.UNKNOWN, LocalNav.distance(t(95, 90), t(96, 90), 5))
        assertNull(RoutePicks.choose(listOf(t(95, 90), t(99, 90)), t(95, 90), 20, 0), "no click choice on a degraded map")
        wallWithGap(-1).install()
        assertEquals(LocalNav.UNKNOWN, LocalNav.distance(t(95, 90), Tile.of(OX - 5, OY, 0), 5))
        LiveMapFixture.uninstall()
        assertFalse(LocalNav.trusted(t(95, 90)), "no map at all")
    }

    @Test
    fun `a tile nothing enters is not reached, but a step leads off it`() {
        LiveMapFixture().block(60, 60).install()
        val field = LocalNav.field(t(70, 60), 80)!!
        assertEquals(LocalNav.UNREACHABLE, field.distanceTo(t(60, 60)))
        assertEquals(1, LocalNav.distance(t(60, 60), t(61, 60), 1))
        assertFalse(LocalNav.walkable(t(60, 60)))
        assertTrue(LocalNav.walkable(t(61, 60)))
    }

    @Test
    fun `the live map feeds hop learning in the shared flag layout`() {
        wallWithGap(-1).install()
        assertTrue(CollisionHopMap.contains(OX + 100, OY + 100, 0))
        assertTrue(CollisionHopMap.known(OX + 100, OY + 100, 0))
        assertEquals(4, CollisionHopMap.flags(OX + 100, OY + 100, 0))
        assertFalse(CollisionHopMap.walk().canStep(OX + 100, OY + 100, 0, Direction.EAST))
    }
}
