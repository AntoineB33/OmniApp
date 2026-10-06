package org.example.project.scheduler.domain

/**
 * `docs/scheduler_requirements.md` § *Default restrictive periods* — **the vocabulary of the three screen breaks**:
 * the three roles ([LABEL_20S], [LABEL_5MIN], [LABEL_15MIN]), the requirements' bars and stretch thresholds, the
 * `t_p` modes and the two questions each mode answers ([lineIsCoveredAt], [dragsAt]), and the kind each break is.
 *
 * WHERE the breaks fall is the break machine's alone ([BreakMachine]): a forward state machine driven by the line.
 * The walk that used to live here re-derived the breaks from a day-quantized origin at every reading, and every rule
 * it grew to keep the past still (the drag "put down" at the first stretch the line was not at a screen for, the
 * chain that "outlasted" a break, the origin anchored on the banked front) was a patch for re-deriving the past with
 * the mode of now. The machine never re-derives: it moves forward, and what the line met is banked.
 */
object DynamicPeriods {

    /**
     * After the end of a 20 s period, no 20 s period for 20 minutes. (Until 2026-10-05 the end of ANY of the three
     * barred it, and so did a >= 15-minute rest stretch: the requirements dropped both.)
     */
    const val BAR_20S_AFTER_20S_MILLIS: Long = 20L * 60_000L

    /** After a >= 5-minute rest stretch, no 5 min period for an hour. */
    const val BAR_5MIN_AFTER_STRETCH_MILLIS: Long = 60L * 60_000L

    /** After a >= 15-minute rest stretch, no 15 min period for two hours. */
    const val BAR_15MIN_AFTER_LONG_MILLIS: Long = 2L * 60L * 60_000L

    /** The length at which a rest stretch starts barring the 5-minute period. */
    const val STRETCH_SHORT_MILLIS: Long = 5L * 60_000L

    /** The length at which a rest stretch starts barring the 15-minute period. */
    const val STRETCH_LONG_MILLIS: Long = 15L * 60_000L

    /** The stable label of the 20-second period. */
    const val LABEL_20S: String = "20s"

    /** The stable label of the 5-minute period. */
    const val LABEL_5MIN: String = "5min"

    /** The stable label of the 15-minute period. */
    const val LABEL_15MIN: String = "15min"

    /**
     * What an account with no schedulable task is answered with: the resilience a freshly created task
     * carries (on screen). Kept here rather than read off `Task` so [Base.anybodyAt] stays a pure function of
     * the plan layer's own types.
     */
    private val DEFAULT_TASK_RESILIENCE: Map<String, Double> = mapOf(PeriodKinds.NO_SCREEN to 0.0)

    /**
     * `t_p` mode 1: *"$t_p$ must not be covered by the period 'no on-screen task'"* — the user is at the
     * screen, so a POSE the line reaches is pushed ahead of it and never happens until it is taken.
     *
     * The 20 s look-away is not dragged here ([dragsAt]): the line enters it and is in [MODE_ON_BREAK] for its
     * twenty seconds ([BreakMachine.effectiveMode]), and it stays on the timeline behind the line.
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
     * A line at a screen that enters a 20 s look-away is in this mode for its twenty seconds
     * ([BreakMachine.effectiveMode]) — *"When $now line$ enters a '20s screen break' restrictive period in mode
     * 1, it gets in mode 3"*.
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

    /** A half-open `[startMillis, endMillis)` span of the timeline. */
    data class Span(val startMillis: Long, val endMillis: Long) {
        val durationMillis: Long get() = endMillis - startMillis
    }

