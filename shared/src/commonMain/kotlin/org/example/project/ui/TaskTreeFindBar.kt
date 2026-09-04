package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.domain.TaskTreeSearch

/**
 * PRD §4 **Find & replace**: the task tree's Ctrl+F bar, in the top-right corner of the tree, laid out like
 * VS Code's — a query field with a match counter, the two toggles, up/down and close; and, behind the
 * chevron on the left, a second row with the replacement field and its two buttons.
 *
 * Purely presentational: every piece of state is hoisted into
 * [org.example.project.scheduler.ui.TaskSchedulerScreen], which owns the matching, the navigation and the
 * intents. The bar owns only the keyboard contract, because the tree's own `onPreviewKeyEvent` never sees
 * these keys — the bar is a sibling of the tree, not a child of it:
 *
 * - **Enter / Shift+Enter** — next / previous match; from the replacement field, replace the current match.
 * - **Escape** — close, handing the keyboard back to the tree.
 */
@Composable
internal fun TaskTreeFindBar(
    query: TextFieldValue,
    replacement: TextFieldValue,
    options: TaskTreeSearch.Options,
    replaceExpanded: Boolean,
    matchCount: Int,
    currentIndex: Int,
    focusRequester: FocusRequester,
    onQueryChange: (TextFieldValue) -> Unit,
    onReplacementChange: (TextFieldValue) -> Unit,
    onOptionsChange: (TaskTreeSearch.Options) -> Unit,
    onToggleReplace: () -> Unit,
    onFindNext: () -> Unit,
    onFindPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    // Which of the two fields holds the caret, so Enter can mean "next match" in one and "replace" in the
    // other. Read from a key handler, so it has to survive recomposition.
    var replaceFieldFocused by remember { mutableStateOf(false) }
    val canReplace = matchCount > 0

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, SheetColors.activeBorder),
        modifier = Modifier
            // Clicks in the bar are the bar's own; they must not reach the tree's "clear selection" tap
            // handler underneath, which would drop the very selection a match had just made.
            .pointerInput(Unit) { detectTapGestures { } }
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Escape -> {
                        onClose()
                        true
                    }
                    event.key == Key.Enter && replaceFieldFocused -> {
                        if (canReplace) onReplace()
                        true
                    }
                    event.key == Key.Enter && event.isShiftPressed -> {
                        onFindPrevious()
                        true
                    }
                    event.key == Key.Enter -> {
                        onFindNext()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The chevron spans both rows, as in VS Code: it is what reveals the replacement field.
            FindBarIconButton(
                label = if (replaceExpanded) "▾" else "▸",
                enabled = true,
                onClick = onToggleReplace,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FindBarField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = "Find",
                        focusRequester = focusRequester,
                        onFocusChange = { if (it) replaceFieldFocused = false },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text =
                            when {
                                query.text.isEmpty() -> ""
                                matchCount == 0 -> "No results"
                                else -> "${currentIndex + 1} of $matchCount"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp),
                    )
                    // "Aa" is Match Case; "[ab]" — boxed, as VS Code draws it — is Match Whole Word.
                    FindBarToggle(
                        label = "Aa",
                        active = options.matchCase,
                        onClick = { onOptionsChange(options.copy(matchCase = !options.matchCase)) },
                    )
                    FindBarToggle(
                        label = "ab",
                        active = options.wholeWord,
                        boxed = true,
                        onClick = { onOptionsChange(options.copy(wholeWord = !options.wholeWord)) },
                    )
                    Spacer(Modifier.width(4.dp))
                    FindBarIconButton(
                        "↑",
                        enabled = matchCount > 0,
                        chord = ControlChords.SHIFT_ENTER,
                        onClick = onFindPrevious,
                    )
                    FindBarIconButton(
                        "↓",
                        enabled = matchCount > 0,
                        chord = ControlChords.ENTER,
                        onClick = onFindNext,
                    )
                    FindBarIconButton("✕", enabled = true, chord = ControlChords.ESCAPE, onClick = onClose)
                }
                if (replaceExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FindBarField(
                            value = replacement,
                            onValueChange = onReplacementChange,
                            placeholder = "Replace",
                            focusRequester = null,
                            onFocusChange = { replaceFieldFocused = it },
                        )
                        Spacer(Modifier.width(6.dp))
                        FindBarTextButton(
                            "Replace",
                            enabled = canReplace,
                            chord = ControlChords.ENTER_IN_REPLACE_FIELD,
                            onClick = onReplace,
                        )
                        Spacer(Modifier.width(4.dp))
                        FindBarTextButton("Replace All", enabled = canReplace, onClick = onReplaceAll)
                    }
                }
            }
        }
    }
}

/** Width of both of the bar's fields, so the query and the replacement line up. */
private val FIND_FIELD_WIDTH = 180.dp

@Composable
private fun FindBarField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(FIND_FIELD_WIDTH)
            .border(1.dp, SheetColors.grid)
            .background(SheetColors.cellBackground)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(SheetColors.activeBorder),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChange(it.isFocused) }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
    }
}

/**
 * One of the bar's square icon buttons. [chord] is the key that does the same thing while the bar holds the
 * keyboard, shown in a hover bubble ([ShortcutHint]) — the bar's buttons are unlabelled arrows, so the chord
 * is the only place their equivalence with Enter / Shift + Enter / Escape is ever stated.
 */
@Composable
private fun FindBarIconButton(
    label: String,
    enabled: Boolean,
    chord: String? = null,
    onClick: () -> Unit,
) {
    ShortcutHint(chord) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
            )
        }
    }
}

/** A latching toggle — [SheetColors.selectionFill] behind the active border is "on". */
@Composable
private fun FindBarToggle(
    label: String,
    active: Boolean,
    boxed: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(if (active) SheetColors.selectionFill else SheetColors.cellBackground)
            .border(1.dp, if (active) SheetColors.activeBorder else SheetColors.grid)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (boxed) "[$label]" else label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FindBarTextButton(
    label: String,
    enabled: Boolean,
    chord: String? = null,
    onClick: () -> Unit,
) {
    ShortcutHint(chord) {
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 22.dp)
                .border(1.dp, SheetColors.grid)
                .background(SheetColors.cellBackground)
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
            )
        }
    }
}
