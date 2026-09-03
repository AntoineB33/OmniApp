# ADR 0010 — Alarms and timers (PRD §18): no server involvement, by design

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *Alarms*.

The left-menu **Alarms** window edits `SchedulerState.alarms` (`AlarmEntry`: time of day, label, how long the
sound lasts, vibrate, the days it is triggered on, repeat/one-off, on/off) — authoritative state, so it rides the
ordinary snapshot auto-sync to every device.

## The DAYS are part of the alarm, not of the device

2026-08-06. `AlarmEntry.days` is a `Set<DayOfWeek>` defaulting to `EVERY_DAY`, every boundary in `AlarmDomain` is
gated on it (`ringsOn`), and it is synced with the rest of the entry — so every device rings on the same days and
narrowing them moves the sync fingerprint
(`AlarmTest.the_days_an_alarm_rings_on_are_synced_to_the_other_devices`).

An **empty** set never rings (`schedulable` is false) and is NOT the same as the default. The UI refuses to
deselect the last day, but a hand-edited/merged DB can still hold one.

Persisted as sorted ISO day numbers with **null ⇒ every day**, so a payload written before the field decodes
exactly as it behaved. The old `repeatDaily` flag was renamed `repeats` (it no longer implies "every day") and
decodes through `@JsonNames("repeatDaily")`.

`nextOccurrenceMillis` scans **9** local days forward, not 3 — a Monday-only alarm's next ring can be a week out.

## Every ring is drawn on the calendar

`CalendarRecord.alarm` / `PlacedRecord.alarm`, `AlarmMarker` in `CalendarUi.kt`, projected in `App.kt` by
`AlarmDomain.occurrencesInWindow` over the **displayed day span only** (ADR 0009 — cost follows the screen, and the
projection does not depend on `now`).

A zero-duration marker, inert: no check-off, no drag/resize/edit (excluded from `blockRecords` and `allBlocks`).
Being a fixed wall-clock boundary, a past ring stays where it went off instead of following the now-line like an
overdue reminder.

## Phone: each device arms its own OS-level exact alarm

From the synced list: `SchedulerEngine.launchAlarmArming` → the `scheduleDeviceAlarm` seam →
`AlarmClockScheduler` / `AlarmManager`; `AlarmClockReceiver` → `engine.onAlarmFire` → `AlarmRingService` plays the
alarm ringtone + vibrates for the configured length, then arms the next ring.

**Deliberately NOT an Edge-Function push like the pause cue:** an alarm's instant is known in advance, so local
arming rings offline / dozing / app-killed and costs no Supabase traffic. The pause cue only needs the server
because its *timing* depends on cross-device presence.

Only the **soonest** ring is armed (the receiver arms the next); a reboot re-arms via `BootReceiver` →
`SchedulerService` → `engine.start()`.

## Desktop: rings off the now-line, not off an armed OS alarm

2026-08-01. PRD §18 used to say "the desktop never rings" — it does now.

The split is about what each platform *can* promise, not about who is allowed to ring: a phone must ring with the
app killed, which only the OS can guarantee, whereas a desktop app that is not running cannot ring at all — so
there is nothing to arm and the crossing IS the trigger.

`launchAlarmArming` stays `deviceKind == Phone`; `launchAlarmSweep` is its `!= Phone` counterpart and is an
ordinary boundary sweep:

- instants from `AlarmDomain.crossingsBetween` (fixed per local day, so a leap fires each one in order);
- consecutive scans tiled by `BoundarySweep.scanFloorMillis`;
- fire/stale by REAL age alone (`ALARM_FRESH_MILLIS` = 60 s, looser than the 2-s cue budget because an alarm is
  worth hearing slightly late);
- de-duped on **(id, instant)**, because two alarms may share one instant.

It **self-delays to the next ring** — without that the production tick is 30 s (`ADVANCE_TICK_MILLIS_PROD`), so
every ring would be up to 30 s late and then swallowed by its own freshness budget.

It reuses `onAlarmFire`, so both platforms ring/disarm/log identically; the re-arm at its tail is phone-gated so the
desktop can't log an OS arming that never happened.

There is **no screen-active gate** (an alarm is exactly for a user who is away), and no Stop control on the desktop
— the ring ends by itself after the configured seconds.

## The sound: an acoustic guitar, synthesized in commonMain

Not bundled as a WAV like the §15 voice cues. An alarm has no spoken content, and a plucked steel string is exactly
what Karplus–Strong models — a seeded noise burst circulating through a delay line that loses its highs each pass.

