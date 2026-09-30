# Screen breaks, cues and notifications

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Screen breaks — the three dynamic restrictive periods

→ ADR 0003, ADR 0017, `docs/scheduler_requirements.md` § *Default restrictive periods*. The three are the 20-s
look-away, the 5-min pose, the 15-min pose. Terminology: **"screen breaks"** everywhere — UI, docs, code identifiers,
persisted keys.

- **Each break's kind is the requirements' own** (`DynamicPeriods.breakKind`, 2026-09-29): the 20 s look-away is
  `no task allowed` end to end; the 5-min pose is one minute of `no task allowed` then four minutes of the kind
  **"5min screen break"** (`PeriodKinds.BREAK_5MIN`); the 15-min pose is the kind **"15min screen break"**
  (`PeriodKinds.BREAK_15MIN`). Both new kinds start at resilience 0 for every task and are edited in the resilience
  editor like any other kind. There is no *doable during a screen break* switch and no per-break accepted set.

### The break machine is the one place the three are placed

→ ADR 0017.

- **Where they fall is `BreakMachine`, and nothing else** (2026-09-30). It is a forward state machine: a **bar** per
  break (the earliest instant the rules let it start), the break the line is **in**, the one it **drags**, and the
  **stretch of "no screen"** the line is in. The runtime moves it with the line (`SchedulerDomain.stepScreenBreaks`);
  every question about where the breaks fall AHEAD of the line runs the same step function forward from the state the
  line is in (`BreakMachine.predict`). Nothing re-derives the breaks from an origin: the walk that did
  (`DynamicPeriods.instances`) is gone, and with it every patch it grew to keep the past still (the drag "put down" at
  the first stretch the line was not at a screen for, the chain that "outlasted" a break, the day-quantized origin,
  the pull-back floor). Do not bring a walk back beside the machine — two placements of one break is the drift the
  due/place split, the banked record and the one cue sweep each existed to remove.
- **The rules, as transitions:**
  - *As early as possible*: a break falls due when the line reaches its bar. A machine with nothing to continue from
    starts **rested**: each bar is what the past it can see sets (the banked record, the conducted breaks, the
    stretches of "no screen" behind the line — `BreakMachine.absorbHistory`), and a break that past says nothing
    about is barred one cadence from the line.
  - *"After the end of a screen break, no 20s break in the next 20 minutes"* and each break's own recurrence
    (`ScreenBreak.intervalMillis`) are set when a break ENDS.
  - *"After a ≥5-minute / ≥15-minute of 'no screen'"* is set when the line's stretch of "no screen" ENDS (a mode edge
    back to 1, or a break the line entered at a screen ending). A break falling due INSIDE a stretch is taken there
    — the stretch bars what comes after it, never the break it is the taking of.
  - *The chain rule*: a break falling due while another is in progress (or touching its end) joins it; the break the
    line is in becomes the longest of them from the same start (`Event.Grew`), re-banked as that. A break falling due
    within the reach of a dragged one joins the drag; a longer one **teleports** onto the line
    (*"the 15min break teleports 5 minutes backward, starting right after $now line$, the 5min break is removed"*).
  - *"In a 'no screen' period, if t_b is the start of a screen break and $now line$ < t_b, the break starts at
    max($now line$, t_s)"* (`BreakMachine.dueOf`): a bar inside a known no-screen period ahead is pulled to its start,
    or to the line if the line is already in it (a mode-1 line retracts the period, so `t_s` is the line). **Once per
    continuous period and label** (`State.taken`): a later bar of that label inside the same period waits for its end.
    A continuous period includes the line's own stretch and the last one it ended (`State.lastStretchStart/End`), so a
    line that comes back to a screen inside the night it walked into while away owes nothing the night already took
    (start-up of account 3, 2026-09-30).
  - It reads **no task, no pre-placed block and no emptiness**: *"the three screen breaks are placed everywhere in
    the timeline as earliest as possible"*. The walk pushed a break out of any stretch nobody could run in, a rule
    the requirements do not have. The environment it reads is the no-screen periods (`BreakMachine.chainsOf`).
- **What is armed** (`BreakMachine.nextEventMillis`): the end of the break the line is in, each break's due, and — while
  a break is dragged — the instant each other break's due comes within its reach. The runtime compares the line with
  that one instant and does nothing before it. A mode edge and a history rewrite are triggers of their own.
