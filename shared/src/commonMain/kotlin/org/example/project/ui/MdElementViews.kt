package org.example.project.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.scheduler.domain.MdDomain
import org.example.project.scheduler.domain.MdElement
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-19, the action "Md elements" of any element ([SearchDomain.AddedAction.ElementMd]): the md elements
 * associated with the added elements, each drawn as markdown and edited in place ([MdBlock]), its ✕ dissociating it from
 * them (the md element stays, an element of its own); and the button adding a new one associated with them all.
 */
@Composable
internal fun MdElementsEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    run: (SearchDomain.AddedCommand) -> Unit,
) {
    val keys = remember(added) { added.map(SearchDomain::keyOf) }
    val shown = remember(state.mdElements, keys) { MdDomain.associatedWithAny(state, keys) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (shown.isEmpty()) NoteText("No md element.")
        for (md in shown) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MdBlock(md, Modifier.weight(1f)) { text -> run(SearchDomain.AddedCommand.MdSetText(md.id, text, editKey = "md/" + md.id)) }
                CrossButton { run(SearchDomain.AddedCommand.MdDissociate(md.id)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FrameButton("Add an md element", enabled = keys.isNotEmpty()) { run(SearchDomain.AddedCommand.MdAdd()) }
        }
    }
}

/** User rule 2026-11-19: an added md element's text ([SearchDomain.AddedAction.MdText]), one block per md element. */
@Composable
internal fun MdTextEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    run: (SearchDomain.AddedCommand) -> Unit,
) {
    val ids = added.filter { it.kind == SearchDomain.Kind.Md }.mapNotNull { (it as? SearchDomain.ItemResult)?.id }.toSet()
    val shown = state.mdElements.filter { it.id in ids }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (md in shown) {
            MdBlock(md, Modifier.fillMaxWidth()) { text -> run(SearchDomain.AddedCommand.MdSetText(md.id, text, editKey = "md/" + md.id)) }
        }
    }
}

/**
 * User rule 2026-11-19: what each added md element is associated with ([SearchDomain.AddedAction.MdAssociated]), by the
 * title of its row, each with the ✕ that dissociates them. [onOpen] opens the element, as a click on a result row does.
 */
@Composable
internal fun MdAssociatedEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    run: (SearchDomain.AddedCommand) -> Unit,
) {
    val ids = added.filter { it.kind == SearchDomain.Kind.Md }.mapNotNull { (it as? SearchDomain.ItemResult)?.id }.toSet()
    val shown = state.mdElements.filter { it.id in ids }
    val keys = shown.flatMap { it.of }.distinct()
    val rows = remember(keys, state.tasks, state.mdElements, state.alarms, state.timers, state.chronos, state.quotas, state.chores, state.workplaceSetups) {
        SearchDomain.resolve(state, keys).associateBy(SearchDomain::keyOf)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (keys.isEmpty()) NoteText("Associated with nothing.")
        for (key in keys) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val row = rows[key]
                Text(
                    (row?.kind?.label ?: key.substringBefore('/')) + ": " + (row?.name ?: key.substringAfter('/')),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CrossButton { shown.filter { key in it.of }.forEach { run(SearchDomain.AddedCommand.MdDissociate(it.id, listOf(key))) } }
            }
        }
    }
}

/**
 * One md element: its text drawn as markdown ([renderMarkdown]); a click turns it into its text field, which writes as it
 * is typed ([onText], one History Unit per field through its edit key) and is drawn again once it loses the focus.
 */
@Composable
internal fun MdBlock(md: MdElement, modifier: Modifier = Modifier, onText: (String) -> Unit) {
    var editing by remember(md.id) { mutableStateOf(md.text.isEmpty()) }
    var draft by remember(md.id) { mutableStateOf(md.text) }
    // Another device's (or an Undo's) text reaches a block not being typed into.
    if (!editing && draft != md.text) draft = md.text
    if (editing) {
        val focus = remember { FocusRequester() }
        var hadFocus by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                onText(it)
            },
            placeholder = { Text("markdown") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = modifier.heightIn(min = 72.dp).focusRequester(focus).onFocusChanged { f ->
                if (f.isFocused) hadFocus = true
                else if (hadFocus && draft.isNotEmpty()) editing = false
            },
        )
        LaunchedEffect(Unit) { if (md.text.isNotEmpty()) runCatching { focus.requestFocus() } }
    } else {
        Text(
            renderMarkdown(md.text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .clickable { editing = true }
                .padding(8.dp),
        )
    }
}

/**
 * The markdown an md element's text says, drawn: `#` headings, `-` / `*` / `1.` list items, `>` quotes, `**bold**`,
 * `*italic*` / `_italic_` and `` `code` ``. Whatever else is drawn as it is written.
 */
internal fun renderMarkdown(text: String): AnnotatedString =
    buildAnnotatedString {
        val lines = text.lines()
        lines.forEachIndexed { index, raw ->
            val heading = raw.takeWhile { it == '#' }.length.takeIf { it in 1..6 && raw.getOrNull(it) == ' ' }
            val trimmed = raw.trimStart()
            when {
                heading != null ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (22 - heading * 2).coerceAtLeast(14).sp)) {
                        inline(raw.drop(heading + 1))
                    }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append(" ".repeat(raw.length - trimmed.length) + "• ")
                    inline(trimmed.drop(2))
                }
                trimmed.startsWith("> ") ->
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append("│ ")
                        inline(trimmed.drop(2))
                    }
                else -> inline(raw)
            }
            if (index < lines.lastIndex) append('\n')
        }
    }

/** The inline marks of one line: `**bold**`, `*italic*` / `_italic_`, `` `code` ``; an unclosed mark is text. */
private fun AnnotatedString.Builder.inline(line: String) {
    var i = 0
    while (i < line.length) {
        val (mark, style) =
            when {
                line.startsWith("**", i) -> "**" to SpanStyle(fontWeight = FontWeight.Bold)
                line[i] == '`' -> "`" to SpanStyle(fontFamily = FontFamily.Monospace)
                line[i] == '*' || line[i] == '_' -> line[i].toString() to SpanStyle(fontStyle = FontStyle.Italic)
                else -> null to null
            }
        val end = if (mark != null) line.indexOf(mark, i + mark.length) else -1
        if (mark != null && style != null && end > i + mark.length) {
            withStyle(style) { append(line.substring(i + mark.length, end)) }
            i = end + mark.length
        } else {
            append(line[i])
            i++
        }
    }
}
