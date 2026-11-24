package com.projectx.traversal.pathing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.script.api.Area
import com.projectx.script.api.Lodestone
import com.projectx.script.api.TeleportSpell
import com.projectx.webwalker.WalkGoal
import com.projectx.webwalker.WebArea
import com.projectx.webwalker.WebInterfaceStep
import com.projectx.webwalker.WebItemStep
import com.projectx.webwalker.WebLinkKind
import com.projectx.webwalker.WebNestedStep
import com.projectx.webwalker.WebWalkStep
import com.projectx.webwalker.WebPath
import world.gregs.voidps.type.Tile
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavpathTest {

    private fun fixture(name: String): String =
        NavpathTest::class.java.getResourceAsStream("/navpath/$name")!!.use { it.reader().readText() }

    private fun tile(x: Int, y: Int, plane: Int = 0) = Tile.of(x, y, plane)

    private fun mapped(name: String, start: Tile): WebPath {
        val result = NavRouteMapper.map(start, ResultsParser.parse(fixture(name)))
        return assertIs<NavRouteMapper.Mapped.Route>(result, result.toString()).path
    }

    // ---- parser ----

    @Test
    fun `a walk with a door, a surge and a staircase parses into its steps`() {
        val route = ResultsParser.parse(fixture("walk-door-stairs.json"))
        assertTrue(route.found)
        assertEquals(6, route.lengthTiles)
        assertEquals(listOf("move", "move", "door", "surge", "move", "object", "move"), route.steps.map { it.type })
        val door = assertIs<Step.Door>(route.steps[2])
        assertEquals("IN", door.direction)
        assertEquals(1530, door.realIdClosed)
        assertEquals(tile(3201, 3200), door.tileInside)
        val stairs = assertIs<Step.Loc>(route.steps[5])
        assertEquals(24350, stairs.id)
        assertEquals("Climb-up", stairs.action)
        assertEquals(NavArea(3211, 3200, 3214, 3203, 1), stairs.destinationArea)
        assertEquals(tile(3212, 3201, 1), stairs.to)
    }

    @Test
    fun `chained rows become the step's chain`() {
        val route = ResultsParser.parse(fixture("teleport-npc-item.json"))
        val lodestone = assertIs<Step.Lodestone>(route.steps[0])
        assertEquals("VARROCK", lodestone.value)
        val npc = assertIs<Step.Npc>(route.steps[2])
        assertEquals(376, npc.id)
        assertEquals("Travel", npc.action)
        val click = assertIs<Step.IFSlot>(npc.chain.single())
        assertEquals(1188, click.interfaceId)
        assertEquals(0, click.clickId)
        val glory = assertIs<Step.ItemClick>(route.steps[3])
        assertEquals("Rub", glory.option)
        assertEquals(720, assertIs<Step.IFSlot>(glory.chain.single()).interfaceId)
    }

    @Test
    fun `a later step of the parent's own kind stays in the chain`() {
        val route = ResultsParser.parse(fixture("ifslot-ifslot-chain.json"))
        val first = assertIs<Step.IFSlot>(route.steps[1])
        assertEquals(1145, first.interfaceId)
        val next = assertIs<Step.IFSlot>(first.chain.single())
        assertEquals(1188, next.interfaceId)
        assertEquals(8, next.component)
    }

    @Test
    fun `requirement ids come from the metadata and every row, as ids or as text`() {
        val action = JsonParser.parseString(
            """{"type":"object","metadata":{"requirements":[74],"db_row":{"requirements":"74;379"},
              "steps":[{"kind":"object","db_row":{"requirements":"12, 13"}},{"kind":"ifslot","db_row":{"requirements":null}}]}}""",
        ).asJsonObject
        assertEquals(listOf(74, 379, 12, 13), ResultsParser.requirementIds(action))
        assertEquals(emptyList(), ResultsParser.requirementIds(JsonParser.parseString("""{"type":"move"}""").asJsonObject))
    }

    @Test
    fun `fairy rings keep the ring object or the portable flag`() {
        val route = ResultsParser.parse(fixture("fairy-rings.json"))
        val portable = assertIs<Step.FairyRing>(route.steps[0])
        assertTrue(portable.portable)
        assertEquals(-1, portable.objectId)
        val ring = assertIs<Step.FairyRing>(route.steps[2])
        assertFalse(ring.portable)
        assertEquals(12128, ring.objectId)
        assertEquals("ajr", ring.code)
    }

    @Test
    fun `a generic teleport is dropped and the rest of the route kept, and a missing route is not found`() {
        assertEquals(listOf("move"), ResultsParser.parse(fixture("generic-teleport.json")).steps.map { it.type })
        assertEquals(listOf("move"), ResultsParser.parse(fixture("off-graph-start.json")).steps.map { it.type })
        assertEquals(listOf("move"), ResultsParser.parse(route("""{"type":"warp","to":[1,1,0]}""", """{"type":"move","to":[3200,3200,0]}""")).steps.map { it.type })
        val missing = ResultsParser.parse(fixture("not-found.json"))
        assertFalse(missing.found)
        assertTrue(missing.steps.isEmpty())
    }

    @Test
    fun `malformed answers are refused`() {
        assertFailsWith<ResultsParseException> { ResultsParser.parse("") }
        assertFailsWith<ResultsParseException> { ResultsParser.parse("<html>busy</html>") }
        assertFailsWith<ResultsParseException> { ResultsParser.parse("""{"found":true}""") }
        assertFailsWith<ResultsParseException> { ResultsParser.parse("""{"actions":[{"type":"move","to":[1,2]}]}""") }
        assertFailsWith<ResultsParseException> { ResultsParser.parse("""{"actions":[{"type":"npc","to":[1,2,0]}]}""") }
    }

    // ---- mapping onto the walker ----

    @Test
    fun `moves and surges become walked tiles and a door becomes a door link`() {
        val path = mapped("walk-door-stairs.json", tile(3198, 3200))
        assertEquals(tile(3198, 3200), path.tile(0))
        val door = path.nextLink(1)
        val doorLink = assertNotNull(path.linkAt(door))
        assertEquals(WebLinkKind.DOOR, doorLink.kind)
        assertEquals(1530, doorLink.objectId)
        assertEquals("Open", doorLink.action)
        assertEquals(tile(3200, 3200), path.tile(door - 1))
        assertEquals(tile(3201, 3200), path.tile(door))
        for (i in door + 1 until path.size) {
            if (path.linkAt(i) == null) assertTrue(path.tile(i).getLongestDelta(path.tile(i - 1)) <= 1, "a gap at $i")
        }
        val stairs = path.nextLink(door + 1)
        val stairsLink = assertNotNull(path.linkAt(stairs))
        assertEquals(WebLinkKind.OBJECT, stairsLink.kind)
        assertEquals(24350, stairsLink.objectId)
        assertEquals(12, stairsLink.searchRadius)
        assertEquals(1, stairsLink.to.plane)
        assertTrue(stairsLink.to.contains(3212, 3201, 1))
        assertEquals(tile(3213, 3202, 1), path.tile(path.lastIndex))
    }

    @Test
    fun `a house tablet's Modify chain ends by breaking the new tablet`() {
        val path = mapped("tablet-modify.json", tile(3222, 3218))
        val modify = assertNotNull(path.linkAt(path.lastIndex))
        assertEquals(WebLinkKind.ITEM, modify.kind)
        assertEquals("Modify", modify.action)
        assertEquals(18810, modify.target?.id)
        assertEquals(listOf(WebInterfaceStep(720, 1, -1, 0), WebInterfaceStep(1188, 18, -1, 0), WebItemStep(18809, "Break")), modify.chain)
    }

    @Test
    fun `teleports, npcs and items become links with their chains`() {
        val path = mapped("teleport-npc-item.json", tile(3000, 3000))
        val lodestone = assertNotNull(path.linkAt(1))
        assertEquals(WebLinkKind.LODESTONE, lodestone.kind)
        assertEquals(Lodestone.VARROCK, lodestone.lodestone)
        val npc = assertNotNull(path.linkAt(path.nextLink(2)))
        assertEquals(WebLinkKind.NPC, npc.kind)
        assertEquals(376, npc.target?.id)
        assertEquals(WebInterfaceStep(1188, 8, -1, 0), npc.chain.single())
        val glory = assertNotNull(path.linkAt(path.lastIndex))
        assertEquals(WebLinkKind.ITEM, glory.kind)
        assertEquals("Rub", glory.action)
        assertTrue(glory.isGlobal)
        assertTrue(glory.to.contains(3087, 3496, 0))
    }

    @Test
    fun `passage jewellery, item-on-object and interface steps map onto their link kinds`() {
        val path = mapped("poa-useon-ifslot.json", tile(3000, 3000))
        val links = (0 until path.size).mapNotNull(path::linkAt)
        assertEquals(listOf(WebLinkKind.POA, WebLinkKind.USE_ON, WebLinkKind.INTERFACE), links.map { it.kind })
        assertEquals(listOf("Games necklace", "Burthorpe"), links[0].menu)
        assertEquals(2622, links[1].objectId)
        assertEquals(954, links[1].target?.id)
        assertEquals(WebInterfaceStep(1092, 21, -1, 1), links[2].chain.first())
        assertEquals(tile(3301, 3300), path.tile(path.lastIndex))
    }

    @Test
    fun `fairy rings dial from the ring or from the portable ring`() {
        val path = mapped("fairy-rings.json", tile(3000, 3000))
        val links = (0 until path.size).mapNotNull(path::linkAt)
        assertTrue(links[0].portable)
        assertEquals("aip", links[0].fairyCode)
        assertFalse(links[1].portable)
        assertEquals(12128, links[1].objectId)
        assertEquals("Configure", links[1].action)
        assertEquals("ajr", links[1].fairyCode)
    }

    @Test
    fun `a route with a dropped teleport is walked, and only a missing route is refused`() {
        val generic = assertIs<NavRouteMapper.Mapped.Route>(NavRouteMapper.map(tile(3200, 3200), ResultsParser.parse(fixture("generic-teleport.json")))).path
        assertEquals(tile(3201, 3200), generic.tile(generic.lastIndex))
        val offGraph = assertIs<NavRouteMapper.Mapped.Route>(NavRouteMapper.map(tile(3214, 3376), ResultsParser.parse(fixture("off-graph-start.json")))).path
        assertEquals(tile(3215, 3376), offGraph.tile(offGraph.lastIndex))
        assertIs<NavRouteMapper.Mapped.Refused>(NavRouteMapper.map(tile(3000, 3000), ResultsParser.parse(fixture("not-found.json"))))
    }

    // ---- the request ----

    @Test
    fun `the request carries the profile, abilities and seed`() {
        val request = NavRequest(
            tile(3200, 3200), tile(3300, 3300, 1), listOf("coins" to 100, "varbit_39" to 1),
            SurgeOpt(true, 2, ABILITY_COOLDOWN_MS), DiveOpt(false, ABILITY_COOLDOWN_MS, 1200), 77,
        )
        val body = JsonParser.parseString(request.toJson()).asJsonObject
        assertEquals(3300, body.getAsJsonObject("goal").get("wx").asInt)
        assertEquals(1, body.getAsJsonObject("goal").get("plane").asInt)
        val requirements = body.getAsJsonObject("profile").getAsJsonArray("requirements")
        assertEquals("coins", requirements[0].asJsonObject.get("key").asString)
        assertEquals(100, requirements[0].asJsonObject.get("value").asInt)
        assertTrue(body.getAsJsonObject("options").get("only_actions").asBoolean)
        assertFalse(body.getAsJsonObject("options").get("return_geometry").asBoolean)
        assertEquals(2, body.getAsJsonObject("surge").get("charges").asInt)
        assertEquals(1200, body.getAsJsonObject("dive").get("available_in_ms").asInt)
        assertEquals(77, body.get("seed").asInt)
    }

    // ---- answers, retries and failures ----

    private class FakeTransport(vararg replies: () -> NavReply) : NavTransport {
        val queue = ArrayDeque(replies.toList())
        var calls = 0
        val urls = ArrayList<String>()
        var lastUrl: String? = null
        var lastBody: String? = null

        override fun post(url: String, body: String, timeoutMs: Long): NavReply {
            lastBody = body
            return get(url, timeoutMs)
        }

        override fun get(url: String, timeoutMs: Long): NavReply {
            calls++
            lastUrl = url
            urls += url
            return (queue.removeFirstOrNull() ?: { NavReply(200, "") })()
        }
    }

    private val request = NavRequest(Tile.of(3198, 3200, 0), Tile.of(3213, 3202, 1), emptyList(), seed = 1)

    private fun planner(transport: NavTransport, url: String? = "http://127.0.0.1:18889/") =
        NavPathClient(transport, { url }, pause = {}, log = {})

    private fun NavPathClient.plan(request: NavRequest, acceptsTeleports: Boolean) = plan(request, null, acceptsTeleports)

    @Test
    fun `a good answer becomes a route from the local navpath`() {
        val transport = FakeTransport({ NavReply(200, fixture("walk-door-stairs.json")) })
        val outcome = planner(transport).plan(request, true)
        assertIs<NavPathClient.Outcome.Planned>(outcome)
        assertEquals("http://127.0.0.1:18889/route", transport.lastUrl)
    }

    @Test
    fun `a planner still warming up is asked again, then given up on`() {
        val busyThenGood = FakeTransport({ NavReply(503, "busy") }, { NavReply(200, fixture("walk-door-stairs.json")) })
        assertIs<NavPathClient.Outcome.Planned>(planner(busyThenGood).plan(request, true))
        assertEquals(2, busyThenGood.calls)

        val alwaysBusy = FakeTransport({ NavReply(503, "busy") }, { NavReply(503, "busy") }, { NavReply(503, "busy") })
        assertIs<NavPathClient.Outcome.Failed>(planner(alwaysBusy).plan(request, true))
        assertEquals(1 + NavPathClient.BUSY_RETRIES, alwaysBusy.calls)
    }

    @Test
    fun `no endpoint says navpath isn't running and never calls`() {
        val transport = FakeTransport()
        val outcome = assertIs<NavPathClient.Outcome.Failed>(planner(transport, url = null).plan(request, true))
        assertEquals(NavPathClient.NOT_RUNNING, outcome.reason)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `a connection failure says navpath isn't running`() {
        val outcome = planner(FakeTransport({ throw IOException("refused") })).plan(request, true)
        assertTrue(assertIs<NavPathClient.Outcome.Failed>(outcome).reason.startsWith(NavPathClient.NOT_RUNNING))
    }

    @Test
    fun `a garbled answer fails with its reason, and one with a dropped step still plans`() {
        assertIs<NavPathClient.Outcome.Failed>(planner(FakeTransport({ NavReply(200, "{oops") })).plan(request, true))
        assertIs<NavPathClient.Outcome.Planned>(planner(FakeTransport({ NavReply(200, fixture("generic-teleport.json")) })).plan(request, true))
        val refused = planner(FakeTransport({ NavReply(400, "goal tile not found in snapshot") })).plan(request, true)
        assertTrue(assertIs<NavPathClient.Outcome.Failed>(refused).reason.contains("goal tile not found"))
    }

    @Test
    fun `a teleport is refused when the walk may not use one`() {
        val transport = FakeTransport({ NavReply(200, fixture("teleport-npc-item.json")) })
        val outcome = planner(transport).plan(request.copy(start = Tile.of(3000, 3000, 0)), acceptsTeleports = false)
        assertIs<NavPathClient.Outcome.Failed>(outcome)
    }

    @Test
    fun `excluded edges are sent sorted, and an empty set sends none`() {
        assertFalse(request.toJsonObject().has("exclude_macros"))
        val body = request.copy(exclude = setOf(NavMacro("object", 2), NavMacro("door", 7))).toJsonObject()
        val sent = body.getAsJsonArray("exclude_macros").map { it.asJsonObject.get("kind").asString to it.asJsonObject.get("id").asLong }
        assertEquals(listOf("door" to 7L, "object" to 2L), sent)
    }

    @Test
    fun `each link remembers the edge it came from`() {
        val answer = JsonParser.parseString(fixture("walk-door-stairs.json")).asJsonObject
        val actions = answer.getAsJsonArray("actions")
        for ((index, kind) in listOf(2 to "door", 5 to "object")) {
            actions[index].asJsonObject.add("macro", JsonObject().apply {
                addProperty("kind", kind)
                addProperty("id", 40 + index)
            })
        }
        val planned = assertIs<NavPathClient.Outcome.Planned>(planner(FakeTransport({ NavReply(200, answer.toString()) })).plan(request, true))
        assertEquals(NavMacro("door", 42), planned.route.steps[2].macro)
        val path = planned.path
        val remembered = (0 until path.size).filter { path.linkAt(it) != null }.map { path.linkAt(it)!!.kind to path.macroAt(it) }
        assertEquals(listOf(WebLinkKind.DOOR to NavMacro("door", 42), WebLinkKind.OBJECT to NavMacro("object", 45)), remembered)
    }

    // ---- routing to an area ----

    private fun JsonObject.tile(key: String): Tile = getAsJsonObject(key).let { Tile.of(it.get("wx").asInt, it.get("wy").asInt, it.get("plane").asInt) }

    private fun body(transport: FakeTransport): JsonObject = JsonParser.parseString(transport.lastBody).asJsonObject

    private fun goalArea(body: JsonObject): WebArea = body.getAsJsonObject("goal_area").let {
        WebArea(it.get("min_wx").asInt, it.get("max_wx").asInt, it.get("min_wy").asInt, it.get("max_wy").asInt, it.get("plane").asInt)
    }

    @Test
    fun `an area request carries the route body, the goal area, the goal tiles and the tie-break goal`() {
        val goals = NavGoalSet(WebArea(3201, 3209, 3196, 3204, 0), listOf(tile(3300, 3300, 1), tile(3301, 3300, 1)))
        val body = JsonParser.parseString(NavAreaRequest(request.copy(goal = tile(3205, 3200)), goals).toJson()).asJsonObject
        assertEquals(tile(3198, 3200), body.tile("start"))
        assertEquals(tile(3205, 3200), body.tile("goal"))
        assertEquals(WebArea(3201, 3209, 3196, 3204, 0), goalArea(body))
        val tiles = body.getAsJsonArray("goal_tiles").map { it.asJsonObject }
        assertEquals(listOf(3300, 3301), tiles.map { it.get("wx").asInt })
        assertEquals(listOf(1, 1), tiles.map { it.get("plane").asInt })
        assertTrue(body.getAsJsonObject("options").get("only_actions").asBoolean)
        assertEquals(1, body.get("seed").asInt)
        assertTrue(body.has("profile") && body.has("surge") && body.has("dive"))
        assertFalse(body.toString().contains("avoidDangerAreas"))

        val rectOnly = JsonParser.parseString(NavAreaRequest(request, NavGoalSet(WebArea(1, 2, 3, 4, 0), emptyList())).toJson()).asJsonObject
        assertFalse(rectOnly.has("goal_tiles"))
        val tilesOnly = JsonParser.parseString(NavAreaRequest(request, NavGoalSet(null, listOf(tile(1, 1)))).toJson()).asJsonObject
        assertFalse(tilesOnly.has("goal_area"))
    }

    @Test
    fun `an area answer gives the tile it reached`() {
        val answer = ResultsParser.parseArea(fixture("area-walk.json"))
        assertEquals(tile(3201, 3200), answer.goalReached)
        assertEquals(81, answer.goalTilesResolved)
        assertEquals(32, answer.goalTilesSearched)
        assertEquals(listOf("move", "move", "move"), answer.route.steps.map { it.type })

        val none = ResultsParser.parseArea(fixture("not-found.json"))
        assertFalse(none.route.found)
        assertNull(none.goalReached)

        assertFailsWith<ResultsParseException> { ResultsParser.parseArea(fixture("walk-door-stairs.json")) }
        assertFailsWith<ResultsParseException> { ResultsParser.parseArea("""{"found":true,"actions":[],"goal_reached":{"wx":1}}""") }
    }

    @Test
    fun `an arrive distance plans to the square around the destination, tie-broken towards it`() {
        val destination = tile(3205, 3200)
        val goals = WalkGoal.Near(destination, 4).goalSet(request.start)
        val transport = FakeTransport({ NavReply(200, fixture("area-walk.json")) })
        val outcome = planner(transport).plan(request.copy(goal = destination), goals, true)
        val path = assertIs<NavPathClient.Outcome.Planned>(outcome).path
        assertEquals("http://127.0.0.1:18889/route_area", transport.lastUrl)
        val body = body(transport)
        assertEquals(WebArea(3201, 3209, 3196, 3204, 0), goalArea(body))
        assertEquals(destination, body.tile("goal"))
        assertFalse(body.has("goal_tiles"))
        assertEquals(tile(3198, 3200), path.tile(0))
        assertEquals(tile(3201, 3200), path.tile(path.lastIndex))
    }

    @Test
    fun `no arrive distance stays on the single-tile route`() {
        assertNull(WalkGoal.Near(tile(3213, 3202, 1), 0).goalSet(request.start))
        val transport = FakeTransport({ NavReply(200, fixture("walk-door-stairs.json")) })
        assertIs<NavPathClient.Outcome.Planned>(planner(transport).plan(request, null, true))
        assertEquals("http://127.0.0.1:18889/route", transport.lastUrl)
        assertFalse(body(transport).has("goal_area"))
    }

    @Test
    fun `area planning falls back like single-tile planning`() {
        val goals = WalkGoal.Near(tile(3205, 3200), 4).goalSet(request.start)
        val alwaysBusy = FakeTransport({ NavReply(503, "busy") }, { NavReply(503, "busy") }, { NavReply(503, "busy") })
        assertIs<NavPathClient.Outcome.Failed>(planner(alwaysBusy).plan(request, goals, true))
        assertEquals(1 + NavPathClient.BUSY_RETRIES, alwaysBusy.calls)

        val busyThenGood = FakeTransport({ NavReply(503, "busy") }, { NavReply(200, fixture("area-walk.json")) })
        assertIs<NavPathClient.Outcome.Planned>(planner(busyThenGood).plan(request, goals, true))

        assertIs<NavPathClient.Outcome.Failed>(planner(FakeTransport({ throw IOException("reset") })).plan(request, goals, true))
        assertIs<NavPathClient.Outcome.Failed>(planner(FakeTransport({ NavReply(400, "goal_area too large") })).plan(request, goals, true))
        assertIs<NavPathClient.Outcome.Failed>(planner(FakeTransport({ NavReply(200, fixture("not-found.json")) })).plan(request, goals, true))
        assertIs<NavPathClient.Outcome.Failed>(planner(FakeTransport()).plan(request, goals, true))

        val elsewhere = NavGoalSet(WebArea(3300, 3310, 3300, 3310, 0), emptyList())
        assertIs<NavPathClient.Outcome.Failed>(planner(FakeTransport({ NavReply(200, fixture("area-walk.json")) })).plan(request, elsewhere, true))
    }

    @Test
    fun `area failures are answered one call each, then planning works again`() {
        val goals = WalkGoal.Near(tile(3205, 3200), 4).goalSet(request.start)
        val transport = FakeTransport({ throw IOException("reset") }, { NavReply(502, "") }, { NavReply(200, fixture("area-walk.json")) })
        val p = planner(transport)
        repeat(2) { assertIs<NavPathClient.Outcome.Failed>(p.plan(request, goals, true)) }
        assertIs<NavPathClient.Outcome.Planned>(p.plan(request, goals, true))
        assertEquals(3, transport.calls)
    }

    @Test
    fun `the square is clipped to fit the planner area limit and stays centred`() {
        val destination = tile(3200, 3200, 2)
        val wide = assertNotNull(WalkGoal.Near(destination, 100).goalSet(destination)?.area)
        assertEquals(WebArea(3169, 3231, 3169, 3231, 2), wide)
        assertTrue(wide.maxX - wide.minX + 1 <= 64 && wide.maxY - wide.minY + 1 <= 64)
        assertEquals(wide, WalkGoal.Near(destination, WalkGoal.MAX_RADIUS).goalSet(destination)?.area)
        assertEquals(WebArea(3198, 3202, 3198, 3202, 2), WalkGoal.Near(destination, 2).goalSet(destination)?.area)
    }

    @Test
    fun `arrival counts within the distance on the destination plane, and anywhere in a tile set`() {
        val near = WalkGoal.Near(tile(3200, 3200), 2)
        assertTrue(near.contains(tile(3202, 3198)))
        assertFalse(near.contains(tile(3203, 3200)))
        assertFalse(near.contains(tile(3200, 3200, 1)))
        assertEquals(2, near.marginAt(tile(3200, 3200)))
        assertEquals(0, near.marginAt(tile(3202, 3200)))

        val set = WalkGoal.of(Area.Rectangular(3210, 3210, 3212, 3215, 1))
        assertTrue(set.contains(tile(3212, 3215, 1)))
        assertFalse(set.contains(tile(3212, 3215, 0)))
        assertEquals(0, set.marginAt(tile(3211, 3212, 1)))
        assertEquals(WebArea(3210, 3212, 3210, 3215, 1), set.goalSet(tile(0, 0)).area)

        val tiles = WalkGoal.tiles(listOf(tile(3000, 3000), tile(3100, 3100, 1)))
        assertTrue(tiles.contains(tile(3100, 3100, 1)))
        assertFalse(tiles.contains(tile(3001, 3000)))
        assertEquals(2, tiles.goalSet(tile(0, 0)).tiles.size)
        assertTrue(WalkGoal.tiles(emptyList()).isEmpty)
    }

    @Test
    fun `a set over the planner limit is thinned to the edge it can be walked into by`() {
        val big = WalkGoal.rectangle(3000, 3000, 3099, 3099, 0)
        val goals = big.goalSet(tile(2900, 3050))
        assertNull(goals.area)
        assertEquals(396, goals.tiles.size)
        assertTrue(goals.tiles.all { it.x == 3000 || it.x == 3099 || it.y == 3000 || it.y == 3099 })
        assertTrue(tile(3050, 3050) in big.goalSet(tile(3050, 3050)).tiles)

        val huge = WalkGoal.rectangle(0, 0, 1199, 1199, 0).goalSet(tile(0, 0))
        assertEquals(NavGoalSet.MAX_TILES, huge.tiles.size)
        assertTrue(huge.tiles.all { it.x == 0 || it.y == 0 || it.x == 1199 || it.y == 1199 })
    }

    @Test
    fun `the local fallback tries the nearest standable tiles, spread apart`() {
        val goal = WalkGoal.rectangle(3010, 3000, 3020, 3010, 0)
        val candidates = goal.candidates(tile(3000, 3005)) { it.x != 3010 }
        assertEquals(tile(3011, 3005), candidates.first())
        assertTrue(candidates.size in 2..4)
        for (i in candidates.indices) for (j in i + 1 until candidates.size) {
            assertTrue(maxOf(abs(candidates[i].x - candidates[j].x), abs(candidates[i].y - candidates[j].y)) >= 4)
        }
        assertTrue(goal.candidates(tile(3000, 3005)) { false }.isEmpty())
    }

    // ---- drop parity: parser and mapper ----

    private fun route(vararg actions: String) = """{"found":true,"cost":1.0,"length_tiles":1,"actions":[${actions.joinToString(",")}]}"""

    @Test
    fun `a planned hop that starts off the last tile walks there first, leaving no gap`() {
        val json = route(
            """{"type":"move","to":[3200,3200,0]}""",
            """{"type":"surge","from":[3202,3200,0],"to":[3212,3200,0],"tiles_covered":10}""",
        )
        val result = NavRouteMapper.map(tile(3199, 3200), ResultsParser.parse(json))
        val path = assertIs<NavRouteMapper.Mapped.Route>(result).path
        val tiles = (0 until path.size).map { path.tile(it) }
        assertTrue(tile(3202, 3200) in tiles)
        assertEquals(tile(3212, 3200), tiles.last())
        assertTrue(tiles.zipWithNext().all { (a, b) -> a.getLongestDelta(b) <= 1 })
    }

    @Test
    fun `an interface click with a negative click id is refused`() {
        val json = route(
            """{"type":"ifslot","from":{"min":[3200,3200,0],"max":[3200,3200,0]},"to":{"min":[3300,3300,0],"max":[3300,3300,0]},
               "metadata":{"kind":"ifslot","steps":[{"kind":"ifslot","db_row":{"interface_id":1092,"component_id":21,"slot_id":-1,"click_id":-1}}]}}""",
        )
        assertIs<NavRouteMapper.Mapped.Refused>(NavRouteMapper.map(tile(3200, 3200), ResultsParser.parse(json)))
    }

    @Test
    fun `a child chain's interface click keeps its destination rectangle`() {
        val json = route(
            """{"type":"npc","from":{"min":[3200,3200,0],"max":[3200,3200,0]},"to":{"min":[2954,3146,0],"max":[2954,3146,0]},
               "metadata":{"kind":"npc","steps":[{"kind":"npc","db_row":{"npc_id":376,"action":"Travel",
               "child_chain":[{"type":"ifslot","meta":{"interface_id":1188,"component_id":8,"slot_id":-1,"click_id":0,"dest_rect":[2952,3144,2956,3148],"plane":0}}]}}]}}""",
        )
        val npc = assertIs<Step.Npc>(ResultsParser.parse(json).steps.single())
        val click = assertIs<Step.IFSlot>(npc.chain.first())
        assertEquals(NavArea(2952, 3144, 2956, 3148, 0), click.destinationArea)
    }

    @Test
    fun `a door row kept as columns still gives its two sides`() {
        val json = route(
            """{"type":"door","from":{"min":[3200,3200,0],"max":[3200,3200,0]},"to":{"min":[3201,3200,0],"max":[3201,3200,0]},
               "metadata":{"kind":"door","steps":[{"kind":"door","db_row":{"direction":"IN","real_id_closed":1530,
               "tile_inside_x":3201,"tile_inside_y":3200,"tile_inside_plane":0,"tile_outside_x":3200,"tile_outside_y":3200,"tile_outside_plane":0}}]}}""",
        )
        val door = assertIs<Step.Door>(ResultsParser.parse(json).steps.single())
        assertEquals(tile(3201, 3200), door.tileInside)
        assertEquals(tile(3200, 3200), door.tileOutside)
    }

    @Test
    fun `an area given only by its max corner still gives a tile`() {
        val json = route(
            """{"type":"object","from":{"max":[3212,3200,0]},"to":{"min":[3212,3201,1],"max":[3212,3201,1]},"id":24350,"action":"Climb-up"}""",
        )
        val stairs = assertIs<Step.Loc>(ResultsParser.parse(json).steps.single())
        assertEquals(tile(3212, 3200), stairs.from)
    }

    // ---- spell teleports ----

    @Test
    fun `a spell step gives its ability id, its landing box and its destination tile`() {
        val route = ResultsParser.parse(fixture("spell-teleport.json"))
        assertEquals(listOf("spell", "move", "move"), route.steps.map { it.type })
        val spell = assertIs<Step.Spell>(route.steps[0])
        assertEquals(57, spell.spell)
        assertEquals(NavArea(2881, 3666, 2884, 3670, 0), spell.destinationArea)
        assertEquals(tile(3222, 3218), spell.from)
        assertEquals(tile(2882, 3668), spell.to)
        assertTrue(spell.chain.isEmpty())
    }

    @Test
    fun `a spell chained to an interface click keeps the click and lands in its box`() {
        val spell = assertIs<Step.Spell>(ResultsParser.parse(fixture("spell-ifslot-chain.json")).steps[0])
        assertEquals(205, spell.spell)
        val click = assertIs<Step.IFSlot>(spell.chain.single())
        assertEquals(720, click.interfaceId)
        assertEquals(17, click.component)
        assertEquals(NavArea(3294, 10127, 3294, 10127, 0), spell.destinationArea)
    }

    @Test
    fun `a spell id given on the action itself is read too`() {
        val json = route("""{"type":"spell","spell_id":28,"from":{"min":[3000,3000,0],"max":[3000,3000,0]},"to":{"min":[3212,3424,0],"max":[3212,3424,0]}}""")
        val spell = assertIs<Step.Spell>(ResultsParser.parse(json).steps.single())
        assertEquals(28, spell.spell)
        assertEquals(NavArea(3212, 3424, 3212, 3424, 0), spell.destinationArea)
        assertFailsWith<ResultsParseException> { ResultsParser.parse(route("""{"type":"spell","to":[3212,3424,0]}""")) }
    }

    @Test
    fun `a spell becomes a global spell link landing in its box`() {
        val path = mapped("spell-teleport.json", tile(3222, 3218))
        val link = assertNotNull(path.linkAt(1))
        assertEquals(WebLinkKind.SPELL, link.kind)
        assertEquals(TeleportSpell.TROLLHEIM, link.spell)
        assertTrue(link.isGlobal)
        assertEquals(WebArea(2881, 2884, 3666, 3670, 0), link.to)
        assertEquals(tile(2882, 3668), path.tile(1))
        assertEquals(tile(2884, 3670), path.tile(path.lastIndex))
    }

    @Test
    fun `a spell chained to an interface click carries the click`() {
        val path = mapped("spell-ifslot-chain.json", tile(3222, 3218))
        val link = assertNotNull(path.linkAt(1))
        assertEquals(TeleportSpell.WARS_RETREAT, link.spell)
        assertEquals(WebInterfaceStep(720, 17, -1, 0), link.chain.single())
        assertTrue(link.to.contains(3292, 10125, 0))
    }

    @Test
    fun `a spell this engine does not know is left out, and a walk without teleports refuses any spell`() {
        val path = assertIs<NavRouteMapper.Mapped.Route>(NavRouteMapper.map(tile(3222, 3218), ResultsParser.parse(fixture("spell-unknown.json")))).path
        assertEquals(0, path.linkCount)
        val transport = FakeTransport({ NavReply(200, fixture("spell-teleport.json")) })
        assertIs<NavPathClient.Outcome.Failed>(planner(transport).plan(request, false))
    }

    @Test
    fun `every teleport spell is a spell key`() {
        assertEquals("spell_57", RequirementsBuilder.spellKey(TeleportSpell.TROLLHEIM))
        for (spell in TeleportSpell.entries) assertEquals(RequirementsBuilder.Block.SPELL, RequirementsBuilder.blockOf(RequirementsBuilder.spellKey(spell)))
    }

    @Test
    fun `the spell table matches the planner's spell rows`() {
        val plannerIds = setOf(
            57, 205, 19, 207, 28, 260, 31, 34, 39, 45, 50, 170, 191, 253, 254, 255, 203, 111, 113, 116, 117, 121, 123, 127,
            251, 252, 137, 140, 148, 83, 86, 89, 92, 95, 98, 101, 104, 143, 200, 201, 202,
        )
        assertEquals(plannerIds, TeleportSpell.entries.map { it.abilityId }.toSet())
        assertEquals(TeleportSpell.entries.size, plannerIds.size)
        assertEquals(TeleportSpell.CAMELOT, TeleportSpell.byAbilityId(39))
        assertNull(TeleportSpell.byAbilityId(9999))
    }
    // ---- the rest of navpath's endpoints ----

    @Test
    fun `an area route on a navpath without route_area asks for the goal tile, and keeps doing so`() {
        val goals = WalkGoal.Near(tile(3213, 3202, 1), 2).goalSet(request.start)
        val transport = FakeTransport({ NavReply(404, "") }, { NavReply(200, fixture("walk-door-stairs.json")) }, { NavReply(200, fixture("walk-door-stairs.json")) })
        val client = planner(transport)
        assertIs<NavPathClient.Outcome.Planned>(client.plan(request, goals, true))
        assertTrue(client.routeAreaMissing)
        assertIs<NavPathClient.Outcome.Planned>(client.plan(request, goals, true))
        assertEquals(listOf("/route_area", "/route", "/route"), transport.urls.map { it.removePrefix("http://127.0.0.1:18889") })
    }

    @Test
    fun `tile exists and reachable read their answers, and an unreachable navpath throws`() {
        val transport = FakeTransport(
            { NavReply(200, "true") }, { NavReply(200, """{"exists":false}""") }, { NavReply(200, """{"reachable":false,"reason":"not_connected"}""") },
        )
        val client = planner(transport)
        assertTrue(client.tileExists(tile(3200, 3200)))
        assertEquals("http://127.0.0.1:18889/tile/exists?x=3200&y=3200&plane=0", transport.lastUrl)
        assertFalse(client.tileExists(tile(3200, 3201)))
        assertEquals(ReachableResult(false, "not_connected"), client.reachable(tile(1, 2), tile(3, 4, 1)))
        assertEquals("http://127.0.0.1:18889/reachable?sx=1&sy=2&splane=0&gx=3&gy=4&gplane=1", transport.lastUrl)
        assertEquals(ReachableResult.YES, NavPathClient.parseReachable(" true "))
        assertEquals(ReachableResult(false, ReachableResult.UNPARSEABLE), NavPathClient.parseReachable("{oops"))
        assertFailsWith<NavPathUnavailableException> { planner(FakeTransport({ throw IOException("refused") })).tileExists(tile(1, 1)) }
        assertFailsWith<NavPathUnavailableException> { planner(FakeTransport(), url = null).reachable(tile(1, 1), tile(2, 2)) }
    }

    @Test
    fun `the seed is stable for a name and never zero`() {
        assertEquals(NavPathClient.seedFromName("Zezima"), NavPathClient.seedFromName("Zezima"))
        assertTrue(NavPathClient.seedFromName("Zezima") > 0)
        assertTrue(NavPathClient.seedFromName("Zezima") != NavPathClient.seedFromName("Zezimb"))
    }

    @Test
    fun `geometry is asked for when hops are placed by the walk`() {
        val options = NavOptions()
        assertFalse(options.wantsHopWalks)
        options.surge = SurgeOpt(true, 1, ABILITY_COOLDOWN_MS)
        assertTrue(options.wantsHopWalks)
        options.clientPlacedAbilities = false
        assertFalse(options.wantsHopWalks)
        val body = request.copy(returnGeometry = true, onlyActions = false).toJsonObject().getAsJsonObject("options")
        assertTrue(body.get("return_geometry").asBoolean)
        assertFalse(body.has("only_actions"))
    }

    @Test
    fun `options copy field for field`() {
        val options = NavOptions(tile(1, 1), tile(2, 2)).apply {
            useItems = false
            hops = false
            teleports = false
            seed = 9
            dive = DiveOpt(true, 100, 5)
        }
        val copy = options.copy()
        assertEquals(listOf(tile(1, 1), tile(2, 2), false, false, false, 9, DiveOpt(true, 100, 5)),
            listOf(copy.start, copy.goal, copy.useItems, copy.hops, copy.teleports, copy.seed, copy.dive))
        copy.useItems = true
        assertFalse(options.useItems)
    }

    // ---- Java's step fields ----

    @Test
    fun `a door row gives its open and closed locations`() {
        val door = assertIs<Step.Door>(ResultsParser.parse(fixture("walk-door-stairs.json")).steps[2])
        assertEquals(tile(3201, 3201), door.info?.locationOpen)
        assertEquals(tile(3201, 3200), door.info?.locationClosed)
        assertEquals(1531, door.realIdOpen)
        assertEquals("Open", door.openAction)
    }

    @Test
    fun `an npc gives its first interface and origin, an object its origin from its from area`() {
        val route = ResultsParser.parse(fixture("teleport-npc-item.json"))
        val npc = assertIs<Step.Npc>(route.steps[2])
        assertEquals(NavArea(3213, 3375, 3217, 3379, 0), npc.originArea)
        val child = route(
            """{"type":"npc","to":[2954,3146,0],"metadata":{"db_row":{"npc_id":376,"action":"Travel",
               "child_chain":[{"type":"ifslot","meta":{"interface_id":1188,"component_id":8,"slot_id":-1,"click_id":0}}]}}}""",
        )
        assertEquals(1188, assertIs<Step.Npc>(ResultsParser.parse(child).steps.single()).firstInterfaceId)
        val stairs = assertIs<Step.Loc>(ResultsParser.parse(fixture("walk-door-stairs.json")).steps[5])
        assertEquals(NavArea(3212, 3200, 3212, 3200, 0), stairs.originArea)
    }

    @Test
    fun `an item clicked by its option number alone becomes a link firing that op`() {
        val json = route(
            """{"type":"item","from":{"min":[3000,3000,0],"max":[3000,3000,0]},"to":{"min":[3100,3100,0],"max":[3100,3100,0]},"id":1234,"option_num":3}""",
        )
        val item = assertIs<Step.ItemClick>(ResultsParser.parse(json).steps.single())
        assertNull(item.option)
        assertEquals(3, item.optionNum)
        val path = assertIs<NavRouteMapper.Mapped.Route>(NavRouteMapper.map(tile(3000, 3000), ResultsParser.parse(json))).path
        val link = assertNotNull(path.linkAt(path.lastIndex))
        assertEquals(3, link.optionNumber)
        assertEquals(1234, link.target?.id)
    }

    @Test
    fun `tile exact keeps its minimap flag`() {
        val json = route("""{"type":"tile_exact","to":[3200,3200,0],"minimap":true}""")
        assertEquals(true, assertIs<Step.TileExact>(ResultsParser.parse(json).steps.single()).minimap)
    }

    @Test
    fun `every chain child Java maps becomes a step of the link`() {
        val json = route(
            """{"type":"object","id":100,"action":"Enter","from":{"min":[3000,3000,0],"max":[3000,3000,0]},"to":{"min":[3100,3100,0],"max":[3100,3100,0]},
               "chain":[{"type":"npc","id":7,"action":"Talk-to","to":[3100,3100,0]},{"type":"tile_exact","to":[3101,3100,0],"minimap":true},
                        {"type":"spell","spell":57,"to":[2882,3668,0]},{"type":"spell","spell":99999,"to":[1,1,0]},
                        {"type":"use_on","item_id":954,"object_id":2622,"to":[3100,3101,0]}]}""",
        )
        val path = assertIs<NavRouteMapper.Mapped.Route>(NavRouteMapper.map(tile(3000, 3000), ResultsParser.parse(json))).path
        val chain = assertNotNull(path.linkAt(path.lastIndex)).chain
        assertEquals(4, chain.size)
        assertEquals(WebLinkKind.NPC, assertIs<WebNestedStep>(chain[0]).link.kind)
        assertEquals(WebWalkStep(tile(3101, 3100), true), chain[1])
        assertEquals(TeleportSpell.TROLLHEIM, assertIs<WebNestedStep>(chain[2]).link.spell)
        assertEquals(WebLinkKind.USE_ON, assertIs<WebNestedStep>(chain[3]).link.kind)
    }

    @Test
    fun `a spell's book action is kept, a blank one is the plain cast`() {
        val json = route("""{"type":"spell","to":{"min":[3561,1486,0],"max":[3564,1490,0]},"metadata":{"db_row":{"spell_id":260,"action":"Marigold Farm"}}}""")
        assertEquals("Marigold Farm", assertIs<Step.Spell>(ResultsParser.parse(json).steps.single()).action)
        val blank = route("""{"type":"spell","to":[1,1,0],"metadata":{"db_row":{"spell_id":260,"action":""}}}""")
        assertNull(assertIs<Step.Spell>(ResultsParser.parse(blank).steps.single()).action)
    }
}
