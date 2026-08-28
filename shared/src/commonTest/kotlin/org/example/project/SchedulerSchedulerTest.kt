package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CalendarRecord
import org.example.project.ui.recordsForDay

/**
 * Tests for the v1.2.0 scheduler: §9 windowed multi-panel fill ([SchedulerDomain.fillSchedule]),
 * the §9 advance/record tick, §10 minimum time, §7 automatic-schedule switch, and §8 task record
 * (history-excluded, persisted, calendar-mapped).
 */
class SchedulerSchedulerTest {

    private val MIN = 60_000L
    private val HOUR_MS = 3_600_000L

    /** Two equal-priority sibling tasks A and B under "main". */
    private fun stateWithTwoTasks(): Triple<SchedulerState, TaskId, TaskId> {
        var s = SchedulerState.empty()
        val root = s.rootListId
        val c0 = s.lists[root]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "A"))
        val c1 = s.lists[root]!!.cellIds[1] // auto-appended empty placeholder
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c1, "B"))
        val a = s.tasks.keys.first { s.tasks[it]!!.title == "A" }
        val b = s.tasks.keys.first { s.tasks[it]!!.title == "B" }
        return Triple(s, a, b)
    }

    /** Two siblings A and B with priority weights 3 / 1 → absolute priorities 0.75 / 0.25. */
    private fun stateWithWeightedTasks(): Triple<SchedulerState, TaskId, TaskId> {
        val (s0, a, b) = stateWithTwoTasks()
        val cA = s0.lists[s0.rootListId]!!.cellIds[0]
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetPriorityWeight(cA, 0, 3.0))
        return Triple(s, a, b)
    }

    private fun auto(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = false, auto = true)

    private fun pinned(id: String, taskId: TaskId, start: Long, end: Long) =
        // A well-formed pinned panel: the existence pin is the source of [TaskPanel.pinned] (PRD §8).
        TaskPanel(id, taskId, "x", start, end, pinned = true, auto = false, pins = PanelPins(existence = true))

    /** A single task "Solo" with the given minimum time (minutes). */
    private fun stateWithOneTask(minMinutes: Int): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, minMinutes))
        return s to solo
    }

    // ----- §9 task choice: which tasks are schedulable at all ---------------------------------
    // The pick rule itself (the cyclic proportional-share model) is covered by SchedulerPlanTest.

    @Test
    fun schedulable_leaves_exclude_root_main_and_removed_tasks() {
        val (s0, a, b) = stateWithTwoTasks()
        assertEquals(setOf(a, b), SchedulerDomain.schedulableLeaves(s0).toSet())
        // Emptying A's cell removes it from the tree → no longer schedulable.
        val c0 = s0.lists[s0.rootListId]!!.cellIds[0]
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(c0, ""))
        assertFalse(a in SchedulerDomain.schedulableLeaves(s1))
    }

    @Test
    fun removed_task_is_not_scheduled() {
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val c0 = s0.lists[s0.rootListId]!!.cellIds[0]
        // "Remove" A from the tree by emptying its cell.
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(c0, ""))
        val priorities = SchedulerDomain.absoluteTaskPriorities(s1)
        val panels = SchedulerDomain.fillSchedule(s1, now)
        assertTrue(panels.none { it.taskId == a }, "removed A still scheduled: ${panels.map { it.taskId }}")
        assertTrue(panels.any { it.taskId == b }, "survivor B not scheduled. priorities=$priorities")
    }

    @Test
    fun advance_tick_drops_a_removed_tasks_lingering_panels() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A schedule already laid down with A panels; auto-scheduling OFF, so no refill runs and only
        // the frequent advance tick can clean up after the task is removed (PRD §7/§9).
        val scheduled = s0.copy(
            panels = SchedulerDomain.fillSchedule(s0, now),
            automaticSchedule = false,
        )
        assertTrue(scheduled.panels.any { it.taskId == a })
        // Remove A via the Delete-key flow (select A's cell, EmptySelectedCells).
        val cA = scheduled.lists[scheduled.rootListId]!!.cellIds[0]
        val withSel = scheduled.copy(
            selection = org.example.project.scheduler.state.SchedulerSelection(main = cA, selected = setOf(cA)),
        )
        val removed = SchedulerReducer.reduce(withSel, SchedulerIntent.EmptySelectedCells)
        val ticked = SchedulerReducer.reduce(removed, SchedulerIntent.AdvanceSchedule(now))
        assertTrue(
            ticked.panels.none { it.taskId == a },
            "removed A lingers in the schedule: ${ticked.panels.map { it.taskId }}",
        )
    }

    // ----- §9 fillSchedule (windowed multi-panel) --------------------------------------------

    @Test
    fun fill_schedule_lays_a_contiguous_chain_from_now_out_past_the_horizon() {
        val (s, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val panels = SchedulerDomain.fillSchedule(s, now)

        assertTrue(panels.isNotEmpty())
        assertEquals(now, panels.first().startEpochMillis) // first panel starts exactly at now
        assertEquals(a, panels.first().taskId) // A and B tie → A picked first, then rotates
        assertEquals(b, panels[1].taskId)
        // Contiguous & non-overlapping, every panel a real leaf task, all auto.
        for (i in panels.indices) {
            assertTrue(panels[i].auto)
            assertTrue(panels[i].taskId == a || panels[i].taskId == b)
            if (i > 0) assertEquals(panels[i - 1].endEpochMillis, panels[i].startEpochMillis)
        }
        // The window reaches at least 168h ahead.
        assertTrue(panels.last().endEpochMillis >= now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
    }

    @Test
    fun fill_schedule_on_empty_database_is_empty() {
        assertTrue(SchedulerDomain.fillSchedule(SchedulerState.empty(), 1_000L).isEmpty())
    }

    @Test
    fun refill_at_the_same_now_gives_every_panel_a_unique_id_without_overlap() {
        // A re-fill (e.g. the second of §9's two calculation events) cuts every in-window non-pinned
        // panel and regenerates from now, so ids stay unique (auto/0, auto/1, …) and nothing overlaps.
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val firstFill = SchedulerDomain.fillSchedule(s0, now)
        val refilled = SchedulerDomain.fillSchedule(s0.copy(panels = firstFill), now)

        val ids = refilled.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "panel ids must be unique, got $ids")
        val sorted = refilled.sortedBy { it.startEpochMillis }
        for (i in 1 until sorted.size) {
            assertTrue(
                sorted[i].startEpochMillis >= sorted[i - 1].endEpochMillis,
                "panels overlap: ${sorted[i - 1]} vs ${sorted[i]}",
            )
        }
        // The rotation still reads A, B, A, … from now.
        assertEquals(a, sorted.first().taskId)
        assertEquals(b, sorted[1].taskId)
    }

    @Test
    fun steady_state_refill_at_the_same_now_is_a_no_op() {
        // The id normalization must stay deterministic: refilling the kept schedule at the same instant
        // must reproduce the identical panels, so reduceRefreshSchedule's `filled == panels` short-circuit
        // still fires (no spurious calendar history unit on every tick).
        val (s0, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val firstFill = SchedulerDomain.fillSchedule(s0, now)
        val refilled = SchedulerDomain.fillSchedule(s0.copy(panels = firstFill), now)
        assertEquals(firstFill, refilled)
    }

    @Test
    fun fill_schedule_keeps_a_pinned_panel_and_flows_auto_panels_around_it() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A pinned panel sits 2–3h ahead; the auto fill must not overlap it (PRD §10) and must keep it.
        val pin = pinned("panel/0", a, now + 2 * HOUR_MS, now + 3 * HOUR_MS)
        val s = s0.copy(panels = listOf(pin))

        val panels = SchedulerDomain.fillSchedule(s, now)

        assertTrue(pin in panels) // pinned panel survives
        val autos = panels.filter { it.auto }
        // No auto panel overlaps the pinned window.
        assertTrue(autos.none { it.startEpochMillis < pin.endEpochMillis && pin.startEpochMillis < it.endEpochMillis })
        // Some auto panel ends exactly where the pinned one begins (shortened to fit, PRD §10).
        assertTrue(autos.any { it.endEpochMillis == pin.startEpochMillis })
    }

    @Test
    fun fill_schedule_re_derives_the_current_task_from_now() {
        // PRD §9: the in-progress non-pinned panel is cut and the window re-filled from now; the current
        // task is re-picked deterministically (continuity), now starting exactly at `now`.
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val current = auto("auto/0", a, now - 10 * MIN, now + 35 * MIN)
        val s = s0.copy(panels = listOf(current))

        val panels = SchedulerDomain.fillSchedule(s, now)

        assertTrue(panels.none { it.startEpochMillis < now }) // the stale (non-pinned) panel is gone
        val firstAuto = panels.first { it.auto }
        assertEquals(now, firstAuto.startEpochMillis) // the fresh fill starts at now
        assertEquals(a, firstAuto.taskId) // A and B tie → A re-picked first (continuity)
    }

    @Test
    fun fill_schedule_shows_a_sole_task_as_one_merged_block() {
        // PRD §9 merge: a single task's consecutive auto sessions fuse into one continuous panel that
        // covers the whole window from now past the horizon.
        val (s, solo) = stateWithOneTask(45)
        val now = 1_000_000_000_000L

        val autos = SchedulerDomain.fillSchedule(s, now).filter { it.auto }

        assertEquals(1, autos.size, "a sole task should be one merged block, got ${autos.size}")
        assertEquals(solo, autos[0].taskId)
        assertEquals(now, autos[0].startEpochMillis)
        assertTrue(autos[0].endEpochMillis >= now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
    }

    @Test
    fun fill_schedule_allocates_time_in_proportion_to_priority() {
        // PRD §9 EDF: over the window, A (p=0.75) and B (p=0.25) receive ~3:1 of the scheduled time —
        // each task's utilization m/T equals its priority share.
        val (s, a, b) = stateWithWeightedTasks()
        val now = 1_000_000_000_000L
        val byTask =
            SchedulerDomain.fillSchedule(s, now)
                .filter { it.auto }
                .groupBy { it.taskId }
                .mapValues { (_, ps) -> ps.sumOf { it.endEpochMillis - it.startEpochMillis } }
        val aTime = byTask[a] ?: 0L
        val bTime = byTask[b] ?: 0L
        val share = aTime.toDouble() / (aTime + bTime)
        assertTrue(share in 0.70..0.80, "A should get ~75% of the time, got $share")
    }

    @Test
    fun fill_schedule_lets_pre_now_excess_set_the_starting_phase_without_balancing() {
        // PRD §9 example 1: A (50%, 45min) and B (50%, 45min) with lots of A pinned before now. The
        // excess of A must NOT be balanced (B is not over-served to catch up — they still split the
        // window 50/50) but it sets the starting phase: B goes first → B, A, B, A.
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // "lots of A before now": four back-to-back pinned A panels ending exactly at now.
        val pastA = (1..4).map { k ->
            pinned("pin/$k", a, now - k * 45 * MIN, now - (k - 1) * 45 * MIN)
        }
        val s = s0.copy(panels = pastA)

        val autos = SchedulerDomain.fillSchedule(s, now).filter { it.auto }.sortedBy { it.startEpochMillis }

        assertTrue(autos.size >= 4, "expected an alternating fill, got ${autos.map { it.taskId }}")
        assertEquals(now, autos[0].startEpochMillis) // fresh fill starts at now
        assertEquals(listOf(b, a, b, a), autos.take(4).map { it.taskId }) // B first, then alternates
        // Excess is not balanced: across the whole window A and B still get equal time (~50/50).
        val byTask = autos.groupBy { it.taskId }
            .mapValues { (_, ps) -> ps.sumOf { it.endEpochMillis - it.startEpochMillis } }
        val aTime = byTask[a] ?: 0L
        val bTime = byTask[b] ?: 0L
        val share = bTime.toDouble() / (aTime + bTime)
        assertTrue(share in 0.45..0.55, "B should still get ~50% in-window (no catch-up), got $share")
    }

    // ----- §15 Screen breaks --------------------------------------------------------------------


    @Test
    fun screen_breaks_project_to_the_given_horizon_beyond_the_default() {
        // PRD §15: the calendar passes the focused week's end as the horizon so navigating past the default
        // 168h scheduling window still shows screen-break markers. The shared prefix is unchanged — extending
        // the horizon only appends later occurrences.
        val now = 1_000_000_000_000L
        val sides = listOf(ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20_000L))
        val defaultPanels = SchedulerDomain.screenBreakPanels(sides, now)
        val twoWeeksOut = now + 14L * 24 * HOUR_MS
        val extendedPanels = SchedulerDomain.screenBreakPanels(sides, now, twoWeeksOut)

        assertTrue(extendedPanels.size > defaultPanels.size)
        assertTrue(defaultPanels.last().startEpochMillis <= now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
        assertTrue(extendedPanels.last().startEpochMillis > now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
        assertTrue(extendedPanels.last().startEpochMillis <= twoWeeksOut)
        assertEquals(defaultPanels, extendedPanels.subList(0, defaultPanels.size))
    }



    @Test
    fun fast_break_override_retimes_only_the_5min_pose() {
        DebugFlags.breakDurationMillisOverride = 5_000L
        DebugFlags.breakIntervalMillisOverride = 5_000L
        try {
            val sides = SchedulerDomain.effectiveDefaultScreenBreaks()
            val pose5 = sides.first { it.restBreak && it.durationMillis == 5_000L }
            assertEquals(5_000L, pose5.intervalMillis)
            // This pair names the 5-min pose only, so the 15-min pose and the 20-20-20 look-away keep
            // production timings (they have their own knobs — see the per-break test below).
            assertTrue(sides.any { it.restBreak && it.durationMillis == 15L * 60_000 && it.intervalMillis == 2 * HOUR_MS })
            assertTrue(sides.any { !it.restBreak && it.durationMillis == 20L * 1_000 && it.intervalMillis == 20 * MIN })
        } finally {
            DebugFlags.breakDurationMillisOverride = null
            DebugFlags.breakIntervalMillisOverride = null
        }
    }

    @Test
    fun the_debug_override_retimes_each_of_the_three_screen_breaks_independently() {
        // account1-empty-open-fast-break.bat's six knobs: every break, matched by its stable key, with each
        // rule optional. Here the look-away and the 15-min pose are retimed and the 5-min pose is not.
        DebugFlags.screenBreakOverrides = mapOf(
            SchedulerDomain.LOOK_AWAY_KEY to ScreenBreakOverride(durationMillis = 3_000L, intervalMillis = 30_000L),
            SchedulerDomain.FIFTEEN_MIN_BREAK_KEY to ScreenBreakOverride(
                durationMillis = 9_000L, intervalMillis = 60_000L,
            ),
        )
        try {
            val breaks = SchedulerDomain.effectiveDefaultScreenBreaks().associateBy { it.key }
            val lookAway = breaks.getValue(SchedulerDomain.LOOK_AWAY_KEY)
            assertEquals(3_000L, lookAway.durationMillis)
            assertEquals(30_000L, lookAway.intervalMillis)

            val pose15 = breaks.getValue(SchedulerDomain.FIFTEEN_MIN_BREAK_KEY)
            assertEquals(9_000L, pose15.durationMillis)
            assertEquals(60_000L, pose15.intervalMillis)

            // A break with no entry at all is untouched.
            val pose5 = breaks.getValue(SchedulerDomain.FIVE_MIN_BREAK_KEY)
            assertEquals(5L * 60_000, pose5.durationMillis)
            assertEquals(60L * 60_000, pose5.intervalMillis)
        } finally {
            DebugFlags.screenBreakOverrides = emptyMap()
        }
    }

    @Test
    fun the_legacy_5min_break_properties_are_a_view_onto_the_per_break_override_map() {
        // The desktop's older `-Pomniapp.breakDurationMs` pair and Android's remembered debug flags both write
        // through these, so they must land on the 5-min pose's entry — and clearing the last one must drop the
        // entry, keeping "nothing overridden" exactly equal to an empty map (what a release always runs).
        DebugFlags.breakDurationMillisOverride = 5_000L
        try {
            assertEquals(
                ScreenBreakOverride(durationMillis = 5_000L),
                DebugFlags.screenBreakOverrides[SchedulerDomain.FIVE_MIN_BREAK_KEY],
            )
            assertTrue(DebugFlags.fastBreakOverrideActive)
        } finally {
            DebugFlags.breakDurationMillisOverride = null
        }
        assertEquals(emptyMap(), DebugFlags.screenBreakOverrides)
        assertFalse(DebugFlags.fastBreakOverrideActive)
        assertEquals(SchedulerDomain.DEFAULT_SCREEN_BREAKS, SchedulerDomain.effectiveDefaultScreenBreaks())
    }

    @Test
    fun the_displayed_plan_is_cut_out_of_a_break_that_slid_onto_it() {
        // The fill that materialized the plan ran when the break was elsewhere (CLAUDE.md: time passing never
        // re-plans). As the owed break slides right with the now-line it drifts over auto panels the fill
        // placed past it, so the display has to cut them — "a period that accepts no task" has to stay true
        // between fills, which is the sliding-period regime of `side-dev/scheduler_logic.py` tests 10–11.
        val now = 1_000_000_000_000L
        val auto = { id: String, start: Long, end: Long ->
            TaskPanel(id = id, taskId = TaskId("t"), title = "T", startEpochMillis = start, endEpochMillis = end, auto = true)
        }
        val breakPanel = TaskPanel(
            id = "side/0", taskId = null, title = "Eyes",
            startEpochMillis = now, endEpochMillis = now + 20_000L, screenBreak = true,
        )
        // A panel straddling the now-line, one wholly inside the break, one already past it, and a PINNED
        // block (a pre-placed block in the reference's sense, which a period may not move).
        val pinned = auto("pin", now, now + 20_000L).copy(auto = false, pinned = true)
        val panels = listOf(
            auto("a", now - 10 * MIN, now + 30 * MIN),
            auto("b", now + 5_000L, now + 15_000L),
            auto("c", now + 30 * MIN, now + 60 * MIN),
            pinned,
        )
        val out = SchedulerDomain.clipPlanForPinnedScreenBreak(panels, listOf(breakPanel), now)

        // Nothing regenerable overlaps the break any more…
        assertTrue(
            out.filter { it.auto && !it.pinned }
                .none { it.startEpochMillis < breakPanel.endEpochMillis && it.endEpochMillis > now },
        )
        // …the straddling panel keeps its elapsed head and resumes on the far side (two blocks, distinct ids)…
        val a = out.filter { it.id.startsWith("a") }.sortedBy { it.startEpochMillis }
        assertEquals(listOf(now - 10 * MIN to now, now + 20_000L to now + 30 * MIN), a.map { it.startEpochMillis to it.endEpochMillis })
        assertEquals(2, a.map { it.id }.distinct().size)
        // …the panel wholly inside the break is gone, the one past it untouched, the pinned block untouched.
        assertTrue(out.none { it.id == "b" })
        assertTrue(out.any { it.id == "c" && it.startEpochMillis == now + 30 * MIN })
        assertTrue(out.any { it.id == "pin" && it.startEpochMillis == now && it.endEpochMillis == now + 20_000L })

        // With no break on the now-line the plan is returned untouched.
        assertEquals(panels, SchedulerDomain.clipPlanForPinnedScreenBreak(panels, emptyList(), now))
        val future = listOf(breakPanel.copy(startEpochMillis = now + MIN, endEpochMillis = now + MIN + 20_000L))
        assertEquals(panels, SchedulerDomain.clipPlanForPinnedScreenBreak(panels, future, now))
    }




    @Test
    fun default_screen_breaks_are_the_three_from_the_prd() {
        val titles = SchedulerDomain.DEFAULT_SCREEN_BREAKS.map { it.title }
        assertEquals(
            listOf("look 20 feet away", "take a 5min pose and blink hard", "take a 15min pose"),
            titles,
        )
        // The 20s look-away, the 5-min blink pose, the 15-min pose.
        assertEquals(20 * 1_000L, SchedulerDomain.DEFAULT_SCREEN_BREAKS[0].durationMillis)
        assertEquals(20 * MIN, SchedulerDomain.DEFAULT_SCREEN_BREAKS[0].intervalMillis)
        assertEquals(2 * 60 * MIN, SchedulerDomain.DEFAULT_SCREEN_BREAKS[2].intervalMillis)
    }

    @Test
    fun the_running_app_seeds_the_default_screen_breaks() {
        // PRD §15: screen breaks are a hardcoded set, seeded into the live state (not into bare test states).
        val seeded = TaskSchedulerViewModel.loadInitialState(store = null, initial = SchedulerState.empty())
        assertEquals(SchedulerDomain.DEFAULT_SCREEN_BREAKS, seeded.screenBreaks)
    }

    @Test
    fun a_rest_stretch_bars_the_break_that_follows_it_for_that_break_s_own_interval() {
        // What "overdue" became. There is no anchor to ask (`isScreenBreakOverdue` is gone with it): the
        // README's bar is the rule — after a >=5-min rest stretch, no 5-min pose for an hour — so a stretch
        // on the timeline pushes the next occurrence to at least `stretch end + 1 h`.
        //
        // All three breaks are configured because the bars are POSITIONAL: the shortest of the account's
        // breaks plays the README's "20s" role, the longest its "15min". One break alone is the 20 s one.
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        val pose5 = breaks.first { it.durationMillis == 5L * 60_000 }
        val now = 1_000_000_000_000L

        assertTrue(SchedulerDomain.nextScreenBreakStartMillis(breaks, pose5.title, now) != null)
        val restEnd = now + 10 * MIN
        val afterRest = SchedulerDomain.nextScreenBreakStartMillis(
            breaks, pose5.title, now,
            basePeriods = listOf(
                org.example.project.scheduler.domain.RestrictivePeriod(
                    now, restEnd, org.example.project.scheduler.domain.PeriodKinds.NO_TASK, "away",
                ),
            ),
        )
        assertTrue(afterRest != null && afterRest >= restEnd + 60 * MIN, "got $afterRest")
    }




    @Test
    fun a_screen_break_suspends_a_task_panel_without_cutting_its_minimum_time() {
        // PRD §15, unchanged by the README's new placement: a dynamic period belongs to NOBODY, so a chunk
        // that meets one is suspended and resumes on the far side with its minimum intact — it is not cut
        // short the way a screen-zone edge cuts it. What changed is only WHERE the break falls.
        val now = 1_000_000_000_000L
        var s0 = SchedulerState.empty()
        val c0 = s0.lists[s0.rootListId]!!.cellIds[0]
        s0 = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s0.tasks.keys.first { s0.tasks[it]!!.title == "Solo" }
        s0 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskMinimumTime(solo, 45))
        s0 = s0.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val panels = SchedulerDomain.fillSchedule(s0, now, horizonMillis = now + 4 * HOUR_MS)
        val breaks = panels.filter { it.screenBreak }
        assertTrue(breaks.isNotEmpty(), "the case needs a break to be about")
        val mine = panels.filter { it.taskId == solo && it.auto }.sortedBy { it.startEpochMillis }
        assertTrue(mine.isNotEmpty())
        // No task panel ever overlaps a break…
        assertTrue(
            mine.none { p -> breaks.any { p.startEpochMillis < it.endEpochMillis && p.endEpochMillis > it.startEpochMillis } },
            "a task may not run inside a period of the kind \"no task allowed\"",
        )
        // …and the service the task actually gets, summed across the pieces a break split, still reaches its
        // whole minimum: the break cost it time, not slot.
        val served = mine.sumOf { it.endEpochMillis - it.startEpochMillis }
        assertTrue(served >= 45 * MIN, "the minimum survives the suspension: served ${served / MIN}min")
    }

    @Test
    fun a_pinned_panel_by_contrast_leaves_the_gap_before_it_empty() {
        // Control for the test above: a *pinned* obstacle 20 min ahead is NOT a screen break, so nothing
        // resumes across it. The 20 minutes before it are shorter than any task's minimum, so — per
        // `side-dev/scheduler_logic.py`'s `fitting` rule and PRD §10 ("a task panel can't be shorter than its minimum") —
        // they are left empty rather than filled with a 20-minute sliver of A. The plan starts on the far
        // side of the obstacle. (Earlier revisions truncated A to 20 min here; that violated §10.)
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val pin = pinned("panel/0", b, now + 20 * MIN, now + 25 * MIN)
        val s = s0.copy(panels = listOf(pin))

        val autos = SchedulerDomain.fillSchedule(s, now).filter { it.auto }.sortedBy { it.startEpochMillis }

        assertTrue(
            autos.none { it.startEpochMillis < now + 20 * MIN },
            "the sub-minimum gap before the pinned block must stay empty: $autos",
        )
        assertEquals(a, autos[0].taskId)
        assertEquals(now + 25 * MIN, autos[0].startEpochMillis) // the plan resumes after the obstacle
        assertTrue(
            autos[0].endEpochMillis - autos[0].startEpochMillis >= 45 * MIN,
            "and it gets at least its whole minimum: ${autos[0]}",
        )
    }

    // ----- §9 RefreshSchedule (calculation event) --------------------------------------------

    @Test
    fun refresh_schedule_fills_panels_without_creating_a_history_unit() {
        val (s, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))
        assertTrue(scheduled.panels.isNotEmpty())

        // PRD §9: a derived schedule is NOT a History Unit, so no calendar unit is recorded and undo
        // (while the calendar is focused) has nothing to walk — the filled panels stay put.
        assertEquals(0, scheduled.histories.calendar.units.size)
        val focused = SchedulerReducer.reduce(scheduled, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertEquals(scheduled.panels, undone.panels) // nothing to undo: the schedule is unchanged
    }

    @Test
    fun refresh_schedule_wipes_non_pinned_panels_but_keeps_pinned_ones() {
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val pin = pinned("panel/0", a, now + 10 * HOUR_MS, now + 11 * HOUR_MS)
        val stale = auto("auto/9", b, now + 2 * HOUR_MS, now + 3 * HOUR_MS) // not pinned, not current
        val s = s0.copy(panels = listOf(pin, stale))

        val refreshed = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))

        assertTrue(pin in refreshed.panels) // pinned kept
        assertTrue(stale !in refreshed.panels) // stale non-pinned wiped & regenerated
        assertTrue(refreshed.panels.any { it.auto && it.startEpochMillis == now }) // fresh fill from now
    }

    @Test
    fun adding_a_second_task_after_a_full_schedule_reschedules_to_include_it() {
        // Regression: a sole task fills the window as one merged block. Because a refill cuts every
        // in-window non-pinned panel (including that block) and re-derives the schedule from now, a
        // newly added task always enters the schedule — the merged block can never swallow the window.
        val (s0, solo) = stateWithOneTask(45)
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(now))
        assertTrue(scheduled.panels.all { it.taskId == solo }) // only the sole task so far

        // Add a second sibling task in the same sublist (the auto-appended placeholder cell).
        val placeholder = scheduled.lists[scheduled.rootListId]!!.cellIds[1]
        val withSecond = SchedulerReducer.reduce(scheduled, SchedulerIntent.SetCellTitle(placeholder, "Two"))
        val two = withSecond.tasks.keys.first { withSecond.tasks[it]!!.title == "Two" }

        val refreshed = SchedulerReducer.reduce(withSecond, SchedulerIntent.RefreshSchedule(now))

        assertTrue(refreshed.panels.any { it.taskId == two }) // the new task is now scheduled
        assertTrue(refreshed.panels.any { it.taskId == solo }) // and the original still appears
    }

    @Test
    fun deleting_a_task_by_emptying_its_cell_drops_it_from_the_schedule() {
        // Regression: "deleting" a task clears its cell title but the cell keeps its id and the task
        // lingers (its panels/records still reference it). A blank-titled task must NOT be scheduled —
        // otherwise the refill alternates the survivor with "(untitled)" blocks.
        val (s0, a, b) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(now))
        assertTrue(scheduled.panels.any { it.taskId == a } && scheduled.panels.any { it.taskId == b })

        // Delete task B by emptying the cell that titled it.
        val bCell = scheduled.tasks[b]!!.occurrences.first()
        val deleted = SchedulerReducer.reduce(scheduled, SchedulerIntent.SetCellTitle(bCell, ""))

        val refreshed = SchedulerReducer.reduce(deleted, SchedulerIntent.RefreshSchedule(now))

        assertTrue(refreshed.panels.any { it.taskId == a }) // the survivor fills the window
        assertTrue(refreshed.panels.none { it.taskId == b }) // no blank "(untitled)" B panels
        assertTrue(refreshed.panels.none { it.title.isBlank() }) // nothing renders as "(untitled)"
    }

    @Test
    fun refresh_schedule_keeps_a_non_pinned_panel_that_is_in_the_past() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A non-pinned user panel sitting just before now — e.g. dragged from just-after to just-before
        // the current time. Scheduling regenerates only the future, so it must NOT be wiped.
        val past = TaskPanel("panel/0", a, "A", now - 30 * MIN, now - MIN, pinned = false, auto = false)
        val s = s0.copy(panels = listOf(past), nextPanelCounter = 1)

        val refreshed = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))

        assertTrue(past in refreshed.panels) // PRD §9: past panels are history, not removed
        assertTrue(refreshed.panels.any { it.auto && it.startEpochMillis == now }) // future still filled
    }

    @Test
    fun refresh_schedule_keeps_a_non_pinned_panel_beyond_the_168h_horizon() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        // A non-pinned user panel added more than 168h out is outside the scheduling window, so a
        // reschedule must not wipe it.
        val far = TaskPanel("panel/0", a, "A", now + 200 * HOUR_MS, now + 201 * HOUR_MS, pinned = false, auto = false)
        val s = s0.copy(panels = listOf(far), nextPanelCounter = 1)

        val refreshed = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(now))

        assertTrue(far in refreshed.panels) // PRD §9: panels beyond the horizon are outside scope
    }

    @Test
    fun refresh_schedule_is_deferred_while_automatic_schedule_is_off() {
        val (s0, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val off = s0.copy(automaticSchedule = false)
        val after = SchedulerReducer.reduce(off, SchedulerIntent.RefreshSchedule(now))
        assertTrue(after.panels.isEmpty()) // PRD §7: the scheduling event waits

        // Turning it on then refreshing fills the schedule.
        val on = SchedulerReducer.reduce(after, SchedulerIntent.SetAutomaticSchedule(true))
        val filled = SchedulerReducer.reduce(on, SchedulerIntent.RefreshSchedule(now))
        assertTrue(filled.panels.isNotEmpty())
    }

    @Test
    fun set_show_screen_breaks_toggles_the_display_flag_without_touching_panels() {
        // PRD §15: the calendar's screen-break display switch is a cosmetic flag — it changes nothing else.
        val (s0, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(now))
        assertFalse(scheduled.showScreenBreaks) // default off

        val shown = SchedulerReducer.reduce(scheduled, SchedulerIntent.SetShowScreenBreaks(true))
        assertTrue(shown.showScreenBreaks)
        assertEquals(scheduled.panels, shown.panels) // the schedule (and screen breaks) are untouched

        // Idempotent (same instance back) + reversible.
        assertTrue(SchedulerReducer.reduce(shown, SchedulerIntent.SetShowScreenBreaks(true)) === shown)
        assertFalse(SchedulerReducer.reduce(shown, SchedulerIntent.SetShowScreenBreaks(false)).showScreenBreaks)
    }

    @Test
    fun set_show_reminders_toggles_the_display_flag_without_touching_panels() {
        // PRD §14: the calendar's reminder display switch is a cosmetic flag — it changes nothing else.
        val (s0, _, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val scheduled = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(now))
        assertTrue(scheduled.showReminders) // default on

        val hidden = SchedulerReducer.reduce(scheduled, SchedulerIntent.SetShowReminders(false))
        assertFalse(hidden.showReminders)
        assertEquals(scheduled.panels, hidden.panels) // the schedule (and reminders) are untouched

        // Idempotent (same instance back) + reversible.
        assertTrue(SchedulerReducer.reduce(hidden, SchedulerIntent.SetShowReminders(false)) === hidden)
        assertTrue(SchedulerReducer.reduce(hidden, SchedulerIntent.SetShowReminders(true)).showReminders)
    }

    // ----- §9 AdvanceSchedule (frequent tick) ------------------------------------------------

    @Test
    fun advance_records_a_completed_auto_panel_and_drops_it() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val completed = auto("auto/0", a, now - 30 * MIN, now - MIN)
        val s = s0.copy(panels = listOf(completed))

        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(now))

        assertTrue(advanced.panels.isEmpty()) // the elapsed panel is gone…
        // …and its span was logged as a record so the calendar keeps showing it (green).
        assertEquals(listOf(TaskTimeRange(now - 30 * MIN, now - MIN)), advanced.tasks[a]!!.record)
    }

    @Test
    fun advance_keeps_an_in_progress_panel_and_is_not_undoable() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val current = auto("auto/0", a, now - 5 * MIN, now + 25 * MIN)
        val s = s0.copy(panels = listOf(current))
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(now))
        assertTrue(advanced.tasks[a]!!.record.isEmpty()) // not yet completed → nothing recorded
        assertTrue(current in advanced.panels)
        // Touches only history-excluded record/panel-progress state → undo must not bring it back.
        val undone = SchedulerReducer.reduce(advanced, SchedulerIntent.Undo)
        assertEquals(advanced.panels, undone.panels)
    }

    @Test
    fun advance_cuts_and_records_an_in_progress_panel_whose_task_left_the_tree() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val current = auto("auto/0", a, now - 10 * MIN, now + 20 * MIN)
        // `a` is removed from the tree (no cell points at it); the panel must be cut at `now`.
        val s = s0.copy(cells = s0.cells.filterValues { it.taskId != a }, panels = listOf(current))
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(now))
        assertTrue(advanced.panels.isEmpty())
        assertEquals(listOf(TaskTimeRange(now - 10 * MIN, now)), advanced.tasks[a]!!.record)
    }

    // ----- §10 minimum time ------------------------------------------------------------------

    @Test
    fun scheduled_span_subtracts_recent_contiguous_effort() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val recent = TaskTimeRange(now - 25 * MIN, now - 5 * MIN)
        val task = s0.tasks[a]!!.copy(minimumMinutes = 60, record = listOf(recent))
        assertEquals(20L, SchedulerDomain.recentContiguousRecordMinutes(task.record, now))
        assertEquals(40L, SchedulerDomain.scheduledSpanMinutes(task, now))
    }

    @Test
    fun scheduled_span_ignores_effort_whose_streak_already_broke() {
        val (s0, a, _) = stateWithTwoTasks()
        val now = 1_000_000_000_000L
        val stale = TaskTimeRange(now - 35 * MIN, now - 15 * MIN)
        val task = s0.tasks[a]!!.copy(minimumMinutes = 60, record = listOf(stale))
        assertEquals(0L, SchedulerDomain.recentContiguousRecordMinutes(task.record, now))
        assertEquals(60L, SchedulerDomain.scheduledSpanMinutes(task, now))
    }

    @Test
    fun new_task_defaults_to_45_minute_minimum() {
        val (s, a, _) = stateWithTwoTasks()
        assertEquals(45, s.tasks[a]!!.minimumMinutes)
        val now = 1_000_000_000_000L
        val panels = SchedulerDomain.fillSchedule(s, now)
        assertEquals(now + 45 * MIN, panels.first().endEpochMillis)
    }

    @Test
    fun set_minimum_time_updates_and_clamps_negative_to_zero() {
        val (s, a, _) = stateWithTwoTasks()
        val set = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 30))
        assertEquals(30, set.tasks[a]!!.minimumMinutes)
        val clamped = SchedulerReducer.reduce(set, SchedulerIntent.SetTaskMinimumTime(a, -5))
        assertEquals(0, clamped.tasks[a]!!.minimumMinutes)
    }

    @Test
    fun undo_restores_previous_minimum_time() {
        val (s, a, _) = stateWithTwoTasks()
        val s30 = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 30))
        val s45 = SchedulerReducer.reduce(s30, SchedulerIntent.SetTaskMinimumTime(a, 45))
        val undone = SchedulerReducer.reduce(s45, SchedulerIntent.Undo)
        assertEquals(30, undone.tasks[a]!!.minimumMinutes)
    }

    // ----- §8 task record is excluded from history -------------------------------------------

    @Test
    fun undo_does_not_revert_a_task_record() {
        val (s, a, _) = stateWithTwoTasks()
        val record = listOf(TaskTimeRange(1_000L, 2_000L))
        val withRecord = s.copy(tasks = s.tasks + (a to s.tasks[a]!!.copy(record = record)))
        val mutated = SchedulerReducer.reduce(withRecord, SchedulerIntent.SetTaskMinimumTime(a, 15))
        val undone = SchedulerReducer.reduce(mutated, SchedulerIntent.Undo)
        assertEquals(45, undone.tasks[a]!!.minimumMinutes)
        assertEquals(record, undone.tasks[a]!!.record)
    }

    // ----- persistence -----------------------------------------------------------------------

    @Test
    fun codec_round_trip_preserves_panels_and_switch() {
        val (s, a, _) = stateWithTwoTasks()
        val prepared =
            s.copy(
                panels = listOf(pinned("panel/0", a, 10_000L, 20_000L), auto("auto/0", a, 20_000L, 30_000L)),
                nextPanelCounter = 1,
                automaticSchedule = false,
            )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(prepared))
        assertNotNull(decoded)
        assertEquals(prepared.panels, decoded.panels)
        assertEquals(1, decoded.nextPanelCounter)
        assertEquals(false, decoded.automaticSchedule)
    }

    @Test
    fun codec_round_trip_preserves_minimum_time_and_record() {
        val (s, a, _) = stateWithTwoTasks()
        val record = listOf(TaskTimeRange(10_000L, 20_000L), TaskTimeRange(30_000L, 40_000L))
        val prepared =
            SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 25))
                .let { it.copy(tasks = it.tasks + (a to it.tasks[a]!!.copy(record = record))) }
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(prepared))
        assertNotNull(decoded)
        assertEquals(25, decoded.tasks[a]!!.minimumMinutes)
        assertEquals(record, decoded.tasks[a]!!.record)
    }

    @Test
    fun codec_decodes_old_payload_without_new_fields() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(45, decoded.tasks[TaskId("t0")]!!.minimumMinutes)
        assertTrue(decoded.panels.isEmpty())
        assertEquals(true, decoded.automaticSchedule) // §7 default-on for pre-1.2.0 payloads
    }

    // ----- §8 calendar mapping ----------------------------------------------------------------

    @Test
    fun records_for_day_clips_to_the_day_and_maps_hour_offsets() {
        val tz = TimeZone.UTC
        val day = LocalDate(2024, 1, 1)
        fun millis(h: Int, m: Int) =
            LocalDateTime(2024, 1, 1, h, m).toInstant(tz).toEpochMilliseconds()
        val record = CalendarRecord("Practice English", TaskTimeRange(millis(9, 0), millis(10, 30)))

        val placed = recordsForDay(listOf(record), day, tz)
        assertEquals(1, placed.size)
        assertEquals(9.0f, placed[0].startHour)
        assertEquals(10.5f, placed[0].endHour)
        assertEquals("Practice English", placed[0].title)
        assertTrue(recordsForDay(listOf(record), LocalDate(2024, 1, 2), tz).isEmpty())
    }

    @Test
    fun records_for_day_keeps_zero_duration_reminder_tags_but_drops_zero_duration_blocks() {
        // PRD §14: a reminder is a zero-duration tag rendered at its time — it must survive recordsForDay
        // (the block path drops zero-height periods). A non-reminder zero-duration period is still dropped.
        val tz = TimeZone.UTC
        val day = LocalDate(2024, 1, 1)
        val at20 = LocalDateTime(2024, 1, 1, 20, 0).toInstant(tz).toEpochMilliseconds()
        val reminder = CalendarRecord(
            "Water plants", TaskTimeRange(at20, at20), entryId = "chore/0/0", reminder = true, checked = true,
        )
        val degenerateBlock = CalendarRecord("Oops", TaskTimeRange(at20, at20))

        val placed = recordsForDay(listOf(reminder, degenerateBlock), day, tz)
        assertEquals(1, placed.size) // only the reminder tag survives
        assertTrue(placed[0].reminder)
        assertTrue(placed[0].checked)
        assertEquals(20.0f, placed[0].startHour) // rendered at 20:00
        assertEquals("chore/0/0", placed[0].entryId)
    }

    @Test
    fun records_for_day_keeps_a_sub_minute_screen_break_as_a_marker() {
        // PRD §15 regression: a 20-second screen break drawn to scale is ~invisible. recordsForDay must keep it
        // (flagged screenBreak) so the day column can render it as a fixed-height marker at its time.
        val tz = TimeZone.UTC
        val day = LocalDate(2024, 1, 1)
        val start = LocalDateTime(2024, 1, 1, 20, 0).toInstant(tz).toEpochMilliseconds()
        val side = CalendarRecord(
            "look 20 feet away", TaskTimeRange(start, start + 20_000L), entryId = "side/0/$start", screenBreak = true,
        )

        val placed = recordsForDay(listOf(side), day, tz)
        assertEquals(1, placed.size)
        assertTrue(placed[0].screenBreak)
        assertEquals(20.0f, placed[0].startHour)
        assertEquals("look 20 feet away", placed[0].title)
    }

    @Test
    fun fill_horizon_follows_the_requested_week_and_scales_the_panel_cap() {
        // PRD §9/§17 "schedule the whole week displayed": a display fill for a focused future week passes that
        // week's end as [horizonMillis], so the plan is materialized from `now` out to it — well past the
        // default one-week horizon — without the fixed panel cap clipping the far weeks.
        val (s, _) = stateWithOneTask(minMinutes = 30)
        val now = 0L
        val week = 168L * HOUR_MS
        val autoEnd = { st: SchedulerState, horizon: Long? ->
            val panels =
                if (horizon == null) SchedulerDomain.fillSchedule(st, now, timeZone = TimeZone.UTC)
                else SchedulerDomain.fillSchedule(st, now, timeZone = TimeZone.UTC, horizonMillis = horizon)
            panels.filter { it.auto }.maxOf { it.endEpochMillis }
        }
        // Default horizon still stops around one week (unchanged near behavior).
        assertTrue(autoEnd(s, null) <= now + week + HOUR_MS)
        // A four-week horizon fills into the fourth week — the far weeks are not clipped at the old cap.
        assertTrue(
            autoEnd(s, now + 4 * week) > now + 3 * week,
            "far-week fill should reach past 3 weeks",
        )
    }
}
