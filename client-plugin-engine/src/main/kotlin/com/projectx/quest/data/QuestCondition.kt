package com.projectx.quest.data

/** A check on game state that finishes a step (postconditions) or moves the guide elsewhere (jumpconditions). */
sealed class QuestCondition {
    /** Within [range] tiles (Chebyshev) of the point. */
    data class DistanceTo(val x: Double, val heightFine: Double, val y: Double,
                          val range: Int, val instance: Boolean = false) : QuestCondition()

    data class DistanceFrom(val x: Double, val heightFine: Double, val y: Double,
                            val range: Int, val instance: Boolean = false) : QuestCondition()

    /** As [DistanceTo], and on upper ground when [heightFine] says the point is raised. */
    data class DistanceToWithHeight(val x: Double, val heightFine: Double, val y: Double,
                                    val range: Int, val instance: Boolean = false) : QuestCondition()

    data class DistanceFromWithHeight(val x: Double, val heightFine: Double, val y: Double,
                                      val range: Int, val instance: Boolean = false) : QuestCondition()

    data object NotInInstance : QuestCondition()

    data object ChangedInstance : QuestCondition()

    data object Manual : QuestCondition()

    data object Always : QuestCondition()

    /** Stores the first group of [pattern] found in dialogue under [key]. */
    data class CaptureConversationState(
        val pattern: String,
        val key: String,
    ) : QuestCondition()

    data class StateEquals(
        val key: String,
        val value: String,
    ) : QuestCondition()

    data class InventoryContains(val itemId: Int, val displayName: String, val quantity: Int,
                                 val candidateItemTypeIds: List<Int> = listOf()) : QuestCondition()

    data class InventoryDoesNotContain(val itemId: Int, val displayName: String,
                                       val candidateItemTypeIds: List<Int> = listOf()) : QuestCondition()

    /** With a positive [quantity], at least that many targets must be in view. */
    data class ModelVisible(val kind: String, val typeId: Int, val modelIds: List<Int>,
                            val candidateNpcTypeIds: List<Int> = listOf(), val candidateObjectTypeIds: List<Int> = listOf(),
                            val displayName: String, val animated: Boolean = false, val instance: Boolean = false,
                            val quantity: Int = -1, val atLocation: WorldLocation? = null) : QuestCondition()

    data class ModelNotVisible(val kind: String, val typeId: Int, val modelIds: List<Int>,
                               val candidateNpcTypeIds: List<Int> = listOf(), val candidateObjectTypeIds: List<Int> = listOf(),
                               val displayName: String, val instance: Boolean = false, val atLocation: WorldLocation? = null) : QuestCondition()

    data object QuestStarted : QuestCondition()

    data object QuestComplete : QuestCondition()

    data object QuestInterfaceOpen : QuestCondition()

    /** A negative [interfaceId] is unset and never open. */
    data class InterfaceOpen(val interfaceId: Int = UNSET) : QuestCondition()

    data class NpcNearTile(val typeId: Int = UNSET, val displayName: String = NO_NAME,
                           val tileX: Int = 0, val tileY: Int = 0, val plane: Int = 0, val distance: Int = 2,
                           val instance: Boolean = false, val candidateNpcTypeIds: List<Int> = listOf()) : QuestCondition()

    data object ConversationActive : QuestCondition()

    data object ConversationInactive : QuestCondition()

    data class ConversationText(
        val text: String,
    ) : QuestCondition()

    data class ChatText(
        val text: String,
    ) : QuestCondition()

    data object InInstance : QuestCondition()

    data class InCombatWith(
        val npcId: Int,
        val displayName: String = NO_NAME,
    ) : QuestCondition()

    data class ItemClicked(
        val itemId: Int,
    ) : QuestCondition()

    data object Generic : QuestCondition()

    data class Unknown(
        val name: String,
    ) : QuestCondition()

    private companion object Defaults {
        const val UNSET = -1
        const val NO_NAME = ""
    }
}
