# Scheduler

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.
The requirements are `docs/scheduler_requirements.md` (user-owned — never edit it); the score its two optimization
criteria are measured by is defined in `docs/scheduler_score.md`.

---

## Scheduler

→ ADR 0001 §11. The requirements are `docs/scheduler_requirements.md`; their two optimization
criteria are one score, defined in `docs/scheduler_score.md`. `SchedulerDomain.fillSchedule` is a driver over it.

- **The score is the ONLY copy of the scheduling rules.** `ScoreModel` (`ScheduleScore.kt`) evaluates it;
  `ScheduleOptimizer` and `ScheduleImprover` search for its best continuation; `ScheduleFill` maps OmniApp's world
  onto its inputs. Nothing else decides which task runs or for how long — a rule that wants to change the schedule
  changes the score (and `docs/scheduler_score.md` with it), never a special case beside the search.
- **Everything the score measures is on the SCHEDULABLE clock** — time at which at least one task may run. A
  stretch nobody may run in (a night, a 20 s look-away) neither separates a panel, nor discounts, nor forgets, nor
  creates any compensation. Do not reintroduce wall-time distances anywhere in it.
- **How far back the frozen past is replayed is measured in `Θ`, on the SCHEDULABLE clock**
  (`ScheduleFill.pastLookbackMillis`; four windows, floored at 168 h and capped at 90 days). A lag forgets over its
  own `τ_i`, so a cutoff loses exactly the lag standing there — and `τ_i = max(M_i, 1min)/π_i` grows without bound
  as a task gets rarer. The flat 168 h of WALL time that shipped re-seeded at zero the lag of every task under
  about `M_i/168h` of share (0.3 % for a 30-minute minimum): a three-day pre-placed block ending nine days ago did
  not count as served, and the task was laid at the line instead of not for weeks (`SchedulePastLookbackTest`). Do
  not put a wall-time span back in, and do not raise the cap without bounding what a fill then costs.
- **A task's lag depends on its own service and its own target alone.** Both the optimizer's per-decision
  baselines and the improver's per-task terms rest on it; a coupling between tasks' lags would silently make both
  wrong.
- **Criterion 2 costs from the first minute missed and is scaled by `τ_i`** (`ScoreModel.shortfallCost` =
  `τ_i·s·(2M_i + s)`). A squared shortfall made the first minutes free and the improver trimmed a minute or two
  off most panels; scaled by `M_i` alone it grew cheaper against the lag as tasks were added. Both regressions are
  pinned by `ScheduleImproverTest`.
- **The improver may only lower the score of the WHOLE continuation.** A move is kept only when `J` of the whole
  continuation goes down; a search that judged each decision over its own window was tried and made `J` worse
  (2026-09-16). It never touches a pre-placed run, never puts a task where it may not run, and never changes a
  first run that §7 or §13 decided.
- **Nothing requires two devices to reach the same rules** (user rule, 2026-09-17). The step-bounded passes
  (rollout, seeds, improver) always run; what a fill is given WALL TIME for is reaching the best score (§ *The best
  score* below). Where two devices' plans differ, the score decides (§ *One device plans*). Do not reintroduce
  "every device must land on the same answer" as a reason to refuse a solver or a wall-time budget.
