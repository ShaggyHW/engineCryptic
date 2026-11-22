package com.projectx.script.api

import com.projectx.game.interfaces.IFSlot
import world.gregs.voidps.gameval.Gameval

private val continueButtons by lazy {
    listOf("chat_v2_left", "chat_v2_right", "chat_v2_pair", "mesbox_v2", "objbox_v2")
        .map { Gameval.requireComponentHash("$it:click_continue") }
}

/** Clicks "Click here to continue" on whichever chat box is showing. Returns false when none is. */
fun continueDialogue(): Boolean = continueButtons.any { hash ->
    interfaces.getComponent(hash ushr 16, hash and 0xFFFF) != null && IFSlot(hash ushr 16, hash and 0xFFFF).dialogueContinue()
}
