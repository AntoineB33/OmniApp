Invariants for this directory. Paths are from the repo root; global rules are in `CLAUDE.md`.

**Before editing here, read the parts of these that cover your change** (they are long: search them for what you are touching):

- `docs/invariants/line.md`

A port of `C:\code\line_manager\reverse_logic` (proofs: its `SEMANTICS.md`). Reasoning: `docs/adr/0018-the-line-is-the-reduction-of-its-edits.md`.
Verify with `./gradlew :shared:jvmTest --tests "org.example.project.scheduler.line.*"`.
