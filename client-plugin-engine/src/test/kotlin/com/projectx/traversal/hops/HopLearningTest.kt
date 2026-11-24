package com.projectx.traversal.hops

import com.projectx.pathfinder.GridWorld
import com.projectx.pathfinder.GridWorld.Companion.tile
import com.projectx.traversal.hops.SyntheticHopMap.Companion.OX
import com.projectx.traversal.hops.SyntheticHopMap.Companion.OY
import com.projectx.traversal.hops.SyntheticHopMap.Companion.t
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.TreeMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline hop learning: the model's directions, edge keys, evidence promotion, the explainer on recorded windows, the
 * overlay rule, the local store (flush, merge, rebuild, discard), the shared layer, and probe candidates, on synthetic
 * maps.
 */
class HopLearningTest {

    @BeforeTest
    fun reset() {
        HopLearning.installForTests(null, null)
        HopKnowledge.installForTests(null, null)
        ScheduleStore.installForTests(null, null)
        HopBlockers.clearForTests()
        HopBlockers.doorForTests = null
        HopExplorer.resetForTests()
        SharedHopData.dirOverride = null
    }

    @AfterTest
    fun restore() {
        reset()
        HopMap.installForTests(null)
    }

    private fun fence(): SyntheticHopMap = SyntheticHopMap().apply { for (y in 80..120) wallEast(100, y) }

    private fun observe(ability: String, from: Tile, direction: Direction?, aim: Tile?, landed: Tile?, outcome: String): HopObservation {
        val o = HopObservation()
        o.time = System.currentTimeMillis()
        o.ability = ability
        o.from = from
        o.starts += from
        o.direction = direction
        o.aim = aim
        o.build = "950-1"
        o.capture(from)
        o.landed = landed
        o.outcome = outcome
        return o
    }

    private fun twoStrong(): Evidence = Evidence().apply {
        success(true, 1, "950-1")
        success(true, 1, "950-1")
    }

    private val fenceEdge = HopEdges.pack(OX + 100, OY + 100, 0, Direction.EAST)

    @Test
    fun directions() {
        assertEquals(Direction.EAST, HopModel.dir8(10, 0))
        assertEquals(Direction.EAST, HopModel.dir8(10, 1))
        assertEquals(Direction.EAST, HopModel.dir8(2, 1), "2:1 is not diagonal")
        assertEquals(Direction.NORTH_EAST, HopModel.dir8(3, 2))
        assertEquals(Direction.EAST, HopModel.dir8(18, 9))
        assertEquals(Direction.EAST, HopModel.dir8(12, 6))
        assertEquals(Direction.EAST, HopModel.dir8(20, 10))
        assertEquals(Direction.NORTH_EAST, HopModel.dir8(16, 9))
        assertNull(HopModel.dir8(0, 0))
        assertEquals(Direction.SOUTH_WEST, HopModel.dir8(-20, -20))
        assertEquals(Direction.EAST, HopModel.dir8(20, 7))
        assertEquals(Direction.EAST, HopModel.dir8(20, 9))
        assertEquals(Direction.NORTH_EAST, HopModel.dir8(20, 12))
        assertEquals(Direction.SOUTH, HopModel.dir8(0, -11))
        assertEquals(Direction.NORTH_EAST, HopModel.fromBearing(30.0))
        assertEquals(Direction.NORTH, HopModel.fromBearing(350.0))
        assertEquals(Direction.SOUTH, HopModel.fromBearing(200.0))
    }

    @Test
    fun edgeKeys() {
        val a = HopEdges.pack(3200, 3200, 0, Direction.WEST)
        assertEquals(a, HopEdges.pack(3199, 3200, 0, Direction.EAST), "a step and its reverse are one edge")
        assertEquals(HopEdges.pack(10, 10, 1, Direction.SOUTH_WEST), HopEdges.pack(9, 9, 1, Direction.NORTH_EAST))
        val key = HopEdges.key(HopEdges.PASS, a)
        assertEquals("pe:3199:3200:0:E", key)
        assertEquals(a, HopEdges.parse(key))
        assertEquals(-1L, HopEdges.parse(HopEdges.typeKey(1902, 0)), "type keys are not edges")
    }

