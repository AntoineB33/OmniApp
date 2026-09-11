package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock
import org.example.project.ui.PlacedRecord
import org.example.project.ui.overlapLayout

/**
 * PRD §8: **two overlapping "No screen" periods are ONE period — their union.**
 *
 * A no-screen period does not own a slice of the timeline the way a task panel does; it is the statement
 * *no screen was in use here*, and two overlapping statements of it say one thing. So they must never come
 * out as two blocks splitting the day column's width between them (Overlap Mode's `overlapLayout`) — that
 * shape is for panels genuinely competing for the same hours. The scheduler has always read them merged
 * (`mergeOccupied`); this pins the same reading into the state the calendar draws.
 *
 * The rule has one funnel, [SchedulerDomain.unifyNoScreenPeriods], reached from two places: the reducer's
 * `resolveScreenOverrides` (every point a period is laid, moved or resized) and
 * [SchedulerStateCodec]'s decode, which heals a payload an older build wrote (CLAUDE.md
 * § *Persisted-DB compatibility*).
 */
class NoScreenPeriodUnifyTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L // fixed reference instant

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    /** Runs [body] with the reducer's clock pinned at [NOW] (the record strip refills the schedule off it). */
    private fun withClock(body: () -> Unit) {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(NOW)
        try {
            body()
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    private fun noScreenPeriods(s: SchedulerState): List<TaskPanel> =
        s.panels.filter { it.noScreen }.sortedBy { it.startEpochMillis }

    private fun span(p: TaskPanel): Pair<Long, Long> = p.startEpochMillis to p.endEpochMillis

    /** A single leaf task "Solo", on screen. */
    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        return s to solo
    }

    /** Banks `[start, end]` as completed work for [taskId], bypassing the advance tick. */
    private fun withRecord(s: SchedulerState, taskId: TaskId, start: Long, end: Long): SchedulerState {
        val task = s.tasks.getValue(taskId)
        return s.copy(tasks = s.tasks + (taskId to task.copy(record = task.record + TaskTimeRange(start, end))))
    }

    // ----- laying one over another ------------------------------------------------------------

    @Test
    fun a_no_screen_period_added_over_another_unifies_into_the_union() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + HOUR, NOW + 3 * HOUR))
        val period = noScreenPeriods(s).single()
        assertEquals(NOW to NOW + 3 * HOUR, span(period))
        // Still a period in every other respect — the fuse only moves the bounds.
        assertEquals("No screen", period.title)
        assertEquals(PeriodKinds.NO_SCREEN, period.restrictiveKind)
        assertTrue(period.pins.existence)
    }

    @Test
    fun a_period_added_inside_another_leaves_the_wider_one_alone() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + 4 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + HOUR, NOW + 2 * HOUR))
        assertEquals(NOW to NOW + 4 * HOUR, span(noScreenPeriods(s).single()))
    }

    @Test
    fun a_period_laid_across_two_others_swallows_both() {
        // Transitive: the newcomer overlaps A and B, which do not overlap each other.
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + 4 * HOUR, NOW + 5 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + 30 * 60_000, NOW + 4 * HOUR + 30 * 60_000))
        assertEquals(NOW to NOW + 5 * HOUR, span(noScreenPeriods(s).single()))
    }

    @Test
    fun two_periods_that_only_abut_stay_two_periods() {
        // They already draw full-width (no time slice holds both), and each is still an object the user can
        // remove on its own — so nothing is fused away.
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + HOUR, NOW + 2 * HOUR))
        assertEquals(
            listOf(NOW to NOW + HOUR, NOW + HOUR to NOW + 2 * HOUR),
            noScreenPeriods(s).map(::span),
        )
    }

    @Test
    fun a_disjoint_period_is_untouched() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + 3 * HOUR, NOW + 4 * HOUR))
        assertEquals(
            listOf(NOW to NOW + HOUR, NOW + 3 * HOUR to NOW + 4 * HOUR),
            noScreenPeriods(s).map(::span),
        )
    }

    @Test
    fun an_inactivity_period_overlapping_a_no_screen_one_is_not_fused() {
        // Different kinds say different things (grey refuses everybody; no-screen only the on-screen tasks),
        // so there is no union to take — they are two periods and the calendar shows them as two.
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, NOW + HOUR, NOW + 3 * HOUR))
        assertEquals(NOW to NOW + 2 * HOUR, span(noScreenPeriods(s).single()))
        assertEquals(NOW + HOUR to NOW + 3 * HOUR, span(s.panels.single { it.inactivity }))
    }

    @Test
    fun the_fuse_is_one_undo_step() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + HOUR, NOW + 3 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(NOW to NOW + 2 * HOUR, span(noScreenPeriods(undone).single()))
    }

    // ----- dragging one onto another ----------------------------------------------------------

    @Test
    fun a_period_dragged_onto_another_unifies_with_it() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + 6 * HOUR, NOW + 7 * HOUR))
        val dragged = noScreenPeriods(s).last()
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel(
                id = dragged.id,
                taskId = null,
                title = dragged.title,
                startEpochMillis = NOW + HOUR,
                endEpochMillis = NOW + 3 * HOUR,
                pins = dragged.pins,
            ),
        )
        val period = noScreenPeriods(s).single()
        assertEquals(NOW to NOW + 3 * HOUR, span(period))
        // The panel the user was holding is the one that survives — the drag must not vanish under them.
        assertEquals(dragged.id, period.id)
    }

    // ----- the union is what the other §8 rules then apply to -----------------------------------

    @Test
    fun the_override_and_the_record_strip_run_over_the_whole_union() = withClock {
        val (s0, solo) = stateWithOneTask()
        // Work banked over the hour that only the FUSED span covers, and an on-screen panel over it too.
        var s = withRecord(s0, solo, NOW - 3 * HOUR, NOW - 2 * HOUR)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(solo, "Solo", NOW - 3 * HOUR, NOW - 2 * HOUR, PanelPins(existence = true)),
        )
        // An existing period over the earlier hours, then one laid so that the two fuse across the work.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW - 6 * HOUR, NOW - 4 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW - 5 * HOUR, NOW - 2 * HOUR))

        assertEquals(NOW - 6 * HOUR to NOW - 2 * HOUR, span(noScreenPeriods(s).single()))
        assertTrue(
            s.tasks.getValue(solo).record.isEmpty(),
            "the work under the fused span must go the way it would under a period drawn that wide",
        )
        assertTrue(
            s.panels.none { it.taskId == solo && !it.auto },
            "and so must the on-screen panel it covers",
        )
    }

    // ----- what the user actually sees ---------------------------------------------------------

    @Test
    fun the_unified_period_takes_the_whole_day_column_width() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + HOUR, NOW + 3 * HOUR))
        // The blocks the calendar would slice: one per no-screen panel, laid out over the same hours.
        val blocks =
            noScreenPeriods(s).map { p ->
                PlacedRecord(
                    title = p.title,
                    startHour = (p.startEpochMillis - NOW).toFloat() / HOUR,
                    endHour = (p.endEpochMillis - NOW).toFloat() / HOUR,
                    scheduled = false,
                    manual = true,
                    entryId = p.id,
                    noScreen = true,
                )
            }
        val slices = overlapLayout(blocks).values.flatten()
        assertEquals(1, slices.size, "one period, one slice — not a stepped, shared-width shape")
        assertEquals(0f, slices.single().xFraction, 1e-3f)
        assertEquals(1f, slices.single().widthFraction, 1e-3f)
    }

    // ----- healing what an older build wrote ----------------------------------------------------

    @Test
    fun decode_heals_overlapping_periods_an_older_build_persisted() {
        // Persisted-DB rule: a payload written before this rule existed holds the two panels the user drew,
        // and it must load as the one period the invariant now says they are.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[],
             "panels":[
               {"id":"panel/0","taskId":null,"title":"No screen","start":0,"end":7200000,"noScreen":true},
               {"id":"panel/1","taskId":null,"title":"No screen","start":3600000,"end":10800000,"noScreen":true}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val period = decoded.panels.single { it.noScreen }
        assertEquals(0L to 10_800_000L, span(period))
        assertEquals("panel/0", period.id, "the run's earliest keeps its id when no panel is being edited")
    }

    @Test
    fun decode_leaves_a_state_that_already_holds_the_rule_untouched() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + 3 * HOUR, NOW + 4 * HOUR))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(
            listOf(NOW to NOW + HOUR, NOW + 3 * HOUR to NOW + 4 * HOUR),
            noScreenPeriods(decoded).map(::span),
        )
    }

    // ----- the funnel itself ---------------------------------------------------------------------

    @Test
    fun the_domain_rule_touches_nothing_but_the_overlapping_periods() {
        val other = TaskPanel(id = "p/task", taskId = TaskId("t"), title = "T", startEpochMillis = 0, endEpochMillis = 99)
        val a = TaskPanel(id = "p/a", taskId = null, title = "No screen", startEpochMillis = 0, endEpochMillis = 10, noScreen = true)
        val b = TaskPanel(id = "p/b", taskId = null, title = "No screen", startEpochMillis = 5, endEpochMillis = 20, noScreen = true)
        val panels = listOf(other, a, b)

        val fused = SchedulerDomain.unifyNoScreenPeriods(panels)
        assertEquals(listOf("p/task", "p/a"), fused.map { it.id }, "order and the untouched panels are kept")
        assertEquals(0L to 20L, span(fused.single { it.noScreen }))

        // `keepId` names the survivor: the panel the caller is about to apply the override rule for.
        assertEquals("p/b", SchedulerDomain.unifyNoScreenPeriods(panels, keepId = "p/b").single { it.noScreen }.id)

        // Identity-stable when there is nothing to fuse — the common case, on every edit.
        val disjoint = listOf(other, a, b.copy(startEpochMillis = 50, endEpochMillis = 60))
        assertTrue(SchedulerDomain.unifyNoScreenPeriods(disjoint) === disjoint)
    }
}
