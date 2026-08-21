# `scheduler_logic.py` — condensed specification

A summary of the entire scheduling logic, meant to be given to an LLM in place of the
~2250-line source. Names in `backticks` are the real identifiers.

---

## 0. What the thing is

Tasks must be laid out on an **infinite** timeline starting at `t_now`, so the output is
never a list of placements: it is a **finite rule list** — a `Plan(start, prefix, cycle)`,
a prefix of slots followed by a cycle that repeats forever. Reading the schedule at any
instant is then arithmetic, not scheduling (`materialise` unrolls it).

Three layers, built on one another:

| Layer | Class | Question it answers |
|---|---|---|
| static | `Scheduler.plan` | one walk over the timeline → one prefix + one cycle |
| dynamic | `MovingWindow` | one period *slides*; rules become **affine in its position `t_p`** |
| progressive | `ProgressiveWindow` | a 3-day timeline with nights + a break every 20 min → a **chain** of plans, settled front-first |

All arithmetic is exact `Fraction`. The time unit is the minute.

---

## 1. Inputs

* **Task** = `(name, priority, min_time, color)`. Priorities are normalised to shares
  `p[n]`; `minimum[n]` is the smallest slot a task may ever receive.
* **Periods** = `{start, end (or FOREVER), forbidden: set of task names}`. They **may
  overlap**; what an instant refuses is the **union** of every period covering it. The
  timeline need not be covered — an instant no period covers refuses nobody.
* **Pre-placed blocks** (`timeline=` / `pre`) = `Placement(task, start, end)` locked in
  advance. A block owned by a name that is not a task (e.g. `MAINTENANCE`) belongs to
  nobody.
* **`history=`** = the already-committed past, handed in so a resumed plan can continue the
  walk that passed through `t_now`.

Derived constants: `min_period = max(minimum[n]/p[n])` (the spacing of the slowest task's
slots) and `tau`, the field's decay constant, defaulting to `min_period`.

---

## 2. The core walk (`Scheduler.plan`)

State carried while walking: a cursor `t`, virtual clocks `v`, `last` (who just ran), and
how much the current run has served.

### 2.1 The clock and the claim — *who runs next*

Weighted Fair Queuing: each task has a **virtual clock** `v[n] = served[n] / p[n]`.

The pick is **not** `min v`. Service is **quantized** by the minima, so one slot moves a
task's clock by a whole period of its own `T = m/p`, and those periods differ by the
priority ratio. So the pick orders by the **claim** — the lag counted in the task's *own
slots* (`_claims`):

```
V     = Σ p[j]·v[j] / Σ p[j]      # priority-weighted mean clock: where everyone would
                                  # sit had service been exactly proportional
claim = (V − v[n]) · p[n] / m[n]  # = lag / T, the number of its own slots it is behind
```

`_pick` takes `max claim`, tie-broken by higher priority then name, **excluding `last`**
unless it is the only candidate (a task owed a lot gets a *denser* presence, never one long
block).

Why it matters: with A at 50 % against twenty tasks at 2.5 % (all 45 min), raw clocks say
every one of the twenty outranks A the instant A takes one slot — they take twenty slots in
a row, A gets 5 % of day one, and the never-twice rule then caps A at every other slot so
the deficit is never repaid. Counted in slots, A interleaves from the first morning. Where
every task shares one `m/p`, the claim is a monotone transform of `v` and picks exactly what
the raw clock picked; it only parts company where the raw clock was wrong.

### 2.2 The chunk — *how long it runs* (`_chunk`)

Three quantities, combined:

1. **`need`** = `p[n]·(min over others of v − v[n])` — just enough to catch the runner-up.
2. **Floor** = the task's own `minimum` (or what its interrupted run still *owes*).
3. **Cap = one ROUND** (`_round`): a share is a ratio and holds at any scale, so "catch the
   runner-up" alone lets a task owed twenty of a rival's slots take them as one block twenty
   times as long — exact percentages, and precisely the monolith the spec refuses. The
   never-twice rule cannot stop it, because one slot is one pick however long. The natural
   unit is the task's share of a round in which it and its rival each run once:
   `c/(c + m_rival) = p`, i.e.

   ```
   unit = p · m_rival / (1 − p)
   ```

   The rival is `_pick`'s own answer among the others (read off the same claim ordering).
   This replaced `p·T` (the share of a whole *period*): identical with two tasks, far too
   large with more, since a period is the spacing of the **slowest** task's slots.

