package com.projectx.traversal.hops

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The few JSON reads hop lines need; writers build their lines by hand so the bytes match every other client's. */
internal object HopJson {
    /** One line as an object; throws when it is not one. */
    fun parse(line: String): JsonObject = JsonParser.parseString(line).asJsonObject

    fun parseOrNull(line: String): JsonObject? = runCatching { parse(line) }.getOrNull()

    private fun JsonObject.number(name: String): JsonElement? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }

    fun int(o: JsonObject, name: String): Int? = o.number(name)?.asNumber?.toInt()

    fun long(o: JsonObject, name: String): Long? = o.number(name)?.asNumber?.toLong()

    fun double(o: JsonObject, name: String): Double? = o.number(name)?.asNumber?.toDouble()

    fun string(o: JsonObject, name: String): String? = o.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun bool(o: JsonObject, name: String, default: Boolean): Boolean =
        o.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default

    fun array(o: JsonObject, name: String): JsonArray? = o.get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    fun intAt(e: JsonElement?): Int? = e?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber?.toInt()

    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) {
            when {
                ch == '"' || ch == '\\' -> append('\\').append(ch)
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}
