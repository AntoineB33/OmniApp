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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("starts", style = MaterialTheme.typography.bodySmall)
            DayField(startAt?.date, "day", today, enabled = true) { date ->
                write { it.copy(startMillis = instantOf(date, startMinutes ?: 0, tz)) }
            }
            Text("at", style = MaterialTheme.typography.bodySmall)
            // A step past midnight is the next day's (or the day before's): the start is an instant.
            TimeOfDayField(
                startMinutes,
                enabled = true,
                blank = "HH:MM",
                onNudge = { delta -> start?.let { from -> write { it.copy(startMillis = from + delta * 60_000L) } } },
            ) { minutes ->
                write { it.copy(startMillis = instantOf(startAt?.date ?: today, minutes, tz)) }
            }
            // To the minute, as the fields say it.
            FrameButton("Now") { write { it.copy(startMillis = nowMillis() / 60_000L * 60_000L) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = placement.endByDelta, onCheckedChange = { on -> write { it.copy(endByDelta = on) } })
            if (placement.endByDelta) {
                Text("ends", style = MaterialTheme.typography.bodySmall)
                LengthField(placement.lengthMillis, mixed = false, blank = "1", defaultUnit = QuotaDomain.LengthUnit.Hours) { length ->
                    write { it.copy(lengthMillis = length) }
                }
                Text("after the start", style = MaterialTheme.typography.bodySmall)
            } else {
                val endAt = placement.endMillis?.let { localOf(it, tz) }
                val endMinutes = endAt?.let { it.hour * 60 + it.minute }
                Text("ends on", style = MaterialTheme.typography.bodySmall)
                DayField(endAt?.date, "day", today, enabled = true) { date ->
                    write { it.copy(endMillis = instantOf(date, endMinutes ?: startMinutes ?: 0, tz)) }
                }
                Text("at", style = MaterialTheme.typography.bodySmall)
                TimeOfDayField(
                    endMinutes,
                    enabled = true,
                    refused = refused,
                    blank = "HH:MM",
                    onNudge = { delta -> placement.endMillis?.let { from -> write { it.copy(endMillis = from + delta * 60_000L) } } },
                ) { minutes ->
                    write { it.copy(endMillis = instantOf(endAt?.date ?: startAt?.date ?: today, minutes, tz)) }
                }
            }
        }
        if (start == null) {
            Text(
                "Give it a start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            // An alarm is added as a new one (an existing one's occurrences are its weekdays').
            FrameButton("New alarm here") { handlers.onPlaceOnCalendar(listOf(SearchDomain.calendarAlarmDraft(state, start))) }
        }
        Text(
            if (refused) "NOT ADDED — the end has to be after the start."
            else "A task's panel and a period end there; a tag and a ring are at the start.",
            style = if (refused) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
            color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
