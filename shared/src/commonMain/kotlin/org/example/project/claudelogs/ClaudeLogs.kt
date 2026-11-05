package org.example.project.claudelogs

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The Claude Code logs page: what a session log (`<projects>/<project>/<session>.jsonl`, one JSON object per line)
 * holds that the user looks for — the prompts they typed, the answers and the reasoning chains — and the search over
 * them. Read-only: the page never writes into the logs.
 */
@Serializable
enum class ClaudeLogKind(val label: String) {
    Prompt("Prompts"),
    Answer("Answers"),
    Reasoning("Reasoning"),
}

/**
 * One prompt, answer or reasoning chain. [replyTo] is the prompt an answer or a reasoning chain followed, if any;
 * [ordinal] its place among its session's entries.
 */
class ClaudeLogEntry(
    val kind: ClaudeLogKind,
    val text: String,
    val timeMillis: Long?,
    val replyTo: String?,
    val ordinal: Int,
) {
    /** [text] lowercased, once: what the search looks in ([ClaudeLogSearch.matches]) at every keystroke. */
    val searchText: String by lazy { text.lowercase() }
}

/** One session log as read: where it is, what it is about, and what it holds in the order it was written. */
class ClaudeLogSession(
    val path: String,
    val sessionId: String,
    /** The folder the log sits in (one per project), and the working directory the log says the session ran in. */
    val folder: String,
    val cwd: String?,
    /** Claude Code's own title for the session, else null. */
    val title: String?,
    val entries: List<ClaudeLogEntry>,
    /** What the session is filed under: its working directory, else its folder's ([ClaudeLogIndex]), else the folder. */
    val project: String = cwd ?: folder,
    /**
     * Whether its last turn is still going, as the log says ([ClaudeLogParser]): the reply has not ended, or it ended
     * only to wait for a background command. A fact of the log as it stands, never kept in the archive.
     */
    val working: Boolean = false,
) {
    fun inProject(project: String): ClaudeLogSession = ClaudeLogSession(path, sessionId, folder, cwd, title, entries, project, working)
}

/**
 * **What the user reads of a session** (user rule 2026-10-31): each prompt followed by at most ONE reasoning chain and
 * ONE answer. A log holds a text for every step between two tool calls and a thinking block apart: of all that follows
 * a prompt, up to the next one, the LAST text is the answer, and everything else is one reasoning chain — its pieces in
 * order, standing where the first of them stood, so its id does not move while the turn goes on.
 *
 * User rule 2026-11-01: **an answer is what Claude Code ends a turn on**, so a turn still going ([lastTurnOpen]) has none
 * yet: all it wrote so far is its chain, and what it says before it waits for a shell is a step, not the answer.
 *
 * For the reading only ([ClaudeLogCatalog]): the archive and the server keep the entries as the log has them, so the
 * logs kept before this rule read the same.
 */
fun foldedTurns(entries: List<ClaudeLogEntry>, lastTurnOpen: Boolean = false): List<ClaudeLogEntry> {
    val out = ArrayList<ClaudeLogEntry>(entries.size)
    val turn = ArrayList<ClaudeLogEntry>()
    fun closeTurn(open: Boolean = false) {
        val answer = if (open) null else turn.lastOrNull { it.kind == ClaudeLogKind.Answer }
        val chain = turn.filter { it !== answer }
        chain.firstOrNull()?.let { first ->
            out +=
                if (chain.size == 1 && first.kind == ClaudeLogKind.Reasoning) {
                    first
                } else {
                    val text = chain.joinToString("\n\n") { it.text }
                    ClaudeLogEntry(ClaudeLogKind.Reasoning, text, first.timeMillis, first.replyTo, first.ordinal).also { it.searchText }
                }
        }
        if (answer != null) out += answer
        turn.clear()
    }
    for (entry in entries) {
        if (entry.kind == ClaudeLogKind.Prompt) {
            closeTurn()
            out += entry
        } else {
            turn += entry
        }
    }
    closeTurn(open = lastTurnOpen)
    return out
}

/** A session log file as the platform lists it ([listClaudeLogFiles]). */
data class ClaudeLogFile(val path: String, val folder: String, val sizeBytes: Long, val modifiedMillis: Long)

/**
 * **The one reading of a session log.** Lines are pre-filtered on their raw text before any JSON is parsed: most of a
 * log is tool results, attachments and request blobs, often megabytes per line, which hold nothing searched here.
 */
