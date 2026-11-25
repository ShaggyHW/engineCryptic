package com.projectx.traversal.pathing

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import world.gregs.voidps.type.Tile

class ResultsParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Reads a navpath `/route` answer: an object with an `actions` array (or a bare array of actions). Macro actions
 * describe themselves through the planner's own database rows in `metadata.db_row` and `metadata.steps[].db_row`; the
 * rows after the action's own become its chain. A step type with no client action (`teleport`, `global_teleport`, or
 * one navpath added later) is logged and dropped, and the rest of the route is kept.
 */
object ResultsParser {

    private val KNOWN_TYPES = sortedSetOf(
        "move", "surge", "dive", "fairy_ring", "lodestone", "npc", "ifslot", "object", "door", "item_click", "item",
        "poa_item", "use_on", "tile_exact", "spell", "teleport", "global_teleport",
    )

    private val DOOR_INFO_TILE_KEYS = setOf("locationOpen", "locationClosed", "tileInside", "tileOutside")

    var log: (String) -> Unit = ::println

    fun parse(text: String?): NavRoute = route(root(text))

    /** Reads a `/route_area` answer: the `/route` fields plus the area tile the route ends on. */
    fun parseArea(text: String?): NavAreaRoute {
        val root = root(text)
        val route = route(root)
        val envelope = root.takeIf { it.isJsonObject }?.asJsonObject ?: throw ResultsParseException("the area answer is not an object")
        val reached = obj(envelope, "goal_reached")?.let { reached ->
            val x = int(reached, "wx") ?: throw ResultsParseException("goal_reached has no wx")
            val y = int(reached, "wy") ?: throw ResultsParseException("goal_reached has no wy")
            val plane = int(reached, "plane") ?: throw ResultsParseException("goal_reached has no plane")
            Tile.of(x, y, plane)
        }
        if (route.found && reached == null) throw ResultsParseException("the area answer names no goal tile")
        return NavAreaRoute(route, reached, int(envelope, "goal_tiles_resolved"), int(envelope, "goal_tiles_searched"))
    }

    /**
     * The walked length of a `/route` answer, or null when it is no walkable route: not found, or no move step at all.
     * `length_tiles` when the answer has it, else the move-step count.
     */
    fun walkedLength(raw: String?): Int? {
        val route = parse(raw)
        val moves = route.steps.count { it is Step.Move }
        if (!route.found || moves == 0) return null
        return route.lengthTiles ?: moves
    }

    /** The route's tiles from an answer asked for with `return_geometry`; null when no route was found. */
    fun parseGeometry(text: String?): List<Tile>? {
        val root = root(text)
        val envelope = root.takeIf { it.isJsonObject }?.asJsonObject ?: throw ResultsParseException("expected a route object")
        if (bool(envelope, "found") == false) return null
        val geometry = envelope.arrayOrNull("geometry") ?: return emptyList()
        return geometry.mapIndexed { i, element ->
            val triple = element.takeIf { it.isJsonArray && it.asJsonArray.size() >= 3 }?.asJsonArray
                ?: throw ResultsParseException("'geometry[$i]' must be [x, y, plane]")
            val values = (0 until 3).map { k -> intAt(triple[k]) ?: throw ResultsParseException("'geometry[$i][$k]' must be an integer") }
            Tile.of(values[0], values[1], values[2])
        }
    }

    private fun root(text: String?): JsonElement {
        if (text.isNullOrBlank()) throw ResultsParseException("the route answer is blank")
        return try {
            JsonParser.parseString(text)
        } catch (e: RuntimeException) {
            throw ResultsParseException("the route answer is not JSON: ${e.message}", e)
        }
    }

