# ADR 0018 — The line is the reduction of its edits (line_manager's reverse logic)

Status: accepted, 2026-10-22. Phases 1 and 2 of 5 shipped (the engine and the fold, not yet wired to anything).

## Context

`C:\code\line_manager\` is the user's formal model of what OmniApp's timeline is: an infinite line, a chip moving
right continuously (the now-line), kinds of elements on sets of intervals and points (panels, periods), rules between
kinds, modes given from outside (`t_p`), and a machine that generates "texts" of rules and reverses the previous
text's edits when it installs a new one (the scheduler and its set of rules output). It comes in two parts:

- `reverse_logic/` — edits (add, remove, move, extend, retract), their reversal, rule adaptation ("the newest edit
  wins"), drags. Requirements in its `requirements.md`, proofs in `SEMANTICS.md` (T1–T6, Theorems 3.2–3.6).
- `rules_generation/` — texts, the chip, the machine that runs an optimizer under a CPU and time budget.

OmniApp's manual editing had grown a hand-made approximation of `reverse_logic`: the `tm_level` stack fixed at
placement (2026-10-17, citing the older single-file `line_manager.py`), `tmLeft` for the hole rule, `rejoinedPattern`
for occurrences put back. Each was a special case of the property the reference proves in general — an edit followed
by its inverse leaves the line as it was (T3), an edit reversed leaves the line as if it had never been made (T4).

The user decided (2026-10-22) to copy the logic itself, not to keep it as a reference only.

## Decision

**The line is recomputed from the ordered list of active edits** — the reference's model, ported as-is to
`shared/.../scheduler/line/` (`Rational`, `Region`, `LineRules`, `LineChoice`, `LineEngine`). The line is never stored
as truth: two histories with the same visible line can differ in what an undo gives back (with `D → ¬C`, removing C
gives back D after "add D, add C" and nothing after "add C").

The user's decisions on how it lives in a multi-device, long-lived app:

1. **What is far in the past of the TIMELINE is folded** — flattened into a plain record and ignored by the replay —
   at **F = now − 30 days**. Folding is by timeline position, not by edit count or age: the cost is bounded by the
   stretch of timeline still live. The folded side is a fixed constraint for the live solve, the reference's own pin
   (`rules_generation` R6, `LineSystem.force`), placed at F instead of at the chip. Consequence, accepted: an edit no
   longer adapts anything before F, and Undo takes back only the part of an edit still after F.
2. **An edit that touches the timeline before F is applied FLAT** — written into the folded record as a plain diff,
   with no rule adaptation — not refused.
3. **The merge orders the edits by the date they were made** (device clocks corrected by the server's `Date` offset;
   ties by device id), not by arrival. A late offline edit lands at its date and the line after it is replayed; an
   edit no longer valid there is skipped and listed (the reference's `refused`).
4. **A moved block lands ON TOP** of what it is dragged over: a move is a new edit, and the newest edit wins. This
   reverses the 2026-10-17 rule that a move keeps the block's `tm_level` — which existed only because the old raise
   broke move-and-move-back, and the reduction (§ 2, Lemma 2.1) does not have that problem.
5. **Undoing an edit that another device's later edit requires is REFUSED** with a notice (`DependencyError`), rather
   than taking the other device's edit with it (`reverseAll`): a device never undoes another's units
   (`persistence.md` § *One history, per-device undo*).

Phases: (1) port the engine with its tests — **done**; (2) folding at F — **done**, below; (3) the calendar's manual edits become line
edits, the panels and `tm_level`s of existing databases migrated into a folded base plus one edit per live block in
level order; (4) date-ordered sync of the edits (a `line_edit` table, the purge at F, the quota test); (5) later, if
wanted, the scheduler's plan as a `rules_generation` text reversed on each re-plan.

## Phase 2: the fold (`FoldedLine`, 2026-10-23)

- **The record is `rules_generation`'s pin, and it is the NEWEST level of every solve** — "newest of all", as the
  reference's pins are. First built as the oldest edit, it failed at once: with "X forbids C one unit right", an old C
  that a newer X had beaten came back when the X was folded into an oldest record, and pushed the X out of the past.
  Top priority is also what makes "an edit no longer adapts anything before F" true without a second check: an edit
  whose claims the pin would drop is refused like any edit whose claims are dropped.
- **But an edit's validity is judged as it was made.** Validating a replayed edit against the pin as the newest (or
  re-applying the live edits to rebuild after a fold) refused older edits a newer one had made adapt: they were valid
  then, and are not valid if applied anew. So a replay validates with the record as the history's base, and a fold
  rebuilds with `LineSystem.restore`, unvalidated, then checks the rebuilt line equals the old one.
- **Flat edits adapt nothing, so one that breaks a rule is refused** rather than completed: under "A → B" an A added
  before F without its B is refused. This is the strict reading of "applied flat"; whether a flat add should carry its
  derived elements is phase 3's question, when the calendar's records meet real rules.
- Measured: 360 random folds, all made, every one leaving the line unchanged, ordered disjunctions included.

## How the port is held to the reference

- `LineReverseLogicTest` is `test_reverse_logic.py` test for test (39 tests + 2): the theorems on random histories,
  acceptance against a brute-force search, the requirements' three examples step by step.
- `LineGoldenTest` replays `resources/line/reverse-logic-golden.json`, written by the Python reference itself
  (`scripts/line-reverse-logic-golden.py`): every step's outcome and the line after it must be the reference's,
  region for region. Regions are canonical, so a tie-break or a periodic tail that differs shows as an inequality.
- Coordinates are exact rationals over `Long`; arithmetic that would overflow throws instead of wrapping.

## Rejected

- **Keep line_manager as a reference only** (the first recommendation): the user wants the semantics, and the
  hand-made approximations are exactly the "second copy of a rule that drifted" `CLAUDE.md` warns about.
- **Cap the history by count** (`MAX_HISTORY_UNITS`-style, evicting the oldest edits): a dropped edit changes what
  later edits do, not only how far Undo reaches (the `D → ¬C` example). Edits are folded by timeline position instead,
  and live line edits must never be evicted by the count cap.
- **Order the merge by server arrival**: considered for its simplicity; the user chose creation date.

## Open, found by the port

- **The lattice step on millis.** With every rule offset 0, the reference takes g = 1; on millisecond coordinates
  the finite ordered-disjunction search then walks one lattice position per millisecond of an anchor's span. Phase 3
  must give positions a unit (or g a scale) before an `ImpliesAny` rule meets a real panel.
- **Cost — a blocker for phase 3.** `LineSystem.apply` solves the whole active history, and `reverse`/`requires`
  replay it edit by edit (O(n²) solves). The fold bounds n, but not enough: measured 2026-10-23, a replay of ~60 live
  edits (two a day over 30 days, one simple rule) costs **~100 ms per edit** on the JVM, and an undo replays all of
  them. A real account makes far more than two edits a day. Before the calendar runs on it, the solve must be made
  incremental — rules relate only points within their reach, so an edit's level can only change what lies within reach
  of its regions — and measured on a copy of the release DB (`docs/PERFORMANCE.md`). The infinite-lattice search is the reference's own algorithm and its cost: a closure over
  a window of ~2 × radius lattice positions for every local check, thousands of times — 10–20 s for the worst of the
  property test's 30 cases (Python's whole suite is ~7 min). The window closure was moved to primitive flags (16 s →
  11.5 s on the worst case, same answers); the rest is algorithmic.

## 2026-11-28: the reference grew; the port follows it (`scheduler/ruleline/`)

The user asked that `docs/scheduler/scheduler_requirements.md` be satisfied, and chose to start the rule engine. Since the
phase-1 port, `C:\code\line_manager` replaced `reverse_logic/` with `scripts/` (~15k lines): percentages and percentage
rules, element rules anchored at p or at l, mode rules, positions x + kε with ε periods, lazy regions, the rules
generator and its scripts, the instant schedule, the pace, the probability finder and the memory limit — the
requirements' whole model, which OmniApp's scheduler only approximates by hand (`BreakMachine`, the fill).

Decision: **port `scripts/` layer by layer into `shared/.../scheduler/ruleline/`, each layer held to golden files the
reference writes** (`scripts/ruleline-golden.py`), the same discipline as phase 1. The old `scheduler/line/` port stayed
until `ruleline` covered what it did, then went (step 7). Order, from the dependency graph of `scripts/`:

1. `intervals.py`, `region.py` → `Positions.kt`, `Region.kt` — **done** (`RuleLineGoldenTest`).
2. `rules.py` → `Rules.kt` — **done**, without `parse_rules`'s satisfiability check, which is the solver's
   (`RuleLanguageGoldenTest`: the reference's 309 rule texts, random ones, rule sets, left sides read on values).
3. `solver.py`, `sweep.py`, `line.py` → `Solver.kt`, `Sweep.kt`, `LineState.kt` — **done** (`SolverGoldenTest`: 501
   random rule sets and claims, the lattices and the sweep both reached — its outward decision, look-ahead, runs and
   repeats — and `satisfiable`; `LineStateGoldenTest`). The sweep's probe (`_probe`) is ported but no golden case
   reaches it yet. The solver's time limit and its "by point" flag are process-wide where the reference's are per
   thread (`SolverLimits`): to settle when the generator runs beside the display.
4. `resolve.py`, `guards.py` → `Resolve.kt`, `Guards.kt` — **done** (`ResolveGoldenTest`: 200 random sequences of
   the user's, the script's and the mode rules' edits, 674 steps, the search for the fewest elements reached by 49 of
   them; only cases the reference answers the same under two hash seeds). `state_delta.py` is not ported: it exists
   to send a state between Python processes cheaply, and Kotlin runs edits on threads. The reference raises a
   `TypeError` (`-inf // period`) on a few rule sets where `not` or `<` puts a kind on a region with no left end — a
   bug of the reference, reported to the user; the golden test accepts any exception there.
