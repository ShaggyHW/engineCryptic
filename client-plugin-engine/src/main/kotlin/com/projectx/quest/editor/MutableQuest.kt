package com.projectx.quest.editor

import com.projectx.quest.data.CombatNpcInfo
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestItemReq
import com.projectx.quest.data.QuestReq
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.TpHint
import com.projectx.quest.data.WorldLocation

/** The editor's working copy of a quest; [toQuest] takes an immutable snapshot that later edits never reach. */
class MutableQuest(
    var slug: String, var name: String, var members: Boolean = false,
    var length: String? = null, var timeline: String? = null, var releaseDate: Long = 0L,
    var stageVarbit: Int = -1, var stageVarbitCompleteValue: Int = -1,
    var cacheQuestId: Int = -1, val neededItems: MutableList<QuestItemReq> = mutableListOf(),
    val recommendedItems: MutableList<QuestItemReq> = mutableListOf(), val steps: MutableList<MutableStep> = mutableListOf(),
) {
    /** Kept so a save from the editor writes back the requirements it has no fields for. */
    var combatNPCs: List<CombatNpcInfo> = listOf()
    var prereqQuests: List<String> = listOf()
    var questReqs: List<QuestReq> = listOf()

    fun toQuest() = Quest(
        slug = slug, name = name, members = members, length = length, timeline = timeline, releaseDate = releaseDate,
        neededItems = neededItems.toList(), recommendedItems = recommendedItems.toList(),
        combatNPCs = combatNPCs.toList(), prereqQuests = prereqQuests.toList(), questReqs = questReqs.toList(),
        steps = steps.map(MutableStep::toStep),
        stageVarbit = stageVarbit, stageVarbitCompleteValue = stageVarbitCompleteValue, cacheQuestId = cacheQuestId,
    )

    companion object {
        fun fromQuest(quest: Quest): MutableQuest = MutableQuest(
            quest.slug, quest.name, quest.members, quest.length, quest.timeline, quest.releaseDate,
            quest.stageVarbit, quest.stageVarbitCompleteValue, quest.cacheQuestId,
            quest.neededItems.toMutableList(), quest.recommendedItems.toMutableList(),
            quest.steps.mapTo(mutableListOf(), MutableStep::fromStep),
        ).apply {
            combatNPCs = quest.combatNPCs.toList()
            prereqQuests = quest.prereqQuests.toList()
            questReqs = quest.questReqs.toList()
        }
    }
}

/** One step of a [MutableQuest]; blank strings save as absent. */
class MutableStep(
    var title: String? = null, var text: String? = null, var warning: String? = null,
    var solverId: String? = null, var jumpOffset: Int = 0, val actions: MutableList<QuestAction> = mutableListOf(),
    val postconditions: MutableList<QuestCondition> = mutableListOf(), val jumpconditions: MutableList<QuestCondition> = mutableListOf(),
    val neededItems: MutableList<QuestItemReq> = mutableListOf(), val recommendedItems: MutableList<QuestItemReq> = mutableListOf(),
) {
    var tpHint: TpHint? = null

    fun toStep() = QuestStep(
        title = title.orAbsent(), text = text.orAbsent(), warning = warning.orAbsent(), tpHint = tpHint,
        actions = actions.toList(), postconditions = postconditions.toList(), jumpconditions = jumpconditions.toList(),
        jumpOffset = jumpOffset, neededItems = neededItems.toList(), recommendedItems = recommendedItems.toList(),
        solverId = solverId.orAbsent(),
    )

    companion object {
        fun fromStep(step: QuestStep): MutableStep = MutableStep(
            step.title, step.text, step.warning, step.solverId, step.jumpOffset,
            step.actions.toMutableList(), step.postconditions.toMutableList(), step.jumpconditions.toMutableList(),
            step.neededItems.toMutableList(), step.recommendedItems.toMutableList(),
        ).apply { tpHint = step.tpHint }
    }
}

private fun String?.orAbsent(): String? = this?.ifEmpty { null }

/** Blank actions for the editor's "add" menu. */
object QuestActionFactory {
    fun direction(x: Int, y: Int, heightFine: Double = 0.0): QuestAction.Direction =
        QuestAction.Direction(x.toDouble(), heightFine, y.toDouble(), instance = false, tile = false, distance = -1)

    fun modelHighlight(kind: String = "object", name: String = ""): QuestAction.ModelHighlight =
        QuestAction.ModelHighlight(kind = kind, typeId = -1, modelIds = listOf(), displayName = name)

    fun conversationHighlight(): QuestAction.ConversationHighlight = QuestAction.ConversationHighlight("")

    fun inventoryHighlight(): QuestAction.InventoryHighlight =
        QuestAction.InventoryHighlight(itemId = -1, modelIds = listOf(), displayName = "")

    fun continueConversation(): QuestAction.ContinueConversation = QuestAction.ContinueConversation

    fun resetInstance(): QuestAction.ResetInstance = QuestAction.ResetInstance

    fun pathGuide(): QuestAction.PathGuide = QuestAction.PathGuide(listOf())

    fun textHint(text: String = ""): QuestAction.TextHint = QuestAction.TextHint(text)

    fun interfaceComponentHighlight(): QuestAction.InterfaceComponentHighlight = QuestAction.InterfaceComponentHighlight(-1, -1)
}

/** Blank conditions for the editor's "add" menu. */
object QuestConditionFactory {
    fun distanceTo(x: Int, y: Int, range: Int = 2, heightFine: Double = 0.0): QuestCondition.DistanceTo =
        QuestCondition.DistanceTo(x.toDouble(), heightFine, y.toDouble(), range, instance = false)

    fun distanceFrom(x: Int, y: Int, range: Int = 5, heightFine: Double = 0.0): QuestCondition.DistanceFrom =
        QuestCondition.DistanceFrom(x.toDouble(), heightFine, y.toDouble(), range, instance = false)

    fun inventoryContains(itemId: Int = -1, name: String = "", qty: Int = 1): QuestCondition.InventoryContains =
        QuestCondition.InventoryContains(itemId = itemId, displayName = name, quantity = qty)

    fun inventoryDoesNotContain(itemId: Int = -1, name: String = ""): QuestCondition.InventoryDoesNotContain =
        QuestCondition.InventoryDoesNotContain(itemId = itemId, displayName = name)

    fun modelVisible(kind: String = "npc", name: String = ""): QuestCondition.ModelVisible =
        QuestCondition.ModelVisible(kind = kind, typeId = -1, modelIds = listOf(), displayName = name)

    fun modelNotVisible(kind: String = "npc", name: String = ""): QuestCondition.ModelNotVisible =
        QuestCondition.ModelNotVisible(kind = kind, typeId = -1, modelIds = listOf(), displayName = name)

    fun conversationText(text: String = ""): QuestCondition.ConversationText = QuestCondition.ConversationText(text)

    fun chatText(text: String = ""): QuestCondition.ChatText = QuestCondition.ChatText(text)
}

/** Note the order: x, y, then height, unlike the action's own x, height, y. */
fun QuestAction.Direction.withLocation(x: Double, y: Double, heightFine: Double) = copy(x = x, heightFine = heightFine, y = y)

fun QuestAction.ModelHighlight.withAtLocation(loc: WorldLocation?): QuestAction.ModelHighlight = copy(atLocation = loc)
