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
        // PRD §11 (user rule 2026-10-03): a click on the balloon brings the app to the front and answers the
        // balloon's target. AWT reports it as the icon's action — which a double-click on the icon itself also is,
        // so the last balloon's target is taken once, and only while it is recent; otherwise the click just
        // brings the app up.
        icon.addActionListener {
            val now = System.currentTimeMillis()
            val target =
                lastBalloon?.takeIf { now - it.second <= BALLOON_CLICK_REACH_MILLIS }?.first ?: NotificationTarget.App
            lastBalloon = null
            DesktopAppWindow.bringToFront()
            NotificationClicks.clicked(target)
        }
        SystemTray.getSystemTray().add(icon)
        icon
    }.getOrNull()
}

/** The last balloon's target and when it was shown — what a click on the tray icon answers ([trayIcon]). */
@Volatile
private var lastBalloon: Pair<NotificationTarget, Long>? = null

/** How long after a balloon a click on the tray icon is still taken as a click on that balloon. */
private const val BALLOON_CLICK_REACH_MILLIS: Long = 2 * 60 * 1000L

/**
 * The desktop app's own window, as `desktopApp`'s `main` hands it over: what a click on a notification brings to the
 * front. A no-op until the window exists (a headless host, a test).
 */
object DesktopAppWindow {
    @Volatile
    var bringToFront: () -> Unit = {}

    /**
     * Ask the OS for the foreground for [window], the way that is granted to a process that is NOT in front (the
     * task picker's own request, `TaskPickerOverlay.jvm.kt`): a chord swallowed by our keyboard hook is not our
     * window having been typed into, so a plain `toFront` puts the window on top and leaves the KEYBOARD with the
     * application the user was in. Best-effort, and nothing off Windows.
     */
    fun takeForeground(window: java.awt.Window) = org.example.project.ui.requestForeground(window)
}

actual fun sendSystemNotification(title: String, message: String, target: NotificationTarget) {
    runCatching {
        val icon = trayIcon ?: return
        // This device's sound is off (user rule 2026-10-18): a tray balloon cannot be shown without the shell's sound
        // (AWT has no "no sound" flag), so none is shown; the History window and the notifications window still have it.
        if (AppVolume.muted) return
        lastBalloon = target to System.currentTimeMillis()
        icon.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }
}

/**
 * PRD §11 (desktop): a **no-op**, and the one platform where that is the whole truth. A tray balloon is fire
 * and forget — AWT hands it to the shell and keeps no handle to withdraw it — so there is nothing left for
 * the app to clear once it has been shown; the balloon fades on its own. Muting still stops the next one.
 */
actual fun cancelSystemNotifications() = Unit