    private fun route(root: JsonElement): NavRoute {
        val (actions, envelope) = when {
            root.isJsonArray -> root.asJsonArray to null
            root.isJsonObject -> {
                val o = root.asJsonObject
                val list = o.arrayOrNull("actions")
                if (list == null && bool(o, "found") != false) throw ResultsParseException("the route answer has no actions")
                (list ?: JsonArray()) to o
            }
            else -> throw ResultsParseException("the route answer is neither an object nor an array")
        }
        val path = envelope?.arrayOrNull("path")?.mapIndexed { i, v -> intAt(v) ?: throw ResultsParseException("'path[$i]' must be an integer") }
        var steps = ArrayList<Step>(actions.size())
        actions.forEachIndexed { index, element ->
            val action = element.takeIf { it.isJsonObject }?.asJsonObject ?: throw ResultsParseException("action $index is not an object")
            step(index, action)?.let { steps += it.withMacro(macroOf(action)) }
        }
        val geometry = envelope?.arrayOrNull("geometry")?.let(::tiles).orEmpty()
        if (geometry.isNotEmpty()) steps = ArrayList(withHopWalks(steps, geometry))
        return NavRoute(
            found = envelope?.let { bool(it, "found") } ?: true,
            cost = envelope?.get("cost")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: 0.0,
            path = path,
            lengthTiles = int(envelope, "length_tiles"),
            durationMs = int(envelope, "duration_ms"),
            steps = steps,
            geometry = geometry,
        )
    }

    private fun tiles(array: JsonArray): List<Tile> {
        val out = ArrayList<Tile>(array.size())
        for (element in array) {
            val triple = element.takeIf { it.isJsonArray && it.asJsonArray.size() >= 3 }?.asJsonArray ?: return emptyList()
            val x = intAt(triple[0]) ?: return emptyList()
            val y = intAt(triple[1]) ?: return emptyList()
            val plane = intAt(triple[2]) ?: return emptyList()
            out += Tile.of(x, y, plane)
        }
        return out
    }

    /**
     * Each Surge and Dive with the tiles [geometry] walks between its `from` and `to`. The planner lets a hop clear a
     * fence a walker cannot pass, and its geometry is the walk around it, so a hop walked as the straight line can
     * leave the player standing against the fence. A hop the geometry cannot be lined up with keeps no walk.
     */
    fun withHopWalks(steps: List<Step>, geometry: List<Tile>): List<Step> {
        val out = ArrayList<Step>(steps.size)
        var cursor = 0
        for (step in steps) {
            val (from, to) = when (step) {
                is Step.Surge -> step.from to step.to
                is Step.Dive -> step.from to step.to
                is Step.Move -> {
                    val i = indexFrom(geometry, step.tile, cursor)
                    if (i >= 0) cursor = i
                    null to null
                }
                else -> null to null
            }
            val fi = if (from == null || to == null) -1 else indexFrom(geometry, from, cursor)
            val ti = if (fi < 0) -1 else indexFrom(geometry, to!!, fi + 1)
            val walked = if (ti < 0) null else steppedWalk(from!!, geometry.subList(fi + 1, ti + 1))
            if (walked == null) {
                out += step
                continue
            }
            cursor = ti
            out += when (step) {
                is Step.Surge -> step.copy(walked = walked)
                is Step.Dive -> step.copy(walked = walked)
                else -> step
            }
        }
        return out
    }

    private fun indexFrom(geometry: List<Tile>, tile: Tile, start: Int): Int {
        if (start > geometry.size) return -1
        val i = geometry.subList(start, geometry.size).indexOf(tile)
        return if (i < 0) -1 else start + i
    }

    /** [tiles] when each is one step from the one before, starting at [from]; null on a jump (a teleport, a door). */
    private fun steppedWalk(from: Tile, tiles: List<Tile>): List<Tile>? {
        var previous = from
        for (tile in tiles) {
            if (tile.plane != previous.plane || previous.getLongestDelta(tile) != 1) return null
            previous = tile
        }
        return tiles.toList()
    }

    private fun macroOf(action: JsonObject): NavMacro? {
        val macro = obj(action, "macro") ?: return null
        val kind = str(macro, "kind") ?: return null
        val id = macro.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: return null
        return NavMacro(kind, id)
    }

