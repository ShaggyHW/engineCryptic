package com.projectx.util

import java.util.Properties

object EngineBuild {

    private const val UNKNOWN = "unknown"

    private val stamp: Properties by lazy {
        Properties().apply {
            EngineBuild::class.java.getResourceAsStream("/engine-build.properties")?.use { load(it) }
        }
    }

    val commit: String get() = stamp.getProperty("commit") ?: UNKNOWN

    val builtAt: String get() = stamp.getProperty("builtAt") ?: UNKNOWN

    fun describe(): String = "commit $commit built $builtAt"
}
