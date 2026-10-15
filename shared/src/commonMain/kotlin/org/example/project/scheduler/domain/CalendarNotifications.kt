package org.example.project.scheduler.domain

import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.state.NotificationLogEntry

/**
 * User rule 2026-10-13: **the notifications a calendar window shows as chips** — *"Add configurations in the calendar
 * window to show a chip for all the notifications that are selected in a drop-down menu."* A chip is a mark on the
 * timeline at the instant a notification was posted, with its title.
 *
 * Which notifications a window shows is that WINDOW's configuration ([WindowConfig]) — there can be several calendar
 * windows, each with its own — kept on its placement row like a Search window's: local-only view state, never synced.
 * A notification is named by the id its Search row has ([SearchDomain.notificationIds]), so what the Search window
 * adds and what a calendar shows are the same names.
 */
object CalendarNotifications {

    /**
     * What a calendar window keeps of its own: the notifications it shows, by id, in no particular order — and whether
     * it shows the actions recorded on the computer ([showsActions], user rule 2026-10-15, `InputActivity`).
     */
    data class WindowConfig(val notifications: List<String> = emptyList(), val showsActions: Boolean = false) {
        /** Stored text; blank for the default, so a calendar that shows nothing of the two writes nothing. */
        fun encode(): String =
            listOfNotNull(
                notifications.takeIf { it.isNotEmpty() }?.let { PREFIX + it.distinct().joinToString(SEPARATOR) },
                ACTIONS.takeIf { showsActions },
            ).joinToString(PART_SEPARATOR)

        companion object {
            private const val PREFIX = "notifications="
            private const val SEPARATOR = ","
            private const val ACTIONS = "actions"
            private const val PART_SEPARATOR = ";"

            /**
             * A missing, blank or foreign text is the default: a calendar that shows no notification and no action. A
             * text written before the actions existed is its notifications alone.
             */
            fun decode(text: String?): WindowConfig {
                if (text.isNullOrBlank()) return WindowConfig()
                val parts = text.split(PART_SEPARATOR)
                val notifications =
                    parts.firstOrNull { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.split(SEPARATOR)
                        ?.filter { it.isNotBlank() }?.distinct().orEmpty()
                return WindowConfig(notifications, showsActions = ACTIONS in parts)
            }
        }
    }

    /** One notification as a calendar draws or lists it. */
    data class Chip(val id: String, val timeMillis: Long, val title: String)

    private fun chipOf(id: String, entry: NotificationLogEntry) =
        Chip(id, entry.timeMillis, entry.title.ifBlank { "Notification" })

    /**
     * The chips of the notifications [ids] name, oldest first. An id whose notification has left the log (it is a
     * rolling tail) names nothing and is dropped.
     */
    fun chips(log: List<NotificationLogEntry>, ids: Collection<String>): List<Chip> {
        if (ids.isEmpty() || log.isEmpty()) return emptyList()
        val wanted = ids.toHashSet()
        val all = SearchDomain.notificationIds(log)
        return log.indices.filter { all[it] in wanted }.map { chipOf(all[it], log[it]) }.sortedBy { it.timeMillis }
    }

    /**
     * What the window's drop-down lists, newest first: the last [limit] notifications of the log, and every selected
     * one however old — a checked entry must be there to be unchecked.
     */
    fun options(log: List<NotificationLogEntry>, selected: Collection<String>, limit: Int = OPTION_LIMIT): List<Chip> {
        if (log.isEmpty()) return emptyList()
        val wanted = selected.toHashSet()
        val all = SearchDomain.notificationIds(log)
        val from = (log.size - limit).coerceAtLeast(0)
        return log.indices.filter { it >= from || all[it] in wanted }.map { chipOf(all[it], log[it]) }.asReversed()
    }

    /** How the drop-down names one: the day and time it was posted at, then its title. */
    fun label(chip: Chip, timeZone: kotlinx.datetime.TimeZone): String {
        val at = kotlin.time.Instant.fromEpochMilliseconds(chip.timeMillis).toLocalDateTime(timeZone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return two(at.day) + "/" + two(at.month.ordinal + 1) + " " + two(at.hour) + ":" + two(at.minute) + " · " + chip.title
    }

    /** The chips by the local day they fall on — what a day column reads. */
    fun byDay(chips: List<Chip>, timeZone: kotlinx.datetime.TimeZone): Map<kotlinx.datetime.LocalDate, List<Chip>> =
        chips.groupBy { kotlin.time.Instant.fromEpochMilliseconds(it.timeMillis).toLocalDateTime(timeZone).date }

    /** How many notifications the drop-down lists besides the selected ones: the list is drawn whole when it opens. */
    const val OPTION_LIMIT: Int = 200

    /**
     * User rule 2026-10-14: **the "unfocused notif" window a calendar was opened under** — its tab stands beside that
     * window's on the window bar, a line under the two. Read off what the windows hold, so it comes back after a
     * restart: the notifications window among [searchWindows] (frame id → its configuration) whose added notifications
     * are exactly the ones [calendar] shows. A calendar that shows none is under no window.
     */
    fun notificationsWindowOf(calendar: WindowConfig, searchWindows: List<Pair<String, SearchDomain.Config>>): String? {
        val shown = calendar.notifications.toSet().ifEmpty { return null }
        return searchWindows.firstOrNull { (_, config) ->
            SearchDomain.isNotificationsWindow(config) &&
                config.added.filter { it.startsWith(SearchDomain.Kind.Notification.name + "/") }.map { it.substringAfter('/') }.toSet() == shown
        }?.first
    }

    /**
     * User rule 2026-10-14: **the stretch of time the calendar under an "unfocused notif" window opens on** — from the
     * first of its [chips] to the last, and the now-line ([nowMillis]) included wherever it falls. Null for a calendar
     * that shows none: it opens as any other.
     */
    fun openingSpan(chips: List<Chip>, nowMillis: Long): Pair<Long, Long>? {
        if (chips.isEmpty()) return null
        return minOf(chips.minOf { it.timeMillis }, nowMillis) to maxOf(chips.maxOf { it.timeMillis }, nowMillis)
    }

    /** The ids of the notifications among the Search window's added elements [added] — what its action hands a calendar. */
    fun idsOf(added: List<SearchDomain.Result>): List<String> =
        added.filterIsInstance<SearchDomain.ItemResult>().filter { it.kind == SearchDomain.Kind.Notification }.map { it.id }
}
