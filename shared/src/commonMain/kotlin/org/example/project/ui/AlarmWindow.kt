package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.model.AlarmEntry

/**
 * PRD §18 Alarms: a floating, draggable window listing the account's alarms — one row each, with the time of
 * day it rings, an optional label, **the days it is triggered on** (every day by default), **how long the
 * alarm sound lasts**, whether it **vibrates the phone**, whether it repeats, and an on/off switch. Rows are
 * edited live: every change pushes the parsed list up via [onChange], which persists and syncs it, so every
 * device on the account rings at the new time, on the new days.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun AlarmWindow(
    alarms: List<AlarmEntry>,
    onChange: (List<AlarmEntry>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
    /** Time of day (minutes since midnight) to pre-fill a newly added row with — the current clock time. */
    newRowTimeOfDayMinutes: () -> Int = { 0 },
) {
    var offset by remember { mutableStateOf(initialOffset) }
    // Per-row editable text for the parsed fields, so an in-progress "7:" / "" isn't reformatted on each
    // keystroke. Seeded once from the incoming alarms; live edits drive both this and the pushed list.
    val rows = remember {
        mutableStateListOf<AlarmRow>().apply {
            addAll(
                alarms.map {
                    AlarmRow(
                        id = it.id,
                        timeText = formatAlarmTime(it.timeOfDayMinutes),
                        label = it.label,
                        soundText = it.soundSeconds.toString(),
                        vibrate = it.vibrate,
                        days = it.days,
                        repeats = it.repeats,
                        enabled = it.enabled,
                    )
                },
            )
        }
    }

    fun push() {
        onChange(
            rows.map { row ->
                AlarmEntry(
                    id = row.id,
                    label = row.label,
                    // A half-typed time keeps the row alive at midnight rather than dropping the alarm; the
                    // field shows the error state until it parses.
                    timeOfDayMinutes = parseAlarmTime(row.timeText) ?: 0,
                    soundSeconds = parseSoundSeconds(row.soundText) ?: AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS,
                    vibrate = row.vibrate,
                    days = row.days,
                    repeats = row.repeats,
                    enabled = row.enabled,
                )
            },
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width and does not adapt to the app's
            // width when the content area is narrower than it.
            .requiredWidth(440.dp)
            // Raise on press AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress(onRaise),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Title bar doubles as the drag handle for moving the window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .windowDragHandle(onDragEnd = { onOffsetChange(offset) }) { dragAmount ->
                        offset += dragAmount
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Alarms", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Grows with the rows up to a cap, then scrolls — an account may hold many alarms.
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (rows.isEmpty()) {
                    Text(
                        text = "No alarm yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows.forEachIndexed { index, row ->
                    AlarmRowEditor(
                        row = row,
                        onRowChange = {
                            rows[index] = it
                            push()
                        },
                        onRemove = {
                            rows.removeAt(index)
                            push()
                        },
                    )
                    if (index != rows.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }

                Text(
                    text = "+ Add alarm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            rows.add(
                                AlarmRow(
                                    // A locally-unique id right away, so the row has an identity before the
                                    // round-trip through onChange (the reducer mints one for a blank id too).
                                    id = AlarmDomain.mintAlarmId(rows.map { it.id }),
                                    timeText = formatAlarmTime(newRowTimeOfDayMinutes()),
                                ),
                            )
                            push()
                        }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
                Text(
                    text = "Alarms ring on every phone signed in to this account.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One editable alarm row: time + label on the first line, the days it is triggered on on the second, and
 * sound length / vibrate / repeat on the third.
 */
@Composable
private fun AlarmRowEditor(
    row: AlarmRow,
    onRowChange: (AlarmRow) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = row.enabled, onCheckedChange = { onRowChange(row.copy(enabled = it)) })
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = row.timeText,
                onValueChange = { onRowChange(row.copy(timeText = it)) },
                singleLine = true,
                isError = parseAlarmTime(row.timeText) == null,
                modifier = Modifier.width(92.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = row.label,
                onValueChange = { onRowChange(row.copy(label = it)) },
                singleLine = true,
                placeholder = { Text("Label", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("🗑", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        // PRD §18: the days this alarm is triggered on — every day by default. Tapping a letter toggles that
        // weekday; the last selected one cannot be turned off, so an alarm always has a day to ring on (use
        // the on/off switch to silence it instead of emptying the week).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Days", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(2.dp))
            WEEK_DAYS.forEach { day ->
                val selected = day in row.days
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable {
                            val next = if (selected) row.days - day else row.days + day
                            if (next.isNotEmpty()) onRowChange(row.copy(days = next))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dayInitial(day),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (row.days.size == 7) "Every day" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Rings for", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = row.soundText,
                onValueChange = { onRowChange(row.copy(soundText = it)) },
                singleLine = true,
                isError = parseSoundSeconds(row.soundText) == null,
                modifier = Modifier.width(76.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = "s", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(12.dp))
            Text(text = "Vibrate", style = MaterialTheme.typography.bodySmall)
            Switch(checked = row.vibrate, onCheckedChange = { onRowChange(row.copy(vibrate = it)) })
            Spacer(Modifier.width(8.dp))
            // Off = a one-off: it rings at the next of its days and then disarms itself.
            Text(text = "Repeat", style = MaterialTheme.typography.bodySmall)
            Switch(checked = row.repeats, onCheckedChange = { onRowChange(row.copy(repeats = it)) })
        }
    }
}

/** The in-window editing shape of one alarm (text fields kept raw so typing isn't reformatted). */
private data class AlarmRow(
    val id: String,
    val timeText: String = "",
    val label: String = "",
    val soundText: String = AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS.toString(),
    val vibrate: Boolean = true,
    val days: Set<DayOfWeek> = AlarmEntry.EVERY_DAY,
    val repeats: Boolean = true,
    val enabled: Boolean = true,
)

/** Monday-first, matching the calendar's week (PRD §8). */
private val WEEK_DAYS: List<DayOfWeek> = DayOfWeek.entries.sortedBy { it.isoDayNumber }

/** The one-letter chip label for [day] (English initials; Tuesday/Thursday and Saturday/Sunday collide). */
private fun dayInitial(day: DayOfWeek): String =
    when (day) {
        DayOfWeek.MONDAY -> "M"
        DayOfWeek.TUESDAY -> "T"
        DayOfWeek.WEDNESDAY -> "W"
        DayOfWeek.THURSDAY -> "T"
        DayOfWeek.FRIDAY -> "F"
        DayOfWeek.SATURDAY -> "S"
        else -> "S"
    }

private fun formatAlarmTime(minutes: Int): String {
    val m = ((minutes % AlarmEntry.MINUTES_PER_DAY) + AlarmEntry.MINUTES_PER_DAY) % AlarmEntry.MINUTES_PER_DAY
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

/** Parses `H:MM` / `HH:MM` (00:00..23:59) to minutes since midnight, or null when it isn't a valid time. */
private fun parseAlarmTime(text: String): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** Parses how long the alarm rings, in seconds (1..[AlarmEntry.MAX_ALARM_SOUND_SECONDS]). */
private fun parseSoundSeconds(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in 1..AlarmEntry.MAX_ALARM_SOUND_SECONDS }
