package com.projectx.script.api

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.util.gaussian
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

private val emoteGrid by lazy { Gameval.requireComponentHash("emotes2:main") }

/** The emotes in the emote tab, by the names it shows ("Cheer", "Jump For Joy", "Raspberry"). */
object Emotes {
    private val structs by lazy { Gameval.requireId(Gameval.ENUM, "emotes2_structs") }
    private val nameParam by lazy { Gameval.requireId(Gameval.PARAM, "emotes2_name") }

    /** Every emote name, in the order of the tab's grid. */
    val names: List<String> by lazy {
        val map = Cache.enum(structs)?.map ?: return@lazy emptyList()
        map.keys.sorted().map { slot -> (map[slot] as? Int)?.let { Cache.struct(it)?.getParamString(nameParam) } ?: "" }
    }

    /** The grid slot of the emote with this name, ignoring case, or -1. */
    fun slot(name: String): Int = names.indexOfFirst { it.equals(name.trim(), ignoreCase = true) }
}

/**
 * Performs the named emote from the emote tab and waits for the player to start it. Returns false when the emote is
 * unknown, the tab is not open, or no animation started. Java: `Wait.performEmote`.
 */
@JvmSynthetic
suspend fun Script.performEmote(name: String): Boolean {
    val slot = Emotes.slot(name)
    if (slot < 0) return false
    if (!IFSlot(emoteGrid ushr 16, emoteGrid and 0xFFFF, slot).click(1)) return false
    delayUntil(gaussian(2400L, 360_000L)) { localPlayer.animationId != -1 }
    return localPlayer.animationId != -1
}
