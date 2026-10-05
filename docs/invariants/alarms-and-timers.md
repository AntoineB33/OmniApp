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
- **Which of the two is decided by the seam, not the device kind** (`SchedulerEngine.hasOsAlarmClock`): a phone
  that was handed an OS arming seam (Android) arms; every other device — iOS, which is a phone with no seam yet,
  and the browser — sweeps. Gating on `DeviceKind.Phone` alone left iOS ringing nothing (2026-10-04).
- The sweep **self-delays to the next ring**, de-dupes on **(id, instant)**, and has no screen-active gate.
- The boundary is `LocalDateTime(day, hh:mm).toInstant(tz)` — **not** `startOfDay + minutes`, which skews on
  DST days.
- Every ring is drawn on the calendar as an inert zero-duration marker, projected over the displayed span
  only — and **inert is not silent**: it names itself in the hover bubble over what it hides (below).
- The tone is synthesized in commonMain (`AlarmTone.loopPcm(sound)`, deterministic) so every device rings
  identically with no loadable resource. Android falls back to the system alarm ringtone if the PCM track
  fails — an alarm must never fail silently. The desktop uses its own thread, never the voice-cue worker.

### A new alarm, timer or reminder is built in ONE place, from the account's default

`scheduler/domain/NewElementDefaults.kt`. `SchedulerState.newAlarmDefaults` / `newTimerDefaults` /
`newReminderDefaults` are whole elements of their kind, but settings only — the setters (`SetNew…Defaults`)
and the codec strip the id, the name, the time of day and a timer's run. Every creation path goes through
`NewElementDefaults.newAlarm/newTimer/newReminder`: the windows' "+ New …" / "+ Add …" (`AlarmWindow`'s
`addAlarm`/`addTimer`, the reminder window's `newRow`) and the calendar's "add…" (the new alarm draft in
`seedDraft`, the saved alarm's base in `applyAlarmDrafts`). A new creation path that builds an element from
`AlarmEntry(id = …)` directly is the second funnel this exists to prevent.

- The defaults are **authoritative account settings**, synced as one field row each and merged as whole values
  (`SnapshotMerge`); like the deep-copy depth they are not Undo/Redo units.
- They are edited in the element's own editor in its settings-only mode (`AlarmWindow(defaults = true)`,
  `ChoresManagerWindow(defaults = true)`, one row under `DEFAULT_CONFIGURATION_ROW_ID`), a per-object window of
  its own (`ObjectWindowKey.Kind.AlarmDefaults` / `TimerDefaults` / `ReminderDefaults` — ☆ and kept across
  restarts), opened from the link at the foot of any alarm's, timer's or reminder's own window.

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
- **`ArmedAlarm.kind` is the one distinguishing bit, and it TRAVELS with the armed ring** — into the phone's
  OS intent included — never inferred from the id. It decides reset-vs-disarm and what the ring is called
  (*Alarm* / *Timer* / *Reminder*), and nothing else; `timer` is derived from it, never stored beside it.
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
- **"Below zero" (`TimerEntry.goesNegative`) is the ONE setting that moves the run, and only to leave the row
  as if it had always been set so** (`TimerDomain.withGoesNegative`, applied by the window's push). On: the ring
  (`TimerRang` → `TimerDomain.rang`) leaves the row running and its countdown reads below zero (`−0:07`); it is
  never armed again, its instant being behind the clock. Off: the ring resets the row as ever **but keeps the
  instant it reached zero** (`endedAtMillis`, authoritative, persisted and synced, cleared by any new run and by
  Reset) — so turning the option on afterwards runs it again from that instant, and turning it off past zero puts
  it back to that rung state. The ring reads the engine clock, never the ring's instant (the phone's is converted
  to real time for the OS); a row due later than that clock is a later run, and keeps no instant. The countdown's
  split is signed (`TimerCountdown.negative`): the fields hold the magnitude, the sign stands before them, and an
  edit moves in the direction the countdown reads.
