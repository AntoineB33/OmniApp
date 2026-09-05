package org.example.project.scheduler.platform

import java.util.concurrent.TimeUnit

/** One power-state transition read out of the Windows System event log. */
internal data class PowerTransition(val millis: Long, val down: Boolean)

/**
 * The Windows System event log's power history — the ONE place that says which events mean "the device went
 * away", how a raw event stream becomes `[away, back]` intervals, and how the log is queried. All three
 * `SleepHistory` actuals read it, so a fix here reaches the calendar layer, the record-bank evidence, the
 * screen-break seed and the exact pause recorder at once. Two copies of an event-id set is how two readouts
 * of one machine start disagreeing about whether the user was there.
 *
 * ## Which events, and why sleep alone is not enough
 *
 * Sleep/standby is not the whole of "the device was away": a machine that is **shut down** writes no
 * `42`/`506` at all, so a query watching only Kernel-Power's sleep pair reports an overnight power-off as
 * time the user was present. On the calendar that draws no hatch; in `SchedulerDomain.observedNoScreenRegions`
 * it is worse — no evidence means on-screen tasks bank records straight through hours the machine was off,
 * which is exactly the ADR 0002 failure the evidence union exists to prevent. So [DOWN_IDS] carries the clean
 * shutdown (`109`, `13`, `6006`) and the unexpected one (`6008`) beside the sleep, and [UP_IDS] carries the
 * boot (`12`, `6005`) beside the wake.
 *
 * `6008` (power loss) is stamped at the **next boot**, not at the crash. The real instant is in the record's
 * own properties and [script] substitutes it — otherwise a power cut would read as a down transition a second
 * before the boot that ended it, and the whole outage would look like time spent at the machine.
 *
 * ## An event id means nothing without its provider
 *
 * Ids are only unique **within** a provider, and the System log's power events collide: `1` is Kernel-Power's
 * "the system has resumed", and it is ALSO Kernel-General's "the system time has changed" — which Windows
 * writes on almost every resume, a second after the sleep that preceded it. Asking all three providers for
 * one flat id list therefore turns every clock resync into a wake, and the observed cost is exact: a sleep at
 * 01:19:44 followed by a time-change at 01:19:45 pairs into a one-second absence, the genuine resume eight
 * hours later has nothing left to close, and the night reads as time at the desk. So [PROVIDERS] asks each
 * provider for **its own** ids only. The three sets are disjoint, which is what lets the output stay a plain
 * `<millis>,<id>` line.
 *
 * ## Debouncing
 *
 * A resume that lasts three seconds, or one wake logged by two providers a second apart, is not a state
 * change. Any flip shorter than [DEBOUNCE_MILLIS] **cancels** the transition it undid — both events are
 * dropped — and a repeat of the state already held is ignored, so [transitions] always alternates strictly.
 * Without the cancellation a `506`/`507` bounce becomes a real three-second "locked" interval, and where the
 * bounce leaves an unmatched wake a genuine eight-hour absence pairs with the wrong edge or vanishes.
 *
 * A shutdown legitimately logs several downs in a row (`109`, then `13`, then `6006`): the run counts from
 * the **first**, because that is when the user left.
 */
internal object WindowsPowerLog {
    /** A state that did not hold for a minute was jitter, never a transition. */
    const val DEBOUNCE_MILLIS = 60_000L

    /** The device became usable again: a wake from sleep/standby, or a boot. */
    val UP_IDS = listOf(1, 131, 507, 12, 6005)

    /** The device stopped being usable: sleep/standby, a clean shutdown, or a power loss. */
    val DOWN_IDS = listOf(42, 506, 13, 109, 6006, 6008)

    /**
     * Which provider owns which id — see the class comment. The value lists partition `UP_IDS + DOWN_IDS`
     * with no id in two of them, so a line the query prints identifies its transition on its own.
     */
    val PROVIDERS =
        listOf(
            "Microsoft-Windows-Kernel-Power" to listOf(1, 131, 507, 42, 506, 109),
            "Microsoft-Windows-Kernel-General" to listOf(12, 13),
            "EventLog" to listOf(6005, 6006, 6008),
        )

    /**
     * How many events before the asked window are fetched to establish the state it OPENS in. Without them a
     * window whose first event is a wake drops it — there is nothing to pair it with — and reports the machine
     * as present from the window's start until it woke, which is the exact opposite of the truth. More than
     * one because the newest may itself be cancelled by the debouncer.
     */
    const val PRIOR_EVENTS = 5