    @Test
    fun evidence() {
        val e = Evidence()
        e.success(true, 100, "950-1")
        assertFalse(e.usableEdge(), "one strong success is not enough")
        e.success(true, 100, "950-1")
        assertTrue(e.usableEdge(), "two strong successes make an edge usable")
        e.failure(101, "950-1")
        assertFalse(e.usableEdge(), "one failure demotes it")
        assertFalse(e.probeAllowed(102, "950-1"), "no reprobe the next day")
        assertFalse(e.probeAllowed(108, "950-1"), "a failure is for good")
        assertFalse(e.probeAllowed(102, "951-1"), "no reprobe on a new build either")
        repeat(3) { e.success(true, 109, "950-1") }
        assertTrue(e.usableEdge(), "three strong successes after the failure bring it back")

        val type = Evidence()
        type.success(true, 100, "950-1")
        type.place("pe:1:1:0:E")
        type.place("pe:5:1:0:E")
        assertFalse(type.usableType(), "two places by one contributor")
        type.place("pe:9:1:0:E")
        assertTrue(type.usableType(), "three places")

        val other = Evidence()
        other.success(true, 100, "950-1")
        other.pd = 99
        other.c += "b"
        e.add(other)
        assertTrue(e.s == 6 && e.pd == 99L && "b" in e.c, "merge sums successes and keeps the probe day")
        val (key, back) = assertNotNull(Evidence.fromJson(HopJson.parse(e.toJson("pe:1:2:0:N", "abc"))))
        assertEquals("pe:1:2:0:N", key)
        assertTrue(back.s == e.s && back.f == e.f && back.sa == e.sa && back.xf == e.xf && "abc" in back.c)
    }

    @Test
    fun `a store line keeps the shared field order and writes no contributor set`() {
        val e = Evidence()
        e.success(true, 20000, "951-3")
        e.c += "aaaaaaaaaaaaaaaa"
        e.c += "bbbbbbbbbbbbbbbb"
        assertEquals("""{"k":"pe:1:2:0:N","s":1,"w":0,"f":0,"fd":20000,"ld":20000,"b":"951-3"}""", e.toJson("pe:1:2:0:N", null))
    }

    @Test
    fun explainOvershoot() {
        fence().install()
        val o = observe(HopExplainer.DIVE, t(96, 100), null, t(104, 100), t(104, 100), HopExplainer.LANDED)
        assertFalse(o.walkStep(OX + 100, OY + 100, 0, Direction.EAST), "the window sees the fence as blocked")
        val r = HopExplainer.explain(o)
        assertTrue(r.kind == HopExplainer.Kind.PASS && r.strong && r.pass == listOf(fenceEdge), "overshoot explained by one fence edge ($r)")

        val s = observe(HopExplainer.SURGE, t(95, 95), Direction.NORTH_EAST, null, t(105, 105), HopExplainer.LANDED)
        val rs = HopExplainer.explain(s)
        assertTrue(
            rs.kind == HopExplainer.Kind.PASS && !rs.strong && rs.pass.isNotEmpty() && rs.pass.size <= 3,
            "diagonal surge across the fence is weak evidence at the first fence tile met ($rs)",
        )

        val back = assertNotNull(HopObservation.fromJson(o.toJson()))
        assertEquals(r, HopExplainer.explain(back), "observation JSON round-trips to the same explanation")
    }

