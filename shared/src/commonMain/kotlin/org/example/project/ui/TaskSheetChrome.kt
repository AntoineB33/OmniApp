package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The **task sheet chrome**: the colours, metrics and small pieces that make a row *look like a task-tree
 * cell*. Extracted here because there are now two trees drawn the same way — the task tree itself
 * ([org.example.project.scheduler.ui.TaskSchedulerScreen]) and the **default sub-tree** template
 * ([DefaultSubtreeWindow], PRD §4) — and PRD §4 requires the template to be "a tree of task cells edited
 * exactly like the task tree itself".
 *
 * Keep this the ONLY copy of the look. A second set of colours or a second indent step is how the two trees
 * silently drift apart.
 */
internal object SheetColors {
    val grid = Color(0xFFDADCE0)
    val cellBackground = Color.White
    /**
     * The "on" fill of a latching toggle (the find bar's). **Not** a task cell's selection, which has its own
     * two greys ([selectedFill], [mainSelectionFill]).
     */
    val selectionFill = Color(0xFFE8F0FE)
    /** PRD §3: the background of a cell of the selection that is not the main one — the lighter grey. */
    val selectedFill = Color(0xFFECEEF1)
    /** PRD §3: the background of the main selection — the darker of the two greys. */
    val mainSelectionFill = Color(0xFFDADDE2)
    val activeBorder = Color(0xFF1A73E8)
    /**
     * PRD §4: the outline of a cell **in Edit Mode**. Its own hue: Edit Mode is not a third degree of
     * selection, it is the state where the keyboard writes into the cell, and the user has to be able to
     * tell it from the main selection at a glance ([TaskCellOutline]).
     */
    val editBorder = Color(0xFF9334E6)
    val nonSelectableFill = Color(0xFFF8F9FA)
    val guideLine = Color(0xFFC7CBD1)
    val overflowArrow = Color(0xFFD93025)
    /** PRD §3 / §5: background of a cell or column while it is being drag-moved — darker than both selection greys. */
    val moveDragFill = Color(0xFFBDC1C6)
    /** PRD §4 Find & replace: shading behind every hit of the Ctrl+F query inside a title. */
    val searchMatchFill = Color(0xFFFFF2A8)
    /** …and behind the one hit the find bar is currently sitting on. */
    val searchCurrentFill = Color(0xFFFFB74D)
}

/**
 * PRD §3/§4: the three states a task cell can be in, each with its **own drawing** ([fill], [borderWidth],
 * [borderColor]).
 *
 * The selection is said in the cell's BACKGROUND — a grey for a cell of the selection, a darker grey for the
 * main one (user rule 2026-10-01; the task's colour is the swatch under the expand arrow, so a fill hides
 * nothing). `Editing` alone keeps an outline, in its own colour, because it is not a third degree of
 * selection but the state where the keyboard writes into the cell.
 *
 * One decision, in one place: three surfaces draw task cells (the tree, the Search window's sub-trees
 * and the default sub-tree template) and a second copy of this rule is how two of them come to disagree about what
 * "selected" looks like.
 */
internal enum class TaskCellOutline {
    /** Not selected at all: the ordinary grid line. */
    None,

    /** In the Selected Cells List, but not the main selection. */
    Selected,

    /** The main selection, not being edited. */
    Main,

    /** In Edit Mode. Takes precedence over [Main]: the edited cell is always the main selection too. */
    Editing,
}

/**
 * Which outline a cell wears. [isEditing] wins over [isMainSelection], which wins over
 * [isInSelectionRange] — the edited cell is also the main selection, and the main selection is also in the
 * selection range, so the states are nested and only the innermost one may show.
 */
internal fun taskCellOutline(
    isEditing: Boolean,
    isMainSelection: Boolean,
    isInSelectionRange: Boolean,
): TaskCellOutline =
    when {
        isEditing -> TaskCellOutline.Editing
        isMainSelection -> TaskCellOutline.Main
        isInSelectionRange -> TaskCellOutline.Selected
        else -> TaskCellOutline.None
    }

