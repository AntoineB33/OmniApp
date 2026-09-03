package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * The **task-tree timeline** ("All task trees"): the named task trees that have been given a date are
 * keyframes of the account's priorities, and between two of them the scheduler follows a continuous linear
 * blend of the two rather than the tree on screen.
 *
 * Covers the blend itself (including the "a task missing from one tree is 0% there" rule and the union of
 * schedulable leaves that makes the transition actually continuous), the clamping outside the dated range,
 * the two new intents, the fact that a dated tree is a scheduling input even when it is not the live tree,
 * and — per the persisted-DB compatibility rule — that a payload written before dates existed decodes to
 * "no tree is on the timeline".
 */
class TaskTreeTimelineTest {

    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_700_000_000_000L

    /** Root list with [titles] in its first cells, one task each. */
    private fun stateWithTasks(vararg titles: String): SchedulerState {
        var s = SchedulerState.empty()
        titles.forEachIndexed { i, title ->
            val cells = s.lists[s.rootListId]!!.cellIds
            val cell = cells.getOrNull(i) ?: cells.last()
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, title))
        }
        return s
    }

    /**
     * The id of the task titled [title], looked up across the live tree **and** the stored trees — the
     * whole point of the fixture below is that a task can live only in an inactive tree.
     */
    private fun taskIdOf(state: SchedulerState, title: String): TaskId =
        state.tasks.entries.firstOrNull { it.value.title == title }?.key
            ?: state.taskTrees.firstNotNullOf { entry ->
                entry.tree.tasks.entries.firstOrNull { it.value.title == title }?.key
            }

    /** The user-created task titles a stored tree holds (the root/MAIN scaffolding filtered out). */
    private fun titlesIn(state: SchedulerState, tree: String): Set<String> {
        val entry = state.taskTrees.first { it.title == tree }
        return entry.tree.cells.values
            .mapNotNull { cell -> cell.taskId?.let { entry.tree.tasks[it]?.title } }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun treeIdOf(state: SchedulerState, title: String): TaskTreeId =
        state.taskTrees.first { it.title == title }.id

    private fun dated(state: SchedulerState, title: String, millis: Long?): SchedulerState =
        SchedulerReducer.reduce(state, SchedulerIntent.SetTaskTreeDate(treeIdOf(state, title), millis))

    /**
     * Two dated trees: "Summer" (at [t0]) holds A and B; "Autumn" (at t0 + 10 days), which diverged from it,
     * holds B and C — so A exists only in the first and C only in the second.
     */
    private fun twoKeyframes(): SchedulerState {
        var s = stateWithTasks("A", "B")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Summer"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Autumn"))
        // Diverge "Autumn": add C, then drop A. Note C must be a NEW cell rather than A's cell retitled —
        // retitling reuses the same TaskId, which by the identity rule would make it the same task renamed
        // (and rightly so: its priority would then simply carry across both trees).
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "C"))
        val aCell = s.lists[s.rootListId]!!.cellIds.first { s.tasks[s.cells[it]?.taskId]?.title == "A" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(aCell, ""))
        s = dated(s, "Summer", t0)
        return dated(s, "Autumn", t0 + 10 * day)
    }

    // ---- the blend -----------------------------------------------------------------------------

    @Test
    fun midway_between_two_trees_every_priority_is_the_average_of_the_two() {
        val s = twoKeyframes()
        val a = taskIdOf(s, "A")
        val b = taskIdOf(s, "B")
        val c = taskIdOf(s, "C")

        val p = SchedulerDomain.blendedTaskPriorities(s, t0 + 5 * day)
        // A is 50% in "Summer" and absent (0%) from "Autumn" → 25% halfway.
        assertEquals(0.25, p[a]!!, 1e-9)
        // B is 50% in both → 50% throughout, which is what "continuous" has to mean for an unchanged task.
        assertEquals(0.5, p[b]!!, 1e-9)
        // C is absent from "Summer" and 50% in "Autumn" → 25% halfway.
        assertEquals(0.25, p[c]!!, 1e-9)
    }

    @Test
    fun the_transformation_is_continuous_and_even_across_the_whole_span() {
        val s = twoKeyframes()
        val a = taskIdOf(s, "A")
        val c = taskIdOf(s, "C")
        // A falls 50% → 0% linearly, C rises 0% → 50%, and they cross exactly at the midpoint.
        for (step in 0..10) {
            val now = t0 + step * day
            val p = SchedulerDomain.blendedTaskPriorities(s, now)
            val f = step / 10.0
            assertEquals(0.5 * (1 - f), p[a] ?: 0.0, 1e-9, "A at day $step")
            assertEquals(0.5 * f, p[c] ?: 0.0, 1e-9, "C at day $step")
        }
    }

    @Test
    fun outside_the_dated_range_the_nearest_tree_holds() {
        val s = twoKeyframes()
        val a = taskIdOf(s, "A")
        val c = taskIdOf(s, "C")

        val before = SchedulerDomain.blendedTaskPriorities(s, t0 - 30 * day)
        assertEquals(0.5, before[a]!!, 1e-9)
        assertNull(before[c])

        val after = SchedulerDomain.blendedTaskPriorities(s, t0 + 400 * day)
        assertEquals(0.5, after[c]!!, 1e-9)
        assertNull(after[a])
    }

    @Test
    fun with_no_dated_tree_the_priorities_are_the_live_trees_own() {
        var s = stateWithTasks("A", "B")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Summer"))
        assertNull(SchedulerDomain.taskTreeBlendAt(s, t0))
        assertEquals(
            SchedulerDomain.absoluteTaskPriorities(s),
            SchedulerDomain.blendedTaskPriorities(s, t0),
        )
    }

    @Test
    fun the_live_trees_unflushed_edits_count_when_it_is_a_keyframe() {
        // A keyframe that happens to be the tree on screen must contribute what the user has actually got:
        // its STORED snapshot is stale by design between switches, so the blend has to flush first.
        var s = twoKeyframes()
        // "Autumn" is live; give C a heavier weight so its share stops being 50%.
        val cCell = s.lists[s.rootListId]!!.cellIds.first { s.tasks[s.cells[it]?.taskId]?.title == "C" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cCell, 0, 3.0))

        val c = taskIdOf(s, "C")
        // 3/(3+1) = 75% in "Autumn"; at "Autumn"'s own date that is exactly what the scheduler follows.
        assertEquals(0.75, SchedulerDomain.blendedTaskPriorities(s, t0 + 10 * day)[c]!!, 1e-9)
    }

    // ---- what gets scheduled -------------------------------------------------------------------

    @Test
    fun the_schedulable_leaves_are_the_union_of_both_keyframes() {
        val s = twoKeyframes()
        val leaves = SchedulerDomain.blendedSchedulableLeaves(s, t0 + 5 * day).toSet()
        assertEquals(
            setOf(taskIdOf(s, "A"), taskIdOf(s, "B"), taskIdOf(s, "C")),
            leaves,
            "a task from the tree being transitioned TO must be placeable while its share ramps up",
        )
    }

    @Test
    fun a_task_from_the_other_keyframe_is_scheduled_and_keeps_its_title() {
        val s = twoKeyframes()
        // "Autumn" is live, so A survives there only as a blank-titled tombstone — yet halfway through the
        // transition it still owns 25% of the plan, and its panels must take their name from "Summer"
        // rather than from the tombstone.
        assertEquals("", s.tasks[taskIdOf(s, "A")]?.title.orEmpty(), "the fixture must delete A from the live tree")
        val panels = SchedulerDomain.fillSchedule(s, t0 + 5 * day, horizonMillis = t0 + 5 * day + 2 * day)
        val aPanels = panels.filter { it.taskId == taskIdOf(s, "A") }
        assertTrue(aPanels.isNotEmpty(), "A owns 25% of the blend and must be placed: ${panels.map { it.title }}")
        assertTrue(aPanels.all { it.title == "A" }, "a foreign-tree task must carry its own title")
    }

    @Test
    fun the_fill_follows_the_blend_not_the_tree_on_screen() {
        val s = twoKeyframes()
        val a = taskIdOf(s, "A")
        val c = taskIdOf(s, "C")
        val horizon = 3 * day
        fun servedAt(now: Long): Pair<Long, Long> {
            val panels = SchedulerDomain.fillSchedule(s, now, horizonMillis = now + horizon)
            fun span(id: TaskId) =
                panels.filter { it.taskId == id }.sumOf { it.endEpochMillis - it.startEpochMillis }
            return span(a) to span(c)
        }
        // Early in the transition A (fading out) still outweighs C (fading in); late on, the reverse.
        val (earlyA, earlyC) = servedAt(t0 + 1 * day)
        val (lateA, lateC) = servedAt(t0 + 9 * day)
        assertTrue(earlyA > earlyC, "early in the transition A should outweigh C ($earlyA vs $earlyC)")
        assertTrue(lateC > lateA, "late in the transition C should outweigh A ($lateC vs $lateA)")
    }

    // ---- the re-plan trigger -------------------------------------------------------------------

    @Test
    fun editing_a_dated_tree_that_is_not_on_screen_changes_the_scheduling_signature() {
        // A keyframe is a scheduling input like the live tree, so an edit to it must re-plan — even though
        // nothing the user can see on the tree changed.
        var s = twoKeyframes()
        val before = SchedulerDomain.schedulingSignature(s)
        val summer = s.taskTrees.first { it.title == "Summer" }
        val someCell = summer.tree.cells.keys.first { summer.tree.cells[it]?.taskId != null }
        val bumped =
            summer.copy(
                tree = summer.tree.copy(
                    cells = summer.tree.cells + (someCell to summer.tree.cells[someCell]!!.copy(priorityWeights = listOf(7.0))),
                ),
            )
        s = s.copy(taskTrees = s.taskTrees.map { if (it.id == summer.id) bumped else it })
        assertTrue(before != SchedulerDomain.schedulingSignature(s))
    }

    @Test
    fun an_undated_tree_is_not_a_scheduling_input() {
        // Nothing reads an undated tree until it is selected, at which point it IS the live tree.
        var s = stateWithTasks("A", "B")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Summer"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Autumn"))
        val before = SchedulerDomain.schedulingSignature(s)
        val summer = s.taskTrees.first { it.title == "Summer" }
        val someCell = summer.tree.cells.keys.first { summer.tree.cells[it]?.taskId != null }
        val bumped =
            summer.copy(
                tree = summer.tree.copy(
                    cells = summer.tree.cells + (someCell to summer.tree.cells[someCell]!!.copy(priorityWeights = listOf(7.0))),
                ),
            )
        s = s.copy(taskTrees = s.taskTrees.map { if (it.id == summer.id) bumped else it })
        assertEquals(before, SchedulerDomain.schedulingSignature(s))
    }

    @Test
    fun the_blend_step_is_quantized_so_time_alone_replans_a_bounded_number_of_times() {
        val s = twoKeyframes()
        val steps = (0..10_000).map { SchedulerDomain.taskTreeBlendStep(s, t0 + it * (10L * day / 10_000)) }
        val changes = steps.zipWithNext().count { (a, b) -> a != b }
        assertTrue(
            changes <= SchedulerDomain.TASK_TREE_BLEND_STEPS + 1,
            "a whole transition must cost at most one fill per step, got $changes",
        )
        assertTrue(changes > 1, "the blend must actually move the trigger, got $changes")
        // With no dated tree the step is constant, so an account that never uses the timeline never fires.
        val plain = SchedulerReducer.reduce(stateWithTasks("A"), SchedulerIntent.CreateTaskTree("Only"))
        assertEquals(
            SchedulerDomain.taskTreeBlendStep(plain, t0),
            SchedulerDomain.taskTreeBlendStep(plain, t0 + 900 * day),
        )
    }

    // ---- the intents ---------------------------------------------------------------------------

    @Test
    fun dating_a_tree_puts_it_on_the_timeline_and_clearing_takes_it_off() {
        var s = twoKeyframes()
        assertEquals(listOf("Summer", "Autumn"), SchedulerDomain.datedTaskTrees(s).map { it.title })

        s = dated(s, "Summer", null)
        assertEquals(listOf("Autumn"), SchedulerDomain.datedTaskTrees(s).map { it.title })
        assertNull(s.taskTrees.first { it.title == "Summer" }.dateMillis)
    }

    @Test
    fun the_timeline_is_ordered_by_date_not_by_creation() {
        var s = twoKeyframes()
        s = dated(s, "Autumn", t0 - 5 * day)
        assertEquals(listOf("Autumn", "Summer"), SchedulerDomain.datedTaskTrees(s).map { it.title })
    }

    @Test
    fun setting_the_same_date_or_dating_an_unknown_tree_is_a_no_op() {
        val s = twoKeyframes()
        assertEquals(s, dated(s, "Summer", t0))
        assertEquals(
            s,
            SchedulerReducer.reduce(s, SchedulerIntent.SetTaskTreeDate(TaskTreeId("tree/nope"), t0)),
        )
    }

    @Test
    fun a_date_is_undoable() {
        var s = twoKeyframes()
        s = dated(s, "Summer", t0 + 2 * day)
        assertEquals(t0 + 2 * day, s.taskTrees.first { it.title == "Summer" }.dateMillis)
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(t0, s.taskTrees.first { it.title == "Summer" }.dateMillis)
        s = SchedulerReducer.reduce(s, SchedulerIntent.Redo)
        assertEquals(t0 + 2 * day, s.taskTrees.first { it.title == "Summer" }.dateMillis)
    }

    @Test
    fun deleting_an_inactive_tree_removes_it_and_undo_puts_it_back_whole() {
        var s = twoKeyframes()
        val summerId = treeIdOf(s, "Summer")
        s = SchedulerReducer.reduce(s, SchedulerIntent.DeleteTaskTree(summerId))
        assertEquals(listOf("Autumn"), s.taskTrees.map { it.title })
        // …and with it, its share of the blend: only "Autumn" is left to follow.
        val c = taskIdOf(s, "C")
        assertEquals(0.5, SchedulerDomain.blendedTaskPriorities(s, t0)[c]!!, 1e-9)

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(listOf("Summer", "Autumn"), s.taskTrees.map { it.title })
        assertEquals(t0, s.taskTrees.first { it.title == "Summer" }.dateMillis)
        assertTrue(titlesIn(s, "Summer").containsAll(setOf("A", "B")), "restored: ${titlesIn(s, "Summer")}")
    }

    @Test
    fun deleting_the_live_tree_keeps_what_is_on_screen_and_only_drops_its_name() {
        var s = twoKeyframes()
        val liveId = s.activeTaskTreeId!!
        val onScreen = s.lists[s.rootListId]!!.cellIds.mapNotNull { s.tasks[s.cells[it]?.taskId]?.title }

        s = SchedulerReducer.reduce(s, SchedulerIntent.DeleteTaskTree(liveId))
        assertNull(s.activeTaskTreeId)
        assertEquals(listOf("Summer"), s.taskTrees.map { it.title })
        assertEquals(
            onScreen,
            s.lists[s.rootListId]!!.cellIds.mapNotNull { s.tasks[s.cells[it]?.taskId]?.title },
            "the bin must never cost the user the tree they are looking at",
        )
    }

    @Test
    fun deleting_an_unknown_tree_is_a_no_op() {
        val s = twoKeyframes()
        assertEquals(s, SchedulerReducer.reduce(s, SchedulerIntent.DeleteTaskTree(TaskTreeId("tree/nope"))))
    }

    // ---- persistence ---------------------------------------------------------------------------

    @Test
    fun the_dates_round_trip_through_the_codec_and_the_sync_snapshot() {
        val s = twoKeyframes()
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(listOf(t0, t0 + 10 * day), decoded.taskTrees.map { it.dateMillis })

        val synced = SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))
        assertNotNull(synced)
        assertEquals(listOf(t0, t0 + 10 * day), synced.taskTrees.map { it.dateMillis })
    }

    @Test
    fun a_tree_written_before_dates_existed_decodes_as_not_on_the_timeline() {
        // Persisted-DB compatibility: a taskTrees entry with no "date" key. It must load as an ordinary
        // undated tree — which is also what leaves the scheduler following the live tree, as it did then.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":"t0"}],
             "tasks":[{"id":"t0","title":"Alpha","occurrences":["c0"]}],
             "taskTrees":[{"id":"tree/0","title":"Work",
               "tree":{"lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
                       "cells":[{"id":"c0","parentListId":"L","taskId":"t0"}],
                       "tasks":[{"id":"t0","title":"Alpha","occurrences":["c0"]}],
                       "nextTaskCounter":1,"nextCellCounter":1}}],
             "activeTaskTreeId":"tree/0","nextTaskTreeCounter":1}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(listOf("Work"), decoded.taskTrees.map { it.title })
        assertNull(decoded.taskTrees[0].dateMillis)
        assertTrue(SchedulerDomain.datedTaskTrees(decoded).isEmpty())
        assertNull(SchedulerDomain.taskTreeBlendAt(decoded, t0))
    }

    // ---- the rest of the rule state ------------------------------------------------------------

    /**
     * `side-dev/README.md` § *Rule State Definition*: a rule state is *"the set of tasks and their associated
     * priority percentages, **minimum execution time and resilience values**"*, and § *Rule State Evolution*
     * says the whole of it *"transforms evenly from the first state to the second one"*.
     *
     * Only the percentages travelled. The other two were read off the LIVE tree at every instant — so a task
     * sitting exactly ON a keyframe was scheduled with a minimum that keyframe does not state, and a
     * transition between a 30-minute task and a 90-minute one was a step, not a transformation.
     */
    private fun twoMinimumKeyframes(): Pair<SchedulerState, TaskId> {
        var s = stateWithTasks("A")
        val a = taskIdOf(s, "A")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 30))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Summer"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Autumn"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Autumn")))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 90))
        s = dated(s, "Summer", t0)
        return dated(s, "Autumn", t0 + 10 * day) to a
    }

    @Test
    fun the_minimum_execution_time_travels_with_the_percentage() {
        val (s, a) = twoMinimumKeyframes()
        fun minimumAt(now: Long) = SchedulerDomain.blendedTaskAttributes(s, now)[a]!!.minimumMinutes
        // On each keyframe the rule state is that keyframe's, not the tree the user happens to be editing.
        assertEquals(30, minimumAt(t0))
        assertEquals(90, minimumAt(t0 + 10 * day))
        // And it transforms evenly in between.
        for (step in 0..10) {
            assertEquals(30 + 6 * step, minimumAt(t0 + step * day), "day $step")
        }
        // Outside the dated range the nearest keyframe holds, exactly as the percentages do.
        assertEquals(30, minimumAt(t0 - 30 * day))
        assertEquals(90, minimumAt(t0 + 400 * day))
    }

    @Test
    fun a_resilience_travels_with_it_too() {
        // "On screen" is a resilience of 0 to "no on-screen task", so a task that goes off screen between two
        // keyframes crosses over evenly rather than flipping on the date.
        var s = stateWithTasks("A")
        val a = taskIdOf(s, "A")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Summer"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Autumn"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Autumn")))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(a, PeriodKinds.NO_SCREEN, 1.0))
        s = dated(s, "Summer", t0)
        s = dated(s, "Autumn", t0 + 10 * day)
        fun resilienceAt(now: Long) =
            SchedulerDomain.blendedTaskAttributes(s, now)[a]!!.resilienceFor(PeriodKinds.NO_SCREEN)
        assertEquals(0.0, resilienceAt(t0), 1e-9)
        assertEquals(0.5, resilienceAt(t0 + 5 * day), 1e-9)
        assertEquals(1.0, resilienceAt(t0 + 10 * day), 1e-9)
    }

    @Test
    fun a_task_only_one_keyframe_holds_keeps_that_keyframes_minimum_throughout() {
        // Only the PERCENTAGE fades to zero on the side that has no such task: the identity is what carries
        // across, so the side that HAS it states its minimum for the whole transition
        // (`side-dev/scheduler.py` `RuleStates.at`).
        val s = twoKeyframes()
        val a = taskIdOf(s, "A") // only in "Summer"
        val live = s.taskTrees.first { it.title == "Summer" }.tree.tasks[a]!!.minimumMinutes
        for (step in 0..10) {
            assertEquals(live, SchedulerDomain.blendedTaskAttributes(s, t0 + step * day)[a]!!.minimumMinutes)
        }
    }

    @Test
    fun a_date_is_authoritative_so_it_moves_the_sync_fingerprint() {
        val s = twoKeyframes()
        val cleared = dated(s, "Summer", null)
        assertTrue(
            SchedulerStateCodec.syncFingerprint(s) != SchedulerStateCodec.syncFingerprint(cleared),
            "the timeline is account-wide user data and must push",
        )
    }

    // ---- `side-dev/README.md` § *Rule state evolution*, the Example ----------------------------

    /**
     * The README's own worked example, which is the one clause of § *Rule state evolution* that is not a
     * statement about a single instant but about two whole timelines:
     *
     * > Scenario 1: Task A goes from 0 to 100% priority from t1 to t2=t1+10min.
     * > Scenario 2: Task A goes from 0 to 50% priority from t1 to t2=t1+5min.
     * > It is guaranteed ... that the resulting set of rules on both scenarios gives the same schedule and
     * > alternative schedule up to t1+5min.
     *
     * Two transitions that move A **at the same rate** are the same rule state at every moment they share,
     * so they must be the same plan there — the transition's *span* may not leak into the numbers, only its
     * slope. That is exactly what a linear interpolation buys, and it is what would break the instant the
     * blend acquired an easing curve, a per-span normalization, or a fraction quantized into the value
     * rather than only into the re-plan trigger ([TASK_TREE_BLEND_STEPS], which the fixed sampling below
     * deliberately does not exercise).
     *
     * "Start" holds B alone; the second keyframe hands A `endPercent` of the share, `spanMinutes` later.
     * With 100 % / 10 min and 50 % / 5 min, A rises at 10 %/min in both.
     */
    private fun rampScenario(endPercent: Int, spanMinutes: Long): SchedulerState {
        val minute = 60_000L
        var s = stateWithTasks("B")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Start"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("End"))
        // "End" is live: give it A. A 50/50 end state keeps B; a 100 % one drops it.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "A"))
        if (endPercent == 100) {
            val bCell = s.lists[s.rootListId]!!.cellIds.first { s.tasks[s.cells[it]?.taskId]?.title == "B" }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bCell, ""))
        }
        s = dated(s, "Start", t0)
        return dated(s, "End", t0 + spanMinutes * minute)
    }

    /** The plan as a comparable value: what ran, when, under what title. */
    private fun planAt(state: SchedulerState, now: Long, horizon: Long): List<String> =
        SchedulerDomain.fillSchedule(state, now, horizonMillis = now + horizon)
            .filter { it.taskId != null }
            .sortedBy { it.startEpochMillis }
            .map { "${it.title}@${it.startEpochMillis}-${it.endEpochMillis}" }

    @Test
    fun two_transitions_with_the_same_slope_are_the_same_rule_state_while_they_overlap() {
        val minute = 60_000L
        val slow = rampScenario(endPercent = 100, spanMinutes = 10) // A: 0 → 100 % over 10 min
        val fast = rampScenario(endPercent = 50, spanMinutes = 5) //  A: 0 → 50 % over 5 min

        val a = taskIdOf(slow, "A")
        val b = taskIdOf(slow, "B")
        assertEquals(a, taskIdOf(fast, "A"), "the two scenarios must differ only in the second keyframe")

        for (tenth in 0..50) {
            val now = t0 + tenth * minute / 10
            val slowP = SchedulerDomain.blendedTaskPriorities(slow, now)
            val fastP = SchedulerDomain.blendedTaskPriorities(fast, now)
            // 10 %/min in both, whatever the span the keyframes are pinned at.
            assertEquals(0.1 * tenth / 10.0, slowP[a] ?: 0.0, 1e-9, "slow A at ${tenth / 10.0} min")
            assertEquals(slowP[a] ?: 0.0, fastP[a] ?: 0.0, 1e-9, "A at ${tenth / 10.0} min")
            assertEquals(slowP[b] ?: 0.0, fastP[b] ?: 0.0, 1e-9, "B at ${tenth / 10.0} min")
            // The rest of the rule state travels with the percentages, so it has to agree too.
            val slowAttrs = SchedulerDomain.blendedTaskAttributes(slow, now)
            val fastAttrs = SchedulerDomain.blendedTaskAttributes(fast, now)
            for (id in listOf(a, b)) {
                assertEquals(
                    slowAttrs[id]?.minimumMinutes,
                    fastAttrs[id]?.minimumMinutes,
                    "minimum of $id at ${tenth / 10.0} min",
                )
                assertEquals(
                    slowAttrs[id]?.resilienceFor(PeriodKinds.NO_SCREEN),
                    fastAttrs[id]?.resilienceFor(PeriodKinds.NO_SCREEN),
                    "resilience of $id at ${tenth / 10.0} min",
                )
            }
            assertEquals(
                SchedulerDomain.blendedSchedulableLeaves(slow, now).toSet(),
                SchedulerDomain.blendedSchedulableLeaves(fast, now).toSet(),
                "the placeable set at ${tenth / 10.0} min",
            )
        }
    }

    @Test
    fun the_same_slope_gives_the_same_schedule_while_the_two_transitions_overlap() {
        val minute = 60_000L
        val slow = rampScenario(endPercent = 100, spanMinutes = 10)
        val fast = rampScenario(endPercent = 50, spanMinutes = 5)
        val horizon = 6 * 60 * minute

        for (halfMinute in 0..10) {
            val now = t0 + halfMinute * minute / 2
            assertEquals(
                planAt(slow, now, horizon),
                planAt(fast, now, horizon),
                "the plan at ${halfMinute / 2.0} min past t1",
            )
        }
        // …and the guarantee stops where the README stops it: past t1+5min the fast scenario has settled on
        // its final state while the slow one is still climbing, so the two plans are free to diverge.
        assertTrue(
            planAt(slow, t0 + 9 * minute, horizon) != planAt(fast, t0 + 9 * minute, horizon),
            "the guarantee must be about the shared slope, not vacuously true",
        )
    }
}
