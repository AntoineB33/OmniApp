package org.example.project.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.ui.TASK_ROW_MIN_HEIGHT

/**
 * User rule 2026-10-06: **a section of settings is as compact as the task tree** — the Search configurations window,
 * its twin for the added elements, and the Search window's actions section. Inside one ([CompactFields]) every control
 * that stands in a row is one task cell tall ([TASK_ROW_MIN_HEIGHT]): the app's text field ([OutlinedTextField]) draws
 * as a cell-sized field rather than Material's 56 dp one, and the drop-down faces and buttons beside it follow
 * ([fieldFaceVerticalPadding], [buttonVerticalPadding]).
 *
 * Read by the controls themselves, so an editor embedded in such a section (a quota's fields, the reminder's, a
 * timer's) is compact there and unchanged in its own window — never a second drawing of it.
 */
internal val LocalCompactFields = staticCompositionLocalOf { false }

/** [content] drawn compact ([LocalCompactFields]); Material's 48 dp minimum touch target goes with it. */
@Composable
internal fun CompactFields(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCompactFields provides true,
        LocalMinimumInteractiveComponentSize provides 0.dp,
        content = content,
    )
}

/** The gap between two rows of a compact section, and between a section's title and its rows. */
internal val COMPACT_ROW_GAP: Dp = 2.dp

/**
 * The width a compact section's content is laid out at when the section is narrower ([keepsWidthAbove]): what it does
 * not show is hidden, never wrapped onto more lines.
 */
internal val COMPACT_SECTION_MIN_WIDTH: Dp = 440.dp

/** Above and below a line of text, what makes it one task cell tall. */
private val COMPACT_FACE_PADDING: Dp = 4.dp

/** The vertical padding of a drop-down's face, or of a button standing beside a text field: the field's height. */
@Composable
internal fun fieldFaceVerticalPadding(): Dp = if (LocalCompactFields.current) COMPACT_FACE_PADDING else 14.dp

/** The vertical padding of a framed button or chip: [regular], or a task cell's in a compact section. */
@Composable
internal fun buttonVerticalPadding(regular: Dp): Dp = if (LocalCompactFields.current) COMPACT_FACE_PADDING else regular

/**
 * **Shrinking a section sideways hides its content, it never makes it taller** (user rule 2026-10-06): the content is
 * laid out at [min] at least and cut at the section's edge. Without it every row squeezed by a narrower section wraps
 * its texts, and the section grows downwards as it shrinks.
 */
internal fun Modifier.keepsWidthAbove(min: Dp): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val width = if (constraints.hasBoundedWidth) maxOf(constraints.maxWidth, min.roundToPx()) else constraints.maxWidth
        val placeable = measurable.measure(constraints.copy(maxWidth = width))
        layout(minOf(placeable.width, constraints.maxWidth), placeable.height) { placeable.place(0, 0) }
    }

/** A line of explanation in a compact section: ONE line, cut where the section ends. */
@Composable
internal fun NoteText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** [OutlinedTextField] in a compact section: a task cell's height and text, the drop-down faces' outline. */
@Composable
internal fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    textStyle: TextStyle,
    label: @Composable (() -> Unit)?,
    placeholder: @Composable (() -> Unit)?,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    prefix: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)?,
    supportingText: @Composable (() -> Unit)?,
    isError: Boolean,
    visualTransformation: VisualTransformation,
    keyboardOptions: KeyboardOptions,
    singleLine: Boolean,
    maxLines: Int,
    minLines: Int,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minWidth = COMPACT_FIELD_MIN_WIDTH, minHeight = TASK_ROW_MIN_HEIGHT),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = compactFieldTextStyle(textStyle, enabled),
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            CompactFieldDecoration(
                empty = value.isEmpty(), enabled = enabled, isError = isError, focused = focused, singleLine = singleLine,
                hint = placeholder ?: label, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                prefix = prefix, suffix = suffix, supportingText = supportingText, inner = inner,
            )
        },
    )
}

/** [CompactTextField] over a [TextFieldValue] — a field that holds its own caret. */
@Composable
internal fun CompactTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    textStyle: TextStyle,
    label: @Composable (() -> Unit)?,
    placeholder: @Composable (() -> Unit)?,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    prefix: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)?,
    supportingText: @Composable (() -> Unit)?,
    isError: Boolean,
    visualTransformation: VisualTransformation,
    keyboardOptions: KeyboardOptions,
    singleLine: Boolean,
    maxLines: Int,
    minLines: Int,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minWidth = COMPACT_FIELD_MIN_WIDTH, minHeight = TASK_ROW_MIN_HEIGHT),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = compactFieldTextStyle(textStyle, enabled),
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            CompactFieldDecoration(
                empty = value.text.isEmpty(), enabled = enabled, isError = isError, focused = focused, singleLine = singleLine,
                hint = placeholder ?: label, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                prefix = prefix, suffix = suffix, supportingText = supportingText, inner = inner,
            )
        },
    )
}

/** The narrowest a compact field given no width of its own is drawn. */
private val COMPACT_FIELD_MIN_WIDTH: Dp = 64.dp

/** What the caller asked of the text (its alignment…), at the task cell's size. */
@Composable
private fun compactFieldTextStyle(textStyle: TextStyle, enabled: Boolean): TextStyle =
    textStyle.merge(MaterialTheme.typography.bodyMedium)
        .copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_FIELD_ALPHA))

private const val DISABLED_FIELD_ALPHA: Float = 0.38f

/**
 * The compact field's frame: the drop-down faces' outline (the primary colour while it holds the focus, the error
 * colour for a text it refuses), and — while the field is empty — its placeholder, or its label in that place: a
 * compact field has no room for a label floating above it.
 */
@Composable
private fun CompactFieldDecoration(
    empty: Boolean,
    enabled: Boolean,
    isError: Boolean,
    focused: Boolean,
    singleLine: Boolean,
    hint: @Composable (() -> Unit)?,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    prefix: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)?,
    supportingText: @Composable (() -> Unit)?,
    inner: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    val line =
        when {
            isError -> MaterialTheme.colorScheme.error
            focused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        }.let { if (enabled) it else it.copy(alpha = DISABLED_FIELD_ALPHA) }
    CompositionLocalProvider(
        LocalTextStyle provides MaterialTheme.typography.bodyMedium,
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        // The frame takes the field's own constraints (a field given a minimum height is framed at that height), so it
        // is the decoration's root wherever nothing is written under it.
        val frame: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = TASK_ROW_MIN_HEIGHT)
                    .border(1.dp, line, shape)
                    .padding(horizontal = 6.dp, vertical = COMPACT_FACE_PADDING),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                leadingIcon?.invoke()
                prefix?.invoke()
                Box(Modifier.weight(1f)) {
                    if (empty) hint?.invoke()
                    inner()
                }
                suffix?.invoke()
                trailingIcon?.invoke()
            }
        }
        if (supportingText == null) {
            frame()
        } else {
            Column {
                frame()
                supportingText()
            }
        }
    }
}
