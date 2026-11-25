package com.projectx.quest.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.projectx.quest.data.QuestAction as Action
import com.projectx.quest.data.QuestCondition as Check
import kotlin.math.abs

/** A guide file that lacks a field its schema requires; the whole file is rejected. */
internal class GuideFormatException(message: String) : RuntimeException(message)

/**
 * The per-quest guide JSON. Every action and condition is an object whose `t` names its kind; reading tolerates
 * legacy keys and omissions, writing emits the canonical shape and leaves out values equal to their defaults.
 */
internal object GuideFormat {
    private const val OFFSET_LIMIT = 250.0

    private val printer = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun parse(text: String): Quest = quest(JsonParser.parseString(text).asJsonObject)

    fun print(quest: Quest): String = printer.toJson(questJson(quest))

    fun quest(json: JsonObject): Quest = with(Fields(json)) {
        Quest(
            slug = string("slug"), name = string("name"), members = flag("members") ?: false,
            length = stringOrNull("length"), timeline = stringOrNull("timeline"),
            releaseDate = present("releaseDate")?.asLong ?: 0L,
            neededItems = objects("neededItems").map(::item), recommendedItems = objects("recommendedItems").map(::item),
            combatNPCs = objects("combatNPCs").map(::combatNpc),
            prereqQuests = array("prereqQuests").map { it.asString },
            questReqs = objects("questReqs").map(::requirement),
            steps = objects("steps").map(::step),
            stageVarbit = intOr("stageVarbit", 0), stageVarbitCompleteValue = intOr("stageVarbitCompleteValue", 0),
            cacheQuestId = intOr("cacheQuestId", 0),
        )
    }

    private fun step(json: JsonObject): QuestStep = with(Fields(json)) {
        QuestStep(
            title = stringOrNull("title"), text = stringOrNull("text"), warning = stringOrNull("warning"),
            tpHint = present("tpHint")?.asJsonObject?.let(::teleport),
            actions = objects("actions").map(::action),
            postconditions = objects("postconditions").map(::condition),
            jumpconditions = objects("jumpconditions").map(::condition),
            jumpOffset = intOr("jumpOffset", 0),
            neededItems = objects("neededItems").map(::item), recommendedItems = objects("recommendedItems").map(::item),
            solverId = stringOrNull("solverId"),
        )
    }

    private fun item(json: JsonObject) = with(Fields(json)) {
        QuestItemReq(stringOrNull("name").orEmpty(), intOr("itemId", 0), intOr("quantity", 0), flag("duringQuest") ?: false)
    }

    private fun combatNpc(json: JsonObject) = with(Fields(json)) {
        CombatNpcInfo(stringOrNull("name").orEmpty(), stringOrNull("level").orEmpty(), intOr("quantity", 0))
    }

    private fun requirement(json: JsonObject) = with(Fields(json)) {
        QuestReq(stringOrNull("type").orEmpty(), stringOrNull("name"), intOr("level", 0), stringOrNull("text"), flag("ironmanOnly") ?: false)
    }

    private fun teleport(json: JsonObject) = with(Fields(json)) {
        TpHint(stringOrNull("type").orEmpty(), stringOrNull("hover").orEmpty(), stringOrNull("url"))
    }

    fun action(json: JsonObject): Action {
        val fields = Fields(json)
        val kind = fields.string("t")
        return actionReaders[kind]?.invoke(fields) ?: Action.Unknown(kind)
    }

    fun condition(json: JsonObject): Check {
        val fields = Fields(json)
        val kind = fields.string("t")
        return conditionReaders[kind]?.invoke(fields) ?: Check.Unknown(kind)
    }

    private fun nearOrigin(x: Double, y: Double) = abs(x) < OFFSET_LIMIT && abs(y) < OFFSET_LIMIT

