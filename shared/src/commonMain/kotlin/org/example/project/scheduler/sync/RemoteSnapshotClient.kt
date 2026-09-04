package org.example.project.scheduler.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.ActiveSessionRecord

/** A signed-in Supabase session: the bearer token plus enough to refresh it and identify the user. */
@Serializable
data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
)

/**
 * The remote mirror of one user's [org.example.project.scheduler.persistence.PersistedSnapshot].
 *
 * [writerDeviceId] is the device that wrote this revision (migration `20260730000000`); `null` when the
 * revision was written by a client older than that migration. [SchedulerSyncEngine] uses it to tell a
 * genuine peer write from its OWN push whose acknowledgement was lost in transit.
 */
data class RemoteSnapshot(val payload: String, val revision: Long, val writerDeviceId: String? = null)

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

    /**
     * Creates a **guest account** (PRD §5): a real Supabase account with no email and no password — GoTrue's
     * anonymous sign-in, `POST /auth/v1/signup` with an empty body. It behaves exactly like any other account
     * (own `scheduler_snapshot` row, own device/presence rows, own RLS-scoped data); it simply has no
     * credentials, so no other device can ever sign in to it — the guest belongs to the device that created
     * it. [updateCredentials] later turns the very same account into a normal one, keeping all its data.
     *
     * Requires **anonymous sign-ins to be enabled** on the Supabase project (Dashboard → Authentication →
     * Sign In / Providers, or `enable_anonymous_sign_ins` in `supabase/config.toml`); it throws
     * [SupabaseException] (422) when they are not.
     */
    suspend fun signUpGuest(): SupabaseSession = token("${config.authUrl}/signup", "{}")

    /**
     * Sets the signed-in account's email + password — the "create an account" action on a guest account
     * (PRD §5): the guest account **becomes** that account (same user id, same data, same devices), rather
     * than a second account being created and the data copied. `PUT /auth/v1/user`, the same call Supabase's
     * own clients use to convert an anonymous user to a permanent one. Requires the project's email
     * confirmation to be disabled (as the account scripts already do), otherwise GoTrue parks the address as
     * a pending change instead of applying it. Throws [SupabaseException] if the email is already taken.
     */
    suspend fun updateCredentials(session: SupabaseSession, email: String, password: String) {
        val response =
            http.put("${config.authUrl}/user") {
                authHeaders(session)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(EmailPasswordBody(email, password)))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

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
                url.parameters.append("select", "payload,revision,writer_device_id")
            }
        if (!response.status.isSuccess()) throw response.toException()
        val rows = json.decodeFromString<List<SnapshotRow>>(response.bodyAsText())
        return rows.firstOrNull()?.let { RemoteSnapshot(it.payload, it.revision, it.writerDeviceId) }
    }

    /**
     * Inserts the first revision for this user. Returns `false` if a row already exists (another device
     * created it first) — the caller should [fetch] and reconcile instead. Throws on other errors.
     */
    suspend fun insert(session: SupabaseSession, payload: String, deviceId: String): Boolean {
        val response =
            http.post("${config.restUrl}/scheduler_snapshot") {
                authHeaders(session)
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SnapshotInsert(session.userId, payload, 1, deviceId)))
            }
        if (response.status == HttpStatusCode.Conflict) return false
        if (!response.status.isSuccess()) throw response.toException()
        return true
    }

    /**
     * Optimistic-concurrency update: writes [payload] at `revision = expectedRevision + 1` only if the
     * remote row still sits at [expectedRevision]. Returns `false` if the guard matched no row (the remote
     * moved underneath us — the caller must pull and reconcile), `true` on success.
     *
     * [deviceId] is stamped into `writer_device_id` so the caller can later recognise this very revision as
     * its own if the response below never comes back (see [RemoteSnapshot.writerDeviceId]). It must be
     * written in the SAME statement as the payload: a separate write would leave a window where the row is
     * advanced but unattributed, which is exactly the state the repair keys on.
     */
    suspend fun update(
        session: SupabaseSession,
        payload: String,
        expectedRevision: Long,
        deviceId: String,
    ): Boolean {
        val response =
            http.patch("${config.restUrl}/scheduler_snapshot") {
                authHeaders(session)
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                url.parameters.append("user_id", "eq.${session.userId}")
                url.parameters.append("revision", "eq.$expectedRevision")
                setBody(json.encodeToString(SnapshotUpdate(payload, expectedRevision + 1, deviceId)))
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

    /**
     * The **`t_a` tick** (PRD §15, migrations 20260724000000 + 20260726000000): upserts this device's row in the
     * presence table (`device_heartbeat`) through the `publish_presence` RPC and returns the account's current
     * `t_a`, in SECONDS, so the caller can re-pace itself when it is changed over HTTP.
     *
     * The whole request body is the device id — the presence row is `{ account, device, time of upsert }` and
     * nothing else since the break instants moved to their own event-driven row ([publishNextBreak]). An RPC
     * rather than a plain PostgREST upsert for three reasons: the server (not the client clock) stamps
     * `beat_at`; the account's `data_payload_sent` row is upserted back to `false` in the same call, re-arming
     * the next idle episode atomically with the beat; and the reply carries `t_a` back at no extra request.
     */
    suspend fun publishPresence(session: SupabaseSession, deviceId: String): Int? {
        val response =
            http.post("${config.restUrl}/rpc/publish_presence") {
                authHeaders(session)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PublishPresenceArgs(deviceId)))
            }
        if (!response.status.isSuccess()) throw response.toException()
        // The function returns a bare scalar (`10`); a blank body just means "keep the current pacing".
        return response.bodyAsText().trim().toIntOrNull()
    }

    /**
     * PRD §15 (migrations 20260726000000 + 20260728000000): upserts the ACCOUNT's `device_break` row — when each
     * of the two screen breaks next comes due — through the `publish_next_break` RPC. Called **only when the app
     * calculates a different pair**, so the server's copy is never a beat behind the schedule. Each value is the
     * pose's fixed DUE instant on the real wall clock, which the server's overdue gate compares against the
     * account's last `beat_at`; the account itself comes from the JWT, so the two instants are the entire body.
     */
    suspend fun publishNextBreak(
        session: SupabaseSession,
        fiveMinDueMs: Long?,
        fifteenMinDueMs: Long?,
    ) {
        val response =
            http.post("${config.restUrl}/rpc/publish_next_break") {
                authHeaders(session)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PublishNextBreakArgs(fiveMinDueMs, fifteenMinDueMs)))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /**
     * PRD §15 clean screen-off: invokes the `pause-cue` Edge Function (**e1** — the one that *decides* whether a
     * data payload must be sent) directly, with this device's own user JWT, the moment this device's screen goes
     * off. e1 re-evaluates account liveness server-side — excluding [deviceId], whose presence row is
     * necessarily still fresh — and arms the cue at `now() + break_length` right away instead of waiting up to
     * `t_b + 2·t_a` for the cron to notice the tick stopped. The account is taken from the JWT's `sub` claim, so
     * the body is just this device's id.
     *
     * Best-effort by construction: if this call is lost (or the app was killed before making it), the presence
     * row simply goes stale and the cron's own function (**e2**, `pause-cue-cron`) delivers the same cue, just
     * later and timed from an estimate.
     */
    suspend fun notifyScreenOff(session: SupabaseSession, deviceId: String) {
        val response =
            http.post("${config.functionsUrl}/pause-cue") {
                authHeaders(session)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ScreenOffInvoke(action = "evaluate", deviceId = deviceId)))
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    // ---- PRD §15 device-active sessions (PostgREST) — ride a reconcile (any trigger), never a timer ----

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

    // ---- README 3 modes: the away flag and the rule set (migration 20260904000000) ----

    /**
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes* — **this device's "I'm away" flag, out and back**
     * through the `sync_device_away` RPC. The account comes from the JWT, so the body is this device and the
     * flag; [away] `null` reads without writing.
     *
     * An RPC rather than a plain upsert because one call has to do three things atomically: write the flag,
     * answer the ACCOUNT's question (mode 3 is a quantifier over every device's flag, not this one's), and
     * open or close the mode-3 episode the answer implies — which is what [fetchAwaySpans] reads back.
     */
    suspend fun syncDeviceAway(session: SupabaseSession, deviceId: String, away: Boolean?): Boolean {
        val response =
            http.post("${config.restUrl}/rpc/sync_device_away") {
                authHeaders(session)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SyncDeviceAwayArgs(deviceId, away)))
            }
        if (!response.status.isSuccess()) throw response.toException()
        // The function returns a bare scalar (`true`/`false`); a blank body is "nothing said", i.e. not away.
        return response.bodyAsText().trim().equals("true", ignoreCase = true)
    }

    /**
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes* — **the scheduler's SET OF RULES for the two
     * poses**, replaced whole through the `publish_break_rules` RPC (`screen_break_rule`).
     *
     * This is the only thing the server is given about where breaks fall, and all it does with it is compare
     * `now()` against the windows: in mode 3 nothing drags a pose, so where the scheduler placed it is where it
     * happens. The server never places one.
     */
    suspend fun publishBreakRules(session: SupabaseSession, rules: List<BreakWindow>) {
        val response =
            http.post("${config.restUrl}/rpc/publish_break_rules") {
                authHeaders(session)
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        PublishBreakRulesArgs(rules.map { BreakRuleRow(it.key, it.startMillis, it.endMillis) }),
                    ),
                )
            }
        if (!response.status.isSuccess()) throw response.toException()
    }

    /**
     * The account's **mode-3 stretches** overlapping `[fromMillis, toMillis]`, oldest first — what a waking app
     * asks for so its fast-forward move of the now-line is walked in mode 3 where the account really was on a
     * declared break. A still-open span is returned with its end clamped to [toMillis] by the server.
     */
    suspend fun fetchAwaySpans(
        session: SupabaseSession,
        fromMillis: Long,
        toMillis: Long,
    ): List<TaskTimeRange> {
        val response =
            http.post("${config.restUrl}/rpc/away_spans") {
                authHeaders(session)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AwaySpanArgs(fromMillis, toMillis)))
            }
        if (!response.status.isSuccess()) throw response.toException()
        return json.decodeFromString<List<AwaySpanRow>>(response.bodyAsText())
            .map { TaskTimeRange(it.startMs, it.endMs) }
            .filter { it.endEpochMillis > it.startEpochMillis }
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
                // Without a timeout a response that never arrives (dropped connection, the machine
                // suspending mid-request) leaves the call parked forever — and because every snapshot path
                // funnels through the one mutex-guarded `SchedulerSyncEngine.reconcile()`, that wedges ALL
                // syncing behind it until the socket eventually errors (observed: ~40 min, across an OS
                // sleep). Fail in seconds instead; the next sync moment retries.
                install(HttpTimeout) {
                    requestTimeoutMillis = 45_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                }
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

