package org.example.project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import org.example.project.scheduler.platform.AlarmTone
import org.example.project.scheduler.platform.Diagnostics

/**
 * PRD §18 Alarms/Timers (Android): rings one alarm or timer — plays the **acoustic guitar** arpeggio ([AlarmTone], synthesized
 * in shared code so the phone and the desktop ring with the identical waveform) on the **alarm** audio stream,
 * looping so it is audible for the whole configured length, and vibrates alongside it when asked, then stops
 * itself. If the raw PCM track cannot be created the device's own alarm ringtone rings instead — an alarm must
 * never fail silently.
 *
 * It is a foreground service rather than work done in [AlarmClockReceiver], because a receiver's process may
 * be torn down seconds after `onReceive` returns while an alarm may ring for minutes. Its notification
 * carries a **Stop** action so the user can silence the alarm before its length elapses; a second alarm
 * ringing replaces the first (one ring at a time).
 */
class AlarmRingService : Service() {
    /** The looping guitar track (the normal path). */
    private var track: AudioTrack? = null

    /** The ringtone fallback, used only when a PCM track could not be had. */
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopSelfRunnable = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Diagnostics.log("alarm ring stopped by the user")
            stopSelf()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Alarm"
        val seconds = (intent?.getIntExtra(EXTRA_SECONDS, 0) ?: 0).coerceIn(1, MAX_SECONDS)
        val vibrate = intent?.getBooleanExtra(EXTRA_VIBRATE, false) ?: false

        startForegroundNotification(title, label)
        // A new ring supersedes whatever was still sounding (same service instance).
        stopRinging()
        startRinging(vibrate)
        handler.removeCallbacks(stopSelfRunnable)
        handler.postDelayed(stopSelfRunnable, seconds * 1_000L)
        // Not sticky: a restart after a process kill must not resurrect a ring whose instant has passed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopSelfRunnable)
        stopRinging()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRinging(vibrate: Boolean) {
        // The acoustic guitar arpeggio, synthesized in shared code so the phone and the desktop ring with the
        // identical waveform (PRD §18 / AlarmTone). The device ringtone is only the fallback below.
        val played = runCatching { startGuitarLoop() }
            .onFailure { Diagnostics.log("alarm guitar sound failed: ${it.message}") }
            .getOrDefault(false)
        // An alarm that fails silently is the worst outcome there is, so a device that would not give us a
        // raw PCM track still rings — with its own alarm ringtone.
        if (!played) startRingtoneFallback()

        if (!vibrate) return
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return@runCatching
            vibrator = vib
            // Repeating 1 s buzz / 1 s pause until the ring is stopped (repeat index 0 = loop the pattern).
            val pattern = longArrayOf(0, 1_000, 1_000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        }.onFailure { Diagnostics.log("alarm vibration failed: ${it.message}") }
    }

    /**
     * Plays [AlarmTone]'s loop cycle on the **alarm** stream, looping in hardware until the service stops it —
     * a `MODE_STATIC` track holding the whole cycle, with `setLoopPoints(…, -1)`. No writer thread is needed
     * (the cycle fits in one buffer, ~260 kB) and no polling: the service's own `stopSelfRunnable` ends the
     * ring at the configured length. Returns false if the track could not be created or filled.
     */
    private fun startGuitarLoop(): Boolean {
        val pcm = AlarmTone.loopPcm()
        val frames = pcm.size / 2 // 16-bit mono
        if (frames <= 0) return false
        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(AlarmTone.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            pcm.size,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (audioTrack.state != AudioTrack.STATE_NO_STATIC_DATA) {
            runCatching { audioTrack.release() }
            return false
        }
        track = audioTrack
        if (audioTrack.write(pcm, 0, pcm.size) < pcm.size) {
            stopRinging()
            return false
        }
        // Loop points are only settable on a filled MODE_STATIC track; -1 = repeat until stopped.
        audioTrack.setLoopPoints(0, frames, -1)
        audioTrack.play()
        return true
    }

    /** The device's own alarm (else notification) ringtone, looping — used only when [startGuitarLoop] fails. */
    private fun startRingtoneFallback() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
                ?: return@runCatching
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Diagnostics.log("alarm ringtone fallback failed: ${it.message}") }
    }

    private fun stopRinging() {
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startForegroundNotification(title: String, label: String) {
        val notification = buildNotification(title, label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, label: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    // The service plays the sound itself (on the alarm stream, for the configured length), so
                    // the channel is silent — otherwise the notification would layer a second sound over it.
                    NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "A ringing alarm"
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
            }
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(label.ifBlank { title })
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "omniapp_alarms"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "org.example.project.ALARM_STOP"
        private const val EXTRA_LABEL = "org.example.project.ALARM_LABEL"
        // PRD §18 Timers: "Alarm" or "Timer" — the ring is identical, only what it is called differs.
        private const val EXTRA_TITLE = "org.example.project.ALARM_TITLE"
        private const val EXTRA_SECONDS = "org.example.project.ALARM_SECONDS"
        private const val EXTRA_VIBRATE = "org.example.project.ALARM_VIBRATE"
        private const val MAX_SECONDS = 600

        /**
         * Rings for [seconds] with [label] shown under [title], vibrating when [vibrate]. Best-effort (never
         * throws). [title] is "Alarm" or "Timer" (PRD §18) — the sound and the length are the same either way.
         */
        fun ring(context: Context, label: String, seconds: Int, vibrate: Boolean, title: String = "Alarm") {
            val intent = Intent(context, AlarmRingService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_SECONDS, seconds)
                .putExtra(EXTRA_VIBRATE, vibrate)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Diagnostics.log("alarm ring service could not start: ${it.message}") }
        }
    }
}
