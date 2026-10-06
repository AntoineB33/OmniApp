package org.example.project.scheduler.domain

import kotlinx.serialization.Serializable
import org.example.project.scheduler.domain.DynamicPeriods.LABEL_20S
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN
import org.example.project.scheduler.domain.DynamicPeriods.MODE_ON_BREAK
import org.example.project.scheduler.domain.DynamicPeriods.Span
import org.example.project.scheduler.domain.DynamicPeriods.Spec

/**
 * `docs/scheduler_requirements.md` § *Default restrictive periods* — **the three screen breaks as a forward state
 * machine**, the ONE place their rules are applied. The runtime drives it with the now-line (and the mode edges and
 * the history rewrites); every question about where the breaks fall AHEAD of the line — the plan's obstacles, the
 * calendar, the pause cue's published windows — runs the same step function forward from the state the line is in
 * ([predict]). § *Rule Structure*: nothing here walks the timeline from an origin. The state is local (a bar per
 * label, the break the line is in, the break it drags, the stretch of "no screen" it is in), and every transition is
 * armed at an instant the state already names ([nextEventMillis]).
 *
 * ### The rules, as the machine applies them
 * - **As early as possible** where the rules below allow: each label has a **bar**, the earliest instant it may start;
 *   it falls due when the line reaches the bar. The timeline starts rested ([initial]: one cadence after the start).
 * - *"After the end of a 20s break, no 20s break in the next 20 minutes"* and each break's own recurrence
 *   ([Spec.cadenceMillis]): applied when a break ENDS ([endActive]). The end of a 5min or a 15min break bars no 20s
 *   break (requirements, 2026-10-05).
 * - *"After a ≥5-minute of 'no screen', no 5min break in the next 1 hour; after a ≥15-minute … no 15min break in the
 *   next 2 hours"* — a stretch of "no screen" bars no 20s break either: applied when the line's stretch of "no screen" ENDS
 *   ([endStretch]). "After" is after the stretch: a break falling due INSIDE a stretch is taken there.
 * - *"Where the rules allow a continuous chain of breaks, the interval of the whole chain only contains one screen
 *   break, the longest of the chain brought to the start"*: a break falling due while another is in progress (or
 *   touching its end) joins it, and the break the line is in becomes the longest of them from the same start
 *   ([Event.Grew]); a break falling due within the reach of a dragged one joins the drag — the longer one TELEPORTS
 *   onto the line (*"the 15min break teleports 5 minutes backward, starting right after $now line$, the 5min break is
 *   removed"*).
 * - *"In a 'no screen' period, if t_b is the start of a screen break and $now line$ < t_b, the break must start at
 *   max($now line$, t_s)"* ([dueOf]): a bar inside a continuous no-screen period is pulled to its start, or to the
 *   line if the line is already in it. **Once per period and label**: the pulled break takes the period's occurrence
 *   of that label ([State.taken]), and the chain rule is what that is — a second one pulled onto the same start would
 *   join the first. A later bar of that label inside the same known period waits for its end.
 * - **Modes** ([effectiveMode], [switchMode]): mode 1 may not be covered by "no screen", so a POSE the line reaches
 *   is dragged, `]now line; now line + d]`, and goes on being dragged; the 20 s break is ENTERED and the line is in
 *   mode 3 until it ends (*"can't go in mode 1 during a '20s screen break'"*). Modes 2 and 3 are covered: a pose is
 *   entered where it falls due; mode 2 drags the 20 s break (*"the $now line$ must be in mode 1 or 3 before entering
 *   the 20s break"*). A dragged break the line becomes covered for is entered on the spot. A line switching to mode 1
 *   inside a pose REMOVES it — the requirements' one exception to the frozen past — and drags it as owed.
 *
 * ### What it is not
 * It reads no task, no pre-placed block and no emptiness: *"the three screen breaks are placed everywhere in the
 * timeline as earliest as possible"*. (The walk it replaced pushed a break out of any stretch nobody could run in —
 * a rule the requirements do not have.) The environment it reads is the no-screen periods alone ([chainsOf]), for
 * the pull; the line's own cover is the state.
 */
object BreakMachine {

    /**
     * Runaway guard for [fire], whose every transition consumes a label at one instant: it can only fire on a degenerate
     * input. [advance] needs none — it stops as soon as an armed trigger would not move the line forward.
     */
    private const val MAX_STEPS = 100_000

    /** A break the line is inside: banked the moment it starts, grown by the chain rule, ended at [endMillis]. */
    @Serializable
    data class Active(
        val label: String,
        val startMillis: Long,
        val endMillis: Long,
        /** Every label this chain has taken the occurrence of (the chain rule's one break stands for all of them). */
        val members: List<String>,
        /** Entered by a line at a screen: the line is in mode 3 until it ends (§ *Mode switching*). */
        val held: Boolean = false,
        /** A "Look away now" the app is conducting: recorded as its own panel, so it is never banked. */
        val conducted: Boolean = false,
    )

