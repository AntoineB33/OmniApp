package org.example.project.scheduler.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import org.example.project.scheduler.model.TaskId

/**
 * PRD §9 / `side-dev/README.md`: the **cyclic proportional-share** scheduler — the Kotlin port of the reference
 * implementation in `side-dev/test.py`.
 *
 * ### The rules, verbatim from `side-dev/README.md`
 * - each task has a **minimum time** and a **priority percentage**; once a task is placed, nothing else may be
 *   placed until its minimum has elapsed;
 * - the priorities must be satisfied "as much as possible, **in a scale as small as possible**" — two 50 %
 *   tasks with a 10-min minimum alternate every 10 min, they do not alternate every hour;
 * - the timeline is infinite, so the result is not a list of slots but a **finite list of rules**: a `prefix`
 *   played once and a `cycle` repeated forever ([Plan]). Drawing `t = 0…x` unrolls that list, and any instant
 *   is looked up in `O(log rules)` ([Plan.taskAt]);
 * - the timeline is made of **periods**, each accepting a set of tasks;
 * - a task deprived of the timeline — by a **fixed block owned by someone else**, or by a **period that does
 *   not accept it** — is compensated around that deprivation, and the compensation **decays exponentially**
 *   with the distance to it, on both sides. A deprivation ten times longer buys a few times more
 *   compensation, not ten times more.
 *
 * ### The cycle
 * Built analytically on the smallest period that can exist:
 * ```
 *   T        = max(mᵢ / pᵢ)        <- below this, some task cannot get a slot at all
 *   budgetᵢ  = pᵢ · T  (≥ mᵢ)      <- its exact share, by construction
 * ```
 * Inside the period a virtual-clock greedy (Weighted Fair Queuing) interleaves the budgets at the finest
 * granularity: it always serves the most starved task (smallest `vᵢ = servedᵢ / pᵢ`), giving it just enough to
 * catch up with the runner-up, never less than its minimum, never more than its remaining budget, and never
 * leaving a remainder too small to be placed. Shares inside the cycle are therefore **exact**, not approximate
 * ([steadyCycle]). When the fine cycle would need more rules than the caller allows, it falls back to one
 * block per task ([coarseCycle]) — the same period and the same exact shares, coarsely interleaved.
 *
 * ### The prefix
 * Pre-placed blocks cannot be moved and period windows restrict which tasks are allowed, so while any of them
 * is still ahead the same greedy runs freely and its output goes to the prefix. Once the context is frozen
 * forever, the remaining imbalance is settled and the cycle is attached.
 *
 * ### The influence field (`tau`)
 * A task can be deprived of the timeline in exactly two ways, and they are the same phenomenon: a fixed block
 * owned by *another* task forbids everyone else for its whole length, and a period window forbids every task it
 * does not list. Both are reduced to one object — an **exclusion**, an interval a given task is kept out of
 * ([exclusionsOf]). Around an exclusion the schedule is deliberately distorted in favour of the deprived task,
 * and the distortion decays exponentially with the distance `d`:
 * ```
 *   a(L)       = L / tau                                  amplitude of an exclusion of length L
 *   boostₙ(t)  = 1 + min(maxBoost − 1, Σ a(L) · e^(−d/tau))
 * ```
 * Two things follow, and they are the whole point:
 * - the boost is **capped**, so near an exclusion a task dominates its neighbourhood but never owns it; the
 *   saturated zone is `tau · ln(a / maxBoost)` wide, so the total compensation grows like `tau · ln(L / tau)`
 *   — an exclusion 100× longer buys a few times more compensation, not 100× more;
 * - the influence vanishes (below [fieldFloor]) at a **finite** distance, so the rule list stays finite and the
 *   steady cycle is reached again.
 *
 * An interval that excludes *everybody* (a maintenance block owned by nobody, a window that allows nothing)
 * takes the same amount from everyone: no relative distortion, no field. An exclusion that never re-opens has
 * no "after", so it only ramps up before itself, and its amplitude is capped ([maxReachMillis]) — past a few
 * `tau`, "very long" and "forever" are indistinguishable anyway.
 *
 * Because boosted time is charged to the virtual clock **at the boosted rate** (`v += c / (p · boost)`), it is
 * not a debt and is never clawed back later: the local share really is higher near the exclusion. Symmetrically
 * an imbalance larger than one period is forgotten exponentially ([PlanWalk.relax]), so an enormous block — or a
 * very long ban — is not repaid in full; it is repaid up to the same logarithmic bound, before and after itself.
 * Below one period nothing is forgotten and no slot is capped, so an unperturbed timeline behaves exactly as a
 * plain WFQ and the cycle stays exact.
 *
 * ### Deliberate deviations from `side-dev/test.py`
 * - **`Fraction` → `Double` millis.** The reference keeps every value an exact rational so the cycle's shares
 *   are provably exact; Kotlin Multiplatform has no rational type, so the walk runs in `Double` milliseconds
 *   and slot durations are rounded to whole millis at the boundary. Over a 168-hour horizon that is ~10⁻⁷ of a
 *   millisecond of drift per operation — far below the one-minute granularity anything in the app displays.
 * - **Zero-priority tasks are kept as last-resort candidates** instead of being dropped. The reference raises
 *   when no task has a positive priority; the app must still fill the calendar for a tree whose weights are all
 *   zero, and a period may accept *only* a zero-priority task (a 0 % off-screen task inside a no-screen
 *   period). Such a task is absent from [share] and from the field, is picked only when no positive-priority
 *   task is available, and is placed for exactly its minimum.
 */
data class PlanTask(
    val id: TaskId,
    /** The task's absolute priority share (see [SchedulerDomain.absoluteTaskPriorities]); 0 is allowed. */
    val priority: Double,
    /** PRD §10 minimum time, in millis — the shortest slot the task may be placed in. */
    val minimumMillis: Long,
)

