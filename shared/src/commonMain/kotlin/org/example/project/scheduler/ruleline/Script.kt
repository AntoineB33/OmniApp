package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * Generated scripts — a port of `C:\code\line_manager\scripts\script.py` (its module text describes the language).
 *
 * A script is an object with a state and three elements: S_f ([Script.loops], and the frame s_f, which the display
 * makes), s_u ([Script.onEdit], executed at each edit of the user) and s_v ([Script.onView], when the view moves).
 * Each can change the state, which includes the others but not itself ([ChangeAction]). The STEP loop is exact: an
 * iteration goes through the positions the chip went through since the last one, in order, each trigger at its exact
 * position. What the text says is what the script does.
 *
 * The reference's `Place.run` draws on a Tk canvas: display code, not ported ([Place] is kept as data).
 */
const val MODE_RULES_TEXT = "apply the mode rules"

/** The seconds between two iterations of the exact loop. */
val STEP: Rational = Rational.of(1, 20)

const val ON_EDIT = "the user edit script"
const val ON_VIEW = "the view script"

/** The reference's `_fmt` of a number: `3`, `1/2`, `1+ε`, `+inf`. */
internal fun fmtNum(x: Any): String = when (x) {
    is Pos -> x.toString()
    is Rational -> if (x.isInfinite) (if (x.sign > 0) "+inf" else "-inf") else x.toString()
    is Num.Exact -> fmtNum(x.r)
    is Num.Real -> if (x.d.isInfinite()) (if (x.d > 0) "+inf" else "-inf") else exactRational(x.d).toString() // (Fraction(float), exact)
    else -> x.toString()
}

/** What a problem's error reads, on the line as it is (nothing predicted). */
class NowContext(override val c: Pos) : ProblemContext {
    override val state: LineState get() = throw UnsupportedOperationException("read through the state given")
    override val rules: RuleSet? get() = null
    override fun expected(s: LineState, kind: String, weights: Any?, w: IntervalSet): Rational = s.line.integral(kind, w)
}

/** The optimization score of the line, in (0, 1]: 1 when the targets are reached. */
fun score(problems: List<Pair<OptProblem, Rational>>, state: LineState, chip: Pos): Num {
    var err: Num = Num.ZERO
    for ((p, w) in problems) if (w.sign != 0) err += p.error(NowContext(chip), state) * w
    return Num.ONE / (Num.ONE + err)
}

sealed interface Trigger

data object Start : Trigger {
    override fun toString() = "the program starts"
}

data class ChipIn(val lo: Pos, val hi: Pos, val mode: String? = null) : Trigger {
    fun holds(chip: Pos, mode: String): Boolean = lo <= chip && chip < hi && (this.mode == null || this.mode == mode)

    override fun toString(): String {
        val s = if (hi != Pos.INF) "the chip is in [${fmtNum(lo)}, ${fmtNum(hi)})" else "the chip is at or after ${fmtNum(lo)}"
        return s + (if (mode != null) " and mode is $mode" else "")
    }
}

/**
 * True while the chip is in an interval of [kind], or one is less than [within] after it, and (if [mode] is not null)
 * the mode at the chip is [mode]. With [lasted]: [kind] has been on the chip for [lasted]. With [unless]: and the chip
 * is not on [unless].
 */
data class ChipOn(val kind: String, val mode: String? = null, val within: Rational? = null, val lasted: Rational? = null, val unless: String? = null) : Trigger {
    fun holds(state: LineState, chip: Pos, mode: String): Boolean {
        if (this.mode != null && this.mode != mode) return false
        if (unless != null && (state.line.presence(unless).contains(chip) || state.line.presence(unless).contains(chip + Pos.EPS))) return false
        val pres = state.line.presence(kind)
        if (lasted != null) return Region.of(Interval.of(chip - lasted, chip, true, false)).isSubsetOf(pres)
        val d = within ?: Rational.ZERO
        if (d.sign == 0) return pres.contains(chip)
        return pres.contains(chip) || (pres and Region.of(Interval.of(chip, chip + d, false, false))).isNotEmpty()
    }

    override fun toString(): String {
        val d = within ?: Rational.ZERO
        var where = when {
            lasted != null -> "the chip has been on $kind for ${fmtNum(lasted)}"
            d.sign == 0 -> "the chip is on $kind"
            else -> "$kind is less than ${fmtNum(d)} after the chip"
        }
        if (unless != null) where += " and is not on $unless"
        return where + (if (mode != null) " and mode is $mode" else "")
    }
}

