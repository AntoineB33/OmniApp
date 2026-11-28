package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * Modes of the chip positions — a port of `C:\code\line_manager\scripts\modes.py`.
 *
 * Modes are defined on [start, +inf). A timeline is an initial mode and a strictly increasing list of transitions
 * (t_i, m_i), t_i > start, m_i != m_{i-1}; the mode at x is the mode of the last transition at or before x. Every
 * bounded stretch holds finitely many transitions. [ModeTimeline] is the external truth; [ModeKnowledge] is what is
 * revealed when the chip is at a frontier F.
 */
class ModeTimeline(start: Rational, var initialMode: String, transitions: List<Pair<Rational, String>> = emptyList()) {
    val start: Rational = start
    val transitions: MutableList<Pair<Rational, String>> = transitions.toMutableList()

    init {
        var prevT = start
        var prevM = initialMode
        for ((t, m) in this.transitions) {
            require(t > prevT) { "transition positions must be strictly increasing and after the start" }
            require(m != prevM) { "transition at $t does not change the mode" }
            prevT = t
            prevM = m
        }
    }

    /** New modes, given externally; a transition that does not change the mode is dropped. */
    fun replace(initialMode: String, transitions: List<Pair<Rational, String>>) {
        val out = ArrayList<Pair<Rational, String>>()
        var prev = initialMode
        for ((t, m) in transitions) {
            require(t > start) { "transitions are after the start" }
            if (m != prev) {
                out.add(t to m)
                prev = m
            }
        }
        this.initialMode = initialMode
        this.transitions.clear()
        this.transitions.addAll(out)
    }

    fun modeAt(x: Pos): String {
        require(x >= Pos(start)) { "no mode before the start $start" }
        var mode = initialMode
        for ((t, m) in transitions) if (Pos(t) <= x) mode = m else break
        return mode
    }

    fun known(frontier: Rational): ModeKnowledge =
        ModeKnowledge(start, frontier, initialMode, transitions.filter { it.first <= frontier })
}

class ModeKnowledge(val start: Rational, val frontier: Rational, val initialMode: String, val transitions: List<Pair<Rational, String>>) {
    init {
        require(frontier >= start) { "frontier before the start" }
    }

    /** Known segments (lo, hi, mode): [lo, hi) for completed ones, [lo, F] for the current one. */
    fun segments(): List<Triple<Rational, Rational, String>> {
        val starts = listOf(start to initialMode) + transitions
        return starts.mapIndexed { i, (lo, m) -> Triple(lo, if (i + 1 < starts.size) starts[i + 1].first else frontier, m) }
    }

    fun currentMode(): String = transitions.lastOrNull()?.second ?: initialMode

    fun currentStart(): Rational = transitions.lastOrNull()?.first ?: start

    /** Known positions with this mode. */
    fun region(mode: String): IntervalSet {
        val segs = segments()
        val out = ArrayList<Interval>()
        for ((i, s) in segs.withIndex()) {
            val (lo, hi, m) = s
            if (m != mode) continue
            val last = i == segs.size - 1
            if (lo < hi || last) Interval.make(Pos(lo), Pos(hi), true, last)?.let { out.add(it) }
        }
        return IntervalSet.of(out)
    }

    /** Positions whose mode is not known yet: (F, +inf). */
    fun unknown(): IntervalSet = IntervalSet.after(Pos(frontier))

    /** Positions that have, or may still get, this mode. */
    fun possible(mode: String): IntervalSet = region(mode) or unknown()

    fun modesSeen(): Set<String> = setOf(initialMode) + transitions.map { it.second }
}
