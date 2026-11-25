package com.projectx.quest.runtime

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.quest.data.Quest
import com.projectx.script.api.varps

/** The quest being guided and the step the player is on; an index equal to the step count means finished. */
object ActiveQuestState {
    /** Swapped whole, so the game and UI threads always see a quest with an index that belongs to it. */
    private data class Position(val quest: Quest? = null, val step: Int = 0, val stage: Int = -1)

    @Volatile private var position = Position()

    val selectedQuest: Quest? get() = position.quest

    val currentStepIndex: Int get() = position.step

    val lastVarbitValue: Int get() = position.stage

    fun select(quest: Quest?) {
        position = Position(quest)
        quest?.let(::seekToVarbitStage)
    }

    fun next() = moveTo { it + 1 }

    fun previous() {
        position = position.copy(step = (position.step - 1).coerceAtLeast(0))
    }

    fun reset() {
        position = position.copy(step = 0)
    }

    fun setStep(index: Int) = moveTo { index }

    fun jumpBy(offset: Int) = moveTo { it + offset }

    fun updateLastVarbit(value: Int) {
        position = position.copy(stage = value)
    }

    fun seekToVarbitStage() {
        position.quest?.let(::seekToVarbitStage)
    }

    /** Only the two ends of a stage varbit map onto guide steps; values in between leave the step alone. */
    fun seekToVarbitStage(quest: Quest) {
        if (quest.stageVarbit < 0 || quest.stageVarbitCompleteValue <= 0) return
        val stage = readStage(quest.stageVarbit) ?: return
        val step = when {
            stage <= 0 -> 0
            stage >= quest.stageVarbitCompleteValue -> quest.steps.size
            else -> position.step
        }
        position = position.copy(step = step, stage = stage)
    }

    private inline fun moveTo(target: (Int) -> Int) {
        val now = position
        val quest = now.quest ?: return
        position = now.copy(step = target(now.step).coerceIn(0, quest.steps.size))
    }

    private fun readStage(varbit: Int): Int? = try {
        if (Bootstrap.client.mainState == MainState.LOGGED_IN) varps.getVarBit(varbit) else null
    } catch (_: Throwable) {
        null
    }
}
