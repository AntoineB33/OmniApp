package org.example.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ContextDomain

/**
 * User rule 2026-11-25: every right-click menu of the app is a context of its own ([ContextDomain.RIGHT_CLICK_MENUS]) —
 * one drawn under a name the catalog misses would open with a drop-down's default, and its own could not be seen or set.
 */
class ContextCatalogTest {
    @Test
    fun every_right_click_menu_of_the_code_is_in_the_catalog() {
        val sources = File("src/commonMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        val named = Regex("""ActionMenu\(\s*"([^"]+)"""")
        val names = sources.flatMap { file -> named.findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(names.size >= 20, "the menus are found: $names")
        val catalog = ContextDomain.RIGHT_CLICK_MENUS.map { it.first }.toSet()
        assertEquals(emptySet(), names - catalog, "menus missing from the catalog")
        assertEquals(catalog.size, ContextDomain.RIGHT_CLICK_MENUS.size, "no menu twice")
        assertEquals(ContextDomain.CONTEXTS.size, ContextDomain.CONTEXTS.map { it.id }.toSet().size, "every context's id is its own")
    }

    /**
     * User rule 2026-11-27: every drop-down named in the code is a context of its own ([ContextDomain.DROP_DOWNS]) — one
     * missing opens with "default of a drop-down", and no configuration could be set for it alone.
     */
    @Test
    fun every_named_drop_down_of_the_code_is_in_the_catalog() {
        val sources = File("src/commonMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        val named = Regex("""(?:CheckBoxDropDown|ChoiceDropDown|OptionSearchMenu|KindsDropDown|ElementsDropDown|EnumPicker)\(\s*"([^"]+)"""")
        val names = sources.flatMap { file -> named.findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(names.size >= 20, "the drop-downs are found: $names")
        // A list of integers has its own context ("integers").
        val catalog = ContextDomain.DROP_DOWNS.map { it.first }.toSet() + "ParentDistanceField"
        assertEquals(emptySet(), names - catalog, "drop-downs missing from the catalog")
        assertEquals(ContextDomain.DROP_DOWNS.size, ContextDomain.DROP_DOWNS.map { it.first }.toSet().size, "no drop-down twice")
    }
}
