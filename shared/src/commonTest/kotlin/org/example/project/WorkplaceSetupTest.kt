package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.WorkplaceDomain
import org.example.project.scheduler.domain.WorkplaceApp
import org.example.project.scheduler.domain.WorkplaceOs
import org.example.project.scheduler.domain.NewElementDefaults
import org.example.project.scheduler.domain.OpenExplorerWindow
import org.example.project.scheduler.domain.WorkplaceSetup
import org.example.project.scheduler.domain.WorkplaceStep
import org.example.project.scheduler.domain.WorkplaceWindow
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.SnapshotMerge

/**
 * User rule 2026-11-01: *"Add the workplace setup element type in the Search window, found by default in the element type
 * selector in the settings feature page. This element defines some windows to be open, the OS, and has a button to apply
 * them. For example, the user can define one to quickly open a file explorer window with two tabs on specific paths on
 * Windows 11."* The script is only ever read here: running it opens windows.
 */
class WorkplaceSetupTest {
    private val setup =
        WorkplaceSetup("workplace-1", "Code", WorkplaceOs.Windows11, listOf(WorkplaceWindow(listOf("C:\\code", "D:\\docs (old)"))))

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun main(state: SchedulerState) = state.histories.forCategory(HistoryCategory.Main).units

    @Test
    fun it_is_checked_by_default_on_the_settings_page_alone() {
        assertTrue(Kind.WorkplaceSetup in SearchDomain.pageDefaultConfig(SearchDomain.SETTINGS_PAGE).kinds)
        assertTrue(Kind.WorkplaceSetup in SearchDomain.kindsOfPages(setOf(SearchDomain.SETTINGS_PAGE)))
        assertFalse(Kind.WorkplaceSetup in SearchDomain.kindsOfPages(setOf("")))
        assertTrue(Kind.WorkplaceSetup in SearchDomain.CREATABLE)
    }

    @Test
    fun it_is_listed_with_what_it_opens() {
        val s = r(SchedulerState.empty(), SchedulerIntent.SetWorkplaceSetups(listOf(setup)))
        val row = SearchDomain.itemResults(s, Kind.WorkplaceSetup, "code").single()
        assertEquals("Code", row.name)
        assertEquals("Windows 11 · 1 window, 2 tabs", row.detail)
        // A setup with no name still has a row to find.
        val nameless = r(s, SchedulerIntent.SetWorkplaceSetups(listOf(WorkplaceSetup("workplace-2"))))
        assertEquals("Workplace setup", SearchDomain.itemResults(nameless, Kind.WorkplaceSetup, "").single().name)
    }

    @Test
    fun its_actions_are_a_name_an_os_windows_and_apply() {
        val actions = SearchDomain.addedActions("", setOf(Kind.WorkplaceSetup)).single { it.first == Kind.WorkplaceSetup }.second
        for (action in listOf(
            SearchDomain.AddedAction.WorkplaceTitle, SearchDomain.AddedAction.WorkplaceOsChoice, SearchDomain.AddedAction.WorkplaceWindows,
            SearchDomain.AddedAction.WorkplaceApply, SearchDomain.AddedAction.WorkplaceDuplicate, SearchDomain.AddedAction.WorkplaceDelete,
        )) {
            assertTrue(action in actions, "$action")
        }
    }

    @Test
    fun a_change_is_one_unit_undone_and_kept_and_a_payload_before_it_has_none() {
        val before = SchedulerState.empty()
        val s = r(before, SchedulerIntent.SetWorkplaceSetups(listOf(setup)))
        assertEquals(main(before).size + 1, main(s).size)
        assertEquals(emptyList(), r(s, SchedulerIntent.Undo).workplaceSetups)
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(listOf(setup), back.workplaceSetups)
        assertEquals(emptyList(), r(back, SchedulerIntent.Undo).workplaceSetups, "the unit survives the store")
        // Typed: one unit for the run of keystrokes.
        var typed = s
        for (text in listOf("C:\\a", "C:\\ab", "C:\\abc")) {
            typed = r(typed, SchedulerIntent.SetWorkplaceSetups(WorkplaceDomain.withWindows(typed.workplaceSetups, setup.id, listOf(WorkplaceWindow(listOf(text)))), "field"))
        }
        assertEquals(main(s).size + 1, main(typed).size)
        // The shape before the field existed.
        val older = SchedulerStateCodec.encode(SchedulerState.empty())
        assertFalse("workplaceSetups" in older, "none written: the older shape")
        assertEquals(emptyList(), SchedulerStateCodec.decode(older)!!.workplaceSetups)
    }

