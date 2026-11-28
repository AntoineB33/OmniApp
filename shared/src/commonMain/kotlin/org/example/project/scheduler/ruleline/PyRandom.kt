package org.example.project.scheduler.ruleline

import kotlin.math.ceil
import kotlin.math.ln

/**
 * CPython's `random.Random` for an integer seed: the Mersenne Twister MT19937 seeded by `init_by_array`, and
 * `random`, `getrandbits`, `randint`, `choice` and `sample` as CPython 3.11+ draws them. The reference draws its
 * choices between equal targets (`ratio_plan.Plan`) and its shaking of a plan (`pace.Pace`) from it: the same seed
 * must give the same choices here.
 */
class PyRandom(seed: Long) {
    private val mt = IntArray(624)
    private var index = 625

    init {
        var a = if (seed < 0) -seed else seed
        val key = ArrayList<Int>()
        if (a == 0L) key.add(0)
        while (a != 0L) {
            key.add((a and 0xffffffffL).toInt())
            a = a ushr 32
        }
        initByArray(key.toIntArray())
    }

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until 624) {
            val prev = mt[i - 1].toLong() and 0xffffffffL
            mt[i] = ((1812433253L * (prev xor (prev ushr 30)) + i) and 0xffffffffL).toInt()
        }
        index = 624
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = maxOf(624, key.size)
        while (k > 0) {
            val prev = mt[i - 1].toLong() and 0xffffffffL
            val cur = mt[i].toLong() and 0xffffffffL
            mt[i] = (((cur xor ((prev xor (prev ushr 30)) * 1664525L)) + (key[j].toLong() and 0xffffffffL) + j) and 0xffffffffL).toInt()
            i++
            j++
            if (i >= 624) {
                mt[0] = mt[623]
                i = 1
            }
            if (j >= key.size) j = 0
            k--
        }
        k = 623
        while (k > 0) {
            val prev = mt[i - 1].toLong() and 0xffffffffL
            val cur = mt[i].toLong() and 0xffffffffL
            mt[i] = (((cur xor ((prev xor (prev ushr 30)) * 1566083941L)) - i) and 0xffffffffL).toInt()
            i++
            if (i >= 624) {
                mt[0] = mt[623]
                i = 1
            }
            k--
        }
        mt[0] = 0x80000000.toInt()
    }

    /** The next 32 random bits, as an unsigned value. */
    fun genrand(): Long {
        if (index >= 624) {
            for (kk in 0 until 624) {
                val y = (mt[kk] and 0x80000000.toInt()) or (mt[(kk + 1) % 624] and 0x7fffffff)
                var v = mt[(kk + 397) % 624] xor (y ushr 1)
                if (y and 1 != 0) v = v xor 0x9908b0df.toInt()
                mt[kk] = v
            }
            index = 0
        }
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y.toLong() and 0xffffffffL
    }

    /** A float in [0, 1) with 53 random bits. */
    fun random(): Double {
        val a = genrand() ushr 5
        val b = genrand() ushr 6
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    /** k random bits (k <= 62 here). */
    fun getrandbits(k: Int): Long {
        require(k in 0..62) { "getrandbits up to 62 bits here" }
        if (k == 0) return 0L
        if (k <= 32) return genrand() ushr (32 - k)
        var result = 0L
        var left = k
        var shift = 0
        while (left > 0) {
            var r = genrand()
            if (left < 32) r = r ushr (32 - left)
            result = result or (r shl shift)
            shift += 32
            left -= 32
        }
        return result
    }

    /** A random integer in [0, n). */
    fun randbelow(n: Int): Int {
        if (n <= 0) return 0
        val k = 32 - n.countLeadingZeroBits()
        var r = getrandbits(k)
        while (r >= n) r = getrandbits(k)
        return r.toInt()
    }

    fun randint(a: Int, b: Int): Int = a + randbelow(b - a + 1)

    fun <T> choice(seq: List<T>): T = seq[randbelow(seq.size)]

    /** k distinct elements of [population], in the order CPython draws them. */
    fun <T> sample(population: List<T>, k: Int): List<T> {
        val n = population.size
        require(k in 0..n) { "Sample larger than population or is negative" }
        val result = ArrayList<T>(k)
        var setsize = 21
        if (k > 5) setsize += pow4(ceil(ln((k * 3).toDouble()) / ln(4.0)).toInt())
        if (n <= setsize) {
            val pool = population.toMutableList()
            for (i in 0 until k) {
                val j = randbelow(n - i)
                result.add(pool[j])
                pool[j] = pool[n - i - 1]
            }
        } else {
            val selected = HashSet<Int>()
            for (i in 0 until k) {
                var j = randbelow(n)
                while (j in selected) j = randbelow(n)
                selected.add(j)
                result.add(population[j])
            }
        }
        return result
    }

    private fun pow4(e: Int): Int {
        var x = 1
        repeat(e) { x *= 4 }
        return x
    }
}
