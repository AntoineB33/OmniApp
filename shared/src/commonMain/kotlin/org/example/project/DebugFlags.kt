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
     * fire quickly: the `t_b` cron + Edge Function run on the REAL wall clock (server `now()` against
     * `beat_at`), so accelerating the client's [org.example.project.time.SimAppClock] ([TIME_SIMULATION]) does
     * NOT speed them up. The server times the cue off `next_break_len_ms`, written straight from this pose's
     * drawn duration into the presence row, so the phone speaks `duration` after the last device leaves the
     * break window — **no Supabase redeploy is needed**, the length rides the client's tick. (The equivalent
     * server-side lever, needing no rebuild either, is a `break_config.length_ms` row — see
     * docs/PAUSE_CUE_DELIVERY.md Step 3; it OVERRIDES this one for the cue while leaving the calendar alone.
     * Either way, a length below the ~1-min `t_b` can only be armed by the clean screen-off short-circuit.)
     *
     * Set at startup from the platform entry point (`omniapp.breakDurationMs` system property on desktop; the
     * `omniapp_break_duration_ms` launch-Intent extra on Android) and always off in release. On desktop it is
     * not persisted (the launcher script re-passes it every run); on Android a **debug** build remembers it for
     * the install (`AndroidDebugFlagStore`), because the engine restarts after a reboot with no Activity and so
     * no extras — and the server-side half of the fast break (`break_config.length_ms`) is a DB row that
     * persists, so a phone that forgot its override would publish production due instants against a shrunk
     * server cue. Test-only: it changes how a device seeds/records its rest poses, so point it at a scratch
     * account.
     */
    var breakDurationMillisOverride: Long? = null

    /**
     * Debug-only override of the **5-min screen break**'s INTERVAL, in milliseconds — how long after the
     * previous ≥5-min pause the next 5-min break becomes due. `null` = production 1 hour. Companion of
     * [breakDurationMillisOverride]; set both to a few seconds so the now-line reaches a 5-min break almost
     * immediately (then put the machine to sleep and wait `duration` for the phone cue).
     *
     * Only the 5-min pose is retimed — the 15-min pose and the 20-20-20 look-away keep their production
     * timings. Whether short 5-min breaks recur depends on [breakPauseThresholdMillisOverride]: with no
     * threshold (the real-time `account2-open-fast-break.bat` shape, qualifying pause == the 5 s drawn length)
     * the pose grid-recurs and the dense cap holds it to one at a time; with a threshold (the
     * `account1-…-fast-break.bat` shape, e.g. 2 h) the pose is *decoupled* and does NOT grid-recur — it appears
     * exactly `interval` after each qualifying pause, i.e. one break 5 s after each scheduled sleep window
     * (`SchedulerDomain.decoupledPoseOccurrences`), so the calendar shows one 5-min break per day rather than a
     * 5-second flood. The screen-break projection is O(n), so even a seconds interval over the fill horizon
     * stays linear rather than freezing. Startup-only (`omniapp.breakIntervalMs` / `omniapp_break_interval_ms`),
     * remembered per install on Android debug builds like [breakDurationMillisOverride], off in release.
     */
    var breakIntervalMillisOverride: Long? = null

    /**
     * Debug-only override of the **5-min screen break**'s qualifying-PAUSE THRESHOLD, in milliseconds — the
     * minimum real pause length that anchors the pose, decoupled from its drawn length. `null` = production
     * behavior (the threshold equals the break's [breakDurationMillisOverride]/duration). Set it to place a
     * short break only after a long pause — e.g. `account1-empty-and-open-fast-break.bat` runs under the
     * accelerated time-sim with duration 5 s, interval 5 s, and this threshold at 2 h, so the pose fires 5 s
     * after a ≥2-h pause and lasts 5 s. Companion of the other two; startup-only (`omniapp.breakPauseThresholdMs`
     * / `omniapp_break_pause_threshold_ms`), remembered per install on Android debug builds, off in release.
     */
    var breakPauseThresholdMillisOverride: Long? = null

    /** True when any 5-min-break override is set — the debug fast-break test mode is active. */
    val fastBreakOverrideActive: Boolean
        get() = breakDurationMillisOverride != null ||
            breakIntervalMillisOverride != null ||
            breakPauseThresholdMillisOverride != null
}
