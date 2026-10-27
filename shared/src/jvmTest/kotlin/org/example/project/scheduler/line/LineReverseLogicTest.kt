package org.example.project.scheduler.line

import org.example.project.scheduler.line.LineOp.Add
import org.example.project.scheduler.line.LineOp.Extend
import org.example.project.scheduler.line.LineOp.Move
import org.example.project.scheduler.line.LineOp.Remove
import org.example.project.scheduler.line.LineOp.Retract
import org.example.project.scheduler.line.Rational.Companion.INF
import org.example.project.scheduler.line.Rational.Companion.NEG_INF
import org.example.project.scheduler.line.Region.Companion.EMPTY
import org.example.project.scheduler.line.Region.Companion.FULL
import org.example.project.scheduler.line.Region.Part
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The port of `C:\code\line_manager\reverse_logic\test_reverse_logic.py`, test for test: every theorem of SEMANTICS.md
 * on random histories, acceptance against a brute-force search, the requirements' examples step by step.
 * `REVLOGIC_SEEDS=n` for more random runs, as in the reference. `LineGoldenTest` checks the outcomes are the
 * reference's own, step for step.
 */
class LineReverseLogicTest {
    private val seedsSet = System.getenv("REVLOGIC_SEEDS")?.toIntOrNull()
    private val seeds = seedsSet ?: 60

    /**
     * Cases of the two infinite-versus-cut agreement tests. The reference runs `seeds / 2` (30): a few of them take
     * 10–20 s each on the infinite lattice search, ~90 s in all, so the gate runs the first 5 (the same random stream,
     * a prefix of the full run) and `REVLOGIC_SEEDS` restores the reference's count. `LineGoldenTest` holds 9
     * unbounded cases to the reference's own answers.
     */
    private val infiniteCases = seedsSet?.let { it / 2 } ?: 5

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun samples(vararg regions: Region): Set<Rational> {
        val ends = (regions.flatMap { r -> r.parts.flatMap { listOf(it.lo, it.hi) } } + Rational.ZERO).distinct().sorted()
        val pts = ends.toMutableSet()
        for ((a, b) in ends.zipWithNext()) pts.add((a + b) / 2)
        pts.add(ends.first() - Rational.ONE)
        pts.add(ends.last() + Rational.ONE)
        return pts
    }

    private val ray = Region(listOf(Part(Rational.ZERO, true, INF, false)))

    private fun rayFrom(x: Int) = Region(listOf(Part(x.q, true, INF, false)))

    private fun state(vararg pairs: Pair<String, Region>): Claims = mapOf(*pairs)

    // ================================================================================================================
    // Regions
    // ================================================================================================================

    @Test
    fun algebraMatchesPointwiseSemantics() {
        val rng = Random(1)
        repeat(300) {
            val a = randRegion(rng)
            val b = randRegion(rng)
            val d = Rational.of(rng.randint(-6, 6), 2)
            for (x in samples(a, b, a.shift(d))) {
                assertEquals(x in (a or b), x in a || x in b)
                assertEquals(x in (a and b), x in a && x in b)
                assertEquals(x in (a - b), x in a && x !in b)
                assertEquals(x in a.shift(d), (x - d) in a)
            }
            assertEquals(a, a.shift(d).shift(-d))
        }
    }

    /** Regions that repeat forever: every operation is right at every point, and equal sets are equal regions. */
    @Test
    fun periodicAlgebraMatchesPointwiseSemantics() {
        val rng = Random(2)
        fun randPeriodic(): Region {
            var out = randRegion(rng, -4, 4)
            repeat(rng.randint(1, 2)) {
                val step = Rational.of(rng.randint(1, 4), rng.randint(1, 2))
                val cell = randRegion(rng, 0, 2) and Region.interval(Rational.ZERO, step * rng.randint(1, 2), true, false)
                val k = rng.randint(-3, 3).toLong()
                out = out or when (rng.nextInt(3)) {
                    0 -> Region.tiled(cell, step, k, null)
                    1 -> Region.tiled(cell, step, null, k)
                    else -> Region.tiled(cell, step, null, null)
                }
            }
            return out
        }

        fun points(vararg regions: Region): List<Rational> {
            val pts = (-30..30).map { it.q }.toMutableSet()
            for (r in regions) for (e in r.ends((-30).q, 30.q)) pts.addAll(listOf(e, e + Rational.of(1, 97), e - Rational.of(1, 97)))
            return pts.sorted() + (-200 until 200).map { Rational.of(it, 7) }
        }

        repeat(60) {
            val a = randPeriodic()
            val b = randPeriodic()
            val d = Rational.of(rng.randint(-6, 6), 2)
            val union = a or b
            val inter = a and b
            val diff = a - b
            val moved = a.shift(d)
            for (x in points(a, b, moved)) {
                assertEquals(x in union, x in a || x in b)
                assertEquals(x in inter, x in a && x in b)
                assertEquals(x in diff, x in a && x !in b)
                assertEquals(x in moved, (x - d) in a)
            }
            assertEquals(a, moved.shift(-d))
            assertEquals(a, (a - b) or (a and b)) // the same set, built differently
            assertEquals(a - b, (a or b) - b)
            assertEquals(a, a and a)
        }
        val c = Region.interval(0, 1, true, false)
        assertEquals(Region.tiled(c, 4.q, 0, null) or Region.tiled(c, 4.q, 0, null).shift(2.q), Region.tiled(c, 2.q, 0, null))
        assertEquals(ray, Region.tiled(c, 1.q, 0, null)) // constant: a ray
    }

    @Test
    fun openEndpointsAfterRemoval() {
        val r = Region.closed(0, 2) - Region.closed(0, 1)
        assertEquals(Region.interval(1, 2, leftClosed = false), r)
        assertFalse(1.q in r)
    }

    @Test
    fun canonicalEquality() {
        assertEquals(Region.closed(0, 2), Region.closed(0, 1) or Region.closed(1, 2))
        assertEquals(Region.open(0, 2), Region.open(0, 1) or Region.point(1) or Region.open(1, 2))
        assertEquals(Rational.of(1, 10), Region.point(Rational.of(1, 10)).start)
    }

