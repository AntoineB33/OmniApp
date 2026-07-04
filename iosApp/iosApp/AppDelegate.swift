import UIKit
import UserNotifications
import Shared

/// PRD §15 / ARCHITECTURE.md §8 (iOS APNs, reqs #2/#6). Requests notification authorization, registers for
/// remote (APNs) notifications, forwards the device token to the shared engine via `IosPushBridge`, and routes
/// each received pause-cue background push into the engine (schedule/cancel the local cue). There is never a
/// socket to the server — the only server→device channel is this APNs push.
///
/// Xcode setup required (see docs/PAUSE_CUE_DELIVERY.md): add the "Push Notifications" capability and the
/// "Background Modes → Remote notifications" capability to the iosApp target.
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async {
                application.registerForRemoteNotifications()
            }
        }
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        IosPushBridge.shared.registerToken(token: token)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // Best-effort: with no token the account simply has no APNs target until a later successful registration.
    }

    /// PRD §15 / ARCHITECTURE.md §8, scenario #3: this phone became active — re-claim the account's last phone
    /// so the previously-active phone is told (via the server) to cancel its scheduled pause-end cue and only
    /// this phone speaks. Idempotent (re-claiming the same phone sends no cancel push).
    func applicationDidBecomeActive(_ application: UIApplication) {
        IosPushBridge.shared.notifyForegrounded()
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard (userInfo["type"] as? String) == "pause_cue",
              let action = userInfo["action"] as? String else {
            completionHandler(.noData)
            return
        }
        let dueAt = userInfo["due_at"] as? String
        let dueIso = (dueAt?.isEmpty ?? true) ? nil : dueAt
        IosPushBridge.shared.deliverPush(action: action, dueAtIso: dueIso)
        completionHandler(.newData)
    }

    /// Show the pause-end banner/sound even if the app happens to be foregrounded when the local cue fires.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}
