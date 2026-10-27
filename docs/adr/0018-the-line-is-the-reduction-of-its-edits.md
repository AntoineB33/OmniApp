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
