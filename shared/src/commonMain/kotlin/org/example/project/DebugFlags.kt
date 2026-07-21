package org.example.project

/**
 * Switches for in-app debug tooling, set once at startup from the platform entry point (before [App]
 * composes) and never changed afterwards. **Off by default**, so a packaged/release build never shows the
 * debug tooling. The desktop dev `run` task turns [TIME_SIMULATION] on via the `omniapp.timeSim` system
 * property (see `desktopApp/build.gradle.kts` and `desktopApp/.../main.kt`); `createDistributable` does
 * not set it, so the installed release stays off.
 */
object DebugFlags {
    /** Shows the time-acceleration panel and drives the scheduler/calendar from a [org.example.project.time.SimAppClock]. */
    var TIME_SIMULATION: Boolean = false

    /**
     * Debug-only override of the **5-min screen break**'s DURATION, in milliseconds (for exercising the
     * pause-cue voice message end-to-end on real phones). `null` = production 5 minutes.
     *
     * The "5-min break" is the shorter of the two rest-break poses in
     * [org.example.project.scheduler.domain.SchedulerDomain.DEFAULT_SCREEN_BREAKS] ("take a 5min pose and blink
     * hard"). Shrinking its duration to a few seconds is the one lever that makes the REAL server→phone push
     * fire quickly: the external `/listener` + Edge Function run on the REAL wall clock and read Realtime
     * Presence, so accelerating the client's [org.example.project.time.SimAppClock] ([TIME_SIMULATION]) does
     * NOT speed them up. The listener times the cue off `next_break_len_ms`, published straight from this
     * pose's drawn duration, so the phone speaks `duration` after the last device leaves the break window —
     * **no Supabase redeploy is needed**, the length rides the client's presence.
     *
     * Set at startup from the platform entry point (`omniapp.breakDurationMs` system property on desktop; the
     * `omniapp_break_duration_ms` launch-Intent extra on Android), never persisted, always off in release.
     * Test-only: it changes how a device seeds/records its rest poses, so point it at a scratch account.
     */
    var breakDurationMillisOverride: Long? = null

    /**
     * Debug-only override of the **5-min screen break**'s INTERVAL, in milliseconds — how long after the
     * previous ≥5-min pause the next 5-min break becomes due. `null` = production 1 hour. Companion of
     * [breakDurationMillisOverride]; set both to a few seconds so the now-line reaches a 5-min break almost
     * immediately (then put the machine to sleep and wait `duration` for the phone cue).
     *
     * Only the 5-min pose is retimed — the 15-min pose and the 20-20-20 look-away keep their production
     * timings, so many short 5-min breaks pile up toward the still-distant 15-min pose. The screen-break
     * projection is O(n) (`SchedulerDomain.simulateScreenBreaks`), so even a seconds interval over the fill
     * horizon stays linear rather than freezing. Startup-only (`omniapp.breakIntervalMs` /
     * `omniapp_break_interval_ms`), never persisted, off in release.
     */
    var breakIntervalMillisOverride: Long? = null

    /**
     * Debug-only override of the **5-min screen break**'s qualifying-PAUSE THRESHOLD, in milliseconds — the
     * minimum real pause length that anchors the pose, decoupled from its drawn length. `null` = production
     * behavior (the threshold equals the break's [breakDurationMillisOverride]/duration). Set it to place a
     * short break only after a long pause — e.g. `account1-empty-and-open-fast-break.bat` runs under the
     * accelerated time-sim with duration 5 s, interval 5 s, and this threshold at 2 h, so the pose fires 5 s
     * after a ≥2-h pause and lasts 5 s. Companion of the other two; startup-only (`omniapp.breakPauseThresholdMs`
     * / `omniapp_break_pause_threshold_ms`), never persisted, off in release.
     */
    var breakPauseThresholdMillisOverride: Long? = null

    /** True when any 5-min-break override is set — the debug fast-break test mode is active. */
    val fastBreakOverrideActive: Boolean
        get() = breakDurationMillisOverride != null ||
            breakIntervalMillisOverride != null ||
            breakPauseThresholdMillisOverride != null
}
