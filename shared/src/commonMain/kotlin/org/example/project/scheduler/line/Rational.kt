package org.example.project.scheduler.line

/**
 * An exact rational number, or +-infinity: the coordinate of the line (`docs/invariants/line.md`).
 *
 * The reference (`C:\code\line_manager\reverse_logic\region.py`) uses `fractions.Fraction` mixed with `math.inf`;
 * this is the same thing over [Long], reduced, with the denominator positive. An infinity is `den == 0` and
 * `num == +-1`. Arithmetic that would overflow a [Long] throws rather than wraps: a wrong coordinate would break
 * "move then move back" silently, which is exactly what the exact type is for.
 */
class Rational private constructor(val num: Long, val den: Long) : Comparable<Rational> {
    val isInfinite: Boolean get() = den == 0L
    val isFinite: Boolean get() = den != 0L
    val isInteger: Boolean get() = den == 1L
    val sign: Int get() = num.compareTo(0L)

    operator fun plus(o: Rational): Rational {
        if (isInfinite || o.isInfinite) {
            require(!(isInfinite && o.isInfinite && num != o.num)) { "inf - inf" }
            return if (isInfinite) this else o
        }
        if (den == o.den) return of(addExact(num, o.num), den)
        val g = gcd(den, o.den)
        val l = mulExact(den / g, o.den)
        return of(addExact(mulExact(num, l / den), mulExact(o.num, l / o.den)), l)
    }

    operator fun minus(o: Rational): Rational = this + (-o)

    operator fun unaryMinus(): Rational = Rational(negateExact(num), den)

    operator fun times(o: Rational): Rational {
        require(isFinite && o.isFinite) { "product of an infinity" }
        if (num == 0L || o.num == 0L) return ZERO
        val g1 = gcd(num, o.den)
        val g2 = gcd(o.num, den)
        return of(mulExact(num / g1, o.num / g2), mulExact(den / g2, o.den / g1))
    }

    operator fun times(k: Long): Rational = this * of(k)

    operator fun times(k: Int): Rational = this * of(k.toLong())

    operator fun div(o: Rational): Rational {
        require(isFinite && o.isFinite && o.num != 0L) { "division by $o" }
        return this * Rational(if (o.num < 0) negateExact(o.den) else o.den, if (o.num < 0) negateExact(o.num) else o.num)
    }

    operator fun div(k: Long): Rational = this / of(k)

    operator fun div(k: Int): Rational = this / of(k.toLong())

    /** Python's `%`: the result has the sign of [o] (`x - o * floor(x / o)`). */
    operator fun rem(o: Rational): Rational = this - o * of((this / o).floor())

    fun floor(): Long {
        require(isFinite) { "floor of an infinity" }
        return num.floorDiv(den)
    }

    fun ceil(): Long {
        require(isFinite) { "ceil of an infinity" }
        return -((-num).floorDiv(den))
    }

    fun abs(): Rational = if (num < 0) -this else this

    /** The value as a [Long]; it must be an integer. */
    fun toLongExact(): Long {
        require(isInteger) { "$this is not an integer" }
        return num
    }

    override fun compareTo(other: Rational): Int {
        if (isInfinite || other.isInfinite) {
            val a = if (isInfinite) num * 2 else 0L
            val b = if (other.isInfinite) other.num * 2 else 0L
            return a.compareTo(b)
        }
        if (den == other.den) return num.compareTo(other.num)
        return compareFractions(num, den, other.num, other.den)
    }

    override fun equals(other: Any?): Boolean = other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

    /** Python's `str(Fraction)`: `3`, `-1/2`; `inf` and `-inf` as Python prints them. */
    override fun toString(): String = when {
        isInfinite -> if (num > 0) "inf" else "-inf"
        den == 1L -> num.toString()
        else -> "$num/$den"
    }

