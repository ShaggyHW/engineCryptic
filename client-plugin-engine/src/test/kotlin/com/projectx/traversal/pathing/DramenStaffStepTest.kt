package com.projectx.traversal.pathing

import com.projectx.traversal.TraversalBuilder
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.TraversalNodeList
import com.projectx.traversal.nodes.DramenStaff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A step whose row lists the Dramen staff marks its nodes as needing it, and the mark survives a copy. */
class DramenStaffStepTest {

    private val shedDoor = """
        {"found":true,"cost":1200.0,"actions":[
         {"type":"object","from":{"min":[3201,3169,0],"max":[3201,3169,0]},
          "to":{"min":[2452,4473,0],"max":[2452,4473,0]},"cost_ms":1200.0,
          "node":{"type":"object","id":901},
          "metadata":{"kind":"object","first_id":901,"requirements":[378,379],
           "steps":[{"kind":"object","id":901,"cost_ms":1200.0,
            "db_row":{"object_id":2406,"object_name":"Door","action":"Open","search_radius":10,
             "dest_min_x":2450,"dest_max_x":2454,"dest_min_y":4471,"dest_max_y":4475,"dest_plane":0,
             "requirements":"378;379"}}]}}]}
    """.trimIndent()

    private val dbRowOnly = shedDoor.replace("\"requirements\":[378,379],", "")
    private val plain = dbRowOnly.replace("\"requirements\":\"378;379\"", "\"requirements\":\"74\"")

    @Test
    fun `the requirement ids come from the metadata or the row`() {
        val shed = assertIs<Step.Loc>(only(shedDoor))
        assertTrue(shed.requirements.containsAll(listOf(378, 379)))
        assertTrue(DramenStaff.requiredBy(shed.requirements))
        assertTrue(DramenStaff.requiredBy((only(dbRowOnly) as Step.HasChain).requirements))
        val plainStep = only(plain) as Step.HasChain
        assertFalse(DramenStaff.requiredBy(plainStep.requirements))
        assertEquals(listOf(74), plainStep.requirements)
    }

    @Test
    fun `mapped nodes carry the mark through a copy`() {
        val nodes = mapped(only(shedDoor))
        assertTrue(all(nodes).all(TraversalNode::needsDramenStaff))
        assertTrue(all(nodes.copy()).all(TraversalNode::needsDramenStaff))
        assertTrue(all(mapped(only(plain))).none(TraversalNode::needsDramenStaff))
    }

    private fun only(json: String): Step = ResultsParser.parse(json).steps.single()

    private fun mapped(step: Step): TraversalNodeList {
        val builder = TraversalBuilder<Nothing>()
        PathingTraversal.mapSteps(listOf(step), builder, NavOptions())
        return builder.buildNodes()
    }

    private fun all(list: TraversalNodeList): List<TraversalNode> = generateSequence(list.head) { it.next }.toList()
}