The **field lifts** (`floor · boost`) and the **round caps** (`max(minimum, unit) · boost`),
and the two are asked **separately**: a boost is owed whether or not anybody is there to
race, while a round means nothing without a rival — and the atomic block is exactly the
no-rival case, so gating the lift on having one silently shrinks it back to the bare minimum.

### 2.3 The influence field — *how deprivation is compensated*

A pre-placed block owned by somebody else and a period that refuses a task are **the same
event**: an *exclusion*. `_sources` reduces both to one object, **per instant, not per
period** — cut at every edge first, then what an instant refuses is the union of everything
covering it (the only reading under which overlap means anything); touching/overlapping
spans merge, so ten short bans in a row are one long ban.

* Only obstacles **still ahead** build the field (`_set_field(pre, periods)`); what already
  happened is history, and it reaches the walk through the clocks instead.
* An instant that refuses **everybody** is dropped: nobody is deprived relative to anybody.
  It also does not bridge the two exclusions it separates.
* An exclusion **shorter than the deprived task's own minimum** builds no field: it cost no
  slot, only a delay, and the virtual clock already repays a delay exactly.

Around an exclusion of length `L`, at distance `d`, the deprived task's slots may grow by

```
boost = 1 + min(max_boost − 1, Σ (L/tau) · e^(−d/tau))      # symmetric on both sides
```

`max_boost = 6`, so compensation grows like `tau·ln(L/tau)` — an exclusion 10× longer buys a
*few* times more, never 10×. It vanishes below `field_floor` at a finite distance
(`field_end`), so the plan returns to its steady cycle. Boosted time is charged **at the
boosted rate** (`v += c/(p·boost)`), so it is a genuinely higher local share, never clawed
back.

### 2.4 Forgetting (`_relax`)

After every slot, an imbalance older than one period `T` decays exponentially toward the
minimum clock — which is what stops 17 h of a pinned task from buying its peer 17 h of
catch-up.

The same function holds an **excluded** task's clock within one period of the served pool,
and does so by **translating the whole excluded set**, never by clamping each member
separately: clamping set every task past the bound to the *same* value, so a period refusing
eleven tasks left all eleven tied and their priorities stopped deciding who went first. The
bound is a property of the group's distance from the pool; the gaps *inside* the group are
the claims themselves. Where the group is spread wider than `2T` and cannot fit the band at
all, the **credit cap wins** — it is the load-bearing half.

### 2.5 Fitting, walls and cutting — *the atomic block*

* **A window bounds only the tasks it turns away.** What bounds a chunk is the first instant
  *its own* task is refused (`_blocked_from`), never merely the next instant anything
  changes — treating the two as one punches an unfillable hole into somebody else's slot.
* **"Does the minimum fit?" counts the instants the task may actually RUN** (`_fits_from`):
  an interval **nobody** may run in only *suspends* a run (it is stepped over and costs only
  time); an interval **somebody else** may run in *ends* it. This is why a 20 s all-refusing
  look-away every 20 min does not make a 45-min minimum permanently unschedulable.
* **Starting is stricter than continuing** (`_walls` + `_clears`): a run begun at `t` is owed
  its whole minimum, so if a rival returns at `e` with `t < e < t + minimum`, starting there
  does not *use* the period — it **lengthens the rival's ban**, the one thing the virtual
  clock cannot repay (the rival is not a candidate at the instant the decision is made). A
  task **already running** has no such choice; it is the atomic block itself. Only a
  *relative* deprivation counts: an interval refusing everybody is nobody's exclusion, and a
  rival whose own minimum would not fit from `e` either is not being deprived of anything —
  which is also what keeps a densely broken timeline from idling forever for want of a legal
  start.
* **Cutting**: a chunk is cut short by a rival becoming placeable again (`_next_placeable`),
  but never below what the slot still **owes** of its minimum — consecutive slots of one task
  are one slot, so a task already running has paid it and may be cut anywhere.
