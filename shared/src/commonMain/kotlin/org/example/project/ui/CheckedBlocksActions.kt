package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-25 (*"the search window is the only window of the app"*): **one row of the "Checked blocks" group**
 * ([SearchDomain.BLOCK_EDIT_ACTIONS]) — what the calendar's block editors asked, over the blocks checked in "Blocks on the
 * calendar" ([SearchDomain.Config.pickedBlocks]). A control shows the value the checked blocks share (none where they
 * differ) and writes each one's panel at once ([SearchDomain.blockPanelIntent]).
 */
@Composable
internal fun CheckedBlocksActionEditor(
    state: SchedulerState,
    action: SearchDomain.AddedAction,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    nowMillis: () -> Long,
    onIntent: (SchedulerIntent) -> Unit,
) {
    val tz = remember { TimeZone.currentSystemDefault() }
    val blocks = SearchDomain.blocksOfAdded(state, added, tz).filter { it.id in config.pickedBlocks }
    val panels = blocks.mapNotNull { block -> SearchDomain.blockPanel(state, block)?.let { block to it } }
    val tasks = panels.filter { !it.second.chore && !it.second.isRestrictivePeriod }
    val periods = panels.filter { it.second.isRestrictivePeriod }
    val tags = panels.filter { it.second.chore }
    fun each(of: List<Pair<SearchDomain.CalendarBlock, *>>, intent: (SearchDomain.CalendarBlock) -> SchedulerIntent?) {
        of.forEach { (block, _) -> intent(block)?.let(onIntent) }
    }
    if (blocks.isEmpty()) {
        NoteText("Check blocks in “Blocks on the calendar”.")
        return
    }
    when (action) {
        SearchDomain.AddedAction.BlockTask -> {
            if (tasks.isEmpty()) return NoteText("No checked block is a task's.")
            val shared = tasks.map { it.second.taskId }.distinct().singleOrNull()
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    FrameButton("Choose…") { open = !open }
                    val entries = SchedulerDomain.calendarTaskMenuEntries(state, "", null)
                    OptionSearchMenu(
                        "task of the blocks",
                        open = open,
                        onDismiss = { open = false },
                        options = SearchOptions(
                            options = entries.map { (it.taskId?.value ?: NEW_TASK_OPTION) to it.label },
                            checked = null,
                            onClick = { id ->
                                val task = if (id == NEW_TASK_OPTION) null else org.example.project.scheduler.model.TaskId(id)
                                each(tasks) { block ->
                                    SearchDomain.blockPanelIntent(state, block, taskId = task, title = task?.let { state.tasks[it]?.title })
                                }
                                open = false
                            },
                        ),
                    )
                }
                Text(shared?.let { SchedulerDomain.taskTitleLabel(state, it) } ?: if (tasks.size > 1) "(they differ)" else "(none)")
            }
        }
        SearchDomain.AddedAction.BlockPins -> {
            val pinned = tasks + periods
            if (pinned.isEmpty()) return NoteText("No checked block is a task's or a period's.")
            fun shared(of: (PanelPins) -> Boolean) = pinned.all { of(it.second.pins) }
            fun set(change: (PanelPins) -> PanelPins) =
                pinned.forEach { (block, panel) -> SearchDomain.blockPanelIntent(state, block, pins = change(panel.pins))?.let(onIntent) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PinSwitchRow("Existence", shared { it.existence }) { on -> set { it.copy(existence = on) } }
                PinSwitchRow("Position", shared { it.position }) { on -> set { it.copy(position = on) } }
                PinSwitchRow("Spanning", shared { it.spanning }) { on -> set { it.copy(spanning = on) } }
                PinSwitchRow("Distance", shared { it.distance }) { on -> set { it.copy(distance = on) } }
            }
        }
        SearchDomain.AddedAction.BlockRepeat -> {
            val repeated = tasks + periods
            if (repeated.isEmpty()) return NoteText("No checked block is a task's or a period's.")
            val shared: Int? = repeated.map { it.second.repeat?.everyDays ?: 0 }.distinct().singleOrNull()
            var text by remember(shared) { mutableStateOf(shared?.let { repeatDaysText(it) }.orEmpty()) }
            Column(Modifier.width(240.dp)) {
                RepeatEveryField(text) { typed ->
                    text = typed
                    val days = parseRepeatDays(typed)
                    each(repeated) { block -> SearchDomain.blockPanelIntent(state, block, repeatEveryDays = days) }
                }
            }
        }
        SearchDomain.AddedAction.BlockOpenBounds -> {
            if (periods.isEmpty()) return NoteText("No checked block is a period's.")
            val openStart = periods.all { SchedulerDomain.isOpenPast(it.second.startEpochMillis) }
            val openEnd = periods.all { SchedulerDomain.isOpenFuture(it.second.endEpochMillis) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PinSwitchRow("Starts in the infinite past", openStart) { on ->
                    each(periods) { block ->
                        SearchDomain.blockPanelIntent(state, block, startMillis = if (on) SchedulerDomain.OPEN_PAST_MILLIS else nowMillis())
                    }
                }
                PinSwitchRow("Ends in the infinite future", openEnd) { on ->
                    each(periods) { block ->
                        SearchDomain.blockPanelIntent(state, block, endMillis = if (on) SchedulerDomain.OPEN_FUTURE_MILLIS else nowMillis())
                    }
                }
            }
        }
        SearchDomain.AddedAction.BlockReminderTag -> {
            if (tags.isEmpty()) return NoteText("No checked block is a reminder's tag.")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PinSwitchRow("Done", tags.all { it.second.checked }) { on ->
                    each(tags) { block -> SearchDomain.blockPanelIntent(state, block, reminderChecked = on) }
                }
                PinSwitchRow("Pinned", tags.all { it.second.pinned }) { on ->
                    each(tags) { block -> SearchDomain.blockPanelIntent(state, block, reminderPinned = on) }
                }
            }
        }
        else -> Unit
    }
}

/** The "New task" row of the task drop-down ([CheckedBlocksActionEditor]): a panel of the calendar's own. */
private const val NEW_TASK_OPTION: String = "new task"
