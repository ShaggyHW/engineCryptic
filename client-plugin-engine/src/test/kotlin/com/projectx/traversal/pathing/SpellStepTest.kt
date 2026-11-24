package com.projectx.traversal.pathing

import com.projectx.script.api.TeleportSpell
import com.projectx.webwalker.WebLink
import com.projectx.webwalker.WebLinkKind
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** navpath's `spell` step in both of its shapes, its book action, and its requirement key. */
class SpellStepTest {

    private val trollheimDb = """
        {"found":true,"cost":3150.0,"actions":[
         {"type":"spell","from":{"min":[3200,3200,0],"max":[3200,3200,0]},
          "to":{"min":[2881,3666,0],"max":[2884,3670,0]},"cost_ms":3150.0,
          "metadata":{"steps":[
           {"kind":"spell","db_row":{"spell_id":57,"name":"Trollheim Teleport","dest_min_x":2881,"dest_max_x":2884,
            "dest_min_y":3666,"dest_max_y":3670,"dest_plane":0}}],
           "db_row":{"spell_id":57,"name":"Trollheim Teleport","dest_min_x":2881,"dest_max_x":2884,
            "dest_min_y":3666,"dest_max_y":3670,"dest_plane":0}}}]}
    """.trimIndent()

    private val moonclanFlat = """
        {"found":true,"actions":[
         {"type":"spell","spell":111,"name":"Moonclan Teleport",
          "to":{"min":[2112,3913,0],"max":[2116,3917,0]}}]}
    """.trimIndent()

    private val serviceRoute = """
        {"found":true,"cost":7422.7925,"length_tiles":15,"duration_ms":0,"duration_us":71,"actions":[
         {"type":"spell","from":{"min":[3222,3218,0],"max":[3222,3218,0]},"to":{"min":[2882,3668,0],"max":[2882,3668,0]},
          "cost_ms":3150.0,"metadata":{"cost_ms":3150.0,
           "db_row":{"cost":3150.0,"dest_max_x":2884,"dest_max_y":3670,"dest_min_x":2881,"dest_min_y":3666,"dest_plane":0,
            "next_node_id":null,"next_node_type":null,"requirements":"304","spell_id":57},
           "dst":506240,"requirements":[304],
           "steps":[{"cost_ms":3150.0,"db_row":{"cost":3150.0,"dest_max_x":2884,"dest_max_y":3670,"dest_min_x":2881,
            "dest_min_y":3666,"dest_plane":0,"next_node_id":null,"next_node_type":null,"requirements":"304","spell_id":57},
            "id":1,"kind":"spell"}]}},
         {"type":"move","to":[2883,3668,0],"cost_ms":300.0},{"type":"move","to":[2884,3669,0],"cost_ms":424.0},
         {"type":"move","to":[2885,3669,0],"cost_ms":300.0},{"type":"move","to":[2886,3669,0],"cost_ms":300.0},
         {"type":"move","to":[2887,3669,0],"cost_ms":300.0},{"type":"move","to":[2888,3670,0],"cost_ms":424.0},
         {"type":"move","to":[2889,3671,0],"cost_ms":424.0},{"type":"move","to":[2890,3671,0],"cost_ms":300.0},
         {"type":"move","to":[2890,3672,0],"cost_ms":300.0},{"type":"move","to":[2890,3673,0],"cost_ms":300.0},
         {"type":"move","to":[2890,3674,0],"cost_ms":300.0},{"type":"move","to":[2890,3675,0],"cost_ms":300.0},
         {"type":"move","to":[2890,3676,0],"cost_ms":300.0}]}
    """.trimIndent()

    private val marigoldDb = """
        {"found":true,"actions":[
         {"type":"spell","to":{"min":[3561,1486,0],"max":[3564,1490,0]},
          "metadata":{"db_row":{"spell_id":260,"action":"Marigold Farm","dest_min_x":3561,"dest_max_x":3564,
            "dest_min_y":1486,"dest_max_y":1490,"dest_plane":0,"requirements":"309;383"}}}]}
    """.trimIndent()

    private val wendlewickBlank = """
        {"found":true,"actions":[
         {"type":"spell","metadata":{"db_row":{"spell_id":260,"action":"","dest_min_x":3479,"dest_max_x":3483,
            "dest_min_y":1553,"dest_max_y":1555,"dest_plane":0}}}]}
    """.trimIndent()

    private val serviceMarigold = """
        {"found":true,"actions":[{"type":"spell","from":{"min":[3164,3465,0],"max":[3164,3465,0]},"to":{"min":[3562,1488,0],"max":[3562,1488,0]},"cost_ms":5800.0,"metadata":{"reason":"start_coordinate_not_found","teleport":{"cost_ms":2800.0,"db_row":{"action":"Marigold Farm","cost":2800.0,"dest_max_x":3564,"dest_max_y":1490,"dest_min_x":3561,"dest_min_y":1486,"dest_plane":0,"next_node_id":null,"next_node_type":null,"requirements":"309;383","spell_id":260},"dst":30159,"requirements":[309,383],"steps":[{"cost_ms":2800.0,"db_row":{"action":"Marigold Farm","cost":2800.0,"dest_max_x":3564,"dest_max_y":1490,"dest_min_x":3561,"dest_min_y":1486,"dest_plane":0,"next_node_id":null,"next_node_type":null,"requirements":"309;383","spell_id":260},"id":42,"kind":"spell"}]}}}]}
    """.trimIndent()

