package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.state.NotificationSource
import org.example.project.scheduler.state.SchedulerState

/**
 * User 2026-11-27: *"A search configuration element that matches with everything shouldn't be put in 'Currently on' or
 * 'Default for this', right? Fix it and find other similar anomalies then fix them."* A check-box filter with every box
 * checked is none set (as the types, user rule 2026-11-26), and a part of "Where to search" that changes no match is off.
 */
class MatchesEverythingIsNotOnTest {
    private fun shown(config: SearchDomain.Config, label: String): List<Setting> =
        SearchDomain.configurationTree(config, opened = config).filterIsInstance<SearchDomain.ConfigurationCategory>()
            .singleOrNull { it.label == label }?.children.orEmpty().map { (it as SearchDomain.ConfigurationLeaf).setting }

    private fun assertNowhere(config: SearchDomain.Config, setting: Setting) {
        assertFalse(setting in SearchDomain.activeSettings(config), "$setting is not on")
        assertFalse(setting in shown(config, "Currently on"), "$setting not in Currently on")
        assertFalse(setting in shown(config, SearchDomain.DEFAULT_NODE_LABEL), "$setting not in Default for this")
    }

    private val all = SearchDomain.Config(kinds = Kind.entries.toSet())

    /** Every notification answers one source ("other" included): every source checked is none set. */
    @Test
    fun every_source_checked_is_none_set() {
        val full = SearchDomain.Filters(notificationSources = NotificationSource.entries.toSet())
        assertFalse(full.isOn(Setting.NotificationSourceSetting))
        assertEquals(null, full.onlyKinds, "it keeps no type alone")
        assertNowhere(all.copy(filters = full), Setting.NotificationSourceSetting)
        // Some sources checked, not all: it narrows, and says so.
        val some = all.copy(filters = SearchDomain.Filters(notificationSources = setOf(NotificationSource.Alarm)))
        assertTrue(Setting.NotificationSourceSetting in shown(some, "Currently on"))
    }

    /**
     * Not so where a row can answer no box: every weekday checked still keeps out a row with no date (user rule
     * 2026-11-27), an alarm that rings on no day, a unit that changed nothing, a window that lists nothing.
     */
    @Test
    fun every_box_checked_still_narrows_where_a_row_can_answer_none() {
        val everyDay = DayOfWeek.entries.toSet()
        assertTrue(SearchDomain.Filters(alarmDays = everyDay).isOn(Setting.AlarmDays))
        assertTrue(SearchDomain.Filters(datedOnDays = everyDay).isOn(Setting.DatedOnDays))
        val state = SchedulerState.empty().copy(alarms = listOf(AlarmEntry(id = "alarm-1", label = "never", days = emptySet())))
        val rows = SearchDomain.results(state, setOf(Kind.Alarm), "", filters = SearchDomain.Filters(alarmDays = everyDay))
        assertTrue(rows.none { (it as? SearchDomain.ItemResult)?.id == "alarm-1" })
    }

    @Test
    fun the_children_parts_are_off_while_the_children_are_not_searched() {
        val parts = SearchDomain.Filters().withSearches(Setting.ChildSearchInId, true).withSearches(Setting.ChildSearchInDescription, true)
        assertEquals(emptyList(), SearchDomain.whereSettingsOn(parts))
        assertNowhere(all.copy(filters = parts), Setting.ChildSearchInId)
        // Searched in none of their parts, the children change nothing either.
        val nothing = SearchDomain.Filters(searchChildren = true, childSearchIn = emptySet(), childDistance = 2)
        assertEquals(emptyList(), SearchDomain.whereSettingsOn(nothing))
        // Searched, they are on, and so are their parts set otherwise.
        assertEquals(
            listOf(Setting.SearchInChildren, Setting.ChildSearchInId, Setting.ChildSearchInDescription),
            SearchDomain.whereSettingsOn(parts.withSearches(Setting.SearchInChildren, true)),
        )
    }

    /** No window's code default holds a configuration that matches everything. */
    @Test
    fun no_context_default_lists_a_filter_that_is_not_on() {
        for ((id, _, config) in SearchDomain.WINDOW_CONTEXTS) {
            for (setting in SearchDomain.contextSettings(config)) {
                if (SearchDomain.roleOf(setting) != SearchDomain.SettingRole.Filter) continue
                if (setting in SearchDomain.WHERE_SETTINGS || setting == Setting.Types || setting == Setting.PageTypes) continue
                assertTrue(config.filters.isOn(setting), "$id: $setting")
            }
        }
    }
}