data class TransitionCount(val k: Int) : Trigger {
    override fun toString() = "$k mode transition${if (k > 1) "s" else ""} happened"
}

data class Edited(val only: Boolean = false) : Trigger {
    override fun toString() =
        if (only) "the user edits what the rules, the constraints or the problems read" else "the user edits the line"
}

data object Viewed : Trigger {
    override fun toString() = "the view moves or is resized"
}

data object Uncovered : Trigger {
    override fun toString() = "the chip is where this script does not apply the mode rules"
}

/** The ratio of K at this value on (chip, chip + H] is above y, the chip in [lo, hi) and the mode [mode]. */
data class PresenceAbove(val kind: String, val value: Rational, val y: Rational, val h: Rational, val lo: Pos, val hi: Pos, val mode: String? = null) : Trigger {
    fun holds(state: LineState, chip: Pos, mode: String): Boolean {
        if (!(lo <= chip && chip < hi && (this.mode == null || this.mode == mode))) return false
        val r = state.line.vals[kind]?.get(value) ?: return false
        return r.measureOn(IntervalSet.of(Interval.of(chip, chip + h, false, true))) / h > y
    }

    override fun toString(): String =
        "$kind ${fmtNum(value)}% exceeds a presence ratio of ${fmtNum(y)} over the next ${fmtNum(h)} " +
            "when the chip is in [${fmtNum(lo)}, ${fmtNum(hi)})" + (if (mode != null) " and mode is $mode" else "")
}

/** The optimization score ([score]) is below z. */
data class ScoreBelow(val z: Num) : Trigger {
    override fun toString() = "the optimization score becomes less than ${fmtNum(z)}"
}

sealed interface Action

/** An edit of the line on exact intervals; with [fromChip], its intervals are written from the chip. */
data class EditAction(val ops: List<Op>, val fromChip: Boolean = false) : Action {
    override fun toString() = (if (fromChip) "from the chip, " else "") + ops.joinToString(", ")
}

data object ConstraintEditAction : Action {
    override fun toString() = "satisfy the constraints"
}

data class RerunAction(val first: Boolean = false) : Action {
    override fun toString() = if (first) "run the generator once, as soon as possible" else "run the generator again"
}

data object ShowLine : Action {
    override fun toString() = "show the line"
}

data class Event(val trigger: Trigger, val actions: List<Action>) {
    override fun toString() = "if $trigger, then " + actions.joinToString("; ") + "."
}

data class ChangeAction(val add: Boolean, val target: String, val event: Event) : Action {
    override fun toString() = "${if (add) "add to" else "remove from"} $target: \"${event.toString().removeSuffix(".")}\""
}

fun loopName(period: Rational): String = "every ${fmtNum(period)} s"

/** A script of S_f: these events, executed every [period] seconds. */
data class Loop(val period: Rational, val events: List<Event> = emptyList())

/** An instruction of s_f (what to draw): display data, kept as such. */
data class Place(val item: Any?, val shape: String, val coords: List<Double> = emptyList(), val rates: List<Double> = emptyList()) {
    override fun toString() = "place $shape at " + (coords.joinToString(", ").ifEmpty { "the line" })
}

val SHOW: List<Event> = listOf(Event(Viewed, listOf(ShowLine)))

