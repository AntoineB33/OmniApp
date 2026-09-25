# Persistence: history units and the store

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

### Adding a History Unit is ONE INSERT

→ ADR 0007. A History Unit is **immutable once committed** — with one named exception below — and the only
things that happen to a category's list are an append, a redo branch discarding the tail, and the cap evicting
the head. Persisting it must cost what those are worth — one row — and every piece below exists because it did
not.

A unit records **where it was made**: `HistoryUnit.window`, stamped at commit with `SchedulerState.focusedWindow`
once the shell turns `SchedulerReducer.stampsWindow` on. Since 2026-09-25 every window claims the focus, so the
focus IS where the user acts — there used to be a second, Compose-side answer (`activeWindow`) because only five
windows could be focused. Do not derive it from `HistoryCategory` (`Main` alone holds tree mutations, alarms,
timers, categories, relations and rebound chords). `Ctrl+Z` reads it (`SchedulerReducer.changesOf`: the changes
made in the focused window); a NULL window — a unit written before 11.sqm, or by a headless host — is the
tree's (the calendar's for a Calendar-stack unit), which is where it was undone from then. A **selection**'s window
is read off its delta, never off this stamp (`positionOf`): "go to task tree" pressed in Search selects a cell of
the tree.

The exception: a unit whose gesture is still open (`Delta.coalesceKey` — a field being typed into live, see
`docs/invariants/alarms-and-timers.md`) is **replaced at the pointer** by the next keystroke's unit rather than
appended after it. It costs the alignment below nothing special: the replacement's `(length, hash)` differ, so
the digest diff simply ends the matched run there and the row is rewritten — one delete plus one insert, which
is what the append it replaces would have cost anyway, and the list does not grow.

- **The `history_unit` row's key is a stable `seq`, NOT the unit's position.** It is allocated once, when the
  unit is first written, and never renumbered; the list order is the seq order and the dense index the rest
  of the app speaks in (`HistoryRow.ordinal`, what a `history_pointer` indexes) is derived back from it on
  load. Keying on the position is what forced the rewrite: **the cap evicts from the FRONT**, so one new unit
  renumbered all `MAX_HISTORY_UNITS` of them and no row could be reused.
- **A save diffs the DIGEST, never the deltas** (`selectHistoryDigests`, `HistoryDigest`). Identity is
  `(timeMillis, chronoId, debugTainted, length, hash)`; a wrong "different" costs one rewritten row, a wrong
  "same" would keep a stale delta, which is why all five must agree. `window` is deliberately NOT among them:
  it is stamped once at commit and can never differ between a stored row and the unit that matched it, so
  adding it would only have made carried-up rows (whose column is NULL) look different and rewrite the lot. Reading the deltas back to compare them
  would reload exactly the bytes this exists to stop writing.
- **`delta` is the LAST column a SAVE reads, and that is a rule, not a layout.** SQLite walks a record from
  the front and a delta of tens of KB lives in overflow pages, so a column read after it drags the whole
  chain in: the same digest scan costs 36 ms with `delta` before it and 3 ms with it after (54 MB history).
  Anything a save reads goes BEFORE `delta`. The one column allowed after it is `window` (11.sqm): no save
  reads it — a unit is immutable, so its window takes no part in the digest — and its only reader is the full
  load, which walks `delta` anyway. That is what let it be an `ALTER TABLE ADD COLUMN` instead of the table
  rebuild 10.sqm needed, i.e. instead of rewriting tens of MB of delta text on the release account's first
  launch. **A new column that a save WOULD read still has to be a rebuild.**
- **`SchedulerStateCodec` memoizes each unit's serialized delta on the unit** (`HistoryUnit.encodedDelta`,
  seeded on load from the row just read). Without it the store's thrift is pointless: `encodeSnapshot` runs
  on every save AND again for every `syncFingerprint`, so the history was re-serialized **twice per
  keystroke debounce** — 267 ms a time on a full 1000-unit stack — before the store was even called. The
  reducer carries the same unit OBJECT through every state copy, which is what makes the memo hold.
