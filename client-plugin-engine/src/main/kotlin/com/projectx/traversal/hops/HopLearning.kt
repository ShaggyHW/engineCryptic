package com.projectx.traversal.hops

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Whether this client may write hop knowledge. Switched on by `-Dprojectx.hopLearning=true` or the launcher's
 * `RS3_HOP_LEARNING=1`; the contributor id (16 hex) comes from `-Dprojectx.hopContributor` or `RS3_HOP_CONTRIBUTOR`,
 * else is derived from the user name. Without both, the client only reads the shared data: it never creates the store,
 * records an observation or probes. Read once; a change applies at the next start.
 */
internal object HopLearning {
    const val PROPERTY_ENABLED = "projectx.hopLearning"
    const val PROPERTY_CONTRIBUTOR = "projectx.hopContributor"
    const val ENV_ENABLED = "RS3_HOP_LEARNING"
    const val ENV_CONTRIBUTOR = "RS3_HOP_CONTRIBUTOR"

    private val CONTRIBUTOR_ID = Regex("[0-9a-f]{16}")

    @Volatile
    private var enabled: Boolean? = null

    @Volatile
    private var contributor: String? = null

    @Volatile
    private var storeOverride: Path? = null

    fun enabled(): Boolean {
        enabled?.let { return it }
        val switchedOn = System.getProperty(PROPERTY_ENABLED) == "true" || System.getenv(ENV_ENABLED) == "1"
        val id = (System.getProperty(PROPERTY_CONTRIBUTOR) ?: System.getenv(ENV_CONTRIBUTOR) ?: derivedId())
            ?.takeIf { CONTRIBUTOR_ID.matches(it) }
        contributor = id
        return (switchedOn && id != null).also { enabled = it }
    }

    fun contributor(): String? = if (enabled()) contributor else null

    /** `~/.projectx/hops`, shared by every client on this machine; created on demand. */
    fun storeDir(): Path = storeOverride ?: Paths.get(System.getProperty("user.home"), ".projectx", "hops").also {
        runCatching { Files.createDirectories(it) }
    }

    private fun derivedId(): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(System.getProperty("user.name").toByteArray())
        digest.take(8).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun installForTests(dir: Path?, contributorId: String?) {
        storeOverride = dir
        contributor = contributorId
        enabled = dir != null && contributorId != null
    }
}
