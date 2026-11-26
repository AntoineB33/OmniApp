package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.claudelogs.ClaudeLogCatalog
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
import org.example.project.ui.transientMenuDismissRoot

/**
 * Anomaly 2026-11-26: *"Right-clicking in the background of an action section of an action section should at least show 2
 * horizontal separators"* — the window's options, then the outer actions section's, then the inner one's.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class NestedSectionMenuTest {
    private val account: SchedulerState =
        SchedulerState.empty().copy(chronos = listOf("a", "b").map { ChronoEntry(id = it, label = it) })

    private fun actionRows(added: List<SearchDomain.Result>, config: SearchDomain.Config): List<SearchDomain.ActionListRow> {
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions(config.actionQuery, Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
        return SearchDomain.actionListRows(SearchDomain.shownActionGroups(sections, added, config, ClaudeLogCatalog.EMPTY), config)
    }

    @Test
    fun the_background_of_an_actions_section_of_an_actions_section_has_three_groups() {
        val elements = listOf("Chrono/a", "Chrono/b")
        val window = SearchDomain.Config(kinds = setOf(Kind.Chrono), added = elements, actionsExpanded = true)
        val rows = SearchDomain.resolve(account, elements, { emptyMap() })
        val parents = actionRows(rows, window).first { it.action == AddedAction.ElementParents && it.group.members.size == 2 }
        val own = ACTIONS_SECTION_OWN.copy(added = listOf(Kind.Action.name + "/" + parents.key))
        val initial = window.copy(inside = mapOf("/actions" to own.encode()))
        val menus = org.example.project.ui.TransientMenuHost()
        val scene = onEdt {
            ImageComposeScene(1400, 1800, density = Density(1f)) {
                var config by remember { mutableStateOf(initial) }
                val inside = remember { ArrowSearches(read = { config.inside }, write = { _, next -> config = config.copy(inside = next) }) }
                var splits by remember { mutableStateOf(SearchSplits(searchRemoved = true, addedRemoved = true)) }
                CompositionLocalProvider(
                    org.example.project.ui.LocalTransientMenuHost provides menus,
                    LocalArrowSearches provides inside, LocalArrowOwner provides "Search", LocalArrowRoot provides "Search",
                ) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) {
                        SearchWindow(
                            state = account,
                            openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {}),
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
                            initialSize = Size(1400f, 1800f),
                        )
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        try {
            frames(8)
            // The inner section's one row: "Action: Parents".
            val row = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.startsWith("Action:") == true }
            val at = Offset(row.boundsInRoot.center.x, row.boundsInRoot.bottom + 200f)
            fun press(where: Offset, secondary: Boolean) = onEdt {
                time += 1_000L
                val buttons = if (secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
                val button = if (secondary) PointerButton.Secondary else PointerButton.Primary
                scene.sendPointerEvent(PointerEventType.Move, where, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, where, timeMillis = time, buttons = buttons, button = button)
                scene.sendPointerEvent(PointerEventType.Release, where, timeMillis = time + 30, button = button)
            }
            press(at, secondary = true)
            frames(8)
            // The window's options and the outer section's, its actions shown ("Hide the actions").
            assertEquals(2, texts().count { it == "Hide the actions" }, texts().toString())
            // The menu holds more than its height shows: the wheel takes it to its end, the inner section's own options —
            // its actions not shown, the one "Show the actions".
            val menu = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == "Copy id" }.boundsInRoot.center
            val over = menu + Offset(0f, 100f)
            onEdt { scene.sendPointerEvent(PointerEventType.Move, over, timeMillis = time) }
            repeat(10) {
                onEdt {
                    time += 50L
                    scene.sendPointerEvent(PointerEventType.Scroll, over, scrollDelta = Offset(0f, 1f), timeMillis = time)
                }
                frames(10)
            }
            assertTrue("Show the actions" in texts(), "the inner section's options, after a separator of their own: ${texts().takeLast(16)}")
            // Anomaly 2026-11-26 (*"It works first by scrolling a few pixel, then no right-click menu works anymore"*): a
            // press outside still closes it, and a row's right-click opens the row's own menu.
            press(Offset(1350f, 60f), secondary = false)
            frames(10)
            assertEquals(0, texts().count { it == "Copy id" }, "closed by a press outside: ${texts().takeLast(16)}")
            val parentsRow = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == "Parents" }.boundsInRoot.center
            press(parentsRow, secondary = true)
            frames(10)
            assertTrue("pin this row" in texts(), "the row's own menu: ${texts().takeLast(16)}")
        } finally {
            onEdt { scene.close() }
        }
    }
}
