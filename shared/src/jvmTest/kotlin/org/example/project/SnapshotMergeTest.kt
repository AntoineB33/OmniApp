package org.example.project

import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.NoOpDelta
import org.example.project.scheduler.state.SchedulerHistories
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.SnapshotMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three-way merge rules of [SnapshotMerge] — the answer to "two devices edited the account at the same
 * time". Each test states one rule and the concrete divergence it resolves; together they pin down that a
 * merge never loses work a plain last-write-wins pull would have kept.
 */
class SnapshotMergeTest {
    private fun taskId(title: String) = TaskId("task/$title")

    private fun cellId(name: String) = CellId("cell/main/$name")

    /**
     * A tree of one row per [rows] entry (`cell name to task title`) under the main list — the shape every
     * merge below diverges from. Rows are reachable from the root, so the repair pass keeps them.
     */
    private fun tree(vararg rows: Pair<String, String>): SchedulerState {
        val empty = SchedulerState.empty()
        val cells =
            rows.associate { (name, title) ->
                cellId(name) to Cell(cellId(name), WellKnownIds.MAIN_LIST, taskId(title))
            }
        val tasks =
            rows.associate { (name, title) ->
                taskId(title) to Task(taskId(title), title, occurrences = listOf(cellId(name)))
            }
        return empty.copy(
            lists =
                empty.lists +
                    (WellKnownIds.MAIN_LIST to empty.lists.getValue(WellKnownIds.MAIN_LIST)
                        .copy(cellIds = rows.map { cellId(it.first) })),
            cells = cells,
            tasks = empty.tasks + tasks,
        )
    }

    private fun SchedulerState.titles(): List<String> =
        lists.getValue(WellKnownIds.MAIN_LIST).cellIds.mapNotNull { cells[it]?.taskId?.let { id -> tasks[id]?.title } }

    private fun SchedulerState.task(title: String): Task? = tasks[taskId(title)]

    private fun SchedulerState.withRow(name: String, title: String): SchedulerState {
        val list = lists.getValue(WellKnownIds.MAIN_LIST)
        return copy(
            lists = lists + (WellKnownIds.MAIN_LIST to list.copy(cellIds = list.cellIds + cellId(name))),
            cells = cells + (cellId(name) to Cell(cellId(name), WellKnownIds.MAIN_LIST, taskId(title))),
            tasks = tasks + (taskId(title) to Task(taskId(title), title, occurrences = listOf(cellId(name)))),
        )
    }

    private fun SchedulerState.withoutRow(name: String, title: String): SchedulerState {
        val list = lists.getValue(WellKnownIds.MAIN_LIST)
        return copy(
            lists = lists + (WellKnownIds.MAIN_LIST to list.copy(cellIds = list.cellIds - cellId(name))),
            cells = cells - cellId(name),
            tasks = tasks - taskId(title),
        )
    }

    private fun panel(id: String, title: String, start: Long) =
        TaskPanel(id = id, taskId = null, title = title, startEpochMillis = start, endEpochMillis = start + 1000, pinned = true)

    @Test
    fun rows_added_on_both_devices_at_once_all_survive() {
        // The everyday concurrent edit: the desktop adds a task while the phone adds another. Under
        // last-write-wins one of the two simply never existed.
        val base = tree("0" to "A")
        val local = base.withRow("1", "Desktop")
        val remote = base.withRow("2", "Phone")

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        // Both rows are kept; each lands after the neighbour it followed on its own device.
        assertEquals(listOf("A", "Desktop", "Phone"), merged.titles())
    }

    @Test
    fun independent_edits_to_the_same_task_are_both_applied() {
        // Field-level: renaming a task on one device and raising its minimum time on the other must keep both,
        // which resolving the whole Task as one value could not.
        val base = tree("0" to "A")
        val local = base.copy(tasks = base.tasks + (taskId("A") to base.task("A")!!.copy(title = "Renamed")))
        val remote = base.copy(tasks = base.tasks + (taskId("A") to base.task("A")!!.copy(minimumMinutes = 90)))

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        val task = merged.tasks.getValue(taskId("A"))
        assertEquals("Renamed", task.title)
        assertEquals(90, task.minimumMinutes)
    }

