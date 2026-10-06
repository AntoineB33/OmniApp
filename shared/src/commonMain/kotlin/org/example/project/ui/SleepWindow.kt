package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
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
    val bedMinutes = ((sleep.wakeMinutes - sleep.sleepDurationMinutes) % (24 * 60) + 24 * 60) % (24 * 60)

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
            TimeField("Wake time", sleep.wakeMinutes) { onSave(sleep.copy(wakeMinutes = it)) }
            TimeField("Goal wake time", sleep.goalWakeMinutes) { onSave(sleep.copy(goalWakeMinutes = it)) }
            TimeField("Total sleep time", sleep.sleepDurationMinutes, allowOver24 = true) {
                onSave(sleep.copy(sleepDurationMinutes = it))
            }
            Text(
                text = "Bedtime ${formatHourMinute(bedMinutes)} → wake ${formatHourMinute(sleep.wakeMinutes % (24 * 60))}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A labeled `HH:MM` text field bound to a minutes value. Keeps local edit text so typing isn't disrupted;
 * calls [onMinutes] whenever the text parses to a valid value ([allowOver24] permits a duration ≥ 24h).
 */
@Composable
private fun TimeField(
    label: String,
    minutes: Int,
    allowOver24: Boolean = false,
    onMinutes: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(formatHourMinute(minutes)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                modifier = Modifier.width(96.dp),
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