    @Test
    fun rationalArithmetic() {
        assertEquals(Rational.of(5, 6), Rational.of(1, 2) + Rational.of(1, 3))
        assertEquals(Rational.of(1, 2), Rational.of(-7, 2) % 2.q) // the sign of the divisor, as in Python
        assertEquals(-4L, Rational.of(-7, 2).floor())
        assertEquals(-3L, Rational.of(-7, 2).ceil())
        assertTrue(Rational.of(1, 3) < Rational.of(1, 2) && NEG_INF < Rational.of(-1_000_000_000_000L, 1) && INF > 0.q)
        assertTrue(Rational.of(1_700_000_000_000L, 3) < Rational.of(1_700_000_000_001L, 3))
        assertEquals(Rational.of(1, 2), Rational.gcdOf(listOf(Rational.of(3, 2), 2.q)))
        assertEquals(6.q, Rational.lcm(2.q, 3.q))
        assertEquals(INF, INF + 5.q)
        assertEquals(Rational.of(-3, 4), Rational.parse("-3/4"))
    }

    // ================================================================================================================
    // Rules
    // ================================================================================================================

    @Test
    fun cycleRejected() {
        assertFailsWith<RuleCycleError> { LineSystem(listOf(implies("A", atom("B", 1)), implies("B", atom("A", 1)))) }
        LineSystem(listOf(forbids("A", "B"), forbids("B", "A"))) // symmetric clause, no production cycle
    }

    @Test
    fun requirementsExample1() {
        val line = LineSystem(listOf(forbids("D", "C")))
        val r67 = Region.closed(6, 7)
        line.apply(listOf(Add("A", Region.closed(1, 2)), Add("B", Region.closed(1, 4)), Add("D", r67)))
        val initial = line.state()
        val a = line.apply(listOf(Move.to("A", Region.interval(1, 2, true, false), 3.q), Move.to("B", Region.point(4), 5.q), Add("C", r67)))
        val afterA = line.state()
        assertEquals(r67, afterA["C"])
        assertFalse("D" in afterA)
        assertEquals(Region.interval(1, 4, true, false), afterA.getValue("B") and Region.closed(1, 4))
        assertEquals(Region.point(2), afterA.getValue("A") and Region.closed(1, 2))
        val b = line.apply(listOf(Add("D", r67)))
        assertEquals(r67, line.state()["D"])
        assertFalse("C" in line.state())
        line.reverse(b)
        assertEquals(afterA, line.state())
        line.reverse(a)
        assertEquals(initial, line.state())
        assertFailsWith<ContradictionError> { line.apply(listOf(Add("C", Region.point(8)), Add("D", Region.point(8)))) }
        assertEquals(initial, line.state())
    }

    private fun dragStates(rules: List<LineRule>, start: Pair<String, Region>, block: Pair<String, Region>, steps: List<Rational>): Pair<LineSystem, List<Claims>> {
        val line = LineSystem(rules)
        line.apply(listOf(Add(start.first, start.second)))
        line.apply(listOf(Add(block.first, block.second)))
        val drag = line.drag(listOf(block))
        val states = steps.map {
            drag.step(it)
            line.state()
        }
        drag.drop()
        return line to states
    }

    @Test
    fun requirementsExample2() {
        val steps = (0 until 25).map { Rational.of(it, 8) }
        val (line, states) = dragStates(listOf(forbids("A", "B")), "A" to Region.closed(0, 2), "B" to Region.closed(0, 1), steps)
        for ((d, st) in steps.zip(states)) { // A is cut by B, and comes back behind it
            val b = Region.closed(d, 1.q + d)
            assertEquals(state("A" to (Region.closed(0, 2) - b), "B" to b), st)
        }
        assertEquals(state("A" to Region.closed(0, 2), "B" to Region.closed(3, 4)), line.state())
    }

    @Test
    fun requirementsExample3() {
        val steps = (0 until 25).map { Rational.of(it, 8) }
        val (line, states) = dragStates(listOf(forbids("D", "C")), "D" to Region.point(2), "C" to Region.closed(0, 1), steps)
        for ((d, st) in steps.zip(states)) assertEquals(!(d <= 2.q && 2.q <= 1.q + d), "D" in st) // gone exactly while C covers it
        val direct = LineSystem(listOf(forbids("D", "C")))
        direct.apply(listOf(Add("D", Region.point(2))))
        direct.apply(listOf(Add("C", Region.closed(3, 4))))
        assertEquals(direct.state(), line.state())
    }

    @Test
    fun dragBackToStartRestoresTheLine() {
        val line = LineSystem(listOf(forbids("D", "C")))
        line.apply(listOf(Add("D", Region.point(2))))
        line.apply(listOf(Add("C", Region.closed(0, 1))))
        val before = line.state()
        val drag = line.drag(listOf("C" to Region.closed(0, 1)))
        for (d in listOf(1.q, 2.q, Rational.of(3, 2), 0.q)) drag.step(d)
        assertNull(drag.drop())
        assertEquals(before, line.state())
    }

    @Test
    fun dragOntoSameKindIsRefused() {
        val line = LineSystem()
        line.apply(listOf(Add("C", Region.closed(0, 1)), Add("C", Region.closed(5, 6))))
        val drag = line.drag(listOf("C" to Region.closed(0, 1)))
        drag.step(2.q)
        assertFailsWith<MissingElementError> { drag.step(4.q) } // [4, 5] meets C on [5, 6]
        assertEquals(Region.closed(2, 3) or Region.closed(5, 6), line.state()["C"])
    }

