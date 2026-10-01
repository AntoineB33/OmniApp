package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.example.project.ui.SheetColors
import org.example.project.ui.TaskCellOutline
import org.example.project.ui.borderColor
import org.example.project.ui.borderWidth
import org.example.project.ui.fill
import org.example.project.ui.taskCellOutline

/**
 * PRD §3/§4: a task cell can be in three states — in the selection, the **main** selection, and **Edit
 * Mode** — and each has its own drawing: a grey background for the selection, a darker one for the main
 * selection, and an outline in its own colour for Edit Mode (the task's colour is the swatch under the expand
 * arrow, never the cell's background).
 *
 * The states are NESTED in the state itself — the edited cell is also the main selection, which is also in
 * the selection range — so what is tested here is the ranking that turns three overlapping flags into one
 * drawing, and that the four drawings are actually different from one another.
 */
class TaskCellOutlineTest {

    @Test
    fun `an unselected cell rests on the sheet behind the plain grid line`() {
        val outline = taskCellOutline(isEditing = false, isMainSelection = false, isInSelectionRange = false)
        assertEquals(TaskCellOutline.None, outline)
        assertEquals(SheetColors.cellBackground, outline.fill)
        assertEquals(SheetColors.grid, outline.borderColor)
    }

    @Test
    fun `a cell of the selection that is not main takes the lighter grey and no outline of its own`() {
        val outline = taskCellOutline(isEditing = false, isMainSelection = false, isInSelectionRange = true)
        assertEquals(TaskCellOutline.Selected, outline)
        assertEquals(SheetColors.selectedFill, outline.fill)
        assertEquals(SheetColors.grid, outline.borderColor)
    }

    @Test
    fun `the main selection takes the darker grey and no outline of its own`() {
        // Main is always in the selection range too: the ranking, not the caller, is what keeps the two apart.
        val outline = taskCellOutline(isEditing = false, isMainSelection = true, isInSelectionRange = true)
        assertEquals(TaskCellOutline.Main, outline)
        assertEquals(SheetColors.mainSelectionFill, outline.fill)
        assertEquals(SheetColors.grid, outline.borderColor)
    }

    @Test
    fun `edit mode wins over the main selection and wears its own outline`() {
        // The edited cell is the main selection as well, so Edit Mode has to be read FIRST.
        val outline = taskCellOutline(isEditing = true, isMainSelection = true, isInSelectionRange = true)
        assertEquals(TaskCellOutline.Editing, outline)
        assertEquals(SheetColors.cellBackground, outline.fill)
        assertEquals(SheetColors.editBorder, outline.borderColor)
    }

    @Test
    fun `the four drawings are pairwise distinguishable`() {
        // A state the user cannot tell from its neighbour is the same as not drawing it at all.
        val drawings = TaskCellOutline.entries.map { it to Triple(it.fill, it.borderWidth, it.borderColor) }
        for ((a, drawingA) in drawings) {
            for ((b, drawingB) in drawings) {
                if (a == b) continue
                assertNotEquals(drawingA, drawingB, "$a and $b are drawn identically")
            }
        }
    }

    @Test
    fun `no selection grey is the fill of another state of the cell`() {
        val fills =
            listOf(
                SheetColors.cellBackground,
                SheetColors.nonSelectableFill,
                SheetColors.selectedFill,
                SheetColors.mainSelectionFill,
                SheetColors.moveDragFill,
            )
        assertEquals(fills.size, fills.toSet().size)
    }
}
