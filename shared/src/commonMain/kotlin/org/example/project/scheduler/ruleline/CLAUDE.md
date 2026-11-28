Invariants for this directory. Paths are from the repo root; global rules are in `CLAUDE.md`.

**Before editing here, read the parts of these that cover your change** (they are long: search them for what you are touching):

- `docs/invariants/line.md`

A port of `C:\code\line_manager\scripts` (the requirements' whole model), layer by layer: `Positions.kt` =
`intervals.py`, `Region.kt` = `region.py`, `Rules.kt` = `rules.py`, `Solver.kt` = `solver.py`, `Sweep.kt` =
`sweep.py`, `LineState.kt` = `line.py`, `Resolve.kt` = `resolve.py`, `Guards.kt` = `guards.py`. Plan and order: `docs/adr/0018-the-line-is-the-reduction-of-its-edits.md`
§ *2026-11-28*. Not wired to the app yet.

A behaviour change starts in the reference; then `python -I scripts/ruleline-golden.py` rewrites the golden files and
the port must reproduce them. Never edit a golden file by hand.
Verify with `./gradlew :shared:jvmTest --tests "org.example.project.scheduler.ruleline.*"`.
