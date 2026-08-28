package org.example.project.scheduler.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import org.example.project.scheduler.model.TaskId

/**
 * PRD §9 / `side-dev/README.md`: the **cyclic proportional-share** scheduler — the Kotlin port of the reference
 * implementation in `side-dev/scheduler_logic.py`.
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
 * ### Resuming: a chain of re-plans is the same schedule as one long plan
 * The app re-plans at every rule change and materializes its tail by extension, so a plan resumed at `t` must
 * reproduce the walk that passed through `t`. Everything the walk carries and the seeding has to rebuild from
 * the history is a way for that to fail, and three of them are load-bearing here:
 * - [replayClocks] replays the past **the way the walk writes it** — edge by edge, with [PlanWalk.relax]
 *   applied exactly where the walk applies it — rather than summing `served / p` over a window;
 * - [lookbackStart] measures how far back that reaches in **schedulable** time, not wall time;
 * - [lastRun], deliberately not [headRun], is what the walk carries as `last`.
 *
 * ### What may START here, and what merely bounds it
 * A context change is not a wall. What bounds a chunk is the first instant *its own* task is turned away
 * ([blockedFrom]); what decides whether a task may start is whether its minimum fits the instants it may
 * actually **run** in ([fitsFrom], which steps over every interval belonging to nobody — a 20-second ban only
 * *suspends* a run) **and** whether finishing that run would extend somebody else's ban ([wallsOf] + [clears]).
 *
 * ### The influence field (`tau`)
 * A task can be deprived of the timeline in exactly two ways, and they are the same phenomenon: a fixed block
 * owned by *another* task forbids everyone else for its whole length, and a period window forbids every task it
 * does not list. Both are reduced to one object — an **exclusion**, an interval a given task is kept out of
 * ([deprivationsOf], computed per *instant*: the edges are cut first and what an instant refuses is the union
 * of everything covering it, which is the only reading under which overlapping periods mean anything; and only
 * obstacles still **ahead** build the field, since what already happened is history and reaches the walk
 * through the clocks instead). Around an exclusion the schedule is deliberately distorted in favour of the
 * deprived task,
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
 * ### Deliberate deviations from `side-dev/scheduler_logic.py`
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
    /**
     * `side-dev/README.md`: the task's **resilience** to each kind of restrictive period — a multiplier in
     * `[0, 1]` on its priority percentage for as long as a period of that kind lasts. Overrides only: a kind
     * absent from the map takes [PeriodKinds.defaultResilience], which is `1` for every kind except
     * [PeriodKinds.NO_TASK]. So a kind the user has only just defined restricts nobody, and "on screen" is
     * exactly a `0` against [PeriodKinds.NO_SCREEN].
     */
    val resilience: Map<String, Double> = emptyMap(),
) {
    /** This task's multiplier inside a period of [kind]; see [PeriodKinds.resilienceFor]. */
    fun resilienceFor(kind: String): Double = PeriodKinds.resilienceFor(resilience, kind)
}

