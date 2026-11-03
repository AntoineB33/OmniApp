package org.example.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.ElementLink
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow

/**
 * User rule 2026-11-02: *"Every element now has an expansion arrow. For example, expanding a timer element that has no
 * children shows a blank field like a task element, but double-clicking on it will open a new Search window."* Drawn off
 * screen, in the real Search window.
 */
class ElementChildrenTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private val account =
        QuotaScenario.account().copy(chronos = listOf(ChronoEntry(id = "a", label = "First"), ChronoEntry(id = "b", label = "Second")))

    private fun scene(links: List<ElementLink>, splits: SearchSplits, onSplits: (SearchSplits) -> Unit, opened: MutableList<String>) =
        ImageComposeScene(1200, 620, density = Density(1f)) {
            var state by remember { mutableStateOf(account.copy(elementLinks = links)) }
            var config by remember { mutableStateOf(SearchDomain.Config(kinds = setOf(Kind.Chrono), query = "First")) }
            SearchWindow(
                state = state,
                openers = SearchRowOpeners(
                    onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {},
                    onOpenChildSearch = { opened += it },
                ),
                onStartTaskNow = {},
                onGoToTaskTree = { _, _ -> },
                onDeepCopyCell = {},
                actionHandlers = AddedActionHandlers(
                    onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                    onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                    onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                ),
                onIntent = { state = SchedulerReducer.reduce(state, it) },
                onDismiss = {},
                config = config,
                onConfigChange = { config = it },
                onOpenConfigurations = {},
                splits = splits,
                onSplitsChange = onSplits,
                initialSize = Size(1200f, 620f),
            )
        }

    private fun doubleClick(scene: ImageComposeScene, at: Offset, start: Long) {
        var time = start * 1_000_000L
        fun frame() = onEdt { time += 16_000_000L; scene.render(time) }
        fun press(ms: Long) = onEdt {
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms)
        }
        fun release(ms: Long) = onEdt { scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms) }
        press(start); frame(); release(start + 50); frame(); frame()
        press(start + 120); frame(); release(start + 170); repeat(4) { frame() }
    }

    @Test
    fun a_double_click_on_the_blank_field_opens_a_search_from_the_element() {
        val opened = ArrayList<String>()
        val scene = onEdt { scene(emptyList(), SearchSplits(expandedElements = setOf("result/Chrono/a")), {}, opened) }
        try {
            repeat(4) { i -> onEdt { scene.render((i + 1) * 16_000_000L) } }
            // Under the only row of the list, whichever band of the head the list starts at.
            for ((i, y) in (130..260 step 17).withIndex()) {
                if (opened.isNotEmpty()) break
                doubleClick(scene, Offset(300f, y.toFloat()), 1000L + i * 1000L)
            }
            assertEquals(listOf("Chrono/a"), opened)
        } finally {
            onEdt { scene.close() }
        }
    }

    /**
     * Anomaly 2026-11-03: the blank field ends the list of children, and it is the PARENT's: under an expanded element with
     * a child, a double-click on it opens a search from that element — never from the child, which is not expanded.
     */
    @Test
    fun the_blank_field_ending_the_children_is_the_parents() {
        val opened = ArrayList<String>()
        val links = listOf(ElementLink(ElementTree.linkId("Chrono/a", "Chrono/b"), "Chrono/a", "Chrono/b"))
        val scene = onEdt { scene(links, SearchSplits(expandedElements = setOf("result/Chrono/a")), {}, opened) }
        try {
            repeat(4) { i -> onEdt { scene.render((i + 1) * 16_000_000L) } }
            for ((i, y) in (130..300 step 17).withIndex()) {
                if (opened.isNotEmpty()) break
                doubleClick(scene, Offset(300f, y.toFloat()), 1000L + i * 1000L)
            }
            assertEquals(listOf("Chrono/a"), opened)
        } finally {
            onEdt { scene.close() }
        }
    }

    @Test
    fun the_arrow_opens_a_row_that_has_no_children() {
        var seen = SearchSplits()
        val scene = onEdt { scene(emptyList(), SearchSplits(), { seen = it }, ArrayList()) }
        try {
            repeat(4) { i -> onEdt { scene.render((i + 1) * 16_000_000L) } }
            var ms = 1000L
            outer@ for (y in (130..200 step 12)) for (x in (20..190 step 10)) {
                val at = Offset(x.toFloat(), y.toFloat())
                onEdt {
                    scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
                    scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms)
                    scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms + 30)
                    scene.render(ms * 1_000_000L)
                    scene.render((ms + 16) * 1_000_000L)
                }
                ms += 1000L
                if (seen.expandedElements.isNotEmpty()) break@outer
            }
            assertTrue("result/Chrono/a" in seen.expandedElements, "seen: ${seen.expandedElements}")
        } finally {
            onEdt { scene.close() }
        }
    }

    @Test
    fun a_child_draws_under_its_parent_and_a_loop_ends() {
        val links = listOf(
            ElementLink(ElementTree.linkId("Chrono/a", "Chrono/b"), "Chrono/a", "Chrono/b"),
            ElementLink(ElementTree.linkId("Chrono/b", "Chrono/a"), "Chrono/b", "Chrono/a"),
        )
        val open = setOf("result/Chrono/a", "result/Chrono/a>Chrono/b")
        val scene = onEdt { scene(links, SearchSplits(expandedElements = open), {}, ArrayList()) }
        try {
            repeat(6) { i -> onEdt { scene.render((i + 1) * 16_000_000L) } }
        } finally {
            onEdt { scene.close() }
        }
    }
}