/** A pre-placed piece of timeline. [taskId] `null` = owned by nobody, so it excludes everyone equally. */
data class PlanBlock(val taskId: TaskId?, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/** A period of the timeline that accepts only [allowed]. [endMillis] `null` = FOREVER (it never re-opens). */
data class PlanWindow(val startMillis: Long, val endMillis: Long?, val allowed: Set<TaskId>)

/** One rule: run [taskId] for [durationMillis]. A `null` task is an idle hole nobody could fill. */
data class PlanSlot(val taskId: TaskId?, val durationMillis: Long)

/**
 * `side-dev/README.md`: the finite rule list — [prefix] played once from [startMillis], then [cycle] forever.
 * An empty [cycle] means the walk was capped before a stable cycle could be calculated ("truncated timelines").
 */
class Plan(
    val startMillis: Long,
    val prefix: List<PlanSlot>,
    val cycle: List<PlanSlot>,
) {
    val periodMillis: Long = cycle.sumOf { it.durationMillis }

    val cycleStartMillis: Long = startMillis + prefix.sumOf { it.durationMillis }

    /** The share each task holds in the repeating cycle — the percentages the plan converges on. */
    val shares: Map<TaskId, Double> =
        if (periodMillis <= 0L) {
            emptyMap()
        } else {
            cycle.filter { it.taskId != null }
                .groupBy { it.taskId!! }
                .mapValues { (_, slots) -> slots.sumOf { it.durationMillis }.toDouble() / periodMillis }
        }

    private val prefixOffsets: LongArray = offsetsOf(prefix)
    private val cycleOffsets: LongArray = offsetsOf(cycle)

    /** Which task occupies instant [millis]? `O(log rules)`. Null = before the plan, or an idle hole. */
    fun taskAt(millis: Long): TaskId? {
        if (millis < startMillis) return null
        if (millis < cycleStartMillis) return prefix[indexOf(prefixOffsets, millis - startMillis)].taskId
        if (periodMillis <= 0L) return null
        val k = (millis - cycleStartMillis) % periodMillis
        return cycle[indexOf(cycleOffsets, k)].taskId
    }

    /** Unroll the rules into concrete blocks covering `[startMillis, untilMillis)`. */
    fun unroll(untilMillis: Long): List<PlanBlock> {
        val out = mutableListOf<PlanBlock>()
        var t = startMillis
        fun place(slot: PlanSlot): Boolean {
            if (t >= untilMillis) return false
            val end = minOf(t + slot.durationMillis, untilMillis)
            if (end > t) out += PlanBlock(slot.taskId, t, end)
            t = end
            return true
        }
        for (slot in prefix) if (!place(slot)) return out
        if (periodMillis <= 0L) return out
        while (t < untilMillis) for (slot in cycle) if (!place(slot)) return out
        return out
    }

    private companion object {
        fun offsetsOf(slots: List<PlanSlot>): LongArray {
            val out = LongArray(slots.size + 1)
            for (i in slots.indices) out[i + 1] = out[i] + slots[i].durationMillis
            return out
        }

        /** The slot index holding [offset]: `bisect_right(offsets, offset) - 1`, clamped into the list. */
        fun indexOf(offsets: LongArray, offset: Long): Int {
            var lo = 0
            var hi = offsets.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (offsets[mid] <= offset) lo = mid + 1 else hi = mid
            }
            if (offsets.size < 2) return 0
            return (lo - 1).coerceIn(0, offsets.size - 2)
        }
    }
}

