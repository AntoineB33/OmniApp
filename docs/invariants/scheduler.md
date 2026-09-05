# Scheduler

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Scheduler

→ ADR 0001. The model is `side-dev/README.md`; `side-dev/scheduler.py` is the reference and
`SchedulerPlan.kt` its port. `SchedulerDomain.fillSchedule` is a driver over that port.

- **`PlanWalk` is the ONLY copy of the scheduling rules.** `SchedulerPlanner.plan()` and `fillSchedule` are
  both thin drivers over it. Keep them in step (the one sanctioned divergence is the atomic block, below).
- **The pick is a function of the walk state at the cursor.** It cannot be answered by a point query — do not
  reintroduce an EDF/deadline-style shortcut.
- **A claim is the lag counted in the task's own slots**: `claim = (V − v)·p/m`. Do not simplify back to
  `min v`.
- **A chunk's scale is one ROUND**, `c = p·m_rival/(1 − p)`, floored at the task's minimum. The lift (boost)
  and the cap (round) are asked **separately**.
- **`last` is never picked twice in a row** unless it is the only candidate.
- **PRD §7 "Switch task" IS that same `last`, not a ban.** The button (and `Ctrl+Shift+Alt+Z`) records a
  `ForcedTaskSwitch(task, at)` and the fill hands it to `walk.setLast`, so the refused task keeps its clock and
  its share and is an ordinary candidate again from the second slot — and a task nothing can replace still
  runs. Do not give it a rule of its own, and do not put it in `schedulingSignature`: the press re-plans inside
  its own reducer, or dropping the spent marker would fire a second, un-refused re-plan. It stays live until
  **another task has actually been served past `at`** (`liveForcedSwitchTask`, read off the recorded past, so
  the resume contract holds); the advance tick drops it then.
- **PRD §13 "start this task now" is the SAME lever from the other end.** The task cell's menu records a
  `ForcedTaskStart(task, at)` and the fill puts that task in the **first slot it places** — charged like any
  other pick, so only that slot is the user's answer. Same liveness predicate as the refusal
  (`liveForcedStartTask`: outstanding until another task has been served past `at`), same reason it is not in
  `schedulingSignature`, same drop by the advance tick. Offered on a schedulable **leaf** only; asking for a
  task clears an outstanding refusal *of that same task*. It is answered in phase 1 **and** in phase 2 — a
  timeline nothing disturbs freezes before phase 1 places anything, and the request must not vanish there.