* **Pending** (`pending()`): while a task is short of its minimum it is the **only**
  candidate; where a period refuses it there is **no** candidate and the period is
  **scheduled with nothing** (the spec's headline example).
* **Idle tail** (`free_tail`): a gap may be absorbed by the previous slot only if that slot's
  task is itself allowed to run in the gap; otherwise it becomes `IDLE`.
* **A pre-placed block is walked edge by edge**, not swallowed whole: where a period refuses
  the block's own task the block is suspended and resumes on the far side, instead of running
  through the period or being moved off its slot.

### 2.6 Seeding a resumed plan — **the resume contract**

> **A chain of re-plans must be the same schedule as one long plan.**

Everything the walk carries must be reconstructible from the history, or this breaks
silently. Three carriers, each of which has failed in turn:

1. **`_last_run`, not `_head`.** `_head` reads the run still in progress (it *survives* an
   idling interruption — that is the atomic block). `last` is the other rule: a task that
   stopped and was not replaced never took a second turn, so `last` is the task running at the
   instant before `t_now`, and **nothing** where the past ends in idling. Reading it off
   `_head` makes a resumed plan refuse the very task the timeline left off with, losing it a
   slot at every break (21 % instead of 39 % in test 12).
2. **The lookback window is measured in SCHEDULABLE time** (`_lookback_start`), not wall time
   — an instant nobody may run in must not push real service out of the window, or a task
   served just before a long night reads as *never served* and leapfrogs one that has been
   waiting (2 % instead of 35 % in test 12). Its width is **two `min_period`s**, not one: one
   period is exactly the spacing of one task's slots, so a one-period window aliases —
   whatever the phase, some task always reads as never served and claims the maximum.
3. **The forgetting is REPLAYED** (`_replay_clocks`). Reading the past as a flat `served/p`
   rebuilds a state the walk never held (a task idle for an hour reads as owed the whole hour,
   where the walk had already forgotten most of it). Instead the past is walked and `_relax`
   applied exactly where the walk applies it — over served time, with that stretch's own
   period, against whoever was allowed to run in it. Idling relaxes nothing. **Edge by edge,
   not block by block**: a block in the history is a run the walk *merged*, and a period may
   well begin inside one. Clocks are read for their differences, so the earliest is anchored
   at `t_now`.

### 2.7 Termination and the cycle

The walk stops when no boundary remains ahead, the field has died, and nothing is pending.
Then, if some task is allowed:

* run a few more slots until the clock **spread ≤ T**;
* build `steady_cycle(allowed)` — one period's worth. The **sizes** come from the clock (each
  task gets exactly its share of one period, capped by `_round` exactly as the walk is); the
  **order** does not, because over a whole period nobody is behind and the claim cannot order
  them. The order is the density rule: nobody twice in a row while somebody else still owes a
  slot this period, most-slots-left first. Same multiset ⇒ shares untouched.
* `_phase` rotates the cycle to start with whoever the walk would have gone on with — a cycle
  built from a blank slate always opens with the same task, handing it two slots in a row
  after a prefix that starved somebody else.
* `_tidy` merges adjacent same-task slots and rotates the prefix/cycle boundary.

`coarse_cycle` is the fallback when the steady cycle exceeds `MAX_RULES` (50).

### 2.8 Measuring the result

`open_regions` / `resulting_shares`: a share is measured over the **schedulable** timeline —
instants that refuse everybody are cut from both numerator and denominator (a night that
refuses everyone is not a share anyone lost). What is missing from the sum is time that was
offered and left empty.

---

## 3. The dynamic rule list (`SlidingRules` / `MovingWindow`)

One period *slides* with a parameter `t_p`. Re-running the scheduler at every position would
answer the question but would not *be* a rule list. Instead: the plan changes shape only where
a sliding edge crosses a boundary, and between two such breakpoints **every duration is affine
in `t_p`**. Output:

```
[ (range of positions, prefix rules, cycle rules) ... ]   # Regime
duration written as   a + b·(t_p − lo)                    # Rule
```

Drawing at a given position is a binary search plus arithmetic — no scheduling happens, which
is what lets a display follow the period continuously.

Two things keep the derivation honest:

* it calls the **same** `Scheduler.plan` every static test calls — the sliding period is an
  ordinary period whose position is a parameter, and the rules are fitted to, then certified
  against, the scheduler itself;
* the past is **frozen by construction**: `base` is the timeline committed before the period
  reached it (`_base`), and the scheduler is asked only for the continuation from `t_p`, with
  that past handed in as `history` (`history_at`). No position can rewrite an earlier one's
  past, and the sliding period cannot drag the whole timeline along.

Mechanics:

* `_offsets` tells a genuinely *sliding* edge from one merely standing still at this position,
  by sampling at three odd spacings and keeping only what every sample agrees on.
* `_edges_of` / `_candidates`: candidate breakpoints are the positions where a sliding edge
  reaches a fixed boundary.
* `_fit(lo, hi)`: two positions determine the affine form, the rest **check** it (`PROBES`,
  crowded toward both edges, plus `hi − MIN_RANGE`). A regime is never accepted on the
  strength of the samples that built it; if the plan does not in fact vary affinely across the
  range, the fit is rejected and the range is split further.
