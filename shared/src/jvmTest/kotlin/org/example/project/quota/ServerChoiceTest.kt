package org.example.project.quota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.domain.ProjectUsageMiss
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * User rule 2026-11-04 (docs/SERVER_SETUP.md): OmniApp is shared with a few technical people, each on a free Supabase
 * project of their own, chosen on the Settings page. The choice takes effect at the next start; until then the device
 * sends nothing — no sync, no guest account, no Refresh — since an account lives on the server it was made on.
 */
class ServerChoiceTest {
    private val t0 = 1_788_343_200_000L

    @Test
    fun a_project_is_an_https_address_and_a_key() {
        assertEquals(SupabaseConfig("https://abcd.supabase.co", "eyJ.k"), SupabaseConfig.typed("  https://abcd.supabase.co/ ", " eyJ.k "))
        assertNull(SupabaseConfig.typed("http://abcd.supabase.co", "eyJ.k"), "not https")
        assertNull(SupabaseConfig.typed("https://", "eyJ.k"), "no address")
        assertNull(SupabaseConfig.typed("https://abcd.supabase.co", "  "), "no key")
        assertNull(SupabaseConfig.typed("https://ab cd.supabase.co", "eyJ.k"), "a space in the address")
    }

    @Test
    fun a_server_chosen_takes_effect_at_the_next_start_and_nothing_is_sent_until_then() = runTest(timeout = 5.minutes) {
        val server = FakeSupabase({ t0 + testScheduler.currentTime })
        val scenario = QuotaScenario(this, server, QuotaScenario.Millis(t0))
        val desktop = scenario.Device("desktop", "user-server", QuotaScenario.account(topLevel = 3, children = 2), DeviceKind.Desktop)
        scenario.pass(60_000L)
        assertNull(desktop.vm.serverChoice.value, "test premise: the built-in server")

        val mine = SupabaseConfig("https://abcd.supabase.co", "eyJ.public")
        desktop.vm.chooseServer(mine)
        assertEquals(mine, desktop.vm.serverChoice.value, "kept for the next start")
        assertEquals(true, desktop.vm.serverChangePending?.value)
        assertTrue(!desktop.sync.isSignedIn, "the old server's account is this launch's no more")
        val requests = server.requests

        // An edit, a sync asked for, a Refresh: nothing reaches the old server.
        desktop.dispatch(org.example.project.scheduler.state.SchedulerIntent.SetTaskMinimumTime(desktop.state.tasks.keys.first { it.value != "task/root" }, 20))
        desktop.vm.syncNow()
        desktop.vm.refreshProjectUsage()
        scenario.pass(120_000L)
        assertEquals(requests, server.requests, "nothing sent")
        assertEquals(ProjectUsageMiss.ServerChanged, desktop.vm.projectUsageMiss.value)
        // Going online again does not reopen it either.
        desktop.vm.setOffline(false)
        desktop.vm.syncNow()
        scenario.pass(60_000L)
        assertEquals(requests, server.requests, "still nothing")
    }
}
