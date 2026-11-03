package org.example.project.scheduler.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.state.SupabaseUsageEntry

/**
 * `docs/invariants/server-quota.md` § *The budget*: **the app never uses more than a tenth of any Supabase free-tier
 * quota, on any project** (user rule 2026-11-03: *"the app must not exceeds 10% of any usage limits on any project"*).
 * The free tier's figures (read 2026-09-17) and the tenth of each, for the app to show and to hold to; `ServerQuotaTest`
 * measures the same budget.
 */
object ServerBudget {
    const val MB: Long = 1_000_000L

    /** The share of a free-tier quota the app may use. A limit set lower is the user's; never higher. */
    const val FRACTION: Double = 0.10

    const val DATABASE_BYTES: Long = 500 * MB
    const val EGRESS_BYTES_PER_MONTH: Long = 5_120 * MB
    const val REALTIME_MESSAGES_PER_MONTH: Long = 2_000_000L
    const val EDGE_INVOCATIONS_PER_MONTH: Long = 500_000L

    fun budget(quota: Long): Long = (quota * FRACTION).toLong()

    /** The database budget: the most a project's database may hold, and what the server limit field is clamped to. */
    val databaseBudgetBytes: Long get() = budget(DATABASE_BYTES)

    /** [requested] kept between a megabyte and [databaseBudgetBytes]: a limit is never set above the tenth. */
    fun serverLimitOf(requested: Long): Long = requested.coerceIn(MB, databaseBudgetBytes)

    /** What a device's own database may hold before it forgets, by default: room for months of history. */
    const val DEFAULT_DEVICE_LIMIT_BYTES: Long = 500 * MB

    fun deviceLimitOf(requested: Long): Long = requested.coerceAtLeast(10 * MB)
}

/**
 * The project's usage as the server measured it (`project_usage`, migration 20261103000000), at [readAtMillis]: the whole
 * project's database, and this account's own rows in it.
 */
data class ProjectUsage(val databaseBytes: Long, val accountBytes: Long, val readAtMillis: Long) : ProjectUsageAnswer

/** What a press of the Settings page's Refresh got: the [ProjectUsage], or why it got none ([ProjectUsageMiss]). */
sealed interface ProjectUsageAnswer

/**
 * Why a press of Refresh read nothing, as the Settings page says it: each cause its own sentence, so the user knows
 * what to do (2026-11-04: one sentence for all three left the user guessing whether to deploy). Working offline is
 * not one of them: the press is sent all the same.
 */
enum class ProjectUsageMiss(val said: String) : ProjectUsageAnswer {
    SignedOut("not read: no account is signed in on this device"),
    NotDeployed("not read: the server has no project_usage yet (scripts\\deploy-supabase.bat)"),
    Unreachable("not read: the server did not answer"),
}

/**
 * What this device's logged calls drew this calendar month ([SupabaseUsageEntry]): the bytes sent and received, which is
 * the egress the app can see, and how many calls. A lower bound: the log keeps its most recent entries only, and other
 * devices draw on the same project.
 */
data class DeviceMonthUsage(val bytes: Long, val calls: Int, val sinceMillis: Long)

fun deviceMonthUsage(log: List<SupabaseUsageEntry>, nowMillis: Long, timeZone: TimeZone): DeviceMonthUsage {
    val today = kotlin.time.Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone)
    val monthStart = LocalDateTime(today.year, today.month, 1, 0, 0).toInstant(timeZone).toEpochMilliseconds()
    val month = log.filter { it.timeMillis >= monthStart }
    return DeviceMonthUsage(month.sumOf { it.requestBytes + it.responseBytes }, month.size, monthStart)
}
