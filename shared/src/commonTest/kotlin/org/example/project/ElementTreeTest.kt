package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementLink
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.WorkplaceOs
import org.example.project.scheduler.domain.WorkplaceSetup
import org.example.project.scheduler.domain.WorkplaceWindow
import org.example.project.scheduler.model.Category
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CategoryKind
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.EntityRows
import org.example.project.scheduler.sync.SnapshotMerge
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.WindowSections
import org.example.project.ui.WorkplaceRuns

/**
 * User rule 2026-11-02: *"Any element can have categories. Any element can be a parent/child of other elements. For example,
 * workspace setup A is a parent of workspace setup B, then applying A also applies B. Some things are not allowed, for
 * example a task element can only have task childs."*
 */
class ElementTreeTest {
    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun run(state: SchedulerState, intents: List<SchedulerIntent>) = intents.fold(state, ::r)

    private fun main(state: SchedulerState) = state.histories.forCategory(HistoryCategory.Main).units

    private val a = "Chrono/a"
    private val b = "Chrono/b"
    private val c = "Chrono/c"
    private val chronos = SchedulerState.empty().copy(chronos = listOf("a", "b", "c").map { ChronoEntry(id = it, label = it) })

    private fun setup(id: String, vararg paths: String) = WorkplaceSetup(id, id, WorkplaceOs.Windows11, listOf(WorkplaceWindow(paths.toList())))

    /** Tasks at the top of the tree, by titling the root list's last cell. */
    private fun withTasks(vararg titles: String): SchedulerState {
        var s = chronos
        for (title in titles) {
            val cell = s.lists[s.rootListId]!!.cellIds.last()
            s = r(s, SchedulerIntent.SetCellTitle(cell, title))
        }
        return s
    }

    private fun taskKey(s: SchedulerState, title: String) = SearchDomain.taskKey(s.tasks.values.first { it.title == title }.id)

    private fun link(s: SchedulerState, parent: String, child: String) = run(s, ElementTree.parentIntents(s, listOf(child), parent, on = true))

    @Test
    fun a_parent_is_set_and_taken_back_as_one_unit_each() {
        val before = main(chronos).size
        val s = link(chronos, a, b)
        assertEquals(listOf(b), ElementTree.childrenOf(s, a))
        assertEquals(listOf(a), ElementTree.parentsOf(s, b))
        assertEquals(before + 1, main(s).size)
        assertEquals(emptyList(), r(s, SchedulerIntent.Undo).elementLinks)
        val gone = run(s, ElementTree.parentIntents(s, listOf(b), a, on = false))
        assertEquals(emptyList(), ElementTree.childrenOf(gone, a))
    }

    @Test
    fun the_action_links_every_added_element_and_keeps_their_order() {
        val added = SearchDomain.resolve(chronos, listOf(b, c), { emptyMap() })
        val s = run(chronos, SearchDomain.addedIntents(chronos, added, SearchDomain.AddedCommand.ElementParent(a, on = true), 0L))
        assertEquals(listOf(b, c), ElementTree.childrenOf(s, a))
        // Whatever order the rows come back in.
        assertEquals(listOf(b, c), ElementTree.childrenOf(s.copy(elementLinks = s.elementLinks.reversed()), a))
    }

    /**
     * User rule 2026-11-12: *"The task element can only have other task elements as children, but in the action section,
     * any element can be linked to any element."* The tree holds tasks alone; any other element under a task is a link.
     */
    @Test
    fun any_element_is_linked_under_a_task_and_the_tree_holds_tasks_alone() {
        val s = withTasks("One", "Two")
        val one = taskKey(s, "One")
        val two = taskKey(s, "Two")
        assertTrue(ElementTree.canLink(s, one, a), "the actions section links any element under a task")
        assertTrue(ElementTree.canLink(s, one, two))
        assertTrue(ElementTree.canLink(s, a, one))
        val linked = link(s, one, a)
        assertEquals(listOf(ElementLink(ElementTree.linkId(one, a), one, a, 1.0)), linked.elementLinks)
        assertTrue(a in ElementTree.childrenOf(linked, one))
        assertEquals(listOf(one), ElementTree.parentsOf(linked, a))
        assertEquals(s.cells.size, linked.cells.size, "the tree holds no chrono")
        assertFalse(ElementTree.canLink(linked, a, one), "no loop through a link either")
        assertTrue(ElementTree.canUnlink(linked, one, a))
        assertEquals(emptyList(), run(linked, ElementTree.parentIntents(linked, listOf(a), one, on = false)).elementLinks)
    }