    private val actionReaders: Map<String, Fields.() -> Action> = mapOf(
        "direction" to {
            val x = double("x")
            val y = double("y")
            Action.Direction(x, double("heightFine"), y, flag("instance") ?: nearOrigin(x, y), isSet("tile"), intOr("distance", -1))
        },
        "modelHighlight" to {
            Action.ModelHighlight(
                kind = string("kind"), typeId = typeId(), modelIds = ints("modelIds"),
                candidateNpcTypeIds = ints("candidateNpcTypeIds"), candidateObjectTypeIds = ints("candidateObjectTypeIds"),
                displayName = string("displayName"), priority = stringOrNull("priority"),
                instance = isSet("instance"), distance = intOr("distance", -1), atLocation = anchor(),
            )
        },
        "conversationHighlight" to { Action.ConversationHighlight(string("text")) },
        "inventoryHighlight" to {
            Action.InventoryHighlight(int("itemId"), ints("modelIds"), ints("candidateItemTypeIds"), string("displayName"))
        },
        "interfaceComponentHighlight" to {
            Action.InterfaceComponentHighlight(
                int("interfaceId"), int("componentId"), intOr("slotId", -1), stringOrNull("label").orEmpty(), present("color")?.asInt,
            )
        },
        "continueConversation" to { Action.ContinueConversation },
        "resetInstance" to { Action.ResetInstance },
        "pathGuide" to { path() },
        "textHint" to { Action.TextHint(string("text")) },
        "unknown" to { Action.Unknown(string("name")) },
    )

    /** Without a waypoint list the path is empty and world-space, whatever else the object says. */
    private fun Fields.path(): Action.PathGuide {
        val points = present("waypoints")?.asJsonArray ?: return Action.PathGuide(listOf(), instance = false)
        val waypoints = points.map { element ->
            with(Fields(element.asJsonObject)) { Action.PathGuide.Waypoint(double("x"), doubleOr("heightFine", 0.0), double("y")) }
        }
        val allNear = waypoints.isNotEmpty() && waypoints.all { nearOrigin(it.x, it.y) }
        return Action.PathGuide(waypoints, flag("instance") ?: allNear)
    }

    private val conditionReaders: Map<String, Fields.() -> Check> = mapOf(
        "distanceTo" to { Check.DistanceTo(double("x"), double("heightFine"), double("y"), int("range"), isSet("instance")) },
        "distanceFrom" to { Check.DistanceFrom(double("x"), double("heightFine"), double("y"), int("range"), isSet("instance")) },
        "distanceToWithHeight" to {
            val range = present("range")?.asInt ?: intOr("plane", 1)
            Check.DistanceToWithHeight(double("x"), double("heightFine"), double("y"), range, isSet("instance"))
        },
        "distanceFromWithHeight" to {
            Check.DistanceFromWithHeight(double("x"), double("heightFine"), double("y"), int("range"), isSet("instance"))
        },
        "notInInstance" to { Check.NotInInstance },
        "changedInstance" to { Check.ChangedInstance },
        "manual" to { Check.Manual },
        "always" to { Check.Always },
        "captureConversationState" to { Check.CaptureConversationState(string("pattern"), string("key")) },
        "stateEquals" to { Check.StateEquals(string("key"), string("value")) },
        "inventoryContains" to { Check.InventoryContains(int("itemId"), string("displayName"), int("quantity"), ints("candidateItemTypeIds")) },
        "inventoryDoesNotContain" to { Check.InventoryDoesNotContain(int("itemId"), string("displayName"), ints("candidateItemTypeIds")) },
        "modelVisible" to {
            Check.ModelVisible(
                kind = string("kind"), typeId = typeId(), modelIds = ints("modelIds"),
                candidateNpcTypeIds = ints("candidateNpcTypeIds"), candidateObjectTypeIds = ints("candidateObjectTypeIds"),
                displayName = string("displayName"), animated = isSet("animated"), instance = isSet("instance"),
                quantity = intOr("quantity", -1), atLocation = anchor(),
            )
        },
        "modelNotVisible" to {
            Check.ModelNotVisible(
                kind = string("kind"), typeId = typeId(), modelIds = ints("modelIds"),
                candidateNpcTypeIds = ints("candidateNpcTypeIds"), candidateObjectTypeIds = ints("candidateObjectTypeIds"),
                displayName = string("displayName"), instance = isSet("instance"), atLocation = anchor(),
            )
        },
        "questStarted" to { Check.QuestStarted },
        "questComplete" to { Check.QuestComplete },
        "questInterfaceOpen" to { Check.QuestInterfaceOpen },
        "interfaceOpen" to { Check.InterfaceOpen(intOr("interfaceId", -1)) },
        "npcNearTile" to {
            Check.NpcNearTile(
                intOr("typeId", -1), stringOrNull("displayName").orEmpty(),
                intOr("tileX", 0), intOr("tileY", 0), intOr("plane", 0), intOr("distance", 2),
                isSet("instance"), ints("candidateNpcTypeIds"),
            )
        },
        "conversationActive" to { Check.ConversationActive },
        "conversationInactive" to { Check.ConversationInactive },
        "conversationText" to { Check.ConversationText(string("text")) },
        "chatText" to { Check.ChatText(string("text")) },
        "inInstance" to { Check.InInstance },
        "inCombatWith" to { Check.InCombatWith(int("npcId"), stringOrNull("displayName").orEmpty()) },
        "itemClicked" to { Check.ItemClicked(int("itemId")) },
        "generic" to { Check.Generic },
        "unknown" to { Check.Unknown(string("name")) },
    )

