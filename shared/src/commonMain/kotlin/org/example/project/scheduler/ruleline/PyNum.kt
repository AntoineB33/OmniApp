package org.example.project.scheduler.ruleline


/**
 * A number as the reference's arithmetic has it: an exact fraction, or a float once a float joined in (Python's
 * `Fraction + float` is a float). The problems' errors and the generator's scores are of this kind: exact for the
 * presence ratios and minimum lengths, floats for OmniApp's score (`score.py`). Keeping the reference's promotion
 * keeps its ties: two exact errors compare exactly.
 */
sealed class Num : Comparable<Num> {
    data class Exact(val r: Rational) : Num() {
        override fun toString(): String = r.toString()
        override fun equals(other: Any?): Boolean = other is Num && compareTo(other) == 0
        override fun hashCode(): Int = toDouble().hashCode()
    }
    data class Real(val d: Double) : Num() {
        override fun toString(): String = pyFloat(d)
        override fun equals(other: Any?): Boolean = other is Num && compareTo(other) == 0
        override fun hashCode(): Int = toDouble().hashCode()
    }

    fun toDouble(): Double = when (this) {
        is Exact -> r.toDoubleValue()
        is Real -> d
    }

    operator fun plus(o: Num): Num = if (this is Exact && o is Exact) Exact(r + o.r) else Real(toDouble() + o.toDouble())
    operator fun minus(o: Num): Num = if (this is Exact && o is Exact) Exact(r - o.r) else Real(toDouble() - o.toDouble())
    operator fun times(o: Num): Num = if (this is Exact && o is Exact) Exact(r * o.r) else Real(toDouble() * o.toDouble())
    operator fun div(o: Num): Num = if (this is Exact && o is Exact) Exact(r / o.r) else Real(toDouble() / o.toDouble())
    operator fun unaryMinus(): Num = when (this) {
        is Exact -> Exact(-r)
        is Real -> Real(-d)
    }

    operator fun plus(o: Rational): Num = this + Exact(o)
    operator fun times(o: Rational): Num = this * Exact(o)

    fun abs(): Num = if (this < ZERO) -this else this

    val isZero: Boolean get() = compareTo(ZERO) == 0

    /** Python compares a fraction and a float exactly; here through the float when one is a float. */
    override fun compareTo(other: Num): Int =
        if (this is Exact && other is Exact) r.compareTo(other.r) else toDouble().compareTo(other.toDouble())

    override fun equals(other: Any?): Boolean = other is Num && compareTo(other) == 0

    override fun hashCode(): Int = toDouble().hashCode()

    /** Python's `str`: `3`, `1/2`, `0.25`. */
    override fun toString(): String = when (this) {
        is Exact -> r.toString()
        is Real -> pyFloat(d)
    }

    companion object {
        val ZERO: Num = Exact(Rational.ZERO)
        val ONE: Num = Exact(Rational.ONE)

        fun of(r: Rational): Num = Exact(r)
        fun of(d: Double): Num = Real(d)
    }
}

/** A rational as the nearest double (the reference's `float(Fraction)`, up to the last bit for huge terms). */
fun Rational.toDoubleValue(): Double = when {
    isInfinite -> if (sign > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
    else -> num.toDouble() / den.toDouble()
}

/** The exact rational of a finite double (the reference's `Fraction(float)`); throws if it needs more than a Long. */
fun exactRational(d: Double): Rational {
    require(d.isFinite()) { "not a finite number: $d" }
    if (d == 0.0) return Rational.ZERO
    val bits = d.toRawBits()
    val negative = bits < 0
    val exponent = ((bits shr 52) and 0x7ffL).toInt()
    var mantissa = bits and 0xfffffffffffffL
    var e: Int
    if (exponent == 0) {
        e = -1074
    } else {
        mantissa = mantissa or (1L shl 52)
        e = exponent - 1075
    }
    while (mantissa and 1L == 0L && e < 0) {
        mantissa = mantissa shr 1
        e++
    }
    val m = if (negative) -mantissa else mantissa
    if (e >= 0) {
        if (e > 62 - (64 - mantissa.countLeadingZeroBits())) throw ArithmeticException("Long overflow: $d")
        return Rational.of(m shl e)
    }
    if (-e > 62) throw ArithmeticException("Long overflow: $d")
    return Rational.of(m, 1L shl -e)
}

/** Python's `repr` of a float: the shortest string that reads back the same. */
fun pyFloat(d: Double): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e16) {
        val l = d.toLong()
        return "$l.0".let { if (d == 0.0 && 1.0 / d < 0) "-0.0" else it }
    }
    return d.toString().let { s ->
        // Kotlin writes 1.0E-5 where Python writes 1e-05; both are the shortest digits
        if ('E' in s) {
            val (mant, exp) = s.split('E')
            val e = exp.toInt()
            val m = mant.removeSuffix(".0")
            m + "e" + (if (e < 0) "-" else "+") + kotlin.math.abs(e).toString().padStart(2, '0')
        } else {
            s
        }
    }
}
