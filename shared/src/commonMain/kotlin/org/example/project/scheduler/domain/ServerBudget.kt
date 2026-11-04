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

    /**
     * User rule 2026-11-04: **where to see what the app cannot read** — the whole project's egress, Realtime messages and
     * Edge Function calls. Supabase's Management API gives none of them over the billing period (checked 2026-11-04:
     * `usage.api-counts` counts requests by kind over a week at most), so no Edge Function could fetch them; the
     * dashboard's Usage page shows them all. The address Supabase's own docs link to: `_` is the signed-in user's
     * organization, and the page picks the project.
     */
    const val SUPABASE_USAGE_PAGE: String = "https://supabase.com/dashboard/org/_/usage"

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

/**
 * User rule 2026-11-04 (docs/SERVER_SETUP.md): **whether the server this device syncs with has every migration this
 * build needs** — each user runs their own Supabase project, and every update that brings a migration needs
 * `scripts\deploy-supabase.bat` run against it. Asked once per start and when the device goes back online.
 */
sealed interface ServerVersion {
    /** It has [REQUIRED_SERVER_MIGRATION] or later. */
    data object UpToDate : ServerVersion

    /** It is behind: its latest migration is [has] (blank: none the app can read). */
    data class Behind(val has: String) : ServerVersion

    /** It did not answer (offline, unreachable): nothing is known. */
    data object Unknown : ServerVersion

    companion object {
        /**
         * The latest migration this build needs, by its file's timestamp (`supabase/migrations/<it>_….sql`).
         * `ServerVersionTest` keeps it the newest migration's.
         */
        const val REQUIRED_SERVER_MIGRATION: String = "20261104010000"

        /** What the server's [latest] migration says of it. Timestamps of one length compare as text. */
        fun of(latest: String): ServerVersion =
            if (latest.isNotBlank() && latest >= REQUIRED_SERVER_MIGRATION) UpToDate else Behind(latest.trim())
    }
}

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

    /** Another server was chosen on this device: it is read from the next start (docs/SERVER_SETUP.md). */
    ServerChanged("not read: restart the app to use the server chosen"),
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
