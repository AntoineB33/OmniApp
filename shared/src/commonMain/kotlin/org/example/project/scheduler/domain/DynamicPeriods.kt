package org.example.project.scheduler.domain

/**
 * `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period* — the whole of where the **20 s**, the
 * **5 min** and the **15 min** periods go, ported from `side-dev/scheduler.py`'s `DynamicPlanner`.
 *
 * ### What changed, and why the three are not what they were
 * They used to be three differently-shaped objects: the look-away accepted nobody, the 5-minute pose had a
 * closed first minute and then accepted the "doable during a screen break" tasks, and the 15-minute pose
 * accepted every off-screen task. The README allows none of that — **all three have the kind
 * [PeriodKinds.NO_TASK]**, end to end — and their *placement* is no longer a cadence off a "last rest"
 * anchor but the three recurrence bars below. So there is no shape to read any more, and no
 * `doableDuringBreak` switch for a shape to read: whether a task may run somewhere is a resilience, and
 * inside a period that allows no task there is nothing to be resilient to.
 *
 * ### The three bars, verbatim
 * - after **any** dynamic period, no 20 s period in the next **20 minutes** ([BAR_20S_AFTER_ANY_MILLIS]);
 * - after any **>= 5-minute** stretch covered by "no on-screen task" *without any task*, no 5 min period in
 *   the next **1 hour** ([BAR_5MIN_AFTER_STRETCH_MILLIS]);
 * - after a **>= 15-minute** such stretch, no 20 s period in the next **20 minutes** and no 15 min period in
 *   the next **2 hours** ([BAR_15MIN_AFTER_LONG_MILLIS]).
 *
 * A **rest stretch** is that phrase read literally, and it takes all three of its clauses ([isRestAt]):
 * *covered by* "no on-screen task" — a period of that kind, or [PeriodKinds.NO_TASK], which turns the
 * on-screen tasks away a fortiori; *without any task* — so a period that still accepts somebody makes none
 * at all (the no-idling rule puts a task there), and neither does a pre-placed block, since a pre-placed task
 * IS a task; and it is a **stretch**, not a period — two that abut make one ([growStretch]).
 *
 * `blocked` and `rested` are deliberately two different sets. Everywhere nothing can be placed a dynamic
 * period is pointless and is pushed past; only the part of that which is a rest stretch bars what comes after
 * it. A pre-placed hour of maintenance is not a rest: the user was at the screen the whole time.
 *
 * The timeline is taken to **start rested**, so the first 20 s may fall one bar after `t_pstart`, the first
 * 5 min an hour after it and the first 15 min two hours after it. Placing all three at the origin instead
 * would be legal and useless — the chain merge would collapse them into one 15-minute period there.
 */
object DynamicPeriods {

    /** After ANY dynamic period, no 20 s period for 20 minutes. */
    const val BAR_20S_AFTER_ANY_MILLIS: Long = 20L * 60_000L

    /** After a >= 5-minute rest stretch, no 5 min period for an hour. */
    const val BAR_5MIN_AFTER_STRETCH_MILLIS: Long = 60L * 60_000L

    /** After a >= 15-minute rest stretch, no 20 s period for 20 minutes. */
    const val BAR_20S_AFTER_LONG_MILLIS: Long = 20L * 60_000L

    /** After a >= 15-minute rest stretch, no 15 min period for two hours. */
    const val BAR_15MIN_AFTER_LONG_MILLIS: Long = 2L * 60L * 60_000L

    /** The length at which a rest stretch starts barring the 5-minute period. */
    const val STRETCH_SHORT_MILLIS: Long = 5L * 60_000L

    /** The length at which a rest stretch starts barring the 20 s and the 15-minute periods. */
    const val STRETCH_LONG_MILLIS: Long = 15L * 60_000L

    /** The stable label of the 20-second period. */
    const val LABEL_20S: String = "20s"

    /** The stable label of the 5-minute period. */
    const val LABEL_5MIN: String = "5min"

