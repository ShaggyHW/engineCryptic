package com.projectx.game.nxt

/** Counts every synthetic action fired, so a prediction built on one action can tell when another has replaced it. */
internal object SyntheticFires {
    @Volatile
    var count = 0L
        private set

    /** Synthetic actions other than a walk click: a walk's predicted route holds only while this stands still. */
    @Volatile
    var others = 0L
        private set

    fun fired(walk: Boolean = false) {
        count++
        if (!walk) others++
    }
}