@Serializable
private data class SnapshotRow(
    val payload: String,
    val revision: Long,
    // Null on a row last written by a client older than migration 20260730000000, and absent altogether
    // from a project that has not applied it — hence the default, so an unmigrated project still decodes.
    @SerialName("writer_device_id") val writerDeviceId: String? = null,
)

@Serializable
private data class SnapshotInsert(
    @SerialName("user_id") val userId: String,
    val payload: String,
    val revision: Long,
    @SerialName("writer_device_id") val writerDeviceId: String,
)

@Serializable
private data class SnapshotUpdate(
    val payload: String,
    val revision: Long,
    @SerialName("writer_device_id") val writerDeviceId: String,
)

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

/**
 * Arguments of the `publish_presence` RPC — the `t_a` tick. `user_id` comes from the JWT, never the body, and
 * the server stamps the time, so the device id is the entire payload.
 */
@Serializable
private data class PublishPresenceArgs(
    @SerialName("p_device_id") val deviceId: String,
)

/**
 * Arguments of the `publish_next_break` RPC — the event-driven break write. The account comes from the JWT and
 * the row is account-keyed, so the two due instants are the whole payload.
 */
@Serializable
private data class PublishNextBreakArgs(
    @SerialName("p_break_5min_due_ms") val fiveMinDueMs: Long?,
    @SerialName("p_break_15min_due_ms") val fifteenMinDueMs: Long?,
)

