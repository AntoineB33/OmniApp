package org.example.project.scheduler.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A signed-in Supabase session: the bearer token plus enough to refresh it and identify the user. */
@Serializable
data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
)

/** The remote mirror of one user's [org.example.project.scheduler.persistence.PersistedSnapshot]. */
data class RemoteSnapshot(val payload: String, val revision: Long)

/**
 * PRD §15 cross-device presence: one device's heartbeat row. [updatedAt] is the server timestamp (ISO-8601)
 * of the last heartbeat — the freshness the gateway uses to age out a device that stopped reporting.
 */
@Serializable
data class DevicePresence(
    @SerialName("device_id") val deviceId: String,
    val kind: String,
    @SerialName("screen_active") val screenActive: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * PRD §15 device-sleep gaps: one device's exact pause interval row in the `device_sleep_gap` table. Mirrors
 * [org.example.project.scheduler.persistence.SleepGapRecord]; the column names match PostgREST.
 */
@Serializable
data class SleepGapRow(
    @SerialName("device_id") val deviceId: String,
    @SerialName("sleep_start") val sleepStart: Long,
    @SerialName("sleep_end") val sleepEnd: Long,
    @SerialName("recorded_at") val recordedAt: Long,
)

/**
 * PRD §15 device-active sessions: one device's active interval row in the `device_active_session` table.
 * Mirrors [org.example.project.scheduler.persistence.ActiveSessionRecord]; the column names match PostgREST.
 */
@Serializable
data class ActiveSessionRow(
    @SerialName("device_id") val deviceId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

/** PRD §15: one account-wide pause interval returned by the `derive_pauses` RPC. */
@Serializable
data class PauseRow(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
)

/** Raised when Supabase returns a non-success response we did not specifically handle. */
class SupabaseException(val status: Int, message: String) : Exception("Supabase $status: $message")

/**
 * Thin Ktor client over Supabase's GoTrue (`/auth/v1`) and PostgREST (`/rest/v1`) HTTP APIs — the
 * transport half of cross-device sync. It is intentionally stateless about *which* snapshot is current:
 * it exposes auth + four data primitives ([fetch], [insert], [update]) and lets
 * [SchedulerSyncEngine] own the conflict/merge policy.
 *
 * The whole [org.example.project.scheduler.persistence.PersistedSnapshot] is stored as one JSON string in
 * the `payload` column of the `scheduler_snapshot` table, versioned by a monotonically increasing
 * [RemoteSnapshot.revision] that backs optimistic-concurrency on [update].
 */
class RemoteSnapshotClient(
    private val config: SupabaseConfig = SupabaseConfig.DEFAULT,
    // Injectable so tests can supply a MockEngine; defaults to the platform engine on the classpath.
    private val http: HttpClient = defaultHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Auth (GoTrue) ----

    /** Registers a new account. Throws [SupabaseException] on failure (e.g. the email already exists). */
    suspend fun signUp(email: String, password: String): SupabaseSession =
        token("${config.authUrl}/signup", json.encodeToString(EmailPasswordBody(email, password)))

    /** Signs in with email + password. Throws [SupabaseException] on bad credentials. */
    suspend fun signIn(email: String, password: String): SupabaseSession =
        token(
            "${config.authUrl}/token?grant_type=password",
            json.encodeToString(EmailPasswordBody(email, password)),
        )

    /** Exchanges a refresh token for a fresh session. Throws [SupabaseException] if the token is stale. */
    suspend fun refresh(refreshToken: String): SupabaseSession =
        token(
            "${config.authUrl}/token?grant_type=refresh_token",
            json.encodeToString(RefreshBody(refreshToken)),
        )

    private suspend fun token(url: String, body: String): SupabaseSession {
        val response =
            http.post(url) {
                header("apikey", config.anonKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        if (!response.status.isSuccess()) throw response.toException()
        val token = json.decodeFromString<TokenResponse>(response.bodyAsText())
        return SupabaseSession(token.accessToken, token.refreshToken, token.user.id)
    }

    // ---- Data (PostgREST) ----

    /** Returns this user's remote snapshot, or `null` if the row does not exist yet (first device). */
    suspend fun fetch(session: SupabaseSession): RemoteSnapshot? {
        val response =
            http.get("${config.restUrl}/scheduler_snapshot") {
                authHeaders(session)
                url.parameters.append("user_id", "eq.${session.userId}")
                url.parameters.append("select", "payload,revision")
            }
        if (!response.status.isSuccess()) throw response.toException()
        val rows = json.decodeFromString<List<SnapshotRow>>(response.bodyAsText())
        return rows.firstOrNull()?.let { RemoteSnapshot(it.payload, it.revision) }
    }

    /**
     * Inserts the first revision for this user. Returns `false` if a row already exists (another device
     * created it first) — the caller should [fetch] and reconcile instead. Throws on other errors.
     */
    suspend fun insert(session: SupabaseSession, payload: String): Boolean {
        val response =
            http.post("${config.restUrl}/scheduler_snapshot") {
                authHeaders(session)
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SnapshotInsert(session.userId, payload, 1)))
            }
        if (response.status == HttpStatusCode.Conflict) return false
        if (!response.status.isSuccess()) throw response.toException()
        return true
    }

    /**
     * Optimistic-concurrency update: writes [payload] at `revision = expectedRevision + 1` only if the
     * remote row still sits at [expectedRevision]. Returns `false` if the guard matched no row (the remote
     * moved underneath us — the caller must pull and reconcile), `true` on success.
     */
    suspend fun update(session: SupabaseSession, payload: String, expectedRevision: Long): Boolean {
        val response =
            http.patch("${config.restUrl}/scheduler_snapshot") {
                authHeaders(session)
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                url.parameters.append("user_id", "eq.${session.userId}")
                url.parameters.append("revision", "eq.$expectedRevision")
                setBody(json.encodeToString(SnapshotUpdate(payload, expectedRevision + 1)))
            }
        if (!response.status.isSuccess()) throw response.toException()
        val updated = json.decodeFromString<List<SnapshotRow>>(response.bodyAsText())
        return updated.isNotEmpty()
    }

    /**
     * Reads the account's server-side force-logout marker `account_logout.logout_at` (epoch millis), or null
     * if no row exists. Set by the account-empty script (scripts/account1-empty-and-open.bat) BEFORE it
     * clears the snapshot; [SchedulerSyncEngine] records it as a per-login baseline and signs the device out
     * when it advances, so a still-running app can't re-seed the snapshot the empty just deleted.
     */
    suspend fun fetchLogoutAt(session: SupabaseSession): Long? {
        val response =
            http.get("${config.restUrl}/account_logout") {
                authHeaders(session)
                url.parameters.append("user_id", "eq.${session.userId}")
                url.parameters.append("select", "logout_at")
            }
        if (!response.status.isSuccess()) throw response.toException()
        val rows = json.decodeFromString<List<AccountLogoutRow>>(response.bodyAsText())
        return rows.firstOrNull()?.logoutAt?.let { Instant.parse(it).toEpochMilliseconds() }
    }

    // ---- Presence (PostgREST, PRD §15) ----

    /**
     * Upserts this device's presence row (heartbeat) keyed by `(user_id, device_id)`. `merge-duplicates`
     * makes a repeat from the same device update its existing row rather than conflict; `updated_at` is
     * refreshed server-side (the table trigger), so freshness does not depend on the client clock.
     */
    suspend fun upsertPresence(session: SupabaseSession, deviceId: String, kind: String, screenActive: Boolean) {
        val response =
            http.post("${config.restUrl}/device_presence") {
                authHeaders(session)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PresenceUpsert(session.userId, deviceId, kind, screenActive)))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /** Returns every presence row for this account (all of the user's devices). */
    suspend fun fetchPresence(session: SupabaseSession): List<DevicePresence> {
        val response =
            http.get("${config.restUrl}/device_presence") {
                authHeaders(session)
                url.parameters.append("user_id", "eq.${session.userId}")
                url.parameters.append("select", "device_id,kind,screen_active,updated_at")
            }
        if (!response.status.isSuccess()) throw response.toException()
        return json.decodeFromString<List<DevicePresence>>(response.bodyAsText())
    }

    // ---- Device-sleep gaps (PostgREST, PRD §15) ----

    /**
     * Upserts this device's gap [rows] keyed by `(user_id, device_id, sleep_start)`. `merge-duplicates`
     * makes a repeat of the same interval update its existing row rather than conflict. A no-op for an empty
     * list (PostgREST rejects an empty insert body).
     */
    suspend fun upsertSleepGaps(session: SupabaseSession, rows: List<SleepGapRow>) {
        if (rows.isEmpty()) return
        val body = rows.map { GapUpsert(session.userId, it.deviceId, it.sleepStart, it.sleepEnd, it.recordedAt) }
        val response =
            http.post("${config.restUrl}/device_sleep_gap") {
                authHeaders(session)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /** Returns every gap row for this account (all of the user's devices). */
    suspend fun fetchSleepGaps(session: SupabaseSession): List<SleepGapRow> {
        val response =
            http.get("${config.restUrl}/device_sleep_gap") {
                authHeaders(session)
                url.parameters.append("user_id", "eq.${session.userId}")
                url.parameters.append("select", "device_id,sleep_start,sleep_end,recorded_at")
            }
        if (!response.status.isSuccess()) throw response.toException()
        return json.decodeFromString<List<SleepGapRow>>(response.bodyAsText())
    }

    // ---- Device-active sessions + server-derived pauses (PostgREST, PRD §15) ----

    /**
     * Upserts this device's active-session [rows] keyed by `(user_id, device_id, start_ms)`.
     * `merge-duplicates` makes a repeat of the same session (its `end_ms` extended by a later heartbeat)
     * update its existing row rather than conflict. A no-op for an empty list (PostgREST rejects an empty
     * insert body).
     */
    suspend fun upsertActiveSessions(session: SupabaseSession, rows: List<ActiveSessionRow>) {
        if (rows.isEmpty()) return
        val body = rows.map { ActiveSessionUpsert(session.userId, it.deviceId, it.startMs, it.endMs, it.updatedAt) }
        val response =
            http.post("${config.restUrl}/device_active_session") {
                authHeaders(session)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /**
     * Calls the `derive_pauses(p_since, p_until)` RPC — the server unions every device's active intervals for
     * this account and returns the interior gaps (the account-wide pauses) over the window. RLS scopes the
     * function to the caller's own rows.
     */
    suspend fun fetchDerivedPauses(session: SupabaseSession, sinceMillis: Long, untilMillis: Long): List<PauseRow> {
        val response =
            http.post("${config.restUrl}/rpc/derive_pauses") {
                authHeaders(session)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(DerivePausesArgs(sinceMillis, untilMillis)))
            }
        if (!response.status.isSuccess()) throw response.toException()
        return json.decodeFromString<List<PauseRow>>(response.bodyAsText())
    }

    // ---- Pause-end cue delivery (PostgREST, PRD §15 / ARCHITECTURE.md §8) ----

    /**
     * Upserts the account's next pause-end cue instant (keyed by `user_id`). [originDeviceId] records which
     * device's change set it, so the `tick_pause_cues()` cron can skip the server push when the origin is the
     * last phone (it already scheduled the cue locally). `merge-duplicates` overwrites the single per-user row.
     */
    suspend fun upsertPauseCueSchedule(session: SupabaseSession, dueAtIso: String, originDeviceId: String) {
        val response =
            http.post("${config.restUrl}/pause_cue_schedule") {
                authHeaders(session)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PauseCueUpsert(session.userId, dueAtIso, originDeviceId)))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /**
     * Reads the account's currently-stored next pause-end instant (epoch millis), or null if no row exists.
     * Seeds `d1` for the deferred cue push ([org.example.project.scheduler.sync.PauseCuePushScheduler]).
     */
    suspend fun fetchPauseCueSchedule(session: SupabaseSession): Long? {
        val response =
            http.get("${config.restUrl}/pause_cue_schedule") {
                authHeaders(session)
                url.parameters.append("user_id", "eq.${session.userId}")
                url.parameters.append("select", "due_at")
            }
        if (!response.status.isSuccess()) throw response.toException()
        val rows = json.decodeFromString<List<PauseCueRow>>(response.bodyAsText())
        return rows.firstOrNull()?.dueAt?.let { Instant.parse(it).toEpochMilliseconds() }
    }

    /**
     * Claims [deviceId] as the account's last-logged-in phone (the single device that voices the cue). The
     * `account_last_phone` UPDATE fires the DB trigger that pushes `cancel` to the *previous* phone, so only
     * one phone ever speaks. `merge-duplicates` overwrites the single per-user row.
     */
    suspend fun claimLastPhone(session: SupabaseSession, deviceId: String) {
        val response =
            http.post("${config.restUrl}/account_last_phone") {
                authHeaders(session)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(LastPhoneUpsert(session.userId, deviceId)))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /**
     * Registers this device's push token (keyed by `(user_id, device_id)`) so the `pause-cue` Edge Function can
     * reach it. [platform] is `"fcm"` on Android / `"apns"` on iOS. `merge-duplicates` refreshes the token on
     * rotation.
     */
    suspend fun upsertPushToken(session: SupabaseSession, deviceId: String, kind: String, platform: String, token: String) {
        val response =
            http.post("${config.restUrl}/device_push_token") {
                authHeaders(session)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PushTokenUpsert(session.userId, deviceId, kind, platform, token)))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    fun close() = http.close()

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders(session: SupabaseSession) {
        header("apikey", config.anonKey)
        header("Authorization", "Bearer ${session.accessToken}")
    }

    private suspend fun HttpResponse.toException() = SupabaseException(status.value, bodyAsText())

    companion object {
        private fun defaultHttpClient(): HttpClient =
            HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
    }
}

@Serializable private data class EmailPasswordBody(val email: String, val password: String)

@Serializable private data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val user: TokenUser,
)

@Serializable private data class TokenUser(val id: String)

@Serializable private data class SnapshotRow(val payload: String, val revision: Long)

@Serializable
private data class SnapshotInsert(
    @SerialName("user_id") val userId: String,
    val payload: String,
    val revision: Long,
)

@Serializable private data class SnapshotUpdate(val payload: String, val revision: Long)

@Serializable
private data class PresenceUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    val kind: String,
    @SerialName("screen_active") val screenActive: Boolean,
)

@Serializable
private data class GapUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("sleep_start") val sleepStart: Long,
    @SerialName("sleep_end") val sleepEnd: Long,
    @SerialName("recorded_at") val recordedAt: Long,
)

@Serializable
private data class ActiveSessionUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
private data class DerivePausesArgs(
    @SerialName("p_since") val pSince: Long,
    @SerialName("p_until") val pUntil: Long,
)

@Serializable
private data class PauseCueUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("due_at") val dueAt: String,
    @SerialName("origin_device_id") val originDeviceId: String,
)

@Serializable private data class PauseCueRow(@SerialName("due_at") val dueAt: String)

@Serializable private data class AccountLogoutRow(@SerialName("logout_at") val logoutAt: String)

@Serializable
private data class LastPhoneUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
private data class PushTokenUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    val kind: String,
    val platform: String,
    val token: String,
)
