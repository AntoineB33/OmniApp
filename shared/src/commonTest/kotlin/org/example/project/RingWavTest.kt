package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.platform.AlarmTone
import org.example.project.scheduler.platform.AlertSound

/**
 * `docs/PLATFORMS.md`: iOS and the browser ring an alarm from one finished WAV ([AlarmTone.ringWav]) instead of
 * streaming PCM like the desktop and Android. A wrong header is a silent ring, so the header is held here.
 */
class RingWavTest {
    private fun ByteArray.int32(at: Int) =
        (0 until 4).fold(0) { acc, i -> acc or ((this[at + i].toInt() and 0xFF) shl (8 * i)) }

    private fun ByteArray.ascii(at: Int, length: Int) = (0 until length).map { this[at + it].toInt().toChar() }.joinToString("")

    @Test
    fun a_ring_is_a_mono_16_bit_wav_exactly_its_length_long() {
        val seconds = 3
        val wav = AlarmTone.ringWav(AlertSound.Beeps, seconds)
        val dataBytes = AlarmTone.SAMPLE_RATE * seconds * 2

        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(AlarmTone.SAMPLE_RATE, wav.int32(24), "sample rate")
        assertEquals(dataBytes, wav.int32(40), "the data chunk holds the whole ring, cycles repeated to its length")
        assertEquals(36 + dataBytes, wav.int32(4), "RIFF size")
        assertEquals(44 + dataBytes, wav.size)
    }
}
