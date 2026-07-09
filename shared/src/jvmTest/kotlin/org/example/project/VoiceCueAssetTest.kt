package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.project.scheduler.platform.VoiceCue
import org.example.project.scheduler.platform.voiceCueBytes

/**
 * PRD §15: the voice cues now play a **bundled** pre-rendered Piper WAV (the same asset on every platform),
 * not live TTS. This guards the wiring that the engine depends on: every [VoiceCue] resolves to a real bundled
 * resource that is a canonical PCM WAV. A broken enum path / missing asset would silently drop the cue at
 * runtime (the platform players fall back, and the phone had no working fallback — the original anomaly), so
 * catching it here keeps the shared-voice contract honest.
 */
class VoiceCueAssetTest {

    @Test
    fun every_cue_resolves_to_a_canonical_pcm_wav() = runBlocking {
        for (cue in VoiceCue.entries) {
            val bytes = voiceCueBytes(cue)
            assertNotNull(bytes, "no bundled audio for $cue (${cue.resourcePath})")
            assertTrue(bytes.size > 44, "audio for $cue is too small to be a WAV (${bytes.size} bytes)")
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII), "$cue is not a RIFF file")
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII), "$cue is not a WAVE file")
        }
    }
}
