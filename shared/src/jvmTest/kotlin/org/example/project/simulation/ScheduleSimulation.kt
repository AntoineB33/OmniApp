package org.example.project.simulation

import kotlin.random.Random
import org.example.project.CalendarHorizonFixture
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * **A chaotic account, generated from a seed, lived through for weeks** — the harness behind
 * [ScheduleSimulationTest] (the fast tier, in every `jvmTest`) and [ScheduleSimulationLongTest] (the long tier,
 * `./gradlew :shared:longTest`).
 *
 * Everything goes through the app's own reducer, with the intents the engine fires: the tasks, kinds, periods and
 * pre-placed panels are ADDED by their intents (so whatever the reducer refuses or trims is refused and trimmed here
 * too), the plan is made by `RefreshSchedule`, the line is walked by `AdvanceSchedule` + `ExtendSchedule`, and a rule
 * change is its intent followed by the re-plan a changed scheduling signature brings. Nothing here calls the fill
 * directly, and nothing reads the scheduler's own score: what is asserted is measured off the RECORDS the walk banked,
 * against targets this file computes from the requirements alone ([Outcome]) — a second reading of "its share", so a
 * fault in the score model cannot hide behind itself.
 *
 * Deterministic: a scenario is its [Scenario.seed], and a failure prints it.
 */
internal object ScheduleSimulation {
    const val MIN: Long = 60_000L
    const val HOUR: Long = 60 * MIN
    const val DAY: Long = 24 * HOUR
    const val WEEK: Long = 7 * DAY

    /** A Monday 00:00 UTC far from any real clock. No sleep schedule is used, so no time zone enters a scenario. */
    const val T0: Long = 1_900_800_000_000L / DAY * DAY

    /** One rule change at [atMillis] past [T0]: what the user does to the account mid-simulation. */
    sealed interface Change {
        val atMillis: Long

        /** MINOR: one task's weight and minimum time are redrawn. */
        data class OneTask(override val atMillis: Long) : Change

        /** MAJOR: every weight is redrawn, a share of the tasks change resilience, and new periods are laid ahead. */
        data class Upheaval(override val atMillis: Long) : Change
    }

    data class Scenario(
        val seed: Long,
        val tasks: Int,
        val kinds: Int,
        /** How long the account is lived through. */
        val days: Int,
        /** Restrictive periods laid per week, of random kinds and lengths. */
        val periodsPerWeek: Int,
        /** Pre-placed task panels laid per week. */
        val prePlacedPerWeek: Int,
        /** A stretch every day nobody may run in (a night), so the schedulable clock is not the wall clock. */
        val nightHours: Int = 8,
        val changes: List<Change> = emptyList(),
        /**
         * How far past the line the plan is materialized, and how far the line is walked between two extensions of it.
         * A day and half a day: a fill costs in proportion to what it materializes (a week of twenty tasks is ~20 s on
         * a desktop), and the app itself never plans a week at each step — it plans to its goal and extends.
         */
        val horizonMillis: Long = DAY,
        val stepMillis: Long = 12 * HOUR,
    ) {
        val endMillis: Long get() = T0 + days * DAY
        override fun toString(): String =
            "seed=$seed tasks=$tasks kinds=$kinds days=$days periods/week=$periodsPerWeek pre-placed/week=$prePlacedPerWeek " +
                "night=${nightHours}h changes=${changes.map { it::class.simpleName + "@" + (it.atMillis / DAY) + "d" }}"
    }

    /**
     * **What a run is held to.** Two levels, and the difference between them is the scheduler's open debt:
     *
     *  - [GOAL] is what "every task reaches its target" means: nearly all the time anybody may run in is given to
     *    somebody, and every task gets its share of it.
     *  - [TOLERATED] is what the scheduler does TODAY, with a margin — measured when these tests were written
     *    (2026-10-06): the plan leaves 3 % to 31 % of the schedulable time to nobody, and a task with a short minimum
     *    execution time and a large share gets as little as 0.5× its lower target among what is served, the difference
     *    going to the tasks with long minimums. The tests FAIL past it, so nothing gets worse unnoticed; every run also
     *    says how far from [GOAL] it is. **Tightening [TOLERATED] towards [GOAL] is the work this file exists to
     *    measure — never loosen it to make a change pass.**
     *
     * The hard constraints have one level: none may be broken, ever.
     */
    class Levels(
        /** The most of the schedulable time the plan may leave to nobody. */
        val idleFraction: Double,
        /** The least a measurable task may get of its lower target, among what is served ([Outcome.underService]). */
        val underService: Double,
        /** The most a measurable task may get of its upper target ([Outcome.overService]). */
        val overService: Double,
        /** The most of the runs that may end short of their minimum with nothing forcing them to. */
        val shortRunFraction: Double,
    )