    @Test
    fun extendAndRetract() {
        val line = LineSystem()
        line.apply(listOf(Add("A", Region.closed(0, 1))))
        val before = line.state()
        val grow = listOf(Extend("A", Region.interval(1, 2, false, true)))
        line.apply(grow)
        assertEquals(state("A" to Region.closed(0, 2)), line.state())
        line.apply(inverse(grow))
        assertEquals(before, line.state())
        assertFailsWith<MissingElementError> { line.apply(listOf(Extend("A", Region.closed(3, 4)))) } // touches nothing
        assertFailsWith<MissingElementError> { line.apply(listOf(Retract("A", Region.closed(0, 1)))) } // nothing would remain
        assertFailsWith<MissingElementError> { line.apply(listOf(Retract("A", Region.closed(1, 2)))) } // partly absent
    }

    @Test
    fun puttingWhereTheKindIsAlreadyPresentIsRefused() {
        val line = LineSystem(listOf(implies("A", "B")))
        line.apply(listOf(Add("A", Region.closed(0, 1)), Add("C", Region.closed(5, 6))))
        for (ops in listOf(
            listOf(Add("A", Region.closed(1, 2))),
            listOf(Add("B", Region.point(0))), // B is there by the rule
            listOf(Move("A", Region.closed(0, 1), Rational.of(1, 2)), Add("A", Region.closed(1, 2))),
        )) {
            val err = runCatching { line.apply(ops) }.exceptionOrNull()
            assertTrue(err is MissingElementError || err is ContradictionError, "$ops: $err")
        }
        line.apply(listOf(Move("C", Region.closed(5, 6), Rational.of(1, 2)))) // onto its own place: allowed
    }

    @Test
    fun removingWhatARuleNeedsMakesThePremiseAdapt() {
        val line = LineSystem(listOf(implies("A", "B")))
        line.apply(listOf(Add("A", Region.closed(0, 2))))
        line.apply(listOf(Remove("B", Region.point(1))))
        assertEquals(state("A" to (Region.closed(0, 2) - Region.point(1)), "B" to (Region.closed(0, 2) - Region.point(1))), line.state())
    }

    @Test
    fun reverseAllTakesTheDependentEdits() {
        val line = LineSystem()
        val a = line.apply(listOf(Add("E", Region.point(0))))
        val b = line.apply(listOf(Move("E", Region.point(0), 5.q)))
        val c = line.apply(listOf(Add("G", Region.point(9))))
        assertEquals(listOf(a, b), line.reverseAll(listOf(a)))
        assertEquals(state("G" to Region.point(9)), line.state())
        assertTrue(line.allEdits[c].active)
    }

    @Test
    fun implicationAddsAndContrapositiveAdapts() {
        val line = LineSystem(listOf(implies("A", "B")))
        line.apply(listOf(Add("A", Region.closed(0, 2))))
        assertEquals(Region.closed(0, 2), line.state()["B"])
        line.apply(listOf(Remove("B", Region.closed(1, 2))))
        assertEquals(state("A" to Region.interval(0, 1, true, false), "B" to Region.interval(0, 1, true, false)), line.state())
    }

    @Test
    fun conjunctionWithOffset() {
        val line = LineSystem(listOf(implies(listOf(atom("A"), atom("B")), atom("C", -3))))
        line.apply(listOf(Add("A", Region.closed(0, 4)), Add("B", Region.closed(2, 6))))
        assertEquals(Region.closed(-1, 1), line.state()["C"])
    }

    @Test
    fun disjunctionUsesFirstOperandThenFallsBack() {
        val line = LineSystem(listOf(impliesAny("A", listOf("B", "C"))))
        line.apply(listOf(Add("A", Region.closed(0, 1))))
        assertEquals(state("A" to Region.closed(0, 1), "B" to Region.closed(0, 1)), line.state())
        val rm = listOf(Remove("B", Region.closed(0, 1)))
        line.apply(rm) // B is forbidden by a newer edit: A's older obligation falls back to C
        assertEquals(state("A" to Region.closed(0, 1), "C" to Region.closed(0, 1)), line.state())
        line.apply(inverse(rm))
        assertEquals(state("A" to Region.closed(0, 1), "B" to Region.closed(0, 1)), line.state())
    }

    @Test
    fun disjunctionInOneEditPicksAllowedOperand() {
        val line = LineSystem(listOf(impliesAny("A", listOf("B", "C")), forbids("D", "B"), forbids("E", "C")))
        line.apply(listOf(Add("A", Region.point(0)), Add("D", Region.point(0))))
        assertEquals(state("A" to Region.point(0), "C" to Region.point(0), "D" to Region.point(0)), line.state())
        assertFailsWith<ContradictionError> { line.apply(listOf(Add("A", Region.point(5)), Add("D", Region.point(5)), Add("E", Region.point(5)))) }
    }

    @Test
    fun addAndRemoveSamePlaceInOneEditRejected() {
        val line = LineSystem()
        line.apply(listOf(Add("A", Region.closed(0, 3))))
        assertFailsWith<ContradictionError> { line.apply(listOf(Add("A", Region.closed(1, 2)), Remove("A", Region.closed(1, 3)))) }
        assertFailsWith<ContradictionError> { line.apply(listOf(Remove("A", Region.closed(0, 1)), Move("A", Region.closed(2, 3), (-2).q))) }
    }

    @Test
    fun removingOrMovingWhatIsNotThereIsRefused() {
        val line = LineSystem(listOf(implies("A", "B")))
        line.apply(listOf(Add("A", Region.closed(0, 1))))
        for (ops in listOf(
            listOf(Remove("C", Region.point(0))),
            listOf(Remove("A", Region.closed(0, 2))), // partly absent
            listOf(Move("A", Region.closed(1, 2), 5.q)),
        )) assertFailsWith<MissingElementError> { line.apply(ops) }
        assertEquals(state("A" to Region.closed(0, 1), "B" to Region.closed(0, 1)), line.state())
        line.apply(listOf(Remove("B", Region.point(0)))) // derived elements are on the line too
    }