/** A pre-placed piece of timeline. [taskId] `null` = owned by nobody, so it excludes everyone equally. */
data class PlanBlock(val taskId: TaskId?, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * A restrictive period, as the walk reads it: `[startMillis, endMillis)` with the per-task **multiplier** the
 * kinds covering it work out to. [endMillis] `null` = FOREVER (it never re-opens).
 *
 * The README's object is a *kind*, and the multiplier is `Π resilience(kind)` over every kind covering the
 * span ([PeriodKinds.multiplier]) — evaluated once, when the window is built ([of]), because the walk asks
 * for it at every step and the tasks it applies to do not change inside a plan. [multipliers] holds
 * **overrides only**, exactly like [PlanTask.resilience]: a task absent from it is unaffected here.
 */
data class PlanWindow(
    val startMillis: Long,
    val endMillis: Long?,
    /** taskId → its multiplier inside this window. Absent ⇒ [defaultMultiplier]. */
    val multipliers: Map<TaskId, Double> = emptyMap(),
    /** The README kinds this span is covered by — carried for diagnostics and for the recurrence bars. */
    val kinds: Set<String> = emptySet(),
    /**
     * What a task the map says nothing about gets. `1.0` for a window built from kinds ([of]) — the
     * resilience model's own default, "a kind this task was never told about leaves it alone". `0.0` for the
     * binary form ([accepting]), which names the accepted set and refuses everybody else, so it needs no
     * roster of who "everybody else" is.
     */
    val defaultMultiplier: Double = 1.0,
) {
    /** The multiplier this window applies to [id]: an override if it has one, else [defaultMultiplier]. */
    fun multiplierFor(id: TaskId): Double = multipliers[id] ?: defaultMultiplier

    /** Whether [id] may run here at all — a resilience of `0` is the README's own word for "forbidden". */
    fun allows(id: TaskId): Boolean = multiplierFor(id) > 0.0

    /**
     * The tasks this window explicitly does not turn away. Only meaningful for a window whose
     * [defaultMultiplier] is `0.0` (the binary form); a kind-built window turns nobody away by default and
     * this is the empty set, which is why the walk asks [multiplierFor] and never this.
     */
    val allowed: Set<TaskId> get() = multipliers.filterValues { it > 0.0 }.keys

    companion object {
        /**
         * The window a set of [kinds] makes for [tasks] — the README's model read straight: each task's
         * multiplier is the product of its resilience to every kind covering the span. A task the kinds leave
         * at `1` is omitted from [multipliers], so an uncovered stretch carries an empty map and costs
         * nothing to ask about.
         */
        fun of(
            startMillis: Long,
            endMillis: Long?,
            kinds: Collection<String>,
            tasks: Collection<PlanTask>,
        ): PlanWindow {
            if (kinds.isEmpty()) return PlanWindow(startMillis, endMillis)
            val mult = HashMap<TaskId, Double>()
            for (t in tasks) {
                val m = PeriodKinds.multiplier(t.resilience, kinds)
                if (m < 1.0) mult[t.id] = m
            }
            return PlanWindow(startMillis, endMillis, mult, kinds.toSet())
        }

        /**
         * The binary form, for a caller that has already worked out who is accepted (the tests, and the
         * reference-conformance surface [SchedulerPlanner.plan]). Everybody else is refused outright — stated
         * by the DEFAULT rather than by listing them, so the caller needs no roster of who exists.
         */
        fun accepting(startMillis: Long, endMillis: Long?, allowed: Set<TaskId>): PlanWindow =
            PlanWindow(startMillis, endMillis, allowed.associateWith { 1.0 }, defaultMultiplier = 0.0)
    }
}

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
     * [minPeriodMillis] by default, exactly as in `side-dev/scheduler_logic.py`.
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

    /**
     * `T` for a restricted set: the smallest period giving each allowed task one slot ≥ its minimum.
     *
     * [shares] defaults to the nominal percentages renormalized over [allowed]; a caller standing inside a
     * restrictive period hands the EFFECTIVE ones ([localSharesOf]) instead, because that is what the shares
     * really are for as long as the period lasts.
     */
    fun periodOf(allowed: Collection<TaskId>, shares: Map<TaskId, Double> = sharesOf(allowed)): Double {
        if (allowed.isEmpty()) return minPeriodMillis
        return allowed.maxOf { id ->
            val p = shares[id] ?: 0.0
            if (p <= 0.0) minPeriodMillis else (minimumOf[id] ?: 0L).toDouble() / p
        }.coerceAtLeast(1.0)
    }

    // ----- the influence field ------------------------------------------------------------------

    /**
     * `side-dev/scheduler.py` `Walk._build_field`: per task, the stretches it was turned away from — **with
     * how much of it it was turned away from**.
     *
     * A resilience is a multiplier, not a gate, so deprivation is a matter of degree: a period a task is
     * `0.4`-resilient to costs it `0.6` of its share there, and the compensation owed around it is the same
     * fraction of what a flat refusal would owe. A pre-placed block owned by A, and a period A alone is
     * allowed in, are the same event read two ways — both leave everybody else at a multiplier of `0` — so
     * both are read here through one quantity, `1 − mult`.
     *
     * A stretch that refuses EVERYBODY creates no field at all: nobody is served there, so nobody is deprived
     * *relative to* anybody, and a wait no rival profits from is a pure delay the virtual clock already
     * repays exactly. Neither does a stretch shorter than the deprived task's own minimum — it could not have
     * run in it anyway.
     *
     * The result is per task a list of contiguous stretches with the **cost** each one carries
     * (`Σ (1 − mult)·length`), which is what the amplitude is read off in [setField].
     */
    fun deprivationsOf(blocks: List<PlanBlock>, windows: List<PlanWindow>): Map<TaskId, List<Deprivation>> {
        val everyone = share.keys
        if (everyone.isEmpty()) return emptyMap()
        val edgeSet = HashSet<Long>()
        for (b in blocks) {
            edgeSet += b.startMillis
            edgeSet += b.endMillis
        }
        for (w in windows) {
            edgeSet += w.startMillis
            edgeSet += w.endMillis ?: FOREVER
        }
        if (edgeSet.size < 2) return emptyMap()
        val edges = edgeSet.sorted()
        val raw = HashMap<TaskId, MutableList<Deprivation>>()
        for (i in 0 until edges.size - 1) {
            val a = edges[i]
            val b = edges[i + 1]
            if (b <= a) continue
            // Read at the MIDPOINT, exactly as the reference does: that is what makes an open-started period
            // (the dragged 20 seconds, the half-open `(t_p, t_p + 20s]`) mean what it says.
            val mid = if (b >= FOREVER) a + 1L else a + (b - a) / 2
            val w = weightsAt(blocks, windows, mid)
            if (w.values.none { it > 0.0 }) continue // refuses everybody: no relative deprivation
            for (id in everyone) {
                val p = share[id] ?: continue
                val mult = (w[id] ?: 0.0) / p
                if (mult >= 1.0) continue
                val cost = (1.0 - mult) * (if (b >= FOREVER) Double.POSITIVE_INFINITY else (b - a).toDouble())
                val list = raw.getOrPut(id) { mutableListOf() }
                val prev = list.lastOrNull()
                // Contiguous pieces are ONE stretch — a period abutting another, or a shape whose halves
                // deprive the same task, is a single blockage and not two.
                if (prev != null && prev.endMillis == a) {
                    list[list.size - 1] = Deprivation(prev.startMillis, b, prev.costMillis + cost)
                } else {
                    list += Deprivation(a, b, cost)
                }
            }
        }
        return raw
    }

    /**
     * Turn the deprivations into the field: for each task, the stretches it was kept out of and the amplitude
     * `a = cost / tau` of the compensation owed around each of them. Must be called before [boostAt].
     */
    fun setField(blocks: List<PlanBlock>, windows: List<PlanWindow>) {
        val tau = tauMillis
        var end: Long? = null
        fieldSpans =
            deprivationsOf(blocks, windows).mapValues { (id, spans) ->
                val minimum = (minimumOf[id] ?: 0L).toDouble()
                spans.mapNotNull { span ->
                    val length = if (span.endMillis >= FOREVER) Double.POSITIVE_INFINITY
                    else (span.endMillis - span.startMillis).toDouble()
                    // Shorter than one slot of its own: it cannot have cost the task a slot, only delayed it —
                    // and a delay is already repaid exactly by the virtual clock, which hands the task back
                    // every minute it lost the moment the ban lifts. Compensating it here as well would pay the
                    // same debt twice, and would leave a 20-second ban swelling the slots around it forever.
                    if (length < minimum) return@mapNotNull null
                    val amp = minOf(span.costMillis / tau, maxAmp)
                    if (amp <= 0.0) return@mapNotNull null
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
     * How much longer than usual a slot of [id] may be at [millis]: `1 + Σ a·e^(−d/tau)` over the stretches it
     * was *deprived in*, clipped to [maxBoost]. Symmetric in `d` — the same ramp before a blockage and after
     * it. Every span is summed, however far: [maxReachMillis] bounds where the *walk* stops caring
     * ([fieldEndMillis]), not what a boost inside that range is worth.
     *
     * `side-dev/scheduler.py` `Walk._boost`: a task standing INSIDE its own deprivation is being deprived,
     * not repaid — a boost there would hand straight back what the period took, which is exactly the
     * overcompensation the README rules out — so its own span is skipped rather than counted at `d = 0`.
     */
    fun boostAt(id: TaskId, millis: Long): Double {
        val spans = fieldSpans[id] ?: return 1.0
        val tau = tauMillis
        var acc = 0.0
        for (span in spans) {
            if (millis in span.startMillis..span.endMillis) continue
            val d =
                if (millis < span.startMillis) (span.startMillis - millis).toDouble()
                else (millis - span.endMillis).toDouble()
            acc += span.amp * exp(-d / tau)
        }
        if (acc <= 0.0) return 1.0
        return 1.0 + minOf(acc, maxBoost - 1.0)
    }


    // ----- context helpers ----------------------------------------------------------------------

    /** Every task, in the caller's tie-break order — the answer when no period covers an instant. */
    private val allIds: List<TaskId> = tasks.map { it.id }

    /**
     * `side-dev/scheduler_logic.py` `_allowed_at`: who may run at [millis] — everyone, **less what EVERY
     * period covering it refuses**.
     *
     * Periods may overlap and the timeline need not be covered by them at all. What an instant refuses is
     * therefore the *sum* of the bans of every period over it (a task one of them forbids is forbidden
     * whatever the others say), and an instant no period covers refuses nobody. Stated on [PlanWindow]'s
     * `allowed` encoding, "the sum of the bans" is the **intersection** of the accepted sets.
     */
    /**
     * `side-dev/scheduler.py` `Environment.multiplier`: what every period covering [millis] does to [id]'s
     * percentage — the **product** of their multipliers, so overlapping periods compound and the strictest of
     * them still forbids ("Multiple restrictive periods can appear at a given time t").
     */
    fun multiplierAt(windows: List<PlanWindow>, id: TaskId, millis: Long): Double {
        var m = 1.0
        for (w in windows) {
            if (w.startMillis <= millis && (w.endMillis == null || millis < w.endMillis)) {
                m *= w.multiplierFor(id)
                if (m <= 0.0) return 0.0
            }
        }
        return m
    }

    /**
     * `side-dev/scheduler.py` `Environment.weights`: **the effective priority share of every task at
     * [millis]** — its percentage after resilience and after the pre-placed blocks. Zero means "may not run
     * here", so the candidate set is simply the positive entries.
     *
     * This is the one quantity the whole walk is written in terms of, and it is why the resilience model
     * needs no second mechanism: a task inside a period it is half-resilient to is not banned and not
     * unaffected — it carries half its percentage, races the others with it, and is charged for its service
     * against it ([PlanWalk.serveWeighted]), which is exactly what "a multiplier for the task's priority
     * percentage during that restrictive period" says.
     */
    /**
     * Who the environment PERMITS at [millis] — every task a covering period leaves a positive multiplier
     * and a pre-placed block does not lock out, **whatever its priority**.
     *
     * The deliberate deviation from `side-dev/scheduler.py`, which raises when nothing has a positive
     * priority: the app must still fill the calendar for a tree whose weights are all zero, and a period may
     * accept *only* a zero-priority task. Such a task is absent from [share] and from the field, so it is
     * picked only where nothing with a real share is available ([candidatesAt]) and placed for exactly its
     * minimum.
     */
    fun permittedAt(blocks: List<PlanBlock>, windows: List<PlanWindow>, millis: Long): List<TaskId> {
        val block = blockAt(blocks, millis)
        return allIds.filter { id ->
            (block == null || block.taskId == id) && multiplierAt(windows, id, millis) > 0.0
        }
    }

    /**
     * The candidate set the walk races at [millis]: whoever is permitted **and** carries a positive effective
     * weight — falling back to the bare permitted set where nobody does, so a zero-priority task still fills
     * a period nothing else can occupy rather than the timeline idling.
     */
    fun candidatesAt(weights: Map<TaskId, Double>, permitted: List<TaskId>): List<TaskId> =
        permitted.filter { (weights[it] ?: 0.0) > 0.0 }.ifEmpty { permitted }

    fun weightsAt(blocks: List<PlanBlock>, windows: List<PlanWindow>, millis: Long): Map<TaskId, Double> {
        val block = blockAt(blocks, millis)
        val out = HashMap<TaskId, Double>(share.size)
        for ((id, p) in share) {
            if (block != null && block.taskId != id) {
                out[id] = 0.0
                continue
            }
            out[id] = p * multiplierAt(windows, id, millis)
        }
        return out
    }

    /**
     * The shares of [weightsAt], renormalized over the tasks that may actually run there — the reference's
     * `p_local`. Renormalizing is what makes a claim and a round mean the same thing inside a restrictive
     * period as outside one: the percentages are always a share of the hundred that is actually on offer.
     */
    fun localSharesOf(weights: Map<TaskId, Double>, candidates: Collection<TaskId>): Map<TaskId, Double> {
        val total = candidates.sumOf { weights[it] ?: 0.0 }
        if (total <= 0.0) return candidates.associateWith { 1.0 / candidates.size.coerceAtLeast(1) }
        return candidates.associateWith { (weights[it] ?: 0.0) / total }
    }

    fun allowedAt(windows: List<PlanWindow>, millis: Long): List<TaskId> {
        var banned: HashSet<TaskId>? = null
        for (w in windows) {
            if (w.startMillis <= millis && (w.endMillis == null || millis < w.endMillis)) {
                val set = banned ?: HashSet<TaskId>().also { banned = it }
                for (id in allIds) if (!w.allows(id)) set.add(id)
            }
        }
        val ban = banned ?: return allIds
        return allIds.filter { it !in ban }
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
        for (b in blocks) {
            offer(b.startMillis)
            // BOTH edges: `side-dev/scheduler.py`'s `Environment.bounds` cuts at a block's end exactly as at
            // its start. Offering only the start left a walk with no periods no boundary at all, so it read
            // the environment at a midpoint far past the block and let a rival run straight through it.
            offer(b.endMillis)
        }
        for (w in windows) {
            offer(w.startMillis)
            offer(w.endMillis)
        }
        return best
    }

    /**
     * `_bounds_after`: every instant the environment can change at, after [millis] — both edges of every
     * period and of every pre-placed block. The walk asks about them constantly, so they are found once.
     */
    fun boundsAfter(blocks: List<PlanBlock>, windows: List<PlanWindow>, millis: Long): List<Long> {
        val out = HashSet<Long>()
        for (w in windows) {
            if (w.startMillis > millis) out += w.startMillis
            w.endMillis?.let { if (it > millis) out += it }
        }
        for (b in blocks) {
            if (b.startMillis > millis) out += b.startMillis
            if (b.endMillis > millis) out += b.endMillis
        }
        return out.sorted()
    }

    /**
     * `_nobody_at`: is [millis] **nobody's** — a period refusing everyone, or a block owned by nobody?
     *
     * Such an interval SUSPENDS a run instead of ending it (`side-dev/README.md`'s atomic block: the period
     * is scheduled with nothing and the task resumes on the far side), and it deprives nobody relative to
     * anybody — which is already why it creates no field.
     */
    fun nobodyAt(blocks: List<PlanBlock>, windows: List<PlanWindow>, millis: Long): Boolean {
        val block = blockAt(blocks, millis)
        if (block != null) return block.taskId == null || block.taskId !in minimumOf
        return allowedAt(windows, millis).isEmpty()
    }

    /** `_may_run`: may [id] actually run at [millis]? */
    fun mayRun(id: TaskId, blocks: List<PlanBlock>, windows: List<PlanWindow>, millis: Long): Boolean {
        val block = blockAt(blocks, millis)
        if (block != null && block.taskId != id) return false
        return id in allowedAt(windows, millis)
    }

    /**
     * `_fits_from`: can [id] still pay [needMillis] of service from [millis] on?
     *
     * The room a task has is **not** simply the distance to the next boundary. An interval nobody may run in
     * only suspends the run, so it costs the task nothing but time; an interval somebody ELSE may run in ends
     * it, and starting there would blockade that somebody out of the period. So "does the minimum fit?"
     * counts the instants the task may actually run and *steps over* the ones that belong to nobody.
     *
     * That is what keeps a short all-refusing period from making a long minimum unschedulable — a 20-second
     * look-away every 20 minutes would otherwise forbid every 45-minute task from ever starting.
     */
    fun fitsFrom(
        id: TaskId,
        blocks: List<PlanBlock>,
        windows: List<PlanWindow>,
        millis: Long,
        needMillis: Long,
        bounds: List<Long>? = null,
    ): Boolean {
        if (needMillis <= 0L) return true
        var got = 0L
        var cur = millis
        for (b in bounds ?: boundsAfter(blocks, windows, millis)) {
            if (b <= cur) continue
            when {
                nobodyAt(blocks, windows, cur) -> Unit // suspended, not stopped
                mayRun(id, blocks, windows, cur) -> {
                    got += b - cur
                    if (got >= needMillis) return true
                }
                else -> return false
            }
            cur = b
        }
        // past the last boundary nothing changes any more
        return !nobodyAt(blocks, windows, cur) && mayRun(id, blocks, windows, cur)
    }

    /**
     * The first instant after [millis] at which **[id] itself** can no longer run, or null if it never stops.
     *
     * Not every context change stops every task. A window that bans B does not interrupt A, so a chunk of A
     * must be allowed to run straight through it: what bounds a chunk is where *its own* task is turned away,
     * never merely where the context happens to change. A pre-placed block stops everyone, so it always
     * bounds; a window bounds only if [id] is absent from it.
     */
    fun blockedFrom(
        id: TaskId,
        blocks: List<PlanBlock>,
        windows: List<PlanWindow>,
        millis: Long,
        bounds: List<Long>? = null,
    ): Long? {
        var best: Long? = null
        for (b in blocks) if (b.startMillis > millis && (best == null || b.startMillis < best!!)) best = b.startMillis
        for (bound in bounds ?: sortedBounds(windows, millis)) {
            if (bound <= millis) continue
            if (best != null && bound >= best!!) break
            if (id !in allowedAt(windows, bound)) return bound
        }
        return best
    }

    /** One instant a task comes back from an exclusion, and who returns there. */
    data class PlanWall(val millis: Long, val back: Set<TaskId>)

    /**
     * `_walls`: every instant a task comes **back** from an exclusion, with who returns.
     *
     * This is the other half of the atomic block. `side-dev/README.md` states one half — a run in progress is
     * not replaced by a period it may not run in, the period is scheduled with nothing and the run resumes on
     * the far side. The half that follows is about STARTING: a run begun at `t` is owed its whole minimum, so
     * if a rival comes back at `e` with `t < e < t + minimum`, that rival's exclusion does not really end at
     * `e` — it ends when the run does. Starting there does not merely use the period, it LENGTHENS somebody
     * else's ban, and it is the one thing the virtual clock cannot repay, because the rival is not a candidate
     * at the instant the decision is made.
     *
     * Only a RELATIVE deprivation counts, exactly as [deprivationsOf] counts it: an interval that refuses
     * everybody is nobody's exclusion, so the instant it ends is not a return. And a rival whose own minimum
     * does not fit from `e` either is not being deprived of anything, which is what keeps a densely broken
     * timeline from idling forever for want of a legal start.
     */
    fun wallsOf(blocks: List<PlanBlock>, windows: List<PlanWindow>): List<PlanWall> {
        val everyone = share.keys
        if (everyone.isEmpty()) return emptyList()
        val edgeSet = HashSet<Long>()
        for (w in windows) {
            edgeSet += w.startMillis
            w.endMillis?.let { edgeSet += it }
        }
        for (b in blocks) {
            edgeSet += b.startMillis
            edgeSet += b.endMillis
        }
        val edges = edgeSet.sorted()
        if (edges.size < 2) return emptyList()
        // One sweep for the allowed set of every interval: asking each period about each edge instead is
        // quadratic, and on three days of screen breaks both lists run into the hundreds.
        val opens = HashMap<Long, MutableList<Set<TaskId>>>()
        val closes = HashMap<Long, MutableList<Set<TaskId>>>()
        for (w in windows) {
            val forbidden = everyone.filterTo(HashSet()) { !w.allows(it) }
            if (forbidden.isEmpty()) continue
            opens.getOrPut(w.startMillis) { mutableListOf() } += forbidden
            w.endMillis?.let { closes.getOrPut(it) { mutableListOf() } += forbidden }
        }
        val ban = HashMap<TaskId, Int>()
        for (id in everyone) ban[id] = 0
        val sets = ArrayList<Set<TaskId>>(edges.size)
        for (a in edges) {
            closes[a]?.forEach { f -> for (id in f) ban[id] = (ban[id] ?: 0) - 1 }
            opens[a]?.forEach { f -> for (id in f) ban[id] = (ban[id] ?: 0) + 1 }
            var allowed = everyone.filterTo(HashSet()) { (ban[it] ?: 0) == 0 }
            val block = blockAt(blocks, a)
            if (block != null) allowed = allowed.filterTo(HashSet()) { it == block.taskId }
            sets += allowed
        }
        // `_fits_from` read off the sweep: the room a task has from the i-th edge, stepping over the intervals
        // that belong to nobody and stopping at the first that belongs to somebody else.
        fun fits(i: Int, id: TaskId): Boolean {
            var got = 0L
            val need = minimumOf[id] ?: 0L
            for (j in i until edges.size - 1) {
                if (sets[j].isEmpty()) continue
                if (id !in sets[j]) return false
                got += edges[j + 1] - edges[j]
                if (got >= need) return true
            }
            return sets.last().isNotEmpty() && id in sets.last()
        }
        val out = mutableListOf<PlanWall>()
        for (i in 1 until edges.size) {
            val before = sets[i - 1]
            if (before.isEmpty()) continue // nobody's exclusion, so no return
            val back = sets[i].filterTo(HashSet()) { it !in before && fits(i, it) }
            if (back.isNotEmpty()) out += PlanWall(edges[i], back)
        }
        return out
    }

    /**
     * `_clears`: may [id] START a run of [needMillis] at [millis]?
     *
     * Only where finishing it costs no rival a SLOT. Running past a rival's return merely *delays* that
     * rival, and the virtual clock repays a delay exactly the moment the rival is a candidate again — the
     * same reason [setField] builds no field around an exclusion shorter than the deprived task's own
     * minimum. What it cannot answer is a rival that could have run at its return and can no longer run at
     * all by the time this run releases it: there the ban did not end where the period says it did, it ended
     * where this run does, and the rival loses the whole slot rather than waiting for it.
     */
    fun clears(
        walls: List<PlanWall>,
        id: TaskId,
        blocks: List<PlanBlock>,
        windows: List<PlanWindow>,
        millis: Long,
        needMillis: Long,
        bounds: List<Long>,
    ): Boolean {
        val end = millis + needMillis
        val after = bounds.filter { it > end }
        for (wall in walls) {
            if (wall.millis <= millis) continue
            if (wall.millis >= end) return true
            for (rival in wall.back) {
                if (rival == id) continue
                if (!fitsFrom(rival, blocks, windows, end, minimumOf[rival] ?: 0L, after)) return false
            }
        }
        return true
    }

    /**
     * `_next_placeable`: the earliest instant after [millis] at which one of [missing] — the tasks that cannot
     * be placed here — could be, either because it is let back in or because from there it has room for its
     * whole minimum. This is the only reason to end a chunk early.
     */
    fun nextPlaceable(
        missing: Collection<TaskId>,
        blocks: List<PlanBlock>,
        windows: List<PlanWindow>,
        millis: Long,
        bounds: List<Long>? = null,
    ): Long? {
        if (missing.isEmpty()) return null
        val after = (bounds ?: boundsAfter(blocks, windows, millis)).filter { it > millis }
        for (bound in after) {
            for (id in missing) {
                if (!mayRun(id, blocks, windows, bound)) continue
                if (fitsFrom(id, blocks, windows, bound, minimumOf[id] ?: 0L, after)) return bound
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
     *
     * What each task takes and the ORDER it takes it in are two questions, and only the first is the clock's.
     * Over a period every task advances by exactly one `T`, so a builder reading the clocks alone serves the
     * whole of the fastest task's share before the slowest one's turn comes round — with twenty rivals, twenty
     * slots in a row, which [pushSlot] then merges into precisely the long block [roundUnitMillis] exists to
     * prevent. This loop starts every clock level and gives each task exactly its share of one period, so
     * every one of them ends it owing nothing, and the claim [pickNeediest] orders by is about who is BEHIND:
     * what the loop settles is the multiset, not its order. So the sizes are settled by the clock and the
     * order by the rule the clock cannot express —
     * nobody twice in a row while somebody else still owes a slot this period, most slots left going first.
     * Reordering cannot touch the shares: it is the same slots.
     */
    fun steadyCycle(
        allowed: Collection<TaskId>,
        shares: Map<TaskId, Double> = sharesOf(allowed),
    ): List<PlanSlot> {
        if (allowed.isEmpty()) return emptyList()
        val order = allowed.sortedBy { it.value }
        val p = shares
        val period = periodOf(order, p)
        val remaining = HashMap<TaskId, Double>(order.size).apply {
            for (id in order) put(id, (p[id] ?: 0.0) * period)
        }
        val v = HashMap<TaskId, Double>(order.size).apply { for (id in order) put(id, 0.0) }
        val parts = HashMap<TaskId, MutableList<Long>>(order.size).apply {
            for (id in order) put(id, mutableListOf())
        }
        var last: TaskId? = null
        var guard = 0
        while (order.any { (remaining[it] ?: 0.0) > CRUMB } && guard++ < MAX_CYCLE_SLOTS) {
            val live = order.filter { (remaining[it] ?: 0.0) > CRUMB }
            val name = pickNeediest(v, live, p, last) ?: break
            var c = minOf(chunkMillis(name, v, live, p, boost = null), remaining[name]!!)
            if (remaining[name]!! - c < (minimumOf[name] ?: 0L).toDouble()) c = remaining[name]!! // no crumb
            if (c <= 0.0) break
            parts[name]!! += c.roundToLong()
            v[name] = v[name]!! + c / (p[name] ?: 1.0)
            remaining[name] = remaining[name]!! - c
            last = name
        }

        val slots = mutableListOf<PlanSlot>()
        last = null
        while (order.any { parts[it]!!.isNotEmpty() }) {
            val left = order.filter { parts[it]!!.isNotEmpty() }
            val pool = left.filter { it != last }.ifEmpty { left }
            // the densest arrangement of what the clock allotted: whoever has the most turns still to take
            val name = pool.maxWithOrNull(
                compareBy<TaskId> { parts[it]!!.size }.thenBy { p[it] ?: 0.0 }
                    .thenByDescending { it.value },
            ) ?: break
            slots += PlanSlot(name, parts[name]!!.removeAt(0))
            last = name
        }
        return slots
    }

    /**
     * One slot per task: the same minimal period and the same exact shares, but the coarsest possible
     * interleaving. Used when the fine cycle needs too many rules to stay a practical, finite list.
     */
    fun coarseCycle(
        allowed: Collection<TaskId>,
        shares: Map<TaskId, Double> = sharesOf(allowed),
    ): List<PlanSlot> {
        if (allowed.isEmpty()) return emptyList()
        val p = shares
        val period = periodOf(allowed, p)
        return allowed.sortedWith(compareByDescending<TaskId> { p[it] ?: 0.0 }.thenBy { it.value })
            .map { PlanSlot(it, ((p[it] ?: 0.0) * period).roundToLong()) }
    }

    // ----- the plan -----------------------------------------------------------------------------

    /**
     * `side-dev/scheduler_logic.py` `Scheduler.plan`, verbatim:
     *
     * - **Phase 1** walks the disturbed part of the timeline (pre-placed blocks, periods, and the tail of the
     *   influence field) with the field-aware greedy → `prefix`;
     * - **Phase 2**, once nothing is left to distort the schedule, settles what is still owed → `prefix`, then
     *   attaches the analytic cycle → `cycle`.
     *
     * [maxRules] is the "strict maximum rule limit" of `side-dev/README.md`: a cycle needing more rules falls
     * back to [coarseCycle], and a prefix that fills the budget before the context ever freezes returns with
     * **no cycle at all** (a truncated timeline).
     *
     * [history] is the timeline already committed before [nowMillis] — what a *resumed* plan must continue.
     * Together with [replayClocks], [lastRun] and [lookbackStart] it is what makes a chain of re-plans and one
     * long plan of the same environment the same schedule.
     */
    fun plan(
        blocks: List<PlanBlock> = emptyList(),
        windows: List<PlanWindow> = emptyList(),
        nowMillis: Long = 0L,
        lookbackMillis: Double? = null,
        maxRules: Int = MAX_RULES,
        history: List<PlanBlock> = emptyList(),
    ): Plan {
        val sorted = blocks.sortedBy { it.startMillis }

        val past = sorted.filter { it.endMillis <= nowMillis } + history.filter { it.startMillis < nowMillis }
        val ahead = sorted.filter { it.endMillis > nowMillis }
        // Only obstacles still AHEAD bend the plan: what already happened is history, not a blockage the
        // timeline has to be compensated around. The past reaches the walk through the clocks instead.
        setField(ahead, windows)

        // The clocks are replayed over TWO periods, not one. A period is exactly the spacing of one task's
        // slots, so a window one period wide samples the past at the rate the schedule repeats at: whatever
        // the phase, a task whose slot has just left the window and whose next one has not yet entered it
        // reads as NEVER SERVED, and a task that reads as never served claims the maximum however small its
        // priority. Two periods cannot alias — a task's slot count in the window is one or two, never none.
        val want = lookbackMillis ?: (2.0 * minPeriodMillis)
        var windowStart = lookbackStart(windows, nowMillis, want)
        past.minOfOrNull { it.startMillis }?.let { windowStart = maxOf(windowStart, it) }
        windowStart = minOf(windowStart, nowMillis)

        val walk = PlanWalk(this, replayClocks(past, windows, nowMillis, windowStart))
        walk.clamp(nowMillis)

        val slots = mutableListOf<PlanSlot>()
        var t = nowMillis
        var freeTail = false
        var steps = 0
        // Safety valve: slots are merged, so their count no longer bounds the walk.
        val maxSteps = MAX_STEPS_PER_RULE * maxRules

        // `_head`: the run still in progress at `nowMillis`, and how much of its minimum it has already
        // served. The walk continues that run rather than starting a fresh one.
        val head = headRun(past, nowMillis)
        // `_last_run`, deliberately NOT `_head`: a run SURVIVES an idling interruption (which is what `_head`
        // reads, and what the atomic block is about), but `last` is the other rule — a task is not picked
        // twice in a row — and the walk clears it at every gap it pushes. A task that stopped and was not
        // replaced never took a second turn, so nothing is owed on its account. Reading `last` off `_head`
        // instead makes a RESUMED plan refuse the very task the timeline left off with, however far behind it
        // is: on a timeline that re-plans at every boundary the rightful pick then loses a slot at every break.
        walk.setLast(lastRun(past, nowMillis))

        // Every instant the environment can change at, sorted once: the walk asks about them constantly, and
        // with a three-day timeline there are many.
        val allBounds = boundsAfter(ahead, windows, nowMillis)
        var boundIndex = 0
        // The instants a rival comes back from an exclusion: a run may not be in progress across one of them
        // unless it was already running.
        val walls = wallsOf(ahead, windows)

        /**
         * `side-dev/scheduler.py` `Walk.run`'s `pending`: **the chunk still in progress** — the task, what is
         * left of it, and the candidate set it was decided against.
         *
         * The last of those three is the whole of the atomic block's other half. The run continues while its
         * task is still a candidate and its chunk is unpaid — *but only while the field it was decided
         * against has not WIDENED*. A task that started inside a period only it was allowed in has no claim
         * on the time after the rivals come back: carrying its minimum out past the window would not merely
         * use the period, it would lengthen everybody else's ban. A set that SHRANK, or one that came back
         * the same after an interval that accepted nobody, is the atomic block and does resume.
         */
        var pending: Triple<TaskId, Double, Set<TaskId>>? =
            head?.let { (id, served) ->
                val minimum = (minimumOf[id] ?: 0L).toDouble()
                if (served.toDouble() < minimum) Triple(id, minimum - served.toDouble(), share.keys.toSet()) else null
            }

        // --- phase 1: the disturbed part of the timeline ---
        //
        // A literal port of `side-dev/scheduler.py` `Walk.run`. Two rules of the README are structural here
        // rather than checked afterwards: NO IDLING — wherever the candidate set is non-empty somebody is
        // placed, always; and an interval that accepts NOBODY suspends the run in progress instead of ending
        // it, so a 20-second look-away every twenty minutes does not make a 45-minute minimum unschedulable.
        while (slots.size < maxRules && steps < maxSteps) {
            steps++
            val limit = nextBoundary(ahead, windows, t)
            val fieldEnd = fieldEndMillis
            // A run suspended by a period it is banned from is not finished with the timeline, so the walk may
            // not stop at the last boundary while it still owes its minimum.
            if (limit == null && (fieldEnd == null || t >= fieldEnd) && pending == null) break

            // The environment is read at the MIDPOINT of the segment, exactly as the reference reads it —
            // which is what makes an open-started period (the README's dragged 20 seconds, the half-open
            // `(t_p, t_p + 20s]`) mean what it says.
            val next = limit ?: (t + (2.0 * minPeriodMillis).roundToLong().coerceAtLeast(1L))
            val mid = t + (next - t) / 2

            val block = blockAt(ahead, mid)
            if (block != null && block.taskId !in minimumOf) {
                // A pre-placed block owned by nobody schedulable: it suspends, exactly as an all-refusing
                // period does. `last` stands and the run in progress is untouched.
                pushSlot(slots, block.taskId, next - t)
                t = next
                freeTail = false
                walk.clamp(t)
                continue
            }

            val weights = weightsAt(ahead, windows, mid)
            val candidates = candidatesAt(weights, permittedAt(ahead, windows, mid))
            if (candidates.isEmpty()) {
                pushSlot(slots, null, next - t)
                t = next
                freeTail = false
                walk.clamp(t)
                continue
            }
            // The percentages the walk races on here are the EFFECTIVE ones — each task's share after its
            // resilience to the kinds in force, renormalized over whoever is left.
            val local = localSharesOf(weights, candidates)

            val held = pending
            val name: TaskId
            var left: Double
            val here = candidates.toSet()
            // `not set(cand) > pending[2]` — a STRICT superset is the only thing that ends the run.
            val widened = here.size > held?.third.orEmpty().size && here.containsAll(held?.third.orEmpty())
            if (held != null && held.first in candidates && held.second > 0.0 && !widened) {
                name = held.first
                left = held.second
            } else {
                val picked = walk.pick(candidates, avoidLast = true, shares = local) ?: break
                name = picked
                left = walk.chunkMillis(picked, candidates, boostAt(picked, t), shares = local)
            }

            val stop = minOf(t + left.roundToLong().coerceAtLeast(1L), next)
            val served = stop - t
            if (served <= 0L) break
            pushSlot(slots, name, served)
            // `side-dev/scheduler.py`: `v[name] += served / w[name]` — charged against the task's EFFECTIVE
            // weight, at the plain rate. The field lengthens the slot; it does not also discount its cost.
            walk.serveWeighted(name, served.toDouble(), weights[name] ?: 0.0)
            // `side-dev/scheduler.py` `Walk._relax`: `T = self.min_period` — the GLOBAL period, never one
            // renormalized over whoever happens to be a candidate here. The band an excluded group is held
            // within is a property of the whole rule state, not of the stretch being walked.
            walk.relax(served.toDouble(), minPeriodMillis, candidates)
            left -= served.toDouble()
            pending = if (left > CHUNK_EPSILON_MILLIS) Triple(name, left, candidates.toSet()) else null
            t = stop
            freeTail = true
            walk.clamp(t)
        }

        // --- phase 2: settle what is still owed, then repeat forever ---
        var cycle = emptyList<PlanSlot>()
        val tailWeights = weightsAt(ahead, windows, t)
        val allowed = candidatesAt(tailWeights, permittedAt(ahead, windows, t))
        // The shares the steady state is built on are the EFFECTIVE ones where a period is still in force
        // at the tail — a task half-resilient to it keeps half its percentage for as long as it lasts.
        val tailShares = localSharesOf(tailWeights, allowed)
        // A cycle may only be attached once nothing is owed: an atomic block still running is not a steady state.
        if (allowed.isNotEmpty() && slots.size < maxRules && pending == null) {
            val period = periodOf(allowed, tailShares)
            val horizon = t + (SETTLE_PERIODS * period).roundToLong() // settling is bounded
            while (slots.size < maxRules && t < horizon) {
                if (walk.spread(allowed) <= period) break // square again
                val name = walk.pick(allowed, avoidLast = true, shares = tailShares) ?: break
                val boost = boostAt(name, t)
                val placed =
                    walk.chunkMillis(name, allowed, boost, shares = tailShares).roundToLong().coerceAtLeast(1L)
                pushSlot(slots, name, placed)
                walk.serveWeighted(name, placed.toDouble(), tailWeights[name] ?: 0.0)
                t += placed
                walk.relax(placed.toDouble(), period, allowed)
                walk.clamp(t)
            }
            cycle = steadyCycle(allowed, tailShares)
            if (cycle.size > maxRules) cycle = coarseCycle(allowed, tailShares)
            cycle = phaseCycle(cycle, walk, allowed)
        }

        val (prefix, tidied) = tidy(slots, cycle)
        return Plan(startMillis = nowMillis, prefix = prefix, cycle = tidied)
    }

    /**
     * `_lookback_start`: how far back the clocks are replayed — [wantMillis] of **SCHEDULABLE** time.
     *
     * The window is what the past is read off, and it is measured in the only currency the shares are about:
     * time somebody could actually have been served. Measuring it in wall time instead lets an instant nobody
     * may run in push real service out of the window, and then a task served just before the last long
     * exclusion reads as **never served** and leapfrogs the one that has been waiting. Over a timeline whose
     * nights take nine hours out of every twenty-four that is not a rounding error: in `side-dev` test 12 it
     * was the difference between a 50 %-priority task getting 2 % of the schedulable timeline and 35 % of it.
     *
     * Faithful to the reference, it counts **periods only** — a stretch occupied by a block owned by nobody
     * (OmniApp's scheduled sleep) is not stepped over.
     */
    fun lookbackStart(windows: List<PlanWindow>, nowMillis: Long, wantMillis: Double): Long {
        if (wantMillis <= 0.0) return nowMillis
        val edges = HashSet<Long>().apply {
            for (w in windows) {
                if (w.startMillis < nowMillis) add(w.startMillis)
                w.endMillis?.let { if (it < nowMillis) add(it) }
            }
        }.sortedDescending()
        var got = 0.0
        var cur = nowMillis
        for (b in edges) {
            val span = (cur - b).toDouble()
            if (allowedAt(windows, b).isNotEmpty()) {
                if (got + span >= wantMillis) return cur - (wantMillis - got).roundToLong()
                got += span
            }
            cur = b
        }
        // before the earliest edge nothing changes any more
        if (edges.isEmpty() || allowedAt(windows, cur - 1).isNotEmpty()) {
            return cur - (wantMillis - got).roundToLong()
        }
        return cur
    }

    /**
     * `_replay_clocks`: the virtual clocks at [nowMillis], **REPLAYED the way the walk writes them**.
     *
     * A plan resumed at `t` must continue the plan that walked through `t`: a chain of re-plans and one long
     * plan of the same environment are the same schedule, and the only thing that can make them differ is
     * state the walk carries but the seeding does not reconstruct. `last` was one such carrier
     * ([lastRun]) and the lookback's currency was another ([lookbackStart]); the third is the FORGETTING
     * itself. The walk relaxes after every slot — an imbalance older than a period decays exponentially, and
     * an excluded group is translated back toward the pool — so reading the past as a flat sum of `served/p`
     * over a window rebuilds a state the walk never held: a task idle for an hour reads as owed the whole
     * hour, where the walk had already forgotten most of it.
     *
     * The remedy is not a different window but the same law: walk the past and apply [PlanWalk.relax] exactly
     * where the walk applies it — over served time, with that stretch's own period, against whoever was
     * allowed to run in it. Idling relaxes nothing, as in the walk.
     *
     * The replay is **edge by edge, not block by block**. A block in the history is a run the walk merged, and
     * a period may well begin inside one: fifteen privileged-only minutes a privileged task works straight
     * through leave no mark on the timeline at all, and reading the allowed set once at the block's start
     * would miss them entirely — with them the whole excluded group's credit, which is the largest single term
     * [PlanWalk.relax] contributes.
     *
     * With [PlanWalk.relax] removed this is `served/p` normalized, the arithmetic the seeding did before, so
     * the window still bounds how far back it reads and a case with no history still starts every clock level.
     */
    fun replayClocks(
        past: List<PlanBlock>,
        windows: List<PlanWindow>,
        nowMillis: Long,
        windowStartMillis: Long,
    ): Map<TaskId, Double> {
        val seed = HashMap<TaskId, Double>(share.size)
        for (id in share.keys) seed[id] = windowStartMillis.toDouble()
        val walk = PlanWalk(this, seed)
        val blocks = past.mapNotNull { b ->
            val id = b.taskId
            if (id == null || id !in share) return@mapNotNull null
            val s = maxOf(b.startMillis, windowStartMillis)
            val e = minOf(b.endMillis, nowMillis)
            if (e > s) Triple(s, e, id) else null
        }.sortedBy { it.first }
        val edges = HashSet<Long>().apply {
            for (w in windows) {
                if (w.startMillis > windowStartMillis && w.startMillis < nowMillis) add(w.startMillis)
                w.endMillis?.let { if (it > windowStartMillis && it < nowMillis) add(it) }
            }
        }.sorted()
        for ((blockStart, blockEnd, id) in blocks) {
            var t = blockStart
            while (t < blockEnd) {
                val i = upperBound(edges, t)
                val stop = if (i < edges.size) minOf(blockEnd, edges[i]) else blockEnd
                // `side-dev/scheduler.py` `Walk._seed`: the placement is charged against the EFFECTIVE
                // weight it was served at — `weight = w[pl.task] or p[pl.task]` — and relaxed against the set
                // that was ACTUALLY racing then, read at the placement's midpoint. Replaying either of those
                // wrong is what makes a resumed plan drift from the one long plan it must continue.
                val mid = t + (stop - t) / 2
                val weights = weightsAt(emptyList(), windows, mid)
                val active = share.keys.filter { (weights[it] ?: 0.0) > 0.0 }.ifEmpty { share.keys.toList() }
                val weight = (weights[id] ?: 0.0).takeIf { it > 0.0 } ?: (share[id] ?: 0.0)
                val c = (stop - t).toDouble()
                walk.serveWeighted(id, c, weight)
                // The GLOBAL period, exactly as `Walk._relax`'s `T = self.min_period`.
                walk.relax(c, minPeriodMillis, active)
                t = stop
            }
        }
        // The clocks are read for their DIFFERENCES; anchoring the earliest at `now` is what the seeding has
        // always done, and it is what keeps [PlanWalk.clamp]'s band meaningful.
        val out = walk.clocks()
        val base = out.values.minOrNull() ?: 0.0
        return out.mapValues { (_, x) -> nowMillis + x - base }
    }

    /** `bisect_right`: the index of the first entry of [sorted] strictly greater than [value]. */
    private fun upperBound(sorted: List<Long>, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** A new walk over this planner's rules, seeded with [clocks] (defaults to a level playing field). */
    fun walk(clocks: Map<TaskId, Double> = share.mapValues { 0.0 }): PlanWalk = PlanWalk(this, clocks)

    /**
     * `_head`: the run still in progress at the end of [past] — the task, and how long it has been served —
     * or null when the past ends on nothing of ours.
     *
     * A run survives an interruption by something that is not a task (an idle stretch, a block owned by
     * nobody): the README's atomic block is about a task's *service*, and a period that accepts nobody
     * suspends a run rather than ending it. So the scan skips those and stops at the first different task.
     */
    internal fun headRun(past: List<PlanBlock>, nowMillis: Long): Pair<TaskId, Long>? {
        var name: TaskId? = null
        var total = 0L
        for (block in past.sortedByDescending { it.startMillis }) {
            val id = block.taskId
            if (id == null || id !in minimumOf) continue // not one of ours: it does not end the run
            if (name == null) name = id else if (id != name) break
            total += minOf(block.endMillis, nowMillis) - block.startMillis
        }
        return name?.let { it to total }
    }

    /**
     * `_last_run`: the task the walk carries as `last` — the one running at the instant before [nowMillis],
     * and **nothing** where the past ends in idling.
     *
     * This is deliberately not [headRun]. A run SURVIVES an idling interruption, which is what [headRun] reads
     * and what the atomic block is about; `last` is the other rule — a task is not picked twice in a row so
     * that what is owed a lot gets a denser presence rather than one long block — and the walk clears it at
     * every gap it pushes. A task that stopped and was not replaced never took a second turn.
     */
    internal fun lastRun(past: List<PlanBlock>, nowMillis: Long): TaskId? {
        var best: PlanBlock? = null
        for (block in past) {
            if (block.startMillis >= nowMillis || minOf(block.endMillis, nowMillis) < nowMillis) continue
            if (best == null || block.startMillis > best.startMillis) best = block
        }
        val id = best?.taskId ?: return null
        return if (id in minimumOf) id else null
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
     * `side-dev/scheduler_logic.py` `Scheduler._claims`: what each of [names] is OWED, counted in its OWN
     * slots.
     *
     * The virtual clock `v = served/p` is a time, so comparing two of them directly asks "who has had least
     * per unit of priority" — the right question where service is continuous. It is not the question here,
     * because service is QUANTIZED: nobody may be served less than its own minimum, so one slot moves a task's
     * clock by a whole period of its own, `T = m/p`, and those periods differ by the priority ratio. With A at
     * 50% against twenty tasks at 2.5%, all of 45 minutes, A's slot costs it 90 minutes of clock and each of
     * theirs costs 1800.
     *
     * Read raw, that says every one of the twenty (still at 0) outranks A the moment A has taken a single
     * slot — and they take twenty slots in a row before A's second. That is `side-dev/README.md`'s monolithic
     * block assembled out of twenty tasks instead of one, and the deficit it opens is never repaid, because
     * the pick will not run A twice in a row and [roundUnitMillis] will not let its slot grow: A can hold its
     * half from then on but can never catch up. Measured in `side-dev` test 14: 35% of three days against a
     * target of 50%, and 5% of the first day.
     *
     * So the claim is the lag divided by the task's own period — the number of ITS OWN slots it is behind:
     *
     *     claim = (V − v) / T = (V − v)·p / m
     *
     * where `V` is the priority-weighted mean of the clocks: the point every one of them would sit at if the
     * service so far had been exactly proportional (`Σ p·v` is `Σ served`, the time actually served, so the
     * reference needs no new state — it is a function of the same clocks). Equivalently the claim is the
     * task's real-time deficit `p·(V − v)` divided by the size of the slot that would repay it: a task 0.025
     * of a slot behind does not outrank one half a slot ahead.
     *
     * Where every task shares one period `m/p` this is a monotone transform of `v` and picks exactly what the
     * raw clock picked; it only parts company where the periods differ, which is where the raw clock was
     * wrong. Renormalising [shares] over a subset scales every claim by one factor and so cannot change the
     * order.
     */
    internal fun claims(
        clocks: Map<TaskId, Double>,
        names: List<TaskId>,
        shares: Map<TaskId, Double>,
    ): Map<TaskId, Double> {
        val weight = names.sumOf { shares[it] ?: 0.0 }
        if (weight <= 0.0) return names.associateWith { 0.0 }
        val mean = names.sumOf { (shares[it] ?: 0.0) * (clocks[it] ?: 0.0) } / weight
        return names.associateWith { id ->
            val minimum = (minimumOf[id] ?: 0L).toDouble()
            if (minimum <= 0.0) 0.0 else (mean - (clocks[id] ?: 0.0)) * (shares[id] ?: 0.0) / minimum
        }
    }

    /**
     * The most starved task — the biggest [claims]; ties resolve by biggest share and then by the order
     * [candidates] arrives in, so the walk is deterministic. Shared by [PlanWalk.pick] and [steadyCycle].
     */
    internal fun pickNeediest(
        clocks: Map<TaskId, Double>,
        candidates: List<TaskId>,
        shares: Map<TaskId, Double>,
        last: TaskId?,
        precomputed: Map<TaskId, Double>? = null,
    ): TaskId? {
        if (candidates.isEmpty()) return null
        // `last` is never picked twice in a row unless it is the only one left: a task that is owed a lot gets
        // a *denser* presence, not one long block that would swallow the whole compensation at once.
        val pool = candidates.filter { it != last }.ifEmpty { candidates }
        // Deviation from the reference: zero-priority tasks are kept out of the share model, so they are only
        // considered when nothing with a real share is available.
        val real = pool.filter { it in share }
        val effective = real.ifEmpty { pool }
        val claim = precomputed ?: claims(clocks, candidates, shares)
        var best: TaskId? = null
        var bestClaim = 0.0
        var bestShare = 0.0
        for (id in effective) {
            val owed = claim[id] ?: 0.0
            val sh = shares[id] ?: 0.0
            val better =
                when {
                    best == null -> true
                    owed > bestClaim + TIE_EPSILON -> true
                    owed < bestClaim - TIE_EPSILON -> false
                    // Ties resolve by the biggest share and then by the order [candidates] arrives in — which
                    // is the caller's deterministic tie-break (PRD §9: higher priority, then title).
                    else -> sh > bestShare
                }
            if (better) {
                best = id
                bestClaim = owed
                bestShare = sh
            }
        }
        return best
    }

    /**
     * `side-dev/scheduler_logic.py` `Scheduler._round`: how much [rival] leaves for the task racing it — the
     * SCALE of a slot, which "catch the runner-up" does not fix on its own.
     *
     * A share is a ratio and a ratio holds at any scale, so a task owed twenty of its rival's slots would
     * otherwise take them as one block twenty times as long: the percentages come out exact and the answer is
     * still the one `side-dev/README.md` refuses ("A 1h, B 1h" for "A 10min, B 10min"). The rule against that
     * is [pickNeediest]'s — never the same task twice in a row — and one long chunk walks straight past it,
     * because a slot is one pick however long it is.
     *
     * So the scale comes from the only quantity in sight that has one, the RIVAL's own minimum: over a round
     * in which each runs once, `c / (c + m) = p`, hence `c = p·m / (1 − p)`. A at 50% takes exactly one
     * 45-minute slot against a 45-minute rival and comes back every other one; A at 90% takes 405 minutes
     * against the same rival. It replaces `p·T` (the share of a whole *period*), which is the same number
     * wherever there are two tasks and far too large wherever there are more — a period is the spacing of the
     * SLOWEST task's slots, so with twenty rivals it licensed twenty rounds at once, and a run longer than a
     * minimum has interior instants at which a re-plan picks somebody else (the resume contract).
     *
     * Null where there is nobody to race, or where the task is the whole of the share.
     */
    private fun roundUnitMillis(rival: TaskId?, share: Double): Double? {
        if (rival == null || share >= 1.0) return null
        return share * (minimumOf[rival] ?: 0L).toDouble() / (1.0 - share)
    }

    /**
     * How long the next slot of [name] should be: enough to catch up with the runner-up, never below the
     * minimum, and never past its share of one round ([roundUnitMillis]). With a [boost] the sizing is
     * field-aware — the slot may grow up to `boost ×` that unit, which is how the compensation around an
     * exclusion is delivered.
     */
    internal fun chunkMillis(
        name: TaskId,
        clocks: Map<TaskId, Double>,
        candidates: List<TaskId>,
        shares: Map<TaskId, Double>,
        boost: Double?,
        // `side-dev/scheduler_logic.py` `floor=owed(name) or minimum[name]`: a task RESUMING a run only owes
        // what is left of its minimum, so flooring it at the whole minimum again would over-serve it. Null
        // (the default) is the reference's own fallback — the full minimum, for a slot that is starting.
        floorMillis: Double? = null,
    ): Double {
        val p = shares[name] ?: 0.0
        val full = (minimumOf[name] ?: 0L).toDouble()
        val floor = floorMillis ?: full
        if (p <= 0.0) return floor // a zero-priority task has no catch-up to compute
        val mine = clocks[name] ?: 0.0
        // Only tasks that carry a share have a virtual clock, so only they can be the runner-up to catch.
        val others = candidates.filter { it != name && it in clocks }
        val target = others.minOfOrNull { clocks[it]!! } ?: mine
        val need = p * (target - mine) // time to catch the runner-up
        var c = maxOf(floor, need)
        // The field LIFTS and the round CAPS, and they are asked separately because they answer different
        // questions: a boost is owed to this task whether or not anybody is there to race it, while a round is
        // measured against a rival and means nothing without one. Asking them together loses the lift exactly
        // where the atomic block puts it — a task still short of its minimum is the ONLY candidate, so there
        // are no others, and its resumed slot would fall back to the bare minimum.
        val b = boost ?: 1.0
        if (boost != null) c = maxOf(c, floor * b)
        // Who runs next if this chunk ends here — [pickNeediest]'s own answer among the others, since it
        // is the one the chunk is measured against, so it is read off the same claim the pick orders by —
        // over the whole candidate set, which is what that claim's reference clock is a mean of.
        val rival = pickNeediest(clocks, others, shares, last = null, precomputed = claims(clocks, candidates, shares))
        roundUnitMillis(rival, p)?.let { round ->
            // The natural unit is a property of the TASK (its whole minimum, or its share of a round), not of
            // what this particular slot still owes — and the cap may never push the slot below its floor.
            c = minOf(c, maxOf(maxOf(full, round) * b, floor))
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

    /**
     * One contiguous stretch a task was deprived in, and the **cost** it carries: `Σ (1 − mult)·length` over
     * the pieces of it. A flat refusal costs the whole length; a period the task is half-resilient to costs
     * half of it. That is the quantity the field's amplitude is read off ([setField]).
     */
    data class Deprivation(val startMillis: Long, val endMillis: Long, val costMillis: Double)

    companion object {
        /** An end that never comes — the `FOREVER` of `side-dev/scheduler_logic.py`, kept in `Long` arithmetic. */
        const val FOREVER: Long = Long.MAX_VALUE

        /** `side-dev/scheduler.py` `MAX_RULES`: the strict rule limit of `side-dev/README.md`. */
        const val MAX_RULES: Int = 50

        /**
         * `side-dev/scheduler.py` `EPS`: below this a chunk is finished, not merely nearly finished. Kept in
         * millis because the port runs in `Double` millis where the reference keeps exact rationals.
         */
        const val CHUNK_EPSILON_MILLIS: Double = 1e-3

        const val DEFAULT_MAX_BOOST: Double = 6.0

        const val DEFAULT_FIELD_FLOOR: Double = 0.1

        /** `max_reach` defaults to six `tau` in the reference. */
        const val DEFAULT_REACH_TAUS: Double = 6.0

        /** `side-dev/scheduler_logic.py`: the phase-2 settling is bounded to four periods. */
        const val SETTLE_PERIODS: Double = 4.0

        private const val MAX_STEPS_PER_RULE = 200

        /** Enough to build any sane fine cycle; guards a float pathology from spinning [steadyCycle]. */
        private const val MAX_CYCLE_SLOTS = 10_000

        /** Below this many millis a remaining budget is exhausted (the reference compares exact rationals). */
        private const val CRUMB = 0.5

        /** Two claims this close are the same number, so the tie-break decides (see [pickNeediest]). */
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
 * This is the whole of `side-dev/scheduler_logic.py`'s scheduling logic, and it is deliberately the **only** copy of it:
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

    /** A copy of the clocks as they stand — what [SchedulerPlanner.replayClocks] reads its replay off. */
    fun clocks(): Map<TaskId, Double> = HashMap(v)

    /**
     * `side-dev/scheduler_logic.py` `plan`'s `last = self._last_run(past, t_now)`: the task the walk resumes
     * behind. Set once, from the history, before the walk starts.
     */
    fun setLast(id: TaskId?) {
        last = id
    }

    /** `max v − min v` over [active]: how far from square the schedule is. */
    fun spread(active: Collection<TaskId>): Double {
        val live = active.filter { it in v }
        if (live.isEmpty()) return 0.0
        return live.maxOf { v[it]!! } - live.minOf { v[it]!! }
    }

    /**
     * The most starved of [candidates]; see [SchedulerPlanner.pickNeediest]. [shares] defaults to the
     * nominal percentages and is handed the **locally renormalized** ones
     * ([SchedulerPlanner.localSharesOf]) wherever a restrictive period is in force.
     */
    fun pick(
        candidates: List<TaskId>,
        avoidLast: Boolean = true,
        shares: Map<TaskId, Double> = planner.share,
    ): TaskId? = planner.pickNeediest(v, candidates, shares, if (avoidLast) last else null)

    /**
     * `side-dev/scheduler.py` `Walk._alternative` — **the README's *alternative schedule***: who runs from
     * here instead, if [chosen] turns out not to be runnable now. It is the same ordering the pick uses, over
     * the same claims, minus the pick itself; empty where there is nobody else, since an answer of "the same
     * task again" would be no answer at all.
     */
    fun alternative(
        candidates: List<TaskId>,
        chosen: TaskId?,
        shares: Map<TaskId, Double> = planner.share,
    ): TaskId? {
        val pool = candidates.filter { it != chosen }
        if (pool.isEmpty()) return null
        return planner.pickNeediest(v, pool, shares, last = null, precomputed = planner.claims(v, candidates, shares))
    }

    /** How long [id]'s next slot should be; see [SchedulerPlanner.chunkMillis]. */
    fun chunkMillis(
        id: TaskId,
        candidates: List<TaskId>,
        boost: Double? = null,
        floorMillis: Double? = null,
        shares: Map<TaskId, Double> = planner.share,
    ): Double = planner.chunkMillis(id, v, candidates, shares, boost, floorMillis)

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

    /**
     * `side-dev/scheduler.py`'s own charge, `v[name] += served / w[name]`: [durationMillis] booked against the
     * task's **effective weight** at the instant it was served — its percentage after resilience, not its
     * nominal one.
     *
     * That is the whole of what a resilience below one costs a task. Inside a period it is only half-resilient
     * to, an hour of service costs it twice the clock, so it comes back half as often for as long as the
     * period lasts — which is what "a multiplier for the task's priority percentage during that restrictive
     * period" means, rather than "the same alternation, one boundary later". Where no period is in force the
     * effective weight IS the nominal share and this is [serve] with no boost.
     */
    fun serveWeighted(id: TaskId?, durationMillis: Double, weight: Double) {
        if (id != null && weight > 0.0) v[id] = (v[id] ?: 0.0) + durationMillis / weight
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
        // An excluded task's clock is held within one period of the served pool — an imbalance older than that
        // is forgotten, which is what stops a long exclusion from buying an equally long catch-up. It is held
        // by TRANSLATING the whole excluded set, never by clamping each of them separately: clamping sets
        // every task past the bound to the SAME value, so a period that refuses eleven tasks would erase what
        // distinguishes them and their priorities would stop deciding which one goes first. The bound is a
        // property of the group's distance from the pool; the gaps inside the group are the claims themselves.
        val activeSet = live.toHashSet()
        val idle = v.keys.filter { it !in activeSet }
        if (idle.isEmpty()) return
        val low = (lo - periodMillis) - idle.minOf { v[it]!! }  // the shift the credit cap asks for
        val high = (lo + periodMillis) - idle.maxOf { v[it]!! } // the one the debt cap allows
        // A group spread wider than 2·period cannot sit inside the band at all; the credit cap is the
        // load-bearing half, so it wins.
        val shift = if (low > high) low else maxOf(low, minOf(high, 0.0))
        if (shift != 0.0) for (id in idle) v[id] = v[id]!! + shift
    }

    /**
     * Optional hard bound on the virtual clock ([SchedulerPlanner.maxLagMillis]); off by default. The clocks
     * are anchored on the timeline itself ([SchedulerPlanner.replayClocks] puts the most starved task at
     * `now`), so [millis] is the cursor's absolute position, exactly as in the reference.
     */
    fun clamp(millis: Long) {
        val lag = planner.maxLagMillis ?: return
        val t = millis.toDouble()
        for (id in v.keys.toList()) v[id] = v[id]!!.coerceIn(t - lag, t + lag)
    }
}
