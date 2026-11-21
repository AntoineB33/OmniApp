package org.example.project.scheduler.sync

/**
 * **Raw DEFLATE (RFC 1951), in common code** — what [E2ee] compresses a value with before sealing it.
 *
 * Why: a ciphertext cannot be compressed, and base64 grows it by a third; measured 2026-11-21 on the release account's
 * 3 178 rows, sealing alone made the rows 1.46× their plaintext and broke the egress budget (`ServerQuotaTest`), while
 * deflating first made them 0.66×. Why here and not the platform's zlib: one implementation, the same bytes on the JVM,
 * Android, iOS and both web targets, with no asynchronous browser stream API in the way.
 *
 * [compress] writes ONE block with the fixed Huffman codes (LZ77 matches, greedy, hash chains): on short JSON rows
 * that is within 6 % of zlib's best (0.66× against 0.62×) for a fraction of the code. [decompress] reads any raw
 * DEFLATE stream — stored, fixed and dynamic blocks — so `java.util.zip` output opens too (`RawDeflateJvmTest`).
 */
object RawDeflate {
    private const val WINDOW = 32_768
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val MAX_CHAIN = 64
    private const val HASH_BITS = 15

    /** A decompressed value larger than this is refused: a hostile server could otherwise send a "zip bomb". */
    const val MAX_OUTPUT: Int = 64 * 1024 * 1024

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    // ----- compress ------------------------------------------------------------------------------------------------

    private class BitWriter(capacity: Int) {
        var out = ByteArray(maxOf(16, capacity))
        var size = 0
        private var bits = 0L
        private var count = 0

        fun write(value: Int, n: Int) {
            bits = bits or ((value.toLong() and ((1L shl n) - 1)) shl count)
            count += n
            while (count >= 8) {
                if (size == out.size) out = out.copyOf(out.size * 2)
                out[size++] = bits.toByte()
                bits = bits ushr 8
                count -= 8
            }
        }

        /** A Huffman code is sent most significant bit first: reversed into the LSB-first stream. */
        fun writeCode(code: Int, n: Int) {
            var reversed = 0
            for (i in 0 until n) reversed = reversed or (((code ushr i) and 1) shl (n - 1 - i))
            write(reversed, n)
        }

        fun finish(): ByteArray {
            if (count > 0) write(0, 8 - count)
            return out.copyOf(size)
        }
    }

    private fun writeLiteral(w: BitWriter, symbol: Int) {
        when {
            symbol <= 143 -> w.writeCode(0x30 + symbol, 8)
            symbol <= 255 -> w.writeCode(0x190 + symbol - 144, 9)
            symbol <= 279 -> w.writeCode(symbol - 256, 7)
            else -> w.writeCode(0xC0 + symbol - 280, 8)
        }
    }

    private fun writeMatch(w: BitWriter, length: Int, distance: Int) {
        var l = LENGTH_BASE.size - 1
        while (LENGTH_BASE[l] > length) l--
        writeLiteral(w, 257 + l)
        if (LENGTH_EXTRA[l] > 0) w.write(length - LENGTH_BASE[l], LENGTH_EXTRA[l])
        var d = DIST_BASE.size - 1
        while (DIST_BASE[d] > distance) d--
        w.writeCode(d, 5)
        if (DIST_EXTRA[d] > 0) w.write(distance - DIST_BASE[d], DIST_EXTRA[d])
    }

    fun compress(input: ByteArray): ByteArray {
        val w = BitWriter(input.size / 2 + 16)
        w.write(1, 1) // BFINAL
        w.write(1, 2) // BTYPE = 01, fixed Huffman codes
        val head = IntArray(1 shl HASH_BITS) { -1 }
        val prev = IntArray(input.size)
        fun hash(i: Int): Int =
            (((input[i].toInt() and 0xFF) shl 10) xor ((input[i + 1].toInt() and 0xFF) shl 5) xor (input[i + 2].toInt() and 0xFF)) and
                ((1 shl HASH_BITS) - 1)
        fun insert(i: Int) {
            if (i + MIN_MATCH > input.size) return
            val h = hash(i)
            prev[i] = head[h]
            head[h] = i
        }
        var i = 0
        while (i < input.size) {
            var bestLength = 0
            var bestDistance = 0
            if (i + MIN_MATCH <= input.size) {
                var candidate = head[hash(i)]
                var chain = 0
                val limit = minOf(MAX_MATCH, input.size - i)
                while (candidate >= 0 && i - candidate <= WINDOW && chain++ < MAX_CHAIN) {
                    var length = 0
                    while (length < limit && input[candidate + length] == input[i + length]) length++
                    if (length > bestLength) {
                        bestLength = length
                        bestDistance = i - candidate
                        if (length == limit) break
                    }
                    candidate = prev[candidate]
                }
            }
            if (bestLength >= MIN_MATCH) {
                writeMatch(w, bestLength, bestDistance)
                for (k in 0 until bestLength) insert(i + k)
                i += bestLength
            } else {
                writeLiteral(w, input[i].toInt() and 0xFF)
                insert(i)
                i++
            }
        }
        writeLiteral(w, 256) // end of block
        return w.finish()
    }

    // ----- decompress ----------------------------------------------------------------------------------------------

    private class BitReader(val data: ByteArray) {
        var pos = 0
        private var bits = 0
        private var count = 0

        fun bit(): Int {
            if (count == 0) {
                if (pos >= data.size) throw IllegalArgumentException("deflate stream ends early")
                bits = data[pos++].toInt() and 0xFF
                count = 8
            }
            val b = bits and 1
            bits = bits ushr 1
            count--
            return b
        }

