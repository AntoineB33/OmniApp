package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7 the **task picker**: the menu the "Choose the task to do now" chord
 * ([org.example.project.scheduler.platform.GlobalShortcut.PickTask]) opens at the pointer.
 *
 * Two ways of naming a task, stacked in the order they are wanted:
 *  - **the list** — every task the plan can be started on, the ones the now-line has been on most recently
 *    first ([SchedulerDomain.taskPickerEntries]), scrollable. The task the now-line is on right now is not in
 *    it: this list is what to switch *to*, so the first row is the task worked before the current one and
 *    the chord followed by Enter means "back to what I was doing";
 *  - **the search field** under it, which is a task cell in Edit Mode and nothing else: the same
 *    [EditModeMenuBlock] every naming field in the app renders through, with the **Tasks** id rows (the
 *    tasks whose title IS what is typed — those *act*) over the **Title suggestions** (which only fill the
 *    field, here as everywhere). There is no Mode selector and no "New task" row, because the picker names
 *    an existing task and creating one would name nothing that can be started.
 *
 * The keyboard: ↑/↓ move the highlight in the list, Enter takes what
 * [SchedulerDomain.taskPickerCommit] says (the highlighted row, or — once the field holds text — the task
 * that text names), Escape leaves. The field holds the focus from the moment the menu opens, so the user can
 * type straight away; the arrows and Enter are read *before* it in the preview pass, so they still mean the
 * list.
 *
 * **What the now-line forbids is written in the rows themselves**: a task with a resilience of `0` to the
 * periods covering the line right now is **red** — the plan cannot place it here however it is asked — and
 * one between `0` and `1` is **orange**, its share merely scaled while the period lasts
 * ([SchedulerDomain.taskResilienceAt]). Those colours follow [restrictionNowMillis], the display's own
 * instant, so they change the moment the line crosses into new periods and never on a timer of their own.
 *
 * The state of the menu is the menu's own and lives exactly as long as it does: this is a question, not an
 * edit session, and the only thing it can leave behind is the one intent [onPick] dispatches.
 */
