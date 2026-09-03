# ADR 0001 — The scheduler is a cyclic proportional-share model

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *Scheduler*.

## Where the rules live

`side-dev/README.md` is the specification. `side-dev/scheduler.py` is the reference
implementation; `SchedulerPlan.kt` (`SchedulerPlanner` + `PlanWalk`) is its Kotlin port, and
`SchedulerDomain.fillSchedule` is a **driver** over that port which maps OmniApp's world onto the
reference's two inputs (**pre-placed blocks** and **restrictive periods**, §4) and materializes panels.

`PlanWalk` is the **only** copy of the scheduling rules. `SchedulerPlanner.plan()` (the rule-list
form) and `fillSchedule` are both thin drivers over it, so the two can never disagree.

Supporting files in `side-dev/`:

| File | Role |
| --- | --- |
| `scheduler.py` | reference implementation (`uv run scheduler.py --check` — every README clause, asserted) |
| `test_configs.py` | the shape of the ONE test, its default, and `test_config.json` (the test as last edited) |
| `tests_displayer.py` | runner + GUI (`uv run tests_displayer.py --verify`); the window's editor is where the test is written |

`verify_moving` only certifies that a rule list is *self-consistent* — a different-but-consistent
answer passes it silently. **There is no frozen-answer snapshot** since the reference was rebuilt
from the README (2026-08-27): the checks say the rules are consistent and hit their targets, not
that they are still the same rules.

## 1. The pick and the chunk

