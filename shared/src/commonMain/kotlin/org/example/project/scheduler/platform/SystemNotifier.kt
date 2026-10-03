package org.example.project.scheduler.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PRD §11 (user rule 2026-10-03): **where a click on a notification takes the user.** Every click brings the app to
 * the front; [Calendar] also opens the calendar, or brings it back and focuses it — what a notification about the
 * schedule is for: the task to do now and the screen breaks are read on the calendar.
 */
enum class NotificationTarget { App, Calendar }

/** One click on a notification, numbered so the app answers each click once. */
data class NotificationClick(val seq: Long, val target: NotificationTarget)

/**
 * PRD §11: **the clicks on the app's notifications**, as each platform reports them — the desktop tray balloon's
 * action, Android's content intent landing in the activity. The one channel `App` reads them from, so the answer to
 * a click is written once for every platform.
 *
 * Held as the latest click not answered yet (a [StateFlow], not an event stream): on Android a click can launch the
 * activity cold, before `App` has composed to listen, and it must still be answered once it has. `App` answers and
 * [consume]s it.
 */
object NotificationClicks {
    private var nextSeq: Long = 1
    private val _pending = MutableStateFlow<NotificationClick?>(null)
    val pending: StateFlow<NotificationClick?> = _pending.asStateFlow()

    /** A click on a notification posted for [target]. */
    fun clicked(target: NotificationTarget) {
        _pending.value = NotificationClick(nextSeq++, target)
    }

    /** [click] has been answered; a newer one stays pending. */
    fun consume(click: NotificationClick) {
        _pending.compareAndSet(click, null)
    }
}

/**
 * PRD §11 Notifications: post a system notification with [title] and [message]; a click on it brings the app to the
 * front and reports [target] ([NotificationClicks]). Best-effort — a platform without notification support (or where
 * the user denied it) silently does nothing.
 */
expect fun sendSystemNotification(title: String, message: String, target: NotificationTarget)

/**
 * PRD §11 Notifications: **clear the notifications this app has already posted** and that the OS is still
 * showing.
 *
 * The companion of muting (`SchedulerState.notificationsEnabled`): switching notifications off has to answer
 * the pile already sitting in the shade as well as the ones still to come, or "notifications off" would leave
 * the interruption the user pressed the switch about still on screen. Nothing about the app's own record is
 * touched — the History window's Notifications column keeps every entry, muted or cleared.
 *
 * Best-effort and platform-shaped, exactly like [sendSystemNotification]: Android and iOS can withdraw a
 * delivered notification, a desktop tray balloon cannot be recalled once shown (it fades on its own), so
 * that actual is deliberately a no-op.
 */
expect fun cancelSystemNotifications()