    @Test
    fun an_os_this_build_does_not_know_reads_as_one_it_does() {
        val payload = SchedulerStateCodec.encode(SchedulerState.empty().copy(workplaceSetups = listOf(setup))).replace("\"Windows11\"", "\"Plan9\"")
        assertEquals(WorkplaceOs.Windows11, SchedulerStateCodec.decode(payload)!!.workplaceSetups.single().os)
    }

    @Test
    fun it_is_applied_on_its_own_os_with_a_path() {
        assertTrue(WorkplaceDomain.canApply(setup, WorkplaceOs.Windows11))
        assertFalse(WorkplaceDomain.canApply(setup, null), "a device that applies none")
        assertFalse(WorkplaceDomain.canApply(WorkplaceSetup("x"), WorkplaceOs.Windows11), "no path")
    }

    @Test
    fun a_window_opens_on_its_first_path_and_the_others_are_typed_as_tabs() {
        val script = WorkplaceDomain.windows11Script(setup)
        assertTrue("Open-ExplorerWindow 'C:\\code' @('D:\\docs {(}old{)}')" in script, script)
        assertEquals(1, Regex("""Open-ExplorerWindow '""").findAll(script).count())
        // Two windows, a blank tab and an empty window left out.
        val two = setup.copy(windows = listOf(WorkplaceWindow(listOf("A:\\", " ")), WorkplaceWindow(listOf("")), WorkplaceWindow(listOf("B:\\"))))
        assertEquals(2, Regex("""Open-ExplorerWindow '""").findAll(WorkplaceDomain.windows11Script(two)).count())
    }

    @Test
    fun no_key_is_sent_unless_the_window_is_in_front() {
        val script = WorkplaceDomain.windows11Script(setup)
        val send = script.substringAfter("function Send-Keys").substringBefore("function Open-ExplorerWindow")
        assertTrue(send.indexOf("Assert-Foreground") in 0 until send.indexOf("SendWait"), "checked before the keys, every time")
        assertTrue("throw" in script.substringAfter("function Assert-Foreground").substringBefore("function Send-Keys"))
    }

    @Test
    fun a_path_is_text_whatever_it_holds() {
        assertEquals("{+}{^}{%}{~}{(}{)}{{}{}}{[}{]}", WorkplaceDomain.sendKeysText("+^%~(){}[]"))
        assertEquals("'it''s'", WorkplaceDomain.powerShellQuoted("it's"))
        val script = WorkplaceDomain.windows11Script(WorkplaceSetup("x", windows = listOf(WorkplaceWindow(listOf("C:\\it's'; Remove-Item x #")))))
        assertTrue("'C:\\it''s''; Remove-Item x #'" in script, "closed by nothing: it stays one string")
    }

    private val required = WorkplaceSetup("w", windows = listOf(WorkplaceWindow(listOf("C:\\a", "C:\\b"))))

    private fun window(handle: Long, vararg paths: String) = OpenExplorerWindow(handle, paths.toList())

    @Test
    fun nothing_is_opened_when_a_window_holds_it_all() {
        assertEquals(emptyList(), WorkplaceDomain.plan(required, listOf(window(1, "C:\\b", "D:\\other", "C:\\a"))))
        // However it was typed.
        assertEquals(emptyList(), WorkplaceDomain.plan(required, listOf(window(1, "c:/A/", "C:\\B\\"))))
        // Before a window holding fewer of them.
        assertEquals(emptyList(), WorkplaceDomain.plan(required, listOf(window(1, "C:\\a"), window(2, "C:\\a", "C:\\b"))))
    }