- **Banked the moment the line enters it, and never moved** (`SchedulerDomain.stepScreenBreaks`, `FrozenScreenBreaks`,
  requirements § *frozen past*). `Event.Started` banks the whole break from its start; `Event.Grew` re-banks it as the
  longer one; the one removal is the requirements' own exception — a POSE a line switching to mode 1 is inside
  (`Event.Removed`): it is un-banked and owed again. **The calendar draws the past from this record and nothing
  else** (`takenScreenBreakPanels`, bisected to the visible window). A first banking invents no past: nothing is banked
  behind the line's first moment.
  - **The machine is persisted with the front** (`FrozenScreenBreaks.machine`, `screen_break_front.machine`, schema v17
    / `16.sqm`), so a restart continues from it. A row written before it (NULL) starts the machine rested at the line,
    its bars raised by the record. A record whose front is more than two days behind the line is not continued from
    (`FrozenScreenBreaks.STALE_AFTER_MILLIS`); a machine a hair AHEAD of the caller's instant (a clock read a few
    milliseconds apart) still is (`SchedulerDomain.breakMachineAt`), or every break would be placed a few milliseconds
    off the ones drawn and banked.
  - **LOCAL-ONLY, pruned at 90 days** (the requirements' exception 2): `screen_break_history` / `screen_break_front`.
    `lineMillis` beside the front is where the line last was: an engine that starts far past it walks the line there
    in mode 2 (below). The front is written on a 20-s real-time cadence (`persistFront`), a transition at once.
  - **A banked break and recorded work never overlap**: work is recorded minus every banked break refusing its task.
- **History rewritten behind the line is a trigger, never a tick** (`SchedulerEngine.absorbBreakHistory`): a pause the
  devices observed late (`noScreenEvidence`, the swept cover of a wake) raises the bars the machine carries where it
  stands — it never MOVES the machine (moving it to the clock before a journey made the journey's steps all fall
  behind it, and the whole walk was skipped: start-up of account 3, 2026-09-30). Bars only rise.
- **A re-run of the scheduler drops what was deduced and not saved in history** (requirements § *Use of the set of rules
  output*; `SchedulerEngine.rebuildBreaksFromHistory`, after every set of rules the scheduler FINDS — `RefreshSchedule`,
  `ExtendSchedule`, `AdoptScheduleRules`; not `SwitchTpMode`, which lays a plan already held). The machine's bars are
  deduced, so they are REBUILT from history (`BreakMachine.rebuildFromHistory`) — and may go down: the banked breaks, the
  conducted breaks, the "no screen" observed (`noScreenEvidence`) and the stretches the line itself lived through in
  this process (`lineStretches`, in memory only, so a restart does not carry a mode a previous process deduced). A label
  history says nothing about keeps its carried bar (the rested start — rebuilding it would push it one cadence on at
  every re-run). A drag history still owes keeps its due, so an owed pose is announced once — and what it still owes is
  re-derived under the restrictive periods at the line (`BreakMachine.stillOwed`: `dueOf` with the pull, then the drag's
  reach), never read off a bar alone. A pose the pull brought in has its bar AHEAD of the line; reading "bar ≤ line"
  dropped it at every re-run and the next step pulled it back in, re-announced at every progressive stage (account 3,
  2026-09-30). The rebuild arms the next
  step, which moves the line on to the clock. The plan itself was already found from the line the run started at and
  published by compare-and-set; the calendar keeps the previous rules until then.
  - Drop the line's own stretches from the history and `ServerQuotaTest` goes over budget (≈ +3 MB of egress a
    month): in its scenario, as on a peer, a lock the OS log does not show is a stretch only the line saw, and dropping
    its bar re-publishes the pose windows.
- **A materialized break is never an input to its own placement.** `restrictivePeriodsOf` drops `screenBreak` panels.

### The modes

