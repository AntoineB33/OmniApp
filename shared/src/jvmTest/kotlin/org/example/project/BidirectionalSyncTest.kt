package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SnapshotChangeSubscription
import org.example.project.scheduler.sync.SupabaseConfig
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PRD §5 bidirectional auto-sync WIRING at the [TaskSchedulerViewModel] level (the reconcile push/pull/conflict
 * semantics themselves are covered by [SchedulerSyncEngineTest]). Verifies deterministically, under virtual time
 * and with no network, that: an authoritative edit auto-reconciles once after the 500 ms debounce (local→remote),
 * a burst of edits coalesces into one reconcile, a pulled remote snapshot never triggers a push-back (echo
 * prevention), and the Realtime auto-pull subscription is pointed at the account on sign-in and cleared on
 * sign-out. A recording [FakeEngine] stands in for the transport so reconcile() records a call instead of doing
 * HTTP; the session is pre-seeded so sign-in needs no round-trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BidirectionalSyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val config = SupabaseConfig("https://test.supabase.co", "anon-key")

    /** A meta store pre-seeded with a signed-in session so [SchedulerSyncEngine.restoreSession] signs in offline. */
    private fun signedInMeta() =
        FakeMetaStore(
            SyncMeta(deviceId = "d", accessToken = "at", refreshToken = "rt", userId = "user-1", email = "e@f.g"),
        )

    private class FakeMetaStore(private var meta: SyncMeta? = null) : SyncMetaStore {
        override fun loadSyncMeta(): SyncMeta? = meta

        override fun saveSyncMeta(meta: SyncMeta) {
            this.meta = meta
        }
    }

    private class RecordingStore : SchedulerStore {
        override fun load(): PersistedSnapshot? = null

        override fun save(snapshot: PersistedSnapshot) = Unit
    }

    /** Records where the Realtime auto-pull subscription was pointed, without opening a socket. */
    private class RecordingSubscription : SnapshotChangeSubscription {
        val accounts = mutableListOf<String?>()

        override fun start() = Unit

        override fun setAccount(userId: String?) {
            accounts.add(userId)
        }
    }

    /** A transport-free engine: reconcile() just counts, and bind() captures the pull sink so a test can fire it. */
    private inner class FakeEngine(meta: SyncMetaStore) :
        SchedulerSyncEngine(RemoteSnapshotClient(config, HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })), meta, json) {
        var reconcileCount = 0
        var applyRemote: ((PersistedSnapshot) -> Unit)? = null

        override fun bind(localSnapshot: () -> PersistedSnapshot, applyRemote: (PersistedSnapshot) -> Unit) {
            this.applyRemote = applyRemote
            super.bind(localSnapshot, applyRemote)
        }

        override suspend fun reconcile() {
            reconcileCount++
        }
    }

    private fun vmOn(engine: SchedulerSyncEngine, sub: SnapshotChangeSubscription, dispatcher: TestDispatcher) =
        TaskSchedulerViewModel(
            store = RecordingStore(),
            saveDispatcher = dispatcher,
            syncEngine = engine,
            startupLogin = { null },
            snapshotSubscription = sub,
        )

    @Test
    fun authoritative_edit_auto_reconciles_after_the_debounce() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val engine = FakeEngine(signedInMeta())
            val sub = RecordingSubscription()
            val vm = vmOn(engine, sub, dispatcher)
            advanceUntilIdle()

            // Restored session pointed the auto-pull subscription at the account; sign-in reconciles nothing.
            assertEquals(listOf<String?>("user-1"), sub.accounts)
            assertEquals(0, engine.reconcileCount)

            // An authoritative edit moves the fingerprint → exactly one auto-push reconcile after the debounce.
            vm.dispatch(SchedulerIntent.SetLookAwayVoice(enabled = false))
            advanceUntilIdle()
            assertEquals(1, engine.reconcileCount)
        }
    }

    @Test
    fun a_burst_of_edits_coalesces_into_a_single_reconcile() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val engine = FakeEngine(signedInMeta())
            val vm = vmOn(engine, RecordingSubscription(), dispatcher)
            advanceUntilIdle()

            vm.dispatch(SchedulerIntent.SetLookAwayVoice(enabled = false))
            vm.dispatch(SchedulerIntent.SetLookAwayVoice(enabled = true))
            vm.dispatch(SchedulerIntent.SetLookAwayVoice(enabled = false))
            advanceUntilIdle()

            assertEquals(1, engine.reconcileCount) // one coalesced push, not three
        }
    }

    @Test
    fun a_derived_only_change_does_not_reconcile() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val engine = FakeEngine(signedInMeta())
            val vm = vmOn(engine, RecordingSubscription(), dispatcher)
            advanceUntilIdle()

            // A local-only view change (neutralized in the sync fingerprint) must never enqueue a push.
            vm.dispatch(SchedulerIntent.SetShowReminders(show = false))
            advanceUntilIdle()
            assertEquals(0, engine.reconcileCount)
        }
    }

    @Test
    fun materializing_past_sleep_does_not_reconcile() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val engine = FakeEngine(signedInMeta())
            val vm = vmOn(engine, RecordingSubscription(), dispatcher)
            advanceUntilIdle()

            // Time-driven past-sleep materialization: it DOES mutate state (a "Sleep" panel is banked and the
            // persisted `nextPanelCounter` advances), so it moves the sync fingerprint — but it is DERIVED
            // (a pure function of now + sleep config + inactivity), so it must NOT auto-push. Left syncable, the
            // counter bump would fire a phantom push that clobbers peers' unpushed edits via whole-doc LWW.
            vm.dispatch(SchedulerIntent.MaterializePastSleep(listOf(TaskTimeRange(0L, 3_600_000L))))
            advanceUntilIdle()

            assertEquals(0, engine.reconcileCount)
        }
    }

    @Test
    fun a_pulled_remote_snapshot_does_not_push_back() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val engine = FakeEngine(signedInMeta())
            val vm = vmOn(engine, RecordingSubscription(), dispatcher)
            advanceUntilIdle()

            // Simulate a Realtime-driven pull landing through the engine's bound sink.
            val remote = SchedulerStateCodec.syncFingerprint(SchedulerState.empty().copy(lookAwayVoiceEnabled = false))
            engine.applyRemote!!(remote)
            advanceUntilIdle()

            assertEquals(false, vm.state.value.lookAwayVoiceEnabled) // remote applied
            // The pull reset the sync baseline and never went through the edit path — no push-back was enqueued.
            assertEquals(0, engine.reconcileCount)
        }
    }

    /**
     * PRD §5: the local data is partitioned per account, so a change of the ACTIVE account must swap what
     * the app shows — here a sign-out, which lands on a (different) guest account whose partition is empty.
     * The edit made on the previous account must not follow the device onto the new one; it stays in the
     * account it was made on (the store keeps it) and comes back when that account signs in again.
     */
    @Test
    fun changing_account_swaps_the_in_memory_state_for_the_new_account_partition() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val engine = FakeEngine(signedInMeta())
            val vm = vmOn(engine, RecordingSubscription(), dispatcher)
            advanceUntilIdle()

            vm.dispatch(SchedulerIntent.SetLookAwayVoice(enabled = false))
            advanceUntilIdle()
            assertEquals(false, vm.state.value.lookAwayVoiceEnabled)

            // Leaving the account (the store then reads the new account's — here empty — partition).
            engine.signOut()
            advanceUntilIdle()

            assertEquals(true, vm.state.value.lookAwayVoiceEnabled, "the new account starts from its own data")
        }
    }

    @Test
    fun sign_out_clears_the_auto_pull_subscription() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val sub = RecordingSubscription()
            val vm = vmOn(FakeEngine(signedInMeta()), sub, dispatcher)
            advanceUntilIdle()
            assertEquals(listOf<String?>("user-1"), sub.accounts) // pointed at the account by init

            vm.signOut()
            advanceUntilIdle()

            // Pointed at the account on login, then cleared (null) on sign-out.
            assertEquals(listOf<String?>("user-1", null), sub.accounts)
        }
    }
}
