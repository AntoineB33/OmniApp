package org.example.project.scheduler.platform

/**
 * PRD §15 (Android): there is no system sleep/wake event log a normal app can read (the desktop reads the
 * Windows Kernel-Power events; Android offers no equivalent). Return null — the documented "can't determine"
 * path — so each side task's last-rest time is left unseeded and shown at the now-line, exactly as on a
 * non-Windows desktop.
 */
actual fun lastWakeAfterLongSleepMillis(minSleepMillis: Long): Long? = null