    /**
     * The PowerShell that dumps `<epochMillis>,<eventId>` for every power event, preceded by an `OK` sentinel.
     * The sentinel is what separates "read the log, nothing was recorded" from "could not read the log" — a
     * successful query with no events prints nothing either, and the two answers mean opposite things to the
     * record bank. It is printed only when every error the query raised was `NoMatchingEventsFound`: an
     * access-denied read is *not* an empty log.
     *
     * When [sinceMillis]/[untilMillis] are given the query is bounded to that window (ADR 0009 — there is no
     * reason to ask the OS about days that are not on screen) and a second, small query fetches the
     * [PRIOR_EVENTS] events before it. Both run in ONE process: a second `powershell` launch costs more than
     * the query does.
     */
    fun script(maxEvents: Int, sinceMillis: Long? = null, untilMillis: Long? = null): String {
        // PowerShell variables are written as ${'$'}name so Kotlin does not try to interpolate them.
        fun reads(start: String, end: String, max: Int) =
            PROVIDERS.joinToString("\n") { (provider, ids) ->
                "Read-Power '$provider' @(${ids.joinToString(",")}) $start $end $max"
            }
        val reads =
            if (sinceMillis == null || untilMillis == null) {
                reads("${'$'}null", "${'$'}null", maxEvents)
            } else {
                listOf(
                    "${'$'}since = [DateTimeOffset]::FromUnixTimeMilliseconds($sinceMillis).LocalDateTime",
                    "${'$'}until = [DateTimeOffset]::FromUnixTimeMilliseconds($untilMillis).LocalDateTime",
                    reads("${'$'}since", "${'$'}until", maxEvents),
                    reads("${'$'}null", "${'$'}since", PRIOR_EVENTS),
                ).joinToString("\n")
            }
        // The body is built separately from [reads] because `trimIndent` sees the interpolated string, and a
        // multi-line insertion would flatten the common indent to zero and leave the whole script indented.
        val body =
            """
            ${'$'}global:ok = ${'$'}true
            ${'$'}global:out = New-Object System.Collections.ArrayList
            function Read-Power(${'$'}provider, ${'$'}ids, ${'$'}start, ${'$'}end, ${'$'}max) {
                ${'$'}f = @{ LogName = 'System'; ProviderName = ${'$'}provider; ID = ${'$'}ids }
                if (${'$'}null -ne ${'$'}start) { ${'$'}f['StartTime'] = ${'$'}start }
                if (${'$'}null -ne ${'$'}end) { ${'$'}f['EndTime'] = ${'$'}end }
                ${'$'}err = ${'$'}null
                ${'$'}ev = Get-WinEvent -FilterHashtable ${'$'}f -MaxEvents ${'$'}max -ErrorAction SilentlyContinue -ErrorVariable err
                foreach (${'$'}x in ${'$'}err) {
                    if (${'$'}x.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*') { ${'$'}global:ok = ${'$'}false }
                }
                foreach (${'$'}e in ${'$'}ev) {
                    ${'$'}t = ${'$'}e.TimeCreated
                    if (${'$'}e.Id -eq 6008) {
                        try {
                            ${'$'}p = ${'$'}e.Properties
                            if (${'$'}p.Count -ge 2) { ${'$'}t = [datetime]::Parse('' + ${'$'}p[1].Value + ' ' + ${'$'}p[0].Value) }
                        } catch { }
                    }
                    [void]${'$'}global:out.Add('' + [long]([DateTimeOffset]${'$'}t).ToUnixTimeMilliseconds() + ',' + ${'$'}e.Id)
                }
            }
        """.trimIndent()
        return "$body\n$reads\nif (${'$'}global:ok) { 'OK' }\n${'$'}global:out"
    }

    /** How long the drain thread is given to finish once the process itself has exited. */
    private const val DRAIN_JOIN_MILLIS = 5_000L

    /**
     * Run [script] and return its lines, or **null** when the query could not be answered at all — no
     * `powershell` (a non-Windows desktop), a timeout, or a launch failure.
     *
     * **The output is drained WHILE the process runs, and that is a rule, not a style.** A child's stdout is
     * an OS pipe with a small fixed buffer (4 KB on Windows); a process that fills it BLOCKS on its next
     * write until somebody reads. Waiting for the exit before reading a word therefore deadlocks the moment
     * the answer outgrows the buffer — the query has already finished, and the query is not what timed out.
     *
     * Observed on the release machine (2026-08-30 → 2026-09-05): the 168 h window's answer crossed 4 KB as
     * the machine accumulated power events (72 h = 1.6 KB, still fine; 168 h = 4.2 KB, hung), so every scan
     * timed out after 20 s and [transitions] read the empty result as "the log cannot be read". A null there
     * means assumed-locked to the CALENDAR and no evidence at all to the record bank, so both layers hatched
     * the whole displayed past while `SchedulerDomain.observedNoScreenRegions` stayed empty — the on-screen
     * task panels went on being drawn straight across the hatch, which is the one thing that pairing exists
     * to deny. The failure grows with the log, so it can only ever get worse: never wait before reading.
     */
    fun run(script: String, timeoutSeconds: Long): List<String>? = runCatching {
        val process =
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start()
        var output: String? = null
        val drain =
            Thread { output = runCatching { process.inputStream.bufferedReader().readText() }.getOrNull() }
        drain.isDaemon = true
        drain.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        // The pipe is closed by the exit, so this returns at once; the bound is only there so a wedged
        // reader cannot hold the scan thread. `join` is what publishes the write to this thread.
        drain.join(DRAIN_JOIN_MILLIS)
        (output ?: return@runCatching null).lines().map { it.trim() }
    }.getOrNull()

