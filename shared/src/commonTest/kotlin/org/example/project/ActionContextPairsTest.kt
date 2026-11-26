package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind

/**
 * Anomaly 2026-11-26: *"in the action section of the element 'Minimum Time', in 'Contexts and states', I can't select the
 * context or add a new pair of contexts - states fields"*. A context row opens its elements, added; the new pair's elements
 * are picked among those the action acts on, and the pair is gone once they are one of the contexts.
 */
class ActionContextPairsTest {
    private val a = SearchDomain.ItemResult(Kind.Alarm, "a", "A", "")
    private val b = SearchDomain.ItemResult(Kind.Alarm, "b", "B", "")
    private val t = SearchDomain.ItemResult(Kind.Timer, "t", "T", "")

    @Test
    fun a_context_row_opens_its_elements_added_their_kinds_listed() {
        val config = SearchDomain.elementsSearchConfig(listOf(a, t).map(SearchDomain::keyOf))
        assertEquals(listOf("Alarm/a", "Timer/t"), config.added)
        assertEquals(setOf(Kind.Alarm, Kind.Timer), config.kinds)
    }

    @Test
    fun the_new_pair_is_what_is_picked_in_the_members_order_until_it_is_a_context() {
        val members = listOf(a, b, t)
        assertEquals(listOf(a, t), SearchDomain.newContextMembers(members, setOf("Timer/t", "Alarm/a")))
        assertTrue(SearchDomain.newContextMembers(members, emptySet()).isEmpty())
        val contexts = listOf(listOf(a, b), listOf(t))
        assertTrue(SearchDomain.isOneOfContexts(listOf(t), contexts))
        assertFalse(SearchDomain.isOneOfContexts(listOf(a), contexts))
        assertFalse(SearchDomain.isOneOfContexts(emptyList(), contexts))
    }
}