Each leaf carries a **virtual clock** `vᵢ = servedᵢ / pᵢ` (Weighted Fair Queuing). The cursor serves
the task with the biggest **claim** and gives it just enough to catch the runner-up, never below its
minimum — so shares converge on the priority percentages **at the smallest scale the minima allow**
(the README's "A 10min, B 10min", never "A 1h, B 1h").

### A claim is the lag counted in the task's own slots

`claimᵢ = (V − vᵢ)·pᵢ/mᵢ`, where `V = Σ pⱼvⱼ / Σ pⱼ` is the priority-weighted mean of the clocks —
the point every task would sit at had service been exactly proportional. (`Σ p·v` is `Σ served`, so
the reference needs no new state.) See `SchedulerPlanner.claims` / `Scheduler._claims`.

Comparing raw `v` is the right question where service is continuous. It is the *wrong* one here
because service is **quantized** by the minima: one slot moves a task's clock by a whole period of
its own `T = m/p`, and those periods differ by the priority ratio.

> **Post-mortem (2026-08-21).** Read raw, `side-dev` test 14 (A of 45 min at 50 % against twenty of
> 45 min at 2.5 %, eight days, nights 23h–8h) said every one of the twenty still at 0 outranked A the
> moment A had taken ONE slot — so they took twenty slots in a row before A's second. The README's
> monolithic block, assembled out of twenty tasks instead of one, leaving A **5 % of the first day**
> against a target of 50 %. The deficit is never repaid either: the never-twice rule caps A at every
> other slot from then on and the round cap will not let its slot grow.
>
> Counted in slots, A interleaves from the first morning: one long plan gives **49.4 %** (the missing
> slot being the cold start), and test 12's A moved 33.4 % → 43.0 % with its privileged ten
> 35.1 % → 25.9 % against a target of 25 %.

Where every task shares one period `m/p` the claim is a monotone transform of `v` and picks exactly
what the raw clock picked — which is why nothing else in the reference suite moved (tests 1–10 are
byte-identical in `rules_snapshot.txt`). **Do not "simplify" it back to `min v`.**

`chunkMillis`'s **rival** reads the same claim (who runs next is an ordering question); its `need`
deliberately stays in raw-clock terms (how much catching up — a magnitude).

Tests: `SchedulerPlanTest.reference_test_14_a_50_percent_task_interleaves_with_twenty_small_ones_from_the_first_morning`,
and `side-dev`'s own `--verify`.

### Open defect: seeding in a pool of interchangeable tasks

The 8-day span exposes a **separate, still-open** defect. `check_resume_contract` fails 3 of 46
resumptions (112 h, 136 h, 184 h), all B-against-B, and the case reports 47.5 % instead of the long
plan's 49.4 %.

Cause: at such a resumption fourteen of the twenty seed to an *exactly equal* claim, so the pick
falls through to the name tie-break — a 2-`minPeriod` lookback cannot reconstruct whose turn is next
in a pool of twenty interchangeable tasks. That is a seeding (`_replay_clocks` / `_lookback_start`)
question, not a pick one. The three-day span never reached those positions.

### The scale of a chunk is one ROUND, not one period

A share is a ratio and a ratio holds at any scale, so "catch the runner-up" alone permits a task owed
twenty of its rival's slots to take them as ONE block twenty times as long: exact percentages, and
precisely the answer the README refuses. The never-twice rule cannot stop it, because a slot is one
pick however long it is.

So the natural unit of a slot is the task's share of a round in which it and its rival each run
once — `c/(c + m_rival) = p`, i.e. **`c = p · m_rival / (1 − p)`**, floored at the task's own
minimum. The field then LIFTS (`floor · boost`) while the round CAPS (`max(minimum, round) · boost`).
See `SchedulerPlanner.roundUnitMillis` / `Scheduler._round`.

**The lift and the cap are asked separately, and that is load-bearing.** A boost is owed whether or
not anybody is there to race, while a round means nothing without a rival — and the atomic block is
exactly the case with no rival (a task short of its minimum is the only candidate). Gating the lift
on having a rival silently shrinks the README's own example from `B 1.5` to `B 1.0`.

This **replaced `p · T`**, the share of a whole *period*: identical wherever there are two tasks, far
too large wherever there are more, since a period is the spacing of the SLOWEST task's slots.

Two consequences to know:

1. It is what makes the resume contract hold at a coarse ratio — a run longer than a minimum has
   interior instants where `last` bars the running task and a re-plan picks somebody else, which is
   how `side-dev` test 14 failed 3 of 16 resumptions with a 15-hour block of A.
2. It **lowers what a deprived task recovers per slot** (6 × 45 min instead of 6 × 900 min in a
   21-task case): test 12's A moved 37.6 % → 33.3 % of the schedulable timeline. That is the density
   rule's price and it is deliberate — compensation is delivered as a denser presence rather than as
   one long catch-up.

### steady_cycle needed the same rule twice over

`Scheduler.steady_cycle` / `SchedulerPlanner.steadyCycle` starts every clock level and gives each task
exactly its share of one period, so every one of them ends it owing nothing and the claim cannot order
them. Read straight off, it serves the whole of the fastest task's share before the slowest one's turn
and `_tidy`/`pushSlot` merges it back into the block.

What that loop settles is the **multiset**, not its order. The sizes are the clock's; the **order** is
density's — nobody twice in a row while somebody else still owes a slot this period, most slots left
going first. Same multiset, so the shares are untouched.

Tests: `SchedulerPlanTest.reference_test_4_minimums_force_the_period_and_the_shares_stay_exact` (the
reordered cycle), `…the_readme_s_atomic_block_example_…` (the lift with no rival), `side-dev --verify`.

### Never twice in a row

`last` is never picked twice in a row unless it is the only candidate: a task that is owed a lot gets
a *denser* presence, not one long block.

## 2. Seeding the clocks from the past

The clocks are seeded by replaying the committed past (`SchedulerPlanner.replayClocks` /
`Scheduler._replay_clocks`), merged per task so a record and the auto panel that banked it count once.

**The replay is the walk's own law, not a flat sum.** It walks the past EDGE BY EDGE and applies
`PlanWalk.relax` exactly where the walk applies it — over served time, with that stretch's own period,
against whoever was allowed to run in it.

Reading `served/p` over a window instead rebuilds a state the walk never held (a task idle for an hour
reads as owed the whole hour, where the walk had already forgotten most of it). Edge by edge is
load-bearing because a block in the history is a run the walk MERGED: a period may begin inside one,
and the fifteen privileged-only minutes a privileged task works straight through leave no mark on the
timeline at all.

### The lookback window is TWO minPeriods, measured in SCHEDULABLE time

`SchedulerPlanner.lookbackStart` / `Scheduler._lookback_start` reaches back until it has covered two
`minPeriod`s of time **at least one task was allowed to run in**.

Two, not one: a period is exactly the spacing of one task's slots, so a one-period window samples the
past at the rate the schedule repeats at — whatever the phase, a task whose slot has just left it and
whose next has not yet entered reads as **never served**, and a task that reads as never served claims
the maximum however small its priority.

> **Post-mortem (2026-08-18).** Measured in wall time, an instant nobody may run in pushes real
> service out of the window; a task served just before the last long exclusion then reads as **never
> served** and leapfrogs the one that has been waiting. Over a timeline whose nights take nine hours
> out of every twenty-four that is not a rounding error: in `side-dev` test 12 it was the whole
> difference between a 50 %-priority task getting **2 %** of the schedulable timeline and getting
> **35 %** (91 min vs 993 min over three days), because each of the twenty low-priority tasks came
> back due once per wall-clock window and spent most of the usable time on its 45-min minimum.

Faithful to the reference, it counts **periods only** — a stretch occupied by a block owned by nobody
(OmniApp's scheduled sleep) is *not* stepped over, in either implementation.

Consequence for OmniApp, and it grew with the resilience model: every `no task allowed` period is now
all-refusing, and all three screen breaks are that kind **end to end** (ADR 0003), as are inactivity
periods and hand-drawn grey. So the correction is worth minutes per hour rather than the sub-minute it
was worth when only a pose's closed head refused everybody. The *replay* is not inert either — it is
what caps what a long past block buys.

Test: `SchedulerPlanTest.the_clock_replay_window_is_measured_in_schedulable_time_not_wall_time`.

Ties resolve by the order the candidate list arrives in, which is where PRD §9's tie-break (highest
absolute priority, then title) is applied.

### Rejected: point-query fills

This replaced a debt-with-a-natural-bound (`C = Σ mᵢ`) fill and, before it, an EDF (`deadline = m/p`)
fill. The EDF helpers `edfPeriodMillis` / `nextTask` were deleted because a static period `T = m/p`
predicts nothing the walk does.

**Do not reintroduce a point-query of that shape.** The pick is a function of the *walk state* at the
cursor, so it cannot be answered without walking the fill. (The §8 manual-add pick is a different
thing entirely and is unaffected — `SchedulerDomain.manualAddTaskId`.)

Tests: `SchedulerPlanTest` replays the reference's own ten cases slot for slot, plus two 50-rule days
of the test-12/13 break grid.

## 3. The influence field (`tau`) — how deprivation is compensated

A fixed block owned by ANOTHER task and a period that scales a task's share down are **the same event** —
a *deprivation* — and are reduced to one object (`SchedulerPlanner.deprivationsOf` / `Scheduler._sources`;
touching/overlapping ones merge, so ten short bans in a row are one long ban).

**It is fractional, because a resilience is** (§4). What a stretch costs a task is
`Σ (1 − mult)·length`, where `mult` is its multiplier there: a resilience of `0.4` deprives it of `0.6`
of its share, and buys `0.6` of the compensation a flat refusal would. A binary reading would be two
mistakes at once — a `0.4` either bought a full refusal's compensation or bought nothing, and the
README's own sentence ("a multiplier on the priority percentage") has no such cliff in it.

It is computed **per INSTANT, not per period**: edges are cut first and what an instant refuses is the
UNION of everything covering it — the only reading under which overlapping periods mean anything.

**Only obstacles still AHEAD build the field** (`_set_field(pre, periods)`). What already happened is
history, not a blockage the timeline has to be compensated around; it reaches the walk through the
clocks instead.

Around an exclusion of length `L` the deprived task's slots may grow by
`boost = 1 + min(maxBoost−1, Σ (L/tau)·e^(−d/tau))`, symmetric on both sides.

Two consequences **are** the model:

- The boost is **capped** (`maxBoost` = 6), so compensation grows like `tau·ln(L/tau)` — an exclusion
  10× longer buys a *few* times more, never 10× more.
- It vanishes below `fieldFloor` at a finite distance (`fieldEndMillis`), so the plan returns to its
  steady cycle.

Boosted time is charged **at the boosted rate** (`v += c/(p·boost)`), so it is a genuinely higher
local share and is never clawed back.

### Forgetting

An imbalance beyond one period is forgotten exponentially (`PlanWalk.relax`), which is what stops 17 h
of a task pinned yesterday from buying its peer 17 h of catch-up. Since the forgetting is now replayed
over the past as well (`replayClocks`), that block leaves the pinned task at **exactly one period**
ahead however long it ran, so the peer opens with one catch-up chunk and the timeline is square again —
not the ~270 min an un-forgotten reading used to owe it
(`SchedulerPlanTest.a_massive_past_exclusion_buys_a_bounded_compensation_not_an_equal_one`).

### Excluded tasks are TRANSLATED, never clamped

The same function holds an **excluded** task's clock within one period of the served pool, and it does
so by **translating the whole excluded set**, never by clamping each member separately.

> **Post-mortem.** Clamping set every task past the bound to the *same* value, so a period refusing
> eleven tasks left all eleven tied and their priorities stopped deciding which went first (measured
> in `side-dev` test 12: eleven distinct clocks collapsed to one at every hourly privileged-only
> period).

The bound is a property of the group's distance from the pool; the gaps *inside* the group are the
claims themselves. Where the group is spread wider than 2·period and cannot fit the band at all, the
credit cap wins — it is the load-bearing half. Test:
`SchedulerPlanTest.a_period_does_not_erase_the_ranking_of_everyone_it_excludes`.

### What creates NO field

- An interval that deprives **everybody** — a block owned by nobody, a period nobody is resilient to.
  Nobody is served there, so nobody is deprived *relative to* anybody, and a wait no rival profits from
  is a pure delay the virtual clock already repays exactly. (This is why a look-away recurring every
  20 min forever does not distort the plan around each occurrence, and it is now the answer for the
  poses too, which used to have an accepting tail that did distort.)
- A deprivation **shorter than the deprived task's own minimum** — it cannot have cost that task a
  slot, only delayed it, and a delay is already repaid exactly by the virtual clock the moment the ban
  lifts. Compensating it here would pay the same debt twice and leave a 20-s ban swelling its
  neighbours forever after
  (`SchedulerPlanTest.a_ban_shorter_than_the_deprived_task_s_own_minimum_creates_no_field`).

`tau` defaults to the minimal period `max(mᵢ/pᵢ)`; there is no separate half-life constant any more.

## 4. OmniApp's mapping onto the reference: resilience is the whole of it

**Pre-placed blocks** = pinned/manual panels ahead of `now` + the kept head on an extension + the
already-served past.

**Restrictive periods** = every panel `TaskPanel.restrictiveKind` names one for. **Nothing reaches the
scheduler any other way.**

### A period is a start, an end and a KIND; a task has a resilience to each kind

`side-dev/README.md` § *Restrictive Period*. A resilience is a **multiplier in `[0, 1]` on the task's
priority percentage for as long as a period of that kind lasts**: `0` forbids it there, `1` leaves it
untouched, anything between scales its share. Overlapping periods **multiply**, so the strictest still
forbids and the model needs no precedence rule.

`PeriodKinds` is the one reading of a resilience map, and the app carries **three built-in kinds**:

| Kind | Default resilience | What it is |
| --- | --- | --- |
| `no task allowed` | **0** | the kind that, by its name, accepts nobody: sleep windows, inactivity periods, hand-drawn grey, and all three screen breaks end to end |
| `no on-screen task` | **1** | the §9 screen zone: a no-screen period, and the gap mode 2 covers behind the now-line |
| `before bed` | **0** | PRD §17's wind-down: the hour before each bedtime, laid by the sleep schedule itself (2026-08-29) |
| anything the user defines | **0** | `SchedulerState.periodKinds`, defined by the `+` in the task edit window |

**`before bed` is built in because the sleep schedule lays it, not because the README names it.** PRD §17's
wind-down used to be a hard-coded extension of the sleep obstacle for the task fill — a *second* mechanism
for "where may this task run", which is the one thing this model exists to prevent. Expressed as a kind it
needs no rule of its own: `SchedulerDomain.beforeBedPanels` lays `[bedtime − 1 h, bedtime)` for every §17
window (derived from `sleepPanels`, so the hour drifts with the wake time it is measured back from), the
hour is empty because the default is `0`, and a task the user hands a value above zero works through it —
which nothing could do while the rule was hard-coded. Three properties fall out of *which* kind it is, and
each was chosen:

- **Editable, unlike `no task allowed`.** "I may still do this in the hour before bed" is a real answer for a
  task to give, so the edit window offers the row and the kind has its own `PeriodKindEditWindow`.
- **Not `coversNoScreen`.** The user is expected to be at a screen in that hour (PRD §17 lets the screen
  breaks fall in it), so it absorbs a dynamic period like any other emptiness but is never a *rest stretch*
  barring the breaks that follow. `no task allowed` would have made the wind-down re-anchor every bar.
- **Not the user's** (`isUserDefined` is false), so it can be neither defined again nor deleted, and an older
  payload that held a user-defined kind of that name collapses into it on decode with the tasks' overrides
  intact — they are keyed by the name, so they go on answering for the period they were written for.

The periods themselves are **derived**, like the sleep windows: `before-bed/{wake day}` is cut and
regenerated by every fill, is an `isRegeneratedPanel` (out of the sync fingerprint and out of
`schedulingSignature`), draws as a grey band with no Edit and no Remove, and the "Stop work" cue keys on the
*period's* start rather than recomputing `sleep.start − 1 h`, so the notification and the band cannot drift
apart.

**`Task.resilience` holds OVERRIDES ONLY**, and that single decision carries two of the README's
sentences:

- **A kind the user has just defined is added to every task at `0`** (2026-08-29; it was `1` before).
  A restrictive period restricts: a new one turns everybody away, and its own edit window is where
  somebody is let back in — which is the workflow that window exists for. "Added to every task" is not
  a migration that stamps a `0` onto every task, it is the *absence* of an override, so defining a kind
  still writes nothing to any task at all, removing one takes only the overrides that named it, and a
  task created **later** carries the same answer as the ones that were already there. The rejected
  alternative was to write an explicit `0` to every existing task at definition time: same first
  screen, but the account then holds N rows saying what the default already says, and the next task the
  user creates silently disagrees with all of them.
- **"On screen" is not a flag.** It is exactly a `0` against `no on-screen task`, read through the
  derived `Task.onScreen`. `Task.DEFAULT_RESILIENCE` (a task the user has just created is on screen) is
  a default for a **task**; `PeriodKinds.defaultResilience` is a default for a **kind**. They are
  different questions, and the clipboard writes the difference between the two (ADR 0012).

`no on-screen task` is the one kind whose default is the *other* answer, and it has to be: "on screen" is a
`0` against it, so an off-screen task would otherwise be the one that had to say so — and every task the app
has ever created would carry a redundant override.

**`no task allowed` is the one kind the edit window offers no field for** (2026-08-28,
`PeriodKinds.isResilienceEditable`). It accepts nobody by its own name — so unlike every other kind, there is
no value there for a task to choose, and a field would have been offering to write one. This is a rule about the **window**, deliberately and only: `resilienceFor` still answers for the kind on
every path (that is how a grey period refuses everybody), an override an older payload wrote still decodes,
syncs and is obeyed by the walk, and nothing below §4 changed.

### The period edit window: one kind, every task

The task edit window's resilience section is *one task, every kind*. The **period edit window**
(`PeriodKindEditWindow`, 2026-08-29) is the same table read the other way round — *one kind, every task* —
and it is the question the user actually has the moment a period is defined, because defining one turns
everybody away and somebody has to be let back in. Every row of the section carries a **✎** onto it.

Three things it holds, and the reasons they are there rather than somewhere else:

- **Delete.** A period had no home of its own before, so its removal lived as a `×` on a row of *one task's*
  window — which read as "remove this from this task". The window is where a period is an object, so it is
  where the period is deleted. Offered for a user-defined kind only; the README names the other two.
- **The schedulable leaves**, checkable, each with its own percentage (`SchedulerDomain.periodKindTaskRows`).
  Parents are left out for exactly the reason the task edit window shows a parent no resilience section: the
  scheduler places leaves, so a value on a parent is a number nothing reads.
- **A bulk field**, shown only while something is checked, holding the value the checked tasks share or
  **blank** where they do not (`SchedulerDomain.commonResilience` — `null` is a real answer, not a missing
  one). Blank is what makes "select all, type 100" a safe gesture on a table you have not read.

**One intent, `SetPeriodResilience`, for both the bulk field and each row.** The temptation is to fan out
`SetTaskResilience` per task; that is right arithmetically and wrong for the history — checking twenty tasks
and typing one percentage is one gesture, so it must be one Undo/Redo unit, and twenty units would need
twenty `Ctrl+Z` to put back. A call that moves nobody records nothing, exactly as the single-task intent
already did.

It is a **sort-2** pop-up, so opening it dismisses the task edit window it came from, discarding what was
half-typed there. That is the sort's price (`ui/PopupWindows.kt`), not an oversight: "the window of period A"
and "the window of period B" are two different windows. Saving the task window on the way out was rejected —
a button that silently commits an unrelated form is worse than a button that discards visibly.

### What this replaced

| Was | Is |
| --- | --- |
| grey period: accepts nobody | a period of kind `no task allowed` |
| no-screen period: only tasks needing no screen | a period of kind `no on-screen task`, which multiplies an on-screen task by its `0` |
| everywhere else: only tasks that need a screen | **nothing** — a task no period covers is unaffected |
| 20-s look-away: nobody, end to end | `no task allowed`, end to end |
| 5-min pose: a closed minute, then `!onScreen && doableDuringBreak` | `no task allowed`, end to end |
| 15-min pose: every `!onScreen` task from its first second | `no task allowed`, end to end |

Three retirements are worth naming, because each was a rule and is now a consequence:

1. **`ScreenBreakPeriod` and `screenBreakOpenStartMillis` are gone.** A break has no *shape* to read any
   more (ADR 0003), so the calendar no longer draws a solid head and a hollow tail — there is one span
   of one kind. A task works through a break exactly when it has been given a non-zero resilience to
   `no task allowed`, which is the same sentence and the same code path as any other kind.
2. **`doableDuringBreak` is gone**, with nothing left for it to gate.
3. **An off-screen task is no longer CONFINED to no-screen periods.** A period can only multiply what it
   covers and says nothing about the timeline it does not, so "only tasks that need a screen may run
   everywhere else" is not expressible in this model — and it should not be: the README describes where
   a task is *restricted*, never where it is *required*. This is a real behaviour change and the one the
   user is most likely to notice.

### How a multiplier reaches the walk

Three functions, and the split between them is the model:

- **`weightsAt(blocks, windows, millis)`** — every task's percentage **after** resilience at that
  instant: `share × Π multiplier` over every window covering it. Blocks are folded in here too (inside a
  pre-placed block, only its own task has a weight).
- **`localSharesOf(weights, candidates)`** — those weights renormalized over whoever may actually run,
  which is what the race is decided on.
- **`serveWeighted(id, duration, weight)`** — service charged against the **effective** weight, the
  reference's `v += served / w[name]`.

The third is what makes a multiplier mean what the README says. Charged against the *nominal* share, a
resilience of `0.5` would produce the same alternation one boundary later rather than half the
percentage for as long as the period lasts. For the same reason the **steady cycle inherits the
effective shares** of the window it is attached to (`periodOf` / `steadyCycle` / `coarseCycle` all take
them as a parameter): built on the nominal ones, a standing period halving one side still answered a
flat 50/50.

`PlanWindow` carries `multipliers` (per task) plus a **`defaultMultiplier`** for everybody it does not
name. That is what lets a kind-built window say "I turn nobody away by default" without a roster of
every task in the account, and it is also how the reference's binary form is expressed —
`PlanWindow.accepting(...)` is a `defaultMultiplier` of `0` with the accepted set at `1`.

### Consequences worth knowing

- A gap too short for any minimum is **worked all the same**, and the panel there is short. `side-dev/README.md`
  § *No idling* is a hard constraint and § *Soft Minimum Execution Time* is *"another optimization goal"*, so
  the minimum yields where the two meet. This reverses what this ADR said until 2026-09-03 — "left empty (or
  absorbed by the previous slot, the reference's `free_tail`)" — which was read off `scheduler_logic.py`, a
  reference file that no longer exists; `side-dev/scheduler.py` idles only where the candidate set is empty,
  and `SchedulerPlanner.runRange` always did. PRD §9/§10 were amended to match.
- A period that refuses everybody deprives everyone equally and so creates **no field** (§3).
- A dynamic period — like every grey period (`suspendRegions` / `suspendStarts`) — **suspends** the chunk it
  meets and resumes it on the far side, where a screen-zone edge or a fixed block **cuts** it (PRD §15, and
  §17 for sleep in the same words). Load-bearing: without it the 20-min look-away cadence would make the
  default 45-min minimum permanently unschedulable. Note this is a rule about the chunk's REMAINDER only —
  since 2026-09-03 nothing asks "does the minimum fit?" at all, that question having been the candidate
  filter No idling forbids.

## 5. A window bounds only the tasks it turns away

A context change is not a wall. What bounds a chunk is the first instant *its own* task is turned away
(`SchedulerPlanner.blockedFrom`), never merely the next instant something changes. Treating the two as
one makes a short ban punch an unfillable hole into somebody else's slot (no 45-min minimum fits in
the 4 minutes left of a 5-min break) and the timeline goes idle for want of anything that fits.

