package org.example.project

import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SupabaseUsageEntry
import org.example.project.scheduler.sync.SupabaseUsageEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The History Manager's local-only **Supabase usage** column: recording appends, the log is a ROLLING TAIL
 * capped at [SchedulerState.MAX_SUPABASE_USAGE_LOG] (keeps the most RECENT that many — unlike the notification
 * log's first-N audit), it round-trips through the codec, an old payload written before the field existed still
 * decodes, and it is a per-device diagnostic that never affects the sync fingerprint and is carried across a
 * remote pull. Also pins the [SupabaseUsageEvent] classification the transport emits.
 */
class SupabaseUsageLogTest {
    private fun usage(
        resource: String = "Database",
        operation: String = "POST scheduler_snapshot",
        reqBytes: Long = 100,
        respBytes: Long = 200,
        status: Int = 200,
        time: Long = 1_000,
    ) = SchedulerIntent.RecordSupabaseUsage(resource, operation, reqBytes, respBytes, status, time)

    @Test
    fun record_usage_appends_an_entry() {
        val next = SchedulerReducer.reduce(SchedulerState.empty(), usage())
        assertEquals(1, next.supabaseUsageLog.size)
        assertEquals(
            SupabaseUsageEntry(1_000, "Database", "POST scheduler_snapshot", 100, 200, 200),
            next.supabaseUsageLog.single(),
        )
    }

    @Test
    fun keeps_only_the_most_recent_max_entries() {
        // Fill the log past the cap; the OLDEST are dropped and the newest (including the overflow) are kept.
        val full =
            SchedulerState.empty().copy(
                supabaseUsageLog =
                    (0 until SchedulerState.MAX_SUPABASE_USAGE_LOG).map {
                        SupabaseUsageEntry(it.toLong(), "Database", "op$it", 1, 1, 200)
                    },
            )
        val next = SchedulerReducer.reduce(full, usage(operation = "overflow", time = 9_999))
        assertEquals(SchedulerState.MAX_SUPABASE_USAGE_LOG, next.supabaseUsageLog.size)
        // The oldest (op0) rolled off; the overflow one is the newest (last).
        assertTrue(next.supabaseUsageLog.none { it.operation == "op0" })
        assertEquals("overflow", next.supabaseUsageLog.last().operation)
    }

    @Test
    fun log_round_trips_through_the_codec() {
        val entries = listOf(
            SupabaseUsageEntry(1_000, "Auth", "POST token", 80, 640, 200),
            SupabaseUsageEntry(2_000, "Database RPC", "POST rpc/derive_pauses", 40, 120, 200),
        )
        val state = SchedulerState.empty().copy(supabaseUsageLog = entries)
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))
        assertNotNull(decoded)
        assertEquals(entries, decoded.supabaseUsageLog)
    }

    /**
     * Persisted-DB compatibility (CLAUDE.md): a payload written before the usage log existed (no
     * `supabaseUsageLog` field) must still decode, with the log defaulting to empty.
     */
    @Test
    fun legacy_payload_without_the_field_decodes_to_an_empty_log() {
        val legacy = """{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}"""
        val decoded = SchedulerStateCodec.decode(legacy)
        assertNotNull(decoded)
        assertTrue(decoded.supabaseUsageLog.isEmpty())
    }

    @Test
    fun log_is_local_only_and_never_affects_the_sync_fingerprint() {
        val base = SchedulerState.empty()
        val withLog = base.copy(supabaseUsageLog = listOf(SupabaseUsageEntry(1_000, "Database", "op", 1, 1, 200)))
        assertEquals(
            SchedulerStateCodec.syncFingerprint(base),
            SchedulerStateCodec.syncFingerprint(withLog),
        )
    }

    @Test
    fun a_remote_pull_keeps_the_local_log() {
        val local = SchedulerState.empty().copy(supabaseUsageLog = listOf(SupabaseUsageEntry(1_000, "Database", "op", 1, 1, 200)))
        val remote = SchedulerState.empty() // a pulled snapshot ships an empty (neutralized) log
        assertEquals(local.supabaseUsageLog, remote.withLocalViewStateFrom(local).supabaseUsageLog)
    }

    @Test
    fun event_classifies_the_free_plan_resource_bucket() {
        assertEquals("Auth", SupabaseUsageEvent("POST", "/auth/v1/token", 200, 0, 0).resource)
        assertEquals("Database", SupabaseUsageEvent("GET", "/rest/v1/scheduler_snapshot", 200, 0, 0).resource)
        assertEquals("Database RPC", SupabaseUsageEvent("POST", "/rest/v1/rpc/derive_pauses", 200, 0, 0).resource)
    }

    @Test
    fun event_operation_is_a_compact_method_and_table_label() {
        assertEquals("POST scheduler_snapshot", SupabaseUsageEvent("POST", "/rest/v1/scheduler_snapshot", 200, 0, 0).operation)
        assertEquals("POST rpc/derive_pauses", SupabaseUsageEvent("POST", "/rest/v1/rpc/derive_pauses", 200, 0, 0).operation)
        assertEquals("POST token", SupabaseUsageEvent("POST", "/auth/v1/token", 200, 0, 0).operation)
    }
}