/** The cell's background: the sheet's at rest and in Edit Mode, a grey per degree of selection. */
internal val TaskCellOutline.fill: Color
    get() =
        when (this) {
            TaskCellOutline.None, TaskCellOutline.Editing -> SheetColors.cellBackground
            TaskCellOutline.Selected -> SheetColors.selectedFill
            TaskCellOutline.Main -> SheetColors.mainSelectionFill
        }

/** The outline's weight: the grid line, thickened for Edit Mode alone. */
internal val TaskCellOutline.borderWidth: Dp
    get() =
        when (this) {
            TaskCellOutline.None, TaskCellOutline.Selected, TaskCellOutline.Main -> 1.dp
            TaskCellOutline.Editing -> 2.dp
        }

/** The outline's colour. */
internal val TaskCellOutline.borderColor: Color
    get() =
        when (this) {
            TaskCellOutline.None, TaskCellOutline.Selected, TaskCellOutline.Main -> SheetColors.grid
            TaskCellOutline.Editing -> SheetColors.editBorder
        }

/** Indentation step (dp) per nesting level; also the spacing between hierarchy guide-lines. */
internal const val INDENT_STEP_DP = 16

/** Horizontal offset (dp) of a level's guide-line, aligned under that ancestor's expand arrow. */
internal const val GUIDE_LINE_OFFSET_DP = 14

/**
 * PRD §2 Priority Display: the text column before the priority percentage is sized to the widest
 * cell text of the sublist, clamped between these bounds so the percentages of one sublist all
 * align at the same horizontal position.
 */
internal val PRIORITY_COLUMN_MIN = 56.dp
internal val PRIORITY_COLUMN_MAX = 280.dp

/**
 * Fixed width of the column that follows a cell's text: the priority percentage in the task tree, and — so
 * the two trees line up at the same place — the row switch in the default sub-tree window (PRD §4).
 */
internal val PERCENT_COLUMN_WIDTH = 52.dp

/**
 * User rule 2026-10-07: **a Search result row gives its room to the PATH.** A tree row's minimum time and
 * categories stand in columns wide enough for every row of the tree to line up under one another; in a result row
 * those widths were blank space beside a path box squeezed for lack of room. Where this is true the two are only as
 * wide as what they show ([TIGHT_MIN_TIME_WIDTH]; the categories' own text), and the path takes what they leave.
 * Read by the cells themselves, so the row stays the tree's one drawing.
 */
internal val LocalTightRowColumns = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The minimum time's column in a tight row: its longest usual label ("120m") and the cell's padding. */
internal val TIGHT_MIN_TIME_WIDTH = 44.dp

/** The percentage's column in a tight row: its longest label ("12.5%") and the column's padding. */
internal val TIGHT_PERCENT_WIDTH = 44.dp

/**
 * PRD §2: the guide-lines on the left that illustrate the parent-child hierarchy. One vertical line is drawn
 * in the indentation gutter under each expanded ancestor's arrow; they only appear beneath expanded cells
 * (a collapsed cell hides its rows, so there is no gutter to draw in).
 *
 * Applied *before* the row's own `padding(start = depth * INDENT_STEP_DP)`, so it spans the whole row.
 */
internal fun Modifier.taskSheetGuideLines(depth: Int): Modifier = drawBehind {
    val step = INDENT_STEP_DP.dp.toPx()
    val offset = GUIDE_LINE_OFFSET_DP.dp.toPx()
    val stroke = 1.dp.toPx()
    for (level in 0 until depth) {
        val x = level * step + offset
        drawLine(
            color = SheetColors.guideLine,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = stroke,
        )
    }
}

/**
 * The expand/collapse arrow at the head of a task-sheet row — a fixed 20 dp box either way, so rows with and
 * without children still align their text at the same x.
 */
