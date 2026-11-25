package com.projectx.quest.data

/** A world anchor; [x] and [y] are offsets from the instance origin when the owner is instance-relative. */
data class WorldLocation(
    val x: Double, val heightFine: Double, val y: Double,
)

/** What a guide step shows the player. */
sealed class QuestAction {
    /** An arrow to a point; small [x]/[y] with [instance] are offsets from the instance origin. */
    data class Direction(val x: Double, val heightFine: Double, val y: Double,
                         val instance: Boolean = false, val tile: Boolean = false, val distance: Int = -1) : QuestAction()

    /** [kind] is `npc`, `object`, `item`, `model` or `grounditem`; [priority] `all` outlines every match. */
    data class ModelHighlight(val kind: String, val typeId: Int, val modelIds: List<Int>,
                              val candidateNpcTypeIds: List<Int> = listOf(), val candidateObjectTypeIds: List<Int> = listOf(),
                              val displayName: String, val priority: String? = null,
                              val instance: Boolean = false, val distance: Int = -1, val atLocation: WorldLocation? = null) : QuestAction()

    /** Points at the dialogue option whose text contains [text]. */
    data class ConversationHighlight(
        val text: String,
    ) : QuestAction()

    data class InventoryHighlight(val itemId: Int, val modelIds: List<Int>,
                                  val candidateItemTypeIds: List<Int> = listOf(), val displayName: String) : QuestAction()

    data object ContinueConversation : QuestAction()

    data object ResetInstance : QuestAction()

    /** A null [color] draws the default outline colour. */
    data class InterfaceComponentHighlight(val interfaceId: Int, val componentId: Int,
                                           val slotId: Int = -1, val label: String = "", val color: Int? = null) : QuestAction()

    data class PathGuide(val waypoints: List<Waypoint>,
                         val instance: Boolean = false) : QuestAction() {
        data class Waypoint(
            val x: Double, val heightFine: Double, val y: Double,
        )
    }

    /** A line of advice with nothing to draw in the world. */
    data class TextHint(
        val text: String,
    ) : QuestAction()

    /** A kind this build does not know; [name] keeps the original kind so it saves back unchanged. */
    data class Unknown(
        val name: String,
    ) : QuestAction()
}
