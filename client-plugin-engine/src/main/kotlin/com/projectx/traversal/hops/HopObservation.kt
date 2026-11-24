package com.projectx.traversal.hops

import com.google.gson.JsonElement
import com.projectx.pathfinder.StepRule
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile

/**
 * One hop, as cast and as it came out, with an [N] x [N] window of the live move masks and walk flags around the cast
 * tile plus the learned edges inside it, so any later model can explain it again ([HopExplainer]) without going back
 * to the spot. The line format is shared with every other client's observations.
 */
internal class HopObservation {
    var id = ""
    var time = 0L
    var ability = ""
    var probe = false

    /** Cast by hand and confirmed by the user: its strong evidence is manual. */
    var manual = false
    var probeEdge = -1L
    var from: Tile? = null
    val starts = ArrayList<Tile>()
    var direction: Direction? = null
    var aim: Tile? = null
    var predicted: Tile? = null
    var predictedLearned: Tile? = null
    var landed: Tile? = null
    var outcome = ""

    /** No walk click between the cast and the jump: the first tile seen after it is the landing. */
    var held = true
    var message = ""
    var model = HopModel.VERSION
    var build = ""
    var originX = 0
    var originY = 0
    var plane = 0
    val masks = ByteArray(N * N)
    val flags = ShortArray(N * N)
    val learnedPass = ArrayList<Long>()
    val learnedStop = ArrayList<Long>()
    val occupied = HashSet<Int>()

    /** Blockers resolved for explained edges: edge key to (locId, shape) pairs. */
    val blockers = LinkedHashMap<String, List<IntArray>>()

    /** Copies the live map around [center] into the window. */
    fun capture(center: Tile, map: HopMap = HopMap.current) {
        plane = center.plane
        originX = center.x - R
        originY = center.y - R
        val walk = map.walk()
        for (ly in 0 until N) for (lx in 0 until N) {
            val x = originX + lx
            val y = originY + ly
            val i = ly * N + lx
            // A cell holding cache state (not loaded live) has doors as the cache left them: never learn from it.
            if (!map.contains(x, y, plane) || !map.known(x, y, plane)) {
                masks[i] = 0
                flags[i] = UNKNOWN.toShort()
                continue
            }
            var bits = 0
            HopModel.DIRECTIONS.forEachIndexed { bit, d -> if (walk.canStep(x, y, plane, d)) bits = bits or (1 shl bit) }
            masks[i] = bits.toByte()
            flags[i] = (map.flags(x, y, plane) and WalkFlags.WALK_MASK).toShort()
        }
        learnedPass += HopKnowledge.learnedIn(true, originX - 1, originY - 1, originX + N, originY + N, plane)
        learnedStop += HopKnowledge.learnedIn(false, originX - 1, originY - 1, originX + N, originY + N, plane)
    }

    fun inWindow(x: Int, y: Int): Boolean = x >= originX && y >= originY && x < originX + N && y < originY + N

    private fun index(x: Int, y: Int): Int = (y - originY) * N + (x - originX)

    fun known(x: Int, y: Int): Boolean = inWindow(x, y) && flags[index(x, y)].toInt() and UNKNOWN == 0

    fun flagsAt(x: Int, y: Int): Int = if (inWindow(x, y)) flags[index(x, y)].toInt() and 0xFFFF else UNKNOWN

    fun markOccupied(x: Int, y: Int) {
        if (inWindow(x, y)) occupied += index(x, y)
    }

    fun occupiedAt(x: Int, y: Int): Boolean = inWindow(x, y) && index(x, y) in occupied

    /** The recorded walking rule: legal where the window's mask says so; unknown is never legal. */
    fun walkStep(x: Int, y: Int, plane: Int, direction: Direction): Boolean {
        if (plane != this.plane || !known(x, y) || !known(x + direction.delta.x, y + direction.delta.y)) return false
        return masks[index(x, y)].toInt() and maskBit(direction) != 0
    }

    /** The recorded walking rule, plus the edges in [open], minus those in [closed]. */
    fun rule(open: Set<Long>?, closed: Set<Long>?): StepRule = StepRule { x, y, p, d ->
        val edge = HopEdges.pack(x, y, p, d)
        when {
            closed != null && edge in closed -> false
            walkStep(x, y, p, d) -> true
            else -> open != null && edge in open && p == plane && known(x, y) && known(x + d.delta.x, y + d.delta.y)
        }
    }

    fun toJson(): String = buildString(6000) {
        append('{')
        append("\"id\":").append(HopJson.quote(id))
        append(",\"t\":").append(time)
        append(",\"ab\":").append(HopJson.quote(ability))
        if (!held) append(",\"held\":false")
        if (manual) append(",\"man\":true")
        if (probe) append(",\"probe\":true,\"pe\":").append(HopJson.quote(HopEdges.key(HopEdges.PASS, probeEdge)))
        append(",\"from\":").append(tile(from))
        append(",\"starts\":").append(starts.joinToString(",", "[", "]", transform = ::tile))
        direction?.let { append(",\"dir\":\"").append(HopEdges.name(it)).append('"') }
        aim?.let { append(",\"aim\":").append(tile(it)) }
        predicted?.let { append(",\"pred\":").append(tile(it)) }
        predictedLearned?.let { append(",\"predL\":").append(tile(it)) }
        landed?.let { append(",\"land\":").append(tile(it)) }
        append(",\"out\":").append(HopJson.quote(outcome))
        if (message.isNotEmpty()) append(",\"msg\":").append(HopJson.quote(message))
        append(",\"mv\":").append(model).append(",\"b\":").append(HopJson.quote(build))
        append(",\"w\":{\"ox\":").append(originX).append(",\"oy\":").append(originY).append(",\"p\":").append(plane)
            .append(",\"n\":").append(N).append(",\"m\":\"")
        for (m in masks) hex(m.toInt() and 0xFF, 2)
        append("\",\"f\":\"")
        for (f in flags) hex(f.toInt() and 0xFFF, 3)
        append("\"}")
        edges("le", HopEdges.PASS, learnedPass)
        edges("ls", HopEdges.STOP, learnedStop)
        if (occupied.isNotEmpty()) append(",\"occ\":").append(occupied.joinToString(",", "[", "]"))
        if (blockers.isNotEmpty()) {
            append(",\"bl\":{")
            blockers.entries.forEachIndexed { i, (key, pairs) ->
                if (i > 0) append(',')
                append(HopJson.quote(key)).append(':').append(pairs.joinToString(",", "[", "]") { "[${it[0]},${it[1]}]" })
            }
            append('}')
        }
        append('}')
    }

