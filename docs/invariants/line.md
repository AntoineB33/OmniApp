# The line: edits, reversal, rule adaptation

Active invariants. Reasoning and post-mortems: `docs/adr/0018-the-line-is-the-reduction-of-its-edits.md`. Dated log:
`CHANGELOG.md`. Global rules that always apply: `CLAUDE.md`.

---

### The engine is a port, and the reference decides

→ ADR 0018. `scheduler/line/` is `C:\code\line_manager\reverse_logic` in Kotlin: `Rational` (`Fraction` + `math.inf`),
`Region` (`region.py`), `LineRules` (`rules.py`), `LineChoice` (`choice.py`), `LineEngine` (`engine.py`). The proofs
are the reference's `SEMANTICS.md`; the names and the order of every decision follow it.

- **A behaviour change starts in the reference**, then the golden file is regenerated
  (`python scripts/line-reverse-logic-golden.py`) and the port made to reproduce it. `LineGoldenTest` fails on any
  step whose outcome or line differs; never "fix" it by editing the golden file.
- **Never add a fast path that answers differently.** An optimization is fine when `LineGoldenTest` and
  `LineReverseLogicTest` still pass unchanged; a shortcut that changes a tie-break (closest to 0 first, the leftmost
  kept, the first operand) is a second copy of the rule.
- **Coordinates are exact.** Never route a position through a `Double`: "move then move back" relies on
  `(x + d) - d == x`. `Rational` throws on `Long` overflow rather than wrapping.

### The line is a function of the ordered active edits

- **The line is never stored as truth**: it is recomputed from the active edits (§ 1 of `SEMANTICS.md`). Two
  histories with the same visible line can give back different lines on an undo.
- **An edit is valid** when it fits the line, none of its claims is dropped, everything it puts is on the result, and
  its inverse fits the result (`LineSystem.validate`). An invalid edit changes nothing.
- **A drag step is the line of the direct move** from the active edits alone (`setPending`), never a function of the
  previous step; no step is skipped or delayed.

### The fold (`FoldedLine`)

→ ADR 0018 § *Phase 2*. What lies before the fold point is a plain record the replay never sees again.

- **The record is a pin with TOP priority** (`LineSystem.pin`), never the oldest edit. As the oldest it would be the
  first thing to adapt: an older live edit that a newer one once beat would come back and push the record aside
  (`LineFoldTest.theFoldedPastOutranksEveryLiveEdit`). As the newest, live edits adapt around it, and an edit whose
  own claims it would drop (its rules reach into the past) is refused.
- **Validity is judged as the edit was made**: a replay (undo, `requires`) validates each edit with the record as the
  history's BASE, and a fold rebuilds the replay with `LineSystem.restore`, unvalidated. Validating against the pin as
  the newest, or re-applying, refuses the older edits a newer one made adapt.
- **A fold never changes the line**: the rebuilt line is compared with the old one, and a fold that would differ is
  not made (the point stays). An edit is cut at the point: before it flat, after it live; a move across it is what it
  takes and what it puts on each side.
- **A flat edit adapts nothing**: a plain diff of the record, refused if the record would then break a rule by itself
  (an A without its B under "A → B" is refused, not completed). Its undo lasts until the next fold, which forgets every
  edit it leaves nothing live of.
- **The fold point is quantized**: midnight UTC, `FOLD_DAYS` (30) before (`foldPointMillis`), so every device folds at
  the same point. Nothing folds on a timer: the caller folds at start-up, a sync or an edit.

### Decided, not yet built (ADR 0018 phases 3–5)

- The merge orders edits by creation date (server-corrected clocks, ties by device id).
- A moved block lands on top (a move is a new edit) — replaces the 2026-10-17 `tm_level`-kept rule once phase 3 ships.
- Undoing an edit another device's edit requires is refused with a notice.
- Live line edits are never evicted by `MAX_HISTORY_UNITS`.