    /** The stable label of the 15-minute period. */
    const val LABEL_15MIN: String = "15min"

    /** The label mode 2's cover carries, so the calendar can name the band the user is standing in. */
    const val LABEL_AWAY: String = "away"

    /** Runaway guard — the placement loop is monotone, so this can only fire on a degenerate environment. */
    private const val MAX_STEPS = 200_000

    /**
     * What an account with no schedulable task is answered with: the resilience a freshly created task
     * carries (on screen). Kept here rather than read off `Task` so the placement stays a pure function of
     * the plan layer's own types.
     */
    private val DEFAULT_TASK_RESILIENCE: Map<String, Double> = mapOf(PeriodKinds.NO_SCREEN to 0.0)

    /**
     * `t_p` mode 1: *"$t_p$ must not be covered by the period 'no on-screen task'"* — the user is at the
     * screen, so a period the line reaches is pushed ahead of it and never happens at all.
     */
    const val MODE_AT_SCREEN: Int = 1

    /**
     * `t_p` mode 2: *"$t_p$ must be covered by the period 'no on-screen task'"* — the user is away, so the
     * gap between the last period's end and the line is covered by a "no on-screen task" period, filled with
     * whatever tasks are resilient to that kind (and left empty if none are).
     */
    const val MODE_AWAY: Int = 2

    /** One of the three: a label, how long it lasts, and its own recurrence bar. */
    data class Spec(val label: String, val durationMillis: Long, val cadenceMillis: Long)

    /** One placed occurrence of a [Spec]. */
    data class Instance(val spec: Spec, val startMillis: Long, val openStart: Boolean = false) {
        val endMillis: Long get() = startMillis + spec.durationMillis
        val durationMillis: Long get() = spec.durationMillis

        /**
         * The README's period: one span of [PeriodKinds.NO_TASK]. [openStart] carries the half-open
         * `(t_p, t_p + duration]` of a period the line is dragging — the instant `t_p` itself must NOT be
         * covered while every instant after it is.
         */
        fun toPeriod(): RestrictivePeriod =
            RestrictivePeriod(
                startMillis = startMillis,
                endMillis = endMillis,
                kind = PeriodKinds.NO_TASK,
                label = spec.label,
                openStart = openStart,
                closedEnd = openStart,
            )
    }

    /** A half-open `[startMillis, endMillis)` span of the timeline. */
    data class Span(val startMillis: Long, val endMillis: Long) {
        val durationMillis: Long get() = endMillis - startMillis
    }

