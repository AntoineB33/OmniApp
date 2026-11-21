package org.example.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User rule 2026-11-21: *"In the down arrow of the left-side menu, in 'Currently on', there is only 'Types: option'. Make
 * sure every search window of the app always respect only what is in 'Currently on'."* — and *"This is not the first time
 * I asked Claude Code to fix this kind of anomaly in other windows, and I don't want to do it again."*
 *
 * Every row a caller gives a Search window reaches it through a parameter of [org.example.project.ui.SearchWindow], and
 * every such parameter goes through the one funnel (`listedFromOf`), which says where the rows come from in the window's
 * configuration ("Listed from") — and whose rows the configuration narrows (`SearchDomain.keptListed`). A new way to give
 * a Search window rows fails here until it goes through it.
 */
class ListedFromTest {
    private val source: String by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        File(root, "shared/src/commonMain/kotlin/org/example/project/ui/SearchWindow.kt").readText()
    }

    private val body: String by lazy { source.substring(source.indexOf("\nfun SearchWindow(")) }

    /** The parameters of `SearchWindow`, name to type. */
    private val parameters: Map<String, String> by lazy {
        val head = body.substring(0, body.indexOf("\n) {"))
        Regex("""^    (\w+): ([^=\n]+?)(?: = .*)?,?$""", RegexOption.MULTILINE)
            .findAll(head)
            .associate { it.groupValues[1] to it.groupValues[2].trim().removeSuffix(",") }
    }

    /** The types that give a Search window its rows: each is a row source. */
    private val rowSourceTypes = setOf("SearchOptions", "MenuSearchRows", "AddedSearchRows", "ActionSearchRows", "SearchPicker", "SearchTreeList")

    @Test
    fun every_row_source_of_a_search_window_goes_through_the_funnel() {
        val sources = parameters.filterValues { it.removeSuffix("?") in rowSourceTypes }
        assertEquals(rowSourceTypes, sources.values.map { it.removeSuffix("?") }.toSet(), "the row sources among $parameters")
        val call = Regex("""listedFromOf\(([^)]*)\)""").find(body)!!.groupValues[1]
        for (name in sources.keys) assertTrue(name in call, "$name gives rows the funnel does not read: $call")
    }

    @Test
    fun no_new_way_to_give_rows_stands_beside_the_funnel() {
        // A type named as a list of rows, a picker or options is a row source: it must be one the funnel reads.
        val looksLikeRows = parameters.values.map { it.removeSuffix("?") }.filter { Regex("""^\w*(Rows|Options|Picker|TreeList)$""").matches(it) }
        assertEquals(emptyList(), looksLikeRows.filter { it !in rowSourceTypes }, "a row source not read by listedFromOf")
    }

    @Test
    fun the_window_reads_the_configuration_the_funnel_made() {
        val funnel = body.indexOf("SearchDomain.withListedFrom(config, listedFrom)")
        assertTrue(funnel > 0 && funnel < body.indexOf("rememberWindowFrameState"), "the funnel comes first in the window")
    }
}