`AlarmTone.loopPcm()` renders ONE loopable arpeggio cycle (`LOOP_MILLIS` = 2.6 s, an Am-add9 line up and back down,
16-bit LE mono at 44.1 kHz, normalized to 0.35 peak with raised-cosine fades at both edges so the loop seam cannot
click). BOTH ringing platforms write that same buffer repeatedly for the alarm's length — so every device rings with
the identical waveform, and there is no resource that could fail to load in a packaged app image.

It is deterministic (fixed noise seed), which is what lets `AlarmTest` assert it.

| Platform | Implementation |
| --- | --- |
| Desktop | `ringAlarmPlatform` (`AlarmSound.kt`, wired in `App.kt`) → `AlarmSound.jvm.kt` writes the cycle to a `SourceDataLine` on its **own** daemon thread |
| Android | common actual is inert (`SchedulerHolder` injects `AlarmRingService` directly, so this seam must never double-ring); the service plays the cycle through a `MODE_STATIC` `AudioTrack` with `setLoopPoints(…, -1)` on `USAGE_ALARM` |
| iOS / web | no-ops; iOS ringing still unimplemented like the rest of its push path |

The desktop thread is deliberately **not** the `playVoiceCue` worker, which plays each cue to completion and so
would mute every look-away cue for the length of a long ring.

Android **falls back to the device's own alarm ringtone** if the PCM track can't be created — an alarm must never
fail silently.

## The boundary instant

Built from `LocalDateTime(day, hh:mm).toInstant(tz)` — **NOT** `startOfDay + minutes`, which skews an hour on a DST
day.

Under the sim clock it is converted to the real instant it is reached at (`realInstantFor`) for the phone's OS
arming; the desktop sweep fires on sim instants directly.

## A timer is an alarm at an absolute instant

2026-08-28. The Alarms window gained a second section: **timers** (`SchedulerState.timers`, `TimerEntry`).

The whole design is one observation. An alarm's due instant is derived from the local calendar — a time of day,
on a set of weekdays, re-derived per local day so it survives DST. A timer's is *stored*: one absolute instant,
fixed the moment it was started. **Everything downstream of "when is it due" is therefore the same problem**, so
the timers reuse the alarms' machinery unchanged rather than growing a parallel one:

| | Alarm | Timer |
| --- | --- | --- |
| Due instant | `LocalDateTime(day, hh:mm).toInstant(tz)`, per ringing day | `TimerEntry.endsAtMillis`, stored |
| Boundary math | `AlarmDomain` | `TimerDomain` (no time zone, no calendar) |
| Phone | one OS exact alarm, **shared slot** | same |
| Desktop | `launchAlarmSweep` | same sweep, crossings merged |
| Ring | `onAlarmFire` → `ringAlarm` | same, titled *Timer* |
| Once it has rung | a one-off **disarms** itself | **resets** to its full duration |

`ArmedAlarm.timer` is the one bit that distinguishes them, and it travels *with* the armed ring (into the phone's
OS intent included) rather than being inferred from the `timer-{n}` id — the routing is stated, not guessed.

The other consequence of the stored instant is that the time left is a thing the user can **hand back**: an alarm's
due instant is a wall-clock fact and there is nothing to retype into it, whereas a timer's is just a number, so the
countdown is a set of input fields (below).

### One OS slot, so one arming loop and one sweep

`AlarmClockScheduler` arms exactly one alarm at a time, under a fixed request code. A second arming loop for the
timers would therefore not add a ring — it would *overwrite* whichever the first loop had put there, and the phone
would ring for whatever was recomputed last. So `launchAlarmArming` combines both lists and arms the soonest of
the two, and `launchAlarmSweep` merges both crossing streams (`ringCrossingsBetween`) in boundary order. The two
id namespaces are disjoint, so the sweep's `(id, instant)` de-dupe key cannot collide.

### The run state is authoritative; the countdown is derived

CLAUDE.md's state rule decides the split, and it decides it both ways at once:

- **`endsAtMillis` is authoritative** — it cannot be recomputed from any other persisted field, so it is persisted
  *and synced*. That is also what makes the feature behave the way PRD §18 already promises for alarms: starting a
  timer on the desktop is what makes the phone ring at its end.
- **The remaining time is derived** — `endsAtMillis` minus the now-line (`remainingAtMillis`). Nothing is written
  as a timer counts down, so a running timer cannot move the sync fingerprint on a tick, and there is no per-tick
  cost anywhere (ADR 0009).