    @Test
    fun the_parent_index_is_parents_of_every_element() {
        val category = Category(CategoryId("health"), "Health")
        var s = withTasks("One", "Two").copy(categories = listOf(category))
        val one = taskKey(s, "One")
        val two = taskKey(s, "Two")
        s = link(link(link(link(s, one, two), one, a), ElementTree.categoryKey(category.id), b), ElementTree.categoryKey(category.id), two)
        val index = ElementTree.parentIndex(s)
        val keys = s.tasks.keys.map(SearchDomain::taskKey) + listOf(a, b, c)
        for (key in keys) assertEquals(ElementTree.parentsOf(s, key).toSet(), index[key].orEmpty(), key)
    }

    /** User rule 2026-11-12: *"The root task must be found when the search configurations are only tasks and must not have parents of type [task]."* */
    @Test
    fun the_root_task_is_found_by_a_task_search_with_no_task_parent() {
        val tasks = withTasks("One", "Two")
        val s = link(tasks, taskKey(tasks, "One"), taskKey(tasks, "Two"))
        val root = SearchDomain.taskKey(org.example.project.scheduler.model.WellKnownIds.ROOT_TASK)
        fun keys(filters: SearchDomain.Filters) = SearchDomain.results(s, setOf(Kind.Task), "", filters = filters).map(SearchDomain::keyOf)
        assertFalse(root in keys(SearchDomain.Filters()), "the root task is no result of an ordinary search")
        val tops = keys(SearchDomain.Filters(noParentOfKinds = setOf(Kind.Task)))
        assertTrue(root in tops)
        assertTrue(taskKey(s, "One") in tops)
        assertFalse(taskKey(s, "Two") in tops, "Two stands under One")
        // The filter about another kind keeps every task, and offers no root.
        assertFalse(root in keys(SearchDomain.Filters(noParentOfKinds = setOf(Kind.Chrono))))
        val linked = link(s, a, taskKey(s, "One"))
        assertFalse(taskKey(s, "One") in SearchDomain.results(linked, setOf(Kind.Task), "", filters = SearchDomain.Filters(noParentOfKinds = setOf(Kind.Chrono))).map(SearchDomain::keyOf))
    }

    @Test
    fun the_parent_filter_survives_the_store_and_an_older_configuration_has_none() {
        val config = SearchDomain.Config(filters = SearchDomain.Filters(noParentOfKinds = setOf(Kind.Task)))
        val back = SearchDomain.Config.decode(config.encode())!!
        assertEquals(setOf(Kind.Task), back.filters.noParentOfKinds)
        assertTrue(back.filters.isOn(SearchDomain.Setting.NoParentOfKinds))
        assertEquals(emptySet(), SearchDomain.Config.decode("{}")!!.filters.noParentOfKinds)
        assertEquals(emptySet(), SearchDomain.Config.decode("""{"noParentOfKinds":["Gone","Task"]}""")!!.filters.noParentOfKinds - Kind.Task)
    }

    @Test
    fun a_task_under_a_task_is_in_the_tree() {
        val s = withTasks("One", "Two")
        val one = taskKey(s, "One")
        val two = taskKey(s, "Two")
        val linked = link(s, one, two)
        assertEquals(emptyList(), linked.elementLinks, "no second copy of the tree")
        assertEquals(listOf(two), ElementTree.childrenOf(linked, one))
        assertTrue(one in ElementTree.parentsOf(linked, two))
        assertFalse(ElementTree.canLink(linked, two, one), "no loop through the tree either")
    }