5. `constraints.py`, `problems.py`, `mode_rules.py`, `modes.py`, `script.py`, `script_text.py`, `watch.py` →
   `Constraints.kt`, `Problems.kt`, `ModeRules.kt`, `Modes.kt`, `Script.kt`, `ScriptText.kt`, `Watch.kt` — **done**
   (`GeneratorInputsGoldenTest`: 286 cases — the constraints' targets, edits and placements on break-like lines, the
   problems' errors and proposals, the triggers' next positions, scripts and their text read back, the mode rules
   compiled along segments with constraints and pushes, read inside the compiled stretches). Not ported, being
   machinery of the Python program rather than rules: `Place.run` (draws on a Tk canvas), `prepare_ahead` and
   `take_ahead` (runs checked in another process). `cycle` and its `Built` region come from `ratio_plan.py` early;
   `script_python.py` needs `ratio_plan.py` and goes with layer 6. Where the reference doubles a window over unbounded
   integers (`ScreenBreaks._around`), the port stops before a `Long` overflows and makes the still-cut end infinite: it
   is only compared. Found in the reference: its script parser cannot read back a `ChipOn` trigger ("the chip is on K
   …") nor an edit "from the chip, …" — what generated scripts write for pushes — and after a refused `every 0 s:`
   line an event line raises `KeyError`. Reported to the user; the port reproduces the reference.
6. `score.py`, `score_search.py`, `ratio_plan.py`, `instant.py`, `pace.py`, `probability.py`, `generator.py`,
   `simulation.py` → `Score.kt`, `ScoreSearch.kt`, `RatioPlan.kt`, `Instant.kt`, `Pace.kt`, `Probability.kt`,
   `Generator.kt`, `Simulation.kt` (+ `PyNum.kt`: Python's number promotion, exact unless a float joins;
   `PyRandom.kt`: CPython's Mersenne Twister, so a seed draws what it draws in the reference) — **done**
   (`LastLayerGoldenTest`: 265 cases — CPython's random draws, mode models and their scenarios, `turns`, `Plan` and `Fill` on random lines, the score and its targets within 1e-9 with the instant schedule, 40 whole generator runs (its search among the predictions and placements reached) and 25 simulations whose logs hold the script's, the mode rules' and the user's changes, refusals and reruns; under two hash seeds). What runs on a time budget — the score search's improving
   and deepening, the pace's steps, the generator at q = 1 or paced — is ported but held to the reference only where
   it does not depend on the clock: a golden answer never may. Not ported: `script_python.py` (a script written as
   Python source and executed: Kotlin has no such evaluation; a script is the data of `Script.kt`), the worker
   processes (`edit_worker.py`, `generator_worker.py`: Kotlin runs these on threads, hooks `Simulation.calculate`
   and `aheadJobs`). The generator's interface is `Generating` (the rules generator and the pace's `Stepped`). This
   is the second copy of the score beside OmniApp's own scheduler (`docs/scheduler_score.md`): step 7 must leave one.
7. Wiring: the calendar's elements and the default rules (`docs/scheduler/default_scheduler_rules.md`) written as rules,
   the scheduler replaced, `scheduler/line/` removed. Started 2026-11-28:
   - `scheduler/line/` is **removed** (`Rational` moved into `ruleline`, the only user left).
   - **The bridge** (`RuleEngineBridge`): `ScheduleFill.Input` — the app's tasks, restrictive periods, pre-placed
     blocks and frozen past — as the rule engine's kinds, claims and score (`docs/invariants/line.md`).
   - **Measured on the real account** (`RuleEngineShadowTest`, a copy of the release DB: 141 tasks, 337 periods, 1119
     past runs, 24 h ahead), both plans judged by both scores. The rule engine's instant schedule is far behind (J
     about twice the app's plan's); its score search, ~4 s, first beat the app's plan on both scores (1.2 % on the
     app's) — and that exposed an optimizer fault rather than a reason to switch: `ScheduleOptimizer` improved only the
     candidate that scored lowest before improvement, the plan on screen, when the rollout's continuation improves
     further. Improving the two best (`IMPROVED_CANDIDATES`) made the app's fill 1.4 % better and 2.6× faster there,
     and then better than every continuation the rule engine found. So the rule engine's search is wired only as an
     optional seed (`ScheduleFill.Input.ruleEngine`, off), and **the score's search stays the app's**: the rule
     engine's port of the score is a second copy, kept for the comparison and the reference's tests.
   - **What is left is the model, not the search**: the task tree's relations and the default rules as element and
     mode rules, the breaks as constraints instead of `BreakMachine`, and the line as the reduction of the user's
     edits instead of stored panels — which changes what is persisted and synced (`persistence.md`,
     `sync-and-accounts.md`, `server-quota.md`), the user's live data. Each is its own stage, measured on the real
     account the same way before it replaces anything.

Where the two requirement files differ, OmniApp's is the one to satisfy; the reference's `requirement.md` still has the
older wording of 2026-11-28's edits (positions over the reals; an "or" decided leftmost only when no optimization goal
breaks the tie; randomness allowed in optimization; the switch to small intervals after N ε-steps or a cycle) and of the
two schedulers. Those land in the reference first (`docs/invariants/line.md`), then in the port.

Coordinates stay `Rational` over `Long` (throwing on overflow) where the reference has unbounded `Fraction`s.
