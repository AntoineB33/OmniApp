package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.CalendarPart
import org.example.project.ui.LocalCalendarElement
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow

/**
 * User rule 2026-11-21: the default Calendar menu button's window shows its actions section alone, the calendar in view —
 * drawn off screen as `App` opens it: [SearchDomain.calendarWindowConfig], [SearchSplits.actionsOnly], the actions asked to
 * start at [SearchDomain.calendarActionRowId]. `App`'s calendar is a stand-in of its height here.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class CalendarButtonWindowTest {
    private val width = 1000

    @Test
    fun the_calendar_buttons_window_shows_the_calendar() = check(broughtBack = false)

    /**
     * Anomaly 2026-11-21 (*"the search result section and the added element list section are still not hidden"*): the
     * button brought back the calendar window left open from before, as it stood. Brought back, it is given the same
     * sections and the same scroll as a new one — drawn here first with its three sections, then told so.
     */
    @Test
    fun the_calendar_window_it_brings_back_shows_the_calendar_alone_too() = check(broughtBack = true)

    /**
     * User rule 2026-11-21: *"scrolled up at maximum where the parent of the 'Calendar' row doesn't appear. It must be this
     * way even if I change the rows appearing in the action section"* — a short calendar in a tall window, the rows after it
     * too few to fill the list: the "Calendar" row still stands at the top, its group out of view.
     */
    @Test
    fun the_calendar_row_is_at_the_top_however_few_rows_follow_it() = check(broughtBack = false, height = 2400, gridDp = 120)

    private fun check(broughtBack: Boolean, height: Int = 700, gridDp: Int = 520) {
        val splitsAsked = mutableStateOf(if (broughtBack) SearchSplits() else SearchSplits.actionsOnly(SearchSplits()))
        val revealAsked = mutableStateOf(if (broughtBack) null else SearchDomain.calendarActionRowId())
        val config0 = SearchDomain.calendarWindowConfig(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Calendar), calendarDayMode = true))
        val account = QuotaScenario.account()
        val scene = onEdt {
            ImageComposeScene(width, height, density = Density(1f)) {
                var config by remember { mutableStateOf(config0) }
                var splits by splitsAsked
                val registry = remember {
                    org.example.project.ui.ArrowSearches(read = { config.inside }, write = { _, inside -> config = config.copy(inside = inside) })
                }
                CompositionLocalProvider(
                    // `App`'s own wrapper of the grid (anomaly 2026-11-21: it drew the calendar 0 px tall), the grid a
                    // stand-in at the calendar's height.
                    LocalCalendarElement provides { part ->
                        if (part == CalendarPart.Grid) {
                            org.example.project.ui.CalendarGridWithProblems(account) {
                                Box(Modifier.fillMaxWidth().height(gridDp.dp)) { Text("THE CALENDAR GRID") }
                            }
                        } else {
                            Text("calendar part $part")
                        }
                    },
                    org.example.project.ui.LocalArrowSearches provides registry,
                    org.example.project.ui.LocalArrowOwner provides "Search#2",
                    org.example.project.ui.LocalArrowRoot provides "Search#2",
                ) {
                    SearchWindow(
                        state = account,
                        openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {}),
                        onStartTaskNow = {},
                        onGoToTaskTree = { _, _ -> },
                        onDeepCopyCell = {},
                        actionRevealAsked = revealAsked.value,
                        onActionRevealTaken = { revealAsked.value = null },
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
            repeat(12) { onEdt { time += 16; scene.render(time * 1_000_000L) } }
            fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
            fun texts() = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            if (broughtBack) {
                val before = texts()
                assertTrue(before.any { it.startsWith("Added elements") }, "as it stood: its added elements shown: $before")
                // What the button does to the window it brings back (`App`'s onMenuButtonClicked).
                onEdt {
                    splitsAsked.value = SearchSplits.actionsOnly(splitsAsked.value)
                    revealAsked.value = SearchDomain.calendarActionRowId()
                }
                repeat(12) { onEdt { time += 16; scene.render(time * 1_000_000L) } }
            }
            val shown = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.let { it to n.boundsInWindow } }
            println("shown:\n" + shown.joinToString("\n") { (t, b) -> "  $b  $t" })
            val grid = shown.firstOrNull { it.first == "THE CALENDAR GRID" }
            assertTrue(grid != null && grid.second.top < height && grid.second.top >= 0f && grid.second.height > 0f, "the calendar is in view: $grid")
            // Only the actions section: the search's and the added elements' heads are not there at all.
            assertTrue(shown.none { it.first.startsWith("Added elements") }, "no added elements section")
            // …nor the search section: the actions' head starts at the window's left edge, not right of a search column.
            // A section's title may end with how many it lists (user rule 2026-11-26).
            fun titled(title: String, text: String) = text == title || text.startsWith("$title  ·  ")
            val actions = shown.first { titled("Actions on the added elements", it.first) }
            assertTrue(actions.second.left < 100f, "the actions take the whole width: $actions")
            // The "Calendar" row at the top of the list, its group ("Calendar" too, one level out) not drawn at all — nor
            // pinned over it: one "Calendar" shown, and no row between the bar and it.
            val calendarRows = shown.filter { it.first == "Calendar" }
            assertTrue(calendarRows.size == 1, "the Calendar row alone, not its group: $calendarRows")
            val bar = shown.first { titled("Search", it.first) }
            val calendarRow = calendarRows.single()
            assertTrue(
                shown.none { (t, b) -> t.isNotBlank() && b.top > bar.second.bottom && b.bottom < calendarRow.second.top - 1f },
                "nothing between the bar and the Calendar row: $calendarRow",
            )
        } finally {
            onEdt { scene.close() }
        }
    }
}
