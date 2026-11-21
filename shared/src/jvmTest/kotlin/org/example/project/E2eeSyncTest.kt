package org.example.project

import io.ktor.client.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.quota.FakeSupabase
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.E2ee
import org.example.project.scheduler.sync.E2eeException
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.sync.SyncState

/**
 * `docs/invariants/sync-and-accounts.md` § *End-to-end encryption*, the account flows against the server's own rules
 * ([FakeSupabase] with GoTrue's password check on): what the server ends up holding is never readable, and every device
 * of the account still reads everything.
 */
class E2eeSyncTest {
    private val config = SupabaseConfig("https://e2ee.supabase.co", "anon")
    private val t0 = 1_788_343_200_000L
    private val alarm = AlarmEntry(id = "alarm-1", label = "Wake for the dentist", timeOfDayMinutes = 450)

    @AfterTest
    fun reset() {
        SchedulerReducer.deviceId = { SchedulerReducer.LOCAL_DEVICE_ID }
    }

    private class Meta(var meta: SyncMeta) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta = meta
        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    private inner class Device(id: String, server: FakeSupabase, test: TestScope, var state: SchedulerState = SchedulerState.empty(), meta: SyncMeta = SyncMeta(deviceId = id)) {
        val store = Meta(meta)
        val sync = SchedulerSyncEngine(RemoteSnapshotClient(config, HttpClient(server.engine(StandardTestDispatcher(test.testScheduler)))), store, clock = { t0 })

        init {
            sync.bind({ SchedulerStateCodec.encodeSnapshot(state) }, { state = SchedulerStateCodec.decodeSnapshot(it)!!.copy(histories = state.histories) })
        }
    }

    /** The alarm's row text as the codec writes it: what an older build pushed in clear. */
    private fun legacyRows(): Map<org.example.project.scheduler.sync.EntityRows.Key, String> =
        org.example.project.scheduler.sync.EntityRows.split(SchedulerStateCodec.encodeSnapshot(SchedulerState.empty().copy(alarms = listOf(alarm))).statePayload)

    private fun FakeSupabase.payloads(): List<String> =
        table("scheduler_entity").rows.values.filter { (it["kind"] as? JsonPrimitive)?.content != "forgotten" }.mapNotNull { (it["payload"] as? JsonPrimitive)?.content }

    private fun FakeSupabase.nothingReadable() {
        val payloads = payloads()
        assertTrue(payloads.isNotEmpty())
        assertTrue(payloads.all(E2ee::isEncrypted), "every row on the server is sealed")
        assertTrue(payloads.none { "dentist" in it }, "no title on the server")
        assertTrue(passwords.values.none { it == "pw" }, "the server never holds the password")
    }

    @Test
    fun an_account_from_before_encryption_is_moved_at_its_next_sign_in_and_its_rows_sealed() = runTest {
        val server = FakeSupabase({ t0 }).apply { checkPasswords = true }
        val user = server.registerLegacy("a@b.c", "pw")
        // What an older build left: the account's rows, in clear.
        for ((key, text) in legacyRows()) {
            server.table("scheduler_entity").upsert(
                buildJsonObject {
                    put("user_id", user); put("kind", key.kind); put("entity_id", key.id)
                    put("payload", text); put("deleted", false); put("device_id", "old")
                },
                user,
            )
        }
        // A device still on a build without encryption, signed in before: it must never get to push. (Played here by a
        // device that HAS a key, as an old build pushes without one; a keyless device of this build sends nothing anyway.)
        val oldToken = server.tokenFor(user)
        val old = Device(
            "old-phone", server, this, SchedulerState.empty(),
            SyncMeta(
                deviceId = "old-phone", accessToken = oldToken, refreshToken = oldToken, userId = user, email = "a@b.c",
                acknowledgedLogoutAtMillis = 0L, contentKey = E2ee.ContentKey.random().encoded(),
            ),
        )
        old.sync.restoreSession()
        val desk = Device("desk", server, this)
        desk.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()

        assertEquals(listOf(alarm.label), desk.state.alarms.map { it.label }, "the row in clear was read")
        assertTrue(desk.sync.isSignedIn && desk.sync.state.value == SyncState.Idle, "the migrating device stays signed in: ${desk.sync.state.value}")
        val writes = server.table("scheduler_entity").writes
        old.sync.reconcile()
        assertEquals(writes, server.table("scheduler_entity").writes, "the other devices are signed out before they push anything")
        assertTrue(old.store.meta.userId != user, "the old device left the account")
        server.nothingReadable()
        assertNotNull(server.table("account_key").rows.values.singleOrNull(), "the wrapped key is stored")

        // Another device signs in with the same password: it opens the key and reads everything.
        val phone = Device("phone", server, this)
        phone.sync.signIn("a@b.c", "pw")
        phone.sync.reconcile()
        assertEquals(listOf(alarm.label), phone.state.alarms.map { it.label })
        assertEquals(desk.sync.contentKey?.encoded(), phone.sync.contentKey?.encoded(), "one key per account")

        // The wrong password opens nothing.
        val intruder = Device("intruder", server, this)
        assertFailsWith<Exception> { intruder.sync.signIn("a@b.c", "wrong") }
    }

