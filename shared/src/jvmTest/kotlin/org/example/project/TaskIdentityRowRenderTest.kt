package org.example.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.EditMenuItem
import org.example.project.ui.TaskIdentityRow

/**
 * Anomaly 2026-10-03 (account3): typing in a tree cell and picking from its menus raised *"Can't represent a width of
 * 2147483563 and height of 0 in Constraints"*. A tree cell's edit menus are measured with NO maximum width (the tree
 * scrolls sideways), and the id suggestion list's task row ([TaskIdentityRow]) shared an unbounded width between its
 * title and its path box. The row is RENDERED here under exactly that parent: it must lay out, whatever it is offered.
 */
class TaskIdentityRowRenderTest {

    private fun stateWithTasks(): SchedulerState {
        var s = SchedulerState.empty()
        val parentCell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(parentCell, "Transformation de données complexes en résultats exploitables"))
        val parent = s.cells[parentCell]!!.taskId!!
        val child = s.lists[s.tasks[parent]!!.childListId!!]!!.cellIds.first()
        return SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(child, "Child"))
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) { MaterialTheme { content() } }
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }

    @Test
    fun the_row_lays_out_under_a_parent_that_offers_no_maximum_width() {
        val s = stateWithTasks()
        val paths = SearchDomain.allPathsInAnyTree(s)
        val tasks = s.tasks.values.filter { it.title.isNotBlank() && it.title != "root" }
        assertTrue(tasks.size >= 2)
        render {
            // A horizontally scrolling parent measures its content with infinite width.
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    for (task in tasks) TaskIdentityRow(s, paths, EditMenuItem(label = task.title, taskId = task.id) {}, onIntent = {})
                }
            }
        }
    }

    /** The reported crash: a parent sized by its content's INTRINSIC width asks the row how wide it would be. */
    @Test
    fun the_row_answers_a_parent_sized_by_its_intrinsic_width() {
        val s = stateWithTasks()
        val paths = SearchDomain.allPathsInAnyTree(s)
        val tasks = s.tasks.values.filter { it.title.isNotBlank() && it.title != "root" }
        for (size in listOf(IntrinsicSize.Max, IntrinsicSize.Min)) {
            render {
                Column(Modifier.width(size)) {
                    for (task in tasks) TaskIdentityRow(s, paths, EditMenuItem(label = task.title, taskId = task.id) {}, onIntent = {})
                }
            }
        }
    }

    @Test
    fun the_row_lays_out_in_a_narrow_parent_too() {
        val s = stateWithTasks()
        val paths = SearchDomain.allPathsInAnyTree(s)
        val task = s.tasks.values.first { it.title.startsWith("Transformation") }
        render {
            Box(Modifier.width(120.dp)) {
                TaskIdentityRow(s, paths, EditMenuItem(label = task.title, selected = true, taskId = task.id) {}, onIntent = {})
            }
        }
    }
}