    @Test
    fun the_remote_wins_when_both_devices_changed_the_same_field() {
        // The one case an ancestor cannot adjudicate. The remote is what the account already agreed on (and is
        // what the old last-write-wins policy did), so it is the deterministic winner every device converges on.
        val base = tree("0" to "A")
        val local = base.copy(tasks = base.tasks + (taskId("A") to base.task("A")!!.copy(title = "Mine")))
        val remote = base.copy(tasks = base.tasks + (taskId("A") to base.task("A")!!.copy(title = "Theirs")))

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals("Theirs", merged.tasks.getValue(taskId("A")).title)
    }

    @Test
    fun a_deletion_the_other_device_did_not_touch_is_honoured_in_both_directions() {
        val base = tree("0" to "A", "1" to "B")

        val remoteDeleted = SnapshotMerge.mergeStates(base, base.withRow("2", "C"), base.withoutRow("1", "B"))
        assertEquals(listOf("A", "C"), remoteDeleted.titles(), "the peer's deletion must stick")

        val localDeleted = SnapshotMerge.mergeStates(base, base.withoutRow("1", "B"), base.withRow("2", "C"))
        assertEquals(listOf("A", "C"), localDeleted.titles(), "and so must this device's")
    }

    @Test
    fun a_row_deleted_on_one_device_leaves_no_dangling_reference_behind() {
        // Deleting a row that the other device renamed is the case where a naive per-entry merge breaks the
        // tree: the task survives (an edit outranks a deletion) while the cell is gone from the list. The
        // repair pass must leave a consistent tree — no list naming a missing cell, no cell in no list.
        val base = tree("0" to "A", "1" to "B")
        val local = base.copy(tasks = base.tasks + (taskId("B") to base.task("B")!!.copy(title = "B renamed")))
        val remote = base.withoutRow("1", "B")

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals(listOf("A"), merged.titles())
        val listed = merged.lists.values.flatMap { it.cellIds }
        assertTrue(listed.all { it in merged.cells }, "every listed cell must exist")
        assertTrue(merged.cells.keys.all { it in listed }, "every cell must be listed somewhere")
        assertTrue(
            merged.cells.values.all { it.taskId == null || it.taskId in merged.tasks },
            "no cell may point at a purged task",
        )
        assertTrue(
            merged.tasks.values.all { t -> t.occurrences.all { it in merged.cells } },
            "no task may name a deleted occurrence",
        )
    }

    @Test
    fun completed_work_recorded_on_each_device_is_unioned_rather_than_replaced() {
        // Records are banked facts, not a value one side owns: each device banks the periods its own now-line
        // crossed, so taking one side's list would erase real completed work.
        val base = tree("0" to "A")
        val early = TaskTimeRange(1_000, 2_000)
        val fromLocal = TaskTimeRange(3_000, 4_000)
        val fromRemote = TaskTimeRange(5_000, 6_000)
        val seeded = base.copy(tasks = base.tasks + (taskId("A") to base.task("A")!!.copy(record = listOf(early))))
        val local =
            seeded.copy(tasks = seeded.tasks + (taskId("A") to seeded.task("A")!!.copy(record = listOf(early, fromLocal))))
        val remote =
            seeded.copy(tasks = seeded.tasks + (taskId("A") to seeded.task("A")!!.copy(record = listOf(early, fromRemote))))

        val merged = SnapshotMerge.mergeStates(seeded, local, remote)

        assertEquals(listOf(early, fromLocal, fromRemote), merged.tasks.getValue(taskId("A")).record)
    }

