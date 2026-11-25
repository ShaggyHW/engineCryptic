package com.projectx.traversal

/**
 * What the script knows about the walking after the route being walked, such as a search whose next walk is only
 * chosen once this one ends. The walker's Powerburst check reads it: without it, a route that ends close by holds the
 * dose although the walking goes on past it. A value is dropped after [EXPIRES_MS], so a script that stops without
 * clearing it cannot leave the walker drinking on later routes.
 */
object WalkOutlook {
    const val EXPIRES_MS = 60_000L

    @Volatile
    private var continuesMs = 0L

    @Volatile
    private var setAt = 0L

    /** The walking goes on for about [ms] of real time after the route being walked ends. */
    @JvmStatic
    fun continuesFor(ms: Long) {
        continuesMs = ms.coerceAtLeast(0)
        setAt = System.currentTimeMillis()
    }

    /** Nothing is known past the route. */
    @JvmStatic
    fun clear() {
        continuesMs = 0
    }

    /** The walking expected after the route in ms, or 0 when none is known. */
    @JvmStatic
    fun continuesMs(): Long {
        val ms = continuesMs
        return if (ms > 0 && System.currentTimeMillis() - setAt <= EXPIRES_MS) ms else 0
    }
}
