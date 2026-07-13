package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.platform.Diagnostics
import org.example.project.time.SimAppClock

private val SPEEDS = listOf(1.0, 10.0, 60.0, 300.0)
private val panelAccent = Color(0xFF8E24AA) // distinct from the calendar's blue, to read as "debug"

/**
 * Debug "simulate pause + leap": which device(s) the leap treats as INACTIVE while the accelerated break
 * elapses (the others keep reading their real screen state, so e.g. "only the phone" leaves the desktop's
 * active session — and thus the account's activity — intact). "Only the phone" needs a phone on the
 * time-link, so the dropdown's choices adapt to [TimeSimPanel]'s `linkedCount`.
 */
enum class SimPauseScope(val label: String) {
    All("all"),
    ComputerOnly("only the computer"),
    PhoneOnly("only the phone"),
}

/**
 * Debug-only time-acceleration control (gated by [org.example.project.DebugFlags.TIME_SIMULATION]).
 * Drives a [SimAppClock] so the scheduler's deadlines, the calendar's now-line and day rollovers can
 * be exercised in seconds instead of hours. [nowMillis] is the live virtual instant (for the
 * readout), passed in so the panel shares the app's single time tick.
 */
@Composable
fun TimeSimPanel(
    clock: SimAppClock,
    nowMillis: Long,
    /**
     * Debug: simulate taking a pause of `durationMillis` by INSTANTLY jumping the sim clock forward by the whole
     * break (the now-line leaps to its end), with the device(s) in `inactiveScope` forced to read as
     * screen-inactive across the jumped-over window — the engine's own loops then live it as the release logic would.
     */
    onSimulatePause: (durationMillis: Long, inactiveScope: SimPauseScope) -> Unit = { _, _ -> },
    /**
     * Whether the history currently holds changes made under the diverged clock that the next app start
     * will revert (PRD §6). Drives the red "will be reverted on restart" warning.
     */
    pendingRollback: Boolean = false,
    /**
     * Debug time-link status (docs/PAUSE_CUE_DELIVERY.md "Testing C"): number of phones receiving this clock,
     * or -1 when the link server isn't running. Drives the "phone link" banner. Warn-only — the controls stay
     * usable when disconnected (acceleration then applies to the desktop alone).
     */
    linkedCount: Int = -1,
    modifier: Modifier = Modifier,
) {
    val tz = remember { TimeZone.currentSystemDefault() }
    var selectedSpeed by remember { mutableStateOf(clock.speed) }
    val dt = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, panelAccent),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "SIM TIME",
                style = MaterialTheme.typography.labelSmall,
                color = panelAccent,
                fontWeight = FontWeight.Bold,
            )
            // Debug time-link status: green when ≥1 phone shares this accelerated clock, amber warning when the
            // link is up but no phone is attached (acceleration then applies to the desktop only).
            if (linkedCount >= 0) {
                val connected = linkedCount > 0
                Text(
                    text =
                        if (connected) "● Phone link: connected ($linkedCount)"
                        else "⚠ Phone link: not connected — acceleration is desktop-only",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connected) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = formatDateTime(dt.year, dt.monthNumber, dt.dayOfMonth, dt.hour, dt.minute, dt.second),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SPEEDS.forEach { sp ->
                    SpeedChip(label = "${sp.toInt()}x", active = selectedSpeed == sp) {
                        // Diagnostics: bracket the accelerated window in the cross-device timeline, so "the
                        // phone missed the first break after I clicked 300x" reads with its trigger in place.
                        Diagnostics.log("sim panel: speed set to ${sp.toInt()}x")
                        clock.setSpeed(sp)
                        selectedSpeed = sp
                    }
                }
                SpeedChip(label = "❚❚", active = selectedSpeed == 0.0) {
                    Diagnostics.log("sim panel: time paused (speed 0)")
                    clock.setSpeed(0.0)
                    selectedSpeed = 0.0
                }
            }
            // PRD §15 debug: simulate taking a pause — instantly jump the sim clock forward by the break with
            // the selected device(s) reading as inactive, so the side-task rhythm (look-away / poses), the
            // derived Inactivity bands and the now-line can be exercised without waiting.
            Text(
                text = "simulate pause + leap",
                style = MaterialTheme.typography.labelSmall,
                color = panelAccent,
                fontWeight = FontWeight.Bold,
            )
            // Which device(s) the leap treats as inactive. "Only the phone" (and the distinction with "all")
            // only exists while a phone is actually on the time-link, so the choices adapt to [linkedCount];
            // a selection that loses its phone falls back to "all" without discarding the user's pick.
            val scopes =
                if (linkedCount > 0) SimPauseScope.entries.toList() else listOf(SimPauseScope.All)
            var pauseScope by remember { mutableStateOf(SimPauseScope.All) }
            val effectiveScope = if (pauseScope in scopes) pauseScope else SimPauseScope.All
            val simulatePause = { durationMillis: Long ->
                Diagnostics.log(
                    "sim panel: simulate pause ${durationMillis / 1_000}s + leap (inactive=${effectiveScope.label})",
                )
                onSimulatePause(durationMillis, effectiveScope)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PauseChip(label = "20s") { simulatePause(20L * 1_000) }
                PauseChip(label = "5min") { simulatePause(5L * 60 * 1_000) }
                PauseChip(label = "15min") { simulatePause(15L * 60 * 1_000) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "inactive:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    Text(
                        text = "${effectiveScope.label} ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(BorderStroke(1.dp, panelAccent), RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { menuOpen = true }
                            .padding(vertical = 2.dp, horizontal = 8.dp),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        scopes.forEach { scope ->
                            DropdownMenuItem(
                                text = { Text(scope.label, style = MaterialTheme.typography.labelMedium) },
                                onClick = {
                                    pauseScope = scope
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            // PRD §6: warn that any change made while time is diverged is debug-only and will be undone
            // on the next start, so the real saved data is never polluted by fast-forwarding.
            if (pendingRollback) {
                Text(
                    text = "⚠ changes made under sim time will be reverted on restart",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD32F2F),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = "reset to real time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        Diagnostics.log("sim panel: reset to real time")
                        clock.reset()
                        selectedSpeed = 1.0
                    }
                    .padding(vertical = 2.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun PauseChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Transparent)
            .border(BorderStroke(1.dp, panelAccent), RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp, horizontal = 8.dp),
    )
}

@Composable
private fun SpeedChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) panelAccent else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp, horizontal = 8.dp),
    )
}

private fun formatDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
    "$year-${pad(month)}-${pad(day)} ${pad(hour)}:${pad(minute)}:${pad(second)}"

private fun pad(value: Int): String = if (value < 10) "0$value" else value.toString()
