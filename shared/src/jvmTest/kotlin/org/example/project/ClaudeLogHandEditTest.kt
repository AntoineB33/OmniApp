package org.example.project

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.claudelogs.ClaudeLogIndex
import org.example.project.claudelogs.ClaudeLogKeeper

/**
 * User rule 2026-11-01: **what was first read of an entry is what the account keeps.** Each case reads a real file, edits
 * it by hand, and reads it again with a fresh index — what a restart of the app does — into the same archive.
 */
class ClaudeLogHandEditTest {
    private fun prompt(text: String, second: Int) =
        """{"type":"user","message":{"role":"user","content":"$text"},"origin":{"kind":"human"},"timestamp":"2026-11-01T00:00:%02d.000Z","sessionId":"s"}""".format(second)

    private fun answer(text: String, second: Int) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]},"timestamp":"2026-11-01T00:00:%02d.000Z"}""".format(second)

    private val lines = listOf(prompt("one", 1), answer("first", 2), prompt("two", 3), answer("second", 4))

    /** The archive after reading [root], and again after [edit]: the entries' texts, oldest first. */
    private fun textsAfter(edit: (File) -> Unit): List<String> {
        val root = Files.createTempDirectory("claude-edit").toFile()
        try {
            val log = File(root, "s.jsonl")
            log.writeText(lines.joinToString("\n", postfix = "\n"))
            val keeper = ClaudeLogKeeper(store = null, limitBytes = 1024 * 1024)
            keeper.absorb("me", ClaudeLogIndex().refresh(root.path))
            edit(log)
            val sessions = keeper.absorb("me", ClaudeLogIndex().refresh(root.path))
            return sessions.single().entries.map { it.text }
        } finally {
            root.deleteRecursively()
        }
    }

    private val kept = listOf("one", "first", "two", "second")

    @Test
    fun aDeletedLogChangesNothing() {
        assertEquals(kept, textsAfter { it.delete() })
    }

    @Test
    fun anEditedEntryKeepsWhatWasFirstRead() {
        assertEquals(kept, textsAfter { log -> log.writeText(log.readText().replace("first", "EDITED")) })
    }

    @Test
    fun aLineCutFromTheLogDuplicatesNothingAfterIt() {
        assertEquals(kept, textsAfter { log -> log.writeText(log.readLines().drop(1).joinToString("\n", postfix = "\n")) })
    }

    @Test
    fun aLineCutThenANewEntryComesAfterWhatIsKept() {
        val texts =
            textsAfter { log ->
                log.writeText((log.readLines().drop(1) + answer("third", 5)).joinToString("\n", postfix = "\n"))
            }
        assertEquals(kept + "third", texts)
    }

    @Test
    fun whatIsNewInTheLogStillComesAndATitleStillFollows() {
        val root = Files.createTempDirectory("claude-title").toFile()
        try {
            val log = File(root, "s.jsonl")
            log.writeText(lines.joinToString("\n", postfix = "\n"))
            val keeper = ClaudeLogKeeper(store = null, limitBytes = 1024 * 1024)
            keeper.absorb("me", ClaudeLogIndex().refresh(root.path))
            log.appendText("""{"type":"ai-title","aiTitle":"A title","sessionId":"s"}""" + "\n")
            val sessions = keeper.absorb("me", ClaudeLogIndex().refresh(root.path))
            assertEquals("A title", sessions.single().title)
            assertEquals(4, ClaudeLogCatalog("d", sessions).entries.size)
        } finally {
            root.deleteRecursively()
        }
    }
}
