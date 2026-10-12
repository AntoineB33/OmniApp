package org.example.project.scheduler.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_input_requirements.md`, its last paragraph: *"When an orange outlined block/chip becomes blue
 * outlined, that means that the configuration that created this orange outlined block/chip gets an 'exception' info
 * (e.g., timer every day at 10h, with an exception for the occurrence of tomorrow that got dragged 1h later)."*
 *
 * **The exceptions of each configuration that lays orange blocks** — the Sleep schedule (its nights and its periods
 * before bed), a panel the user made repeat, an alarm — read off what the edit itself stored: the occurrence the
 * pattern no longer lays (`SleepSchedule.skippedWakeEpochDays` / `skippedBeforeBedEpochDays`, `PanelRepeat.skipped`,
 * `AlarmEntry.skippedEpochDays`) and, where the block the hand made of it still stands, where it is now
 * (`TaskPanel.patternOrigin`, `AlarmEntry.isolatedFrom`). ONE funnel: every window that shows a configuration asks
 * here and draws [text].
 */
object PatternExceptions {
    /**
     * One occurrence that left its pattern: [occurrence] is where the pattern would have laid it, [movedTo] where the
     * block made of it stands now — null once it is gone (removed, or removed since). [label] names which of the
     * configuration's patterns it was, where it has several (the Sleep schedule's night, its period before bed).
     */
    data class Entry(
        val occurrence: TaskTimeRange,
        val movedTo: TaskTimeRange?,
        val label: String = "",
        /**
         * The block made of it, as "Blocks on the calendar" lists it ([SearchDomain.CalendarBlock.id]) — what lets the
         * configuration draw its exception as that block, fields and all. Null with [movedTo].
         */
        val blockId: String? = null,
    )

    /** Where the block a hand made of an occurrence stands, and which block it is. */
    private class Standing(val range: TaskTimeRange, val blockId: String)

    private const val DAY_MILLIS = 86_400_000L

    private fun dayStart(epochDay: Long, timeZone: TimeZone): Long =
        LocalDate.fromEpochDays(epochDay.toInt()).atStartOfDayIn(timeZone).toEpochMilliseconds()

    /** Where the block a hand made of the occurrence [originId] stands: from its first piece to its last, hidden ones included. */
    private fun standing(
        state: SchedulerState,
        originId: String,
        /**
         * An exception made before 2026-10-11 stored no link: the period of [legacyKind] the user placed over
         * [legacyOver] (the occurrence's own span) is read as the one made of it — a night edited in place still
         * says where it is, rather than "removed".
         */
        legacyKind: String? = null,
        legacyOver: TaskTimeRange? = null,
    ): Standing? {
        val all = state.panels + state.hiddenPanels.map { it.panel }
        val pieces =
            all.filter { it.patternOrigin == originId }.ifEmpty {
                if (legacyKind == null || legacyOver == null) emptyList()
                else all.filter {
                    it.patternOrigin.isBlank() && it.restrictiveKind == legacyKind && SchedulerDomain.isUserPlaced(it) && !it.tmFill &&
                        it.startEpochMillis < legacyOver.endEpochMillis && legacyOver.startEpochMillis < it.endEpochMillis
                }
            }
        if (pieces.isEmpty()) return null
        // The block is the piece that still stands on the calendar, where one does (a hidden piece has no row).
        val shown = pieces.filter { piece -> state.panels.any { it.id == piece.id } }.ifEmpty { pieces }
        return Standing(
            TaskTimeRange(pieces.minOf(TaskPanel::startEpochMillis), pieces.maxOf(TaskPanel::endEpochMillis)),
            shown.minBy(TaskPanel::startEpochMillis).id,
        )
    }

    /** The Sleep schedule's exceptions: the nights, then the periods before bed, each in the timeline's order. */
    fun ofSleep(state: SchedulerState, timeZone: TimeZone): List<Entry> {
        val sleep = state.sleep ?: return emptyList()
        if (sleep.skippedWakeEpochDays.isEmpty() && sleep.skippedBeforeBedEpochDays.isEmpty()) return emptyList()
        // The schedule as it lays every occurrence: the one asked about is skipped in the stored one.
        val whole = sleep.copy(skippedWakeEpochDays = emptySet(), skippedBeforeBedEpochDays = emptySet())
        val nights =
            sleep.skippedWakeEpochDays.sorted().mapNotNull { day ->
                val at = dayStart(day, timeZone)
                val id = "sleep/$day"
                SchedulerDomain.sleepPanels(whole, at - DAY_MILLIS, at + 2 * DAY_MILLIS, timeZone).firstOrNull { it.id == id }?.let {
                    val was = TaskTimeRange(it.startEpochMillis, it.endEpochMillis)
                    val now = standing(state, id, PeriodKinds.SLEEP, was)
                    Entry(was, now?.range, PeriodKinds.periodTitle(PeriodKinds.SLEEP), now?.blockId)
                }
            }
        val evenings =
            sleep.skippedBeforeBedEpochDays.sorted().mapNotNull { day ->
                val at = dayStart(day, timeZone)
                val id = SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX + day
                SchedulerDomain.beforeBedPanels(whole, at - 2 * DAY_MILLIS, at + 2 * DAY_MILLIS, timeZone).firstOrNull { it.id == id }?.let {
                    val was = TaskTimeRange(it.startEpochMillis, it.endEpochMillis)
                    val now = standing(state, id, PeriodKinds.BEFORE_BED, was)
                    Entry(was, now?.range, PeriodKinds.periodTitle(PeriodKinds.BEFORE_BED), now?.blockId)
                }
            }
        return nights + evenings
    }

    /** The exceptions of the pattern the panel [panelId] carries — or is an occurrence of. */
    fun ofPanel(state: SchedulerState, panelId: String?, timeZone: TimeZone): List<Entry> {
        val baseId = panelId?.let { PanelRepeats.baseIdOf(it) ?: it } ?: return emptyList()
        val base = state.panels.firstOrNull { it.id == baseId } ?: return emptyList()
        val rule = base.repeat ?: return emptyList()
        if (rule.skipped.isEmpty()) return emptyList()
        val whole = base.copy(repeat = rule.copy(skipped = emptySet()))
        return rule.skipped.sorted().mapNotNull { k ->
            PanelRepeats.occurrence(whole, k, timeZone)?.let {
                val now = standing(state, it.id)
                Entry(TaskTimeRange(it.startEpochMillis, it.endEpochMillis), now?.range, blockId = now?.blockId)
            }
        }
    }

    /** The exceptions of the alarm [alarmId]: the days it does not ring on, and the isolated ring each became. */
    fun ofAlarm(state: SchedulerState, alarmId: String, timeZone: TimeZone): List<Entry> {
        val alarm = state.alarms.firstOrNull { it.id == alarmId } ?: return emptyList()
        return alarm.skippedEpochDays.sorted().map { day ->
            val at = dayStart(day, timeZone) + alarm.timeOfDayMinutes * 60_000L
            val ring = state.alarms.firstOrNull { it.isolatedFrom == alarm.id && it.onlyOnEpochDay == day }
            Entry(
                TaskTimeRange(at, at),
                ring?.let { (dayStart(day, timeZone) + it.timeOfDayMinutes * 60_000L).let { m -> TaskTimeRange(m, m) } },
                blockId = ring?.let { SearchDomain.alarmBlockId(it.id) },
            )
        }
    }

    /**
     * The exceptions of the reminder [reminderId] (anomaly 2026-10-11: *"I added the reminder …, selected one block,
     * changed 14:50 to 14:51, but I didn't see an exception info under the Every 1 day configuration"*): the tags of
     * its cadence a hand put elsewhere. Such a tag keeps the id of the day it stood for (`chore/<reminder>/d<day>`)
     * and is pinned where it was put, so the occurrence is that day at the reminder's time and the tag itself is
     * where it went. A tag pinned where the cadence laid it is no exception, nor is one added by hand
     * (`chore-manual/…`, which never was an occurrence).
     */
    fun ofReminder(state: SchedulerState, reminderId: String, timeZone: TimeZone): List<Entry> {
        if (reminderId.isBlank()) return emptyList()
        val chore = SchedulerDomain.assignReminderIds(state.chores).firstOrNull { it.id == reminderId } ?: return emptyList()
        val prefix = "chore/$reminderId/"
        return state.panels.asSequence()
            .filter { it.chore && it.pinned && it.id.startsWith(prefix) }
            .map { tag ->
                // The day it stood for is in its id (`d<day>`). A tag made before the ids named a day
                // (`chore/<reminder>/<offset>`, found on the release account: the user's own 14:50 -> 14:51) names
                // none: it is read as an occurrence of the day it stands on.
                val day =
                    tag.id.removePrefix(prefix).takeIf { it.startsWith("d") }?.drop(1)?.toLongOrNull()
                        ?: Instant.fromEpochMilliseconds(tag.startEpochMillis).toLocalDateTime(timeZone).date.toEpochDays().toLong()
                tag to day
            }
            .mapNotNull { (tag, day) ->
                val at = dayStart(day, timeZone) + chore.timeOfDayMinutes * 60_000L
                if (tag.startEpochMillis == at) null
                else Entry(TaskTimeRange(at, at), TaskTimeRange(tag.startEpochMillis, tag.startEpochMillis), chore.title, tag.id)
            }
            .sortedBy { it.occurrence.startEpochMillis }
            .toList()
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun date(millis: Long, timeZone: TimeZone): String {
        val d = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone).date
        return two(d.dayOfMonth) + "/" + two(d.monthNumber) + "/" + d.year
    }

    private fun time(millis: Long, timeZone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
        return two(t.hour) + ":" + two(t.minute)
    }

    /** `11/10/2026 05:15–12:15`, the end's date said only where it is another day; an instant reads as its time alone. */
    private fun span(range: TaskTimeRange, timeZone: TimeZone, againstDate: String? = null): String {
        val day = date(range.startEpochMillis, timeZone)
        val head = (if (day == againstDate) "" else "$day ") + time(range.startEpochMillis, timeZone)
        if (range.endEpochMillis <= range.startEpochMillis) return head
        val endDay = date(range.endEpochMillis, timeZone)
        return head + "–" + (if (endDay == day) "" else "$endDay ") + time(range.endEpochMillis, timeZone)
    }

    /** How far the start moved: `1 h later`, `30 min earlier`, `1 h 15 min later`; blank where it did not. */
    private fun shift(fromMillis: Long, toMillis: Long): String {
        val minutes = (toMillis - fromMillis) / 60_000L
        if (minutes == 0L) return ""
        val abs = kotlin.math.abs(minutes)
        val length = listOfNotNull(
            (abs / 1440).takeIf { it > 0 }?.let { "$it d" },
            (abs % 1440 / 60).takeIf { it > 0 }?.let { "$it h" },
            (abs % 60).takeIf { it > 0 }?.let { "$it min" },
        ).joinToString(" ")
        return length + if (minutes > 0) " later" else " earlier"
    }

    /**
     * What a configuration says of one exception: the occurrence, then what became of it —
     * `11/10/2026 05:15–12:15 → 06:15–12:15 (1 h later)`, or `… → removed`. [labelled] puts the pattern's name first,
     * where the configuration lays several.
     */
    fun text(entry: Entry, timeZone: TimeZone, labelled: Boolean = false): String {
        val head = occurrenceText(entry, timeZone, labelled)
        val to = entry.movedTo ?: return "$head → removed"
        val moved = shift(entry.occurrence.startEpochMillis, to.startEpochMillis)
        return head + " → " + span(to, timeZone, againstDate = date(entry.occurrence.startEpochMillis, timeZone)) +
            (if (moved.isBlank()) "" else " ($moved)")
    }

    /** Where the pattern would have laid the occurrence: `11/10/2026 05:15–12:15`, the pattern's name first where [labelled]. */
    fun occurrenceText(entry: Entry, timeZone: TimeZone, labelled: Boolean = false): String {
        val was = span(entry.occurrence, timeZone)
        return if (labelled && entry.label.isNotBlank()) entry.label + " " + was else was
    }

    /** Whether [entries] are of more than one pattern, so that each is said with its pattern's name. */
    fun labelled(entries: List<Entry>): Boolean = entries.mapTo(HashSet()) { it.label }.size > 1

    /** [text] of each of [entries], the name of the pattern said where they are of more than one. */
    fun texts(entries: List<Entry>, timeZone: TimeZone): List<String> {
        val labelled = entries.mapTo(HashSet()) { it.label }.size > 1
        return entries.map { text(it, timeZone, labelled) }
    }
}