    @Test
    fun nothing_is_its_own_parent_or_below_itself_and_a_creation_row_is_no_element() {
        val s = link(link(chronos, a, b), b, c)
        assertFalse(ElementTree.canLink(s, a, a))
        assertFalse(ElementTree.canLink(s, c, a), "a loop")
        assertFalse(ElementTree.canLink(s, a, b), "already there")
        assertFalse(ElementTree.canLink(s, "Creation/Timer", a))
        assertFalse(ElementTree.canLink(s, a, "Creation/Timer"))
        assertEquals(s, run(s, ElementTree.parentIntents(s, listOf(a), c, on = true)))
    }

    @Test
    fun several_parents_stand_and_a_loop_from_a_merge_ends() {
        val s = link(link(chronos, a, c), b, c)
        assertEquals(setOf(a, b), ElementTree.parentsOf(s, c).toSet())
        val looped = chronos.copy(elementLinks = listOf(ElementLink(ElementTree.linkId(a, b), a, b), ElementLink(ElementTree.linkId(b, a), b, a)))
        assertEquals(setOf(a, b), ElementTree.descendantsOf(looped, a).toSet())
    }

    @Test
    fun a_category_is_carried_by_any_element_and_a_task_keeps_its_own_list() {
        val category = Category(CategoryId("health"), "Health")
        val s = withTasks("One").copy(categories = listOf(category))
        val key = ElementTree.categoryKey(category.id)
        val one = taskKey(s, "One")
        val carried = run(s, ElementTree.parentIntents(s, listOf(a, one), key, on = true))
        assertEquals(listOf("Health"), ElementTree.categoryTitles(carried)[a])
        assertEquals(setOf(one, a), ElementTree.childrenOf(carried, key).toSet())
        assertEquals(listOf(ElementLink(ElementTree.linkId(key, a), key, a, 1.0)), carried.elementLinks, "the task's is no link")
        assertTrue(category.id in carried.tasks.values.first { it.title == "One" }.categoryIds)
        val back = run(carried, ElementTree.parentIntents(carried, listOf(a, one), key, on = false))
        assertEquals(emptyList(), ElementTree.childrenOf(back, key))
    }

    @Test
    fun a_task_cell_category_is_carried_by_no_task_but_links_any_other_element() {
        val category = Category(CategoryId("place"), "Place", kind = CategoryKind.TaskCell)
        val s = withTasks("One").copy(categories = listOf(category))
        assertFalse(ElementTree.canLink(s, ElementTree.categoryKey(category.id), taskKey(s, "One")), "one occurrence carries it, not the task")
        assertTrue(ElementTree.canLink(s, ElementTree.categoryKey(category.id), a), "any element can be linked to any element")
    }

    @Test
    fun a_link_to_what_is_gone_is_ignored_and_kept_for_an_undo() {
        val s = link(chronos, a, b)
        val without = s.copy(chronos = s.chronos.filterNot { it.id == "b" })
        assertEquals(emptyList(), SearchDomain.resolve(without, ElementTree.childrenOf(without, a), { emptyMap() }))
        assertEquals(1, without.elementLinks.size)
    }

    @Test
    fun the_links_survive_the_codec_and_the_merge_and_an_older_payload_has_none() {
        val s = link(chronos, a, b)
        val back = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!
        assertEquals(s.elementLinks, back.elementLinks)
        assertEquals(emptyList(), r(back, SchedulerIntent.Undo).elementLinks, "the unit survives the store")
        assertEquals(emptyList(), SchedulerStateCodec.decode(SchedulerStateCodec.encode(chronos))!!.elementLinks)

        val base = SchedulerStateCodec.encodeSnapshot(chronos)
        val here = SchedulerStateCodec.encodeSnapshot(link(chronos, a, b))
        val there = SchedulerStateCodec.encodeSnapshot(link(chronos, a, c))
        val merged = SchedulerStateCodec.decodeSnapshot(SnapshotMerge.merge(base, here, there)!!)!!
        assertEquals(setOf(b, c), ElementTree.childrenOf(merged, a).toSet())

        val rows = EntityRows.split(SchedulerStateCodec.encodeSnapshot(link(s, a, c)).statePayload)
        assertEquals(2, rows.keys.count { it.kind == "elementLinks" }, "one server row per link")
    }

