package org.example.project.scheduler.ruleline

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Fraction(x).limit_denominator(m)` as CPython answers it, on floats whose exact fraction has a denominator far past
 * a `Long` product (2^54 and more): the port compares the two bounds exactly ([BigInt]), so a real account's
 * positions in seconds never overflow there (it did, in `RuleEngineShadowTest`). The table was printed by
 * `Fraction(x).limit_denominator(m)` over these x and m (random.Random(7) for the last twelve x).
 */
class LimitDenominatorTest {
    private val reference = listOf(
            Triple(86399.123456789, 1L, 86399L to 1L),
            Triple(86399.123456789, 7L, 604794L to 7L),
            Triple(86399.123456789, 1000L, 6998329L to 81L),
            Triple(699.0000000001, 1L, 699L to 1L),
            Triple(699.0000000001, 7L, 699L to 1L),
            Triple(699.0000000001, 1000L, 699L to 1L),
            Triple(1e-07, 1L, 0L to 1L),
            Triple(1e-07, 7L, 0L to 1L),
            Triple(1e-07, 1000L, 0L to 1L),
            Triple(123456.987654321, 1L, 123457L to 1L),
            Triple(123456.987654321, 7L, 123457L to 1L),
            Triple(123456.987654321, 1000L, 10000016L to 81L),
            Triple(3600.5, 1L, 3600L to 1L),
            Triple(3600.5, 7L, 7201L to 2L),
            Triple(3600.5, 1000L, 7201L to 2L),
            Triple(0.1, 1L, 0L to 1L),
            Triple(0.1, 7L, 1L to 7L),
            Triple(0.1, 1000L, 1L to 10L),
            Triple(0.6666666666666666, 1L, 1L to 1L),
            Triple(0.6666666666666666, 7L, 2L to 3L),
            Triple(0.6666666666666666, 1000L, 2L to 3L),
            Triple(71428.57142857143, 1L, 71429L to 1L),
            Triple(71428.57142857143, 7L, 500000L to 7L),
            Triple(71428.57142857143, 1000L, 500000L to 7L),
            Triple(64766.55296663247, 1L, 64767L to 1L),
            Triple(64766.55296663247, 7L, 453366L to 7L),
            Triple(64766.55296663247, 1000L, 30569813L to 472L),
            Triple(30169.834784900384, 1L, 30170L to 1L),
            Triple(30169.834784900384, 7L, 181019L to 6L),
            Triple(30169.834784900384, 1000L, 3469531L to 115L),
            Triple(130186.89460797075, 1L, 130187L to 1L),
            Triple(130186.89460797075, 7L, 911308L to 7L),
            Triple(130186.89460797075, 1000L, 53116253L to 408L),
            Triple(14487.257333508553, 1L, 14487L to 1L),
            Triple(14487.257333508553, 7L, 57949L to 4L),
            Triple(14487.257333508553, 1000L, 10865443L to 750L),
            Triple(107176.40086133784, 1L, 107176L to 1L),
            Triple(107176.40086133784, 7L, 535882L to 5L),
            Triple(107176.40086133784, 1000L, 24864925L to 232L),
            Triple(73137.78338251711, 1L, 73138L to 1L),
            Triple(73137.78338251711, 7L, 365689L to 5L),
            Triple(73137.78338251711, 1000L, 24647433L to 337L),
            Triple(11599.78495494136, 1L, 11600L to 1L),
            Triple(11599.78495494136, 7L, 57999L to 5L),
            Triple(11599.78495494136, 1000L, 10950197L to 944L),
            Triple(101487.14663788406, 1L, 101487L to 1L),
            Triple(101487.14663788406, 7L, 710410L to 7L),
            Triple(101487.14663788406, 1000L, 92048842L to 907L),
            Triple(7499.131688396976, 1L, 7499L to 1L),
            Triple(7499.131688396976, 7L, 52494L to 7L),
            Triple(7499.131688396976, 1000L, 1822289L to 243L),
            Triple(86729.13673247717, 1L, 86729L to 1L),
            Triple(86729.13673247717, 7L, 607104L to 7L),
            Triple(86729.13673247717, 1000L, 74847245L to 863L),
            Triple(13971.084714923787, 1L, 13971L to 1L),
            Triple(13971.084714923787, 7L, 97798L to 7L),
            Triple(13971.084714923787, 1000L, 7586299L to 543L),
            Triple(18142.602668773012, 1L, 18143L to 1L),
            Triple(18142.602668773012, 7L, 90713L to 5L),
            Triple(18142.602668773012, 1000L, 17670895L to 974L),
    )

    @Test
    fun theClosestFractionIsCPythons() {
        for ((x, m, want) in reference) {
            val got = limitDenominatorOf(x, m)
            assertEquals(Rational.of(want.first, want.second), got, "$x, $m")
        }
    }

    @Test
    fun bigIntComparesExactly() {
        val a = BigInt.of(Long.MAX_VALUE) * BigInt.of(Long.MAX_VALUE)
        val b = BigInt.of(Long.MAX_VALUE) * BigInt.of(Long.MAX_VALUE - 1)
        assertEquals(1, a.compareTo(b))
        assertEquals(0, (a - b - BigInt.of(Long.MAX_VALUE)).compareTo(BigInt.ZERO))
        assertEquals(-1, (b - a).compareTo(BigInt.ZERO))
        assertEquals(0, (BigInt.of(Long.MIN_VALUE).abs() - BigInt.of(Long.MAX_VALUE) - BigInt.of(1)).compareTo(BigInt.ZERO))
    }
}