    @Test
    fun disjunctionRefusedOnlyIfNoOperandsWork() {
        val rules = listOf(impliesAny("A", listOf("B", "C")), forbids("B", atom("B", 1)))
        var line = LineSystem(rules)
        line.apply(listOf(Add("A", Region.points(0, 1))))
        assertEquals(state("A" to Region.points(0, 1), "B" to Region.point(0), "C" to Region.point(1)), line.state())
        line = LineSystem(rules)
        line.apply(listOf(Add("A", Region.closed(0, 3))))
        assertEquals(Region.interval(0, 1, true, false) or Region.interval(2, 3, true, false), line.state()["B"])
    }

    @Test
    fun olderPositionsThatCannotBePlacedAdaptAlone() {
        val rules = listOf(impliesAny("A", listOf("B", "C")), forbids("B", atom("B", 1)), forbids("X", "B"), forbids("X", "C"))
        var line = LineSystem(rules)
        line.apply(listOf(Add("A", Region.points(0, 1, 10))))
        val before = state("A" to Region.points(0, 1, 10), "B" to Region.points(0, 10), "C" to Region.point(1))
        assertEquals(before, line.state())
        val x = line.apply(listOf(Add("X", Region.point(10))))
        assertEquals(state("A" to Region.points(0, 1), "B" to Region.point(0), "C" to Region.point(1), "X" to Region.point(10)), line.state())
        line.reverse(x)
        assertEquals(before, line.state())
        // Positions that cannot all stay are kept closest to 0 first.
        line = LineSystem(listOf(impliesAny("A", listOf("B", "C")), forbids("B", atom("B", 1)), forbids("X", "C")))
        line.apply(listOf(Add("A", Region.points(1, 2))))
        line.apply(listOf(Add("X", Region.points(1, 2))))
        assertEquals(state("A" to Region.point(1), "B" to Region.point(1), "X" to Region.points(1, 2)), line.state())
        // A position that is not hit still takes the first operand that can be placed.
        line = LineSystem(listOf(impliesAny("A", listOf("B", "C")), forbids("B", atom("B", 1)), forbids("X", "B")))
        line.apply(listOf(Add("A", Region.points(1, 2))))
        assertEquals(state("A" to Region.points(1, 2), "B" to Region.point(1), "C" to Region.point(2)), line.state())
        line.apply(listOf(Add("X", Region.point(1))))
        assertEquals(state("A" to Region.points(1, 2), "B" to Region.point(2), "C" to Region.point(1), "X" to Region.point(1)), line.state())
    }

    private fun steps(rules: List<LineRule>, steps: List<Pair<List<LineOp>, List<String>>>) {
        val line = LineSystem(rules)
        for ((ops, expected) in steps) {
            line.apply(ops)
            assertEquals(expected, line.state().keys.sorted(), "$ops")
        }
    }

    @Test
    fun requirementsConjunctionExamples() {
        val x = Region.point(0)
        steps(
            listOf(implies(listOf(atom("A"), atom("B"), atom("C")), atom("D")), implies(listOf(atom("A"), atom("B")), atom("C"))),
            listOf(
                listOf(Add("A", x)) to listOf("A"),
                listOf(Add("B", x)) to listOf("A", "B", "C", "D"),
                listOf(Remove("A", x)) to listOf("B"),
                listOf(Remove("B", x)) to listOf(),
            ),
        )
        steps(
            listOf(implies(listOf(atom("A"), atom("B"), atom("C")), atom("D")), forbids("X", "D"), implies(listOf(atom("A"), atom("B")), atom("C"))),
            listOf(
                listOf(Add("A", x), Add("B", x)) to listOf("A", "B", "C", "D"),
                listOf(Add("X", x)) to listOf("A", "X"),
                listOf(Remove("A", x)) to listOf("B", "X"),
                listOf(Remove("X", x)) to listOf("B"),
                listOf(Add("A", x)) to listOf("A", "B", "C", "D"),
            ),
        )
        steps(
            listOf(implies(listOf(atom("A"), atom("B")), atom("C")), forbids("X", "C")),
            listOf(listOf(Add("A", x), Add("B", x)) to listOf("A", "B", "C"), listOf(Add("X", x)) to listOf("A", "X")),
        )
    }

    @Test
    fun theFewestElementsAreLostThenTheLeftmostKept() {
        val x = Region.point(0)
        // Keeping A would lose B, E and F; keeping B loses only A.
        steps(
            listOf(implies(listOf(atom("A"), atom("B")), atom("C")), forbids("X", "C"), implies("B", "E"), implies("B", "F")),
            listOf(listOf(Add("A", x), Add("B", x)) to listOf("A", "B", "C", "E", "F"), listOf(Add("X", x)) to listOf("B", "E", "F", "X")),
        )
        // A(x) and B(x + 1) give C(x): only the B paired with the X go.
        var line = LineSystem(listOf(implies(listOf(atom("A"), atom("B", 1)), atom("C")), forbids("X", "C")))
        line.apply(listOf(Add("A", Region.closed(0, 9)), Add("B", Region.closed(0, 9))))
        line.apply(listOf(Add("X", Region.closed(2, 4))))
        assertEquals(Region.closed(0, 9), line.state()["A"])
        assertEquals(Region.interval(0, 3, true, false) or Region.interval(5, 9, false, true), line.state()["B"])
        // No two neighbours: two of the four stay, the earliest ones.
        line = LineSystem(listOf(implies(listOf(atom("A"), atom("A", 1)), atom("C")), forbids("X", "C")))
        line.apply(listOf(Add("A", Region.points(0, 1, 2, 3))))
        line.apply(listOf(Add("X", Region.points(0, 1, 2))))
        assertEquals(Region.points(0, 2), line.state()["A"])
    }

    @Test
    fun requires() {
        val line = LineSystem(listOf(forbids("F", "E")))
        val a = line.apply(listOf(Add("E", Region.point(0))))
        val b = line.apply(listOf(Remove("E", Region.point(0)))) // removes what A added
        assertTrue(line.requires(b, a))
        assertFalse(line.requires(line.apply(listOf(Add("F", Region.point(1)))), a))
        val c = line.apply(listOf(Add("G", Region.point(9))))
        assertFalse(line.requires(c, a))
    }

