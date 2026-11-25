package com.projectx.quest.overlay

import com.projectx.game.hooks.Priority
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.runtime.ActiveQuestState
import com.projectx.quest.runtime.QuestHighlightTick
import com.projectx.quest.runtime.QuestInstanceTracker
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender
import com.projectx.ui.highlight.HighlightStyle
import com.projectx.ui.highlight.InterfaceHighlight
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile

/** Draws the guided step in the world and keeps its interface highlights registered. */
object QuestOverlayRenderer {
    private const val LOG_TAG = "[QuestOverlayRenderer]"
    private const val LABEL_LIFT = 32f
    private const val PATH_THICKNESS = 3f
    private const val DIALOG_FAMILY = "quest-helper-dialog-"
    private const val BACKPACK_FAMILY = "quest-helper-inv-"
    private const val COMPONENT_FAMILY = "quest-helper-comp-"

    private val families = listOf(DIALOG_FAMILY, BACKPACK_FAMILY, COMPONENT_FAMILY)

    private class OptionList(val interfaceId: Int, val optionComponents: List<Int>)

    private val optionLists: List<OptionList> by lazy {
        listOf("choice_v2" to 5, "choice_v2_frem" to 5, "choice_v2_overlay" to 5, "modal_choice" to 10).map { (name, count) ->
            OptionList(
                Gameval.requireId(Gameval.INTERFACE, name),
                (1..count).map { Gameval.requireComponentId("$name:option_text_$it") },
            )
        }
    }

    /** The world point a guide coordinate names, with the ground height to draw it at. */
    private class Spot(val x: Int, val y: Int, val z: Float)

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() = attempt("render", ::drawFrame)

    @JvmStatic
    fun tickInterfaceHighlights() = attempt("interface highlights", ::syncHighlights)

    private fun drawFrame() {
        val step = guidedStep() ?: return
        val shown = step.actions + QuestHighlightTick.solverOverlayActions
        val colour = pulseAlpha(ImGuiColors.CYAN)
        val player = LiveWorld.playerTile()
        backgroundDrawList { shown.forEach { attempt("drawing $it") { drawAction(it, colour, player) } } }
    }

    private fun syncHighlights() {
        val step = guidedStep() ?: return families.forEach { keep(it, emptySet()) }
        val shown = step.actions + QuestHighlightTick.solverOverlayActions
        attempt("dialogue highlights") {
            publish(DIALOG_FAMILY, shown.filterIsInstance<QuestAction.ConversationHighlight>(), ::highlightOption)
        }
        attempt("backpack highlights") {
            publish(BACKPACK_FAMILY, shown.filterIsInstance<QuestAction.InventoryHighlight>(), ::highlightCarried)
        }
        attempt("component highlights") {
            publish(COMPONENT_FAMILY, shown.filterIsInstance<QuestAction.InterfaceComponentHighlight>(), ::highlightComponent)
        }
    }