    private fun step(index: Int, o: JsonObject): Step? {
        val type = str(o, "type") ?: throw ResultsParseException("Step[$index]: missing required field 'type'")
        return when (type) {
            "move" -> Step.Move(tileOf(index, o.get("to"), "to") ?: moveFromMetadata(index, o))
            "surge" -> Step.Surge(tileOf(index, o.get("from"), "from"), required(index, o, "to", "surge"), int(o, "tiles_covered"))
            "dive" -> Step.Dive(tileOf(index, o.get("from"), "from"), required(index, o, "to", "dive"), int(o, "tiles_covered"))
            "tile_exact" -> Step.TileExact(required(index, o, "to", "tile_exact"), bool(o, "minimap"))
            "fairy_ring" -> fairyRing(index, o)
            "lodestone" -> lodestone(index, o)
            "npc" -> npc(index, o)
            "ifslot" -> ifSlot(index, o)
            "object" -> loc(index, o)
            "door" -> door(index, o)
            "item_click", "item" -> itemClick(index, o)
            "poa_item" -> poaItem(index, o)
            "use_on" -> useOn(index, o)
            "spell" -> spell(index, o)
            "teleport", "global_teleport" -> skip(index, type, "no client action for a generic teleport")
            else -> skip(index, type, "unknown step type (known: $KNOWN_TYPES)")
        }
    }

    private fun skip(index: Int, type: String, reason: String): Step? {
        log("[ResultsParser] skipping step[$index] type='$type' - $reason")
        return null
    }

    private fun error(index: Int, message: String): Nothing = throw ResultsParseException("Step[$index]: $message")

    private fun moveFromMetadata(index: Int, o: JsonObject): Tile {
        val meta = obj(o, "metadata") ?: error(index, "missing 'to' for move step")
        val x = int(meta, "x1") ?: error(index, "missing 'x1' for move step")
        val y = int(meta, "y1") ?: error(index, "missing 'y1' for move step")
        val plane = int(meta, "plane") ?: error(index, "missing 'plane' for move step")
        return Tile.of(x, y, plane)
    }

    private fun fairyRing(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val code = str(o, "code") ?: str(meta, "destination_code") ?: error(index, "missing 'code' for fairy_ring step")
        val action = str(o, "action") ?: str(meta, "action") ?: ""
        val portable = bool(meta, "portable") == true
        val from = obj(o, "from") ?: error(index, "missing 'from' for fairy_ring step")
        val objectId = int(from, "object_id")
        if (objectId == null && !portable) error(index, "missing 'from.object_id' for fairy_ring step")
        val to = required(index, o, "to", "fairy_ring")
        val corner = (from.arrayOrNull("min") ?: from.arrayOrNull("max"))?.takeIf { it.size() >= 3 }
        val fromTile = corner?.let {
            val x = intAt(it[0])
            val y = intAt(it[1])
            val plane = intAt(it[2])
            if (x != null && y != null && plane != null) Tile.of(x, y, plane) else null
        }
        return Step.FairyRing(code.lowercase(), if (portable) -1 else objectId!!, action, fromTile, to, portable)
    }

    private fun lodestone(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val db = obj(meta, "db_row")
        val teleport = obj(meta, "teleport")
        val teleportDb = obj(teleport, "db_row")
        val value = str(o, "value") ?: str(o, "lodestone") ?: str(o, "target_lodestone") ?: str(meta, "lodestone") ?:
            str(meta, "target_lodestone") ?: str(teleport, "lodestone") ?: str(teleport, "target_lodestone") ?:
            str(db, "lodestone") ?: str(teleportDb, "lodestone") ?: firstStepField(meta, "lodestone") ?: firstStepField(teleport, "lodestone")
            ?: error(index, "missing 'value' for lodestone step")
        // A lodestone's metadata.steps holds the lodestone itself first; chaining it would teleport twice.
        val chain = dbChildChain(db) + dbChildChain(teleportDb) + metaStepsChain(meta, setOf("lodestone")) +
            metaStepsChain(teleport, setOf("lodestone")) + topLevelChain(index, o)
        return Step.Lodestone(value, chain, requirementIds(o), pointOf(index, o, "from"), pointOf(index, o, "to"))
    }

