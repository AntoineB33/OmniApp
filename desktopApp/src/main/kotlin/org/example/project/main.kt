package org.example.project

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.example.project.perf.Perf
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.WindowPlacementStore
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.ui.KeepAppWindowPlacement
import org.example.project.ui.rememberPersistedAppWindow

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

/** Reads one break's two debug timing properties; absent/unparsable ones stay `null` (production). */
private fun screenBreakOverrideFrom(prefix: String): ScreenBreakOverride = ScreenBreakOverride(
    durationMillis = System.getProperty("$prefix.durationMs")?.toLongOrNull(),
    intervalMillis = System.getProperty("$prefix.intervalMs")?.toLongOrNull(),
)

fun main() {
    // Debug tooling (time simulation) is off unless the `omniapp.timeSim` property is set. The dev `run`
    // task sets it (defaulting true); the packaged release never does, so it ships without the debug panel.
    DebugFlags.TIME_SIMULATION = System.getProperty("omniapp.timeSim").toBoolean()
    // The performance recorder + overlay (`-Pomniapp.perf=true`, or scripts/perf-profile.bat). Independent
    // of the time simulation on purpose: what is worth profiling is the app on the REAL clock against the
    // real account, and an accelerated now-line inflates every per-tick cost. Never set by the release, so
    // the packaged build pays only one static boolean read per instrumented site.
    DebugFlags.PERF = System.getProperty("omniapp.perf").toBoolean()
    Perf.enabled = DebugFlags.PERF
    // Debug fast-break override (for testing the pause-cue voice message, and for watching the break rules on
    // an accelerated calendar): retime a screen break with `-Pomniapp.break.<lookAway|pose5|pose15>.durationMs`
    // (its drawn length) and `.intervalMs` (its recurrence bar). ALL THREE BREAKS are tweakable and
    // independent; an absent property leaves that rule at its production value. Never set by the release.
    // The unprefixed `-Pomniapp.break{Duration,Interval}Ms` pair is the older 5-min-pose-only spelling, still
    // honored (account2-open-fast-break.bat and the Android deploys pass it); it is read FIRST so an explicit
    // `omniapp.break.pose5.*` wins on the rare launch that passes both.
    System.getProperty("omniapp.breakDurationMs")?.toLongOrNull()?.let { DebugFlags.breakDurationMillisOverride = it }
    System.getProperty("omniapp.breakIntervalMs")?.toLongOrNull()?.let { DebugFlags.breakIntervalMillisOverride = it }
    BREAK_PROPERTY_PREFIXES.forEach { (key, prefix) ->
        DebugFlags.mergeScreenBreakOverride(key, screenBreakOverrideFrom(prefix))
    }
    // Opened once, before the window: the window's own placement is read from it, and the app runs on it.
    val store = createDefaultSchedulerStore()
    application {
        // The app's own window comes back where, and as big as, it was left (local-only, like its windows).
        val appWindow = rememberPersistedAppWindow(store as? WindowPlacementStore)
        Window(
            onCloseRequest = ::exitApplication,
            title = "OmniApp",
            state = appWindow.state,
        ) {
            KeepAppWindowPlacement(appWindow)
            App(store = store)
        }
    }
}
