package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.QuotaDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * The calendar right-click the Search window being drawn came from ([SearchDomain.Config.calendarClickMillis], a window
 * opened from it holding the same: [SearchDomain.linkedConfig]) — provided by the Search window and by its two
 * configurations windows for what they draw, null everywhere else.
 */
val LocalCalendarClickMillis = compositionLocalOf<Long?> { null }

/**
 * **"Right-click time"** (user rules 2026-10-07 and 2026-10-18: *"the Time field must have a button next to it to set it
 * to the position of the right-click on the timeline"*): the ONE button beside every time field of a Search window
 * opened from a calendar right-click — and of the windows opened from that window — which sets the field to where the
 * right-click was, to the second. Absent where there was no right-click ([LocalCalendarClickMillis]). [onSet] gets the
 * instant; a field holding a time of day takes the right-click's ([minuteOfDayOf]).
 */
@Composable
internal fun RightClickTimeButton(enabled: Boolean = true, onSet: (Long) -> Unit) {
    val click = LocalCalendarClickMillis.current ?: return
    FrameButton("Right-click time", enabled = enabled) { onSet(click / 1_000L * 1_000L) }
}

/** The app's clock (the time simulation's when it runs) — what "Now" beside a time field reads. `App` provides it. */
val LocalAppNowMillis = compositionLocalOf<() -> Long> { { kotlin.time.Clock.System.now().toEpochMilliseconds() } }

/**
 * **The buttons beside every time field** — anomaly 2026-10-19 (*"the Now button is in 'Add to the calendar' but not in
 * … 'Date and time'"*): "Now" stood beside "Add to the calendar"'s fields alone, written there by hand, while "Right-click
 * time" stood beside them all. ONE place draws both: "Now" sets the field to the clock's instant (to the second), and
 * [RightClickTimeButton] follows where there was a right-click. [onSet] gets the instant, as [RightClickTimeButton]'s does;
 * [rightClickEnabled] greys the right-click alone (a field already there).
 */
@Composable
internal fun InstantButtons(enabled: Boolean = true, rightClickEnabled: Boolean = enabled, onSet: (Long) -> Unit) {
    val now = LocalAppNowMillis.current
    FrameButton("Now", enabled = enabled) { onSet(now() / 1_000L * 1_000L) }
    RightClickTimeButton(enabled = rightClickEnabled, onSet = onSet)
}

/** The minute of the day [millis] falls on here — what a time-of-day field takes of a right-click. */
internal fun minuteOfDayOf(millis: Long, tz: TimeZone = TimeZone.currentSystemDefault()): Int =
    localOf(millis, tz).let { it.hour * 60 + it.minute }

/**
 * **"Add to the calendar"** (user rule 2026-10-05): where the added elements are laid, said the way a quota's loop is
 * ([QuotaLoopEditor], the same fields) —
 *  - a START: a day and a time of day. Until one is given it is the calendar filter's position, which is where the
 *    calendar's "add…" right-click put it ([SearchDomain.placementStart]);
 *  - an END — a switch says which way it is stated: a LENGTH after the start (a number and its unit's drop-down;
 *    until one is stated, the added elements' own length, else 1 hour — [SearchDomain.placementLength]), or a day
 *    and a time of its own;
 *  - the button that lays every added element that can go there ([SearchDomain.calendarDrafts], saved as the
 *    calendar's element window saves its own). A task's panel and a period take the end; a reminder's tag, an alarm's
 *    ring and a timer's end are an instant, at the start.
 * Every time field has the right-click menu of steps ([TimeNudgeMenu]); the end's length too (user rule 2026-10-14,
 * to the second: [LENGTH_NUDGE_MILLIS]).
 * Beside each day-and-time (the start, and the end when it is stated as one) stand **"Now"** and — user rule
 * 2026-10-07, *"only if the Search window originates from the calendar from a right-click"*
 * ([SearchDomain.Config.calendarClickMillis]) — **"Right-click time"**, which set it to the clock's instant and to the
 * position of that right-click, to the second as the fields say it (`HH:MM:SS`, user rule 2026-10-14).
 * The start and the end are this Search window's ([SearchDomain.Config.placement], local-only view state). An end not
 * after the start is shown as the error it is, and nothing is laid.
 */
