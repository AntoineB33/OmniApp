package org.example.project.quota

/**
 * `docs/invariants/server-quota.md`: **the Supabase free plan's quotas** (supabase.com/pricing, read 2026-09-17),
 * and the share of each the app may plan to use.
 *
 * The app must not come even NEAR a quota on a real server (user rule, 2026-09-17), so every budget is
 * [BUDGET_SHARE] of its limit, for [ACCOUNTS] accounts each used heavily on a desktop and a phone.
 */
object FreeTierQuota {
    const val DATABASE_BYTES: Long = 500L * 1024 * 1024
    const val EGRESS_BYTES_PER_MONTH: Long = 5L * 1024 * 1024 * 1024
    const val REALTIME_MESSAGES_PER_MONTH: Long = 2_000_000
    const val REALTIME_MESSAGE_MAX_BYTES: Long = 256L * 1024
    const val REALTIME_PEAK_CONNECTIONS: Long = 200
    const val EDGE_INVOCATIONS_PER_MONTH: Long = 500_000

    /** The share of every quota the app may use. */
    const val BUDGET_SHARE: Double = 0.10

    /** How many accounts the budget is planned for (the user's three, each on a desktop and a phone). */
    const val ACCOUNTS: Int = 3

    /** Hours of use a day, per account, in the planned month — a heavy day. */
    const val ACTIVE_HOURS_PER_DAY: Int = 10

    const val DAYS_PER_MONTH: Int = 30

    /** How far ahead the database size is projected: a year of growth for anything that is not bounded. */
    const val DAYS_PROJECTED: Int = 365

    fun budget(limit: Long): Long = (limit * BUDGET_SHARE).toLong()
}