    @Test
    fun explainUndershootAndMatch() {
        SyntheticHopMap().install()
        val m = observe(HopExplainer.SURGE, t(50, 50), Direction.NORTH, null, t(50, 60), HopExplainer.LANDED)
        assertEquals(HopExplainer.Kind.MATCH, HopExplainer.explain(m).kind)
        assertNull(HopExplainer.otherWay(m))
        val w = observe(HopExplainer.SURGE, t(50, 50), Direction.SOUTH_WEST, null, t(40, 50), HopExplainer.LANDED)
        assertEquals(Direction.WEST, HopExplainer.otherWay(w), "a surge read SW that went W is a direction miss")
        assertEquals(HopExplainer.Kind.MATCH, HopExplainer.explain(w).kind)

        val u = observe(HopExplainer.SURGE, t(50, 50), Direction.NORTH, null, t(50, 56), HopExplainer.LANDED)
        val r = HopExplainer.explain(u)
        assertTrue(r.kind == HopExplainer.Kind.STOP && r.stop == listOf(HopEdges.pack(OX + 50, OY + 56, 0, Direction.NORTH)), "stopper ($r)")

        val npc = observe(HopExplainer.SURGE, t(50, 50), Direction.NORTH, null, t(50, 56), HopExplainer.LANDED)
        npc.markOccupied(OX + 50, OY + 57)
        assertEquals(HopExplainer.Kind.UNEXPLAINED, HopExplainer.explain(npc).kind, "a stop before an NPC is not a map fact")

        val walk = observe(HopExplainer.DIVE, t(50, 50), null, t(70, 50), t(61, 50), HopExplainer.LANDED)
        walk.starts += t(51, 50)
        assertEquals(HopExplainer.Kind.MATCH, HopExplainer.explain(walk).kind, "a dive from a tile walked onto while arming matches")

        val refused = observe(HopExplainer.DIVE, t(50, 50), null, t(58, 50), null, HopExplainer.REFUSED)
        assertEquals(HopExplainer.Kind.REFUSED, HopExplainer.explain(refused).kind)
        val none = observe(HopExplainer.DIVE, t(50, 50), null, t(58, 50), null, HopExplainer.NO_CAST)
        assertEquals(HopExplainer.Kind.NO_CAST, HopExplainer.explain(none).kind)
    }

    @Test
    fun explainLearned() {
        HopKnowledge.installForTests(null, mapOf(HopEdges.key(HopEdges.PASS, fenceEdge) to twoStrong()))
        fence().install()
        assertTrue(HopKnowledge.passable(OX + 100, OY + 100, 0, Direction.EAST), "the learned edge is in the overlay")

        val crossed = observe(HopExplainer.DIVE, t(96, 100), null, t(104, 100), t(104, 100), HopExplainer.LANDED)
        assertTrue(fenceEdge in crossed.learnedPass, "the observation carries the learned edge")
        val r = HopExplainer.explain(crossed)
        assertTrue(r.kind == HopExplainer.Kind.LEARNED_OK && fenceEdge in r.confirmed, "crossing it confirms it ($r)")

        val refused = observe(HopExplainer.DIVE, t(96, 100), null, t(104, 100), null, HopExplainer.REFUSED)
        val f = HopExplainer.explain(refused)
        assertTrue(f.kind == HopExplainer.Kind.LEARNED_FAILED && fenceEdge in f.failed, "a refusal through it fails it ($f)")
    }

    @Test
    fun explainUncertain() {
        SyntheticHopMap().install()
        val ran = observe(HopExplainer.DIVE, t(50, 50), null, t(48, 60), t(48, 62), HopExplainer.LANDED)
        ran.held = false
        assertEquals(HopExplainer.Kind.MATCH, HopExplainer.explain(ran).kind, "2 tiles of running past the landing")
        val exact = observe(HopExplainer.DIVE, t(50, 50), null, t(48, 60), t(48, 62), HopExplainer.LANDED)
        assertTrue(HopExplainer.explain(exact).kind != HopExplainer.Kind.MATCH, "the same landing on a held hop")
        val armed = observe(HopExplainer.DIVE, t(50, 50), null, t(50, 70), t(52, 60), HopExplainer.LANDED)
        armed.held = false
        assertEquals(HopExplainer.Kind.MATCH, HopExplainer.explain(armed).kind, "a start 2 steps from the cast tile")

        fence().install()
        val loose = observe(HopExplainer.DIVE, t(96, 100), null, t(104, 100), t(104, 100), HopExplainer.LANDED)
        loose.held = false
        val r = HopExplainer.explain(loose)
        assertTrue(r.kind == HopExplainer.Kind.PASS && !r.strong, "a crossing on an unheld hop is weak ($r)")

        HopKnowledge.installForTests(null, mapOf(HopEdges.key(HopEdges.PASS, fenceEdge) to twoStrong()))
        val stopped = observe(HopExplainer.DIVE, t(96, 100), null, t(104, 100), null, HopExplainer.REFUSED)
        stopped.held = false
        stopped.starts += t(97, 100)
        assertEquals(HopExplainer.Kind.UNEXPLAINED, HopExplainer.explain(stopped).kind, "an uncertain start fails nothing")
    }

