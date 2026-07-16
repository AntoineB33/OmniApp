package org.example.project.scheduler.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/** PRD §15: this install is the phone. */
actual fun currentDeviceKind(): DeviceKind = DeviceKind.Phone

/**
 * The phone's ONLY activity signal: the app is in the **foreground** (an Activity is resumed). Screen-on
 * with the app backgrounded — or killed with just the foreground service running — reads as inactive; the
 * engine turns this into one-minute activity leases, so a walk-away registers within a minute. This
 * deliberately replaced `PowerManager.isInteractive` (screen-on said nothing about the app being used).
 */
actual fun isScreenActive(): Boolean = AndroidForegroundTracker.foreground

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
