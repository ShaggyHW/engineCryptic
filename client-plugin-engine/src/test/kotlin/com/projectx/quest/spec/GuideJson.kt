package com.projectx.quest.spec

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.WorldLocation
import kotlin.math.abs

/** The guide schema as the spec states it, decoded by hand so loaded objects can be checked field by field. */
object GuideJson {
    private const val INSTANCE_DELTA_LIMIT = 250.0

    fun action(o: JsonObject): QuestAction = when (val t = o.str("t")) {
        "direction" -> {
            val x = o.num("x")
            val y = o.num("y")
            QuestAction.Direction(
                x = x,
                heightFine = o.num("heightFine"),
                y = y,
                instance = o.boolOrNull("instance") ?: (abs(x) < INSTANCE_DELTA_LIMIT && abs(y) < INSTANCE_DELTA_LIMIT),
                tile = o.boolOrNull("tile") ?: false,
                distance = o.intOrNull("distance") ?: -1,
            )
        }
        "modelHighlight" -> QuestAction.ModelHighlight(
            kind = o.str("kind"),
            typeId = o.intOrNull("typeId") ?: o.intOrNull("id") ?: -1,
            modelIds = o.ints("modelIds"),
            candidateNpcTypeIds = o.ints("candidateNpcTypeIds"),
            candidateObjectTypeIds = o.ints("candidateObjectTypeIds"),
            displayName = o.str("displayName"),
            priority = o.strOrNull("priority"),
            instance = o.boolOrNull("instance") ?: false,
            distance = o.intOrNull("distance") ?: -1,
            atLocation = o.location("atLocation"),
        )
        "conversationHighlight" -> QuestAction.ConversationHighlight(o.str("text"))
        "inventoryHighlight" -> QuestAction.InventoryHighlight(
            itemId = o.int("itemId"),
            modelIds = o.ints("modelIds"),
            candidateItemTypeIds = o.ints("candidateItemTypeIds"),
            displayName = o.str("displayName"),
        )
        "interfaceComponentHighlight" -> QuestAction.InterfaceComponentHighlight(
            interfaceId = o.int("interfaceId"),
            componentId = o.int("componentId"),
            slotId = o.intOrNull("slotId") ?: -1,
            label = o.strOrNull("label") ?: "",
            color = o.intOrNull("color"),
        )
        "continueConversation" -> QuestAction.ContinueConversation
        "resetInstance" -> QuestAction.ResetInstance
        "pathGuide" -> {
            val points = o.getAsJsonArray("waypoints")?.map {
                val p = it.asJsonObject
                QuestAction.PathGuide.Waypoint(p.num("x"), p.numOrNull("heightFine") ?: 0.0, p.num("y"))
            }
            if (points == null) {
                QuestAction.PathGuide(emptyList())
            } else {
                val allNear = points.isNotEmpty() && points.all { abs(it.x) < INSTANCE_DELTA_LIMIT && abs(it.y) < INSTANCE_DELTA_LIMIT }
                QuestAction.PathGuide(points, o.boolOrNull("instance") ?: allNear)
            }
        }
        "textHint" -> QuestAction.TextHint(o.str("text"))
        "unknown" -> QuestAction.Unknown(o.str("name"))
        else -> QuestAction.Unknown(t)
    }