    @Test
    fun probeVerdicts() {
        val open = SyntheticHopMap()
        for (y in 80..120) open.wallEast(100, y)
        open.install()
        val round = observe(HopExplainer.DIVE, t(100, 81), null, t(104, 79), t(104, 79), HopExplainer.LANDED)
        round.probe = true
        round.probeEdge = HopEdges.pack(OX + 100, OY + 81, 0, Direction.EAST)
        round.starts += t(100, 79)
        val got = TreeMap<String, Evidence>()
        HopRecorder.applyResult(round, HopExplainer.explain(round), got)
        val ev = got[HopEdges.key(HopEdges.PASS, round.probeEdge)]
        assertTrue(ev == null || ev.f == 0, "a probe that went round its edge records no failure ($ev)")

        fence().install()
        val refused = observe(HopExplainer.DIVE, t(96, 100), null, t(104, 100), null, HopExplainer.REFUSED)
        refused.probe = true
        refused.probeEdge = fenceEdge
        assertTrue(HopExplainer.probeRefuted(refused), "a clean refused probe refutes its edge")
        val got2 = TreeMap<String, Evidence>()
        HopRecorder.applyResult(refused, HopExplainer.explain(refused), got2)
        assertEquals(1, got2[HopEdges.key(HopEdges.PASS, fenceEdge)]?.f, "one failure")

        val moving = observe(HopExplainer.DIVE, t(96, 100), null, t(104, 100), null, HopExplainer.REFUSED)
        moving.probe = true
        moving.probeEdge = fenceEdge
        moving.held = false
        assertFalse(HopExplainer.probeRefuted(moving), "an unheld probe refutes nothing")

        val zero = observe(HopExplainer.DIVE, t(100, 100), null, t(120, 100), t(100, 100), HopExplainer.LANDED)
        zero.probe = true
        zero.probeEdge = fenceEdge
        assertTrue(HopExplainer.probeRefuted(zero), "a far dive cast that moved 0 tiles refutes its edge")
        val got3 = TreeMap<String, Evidence>()
        HopRecorder.applyResult(zero, HopExplainer.explain(zero), got3)
        assertEquals(1, got3[HopEdges.key(HopEdges.PASS, fenceEdge)]?.f)

        val never = observe(HopExplainer.DIVE, t(100, 100), null, t(120, 100), null, HopExplainer.NO_CAST)
        never.probe = true
        never.probeEdge = fenceEdge
        val got4 = TreeMap<String, Evidence>()
        HopRecorder.applyResult(never, HopExplainer.explain(never), got4)
        val ev4 = got4[HopEdges.key(HopEdges.PASS, fenceEdge)]
        assertTrue(ev4 == null || ev4.f == 0, "a dive never cast records no failure")

        val stayed = observe(HopExplainer.DIVE, t(90, 100), null, t(110, 100), t(90, 100), HopExplainer.LANDED)
        stayed.probe = true
        stayed.probeEdge = fenceEdge
        assertFalse(HopExplainer.probeRefuted(stayed), "0 tiles where the map says it moves refutes nothing")
    }

    @Test
    fun overlayRule() {
        fence().install()
        HopKnowledge.installForTests(
            listOf("""{"k":"${HopEdges.key(HopEdges.PASS, fenceEdge)}","c":"aaaa","s":2,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1","m":8}"""),
            null,
        )
        val from = t(96, 100)
        assertEquals(t(104, 100), HopModel.dive(HopKnowledge.rule(), from, t(104, 100)), "dive across a learned fence edge lands")
        assertNull(HopModel.dive(HopModel.live(), from, t(104, 100)), "the plain live rule still refuses it")
        assertNull(HopModel.dive(HopKnowledge.rule(), t(96, 101), t(104, 101)), "one tile up the fence is still a wall")

        SyntheticHopMap().install()
        val stopper = HopEdges.pack(OX + 50, OY + 56, 0, Direction.NORTH)
        HopKnowledge.installForTests(
            listOf("""{"k":"${HopEdges.key(HopEdges.STOP, stopper)}","s":2,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1","m":8}"""),
            null,
        )
        assertEquals(t(50, 56), HopModel.surge(HopKnowledge.rule(), t(50, 50), Direction.NORTH), "a learned stopper cuts the surge")
        assertTrue(HopModel.live().canStep(OX + 50, OY + 56, 0, Direction.NORTH), "walking never reads it")
    }