**"Does the minimum fit?" counts the instants the task may actually RUN**
(`SchedulerPlanner.fitsFrom` / `_fits_from`): an interval nobody may run in only *suspends* a run, so
it is stepped over and costs the task nothing but time, while an interval somebody ELSE may run in
ends it. That is the whole of why a 20-s look-away every 20 min does not make a 45-min minimum
permanently unschedulable — pinned by
`SchedulerPlanTest.a_45min_task_works_straight_through_a_20s_look_away_every_20min`, which replays a
full 50-rule day of the reference's break grid.

Its counterpart: a task about to **start** must also be able to **finish** before it would lengthen
somebody else's ban (`SchedulerPlanner.wallsOf` + `clears` / `_walls` + `_clears`). A run is owed its
whole minimum, so beginning one with less than that left before a rival returns does not *use* the
period — it extends the exclusion the rival is already serving, and that is the one thing the virtual
clock cannot repay (the rival is not a candidate at the instant the decision is made). A task already
running has no such choice to make. This is what leaves the 5-min pose empty in
`SchedulerPlanTest.a_5min_pose_the_user_works_through_is_left_empty_rather_than_lengthening_the_other_task_s_ban`.

For the same reason a chunk is only ever *cut short* by a rival becoming placeable again
(`SchedulerPlanner.nextPlaceable`), and never below what the slot still **owes** of its minimum —
consecutive slots of one task are one slot, so a task already running has paid that minimum and may be
cut anywhere; only a slot that is *starting* owes the whole of it.

