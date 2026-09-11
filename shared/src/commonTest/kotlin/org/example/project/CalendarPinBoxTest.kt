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
import org.example.project.ui.panelPinBoxSpec

/**
 * PRD §8: **everything the user placed by hand wears a blue outline and a pin box**, and that box is the
 * scheduler's own "keep this occurrence" switch reached from the panel.
 *
 * Three separate claims, one per section below:
 *  - [SchedulerDomain.isUserPlaced] — who placed a panel, asked as the complement of what the app lays down
 *    itself, so a new family of generated panel can never quietly acquire an outline;
 *  - a drag or a resize IS the existence pin ([SchedulerDomain.pinsAfterHandPlacement]) — without it the
 *    gesture made the panel user-authored and *unpinned*, which is exactly the shape the fill deletes, so the
 *    re-plan the edit itself triggers silently undid it;
 *  - unpinning hands the stretch back to the scheduler — cut ahead of the now-line, kept behind it, and
 *    truncated AT it, because the frozen past belongs to the timeline whoever placed the panel.
 */
class CalendarPinBoxTest {

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

    // ----- which blocks wear a box at all -------------------------------------------------------

    /** A displayed block, as the calendar builds one — only the fields the box's rule reads. */
    private fun block(
        userPlaced: Boolean = true,
        noScreen: Boolean = false,
        inactivity: Boolean = false,
        existence: Boolean = true,
    ) = PlacedRecord(
        title = "block",
        startHour = 9f,
        endHour = 10f,
        scheduled = false,
        userPlaced = userPlaced,
        noScreen = noScreen,
        inactivity = inactivity,
        pins = PanelPins(existence = existence),
    )

    @Test
    fun only_a_user_placed_block_wears_a_box_and_it_is_that_blocks_own_pin() {
        assertNull(panelPinBoxSpec(block(userPlaced = false)), "the app placed it: no box")
        val spec = panelPinBoxSpec(block(existence = true))
        assertNotNull(spec)
        assertTrue(spec.checked)
        assertTrue(spec.enabled, "on a task panel the box is a real switch")
        assertEquals(false, panelPinBoxSpec(block(existence = false))?.checked)
    }

    @Test
    fun a_no_screen_period_wears_no_box_at_all() {
        // The user drew it, so it keeps the outline — but it is a DECORATIVE panel (it patterns the timeline
        // rather than occupying it, and has no fill of its own), and the box it would wear could only ever be
        // inert: a period is reached by its KIND and taken away with "Remove", never unpinned.
        assertNull(panelPinBoxSpec(block(noScreen = true)))
        assertNull(panelPinBoxSpec(block(noScreen = true, existence = false)))
    }

    @Test
    fun an_inactivity_period_wears_an_inert_box() {
        val spec = panelPinBoxSpec(block(inactivity = true, existence = false))
        assertNotNull(spec)
        assertFalse(spec.enabled, "a period has no 'still drawn, no longer obeyed' state")
        // Checked as a RULE, not off the field, so a period an older build wrote needs no migration.
        assertTrue(spec.checked)
    }

    // ----- a hand-drawn period's box states a fact ---------------------------------------------

    @Test
    fun a_hand_drawn_period_is_pinned_in_the_box_but_never_in_the_scheduler() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, NOW + 2 * HOUR, NOW + 3 * HOUR))
        val periods = s.panels.filter { it.isRestrictivePeriod }
        assertEquals(2, periods.size)
        periods.forEach { period ->
            assertTrue(period.pins.existence, "the pin box reads checked on a period: ${period.title}")
            // A period reaches the scheduler by its KIND. Letting it set `pinned` would ALSO enter it in the
            // walk's pre-placed blocks — a block owned by no task, on top of the period it already is.
            assertFalse(SchedulerDomain.isSchedulerFixed(period), "a period is not a pre-placed block")
        }
    }

    @Test
    fun the_box_cannot_make_a_period_a_pre_placed_block() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        val id = s.panels.first { it.noScreen }.id
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPanelPinned(listOf(id), true))
        assertFalse(s.panels.first { it.id == id }.pinned)

        // ...and neither does dragging it, which sets the existence pin like every other hand placement.
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
        assertTrue(SchedulerDomain.isUserPlaced(resized), "so it wears the blue outline and the box")

        // The whole point: the re-plan the edit itself triggers must not undo the resize.
        val kept = fill(s).firstOrNull { it.id == resized.id }
        assertNotNull(kept, "a pinned block survives the re-plan")
        assertEquals(resized.startEpochMillis, kept.startEpochMillis)
        assertEquals(newEnd, kept.endEpochMillis)
    }

    // ----- unpinning hands the stretch back ----------------------------------------------------

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
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPanelPinned(listOf(id), false))
        assertFalse(s.panels.first { it.id == id }.pinned)
        assertFalse(s.panels.first { it.id == id }.pins.existence)
        // CLAUDE.md: whatever wants to re-plan belongs in the signature, never in a fresh dispatch site.
        assertTrue(before != SchedulerDomain.schedulingSignature(s), "the rule watcher is what re-plans")
    }

    @Test
    fun an_unpinned_panel_ahead_of_the_line_is_cut_by_the_next_fill() {
        var (s, _, id) = withPinnedPanel(NOW + 2 * HOUR, NOW + 3 * HOUR)
        assertNotNull(fill(s).firstOrNull { it.id == id }, "while pinned the fill keeps it")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPanelPinned(listOf(id), false))
        assertNull(fill(s).firstOrNull { it.id == id }, "unpinned and still ahead: the scheduler owns it again")
    }

    @Test
    fun an_unpinned_panel_wholly_behind_the_line_is_kept() {
        var (s, _, id) = withPinnedPanel(NOW - 3 * HOUR, NOW - 2 * HOUR)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPanelPinned(listOf(id), false))
        val kept = fill(s).firstOrNull { it.id == id }
        assertNotNull(kept, "the past is frozen: unpinning cannot rewrite what already happened")
        assertEquals(NOW - 3 * HOUR, kept.startEpochMillis)
        assertEquals(NOW - 2 * HOUR, kept.endEpochMillis)
    }

    @Test
    fun unpinning_the_panel_the_line_stands_in_keeps_its_elapsed_head() {
        var (s, _, id) = withPinnedPanel(NOW - HOUR, NOW + HOUR)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPanelPinned(listOf(id), false))
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
    fun the_pin_box_is_one_undoable_calendar_delta() {
        var (s, _, id) = withPinnedPanel(NOW + 2 * HOUR, NOW + 3 * HOUR)
        val unitsBefore = s.histories.calendar.units.size
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPanelPinned(listOf(id), false))
        assertEquals(unitsBefore + 1, s.histories.calendar.units.size)
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertTrue(s.panels.first { it.id == id }.pinned, "undo puts the pin back")
        // A toggle that changes nothing records nothing.
        val stable = SchedulerReducer.reduce(s, SchedulerIntent.SetPanelPinned(listOf(id), true))
        assertEquals(s.histories.calendar.units.size, stable.histories.calendar.units.size)
    }
}
