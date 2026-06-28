package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.model.SleepSchedule

/**
 * Floating window to configure the user's sleep schedule (the nightly window the scheduler avoids):
 * current wake time, goal wake time (the wake time drifts 15 min toward it every 2 days), and total
 * sleep duration. Each edit is saved immediately via [onSave]; mirrors the other floating windows'
 * drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun SleepWindow(
    sleep: SleepSchedule,
    onSave: (SleepSchedule) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }
    val bedMinutes = ((sleep.wakeMinutes - sleep.sleepDurationMinutes) % (24 * 60) + 24 * 60) % (24 * 60)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width and does not adapt to the app's
            // width when the content area is narrower than it.
            .requiredWidth(320.dp)
            .raiseOnPress(onRaise),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Title bar doubles as the drag handle for moving the window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { onOffsetChange(offset) }) { change, dragAmount ->
                            change.consume()
                            offset += dragAmount
                        }
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Sleep", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
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
