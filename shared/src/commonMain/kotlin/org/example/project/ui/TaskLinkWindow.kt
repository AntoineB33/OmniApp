package org.example.project.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TaskLinks
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TaskTreeEntry

/** The link window's frame id ([TaskLinkWindow]). */
const val TASK_LINK_FRAME_ID: String = "TaskLink"

/**
 * User rule 2026-10-20: **"link to a task of another tree"**, from a task cell's right-click — *"This option makes the user
 * select a task tree, then a task id from this task tree, but if there was already another task id from the current task
 * tree that was linked to this selected task id from the other task tree, then an error message shows up."*
 *
 * A tree of the account other than the live one, then one of its tasks (filtered by what is typed); the refusal
 * ([TaskLinks.refusal]) is said as soon as a task is picked, and "Link" is greyed while it stands. Above them, what
 * [taskId] is linked to now, tree by tree.
 */
@Composable
fun TaskLinkWindow(
    state: SchedulerState,
    taskId: TaskId,
    onLink: (TaskTreeId, TaskId) -> Unit,
    onDismiss: () -> Unit,
) {
    val others = state.taskTrees.filter { it.id != state.activeTaskTreeId }
    var treeId by remember(taskId) { mutableStateOf(others.singleOrNull()?.id) }
    var picked by remember(taskId) { mutableStateOf<TaskId?>(null) }
    var query by remember(taskId) { mutableStateOf("") }
    val tree = others.firstOrNull { it.id == treeId }
    val title = state.tasks[taskId]?.title?.ifBlank { null } ?: SchedulerDomain.UNTITLED_LABEL
    val refusal = tree?.let { t -> picked?.let { TaskLinks.refusal(state, taskId, t.id, it) } }
    val frame = rememberWindowFrameState(TASK_LINK_FRAME_ID)

    TransientPopupLayer(frame.id) {
        AppWindowFrame(
            title = "Link “$title”",
            state = frame,
            onClose = onDismiss,
            defaultWidth = 460.dp,
            defaultHeight = 520.dp,
            claimsKeyboard = true,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // What it is linked to now, in each other tree.
                val keys = TaskLinks.keys(state)
                val key = TaskLinks.keyOf(keys, taskId)
                val now = others.mapNotNull { t ->
                    linkedTasks(t).firstOrNull { (id, _) -> TaskLinks.keyOf(keys, id) == key }?.let { (_, name) -> "${t.title}: $name" }
                }
                NoteText(if (now.isEmpty()) "Linked to no task of another tree." else "Linked to — " + now.joinToString(" · "))
                if (others.isEmpty()) {
                    NoteText("The account has no other task tree.", color = MaterialTheme.colorScheme.error)
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NoteText("Task tree", color = MaterialTheme.colorScheme.onSurface)
                    ChoiceDropDown(
                        options = others,
                        selected = tree,
                        label = { it.title.ifBlank { SchedulerDomain.UNTITLED_LABEL } },
                        onSelect = {
                            treeId = it.id
                            picked = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (tree == null) return@Column
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val rows = linkedTasks(tree).filter { (_, name) -> query.isBlank() || name.contains(query.trim(), ignoreCase = true) }
                if (rows.isEmpty()) NoteText("No task of “${tree.title}” matches.")
                for ((id, name) in rows) {
                    val selected = id == picked
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = id }
                            .padding(vertical = 4.dp),
                    )
                }
                refusal?.let { NoteText(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameButton("Link", enabled = picked != null && refusal == null) {
                        picked?.let { onLink(tree.id, it) }
                    }
                    FrameButton("Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

/** The tasks a stored tree's cells hold, with their titles, alphabetically — what a link can be made to. */
private fun linkedTasks(tree: TaskTreeEntry): List<Pair<TaskId, String>> =
    tree.tree.cells.values.asSequence()
        .mapNotNull { it.taskId }
        .filter { it != WellKnownIds.ROOT_TASK }
        .distinct()
        .mapNotNull { id -> tree.tree.tasks[id]?.title?.takeIf { it.isNotBlank() }?.let { id to it } }
        .sortedBy { it.second.lowercase() }
        .toList()
