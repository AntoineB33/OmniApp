package org.example.project

import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import org.example.project.scheduler.sync.RawDeflate

/** [RawDeflate] is RFC 1951: the JDK's zlib reads what it writes, and it reads what zlib writes (dynamic blocks too). */
class RawDeflateJvmTest {
    private val samples: List<ByteArray> =
        Random(7).let { r ->
            listOf(
                ByteArray(0),
                """{"id":"task/1","title":"Write","occurrences":["cell/1","cell/2"]}""".repeat(40).encodeToByteArray(),
                r.nextBytes(10_000),
                ByteArray(300_000) { (it / 7 % 13).toByte() },
            )
        }

    @Test
    fun zlib_inflates_what_it_compresses() {
        for (bytes in samples) {
            val inflater = Inflater(true)
            inflater.setInput(RawDeflate.compress(bytes))
            val out = ByteArray(bytes.size)
            var n = 0
            while (n < bytes.size) n += inflater.inflate(out, n, bytes.size - n).also { check(it > 0 || inflater.finished()) }
            assertContentEquals(bytes, out)
        }
    }

    @Test
    fun it_inflates_what_zlib_compresses_at_every_level_and_strategy() {
        for (bytes in samples) for (level in listOf(0, 1, 6, 9)) for (strategy in listOf(Deflater.DEFAULT_STRATEGY, Deflater.HUFFMAN_ONLY, Deflater.FILTERED)) {
            val deflater = Deflater(level, true).apply { setStrategy(strategy); setInput(bytes); finish() }
            val buffer = ByteArray(bytes.size * 2 + 1024)
            var n = 0
            while (!deflater.finished()) n += deflater.deflate(buffer, n, buffer.size - n)
            assertContentEquals(bytes, RawDeflate.decompress(buffer.copyOf(n)), "level $level strategy $strategy")
        }
    }
}