@Composable
fun TaskPickerMenu(
    state: SchedulerState,
    /** The instant the chord was struck — frozen, so the list cannot re-order itself under the pointer. */
    nowMillis: Long,
    /**
     * The LIVE display instant, which the rows' colours are read at. Deliberately not [nowMillis]: the order
     * is a fact about the moment the user asked, but what the timeline forbids is a fact about *now*, and the
     * menu stands open across period boundaries. It moves when the display resamples — at the boundaries the
     * panels name — so a row turns red the moment the line enters a period that refuses it.
     */
    restrictionNowMillis: Long,
    onPick: (TaskId) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = remember(state, nowMillis) { SchedulerDomain.taskPickerEntries(state, nowMillis) }
    // The kinds the line is inside, read ONCE per composition rather than per row: the rows ask the same
    // question of the same instant, and a picker on a large account would otherwise walk the panel list
    // once per task.
    val restrictiveKinds =
        remember(state.panels, restrictionNowMillis) {
            SchedulerDomain.restrictiveKindsAt(state, restrictionNowMillis)
        }
    fun restrictionColorOf(taskId: TaskId): Color? =
        restrictionColor(SchedulerDomain.taskResilienceIn(state, taskId, restrictiveKinds))
    var draft by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val fieldFocus = remember { FocusRequester() }

    fun move(delta: Int) {
        if (entries.isEmpty()) return
        highlighted = (highlighted + delta).coerceIn(0, entries.size - 1)
    }

    fun commit() {
        val taskId = SchedulerDomain.taskPickerCommit(state, entries, draft, highlighted) ?: return
        onPick(taskId)
    }

    // The field holds the focus from the moment the menu opens — that is what lets the user type straight
    // away, and it is also what puts the `onPreviewKeyEvent` below on the path a keystroke takes, so it is
    // what makes Escape, Enter and the arrows work at all.
    //
    // Keyed on **whether the window is focused**, not on `Unit`: this menu is the one surface of the app in
    // an OS window of its own (`shortcuts.md`), and that window is still taking the foreground from the
    // application the chord was struck in when this first composes. A focus request made before the window
    // itself is focused is dropped, and what is left is a menu that holds the keyboard and has nothing
    // inside it to give the keystrokes to — the state the user met as "Escape does nothing until I click
    // the menu once", the click being the only other thing that could focus the field.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (windowFocused) fieldFocus.requestFocus() }
    // Keep the highlighted row in view when the arrows walk it past the viewport — the list is the half of
    // the menu the keyboard drives, so a highlight the user cannot see is a highlight they cannot trust.
    LaunchedEffect(highlighted) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(highlighted.coerceIn(0, entries.size - 1))
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.Enter, Key.NumPadEnter -> { commit(); true }
                        Key.DirectionDown -> { move(1); true }
                        Key.DirectionUp -> { move(-1); true }
                        else -> false
                    }
                }
            },
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = "Choose the task to do now",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Most recently worked first. Enter takes the highlighted one; Escape closes.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (entries.isEmpty()) {
                    Text(
                        text = "No other task to switch to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(entries, key = { _, entry -> entry.taskId.value }) { index, entry ->
                            TaskPickerRow(
                                label = entry.label,
                                ago = taskTouchedAgoLabel(entry.lastTouchedMillis, nowMillis),
                                highlighted = index == highlighted,
                                color = restrictionColorOf(entry.taskId),
                                onClick = { onPick(entry.taskId) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Find a task") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EditModeMenuBlock(
                    identityLabel = "Tasks",
                    identityRows =
                        SchedulerDomain.taskPickerIdentityRows(state, draft).mapNotNull { row ->
                            val taskId = row.taskId ?: return@mapNotNull null
                            // The same red/orange as the list above: a row names one task, so it can say what
                            // the now-line makes of it. A title *suggestion* cannot — it names a string
                            // several tasks may share — so those keep the ordinary colour.
                            EditMenuItem(label = row.label, color = restrictionColorOf(taskId)) { onPick(taskId) }
                        },
                    // A suggestion only fills the field, as in every other naming field of the app: it names
                    // a title, and a title several tasks share names no task. Filling it is what then raises
                    // the id rows above, which do name one.
                    suggestions =
                        SchedulerDomain.placeableTaskTitleSuggestions(state, draft).map { suggestion ->
                            EditMenuItem(label = suggestion) { draft = suggestion }
                        },
                    focusPreserving = true,
                )
            }
        }
    }
}

/** One row of the picker's list: what the task is, and when the now-line was last on it. */
@Composable
private fun TaskPickerRow(
    label: String,
    ago: String,
    highlighted: Boolean,
    /** [restrictionColor] — what the periods covering the now-line make of this task, or null for nothing. */
    color: Color?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = ago,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * PRD §7: **a task the now-line's own periods refuse is written in red; one they merely scale, in orange.**
 *
 * The two ends of the resilience model (`docs/invariants/scheduler.md` § *Resilience is the whole of "where
 * may this task run"*): `0` is a task the plan cannot place at this instant however it is asked — picking it
 * would leave the timeline to somebody else — and anything between `0` and `1` is a task whose share is being
 * scaled for as long as the period lasts. `1` is unaffected and wears no colour: a menu where most rows are
 * coloured says nothing.
 *
 * The hues are the app's own — the now-line's red and the §18 alarm orange — so the menu belongs to the same
 * palette as the calendar it is about.
 */
internal fun restrictionColor(resilience: Double): Color? = when {
    resilience <= 0.0 -> Color(0xFFD93025)
    resilience < 1.0 -> Color(0xFFE8710A)
    else -> null
}

/**
 * How long ago the now-line was last on a task, as the picker's list prints it.
 *
 * Coarse on purpose, and coarser the further back it goes: the number is there to tell two rows apart, not
 * to be read as a duration — the exact spans are the calendar's job. A task the line has never been on says
 * so, because "never" is the one answer the ordering itself cannot show (those rows are simply last).
 */
internal fun taskTouchedAgoLabel(lastTouchedMillis: Long?, nowMillis: Long): String {
    if (lastTouchedMillis == null) return "never"
    val elapsed = (nowMillis - lastTouchedMillis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = elapsed / 3_600_000L
    val days = elapsed / 86_400_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        else -> "$days d ago"
    }
}
