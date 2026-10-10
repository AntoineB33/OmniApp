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
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * `docs/scheduler_input_requirements.md` § tm_levels, **lived through at random**: thousands of calendar edits — a
 * block laid, dragged, resized, removed, an edit undone — each checked, slot by slot, against a MODEL of the
 * document written here from its sentences alone, never from the reducer's code:
 *
 * *"When a blue/orange outlined block is positioned at t_r, then what was there before is added at t_r to the lowest
 * tm_level where nothing is at t_r. What is in the timeline is the result of the overlap of those tm_levels, applying
 * them from top to bottom, ignoring those that are incompatible with the higher tm_levels."*
 *
 * The model is a stack of what the user placed, the last positioned on top. At an instant the timeline is that stack
 * read from the top: a period is ignored under a period of its own kind and where it — with the periods the account's
 * rules bring with it — would refuse a task above it; a task panel is ignored where a period above refuses its task
 * and under another task's panel that was not positioned to share the width. What the worked examples of
 * [TimelineLevelsTest] show on five cases, this asks of every case the seeds reach — so that a combination nobody
 * thought of is met here rather than on the user's calendar.
 *
 * Two invariants at every slot, after every edit:
 *  - **the timeline is the overlap** — what is shown is exactly what the model says is shown;
 *  - **nothing is destroyed** — what is shown or hidden is exactly what the user placed there and did not remove.
 */
class TimelineLevelsFuzzTest {
    private val MIN = 60_000L
    private val SLOT = 5 * MIN
    private val SLOTS = 72 // six hours
    private val DAY = 24 * 60 * MIN
    private val pins = PanelPins(existence = true)
    private val kinds = listOf(PeriodKinds.NO_SCREEN, PeriodKinds.INACTIVITY, PeriodKinds.NO_COMPUTER_UNLOCKED, PeriodKinds.NO_PHONE_UNLOCKED)

