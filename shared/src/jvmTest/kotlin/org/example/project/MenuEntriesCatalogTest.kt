package org.example.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ContextDomain

/**
 * User rule 2026-11-25 (*"Make sure the logic is the same everywhere."*): every right-click menu lists what its context's
 * "Entries, in order" names ([ContextDomain.MENU_ENTRIES]) — so an entry the code gives under an id no menu names would
 * never be shown. Every entry written in the code, by its id (`id = "…"`, else its label), is named by some menu.
 */
class MenuEntriesCatalogTest {
    @Test
    fun every_menu_entry_of_the_code_is_named_by_a_menus_entries() {
        val sources = File("src/commonMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        val call = Regex("""(?:MenuAction|HeadMenuEntry)\(\s*(?:"([^"]+)"|[^,)]*?)([^\n]*)""")
        val idArg = Regex("""\bid = "([^"]+)"""")
        val ids = sources.flatMap { text ->
            call.findAll(text).mapNotNull { m ->
                if (m.value.startsWith("MenuAction(\n") || m.value.contains("class ")) return@mapNotNull null
                idArg.find(m.groupValues[2])?.groupValues?.get(1) ?: m.groupValues[1].takeIf { it.isNotEmpty() }
            }.toList()
        }.toSet()
        assertTrue(ids.size >= 30, "the entries are found: $ids")
        val named = ContextDomain.MENU_ENTRIES.values.flatten().toSet()
        assertEquals(emptySet(), ids - named, "entries no menu's \"Entries, in order\" names")
        // Every right-click menu has its own list.
        assertEquals(emptySet(), ContextDomain.RIGHT_CLICK_MENUS.map { it.first }.toSet() - ContextDomain.MENU_ENTRIES.keys)
        assertTrue(ContextDomain.RIGHT_CLICK_MENUS.all { (name, _) -> ContextDomain.configOf(emptyList(), "menu:$name").entries.isNotEmpty() })
    }
}