    /**
     * The debounced, strictly alternating transition timeline the log described, oldest first — or **null**
     * when the output carries no `OK` sentinel, which means the log was not read and nothing at all is known.
     */
    fun transitions(lines: List<String>?): List<PowerTransition>? {
        if (lines == null || lines.none { it == "OK" }) return null
        val raw =
            lines
                .mapNotNull { line ->
                    val parts = line.split(',')
                    if (parts.size != 2) return@mapNotNull null
                    val millis = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val id = parts[1].toIntOrNull() ?: return@mapNotNull null
                    when (id) {
                        in DOWN_IDS -> PowerTransition(millis, down = true)
                        in UP_IDS -> PowerTransition(millis, down = false)
                        else -> null
                    }
                }
                // The windowed query overlaps its own prior-state query at the boundary instant, and one
                // shutdown can be logged by two providers at the same millisecond.
                .distinct()
                .sortedBy { it.millis }
        return debounce(raw)
    }

    /**
     * See the class comment: a flip that did not hold for [DEBOUNCE_MILLIS] cancels its predecessor.
     *
     * A cancellation is **provisional**, and that is what closes the class comment's "unmatched wake" case.
     * Cancelling a pair asserts the device came back at the bounce; if the very next event REPEATS the state
     * the bounce claimed to have returned to, that return demonstrably never happened — the bounce was the
     * spurious half — so the cancelled transition is put back and the repeat closes it instead. Observed
     * 2026-08-29 on the release machine: `506`@15:12:40, `507`@15:12:41, `507`@15:26:53 cancelled the sleep,
     * left the genuine resume with nothing to pair against, and lost a real 14-minute standby outright — the
     * machine then read as time spent at the desk, which is precisely the reading the record bank must not
     * get. The restored pair is still re-tested against [DEBOUNCE_MILLIS], so a genuinely brief flip stays
     * jitter.
     */
    fun debounce(events: List<PowerTransition>): List<PowerTransition> {
        val kept = ArrayList<PowerTransition>(events.size)
        // The transition the last cancellation removed, and the polarity the cancelling event claimed.
        var cancelled: PowerTransition? = null
        var cancelledBy: Boolean? = null
        for (event in events) {
            if (cancelled != null) {
                // Same polarity as the canceller ⇒ the state never returned; undo the cancellation.
                if (cancelledBy == event.down) kept.add(cancelled)
                cancelled = null
                cancelledBy = null
            }
            val last = kept.lastOrNull()
            if (last == null) {
                kept.add(event)
                continue
            }
            if (event.millis - last.millis < DEBOUNCE_MILLIS) {
                // Jitter: the pair leaves no trace *unless* the next event proves otherwise (above). A repeat
                // of the state already held is a duplicate — two providers logging one shutdown — and is
                // simply dropped, so a shutdown run still counts from its first event.
                if (event.down != last.down) {
                    kept.removeAt(kept.size - 1)
                    cancelled = last
                    cancelledBy = event.down
                }
            } else if (event.down != last.down) {
                kept.add(event)
            }
        }
        return kept
    }

    /**
     * Pair each "the device went away" with the event that ended it. An "up" with nothing to close means the
     * device was already present — the state before the timeline begins, or a wake whose sleep is older than
     * the events fetched. [openEndMillis] closes an absence still open at the end of the asked window; null
     * drops it, since nothing on record says the device ever came back.
     */
    fun intervals(transitions: List<PowerTransition>, openEndMillis: Long? = null): List<DeviceSleepGap> {
        val out = ArrayList<DeviceSleepGap>()
        var openDown: Long? = null
        for (transition in transitions) {
            if (transition.down) {
                if (openDown == null) openDown = transition.millis
            } else {
                val start = openDown ?: continue
                out.add(DeviceSleepGap(start, transition.millis))
                openDown = null
            }
        }
        val trailing = openDown
        if (trailing != null && openEndMillis != null && openEndMillis > trailing) {
            out.add(DeviceSleepGap(trailing, openEndMillis))
        }
        return out
    }
}
