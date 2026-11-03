package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.ServerBudget
import org.example.project.scheduler.domain.deviceMonthUsage
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SupabaseUsageEntry
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.MenuControl

/**
 * User rule 2026-11-03: the Settings page shows the Supabase free tier's usage with a refresh button, and the memory
 * limits' fields; *"the app must not exceeds 10% of any usage limits on any project."*
 */
class ServerUsageSettingsTest {
    private val mb = ServerBudget.MB

    @Test
    fun the_project_limit_is_never_set_above_a_tenth_of_the_free_tier() {
        assertEquals(50 * mb, ServerBudget.databaseBudgetBytes)
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDatabaseLimits(serverBytes = 80 * mb))
        assertEquals(50 * mb, s.serverDatabaseLimitBytes, "above the tenth: the tenth")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDatabaseLimits(serverBytes = 20 * mb))
        assertEquals(20 * mb, s.serverDatabaseLimitBytes, "below it: as set")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDatabaseLimits(deviceBytes = 300 * mb))
        assertEquals(300 * mb, s.deviceDatabaseLimitBytes)
        assertEquals(20 * mb, s.serverDatabaseLimitBytes, "a null leaves the other alone")
    }

    @Test
    fun a_payload_written_before_the_limits_has_the_defaults_and_a_limit_above_the_tenth_is_healed() {
        val text = SchedulerStateCodec.encode(SchedulerState.empty())
        val older = text.replace(Regex(""","serverDatabaseLimitBytes":\d+"""), "").replace(Regex(""","deviceDatabaseLimitBytes":\d+"""), "")
        assertFalse("DatabaseLimitBytes" in older, "the test payload is the older shape")
        val decoded = SchedulerStateCodec.decode(older)!!
        assertEquals(ServerBudget.databaseBudgetBytes, decoded.serverDatabaseLimitBytes)
        assertEquals(ServerBudget.DEFAULT_DEVICE_LIMIT_BYTES, decoded.deviceDatabaseLimitBytes)
        val tooHigh = SchedulerStateCodec.encode(SchedulerState.empty().copy(serverDatabaseLimitBytes = 400 * mb))
        assertEquals(50 * mb, SchedulerStateCodec.decode(tooHigh)!!.serverDatabaseLimitBytes)
    }

    @Test
    fun the_device_limit_is_this_devices_and_never_synced() {
        val base = SchedulerState.empty()
        val a = SchedulerStateCodec.syncFingerprint(base.copy(deviceDatabaseLimitBytes = 100 * mb))
        val b = SchedulerStateCodec.syncFingerprint(base.copy(deviceDatabaseLimitBytes = 900 * mb))
        assertEquals(a, b, "the device's limit moves no fingerprint")
        val c = SchedulerStateCodec.syncFingerprint(base.copy(serverDatabaseLimitBytes = 20 * mb))
        assertTrue(a != c, "the project's limit is the account's: synced")
    }

    @Test
    fun this_months_usage_counts_this_month_only() {
        val tz = TimeZone.UTC
        val now = 1_793_577_600_000L + 3 * 86_400_000L // 2026-11-03 UTC
        fun call(at: Long, bytes: Long) = SupabaseUsageEntry(at, "x", "GET", bytes / 2, bytes / 2, 200)
        val log = listOf(call(now - 10 * 86_400_000L, 1_000), call(now - 86_400_000L, 400), call(now, 600))
        val month = deviceMonthUsage(log, now, tz)
        assertEquals(1_000L, month.bytes)
        assertEquals(2, month.calls)
    }

    @Test
    fun a_settings_menu_stored_before_gets_the_usage_and_the_limits_once() {
        val defaults = CustomMenuButtons.SETTINGS_DEFAULTS
        val before = defaults.filterNot { it.control == MenuControl.SupabaseUsage.name || it.control == MenuControl.MemoryLimits.name }
        val stored = CustomMenuButtons.encode(before).replace(""","usageSeeded":true""", "")
        assertFalse("usageSeeded" in stored)
        val decoded = CustomMenuButtons.decode(stored, defaults)
        assertEquals(listOf(MenuControl.SupabaseUsage.name, MenuControl.MemoryLimits.name), decoded.takeLast(2).map { it.control })
        // Removed by the user afterwards: it stays removed.
        val removed = decoded.filterNot { it.control == MenuControl.MemoryLimits.name }
        assertTrue(CustomMenuButtons.decode(CustomMenuButtons.encode(removed), defaults).none { it.control == MenuControl.MemoryLimits.name })
    }

    /** User rule 2026-11-04: the online / offline button is in the settings page's menu by default, given ONCE to one stored before. */
    @Test
    fun a_settings_menu_stored_before_gets_the_online_button_once_above_the_usage() {
        val defaults = CustomMenuButtons.SETTINGS_DEFAULTS
        val before = defaults.filterNot { it.control == MenuControl.Online.name }
        val stored = CustomMenuButtons.encode(before).replace(""","onlineSeeded":true""", "")
        assertFalse("onlineSeeded" in stored, "test premise: a list written before the flag")
        val decoded = CustomMenuButtons.decode(stored, defaults)
        assertEquals(defaults, decoded, "it stands where a new menu has it: just above the usage")
        // Removed by the user afterwards: it stays removed.
        val removed = decoded.filterNot { it.control == MenuControl.Online.name }
        assertTrue(CustomMenuButtons.decode(CustomMenuButtons.encode(removed), defaults).none { it.control == MenuControl.Online.name })
        // The Task Scheduler's menu is never given it: a list of it stored before the flag stays as it was.
        val schedulerStored = CustomMenuButtons.encode(CustomMenuButtons.DEFAULTS).replace(""","onlineSeeded":true""", "")
        assertTrue(CustomMenuButtons.decode(schedulerStored, CustomMenuButtons.DEFAULTS).none { it.control == MenuControl.Online.name })
    }

    /**
     * Anomaly 2026-11-04: the online button MOVED to the settings page's menu, so a Reset of the Task Scheduler's menu
     * leaves it out — it put it back while it was among that menu's defaults too.
     */
    @Test
    fun a_reset_of_the_task_schedulers_menu_leaves_the_online_button_out_and_the_settings_reset_has_it() {
        assertTrue(CustomMenuButtons.DEFAULTS.none { it.control == MenuControl.Online.name })
        val stored = CustomMenuButtons.DEFAULTS + CustomMenuButtons.SETTINGS_ONLINE
        val lists = CustomMenuButtons.PAGES.associateWith { page -> if (page == "") stored else CustomMenuButtons.defaultsOf(page) }
        val reset = CustomMenuButtons.withPageReset(lists, "")
        assertTrue(reset.getValue("").none { it.control == MenuControl.Online.name }, "gone from the Task Scheduler's menu")
        val settings = CustomMenuButtons.withPageReset(reset, org.example.project.scheduler.domain.SearchDomain.SETTINGS_PAGE)
        assertTrue(settings.getValue(org.example.project.scheduler.domain.SearchDomain.SETTINGS_PAGE).any { it.control == MenuControl.Online.name })
    }
}