    @Test
    fun reversingARequiredEditIsRefused() {
        val line = LineSystem()
        val a = line.apply(listOf(Add("E", Region.point(0))))
        val b = line.apply(listOf(Move("E", Region.point(0), 5.q)))
        assertTrue(line.requires(b, a))
        assertFailsWith<DependencyError> { line.reverse(a) }
        assertEquals(state("E" to Region.point(5)), line.state())
        line.reverse(b)
        line.reverse(a)
        assertEquals(emptyMap(), line.state())
    }

    // ================================================================================================================
    // Randomized properties
    // ================================================================================================================

    private val kinds = KINDS

    /**
     * Is there any choice of operands under which the closure of [adds] breaks no rule? Plain enumeration over
     * integer points (integer offsets).
     */
    private fun bruteForceConsistent(ruleset: RuleSet, adds: Map<String, Set<Int>>): Boolean {
        val order = ruleset.order(adds.keys)
        val idx = order.withIndex().associate { (i, k) -> k to i }
        val xs = -20 until 30
        fun holds(state: Map<String, Set<Int>>, atoms: List<Atom>, x: Int) = atoms.all { (x + it.offset.toLongExact().toInt()) in (state[it.kind] ?: emptySet()) }

        fun run(state: Map<String, Set<Int>>, rules: List<LineRule>): Boolean {
            if (rules.isEmpty()) return true
            val r = rules[0]
            val rest = rules.drop(1)
            if (r is LineRule.Forbids) return xs.none { holds(state, r.premises + r.head, it) } && run(state, rest)
            val anchors = xs.filter { holds(state, r.premises, it) }
            val options = if (r is LineRule.Implies) listOf(r.head) else (r as LineRule.ImpliesAny).options
            fun pick(i: Int, st: Map<String, Set<Int>>): Boolean {
                if (i == anchors.size) return run(st, rest)
                for (o in options) {
                    val next = st.toMutableMap()
                    next[o.kind] = (st[o.kind] ?: emptySet()) + (anchors[i] + o.offset.toLongExact().toInt())
                    if (pick(i + 1, next)) return true
                }
                return false
            }
            return pick(0, state)
        }

        // Same firing order as the solver: a rule fires once every kind it reads is final.
        fun fireAt(r: LineRule) = (r.premises + (if (r is LineRule.Forbids) listOf(r.head) else emptyList())).maxOf { idx.getValue(it.kind) }
        return run(adds, ruleset.rules.sortedBy { fireAt(it) })
    }

    /** The least model of the atoms (x, kind) and X at [xsX] under definite rules, or null if it breaks a Forbids rule. */
    private fun bruteClosure(ruleset: RuleSet, atoms: List<Pair<Int, String>>, xsX: Set<Int>): Map<String, Set<Int>>? {
        val st = HashMap<String, MutableSet<Int>>()
        st["X"] = xsX.toMutableSet()
        for ((x, k) in atoms) st.getOrPut(k) { mutableSetOf() }.add(x)
        fun inst(atoms: List<Atom>): Set<Int> = atoms.map { a -> (st[a.kind] ?: emptySet<Int>()).map { it - a.offset.toLongExact().toInt() }.toSet() }.reduce { acc, s -> acc intersect s }
        var changed = true
        while (changed) {
            changed = false
            for (r in ruleset.rules) {
                if (r is LineRule.Implies) {
                    val new = inst(r.premises).map { it + r.head.offset.toLongExact().toInt() }.toSet() - (st[r.head.kind] ?: emptySet())
                    if (new.isNotEmpty()) {
                        st.getOrPut(r.head.kind) { mutableSetOf() }.addAll(new)
                        changed = true
                    }
                }
            }
        }
        for (r in ruleset.rules) if (r is LineRule.Forbids && inst(r.premises + r.head).isNotEmpty()) return null
        return st
    }

    private fun checkInvariants(line: LineSystem, newest: Int?) {
        val st = line.state()
        assertEquals(emptyList(), line.ruleset.violations(st), "rules violated")
        if (newest != null) { // the newest edit's effective claims all hold
            val (pos, neg) = line.effective(newest)
            for ((k, r) in pos) assertEquals(EMPTY, r - st.at(k))
            for ((k, r) in neg) assertEquals(EMPTY, r and st.at(k))
        }
    }

    @Test
    fun randomHistories() {
        for (seed in 0 until seeds) {
            val rng = Random(seed)
            val rules = randRules(rng)
            val line = LineSystem(rules)
            val ids = mutableListOf<Int>()
            repeat(rng.randint(3, 6)) {
                val ops = randEdit(rng, line.state())
                val before = line.state()
                val eid = tryApply(line, ops)
                if (eid == null) {
                    assertEquals(before, line.state(), "seed $seed: a rejected edit changes nothing")
                    return@repeat
                }
                ids.add(eid)
                checkInvariants(line, eid)

                // A drag step is the line of a direct move, whatever the previous steps.
                if (line.state().isNotEmpty() && rng.nextDouble() < 0.3) {
                    val k = rng.choice(line.state().keys.sorted())
                    val block = listOf(k to line.state().getValue(k))
                    val undragged = line.state()
                    val drag = line.drag(block)
                    repeat(3) {
                        val d = Rational.of(rng.randint(-6, 6), 2)
                        val direct = try {
                            line.validated(if (d != Rational.ZERO) listOf(Move(k, block[0].second, d)) else emptyList())
                        } catch (e: ContradictionError) {
                            null
                        } catch (e: MissingElementError) {
                            null
                        }
                        val shown = line.state()
                        try {
                            drag.step(d)
                        } catch (e: IllegalArgumentException) {
                            if (e !is ContradictionError && e !is MissingElementError) throw e
                            assertNull(direct, "seed $seed")
                            assertEquals(shown, line.state())
                            return@repeat
                        }
                        assertEquals(direct, line.state(), "seed $seed: drag step $d")
                    }
                    drag.cancel()
                    assertEquals(undragged, line.state())
                }

                // An edit followed by its inverse restores the line.
                if (rng.nextDouble() < 0.5) {
                    val inv = line.apply(inverse(ops)) // must never be rejected
                    assertEquals(before, line.state(), "seed $seed: edit then inverse")
                    line.reverse(inv) // keep the history going from the edit
                    checkInvariants(line, eid)
                }
            }

            // Reversing an edit gives the line as if it had never been made. It is refused, and changes nothing, iff
            // a later edit requires it.
            val active = ids.toMutableList()
            while (active.isNotEmpty()) {
                var victim = rng.choice(active)
                val before = line.state()
                try {
                    line.reverse(victim)
                } catch (e: DependencyError) {
                    assertEquals(before, line.state())
                    val v = victim
                    assertTrue(active.filter { it > v }.any { line.requires(it, v) }, "seed $seed")
                    victim = active.last() // the newest edit can always be reversed
                    line.reverse(victim)
                }
                active.remove(victim)
                checkInvariants(line, null)
                val replay = LineSystem(rules)
                val replayed = active.map { tryApply(replay, line.allEdits[it].ops) }
                assertTrue(replayed.all { it != null }, "seed $seed")
                assertEquals(replay.state(), line.state(), "seed $seed")
            }
            assertEquals(emptyMap(), line.state())
        }
    }

