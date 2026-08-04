---
name: history-notif-vs-os-permission-race
description: History window shows a break notification that never appeared on the phone — fast-break first cue races the fresh-install POST_NOTIFICATIONS grant and is dropped
metadata:
  type: project
---

Symptom (2026-07-24): fast-break android deploy, History window's Notifications column shows the 5-min "Screen break" notification fired, but no OS notification appeared on the phone.

Root cause: `SchedulerEngine.notifyUser()` appends to `notificationLog` (→ History window) via `RecordNotification` BEFORE calling `sendSystemNotification()`. So the log records the engine's DECISION to fire, not OS delivery. On a fast-break deploy the break fires ~14 s after a fresh uninstall+reinstall (`account1-deploy-android.bat` wipes the runtime grant), while the POST_NOTIFICATIONS dialog is still up. `NotificationManagerCompat.notify()` silently drops the post when notifications aren't enabled at call time (wrapped in runCatching, see `SystemNotifier.android.kt:44`), so nothing displays yet the log already has the entry.

Evidence from `adb shell run-as org.example.project cat files/diagnostics.log`: install 21:01:09, engine 21:01:16, sign-in 21:01:18, `notification [Screen break] take a 5min pose and blink hard` 21:01:23. Afterward `dumpsys package` shows POST_NOTIFICATIONS granted=true and channel `omniapp_reminders` mImportance=4 unblocked, but NotificationManagerService has NO record of the post and the tray is empty → dropped app-side for lack of permission at 21:01:23.

Only bites fast-break (break engineered to fire in seconds + reinstall wipes grant). Production's first ≥2 h qualifying pause is hours out, long after the grant. Unrelated log noise: `FCM token fetch FAILED: SERVICE_NOT_AVAILABLE` is the server pause-cue push channel, not this local notification.

**Mitigated 2026-08-04 at the DEPLOY level** (not in the app): the deploy scripts now pre-grant the permission over adb, so the race window never opens on a scripted install — `adb shell pm grant org.example.project android.permission.POST_NOTIFICATIONS`, added to `scripts/internal/deploy-android-debug.bat` (accounts 1/2, alongside the existing Doze + MIUI `AUTO_START` grants) and to `scripts/account3-deploy-android.bat`. The account3 release script got ONLY this grant — deliberately not the Doze/`AUTO_START` ones, because pre-granting Doze would silently skip half of `MainActivity.maybePromptKeepAliveOnce`, and that release path is meant to behave like a genuine first-run install. Unverified against a device (none was connected when it shipped); best-effort/silenced, no-ops pre-API-33.

The app-side race still exists for any install the scripts didn't do (sideload, Play Store): `maybeRequestNotificationPermission` still asks at launch and a cue firing before the answer is still dropped silently. Proposed fixes, still not done: (1) gate cue firing / `engine.start()` on the permission result or re-post the first cue once granted; (2) plumb a delivered/dropped bool back from `sendSystemNotification` so `notificationLog` records the drop rather than the decision. See [[phone-missed-first-break-cue]], [[pause-cue-phone-stay-awake]], [[per-account-scripts]].
