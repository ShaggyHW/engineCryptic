package com.projectx.pathfinder.navpath

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.projectx.webwalker.WebInterfaceStep
import com.projectx.webwalker.WebLink
import com.projectx.webwalker.WebLinkKind
import com.projectx.webwalker.WebLinks
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Answers recorded from navpath on the merged map, each through a row only links.json has; it must map back onto that link. */
class NavpathMergedMapTest {

    private fun fixture(name: String): String =
        NavpathMergedMapTest::class.java.getResourceAsStream("/navpath/$name")!!.use { it.reader().readText() }

    private fun onlyLink(name: String, start: Tile): WebLink {
        val result = NavRouteMapper.map(start, NavResultsParser.parse(fixture(name)))
        val path = assertIs<NavRouteMapper.Mapped.Route>(result, result.toString()).path
        return (0 until path.size).mapNotNull(path::linkAt).single()
    }

    private fun ours(mapped: WebLink, landing: Tile): WebLink = assertNotNull(
        WebLinks.all.singleOrNull {
            it.kind == mapped.kind && it.objectId == mapped.objectId && (it.kind == WebLinkKind.INTERFACE || it.action == mapped.action) &&
                it.target == mapped.target && it.chain == mapped.chain && it.to.contains(landing.x, landing.y, landing.plane) &&
                (it.isGlobal || it.from.contains(mapped.from.minX, mapped.from.minY, mapped.from.plane))
        },
        "no link of ours is $mapped",
    )

    @Test
    fun `an object only links json has maps back onto it`() {
        val link = onlyLink("merged-map-object.json", Tile.of(2745, 3152, 0))
        assertEquals(WebLinkKind.OBJECT, link.kind)
        assertEquals(5083, link.objectId)
        assertEquals("Enter", link.action)
        ours(link, Tile.of(2710, 9564, 0))
    }

    @Test
    fun `a ladder only links json has maps back onto it`() {
        val link = onlyLink("merged-map-ladder.json", Tile.of(2984, 3340, 2))
        assertEquals(35783, link.objectId)
        assertEquals("Climb-down", link.action)
        assertEquals(1, link.to.plane)
        ours(link, Tile.of(2984, 3340, 1))
    }

    @Test
    fun `an interface teleport only links json has maps back onto it`() {
        val link = onlyLink("merged-map-interface.json", Tile.of(3222, 3218, 0))
        assertEquals(WebLinkKind.INTERFACE, link.kind)
        assertEquals(listOf(WebInterfaceStep(1461, 1, 205, 1)), link.chain)
        ours(link, Tile.of(3294, 10127, 0))
    }

    @Test
    fun `an item teleport and its chained click map back onto ours`() {
        val link = onlyLink("merged-map-item-chain.json", Tile.of(3222, 3218, 0))
        assertEquals(WebLinkKind.ITEM, link.kind)
        assertEquals(22332, link.target?.id)
        assertEquals("Activate", link.action)
        assertEquals(listOf(WebInterfaceStep(1153, 24, -1, 3)), link.chain)
        ours(link, Tile.of(3128, 3407, 0))
    }

    @Test
    fun `an object step our measured link answers by its prompt maps onto ours, choice and all`() {
        val ours = assertNotNull(WebLinks.all.firstOrNull { it.kind == WebLinkKind.OBJECT && it.choice != null })
        val landing = Tile.of(ours.to.centreX, ours.to.centreY, ours.to.plane)
        val answer = JsonParser.parseString(fixture("merged-map-object.json")).asJsonObject
        val action = answer.getAsJsonArray("actions")[0].asJsonObject
        val corner = JsonArray().apply { add(landing.x); add(landing.y); add(landing.plane) }
        action.getAsJsonObject("to").apply { add("min", corner); add("max", corner) }
        action.getAsJsonObject("metadata").getAsJsonArray("steps")[0].asJsonObject.getAsJsonObject("db_row").apply {
            addProperty("object_id", ours.objectId)
            addProperty("action", ours.action)
            addProperty("dest_min_x", landing.x)
            addProperty("dest_max_x", landing.x)
            addProperty("dest_min_y", landing.y)
            addProperty("dest_max_y", landing.y)
            addProperty("dest_plane", landing.plane)
        }
        val start = Tile.of(ours.from.centreX, ours.from.centreY, ours.from.plane)
        val path = assertIs<NavRouteMapper.Mapped.Route>(NavRouteMapper.map(start, NavResultsParser.parse(answer.toString()))).path
        val link = (0 until path.size).mapNotNull(path::linkAt).single()
        assertEquals(ours.choice, link.choice)
    }
}
