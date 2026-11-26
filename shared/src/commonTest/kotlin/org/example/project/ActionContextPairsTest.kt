package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind

/**
 * Anomaly 2026-11-26: *"in 'Contexts and states', I don't see the field named context and the field named states. When I
 * click on the first row … it doesn't open a little pop-up window B listing all the contexts in the search result section
 * of B and all the selected contexts in added elements list section of B"*. Each pair is a context field and a states
 * field; the last pair is a new one, empty; B lists every value of the action's kinds, the context's own added.
 */
class ActionContextPairsTest {
    private val a = SearchDomain.ItemResult(Kind.Alarm, "a", "A", "")
    private val b = SearchDomain.ItemResult(Kind.Alarm, "b", "B", "")
    private val t = SearchDomain.ItemResult(Kind.Timer, "t", "T", "")

    @Test
    fun each_context_is_a_pair_then_an_empty_new_one() {
        assertEquals(listOf(listOf("Alarm/a", "Alarm/b"), listOf("Timer/t"), emptyList()), SearchDomain.contextPairs(listOf(listOf(a, b), listOf(t))))
        assertEquals(listOf(emptyList<String>()), SearchDomain.contextPairs(emptyList()))
    }

    @Test
    fun the_new_pair_given_values_is_followed_by_another_empty_one() {
        val pairs = listOf(listOf("Alarm/a"), emptyList())
        assertEquals(listOf(listOf("Alarm/a"), listOf("Timer/t"), emptyList()), SearchDomain.withPairValues(pairs, 1, listOf("Timer/t")))
        assertEquals(listOf(listOf("Alarm/a", "Alarm/b"), emptyList()), SearchDomain.withPairValues(pairs, 0, listOf("Alarm/a", "Alarm/b")))
    }

    @Test
    fun pop_up_b_lists_every_value_of_the_action_s_kinds_and_holds_the_context_s_values_added() {
        val config = SearchDomain.contextFieldConfig(listOf(a, b, t), listOf("Timer/t"))
        assertEquals(setOf(Kind.Alarm, Kind.Timer), config.kinds)
        assertEquals(listOf("Timer/t"), config.added)
    }
}
