package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * `side-dev/README.md` § *$now line$* + § *Progressive Calculation*'s direct consequence: **waking from device
 * sleep is a JOURNEY of the now-line, walked in mode 2 — never a landing.**
 *
 * *"If the device bearing the running process is put to sleep, then when the program wakes up, the now line
 * does a fast move forward (in epsilon time) in mode 2 to the current date. If the current date is beyond the
 * definitive schedule, then it is similar to a case where no CPU were available during this period and the
 * current set of rules, parameterized by now line and now line mode, is used to define the schedule as the now
 * line does its fast move, while no better set of rules was found."*
 *
 * Three things had to be true for that and none of them were. The engine **teleported** (`advanceTo(sleepEnd)`
 * in one step, which the line "moves continuously forward in time" forbids); the mode was read at the
 * ARRIVAL, where the machine is unlocked again, so the whole night was answered in mode 1; and mode 2's own
 * rule — *"the now line must be covered by the period 'no on-screen task'"* — reached the timeline only when
 * the ten-minute OS lock scan next landed, so for up to ten minutes after a wake the recurrence bars went on
 * counting from the last recorded break and a break could fall due at once.
 */
class NowLineSweepTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
    private val lookAway = breaks.first { it.durationMillis == 20 * SEC }.title
    private val pose15 = breaks.first { it.durationMillis == 15 * MIN }.title

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
        SchedulerReducer.noScreenEvidence = { emptyList() }
    }

    // ----- the step ------------------------------------------------------------------------------

    /** One schedulable task with a [minutes] minimum execution time. */
    private fun oneTask(minutes: Int, title: String = "Solo"): SchedulerState {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, title))
        val id = s.tasks.keys.first { s.tasks[it]!!.title == title }
        return SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(id, minutes))
    }

    @Test
    fun the_step_is_the_smallest_minimum_execution_time_in_force_at_the_line() {
        // `side-dev/scheduler.py`'s `Walk._sweep_step`: the finest thing the walk can place is one task's
        // minimum, so a line that never skips a whole minimum never skips a placement it should have entered.
        assertEquals(45 * MIN, SchedulerDomain.sweepStepMillis(oneTask(45), NOW))

        // The SMALLEST of them, not the one that happens to sort first: a 5-minute task is a placement the
        // line would step over if it moved by the 45-minute one.
        var two = oneTask(45)
        two = SchedulerReducer.reduce(
            two,
            SchedulerIntent.SetCellTitle(two.lists[two.rootListId]!!.cellIds[1], "Quick"),
        )
        val quick = two.tasks.keys.first { two.tasks[it]!!.title == "Quick" }
        two = SchedulerReducer.reduce(two, SchedulerIntent.SetTaskMinimumTime(quick, 5))
        assertEquals(5 * MIN, SchedulerDomain.sweepStepMillis(two, NOW))

        // No minimum anywhere is no bound on the step: the journey is one commit, which is what an empty
        // account (and every `min_time == 0` task in the reference) asks for.
        assertNull(SchedulerDomain.sweepStepMillis(oneTask(0), NOW))
        assertNull(SchedulerDomain.sweepStepMillis(SchedulerState.empty(), NOW))
    }

    // ----- the journey ---------------------------------------------------------------------------

    private class Harness(var now: Long, initial: SchedulerState = SchedulerState.empty()) {
        val vm = TaskSchedulerViewModel(initial = initial, store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock { override fun nowMillis(): Long = now },
                scope = CoroutineScope(Dispatchers.Unconfined),
                // Awake and unlocked, which is what a machine that has just woken up reports — and what made
                // reading the mode at the arrival answer mode 1 for the whole night.
                screenActive = { true },
                playCue = {},
            )

        /** Every position the line was seen at, with the mode it was asked in there. */
        fun walk(
            fromMillis: Long,
            toMillis: Long,
            awaySpans: List<TaskTimeRange> = emptyList(),
        ): List<Pair<Long, Int>> {
            val seen = mutableListOf<Pair<Long, Int>>()
            val job =
                CoroutineScope(Dispatchers.Unconfined).launch {
                    engine.nowMillis.collect { seen += it to engine.tpModeNow(it) }
                }
            engine.reportTimeGap(fromMillis, toMillis, awaySpans)
            job.cancel()
            // The flow replays its current value to a fresh collector, so the first entry is where the line
            // already was; the journey is everything after it.
            return seen.drop(1)
        }
    }

    @Test
    fun a_wake_walks_the_line_step_by_step_and_lands_on_the_wake_instant() {
        // Eight hours asleep, a 45-minute minimum: the line has ground to cover, so the journey is more than
        // one commit — and every one of its positions is a value the line really took.
        val h = Harness(now = NOW, initial = oneTask(45))
        val sleepStart = NOW - 8 * HOUR

        val walk = h.walk(sleepStart, NOW)
        assertTrue(walk.size > 1, "the line teleported: one commit for eight hours of ground ($walk)")
        assertEquals(NOW, walk.last().first, "the journey must end at the wake instant")
        assertEquals(NOW, h.engine.nowMillis.value)

        // Monotone, and no step wider than the minimum execution time in force — the line skipped no slot.
        var previous = sleepStart
        for ((at, _) in walk) {
            assertTrue(at > previous, "the line only ever moves forward: $previous then $at")
            assertTrue(
                at - previous <= 45 * MIN,
                "a step of ${(at - previous) / MIN}min steps over a 45-minute placement",
            )
            previous = at
        }
    }

    @Test
    fun every_position_of_the_journey_is_asked_in_mode_two() {
        // *"...does a fast move forward (in epsilon time) in mode 2..."* — the mode of the JOURNEY, not of the
        // arrival. The machine reports unlocked here (it has just woken), which is exactly the reading that
        // made this mode 1 before.
        val h = Harness(now = NOW, initial = oneTask(45))

        val walk = h.walk(NOW - 8 * HOUR, NOW)
        assertTrue(walk.isNotEmpty())
        assertTrue(
            walk.all { it.second == DynamicPeriods.MODE_AWAY },
            "the journey must be walked in mode 2: ${walk.map { it.second }.distinct()}",
        )

        // …and the arrival is back on the live reading, or the app would stay away after the user came back.
        assertEquals(DynamicPeriods.MODE_AT_SCREEN, h.engine.tpModeNow(NOW))
    }

    @Test
    fun a_stretch_the_account_declared_away_for_is_swept_in_mode_three() {
        // `side-dev/README.md` § *Progressive Calculation*, direct consequence, as amended by mode 3: *"When
        // the app wakes up, it asks the server for any changes. If there was a period of $now line$ mode 3,
        // then the fast forward move of the $now line$ ... will get in mode 3 at those periods, instead of
        // always mode 2."*
        //
        // The ask is the caller's (the tick loop is a coroutine; the journey is not), so what is pinned here
        // is the journey's own half: handed the account's mode-3 stretches, the walk holds mode 3 over exactly
        // them and mode 2 everywhere else — and no single step straddles an edge, or a placement would be
        // committed in a mode that did not hold for half of itself.
        val h = Harness(now = NOW, initial = oneTask(45))
        val sleepStart = NOW - 8 * HOUR
        val declared = TaskTimeRange(NOW - 6 * HOUR, NOW - 4 * HOUR)

        val walk = h.walk(sleepStart, NOW, listOf(declared))
        assertTrue(walk.isNotEmpty())
        // Each entry is where the line ARRIVED, so the mode that held for the step ending there is the one
        // asked at its interior — read the position back against the span it landed in or just left.
        for ((at, mode) in walk) {
            val inside = at > declared.startEpochMillis && at <= declared.endEpochMillis
            val expected = if (inside) DynamicPeriods.MODE_ON_BREAK else DynamicPeriods.MODE_AWAY
            assertEquals(expected, mode, "the line at $at (declared away = $inside)")
        }
        // The edges are positions the line really took: a step is cut at each of them.
        val positions = walk.map { it.first }
        assertTrue(declared.startEpochMillis in positions, "a step must end at the start of the mode-3 span")
        assertTrue(declared.endEpochMillis in positions, "…and another at its end")

        // With no spans at all it is the plain mode-2 journey it always was.
        val plain = Harness(now = NOW, initial = oneTask(45)).walk(sleepStart, NOW)
        assertTrue(plain.all { it.second == DynamicPeriods.MODE_AWAY })
    }

    @Test
    fun the_swept_stretch_is_covered_by_no_on_screen_task_at_once() {
        // Mode 2's rule is that the line IS covered, so a stretch the line swept in mode 2 is covered — a fact
        // about the mode, owing nothing to the OS lock scan. This harness has no lock history at all (the JVM
        // shell's `deviceLockedIntervals` reports nothing), which is precisely the case that used to leave the
        // bars counting from the last recorded break for ten minutes after every wake.
        val h = Harness(now = NOW, initial = oneTask(45))
        assertTrue(h.engine.noScreenEvidence.value.isEmpty(), "nothing observed yet")

        h.engine.reportTimeGap(NOW - 8 * HOUR, NOW)

        val covered = h.engine.noScreenEvidence.value
        assertEquals(1, covered.size, "the swept stretch must be one covered span: $covered")
        assertEquals(NOW - 8 * HOUR, covered.single().startEpochMillis)
        assertEquals(NOW, covered.single().endEpochMillis)
    }

    @Test
    fun the_swept_cover_bars_the_breaks_that_follow_it() {
        // The behavioural payoff, in the bars' own terms: a stretch covered by "no on-screen task" with no task
        // in it is the README's rest stretch, so a night of it bars the 20 s period for twenty minutes and the
        // 15 min for two hours after the wake. An on-screen task is a 0 against `no on-screen task`, which is
        // what "on screen" IS.
        val onScreen = listOf(PlanTask(TaskId("task/user/0"), 1.0, 45 * MIN, mapOf(PeriodKinds.NO_SCREEN to 0.0)))
        fun place(covered: List<TaskTimeRange>): List<TaskPanel> =
            SchedulerDomain.screenBreakPanels(
                breaks, NOW, NOW + 3 * HOUR,
                SchedulerDomain.observedNoScreenPeriods(covered),
                emptyList(),
                onScreen,
                DynamicPeriods.MODE_AT_SCREEN,
            )

        // Without the cover the app has no idea the night happened: the owed chain is dragging AT the line, so
        // a break falls due the instant the user comes back. That is the anomaly this fixes.
        val blind = place(emptyList())
        assertEquals(
            NOW + 1,
            blind.minOfOrNull { it.startEpochMillis },
            "the scenario must contain the break the night is supposed to bar",
        )

        // With it, nothing is owed for twenty minutes — the shortest of the three bars after a rest stretch.
        val swept = place(listOf(TaskTimeRange(NOW - 8 * HOUR, NOW)))
        val earliest = swept.minOfOrNull { it.startEpochMillis }
        assertTrue(
            earliest == null || earliest >= NOW + 20 * MIN,
            "a swept night bars every dynamic period for twenty minutes; got ${earliest?.minus(NOW)?.div(MIN)}min",
        )
        // …and the 15 min period for two hours, which is the bar a night of it fires in particular.
        val pose = swept.filter { it.title == pose15 }.minOfOrNull { it.startEpochMillis }
        assertTrue(
            pose == null || pose >= NOW + 2 * HOUR,
            "a swept night bars the 15 min period for two hours; got ${pose?.minus(NOW)?.div(MIN)}min",
        )
    }

    @Test
    fun an_ordinary_tick_is_still_one_commit() {
        // The step is a bound on the journey, not a cadence: a tick of thirty seconds is far inside the first
        // step, so nothing here costs more than it did — which is what keeps the sweep off ADR 0009's budget.
        val state = oneTask(45)
        val step = SchedulerDomain.sweepStepMillis(state, NOW)!!
        assertTrue(ADVANCE_TICK_SECONDS * SEC < step, "the production tick must be inside one step")

        // The line is seeded an hour back so the single commit is a value it had not already taken (a
        // StateFlow publishes no event for a value it is already at).
        val h = Harness(now = NOW - HOUR, initial = state)
        assertEquals(1, h.walk(NOW - 30 * SEC, NOW).size, "a 30-second gap is one commit")
    }

    private companion object {
        /** `SchedulerEngine`'s production advance cadence, in seconds. */
        const val ADVANCE_TICK_SECONDS = 30L
    }

    /** A ScreenBreak fixture guard: the three the README names are what the bars above are written against. */
    @Test
    fun the_fixture_is_the_three_the_readme_names() {
        assertEquals(3, breaks.size)
        assertTrue(breaks.any { it.durationMillis == 20 * SEC && it.intervalMillis == 20 * MIN })
        assertTrue(breaks.any { it.durationMillis == 5 * MIN && it.intervalMillis == HOUR })
        assertTrue(breaks.any { it.durationMillis == 15 * MIN && it.intervalMillis == 2 * HOUR })
    }
}