    private class Fields(private val json: JsonObject) {
        fun present(key: String): JsonElement? = json.get(key)?.takeUnless { it.isJsonNull }

        private fun required(key: String): JsonElement =
            present(key) ?: throw GuideFormatException("missing \"$key\" in ${json.toString().take(120)}")

        fun string(key: String): String = required(key).asString
        fun stringOrNull(key: String): String? = present(key)?.asString
        fun double(key: String): Double = required(key).asDouble
        fun doubleOr(key: String, fallback: Double): Double = present(key)?.asDouble ?: fallback
        fun int(key: String): Int = required(key).asInt
        fun intOr(key: String, fallback: Int): Int = present(key)?.asInt ?: fallback
        fun flag(key: String): Boolean? = present(key)?.asBoolean
        fun isSet(key: String): Boolean = flag(key) == true
        fun array(key: String): JsonArray = present(key)?.asJsonArray ?: JsonArray()
        fun objects(key: String): List<JsonObject> = array(key).map { it.asJsonObject }
        fun ints(key: String): List<Int> = array(key).map { it.asInt }

        /** Old guides named the type id `id`. */
        fun typeId(): Int = present("typeId")?.asInt ?: intOr("id", -1)

        fun anchor(): WorldLocation? {
            val at = present("atLocation")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val point = Fields(at)
            val x = point.present("x")?.asDouble ?: return null
            val y = point.present("y")?.asDouble ?: return null
            return WorldLocation(x, point.doubleOr("heightFine", 0.0), y)
        }
    }

    private fun questJson(q: Quest) = json {
        put("slug", q.slug)
        put("name", q.name)
        put("members", q.members)
        optional("length", q.length)
        optional("timeline", q.timeline)
        put("releaseDate", q.releaseDate)
        put("neededItems", q.neededItems.map(::itemJson))
        put("recommendedItems", q.recommendedItems.map(::itemJson))
        put("combatNPCs", q.combatNPCs.map { npc -> json { put("name", npc.name); put("level", npc.level); put("quantity", npc.quantity) } })
        put("prereqQuests", q.prereqQuests.map(::JsonPrimitive))
        put("questReqs", q.questReqs.map(::requirementJson))
        put("steps", q.steps.map(::stepJson))
        put("stageVarbit", q.stageVarbit)
        put("stageVarbitCompleteValue", q.stageVarbitCompleteValue)
        put("cacheQuestId", q.cacheQuestId)
    }

    private fun stepJson(s: QuestStep) = json {
        optional("title", s.title)
        optional("text", s.text)
        optional("warning", s.warning)
        s.tpHint?.let { hint -> put("tpHint", json { put("type", hint.type); put("hover", hint.hover); optional("url", hint.url) }) }
        put("actions", s.actions.map(::actionJson))
        put("postconditions", s.postconditions.map(::conditionJson))
        put("jumpconditions", s.jumpconditions.map(::conditionJson))
        put("jumpOffset", s.jumpOffset)
        put("neededItems", s.neededItems.map(::itemJson))
        put("recommendedItems", s.recommendedItems.map(::itemJson))
        optional("solverId", s.solverId)
    }

    private fun itemJson(i: QuestItemReq) = json {
        put("name", i.name)
        put("itemId", i.itemId)
        put("quantity", i.quantity)
        put("duringQuest", i.duringQuest)
    }