    private fun npc(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val db = obj(meta, "db_row") ?: rowFromSteps(meta) { kind, row -> kind == "npc" || str(row, "action") != null }
        val id = int(o, "id") ?: int(meta, "npc_id") ?: int(db, "npc_id")
        val name = str(o, "name") ?: str(meta, "npc_name") ?: str(db, "npc_name")
        val action = str(o, "action") ?: str(meta, "action") ?: str(db, "action") ?: error(index, "missing 'action' for npc step")
        val radius = int(o, "search_radius") ?: int(meta, "search_radius") ?: int(db, "search_radius")
        if (id == null && name.isNullOrBlank()) error(index, "npc step requires either 'id' (int) or 'name' (string)")
        val destination = dbRect(db, "dest") ?: (obj(meta, "destination_area") ?: obj(o, "destination_area"))?.let { xyArea(index, it, "destination_area") }
        val firstInterfaceId = db?.arrayOrNull("child_chain")?.firstOrNull()?.takeIf { it.isJsonObject }
            ?.let { int(obj(it.asJsonObject, "meta"), "interface_id") }
        val chain = dbChildChain(db) + metaStepsChain(meta, setOf("npc")) + topLevelChain(index, o)
        return Step.Npc(
            id, name, action, radius, destination, dbRect(db, "orig"), firstInterfaceId, chain, requirementIds(o),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
        )
    }

    private fun ifSlot(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val db = obj(meta, "db_row") ?: rowFromSteps(meta) { kind, _ -> kind == "ifslot" }
        fun need(key: String, metaKey: String) = int(o, key) ?: int(meta, metaKey) ?: int(db, metaKey)
            ?: error(index, "missing '$key' for ifslot step")
        val iface = need("interface", "interface_id")
        val component = need("component", "component_id")
        val slot = need("slot", "slot_id")
        val clickId = need("click_id", "click_id")
        val origin = obj(o, "from")?.let { areaObject(index, it, "from") }
        val chain = dbChildChain(db) + metaStepsChain(meta, setOf("ifslot")) + topLevelChain(index, o)
        return Step.IFSlot(
            iface, component, slot, clickId, dbRect(db, "dest"), origin, chain, requirementIds(o),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
        )
    }

    private fun loc(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val db = obj(meta, "db_row") ?: rowFromSteps(meta) { kind, row -> kind == "object" || str(row, "action") != null }
        val id = int(o, "id") ?: int(meta, "object_id") ?: int(db, "object_id")
        val name = str(o, "name") ?: str(meta, "object_name") ?: str(db, "object_name")
        val action = str(o, "action") ?: str(meta, "action") ?: str(db, "action") ?: error(index, "missing 'action' for object step")
        val radius = int(o, "search_radius") ?: int(meta, "search_radius") ?: int(db, "search_radius")
        if (id == null && name.isNullOrBlank()) error(index, "object step requires either 'id' (int) or 'name' (string)")
        val destination = dbRect(db, "dest") ?: (obj(meta, "destination_area") ?: obj(o, "destination_area"))?.let { xyArea(index, it, "destination_area") }
        val origin = obj(o, "from")?.let { areaObject(index, it, "from") } ?: dbRect(db, "orig")
        val chain = dbChildChain(db) + metaStepsChain(meta, setOf("object")) + topLevelChain(index, o)
        return Step.Loc(
            id, name, action, radius, destination, origin, chain, requirementIds(o), pointOf(index, o, "from"), pointOf(index, o, "to"),
        )
    }

    private fun door(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val db = obj(meta, "db_row") ?: meta?.arrayOrNull("steps")?.firstNotNullOfOrNull { s -> s.takeIf { it.isJsonObject }?.let { obj(it.asJsonObject, "db_row") } }
        val direction = str(o, "direction") ?: str(meta, "door_direction") ?: str(db, "direction") ?: error(index, "missing 'direction' for door step")
        val info = obj(o, "info")?.let { explicitDoorInfo(index, it) } ?: doorInfo(o, meta, db)
        val chain = dbChildChain(db) + metaStepsChain(meta, setOf("door")) + topLevelChain(index, o)
        return Step.Door(
            direction.uppercase(), info, info?.tileInside, info?.tileOutside, chain, requirementIds(o),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
        )
    }

    private fun explicitDoorInfo(index: Int, info: JsonObject): DoorInfo {
        fun tile(key: String): Tile? {
            val value = info.get(key) ?: return null
            if (key in DOOR_INFO_TILE_KEYS && value.isJsonObject) {
                val min = value.asJsonObject.arrayOrNull("min") ?: error(index, "door info '$key.min' must be an int[3] array when Area object is used")
                return xyz(index, min, "info.$key")
            }
            return value.takeIf { it.isJsonArray }?.let { xyz(index, it.asJsonArray, "info.$key") }
        }
        return DoorInfo(
            int(info, "realIdOpen"), int(info, "realIdClosed"), tile("locationOpen"), tile("locationClosed"),
            tile("tileInside"), tile("tileOutside"), str(info, "openAction"),
        )
    }

