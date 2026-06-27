package org.example.project.scheduler.platform

import android.content.Context
import android.os.PowerManager
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder

/** PRD §15: this install is the phone. */
actual fun currentDeviceKind(): DeviceKind = DeviceKind.Phone

/**
 * PRD §15: the phone's screen is "active" when it is interactive (on and not in ambient/doze). Uses the
 * same application [Context] source as [speak]; returns `false` when the context is not yet available.
 */
actual fun isScreenActive(): Boolean {
    val context = AndroidSchedulerStoreHolder.context ?: return false
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isInteractive
}
