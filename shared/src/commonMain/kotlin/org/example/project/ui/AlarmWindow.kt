package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.TimerEntry

/**
 * PRD §18 Alarms and timers: a floating, draggable window in **two sections**.
 *
 * **Alarms** — one row each, with the time of day it rings, an optional label, **the days it is triggered
 * on** (every day by default), **how long the alarm sound lasts**, whether it **vibrates the phone**, whether
 * it repeats, and an on/off switch.
 *
 * **Timers** — one row each, with a duration, an optional label, the same ring length / vibration, a live
 * countdown and start / pause / reset. A timer is due at one absolute instant rather than at a wall-clock
 * time of day; everything after that instant — the arming, the sweep, the ring — is the alarms' own machinery
 * (see [TimerDomain]).
 *
 * Rows are edited live: every change pushes the parsed list up via [onChange] / [onTimersChange], which
 * persists and syncs it, so every device on the account rings at the new time, on the new days. The three
 * timer transitions go through their own callbacks rather than through [onTimersChange], so editing a row's
 * text while it counts down cannot disturb the instant it is due at.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun AlarmWindow(
    alarms: List<AlarmEntry>,
    onChange: (List<AlarmEntry>) -> Unit,
    /** PRD §18 Timers: the account's countdowns, including which of them are running. */
    timers: List<TimerEntry>,
    /** Persists + syncs the timer rows' settings (label, duration, ring length, vibration). */
    onTimersChange: (List<TimerEntry>) -> Unit,
    /** Start the timer with this id, or resume it from where a pause left it. */
    onStartTimer: (String) -> Unit,
    /** Hold the timer with this id where it is. */
    onPauseTimer: (String) -> Unit,
    /** Return the timer with this id to idle at its full duration. */
    onResetTimer: (String) -> Unit,
    /**
     * The current instant, read from the app clock (the **simulated** one under §16). Polled by this window
     * while a timer is running so the countdown reads down in real time: the engine's own now-line only
     * advances once per production tick (30 s), which is the schedule's cadence and not a countdown's.
     */
    nowMillis: () -> Long,
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
    // The same, for the timers — their SETTINGS only. Whether a row is running is read live off [timers]
    // below, because it is moved by the start/pause/reset callbacks (and by a peer over sync), not by typing
    // here; keeping it in this local copy is what would let a keystroke overwrite a running countdown.
    val timerRows = remember {
        mutableStateListOf<TimerRow>().apply {
            addAll(
                timers.map {
                    TimerRow(
                        id = it.id,
                        durationText = formatDuration(it.durationSeconds),
                        label = it.label,
                        soundText = it.soundSeconds.toString(),
                        vibrate = it.vibrate,
                    )
                },
            )
        }
    }

    // The countdown's own clock. The engine's now-line ticks once per production tick (30 s), which would
    // make a countdown jump in half-minutes, so this window polls the app clock itself — but only while a
    // timer is actually running, and only while the window is open. Display-only Compose state, like the
    // calendar's zoom: nothing here is persisted, synced or scheduled.
    var displayNowMillis by remember { mutableStateOf(nowMillis()) }
    val anyRunning = timers.any { it.running }
    LaunchedEffect(anyRunning) {
        displayNowMillis = nowMillis()
        while (anyRunning) {
            delay(COUNTDOWN_TICK_MILLIS)
            displayNowMillis = nowMillis()
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

    fun pushTimers() {
        onTimersChange(
            timerRows.map { row ->
                // Carry the run state through untouched: this push is about the settings, and the row may be
                // counting down while the user edits its label.
                val live = timers.firstOrNull { it.id == row.id }
                TimerEntry(
                    id = row.id,
                    label = row.label,
                    // A half-typed duration keeps the row alive at its default rather than dropping the
                    // timer; the field shows the error state until it parses.
                    durationSeconds = parseDurationSeconds(row.durationText) ?: TimerEntry.DEFAULT_TIMER_SECONDS,
                    soundSeconds = parseSoundSeconds(row.soundText) ?: AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS,
                    vibrate = row.vibrate,
                    endsAtMillis = live?.endsAtMillis,
                    remainingMillis = live?.remainingMillis,
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
                SectionHeader("Alarms")
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

                // PRD §18 Timers: the second section. A timer is the same ring at a different kind of due
                // instant, which is why it lives in this window and not in one of its own.
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                SectionHeader("Timers")
                if (timerRows.isEmpty()) {
                    Text(
                        text = "No timer yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                timerRows.forEachIndexed { index, row ->
                    TimerRowEditor(
                        row = row,
                        // The live entry, which is where the run state lives; null only for the instant
                        // between adding a row and the push landing.
                        entry = timers.firstOrNull { it.id == row.id },
                        nowMillis = displayNowMillis,
                        onRowChange = {
                            timerRows[index] = it
                            pushTimers()
                        },
                        onStart = { onStartTimer(row.id) },
                        onPause = { onPauseTimer(row.id) },
                        onReset = { onResetTimer(row.id) },
                        onRemove = {
                            timerRows.removeAt(index)
                            pushTimers()
                        },
                    )
                    if (index != timerRows.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }

                Text(
                    text = "+ Add timer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            timerRows.add(
                                TimerRow(id = TimerDomain.mintTimerId(timerRows.map { it.id })),
                            )
                            pushTimers()
                        }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
                Text(
                    text = "A running timer belongs to the account, not to this device: every device rings " +
                        "when it ends.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The label above each of the window's two sections (PRD §18: alarms and timers). */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/**
 * PRD §18 Timers: one editable timer row — duration + label on the first line, the live countdown and the
 * start/pause/reset controls on the second, sound length and vibration on the third.
 *
 * [entry] is the live state of this timer (null only in the instant between adding the row and the push
 * landing): it is what says whether the row is idle, running or paused, and the countdown is read off it and
 * [nowMillis] rather than stored anywhere.
 */
@Composable
private fun TimerRowEditor(
    row: TimerRow,
    entry: TimerEntry?,
    nowMillis: Long,
    onRowChange: (TimerRow) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit,
) {
    val running = entry?.running == true
    val paused = entry?.paused == true
    // Before the first push lands there is no entry yet, so fall back to the typed duration.
    val remaining = entry?.remainingAtMillis(nowMillis)
        ?: ((parseDurationSeconds(row.durationText) ?: 0).toLong() * 1_000L)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.durationText,
                onValueChange = { onRowChange(row.copy(durationText = it)) },
                singleLine = true,
                isError = parseDurationSeconds(row.durationText) == null,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The countdown: derived from the end instant and the now-line, never stored. Emphasised while it
            // is actually running so a paused row reads as held rather than stuck.
            Text(
                text = formatCountdown(remaining),
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 84.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                TimerActionChip(text = "Pause", onClick = onPause)
            } else {
                // Resuming and starting are the same button: one continues from what a pause banked, the
                // other from the full duration, and TimerDomain.started is what tells them apart.
                TimerActionChip(
                    text = if (paused) "Resume" else "Start",
                    enabled = parseDurationSeconds(row.durationText) != null,
                    onClick = onStart,
                )
            }
            Spacer(Modifier.width(6.dp))
            TimerActionChip(text = "Reset", enabled = running || paused, onClick = onReset)
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
        }
    }
}

/** A small outlined text button for a timer's start/pause/reset, in the window's own flat idiom. */
@Composable
private fun TimerActionChip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val color =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
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

/**
 * The in-window editing shape of one timer's **settings** (text fields kept raw so typing isn't reformatted).
 * Deliberately holds no run state: that lives on the [TimerEntry] and is moved only by the three transitions.
 */
private data class TimerRow(
    val id: String,
    val durationText: String = formatDuration(TimerEntry.DEFAULT_TIMER_SECONDS),
    val label: String = "",
    val soundText: String = AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS.toString(),
    val vibrate: Boolean = true,
)

/**
 * How often the window re-reads the clock while a timer counts down. Fast enough that the seconds readout
 * does not visibly stutter, and it runs only while this window is open **and** something is running.
 */
private const val COUNTDOWN_TICK_MILLIS: Long = 250L

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

/**
 * PRD §18 Timers: parses a countdown length written as `SS`, `M:SS` or `H:MM:SS` into seconds, or null when
 * it is not a duration in `1..`[TimerEntry.MAX_TIMER_SECONDS]. The minutes/seconds fields are bounded at 59
 * so a typed `5:70` is an error rather than silently 6:10 — the leading field alone may run over (`90:00` is
 * an hour and a half).
 */
private fun parseDurationSeconds(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size !in 1..3) return null
    val fields = parts.map { it.trim().toIntOrNull() ?: return null }
    if (fields.any { it < 0 }) return null
    if (fields.drop(1).any { it > 59 }) return null
    val seconds = when (fields.size) {
        1 -> fields[0]
        2 -> fields[0] * 60 + fields[1]
        else -> fields[0] * 3600 + fields[1] * 60 + fields[2]
    }
    return seconds.takeIf { it in 1..TimerEntry.MAX_TIMER_SECONDS }
}

/**
 * A duration in seconds as `M:SS`, or `H:MM:SS` once it reaches an hour. Both of these are [TimerDomain]'s,
 * because the calendar marker names a nameless timer by its duration too — one spelling, so the window and
 * the marker can never disagree about how long a timer is.
 */
private fun formatDuration(seconds: Int): String = TimerDomain.formatDuration(seconds)

/** PRD §18 Timers: how much is left. See [TimerDomain.formatCountdown]. */
private fun formatCountdown(millis: Long): String = TimerDomain.formatCountdown(millis)