    private fun doorInfo(o: JsonObject, meta: JsonObject?, db: JsonObject?): DoorInfo? {
        fun tile(key: String, column: String): Tile? =
            sequenceOf(meta, db).firstNotNullOfOrNull { source -> lenientTile(source?.get(key)) } ?: columnsTile(db, column)
        val info = DoorInfo(
            realIdOpen = int(o, "realIdOpen") ?: int(meta, "real_id_open") ?: int(db, "real_id_open"),
            realIdClosed = int(o, "realIdClosed") ?: int(meta, "real_id_closed") ?: int(db, "real_id_closed"),
            locationOpen = tile("location_open", "location_open"),
            locationClosed = tile("location_closed", "location_closed"),
            tileInside = tile("tile_inside", "tile_inside"),
            tileOutside = tile("tile_outside", "tile_outside"),
            openAction = (str(o, "openAction") ?: str(meta, "action") ?: str(db, "open_action"))?.takeIf { it.isNotBlank() },
        )
        return info.takeIf { it != DoorInfo() }
    }

    /** An `[x, y, plane]` array, or an area object's `min`; null for anything else. */
    private fun lenientTile(element: JsonElement?): Tile? {
        val array = when {
            element == null || element.isJsonNull -> return null
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> element.asJsonObject.arrayOrNull("min") ?: return null
            else -> return null
        }
        if (array.size() != 3) return null
        val values = array.map { intAt(it) ?: return null }
        return Tile.of(values[0], values[1], values[2])
    }

    private fun itemClick(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val teleport = obj(meta, "teleport")
        val db = obj(meta, "db_row") ?: obj(teleport, "db_row") ?: rowFromSteps(meta) { kind, row -> kind == "item" || str(row, "action") != null }
        val id = int(o, "id") ?: int(meta, "item_id") ?: int(db, "item_id")
        val name = str(o, "name") ?: str(meta, "item_name") ?: str(meta, "name") ?: str(db, "item_name") ?: str(db, "name")
        val option = str(o, "option") ?: str(meta, "option") ?: str(db, "action")
        val optionNum = int(o, "option_num") ?: int(meta, "option_num") ?: int(db, "option_num")
        val destination = dbRect(db, "dest") ?: (obj(meta, "destination_area") ?: obj(o, "destination_area"))?.let { xyArea(index, it, "destination_area") }
        if (id == null && name.isNullOrBlank()) error(index, "item_click step requires either 'id' (int) or 'name' (string)")
        if (option.isNullOrBlank() && optionNum == null) error(index, "item_click step requires either 'option' (string) or 'option_num' (int)")
        val origin = obj(o, "from")?.let { areaObject(index, it, "from") }
        val chain = dbChildChain(db) + metaStepsChain(meta, setOf("item", "item_click")) + metaStepsChain(teleport, setOf("item", "item_click")) +
            topLevelChain(index, o)
        return Step.ItemClick(
            id, name, option, optionNum, destination, origin, chain, requirementIds(o), pointOf(index, o, "from"), pointOf(index, o, "to"),
        )
    }

    private fun spell(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val db = obj(meta, "db_row") ?: obj(obj(meta, "teleport"), "db_row") ?: rowFromSteps(meta) { kind, _ -> kind == "spell" }
        val spell = int(o, "spell") ?: int(o, "spell_id") ?: int(meta, "spell") ?: int(meta, "spell_id") ?: int(db, "spell") ?: int(db, "spell_id")
            ?: error(index, "missing 'spell' (ability id) for spell step")
        val name = str(o, "name") ?: str(meta, "name") ?: str(db, "name")
        val action = (str(o, "action") ?: str(meta, "action") ?: str(db, "action"))?.takeIf { it.isNotBlank() }
        val destination = dbRect(db, "dest")
            ?: (obj(meta, "destination_area") ?: obj(o, "destination_area"))?.let { xyArea(index, it, "destination_area") }
            ?: o.get("to")?.let { areaFromTileOrArea(index, it, "to") }
        val chain = dbChildChain(db) + metaStepsChain(meta, setOf("spell")) + topLevelChain(index, o)
        return Step.Spell(spell, name, action, destination, chain, requirementIds(o), pointOf(index, o, "from"), pointOf(index, o, "to"))
    }

