package org.example.project

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.createDefaultSchedulerStore

/**
 * The `omniapp.break.<name>.*` property prefix of each screen break, by its stable [ScreenBreak.key]. The
 * property names are readable rather than key-shaped (`pose5`, not `5min_break`) because they are typed by
 * hand into `/scripts` launchers.
 */
private val BREAK_PROPERTY_PREFIXES: List<Pair<String, String>> = listOf(
    SchedulerDomain.LOOK_AWAY_KEY to "omniapp.break.lookAway",
    SchedulerDomain.FIVE_MIN_BREAK_KEY to "omniapp.break.pose5",
    SchedulerDomain.FIFTEEN_MIN_BREAK_KEY to "omniapp.break.pose15",
)

/** Reads one break's three debug timing properties; absent/unparsable ones stay `null` (production). */
private fun screenBreakOverrideFrom(prefix: String): ScreenBreakOverride = ScreenBreakOverride(
    durationMillis = System.getProperty("$prefix.durationMs")?.toLongOrNull(),
    intervalMillis = System.getProperty("$prefix.intervalMs")?.toLongOrNull(),
    pauseThresholdMillis = System.getProperty("$prefix.pauseThresholdMs")?.toLongOrNull(),
)

fun main() {
    // Debug tooling (time simulation) is off unless the `omniapp.timeSim` property is set. The dev `run`
    // task sets it (defaulting true); the packaged release never does, so it ships without the debug panel.
    DebugFlags.TIME_SIMULATION = System.getProperty("omniapp.timeSim").toBoolean()
    // Debug fast-break override (for testing the pause-cue voice message, and for watching the break rules on
    // an accelerated calendar): retime a screen break with `-Pomniapp.break.<lookAway|pose5|pose15>.durationMs`
    // (its drawn length), `.intervalMs` (how long after the previous qualifying pause it comes due) and
    // `.pauseThresholdMs` (how long a pause must be to anchor it — decoupling that from the drawn length, so a
    // short break can be placed only after a long pause). ALL THREE BREAKS are tweakable and independent; an
    // absent property leaves that rule at its production value. Never set by the release.
    // The unprefixed `-Pomniapp.break{Duration,Interval,PauseThreshold}Ms` trio is the older 5-min-pose-only
    // spelling, still honored (account2-open-fast-break.bat and the Android deploys pass it); it is read FIRST
    // so an explicit `omniapp.break.pose5.*` wins on the rare launch that passes both.
    System.getProperty("omniapp.breakDurationMs")?.toLongOrNull()?.let { DebugFlags.breakDurationMillisOverride = it }
    System.getProperty("omniapp.breakIntervalMs")?.toLongOrNull()?.let { DebugFlags.breakIntervalMillisOverride = it }
    System.getProperty("omniapp.breakPauseThresholdMs")?.toLongOrNull()?.let { DebugFlags.breakPauseThresholdMillisOverride = it }
    BREAK_PROPERTY_PREFIXES.forEach { (key, prefix) ->
        DebugFlags.mergeScreenBreakOverride(key, screenBreakOverrideFrom(prefix))
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "OmniApp",
        ) {
            App(store = createDefaultSchedulerStore())
        }
    }
}