    fun condition(o: JsonObject): QuestCondition = when (val t = o.str("t")) {
        "distanceTo" -> QuestCondition.DistanceTo(o.num("x"), o.num("heightFine"), o.num("y"), o.int("range"), o.flag("instance"))
        "distanceFrom" -> QuestCondition.DistanceFrom(o.num("x"), o.num("heightFine"), o.num("y"), o.int("range"), o.flag("instance"))
        "distanceToWithHeight" -> QuestCondition.DistanceToWithHeight(
            o.num("x"), o.num("heightFine"), o.num("y"),
            o.intOrNull("range") ?: o.intOrNull("plane") ?: 1,
            o.flag("instance"),
        )
        "distanceFromWithHeight" -> QuestCondition.DistanceFromWithHeight(o.num("x"), o.num("heightFine"), o.num("y"), o.int("range"), o.flag("instance"))
        "notInInstance" -> QuestCondition.NotInInstance
        "changedInstance" -> QuestCondition.ChangedInstance
        "manual" -> QuestCondition.Manual
        "always" -> QuestCondition.Always
        "captureConversationState" -> QuestCondition.CaptureConversationState(o.str("pattern"), o.str("key"))
        "stateEquals" -> QuestCondition.StateEquals(o.str("key"), o.str("value"))
        "inventoryContains" -> QuestCondition.InventoryContains(o.int("itemId"), o.str("displayName"), o.int("quantity"), o.ints("candidateItemTypeIds"))
        "inventoryDoesNotContain" -> QuestCondition.InventoryDoesNotContain(o.int("itemId"), o.str("displayName"), o.ints("candidateItemTypeIds"))
        "modelVisible" -> QuestCondition.ModelVisible(
            kind = o.str("kind"),
            typeId = o.intOrNull("typeId") ?: o.intOrNull("id") ?: -1,
            modelIds = o.ints("modelIds"),
            candidateNpcTypeIds = o.ints("candidateNpcTypeIds"),
            candidateObjectTypeIds = o.ints("candidateObjectTypeIds"),
            displayName = o.str("displayName"),
            animated = o.flag("animated"),
            instance = o.flag("instance"),
            quantity = o.intOrNull("quantity") ?: -1,
            atLocation = o.location("atLocation"),
        )
        "modelNotVisible" -> QuestCondition.ModelNotVisible(
            kind = o.str("kind"),
            typeId = o.intOrNull("typeId") ?: o.intOrNull("id") ?: -1,
            modelIds = o.ints("modelIds"),
            candidateNpcTypeIds = o.ints("candidateNpcTypeIds"),
            candidateObjectTypeIds = o.ints("candidateObjectTypeIds"),
            displayName = o.str("displayName"),
            instance = o.flag("instance"),
            atLocation = o.location("atLocation"),
        )
        "questStarted" -> QuestCondition.QuestStarted
        "questComplete" -> QuestCondition.QuestComplete
        "questInterfaceOpen" -> QuestCondition.QuestInterfaceOpen
        "interfaceOpen" -> QuestCondition.InterfaceOpen(o.intOrNull("interfaceId") ?: -1)
        "npcNearTile" -> QuestCondition.NpcNearTile(
            typeId = o.intOrNull("typeId") ?: -1,
            displayName = o.strOrNull("displayName") ?: "",
            tileX = o.intOrNull("tileX") ?: 0,
            tileY = o.intOrNull("tileY") ?: 0,
            plane = o.intOrNull("plane") ?: 0,
            distance = o.intOrNull("distance") ?: 2,
            instance = o.flag("instance"),
            candidateNpcTypeIds = o.ints("candidateNpcTypeIds"),
        )
        "conversationActive" -> QuestCondition.ConversationActive
        "conversationInactive" -> QuestCondition.ConversationInactive
        "conversationText" -> QuestCondition.ConversationText(o.str("text"))
        "chatText" -> QuestCondition.ChatText(o.str("text"))
        "inInstance" -> QuestCondition.InInstance
        "inCombatWith" -> QuestCondition.InCombatWith(o.int("npcId"), o.strOrNull("displayName") ?: "")
        "itemClicked" -> QuestCondition.ItemClicked(o.int("itemId"))
        "generic" -> QuestCondition.Generic
        "unknown" -> QuestCondition.Unknown(o.str("name"))
        else -> QuestCondition.Unknown(t)
    }

    private fun JsonObject.present(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
    private fun JsonObject.str(key: String): String = get(key).asString
    private fun JsonObject.strOrNull(key: String): String? = present(key)?.asString
    private fun JsonObject.num(key: String): Double = get(key).asDouble
    private fun JsonObject.numOrNull(key: String): Double? = present(key)?.asDouble
    private fun JsonObject.int(key: String): Int = get(key).asInt
    private fun JsonObject.intOrNull(key: String): Int? = present(key)?.asInt
    private fun JsonObject.boolOrNull(key: String): Boolean? = present(key)?.asBoolean
    private fun JsonObject.flag(key: String): Boolean = boolOrNull(key) ?: false
    private fun JsonObject.ints(key: String): List<Int> = getAsJsonArray(key)?.map { it.asInt } ?: emptyList()
    private fun JsonObject.location(key: String): WorldLocation? {
        val l = present(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val x = l.numOrNull("x") ?: return null
        val y = l.numOrNull("y") ?: return null
        return WorldLocation(x, l.numOrNull("heightFine") ?: 0.0, y)
    }
}
