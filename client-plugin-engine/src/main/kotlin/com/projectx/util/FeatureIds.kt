package com.projectx.util

import world.gregs.voidps.gameval.Gameval

class FeatureIds<T : Any>(private val feature: String, resolve: () -> T) {

    private val outcome: Result<T> = runCatching(resolve)

    @Volatile
    private var announced = false

    val disabled: Boolean get() = outcome.isFailure

    fun get(): T? {
        val failure = outcome.exceptionOrNull() ?: return outcome.getOrNull()
        if (!announced) {
            announced = true
            println("[$feature] DISABLED: ${failure.message}")
            println("[$feature]   gameval names came from ${Gameval.sourceDescription}")
            println("[$feature]   the feature stays off for this session; re-dump the gamevals and restart")
        }
        return null
    }
}
