package org.example.project.quota

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `docs/invariants/server-quota.md`: **an in-memory Supabase that counts what the real one would bill.**
 *
 * It answers the app's real HTTP calls — PostgREST reads and writes on any table, the RPCs, the Edge Functions,
 * GoTrue — through a Ktor [MockEngine], so the code under test is the app's own `RemoteSnapshotClient` and sync
 * engine, byte for byte. The production triggers that change what is stored are emulated too (migration
 * 20260917000000): the row revision sequence, the account's head row, the history trim. Around that it meters the
 * free-tier quotas ([FreeTierQuota]):
 *
 * - **Database size** — per table, the Postgres footprint: live rows **plus the dead versions every UPDATE and
 *   DELETE leaves behind until autovacuum reclaims them**. Reclaimed space is reused, never returned, so a table's
 *   disk size is the HIGH-WATER MARK of that footprint ([PgTable.diskBytes]). That is the mechanism that filled the
 *   real project on 2026-09-14: a multi-MB row rewritten on every edit.
 * - **Egress** — every response body, and every Realtime message delivered.
 * - **Realtime** — every `postgres_changes` event delivered to each subscribed device (with the OLD row too where
 *   the table is `replica identity full`), every broadcast delivered to each peer, the largest single message, and
 *   the peak number of connected devices.
 * - **Edge Function invocations**.
 *
 * Rows are sized as their JSON text plus a tuple header, never compressed: every estimate errs toward the quota.
 */