    @Test
    fun a_guest_claimed_as_an_account_is_read_by_the_next_device_that_signs_in() = runTest {
        val server = FakeSupabase({ t0 }).apply { checkPasswords = true }
        val desk = Device("desk", server, this, SchedulerState.empty().copy(alarms = listOf(alarm)))
        assertTrue(desk.sync.ensureAccount())
        desk.sync.reconcile()
        val guestKey = assertNotNull(desk.sync.contentKey)
        assertTrue(server.table("account_key").rows.isEmpty(), "a guest's key never leaves its device")
        server.nothingReadable()

        desk.sync.createAccount("me@x.y", "pw")
        assertEquals(guestKey.encoded(), desk.sync.contentKey?.encoded(), "claiming keeps the key, so nothing is re-sealed")
        val phone = Device("phone", server, this)
        phone.sync.signIn("me@x.y", "pw")
        phone.sync.reconcile()
        assertEquals(listOf(alarm.label), phone.state.alarms.map { it.label })
        server.nothingReadable()
    }

    @Test
    fun a_device_signed_in_before_the_update_sends_nothing_until_it_signs_in_again() = runTest {
        val server = FakeSupabase({ t0 })
        val token = server.tokenFor("user-9")
        val desk = Device(
            "desk", server, this, SchedulerState.empty().copy(alarms = listOf(alarm)),
            SyncMeta(deviceId = "desk", accessToken = token, refreshToken = token, userId = "user-9", email = "old@x.y"),
        )
        desk.sync.restoreSession()
        desk.sync.reconcile()

        assertTrue(server.table("scheduler_entity").rows.isEmpty(), "no content left the device")
        val state = desk.sync.state.value
        assertTrue(state is SyncState.Error && E2ee.SIGN_IN_FOR_ENCRYPTION in state.message, "it says why: $state")
    }

    @Test
    fun a_device_without_its_key_is_not_signed_out_by_another_devices_move_and_gets_the_key_by_signing_in() = runTest {
        val server = FakeSupabase({ t0 }).apply { checkPasswords = true }
        val user = server.registerLegacy("a@b.c", "pw")
        // The offline desktop: this build, signed in before the account moved, no key yet.
        val token = server.tokenFor(user)
        val desk = Device(
            "desk", server, this, SchedulerState.empty().copy(alarms = listOf(alarm)),
            SyncMeta(deviceId = "desk", accessToken = token, refreshToken = token, userId = user, email = "a@b.c", acknowledgedLogoutAtMillis = 0L),
        )
        desk.sync.restoreSession()
        // The phone moves the account (and raises the marker).
        val phone = Device("phone", server, this)
        phone.sync.signIn("a@b.c", "pw")
        phone.sync.reconcile()
        // The desktop comes online: still on its account, sending nothing, until it signs in.
        desk.sync.reconcile()
        assertEquals(user, desk.store.meta.userId, "not signed out to a guest")
        desk.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()
        assertEquals(SyncState.Idle, desk.sync.state.value)
        assertEquals(phone.sync.contentKey?.encoded(), desk.sync.contentKey?.encoded(), "the account's one key")
        server.nothingReadable()
    }

    @Test
    fun a_row_the_server_moved_or_altered_is_refused() = runTest {
        val server = FakeSupabase({ t0 })
        val desk = Device("desk", server, this, SchedulerState.empty().copy(alarms = listOf(alarm, alarm.copy(id = "alarm-2", label = "Other"))))
        desk.sync.signIn("a@b.c", "pw")
        desk.sync.reconcile()
        // The server swaps two rows' ciphertexts.
        val rows = server.table("scheduler_entity").rows.values.filter { (it["kind"] as? JsonPrimitive)?.content == "alarms" }
        val (one, two) = rows
        server.table("scheduler_entity").upsert(JsonObject(one + ("payload" to two["payload"]!!) + ("device_id" to JsonPrimitive("server"))), one["user_id"].let { (it as JsonPrimitive).content })
        val phone = Device("phone", server, this)
        phone.sync.signIn("a@b.c", "pw")
        phone.sync.reconcile()
        assertNotEquals(SyncState.Idle, phone.sync.state.value, "a moved row does not open: the reconcile fails rather than applying it")
        assertTrue(phone.state.alarms.isEmpty(), "nothing forged was applied")
        assertFailsWith<E2eeException> { E2ee.decrypt(desk.sync.contentKey!!, (two["payload"] as JsonPrimitive).content, E2ee.entityPlace("alarms", (one["entity_id"] as JsonPrimitive).content)) }
    }
}
