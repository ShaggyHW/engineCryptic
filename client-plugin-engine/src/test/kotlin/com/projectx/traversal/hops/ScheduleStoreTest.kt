package com.projectx.traversal.hops

import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hop schedule's store: contributors split from this machine's own export, layers summed, samples kept only on a
 * learning client and folded into the local store, the weight cap, the old properties file converted, and the probe
 * give-up tallies kept in it.
 */
class ScheduleStoreTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("schedule-store-test")
        HopLearning.installForTests(null, null)
        ScheduleStore.installForTests(null, null)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        HopLearning.installForTests(null, null)
        ScheduleStore.installForTests(null, null)
        HopMap.installForTests(null)
        runCatching { dir.deleteRecursively() }
    }

    private fun learning(contributor: String) {
        HopLearning.installForTests(dir, contributor)
        ScheduleStore.installForTests(null, null)
    }

    @Test
    fun oldProbeKeysDropped() {
        val others = TreeMap<String, ScheduleStore.Sums>()
        ScheduleStore.parse(
            listOf(
                """{"k":"ptry:(1,1,0)->E","w":4,"s1":0,"s2":0}""",
                """{"k":"face:(1,1,0)->E:N","m":6,"w":4,"s1":0,"s2":0}""",
                """{"k":"stand:1:1:0","m":${HopModel.VERSION},"w":3,"s1":0,"s2":0}""",
                """{"k":"dead:DoorNode","w":2,"s1":3600,"s2":7e6}""",
                """{"k":"walk","m":${HopModel.SCHEDULE_DISCARD_BELOW - 1},"w":2,"s1":20,"s2":200}""",
                """{"k":"lock:LodestoneNode","m":${HopModel.VERSION},"w":1,"s1":2000,"s2":4e6}""",
            ),
            null, others, null,
        )
        assertFalse("ptry:(1,1,0)->E" in others, "an untagged probe give-up is dropped")
        assertFalse("face:(1,1,0)->E:N" in others, "an old-model probe give-up is dropped")
        assertTrue("stand:1:1:0" in others, "a current probe give-up stays")
        assertFalse("dead:DoorNode" in others, "an untagged timing is dropped")
        assertFalse("walk" in others, "an old-model margin is dropped")
        assertTrue("lock:LodestoneNode" in others, "a current timing stays")
        val file = dir.resolve("tagged.jsonl")
        ScheduleStore.write(file, others, null)
        assertTrue(Files.readString(file).contains("\"m\":${HopModel.VERSION}"), "written lines carry the model")
    }

    @Test
    fun parseSplitsContributors() {
        val others = TreeMap<String, ScheduleStore.Sums>()
        val own = TreeMap<String, ScheduleStore.Sums>()
        ScheduleStore.parse(
            listOf(
                """{"k":"walk","c":"aaaaaaaaaaaaaaaa","m":${HopModel.VERSION},"w":4,"s1":12,"s2":40}""",
                """{"k":"walk","c":"bbbbbbbbbbbbbbbb","m":${HopModel.VERSION},"w":2,"s1":2,"s2":4}""",
                """{"k":"dead:ObjectNode:Climb","c":"aaaaaaaaaaaaaaaa","m":${HopModel.VERSION},"w":3,"s1":21000,"s2":1.5e8}""",
                "not json",
                """{"k":"bad","w":-1,"s1":0,"s2":0}""",
            ),
            "aaaaaaaaaaaaaaaa", others, own,
        )
        assertTrue(others.size == 1 && others["walk"]?.w == 2.0, "others: only the other contributor")
        assertTrue(own.size == 2 && own["walk"]?.s1 == 12.0, "own: this machine's lines")
        assertTrue("bad" !in others && "bad" !in own, "damaged and negative lines skipped")
    }

    @Test
    fun layersAddUp() {
        ScheduleStore.installForTests(mapOf("walk" to ScheduleStore.Sums(2.0, 4.0, 10.0)), mapOf("walk" to ScheduleStore.Sums(3.0, 9.0, 30.0)))
        val s = ScheduleStore.get("walk")
        assertTrue(s.w == 5.0 && s.s1 == 13.0, "shared + local add up ($s)")
        ScheduleStore.add("walk", 100.0)
        assertEquals(5.0, ScheduleStore.get("walk").w, "a client that does not learn keeps no samples")
        ScheduleStore.installForTests(mapOf("dead:x" to ScheduleStore.Sums(50.0, 50_000.0, 5e7)), null)
        assertEquals(20.0, ScheduleStore.get("dead:x").w, "timings are capped at 20")
        assertTrue(abs(ScheduleStore.get("dead:x").mean() - 1000) < 1e-9, "capping keeps the mean")
    }

    @Test
    fun samplesFoldIntoTheStore() {
        learning("cccccccccccccccc")
        ScheduleStore.add("surge", 9.0)
        ScheduleStore.add("surge", 11.0)
        assertTrue(ScheduleStore.get("surge").w == 2.0 && ScheduleStore.get("surge").mean() == 10.0, "a learning client's samples count at once")
        val after = ScheduleStore.fold(mapOf("surge" to ScheduleStore.Sums(2.0, 20.0, 202.0)))
        assertEquals(2.0, after?.get("surge")?.w, "folded into the local store")
        val again = ScheduleStore.fold(mapOf("surge" to ScheduleStore.Sums(1.0, 7.0, 49.0)))
        assertTrue(again != null && again["surge"]?.w == 3.0 && again["surge"]?.s1 == 27.0, "a second client's fold adds to the first's")
        val text = Files.readString(dir.resolve(ScheduleStore.FILE), StandardCharsets.UTF_8)
        assertTrue(text.contains("\"k\":\"surge\"") && !text.contains("\"c\""), "one line per key, no contributor in the local store")
    }

    @Test
    fun oldPropertiesConvert() {
        learning("cccccccccccccccc")
        Files.writeString(
            dir.resolve(ScheduleStore.OLD_FILE),
            "walk.mean=5.0\nwalk.dev=1.0\nwalk.n=3\ndead.ObjectNode\\:Climb=7080\n",
            StandardCharsets.UTF_8,
        )
        ScheduleStore.migrateProperties()
        val f = ScheduleStore.readFile(dir.resolve(ScheduleStore.FILE))
        val walk = assertNotNull(f["walk"], "old means converted")
        assertTrue(walk.w == 3.0 && abs(walk.mean() - 5) < 1e-9)
        val climb = assertNotNull(f["dead:ObjectNode:Climb"], "old timings converted")
        assertTrue(abs(climb.mean() - 7080) < 1e-6)
        assertTrue(!Files.exists(dir.resolve(ScheduleStore.OLD_FILE)) && Files.exists(dir.resolve(ScheduleStore.OLD_FILE + ".migrated")), "old file renamed")
    }

    @Test
    fun surgeProbeFacing() {
        learning("cccccccccccccccc")
        val edge = HopEdges.pack(3109, 3290, 0, Direction.EAST)
        val se = Direction.SOUTH_EAST
        repeat(HopExplorer.FACING_TRIES - 1) { HopExplorer.noteSurgeFacing(edge, se, false) }
        assertFalse(HopExplorer.facingImpossible(edge, se), "not given up before ${HopExplorer.FACING_TRIES} stops")
        HopExplorer.noteSurgeFacing(edge, se, false)
        assertTrue(HopExplorer.facingImpossible(edge, se), "given up after ${HopExplorer.FACING_TRIES} stops never facing its way")
        assertFalse(HopExplorer.facingImpossible(edge, Direction.EAST), "another way along the same edge is not")
        val other = HopEdges.pack(2631, 3342, 0, Direction.SOUTH)
        HopExplorer.noteSurgeFacing(other, Direction.SOUTH, true)
        repeat(2 * HopExplorer.FACING_TRIES) { HopExplorer.noteSurgeFacing(other, Direction.SOUTH, false) }
        assertFalse(HopExplorer.facingImpossible(other, Direction.SOUTH), "once faced, never given up")
        ScheduleStore.installForTests(mapOf(HopExplorer.facingKey(other, se) to ScheduleStore.Sums(HopExplorer.FACING_TRIES.toDouble(), 0.0, 0.0)), null)
        assertTrue(HopExplorer.facingImpossible(other, se), "another contributor's stops count too")
    }

    @Test
    fun probeStandGivenUp() {
        learning("cccccccccccccccc")
        val t = Tile.of(3051, 3284, 0)
        repeat(HopExplorer.STAND_TRIES - 1) { HopExplorer.noteStand(t, false) }
        assertFalse(HopExplorer.standGivenUp(t))
        HopExplorer.noteStand(t, false)
        assertTrue(HopExplorer.standGivenUp(t), "given up after ${HopExplorer.STAND_TRIES} stops casting nothing")
        assertFalse(HopExplorer.standGivenUp(Tile.of(3051, 3283, 0)), "another tile is not")
        val cast = Tile.of(3109, 3290, 0)
        HopExplorer.noteStand(cast, true)
        repeat(2 * HopExplorer.STAND_TRIES) { HopExplorer.noteStand(cast, false) }
        assertFalse(HopExplorer.standGivenUp(cast), "a stand a probe went from once is never given up")
    }

    @Test
    fun surgeMisfaced() {
        learning("dddddddddddddddd")
        SyntheticHopMap().install()
        val sw = Direction.SOUTH_WEST
        val t = Tile.of(3150, 3200, 0)
        repeat(HopExplorer.MISFACE_TRIES - 1) { HopExplorer.noteSurgeWay(t, sw, false) }
        assertFalse(HopExplorer.misfaced(t, sw))
        HopExplorer.noteSurgeWay(t, sw, false)
        assertTrue(HopExplorer.misfaced(t, sw), "misfaced after ${HopExplorer.MISFACE_TRIES} misses")
        assertFalse(HopExplorer.misfaced(t, Direction.SOUTH), "another read way on the tile is not")
        assertNull(HopExplorer.surge(t, sw, { 99 }, 1), "no surge probe from a misfaced tile")
        val ok = Tile.of(3150, 3201, 0)
        HopExplorer.noteSurgeWay(ok, sw, true)
        repeat(2 * HopExplorer.MISFACE_TRIES) { HopExplorer.noteSurgeWay(ok, sw, false) }
        assertFalse(HopExplorer.misfaced(ok, sw), "once it went the read way, never misfaced")
    }

    @Test
    fun edgeTriesSpent() {
        learning("eeeeeeeeeeeeeeee")
        val e = HopEdges.pack(2608, 3393, 0, Direction.EAST)
        repeat(HopExplorer.EDGE_TRIES - 1) { HopExplorer.noteProbeTry(e, false) }
        assertFalse(HopExplorer.triesSpent(e))
        HopExplorer.noteProbeTry(e, false)
        assertTrue(HopExplorer.triesSpent(e), "given up after ${HopExplorer.EDGE_TRIES} tries with no pass")
        val n = HopEdges.pack(2608, 3394, 0, Direction.EAST)
        assertFalse(HopExplorer.triesSpent(n), "a neighbouring edge is not")
        HopExplorer.noteProbeTry(n, true)
        repeat(2 * HopExplorer.EDGE_TRIES) { HopExplorer.noteProbeTry(n, false) }
        assertFalse(HopExplorer.triesSpent(n), "an edge a hop crossed once is never given up")

        val from = Tile.of(2598, 3391, 0)
        val aim = Tile.of(2596, 3389, 0)
        assertFalse(HopExplorer.diveAimTested(from, aim))
        HopExplorer.noteDiveAim(from, aim, false)
        assertTrue(HopExplorer.diveAimTested(from, aim), "a dive aim tried once with no crossing is not tried again")
        assertFalse(HopExplorer.diveAimTested(from, Tile.of(2596, 3390, 0)), "another aim from the same tile is open")
        assertTrue(ScheduleStore.probeKey(HopExplorer.diveAimKey(from, aim)), "dropped with the probe keys on a clean start")

        assertFalse(HopExplorer.surgeWayTested(from, Direction.WEST))
        HopExplorer.noteSurgeWayTested(from, Direction.WEST, false)
        assertTrue(HopExplorer.surgeWayTested(from, Direction.WEST), "a surge way tried once with no crossing is not tried again")
        assertFalse(HopExplorer.surgeWayTested(from, Direction.SOUTH_WEST), "another way from the same tile is open")
        val crossed = Tile.of(2600, 3391, 0)
        HopExplorer.noteSurgeWayTested(crossed, Direction.WEST, true)
        assertFalse(HopExplorer.surgeWayTested(crossed, Direction.WEST), "a surge way that showed a crossing may go again")
        assertTrue(ScheduleStore.probeKey(HopExplorer.surgeWayKey(from, Direction.WEST)))
    }
}