class FakeSupabase(private val clock: () -> Long, val cronLogRetentionMillis: Long? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    // ----- the storage model -------------------------------------------------------------------------------------

    /**
     * One Postgres table. [key] names the primary-key columns; [realtime] puts it in the `supabase_realtime`
     * publication; [replicaIdentityFull] makes every UPDATE/DELETE event carry the old row.
     */
    inner class PgTable(val name: String, val key: List<String>, val realtime: Boolean = false, val replicaIdentityFull: Boolean = false) {
        val rows = LinkedHashMap<String, JsonObject>()
        var liveBytes = 0L
            private set
        private var deadBytes = 0L
        private var deadTuples = 0L
        private var lastVacuumMillis = Long.MIN_VALUE / 2

        /** The high-water mark of live + not-yet-vacuumed dead bytes: what the table occupies on disk. */
        var diskBytes = 0L
            private set
        var writtenBytes = 0L
            private set
        var writes = 0L
            private set

        fun rowBytes(row: JsonObject): Long = row.toString().length + TUPLE_OVERHEAD_BYTES

        private fun keyOf(row: JsonObject): String = key.joinToString("|") { row[it]?.let(::scalar) ?: "" }

        fun upsert(row: JsonObject, userId: String?): JsonObject {
            val k = keyOf(row)
            val old = rows[k]
            val merged = stamped(if (old == null) row else JsonObject(old + row))
            if (old != null) retire(old)
            rows[k] = merged
            liveBytes += rowBytes(merged)
            written(merged)
            changed(userId, old, merged)
            return merged
        }

        fun insert(row: JsonObject, userId: String?): Boolean {
            if (rows.containsKey(keyOf(row))) return false
            upsert(row, userId)
            return true
        }

        fun update(filter: (JsonObject) -> Boolean, patch: JsonObject, userId: String?): List<JsonObject> =
            rows.entries.filter { filter(it.value) }.map { (k, old) ->
                retire(old)
                val merged = stamped(JsonObject(old + patch))
                rows[k] = merged
                liveBytes += rowBytes(merged)
                written(merged)
                changed(userId, old, merged)
                merged
            }

        fun delete(filter: (JsonObject) -> Boolean, userId: String?): Int {
            val hits = rows.entries.filter { filter(it.value) }.map { it.key }
            for (k in hits) {
                val old = rows.remove(k)!!
                retire(old)
                writes++
                changed(userId, old, null)
            }
            return hits.size
        }

        /** `bump_scheduler_revision`: every write of a revisioned row takes the next value of the sequence. */
        private fun stamped(row: JsonObject): JsonObject =
            if (name in REVISIONED_TABLES) JsonObject(row + ("revision" to JsonPrimitive(++revisionSeq))) else row

        private fun retire(old: JsonObject) {
            liveBytes -= rowBytes(old)
            deadBytes += rowBytes(old)
            deadTuples++
            track()
        }

        private fun written(row: JsonObject) {
            writes++
            writtenBytes += rowBytes(row)
            track()
        }

        private fun track() {
            diskBytes = maxOf(diskBytes, liveBytes + deadBytes)
            // Autovacuum: the launcher looks at most once per naptime, and a table qualifies once its dead tuples
            // pass `50 + 0.2 × live tuples`. What it reclaims is reused by later writes, never given back.
            val now = clock()
            if (now - lastVacuumMillis >= AUTOVACUUM_NAPTIME_MILLIS && deadTuples > 50 + 0.2 * rows.size) {
                deadBytes = 0
                deadTuples = 0
                lastVacuumMillis = now
            }
        }

        private fun changed(userId: String?, old: JsonObject?, new: JsonObject?) {
            if (!realtime || userId == null) return
            val body = buildJsonObject {
                put("table", name)
                put("type", if (old == null) "INSERT" else if (new == null) "DELETE" else "UPDATE")
                if (new != null) put("record", new)
                if (old != null && replicaIdentityFull) put("old_record", old)
            }.toString()
            deliverRealtime(userId, name, body)
        }
    }

    private var revisionSeq = 0L
    private val tables = LinkedHashMap<String, PgTable>()

    private fun table(name: String, key: List<String>, realtime: Boolean = false, replicaIdentityFull: Boolean = false): PgTable =
        tables.getOrPut(name) { PgTable(name, key, realtime, replicaIdentityFull) }

    fun tables(): Collection<PgTable> = tables.values

    fun table(name: String): PgTable = tables.getValue(name)

    init {
        // The production schema (supabase/migrations): primary keys, and what is in the Realtime publication.
        table("scheduler_snapshot", listOf("user_id"))
        table("scheduler_entity", listOf("user_id", "kind", "entity_id"))
        table("history_unit", listOf("user_id", "device_id", "category", "device_seq"))
        table("scheduler_head", listOf("user_id"), realtime = true)
        table("account_logout", listOf("user_id"))
        table("account_last_phone", listOf("user_id"))
        table("device_push_token", listOf("user_id", "device_id"))
        table("account_state", listOf("user_id"))
        table("device_active_session", listOf("user_id", "device_id", "start_ms"))
        table("device_heartbeat", listOf("user_id", "device_id"))
        table("data_payload_sent", listOf("user_id"))
        table("device_break", listOf("user_id"))
        table("screen_break_rule", listOf("user_id", "break_kind", "start_ms"))
        table("device_away", listOf("user_id", "device_id"))
        // pg_cron logs every run of every job here; `pause-cue-setup.sql` purges it ([cronLogRetentionMillis]).
        table("cron.job_run_details", listOf("runid"))
    }

    // ----- the meters ---------------------------------------------------------------------------------------------

    var egressBytes = 0L
        private set
    var requests = 0L
        private set
    var edgeInvocations = 0L
        private set
    var realtimeMessages = 0L
        private set
    var largestRealtimeMessageBytes = 0L
        private set
    var largestRequestBodyBytes = 0L
        private set

    /** Per table or channel, how many Realtime messages were delivered — to say WHICH stream costs. */
    val realtimeByStream = LinkedHashMap<String, Long>()

    /** Per `METHOD path`, response bytes — to say WHICH call costs. */
    val egressByCall = LinkedHashMap<String, Long>()

    /**
     * When it answers true for a call (`METHOD path`), that call is applied and then its connection drops: the client
     * gets an exception instead of the answer — the lost acknowledgement.
     */
    var dropAnswer: (String) -> Boolean = { false }

    /** Per `METHOD path`, how many requests. */
    val requestsByCall = LinkedHashMap<String, Long>()

    private var cronRuns = 0L
    private var lastCronMillis: Long? = null

    /** pg_cron's `pause-cue-tick` runs once a minute whatever the clients do; its log row is the server's own growth. */
    fun runCronUntil(nowMillis: Long) {
        var last = lastCronMillis ?: nowMillis
        val log = table("cron.job_run_details")
        while (nowMillis - last >= 60_000L) {
            last += 60_000L
            cronRuns++
            log.insert(
                buildJsonObject {
                    put("runid", cronRuns)
                    put("jobid", 1)
                    put("job_pid", 12345)
                    put("database", "postgres")
                    put("username", "postgres")
                    put("command", "select public.tick_pause_cues()")
                    put("status", "succeeded")
                    put("return_message", "1 row")
                    put("start_time", "2026-09-17 00:00:00.000000+00")
                    put("end_time", "2026-09-17 00:00:00.004000+00")
                    put("end_ms", last)
                },
                null,
            )
            cronLogRetentionMillis?.let { keep ->
                log.delete({ (it["end_ms"]?.let(::scalar)?.toLongOrNull() ?: 0L) < last - keep }, null)
            }
        }
        lastCronMillis = last
    }

    // ----- Realtime ---------------------------------------------------------------------------------------------

    private class Subscriber(val deviceId: String, val userId: String, val table: String, val onChange: (String) -> Unit)

    private class Peer(val deviceId: String, val userId: String, val onMessage: (String) -> Unit)

    private val subscribers = ArrayList<Subscriber>()
    private val peers = ArrayList<Peer>()
    private val connectedDevices = HashSet<String>()
    var peakConnections = 0
        private set

    fun subscribePostgresChanges(deviceId: String, userId: String, table: String, onChange: (String) -> Unit) {
        subscribers.removeAll { it.deviceId == deviceId && it.table == table }
        subscribers += Subscriber(deviceId, userId, table, onChange)
        connect(deviceId)
    }

    fun joinBroadcast(deviceId: String, userId: String, onMessage: (String) -> Unit) {
        peers.removeAll { it.deviceId == deviceId }
        peers += Peer(deviceId, userId, onMessage)
        connect(deviceId)
    }

    private fun connect(deviceId: String) {
        connectedDevices += deviceId
        peakConnections = maxOf(peakConnections, connectedDevices.size)
    }

    fun broadcast(fromDevice: String, userId: String, text: String) {
        for (p in peers.toList()) {
            if (p.userId != userId || p.deviceId == fromDevice) continue
            countRealtime("broadcast:" + (Regex("\"type\":\"([a-z_]+)\"").find(text)?.groupValues?.get(1) ?: "?"), text.length.toLong())
            p.onMessage(text)
        }
    }

    private fun deliverRealtime(userId: String, table: String, body: String) {
        for (s in subscribers.toList()) {
            if (s.userId != userId || s.table != table) continue
            countRealtime("postgres_changes:$table", body.length.toLong())
            s.onChange(body)
        }
    }

    private fun countRealtime(stream: String, bytes: Long) {
        realtimeMessages++
        realtimeByStream[stream] = (realtimeByStream[stream] ?: 0L) + 1
        largestRealtimeMessageBytes = maxOf(largestRealtimeMessageBytes, bytes)
        egressBytes += bytes
    }

    // ----- HTTP -------------------------------------------------------------------------------------------------

    private var nextUser = 0
    private val userByEmail = HashMap<String, String>()
    private val userByToken = HashMap<String, String>()

    /** A token for [userId], as a device restored from its saved session already holds. */
    fun tokenFor(userId: String): String = "token-$userId".also { userByToken[it] = userId }

    /**
     * The engine one device's `HttpClient` runs on — on [dispatcher], the test's own, so a request is answered in
     * virtual time: on real IO threads a slow body lets the simulated clock run ahead of the traffic it drives.
     */
    fun engine(dispatcher: CoroutineDispatcher): MockEngine =
        MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request -> handle(request) }
            },
        )

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) =
        run {
            requests++
            val path = request.url.encodedPath
            val body =
                when (val content = request.body) {
                    is TextContent -> content.text
                    is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                    else -> ""
                }
            largestRequestBodyBytes = maxOf(largestRequestBodyBytes, body.length.toLong())
            val (status, text) = route(request, path, body)
            val bytes = text.length + RESPONSE_OVERHEAD_BYTES
            egressBytes += bytes
            val call = "${request.method.value} ${path.substringAfter("/v1/")}"
            egressByCall[call] = (egressByCall[call] ?: 0L) + bytes
            requestsByCall[call] = (requestsByCall[call] ?: 0L) + 1
            if (dropAnswer(call)) throw java.io.IOException("connection reset")
            respond(text, status, headersOf("Content-Type", "application/json"))
        }

    private fun route(request: HttpRequestData, path: String, body: String): Pair<HttpStatusCode, String> {
        if (path.startsWith("/auth/v1")) return auth(request, path, body)
        val user =
            request.headers["Authorization"]?.removePrefix("Bearer ")?.let(userByToken::get)
                ?: return HttpStatusCode.Unauthorized to """{"message":"JWT expired"}"""
        if (path.startsWith("/functions/v1/")) {
            edgeInvocations++
            return HttpStatusCode.OK to "{}"
        }
        val rest = path.removePrefix("/rest/v1/")
        if (rest.startsWith("rpc/")) return rpc(rest.removePrefix("rpc/"), user, body)
        val table = tables[rest] ?: return HttpStatusCode.NotFound to """{"message":"relation $rest does not exist"}"""
        val filter = filterOf(request, user)
        val prefer = request.headers["Prefer"].orEmpty()
        val result =
            when (request.method) {
                HttpMethod.Get -> {
                    var hits = table.rows.values.filter(filter)
                    request.url.parameters["order"]?.let { order ->
                        val column = order.substringBefore('.')
                        val byColumn = compareBy<JsonObject> { it[column]?.let(::scalar)?.toDoubleOrNull() ?: 0.0 }
                        hits = hits.sortedWith(if (order.endsWith(".desc")) byColumn.reversed() else byColumn)
                    }
                    request.url.parameters["limit"]?.toIntOrNull()?.let { hits = hits.take(it) }
                    HttpStatusCode.OK to JsonArray(hits.map { select(it, request) }).toString()
                }
                HttpMethod.Post -> {
                    val incoming = parseRows(body).map { JsonObject(it + ("user_id" to JsonPrimitive(user))) }
                    if (prefer.contains("merge-duplicates")) {
                        incoming.forEach { table.upsert(it, user) }
                        HttpStatusCode.Created to ""
                    } else if (incoming.all { table.insert(it, user) }) {
                        HttpStatusCode.Created to ""
                    } else {
                        HttpStatusCode.Conflict to """{"code":"23505"}"""
                    }
                }
                HttpMethod.Patch -> {
                    val patch = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }
                    val updated = table.update(filter, patch, user)
                    HttpStatusCode.OK to if (prefer.contains("return=representation")) JsonArray(updated).toString() else ""
                }
                HttpMethod.Delete -> {
                    table.delete(filter, user)
                    HttpStatusCode.NoContent to ""
                }
                else -> HttpStatusCode.MethodNotAllowed to "{}"
            }
        if (request.method == HttpMethod.Post || request.method == HttpMethod.Patch) afterWrite(table, user)
        return result
    }

    /** The statement-level triggers of migration 20260917000000. */
    private fun afterWrite(table: PgTable, user: String) {
        when (table.name) {
            "scheduler_entity" -> {
                val own = table.rows.values.filter { scalarOf(it, "user_id") == user }
                val newest = own.maxOfOrNull { scalarOf(it, "revision")?.toLongOrNull() ?: 0L } ?: return
                val head = table("scheduler_head")
                val current = head.rows.values.firstOrNull { scalarOf(it, "user_id") == user }
                if ((current?.let { scalarOf(it, "revision")?.toLongOrNull() } ?: -1L) >= newest) return
                val writer = own.first { (scalarOf(it, "revision")?.toLongOrNull() ?: 0L) == newest }
                head.upsert(
                    buildJsonObject {
                        put("user_id", user)
                        put("revision", newest)
                        put("device_id", scalarOf(writer, "device_id"))
                    },
                    user,
                )
            }
            "history_unit" -> {
                val byCategory = table.rows.values.filter { scalarOf(it, "user_id") == user }.groupBy { scalarOf(it, "category") }
                for ((_, units) in byCategory) {
                    if (units.size <= HISTORY_UNITS_KEPT) continue
                    val evicted =
                        units.sortedWith(
                            compareByDescending<JsonObject> { scalarOf(it, "time_millis")?.toLongOrNull() ?: 0L }
                                .thenByDescending { scalarOf(it, "chrono_id")?.toLongOrNull() ?: 0L },
                        ).drop(HISTORY_UNITS_KEPT).toHashSet()
                    table.delete({ it in evicted }, null)
                }
            }
        }
    }

    private fun auth(request: HttpRequestData, path: String, body: String): Pair<HttpStatusCode, String> {
        val args = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val email = args?.get("email")?.jsonPrimitive?.content
        val refresh = args?.get("refresh_token")?.jsonPrimitive?.content
        val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")?.let(userByToken::get)
        val user =
            when {
                refresh != null -> userByToken[refresh] ?: return HttpStatusCode.BadRequest to """{"error":"refresh token not found"}"""
                email != null -> userByEmail.getOrPut(email) { "user-${nextUser++}" }
                path.endsWith("/user") && bearer != null -> bearer
                else -> "user-${nextUser++}"
            }
        val token = tokenFor(user)
        return HttpStatusCode.OK to
            """{"access_token":"$token","token_type":"bearer","expires_in":3600,"refresh_token":"$token","user":{"id":"$user","email":"${email ?: ""}"},"id":"$user"}"""
    }

    private fun rpc(name: String, user: String, body: String): Pair<HttpStatusCode, String> {
        val args = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        fun str(k: String) = args[k]?.let(::scalar)
        return when (name) {
            "publish_presence" -> {
                table("device_heartbeat").upsert(
                    buildJsonObject {
                        put("user_id", user)
                        put("device_id", str("p_device_id") ?: "?")
                        put("closed", str("p_closed"))
                        put("beat_at", "2026-09-17T00:00:00.000Z")
                    },
                    user,
                )
                table("data_payload_sent").upsert(
                    buildJsonObject {
                        put("user_id", user)
                        put("data_payload_sent", false)
                        put("updated_at", "2026-09-17T00:00:00.000Z")
                    },
                    user,
                )
                HttpStatusCode.OK to "10"
            }
            "publish_next_break" -> {
                table("device_break").upsert(JsonObject(args + ("user_id" to JsonPrimitive(user))), user)
                HttpStatusCode.OK to ""
            }
            "publish_break_rules" -> {
                val rules = table("screen_break_rule")
                rules.delete({ scalarOf(it, "user_id") == user }, user)
                (args["p_rules"] as? JsonArray).orEmpty().forEach { r ->
                    val o = r as? JsonObject ?: return@forEach
                    rules.upsert(
                        buildJsonObject {
                            put("user_id", user)
                            put("break_kind", o["k"]?.let(::scalar))
                            put("start_ms", o["s"]?.let(::scalar))
                            put("end_ms", o["e"]?.let(::scalar))
                        },
                        user,
                    )
                }
                HttpStatusCode.OK to ""
            }
            "sync_device_away" -> {
                if (args["p_away"] != null && args["p_away"] !is JsonNull) {
                    table("device_away").upsert(
                        buildJsonObject {
                            put("user_id", user)
                            put("device_id", str("p_device_id"))
                            put("away", str("p_away"))
                        },
                        user,
                    )
                }
                HttpStatusCode.OK to "false"
            }
            "away_spans" -> HttpStatusCode.OK to "[]"
            else -> HttpStatusCode.OK to ""
        }
    }

    private fun parseRows(body: String): List<JsonObject> =
        when (val e = runCatching { json.parseToJsonElement(body) }.getOrNull()) {
            is JsonArray -> e.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(e)
            else -> emptyList()
        }

    private fun select(row: JsonObject, request: HttpRequestData): JsonObject {
        val cols = request.url.parameters["select"]?.split(",")?.map { it.trim() } ?: return row
        if ("*" in cols) return row
        return JsonObject(row.filterKeys { it in cols })
    }

    /**
     * The PostgREST filters the app uses (`eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `is`), always scoped to the
     * caller's own rows (RLS).
     */
    private fun filterOf(request: HttpRequestData, user: String): (JsonObject) -> Boolean {
        val conditions =
            request.url.parameters.entries()
                .filter { (k, _) -> k !in setOf("select", "order", "limit", "on_conflict", "columns") }
                .flatMap { (k, values) -> values.map { k to it } }
        return { row ->
            scalarOf(row, "user_id") == user &&
                conditions.all { (col, expr) ->
                    val v = scalarOf(row, col)
                    val op = expr.substringBefore('.')
                    val arg = expr.substringAfter('.')
                    fun num(x: String?) = x?.toDoubleOrNull() ?: Double.NaN
                    when (op) {
                        "eq" -> v == arg
                        "neq" -> v != null && v != arg
                        "gt" -> num(v) > num(arg)
                        "gte" -> num(v) >= num(arg)
                        "lt" -> num(v) < num(arg)
                        "lte" -> num(v) <= num(arg)
                        "in" -> v != null && v in arg.removePrefix("(").removeSuffix(")").split(",")
                        "is" ->
                            when (arg) {
                                "null" -> v == null
                                "true" -> v == "true"
                                "false" -> v == "false" || v == null // the boolean columns are `not null default false`
                                else -> true
                            }
                        else -> true
                    }
                }
        }
    }

    private fun scalarOf(row: JsonObject, column: String): String? = row[column]?.let(::scalar)

    private fun scalar(e: JsonElement): String? = (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    companion object {
        /** A heap tuple's header and alignment, per row version. */
        const val TUPLE_OVERHEAD_BYTES = 40L

        /** Headers and framing of one HTTP response. */
        const val RESPONSE_OVERHEAD_BYTES = 300L

        /** `autovacuum_naptime`. */
        const val AUTOVACUUM_NAPTIME_MILLIS = 60_000L

        /** `trim_history_units`: the newest units kept per account and category. */
        const val HISTORY_UNITS_KEPT = 1000

        private val REVISIONED_TABLES = setOf("scheduler_entity", "history_unit")
    }
}
