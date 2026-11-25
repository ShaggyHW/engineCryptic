package com.projectx.puzzle.combolock

import com.projectx.puzzle.PuzzleClient
import world.gregs.voidps.gameval.Gameval

object CombinationPlaqueReader {
    const val INTERFACE_ID = 140

    // Lumbridge, Varrock, Falador, ... Prifddinas: the initial of lodestone insignia i.
    private const val LODESTONE_INITIALS = "LVFATBCSPDYEABLCEOFKTWAP"
    private val INSIGNIA_SHEETS = listOf("rs3_icon_lodestone_insignia_", "rs3_icon_lodestone_insignia_small_")
    private const val CRESTS = 4

    private val initialByGraphic: Map<Int, Char> by lazy {
        buildMap {
            for (sheet in INSIGNIA_SHEETS) {
                LODESTONE_INITIALS.forEachIndexed { index, initial ->
                    Gameval.id(Gameval.GRAPHIC, "$sheet$index")?.let { put(it, initial) }
                }
            }
        }
    }

    private val crestComponents: List<Int> by lazy {
        (1..CRESTS).map { Gameval.requireComponentId("quest_charos_escape_room_plaque:crest_$it") }
    }

    fun letterForGraphic(graphic: Int): Char? = initialByGraphic[graphic]

    fun isOpen(): Boolean = PuzzleClient.isOpen(INTERFACE_ID)

    fun read(): String? {
        if (!PuzzleClient.isOpen(INTERFACE_ID)) return null
        val code = StringBuilder(CRESTS)
        for (component in crestComponents) {
            val graphic = PuzzleClient.graphic(INTERFACE_ID, component) ?: return null
            code.append(letterForGraphic(graphic) ?: return null)
        }
        return code.toString()
    }
}
