package org.example.project

import org.example.project.scheduler.domain.SchedulerDomain

/**
 * Debug-only retiming of ONE screen break, by [org.example.project.scheduler.model.ScreenBreak.key]. Each
 * field is `null` when that rule keeps its production value, so a partial override (say: only the interval)
 * leaves the rest of the break alone. Applied in
 * [org.example.project.scheduler.domain.SchedulerDomain.effectiveDefaultScreenBreaks].
 */
data class ScreenBreakOverride(
    /** The break's drawn LENGTH, in millis. */
    val durationMillis: Long? = null,
    /** How long after the previous qualifying pause the break comes due, in millis. */
    val intervalMillis: Long? = null,
) {
    /** True when this override changes nothing — the break keeps every production timing. */
    val isEmpty: Boolean
        get() = durationMillis == null && intervalMillis == null
}

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
     * Debug-only retiming of the screen breaks, keyed by [org.example.project.scheduler.model.ScreenBreak.key]
     * — `"look_away"` / `"5min_break"` / `"15min_break"`. **All three breaks are tweakable**, independently and
     * partially: a key absent from the map (or a `null` field in its [ScreenBreakOverride]) keeps that break's
     * production rule. Empty = production everywhere, which is what a release always runs.
     *
     * Set at startup from the platform entry point and never changed afterwards: on desktop from the
     * `omniapp.break.<lookAway|pose5|pose15>.<durationMs|intervalMs>` system properties (the
     * `/scripts` fast-break launchers pass them as `-P` properties); on Android only the 5-min pose is wired,
     * through the legacy [breakDurationMillisOverride] trio below.
     */
    var screenBreakOverrides: Map<String, ScreenBreakOverride> = emptyMap()

    /** This break's override, or an all-`null` one when it is not retimed. */
    fun screenBreakOverride(key: String): ScreenBreakOverride =
        screenBreakOverrides[key] ?: ScreenBreakOverride()

    /**
     * Applies [override]'s NON-null fields on top of whatever [key] already carries, leaving the rest alone —
     * so a caller reading several sources (e.g. the legacy 5-min properties and then the per-break ones) can
     * layer them without a later, sparser source erasing an earlier value. An entry that ends up
     * [ScreenBreakOverride.isEmpty] is dropped, keeping "no overrides" exactly equal to an empty map.
     */
    fun mergeScreenBreakOverride(key: String, override: ScreenBreakOverride) {
        val current = screenBreakOverride(key)
        val merged = ScreenBreakOverride(
            durationMillis = override.durationMillis ?: current.durationMillis,
            intervalMillis = override.intervalMillis ?: current.intervalMillis,
        )
        screenBreakOverrides =
            if (merged.isEmpty) screenBreakOverrides - key else screenBreakOverrides + (key to merged)
    }

    /** Replaces one FIELD of one break's override ([value] `null` clears just that field). */
    private fun setScreenBreakField(key: String, field: ScreenBreakOverride.(Long?) -> ScreenBreakOverride, value: Long?) {
        val updated = screenBreakOverride(key).field(value)
        screenBreakOverrides =
            if (updated.isEmpty) screenBreakOverrides - key else screenBreakOverrides + (key to updated)
    }

    /**
     * Debug-only override of the **5-min screen break**'s DURATION, in milliseconds (for exercising the
     * pause-cue voice message end-to-end on real phones). `null` = production 5 minutes. A named view onto
     * [screenBreakOverrides]`["5min_break"]`, kept because the 5-min pose is the one break the *phone* can
     * retime (`AndroidDebugFlagStore`) and the one the pause-cue scripts shrink.
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
    var breakDurationMillisOverride: Long?
        get() = screenBreakOverride(SchedulerDomain.FIVE_MIN_BREAK_KEY).durationMillis
        set(value) = setScreenBreakField(
            SchedulerDomain.FIVE_MIN_BREAK_KEY, { copy(durationMillis = it) }, value,
        )

    /**
     * Debug-only override of the **5-min screen break**'s INTERVAL, in milliseconds — how long after the
     * previous ≥5-min pause the next 5-min break becomes due. `null` = production 1 hour. Companion of
     * [breakDurationMillisOverride]; set both to a few seconds so the now-line reaches a 5-min break almost
     * immediately (then put the machine to sleep and wait `duration` for the phone cue).
     *
     * This pair retimes only the 5-min pose; the other two breaks are retimed through [screenBreakOverrides]
     * (desktop only). The interval IS the pose's recurrence bar (`side-dev/README.md`), so a seconds-long one
     * simply places the pose that often — bounded, as ever, by the rest stretches the bars find: a break the
     * user actually takes bars the next for its own bar's length. The retired "qualifying pause threshold"
     * knob is gone with the anchor engine it decoupled (ADR 0003). Startup-only (`omniapp.breakIntervalMs` /
     * `omniapp_break_interval_ms`), remembered per install on Android debug builds like
     * [breakDurationMillisOverride], off in release.
     */
    var breakIntervalMillisOverride: Long?
        get() = screenBreakOverride(SchedulerDomain.FIVE_MIN_BREAK_KEY).intervalMillis
        set(value) = setScreenBreakField(
            SchedulerDomain.FIVE_MIN_BREAK_KEY, { copy(intervalMillis = it) }, value,
        )

    /** True when ANY screen break is retimed — the debug fast-break test mode is active. */
    val fastBreakOverrideActive: Boolean
        get() = screenBreakOverrides.isNotEmpty()
}