    /** The environment the bars are read against: the standing periods and the pre-placed blocks. */
    class Base(
        val periods: List<RestrictivePeriod>,
        val blocks: List<PlanBlock>,
        val tasks: List<PlanTask>,
    ) {
        /** Every instant the environment can change at, sorted — the reference's `Environment.bounds`. */
        val bounds: List<Long> =
            buildSet {
                for (p in periods) {
                    add(p.startMillis)
                    add(p.endMillis)
                }
                for (b in blocks) {
                    add(b.startMillis)
                    add(b.endMillis)
                }
            }.sorted()

        fun blockAt(millis: Long): PlanBlock? =
            blocks.firstOrNull { it.startMillis <= millis && millis < it.endMillis }

        fun kindsAt(millis: Long): Set<String> =
            periods.filter { it.covers(millis) }.mapTo(HashSet()) { it.kind }

        /**
         * `Environment.no_screen_at`: is the instant COVERED BY "no on-screen task"? Asked at an exact
         * instant rather than at a midpoint, because it is the question the half-open dragged 20 seconds
         * exists to answer.
         */
        fun noScreenAt(millis: Long): Boolean =
            periods.any { it.covers(millis) && PeriodKinds.coversNoScreen(it.kind) }

        /**
         * `Environment.weights`, restricted to the question the bars ask: may ANYBODY run at [millis]?
         *
         * An account with **no schedulable task at all** answers *yes* everywhere, deliberately. The rule
         * this feeds — "any emptiness absorbs a dynamic period, there is nothing for a break to interrupt
         * where nothing is placed" — is about a stretch the *rules* refuse, not about a tree that happens to
         * be empty. Read the other way an empty account would read as blocked end to end and be given no
         * breaks at all, when a break is exactly what it should still get.
         */
        fun anybodyAt(millis: Long): Boolean {
            val kindsHere = kindsAt(millis)
            // An account with **no schedulable task at all** is answered with ONE hypothetical default task
            // ([Task.DEFAULT_RESILIENCE]) rather than with "nobody". The rule this feeds is about a stretch
            // the RULES refuse, not about a tree that happens to be empty: read as nobody, an empty account
            // would be blocked end to end and given no breaks at all, when a break is exactly what it should
            // still get. Read this way, an empty account's breaks are placed, and a period of "no task
            // allowed" still refuses — which is what it means.
            if (tasks.isEmpty()) {
                return blockAt(millis) == null &&
                    PeriodKinds.multiplier(DEFAULT_TASK_RESILIENCE, kindsHere) > 0.0
            }
            val block = blockAt(millis)
            for (task in tasks) {
                if (block != null && block.taskId != task.id) continue
                if (PeriodKinds.multiplier(task.resilience, kindsHere) > 0.0) return true
            }
            return false
        }

        /** `[lo, hi)` cut at every edge of the environment. */
        fun segments(lo: Long, hi: Long): List<Span> {
            val out = mutableListOf<Span>()
            var cur = lo
            while (cur < hi) {
                val next = bounds.firstOrNull { it > cur }?.coerceAtMost(hi) ?: hi
                val stop = if (next <= cur) hi else next
                out += Span(cur, stop)
                cur = stop
            }
            return out
        }
    }

    /**
     * Where the three periods fall, for this position of the line.
     *
     * [sweepFromMillis] is where the line's continuous motion began. It matters because mode 1 **drags**: a
     * period the line has reached is pushed ahead of it and goes on being pushed, so it never happens at all.
     * A period the line never stood inside is untouched, which is why a jump sweeps nothing.
     */
    fun instances(
        base: Base,
        dynamics: List<Spec>,
        startMillis: Long,
        horizonMillis: Long,
        tpMillis: Long,
        mode: Int = MODE_AT_SCREEN,
        sweepFromMillis: Long = startMillis,
    ): List<Instance> {
        if (dynamics.isEmpty() || horizonMillis <= startMillis) return emptyList()
        val blocked = mutableListOf<Span>()
        val rested = mutableListOf<Span>()
        for (seg in base.segments(startMillis, horizonMillis)) {
            val mid = seg.startMillis + (seg.endMillis - seg.startMillis) / 2
            if (base.anybodyAt(mid)) continue
            blocked += seg
            if (isRestAt(base, mid)) rested += seg
        }
        val blockedSpans = mergeSpans(blocked)
        val restedSpans = mergeSpans(rested)

        val byLabel = dynamics.associateBy { it.label }
        val labels = dynamics.map { it.label }
        val bars = HashMap<String, Long>()
        for (spec in dynamics) bars[spec.label] = startMillis + spec.cadenceMillis
        val out = mutableListOf<Instance>()
        var steps = 0
        while (true) {
            if (++steps > MAX_STEPS) break
            // The earliest bar goes first; on a tie the LONGEST period does, so that the chain merge which
            // follows keeps the long one rather than an equally-placed short one.
            val label =
                labels.minWithOrNull(
                    compareBy<String> { bars[it] ?: Long.MAX_VALUE }
                        .thenByDescending { byLabel[it]?.durationMillis ?: 0L },
                ) ?: break
            val spec = byLabel[label] ?: break
            var start = bars[label] ?: break
            if (start >= horizonMillis) break
            // A rest stretch bars what comes AFTER it, and any emptiness at all absorbs what would fall
            // inside it — there is nothing for a break to interrupt where nothing is placed. Both are applied
            // in chronological order: a night on the third day cannot delay a break on the first.
            var moved = false
            for (span in restedSpans) {
                if (span.endMillis <= start || (span.startMillis <= start && start < span.endMillis)) {
                    val before = HashMap(bars)
                    barStretch(bars, span.startMillis, span.endMillis)
                    if (bars != before) moved = true
                }
            }
            for (span in blockedSpans) {
                if (span.startMillis <= start && start < span.endMillis && (bars[label] ?: 0L) < span.endMillis) {
                    bars[label] = span.endMillis
                    moved = true
                }
            }
            if (moved) continue
            var openStart = false
            // Mode 1: `t_p` may not be covered by "no on-screen task". A period the line has swept up to is
            // therefore pushed ahead of it and becomes the half-open `(t_p, t_p + duration]` — the line goes
            // on delaying it, placing tasks where it stood.
            if (mode == MODE_AT_SCREEN && start >= sweepFromMillis && start <= tpMillis) {
                start = tpMillis
                openStart = true
            }
            val inst = Instance(spec, start, openStart)
            out += inst
            barInstance(bars, byLabel, restedSpans, inst)
        }
        return mergeChain(out)
    }