- **Editing a row's settings — its alert block included — must not disturb the instant it is due at, and a
  countdown edit must not touch the settings.** One rule, said both ways. `SetTimers` carries the settings;
  the run state moves only through
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
  Only a **running** row is stopped by it — a paused or idle one has no countdown to stop and simply banks the
  snapped value, through the same primitive as every other write.
  `NudgeTimerRemaining` — `−10s / −5s / −1s / +1s / +5s / +10s`, `TimerDomain.nudged` — is how the seconds move
  **without** stopping, and it is the only reason both exist. Do not make the seconds field silently
  non-stopping (the value would not stick) and do not drop the buttons (the seconds would be unreachable while
  running).
- **Each write goes through `withRemaining`, in the state's OWN currency.** A **running** row's time left is
  `endsAtMillis`, so it moves and the row **stays running**; a **paused** row's is the banked `remainingMillis`,
  so that is rewritten and the row **stays paused**. Neither ever writes the other's field, which is what keeps
  the three-state invariant true without `healed` catching it.
- **An IDLE row's countdown is editable too, and editing it makes the row PAUSED — which is why the button
  then reads *Resume*.** Setting up how long this run is to be before pressing anything is the ordinary way to
  use a timer, and a countdown dialled in but not started *is* a held one: there is no fourth state to invent
  for it, and *Start* would be claiming the duration is what runs when it is not. The ± buttons work there for
  the same reason. **The two numbers stay one each**: `durationSeconds` is the *setting* the Duration field
  beside the countdown edits, it is what `reset` returns to and what a start from a genuinely idle row takes,
  and no countdown edit ever writes it — two fields writing one number by two routes is the drift this
  codebase keeps deleting. The one exception is an idle row retyped as the number it was already showing: it
  banks nothing and stays idle, so a value retyped as it was never turns *Start* into *Resume*. A **paused**
  row is not normalised back the other way — nudged onto its duration exactly it stays held, because a paused
  row is always written in its own currency.
