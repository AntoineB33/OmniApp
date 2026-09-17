package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * **A task's name, written in that task's colour.** Wherever the app names a task, it names it the same way:
 * the string comes from `SchedulerDomain.taskTitleLabel` and the tint from [TaskPalette], so a task is one
 * word and one colour on every surface that mentions it (ADR 0013).
 *
 * The colour is a **background**, never the text colour, and that is the whole reason the rule can be
 * universal. The foreground stays free for what a particular surface has to say about the row — PRD §7's
 * task picker writes a task the now-line's periods forbid in red and one they merely scale in orange — so
 * "which task is this" and "what is true of it here" are two channels that never compete for one.
 *
 * ### The one thing a caller configures
 *
 * **Which reading of the hue**, because that follows the surface the label sits on and nothing else:
 *
 * - [TaskPalette.sheet] — a pale tint, on a light surface. The task tree's cells, the menus, the windows.
 * - [TaskPalette.accent] — full strength, on a dark one. The calendar's hover bubble is drawn on
 *   `inverseSurface`, where a sheet tint is invisible.
 *
 * Callers already hold one map or the other (`TaskPalette.sheetColors` / `accentColors`, both solved from
 * the one [TaskHueMemo]), so they pass a resolved [taskColor] and the choice stays where the background is
 * known. A **null** is a task with no colour of its own — an empty placeholder, a tombstone — and draws
 * untinted, keeping the same metrics so nothing shifts.
 *
 * ### Two drawings of one rule
 *
 * This is the **chip** form: a tinted box around the name, for a label sitting among other things (a menu
 * row, a list row, a breadcrumb, a bubble). The **block** form is
 * [org.example.project.scheduler.ui.TaskRow], where the tint is the whole cell's background because the
 * whole row *is* the task. They are one rule drawn at two sizes, not two rules — a third drawing that
 * leaves a task name uncoloured is the drift this file exists to stop.
 *
 * The one deliberate exception in the app is the priority-weight window's **pie legend**: its swatch must
 * match its slice, and the slice colours cannot be the tasks' own (ADR 0013 spreads childless tasks in
 * depth-first order, so one sub-list's leaves occupy a contiguous arc — the worst possible input to a pie).
 * The legend's label is still tinted here; only the swatch beside it answers a different question.
 */
@Composable
internal fun TaskTitleLabel(
    label: String,
    taskColor: Color?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    textColor: Color = LocalContentColor.current,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    softWrap: Boolean = true,
) {
    Text(
        modifier = modifier.taskTitleTint(taskColor),
        text = label,
        style = style,
        color = textColor,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}

/**
 * The chip itself, for a caller that has to build the text run some other way — a breadcrumb segment, or a
 * label already carrying its own search highlighting.
 *
 * The padding is applied whether or not there is a colour, so a task that has none occupies exactly the same
 * space: a row must not move when a colour arrives (they are solved on a debounce, see [TaskHueMemo]).
 */
internal fun Modifier.taskTitleTint(taskColor: Color?): Modifier =
    this
        .background(taskColor ?: Color.Transparent, TASK_TITLE_SHAPE)
        .padding(horizontal = 4.dp, vertical = 1.dp)

/** Rounded like the calendar's blocks, so a chip reads as the same object the panel does. */
private val TASK_TITLE_SHAPE = RoundedCornerShape(3.dp)
