package com.projectx.util.spec

import com.projectx.util.DiscordWebhook
import com.projectx.util.showNotification

/** Run only through [IsolatedHome]; the config file in that home decides where, and whether, anything is sent. */
class NotificationScenarios {

    fun sendWebhook(title: String, message: String) = DiscordWebhook.sendDiscordNotification(title, message)

    fun notify(title: String, message: String) = showNotification(title, message, false)
}