    private fun poaItem(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val teleport = obj(meta, "teleport")
        val db = obj(meta, "db_row") ?: obj(teleport, "db_row")
            ?: rowFromSteps(meta) { kind, row -> kind == "poa_item" || kind == "item" || str(row, "action") != null }
        val id = int(o, "id") ?: int(meta, "item_id") ?: int(db, "item_id")
        val name = str(o, "name") ?: str(meta, "item_name") ?: str(meta, "name") ?: str(db, "item_name") ?: str(db, "name")
        val action = str(o, "action") ?: str(meta, "action") ?: str(db, "action") ?: error(index, "poa_item step requires 'action' (string)")
        val action2 = str(o, "action2") ?: str(o, "action_2") ?: str(meta, "action2") ?: str(db, "action2")
        val action3 = str(o, "action3") ?: str(o, "action_3") ?: str(meta, "action3") ?: str(db, "action3")
        val destination = dbRect(db, "dest") ?: (obj(meta, "destination_area") ?: obj(o, "destination_area"))?.let { xyArea(index, it, "destination_area") }
        if (id == null && name.isNullOrBlank()) error(index, "poa_item step requires either 'id' (int) or 'name' (string)")
        val origin = obj(o, "from")?.let { areaObject(index, it, "from") }
        val exclude = setOf("poa_item", "item", "item_click")
        val chain = dbChildChain(db) + metaStepsChain(meta, exclude) + metaStepsChain(teleport, exclude) + topLevelChain(index, o)
        return Step.POAItem(
            id, name, action, action2, action3, destination, origin, chain, requirementIds(o), pointOf(index, o, "from"), pointOf(index, o, "to"),
        )
    }

    private fun useOn(index: Int, o: JsonObject): Step {
        val meta = obj(o, "metadata")
        val db = obj(meta, "db_row") ?: rowFromSteps(meta) { kind, row -> kind == "use_on" || int(row, "item_id") != null && int(row, "object_id") != null }
        val itemId = int(o, "item_id") ?: int(o, "id") ?: int(meta, "item_id") ?: int(db, "item_id")
        val itemName = str(o, "item_name") ?: str(o, "name") ?: str(meta, "item_name") ?: str(db, "item_name")
        val objectId = int(o, "object_id") ?: int(meta, "object_id") ?: int(db, "object_id")
        val objectName = str(o, "object_name") ?: str(meta, "object_name") ?: str(db, "object_name")
        if (itemId == null && itemName.isNullOrBlank()) error(index, "use_on step requires either 'item_id' (int) or 'item_name' (string)")
        if (objectId == null && objectName.isNullOrBlank()) error(index, "use_on step requires either 'object_id' (int) or 'object_name' (string)")
        val radius = int(o, "search_radius") ?: int(meta, "search_radius") ?: int(db, "search_radius")
        val destination = dbRect(db, "dest")
            ?: (obj(meta, "destination_area") ?: obj(o, "destination_area"))?.let { xyArea(index, it, "destination_area") }
            ?: o.get("to")?.let { areaFromTileOrArea(index, it, "to") }
        val origin = obj(o, "from")?.let { areaObject(index, it, "from") } ?: dbRect(db, "orig")
        val chain = dbChildChain(db) + metaStepsChain(meta, setOf("use_on")) + topLevelChain(index, o)
        return Step.ItemOnObject(
            itemId, itemName, objectId, objectName, radius, destination, origin, chain, requirementIds(o),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
        )
    }

    /** The action's row from `metadata.steps` when it has no `db_row`: the first [matches], else the first row. */
    private fun rowFromSteps(meta: JsonObject?, matches: (String?, JsonObject) -> Boolean): JsonObject? {
        val steps = meta?.arrayOrNull("steps") ?: return null
        var first: JsonObject? = null
        for (element in steps) {
            val step = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val row = obj(step, "db_row") ?: continue
            if (first == null) first = row
            if (matches(str(step, "kind")?.lowercase(), row)) return row
        }
        return first
    }

    private fun firstStepField(meta: JsonObject?, key: String): String? =
        meta?.arrayOrNull("steps")?.firstOrNull { it.isJsonObject }?.asJsonObject?.let { str(it, key) }