    /** A break the line is dragging: `]now line; now line + d]`, owed since [dueMillis]. */
    @Serializable
    data class Drag(val label: String, val dueMillis: Long, val members: List<String>)

    /**
     * The machine at the instant [atMillis] of the line. Persisted with the banked record ([FrozenScreenBreaks.machine]),
     * so a restart continues from it rather than re-deriving where the breaks fall.
     */
    @Serializable
    data class State(
        val atMillis: Long,
        /** The mode the devices report (the requirements' mode before the look-away hold, [effectiveMode]). */
        val baseMode: Int,
        /** Per label: the earliest instant the rules let it start. */
        val bars: Map<String, Long>,
        val active: Active? = null,
        val drag: Drag? = null,
        /** Start of the stretch of "no screen" the line is in, or null while the line is at a screen. */
        val stretchStart: Long? = null,
        /** Per label: the start of the continuous no-screen period that has taken its occurrence. */
        val taken: Map<String, Long> = emptyMap(),
        /**
         * The last stretch the line was covered for, `[lastStretchStart, lastStretchEnd]`: a known no-screen period that
         * continues it (a night the line walked into while away, and came back to a screen inside) is the same
         * continuous period, whose occurrences it already took.
         */
        val lastStretchStart: Long? = null,
        val lastStretchEnd: Long? = null,
    )

    /** What happened as the line moved: the runtime banks, un-banks and announces from these. */
    sealed interface Event {
        val atMillis: Long

        /** A break started at the line: banked whole, from its start. */
        data class Started(
            val label: String,
            val startMillis: Long,
            val endMillis: Long,
            val conducted: Boolean = false,
        ) : Event {
            override val atMillis: Long get() = startMillis
        }

        /** The chain rule made the break the line is in the longest of its chain: same start, [label] now, to [endMillis]. */
        data class Grew(
            override val atMillis: Long,
            val label: String,
            val startMillis: Long,
            val endMillis: Long,
            val fromLabel: String,
            val fromEndMillis: Long,
        ) : Event

        /** The break the line was in ended. */
        data class Ended(val label: String, val startMillis: Long, val endMillis: Long, val conducted: Boolean = false) : Event {
            override val atMillis: Long get() = endMillis
        }

        /** The requirements' one removal: a mode-1 line inside a pose. Un-bank it; it is owed again. */
        data class Removed(override val atMillis: Long, val label: String, val startMillis: Long, val endMillis: Long) : Event

        /** A pose fell due at a line that drags it (or teleported onto it): owed from [atMillis]. */
        data class Owed(override val atMillis: Long, val label: String) : Event
    }

    /**
     * Which breaks the line drags. [HOLD] is the requirements' own: the mode says. [TAKE_POSES] is a line that takes
     * each pose where it falls due — what the calendar draws ahead and what the server is told (the user follows the
     * app): a pose never drags, and the line is in mode 3 while it is inside one.
     */
    enum class Policy { HOLD, TAKE_POSES }

    /** The machine at a line with no history: the timeline starts rested, so each label is barred one cadence. */
    fun initial(atMillis: Long, specs: List<Spec>, baseMode: Int = MODE_AT_SCREEN): State =
        State(
            atMillis = atMillis,
            baseMode = baseMode,
            bars = specs.associate { it.label to atMillis + it.cadenceMillis },
            stretchStart = atMillis.takeIf { baseMode != MODE_AT_SCREEN },
        )

    /** The requirements' mode at the line: [State.baseMode], except that mode 1 cannot be inside a 20 s break. */
    fun effectiveMode(state: State): Int {
        val a = state.active
        return if (state.baseMode == MODE_AT_SCREEN && a != null && (a.held || a.label == LABEL_20S)) MODE_ON_BREAK
        else state.baseMode
    }

    /** The merged, sorted stretches of the timeline a period covered by "no screen" makes — what the pull reads. */
    fun chainsOf(periods: List<RestrictivePeriod>): List<Span> {
        val spans =
            periods.filter { it.endMillis > it.startMillis && PeriodKinds.coversNoScreen(it.kind) }
                .map { Span(it.startMillis, it.endMillis) }
                .sortedBy { it.startMillis }
        val out = ArrayList<Span>(spans.size)
        for (s in spans) {
            val prev = out.lastOrNull()
            if (prev != null && s.startMillis <= prev.endMillis) {
                out[out.size - 1] = Span(prev.startMillis, maxOf(prev.endMillis, s.endMillis))
            } else {
                out += s
            }
        }
        return out
    }

    // ---- The next armed trigger -------------------------------------------------------------------------------------

