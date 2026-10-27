package org.example.project.scheduler.line

import org.example.project.scheduler.line.LineOp.Add
import org.example.project.scheduler.line.LineOp.Move
import org.example.project.scheduler.line.LineOp.Remove
import org.example.project.scheduler.line.Region.Companion.EMPTY
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fold (ADR 0018 phase 2, `docs/invariants/line.md` § *The fold*): the far past is a fixed record, a fold keeps
 * the line exactly as it is, an edit is cut at the fold point, and the replay holds only what is live.
 */
class LineFoldTest {
    private val seeds = System.getenv("REVLOGIC_SEEDS")?.toIntOrNull() ?: 60

    private fun Claims.before(f: Rational): Claims = mapValues { it.value and Region.below(f) }.filterValues { it.isNotEmpty() }

    private fun Claims.from(f: Rational): Claims = mapValues { it.value and Region.atOrAbove(f) }.filterValues { it.isNotEmpty() }

    private fun checkInvariants(line: FoldedLine, where: String) {
        val st = line.state()
        assertEquals(emptyList(), line.ruleset.violations(st), "$where: rules violated")
        if (line.foldAt.isFinite) assertEquals(line.folded, st.before(line.foldAt), "$where: the record is the line before the fold point")
    }

    @Test
    fun withoutAFoldItIsTheLineSystem() {
        for (seed in 0 until seeds) {
            val rng = Random(seed)
            val rules = randRules(rng)
            val plain = LineSystem(rules)
            val folded = FoldedLine(rules)
            val ids = mutableListOf<Pair<Int, Int>>()
            repeat(rng.randint(3, 6)) {
                val ops = randEdit(rng, plain.state())
                val a = tryApply(plain, ops)
                val b = refusedAsNull { folded.apply(ops) }
                assertEquals(a == null, b == null, "seed $seed: $ops")
                if (a != null && b != null) ids.add(a to b)
                assertEquals(plain.state(), folded.state(), "seed $seed")
            }
            for ((a, b) in ids.reversed()) {
                plain.reverse(a)
                folded.reverse(b)
                assertEquals(plain.state(), folded.state(), "seed $seed: reverse")
            }
        }
    }

    /** A fold never changes the line; with definite rules it is always made. */
    @Test
    fun aFoldKeepsTheLine() {
        var made = 0
        var refused = 0
        for (seed in 0 until seeds * 2) {
            val rng = Random(100 + seed)
            val definite = rng.nextDouble() < 0.5
            val rules = randRules(rng).let { r -> if (definite) r.filter { it !is LineRule.ImpliesAny } else r }
            val line = FoldedLine(rules)
            repeat(rng.randint(3, 7)) { refusedAsNull { line.apply(randEdit(rng, line.state())) } }
            var at = Rational.of(rng.randint(-2, 6), 2)
            repeat(3) {
                val before = line.state()
                val foldBefore = line.foldAt
                val ok = line.fold(at)
                assertEquals(before, line.state(), "seed $seed: fold at $at changed the line")
                if (ok) {
                    made++
                    assertEquals(at, line.foldAt)
                } else {
                    refused++
                    assertFalse(definite, "seed $seed: a fold under definite rules was refused")
                    assertEquals(foldBefore, line.foldAt)
                }
                checkInvariants(line, "seed $seed after fold")
                // The line goes on after the fold: edits after it are live, before it flat.
                repeat(rng.randint(1, 3)) {
                    val st = line.state()
                    val ops = randEdit(rng, st)
                    val id = refusedAsNull { line.apply(ops) }
                    if (id == null) {
                        assertEquals(st, line.state(), "seed $seed: a refused edit changes nothing")
                    } else {
                        checkInvariants(line, "seed $seed after $ops")
                        val past = st.before(line.foldAt)
                        if (line.foldAt.isFinite && ops.all { o -> (taken(o) or put(o)) - Region.atOrAbove(line.foldAt) == EMPTY }) {
                            assertEquals(past, line.state().before(line.foldAt), "seed $seed: a live edit changed the folded past")
                        }
                        if (rng.nextDouble() < 0.5) {
                            line.reverse(id)
                            assertEquals(st, line.state(), "seed $seed: undo right after")
                        }
                    }
                }
                at += Rational.of(rng.randint(0, 4), 2)
            }
        }
        assertTrue(made > seeds, "only $made folds made ($refused refused)")
        println("LineFoldTest: $made folds made, $refused refused (all under ordered disjunctions)")
    }

