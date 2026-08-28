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

### Deliberately not on the calendar

An alarm's rings are drawn (above); a timer's are not. An alarm is a fact about the user's week and belongs on it,
whereas a timer exists only between a start and a ring — a mark for an instant that moves every time the row is
restarted would say nothing about how the week is spent, and would have to be erased and redrawn on every press.

## Tests

- `AlarmTest` — boundary math incl. a multi-day jump + DST, reducer, codec incl. a pre-alarms payload, and that
  alarms move the sync fingerprint.
- `AlarmEngineTest` — the phone's arming/ring/disarm, and the desktop sweep: it rings on the crossing, exactly once,
  arms no OS alarm, and stays silent for a ring the process was down for.
- `TimerTest` — the three states and the transitions between them, that only a running timer is due, half-open
  crossings, the healing of a payload holding both run fields, a pre-timers payload, and that the settings *and*
  the start move the sync fingerprint while counting down moves nothing.
- `TimerEngineTest` — the join: one OS slot serving both lists (soonest wins, either way round), an idle/paused
  timer arming nothing, the desktop ringing a timer and an alarm in boundary order, and a rung timer resetting.
