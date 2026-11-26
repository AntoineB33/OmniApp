package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.ContextPair
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * Anomaly 2026-11-26: *"in 'Contexts and states' for the element 'Minimum Time', I must find the minimum time of all task
 * elements. If the user never changed minimum time, then there will only be two pairs"* — the tasks at 45 minutes, then
 * "no state" over every other context; a task changed is a third pair, the first one less that task. Each pair is a
 * context field (pop-up window B: every context in its results, the pair's added) and a states field; the last pair is a
 * new one, empty.
 */
class ActionContextPairsTest {
    private fun account(vararg minutes: Pair<String, Int>): SchedulerState =
        SchedulerState.empty().let { empty ->
            empty.copy(tasks = empty.tasks + minutes.associate { (id, m) -> TaskId(id) to Task(TaskId(id), id.uppercase(), minimumMinutes = m) })
        }

    private fun tasks(state: SchedulerState) = SearchDomain.results(state, setOf(Kind.Task), "").filter { it.kind == Kind.Task }

    private fun pairs(state: SchedulerState) = SearchDomain.actionContextPairs(state, AddedAction.TaskMinimumTime, tasks(state))

    private fun keys(vararg ids: String) = ids.map { SearchDomain.taskKey(TaskId(it)) }.toSet()

    @Test
    fun minimum_time_never_changed_is_every_task_then_no_state() {
        val pairs = pairs(account("a" to 45, "b" to 45, "c" to 45))
        assertEquals(3, pairs.size)
        assertEquals(keys("a", "b", "c"), pairs[0].keys!!.toSet())
        assertEquals(true, pairs[0].stated)
        assertEquals(ContextPair(null, stated = false), pairs[1])
        assertEquals(ContextPair(emptyList(), stated = true), pairs[2])
    }

    @Test
    fun a_task_whose_minimum_time_changed_leaves_the_first_pair_for_a_third() {
        val pairs = pairs(account("a" to 45, "b" to 30, "c" to 45))
        assertEquals(4, pairs.size)
        assertEquals(keys("a", "c"), pairs[0].keys!!.toSet())
        assertEquals(ContextPair(null, stated = false), pairs[1])
        assertEquals(keys("b"), pairs[2].keys!!.toSet())
        assertEquals(ContextPair(emptyList(), stated = true), pairs[3])
    }

    @Test
    fun the_no_state_pair_is_every_kind_but_the_action_s() {
        val others = SearchDomain.otherContextKinds(AddedAction.TaskMinimumTime)
        assertEquals(false, Kind.Task in others)
        assertEquals(true, Kind.Alarm in others && Kind.Context in others)
    }

    @Test
    fun the_new_pair_given_contexts_is_followed_by_another_empty_one() {
        val pairs = listOf(ContextPair(listOf("Alarm/a"), true), ContextPair(emptyList(), true))
        assertEquals(
            listOf(ContextPair(listOf("Alarm/a"), true), ContextPair(listOf("Timer/t"), true), ContextPair(emptyList(), true)),
            SearchDomain.withPairValues(pairs, 1, listOf("Timer/t")),
        )
        assertEquals(
            listOf(ContextPair(listOf("Alarm/a", "Alarm/b"), true), ContextPair(emptyList(), true)),
            SearchDomain.withPairValues(pairs, 0, listOf("Alarm/a", "Alarm/b")),
        )
    }

    @Test
    fun pop_up_b_lists_every_context_and_holds_the_pair_s_added() {
        val config = SearchDomain.contextFieldConfig(listOf("Task/a"))
        assertEquals(SearchDomain.CONTEXT_KINDS, config.kinds)
        assertEquals(listOf("Task/a"), config.added)
    }
    /**
     * User rule 2026-11-26 (*"A search window with every search configuration off is supposed to list every single element
     * of the app"*): the "no state" pair holds every element of the other kinds — an alarm, a chrono — and no task.
     */
    @Test
    fun the_no_state_pair_lists_every_element_of_the_other_kinds() {
        val state = account("a" to 45).copy(
            alarms = listOf(org.example.project.scheduler.model.AlarmEntry(id = "x", label = "wake", timeOfDayMinutes = 420)),
            chronos = listOf(org.example.project.scheduler.model.ChronoEntry(id = "c", label = "c")),
        )
        val keys = SearchDomain.otherContextKeys(state, AddedAction.TaskMinimumTime)
        assertEquals(true, "Alarm/x" in keys && "Chrono/c" in keys, keys.toString())
        assertEquals(false, keys.any { it.startsWith("Task/") }, keys.toString())
    }
}
