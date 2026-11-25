package com.projectx.quest.editor

import com.projectx.game.hooks.Priority
import com.projectx.game.math.Vector2f
import com.projectx.quest.overlay.LiveWorld
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender
import world.gregs.voidps.type.Tile
import kotlin.math.roundToInt

/** Shows the tile a tile pick would capture and offers it on a left click; Esc is the editor's Cancel button. */
object TilePickOverlay {
    private const val CLICK_GAP_MS = 250L
    private const val HINT_GREY = 0xFFAAAAAA.toInt()

    @Volatile private var lastOfferAt = 0L

    @JvmStatic
    @ImGUIRender(priority = Priority.HIGH)
    fun render() {
        try {
            if (!QuestEditorState.pickMode.value || PickKind.TILE !in QuestEditorState.pendingAccepts) return
            if (!LiveWorld.loggedIn) return
            val (mouseX, mouseY) = currentMousePos()
            val hit = TilePickResolver.resolve(mouseX, mouseY) ?: return
            backgroundDrawList { drawPicker(hit) }
            if (NativeBridge.isMouseClicked(0, false)) offerClick(hit)
        } catch (t: Throwable) {
            println("[TilePickOverlay] render failed: $t")
        }
    }

    private fun BackgroundDrawListScope.drawPicker(hit: TilePickResolver.Hit) {
        val ink = ImGuiColors.MAGENTA
        tile(Tile.of(hit.tileX, hit.tileY, hit.plane), ink, hit.heightFine)
        val point = Vector2f(hit.screenX, hit.screenY)
        circle(point, 6f, ink, thickness = 2f)
        circleFilled(point, 2.5f, ink)
        val caption = "Pick tile  (${hit.tileX}, ${hit.tileY})  plane=${hit.plane}  fineZ=${hit.heightFine.roundToInt()}"
        text(Vector2f(point.x + 12f, point.y - 24f), ink, caption)
        text(Vector2f(point.x + 12f, point.y - 8f), HINT_GREY, "left-click to select, Esc to cancel")
    }

    private fun offerClick(hit: TilePickResolver.Hit) {
        val clickedAt = System.currentTimeMillis()
        if (clickedAt - lastOfferAt < CLICK_GAP_MS) return
        lastOfferAt = clickedAt
        QuestEditorState.offerPick(PickResult(PickKind.TILE, tileX = hit.tileX, tileY = hit.tileY, plane = hit.plane, heightFine = hit.heightFine))
    }
}
