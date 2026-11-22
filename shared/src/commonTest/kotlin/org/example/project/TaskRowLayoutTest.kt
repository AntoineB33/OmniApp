package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.domain.SearchDomain.SettingRole
import org.example.project.scheduler.domain.SearchDomain.TaskRowPart

/**
 * User rule 2026-11-22: a search configuration, **"Row layout"**, says what a task row shows and in which order — e.g. the
 * expansion arrow, then the priority relative to the parent, then the title uncut, the list scrolling sideways for a wide
 * title. Empty is the default row.
 */
class TaskRowLayoutTest {
    @Test
    fun a_layout_survives_the_store_and_an_older_configuration_has_the_default_row() {
        val config = SearchDomain.Config(taskRowLayout = TaskRowPart.ARROW_RELATIVE_TITLE)
        val back = SearchDomain.Config.decode(config.encode())!!
        assertEquals(listOf(TaskRowPart.Arrow, TaskRowPart.RelativePriority, TaskRowPart.Title), back.taskRowLayout)
        // Written by a build before the setting: no field, the default row.
        assertEquals(emptyList(), SearchDomain.Config.decode("{}")!!.taskRowLayout)
    }

    @Test
    fun a_part_this_build_does_not_know_is_skipped_and_a_repeated_one_kept_once() {
        assertEquals(
            listOf(TaskRowPart.Title, TaskRowPart.Arrow),
            TaskRowPart.decode(listOf("Title", "Sparkline", "Arrow", "Title")),
        )
    }

    @Test
    fun parts_are_added_at_the_end_taken_out_and_moved() {
        val added = SearchDomain.withTaskRowPart(TaskRowPart.ARROW_RELATIVE_TITLE, TaskRowPart.Path, on = true)
        assertEquals(listOf(TaskRowPart.Arrow, TaskRowPart.RelativePriority, TaskRowPart.Title, TaskRowPart.Path), added)
        assertEquals(added, SearchDomain.withTaskRowPart(added, TaskRowPart.Path, on = true), "a part is in the row once")
        assertEquals(TaskRowPart.ARROW_RELATIVE_TITLE, SearchDomain.withTaskRowPart(added, TaskRowPart.Path, on = false))
        assertEquals(
            listOf(TaskRowPart.Path, TaskRowPart.Arrow, TaskRowPart.RelativePriority, TaskRowPart.Title),
            SearchDomain.movedTaskRowPart(added, from = 3, to = 0),
        )
        assertEquals(added, SearchDomain.movedTaskRowPart(added, from = 9, to = 0), "nothing at that place: unchanged")
    }

    @Test
    fun it_is_a_display_setting_of_the_task_type_on_only_when_a_layout_is_given() {
        assertEquals(SettingRole.Display, SearchDomain.roleOf(Setting.TaskRowLayout))
        assertEquals(SearchDomain.Kind.Task, Setting.TaskRowLayout.section)
        assertTrue(Setting.TaskRowLayout !in SearchDomain.activeSettings(SearchDomain.Config()))
        assertTrue(Setting.TaskRowLayout in SearchDomain.activeSettings(SearchDomain.Config(taskRowLayout = listOf(TaskRowPart.Title))))
    }
}