@Composable
internal fun TaskSheetExpandArrow(
    hasChildren: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** The arrow's colour: the sheet's, or — on a task's colour — the colour of highest contrast with it. */
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /**
     * The task's colour, painted under the arrow's box — the one place a tree row shows it (user rule
     * 2026-10-01). The box is there with or without children, so every task's row carries its swatch.
     */
    background: Color? = null,
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .then(if (background != null) Modifier.taskSwatch(background) else Modifier)
            .then(
                if (hasChildren) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (hasChildren) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

/**
 * A task's colour as a swatch — the box under a row's expansion arrow, or the bare one a row with no arrow shows —
 * **outlined in the colour of highest contrast with it** ([TaskPalette.foreground]), exactly as a task panel is on the
 * calendar (user rule 2026-10-03): the outline is what keeps a pale or a dark colour's edge visible on the sheet.
 */
internal fun Modifier.taskSwatch(color: Color): Modifier {
    val shape = RoundedCornerShape(3.dp)
    return background(color, shape).border(1.dp, TaskPalette.foreground(color), shape)
}

internal fun Key.isModifierKey(): Boolean =
    when (this) {
        Key.ShiftLeft,
        Key.ShiftRight,
        Key.CtrlLeft,
        Key.CtrlRight,
        Key.AltLeft,
        Key.AltRight,
        Key.MetaLeft,
        Key.MetaRight,
        -> true
        else -> false
    }

/**
 * PRD §4: the character that typing on a *selected* (not yet editing) cell opens Edit Mode with — or null
 * when the key press is not text at all. Shared so both trees enter Edit Mode on exactly the same keys.
 */
internal fun KeyEvent.printableChar(): String? {
    if (isCtrlPressed || isMetaPressed) return null
    if (key.isModifierKey()) return null
    if (key == Key.Enter || key == Key.Tab || key == Key.Escape || key == Key.Backspace) return null
    if (key == Key.DirectionUp || key == Key.DirectionDown ||
        key == Key.DirectionLeft || key == Key.DirectionRight
    ) {
        return null
    }
    val codePoint = utf16CodePoint
    if (!codePoint.isValidTextCodePoint()) return null
    return Char(codePoint).toString()
}

/** Rejects control codes and Unicode non-characters (e.g. U+FFFF from bare Shift on desktop). */
private fun Int.isValidTextCodePoint(): Boolean {
    if (this <= 0x1F) return false
    if (this in 0x7F..0x9F) return false
    if (this in 0xFDD0..0xFDEF) return false
    if ((this and 0xFFFE) == 0xFFFE) return false
    return true
}

/**
 * PRD §4: Edit Mode is opened by a double-click **on the cell's title** — not on the rest of the row (the
 * percentage, the minimum time, the switch, the empty tail). The row's pointer handler has to sit on the
 * whole row anyway (it also selects, range-drags and move-drags), so the title column records its own
 * window-space horizontal band here and the handler asks whether the press landed inside it.
 *
 * Deliberately not Compose state: it is written from the layout phase and read from a gesture coroutine, so
 * nothing should recompose on it.
 */
internal class TaskSheetTitleBounds {
    private var startX = Float.NaN
    private var endX = Float.NaN

    internal fun record(coordinates: LayoutCoordinates) {
        startX = coordinates.positionInWindow().x
        endX = startX + coordinates.size.width
    }

    /**
     * Whether [windowX] falls in the title column. Unmeasured (the row has not been laid out yet) counts as
     * inside, so a missing measurement can never make a title double-click do nothing.
     */
    internal fun containsWindowX(windowX: Float): Boolean =
        startX.isNaN() || (windowX >= startX && windowX < endX)
}

/** Records the title column's band for [TaskSheetTitleBounds]. Put it on the title cell of a row. */
internal fun Modifier.taskSheetTitleBounds(bounds: TaskSheetTitleBounds): Modifier =
    onGloballyPositioned { bounds.record(it) }
