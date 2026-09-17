package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * `docs/invariants/persistence.md` § *One history, per-device undo* (user rule, 2026-09-17): **every device of the
 * account holds the same history, and Ctrl+Z / Ctrl+Y / Alt+arrows act only on the units of the device they are
 * pressed on.**
 */
class HistoryPerDeviceTest {
    private var now = 1_788_343_200_000L

    @AfterTest
    fun reset() {
        SchedulerReducer.deviceId = { SchedulerReducer.LOCAL_DEVICE_ID }
        SchedulerReducer.clock = org.example.project.time.SystemAppClock
    }

    private fun on(device: String, state: SchedulerState, intent: SchedulerIntent): SchedulerState {
        SchedulerReducer.deviceId = { device }
        SchedulerReducer.clock = object : AppClock { override fun nowMillis(): Long = now }
        now += 1_000
        return SchedulerReducer.reduce(state, intent)
    }

    private fun titles(s: SchedulerState): List<String> =
        s.lists[s.rootListId]!!.cellIds.mapNotNull { s.cells[it]?.taskId?.let { t -> s.tasks[t]?.title } }

    @Test
    fun undo_walks_only_this_devices_units_even_past_a_newer_unit_of_another_device() {
        var s = SchedulerState.empty()
        s = on("desktop", s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "A"))
        // The phone's edit arrives (its state change and its unit) after the desktop's.
        val phoneState = on("phone", s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "B"))
        s = phoneState
        assertEquals(listOf("A", "B"), titles(s))
        assertEquals(listOf("desktop", "phone"), s.histories.forCategory(HistoryCategory.Main).units.map { it.deviceId })

        // Ctrl+Z on the desktop takes back the desktop's "A", not the newer "B".
        s = on("desktop", s, SchedulerIntent.Undo)
        assertEquals(listOf("B"), titles(s))
        val main = s.histories.forCategory(HistoryCategory.Main)
        assertEquals(listOf(true, false), main.units.map { it.undone })
        assertEquals(-1, main.pointer, "the desktop has nothing applied left")

        // Ctrl+Z again on the desktop: nothing of its own is left — the phone's unit is untouched.
        val again = on("desktop", s, SchedulerIntent.Undo)
        assertEquals(listOf("B"), titles(again))

        // Ctrl+Y on the desktop brings "A" back.
        s = on("desktop", s, SchedulerIntent.Redo)
        assertEquals(listOf("A", "B"), titles(s))
    }

    @Test
    fun a_new_edit_drops_only_this_devices_redo_branch() {
        // Two tasks another device made; the desktop and the phone each rename one.
        var s = SchedulerState.empty()
        s = on("laptop", s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "X"))
        s = on("laptop", s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "Y"))
        val (x, y) = s.lists[s.rootListId]!!.cellIds.take(2)
        s = on("desktop", s, SchedulerIntent.SetCellTitle(x, "A"))
        s = on("phone", s, SchedulerIntent.SetCellTitle(y, "B"))
        s = on("phone", s, SchedulerIntent.Undo)
        s = on("desktop", s, SchedulerIntent.Undo)
        assertEquals(listOf("X", "Y"), titles(s))
        // The desktop edits again: its own undone "A" is gone for good; the phone's undone "B" stays redoable there.
        s = on("desktop", s, SchedulerIntent.SetCellTitle(x, "C"))
        val units = s.histories.forCategory(HistoryCategory.Main).units.filter { it.deviceId != "laptop" }
        assertEquals(listOf("phone" to true, "desktop" to false), units.map { it.deviceId to it.undone })
        s = on("phone", s, SchedulerIntent.Redo)
        assertEquals(listOf("C", "B"), titles(s))
    }

    @Test
    fun undoing_a_unit_keeps_what_another_device_changed_since_in_the_same_entries() {
        // The desktop creates "A" (which adds a row to the root list); the phone then creates "B" in the list's new row
        // (which adds another). Undoing "A" on the desktop takes "A" back and nothing of "B".
        var s = SchedulerState.empty()
        s = on("desktop", s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "A"))
        s = on("phone", s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "B"))
        s = on("desktop", s, SchedulerIntent.Undo)
        assertEquals(listOf("B"), titles(s))
        // And the phone renaming a task the desktop renamed earlier wins over the desktop's undo.
        s = on("desktop", s, SchedulerIntent.Redo)
        val a = s.lists[s.rootListId]!!.cellIds.first { s.cells[it]!!.taskId?.let { t -> s.tasks[t]!!.title } == "A" }
        s = on("desktop", s, SchedulerIntent.SetCellTitle(a, "A2"))
        s = on("phone", s, SchedulerIntent.SetCellTitle(a, "A3"))
        s = on("desktop", s, SchedulerIntent.Undo)
        assertTrue("A3" in titles(s), "the phone's later rename stands: ${titles(s)}")
    }

    @Test
    fun peer_units_merge_in_time_order_with_their_undone_flag_and_apply_nothing() {
        var desktop = SchedulerState.empty()
        desktop = on("desktop", desktop, SchedulerIntent.SetCellTitle(desktop.lists[desktop.rootListId]!!.cellIds[0], "A"))
        var phone = SchedulerState.empty()
        phone = on("phone", phone, SchedulerIntent.SetCellTitle(phone.lists[phone.rootListId]!!.cellIds[0], "P"))
        phone = on("phone", phone, SchedulerIntent.Undo)

        val peer = phone.histories.forCategory(HistoryCategory.Main).units
        val merged = SchedulerReducer.mergePeerUnits(desktop, HistoryCategory.Main, peer, "desktop")
        assertEquals(listOf("A"), titles(merged), "a peer unit's change comes with the state, never from its unit")
        val units = merged.histories.forCategory(HistoryCategory.Main).units
        assertEquals(listOf("desktop" to false, "phone" to true), units.map { it.deviceId to it.undone })
        assertEquals(0, merged.histories.forCategory(HistoryCategory.Main).pointer)
        // Merging the same units again changes nothing.
        assertTrue(SchedulerReducer.mergePeerUnits(merged, HistoryCategory.Main, peer, "desktop") === merged)
    }

    @Test
    fun a_units_owner_number_and_flag_survive_the_store_and_an_old_unit_is_claimed() {
        var s = SchedulerState.empty()
        s = on("desktop", s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "A"))
        s = on("desktop", s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "B"))
        s = on("desktop", s, SchedulerIntent.Undo)
        val loaded = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s)))
        assertEquals(
            s.histories.forCategory(HistoryCategory.Main).units.map { Triple(it.deviceId, it.deviceSeq, it.undone) },
            loaded.histories.forCategory(HistoryCategory.Main).units.map { Triple(it.deviceId, it.deviceSeq, it.undone) },
        )

        // A unit written before units had an owner: undone exactly when it sat past the pointer, and claimed.
        val unowned = s.copy(
            histories = s.histories.withCategory(
                HistoryCategory.Main,
                s.histories.forCategory(HistoryCategory.Main).let { h -> h.copy(units = h.units.map { it.copy(deviceId = "", deviceSeq = 0, undone = false) }) },
            ),
        )
        val reloaded = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(unowned)))
        assertEquals(listOf(false, true), reloaded.histories.forCategory(HistoryCategory.Main).units.map { it.undone })
        val claimed = SchedulerReducer.claimUnownedUnits(reloaded, "desktop")
        assertTrue(claimed.histories.forCategory(HistoryCategory.Main).units.all { it.deviceId == "desktop" && it.deviceSeq != 0L })
    }
}