Three states are held in two nullable fields (`endsAtMillis` running, `remainingMillis` paused, both null idle) of
which **at most one is ever non-null**. Both halves are synced, so a per-field merge could forge a row holding
both, and so could an older or hand-edited payload — `TimerDomain.healed` is the single place that invariant is
applied, from `decode`, from `SnapshotMerge` and from the reducer.

### Why no on/off switch, and no repeat switch

A timer that is not running is already not due — an idle row is not a silenced one, so there is nothing for a
switch to say. And a timer is a one-off *by nature*: having run out it resets to its full duration, ready to be
started again. A one-off alarm disarms itself instead precisely because it *has* a switch, and leaving it off is
how the row survives the ring without coming round again tomorrow.

### The countdown's clock is the window's own

The engine's now-line advances once per production tick (`ADVANCE_TICK_MILLIS_PROD`, 30 s) — the schedule's
cadence, and useless for a countdown. So `AlarmWindow` polls the app clock itself every 250 ms, but **only while
it is open and something is actually running**. That is display-only Compose state, like the calendar's zoom: it
is not persisted, not synced, and it schedules nothing. The three transitions dispatch `clock.nowMillis()` rather
than the display now-line, so a timer started at 17:00:00.4 ends five minutes after *that*, not after the
quantized instant the calendar happens to be drawn against.

### The countdown is an input, and an edit is a SHIFT, not a rewrite

2026-09-04. The countdown was a read-only readout, so the only way to change how much was left was Reset + retype
the duration + Start — which throws away what has already elapsed and re-answers a question the user did not ask
("how long is this timer", when they meant "how much longer"). It is now **three fields** — hours, minutes,
seconds — plus six ± second buttons, and the intents behind them are the exact mirror of the settings push:

| | `SetTimers` | `SetTimerCountdownField` / `NudgeTimerRemaining` |
| --- | --- | --- |
| Carries | label, duration, ring length, vibration | one countdown component / one delta |
| Must not disturb | the instant it is due at | any of the settings |

**An edit moves the countdown by that component's own unit.** `TimerDomain.withCountdownField` shifts the due
instant by `(value − shown component) × unit`; it does not rewrite the countdown. That single choice is what makes
the feature what was asked for: setting the **hours** leaves the minutes and seconds exactly where they were and
still falling, setting the **minutes** leaves the seconds falling. A rewrite would have restarted the sub-component
at zero, which is "restart it at 2 hours" rather than "make it 2 hours". Each keystroke is measured against the
**live** value, so typing `12` into the minutes — which commits `1`, then `12` — self-corrects and lands on 12.

**Three fields, not one.** A single `H:MM:SS` field cannot express any of this: an edit to it is a whole new
countdown, so there is nothing left to keep running, and the caret would be fighting a string that changes four
times a second. Splitting the readout is what gives each component its own draft and its own untouched neighbours.

