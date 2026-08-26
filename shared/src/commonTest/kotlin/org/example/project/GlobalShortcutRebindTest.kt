package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.platform.ShortcutKey
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7: **rebinding the system-wide chords** from the keyboard-shortcuts window.
 *
 * These three are the only shortcuts in the app another application can take (the claim is first come, first
 * served), so they are the only ones the user can move. What is pinned here is everything that is not the
 * Compose surface: which chords the rules accept, that only the *overrides* are stored, that a rebinding is
 * one Undo/Redo unit in the Main history, and — per the persisted-DB compatibility rule — that a payload
 * written before any of this existed still loads as "every chord is the one it ships with".
 */
class GlobalShortcutRebindTest {

    private fun binding(key: ShortcutKey, ctrl: Boolean = true, shift: Boolean = true, alt: Boolean = true) =
        ShortcutBinding(key, ctrl = ctrl, shift = shift, alt = alt)

    private fun rebind(
        state: SchedulerState,
        shortcut: GlobalShortcut,
        binding: ShortcutBinding?,
    ): SchedulerState =
        SchedulerReducer.reduce(state, SchedulerIntent.SetGlobalShortcutBinding(shortcut, binding))

    // ---- the chord itself ------------------------------------------------------------------------

    @Test
    fun a_chord_is_spelled_one_way_only() {
        // The window, the receipt notification and the diagnostics all print this string; two spellings of
        // one chord would make "the app never got my press" undiagnosable from the log.
        assertEquals("Ctrl+Shift+Alt+A", binding(ShortcutKey.A).chord)
        assertEquals("Ctrl+Alt+K", binding(ShortcutKey.K, shift = false).chord)
        assertEquals("Shift+Alt+F9", binding(ShortcutKey.F9, ctrl = false).chord)
    }

    @Test
    fun the_defaults_are_what_an_untouched_account_is_bound_to() {
        val live = GlobalShortcutBindings.resolve(emptyMap())
        assertEquals(GlobalShortcut.entries.toSet(), live.keys)
        GlobalShortcut.entries.forEach { assertEquals(it.defaultBinding, live.getValue(it)) }
        // ...and no two of them collide, which is the invariant the merge repair leans on.
        assertEquals(GlobalShortcut.entries.size, live.values.toSet().size)
    }

    // ---- what the rules refuse -------------------------------------------------------------------

    @Test
    fun a_chord_with_too_few_modifiers_is_refused() {
        // The claim SWALLOWS the chord for the whole session: one modifier would take Ctrl+C (or Alt+F4)
        // away from every other application the user runs, and none at all would eat their typing.
        val bare = binding(ShortcutKey.K, ctrl = false, shift = false, alt = false)
        val oneModifier = binding(ShortcutKey.C, shift = false, alt = false)

        assertNotNull(GlobalShortcutBindings.rejection(emptyMap(), GlobalShortcut.ToggleAway, bare))
        assertNotNull(GlobalShortcutBindings.rejection(emptyMap(), GlobalShortcut.ToggleAway, oneModifier))
        // Two is the floor, and it is accepted.
        assertNull(
            GlobalShortcutBindings.rejection(
                emptyMap(),
                GlobalShortcut.ToggleAway,
                binding(ShortcutKey.K, shift = false),
            ),
        )
    }

    @Test
    fun two_shortcuts_may_not_hold_one_chord() {
        // One press, two actions is not something the claim can honour — and the refusal has to see the
        // DEFAULTS too, not only the chords somebody has already rebound.
        val ontoAnotherDefault =
            GlobalShortcutBindings.rejection(
                emptyMap(),
                GlobalShortcut.ToggleAway,
                GlobalShortcut.SwitchTask.defaultBinding,
            )
        assertNotNull(ontoAnotherDefault)
        assertTrue(ontoAnotherDefault.contains(GlobalShortcut.SwitchTask.action))

        val overrides = mapOf(GlobalShortcut.LookAwayNow to binding(ShortcutKey.K, shift = false))
        assertNotNull(
            GlobalShortcutBindings.rejection(
                overrides,
                GlobalShortcut.ToggleAway,
                binding(ShortcutKey.K, shift = false),
            ),
        )
    }

