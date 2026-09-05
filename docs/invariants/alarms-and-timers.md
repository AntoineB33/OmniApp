# Alarms and timers

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Alarms and timers

→ ADR 0010. **No server involvement, by design** — an alarm's instant is known in advance, so local arming
rings offline/dozing/app-killed. The pause cue needs the server only because its *timing* depends on
cross-device presence.

- The **days** are part of the alarm and are synced. An empty set never rings and is not the default.
- The phone arms its own OS exact alarm (soonest only; the receiver arms the next). The desktop **rings off
  the now-line** via an ordinary boundary sweep — it cannot arm what it isn't running for.
- The sweep **self-delays to the next ring**, de-dupes on **(id, instant)**, and has no screen-active gate.
- The boundary is `LocalDateTime(day, hh:mm).toInstant(tz)` — **not** `startOfDay + minutes`, which skews on
  DST days.
- Every ring is drawn on the calendar as an inert zero-duration marker, projected over the displayed span
  only.
- The tone is synthesized in commonMain (`AlarmTone.loopPcm()`, deterministic) so every device rings
  identically with no loadable resource. Android falls back to the system alarm ringtone if the PCM track
  fails — an alarm must never fail silently. The desktop uses its own thread, never the voice-cue worker.

### A timer is an alarm at an ABSOLUTE instant, and that is the whole difference

The Alarms window's second section (`SchedulerState.timers`, `TimerEntry`, `TimerDomain`). An alarm's due
instant is derived from the local calendar per ringing day; a timer's is **stored** — one instant, fixed when
it was started. Everything after "when is it due" is the alarms' machinery **unchanged**: do not grow a second
arming loop, a second sweep, a second ring path or a second notification funnel.

- **One OS slot ⇒ one arming loop and one sweep.** `AlarmClockScheduler` arms exactly one alarm under a fixed
  request code, so `launchAlarmArming` combines both lists and arms the **soonest of the two**, and
  `launchAlarmSweep` merges both crossing streams (`ringCrossingsBetween`) in boundary order. A second loop
  would not add a ring — it would overwrite the first's. Ids are disjoint (`alarm-{n}` / `timer-{n}`), so the
  sweep's `(id, instant)` de-dupe key cannot collide.
- **`ArmedAlarm.timer` is the one distinguishing bit, and it TRAVELS with the armed ring** — into the phone's
  OS intent included — never inferred from the id. It decides two things and nothing else: reset-vs-disarm,
  and whether the notification is titled *Timer* or *Alarm*.
- **`endsAtMillis` is authoritative; the remaining time is DERIVED.** The instant cannot be recomputed from
  anything else, so it is persisted **and synced** — which is what makes "it rings on every device of the
  account" true of a timer started on the desktop. The countdown is `endsAtMillis` minus the now-line
  (`remainingAtMillis`), so a running timer writes nothing and can never move the fingerprint on a tick.
- **Three states, two nullable fields, AT MOST ONE non-null** (running / paused / idle). Both are synced, so a
  per-field merge — or an older payload — can forge a row holding both; **`TimerDomain.healed` is the single
  place that invariant is applied**, from `decode`, from `SnapshotMerge` and from the reducer.
- **No on/off switch and no repeat switch.** A timer that is not running is already not due (an idle row is not
  a silenced one), and a timer is a one-off by nature: having rung it **resets** to its full duration. A
  one-off *alarm* disarms itself instead precisely because it has a switch to leave off.
- **Editing a row's settings must not disturb the instant it is due at, and a countdown edit must not touch
  the settings.** One rule, said both ways. `SetTimers` carries the settings; the run state moves only through
  `StartTimer` / `PauseTimer` / `ResetTimer` / `SetTimerCountdownField` / `NudgeTimerRemaining`, which take
  `nowMillis` as an argument so the reducer stays pure — and the window's local row copy deliberately holds no
  run state.
