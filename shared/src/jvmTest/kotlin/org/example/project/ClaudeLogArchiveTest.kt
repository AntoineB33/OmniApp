package org.example.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.example.project.claudelogs.ClaudeLogArchive
import org.example.project.claudelogs.ClaudeLogEntry
import org.example.project.claudelogs.ClaudeLogKeeper
import org.example.project.claudelogs.ClaudeLogKind
import org.example.project.claudelogs.ClaudeLogSession
import org.example.project.quota.ClaudeLogFeed
import org.example.project.quota.FakeSupabase
import org.example.project.scheduler.persistence.SqlDelightSchedulerStore
import org.example.project.scheduler.persistence.SyncMeta
import org.example.project.scheduler.persistence.db.SchedulerDatabase
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.sync.SupabaseConfig

/**
 * User rule 2026-10-19: *"when the program gets logs from the local folder, it stays in the account's memory. If the
 * folder got deleted, the app keeps the logs. The limit of the memory is adapted for each device or server."*
 */
class ClaudeLogArchiveTest {
    private fun store(user: String = "user-1") =
        SqlDelightSchedulerStore(SchedulerDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), SchedulerDatabase.Schema)))
            .also { it.saveSyncMeta(SyncMeta(deviceId = "dev", userId = user)) }

    private fun session(name: String, vararg texts: String, from: Long = 1_000_000L, title: String? = "Title $name") =
        ClaudeLogSession(
            path = "C:/logs/proj/$name.jsonl",
            sessionId = name,
            folder = "proj",
            cwd = "C:/code/proj",
            title = title,
            entries = texts.mapIndexed { i, t ->
                ClaudeLogEntry(if (i % 2 == 0) ClaudeLogKind.Prompt else ClaudeLogKind.Answer, t, from + i * 1_000L, null, i)
            },
        )

    private fun texts(sessions: List<ClaudeLogSession>) = sessions.flatMap { s -> s.entries.map { it.text } }.toSet()

    @Test
    fun the_logs_a_folder_gave_stay_when_the_folder_is_gone_and_across_a_restart() {
        val store = store()
        val keeper = ClaudeLogKeeper(store, limitBytes = Long.MAX_VALUE)
        val account = keeper.activeAccount()
        keeper.absorb(account, listOf(session("a", "Find the bug", "It is an off-by-one")))
        // The folder deleted: the read gives nothing (null), and the account still has them.
        assertEquals(setOf("Find the bug", "It is an off-by-one"), texts(keeper.absorb(account, null)))
        // A session gone from the folder, another one new: both kept.
        assertEquals(
            setOf("Find the bug", "It is an off-by-one", "Next"),
            texts(keeper.absorb(account, listOf(session("b", "Next", from = 2_000_000L)))),
        )
        // The app restarted: read off the store.
        assertEquals(setOf("Find the bug", "It is an off-by-one", "Next"), texts(ClaudeLogKeeper(store, Long.MAX_VALUE).sessions(account)))
    }

    @Test
    fun a_log_read_again_replaces_its_entries_and_an_answer_gets_its_prompt_back() {
        val keeper = ClaudeLogKeeper(store(), limitBytes = Long.MAX_VALUE)
        val account = keeper.activeAccount()
        keeper.absorb(account, listOf(session("a", "Find the bug", "It is an off-by-one", title = null)))
        val again = keeper.absorb(account, listOf(session("a", "Find the bug", "It is an off-by-one", title = "Parser bug hunt"))).single()
        assertEquals("Parser bug hunt", again.title, "the title the log gained")
        assertEquals(2, again.entries.size, "one record per entry, not two")
        assertEquals("Find the bug", again.entries[1].replyTo, "what the answer replied to, put back from its session")
        assertEquals("C:/logs/proj/a.jsonl", again.path)
    }

    @Test
    fun past_the_devices_limit_the_oldest_entries_go_first() {
        val older = session("old", "a".repeat(1_000), from = 1_000L)
        val newer = session("new", "b".repeat(1_000), from = 9_000_000L)
        val one = ClaudeLogArchive.records(listOf(newer)).single().sizeBytes
        val keeper = ClaudeLogKeeper(store(), limitBytes = one)
        val account = keeper.activeAccount()
        keeper.absorb(account, listOf(older))
        assertEquals(setOf("a".repeat(1_000)), texts(keeper.sessions(account)))
        assertEquals(setOf("b".repeat(1_000)), texts(keeper.absorb(account, listOf(newer))), "the newer one kept, the older dropped")
    }

    @Test
    fun each_account_keeps_its_own_logs() {
        val store = store("user-1")
        ClaudeLogKeeper(store, Long.MAX_VALUE).absorb("user-1", listOf(session("a", "One's")))
        store.saveSyncMeta(SyncMeta(deviceId = "dev", userId = "user-2"))
        val keeper = ClaudeLogKeeper(store, Long.MAX_VALUE)
        assertEquals("user-2", keeper.activeAccount())
        assertTrue(keeper.sessions("user-2").isEmpty(), "nothing smears into the other account")
        assertEquals(setOf("One's"), texts(keeper.sessions("user-1")))
    }

    @Test
    fun what_is_sent_is_the_newest_that_the_server_keeps_in_bounded_requests() {
        val records = ClaudeLogArchive.records(ClaudeLogFeed(1_000L).sessions(ClaudeLogArchive.SERVER_LIMIT_BYTES * 3))
        val sent = ClaudeLogArchive.toPush(records, onServer = emptySet())
        assertTrue(sent.sumOf { it.sizeBytes } <= ClaudeLogArchive.SERVER_LIMIT_BYTES, "never more than the server keeps")
        val oldestSent = sent.minOf { it.timeMillis!! }
        assertTrue(records.filter { it !in sent }.all { it.timeMillis!! <= oldestSent }, "and the newest of them")
        assertTrue(ClaudeLogArchive.batches(sent).all { b -> b.sumOf { it.sizeBytes } <= ClaudeLogArchive.PUSH_BATCH_BYTES })
        assertEquals(sent.toSet(), ClaudeLogArchive.batches(sent).flatten().toSet())
        assertTrue(ClaudeLogArchive.toPush(records, sent.mapTo(HashSet()) { it.entryId }).isEmpty(), "what the server holds is not sent again")
    }

    /**
     * One device as the logs page drives it: its store, its sync engine on [server], its keeper. No `SchedulerEngine`:
     * starting one installs seams on `SchedulerReducer` that outlive the test and change what the next test plans.
     */
    private inner class Device(id: String, server: FakeSupabase, dispatcher: kotlinx.coroutines.test.TestDispatcher) {
        val store =
            store("user-logs").also {
                val token = server.tokenFor("user-logs")
                it.saveSyncMeta(SyncMeta(deviceId = id, accessToken = token, refreshToken = token, userId = "user-logs"))
            }
        val sync =
            SchedulerSyncEngine(
                RemoteSnapshotClient(SupabaseConfig("https://logs.supabase.co", "anon"), io.ktor.client.HttpClient(server.engine(dispatcher))),
                store,
            ).also { it.restoreSession() }
        val logs = ClaudeLogKeeper(store, Long.MAX_VALUE)

        /** What the logs page does when shown: [read] kept, the newest of it sent, the other devices' pulled. */
        suspend fun readLogs(read: List<ClaudeLogSession>?) {
            val account = logs.activeAccount()
            logs.absorb(account, read)
            sync.syncClaudeLogs(logs.pushBatches(account), logs.cursor(account))?.let(logs::applySync)
        }
    }

    @Test
    fun a_phone_gets_the_logs_the_desktop_read_and_the_server_keeps_only_its_bound() = runTest {
        val server = FakeSupabase({ testScheduler.currentTime }, claudeLogKeptBytes = ClaudeLogArchive.SERVER_LIMIT_BYTES)
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val desktop = Device("desktop", server, dispatcher)
        val phone = Device("phone", server, dispatcher)
        desktop.readLogs(ClaudeLogFeed(1_000_000L).sessions(ClaudeLogArchive.SERVER_LIMIT_BYTES * 2))
        phone.readLogs(null)

        val kept = server.table("claude_log_entry").rows.values.sumOf { it["size_bytes"].toString().toLong() }
        assertTrue(kept in 1..ClaudeLogArchive.SERVER_LIMIT_BYTES, "the server keeps its bound: $kept")
        val onPhone = phone.logs.sessions("user-logs").flatMap { it.entries }
        val onDesktop = desktop.logs.sessions("user-logs").flatMap { it.entries }
        assertTrue(onPhone.isNotEmpty() && onPhone.size < onDesktop.size, "the phone has the newest, the desktop all of them")
        assertEquals(onDesktop.maxOf { it.timeMillis!! }, onPhone.maxOf { it.timeMillis!! })
        assertNull(onPhone.firstOrNull { e -> onDesktop.none { it.text == e.text } }, "nothing the desktop did not read")
        assertEquals(onPhone.size, ClaudeLogKeeper(phone.store, Long.MAX_VALUE).sessions("user-logs").sumOf { it.entries.size }, "kept on the phone's disk")

        // Read again with nothing new: nothing is sent again.
        val written = server.table("claude_log_entry").writes
        desktop.readLogs(null)
        assertEquals(written, server.table("claude_log_entry").writes)
    }
}