class SchedulerPlanner(
    /** Every schedulable task, in the caller's deterministic tie-break order (see [PlanWalk.pick]). */
    val tasks: List<PlanTask>,
    /**
     * How far, in time, an exclusion is felt — and how fast an abnormal imbalance is forgotten. One
     * [minPeriodMillis] by default, exactly as in `side-dev/test.py`.
     */
    tauMillis: Double? = null,
    /**
     * How much longer than usual a slot may get at the very edge of an exclusion. Caps the local dominance,
     * and is what turns the linear amplitude `L / tau` into a *logarithmic* compensation.
     */
    val maxBoost: Double = DEFAULT_MAX_BOOST,
    /** Influence below this is dropped, so the field has a finite reach and the rule list stays finite. */
    val fieldFloor: Double = DEFAULT_FIELD_FLOOR,
    /**
     * Hard bound on that reach. An exclusion that never ends would otherwise carry an infinite amplitude;
     * since the boost saturates at [maxBoost], only the width of the saturated zone would grow, and it grows
     * like `tau · ln a`. Capping it just states that past a few `tau`, "very long" and "forever" are the same.
     */
    maxReachMillis: Double? = null,
    /** Optional slot-length quantum (`ceil` to a multiple). Off by default, as in the reference. */
    val resolutionMillis: Double = 0.0,
    /** Optional hard bound on the virtual clock. [PlanWalk.relax] normally does the job on its own. */
    val maxLagMillis: Double? = null,
) {
    /** The normalized priority percentages. Zero-priority tasks are absent (see the class docs). */
    val share: Map<TaskId, Double>

    val minimumOf: Map<TaskId, Long> = tasks.associate { it.id to it.minimumMillis }

    /** `T = max(mᵢ / pᵢ)` — the smallest period able to give every task one slot ≥ its minimum. */
    val minPeriodMillis: Double

    val tauMillis: Double

    val maxReachMillis: Double

    /** The largest amplitude an exclusion may carry, i.e. the one whose reach is exactly [maxReachMillis]. */
    private val maxAmp: Double

    private var fieldSpans: Map<TaskId, List<FieldSpan>> = emptyMap()

    /**
     * The last instant any exclusion is still felt at, or null when there is no field. Past it the schedule is
     * undisturbed and the analytic cycle applies.
     */
    var fieldEndMillis: Long? = null
        private set

    init {
        val positive = tasks.filter { it.priority > 0.0 }
        // Deviation from the reference, which raises here: an all-zero tree must still fill the calendar, so
        // when nothing has a positive share every task gets an equal one.
        val weighted = if (positive.isNotEmpty()) positive else tasks
        val total = weighted.sumOf { it.priority }
        share =
            when {
                weighted.isEmpty() -> emptyMap()
                total > 0.0 -> weighted.associate { it.id to it.priority / total }
                else -> weighted.associate { it.id to 1.0 / weighted.size }
            }
        minPeriodMillis =
            (share.entries.maxOfOrNull { (id, p) -> (minimumOf[id] ?: 0L).toDouble() / p } ?: 0.0)
                .coerceAtLeast(1.0)
        this.tauMillis = (tauMillis ?: minPeriodMillis).coerceAtLeast(1.0)
        this.maxReachMillis = maxReachMillis ?: (DEFAULT_REACH_TAUS * this.tauMillis)
        maxAmp = fieldFloor * (exp(this.maxReachMillis / this.tauMillis) - 1.0)
    }

    // ----- shares and period, restricted to a set of tasks --------------------------------------

    /**
     * The priority percentages renormalized over [allowed] alone. Zero-priority tasks carry no share, so they
     * are dropped first — a set containing nothing else falls back to an equal split, which is what keeps
     * [periodOf] finite when only such tasks are allowed.
     */
    fun sharesOf(allowed: Collection<TaskId>): Map<TaskId, Double> {
        val weighted = allowed.filter { it in share }.ifEmpty { allowed.toList() }
        val total = weighted.sumOf { share[it] ?: 0.0 }
        if (total <= 0.0) return weighted.associateWith { 1.0 / weighted.size.coerceAtLeast(1) }
        return weighted.associateWith { (share[it] ?: 0.0) / total }
    }

    /** `T` for a restricted set: the smallest period giving each allowed task one slot ≥ its minimum. */
    fun periodOf(allowed: Collection<TaskId>): Double {
        if (allowed.isEmpty()) return minPeriodMillis
        val p = sharesOf(allowed)
        return p.entries.maxOf { (id, share) -> (minimumOf[id] ?: 0L).toDouble() / share }.coerceAtLeast(1.0)
    }

    // ----- the influence field ------------------------------------------------------------------

    /**
     * Every interval during which a task cannot be scheduled.
     *
     * A fixed block owned by A excludes every *other* task for its whole length; a period window excludes every
     * task it does not allow. These are the same event and are treated as one, so a long ban distorts the
     * schedule around itself exactly like a long block does. An interval that excludes *everybody* creates no
     * relative distortion and is dropped.
     */
    fun exclusionsOf(blocks: List<PlanBlock>, windows: List<PlanWindow>): Map<TaskId, List<LongRangeSpan>> {
        val everyone = share.keys
        if (everyone.isEmpty()) return emptyMap()
        val spans = HashMap<TaskId, MutableList<LongRangeSpan>>()
        fun add(start: Long, end: Long, excluded: Set<TaskId>) {
            if (excluded.size >= everyone.size) return // hits everybody equally
            for (id in excluded) spans.getOrPut(id) { mutableListOf() } += LongRangeSpan(start, end)
        }
        for (b in blocks) {
            if (b.endMillis > b.startMillis) add(b.startMillis, b.endMillis, everyone - setOfNotNull(b.taskId))
        }
        for (w in windows) {
            val end = w.endMillis ?: FOREVER
            if (end > w.startMillis) add(w.startMillis, end, everyone - w.allowed)
        }
        return spans.mapValues { (_, s) -> mergeSpans(s) }
    }

    /**
     * Turn the exclusions into the field: for each task, the intervals it is kept out of and the amplitude
     * `a = L / tau` of the compensation owed around each of them. Must be called before [boostAt].
     */
    fun setField(blocks: List<PlanBlock>, windows: List<PlanWindow>) {
        val tau = tauMillis
        var end: Long? = null
        fieldSpans =
            exclusionsOf(blocks, windows).mapValues { (id, spans) ->
                val minimum = (minimumOf[id] ?: 0L).toDouble()
                spans.mapNotNull { span ->
                    val length = if (span.endMillis >= FOREVER) Double.POSITIVE_INFINITY
                    else (span.endMillis - span.startMillis).toDouble()
                    // Shorter than one slot of its own: it cannot have cost the task a slot, only delayed it —
                    // and a delay is already repaid exactly by the virtual clock, which hands the task back
                    // every minute it lost the moment the ban lifts. Compensating it here as well would pay the
                    // same debt twice, and would leave a 20-second ban swelling the slots around it forever.
                    if (length < minimum) return@mapNotNull null
                    val amp = minOf(length / tau, maxAmp)
                    if (span.endMillis < FOREVER) {
                        // Where the influence drops below the floor — past that the field is gone.
                        val reach = tau * ln(1.0 + amp / fieldFloor)
                        val stop = span.endMillis + reach.roundToLong()
                        if (end == null || stop > end!!) end = stop
                    }
                    FieldSpan(span.startMillis, span.endMillis, amp)
                }
            }
        fieldEndMillis = end
    }

    /**
     * How much longer than usual a slot of [id] may be at [millis]: `1 + Σ a·e^(−d/tau)` over the intervals it
     * is *excluded from*, clipped to [maxBoost]. Symmetric in `d` — the same ramp before an exclusion and after
     * it. Every span is summed, however far: [maxReachMillis] bounds where the *walk* stops caring
     * ([fieldEndMillis]), not what a boost inside that range is worth.
     */
    fun boostAt(id: TaskId, millis: Long): Double {
        val spans = fieldSpans[id] ?: return 1.0
        val tau = tauMillis
        var acc = 0.0
        for (span in spans) {
            val d =
                when {
                    millis in span.startMillis..span.endMillis -> 0.0
                    millis < span.startMillis -> (span.startMillis - millis).toDouble()
                    else -> (millis - span.endMillis).toDouble()
                }
            acc += span.amp * exp(-d / tau)
        }
        if (acc <= 0.0) return 1.0
        return 1.0 + minOf(acc, maxBoost - 1.0)
    }

    // ----- context helpers ----------------------------------------------------------------------

    /** The tasks the window covering [millis] accepts; every task when no window covers it. */
    fun allowedAt(windows: List<PlanWindow>, millis: Long): List<TaskId> {
        for (w in windows) {
            if (w.startMillis <= millis && (w.endMillis == null || millis < w.endMillis)) {
                return tasks.map { it.id }.filter { it in w.allowed }
            }
        }
        return tasks.map { it.id }
    }

    /** The pre-placed block covering [millis], if any. */
    fun blockAt(blocks: List<PlanBlock>, millis: Long): PlanBlock? =
        blocks.firstOrNull { it.startMillis <= millis && millis < it.endMillis }

    /** The next instant the context changes after [millis], or null if it never does again. */
    fun nextBoundary(blocks: List<PlanBlock>, windows: List<PlanWindow>, millis: Long): Long? {
        var best: Long? = null
        fun offer(b: Long?) {
            if (b != null && b > millis && (best == null || b < best!!)) best = b
        }
        for (b in blocks) offer(b.startMillis)
        for (w in windows) {
            offer(w.startMillis)
            offer(w.endMillis)
        }
        return best
    }

    /**
     * The first instant after [millis] at which **[id] itself** can no longer run, or null if it never stops.
     *
     * Not every context change stops every task. A window that bans B does not interrupt A, so a chunk of A
     * must be allowed to run straight through it: what bounds a chunk is where *its own* task is turned away,
     * never merely where the context happens to change. Treating the two as one is what makes a short ban punch
     * an unfillable hole into somebody else's slot — no 45-minute minimum fits in the 4 minutes left of a
     * 5-minute screen break, so the tail an off-screen task is entitled to would go idle instead.
     *
     * A pre-placed block stops everyone, so it always bounds; a window bounds only if [id] is absent from it.
     */
    fun blockedFrom(id: TaskId, blocks: List<PlanBlock>, windows: List<PlanWindow>, millis: Long): Long? {
        var best: Long? = null
        for (b in blocks) if (b.startMillis > millis && (best == null || b.startMillis < best!!)) best = b.startMillis
        val bounds = sortedBounds(windows, millis)
        for (bound in bounds) {
            if (best != null && bound >= best!!) break
            if (id !in allowedAt(windows, bound)) return bound
        }
        return best
    }

    /**
     * The earliest instant after [millis] at which one of [missing] — the tasks that cannot be placed here —
     * could be, either because it is let back in or because from there it has room for its whole minimum.
     *
     * This is the only reason to end a chunk early: a context change that makes no new task placeable changes
     * nothing about the decision, and cutting there would just split a slot in two for nothing.
     */
    fun nextPlaceable(
        missing: Collection<TaskId>,
        blocks: List<PlanBlock>,
        windows: List<PlanWindow>,
        millis: Long,
    ): Long? {
        if (missing.isEmpty()) return null
        val bounds = (sortedBounds(windows, millis) + blocks.map { it.endMillis }.filter { it > millis })
            .distinct().sorted()
        for (bound in bounds) {
            for (id in missing) {
                if (id !in allowedAt(windows, bound)) continue
                val room = blockedFrom(id, blocks, windows, bound)
                if (room == null || room - bound >= (minimumOf[id] ?: 0L)) return bound
            }
        }
        return null
    }

    private fun sortedBounds(windows: List<PlanWindow>, millis: Long): List<Long> =
        windows.flatMap { listOfNotNull(it.startMillis, it.endMillis) }
            .filter { it > millis }
            .distinct()
            .sorted()

    // ----- the repeating cycle ------------------------------------------------------------------

    /**
     * The cycle, built on the smallest possible period `T = max(mᵢ/pᵢ)` with `budgetᵢ = pᵢ·T ≥ mᵢ`, so the
     * shares inside it are exact by construction. The same greedy runs inside the period with two extra rules:
     * a slot never exceeds the task's remaining budget, and it never leaves a remainder smaller than the
     * minimum (that crumb could not be placed). No field, no forgetting here — this is the undisturbed regime.
     */
    fun steadyCycle(allowed: Collection<TaskId>): List<PlanSlot> {
        if (allowed.isEmpty()) return emptyList()
        val order = allowed.sortedBy { it.value }
        val p = sharesOf(order)
        val period = periodOf(order)
        val remaining = HashMap<TaskId, Double>(order.size).apply {
            for (id in order) put(id, (p[id] ?: 0.0) * period)
        }
        val v = HashMap<TaskId, Double>(order.size).apply { for (id in order) put(id, 0.0) }
        val slots = mutableListOf<PlanSlot>()
        var guard = 0
        while (order.any { (remaining[it] ?: 0.0) > CRUMB } && guard++ < MAX_CYCLE_SLOTS) {
            val live = order.filter { (remaining[it] ?: 0.0) > CRUMB }
            val name = pickLowestClock(v, live, p, last = null) ?: break
            var c = minOf(chunkMillis(name, v, live, p, boost = null, periodMillis = null), remaining[name]!!)
            if (remaining[name]!! - c < (minimumOf[name] ?: 0L).toDouble()) c = remaining[name]!! // no crumb
            if (c <= 0.0) break
            slots += PlanSlot(name, c.roundToLong())
            v[name] = v[name]!! + c / (p[name] ?: 1.0)
            remaining[name] = remaining[name]!! - c
        }
        return slots
    }

    /**
     * One slot per task: the same minimal period and the same exact shares, but the coarsest possible
     * interleaving. Used when the fine cycle needs too many rules to stay a practical, finite list.
     */
    fun coarseCycle(allowed: Collection<TaskId>): List<PlanSlot> {
        if (allowed.isEmpty()) return emptyList()
        val p = sharesOf(allowed)
        val period = periodOf(allowed)
        return allowed.sortedWith(compareByDescending<TaskId> { p[it] ?: 0.0 }.thenBy { it.value })
            .map { PlanSlot(it, ((p[it] ?: 0.0) * period).roundToLong()) }
    }

    // ----- the plan -----------------------------------------------------------------------------

    /**
     * `side-dev/test.py` `Scheduler.plan`, verbatim:
     *
     * - **Phase 1** walks the disturbed part of the timeline (pre-placed blocks, period windows, and the tail
     *   of the influence field) with the field-aware greedy → `prefix`;
     * - **Phase 2**, once nothing is left to distort the schedule, settles what is still owed → `prefix`, then
     *   attaches the analytic cycle → `cycle`.
     *
     * [maxRules] is the "strict maximum rule limit" of `side-dev/README.md`: a cycle needing more rules falls
     * back to [coarseCycle], and a prefix that fills the budget before the context ever freezes returns with
     * **no cycle at all** (a truncated timeline).
     */
    fun plan(
        blocks: List<PlanBlock> = emptyList(),
        windows: List<PlanWindow> = emptyList(),
        nowMillis: Long = 0L,
        lookbackMillis: Double? = null,
        maxRules: Int = MAX_RULES,
    ): Plan {
        val sorted = blocks.sortedBy { it.startMillis }
        setField(sorted, windows)

        val past = sorted.filter { it.endMillis <= nowMillis }
        val ahead = sorted.filter { it.endMillis > nowMillis }

        val walk = PlanWalk(this, seedClocks(past, nowMillis, lookbackMillis))
        val slots = mutableListOf<PlanSlot>()
        var t = nowMillis
        var freeTail = false
        var steps = 0
        // Safety valve: slots are merged, so their count no longer bounds the walk.
        val maxSteps = MAX_STEPS_PER_RULE * maxRules

        // `side-dev/scheduler_logic.py` `_head`: the run still in progress at `nowMillis`, and how much of its
        // minimum it has already served. The walk continues that run rather than starting a fresh one.
        val head = headRun(past)
        walk.serve(head?.first, 0.0) // `last = head_task`: the head is not picked twice in a row

        /**
         * `run_served`: how much of its minimum the *current run* of [id] has already paid. Consecutive slots
         * of one task are one slot, and an idle hole **suspends** a run rather than ending it — the README's
         * atomic block is about a task's service, and a period that accepts nobody does not serve anyone.
         *
         * (One conflation with the reference: a block owned by **nobody** is a `null` slot here, exactly like
         * an idle hole, so it suspends a run where the reference would end it. Both are "nothing of ours was
         * served", and the case only differs when such a block lands mid-minimum.)
         */
        fun runServed(id: TaskId): Long {
            var total = 0L
            for (i in slots.indices.reversed()) {
                val slot = slots[i]
                when (slot.taskId) {
                    id -> total += slot.durationMillis
                    null -> Unit // idle: skip it and keep scanning back
                    else -> return total
                }
            }
            return total + if (head != null && head.first == id) head.second else 0L
        }

        /** What a slot of [id] starting here would still owe of its minimum. */
        fun owed(id: TaskId): Long = ((minimumOf[id] ?: 0L) - runServed(id)).coerceAtLeast(0L)

        /**
         * `side-dev/scheduler_logic.py` `pending`: the task **no other task may interrupt** — the one running,
         * still short of its minimum — or null when nothing is owed. This is the README's atomic block,
         * verbatim ("if task B is scheduled at t=0 and a period p that only allows task A is at t=1, and task B
         * has a minimum time of 2, then the whole period p is scheduled with nothing"), and it is the rule a
         * *sliding* period runs into constantly: a period the held task is not allowed in **suspends** it, it
         * does not hand the timeline to somebody else.
         */
        fun pending(): TaskId? {
            for (i in slots.indices.reversed()) {
                val id = slots[i].taskId ?: continue
                return if (owed(id) > 0L) id else null
            }
            return head?.first?.takeIf { owed(it) > 0L }
        }

        // --- phase 1: the disturbed part of the timeline ---
        while (slots.size < maxRules && steps < maxSteps) {
            steps++
            val allowed = allowedAt(windows, t)
            val period = if (allowed.isNotEmpty()) periodOf(allowed) else minPeriodMillis

            val block = blockAt(ahead, t)
            if (block != null) { // cannot be moved
                // A pre-placed block is locked to its own coordinates, but a period still dictates what may RUN
                // there: where the block's own task is refused, the block is **suspended** and resumes on the
                // far side, exactly as a scheduled run is. So it is walked edge by edge, not swallowed whole.
                // A block owned by nobody is not one of the tasks the allow-lists speak about, and no period
                // suspends it.
                val stop = minOf(block.endMillis, nextBoundary(emptyList(), windows, t) ?: block.endMillis)
                val d = stop - t
                if (block.taskId == null || block.taskId in allowed) {
                    pushSlot(slots, block.taskId, d)
                    walk.serve(block.taskId, d.toDouble(), boost = 1.0)
                } else {
                    // Suspended: an idle hole that does NOT break the run (see [runServed]), so `last` stands.
                    pushSlot(slots, null, d)
                }
                t = stop
                freeTail = false
                walk.relax(0.0, period, allowed) // no forgetting here: the block is not ours
                walk.clamp(t - nowMillis)
                continue
            }

            val limit = nextBoundary(ahead, windows, t)
            val end = fieldEndMillis
            // A run suspended by a period it is banned from is not finished with the timeline, so the walk may
            // not stop at the last boundary while it still owes its minimum.
            if (limit == null && (end == null || t >= end) && pending() == null) break // nothing left to disturb

            // The atomic block: while a task is owed its minimum it is the only candidate, and where it is not
            // allowed there is no candidate at all — the period is scheduled with nothing.
            val held = pending()
            val candidates = if (held == null) allowed else if (held in allowed) listOf(held) else emptyList()

            // Each task is bounded by where *it* is turned away, not by the next context change.
            val room = candidates.associateWith { blockedFrom(it, ahead, windows, t) }
            // "does the minimum fit?" is a question for a task about to *start*. A task already running has
            // started, and refusing it the room it has left would idle the timeline and still leave its run
            // short: it may be cut anywhere, by whatever it cannot pass.
            val fitting =
                candidates.filter { room[it] == null || room[it]!! - t >= owed(it) || runServed(it) > 0L }

            if (fitting.isEmpty()) { // nothing can be placed here
                if (limit == null) break
                val gap = limit - t
                val tail = slots.lastOrNull()
                // The tail may only absorb a gap it is itself allowed to run in: a period that bans it
                // suspends the run, it does not extend it.
                if (freeTail && tail?.taskId != null && tail.taskId in allowed) { // stretch the last slot over it
                    slots[slots.size - 1] = PlanSlot(tail.taskId, tail.durationMillis + gap)
                    walk.serve(tail.taskId, gap.toDouble(), boost = 1.0)
                } else { // or leave a hole
                    pushSlot(slots, null, gap)
                    walk.idle()
                    freeTail = false
                }
                t += gap
                continue
            }

            val name = walk.pick(fitting, avoidLast = true) ?: break
            val boost = boostAt(name, t)
            var c = walk.chunkMillis(name, fitting, boost, period, floorOf(name, owed(name)))
            // Forced cut: where this task itself is turned away.
            room[name]?.let { c = minOf(c, (it - t).toDouble()) }
            // Optional cut: hand the timeline back as soon as a rival can take it, once the minimum is paid.
            val back = nextPlaceable(tasks.map { it.id }.filter { it !in fitting }, ahead, windows, t)
            if (back != null) c = minOf(c, (maxOf(back, t + owed(name)) - t).toDouble())
            val placed = c.roundToLong().coerceAtLeast(1L)
            pushSlot(slots, name, placed)
            // Charged at the boosted rate: the extra time is a genuinely higher local share, not a debt to be
            // taken back once the field is gone.
            walk.serve(name, placed.toDouble(), boost)
            t += placed
            freeTail = true
            walk.relax(placed.toDouble(), period, allowed)
            walk.clamp(t - nowMillis)
        }

        // --- phase 2: settle what is still owed, then repeat forever ---
        var cycle = emptyList<PlanSlot>()
        val allowed = allowedAt(windows, t)
        // A cycle may only be attached once nothing is owed: an atomic block still running is not a steady state.
        if (allowed.isNotEmpty() && slots.size < maxRules && pending() == null) {
            val period = periodOf(allowed)
            val horizon = t + (SETTLE_PERIODS * period).roundToLong() // settling is bounded
            while (slots.size < maxRules && t < horizon) {
                if (walk.spread(allowed) <= period) break // square again
                val name = walk.pick(allowed, avoidLast = true) ?: break
                val boost = boostAt(name, t)
                val placed =
                    walk.chunkMillis(name, allowed, boost, period, floorOf(name, owed(name)))
                        .roundToLong().coerceAtLeast(1L)
                pushSlot(slots, name, placed)
                walk.serve(name, placed.toDouble(), boost)
                t += placed
                walk.relax(placed.toDouble(), period, allowed)
                walk.clamp(t - nowMillis)
            }
            cycle = steadyCycle(allowed)
            if (cycle.size > maxRules) cycle = coarseCycle(allowed)
            cycle = phaseCycle(cycle, walk, allowed)
        }

        val (prefix, tidied) = tidy(slots, cycle)
        return Plan(startMillis = nowMillis, prefix = prefix, cycle = tidied)
    }

    /**
     * The virtual clocks the walk starts from: the imbalance inherited from the already-placed past.
     *
     * A task served exactly its share over the lookback window starts level; one that was never served starts
     * a whole window behind. The window is one [minPeriodMillis] by default, shortened to the first past block
     * when the history is younger than that — so a fresh account does not read as "everything is starved".
     * Only differences matter, so the clocks are shifted to put the most starved task at zero.
     */
    fun seedClocks(past: List<PlanBlock>, nowMillis: Long, lookbackMillis: Double? = null): Map<TaskId, Double> {
        val window = lookbackMillis ?: minPeriodMillis
        var windowStart = nowMillis - window.roundToLong()
        val earliest = past.minOfOrNull { it.startMillis }
        if (earliest != null) windowStart = maxOf(windowStart, earliest)
        windowStart = minOf(windowStart, nowMillis)
        val elapsed = (nowMillis - windowStart).toDouble()

        val served = HashMap<TaskId, Double>()
        for (block in past) {
            val id = block.taskId ?: continue
            if (id !in share) continue
            val overlap = minOf(block.endMillis, nowMillis) - maxOf(block.startMillis, windowStart)
            if (overlap > 0L) served[id] = (served[id] ?: 0.0) + overlap.toDouble()
        }
        val lag = share.mapValues { (id, p) -> (served[id] ?: 0.0) / p - elapsed }
        val base = lag.values.minOrNull() ?: 0.0
        return lag.mapValues { (_, l) -> l - base }
    }

    /** A new walk over this planner's rules, seeded with [clocks] (defaults to a level playing field). */
    fun walk(clocks: Map<TaskId, Double> = share.mapValues { 0.0 }): PlanWalk = PlanWalk(this, clocks)

    /**
     * `side-dev/scheduler_logic.py` `_head`: the run still in progress at the end of [past] — the task, and how
     * long it has been served — or null when the past ends on nothing of ours.
     *
     * A run survives an interruption by something that is not a task (an idle stretch, a block owned by
     * nobody): the README's atomic block is about a task's *service*, and a period that accepts nobody
     * suspends a run rather than ending it. So the scan skips those and stops at the first different task.
     */
    private fun headRun(past: List<PlanBlock>): Pair<TaskId, Long>? {
        var name: TaskId? = null
        var total = 0L
        for (block in past.sortedByDescending { it.startMillis }) {
            val id = block.taskId
            if (id == null || id !in minimumOf) continue // not one of ours: it does not end the run
            if (name == null) name = id else if (id != name) break
            total += block.durationMillis
        }
        return name?.let { it to total }
    }

    /** The reference's `floor=owed(name) or minimum[name]`, as a nullable [chunkMillis] floor. */
    private fun floorOf(id: TaskId, owedMillis: Long): Double? =
        if (owedMillis > 0L) owedMillis.toDouble() else null

    // ----- internals ----------------------------------------------------------------------------

    /**
     * Append a slot, merging it into the previous one when it belongs to the same task. Two consecutive slots
     * of one task are a single rule, so they must not each cost one: inside a window where only one task is
     * allowed the greedy still steps by `minimum`, and without this the rule budget would be spent on invisible
     * seams instead of real alternations.
     */
    private fun pushSlot(slots: MutableList<PlanSlot>, id: TaskId?, durationMillis: Long) {
        if (durationMillis <= 0L) return
        val tail = slots.lastOrNull()
        if (tail != null && tail.taskId == id) {
            slots[slots.size - 1] = PlanSlot(id, tail.durationMillis + durationMillis)
        } else {
            slots += PlanSlot(id, durationMillis)
        }
    }

    /**
     * Attach the cycle in the phase the walk would have gone on with.
     *
     * [steadyCycle] starts from a blank slate, so it always opens with the same task; if the prefix left
     * another one starved, opening there hands the first task its slot twice in a row. They merge into one
     * block of twice the minimum — the coarse scale this model exists to avoid — even though simply starting
     * the cycle one slot later costs nothing.
     */
    internal fun phaseCycle(cycle: List<PlanSlot>, walk: PlanWalk, allowed: List<TaskId>): List<PlanSlot> {
        if (cycle.isEmpty()) return cycle
        val first = walk.pick(allowed, avoidLast = true) ?: return cycle
        val i = cycle.indexOfFirst { it.taskId == first }
        return if (i <= 0) cycle else cycle.drop(i) + cycle.take(i)
    }

    /** No two consecutive slots of the same task, wrap-around included. */
    private fun tidy(prefixIn: List<PlanSlot>, cycleIn: List<PlanSlot>): Pair<List<PlanSlot>, List<PlanSlot>> {
        val prefix = mergeRun(prefixIn).toMutableList()
        val cycle = mergeRun(cycleIn).toMutableList()

        // The cycle wraps onto itself: move the head out and merge it into the tail.
        while (cycle.size > 1 && cycle.first().taskId == cycle.last().taskId) {
            val head = cycle.removeAt(0)
            cycle[cycle.size - 1] =
                PlanSlot(cycle.last().taskId, cycle.last().durationMillis + head.durationMillis)
            prefix += head
        }
        val merged = mergeRun(prefix).toMutableList()

        // Prefix/cycle junction: rotate the cycle once (the timeline is unchanged).
        var rotations = cycle.size
        while (rotations-- > 0 && merged.isNotEmpty() && cycle.size > 1 &&
            merged.last().taskId == cycle.first().taskId
        ) {
            val head = cycle.removeAt(0)
            cycle += head
            merged[merged.size - 1] = PlanSlot(head.taskId, merged.last().durationMillis + head.durationMillis)
        }
        return merged to cycle
    }

    private fun mergeRun(slots: List<PlanSlot>): List<PlanSlot> {
        val out = mutableListOf<PlanSlot>()
        for (s in slots) {
            val tail = out.lastOrNull()
            if (tail != null && tail.taskId == s.taskId) {
                out[out.size - 1] = PlanSlot(s.taskId, tail.durationMillis + s.durationMillis)
            } else {
                out += s
            }
        }
        return out
    }

    /**
     * The most starved task; ties resolve by biggest share and then by the order [candidates] arrives in, so
     * the walk is deterministic. Shared by [PlanWalk.pick] and [steadyCycle].
     */
    internal fun pickLowestClock(
        clocks: Map<TaskId, Double>,
        candidates: List<TaskId>,
        shares: Map<TaskId, Double>,
        last: TaskId?,
    ): TaskId? {
        if (candidates.isEmpty()) return null
        // `last` is never picked twice in a row unless it is the only one left: a task that is owed a lot gets
        // a *denser* presence, not one long block that would swallow the whole compensation at once.
        val pool = candidates.filter { it != last }.ifEmpty { candidates }
        // Deviation from the reference: zero-priority tasks are kept out of the share model, so they are only
        // considered when nothing with a real share is available.
        val real = pool.filter { it in share }
        val effective = real.ifEmpty { pool }
        var best: TaskId? = null
        var bestClock = 0.0
        var bestShare = 0.0
        for (id in effective) {
            val clock = clocks[id] ?: 0.0
            val sh = shares[id] ?: 0.0
            val better =
                when {
                    best == null -> true
                    clock < bestClock - TIE_EPSILON -> true
                    clock > bestClock + TIE_EPSILON -> false
                    // Ties resolve by the biggest share and then by the order [candidates] arrives in — which
                    // is the caller's deterministic tie-break (PRD §9: higher priority, then title).
                    else -> sh > bestShare
                }
            if (better) {
                best = id
                bestClock = clock
                bestShare = sh
            }
        }
        return best
    }

    /**
     * How long the next slot of [name] should be: enough to catch up with the runner-up, never below the
     * minimum. With a [boost] the sizing is field-aware — the slot may grow up to `boost ×` the task's natural
     * unit (`max(minimum, p·T)`), which is exactly how the compensation around an exclusion is delivered.
     */
    internal fun chunkMillis(
        name: TaskId,
        clocks: Map<TaskId, Double>,
        candidates: List<TaskId>,
        shares: Map<TaskId, Double>,
        boost: Double?,
        periodMillis: Double?,
        // `side-dev/scheduler_logic.py` `floor=owed(name) or minimum[name]`: a task RESUMING a run only owes
        // what is left of its minimum, so flooring it at the whole minimum again would over-serve it. Null
        // (the default) is the reference's own fallback — the full minimum, for a slot that is starting.
        floorMillis: Double? = null,
    ): Double {
        val p = shares[name] ?: 0.0
        val minimum = floorMillis ?: (minimumOf[name] ?: 0L).toDouble()
        if (p <= 0.0) return minimum // a zero-priority task has no catch-up to compute
        val mine = clocks[name] ?: 0.0
        // Only tasks that carry a share have a virtual clock, so only they can be the runner-up to catch.
        val target = candidates.filter { it != name && it in clocks }.minOfOrNull { clocks[it]!! } ?: mine
        val need = p * (target - mine) // time to catch the runner-up
        var c = maxOf(minimum, need)
        if (boost != null && periodMillis != null) { // local, field-aware sizing
            val unit = maxOf(minimum, p * periodMillis)
            c = minOf(maxOf(c, minimum * boost), unit * boost)
        }
        if (resolutionMillis > 0.0) c = ceilTo(c, resolutionMillis)
        return c
    }

    private fun ceilTo(x: Double, step: Double): Double {
        val n = kotlin.math.ceil(x / step - CEIL_EPSILON)
        return n * step
    }

    private class FieldSpan(val startMillis: Long, val endMillis: Long, val amp: Double)

    /** A half-open exclusion interval; [endMillis] == [FOREVER] means it never re-opens. */
    data class LongRangeSpan(val startMillis: Long, val endMillis: Long)

    companion object {
        /** An end that never comes — the `FOREVER` of `side-dev/test.py`, kept in `Long` arithmetic. */
        const val FOREVER: Long = Long.MAX_VALUE

        /** `side-dev/test.py` `MAX_RULES`: the strict rule limit of `side-dev/README.md`. */
        const val MAX_RULES: Int = 50

        const val DEFAULT_MAX_BOOST: Double = 6.0

        const val DEFAULT_FIELD_FLOOR: Double = 0.1

        /** `max_reach` defaults to six `tau` in the reference. */
        const val DEFAULT_REACH_TAUS: Double = 6.0

        /** `side-dev/test.py`: the phase-2 settling is bounded to four periods. */
        const val SETTLE_PERIODS: Double = 4.0

        private const val MAX_STEPS_PER_RULE = 200

        /** Enough to build any sane fine cycle; guards a float pathology from spinning [steadyCycle]. */
        private const val MAX_CYCLE_SLOTS = 10_000

        /** Below this many millis a remaining budget is exhausted (the reference compares exact rationals). */
        private const val CRUMB = 0.5

        /** Two virtual clocks this close are the same number, so the tie-break decides (see [pickLowestClock]). */
        private const val TIE_EPSILON = 1e-6

        private const val CEIL_EPSILON = 1e-9

        /** Overlapping or touching exclusions are one exclusion: what matters is how long a task is kept out,
         * not how many separate rules keep it out. A block of A followed by a window that bans B is, for B, a
         * single ban of the combined length. */
        fun mergeSpans(spans: List<LongRangeSpan>): List<LongRangeSpan> {
            val out = mutableListOf<LongRangeSpan>()
            for (span in spans.sortedWith(compareBy({ it.startMillis }, { it.endMillis }))) {
                val tail = out.lastOrNull()
                if (tail != null && span.startMillis <= tail.endMillis) {
                    out[out.size - 1] = LongRangeSpan(tail.startMillis, maxOf(tail.endMillis, span.endMillis))
                } else {
                    out += span
                }
            }
            return out
        }
    }
}