The cycle is attached in the phase the walk would have gone on with (`phaseCycle`, shared by `plan()`
and `fillSchedule`'s phase 2), because a cycle built from a blank slate always opens with the same
task and opening there after a prefix that starved another one hands the first task two slots in a row.

In `fillSchedule` these no longer collapse to anything simpler, and that is the resilience model's
doing: a period used to partition the timeline into complementary accepted sets (on-screen here,
off-screen there), so every boundary turned everybody away and "the next change" and "the next instant
this task is refused" were the same instant. A period that merely *scales* a share turns nobody away at
all, so the two questions have genuinely come apart and both are asked. The PRD §15 suspend/resume
exception still applies on top, at a dynamic period.

Ported 2026-08-05 with the reference's own rewrite and brought back in step 2026-08-20.
`SchedulerPlanTest` replays all ten reference cases against it — including **test 9b**, two OVERLAPPING
periods on a timeline they do not cover, the case that forces `allowedAt` to be the *intersection* of
every covering window's accepted set (an instant no period covers refuses nobody).

## 6. The atomic block — where `plan()` and `fillSchedule` part company

`SchedulerPlanner.plan` carries the reference's `_head` / `run_served` / `pending` trio: while a task
is still short of its minimum it is the **only** candidate, and where a period refuses it there is
**no** candidate — so the period is scheduled with **nothing** (`side-dev/README.md`'s own headline
example, pinned by
`SchedulerPlanTest.the_readme_s_atomic_block_example_schedules_the_whole_period_with_nothing`).

Also: an idle stretch **suspends** a run rather than ending it; a resuming task is floored at what it
still **owes** (not a fresh whole minimum); a pre-placed block is **walked edge by edge** — where a period refuses the block's own task
the block is suspended and resumes on the far side instead of running through it (`side-dev` commit
`48a8a69`; `a_pre_placed_block_is_suspended_where_a_period_refuses_its_own_task`).

### Where `plan()` and `fillSchedule` still differ, and where they no longer do

`plan()` is the reference-conformance surface (nothing outside the tests calls it); `fillSchedule` is
the app. **Keep them in step everywhere else.** The complete list of sanctioned differences:

1. **Zero-priority tasks stay last-resort candidates** (`permittedAt` / `candidatesAt`). The reference
   raises when nothing has a positive priority; the app must still fill the calendar for an all-zero
   tree, and a period may legitimately accept *only* a zero-priority task.
2. **`Fraction` → `Double` millis.** The reference keeps exact rationals; KMP has no rational type, so
   every equality in the port is an epsilon comparison (`CHUNK_EPSILON_MILLIS`).
3. **`fillSchedule` keeps its own suspension rule for the app's dynamic periods** — a chunk suspends
   across a break and resumes with its minimum intact, where the reference simply cuts a chunk at the
   next environment edge. That is PRD §15, and it is the one place the driver is deliberately not the
   walk.

Item 3 is what the **atomic block** divergence has narrowed to. It used to be stated the other way
round — "`fillSchedule` does not adopt the pending rule, because PRD §15/§9 want the break's own
accepted set to fill a period an on-screen chunk is suspended across, and the atomic block would leave
every break and every no-screen zone permanently empty". Half of that reasoning is gone with the shapes:
a break no longer *has* an accepted set to fill it with, so "the period is scheduled with nothing" is
simply the right answer there. What remains is the chunk's own minimum. `fillSchedule` keeps a `pending`
of its own — the task whose chunk a dynamic period interrupted, and what it still owes — so it resumes
on the far side instead of being re-picked mid-chunk, where the reference would have ended the run at
that edge.

The rule the two now share, and which the resilience model is what made shareable: **who may run in a
period is its kind and the task's resilience to it, and nothing else.** `fillSchedule` used to exclude
the suspended task from its own break — sensible while a break's accepted set was a shape the task was
in for the wrong reason ("off-screen work only"), and wrong now that being in it at all *means* the user
gave that task a non-zero resilience to `no task allowed`.

## 7. The resume contract

**A chain of re-plans is the SAME schedule as one long plan.** This is the invariant every seeding rule
above exists to satisfy, asserted directly by
`SchedulerPlanTest.a_chain_of_re_plans_is_the_same_schedule_as_one_long_plan`, the port of
`side-dev/scheduler.py`'s `check_resume_contract`.

It matters to OmniApp because the app re-plans at every rule change and materializes its tail by
extension: a plan resumed at `t` must reproduce the walk that passed through `t`, or the calendar
changes under the user for no reason anybody can point at.

**Everything the walk carries and the seeding has to rebuild from the history is a way for that to
fail, and each one has failed in turn:**

1. `last` read off `_head` instead of `_last_run` — a resumed plan then refuses the very task the
   timeline left off with, so the rightful pick loses a slot at every break (21 % instead of 39 % in
   `side-dev` test 12).
2. The lookback measured in wall time (§2).
3. The forgetting itself, which the seeding did not replay at all (§3).

`SchedulerPlanner.lastRun` is the fix for the first, and it is **deliberately not** `headRun` — a run
SURVIVES an idling interruption (that is what `headRun` reads, and what the atomic block is about), but
`last` is the other rule, and a task that stopped and was not replaced never took a second turn. Both
`plan()` and `fillSchedule` seed from it.

**Anything new the walk starts carrying must be reconstructible from the history, or this contract
breaks silently.**

## 8. The sliding period, and why it is a display clip

The reference's MOVING period (`DynamicPlanner` / `Regime` / `RuleSegment` in `scheduler.py`, tests 10–11,
`--verify`) is ported as ONE regime, not as the general dynamic rule list.