/**
 * Body of the direct `pause-cue` Edge Function call on a clean screen-off. `action:"evaluate"` asks e1 to decide
 * (and claim) server-side; `device_id` is this device — the one that just went dark, so it must not count
 * itself as an active device.
 */
@Serializable
private data class ScreenOffInvoke(
    // No default value: kotlinx.serialization omits defaults from the encoded body, and e1 needs the action.
    val action: String,
    @SerialName("device_id") val deviceId: String,
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

/**
 * Arguments of the `sync_device_away` RPC — this device's away flag. The account is the JWT's; `p_away` is null
 * to read the account's answer without writing.
 */
@Serializable
private data class SyncDeviceAwayArgs(
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_away") val away: Boolean?,
)

/** Arguments of the `publish_break_rules` RPC — the whole rule set, replaced. */
@Serializable
private data class PublishBreakRulesArgs(
    @SerialName("p_rules") val rules: List<BreakRuleRow>,
)

/** One published rule: a placed pose on the REAL wall clock. Short keys — the array is the request body. */
@Serializable
private data class BreakRuleRow(
    @SerialName("k") val kind: String,
    @SerialName("s") val startMs: Long,
    @SerialName("e") val endMs: Long,
)

/** Arguments of the `away_spans` RPC — the window a waking app is asking about. */
@Serializable
private data class AwaySpanArgs(
    @SerialName("p_from_ms") val fromMs: Long,
    @SerialName("p_to_ms") val toMs: Long,
)

/** One row of it: a stretch the account was in mode 3 for, clipped to the asked window. */
@Serializable
private data class AwaySpanRow(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
)

@Serializable
private data class ActiveSessionRow(
    @SerialName("device_id") val deviceId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val kind: String = "",
)