    /** The `chain` array a step carries itself. */
    private fun topLevelChain(index: Int, o: JsonObject): List<Step> {
        val chain = o.arrayOrNull("chain") ?: return emptyList()
        return chain.mapNotNull { element -> element.takeIf { it.isJsonObject }?.let { step(index, it.asJsonObject) } }
    }

    /** A row's `child_chain`: interface clicks directly, any other kind read like a step of its own. */
    private fun dbChildChain(db: JsonObject?): List<Step> {
        val chain = db?.arrayOrNull("child_chain") ?: return emptyList()
        val out = ArrayList<Step>()
        chain.forEachIndexed { i, element ->
            val child = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val type = str(child, "type") ?: return@forEachIndexed
            val meta = obj(child, "meta")
            if (type.equals("ifslot", ignoreCase = true)) {
                val iface = int(meta, "interface_id") ?: return@forEachIndexed
                val component = int(meta, "component_id") ?: return@forEachIndexed
                val slot = int(meta, "slot_id") ?: return@forEachIndexed
                out += Step.IFSlot(iface, component, slot, int(meta, "click_id") ?: 0, destRect(meta), null)
            } else {
                val synthetic = JsonObject()
                synthetic.addProperty("type", type)
                if (meta != null) {
                    val copy = meta.deepCopy()
                    destRect(meta)?.let { area ->
                        copy.add("destination_area", JsonObject().apply {
                            addProperty("x1", area.minX)
                            addProperty("y1", area.minY)
                            addProperty("x2", area.maxX)
                            addProperty("y2", area.maxY)
                        })
                    }
                    synthetic.add("metadata", copy)
                }
                runCatching { step(i, synthetic) }.getOrNull()?.let(out::add)
            }
        }
        return out
    }

    /** The `metadata.steps` read as steps from their rows; the first is the parent itself when of [exclude]'s kind. */
    private fun metaStepsChain(meta: JsonObject?, exclude: Set<String>): List<Step> {
        val steps = meta?.arrayOrNull("steps") ?: return emptyList()
        val out = ArrayList<Step>()
        steps.forEachIndexed { i, element ->
            val step = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val kind = (str(step, "kind") ?: str(step, "type"))?.lowercase() ?: return@forEachIndexed
            // A later step of the parent's own kind is a real step of the chain: a house tablet's Modify ends by
            // breaking the new tablet.
            if (i == 0 && kind in exclude) return@forEachIndexed
            val synthetic = JsonObject()
            synthetic.addProperty("type", kind)
            val childMeta = JsonObject()
            obj(step, "db_row")?.let { childMeta.add("db_row", it) }
            step.arrayOrNull("steps")?.let { childMeta.add("steps", it) }
            if (childMeta.size() > 0) synthetic.add("metadata", childMeta)
            runCatching { step(i, synthetic) }.getOrNull()?.let(out::add)
        }
        return out
    }

    /**
     * The planner's requirement ids behind an action: its metadata's (the whole macro chain's) plus those its row and
     * each chained row list, which the planner sends as ids or as text such as "74;379".
     */
    fun requirementIds(action: JsonObject): List<Int> {
        val meta = obj(action, "metadata") ?: return emptyList()
        val ids = LinkedHashSet<Int>()
        addIds(ids, meta.get("requirements"))
        obj(meta, "db_row")?.let { addIds(ids, it.get("requirements")) }
        meta.arrayOrNull("steps")?.forEach { step ->
            step.takeIf { it.isJsonObject }?.asJsonObject?.let { obj(it, "db_row") }?.let { addIds(ids, it.get("requirements")) }
        }
        return ids.toList()
    }

    private fun addIds(into: MutableSet<Int>, value: JsonElement?) {
        when {
            value == null || value.isJsonNull -> Unit
            value.isJsonArray -> value.asJsonArray.forEach { addIds(into, it) }
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> into += value.asInt
            value.isJsonPrimitive -> value.asString.split(Regex("[;,\\s]+")).mapNotNullTo(into) { it.toIntOrNull() }
        }
    }

