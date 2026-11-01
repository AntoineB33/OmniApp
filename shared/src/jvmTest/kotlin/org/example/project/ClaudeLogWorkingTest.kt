package org.example.project

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.claudelogs.ClaudeLogEntry
import org.example.project.claudelogs.ClaudeLogFile
import org.example.project.claudelogs.ClaudeLogIndex
import org.example.project.claudelogs.ClaudeLogKind
import org.example.project.claudelogs.ClaudeLogParser
import org.example.project.claudelogs.foldedTurns

/**
 * User rule 2026-11-01: *"the element 'answer' must always be the last answer of Claude Code when waiting for the user's
 * answer. If Claude Code is waiting for a shell command to finish, then the most recent element should be a 'reasoning
 * chain' element"*. The lines are the shapes a real log writes.
 */
class ClaudeLogWorkingTest {
    private val prompt =
        """{"type":"user","message":{"role":"user","content":"do it"},"origin":{"kind":"human"},"timestamp":"2026-11-01T10:00:00.000Z","sessionId":"s"}"""

    private fun said(text: String, at: String = "01") =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]},"timestamp":"2026-11-01T10:00:$at.000Z"}"""

    private val ended = """{"type":"system","subtype":"turn_duration","durationMs":1000,"timestamp":"2026-11-01T10:00:09.000Z"}"""

    private val started =
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]},"toolUseResult":{"stdout":"","backgroundTaskId":"b1"},"timestamp":"2026-11-01T10:00:02.000Z"}"""

    private val finished =
        """{"type":"user","message":{"role":"user","content":"<task-notification>\n<task-id>b1</task-id>\n</task-notification>"},"origin":{"kind":"task-notification"},"timestamp":"2026-11-01T10:00:20.000Z"}"""

    private val stopped =
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]},"toolUseResult":{"task_id":"b1","task_type":"local_bash"},"timestamp":"2026-11-01T10:00:21.000Z"}"""

    private fun working(vararg lines: String): Boolean {
        val parser = ClaudeLogParser(ClaudeLogFile("s.jsonl", "p", 0, 0))
        lines.forEach(parser::accept)
        return parser.build().working
    }

    @Test
    fun a_turn_goes_on_until_its_reply_ended_with_no_command_out() {
        assertTrue(working(prompt), "asked, nothing yet")
        assertTrue(working(prompt, said("a step")), "working")
        assertFalse(working(prompt, said("done"), ended), "ended: it waits for the user")
        assertTrue(working(prompt, started, said("the gate is running"), ended), "ended only to wait for the shell")
        assertTrue(working(prompt, started, said("running"), ended, finished), "told, it goes on writing")
        assertFalse(working(prompt, started, said("running"), ended, finished, said("passed", "30"), ended))
        assertFalse(working(prompt, started, said("running"), stopped, said("stopped", "30"), ended), "a command stopped is out")
        assertFalse(working(), "an empty log")
    }

    @Test
    fun a_turn_going_on_has_no_answer_and_a_turn_ended_has_its_last_text() {
        fun entry(kind: ClaudeLogKind, text: String, ordinal: Int) = ClaudeLogEntry(kind, text, 1000L + ordinal, null, ordinal)
        val entries =
            listOf(
                entry(ClaudeLogKind.Prompt, "one", 0), entry(ClaudeLogKind.Answer, "one done", 1),
                entry(ClaudeLogKind.Prompt, "two", 2), entry(ClaudeLogKind.Answer, "a step", 3),
                entry(ClaudeLogKind.Answer, "the gate is running", 4),
            )
        val open = foldedTurns(entries, lastTurnOpen = true)
        assertEquals(ClaudeLogKind.Reasoning, open.last().kind)
        assertEquals("a step\n\nthe gate is running", open.last().text)
        assertEquals(1, open.count { it.kind == ClaudeLogKind.Answer }, "the turn that ended keeps its answer")
        val closed = foldedTurns(entries)
        assertEquals(ClaudeLogKind.Answer, closed.last().kind)
        assertEquals("the gate is running", closed.last().text)
        // One text alone, going on: a chain of one, at the id it will keep.
        val single = foldedTurns(entries.take(4), lastTurnOpen = true).last()
        assertEquals(ClaudeLogKind.Reasoning to 3, single.kind to single.ordinal)
    }

    @Test
    fun the_catalog_lists_the_turn_as_the_log_stands_and_a_silent_log_is_no_turn_going_on() {
        val root = Files.createTempDirectory("claude-working").toFile()
        try {
            val log = File(root, "s.jsonl")
            log.writeText(listOf(prompt, started, said("the gate is running"), ended).joinToString("\n", postfix = "\n"))
            var now = log.lastModified()
            val index = ClaudeLogIndex(nowMillis = { now })
            val sessions = index.refresh(root.path)!!
            assertEquals(setOf("s"), index.working)
            fun kinds() = ClaudeLogCatalog("", sessions, working = index.working).entries.map { it.second.kind }
            assertEquals(listOf(ClaudeLogKind.Prompt, ClaudeLogKind.Reasoning), kinds())
            // Closed with a command out, the log says so for ever: past the silence its last text is its answer.
            now += 7 * 60 * 60_000L
            index.refresh(root.path)
            assertEquals(emptySet(), index.working)
            assertEquals(listOf(ClaudeLogKind.Prompt, ClaudeLogKind.Answer), kinds())
        } finally {
            root.deleteRecursively()
        }
    }
}