    private class FixedClock(val now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    /** One thing the user placed: what it is, the slots it stands on, and whether it shares the width. */
    private data class Placed(val what: String, val slots: Set<Int>, val share: Boolean)

    private fun <T> withClock(now: Long, body: () -> T): T {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(now)
        try {
            return body()
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    /** A at a screen, B at a screen, C away from one (it may run under "no screen"); no night, no automatic schedule. */
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

    private fun whatOf(panel: TaskPanel): String = panel.taskId?.let { "task:" + it.value } ?: panel.restrictiveKind

    private fun slotsOf(t0: Long, panel: TaskPanel): Set<Int> =
        (0 until SLOTS).filterTo(HashSet()) { panel.startEpochMillis <= t0 + it * SLOT && t0 + it * SLOT < panel.endEpochMillis }

    /** The model's reading of one slot: the indices of [stack] shown there, from the top down. */
    private fun shownAt(state: SchedulerState, stack: List<Placed>, slot: Int, closures: MutableMap<Set<String>, Set<String>>): List<Int> {
        val shown = ArrayList<Int>()
        val periods = HashSet<String>()
        fun closure(of: Set<String>): Set<String> =
            closures.getOrPut(of.toSet()) {
                state.periodKindConfig.closeRegions(of.associateWith { listOf(TaskTimeRange(0, 1)) }).filterValues { it.isNotEmpty() }.keys
            }
        fun refused(task: String, by: Set<String>) =
            closure(by).any { SchedulerDomain.periodRefuses(state.tasks, TaskId(task.removePrefix("task:")), it) }
        for (i in stack.indices.reversed()) {
            val entry = stack[i]
            if (slot !in entry.slots) continue
            val above = shown.map { stack[it] }
            val ok =
                if (entry.what.startsWith("task:")) {
                    !refused(entry.what, periods) &&
                        above.none { it.what.startsWith("task:") && (it.what == entry.what || !it.share) }
                } else {
                    entry.what !in periods &&
                        above.none { it.what.startsWith("task:") && refused(it.what, periods + entry.what) }
                }
            if (ok) {
                shown += i
                if (!entry.what.startsWith("task:")) periods += entry.what
            }
        }
        return shown
    }

    private fun check(state: SchedulerState, stack: List<Placed>, t0: Long, closures: MutableMap<Set<String>, Set<String>>, told: () -> String) {
        val shown = state.panels.filter(TimelineLevels::participates)
        for (slot in 0 until SLOTS) {
            val at = t0 + slot * SLOT
            fun covers(p: TaskPanel) = p.startEpochMillis <= at && at < p.endEpochMillis
            val drawn = shown.filter(::covers).map(::whatOf).sorted()
            val expected = shownAt(state, stack, slot, closures).map { stack[it].what }.distinct().sorted()
            assertEquals(expected, drawn.distinct(), "the timeline is the overlap of the levels — slot $slot\n${told()}")
            // No period is said twice at one instant: the lower of two of a kind is hidden there.
            assertEquals(drawn.filterNot { it.startsWith("task:") }.distinct(), drawn.filterNot { it.startsWith("task:") }, "slot $slot\n${told()}")
            val kept = (drawn + state.hiddenPanels.filter { !it.record && covers(it.panel) }.map { whatOf(it.panel) }).toSet()
            val placed = stack.filter { slot in it.slots }.mapTo(HashSet()) { it.what }
            assertEquals(placed, kept, "nothing the user placed is destroyed, nothing is invented — slot $slot\n${told()}")
        }
        // A block is never both shown and hidden over one stretch, and no piece is shorter than a block can be.
        for (piece in state.hiddenPanels) {
            assertTrue(piece.panel.endEpochMillis - piece.panel.startEpochMillis >= SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS, told())
        }
        assertEquals(state.panels.map { it.id }.toSet().size, state.panels.size, "one id, one panel\n${told()}")
        assertTrue(state.hiddenPanels.none { h -> state.panels.any { it.id == h.id } }, "a piece is shown or hidden, not both\n${told()}")
    }

    private fun live(seed: Int, steps: Int) {
        val random = Random(seed)
        // Ahead of the line, so nothing here is the past: the levels alone are what moves.
        val t0 = (1_800_000_000_000L / DAY) * DAY
        withClock(t0 - 10 * DAY) {
            var state = account()
            val tasks = listOf("A", "B", "C").associateWith { title -> state.tasks.values.first { it.title == title }.id }
            var stack = listOf<Placed>()
            val past = ArrayList<Pair<SchedulerState, List<Placed>>>()
            val closures = HashMap<Set<String>, Set<String>>()
            val log = ArrayList<String>()
            fun told() = "seed $seed, after:\n  " + log.joinToString("\n  ") +
                "\nshown: " + state.panels.filter(TimelineLevels::participates).joinToString { "${whatOf(it)}[${(it.startEpochMillis - t0) / SLOT},${(it.endEpochMillis - t0) / SLOT})L${it.tmLevel}${if (it.tmShare) "s" else ""}#${it.id.substringAfter('/')}" } +
                "\nhidden: " + state.hiddenPanels.joinToString { "${whatOf(it.panel)}[${(it.panel.startEpochMillis - t0) / SLOT},${(it.panel.endEpochMillis - t0) / SLOT})L${it.panel.tmLevel}" }
            fun span(): Pair<Int, Int> {
                val from = random.nextInt(SLOTS - 1)
                return from to minOf(SLOTS, from + 1 + random.nextInt(18))
            }
            fun without(stack: List<Placed>, what: String, slots: Set<Int>): List<Placed> {
                // What a hand takes away is what is SHOWN of it there; what is hidden under it stays on its level.
                val next = stack.toMutableList()
                for (slot in slots) {
                    for (i in shownAt(state, stack, slot, closures)) {
                        if (stack[i].what == what) next[i] = next[i].copy(slots = next[i].slots - slot)
                    }
                }
                return next.filter { it.slots.isNotEmpty() }
            }
            repeat(steps) {
                val before = state
                val shown = state.panels.filter(TimelineLevels::participates)
                val roll = random.nextInt(100)
                var nextStack = stack
                when {
                    roll < 22 || shown.isEmpty() -> {
                        val (from, to) = span()
                        if (random.nextBoolean()) {
                            val kind = kinds.random(random)
                            log += "add $kind [$from,$to)"
                            state = SchedulerReducer.reduce(state, SchedulerIntent.AddRestrictivePeriod(kind, t0 + from * SLOT, t0 + to * SLOT))
                            nextStack = stack + Placed(kind, (from until to).toSet(), false)
                        } else {
                            val title = tasks.keys.random(random)
                            log += "add task $title [$from,$to)"
                            state = SchedulerReducer.reduce(state, SchedulerIntent.AddTaskPanel(tasks.getValue(title), title, t0 + from * SLOT, t0 + to * SLOT, pins))
                            nextStack = stack + Placed("task:" + tasks.getValue(title).value, (from until to).toSet(), false)
                        }
                    }
                    roll < 72 -> {
                        val panel = shown.random(random)
                        val old = slotsOf(t0, panel)
                        val (from, to) =
                            when (random.nextInt(3)) {
                                // Dragged elsewhere, keeping its length.
                                0 -> random.nextInt(SLOTS - old.size + 1).let { it to it + old.size }
                                // An edge dragged.
                                1 -> minOf(old.min(), random.nextInt(SLOTS - 1)).let { it to maxOf(it + 1, old.max() + 1) }
                                else -> span()
                            }
                        val share = panel.taskId != null && random.nextInt(4) == 0
                        // Put back exactly where and as it was, a block has not been positioned: nothing to ask.
                        if (from == old.min() && to == old.max() + 1 && share == panel.tmShare) return@repeat
                        log += "move ${whatOf(panel)} ${panel.id} [${old.min()},${old.max() + 1}) -> [$from,$to)" + if (share) " sharing" else ""
                        state =
                            SchedulerReducer.reduce(
                                state,
                                SchedulerIntent.UpdateTaskPanel(panel.id, panel.taskId, panel.title, t0 + from * SLOT, t0 + to * SLOT, pins, allowOverlap = share),
                            )
                        nextStack = without(stack, whatOf(panel), old) + Placed(whatOf(panel), (from until to).toSet(), share)
                    }
                    roll < 88 -> {
                        val panel = shown.random(random)
                        log += "remove ${whatOf(panel)} ${panel.id}"
                        state = SchedulerReducer.reduce(state, SchedulerIntent.RemoveTaskPanel(panel.id))
                        nextStack = without(stack, whatOf(panel), slotsOf(t0, panel))
                    }
                    else -> {
                        val back = past.removeLastOrNull()
                        if (back != null) {
                            log += "undo"
                            state = SchedulerReducer.reduce(state, SchedulerIntent.Undo)
                            nextStack = back.second
                            closures.clear()
                            stack = nextStack
                            check(state, stack, t0, closures, ::told)
                            return@repeat
                        }
                    }
                }
                // An edit that changes nothing is no History Unit: nothing to walk back to.
                if (state.panels != before.panels || state.hiddenPanels != before.hiddenPanels) past += before to stack
                stack = nextStack
                check(state, stack, t0, closures, ::told)
                // Settled: asking again of the whole day moves nothing.
                var fresh = 0
                val again =
                    TimelineLevels.settle(
                        state.panels, state.hiddenPanels, state.tasks, state.periodKindConfig,
                        listOf(TaskTimeRange(t0, t0 + SLOTS * SLOT)), allocate = { "fresh/${fresh++}" },
                    )
                assertTrue(again.panels == state.panels && again.hidden == state.hiddenPanels, "settling twice changes nothing\n${told()}")
            }
        }
    }

    @Test
    fun the_timeline_is_the_overlap_of_the_levels_whatever_the_user_does() {
        for (seed in 1..250) live(seed, steps = 45)
    }

    @Test
    fun a_long_session_of_edits_stays_the_overlap_of_the_levels() {
        for (seed in 1001..1006) live(seed, steps = 250)
    }
}
