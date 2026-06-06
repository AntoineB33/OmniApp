package org.example.project.scheduler.platform

import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * PRD §11 Notifications (desktop): show a tray balloon notification. The tray icon is created lazily
 * and reused so repeated notifications don't stack up extra icons. Wrapped in `runCatching` because
 * the system tray is unsupported on some platforms/headless setups.
 */
private val trayIcon: TrayIcon? by lazy {
    runCatching {
        if (!SystemTray.isSupported()) return@runCatching null
        // A small solid square keeps a valid tray icon present without shipping an image asset.
        val size = SystemTray.getSystemTray().trayIconSize
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().run {
                color = Color(0x1A73E8) // app accent blue
                fillRect(0, 0, width, height)
                dispose()
            }
        }
        val icon = TrayIcon(image, "OmniApp").apply { isImageAutoSize = true }
        SystemTray.getSystemTray().add(icon)
        icon
    }.getOrNull()
}

actual fun sendSystemNotification(title: String, message: String) {
    runCatching {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }
}