    @Test
    fun a_record_period_removed_by_the_user_stays_removed_even_as_the_peer_banks_more() {
        // The union must still respect a deliberate removal (the RemoveRecordPeriod intent) — that is exactly
        // what the ancestor is for.
        val base = tree("0" to "A")
        val removed = TaskTimeRange(1_000, 2_000)
        val banked = TaskTimeRange(5_000, 6_000)
        val seeded = base.copy(tasks = base.tasks + (taskId("A") to base.task("A")!!.copy(record = listOf(removed))))
        val local = seeded.copy(tasks = seeded.tasks + (taskId("A") to seeded.task("A")!!.copy(record = emptyList())))
        val remote =
            seeded.copy(tasks = seeded.tasks + (taskId("A") to seeded.task("A")!!.copy(record = listOf(removed, banked))))

        val merged = SnapshotMerge.mergeStates(seeded, local, remote)

        assertEquals(listOf(banked), merged.tasks.getValue(taskId("A")).record)
    }

    @Test
    fun reminders_and_alarms_added_on_either_device_all_survive_and_deletions_still_apply() {
        val base =
            tree("0" to "A").copy(
                chores = listOf(ChoreEntry(title = "Water plants", spanDays = 3.0, id = "reminder-1")),
                alarms = listOf(AlarmEntry(id = "alarm-1", label = "Wake", timeOfDayMinutes = 450)),
            )
        val local =
            base.copy(
                chores = base.chores + ChoreEntry(title = "Call the dentist", spanDays = 30.0, id = "reminder-2"),
                // Alarm removed here; the peer never touched it, so the removal must stick.
                alarms = emptyList(),
            )
        val remote =
            base.copy(
                chores = base.chores + ChoreEntry(title = "Backups", spanDays = 7.0, id = "reminder-3"),
                alarms = base.alarms + AlarmEntry(id = "alarm-2", label = "Standup", timeOfDayMinutes = 540),
            )

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals(listOf("reminder-1", "reminder-2", "reminder-3"), merged.chores.map { it.id })
        assertEquals(listOf("alarm-2"), merged.alarms.map { it.id })
    }

    @Test
    fun user_authored_panels_added_on_both_devices_all_survive() {
        val base = tree("0" to "A").copy(panels = listOf(panel("panel/1", "Existing", 1_000)))
        val local = base.copy(panels = base.panels + panel("panel/2", "Mine", 2_000))
        val remote = base.copy(panels = base.panels + panel("panel/3", "Theirs", 3_000))

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals(listOf("panel/1", "panel/2", "panel/3"), merged.panels.map { it.id })
    }

    @Test
    fun a_row_inserted_in_the_middle_stays_where_the_user_put_it() {
        // Why merged order follows each side's own neighbour rather than simply appending the newcomers: a row
        // the user slotted between two others must not jump to the end of the list just because the peer's
        // snapshot is the one that reached the server first.
        val base = tree("0" to "A", "1" to "C")
        val list = base.lists.getValue(WellKnownIds.MAIN_LIST)
        val local =
            base.withRow("2", "B").let {
                it.copy(
                    lists =
                        it.lists +
                            (WellKnownIds.MAIN_LIST to list.copy(cellIds = listOf(cellId("0"), cellId("2"), cellId("1")))),
                )
            }
        val remote = base.withRow("3", "D")

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals(listOf("A", "B", "C", "D"), merged.titles())
    }

    @Test
    fun a_panel_edited_on_one_device_survives_its_deletion_on_the_other() {
        // Edit outranks deletion here as it does in the tree — and the ordering pass, which sees only ids and
        // cannot tell an edited entry from an untouched one, must not quietly drop what the merge kept.
        val base = tree("0" to "A").copy(panels = listOf(panel("panel/1", "Block", 1_000), panel("panel/2", "Other", 8_000)))
        val local = base.copy(panels = listOf(panel("panel/1", "Block moved", 5_000), panel("panel/2", "Other", 8_000)))
        val remote = base.copy(panels = listOf(panel("panel/2", "Other", 8_000)))

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals(listOf("panel/2", "panel/1"), merged.panels.map { it.id })
        assertEquals(5_000, merged.panels.single { it.id == "panel/1" }.startEpochMillis)
    }

