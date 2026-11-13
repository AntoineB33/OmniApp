package org.example.project

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.ElementLink
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.ACTIONS_SECTION_OWN
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.ArrowSearches
import org.example.project.ui.LocalArrowOwner
import org.example.project.ui.LocalArrowRoot
import org.example.project.ui.LocalArrowSearches
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow

/**
 * User rule 2026-11-13: a row of the actions section is added to the section's own list, and its action there shows its
 * **contexts and states** — *"if the same state … is set the same for the task elements A, B and C, then the context
 * element will have the values A, B and C, and the state element will be the exact 'list of parents' element the user
 * would find in the action section of A, B or C."*
 */
class ActionContextsTest {
    private fun key(id: String) = "Chrono/$id"

    /** a, b and c under p; d under q; e under nothing. */
    private val account: SchedulerState =
        SchedulerState.empty().copy(
            chronos = listOf("a", "b", "c", "d", "e", "p", "q").map { ChronoEntry(id = it, label = it) },
            elementLinks = listOf("p" to "a", "p" to "b", "p" to "c", "q" to "d").map { (parent, child) ->
                ElementLink(ElementTree.linkId(key(parent), key(child)), key(parent), key(child))
            },
        )

    private fun rows(vararg ids: String) = SearchDomain.resolve(account, ids.map(::key), { emptyMap() })

    @Test
    fun the_elements_sharing_a_state_are_one_context() {
        val contexts = SearchDomain.actionContexts(account, AddedAction.ElementParents, rows("a", "d", "b", "e", "c"))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d"), listOf("e")), contexts.map { members -> members.map { it.name } })
    }

    @Test
    fun an_action_about_the_set_has_one_context_and_an_unread_one_a_context_each() {
        val all = rows("a", "d", "e")
        assertEquals(1, SearchDomain.actionContexts(account, AddedAction.OpenEach, all).size)
        // Its state is not read yet: never shown as shared.
        assertEquals(3, SearchDomain.actionContexts(account, AddedAction.ChronoRun, all).size)
    }

    @Test
    fun an_added_action_has_its_contexts_and_none_of_an_elements_actions() {
        val added = listOf(SearchDomain.ItemResult(Kind.Action, "some/row", "Parents", ""))
        val actions = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
            .flatMap { it.second }
        assertTrue(AddedAction.ActionContexts in actions, actions.toString())
        assertFalse(AddedAction.ElementParents in actions, "an action row is no element of the account")
        // ...and an element's own list never offers it.
        val ofElements = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(rows("a"))), rows("a"))
            .flatMap { it.second }
        assertFalse(AddedAction.ActionContexts in ofElements)
        assertTrue(AddedAction.ElementParents in ofElements)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun actionRows(added: List<SearchDomain.Result>, config: SearchDomain.Config): List<SearchDomain.ActionListRow> {
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions(config.actionQuery, Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
        return SearchDomain.actionListRows(SearchDomain.shownActionGroups(sections, added, config, ClaudeLogCatalog.EMPTY), config)
    }

    /**
     * The whole use case, drawn: "Parents" of five elements added to the actions section's own list, the section's own
     * actions shown, "Contexts and states" open. Laid out without throwing; its picture differs from the plain section's.
     */
    @Test
    fun the_section_shows_the_contexts_of_an_action_added_to_its_own_list() {
        val elements = listOf("a", "b", "c", "d", "e").map(::key)
        val window = SearchDomain.Config(kinds = setOf(Kind.Chrono), added = elements, actionsExpanded = true)
        val parents = actionRows(rows("a", "b", "c", "d", "e"), window).first { it.action == AddedAction.ElementParents && it.group.members.size == 5 }
        val ownAdded = listOf(SearchDomain.ItemResult(Kind.Action, parents.key, parents.title, parents.detail))
        val contextsRow = actionRows(ownAdded, window).first { it.action == AddedAction.ActionContexts }
        val own = ACTIONS_SECTION_OWN.copy(added = listOf(Kind.Action.name + "/" + parents.key), toggledActionRows = setOf(contextsRow.id))
        val plain = draw(window)
        val shown = draw(window.copy(inside = mapOf("/actions" to own.encode())))
        assertTrue(!plain.contentEquals(shown), "the section's own actions are drawn")
    }

    private fun draw(initial: SearchDomain.Config, width: Int = 1300, height: Int = 800): ByteArray {
        val scene = onEdt {
            ImageComposeScene(width, height, density = Density(1f)) {
                var config by remember { mutableStateOf(initial) }
                val inside = remember { ArrowSearches(read = { config.inside }, write = { _, next -> config = config.copy(inside = next) }) }
                var splits by remember { mutableStateOf(SearchSplits(left = 0.3f, topRight = 0.8f)) }
                CompositionLocalProvider(LocalArrowSearches provides inside, LocalArrowOwner provides "Search", LocalArrowRoot provides "Search") {
                    SearchWindow(
                        state = account,
                        openers = SearchRowOpeners(
                            onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {},
                        ),
                        onStartTaskNow = {},
                        onGoToTaskTree = { _, _ -> },
                        onDeepCopyCell = {},
                        actionHandlers = AddedActionHandlers(
                            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                        ),
                        onIntent = {},
                        onDismiss = {},
                        config = config,
                        onConfigChange = { config = it },
                        onOpenConfigurations = {},
                        splits = splits,
                        onSplitsChange = { splits = it },
                        initialSize = Size(width.toFloat(), height.toFloat()),
                    )
                }
            }
        }
        try {
            var bytes = ByteArray(0)
            repeat(5) { onEdt { bytes = scene.render().encodeToData()!!.bytes } }
            return bytes
        } finally {
            onEdt { scene.close() }
        }
    }
}
