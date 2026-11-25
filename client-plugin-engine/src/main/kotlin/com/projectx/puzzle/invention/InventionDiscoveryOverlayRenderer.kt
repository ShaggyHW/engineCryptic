package com.projectx.puzzle.invention

import com.projectx.game.hooks.Priority
import com.projectx.game.math.Vector2f
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.puzzle.PuzzleClient
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.rendering.ImGUIRender

object InventionDiscoveryOverlayRenderer {

    private const val OUTSET = 2f
    private const val ROUNDING = 4f
    private const val LINE_HEIGHT = 16f
    private const val INSET = 8f
    private const val CHAR_WIDTH = 7f
    private const val PANEL_GAP = 6f

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        val snapshot = InventionDiscoveryFeature.overlay ?: return
        if (!PuzzleClient.loggedIn) return
        val icons = List(TRACK_SLOTS) { slot ->
            PuzzleClient.rect(InventionDiscoveryOverlayState.INTERFACE_ID, InventionDiscoveryOverlayState.FIRST_ICON_COMPONENT + slot)
        }
        backgroundDrawList {
            icons.forEachIndexed { slot, rect ->
                if (rect != null) outlineSlot(rect, snapshot.slotHints.getOrElse(slot) { SlotHint.NEUTRAL })
            }
            val anchor = icons.filterNotNull().minByOrNull { it.y }
            if (anchor != null && snapshot.lines.isNotEmpty()) panel(anchor, snapshot)
        }
    }

    private fun BackgroundDrawListScope.outlineSlot(bounds: ScreenRect, hint: SlotHint) {
        val (color, thickness, badge) = when (hint) {
            SlotHint.NEUTRAL -> return
            SlotHint.LIKELY -> Triple(withAlpha(ImGuiColors.YELLOW, 140), 2f, null)
            SlotHint.LOCKED -> Triple(withAlpha(ImGuiColors.GREEN, 255), 2.5f, null)
            SlotHint.SWAP_FIRST -> Triple(pulseAlpha(ImGuiColors.CYAN, minAlpha = 220, maxAlpha = 255), 3.5f, "1")
            SlotHint.SWAP_SECOND -> Triple(pulseAlpha(ImGuiColors.CYAN, minAlpha = 220, maxAlpha = 255), 3.5f, "2")
        }
        val topLeft = Vector2f(bounds.x - OUTSET, bounds.y - OUTSET)
        val bottomRight = Vector2f(bounds.x + bounds.width + OUTSET, bounds.y + bounds.height + OUTSET)
        rect(topLeft, bottomRight, color, rounding = ROUNDING, thickness = thickness)
        if (badge != null) text(Vector2f(bounds.x + 3f, bounds.y + 1f), color, badge)
    }

    private fun BackgroundDrawListScope.panel(anchor: ScreenRect, state: InventionDiscoveryOverlayState) {
        val lines = state.lines
        val height = lines.size * LINE_HEIGHT + INSET
        val width = lines.maxOf { it.length } * CHAR_WIDTH + 2 * INSET
        val origin = Vector2f(anchor.x.toFloat(), anchor.y - height - PANEL_GAP)
        val corner = Vector2f(origin.x + width, origin.y + height)
        rectFilled(origin, corner, withAlpha(ImGuiColors.BLACK, 190))
        rect(origin, corner, if (state.solved) ImGuiColors.GREEN else ImGuiColors.CYAN, thickness = 1.5f)
        for ((row, words) in lines.withIndex()) {
            val color = when {
                row == 0 -> ImGuiColors.WHITE
                state.solved && row == lines.lastIndex -> ImGuiColors.GREEN
                else -> ImGuiColors.TEXT_PRIMARY
            }
            text(Vector2f(origin.x + INSET, origin.y + INSET / 2 + row * LINE_HEIGHT), color, words)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
