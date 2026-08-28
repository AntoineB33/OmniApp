package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.engine.ArmedAlarm
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.platform.DeviceKind
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §18 Timers — the engine half. A timer is due at one absolute instant instead of a wall-clock time of
 * day, and that is the whole difference: it is armed by the same phone loop, swept by the same desktop
 * boundary sweep and rung through the same `onAlarmFire` as an alarm.
 *
 * So what is asserted here is mostly the JOIN: that one OS slot serves both lists (the soonest of the two
 * wins), that the two crossing streams fire in boundary order, and that a rung timer resets itself where a
 * rung one-off alarm would disarm itself.
 */
class TimerEngineTest {
    private val tz = TimeZone.currentSystemDefault()
    private val minute = 60_000L

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    /** Local wall-clock `hh:mm` on 2026-07-24, in the engine's zone. */
    private fun at(hh: Int, mm: Int): Long =
        LocalDateTime(2026, 7, 24, hh, mm).toInstant(tz).toEpochMilliseconds()

    private class Harness(nowMillis: Long, deviceKind: DeviceKind = DeviceKind.Phone) {
        val armed = mutableListOf<ArmedAlarm?>()
        val rung = mutableListOf<ArmedAlarm>()
        val clock = FixedClock(nowMillis)
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = clock,
            scope = CoroutineScope(Dispatchers.Unconfined),
            deviceKind = deviceKind,
            screenActive = { true },
            scheduleDeviceAlarm = { armed.add(it) },
            ringAlarm = { rung.add(it) },
        )
    }

    private fun timer(id: String, durationSeconds: Int, label: String = "", soundSeconds: Int = 30) =
        TimerEntry(id = id, label = label, durationSeconds = durationSeconds, soundSeconds = soundSeconds)

    @Test
    fun a_rung_timer_resets_itself_and_leaves_nothing_armed() = runTest {
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(SchedulerIntent.SetTimers(listOf(timer("timer-0", 300, label = "Tea", soundSeconds = 45))))
        h.vm.dispatch(SchedulerIntent.StartTimer("timer-0", at(7, 0)))
        h.armed.clear()
        val armedNow = ArmedAlarm("timer-0", at(7, 5), "Tea", 45, vibrate = true, timer = true)

        h.engine.onAlarmFire(armedNow)

        // Rings exactly what was armed — length + vibration included, exactly like an alarm.
        assertEquals(listOf(armedNow), h.rung)
        // A timer has no on/off switch: having run out, it goes back to its full duration.
        val entry = h.vm.state.value.timers.single()
        assertTrue(entry.idle, "a rung timer resets rather than disarming itself")
        assertEquals(300, entry.durationSeconds)
        assertTrue(h.armed.none { it != null }, "an idle timer is not due, so nothing is armed after it rings")
    }

    @Test
    fun one_os_slot_serves_both_lists_and_the_soonest_ring_takes_it() = runTest {
        // The phone can arm exactly one alarm with the OS, so the two lists must be armed together — arming
        // them separately would let whichever was recomputed last overwrite the other.
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(SchedulerIntent.SetAlarms(listOf(AlarmEntry(id = "alarm-0", timeOfDayMinutes = 9 * 60))))
        h.vm.dispatch(SchedulerIntent.SetTimers(listOf(timer("timer-0", 30 * 60))))
        h.vm.dispatch(SchedulerIntent.StartTimer("timer-0", at(7, 0)))
        h.armed.clear()

        // Any ring re-arms from the state; fire an unrelated id so only the arming decision is under test.
        h.engine.onAlarmFire(ArmedAlarm("alarm-9", at(7, 0), "", 30, vibrate = false))

        val next = h.armed.single()
        assertNotNull(next)
        assertEquals("timer-0", next.alarmId, "the timer at 07:30 beats the alarm at 09:00")
        assertEquals(at(7, 30), next.atMillis)
        assertTrue(next.timer, "the armed ring says which list it came from, so the fire path can route it")
    }

    @Test
    fun an_alarm_sooner_than_every_timer_still_takes_the_slot() = runTest {
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(SchedulerIntent.SetAlarms(listOf(AlarmEntry(id = "alarm-0", timeOfDayMinutes = 7 * 60 + 10))))
        h.vm.dispatch(SchedulerIntent.SetTimers(listOf(timer("timer-0", 30 * 60))))
        h.vm.dispatch(SchedulerIntent.StartTimer("timer-0", at(7, 0)))
        h.armed.clear()

        h.engine.onAlarmFire(ArmedAlarm("alarm-9", at(7, 0), "", 30, vibrate = false))

        val next = h.armed.single()
        assertNotNull(next)
        assertEquals("alarm-0", next.alarmId)
        assertEquals(at(7, 10), next.atMillis)
        assertTrue(!next.timer)
    }

    @Test
    fun an_idle_or_paused_timer_arms_nothing() = runTest {
        val h = Harness(nowMillis = at(7, 0))
        h.vm.dispatch(SchedulerIntent.SetTimers(listOf(timer("timer-0", 300), timer("timer-1", 600))))
        h.vm.dispatch(SchedulerIntent.StartTimer("timer-1", at(7, 0)))
        h.vm.dispatch(SchedulerIntent.PauseTimer("timer-1", at(7, 1)))
        h.armed.clear()

        h.engine.onAlarmFire(ArmedAlarm("alarm-9", at(7, 0), "", 30, vibrate = false))

        assertTrue(
            h.armed.none { it != null },
            "a timer that is not counting down has no instant to arm — it is not a silenced alarm",
        )
    }

    @Test
    fun a_desktop_rings_a_timer_from_the_now_line_exactly_once() = runTest {
        // The desktop has no OS alarm clock, so the now-line crossing IS the trigger — for a timer exactly as
        // for an alarm (CLAUDE.md: a pure function of the boundary instants the clock crossed).
        val start = at(7, 0)
        val scheduler = testScheduler
        val clock = object : AppClock {
            override fun nowMillis(): Long = start + scheduler.currentTime
        }
        val rung = mutableListOf<ArmedAlarm>()
        val armed = mutableListOf<ArmedAlarm?>()
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = clock,
            scope = backgroundScope,
            deviceKind = DeviceKind.Desktop,
            screenActive = { true },
            scheduleDeviceAlarm = { armed.add(it) },
            ringAlarm = { rung.add(it) },
        )
        vm.dispatch(SchedulerIntent.SetTimers(listOf(timer("timer-0", 60, label = "Tea", soundSeconds = 45))))
        vm.dispatch(SchedulerIntent.StartTimer("timer-0", start))
        engine.start()
        runCurrent()
        assertTrue(rung.isEmpty(), "nothing rings before the countdown runs out")

        advanceTimeBy(minute + 1) // cross the end instant
        runCurrent()

        assertEquals(1, rung.size, "the desktop rings when the now-line crosses the timer's end")
        assertEquals("timer-0", rung.single().alarmId)
        assertEquals(45, rung.single().soundSeconds, "it rings for the length the user configured")
        assertTrue(rung.single().timer)
        assertTrue(armed.isEmpty(), "a desktop arms no OS alarm — it has none")
        assertTrue(vm.state.value.timers.single().idle, "and the row is back at its full duration")

        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(1, rung.size, "a crossed ring fires exactly once")
    }

    @Test
    fun a_desktop_sweep_fires_an_alarm_and_a_timer_in_boundary_order() = runTest {
        // One merged stream, not one list after the other: a leap that crosses both must fire them in the
        // order they were actually due.
        val start = at(6, 59)
        val scheduler = testScheduler
        val clock = object : AppClock {
            override fun nowMillis(): Long = start + scheduler.currentTime
        }
        val rung = mutableListOf<ArmedAlarm>()
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = clock,
            scope = backgroundScope,
            deviceKind = DeviceKind.Desktop,
            screenActive = { true },
            ringAlarm = { rung.add(it) },
        )
        // The alarm is due at 07:00, the timer 30 s later.
        vm.dispatch(SchedulerIntent.SetAlarms(listOf(AlarmEntry(id = "alarm-0", timeOfDayMinutes = 7 * 60))))
        vm.dispatch(SchedulerIntent.SetTimers(listOf(timer("timer-0", 90))))
        vm.dispatch(SchedulerIntent.StartTimer("timer-0", start))
        engine.start()
        runCurrent()

        advanceTimeBy(2 * minute)
        runCurrent()

        assertEquals(listOf("alarm-0", "timer-0"), rung.map { it.alarmId })
    }

    @Test
    fun a_desktop_stays_silent_for_a_timer_the_machine_slept_through() = runTest {
        // The engine starts well PAST the end instant: the first sweep has no previous sweep, so the crossing
        // measures Long.MAX_VALUE old and is swallowed. A countdown that ran out while the process was down
        // is not sounded at the wrong moment on resume.
        val start = at(9, 0)
        val scheduler = testScheduler
        val clock = object : AppClock {
            override fun nowMillis(): Long = start + scheduler.currentTime
        }
        val rung = mutableListOf<ArmedAlarm>()
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = clock,
            scope = backgroundScope,
            deviceKind = DeviceKind.Desktop,
            screenActive = { true },
            ringAlarm = { rung.add(it) },
        )
        vm.dispatch(SchedulerIntent.SetTimers(listOf(timer("timer-0", 60))))
        vm.dispatch(SchedulerIntent.StartTimer("timer-0", at(7, 0)))
        engine.start()
        runCurrent()
        advanceTimeBy(minute)
        runCurrent()

        assertTrue(rung.isEmpty(), "a timer crossed while the process was down does not ring on resume")
    }
}
