package org.example.project

import kotlinx.datetime.TimeZone
import org.example.project.perf.Perf
import org.example.project.perf.PerfReport
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.PlacedRecord
import org.example.project.ui.overlapLayout
import org.example.project.ui.weightHandles
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The **headless** half of the performance tooling: what the app costs per derivation, measured without a
 * window, a clock or a user.
 *
 * The in-app overlay ([org.example.project.ui.PerfOverlay]) answers "what is it doing *now*"; this answers
 * "what does one of these cost, and does the cost follow the screen or the account". The second question is
 * the one CLAUDE.md's hot-path rule is about, and it is the one a test can pin — a wall-clock budget cannot
 * be asserted on an unknown machine, but the *shape* of a cost can:
 *
 *  * [display_derivation_cost_follows_the_visible_window_not_total_history] is a real regression guard. It
 *    asks the same visible week of the same account twice, once with a week of history behind it and once
 *    with a year, and requires the answer to cost about the same. A derivation that started scanning all of
 *    history would fail it whatever machine it ran on, because the assertion is a RATIO.
 *  * [benchmark_report] asserts nothing about time and prints the table. It is the tool, not the gate: run
 *    it before and after a change and read the two outputs.
 *
 * Run just these:
 * `./gradlew :shared:jvmTest --tests "*PerfBenchmarkTest*" -i`
 * (`-i` so the printed report reaches the console; Gradle swallows stdout otherwise.)
 */
class PerfBenchmarkTest {

    private val tz = TimeZone.currentSystemDefault()
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_760_000_000_000L

    // ---- fixtures ------------------------------------------------------------------------------