    @Test
    fun `the overlay rule works over any walking rule`() {
        val line = { key: String -> twoStrong().toJson(key, "x").dropLast(1) + ",\"m\":8}" }
        val wall = GridWorld().block(3205, 3200)
        HopKnowledge.installForTests(
            listOf(
                line(HopEdges.key(HopEdges.PASS, HopEdges.pack(3204, 3200, 0, Direction.EAST))),
                line(HopEdges.key(HopEdges.PASS, HopEdges.pack(3205, 3200, 0, Direction.EAST))),
            ),
            null,
        )
        assertEquals(tile(3210, 3200), HopModel.surge(HopKnowledge.rule(wall.rule) { true }, tile(3200, 3200), Direction.EAST))
        assertEquals(tile(3204, 3200), HopModel.surge(wall.rule, tile(3200, 3200), Direction.EAST))
    }

    @Test
    fun baselineContributors() {
        HopKnowledge.installForTests(
            listOf(
                """{"k":"pt:1902:0","c":"aaaa","s":1,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1","m":8,"pl":["pe:1:1:0:E"]}""",
                """{"k":"pt:1902:0","c":"bbbb","s":1,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1","m":8,"pl":["pe:5:5:0:E"]}""",
                """{"k":"pt:77:0","c":"aaaa","s":5,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1","m":8,"pl":["pe:1:1:0:E"]}""",
            ),
            null,
        )
        assertTrue(HopKnowledge.typeUsable("pt:1902:0"), "two contributors at two places")
        assertFalse(HopKnowledge.typeUsable("pt:77:0"), "one place")
        val e = HopKnowledge.get("pt:1902:0")
        assertTrue(e != null && e.s == 2 && e.c.size == 2, "baseline lines are summed ($e)")

        fence().install()
        val edge = HopEdges.pack(OX + 100, OY + 105, 0, Direction.EAST)
        HopBlockers.putForTests(edge, listOf(HopBlockers.Blocker(1902, 0, 0, 2, false, 4, 1, 0)))
        assertTrue(HopKnowledge.passable(OX + 100, OY + 105, 0, Direction.EAST), "a usable type opens a cached edge")
        assertFalse(HopKnowledge.passable(OX + 100, OY + 106, 0, Direction.EAST), "an uncached edge stays closed")
    }

