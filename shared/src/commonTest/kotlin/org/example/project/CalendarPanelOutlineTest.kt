package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.PlacedRecord

/**
 * PRD §8: **a block's outline says who put it there, and nothing on the calendar wears a check box.**
 *
 * Four separate claims, one per section below:
 *  - [SchedulerDomain.isUserPlaced] — who placed a panel, asked as the complement of what the app lays down
 *    itself, so a new family of generated panel can never quietly acquire the user's blue outline;
 *  - [SchedulerDomain.panelOutline] — the colour that answer is drawn in: blue for the user, ORANGE for a
 *    restrictive period a repeating rule lays (the §17 sleep windows and the wind-down hours), and none at
 *    all for the fill's own task panels;
 *  - a drag or a resize IS the existence pin ([SchedulerDomain.pinsAfterHandPlacement]) — without it the
 *    gesture made the panel user-authored and *unpinned*, which is exactly the shape the fill deletes, so the
 *    re-plan the edit itself triggers silently undid it;
 *  - unpinning (from the edit window, the one surface that still holds the switch) hands the stretch back to
 *    the scheduler — cut ahead of the now-line, kept behind it, and truncated AT it, because the frozen past
 *    belongs to the timeline whoever placed the panel.
 */
class CalendarPanelOutlineTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private fun oneTask(minMinutes: Int = 45): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, minMinutes))
        return s to solo
    }

    /** The fill with no sleep window in the way, so only the panels under test move. */
    private fun fill(s: SchedulerState, now: Long = NOW, horizon: Long = NOW + 6 * HOUR): List<TaskPanel> =
        SchedulerDomain.fillSchedule(s, now, timeZone = TimeZone.UTC, horizonMillis = horizon)

    // ----- who placed it -----------------------------------------------------------------------

    @Test
    fun the_auto_fills_own_panels_are_not_user_placed() {
        val (s, solo) = oneTask()
        val autos = fill(s).filter { it.taskId == solo }
        assertTrue(autos.isNotEmpty())
        assertTrue(autos.none { SchedulerDomain.isUserPlaced(it) }, "a picked panel is the scheduler's")
    }

    @Test
    fun the_three_generated_period_families_are_not_user_placed() {
        // A tree with work in it: the breaks and the wind-down hours are laid around a PLAN, so an empty
        // account would have nothing for this to look at.
        val (s0, _) = oneTask()
        val s = s0.copy(
            sleep = SleepSchedule(wakeMinutes = 450, sleepDurationMinutes = 510),
            screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS,
        )
        val panels = SchedulerDomain.fillSchedule(
            s,
            NOW,
            timeZone = TimeZone.UTC,
            horizonMillis = NOW + 48 * HOUR,
        )
        val breaks = panels.filter { it.screenBreak }
        val sleeps = panels.filter { it.sleep }
        val windDown = panels.filter { it.id.startsWith(SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX) }
        assertTrue(breaks.isNotEmpty(), "the fill lays screen breaks")
        assertTrue(sleeps.isNotEmpty(), "the fill lays sleep windows")
        assertTrue(windDown.isNotEmpty(), "the fill lays the wind-down hours")
        (breaks + sleeps + windDown).forEach {
            assertFalse(SchedulerDomain.isUserPlaced(it), "a regenerated period is not the user's: ${it.id}")
        }
    }

    @Test
    fun a_reminder_tag_is_not_a_panel_the_box_belongs_on() {
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddReminder("", "Pills", NOW, checked = false, pinned = true),
        )
        val tag = s.panels.first { it.chore }
        // It IS the user's placement — but a tag already carries its own §14 check box and is drawn as a chip
        // rather than a panel, so the panel's box has nowhere to sit and nothing left to say.
        assertFalse(SchedulerDomain.isUserPlaced(tag))
    }

    @Test
    fun a_hand_drawn_period_and_a_hand_added_task_panel_are_user_placed() {
        val (s0, solo) = oneTask()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW + HOUR, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, NOW + 3 * HOUR, NOW + 4 * HOUR))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(solo, "Solo", NOW + 5 * HOUR, NOW + 6 * HOUR, PanelPins(existence = true)),
        )
        assertTrue(SchedulerDomain.isUserPlaced(s.panels.first { it.noScreen }))
        assertTrue(SchedulerDomain.isUserPlaced(s.panels.first { it.inactivity }))
        assertTrue(SchedulerDomain.isUserPlaced(s.panels.first { it.taskId == solo && !it.auto }))
    }

    // ----- what the outline says ---------------------------------------------------------------

    @Test
    fun a_repeating_rule_outlines_its_periods_orange_and_its_task_panels_not_at_all() {
        val (s0, solo) = oneTask()
        val s = s0.copy(
            sleep = SleepSchedule(wakeMinutes = 450, sleepDurationMinutes = 510),
            screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS,
        )
        val panels = SchedulerDomain.fillSchedule(
            s,
            NOW,
            timeZone = TimeZone.UTC,
            horizonMillis = NOW + 48 * HOUR,
        )
        val sleeps = panels.filter { it.sleep }
        val windDown = panels.filter { it.id.startsWith(SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX) }
        assertTrue(sleeps.isNotEmpty() && windDown.isNotEmpty())
        // The three DYNAMIC periods are neither: the recurrence bars place them against the timeline itself.
        val breaks = panels.filter { it.screenBreak }
        assertTrue(breaks.isNotEmpty(), "the fill lays screen breaks")
        breaks.forEach {
            assertEquals(
                SchedulerDomain.PanelOutline.Dynamic,
                SchedulerDomain.panelOutline(it),
                "a screen break is a dynamic restrictive period: " + it.id,
            )
        }
        (sleeps + windDown).forEach {
            assertEquals(
                SchedulerDomain.PanelOutline.Pattern,
                SchedulerDomain.panelOutline(it),
                "the §17 schedule is a repeating rule: " + it.id,
            )
        }
        // A picked task panel is not a period and nobody drew it: no outline of its own at all.
        panels.filter { it.taskId == solo && it.auto }.forEach {
            assertEquals(SchedulerDomain.PanelOutline.None, SchedulerDomain.panelOutline(it))
        }
    }

    @Test
    fun every_period_the_user_draws_is_outlined_blue_whatever_its_kind() {
        var s = SchedulerState.empty()
        val kinds = listOf(
            PeriodKinds.NO_SCREEN,
            PeriodKinds.NO_TASK,
            PeriodKinds.BEFORE_BED,
            PeriodKinds.NO_COMPUTER_UNLOCKED,
            PeriodKinds.NO_PHONE_UNLOCKED,
        )
        kinds.forEachIndexed { i, kind ->
            s = SchedulerReducer.reduce(
                s,
                SchedulerIntent.AddRestrictivePeriod(kind, NOW + (2 * i) * HOUR, NOW + (2 * i + 1) * HOUR),
            )
        }
        assertEquals(kinds.size, s.panels.count { it.isRestrictivePeriod })
        s.panels.filter { it.isRestrictivePeriod }.forEach {
            assertEquals(
                SchedulerDomain.PanelOutline.User,
                SchedulerDomain.panelOutline(it),
                "the user drew a " + it.restrictiveKind + " period",
            )
        }
    }

    @Test
    fun a_conducted_break_is_dynamic_and_not_something_the_user_drew() {
        // "Look away now" records one of the three as a period that really happened. It is `auto = false`
        // and carries the user's own press, so without the dynamic answer coming FIRST it would read as a
        // hand-drawn inactivity period and wear the blue outline.
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.RecordConductedBreak("look 20 feet away", NOW, NOW + 20_000),
        )
        val conducted = s.panels.single { it.conductedBreak }
        assertTrue(SchedulerDomain.isUserPlaced(conducted), "nothing about the panel says the app laid it")
        assertEquals(SchedulerDomain.PanelOutline.Dynamic, SchedulerDomain.panelOutline(conducted))
    }

    /** A displayed block, as the calendar builds one. */
    private fun block(
        outline: SchedulerDomain.PanelOutline = SchedulerDomain.PanelOutline.User,
        noScreen: Boolean = false,
        inactivity: Boolean = false,
        existence: Boolean = true,
    ) = PlacedRecord(
        title = "block",
        startHour = 9f,
        endHour = 10f,
        scheduled = false,
        outline = outline,
        noScreen = noScreen,
        inactivity = inactivity,
        pins = PanelPins(existence = existence),
    )

    @Test
    fun the_outline_travels_to_the_drawing_untouched() {
        // The record carries the answer the domain gave; the drawing never re-derives it from the paint.
        assertEquals(SchedulerDomain.PanelOutline.User, block().outline)
        assertEquals(
            SchedulerDomain.PanelOutline.Pattern,
            block(outline = SchedulerDomain.PanelOutline.Pattern, inactivity = true).outline,
        )
        assertEquals(
            SchedulerDomain.PanelOutline.None,
            block(outline = SchedulerDomain.PanelOutline.None).outline,
        )
        assertEquals(
            SchedulerDomain.PanelOutline.Dynamic,
            block(outline = SchedulerDomain.PanelOutline.Dynamic).outline,
        )
    }

    // ----- a hand-drawn period is pinned, and that is a fact about the PIN, not a mark on screen -

    @Test
    fun a_hand_drawn_period_is_pinned_in_the_box_but_never_in_the_scheduler() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, NOW + 2 * HOUR, NOW + 3 * HOUR))
        val periods = s.panels.filter { it.isRestrictivePeriod }
        assertEquals(2, periods.size)
        periods.forEach { period ->
            assertTrue(period.pins.existence, "a hand-drawn period carries the existence pin")
            // A period reaches the scheduler by its KIND. Letting it set `pinned` would ALSO enter it in the
            // walk's pre-placed blocks — a block owned by no task, on top of the period it already is.
            assertFalse(SchedulerDomain.isSchedulerFixed(period), "a period is not a pre-placed block")
        }
    }

    @Test
    fun dragging_a_period_cannot_make_it_a_pre_placed_block() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        val id = s.panels.first { it.noScreen }.id
        // Dragging sets the existence pin like every other hand placement — and the period still never
        // becomes a pre-placed block, because it reaches the scheduler by its KIND.
        val period = s.panels.first { it.id == id }
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel(
                id,
                null,
                period.title,
                NOW + 4 * HOUR,
                NOW + 5 * HOUR,
                SchedulerDomain.pinsAfterHandPlacement(period.pins),
            ),
        )
        val moved = s.panels.first { it.noScreen }
        assertTrue(moved.pins.existence)
        assertFalse(moved.pinned)
        assertEquals(PeriodKinds.NO_SCREEN, moved.restrictiveKind)
    }

    // ----- a drag IS the existence pin ---------------------------------------------------------

    @Test
    fun pins_after_hand_placement_sets_existence_and_touches_nothing_else() {
        val pins = PanelPins(existence = false, position = true, spanning = true, distance = false)
        assertEquals(pins.copy(existence = true), SchedulerDomain.pinsAfterHandPlacement(pins))
        // Already pinned: the same value back, so dragging a pinned panel changes no pin.
        val pinned = pins.copy(existence = true)
        assertEquals(pinned, SchedulerDomain.pinsAfterHandPlacement(pinned))
    }

    @Test
    fun resizing_a_scheduler_panel_makes_it_a_pre_placed_block_the_next_fill_keeps() {
        val (s0, solo) = oneTask(minMinutes = 30)
        var s = s0.copy(panels = fill(s0))
        // A sole task fills the horizon as one merged run, so this is the panel the user sees.
        val auto = s.panels
            .filter { it.taskId == solo && it.auto }
            .minByOrNull { it.startEpochMillis }
        assertNotNull(auto, "the fill must place something for this to test anything")
        val newEnd = auto.endEpochMillis + 20 * MIN
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel(
                auto.id,
                auto.taskId,
                auto.title,
                auto.startEpochMillis,
                newEnd,
                SchedulerDomain.pinsAfterHandPlacement(auto.pins),
            ),
        )
        val resized = s.panels.single {
            it.taskId == solo && !it.auto && it.startEpochMillis == auto.startEpochMillis
        }
        assertEquals(newEnd, resized.endEpochMillis)
        assertTrue(resized.pinned, "the gesture IS the existence pin")
        assertEquals(
            SchedulerDomain.PanelOutline.User,
            SchedulerDomain.panelOutline(resized),
            "so it wears the blue outline",
        )

        // The whole point: the re-plan the edit itself triggers must not undo the resize.
        val kept = fill(s).firstOrNull { it.id == resized.id }
        assertNotNull(kept, "a pinned block survives the re-plan")
        assertEquals(resized.startEpochMillis, kept.startEpochMillis)
        assertEquals(newEnd, kept.endEpochMillis)
    }

    // ----- unpinning hands the stretch back ----------------------------------------------------

    /**
     * PRD §8: unpin [id] the way the calendar edit window does — its Save, which is the ONE surface the
     * Existence switch is reached from now that no panel wears a check box.
     */
    private fun unpin(s: SchedulerState, id: String, pinned: Boolean = false): SchedulerState {
        val panel = s.panels.first { it.id == id }
        return SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel(
                id,
                panel.taskId,
                panel.title,
                panel.startEpochMillis,
                panel.endEpochMillis,
                panel.pins.copy(existence = pinned),
            ),
        )
    }

    /** A single hand-placed, pinned panel of [solo] over `[start, end]`, and its id. */
    private fun withPinnedPanel(start: Long, end: Long): Triple<SchedulerState, TaskId, String> {
        val (s0, solo) = oneTask()
        // Focused, because Undo routes to the focused surface's own stack (PRD §8).
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCalendarFocus(true))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(solo, "Solo", start, end, PanelPins(existence = true)),
        )
        return Triple(s, solo, s.panels.first { !it.auto && it.taskId == solo }.id)
    }

    @Test
    fun unpinning_is_a_scheduling_rule_change() {
        var (s, _, id) = withPinnedPanel(NOW + 2 * HOUR, NOW + 3 * HOUR)
        val before = SchedulerDomain.schedulingSignature(s)
        s = unpin(s, id)
        assertFalse(s.panels.first { it.id == id }.pinned)
        assertFalse(s.panels.first { it.id == id }.pins.existence)
        // CLAUDE.md: whatever wants to re-plan belongs in the signature, never in a fresh dispatch site.
        assertTrue(before != SchedulerDomain.schedulingSignature(s), "the rule watcher is what re-plans")
    }

    @Test
    fun an_unpinned_panel_ahead_of_the_line_is_cut_by_the_next_fill() {
        var (s, _, id) = withPinnedPanel(NOW + 2 * HOUR, NOW + 3 * HOUR)
        assertNotNull(fill(s).firstOrNull { it.id == id }, "while pinned the fill keeps it")
        s = unpin(s, id)
        assertNull(fill(s).firstOrNull { it.id == id }, "unpinned and still ahead: the scheduler owns it again")
    }

    @Test
    fun an_unpinned_panel_wholly_behind_the_line_is_kept() {
        var (s, _, id) = withPinnedPanel(NOW - 3 * HOUR, NOW - 2 * HOUR)
        s = unpin(s, id)
        val kept = fill(s).firstOrNull { it.id == id }
        assertNotNull(kept, "the past is frozen: unpinning cannot rewrite what already happened")
        assertEquals(NOW - 3 * HOUR, kept.startEpochMillis)
        assertEquals(NOW - 2 * HOUR, kept.endEpochMillis)
    }

    @Test
    fun unpinning_the_panel_the_line_stands_in_keeps_its_elapsed_head() {
        var (s, _, id) = withPinnedPanel(NOW - HOUR, NOW + HOUR)
        s = unpin(s, id)
        val head = fill(s).firstOrNull { it.id == id }
        assertNotNull(head, "side-dev/README.md frozen past: the elapsed head never disappears")
        assertEquals(NOW - HOUR, head.startEpochMillis)
        // Cut at the line and re-planned from there — and since the re-plan picks the same sole task again,
        // [mergeSameTaskPanels] fuses the new tail straight back onto this head, which is what it is for. So
        // the end is the plan's, not the panel's; what proves the head went through the frozen-past branch
        // (rather than simply surviving) is that it is an ordinary AUTO panel now.
        assertTrue(head.endEpochMillis >= NOW, "the head still covers everything up to the line")
        assertTrue(head.auto, "the head is history now, so it is an ordinary auto panel")
        assertFalse(head.pinned)
        assertFalse(SchedulerDomain.isUserPlaced(head), "and the calendar stops outlining it as the user's")
    }

    @Test
    fun unpinning_is_one_undoable_calendar_delta() {
        var (s, _, id) = withPinnedPanel(NOW + 2 * HOUR, NOW + 3 * HOUR)
        val unitsBefore = s.histories.calendar.units.size
        s = unpin(s, id)
        assertEquals(unitsBefore + 1, s.histories.calendar.units.size)
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertTrue(s.panels.first { it.id == id }.pinned, "undo puts the pin back")
        // A toggle that changes nothing records nothing.
        val stable = unpin(s, id, pinned = true)
        assertEquals(s.histories.calendar.units.size, stable.histories.calendar.units.size)
    }
}
