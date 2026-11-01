Invariants for this directory. Paths are from the repo root; global rules are in `CLAUDE.md`.

**Before editing here, read the parts of these that cover your change** (they are long: search them for what you are touching):

- `docs/invariants/pause-cue.md`
- `docs/invariants/sync-and-accounts.md`
- `docs/invariants/server-quota.md`

`pause-cue-setup.sql` must contain no double-quote character at all, comments included (`docs/SCRIPTS.md` § *Three hard-won gotchas baked into it*).

Reasoning and post-mortems: `docs/adr/`. Verify with `./gradlew :shared:jvmTest`.
