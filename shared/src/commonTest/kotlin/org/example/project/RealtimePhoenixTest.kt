package org.example.project

import org.example.project.scheduler.sync.PresenceState
import org.example.project.scheduler.sync.RealtimePhoenix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure Phoenix (Supabase Realtime, vsn=1.0.0 → V1 object serializer) message envelopes the presence
 * publisher sends: `{"topic","event","payload","ref"[,"join_ref"]}`. The live WebSocket connection is verified
 * on-device; the wire shape of each frame is pinned here.
 */
class RealtimePhoenixTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun topic_is_the_account_presence_channel() {
        assertEquals("realtime:presence:user-1", RealtimePhoenix.topic("user-1"))
    }

    @Test
    fun join_frame_carries_config_presence_key_and_token() {
        val frame = json.parseToJsonElement(
            RealtimePhoenix.joinFrame(topic = "realtime:presence:u1", accessToken = "jwt-123", presenceKey = "dev-1", ref = 1),
        ).jsonObject

        assertEquals("realtime:presence:u1", frame["topic"]!!.jsonPrimitive.content)
        assertEquals("phx_join", frame["event"]!!.jsonPrimitive.content)
        // ref == join_ref for the join.
        assertEquals("1", frame["ref"]!!.jsonPrimitive.content)
        assertEquals("1", frame["join_ref"]!!.jsonPrimitive.content)
        val payload = frame["payload"]!!.jsonObject
        assertEquals("jwt-123", payload["access_token"]!!.jsonPrimitive.content)
        assertEquals("dev-1", payload["config"]!!.jsonObject["presence"]!!.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun heartbeat_frame_targets_the_phoenix_topic_with_no_join_ref() {
        val frame = json.parseToJsonElement(RealtimePhoenix.heartbeatFrame(ref = 7)).jsonObject
        assertEquals("phoenix", frame["topic"]!!.jsonPrimitive.content)
        assertEquals("heartbeat", frame["event"]!!.jsonPrimitive.content)
        assertEquals("7", frame["ref"]!!.jsonPrimitive.content)
        assertTrue("join_ref" !in frame)
    }

    @Test
    fun track_frame_carries_the_presence_payload() {
        val state = PresenceState(
            deviceId = "dev-1",
            kind = "phone",
            nextBreakEndMillis = 1_800_000_000_000L,
            nextBreakStartMillis = 1_799_999_100_000L,
            nextBreakLenMillis = 900_000L,
            publishedAtMillis = 1_799_999_200_000L,
        )
        val frame = json.parseToJsonElement(
            RealtimePhoenix.trackFrame(topic = "realtime:presence:u1", joinRef = 1, state = state, ref = 3),
        ).jsonObject

        assertEquals("presence", frame["event"]!!.jsonPrimitive.content)
        assertEquals("1", frame["join_ref"]!!.jsonPrimitive.content)
        val payload = frame["payload"]!!.jsonObject
        assertEquals("presence", payload["type"]!!.jsonPrimitive.content)
        assertEquals("track", payload["event"]!!.jsonPrimitive.content)
        val inner = payload["payload"]!!.jsonObject
        assertEquals("dev-1", inner["device_id"]!!.jsonPrimitive.content)
        assertEquals("phone", inner["kind"]!!.jsonPrimitive.content)
        assertEquals(1_800_000_000_000L, inner["next_break_end_ms"]!!.jsonPrimitive.content.toLong())
        // PRD §15 server-side break computation: the pose window rides along.
        assertEquals(1_799_999_100_000L, inner["next_break_start_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(900_000L, inner["next_break_len_ms"]!!.jsonPrimitive.content.toLong())
        // Real-clock liveness heartbeat used by the listener to drop stale ghost presence.
        assertEquals(1_799_999_200_000L, inner["published_at_ms"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun track_frame_encodes_a_null_break_end() {
        val state = PresenceState(deviceId = "dev-1", kind = "desktop", nextBreakEndMillis = null)
        val frame = json.parseToJsonElement(
            RealtimePhoenix.trackFrame(topic = "realtime:presence:u1", joinRef = 1, state = state, ref = 4),
        ).jsonObject
        val inner = frame["payload"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals("null", inner["next_break_end_ms"].toString())
        assertEquals("null", inner["next_break_start_ms"].toString())
        assertEquals("null", inner["next_break_len_ms"].toString())
    }

    @Test
    fun untrack_frame_removes_presence() {
        val frame = json.parseToJsonElement(
            RealtimePhoenix.untrackFrame(topic = "realtime:presence:u1", joinRef = 1, ref = 5),
        ).jsonObject
        assertEquals("presence", frame["event"]!!.jsonPrimitive.content)
        assertEquals("untrack", frame["payload"]!!.jsonObject["event"]!!.jsonPrimitive.content)
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