data class Script(
    val loops: List<Loop> = emptyList(),
    val onEdit: List<Event> = emptyList(),
    val onView: List<Event> = emptyList(),
    /** The positions and modes the run compiled: (lo, hi, mode). */
    val covered: List<Triple<Pos, Pos, String?>> = emptyList(),
    /** It applies the mode rules everywhere. */
    val live: Boolean = false,
    /** What it keeps. */
    val constraints: List<Constraint> = emptyList(),
    /** The mode rules compiled by the run, in the order of the chip. */
    val stretches: List<Stretch> = emptyList(),
    /** The chip's position the run compiled from. */
    val origin: Pos? = null,
    /** The line it compiled from, its own edits made. */
    val base: LineState? = null,
) {
    /** s_f, made by the display; excluded from equality as in the reference. */
    var frame: List<Place> = emptyList()
    var frameGiven: Int? = null
    var trueEvents: MutableSet<String> = HashSet()
    var fired: MutableSet<String> = HashSet()
    var since: Int = 0
    private var pushesCache: Map<String, ChipPush>? = null

    /** This copy with the runtime state of [from] (the reference's `dataclasses.replace` carries every field). */
    fun carrying(from: Script): Script {
        frame = from.frame
        frameGiven = from.frameGiven
        trueEvents = from.trueEvents
        fired = from.fired
        since = from.since
        return this
    }

    /** {the text of an event: its push} for each event of the chip pushing a kind, where the script is [live]. */
    fun pushes(): Map<String, ChipPush> {
        pushesCache?.let { return it }
        val out = LinkedHashMap<String, ChipPush>()
        if (live) for (e in events) pushOf(e)?.let { out[e.toString()] = it }
        pushesCache = out
        return out
    }

    /** What the simulation executes (not s_v, which the window does). */
    val events: List<Event> get() = loops.flatMap { it.events } + onEdit

    /** The same script with these events; those of another loop stay in it. */
    fun withEvents(events: List<Event>): Script {
        val where = HashMap<String, Rational>()
        for (loop in loops) for (e in loop.events) where[e.toString()] = loop.period
        val parts = LinkedHashMap<Rational, MutableList<Event>>()
        parts[STEP] = ArrayList()
        for (loop in loops) parts.getOrPut(loop.period) { ArrayList() }
        val edits = ArrayList<Event>()
        for (e in events) if (e.trigger is Edited) edits.add(e) else parts.getValue(where[e.toString()] ?: STEP).add(e)
        return copy(loops = parts.filter { (p, evs) -> evs.isNotEmpty() || p == STEP }.map { (p, evs) -> Loop(p, evs) }, onEdit = edits).carrying(this)
    }

    /** The name of the part of the script this event is in. */
    fun partOf(e: Event): String {
        if (onEdit.any { it.toString() == e.toString() }) return ON_EDIT
        if (onView.any { it.toString() == e.toString() }) return ON_VIEW
        return loops.firstOrNull { l -> l.events.any { it.toString() == e.toString() } }?.let { loopName(it.period) } ?: loopName(STEP)
    }

    /** The script after this action. */
    fun changed(a: ChangeAction): Script {
        fun edit(events: List<Event>) = if (a.add) events + a.event else events.filter { it.toString() != a.event.toString() }
        if (a.target == ON_EDIT) return copy(onEdit = edit(onEdit)).carrying(this)
        if (a.target == ON_VIEW) return copy(onView = edit(onView)).carrying(this)
        val ls = loops.toMutableList()
        for ((i, loop) in ls.withIndex()) {
            if (loopName(loop.period) == a.target) {
                ls[i] = loop.copy(events = edit(loop.events))
                return copy(loops = ls).carrying(this)
            }
        }
        val period = parseFraction(a.target.removePrefix("every ").removeSuffix(" s"))
        return if (a.add) copy(loops = ls + Loop(period, listOf(a.event))).carrying(this) else this
    }

    /** The events the simulation's thread executes when the other loops have threads. */
    fun inStep(): List<Event> = loops.filter { it.period == STEP }.flatMap { it.events } + onEdit

    fun showsLine(): Boolean = onView.any { e -> e.actions.any { it is ShowLine } }

    fun text(): String {
        val applied = covered.map { (lo, hi, m) -> "if the chip is in [$lo, $hi]" + (if (m != null) " and mode is $m" else "") + ", then $MODE_RULES_TEXT." } +
            (if (live) listOf("elsewhere, $MODE_RULES_TEXT.") else emptyList())
        val out = ArrayList<String>(constraints.map { it.toString() })
        for (loop in loops.sortedBy { it.period != STEP }) {
            val body = loop.events.map { it.toString() } + (if (loop.period == STEP) applied else emptyList())
            if (body.isNotEmpty()) {
                out.add("${loopName(loop.period)}:")
                out.addAll(body.map { "  $it" })
            }
        }
        return (out + (onEdit + onView).map { it.toString() }).joinToString("\n")
    }

    fun rerunTriggers(): List<Trigger> = events.filter { e -> e.actions.any { it is RerunAction } }.map { it.trigger }

    companion object {
        /** The script of these events: each in the part its trigger says, the STEP loop if none. */
        fun of(events: List<Event> = emptyList(), live: Boolean = false, constraints: List<Constraint> = emptyList(), onView: List<Event> = emptyList()): Script =
            Script(live = live, constraints = constraints, onView = onView).withEvents(events)

        /** The script at the start of the program: run the generator once, as soon as possible. */
        fun initial(): Script = of(listOf(Event(Start, listOf(RerunAction(first = true)))))
    }
}
