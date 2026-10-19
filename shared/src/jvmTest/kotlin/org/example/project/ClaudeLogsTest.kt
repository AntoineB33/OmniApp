package org.example.project

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.claudelogs.ClaudeLogEntry
import org.example.project.claudelogs.ClaudeLogFile
import org.example.project.claudelogs.ClaudeLogIndex
import org.example.project.claudelogs.ClaudeLogKind
import org.example.project.claudelogs.ClaudeLogParser
import org.example.project.claudelogs.ClaudeLogSearch
import org.example.project.claudelogs.ClaudeLogSession
import org.example.project.claudelogs.ClaudeLogsSettings
import org.example.project.claudelogs.defaultClaudeLogsDirectory
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.MenuControl
import org.example.project.claudelogs.listClaudeLogFiles
import org.example.project.claudelogs.readClaudeLogSession

/** The Claude Code logs page: what a session log yields, the Search window over it and what the page keeps. */
class ClaudeLogsTest {
    private val file = ClaudeLogFile("C:\\logs\\proj\\s1.jsonl", "proj", 0, 0)

    /** Lines in the shapes Claude Code writes them (trimmed of the fields the page does not read). */
    private val log =
        listOf(
            """{"type":"permission-mode","permissionMode":"auto","sessionId":"s1"}""",
            """{"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":"Find the bug in the parser"},"timestamp":"2026-10-18T00:12:03.298Z","origin":{"kind":"human"},"cwd":"C:\\code\\proj","sessionId":"s1"}""",
            """{"type":"attachment","attachment":{"type":"environment"},"timestamp":"2026-10-18T00:12:03.297Z"}""",
            """{"isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"","signature":"x"}]},"timestamp":"2026-10-18T00:12:04.000Z"}""",
            """{"isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"The parser skips the last line.","signature":"x"}]},"timestamp":"2026-10-18T00:12:05.000Z"}""",
            """{"isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"Read","input":{}}]},"timestamp":"2026-10-18T00:12:06.000Z"}""",
            """{"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"file text"}]},"timestamp":"2026-10-18T00:12:07.000Z"}""",
            """{"isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"The bug is an off-by-one in the line loop."}]},"timestamp":"2026-10-18T00:12:08.000Z"}""",
            """{"isSidechain":false,"type":"user","message":{"role":"user","content":"<task-notification>done</task-notification>"},"origin":{"kind":"task-notification"},"timestamp":"2026-10-18T00:13:00.000Z"}""",
            """{"isSidechain":false,"type":"user","isMeta":true,"message":{"role":"user","content":"<local-command-caveat>Caveat</local-command-caveat>"},"timestamp":"2026-10-18T00:13:01.000Z"}""",
            // An older line: no origin, so a command wrapper is told by its text.
            """{"isSidechain":false,"type":"user","message":{"role":"user","content":"<command-name>/clear</command-name>"},"timestamp":"2026-10-18T00:13:02.000Z"}""",
            """{"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"text","text":"Now fix it"}]},"timestamp":"2026-10-19T09:00:00.000Z"}""",
            """{"isSidechain":true,"type":"user","message":{"role":"user","content":"A sub-agent's task"},"timestamp":"2026-10-19T09:00:01.000Z"}""",
            """{"type":"ai-title","aiTitle":"Parser bug hunt","sessionId":"s1"}""",
            "not json at all",
        )

    private fun parse(lines: List<String> = log): ClaudeLogSession =
        ClaudeLogParser(file).also { p -> lines.forEach(p::accept) }.build()

    @Test
    fun aSessionLogYieldsItsPromptsAnswersAndReasoningChains() {
        val session = parse()
        assertEquals("s1", session.sessionId)
        assertEquals("C:\\code\\proj", session.project)
        assertEquals("Parser bug hunt", session.title)
        assertEquals(
            listOf(
                ClaudeLogKind.Prompt to "Find the bug in the parser",
                ClaudeLogKind.Reasoning to "The parser skips the last line.",
                ClaudeLogKind.Answer to "The bug is an off-by-one in the line loop.",
                ClaudeLogKind.Prompt to "Now fix it",
            ),
            session.entries.map { it.kind to it.text },
        )
        assertEquals("Find the bug in the parser", session.entries[2].replyTo)
        assertEquals(listOf(0, 1, 2, 3), session.entries.map { it.ordinal })
    }

    @Test
    fun aLogWithNoWorkingDirectoryIsNamedByItsFolderAndFile() {
        val session = parse(listOf("""{"type":"user","message":{"role":"user","content":"hi"}}"""))
        assertEquals("proj", session.project)
        assertEquals("s1", session.sessionId)
        assertNull(session.entries.single().timeMillis)
    }

