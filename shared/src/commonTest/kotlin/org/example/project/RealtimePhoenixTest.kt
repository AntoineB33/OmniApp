package org.example.project

import org.example.project.scheduler.sync.RealtimePhoenix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure Phoenix (Supabase Realtime, vsn=1.0.0 → V1 object serializer) message envelopes the client sends:
 * `{"topic","event","payload","ref"[,"join_ref"]}`. Since the pause-cue moved to the pg_cron +
 * `device_heartbeat` model, the only live WebSocket left is the snapshot auto-pull, so these are the
 * `postgres_changes` subscription frames plus the shared Phoenix keep-alive. The live connection is verified
 * on-device; the wire shape of each frame is pinned here.
 */
class RealtimePhoenixTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun heartbeat_frame_targets_the_phoenix_topic_with_no_join_ref() {
        val frame = json.parseToJsonElement(RealtimePhoenix.heartbeatFrame(ref = 7)).jsonObject
        assertEquals("phoenix", frame["topic"]!!.jsonPrimitive.content)
        assertEquals("heartbeat", frame["event"]!!.jsonPrimitive.content)
        assertEquals("7", frame["ref"]!!.jsonPrimitive.content)
        assertTrue("join_ref" !in frame)
    }

    // ---- Bidirectional sync (PRD §5): the postgres_changes auto-pull subscription frames ----

    @Test
    fun postgres_changes_topic_is_a_per_account_channel() {
        assertEquals(
            "realtime:db:scheduler_snapshot:user-1",
            RealtimePhoenix.postgresChangesTopic("db:scheduler_snapshot:user-1"),
        )
    }

    @Test
    fun postgres_changes_join_frame_carries_config_filter_and_token() {
        val frame = json.parseToJsonElement(
            RealtimePhoenix.postgresChangesJoinFrame(
                topic = "realtime:db:scheduler_snapshot:u1",
                accessToken = "jwt-abc",
                schema = "public",
                table = "scheduler_snapshot",
                filter = "user_id=eq.u1",
                ref = 1,
            ),
        ).jsonObject

        assertEquals("realtime:db:scheduler_snapshot:u1", frame["topic"]!!.jsonPrimitive.content)
        assertEquals("phx_join", frame["event"]!!.jsonPrimitive.content)
        assertEquals("1", frame["ref"]!!.jsonPrimitive.content)
        assertEquals("1", frame["join_ref"]!!.jsonPrimitive.content)
        val payload = frame["payload"]!!.jsonObject
        assertEquals("jwt-abc", payload["access_token"]!!.jsonPrimitive.content)
        val change = payload["config"]!!.jsonObject["postgres_changes"]!!.jsonArray.single().jsonObject
        assertEquals("*", change["event"]!!.jsonPrimitive.content)
        assertEquals("public", change["schema"]!!.jsonPrimitive.content)
        assertEquals("scheduler_snapshot", change["table"]!!.jsonPrimitive.content)
        assertEquals("user_id=eq.u1", change["filter"]!!.jsonPrimitive.content)
    }

    @Test
    fun postgres_changes_join_frame_omits_an_absent_filter() {
        val frame = json.parseToJsonElement(
            RealtimePhoenix.postgresChangesJoinFrame(
                topic = "realtime:db:t", accessToken = "j", schema = "public", table = "t", filter = null, ref = 2,
            ),
        ).jsonObject
        val change = frame["payload"]!!.jsonObject["config"]!!.jsonObject["postgres_changes"]!!.jsonArray.single().jsonObject
        assertTrue("filter" !in change)
    }

    @Test
    fun is_postgres_change_matches_a_broadcast_but_not_the_join_reply() {
        // An actual change broadcast.
        assertTrue(
            RealtimePhoenix.isPostgresChange(
                """{"topic":"realtime:db:x","event":"postgres_changes","payload":{"data":{"type":"UPDATE"}}}""",
            ),
        )
        // The phx_reply that echoes the subscription config on join must NOT count as a change.
        assertTrue(
            !RealtimePhoenix.isPostgresChange(
                """{"topic":"realtime:db:x","event":"phx_reply","payload":{"status":"ok","response":{"postgres_changes":[]}}}""",
            ),
        )
    }

    @Test
    fun postgres_subscription_error_is_detected_but_not_the_success_frame() {
        // The real rejection `system` frame Supabase sends when the table is absent from the Realtime
        // publication (migration not applied) — the client must surface it and reconnect, not sit silent.
        assertTrue(
            RealtimePhoenix.isPostgresSubscriptionError(
                """{"ref":null,"event":"system","payload":{"message":"Unable to subscribe to changes with given""" +
                    """ parameters. Please check Realtime is enabled...","status":"error","extension":""" +
                    """"postgres_changes","channel":"db:scheduler_snapshot:u1"},"topic":"realtime:db:scheduler_snapshot:u1"}""",
            ),
        )
        // The success `system` frame carries the same extension but `"status":"ok"` — must NOT match.
        assertTrue(
            !RealtimePhoenix.isPostgresSubscriptionError(
                """{"ref":null,"event":"system","payload":{"message":"Subscribed to PostgreSQL","status":"ok",""" +
                    """"extension":"postgres_changes","channel":"db:scheduler_snapshot:u1"},"topic":"x"}""",
            ),
        )
        // An auth `phx_reply` error is a different failure (handled by the JWT-refresh path) — must NOT match.
        assertTrue(
            !RealtimePhoenix.isPostgresSubscriptionError(
                """{"event":"phx_reply","payload":{"status":"error","response":{"reason":"JWT expired"}},"topic":"x"}""",
            ),
        )
    }
}
