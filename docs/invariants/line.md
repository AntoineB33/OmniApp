# The line: edits, reversal, rule adaptation

Active invariants. Reasoning and post-mortems: `docs/adr/0018-the-line-is-the-reduction-of-its-edits.md`. Dated log:
`CHANGELOG.md`. Global rules that always apply: `CLAUDE.md`.

---

### The engine is a port, and the reference decides (`scheduler/ruleline/`)

→ ADR 0018 § *2026-11-28*. `ruleline` is `C:\code\line_manager\scripts` in Kotlin, layer by layer (positions x + kε,
regions, the rule language, the solver and the sweep, the claims and line state, the resolution of edits, the
generator's inputs, the generator, the two schedulers and the simulation); the file mapping is in
`scheduler/ruleline/CLAUDE.md`. Each layer is held to `scripts/ruleline-golden.py`'s files (`RuleLineGoldenTest`,
`RuleLanguageGoldenTest`, `SolverGoldenTest`, `LineStateGoldenTest`, `ResolveGoldenTest`, `GeneratorInputsGoldenTest`,
`LastLayerGoldenTest`). The first port (`scheduler/line/`, of `reverse_logic/`) is gone: `ruleline` covers it.

- **A behaviour change starts in the reference**, then `python -I scripts/ruleline-golden.py` rewrites the golden
  files and the port is made to reproduce them; never "fix" a golden test by editing its file.
- **Never add a fast path that answers differently.** An optimization is fine when the golden tests still pass
  unchanged; a shortcut that changes a tie-break (closest to 0 first, the leftmost kept, the first operand) is a
  second copy of the rule.
- **Coordinates are exact.** Never route a position through a `Double`: "move then move back" relies on
  `(x + d) - d == x`. `Rational` throws on `Long` overflow rather than wrapping.
- **The same inputs answer the same.** A random choice of the reference is drawn by `PyRandom` (CPython's generator)
  from the same seed, and a number keeps Python's promotion (`Num`: exact until a float joins). Where the reference
  breaks a tie by Python's set order, the port uses a fixed order; golden cases are kept only when the reference
  answers the same under two hash seeds, and a case the reference settles near its own time limit is left out: no
  expected answer may depend on a clock.
- **Python's unbounded integers are `Long` here**: where the reference relies on growing past 2^63, the port must say
  how it stops instead (`ScreenBreaks.around`), or compute exactly another way (`limitDenominatorOf`: the closest
  fraction of a float whose exact denominator no `Long` holds, `LimitDenominatorTest` against CPython).

### The line is a function of the ordered edits

- **The line is never stored as truth**: it is recomputed from the user's edits, kept in the order they were made
  (`rebuild`, `LineState.history`). Where edits contradict, the newer wins; an older one gives way only where and as
  long as a newer one contradicts it.
- **An edit is refused rather than half-applied**: `applyEdit` throws `Refused` (it breaks the rules, or the
  generator would remove what the user put) or `Undetermined` (no answer in reasonable time), and the state is left
  as it was.
- **Past the memory limit** the far left of the chip is flattened (`flattened`), then removed
  (`Simulation.withinMemory`): the reversal is degraded there, the line itself unchanged.

### OmniApp's world as rules (`RuleEngineBridge`)

→ ADR 0018 step 7. `ScheduleFill.Input` translated: positions in seconds after the fill's start (exact
milliseconds), a kind per task and per restrictive-period kind, the periods and pre-placed blocks the user's claims,
the frozen past the generator's, every task's resilience written for every period kind (the rule engine's default is
1, the app's `PeriodKinds.defaultResilience`). Not carried yet, so the bridge declines such a fill: sets of tasks, a
rule state changing over the fill, PRD §7 / §13's first run. `RuleEngineShadowTest` runs both schedulers on a copy of
a real account (`OMNIAPP_SHADOW_DB`, never the live file).

### Decided, not yet built (ADR 0018 phases 3–5)

- The merge orders edits by creation date (server-corrected clocks, ties by device id).
- A moved block lands on top (a move is a new edit) — replaces the 2026-10-17 `tm_level`-kept rule once it ships.
- Undoing an edit another device's edit requires is refused with a notice.
- Live line edits are never evicted by `MAX_HISTORY_UNITS`.
- The fold point, when the line is wired to the app, is quantized (midnight UTC, 30 days back) so every device
  folds at the same point, and nothing folds on a timer.
