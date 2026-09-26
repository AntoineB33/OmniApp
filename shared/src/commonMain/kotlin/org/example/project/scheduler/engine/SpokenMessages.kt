package org.example.project.scheduler.engine

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.platform.GlobalShortcut

/**
 * What a chord's WRITTEN receipt says it did: the shortcut's action, except where the press flips a state and
 * the receipt can say which way — "I'm away" or "I'm back" rather than "I'm away / I'm back". [awayBefore] is the
 * state the press is about to flip (the receipt is raised before the action runs).
 */
internal fun shortcutReceiptAction(shortcut: GlobalShortcut, awayBefore: Boolean): String =
    when (shortcut) {
        GlobalShortcut.ToggleAway -> if (awayBefore) "I'm back" else "I'm away"
        else -> shortcut.action
    }

/**
 * PRD §11/§15: **what a notification SAYS**, where that is not what it shows.
 *
 * A notification is written to be read at leisure — a chord, a task's path, a list of step deadlines — and
 * heard once, in passing, while the user is doing something else. So the spoken half is its own short sentence
 * saying what just happened or what to do ("I'm away", "Current task: Write the report"), never the written text
 * read out. Every phrase the engine speaks for a notification is built here, so they are asked of one place and
 * pinned by `SpokenMessagesTest`. [SILENT] is the one answer that says nothing: the notification whose
 * consequence speaks for itself right after it.
 */
internal object SpokenMessages {
    /** Say nothing for this notification (it is still posted and recorded). */
    const val SILENT: String = ""

    /**
     * The receipt of a system-wide chord, spoken as what the press does NOW — not the chord, which the user has
     * just struck and does not need read back. [awayBefore] and [notificationsOnBefore] are the states the press
     * is about to flip (the receipt is raised before the action runs).
     */
    fun shortcutReceipt(shortcut: GlobalShortcut, awayBefore: Boolean, notificationsOnBefore: Boolean): String =
        when (shortcut) {
            GlobalShortcut.ToggleAway -> if (awayBefore) "I'm back" else "I'm away"
            // The look-away that follows speaks its own cue at once; a receipt before it would say it twice.
            GlobalShortcut.LookAwayNow -> SILENT
            GlobalShortcut.SwitchTask -> "Switching task"
            GlobalShortcut.PickTask -> "Choose a task"
            // Switching ON: the receipt is muted anyway, and the "Notifications on" notice says it from the far
            // side of the flip.
            GlobalShortcut.ToggleNotifications -> if (notificationsOnBefore) "Notifications off" else SILENT
        }

    /** The task to do now — its title alone; the written notification carries its path and step deadlines. */
    fun currentTask(title: String): String = "Current task: $title"

    /** A screen break whose wording no recording fixes (a rest pose): its name, and what it runs out into. */
    fun screenBreak(title: String, followOn: SchedulerDomain.ScreenBreakFollowOn?): String =
        "Break: $title" +
            when (followOn) {
                SchedulerDomain.ScreenBreakFollowOn.UserPeriod -> ", then a no-screen period"
                SchedulerDomain.ScreenBreakFollowOn.BeforeBed -> ", then the hour before bed"
                null -> ""
            }

    /** A ring: what rings, and its name when it has one. */
    fun ring(kind: RingKind, label: String): String {
        val name = label.trim()
        return when (kind) {
            RingKind.Alarm -> if (name.isEmpty()) "Alarm" else "Alarm: $name"
            RingKind.Timer -> if (name.isEmpty()) "Time's up" else "Time's up: $name"
            RingKind.Reminder -> if (name.isEmpty()) "Reminder" else "Reminder: $name"
        }
    }

    const val WIND_DOWN: String = "Time to wind down. Bedtime in one hour."

    const val NOTIFICATIONS_ON: String = "Notifications on"
}
