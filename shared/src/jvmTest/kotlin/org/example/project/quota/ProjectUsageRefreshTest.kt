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
}