    private fun StringBuilder.edges(name: String, kind: String, list: List<Long>) {
        if (list.isEmpty()) return
        append(",\"").append(name).append("\":").append(list.joinToString(",", "[", "]") { HopJson.quote(HopEdges.key(kind, it)) })
    }

    private fun StringBuilder.hex(value: Int, digits: Int) {
        for (k in digits - 1 downTo 0) append(Character.forDigit((value ushr (4 * k)) and 0xF, 16))
    }

    companion object {
        /** The cast tile +- [R]: a ten-tile hop plus the walk while it arms. */
        const val R = 15
        const val N = 2 * R + 1

        /** Window flag: the tile was outside the live map (unknown, never a candidate). */
        const val UNKNOWN = 0x800

        fun maskBit(direction: Direction): Int = 1 shl HopModel.DIRECTIONS.indexOf(direction)

        /** One observation line; null for another window size. Throws on a damaged line. */
        fun fromJson(line: String): HopObservation? {
            val o = HopJson.parse(line)
            val h = HopObservation()
            h.id = HopJson.string(o, "id") ?: ""
            h.time = HopJson.long(o, "t") ?: 0
            h.ability = HopJson.string(o, "ab") ?: ""
            h.probe = HopJson.bool(o, "probe", false)
            h.manual = HopJson.bool(o, "man", false)
            HopJson.string(o, "pe")?.let { h.probeEdge = HopEdges.parse(it) }
            val window = o.getAsJsonObject("w")
            h.originX = window.get("ox").asInt
            h.originY = window.get("oy").asInt
            h.plane = window.get("p").asInt
            if (window.get("n").asInt != N) return null
            val masks = window.get("m").asString
            val flags = window.get("f").asString
            for (i in 0 until N * N) {
                h.masks[i] = Integer.parseInt(masks, 2 * i, 2 * i + 2, 16).toByte()
                h.flags[i] = Integer.parseInt(flags, 3 * i, 3 * i + 3, 16).toShort()
            }
            h.from = tileOf(o.get("from"), h.plane)
            HopJson.array(o, "starts")?.forEach { s -> tileOf(s, h.plane)?.let(h.starts::add) }
            h.direction = HopJson.string(o, "dir")?.let(HopEdges::directionNamed)
            h.aim = tileOf(o.get("aim"), h.plane)
            h.predicted = tileOf(o.get("pred"), h.plane)
            h.predictedLearned = tileOf(o.get("predL"), h.plane)
            h.landed = tileOf(o.get("land"), h.plane)
            h.outcome = HopJson.string(o, "out") ?: ""
            h.message = HopJson.string(o, "msg") ?: ""
            h.model = HopJson.int(o, "mv") ?: 0
            // Older observations did not record whether a walk click followed the cast: uncertain.
            h.held = h.model >= 3 && HopJson.bool(o, "held", true)
            h.build = HopJson.string(o, "b") ?: ""
            readEdges(o.get("le"), h.learnedPass)
            readEdges(o.get("ls"), h.learnedStop)
            HopJson.array(o, "occ")?.forEach { HopJson.intAt(it)?.let(h.occupied::add) }
            o.get("bl")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                val pairs = ArrayList<IntArray>()
                if (value.isJsonArray) {
                    for (p in value.asJsonArray) {
                        if (!p.isJsonArray || p.asJsonArray.size() != 2) continue
                        val loc = HopJson.intAt(p.asJsonArray[0]) ?: continue
                        val shape = HopJson.intAt(p.asJsonArray[1]) ?: continue
                        pairs += intArrayOf(loc, shape)
                    }
                }
                h.blockers[key] = pairs
            }
            return h
        }

        private fun readEdges(value: JsonElement?, out: MutableList<Long>) {
            if (value == null || !value.isJsonArray) return
            for (k in value.asJsonArray) {
                val edge = HopEdges.parse(if (k.isJsonPrimitive) k.asString else k.toString())
                if (edge >= 0) out += edge
            }
        }

        private fun tile(t: Tile?): String = if (t == null) "null" else "[${t.x},${t.y}]"

        private fun tileOf(value: JsonElement?, plane: Int): Tile? {
            if (value == null || !value.isJsonArray || value.asJsonArray.size() < 2) return null
            val x = HopJson.intAt(value.asJsonArray[0]) ?: return null
            val y = HopJson.intAt(value.asJsonArray[1]) ?: return null
            return Tile.of(x, y, plane)
        }
    }
}