- **The `t_p` mode is TWO QUESTIONS, asked in order, and BOTH are about the ACCOUNT** (`SchedulerDomain.tpMode`, the
  one place it is decided). **Which devices are unlocked** — mode 1 while any device of the account is,
  `anyDeviceUnlockedAt` reading the input once off the **account-wide pause the calendar already draws**
  (`displayInactivityGaps`), so the mode and the Inactivity band can never disagree. And, where none is, **has a device
  SAID it is away**: mode 3 if at least one has the "I'm away" button on, mode 2 if none has. "I'm away" is the
  requirements' *"not on a computer"* (or phone) period, which *"can't be with 'no computer unlocked'"*: a
  device's flag cannot stand while that device is really locked (below), so mode 2 is every device really locked and
  mode 3 at least one device away at an unlocked screen. The engine reads it
  through a cache held on its inputs (`SchedulerEngine.baseTpMode`): for the same inputs the answer only changes where
  the line crosses an edge they hold, so it is not re-derived from the pause history at every move of the line.
- **The away flag LEAVES the device it was pressed on, or the second question is unanswerable**
  (`PauseCueGateway.syncDeviceAway` → `device_away`, migration 20260904000000). The engine's reading is
  `own || account`: the `or` makes the button take effect at the press, offline or before the round trip lands. **The
  display reads the same two facts** (`App.kt`: `userAway || accountAway`, off `SchedulerEngine.accountAway`).
- **A mode-3 stretch is drawn as one** (ADR 0002, `docs/invariants/calendar.md`): every device's layer covers it and
  the calendar hatches BOTH — which by the layers' own identity makes it a no-screen period. The away device is the half
  the OS cannot report, so the button feeds that layer itself (`SchedulerDomain.declaredAwayRegions`).
