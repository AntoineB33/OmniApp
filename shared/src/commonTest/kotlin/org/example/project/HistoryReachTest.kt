package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PriorityWeightPin
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskLink
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskRelationKey
import org.example.project.scheduler.model.TaskRelationMark
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SettingEntries
import org.example.project.scheduler.persistence.WHOLE_PIN_ROW
import org.example.project.scheduler.state.ExternalKeys
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.RecordDelta
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-01: *"Make sure reset creates a history unit that ctrl+z reverts whatever window is focused. Then
 * find other things in the app that should create a history unit but don't (or can't be reverted for other bugs)"*.
 *
 * The anomaly, read off the release DB: two menu Resets stamped with the Search window, never walked, and a banked
 * record on top of them that the next Ctrl+Z would have taken instead.
 */
class HistoryReachTest {
    private val now = 1_788_343_200_000L
    private val min = 60_000L
    private var stamped = false

    @BeforeTest
    fun stampWindows() {
        stamped = SchedulerReducer.stampsWindow
        SchedulerReducer.stampsWindow = true
    }

    @AfterTest
    fun restore() {
        SchedulerReducer.stampsWindow = stamped
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun focus(state: SchedulerState, window: HistoryWindow) = r(state, SchedulerIntent.FocusWindow(window, ""))

    private fun main(state: SchedulerState): List<HistoryUnit> = state.histories.forCategory(HistoryCategory.Main).units

    private fun menuUnit(key: String = ExternalKeys.MENU + "*") = SchedulerIntent.RecordExternal(key, "before", "after", "Menu buttons")

    private fun inMenu(intent: SchedulerIntent) = SchedulerIntent.MadeIn(HistoryWindow.Menu, "", intent)

    private fun oneTask(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        return s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    }

    private fun work(s: SchedulerState): TaskId = s.tasks.values.single { it.title == "Work" }.id

    /** [state] with one banked record on top of the Main stack, as the line leaves it. */
    private fun banked(state: SchedulerState): SchedulerState {
        val id = work(state)
        val panel = TaskPanel("auto/0", id, "Work", now - 30 * min, now - min, auto = true)
        val out = r(state.copy(panels = state.panels + panel), SchedulerIntent.AdvanceSchedule(now))
        assertTrue(main(out).last().delta is RecordDelta, "the line banked a record on the Main stack")
        return out
    }

    /** User rule 2026-11-12 (replacing 2026-11-01's): the menu is no window, so only the whole walk reaches its units. */
    @Test
    fun a_menu_unit_is_walked_by_ctrl_shift_z_from_every_window_and_by_no_windows_ctrl_z() {
        for (window in listOf(HistoryWindow.Tree, HistoryWindow.Calendar, HistoryWindow.Search, HistoryWindow.Alarms)) {
            var s = focus(SchedulerState.empty(), HistoryWindow.Search)
            s = r(s, inMenu(menuUnit()))
            assertEquals(HistoryWindow.Menu, main(s).last().window)
            assertEquals(HistoryWindow.Search, s.focusedWindow, "the menu takes no focus")
            s = focus(s, window)
            assertEquals(s, r(s, SchedulerIntent.Undo), "Ctrl+Z in $window does not reach the menu")
            s = r(s, SchedulerIntent.UndoAll)
            assertEquals("before", s.externalRestores.last().to, "undone from $window")
            assertEquals(s, r(s, SchedulerIntent.Redo), "nor does Ctrl+Y")
            s = r(s, SchedulerIntent.RedoAll)
            assertEquals("after", s.externalRestores.last().to, "redone from $window")
        }
    }

    @Test
    fun a_menu_unit_written_before_the_menu_had_a_stamp_is_the_whole_walks_too() {
        // Units 7536-7537 of the release DB: the Resets, stamped with whichever window held the focus.
        var s = focus(SchedulerState.empty(), HistoryWindow.Search)
        s = r(s, menuUnit())
        assertEquals(HistoryWindow.Search, main(s).last().window)
        assertEquals(s, r(s, SchedulerIntent.Undo), "not the Search window's change, though stamped with it")
        s = focus(s, HistoryWindow.Calendar)
        s = r(s, SchedulerIntent.UndoAll)
        assertEquals("before", s.externalRestores.last().to)
        // …and the store keeps both facts.
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(r(s, SchedulerIntent.RedoAll))))
        assertEquals("before", r(back, SchedulerIntent.UndoAll).externalRestores.last().to)
    }

    /** A change [value] made in [window] (its title, which restores to "[value]-" when undone). */
    private fun edit(state: SchedulerState, window: HistoryWindow, value: String): SchedulerState {
        val key = ExternalKeys.WINDOW_TITLE + window.name
        return r(focus(state, window), SchedulerIntent.RecordExternal(key, "$value-", value, "Title"))
    }

    /**
     * User rule 2026-11-12: the whole walk going back skips a unit its window's Ctrl+Z already undid; going forward it
     * moves the whole pin to the next undone change over it.
     */
    @Test
    fun the_whole_walk_skips_what_a_window_undid() {
        var s = SchedulerState.empty()
        s = edit(s, HistoryWindow.Tree, "a")
        s = edit(s, HistoryWindow.Calendar, "b")
        s = edit(s, HistoryWindow.Tree, "c")
        // The tree's Ctrl+Z, twice: c then a. The calendar's b stays.
        s = r(focus(s, HistoryWindow.Tree), SchedulerIntent.Undo)
        s = r(s, SchedulerIntent.Undo)
        assertEquals(listOf("c-", "a-"), s.externalRestores.takeLast(2).map { it.to })
        // Ctrl+Shift+Z: the newest applied change is b; a and c are skipped.
        s = r(s, SchedulerIntent.UndoAll)
        assertEquals("b-", s.externalRestores.last().to)
        assertEquals(s, r(s, SchedulerIntent.UndoAll), "nothing applied is left")
        // Ctrl+Shift+Y: a, b, c, in the order made.
        val redone = mutableListOf<String?>()
        repeat(3) {
            s = r(s, SchedulerIntent.RedoAll)
            redone += s.externalRestores.last().to
        }
        assertEquals<List<String?>>(listOf("a", "b", "c"), redone)
    }

    @Test
    fun what_the_line_banked_is_walked_by_no_chord_and_discards_no_redo() {
        var s = focus(oneTask(), HistoryWindow.Search)
        s = r(s, inMenu(menuUnit()))
        // The banked record on top: Ctrl+Z goes under it, to what the user did.
        val atop = banked(s)
        val undone = r(atop, SchedulerIntent.UndoAll)
        assertEquals("before", undone.externalRestores.lastOrNull()?.to)
        assertEquals(atop.tasks.getValue(work(atop)).record, undone.tasks.getValue(work(atop)).record, "the work stays banked")
        // Undone, then the line banks again: the Ctrl+Y is still there.
        val later = banked(undone.copy(panels = emptyList()))
        assertEquals("after", r(later, SchedulerIntent.RedoAll).externalRestores.last().to)
    }

    @Test
    fun a_record_unit_of_an_older_build_is_the_apps_and_a_removed_one_the_users() {
        val s = banked(oneTask())
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertTrue(main(back).last().delta.authoredByApp)
        val id = work(s)
        val range = s.tasks.getValue(id).record.first()
        val removed = r(s, SchedulerIntent.RemoveRecordPeriod(id, range.startEpochMillis, range.endEpochMillis))
        val backRemoved = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(removed)))
        assertFalse(main(backRemoved).last().delta.authoredByApp)
        assertTrue(range !in backRemoved.tasks.getValue(id).record)
    }

    @Test
    fun a_record_removed_by_hand_comes_back_with_ctrl_z() {
        val s = banked(oneTask())
        val id = work(s)
        val range = s.tasks.getValue(id).record.first()
        val removed = r(s, SchedulerIntent.RemoveRecordPeriod(id, range.startEpochMillis, range.endEpochMillis))
        assertTrue(range !in removed.tasks.getValue(id).record)
        assertTrue(range in r(removed, SchedulerIntent.Undo).tasks.getValue(id).record)
    }

    @Test
    fun a_period_laid_over_work_gives_the_work_back_with_its_one_ctrl_z() {
        val s = focus(banked(oneTask()), HistoryWindow.Calendar)
        val id = work(s)
        val record = s.tasks.getValue(id).record
        val calendarUnits = s.histories.forCategory(HistoryCategory.Calendar).units.size
        val laid = r(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.SLEEP, now - 40 * min, now - 2 * min))
        assertTrue(laid.tasks.getValue(id).record != record, "the period took the work under it")
        assertEquals(calendarUnits + 1, laid.histories.forCategory(HistoryCategory.Calendar).units.size, "one gesture, one unit")
        assertEquals(record, r(laid, SchedulerIntent.Undo).tasks.getValue(id).record)
    }

    @Test
    fun every_setting_without_a_kind_of_its_own_is_one_unit_put_back_by_ctrl_z() {
        val s = oneTask()
        val id = work(s)
        val cell = s.lists[s.rootListId]!!.cellIds[0]
        val list = s.rootListId
        val intents =
            listOf(
                SchedulerIntent.SetSoundVolume(0.25),
                SchedulerIntent.SetAutomaticSchedule(!s.automaticSchedule),
                SchedulerIntent.SetMinimumTimeWeight(s.minimumTimeWeight / 2 + 0.01),
                SchedulerIntent.SetSoundMuted(!s.soundMuted),
                SchedulerIntent.SetPlanCalculationLimit(s.planCalculationLimitSeconds + 1),
                SchedulerIntent.SetShowScreenBreaks(!s.showScreenBreaks),
                SchedulerIntent.SetShowReminders(!s.showReminders),
                SchedulerIntent.SetCalendarDayMode(!s.calendarDayMode),
                SchedulerIntent.SetDefaultSubtreeEnabled(!s.defaultSubtreeEnabled),
                SchedulerIntent.SetDeepCopyMaxDepth(s.deepCopyMaxDepth + 1),
                SchedulerIntent.SetDeepCopyUnlimited(!s.deepCopyUnlimited),
                SchedulerIntent.SetNewAlarmDefaults(s.newAlarmDefaults.copy(soundSeconds = s.newAlarmDefaults.soundSeconds + 1)),
                SchedulerIntent.SetNewTimerDefaults(s.newTimerDefaults.copy(durationSeconds = s.newTimerDefaults.durationSeconds + 1)),
                SchedulerIntent.SetNewQuotaDefaults(s.newQuotaDefaults.copy(title = "Mine")),
                SchedulerIntent.SetNewAlarmTimes(s.newAlarmTimes.copy(fixedMinutes = 90)),
                SchedulerIntent.SetNewReminderTimes(s.newReminderTimes.copy(fixedMinutes = 90)),
                SchedulerIntent.ToggleRelativePriorityPin(id, id, cell),
                SchedulerIntent.TogglePriorityWeightPin(list, cell, 0),
                SchedulerIntent.KeepTaskRelation(id, id),
                SchedulerIntent.SetSleepMode(now + 60 * min, byUser = true),
            )
        for (intent in intents) {
            val done = r(s, intent)
            assertEquals(main(s).size + 1, main(done).size, "one unit: $intent")
            assertTrue(SettingEntries.of(done) != SettingEntries.of(s), "it moved something: $intent")
            val undone = r(done, SchedulerIntent.Undo)
            assertEquals(SettingEntries.of(s), SettingEntries.of(undone), "put back: $intent")
            assertEquals(SettingEntries.of(done), SettingEntries.of(r(undone, SchedulerIntent.Redo)), "redone: $intent")
            // …and the unit survives the store and the server.
            val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(done)))
            assertEquals(SettingEntries.of(s), SettingEntries.of(r(back, SchedulerIntent.Undo)), "put back after a load: $intent")
        }
    }

    @Test
    fun a_field_typed_or_a_slider_dragged_is_one_unit() {
        var s = oneTask()
        val units = main(s).size
        for (v in listOf(0.9, 0.8, 0.7)) s = r(s, SchedulerIntent.SetSoundVolume(v))
        assertEquals(units + 1, main(s).size)
        assertEquals(1.0, r(s, SchedulerIntent.Undo).soundVolume)
        // The three writes of the deep-copy window's one Save.
        val d = oneTask()
        var saved = r(d, SchedulerIntent.SetDeepCopyMaxDepth(d.deepCopyMaxDepth + 1))
        saved = r(saved, SchedulerIntent.SetDeepCopyUnlimited(!d.deepCopyUnlimited))
        saved = r(saved, SchedulerIntent.SetCopyOptions(SchedulerDomain.CopyOptions(includeIds = !d.copyIncludeIds, excludeTitle = "x")))
        assertEquals(main(d).size + 1, main(saved).size)
        assertEquals(SettingEntries.of(d), SettingEntries.of(r(saved, SchedulerIntent.Undo)))
    }

    @Test
    fun the_engine_ending_a_sleep_is_no_unit_and_the_button_is() {
        val s = oneTask()
        val sleeping = r(s, SchedulerIntent.SetSleepMode(now + 60 * min))
        assertEquals(main(s).size, main(sleeping).size)
        val woken = r(sleeping, SchedulerIntent.SetSleepMode(null))
        assertEquals(main(s).size, main(woken).size)
        val pressed = r(s, SchedulerIntent.SetSleepMode(now + 60 * min, byUser = true))
        assertEquals(main(s).size + 1, main(pressed).size)
        val undone = r(pressed, SchedulerIntent.Undo)
        assertEquals(null, undone.sleepingUntilMillis)
        assertEquals(null, undone.sleepingSinceMillis)
    }

    @Test
    fun pins_and_marks_and_links_go_through_entries_exactly() {
        val a = TaskId("a")
        val b = TaskId("b")
        val c = org.example.project.scheduler.model.CellId("c/1")
        val s =
            SchedulerState.empty().copy(
                relativePriorityPins = mapOf(RelativePriorityPinKey(a, b) to setOf(c)),
                priorityWeightPins = mapOf(org.example.project.scheduler.model.CellListId("l") to setOf(PriorityWeightPin(c, 2), PriorityWeightPin(null, 0))),
                taskRelations = mapOf(TaskRelationKey(a, b) to TaskRelationMark(kept = true, retargeted = false, hidden = true)),
                taskLinks = listOf(TaskLink(a, b)),
                sleepingSinceMillis = now,
            )
        val rebuilt =
            SettingEntries.of(s).entries.fold(SchedulerState.empty()) { out, (key, value) -> SettingEntries.with(out, key, value) }
        assertEquals(SettingEntries.of(s), SettingEntries.of(rebuilt))
        assertEquals(s.relativePriorityPins, rebuilt.relativePriorityPins)
        assertEquals(s.priorityWeightPins, rebuilt.priorityWeightPins)
        assertEquals(s.taskRelations, rebuilt.taskRelations)
        assertEquals(s.taskLinks, rebuilt.taskLinks)
        // A removed entry is gone.
        val cleared = SettingEntries.of(s).keys.fold(s) { out, key -> SettingEntries.with(out, key, null) }
        assertTrue(cleared.relativePriorityPins.isEmpty() && cleared.priorityWeightPins.isEmpty())
        assertTrue(cleared.taskRelations.isEmpty() && cleared.taskLinks.isEmpty() && cleared.sleepingSinceMillis == null)
    }

    /**
     * User example 2026-11-12: two changes in A (A's pin and the whole pin on the second), Ctrl+Z in A (both on the
     * first), a change in B (A's pin unchanged, the whole pin and B's on the new change) — and A's Ctrl+Y still there.
     */
    @Test
    fun a_change_in_another_window_keeps_this_windows_redo() {
        var s = SchedulerState.empty()
        s = edit(s, HistoryWindow.Tree, "a1")
        s = edit(s, HistoryWindow.Tree, "a2")
        val a2 = main(s).last().deviceSeq
        assertEquals(a2, SchedulerReducer.wholePinOf(s))
        s = r(s, SchedulerIntent.Undo)
        val a1 = main(s).first { it.delta.label == "Title" }.deviceSeq
        assertEquals(a1, SchedulerReducer.wholePinOf(s), "the whole pin went down with A's")
        s = edit(s, HistoryWindow.Calendar, "b")
        assertEquals(main(s).last().deviceSeq, SchedulerReducer.wholePinOf(s), "the whole pin is on B's change")
        assertTrue(main(s).any { it.deviceSeq == a2 && it.undone }, "A's undone change was kept")
        assertEquals(s, r(s, SchedulerIntent.RedoAll), "the whole pin has nothing over it")
        s = r(focus(s, HistoryWindow.Tree), SchedulerIntent.Redo)
        assertEquals("a2", s.externalRestores.last().to, "A's Ctrl+Y")
        // A new change in A is A's new branch: its redo goes.
        s = r(s, SchedulerIntent.Undo)
        s = edit(s, HistoryWindow.Tree, "a3")
        assertTrue(main(s).none { it.deviceSeq == a2 }, "A's own change drops A's redo")
    }

    /** User rule 2026-11-12: Ctrl+Shift+Y passes over a change a window's Ctrl+Y already redid. */
    @Test
    fun ctrl_shift_y_passes_over_what_a_window_redid() {
        var s = SchedulerState.empty()
        s = edit(s, HistoryWindow.Calendar, "x")
        s = edit(s, HistoryWindow.Tree, "y")
        s = r(s, SchedulerIntent.UndoAll)
        s = r(s, SchedulerIntent.UndoAll)
        assertEquals(0L, SchedulerReducer.wholePinOf(s))
        s = r(focus(s, HistoryWindow.Tree), SchedulerIntent.Redo)
        assertEquals("y", s.externalRestores.last().to)
        s = r(s, SchedulerIntent.RedoAll)
        assertEquals("x", s.externalRestores.last().to)
        assertEquals(s, r(s, SchedulerIntent.RedoAll), "y is applied already: nothing over the pin")
    }

    /** The whole pin is this device's: stored with the history, never in what goes over the wire. */
    @Test
    fun the_whole_pin_is_stored_and_kept_off_the_wire() {
        var s = SchedulerState.empty()
        s = edit(s, HistoryWindow.Tree, "a")
        s = edit(s, HistoryWindow.Calendar, "b")
        s = r(s, SchedulerIntent.UndoAll)
        val pin = SchedulerReducer.wholePinOf(s)
        assertEquals(pin, s.histories.wholePin)
        val back = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s)))
        assertEquals(pin, back.histories.wholePin)
        assertTrue(SchedulerStateCodec.syncFingerprint(s).pointers.none { it.category == WHOLE_PIN_ROW })
        // A snapshot written before the pin existed: no row, the pin is read off the units.
        val older = SchedulerStateCodec.encodeSnapshot(s).let { it.copy(pointers = it.pointers.filterNot { p -> p.category == WHOLE_PIN_ROW }) }
        val loaded = assertNotNull(SchedulerStateCodec.decodeSnapshot(older))
        assertEquals(null, loaded.histories.wholePin)
        assertEquals(main(loaded).filter { !it.undone }.maxOf { it.deviceSeq }, SchedulerReducer.wholePinOf(loaded))
    }

    /** A change [value] made in the copy [instance] of [window]. */
    private fun editIn(state: SchedulerState, window: HistoryWindow, instance: String, value: String): SchedulerState {
        val focused = r(state, SchedulerIntent.FocusWindow(window, instance))
        return r(focused, SchedulerIntent.RecordExternal(ExternalKeys.WINDOW_TITLE + window.name + instance, "$value-", value, "Title"))
    }

    /** User rule 2026-11-12: each copy of a window is a window of its own, with its own Ctrl+Z, pin and redo. */
    @Test
    fun each_copy_of_a_window_has_its_own_history() {
        var s = SchedulerState.empty()
        s = editIn(s, HistoryWindow.Search, "", "one")
        s = editIn(s, HistoryWindow.Search, "#2", "two")
        assertEquals("#2", main(s).last().instance)
        // Ctrl+Z in the original takes back its own change, under the copy's newer one.
        s = r(r(s, SchedulerIntent.FocusWindow(HistoryWindow.Search, "")), SchedulerIntent.Undo)
        assertEquals("one-", s.externalRestores.last().to)
        assertEquals(s, r(s, SchedulerIntent.Undo), "the copy's change is not the original's")
        // A change in the copy keeps the original's redo.
        s = editIn(s, HistoryWindow.Search, "#2", "three")
        s = r(r(s, SchedulerIntent.FocusWindow(HistoryWindow.Search, "")), SchedulerIntent.Redo)
        assertEquals("one", s.externalRestores.last().to)
    }

    /** The copy is stored with the window, as its frame id, and a unit written before copies is the original's. */
    @Test
    fun the_copy_survives_the_store_and_the_wire() {
        var s = SchedulerState.empty()
        s = editIn(s, HistoryWindow.Search, "#3", "x")
        val snapshot = SchedulerStateCodec.encodeSnapshot(s)
        assertTrue(snapshot.history.any { it.window == "Search#3" })
        val back = assertNotNull(SchedulerStateCodec.decodeSnapshot(snapshot))
        assertEquals(HistoryWindow.Search to "#3", main(back).last().let { it.window to it.instance })
        val unit = main(s).last()
        val row = SchedulerStateCodec.encodeUnit(unit)
        val peer = assertNotNull(SchedulerStateCodec.decodeUnit(1L, 0L, false, "Search#3", "d", 1L, false, row))
        assertEquals(HistoryWindow.Search to "#3", peer.window to peer.instance)
        val older = assertNotNull(SchedulerStateCodec.decodeUnit(1L, 0L, false, "Search", "d", 1L, false, row))
        assertEquals(HistoryWindow.Search to "", older.window to older.instance)
    }
}