/**
 * The running state of one walk: each task's **virtual clock** `vᵢ = servedᵢ / pᵢ` plus the task placed last.
 * Single-use and not thread-safe — [serve] mutates it as the caller's cursor sweeps the timeline.
 *
 * This is the whole of `side-dev/test.py`'s scheduling logic, and it is deliberately the **only** copy of it:
 * both [SchedulerPlanner.plan] (the rule-list form of `side-dev/README.md`) and
 * [SchedulerDomain.fillSchedule] (the app's calendar fill, which additionally has to weave in screen breaks,
 * screen zones and concrete panels) are thin drivers over it, so the two can never disagree on the rules.
 */
class PlanWalk internal constructor(
    private val planner: SchedulerPlanner,
    clocks: Map<TaskId, Double>,
) {
    private val v = HashMap(clocks)

    /** The task served by the previous slot, or null after an idle hole. */
    var last: TaskId? = null
        private set

    fun clockOf(id: TaskId): Double = v[id] ?: 0.0

    /** `max v − min v` over [active]: how far from square the schedule is. */
    fun spread(active: Collection<TaskId>): Double {
        val live = active.filter { it in v }
        if (live.isEmpty()) return 0.0
        return live.maxOf { v[it]!! } - live.minOf { v[it]!! }
    }

    /** The most starved of [candidates]; see [SchedulerPlanner.pickLowestClock]. */
    fun pick(candidates: List<TaskId>, avoidLast: Boolean = true): TaskId? =
        planner.pickLowestClock(v, candidates, planner.share, if (avoidLast) last else null)

    /** How long [id]'s next slot should be; see [SchedulerPlanner.chunkMillis]. */
    fun chunkMillis(
        id: TaskId,
        candidates: List<TaskId>,
        boost: Double? = null,
        periodMillis: Double? = null,
        floorMillis: Double? = null,
    ): Double = planner.chunkMillis(id, v, candidates, planner.share, boost, periodMillis, floorMillis)

    /**
     * Charge [durationMillis] of timeline to [id] at the boosted rate. Boosted time is a genuinely higher
     * local share, never a debt to be clawed back — that is what makes the compensation around an exclusion
     * stick. A null [id] (a block owned by nobody) only sets [last].
     */
    fun serve(id: TaskId?, durationMillis: Double, boost: Double = 1.0) {
        if (id != null) {
            val p = planner.share[id]
            if (p != null && p > 0.0) v[id] = (v[id] ?: 0.0) + durationMillis / (p * boost)
        }
        last = id
    }

    /** Nothing was served over this stretch: the run is broken, so the next pick may repeat the previous task. */
    fun idle() {
        last = null
    }

    /**
     * Exponential forgetting.
     *
     * Whatever sits inside one period is normal scheduling pressure and is left untouched. Anything beyond —
     * the debt a big exclusion creates, which no bounded compensation could ever repay — loses a factor
     * `e^(−dt/tau)` of itself for every `dt` of freely scheduled time. That is what makes the influence of an
     * exclusion decay instead of turning into an equally long block of the deprived task.
     *
     * Tasks that are currently not allowed keep at most one period of credit, otherwise they would come back
     * from a long exclusion and monopolise the timeline.
     */
    fun relax(dtMillis: Double, periodMillis: Double, active: Collection<TaskId>) {
        val live = active.filter { it in v }
        if (live.isEmpty()) return
        val lo = live.minOf { v[it]!! }
        if (dtMillis > 0.0) {
            val f = exp(-dtMillis / planner.tauMillis)
            for (id in live) {
                val over = v[id]!! - lo - periodMillis
                if (over > 0.0) v[id] = v[id]!! - over * (1.0 - f)
            }
        }
        val activeSet = live.toHashSet()
        for (id in v.keys.toList()) {
            if (id !in activeSet) v[id] = v[id]!!.coerceIn(lo - periodMillis, lo + periodMillis)
        }
    }

    /** Optional hard bound on the virtual clock ([SchedulerPlanner.maxLagMillis]); off by default. */
    fun clamp(elapsedMillis: Long) {
        val lag = planner.maxLagMillis ?: return
        val t = elapsedMillis.toDouble()
        for (id in v.keys.toList()) v[id] = v[id]!!.coerceIn(t - lag, t + lag)
    }
}