class ClaudeLogParser(private val file: ClaudeLogFile) {
    private val entries = ArrayList<ClaudeLogEntry>()
    private var title: String? = null
    private var summary: String? = null
    private var cwd: String? = null
    private var sessionId: String? = null
    private var lastPrompt: String? = null

    /** Whether the reply last begun has ended: Claude Code logs it, also when it ends only to wait for a command. */
    private var replyEnded = true

    /** The background commands started and not yet told finished or stopped. */
    private val background = HashSet<String>()

    fun accept(line: String) {
        noteWork(line)
        when {
            AI_TITLE in line -> obj(line)?.str("aiTitle")?.let { title = it }
            SUMMARY in line -> obj(line)?.str("summary")?.let { summary = it }
            USER in line && TOOL_RESULT !in line -> obj(line)?.let(::acceptUser)
            ASSISTANT in line && (TEXT in line || THINKING in line) -> obj(line)?.let(::acceptAssistant)
        }
    }

    fun build(): ClaudeLogSession =
        ClaudeLogSession(
            path = file.path,
            sessionId = sessionId ?: file.path.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".jsonl"),
            folder = file.folder,
            cwd = cwd,
            title = title ?: summary,
            // A copy: this parser goes on reading the log ([ClaudeLogTails]) while the session is read elsewhere.
            entries = entries.toList(),
            working = !replyEnded || background.isNotEmpty(),
        )

    /** The log's own marks of a turn going on. Read off the raw line, for the reason [accept] is: most of a log is big. */
    private fun noteWork(line: String) {
        when {
            TURN_ENDED in line -> replyEnded = true
            BACKGROUND_STARTED in line -> BACKGROUND_ID.find(line)?.let { background += it.groupValues[1] }
            TASK_STOPPED in line -> TASK_ID.find(line)?.let { background -= it.groupValues[1] }
        }
        if (TASK_NOTIFICATION in line) NOTIFIED_ID.findAll(line).forEach { background -= it.groupValues[1] }
        // Told a command ended, Claude Code takes the turn up again.
        if (TURN_RESUMED in line) replyEnded = false
    }

    private fun acceptUser(o: JsonObject) {
        if (o.str("type") != "user" || !mainChain(o) || o.bool("isMeta")) return
        // Newer logs say who wrote the line; only the human's are prompts (not a task notification, not an automatic
        // continuation). Older logs do not, so the wrappers Claude Code writes as "user" lines are told by their text.
        val origin = (o["origin"] as? JsonObject)?.str("kind")
        if (origin != null && origin != "human") return
        val message = o["message"] as? JsonObject ?: return
        val text =
            when (val content = message["content"]) {
                is JsonPrimitive -> content.contentOrNull
                is JsonArray -> content.mapNotNull { block -> (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }
                    .joinToString("\n").ifEmpty { null }
                else -> null
            }?.trim()
        if (text.isNullOrEmpty() || (origin == null && NOT_A_PROMPT.any { text.startsWith(it) })) return
        noteContext(o)
        replyEnded = false
        lastPrompt = text
        entries += ClaudeLogEntry(ClaudeLogKind.Prompt, text, timeOf(o), null, entries.size)
    }

    private fun acceptAssistant(o: JsonObject) {
        if (o.str("type") != "assistant" || !mainChain(o)) return
        val content = (o["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        noteContext(o)
        replyEnded = false
        val time = timeOf(o)
        for (block in content) {
            val b = block as? JsonObject ?: continue
            val entry =
                when (b.str("type")) {
                    "text" -> b.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { ClaudeLogEntry(ClaudeLogKind.Answer, it, time, lastPrompt, entries.size) }
                    // A reasoning chain the log kept the text of: most are stored empty (only their signature).
                    "thinking" -> b.str("thinking")?.trim()?.takeIf { it.isNotEmpty() }?.let { ClaudeLogEntry(ClaudeLogKind.Reasoning, it, time, lastPrompt, entries.size) }
                    else -> null
                }
            if (entry != null) entries += entry
        }
    }

    /** A sub-agent's conversation is the agent's, not the user's. */
    private fun mainChain(o: JsonObject) = !o.bool("isSidechain")

    private fun noteContext(o: JsonObject) {
        if (cwd == null) cwd = o.str("cwd")?.takeIf { it.isNotBlank() }
        if (sessionId == null) sessionId = o.str("sessionId")
    }

    private fun timeOf(o: JsonObject): Long? =
        o.str("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        // Unescaped, these only occur as a log's own structure: inside a string they are written `\"type\":…`.
        const val USER = "\"type\":\"user\""
        const val ASSISTANT = "\"type\":\"assistant\""
        const val TOOL_RESULT = "\"type\":\"tool_result\""
        const val TEXT = "\"type\":\"text\""
        const val THINKING = "\"type\":\"thinking\""
        const val AI_TITLE = "\"type\":\"ai-title\""
        const val SUMMARY = "\"type\":\"summary\""
        const val TURN_ENDED = "\"subtype\":\"turn_duration\""
        const val BACKGROUND_STARTED = "\"backgroundTaskId\":\""
        const val TASK_STOPPED = "\"task_type\":\""
        const val TASK_NOTIFICATION = "<task-id>"
        const val TURN_RESUMED = "\"kind\":\"task-notification\""
        val BACKGROUND_ID = Regex("\"backgroundTaskId\":\"([^\"]+)\"")
        val TASK_ID = Regex("\"task_id\":\"([^\"]+)\"")
        val NOTIFIED_ID = Regex("<task-id>([^<]+)</task-id>")
        val NOT_A_PROMPT = listOf("<command-", "<local-command-", "[Request interrupted", "<task-notification>")

        fun obj(line: String): JsonObject? = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull()

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
    }
}

/**
 * Reads session logs through the platform seam, **each from where it was last read**: a log is written by appending and
 * reaches megabytes, and a live one changes every few seconds ([ClaudeLogIndex.changed]). Its parser stays, so a prompt
 * read long ago is still what the next answer replied to and the entries keep their places. A log that grew shorter was
 * written anew, and is read from its start.
 */
class ClaudeLogTails {
    private class Tail(val parser: ClaudeLogParser, var nextByte: Long)

    private val tails = HashMap<String, Tail>()

    fun read(file: ClaudeLogFile): ClaudeLogSession {
        val tail = tails[file.path]?.takeIf { file.sizeBytes >= it.nextByte } ?: Tail(ClaudeLogParser(file), 0L).also { tails[file.path] = it }
        tail.nextByte = forEachClaudeLogLine(file.path, tail.nextByte, tail.parser::accept)
        return tail.parser.build()
    }
}

/**
 * The logs under one directory, kept between searches: a refresh re-reads only the files whose size or date changed
 * since they were read. Refreshed when the user asks (the page opening, the directory set, the Refresh button) —
 * never on a timer.
 */
class ClaudeLogIndex(
    private val list: (String) -> List<ClaudeLogFile>? = ::listClaudeLogFiles,
    private val read: (ClaudeLogFile) -> ClaudeLogSession = ClaudeLogTails()::read,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private var directory: String? = null
    private val cache = HashMap<String, Pair<ClaudeLogFile, ClaudeLogSession>>()

    /**
     * The sessions whose last turn is still going, by [ClaudeLogArchive.sessionName], as of the last read. A log silent
     * for [WORKING_LOG_SILENCE_MILLIS] is no turn going on: a session closed in the middle of one never says it ended,
     * and a log of a build that did not write the end of a reply would lose its last answer.
     */
    var working: Set<String> = emptySet()
        private set

    /**
     * The sessions under [dir], or null when it is not a readable directory. A log that never says where its session
     * ran (one with no prompt) is filed under the working directory its folder's other logs name, so one project is
     * not listed twice.
     */
    fun refresh(dir: String): List<ClaudeLogSession>? = scan(dir)?.first

    /** The sessions of the logs written since [dir] was last read; null when it is not a readable directory. */
    fun changed(dir: String): List<ClaudeLogSession>? =
        scan(dir)?.let { (sessions, changed) -> sessions.filter { it.path in changed } }

    /** Every session under [dir], and the paths of those read this time. */
    private fun scan(dir: String): Pair<List<ClaudeLogSession>, Set<String>>? {
        if (dir != directory) {
            cache.clear()
            directory = dir
        }
        val files = list(dir) ?: return null
        val seen = files.mapTo(HashSet()) { it.path }
        cache.keys.retainAll(seen)
        val changed = HashSet<String>()
        val sessions =
            files.map { file ->
                val known = cache[file.path]
                if (known != null && known.first == file) {
                    known.second
                } else {
                    // Lowercased here, off the UI thread, rather than at the first keystroke that searches it.
                    changed += file.path
                    read(file).also { session -> session.entries.forEach { it.searchText }; cache[file.path] = file to session }
                }
            }
        val folderCwd = HashMap<String, String>()
        for (s in sessions) if (s.cwd != null) folderCwd.getOrPut(s.folder) { s.cwd }
        val written = files.associate { it.path to it.modifiedMillis }
        val now = nowMillis()
        working =
            sessions.filterTo(ArrayList()) { it.working && now - (written[it.path] ?: 0L) < WORKING_LOG_SILENCE_MILLIS }
                .mapTo(HashSet(), ClaudeLogArchive::sessionName)
        return sessions.map { s -> if (s.cwd == null) folderCwd[s.folder]?.let(s::inProject) ?: s else s } to changed
    }

    private companion object {
        /** As long as the longest wait for a command that still tells of its end: the screen guard's own six hours. */
        const val WORKING_LOG_SILENCE_MILLIS: Long = 6 * 60 * 60_000L
    }
}

/**
 * What the Claude Code logs keep on this device: the directory the user defined. Local-only view state, never synced and
 * never a History Unit (CLAUDE.md, *State*), on the placement row [PLACEMENT_ID]. How the logs are searched is a Search
 * window's configuration, kept like every Search window's.
 */
@Serializable
data class ClaudeLogsSettings(
    val directory: String = "",
    /**
     * Whether the folder Claude Code keeps its logs in ([defaultClaudeLogsDirectory]) was already taken as the directory,
     * the first time the page was shown with none defined. Once: a directory the user empties stays empty.
     */
    val defaultAdopted: Boolean = false,
) {
    companion object {
        /**
         * What Tab writes in the directory field holding [draft]: the [suggestion] it is the start of (an empty field
         * is the start of any), else null — and Tab then moves on, as in any other field.
         */
        fun directoryCompletion(draft: String, suggestion: String?): String? =
            suggestion?.takeIf { it != draft && it.startsWith(draft, ignoreCase = true) }

        const val PLACEMENT_ID: String = "ClaudeLogs"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(settings: ClaudeLogsSettings): String = JSON.encodeToString(serializer(), settings)

        /** Whatever an older or newer build wrote (the first build kept its own search there too): what can be read of it. */
        fun decode(text: String?): ClaudeLogsSettings =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: ClaudeLogsSettings()
    }
}

/**
 * **The logs the account keeps** ([ClaudeLogKeeper]: what the folder gave, kept after it is gone, and what the server
 * sent), which the Search window lists ([org.example.project.scheduler.domain.SearchDomain]'s Claude kinds) the way it
 * lists `App`'s windows: handed in, not held by the state. [sessions] is null until the archive is loaded ([loading]).
 */
class ClaudeLogCatalog(
    val directory: String,
    val sessions: List<ClaudeLogSession>?,
    val loading: Boolean = false,
    /** Where Claude Code keeps its logs on this device, which the directory field suggests ([defaultClaudeLogsDirectory]). */
    val suggestion: String? = null,
    /** Whether [directory] could be read the last time it was asked; the kept logs are shown either way. */
    val folderReadable: Boolean = true,
    /** The sessions whose last turn is going on ([ClaudeLogIndex.working]): it has no answer yet ([foldedTurns]). */
    val working: Set<String> = emptySet(),
) {
    /** Every entry with its session, in the order the logs hold them. */
    val entries: List<Pair<ClaudeLogSession, ClaudeLogEntry>> by lazy {
        sessions.orEmpty().flatMap { session ->
            foldedTurns(session.entries, lastTurnOpen = ClaudeLogArchive.sessionName(session) in working).map { session to it }
        }
    }

    private val byId: Map<String, Pair<ClaudeLogSession, ClaudeLogEntry>> by lazy {
        entries.associateBy { (session, entry) -> idOf(session, entry) }
    }

    private val memos = HashMap<Any, Any?>()

    /**
     * What [compute] answers for [key], computed once for these logs: what the Search window derives from them that does
     * not change from one keystroke to the next (the rows of a kind, before the query filters them).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> memo(key: Any, compute: () -> T): T = memos.getOrPut(key) { compute() } as T

    /** The entry a Search row's id names, or null for one no longer in the logs. */
    fun find(id: String): Pair<ClaudeLogSession, ClaudeLogEntry>? = byId[id]

    /**
     * User rule 2026-11-05: **what follows an entry in its turn is its child** — a prompt's are the reasoning chain and the
     * answer of its turn ([foldedTurns]), in that order; a reasoning chain's is the answer it led to; an answer has none. By
     * id ([idOf]), read off the logs as they stand: derived, never stored nor synced.
     */
    fun childrenOf(id: String): List<Pair<ClaudeLogKind, String>> = turnChildren[id].orEmpty()

    private val turnChildren: Map<String, List<Pair<ClaudeLogKind, String>>> by lazy {
        val out = HashMap<String, List<Pair<ClaudeLogKind, String>>>()
        var prompt: String? = null
        var reasoning: String? = null
        var session: ClaudeLogSession? = null
        for ((s, entry) in entries) {
            if (s !== session) {
                session = s
                prompt = null
                reasoning = null
            }
            val id = idOf(s, entry)
            when (entry.kind) {
                ClaudeLogKind.Prompt -> {
                    prompt = id
                    reasoning = null
                }
                ClaudeLogKind.Reasoning -> {
                    prompt?.let { out[it] = out[it].orEmpty() + (entry.kind to id) }
                    reasoning = id
                }
                ClaudeLogKind.Answer -> {
                    prompt?.let { out[it] = out[it].orEmpty() + (entry.kind to id) }
                    reasoning?.let { out[it] = out[it].orEmpty() + (entry.kind to id) }
                }
            }
        }
        out
    }

    /** Every project the sessions are filed under, for the project filter. */
    val projects: List<String> by lazy { sessions.orEmpty().map { it.project }.distinct().sorted() }

    /** Where the reading stands, in words: what is kept, and why the folder added nothing where it did not. */
    val status: String
        get() {
            val kept = sessions?.let { "${it.size} sessions, ${entries.size} entries" }
            val folder =
                when {
                    !claudeLogFilesSupported -> "this platform reads no folder"
                    directory.isBlank() -> "define the directory the logs are in"
                    !folderReadable -> "the directory is not readable"
                    else -> null
                }
            return when {
                kept == null -> if (loading) "Reading…" else folder?.replaceFirstChar { it.uppercase() } ?: "Reading…"
                loading -> "$kept · reading…"
                folder == null -> kept
                else -> "$kept kept · $folder"
            }
        }

    companion object {
        val EMPTY: ClaudeLogCatalog = ClaudeLogCatalog("", null)

        /**
         * A Search row's id for [entry]: its instant first (what the date sort reads back, [timeOf]), then its log
         * file's name and its place in it — stable while the file is, whichever directory it is reached from.
         */
        fun idOf(session: ClaudeLogSession, entry: ClaudeLogEntry): String =
            (entry.timeMillis ?: 0L).toString() + "#" + session.path.substringAfterLast('/').substringAfterLast('\\')
                .removeSuffix(".jsonl") + "#" + entry.ordinal

        /** The instant [idOf] wrote first; null for an entry the log gave none. */
        fun timeOf(id: String): Long? = id.substringBefore('#').toLongOrNull()?.takeIf { it > 0L }
    }
}

/** **The one text search over an entry**: every term of the query occurs in it, case aside. */
object ClaudeLogSearch {
    /** The query's terms: words, or "quoted phrases" kept whole — lowercased, as [matches] reads them. */
    fun terms(query: String): List<String> = rawTerms(query).map { it.lowercase() }

    /** The query's terms as typed. */
    fun rawTerms(query: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        fun flush() {
            if (current.isNotBlank()) out += current.toString().let { if (quoted) it else it.trim() }
            current.clear()
        }
        for (c in query) {
            when {
                c == '"' -> { flush(); quoted = !quoted }
                c.isWhitespace() && !quoted -> flush()
                else -> current.append(c)
            }
        }
        flush()
        return out
    }

    /** Whether [entry] holds every one of [terms] ([terms]' lowercased ones), case aside; an empty query holds in every entry. */
    fun matches(entry: ClaudeLogEntry, terms: List<String>): Boolean = terms.all { entry.searchText.contains(it) }

    /** The day of an instant, where the user is. */
    fun dayOf(millis: Long, timeZone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone).date
}
