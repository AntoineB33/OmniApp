package org.example.project.scheduler.ruleline


/**
 * Probability finder: probability rules in the pattern of mode transitions — a port of
 * `C:\code\line_manager\scripts\probability.py` (`docs/scheduler/scheduler_requirements.md`: "used to find probability
 * rules in the mode transitions pattern. This helps the rules generator being more performant with a low q").
 *
 * From the revealed modes it counts, over completed segments, which mode follows which and how long each lasts (the
 * first segment excluded from the lengths). From these it predicts the future as weighted scenarios after the
 * frontier, conditioned on how long the current segment has already lasted.
 */
class Scenario(val probability: Rational, val cover: Map<String, IntervalSet>, val uncovered: IntervalSet) {
    fun modeRegion(mode: String): IntervalSet = cover[mode] ?: IntervalSet.EMPTY
}

class ModeModel(
    val nextCounts: MutableMap<String, MutableMap<String, Int>> = LinkedHashMap(),
    val lengthCounts: MutableMap<String, MutableMap<Rational, Int>> = LinkedHashMap(),
) {
    fun rules(): List<String> {
        val out = ArrayList<String>()
        for (a in nextCounts.keys.sorted()) {
            val total = nextCounts.getValue(a).values.sum()
            for ((b, n) in nextCounts.getValue(a).entries.sortedBy { it.key }) out.add("after mode $a, mode $b follows with probability ${Rational.of(n, total)}")
        }
        for (a in lengthCounts.keys.sorted()) {
            val total = lengthCounts.getValue(a).values.sum()
            for ((l, n) in lengthCounts.getValue(a).entries.sortedBy { it.key }) out.add("mode $a lasts $l with probability ${Rational.of(n, total)}")
        }
        return out
    }

    private class Partial(val p: Rational, val counter: Int, val segs: List<Triple<Rational, Rational, String>>, val start: Rational, val mode: String, val elapsed: Rational?)

    /** Most probable scenarios for (F, until], best first, and the probability left out. */
    fun scenarios(known: ModeKnowledge, until: Rational, maxScenarios: Int = 8, maxExpansions: Int = 256): Pair<List<Scenario>, Rational> {
        val f = known.frontier
        val heap = ArrayList<Partial>()
        heap.add(Partial(Rational.ONE, 0, emptyList(), known.currentStart(), known.currentMode(), f - known.currentStart()))
        var counter = 1
        val done = ArrayList<Scenario>()
        var expansions = 0
        while (heap.isNotEmpty() && done.size < maxScenarios && expansions < maxExpansions) {
            // (heapq: the most probable first, the earliest pushed on a tie)
            var bi = 0
            for (i in 1 until heap.size) {
                val a = heap[i]
                val b = heap[bi]
                if (a.p > b.p || (a.p == b.p && a.counter < b.counter)) bi = i
            }
            val cur = heap.removeAt(bi)
            expansions++
            val p = cur.p
            if (cur.start >= until) {
                done.add(scenario(p, cur.segs, f, until))
                continue
            }
            val lengths = lengthCounts[cur.mode].orEmpty()
            val options = if (cur.elapsed == null) lengths else lengths.filter { it.key > cur.elapsed }
            val total = options.values.sum()
            if (total == 0) {
                done.add(scenario(p, cur.segs, f, until))
                continue
            }
            for ((l, n) in options.entries.sortedBy { it.key }) {
                val end = cur.start + l
                val seg = Triple(maxOf(cur.start, f), end, cur.mode)
                val nxt = nextCounts[cur.mode].orEmpty()
                val pl = p * Rational.of(n, total)
                if (end >= until) {
                    heap.add(Partial(pl, counter++, cur.segs + seg, until, cur.mode, null))
                    continue
                }
                val ntotal = nxt.values.sum()
                if (ntotal == 0) {
                    done.add(scenario(pl, cur.segs + seg, f, until))
                    continue
                }
                for ((b, k) in nxt.entries.sortedBy { it.key }) heap.add(Partial(pl * Rational.of(k, ntotal), counter++, cur.segs + seg, end, b, null))
            }
        }
        val residual = Rational.ONE - done.fold(Rational.ZERO) { a, s -> a + s.probability }
        return done to residual
    }

    private fun scenario(p: Rational, segs: List<Triple<Rational, Rational, String>>, f: Rational, until: Rational): Scenario {
        val horizon = IntervalSet.of(Interval.of(Pos(f), Pos(until), false, true))
        val cover = LinkedHashMap<String, IntervalSet>()
        for ((lo, hi, m) in segs) {
            val iv = Interval.make(Pos(lo), Pos(hi), true, false) ?: continue
            cover[m] = (cover[m] ?: IntervalSet.EMPTY) or (IntervalSet.of(iv) and horizon)
        }
        var covered = IntervalSet.EMPTY
        for (c in cover.values) covered = covered or c
        return Scenario(p, cover, horizon - covered)
    }
}

class ProbabilityFinder {
    fun fit(known: ModeKnowledge): ModeModel {
        val model = ModeModel()
        val segs = known.segments()
        for (i in 0 until segs.size - 1) { // completed segments
            val (lo, hi, m) = segs[i]
            val nc = model.nextCounts.getOrPut(m) { LinkedHashMap() }
            nc[segs[i + 1].third] = (nc[segs[i + 1].third] ?: 0) + 1
            if (i > 0) {
                val lc = model.lengthCounts.getOrPut(m) { LinkedHashMap() }
                lc[hi - lo] = (lc[hi - lo] ?: 0) + 1
            }
        }
        return model
    }
}
