package org.example.project.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.scheduler.domain.MdDomain
import org.example.project.scheduler.domain.MdElement
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.platform.claudeCodeOpensHere
import org.example.project.scheduler.platform.openClaudeCodeOn
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
                MdBlock(md, Modifier.weight(1f), state.mdElements, mdLinkNew(state, md, run)) { text -> run(SearchDomain.AddedCommand.MdSetText(md.id, text, editKey = "md/" + md.id)) }
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
            MdBlock(md, Modifier.fillMaxWidth(), state.mdElements, mdLinkNew(state, md, run)) { text -> run(SearchDomain.AddedCommand.MdSetText(md.id, text, editKey = "md/" + md.id)) }
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
 * User rule 2026-11-21: *"In the actions of an md document, there must be one to open Claude Code to directly ask it
 * questions that Claude Code will easily access the graph of md files, starting in the directory path of the folder
 * containing all the md files the action is associated with."* ([SearchDomain.AddedAction.MdAskClaude]). The press writes
 * every md element into the device's md folder, the added ones named as where to start ([MdDomain.claudeFolderFiles]),
 * and opens Claude Code there; the folder, or what went wrong, is said under it.
 */
@Composable
internal fun MdAskClaudeButton(state: SchedulerState, added: List<SearchDomain.Result>) {
    val ids = added.filter { it.kind == SearchDomain.Kind.Md }.mapNotNull { (it as? SearchDomain.ItemResult)?.id }
    var opening by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val here = remember { claudeCodeOpensHere() }
    val latest by androidx.compose.runtime.rememberUpdatedState(state)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FrameButton(if (opening) "Opening…" else "Ask Claude Code", enabled = here && !opening && ids.isNotEmpty()) {
            opening = true
            said = null
            openClaudeCodeOn(MdDomain.claudeFolderFiles(latest, ids)) { folder, error ->
                opening = false
                said = if (error != null) error to true else ("Opened in " + folder) to false
            }
        }
        val note = said
        when {
            !here -> NoteText("This device does not open Claude Code: use the desktop app.")
            note != null -> if (note.second) NoteText(note.first, color = MaterialTheme.colorScheme.error) else NoteText(note.first)
            else -> NoteText("Writes the md elements into a folder, a file each, and opens Claude Code there. Edits made there stay there.")
        }
    }
}

/**
 * One md element: its text drawn as markdown ([renderMarkdown]); a click turns it into its text field, which writes as it
 * is typed ([onText], one History Unit per field through its edit key) and is drawn again once it loses the focus — or
 * (user rule 2026-11-25) at the first press anywhere outside it, which a bare stretch of a window does without taking the
 * focus ([leaveOnOutsidePress]). An empty one is drawn as a line saying so, which a click edits.
 */
@Composable
internal fun MdBlock(
    md: MdElement,
    modifier: Modifier = Modifier,
    /** Every md element of the account: what a `[[` offers ([MdLinkPopup]). */
    all: List<MdElement> = emptyList(),
    /** A `[[`'s creation row: this block's text with the link written, and the new md element it names, in one change. */
    onLinkNew: ((String, MdElement) -> Unit)? = null,
    onText: (String) -> Unit,
) {
    var editing by remember(md.id) { mutableStateOf(md.text.isEmpty()) }
    var value by remember(md.id) { mutableStateOf(TextFieldValue(md.text)) }
    val draft = value.text
    // Another device's (or an Undo's) text reaches a block not being typed into.
    if (!editing && draft != md.text) value = TextFieldValue(md.text)
    if (editing) {
        val focus = remember { FocusRequester() }
        var hadFocus by remember { mutableStateOf(false) }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        // User rule 2026-11-25: the link being written at the caret, and the one whose pop-up was sent away (by where it starts).
        val link = if (value.selection.collapsed) MdDomain.openLinkAt(draft, value.selection.start) else null
        var dismissed by remember(md.id) { mutableStateOf<Int?>(null) }
        if (link == null && dismissed != null) dismissed = null
        Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                val changed = it.text != value.text
                value = it
                if (changed) onText(it.text)
            },
            placeholder = { Text("markdown — [[Name]] links an md element, [[>Name]] makes it a child") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .leaveOnOutsidePress(active = true) {
                    editing = false
                    focusManager.clearFocus()
                }
                .focusRequester(focus).onFocusChanged { f ->
                    if (f.isFocused) hadFocus = true
                    else if (hadFocus) editing = false
                },
        )
        if (link != null && dismissed != link.start) {
            // Keyed by the link: once it is closed (or another is begun) this pop-up is gone with it.
            androidx.compose.runtime.key(link.start) {
                MdLinkPopup(
                    current = md,
                    all = all,
                    typed = link.typed,
                    onDismiss = { dismissed = link.start },
                    onPick = { picked ->
                        val (text, caret) = MdDomain.completeLink(draft, value.selection.start, link, MdDomain.linkName(picked))
                        value = TextFieldValue(text, TextRange(caret))
                        onText(text)
                    },
                    onNew = onLinkNew?.let { made ->
                        {
                            val id = MdDomain.mintId(all.map { it.id })
                            val element = MdElement(id = id, text = link.typed.trim())
                            val (text, caret) = MdDomain.completeLink(draft, value.selection.start, link, MdDomain.linkName(element))
                            value = TextFieldValue(text, TextRange(caret))
                            made(text, element)
                        }
                    },
                )
            }
        }
        }
        LaunchedEffect(Unit) { if (md.text.isNotEmpty()) runCatching { focus.requestFocus() } }
    } else {
        Text(
            if (md.text.isEmpty()) AnnotatedString("Empty — click to write.") else renderMarkdown(md.text),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (md.text.isEmpty()) FontStyle.Italic else null,
            modifier = modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .clickable { editing = true }
                .padding(8.dp),
        )
    }
}