    @Test
    fun applying_a_setup_applies_what_stands_under_it_one_after_the_other() {
        val s = SchedulerState.empty().copy(workplaceSetups = listOf(setup("a", "C:\\a"), setup("b", "C:\\b"), setup("c", "C:\\c"), setup("empty")))
        val linked =
            link(link(link(s, "WorkplaceSetup/a", "WorkplaceSetup/empty"), "WorkplaceSetup/empty", "WorkplaceSetup/b"), "WorkplaceSetup/b", "WorkplaceSetup/c")
        val family = ElementTree.workplaceFamily(linked, "a")
        assertEquals(listOf("a", "empty", "b", "c"), family.map { it.id })

        val pending = ArrayList<Pair<String, (String?) -> Unit>>()
        val runs = WorkplaceRuns({ setup, done -> pending += setup.id to done }, { WorkplaceOs.Windows11 })
        runs.applyAll(family)
        assertEquals(listOf("a"), pending.map { it.first }, "the next waits for this one")
        assertTrue(runs.isRunning("c"), "a second press finds them all running")
        pending[0].second("failed")
        assertEquals(listOf("a", "b"), pending.map { it.first }, "a setup with no path is passed, a failure stops nothing")
        assertEquals("failed", runs.errorOf("a"))
        pending[1].second(null)
        pending[2].second(null)
        assertEquals(listOf("a", "b", "c"), pending.map { it.first })
        assertFalse(runs.isRunning("c"))
    }

    @Test
    fun the_window_from_a_blank_field_says_where_it_comes_from() {
        val config = ElementTree.childSearchConfig(a, "")
        assertEquals(setOf(Kind.Chrono), config.kinds)
        assertEquals(a, config.elementSource)
        assertEquals(a, SearchDomain.Config.decode(config.encode())!!.elementSource)
        assertEquals(null, SearchDomain.Config.decode("{}")!!.elementSource)
        assertEquals(SearchDomain.pageDefaultConfig("").kinds, ElementTree.childSearchConfig("Category/health", "").kinds, "a category has children of every kind")
        assertEquals(SearchDomain.SETTINGS_PAGE, ElementTree.childSearchConfig("WorkplaceSetup/a", SearchDomain.SETTINGS_PAGE).page)
    }