- **`encodeSnapshot` asks for the payload WITHOUT the histories** (`toPersisted(withHistories = false)`). It
  used to build them into `PersistedState` and then `.copy(histories = null)` them away.
- Measured, 1000 units / 54 MB: **~500–870 ms → ~25–40 ms** to write, **267 ms → ~2 ms** to encode.

---


### A History Unit is what changed

→ ADR 0016. **A unit stores the diff of its edit, never the state before and after it.** A whole-tree unit was
396 KB on the release account (80 MB of history), and it is what made the synced document too big to write.

- The deltas carry `EntryChanges` / `TreeDiff` / `SetChanges` / `RecordChanges` (`state/HistoryDiff.kt`): for each
  entry the edit touched, its value before and after (null = absent). Typing one character into a title is a unit of
  a few hundred bytes; `HistoryUnitSizeTest` holds every everyday unit under **2 KB**.
- **Committing applies the diff exactly** (`Delta.commit`); **undo and redo apply it three-way, field by field**
  (`resolve`): a field still as the unit left it is moved back, a field another device changed since is left alone,
  a list is rebased by membership (`rebaseIds`). Undoing a unit must never take back a change it did not make.
- Counters never go down on undo; records are stripped from a tree diff unless the edit was about records.
- **A unit an older build wrote as whole snapshots still loads and undoes**, and is rewritten small the next time it
  is saved (`HistoryUnitSizeTest`; a row over `LEGACY_UNIT_CHARS` is re-encoded rather than memoized). The codec is
  compact JSON — no pretty printing.

### One history, per-device undo

→ ADR 0016. User rule, 2026-09-17: *"all history units must be synced. All devices have the same history database,
by ctrl+z, ctrl+y, ctrl+shift+z and alt+arrow keys act only for the history units associated to this device."*

- **Every unit records its device** (`HistoryUnit.deviceId`), a per-device key (`deviceSeq` = time × 1000 + chrono),
  whether it is `undone`, and when that last changed (`changedAtMillis`). They ride the unit's row JSON as extra
  top-level keys, so the local table needed no migration.
- **Undo takes this device's newest applied unit; redo its oldest undone one.** Other devices' units are skipped,
  never undone, and never block. The category pointer is this device's newest applied unit.
- **Every chord is relative to the focused window** (user rule, 2026-09-25; `SchedulerReducer.undoIn` over a
  `HistoryWalk`): `Ctrl+Z`/`Ctrl+Y`/`Ctrl+Shift+Z` walk the Main and Calendar units stamped with the focused window
  (the Edit stack while a session is open); `Alt+←/→` the Selection units whose delta is the focused window's (and,
  for a per-copy selection, the focused copy's); `Shift+Alt+←/→` every Selection and WindowNav unit. A walk over
  two stacks orders them by `deviceSeq`, which is why `chronoId` is counted across **every** category: a press
  moves the focus and selects in one millisecond, and the two must still be walked in the order made.
- **Every window's selection is a unit** — the tree's (`SetSelectionDelta`), the projected windows'
  (`ViewSelectionDelta`, committed by `reduceInTaskList` / `reduceInSearchSubtree` / `reduceInDefaultSubtree`),
  and `SchedulerState.windowSelections` (`WindowSelectionDelta`: the Search window's row, the Task trees window's
  open entry, per copy). A window's own reset (`SelectInWindow(record = false)`) is not one.
- **A new edit discards only this device's undone units** — the other devices' redo branches stay theirs. It is
  still per CATEGORY, not per window: the server's `dropHistoryBranch` drops a category's undone rows older than its
  newest applied one, and a per-window branch would leave the two disagreeing.
- **A unit with no device (written before this rule) is claimed by the device that loads it**, its `undone` read
  off the old pointer.
- Every unit syncs as a `history_unit` row (`sync-and-accounts.md` § *Sync by rows*); a peer's units are merged in
  by `MergePeerHistory`, which never touches this device's own.