    @Test
    fun determinism() {
        for (seed in 0 until seeds / 3) {
            val rng = Random(1000 + seed)
            val rules = randRules(rng)
            val edits = List(4) { randEdit(rng, emptyMap()) }
            val states = List(2) {
                val line = LineSystem(rules)
                for (ops in edits) tryApply(line, ops)
                line.state()
            }
            assertEquals(states[0], states[1])
        }
    }

    /** An edit is refused only if no choice of operands satisfies the rules. */
    @Test
    fun refusedIffNoOperandsWork() {
        for (seed in 0 until seeds * 3) {
            val rng = Random(5000 + seed)
            val rules = randRules(rng)
            rules.add(impliesAny(listOf(atom(kinds[0])), listOf(atom(kinds[1]), atom(kinds[2], rng.choice(listOf(-1, 1))))))
            rules.add(forbids(kinds[rng.nextInt(1, 5)], atom(kinds[rng.nextInt(1, 5)], rng.choice(listOf(-1, 0, 1)))))
            val adds = kinds.shuffled(rng).take(2).associateWith { (0 until 6).shuffled(rng).take(rng.randint(1, 3)).toSet() }
            val line = LineSystem(rules)
            val accepted = tryApply(line, adds.map { (k, xs) -> Add(k, Region.points(*xs.toIntArray())) }) != null
            assertEquals(bruteForceConsistent(line.ruleset, adds), accepted, "seed $seed: $rules $adds")
        }
    }

    /**
     * A newer edit leaves some positions of an older one no operand: the older claims are kept one after the other,
     * closest to 0 first, each if some choice of operands satisfies the rules with the ones before.
     */
    @Test
    fun olderClaimsAreKeptClosestTo0First() {
        var tried = 0
        for (seed in 0 until seeds * 3) {
            val rng = Random(8000 + seed)
            val rules = randRules(rng)
            val o = rng.choice(listOf(-1, 1))
            rules.add(impliesAny(listOf(atom(kinds[0])), listOf(atom(kinds[1]), atom(kinds[2], o))))
            // X often leaves an A no operand.
            rules.add(forbids("X", kinds[1]))
            rules.add(forbids("X", atom(kinds[2], rng.choice(listOf(o, o, 0)))))
            rules.add(forbids("X", atom(rng.choice(kinds.drop(1)), rng.choice(listOf(-1, 0, 1)))))
            val old = linkedMapOf(
                kinds[0] to (-3..3).shuffled(rng).take(rng.randint(2, 5)).toSet(),
            )
            val other = rng.choice(kinds.drop(1))
            old[other] = (-3..3).shuffled(rng).take(rng.randint(1, 3)).toSet()
            val new = mapOf("X" to (-3..3).shuffled(rng).take(rng.randint(1, 3)).toSet())
            val line = LineSystem(rules)
            if (tryApply(line, old.map { (k, xs) -> Add(k, Region.points(*xs.toIntArray())) }) == null) continue
            val definite = RuleSet(rules.filter { it !is LineRule.ImpliesAny })
            if (!bruteForceConsistent(definite, old + new)) continue // a conflict no operand avoids: the older claims adapt by their support
            tried++
            line.apply(listOf(Add("X", Region.points(*new.getValue("X").toIntArray()))))
            val order = line.ruleset.order(kinds + "X").withIndex().associate { (i, k) -> k to i }
            var kept = mapOf<String, Set<Int>>()
            val atoms = old.flatMap { (k, xs) -> xs.map { it to k } }
                .sortedWith(compareBy<Pair<Int, String>>({ kotlin.math.abs(it.first) }, { it.first }, { order.getValue(it.second) }))
            for ((x, k) in atoms) {
                val trial = kept.toMutableMap()
                trial[k] = (trial[k] ?: emptySet()) + x
                if (bruteForceConsistent(line.ruleset, trial + new)) kept = trial
            }
            val dropped = line.solveActive().dropped[0].first
            for ((k, xs) in old) {
                val expected = kept[k]?.takeIf { it.isNotEmpty() }?.let { Region.points(*it.toIntArray()) } ?: EMPTY
                assertEquals(expected, Region.points(*xs.toIntArray()) - dropped.at(k), "seed $seed: $rules $old $new")
            }
        }
        assertTrue(tried > seeds, "only $tried cases tried")
    }

