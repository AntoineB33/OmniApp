package org.example.project.scheduler.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Instant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.scheduler.persistence.ActiveSessionRecord

/** A signed-in Supabase session: the bearer token plus enough to refresh it and identify the user. */
@Serializable
data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
)

/** The remote mirror of one user's [org.example.project.scheduler.persistence.PersistedSnapshot]. */
data class RemoteSnapshot(val payload: String, val revision: Long)

/** Raised when Supabase returns a non-success response we did not specifically handle. */
class SupabaseException(val status: Int, message: String) : Exception("Supabase $status: $message")

/**
 * One completed Supabase HTTP call — the raw material for the History window's **Supabase usage** column, a
 * per-device diagnostic of the account's draw-down on the Supabase **free-plan** limits. Every call this
 * client makes consumes egress **bandwidth** (request + response bytes); auth calls also count toward Monthly
 * Active Users, and the sheer request count matters too. [RemoteSnapshotClient] emits one of these for every
 * request via [RemoteSnapshotClient.usageEvents]; the ViewModel turns each into a local-only log entry. Byte
 * counts are best-effort (from `Content-Length`; a chunked/absent length reads 0) — indicative, not billing-exact.
 *
 * The Edge Function invocations (`pause-cue`) are triggered server-side by pg_cron / DB triggers, not by this
 * client, so they are not visible here — only the client's own HTTP traffic is.
 */
data class SupabaseUsageEvent(
    val method: String,
    /** The URL path of the call, e.g. `/rest/v1/scheduler_snapshot`, `/auth/v1/token`, `/rest/v1/rpc/derive_pauses`. */
    val path: String,
    val status: Int,
    val requestBytes: Long,
    val responseBytes: Long,
) {
    /** The free-plan resource bucket this call draws down (every REST/RPC call also spends egress bandwidth). */
    val resource: String
        get() = when {
            path.startsWith("/auth/") -> "Auth"
            "/rpc/" in path -> "Database RPC"
            path.startsWith("/rest/") -> "Database"
            else -> "REST"
        }

    /** A compact `METHOD table` label for the row, e.g. `POST scheduler_snapshot`, `POST rpc/derive_pauses`. */
    val operation: String
        get() {
            val tail = path.substringAfter("/v1/", missingDelimiterValue = path.substringAfterLast('/'))
            return "$method ${tail.ifBlank { path }}"
        }
}

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
    val config: SupabaseConfig = SupabaseConfig.DEFAULT,
    // Injectable so tests can supply a MockEngine; defaults to the platform engine on the classpath.
    private val http: HttpClient = defaultHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Every Supabase HTTP call this client makes (the only calls the app makes) is surfaced here so the History
    // window's local-only "Supabase usage" column can show the account's draw-down on the free-plan limits.
    // replay so the collector, wired slightly after construction by the ViewModel, still catches the launch's
    // login/reconcile traffic; DROP_OLDEST so a stalled collector can never block a request (emits are tryEmit).
    private val _usageEvents =
        MutableSharedFlow<SupabaseUsageEvent>(
            replay = 64,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Every completed Supabase HTTP call (see [SupabaseUsageEvent]); collected by the ViewModel into the usage log. */
    val usageEvents: SharedFlow<SupabaseUsageEvent> = _usageEvents.asSharedFlow()

    init {
        // One interception point over the whole client covers every endpoint below (auth + PostgREST), so the
        // usage log stays complete even as new calls are added. Reading Content-Length does not consume the body.
        http.plugin(HttpSend).intercept { request ->
            val requestBytes = (request.body as? OutgoingContent)?.contentLength ?: 0L
            val path = request.url.build().encodedPath
            val call = execute(request)
            _usageEvents.tryEmit(
                SupabaseUsageEvent(
                    method = request.method.value,
                    path = path,
                    status = call.response.status.value,
                    requestBytes = requestBytes,
                    responseBytes = call.response.contentLength() ?: 0L,
                ),
            )
            call
        }
    }

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

    // ---- Pause-end cue delivery: last-phone + push token (PostgREST, PRD §15) ----

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

    /**
     * Upserts the account's Sleep/Work mode row (keyed by `user_id`) so the external Realtime listener can
     * suppress the pause-end cue while the user is deliberately away. `mode` is `"sleeping"`/`"working"`;
     * `wake_at` carries the scheduled wake instant (ISO-8601) while sleeping, null when working.
     * `merge-duplicates` overwrites the single per-user row.
     */
    suspend fun upsertAccountState(session: SupabaseSession, sleeping: Boolean, wakeAtMillis: Long?) {
        val wakeAtIso = wakeAtMillis?.let { Instant.fromEpochMilliseconds(it).toString() }
        val response =
            http.post("${config.restUrl}/account_state") {
                authHeaders(session)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AccountStateUpsert(session.userId, if (sleeping) "sleeping" else "working", wakeAtIso)))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    // ---- PRD §15 device-active sessions (PostgREST) — ride the manual Sync-button reconcile only ----

    /**
     * Upserts this device's active-session [rows] (keyed `(user_id, device_id, start_ms)`) so peers can show
     * which devices were open during past calendar panels. One batched POST; `merge-duplicates` re-extends a
     * row whose live `end_ms` advanced since the last sync.
     */
    suspend fun upsertActiveSessions(session: SupabaseSession, rows: List<ActiveSessionRecord>) {
        if (rows.isEmpty()) return
        val response =
            http.post("${config.restUrl}/device_active_session") {
                authHeaders(session)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        rows.map {
                            ActiveSessionUpsert(
                                userId = session.userId,
                                deviceId = it.deviceId,
                                startMs = it.startMillis,
                                endMs = it.endMillis,
                                updatedAt = it.updatedAtMillis,
                                kind = it.kind,
                            )
                        },
                    ),
                )
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /** Every active-session row of this account ending at/after [sinceMillis] (all devices, oldest first). */
    suspend fun fetchActiveSessions(session: SupabaseSession, sinceMillis: Long): List<ActiveSessionRecord> {
        val response =
            http.get("${config.restUrl}/device_active_session") {
                authHeaders(session)
                url.parameters.append("user_id", "eq.${session.userId}")
                url.parameters.append("end_ms", "gte.$sinceMillis")
                url.parameters.append("select", "device_id,start_ms,end_ms,updated_at,kind")
                url.parameters.append("order", "start_ms.asc")
            }
        if (!response.status.isSuccess()) throw response.toException()
        return json.decodeFromString<List<ActiveSessionRow>>(response.bodyAsText()).map {
            ActiveSessionRecord(
                deviceId = it.deviceId,
                startMillis = it.startMs,
                endMillis = it.endMs,
                updatedAtMillis = it.updatedAt,
                kind = it.kind,
            )
        }
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

@Serializable
private data class AccountStateUpsert(
    @SerialName("user_id") val userId: String,
    val mode: String,
    @SerialName("wake_at") val wakeAt: String?,
)

@Serializable
private data class ActiveSessionUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val kind: String,
)

@Serializable
private data class ActiveSessionRow(
    @SerialName("device_id") val deviceId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val kind: String = "",
)
