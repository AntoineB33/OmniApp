package org.example.project.ui

import androidx.compose.foundation.clickable
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
    val selectionFill = Color(0xFFE8F0FE)
    val activeBorder = Color(0xFF1A73E8)
    val nonSelectableFill = Color(0xFFF8F9FA)
    val guideLine = Color(0xFFC7CBD1)
    val overflowArrow = Color(0xFFD93025)
    /** PRD §3 / §5: background of a cell or column while it is being drag-moved. */
    val moveDragFill = Color(0xFFCFD3D8)
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
) {
    Box(
        modifier = Modifier
            .size(20.dp)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
