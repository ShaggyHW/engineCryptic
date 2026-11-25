package com.projectx.quest.data

/** One quest: journal facts, requirements and the step-by-step guide (empty for a quest nobody has written up). */
data class Quest(
    val slug: String, val name: String, val members: Boolean = false,
    val length: String? = null, val timeline: String? = null, val releaseDate: Long = 0L,
    val neededItems: List<QuestItemReq> = listOf(), val recommendedItems: List<QuestItemReq> = listOf(),
    val combatNPCs: List<CombatNpcInfo> = listOf(), val prereqQuests: List<String> = listOf(),
    val questReqs: List<QuestReq> = listOf(), val steps: List<QuestStep> = listOf(),
    val stageVarbit: Int = -1, val stageVarbitCompleteValue: Int = -1, val cacheQuestId: Int = -1,
)

data class QuestStep(val title: String? = null, val text: String? = null, val warning: String? = null,
                     val tpHint: TpHint? = null, val actions: List<QuestAction> = listOf(),
                     val postconditions: List<QuestCondition> = listOf(), val jumpconditions: List<QuestCondition> = listOf(),
                     val jumpOffset: Int = 0, val neededItems: List<QuestItemReq> = listOf(),
                     val recommendedItems: List<QuestItemReq> = listOf(), val solverId: String? = null)

/** [itemId] -1 when only the name is known. */
data class QuestItemReq(val name: String, val itemId: Int, val quantity: Int, val duringQuest: Boolean = false)

/** [level] is text because guides list ranges like "12-20". */
data class CombatNpcInfo(val name: String, val level: String, val quantity: Int)

/** [type] is one of `skill`, `ironmanOnlySkill`, `combat`, `questpoints` or `misc`. */
data class QuestReq(val type: String, val name: String? = null, val level: Int = 0, val text: String? = null, val ironmanOnly: Boolean = false)

data class TpHint(val type: String, val hover: String, val url: String? = null)