    private fun requirementJson(r: QuestReq) = json {
        put("type", r.type)
        optional("name", r.name)
        put("level", r.level)
        optional("text", r.text)
        put("ironmanOnly", r.ironmanOnly)
    }

    fun actionJson(action: Action): JsonObject = json {
        when (action) {
            is Action.Direction -> arrow(action)
            is Action.ModelHighlight -> highlight(action)
            is Action.ConversationHighlight -> tagged("conversationHighlight", "text" to action.text)
            is Action.InventoryHighlight -> backpack(action)
            Action.ContinueConversation -> kind("continueConversation")
            Action.ResetInstance -> kind("resetInstance")
            is Action.InterfaceComponentHighlight -> component(action)
            is Action.PathGuide -> path(action)
            is Action.TextHint -> tagged("textHint", "text" to action.text)
            is Action.Unknown -> tagged("unknown", "name" to action.name)
        }
    }

    fun conditionJson(condition: Check): JsonObject = json {
        when (val c = condition) {
            is Check.DistanceTo -> radius("distanceTo", c.x, c.heightFine, c.y, c.range, c.instance)
            is Check.DistanceFrom -> radius("distanceFrom", c.x, c.heightFine, c.y, c.range, c.instance)
            is Check.DistanceToWithHeight -> radius("distanceToWithHeight", c.x, c.heightFine, c.y, c.range, c.instance)
            is Check.DistanceFromWithHeight -> radius("distanceFromWithHeight", c.x, c.heightFine, c.y, c.range, c.instance)
            is Check.CaptureConversationState -> tagged("captureConversationState", "pattern" to c.pattern, "key" to c.key)
            is Check.StateEquals -> tagged("stateEquals", "key" to c.key, "value" to c.value)
            is Check.InventoryContains -> carrying("inventoryContains", c.itemId, c.displayName, c.quantity, c.candidateItemTypeIds)
            is Check.InventoryDoesNotContain -> carrying("inventoryDoesNotContain", c.itemId, c.displayName, null, c.candidateItemTypeIds)
            is Check.ModelVisible -> visible(c)
            is Check.ModelNotVisible -> notVisible(c)
            is Check.InterfaceOpen -> { kind("interfaceOpen"); put("interfaceId", c.interfaceId) }
            is Check.NpcNearTile -> npcNear(c)
            is Check.ConversationText -> tagged("conversationText", "text" to c.text)
            is Check.ChatText -> tagged("chatText", "text" to c.text)
            is Check.InCombatWith -> { kind("inCombatWith"); put("npcId", c.npcId); put("displayName", c.displayName) }
            is Check.ItemClicked -> { kind("itemClicked"); put("itemId", c.itemId) }
            is Check.Unknown -> tagged("unknown", "name" to c.name)
            else -> kind(bareKinds.getValue(c))
        }
    }

    private val bareKinds: Map<Check, String> = mapOf(
        Check.NotInInstance to "notInInstance", Check.ChangedInstance to "changedInstance",
        Check.Manual to "manual", Check.Always to "always",
        Check.QuestStarted to "questStarted", Check.QuestComplete to "questComplete",
        Check.QuestInterfaceOpen to "questInterfaceOpen",
        Check.ConversationActive to "conversationActive", Check.ConversationInactive to "conversationInactive",
        Check.InInstance to "inInstance", Check.Generic to "generic",
    )

    private fun JsonShape.arrow(a: Action.Direction) {
        kind("direction")
        point(a.x, a.heightFine, a.y)
        put("instance", a.instance)
        if (a.tile) put("tile", true)
        if (a.distance >= 0) put("distance", a.distance)
    }

    private fun JsonShape.highlight(a: Action.ModelHighlight) {
        kind("modelHighlight")
        target(a.kind, a.typeId, a.modelIds, a.candidateNpcTypeIds, a.candidateObjectTypeIds, a.displayName)
        optional("priority", a.priority)
        if (a.instance) put("instance", true)
        if (a.distance >= 0) put("distance", a.distance)
        anchor(a.atLocation)
    }

    private fun JsonShape.backpack(a: Action.InventoryHighlight) {
        kind("inventoryHighlight")
        put("itemId", a.itemId)
        put("modelIds", a.modelIds.map(::JsonPrimitive))
        idsIfAny("candidateItemTypeIds", a.candidateItemTypeIds)
        put("displayName", a.displayName)
    }