    @Test
    fun sharedLiveAndOwn() {
        val repo = Files.createTempDirectory("hops-repo")
        try {
            fence().install()
            val key = HopEdges.key(HopEdges.PASS, fenceEdge)
            HopKnowledge.installForTests(null, null)
            SharedHopData.dirOverride = repo
            val theirs = repo.resolve("contrib-bbbbbbbbbbbbbbbb.jsonl")
            Files.writeString(theirs, """{"k":"$key","c":"bbbbbbbbbbbbbbbb","s":2,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1","m":8}""" + "\n")
            HopKnowledge.reloadSharedIfChanged()
            assertTrue(HopKnowledge.passable(OX + 100, OY + 100, 0, Direction.EAST), "a contributor file is read live")
            Files.writeString(
                theirs,
                """{"k":"$key","c":"bbbbbbbbbbbbbbbb","s":2,"w":0,"f":1,"fd":1,"ld":2,"xf":2,"xb":"950-1","b":"950-1","m":8}""" + "\n",
            )
            Files.setLastModifiedTime(theirs, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            HopKnowledge.reloadSharedIfChanged()
            assertEquals(1, HopKnowledge.get(key)?.f, "a changed file is picked up on the next reload")
            assertFalse(HopKnowledge.passable(OX + 100, OY + 100, 0, Direction.EAST), "the demoted edge is closed again")
            Files.delete(theirs)

            HopLearning.installForTests(repo, "0123456789abcdef")
            val mine = Evidence()
            mine.success(true, 1, "950-1")
            mine.c += "0123456789abcdef"
            HopKnowledge.installForTests(
                listOf(
                    """{"k":"$key","c":"0123456789abcdef","s":1,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1","m":8}""",
                    """{"k":"pt:5:0","c":"cccccccccccccccc","s":1,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1","m":8}""",
                ),
                mapOf(key to mine, "pt:5:0" to mine.copy()),
            )
            assertEquals(1, HopKnowledge.get(key)?.s, "own exported line + own store count once")
            assertFalse(HopKnowledge.passable(OX + 100, OY + 100, 0, Direction.EAST))
            assertEquals(2, HopKnowledge.get("pt:5:0")?.s, "another contributor's line still adds to the store")
        } finally {
            repo.deleteRecursivelyQuietly()
        }
    }

    @Test
    fun store() {
        val dir = Files.createTempDirectory("hops-test")
        try {
            HopLearning.installForTests(dir, "0123456789abcdef")
            HopKnowledge.installForTests(null, null)
            fence().install()
            val o = observe(HopExplainer.DIVE, t(96, 100), null, t(104, 100), t(104, 100), HopExplainer.LANDED)
            val r = HopExplainer.explain(o)
            HopRecorder.applyResult(o, r, null)
            HopRecorder.applyResult(o, r, null)
            HopKnowledge.flushNow()
            val key = HopEdges.key(HopEdges.PASS, fenceEdge)
            assertEquals(2, HopStore.load()[key]?.s, "two explained crossings were flushed")
            assertTrue(Files.readAllLines(HopStore.knowledgeFile())[0].contains("\"model\":${HopModel.VERSION}"), "model header")
            assertTrue(HopKnowledge.passable(OX + 100, OY + 100, 0, Direction.EAST), "usable here after two strong crossings")

            val theirs = Evidence()
            theirs.success(true, HopStore.today(), "950-1")
            assertEquals(3, HopStore.flush(mapOf(key to theirs))?.get(key)?.s, "a second client's flush adds to the store")

            val file = HopStore.knowledgeFile()
            val good = Files.readAllLines(file)
            Files.writeString(file, "{not json\n")
            assertNull(HopStore.flush(mapOf(key to theirs)), "an unparseable store is not written")
            assertEquals("{not json\n", Files.readString(file), "... and left alone")
            Files.write(file, good)

            HopStore.append(o.toJson())
            HopStore.append(o.toJson())
            val lines = Files.readAllLines(file).toMutableList()
            lines[0] = """{"meta":"hops","model":0}"""
            Files.write(file, lines)
            assertTrue(HopStore.rebuildIfModelChanged(), "a store from another model is rebuilt")
            assertEquals(2, HopStore.load()[key]?.s, "rebuilt from the two observations")
            assertFalse(HopStore.rebuildIfModelChanged(), "and not rebuilt twice")
        } finally {
            dir.deleteRecursivelyQuietly()
        }
    }

    @Test
    fun probes() {
        fence().install()
        val server = t(96, 100)
        val gain = { l: Tile -> l.x - server.x }
        HopBlockers.putForTests(fenceEdge, listOf(HopBlockers.Blocker(1902, 0, 0, 2, false, 4, 1, 0)))
        HopBlockers.doorForTests = { false }
        val p = HopExplorer.surge(server, Direction.EAST, gain, 8)
        assertTrue(p != null && p.edge == fenceEdge && p.landingIfOpen == t(106, 100), "a surge probe tests the fence edge ($p)")
        ScheduleStore.installForTests(null, mapOf(HopExplorer.surgeWayKey(server, Direction.EAST) to ScheduleStore.Sums(1.0, 0.0, 0.0)))
        assertNull(HopExplorer.surge(server, Direction.EAST, gain, 8), "a surge probe already cast E from the tile")
        assertFalse(HopExplorer.surgeWayTested(server, Direction.NORTH_EAST), "the tile facing another way is open")
        assertFalse(HopExplorer.surgeWayTested(t(97, 100), Direction.EAST), "the same way from another tile is open")
        ScheduleStore.installForTests(null, null)
        HopExplorer.noteProbe(fenceEdge)
        assertFalse(HopExplorer.eligible(fenceEdge), "not probed again within the gap")
        HopExplorer.resetForTests()
        assertTrue(HopExplorer.eligible(fenceEdge), "eligible again after it")
        val count = HopExplorer.probeCount()
        HopExplorer.setEdgeGapOverride(1)
        assertEquals(15_000L, HopExplorer.edgeGapMs(), "a gap override is never below 15 s")
        HopExplorer.noteProbe(fenceEdge)
        assertEquals(count + 1, HopExplorer.probeCount())
        assertFalse(HopExplorer.eligible(fenceEdge), "within the shortened gap the edge still waits")
        HopExplorer.setEdgeGapOverride(-1)
        assertEquals(HopExplorer.EDGE_GAP_MS, HopExplorer.edgeGapMs())
        HopExplorer.resetForTests()
        HopBlockers.doorForTests = { it == 1902 }
        assertNull(HopExplorer.surge(server, Direction.EAST, gain, 8), "never through a door")
        HopBlockers.doorForTests = { false }

        val route = (96..125).map { t(it, 100) }
        val d = HopExplorer.dive(server, route, 0, 30, 20, gain, 6)
        assertTrue(d != null && d.edge == fenceEdge, "a dive probe tests the fence edge ($d)")
        val firstAim = assertNotNull(d.aim)
        ScheduleStore.installForTests(null, mapOf(HopExplorer.diveAimKey(server, firstAim) to ScheduleStore.Sums(1.0, 0.0, 0.0)))
        val d2 = HopExplorer.dive(server, route, 0, 30, 20, gain, 6)
        assertTrue(d2 != null && d2.edge == fenceEdge && d2.aim != firstAim, "a tested aim goes at another aim ($d2)")
        assertFalse(HopExplorer.diveAimTested(t(97, 100), firstAim), "from another tile the first aim is open")
        ScheduleStore.installForTests(null, mapOf(HopExplorer.diveAimKey(server, firstAim) to ScheduleStore.Sums(1.0, 1.0, 1.0)))
        assertFalse(HopExplorer.diveAimTested(server, firstAim), "a dive probe that showed a crossing may go again")
        ScheduleStore.installForTests(null, null)

        fence().water(101, 100).install()
        val wet = HopExplorer.surge(server, Direction.EAST, gain, 8)
        assertTrue(wet == null || wet.edge != fenceEdge, "never into water")
    }

    @Test
    fun `probes need known blockers`() {
        fence().install()
        val server = t(96, 100)
        assertNull(HopExplorer.surge(server, Direction.EAST, { it.x - server.x }, 8), "no blocker source, no probe")
    }

    @Test
    fun manualOutranks() {
        val refuted = Evidence()
        refuted.failure(1, "b")
        refuted.failure(2, "b")
        assertFalse(refuted.usableEdge())
        refuted.manualSuccess(3, "b")
        assertTrue(refuted.usableEdge(), "one manual success beats learned failures")
        assertFalse(refuted.probeAllowed(9, "b"), "a manually decided key is not probed")

        val learned = Evidence()
        repeat(5) { learned.success(true, 1, "b") }
        assertTrue(learned.usableEdge())
        learned.manualFailure(2, "b")
        assertFalse(learned.usableEdge(), "one manual failure beats learned successes")
        learned.success(true, 3, "b")
        assertFalse(learned.usableEdge(), "a learned success after it does not bring it back")
        learned.manualSuccess(4, "b")
        assertTrue(learned.usableEdge(), "a manual success after the manual failure does")

        val (_, e) = assertNotNull(Evidence.fromJson(HopJson.parse(learned.toJson("pe:1:1:0:E", "aaaa"))))
        assertTrue(e.ms == 1 && e.mf == 1 && e.msa == 1 && e.mxf == 2L && e.usableEdge(), "manual counts survive the JSON line")
        val sum = e.copy()
        sum.add(e)
        assertTrue(sum.ms == 2 && sum.mf == 2 && sum.msa == 2, "manual counts add up across contributors")
        val max = Evidence.atLeast(e, e)
        assertTrue(max.ms == 1 && max.mf == 1, "the larger of two views, not the sum")

        val into = TreeMap<String, Evidence>()
        val e42 = HopEdges.pack(OX + 10, OY + 10, 0, Direction.EAST)
        val e43 = HopEdges.pack(OX + 10, OY + 11, 0, Direction.EAST)
        val o = HopObservation()
        o.manual = true
        o.time = 0
        HopRecorder.applyResult(o, HopExplainer.Result(HopExplainer.Kind.PASS, pass = listOf(e42), strong = true), into)
        val m = into[HopEdges.key(HopEdges.PASS, e42)]
        assertTrue(m != null && m.ms == 1 && m.s == 1 && m.usableEdge(), "a manual strong pass counts as manual")
        into.clear()
        HopRecorder.applyResult(o, HopExplainer.Result(HopExplainer.Kind.PASS, pass = listOf(e42, e43), strong = false), into)
        val weak = into[HopEdges.key(HopEdges.PASS, e42)]
        assertTrue(weak != null && weak.ms == 0 && weak.w == 1, "a manual weak pass stays ordinary evidence")
        into.clear()
        o.manual = false
        HopRecorder.applyResult(o, HopExplainer.Result(HopExplainer.Kind.PASS, pass = listOf(e42), strong = true), into)
        assertEquals(0, into[HopEdges.key(HopEdges.PASS, e42)]?.ms, "an automatic strong pass is not manual")
    }

    @Test
    fun sharedModelFilter() {
        fence().install()
        val body = """{"k":"${HopEdges.key(HopEdges.PASS, fenceEdge)}","c":"aaaa","s":2,"w":0,"f":0,"fd":1,"ld":1,"b":"950-1""""
        val from = t(96, 100)
        HopKnowledge.installForTests(listOf("$body}"), null)
        assertNull(HopModel.dive(HopKnowledge.rule(), from, t(104, 100)), "a shared line with no model is ignored")
        HopKnowledge.installForTests(listOf("$body,\"m\":${HopModel.DISCARD_BELOW - 1}}"), null)
        assertNull(HopModel.dive(HopKnowledge.rule(), from, t(104, 100)), "a shared line from an older model is ignored")
        HopKnowledge.installForTests(listOf("$body,\"m\":${HopModel.VERSION}}"), null)
        assertEquals(t(104, 100), HopModel.dive(HopKnowledge.rule(), from, t(104, 100)), "a shared line from this model counts")
    }

    @Test
    fun discardOld() {
        val root = Files.createTempDirectory("hops-discard-test")
        try {
            val store = root.resolve("hops")
            Files.createDirectories(store.resolve("observations"))
            HopLearning.installForTests(store, "aaaaaaaaaaaaaaaa")
            Files.writeString(store.resolve("knowledge.jsonl"), "{\"meta\":\"hops\",\"model\":6}\n{\"k\":\"pe:1:1:0:E\",\"s\":2}\n")
            Files.writeString(store.resolve("observations").resolve("2026-11-01-1.jsonl"), "{\"id\":\"x\",\"mv\":6}\n")
            Files.writeString(store.resolve("schedule.jsonl"), "{\"k\":\"walk\",\"m\":6,\"w\":1,\"s1\":1,\"s2\":1}\n")
            assertTrue(HopStore.discardIfOld(), "an old store is moved aside")
            assertTrue(!Files.exists(store.resolve("knowledge.jsonl")) && !Files.exists(store.resolve("observations")), "the client starts empty")
            val backups = root.listDirectoryEntries("hops-reset-v6-*")
            assertEquals(1, backups.size)
            val backup = backups.first()
            assertTrue(Files.exists(backup.resolve("knowledge.jsonl")) && Files.exists(backup.resolve("observations").resolve("2026-11-01-1.jsonl")))
            assertTrue(Files.exists(backup.resolve("schedule.jsonl")), "the old schedule is kept with it")
            assertFalse(HopStore.discardIfOld(), "nothing left to move")

            Files.writeString(store.resolve("knowledge.jsonl"), "{\"meta\":\"hops\",\"model\":${HopModel.VERSION}}\n")
            assertTrue(!HopStore.discardIfOld() && Files.exists(store.resolve("knowledge.jsonl")), "a current store stays")
            Files.delete(store.resolve("knowledge.jsonl"))
            Files.createDirectories(store.resolve("observations"))
            Files.writeString(store.resolve("observations").resolve("2026-11-02-1.jsonl"), "{\"id\":\"y\",\"mv\":5}\n")
            assertTrue(HopStore.discardIfOld() && !Files.exists(store.resolve("observations")), "old observations with no store move too")
        } finally {
            root.deleteRecursivelyQuietly()
        }
    }

    @Test
    fun `the shipped shared data is found on the classpath`() {
        val contrib = SharedHopData.resourceFiles(SharedHopData.CONTRIB)
        assertTrue(contrib.isNotEmpty() && contrib.all { it.isNotEmpty() }, "contrib files")
        val others = TreeMap<String, Evidence>()
        HopKnowledge.split(contrib.flatten(), null, others, TreeMap())
        assertTrue(others.isNotEmpty(), "model-8 lines are read")
        assertTrue(SharedHopData.resourceFiles(SharedHopData.SCHEDULE).isNotEmpty(), "schedule files")
    }

    @OptIn(ExperimentalPathApi::class)
    private fun Path.deleteRecursivelyQuietly() {
        runCatching { deleteRecursively() }
    }
}
