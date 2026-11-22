package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.persistence.WindowPlacement
import org.example.project.scheduler.state.ExternalKeys
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CalendarWindowMigration
import org.example.project.ui.WindowIds

/**
 * User rule 2026-11-22: **a window's id is never reused** — every window that existed is an element the history still
 * names — and the user can find what was done in ONE window by its id, and find that window by its configuration.
 */
class WindowIdsTest {

    private var stamped = false

    @BeforeTest
    fun stampWindows() {
        // The shell's setting: every unit is stamped with the focused window.
        stamped = SchedulerReducer.stampsWindow
        SchedulerReducer.stampsWindow = true
    }

    @AfterTest
    fun restore() {
        SchedulerReducer.stampsWindow = stamped
    }

    @Test
    fun a_closed_copy_s_number_is_never_given_again() {
        // Search#2 closed (its row kept, not visible), Search#3 open: the next copy is #4, never #2 again.
        assertEquals(4, WindowIds.next("Search", emptyMap(), listOf("Search", "Search#2", "Search#3"), first = 2))
        // The floor holds even once every row naming a number is gone.
        assertEquals(8, WindowIds.next("Search", mapOf("Search" to 7), listOf("Search"), first = 2))
        // An id only the history still names counts too; another base does not.
        assertEquals(6, WindowIds.next("Search", emptyMap(), listOf("Search#5", "ConfigSearch#9"), first = 2))
        // The first copy of a base never used is #2: the original is the first.
        assertEquals(2, WindowIds.next("Search", emptyMap(), emptyList(), first = 2))
        // The per-object windows share one count, whatever their base.
        assertEquals(
            10,
            WindowIds.next(WindowIds.OBJECTS, emptyMap(), listOf("TaskEdit#4", "DeepCopy#9"), first = 1, bases = listOf("TaskEdit", "DeepCopy")),
        )
    }

    @Test
    fun the_floors_survive_their_row() {
        val floors = mapOf("Search" to 12, WindowIds.OBJECTS to 3)
        assertEquals(floors, WindowIds.decode(WindowIds.encode(floors)))
        assertEquals(emptyMap(), WindowIds.decode(null))
        assertEquals(mapOf("Search" to 2), WindowIds.decode("Search=2;garbage;=4;x=y"))
    }

    @Test
    fun a_window_s_id_and_the_stamp_of_what_was_made_in_it_read_each_other() {
        for ((window, bases) in WindowIds.OBJECT_BASES) {
            val id = WindowIds.frameIdOf(window, "#7")!!
            assertEquals(bases.first() + "#7", id)
            assertEquals(window to "#7", WindowIds.objectWindowOfFrame(id))
        }
        assertEquals("Search#3", WindowIds.frameIdOf(HistoryWindow.Search, "#3"))
        assertEquals("Search", WindowIds.frameIdOf(HistoryWindow.Search, ""))
        assertEquals("TaskTreeDetail/t1", WindowIds.frameIdOf(HistoryWindow.TaskTreeDetail, "/t1"))
        assertEquals(HistoryWindow.TaskTreeDetail to "/t1", WindowIds.objectWindowOfFrame("TaskTreeDetail/t1"))
        // The tree and the left-side menu are no window with an id.
        assertNull(WindowIds.frameIdOf(HistoryWindow.Tree, ""))
        assertNull(WindowIds.frameIdOf(HistoryWindow.Menu, ""))
    }

    @Test
    fun the_calendar_migration_takes_numbers_no_search_window_ever_had() {
        val placements =
            mapOf(
                "Calendar" to WindowPlacement(x = 0f, y = 0f, visible = true),
                // A Search copy closed long ago: its number stays taken.
                "Search#5" to WindowPlacement(x = 0f, y = 0f, visible = false),
            )
        val out = CalendarWindowMigration.migrate(placements)
        assertTrue("Search#6" in out, "the calendar becomes Search#6, not Search#2: ${out.keys}")
        assertEquals(6, WindowIds.decode(out.getValue(WindowIds.PLACEMENT_ID).config)["Search"])
    }

    private fun made(s: SchedulerState, instance: String, label: String): SchedulerState =
        SchedulerReducer.reduce(
            s,
            SchedulerIntent.MadeIn(
                HistoryWindow.Search, instance,
                SchedulerIntent.RecordExternal(ExternalKeys.SEARCH + "Search" + instance, "{}", "{\"query\":\"$label\"}", label),
            ),
        )

    private fun labels(s: SchedulerState, filters: SearchDomain.Filters): List<String> =
        SearchDomain.results(s, setOf(SearchDomain.Kind.HistoryUnit), "", filters = filters).map { it.name }

    @Test
    fun what_was_done_in_one_window_is_found_by_its_id() {
        var s = SchedulerState.empty()
        s = made(s, "", "in the original")
        s = made(s, "#3", "in copy 3")
        s = made(s, "#4", "in copy 4")
        assertEquals(listOf("in copy 3"), labels(s, SearchDomain.Filters(historyMadeInWindow = "Search#3")))
        assertEquals(listOf("in the original"), labels(s, SearchDomain.Filters(historyMadeInWindow = "Search")))
        // "Made in" (the type) still takes the three.
        assertEquals(3, labels(s, SearchDomain.Filters(historyWindow = HistoryWindow.Search)).size)
        // The row says which window: its id.
        val row = SearchDomain.results(s, setOf(SearchDomain.Kind.HistoryUnit), "", filters = SearchDomain.Filters(historyMadeInWindow = "Search#3"))
            .single() as SearchDomain.ItemResult
        assertTrue(row.detail.startsWith("Search#3 · "), row.detail)
    }