    /**
     * **The next instant the machine changes at**, with the mode held — the trigger the runtime arms. At or before
     * [State.atMillis] when something is due now; null when nothing will happen until the mode changes or history is
     * rewritten.
     */
    fun nextEventMillis(state: State, chains: List<Span>, specs: List<Spec>, policy: Policy = Policy.HOLD): Long? {
        val byLabel = specs.associateBy { it.label }
        var next: Long? = state.active?.endMillis
        val drag = state.drag
        for (spec in specs) {
            val due = dueOf(state, spec.label, chains) ?: continue
            val at =
                if (drag != null && state.active == null) {
                    if (spec.label in drag.members) continue
                    due - (byLabel[drag.label]?.durationMillis ?: 0L)
                } else {
                    due
                }
            if (next == null || at < next) next = at
        }
        return next
    }

    /**
     * Where [label] falls due at this state of the line, or null while it cannot (in the break or the drag already, or
     * its occurrence in the no-screen period the line is in has been taken).
     */
    fun dueOf(state: State, label: String, chains: List<Span>): Long? {
        if (state.drag?.members?.contains(label) == true) return null
        if (state.active?.members?.contains(label) == true) return null
        val bar = state.bars[label] ?: return null
        val at = state.atMillis
        val q = maxOf(bar, at)
        val period = periodAt(state, q, chains) ?: return q
        if (state.taken[label] == period.startMillis) {
            // One occurrence per continuous period: the next is looked for past it — at its known end, or not at all
            // while the line's own stretch still reaches it (its end is the mode's to say).
            return if (period.endMillis > at) period.endMillis else null
        }
        return maxOf(at, period.startMillis)
    }

    /**
     * The continuous "no screen" period containing [t]: the known stretches ([chains], half-open) merged with the
     * line's own stretch `[stretchStart, atMillis]` (closed at the line — the line is covered). Null outside every one.
     */
    private fun periodAt(state: State, t: Long, chains: List<Span>): Span? {
        val at = state.atMillis
        val ss = state.stretchStart
        val chain = chainAt(chains, t)
        if (ss != null && t in ss..at) {
            // Inside the line's own stretch: grown by the known period the line is standing in, if any.
            val here = chainAt(chains, at)
            val start = minOf(ss, here?.startMillis ?: ss, chain?.startMillis ?: ss)
            return Span(start, maxOf(at, here?.endMillis ?: at))
        }
        if (chain == null) return null
        if (ss != null && chain.startMillis <= at) return Span(minOf(ss, chain.startMillis), chain.endMillis)
        val ls = state.lastStretchStart
        val le = state.lastStretchEnd
        if (ls != null && le != null && chain.startMillis <= le && chain.endMillis >= ls) {
            return Span(minOf(ls, chain.startMillis), chain.endMillis)
        }
        return chain
    }

