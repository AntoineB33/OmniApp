package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.QuotaDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * **"Add to the calendar"** (user rule 2026-10-05): where the added elements are laid, said the way a quota's loop is
 * ([QuotaLoopEditor], the same fields) —
 *  - a START: a day and a time of day. Until one is given it is the calendar filter's position, which is where the
 *    calendar's "add…" right-click put it ([SearchDomain.placementStart]);
 *  - an END — a switch says which way it is stated: a LENGTH after the start (the default, 1 hour: a number and its
 *    unit's drop-down), or a day and a time of its own;
 *  - the button that lays every added element that can go there ([SearchDomain.calendarDrafts], saved as the
 *    calendar's element window saves its own). A task's panel and a period take the end; a reminder's tag, an alarm's
 *    ring and a timer's end are an instant, at the start.
 * Beside each day-and-time (the start, and the end when it is stated as one) stand **"Now"** and — user rule
 * 2026-10-07, *"only if the Search window originates from the calendar from a right-click"*
 * ([SearchDomain.Config.calendarClickMillis]) — **"Right-click time"**, which set it to the clock's instant and to the
 * position of that right-click, to the minute as the fields say it.
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
    val startMinutes = startAt?.let { it.hour * 60 + it.minute }
    val refused = start != null && SearchDomain.placementRefused(placement, start)
    // "Now" and "Right-click time" for one day-and-time: to the minute, as the fields say it.
    @Composable
    fun instantButtons(set: (Long) -> Unit) {
        FrameButton("Now") { set(nowMillis() / 60_000L * 60_000L) }
        config.calendarClickMillis?.let { click -> FrameButton("Right-click time") { set(click / 60_000L * 60_000L) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NoteText("starts", color = MaterialTheme.colorScheme.onSurface)
            DayField(startAt?.date, "day", today, enabled = true) { date ->
                write { it.copy(startMillis = instantOf(date, startMinutes ?: 0, tz)) }
            }
            NoteText("at", color = MaterialTheme.colorScheme.onSurface)
            // A step past midnight is the next day's (or the day before's): the start is an instant.
            TimeOfDayField(
                startMinutes,
                enabled = true,
                blank = "HH:MM",
                onNudge = { delta -> start?.let { from -> write { it.copy(startMillis = from + delta * 60_000L) } } },
            ) { minutes ->
                write { it.copy(startMillis = instantOf(startAt?.date ?: today, minutes, tz)) }
            }
            instantButtons { at -> write { it.copy(startMillis = at) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = placement.endByDelta, onCheckedChange = { on -> write { it.copy(endByDelta = on) } })
            if (placement.endByDelta) {
                NoteText("ends", color = MaterialTheme.colorScheme.onSurface)
                LengthField(placement.lengthMillis, mixed = false, blank = "1", defaultUnit = QuotaDomain.LengthUnit.Hours) { length ->
                    write { it.copy(lengthMillis = length) }
                }
                NoteText("after the start", color = MaterialTheme.colorScheme.onSurface)
            } else {
                val endAt = placement.endMillis?.let { localOf(it, tz) }
                val endMinutes = endAt?.let { it.hour * 60 + it.minute }
                NoteText("ends on", color = MaterialTheme.colorScheme.onSurface)
                DayField(endAt?.date, "day", today, enabled = true) { date ->
                    write { it.copy(endMillis = instantOf(date, endMinutes ?: startMinutes ?: 0, tz)) }
                }
                NoteText("at", color = MaterialTheme.colorScheme.onSurface)
                TimeOfDayField(
                    endMinutes,
                    enabled = true,
                    refused = refused,
                    blank = "HH:MM",
                    onNudge = { delta -> placement.endMillis?.let { from -> write { it.copy(endMillis = from + delta * 60_000L) } } },
                ) { minutes ->
                    write { it.copy(endMillis = instantOf(endAt?.date ?: startAt?.date ?: today, minutes, tz)) }
                }
                instantButtons { at -> write { it.copy(endMillis = at) } }
            }
        }
        if (start == null) {
            NoteText("Give it a start.")
            return@Column
        }
        val end = SearchDomain.placementEnd(placement, start)
        val drafts = if (refused) emptyList() else SearchDomain.calendarDrafts(state, added, start, endMillis = end)
        // A timer is put on the clock to end there (it has no start on the calendar to give).
        val timers = if (refused) emptyList() else SearchDomain.calendarTimerIntents(state, added, start, nowMillis())
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
            if (refused) "NOT ADDED — the end has to be after the start."
            else "A task's panel and a period end there; a tag and a ring are at the start.",
            style = if (refused) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
            color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