    @Test
    fun a_moved_panel_keeps_its_start_and_end_together() {
        // A panel's start and end are one gesture, so they are resolved as one value: a field-wise merge could
        // take the start from one device and the end from the other and invent an inverted block.
        val base = tree("0" to "A").copy(panels = listOf(panel("panel/1", "Block", 1_000)))
        val local = base.copy(panels = listOf(panel("panel/1", "Block", 5_000)))
        val remote = base.copy(panels = listOf(panel("panel/1", "Block", 9_000)))

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        val moved = merged.panels.single()
        assertEquals(9_000, moved.startEpochMillis)
        assertEquals(10_000, moved.endEpochMillis)
    }

    @Test
    fun id_counters_clear_both_devices_allocations() {
        // Ids are never reused. If the merged counter trailed either side, the next id this device hands out
        // would collide with one the peer already used.
        val base = tree("0" to "A")
        val local = base.copy(nextTaskCounter = 7, nextCellCounter = 4, nextPanelCounter = 2)
        val remote = base.copy(nextTaskCounter = 3, nextCellCounter = 9, nextPanelCounter = 11)

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals(7, merged.nextTaskCounter)
        assertEquals(9, merged.nextCellCounter)
        assertEquals(11, merged.nextPanelCounter)
    }

    @Test
    fun settings_changed_on_only_one_device_are_adopted_from_that_device() {
        val base = tree("0" to "A").copy(sleep = SleepSchedule(wakeMinutes = 450), automaticSchedule = true)
        val local = base.copy(automaticSchedule = false)
        val remote = base.copy(sleep = SleepSchedule(wakeMinutes = 400))

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals(false, merged.automaticSchedule, "only this device touched the switch")
        assertEquals(400, merged.sleep!!.wakeMinutes, "only the peer touched the sleep schedule")
    }

    @Test
    fun this_devices_undo_history_is_kept_rather_than_interleaved_with_the_peers() {
        // A history unit carries whole before/after tree snapshots, so interleaving two devices' stacks would
        // build a timeline whose Ctrl+Z restores a tree that never existed and discards the merge. Keeping the
        // local stack is the only choice that leaves Ctrl+Z meaning what this user just did.
        val base = tree("0" to "A")
        val mine = HistoryUnit(timeMillis = 200, delta = NoOpDelta)
        val theirs = HistoryUnit(timeMillis = 300, delta = NoOpDelta)
        val local =
            base.copy(histories = SchedulerHistories().withCategory(HistoryCategory.Main, SchedulerHistory(0, listOf(mine))))
        val remote =
            base.copy(histories = SchedulerHistories().withCategory(HistoryCategory.Main, SchedulerHistory(0, listOf(theirs))))

        val merged = SnapshotMerge.mergeStates(base, local, remote)

        assertEquals(listOf(mine), merged.histories.forCategory(HistoryCategory.Main).units)
    }

    @Test
    fun merging_snapshots_round_trips_through_the_codec() {
        // The engine merges serialized snapshots, not states: the encode/decode round trip must preserve the
        // merge (and an undecodable side must report failure rather than guess).
        val base = tree("0" to "A")
        val local = base.withRow("1", "Desktop")
        val remote = base.withRow("2", "Phone")

        val merged =
            SnapshotMerge.merge(
                base = SchedulerStateCodec.encodeSnapshot(base),
                local = SchedulerStateCodec.encodeSnapshot(local),
                remote = SchedulerStateCodec.encodeSnapshot(remote),
            )

        assertNotNull(merged)
        assertEquals(listOf("A", "Desktop", "Phone"), SchedulerStateCodec.decodeSnapshot(merged)!!.titles())
        assertNull(
            SnapshotMerge.merge(
                base = SchedulerStateCodec.encodeSnapshot(base).copy(statePayload = "not json"),
                local = SchedulerStateCodec.encodeSnapshot(local),
                remote = SchedulerStateCodec.encodeSnapshot(remote),
            ),
            "an undecodable snapshot must fail the merge so the caller can fall back to the plain pull",
        )
    }
}
