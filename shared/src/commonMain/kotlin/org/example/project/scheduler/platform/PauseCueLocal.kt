package org.example.project.scheduler.platform

/**
 * PRD §15 / ARCHITECTURE.md §8 pause-end cue delivery — the platform's OS-scheduled local cue seam used by
 * the engine that `App()` builds itself (desktop / iOS / web). Android does NOT go through here: it builds
 * its engine in `SchedulerHolder` and injects an AlarmManager-backed seam directly, so its App() default
 * branch is never taken.
 *
 * [scheduleLocalPauseCuePlatform] schedules the "your pause is over" cue at [dueAtMillis] (epoch millis), or
 * cancels the pending one when null. [localPauseCueDeliveryPlatform] is true only where this seam actually
 * delivers the cue via the OS (so the engine skips its in-app timer to avoid a double-speak); it is false on
 * platforms that keep the in-app path (desktop / web / not-yet-wired).
 */
expect fun scheduleLocalPauseCuePlatform(dueAtMillis: Long?)

/** See [scheduleLocalPauseCuePlatform]: true where the OS-scheduled local cue is the delivery path. */
expect val localPauseCueDeliveryPlatform: Boolean

/**
 * PRD §15 / ARCHITECTURE.md §8 requirements #2/#6 (iOS APNs): hand the platform the three callbacks its native
 * push layer needs — [registerApnsToken] to publish this phone's APNs token (`device_push_token`),
 * [onRemotePush] to act on a received `{action, due_at, voice_cue}` remote push, and [onForegrounded] (#3) to
 * re-claim the account's last phone when the app becomes active (the Swift AppDelegate calls it from
 * `applicationDidBecomeActive`). `App()` calls this once after building the engine; only the iOS actual retains
 * them (for a Swift AppDelegate to invoke via `IosPushBridge`), every other platform is a no-op (Android routes
 * the same callbacks through its FirebaseMessagingService / Activity lifecycle instead).
 */
expect fun installPauseCuePushBridge(
    registerApnsToken: (token: String) -> Unit,
    onRemotePush: (action: String, dueAtIso: String?, voiceCue: String?) -> Unit,
    onForegrounded: () -> Unit,
)
