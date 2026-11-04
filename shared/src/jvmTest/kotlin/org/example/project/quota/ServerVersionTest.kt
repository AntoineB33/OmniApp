package org.example.project.quota

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.domain.ServerVersion
import org.example.project.scheduler.platform.DeviceKind

/**
 * User rule 2026-11-04 (docs/SERVER_SETUP.md): each user runs their own Supabase project, and an update that brings a
 * migration needs `deploy-supabase.bat` run against it. The app asks the server for the latest migration it applied
 * (`server_version`) and says so on the Settings page when the server is behind.
 */
class ServerVersionTest {
    private val t0 = 1_788_343_200_000L

    @Test
    fun the_version_the_app_needs_is_the_newest_migration() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "supabase/migrations").isDirectory }
        val newest = File(root, "supabase/migrations").listFiles().orEmpty().map { it.name.substringBefore('_') }.max()
        assertEquals(newest, ServerVersion.REQUIRED_SERVER_MIGRATION, "a new migration moves what the app needs")
        assertEquals(ServerVersion.UpToDate, ServerVersion.of(newest))
        assertEquals(ServerVersion.UpToDate, ServerVersion.of("29990101000000"), "later is fine")
        assertEquals(ServerVersion.Behind("20261103000000"), ServerVersion.of("20261103000000"))
        assertEquals(ServerVersion.Behind(""), ServerVersion.of(""))
    }

    private fun versionAfterLaunch(server: FakeSupabase) =
        runTest(timeout = 5.minutes) {
            val scenario = QuotaScenario(this, server, QuotaScenario.Millis(t0))
            val desktop = scenario.Device("desktop", "user-version", QuotaScenario.account(topLevel = 3, children = 2), DeviceKind.Desktop)
            scenario.pass(60_000L)
            result = desktop.sync.serverVersion.value
        }

    private var result: ServerVersion? = null

    @Test
    fun a_launch_asks_the_server_once_and_reads_what_it_has() {
        val deployed = FakeSupabase({ t0 })
        versionAfterLaunch(deployed)
        assertEquals(ServerVersion.UpToDate, result)
        assertEquals(1L, deployed.requestsByCall["POST rpc/server_version"], "once per start, never on a timer")

        versionAfterLaunch(FakeSupabase({ t0 }).also { it.serverVersion = "20261103010000" })
        assertEquals(ServerVersion.Behind("20261103010000"), result, "an update not deployed")

        versionAfterLaunch(FakeSupabase({ t0 }).also { it.undeployed += "server_version" })
        assertEquals(ServerVersion.Behind(""), result, "a server from before the function: behind too")
    }
}
