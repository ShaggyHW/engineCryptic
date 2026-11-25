package com.projectx.ui

import java.io.File

/** Whether rare drops from store scripts go to the website's drop feed and its Discord channel. On unless turned off. */
internal object RareDropsPreference {
    private val off = File(System.getProperty("user.home"), ".projectx/website-rare-drops-off")

    val enabled: Setting<Boolean> = setting(!off.exists())

    fun save(on: Boolean) {
        if (on) off.delete() else off.apply { parentFile.mkdirs() }.createNewFile()
    }
}