        fun read(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = v or (bit() shl i)
            return v
        }

        fun alignToByte() {
            count = 0
        }
    }

    /** A canonical Huffman code, as `puff.c` keeps it: how many codes of each length, and the symbols in order. */
    private class Huffman(lengths: IntArray, n: Int) {
        val count = IntArray(16)
        val symbol = IntArray(n)

        init {
            for (s in 0 until n) count[lengths[s]]++
            val offs = IntArray(16)
            for (len in 1 until 15) offs[len + 1] = offs[len] + count[len]
            for (s in 0 until n) if (lengths[s] != 0) symbol[offs[lengths[s]]++] = s
        }

        fun decode(r: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                code = code or r.bit()
                val c = count[len]
                if (code - c < first) return symbol[index + (code - first)]
                index += c
                first += c
                first = first shl 1
                code = code shl 1
            }
            throw IllegalArgumentException("invalid Huffman code")
        }
    }

    private val FIXED_LITERALS: Huffman by lazy {
        val lengths = IntArray(288) { s -> when { s < 144 -> 8; s < 256 -> 9; s < 280 -> 7; else -> 8 } }
        Huffman(lengths, 288)
    }
    private val FIXED_DISTANCES: Huffman by lazy { Huffman(IntArray(30) { 5 }, 30) }

    fun decompress(input: ByteArray, maxOutput: Int = MAX_OUTPUT): ByteArray {
        val r = BitReader(input)
        var out = ByteArray(maxOf(64, input.size * 4))
        var size = 0
        fun put(b: Byte) {
            if (size == out.size) {
                require(size < maxOutput) { "deflate stream larger than $maxOutput bytes" }
                out = out.copyOf(minOf(maxOutput, out.size * 2))
            }
            out[size++] = b
        }
        do {
            val last = r.bit()
            when (r.read(2)) {
                0 -> {
                    r.alignToByte()
                    require(r.pos + 4 <= input.size) { "deflate stored block ends early" }
                    val len = (input[r.pos].toInt() and 0xFF) or ((input[r.pos + 1].toInt() and 0xFF) shl 8)
                    val nlen = (input[r.pos + 2].toInt() and 0xFF) or ((input[r.pos + 3].toInt() and 0xFF) shl 8)
                    require(len == (nlen xor 0xFFFF)) { "deflate stored block length mismatch" }
                    r.pos += 4
                    require(r.pos + len <= input.size) { "deflate stored block ends early" }
                    for (k in 0 until len) put(input[r.pos + k])
                    r.pos += len
                }
                1 -> size = inflateBlock(r, FIXED_LITERALS, FIXED_DISTANCES, { out }, { out = it }, size, maxOutput)
                2 -> {
                    val hlit = r.read(5) + 257
                    val hdist = r.read(5) + 1
                    val hclen = r.read(4) + 4
                    val codeLengths = IntArray(19)
                    for (k in 0 until hclen) codeLengths[CODE_LENGTH_ORDER[k]] = r.read(3)
                    val lengthCode = Huffman(codeLengths, 19)
                    val lengths = IntArray(hlit + hdist)
                    var k = 0
                    while (k < hlit + hdist) {
                        val sym = lengthCode.decode(r)
                        if (sym < 16) {
                            lengths[k++] = sym
                        } else {
                            val (value, repeat) = when (sym) {
                                16 -> { require(k > 0) { "deflate repeat with no previous length" }; lengths[k - 1] to 3 + r.read(2) }
                                17 -> 0 to 3 + r.read(3)
                                else -> 0 to 11 + r.read(7)
                            }
                            require(k + repeat <= hlit + hdist) { "deflate code lengths overrun" }
                            repeat(repeat) { lengths[k++] = value }
                        }
                    }
                    val literals = Huffman(lengths.copyOfRange(0, hlit), hlit)
                    val distances = Huffman(lengths.copyOfRange(hlit, hlit + hdist), hdist)
                    size = inflateBlock(r, literals, distances, { out }, { out = it }, size, maxOutput)
                }
                else -> throw IllegalArgumentException("invalid deflate block type")
            }
        } while (last == 0)
        return out.copyOf(size)
    }

    private fun inflateBlock(
        r: BitReader,
        literals: Huffman,
        distances: Huffman,
        buffer: () -> ByteArray,
        grow: (ByteArray) -> Unit,
        start: Int,
        maxOutput: Int,
    ): Int {
        var size = start
        fun ensure(extra: Int) {
            val out = buffer()
            if (size + extra <= out.size) return
            require(size + extra <= maxOutput) { "deflate stream larger than $maxOutput bytes" }
            var cap = out.size
            while (cap < size + extra) cap *= 2
            grow(out.copyOf(minOf(maxOutput, cap)))
        }
        while (true) {
            val sym = literals.decode(r)
            when {
                sym < 256 -> {
                    ensure(1)
                    buffer()[size++] = sym.toByte()
                }
                sym == 256 -> return size
                else -> {
                    val l = sym - 257
                    require(l < 29) { "invalid deflate length code" }
                    val length = LENGTH_BASE[l] + r.read(LENGTH_EXTRA[l])
                    val d = distances.decode(r)
                    require(d < 30) { "invalid deflate distance code" }
                    val distance = DIST_BASE[d] + r.read(DIST_EXTRA[d])
                    require(distance <= size) { "deflate distance too far back" }
                    ensure(length)
                    val out = buffer()
                    for (k in 0 until length) {
                        out[size] = out[size - distance]
                        size++
                    }
                }
            }
        }
    }
}