    val GOAL = Levels(idleFraction = 0.05, underService = 0.90, overService = 1.15, shortRunFraction = 0.05)
    val TOLERATED = Levels(idleFraction = 0.40, underService = 0.50, overService = 2.00, shortRunFraction = 0.10)

    /** What of [outcome] is past [levels] — empty when it is within them. */
    fun shortOf(outcome: Outcome, levels: Levels): List<String> = buildList {
        if (outcome.idleFraction > levels.idleFraction) {
            add("${"%.1f".format(100 * outcome.idleFraction)}% of the schedulable time is left to nobody (at most ${"%.0f".format(100 * levels.idleFraction)}%)")
        }
        outcome.measurable.filter { outcome.underService(it) < levels.underService }.sortedBy { outcome.underService(it) }.take(5).forEach {
            add("${it.title} (${"%.1f".format(100 * it.share)}%, min ${it.minimumMillis / MIN}min) got ${"%.2f".format(outcome.underService(it))}x its lower target (at least ${levels.underService}x)")
        }
        outcome.measurable.filter { outcome.overService(it) > levels.overService }.sortedByDescending { outcome.overService(it) }.take(5).forEach {
            add("${it.title} (${"%.1f".format(100 * it.share)}%, min ${it.minimumMillis / MIN}min) got ${"%.2f".format(outcome.overService(it))}x its upper target (at most ${levels.overService}x)")
        }
        if (outcome.shortRunFraction > levels.shortRunFraction) {
            add("${outcome.shortRuns} of ${outcome.runs} runs ended short of their minimum (at most ${"%.0f".format(100 * levels.shortRunFraction)}%)")
        }
    }

    /** The hard constraints [run] broke over [outcome]'s stretch: none is ever tolerated. */
    fun broken(run: Run, outcome: Outcome): List<String> = outcome.violations + run.pastRewrites

    private const val NIGHT_KIND = "night"
    private val MINIMUMS = listOf(5, 10, 15, 20, 30, 45, 60)

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun minuteAligned(millis: Long) = millis / MIN * MIN

    /** The account at [T0]: the tasks with their weights, minimums and resiliences, and everything laid on the weeks ahead. */
    fun build(scenario: Scenario): SchedulerState {
        val random = Random(scenario.seed)
        var s = SchedulerState.empty()
        repeat(scenario.tasks) { i ->
            val cell = s.lists[s.rootListId]!!.cellIds.last()
            s = r(s, SchedulerIntent.SetCellTitle(cell, "T$i"))
        }
        val cells = s.lists[s.rootListId]!!.cellIds.filter { s.tasks[s.cells[it]?.taskId]?.title?.startsWith("T") == true }
        val ids = cells.mapNotNull { s.cells[it]?.taskId }
        val kinds = (0 until scenario.kinds).map { "kind-$it" }
        for (kind in kinds + NIGHT_KIND) s = r(s, SchedulerIntent.AddPeriodKind(kind))
        cells.forEachIndexed { i, cell ->
            s = r(s, SchedulerIntent.SetPriorityWeight(cell, 0, drawWeight(random)))
            s = r(s, SchedulerIntent.SetTaskMinimumTime(ids[i], MINIMUMS.random(random)))
            s = r(s, SchedulerIntent.SetTaskResilience(ids[i], NIGHT_KIND, 0.0))
            for (kind in kinds) s = r(s, SchedulerIntent.SetTaskResilience(ids[i], kind, drawResilience(random)))
        }
        // The nights first — one period per day rather than a repeating one, so what is laid is what is counted.
        if (scenario.nightHours > 0) {
            for (day in 0 until scenario.days) {
                val start = T0 + day * DAY + (24 - scenario.nightHours) * HOUR
                s = r(s, SchedulerIntent.AddRestrictivePeriod(NIGHT_KIND, start, start + scenario.nightHours * HOUR))
            }
        }
        s = layAhead(s, random, kinds, ids, T0, scenario.endMillis, scenario.periodsPerWeek, scenario.prePlacedPerWeek)
        return s
    }