    /** The one of the sorted, disjoint [chains] containing [t] (half-open), by bisection. */
    private fun chainAt(chains: List<Span>, t: Long): Span? {
        var lo = 0
        var hi = chains.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = chains[mid]
            when {
                t < c.startMillis -> hi = mid - 1
                t >= c.endMillis -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    // ---- Moving the line ------------------------------------------------------------------------------------------

    /**
     * **The line moves to [toMillis] with the mode held**, applying every transition armed on the way, in order.
     * [events] receives what happened. A [toMillis] behind the line changes nothing.
     */
    fun advance(
        state: State,
        toMillis: Long,
        chains: List<Span>,
        specs: List<Spec>,
        events: MutableList<Event>? = null,
        policy: Policy = Policy.HOLD,
    ): State {
        var s = state
        while (true) {
            s = fire(s, chains, specs, events, policy)
            val next = nextEventMillis(s, chains, specs, policy) ?: break
            if (next > toMillis) break
            // Everything due at the line was applied by [fire]: a trigger that does not move the line is degenerate.
            if (next <= s.atMillis) break
            s = s.copy(atMillis = next)
        }
        return if (toMillis > s.atMillis) s.copy(atMillis = toMillis) else s
    }

    /** Everything due at the line's own instant, applied until nothing more is. */
    private fun fire(
        state: State,
        chains: List<Span>,
        specs: List<Spec>,
        events: MutableList<Event>?,
        policy: Policy,
    ): State {
        var s = state
        val x = s.atMillis
        val byLabel = specs.associateBy { it.label }
        var steps = 0
        while (++steps < MAX_STEPS) {
            val active = s.active
            // Due now, longest first: on a tie the chain rule keeps the long one.
            val due =
                specs.mapNotNull { spec -> dueOf(s, spec.label, chains)?.let { spec to it } }
                    .sortedWith(compareBy<Pair<Spec, Long>> { it.second }.thenByDescending { it.first.durationMillis })
            // 1. The chain rule: a break due inside (or touching the end of) the one the line is in joins it.
            if (active != null) {
                val joining = due.firstOrNull { it.second <= x && x <= active.endMillis }
                if (joining != null) {
                    s = joinActive(s, joining.first, chains, events)
                    continue
                }
                if (active.endMillis <= x) {
                    s = endActive(s, byLabel, chains, events)
                    continue
                }
                break
            }
            // 2. A drag: a break coming due within its reach joins it (the longer one teleports onto the line).
            val drag = s.drag
            if (drag != null) {
                val reach = byLabel[drag.label]?.durationMillis ?: 0L
                val joining = due.firstOrNull { it.second - reach <= x } ?: break
                s = joinDrag(s, joining.first, byLabel, events, policy)
                continue
            }
            // 3. A break falls due: dragged or entered, as the mode says.
            val first = due.firstOrNull { it.second <= x } ?: break
            s = fallDue(s, first.first, chains, events, policy)
        }
        return s
    }

    private fun drags(label: String, state: State, policy: Policy): Boolean =
        if (policy == Policy.TAKE_POSES && label != LABEL_20S) false
        else DynamicPeriods.dragsAt(label, effectiveMode(state))

    private fun fallDue(state: State, spec: Spec, chains: List<Span>, events: MutableList<Event>?, policy: Policy): State {
        val x = state.atMillis
        if (drags(spec.label, state, policy)) {
            if (spec.label != LABEL_20S) events?.add(Event.Owed(x, spec.label))
            return state.copy(drag = Drag(spec.label, x, listOf(spec.label)))
        }
        return enter(state, spec, x, listOf(spec.label), chains, events, conducted = false, policy = policy)
    }

    /** The line enters a break at its own instant: banked, and the line is covered while it lasts. */
    private fun enter(
        state: State,
        spec: Spec,
        start: Long,
        members: List<String>,
        chains: List<Span>,
        events: MutableList<Event>?,
        conducted: Boolean,
        policy: Policy,
    ): State {
        val atScreen = state.baseMode == MODE_AT_SCREEN
        val held = atScreen && (spec.label == LABEL_20S || conducted || policy == Policy.TAKE_POSES)
        val active = Active(spec.label, start, start + spec.durationMillis, members, held = held, conducted = conducted)
        // A line at a screen entering a break is covered from its start: the stretch starts here.
        val stretch = state.stretchStart ?: start
        var s = state.copy(active = active, drag = null, stretchStart = stretch)
        val key = periodAt(s, s.atMillis, chains)?.startMillis ?: stretch
        s = s.copy(taken = s.taken + members.associateWith { key })
        events?.add(Event.Started(spec.label, active.startMillis, active.endMillis, conducted))
        return s
    }

    private fun joinActive(state: State, spec: Spec, chains: List<Span>, events: MutableList<Event>?): State {
        val a = state.active ?: return state
        val key = periodAt(state, state.atMillis, chains)?.startMillis ?: a.startMillis
        val grows = spec.durationMillis > a.endMillis - a.startMillis
        val next =
            if (grows) a.copy(label = spec.label, endMillis = a.startMillis + spec.durationMillis, members = a.members + spec.label)
            else a.copy(members = a.members + spec.label)
        if (grows) events?.add(Event.Grew(state.atMillis, spec.label, a.startMillis, next.endMillis, a.label, a.endMillis))
        return state.copy(active = next, taken = state.taken + (spec.label to key))
    }

    private fun joinDrag(
        state: State,
        spec: Spec,
        byLabel: Map<String, Spec>,
        events: MutableList<Event>?,
        policy: Policy,
    ): State {
        val d = state.drag ?: return state
        val members = d.members + spec.label
        val longer = spec.durationMillis > (byLabel[d.label]?.durationMillis ?: 0L)
        if (!longer) return state.copy(drag = d.copy(members = members))
        // The longer break teleports onto the line. Where the line may be inside it (a pose joining a 20 s break mode 2
        // drags), the chain starts at the line and the line is in it; otherwise it is dragged in its turn.
        if (drags(spec.label, state, policy)) {
            if (spec.label != LABEL_20S) events?.add(Event.Owed(state.atMillis, spec.label))
            return state.copy(drag = Drag(spec.label, state.atMillis, members))
        }
        return enter(state.copy(drag = null), spec, state.atMillis, members, emptyList(), events, conducted = false, policy = policy)
    }

    /** The break the line was in ends: its labels' own bars — and, where a 20 s break was of it, the 20 s bar — are set from its end. */
    private fun endActive(
        state: State,
        byLabel: Map<String, Spec>,
        chains: List<Span>,
        events: MutableList<Event>?,
    ): State {
        val a = state.active ?: return state
        events?.add(Event.Ended(a.label, a.startMillis, a.endMillis, a.conducted))
        val bars = state.bars.toMutableMap()
        for (m in a.members) {
            val spec = byLabel[m] ?: continue
            bars[m] = maxOf(bars[m] ?: Long.MIN_VALUE, a.endMillis + spec.cadenceMillis)
        }
        if (LABEL_20S in a.members && LABEL_20S in bars) {
            bars[LABEL_20S] = maxOf(bars.getValue(LABEL_20S), a.endMillis + DynamicPeriods.BAR_20S_AFTER_20S_MILLIS)
        }
        var s = state.copy(active = null, bars = bars)
        if (effectiveMode(s) == MODE_AT_SCREEN) s = endStretch(s, chains)
        return s
    }

    /**
     * The line's stretch of "no screen" ends at the line: the requirements' stretch bars, over the stretch the line was
     * covered for. A known no-screen period around it is not grown in: at a screen the line retracts every one it
     * meets, so being inside one says nothing about a rest; a pause observed behind the line reaches the bars through
     * [absorbHistory].
     */
    @Suppress("UNUSED_PARAMETER")
    private fun endStretch(state: State, chains: List<Span>): State {
        val ss = state.stretchStart ?: return state
        return state.copy(
            bars = stretchBars(state.bars, ss, state.atMillis),
            stretchStart = null,
            lastStretchStart = ss,
            lastStretchEnd = state.atMillis,
        )
    }

    /** The requirements' two stretch bars for a stretch of "no screen" `[a, b)`. */
    fun stretchBars(bars: Map<String, Long>, a: Long, b: Long): Map<String, Long> {
        val length = b - a
        if (length < DynamicPeriods.STRETCH_SHORT_MILLIS) return bars
        val out = bars.toMutableMap()
        out[DynamicPeriods.LABEL_5MIN]?.let { out[DynamicPeriods.LABEL_5MIN] = maxOf(it, b + DynamicPeriods.BAR_5MIN_AFTER_STRETCH_MILLIS) }
        if (length >= DynamicPeriods.STRETCH_LONG_MILLIS) {
            out[DynamicPeriods.LABEL_15MIN]?.let {
                out[DynamicPeriods.LABEL_15MIN] = maxOf(it, b + DynamicPeriods.BAR_15MIN_AFTER_LONG_MILLIS)
            }
        }
        return out
    }

    /**
     * **The mode changes at the line** (§ *$now line$ 3 modes*, a trigger of its own). Everything due before the change
     * must already have been applied ([advance] to the instant first).
     */
    fun switchMode(
        state: State,
        newBaseMode: Int,
        chains: List<Span>,
        specs: List<Spec>,
        events: MutableList<Event>? = null,
        policy: Policy = Policy.HOLD,
    ): State {
        if (newBaseMode == state.baseMode) return state
        val byLabel = specs.associateBy { it.label }
        val before = effectiveMode(state)
        var s = state.copy(baseMode = newBaseMode)
        val after = effectiveMode(s)
        val x = s.atMillis
        if (after == MODE_AT_SCREEN && before != MODE_AT_SCREEN) {
            // A pose the line is inside may not cover a line at a screen: removed, and owed again (the requirements'
            // exception to the frozen past).
            val a = s.active
            if (a != null && policy == Policy.HOLD) {
                events?.add(Event.Removed(x, a.label, a.startMillis, a.endMillis))
                s = s.copy(active = null, drag = Drag(a.label, a.startMillis, a.members))
            }
            // The stretch ends here — unless the line walks straight into the 20 s break mode 2 was dragging, which it
            // now enters: then it never left "no screen", and the stretch goes on to that break's end. What the stretch
            // already bars is barred from here all the same: a pose it took may not fall due again inside the twenty
            // seconds and grow them into a pose that holds the line (a wake landed in a fifteen-minute hold once
            // nothing barred the 20 s break after a long stretch — requirements 2026-10-05).
            val ended = endStretch(s, chains)
            val drag = stillOwed(ended, chains, byLabel)
            s =
                if (drag != null && drag.label == LABEL_20S) {
                    enter(s.copy(drag = null, bars = ended.bars), byLabel.getValue(LABEL_20S), x, drag.members, chains, events, false, policy)
                } else {
                    ended.copy(drag = drag)
                }
        } else if (after != MODE_AT_SCREEN && before == MODE_AT_SCREEN) {
            s = s.copy(stretchStart = s.stretchStart ?: x)
            s = enterDragIfCovered(s, byLabel, chains, events, policy)
        } else {
            // Between the two covered modes: a 20 s break mode 2 was dragging is entered in mode 3.
            s = enterDragIfCovered(s, byLabel, chains, events, policy)
        }
        return fire(s, chains, specs, events, policy)
    }

    private fun enterDragIfCovered(
        state: State,
        byLabel: Map<String, Spec>,
        chains: List<Span>,
        events: MutableList<Event>?,
        policy: Policy,
    ): State {
        val d = state.drag ?: return state
        if (drags(d.label, state, policy)) return state
        val spec = byLabel[d.label] ?: return state.copy(drag = null)
        return enter(state.copy(drag = null), spec, state.atMillis, d.members, chains, events, conducted = false, policy = policy)
    }

    /**
     * PRD §15 **"Look away now"**: the app conducts a 20 s break from the line. It joins the break the line is in, if
     * any (the chain rule); otherwise the line enters it, held in mode 3 at a screen. It is never banked — it is
     * recorded as the conducted panel once it completes.
     */
    fun conduct(
        state: State,
        chains: List<Span>,
        specs: List<Spec>,
        events: MutableList<Event>? = null,
    ): State {
        val spec = specs.firstOrNull { it.label == LABEL_20S } ?: return state
        if (state.active != null) return joinActive(state, spec, chains, events)
        val members = listOf(LABEL_20S) + state.drag?.members.orEmpty().filter { it == LABEL_20S }
        val keptDrag = state.drag?.takeIf { it.label != LABEL_20S }
        val entered = enter(state.copy(drag = null), spec, state.atMillis, members.distinct(), chains, events, true, Policy.HOLD)
        return entered.copy(drag = keptDrag)
    }

    /**
     * **History was rewritten behind the line** (evidence of a pause arriving late, a record loaded at start-up): the
     * bars the requirements set after what happened are raised to cover it. Bars only ever rise — a stretch or a break
     * learned of late cannot un-happen one already taken. A drag whose labels are no longer due is dropped.
     *
     * [banked] is what the line banked; [dynamic] the breaks the app conducted; [stretches] the stretches of "no screen"
     * observed behind the line. Only what ended at or before the line counts: the line's own stretch is the state's.
     */
    fun absorbHistory(
        state: State,
        /** The no-screen periods ([chainsOf]): the rules the drag is re-derived under ([stillOwed]). */
        chains: List<Span>,
        specs: List<Spec>,
        banked: List<BankedBreak> = emptyList(),
        dynamic: List<Span> = emptyList(),
        stretches: List<Span> = emptyList(),
    ): State {
        val x = state.atMillis
        val byLabel = specs.associateBy { it.label }
        var bars = state.bars.toMutableMap()
        // [label] null: a look-away the app conducted ([dynamic]) — a 20 s break, the only kind that bars the next one.
        fun afterBreak(label: String?, end: Long) {
            val cadence = label?.let { byLabel[it]?.cadenceMillis }
            if (label != null && cadence != null) bars[label] = maxOf(bars[label] ?: Long.MIN_VALUE, end + cadence)
            if ((label == null || label == LABEL_20S) && LABEL_20S in bars) {
                bars[LABEL_20S] = maxOf(bars.getValue(LABEL_20S), end + DynamicPeriods.BAR_20S_AFTER_20S_MILLIS)
            }
        }
        for (b in banked) if (b.endMillis <= x && b.endMillis > b.startMillis) afterBreak(b.label, b.endMillis)
        for (d in dynamic) if (d.endMillis <= x) afterBreak(null, d.endMillis)
        val rests = chainsOfSpans(stretches + banked.map { Span(it.startMillis, it.endMillis) } + dynamic)
        for (r in rests) {
            if (r.endMillis <= x) {
                bars = stretchBars(bars, r.startMillis, r.endMillis).toMutableMap()
            } else if (r.startMillis < x) {
                // The rest the line is still in — a stretch of "no screen" running into the 20 s break the line entered
                // where it came back: what it already bars is barred from the line (as [switchMode] has it), or the
                // poses it took fall due again inside those twenty seconds and grow them into a pose that holds the line.
                bars = stretchBars(bars, r.startMillis, x).toMutableMap()
            }
        }
        val kept = stillOwed(state.copy(bars = bars), chains, byLabel)
        // What is left of it is dragged only if the mode drags it; otherwise its labels simply fall due at the line.
        val drag = kept?.takeIf { drags(it.label, state, Policy.HOLD) }
        return state.copy(bars = bars, drag = drag)
    }

    /**
     * `docs/scheduler_requirements.md` § *Use of the set of rules output*: **a re-run of the scheduler drops what was
     * deduced but not saved in history.** The bars are deduced — each is what the rules set after a break or a stretch
     * of "no screen" — so they are rebuilt from what history holds: [banked] (the breaks the line banked), [dynamic]
     * (the breaks the app conducted) and [stretches] (the "no screen" observed behind the line). A bar the carried
     * machine raised on something history does not hold goes (account 3, 2026-09-30: a restart's catch-up counted a
     * stretch at an unlocked computer as "no screen", and the 5-min break was barred for the hour after it).
     *
     * [absorbHistory] only raises; this one also LOWERS. A label history says nothing about keeps its carried bar —
     * that one is the rested start at the line ([initial]), which a rebuild at every re-run would push one cadence on
     * each time. The drag is kept, with the instant it fell due, while its labels are still due: the break the line
     * drags is the same break, announced once. The break the line is in, and its stretch, are the line's own.
     */
    fun rebuildFromHistory(
        state: State,
        /** The no-screen periods ([chainsOf]): the rules the drag is re-derived under ([stillOwed]). */
        chains: List<Span>,
        specs: List<Spec>,
        banked: List<BankedBreak>,
        dynamic: List<Span> = emptyList(),
        stretches: List<Span> = emptyList(),
    ): State {
        val byLabel = specs.associateBy { it.label }
        val blank = state.copy(bars = state.bars.mapValues { Long.MIN_VALUE }, drag = null)
        val history = absorbHistory(blank, chains, specs, banked, dynamic, stretches).bars
        val bars = state.bars.mapValues { (label, carried) -> history[label]?.takeIf { it != Long.MIN_VALUE } ?: carried }
        val drag = stillOwed(state.copy(bars = bars), chains, byLabel)?.takeIf { drags(it.label, state, Policy.HOLD) }
        return state.copy(bars = bars, drag = drag)
    }

    /**
     * [state]'s drag after its bars moved: its members as the rules give them at the line, over the restrictive periods
     * ([chains]) — by the same two questions [fire] asks, never by a bar of its own. A member stays when it falls due at
     * the line ([dueOf], the pull included: a bar inside a no-screen period ahead is due at the line), or when its due
     * comes within the reach of the members kept (the chain rule's join); the longest of them leads.
     *
     * Reading "bar ≤ line" instead dropped a member the pull had brought in at every re-run, and the next step pulled it
     * back in, a new due each time (account 3, 2026-09-30: the 15-min bar at 23:00 inside "the hour before bed" was
     * re-announced at every progressive stage). Reading the periods also drops such a member once no period pulls it any
     * more: its bar governs again.
     */
    private fun stillOwed(state: State, chains: List<Span>, byLabel: Map<String, Spec>): Drag? {
        val d = state.drag ?: return null
        val x = state.atMillis
        val free = state.copy(drag = null)
        val dues = d.members.associateWith { dueOf(free, it, chains) }
        val owed = d.members.filterTo(LinkedHashSet()) { m -> dues[m]?.let { it <= x } == true }
        if (owed.isEmpty()) return null
        while (true) {
            val reach = owed.maxOf { byLabel[it]?.durationMillis ?: 0L }
            val joining = d.members.filter { m -> m !in owed && dues[m]?.let { it - reach <= x } == true }
            if (joining.isEmpty()) break
            owed += joining
        }
        val members = d.members.filter { it in owed }
        return d.copy(label = members.maxBy { byLabel[it]?.durationMillis ?: 0L }, members = members)
    }

    private fun chainsOfSpans(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans.filter { it.endMillis > it.startMillis }.sortedBy { it.startMillis }) {
            val prev = out.lastOrNull()
            if (prev != null && s.startMillis <= prev.endMillis) {
                out[out.size - 1] = Span(prev.startMillis, maxOf(prev.endMillis, s.endMillis))
            } else {
                out += s
            }
        }
        return out
    }

    /**
     * The configuration moved (a break added, removed or retimed): labels that are gone leave the state, new ones
     * start rested from the line.
     */
    fun withSpecs(state: State, specs: List<Spec>): State {
        val labels = specs.map { it.label }.toSet()
        if (labels == state.bars.keys && state.active?.let { it.label in labels } != false &&
            state.drag?.let { it.label in labels } != false
        ) {
            return state
        }
        val bars = state.bars.filterKeys { it in labels } + specs.filter { it.label !in state.bars }
            .associate { it.label to state.atMillis + it.cadenceMillis }
        val active = state.active?.let { a ->
            val members = a.members.filter { it in labels }
            if (a.label in labels) a.copy(members = members) else null
        }
        val drag = state.drag?.let { d ->
            val members = d.members.filter { it in labels }
            if (d.label in labels) d.copy(members = members) else null
        }
        return state.copy(bars = bars, active = active, drag = drag, taken = state.taken.filterKeys { it in labels })
    }

    // ---- Where they fall ahead of the line -------------------------------------------------------------------------

    /** One break the machine places: `[startMillis, endMillis)`, or `]start; start + d]` when [openStart] (dragged). */
    data class Placed(val label: String, val startMillis: Long, val endMillis: Long, val openStart: Boolean = false) {
        val coveredFromMillis: Long get() = if (openStart) startMillis + 1 else startMillis
        val coveredUntilMillis: Long get() = if (openStart) endMillis + 1 else endMillis
    }

    /**
     * The breaks as they fall ahead of the line, up to [untilMillis]: the break the line is in (whole), the one it
     * drags (`]x; x + d]`, [Placed.openStart]), then every one the machine places as the line goes on.
     *
     * - [Policy.HOLD] with [modeAhead] null: **the line goes on in the mode it is in** — the rules the plan is made for.
     *   A pose falling due at a mode-1 line is dragged from then on and happens nowhere; a dragged break is not
     *   returned beyond the one at the line.
     * - [modeAhead]: the mode the line is EXPECTED to be in at an instant ahead ([expectedModeAt] — at a screen, away
     *   in every known no-screen period), switched at its boundaries ([modeBoundaries]).
     */
    fun predict(
        state: State,
        untilMillis: Long,
        chains: List<Span>,
        specs: List<Spec>,
        policy: Policy = Policy.HOLD,
        modeAhead: ((Long) -> Int)? = null,
        modeBoundaries: List<Long> = emptyList(),
    ): List<Placed> {
        val byLabel = specs.associateBy { it.label }
        val out = LinkedHashMap<Pair<Long, Boolean>, Placed>()
        state.active?.let { out[it.startMillis to false] = Placed(it.label, it.startMillis, it.endMillis) }
        var s = state
        // A break the line drags at its own instant is drawn where the line puts it.
        s.drag?.let { d ->
            val len = byLabel[d.label]?.durationMillis ?: 0L
            out[s.atMillis to true] = Placed(d.label, s.atMillis, s.atMillis + len, openStart = true)
            if (policy == Policy.TAKE_POSES && d.label != LABEL_20S) {
                // A line that takes the pose it owes takes it now: the rest of the prediction follows from there.
                s = s.copy(drag = null, active = Active(d.label, s.atMillis + 1, s.atMillis + 1 + len, d.members, held = true))
                s = s.copy(stretchStart = s.stretchStart ?: s.atMillis)
                // Taking it IS its no-screen period's occurrence of each member ([enter]'s own bookkeeping): without
                // it, a line inside a known period (the hour before bed) had the next one pulled onto this one's end,
                // and two 15-min breaks were drawn back to back right after the line (anomaly 2026-10-02).
                val key = periodAt(s, s.atMillis, chains)?.startMillis ?: s.stretchStart ?: s.atMillis
                s = s.copy(taken = s.taken + d.members.associateWith { key })
            }
        }
        val events = ArrayList<Event>()
        fun collect() {
            for (e in events) {
                when (e) {
                    is Event.Started -> if (!e.conducted) out[e.startMillis to false] = Placed(e.label, e.startMillis, e.endMillis)
                    is Event.Grew -> out[e.startMillis to false] = Placed(e.label, e.startMillis, e.endMillis)
                    is Event.Removed -> out.remove(e.startMillis to false)
                    else -> Unit
                }
            }
            events.clear()
        }
        if (modeAhead == null) {
            advance(s, untilMillis, chains, specs, events, policy)
            collect()
        } else {
            // Past the break the line is in, the expected mode takes over.
            val handover = maxOf(s.atMillis, s.active?.endMillis ?: s.atMillis)
            val cuts = (listOf(handover) + modeBoundaries.filter { it > handover && it < untilMillis }).distinct().sorted()
            for (cut in cuts) {
                s = advance(s, cut, chains, specs, events, policy)
                s = switchMode(s, modeAhead(cut), chains, specs, events, policy)
                collect()
            }
            advance(s, untilMillis, chains, specs, events, policy)
            collect()
        }
        return out.values.filter { it.coveredUntilMillis > state.atMillis && it.startMillis < untilMillis }.sortedBy { it.startMillis }
    }

    /**
     * The mode a line is expected to be in ahead: away (mode 3, taking the breaks it meets) inside every known
     * no-screen period, at a screen elsewhere.
     */
    fun expectedModeAt(chains: List<Span>): (Long) -> Int = { t -> if (chainAt(chains, t) != null) MODE_ON_BREAK else MODE_AT_SCREEN }

    /** Where [expectedModeAt] changes: every edge of the known no-screen periods. */
    fun expectedModeBoundaries(chains: List<Span>): List<Long> = chains.flatMap { listOf(it.startMillis, it.endMillis) }

    /**
     * [predict] as the calendar and the server read it. A line AWAY goes on away — the rules for the mode it is in, the
     * same breaks the plan is built around. A line at a screen takes each break where it falls, and is away in every
     * known no-screen period.
     */
    fun predictExpected(state: State, untilMillis: Long, chains: List<Span>, specs: List<Spec>): List<Placed> =
        if (state.baseMode != MODE_AT_SCREEN) predict(state, untilMillis, chains, specs, Policy.TAKE_POSES)
        else predict(state, untilMillis, chains, specs, Policy.TAKE_POSES, expectedModeAt(chains), expectedModeBoundaries(chains))
}
