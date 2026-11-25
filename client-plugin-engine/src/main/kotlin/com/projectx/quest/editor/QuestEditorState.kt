package com.projectx.quest.editor

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.overlay.LiveWorld
import com.projectx.quest.runtime.ActiveQuestState
import com.projectx.script.ScriptExecutor
import com.projectx.script.event.Event
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.script.event.impl.ManualGroundItem
import com.projectx.script.event.impl.ManualItemTarget
import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.setting
import world.gregs.voidps.type.Tile
import java.io.File

enum class PickKind {
    TILE, OBJECT, NPC,
    ITEM, GROUND_ITEM, COMPONENT,
}

/** Something picked in game; which fields carry meaning depends on [kind]. */
data class PickResult(val kind: PickKind, val typeId: Int = -1, val name: String = "",
    val tileX: Int = 0, val tileY: Int = 0, val plane: Int = 0, val heightFine: Float = 0f,
    val interfaceId: Int = -1, val componentId: Int = -1, val slotId: Int = -1,
)

fun interface PickSink { fun onPicked(pick: PickResult) }

fun interface TilePickSink { fun onPicked(x: Int, y: Int, plane: Int, groundZ: Float) }

/**
 * The quest being edited and the "pick something in game" handshake. A pick is captured on the game thread and
 * handed to its sink only from [drainPickDelivery], so sinks always change editor state on one known thread.
 */
object QuestEditorState {
    private const val TILE_PROMPT = "left-click a tile to capture"

    val windowOpen: ImGuiState<Boolean> = setting(false)
    val selectedStepIndex: ImGuiState<Int> = setting(0)
    val pickMode: ImGuiState<Boolean> = setting(false)
    val pickPrompt: ImGuiState<String> = setting("")
    val saveStatus: ImGuiState<String> = setting("")

    @Volatile var draft: MutableQuest? = null
        private set

    @Volatile var pendingAccepts: Set<PickKind> = emptySet()
        private set

    @Volatile var lastSavedFile: File? = null
        private set

    private class Delivery(val pick: PickResult, val sink: PickSink)

    private var armed: PickSink? = null
    private var waiting: Delivery? = null
    private var observerAdded = false

    /** A quest without a guide opens with no steps; the screen then offers "Add the first step". */
    fun beginEdit(source: Quest) {
        val copy = MutableQuest.fromQuest(source)
        draft = copy
        val last = copy.steps.lastIndex
        selectedStepIndex.value = if (last < 0) 0 else ActiveQuestState.currentStepIndex.coerceIn(0, last)
        showEditor(true, status = "")
    }

    fun discard() {
        draft = null
        cancelPick()
        synchronized(this) { waiting = null }
        showEditor(false)
    }

    /** Re-selects the reloaded quest after saving the live one, so the player keeps their step. */
    fun save(): Boolean = draft?.toQuest()?.let(::store) ?: false

    @Synchronized
    fun beginPick(kinds: Set<PickKind>, prompt: String, sink: PickSink) {
        if (!observerAdded) {
            ScriptExecutor.addEventObserver(::watchManualAction)
            observerAdded = true
        }
        arm(sink, kinds.toSet(), prompt)
    }

    fun beginPickTile(onTile: TilePickSink) =
        beginPick(setOf(PickKind.TILE), TILE_PROMPT) { onTile.onPicked(it.tileX, it.tileY, it.plane, it.heightFine) }

    @Synchronized
    fun cancelPick() = arm(null, emptySet(), null)

    @Synchronized
    fun offerPick(pick: PickResult) {
        val sink = armed?.takeIf { pick.kind in pendingAccepts } ?: return
        waiting = Delivery(pick, sink)
        cancelPick()
    }

    fun drainPickDelivery() = hand(synchronized(this) { waiting.also { waiting = null } })

    private fun showEditor(open: Boolean, status: String? = null) {
        status?.let { saveStatus.value = it }
        windowOpen.value = open
    }

    private fun arm(sink: PickSink?, kinds: Set<PickKind>, prompt: String?) {
        armed = sink
        pendingAccepts = kinds
        prompt?.let { pickPrompt.value = it }
        pickMode.value = sink != null
    }

    private fun hand(delivery: Delivery?) {
        if (delivery == null) return
        try {
            delivery.sink.onPicked(delivery.pick)
        } catch (t: Throwable) {
            println("[QuestEditor] pick handler failed: $t")
        }
    }

    private fun store(quest: Quest): Boolean {
        val liveStep = ActiveQuestState.currentStepIndex
        val written = QuestLibrary.saveQuest(quest)
        lastSavedFile = written
        saveStatus.value = if (written == null) "! Save failed" else "[x] Saved ${written.name}"
        if (written == null) return false
        if (ActiveQuestState.selectedQuest?.slug == quest.slug) {
            QuestLibrary.bySlug(quest.slug)?.let { reloaded ->
                ActiveQuestState.select(reloaded)
                ActiveQuestState.setStep(liveStep)
            }
        }
        return true
    }

    private fun watchManualAction(event: Event) {
        if (event !is ManualDoAction || armed == null) return
        val picked = try {
            describe(event.target)
        } catch (_: Throwable) {
            null
        }
        picked?.let(::offerPick)
    }

    private fun describe(target: Any): PickResult? = when (target) {
        is NPC -> PickResult(PickKind.NPC, typeId = target.typeId.takeUnless { it == -1 } ?: target.id, name = target.name)
        is SceneObject -> PickResult(PickKind.OBJECT, typeId = target.visibleTypeId, name = target.name())
        is ManualItemTarget -> PickResult(PickKind.ITEM, typeId = target.itemId, name = target.name)
        is ManualGroundItem -> target.tile.let { tile ->
            PickResult(PickKind.GROUND_ITEM, target.itemId, target.name, tile.x, tile.y, tile.plane, groundUnder(tile))
        }
        is IFSlot -> PickResult(PickKind.COMPONENT, interfaceId = target.interfaceId, componentId = target.componentId, slotId = target.slotId)
        is Tile -> PickResult(PickKind.TILE, tileX = target.x, tileY = target.y, plane = target.plane, heightFine = groundUnder(target))
        else -> null
    }

    private fun groundUnder(tile: Tile): Float = HeightMap.fineHeight(tile)?.toFloat() ?: LiveWorld.playerFineZ() ?: 0f
}

/** The player's own tile as a pick, with no screen position. */
fun currentPlayerTilePick(): TilePickResolver.Hit? = if (LiveWorld.loggedIn) standingPick() else null

private fun standingPick(): TilePickResolver.Hit? {
    val here = LiveWorld.playerTile() ?: return null
    val z = LiveWorld.playerFineZ() ?: return null
    return TilePickResolver.Hit(here.x, here.y, here.plane, z, 0f, 0f)
}

fun currentMousePos(): Pair<Float, Float> = runCatching { NativeBridge.getMousePos() }.getOrDefault(0f to 0f)
