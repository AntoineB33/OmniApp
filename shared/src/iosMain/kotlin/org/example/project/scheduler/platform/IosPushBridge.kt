package org.example.project.scheduler.platform

/**
 * PRD §15 / ARCHITECTURE.md §8 requirements #2/#6 — the Swift ⇄ Kotlin seam for iOS APNs. The shared engine
 * installs its callbacks via [install] (from `installPauseCuePushBridge`, called by `App()`); the Swift
 * `AppDelegate` (see iosApp/…/AppDelegate.swift + docs/PAUSE_CUE_DELIVERY.md) calls [registerToken] with the
 * APNs device token and [deliverPush] for each received pause-cue remote push. Exposed to Swift as
 * `IosPushBridge.shared`. Callbacks are invoked on the thread the AppDelegate calls from (the main thread for
 * both APNs delegate callbacks), which is where the engine/ViewModel already run.
 */
object IosPushBridge {
    private var registerApnsToken: ((String) -> Unit)? = null
    private var onRemotePush: ((String, String?) -> Unit)? = null
    private var onForegrounded: (() -> Unit)? = null

    fun install(
        registerApnsToken: (String) -> Unit,
        onRemotePush: (String, String?) -> Unit,
        onForegrounded: () -> Unit,
    ) {
        this.registerApnsToken = registerApnsToken
        this.onRemotePush = onRemotePush
        this.onForegrounded = onForegrounded
    }

    /** Swift: call from `didRegisterForRemoteNotificationsWithDeviceToken` with the token as a hex string. */
    fun registerToken(token: String) {
        registerApnsToken?.invoke(token)
    }

    /** Swift: call from the remote-notification handler with the push's `action` and ISO-8601 `due_at`. */
    fun deliverPush(action: String, dueAtIso: String?) {
        onRemotePush?.invoke(action, dueAtIso)
    }

    /**
     * Swift: call from `applicationDidBecomeActive` (scenario #3). Re-claims the account's last phone so a phone
     * re-opened after another phone was used tells the server to cancel the previous phone's scheduled cue.
     */
    fun notifyForegrounded() {
        onForegrounded?.invoke()
    }
}
