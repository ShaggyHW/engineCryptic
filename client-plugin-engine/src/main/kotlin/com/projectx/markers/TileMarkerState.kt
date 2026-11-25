package com.projectx.markers

import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.setting

object TileMarkerState {
    val toolActive: ImGuiState<Boolean> = setting(false)
    val activeGroup: ImGuiState<String> = setting("")

    val colorR: ImGuiState<Float> = setting(0f)
    val colorG: ImGuiState<Float> = setting(1f)
    val colorB: ImGuiState<Float> = setting(1f)

    val newGroupName: ImGuiState<String> = setting("")

    val bulkX1: ImGuiState<Int> = setting(0)
    val bulkY1: ImGuiState<Int> = setting(0)
    val bulkX2: ImGuiState<Int> = setting(0)
    val bulkY2: ImGuiState<Int> = setting(0)

    fun activeColor(): Int = ImGuiColors.rgba(colorR.value, colorG.value, colorB.value)

    fun loadPickerColor(packed: Int) {
        colorR.value = channel(packed, 0)
        colorG.value = channel(packed, 8)
        colorB.value = channel(packed, 16)
    }

    private fun channel(packed: Int, shift: Int): Float = ((packed ushr shift) and 0xFF) / 255f
}