    private fun drawWeight(random: Random): Double = listOf(0.5, 1.0, 1.0, 2.0, 3.0, 5.0, 8.0).random(random)

    /** Most tasks are refused by a kind or pass through it untouched; a few are slowed. */
    private fun drawResilience(random: Random): Double =
        when (random.nextInt(10)) {
            in 0..3 -> 0.0
            in 4..5 -> 0.5
            else -> 1.0
        }

    /** Periods and pre-placed task panels scattered over `[from, until)`, each through its own intent. */
    private fun layAhead(
        state: SchedulerState,
        random: Random,
        kinds: List<String>,
        ids: List<TaskId>,
        from: Long,
        until: Long,
        periodsPerWeek: Int,
        prePlacedPerWeek: Int,
    ): SchedulerState {
        var s = state
        val weeks = ((until - from).toDouble() / WEEK)
        if (kinds.isNotEmpty()) {
            repeat((periodsPerWeek * weeks).toInt()) {
                val start = minuteAligned(from + random.nextLong(until - from))
                val length = (30 + random.nextInt(330)) * MIN
                s = r(s, SchedulerIntent.AddRestrictivePeriod(kinds.random(random), start, minOf(start + length, until)))
            }
        }
        // Two pre-placed panels never overlap: the user would be saying two tasks both run there, which Overlap Mode
        // draws side by side and no share can be measured against. A draw that would is skipped (still drawn, so the
        // rest of the scenario does not depend on it).
        val taken = s.panels.filter { !it.isRestrictivePeriod && SchedulerDomain.isUserPlaced(it) }
            .mapTo(ArrayList()) { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        repeat((prePlacedPerWeek * weeks).toInt()) {
            val start = minuteAligned(from + random.nextLong(until - from))
            val length = (15 + random.nextInt(165)) * MIN
            val task = ids.random(random)
            val end = minOf(start + length, until)
            if (taken.any { it.startEpochMillis < end && start < it.endEpochMillis }) return@repeat
            taken += TaskTimeRange(start, end)
            s = r(
                s,
                SchedulerIntent.AddTaskPanel(
                    task, s.tasks[task]?.title.orEmpty(), start, end, PanelPins(existence = true),
                ),
            )
        }
        return s
    }

    private fun applyChange(state: SchedulerState, change: Change, scenario: Scenario, random: Random, now: Long): SchedulerState {
        var s = state
        val cells = s.lists[s.rootListId]!!.cellIds.filter { s.tasks[s.cells[it]?.taskId]?.title?.startsWith("T") == true }
        val ids = cells.mapNotNull { s.cells[it]?.taskId }
        val kinds = (0 until scenario.kinds).map { "kind-$it" }
        when (change) {
            is Change.OneTask -> {
                val i = random.nextInt(cells.size)
                s = r(s, SchedulerIntent.SetPriorityWeight(cells[i], 0, drawWeight(random)))
                s = r(s, SchedulerIntent.SetTaskMinimumTime(ids[i], MINIMUMS.random(random)))
            }
            is Change.Upheaval -> {
                for (cell in cells) s = r(s, SchedulerIntent.SetPriorityWeight(cell, 0, drawWeight(random)))
                for (id in ids) {
                    if (random.nextInt(4) != 0 || kinds.isEmpty()) continue
                    s = r(s, SchedulerIntent.SetTaskResilience(id, kinds.random(random), drawResilience(random)))
                }
                // New periods and pre-placed panels over what is left of the timeline: a fifth again of each.
                s = layAhead(
                    s, random, kinds, ids, minuteAligned(now) + HOUR, scenario.endMillis,
                    scenario.periodsPerWeek / 5, scenario.prePlacedPerWeek / 5,
                )
            }
        }
        return s
    }

    /** What one rule epoch of a run looked like: the rules in force over `[fromMillis, untilMillis)`. */
    class Epoch(val fromMillis: Long, val untilMillis: Long, val rules: SchedulerState)

    class Run(
        val scenario: Scenario,
        val finalState: SchedulerState,
        val epochs: List<Epoch>,
        val wallMillis: Long,
        val replans: Int,
        val extensions: Int,
        /** Steps of the walk after which something banked BEFORE the previous step had changed: the frozen past rewritten. */
        val pastRewrites: List<String>,
    )

    /** [scenario] lived through: planned at [T0], walked to its end, re-planned at each change. */
    fun run(scenario: Scenario): Run {
        val started = System.nanoTime()
        val random = Random(scenario.seed xor 0x5DEECE66DL)
        // The reducer reads the line's mode, the devices' evidence and the horizon through hooks the engine installs
        // — process-wide, so a test that ran before may have left its own. A scenario is a line at a screen with no
        // device evidence and nothing superseding its fills: said here, and whatever was there is put back after.
        val hooks = ReducerHooks.take()
        SchedulerReducer.tpMode = { org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN }
        SchedulerReducer.noScreenEvidence = { emptyList() }
        SchedulerReducer.liveRestGap = { null }
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.planAbandoned = { false }
        CalendarHorizonFixture.show(scenario.horizonMillis)
        try {
            var s = build(scenario)
            var now = T0
            var replans = 1
            var extensions = 0
            val epochs = ArrayList<Epoch>()
            var epochStart = T0
            var epochRules = s
            s = r(s, SchedulerIntent.RefreshSchedule(now))
            val pending = scenario.changes.sortedBy { it.atMillis }.toMutableList()
            // `docs/scheduler_requirements.md` § *frozen past*: what the line has banked is never rewritten — by an
            // extension, a re-plan or a rule change. Checked at every step: every stretch of record a task held one
            // step ago is still its record (a record may only GROW: the run in progress at the line is banked when
            // it ends).
            val rewrites = ArrayList<String>()
            var bankedBefore = emptyMap<TaskId, List<TaskTimeRange>>()
            fun banked(state: SchedulerState): Map<TaskId, List<TaskTimeRange>> =
                state.tasks.values.filter { it.record.isNotEmpty() }.associate { it.id to SchedulerDomain.mergeOccupied(it.record) }
            while (now < scenario.endMillis) {
                val nextChange = pending.firstOrNull()?.let { T0 + it.atMillis }
                val target = minOf(now + scenario.stepMillis, scenario.endMillis, nextChange ?: Long.MAX_VALUE)
                now = target
                s = r(s, SchedulerIntent.AdvanceSchedule(now))
                if (nextChange != null && now >= nextChange) {
                    val change = pending.removeAt(0)
                    epochs += Epoch(epochStart, now, epochRules)
                    s = applyChange(s, change, scenario, random, now)
                    epochStart = now
                    epochRules = s
                    s = r(s, SchedulerIntent.RefreshSchedule(now))
                    replans++
                } else if (now < scenario.endMillis) {
                    s = r(s, SchedulerIntent.ExtendSchedule(now))
                    extensions++
                }
                val bankedNow = banked(s)
                for ((task, ranges) in bankedBefore) {
                    val held = bankedNow[task].orEmpty()
                    val lost = ranges.sumOf { it.endEpochMillis - it.startEpochMillis } -
                        ranges.sumOf { overlap(held, it.startEpochMillis, it.endEpochMillis) }
                    if (lost >= MIN) {
                        rewrites += "at +${(now - T0) / HOUR}h, ${lost / MIN}min of ${s.tasks[task]?.title}'s banked record was gone"
                    }
                }
                bankedBefore = bankedNow
            }
            epochs += Epoch(epochStart, now, epochRules)
            return Run(scenario, s, epochs, (System.nanoTime() - started) / 1_000_000, replans, extensions, rewrites)
        } finally {
            hooks.restore()
        }
    }

    /** The reducer's process-wide hooks as they stood, to put back once a scenario has been lived. */
    private class ReducerHooks(
        val tpMode: () -> Int,
        val noScreenEvidence: () -> List<TaskTimeRange>,
        val liveRestGap: () -> SchedulerDomain.LiveRest?,
        val frozenScreenBreaks: () -> org.example.project.scheduler.domain.FrozenScreenBreaks?,
        val planAbandoned: (Long) -> Boolean,
        val horizon: (Long) -> Long,
    ) {
        fun restore() {
            SchedulerReducer.tpMode = tpMode
            SchedulerReducer.noScreenEvidence = noScreenEvidence
            SchedulerReducer.liveRestGap = liveRestGap
            SchedulerReducer.frozenScreenBreaks = frozenScreenBreaks
            SchedulerReducer.planAbandoned = planAbandoned
            SchedulerReducer.scheduleHorizonEndMillis = horizon
        }

        companion object {
            fun take() = ReducerHooks(
                SchedulerReducer.tpMode, SchedulerReducer.noScreenEvidence, SchedulerReducer.liveRestGap,
                SchedulerReducer.frozenScreenBreaks, SchedulerReducer.planAbandoned, SchedulerReducer.scheduleHorizonEndMillis,
            )
        }
    }

    // ----- What happened, measured off the records ---------------------------------------------------------

    /**
     * One task over one stretch of the run.
     *
     * [servedMillis] is what the walk banked for it (its records and its own pre-placed panels). The two targets are
     * the requirements' two readings of "its share", and the scheduler is asked to stand between them:
     *  - [localMillis] — its priority percentage of every instant it MAY run at, resilience applied
     *    (`P_i·μ_i / Σ P_j·μ_j`, and the whole of its own pre-placed panels): what it gets if a deprivation is never
     *    repaid;
     *  - [nominalMillis] — its priority percentage of ALL the time anybody may run: what it gets if every
     *    deprivation is repaid in full. Compensation is bounded (`docs/scheduler_score.md`), so the truth is between.
     */
    class TaskOutcome(
        val taskId: TaskId,
        val title: String,
        val share: Double,
        val minimumMillis: Long,
        val servedMillis: Long,
        val localMillis: Double,
        val nominalMillis: Double,
        /** How long anybody at all could run while this task could. */
        val allowedMillis: Long,
    ) {
        val low: Double get() = minOf(localMillis, nominalMillis)
        val high: Double get() = maxOf(localMillis, nominalMillis)

        /**
         * Whether the stretch was long enough to say anything about this task: its lower target is at least three of
         * its minimum times. Under that, one panel more or less IS the difference.
         */
        val measurable: Boolean get() = low >= 3.0 * minimumMillis

        /** How far outside `[low, high]` the service is: negative when under-served, 0 inside the band. */
        val outsideMillis: Double
            get() = when {
                servedMillis < low -> servedMillis - low
                servedMillis > high -> servedMillis - high
                else -> 0.0
            }
    }

    class Outcome(
        val fromMillis: Long,
        val untilMillis: Long,
        val schedulableMillis: Long,
        val servedMillis: Long,
        val tasks: List<TaskOutcome>,
        /** Records that break a hard constraint: a task inside a period it has resilience 0 to, or two at once. */
        val violations: List<String>,
        /** Runs of a task that ended short of its minimum time with nothing forcing them to, over every run. */
        val shortRuns: Int,
        val runs: Int,
    ) {
        /** The share of the time anybody could run in that the plan left to nobody. */
        val idleFraction: Double get() = if (schedulableMillis <= 0L) 0.0 else 1.0 - servedMillis.toDouble() / schedulableMillis

        /**
         * A task's share of the SERVED time over the lower target's share of the schedulable time: 1 is on target,
         * 0.5 is half of what it was owed among what was served. Time left to nobody is counted apart
         * ([idleFraction]), so this says how the served time was DIVIDED.
         */
        fun underService(task: TaskOutcome): Double =
            if (servedMillis <= 0L || task.low <= 0.0) 1.0
            else (task.servedMillis.toDouble() / servedMillis) / (task.low / schedulableMillis)

        /** The same against the upper target: 2 is twice what it could be owed at most. */
        fun overService(task: TaskOutcome): Double =
            if (servedMillis <= 0L || task.high <= 0.0) 1.0
            else (task.servedMillis.toDouble() / servedMillis) / (task.high / schedulableMillis)

        val measurable: List<TaskOutcome> get() = tasks.filter { it.measurable }
        val worstUnder: Double get() = measurable.minOfOrNull(::underService) ?: 1.0
        val worstOver: Double get() = measurable.maxOfOrNull(::overService) ?: 1.0
        val shortRunFraction: Double get() = if (runs == 0) 0.0 else shortRuns.toDouble() / runs
    }

    private class Span(val start: Long, val end: Long, val kind: String?, val taskId: TaskId?)

    /** What the user laid on the timeline under [rules]: the hand-placed periods and pre-placed panels. */
    private fun laid(rules: SchedulerState): List<Span> =
        rules.panels.filter { SchedulerDomain.isUserPlaced(it) }.map(::spanOf)

    private fun spanOf(panel: TaskPanel): Span =
        Span(panel.startEpochMillis, panel.endEpochMillis, panel.restrictiveKind.ifEmpty { null }, panel.taskId.takeIf { !panel.isRestrictivePeriod })

    /** The service every task banked over the run: its records, and its own pre-placed panels behind the line. */
    private fun service(state: SchedulerState, laid: List<Span>, until: Long): Map<TaskId, List<TaskTimeRange>> {
        val out = HashMap<TaskId, MutableList<TaskTimeRange>>()
        for (task in state.tasks.values) {
            if (task.record.isNotEmpty()) out.getOrPut(task.id) { ArrayList() } += task.record
        }
        for (span in laid) {
            val task = span.taskId ?: continue
            if (span.start < until) out.getOrPut(task) { ArrayList() } += TaskTimeRange(span.start, minOf(span.end, until))
        }
        return out.mapValues { (_, ranges) -> SchedulerDomain.mergeOccupied(ranges) }
    }

    private fun overlap(ranges: List<TaskTimeRange>, from: Long, until: Long): Long =
        ranges.sumOf { (minOf(it.endEpochMillis, until) - maxOf(it.startEpochMillis, from)).coerceAtLeast(0L) }

    /**
     * [run] measured over `[fromMillis, untilMillis)` — which must lie inside ONE rule epoch, since a target is a
     * statement under one set of rules.
     */
    fun measure(run: Run, fromMillis: Long, untilMillis: Long): Outcome {
        val epoch = run.epochs.first { it.fromMillis <= fromMillis && untilMillis <= it.untilMillis }
        val rules = epoch.rules
        val laid = laid(rules)
        val priorities = SchedulerDomain.absoluteTaskPriorities(rules)
        val tasks = rules.tasks.values.filter { SchedulerDomain.isPlaceableTask(rules, it.id) && it.title.startsWith("T") }
        val total = tasks.sumOf { priorities[it.id] ?: 0.0 }
        val share = tasks.associate { it.id to if (total > 0.0) (priorities[it.id] ?: 0.0) / total else 1.0 / tasks.size }
        val config = rules.periodKindConfig

        val edges = (listOf(fromMillis, untilMillis) + laid.flatMap { listOf(it.start, it.end) })
            .filter { it in fromMillis..untilMillis }.distinct().sorted()
        val local = HashMap<TaskId, Double>()
        val allowed = HashMap<TaskId, Long>()
        var schedulable = 0L
        for (k in 0 until edges.size - 1) {
            val a = edges[k]
            val b = edges[k + 1]
            val covering = laid.filter { it.start <= a && b <= it.end }
            val block = covering.firstOrNull { it.kind == null }
            val kinds = covering.mapNotNull { it.kind }.flatMapTo(HashSet()) { config.kindsOf(it) }
            val mult = tasks.associate { task ->
                task.id to when {
                    block != null -> if (block.taskId == task.id) 1.0 else 0.0
                    else -> PeriodKinds.multiplier(task.resilience, kinds)
                }
            }
            if (mult.values.none { it > 0.0 }) continue
            val len = b - a
            schedulable += len
            val weighted = tasks.sumOf { (share[it.id] ?: 0.0) * (mult[it.id] ?: 0.0) }
            val permitted = mult.values.count { it > 0.0 }
            for (task in tasks) {
                val m = mult[task.id] ?: 0.0
                if (m <= 0.0) continue
                allowed[task.id] = (allowed[task.id] ?: 0L) + len
                val q = if (weighted > 0.0) (share[task.id] ?: 0.0) * m / weighted else 1.0 / permitted
                local[task.id] = (local[task.id] ?: 0.0) + q * len
            }
        }

        val served = service(run.finalState, laid, untilMillis)
        val outcomes = tasks.map { task ->
            TaskOutcome(
                task.id, task.title, share[task.id] ?: 0.0, task.minimumMinutes * MIN,
                overlap(served[task.id].orEmpty(), fromMillis, untilMillis),
                local[task.id] ?: 0.0, (share[task.id] ?: 0.0) * schedulable, allowed[task.id] ?: 0L,
            )
        }

        // Hard constraints, read off the records alone.
        val violations = ArrayList<String>()
        val byTask = rules.tasks
        for ((taskId, ranges) in served) {
            val task = byTask[taskId] ?: continue
            for (range in ranges) {
                if (range.endEpochMillis <= fromMillis || range.startEpochMillis >= untilMillis) continue
                for (span in laid) {
                    val from = maxOf(span.start, range.startEpochMillis, fromMillis)
                    val until = minOf(span.end, range.endEpochMillis, untilMillis)
                    if (until - from < MIN) continue
                    if (span.kind != null && PeriodKinds.multiplier(task.resilience, config.kindsOf(span.kind)) <= 0.0) {
                        violations += "${task.title} ran ${(until - from) / MIN}min inside a '${span.kind}' period it has resilience 0 to, at +${(from - T0) / MIN}min"
                    }
                    if (span.kind == null && span.taskId != taskId) {
                        violations += "${task.title} ran ${(until - from) / MIN}min inside a pre-placed panel of another task, at +${(from - T0) / MIN}min"
                    }
                }
            }
        }
        val all = served.flatMap { (id, ranges) -> ranges.map { id to it } }
            .filter { it.second.endEpochMillis > fromMillis && it.second.startEpochMillis < untilMillis }
            .sortedBy { it.second.startEpochMillis }
        for (k in 0 until all.size - 1) {
            val over = all[k].second.endEpochMillis - all[k + 1].second.startEpochMillis
            if (over >= MIN) {
                violations += "${byTask[all[k].first]?.title} and ${byTask[all[k + 1].first]?.title} both ran for ${over / MIN}min at +${(all[k + 1].second.startEpochMillis - T0) / MIN}min"
            }
        }

        // Runs short of the minimum: a run is a task's consecutive service on the schedulable clock, so one a period
        // nobody may run in suspended is one run; one ended by a laid span's edge (or the stretch's) was forced.
        var runs = 0
        var short = 0
        val laidEdges = laid.flatMapTo(HashSet()) { listOf(it.start, it.end) }
        var k = 0
        while (k < all.size) {
            val (id, first) = all[k]
            var length = first.endEpochMillis - first.startEpochMillis
            var end = first.endEpochMillis
            var j = k + 1
            while (j < all.size && all[j].first == id) {
                length += all[j].second.endEpochMillis - all[j].second.startEpochMillis
                end = all[j].second.endEpochMillis
                j++
            }
            val start = first.startEpochMillis
            val forced = start <= fromMillis || end >= untilMillis || end in laidEdges || start in laidEdges
            val minimum = (byTask[id]?.minimumMinutes ?: 0) * MIN
            runs++
            if (!forced && length < minimum - MIN) short++
            k = j
        }
        return Outcome(fromMillis, untilMillis, schedulable, outcomes.sumOf { it.servedMillis }, outcomes, violations, short, runs)
    }

    /** A table of [outcome], worst tasks first — what a failure prints, and what the long tier logs. */
    fun describe(run: Run, outcome: Outcome, worst: Int = 12): String = buildString {
        appendLine(run.scenario.toString())
        appendLine(
            "  walked in ${run.wallMillis} ms — ${run.replans} re-plan(s), ${run.extensions} extension(s); measured " +
                "+${(outcome.fromMillis - T0) / DAY}d..+${(outcome.untilMillis - T0) / DAY}d: " +
                "${outcome.schedulableMillis / HOUR}h schedulable, ${outcome.servedMillis / HOUR}h served " +
                "(${"%.1f".format(100 * outcome.idleFraction)}% left to nobody), " +
                "${outcome.shortRuns}/${outcome.runs} runs short of their minimum, ${outcome.violations.size} violation(s); " +
                "of ${outcome.measurable.size} measurable tasks the least served got ${"%.2f".format(outcome.worstUnder)}x its lower " +
                "target, the most served ${"%.2f".format(outcome.worstOver)}x its upper one",
        )
        val ranked = outcome.tasks.sortedBy { if (it.measurable) outcome.underService(it) else 9.0 }
        for (t in ranked.take(worst)) {
            appendLine(
                "  ${t.title.padEnd(6)} share ${"%5.2f".format(t.share * 100)}%  min ${t.minimumMillis / MIN}min  served " +
                    "${"%7.1f".format(t.servedMillis / HOUR.toDouble())}h  band ${"%7.1f".format(t.low / HOUR)}h..${"%7.1f".format(t.high / HOUR)}h  " +
                    "x${"%.2f".format(outcome.underService(t))} of low${if (t.measurable) "" else " (too short to say)"}",
            )
        }
        outcome.violations.take(8).forEach { appendLine("  VIOLATION: $it") }
        run.pastRewrites.take(4).forEach { appendLine("  FROZEN PAST REWRITTEN: $it") }
    }
}
