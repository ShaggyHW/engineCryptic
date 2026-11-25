package com.projectx.puzzle.invention

import com.projectx.puzzle.PuzzleClient
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval

/** Advises the next module swap while an invention discovery is being arranged. Read-only: it never clicks. */
object InventionDiscoveryFeature {

    @Volatile
    var overlay: InventionDiscoveryOverlayState? = null
        private set

    private const val MODE_ARRANGING = 2
    private const val NO_BLUEPRINT = Int.MIN_VALUE
    private const val PLACE_MODULES = "Place all five modules on the track to begin."

    private class DiscoveryVars(
        val mode: Int,
        val penalty: Int,
        val orderSlots: IntArray,
        val blueprint: Int,
        val scoreText: Int,
    )

    private val vars by lazy {
        FeatureIds("InventionDiscovery") {
            fun varbit(name: String) = Gameval.requireId(Gameval.VARBIT, name)
            fun varp(name: String) = Gameval.requireId(Gameval.VAR_PLAYER, name)
            DiscoveryVars(
                mode = varbit("invent_discovery_mode"),
                penalty = varbit("invent_discovery_score"),
                orderSlots = IntArray(TRACK_SLOTS) { varbit("invent_discovery_order_slot_$it") },
                blueprint = varp("invent_blueprint_selected"),
                scoreText = Gameval.requireComponentId("invent_discovery:track_score_text"),
            )
        }
    }

    private val learner = InventionDiscoveryModel()
    private var blueprint = NO_BLUEPRINT

    fun tick(enabled: Boolean) {
        if (!enabled || !PuzzleClient.isOpen(InventionDiscoveryOverlayState.INTERFACE_ID)) return standDown()
        val ids = vars.get() ?: return standDown()

        val selected = PuzzleClient.playerVar(ids.blueprint) ?: -1
        if (selected != blueprint) {
            blueprint = selected
            learner.reset()
        }
        if (PuzzleClient.playerVarbit(ids.mode) != MODE_ARRANGING) {
            overlay = null
            return
        }

        val track = IntArray(TRACK_SLOTS) { PuzzleClient.playerVarbit(ids.orderSlots[it]) ?: 0 }
        if (!InventionDiscoveryModel.isArrangement(track)) {
            overlay = InventionDiscoveryOverlayState(Array(TRACK_SLOTS) { SlotHint.NEUTRAL }, listOf(PLACE_MODULES), false)
            return
        }

        val result = PuzzleClient.text(InventionDiscoveryOverlayState.INTERFACE_ID, ids.scoreText)?.let(OptimisationText::parse)
        if (result != null) PuzzleClient.playerVarbit(ids.penalty)?.let { learner.record(track, it) }

        val next = learner.advise(track)
        val lines = ArrayList<String>(3)
        if (result != null) lines += "Optimisation: ${result.level} \u00B7 +${result.bonusXp} xp"
        lines += when {
            next.solved -> "Perfect - click Invent."
            next.swapFirst >= 0 && next.swapSecond >= 0 -> "Probe: swap slot ${next.swapFirst + 1} <-> slot ${next.swapSecond + 1}"
            else -> "Analysing arrangements..."
        }
        if (!next.solved) lines += "Learning: ${learner.observationCount} tried"
        overlay = InventionDiscoveryOverlayState(next.slotHints, lines, next.solved)
    }

    private fun standDown() {
        overlay = null
        blueprint = NO_BLUEPRINT
        learner.reset()
    }
}