* `_split`: two kinds of breakpoint must both be looked for — a **shape** change (a task drops
  out, two swap: bisect on the task sequence) and a **slope** change with the same shape (a cap
  or boundary starts binding a duration that was free: bisect on the fit itself, ten-odd plans
  per round).
* `_simplest`: a bisection only *brackets* a breakpoint; since breakpoints come from the
  environment's own edges and the minima, the simplest rational in the bracket **is** the
  breakpoint, so the sliver between the bracket's ends stops existing rather than needing a fit.
* `_merge` folds adjacent regimes that turn out to obey the same rules — analytically (same
  tasks, same slopes, junction value matching), not by re-probing.
* `_settle_edges` decides which side of a breakpoint the breakpoint itself is on; occasionally
  it obeys neither and gets a **single-point regime** of its own.

**Known hole (2026-08-18):** `lo` itself is not probed, so a regime is never checked at the
position it is claimed from; the fitted rules are wrong on slivers of `t_p` around each
breakpoint. Closing it means giving `MovingWindow` the single-point regime representation
`ProgressiveWindow` already has.

---

## 4. The progressive rule list (`ProgressiveWindow`)

Three days, a night every 24 h, a break every 20 min. Two things differ **in kind**, not
merely in size:

1. **One prefix + one cycle cannot describe it.** The cycle a plan settles into holds only
   while the same tasks are allowed. So the rule list is a **chain of `Segment`s**, each an
   ordinary plan valid over its own stretch, seeded with the timeline the earlier links
   committed.
2. **The chain is not derived up front.** `advance` settles one link at a time and moves a
   `front`: everything at `t < front` is **definitive** and will not move again, and past the
   front the display draws the last link's cycle instead. Requirement: the front settles ≥ 10
   min of timeline per 10 s of work, and `steps` records every link so the checks can hold it
   to that pace. The front is published **after** the segment is appended, so a reader on the
   drawing thread sees an earlier state of the chain, never a torn one.

`_commit_end`: a plan's **prefix** walked every boundary in its way and is sound wherever it
reaches; its **cycle** is a steady state and may fill only up to the next edge. A rule list is
never stretched over an edge it has not seen — that is what would draw a task inside a period
refusing it. A plan that found no cycle says nothing beyond its prefix.

**Three timelines**, because the line's passage really does change the environment:

* `past` — the chain of the environment the line has **swept** (the static periods alone). This
  is what `t < t_p` is read off, and it is one fixed timeline. A break the line reached is at
  the line, never behind it.
* the chain of this object — the environment **standing** (the grid where the recurrence rules
  put the breaks), drawn far ahead of the line.
* `plan_at(t_p)` — the scheduler's own answer **from the line**, with the dragged break in it as
  an ordinary period, so the compensation field and the atomic block are the scheduler's own and
  not a display trick. `regime_at(t_p)` fits it into affine rules, derived **one regime at a time
  on demand** and cached (three days hold far too many to derive up front), under a wall-clock
  `FIT_BUDGET` deadline honoured from *inside* the search (`_out_of_time`); past the deadline
  only the *range* the rules are claimed for is given up, never the answer.

**Reached is not passed:** a break nothing has served is still owed, so — exactly as the 20 s
window is dragged and then swallowed by a five-minute stretch — it is drawn at the line and
slides with it. `sliding(t_p)` = the grid the line has not reached yet, plus what it drags. The
line starts at the origin and **teleports** onto the definitive part once that reaches
`tp_teleport`; nothing is swept by the jump, because the line was never at a position in between.

**Sliding percentages** (`blend`): priorities may be a function of the position a plan is made
from. `_sched_at(t)` builds the scheduler with the priorities at `t`; a link made from `g`
satisfies the targets at `g`, and the rules at the line satisfy those at `t_p` exactly. Nothing
else about the machinery changes — a rule list is a statement about the schedule from *one*
position, so the targets moving after it is the next position's business. A blend that returns
the same tasks everywhere is indistinguishable from no blend at all.

---

## 5. Invariants worth restating

* Output is always a finite rule list; reading it is O(1).
* An instant that refuses everybody creates no field, bridges nothing, and is cut from every
  share measurement.
* A period only *suspends* a run; a rival's return *ends* it.
* A task is never picked twice in a row unless it is the only candidate.
* A slot is never shorter than the task's minimum (or than what its run still owes).
* Compensation is capped (`max_boost`) and finite in reach (`field_end`).
* Anything the walk carries must be reconstructible from the history — else the resume
  contract breaks silently.
