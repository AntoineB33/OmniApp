package org.example.project

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
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

    /**
     * Anomaly 2026-11-14: *"'Contexts and states' groups elements only for 'Parents'"*. An element's own configuration is
     * read off the element: the elements holding the same value are one context.
     */
    @Test
    fun the_elements_holding_the_same_value_of_their_own_configuration_are_one_context() {
        val alarms = listOf(
            org.example.project.scheduler.model.AlarmEntry(id = "x", label = "wake", timeOfDayMinutes = 420),
            org.example.project.scheduler.model.AlarmEntry(id = "y", label = "wake", timeOfDayMinutes = 480),
            org.example.project.scheduler.model.AlarmEntry(id = "z", label = "pill", timeOfDayMinutes = 420, enabled = false),
        )
        val state = account.copy(alarms = alarms)
        val all = SearchDomain.resolve(state, alarms.map { "Alarm/" + it.id }, { emptyMap() })
        fun contexts(action: AddedAction) =
            SearchDomain.actionContexts(state, action, all).map { members -> members.map { (it as SearchDomain.ItemResult).id } }
        assertEquals(listOf(listOf("x", "y"), listOf("z")), contexts(AddedAction.AlarmTitle))
        assertEquals(listOf(listOf("x", "z"), listOf("y")), contexts(AddedAction.AlarmTime))
        assertEquals(listOf(listOf("x", "y"), listOf("z")), contexts(AddedAction.AlarmOnOff))
        assertEquals(listOf(listOf("x", "y", "z")), contexts(AddedAction.AlarmDays))
        // The chronos' titles: each its own here, two the same once renamed alike.
        assertEquals(3, SearchDomain.actionContexts(account, AddedAction.ChronoTitle, rows("a", "d", "e")).size)
        val renamed = account.copy(chronos = account.chronos.map { if (it.id == "d") it.copy(label = "a") else it })
        val chronos = SearchDomain.resolve(renamed, listOf("a", "d", "e").map(::key), { emptyMap() })
        assertEquals(2, SearchDomain.actionContexts(renamed, AddedAction.ChronoTitle, chronos).size)
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

    /**
     * User rule 2026-11-26 (*"In 'Contexts and states', it doesn't really seem to be real rows … there should be three rows,
     * each with two children"*): each pair is a row of the section, with its "context" and "states" rows under it. "Parents"
     * is about every kind, so its contexts are the elements it acts on and it has no "no state" pair: a, b, c under p; d
     * under q; e under nothing — three pairs, then a new one: four pair rows, each with its two.
     */
    @Test
    fun each_pair_is_a_row_with_its_context_and_states_rows() {
        val elements = listOf("a", "b", "c", "d", "e").map(::key)
        val window = SearchDomain.Config(kinds = setOf(Kind.Chrono), added = elements, actionsExpanded = true)
        val parents = actionRows(rows("a", "b", "c", "d", "e"), window).first { it.action == AddedAction.ElementParents && it.group.members.size == 5 }
        val ownAdded = listOf(SearchDomain.ItemResult(Kind.Action, parents.key, parents.title, parents.detail))
        val contextsRow = actionRows(ownAdded, window).first { it.action == AddedAction.ActionContexts }
        val own = ACTIONS_SECTION_OWN.copy(added = listOf(Kind.Action.name + "/" + parents.key), toggledActionRows = setOf(contextsRow.id))
        val texts = drawnTexts(window.copy(inside = mapOf("/actions" to own.encode())), height = 2400)
        assertEquals(4, texts.count { it == "context" }, texts.toString())
        assertEquals(4, texts.count { it == "states" }, texts.toString())
        // User 2026-11-26: a pair's row is titled by every one of its contexts.
        for (title in listOf("a, b, c", "d", "e", SearchDomain.NEW_PAIR_TITLE)) assertTrue(title in texts, "$title: $texts")
        assertFalse(texts.any { it.startsWith("context-state") }, texts.toString())
        assertFalse(texts.any { it.startsWith("not complete") }, "the derived pairs hold every element: $texts")
    }

    /**
     * User rule 2026-11-26: an element taken out of its pair's contexts is in no pair — the "Contexts and states" row warns
     * until another pair holds it again.
     */
    @Test
    fun an_element_in_no_pair_makes_the_pairs_incomplete() {
        val kind = listOf("Task/a", "Task/b", "Task/c")
        val pairs = listOf(SearchDomain.ContextPair(kind, true), SearchDomain.ContextPair(null, false), SearchDomain.ContextPair(emptyList(), true))
        assertEquals(emptyList(), SearchDomain.contextsInNoPair(pairs, kind, listOf("Alarm/x")))
        assertEquals(emptyList(), SearchDomain.contextsInNoPair(pairs, kind, null), "the others not listed yet: the whole \"no state\" pair holds them")
        // "b" removed from the first pair: in no pair.
        val removed = SearchDomain.withPairValues(pairs, 0, listOf("Task/a", "Task/c"))
        assertEquals(listOf("Task/b"), SearchDomain.contextsInNoPair(removed, kind, listOf("Alarm/x")))
        assertEquals("not complete: b is in no context-state", SearchDomain.contextsIncompleteWarning(listOf("Task/b")) { it.substringAfter('/') })
        // The new pair given "b": complete again, and a new pair follows.
        val readded = SearchDomain.withPairValues(removed, 2, listOf("Task/b"))
        assertEquals(emptyList(), SearchDomain.contextsInNoPair(readded, kind, listOf("Alarm/x")))
        assertEquals(4, readded.size)
        // An element of the "no state" pair removed from it: in no pair.
        val noState = SearchDomain.withPairValues(pairs, 1, emptyList())
        assertEquals(listOf("Alarm/x"), SearchDomain.contextsInNoPair(noState, kind, listOf("Alarm/x")))
    }

    /** Anomaly 2026-11-26: an action row's "copy path" and "collapse sub-trees" were greyed. */
    @Test
    fun an_action_rows_path_and_the_open_rows_under_it() {
        val group = actionRows(rows("a"), SearchDomain.Config(kinds = setOf(Kind.Chrono), added = listOf(key("a")))).first().group
        fun row(id: String, depth: Int, open: Boolean = true) = SearchDomain.ActionListRow(id, id, id.uppercase(), "", depth, open, group, null)
        val list = listOf(row("g", 0), row("act", 1), row("pair", 2), row("ctx", 3), row("st", 3, open = false), row("pair2", 2), row("h", 0))
        assertEquals(listOf("G", "ACT", "PAIR", "CTX"), SearchDomain.actionRowPath(list, 3))
        assertEquals(listOf("pair", "ctx", "pair2"), SearchDomain.openActionRowsUnder(list, 1, keepLevels = 1))
        assertEquals(listOf("act", "pair", "ctx", "pair2"), SearchDomain.openActionRowsUnder(list, 1, keepLevels = 0))
        assertEquals(listOf("ctx"), SearchDomain.openActionRowsUnder(list, 1, keepLevels = 2))
        assertEquals(emptyList(), SearchDomain.openActionRowsUnder(list, 3, keepLevels = 1), "a leaf keeping itself has nothing to close")
    }

    /**
     * User rule 2026-11-26 (*"when it can be rows it must be rows"*): "Clicks and keys" lists its groups of contexts as rows,
     * each with its "context" row and a row per press, what it does beside — no longer text drawn under the action.
     */
    @Test
    fun clicks_and_keys_lists_its_groups_and_presses_as_rows() {
        val elements = listOf("a", "b", "c", "d", "e").map(::key)
        val window = SearchDomain.Config(kinds = setOf(Kind.Chrono), added = elements, actionsExpanded = true)
        val presses = actionRows(rows("a", "b", "c", "d", "e"), window).first { it.action == AddedAction.ElementPresses && it.group.members.size == 5 }
        val texts = drawnTexts(window.copy(toggledActionRows = window.toggledActionRows + presses.id), height = 2400)
        val groups = org.example.project.scheduler.domain.PressDomain.groups(emptyList(), Kind.Chrono)
        assertEquals(groups.size, texts.count { it == "context" }, texts.toString())
        val first = groups.first().behaviours.first()
        assertTrue(first.press.label in texts && first.does in texts, "a press's row and what it does: $texts")
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    private fun drawnTexts(initial: SearchDomain.Config, width: Int = 1300, height: Int = 800): List<String> {
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
                        splits = splits,
                        onSplitsChange = { splits = it },
                        initialSize = Size(width.toFloat(), height.toFloat()),
                    )
                }
            }
        }
        try {
            var time = 0L
            repeat(8) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
            return onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .mapNotNull { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text } }
            }
        } finally {
            onEdt { scene.close() }
        }
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
