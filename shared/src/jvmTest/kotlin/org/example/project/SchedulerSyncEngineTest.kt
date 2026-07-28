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
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.sync.SyncState
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises [SchedulerSyncEngine]'s Phase 1 whole-document last-write-wins reconcile against a stateful
 * in-memory fake of Supabase (GoTrue auth + the single `scheduler_snapshot` row) driven through Ktor's
 * [MockEngine]. Covers: first-device seed (insert), pull-when-remote-newer, push-when-dirty, and the
 * conflict case where the remote advanced past what this device last saw.
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

    /** A snapshot tagged by its [statePayload] so tests can assert which one round-tripped. */
    private fun snap(tag: String) = PersistedSnapshot(statePayload = tag, history = emptyList(), pointers = emptyList())

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    /**
     * Mutable server-side row: the stored payload (a serialized [PersistedSnapshot]) and its revision, plus
     * the account's `account_logout` marker ([logoutAtMillis], null = no row) the force-logout reads.
     */
    private class FakeServer(
        var payload: String? = null,
        var revision: Long = 0,
        var logoutAtMillis: Long? = null,
        /** `writer_device_id` (migration 20260730000000): which device wrote the stored revision. */
        var writerDeviceId: String? = null,
        /**
         * When set, the PATCH is applied server-side and then the connection "drops" — the client sees an
         * exception instead of the response. Models the lost-acknowledgement push the repair keys on.
         */
        var dropPatchResponse: Boolean = false,
    ) {
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

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Get -> {
                        val arr =
                            server.payload?.let {
                                """[{"payload":${json.encodeToString(it)},"revision":${server.revision},""" +
                                    """"writer_device_id":${
                                        server.writerDeviceId?.let { w -> json.encodeToString(w) } ?: "null"
                                    }}]"""
                            } ?: "[]"
                        respond(arr, HttpStatusCode.OK, jsonHeader)
                    }

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Post -> {
                        if (server.payload != null) {
                            respond("", HttpStatusCode.Conflict, jsonHeader)
                        } else {
                            server.payload = json.parseToJsonElement(body).jsonObject["payload"]!!.jsonPrimitive.content
                            server.writerDeviceId =
                                json.parseToJsonElement(body).jsonObject["writer_device_id"]?.jsonPrimitive?.content
                            server.revision = 1
                            respond("", HttpStatusCode.Created, jsonHeader)
                        }
                    }

                    path.endsWith("/scheduler_snapshot") && request.method == HttpMethod.Patch -> {
                        val expected = request.url.parameters["revision"]?.removePrefix("eq.")?.toLong()
                        if (server.payload != null && server.revision == expected) {
                            server.payload = json.parseToJsonElement(body).jsonObject["payload"]!!.jsonPrimitive.content
                            server.writerDeviceId =
                                json.parseToJsonElement(body).jsonObject["writer_device_id"]?.jsonPrimitive?.content
                            server.revision += 1
                            // The write is applied BEFORE the response is read, so "the connection dropped"
                            // still leaves the server one revision ahead — the state the client must recognise.
                            if (server.dropPatchResponse) throw java.io.IOException("connection reset")
                            respond(
                                """[{"payload":${json.encodeToString(server.payload!!)},"revision":${server.revision}}]""",
                                HttpStatusCode.OK,
                                jsonHeader,
                            )
                        } else {
                            respond("[]", HttpStatusCode.OK, jsonHeader)
                        }
                    }

                    else -> respond("", HttpStatusCode.NotFound)
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
    fun first_device_seeds_remote_via_insert() = runTest {
        val server = FakeServer()
        val meta = FakeMetaStore()
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("A") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("A", json.decodeFromString<PersistedSnapshot>(server.payload!!).statePayload)
        assertEquals(1, server.revision)
        assertEquals(1, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty)
        assertNull(applied) // we pushed; nothing pulled
    }

    @Test
    fun pulls_when_remote_is_newer() = runTest {
        val server = FakeServer(payload = json.encodeToString(snap("REMOTE")), revision = 5)
        val meta = FakeMetaStore(SyncMeta(deviceId = "d", lastKnownRevision = 1))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("LOCAL") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("REMOTE", applied!!.statePayload)
        assertEquals(5, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(5, server.revision) // remote untouched
    }

    @Test
    fun pushes_local_changes_when_dirty() = runTest {
        val server = FakeServer(payload = json.encodeToString(snap("OLD")), revision = 2)
        val meta = FakeMetaStore(SyncMeta(deviceId = "d", lastKnownRevision = 2, dirty = true))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("NEW") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("NEW", json.decodeFromString<PersistedSnapshot>(server.payload!!).statePayload)
        assertEquals(3, server.revision)
        assertEquals(3, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty)
        assertNull(applied)
    }

    @Test
    fun dirty_but_content_identical_to_remote_skips_the_phantom_push() = runTest {
        // Regression: a transient derived change (a time-passing reschedule/materialization) can leave `dirty`
        // set even though this device's authoritative projection is byte-identical to what the server holds.
        // Pushing it would advance the revision for no content change, and every peer would then take the LWW
        // `pull` branch and DROP its own genuine unpushed edit. The guard must recognise local == remote and
        // send nothing (just clear the flag), leaving the revision untouched.
        val server = FakeServer(payload = json.encodeToString(snap("SAME")), revision = 2)
        val meta = FakeMetaStore(SyncMeta(deviceId = "d", lastKnownRevision = 2, dirty = true))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("SAME") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals(2, server.revision) // no phantom push — revision never advanced
        assertEquals(2, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty) // flag cleared, nothing to send
        assertNull(applied) // nothing pulled either
    }

    @Test
    fun a_push_whose_response_was_lost_is_adopted_not_pulled_back_over_newer_local_edits() = runTest {
        // Regression (observed 2026-07-27 on a SINGLE-device account: two task cells were deleted and came
        // back seconds later). A push is not atomic from the client's side — the server applies the PATCH and
        // only then does the client read the response. When that response is lost (dropped connection, the
        // machine suspending mid-request) the remote sits one revision ahead while this device still holds the
        // old baseline with `dirty` set. Reading that as "a peer wrote something newer" and pulling would
        // apply THIS DEVICE'S OWN older push over everything the user edited since. `writer_device_id` tells
        // the two apart: the revision must be adopted, and the newer local state pushed on top of it.
        val server = FakeServer(payload = json.encodeToString(snap("V1")), revision = 62, writerDeviceId = "desk")
        val meta = FakeMetaStore(SyncMeta(deviceId = "desk", lastKnownRevision = 62, dirty = true))
        var applied: PersistedSnapshot? = null
        var local = snap("V2")
        val sync = engine(harness(server), meta, { local }, { applied = it })
        sync.signIn("a@b.c", "pw")

        server.dropPatchResponse = true
        sync.reconcile()

        // The write landed (63) but the acknowledgement did not: the baseline is stale and still dirty.
        assertEquals(63, server.revision)
        assertEquals(62, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(true, meta.loadSyncMeta()!!.dirty)

        // The user now makes the edit that must survive (in the field: deleting the two task cells).
        local = snap("V3")
        server.dropPatchResponse = false
        sync.reconcile()

        assertNull(applied) // nothing was applied over the local state — the edit stands
        assertEquals("V3", json.decodeFromString<PersistedSnapshot>(server.payload!!).statePayload)
        assertEquals(64, server.revision)
        assertEquals(64, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty)
    }

    @Test
    fun a_peers_write_one_revision_ahead_still_wins_over_local_changes() = runTest {
        // The repair above must stay narrow: same shape (one revision ahead, local dirty), but written by
        // ANOTHER device, which is a genuine conflict — whole-document LWW still applies and the remote wins.
        val server = FakeServer(payload = json.encodeToString(snap("PEER")), revision = 63, writerDeviceId = "phone")
        val meta = FakeMetaStore(SyncMeta(deviceId = "desk", lastKnownRevision = 62, dirty = true))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("LOCAL") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("PEER", applied!!.statePayload)
        assertEquals(63, server.revision) // remote untouched; the local change was dropped (LWW)
        assertEquals(63, meta.loadSyncMeta()!!.lastKnownRevision)
    }

    @Test
    fun an_unattributed_revision_falls_back_to_the_plain_last_write_wins_pull() = runTest {
        // A row last written by a client older than migration 20260730000000 — or read from a project that has
        // not applied it — carries no writer id. Unknown writer must behave exactly as before (pull), never
        // guess that it was ours.
        val server = FakeServer(payload = json.encodeToString(snap("REMOTE")), revision = 63, writerDeviceId = null)
        val meta = FakeMetaStore(SyncMeta(deviceId = "desk", lastKnownRevision = 62, dirty = true))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("LOCAL") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("REMOTE", applied!!.statePayload)
        assertEquals(63, meta.loadSyncMeta()!!.lastKnownRevision)
    }

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

        assertNull(server.payload) // the emptied account was never re-seeded
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

        assertEquals("FRESH", json.decodeFromString<PersistedSnapshot>(server.payload!!).statePayload)
        assertEquals(SyncState.Idle, sync.state.value)
        assertEquals("user-1", meta.loadSyncMeta()!!.userId)
    }

    @Test
    fun conflict_remote_wins_and_local_change_is_dropped() = runTest {
        // We think we're at revision 2 with a dirty local edit, but the remote already moved to 4.
        val server = FakeServer(payload = json.encodeToString(snap("REMOTE_WINS")), revision = 4)
        val meta = FakeMetaStore(SyncMeta(deviceId = "d", lastKnownRevision = 2, dirty = true))
        var applied: PersistedSnapshot? = null
        val sync = engine(harness(server), meta, { snap("LOCAL_LOSES") }, { applied = it })

        sync.signIn("a@b.c", "pw")
        sync.reconcile()

        assertEquals("REMOTE_WINS", applied!!.statePayload)
        assertEquals(4, meta.loadSyncMeta()!!.lastKnownRevision)
        assertEquals(false, meta.loadSyncMeta()!!.dirty)
        assertEquals(4, server.revision) // local edit never pushed
    }
}
