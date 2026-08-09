package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.ScreenBreakPeriod
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §8/§9/§12 no-screen + inactivity calendar periods and the screen-switch scheduling rules:
 *  - the manual "add a no-screen period" / "add an inactivity period" panels (undoable, persisted,
 *    old payloads decode with the flags defaulted — persisted-DB compatibility rule);
 *  - the automatic override/trim between an added screen task and a no-screen period (both ways);
 *  - [SchedulerDomain.fillSchedule] placement: on-screen tasks only outside no-screen periods,
 *    off-screen tasks only inside them, and the §15 screen breaks as periods — closed for their first
 *    minute (a 20-second look-away end to end), then accepting the off-screen break-doable tasks whose
 *    minimum still fits;
 *  - past no-screen ⇒ past inactivity: the schedule-advance banks NO record over a no-screen period.
 */
class NoScreenInactivityPanelTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L // fixed reference instant

    /** A single task "Solo" with the given minimum time (minutes). */
    private fun stateWithOneTask(minMinutes: Int = 45): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, minMinutes))
        return s to solo
    }

    private fun noScreenPanel(s: SchedulerState): TaskPanel? = s.panels.firstOrNull { it.noScreen }

    // ----- the manual panels ------------------------------------------------------------------

    @Test
    fun add_no_screen_period_creates_an_undoable_titled_panel() {
        val s0 = SchedulerState.empty()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddNoScreenPeriod(NOW, NOW + HOUR))
        val panel = noScreenPanel(s)
        assertNotNull(panel)
        assertEquals("No screen", panel.title)
        assertEquals(NOW, panel.startEpochMillis)
        assertEquals(NOW + HOUR, panel.endEpochMillis)
        assertFalse(panel.auto)
        // Undo is focus-routed (four-category history): panel deltas sit in the Calendar category.
        val focused = SchedulerReducer.reduce(s, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertTrue(undone.panels.none { it.noScreen })
    }

    @Test
    fun add_inactivity_period_creates_an_undoable_titled_panel() {
        val s0 = SchedulerState.empty()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddInactivityPeriod(NOW, NOW + HOUR))
        val panel = s.panels.firstOrNull { it.inactivity }
        assertNotNull(panel)
        assertEquals("Inactivity", panel.title)
        val focused = SchedulerReducer.reduce(s, SchedulerIntent.SetCalendarFocus(true))
        val undone = SchedulerReducer.reduce(focused, SchedulerIntent.Undo)
        assertTrue(undone.panels.none { it.inactivity })
    }

    @Test
    fun codec_round_trips_the_no_screen_and_inactivity_flags() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW, NOW + HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddInactivityPeriod(NOW + 2 * HOUR, NOW + 3 * HOUR))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertTrue(decoded.panels.any { it.noScreen && it.title == "No screen" })
        assertTrue(decoded.panels.any { it.inactivity && it.title == "Inactivity" })
    }

    @Test
    fun codec_decodes_old_panel_payload_without_the_flags() {
        // Persisted-DB rule: a payload written before the fields existed must decode with both false.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "panels":[{"id":"panel/0","taskId":"t0","title":"X","start":0,"end":3600000}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val panel = decoded.panels.single()
        assertFalse(panel.noScreen)
        assertFalse(panel.inactivity)
    }

    @Test
    fun codec_decodes_old_screen_break_key_names_as_screen_breaks() {
        // Persisted-DB rule: the screen-break rename also renamed the persisted JSON keys (the legacy
        // `sideTask` panel flag → `screenBreak`, `showSideTasks` → `showScreenBreaks`). A DB written by the
        // OLD build (legacy key names) must still load — @JsonNames maps the legacy names onto the new fields.
        val oldJson =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "showSideTasks":true,
             "panels":[{"id":"side/0/0","taskId":null,"title":"look 20 feet away","start":0,"end":20000,"sideTask":true}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(oldJson)
        assertNotNull(decoded)
        assertTrue(decoded.showScreenBreaks, "old showSideTasks key should decode into showScreenBreaks")
        assertTrue(
            decoded.panels.single().screenBreak,
            "old sideTask panel flag should decode into screenBreak",
        )
    }

    // ----- override/trim between screen tasks and no-screen periods ---------------------------

    @Test
    fun adding_a_no_screen_period_trims_the_on_screen_panel_it_overlaps() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddTaskPanel(solo, "Solo", NOW, NOW + 2 * HOUR, PanelPins(existence = true)),
        )
        // Covers the second half of the task panel → the panel is trimmed to end at the period start.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW + HOUR, NOW + 3 * HOUR))
        val taskPanel = s.panels.single { it.taskId == solo }
        assertEquals(NOW, taskPanel.startEpochMillis)
        assertEquals(NOW + HOUR, taskPanel.endEpochMillis)
        assertNotNull(noScreenPanel(s))
    }

    @Test
    fun adding_a_no_screen_period_splits_a_panel_it_lands_inside() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddTaskPanel(solo, "Solo", NOW, NOW + 4 * HOUR, PanelPins(existence = true)),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW + HOUR, NOW + 2 * HOUR))
        val pieces = s.panels.filter { it.taskId == solo }.sortedBy { it.startEpochMillis }
        assertEquals(2, pieces.size)
        assertEquals(NOW to NOW + HOUR, pieces[0].startEpochMillis to pieces[0].endEpochMillis)
        assertEquals(NOW + 2 * HOUR to NOW + 4 * HOUR, pieces[1].startEpochMillis to pieces[1].endEpochMillis)
    }

    @Test
    fun adding_an_on_screen_panel_over_a_no_screen_period_trims_the_period() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddNoScreenPeriod(NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(solo, "Solo", NOW + HOUR, NOW + 3 * HOUR, PanelPins(existence = true)),
        )
        val period = noScreenPanel(s)
        assertNotNull(period)
        assertEquals(NOW, period.startEpochMillis)
        assertEquals(NOW + HOUR, period.endEpochMillis)
    }

    @Test
    fun adding_an_off_screen_panel_over_a_no_screen_period_leaves_it_whole() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = false),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(solo, "Solo", NOW, NOW + HOUR, PanelPins(existence = true)),
        )
        val period = noScreenPanel(s)
        assertNotNull(period)
        assertEquals(NOW, period.startEpochMillis)
        assertEquals(NOW + 2 * HOUR, period.endEpochMillis)
    }

    @Test
    fun a_fully_covered_no_screen_period_is_deleted() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddNoScreenPeriod(NOW + HOUR, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(solo, "Solo", NOW, NOW + 3 * HOUR, PanelPins(existence = true)),
        )
        assertTrue(s.panels.none { it.noScreen })
    }

    // ----- §9 fill placement under the screen switches ----------------------------------------

    @Test
    fun fill_keeps_an_on_screen_task_out_of_a_no_screen_period() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddNoScreenPeriod(NOW + HOUR, NOW + 2 * HOUR))
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        val taskPanels = panels.filter { it.taskId == solo }
        assertTrue(taskPanels.isNotEmpty())
        assertTrue(
            taskPanels.none { it.startEpochMillis < NOW + 2 * HOUR && it.endEpochMillis > NOW + HOUR },
            "on-screen chunks must never overlap the no-screen period",
        )
        // The window before and after the period still fills (the fill flows around it).
        assertTrue(taskPanels.any { it.startEpochMillis == NOW })
        assertTrue(taskPanels.any { it.startEpochMillis == NOW + 2 * HOUR })
    }

    @Test
    fun fill_places_an_off_screen_task_only_inside_no_screen_periods() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = false),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW + HOUR, NOW + 2 * HOUR))
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        val taskPanels = panels.filter { it.taskId == solo && it.auto }
        assertTrue(taskPanels.isNotEmpty(), "the off-screen task must fill the no-screen period")
        assertTrue(
            taskPanels.all { it.startEpochMillis >= NOW + HOUR && it.endEpochMillis <= NOW + 2 * HOUR },
            "off-screen chunks must all lie inside the no-screen period",
        )
    }

    @Test
    fun fill_schedules_nothing_for_an_off_screen_task_without_a_no_screen_period() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = false),
        )
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        assertTrue(panels.none { it.taskId == solo && it.auto })
    }

    /** A state whose only screen break is a 15-min rest pose due at `NOW + 1h`. */
    private fun withRestPose(s: SchedulerState): SchedulerState =
        s.copy(
            screenBreaks = listOf(
                ScreenBreak(
                    title = "Rest pose",
                    intervalMillis = HOUR,
                    durationMillis = 15 * MIN,
                    restBreak = true,
                    lastRestMillis = NOW,
                ),
            ),
        )

    @Test
    fun break_doable_task_fills_a_rest_break_only_after_its_closed_first_minute() {
        // PRD §15: the pose's first minute accepts nobody; what is left of it accepts the tasks that need
        // no screen and are marked doable during a break. A 5-min minimum fits the remaining 14 min.
        val (s0, solo) = stateWithOneTask(minMinutes = 5)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = true),
        )
        s = withRestPose(s)
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        val breakRange = panels.first { it.screenBreak }
        val opens = breakRange.startEpochMillis + SchedulerDomain.SCREEN_BREAK_CLOSED_HEAD_MILLIS
        val inBreak = panels.filter {
            it.taskId == solo && it.auto &&
                it.startEpochMillis < breakRange.endEpochMillis &&
                it.endEpochMillis > breakRange.startEpochMillis
        }
        assertTrue(inBreak.isNotEmpty(), "the break-doable off-screen task must fill the rest pose's tail")
        assertTrue(
            inBreak.all { it.startEpochMillis >= opens && it.endEpochMillis <= breakRange.endEpochMillis },
            "nothing may be scheduled in the pose's closed first minute, nor past its end",
        )
    }

    @Test
    fun break_doable_task_with_a_too_long_minimum_never_fills_the_break() {
        // The pose leaves 14 min once its closed first minute is taken off, but the task needs 30.
        val (s0, solo) = stateWithOneTask(minMinutes = 30)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = true),
        )
        s = withRestPose(s)
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        val breakRange = panels.first { it.screenBreak }
        assertTrue(
            panels.none {
                it.taskId == solo && it.auto &&
                    it.startEpochMillis < breakRange.endEpochMillis &&
                    it.endEpochMillis > breakRange.startEpochMillis
            },
            "a 30-min minimum must never overlap the 15-min break interior",
        )
    }

    @Test
    fun a_break_doable_task_still_needs_a_no_screen_period_outside_the_break() {
        // The break is the ONLY extra place the flag opens up: with no no-screen period, the off-screen
        // break-doable task is scheduled inside the pose and nowhere else.
        val (s0, solo) = stateWithOneTask(minMinutes = 5)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = true),
        )
        s = withRestPose(s)
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        val breaks = panels.filter { it.screenBreak }
        val mine = panels.filter { it.taskId == solo && it.auto }
        assertTrue(mine.isNotEmpty())
        // The pose recurs across the horizon, so the task fills every occurrence — but nothing else.
        assertTrue(
            mine.all { p ->
                breaks.any { p.startEpochMillis >= it.startEpochMillis && p.endEpochMillis <= it.endEpochMillis }
            },
            "an off-screen task may only run inside a no-screen period or a screen break",
        )
    }

    @Test
    fun the_20s_look_away_accepts_no_task_at_all() {
        // PRD §15: a look-away is not a rest pose, so it is closed end to end — even a zero-minimum
        // break-doable task may not be placed in it (and it therefore distorts no plan around itself).
        val (s0, solo) = stateWithOneTask(minMinutes = 0)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = true),
        )
        s = s.copy(
            screenBreaks = listOf(
                ScreenBreak(
                    title = "look 20 feet away",
                    intervalMillis = 20 * MIN,
                    durationMillis = 20_000L,
                    restBreak = false,
                    lastRestMillis = NOW,
                ),
            ),
        )
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 2 * HOUR)
        val lookAways = panels.filter { it.screenBreak }
        assertTrue(lookAways.isNotEmpty(), "the look-aways must still be materialized")
        assertTrue(
            panels.none { p ->
                p.taskId == solo && p.auto &&
                    lookAways.any { p.startEpochMillis < it.endEpochMillis && p.endEpochMillis > it.startEpochMillis }
            },
            "no task may ever overlap a 20-second look-away",
        )
    }

    /** The production 15-minute pose: one open period accepting every off-screen task (PRD §15). */
    private fun with15MinPose(s: SchedulerState): SchedulerState =
        s.copy(
            screenBreaks = listOf(
                ScreenBreak(
                    title = "take a 15min pose",
                    intervalMillis = HOUR,
                    durationMillis = 15 * MIN,
                    restBreak = true,
                    lastRestMillis = NOW,
                    shape = ScreenBreakPeriod.OffScreenOnly,
                ),
            ),
        )

    @Test
    fun the_15min_pose_accepts_every_off_screen_task_from_its_very_first_second() {
        // PRD §15: the 15-minute pose is NOT a longer copy of the 5-minute one. It is a plain 15-minute
        // period accepting the tasks that need no screen — no closed first minute, and no *doable during a
        // break* gate, so this task (off-screen, break-doable OFF) fills it, starting at its very first second.
        val (s0, solo) = stateWithOneTask(minMinutes = 5)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = false),
        )
        s = with15MinPose(s)
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 2 * HOUR)
        val pose = panels.first { it.screenBreak }
        val inPose = panels.filter {
            it.taskId == solo && it.auto &&
                it.startEpochMillis < pose.endEpochMillis && it.endEpochMillis > pose.startEpochMillis
        }
        assertTrue(inPose.isNotEmpty(), "an off-screen task must fill the 15-min pose")
        assertEquals(
            pose.startEpochMillis,
            inPose.minOf { it.startEpochMillis },
            "the 15-min pose has no closed first minute",
        )
        assertTrue(
            inPose.all { it.endEpochMillis <= pose.endEpochMillis },
            "nothing may run past the pose's end: outside it this task has no screen zone to run in",
        )
    }

    @Test
    fun the_5min_pose_still_refuses_an_off_screen_task_that_is_not_break_doable() {
        // The counterpart of the test above, and what keeps the two shapes distinct: the 5-minute pose is
        // `side-dev` test 11's "1min: nothing" + "4min: only A" stretch, and "only A" is the break-doable set.
        val (s0, solo) = stateWithOneTask(minMinutes = 1)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = false),
        )
        s = s.copy(
            screenBreaks = listOf(
                ScreenBreak(
                    title = "take a 5min pose and blink hard",
                    intervalMillis = HOUR,
                    durationMillis = 5 * MIN,
                    restBreak = true,
                    lastRestMillis = NOW,
                    shape = ScreenBreakPeriod.ClosedMinuteThenBreakDoable,
                ),
            ),
        )
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 2 * HOUR)
        assertTrue(panels.none { it.taskId == solo && it.auto }, "only a break-doable task may fill a 5-min pose")
    }

    @Test
    fun an_on_screen_task_is_never_placed_in_a_screen_break() {
        // The invariant makes "on screen + doable during a break" unrepresentable; the fill must also
        // refuse it outright, so a payload that predates the invariant cannot slip through.
        val (s0, solo) = stateWithOneTask(minMinutes = 5)
        val s = withRestPose(
            s0.copy(tasks = s0.tasks + (solo to s0.tasks[solo]!!.copy(onScreen = true, doableDuringBreak = true))),
        )
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        val breakRange = panels.first { it.screenBreak }
        assertTrue(
            panels.none {
                it.taskId == solo && it.auto &&
                    it.startEpochMillis < breakRange.endEpochMillis &&
                    it.endEpochMillis > breakRange.startEpochMillis
            },
            "an on-screen task may never be placed inside a screen break",
        )
    }

    // ----- past no-screen ⇒ past inactivity (no assumed work) ---------------------------------

    @Test
    fun advance_banks_no_record_over_a_no_screen_period() {
        val (s0, solo) = stateWithOneTask()
        // A no-screen period covering the middle hour of an elapsed 3-hour auto panel.
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddNoScreenPeriod(NOW - 2 * HOUR, NOW - HOUR),
        )
        s = s.copy(
            panels = s.panels + TaskPanel(
                id = "auto/0",
                taskId = solo,
                title = "Solo",
                startEpochMillis = NOW - 3 * HOUR,
                endEpochMillis = NOW,
                auto = true,
            ),
        )
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        val record = advanced.tasks[solo]!!.record.sortedBy { it.startEpochMillis }
        assertEquals(2, record.size, "the no-screen hour must be a hole, not completed work: $record")
        assertEquals(NOW - 3 * HOUR to NOW - 2 * HOUR, record[0].startEpochMillis to record[0].endEpochMillis)
        assertEquals(NOW - HOUR to NOW, record[1].startEpochMillis to record[1].endEpochMillis)
    }

    @Test
    fun advance_materializes_a_past_inactivity_panel_over_the_covered_span() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddNoScreenPeriod(NOW - 2 * HOUR, NOW - HOUR),
        )
        s = s.copy(
            panels = s.panels + TaskPanel(
                id = "auto/0",
                taskId = solo,
                title = "Solo",
                startEpochMillis = NOW - 3 * HOUR,
                endEpochMillis = NOW,
                auto = true,
            ),
        )
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        // PRD §9 "past no-screen ⇒ past inactivity": the covered hour becomes a REAL inactivity panel
        // (the no-screen panel is only decorative, §8 taxonomy) marking that nothing happened there.
        val inactivity = advanced.panels.single { it.inactivity }
        assertEquals("Inactivity", inactivity.title)
        assertEquals(NOW - 2 * HOUR, inactivity.startEpochMillis)
        assertEquals(NOW - HOUR, inactivity.endEpochMillis)
        // Re-advancing must not duplicate it (the elapsed panel was dropped, nothing re-banks).
        val again = SchedulerReducer.reduce(advanced, SchedulerIntent.AdvanceSchedule(NOW))
        assertEquals(1, again.panels.count { it.inactivity })
    }

    @Test
    fun advance_materializes_no_inactivity_for_an_off_screen_task() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = false),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW - 2 * HOUR, NOW - HOUR))
        s = s.copy(
            panels = s.panels + TaskPanel(
                id = "auto/0",
                taskId = solo,
                title = "Solo",
                startEpochMillis = NOW - 2 * HOUR,
                endEpochMillis = NOW - HOUR,
                auto = true,
            ),
        )
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        // The off-screen task legitimately worked inside the no-screen period — no inactivity.
        assertTrue(advanced.panels.none { it.inactivity })
    }

    @Test
    fun advance_skips_spans_an_existing_inactivity_panel_already_covers() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddNoScreenPeriod(NOW - 2 * HOUR, NOW - HOUR),
        )
        // The user already marked that hour inactive by hand — the advance must not double it.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddInactivityPeriod(NOW - 2 * HOUR, NOW - HOUR))
        s = s.copy(
            panels = s.panels + TaskPanel(
                id = "auto/0",
                taskId = solo,
                title = "Solo",
                startEpochMillis = NOW - 3 * HOUR,
                endEpochMillis = NOW,
                auto = true,
            ),
        )
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        assertEquals(1, advanced.panels.count { it.inactivity })
    }

    @Test
    fun device_sleep_cut_materializes_the_covered_span_as_inactivity() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddNoScreenPeriod(NOW - 2 * HOUR, NOW - HOUR),
        )
        s = s.copy(
            panels = s.panels + TaskPanel(
                id = "auto/0",
                taskId = solo,
                title = "Solo",
                startEpochMillis = NOW - 3 * HOUR,
                endEpochMillis = NOW + HOUR,
                auto = true,
            ),
        )
        val slept = SchedulerReducer.reduce(s, SchedulerIntent.ReportDeviceSleep(NOW, NOW + HOUR))
        val inactivity = slept.panels.single { it.inactivity }
        assertEquals(NOW - 2 * HOUR, inactivity.startEpochMillis)
        assertEquals(NOW - HOUR, inactivity.endEpochMillis)
    }

    @Test
    fun advance_banks_the_whole_record_for_an_off_screen_task_inside_the_period() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskScreenFlags(solo, onScreen = false, doableDuringBreak = false),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW - 2 * HOUR, NOW - HOUR))
        s = s.copy(
            panels = s.panels + TaskPanel(
                id = "auto/0",
                taskId = solo,
                title = "Solo",
                startEpochMillis = NOW - 2 * HOUR,
                endEpochMillis = NOW - HOUR,
                auto = true,
            ),
        )
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        val record = advanced.tasks[solo]!!.record
        assertEquals(1, record.size)
        assertEquals(NOW - 2 * HOUR, record[0].startEpochMillis)
        assertEquals(NOW - HOUR, record[0].endEpochMillis)
    }
}
