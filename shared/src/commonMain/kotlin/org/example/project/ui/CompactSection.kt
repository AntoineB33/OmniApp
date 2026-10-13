package org.example.project.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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

/**
 * User rule 2026-10-11: *"To every search bar, add a cross at the right side."* — the ✕ at the right end of a search
 * bar: a press empties the bar ([onClear]). Always drawn, so the bar keeps its shape as it is typed in; set back and
 * deaf while the bar is empty and there is nothing to take away. Given as the field's `trailingIcon`.
 *
 * One drawing for every search bar of the app (the Search window's, the two configuration windows', the actions
 * section's, the notification filter's, the task picker's). The find bar's ✕ is another thing: it closes the bar.
 */
@Composable
internal fun SearchBarCross(text: String, onClear: () -> Unit) {
    val empty = text.isEmpty()
    androidx.compose.material3.Text(
        text = "✕",
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (empty) 0.35f else 1f),
        modifier = androidx.compose.ui.Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(enabled = !empty, onClick = onClear)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * User rule 2026-10-13: *"In a search bar, when pressing escape, it should revert the search bar content to when the
 * user entered edit mode."* — put on a search bar's field: what the bar held when it took the keyboard is remembered,
 * and Escape hands it back ([onRevert]) while the bar still has the keyboard. Escape with nothing changed is left to
 * whoever is under the bar; the focus leaving ends the session, so the next one starts from what the bar holds then.
 *
 * One answer for every search bar that keeps its text (the Search window's, the two configuration windows', the
 * actions section's, the notification filter's). NOT the two whose Escape closes them — the task picker's and the
 * tree's find bar: there the key already has its meaning.
 */
@Composable
internal fun androidx.compose.ui.Modifier.revertsOnEscape(text: String, onRevert: (String) -> Unit): androidx.compose.ui.Modifier {
    var entered by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val current by androidx.compose.runtime.rememberUpdatedState(text)
    val revert by androidx.compose.runtime.rememberUpdatedState(onRevert)
    return this
        .onFocusChanged { entered = if (it.isFocused) entered ?: current else null }
        .onPreviewKeyEvent { event ->
            val was = entered
            if (event.type != KeyEventType.KeyDown || event.key != Key.Escape || was == null || was == current) {
                false
            } else {
                revert(was)
                true
            }
        }
}

/**
 * The account's state, for the configurations that say their **exceptions** ([PatternExceptionsNote]): the Sleep
 * schedule's fields and an alarm's row are drawn in several windows from the configuration alone, and an exception is
 * read off more than it (the block the hand made of the occurrence). `App` provides it; null outside the app (tests).
 */
internal val LocalPatternExceptionState =
    androidx.compose.runtime.compositionLocalOf<org.example.project.scheduler.state.SchedulerState?> { null }

/** How the exceptions' fields write: the app's dispatch. `App` provides it; null outside the app (the fields then only read). */
internal val LocalPatternExceptionDispatch =
    androidx.compose.runtime.compositionLocalOf<((org.example.project.scheduler.state.SchedulerIntent) -> Unit)?> { null }

/** How many exceptions a configuration lists before it only counts the earlier ones. */
private const val EXCEPTIONS_SHOWN: Int = 6

/**
 * `docs/scheduler_input_requirements.md`, its last paragraph: *"the configuration that created this orange outlined
 * block/chip gets an 'exception' info"*. **The exceptions of a configuration, under its fields** — one per occurrence
 * a hand edited out of the pattern: where the pattern would have laid it, then THE BLOCK IT IS NOW, drawn as "Blocks
 * on the calendar" draws a block ([CalendarBlockFields], user rule 2026-10-11) — its fields edit it on the calendar.
 * An occurrence no block is left of reads "removed". The latest [EXCEPTIONS_SHOWN], the earlier ones counted.
 * Nothing where the pattern has none.
 */
@Composable
internal fun PatternExceptionsNote(
    entries: List<org.example.project.scheduler.domain.PatternExceptions.Entry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val state = LocalPatternExceptionState.current
    val dispatch = LocalPatternExceptionDispatch.current
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    val now = org.example.project.scheduler.state.SchedulerReducer.clock.nowMillis()
    val today = localOf(now, tz).date
    val blocks =
        remember(state?.panels, state?.alarms, state?.timers) {
            state?.let { org.example.project.scheduler.domain.SearchDomain.calendarBlocks(it, tz) }.orEmpty().associateBy { it.id }
        }
    val labelled = org.example.project.scheduler.domain.PatternExceptions.labelled(entries)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (entries.size == 1) "Exception" else "Exceptions (${entries.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val earlier = entries.size - EXCEPTIONS_SHOWN
        if (earlier > 0) {
            Text("… and $earlier earlier", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (entry in entries.takeLast(EXCEPTIONS_SHOWN)) {
            val block = entry.blockId?.let(blocks::get)
            if (block == null || state == null) {
                Text(
                    org.example.project.scheduler.domain.PatternExceptions.text(entry, tz, labelled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                continue
            }
            androidx.compose.runtime.key(block.id) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "instead of " + org.example.project.scheduler.domain.PatternExceptions.occurrenceText(entry, tz, labelled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CalendarBlockFields(block, block.startMillis, block.endMillis, block.startMillis, block.endMillis, today, tz) { start, end ->
                        org.example.project.scheduler.domain.SearchDomain.blockEditIntent(state, block, start, end, now, tz)?.let { dispatch?.invoke(it) }
                    }
                }
            }
        }
    }
}