    @Test
    fun only_the_missing_tabs_join_the_window_holding_one() {
        assertEquals(
            listOf(WorkplaceStep(7, listOf("C:\\b"))),
            WorkplaceDomain.plan(required, listOf(window(3, "D:\\other"), window(7, "C:\\a"))),
        )
    }

    @Test
    fun the_window_holding_the_fewest_takes_them_the_first_on_a_tie() {
        val three = WorkplaceSetup("w", windows = listOf(WorkplaceWindow(listOf("C:\\a", "C:\\b", "C:\\c"))))
        assertEquals(
            listOf(WorkplaceStep(2, listOf("C:\\b", "C:\\c"))),
            WorkplaceDomain.plan(three, listOf(window(1, "C:\\a", "C:\\b"), window(2, "C:\\a"))),
        )
        assertEquals(
            listOf(WorkplaceStep(1, listOf("C:\\b", "C:\\c"))),
            WorkplaceDomain.plan(three, listOf(window(1, "C:\\a"), window(2, "C:\\a"))),
        )
    }

    @Test
    fun a_new_window_opens_where_none_holds_any() {
        assertEquals(listOf(WorkplaceStep(null, listOf("C:\\a", "C:\\b"))), WorkplaceDomain.plan(required, listOf(window(1, "D:\\other"))))
    }

    @Test
    fun an_open_window_serves_one_window_of_the_setup() {
        val two = WorkplaceSetup("w", windows = listOf(WorkplaceWindow(listOf("C:\\a")), WorkplaceWindow(listOf("C:\\a", "C:\\b"))))
        assertEquals(listOf(WorkplaceStep(null, listOf("C:\\a", "C:\\b"))), WorkplaceDomain.plan(two, listOf(window(1, "C:\\a"))))
    }

    @Test
    fun an_existing_window_takes_its_tabs_typed_and_opens_nothing() {
        val script = WorkplaceDomain.windows11Script(listOf(WorkplaceStep(7, listOf("D:\\docs (old)"))))
        assertTrue("Add-ExplorerTabs 7 @('D:\\docs {(}old{)}')" in script, script)
        assertFalse("\nOpen-ExplorerWindow '" in script)
    }

    @Test
    fun the_listing_is_read_window_by_window() {
        assertEquals(
            listOf(window(394168, "C:\\a b", "C:\\c"), window(5, "D:\\é")),
            WorkplaceDomain.openWindows("394168\tC:\\a b\r\n5\tD:\\é\r\n394168\tC:\\c\r\n\r\nnoise\r\n"),
        )
    }

    private val code = WorkplaceSetup("c", "Code", windows = listOf(WorkplaceWindow(listOf("C:\\it's"), WorkplaceApp.VsCode)))

    @Test
    fun a_vs_code_window_opens_on_its_folder_and_types_nothing() {
        val steps = WorkplaceDomain.plan(code, listOf(window(1, "C:\\it's")))
        assertEquals(listOf(WorkplaceStep(null, listOf("C:\\it's"), WorkplaceApp.VsCode)), steps, "VS Code keeps to one window on a folder itself")
        val script = WorkplaceDomain.windows11Script(steps)
        assertTrue("\nOpen-VsCode 'C:\\it''s'\n" in script, script)
        assertFalse("\nOpen-ExplorerWindow '" in script)
        // The name of its function goes to the file explorer's rule neither: no path is typed for it.
        assertFalse("Send-Keys" in script.substringAfter("function Open-VsCode").substringBefore("function Add-Tabs"))
        assertTrue(WorkplaceDomain.canApply(code, WorkplaceOs.Windows11))
    }

