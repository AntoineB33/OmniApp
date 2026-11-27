package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.SearchDomain

/**
 * User rule 2026-11-27: *"Make sure any list in the app is the result of search configuration elements set to off or on in
 * each context … Only their default contexts-states pairs are built in"*, then *"When a window opens, every search
 * configuration element for this window gets the corresponding value by default."* Every window the code opens is opened
 * in a context ([SearchDomain.WINDOW_CONTEXTS]); what the account made of that context's default is what it opens with,
 * under what the opening says ([ContextDomain.opened]).
 */
class WindowContextsTest {

    private fun changed(id: String, change: (SearchDomain.Config) -> SearchDomain.Config) =
        ContextDomain.written(emptyList(), id, change(ContextDomain.configOf(emptyList(), id)))

    @Test
    fun every_window_context_is_a_context_and_each_builder_opens_in_its_own() {
        val ids = ContextDomain.CONTEXTS.map { it.id }.toSet()
        for ((id, _, config) in SearchDomain.WINDOW_CONTEXTS) {
            assertTrue(id in ids, "$id is listed with the contexts")
            assertEquals(id, config.context, "$id's default says it")
        }
        assertEquals(SearchDomain.CALENDAR_ADD_CONTEXT, SearchDomain.calendarAddConfig(1L).context)
        assertEquals(SearchDomain.NOTIFICATIONS_CONTEXT, SearchDomain.notificationsConfig(1L).context)
        assertEquals(SearchDomain.PARENTS_LIST_CONTEXT, SearchDomain.parentsListConfig(listOf("Task/a")).context)
        assertEquals(SearchDomain.CONFIGURATION_TREE_CONTEXT, SearchDomain.CONFIGURATION_SEARCH.context)
    }

    @Test
    fun a_window_opens_with_its_context_default_as_the_account_changed_it() {
        // The History window's default changed: every unit dated on a Sunday.
        val defaults = changed(SearchDomain.HISTORY_CONTEXT) { it.copy(filters = it.filters.copy(datedOnDays = setOf(DayOfWeek.SUNDAY))) }
        val opened = ContextDomain.opened(defaults, SearchDomain.contextDefault(SearchDomain.HISTORY_CONTEXT))
        assertEquals(setOf(DayOfWeek.SUNDAY), opened.filters.datedOnDays)
        assertEquals(setOf(SearchDomain.Kind.HistoryUnit), opened.kinds, "the rest of the code's default kept")
        // Another context is not touched.
        assertEquals(SearchDomain.calendarAddConfig(5L), ContextDomain.opened(defaults, SearchDomain.calendarAddConfig(5L)))
    }

    @Test
    fun what_the_opening_says_is_kept_over_the_account_default() {
        // "add…" on the calendar: the account checked the alarms alone and turned "Match case" on.
        val defaults = changed(SearchDomain.CALENDAR_ADD_CONTEXT) {
            it.copy(kinds = setOf(SearchDomain.Kind.Alarm), filters = it.filters.copy(matchCase = true))
        }
        val opened = ContextDomain.opened(defaults, SearchDomain.calendarAddConfig(1234L))
        assertEquals(setOf(SearchDomain.Kind.Alarm), opened.kinds)
        assertTrue(opened.filters.matchCase)
        assertEquals(1234L, opened.filters.calendarAddAtMillis, "the right-click's instant is the opening's")
        assertEquals(1234L, opened.calendarClickMillis)
        assertTrue(opened.filters.calendarAddOn)
    }

    @Test
    fun a_named_drop_down_inherits_the_drop_downs_default_and_may_differ_from_it() {
        val types = ContextDomain.DROP_DOWN_PREFIX + SearchDomain.Setting.Types.name
        assertEquals(types, ContextDomain.menuContextId(SearchDomain.Setting.Types.name, rightClick = false))
        assertEquals(ContextDomain.DROP_DOWN, ContextDomain.menuContextId("no such drop-down", rightClick = false))
        // On for every drop-down: the Types field has it too.
        val all = changed(ContextDomain.DROP_DOWN) { it.copy(rightClickChecksAlone = true) }
        assertTrue(ContextDomain.configOf(all, types).rightClickChecksAlone)
        assertTrue(all.none { it.id == types }, "nothing stored for it")
        // On for the Types field alone.
        val one = changed(types) { it.copy(rightClickChecksAlone = true) }
        assertTrue(ContextDomain.configOf(one, types).rightClickChecksAlone)
        assertFalse(ContextDomain.configOf(one, ContextDomain.DROP_DOWN).rightClickChecksAlone)
        assertFalse(ContextDomain.configOf(one, ContextDomain.DROP_DOWN_PREFIX + "sort by").rightClickChecksAlone)
        // "State in each context" tells it apart.
        val states = ContextDomain.groups(one, SearchDomain.Setting.RightClickChecksAlone)
        assertEquals(listOf(types), states.single { it.on }.contexts.map { it.id })
    }

    @Test
    fun a_configuration_without_a_context_opens_as_it_is() {
        val defaults = changed(SearchDomain.HISTORY_CONTEXT) { it.copy(filters = it.filters.copy(matchCase = true)) }
        val plain = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Task))
        assertEquals(plain, ContextDomain.opened(defaults, plain))
        // And the context is saved with the window.
        val config = SearchDomain.contextDefault(SearchDomain.HISTORY_CONTEXT)
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        assertEquals(null, SearchDomain.Config.decode("{\"query\":\"\"}")!!.context)
    }
}