- **The row holds ONE DRAFT, naming the field it belongs to** (`draft`, seeded on focus and dropped by
  `onFocusChanged` — only if it is still that field's, since Compose may report the gain before the loss). One,
  because only one field can hold the focus; and a draft at all because the live countdown changes four times a
  second, so a field bound straight to it cannot be typed into — every tick overwrites the keystroke.
  **The draft HOLDS the countdown as it stood at focus (`CountdownDraft.held`): the edited field and every
  field to its LEFT show it and stay still; the fields to its RIGHT go on reading down**, which is what
  "editing the hours does not stop the minutes and seconds" looks like on screen. The coarser fields are held
  too because the seconds wrapping would otherwise take a minute off the minutes beside the caret. **A commit
  is measured against the held numbers** (`SetTimerCountdownField.held` → `withCountdownField`), never the
  live ones, so what lands is what the user saw. **LEAVING the field lands it too, typed in or not**
  (`TimerRowEditor.setDraft`, `TimerDomain.heldDriftMillis`, anomaly 2026-10-04): the held fields stood still while
  the timer ran on, so each wrap of the seconds took a minute off the state that the screen still showed; dropping the
  draft snapped the field back to it. On the way out the time left is moved by what drifted — through
  `NudgeTimerRemaining`, so a running row keeps running, and nothing is written when nothing drifted (an idle or paused
  row, a field left before the next wrap). The same when the caret goes straight to another field of the row, which
  then holds the countdown as it was shown. Display-only Compose state, like the poll below it; each
  keystroke that parses commits, one that does not shows the error state, so a half-typed value never reaches
  the state. Nothing downstream needs a change: `launchAlarmArming` already re-runs on every `state.timers`
  change.
- **A right-click on a countdown field nudges by THAT field's unit** (±1/5/10 s, min or h), through the same
  `NudgeTimerRemaining` as the ± buttons, **stays open when an entry is picked** (so a step repeats click after
  click; it leaves on the first press outside it), and replaces the text field's own cut/copy/paste menu. The timer's
  **run** in the Search window (the actions section's "Run", one block per added timer:
  `AlarmWindow(embeddedSubjects, embeddedRunOnly = true)` → `TimerRowEditor(runOnly = true)`) is the countdown's three
  fields, start / pause / reset, the menu only — no ± buttons — and an **Elapsed** read-only field. No setting is drawn
  there: the settings are that section's other actions, one shared field each. (2026-10-04: "Run" had been left with
  the three buttons alone when the settings became shared fields, and the countdown and Elapsed were on no screen of
  the Search window at all — `AddedActionHandlers.alarmEditor` was built and never drawn.) Elapsed is the countdown in reverse (`TimerDomain.elapsedMillis`: the run's length − the
  countdown AS SHOWN; 0 when idle; negative once pushed above the run's length). **It mirrors the fields, not the
  live countdown**: while a field holds the caret, Elapsed counts against `TimerDomain.displayedCountdown` (the
  held fields, what is typed) — the same function the fields draw from — so it never runs on beside a
  countdown that reads as stopped.
- **The run's length is `TimerEntry.runMillis`, fixed as the timer leaves idle** (start, or a countdown dialled
  in before it) to the duration of that moment, and cleared by Reset (the ring included). So a change to the
  countdown shows in Elapsed **mirrored**, and editing the Duration — what Reset goes back to — **does not
  reach it**. Authoritative (it cannot be re-derived once the duration moved): persisted and synced with the
  timer; a payload without it (a run started by an older build) reads against the duration.
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
  - **A marker is inert, NOT silent: it names itself in the hover bubble** (`alarmBubbleSection`,
    `AlarmMarker`'s own `CalendarHoverTiles` — `docs/invariants/calendar.md`). Inert means nothing to check
    off, drag or edit; it does not mean the one question a ring exists to answer goes unanswered. It shipped
    silent precisely *because* it is inert — a §14 tag left silent shows up at once as a bubble naming
    nothing, whereas a ring registers no pointer input, so the tiles under it went on reporting and the
    bubble named the task panel beneath, looking right while never mentioning the ring the cursor was on.
    The section carries the ring's **instant** (not the position the stacking sweep pushed the marker to) and
    the **icon**, through the one `alarmMarkerIcon` the marker itself reads: the icon is the only thing that
    tells an alarm from a timer, so a bubble without it would say less than the marker it stands in for.

### Both lists are Undo/Redo history, and the run state is not

→ `docs/invariants/task-tree.md` for the four-stack architecture the units live on.

- **Everything the user does to either LIST is one Main History Unit** — a row added, a row struck off with
  the bin, and every settings field of both sections (`AlarmsDelta` / `TimersDelta`, committed by
  `reduceSetAlarms` / `reduceSetTimers`). Ctrl+Z in this window, therefore, and never Alt+←/→: that pair
  walks the focused window's *selections*, and this window has none.
- **The run-state writes the USER makes are units** (user rule 2026-10-01, *"history units for almost every user
  action"*, reversing the earlier "not units" rule): `StartTimer` / `PauseTimer` / `ResetTimer` /
  `SetTimerCountdownField` (one unit per field for the run of its keystrokes) / `NudgeTimerRemaining`, and the chronos'
  three. Accepted with them is what that rule warned of: they write `endsAtMillis`, an ABSOLUTE instant, so undoing a
  pause long after restores an instant that may be past — the timer then rings on the spot.
- **Nothing the app authors itself is a unit**: the ring (`TimerRang`), the engine disarming a one-off that has rung
  (`SetAlarmEnabled` — the row's own switch is a *setting* and travels through `SetAlarms`, which is
  undoable), the reset after a ring, `healed`, a peer's pull. A machine-authored unit would sit on top of the
  Main stack and turn the next Ctrl+Z into "un-ring that".
- **A live-edited field is ONE unit per focus session.** The window pushes its whole list on every keystroke,
  so the unit carries a `Delta.coalesceKey` (`"{rowId}/{field}@{epoch}"`, minted when the field takes the
  focus) and `commitDelta` merges it onto the unit at the pointer when the keys match, keeping that one's
  `before` side. A structural change carries no key, so a switch flipped while a field still holds the focus
  can never be absorbed into that field's unit. The key is never persisted — a unit reloaded from the DB has
  closed its gesture.
- **The window's local row copies must re-seed from an outside change.** They hold unparsed text (`"7:"`), so
  they cannot simply follow the incoming list — every keystroke would be overwritten by the round-trip of its
  own push. They re-seed exactly when the incoming list is not the one this window last pushed; for the
  timers that comparison is the **settings only**, so a start never reformats a half-typed duration. Without
  it an undone deletion stays on screen and is pushed back at the next keystroke.
- **The window catches Ctrl+Z / Ctrl+Y itself and claims `AppWindow.Alarms`.** The chord lives on the tree's
  and the calendar's key handlers, and there is no app-level one — a keystroke aimed at a floating window
  reaches nobody. And `contentCategory` only lands on `Main` while the focus is on neither an Edit session nor
  the calendar, so the window has to own the app-wide focus or its units are skipped.
- **It claims the keyboard when it opens and RECLAIMS IT ON EVERY PRESS INSIDE IT** — the calendar's rule,
  through the same `raiseOnPress` that raises the window, and unconditionally focusable like the calendar is.
  Claiming it once is not enough, **and the bin is the proof**: a press on a row's bin destroys the row and
  with it whichever of its fields held the focus, so a window that only grabbed focus when it became the
  front one is left holding none — and the very Ctrl+Z that would undo the deletion reaches nobody. That is
  not hypothetical; it shipped, and the History rows showed the units committed with the pointer never
  moving. Do not gate either the `focusable()` or the reclaim on "is this the focused window".

### A chrono counts up from an instant, and rings at nothing

The Alarms window's third section (`SchedulerState.chronos`, `ChronoEntry`, `ChronoDomain`). A stopwatch: no
arming, no sweep, no calendar marker, no alert block.

- **`startedAtMillis` is authoritative, the time shown is derived** (`elapsedAtMillis` = `bankedMillis` + the
  time since the start) — the timers' rule, so a running chrono writes nothing and reads the same on every device.
- **The list is a Main History Unit (`SetChronos` → `ChronosDelta`), the run is not** (`StartChrono` /
  `PauseChrono` / `ResetChrono`) — the timers' rule and reason. Undo is three-way like every list: undoing the add
  of a chrono started since keeps it.
- A chrono is edited, like an alarm and a timer, from the Search window holding it (its row's "Edit" action:
  `AlarmWindow(embeddedSubjects)`, user rule 2026-10-01 — the element's own window was removed), opened from its
  Search row; a ☆ made for its old window opens that. There is no default configuration: a chrono has nothing to
  configure but its name.

## One alert block, three kinds of row

→ PRD §11. `model/AlertSettings.kt`, `ui/AlertSettingsEditor.kt`.

**An alarm, a timer and a reminder each carry one `AlertSettings`** — sound (and which of `AlertSound`'s five),
voice, notification, vibrate. One type and one editor for all three, because it is one question asked of all
three; a second spelling of "does this one vibrate" per kind is the drift this file exists to prevent.

- **The four channels are independent, and each is ASKED SEPARATELY at the ring.** `onAlarmFire` calls the ring
  seam only for a row that `rings` (sound or vibration), and hands `alert` to `notifyUser`, which gates the
  posted and the spoken halves on it. The row's switches **narrow** the account's `notificationsEnabled` /
  `notificationVoiceEnabled`; they can never make a muted account speak.
- **A row with every channel off is not schedulable** — nothing to arm an OS slot for. It is a silenced row,
  exactly like one with an empty `days` set, and the Android receiver drops such a ring too.
- **The sound set is a closed enum, never a path or a system ringtone id**, and is synthesized for the reason
  the guitar always was: the choice is account data that must sound identical on every device, offline. Adding
  a sound is adding a recipe to `AlarmTone`, never a resource. On the wire it is the enum's **name** (an
  ordinal would silently re-point every alarm the day the list is reordered), and an unknown name decodes to
  the default rather than to silence.
- **The whole block travels with the armed ring**, into the phone's OS intent included (`EXTRA_SOUND` blank =
  sound off), for the same reason the length always did: what rings must be what was armed.
- `ArmedAlarm.kind` (`RingKind`: Alarm / Timer / Reminder) is the one distinguishing bit and `timer` is now
  **derived from it**. It decides three things and nothing else: reset-vs-disarm, the notification's title, and
  what the phone's ring service calls itself.

## A reminder is announced by the SWEEP, never armed

→ PRD §14, `docs/invariants/calendar.md` for the tags themselves.

A reminder falls due like an alarm and alerts through the same block and the same ring seam — but it is **not**
an alarm, and the two differences are load-bearing:

- **It is never armed with the OS.** A device has one alarm slot and the alarms and the timers own it
  (`launchAlarmArming` arms the soonest of exactly those two). A reminder is announced from the ordered cue
  sweep, like a screen break, so it reaches a running device (the phone's foreground service included) and a
  slept-through one is **not** replayed — the overdue tag riding the now-line is that answer.
- **Its occurrence source is the TAG the calendar draws** (`SchedulerDomain.reminderCueOccurrencesBetween` over
  `state.panels`), not a second reading of the recurrence. A reminder's placement is dispersed, constrained by
  another reminder, anchored on the last completion, and sometimes placed by hand: a second derivation of that
  would announce it at an instant the calendar does not draw it at. A **checked** tag is a completion and is
  never announced.
- **The de-dupe key is the tag's panel id, not its instant.** A reminder with no time of day is re-placed at
  the current time on every regeneration, so an instant key would announce it again each time; the id
  (`chore/{reminderId}/{offset}`) is the same tag across all of them.
- The sweep **self-delays to the next tag** like it does to the next break, and a crossing is swallowed past
  the alarms' real-age budget (`ALARM_FRESH_MILLIS`), not the look-away's — a reminder is worth saying a few
  seconds late.
- A tag whose reminder is no longer a manager row still alerts, with `AlertSettings.REMINDER`: that is a
  hand-placed tag, and it is the one the user was most deliberate about.

---

### Quotas

→ `scheduler/domain/QuotaDomain.kt`, `ui/QuotaEditors.kt`, user rule 2026-10-03. A **quota** (`QuotaEntry`,
`SchedulerState.quotas`) is an amount, with a unit, to reach over a loop of time, and the pace it should be reached
at. It lives in the Search window only: a kind of element (`SearchDomain.Kind.Quota`), made from its creation row, its
actions being its editor.

- **The loops.** Loop 0 runs from `startMillis` to `endMillis`; while the quota `repeats`, loop `k` is that one moved
  by `k` lengths. A `QuotaLoop` says what is particular to ONE loop: its own start and end (both, or the regular
  ones), its `amountFactor` (2 — two times more quota) and its `renewals` (`QuotaDomain.loop`, `loopAt`: a loop whose
  own bounds hold the instant first, else the regular one by index).
- **The target progression is DERIVED and never stored** — a quota writes nothing as time passes. It is the share of
  the loop's time elapsed, each stretch weighted by the quota's **resilience** to the restrictive periods covering it,
  through the task's own reading (`PeriodKinds.multiplier`): 0 and it stands still, 1 and the period changes nothing.
  It is a pace line: nothing is recorded as done. A stretch no period covers — a future the calendar has not planned
  included — moves at full pace.
- **Renewal**: with `r` renewals the progression moves `r` times faster and comes back to 0 % each time it reaches
  100 %; the end of the loop is 100 % of its last renewal.
- **The loop's pace profile is built when the quota or the periods change, never per tick** (`QuotaDomain.profile`,
  keyed on `state.panels` in `QuotaProgressEditor`); an instant is then read off it. The action re-reads the clock each
  time the percentage shown can have changed (a thousandth of a renewal, between 1 s and 60 s) — a display resample,
  never a request.
- **A list like the chronos'**: `SetQuotas` writes the whole list as one History Unit (`QuotasDelta`), ids are minted
  `quota-{n}`, `QuotaDomain.healed` is applied on decode, on merge and in the reducer, the sync splits it one row per
  quota (`EntityRows`) and merges a quota whole.
- **Its resilience action is ONE button whose drop-down lists every period with a field for its value** (user rule
  2026-10-04, `QuotaResilienceEditor`): each field over every added quota at once (the shared value, "mixed"
  otherwise), written as soon as it reads as a percentage in 0…100; the button says how many periods are not at 100 %.
  It no longer reads the window's `Config.resiliencePeriod` (that is the TASKS' resilience action's period). The menu
  is `focusable` — the one menu that is, because its fields must take the keyboard (`popups.md`).
- **A quota's resilience is read by `QuotaDomain.resilienceFor`, never `PeriodKinds.resilienceFor`, and it lists EVERY
  period** (`state.allPeriodKinds`; anomaly 2026-10-04: the 20 s break was missing). The task reading refuses every
  value for the kinds that *"allow no task"* (the 20 s break, `inactivity`) — a rule about tasks the scheduler places.
  A quota is not one: whether its progression goes on through a 20 s break is the user's to say. Untouched, those two
  default to 0, so the progression stands still there exactly as it did. Do not route a quota's rate back through the
  tasks' function.
- **A new quota starts from the account's default configuration** (`SchedulerState.newQuotaDefaults`,
  `NewElementDefaults.newQuota`) — and that default is edited through the QUOTA ACTIONS themselves: an added
  **"New quota" creation row** is acted on as a quota (`SearchDomain.actionKindOf`), `addedQuotas` leads with the
  default (wearing `DEFAULT_CONFIGURATION_ID`), and `QuotaEditors`' one `write` sends its changes to
  `SetNewQuotaDefaults` (a setting, not a History Unit — the alarms' rule). With ONLY the creation row added, the section shows the actions that edit
  the default and nothing else (`SearchDomain.actionsFor`, `DEFAULT_CONFIGURATION_ACTIONS`: Title, Amount, Loop,
  Restarts, Resilience — and "New", which creates one from that default) — Duplicate, Delete, the progression and the particular loops are about a quota that exists. Its loop is unset until the user sets it: a new quota then runs over the week it is made in; once
  set, over the default's loop that now falls in (a default that does not repeat keeps its own dates).
- **A QUOTA'S PACE IS WEIGHTED BY THE PERIODS OF ITS WHOLE LOOP — WHAT IS KNOWN ON BOTH SIDES OF THE NOW-LINE, OR NOT
  AT ALL** (`SchedulerDomain.quotaPeriods`, anomaly 2026-10-04, `QuotaTest`). The profile used to read the restrictive
  panels the state holds (`restrictivePeriods`) — and the state holds the §17 nights, the wind-down hours and the
  screen breaks only as the last fill laid them, AHEAD of the line. So a loop's past ran at full rate while its future
  had the nights taken out: "claude limit" read 82 % elapsed (64.5 % of its second renewal) where 71 % of the waking
  time had gone (43 %) — checked on the release DB, where the old reading reproduces 64.5 % to the digit. Now:
  - the **nights and wind-down hours are projected from the sleep schedule** over the loop (`sleepPanels`,
    `beforeBedPanels`), past and future alike, and the fill's own copies are left out;
  - the **periods the user placed** count where they are, their repeats expanded over the loop;
  - the **three screen breaks do not**: the machine's are predictions ahead of the line and the conducted ones are
    records behind it — five hours of one-sided time on that account. A period of a break's kind the user DRAWS counts.
  Do not hand a quota `state.panels` again, and do not add a derived, one-sided period to it (the observed "no screen"
  stretches of the lock history are the next candidate: they exist only behind the line).
- **THE QUOTA'S PERIOD IS STATED AS A START, AN END SAID ONE OF TWO WAYS, AND A COUNT** (user rule 2026-10-04; the
  "Loop" action, `QuotaLoopEditor`; the rules are `QuotaDomain`'s, `QuotaTest`):
  - **Start**: a day and a time of day. The day field's drop-down holds a row of the seven weekdays — a pick is the
    latest such day up to today (`latestWeekday`), so it names the period being lived — and, below it, the calendar
    window's own month grid (`MiniMonth`, now shared) to pick a date instead.
  - **End**: `QuotaEntry.endByDelta` says which way it is stated. `true` (the default) — a LENGTH after the start
    (`formatLength` / `parseLength`: `7d`, `1d 12h`, `90min`; 7 days by default, `DEFAULT_LOOP_MILLIS`); `false` — a
    date and a time of its own. The period is `[startMillis, endMillis)` either way: the flag only says what a moved
    start keeps (`withStart`: a length travels with it, a date stays put, and a start at or past a dated end is
    refused). An end not after the start, or a length of nothing, is never written.
  - **How many times it repeats**: `QuotaEntry.repeatCount`, null = without end (the default), `n` = `n` more periods
    after the first. ONE fact said once: a count of 0 IS "does not repeat" (`repeats` false, count null — `healed`
    makes it so, `withRepeatCount` writes it). Past its last period a counted quota stays on it at 100 %
    (`lastLoopIndex`, `loopAt`), and what was particular to a later one is dropped.
  - Both fields are persisted and synced with the quota (`PersistedQuota`, absent = a length / without end, which is
    how every earlier quota behaved) and carried by the default configuration into a new quota.
- **THE QUOTA'S RESTARTS, AND THE LIST OF PARTICULAR LOOPS** (user rule 2026-10-04; `QuotaRestartsEditor`,
  `QuotaLoopsEditor`, `QuotaTest`):
  - **`QuotaEntry.renewals`** (the "Restarts" action, 1 by default, one of the default configuration's): how many
    times the percentage runs from 0 to 100 % in ONE period — with 2 it reaches 100 % where it would have reached
    50 %, restarts at 0 %. It was a per-loop number only.
  - **"Particular loops" is a LIST with "+ Add a loop"** (the period of a number; by default the one being lived).
    Each element holds what is particular to that period: its restarts (`QuotaLoop.renewals`, **null = the quota's**
    — an explicit 1 is particular while the quota says 2), its **ending time**, and ✕. An element with nothing
    particular yet is on screen only (`healed` keeps no empty entry). (The amount factor, "two times more quota in
    that loop", was dropped by the user the same day: the model has none, an older payload's is ignored, and the
    amount is the quota's own in every loop.)
  - **A particular ending time is INSIDE the period** (`QuotaDomain.endInsidePeriod`: after the period's start, at
    its regular end at the latest) and stands alone, with no start of its own: the quota is at 100 % from there to
    the start of the next period, which starts where it always did. Refused otherwise — by the row (an error, nothing
    written) and by `healed` (dropped). **A refused time may not go on reading as entered** (anomaly, same day: the
    field kept the typed time with only a small line under it, and it read as accepted): the field is in error while
    refused, the row says NOT SAVED, and on leaving the field it reads the real end again (`TimeOfDayField`). The
    refusal ends when the field reads the stored time again — left, OR typed back: a field reports only a time that
    DIFFERS from the stored one, so typing the real end back told the row nothing and the message stayed (second
    anomaly, same day; `onSettled`). And the quota's text fields leave edit mode on the first press outside them
    (`endsEditOnOutsidePress` → `leaveOnOutsidePress`, `popups.md`): a bare text field keeps the caret when the press
    lands on something that takes no focus. A start AND an end of its own is the shape an earlier build wrote; it still
    reads, and setting the end from the row drops the start.
  - **Stored**: `PersistedQuota.renewals` (absent = 1); `PersistedQuotaLoop.ownRenewals` (null = the quota's). The
    legacy `renewals` is still written (own, else 1) for an older build, and an older payload is read off it — 1 was
    "nothing particular" there.
- **EVERY creation row leads to all the elements of its kind** (user rule 2026-10-04):
  `AddedAction.CreationSearchAll`, in the creation rows' OWN group (`Kind.Creation`), draws one "Every <kind>" button
  per kind among the added creation rows (`SearchDomain.creationKinds`), each opening a new Search window on that kind
  alone, nothing filtered and nothing added (`kindSearchConfig`). The groups an added element brings are
  `SearchDomain.actionKindsOf` — the kind it is acted on as AND its own — so a "New quota" row keeps both the quota's
  default-configuration actions and this one; asking `actionKindOf` alone would have left it without.