    /**
     * The fold is a pin with TOP priority. With "X forbids C one unit right", a C an older edit put at 5.5 gave way to
     * a newer X at 4.5. Folded at 5, the X is in the record: were the record the OLDEST level, the C would come back
     * and push the X out of the past.
     */
    @Test
    fun theFoldedPastOutranksEveryLiveEdit() {
        val line = FoldedLine(listOf(forbids("X", atom("C", 1))))
        line.apply(listOf(Add("C", Region.point(Rational.of(11, 2)))))
        line.apply(listOf(Add("X", Region.point(Rational.of(9, 2)))))
        val before = line.state()
        assertEquals(mapOf("X" to Region.point(Rational.of(9, 2))), before)
        assertTrue(line.fold(5.q))
        assertEquals(before, line.state())
        assertEquals(mapOf("X" to Region.point(Rational.of(9, 2))), line.folded)
    }

    /** An edit whose rules reach into the folded past is refused: nothing adapts there. */
    @Test
    fun anEditThatWouldChangeThePastIsRefused() {
        val line = FoldedLine(listOf(implies("B", atom("A", -3)), forbids("Z", "A")))
        line.apply(listOf(Add("Z", Region.closed(0, 9))))
        assertTrue(line.fold(10.q))
        val before = line.state()
        assertFailsWith<ContradictionError> { line.apply(listOf(Add("B", Region.point(11)))) } // needs A at 8, where Z is
        assertEquals(before, line.state())
        line.apply(listOf(Add("B", Region.point(14)))) // A at 11: after the fold point, fine
        assertEquals(Region.point(11), line.state()["A"])
    }

    /** What an edit does before the fold point is a plain diff of the record; refused if the record would break a rule. */
    @Test
    fun anEditBeforeTheFoldPointIsFlat() {
        val line = FoldedLine(listOf(implies("A", "B"), forbids("D", "C")))
        line.apply(listOf(Add("D", Region.closed(2, 4))))
        assertTrue(line.fold(10.q))
        val before = line.state()
        // A flat edit adapts nothing: C where D is would need D to go, so it is refused.
        assertFailsWith<ContradictionError> { line.apply(listOf(Add("C", Region.point(3)))) }
        // A without its B breaks "A -> B" in the record: refused; with its B it is a plain diff.
        assertFailsWith<ContradictionError> { line.apply(listOf(Add("A", Region.point(6)))) }
        assertEquals(before, line.state())
        val id = line.apply(listOf(Add("A", Region.point(6)), Add("B", Region.point(6))))
        assertEquals(Region.point(6), line.folded["A"])
        assertEquals(Region.point(6), line.state()["B"])
        line.reverse(id)
        assertEquals(before, line.state())
        assertFailsWith<MissingElementError> { line.apply(listOf(Remove("C", Region.point(3)))) } // nothing there
    }

    @Test
    fun anEditAcrossTheFoldPointIsCutThere() {
        val line = FoldedLine()
        assertTrue(line.fold(10.q))
        val id = line.apply(listOf(Add("A", Region.closed(8, 12))))
        assertEquals(Region.interval(8, 10, true, false), line.folded["A"])
        assertEquals(Region.closed(8, 12), line.state()["A"])
        line.reverse(id)
        assertEquals(emptyMap(), line.state())
    }