In the reference those regimes are *derived*, not hand-written: `DynamicPlanner` calls the same
planning walk every static test calls (the sliding period is an ordinary period whose position is a
parameter, and the frozen past is handed to it as `history`), fits affine rules between the breakpoints
it finds, and certifies them against the scheduler at positions they were never fitted on. When a
period slides continuously the rule list becomes `[(range of positions, prefix rules, cycle rules)…]`
with each duration *affine* in the position, so a display can follow the period in real time without
re-scheduling.

OmniApp's case was always that construction's simplest regime: one screen break sitting on the now-line, so
the disturbed slot is the one the cursor is in and nothing else changes shape. (It was literally a *sliding*
period until 2026-08-28, because an owed break was pinned to the now-line; the recurrence bars fix every
break's start now, so what remains is the drift of the now-line across a plan the last fill materialized.)

So the app needs no affine machinery, only `SchedulerDomain.clipPlanForPinnedScreenBreak` — a
**display** transform in `App.kt` that cuts the regenerable panels out of what a break covering the
now-line **refuses**, keeping a straddling panel's elapsed head and resuming it past the refusal.

That is what keeps "a period that accepts no task" true *between* fills, since the fill itself runs
only on a rule change and the marker drifts over it as `now` advances. **"Refuses", not "covers", is
still the load-bearing word**, though the resilience model has simplified what it asks: the question is
now just "is this task's multiplier zero inside this band?" (`periodAccepts`), where it used to be a
per-shape reading of a closed head and an open tail. A task with a non-zero resilience to
`no task allowed` keeps its panel through a break, and cutting it would state on screen that the
scheduler may not use a period it is in fact filling.

Pinned/manual blocks and chores are pre-placed blocks in the reference's sense and are never cut.

**Do not read the absence of the reference's dynamic rule list from `SchedulerPlan.kt` as the port being out of date, and
do NOT answer a sliding period by re-planning per tick.**

**Since 2026-08-30 the affine machinery IS ported** — `SchedulerProgressive.kt`
(`ProgressiveSchedule`, `Regime`, `RuleSegment`), which answers the README's § *Progressive
Calculation* and § *Alternative Schedules* directly: `settle()` grows a definitive front one link at a
time under a budget, `rules()` fits a regime affine in `t_p` and certifies it at positions it was not
fitted on, `advanceTo()` freezes the past by construction, and `alternativeAt()` names the fallback
task. It drives `PlanWalk` like everything else and is **not** a second copy of the rules.

**The progressive/affine half is not yet wired into the app, and it has no test.**
`SchedulerDomain.fillSchedule` still materializes panels from the walk directly and nothing calls
`ProgressiveSchedule`; the paragraph above stays true of what OmniApp *runs*. Treat the file as the
ported answer waiting for a driver — before wiring it, give it the same slot-for-slot pinning
`SchedulerPlanTest` gives the walk.

**§ *Alternative Schedules* is the exception, and since 2026-09-03 it is answered by the app's own
driver.** Leaving it to `ProgressiveSchedule` meant the running app returned rules that named no
alternative at all, which the README forbids outright — so `fillSchedule` now reads
`PlanWalk.alternative` beside every pick and carries the answer on the panel
(`TaskPanel.alternativeTaskId`, read back by `SchedulerDomain.alternativeTaskAt` — the port of
`Scheduler.alternative_at`). It is derived and deliberately unpersisted, and PRD §7's "Switch task" is
the README's own use of it: `ForcedTaskSwitch` hands the refused task to `PlanWalk.setLast`, and
`pickNeediest(…, last = refused)` is the same ordering over the same claims as
`PlanWalk.alternative(…, chosen = refused)`, so the re-plan starts precisely the task the rules named
(`AlternativeScheduleTest`).

**Nothing slides any more** (ADR 0003): the recurrence bars pin every break to a fixed instant, so the
clip's remaining job is the drift *between* fills rather than a period that moves with the now-line. The
cue keys on the drawn start for the same reason.

## 9. When the plan is recomputed

### The trigger

`SchedulerDomain.schedulingSignature(state)` is *everything the plan is a function of except `now`* —
tree/priorities/minima/**resilience maps**, non-regenerated panels and their kinds, sleep, screen
breaks, the §7 switch. (The resilience map is hashed whole: it replaced the two screen booleans, and a
period kind the user defines changes no task's map, so defining one correctly re-plans nothing.)

`SchedulerEngine.launchRuleChangeReschedule` watches it, debounced 1 s
(`RESCHEDULE_DEBOUNCE_MILLIS`), and is the only *rule-watching* dispatcher of `RefreshSchedule`. A
remote user's edit lands in the same flow, since `applyRemoteSnapshot` writes `vm.state`.

**Nothing time-driven dispatches it CONTINUOUSLY.** Two documented, quantized exceptions: the task-tree
timeline's blend cursor (ADR 0007) and the staleness bound below.

### The staleness bound

`SchedulerEngine.launchStaleReschedule`, `SCHEDULE_STALENESS_MILLIS` = 1 h. By spec the plan is
re-planned on a change **and** whenever the last scheduling was triggered an hour or more ago.

It is a BOUND, not a tick: `requestReschedule` — the single funnel every re-plan path goes through
(rule-change watcher, blend step, the §7 switch releasing a deferred fill, the manual look-away
re-anchor) — stamps `lastRescheduleMillis`, so an account being edited never reaches the bound and a
quiet one costs exactly one fill per hour.

It stamps in the deferred branch too (§7 switch off), else the loop would spin on a request it cannot
dispatch. An `ExtendSchedule` deliberately does NOT stamp it (materializing the tail is not re-planning
the head). Polled at the ordinary tick cadence (30 s prod / 1 s accelerated) rather than slept-to-the-
instant, so a clock leap or speed change is noticed within one poll; sim time, like every other engine
duration.

Re-planning an unchanged account is a **no-op** — same inputs at a later `now` ⇒ same continuation ⇒
the reducer returns the same state, nothing saved, nothing pushed — which is why the tests observe
`SchedulerEngine.lastRescheduleMillis` (`internal`) rather than `panels`. Tests:
`ScheduleStalenessRuleTest`.

### The two remaining immediate dispatches

Both are for inputs the signature already covers (`automaticSchedule`, `screenBreaks`), so they only
pre-empt the 1-s debounce rather than adding a trigger: `launchPendingRescheduleOnSwitch` (the §7
switch turned back on, firing the refill deferred while it was off) and `restartLookAway` (the manual
"Look away now" re-anchor).

**Anything NEW that wants to re-plan belongs in the signature** (or, if it genuinely needs one, in
`requestReschedule`), not in a fresh dispatch site.

### Time passing must never re-plan continuously

The hourly bound is the whole of what it may do. The advance tick only banks records
(`AdvanceSchedule`); the rolling horizon and calendar navigation dispatch `ExtendSchedule` →
`fillSchedule(keepExistingUntilMillis = firstFreeMoment)`, which KEEPS the materialized head (feeding
it to the walk as committed service) and appends only the tail.

The signature deliberately excludes the **records** (the advance banks them continuously — watching
them would re-plan every tick), so the one user edit it cannot see, `RemoveRecordPeriod`, refills
inside its own reducer.

Removed with this: the per-tick `screenBreakDue → RefreshSchedule` in `dispatchScheduleAdvance` (it
churned the whole plan continuously while the user was away — `App.kt` projects the break markers for
display itself, and the cue sweep keys on the **placed** period starts, which the recurrence bars fix).

## 10. Why not a constraint solver (Timefold, OR-Tools CP-SAT)

Recurring suggestion, most recently 2026-09-02: model the README as a receding-horizon optimization —
discretize the timeline into fine buckets, re-solve a short window every few seconds with a time-boxed
anytime solver, express the two optimization criteria as weighted soft constraints. **Rejected**, and
for reasons that are about the specification rather than about taste.

1. **The wrong return type.** The README asks for a *set of rules parameterized by the now-line and its
   mode*, plus, for every position of the line, the **alternative** task. A solver returns one
   assignment for one instantiation of those parameters; answering "for every `t_p`" means re-solving
   per position. That parameterization is the whole of `Regime`/`RuleSegment` (§8) and the reason
   `_alternative` is read *before* the clocks are charged (`PlanWalk.alternative`).
2. **The exactness clause forbids an anytime answer where an exact one is reachable.** "If the best
   possible score is reachable within the required time and acceptable computer power, it must be
   reached." The walk reaches the shares *constructively* in milliseconds; a metaheuristic returns its
   best-so-far with no statement about which it did.
3. **Discretization cannot represent the objects.** The 20 s period is the half-open
   `(t_p, t_p + 20 s]` against a 168 h horizon, and the line "moves continuously". A bucket fine enough
   for the shortest dynamic period is ~6·10⁵ buckets × the task count, per solve, on a phone.
4. **A time-boxed solver is not deterministic across devices, and the schedule is DERIVED state**
   (ADR 0007): every device recomputes it from the synced rule state and must land on the same answer.
   An answer that depends on how much CPU a device had would put two calendars on one account.
   The **resume contract** (§7) — a chain of re-plans equals one long plan — is likewise a statement a
   constructive walk can hold and a search cannot.
5. **Re-solving on a clock is exactly what ADR 0009 forbids.** "Time passing must never re-plan
   continuously"; the plan moves on a `schedulingSignature` change, an `ExtendSchedule`, or the hourly
   staleness bound. A solve every 10 s is that failure with a solver attached.
6. **Neither library can live where the scheduler lives.** `SchedulerPlan.kt` is commonMain across
   `iosArm64`, `iosSimulatorArm64`, `jvm`, `js`, `wasmJs` and the Android library. Timefold is
   JVM-only; OR-Tools is a native/JNI binary. The scheduler would have to move behind a platform seam
   or onto a server, which is a far larger change than the one being proposed.
7. **The parts it offers are already here, and are not the hard parts.** The exponential decay of a
   priority deficit is the influence field (§3, `tau`/`maxBoost`); the minimum-execution-time
   preference is the chunk floor (§1); the compute budget is `SettleBudget`. What a solver would add is
   search over a space the model does not need to search.

The proposal's one accurate observation is that the README's *Progressive Calculation* and *Alternative
Schedules* clauses want a rule list that improves under a budget. That is `SchedulerProgressive.kt`
(§8) — already written, in the model the rest of the scheduler is in.
