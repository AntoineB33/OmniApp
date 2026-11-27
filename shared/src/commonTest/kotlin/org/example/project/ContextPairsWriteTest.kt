package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.ContextPair
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.PairsOutcome
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-27: *"When clicking the 'contexts' field, the added elements list represent all the contexts of this
 * pair. If the user changes it, then it is … a question of what are the contexts of this pair, which are now changed. Of
 * course if the change makes no sense (with a warning message in 'Contexts and states'), then the change is not saved"* —
 * and, the same day, a change that makes sense is written at once ([SearchDomain.contextPairsOutcome]).
 */
class ContextPairsWriteTest {
    private val action = AddedAction.TaskMinimumTime

    private fun account(vararg minutes: Pair<String, Int>): SchedulerState =
        SchedulerState.empty().let { empty ->
            empty.copy(tasks = empty.tasks + minutes.associate { (id, m) -> TaskId(id) to Task(TaskId(id), id.uppercase(), minimumMinutes = m) })
        }

    private fun key(id: String) = SearchDomain.taskKey(TaskId(id))

    private fun elements(state: SchedulerState) =
        SearchDomain.results(state, setOf(Kind.Task), "").filter { it.kind == Kind.Task }.associateBy(SearchDomain::keyOf)

    private fun outcome(state: SchedulerState, pairs: List<ContextPair>): PairsOutcome {
        val derived = SearchDomain.actionContextPairs(state, action, elements(state).values.toList())
        return SearchDomain.contextPairsOutcome(state, action, Kind.Task, pairs, derived, elements(state), others = null) { it }
    }

    // a and c at 45 (pair 0), "no state" (pair 1), b at 30 (pair 2).
    private val state = account("a" to 45, "b" to 30, "c" to 45)

    @Test
    fun a_context_moved_into_another_pair_is_given_that_pair_s_state() {
        val moved = listOf(
            ContextPair(listOf(key("a"), key("c"), key("b")), true),
            ContextPair(null, false),
            ContextPair(emptyList(), true),
        )
        val writes = (outcome(state, moved) as PairsOutcome.Writes).writes
        assertEquals(1, writes.size)
        assertEquals(listOf(key("b")), writes.single().to.map(SearchDomain::keyOf))
        var next = state
        for (w in writes) SearchDomain.addedIntents(next, w.to, w.command, 0L).forEach { next = SchedulerReducer.reduce(next, it) }
        assertEquals(45, next.tasks.getValue(TaskId("b")).minimumMinutes, "b now holds its new pair's state")
        assertEquals(2, SearchDomain.actionContextPairs(next, action, elements(next).values.toList()).size, "the account's pairs are those")
    }

    @Test
    fun the_pairs_as_the_account_holds_them_write_nothing() {
        val derived = SearchDomain.actionContextPairs(state, action, elements(state).values.toList())
        assertEquals(PairsOutcome.Writes(emptyList()), outcome(state, derived))
    }

    @Test
    fun a_change_that_makes_no_sense_writes_nothing_and_says_why() {
        val inNone = listOf(ContextPair(listOf(key("a"), key("c")), true), ContextPair(null, false), ContextPair(emptyList(), true))
        assertTrue((outcome(state, inNone) as PairsOutcome.Problem).warning.contains("in no context-state"))
        val inTwo = listOf(ContextPair(listOf(key("a"), key("c"), key("b")), true), ContextPair(null, false), ContextPair(listOf(key("b")), true))
        assertTrue((outcome(state, inTwo) as PairsOutcome.Problem).warning.contains("in two"))
        val noState = listOf(ContextPair(listOf(key("a"), key("c")), true), ContextPair(listOf(key("b")), false))
        assertTrue((outcome(state, noState) as PairsOutcome.Problem).warning.contains("cannot have no state"))
        val foreign = listOf(ContextPair(listOf(key("a"), key("c"), "Alarm/x"), true), ContextPair(null, false), ContextPair(listOf(key("b")), true))
        assertTrue((outcome(state, foreign) as PairsOutcome.Problem).warning.contains("not tasks") ||
            (outcome(state, foreign) as PairsOutcome.Problem).warning.contains("not task"))
        // A new pair gathering a (45) and b (30): no state of its own to give either.
        val mixed = listOf(ContextPair(listOf(key("c")), true), ContextPair(null, false), ContextPair(emptyList(), true), ContextPair(listOf(key("a"), key("b")), true))
        assertTrue((outcome(state, mixed) as PairsOutcome.Problem).warning.contains("different states"))
    }

    /** Every action whose state is read off the element can be given it, but those that show the element (each its own). */
    @Test
    fun a_state_read_off_an_element_can_be_given_to_another() {
        val rows = elements(state).values.toList()
        assertNotNull(SearchDomain.actionStateWrites(state, AddedAction.TaskMinimumTime, rows[0], rows.drop(1)))
        assertNotNull(SearchDomain.actionStateWrites(state, AddedAction.ElementParents, rows[0], rows.drop(1)))
        assertEquals(emptyList(), SearchDomain.actionStateWrites(state, AddedAction.TaskEdit, rows[0], rows.drop(1)))
    }
}
