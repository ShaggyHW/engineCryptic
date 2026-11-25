package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep

/**
 * Elemental Workshop II's pipe junction box: three fixed pairs of holes, each joined by clicking both ends.
 * Never reports solved; the quest's own varbits for the box are unreliable, so the step's conditions decide.
 */
object ElementalWorkshopIIJunctionBoxSolver : QuestStepSolver {
    override val id get() = "elemental-workshop-ii.junction-box"

    private const val BOX = "elem_magicpress_pipes"
    private const val PAIRS = "top-left + bottom-right, top-middle + top-right, bottom-left + bottom-middle"

    private class Hole(val label: String, inlet: String) {
        val component by lazy { Names.component(BOX, inlet) }
        val selectedVar by lazy { Names.varc(inlet) }
    }

    private val topLeft = Hole("top-left", "inletam")
    private val topMiddle = Hole("top-middle", "inletbm")
    private val topRight = Hole("top-right", "inletcm")
    private val bottomLeft = Hole("bottom-left", "inlet1m")
    private val bottomMiddle = Hole("bottom-middle", "inlet2m")
    private val bottomRight = Hole("bottom-right", "inlet3m")

    private val scanOrder = listOf(topLeft, topMiddle, topRight, bottomLeft, bottomMiddle, bottomRight)
    private val partnerOf = mapOf(
        topLeft to bottomRight, bottomRight to topLeft,
        topMiddle to topRight, topRight to topMiddle,
        bottomLeft to bottomMiddle, bottomMiddle to bottomLeft,
    )
    private val pairStarts = listOf(topLeft, topMiddle, bottomLeft)

    private val boxInterface by lazy { Names.iface(BOX) }

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int) = advise()

    private fun advise(): QuestStepSolver.Result {
        if (!SolverWorld.isOpen(boxInterface)) return adviceOnly("Open the junction box, then connect the pipe pairs: $PAIRS.")
        val held = scanOrder.firstOrNull { SolverWorld.varc(it.selectedVar) == 1 }
        val marks = if (held != null) {
            val partner = partnerOf.getValue(held)
            listOf(buttonMark(boxInterface, partner.component, "Connect: ${partner.label}"))
        } else {
            pairStarts.map { buttonMark(boxInterface, it.component, "Start: ${it.label}") }
        }
        return QuestStepSolver.Result(listOf<QuestAction>(advice("Connect pipe pairs (click both holes): $PAIRS.")) + marks)
    }
}