    /**
     * An account of [taskCount] equal-priority tasks with the production screen breaks — the configuration
     * that makes the fill do real work (with no breaks the walk freezes early and the analytic cycle writes
     * the horizon in one step, which measures nothing the app does).
     */
    private fun account(taskCount: Int): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        val names = (1..taskCount).map { "Task $it" }
        names.forEachIndexed { i, name ->
            val cell = s.lists[s.rootListId]!!.cellIds[i]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, name))
        }
        s = s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val ids = names.map { name -> s.tasks.keys.first { s.tasks[it]!!.title == name } }
        for (id in ids) s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(id, 45))
        return s to ids
    }

    /**
     * [days] of past device activity, as the sessions the derive/segmentation passes read — eight waking
     * hours a day, in one-hour rows, which is roughly the shape a real account accumulates.
     */
    private fun sessions(days: Int): List<TaskTimeRange> = buildList {
        for (d in 1..days) {
            val dayStart = now - d * day
            for (h in 9..16) {
                add(TaskTimeRange(dayStart + h * hour, dayStart + h * hour + 55 * 60_000L))
            }
        }
    }

    /**
     * [count] blocks spread across one day, overlapping in pairs — the shape a busy calendar column has, and
     * the input `overlapLayout` slices. A day with more blocks than hours is what makes the slicing grow.
     */
    private fun dayBlocks(count: Int): List<PlacedRecord> = (0 until count).map { i ->
        val start = ((i % 24) + (i / 24) * 0.13f).coerceIn(0f, 23f)
        PlacedRecord(
            title = "block $i",
            startHour = start,
            endHour = (start + 1.7f).coerceAtMost(24f),
            scheduled = false,
            manual = true,
            entryId = "block $i",
            entryIds = listOf("block $i"),
        )
    }

    /** Median of [times] repetitions of [block] — median, not mean, so one JIT or GC outlier cannot set it. */
    private fun medianNanos(times: Int = 7, block: () -> Unit): Long {
        repeat(3) { block() } // warm up: the first calls measure the JIT, not the code
        val samples = LongArray(times) {
            val mark = TimeSource.Monotonic.markNow()
            block()
            mark.elapsedNow().inWholeNanoseconds
        }
        samples.sort()
        return samples[times / 2]
    }

    private fun ms(nanos: Long) = (nanos / 1_000.0).toLong() / 1000.0

    // ---- the regression guard ------------------------------------------------------------------

    /**
     * CLAUDE.md: *anything recomputed on every `nowMillis` tick must be bounded by the visible window, never
     * O(total history)*. The display asks its questions over `[displayFloor, now]`, and `displayFloor` is
     * pinned to the visible span — so lengthening the account's stored history behind that span must not
     * make the same visible week cost more.
     *
     * Asserted as a ratio against the SAME derivation on the SAME machine, so it needs no wall-clock budget
     * and cannot fail merely because the runner is slow. The factor is deliberately loose (4x for 52x the
     * history): the point is to catch a derivation that became linear in history, which shows up as a
     * factor in the tens, not to police a constant.
     */
    @Test
    fun display_derivation_cost_follows_the_visible_window_not_total_history() {
        val (state, _) = account(6)
        val visibleStart = now - 3 * day
        val visibleEnd = now + 4 * day
        // The display floor the app itself computes: the visible span, or 168h back, whichever reaches
        // further. Both runs therefore ask about the same window; only what is STORED behind it differs.
        val floor = minOf(now - SchedulerDomain.SCHEDULE_HORIZON_MILLIS, visibleStart)

        fun derive(sessionDays: Int): Long {
            val rows = sessions(sessionDays)
            return medianNanos {
                val gaps = SchedulerDomain.derivePauses(rows, floor, now)
                val active = SchedulerDomain.subtractRegions(listOf(TaskTimeRange(floor, now)), gaps)
                SchedulerDomain.derivedInactivityBands(active, floor, now)
                SchedulerDomain.screenBreakPanels(
                    screenBreaks = state.screenBreaks,
                    nowMillis = now,
                    horizonMillis = visibleEnd,
                    basePeriods = emptyList(),
                )
            }
        }

        val oneWeek = derive(7)
        val oneYear = derive(365)
        val ratio = oneYear.toDouble() / oneWeek.coerceAtLeast(1L)
        println(
            "display derivation: 7d history ${ms(oneWeek)}ms, 365d history ${ms(oneYear)}ms " +
                "(ratio ${(ratio * 100).toLong() / 100.0} over 52x the sessions)",
        )
        assertTrue(
            ratio < 4.0,
            "the display derivation grew ${ratio}x when only the STORED history behind the visible window " +
                "did — it is now O(total history). See CLAUDE.md and docs/invariants/display-hot-path.md.",
        )
    }

    // ---- the measuring tool --------------------------------------------------------------------

    /**
     * Times each heavy derivation on its own and prints the table. **Asserts nothing about duration** — a
     * wall-clock budget on an unknown runner is a flaky test, and the value here is the comparison between
     * two runs of this same test, before and after a change.
     */
    @Test
    fun benchmark_report() {
        Perf.enabled = true
        Perf.reset()

        val (state, _) = account(12)
        val visibleStart = now - 3 * day
        val visibleEnd = now + 4 * day
        val floor = minOf(now - SchedulerDomain.SCHEDULE_HORIZON_MILLIS, visibleStart)
        val sessionRows = sessions(120)
        val tasks = SchedulerDomain.planTasksOf(state, now)

        val rows = mutableListOf<Pair<String, Long>>()

        // The plan itself — the one derivation that is allowed to be expensive, because CLAUDE.md says it
        // runs on a rule change and never on a tick. If it shows up in the overlay's per-second ranking at
        // all, that rule has been broken somewhere.
        rows += "fillSchedule 24h" to medianNanos { SchedulerDomain.fillSchedule(state, now, tz, horizonMillis = now + day) }
        rows += "fillSchedule 168h" to
            medianNanos(times = 5) {
                SchedulerDomain.fillSchedule(state, now, tz, horizonMillis = now + 7 * day)
            }
        // What every plan reduction adds beside the plan: the plan for the other mode class, found fresh (a re-plan).
        // An extension only fills its tail, as the plan's own stages do.
        // At a screen the other class is the covered one, which only tasks resilient to "no on-screen task" may run in;
        // covered, it is the at-screen plan — a whole plan's cost.
        for ((label, mode) in listOf("covered" to DynamicPeriods.MODE_AT_SCREEN, "at-screen" to DynamicPeriods.MODE_AWAY)) {
            rows += "otherModePlan $label 168h" to
                medianNanos(times = 5) {
                    SchedulerDomain.otherModePlan(
                        state, now, liveRest = null, noScreenEvidence = emptyList(), frozenBreaks = null,
                        tpMode = mode, horizonMillis = now + 7 * day,
                        lineModeUntilMillis = now + 7 * day, timeZone = tz,
                    )
                }
        }

        // Everything below runs on the DISPLAY path, i.e. once per resample of the now-line and once per
        // recomposition of App. These are the numbers that multiply by the recomposition rate.
        rows += "derivePauses 120d" to medianNanos { SchedulerDomain.derivePauses(sessionRows, floor, now) }
        rows += "planTasksOf" to medianNanos { SchedulerDomain.planTasksOf(state, now) }
        rows += "screenBreakPanels now->7d" to
            medianNanos {
                SchedulerDomain.screenBreakPanels(
                    screenBreaks = state.screenBreaks,
                    nowMillis = now,
                    horizonMillis = visibleEnd,
                    basePeriods = emptyList(),
                )
            }
        rows += "sleepPanels 7d" to
            medianNanos { SchedulerDomain.sleepPanels(state.sleep, now, visibleEnd, tz) }
        rows += "absoluteTaskPriorities" to medianNanos { SchedulerDomain.absoluteTaskPriorities(state) }

        // The calendar's own per-column cost. Asked three times per DayColumn and cached on the block list
        // there, so what this measures is the price of a day whose blocks actually CHANGED — the number that
        // matters is how it grows with the count, not the constant.
        rows += "overlapLayout 18 blocks" to medianNanos(times = 15) { overlapLayout(dayBlocks(18)) }
        rows += "overlapLayout 100 blocks" to medianNanos(times = 15) { overlapLayout(dayBlocks(100)) }
        rows += "weightHandles 18 blocks" to medianNanos(times = 15) { weightHandles(dayBlocks(18)) }

        // The persistence path — what one save costs, which is what the typing debounce pays.
        val filled = state.copy(panels = SchedulerDomain.fillSchedule(state, now, tz, horizonMillis = now + 7 * day))
        rows += "encodeSnapshot" to medianNanos { SchedulerStateCodec.encodeSnapshot(filled) }
        rows += "syncFingerprint" to medianNanos { SchedulerStateCodec.syncFingerprint(filled) }

        println()
        println("== headless derivation benchmark (median of repeated runs) ==")
        println("account: ${state.tasks.size} tasks, ${filled.panels.size} panels, ${sessionRows.size} sessions")
        rows.sortedByDescending { it.second }.forEach { (name, nanos) ->
            println("  " + name.padEnd(32) + ms(nanos).toString() + " ms")
        }

        // The recorder's own report, so the section names here match the ones the overlay shows in the app.
        val snapshot = Perf.snapshot()
        println()
        println(PerfReport.format("recorder view", snapshot, snapshot))
        Perf.enabled = false
    }
}
