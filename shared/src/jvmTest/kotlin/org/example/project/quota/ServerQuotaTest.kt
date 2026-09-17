package org.example.project.quota

import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/invariants/server-quota.md`: **a heavy month on the user's three accounts uses less than a tenth of every
 * Supabase free-tier quota.**
 *
 * The real project filled its 500 MB database on 2026-09-14 (a multi-MB snapshot row rewritten on every edit) and
 * went down. This test is what should have caught it: it runs the app's real sync and engine code against
 * [FakeSupabase], lets a heavy user work for an hour on a desktop with a phone joining in, and projects what it
 * measured to a month of [FreeTierQuota.ACTIVE_HOURS_PER_DAY]-hour days on [FreeTierQuota.ACCOUNTS] accounts (and a
 * year, for anything the database keeps growing). Every figure is printed, so a failure says which call costs.
 */
class ServerQuotaTest {
    private val t0 = 1_788_343_200_000L
    private val hour = 3_600_000L

    private class Meters(
        val egress: Long,
        val realtime: Long,
        val edge: Long,
        val live: Map<String, Long>,
        val disk: Map<String, Long>,
        val written: Map<String, Long>,
    ) {
        companion object {
            fun of(s: FakeSupabase) =
                Meters(
                    s.egressBytes, s.realtimeMessages, s.edgeInvocations,
                    s.tables().associate { it.name to it.liveBytes },
                    s.tables().associate { it.name to it.diskBytes },
                    s.tables().associate { it.name to it.writtenBytes },
                )
        }
    }

    @Test
    fun a_heavy_month_on_three_accounts_stays_under_a_tenth_of_every_free_tier_quota() = runTest(timeout = 20.minutes) {
        val server = FakeSupabase({ t0 + testScheduler.currentTime }, cronLogRetentionMillis = cronLogRetentionMillis())
        val scenario = QuotaScenario(this, server, QuotaScenario.Millis(t0))
        val desktop = scenario.Device("desktop", "user-quota", QuotaScenario.account(), DeviceKind.Desktop)
        scenario.pass(2 * 60_000L)
        val phone = scenario.Device("phone", "user-quota", SchedulerState.empty(), DeviceKind.Phone)
        scenario.pass(2 * 60_000L)

        val before = Meters.of(server)
        val egressBefore = HashMap(server.egressByCall)
        val requestsBefore = HashMap(server.requestsByCall)
        val realtimeBefore = HashMap(server.realtimeByStream)
        val entitiesBefore = entityKinds(server)
        val tombstonesBefore = tombstoneBytes(server)
        val desktopUser = HeavyUser(desktop, editsPerMinute = 1, seed = 1)
        val phoneUser = HeavyUser(phone, editsPerMinute = 1, seed = 7)
        for (minute in 0 until 60) {
            val now = t0 + testScheduler.currentTime
            desktop.active = minute !in 45..50
            phone.active = minute in 15..30
            if (desktop.active) desktopUser.actOneMinute(now)
            if (phone.active && minute % 3 == 0) phoneUser.actOneMinute(now)
            scenario.pass(60_000L)
        }
        val after = Meters.of(server)

        // ----- projection -------------------------------------------------------------------------------------
        val q = FreeTierQuota
        val activeHoursPerMonth = q.ACTIVE_HOURS_PER_DAY.toLong() * q.DAYS_PER_MONTH
        val monthlyEgress = (after.egress - before.egress) * activeHoursPerMonth * q.ACCOUNTS
        val monthlyRealtime = (after.realtime - before.realtime) * activeHoursPerMonth * q.ACCOUNTS
        val monthlyEdge = (after.edge - before.edge) * activeHoursPerMonth * q.ACCOUNTS

        val sharedTables = setOf("cron.job_run_details")
        val report = StringBuilder("\nSERVER QUOTA — heavy hour projected to ${q.ACCOUNTS} accounts\n")
        var database = 0L
        for (name in after.disk.keys) {
            val growthPerHour = (after.live.getValue(name) - before.live.getValue(name)).coerceAtLeast(0)
            val shared = name in sharedTables
            // The cron log grows around the clock; everything else grows only while somebody uses the app.
            val hoursProjected = if (shared) 24L * q.DAYS_PROJECTED else q.ACTIVE_HOURS_PER_DAY.toLong() * q.DAYS_PROJECTED
            val retainedHours = if (shared) server.cronLogRetentionMillis?.let { it / hour + 1 } else null
            var perAccount = after.disk.getValue(name) + growthPerHour * minOf(hoursProjected, retainedHours ?: hoursProjected)
            if (name == "scheduler_entity") {
                // Tombstones grow at the pace of editing, but `purge_scheduler_tombstones` deletes them after a week.
                val tombstonesPerHour = (tombstoneBytes(server) - tombstonesBefore).coerceAtLeast(0)
                val keptHours = tombstoneRetentionMillis()?.let { it / (24 * hour) * q.ACTIVE_HOURS_PER_DAY } ?: hoursProjected
                perAccount = after.disk.getValue(name) + (growthPerHour - tombstonesPerHour).coerceAtLeast(0) * hoursProjected +
                    tombstonesPerHour * minOf(hoursProjected, keptHours)
            }
            if (name == "history_unit") {
                // `trim_history_units` keeps the newest [FakeSupabase.HISTORY_UNITS_KEPT] of each category; twice
                // that, for the dead versions the trim's own deletes leave behind.
                val table = server.table(name)
                val averageRow = if (table.rows.isEmpty()) 0L else table.liveBytes / table.rows.size
                perAccount = minOf(perAccount, 2 * averageRow * FakeSupabase.HISTORY_UNITS_KEPT * HistoryCategory.entries.size)
            }
            val projected = perAccount * if (shared) 1 else q.ACCOUNTS
            database += projected
            if (after.disk.getValue(name) > 0) {
                report.append(
                    "  table %-24s live %9s  disk %9s  written/h %9s  → a year: %9s\n".format(
                        name, mb(after.live.getValue(name)), mb(after.disk.getValue(name)),
                        mb(after.written.getValue(name) - before.written.getValue(name)), mb(projected),
                    ),
                )
            }
        }
        fun hour(now: Map<String, Long>, then: Map<String, Long>) = now.mapValues { (k, v) -> v - (then[k] ?: 0L) }.filterValues { it > 0 }
        report.append("  egress by call (the hour): ${hour(server.egressByCall, egressBefore).entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} ${mb(it.value)}" }}\n")
        report.append("  requests by call (the hour): ${hour(server.requestsByCall, requestsBefore).entries.sortedByDescending { it.value }.take(10).joinToString { "${it.key} ${it.value}" }}\n")
        report.append("  realtime by stream (the hour): ${hour(server.realtimeByStream, realtimeBefore)}\n")
        report.append(
            "  entity rows by kind: before $entitiesBefore, after ${entityKinds(server)}\n",
        )
        report.append("  largest request body ${mb(server.largestRequestBodyBytes)}, largest realtime message ${mb(server.largestRealtimeMessageBytes)}\n")

        val failures = ArrayList<String>()
        fun check(what: String, value: Long, limit: Long, unit: (Long) -> String = { it.toString() }) {
            val budget = q.budget(limit)
            val line = "  %-36s %12s of budget %12s (limit %s)".format(what, unit(value), unit(budget), unit(limit))
            report.append(line).append(if (value > budget) "  ✗\n" else "  ✓\n")
            if (value > budget) failures += line.trim()
        }
        check("database size (a year)", database, q.DATABASE_BYTES, ::mb)
        check("egress / month", monthlyEgress, q.EGRESS_BYTES_PER_MONTH, ::mb)
        check("realtime messages / month", monthlyRealtime, q.REALTIME_MESSAGES_PER_MONTH)
        check("largest realtime message", server.largestRealtimeMessageBytes, q.REALTIME_MESSAGE_MAX_BYTES, ::mb)
        check("peak realtime connections", server.peakConnections.toLong() * q.ACCOUNTS, q.REALTIME_PEAK_CONNECTIONS)
        check("edge invocations / month", monthlyEdge, q.EDGE_INVOCATIONS_PER_MONTH)
        println(report)
        if (failures.isNotEmpty()) fail("over a tenth of the free tier:\n" + failures.joinToString("\n") + "\n" + report)
    }

    /** How long `pause-cue-setup.sql` keeps pg_cron's run log, read off its purge job; null when it never purges. */
    private fun cronLogRetentionMillis(): Long? {
        val sql = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "supabase/pause-cue-setup.sql") }.firstOrNull { it.isFile }?.readText() ?: return null
        val days = Regex("""delete from cron\.job_run_details where end_time < now\(\) - interval '(\d+) days?'""").find(sql)?.groupValues?.get(1) ?: return null
        return days.toLong() * 24 * hour
    }

    /**
     * How long the server keeps an entity tombstone: the age `purge_scheduler_tombstones` (the migrations) deletes at,
     * provided `pause-cue-setup.sql` schedules it; null when nothing purges them.
     */
    private fun tombstoneRetentionMillis(): Long? {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.firstOrNull { java.io.File(it, "supabase/pause-cue-setup.sql").isFile } ?: return null
        if ("public.purge_scheduler_tombstones()" !in java.io.File(root, "supabase/pause-cue-setup.sql").readText()) return null
        val migrations = java.io.File(root, "supabase/migrations").listFiles().orEmpty().sortedBy { it.name }.joinToString("\n") { it.readText() }
        val days = Regex("""where deleted and updated_at < now\(\) - interval '(\d+) days?'""").findAll(migrations).lastOrNull()?.groupValues?.get(1) ?: return null
        return days.toLong() * 24 * hour
    }

    private fun tombstoneBytes(server: FakeSupabase): Long =
        server.table("scheduler_entity").rows.values.filter { it["deleted"].toString() == "true" }.sumOf { server.table("scheduler_entity").rowBytes(it) }

    private fun entityKinds(server: FakeSupabase): Map<String, String> =
        server.table("scheduler_entity").rows.values
            .groupBy { it["kind"].toString() + if (it["deleted"].toString() == "true") " (deleted)" else "" }
            .mapValues { (_, rows) -> "${rows.size} rows ${rows.sumOf { it.toString().length.toLong() } / 1024} KB" }

    private fun mb(bytes: Long): String = "%.2f MB".format(bytes / (1024.0 * 1024.0))
}