/** What a `[[`'s creation row does in [md]'s block: the block's text and the new md element, one History Unit. */
private fun mdLinkNew(state: SchedulerState, md: MdElement, run: (SearchDomain.AddedCommand) -> Unit): (String, MdElement) -> Unit =
    { text, made ->
        val next = state.mdElements.map { if (it.id == md.id) it.copy(text = text) else it } + made
        run(SearchDomain.AddedCommand.Raw(org.example.project.scheduler.state.SchedulerIntent.SetMdElements(next)))
    }

/** The id of a `[[`'s creation row ([MdLinkPopup]). */
private const val NEW_MD_OPTION: String = "new md"

/**
 * User rule 2026-11-25: **the pop-up a `[[` or `[[>` shows in an md text** — every md element but [current], its creation
 * row at the top, narrowed to those whose name holds what is typed after the brackets ([typed]). A Search window of them
 * ([OptionSearchMenu]) hanging from the field, which keeps the caret: a click on one writes its name and closes the link
 * ([MdDomain.completeLink]); the creation row makes an md element of that name and links it.
 */
@Composable
private fun MdLinkPopup(
    current: MdElement,
    all: List<MdElement>,
    typed: String,
    onDismiss: () -> Unit,
    onPick: (MdElement) -> Unit,
    onNew: (() -> Unit)?,
) {
    val wanted = typed.trim()
    val others = remember(all, current.id, wanted) {
        all.filter { it.id != current.id }
            .filter { wanted.isEmpty() || MdDomain.linkName(it).contains(wanted, ignoreCase = true) }
            .sortedBy { MdDomain.linkName(it).lowercase() }
    }
    val byId = others.associateBy { it.id }
    val creation =
        if (onNew == null) emptyList()
        else listOf(NEW_MD_OPTION to (if (wanted.isEmpty()) "New md element" else "New md element “$wanted”"))
    OptionSearchMenu(
        "md links",
        open = true,
        onDismiss = onDismiss,
        focusable = false,
        options = SearchOptions(
            options = creation + others.map { it.id to MdDomain.linkName(it) },
            checked = null,
            onClick = { id -> if (id == NEW_MD_OPTION) onNew?.invoke() else byId[id]?.let(onPick) },
        ),
    )
}

/**
 * The markdown an md element's text says, drawn: `#` headings, `-` / `*` / `1.` list items, `>` quotes, `**bold**`,
 * `*italic*` / `_italic_`, `` `code` `` and the links to other md elements, `[[Name]]` / `[[>Name]]` (a child). Whatever
 * else is drawn as it is written.
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
        // User rule 2026-11-21: `[[Name]]` is a link to another md element, drawn underlined; `[[>Name]]` makes it a child,
        // drawn with "▸" in front ([MdDomain.links]).
        if (line.startsWith("[[", i)) {
            val close = line.indexOf("]]", i + 2)
            val link = if (close > i) MdDomain.links(line.substring(i, close + 2)).singleOrNull() else null
            if (link != null) {
                if (link.child) append("▸")
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(link.shown) }
                i = close + 2
                continue
            }
        }
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