    @Test
    fun rebinding_a_shortcut_to_the_chord_it_already_holds_is_not_a_conflict_with_itself() {
        val overrides = mapOf(GlobalShortcut.ToggleAway to binding(ShortcutKey.K, shift = false))
        assertNull(
            GlobalShortcutBindings.rejection(
                overrides,
                GlobalShortcut.ToggleAway,
                binding(ShortcutKey.K, shift = false),
            ),
        )
    }

    @Test
    fun the_reducer_refuses_what_the_rules_refuse() {
        // The window checks the same predicate and shows the sentence, so this is the backstop for anything
        // dispatching without asking — it must leave the state untouched, not half-applied.
        val state = SchedulerState.empty()
        val bare = binding(ShortcutKey.K, ctrl = false, shift = false, alt = false)

        assertSame(state, rebind(state, GlobalShortcut.ToggleAway, bare))
        assertSame(state, rebind(state, GlobalShortcut.ToggleAway, GlobalShortcut.SwitchTask.defaultBinding))
    }

    // ---- overrides only --------------------------------------------------------------------------

    @Test
    fun only_the_overrides_are_stored_and_a_reset_removes_the_entry() {
        val moved = binding(ShortcutKey.K, shift = false)
        val bound = rebind(SchedulerState.empty(), GlobalShortcut.ToggleAway, moved)

        // Exactly one entry: the two shortcuts nobody touched must keep FOLLOWING their defaults, so that a
        // default changed in a later build reaches every account that never rebound it.
        assertEquals(mapOf(GlobalShortcut.ToggleAway to moved), bound.shortcutBindings)
        assertEquals("Ctrl+Alt+K", GlobalShortcutBindings.chordOf(bound.shortcutBindings, GlobalShortcut.ToggleAway))

        val reset = rebind(bound, GlobalShortcut.ToggleAway, null)
        assertTrue(reset.shortcutBindings.isEmpty(), "reset must REMOVE the override, not store the default")
        assertEquals(
            GlobalShortcut.ToggleAway.defaultChord,
            GlobalShortcutBindings.chordOf(reset.shortcutBindings, GlobalShortcut.ToggleAway),
        )
    }

    @Test
    fun a_rebinding_that_changes_nothing_records_nothing() {
        val state = SchedulerState.empty()
        // Resetting a shortcut that was never rebound, and re-binding one to the chord it already holds.
        assertSame(state, rebind(state, GlobalShortcut.ToggleAway, null))
        val bound = rebind(state, GlobalShortcut.LookAwayNow, binding(ShortcutKey.F7))
        assertSame(bound, rebind(bound, GlobalShortcut.LookAwayNow, binding(ShortcutKey.F7)))
    }

    // ---- one Undo/Redo unit ----------------------------------------------------------------------

    @Test
    fun a_rebinding_is_one_main_history_unit_that_undoes_and_redoes() {
        val moved = binding(ShortcutKey.K, shift = false)
        val bound = rebind(SchedulerState.empty(), GlobalShortcut.ToggleAway, moved)

        val unit = bound.histories.forCategory(HistoryCategory.Main).units.single()
        assertEquals("Keyboard shortcut", unit.delta.label)
        assertEquals(
            listOf("${GlobalShortcut.ToggleAway.action}: Ctrl+Shift+Alt+A → Ctrl+Alt+K"),
            unit.delta.details,
        )

        val undone = SchedulerReducer.reduce(bound, SchedulerIntent.Undo)
        assertTrue(undone.shortcutBindings.isEmpty())
        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertEquals(mapOf(GlobalShortcut.ToggleAway to moved), redone.shortcutBindings)
    }

    @Test
    fun undoing_a_reset_puts_the_custom_chord_back() {
        // A reset is a REMOVAL, and a delta stating only "X is now Y" could not put a removal back — which
        // is why both sides of the unit carry the whole override map.
        val moved = binding(ShortcutKey.F5)
        val bound = rebind(SchedulerState.empty(), GlobalShortcut.SwitchTask, moved)
        val reset = rebind(bound, GlobalShortcut.SwitchTask, null)

        val undone = SchedulerReducer.reduce(reset, SchedulerIntent.Undo)
        assertEquals(mapOf(GlobalShortcut.SwitchTask to moved), undone.shortcutBindings)
    }