    private fun guidedStep(): QuestStep? {
        if (!UIState.questHelperEnabled.value || !UIState.questHelperShowOverlay.value) return null
        if (!LiveWorld.loggedIn) return null
        val quest = ActiveQuestState.selectedQuest ?: return null
        return quest.steps.getOrNull(ActiveQuestState.currentStepIndex)
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            println("$LOG_TAG $what failed: $t")
        }
    }

    private fun BackgroundDrawListScope.drawAction(action: QuestAction, colour: Int, player: Tile?) {
        when (action) {
            is QuestAction.ModelHighlight -> if (action.kind != "item") drawTargets(action, colour)
            is QuestAction.Direction -> if (player != null) drawDirection(action, colour, player)
            is QuestAction.PathGuide -> if (player != null) drawPath(action, colour, player.plane)
            else -> Unit
        }
    }

    private fun BackgroundDrawListScope.drawTargets(action: QuestAction.ModelHighlight, colour: Int) {
        val found = QuestHighlightTick.labels[action].orEmpty()
        for (label in found) {
            label.tiles.forEach { tile(it, colour, label.fineZ) }
            val anchor = WorldToScreen.getEstimatedTileCenter(Vector3f(label.fineX, label.fineY, label.fineZ)) ?: continue
            text(Vector2f(anchor.x, anchor.y - LABEL_LIFT), colour, "-> ${targetName(action, label)}")
        }
    }

    private fun targetName(action: QuestAction.ModelHighlight, label: QuestHighlightTick.Label): String {
        val given = action.displayName
        val placeholder = given.isBlank() || given.startsWith("Model #") || given == "(unknown)"
        return if (placeholder && label.name.isNotBlank()) label.name else given
    }

    private fun BackgroundDrawListScope.drawDirection(action: QuestAction.Direction, colour: Int, player: Tile) {
        val spot = locate(action.x, action.y, action.heightFine, action.instance, player.plane) ?: return
        val east = player.x - spot.x
        val north = player.y - spot.y
        if (action.distance > 0 && east * east + north * north <= action.distance * action.distance) return
        tile(Tile.of(spot.x, spot.y, player.plane), colour, spot.z)
        val (width, height) = NativeBridge.getDisplaySize()
        val middle = Vector2f(width / 2f, height / 2f)
        val onScreen = LiveWorld.project(spot.x, spot.y, spot.z)
        if (onScreen == null) {
            offscreenBearingArrow(middle, width, height, bearingRad(player.x, player.y, spot.x, spot.y), colour)
            return
        }
        if (!action.tile) arrow(middle, onScreen, colour)
        val lead = if (action.tile) "Stand on tile (${spot.x}, ${spot.y})" else "Go here (${spot.x}, ${spot.y})"
        val range = if (action.distance > 0) " - ${action.distance} tiles" else ""
        text(Vector2f(onScreen.x, onScreen.y - LABEL_LIFT), colour, lead + range)
    }

    private fun BackgroundDrawListScope.drawPath(action: QuestAction.PathGuide, colour: Int, plane: Int) {
        if (action.waypoints.isEmpty()) return
        val spots = action.waypoints.map { locate(it.x, it.y, it.heightFine, action.instance, plane) ?: return }
        val joined = ArrayList<Float>()
        for (spot in spots) {
            tile(Tile.of(spot.x, spot.y, plane), colour, spot.z)
            LiveWorld.project(spot.x, spot.y, spot.z)?.let { joined += it.x; joined += it.y }
        }
        if (joined.size >= 4) polyLine(joined.toFloatArray(), colour, 0, PATH_THICKNESS)
    }

    private fun locate(x: Double, y: Double, heightFine: Double, instance: Boolean, plane: Int): Spot? {
        val base = QuestInstanceTracker.origin
        if (instance && base == null) return null
        val shift = base.takeIf { instance }
        val worldX = x.toInt() + (shift?.x ?: 0)
        val worldY = y.toInt() + (shift?.y ?: 0)
        val fallback = (heightFine + (shift?.fineZ ?: 0f)).toFloat()
        return Spot(worldX, worldY, LiveWorld.groundZ(plane, worldX, worldY) ?: fallback)
    }

    private class Mark(val slot: IFSlot, val style: HighlightStyle)

    private fun <A : QuestAction> publish(family: String, actions: List<A>, find: (A) -> Mark?) {
        val shown = HashSet<String>()
        actions.forEachIndexed { index, action ->
            val mark = find(action) ?: return@forEachIndexed
            val key = family + index
            InterfaceHighlight.add(key, mark.slot, mark.style)
            shown += key
        }
        keep(family, shown)
    }

    private fun keep(family: String, shown: Set<String>) {
        InterfaceHighlight.entries.keys.removeIf { it.startsWith(family) && it !in shown }
    }

    private fun highlightOption(action: QuestAction.ConversationHighlight): Mark? {
        val needle = action.text.ifBlank { return null }
        for (list in optionLists) {
            if (!interfaces.isOpen(list.interfaceId)) continue
            val hit = list.optionComponents.firstOrNull { component ->
                interfaces.getComponent(list.interfaceId, component)?.text?.contains(needle, ignoreCase = true) == true
            } ?: continue
            return Mark(IFSlot(list.interfaceId, hit), HighlightStyle.DEFAULT)
        }
        return null
    }

    private fun highlightCarried(action: QuestAction.InventoryHighlight): Mark? {
        val wanted = (listOf(action.itemId).filter { it >= 0 } + action.candidateItemTypeIds).toSet()
        val carried = inventory.filter { it.amount > 0 }
        val name = action.displayName
        val item = carried.firstOrNull { it.id in wanted }
            ?: carried.takeIf { name.isNotBlank() }?.firstOrNull { it.name.contains(name, ignoreCase = true) }
            ?: return null
        return Mark(item.slot, HighlightStyle(label = name))
    }

    private fun highlightComponent(action: QuestAction.InterfaceComponentHighlight): Mark? {
        if (!interfaces.isOpen(action.interfaceId)) return null
        val style = HighlightStyle(color = action.color ?: ImGuiColors.CYAN, label = action.label)
        return Mark(IFSlot(action.interfaceId, action.componentId, action.slotId), style)
    }
}
