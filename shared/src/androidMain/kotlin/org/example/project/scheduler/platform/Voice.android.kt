package org.example.project.scheduler.platform

import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder

/**
 * PRD §15 (20s look-away) voice cue on Android, backed by the platform [TextToSpeech] engine.
 *
 * The engine initializes asynchronously, so [speak] is a no-op until [ready] flips true (early cues fired
 * before init complete are simply dropped — acceptable for fire-and-forget cues). Utterances use
 * [TextToSpeech.QUEUE_ADD] so cues that come due together play back-to-back in FIFO order, matching the
 * desktop worker's serialized queue; [stopSpeaking] flushes the utterance in progress and the queue behind
 * it via [TextToSpeech.QUEUE_FLUSH] semantics ([TextToSpeech.stop]).
 */
private val ready = AtomicBoolean(false)

private val tts: TextToSpeech? by lazy {
    val context = AndroidSchedulerStoreHolder.context ?: return@lazy null
    var engine: TextToSpeech? = null
    engine = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            runCatching { engine?.language = Locale.US }
            ready.set(true)
        }
    }
    engine
}

actual fun speak(text: String) {
    val sanitized = text.replace('\n', ' ').trim()
    if (sanitized.isEmpty()) return
    runCatching {
        val engine = tts ?: return
        if (!ready.get()) return
        engine.speak(sanitized, TextToSpeech.QUEUE_ADD, null, sanitized.hashCode().toString())
    }
}

actual fun stopSpeaking() {
    runCatching { tts?.stop() }
}