    private fun dbRect(db: JsonObject?, prefix: String): NavArea? {
        val minX = int(db, "${prefix}_min_x") ?: return null
        val minY = int(db, "${prefix}_min_y") ?: return null
        val maxX = int(db, "${prefix}_max_x") ?: return null
        val maxY = int(db, "${prefix}_max_y") ?: return null
        return NavArea(minX, minY, maxX, maxY, int(db, "${prefix}_plane"))
    }

    /** A child step's `dest_rect` `[x1, y1, x2, y2]` on its `plane`. */
    private fun destRect(meta: JsonObject?): NavArea? {
        val rect = meta?.arrayOrNull("dest_rect")?.takeIf { it.size() >= 4 } ?: return null
        val values = (0 until 4).map { i -> intAt(rect[i]) ?: return null }
        return NavArea(values[0], values[1], values[2], values[3], int(meta, "plane"))
    }

    /** A tile a database row keeps as three columns, `<prefix>_x`, `<prefix>_y` and `<prefix>_plane`. */
    private fun columnsTile(db: JsonObject?, prefix: String): Tile? {
        val x = int(db, "${prefix}_x") ?: return null
        val y = int(db, "${prefix}_y") ?: return null
        val plane = int(db, "${prefix}_plane") ?: return null
        return Tile.of(x, y, plane)
    }

    private fun xyArea(index: Int, o: JsonObject, field: String): NavArea {
        val x1 = int(o, "x1") ?: error(index, "'$field.x1' must be int")
        val y1 = int(o, "y1") ?: error(index, "'$field.y1' must be int")
        val x2 = int(o, "x2") ?: error(index, "'$field.x2' must be int")
        val y2 = int(o, "y2") ?: error(index, "'$field.y2' must be int")
        return NavArea(x1, y1, x2, y2, null)
    }

    /** A `{min, max}` area object, on the min corner's plane; a missing corner is taken to be the other. */
    private fun areaObject(index: Int, area: JsonObject, field: String): NavArea {
        val min = area.arrayOrNull("min") ?: area.arrayOrNull("max") ?: error(index, "'$field.min' must be an int[3] array [x,y,plane]")
        val max = area.arrayOrNull("max") ?: min
        val low = xyz(index, min, "$field.min")
        val high = xyz(index, max, "$field.max")
        return NavArea(low.x, low.y, high.x, high.y, low.plane)
    }

    private fun areaFromTileOrArea(index: Int, element: JsonElement, field: String): NavArea? = when {
        element.isJsonObject -> areaObject(index, element.asJsonObject, field)
        element.isJsonArray -> NavArea.of(xyz(index, element.asJsonArray, field))
        else -> null
    }

    private fun xyz(index: Int, array: JsonArray, field: String): Tile {
        if (array.size() != 3) error(index, "'$field' must contain exactly 3 integers [x,y,plane]; got size=${array.size()}")
        val values = array.mapIndexed { k, v -> intAt(v) ?: error(index, "'$field[$k]' must be int") }
        return Tile.of(values[0], values[1], values[2])
    }

    private fun required(index: Int, o: JsonObject, key: String, kind: String): Tile =
        tileOf(index, o.get(key), key) ?: error(index, "missing '$key' for $kind step (expected [x,y,plane] or Area with min/max)")

    private fun pointOf(index: Int, o: JsonObject, key: String): Tile? = runCatching { tileOf(index, o.get(key), key) }.getOrNull()

    /** An `[x, y, plane]` array, or an area object's `min` (its `max` when it has no `min`). */
    private fun tileOf(index: Int, element: JsonElement?, field: String): Tile? {
        if (element == null || element.isJsonNull) return null
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> element.asJsonObject.let { it.arrayOrNull("min") ?: it.arrayOrNull("max") }
                ?: error(index, "'$field.min' must be an int[3] array [x,y,plane]")
            else -> error(index, "'$field' must be either int[3] array [x,y,plane] or Area object { min, max }")
        }
        return xyz(index, array, field)
    }

    private fun intAt(element: JsonElement?): Int? =
        element?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.let { Math.round(it).toInt() }

    private fun obj(o: JsonObject?, key: String): JsonObject? = o?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun str(o: JsonObject?, key: String): String? =
        o?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun int(o: JsonObject?, key: String): Int? = intAt(o?.get(key))

    private fun bool(o: JsonObject?, key: String): Boolean? =
        o?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.arrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