    private val sessions by lazy { listOf(parse()) }
    private val catalog by lazy { ClaudeLogCatalog("C:\\logs", sessions) }
    private val utc = TimeZone.UTC
    private val claudeKinds = SearchDomain.CLAUDE_KINDS.toSet()

    private fun names(query: String = "", kinds: Set<SearchDomain.Kind> = claudeKinds, filters: SearchDomain.Filters = SearchDomain.Filters()) =
        SearchDomain.results(SchedulerState.empty(), kinds, query, filters = filters, timeZone = utc, claudeLogs = catalog).map { it.name }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun theLogsAreSearchedInTheirWholeTextEveryTermAndPhrase() {
        assertEquals(listOf("The bug is an off-by-one in the line loop."), names("BUG loop"))
        assertEquals(listOf("The parser skips the last line."), names("\"the last\""))
        assertEquals(emptyList(), names("\"last the\""))
        assertEquals(listOf("words", "a Phrase", "x"), ClaudeLogSearch.rawTerms("words \"a Phrase\" x"))
        // Each kind lists its own entries only.
        assertEquals(listOf("Now fix it", "Find the bug in the parser"), names(kinds = setOf(SearchDomain.Kind.ClaudePrompt)))
        assertEquals(listOf("The parser skips the last line."), names(kinds = setOf(SearchDomain.Kind.ClaudeReasoning)))
    }

    @Test
    fun anEntryRowIsNamedByItsFirstLineAndFoundAgainByItsId() {
        val multi = ClaudeLogSession("C:\\logs\\p\\s2.jsonl", "s2", "p", null, null, listOf(ClaudeLogEntry(ClaudeLogKind.Answer, "\nFirst line\nsecond line", 5L, null, 0)))
        val rows = SearchDomain.claudeResults(SearchDomain.Kind.ClaudeAnswer, "second", ClaudeLogCatalog("", listOf(multi)), timeZone = utc)
        val row = rows.single()
        assertEquals("First line", row.name)
        assertEquals("5#s2#0", row.id)
        assertEquals(5L, ClaudeLogCatalog.timeOf(row.id))
        assertEquals("\nFirst line\nsecond line", ClaudeLogCatalog("", listOf(multi)).find(row.id)!!.second.text)
        // An added row resolves back from its key, as the added elements list reads it.
        val key = SearchDomain.keyOf(row)
        assertEquals(listOf(key), SearchDomain.resolve(SchedulerState.empty(), listOf(key), claudeLogs = ClaudeLogCatalog("", listOf(multi))).map(SearchDomain::keyOf))
        assertEquals(emptyList(), SearchDomain.resolve(SchedulerState.empty(), listOf(key)))
    }

    @Test
    fun theLogsFiltersNarrowEveryKindAtOnce() {
        val day18 = SearchDomain.Filters(claudeUntil = millis("2026-10-18T23:59:59Z"))
        assertEquals(3, names(filters = day18).size)
        assertEquals(listOf("Now fix it"), names(filters = SearchDomain.Filters(claudeFrom = millis("2026-10-19T00:00:00Z"))))
        assertEquals(emptyList(), names(filters = SearchDomain.Filters(claudeProject = "elsewhere")))
        assertEquals(4, names(filters = SearchDomain.Filters(claudeProject = "C:\\code\\proj")).size)
        // One filter, listed under each of the three kinds: counted once.
        assertEquals(1, SearchDomain.Filters(claudeProject = "x").activeCount)
        assertTrue(SearchDomain.Filters(claudeProject = "x").isOn(SearchDomain.Setting.AnswerProject))
    }

    @Test
    fun aLogsSearchWindowListsTheNewestFirst() {
        val config = SearchDomain.claudeLogsConfig()
        val rows = SearchDomain.results(SchedulerState.empty(), config.kinds, "", sorts = config.sorts, timeZone = utc, claudeLogs = catalog)
        assertEquals(listOf("Now fix it", "Find the bug in the parser"), rows.filter { it.kind == SearchDomain.Kind.ClaudePrompt }.map { it.name })
    }