    @Test
    fun the_actions_are_every_elements_and_no_creation_rows() {
        fun listed(state: SchedulerState, vararg keys: String): Set<AddedAction> {
            val added = SearchDomain.resolve(state, keys.toList(), { emptyMap() })
            return SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet()), added).flatMap { it.second }.toSet()
        }
        assertTrue(listed(chronos, a).containsAll(SearchDomain.ELEMENT_ACTIONS))
        val tasks = withTasks("One")
        val onlyTask = listed(tasks, taskKey(tasks, "One"))
        assertTrue(AddedAction.ElementParents in onlyTask)
        assertFalse(AddedAction.ElementAddParent in onlyTask, "retired: a child row of \"Parents\"")
        assertFalse(AddedAction.ElementAddCategory in onlyTask, "a task has its own")
        assertTrue(listed(tasks, taskKey(tasks, "One"), a).containsAll(SearchDomain.ELEMENT_CATEGORY_ACTIONS))
        val creation =
            SearchDomain.actionsFor(
                SearchDomain.addedActions("", setOf(Kind.Chrono)),
                listOf(SearchDomain.ItemResult(Kind.Creation, Kind.Chrono.name, "New chrono", "")),
            )
        assertTrue(creation.flatMap { it.second }.none { it in SearchDomain.ELEMENT_ACTIONS })
    }

    @Test
    fun the_open_rows_are_kept_and_an_older_row_has_none() {
        val kept = mapOf("Search" to WindowSections(expandedElements = listOf("result/Timer/a")))
        assertEquals(kept, CustomMenuButtons.decodeWindowSections(CustomMenuButtons.encodeWindowSections(kept)))
        val older = CustomMenuButtons.decodeWindowSections("""{"sections":{"Search":{"splits":[0.5,0.5]}}}""")
        assertEquals(emptyList(), older.getValue("Search").expandedElements)
    }

    @Test
    fun a_titled_task_always_has_the_blank_field_an_arrow_opens() {
        val s = withTasks("One")
        val task = s.tasks.values.first { it.title == "One" }
        assertTrue(s.lists[task.childListId]?.cellIds?.isNotEmpty() == true, "its list holds the blank cell")
    }

    /**
     * User rule 2026-11-05: a prompt's children are its reasoning chain and its answer; a reasoning chain's is its final
     * answer. Read off the logs as [ClaudeLogCatalog.entries] folds them, never a link of the state.
     */
    @Test
    fun aClaudePromptsChildrenAreItsReasoningChainAndAnswer() {
        val session = org.example.project.claudelogs.ClaudeLogSession(
            "C:/logs/p/s1.jsonl", "s1", "p", null, null,
            listOf(
                claudeEntry(org.example.project.claudelogs.ClaudeLogKind.Prompt, "first", 0),
                // Two steps and the last text: the steps fold into one chain, the last text is the answer.
                claudeEntry(org.example.project.claudelogs.ClaudeLogKind.Reasoning, "thinking", 1),
                claudeEntry(org.example.project.claudelogs.ClaudeLogKind.Answer, "a step", 2),
                claudeEntry(org.example.project.claudelogs.ClaudeLogKind.Answer, "the answer", 3),
                claudeEntry(org.example.project.claudelogs.ClaudeLogKind.Prompt, "second", 4),
                claudeEntry(org.example.project.claudelogs.ClaudeLogKind.Answer, "only an answer", 5),
            ),
        )
        val logs = org.example.project.claudelogs.ClaudeLogCatalog("dir", listOf(session))
        val s = SchedulerState.empty()
        fun key(kind: Kind, text: String): String {
            val (sess, entry) = logs.entries.single { it.second.text == text }
            return kind.name + "/" + org.example.project.claudelogs.ClaudeLogCatalog.idOf(sess, entry)
        }
        val first = key(Kind.ClaudePrompt, "first")
        val chain = key(Kind.ClaudeReasoning, "thinking\n\na step")
        val answer = key(Kind.ClaudeAnswer, "the answer")
        val second = key(Kind.ClaudePrompt, "second")
        val onlyAnswer = key(Kind.ClaudeAnswer, "only an answer")

        assertEquals(listOf(chain, answer), ElementTree.childrenOf(s, first, logs))
        assertEquals(listOf(onlyAnswer), ElementTree.childrenOf(s, second, logs))
        assertEquals(listOf(answer), ElementTree.childrenOf(s, chain, logs))
        assertEquals(emptyList(), ElementTree.childrenOf(s, answer, logs))
        // The rows resolve, and the logs' children have no ✕: they are read off the logs.
        assertEquals(listOf(chain, answer), SearchDomain.resolve(s, ElementTree.childrenOf(s, first, logs), { emptyMap() }, claudeLogs = logs).map(SearchDomain::keyOf))
        assertFalse(ElementTree.canUnlink(s, first, answer, logs))
        // Nothing of it is stored: the state holds no link.
        assertTrue(s.elementLinks.isEmpty())
        // While the last turn goes on, it has no answer: all it wrote so far is its chain.
        val working = org.example.project.claudelogs.ClaudeLogCatalog(
            "dir", listOf(session), working = setOf(org.example.project.claudelogs.ClaudeLogArchive.sessionName(session)),
        )
        assertEquals(1, ElementTree.childrenOf(s, second, working).size)
        assertTrue(ElementTree.childrenOf(s, second, working).single().startsWith(Kind.ClaudeReasoning.name + "/"))
    }

    /**
     * User rule 2026-11-12: *"In any element, there must be a parent action, and among the children there must be the [list
     * of parents] and a field to add new parents. Under [list of parents], there is a Search window … with a search
     * configuration element set to on, which makes every element appear with a button to drop them."*
     */
    @Test
    fun the_list_of_parents_is_a_search_of_the_parents_each_with_its_drop_button() {
        val tasks = withTasks("One")
        val one = taskKey(tasks, "One")
        val s = link(link(link(tasks, a, c), b, c), one, c)
        val config = SearchDomain.parentsListConfig(listOf(c))
        assertTrue(config.dropButtons, "the drop buttons are on")
        assertEquals(ElementTree.PARENT_KINDS.toSet(), config.kinds)
        assertEquals(setOf(a, b, one), listedIn(s, config))
        // The user's own question is kept; the parents asked about follow the added elements.
        val typed = SearchDomain.parentsListConfig(listOf(c), config.copy(query = "One"))
        assertEquals(setOf(one), listedIn(s, typed))
        assertEquals(emptySet(), listedIn(s, SearchDomain.parentsListConfig(listOf(a), typed)), "a has no parent")
        // A drop is the parent action's own "no longer a parent".
        val dropped = run(s, ElementTree.parentIntents(s, listOf(c), a, on = false))
        assertEquals(setOf(b, one), listedIn(dropped, config))
    }

    /** User rule 2026-11-12: "Add a parent" opens a headless Search window *"with the search configuration of only tasks"*. */
    @Test
    fun add_a_parent_searches_the_tasks_alone() {
        val tasks = withTasks("One")
        assertEquals(setOf(Kind.Task), SearchDomain.ADD_PARENT_SEARCH.searchedKinds)
        val config = SearchDomain.ADD_PARENT_SEARCH
        val found = SearchDomain.results(tasks, config.searchedKinds, "", filters = config.filters, sorts = config.sorts)
        // As the window's picker shows them: the tasks that can be a parent, no creation row.
        val shown = SearchDomain.pickerRows(tasks, found, tasks.tasks.keys, creation = false).map(SearchDomain::keyOf)
        assertEquals(listOf(taskKey(tasks, "One")), shown, "no chrono, no root")
    }

    private fun listedIn(state: SchedulerState, config: SearchDomain.Config) =
        SearchDomain.results(state, config.searchedKinds, config.query, filters = config.filters).map(SearchDomain::keyOf).toSet()

    @Test
    fun the_parents_rows_are_children_of_the_action_and_keep_their_arrows() {
        val added = SearchDomain.resolve(chronos, listOf(a), { emptyMap() })
        val groups = SearchDomain.actionGroups(SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet()), added), added)
        val listed = SearchDomain.listedGroupIds(groups)
        val group = groups.first { AddedAction.ElementParents in it.actions }
        val micro = SearchDomain.microGroupId(group, AddedAction.ElementParents)
        val list = SearchDomain.actionChildId(micro, SearchDomain.ParentsChild.List)
        assertTrue(list in listed && SearchDomain.actionChildId(micro, SearchDomain.ParentsChild.Add) in listed)
        val config = SearchDomain.Config(toggledActionRows = setOf(list))
        assertTrue(SearchDomain.isActionRowOpen(config, list, top = false))
        // Another row's arrow keeps it open.
        assertTrue(list in SearchDomain.withActionGroupToggled(config, micro, listed).toggledActionRows)
    }

    @Test
    fun the_drop_buttons_and_the_parents_asked_survive_the_store_and_an_older_configuration_has_neither() {
        val config = SearchDomain.parentsListConfig(listOf(a))
        val back = SearchDomain.Config.decode(config.encode())!!
        assertTrue(back.dropButtons)
        assertEquals(setOf(a), back.filters.parentsOf)
        assertTrue(back.filters.isOn(SearchDomain.Setting.ParentsOf))
        val older = SearchDomain.Config.decode("{}")!!
        assertFalse(older.dropButtons)
        assertEquals(emptySet(), older.filters.parentsOf)
    }

    private fun claudeEntry(kind: org.example.project.claudelogs.ClaudeLogKind, text: String, ordinal: Int) =
        org.example.project.claudelogs.ClaudeLogEntry(kind, text, 1_000L * (ordinal + 1), null, ordinal)
}