- **THE COUNTDOWN IS THREE INPUT FIELDS, AND AN EDIT IS A SHIFT BY THAT COMPONENT'S OWN UNIT** — never a
  rewrite of the countdown (`TimerDomain.withCountdownField`, the one rule behind `SetTimerCountdownField`).
  That is the whole of why **the finer components carry on reading down through the edit**: setting the hours
  moves the due instant by `(value − hours) × 1 h`, so the minutes and seconds underneath do not so much as
  jump; setting the minutes leaves the seconds running. "Make it 2 hours" and "restart it at 2 hours" are
  different answers and only the first is the one asked for. Each keystroke is measured against the **live**
  value, which is what makes typing `12` into the minutes (committing `1`, then `12`) land on 12 and not 13.
- **`SECONDS` is the ONE edit that stops it, and that is not an inconsistency — it is the reason the ± buttons
  exist.** The seconds are the digit that is itself reading down, so a value typed into a running timer would
  be consumed by the very next tick; there is no way to *set* it while it moves. So that edit **pauses** the
  row (Pause becomes Resume) and snaps the countdown to the whole second typed, which is what makes it stick.
  `NudgeTimerRemaining` — `−10s / −5s / −1s / +1s / +5s / +10s`, `TimerDomain.nudged` — is how the seconds move
  **without** stopping, and it is the only reason both exist. Do not make the seconds field silently
  non-stopping (the value would not stick) and do not drop the buttons (the seconds would be unreachable while
  running).
- **Each write goes through `withRemaining`, in the state's OWN currency.** A **running** row's time left is
  `endsAtMillis`, so it moves and the row **stays running**; a **paused** row's is the banked `remainingMillis`,
  so that is rewritten and the row **stays paused**. Neither ever writes the other's field, which is what keeps
  the three-state invariant true without `healed` catching it. An **idle** row is returned unchanged and the
  fields are `readOnly` there: its countdown *is* its `durationSeconds`, which the Duration field beside it
  edits, and two fields writing one number by two routes is the drift this codebase keeps deleting.
- **The row holds ONE DRAFT, naming the field it belongs to** (`draft`, seeded on focus and dropped by
  `onFocusChanged` — only if it is still that field's, since Compose may report the gain before the loss). One,
  because only one field can hold the focus; and a draft at all because the live countdown changes four times a
  second, so a field bound straight to it cannot be typed into — every tick overwrites the keystroke. **The
  fields NOT holding the draft go on reading down**, which is what "editing the hours does not stop the minutes
  and seconds" looks like on screen. Display-only Compose state, like the poll below it; each keystroke that
  parses commits, one that does not shows the error state, so a half-typed value never reaches the state.
  Nothing downstream needs a change: `launchAlarmArming` already re-runs on every `state.timers` change.
- **The countdown's clock is the window's own**: the engine's now-line ticks once per 30 s production tick, so
  `AlarmWindow` polls `clock.nowMillis()` itself every 250 ms — **only while it is open and something is
  running**. Display-only Compose state, like the calendar's zoom. The transitions dispatch the clock's
  instant, not the quantized display now-line.
- **A running timer draws the SAME marker an alarm does**, on the same path — `CalendarRecord.alarm` is "this
  is a ring", and `CalendarRecord.timer` beside it is the one bit that says which sort, exactly as
  `ArmedAlarm.timer` does for an armed ring. It decides the icon (⏳ / ⏰) and nothing else; never fork the
  marker, the stacking sweep or the block exclusions on it.
  - **A timer marks the calendar at most ONCE, and only while it is running.** Its instant is stored, not
    derived per ringing day, so `TimerDomain.occurrencesInWindow` is a filter and not a walk; an idle or
    paused row has no instant, and a ring **resets** the row, so nothing is left behind afterwards. That is
    the whole of the difference — an alarm is a fact about the user's week, a timer exists between a start
    and a ring, and the calendar shows it for exactly that long.
  - The label falls back to the timer's **duration** where an alarm's falls back to its time of day — the
    thing each one is. `TimerDomain.formatDuration` / `formatCountdown` are that spelling, and they are the
    Alarms window's own: the window delegates to them so the two readouts cannot disagree.

---

