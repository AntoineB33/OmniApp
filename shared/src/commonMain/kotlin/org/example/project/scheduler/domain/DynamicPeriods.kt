package org.example.project.scheduler.domain

/**
 * `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period* — the whole of where the **20 s**, the
 * **5 min** and the **15 min** periods go, ported from `side-dev/scheduler.py`'s `DynamicPlanner`.
 *
 * ### What changed, and why the three are not what they were
 * They used to be three differently-shaped objects: the look-away accepted nobody, the 5-minute pose had a
 * closed first minute and then accepted the "doable during a screen break" tasks, and the 15-minute pose
 * accepted every off-screen task. The README allows none of that — **all three have the kind
 * [PeriodKinds.INACTIVITY]**, end to end — and their *placement* is no longer a cadence off a "last rest"
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
 * *covered by* "no on-screen task" — a period of that kind, or [PeriodKinds.INACTIVITY], which turns the
 * on-screen tasks away a fortiori; *without any task* — so a period that still accepts somebody makes none
 * at all (the no-idling rule puts a task there), and neither does a pre-placed block, since a pre-placed task
 * IS a task; and it is a **stretch**, not a period — two that abut make one ([growStretch]).
 *
 * `blocked` and `rested` are deliberately two different sets. Everywhere nothing can be placed a dynamic
 * period is pointless and is pushed past; only the part of that which is a rest stretch bars what comes after
 * it. A pre-placed hour of maintenance is not a rest: the user was at the screen the whole time.
 *
 * ### The look-away is assumed taken; a pose is owed
 * When the line reaches a **pose** the user has not taken, [MODE_AT_SCREEN] drags it: `t_p` may not be covered
 * by a dynamic period there, so the period is pushed onto the line and goes on being pushed until a real rest
 * happens. The two AWAY modes do not — the line must BE covered there ([lineIsCoveredAt]), so the pose elapses
 * under the line and is frozen into the past behind it. When the line reaches the **20 s look-away** nothing
 * is dragged in any mode ([dragsAtLine]) — looking twenty feet away costs no working time, so the app assumes
 * it is being done. The occurrence stays where the bars put it, the line walks across it, and behind the line
 * it goes on being drawn where it happened.
 *
 * ### A chain of "no on-screen task" TAKES the break that falls due in it
 * `docs/scheduler_requirements.md`, last bullet of § *3 Dynamic Restrictive Period*: *"When a 'no on-screen
 * task' period touches the start of a dynamic restrictive period, and that this chain of 'no on-screen task'
 * periods ends somewhere in $[now line;+infinity)$, then the dynamic restrictive period now starts at the start
 * of this chain."* ([chainTaking]). The time already spent away COUNTS towards the break that falls due inside
 * it, which is what keeps a break from being owed all over again the moment the user comes back — and it is the
 * requirements' one sanctioned exception to the **frozen past**.
 *
 * Three things follow, and each was a way the rule reached nothing at all until 2026-09-10:
 * - **a stretch does not BAR the break it takes** ([barStretch]'s `spared`): the bar is about what comes after
 *   a stretch, and a five-minute pause is exactly long enough both to be a 5-min pose and to bar one, so the
 *   bar cancelled the break the pause WAS;
 * - **the drag puts an owed pose DOWN at the first stretch the line was not at a screen for**, rather than
 *   carrying every pose the day owed to `t_p` — the mode belongs to where the line WAS, and a chain behind the
 *   line is the timeline's own record of it;
 * - and **the chain goes on taking it once the line has left**, because whether a stretch outlasted a break is
 *   a fact of the past. Read as "the chain must reach the line" alone, the break moved out of the pause the
 *   instant the user came back, which is the frozen past broken by a mode flip.
 *
 * A chain gives each of the three ONE occurrence, and bars them like any other rest stretch after that.
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
     * screen, so a POSE the line reaches is pushed ahead of it and never happens until it is taken.
     *
     * The 20 s look-away is exempt ([dragsAtLine]): it is assumed done as it falls due, so the line crosses it
     * in [MODE_ON_BREAK] for its twenty seconds and it stays on the timeline behind the line.
     */
    const val MODE_AT_SCREEN: Int = 1

    /**
     * `t_p` mode 2: *"Mode 2 & 3: $now line$ must be covered by the period 'no on-screen task'"* — no device
     * of the account is unlocked, and nobody has pressed "I'm away".
     *
     * **Its PLACEMENT rule is mode 3's, verbatim.** The requirements state the two together in one clause, so
     * a dynamic period may cover the line here exactly as it may there ([lineIsCoveredAt]): nothing is
     * dragged, the line walks through a break and the break is then an ordinary fact of the frozen past. The
     * gap between the last such period's end and the line is covered by a "no on-screen task" period
     * ([awayCover]), filled with whatever tasks are resilient to that kind and left empty if none are.
     *
     * **What tells it from mode 3 is the CUE, and nothing else** ([breaksAreNotifiedAt]): every screen of the
     * account is locked and nobody has declared a break, so a screen break that falls due here is placed and
     * drawn but never announced — there is no one at a screen to announce it to, and no statement that the
     * break is being taken. Mode 3 is the same silence plus that statement, and it is the one the server
     * announces the end of a break from (`docs/PAUSE_CUE_DELIVERY.md`).
     */
    const val MODE_AWAY: Int = 2

    /**
     * `t_p` mode 3: *"Mode 2 & 3: $now line$ must be covered by the period 'no on-screen task'"*, and that is
     * the WHOLE of the placement rule — the same one [MODE_AWAY] carries.
     *
     * It is mode 2 plus a statement by the user: no computer and no phone is unlocked **and** the "I'm away"
     * button is on ([SchedulerDomain.tpMode]). The PLACEMENT is the same in both — a pose the line reaches is
     * not dragged in either, it elapses under the line, becomes an ordinary fact of the frozen past, and
     * re-anchors the bars off itself like any other rest. What the statement adds is the CUE
     * ([breaksAreNotifiedAt]): mode 3 is the mode a break is announced in while every screen is locked, and
     * the mode the server announces the END of a break from (`docs/PAUSE_CUE_DELIVERY.md`) — the app is not
     * the one watching, its screen is off, so the server moves the line over the rules it was last given and
     * pushes the phone a cue for the instant the break the line is inside finishes.
     *
     * The 20 s look-away is *always* answered this way whatever the mode says, which is the same sentence read
     * from the other end: looking twenty feet away costs no working time, so it is assumed taken as it falls
     * due and the line crosses it — the line is in mode 3 for those twenty seconds ([dragsAtLine]).
     */
    const val MODE_ON_BREAK: Int = 3

    /**
     * **Is the line covered by "no on-screen task" in this mode?** — the ONE predicate the placement reads the
     * mode through, true of mode 2 and mode 3 alike.
     *
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes* states them in one clause — *"Mode 2 & 3: $now
     * line$ must be covered by the period 'no on-screen task'"* — and a dynamic period's kind is
     * [PeriodKinds.INACTIVITY], which covers "no on-screen task" a fortiori. So being covered and being coverable
     * BY ONE OF THE THREE are not two questions: there was a second predicate here (`breaksAreTakenAt`, mode 3
     * only) and it made mode 2 place the three somewhere mode 3 did not, which the requirements no longer say.
     * The two modes differ over the CUE ([breaksAreNotifiedAt]) and over nothing else.
     */
    fun lineIsCoveredAt(mode: Int): Boolean = mode != MODE_AT_SCREEN

    /**
     * **Is a screen break ANNOUNCED in this mode?** — the whole of what tells mode 2 from mode 3, and the one
     * place it is decided ([SchedulerDomain.cueCrossings] reads it).
     *
     * Mode 2 is every screen of the account locked with nobody having said they are taking a break: a break
     * that falls due there is still placed, still drawn and still re-anchors the bars, but there is nobody at
     * a screen to tell and no declaration that the break is being taken, so it is not announced. Mode 1 (the
     * user is at the screen) and mode 3 (the user said they are away, and the server cues the phone) both
     * announce.
     *
     * It is deliberately NOT a second reading of the lock — `SchedulerEngine.deviceUnlocked()` answers *may
     * this device say anything*, per cue and per device; this answers *does the ACCOUNT's mode make this break
     * an announced one*, which is a fact about the schedule and belongs with the placement.
     */
    fun breaksAreNotifiedAt(mode: Int): Boolean = mode != MODE_AWAY

    /**
     * The mode, in words — `docs/scheduler_requirements.md` § *$now line$ 3 modes*, one phrase each.
     *
     * The set of rules the scheduler returns is parameterized by the mode as well as by the line, so anything
     * that reports a rule list has to say which mode it was drawn at (the History window's scheduler rows do,
     * through [org.example.project.scheduler.domain.SchedulerDomain.describeScheduleRules]). Here rather than
     * in the UI so there is one wording of the three.
     */
    fun modeLabel(mode: Int): String =
        when (mode) {
            MODE_AT_SCREEN -> "at a screen"
            MODE_AWAY -> "away, no break taken"
            MODE_ON_BREAK -> "on a break"
            else -> "unknown"
        }

    /** One of the three: a label, how long it lasts, and its own recurrence bar. */
    data class Spec(val label: String, val durationMillis: Long, val cadenceMillis: Long)

    /**
     * One placed occurrence of a [Spec] — where it sits, given this position of the line.
     *
     * There is no "due" field here on purpose. A break's due is where the bars put it with **nothing
     * dragged**, and the drag re-anchors the bars it fires on, so the undragged run is a different sequence
     * and not something an instance of this one could carry. Ask for it by asking the placement with the line
     * left out (`SchedulerDomain.screenBreakOccurrencesBetween`); a second, subtly different reading of "the
     * due" hanging off this object is exactly how the announced instant and the drawn one drift apart.
     */
    data class Instance(
        val spec: Spec,
        val startMillis: Long,
        val openStart: Boolean = false,
    ) {
        val endMillis: Long get() = startMillis + spec.durationMillis
        val durationMillis: Long get() = spec.durationMillis

        /**
         * The README's period: one span of [PeriodKinds.INACTIVITY]. [openStart] carries the half-open
         * `(t_p, t_p + duration]` of a period the line is dragging — the instant `t_p` itself must NOT be
         * covered while every instant after it is.
         */
        fun toPeriod(): RestrictivePeriod =
            RestrictivePeriod(
                startMillis = startMillis,
                endMillis = endMillis,
                kind = PeriodKinds.INACTIVITY,
                label = spec.label,
                openStart = openStart,
                closedEnd = openStart,
            )

        /**
         * The same span as [toPeriod], written as the ordinary half-open `[from, until)` the rest of the app
         * measures panels in. Time is discrete here — the millisecond is the unit — so the half-open
         * `(t_p, t_p + duration]` a dragged period covers IS `[t_p + 1, t_p + duration + 1)`, exactly
         * [durationMillis] long and leaving the instant `t_p` itself free. That equivalence is what lets the
         * README's open-start period become an ordinary `TaskPanel` with no extra field on it.
         */
        val coveredFromMillis: Long get() = if (openStart) startMillis + 1 else startMillis
        val coveredUntilMillis: Long get() = coveredFromMillis + spec.durationMillis
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
     * [sweepFromMillis] is the floor of the drag. The line itself has no such floor: it moves CONTINUOUSLY,
     * so every instant below it is one it has already stood on, and the placement path passes the timeline's
     * own origin — mode 1 **drags**, a pose the line reached is pushed ahead of it and goes on being pushed,
     * so it never happens until it is taken. The floor exists for the callers that are NOT the line: asking
     * where the bars put a break with nothing dragged (the break's **due**) is `sweepFrom = t_p`, which makes
     * the drag's condition unsatisfiable. Never read it as the line having jumped — under the README the line
     * cannot.
     *
     * Only the two poses are dragged ([dragsAtLine]). The 20 s look-away is placed at its due whatever the line
     * is doing, so for a look-away the answer is the same at the line and away from it — which is what makes
     * the break's **due** and where it is **drawn** one instant for that one of the three.
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
        // The README's first bar keys on a dynamic restrictive PERIOD, not on a rest stretch, and the periods
        // it speaks of are not only the ones this walk is about to place: a break the app already CONDUCTED
        // is one, and it is on the timeline as a recorded period ([RestrictivePeriod.dynamic]). Without this
        // the twenty seconds it lasted barred nothing at all — far too short for either stretch bar — so the
        // instant the user finished a look-away the line went straight back to dragging the next one.
        val dynamicSpans =
            mergeSpans(base.periods.filter { it.dynamic }.map { Span(it.startMillis, it.endMillis) })
        // Hoisted out of the loop below, which asks them once per turn ([chainTaking], and the drag's own
        // put-down): they are a function of the environment alone.
        val noScreenChains = noScreenChains(base)

        val byLabel = dynamics.associateBy { it.label }
        val labels = dynamics.map { it.label }
        val bars = HashMap<String, Long>()
        for (spec in dynamics) bars[spec.label] = startMillis + spec.cadenceMillis
        // **A chain gives each of the three ONE occurrence**, and is an ordinary rest stretch to it after
        // that: one pause is one break of each kind, which is also all the chain merge would leave of two
        // placed at the same instant. Without it the break's own re-anchor lands back inside the chain that
        // just took it, is taken again, and the walk crawls forward a millisecond at a time until [MAX_STEPS]
        // stops it.
        val takenFrom = HashMap<String, Long>()
        fun takingChain(label: String, spec: Spec, bar: Long): Span? =
            chainTaking(noScreenChains, spec, bar, tpMillis, mode)
                ?.takeIf { takenFrom[label] != it.startMillis }
        // The README's stretch bars, with the ONE thing a stretch may not bar taken out of them: the
        // occurrence that same stretch is about to TAKE. The bar is about what comes AFTER a stretch, and the
        // break the stretch is the taking of is not after it — while the check has to be made HERE, against
        // every label's current bar, because a stretch bars labels other than the one whose turn round the
        // walk it is. (That is how the anomaly survived a first fix: the 15-min pose's own turn, and then the
        // re-anchor off the look-away placed in the pause, each kicked the 5-min bar out of the pause it was
        // about to be taken by.)
        fun barRestStretch(a: Long, b: Long) {
            val spared = HashSet<String>()
            for (other in labels) {
                val bar = bars[other] ?: continue
                val otherSpec = byLabel[other] ?: continue
                val chain = takingChain(other, otherSpec, bar) ?: continue
                if (chain.startMillis < b && a < chain.endMillis) spared += other
            }
            barStretch(bars, a, b, spared)
        }
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
            // The chain of "no on-screen task" that TOOK this occurrence, if one did ([chainTaking]) — asked
            // BEFORE the bars get to move it, because the chain's own bar must not cancel the break the chain
            // is the taking of. See the two skips below and the placement at the bottom of the loop.
            val taken = takingChain(label, spec, start)
            // A rest stretch bars what comes AFTER it, and any emptiness at all absorbs what would fall
            // inside it — there is nothing for a break to interrupt where nothing is placed. Both are applied
            // in chronological order: a night on the third day cannot delay a break on the first.
            var moved = false
            for (span in restedSpans) {
                if (span.endMillis <= start || (span.startMillis <= start && start < span.endMillis)) {
                    val before = HashMap(bars)
                    barRestStretch(span.startMillis, span.endMillis)
                    if (bars != before) moved = true
                }
            }
            for (span in dynamicSpans) {
                if (span.endMillis <= start || (span.startMillis <= start && start < span.endMillis)) {
                    val before = bars[LABEL_20S]
                    if (before != null) {
                        bars[LABEL_20S] = maxOf(before, span.endMillis + BAR_20S_AFTER_ANY_MILLIS)
                        if (bars[LABEL_20S] != before) moved = true
                    }
                }
            }
            for (span in blockedSpans) {
                // The emptiness that TOOK the break does not also absorb it: the absorption exists to push a
                // break out of a stretch there is nothing for it to interrupt, and this stretch is the break.
                // (Skipping it also keeps the answer from depending on whether the surrounding emptiness
                // reaches further than the no-screen chain does — pushed to the end of a LONGER blocked span,
                // the slot would no longer touch the chain that took it.)
                if (taken != null && span.startMillis < taken.endMillis && taken.startMillis < span.endMillis) {
                    continue
                }
                if (span.startMillis <= start && start < span.endMillis && (bars[label] ?: 0L) < span.endMillis) {
                    bars[label] = span.endMillis
                    moved = true
                }
            }
            if (moved) continue
            // Mode 1: `t_p` may not be covered by the period "no on-screen task" ([lineIsCoveredAt]), and a
            // dynamic period's kind covers it a fortiori. A POSE whose slot the line has SWEPT — travelled
            // continuously through, from where its motion began up to here — is therefore pushed onto the line
            // and becomes the half-open `(t_p, t_p + duration]`; the line goes on delaying it, placing tasks
            // where it stood.
            //
            // **The two AWAY modes do not drag**: the requirements state them in one clause — *"Mode 2 & 3:
            // $now line$ must be covered by the period 'no on-screen task'"* — so the pose elapses under the
            // line, is frozen into the past like any other placement, and re-anchors the bars off itself.
            // (Mode 2 was dragging until the modes were restated; what tells the two apart is the CUE,
            // [breaksAreNotifiedAt].)
            //
            // **The look-away is exempt in every mode, and is not dragged at all** ([dragsAtLine]). Looking
            // twenty feet away for twenty seconds is not something the user has to stop working to do, so the
            // app takes it as done the moment the line reaches it: the period stays where the bars put it, the
            // line crosses it in mode 3 — covered, which is exactly what the drag exists to prevent elsewhere —
            // and twenty seconds later it is an ordinary fact of the past, still drawn where it happened. A
            // pose is the opposite: five or fifteen minutes away from the screen is something the user must
            // actually do, so an untaken one it never declared is still OWED and goes on being dragged.
            //
            // Pushing it is a move like any other, so the loop goes round again and the ordinary rules get
            // their say at the new position: the line may be standing inside a stretch nobody can run in (a
            // hand-drawn inactivity period, a night), and a period must no more fall inside one of those for
            // having been dragged than for having been placed there. A drag strictly increases the bar, so it
            // cannot spin — and it re-anchors that bar AT the line, which is what bounds the whole thing: at
            // most one occurrence per bar is ever swept, and the chain merge collapses those into one.
            //
            // **A break a no-screen chain already TOOK is not dragged** ([chainTaking]): the line was covered
            // while it crossed that stretch, so it dragged nothing there, and re-reading the past with the
            // mode the line is in NOW is how a pose the user really took ended up parked on the line — the
            // frozen past broken by a mode flip the stretch itself records.
            //
            // **And the drag PUTS THE POSE DOWN at the first stretch the line was not at a screen for**
            // ([noScreenChains]). The line dragged it only for as long as it was in mode 1, and a "no
            // on-screen task" chain behind the line is the timeline's own record that it was not: the user
            // walked away, and the pose they owed is what they walked away to take. Carried all the way to
            // `t_p` instead — which is what reading one mode for the whole journey does — every pose the day
            // owed piled onto the line, and the pause the user actually spent taking one was left with no
            // period in it at all, drawn as a plain "Inactivity" band. The chain still has to be able to TAKE
            // it ([chainTaking], asked at the top of the next turn round the loop): where it is too short the
            // break was not completed, so the drag picks it straight back up.
            if (taken == null && !lineIsCoveredAt(mode) && dragsAtLine(label) &&
                start >= sweepFromMillis && start < tpMillis
            ) {
                val putDown = chainAfter(noScreenChains, start)?.startMillis
                bars[label] = putDown?.coerceAtMost(tpMillis) ?: tpMillis
                continue
            }
            // It sits ON the line, and the line got here by SWEEPING (`sweepFrom < t_p`): this is the period
            // the line is dragging, and the half-open form is what keeps the instant `t_p` itself free. A
            // look-away never is, so it keeps the ordinary closed `[start, start + 20 s)` even when it lands
            // exactly on the line: the line is INSIDE it, in mode 3, and is meant to be.
            //
            // The sweep test is not decoration. A caller whose `t_p` is a window edge rather than the line
            // sweeps nothing (`sweepFrom == t_p`), and must not get the half-open form for the accident of a
            // slot falling on that edge: the cue sweep would then read one break's due as `floor` in one scan
            // and `floor + 1` in the next, and fire it twice.
            val openStart =
                !lineIsCoveredAt(mode) && dragsAtLine(label) && start == tpMillis && sweepFromMillis < tpMillis
            // The requirements' last bullet: a chain of "no on-screen task" periods that touches this slot
            // pulls the period back onto its own start ([chainTaking], which is also where the two refusals
            // live) — the time already spent away counts towards the break that falls due inside it.
            val place = taken?.startMillis?.coerceAtLeast(startMillis)
            val inst = if (place != null) Instance(spec, place) else Instance(spec, start, openStart)
            out += inst
            barInstance(bars, byLabel, restedSpans, inst, ::barRestStretch)
            // A period pulled BACKWARD must not re-open the slots the walk has already passed: whatever its own
            // bar says, the next occurrence of it is looked for past the instant it fell due. Without this the
            // walk can hand the same label a bar below `start` and spin on it (the guard is what makes the loop
            // monotone, not [MAX_STEPS]). Where a CHAIN took it, the next one is looked for past the chain as
            // well: the chain has given this break its occurrence, so from here on it bars it like any other
            // rest stretch (which is what recording it in [takenFrom] switches back on).
            if (taken != null) {
                takenFrom[label] = taken.startMillis
                bars[label] = maxOf(bars.getValue(label), maxOf(start + 1, taken.endMillis))
            } else if (place != null) {
                bars[label] = maxOf(bars.getValue(label), start + 1)
            }
        }
        return mergeChain(out)
    }

    /**
     * The periods the three make at this position of the line — [instances] rendered, plus the away cover.
     *
     * Modes 2 and 3 both want `t_p` COVERED BY "no on-screen task" ([lineIsCoveredAt]). The period that just
     * ended is the one the line came out of, so the GAP BEHIND IT is covered up to `t_p` — the break itself is
     * never stretched — as [PeriodKinds.NO_SCREEN]
     * rather than [PeriodKinds.INACTIVITY],
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
        return placed + listOfNotNull(awayCover(base, placed, tpMillis, mode))
    }

    /**
     * The away cover, on its own — a logic-only no-screen period that represents the line being covered while
     * away. It is kept for the scheduler's environment, but never rendered as a visible synthetic "Away" band
     * in the calendar UI.
     *
     * Both away modes have it ([lineIsCoveredAt]), and it is usually a no-op in either — the period the line
     * is inside already covers it — which is exactly the shape of the test below: it asks whether anything
     * covers `t_p` before manufacturing anything.
     */
    fun awayCover(
        base: Base,
        placed: List<RestrictivePeriod>,
        tpMillis: Long,
        mode: Int,
    ): RestrictivePeriod? {
        if (!lineIsCoveredAt(mode)) return null
        val all = placed + base.periods
        if (all.any { it.covers(tpMillis) && PeriodKinds.coversNoScreen(it.kind) }) return null
        val ends =
            all.filter { PeriodKinds.coversNoScreen(it.kind) && it.endMillis <= tpMillis }
                .maxOfOrNull { it.endMillis }
        // Nothing precedes: the cover is the line's own INSTANT, `[t_p, t_p]`, and that is not a degenerate
        // case to be dropped — it is the rule. Mode 2 drags a pose onto the line, so the period the line came
        // out of may not exist at all (the pose covers `(t_p, t_p + d]` and leaves `t_p` itself uncovered by
        // construction), and answering null there would leave mode 2's own rule reaching nothing exactly where
        // it matters most. `SchedulerDomain.fillSchedule` re-expresses whatever this returns as `[now, now]`
        // — the zero-width instant the fill reads as "the run AT the line must be resilient to no on-screen
        // task" (`ScheduleFill.firstAmong`) — so the reach behind the line is documentation, never a scheduling
        // input.
        val from = (ends ?: tpMillis).coerceAtMost(tpMillis)
        return RestrictivePeriod(from, tpMillis, PeriodKinds.NO_SCREEN, "no screen", closedEnd = true)
    }

    /**
     * `docs/scheduler_requirements.md` § *3 Dynamic Restrictive Period*, last bullet: **the chain of "no
     * on-screen task" periods that TOOK the occurrence falling at [startMillis]**, or `null` where none did.
     *
     * *"When a 'no on-screen task' period touches the start of a dynamic restrictive period, and that this
     * chain of 'no on-screen task' periods ends somewhere in $[now line;+infinity)$, then the dynamic
     * restrictive period now starts at the start of this chain. If it means starting in the past, this is the
     * only exception to the **frozen past** rule."*
     *
     * It is the ONE reading of that bullet, and the walk asks it three questions with one answer: where the
     * period is placed, which rest stretch may not bar it ([instances]' first loop), and whether the line
     * drags it. Five things it says, each load-bearing:
     * - **A chain, not a period.** Two periods that abut are one stretch here exactly as they are for the
     *   recurrence bars ([growStretch]), so a pause running into a night is one chain — [mergeSpans] treats
     *   touching as chaining.
     * - **Touching or containing.** The bullet's own wording is a chain ending exactly at the slot, which is
     *   what the absorption leaves behind (any emptiness pushes a break to the end of the stretch it fell
     *   inside). Asking it of the raw slot too costs nothing and says the same thing one step earlier.
     * - **It must have TAKEN the break, and that is a fact of the past.** The bullet's own clause is the
     *   present tense of it — a chain reaching the line is one the user is still inside, so the break is
     *   still being taken. A chain the line has left took the break exactly when it lasted at least as long
     *   as the break did; **that answer never changes as the line advances, which is what the frozen past
     *   requires**. Read as the bullet's clause alone, a pause the user came back from pulled nothing back,
     *   so the 5-min pose the app announced at the walk-away — and the user sat through — moved to the end of
     *   the pause and was then dragged onto the line by mode 1, leaving the calendar drawing the whole
     *   stretch as one derived "Inactivity" band. A chain SHORTER than the break took nothing: the user came
     *   back too soon, the break was not completed, so it is owed again and mode 1 goes back to dragging it.
     * - **Mode 1 refuses a pull-back that would cover the line.** That is mode 1's own rule rather than an
     *   exception to this one: a pose starting far enough back to still cover `t_p` would cover it, so the
     *   chain does not take it and the drag above keeps it. The look-away is not that case in any mode — it
     *   is assumed taken, so the line is *meant* to walk through it — and neither away mode is, the line
     *   being covered there by definition.
     * - **It reads the ENVIRONMENT, never the walk's own output.** A dynamic period is [PeriodKinds.INACTIVITY]
     *   and so would qualify as a chain of its own; where two of the three touch, the README's *chain merge*
     *   ([mergeChain]) is the rule, and letting both fire would be two answers to one question.
     */
    fun chainTaking(base: Base, spec: Spec, startMillis: Long, tpMillis: Long, mode: Int): Span? =
        chainTaking(noScreenChains(base), spec, startMillis, tpMillis, mode)

    /** [chainTaking] over chains already merged — the walk hoists them out of its loop. */
    private fun chainTaking(
        chains: List<Span>,
        spec: Spec,
        startMillis: Long,
        tpMillis: Long,
        mode: Int,
    ): Span? {
        // The merged chains are disjoint, non-abutting and sorted, so the ONE candidate is the last chain
        // beginning at or before the slot — found by bisection, because this is asked once per label on every
        // turn of a walk that runs on the display's hot path (ADR 0009).
        val chain = chainAtOrBefore(chains, startMillis)?.takeIf {
            it.endMillis == startMillis || (it.startMillis <= startMillis && startMillis < it.endMillis)
        } ?: return null
        // Still being taken (the chain reaches the line), or taken in full (it outlasted the break).
        if (chain.endMillis < tpMillis && chain.durationMillis < spec.durationMillis) return null
        // Mode 1: `t_p` must not be covered by a pose.
        if (!lineIsCoveredAt(mode) && dragsAtLine(spec.label) &&
            chain.startMillis <= tpMillis && tpMillis < chain.startMillis + spec.durationMillis
        ) {
            return null
        }
        return chain
    }

    /**
     * The stretches of the timeline covered by "no on-screen task", chained (two that abut are one) and in
     * order — the environment's own record of where the line was NOT at a screen.
     *
     * It is read for two things that are one rule: where a chain TAKES a break falling due in it
     * ([chainTaking]), and where the mode-1 drag puts an owed pose DOWN ([instances]). It reads the
     * ENVIRONMENT, never the walk's own output — a dynamic period is [PeriodKinds.INACTIVITY] and would qualify
     * as a chain of its own, and where two of the three touch the README's *chain merge* ([mergeChain]) is
     * the rule instead.
     */
    /** The last of the sorted, disjoint [chains] that begins at or before [millis]; `null` if none does. */
    private fun chainAtOrBefore(chains: List<Span>, millis: Long): Span? {
        var lo = 0
        var hi = chains.size - 1
        var found: Span? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (chains[mid].startMillis <= millis) {
                found = chains[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** The first of the sorted [chains] that begins strictly after [millis] — where a drag puts a pose down. */
    private fun chainAfter(chains: List<Span>, millis: Long): Span? {
        var lo = 0
        var hi = chains.size - 1
        var found: Span? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (chains[mid].startMillis > millis) {
                found = chains[mid]
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return found
    }

    private fun noScreenChains(base: Base): List<Span> =
        mergeSpans(
            base.periods
                .filter { PeriodKinds.coversNoScreen(it.kind) && it.endMillis > it.startMillis }
                .map { Span(it.startMillis, it.endMillis) },
        )

    /**
     * **Does the line DRAG this one when it reaches it?** — the two poses yes, the 20 s look-away no.
     *
     * Mode 1's rule is that `t_p` must not be covered, and the drag is how a period the line reaches obeys it:
     * the period is pushed onto the line and goes on being pushed, so it never happens until the user actually
     * rests. That is right for a pose — five or fifteen minutes away from the screen is a thing the user has
     * to *do*, and an untaken one is owed, not spent.
     *
     * It is wrong for the look-away. Looking twenty feet away for twenty seconds costs no working time, so the
     * app assumes it is being done the moment it falls due: the occurrence stays where the bars put it, the
     * line walks across it in **mode 2** (covered, for those twenty seconds), and once the line is past, the
     * break stays on the calendar as what really happened — the placement is a function of the environment, so
     * asking about that stretch again puts it back in the same place. It is derived, not recorded, so a later
     * change to the environment can still move it: a manual "Look away now" less than twenty minutes later
     * re-anchors the 20 s bar off the break the user actually took ([RestrictivePeriod.dynamic]).
     *
     * Keyed on the label, like every other bar rule here: [LABEL_20S] is the role of the shortest of the three,
     * not a title, so a retitled or debug-retimed account still answers this the same way.
     */
    private fun dragsAtLine(label: String): Boolean = label != LABEL_20S

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

    /**
     * The bars a rest stretch `[a, b)` sets, per the README's second and third clauses.
     *
     * [spared] is the labels this very stretch TAKES an occurrence of ([chainTaking]): a stretch bars what
     * comes AFTER it, and the break it is the taking of is not after it. Barring those too is what pushed a
     * pose an hour past the pause the user spent taking it, leaving the calendar to draw the whole absence as
     * one "Inactivity" band — and it fired from two places, the stretch's own turn round the walk and the
     * re-anchor off any period placed inside it ([barInstance]), which is why the sparing lives here.
     */
    private fun barStretch(bars: MutableMap<String, Long>, a: Long, b: Long, spared: Set<String> = emptySet()) {
        val length = b - a
        if (length >= STRETCH_SHORT_MILLIS && bars.containsKey(LABEL_5MIN) && LABEL_5MIN !in spared) {
            bars[LABEL_5MIN] = maxOf(bars.getValue(LABEL_5MIN), b + BAR_5MIN_AFTER_STRETCH_MILLIS)
        }
        if (length >= STRETCH_LONG_MILLIS) {
            if (bars.containsKey(LABEL_20S) && LABEL_20S !in spared) {
                bars[LABEL_20S] = maxOf(bars.getValue(LABEL_20S), b + BAR_20S_AFTER_LONG_MILLIS)
            }
            if (bars.containsKey(LABEL_15MIN) && LABEL_15MIN !in spared) {
                bars[LABEL_15MIN] = maxOf(bars.getValue(LABEL_15MIN), b + BAR_15MIN_AFTER_LONG_MILLIS)
            }
        }
    }

    private fun barInstance(
        bars: MutableMap<String, Long>,
        byLabel: Map<String, Spec>,
        rested: List<Span>,
        inst: Instance,
        /** [barStretch] with the walk's sparing already applied — see the walk's `barRestStretch`. */
        barRestStretch: (Long, Long) -> Unit,
    ) {
        // Measured from the last instant the period COVERS, not from its nominal end: a dragged period is the
        // half-open `(t_p, t_p + duration]`, which in discrete time ends one millisecond later than
        // `startMillis + duration`. A bar read off the nominal end is that millisecond short, and "no 20 s
        // period in the next 20 minutes" then places one at 19 min 59.999 s.
        val until = inst.coveredUntilMillis
        if (bars.containsKey(LABEL_20S) && LABEL_20S in byLabel) {
            bars[LABEL_20S] = maxOf(bars.getValue(LABEL_20S), until + BAR_20S_AFTER_ANY_MILLIS)
        }
        bars[inst.spec.label] =
            maxOf(bars[inst.spec.label] ?: Long.MIN_VALUE, until + inst.spec.cadenceMillis)
        // The whole of a dynamic period counts as a rest stretch: its kind is "no task allowed", which covers
        // "no on-screen task" a fortiori and leaves nobody able to run.
        val grown = growStretch(rested, inst.coveredFromMillis, until)
        barRestStretch(grown.startMillis, grown.endMillis)
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
            if (prev != null && inst.coveredFromMillis <= prev.coveredUntilMillis) {
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
    /**
     * Whether this period IS one of the three dynamic restrictive periods, already on the timeline — a break
     * the app conducted and recorded (`TaskPanel.conductedBreak`).
     *
     * The README's first bar is *"after any **dynamic restrictive period**, no 20 s period in the next 20
     * minutes"*, and it is the one bar that keys on a PERIOD rather than on a rest stretch — so a 20-second
     * look-away fires it while being far too short for either stretch bar. [DynamicPeriods.instances] fires
     * it for the occurrences it places itself; this is how the ones that have already HAPPENED fire it too.
     *
     * It is deliberately not "a 20-second period of `no task allowed`": a 20-second inactivity the user drew
     * by hand is a pre-placed restrictive period, which the README bars nothing after.
     */
    val dynamic: Boolean = false,
) {
    val durationMillis: Long get() = endMillis - startMillis

    fun covers(millis: Long): Boolean {
        val after = if (openStart) startMillis < millis else startMillis <= millis
        val before = if (closedEnd) millis <= endMillis else millis < endMillis
        return after && before
    }
}