    @Test
    fun both_kinds_of_window_stand_in_one_setup() {
        val both = code.copy(windows = setup.windows + code.windows + WorkplaceWindow(listOf(" "), WorkplaceApp.VsCode))
        assertEquals(
            listOf(WorkplaceStep(null, listOf("C:\\code", "D:\\docs (old)")), WorkplaceStep(null, listOf("C:\\it's"), WorkplaceApp.VsCode)),
            WorkplaceDomain.plan(both, emptyList()),
        )
        assertEquals("Windows 11 · 1 window, 2 tabs, 1 VS Code window", WorkplaceDomain.detail(both))
        assertEquals("Windows 11 · 1 VS Code window", WorkplaceDomain.detail(code))
        assertEquals("Windows 11 · 0 windows, 0 tabs", WorkplaceDomain.detail(WorkplaceSetup("x")))
    }

    @Test
    fun a_window_stored_before_vs_code_is_a_file_explorer_and_vs_code_is_kept() {
        val written = SchedulerStateCodec.encode(SchedulerState.empty().copy(workplaceSetups = listOf(setup, code)))
        assertEquals(listOf(setup, code), SchedulerStateCodec.decode(written)!!.workplaceSetups)
        val older = written.replace(",\"app\":\"FileExplorer\"", "").replace("\"app\":\"FileExplorer\",", "")
        assertFalse("FileExplorer" in older, "the shape before the field existed")
        assertEquals(WorkplaceApp.FileExplorer, SchedulerStateCodec.decode(older)!!.workplaceSetups.first().windows.single().app)
        val later = written.replace("\"VsCode\"", "\"Emacs\"")
        assertEquals(WorkplaceApp.FileExplorer, SchedulerStateCodec.decode(later)!!.workplaceSetups.last().windows.single().app)
    }

    private fun creationRow(kind: Kind) = SearchDomain.ItemResult(Kind.Creation, kind.name, "New " + kind.label, "")