- **EVERY rule the fill makes also names WHO RUNS INSTEAD** (`TaskPanel.alternativeTaskId`,
  `SchedulerDomain.alternativeTaskAt`). `side-dev/README.md` § *Alternative Schedules*: *"The returned set of
  rules must also give for every $now line$ the task that must be scheduled if the task scheduled by the
  scheduler can't be scheduled now."* A panel IS one of those rules, so the answer rides on it — read from
  `PlanWalk.alternative` **before the clocks are charged** (the reference's `alt = self._alternative(v, cand,
  name, p_local)`), so it answers at the same instant and against the same claims as the pick it stands in
  for. `SchedulerPlanner.runRange` has always done this through its `PlacementCollector`; `fillSchedule` is
  the other driver and must stay in step. Four things it is: **named at the run's START** (the merge keeps the
  head's, which is the answer one uninterrupted plan would have given); **null where there is nobody** — a
  stretch only one task was allowed in, "the same task again" being no answer at all; **derived, never
  persisted and never synced**, recomputed in full by every fill exactly as the panel is; and answered in
  **both** phases — phase 2's analytic cycle names *the next task the rotation reaches*, said once for the
  whole repeat because the walk is not advanced through it.
- **PRD §7's refusal IS the README's use of that answer**, not a second mechanism. *"A program would simply
  read the rules, set this new task starting at $now line$, and run the scheduler again."* Refuse the
  scheduled task and the fill's next pick is exactly the one the rules named, because
  `pickNeediest(…, last = refused)` and `PlanWalk.alternative(…, chosen = refused)` are the same ordering over
  the same claims. Do not let the two drift into two answers.
- **The clock replay walks the past EDGE BY EDGE**, applying `relax` where the walk applies it. Its window is
  two `minPeriod`s measured in **schedulable** time, never wall time.
- **Only obstacles still AHEAD build the influence field.** The boost is capped (`maxBoost` = 6) and decays to
  a finite range. An exclusion that refuses everybody, or one shorter than the deprived task's own minimum,
  creates **no** field.
- **Excluded tasks are translated as a group, never clamped individually** — clamping destroys their ranking.
- **A window bounds only the tasks it turns away.** "Does the minimum fit?" counts instants the task may
  actually run; an interval nobody may run in suspends, one somebody else may run in ends.
- **A task about to start must be able to finish** before it would lengthen a rival's ban. A task already
  running has no such choice.
- **The resume contract:** a chain of re-plans is the SAME schedule as one long plan. Anything new the walk
  carries must be reconstructible from the history, or this breaks silently.
- **Do not answer a sliding period by re-planning per tick.** A mode-1 drag moves the owed pose with the
  line, and the plan under it was materialized at the last rule change: the answer is a display clip
  (`clipPlanForPinnedScreenBreak`), cutting what a break **refuses** — not what it covers.

### What reaches the scheduler

Only two things: **pre-placed blocks** (pinned/manual panels ahead of `now`, the kept head on an extension,
the served past) and **restrictive periods**. Nothing else, by any other route.

### The frozen past includes the block the line is STANDING IN

→ `side-dev/README.md` § *frozen past*: *"the schedule at `t < now line` never changes as `now line`
increases."*

- **A re-plan cuts the TAIL of the straddling auto panel and keeps its ELAPSED HEAD**, truncated at the line
  (`fillSchedule`'s `kept`). Cutting the whole panel is what shipped, and the head went nowhere: the advance
  banks a panel only once it has *wholly* elapsed (deliberately, so an in-progress one stays a panel), so work
  the app had told the user it was doing vanished from the timeline on every rule change — and, because
  `pastPeriodsForTask` reads those same panels, from the clock replay that seeds the walk, taking the resume
  contract with it.
- **The head is an ordinary auto panel**: the next advance banks it, `mergeSameTaskPanels` fuses it back with
  the tail (so it is folded into the merge input, not appended beside it), and it is behind the line so it is
  never a `futureBlocks` obstacle.
- **The chunk the line is in the middle of RESUMES; it is not re-picked** (`resumedHead` → `pending`, the
  reference's `Walk.run` `if head is not None and head[1] < minimum[head[0]]`). Without it, restoring the head
  makes `lastRun` refuse the very task that is running — "never twice in a row" firing on a run that never
  ended — and the block ends up one minimum *plus* whatever had already elapsed. `headRun` is not required to
  reach the line, so it is qualified by `lastRun`: a run that stopped an hour ago is history, not a chunk.
  **Both §7's refusal and §13's request drop the resume**, or the press is swallowed by an unfinished chunk.
  It is answered in phase 1 **and** phase 2, for the same reason `ForcedTaskStart` is.
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
  `0` for every kind except `no on-screen task`. Two things follow, and both are load-bearing: **a kind the
  user has just defined is added to every task at `0`** — a restrictive period restricts, so a new one turns
  everybody away until its own edit window hands somebody a value above zero, and it is why defining one
  still writes nothing to any task (absence *is* the default, which is also what makes a task created *later*
  carry the same answer) — and **"on screen" is not a flag**: it is exactly a `0` against `no on-screen task`,
  read through the derived `Task.onScreen`, which is the one kind whose default has to be `1` or the
  off-screen task would be the one that has to say so. `doableDuringBreak` is gone: all three dynamic periods
  are `no task allowed` end to end, so there is nothing there to be resilient to.
- **Three kinds are built in** (`PeriodKinds.BUILT_IN`): `no task allowed`, `no on-screen task` — the two the
  README names — and `before bed`, which PRD §17's sleep schedule lays by itself (below).
- **`no task allowed` is the one kind a task has NO resilience to define** (`PeriodKinds.isResilienceEditable`
  — the single predicate, applied by the edit window's row loop and by nothing else). It accepts nobody by its
  own name, so its multiplier is always `0` and there is nothing there for a task to choose; the window shows
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
  window's resilience section. The three built-ins are never in that list; `state.allPeriodKinds` is the one
  reading of "every kind a task can be resilient to". Removing a kind takes every task's override and every
  panel laid with it.
- **PRD §17's wind-down is a KIND, not a rule: `before bed` (`PeriodKinds.BEFORE_BED`).** The hour before
  each §17 bedtime is covered by a period of it (`SchedulerDomain.beforeBedPanels`, derived from
  `sleepPanels` so the hour drifts with the wake time it is measured back from). The hour is empty for the
  one reason any period empties a stretch — every task's default resilience to the kind is `0` — and a task
  given a value above zero works through it. It used to be a hard-coded extension of the sleep obstacle,
  which is precisely the second mechanism this model exists to prevent; do not put one back. Three things
  it is not: it is **not** `no task allowed` (a task may be given a value for it, and the edit window offers
  a row); it is **not** `coversNoScreen` (the user is at a screen in that hour, so it absorbs a dynamic
  period like any emptiness but is never a **rest** that bars the breaks after it); and it is **not** the
  user's, so it cannot be defined again or deleted (`isUserDefined` gates both, and an older payload's
  user-defined kind of that name collapses into it on decode, overrides intact).
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
  fan-out of `SetTaskResilience`. It is a sort-2 pop-up like the window it opens from, so it dismisses it.
- **A panel's kind is `TaskPanel.restrictiveKind`**, the single reading of `periodKind` and the legacy
  `noScreen`/`inactivity`/`sleep`/`screenBreak` flags. A payload written before kinds existed is healed from
  those flags on decode. **Ask through it, never through the four flags**: a period of a kind that has no
  flag — `before bed`, or one of the account's own — is a period too, and spelling the flags out said so only
  for the four that have one (that is why the reducer's `isTaskPanel` is `!panel.isRestrictivePeriod`).
- The walk reads **`weightsAt`** — each task's percentage after resilience — and races on `localSharesOf`,
  those weights renormalized over whoever may run. Service is charged against the **effective** weight
  (`serveWeighted`, the reference's `v += served / w[name]`), which is what makes a multiplier mean "half the
  percentage for as long as the period lasts" and not "the same alternation, one boundary later".
- The influence field is **fractional**: a resilience of `0.4` deprives a task of `0.6` of its share there,
  and the compensation owed is that fraction of a flat refusal's (`deprivationsOf`).
- The **steady cycle inherits the effective shares** of the window it is attached to. Built on the nominal
  ones it would answer 50/50 under a standing period that halves one side.
- **NO IDLING is the hard constraint and the minimum time is the SOFT goal, and only one thing may empty a
  stretch: that nobody may run in it.** `side-dev/README.md` § *No idling* — *"anywhere that is not covered by
  restrictive periods which would prevent any task from being scheduled, the scheduler must schedule a task"* —
  against § *Soft Minimum Execution Time*, which is *"another optimization goal"*. So a gap shorter than every
  minimum is **worked**, and the panel there is simply short. `fillSchedule` idles exactly where
  `SchedulerPlanner.runRange` and the reference do (`if not cand: emit(IDLE)`), and nowhere else. It carried
  two extra reasons until 2026-09-03 — a `_fits_from` candidate filter and a sub-minute `crumb` rule with a
  `free_tail` stretch beside it — both citing `scheduler_logic.py`, a reference file that no longer exists,
  and both unsanctioned divergences from the other driver. Do not put either back: PRD §9 now states the
  soft/hard split, and the chunk floor in `PlanWalk.chunkMillis` is what serves the soft goal.
- **The one panel shorter than a minute is at `t_p` itself**, and it is the README's: modes 1 and 2 push the swept
  period onto the line as the half-open `(t_p, t_p + d]`, so the line's own instant is uncovered and *"the
  passing of the $now line$ creates task panels not covered by the period"*.
- A dynamic period and every grey period **suspend** a chunk rather than cutting it (PRD §15/§17), where a
  screen-zone edge cuts it. That is about the chunk's REMAINDER, never about whether anything is placed.

### `plan()` is the reference; `fillSchedule` is the app

`SchedulerPlanner.plan()`'s phase 1 is a **literal port of `side-dev/scheduler.py`'s `Walk.run`** and is
checked slot-for-slot against it (`SchedulerPlanTest`). Where the two must differ, the difference is named:

- **Zero-priority tasks stay last-resort candidates** (`permittedAt` / `candidatesAt`). The reference raises
  when nothing has a positive priority; the app must still fill the calendar for an all-zero tree, and a
  period may accept *only* a zero-priority task.
- **`Fraction` → `Double` millis.** The reference keeps exact rationals; KMP has no rational type.
- **`fillSchedule` keeps its own suspension rule for the app's dynamic periods** — a chunk suspends across a
  break and resumes with its minimum intact, where the reference simply cuts a chunk at the next environment
  edge. That is PRD §15, and it is the one place the driver is deliberately not the walk.

The resume contract is what the reference actually guarantees, and no more: a chain of links carrying the
walk's own state is the single plan, placement for placement. Re-seeding from the DRAWN past (`_seed`, which
is what the app must do — it re-plans from records, not from a live walk object) is an approximation, and the
reference's own misses at the first slot after a period that admitted a strict subset re-opens. Do not assert
more than that.

### When the plan is recomputed

- **`SchedulerDomain.schedulingSignature(state)`** is everything the plan is a function of except `now`.
  `launchRuleChangeReschedule` watches it (1 s debounce) and is the only rule-watching dispatcher of
  `RefreshSchedule`.
- **Anything new that wants to re-plan belongs in the signature** (or in `requestReschedule`), not in a fresh
  dispatch site.
- **Time passing must never re-plan continuously.** The advance tick only banks records; horizon growth and
  calendar navigation dispatch `ExtendSchedule` (keeps the head, appends the tail).
- Exactly two sanctioned quantized exceptions: the hourly **staleness bound**
  (`SCHEDULE_STALENESS_MILLIS` = 1 h, a bound re-armed by `requestReschedule`, not a tick) and the **task-tree
  blend cursor** (ADR 0008).
- The signature excludes records deliberately, so `RemoveRecordPeriod` refills inside its own reducer.

---

