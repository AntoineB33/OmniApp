package org.example.project.scheduler.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVSpeechBoundaryImmediate
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSData
import platform.Foundation.create

// Retained at module scope so an in-flight player/utterance is not collected mid-playback.
private val scope = CoroutineScope(Dispatchers.Default)
private val synthesizer = AVSpeechSynthesizer()
private var currentPlayer: AVAudioPlayer? = null

/**
 * PRD §15 iOS voice cue (⚠ code written on Windows, only compilable on a Mac — see docs/PAUSE_CUE_DELIVERY.md
 * step 3). Plays the same bundled Piper WAV as the other platforms via [AVAudioPlayer]; if the asset can't be
 * loaded/played it falls back to synthesizing the phrase with [AVSpeechSynthesizer].
 *
 * NOTE (runtime, not compile): for audio to play while backgrounded / the ringer is silent, the app must
 * configure an AVAudioSession (playback category) + the "Audio" background mode. The pause-end cue is normally
 * delivered as a scheduled local notification (see PauseCueLocal.ios.kt), which sounds via the system.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun playVoiceCue(cue: VoiceCue) {
    scope.launch {
        val bytes = voiceCueBytes(cue)
        if (bytes != null && bytes.isNotEmpty() && runCatching { playData(bytes) }.getOrDefault(false)) return@launch
        synthesizer.speakUtterance(AVSpeechUtterance.speechUtteranceWithString(cue.fallbackText))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun playData(bytes: ByteArray): Boolean {
    val data = bytes.usePinned { pinned ->
        // dataWithBytes:length: copies, so the NSData outlives the pinned Kotlin array safely.
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    val player = AVAudioPlayer(data = data, error = null) ?: return false
    currentPlayer = player
    player.prepareToPlay()
    return player.play()
}

actual fun stopSpeaking() {
    currentPlayer?.stop()
    currentPlayer = null
    synthesizer.stopSpeakingAtBoundary(AVSpeechBoundaryImmediate)
}
