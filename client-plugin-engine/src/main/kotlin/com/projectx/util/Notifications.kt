package com.projectx.util

import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread

private val notificationIcon: TrayIcon by lazy {
    TrayIcon(trayImage(), "Project X Notification").apply { isImageAutoSize = true }
}

@JvmOverloads
fun showNotification(title: String, message: String, playSound: Boolean = Configuration.config.soundEnabled) {
    val settings = Configuration.config
    if (settings.discordEnabled) DiscordWebhook.sendDiscordNotification(title, message)
    if (!settings.systemTrayEnabled || !SystemTray.isSupported()) return
    thread(isDaemon = true, name = "tray-notification") {
        runCatching {
            val tray = SystemTray.getSystemTray()
            if (tray.trayIcons.isEmpty()) tray.add(notificationIcon)
            notificationIcon.displayMessage(title, message, TrayIcon.MessageType.INFO)
            if (playSound && settings.soundEnabled) playChime()
        }
    }
}

private fun trayImage(): Image =
    runCatching { DiscordWebhook::class.java.getResource("/icons/logo.png")?.let(ImageIO::read) }.getOrNull()
        ?: drawnTrayImage()

private fun drawnTrayImage(): Image {
    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    val canvas = image.createGraphics()
    canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    canvas.color = Color(52, 152, 219)
    canvas.fillOval(0, 0, 16, 16)
    canvas.color = Color.WHITE
    canvas.fillOval(5, 5, 6, 6)
    canvas.dispose()
    return image
}

private fun playChime() {
    thread(isDaemon = true, name = "notification-sound") {
        val played = runCatching {
            val sound = DiscordWebhook::class.java.getResource("/sounds/notification.wav") ?: return@runCatching false
            val clip = AudioSystem.getClip()
            AudioSystem.getAudioInputStream(sound).use { clip.open(it) }
            clip.start()
            true
        }.getOrDefault(false)
        if (!played) runCatching { Toolkit.getDefaultToolkit().beep() }
    }
}
