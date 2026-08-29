package org.example.project

import org.example.project.scheduler.domain.PeriodKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.ScreenBreak
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
 *  - past no-screen ⇒ past inactivity: the schedule-advance banks NO record over a no-screen period — and
 *    writes NO panel for it either. What the strip vacates is idle time the calendar derives a grey band
 *    for; a grey period is an object only the user or the rules create.
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
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
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
    fun a_task_resilient_to_no_screen_is_unaffected_by_a_no_screen_period() {
        // `side-dev/README.md` § *Restrictive Period*: a resilience is a MULTIPLIER on the task's priority
        // inside a period of that kind, and 1 means "unaffected". So a task fully resilient to "no on-screen
        // task" runs inside such a period and outside it alike.
        //
        // This is a deliberate change of behaviour. The app used to CONFINE an off-screen task to no-screen
        // periods — a two-way classification the README's model cannot express, since a period can only ever
        // multiply what it covers and says nothing about the timeline it does not. "I can do this without a
        // screen" is not "I must only do this away from a screen".
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddNoScreenPeriod(NOW + HOUR, NOW + 2 * HOUR))
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        val taskPanels = panels.filter { it.taskId == solo && it.auto }
        assertTrue(taskPanels.isNotEmpty(), "the resilient task must be scheduled")
        assertTrue(
            taskPanels.any { it.startEpochMillis < NOW + HOUR },
            "a resilience of 1 leaves the task free of the period, so it runs before it too",
        )
        assertTrue(
            taskPanels.any { it.startEpochMillis < NOW + 2 * HOUR && it.endEpochMillis > NOW + HOUR },
            "and inside it",
        )
    }

    @Test
    fun a_half_resilient_task_keeps_half_its_percentage_inside_the_period() {
        // The middle of the range, which the old pair of booleans could not say at all: inside a period it is
        // 0.5-resilient to, a task carries half its percentage — so it still runs there, just less. Measured
        // against a rival that is unaffected.
        val (s0, solo) = stateWithOneTask(minMinutes = 10)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind("noisy"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(solo, "noisy", 0.5))
        val cells = s.lists[s.rootListId]!!.cellIds
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cells[1], "Rival"))
        val rival = s.tasks.keys.first { s.tasks[it]!!.title == "Rival" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(rival, 10))
        // The rival is the UNAFFECTED one, and it has to say so: a kind the user defines is added to every
        // task at the default 0, so a task that has never been given a value for it is forbidden there.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(rival, "noisy", 1.0))
        s = s.copy(
            panels = s.panels + TaskPanel(
                id = "period/noisy",
                taskId = null,
                title = "noisy",
                startEpochMillis = NOW,
                endEpochMillis = NOW + 6 * HOUR,
                periodKind = "noisy",
            ),
        )
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 6 * HOUR)
        fun served(id: org.example.project.scheduler.model.TaskId) =
            panels.filter { it.taskId == id && it.auto }.sumOf { it.endEpochMillis - it.startEpochMillis }
        val mine = served(solo)
        val theirs = served(rival)
        assertTrue(mine > 0, "a half-resilient task still runs inside the period")
        assertTrue(
            mine < theirs,
            "but less than the unaffected rival: $mine vs $theirs",
        )
    }

    @Test
    fun fill_places_nothing_inside_a_hand_added_inactivity_period() {
        // PRD §8 (the user's spec): an inactivity period is the GREY one, and grey means the scheduler places
        // nothing there. This is what a §17 sleep window and a screen break's closed head already were; a
        // hand-added inactivity period is the same period, so it accepts nobody too. (It used to classify
        // nothing at all — the fill planned straight over it.)
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddInactivityPeriod(NOW + HOUR, NOW + 2 * HOUR))
        val taskPanels = SchedulerDomain.fillSchedule(s, NOW).filter { it.taskId == solo }
        assertTrue(taskPanels.isNotEmpty())
        assertTrue(
            taskPanels.none { it.startEpochMillis < NOW + 2 * HOUR && it.endEpochMillis > NOW + HOUR },
            "no task may be scheduled inside an inactivity period",
        )
        // It is a period, not an occupancy obstacle: the plan flows up to it and resumes on the far side.
        assertTrue(taskPanels.any { it.endEpochMillis == NOW + HOUR })
        assertTrue(taskPanels.any { it.startEpochMillis == NOW + 2 * HOUR })
    }

    @Test
    fun a_run_meeting_an_inactivity_period_is_suspended_by_it_rather_than_ended_by_it() {
        // PRD §17 states this for a sleep window — a task that meets one is "split and resumes at wake, not
        // charged for the sleep time, like a screen break" — and a sleep window is just an inactivity period
        // with a label (§8), so every grey period SUSPENDS a run instead of ending it. The stretch belongs to
        // NOBODY, so it costs the run only time; a screen-zone edge is the other kind of boundary — somebody
        // else may run past it, so it ends the run and PRD §9/§10 cut the minimum there.
        //
        // What separates the two here: a suspending boundary does not count against "does the minimum fit?",
        // so a run may START in the half-hour before the grey period even though its 45-minute minimum does
        // not fit before it. Were the boundary a cutting one, both tasks would be unplaceable in that
        // half-hour and it would be left idle.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "A"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "B"))
        s.tasks.keys.forEach { s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(it, 45)) }
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddInactivityPeriod(NOW + 30 * MIN, NOW + 90 * MIN))
        val autos = SchedulerDomain.fillSchedule(s, NOW).filter { it.auto }.sortedBy { it.startEpochMillis }
        val head = autos.first()
        assertEquals(NOW, head.startEpochMillis, "the half-hour before the grey period must not be left idle")
        assertEquals(NOW + 30 * MIN, head.endEpochMillis, "the run must stop at the grey period, not enter it")
        assertEquals(
            NOW + 90 * MIN,
            autos.first { it.startEpochMillis >= NOW + 30 * MIN }.startEpochMillis,
            "the plan must resume the instant the grey period ends",
        )
    }

    @Test
    fun an_inactivity_period_refuses_a_task_a_no_screen_period_would_welcome() {
        // "No task allowed" is the one kind whose DEFAULT resilience is 0, so a grey period turns away even a
        // task fully resilient to "no on-screen task" — grey is not a screen classification.
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddInactivityPeriod(NOW + HOUR, NOW + 2 * HOUR))
        val taskPanels = SchedulerDomain.fillSchedule(s, NOW).filter { it.taskId == solo && it.auto }
        assertTrue(taskPanels.isNotEmpty(), "the task must still fill the timeline around the grey period")
        assertTrue(
            taskPanels.none { it.startEpochMillis < NOW + 2 * HOUR && it.endEpochMillis > NOW + HOUR },
            "the inactivity period must refuse it",
        )
    }

    @Test
    fun a_task_resilient_to_no_screen_needs_no_period_to_run_in() {
        // The other half of the change above: with no no-screen period anywhere, a task fully resilient to
        // that kind is an ordinary task and fills the timeline.
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
        )
        val panels = SchedulerDomain.fillSchedule(s, NOW)
        assertTrue(panels.any { it.taskId == solo && it.auto })
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
                ),
            ),
        )

    @Test
    fun a_task_resilient_to_no_task_allowed_may_work_inside_a_dynamic_period() {
        // `side-dev/README.md`: all three dynamic periods are "no task allowed", and a resilience to THAT
        // kind is the only thing that opens one. It replaces the old "doable during a screen break" switch,
        // and unlike it there is no closed head and no restriction to the shorter pose: a break is a period
        // of a kind like any other, and a task that declares itself resilient to it works straight through.
        val (s0, solo) = stateWithOneTask(minMinutes = 5)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_TASK, 1.0),
        )
        s = withRestPose(s)
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 4 * HOUR)
        val breakRange = panels.first { it.screenBreak }
        val inBreak = panels.filter {
            it.taskId == solo && it.auto &&
                it.startEpochMillis < breakRange.endEpochMillis &&
                it.endEpochMillis > breakRange.startEpochMillis
        }
        assertTrue(inBreak.isNotEmpty(), "a task resilient to \"no task allowed\" must fill the break")
    }

    @Test
    fun break_doable_task_with_a_too_long_minimum_never_fills_the_break() {
        // The pose leaves 14 min once its closed first minute is taken off, but the task needs 30.
        val (s0, solo) = stateWithOneTask(minMinutes = 30)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
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
    fun a_task_with_no_resilience_to_no_task_allowed_is_kept_out_of_every_dynamic_period() {
        // The default for that kind is 0, so an ordinary task — whatever it says about a screen — is kept out
        // of all three dynamic periods without anybody having to state it.
        val (s0, solo) = stateWithOneTask(minMinutes = 5)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
        )
        s = withRestPose(s)
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 4 * HOUR)
        val breaks = panels.filter { it.screenBreak }
        assertTrue(breaks.isNotEmpty(), "the case needs a break to be about")
        assertTrue(
            panels.none { p ->
                p.taskId == solo && p.auto &&
                    breaks.any {
                        p.startEpochMillis < it.endEpochMillis && p.endEpochMillis > it.startEpochMillis
                    }
            },
            "nothing may run inside a \"no task allowed\" period without a resilience to that kind",
        )
    }

    @Test
    fun the_20s_look_away_accepts_no_task_at_all() {
        // PRD §15: a look-away is not a rest pose, so it is closed end to end — even a zero-minimum
        // break-doable task may not be placed in it (and it therefore distorts no plan around itself).
        val (s0, solo) = stateWithOneTask(minMinutes = 0)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
        )
        s = s.copy(
            screenBreaks = listOf(
                ScreenBreak(
                    title = "look 20 feet away",
                    intervalMillis = 20 * MIN,
                    durationMillis = 20_000L,
                    restBreak = false,
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
                ),
            ),
        )

    @Test
    fun a_dynamic_period_has_no_shape_left_to_read() {
        // The 15-minute period is not a longer copy of the 5-minute one, and neither has a closed head any
        // more: `side-dev/README.md` gives all three the single kind "no task allowed", end to end. So a task
        // resilient to that kind fills one from its very FIRST second, and there is no opening minute to wait
        // through.
        val (s0, solo) = stateWithOneTask(minMinutes = 5)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_TASK, 1.0),
        )
        s = with15MinPose(s)
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 4 * HOUR)
        val pose = panels.first { it.screenBreak }
        val inPose = panels.filter {
            it.taskId == solo && it.auto &&
                it.startEpochMillis < pose.endEpochMillis && it.endEpochMillis > pose.startEpochMillis
        }
        assertTrue(inPose.isNotEmpty(), "a resilient task must fill the period")
        assertTrue(
            inPose.any { it.startEpochMillis <= pose.startEpochMillis },
            "there is no closed first minute to wait through",
        )
    }

    @Test
    fun the_shorter_dynamic_period_is_the_same_kind_as_the_longer_one() {
        // The two poses used to be different shapes with different accepted sets. They are the same object
        // now — one span of "no task allowed" — so the same task is refused by both, and admitted by both the
        // moment it declares a resilience to that kind.
        val (s0, solo) = stateWithOneTask(minMinutes = 1)
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
        )
        s = s.copy(
            screenBreaks = listOf(
                ScreenBreak(
                    title = "take a 5min pose and blink hard",
                    intervalMillis = HOUR,
                    durationMillis = 5 * MIN,
                    restBreak = true,
                ),
            ),
        )
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 4 * HOUR)
        val breaks = panels.filter { it.screenBreak }
        assertTrue(breaks.isNotEmpty())
        assertTrue(
            panels.none { p ->
                p.taskId == solo && p.auto &&
                    breaks.any {
                        p.startEpochMillis < it.endEpochMillis && p.endEpochMillis > it.startEpochMillis
                    }
            },
            "the shorter period refuses exactly what the longer one refuses",
        )
    }

    @Test
    fun an_on_screen_task_is_never_placed_in_a_screen_break() {
        // `side-dev/README.md`: all three dynamic periods are "no task allowed", whose default resilience is
        // 0 — so a break admits nobody unless a task has deliberately been given a value for that kind, and
        // an on-screen task certainly has not.
        val (s0, solo) = stateWithOneTask(minMinutes = 5)
        val s = withRestPose(
            s0.copy(
                tasks = s0.tasks +
                    (solo to s0.tasks[solo]!!.copy(resilience = mapOf(PeriodKinds.NO_SCREEN to 0.0))),
            ),
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
    fun advance_writes_no_inactivity_panel_over_the_covered_span() {
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
        // The covered hour banks no record (above) and that is ALL it does. A grey period is an object the
        // user drew, or one the rules lay; "the app was not running / nobody was at a screen" is evidence,
        // and evidence stays DERIVED — the calendar paints the vacated hour as a derived grey band. The app
        // used to materialize a real `no task allowed` panel here, which persisted and SYNCED an observation
        // (218 of them had piled up on the release account by 2026-08-29), and silently upgraded a
        // "no on-screen task" observation into a period that refuses off-screen tasks too.
        assertTrue(
            advanced.panels.none { it.inactivity },
            "a no-screen period must not materialize a grey panel: ${advanced.panels.filter { it.inactivity }}",
        )
        // And it stays that way however many times the tick runs — this was the unbounded-growth path.
        val again = SchedulerReducer.reduce(advanced, SchedulerIntent.AdvanceSchedule(NOW))
        assertTrue(again.panels.none { it.inactivity })
    }

    @Test
    fun advance_materializes_no_inactivity_for_an_off_screen_task() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
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
    fun advance_leaves_a_hand_added_inactivity_panel_alone() {
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
    fun device_sleep_cut_writes_no_inactivity_panel() {
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
        // Same rule as the advance tick: the device going to sleep is evidence, never a period.
        assertTrue(
            slept.panels.none { it.inactivity },
            "a device sleep must not materialize a grey panel: ${slept.panels.filter { it.inactivity }}",
        )
    }

    @Test
    fun advance_banks_the_whole_record_for_an_off_screen_task_inside_the_period() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0),
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