    /**
     * Without ordered disjunctions every conflict is one no operand avoids: the older claims kept are those losing
     * the fewest elements of the line, the earliest ones (closest to 0, then leftmost in the rules) on a tie.
     */
    @Test
    fun olderClaimsLoseTheFewestElements() {
        var tried = 0
        for (seed in 0 until seeds * 3) {
            val rng = Random(9000 + seed)
            val rules = randRules(rng).filter { it !is LineRule.ImpliesAny }.toMutableList()
            rules.add(implies(listOf(atom(kinds[0]), atom(kinds[1], rng.choice(listOf(0, 0, 1)))), atom(kinds[2])))
            for (k in kinds.drop(1).shuffled(rng).take(2)) rules.add(forbids("X", atom(k, rng.choice(listOf(-1, 0, 0, 1)))))
            val old = kinds.shuffled(rng).take(3).associateWith { (-3..3).shuffled(rng).take(rng.randint(1, 4)).toSet() }
            val new = (-3..3).shuffled(rng).take(rng.randint(1, 3)).toSet()
            val line = LineSystem(rules)
            if (tryApply(line, old.map { (k, xs) -> Add(k, Region.points(*xs.toIntArray())) }) == null) continue
            line.apply(listOf(Add("X", Region.points(*new.toIntArray()))))
            val ruleset = line.ruleset
            val rank = LinkedHashMap<String, Int>()
            for (r in ruleset.rules) for (a in r.atoms) if (a.kind !in rank) rank[a.kind] = rank.size
            val atoms = old.flatMap { (k, xs) -> xs.map { it to k } }
                .sortedWith(compareBy<Pair<Int, String>>({ kotlin.math.abs(it.first) }, { it.first }, { rank[it.second] ?: 99 }, { it.second }))
            var best: Pair<Int, Map<String, Set<Int>>>? = null
            // Every subset, the earliest atoms kept first (as itertools.product((True, False), ...) enumerates).
            for (mask in 0 until (1 shl atoms.size)) {
                val keep = atoms.indices.map { (mask shr (atoms.size - 1 - it)) and 1 == 0 }
                val st = bruteClosure(ruleset, atoms.filterIndexed { i, _ -> keep[i] }, new) ?: continue
                val n = st.values.sumOf { it.size }
                if (best == null || n > best.first) best = n to st
            }
            tried++
            val expected = best!!.second.filterValues { it.isNotEmpty() }.mapValues { Region.points(*it.value.toIntArray()) }
            assertEquals(expected, line.state(), "seed $seed: $rules $old $new")
        }
        assertTrue(tried > seeds, "only $tried cases tried")
    }

    // ================================================================================================================
    // Unbounded: elements may be rays or the whole line
    // ================================================================================================================

    @Test
    fun definiteRulesOnRays() {
        val line = LineSystem(listOf(implies("A", atom("B", -1)), forbids("B", "C")))
        line.apply(listOf(Add("C", Region.closed(-5, 5))))
        val a = line.apply(listOf(Add("A", ray)))
        assertEquals(rayFrom(-1), line.state()["B"])
        assertEquals(Region.interval(-5, -1, true, false), line.state()["C"])
        line.reverse(a)
        assertEquals(state("C" to Region.closed(-5, 5)), line.state())
    }

    @Test
    fun everyAnchorTakesItsFirstPossibleOperand() {
        var line = LineSystem(listOf(impliesAny("A", listOf("B", "C")), forbids("B", "D")))
        line.apply(listOf(Add("A", FULL)))
        assertEquals(state("A" to FULL, "B" to FULL), line.state())
        line = LineSystem(listOf(impliesAny("A", listOf("B", "C")), forbids("B", "D")))
        line.apply(listOf(Add("A", ray), Add("D", Region.closed(2, 3))))
        assertEquals(Region.closed(2, 3), line.state()["C"])
        assertEquals(ray - Region.closed(2, 3), line.state()["B"])
    }

    /**
     * B at x forbids B at x + 1, so neighbours compete for B. Positions are decided closest to 0 first (the left one
     * first on a tie), each taking the first operand that still lets the others be placed; on infinitely many
     * positions the result repeats forever.
     */
    @Test
    fun competingAnchorsClosestTo0First() {
        val rules = listOf(impliesAny("A", listOf("B", "C")), forbids("B", atom("B", 1)))
        val unit = Region.interval(0, 1, true, false)
        fun stateOf(a: Region): Claims {
            val line = LineSystem(rules)
            line.apply(listOf(Add("A", a)))
            return line.state()
        }
        val got = stateOf(ray) // every lattice x + Z starts at its point in [0, 1)
        assertEquals(Region.tiled(unit, 2.q, 0, null), got["B"])
        assertEquals(Region.tiled(unit, 2.q, 0, null).shift(1.q), got["C"])
        assertEquals(Region.point(-1), stateOf(Region.points(-2, -1))["B"]) // -1 is closer to 0
        assertEquals(Region.points(-1, 1), stateOf(Region.points(-1, 1))["B"]) // not neighbours
        assertEquals(Region.point(0), stateOf(Region.points(-1, 0, 1))["B"])
        for (a in listOf(FULL, Region(listOf(Part(NEG_INF, false, 5.q, true))))) {
            val b = stateOf(a).getValue("B")
            for (n in -160..40) {
                val x = Rational.of(n, 8)
                val r = x - Rational.of(x.floor())
                val first = if (r < Rational.of(1, 2)) r else r - Rational.ONE // the lattice's position closest to 0 (left on a tie)
                assertEquals((x - first) % 2.q == Rational.ZERO, x in b, "$a $x")
            }
        }
    }

