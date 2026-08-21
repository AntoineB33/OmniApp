# ADR 0010 — Alarms (PRD §18): no server involvement, by design

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

## Tests

- `AlarmTest` — boundary math incl. a multi-day jump + DST, reducer, codec incl. a pre-alarms payload, and that
  alarms move the sync fingerprint.
- `AlarmEngineTest` — the phone's arming/ring/disarm, and the desktop sweep: it rings on the crossing, exactly once,
  arms no OS alarm, and stays silent for a ring the process was down for.
