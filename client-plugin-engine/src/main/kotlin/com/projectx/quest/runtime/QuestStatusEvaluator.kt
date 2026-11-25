package com.projectx.quest.runtime

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.data.QuestReq
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.varps
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

enum class QuestStatus {
    UNKNOWN,
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
}

/** Whether the logged-in account has done, started or may start a quest. */
object QuestStatusEvaluator {
    private val ironmanVarbit by lazy { Gameval.requireId(Gameval.VARBIT, "ironman_enabled") }
    private val questPointsVar by lazy { Gameval.requireId(Gameval.VAR_PLAYER, "qp") }

    /** Guides track a stage varbit; a quest without one falls back to the cache's own progress vars. */
    fun statusOf(quest: Quest): QuestStatus = try {
        if (quest.stageVarbit < 0) cacheStatus(quest) else varbitStatus(quest)
    } catch (_: Throwable) {
        QuestStatus.UNKNOWN
    }

    fun isLocked(quest: Quest): Boolean = try {
        isMembersBlocked(quest) || quest.questReqs.any(::isReqUnmet) || quest.prereqQuests.any(::isPrereqUnmet)
    } catch (_: Throwable) {
        false
    }

    fun isReqUnmet(req: QuestReq): Boolean {
        val needsIronman = req.type == "ironmanOnlySkill"
        return when {
            req.type == "skill" || needsIronman -> (!needsIronman || ironman()) && belowLevel(req)
            req.type == "questpoints" -> readOrZero { varps.getVar(questPointsVar) } < req.level
            else -> false
        }
    }

    fun isPrereqUnmet(name: String): Boolean =
        QuestLibrary.byName(name)?.let { statusOf(it) != QuestStatus.COMPLETED } ?: false

    /** Membership is not detected yet, so every account is treated as a member and nothing is blocked. */
    fun isMembersBlocked(quest: Quest): Boolean = quest.members && !memberAccount()

    private fun memberAccount(): Boolean = true

    private fun varbitStatus(quest: Quest): QuestStatus {
        val stage = varps.getVarBit(quest.stageVarbit)
        val done = quest.stageVarbitCompleteValue
        if (done > 0 && stage >= done) return QuestStatus.COMPLETED
        return if (stage > 0) QuestStatus.IN_PROGRESS else QuestStatus.NOT_STARTED
    }

    private fun cacheStatus(quest: Quest): QuestStatus =
        if (quest.cacheQuestId >= 0 && Cache.initialised) CacheQuests.status(quest.cacheQuestId) else QuestStatus.UNKNOWN

    private fun belowLevel(req: QuestReq): Boolean {
        val skill = skillNamed(req.name) ?: return false
        return readOrZero { getRealLevel(skill) } < req.level
    }

    /** Guides spell it "Defence"; the enum spells it DEFENSE. */
    private fun skillNamed(name: String?): Skill? {
        val wanted = name?.trim()?.uppercase()?.replace("DEFENCE", "DEFENSE") ?: return null
        return Skill.entries.firstOrNull { it.name == wanted }
    }

    private fun ironman(): Boolean = readOrZero { varps.getVarBit(ironmanVarbit) } == 1

    private inline fun readOrZero(read: () -> Int): Int = try {
        read()
    } catch (_: Throwable) {
        0
    }
}