    /**
     * The periods the three make at this position of the line — [instances] rendered, plus mode 2's cover.
     *
     * Mode 2 wants `t_p` COVERED BY "no on-screen task". The period that just ended is the one the line came
     * out of, so it is extended to reach `t_p` — as [PeriodKinds.NO_SCREEN] rather than [PeriodKinds.NO_TASK],
     * which is what the README's own example asks for: *"the gap between the end of the 15min period and
     * $t_p$ is covered by a period 'no on-screen task', filled with tasks that have a non-zero resilience to
     * the kind 'no on-screen task', or no task if none have such resilience"*.
     */
    fun periods(
        base: Base,
        dynamics: List<Spec>,
        startMillis: Long,
        horizonMillis: Long,
        tpMillis: Long,
        mode: Int = MODE_AT_SCREEN,
        sweepFromMillis: Long = startMillis,
    ): List<RestrictivePeriod> {
        val placed =
            instances(base, dynamics, startMillis, horizonMillis, tpMillis, mode, sweepFromMillis)
                .map { it.toPeriod() }
        if (mode != MODE_AWAY) return placed
        val covered = (placed + base.periods).any { it.covers(tpMillis) && PeriodKinds.coversNoScreen(it.kind) }
        if (covered) return placed
        val ends =
            (placed + base.periods)
                .filter { PeriodKinds.coversNoScreen(it.kind) && it.endMillis <= tpMillis }
                .maxOfOrNull { it.endMillis }
        val from = ends ?: tpMillis
        if (from >= tpMillis) return placed
        return placed + RestrictivePeriod(from, tpMillis, PeriodKinds.NO_SCREEN, LABEL_AWAY, closedEnd = true)
    }

    /**
     * The README's stretch, at one instant: covered by "no on-screen task", and no task there — a pre-placed
     * block included, since a pre-placed task is a task.
     */
    private fun isRestAt(base: Base, millis: Long): Boolean =
        base.blockAt(millis) == null && base.noScreenAt(millis) && !base.anybodyAt(millis)

    /** Grow `[a, b)` through whatever pre-placed REST it touches — an abutting night makes one stretch. */
    private fun growStretch(rested: List<Span>, aIn: Long, bIn: Long): Span {
        var a = aIn
        var b = bIn
        var changed = true
        while (changed) {
            changed = false
            for (span in rested) {
                if (span.startMillis <= a && a <= span.endMillis && span.startMillis < a) {
                    a = span.startMillis
                    changed = true
                }
                if (span.startMillis <= b && b <= span.endMillis && span.endMillis > b) {
                    b = span.endMillis
                    changed = true
                }
            }
        }
        return Span(a, b)
    }

