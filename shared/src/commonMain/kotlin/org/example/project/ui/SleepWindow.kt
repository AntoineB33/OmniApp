package org.example.project.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import org.example.project.scheduler.domain.SchedulerDomain
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.model.SleepSchedule

/**
 * Floating window to configure the user's sleep schedule (the nightly window the scheduler avoids):
 * current wake time, goal wake time (the wake time drifts 15 min toward it every 2 days), and total
 * sleep duration. Each edit is saved immediately via [onSave]; it wears the app's one window frame
 * ([AppWindowFrame]) like every other window.
 */
@Composable
fun SleepWindow(
    sleep: SleepSchedule,
    onSave: (SleepSchedule) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    /** Persists the window's new position/size when a move or resize gesture ends (local-only geometry). */
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState("Sleep", initialOffset, initialSize)

    AppWindowFrame(
        title = "Sleep",
        state = frame,
        onClose = onDismiss,
        defaultWidth = 320.dp,
        defaultHeight = 300.dp,
        modifier = modifier,
        onRaise = onRaise,
        onGeometryChange = onGeometryChange,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // weight, not a heightIn cap: the window is resizable, so its content follows the height the
                // user gave it instead of a number written here (see [AppWindowFrame]).
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SleepScheduleFields(sleep, onSave)
        }
    }
}

/**
 * **The sleep schedule's fields**. Each edit is saved at once through [onSave]. ONE drawing: the calendar's
 * configuration section shows it (user rule 2026-10-07 — it left the lateral menu for there), and so does the Sleep
 * window the calendar's "edit… → sleep schedule" still opens. [fieldModifier] is the caller's word on each field (the
 * calendar's hand the keyboard back at a press outside).
 *
 * User rule 2026-10-09: **a drop-down says which time of the night the two time fields are about** — wake up, go to
 * bed or stop screens ([SchedulerDomain.NightTime]). The first is that time now, the second the one it drifts toward
 * (15 min every 2 days): "Goal stop screens time" while "Stop screens" is chosen. Stating either places the whole
 * night and changes neither length — the two lengths are the fields under them.
 */
@Composable
internal fun SleepScheduleFields(sleep: SleepSchedule, onSave: (SleepSchedule) -> Unit, fieldModifier: Modifier = Modifier) {
    var which by remember { mutableStateOf(SchedulerDomain.NightTime.WakeUp) }
    // Keyed on the choice: a field's text is the chosen time's, never the one typed for another.
    key(which) {
        TimeField(
            label = { SleepTimeChoice(which, onChoose = { which = it }, modifier = Modifier.weight(1f)) },
            minutes = SchedulerDomain.nightTimeMinutes(sleep, which),
            fieldModifier = fieldModifier,
        ) { onSave(SchedulerDomain.withNightTime(sleep, which, it)) }
        TimeField("Goal ${which.label.lowercase()} time", SchedulerDomain.goalNightTimeMinutes(sleep, which), fieldModifier = fieldModifier) {
            onSave(SchedulerDomain.withGoalNightTime(sleep, which, it))
        }
    }
    TimeField("Total sleep time", sleep.sleepDurationMinutes, allowOver24 = true, fieldModifier = fieldModifier) {
        onSave(sleep.copy(sleepDurationMinutes = it))
    }
    // How long before bed screens stop: the length of the period the calendar draws there.
    TimeField("No screen before bed", sleep.beforeBedMinutes, allowOver24 = true, fieldModifier = fieldModifier) {
        if (it <= SchedulerDomain.MAX_BEFORE_BED_MINUTES) onSave(sleep.copy(beforeBedMinutes = it))
    }
    Text(
        text = "Stop screens ${formatHourMinute(SchedulerDomain.screensStopMinutes(sleep))} → bed " +
            "${formatHourMinute(SchedulerDomain.bedtimeMinutes(sleep))} → wake ${formatHourMinute(sleep.wakeMinutes % (24 * 60))}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The drop-down that says which time the field beside it edits. It never takes the keyboard: the calendar's is the calendar's. */
@Composable
private fun SleepTimeChoice(
    which: SchedulerDomain.NightTime,
    onChoose: (SchedulerDomain.NightTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .focusProperties { canFocus = false }
                .clickable { open = true }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(which.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
            Text("▾", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SchedulerDomain.NightTime.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        onChoose(entry)
                        open = false
                    },
                )
            }
        }
    }
}

/** [TimeField] under a plain text label. */
@Composable
private fun TimeField(
    label: String,
    minutes: Int,
    allowOver24: Boolean = false,
    fieldModifier: Modifier = Modifier,
    onMinutes: (Int) -> Unit,
) =
    TimeField(
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        },
        minutes = minutes, allowOver24 = allowOver24, fieldModifier = fieldModifier, onMinutes = onMinutes,
    )

/**
 * A labeled `HH:MM` text field bound to a minutes value. Keeps local edit text so typing isn't disrupted;
 * calls [onMinutes] whenever the text parses to a valid value ([allowOver24] permits a duration ≥ 24h).
 */
@Composable
private fun TimeField(
    label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    minutes: Int,
    allowOver24: Boolean = false,
    fieldModifier: Modifier = Modifier,
    onMinutes: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(formatHourMinute(minutes)) }
    // Written from elsewhere — the other place that shows these fields, or another device: read it again, never under
    // the hand typing it (a text that already says this value is left as typed).
    var seen by remember { mutableStateOf(minutes) }
    if (seen != minutes) {
        seen = minutes
        if (parseHourMinute(text, allowOver24) != minutes) text = formatHourMinute(minutes)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        label()
        // A time of day steps round the clock; a duration stops at nothing and at a day.
        TimeNudgeMenu(
            onNudge = { delta ->
                val from = parseHourMinute(text, allowOver24) ?: minutes
                val stepped = if (allowOver24) (from + delta).coerceIn(0, 24 * 60) else nudgedTimeOfDay(from, delta)
                text = formatHourMinute(stepped)
                onMinutes(stepped)
            },
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    parseHourMinute(it, allowOver24)?.let(onMinutes)
                },
                singleLine = true,
                isError = parseHourMinute(text, allowOver24) == null,
                modifier = Modifier.width(96.dp).then(fieldModifier),
            )
        }
    }
}

private fun formatHourMinute(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

/** Parses `H:MM` / `HH:MM` to minutes. A wake time is 0..23h; a duration ([allowOver24]) is 0..24h. */
private fun parseHourMinute(text: String, allowOver24: Boolean): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (m !in 0..59) return null
    val maxHour = if (allowOver24) 24 else 23
    if (h !in 0..maxHour) return null
    val total = h * 60 + m
    return if (allowOver24 && total > 24 * 60) null else total
}