    private fun groups(added: List<SearchDomain.Result>) =
        SearchDomain.actionGroups(
            SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added), added,
        )

    private val defaultActions = listOf(SearchDomain.AddedAction.WorkplaceOsChoice, SearchDomain.AddedAction.WorkplaceWindows)

    @Test
    fun its_default_configuration_is_listed_like_the_other_kinds() {
        val row = groups(listOf(creationRow(Kind.WorkplaceSetup))).single()
        assertTrue(row.actions.containsAll(defaultActions + SearchDomain.AddedAction.WorkplaceNew), "${row.actions}")
        assertFalse(SearchDomain.AddedAction.WorkplaceApply in row.actions, "no setup to apply")
        // Inside the group of one setup, and of several.
        val item = SearchDomain.ItemResult(Kind.WorkplaceSetup, "a", "a", "")
        for (added in listOf(listOf(item), listOf(item, item.copy(id = "b")))) {
            val inner = groups(added).flatMap { it.inner }.single { it.id == "default/WorkplaceSetup" }
            assertEquals(defaultActions, inner.actions)
        }
    }

    @Test
    fun a_new_setup_starts_from_it_with_a_name_of_its_own() {
        val windows = listOf(WorkplaceWindow(listOf("C:\\code"), WorkplaceApp.VsCode))
        val s = r(SchedulerState.empty(), SchedulerIntent.SetNewWorkplaceDefaults(WorkplaceSetup("x", "Named", windows = windows)))
        assertEquals(WorkplaceSetup("", "", windows = windows), s.newWorkplaceDefaults)
        assertEquals(WorkplaceSetup("workplace-1", "", windows = windows), NewElementDefaults.newWorkplaceSetup(s.newWorkplaceDefaults, "workplace-1"))
        assertEquals(NewElementDefaults.WORKPLACE, r(s, SchedulerIntent.Undo).newWorkplaceDefaults, "a unit, as the other defaults")
    }

    @Test
    fun an_edit_reaches_the_default_and_the_added_setups_each_their_own_way() {
        val s = SchedulerState.empty().copy(workplaceSetups = listOf(setup))
        val added = listOf(creationRow(Kind.WorkplaceSetup), SearchDomain.ItemResult(Kind.WorkplaceSetup, setup.id, "Code", ""))
        val listed = SearchDomain.addedWorkplaceSetupsOrDefault(s, added)
        assertEquals(listOf(SearchDomain.DEFAULT_CONFIGURATION_ID, setup.id), listed.map { it.id })
        val windows = listOf(WorkplaceWindow(listOf("D:\\x")))
        val next = SearchDomain.workplaceEdits(s, listed.mapTo(HashSet()) { it.id }) { it.copy(windows = windows) }.fold(s, ::r)
        assertEquals(windows, next.newWorkplaceDefaults.windows)
        assertEquals(windows, next.workplaceSetups.single().windows)
        assertEquals(1, next.workplaceSetups.size, "the default is no row of the list")
    }

    @Test
    fun its_default_survives_the_store_and_a_payload_before_it_has_the_built_in_one() {
        val untouched = SchedulerStateCodec.encode(SchedulerState.empty())
        assertFalse("newWorkplaceDefaults" in untouched, "an untouched default writes no field: the older shape")
        assertEquals(NewElementDefaults.WORKPLACE, SchedulerStateCodec.decode(untouched)!!.newWorkplaceDefaults)
        val s = r(SchedulerState.empty(), SchedulerIntent.SetNewWorkplaceDefaults(WorkplaceSetup("", windows = listOf(WorkplaceWindow(listOf("C:\\a"))))))
        assertEquals(s.newWorkplaceDefaults, SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!.newWorkplaceDefaults)
    }

    @Test
    fun two_devices_keep_a_default_only_one_changed_through_a_merge() {
        val base = SchedulerState.empty()
        val local = r(base, SchedulerIntent.SetNewWorkplaceDefaults(WorkplaceSetup("", windows = listOf(WorkplaceWindow(listOf("C:\\a"))))))
        val merged = SnapshotMerge.merge(SchedulerStateCodec.encodeSnapshot(base), SchedulerStateCodec.encodeSnapshot(local), SchedulerStateCodec.encodeSnapshot(base))!!
        assertEquals(local.newWorkplaceDefaults, SchedulerStateCodec.decodeSnapshot(merged)!!.newWorkplaceDefaults)
    }

    /** The audit of 2026-11-02: what each creatable kind's row offers, a task tree's and a window's having no action of theirs. */
    @Test
    fun every_creatable_kind_with_a_new_offers_it_on_its_row() {
        for (kind in SearchDomain.CREATABLE - Kind.TaskTree - Kind.Window) {
            val actions = groups(listOf(creationRow(kind))).single().actions
            assertTrue(actions.any { it.section == kind && it.label == "New" }, "$kind: $actions")
        }
        val timer = groups(listOf(creationRow(Kind.Timer))).single().actions
        assertTrue(timer.containsAll(listOf(SearchDomain.AddedAction.TimerDuration, SearchDomain.AddedAction.TimerAlert)), "$timer")
        assertFalse(SearchDomain.AddedAction.TimerDelete in timer)
    }

    @Test
    fun an_id_is_new() {
        assertEquals("workplace-3", WorkplaceDomain.mintId(listOf("workplace-1", "workplace-2")))
    }

    @Test
    fun two_devices_keep_each_others_setups_through_a_merge() {
        val base = SchedulerStateCodec.encodeSnapshot(SchedulerState.empty())
        val local = SchedulerStateCodec.encodeSnapshot(SchedulerState.empty().copy(workplaceSetups = listOf(setup)))
        val remote = SchedulerStateCodec.encodeSnapshot(SchedulerState.empty().copy(workplaceSetups = listOf(WorkplaceSetup("workplace-9", "Other"))))
        val merged = SchedulerStateCodec.decodeSnapshot(SnapshotMerge.merge(base, local, remote)!!)!!
        assertEquals(setOf("workplace-1", "workplace-9"), merged.workplaceSetups.map { it.id }.toSet())
    }
}
