package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §8: **the two calendar LAYERS as periods the user can DRAW** —
 * [PeriodKinds.NO_COMPUTER_UNLOCKED] and [PeriodKinds.NO_PHONE_UNLOCKED].
 *
 * A layer used to be evidence only (the OS lock history, plus the "I'm away" button for the device the app
 * runs on), so the sentence *nobody was at a computer here* could not be put on the calendar unless the user
 * was also willing to claim the phone was down. As kinds they are periods like any other — one chooser, one
 * editor, one "Remove" — and the rules they obey are the ones that were already written:
 *
 *  - each asserts its OWN layer and nothing else ([SchedulerDomain.assertedLayerRanges]);
 *  - **a no-screen period is where BOTH layers fall**, so the OVERLAP of the two is one
 *    ([SchedulerDomain.assertedNoScreenRanges]) — and that is the only thing either of them restricts
 *    through, since neither has a scheduling rule of its own;
 *  - two overlapping statements of one layer are ONE statement, exactly as two "No screen" periods are
 *    ([SchedulerDomain.unifyNoScreenPeriods]); two statements about DIFFERENT screens are not.
 */
class LayerPeriodKindTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private fun period(kind: String, start: Long, end: Long, id: String = kind + "@" + start) =
        TaskPanel(
            id = id,
            taskId = null,
            title = PeriodKinds.periodTitle(kind),
            startEpochMillis = start,
            endEpochMillis = end,
            periodKind = kind,
        )

    private fun range(start: Long, end: Long) = TaskTimeRange(start, end)

    // ----- what each kind asserts ---------------------------------------------------------------

    @Test
    fun each_one_sided_kind_asserts_its_own_layer_and_no_other() {
        val panels = listOf(
            period(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW, NOW + HOUR),
            period(PeriodKinds.NO_PHONE_UNLOCKED, NOW + 2 * HOUR, NOW + 3 * HOUR),
        )
        assertEquals(
            listOf(range(NOW, NOW + HOUR)),
            SchedulerDomain.assertedLayerRanges(panels, SchedulerDomain.ActivityLayer.NoComputerUnlocked),
        )
        assertEquals(
            listOf(range(NOW + 2 * HOUR, NOW + 3 * HOUR)),
            SchedulerDomain.assertedLayerRanges(panels, SchedulerDomain.ActivityLayer.NoPhoneUnlocked),
        )
    }

    @Test
    fun a_no_screen_period_asserts_both_layers_and_a_grey_one_asserts_neither() {
        val panels = listOf(
            period(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR),
            period(PeriodKinds.NO_TASK, NOW + 2 * HOUR, NOW + 3 * HOUR),
            period(PeriodKinds.BEFORE_BED, NOW + 4 * HOUR, NOW + 5 * HOUR),
        )
        SchedulerDomain.ActivityLayer.entries.forEach { layer ->
            assertEquals(
                listOf(range(NOW, NOW + HOUR)),
                SchedulerDomain.assertedLayerRanges(panels, layer),
                "only the no-screen period speaks about a screen: " + layer.name,
            )
        }
    }

    // ----- both layers = a no-screen period -----------------------------------------------------

    @Test
    fun the_overlap_of_the_two_one_sided_kinds_is_a_no_screen_stretch() {
        val panels = listOf(
            period(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW, NOW + 3 * HOUR),
            period(PeriodKinds.NO_PHONE_UNLOCKED, NOW + 2 * HOUR, NOW + 5 * HOUR),
        )
        assertEquals(
            listOf(range(NOW + 2 * HOUR, NOW + 3 * HOUR)),
            SchedulerDomain.assertedNoScreenRanges(panels),
            "no screen is where BOTH fall — not the union of the two",
        )
    }

    @Test
    fun one_sided_periods_that_never_meet_assert_no_no_screen_time_at_all() {
        val panels = listOf(
            period(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW, NOW + HOUR),
            period(PeriodKinds.NO_PHONE_UNLOCKED, NOW + 2 * HOUR, NOW + 3 * HOUR),
        )
        assertTrue(SchedulerDomain.assertedNoScreenRanges(panels).isEmpty())
        assertTrue(SchedulerDomain.impliedNoScreenPeriods(panels).isEmpty())
    }

    @Test
    fun an_explicit_no_screen_period_is_never_counted_twice() {
        // The plan MULTIPLIES the resiliences of every covering period, so handing it the same stretch as
        // both a drawn "No screen" period and an implied one would square a task's resilience to the kind.
        val panels = listOf(period(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
        assertEquals(listOf(range(NOW, NOW + HOUR)), SchedulerDomain.assertedNoScreenRanges(panels))
        assertTrue(
            SchedulerDomain.impliedNoScreenPeriods(panels).isEmpty(),
            "the period is already there under its own kind",
        )
    }

    @Test
    fun the_implied_period_covers_only_what_no_drawn_no_screen_period_already_does() {
        val panels = listOf(
            period(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW, NOW + 4 * HOUR),
            period(PeriodKinds.NO_PHONE_UNLOCKED, NOW, NOW + 4 * HOUR),
            period(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR),
        )
        val implied = SchedulerDomain.impliedNoScreenPeriods(panels)
        assertEquals(1, implied.size)
        assertEquals(NOW + HOUR, implied.first().startMillis)
        assertEquals(NOW + 4 * HOUR, implied.first().endMillis)
        assertEquals(PeriodKinds.NO_SCREEN, implied.first().kind)
    }

    // ----- what that means for the plan ---------------------------------------------------------

    /** One on-screen task (the default) with enough work to fill the horizon, and its id. */
    private fun oneOnScreenTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        assertTrue(s.tasks[solo]!!.onScreen, "the default task is an on-screen one")
        return SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, 30)) to solo
    }

    private fun placedMillisIn(panels: List<TaskPanel>, taskId: TaskId, from: Long, to: Long): Long =
        panels.filter { it.taskId == taskId }
            .sumOf { (minOf(it.endEpochMillis, to) - maxOf(it.startEpochMillis, from)).coerceAtLeast(0L) }

    @Test
    fun one_locked_screen_restricts_nobody_but_two_forbid_an_on_screen_task() {
        val (s0, solo) = oneOnScreenTask()
        // A lone "no computer unlocked" period: the phone could be in hand, so this is not no-screen time.
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW + HOUR, NOW + 2 * HOUR),
        )
        val lone = SchedulerDomain.fillSchedule(s, NOW, TimeZone.UTC, horizonMillis = NOW + 6 * HOUR)
        assertTrue(
            placedMillisIn(lone, solo, NOW + HOUR, NOW + 2 * HOUR) > 0L,
            "one locked screen is not 'no screen': the plan may still place on-screen work",
        )

        // Now say the phone was down over the same hour. Both layers fall there, so it IS a no-screen
        // period — and an on-screen task has a resilience of 0 to that kind.
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_PHONE_UNLOCKED, NOW + HOUR, NOW + 2 * HOUR),
        )
        val both = SchedulerDomain.fillSchedule(s, NOW, TimeZone.UTC, horizonMillis = NOW + 6 * HOUR)
        assertEquals(
            0L,
            placedMillisIn(both, solo, NOW + HOUR, NOW + 2 * HOUR),
            "both layers fall here, so it is a no-screen period",
        )
        assertTrue(
            placedMillisIn(both, solo, NOW + 2 * HOUR, NOW + 6 * HOUR) > 0L,
            "and the rest of the horizon is untouched",
        )
    }

    @Test
    fun an_off_screen_task_still_runs_where_both_layers_are_asserted() {
        val (s0, solo) = oneOnScreenTask()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0))
        assertFalse(s.tasks[solo]!!.onScreen)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW + HOUR, NOW + 2 * HOUR),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_PHONE_UNLOCKED, NOW + HOUR, NOW + 2 * HOUR),
        )
        val panels = SchedulerDomain.fillSchedule(s, NOW, TimeZone.UTC, horizonMillis = NOW + 6 * HOUR)
        assertTrue(
            placedMillisIn(panels, solo, NOW + HOUR, NOW + 2 * HOUR) > 0L,
            "§9 lets an off-screen task run in a no-screen period",
        )
    }

    // ----- two statements of one layer are one statement ----------------------------------------

    @Test
    fun overlapping_periods_of_one_layer_kind_fuse_and_the_two_kinds_never_do() {
        val panels = listOf(
            period(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW, NOW + 2 * HOUR, id = "a"),
            period(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW + HOUR, NOW + 3 * HOUR, id = "b"),
            period(PeriodKinds.NO_PHONE_UNLOCKED, NOW, NOW + 3 * HOUR, id = "c"),
        )
        val fused = SchedulerDomain.unifyNoScreenPeriods(panels, keepId = "a")
        assertEquals(listOf("a", "c"), fused.map { it.id }, "the computer's two are one; the phone's stays")
        val keeper = fused.first { it.id == "a" }
        assertEquals(NOW, keeper.startEpochMillis)
        assertEquals(NOW + 3 * HOUR, keeper.endEpochMillis)
    }

    @Test
    fun the_layer_kinds_default_to_restricting_nobody() {
        assertEquals(1.0, PeriodKinds.defaultResilience(PeriodKinds.NO_COMPUTER_UNLOCKED))
        assertEquals(1.0, PeriodKinds.defaultResilience(PeriodKinds.NO_PHONE_UNLOCKED))
        assertEquals(1.0, PeriodKinds.defaultResilience(PeriodKinds.NO_SCREEN))
        // Everything that speaks about the TIMELINE rather than about a screen still refuses everybody.
        assertEquals(0.0, PeriodKinds.defaultResilience(PeriodKinds.NO_TASK))
        assertEquals(0.0, PeriodKinds.defaultResilience(PeriodKinds.BEFORE_BED))
        assertEquals(0.0, PeriodKinds.defaultResilience("deep focus"))
    }

    @Test
    fun a_one_sided_period_carries_no_legacy_flag_and_is_recognised_by_its_kind_alone() {
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW, NOW + HOUR),
        )
        val panel = s.panels.single { it.isRestrictivePeriod }
        assertFalse(panel.noScreen, "the legacy flag stands for 'no on-screen task' and for no other kind")
        assertFalse(panel.inactivity)
        assertEquals(PeriodKinds.NO_COMPUTER_UNLOCKED, panel.restrictiveKind)
        assertEquals("No computer unlocked", panel.title)
    }
}
