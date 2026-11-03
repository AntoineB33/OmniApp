package org.example.project.quota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * Two devices of one account syncing through the real engine (`FakeSupabase`), 2026-11-03:
 *  - **an edit session is local until it ends**: a device typing a title while the other one pushes keeps typing, and no
 *    half-typed letter's task lands in any cell of either device (`sync-and-accounts.md`);
 *  - **an id is unique across the devices**: two tasks made at once on two devices stay two tasks.
 */
class TwoDevicesEditTest {
    private val t0 = 1_788_343_200_000L

    private class Pair2(val scenario: QuotaScenario, val desktop: QuotaScenario.Device, val phone: QuotaScenario.Device)

    private fun TestScope.devices(): Pair2 {
        val server = FakeSupabase({ t0 + testScheduler.currentTime })
        val scenario = QuotaScenario(this, server, QuotaScenario.Millis(t0))
        val desktop = scenario.Device("desktop", "user-two", QuotaScenario.account(topLevel = 4, children = 2), DeviceKind.Desktop)
        scenario.pass(60_000L)
        val phone = scenario.Device("phone", "user-two", SchedulerState.empty(), DeviceKind.Phone)
        scenario.pass(60_000L)
        return Pair2(scenario, desktop, phone)
    }

    /** [intent] reduced on [device], under its own id: the reducer's id is one global, the devices share a JVM here. */
    private fun on(device: QuotaScenario.Device, intent: SchedulerIntent) {
        SchedulerReducer.deviceId = { device.id }
        device.dispatch(intent)
    }

    private fun held(state: SchedulerState): List<String> =
        state.cells.values.mapNotNull { it.taskId }.mapNotNull { state.tasks[it]?.title }

    @Test
    fun typing_survives_the_other_device_pushing_and_leaves_no_letter_behind() = runTest(timeout = 5.minutes) {
        val d = devices()
        try {
            val cell = d.desktop.state.cells.values.first { it.taskId != null && d.desktop.state.tasks[it.taskId]?.title?.startsWith("Area") == true }.id
            on(d.desktop, SchedulerIntent.BeginEdit(cell))
            val title = "Fresh name"
            for (i in 1..title.length) {
                on(d.desktop, SchedulerIntent.UpdateEditText(title.take(i)))
                if (i == 3) {
                    // The phone edits and pushes; the desktop is poked mid-session.
                    val any = d.phone.state.tasks.values.first { it.title.startsWith("Area") }.id
                    on(d.phone, SchedulerIntent.SetTaskMinimumTime(any, 37))
                }
                d.scenario.pass(2_000L)
            }
            assertEquals(title, d.desktop.state.editSession?.draftText, "the session went on through the other device's push")
            on(d.desktop, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))
            d.scenario.pass(30_000L)

            for (device in listOf(d.desktop, d.phone)) {
                val s = device.state
                assertNull(s.editSession)
                val letters = (1 until title.length).map { title.take(it) }
                assertTrue(held(s).none { it in letters }, "${device.id}: a half-typed title in a cell: ${held(s).filter { it in letters }}")
                assertTrue(title in held(s), "${device.id} has the title")
                assertTrue(s.tasks.values.any { it.minimumMinutes == 37 }, "${device.id}: the phone's edit, owed during the session, went through")
            }
        } finally {
            SchedulerReducer.deviceId = { SchedulerReducer.LOCAL_DEVICE_ID }
        }
    }

    @Test
    fun two_tasks_made_at_once_on_two_devices_stay_two() = runTest(timeout = 5.minutes) {
        val d = devices()
        try {
            fun placeholder(s: SchedulerState, n: Int): CellId =
                s.tasks.values.filter { it.title.startsWith("Area") }.sortedBy { it.title }[n].childListId!!.let { s.lists[it]!!.cellIds.last() }
            on(d.desktop, SchedulerIntent.SetCellTitle(placeholder(d.desktop.state, 0), "Made on the desktop"))
            on(d.phone, SchedulerIntent.SetCellTitle(placeholder(d.phone.state, 1), "Made on the phone"))
            d.scenario.pass(30_000L)
            for (device in listOf(d.desktop, d.phone)) {
                val s = device.state
                val desk = s.tasks.values.filter { it.title == "Made on the desktop" }
                val phone = s.tasks.values.filter { it.title == "Made on the phone" }
                assertEquals(1, desk.size, "${device.id}: ${desk.map { it.id }}")
                assertEquals(1, phone.size, "${device.id}: ${phone.map { it.id }}")
                assertNotEquals(desk.single().id, phone.single().id)
                assertEquals(1, s.cells.values.count { it.taskId == desk.single().id })
                assertEquals(1, s.cells.values.count { it.taskId == phone.single().id })
            }
        } finally {
            SchedulerReducer.deviceId = { SchedulerReducer.LOCAL_DEVICE_ID }
        }
    }
}
