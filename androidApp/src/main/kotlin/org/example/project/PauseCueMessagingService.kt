package org.example.project

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PRD §15 / ARCHITECTURE.md §8: receives the pause-cue data push — the only server→device channel (never a
 * WebSocket). [onNewToken] registers this device's FCM token so the Edge Function can reach it; a
 * `{type:pause_cue, action, due_at}` data message is turned into a shared-engine schedule/cancel of the local
 * OS alarm ([PauseCueScheduler]). The class is only ever instantiated by FCM, which requires a configured
 * Firebase project (google-services.json); without one it is simply never invoked.
 */
class PauseCueMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onNewToken(token: String) {
        val host = SchedulerHolder.ensure(applicationContext)
        scope.launch { runCatching { host.vm.pauseCue?.registerPushToken("phone", "fcm", token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "pause_cue") return
        val action = data["action"] ?: return
        val dueAtMillis =
            data["due_at"]?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        SchedulerHolder.ensure(applicationContext).engine.onPauseCuePush(action, dueAtMillis)
    }
}