- **Who is dragged depends on the mode** (`DynamicPeriods.dragsAt(label, mode)`, the ONE predicate): the two poses in
  **mode 1** (*"$now line$ must not be covered by the period 'no screen'"*), the 20 s look-away in **mode 2** (*"the
  $now line$ must be in mode 1 or 3 before entering the 20s break"*). A dragged break is `]now line; now line + d]` —
  in discrete time `[t_p + 1, t_p + d + 1)` (`BreakMachine.Placed.coveredFromMillis`) — and never happens while the
  mode holds. A line that becomes covered (a lock, the away chord) ENTERS the pose it was dragging at that instant; a
  line switching to mode 1 inside a pose removes it and drags it again.
- **A mode-1 line ENTERS the look-away and is in mode 3 until it leaves it** (`BreakMachine.effectiveMode`, read by the
  engine and the calendar through `SchedulerDomain.effectiveTpMode`; *"can't go in mode 1 during a '20s screen
  break'"*). "It" is the break the line entered: grown into a pose by the chain rule, the hold lasts to its end. Mode 2
  is never held. The plan is NOT redone for those twenty seconds.
- **Mode 2 is not mode 3: they differ by the look-away's drag and by the CUE.** The CUE is
  `DynamicPeriods.breaksAreNotifiedAt`, read in exactly one place (`SchedulerDomain.cueCrossings`): **in mode 2 a
  screen break is never announced**. The break is still placed and banked. The wind-down is not a screen break and is
  unaffected. Every machine transition is queued with the mode it happened in (`BreakStep.advancedMode` /
  `switchedMode`), so a flip inside a scan window still announces what the earlier mode crossed.
- **A period the line DRAGS obstructs nothing** (`SchedulerDomain.isDraggedScreenBreak`, the ONE reading; the mark rides
  the panel id, `DRAGGED_BREAK_ID_SUFFIX`). No instant of the timeline is ever inside it. Both answers about **what
  restricts the timeline** — `fillSchedule`'s `restrictions` and `SchedulerDomain.restrictiveKindsAt` — ask the predicate.
- **The plan is built around the breaks the line WILL MEET** (`SchedulerDomain.breaksTheLineWillMeet` —
  `BreakMachine.predict` with the mode held). At a screen a pose the line reaches is dragged, and every break falling
  due within its reach joins it, so only the look-aways before the first pose obstruct and the plan runs straight
  through the rest. A line away is covered from now on — one stretch of "no screen" — so it takes each break once.
- **What is DRAWN ahead is the machine for a line that follows the app** (`SchedulerDomain.screenBreakPanels` —
  `BreakMachine.predictExpected`): at a screen, it takes each break where it falls and is away in every known no-screen
  period; away, it goes on away (the plan's own breaks). The calendar clips the plan out of every break drawn ahead of the
  line (`clipPlanForPinnedScreenBreak`), so a pose ahead reads as the period it is.
- **A break is never STRETCHED to keep covering the line; the gap behind it is covered instead** — the derived
  **Inactivity** band, or **Sleep** inside a §17 window; the oblique layers hatch it by their own rule.
- **Modes 2 and 3: `t_p` is covered**, and the fill covers the WHOLE continuation, `[now, searchEnd]`, as an environment
  period — never a panel (a synthetic "Away" band shipped once and was reverted, 2026-08-31). A resilient task fills the
  away plan; with nobody resilient nothing is planned. A flip back to mode 1 lays the at-screen plan already found.
- **The now-line NEVER JUMPS — a distant position is a JOURNEY, walked in mode 2 except where the account was in MODE 3**
  (`SchedulerEngine.sweepNowLineTo`, one `SchedulerDomain.sweepStepMillis` at a time). A step never straddles an away
  span's bound; the swept stretch is covered as "no screen" (`noteSweptNoScreen`) — a history rewrite, not a move.
  **The journey walks the rules ALREADY HELD for its mode and searches nothing** (user rule 2026-09-30; requirements §
  *Progressive Calculation*: *"similar to a case where no CPU were available … the current set of rules output … is used
  to define the schedule as the $now line$ does its fast move"*): the plan found for its mode class with the last plan
  is laid (`layHeldModePlan` → `SwitchTpMode`), past the plan's front the rules' repetition is unrolled
  (`ExtendSchedule(unrollOnly = true)`, `ScheduleFill.Input.unrollOnly`) and where no rule reaches, nothing is placed.
  The landing re-plans. An engine that starts far past where the line last was walks the line there the same way
  (`catchUpAfterNotRunning`); the account's declared-away spans are asked of the server (`awaySpansFor`).
- **An app that was not RUNNING is not a device asleep** (`catchUpAfterNotRunning`, 2026-09-30): the requirements' mode-2
  fast move is for a device put to sleep. For a stretch the app was closed, the OS lock history of it is asked first
  (`lockedIntervalsQuery`, the reader the no-screen evidence uses, off the start path, bounded by
  `CATCH_UP_LOCK_QUERY_MILLIS`): the parts the device was UNLOCKED for are walked in **mode 1** and are no "no screen"
  (`reportTimeGap`'s `atScreenSpans`); only the rest is mode 2 and noted as swept. A history that cannot be read walks it
  all in mode 2. Walking an unlocked stretch in mode 2 barred the 5-min break for the hour after every restart (account
  3). Nothing that can bank is launched until the walk has landed (`start` → `startRunning`).
- **A mode flip applies the plan for the new mode CLASS, at once and on this device** (`launchTpModeReschedule` →
  `switchTpModePlan`), and moves the break machine at the edge (`advanceBreaks`), not at the next tick.
- **The SCHEDULER RETURNS A SET OF RULES, and that set is the whole of what the server is told about where breaks fall**
  (`SchedulerDomain.poseWindowsBetween` → `publish_break_rules` → `screen_break_rule`). The windows are the machine's
  drawn prediction; **a pose the line owes publishes its own window alone**, from its due and one length long — a fixed
  pair, so the rules do not move with the line at every beat (the breaks after it depend on when it is taken, and the
  line taking it is an edge that publishes again). The server never runs the scheduler.
- **"WHAT IS THE TASK TO DO NOW" IS ASKED WITH THE MODE** (`SchedulerDomain.currentPanelOf`, over the panel the runtime
  cursor holds): in either away mode an ON-SCREEN task is not at the line, whatever a plan built under mode 1 says.
- **That is NOT the away flag silencing the device** (`AwayVersusLockedCueTest`). A LOCK gates the OUTPUT
  (`deviceUnlocked`); "I'm away" gates nothing — it changes what is SCHEDULED. Neither suppression is marked delivered.
- **A LOCK ends "I'm away" — a level — and an UNLOCK clears it — an edge** (`SchedulerEngine.noteScreenSignal`, requirements
  2026-09-30: the fake period cannot be with the real one). The declared-away stretch closes at the lock; the OS lock
  history covers what follows. Both are read off the platform's own lock/unlock notification. **Never add a timer for
  it.**
- **A LOCK silences this device; "I'm away" does NOT.** `effectiveScreenActive()` answers *is anybody working here* (the
  away flag masks it); `deviceUnlocked()` answers *may this device say anything* (the raw lock). **Every §11/§15 output
  gates on `deviceUnlocked`**; a task switch and a pose due are suppressed, not spent. **An ALARM is the one deliberate
  exception** (ADR 0010).
- **"Look away now" is a conducted 20 s break started on the machine the line carries** (`SchedulerEngine.conductBreak`
  → `SchedulerDomain.conductScreenBreak` → `BreakMachine.conduct`): a look-away falling due while it runs joins it (the
  chain rule) and it bars the next one for twenty minutes. It is never banked; it is **recorded as a period**
  (`RecordConductedBreak`) once it completes, marked `TaskPanel.conductedBreak` — load-bearing, since the first bar keys
  on a dynamic PERIOD. A superseded one leaves no trace.
- **A look-away the conducted break makes vanish is replaced by the task on both its sides** (user spec 2026-09-27): what
  vanished is a break the plan was cut around that STARTS between the press and its end, that the line did not bank and
  that the machine no longer places (`SchedulerDomain.vanishedPastBreaks`). When ONE task's boxes touch both edges, the
  task fills it, in the reduction of `RecordConductedBreak` and nowhere else. A break that began before the press happened.
- **The idle check at the line REPORTS, it never re-plans** (`SchedulerDomain.planMismatchAtLine`,
  `SchedulerEngine.guardPlanAtLine`), and runs only at an armed trigger (a machine transition, a plan panel's end).
- The **end** of a break is a notification, not only a voice cue (§ *The Notifications switch* below).
- A screen-break panel has **no Edit** (no editable object behind it). A sleep band's menu leads with Edit.

### Notification / voice-cue triggers

Must be **mathematically accurate** — a pure function of which boundary instants the line crossed (each fires exactly
once, in order), never of how a sweep/heartbeat happens to align with the calendar.

**Every break cue is a transition of the break machine** (`SchedulerDomain.cueCrossings` over `BreakMachine.Event`): a
look-away where the line ENTERS it (`Event.Started`, resumed at its end), a pose where it falls due — owed at a line that
drags it (`Event.Owed`, and the owed drag is a level offered at every sweep until announced), entered at one that does
not (`Event.Started`), or grown out of the break the line was in (`Event.Grew`). The runtime applies each transition at
the instant it was armed for however far one leap goes, and the cue sweep self-delays to the next armed trigger — the
machine's, the rule cursor's (a panel edge, a wind-down, a reminder), a look-away's resume. Nothing in the sweep asks
where a break falls. The pose de-dupe keys on the instant it fell due.

**The sweep also carries the §14 reminder tags and the wind-down** (the rule cursor's crossings,
`RuleProgram.Cursor.moveTo`), for the same reason: a leap that crosses a reminder and a break must say them in the order
they were due. Their occurrence source is the tags themselves; they de-dupe on the tag's stable id —
`docs/invariants/alarms-and-timers.md` § *A reminder is announced by the SWEEP, never armed*.

**A break's start notification says what the break RUNS OUT INTO** — one function,
`SchedulerDomain.screenBreakStartNotificationMessage` over `screenBreakFollowOn`, so the cue sweep and the manual
"Look away now" cannot word one break two ways. The rule is a pair of coverage questions asked of `state.panels`: the
break's START is covered by no qualifying period and its END is (half-open, so a period beginning at the break's own end
instant IS what follows it). Qualifying is a period the **USER** drew (`ScreenBreakFollowOn.UserPeriod`) and §17's
**`before bed`** hour (`BeforeBed`). A dynamic period, a conducted break and a derived sleep window never qualify; a break
wholly inside a qualifying period says nothing.

Staleness is judged only by the crossing's REAL age (`BoundarySweep`, 2-s budget), never by sim distance or scan-window
position. The machine's transitions and the cursor's crossings are queued until the sweep announces them (bounded by
`PENDING_CUE_REACH_MILLIS`), so no crossing is clipped by a clock jump.

### The Notifications switch silences the OUTPUT, never the record

The lateral menu's **Notifications** switch and `Ctrl+Shift+Alt+N` are one lever
(`SchedulerState.notificationsEnabled`, persisted + synced, not an Undo/Redo unit).

- **`SchedulerEngine.notifyUser` is the ONE funnel and the ONE place the switch is read.** Every notification
  the app posts goes through it — a break's start and end, "task to do now", the wind-down, an alarm, a
  chord's own receipt — so there is no exempt caller and no second gate. A mute with a list of exceptions is
  not a mute; never add the check anywhere else, and never post around it.
- **Every notification is SPOKEN from that same call.** A notification is written for a user who is not
  looking at OmniApp, so a silent one only reaches somebody already watching the corner it appears in. The
  funnel posts and speaks together, at one instant — which is what makes it impossible for a new notification
  site to be silent by forgetting to add a cue beside it. Never speak a notification anywhere but here.
- **What it SAYS is its own short sentence, not its text read out** (user spec 2026-09-26). The written half is
  read at leisure and may carry detail (a chord, a task's path, step deadlines); the spoken half is heard once,
  in passing, and says what just happened or what to do: a chord's receipt says what the press does ("I'm
  away" / "I'm back", never the chord), a task switch "Current task: <title>", a ring "Time's up: Tea". Every
  such sentence is built in ONE place (`engine/SpokenMessages.kt`, `SpokenMessagesTest`) and handed to
  `notifyUser(spoken = …)`; a call without one reads its text out (`spokenNotificationText`). The one silent
  notification is one whose consequence speaks at once after it (`SpokenMessages.SILENT`: "Look away now",
  whose look-away speaks its cue).
- **A phrase with a bundled recording is named, and everything else is synthesized from its own text.**
  `notifyUser`'s `cue` parameter is passed by exactly the two notifications §15 fixes word for word (the
  look-away's start and its resume), so those keep the shared Piper voice; every other phrase carries a task
  title, an alarm's label or a chord and cannot be pre-rendered, so it goes to the platform synthesizer
  ([`VoiceUtterance`], `spokenNotificationText`). Do **not** grow `VoiceCue` to try to cover them: an enum
  that cannot say a task's name is not the funnel for a notification that must.
- **The mute silences BOTH halves; the voice switch silences the voice alone.** `notificationsEnabled` is
  "cancel every notification", and the loud half is not the one it may leave running. `notificationVoiceEnabled`
  (persisted under its original key `lookAwayVoiceEnabled`) is the switch for the spoken half — with it off
  the notifications still post, silently.
- **The ONE voice with no notification behind it** is the pause-over cue an OS alarm fires on a phone whose
  user has walked away from every screen (ADR 0006): there is nobody to read anything and the app is not even
  running. Every other phrase the app speaks is a notification.
- **The log is written BEFORE the platform call, muted or not.** The History window's **Notifications** source
  answers "what did the app decide to say", which is why it was never proof of delivery — and why the switch
  can silence the interruption without touching the record.
- **The log records the `cue` the notification was spoken with** (`NotificationLogEntry.cue`), and the History
  window replays a row through `NotificationLogEntry.utterance` = the same `VoiceUtterance.forNotification`
  call — and so does the sentence it said (`NotificationLogEntry.spoken`, persisted with the entry; an entry
  older than it reads its text out, as it was spoken then). Do not re-derive the cue from the text: a look-away's start and a rest pose's are both titled
  "Screen break" and only one plays the WAV. A new `notifyUser(…, cue)` needs nothing else.
- **Switching off also withdraws what the OS is still showing** (`cancelSystemNotifications`): a notification
  sits in Android's shade / iOS's Notification Centre until dismissed, so "cancel every notification" has to
  answer the pile already on screen too. The desktop actual is a deliberate no-op — a tray balloon cannot be
  recalled.
- **Switching back on posts one notification saying so, and that is load-bearing.** The chord's receipt is
  raised before the action, so on the un-mute press it is still muted and swallowed; this is that press's
  receipt, posted from the far side of the flip. Turning them *off* announces nothing extra — the receipt for
  that press goes out normally, just before the mute takes hold.
- **The LOCK gate is not a second mute, and that is why it is not here.** The switch answers *may the app
  speak at all*, which is one question with one funnel; `deviceUnlocked()` answers *is there anybody at this
  device to say it to*, which is asked per cue, before the funnel, and decides whether the cue happens — the
  look-away's start has always been asked it. Do not fold it into `notifyUser`: an alarm rings a locked
  machine on purpose (ADR 0010), and a funnel with an exception is what this section forbids.
- It says nothing about the **schedule**: a break still starts and ends where it did, silently.

---

