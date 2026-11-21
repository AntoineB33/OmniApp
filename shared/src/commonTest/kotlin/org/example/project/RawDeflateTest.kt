package org.example.project

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.example.project.scheduler.sync.RawDeflate

/** [RawDeflate] on every platform: what it compresses comes back byte for byte, and JSON rows shrink. */
class RawDeflateTest {
    private fun roundTrip(bytes: ByteArray) = assertContentEquals(bytes, RawDeflate.decompress(RawDeflate.compress(bytes)))

    @Test
    fun anything_comes_back_as_it_was() {
        roundTrip(ByteArray(0))
        roundTrip(byteArrayOf(7))
        roundTrip("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".encodeToByteArray())
        val random = Random(42)
        repeat(50) { roundTrip(random.nextBytes(random.nextInt(0, 5_000))) }
        // Long runs and matches at the window's edge.
        roundTrip(ByteArray(200_000) { (it % 251).toByte() })
        roundTrip(ByteArray(100_000) { if (it % 40_000 < 300) 1 else random.nextInt(4).toByte() })
        roundTrip("✓ é 日本 🎉".repeat(100).encodeToByteArray())
    }

    @Test
    fun a_json_row_shrinks() {
        val row = """{"id":"cell/3f2a9c","parentListId":"list/7b1e","taskId":"task/9c2d","title":"Write the cover letter","weights":[1.0,1.0,1.0],"minimumMinutes":45,"resilience":{"no screen":0.0,"before bed":0.5}}"""
        val packed = RawDeflate.compress(row.encodeToByteArray())
        assertTrue(packed.size < row.length * 0.9, "${packed.size} of ${row.length}")
    }

    @Test
    fun a_corrupt_stream_is_refused_not_a_crash_elsewhere() {
        assertFailsWith<IllegalArgumentException> { RawDeflate.decompress(byteArrayOf(0x07)) }
        // A "zip bomb": a tiny stream that would expand past the bound.
        val bomb = RawDeflate.compress(ByteArray(2_000_000))
        assertFailsWith<IllegalArgumentException> { RawDeflate.decompress(bomb, maxOutput = 1_000_000) }
    }
}
