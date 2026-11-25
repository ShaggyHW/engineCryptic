package com.projectx.puzzle

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.script.api.interfaces
import com.projectx.script.api.varcs
import com.projectx.script.api.varps

/** The puzzle readers' only window on the client: answers "closed" or null whenever there is nothing to read. */
internal object PuzzleClient {

    private val attached: Boolean get() = NativeAccess.isAttached

    val loggedIn: Boolean get() = attached && Bootstrap.client.mainState == MainState.LOGGED_IN

    fun isOpen(interfaceId: Int): Boolean = attached && interfaces.isOpen(interfaceId)

    fun component(interfaceId: Int, componentId: Int): InterfaceComponent? =
        if (attached) interfaces.getComponent(interfaceId, componentId) else null

    fun text(interfaceId: Int, componentId: Int): String? = component(interfaceId, componentId)?.text

    fun graphic(interfaceId: Int, componentId: Int): Int? = component(interfaceId, componentId)?.graphicId

    fun rect(interfaceId: Int, componentId: Int): ScreenRect? =
        component(interfaceId, componentId)?.screenRect?.takeIf { it.width > 0 && it.height > 0 }

    fun playerVarbit(id: Int): Int? = if (attached) varps.getVarBit(id) else null

    fun playerVar(id: Int): Int? = if (attached) varps.getVar(id) else null

    fun clientVarbit(id: Int): Int? = if (attached) varcs.getVarBit(id) else null
}