@Composable
internal fun CalendarPlacementEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    run: (SearchDomain.AddedCommand) -> Unit,
    nowMillis: () -> Long,
) {
    val tz = TimeZone.currentSystemDefault()
    val today = localOf(nowMillis(), tz).date
    val placement = config.placement
    fun write(change: (SearchDomain.Placement) -> SearchDomain.Placement) = onConfigChange(config.copy(placement = change(placement)))
    val start = SearchDomain.placementStart(config)
    val startAt = start?.let { localOf(it, tz) }
    val startSeconds = startAt?.let(::secondOfDay)
    // User rules 2026-10-11 and 2026-10-14: the length the added elements have of their own — a break's, a task's
    // minimum time, the Sleep schedule's, a ring's sound — what the end is after until the user states another
    // (anomaly 2026-10-19), and one press of "Default" away beside the end once they have.
    val default = SearchDomain.placementDefaultLength(state, added)
    val length = SearchDomain.placementLength(placement, default)
    fun writeLength(length: Long) = write { SearchDomain.withPlacementLength(it, length, default) }
    val refused = start != null && SearchDomain.placementRefused(placement, start, default)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NoteText("starts", color = MaterialTheme.colorScheme.onSurface)
            DayField(startAt?.date, "day", today, enabled = true) { date ->
                write { it.copy(startMillis = instantOfSecond(date, startSeconds ?: 0, tz)) }
            }
            NoteText("at", color = MaterialTheme.colorScheme.onSurface)
            // A step past midnight is the next day's (or the day before's): the start is an instant.
            TimeOfDaySecondsField(
                startSeconds,
                enabled = true,
                blank = "HH:MM:SS",
                onNudge = { delta -> start?.let { from -> write { it.copy(startMillis = from + delta) } } },
            ) { seconds ->
                write { it.copy(startMillis = instantOfSecond(startAt?.date ?: today, seconds, tz)) }
            }
            InstantButtons { at -> write { it.copy(startMillis = at) } }
        }
        // User rule 2026-10-18: the time of day an added element has of its own, the day chosen kept.
        val own = SearchDomain.elementTimesOfDay(state, added, nowMillis(), tz)
        if (own.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NoteText("time of", color = MaterialTheme.colorScheme.onSurface)
            for (time in own) {
                FrameButton(time.label, enabled = startSeconds != time.secondOfDay) {
                    write { it.copy(startMillis = instantOfSecond(startAt?.date ?: today, time.secondOfDay, tz)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = placement.endByDelta, onCheckedChange = { on -> write { it.copy(endByDelta = on) } })
            if (placement.endByDelta) {
                NoteText("ends", color = MaterialTheme.colorScheme.onSurface)
                LengthField(
                    length,
                    mixed = false,
                    blank = "1",
                    defaultUnit = QuotaDomain.LengthUnit.Hours,
                    units = QuotaDomain.LengthUnit.entries,
                    // A step that would leave no length is not taken.
                    onNudge = { delta -> (length + delta).takeIf { it > 0L }?.let(::writeLength) },
                ) { stated ->
                    writeLength(stated)
                }
                NoteText("after the start", color = MaterialTheme.colorScheme.onSurface)
                default?.let { own ->
                    // Back to unstated: the elements' own length, followed again as they change.
                    FrameButton("Default", enabled = length != own) { write { it.copy(lengthMillis = null, lengthOverMillis = null) } }
                }
            } else {
                // Until an instant is given, the end the length says (anomaly 2026-10-19: not a blank to fill).
                val shownEnd = placement.endMillis ?: start?.let { it + length }
                val endAt = shownEnd?.let { localOf(it, tz) }
                val endSeconds = endAt?.let(::secondOfDay)
                NoteText("ends on", color = MaterialTheme.colorScheme.onSurface)
                DayField(endAt?.date, "day", today, enabled = true) { date ->
                    write { it.copy(endMillis = instantOfSecond(date, endSeconds ?: startSeconds ?: 0, tz)) }
                }
                NoteText("at", color = MaterialTheme.colorScheme.onSurface)
                TimeOfDaySecondsField(
                    endSeconds,
                    enabled = true,
                    refused = refused,
                    blank = "HH:MM:SS",
                    onNudge = { delta -> shownEnd?.let { from -> write { it.copy(endMillis = from + delta) } } },
                ) { seconds ->
                    write { it.copy(endMillis = instantOfSecond(endAt?.date ?: startAt?.date ?: today, seconds, tz)) }
                }
                InstantButtons { at -> write { it.copy(endMillis = at) } }
                if (default != null && start != null) {
                    FrameButton("Default", enabled = shownEnd != start + default) {
                        write { it.copy(endMillis = start + default) }
                    }
                }
            }
        }
        if (start == null) {
            NoteText("Give it a start.")
            return@Column
        }
        val end = SearchDomain.placementEnd(placement, start, default)
        // User rule 2026-10-11: a screen break longer than its name implies cannot be laid where it reaches the future.
        val violation = if (refused) null else SearchDomain.placementBreakViolation(state, added, start, end, nowMillis())
        val blocked = refused || violation != null
        val drafts = if (blocked) emptyList() else SearchDomain.calendarDrafts(state, added, start, endMillis = end)
        // A timer is put on the clock to end there (it has no start on the calendar to give).
        val timers = if (blocked) emptyList() else SearchDomain.calendarTimerIntents(state, added, start, nowMillis())
        val timerCount = (timers.firstOrNull() as? SchedulerIntent.SetTimers)?.let { set ->
            set.entries.count { entry -> state.timers.none { it == entry } }
        } ?: 0
        val count = drafts.size + timerCount
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FrameButton(if (count == 1) "Add" else "Add $count", enabled = count > 0) {
                if (drafts.isNotEmpty()) handlers.onPlaceOnCalendar(drafts)
                timers.forEach { run(SearchDomain.AddedCommand.Raw(it)) }
            }
        }
        Text(
            when {
                refused -> "NOT ADDED — the end has to be after the start."
                violation != null -> "NOT ADDED — $violation"
                else -> "A task's panel and a period end there; a tag and a ring are at the start."
            },
            style = if (blocked) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
            color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
