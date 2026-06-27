package org.example.project.scheduler.platform

import java.awt.MouseInfo
import java.awt.Point

/** PRD §15: the desktop build is not the phone. */
actual fun currentDeviceKind(): DeviceKind = DeviceKind.Desktop

// PRD §15: how long without detected pointer movement before the desktop screen counts as inactive.
private const val IDLE_THRESHOLD_MILLIS = 60_000L

private val lock = Any()
private var lastPoint: Point? = null
private var lastActivityMillis: Long = 0L

/**
 * PRD §15: the desktop screen is "active" when the pointer has moved within [IDLE_THRESHOLD_MILLIS]. The
 * pointer is sampled on each call (the presence heartbeat polls it ~every 30 s); a moved pointer refreshes
 * the activity timestamp. Headless environments (tests, locked session) report inactive.
 */
actual fun isScreenActive(): Boolean = synchronized(lock) {
    val now = System.currentTimeMillis()
    try {
        val point = MouseInfo.getPointerInfo()?.location
        when {
            // First sample since launch: assume the user just started using the machine.
            lastActivityMillis == 0L -> {
                lastActivityMillis = now
                lastPoint = point
            }
            point != null && point != lastPoint -> {
                lastActivityMillis = now
                lastPoint = point
            }
        }
        now - lastActivityMillis < IDLE_THRESHOLD_MILLIS
    } catch (t: Throwable) {
        false
    }
}
