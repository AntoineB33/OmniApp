package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.claudelogs.ClaudeLogEntry
import org.example.project.claudelogs.ClaudeLogSession
import org.example.project.claudelogs.ClaudeLogsSettings
import org.example.project.claudelogs.claudeLogFilesSupported
import org.example.project.scheduler.domain.SearchDomain

/**
 * **The Claude Code logs' directory**: where the logs are read from, typed and read when committed (Enter, Load) — a
 * read per keystroke would read the logs of every prefix of the path — and read again by Refresh. One field for
 * everywhere it stands: the logs' app setting's action ([SearchDomain.AddedAction.ClaudeLogsDirectory]) and the item
 * of the lateral menu made from it ([MenuControl.ClaudeLogsDirectory]).
 *
 * The folder Claude Code keeps its logs in on this device ([ClaudeLogCatalog.suggestion]) is shown as **ghost text**:
 * the whole of it in an empty field, the rest of it after what is typed while what is typed is its start. **Tab writes
 * it in** ([ClaudeLogsSettings.directoryCompletion]), the cursor at its end; anywhere else Tab moves on as it always does.
 */
@Composable
internal fun ClaudeLogsDirectoryField(
    catalog: ClaudeLogCatalog,
    onDirectory: (String) -> Unit,
    onRefresh: () -> Unit,
    enabled: Boolean = true,
) {
    val directory = catalog.directory
    var draft by remember(directory) { mutableStateOf(TextFieldValue(directory, TextRange(directory.length))) }
    val usable = enabled && claudeLogFilesSupported
    val completion = ClaudeLogsSettings.directoryCompletion(draft.text, catalog.suggestion)
    val ghost = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    fun commit() = if (draft.text.trim() == directory.trim()) onRefresh() else onDirectory(draft.text.trim())
    Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            enabled = usable,
            // Without a folder to suggest, where the logs usually are, as a hint only.
            placeholder = if (catalog.suggestion == null) ({ Text(DIRECTORY_HINT) }) else null,
            visualTransformation = completion?.let { GhostSuffix(it.substring(draft.text.length), ghost) } ?: VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                            commit()
                            true
                        }
                        event.key == Key.Tab && completion != null &&
                            !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed -> {
                            draft = TextFieldValue(completion, TextRange(completion.length))
                            true
                        }
                        else -> false
                    }
                },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FrameButton(
                if (draft.text.trim() != directory.trim() || directory.isBlank()) "Load" else "Refresh",
                enabled = usable && !catalog.loading && draft.text.isNotBlank(),
            ) { commit() }
            NoteText(if (completion != null && draft.text.isEmpty()) "Tab: " + completion else catalog.status)
        }
    }
}

/**
 * The rest of a suggestion drawn after what is typed, greyed, as ghost text: shown only, never part of the value — the
 * cursor and the selection stay within what is typed.
 */
private class GhostSuffix(private val suffix: String, private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val typed = text.length
        val shown = buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = color)) { append(suffix) }
        }
        return TransformedText(
            shown,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = offset
                override fun transformedToOriginal(offset: Int): Int = offset.coerceAtMost(typed)
            },
        )
    }

    override fun equals(other: Any?): Boolean = other is GhostSuffix && other.suffix == suffix && other.color == color

    override fun hashCode(): Int = suffix.hashCode() * 31 + color.hashCode()
}

/** Where the Claude Code logs usually are, hinted in the empty field where no folder of them is found on this device. */
private const val DIRECTORY_HINT = "C:\\Users\\<you>\\.claude\\projects"

/**
 * The actions that show a Claude Code log entry ([SearchDomain.CLAUDE_ENTRY_ACTIONS]), one block per added entry of
 * the action's kind: its whole text (selectable, with its copy button), the prompt it followed, or the session it was
 * written in — the facts each on one line, with their arrow and copy button, as a history unit's are.
 */
@Composable
internal fun ClaudeEntryAction(action: SearchDomain.AddedAction, added: List<SearchDomain.Result>, catalog: ClaudeLogCatalog) {
    val kind = action.section ?: return
    val rows = added.filterIsInstance<SearchDomain.ItemResult>().filter { it.kind == kind }
    if (rows.isEmpty()) {
        NoteText("No ${kind.label} is added.", color = MaterialTheme.colorScheme.onSurface)
        return
    }
    for (row in rows) {
        ElementHeading(row.name, rows.size)
        val found = catalog.find(row.id)
        if (found == null) {
            NoteText("No longer in the logs read from the directory.")
            continue
        }
        val (session, entry) = found
        when (action) {
            SearchDomain.AddedAction.PromptText, SearchDomain.AddedAction.AnswerText, SearchDomain.AddedAction.ReasoningText -> {
                SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodyMedium) }
                HistoryCopyButton(label = "Copy", value = entry.text)
            }
            SearchDomain.AddedAction.AnswerPrompt, SearchDomain.AddedAction.ReasoningPrompt ->
                entry.replyTo?.let { HistoryTextLine(value = it, copyLabel = "Copy the prompt") }
                    ?: NoteText("No prompt before it in its session.")
            else -> sessionFacts(session, entry).forEach { HistoryInfoLine(it) }
        }
    }
}

/** What is known of the session [entry] was written in: one fact per line. */
private fun sessionFacts(session: ClaudeLogSession, entry: ClaudeLogEntry): List<HistoryInfo> {
    val zone = TimeZone.currentSystemDefault()
    return listOfNotNull(
        entry.timeMillis?.let { HistoryInfo("Written", Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).toString().replace('T', ' ').substringBefore('.')) },
        HistoryInfo("Project", session.project),
        session.title?.let { HistoryInfo("Session", it) },
        HistoryInfo("Session id", session.sessionId),
        HistoryInfo("Log file", session.path),
    )
}
