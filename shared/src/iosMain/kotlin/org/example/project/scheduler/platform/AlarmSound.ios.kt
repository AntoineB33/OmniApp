package org.example.project.scheduler.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.Foundation.NSData
import platform.Foundation.create

private val ringScope = CoroutineScope(Dispatchers.Default)

// Retained so the ring is not collected mid-play; a newer ring stops and replaces it.
private var ringPlayer: AVAudioPlayer? = null

/**
 * PRD §18 Alarms on iOS: rung IN-PROCESS, from the engine's now-line sweep, exactly like the desktop — iOS has no
 * OS alarm seam yet (`docs/PLATFORMS.md`), so a ring sounds while the app is running and not once it is killed.
 * The row's sound is built as one WAV ([AlarmTone.ringWav], at the app's volume) and handed to [AVAudioPlayer].
 * (⚠ written on Windows: compiles against the iOS metadata here, untested on a device.)
 */
@OptIn(ExperimentalForeignApi::class)
actual fun ringAlarmPlatform(label: String, soundSeconds: Int, sound: AlertSound?, vibrate: Boolean) {
    if (soundSeconds <= 0) return
    if (vibrate) AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    if (sound == null) return
    ringScope.launch {
        val wav = AlarmTone.ringWav(sound, soundSeconds)
        val data = wav.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = wav.size.toULong())
        }
        ringPlayer?.stop()
        val player = AVAudioPlayer(data = data, error = null)
        ringPlayer = player
        player.prepareToPlay()
        player.play()
    }
}
