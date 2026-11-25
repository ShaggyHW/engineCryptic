package com.projectx.quest.overlay

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.MainState
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile

/** Reads the overlay and editor share; a client that is absent or mid-transition reads as "nothing there". */
internal object LiveWorld {
    private const val FINE_PER_TILE = 512
    private const val TILE_MIDDLE = 256

    val loggedIn: Boolean
        get() = NativeAccess.isAttached && runCatching { Bootstrap.client.mainState == MainState.LOGGED_IN }.getOrDefault(false)

    fun playerTile(): Tile? = runCatching { localPlayer.tile }.getOrNull()

    fun playerFineZ(): Float? = runCatching { localPlayer.graphNode.tileFine.z }.getOrNull()

    fun centreFine(tile: Int): Int = tile * FINE_PER_TILE + TILE_MIDDLE

    fun groundZ(plane: Int, x: Int, y: Int): Float? = HeightMap.fineHeight(plane, centreFine(x), centreFine(y))?.toFloat()

    fun project(x: Int, y: Int, z: Float): Vector2f? =
        WorldToScreen.getEstimatedTileCenter(Vector3f(centreFine(x).toFloat(), centreFine(y).toFloat(), z))
}
