package org.example.project.scheduler.platform

import platform.AVFAudio.AVSpeechBoundaryImmediate
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance

// Retained at module scope so an in-flight utterance is not collected mid-speech.
private val synthesizer = AVSpeechSynthesizer()

/**
 * PRD §15 iOS voice cue. NOTE (runtime, not compile): for audio to play while the app is backgrounded / the
 * ringer is silent, the app must configure an AVAudioSession with a playback category and the "Audio"
 * background mode — see docs/PAUSE_CUE_DELIVERY.md. The pause-end cue itself is normally delivered as a
 * scheduled local notification (see PauseCueLocal.ios.kt), which speaks/sounds via the system, not this path.
 */
actual fun speak(text: String) {
    val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
    synthesizer.speakUtterance(utterance)
}

actual fun stopSpeaking() {
    synthesizer.stopSpeakingAtBoundary(AVSpeechBoundaryImmediate)
}
