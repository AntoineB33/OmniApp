package org.example.project

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.platform.writeMdFolder

/** User rule 2026-11-21: the md folder Claude Code opens in is written again at each press, its other files left alone. */
class MdFolderWriteTest {
    @Test
    fun a_press_writes_the_md_files_again_and_leaves_the_rest() {
        val folder = Files.createTempDirectory("md-folder").toFile()
        try {
            writeMdFolder(folder, mapOf("Gone.md" to "old", "CLAUDE.md" to "guide 1"))
            File(folder, ".claude").mkdirs()
            File(folder, "notes.txt").writeText("kept")
            writeMdFolder(folder, mapOf("Home.md" to "# Home é", "CLAUDE.md" to "guide 2"))
            assertFalse(File(folder, "Gone.md").exists(), "a removed md element leaves no file")
            assertEquals("# Home é", File(folder, "Home.md").readText(Charsets.UTF_8))
            assertEquals("guide 2", File(folder, "CLAUDE.md").readText())
            assertTrue(File(folder, "notes.txt").exists() && File(folder, ".claude").isDirectory)
        } finally {
            folder.deleteRecursively()
        }
    }
}
