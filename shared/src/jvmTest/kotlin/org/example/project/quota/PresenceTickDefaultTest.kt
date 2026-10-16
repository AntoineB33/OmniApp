package org.example.project.quota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.example.project.scheduler.sync.DeviceHeartbeatPublisher

/**
 * `t_a` has two defaults: the client's ([DeviceHeartbeatPublisher.DEFAULT_TICK_SECONDS], used until the first beat's
 * reply) and the server's (`tick_seconds_for`'s fallback and the `app_config` column default, in the migrations).
 * `ServerQuotaTest` paces the beat by the client's, so a server left at another value would beat at a rate the quota
 * gate never measured. The last definition in the migrations is the deployed one.
 */
class PresenceTickDefaultTest {
    @Test
    fun the_server_and_the_client_default_t_a_to_the_same_value() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "supabase/migrations").isDirectory }
        assertNotNull(root, "supabase/migrations not found")
        val migrations = java.io.File(root, "supabase/migrations").listFiles().orEmpty().sortedBy { it.name }
            .joinToString("\n") { it.readText() }
        val fallback = Regex("""from public\.app_config c where c\.user_id = p_user\), (\d+)\)""")
            .findAll(migrations).lastOrNull()?.groupValues?.get(1)?.toInt()
        val column = Regex("""tick_seconds (?:integer not null )?(?:set )?default (\d+)""")
            .findAll(migrations).lastOrNull()?.groupValues?.get(1)?.toInt()
        assertEquals(DeviceHeartbeatPublisher.DEFAULT_TICK_SECONDS, fallback, "tick_seconds_for's fallback")
        assertEquals(DeviceHeartbeatPublisher.DEFAULT_TICK_SECONDS, column, "app_config.tick_seconds's default")
    }
}
