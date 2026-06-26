package org.example.project.scheduler.sync

/**
 * Connection settings for the Supabase project that backs cross-device sync (PRD §5 Persistence — the
 * offline-first remote mirror). The [anonKey] is the project's public "anon" JWT: it is safe to ship in
 * the client because every row is guarded by Postgres Row-Level Security (a signed-in user can only read
 * and write their own `scheduler_snapshot` row).
 *
 * [baseUrl] is the project root (no `/rest/v1` suffix); the client appends `/auth/v1/...` and
 * `/rest/v1/...` itself.
 */
data class SupabaseConfig(
    val baseUrl: String,
    val anonKey: String,
) {
    /** GoTrue auth endpoint base, e.g. `https://<ref>.supabase.co/auth/v1`. */
    val authUrl: String get() = "${baseUrl.trimEnd('/')}/auth/v1"

    /** PostgREST endpoint base, e.g. `https://<ref>.supabase.co/rest/v1`. */
    val restUrl: String get() = "${baseUrl.trimEnd('/')}/rest/v1"

    companion object {
        /** The OmniApp Supabase project. */
        val DEFAULT =
            SupabaseConfig(
                baseUrl = "https://itoaqbftjemovkzswiyu.supabase.co",
                anonKey =
                    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                        "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Iml0b2FxYmZ0amVtb3ZrenN3aXl1Iiwicm9sZSI6ImFub24i" +
                        "LCJpYXQiOjE3ODI0NzE1ODQsImV4cCI6MjA5ODA0NzU4NH0." +
                        "ruJWPDi4XAbD8brDe_-wVQP1MVTkG9eSvhhae_6dIqs",
            )
    }
}
