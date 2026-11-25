package com.projectx.quest.solver

import com.projectx.puzzle.combolock.CombinationLock
import com.projectx.puzzle.combolock.CombinationLockLayout
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.WorldLocation
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile

internal object Names {
    fun iface(name: String): Int = Gameval.requireId(Gameval.INTERFACE, name)
    fun component(iface: String, name: String): Int = Gameval.requireComponentId("$iface:$name")
    fun npc(name: String): Int = Gameval.requireId(Gameval.NPC, name)
    fun loc(name: String): Int = Gameval.requireId(Gameval.LOC, name)
    fun obj(name: String): Int = Gameval.requireId(Gameval.OBJ, name)
    fun seq(name: String): Int = Gameval.requireId(Gameval.SEQ, name)
    fun varbit(name: String): Int = Gameval.requireId(Gameval.VARBIT_PLAYER, name)
    fun varp(name: String): Int = Gameval.requireId(Gameval.VAR_PLAYER, name)
    fun varc(name: String): Int = Gameval.requireId(Gameval.VAR_CLIENT, name)
}

internal val NOTHING = QuestStepSolver.Result()

internal val SOLVED = QuestStepSolver.Result(solved = true)

internal fun advice(text: String) = QuestAction.TextHint(text)

internal fun adviceOnly(vararg lines: String) = QuestStepSolver.Result(lines.map(::advice))

internal fun npcMark(type: Int, label: String) = QuestAction.ModelHighlight(
    kind = "npc", typeId = type, modelIds = listOf(), candidateNpcTypeIds = listOf(type), displayName = label,
)

internal fun locMark(type: Int, label: String, at: Tile) = QuestAction.ModelHighlight(
    kind = "object", typeId = type, modelIds = listOf(), displayName = label,
    atLocation = WorldLocation(at.x.toDouble(), 0.0, at.y.toDouble()),
)

internal fun buttonMark(interfaceId: Int, componentId: Int, label: String, color: Int? = null, slot: Int = -1) =
    QuestAction.InterfaceComponentHighlight(interfaceId, componentId, slot, label, color)

/** One entry per tile, ordered by plane, then x, then y. */
internal fun <T> List<T>.onePerTile(tileOf: (T) -> Tile): List<T> =
    distinctBy(tileOf).sortedWith(compareBy<T>({ tileOf(it).plane }, { tileOf(it).x }, { tileOf(it).y }))

/** Arrow presses that turn an open four-dial lock to [target], or Enter once it reads right; null while shut. */
internal fun dialGuidance(lock: CombinationLockLayout, target: String): List<QuestAction>? {
    if (target.length != lock.dials || !lock.isOpen()) return null
    val shown = lock.readDials() ?: return null
    val presses = shown.indices.mapNotNull { dial ->
        val turn = CombinationLock.move(shown[dial], target[dial])
        if (turn.solved) null else buttonMark(lock.interfaceId, lock.arrowComponent(dial, turn.direction), turn.clicks.toString(), ImGuiColors.CYAN)
    }
    return presses.ifEmpty { listOf(buttonMark(lock.interfaceId, lock.enterComponent, "Enter", ImGuiColors.CYAN)) }
}

/**
 * Lays [size]x[size] NPC blocks onto a board read north row first: [symbolOf] names each block type, the first
 * NPC of each symbol counts, and the one empty cell becomes [gap]. Null unless every block forms a clean grid.
 */
internal fun boardFromNpcs(size: Int, symbolOf: Map<Int, Int>, gap: Int): IntArray? {
    val firstOfEach = LinkedHashMap<Int, Tile>()
    for (npc in SolverWorld.npcs()) {
        val symbol = symbolOf[npc.type] ?: continue
        firstOfEach.putIfAbsent(symbol, npc.tile)
    }
    val blocks = size * size - 1
    if (firstOfEach.size != blocks) return null
    val columns = firstOfEach.values.map { it.x }.distinct().sorted()
    val rows = firstOfEach.values.map { it.y }.distinct().sortedDescending()
    if (columns.size != size || rows.size != size) return null
    val board = IntArray(size * size) { gap }
    for ((symbol, tile) in firstOfEach) {
        val cell = rows.indexOf(tile.y) * size + columns.indexOf(tile.x)
        if (board[cell] != gap) return null
        board[cell] = symbol
    }
    return board
}
