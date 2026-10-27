package org.example.project.scheduler.line

import org.example.project.scheduler.line.LineOp.Add
import org.example.project.scheduler.line.LineOp.Extend
import org.example.project.scheduler.line.LineOp.Move
import org.example.project.scheduler.line.LineOp.Remove
import org.example.project.scheduler.line.LineOp.Retract
import kotlin.random.Random

/** The random generators of `test_reverse_logic.py`, shared by the line tests. */

internal val KINDS = listOf("A", "B", "C", "D", "E")

internal fun Random.randint(a: Int, b: Int) = nextInt(a, b + 1)

internal fun <T> Random.choice(xs: List<T>) = xs[nextInt(xs.size)]

internal fun randRegion(rng: Random, lo: Int = 0, hi: Int = 8, den: Int = 2): Region {
    val parts = mutableListOf<Region>()
    repeat(rng.randint(1, 2)) {
        val a = Rational.of(rng.randint(lo * den, hi * den), den)
        if (rng.nextDouble() < 0.3) {
            parts.add(Region.point(a))
        } else {
            val b = a + Rational.of(rng.randint(1, 3 * den), den)
            parts.add(Region.interval(a, b, rng.nextDouble() < 0.7, rng.nextDouble() < 0.7))
        }
    }
    return Region.unionOf(parts)
}

internal fun randRules(rng: Random): MutableList<LineRule> {
    val rules = mutableListOf<LineRule>()
    repeat(rng.randint(1, 4)) {
        val (i, j) = (0 until KINDS.size).shuffled(rng).take(2).sorted() // production goes forward: acyclic
        val off = rng.choice(listOf(-1, 0, 0, 1))
        val t = rng.nextDouble()
        when {
            t < 0.35 -> rules.add(implies(KINDS[i], atom(KINDS[j], off)))
            t < 0.6 -> rules.add(forbids(KINDS[rng.nextInt(5)], atom(KINDS[rng.nextInt(5)], off)))
            t < 0.8 && j + 1 < KINDS.size -> {
                val k = rng.nextInt(j + 1, KINDS.size)
                rules.add(impliesAny(listOf(atom(KINDS[i])), listOf(atom(KINDS[j], off), atom(KINDS[k]))))
            }
            i + 1 < j -> rules.add(implies(listOf(atom(KINDS[i]), atom(KINDS[i + 1], off)), atom(KINDS[j])))
        }
    }
    return rules.filterNot {
        (it is LineRule.Implies && it.premises == listOf(it.head)) || (it is LineRule.Forbids && it.premises == listOf(it.head))
    }.toMutableList()
}

/**
 * Mostly edits that fit the line: adds where the kind is absent, removes and moves of what is there, extensions and
 * retractions at an end. Regions in [lo, hi] (the reference's [0, 8]).
 */
internal fun randEdit(rng: Random, state: Claims, lo: Int = 0, hi: Int = 8): List<LineOp> {
    val ops = mutableListOf<LineOp>()
    repeat(rng.randint(1, 3)) {
        var k = rng.choice(KINDS)
        var reg = randRegion(rng, lo, hi)
        val t = rng.nextDouble()
        if (t >= 0.4 && state.isNotEmpty()) {
            k = rng.choice(state.keys.sorted())
            val have = state.getValue(k)
            val (pLo, _, pHi, hc) = rng.choice(have.parts)
            val d = Rational.of(rng.randint(1, 4), 2)
            if (pHi.isFinite && pLo.isFinite && t >= 0.85) {
                ops.add(
                    if (rng.nextDouble() < 0.5) Extend(k, Region.interval(pHi, pHi + d, !hc, true) - have)
                    else Retract(k, if (pLo < pHi) Region.interval(max(pLo, pHi - d), pHi, false, hc) else have),
                )
                return@repeat
            }
            reg = if (rng.nextDouble() < 0.7) randRegion(rng, lo, hi) and have else have
        }
        if (t < 0.4 || reg.isEmpty()) {
            val add = (reg.takeIf { it.isNotEmpty() } ?: randRegion(rng, lo, hi)) - state.at(k)
            ops.add(Add(k, add.takeIf { it.isNotEmpty() } ?: randRegion(rng, lo, hi)))
        } else {
            ops.add(if (t < 0.6) Remove(k, reg) else Move(k, reg, Rational.of(rng.randint(-4, 4), 2)))
        }
    }
    return ops.filter { it.region.isNotEmpty() }
}

/** Runs [fn]; null if the edit was refused ([ContradictionError] or [MissingElementError]). */
internal fun <T> refusedAsNull(fn: () -> T): T? = try {
    fn()
} catch (e: ContradictionError) {
    null
} catch (e: MissingElementError) {
    null
}

internal fun tryApply(line: LineSystem, ops: List<LineOp>): Int? = refusedAsNull { line.apply(ops) }
