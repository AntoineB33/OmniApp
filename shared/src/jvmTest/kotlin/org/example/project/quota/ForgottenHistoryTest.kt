package org.example.project.quota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.sync.EntityRows
import org.example.project.scheduler.sync.ForgottenRows
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-03: *"When a db is reaching its defined memory limit (lower for the free Supabase server), then the
 * oldest rows are deleted"*, and *"instead of deleting them, they are replaced by a single row saying that they are not
 * saved in this db anymore."* Through the app's real engine against `FakeSupabase`'s `forget_old_history`.
 */
class ForgottenHistoryTest {
    private val t0 = 1_788_343_200_000L
    private val old = TaskId("task/user/old")

    // 2026-11-04: a renamed parent — no cell holds it, it keeps its sub-tree so that naming it again brings it back — and
    // that sub-tree: its sub-list, the cell in it, the child held there. No row of the tree reaches any of it.
    private val oldParent = TaskId("task/user/oldparent")
    private val oldList = CellListId("task/user/oldparent/children")
    private val oldCell = CellId("cell/task/user/oldparent/children/0")
    private val oldChild = TaskId("task/user/oldchild")

    /** The account, with a task no cell holds that keeps a past on the calendar, and a renamed parent's sub-tree. */
    private fun account(): SchedulerState {
        val s = QuotaScenario.account(topLevel = 3, children = 2)
        return s.copy(
            tasks =
                s.tasks +
                    (old to Task(id = old, title = "Old work", record = listOf(TaskTimeRange(t0 - 40L * 86_400_000L, t0 - 40L * 86_400_000L + 3_600_000L)))) +
                    (oldParent to Task(id = oldParent, title = "Old parent", childTaskIds = listOf(oldChild), childListId = oldList)) +
                    (oldChild to Task(id = oldChild, title = "Old child", occurrences = listOf(oldCell))),
            lists = s.lists + (oldList to CellList(id = oldList, parentCellId = null, cellIds = listOf(oldCell))),
            cells = s.cells + (oldCell to Cell(id = oldCell, parentListId = oldList, taskId = oldChild)),
        )
    }

    private fun serverHas(server: FakeSupabase, entityId: String, kind: String = "tasks"): Boolean =
        server.table("scheduler_entity").rows.values.any {
            it["kind"]?.toString()?.contains(kind) == true && it["entity_id"]?.toString() == "\"$entityId\"" && it["deleted"]?.toString() != "true"
        }

    @Test
    fun the_sub_tree_of_a_task_no_cell_holds_is_history_and_the_tree_is_not() {
        val rows = EntityRows.split(org.example.project.scheduler.persistence.SchedulerStateCodec.encodeSnapshot(account()).statePayload)
        val covered = ForgottenRows.coveredKeys(rows, ForgottenRows.Marker(untilMillis = t0))
        assertEquals(
            setOf(
                EntityRows.Key("tasks", old.value),
                EntityRows.Key("tasks", oldParent.value),
                EntityRows.Key("lists", oldList.value),
                EntityRows.Key("cells", oldCell.value),
                EntityRows.Key("tasks", oldChild.value),
            ),
            covered,
            "the tasks no cell holds and the renamed parent's whole sub-tree — nothing the tree shows",
        )
        assertTrue(ForgottenRows.coveredKeys(rows, null).isEmpty(), "nothing is history before the server forgot any")
    }

    @Test
    fun a_device_keeps_what_the_server_forgot_and_does_not_send_it_back() = runTest(timeout = 10.minutes) {
        val server = FakeSupabase({ t0 + testScheduler.currentTime })
        val scenario = QuotaScenario(this, server, QuotaScenario.Millis(t0))
        val desktop = scenario.Device("desktop", "user-forget", account(), DeviceKind.Desktop)
        scenario.pass(60_000L)
        assertTrue(serverHas(server, old.value), "test premise: the old task reached the server")
        assertTrue(serverHas(server, oldList.value, "lists") && serverHas(server, oldChild.value), "test premise: and the renamed parent's sub-tree")

        val forgotten = server.forgetOldHistory(limitBytes = 1L, untilMillis = t0)
        assertTrue(forgotten > 0)
        assertFalse(serverHas(server, old.value), "the server forgot it")
        assertFalse(serverHas(server, oldParent.value) || serverHas(server, oldList.value, "lists") || serverHas(server, oldChild.value), "and the sub-tree")
        assertTrue(serverHas(server, QuotaScenario.account(topLevel = 3, children = 2).tasks.keys.first { it.value != "task/root" }.value), "but not the tree")

        // The summary row reaches the desktop as any row does (it has pulled, so its cursor is a real one): the forgotten
        // task stays, since no deletion of it was ever written.
        desktop.vm.syncNow()
        scenario.pass(30_000L)
        assertTrue(old in desktop.state.tasks)
        // Away a week, offline (so no reconcile keeps its cursor fresh): back online, it no longer trusts its cursor and
        // reads the account's live rows in full (past SchedulerSyncEngine.FULL_PULL_AFTER_MILLIS, six days).
        desktop.vm.setOffline(true)
        scenario.pass(6L * 24 * 3_600_000L + 3_600_000L)
        desktop.vm.setOffline(false)
        scenario.pass(30_000L)
        desktop.vm.syncNow()
        scenario.pass(30_000L)

        assertTrue(old in desktop.state.tasks, "the device keeps what the server forgot")
        assertFalse(serverHas(server, old.value), "and does not send it back")
        assertTrue(oldParent in desktop.state.tasks && oldChild in desktop.state.tasks, "the renamed parent's sub-tree too")
        assertEquals(listOf(oldCell), desktop.state.lists[oldList]?.cellIds, "its sub-list whole")
        assertEquals(oldChild, desktop.state.cells[oldCell]?.taskId)
        assertEquals(oldList, desktop.state.tasks[oldParent]?.childListId, "and still the parent's: naming it again brings it back")
        assertFalse(serverHas(server, oldList.value, "lists") || serverHas(server, oldCell.value, "cells") || serverHas(server, oldChild.value))
        val marker = assertNotNull(desktop.sync.forgotten.value, "it has the row standing for what was forgotten")
        assertEquals(t0, marker.untilMillis)

        // A device that never held it gets only the row that says it is not saved there any more.
        val phone = scenario.Device("phone", "user-forget", SchedulerState.empty(), DeviceKind.Phone)
        scenario.pass(60_000L)
        assertFalse(old in phone.state.tasks)
        assertNotNull(phone.sync.forgotten.value)
    }
}
