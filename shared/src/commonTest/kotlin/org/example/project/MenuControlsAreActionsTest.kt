package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.ACTION_MENU_CONTROLS
import org.example.project.ui.MenuControl

/**
 * User rule 2026-11-04: *"Every button that can appear in the left-side menu are links to actions that can be found in
 * the action section of the Search window."* A window's button opens a window; every OTHER item — each control of the
 * menu ([MenuControl]) — is an action of the Search window, of the app setting it is about, found there by its name.
 * Until 2026-11-04 thirteen of the controls were not (Online, Look away now, the Supabase usage…).
 */
class MenuControlsAreActionsTest {
    @Test
    fun every_control_of_the_menu_is_an_action_of_the_search_window() {
        val linked = ACTION_MENU_CONTROLS.values.toSet()
        assertEquals(MenuControl.entries.toSet(), linked, "controls with no action: ${MenuControl.entries - linked}")
        assertEquals(ACTION_MENU_CONTROLS.size, linked.size, "one action per control")
    }

    @Test
    fun each_controls_action_is_found_in_the_action_section_where_its_setting_is_added() {
        val state = SchedulerState.empty()
        val settings = SearchDomain.results(state, setOf(Kind.AppSetting), "").filterIsInstance<SearchDomain.ItemResult>()
        for ((action, setting) in SearchDomain.APP_SETTING_CONTROL_ACTIONS) {
            val added = settings.filter { it.id == setting.name }
            assertEquals(1, added.size, "the setting ${setting.title} is an element of the Search window")
            // Found by its name in the action section, once its setting is among the added elements…
            val found = SearchDomain.actionsFor(SearchDomain.addedActions(action.label, Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
            assertTrue(found.any { (_, actions) -> action in actions }, "${action.label} for ${setting.title}")
            // …and not where another setting is: it acts on its own setting alone.
            val other = settings.filter { it.id != setting.name }.take(1)
            val elsewhere = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(other)), other)
            assertFalse(elsewhere.any { (_, actions) -> action in actions }, "${action.label} beside ${other.map { it.name }}")
        }
    }
}