    /**
     * The environment at an instant, as the check at the line asks it (`SchedulerDomain.planMismatchAtLine`): the
     * standing periods, the pre-placed blocks and the tasks — who may run where.
     */
    class Base(
        val periods: List<RestrictivePeriod>,
        val blocks: List<PlanBlock>,
        val tasks: List<PlanTask>,
    ) {
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

        /** May ANYBODY run at [millis]? An account with no schedulable task is answered with one default task. */
        fun anybodyAt(millis: Long): Boolean {
            val kindsHere = kindsAt(millis)
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
    }

    /**
     * `docs/scheduler_requirements.md` § *screen breaks*: **the kind a break in this role is** — the 20 s *"allows no
     * task"* ([PeriodKinds.INACTIVITY], which nobody may be resilient to); the 5 min and the 15 min are kinds of their
     * own ([PeriodKinds.BREAK_5MIN], [PeriodKinds.BREAK_15MIN]), with the 5 min's first minute allowing no task
     * ([BREAK_5MIN_NO_TASK_MILLIS], `SchedulerDomain.screenBreakPeriods`). All three are accompanied by "no screen".
     */
    fun breakKind(label: String): String =
        when (label) {
            LABEL_5MIN -> PeriodKinds.BREAK_5MIN
            LABEL_15MIN -> PeriodKinds.BREAK_15MIN
            LABEL_20S -> PeriodKinds.BREAK_20S
            else -> PeriodKinds.INACTIVITY
        }

    /** *"The 5min break is accompanied by two periods: the first minute that allow no tasks and the 4 next minutes."* */
    const val BREAK_5MIN_NO_TASK_MILLIS: Long = 60_000L

    /**
     * **Does a line in [mode] DRAG this one when it reaches it?** — pushes it onto itself as `(t_p, t_p + d]`, and
     * goes on pushing it, because the line may not be inside it in that mode. `docs/scheduler_requirements.md`:
     * - **a pose, in mode 1**: *"$now line$ must not be covered by the period 'no screen'"*, and a pose comes with
     *   one — *"If the $now line$ reaches a 5min break in mode 1, this 5min break becomes ]$now line$; $now line$ +
     *   5min]"*. Five or fifteen minutes away from the screen is something the user must DO, so an untaken one is
     *   owed and rides the line;
     * - **the 20 s look-away, in mode 2**: *"The $now line$ must be in mode 1 or 3 before entering the 20s break"* —
     *   *"If the $now line$ reaches a 20s break in mode 2, this 20s break becomes ]$now line$; $now line$ + 20s]"*.
     *   In mode 1 the line enters it and is in mode 3 for its twenty seconds (§ *Mode switching*,
     *   [BreakMachine.effectiveMode]); in mode 3 it simply enters it.
     *
     * Keyed on the label, like every other bar rule here: [LABEL_20S] is the role of the shortest of the three,
     * not a title, so a retitled or debug-retimed account still answers this the same way.
     */
    internal fun dragsAt(label: String, mode: Int): Boolean =
        if (label == LABEL_20S) mode == MODE_AWAY else mode == MODE_AT_SCREEN
}

/**
 * `side-dev/README.md` § *Restrictive Period*: **a start, an end and a kind**. The one object the whole
 * scheduler reads the timeline's restrictions through, whether the period was drawn by the user, derived
 * from a §17 sleep window, or placed by the break machine ([BreakMachine]).
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
     * look-away fires it while being far too short for either stretch bar. The break machine fires it for the
     * breaks the line enters ([BreakMachine]); this is how a break the app conducted fires it too.
     *
     * It is deliberately not "a 20-second period of `no task allowed`": a 20-second inactivity the user drew
     * by hand is a pre-placed restrictive period, which the README bars nothing after.
     */
    val dynamic: Boolean = false,
    /**
     * Whether the user stated this period — drew it on the calendar, or set it in the Sleep schedule
     * (`SchedulerDomain.isUserStated`) — the periods a combination rule's "then … or …" fires from
     * (`PeriodKindConfig.closeRegions`).
     */
    val manual: Boolean = false,
) {
    val durationMillis: Long get() = endMillis - startMillis

    fun covers(millis: Long): Boolean {
        val after = if (openStart) startMillis < millis else startMillis <= millis
        val before = if (closedEnd) millis <= endMillis else millis < endMillis
        return after && before
    }
}
