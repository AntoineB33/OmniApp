package org.example.project

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Persists the debug **fast-break** overrides ([DebugFlags.breakDurationMillisOverride] and friends) on the
 * phone, so they survive a process death or a reboot.
 *
 * They arrive as launch-Intent extras from `scripts\internal\deploy-android-debug.bat`, which only
 * [MainActivity] can see. But the process is just as often started with **no Activity at all** — a reboot goes
 * `BootReceiver` → [SchedulerService] → [SchedulerHolder.ensure], and so do the FCM / alarm receivers. Without
 * this store such a start rebuilt the screen breaks at their PRODUCTION timings while the account's
 * server-side `break_config.length_ms` was still the shrunk one the deploy script upserted, so the phone
 * published production due instants (an hour out) against a 5-second server cue — the two halves of the
 * 5-min break disagreeing. Persisting the overrides keeps a fast-break deploy in force until the next deploy
 * says otherwise.
 *
 * **Debug builds only.** Every entry point is gated on `FLAG_DEBUGGABLE`, so a release install (account 3)
 * neither writes nor reads these — it keeps the production timings unconditionally, as before.
 */
object AndroidDebugFlagStore {

    private const val PREFS = "omniapp_debug_flags"

    private const val KEY_DURATION = "break_duration_ms"
    private const val KEY_INTERVAL = "break_interval_ms"

    private const val EXTRA_DURATION = "omniapp_break_duration_ms"
    private const val EXTRA_INTERVAL = "omniapp_break_interval_ms"

    /** The deploy scripts always pass the login extras — their presence is what marks a script launch. */
    private const val EXTRA_LOGIN_USER = "omniapp_login_user"

    /**
     * Applies (and remembers) the fast-break overrides a **deploy launch** carried.
     *
     * A script launch is authoritative for the whole install: it writes exactly the knobs it passed, so a plain
     * `account1-deploy-android.bat` after a fast-break one CLEARS the remembered values rather than inheriting
     * them. (The scripts uninstall first, which already wipes these prefs; the explicit clear keeps the rule
     * true even for a launch that skipped the reinstall.) An ordinary launch — the user tapping the icon —
     * carries no extras and changes nothing; [restore] is what re-applies the remembered values there.
     *
     * Must run BEFORE [SchedulerHolder.ensure] builds the VM (which seeds the screen breaks).
     */
    fun captureFromLaunchIntent(context: Context, intent: Intent?) {
        if (!isDebuggable(context)) return
        val duration = intent.longExtra(EXTRA_DURATION)
        val interval = intent.longExtra(EXTRA_INTERVAL)
        val carriesBreakExtras = duration != null || interval != null
        val isDeployLaunch = !intent?.getStringExtra(EXTRA_LOGIN_USER).isNullOrBlank()
        if (!carriesBreakExtras && !isDeployLaunch) return

        context.prefs().edit().apply {
            putOrRemove(KEY_DURATION, duration)
            putOrRemove(KEY_INTERVAL, interval)
        }.apply()

        DebugFlags.breakDurationMillisOverride = duration
        DebugFlags.breakIntervalMillisOverride = interval
    }

    /**
     * Re-applies the remembered overrides to [DebugFlags]. Called at the head of [SchedulerHolder.ensure], so
     * EVERY process start gets them — including the Activity-less ones (boot service, FCM message, alarm
     * receiver) that no Intent extra can reach.
     *
     * Authoritative in both directions: an absent key clears the flag, so this can never resurrect a value a
     * later deploy dropped. Safe to run after [captureFromLaunchIntent] in the same startup (it re-reads what
     * that just wrote).
     */
    fun restore(context: Context) {
        if (!isDebuggable(context)) return
        val prefs = context.prefs()
        DebugFlags.breakDurationMillisOverride = prefs.longOrNull(KEY_DURATION)
        DebugFlags.breakIntervalMillisOverride = prefs.longOrNull(KEY_INTERVAL)
    }

    private fun Context.prefs() = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isDebuggable(context: Context) =
        (context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // The scripts pass the knobs as string extras (`--es`), so parse rather than getLongExtra.
    private fun Intent?.longExtra(name: String): Long? = this?.getStringExtra(name)?.toLongOrNull()

    private fun android.content.SharedPreferences.longOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }
}