**Why the seconds stop it, and why the ± buttons exist.** The seconds are the digit that is itself reading down, so
a value typed into a running timer is consumed by the very next tick — there is no way to *set* it while it moves.
So `TimerField.SECONDS` **pauses** the row (the window's Pause becomes Resume) and snaps the countdown to the whole
second typed, which is what makes it stick. That is a real cost, and `NudgeTimerRemaining` (`TimerDomain.nudged`,
−10/−5/−1/+1/+5/+10 s) is what pays it: the seconds moved *without* stopping. The two are a pair — making the
seconds field silently non-stopping would leave the typed value not sticking, and dropping the buttons would leave
the seconds unreachable on a running timer.

Both go through the same `withRemaining` primitive, written **in each state's own currency** rather than as a
stop-and-restart: a **running** row's time left *is* `endsAtMillis`, so it moves and the row stays running; a
**paused** row's is the banked `remainingMillis`, so that is written and the row stays paused. Neither ever crosses
into the other's field, which is what keeps the three-state invariant true without `healed` having to catch it. A
nudge past zero leaves a running timer due *now*, so it rings — the honest answer to "take ten more seconds off a
countdown with three left", and the same clamp every other write goes through.

An **idle** row is returned unchanged, and that is deliberate: it is not counting down, and the number its
countdown shows is its `durationSeconds` — which the Duration field on the same row already edits. Two fields
writing one number by two different routes is exactly the drift this codebase keeps deleting, so the fields are
`readOnly` and the buttons disabled while idle. (The alternative — routing an idle edit to the duration — would
have made a run-state write silently edit a *setting*, which is the one thing the table above exists to prevent.)

The window's half is a **draft**, and there is exactly **one per row**, naming the field it belongs to — one,
because only one field can hold the focus. A draft at all because the live countdown changes four times a second
(the 250 ms poll above), so a field bound straight to it cannot be typed into: every tick overwrites the keystroke.
It is seeded on focus and dropped on focus lost, guarded on still being that field's since Compose may report the
gain before the loss when the focus moves between two of them. **The fields not holding it go on reading down**,
which is what "editing the hours does not stop the minutes and seconds" actually looks like. Display-only Compose
state, like the poll itself. Each keystroke that parses commits, like every other field in this window; one that
does not shows the error state until it does. `parseCountdownComponent` is its own parser (a bare number bounded by
what the component can hold) and deliberately not `parseDurationSeconds`, which still reads the whole `H:MM:SS`
written into the row's **Duration** setting. The dispatch carries `clock.nowMillis()`, not the display now-line,
for the same reason the three transitions do.

The arming and the sweep need nothing: `launchAlarmArming` already re-runs on every `state.timers` change, so a
retyped or nudged countdown re-arms the phone's OS slot by itself.

### On the calendar too — the same marker, one bit apart

*Superseded 2026-08-29.* This originally read "deliberately not on the calendar": an alarm is a fact about the
user's week, whereas a timer exists only between a start and a ring, so a mark for an instant that moves on every
press seemed to say nothing about how the week is spent. That is the wrong way round — the thing a running timer
*is* is an instant it will go off at, and the calendar is where the app says when things go off. So a running
timer now draws the alarms' marker, and the objection is simply its behaviour: it is there while the timer runs,
it moves when the row is restarted, and the ring resets the row so nothing is left behind.

It is the alarms' path unchanged, not a second one. `CalendarRecord.alarm` means "this is a ring"; the new
`CalendarRecord.timer` / `PlacedRecord.timer` beside it says *which sort*, mirroring `ArmedAlarm.timer` — one bit,
travelling with the record rather than inferred from the `timer-{n}` id, deciding the icon (⏳ against the alarm's
⏰) and nothing else. Everything the alarm marker already gets is therefore free and cannot drift: the fixed
height, the stacking sweep, the inertness, and the exclusions from `blockRecords` / `allBlocks` / the drag snap
set / the task-panel menu entries.

`TimerDomain.occurrencesInWindow` is the projection, bounded by the displayed span like the alarms' (ADR 0009) and
likewise independent of `now`. It is a *filter*, not a per-day walk: a timer's instant is stored, so it has at most
one occurrence, and only while it is running — an idle or paused row has no instant to draw at all.

The label falls back to the timer's **duration** where an alarm's falls back to its time of day, each being the
thing that row is. That spelling is `TimerDomain.formatDuration` / `formatCountdown`, which the Alarms window's own
countdown column now delegates to, so the marker and the window cannot describe one timer two ways.

## Tests

- `AlarmTest` — boundary math incl. a multi-day jump + DST, reducer, codec incl. a pre-alarms payload, and that
  alarms move the sync fingerprint.
- `AlarmEngineTest` — the phone's arming/ring/disarm, and the desktop sweep: it rings on the crossing, exactly once,
  arms no OS alarm, and stays silent for a ring the process was down for.
- `TimerTest` — the three states and the transitions between them, that only a running timer is due, half-open
  crossings, what the calendar draws (closed-at-the-start display window, running rows only, the duration
  fallback), the healing of a payload holding both run fields, a pre-timers payload, and that the settings *and*
  the start move the sync fingerprint while counting down moves nothing. For the countdown: that the split agrees
  with `formatCountdown` digit for digit; that setting the hours or the minutes SHIFTS the due instant and leaves
  the finer components reading down (asserted a second later, with a non-zero sub-second phase so a rewrite would
  show as a jump); that setting the seconds pauses the row and snaps it, and Resume continues from there; that the
  ± buttons move a running row without stopping it and a paused one without starting it; that a nudge past zero
  leaves it due now; that no countdown edit touches an idle row or the row's settings; and the clamp.
- `TimerEngineTest` — the join: one OS slot serving both lists (soonest wins, either way round), an idle/paused
  timer arming nothing, the desktop ringing a timer and an alarm in boundary order, and a rung timer resetting.
