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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.claudelogs.ClaudeLogEntry
import org.example.project.claudelogs.ClaudeLogSession
import org.example.project.claudelogs.claudeLogFilesSupported
import org.example.project.scheduler.domain.SearchDomain

/**
 * **The Claude Code logs' directory**: where the logs are read from, typed and read when committed (Enter, Load) — a
 * read per keystroke would read the logs of every prefix of the path — and read again by Refresh. One field for
 * everywhere it stands: the logs' app setting's action ([SearchDomain.AddedAction.ClaudeLogsDirectory]) and the item
 * of the lateral menu made from it ([MenuControl.ClaudeLogsDirectory]).
 */
@Composable
internal fun ClaudeLogsDirectoryField(
    catalog: ClaudeLogCatalog,
    onDirectory: (String) -> Unit,
    onRefresh: () -> Unit,
    enabled: Boolean = true,
) {
    val directory = catalog.directory
    var draft by remember(directory) { mutableStateOf(directory) }
    val usable = enabled && claudeLogFilesSupported
    fun commit() = if (draft.trim() == directory.trim()) onRefresh() else onDirectory(draft.trim())
    Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            enabled = usable,
            placeholder = { Text(DIRECTORY_HINT) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (enter && event.type == KeyEventType.KeyDown) commit()
                    enter
                },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FrameButton(
                if (draft.trim() != directory.trim() || directory.isBlank()) "Load" else "Refresh",
                enabled = usable && !catalog.loading && draft.isNotBlank(),
            ) { commit() }
            NoteText(catalog.status)
        }
    }
}

/** Where the Claude Code logs usually are, shown in the empty directory field (the user defines the directory). */
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