    private fun barStretch(bars: MutableMap<String, Long>, a: Long, b: Long) {
        val length = b - a
        if (length >= STRETCH_SHORT_MILLIS && bars.containsKey(LABEL_5MIN)) {
            bars[LABEL_5MIN] = maxOf(bars.getValue(LABEL_5MIN), b + BAR_5MIN_AFTER_STRETCH_MILLIS)
        }
        if (length >= STRETCH_LONG_MILLIS) {
            if (bars.containsKey(LABEL_20S)) {
                bars[LABEL_20S] = maxOf(bars.getValue(LABEL_20S), b + BAR_20S_AFTER_LONG_MILLIS)
            }
            if (bars.containsKey(LABEL_15MIN)) {
                bars[LABEL_15MIN] = maxOf(bars.getValue(LABEL_15MIN), b + BAR_15MIN_AFTER_LONG_MILLIS)
            }
        }
    }

    private fun barInstance(
        bars: MutableMap<String, Long>,
        byLabel: Map<String, Spec>,
        rested: List<Span>,
        inst: Instance,
    ) {
        if (bars.containsKey(LABEL_20S) && LABEL_20S in byLabel) {
            bars[LABEL_20S] = maxOf(bars.getValue(LABEL_20S), inst.endMillis + BAR_20S_AFTER_ANY_MILLIS)
        }
        bars[inst.spec.label] =
            maxOf(bars[inst.spec.label] ?: Long.MIN_VALUE, inst.endMillis + inst.spec.cadenceMillis)
        // The whole of a dynamic period counts as a rest stretch: its kind is "no task allowed", which covers
        // "no on-screen task" a fortiori and leaves nobody able to run.
        val grown = growStretch(rested, inst.startMillis, inst.endMillis)
        barStretch(bars, grown.startMillis, grown.endMillis)
    }

    /**
     * The README's chain rule: where the bars have made dynamic periods overlap (only the `t_p` drag can),
     * *"the whole chain is replaced by the longest period of the chain starting at the earliest point"*.
     *
     * Touching counts as chaining — that is the README's own example: a 20 s dragged until its end meets a
     * 5 min is absorbed, and the 5 min teleports 20 seconds backward, keeping the line outside it.
     */
    private fun mergeChain(instances: List<Instance>): List<Instance> {
        val sorted =
            instances.sortedWith(compareBy<Instance> { it.startMillis }.thenByDescending { it.durationMillis })
        val out = mutableListOf<Instance>()
        for (inst in sorted) {
            val prev = out.lastOrNull()
            if (prev != null && inst.startMillis <= prev.endMillis) {
                val longest = if (prev.durationMillis >= inst.durationMillis) prev else inst
                out[out.size - 1] = Instance(longest.spec, prev.startMillis, prev.openStart)
            } else {
                out += inst
            }
        }
        return out
    }

    private fun mergeSpans(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (span in spans.sortedBy { it.startMillis }) {
            val prev = out.lastOrNull()
            if (prev != null && span.startMillis <= prev.endMillis) {
                out[out.size - 1] = Span(prev.startMillis, maxOf(prev.endMillis, span.endMillis))
            } else {
                out += span
            }
        }
        return out
    }
}

/**
 * `side-dev/README.md` § *Restrictive Period*: **a start, an end and a kind**. The one object the whole
 * scheduler reads the timeline's restrictions through, whether the period was drawn by the user, derived
 * from a §17 sleep window, or placed by [DynamicPeriods].
 *
 * [openStart] / [closedEnd] exist for one reason: the README's dragged 20-second period is the half-open
 * `(t_p, t_p + 20s]`, so the instant `t_p` itself must NOT be covered while every instant after it is. Every
 * other period is the ordinary `[start, end)`.
 */
data class RestrictivePeriod(
    val startMillis: Long,
    val endMillis: Long,
    val kind: String,
    /** What to call this period on the calendar; blank for an unnamed one. */
    val label: String = "",
    val openStart: Boolean = false,
    val closedEnd: Boolean = false,
) {
    val durationMillis: Long get() = endMillis - startMillis

    fun covers(millis: Long): Boolean {
        val after = if (openStart) startMillis < millis else startMillis <= millis
        val before = if (closedEnd) millis <= endMillis else millis < endMillis
        return after && before
    }
}
