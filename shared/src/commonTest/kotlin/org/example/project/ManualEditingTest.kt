package org.example.project

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PanelRepeats
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TimelineLevels
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * `docs/scheduler_requirements.md` § *Manual editing*:
 *
 * *"If the user adds an element, then removes it, the state of the line is the same as before the addition. If the
 * user moves some elements, then moves them back to their original place, the state of the line is the same as before
 * the move."*
 *
 * The model is `line_manager.py`'s: the elements are stacked in the order they were placed (a z-index fixed at
 * creation, [TimelineLevels.placed]), a block moves whole ([TimelineLevels.carried]), and the rules are read off that
 * stack. Asked here of random calendars, at random, and then of the three things a move leaves behind it that are not
 * the stack: the hole rule's fill behind the line, an occurrence taken out of a repeating period, a night taken out of
 * the Sleep schedule.
 */
class ManualEditingTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val SLOT = 5 * MIN
    private val SLOTS = 72
    private val pins = PanelPins(existence = true)
    private val kinds = listOf(PeriodKinds.NO_SCREEN, PeriodKinds.INACTIVITY, PeriodKinds.NO_COMPUTER_UNLOCKED, PeriodKinds.NO_PHONE_UNLOCKED)

    private class FixedClock(val now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    private fun <T> withClock(now: Long, body: () -> T): T {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(now)
        try {
            return body()
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    /** A and B at a screen, C away from one; no night, no automatic schedule. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("A", "B", "C").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        val c = s.tasks.values.first { it.title == "C" }
        return s.copy(
            tasks = s.tasks + (c.id to c.copy(resilience = c.resilience + (PeriodKinds.NO_SCREEN to 1.0))),
            sleep = SleepSchedule(sleepDurationMinutes = 0),
            automaticSchedule = false,
            focusedWindow = HistoryWindow.Calendar,
        )
    }

    private fun SchedulerState.task(title: String): TaskId = tasks.values.first { it.title == title }.id

    private fun whatOf(panel: TaskPanel): String = panel.taskId?.let { "task:" + it.value } ?: panel.restrictiveKind

    /**
     * **The state of the line**, read the way `line_manager.py` reads it — what stands at every instant and on which
     * level, shown or hidden, and the work in the records — and not by the ids the pieces happen to carry, which an
     * edit is free to hand out anew.
     */
    private fun line(s: SchedulerState): List<Any?> {
        fun piece(p: TaskPanel, shown: Boolean) = listOf(whatOf(p), p.startEpochMillis, p.endEpochMillis, p.tmLevel, p.tmShare && p.taskId != null, shown)
        fun joined(pieces: List<List<Any>>): List<List<Any>> {
            // Two pieces of one thing on one level that touch are one stretch of it, however they are cut.
            val out = ArrayList<MutableList<Any>>()
            for (p in pieces.sortedWith(compareBy({ it[0] as String }, { it[3] as Int }, { it[5] as Boolean }, { it[4] as Boolean }, { it[1] as Long }))) {
                val last = out.lastOrNull()
                if (last != null && last[0] == p[0] && last[3] == p[3] && last[4] == p[4] && last[5] == p[5] && (last[2] as Long) >= (p[1] as Long)) {
                    last[2] = maxOf(last[2] as Long, p[2] as Long)
                } else {
                    out += p.toMutableList()
                }
            }
            return out
        }
        val blocks = joined(s.panels.filter(TimelineLevels::participates).map { piece(it, true) } + s.hiddenPanels.filter { !it.record }.map { piece(it.panel, false) })
        val others = s.panels.filterNot(TimelineLevels::participates).map { listOf(whatOf(it), it.startEpochMillis, it.endEpochMillis, it.repeat?.skipped) }.sortedBy { it.toString() }
        val records = s.tasks.values.sortedBy { it.id.value }.map { it.id.value to SchedulerDomain.mergeOccupied(it.record) }
        val hiddenWork = s.hiddenPanels.filter { it.record }.map { Triple(it.panel.taskId, it.panel.startEpochMillis, it.panel.endEpochMillis) }.sortedBy { it.toString() }
        return listOf(blocks, others, records, hiddenWork, s.sleep?.skippedWakeEpochDays, s.sleep?.skippedBeforeBedEpochDays)
    }

    /** A random calendar of blocks laid, moved, resized and removed, ahead of the line. */
    private fun randomCalendar(random: Random, t0: Long, steps: Int): SchedulerState {
        var s = account()
        val tasks = listOf("A", "B", "C").map { s.task(it) to it }
        repeat(steps) {
            val shown = s.panels.filter(TimelineLevels::participates)
            val from = random.nextInt(SLOTS - 1)
            val to = minOf(SLOTS, from + 1 + random.nextInt(18))
            val roll = random.nextInt(100)
            s =
                when {
                    roll < 45 || shown.isEmpty() ->
                        if (random.nextBoolean()) {
                            SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(kinds.random(random), t0 + from * SLOT, t0 + to * SLOT))
                        } else {
                            val (id, title) = tasks.random(random)
                            SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(id, title, t0 + from * SLOT, t0 + to * SLOT, pins))
                        }
                    roll < 85 -> {
                        val p = shown.random(random)
                        val share = p.taskId != null && random.nextInt(4) == 0
                        SchedulerReducer.reduce(s, SchedulerIntent.UpdateTaskPanel(p.id, p.taskId, p.title, t0 + from * SLOT, t0 + to * SLOT, pins, allowOverlap = share))
                    }
                    else -> SchedulerReducer.reduce(s, SchedulerIntent.RemoveTaskPanel(shown.random(random).id))
                }
        }
        return s
    }

    private fun live(seed: Int) {
        val random = Random(seed)
        val t0 = (1_800_000_000_000L / DAY) * DAY
        withClock(t0 - 10 * DAY) {
            val start = randomCalendar(random, t0, 4 + random.nextInt(25))
            val before = line(start)
            fun told(what: String) = "seed $seed: $what\nbefore: $before"

            // Added, then removed: the line is what it was.
            val from = random.nextInt(SLOTS - 1)
            val to = minOf(SLOTS, from + 1 + random.nextInt(24))
            val added =
                if (random.nextBoolean()) {
                    SchedulerReducer.reduce(start, SchedulerIntent.AddRestrictivePeriod(kinds.random(random), t0 + from * SLOT, t0 + to * SLOT))
                } else {
                    val title = listOf("A", "B", "C").random(random)
                    SchedulerReducer.reduce(start, SchedulerIntent.AddTaskPanel(start.task(title), title, t0 + from * SLOT, t0 + to * SLOT, pins))
                }
            val newest = added.panels.filter(TimelineLevels::participates).maxBy { it.tmLevel }
            assertTrue(newest.tmOrigin.isBlank() && added.panels.none { it.tmOrigin == newest.id }, told("a block just placed is whole and on top"))
            val removed = SchedulerReducer.reduce(added, SchedulerIntent.RemoveTaskPanel(newest.id))
            assertEquals(before, line(removed), told("add [$from,$to) then remove"))

            // Moved, then moved back — one block, or two, put back in either order: the line is what it was.
            val shown = start.panels.filter(TimelineLevels::participates)
            if (shown.isEmpty()) return@withClock
            val moving = shown.shuffled(random).distinctBy { it.tmBlockId }.take(1 + random.nextInt(2))
            var s = start
            // A hand drags what it sees of a block — any of its pieces shown — by as much, and back by as much.
            fun drag(block: String, shift: Long): Boolean {
                val piece = s.panels.firstOrNull { it.tmBlockId == block && TimelineLevels.participates(it) } ?: return false
                s = SchedulerReducer.reduce(
                    s,
                    SchedulerIntent.UpdateTaskPanel(piece.id, piece.taskId, piece.title, piece.startEpochMillis + shift, piece.endEpochMillis + shift, pins, allowOverlap = piece.tmShare),
                )
                return true
            }
            val back = ArrayList<Pair<String, Long>>()
            for (p in moving) {
                val shift = (random.nextInt(2 * SLOTS) - SLOTS) * SLOT
                if (shift != 0L && drag(p.tmBlockId, shift)) back += p.tmBlockId to -shift
            }
            if (random.nextBoolean()) back.reverse()
            // (A block wholly hidden by the other one's move cannot be taken by the hand: nothing to ask of it.)
            for ((block, shift) in back) if (!drag(block, shift)) return@withClock
            assertEquals(before, line(s), told("moved ${moving.map { it.tmBlockId }} and back"))
        }
    }

    @Test
    fun added_then_removed_or_moved_then_moved_back_the_line_is_what_it_was() {
        for (seed in 1..400) live(seed)
    }

    @Test
    fun a_block_moved_away_and_back_is_where_it_was_in_the_stack() {
        // `line_manager.py`'s third proof: X then Y laid on one spot, Y on top; X moved away and back — Y stays on top.
        val t0 = (1_800_000_000_000L / DAY) * DAY
        withClock(t0 - DAY) {
            var s = account()
            s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(s.task("A"), "A", t0 + 10 * HOUR, t0 + 12 * HOUR, pins))
            s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(s.task("B"), "B", t0 + 11 * HOUR, t0 + 13 * HOUR, pins))
            val before = line(s)
            fun shownOf(state: SchedulerState, title: String) =
                state.panels.filter { it.taskId == state.task(title) }.map { (it.startEpochMillis - t0) / HOUR to (it.endEpochMillis - t0) / HOUR }
            assertEquals(listOf(10L to 11L), shownOf(s, "A"))
            // What the hand takes is what it sees of A, 10–11; the hour B hides moves with it: A is one block.
            val x = s.panels.single { it.taskId == s.task("A") }
            val away = SchedulerReducer.reduce(s, SchedulerIntent.UpdateTaskPanel(x.id, x.taskId, x.title, t0 + 20 * HOUR, t0 + 21 * HOUR, pins))
            assertEquals(listOf(20L to 22L), shownOf(away, "A"))
            assertEquals(listOf(11L to 13L), shownOf(away, "B"))
            assertTrue(away.hiddenPanels.isEmpty())
            val y = away.panels.single { it.taskId == s.task("A") }
            val back = SchedulerReducer.reduce(away, SchedulerIntent.UpdateTaskPanel(y.id, y.taskId, y.title, t0 + 10 * HOUR, t0 + 12 * HOUR, pins))
            assertEquals(listOf(10L to 11L), shownOf(back, "A"), "B, placed after A, is still above it")
            assertEquals(before, line(back))
            // And dragged onto B from elsewhere, A goes under it: the levels are the order the blocks were placed in.
            val onto = SchedulerReducer.reduce(away, SchedulerIntent.UpdateTaskPanel(y.id, y.taskId, y.title, t0 + 11 * HOUR, t0 + 13 * HOUR, pins))
            assertEquals(emptyList(), shownOf(onto, "A"))
            assertEquals(listOf(11L to 13L), shownOf(onto, "B"))
        }
    }

    @Test
    fun a_past_period_dragged_away_and_back_takes_back_what_the_hole_rule_put_there() {
        val t0 = 1_700_000_000_000L / DAY * DAY
        fun at(hour: Double) = t0 + (hour * HOUR).toLong()
        withClock(t0 + 2 * DAY) {
            val base = account()
            val a = base.task("A")
            // A worked on both sides of 10–11, and a "no screen" period there: the hole rule gives the stretch to A.
            val worked = base.copy(tasks = base.tasks + (a to base.tasks.getValue(a).copy(record = listOf(TaskTimeRange(at(8.0), at(10.0)), TaskTimeRange(at(11.0), at(12.0))))))
            val laid = SchedulerReducer.reduce(worked, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, at(10.0), at(11.0)))
            val period = laid.panels.single { it.isRestrictivePeriod }
            val before = line(laid)
            val away = SchedulerReducer.reduce(laid, SchedulerIntent.UpdateTaskPanel(period.id, null, period.title, at(14.0), at(15.0), pins))
            assertEquals(listOf(TaskTimeRange(at(8.0), at(12.0))), SchedulerDomain.mergeOccupied(away.tasks.getValue(a).record), "the hole rule gave 10–11 to A")
            assertEquals(listOf(TaskTimeRange(at(10.0), at(11.0))), away.panels.single { it.id == period.id }.tmLeft)
            val back = SchedulerReducer.reduce(away, SchedulerIntent.UpdateTaskPanel(period.id, null, period.title, at(10.0), at(11.0), pins))
            assertEquals(before, line(back))
            assertTrue(back.hiddenPanels.isEmpty(), "nothing the rule put there is left hidden under the period")
            assertTrue(back.panels.single { it.id == period.id }.tmLeft.isEmpty())

            // Edges that differ: the rule lays an Inactivity period; brought back, the period takes it back too.
            val lone = base.copy(tasks = base.tasks + (a to base.tasks.getValue(a).copy(record = listOf(TaskTimeRange(at(8.0), at(10.0))))))
            val laid2 = SchedulerReducer.reduce(lone, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, at(10.0), at(11.0)))
            val period2 = laid2.panels.single { it.isRestrictivePeriod }
            val away2 = SchedulerReducer.reduce(laid2, SchedulerIntent.UpdateTaskPanel(period2.id, null, period2.title, at(14.0), at(15.0), pins))
            assertEquals(1, away2.panels.count { it.tmFill })
            val back2 = SchedulerReducer.reduce(away2, SchedulerIntent.UpdateTaskPanel(period2.id, null, period2.title, at(10.0), at(11.0), pins))
            assertEquals(line(laid2), line(back2))
            assertTrue(back2.panels.none { it.tmFill } && back2.hiddenPanels.isEmpty())
            // Brought back halfway: half of the fill is taken back, half stays, and the period remembers that half.
            val half = SchedulerReducer.reduce(away2, SchedulerIntent.UpdateTaskPanel(period2.id, null, period2.title, at(10.5), at(11.5), pins))
            assertEquals(listOf(at(10.0) to at(10.5)), half.panels.filter { it.tmFill }.map { it.startEpochMillis to it.endEpochMillis })
            assertEquals(listOf(TaskTimeRange(at(10.0), at(10.5))), half.panels.single { it.id == period2.id }.tmLeft)
        }
    }

    @Test
    fun an_occurrence_moved_out_of_its_pattern_and_back_is_the_patterns_again() {
        val t0 = (1_800_000_000_000L / DAY) * DAY
        withClock(t0 - DAY) {
            val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
            val s = SchedulerReducer.reduce(account(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, t0 + 10 * HOUR, t0 + 11 * HOUR, repeatEveryDays = 1))
            val base = s.panels.single { it.repeat != null }
            val second = assertNotNull(PanelRepeats.occurrence(base, 2, tz))
            val before = line(s)
            val out = SchedulerReducer.reduce(s, SchedulerIntent.UpdateTaskPanel(second.id, null, second.title, second.startEpochMillis + HOUR, second.endEpochMillis + HOUR, pins))
            val own = out.panels.single { it.patternOrigin == second.id }
            assertEquals(setOf(2L), out.panels.single { it.id == base.id }.repeat?.skipped)
            val back = SchedulerReducer.reduce(out, SchedulerIntent.UpdateTaskPanel(own.id, null, own.title, second.startEpochMillis, second.endEpochMillis, pins))
            assertEquals(before, line(back))
            assertTrue(back.panels.none { it.patternOrigin.isNotBlank() })
            assertEquals(s.panels, back.panels)
            // Put back anywhere else, it stays a block of its own.
            val elsewhere = SchedulerReducer.reduce(out, SchedulerIntent.UpdateTaskPanel(own.id, null, own.title, second.startEpochMillis + 2 * HOUR, second.endEpochMillis + 2 * HOUR, pins))
            assertEquals(setOf(2L), elsewhere.panels.single { it.id == base.id }.repeat?.skipped)
        }
    }

    @Test
    fun a_night_dragged_out_of_the_sleep_schedule_and_back_is_the_schedules_again() {
        val t0 = (1_800_000_000_000L / DAY) * DAY
        withClock(t0 - 3 * DAY) {
            val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
            val s = account().copy(sleep = SleepSchedule(wakeMinutes = 7 * 60, goalWakeMinutes = 7 * 60, sleepDurationMinutes = 8 * 60))
            val night = SchedulerDomain.sleepPanels(s.sleep, t0, t0 + DAY, tz).first()
            val before = line(s)
            val out =
                SchedulerReducer.reduce(
                    s,
                    SchedulerIntent.PlaceDerivedPeriod(PeriodKinds.SLEEP, night.startEpochMillis, night.endEpochMillis, night.startEpochMillis + HOUR, night.endEpochMillis + HOUR),
                )
            val period = out.panels.single { it.restrictiveKind == PeriodKinds.SLEEP }
            assertEquals(night.id, period.patternOrigin)
            assertTrue(out.sleep!!.skippedWakeEpochDays.isNotEmpty())
            val back = SchedulerReducer.reduce(out, SchedulerIntent.UpdateTaskPanel(period.id, null, period.title, night.startEpochMillis, night.endEpochMillis, pins))
            assertEquals(before, line(back))
            assertTrue(back.panels.none { it.restrictiveKind == PeriodKinds.SLEEP && SchedulerDomain.isUserPlaced(it) })
            // Two units, as the move out made: Ctrl+Z walks the night back out, then the period back in.
            val once = SchedulerReducer.reduce(back, SchedulerIntent.Undo)
            assertEquals(out.sleep!!.skippedWakeEpochDays, once.sleep!!.skippedWakeEpochDays)
            val twice = SchedulerReducer.reduce(once, SchedulerIntent.Undo)
            assertEquals(line(out), line(twice))
        }
    }

    @Test
    fun what_a_block_left_is_persisted_and_an_older_payload_has_none() {
        val panel = TaskPanel("panel/1", null, "No screen", 0, HOUR, periodKind = PeriodKinds.NO_SCREEN, tmLeft = listOf(TaskTimeRange(-HOUR, 0)))
        val s = account().copy(panels = listOf(panel), nextPanelCounter = 2)
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(panel.tmLeft, decoded.panels.single().tmLeft)
        // The undo history keeps it too.
        val snapshot = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s)))
        assertEquals(panel.tmLeft, snapshot.panels.single().tmLeft)
        val older =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[],
             "panels":[{"id":"panel/0","title":"No screen","start":0,"end":3600000,"noScreen":true,"tmLevel":3}]}
            """.trimIndent()
        val old = assertNotNull(SchedulerStateCodec.decode(older))
        assertTrue(old.panels.single().tmLeft.isEmpty())
        assertEquals(3, old.panels.single().tmLevel)
    }
}
