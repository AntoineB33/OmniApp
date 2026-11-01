package org.example.project

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.claudelogs.readClaudeSoundsVolume
import org.example.project.claudelogs.writeClaudeSoundsVolume
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.MenuControl

/**
 * User rule 2026-11-01: *"a setting to modify the volume of the sounds played by Claude Code"*: a slider of the logs page,
 * over the file the sound hook reads. Never the real one: the home folder is a temporary one here.
 */
class ClaudeSoundsVolumeTest {
    private fun inHome(block: (File) -> Unit) {
        val home = Files.createTempDirectory("claude-home").toFile()
        val real = System.getProperty("user.home")
        System.setProperty("user.home", home.path)
        try {
            block(home)
        } finally {
            System.setProperty("user.home", real)
            home.deleteRecursively()
        }
    }

    @Test
    fun no_sounds_folder_no_setting_and_nothing_written() = inHome { home ->
        assertNull(readClaudeSoundsVolume())
        writeClaudeSoundsVolume(0.5)
        assertFalse(File(home, ".claude").exists(), "nothing made for a hook that is not there")
    }

    @Test
    fun the_file_is_the_percentage_the_hook_reads() = inHome { home ->
        val folder = File(home, ".claude/hooks/sounds").apply { mkdirs() }
        assertEquals(1.0, readClaudeSoundsVolume(), "no file: full volume, as the hook reads it")
        writeClaudeSoundsVolume(0.35)
        assertEquals("35", File(folder, "volume.txt").readText())
        assertEquals(0.35, readClaudeSoundsVolume())
        writeClaudeSoundsVolume(0.0)
        assertEquals(0.0, readClaudeSoundsVolume(), "zero is a volume")
        writeClaudeSoundsVolume(7.0)
        assertEquals("100", File(folder, "volume.txt").readText())
        // Whatever else the file holds reads as the hook reads it.
        File(folder, "volume.txt").writeText("loud")
        assertEquals(1.0, readClaudeSoundsVolume())
        File(folder, "volume.txt").writeText("250\n")
        assertEquals(1.0, readClaudeSoundsVolume())
    }

    @Test
    fun the_logs_page_has_the_slider_and_a_menu_stored_before_is_given_it_once() {
        val logs = SearchDomain.CLAUDE_LOGS_PAGE
        assertEquals(MenuControl.ClaudeSoundsVolume.name, CustomMenuButtons.defaultsOf(logs).last().control)
        val older = """{"buttons":[{"id":"g-search","window":"Search","title":"Search","global":true},{"id":"d-ClaudeLogsDirectory","window":"","title":"","control":"ClaudeLogsDirectory"}],"seeded":true,"globalSeeded":true,"muteSeeded":true}"""
        val given = CustomMenuButtons.decode(older, CustomMenuButtons.defaultsOf(logs))
        assertEquals(listOf("g-search", "d-ClaudeLogsDirectory", CustomMenuButtons.CLAUDE_SOUNDS.id), given.map { it.id })
        // Removed after that, it stays removed.
        val removed = CustomMenuButtons.removed(given, CustomMenuButtons.CLAUDE_SOUNDS.id)
        assertEquals(removed, CustomMenuButtons.decode(CustomMenuButtons.encode(removed), CustomMenuButtons.defaultsOf(logs)))
        // Not on another page.
        assertFalse(CustomMenuButtons.decode(older).any { it.id == CustomMenuButtons.CLAUDE_SOUNDS.id })
        // Present already, not twice.
        val twice = CustomMenuButtons.decode(CustomMenuButtons.encode(given), CustomMenuButtons.defaultsOf(logs))
        assertEquals(1, twice.count { it.id == CustomMenuButtons.CLAUDE_SOUNDS.id })
    }

    @Test
    fun it_is_an_app_setting_found_in_the_search_window() {
        assertTrue(SearchDomain.AddedAction.ClaudeSoundsVolume in SearchDomain.addedActions("", setOf(SearchDomain.Kind.AppSetting)).single { it.first == SearchDomain.Kind.AppSetting }.second)
        assertEquals(setOf("Claude Code sounds"), SearchDomain.AppSettingEntry.entries.map { it.title }.filter { "sounds" in it }.toSet())
    }
}
