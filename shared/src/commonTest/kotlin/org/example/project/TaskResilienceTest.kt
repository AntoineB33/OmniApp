package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `side-dev/README.md` § *Restrictive Period*: **"each task has a resilience value for each kind of
 * restrictive period from 0 to 1"** — the model that replaced the pair of screen switches.
 *
 * Covers the undoable [SchedulerIntent.SetTaskResilience] mutation, defining and removing kinds, and — per
 * CLAUDE.md's persisted-DB compatibility rule — that a payload written by the *previous* shape (the
 * `onScreen` / `doableDuringBreak` booleans) still loads and is migrated to the resilience it means.
 */
class TaskResilienceTest {

    /** A single task "Solo". */
    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        return s to solo
    }

    @Test
    fun a_new_task_is_on_screen_which_is_a_zero_against_no_on_screen_task() {
        val (s, solo) = stateWithOneTask()
        assertEquals(0.0, s.tasks[solo]!!.resilienceFor(PeriodKinds.NO_SCREEN))
        assertTrue(s.tasks[solo]!!.onScreen)
    }

    @Test
    fun a_kind_a_task_was_never_told_about_defaults_to_one() {
        val (s, solo) = stateWithOneTask()
        assertEquals(1.0, s.tasks[solo]!!.resilienceFor("deep focus"))
        // …except "no task allowed", the kind that by its own name accepts nobody.
        assertEquals(0.0, s.tasks[solo]!!.resilienceFor(PeriodKinds.NO_TASK))
    }

    @Test
    fun setting_a_resilience_stores_it_on_the_task() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0))
        assertFalse(s.tasks[solo]!!.onScreen)
        assertEquals(1.0, s.tasks[solo]!!.resilienceFor(PeriodKinds.NO_SCREEN))
    }

    @Test
    fun a_value_between_the_two_ends_is_stored_as_itself() {
        // The whole point of a multiplier: a task half-resilient to a kind keeps half its percentage there.
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, "noisy", 0.5))
        assertEquals(0.5, s.tasks[solo]!!.resilienceFor("noisy"))
    }

    @Test
    fun a_value_equal_to_the_kinds_default_is_stored_as_no_override_at_all() {
        // Overrides only — so a changed default reaches every task that never moved it, exactly as the
        // shortcut bindings work.
        val (s0, solo) = stateWithOneTask()
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, "noisy", 0.5))
        assertTrue("noisy" in s1.tasks[solo]!!.resilience)
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.SetTaskResilience(solo, "noisy", 1.0))
        assertFalse("noisy" in s2.tasks[solo]!!.resilience)
        assertEquals(1.0, s2.tasks[solo]!!.resilienceFor("noisy"))
    }

    @Test
    fun a_value_outside_zero_to_one_is_healed_to_the_nearest_bound() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, "noisy", 4.0))
        assertEquals(1.0, s.tasks[solo]!!.resilienceFor("noisy"))
    }

    @Test
    fun undo_restores_the_previous_resilience() {
        val (s0, solo) = stateWithOneTask()
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0))
        val undone = SchedulerReducer.reduce(s1, SchedulerIntent.Undo)
        assertTrue(undone.tasks[solo]!!.onScreen)
        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertFalse(redone.tasks[solo]!!.onScreen)
    }

    @Test
    fun setting_an_unchanged_resilience_adds_no_history_unit() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 0.0))
        assertEquals(s0.histories, s.histories)
    }

    @Test
    fun codec_round_trip_preserves_the_whole_map() {
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPeriodKind("noisy"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(solo, "noisy", 0.25))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(1.0, decoded.tasks[solo]!!.resilienceFor(PeriodKinds.NO_SCREEN))
        assertEquals(0.25, decoded.tasks[solo]!!.resilienceFor("noisy"))
        assertEquals(listOf("noisy"), decoded.periodKinds)
    }

    // ----- defining kinds -----------------------------------------------------------------------

    @Test
    fun a_new_kind_gives_every_task_the_default_resilience_of_one() {
        // The README's own words. Nothing is written to a single task: an absent override IS the default,
        // which is what makes defining a kind free however many tasks the account holds.
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind("deep focus"))
        assertEquals(listOf("deep focus"), s.periodKinds)
        assertEquals(1.0, s.tasks[solo]!!.resilienceFor("deep focus"))
        assertTrue(s.tasks[solo]!!.resilience.none { it.key == "deep focus" })
    }

    @Test
    fun the_two_built_in_kinds_are_always_offered_and_are_never_in_the_accounts_list() {
        val (s0, _) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind("deep focus"))
        assertEquals(listOf(PeriodKinds.NO_TASK, PeriodKinds.NO_SCREEN, "deep focus"), s.allPeriodKinds)
    }

    @Test
    fun a_built_in_or_blank_kind_cannot_be_defined() {
        val (s0, _) = stateWithOneTask()
        assertEquals(s0.periodKinds, SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind("   ")).periodKinds)
        assertEquals(
            s0.periodKinds,
            SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind(PeriodKinds.NO_SCREEN)).periodKinds,
        )
    }

    @Test
    fun a_kind_is_normalized_so_one_name_is_one_kind() {
        val (s0, _) = stateWithOneTask()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind("  deep   focus "))
        assertEquals(listOf("deep focus"), s.periodKinds)
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPeriodKind("Deep Focus"))
        assertEquals(listOf("deep focus"), s.periodKinds)
    }

    @Test
    fun removing_a_kind_takes_every_tasks_value_for_it_with_it() {
        // A resilience to a kind that no longer exists is unreachable state, and would silently come back
        // if the kind were re-added under the same name.
        val (s0, solo) = stateWithOneTask()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind("noisy"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(solo, "noisy", 0.25))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RemovePeriodKind("noisy"))
        assertEquals(emptyList(), s.periodKinds)
        assertFalse("noisy" in s.tasks[solo]!!.resilience)
        assertEquals(1.0, s.tasks[solo]!!.resilienceFor("noisy"))
    }

    @Test
    fun a_built_in_kind_cannot_be_removed() {
        val (s0, solo) = stateWithOneTask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.RemovePeriodKind(PeriodKinds.NO_SCREEN))
        assertEquals(0.0, s.tasks[solo]!!.resilienceFor(PeriodKinds.NO_SCREEN))
    }

    // ----- the previous persisted shape ---------------------------------------------------------

    @Test
    fun codec_migrates_the_pre_resilience_on_screen_flag() {
        // CLAUDE.md persisted-DB rule: a payload written by the previous shape must still load. An
        // on-screen task is exactly a 0 against "no on-screen task".
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":"t0"}],
             "tasks":[{"id":"t0","title":"X","occurrences":["c0"],"onScreen":true,"doableDuringBreak":true}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val task = decoded.tasks[TaskId("t0")]!!
        assertEquals(0.0, task.resilienceFor(PeriodKinds.NO_SCREEN))
        assertTrue(task.onScreen)
    }

    @Test
    fun codec_migrates_the_pre_resilience_off_screen_flag_to_no_override() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":"t0"}],
             "tasks":[{"id":"t0","title":"X","occurrences":["c0"],"onScreen":false}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val task = decoded.tasks[TaskId("t0")]!!
        assertEquals(emptyMap(), task.resilience)
        assertFalse(task.onScreen)
    }

    @Test
    fun codec_decodes_a_payload_with_neither_field_to_the_pre_switch_default() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.tasks[TaskId("t0")]!!.onScreen)
    }

    @Test
    fun codec_heals_a_resilience_outside_zero_to_one_and_drops_a_redundant_override() {
        // A hand-edited or older payload cannot leave a task carrying a value the rules refuse today.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":"t0"}],
             "tasks":[{"id":"t0","title":"X","occurrences":["c0"],
                       "resilience":{"noisy":7.5,"quiet":1.0,"no on-screen task":0.0}}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val task = decoded.tasks[TaskId("t0")]!!
        assertEquals(1.0, task.resilienceFor("noisy"))
        assertFalse("quiet" in task.resilience)
        assertEquals(0.0, task.resilienceFor(PeriodKinds.NO_SCREEN))
    }
}
