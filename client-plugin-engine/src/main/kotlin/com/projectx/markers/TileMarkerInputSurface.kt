package com.projectx.markers

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.nxt.MainState
import com.projectx.quest.editor.TilePickResolver
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.button
import com.projectx.ui.backend.dsl.scopes.popStyleColor
import com.projectx.ui.backend.dsl.scopes.pushStyleColor
import com.projectx.ui.backend.dsl.scopes.setCursorPos
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender

object TileMarkerInputSurface {
    private const val FALLBACK_GROUP = "Default"

    private val captureFlags = WindowFlags.NoTitleBar + WindowFlags.NoResize + WindowFlags.NoMove +
        WindowFlags.NoScrollbar + WindowFlags.NoCollapse + WindowFlags.NoBackground + WindowFlags.NoSavedSettings +
        WindowFlags.NoBringToFrontOnFocus + WindowFlags.NoFocusOnAppearing

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() = reportingErrors {
        if (!inGame() || !TileMarkerState.toolActive.value) return@reportingErrors
        val (width, height) = NativeBridge.getDisplaySize()
        ImGuiDsl.setNextWindowPosUnsafe(0f, 0f, ImGuiCond.Always)
        ImGuiDsl.setNextWindowSizeUnsafe(width, height, ImGuiCond.Always)
        ImGuiDsl.window("##tilemarker-capture", captureFlags) {
            setCursorPos(0f, 0f)
            pushStyleColor(ImGuiCol.Button, ImGuiColors.TRANSPARENT)
            pushStyleColor(ImGuiCol.ButtonHovered, ImGuiColors.TRANSPARENT)
            pushStyleColor(ImGuiCol.ButtonActive, ImGuiColors.TRANSPARENT)
            button("##tilemarker-hit", width, height) { reportingErrors(::toggleTileUnderMouse) }
            popStyleColor(3)
        }
    }

    private inline fun reportingErrors(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            println("[TileMarkerInputSurface] render error: ${t.message}")
        }
    }

    private fun toggleTileUnderMouse() {
        val (mouseX, mouseY) = NativeBridge.getMousePos()
        val hit = TilePickResolver.resolve(mouseX, mouseY) ?: return
        val color = TileMarkerState.activeColor()
        TileMarkerStore.toggleTile(targetGroup(color), hit.tileX, hit.tileY, hit.plane, color)
    }

    private fun targetGroup(color: Int): String {
        val chosen = TileMarkerState.activeGroup.value
        if (chosen.isNotBlank() && TileMarkerStore.groups().any { it.name == chosen }) return chosen
        TileMarkerStore.addGroup(FALLBACK_GROUP, color)
        TileMarkerState.activeGroup.value = FALLBACK_GROUP
        return FALLBACK_GROUP
    }

    private fun inGame(): Boolean = runCatching { Bootstrap.client.mainState }.getOrNull() == MainState.LOGGED_IN
}
