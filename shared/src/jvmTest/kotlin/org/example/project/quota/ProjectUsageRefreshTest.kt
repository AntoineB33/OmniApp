package org.example.project.quota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.platform.DeviceKind

/**
 * User rule 2026-11-03: the Settings page's refresh button reads the project's usage from the server — once per press,
 * through the app's real client and engine (`FakeSupabase` answers `project_usage` from what it holds).
 */
class ProjectUsageRefreshTest {
    private val t0 = 1_788_343_200_000L

    @Test
    fun a_press_reads_the_database_and_the_accounts_share_in_one_call() = runTest(timeout = 5.minutes) {
        val server = FakeSupabase({ t0 + testScheduler.currentTime })
        val scenario = QuotaScenario(this, server, QuotaScenario.Millis(t0))
        val desktop = scenario.Device("desktop", "user-usage", QuotaScenario.account(topLevel = 3, children = 2), DeviceKind.Desktop)
        scenario.pass(60_000L)
        val before = server.requestsByCall["POST rpc/project_usage"] ?: 0
        desktop.vm.refreshProjectUsage()
        scenario.pass(5_000L)
        val usage = assertNotNull(desktop.vm.projectUsage.value, "read")
        assertTrue(usage.databaseBytes > 0 && usage.accountBytes > 0, "$usage")
        assertTrue(usage.accountBytes <= usage.databaseBytes)
        assertEquals(before + 1, server.requestsByCall["POST rpc/project_usage"], "one call per press")
        assertEquals(false, desktop.vm.projectUsageFailed.value)
    }

    /**
     * User rule 2026-11-04: *"The refresh button should work even if the app is set offline."* The press is the one
     * request that leaves a device working offline: nothing else does, before it or after it.
     */
    @Test
    fun a_press_reads_the_usage_while_the_device_works_offline_and_nothing_else_is_sent() = runTest(timeout = 5.minutes) {
        val server = FakeSupabase({ t0 + testScheduler.currentTime })
        val scenario = QuotaScenario(this, server, QuotaScenario.Millis(t0))
        val desktop = scenario.Device("desktop", "user-usage-offline", QuotaScenario.account(topLevel = 3, children = 2), DeviceKind.Desktop)
        scenario.pass(60_000L)
        desktop.vm.setOffline(true)
        scenario.pass(60_000L)
        val requests = server.requests
        val calls = server.requestsByCall["POST rpc/project_usage"] ?: 0

        desktop.vm.refreshProjectUsage()
        scenario.pass(5_000L)
        val usage = assertNotNull(desktop.vm.projectUsage.value, "read while offline")
        assertTrue(usage.databaseBytes > 0, "$usage")
        assertEquals(false, desktop.vm.projectUsageFailed.value)
        assertEquals(calls + 1, server.requestsByCall["POST rpc/project_usage"], "one call per press")

        // Still offline: an edit is saved and marked, and no request follows it.
        desktop.dispatch(org.example.project.scheduler.state.SchedulerIntent.SetTaskMinimumTime(desktop.state.tasks.keys.first { it.value != "task/root" }, 20))
        desktop.vm.syncNow()
        scenario.pass(60_000L)
        assertEquals(requests + 1, server.requests, "the press, and nothing else, left the device")
    }
}