    // ---- persistence -----------------------------------------------------------------------------

    @Test
    fun the_bindings_round_trip_through_the_codec() {
        val state =
            rebind(
                rebind(SchedulerState.empty(), GlobalShortcut.ToggleAway, binding(ShortcutKey.K, shift = false)),
                GlobalShortcut.SwitchTask,
                binding(ShortcutKey.F12, ctrl = false),
            )

        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))
        assertNotNull(decoded)
        assertEquals(state.shortcutBindings, decoded.shortcutBindings)
        // ...and so does the unit, so Ctrl+Z still walks a rebinding back after a restart.
        val unit = decoded.histories.forCategory(HistoryCategory.Main).units.last()
        assertEquals("Keyboard shortcut", unit.delta.label)
        assertEquals(state.shortcutBindings, unit.delta.redo(SchedulerState.empty()).shortcutBindings)
    }

    @Test
    fun codec_decodes_a_payload_written_before_the_shortcuts_could_be_rebound() {
        // Persisted-DB rule: an on-disk DB from a build with no binding table must still load — as "every
        // chord is the one it ships with", which is exactly how that build behaved.
        val oldJson =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()

        val decoded = SchedulerStateCodec.decode(oldJson)
        assertNotNull(decoded)
        assertTrue(decoded.shortcutBindings.isEmpty())
        assertEquals(
            GlobalShortcut.entries.map { it.defaultBinding },
            GlobalShortcut.entries.map { GlobalShortcutBindings.bindingOf(decoded.shortcutBindings, it) },
        )
    }

    @Test
    fun decode_heals_a_binding_table_the_rules_would_refuse() {
        // CLAUDE.md: decode must HEAL what an older build (or a hand edit) wrote that today's invariants
        // forbid. A shortcut this build does not know, a key it does not know, a chord below the modifier
        // floor and a duplicate all drop back to the default rather than reaching the claim.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "shortcutBindings":[
               {"shortcut":"ToggleAway","key":"K","ctrl":true,"shift":false,"alt":true},
               {"shortcut":"LookAwayNow","key":"K","ctrl":true,"shift":false,"alt":true},
               {"shortcut":"SwitchTask","key":"J","ctrl":true,"shift":false,"alt":false},
               {"shortcut":"WhateverCameLater","key":"P","ctrl":true,"shift":true,"alt":true},
               {"shortcut":"LookAwayNow","key":"NumpadPlus","ctrl":true,"shift":true,"alt":true}]}
            """.trimIndent()

        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        // Only the first row survives: the second duplicates its chord, the third is one modifier short, the
        // fourth names a shortcut this build has not got and the fifth a key it has not got.
        assertEquals(
            mapOf(GlobalShortcut.ToggleAway to binding(ShortcutKey.K, shift = false)),
            decoded.shortcutBindings,
        )
    }

    @Test
    fun a_rebinding_moves_the_sync_fingerprint() {
        // The chords are the ACCOUNT's, so a rebinding is authoritative data that has to reach the peers —
        // a fingerprint that did not move would leave the push un-enqueued.
        val before = SchedulerState.empty()
        val after = rebind(before, GlobalShortcut.ToggleAway, binding(ShortcutKey.K, shift = false))
        assertTrue(
            SchedulerStateCodec.syncFingerprint(before) != SchedulerStateCodec.syncFingerprint(after),
        )
    }

    @Test
    fun one_binding_table_has_exactly_one_encoding() {
        // The fingerprint IS the payload, byte for byte, so the same two chords reached in the other order
        // (a differently-ordered map) must not look like an edit and enqueue a push.
        val away = GlobalShortcut.ToggleAway to binding(ShortcutKey.K, shift = false)
        val switch = GlobalShortcut.SwitchTask to binding(ShortcutKey.F12, ctrl = false)
        val a = SchedulerState.empty().copy(shortcutBindings = linkedMapOf(away, switch))
        val b = SchedulerState.empty().copy(shortcutBindings = linkedMapOf(switch, away))

        assertEquals(SchedulerStateCodec.encode(a), SchedulerStateCodec.encode(b))
        assertEquals(SchedulerStateCodec.syncFingerprint(a), SchedulerStateCodec.syncFingerprint(b))
    }
}
