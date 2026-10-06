package org.example.project

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.WindowColorSpace
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * User rule 2026-10-06: **every window has a colour**, and keeps it. The default windows share the `256³` cube in
 * equal parts; another window of a kind takes the colour of that kind's part furthest from those in use and from the
 * part's edge (`docs/invariants/popups.md` § *Window colours*).
 */
class WindowColorSpaceTest {

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun open(host: WindowFrameHost, id: String, colorKind: String? = null) =
        host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) {}.also { it.colorKind = colorKind })

    @Test
    fun the_default_windows_share_the_cube_in_equal_parts() {
        val kinds = WindowColorSpace.DEFAULT_WINDOWS
        assertEquals(kinds.size, kinds.toSet().size)
        val parts = WindowColorSpace.parts(kinds.size)
        assertEquals(kinds.size, parts.size)
        // The whole cube, each colour in exactly one part.
        assertEquals(1L shl 24, parts.sumOf { it.volume })
        for (probe in listOf(0x000000, 0xFFFFFF, 0x808080, 0x123456, 0xFF0000, 0x00FF7F)) {
            assertEquals(1, parts.count { probe in it }, "colour $probe")
        }
        // Equal, to the rounding of a cut to a whole colour.
        val mean = (1L shl 24).toDouble() / parts.size
        assertTrue(parts.all { it.volume > mean * 0.9 && it.volume < mean * 1.1 }, parts.map { it.volume }.toString())
        // Each default window's colour is its part's centre: in it, and no two the same.
        val own = kinds.map { WindowColorSpace.pick(WindowColorSpace.partOf(it), emptyList()) }
        assertEquals(parts.map { it.centre }, own)
        assertEquals(own.size, own.toSet().size)
        // A frame id names its kind; a copy and a companion are of their window's kind, an unknown one is "other".
        assertEquals("Search", WindowColorSpace.kindOf("Search#2"))
        assertEquals("TaskTreeDetail", WindowColorSpace.kindOf("TaskTreeDetail/abc#2"))
        assertEquals(WindowColorSpace.OTHER, WindowColorSpace.kindOf("AppNotice"))
        assertEquals(WindowColorSpace.partOf("Calendar"), WindowColorSpace.partOf("Calendar#3"))
    }

    @Test
    fun a_duplicate_is_as_far_as_its_part_allows_from_the_colours_in_use_and_from_the_edge() {
        val part = WindowColorSpace.partOf("Search")
        val first = WindowColorSpace.pick(part, emptyList())
        // A colour of another part does not count: the Search window still opens in its own.
        assertEquals(first, WindowColorSpace.pick(part, listOf(WindowColorSpace.partOf("Calendar").centre)))
        val second = WindowColorSpace.pick(part, listOf(first))
        assertTrue(second in part)
        val third = WindowColorSpace.pick(part, listOf(first, second))
        assertTrue(third in part)
        assertEquals(3, setOf(first, second, third).size)
        // Nothing in the part does better than the answer: sampled, the nearest of the edge and the colours in use.
        fun edge(c: Int) = minOf(
            minOf(((c shr 16) and 0xFF) - part.rLo, part.rHi - 1 - ((c shr 16) and 0xFF)),
            minOf(((c shr 8) and 0xFF) - part.gLo, part.gHi - 1 - ((c shr 8) and 0xFF)),
            minOf((c and 0xFF) - part.bLo, part.bHi - 1 - (c and 0xFF)),
        ).toDouble()
        fun score(c: Int, used: List<Int>) = minOf(edge(c), used.minOf { distance(c, it) })
        val secondScore = score(second, listOf(first))
        assertTrue(secondScore > 10.0, "told apart from the original: $secondScore")
        for (r in part.rLo until part.rHi step 3) for (g in part.gLo until part.gHi step 3) for (b in part.bLo until part.bHi step 3) {
            assertTrue(score((r shl 16) or (g shl 8) or b, listOf(first)) <= secondScore + 1e-9)
        }
        assertTrue(score(third, listOf(first, second)) > 5.0)
        // The same question has the same answer.
        assertEquals(second, WindowColorSpace.pick(part, listOf(first)))
    }

    @Test
    fun a_window_is_given_its_colour_as_it_opens_and_keeps_it_until_it_closes() {
        val host = WindowFrameHost()
        var written = 0
        host.onColorAssigned = { written++ }
        open(host, "Calendar")
        open(host, "Search")
        assertEquals(WindowColorSpace.partOf("Calendar").centre, host.colors["Calendar"])
        assertEquals(WindowColorSpace.partOf("Search").centre, host.colors["Search"])
        assertEquals(2, written)
        // A duplicate: in the Search windows' part, another colour.
        open(host, "Search#2")
        val copy = host.colors.getValue("Search#2")
        assertTrue(copy in WindowColorSpace.partOf("Search"))
        assertNotEquals(host.colors["Search"], copy)
        // Nothing that happens afterwards moves a colour: more windows, a re-registration, a close beside it.
        open(host, "Search#3")
        open(host, "Search#2")
        host.unregister("Search")
        assertEquals(copy, host.colors["Search#2"])
        assertNull(host.colors["Search"], "closed: the colour goes with it")
        assertEquals(4, written)
        // The Search window opened again takes its kind's own colour back, free once more.
        open(host, "Search")
        assertEquals(WindowColorSpace.partOf("Search").centre, host.colors["Search"])
        // A window that comes back after a restart comes back in the colour kept for it.
        val restarted = WindowFrameHost()
        restarted.colors.putAll(CustomMenuButtons.decodeWindowColors(CustomMenuButtons.encodeWindowColors(host.colors.toMap())))
        open(restarted, "Search#2")
        assertEquals(copy, restarted.colors["Search#2"])
        assertEquals(emptyMap(), CustomMenuButtons.decodeWindowColors(null))
        assertEquals(emptyMap(), CustomMenuButtons.decodeWindowColors("not json"))
    }

    @Test
    fun a_changed_unfocused_notif_window_is_left_alone_and_the_next_one_has_a_colour_of_its_own() {
        val t0 = 1_800_000_000_000L
        val opened = SearchDomain.notificationsConfig(t0)
        assertTrue(SearchDomain.isUntouchedNotificationsWindow(opened))
        assertTrue(SearchDomain.isUntouchedNotificationsWindow(SearchDomain.Config.decode(opened.encode())!!))
        // Changed by the user — its search configuration, or its added elements: no longer the one that gathers.
        assertFalse(SearchDomain.isUntouchedNotificationsWindow(opened.copy(query = "timer")))
        assertFalse(SearchDomain.isUntouchedNotificationsWindow(opened.copy(added = listOf("Notification/$t0#0"))))
        assertFalse(SearchDomain.isUntouchedNotificationsWindow(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Notification))))
        // The window is a default window of its own, apart from the Search windows; the second one, opened beside
        // the changed one, is in the same part and away from it.
        val host = WindowFrameHost()
        open(host, "Search")
        open(host, "Search#2", WindowColorSpace.UNFOCUSED_NOTIF)
        open(host, "Search#3", WindowColorSpace.UNFOCUSED_NOTIF)
        val part = WindowColorSpace.partOf(WindowColorSpace.UNFOCUSED_NOTIF)
        val first = host.colors.getValue("Search#2")
        val second = host.colors.getValue("Search#3")
        assertEquals(part.centre, first)
        assertFalse(host.colors.getValue("Search") in part)
        assertTrue(second in part)
        assertTrue(distance(first, second) > 10.0)
    }
}
