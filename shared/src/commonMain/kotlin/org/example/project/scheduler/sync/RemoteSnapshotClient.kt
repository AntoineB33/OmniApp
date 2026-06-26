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
