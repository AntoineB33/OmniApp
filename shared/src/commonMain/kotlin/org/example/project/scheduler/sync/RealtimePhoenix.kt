package org.example.project.scheduler.sync

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure builders for the Phoenix (Supabase Realtime) message envelopes the client sends. The socket connects
 * with `vsn=1.0.0`, which selects Phoenix's **V1 object** serializer on the server — so messages are
 * `{"topic","event","payload","ref"[,"join_ref"]}` (matching `@supabase/realtime-js`, which `JSON.stringify`s
 * the same object shape; the V2 array form is only for `vsn=2.0.0`). Extracted so the wire shape is
 * unit-testable without a live socket.
 *
 * Since the pause-cue moved to the pg_cron + `device_heartbeat` model (migration 20260723000000), the only
 * live WebSocket left is the [RealtimeSnapshotSubscriber] auto-pull — so these builders are the
 * `postgres_changes` subscription frames plus the shared Phoenix keep-alive. The old presence-track frames were
 * removed with the presence publisher.
 */
internal object RealtimePhoenix {
    /**
     * The Realtime channel topic for a Postgres-changes subscription. The subtopic after `realtime:` is a free
     * channel name (`@supabase/realtime-js` uses an arbitrary name); we make it unique per account so two
     * accounts never share one channel — e.g. `realtime:db:scheduler_snapshot:<userId>`.
     */
    fun postgresChangesTopic(subtopic: String): String = "realtime:$subtopic"

    /**
     * `phx_join` that subscribes to Postgres change events on [table] (all events), optionally narrowed by a
     * PostgREST-style [filter] (e.g. `user_id=eq.<uuid>`). The [accessToken] is the signed-in user's JWT, so
     * Supabase authorizes the subscription through the table's Row-Level Security — a peer only receives rows
     * its own policy admits. `ref == join_ref`.
     */
    fun postgresChangesJoinFrame(
        topic: String,
        accessToken: String,
        schema: String,
        table: String,
        filter: String?,
        ref: Long,
    ): String =
        envelope(topic, "phx_join", ref, ref) {
            putJsonObject("config") {
                putJsonArray("postgres_changes") {
                    addJsonObject {
                        put("event", "*")
                        put("schema", schema)
                        put("table", table)
                        if (filter != null) put("filter", filter)
                    }
                }
                put("private", false)
            }
            put("access_token", accessToken)
        }

    /**
     * True for an actual Postgres-change broadcast (`"event":"postgres_changes"`) — NOT the `phx_reply` that
     * echoes the subscription config on join (that carries `"event":"phx_reply"` and merely mentions the word).
     * We only use it as a signal to re-`reconcile()`, so the record body itself never needs parsing.
     */
    fun isPostgresChange(text: String): Boolean = text.contains("\"event\":\"postgres_changes\"")

    /**
     * True for the `system` frame confirming the postgres_changes subscription is LIVE and streaming
     * (`"message":"Subscribed to PostgreSQL","status":"ok"`), as opposed to the `phx_reply` that merely
     * acknowledges the channel join. This is the moment row changes start flowing — and therefore the moment
     * the client must reconcile, because Realtime streams only while connected and **never replays what was
     * missed** while it was not (see [RealtimeSnapshotSubscriber]).
     */
    fun isPostgresSubscriptionReady(text: String): Boolean =
        text.contains("\"event\":\"system\"") && text.contains("\"extension\":\"postgres_changes\"") &&
            text.contains("\"status\":\"ok\"")

    /**
     * True for a `system` frame reporting the postgres_changes subscription was REJECTED — the server accepted
     * the channel join but refuses to stream row changes (most commonly the table is not in the
     * `supabase_realtime` publication, i.e. the enabling migration was never applied). Deliberately distinct
     * from the success `system` frame (`"message":"Subscribed to PostgreSQL","status":"ok"`): only a
     * `"status":"error"` on the `postgres_changes` extension matches. Not an auth failure, so the JWT-refresh
     * path must NOT treat it as one.
     */
    fun isPostgresSubscriptionError(text: String): Boolean =
        text.contains("\"event\":\"system\"") && text.contains("\"extension\":\"postgres_changes\"") &&
            text.contains("\"status\":\"error\"")

    /** Phoenix keep-alive on the reserved `phoenix` topic (no join_ref). */
    fun heartbeatFrame(ref: Long): String = envelope("phoenix", "heartbeat", ref, null) {}

    // Supabase (vsn=1.0.0 / Phoenix V1) wire shape: {topic, event, payload, ref[, join_ref]}. refs are strings;
    // the heartbeat (a non-channel message) omits join_ref.
    private fun envelope(
        topic: String,
        event: String,
        ref: Long,
        joinRef: Long?,
        payload: JsonObjectBuilderScope,
    ): String =
        buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", buildJsonObject(payload))
            put("ref", ref.toString())
            if (joinRef != null) put("join_ref", joinRef.toString())
        }.toString()
}

// Alias for the trailing-lambda payload builder passed to [RealtimePhoenix.envelope].
private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