    companion object {
        val ZERO = Rational(0L, 1L)
        val ONE = Rational(1L, 1L)
        val INF = Rational(1L, 0L)
        val NEG_INF = Rational(-1L, 0L)

        fun of(n: Long): Rational = Rational(n, 1L)

        fun of(n: Int): Rational = Rational(n.toLong(), 1L)

        fun of(n: Long, d: Long): Rational {
            require(d != 0L) { "zero denominator" }
            if (n == 0L) return ZERO
            val g = gcd(n, d)
            val s = if (d < 0) -1L else 1L
            return Rational(mulExact(n / g, s), mulExact(d / g, s))
        }

        fun of(n: Int, d: Int): Rational = of(n.toLong(), d.toLong())

        /** Parses what [toString] prints. */
        fun parse(s: String): Rational = when (s) {
            "inf" -> INF
            "-inf" -> NEG_INF
            else -> {
                val i = s.indexOf('/')
                if (i < 0) of(s.toLong()) else of(s.substring(0, i).toLong(), s.substring(i + 1).toLong())
            }
        }

        fun gcd(a: Long, b: Long): Long {
            var x = if (a < 0) -a else a
            var y = if (b < 0) -b else b
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }

        /** The greatest common divisor of rationals (0 for none): every value is an integer multiple of it. */
        fun gcdOf(values: Iterable<Rational>): Rational {
            var g = ZERO
            for (v in values) {
                if (v.num == 0L) continue
                if (g.num == 0L) {
                    g = v.abs()
                    continue
                }
                val den = lcmLong(g.den, v.den)
                g = of(gcd(mulExact(g.num, den / g.den), mulExact(v.num, den / v.den)), den)
            }
            return g
        }

        /** The least common multiple of two positive rationals. */
        fun lcm(a: Rational, b: Rational): Rational {
            val d = lcmLong(a.den, b.den)
            return of(lcmLong(mulExact(a.num, d / a.den), mulExact(b.num, d / b.den)), d)
        }

        fun lcmLong(a: Long, b: Long): Long = if (a == 0L || b == 0L) 0L else mulExact(a / gcd(a, b), b).let { if (it < 0) -it else it }

        private fun mulExact(a: Long, b: Long): Long {
            val r = a * b
            if (a != 0L && (r / a != b || (a == -1L && b == Long.MIN_VALUE))) throw ArithmeticException("overflow: $a * $b")
            return r
        }

        private fun addExact(a: Long, b: Long): Long {
            val r = a + b
            if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("overflow: $a + $b")
            return r
        }

        private fun negateExact(a: Long): Long {
            if (a == Long.MIN_VALUE) throw ArithmeticException("overflow: -$a")
            return -a
        }

        /** Compares a/b with c/d (b, d > 0) without overflowing: by their continued fractions. */
        private fun compareFractions(a: Long, b: Long, c: Long, d: Long): Int {
            var n1 = a
            var d1 = b
            var n2 = c
            var d2 = d
            var flip = 1
            while (true) {
                val q1 = n1.floorDiv(d1)
                val q2 = n2.floorDiv(d2)
                if (q1 != q2) return flip * q1.compareTo(q2)
                val r1 = n1 - q1 * d1
                val r2 = n2 - q2 * d2
                if (r1 == 0L && r2 == 0L) return 0
                if (r1 == 0L) return -flip
                if (r2 == 0L) return flip
                // n/d = q + r/d with 0 < r < d: the larger fraction has the smaller d/r.
                n1 = d1
                d1 = r1
                n2 = d2
                d2 = r2
                flip = -flip
            }
        }
    }
}

fun min(a: Rational, b: Rational): Rational = if (a <= b) a else b

fun max(a: Rational, b: Rational): Rational = if (a >= b) a else b

val Int.q: Rational get() = Rational.of(this)

val Long.q: Rational get() = Rational.of(this)

/** `n over d` as a rational: `1 over 2`. */
infix fun Int.over(d: Int): Rational = Rational.of(this, d)