    private fun JsonShape.component(a: Action.InterfaceComponentHighlight) {
        kind("interfaceComponentHighlight")
        put("interfaceId", a.interfaceId)
        put("componentId", a.componentId)
        if (a.slotId >= 0) put("slotId", a.slotId)
        if (a.label.isNotEmpty()) put("label", a.label)
        a.color?.let { put("color", it) }
    }

    private fun JsonShape.path(a: Action.PathGuide) {
        kind("pathGuide")
        put("waypoints", a.waypoints.map { w -> json { point(w.x, w.heightFine, w.y) } })
        if (a.instance) put("instance", true)
    }

    private fun JsonShape.carrying(name: String, itemId: Int, displayName: String, quantity: Int?, candidates: List<Int>) {
        kind(name)
        put("itemId", itemId)
        put("displayName", displayName)
        quantity?.let { put("quantity", it) }
        idsIfAny("candidateItemTypeIds", candidates)
    }

    private fun JsonShape.visible(c: Check.ModelVisible) {
        kind("modelVisible")
        target(c.kind, c.typeId, c.modelIds, c.candidateNpcTypeIds, c.candidateObjectTypeIds, c.displayName)
        if (c.instance) put("instance", true)
        anchor(c.atLocation)
        if (c.animated) put("animated", true)
        if (c.quantity > 0) put("quantity", c.quantity)
    }

    private fun JsonShape.notVisible(c: Check.ModelNotVisible) {
        kind("modelNotVisible")
        target(c.kind, c.typeId, c.modelIds, c.candidateNpcTypeIds, c.candidateObjectTypeIds, c.displayName)
        if (c.instance) put("instance", true)
        anchor(c.atLocation)
    }

    private fun JsonShape.npcNear(c: Check.NpcNearTile) {
        kind("npcNearTile")
        put("typeId", c.typeId)
        put("displayName", c.displayName)
        put("tileX", c.tileX)
        put("tileY", c.tileY)
        put("plane", c.plane)
        put("distance", c.distance)
        if (c.instance) put("instance", true)
        idsIfAny("candidateNpcTypeIds", c.candidateNpcTypeIds)
    }

    private class JsonShape {
        val out = JsonObject()

        fun put(key: String, value: String) = out.addProperty(key, value)
        fun put(key: String, value: Number) = out.addProperty(key, value)
        fun put(key: String, value: Boolean) = out.addProperty(key, value)
        fun put(key: String, value: JsonElement) = out.add(key, value)
        fun put(key: String, values: List<JsonElement>) = out.add(key, JsonArray().apply { values.forEach(::add) })

        fun optional(key: String, value: String?) {
            if (value != null) put(key, value)
        }

        fun kind(name: String) = put("t", name)

        fun tagged(name: String, vararg texts: Pair<String, String>) {
            kind(name)
            texts.forEach { (key, value) -> put(key, value) }
        }

        fun idsIfAny(key: String, ids: List<Int>) {
            if (ids.isNotEmpty()) put(key, ids.map(::JsonPrimitive))
        }

        fun point(x: Double, heightFine: Double, y: Double) {
            put("x", x)
            put("heightFine", heightFine)
            put("y", y)
        }

        fun radius(name: String, x: Double, heightFine: Double, y: Double, range: Int, instance: Boolean) {
            kind(name)
            point(x, heightFine, y)
            put("range", range)
            if (instance) put("instance", true)
        }

        fun target(kind: String, typeId: Int, modelIds: List<Int>, npcIds: List<Int>, locIds: List<Int>, displayName: String) {
            put("kind", kind)
            if (typeId >= 0) put("typeId", typeId)
            put("modelIds", modelIds.map(::JsonPrimitive))
            idsIfAny("candidateNpcTypeIds", npcIds)
            idsIfAny("candidateObjectTypeIds", locIds)
            put("displayName", displayName)
        }

        fun anchor(at: WorldLocation?) {
            if (at != null) put("atLocation", json { point(at.x, at.heightFine, at.y) })
        }
    }

    private inline fun json(fill: JsonShape.() -> Unit): JsonObject = JsonShape().apply(fill).out
}
