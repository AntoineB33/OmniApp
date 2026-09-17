package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.sync.SyncState
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SchedulerSyncEngine]'s ACCOUNT handling against a stateful in-memory fake of Supabase (GoTrue auth, the
 * `account_logout` marker, and the entity rows only as far as "was anything written") driven through Ktor's
 * [MockEngine]: guest accounts, claiming one, signing out, the remote force-logout. The row sync itself is
 * `RowSyncTest`, against [org.example.project.quota.FakeSupabase].
 */
class SchedulerSyncEngineTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    private companion object {
        /** The account [FakeServer] models (the one an email+password sign-in lands on). */
        const val PRIMARY_USER = "user-1"

        /** The account a credential-less (guest) signup creates — a different account with its own rows. */
        const val GUEST_USER = "guest-1"
    }

    /** A real state with one alarm named [tag]. */
    private fun snap(tag: String) =
        SchedulerStateCodec.encodeSnapshot(SchedulerState.empty().copy(alarms = listOf(AlarmEntry(id = tag, label = tag, timeOfDayMinutes = 450))))

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    /** The account's `account_logout` marker ([logoutAtMillis], null = no row) and how many entity rows were written. */
    private class FakeServer(
        var logoutAtMillis: Long? = null,
    ) {
        var entityWrites = 0

        /** Every GoTrue endpoint hit, in order — tells a guest CLAIM (`/user`) from a second signup. */
        val authCalls = mutableListOf<String>()
    }

    private fun harness(server: FakeServer): RemoteSnapshotClient {
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val body = (request.body as? TextContent)?.text ?: ""
                val jsonHeader = headersOf("Content-Type", "application/json")
                // Which account a data call is for: the `user_id=eq.` filter, else the inserted row's user_id.
                val user =
                    request.url.parameters["user_id"]?.removePrefix("eq.")
                        ?: runCatching { json.parseToJsonElement(body).jsonObject["user_id"]?.jsonPrimitive?.content }
                            .getOrNull()
                        ?: PRIMARY_USER
                when {
                    // A credential-less signup is the GUEST account (PRD §5) — a different account, so it gets
                    // a different user id and its own (here: empty) rows.
                    path.startsWith("/auth/v1") -> {
                        server.authCalls += path
                        respond(
                            """{"access_token":"at","refresh_token":"rt","user":{"id":"${
                                if ("email" in body) PRIMARY_USER else GUEST_USER
                            }"}}""",
                            HttpStatusCode.OK,
                            jsonHeader,
                        )
                    }

                    // Any account other than the one this fake models has no rows of its own; a write from it
                    // must not touch [server] (that is exactly what "the guest can't re-seed account 1" means).
                    user != PRIMARY_USER ->
                        respond(if (request.method == HttpMethod.Get) "[]" else "", HttpStatusCode.OK, jsonHeader)

                    path.endsWith("/account_logout") && request.method == HttpMethod.Get -> {
                        val arr =
                            server.logoutAtMillis?.let { """[{"logout_at":"${Instant.fromEpochMilliseconds(it)}"}]""" }
                                ?: "[]"
                        respond(arr, HttpStatusCode.OK, jsonHeader)
                    }

                    path.endsWith("/scheduler_entity") && request.method == HttpMethod.Post -> {
                        server.entityWrites += json.parseToJsonElement(body).let { (it as? kotlinx.serialization.json.JsonArray)?.size ?: 1 }
                        respond("", HttpStatusCode.Created, jsonHeader)
                    }

                    request.method == HttpMethod.Get -> respond("[]", HttpStatusCode.OK, jsonHeader)

                    else -> respond("", HttpStatusCode.Created, jsonHeader)
                }
            }
        return RemoteSnapshotClient(config, HttpClient(engine))
    }

    private fun engine(
        client: RemoteSnapshotClient,
        meta: FakeMetaStore,
        local: () -> PersistedSnapshot,
        applied: (PersistedSnapshot) -> Unit,
    ): SchedulerSyncEngine =
        SchedulerSyncEngine(client, meta, json).apply { bind(local, applied) }

    @Test
    fun remote_logout_after_login_signs_out_to_a_guest_account_and_does_not_reseed() = runTest {
        // A device is signed in (its login recorded logout marker = none) and holds local data. The account is
        // then force-logged-out server-side (the empty script bumps account_logout). The next reconcile must
        // sign this device out of THAT account and push NOTHING to it, so it can't re-seed the snapshot the
        // empty deleted — and then (PRD §5: the app is always connected to an account) land on a fresh guest
        // account, whose own rows are separate.
        val server = FakeServer() // no snapshot on the server (it was just emptied)
        val meta = FakeMetaStore()
        val sync = engine(harness(server), meta, { snap("LOCAL") }, {})

        sync.signIn("a@b.c", "pw") // baseline recorded: no marker yet (0)
        server.logoutAtMillis = 5_000L // account emptied: logout marker set AFTER this device logged in
        sync.reconcile()

        assertEquals(0, server.entityWrites) // the emptied account was never re-seeded
        assertEquals(GUEST_USER, meta.loadSyncMeta()!!.userId) // now on a guest account instead of nothing
        assertNull(meta.loadSyncMeta()!!.email) // a guest account has no credentials
        assertEquals(SyncState.Idle, sync.state.value)
    }

    @Test
    fun a_device_with_no_account_creates_a_guest_one_and_syncs_with_it() = runTest {
        // PRD §5: the app is always connected to an account. A fresh device has none, so reconciling (the
        // startup one included) creates a credential-less guest account and works with it like any other.
        val server = FakeServer()
        val meta = FakeMetaStore()
        val sync = engine(harness(server), meta, { snap("LOCAL") }, {})

        sync.reconcile()

        assertEquals(GUEST_USER, meta.loadSyncMeta()!!.userId)
        assertNull(meta.loadSyncMeta()!!.email)
        assertEquals(SyncState.Idle, sync.state.value)
        assertEquals(GUEST_USER, sync.account.value?.userId)
        assertEquals(true, sync.account.value?.isGuest)
    }

    @Test
    fun creating_an_account_on_a_guest_claims_the_same_account_rather_than_making_a_new_one() = runTest {
        // The requirement: "when the user is on a guest account and creates an account, this guest account
        // gets the new email and password". So it must be a PUT /auth/v1/user on the SAME account — no second
        // signup, no new user id, and therefore nothing to copy over: the data is already there.
        val server = FakeServer()
        val meta = FakeMetaStore()
        val sync = engine(harness(server), meta, { snap("LOCAL") }, {})
        sync.ensureAccount()
        assertEquals(GUEST_USER, meta.loadSyncMeta()!!.userId)

        sync.createAccount("a@b.c", "pw")

        assertEquals(GUEST_USER, meta.loadSyncMeta()!!.userId) // same account…
        assertEquals("a@b.c", meta.loadSyncMeta()!!.email) // …now with credentials
        assertEquals(false, sync.account.value?.isGuest)
        assertEquals(
            listOf("/auth/v1/signup", "/auth/v1/user"),
            server.authCalls,
            "the guest must be CLAIMED (PUT /user), never re-created as a second account",
        )
    }

    @Test
    fun signing_out_lands_on_a_fresh_guest_account() = runTest {
        val server = FakeServer()
        val meta = FakeMetaStore()
        val sync = engine(harness(server), meta, { snap("LOCAL") }, {})
        sync.signIn("a@b.c", "pw")
        assertEquals(PRIMARY_USER, meta.loadSyncMeta()!!.userId)

        sync.signOutToGuest()

        // Never accountless: signing out of an account puts the device on a guest one (with no credentials).
        assertEquals(GUEST_USER, meta.loadSyncMeta()!!.userId)
        assertNull(meta.loadSyncMeta()!!.email)
        assertEquals(SyncState.Idle, sync.state.value)
    }

    @Test
    fun login_after_a_logout_marker_records_it_as_baseline_and_does_not_self_sign_out() = runTest {
        // A device that logs in AFTER a logout marker exists adopts it as its baseline, so the same value on the
        // next reconcile is NOT a new logout — it stays signed in and syncs normally.
        val server = FakeServer(logoutAtMillis = 5_000L)
        val meta = FakeMetaStore()
        val sync = engine(harness(server), meta, { snap("FRESH") }, {})

        sync.signIn("a@b.c", "pw") // baseline recorded = 5000 (the existing marker)
        sync.reconcile()

        assertTrue(server.entityWrites > 0, "it synced its rows")
        assertEquals(SyncState.Idle, sync.state.value)
        assertEquals("user-1", meta.loadSyncMeta()!!.userId)
    }
}
