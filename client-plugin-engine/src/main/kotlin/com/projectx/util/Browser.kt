package com.projectx.util

import java.awt.Desktop
import java.net.URI

object Browser {

    private val webSchemes = setOf("http", "https")

    fun open(url: String): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        if (!target.isAbsolute || target.scheme !in webSchemes) return false
        return viaDesktop(target) || viaPlatformOpener(url)
    }

    private fun viaDesktop(target: URI): Boolean = runCatching {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
        desktop.browse(target)
        true
    }.getOrDefault(false)

    private fun viaPlatformOpener(url: String): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }
        return runCatching { ProcessBuilder(command).start() }.isSuccess
    }
}