    private val serviceCast = """
        {"found":true,"actions":[{"type":"spell","from":{"min":[3164,3465,0],"max":[3164,3465,0]},"to":{"min":[3481,1554,0],"max":[3481,1554,0]},"cost_ms":5750.0,"metadata":{"reason":"start_coordinate_not_found","teleport":{"cost_ms":2750.0,"db_row":{"action":"Cast","cost":2750.0,"dest_max_x":3483,"dest_max_y":1555,"dest_min_x":3479,"dest_min_y":1553,"dest_plane":0,"next_node_id":null,"next_node_type":null,"requirements":"309","spell_id":260},"dst":47545,"requirements":[309],"steps":[{"cost_ms":2750.0,"db_row":{"action":"Cast","cost":2750.0,"dest_max_x":3483,"dest_max_y":1555,"dest_min_x":3479,"dest_min_y":1553,"dest_plane":0,"next_node_id":null,"next_node_type":null,"requirements":"309","spell_id":260},"id":6,"kind":"spell"}]}}}]}
    """.trimIndent()

    private val unknown = """{"found":true,"actions":[{"type":"spell","spell":99999,"to":{"min":[1,1,0],"max":[1,1,0]}}]}"""

    private fun spell(json: String): Step.Spell = assertIs<Step.Spell>(ResultsParser.parse(json).steps.first())

    private fun mapped(json: String): WebLink? {
        val path = assertIs<NavRouteMapper.Mapped.Route>(NavRouteMapper.map(Tile.of(3200, 3200, 0), ResultsParser.parse(json))).path
        return (0 until path.size).firstNotNullOfOrNull(path::linkAt)
    }

    @Test
    fun `the db_row shape parses to a spell with its box and no copy of itself in its chain`() {
        val spell = spell(trollheimDb)
        assertEquals(57, spell.spell)
        assertEquals(2881, spell.destinationArea?.minX)
        assertEquals(3670, spell.destinationArea?.maxY)
        assertTrue(spell.chain.none { it is Step.Spell })
        assertNull(spell.action)
        val link = assertNotNull(mapped(trollheimDb))
        assertEquals(WebLinkKind.SPELL, link.kind)
        assertEquals(TeleportSpell.TROLLHEIM, link.spell)
    }

    @Test
    fun `the flat shape parses`() {
        val spell = spell(moonclanFlat)
        assertEquals(111, spell.spell)
        assertNotNull(spell.destinationArea)
        assertEquals(TeleportSpell.byAbilityId(111), mapped(moonclanFlat)?.spell)
    }

    @Test
    fun `a service route is the spell and then its moves`() {
        val route = ResultsParser.parse(serviceRoute)
        assertEquals(14, route.steps.size)
        val spell = assertIs<Step.Spell>(route.steps[0])
        assertEquals(57, spell.spell)
        assertEquals(NavArea(2881, 3666, 2884, 3670, 0), spell.destinationArea)
        assertTrue(spell.chain.isEmpty())
        assertIs<Step.Move>(route.steps[1])
        assertEquals(TeleportSpell.TROLLHEIM, mapped(serviceRoute)?.spell)
    }

    @Test
    fun `a named book op is the link's action, and a blank one is the plain cast`() {
        assertEquals("Marigold Farm", spell(marigoldDb).action)
        assertEquals("Marigold Farm", mapped(marigoldDb)?.action)
        assertNull(spell(wendlewickBlank).action)
        assertEquals("Cast", mapped(wendlewickBlank)?.action)
    }

    @Test
    fun `the service's answers under metadata teleport keep the spell, its action and its box`() {
        val marigold = spell(serviceMarigold)
        assertEquals(260, marigold.spell)
        assertEquals("Marigold Farm", marigold.action)
        assertEquals(3561, marigold.destinationArea?.minX)
        assertEquals(1490, marigold.destinationArea?.maxY)
        assertTrue(marigold.chain.isEmpty())
        assertEquals("Marigold Farm", mapped(serviceMarigold)?.action)
        assertEquals("Cast", spell(serviceCast).action)
    }

    @Test
    fun `an unknown spell maps to no link`() {
        assertNull(mapped(unknown))
    }

    @Test
    fun `area task unlocks are base keys and spell keys are spell keys`() {
        for (key in listOf("varbit_15554", "varbit_15629", "varbit_15896", "varbit_15876", "varbit_60829")) {
            assertEquals(RequirementsBuilder.Block.BASE, RequirementsBuilder.blockOf(key), key)
        }
        assertEquals("spell_57", RequirementsBuilder.spellKey(TeleportSpell.TROLLHEIM))
        assertEquals(RequirementsBuilder.Block.SPELL, RequirementsBuilder.blockOf("spell_57"))
        assertEquals(TeleportSpell.entries.size, TeleportSpell.entries.map { it.abilityId }.distinct().size)
    }
}
