package org.example.project.simulation

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.CalendarHorizonFixture
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * **One user's week, end to end** â€” the three things the Task Scheduler promises, held AT ONCE on one account lived
 * through the app's own reducer, the way the engine drives it:
 *
 *  1. **priorities balanced**: a nested tree (Job hunt 3 â†’ Applications / Interview prep; Spanish 1; Exercise 1) gets
 *     its absolute percentages (30 / 30 / 20 / 20 %) of the time anybody may run in;
 *  2. **eye-health rules**: the three production screen breaks ([SchedulerDomain.DEFAULT_SCREEN_BREAKS]) â€” never more
 *     than 20 min of screen without a 20 s look-away, an hour without a 5-min pose, two hours without 15 min away;
 *  3. **rules between tasks**: "Podcast run" is a set of tasks (`docs/scheduler_score.md` Â§ *Sets of tasks*) â€” doing
 *     it is Exercise at 100 % while it is Spanish at 20 %.
 *
 * The simulated user FOLLOWS the app: at a screen while the plan says so, away (mode 3, with the no-screen evidence a
 * locked device gives) for each pose the calendar draws, asleep every night. What is asserted is read off the RECORDS
 * the walk banked, never off the scheduler's own score ([ScheduleSimulation]'s rule).
 */
class UserJourneyTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val T0 = ScheduleSimulation.T0
    private val NIGHT = "night"
    private val NIGHT_FROM_HOUR = 23
    private val NIGHT_HOURS = 8
    private val COMMUTE = "commute"
    private val COMMUTE_HOURS = listOf(8)

    private fun r(s: SchedulerState, i: SchedulerIntent) = SchedulerReducer.reduce(s, i)

    @AfterTest
    fun restoreSeams() {
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
        SchedulerReducer.noScreenEvidence = { emptyList() }
        SchedulerReducer.liveRestGap = { null }
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.planAbandoned = { false }
        CalendarHorizonFixture.close()
    }

    private class Account(val state: SchedulerState, val ids: Map<String, TaskId>)

    private fun account(days: Int): Account {
        var s = SchedulerState.empty()
        fun free(list: CellListId) = s.lists[list]!!.cellIds.last()
        fun id(title: String): TaskId = s.tasks.values.first { it.title == title }.id
        s = r(s, SchedulerIntent.SetCellTitle(free(s.rootListId), "Job hunt"))
        s = r(s, SchedulerIntent.SetCellTitle(free(s.rootListId), "Spanish"))
        s = r(s, SchedulerIntent.SetCellTitle(free(s.rootListId), "Exercise"))
        val jobList = s.tasks[id("Job hunt")]!!.childListId!!
        s = r(s, SchedulerIntent.SetCellTitle(free(jobList), "Applications"))
        s = r(s, SchedulerIntent.SetCellTitle(free(jobList), "Interview prep"))
        s = r(s, SchedulerIntent.SetPriorityWeight(s.lists[s.rootListId]!!.cellIds[0], 0, 3.0))
        // Exercise and the podcast run need no screen: they may fill a pose.
        s = r(s, SchedulerIntent.SetTaskMinimumTime(id("Exercise"), 15))
        s = r(s, SchedulerIntent.SetTaskResilience(id("Exercise"), PeriodKinds.NO_SCREEN, 1.0))
        s = r(s, SchedulerIntent.CreatePathlessTask("Podcast run"))
        s = r(s, SchedulerIntent.SetTaskFulfilment(id("Podcast run"), id("Exercise"), 1.0))
        s = r(s, SchedulerIntent.SetTaskFulfilment(id("Podcast run"), id("Spanish"), 0.2))
        s = r(s, SchedulerIntent.SetTaskMinimumTime(id("Podcast run"), 15))
        s = r(s, SchedulerIntent.SetTaskResilience(id("Podcast run"), PeriodKinds.NO_SCREEN, 1.0))
        // Nights nobody may run in.
        s = r(s, SchedulerIntent.AddPeriodKind(NIGHT))
        for (t in s.tasks.values.toList()) s = r(s, SchedulerIntent.SetTaskResilience(t.id, NIGHT, 0.0))
        for (day in -1 until days) {
            val start = T0 + day * DAY + NIGHT_FROM_HOUR * HOUR
            s = r(s, SchedulerIntent.AddRestrictivePeriod(NIGHT, start, start + NIGHT_HOURS * HOUR))
        }
        // Commutes: an hour each morning, on foot — nothing but the podcast run fits there, so the set of tasks is what
        // serves Exercise and Spanish during them.
        s = r(s, SchedulerIntent.AddPeriodKind(COMMUTE))
        for (t in s.tasks.values.toList()) s = r(s, SchedulerIntent.SetTaskResilience(t.id, COMMUTE, 0.0))
        s = r(s, SchedulerIntent.SetTaskResilience(id("Podcast run"), COMMUTE, 1.0))
        for (day in 0 until days) {
            for (hour in COMMUTE_HOURS) {
                val start = T0 + day * DAY + hour * HOUR
                s = r(s, SchedulerIntent.AddRestrictivePeriod(COMMUTE, start, start + HOUR))
            }
        }
        s = s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val titles = listOf("Applications", "Interview prep", "Spanish", "Exercise", "Podcast run")
        return Account(s, titles.associateWith { id(it) })
    }

    private class Lived(val state: SchedulerState, val away: List<TaskTimeRange>, val poses: Int, val replans: Int)

    /** [account] lived for [days] by a user who takes every pose the calendar draws and sleeps at night. */
    private fun live(account: Account, days: Int): Lived {
        val away = ArrayList<TaskTimeRange>()
        for (day in -1 until days) {
            val start = T0 + day * DAY + NIGHT_FROM_HOUR * HOUR
            away += TaskTimeRange(start, start + NIGHT_HOURS * HOUR)
        }
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
        SchedulerReducer.noScreenEvidence = { away.filter { it.startEpochMillis < now }.toList() }
        SchedulerReducer.liveRestGap = { null }
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.planAbandoned = { false }
        CalendarHorizonFixture.show(DAY)
        var s = account.state
        now = T0 + 7 * HOUR // wakes up
        s = r(s, SchedulerIntent.RefreshSchedule(now))
        val end = T0 + days * DAY
        var poses = 0
        var replans = 1
        fun switchTo(mode: Int) {
            SchedulerReducer.tpMode = { mode }
            s = r(s, SchedulerIntent.SwitchTpMode(now))
        }
        while (now < end) {
            val night = away.firstOrNull { it.endEpochMillis > now && it.endEpochMillis - it.startEpochMillis >= HOUR }
            val pose = s.panels
                .filter { it.screenBreak && !SchedulerDomain.isDraggedScreenBreak(it) && it.startEpochMillis >= now }
                .filter { it.endEpochMillis - it.startEpochMillis >= 5 * MIN }
                .minByOrNull { it.startEpochMillis }
            val stop = minOf(now + HOUR, end, pose?.startEpochMillis ?: Long.MAX_VALUE, night?.startEpochMillis?.coerceAtLeast(now) ?: Long.MAX_VALUE)
            if (stop > now) {
                now = stop
                s = r(s, SchedulerIntent.AdvanceSchedule(now))
            }
            val leave = when {
                night != null && night.startEpochMillis <= now -> night.endEpochMillis
                pose != null && pose.startEpochMillis == now -> pose.endEpochMillis.also {
                    poses++
                    away += TaskTimeRange(now, it)
                }
                else -> null
            }
            if (leave != null) {
                switchTo(DynamicPeriods.MODE_ON_BREAK)
                now = minOf(leave, end)
                s = r(s, SchedulerIntent.AdvanceSchedule(now))
                switchTo(DynamicPeriods.MODE_AT_SCREEN)
            }
            if (now < end) s = r(s, SchedulerIntent.ExtendSchedule(now))
        }
        return Lived(s, away, poses, replans)
    }

    private var now = 0L

    private fun merged(ranges: List<TaskTimeRange>) = SchedulerDomain.mergeOccupied(ranges)

    private fun overlap(ranges: List<TaskTimeRange>, a: Long, b: Long) =
        ranges.sumOf { (minOf(it.endEpochMillis, b) - maxOf(it.startEpochMillis, a)).coerceAtLeast(0L) }

    /**
     * The longest stretch of screen work between two rests of at least [rest]: screen time summed from the end of one
     * rest to the start of the next (a shorter gap does not reset it, a rest does).
     */
    private fun longestScreenBetweenRests(screen: List<TaskTimeRange>, rest: Long, from: Long, until: Long): Pair<Long, Long> {
        var worst = 0L
        var worstAt = from
        var since = from
        var run = 0L
        val sorted = merged(screen).filter { it.endEpochMillis > from && it.startEpochMillis < until }
        var lastEnd = from
        for (piece in sorted) {
            if (piece.startEpochMillis - lastEnd >= rest) {
                run = 0L
                since = piece.startEpochMillis
            }
            run += minOf(piece.endEpochMillis, until) - maxOf(piece.startEpochMillis, from)
            if (run > worst) { worst = run; worstAt = since }
            lastEnd = piece.endEpochMillis
        }
        return worst to worstAt
    }

    private fun describe(lived: Lived, ids: Map<String, TaskId>, from: Long, until: Long) = buildString {
        appendLine("poses taken ${lived.poses}")
        for ((title, id) in ids) {
            val rec = merged(lived.state.tasks[id]?.record.orEmpty())
            appendLine("  ${title.padEnd(15)} ${"%6.1f".format(overlap(rec, from, until) / HOUR.toDouble())}h in ${rec.size} runs")
        }
    }

    @Test
    fun a_week_balances_the_priorities_keeps_the_eye_rules_and_credits_the_set_of_tasks() {
        val days = 7
        val account = account(days)
        val ids = account.ids
        val priorities = SchedulerDomain.absoluteTaskPriorities(account.state)
        assertEquals(0.3, priorities[ids.getValue("Applications")]!!, 1e-9)
        assertEquals(0.3, priorities[ids.getValue("Interview prep")]!!, 1e-9)
        assertEquals(0.2, priorities[ids.getValue("Spanish")]!!, 1e-9)
        assertEquals(0.2, priorities[ids.getValue("Exercise")]!!, 1e-9)

        val lived = live(account, days)
        val from = T0 + DAY // the first day settles from an empty past
        val until = T0 + days * DAY - HOUR
        val report = describe(lived, ids, from, until)
        println(report)
        fun record(title: String) = merged(lived.state.tasks[ids.getValue(title)]?.record.orEmpty())

        // --- hard rules: nothing at night, never two tasks at once.
        val all = ids.values.flatMap { id -> lived.state.tasks[id]?.record.orEmpty().map { id to it } }
        val nights = lived.away.filter { it.endEpochMillis - it.startEpochMillis >= HOUR }
        for ((id, range) in all) {
            assertTrue(overlap(nights, range.startEpochMillis, range.endEpochMillis) < MIN, "${lived.state.tasks[id]?.title} ran at night: $range\n$report")
        }
        val sorted = all.sortedBy { it.second.startEpochMillis }
        for (k in 0 until sorted.size - 1) {
            val over = sorted[k].second.endEpochMillis - sorted[k + 1].second.startEpochMillis
            assertTrue(over < MIN, "two tasks ran at once at +${(sorted[k + 1].second.startEpochMillis - T0) / MIN}min\n$report")
        }

        // --- 1. priorities balanced: what each task got, its set's credit included, against its share.
        val podcast = overlap(record("Podcast run"), from, until)
        val credit = mapOf("Exercise" to 1.0 * podcast, "Spanish" to 0.2 * podcast)
        val served = listOf("Applications", "Interview prep", "Spanish", "Exercise")
            .associateWith { overlap(record(it), from, until) + (credit[it] ?: 0.0) }
        val total = served.values.sum()
        // Waking time no task was given, outside the poses (which only an off-screen task may fill): the plan leaves
        // little to nobody (ScheduleSimulation.TOLERATED's 15 %).
        val waking = (until - from) - overlap(nights, from, until)
        val busy = merged(all.map { it.second } + lived.away)
        val idle = (until - from) - overlap(busy, from, until)
        println("  idle ${"%.1f".format(100.0 * idle / waking)}% of waking time")
        assertTrue(idle <= 0.15 * waking, "${idle / MIN}min of ${waking / MIN} waking min left to nobody\n$report")
        // The set of tasks is used where only it may run, and its credit there COUNTS (user rule 2026-11-22: "Task A can
        // have resilience 0 somewhere but a task that represents task A can have a not null resilience there";
        // `docs/scheduler_score.md` § *Sets of tasks*): Exercise is refused by the commute, but the podcast run that
        // serves it may run there, so Exercise is still wanted there and its own runs elsewhere shrink by what the
        // podcast gave it. Measured: Exercise 1.27x its nominal share before the rule, 1.17x with it.
        assertTrue(
            overlap(record("Exercise"), from, until) < 0.2 * total - 0.4 * podcast,
            "Exercise's own runs ignore what the podcast run gave it\n$report",
        )
        assertTrue(podcast >= (days - 1) * 45 * MIN, "the podcast run must fill the commutes, got ${podcast / MIN}min\n$report")
        // Off-screen time is forced on Exercise and the podcast run (they alone may fill a pose): how much of each.
        val posesTaken = lived.away.filter { it.endEpochMillis - it.startEpochMillis < HOUR }
        for (title in listOf("Exercise", "Podcast run")) {
            val inPoses = record(title).sumOf { overlap(posesTaken, it.startEpochMillis, it.endEpochMillis) }
            println("  $title: ${"%.1f".format(inPoses / HOUR.toDouble())}h of it inside poses")
        }
        // Held to the project's own levels ([ScheduleSimulation.TOLERATED]); how far from [ScheduleSimulation.GOAL] is printed.
        val tolerated = ScheduleSimulation.TOLERATED
        for ((title, got) in served) {
            val ratio = (got / total) / priorities[ids.getValue(title)]!!
            val goal = ScheduleSimulation.GOAL
            println("  $title: ${"%.2f".format(ratio)}x its share${if (ratio < goal.underService || ratio > goal.overService) "  (outside the GOAL band)" else ""}")
            assertTrue(ratio in tolerated.underService..tolerated.overService, "$title got ${"%.2f".format(ratio)}x its share\n$report")
            // What the set-of-tasks rule reached (2026-11-22): no task past 1.20x its share. Tighten towards GOAL, never loosen.
            assertTrue(ratio <= 1.20, "$title got ${"%.2f".format(ratio)}x its share (at most 1.20x since 2026-11-22)\n$report")
        }

        // --- 2. the eye rules, read off what was recorded on a screen.
        val screen = listOf("Applications", "Interview prep", "Spanish").flatMap { record(it) }
        val (lookAway, lookAwayAt) = longestScreenBetweenRests(screen, 20_000L, from, until)
        assertTrue(lookAway <= 20 * MIN + MIN, "${lookAway / MIN}min of screen without a 20s look-away from +${(lookAwayAt - T0) / MIN}min\n$report")
        val (pose5, pose5At) = longestScreenBetweenRests(screen, 5 * MIN, from, until)
        assertTrue(pose5 <= 60 * MIN + MIN, "${pose5 / MIN}min of screen without a 5-min pose from +${(pose5At - T0) / MIN}min\n$report")
        val (pose15, pose15At) = longestScreenBetweenRests(screen, 15 * MIN, from, until)
        assertTrue(pose15 <= 2 * HOUR + MIN, "${pose15 / MIN}min of screen without 15min away from +${(pose15At - T0) / MIN}min\n$report")
        assertTrue(lived.poses >= (days - 1) * 10, "the user must have been asked for poses, got ${lived.poses}\n$report")

        // --- 3. no screen task ever ran inside a pose the user took.
        val poses = lived.away.filter { it.endEpochMillis - it.startEpochMillis < HOUR }
        for (piece in screen) {
            assertTrue(
                overlap(poses, piece.startEpochMillis, piece.endEpochMillis) < MIN,
                "a screen task ran in a pose at +${(piece.startEpochMillis - T0) / MIN}min\n$report",
            )
        }
    }
}
