package com.projectx.quest.runtime

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/** Where the current instance starts, so instance-relative guide coordinates can become world tiles. */
object QuestInstanceTracker {
    private const val INSTANCE_SPACE_X = 6400
    private const val NEW_INSTANCE_DISTANCE = 128
    private const val PLANE_SETTLE_TICKS = 15

    /** [fineZ] is the player's fine height at capture, so renderers can sit instance markers on the ground. */
    data class Origin(
        val x: Int,
        val y: Int,
        val plane: Int,
        val fineZ: Float = 0f,
    )

    @Volatile var origin: Origin? = null
        private set

    private var ticksSinceCapture = 0

    val isInInstance: Boolean get() = origin != null

    /** Reading the client while it is detached throws; the caller's catch absorbs that. */
    fun tick() {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) return reset()
        val here = playerTile() ?: return
        val known = origin
        when {
            here.x < INSTANCE_SPACE_X -> if (known != null) reset()
            known == null -> capture(here)
            else -> {
                ticksSinceCapture++
                if (movedToAnotherInstance(here, known) || planeSettled(here, known)) capture(here)
            }
        }
    }

    private fun playerTile(): Tile? = try {
        localPlayer.tile
    } catch (_: Throwable) {
        null
    }

    fun reset() {
        origin = null
        ticksSinceCapture = 0
    }

    private fun movedToAnotherInstance(here: Tile, known: Origin) =
        max(abs(here.x - known.x), abs(here.y - known.y)) > NEW_INSTANCE_DISTANCE

    /** The client nudges the plane shortly after entry while the player stays on the entry tile. */
    private fun planeSettled(here: Tile, known: Origin) =
        ticksSinceCapture <= PLANE_SETTLE_TICKS && here.x == known.x && here.y == known.y && here.level != known.plane

    private fun capture(here: Tile) {
        val groundZ = runCatching { localPlayer.graphNode.tileFine.z }.getOrDefault(0f)
        origin = Origin(here.x, here.y, here.level, groundZ)
        ticksSinceCapture = 0
    }
}
