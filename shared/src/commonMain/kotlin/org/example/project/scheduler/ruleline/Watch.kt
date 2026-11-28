package org.example.project.scheduler.ruleline


/**
 * What an event of the script has to be looked at for — a port of `C:\code\line_manager\scripts\watch.py`: for each
 * trigger, the next position of the chip where it can change and what it reads ([look]); it is tested again when the
 * chip gets there or what it reads changes, so nothing is computed while nothing can change.
 */
object Watch {
    /** Right after a level is reached: a ratio "exceeds" it past that position. */
    val TINY: Rational = Rational.of(1, 1024)

    /** The stretches looked through at once. */
    const val MOST = 32

    /** f linear between the sorted points: the first position after points[0] where f > level changes; null if not. */
    fun crossing(f: (Pos) -> Num, points: List<Pos>, level: Num): Pos? {
        var a = points[0]
        var fa = f(a)
        val above = fa > level
        for (b in points.drop(1)) {
            val fb = f(b)
            if ((fb > level) != above) {
                val x = Num.Exact(a.x) + Num.Exact((b - a).x) * ((level - fa) / (fb - fa))
                val out = if (above) x else x + minNum(Num.Exact(TINY), (Num.Exact(b.x) - x) / Num.Exact(Rational.of(2)))
                return Pos(when (out) {
                    is Num.Exact -> out.r
                    is Num.Real -> exactRational(out.d) // (a float position in the reference)
                })
            }
            a = b
            fa = fb
        }
        return null
    }

    private fun minNum(a: Num, b: Num) = if (b < a) b else a

    /** The positions of (a, b) where the measure of these regions on (x, x + H] changes slope. */
    private fun ends(regions: List<Region>, a: Pos, b: Pos, h: Rational): Set<Pos> {
        val out = HashSet<Pos>()
        for (r in regions) for (iv in r.on(a, b + h).intervals) for (e in listOf(iv.lo.x, iv.hi.x)) {
            for (x in listOf(Pos(e), Pos(e - h))) if (a < x && x < b) out.add(x)
        }
        return out
    }

    private fun search(f: (Pos) -> Num, a: Pos, b: Pos, level: Num, more: Set<Pos> = emptySet()): Pos {
        val points = (setOf(a, b) + more).sorted().take(MOST)
        return crossing(f, points, level) ?: points.last()
    }

    /** Where a presence ratio crosses its target: |ratio - target| changes slope there. */
    private fun kinks(problems: List<Pair<PresenceRatioProblem, Rational>>, state: LineState, points: List<Pos>): Set<Pos> {
        val out = HashSet<Pos>()
        for ((p, _) in problems) {
            val h = p.window!!
            for ((k, r) in p.targets) {
                fun g(x: Pos) = state.line.integral(k, IntervalSet.of(Interval.of(x, x + h, false, true))) / h - r
                for ((a, b) in points.zipWithNext()) {
                    val ga = g(a)
                    val gb = g(b)
                    if ((ga.sign > 0) != (gb.sign > 0) && ga != gb) out.add(a + (b - a).x * (-ga / (gb - ga)))
                }
            }
        }
        return out
    }

    /** What the trigger reads, besides the chip. */
    fun reads(trigger: Trigger, state: LineState, mode: String, problems: List<Pair<OptProblem, Rational>>): List<Any?> = when (trigger) {
        is ChipIn -> if (trigger.mode != null) listOf(mode) else emptyList()
        is PresenceAbove -> listOf(if (trigger.mode != null) mode else null, state.line.vals[trigger.kind]?.get(trigger.value))
        else -> problems.flatMap { (p, _) -> (p as PresenceRatioProblem).targets.keys.map { state.line.vals[it] } }
    }

    private fun next(trigger: Trigger, state: LineState, c: Pos, problems: List<Pair<OptProblem, Rational>>): Pos {
        if (trigger is ChipIn) return if (c < trigger.lo) trigger.lo else if (c < trigger.hi) trigger.hi else Pos.INF
        if (trigger is PresenceAbove) {
            if (c < trigger.lo) return trigger.lo
            if (c >= trigger.hi) return Pos.INF
            val r = state.line.vals[trigger.kind]?.get(trigger.value) ?: return Pos.INF
            val h = trigger.h
            val top = min(trigger.hi, c + h)
            val ratio = { x: Pos -> Num.Exact(r.measureOn(IntervalSet.of(Interval.of(x, x + h, false, true))) / h) }
            return search(ratio, c, top, Num.Exact(trigger.y), ends(listOf(r), c, top, h))
        }
        val sb = trigger as ScoreBelow
        if (sb.z <= Num.ZERO) return Pos.INF
        val pr = problems.map { (p, w) -> (p as PresenceRatioProblem) to w }
        val h = pr.maxOf { it.first.window!! }
        val kinds = pr.flatMap { it.first.targets.keys }
        val top = c + h
        val regions = kinds.flatMap { state.line.vals[it].orEmpty().values }
        val points = (setOf(c, top) + ends(regions, c, top, h)).sorted().take(MOST)
        val error = { x: Pos ->
            var e: Num = Num.ZERO
            for ((p, w) in problems) if (w.sign != 0) e += p.error(NowContext(x), state) * w
            e
        }
        return search(error, points[0], points.last(), Num.ONE / sb.z - Num.ONE, points.toSet() + kinks(pr, state, points))
    }

    /** (the next position where the trigger can change, what it reads then); null if it is read at each instant. */
    fun look(trigger: Trigger, state: LineState, c: Pos, mode: String, problems: List<Pair<OptProblem, Rational>>): Pair<Pos, List<Any?>>? {
        if (trigger is ScoreBelow) {
            if (!problems.all { it.first is PresenceRatioProblem }) return null
        } else if (trigger !is ChipIn && trigger !is PresenceAbove) {
            return null
        }
        return next(trigger, state, c, problems) to reads(trigger, state, mode, problems)
    }

    /** Is the trigger to be tested now? [entry]: its last [look]. */
    fun due(entry: Pair<Pos, List<Any?>>?, trigger: Trigger, state: LineState, c: Pos, mode: String, problems: List<Pair<OptProblem, Rational>>): Boolean {
        if (entry == null) return true
        val (at, seen) = entry
        if (c >= at) return true
        val now = reads(trigger, state, mode, problems)
        return seen.size != now.size || seen.zip(now).any { (a, b) -> a !== b && a != b }
    }
}
