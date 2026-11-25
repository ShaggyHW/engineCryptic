package com.projectx.markers

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.MainState
import com.projectx.markers.TileMarkerStore.Rendered
import com.projectx.quest.editor.TilePickResolver
import com.projectx.script.api.localPlayer
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender
import world.gregs.voidps.type.Tile

object TileMarkerOverlay {
    private const val FINE_PER_TILE = 512
    private const val HALF_TILE = 256f
    private const val FILL_ALPHA = 0x33
    private const val EDGE_THICKNESS = 2f

    @Volatile
    private var toolWasActive = false

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        try {
            val toolActive = TileMarkerState.toolActive.value
            if (toolWasActive && !toolActive) TileMarkerStore.flush()
            toolWasActive = toolActive

            if (!inGame()) return
            val plane = runCatching { localPlayer.tile }.getOrNull()?.plane ?: return
            val visible = TileMarkerStore.visibleOnPlane(plane)

            backgroundDrawList {
                visible.groupBy { it.color }.forEach { (color, tiles) -> drawRegion(color, tiles, plane) }
                visible.filter { it.label.isNotBlank() }.forEach { textOnTile(Tile.of(it.x, it.y, it.plane), it.color, it.label) }
                if (toolActive) drawCursorTile()
            }
        } catch (t: Throwable) {
            println("[TileMarkerOverlay] render error: ${t.message}")
        }
    }

    private fun BackgroundDrawListScope.drawRegion(color: Int, tiles: List<Rendered>, plane: Int) {
        val occupied = tiles.mapTo(HashSet()) { cell(it.x, it.y) }
        val fill = (color and 0x00FFFFFF) or (FILL_ALPHA shl 24)
        for (tile in tiles.distinctBy { cell(it.x, it.y) }) {
            val x = tile.x
            val y = tile.y
            val sw = corner(plane, x, y) ?: continue
            val se = corner(plane, x + 1, y) ?: continue
            val ne = corner(plane, x + 1, y + 1) ?: continue
            val nw = corner(plane, x, y + 1) ?: continue
            convexPolyFilled(floatArrayOf(sw.x, sw.y, nw.x, nw.y, ne.x, ne.y, se.x, se.y), fill)
            if (cell(x - 1, y) !in occupied) line(sw, nw, color, EDGE_THICKNESS)
            if (cell(x + 1, y) !in occupied) line(se, ne, color, EDGE_THICKNESS)
            if (cell(x, y - 1) !in occupied) line(sw, se, color, EDGE_THICKNESS)
            if (cell(x, y + 1) !in occupied) line(nw, ne, color, EDGE_THICKNESS)
        }
    }

    private fun BackgroundDrawListScope.drawCursorTile() {
        val (mouseX, mouseY) = NativeBridge.getMousePos()
        val hit = TilePickResolver.resolve(mouseX, mouseY) ?: return
        val color = TileMarkerState.activeColor()
        val centreX = hit.tileX * FINE_PER_TILE + HALF_TILE
        val centreY = hit.tileY * FINE_PER_TILE + HALF_TILE
        val outline = listOf(-1f to -1f, -1f to 1f, 1f to 1f, 1f to -1f).map { (dx, dy) ->
            WorldToScreen.worldToScreen(Vector3f(centreX + dx * HALF_TILE, centreY + dy * HALF_TILE, hit.heightFine)) ?: return
        }
        val ring = outline + outline.first()
        polyLine(ring.flatMap { listOf(it.x, it.y) }.toFloatArray(), color, 0, EDGE_THICKNESS)
        text(Vector2f(hit.screenX + 12f, hit.screenY - 12f), color, "(${hit.tileX}, ${hit.tileY}, ${hit.plane})")
    }

    private fun corner(plane: Int, x: Int, y: Int): Vector2f? {
        val fineX = x * FINE_PER_TILE
        val fineY = y * FINE_PER_TILE
        val height = HeightMap.fineHeight(plane, fineX, fineY) ?: return null
        return WorldToScreen.worldToScreen(Vector3f(fineX.toFloat(), fineY.toFloat(), height.toFloat()))
    }

    private fun cell(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun inGame(): Boolean = runCatching { Bootstrap.client.mainState }.getOrNull() == MainState.LOGGED_IN
}