- **BOTH switch chords lay an ENTRY at the now-line and re-plan around it** (`SchedulerReducer.placeSwitchEntry`). It
  is an ordinary user-authored panel — `auto = false`, existence pin, built through the same two helpers the calendar's
  own "add" uses — so it is a fixed obstacle the fill plans around (`isSchedulerFixed`), it is a Calendar history unit,
  and it draws with the blue outline without the calendar knowing a chord exists (`calendar.md`).
  - `Ctrl+Shift+Alt+T` names the task; its entry is EPSILON long (`SchedulerDomain.SWITCH_ENTRY_MILLIS` = 1 s) and
    states *which task and when*, **nothing about how long**: the length is the scheduler's answer (below).
  - `Ctrl+Shift+Alt+Z` (and PRD §7's "Switch task") lays the **alternative** the rules name at the line on
    `[now line, now line + d]` with `d` fixed beforehand (`SchedulerDomain.ALTERNATIVE_SCHEDULE_MILLIS`, 10 min) —
    `docs/scheduler_requirements.md` § *Alternative Schedules*, word for word — and runs the scheduler again with it.
    The engine hands the reducer the alternative its runtime cursor holds (`SchedulerIntent.ForceTaskSwitch.rules`,
    `RuleProgram.Cursor.alternative`) rather than letting it search the plan; a reducer with no engine reads the
    panels (`alternativeTaskAt`). Where the rules name nobody, the press falls back to the refusal below.
- **The seed does NOT grow; the request that rides with it is what makes the panel a usable length.** Without a
  `ForcedTaskStart` the seed is only a pre-placed block, and the best continuation after a block need not be the
  same task. With it, the seed and the first run after it are ONE panel on the score's clock, so criterion 2
  charges its shortfall until the task reaches its minimum — the soft *Minimum Execution Time* goal, yielding as
  ever to whatever the timeline restricts.
- **Where the rules name no alternative, PRD §7 "Switch task" constrains the FIRST RUN, not the task.** The press
  records a `ForcedTaskSwitch(task, at)` and the fill hands it to the search as `refusedFirst`: the first run may not be
  that task while anybody else may run, so the refused task keeps its lag and its share and is an ordinary
  candidate again from the second run — and a task nothing can replace still runs. Do not give it a rule of its
  own, and do not put it in `schedulingSignature`: the press re-plans inside its own reducer, or dropping the spent
  marker would fire a second, un-refused re-plan. It stays live until **another task has actually been served past
  `at`** (`liveForcedSwitchTask`, read off the recorded past); the advance tick drops it then.
- **PRD §13 "start this task now" is the SAME lever from the other end.** The task cell's menu — and PRD §7's
  task picker (`Ctrl+Shift+Alt+T`, `shortcuts.md`), which is a second way of naming the task and not a second
  lever — records a `ForcedTaskStart(task, at)` and the fill hands it to the search as `forcedFirst`: the **first run it
  places** is that task — served like any other run, so only that run is the user's answer. Same liveness predicate as the refusal
  (`liveForcedStartTask`: outstanding until another task has been served past `at`), same reason it is not in
  `schedulingSignature`, same drop by the advance tick. Offered on a **placeable** task only — a leaf still in
  the tree, `SchedulerDomain.isPlaceableTask`, which is the one predicate the reducer and every menu raising
  this intent ask; asking for a task clears an outstanding refusal *of that same task*.
- **EVERY rule the fill makes also names WHO RUNS INSTEAD** (`TaskPanel.alternativeTaskId` +
  `alternativeSpans`, `SchedulerDomain.alternativeTaskAt`). `docs/scheduler_requirements.md` § *Alternative
  Schedules*: *"The returned set of rules must also give for every $now line$ the task that must be scheduled if
  the task scheduled by the scheduler can't be scheduled now."* The answer is the next-best first run of the
  decision asked where the run starts (`ScheduleOptimizer.Evaluation.alternativeTo`), and where it changes inside
  the run, the instant it changes (bisection to a second, `alternativeSpans`; the probes look one run deep — probes
  as deep as the decision nearly doubled a week's fill, 103 → 182 ms in `PerfBenchmarkTest`) — named on the FINAL runs, after the
  improver. Three more things it is: **null where there is nobody** — a stretch only one task was allowed in, "the
  same task again" being no answer at all; **derived, never persisted and never synced**, recomputed in full by
  every fill exactly as the panel is; and read at an instant, never per panel.
- **PRD §7's refusal IS the requirements' use of that answer**, not a second mechanism. *"A program would simply
  read the rules, set this new task starting at $now line$, and run the scheduler again."* Both come out of one
  ranking of first runs (`Evaluation`): `choose(refused = …)` and `alternativeTo(…)`. Do not let the two drift
  into two answers.
- **Do not answer a sliding period by re-planning per tick.** A mode-1 drag moves the owed pose with the
  line, and the plan under it was materialized at the last rule change: the answer is a display clip
  (`clipPlanForPinnedScreenBreak`, over every break ahead of the line), cutting what a break **refuses** — not what
  it covers. The plan itself is built around the breaks the line WILL MEET (`breaksTheLineWillMeet` — the break
  machine run forward with the mode held, `screen-breaks.md`), so it runs through a pose the line will drag rather
  than leaving it a hole.

### The rules are read by a forward cursor

→ `docs/scheduler_requirements.md` § *Rule Structure*, ADR 0017.

- **The runtime never scans, sorts or searches the timeline.** As the line moves, the one interpreter
  (`SchedulerEngine.interpretTo`, called by the advance tick, by the cue sweep at the instant it armed itself for,
  and at every journey step) compares the line with the next armed trigger and does nothing before it. Two parts:
  - **the task side is compiled** into sequential branches with their trigger boundaries (`RuleProgram` — the work
    panel holding each piece of the timeline, the alternative the rules name there, where a plan panel ends, the
    wind-downs, the reminder tags) whenever the scheduler returns a new set of rules (a new panel list), and read by a
    forward cursor (`RuleProgram.Cursor`) that only ever steps forward;
  - **the three screen breaks are a state machine** (`BreakMachine`, `screen-breaks.md`): its next transition is the
    trigger, and a mode edge and a history rewrite are triggers of their own.
  Compiling is the scheduler's side, done when the rules change, never on a tick. **Do not add a per-tick reading of
  `state.panels`, of the pause history or of the environment to the runtime**; a question the line asks goes to the
  cursor or the machine.
- **What ran is banked once a plan panel has ENDED or the tree changed** (the cursor's armed trigger, acted on by the
  advance tick: `elapsePending`), never by an `AdvanceSchedule` at every tick. A tree edit is a trigger too: a task
  that left the tree may not go on holding a panel (PRD §9). Banking stays on the tick's cadence: banked at the cue
  sweep's exact instants, a record reached the wire on a different side of the sync throttle and the heavy hour of
  `ServerQuotaTest` gained a round trip.

### What reaches the scheduler

Only three things: **pre-placed tasks** (pinned/manual panels), **the frozen past** (records, past panels, the
kept head on an extension) and **restrictive periods**. Nothing else, by any other route.

- **Pre-placed tasks and restrictive periods can repeat for ever** (`TaskPanel.repeat`, `PanelRepeat`, requirements
  § *Priority, Granularity and Compensation*: *"can be in infinite patterns"*, 2026-09-27): every N days at the same
  local time, optionally until an instant. The panel is the first occurrence; the others are derived by ONE funnel,
  `PanelRepeats.expand`, wherever they are asked for — the fill (over the lags' replay window and past its search
  horizon, keeping in its answer only the occurrences around the line), the break funnel (over the walk), the
  calendar (over the visible span). Occurrences (`repeat/{base}/{k}`) are `isRegeneratedPanel`: never synced, never in
  the signature — the pattern is, on its panel. Editing or dragging an occurrence moves the pattern by as much;
  deleting one deletes the pattern. Set from both editors ("Repeat every (days)").

- **A pre-placed block is a block OWNED BY A TASK, and a period reaches the walk by its KIND** — the two
  slots are not interchangeable, and a panel must never take both. `isSchedulerFixed` (= `TaskPanel.pinned`)
  is what fills the first; `fillSchedule` keeps every `isRestrictivePeriod` panel whatever its pins, which is
  the second. So a hand-drawn period carries the calendar's **existence pin** (`pins.existence` —
  `calendar.md`) and **not** `pinned`: `SchedulerReducer.derivePinned`'s period-aware overload is the one
  place that says so, and without it a dragged no-screen period would enter the fill as a pre-placed block
  owned by nobody, on top of the period it already is.
- **A drag or a resize on the grid IS the existence pin** (`SchedulerDomain.pinsAfterHandPlacement`). The
  gesture is the user placing a block, and an unpinned block is not something the fill keeps — so without it
  the drag became a user-authored *unpinned* panel, exactly the shape the fill deletes, and the re-plan the
  edit itself triggers undid it. The edit window's **Existence** switch is the same field from the other
  side: unpinning is what
  makes the fill stop seeing a panel, and `pinned` being in `schedulingSignature` is what re-plans.

### The frozen past includes the block the line is STANDING IN

→ `side-dev/README.md` § *frozen past*: *"the schedule at `t < now line` never changes as `now line`
increases."*

- **A re-plan cuts the TAIL of the straddling task panel and keeps its ELAPSED HEAD**, truncated at the line
  (`fillSchedule`'s `kept`). Cutting the whole panel is what shipped, and the head went nowhere: the advance
  banks a panel only once it has *wholly* elapsed (deliberately, so an in-progress one stays a panel), so work
  the app had told the user it was doing vanished from the timeline on every rule change — and, because
  `pastPeriodsForTask` reads those same panels, from the frozen past the lags are replayed from.
- **WHOEVER PLACED IT.** The branch reads *a task panel the cut is about to take*, never *an auto panel*: the
  other panel the cut takes is one the user has just UNPINNED (`calendar.md`), and that is the
  one gesture whose whole purpose is to ask for a re-plan. Qualifying the head on `auto` deleted its elapsed
  half — the frozen-past rule breaking on exactly the press that invokes it.
- **The head is an ordinary auto panel** from there on, however it started (`auto = true` on the kept copy):
  the next advance banks it, `mergeSameTaskPanels` fuses it back with the tail (so it is folded into the merge
  input, not appended beside it — and it has to carry the same `auto`/`pinned` to fuse at all), it is behind
  the line so it is served history, never a pre-placed block, and it is no longer something the user placed, so the
  calendar stops outlining it as one (`SchedulerDomain.isUserPlaced`).
- **The run the line is in the middle of CONTINUES; it is not re-picked, and it has no rule of its own.** The head
  is replayed as the run in progress (`ScoreCursor.run` / `runLen`), so a continuation that stops it short of its
  minimum pays its shortfall (criterion 2). **Both §7's refusal and §13's request override it**, because they
  decide the first run.
- PRD §10's continuous-effort credit (`scheduledSpanMinutes`) still answers for an effort the **records** alone
  carry; a resume never reaches it, so the two can never both shorten one slot.

### Resilience is the whole of "where may this task run"

→ `side-dev/README.md` § *Restrictive Period*, `PeriodKinds`.

**A restrictive period is a start, an end and a KIND, and each task has a resilience to each kind: a
multiplier in `[0, 1]` on its priority percentage for as long as a period of that kind lasts.** `0` forbids
it there, `1` leaves it untouched, and anything between scales its share. Overlapping periods **multiply**,
so the strictest still forbids. There is no other mechanism, and adding a second one is the mistake this
model exists to prevent.

- **`Task.resilience` holds OVERRIDES ONLY.** An absent kind takes `PeriodKinds.defaultResilience`, which is
  `0` for every kind except `no screen`. Two things follow, and both are load-bearing: **a kind the
  user has just defined is added to every task at `0`** — a restrictive period restricts, so a new one turns
  everybody away until its own edit window hands somebody a value above zero, and it is why defining one
  still writes nothing to any task (absence *is* the default, which is also what makes a task created *later*
  carry the same answer) — and **"on screen" is not a flag**: it is exactly a `0` against `no screen`,
  read through the derived `Task.onScreen`, which is the one kind whose default has to be `1` or the
  off-screen task would be the one that has to say so. `doableDuringBreak` is gone: all three dynamic periods
  are `inactivity` end to end, so there is nothing there to be resilient to.
- **SIX kinds are built in** (`PeriodKinds.BUILT_IN`): `inactivity` and `sleep` — the README's one
  `no task allowed`, split in two on 2026-09-12 because the calendar has always drawn and named them apart —
  `no screen` (the README's `no on-screen task`), `before bed`, which PRD §17's sleep schedule lays by itself
  (below), and PRD §8's two layer sentences `no computer unlocked` and `no phone unlocked`.
  **The stored name is the USER'S word**: the kinds were renamed out of the README's vocabulary so that the
  picker, the "edit…" chooser's row and the editor's heading are one word and cannot drift. The spellings a
  payload written before that holds are migrated on load, and the clipboard's are too
  (`PeriodKinds.migrateStoredKind`, the single reading of a stored kind name).
- **`inactivity` and `sleep` are the two kinds a task has NO resilience to define**
  (`PeriodKinds.isResilienceEditable` — the single predicate, applied by the edit window's row loop and by
  nothing else). Each accepts nobody by its own name, so its multiplier is always `0` and there is nothing
  there for a task to choose; the window shows
  no row for it and writes no override. That is a rule about the **window**, not about the model:
  `resilienceFor` still answers for it everywhere (that is how a grey period refuses everybody), and an
  override an older payload wrote is still honoured on decode, on the wire and in the walk.
- **A task a period leaves at `1` is UNAFFECTED by it, not confined to it.** The app used to confine an
  off-screen task to no-screen periods; a period can only multiply what it covers and says nothing about the
  timeline it does not, so the model cannot express that and no longer does.
- **`Task.DEFAULT_RESILIENCE` (a new task is on screen) is a default for a TASK; `defaultResilience` is a
  default for a KIND.** They are different questions. The clipboard writes the difference between the two,
  which is why an ordinary task's copy says nothing about a screen (ADR 0012).
- **The account's own kinds are `SchedulerState.periodKinds`**, defined by the **`+`** in the task edit
  window's resilience section **and by the calendar's add window** (`PeriodKindField`, same `AddPeriodKind`
  intent — a second door, not a second rule). The three built-ins are never in that list;
  `state.allPeriodKinds` is the one reading of "every kind a task can be resilient to". Removing a kind takes
  every task's override and every panel laid with it.
- **ONE INTENT LAYS A PERIOD OF ANY KIND** (`AddRestrictivePeriod(kind, start, end)`), reached from PRD §8's
  single "add…" entry. There is no per-kind intent and no per-kind menu entry: a kind the account defined is
  a period exactly as `no on-screen task` is, and two intents covering two of the kinds meant a third had no
  way onto the calendar and the two copies of the lay/trim/strip sequence were free to drift. Nothing in the
  reducer branches on the kind — the title is `PeriodKinds.periodTitle`, the two legacy flags are
  `PeriodKinds.legacyNoScreenFlag` / `legacyInactivityFlag` (written only for the two kinds that HAVE one:
  setting a flag that stands for another kind is a second, disagreeing statement of what the period is), and
  what the period DOES is its refusal (below).
- **"MAY THESE TWO SHARE A STRETCH" IS ONE QUESTION: does the period refuse the task** (resilience `0`,
  `SchedulerReducer.periodRefuses`). It is what `resolveScreenOverrides` trims by in both directions and what
  `stripRecordsUnderPeriod` clears the record by, so the four cases the code used to enumerate ("an on-screen
  task panel overrides no-screen periods", "a grey period overrides every task panel", …) are one sentence
  that also answers for the kinds that have no flag. Do not re-spell them.
- **`TaskPanel.periodKind` IS PERSISTED AND SYNCED** (`PersistedPanel.periodKind`). It is authoritative — the
  user chose it — and for a kind with no legacy flag it is the panel's ONLY statement of what it is, so a
  period of one written without it decoded as a block of **work**. A payload that predates the field decodes
  to blank and `restrictiveKind` heals it out of the flags exactly as before. It is in `schedulingSignature`
  too, in place of the two flags: read off those, re-kinding a period re-planned nothing.
- **A MODE-1 LINE RETRACTS THE PERIOD THAT SAYS NOBODY IS AT A SCREEN** (`SchedulerDomain.retractedAtLineSpans`
  / `retractAtLine`, gated by the one predicate `retractsAtLine`; read in `fillScheduleUninstrumented` and
  nowhere else). Mode 1 is *a device of the account is unlocked*, and the requirements' clause is flat —
  *"$now line$ must not be covered by the period 'no on-screen task'. This means that if it reaches one of
  those periods, the passing of the $now line$ line creates task panels not covered by the period."* So a
  covering period that **is or carries `no screen`** (`PeriodKindConfig.isOrImpliesNoScreen`) gives up
  `[now, its end)`, and the plan may then put a task at the line: what is left covering it is a layer
  period, whose default resilience is `1` and which prevents nobody.
  - **Every period that IS or CARRIES `no screen` retracts** (`SchedulerDomain.retractsAtLine` =
    `PeriodKindConfig.isOrImpliesNoScreen`, user rule 2026-10-01): a drawn `no screen` period, its companions, and
    the period that carries one — a `sleep` window (`when sleep then no screen`), a `before bed` hour, `inactivity`
    or an account kind once a rule gives it the companion. A period that carries `no screen` cannot stand at the line
    without its no-screen period standing there too, so a user still at a screen when sleep was scheduled retracts
    the WINDOW at the line, not only its companion. **A carrier gives way only where the line is IN it**
    (`startMillis <= now`): a night or a wind-down hour still ahead stays an obstacle in the plan, or every future
    night would be planned as working time (the morning plan, the shares and `scheduleCycle` all built as if nobody
    slept — `ScheduleCycleTest` caught it). A line reaching bedtime still at a screen meets the window, and
    `planMismatchAtLine` asks the fill once at that edge. Only the single-kind closure counts: a no-screen period a
    two-kind rule brings retracts itself and neither cause. (2026-09-28 → 2026-10-01 only the companion lifted and
    the window stayed over the line; before that `sleep` retracted on PRD §17's *"carved by activity"*.) The
    predicate is deliberately **not** `coversNoScreen`, or a grey period the user drew would be pulled out from
    under them.
  - **The plan is searched AND materialized across the retracted span; the DISPLAY is what stops at the
    line** (`clipPlanForRetractedPeriod`, beside `clipPlanForPinnedScreenBreak` in `App.kt`, forward only).
    The rules must name which task holds and until when (*"task A from 00:40 to $now line$, until 01:25"*),
    and the fill runs at a rule change rather than on time passing — so a plan stopping at the line leaves
    the stretch between two fills with nothing to be swept into, and the anomaly comes back three minutes to
    the right. Behind the line the band is already gone by §17's own carve, off the same account activity
    that makes the mode 1, so band-hole and task panels coincide. Ahead of it the band is whole and a lock
    flips the mode with nothing to undo. **Neither away mode retracts anything**: their clause is the
    opposite one (the line must BE covered), which is what `DynamicPeriods.awayCover` is for.
- **PRD §17's wind-down is a KIND, not a rule: `before bed` (`PeriodKinds.BEFORE_BED`).** The hour before
  each §17 bedtime is covered by a period of it (`SchedulerDomain.beforeBedPanels`, derived from
  `sleepPanels` so the hour drifts with the wake time it is measured back from). The hour is empty for the
  one reason any period empties a stretch — every task's default resilience to the kind is `0` — and a task
  given a value above zero works through it. It used to be a hard-coded extension of the sleep obstacle,
  which is precisely the second mechanism this model exists to prevent; do not put one back. Three things
  it is not: it is **not** `no task allowed` (a task may be given a value for it, and the edit window offers
  a row); it is **not a no-screen period BY NAME** (`PeriodKinds.isLayerKind` is false, its default stays
  `0`); and it is **not** the
  user's, so it cannot be defined again or deleted (`isUserDefined` gates both, and an older payload's
  user-defined kind of that name collapses into it on decode, overrides intact).
- **EVERY `before bed` PERIOD, AND EVERY §17 SLEEP WINDOW, CARRIES A `no screen` PERIOD BY DEFAULT** (user
  rule, 2026-09-13; the sleep half added 2026-09-18). A night is the plainest stretch there is of nobody being
  at a screen, and the hour of wind-down running into it already said so. It is also what makes the mode-1
  retraction above reach a sleep window. Since 2026-09-18 this is a **COMPANION** — the period edit window's
  *"periods always present when this period is present"* — since 2026-10-01 the combination rule `when sleep then
  no screen` (`PeriodKinds.DEFAULT_COMBINATIONS`, read through `PeriodKindConfig`), transitive, editable — and **`no screen` no longer carries the two layer periods** by default, so
  neither the night nor the hour hatches a layer unless the account says so. Companions reach everything
  through ONE funnel, `SchedulerDomain.companionPeriods`: the scheduler and the recurrence bars (via
  `restrictivePeriodsOf`, the fill's `dynamicBase`/`restrictions`), the record bank
  (`assertedNoScreenRanges`), the mode-1 retraction. Per kind it subtracts the spans a period of that very kind
  already covers, so a fractional resilience is never squared. Never lay a companion panel. **Whoever builds
  periods outside `state.panels` must hand them to `companionPeriods`/`restrictivePeriodsOf`/
  `projectedSleepPeriods`** — the fill's `dynamicBase` (the hours are laid by that very fill, not kept) and the
  calendar's display environment (projected past the horizon). Mapping a period to a `RestrictivePeriod` by
  hand drops its companions: that is exactly how the next wind-down hour once went un-rested.
  `coversNoScreen(before bed)` stays false on purpose — the companion period answers it, and a second answer
  off the kind would count the hour twice.
- **The wind-down periods are DERIVED, like the sleep windows they come from.** `before-bed/{wake day}`
  (`BEFORE_BED_PANEL_ID_PREFIX`) is cut and regenerated by every fill, is an `isRegeneratedPanel` (so it is
  out of the sync fingerprint and out of `schedulingSignature`), and the calendar draws it as a grey band
  with **no Edit and no Remove** — there is no object of its own behind it. The **cue keys on the period's
  own start** (`SchedulerEngine`'s `windDownInstants` reads the panels the fill laid, never a second reading
  of the sleep schedule), so the notification and the band cannot disagree.
- **A period is an OBJECT, and its window is the task edit window's section read the other way round.** Every
  row there carries a **✎** onto `PeriodKindEditWindow` — *one kind, every task*, where the section is *one
  task, every kind*. It holds exactly three things: **Delete** (`RemovePeriodKind`, offered only for a
  user-defined kind — the one place a period is deleted, because it is the one place a period is an object);
  the **schedulable leaves** with a check box and a percentage each (`SchedulerDomain.periodKindTaskRows` — a
  parent task is never placed, so a value on one is a number nothing reads); and a **bulk field** that appears
  as soon as anything is checked, showing the value the checked tasks share or **blank** where they do not
  (`SchedulerDomain.commonResilience`). That field and each row's own write through the **one** intent,
  `SetPeriodResilience`, so checking twenty tasks and typing one percentage is **one** history unit — never a
  fan-out of `SetTaskResilience`. It is a window about ONE kind; opening it on another kind opens a second
  window, and neither it nor the window it was opened from leaves on a press elsewhere (`popups.md`).
- **A panel's kind is `TaskPanel.restrictiveKind`**, the single reading of `periodKind` and the legacy
  `noScreen`/`inactivity`/`sleep`/`screenBreak` flags. A payload written before kinds existed is healed from
  those flags on decode. **Ask through it, never through the four flags**: a period of a kind that has no
  flag — `before bed`, or one of the account's own — is a period too, and spelling the flags out said so only
  for the four that have one (that is why the reducer's `isTaskPanel` is `!panel.isRestrictivePeriod`).
- The score reads each task's **multiplier** `μ_i(t)` — the product of its resilience to every kind covering
  `t` — and its local share is its priority times `μ_i` renormalized over whoever may run. That is what makes a
  multiplier mean "this fraction of the percentage for as long as the period lasts".
- A deprivation is **fractional**: a resilience of `0.4` deprives a task of `0.6` of its multiplier there, and the
  compensation it buys is that fraction of a flat refusal's.
- **The compensation fades over ONE constant length, `λ` = `ScoreModel.COMPENSATION_LENGTH_MILLIS` (4 h of
  schedulable time), never over `τ_i`.** `λ` sets both how far a blockage's repayment reaches and how much a long
  one can buy (`π_i(1−π_i)·λ` of extra presence per side), so a 48-hour blockage buys barely more than a 24-hour
  one. Tied to `τ_i`, it made the repayment depend on the minimum time and shrank it to minutes (2026-09-19).
- **LEAVING TIME TO NOBODY IS A DECISION THE SCORE PRICES, NOT A CONSTRAINT EITHER WAY** (2026-09-29, when
  `docs/scheduler_requirements.md` dropped § *No idling*). Every decision tries nobody (`ScoreModel.IDLE`,
  `ScoreModel.choicesAt`) beside every task — the rollout, the exhaustive search, the improver's reassign, the MIP's
  "at most one task per slot" — and keeps it only where `J` goes down: the targets add up to 100 %, so every lag
  falls behind, and the panel it interrupts ends. In practice that is a stretch before an edge too short for any
  minimum, where a task would be a panel cut far short or a task served far past its share. Never put back a hard
  "no gap" rule (it throws away continuations that score lower), and never a candidate filter on "does the minimum
  fit" either (the score already weighs the shortfall against the lag).
  - **A run of nobody is an explicit `Run` with task `IDLE`**: it names no alternative (§ *Alternative Schedules*
    sets a task), is placed as nothing, and is reported as `ScheduleFill.Result.idle` →
    `SchedulerState.plannedIdle` (in memory only, like `scheduleCycle`).
  - **The check at the line tells the two holes apart through `plannedIdle`** (`planMismatchAtLine`,
    `screen-breaks.md`): a hole the plan chose is the plan; a hole a moved break left behind is a mismatch, logged
    (since 2026-09-29 the check never re-plans — a re-plan there rewrote a definitive schedule).
  - **A peer's gap is not a decision** (§ *One device plans*): `isLegalContinuation` refuses gaps, and the runs then
    compete as a seed, where a gap IS read as time left to nobody and the score judges it.
- **The one panel shorter than a minute is at `t_p` itself**, and it is the README's: modes 1 and 2 push the swept
  period onto the line as the half-open `(t_p, t_p + d]`, so the line's own instant is uncovered and *"the
  passing of the $now line$ creates task panels not covered by the period"*.
- A period nobody may run in (every dynamic period, every grey period) **suspends** a panel rather than cutting it
  (PRD §15/§17) — it is not on the schedulable clock — where a stretch somebody ELSE may run in cuts it. That is
  about the panel's length, never about whether anything is placed.

### The best score, and what "as close as possible" means

→ `docs/scheduler_score.md` § *Degradation*, ADR 0001 § 12.

- **The passes over one score** (`ScheduleOptimizer.plan`): the rollout policy builds the continuation one decision
  at a time; the **seeds** compete (the plan being replaced, another device's plan — each cut to its legal prefix,
  completed, re-scored); `ScheduleImprover` lowers `J` of the whole continuation within `DEFAULT_IMPROVE_BUDGET`
  scored moves; then, **only while the fill's `SearchBudget` lasts**, the exhaustive search and the platform solver;
  the alternatives are named on the result. No pass can move the plan away from the best score
  (`ScheduleImproverTest.the_improvement_never_worsens_the_score_and_keeps_every_constraint`,
  `ScheduleScoreTest.a_seed_that_scores_better_than_the_search_is_what_the_plan_returns`).
- **"If the best score is reachable in the time, it must be reached" is answered by `ScheduleOptimizer.certify`
  given wall time.** It starts from the best continuation so far and, when it finishes, the plan is **certified**
  (`Plan.certified`, `SearchReport`, shown on the History window's scheduler row as `SchedulerRunEntry.search`). On a
  real account it usually does not finish; the result is then the best found, and says so (`exhausted`).
- **The platform solver is resolved only for a fill given search time** (`ScheduleFill.run`): on the desktop that
  loads OR-Tools' native library, about a second once, which a display fill on the UI thread must never pay.
- **The platform solver runs only where the exhaustive search did not finish, and is never trusted.**
  `ExternalScheduleSolver` (desktop: `MipScheduleSolver`, OR-Tools SCIP over windows of the continuation) returns a
  continuation that `accepted` checks against every hard constraint and `score` re-scores over the whole
  continuation; it is kept only when `J` goes down. Measured 2026-09-17 (ADR 0001 § 12): it improves on the
  step-bounded passes only on small cases, where the exhaustive search finishes first anyway — keep it behind the
  exhaustive search, never in front of it.
- **The search never trusts a seed either.** A seed is a statement about OTHER rules or another environment: its
  prefix that breaks a hard constraint is cut (`legalPrefix`), and a seed whose first free run breaks §7/§13 is
  dropped whole.
- **`Fraction` → `Double` millis**, with the tie tolerance of `docs/scheduler_score.md` § *Ties*.
- **The two-scenario example of § *Rule state evolution* is a test**
  (`TaskTreeTimelineTest.the_same_slope_gives_the_same_schedule_while_the_two_transitions_overlap`): inside a
  transition the plan holds `R(x)` and is re-made at every run start the line reaches
  (`SchedulerDomain.taskTreeBlendDecisionKey`).
- **A run the moving rule state turns against ends where it does** (`ScheduleFill.Input.ruleStateAt`,
  `TaskTreeTimelineTest.a_run_the_moving_rule_state_turns_against_ends_where_it_does`). The first free run is probed at
  evenly spaced positions of the line and bisected to a second: where the best first run under `R(x)`, with the run
  so far as the frozen past, is another task, the run is cut and the next run starts there — a run start, so the
  engine re-plans on reaching it. Re-planning at run starts alone left a whole run under the rule state of its
  first instant.

### One device plans

→ ADR 0015.

- **A RULE re-plan goes through `ScheduleCoordinator`, never straight to `dispatchProgressivePlan`**
  (`SchedulerEngine.replan`). The rule-change watcher, the task-tree boundary and the §7 switch turning on funnel
  there. Extensions (the horizon rolling, the calendar scrolling) stay local: they are cheap, and with a cycle they are
  an unroll. **So do the re-plans that are the rules EVALUATED, not changed** (2026-09-27): a `t_p` mode flip (the
  rules are parameterized by the mode, and the plan for the new one applies from the flip — an election's deadline
  left the old mode's plan standing for seconds; since 2026-09-28 the flip LAYS that plan rather than re-planning,
  § *The rules are parameterized by the mode* below). A journey plans nothing at all: it lays the rules held for its
  mode and unrolls their repetition (`screen-breaks.md`, 2026-09-30). (The idle check at the line re-planned too
  until 2026-09-29; it now only reports, `screen-breaks.md`.)
- **"Who is present" is asked when a re-plan is due, and at no other time.** No presence timer, no poll: the probe,
  the one-second reply window and the ten-second rules deadline are one-shot waits after that event. Adding a
  heartbeat to "know earlier" is the timer-driven traffic CLAUDE.md forbids, and it buys nothing the deadline does
  not already guarantee.
- **One decider per election.** The device that probes ranks the replies and announces; two probes for the same
  rules crossing resolve to the smaller election id. Never let each device rank on its own — they would hear
  different replies and elect different leaders.
- **The ranking is `PeerCapability.rank`**: present, kind, speed bucket, device id. A value that changes from second
  to second (CPU load) must not enter it, or the leader flaps between elections.
- **A device with no rules after `RULES_DEADLINE_MILLIS` (5 s) plans for itself**, and so does every device while
  the channel is not joined. No device is ever left without a plan because of another device. The deadline is sized
  to the requirements' pace: the engine's debounce (1 s), the probe window (1 s) and the deadline leave a first stage
  room inside the 10 s (`ScheduleCoordinatorTest.the_wait_for_a_leader_fits_inside_the_pace`); at 10 s the deadline
  alone used the whole pace.
- **A plan already held for these rules is DEFINITIVE as far as it is materialized** (requirements § *Progressive
  Calculation*, 2026-09-27). Rules taken in for the very rules a device already planned (it planned alone past the
  deadline, or took an earlier stage) keep its materialized head and are laid past it (`AdoptScheduleRules.keepHead`),
  and the leader answers a counter with an EXTENSION seeded with the other plan (`ExtendSchedule.seeds`), never a
  re-plan from the line: replacing either rewrote a schedule already published as definitive
  (`DefinitiveAcrossDevicesTest`).
- **The best score wins.** Plans made apart (offline, alone, past a deadline) need not agree. A device whose plan was
  made ALONE (`notePlannedLocally`, or `publish` with nobody to send to) answers the first rules of the next
  election with that plan (`PeerMessage.Counter`, once per election); the leader extends with it as a seed
  (`replanWithSeeds` → `ExtendSchedule.seeds`), so the two compete on the score under the rules in force NOW —
  after the merge, never each under its own pre-merge rules, whose scores are not comparable — and publishes the
  result, which every device takes in. A device that only took rules in never counters, so it cannot bounce
  (`ScheduleCoordinatorTest.a_plan_made_alone_competes_with_the_leaders_on_the_score_once`). The database merge is
  unchanged by this: which schedule won says nothing about whose EDITS win (`sync-and-accounts.md`).
- **Nobody else around, nothing sent** (`server-quota.md`). A device nobody is using plans for itself and tells no
  one. A device that has heard from no other device since its last unanswered probe leads alone: no probe, no
  announcement, no stages on the wire. A device announces itself (`RulesRequest`) when it becomes present AND
  whenever its channel (re)connects, and a present device with no rules to hand answers with a `Reply`, so the
  first re-plan after a device arrives is an election again. Whoever does not answer a probe is forgotten until
  it speaks.
- **A follower takes RUNS, never panels** (`AdoptScheduleRules` → `ScheduleFill.Input.adopted`): its own fill
  regenerates its sleep windows, breaks and periods and lays the runs through its own environment, so a stretch this
  device refuses is never given to a task. Rules are taken only for the follower's own `schedulingSignature`, and
  only when newer (`nowMillis`, then stage) than what it last took or planned.
- **A follower lays the leader's runs only when they are a legal continuation ON ITS OWN TIMELINE**
  (`ScheduleOptimizer.isLegalContinuation`, `SchedulerPeerProtocolTest`). The leader planned over its own
  environment: a period only the leader had leaves a hole in its runs that is no DECISION to leave the time to
  nobody — a peer's gap is ambiguous, so it is never laid (it is read as time left to nobody only as a seed, where the
  score judges it) — and a period only the follower has may refuse the task the leader put there (resilience `0`).
  Either way the follower plans for itself with the leader's runs as a seed — never lays them anyway.
- **The rules on the wire are derived and never stored**: a broadcast, not a row, and `AdoptScheduleRules` never
  pushes (`syncsToServer`). The channel is `realtime:scheduler:<userId>`, **private** — its RLS is migration
  20260916000000.
- **The in-reducer presses stay local** (§7 switch, §13 start, sleep-schedule edit, record removal).

### The rules are parameterized by the mode

→ `docs/scheduler_requirements.md` § *$now line$ 3 modes* against § *Progressive Calculation*, 2026-09-28.

- **What is definitive is definitive for EVERY mode.** *"… they will all indicate the same schedule rules for any
  t < t₁ (task panel scheduling parameterized by now line and now line mode …)"*. A flip used to re-plan from the
  line: a search whose answer depends on the time it is given, so locking and unlocking again rewrote the at-screen
  schedule already published (`TpModeSwitchTest`, whose control shows the re-plan does not come back to it).
- **So every plan reduction also finds the plan for the mode CLASS the line is not in** (`SchedulerDomain.otherModePlan`
  → `SchedulerState.otherModePlan`, `SchedulerReducer.withOtherModePlan`), to the same front: a re-plan, an extension
  and an adoption all end there. The classes are two, not three — modes 2 and 3 place everything identically and
  differ in the cue alone (`DynamicPeriods.breaksAreNotifiedAt`), so **a 2↔3 flip changes nothing**
  (`SchedulerDomain.tpModeFlipChangesPlan`; the engine does not even dispatch).
- **It is extended exactly as the plan is**: the runs held for these rules (same class, same `schedulingSignature`)
  are kept and only the tail past them is filled. Under other rules nothing held is kept.
- **No dynamic period obstructs it** (`fillSchedule(breaksObstruct = false)`): where the three fall depends on when
  the line changes mode, which it cannot know. A flip lays it on the schedulable clock of the timeline it lands on,
  where each of them suspends a run — nobody may run in one — rather than leaving a hole nothing decided.
- **It is the step-bounded passes only**, no wall-time search: the flip must find it made. The score it leaves is the
  first exception of § *Strict Requirements*; the definitive clause is the constraint the score is optimized under.
- **A class flip LAYS it** (`SchedulerIntent.SwitchTpMode` → `reduceSwitchTpMode`), the way a follower lays a
  leader's rules (`adoptedPlacements`, the same legality check), and the plan the line leaves becomes the other
  class's — so a flip and a flip back return to the schedule already published, both fronts swapped. **The hard
  constraints still win**: where the runs are no legal continuation on this timeline (a period retracting at the line
  that did not at the plan's own instant, § *Resilience*), the fill plans from the line with them as a seed and the
  History row (`ModeSwitch`) carries the search it made. With none found for these rules (a restart, a pull, a rule
  change still debouncing) the flip re-plans as it always did.
- **Derived and in memory only**, like `scheduleCycle`: not in the codec, not in the sync fingerprint, not in the
  signature.
- **What it costs** (`PerfBenchmarkTest`, 2026-09-28, 12 tasks): the at-screen plan found from a covered line is
  ~220 ms for a fresh week against ~115 ms for the plan itself; the covered plan found from the screen is ~2 ms when
  no task is resilient to `no screen`. It runs on plan reductions only, never on a tick, and the pace cap measures
  the whole reduction (`notePlanRate`), so the stages shrink to what the device can do.

### The rules repeat

→ `docs/scheduler_score.md` § *The rules repeat*, ADR 0009 § *Beyond the 168 h ceiling*.

- **A fill that settles returns a CYCLE** (`ScheduleFill.settle` → `SchedulerState.scheduleCycle`): the last of
  three identical copies of its task runs, over an environment uniform to the end. Its runs are on the
  **schedulable clock**, so a night or a break never shifts them. Compare **task runs**, never the raw
  `ScheduleOptimizer.Run`s — the alternative-schedule bisection splits a run at an instant that differs from copy
  to copy — and never compare calendar days: a 90-min rotation over a 15.5-h waking day repeats every 3 days in
  wall time, and never across the boundaries of a staged plan.
- **An EXTENSION unrolls the cycle instead of searching** (`ScheduleFill.unroll`), which is what makes the plan past
  the detection point the same as one long fill (`ScheduleCycleTest`) and a far week a walk rather than an
  optimization. It refuses — and the search runs — when the rule state is not the one the cycle was found under
  (`ruleStateHash`), when the environment from the anchor on is not the uniform one (`environmentHash`, a
  pre-placed task, a later edge), or on a re-plan (a forced start or refusal decides the first run).
- **The limit is the materialization ceiling.** The search stops at `now + SCHEDULE_HORIZON_MILLIS`
  (`ScheduleFill.Input.repeatBeyondMillis`); a fill reaching it without an exact repetition takes the closest
  approximate one (`exact = false`). Below the limit only an exact repetition is ever returned.
- **The cycle is derived and in memory only**: not in the codec, not in the sync fingerprint, not in
  `schedulingSignature`. Every reducer fill replaces it with what that fill returned, so a re-plan never carries an
  old one; a restart or a pull simply starts without one.

### Progressive Calculation

→ `docs/scheduler_requirements.md` § *Progressive Calculation*.

- **THE SCHEDULER MAY STOP FOR EXACTLY THREE REASONS** (user rule, 2026-09-18). Two of them are $t_{goal}$'s
  terms and one is not an instant at all:
  1. **$t_{goal}$ — the LATEST of the end of the current week, the last displayed time in the calendar, and
     `now + 10 min`** (`SchedulerDomain.scheduleGoalEndMillis`; the week from `currentWeekEndMillis`, Monday-first
     and in the app's own zone). The week term holds **with the calendar closed**: a rule change on a headless
     device plans to Monday, not for ten minutes, and the goal steps a week forward at each rollover rather than
     drifting with the line. It replaces the 2026-09-16 rule, whose goal was the calendar's end alone. The floor
     can therefore only govern a week's last ten minutes — which is also where the rollover falls, and that is
     what `HorizonRefillRuleTest` reads.
  2. **the set of rules growing too heavy** — the 168 h ceiling, applied by `scheduleHorizonEndMillis`; only a
     calendar scrolled out can reach it. Past it the plan is a display-only far week (`display-hot-path.md`).
  3. **the calculation time limit** (`SchedulerEngine.PLAN_CALCULATION_LIMIT_MILLIS`, 2 min of REAL time per
     progressive fill). When it runs out the front stays where the last stage left it, and — this is the half
     that makes it a stop rather than a pause — `extensionStoodDown` keeps the rolling-horizon and calendar
     watchers from closing the shortfall it left. Only a **rule change** (the signature it was stopped under) or
     a **goal that has grown past the one abandoned** asks the question again. Without that latch the watcher
     would resume the stages one poll later with a fresh budget, for ever (`PlanCalculationLimitTest`).
     The limit is given up in the requirement's own order: `stageSearchMillis` drops the SEARCH first and spends
     what is left on reaching further, because a schedule that stops short is worse than one that is not the best.
     This third reason is the requirement's own: *"It will also stop if the set of rules became too heavy, or if it
     calculated for too long"* — and § *Strict Requirements*' exception 2 bounds the computing power a plan may take.
  How the rolling floor is kept from re-triggering itself, and the 168 h ceiling, are in `display-hot-path.md`.
  Each extension keeps the head, so a run the line is in continues to its minimum across them.
- **The engine fills in DOUBLING STAGES** (`SchedulerEngine.dispatchProgressivePlan`): a re-plan (or an
  extension) to `PROGRESSIVE_FIRST_STAGE_MILLIS` (1 h) ahead, then `ExtendSchedule` to 2 h, 4 h, … up to
  $t_{goal}$, each capped through the intents' `horizonCapMillis`. An extension keeps everything materialized, so
  every published stage is **definitive** until a rule change
  (`SchedulerFillTest.progressive_stages_never_rewrite_what_an_earlier_stage_made_definitive`).
- **Doubling, CAPPED BY THE PACE** (`progressiveStageCapMillis`, `ProgressivePaceTest`). The pace binds every
  stage, not the average: with the front at `t1` when a stage is published, the next must be published within 10 s
  and reach `t1 + 10 min`. A stage costs in proportion to its length, so pure doubling broke it at the long stages
  (a 64-h stage on a device filling an hour in 0.2 s is 12.8 s of work) — the claim that it held "for any device
  under ~15 s an hour" was wrong. So a stage after the first reaches no further past the front than this device
  fills in `PROGRESSIVE_PACE_MILLIS − PROGRESSIVE_PACE_MARGIN_MILLIS` at its measured `planHoursPerSecond`, and
  never less than the requirement's own 10 minutes. A single fill to the goal would hold the pace only while the
  whole fill takes < 10 s (60 tasks over 8 days: 5.4 s on the desktop, 2026-09-16).
- **A newer request cancels the stages the older one has not reached**, and the horizon watchers stand aside while
  a progressive fill is in flight (its own stages are not a gap).
- **A stage spends the pace it is not using on reaching the best score** (`SchedulerEngine.stageSearchMillis`): the
  requirement's pace is a stage within `PROGRESSIVE_PACE_MILLIS` (10 s) of the previous one, so a stage's search gets
  that minus `PROGRESSIVE_PACE_MARGIN_MILLIS` minus this device's measured cost of the stage's own passes, capped at
  `PROGRESSIVE_STAGE_SEARCH_MILLIS`. A stage whose search ran out without certifying (and without the solver finding
  anything) stops the later, larger stages of the same fill from searching: they could not finish either. The
  device's measured speed (`planHoursPerSecond`, which ranks it in elections) excludes the search time. Tests reduce
  on a virtual clock, so `SchedulerEngine.planSearch` is off unless a host turns it on (both do).
- **A stage searches one decision window past its horizon** (`ScheduleOptimizer.searchMarginMillis`,
  `ScheduleFill.Input.searchUntilMillis`) with the environment built that far, and emits only up to the horizon
  (`ProgressiveStageBoundaryTest`). The extension that keeps a stage's end as definitive keeps runs the stage's end
  did not bend.
- **The reducer's in-line re-plans are a first stage too** (`SchedulerReducer.reduceInlineReplan` for `ForceTaskSwitch`
  / `ForceTaskStart`, the same cap for `SetSleepSchedule`, `RemoveRecordPeriod` and the no-screen strip):
  `PROGRESSIVE_FIRST_STAGE_MILLIS` ahead with `INLINE_REPLAN_SEARCH_MILLIS` of search, and the horizon watcher extends
  them in doubling stages. They answer a press synchronously, so they may not hold the thread for a whole fill.

### The rule state is the question; the set of rules is the answer

`docs/scheduler_requirements.md` names two different things and they must never be reported under one name:

- the **rule state** is *"the set of tasks and their associated priority percentages, minimum execution time
  and resilience values"* — the scheduler's INPUT, authored by the user. `SchedulerDomain.describePlanRule`
  spells one line of it.
- the **set of rules** is what the scheduler RETURNS: instructions *"parameterized by $now line$ and $now
  line$ mode"* that give the future, each naming its § *Alternative Schedules* fallback.
  `SchedulerDomain.describeScheduleRules` spells it.

`fillSchedule`'s `rulesSink` reports both, as `SchedulerRunRules`, and the History window shows them as two
sections (`SchedulerRunEntry.ruleState` / `.rules`). Anything reporting a rule list must state the now-line
and the mode with it — the same instruction list read at another position of the line names another schedule,
so a list without its two parameters names none. That is why `SchedulerRunEntry` carries `nowMillis` and
`tpMode`, and why the returned rules are written as offsets from the line rather than as instants.

Only what the fill DECIDES is a returned rule: the picks (`TaskPanel.auto`) and the three dynamic periods it
placed (`screenBreak`). Pre-placed blocks, user-drawn periods and sleep windows are the § *Starting timeline*
— input, already in the rule state or authored by hand.

### When the plan is recomputed

- **`SchedulerDomain.schedulingSignature(state)`** is everything the plan is a function of except `now`.
  `launchRuleChangeReschedule` watches it (1 s debounce) and is the only rule-watching dispatcher of
  `RefreshSchedule`.
- **A TITLE'S TEXT IS NOT A RULE, so a rename is not a re-plan.** Two things about a title reach the plan and
  both are in the signature: whether it is BLANK (a blank title deletes, `docs/invariants/task-tree.md`, so the
  task leaves the schedulable set) and the ORDER titles put the tasks in, which is the tie-break
  `docs/scheduler_score.md` § *Ties* names — "higher priority first, then title". The text itself is not, and
  hashing it made every keystroke of a rename a rule change: renaming a task re-planned the whole account once
  per letter for a plan that could not come out any different (2026-09-22; `RenameIsNotARuleTest`). What the
  rename DOES do is rename the task's panels, in its own reducer — so the schedule shows the new name at the
  keystroke instead of waiting for a fill that has no reason to run.
- **Anything new that wants to re-plan belongs in the signature** (or in `requestReschedule`), not in a fresh
  dispatch site.
- **Time passing must never re-plan.** The advance tick only banks records; horizon growth and calendar navigation
  dispatch progressive `ExtendSchedule` stages (keep the head, append the tail). A re-plan of rules that did not
  change can only rewrite a schedule § *Progressive Calculation* has made definitive — which is why the hourly
  "staleness bound" that did exactly that was removed (2026-09-17, `ScheduleStalenessRuleTest` now pins its absence).
- Exactly one sanctioned exception, bounded: inside a task-tree transition only, a re-plan at every **run start the
  line reaches** (`taskTreeBlendDecisionKey`, ADR 0008) — one fill per run the transition spans, nothing outside one.
  That is the rules being parameterized by the line, not the plan going stale.
- The signature excludes records deliberately, so `RemoveRecordPeriod` refills inside its own reducer.
- **A RE-PLAN NOBODY IS WAITING FOR ANY MORE STOPS WHERE IT STANDS** (user rule, 2026-09-23: *"if the
  scheduler was already running, then it stops abruptly and runs again with the new data"*). A fill is
  straight-line CPU — tens of milliseconds on a small account, seconds on a real one — so `Job.cancel()`
  around it stops nothing: it ran to the end, published an answer about data nobody held any more, and (having
  lost the compare-and-set) started over. So every plan intent carries the **generation** it was asked under
  (`SchedulerIntent.RefreshSchedule.generation`), the engine answers whether that generation is still the one
  it wants (`SchedulerReducer.planAbandoned`), and the fill asks at the checkpoints it already has —
  `SearchBudget.checkAbandoned()` in the rollout, the improver, the exhaustive search and at every entry to
  `ScheduleFill.run`. An abandoned fill unwinds (`PlanAbandoned`) and the reducer hands back **the very state
  it was given**, so nothing half-planned is published and `dispatch` does not retry.
  - **Generation 0 can never be abandoned**, and the reducer holds that rule rather than the seam: it is what
    the in-reducer re-plans answering a press carry (`ForceTaskStart`, `ForceTaskSwitch`, a sleep edit), and a
    press must be in the state before it returns.
  - **The rules moving abandons at the EDGE, and the debounce only decides when to ask again.**
    `launchRuleChangeReschedule` stops the fill in flight the instant `schedulingSignature` moves, then waits
    out `RESCHEDULE_DEBOUNCE_MILLIS` before requesting the new one — which is what stops a typed title from
    leaving a fill per keystroke grinding in the background, each already stale. `PlanAbandonedTest`.
- **The engine's re-plans are dispatched ASYNCHRONOUSLY** (`SchedulerEngine.dispatchProgressivePlan` →
  `runPlan` → `planDispatcher`): the fill is 25-80 ms and the engine's scope is the main thread on both hosts. The rule
  does not move — same intent, same reducer — but nothing may read the new plan straight off
  `vm.state.value` after asking for one. The in-reducer re-plans (`ForceTaskSwitch`, `ForceTaskStart`,
  `SetSleepSchedule`, `RemoveRecordPeriod`, the no-screen strip) stay synchronous: they answer a press.
- **A SUPERSEDED FILL STOPS WHERE IT STANDS** (2026-09-22; `PlanAbandonedTest`). The user's rule: *"if the
  scheduler was already running, then it stops abruptly and runs again with the new data"*. A fill is
  straight-line CPU, so cancelling the coroutine around it stops nothing. Every engine re-plan carries a
  **generation** (`RefreshSchedule.generation` / `ExtendSchedule.generation`); the engine bumps it
  (`abandonRunningPlan`) at the signature EDGE — before the debounce — and on every new dispatch; the reducer asks
  `SchedulerReducer.planAbandoned`, and the fill asks at the checkpoints it already has
  (`SearchBudget.checkAbandoned`: every `ScheduleFill.run` entry, the rollout and search every 64 steps, the
  improver every 64 moves) and unwinds with `PlanAbandoned`. The reducer catches it and returns **the state it was
  given, the same instance**, so nothing half-planned is published. Generation **0** — the in-reducer re-plans
  answering a press — is never abandoned. Abandonment is not the budget expiring: an expired budget DEGRADES to
  the rollout; an abandoned one publishes nothing.
  See `docs/invariants/display-hot-path.md` for the compare-and-set this makes necessary.

---

