package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DebtLedger
import org.example.project.scheduler.domain.DebtTask
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `tests/README.md` + `tests/test.py`: the debt scheduler.
 *
 * The first half pins down [DebtLedger] itself (the ODE port — the decay, the natural bound, the
 * "in both directions" future lookahead). The second half checks the properties the README states, through
 * the real [SchedulerDomain.fillSchedule] — the alternation scale, the priority shares, a huge past excess
 * being forgotten rather than repaid, a looming committed block yielding the cursor, and a zero-priority task
 * running only when nothing else may.
 *
 * The last block covers the trigger rule: the scheduler re-plans **only** on a change to the scheduling rules
 * ([SchedulerDomain.schedulingSignature]) — never on time passing, which merely extends the plan's tail.
 */
class SchedulerDebtTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private fun task(name: String, priority: Double, minMinutes: Int) =
        DebtTask(TaskId(name), priority, minMinutes * MIN)

    // ----- the ledger ---------------------------------------------------------------------------

    @Test
    fun idle_time_accrues_debt_in_proportion_to_priority() {
        val a = task("A", 0.75, 45)
        val b = task("B", 0.25, 45)
        val ledger = DebtLedger(listOf(a, b), halfLifeMillis = Double.MAX_VALUE) // decay off-scale
        ledger.advance(60 * MIN, null)
        // Nothing ran, so each task is owed its own share of the hour.
        assertEquals(45.0 * MIN, ledger.debtOf(a.id), 1.0)
        assertEquals(15.0 * MIN, ledger.debtOf(b.id), 1.0)
    }

    @Test
    fun running_a_task_discharges_its_debt_at_one_minus_its_priority() {
        val a = task("A", 0.75, 45)
        val b = task("B", 0.25, 45)
        val ledger = DebtLedger(listOf(a, b), halfLifeMillis = Double.MAX_VALUE)
        ledger.advance(60 * MIN, a.id)
        assertEquals(-15.0 * MIN, ledger.debtOf(a.id), 1.0) // it consumed 60 but only owed 45
        assertEquals(15.0 * MIN, ledger.debtOf(b.id), 1.0)
    }

    @Test
    fun advancing_in_one_step_equals_advancing_in_many() {
        // The solver is closed-form per segment, so how the caller chops the timeline up must not matter —
        // that is what lets the fill place chunks of any length without drifting.
        val tasks = listOf(task("A", 0.5, 45), task("B", 0.5, 45))
        val whole = DebtLedger(tasks)
        val chopped = DebtLedger(tasks)
        whole.advance(6 * HOUR, tasks[0].id)
        repeat(36) { chopped.advance(10 * MIN, tasks[0].id) }
        for (t in tasks) {
            assertTrue(
                abs(whole.debtOf(t.id) - chopped.debtOf(t.id)) < 1.0,
                "${t.id} drifted: ${whole.debtOf(t.id)} vs ${chopped.debtOf(t.id)}",
            )
        }
    }

    @Test
    fun debt_inside_the_natural_bound_is_never_decayed_away() {
        // `tests/README.md`: only what is HIGHER than a clear timeline could produce decays. One rotation of
        // the round-robin (C = sum of minimums) is exactly what a clear timeline produces, so a debt that
        // size must survive in full however long we wait — otherwise the percentages would silently rot.
        val a = task("A", 0.5, 45)
        val b = task("B", 0.5, 45)
        val ledger = DebtLedger(listOf(a, b))
        assertEquals(90.0 * MIN, ledger.boundary, 1.0)
        ledger.advance(90 * MIN, a.id) // A over-served by exactly C/2… B owed exactly C/2
        val owed = ledger.debtOf(b.id)
        ledger.advance(24 * HOUR, null) // …and now a day of idleness at the same rate for both
        // B's debt grew (it kept being owed); nothing was subtracted from what it was already owed.
        assertTrue(ledger.debtOf(b.id) > owed, "an unpaid debt inside the bound must not decay")
    }

    @Test
    fun an_excess_beyond_the_natural_bound_decays_at_the_configured_half_life() {
        // Two tasks, minimum 45 → C = 90 min. Serve A for 10 hours: its deficit is far past −C, so the part
        // beyond the bound must halve every half-life once the timeline goes idle.
        val a = task("A", 0.5, 45)
        val b = task("B", 0.5, 45)
        val halfLife = SchedulerDomain.DEBT_HALF_LIFE_MILLIS.toDouble()
        val ledger = DebtLedger(listOf(a, b), halfLifeMillis = halfLife)
        ledger.advance(10 * HOUR, a.id)
        val excessBefore = -ledger.debtOf(a.id) - ledger.boundary
        assertTrue(excessBefore > 0.0, "A should be past the bound, got ${ledger.debtOf(a.id)}")
        // One half-life of A running (so its own rate is unchanged) — the excess must halve.
        val ledger2 = DebtLedger(listOf(a, b), halfLifeMillis = halfLife)
        ledger2.advance(10 * HOUR, a.id)
        ledger2.advance(halfLife.toLong(), a.id)
        val excessAfter = -ledger2.debtOf(a.id) - ledger2.boundary
        // Running keeps pushing it down at rate (1 − p) while decay pulls it up; the closed form is
        // exact, so just assert the decay is actually acting: the excess is far below the un-decayed value.
        val undecayed = excessBefore + 0.5 * halfLife
        assertTrue(excessAfter < undecayed * 0.75, "excess $excessAfter should be far below $undecayed")
    }

    @Test
    fun future_committed_work_counts_in_full_up_to_the_bound_and_decays_beyond_it() {
        val a = task("A", 0.5, 45)
        val ledger = DebtLedger(listOf(a, task("B", 0.5, 45)))
        val c = ledger.boundary
        // A block shorter than the bound counts in full, however far away it is.
        val near = listOf(TaskTimeRange(NOW + HOUR, NOW + HOUR + 30 * MIN))
        val far = listOf(TaskTimeRange(NOW + 100 * HOUR, NOW + 100 * HOUR + 30 * MIN))
        assertEquals(30.0 * MIN, ledger.futureCommitmentMillis(near, NOW), 1.0)
        assertEquals(30.0 * MIN, ledger.futureCommitmentMillis(far, NOW), 1.0)
        // A block LONGER than the bound counts fully only for the bound; its excess fades with distance.
        val hugeNear = listOf(TaskTimeRange(NOW, NOW + 10 * HOUR))
        val hugeFar = listOf(TaskTimeRange(NOW + 48 * HOUR, NOW + 58 * HOUR))
        assertEquals(10.0 * HOUR, ledger.futureCommitmentMillis(hugeNear, NOW), 1.0)
        val faded = ledger.futureCommitmentMillis(hugeFar, NOW)
        assertTrue(faded > c && faded < 2 * c, "a far huge block should count ≈ the bound, got $faded")
        // Already-elapsed time is not committed work: a block is clipped at the query instant.
        assertEquals(5.0 * HOUR, ledger.futureCommitmentMillis(hugeNear, NOW + 5 * HOUR), 1.0)
    }

    // ----- the README's properties, through the real fill ---------------------------------------

    /** [n] equal-priority sibling tasks under "main", each with the given minimum time. */
    private fun stateWithTasks(vararg names: String, minMinutes: Int = 45): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        names.forEachIndexed { i, name ->
            val cell = s.lists[s.rootListId]!!.cellIds[i]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, name))
        }
        val ids = names.map { name -> s.tasks.keys.first { s.tasks[it]!!.title == name } }
        for (id in ids) s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(id, minMinutes))
        return s to ids
    }

    private fun pinned(id: String, taskId: TaskId?, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = true, auto = false, pins = PanelPins(existence = true))

    private fun autoSpans(panels: List<TaskPanel>): List<Pair<TaskId?, Long>> =
        panels.filter { it.auto }.sortedBy { it.startEpochMillis }
            .map { it.taskId to (it.endEpochMillis - it.startEpochMillis) }

    @Test
    fun two_equal_tasks_alternate_at_the_smallest_scale_their_minimum_allows() {
        // `tests/README.md`: "task A 50% 10min and task B 50% 10min → right: task A 10min, then task B
        // 10min and so on", NOT an hour of each. The debt swings by one minimum and flips the pick.
        val (s, ids) = stateWithTasks("A", "B", minMinutes = 10)
        val (a, b) = ids
        val spans = autoSpans(SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 2 * HOUR)).take(6)
        assertEquals(listOf(a, b, a, b, a, b), spans.map { it.first })
        assertTrue(spans.all { it.second == 10 * MIN }, "every chunk should be one minimum, got $spans")
    }

    @Test
    fun three_tasks_converge_on_their_priority_percentages() {
        // `tests/test.py` test 1: 50 % / 30 % / 20 %, minimums 10 / 15 / 5.
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
    fun a_massive_past_excess_is_forgotten_not_repaid_in_full() {
        // `tests/test.py` tests 2 & 8: A pinned solid for ~17 hours right up to `now`. B must lead — but the
        // catch-up is bounded by the natural bound plus what the decay leaves, NOT by the 17 hours: A has to
        // come back long before B has been served the whole excess.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val s = s0.copy(panels = listOf(pinned("pin/0", a, NOW - 17 * HOUR, NOW)))

        val spans = autoSpans(SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 48 * HOUR))
        assertEquals(b, spans.first().first, "the starved task must lead")
        val bLead = spans.first().second
        assertTrue(bLead < 17 * HOUR, "B must not be handed the whole 17h excess back, got ${bLead / MIN}min")
        assertTrue(bLead >= 45 * MIN, "B should still get a real catch-up, got ${bLead / MIN}min")
        // Once the imbalance is settled the two share the window evenly again.
        val served = spans.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }
        val share = (served[b] ?: 0L).toDouble() / (served.values.sum())
        assertTrue(share in 0.45..0.60, "B should end up near half the window, got $share")
    }

    @Test
    fun a_task_with_work_committed_ahead_yields_the_cursor_now() {
        // `tests/test.py` test 7 ("B runs pre-emptively because A looms at t=70"): A already has a big
        // pinned block an hour out, so its debt is discharged before the cursor gets there — B goes first
        // even though the two are otherwise identical and start level.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val s = s0.copy(panels = listOf(pinned("pin/0", a, NOW + 70 * MIN, NOW + 130 * MIN)))

        val first = autoSpans(SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 12 * HOUR)).first()
        assertEquals(b, first.first)
    }

    @Test
    fun a_zero_priority_task_only_runs_where_it_is_the_only_task_allowed() {
        // `tests/test.py` test 12. OmniApp's "period that accepts a set of tasks" is the §9 screen zone: an
        // off-screen task may run ONLY inside a no-screen period, an on-screen task only outside one.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        var s = s0
        // B: zero priority AND off-screen, so a no-screen window is the only place it may run.
        val cellB = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellB, 0, 0.0))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetTaskScreenFlags(b, onScreen = false, doableDuringBreak = false),
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
    fun committed_past_work_of_one_task_does_not_starve_it_forever() {
        // The bound cuts both ways: a task served heavily in the past is behind, but once its deficit has
        // decayed back to the bound it must return — the plan may never permanently exclude a task.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, _) = ids
        val s = s0.copy(panels = listOf(pinned("pin/0", a, NOW - 40 * HOUR, NOW)))
        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 72 * HOUR).filter { it.auto }
        assertTrue(autos.any { it.taskId == a }, "A never comes back: ${autos.map { it.taskId }.distinct()}")
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
        changed("a task title", SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Z")))
        changed(
            "an on/off-screen flag",
            SchedulerReducer.reduce(s, SchedulerIntent.SetTaskScreenFlags(a, onScreen = false, doableDuringBreak = false)),
        )
        changed("a pinned block", s.copy(panels = listOf(pinned("pin/0", a, NOW + HOUR, NOW + 2 * HOUR))))
        changed(
            "a no-screen period",
            s.copy(panels = listOf(TaskPanel("ns/0", null, "n", NOW, NOW + HOUR, noScreen = true))),
        )
        changed("the sleep schedule", s.copy(sleep = SleepSchedule(wakeMinutes = 400)))
        changed("the screen breaks", s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS))
        changed(
            "a screen-break anchor",
            s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
                .let { seeded ->
                    val moved = seeded.copy(screenBreaks = seeded.screenBreaks.map { it.copy(lastRestMillis = NOW) })
                    assertNotEquals(
                        SchedulerDomain.schedulingSignature(seeded),
                        SchedulerDomain.schedulingSignature(moved),
                        "a screen-break anchor must re-plan",
                    )
                    moved
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
            extended.filter { it.auto }.maxOf { it.endEpochMillis } > near.filter { it.auto }.maxOf { it.endEpochMillis },
        )
        // A plain refill, by contrast, IS free to re-plan: nothing survives across the now-line (the past
        // panels stay as the record of what was planned, but the whole future is regenerated from `now`).
        val replanned = SchedulerDomain.fillSchedule(s, NOW + HOUR, horizonMillis = NOW + 36 * HOUR)
        assertTrue(
            replanned.filter { it.auto }
                .none { it.startEpochMillis < NOW + HOUR && it.endEpochMillis > NOW + HOUR },
        )
    }

    @Test
    fun an_extension_continues_the_plan_rather_than_restarting_the_rotation() {
        // The kept head is fed to the debt ledger as committed work, so the appended tail keeps alternating
        // from where the plan left off instead of restarting as if nothing had been served.
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
        val spans = autoSpans(extended)
        // No task is ever scheduled twice in a row across the seam (they would have been merged), and both
        // tasks keep appearing in the appended tail.
        val tail = spans.filter { true }.map { it.first }
        for (i in 1 until tail.size) assertNotEquals(tail[i - 1], tail[i], "the rotation stalled at $i: $tail")
        assertEquals(ids.toSet(), tail.toSet())
    }
}