    @Test
    fun another_device_s_window_of_the_same_id_is_not_this_one() {
        val before = SchedulerReducer.deviceId
        val s =
            try {
                SchedulerReducer.deviceId = { "other-device" }
                made(SchedulerState.empty(), "#3", "on the other device")
            } finally {
                SchedulerReducer.deviceId = before
            }
        val mine = made(s, "#3", "on this device")
        assertEquals(listOf("on this device"), labels(mine, SearchDomain.Filters(historyMadeInWindow = "Search#3")))
    }

    private val windows =
        listOf(
            SearchDomain.WindowEntry(
                "Search#3", "Search (3)", SearchDomain.WindowStatus.NotOpen, "Search", "Search",
                configuration = SearchDomain.windowConfigurationSummary(SearchDomain.Config(query = "Spanish", kinds = setOf(SearchDomain.Kind.Task))),
                listedKinds = setOf(SearchDomain.Kind.Task),
            ),
            SearchDomain.WindowEntry(
                "Search#10", "Search (10)", SearchDomain.WindowStatus.Open, "Search", "Search",
                configuration = SearchDomain.windowConfigurationSummary(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Alarm))),
                listedKinds = setOf(SearchDomain.Kind.Alarm),
            ),
            SearchDomain.WindowEntry("Search", "Search", SearchDomain.WindowStatus.NotOpen, "Search", "Search"),
        )

    private fun windowRows(query: String = "", filters: SearchDomain.Filters = SearchDomain.Filters(), sorts: List<SearchDomain.SortMethod> = SearchDomain.DEFAULT_SORTS) =
        SearchDomain.results(SchedulerState.empty(), setOf(SearchDomain.Kind.Window), query, filters = filters, sorts = sorts, windows = windows)
            .filter { it.kind == SearchDomain.Kind.Window }
            .map { (it as SearchDomain.ItemResult).id }

    @Test
    fun a_window_is_found_by_its_search_configuration_and_its_id() {
        // By the types its configuration lists.
        assertEquals(listOf("Search#3"), windowRows(filters = SearchDomain.Filters(windowListedKinds = setOf(SearchDomain.Kind.Task))))
        // By its search text, through "Where to search › Description".
        val inDescription = SearchDomain.Filters(searchIn = setOf(SearchDomain.SearchField.Description))
        assertEquals(listOf("Search#3"), windowRows("Spanish", inDescription))
        // By its id.
        assertEquals(listOf("Search#10"), windowRows("Search#10", SearchDomain.Filters(searchIn = setOf(SearchDomain.SearchField.Id))))
        // The state filter still reads the state, the configuration after it.
        assertEquals(
            listOf("Search#10"),
            windowRows(filters = SearchDomain.Filters(windowStatus = SearchDomain.WindowStatus.Open)),
        )
        // Sorted by id: the number as a number.
        assertEquals(
            listOf("Search", "Search#3", "Search#10"),
            windowRows(sorts = listOf(SearchDomain.SortMethod(SearchDomain.Kind.Window, SearchDomain.SortKey.WindowId))),
        )
    }

    @Test
    fun the_new_filters_survive_their_local_encoding_and_an_older_one_reads_as_any() {
        val config =
            SearchDomain.Config(
                kinds = setOf(SearchDomain.Kind.HistoryUnit, SearchDomain.Kind.Window),
                filters = SearchDomain.Filters(historyMadeInWindow = "Search#3", windowListedKinds = setOf(SearchDomain.Kind.Task)),
            )
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        assertEquals(2, config.filters.activeCount)
        // What a build before these filters wrote: neither field.
        val older = SearchDomain.Config.decode("{\"query\":\"\",\"kinds\":[\"HistoryUnit\",\"Window\"],\"historyWindow\":\"Search\"}")!!
        assertNull(older.filters.historyMadeInWindow)
        assertTrue(older.filters.windowListedKinds.isEmpty())
        assertEquals(HistoryWindow.Search, older.filters.historyWindow)
    }

    /** Anomaly 2026-11-22 (*"there is nothing appearing under it"*): the field is handed the windows, and offers the one pasted. */
    @Test
    fun the_made_in_window_field_offers_the_window_whose_id_is_pasted() {
        assertTrue(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.HistoryUnit)).readsWindows, "a history window reads the windows")
        val menus = SearchDomain.changedElementMenus(SchedulerState.empty(), setOf(SearchDomain.Kind.Window), "Search#3", windows)
        assertEquals(listOf("Window/Search#3"), menus.identity.map { it.key })
        assertEquals("Search (3)", SearchDomain.changedElementTitle(SchedulerState.empty(), "Window/Search#3", windows))
    }
}
