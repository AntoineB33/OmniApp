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
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow
import org.example.project.ui.transientMenuDismissRoot

/**
 * User rule 2026-11-25: *"When right-clicking the background of the Search window S, if it is done in the action section
 * or the added elements list section, the options specific for those two search windows (embedded in S) appear after the
 * ones for S, after a horizontal separator."*
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class SectionBackgroundMenuTest {
    @Test
    fun a_sections_background_adds_its_own_options_after_the_windows() {
        val account = QuotaScenario.account()
        val added = SearchDomain.results(account, setOf(Kind.Task), "").take(1).map(SearchDomain::keyOf)
        val menus = org.example.project.ui.TransientMenuHost()
        val scene = onEdt {
            ImageComposeScene(1200, 900, density = Density(1f)) {
                CompositionLocalProvider(org.example.project.ui.LocalTransientMenuHost provides menus) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) {
                        var config by remember { mutableStateOf(SearchDomain.Config(kinds = setOf(Kind.Task), added = added, query = "Area 0 task 9")) }
                        var splits by remember { mutableStateOf(SearchSplits(actionsRemoved = true)) }
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
                            initialSize = Size(1200f, 900f),
                        )
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        fun rightClick(at: Offset) {
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = PointerButton.Secondary)
            }
            frames(6)
        }
        fun escape() {
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, Offset(5f, 5f), timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, Offset(5f, 5f), timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, Offset(5f, 5f), timeMillis = time + 30, button = PointerButton.Primary)
            }
            frames(6)
        }
        try {
            frames(6)
            val headerLabel = "Hide the search"
            // The search section's background, far under its rows: the window's own options, once.
            rightClick(Offset(300f, 880f))
            assertEquals(1, texts().count { it == headerLabel }, "the window's options alone: ${texts()}")
            escape()
            // The added elements list's background, under its one row: the window's, then the list's own.
            rightClick(Offset(900f, 880f))
            assertEquals(2, texts().count { it == headerLabel }, "the window's options, then the section's: ${texts()}")
        } finally {
            onEdt { scene.close() }
        }
    }
    /**
     * Anomaly 2026-11-26: *"Right-clicking in the background of an action section of an action section should at least
     * show 2 horizontal separators"* — the inner section's options were overwritten by the outer one's as the press reached
     * it: each section's are a group now, the outermost first, each after its separator.
     */
    @Test
    fun a_section_inside_a_section_adds_a_second_group_after_a_second_separator() {
        val holder = org.example.project.ui.SectionBackground()
        val inner = listOf(org.example.project.ui.MenuAction("Show the header", id = org.example.project.ui.SECTION_ENTRY_PREFIX + "header") {})
        val outer = listOf(org.example.project.ui.MenuAction("Show the header", id = org.example.project.ui.SECTION_ENTRY_PREFIX + "header") {})
        // One press reaches the inner section first, then the outer one.
        holder.leave(7L, inner)
        holder.leave(7L, outer)
        val groups = holder.take()
        assertEquals(listOf(outer, inner), groups)
        val entries = org.example.project.ui.sectionGroups(groups)
        assertEquals(2, entries.count { it.separator })
        assertEquals(2, entries.map { it.id }.filter { !it.startsWith("separator") }.distinct().size, "the two groups' entries are told apart")
        assertTrue(holder.take().isEmpty())
        // A press no menu took leaves nothing for the next one.
        holder.leave(8L, inner)
        holder.leave(9L, outer)
        assertEquals(listOf(outer), holder.take())
    }
    /**
     * Anomaly 2026-11-26 (*"It works first by scrolling a few pixel, then no right-click menu works anymore"*): "Entries, in
     * order" naming "separator" three times put the same separator row twice in the menu. Each row is listed once, the
     * separators in their order.
     */
    @Test
    fun entries_named_twice_list_each_row_once() {
        val kept = listOf("0" to "Copy id", "1" to "a", "2" to "———", "3" to "b", "4" to "———", "5" to "c")
        val ids = mapOf("0" to "Copy id", "1" to "a", "2" to "separator", "3" to "b", "4" to "separator", "5" to "c")
        val ordered = org.example.project.ui.orderedByEntries(kept, listOf("Copy id", "a", "separator", "b", "separator", "c", "separator", "d"), setOf("2", "4")) { ids.getValue(it.first) }
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), ordered.map { it.first })
    }
}
