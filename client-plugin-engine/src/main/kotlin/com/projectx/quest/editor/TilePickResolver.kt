package com.projectx.quest.editor

import com.projectx.quest.overlay.LiveWorld

/** Finds the tile under the mouse by projecting the player's surroundings; cheap enough to call every frame. */
object TilePickResolver {
    private const val PICK_RADIUS_PX = 64f

    /** [instance] means [tileX]/[tileY] are offsets from the instance origin; the resolver never sets it. */
    data class Hit(
        val tileX: Int, val tileY: Int, val plane: Int, val heightFine: Float,
        val screenX: Float, val screenY: Float, val instance: Boolean = false,
    )

    fun resolve(cursorX: Float, cursorY: Float, range: Int = 30): Hit? {
        if (!LiveWorld.loggedIn) return null
        val centre = LiveWorld.playerTile() ?: return null
        val plane = centre.plane
        val standingZ = LiveWorld.playerFineZ() ?: 0f
        var nearest: Hit? = null
        var nearestSq = PICK_RADIUS_PX * PICK_RADIUS_PX
        for (y in centre.y - range..centre.y + range) {
            for (x in centre.x - range..centre.x + range) {
                val z = LiveWorld.groundZ(plane, x, y) ?: standingZ
                val point = LiveWorld.project(x, y, z) ?: continue
                val dx = point.x - cursorX
                val dy = point.y - cursorY
                val distanceSq = dx * dx + dy * dy
                if (distanceSq < nearestSq) {
                    nearestSq = distanceSq
                    nearest = Hit(x, y, plane, z, point.x, point.y)
                }
            }
        }
        return nearest
    }
}