    /**
     * When the last operand can always be placed, the decisions near 0 only depend on the positions closer to 0, so
     * an element on a ray or on the whole line gives, near 0, what the same element cut far away gives.
     */
    @Test
    fun infinitePositionsAgreeWithLongFiniteOnes() {
        val rng = Random(7)
        repeat(infiniteCases) {
            val off = rng.choice(listOf(1.q, 2.q, Rational.of(1, 2), Rational.of(3, 2)))
            val rules = mutableListOf<LineRule>(
                impliesAny("A", listOf("B", "C", "D")),
                forbids("B", atom("B", off)),
                forbids("B", atom("C", rng.choice(listOf((-1).q, Rational.of(1, 2), 1.q)))),
                forbids("C", atom("C", off * 2)),
            )
            if (rng.nextDouble() < 0.5) {
                rules.add(implies("B", atom("E", rng.choice(listOf(1.q, Rational.of(-1, 2))))))
                rules.add(forbids("E", atom("C", 0)))
            }
            val lo = rng.choice(listOf(NEG_INF, rng.randint(-6, 0).q))
            val hi = if (lo.isFinite) rng.choice(listOf(INF, rng.randint(1, 6).q)) else INF
            var a = if (lo.isFinite) Region(listOf(Part(lo, true, hi, true))) else Region(listOf(Part(lo, false, hi, hi.isFinite)))
            if (hi.isInfinite) a = Region(listOf(Part(lo, lo.isFinite, hi, false)))
            val near = Region.closed(-4, 4)
            val full = LineSystem(rules)
            full.apply(listOf(Add("A", a)))
            val cut = LineSystem(rules)
            cut.apply(listOf(Add("A", a and Region.closed(-20, 20))))
            for (kind in listOf("B", "C", "D")) assertEquals(cut.state().at(kind) and near, full.state().at(kind) and near, "$rules $a $kind")
        }
    }

    @Test
    fun olderPositionsOnARayAdaptAlone() {
        val rules = listOf(impliesAny("A", listOf("B", "C")), forbids("B", atom("B", 1)), forbids("X", "B"), forbids("X", "C"))
        val line = LineSystem(rules)
        line.apply(listOf(Add("A", ray)))
        val before = line.state()
        val x = line.apply(listOf(Add("X", Region.point(10))))
        val st = line.state()
        assertEquals(ray - Region.point(10), st["A"])
        for (n in 0 until 40) {
            assertEquals(n < 10 && n % 2 == 0 || n > 10 && n % 2 == 1, n.q in st.getValue("B"), "$n")
            assertEquals(n < 10 && n % 2 == 1 || n > 10 && n % 2 == 0, n.q in st.getValue("C"), "$n")
        }
        assertEquals(Region.open(10, 11), st.getValue("B") and Region.open(10, 11)) // other lattices: as before
        line.reverse(x)
        assertEquals(before, line.state())
    }

    @Test
    fun conjunctionsOnRays() {
        var line = LineSystem(listOf(implies(listOf(atom("A"), atom("B")), atom("C")), forbids("X", "C")))
        line.apply(listOf(Add("A", ray), Add("B", ray)))
        line.apply(listOf(Add("X", ray)))
        assertEquals(state("A" to ray, "X" to ray), line.state())
        // Infinitely many linked claims: kept closest to 0 first (SEMANTICS.md, § 5).
        line = LineSystem(listOf(implies(listOf(atom("A"), atom("A", 1)), atom("C")), forbids("X", "C")))
        line.apply(listOf(Add("A", ray)))
        line.apply(listOf(Add("X", ray)))
        assertEquals(Region.tiled(Region.interval(0, 1, true, false), 2.q, 0, null), line.state()["A"])
    }

    /**
     * The claims kept are decided closest to 0 first, each from the ones before it, so near 0 an element on a ray or
     * on the whole line keeps what the same element cut far away keeps (D can always be placed far away).
     */
    @Test
    fun olderPositionsKeptOnInfiniteElementsAgreeWithLongFiniteOnes() {
        val rng = Random(11)
        repeat(infiniteCases) {
            val o = rng.choice(listOf((-1).q, 1.q))
            val rules = mutableListOf<LineRule>(
                impliesAny(listOf(atom("A")), listOf(atom("B"), atom("C", o), atom("D"))),
                forbids("B", atom("B", rng.choice(listOf(1, 2)))),
                forbids("C", atom("C", rng.choice(listOf(1, -2)))),
                forbids("X", "B"),
                forbids("X", atom("C", rng.choice(listOf(o, o, 0.q)))),
                forbids("X", atom("D", rng.choice(listOf(0, 1)))),
            )
            if (rng.nextDouble() < 0.5) {
                rules.add(implies("D", atom("E", rng.choice(listOf(1, -1)))))
                rules.add(forbids("E", "B"))
            }
            val a = rng.choice(listOf(ray, FULL, Region(listOf(Part(NEG_INF, false, rng.randint(3, 8).q, true)))))
            val xs = Region.points(*(-5..5).shuffled(rng).take(rng.randint(1, 4)).toIntArray())
            val near = Region.closed(-6, 6)
            val states = listOf(a, a and Region.closed(-30, 30)).map { reg ->
                val line = LineSystem(rules)
                line.apply(listOf(Add("A", reg)))
                line.apply(listOf(Add("X", xs)))
                line.state()
            }
            for (kind in listOf("A", "B", "C", "D", "E")) assertEquals(states[1].at(kind) and near, states[0].at(kind) and near, "$rules $a $xs $kind")
        }
    }

    @Test
    fun boundedAnchorsAmongUnboundedElements() {
        for (rules in listOf(
            listOf(impliesAny("A", listOf("B", "C")), forbids("B", atom("B", 1))),
            listOf(impliesAny("A", listOf("B", "C")), forbids("B", atom("Z", 1))),
        )) {
            val plain = LineSystem(rules)
            val withRay = LineSystem(rules)
            withRay.apply(listOf(Add("Z", rayFrom(5))))
            plain.apply(listOf(Add("Z", Region.closed(5, 100))))
            for (line in listOf(plain, withRay)) line.apply(listOf(Add("A", Region.points(0.q, 1.q, 2.q, 4.q, Rational.of(9, 2)))))
            assertEquals(plain.state()["B"], withRay.state()["B"])
            assertEquals(plain.state()["C"], withRay.state()["C"])
        }
    }

    @Test
    fun wrongCallsFail() {
        val line = LineSystem()
        assertFailsWith<NoSuchElementException> { line.reverse(3) }
        val a = line.apply(listOf(Add("A", Region.closed(0, 1))))
        assertNotNull(line.allEdits.getOrNull(a))
        line.reverse(a)
        assertFailsWith<IllegalArgumentException> { line.reverse(a) }
        if (line.state().isNotEmpty()) fail("the line is not empty")
    }
}
