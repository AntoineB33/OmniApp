package org.example.project

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TimelineLevels
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * `docs/scheduler_requirements.md` § *frozen past* — *"The schedule at t < now line never changes as now line
 * increases, with only two exceptions"* — with `docs/scheduler_input_requirements.md` § tm_levels, **lived through at
 * random behind the line**: periods laid over worked time, dragged, resized, removed, the edits undone, each checked
 * slot by slot.
 *
 * What is asked, of every slot after every edit:
 *  - **no work is ever lost**: a slot a task worked is still that task's — in its record, or hidden on the level
 *    under a period — whatever was laid over it and wherever that went since;
 *  - **the timeline is consistent**: no work stands in the record under a period of the user's that refuses its task,
 *    and no work is hidden where no such period stands;
 *  - **a slot is one task's at most**: what the hole rule gives back never lands on another task's work.
 */
class RecordLevelsFuzzTest {
    private val MIN = 60_000L
    private val SLOT = 5 * MIN
    private val SLOTS = 72
    private val DAY = 24 * 60 * MIN
    private val pins = PanelPins(existence = true)
    private val kinds = listOf(PeriodKinds.NO_SCREEN, PeriodKinds.INACTIVITY, PeriodKinds.NO_COMPUTER_UNLOCKED)

    private class FixedClock(val now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    private fun live(seed: Int, steps: Int) {
        val random = Random(seed)
        val t0 = (1_800_000_000_000L / DAY) * DAY
        val now = t0 + SLOTS * SLOT + 60 * MIN // the whole stretch is behind the line
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(now)
        try {
            var state = SchedulerState.empty()
            listOf("A", "B", "C").forEachIndexed { i, title ->
                val row = state.lists[state.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
                state = SchedulerReducer.reduce(state, SchedulerIntent.SetCellTitle(row, title))
            }
            val ids = listOf("A", "B", "C").map { title -> state.tasks.values.first { it.title == title }.id }
            // What was worked: runs of A, B (at a screen) and C (away from one), with idle stretches between.
            val worked = HashMap<Int, TaskId>()
            var slot = 0
            while (slot < SLOTS) {
                val length = 1 + random.nextInt(8)
                val who = if (random.nextInt(4) == 0) null else ids.random(random)
                if (who != null) for (i in slot until minOf(SLOTS, slot + length)) worked[i] = who
                slot += length
            }
            fun runsOf(id: TaskId): List<TaskTimeRange> {
                val out = ArrayList<TaskTimeRange>()
                var from = -1
                for (i in 0..SLOTS) {
                    val mine = i < SLOTS && worked[i] == id
                    if (mine && from < 0) from = i
                    if (!mine && from >= 0) {
                        out += TaskTimeRange(t0 + from * SLOT, t0 + i * SLOT)
                        from = -1
                    }
                }
                return out
            }
            val c = ids[2]
            state = state.copy(
                tasks = state.tasks.mapValues { (id, task) ->
                    task.copy(
                        record = runsOf(id),
                        resilience = if (id == c) task.resilience + (PeriodKinds.NO_SCREEN to 1.0) else task.resilience,
                    )
                },
                sleep = SleepSchedule(sleepDurationMinutes = 0),
                automaticSchedule = false,
                focusedWindow = HistoryWindow.Calendar,
            )
            val log = ArrayList<String>()
            fun told() = "seed $seed, after:\n  " + log.joinToString("\n  ") +
                "\nperiods: " + state.panels.joinToString { "${it.restrictiveKind}[${(it.startEpochMillis - t0) / SLOT},${(it.endEpochMillis - t0) / SLOT})#${it.id.substringAfter('/')}" } +
                "\nworked: " + (0 until SLOTS).joinToString("") { worked[it]?.value?.takeLast(1) ?: "." } +
                "\nrecord: " + (0 until SLOTS).joinToString("") { i -> state.tasks.values.firstOrNull { t -> t.record.any { it.startEpochMillis <= t0 + i * SLOT && t0 + i * SLOT < it.endEpochMillis } }?.id?.value?.takeLast(1) ?: "." } +
                "\nhidden: " + state.hiddenPanels.joinToString { "${if (it.record) "work" else it.panel.restrictiveKind}:${it.panel.taskId?.value}[${(it.panel.startEpochMillis - t0) / SLOT},${(it.panel.endEpochMillis - t0) / SLOT})" }
            fun check() {
                val periods = state.panels.filter { it.isRestrictivePeriod && SchedulerDomain.isUserPlaced(it) }
                for (i in 0 until SLOTS) {
                    val at = t0 + i * SLOT
                    fun TaskTimeRange.covers() = startEpochMillis <= at && at < endEpochMillis
                    val inRecord = state.tasks.values.filter { task -> task.record.any { it.covers() } }.map { it.id }
                    val hiddenWork = state.hiddenPanels.filter { it.record && it.panel.startEpochMillis <= at && at < it.panel.endEpochMillis }.mapNotNull { it.panel.taskId }
                    val mine = worked[i]
                    if (mine != null) assertTrue(mine in inRecord || mine in hiddenWork, "no work is ever lost — slot $i\n${told()}")
                    assertTrue((inRecord + hiddenWork).size <= 1, "a slot is one task's at most — slot $i: $inRecord $hiddenWork\n${told()}")
                    val standing = periods.filter { it.startEpochMillis <= at && at < it.endEpochMillis }.map { it.restrictiveKind }
                    for (id in inRecord) {
                        val refusing = standing.filter { state.tasks.getValue(id).resilienceFor(it) <= 0.0 }
                        assertEquals(emptyList(), refusing, "no work in the record under a period that refuses its task — slot $i\n${told()}")
                    }
                    for (id in hiddenWork) {
                        assertTrue(standing.any { state.tasks.getValue(id).resilienceFor(it) <= 0.0 }, "work is hidden only under a period that refuses it — slot $i\n${told()}")
                    }
                }
                // A task's record is in order and never says a stretch twice.
                for (task in state.tasks.values) {
                    val sorted = task.record.sortedBy { it.startEpochMillis }
                    assertTrue(sorted.zipWithNext().all { (a, b) -> a.endEpochMillis <= b.startEpochMillis }, "a record never overlaps itself\n${told()}")
                }
            }
            check()
            var undoable = 0
            repeat(steps) {
                val before = state
                val periods = state.panels.filter { it.isRestrictivePeriod && TimelineLevels.participates(it) }
                val roll = random.nextInt(100)
                fun span(): Pair<Int, Int> = random.nextInt(SLOTS - 1).let { it to minOf(SLOTS, it + 1 + random.nextInt(14)) }
                when {
                    roll < 30 || periods.isEmpty() -> {
                        val (from, to) = span()
                        val kind = kinds.random(random)
                        log += "add $kind [$from,$to)"
                        state = SchedulerReducer.reduce(state, SchedulerIntent.AddRestrictivePeriod(kind, t0 + from * SLOT, t0 + to * SLOT))
                    }
                    roll < 70 -> {
                        val panel = periods.random(random)
                        val (from, to) = span()
                        log += "move ${panel.restrictiveKind} ${panel.id} -> [$from,$to)"
                        state = SchedulerReducer.reduce(state, SchedulerIntent.UpdateTaskPanel(panel.id, null, panel.title, t0 + from * SLOT, t0 + to * SLOT, pins))
                    }
                    roll < 88 -> {
                        val panel = periods.random(random)
                        log += "remove ${panel.restrictiveKind} ${panel.id}"
                        state = SchedulerReducer.reduce(state, SchedulerIntent.RemoveTaskPanel(panel.id))
                    }
                    else -> if (undoable > 0) {
                        log += "undo"
                        state = SchedulerReducer.reduce(state, SchedulerIntent.Undo)
                        undoable--
                        check()
                        return@repeat
                    }
                }
                if (state.panels != before.panels || state.hiddenPanels != before.hiddenPanels) undoable++
                check()
            }
            // With every period gone, every slot that was worked is in its task's record again.
            while (true) {
                val period = state.panels.firstOrNull { it.isRestrictivePeriod && SchedulerDomain.isUserPlaced(it) } ?: break
                log += "remove ${period.restrictiveKind} ${period.id} (clearing)"
                state = SchedulerReducer.reduce(state, SchedulerIntent.RemoveTaskPanel(period.id))
            }
            assertTrue(state.hiddenPanels.none { it.record }, "nothing stays hidden under nothing\n${told()}")
            for ((i, id) in worked) {
                val at = t0 + i * SLOT
                assertTrue(state.tasks.getValue(id).record.any { it.startEpochMillis <= at && at < it.endEpochMillis }, "slot $i is back in the record\n${told()}")
            }
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    @Test
    fun no_period_laid_over_the_past_ever_loses_work() {
        for (seed in 1..120) live(seed, steps = 40)
    }

    @Test
    fun a_long_session_of_edits_behind_the_line_loses_no_work() {
        for (seed in 2001..2005) live(seed, steps = 200)
    }
}
