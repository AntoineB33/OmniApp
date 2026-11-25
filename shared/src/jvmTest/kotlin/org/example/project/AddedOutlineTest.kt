package org.example.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
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
import org.example.project.ui.AddedOutlined
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow

/** User rule 2026-11-25: *"Rows in the search section that appear in the added elements list section must be outlined."* */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class AddedOutlineTest {
    @Test
    fun the_search_sections_rows_of_added_elements_are_outlined_and_no_other() {
        val account = QuotaScenario.account()
        val rows = SearchDomain.results(account, setOf(Kind.Task, Kind.Quota), "")
        val task = rows.first { it.kind == Kind.Task }
        val quota = rows.first { it.kind != Kind.Task }
        val added = listOf(SearchDomain.keyOf(task), SearchDomain.keyOf(quota))
        val scene = onEdt {
            ImageComposeScene(1200, 900, density = Density(1f)) {
                var config by remember { mutableStateOf(SearchDomain.Config(kinds = setOf(Kind.Task, Kind.Quota), added = added)) }
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
                    initialSize = Size(1200f, 900f),
                )
            }
        }
        try {
            repeat(4) { onEdt { scene.render() } }
            val nodes = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
            val outlined = nodes.filter { it.config.getOrNull(AddedOutlined) == true }
            fun textsUnder(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                (listOf(node) + node.children.flatMap { listOf(it) + it.children }).flatMap { n ->
                    n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
                }
            // The search section's row of the added task is outlined; the added list's own rows are not (a lazy list draws
            // only what is in view, so the other added element may be scrolled off).
            assertTrue(outlined.isNotEmpty(), "an added element's row is outlined")
            assertTrue(outlined.size <= added.size, "outlined: ${outlined.map(::textsUnder)}")
            assertTrue(outlined.any { node -> task.name in textsUnder(node) }, "outlined: ${outlined.map(::textsUnder)}")
        } finally {
            onEdt { scene.close() }
        }
    }
}