    /** A move from after the fold point to before it: what it takes is live, what it puts is flat. */
    @Test
    fun aMoveAcrossTheFoldPoint() {
        val line = FoldedLine()
        line.apply(listOf(Add("A", Region.closed(12, 13))))
        assertTrue(line.fold(10.q))
        val id = line.apply(listOf(Move("A", Region.closed(12, 13), (-4).q)))
        assertEquals(mapOf("A" to Region.closed(8, 9)), line.state())
        assertEquals(mapOf("A" to Region.closed(8, 9)), line.folded)
        line.reverse(id)
        assertEquals(mapOf("A" to Region.closed(12, 13)), line.state())
        assertFailsWith<MissingElementError> { line.apply(listOf(Move("A", Region.closed(2, 3), 20.q))) } // nothing to move
    }

    /** A fold forgets what lies wholly before it — and its undo; an edit across it keeps its live remainder. */
    @Test
    fun aFoldForgetsWhatItLeavesNothingOf() {
        val line = FoldedLine()
        val old = line.apply(listOf(Add("A", Region.closed(1, 2))))
        val across = line.apply(listOf(Add("B", Region.closed(4, 7))))
        val young = line.apply(listOf(Add("C", Region.closed(8, 9))))
        assertTrue(line.fold(5.q))
        assertEquals(listOf(across, young), line.liveRecords.map { it.id })
        assertFailsWith<NoSuchElementException> { line.reverse(old) }
        line.reverse(across) // takes back [5, 7] only: [4, 5) is the record's
        assertEquals(mapOf("A" to Region.closed(1, 2), "B" to Region.interval(4, 5, true, false), "C" to Region.closed(8, 9)), line.state())
        assertEquals(2, line.replayedEdits) // the rebuilt replay: the live parts of `across` and `young`
    }

    /**
     * The replay holds what touches the window, not the whole history. A 5-day window here: with the real 30 days the
     * replay holds ~60 edits and every edit costs ~100 ms (a full re-solve each; ADR 0018 § *Open*), 35 s for this test.
     */
    @Test
    fun theReplayIsBoundedByTheWindow() {
        val line = FoldedLine(listOf(implies("A", "B")))
        val day = 10
        val window = 5
        for (d in 0 until 60) {
            line.apply(listOf(Add("A", Region.closed(d * day, d * day + 3))))
            line.apply(listOf(Move("A", Region.closed(d * day, d * day + 3), 2.q)))
            if (d >= window) assertTrue(line.fold(((d - window) * day).q))
            assertTrue(line.replayedEdits <= 2 * (window + 2), "day $d: ${line.replayedEdits} edits replayed")
        }
        assertEquals(Region.unionOf((0 until 60).map { Region.closed(it * day + 2, it * day + 5) }), line.state()["B"])
    }

    @Test
    fun aDragIsLiveOnly() {
        val line = FoldedLine(listOf(forbids("D", "C")))
        line.apply(listOf(Add("D", Region.point(14)), Add("C", Region.closed(11, 12)), Add("C", Region.closed(3, 4))))
        assertTrue(line.fold(10.q))
        assertFailsWith<IllegalArgumentException> { line.drag(listOf("C" to Region.closed(3, 4))) }
        val drag = line.drag(listOf("C" to Region.closed(11, 12)))
        drag.step(3.q)
        assertNull(line.state()["D"])
        drag.step(5.q)
        val id = drag.drop()!!
        assertEquals(Region.point(14), line.state()["D"])
        line.reverse(id)
        assertEquals(Region.closed(3, 4) or Region.closed(11, 12), line.state()["C"])
    }

    @Test
    fun theFoldPointIsMidnightUtcThirtyDaysBefore() {
        val day = 86_400_000L
        val now = 20_000 * day + 13 * 3_600_000L // 13h UTC on day 20000
        assertEquals((20_000 - 30) * day, foldPointMillis(now))
        assertEquals(foldPointMillis(now), foldPointMillis(20_000 * day + day - 1)) // the same all day long
        assertEquals((20_000 - 29) * day, foldPointMillis(20_001 * day))
        assertFailsWith<IllegalArgumentException> { FoldedLine().apply { fold(5.q) }.fold(4.q) } // only forward
    }
}
