package com.projectx.traversal.pathing

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A planned Dive is walked along the route's geometry, not the straight line: the line runs against a fence the dive
 * clears, while the geometry walks round it.
 */
class HopWalkGeometryTest {

    private val actions = """
        {"type":"move","to":[3335,3381,0]},{"type":"move","to":[3335,3382,0]},
        {"type":"move","to":[3334,3383,0]},
        {"type":"dive","from":[3334,3383,0],"to":[3324,3390,0],"cost_ms":600,"tiles_covered":16},
        {"type":"move","to":[3323,3390,0]}
    """.trimIndent()

    private val geometry = """
        [3335,3380,0],[3335,3381,0],[3335,3382,0],[3334,3383,0],[3333,3384,0],[3333,3385,0],
        [3333,3386,0],[3333,3387,0],[3333,3388,0],[3333,3389,0],[3333,3390,0],[3332,3390,0],
        [3331,3390,0],[3330,3390,0],[3329,3390,0],[3328,3390,0],[3327,3390,0],[3326,3390,0],
        [3325,3390,0],[3324,3390,0],[3323,3390,0]
    """.trimIndent()

    private fun answer(geometry: String?) =
        """{"found":true,"cost":9300.0,"actions":[$actions]""" + (geometry?.let { ""","geometry":[$it]""" } ?: "") + "}"

    private fun dive(json: String): Step.Dive = ResultsParser.parse(json).steps.filterIsInstance<Step.Dive>().single()

    private fun walk(json: String): List<Tile> {
        val path = assertIs<NavRouteMapper.Mapped.Route>(NavRouteMapper.map(Tile.of(3335, 3380, 0), ResultsParser.parse(json))).path
        return path.tiles
    }

    @Test
    fun `the dive carries the geometry's walk from after its start to its landing`() {
        val dive = dive(answer(geometry))
        assertEquals(16, dive.walked.size)
        assertEquals(Tile.of(3333, 3384, 0), dive.walked.first())
        assertEquals(Tile.of(3324, 3390, 0), dive.walked.last())
    }

    @Test
    fun `the walk goes round the fence one step at a time`() {
        val walk = walk(answer(geometry))
        assertFalse(Tile.of(3326, 3389, 0) in walk || Tile.of(3327, 3388, 0) in walk, "no tile behind the fence: $walk")
        assertTrue(Tile.of(3333, 3390, 0) in walk)
        assertTrue(walk.zipWithNext().all { (a, b) -> a.getLongestDelta(b) == 1 }, "stepped: $walk")
    }

    @Test
    fun `without geometry the dive is the straight line`() {
        assertTrue(Tile.of(3326, 3389, 0) in walk(answer(null)))
    }

    @Test
    fun `a jump inside the hop's slice leaves the dive without a walk`() {
        assertTrue(dive(answer(geometry.replace("[3329,3390,0]", "[3000,3000,0]"))).walked.isEmpty())
    }
}
