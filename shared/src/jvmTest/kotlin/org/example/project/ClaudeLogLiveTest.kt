package org.example.project

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.claudelogs.ClaudeLogFile
import org.example.project.claudelogs.ClaudeLogIndex
import org.example.project.claudelogs.ClaudeLogKeeper
import org.example.project.claudelogs.ClaudeLogKind
import org.example.project.claudelogs.ClaudeLogTails
import org.example.project.claudelogs.forEachClaudeLogLine
import org.example.project.claudelogs.listClaudeLogFiles
import org.example.project.claudelogs.watchClaudeLogs

/** User rule 2026-11-01: what Claude Code writes goes up as it is written, on real files, while the app is open. */
class ClaudeLogLiveTest {
    private fun prompt(text: String) =
        """{"type":"user","message":{"role":"user","content":"$text"},"origin":{"kind":"human"},"timestamp":"2026-11-01T00:00:00.000Z","sessionId":"s"}""" + "\n"

    private fun answer(text: String) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]},"timestamp":"2026-11-01T00:00:01.000Z"}""" + "\n"

    private fun inFolder(block: (File) -> Unit) {
        val root = Files.createTempDirectory("claude-live").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aReadStopsBeforeALineStillBeingWrittenAndTheNextStartsThere() = inFolder { root ->
        val file = File(root, "s.jsonl")
        file.writeText("one\ntwo\nthr")
        val lines = ArrayList<String>()
        val next = forEachClaudeLogLine(file.path, 0L, lines::add)
        assertEquals(listOf("one", "two"), lines)
        assertEquals("one\ntwo\n".length.toLong(), next)
        assertEquals(next, forEachClaudeLogLine(file.path, next, lines::add), "no whole line yet")
        file.appendText("ee\n")
        assertEquals(file.length(), forEachClaudeLogLine(file.path, next, lines::add))
        assertEquals(listOf("one", "two", "three"), lines)
    }

    @Test
    fun onlyWhatWasAddedIsReadAndAnAnswerStillKnowsItsPrompt() = inFolder { root ->
        val path = File(root, "s.jsonl")
        path.writeText(prompt("the question"))
        val tails = ClaudeLogTails()
        fun file() = ClaudeLogFile(path.path, "p", path.length(), path.lastModified())
        val first = tails.read(file()).entries
        path.appendText(answer("the answer"))
        // The start of the file is not read again: garbling it changes nothing the tail reads.
        val bytes = path.readBytes()
        bytes[2] = 'X'.code.toByte()
        path.writeBytes(bytes)
        val second = tails.read(file()).entries
        assertEquals(listOf(ClaudeLogKind.Prompt, ClaudeLogKind.Answer), second.map { it.kind })
        assertEquals("the question", second[1].replyTo)
        assertEquals(first[0].text, second[0].text)
        assertEquals(1, second[1].ordinal)
        // Written anew, shorter: read from its start.
        path.writeText(prompt("q"))
        assertEquals(listOf("q"), tails.read(file()).entries.map { it.text })
    }

    @Test
    fun theSessionsThatChangedAreTheOnesWritten() = inFolder { root ->
        File(root, "a.jsonl").writeText(prompt("a"))
        File(root, "b.jsonl").writeText(prompt("b"))
        val index = ClaudeLogIndex()
        assertEquals(2, index.refresh(root.path)!!.size)
        assertEquals(emptyList(), index.changed(root.path))
        File(root, "b.jsonl").appendText(answer("more"))
        assertEquals(listOf("b"), index.changed(root.path)!!.map { File(it.path).name.removeSuffix(".jsonl") })
        assertEquals(emptyList(), index.changed(root.path), "read: nothing new")
        assertEquals(2, index.refresh(root.path)!!.size)
    }

    @Test
    fun aBurstAddsToTheArchiveAndOnlyWhatTheServerLacksIsSent() = inFolder { root ->
        val file = File(root, "s.jsonl")
        file.writeText(prompt("q"))
        val index = ClaudeLogIndex()
        val keeper = ClaudeLogKeeper(store = null, limitBytes = 1024 * 1024)
        keeper.absorbChanged("me", index.changed(root.path)!!)
        assertEquals(1, keeper.pushBatches("me").flatten().size)
        file.appendText(answer("a"))
        keeper.absorbChanged("me", index.changed(root.path)!!)
        assertEquals(2, keeper.pushBatches("me").flatten().size)
        // Nothing written: nothing to send, so no request at all.
        keeper.absorbChanged("me", index.changed(root.path)!!)
        assertEquals(2, keeper.sessions("me").single().entries.size)
    }

    @Test
    fun aLogWrittenIsTold() = inFolder { root ->
        val project = File(root, "project").apply { mkdirs() }
        val log = File(project, "s.jsonl").apply { writeText(prompt("q")) }
        val told = CountDownLatch(1)
        val watch = assertNotNull(watchClaudeLogs(root.path) { told.countDown() })
        try {
            log.appendText(answer("a"))
            assertTrue(told.await(10, TimeUnit.SECONDS), "the system said nothing of an append")
            assertEquals(2, listClaudeLogFiles(root.path)!!.single().let { ClaudeLogTails().read(it).entries.size })
        } finally {
            watch.close()
        }
    }

    @Test
    fun aNewProjectsLogIsTold() = inFolder { root ->
        val told = CountDownLatch(1)
        val watch = assertNotNull(watchClaudeLogs(root.path) { told.countDown() })
        try {
            val project = File(root, "new-project").apply { mkdirs() }
            // Its folder is watched once the system has said it exists.
            val log = File(project, "s.jsonl").apply { writeText(prompt("q")) }
            var left = 50
            while (told.count > 0 && left-- > 0) {
                log.appendText(answer("a"))
                told.await(100, TimeUnit.MILLISECONDS)
            }
            assertEquals(0, told.count)
        } finally {
            watch.close()
        }
    }
}
