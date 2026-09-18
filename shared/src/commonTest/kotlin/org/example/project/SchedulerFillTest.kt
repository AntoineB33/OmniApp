package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` through the real calendar fill ([SchedulerDomain.fillSchedule]): the
 * alternation scale, the priority shares, a past or pre-placed exclusion buying a bounded compensation, the
 * zero-priority and sole-task edges, No idling over a week of screen breaks, and the trigger rule (the plan moves
 * only on a change to the rules, and an extension keeps what is materialized).
 */
class SchedulerFillTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    // ----- the README's properties, through the real fill ---------------------------------------

    /** [names] equal-priority sibling tasks under "main", each with the given minimum time. */
    private fun stateWithTasks(vararg names: String, minMinutes: Int = 45): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        names.forEachIndexed { i, name ->
            val cell = s.lists[s.rootListId]!!.cellIds[i]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, name))
        }
        val ids = names.map { name -> s.tasks.keys.first { s.tasks[it]!!.title == name } }
        for (taskId in ids) s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(taskId, minMinutes))
        return s to ids
    }

    private fun pinned(panelId: String, taskId: TaskId?, start: Long, end: Long) =
        TaskPanel(panelId, taskId, "x", start, end, pinned = true, auto = false, pins = PanelPins(existence = true))

    private fun autoSpans(panels: List<TaskPanel>): List<Pair<TaskId?, Long>> =
        panels.filter { it.auto }.sortedBy { it.startEpochMillis }
            .map { it.taskId to (it.endEpochMillis - it.startEpochMillis) }

    @Test
    fun two_equal_tasks_alternate_at_the_smallest_scale_their_minimum_allows() {
        // `side-dev/README.md`: "task A 50% 10min and task B 50% 10min → right: task A 10min, then task B
        // 10min and so on", NOT an hour of each. This is reference test 1, through the real fill.
        val (s, ids) = stateWithTasks("A", "B", minMinutes = 10)
        val (a, b) = ids
        val spans = autoSpans(SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 2 * HOUR)).take(6)
        assertEquals(listOf(a, b, a, b, a, b), spans.map { it.first })
        assertTrue(spans.all { it.second == 10 * MIN }, "every chunk should be one minimum, got $spans")
    }

    @Test
    fun three_tasks_converge_on_their_priority_percentages() {
        // Reference test 4's shape: 50 % / 30 % / 20 %, minimums 10 / 15 / 5.
        val (s0, ids) = stateWithTasks("A", "B", "C")
        val (a, b, c) = ids
        var s = s0
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 10))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(b, 15))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(c, 5))
        val cellA = s.lists[s.rootListId]!!.cellIds[0]
        val cellB = s.lists[s.rootListId]!!.cellIds[1]
        val cellC = s.lists[s.rootListId]!!.cellIds[2]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellA, 0, 5.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellB, 0, 3.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellC, 0, 2.0))

        val horizon = NOW + 48 * HOUR
        val served = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon)
            .filter { it.auto }
            .groupBy { it.taskId }
            .mapValues { (_, ps) -> ps.sumOf { it.endEpochMillis - it.startEpochMillis } }
        val total = served.values.sum().toDouble()
        assertEquals(0.5, (served[a] ?: 0L) / total, 0.05)
        assertEquals(0.3, (served[b] ?: 0L) / total, 0.05)
        assertEquals(0.2, (served[c] ?: 0L) / total, 0.05)
    }

    @Test
    fun a_massive_past_exclusion_buys_a_bounded_compensation_not_an_equal_one() {
        // A pinned solid for 17 hours right up to `now`. `docs/scheduler_score.md`: B's compensation around a
        // deprivation of any length is bounded by 2·π_B·τ_B (90 minutes for two equal 45-minute tasks), and it
        // decays with the distance — so B leads, but is NOT handed 17 hours back.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val s = s0.copy(panels = listOf(pinned("pin/0", a, NOW - 17 * HOUR, NOW)))

        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 48 * HOUR).filter { it.auto }
        val spans = autoSpans(autos)
        fun servedIn(task: TaskId, until: Long) =
            autos.filter { it.taskId == task }.sumOf { maxOf(0L, minOf(it.endEpochMillis, until) - it.startEpochMillis) }
        assertTrue(servedIn(b, NOW + 2 * HOUR) > servedIn(a, NOW + 2 * HOUR), "B is compensated right after A's block")
        assertTrue(spans.filter { it.first == b }.all { it.second <= 3 * HOUR }, "the catch-up is bounded: $spans")
        // …and it settles back onto 50/50 rather than repaying hour for hour.
        val served = spans.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }
        val share = (served[b] ?: 0L).toDouble() / served.values.sum()
        assertEquals(0.5, share, 0.05, "B should end up near half the window, got $share")
    }

    @Test
    fun a_block_committed_ahead_swells_the_other_task_around_it() {
        // § *Priority, Granularity and Compensation*: a pre-placed hour of A deprives B, and the influence of the
        // repayment decays with the distance from the blockage on both sides — so B holds more than half of the
        // stretch just before the block and just after it.
        val (s0, ids) = stateWithTasks("A", "B", minMinutes = 10)
        val (a, b) = ids
        val blockStart = NOW + 100 * MIN
        val blockEnd = blockStart + HOUR
        val s = s0.copy(panels = listOf(pinned("pin/0", a, blockStart, blockEnd)))
        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 12 * HOUR).filter { it.auto }
        fun servedIn(task: TaskId, from: Long, until: Long) =
            autos.filter { it.taskId == task }.sumOf { maxOf(0L, minOf(it.endEpochMillis, until) - maxOf(it.startEpochMillis, from)) }
        assertTrue(servedIn(b, blockStart - 40 * MIN, blockStart) > servedIn(a, blockStart - 40 * MIN, blockStart))
        assertTrue(servedIn(b, blockEnd, blockEnd + 40 * MIN) > servedIn(a, blockEnd, blockEnd + 40 * MIN))
        assertTrue(autos.any { it.taskId == a }, "A must still hold its own share")
    }

    @Test
    fun a_zero_priority_task_only_runs_where_it_is_the_only_task_a_period_accepts() {
        // OmniApp's "period that accepts a set of tasks" is the §9 screen zone: an off-screen task may run
        // ONLY inside a no-screen period, an on-screen task only outside one. A 0 % task is kept out of the
        // share model (the reference would drop it outright) but must still fill a period nothing else can.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        var s = s0
        val cellB = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellB, 0, 0.0))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetTaskResilience(b, PeriodKinds.NO_SCREEN, 1.0),
        )
        val noScreen =
            TaskPanel("ns/0", null, "No screen", NOW + 2 * HOUR, NOW + 4 * HOUR, noScreen = true)
        s = s.copy(panels = listOf(noScreen))

        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 8 * HOUR).filter { it.auto }
        val bBlocks = autos.filter { it.taskId == b }
        assertTrue(bBlocks.isNotEmpty(), "B must fill the no-screen window nothing else can occupy")
        assertTrue(
            bBlocks.all { it.startEpochMillis >= NOW + 2 * HOUR && it.endEpochMillis <= NOW + 4 * HOUR },
            "the zero-priority task escaped its period: ${bBlocks.map { it.startEpochMillis - NOW }}",
        )
        assertTrue(autos.any { it.taskId == a }, "A should hold the rest of the window")
    }

    @Test
    fun a_task_deprived_of_the_past_does_not_starve_the_other_one_forever() {
        // The compensation is bounded in both directions: a task served heavily in the past is behind, but the
        // forgetting brings it back — the plan may never permanently exclude a task.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, _) = ids
        val s = s0.copy(panels = listOf(pinned("pin/0", a, NOW - 40 * HOUR, NOW)))
        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 72 * HOUR).filter { it.auto }
        assertTrue(autos.any { it.taskId == a }, "A never comes back: ${autos.map { it.taskId }.distinct()}")
    }

    @Test
    fun a_sole_task_holds_the_whole_timeline_as_one_merged_block() {
        val (s, ids) = stateWithTasks("A")
        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 8 * HOUR).filter { it.auto }
        assertEquals(1, autos.size, "consecutive slots of one task must merge into one panel: $autos")
        assertEquals(ids.single(), autos.single().taskId)
        assertEquals(NOW, autos.single().startEpochMillis)
        assertEquals(NOW + 8 * HOUR, autos.single().endEpochMillis)
    }

    @Test
    fun a_full_horizon_fill_with_the_real_screen_breaks_stays_linear() {
        // CLAUDE.md: a fill that goes quadratic in the number of screen breaks is how the app ends up with a
        // window that never presents a frame. Over 168 h the §15 grid lays down ~500 breaks, i.e. ~1000
        // periods and ~500 exclusion spans per task — the walk must stay comfortably sub-second.
        val (s0, _) = stateWithTasks("A", "B", "C", "D", minMinutes = 30)
        val s = s0.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
        val autos = panels.filter { it.auto }
        assertTrue(autos.isNotEmpty(), "a week-long horizon must materialize work")
        assertTrue(
            autos.maxOf { it.endEpochMillis } >= NOW + 160 * HOUR,
            "the fill stopped short of the horizon: ${(autos.maxOf { it.endEpochMillis } - NOW) / HOUR}h",
        )
        // `side-dev/README.md` § *No idling*, over the whole week: every gap between two consecutive blocks
        // is covered by a restrictive period that refuses everybody. Nothing else may empty a stretch — this
        // replaces an assertion that no panel could be shorter than a minute, which was the removed `crumb`
        // rule stated as a test and is exactly what No idling forbids.
        val ordered = autos.sortedBy { it.startEpochMillis }
        val periods = panels.filter { it.restrictiveKind.isNotEmpty() }
            .map { it.startEpochMillis to it.endEpochMillis }
        for (i in 0 until ordered.size - 1) {
            val from = ordered[i].endEpochMillis
            val until = ordered[i + 1].startEpochMillis
            if (until <= from) continue
            assertTrue(
                periods.any { (start, end) -> start <= from && until <= end },
                "an idle stretch nothing restricts: ${(from - NOW) / MIN}min..${(until - NOW) / MIN}min",
            )
        }
        // The one stretch shorter than a minute is the instant `t_p` itself: mode 1 pushes the swept period
        // onto the line as the half-open `(t_p, t_p + d]`, which leaves that instant uncovered, and the
        // README says in as many words that the line's passing "creat[es] task panels" there.
        // Away from it, a stretch shorter than a minute is only ever the rest of a run a stretch nobody may run in
        // suspended: the same task on both sides of a period that refuses everybody (`docs/scheduler_score.md` — such
        // a stretch does not interrupt a panel).
        val slivers = ordered.filter { it.endEpochMillis - it.startEpochMillis < MIN && it.startEpochMillis != NOW }
        for (sliver in slivers) {
            val before = ordered.lastOrNull { it.endEpochMillis <= sliver.startEpochMillis }
            val after = ordered.firstOrNull { it.startEpochMillis >= sliver.endEpochMillis }
            val continuesBefore = before != null && before.taskId == sliver.taskId &&
                periods.any { (start, end) -> start <= before.endEpochMillis && sliver.startEpochMillis <= end }
            val continuesAfter = after != null && after.taskId == sliver.taskId &&
                periods.any { (start, end) -> start <= sliver.endEpochMillis && after.startEpochMillis <= end }
            assertTrue(continuesBefore || continuesAfter, "a degenerate sliver that is part of no suspended run: $sliver")
        }
    }

    // ----- the trigger rule ---------------------------------------------------------------------

    @Test
    fun the_scheduling_signature_ignores_time_passing_and_the_panels_the_fill_regenerates() {
        val (s, _) = stateWithTasks("A", "B")
        val before = SchedulerDomain.schedulingSignature(s)
        // Filling the schedule must not move the signature — otherwise every fill would trigger the next.
        val filled = s.copy(panels = SchedulerDomain.fillSchedule(s, NOW))
        assertEquals(before, SchedulerDomain.schedulingSignature(filled))
        // Nor may advancing the now-line over it (records banked, panels re-derived).
        val advanced = SchedulerReducer.reduce(filled, SchedulerIntent.AdvanceSchedule(NOW + 3 * HOUR))
        assertEquals(before, SchedulerDomain.schedulingSignature(advanced))
        // Nor a refill an hour later, which lays down a completely different set of auto panels.
        val refilled = advanced.copy(panels = SchedulerDomain.fillSchedule(advanced, NOW + 3 * HOUR))
        assertEquals(before, SchedulerDomain.schedulingSignature(refilled))
    }

    @Test
    fun the_scheduling_signature_moves_on_every_kind_of_rule_change() {
        val (s, ids) = stateWithTasks("A", "B")
        val (a, _) = ids
        val base = SchedulerDomain.schedulingSignature(s)
        fun changed(label: String, next: SchedulerState) =
            assertNotEquals(base, SchedulerDomain.schedulingSignature(next), "$label must re-plan")

        changed("a minimum time", SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 90)))
        changed(
            "a priority weight",
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.SetPriorityWeight(s.lists[s.rootListId]!!.cellIds[0], 0, 4.0),
            ),
        )
        changed(
            "a task title",
            SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Z")),
        )
        changed(
            "an on/off-screen flag",
            SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(a, PeriodKinds.NO_SCREEN, 1.0)),
        )
        changed("a pinned block", s.copy(panels = listOf(pinned("pin/0", a, NOW + HOUR, NOW + 2 * HOUR))))
        changed(
            "a no-screen period",
            s.copy(panels = listOf(TaskPanel("ns/0", null, "n", NOW, NOW + HOUR, noScreen = true))),
        )
        changed("the sleep schedule", s.copy(sleep = SleepSchedule(wakeMinutes = 400)))
        changed("the screen breaks", s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS))
        changed(
            "a screen break's own timing",
            // There is no anchor to move any more (ADR 0003): what re-plans is the CONFIGURATION — a break's
            // length or its recurrence bar. The rest stretches that place it are read off the timeline, and
            // the panels the timeline is made of are already in the signature.
            s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
                .let { seeded ->
                    val retimed = seeded.copy(
                        screenBreaks = seeded.screenBreaks.map { it.copy(intervalMillis = it.intervalMillis + MIN) },
                    )
                    assertNotEquals(
                        SchedulerDomain.schedulingSignature(seeded),
                        SchedulerDomain.schedulingSignature(retimed),
                        "a screen break's recurrence bar must re-plan",
                    )
                    retimed
                },
        )
        changed("the §7 automatic-schedule switch", s.copy(automaticSchedule = false))
    }

    @Test
    fun extending_the_horizon_keeps_the_plan_and_only_appends_to_it() {
        // The rolling horizon and calendar navigation must not re-plan: what is already materialized ahead of
        // the now-line stays put (the user is looking at it), and the tail continues the same plan.
        val (s0, _) = stateWithTasks("A", "B")
        val near = SchedulerDomain.fillSchedule(s0, NOW, horizonMillis = NOW + 12 * HOUR)
        val s = s0.copy(panels = near)
        val materializedUntil = SchedulerDomain.firstFreeMoment(near, NOW)

        val extended =
            SchedulerDomain.fillSchedule(
                s,
                NOW + HOUR,
                horizonMillis = NOW + 36 * HOUR,
                keepExistingUntilMillis = materializedUntil,
            )
        // Every previously-planned block still exists, unchanged.
        for (panel in near.filter { it.auto }) {
            assertTrue(
                extended.any {
                    it.taskId == panel.taskId && it.startEpochMillis == panel.startEpochMillis &&
                        it.endEpochMillis == panel.endEpochMillis
                },
                "the extension rewrote an existing block: $panel",
            )
        }
        // …and the plan now reaches further out.
        assertTrue(
            extended.filter { it.auto }.maxOf { it.endEpochMillis } >
                near.filter { it.auto }.maxOf { it.endEpochMillis },
        )
        // A plain refill, by contrast, IS free to re-plan the FUTURE: none of the blocks it had materialized
        // ahead of the line survives it. What does survive is the elapsed head of the block the line is
        // standing in — `side-dev/README.md` § *frozen past*, and the reason a refill's first block may span
        // the line rather than start at it.
        val replanned = SchedulerDomain.fillSchedule(s, NOW + HOUR, horizonMillis = NOW + 36 * HOUR)
        val keptAcrossTheLine = replanned.filter { it.auto }
            .filter { it.startEpochMillis < NOW + HOUR && it.endEpochMillis > NOW + HOUR }
        assertTrue(keptAcrossTheLine.size <= 1, "at most the block the line is standing in: $keptAcrossTheLine")
        // …and it is the very block the line was standing in, continued rather than replaced. (That a refill
        // and an extension may otherwise agree about the future is not a defect but the resume contract — a
        // chain of re-plans is the same schedule as one long plan — so this deliberately asserts nothing
        // about the blocks ahead of the line.)
        val wasOnTheLine = near.first {
            it.auto && it.startEpochMillis <= NOW + HOUR && NOW + HOUR < it.endEpochMillis
        }
        assertEquals(wasOnTheLine.taskId, keptAcrossTheLine.single().taskId)
        assertEquals(wasOnTheLine.startEpochMillis, keptAcrossTheLine.single().startEpochMillis)
    }

    @Test
    fun a_re_plan_shorter_than_the_old_plan_drops_the_old_plan_beyond_it() {
        // A fill that stops short of an older, longer plan — a progressive first stage, or the ten-minute goal
        // floor with the calendar closed — must not leave that plan's auto panels past its horizon: they are the
        // answer to rules this re-plan may have replaced, and a stale panel abutting the new tail is what the
        // next extension would keep as definitive.
        val (s0, _) = stateWithTasks("A", "B", minMinutes = 30)
        val old = s0.copy(panels = SchedulerDomain.fillSchedule(s0, NOW, horizonMillis = NOW + 12 * HOUR))
        assertTrue(old.panels.any { it.auto && it.startEpochMillis > NOW + HOUR }, "test premise: a long old plan")
        val horizon = NOW + HOUR
        val replanned = SchedulerDomain.fillSchedule(old, NOW, horizonMillis = horizon)
        assertTrue(
            replanned.none { it.auto && it.startEpochMillis > horizon },
            "the re-plan kept ${replanned.count { it.auto && it.startEpochMillis > horizon }} old auto panel(s) past its horizon",
        )
    }

    @Test
    fun progressive_stages_never_rewrite_what_an_earlier_stage_made_definitive() {
        // `docs/scheduler_requirements.md` § *Progressive Calculation*: the engine re-plans to the first stage and
        // then EXTENDS to twice as far each time. Once a stage is published its schedule is definitive: every later
        // stage must keep it verbatim, and reach further.
        val (s0, _) = stateWithTasks("A", "B", "C", minMinutes = 30)
        val goal = NOW + 40 * HOUR
        SchedulerReducer.scheduleHorizonEndMillis = { goal }
        try {
            var s = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(NOW, NOW + HOUR))
            var stage = HOUR
            while (true) {
                val before = s.panels.filter { it.auto && it.startEpochMillis >= NOW }
                val reach = before.maxOf { it.endEpochMillis }
                stage *= 2
                val cap = (NOW + stage).takeIf { it < goal }
                s = SchedulerReducer.reduce(s, SchedulerIntent.ExtendSchedule(NOW, cap))
                val after = s.panels.filter { it.auto && it.startEpochMillis >= NOW }
                for (p in before) {
                    assertTrue(
                        after.any {
                            it.taskId == p.taskId && it.startEpochMillis == p.startEpochMillis &&
                                it.endEpochMillis == p.endEpochMillis
                        },
                        "the stage to ${((cap ?: goal) - NOW) / HOUR} h rewrote a definitive panel: $p",
                    )
                }
                assertTrue(after.maxOf { it.endEpochMillis } > reach, "every stage reaches further")
                if (cap == null) break
            }
            assertTrue(s.panels.filter { it.auto }.maxOf { it.endEpochMillis } >= goal - HOUR, "the last stage reaches the goal")
        } finally {
            CalendarHorizonFixture.close()
        }
    }

    @Test
    fun an_extension_continues_the_plan_rather_than_restarting_the_rotation() {
        // The kept head is fed to the walk as committed service, so the appended tail keeps alternating from
        // where the plan left off instead of restarting as if nothing had been served.
        val (s0, ids) = stateWithTasks("A", "B", minMinutes = 30)
        val near = SchedulerDomain.fillSchedule(s0, NOW, horizonMillis = NOW + 4 * HOUR)
        val s = s0.copy(panels = near)
        val extended =
            SchedulerDomain.fillSchedule(
                s,
                NOW,
                horizonMillis = NOW + 12 * HOUR,
                keepExistingUntilMillis = SchedulerDomain.firstFreeMoment(near, NOW),
            )
        val order = autoSpans(extended).map { it.first }
        // No task is ever scheduled twice in a row across the seam (they would have been merged), and both
        // tasks keep appearing in the appended tail.
        for (i in 1 until order.size) assertNotEquals(order[i - 1], order[i], "the rotation stalled at $i: $order")
        assertEquals(ids.toSet(), order.toSet())
    }
}
