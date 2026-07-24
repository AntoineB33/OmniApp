package org.example.project.scheduler.platform

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager

/** PRD §15: this install is the phone. */
actual fun currentDeviceKind(): DeviceKind = DeviceKind.Phone

/**
 * PRD §15: the phone's activity signal is the device being **unlocked** — the foreground service runs the `t_a`
 * presence tick exactly while the user can see a screen, and at lock/screen-off stops it and reports the
 * screen-off straight to the pause-cue Edge Function. Tracked by [AndroidUnlockTracker] from the lock/unlock
 * broadcasts (with the app-foreground
 * tracker kept for the resume-time poke). This replaces the earlier foreground minute-lease signal
 * (`AndroidForegroundTracker.foreground`), which read a phone left unlocked on the calendar as inactive.
 */
actual fun isScreenActive(): Boolean = AndroidUnlockTracker.unlocked

// No-op: Android wires the unlock/lock poke directly in SchedulerHolder (`AndroidUnlockTracker.onChanged`),
// because the headless foreground service starts the engine without going through App()'s common seam.
actual fun installPlatformActivityListener(onChanged: () -> Unit) {}

/**
 * PRD §15: tracks whether the device is unlocked (screen on AND keyguard dismissed), from
 * `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` — these are dynamic-register-only
 * broadcasts, which is fine because the foreground service keeps this process alive. On a device with no
 * keyguard, SCREEN_ON is the unlock (USER_PRESENT never fires); with one, SCREEN_ON while still locked
 * stays inactive until USER_PRESENT. [onChanged] (set by the app's holder) pokes the engine so the presence
 * socket reconnects/drops within moments of the flip instead of at the next minute beat.
 */
object AndroidUnlockTracker {
    @Volatile var unlocked: Boolean = false
        private set
    private var installed = false

    /** Invoked (on the main thread) after every unlock/lock flip. */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Idempotent; main-thread only (like every Android component entry point). */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        unlocked = readUnlocked(app)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val next = when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> false
                        Intent.ACTION_USER_PRESENT -> true
                        // Screen on: unlocked only when no keyguard is in the way (none configured, or
                        // already dismissed); otherwise wait for USER_PRESENT.
                        Intent.ACTION_SCREEN_ON -> readUnlocked(ctx)
                        else -> return
                    }
                    if (next != unlocked) {
                        unlocked = next
                        onChanged?.invoke()
                    }
                }
            },
            filter,
        )
    }

    private fun readUnlocked(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return (pm?.isInteractive ?: false) && !(km?.isKeyguardLocked ?: false)
    }
}

/**
 * Counts resumed Activities via [Application.ActivityLifecycleCallbacks] — `> 0` means the app is in the
 * foreground. [install] must run before the first Activity resumes to observe it (SchedulerHolder.ensure
 * does, from MainActivity.onCreate / the service); a service-only process simply stays at 0 = background.
 */
object AndroidForegroundTracker : Application.ActivityLifecycleCallbacks {
    @Volatile private var resumedActivities = 0
    private var installed = false

    val foreground: Boolean get() = resumedActivities > 0

    /** Idempotent; main-thread only (like every Android component entry point). */
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext as? Application ?: return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivities++
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities = maxOf(0, resumedActivities - 1)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