    @Test
    fun eachPageOffersItsOwnKinds() {
        val scheduler = SearchDomain.pageKinds("")
        assertTrue(SearchDomain.CLAUDE_KINDS.none { it in scheduler })
        assertTrue(SearchDomain.Kind.Task in scheduler)
        assertEquals(SearchDomain.CLAUDE_KINDS + SearchDomain.Kind.AppSetting, SearchDomain.pageKinds(SearchDomain.CLAUDE_LOGS_PAGE))
        // The logs' directory is an app setting, so the page can set it.
        val settings = SearchDomain.results(SchedulerState.empty(), setOf(SearchDomain.Kind.AppSetting), "Claude", claudeLogs = catalog)
        assertEquals(listOf("C:\\logs"), settings.map { (it as SearchDomain.ItemResult).detail })
    }

    @Test
    fun anAddedEntryHasItsContentAmongTheActions() {
        val prompt = SearchDomain.claudeResults(SearchDomain.Kind.ClaudePrompt, "fix", catalog, timeZone = utc)
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions("", claudeKinds, SearchDomain.actionKindsOf(prompt)), prompt)
        val actions = SearchDomain.actionGroups(sections, prompt).flatMap { it.actions }
        assertTrue(SearchDomain.AddedAction.PromptText in actions)
        assertTrue(SearchDomain.AddedAction.PromptSession in actions)
        assertTrue(SearchDomain.AddedAction.AnswerText !in actions)
    }

    @Test
    fun aSearchWindowsPageAndTheLogsFiltersSurviveTheStoreAndOlderRowsReadAsTheSchedulers() {
        val config =
            SearchDomain.claudeLogsConfig().copy(filters = SearchDomain.Filters(claudeProject = "p", claudeFrom = 1L, claudeUntil = 2L))
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        // A row written before the page existed: the Task Scheduler's window, no logs' filter.
        val older = SearchDomain.Config.decode("""{"query":"x","kinds":["Task"]}""")!!
        assertEquals("", older.page)
        assertNull(older.filters.claudeProject)
        // A logs window reset to nothing is still the logs page's: a window never changes page.
        val reset = SearchDomain.resetConfig(config, opened = null)
        assertEquals(SearchDomain.CLAUDE_LOGS_PAGE, reset.page)
        assertEquals(SearchDomain.pageKinds(SearchDomain.CLAUDE_LOGS_PAGE).toSet(), reset.kinds)
        // …and named after its page's kinds: all of them is "everything", as on the scheduler's page.
        assertEquals("everything", SearchDomain.windowTitle(reset))
        assertEquals("all prompts, answers and reasoning chains", SearchDomain.windowTitle(SearchDomain.claudeLogsConfig()))
        assertEquals("everything", SearchDomain.windowTitle(SearchDomain.CLEARED_CONFIG))
    }

    @Test
    fun eachPageHasItsOwnMenu() {
        assertEquals(CustomMenuButtons.PLACEMENT_ID, CustomMenuButtons.placementIdOf(""))
        assertEquals(CustomMenuButtons.CLAUDE_LOGS_PLACEMENT_ID, CustomMenuButtons.placementIdOf(SearchDomain.CLAUDE_LOGS_PAGE))
        val claude = CustomMenuButtons.decode(null, CustomMenuButtons.defaultsOf(SearchDomain.CLAUDE_LOGS_PAGE))
        assertEquals(CustomMenuButtons.CLAUDE_LOGS_DEFAULTS, claude)
        // Its Search button is the global one (user rule 2026-10-18), which opens the page's own Search window.
        assertEquals(CustomMenuButtons.GLOBAL_SEARCH, claude.first())
        assertEquals(SearchDomain.CLAUDE_LOGS_PAGE, SearchDomain.pageDefaultConfig(SearchDomain.CLAUDE_LOGS_PAGE).page)
        // …then the global mute switch (user rule 2026-10-18), then the directory field.
        assertEquals(MenuControl.MuteAll.name, claude[1].control)
        assertEquals(MenuControl.ClaudeLogsDirectory.name, claude[2].control)
        // The scheduler's menu is read as it always was.
        assertEquals(CustomMenuButtons.DEFAULTS, CustomMenuButtons.decode(null))
    }

    @Test
    fun theSettingsKeepTheDirectoryAndReadWhatTheFirstBuildWrote() {
        val settings = ClaudeLogsSettings(directory = "C:\\Users\\me\\.claude\\projects")
        assertEquals(settings, ClaudeLogsSettings.decode(ClaudeLogsSettings.encode(settings)))
        // The first build kept its own search on the row too: the directory is kept, the rest is ignored.
        assertEquals(
            ClaudeLogsSettings(directory = "D:\\logs"),
            ClaudeLogsSettings.decode("""{"directory":"D:\\logs","config":{"query":"x"},"saved":[{"name":"a","config":{}}],"searchOpen":false,"x":3.0}"""),
        )
        assertEquals(ClaudeLogsSettings(), ClaudeLogsSettings.decode(null))
        assertEquals(ClaudeLogsSettings(), ClaudeLogsSettings.decode("{not json"))
    }

    @Test
    fun tabWritesInTheSuggestedFolderOnlyWhereWhatIsTypedIsItsStart() {
        val home = "C:/Users/me/.claude/projects"
        assertEquals(home, ClaudeLogsSettings.directoryCompletion("", home))
        assertEquals(home, ClaudeLogsSettings.directoryCompletion("c:/users/M", home), "case aside, as Windows paths are")
        // Already written, or typed elsewhere: Tab moves on.
        assertNull(ClaudeLogsSettings.directoryCompletion(home, home))
        assertNull(ClaudeLogsSettings.directoryCompletion("D:/logs", home))
        assertNull(ClaudeLogsSettings.directoryCompletion("", null))
        // The folder taken once as the directory is remembered; a row written before the field existed reads as not yet.
        val adopted = ClaudeLogsSettings(directory = home, defaultAdopted = true)
        assertEquals(adopted, ClaudeLogsSettings.decode(ClaudeLogsSettings.encode(adopted)))
        assertEquals(false, ClaudeLogsSettings.decode("""{"directory":""}""").defaultAdopted)
    }

    @Test
    fun theSuggestedFolderIsClaudeCodesOwnWhenItExists() {
        val home = Files.createTempDirectory("home").toFile()
        val before = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.path)
            assertNull(defaultClaudeLogsDirectory(), "no .claude/projects yet")
            val projects = File(File(home, ".claude"), "projects").apply { mkdirs() }
            assertEquals(projects.path, defaultClaudeLogsDirectory())
        } finally {
            System.setProperty("user.home", before)
            home.deleteRecursively()
        }
    }

    @Test
    fun theFilesUnderADirectoryAreItsOwnLogsAndItsProjectsLogs() {
        val root = Files.createTempDirectory("claude-logs").toFile()
        try {
            File(root, "a").mkdirs()
            File(root, "a/s1.jsonl").writeText(log.joinToString("\n"))
            File(root, "a/s1/subagents").mkdirs()
            File(root, "a/s1/subagents/agent.jsonl").writeText(log.joinToString("\n"))
            File(root, "a/notes.txt").writeText("x")
            File(root, "top.jsonl").writeText("")
            val files = listClaudeLogFiles(root.path)!!
            assertEquals(setOf("s1.jsonl", "top.jsonl"), files.map { File(it.path).name }.toSet())
            assertEquals("a", files.single { it.path.endsWith("s1.jsonl") }.folder)
            assertEquals(4, readClaudeLogSession(files.single { it.path.endsWith("s1.jsonl") }).entries.size)
            assertNull(listClaudeLogFiles(File(root, "missing").path))
            assertNull(listClaudeLogFiles(""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aLogThatNamesNoWorkingDirectoryIsFiledUnderItsFoldersProject() {
        val files = listOf(ClaudeLogFile("p1", "enc", 1, 1), ClaudeLogFile("p2", "enc", 1, 1), ClaudeLogFile("p3", "lone", 1, 1))
        val index =
            ClaudeLogIndex(
                list = { files },
                read = { f -> ClaudeLogSession(f.path, f.path, f.folder, if (f.path == "p2") "/code/proj" else null, null, emptyList()) },
            )
        assertEquals(listOf("/code/proj", "/code/proj", "lone"), index.refresh("d")!!.map { it.project })
    }

    @Test
    fun aRefreshRereadsOnlyTheFilesThatChanged() {
        var listing = listOf(ClaudeLogFile("p1", "a", 1, 1), ClaudeLogFile("p2", "a", 1, 1))
        val reads = ArrayList<String>()
        val index =
            ClaudeLogIndex(
                list = { listing },
                read = { f -> reads += f.path; ClaudeLogSession(f.path, f.path, f.folder, null, null, emptyList()) },
            )
        assertEquals(2, index.refresh("d")!!.size)
        index.refresh("d")
        assertEquals(listOf("p1", "p2"), reads)
        listing = listOf(ClaudeLogFile("p1", "a", 2, 2))
        assertEquals(listOf("p1"), index.refresh("d")!!.map { it.path })
        assertEquals(listOf("p1", "p2", "p1"), reads)
        // Another directory starts afresh.
        index.refresh("other")
        assertEquals(listOf("p1", "p2", "p1", "p1"), reads)
    }
}
