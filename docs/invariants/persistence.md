# Persistence: history units and the store

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

### State: authoritative, derived, local-only

The rule is in `CLAUDE.md` § *State: authoritative vs. derived*; this is the whole of the classification. A new field of the state is classified here in the change that adds it. → ADR 0007.

| Class | Contents | Rule |
| --- | --- | --- |
| **Authoritative** | task tree (task **resilience** included, a task's **set of tasks** and the tasks with no path it keeps, and a task's **last path** once no task tree holds it), the account's **period kinds**, named task trees, the default sub-tree + its switch, user-authored/pinned panels (their **repeat pattern** and its **exceptions**, the **occurrence a block was edited out of**, the **timeline level** each stands on, and **where the hole rule filled after it left**, included), the **hidden timeline levels** (what a placed block covers of another, `hiddenPanels`) and the periods the app conducted, chores/reminders, sleep schedule (**the nights whose window or hour before bed was dragged away**, **the length of its "no screen before bed" period** and **the day its goal is reached on** included), alarms, timers (**whether one is running**, **the current run's length** and **the instant a rung one reached zero** included), chronos (**whether one is running** included), **quotas** (their amount, loop, resilience, particular loops and the other times they are looked at), settings, the system-wide chord bindings, the **task relations** the user kept or struck off, the account's **categories** (their **kind** included) and their **rules**, the task cell categories each **cell** carries, the **pins** of both priority windows, the **default configuration** of a new alarm, timer, reminder and quota (**when a new alarm or reminder is set**, `newAlarmTimes`/`newReminderTimes`, included), Undo/Redo history units, manual record edits, **the links between the task trees' tasks** (`taskLinks`, one row each), **the Claude Code logs the account keeps** (`ClaudeLogKeeper`: their own table both sides, `claude_log_entry`, bounded per device and on the server — not on the entity wire) | persist + sync |
| **Derived** | auto/screen-break/sleep panels, a repeating panel's later occurrences, task colours, a running timer's remaining time, a quota's target progression, the dynamic periods' placement (the recurrence bars read their anchors out of the timeline), records the advance banks, the rules' repeating part (`scheduleCycle` — in memory only, not even persisted), the plan for the other `t_p` mode class (`otherModePlan` — in memory only), the request for a run after the user rewrote the records (`recordRewrites` — in memory only), the rules the plan was made for (`planBasis` — not carried across a pull), the elected device's rules on the peers' broadcast channel (never stored, adopted as derived panels) | persisted locally, **stripped from the wire**, never trigger a push on their own |
| **Authoritative, the account's workplace setups** | `workplaceSetups`: synced as rows like chronos, each applied only on a device of its OS; `newWorkplaceDefaults`, what a new one starts with, a setting like `newQuotaDefaults` | one row per setup, no migration |
| **Authoritative, the links between elements** | `elementLinks`: one `ElementLink` per parent and child, keyed by the two (`ElementTree.linkId`), ordered by `rank`; a key naming what is gone is ignored and kept, so an undo finds its links. A task's sub-list and `categoryIds` stay where they are | one row per link, no migration |
| **Authoritative, the life plan** | `lifePlan`: one `LifePlanEntry` per step, tab and LINE of a tab, ordered by `rank` and never by place in the list; `healed` at decode and at a merge (a line whose tab is gone goes) | one row per entry, no migration |
| **Local-only view state** | focused window, tree selection, the feature page the app was left on, whether the lateral menu is collapsed and the windows' stacking order (`AppViewState`, `AppView` row), the History window's filter (its row's `config`), the Search window's sub-trees' own expansion/selection/edit session, the Search and Task trees windows' selections (`windowSelections`, in memory only), the lateral menu's items — windows' buttons and controls, all the user's (`CustomMenuButtons`) and the names they gave windows — a menu button's or the head's "Rename", on the tab and the head (`TabTitles` row), each open window's colour (`WindowColors` row) and the lines between its sections with which are retracted (`WindowSections` row), `showScreenBreaks`/`showReminders`/`calendarDayMode` (the calendar's Day/Week display), WindowNav/Selection history, window placement (its filled axes and reduced state included, which per-object windows are open, on what, and the desktop app's own window), the notifications each calendar window shows as chips (its placement row's `config`), the chords the user mapped to a window (`WindowShortcuts` row) and the Search window's query, checked kinds, filters, added elements and "Add to the calendar" start and end (and the Configuration Search and Added elements configurations windows' own configurations), OS-sleep scan checkpoint, the seconds an action was done on the computer (`input-activity/` beside the DB, 90 days), the screen breaks the line has banked (the frozen past of the three dynamic periods, pruned at 90 days) and the break machine at their front (`FrozenScreenBreaks.machine`, the breaks a hand placed — behind the line and ahead of it — included), what a mode-1 line crossed of the user's "no screen" periods (`periodCrossings`), the device's work-offline choice, the Supabase project the device syncs with (`server_choice`, 19.sqm, docs/SERVER_SETUP.md), the scheduler engine's time limit after a change on this device (`planCalculationLimitSeconds`), every sound of the app off on this device (`soundMuted`), the refused-edit notice (`categoryRuleError`, not even persisted), the Claude Code logs' directory (`ClaudeLogs` row) and the logs page's own lateral menu (`LateralMenu/ClaudeLogs` row; an item's **global** flag, which puts it in every page's menu, included), the windows set **global** — on every page (`GlobalWindows` row) | persist locally, **never sync** |

- Local view state is stripped from the fingerprint by `withLocalViewStateNeutralized()` and carried across a
  pull by `withLocalViewStateFrom`.
- Calendar zoom is Compose-only state — never persisted at all.
- The one deliberate exception: the state is replayable from history units, but history is bounded
  (`MAX_HISTORY_UNITS`), so the entity rows stay authoritative.
- Authoritative state syncs as **one row per entity** and each History Unit as **one row**; the units are shared by
  every device, but Undo/Redo act only on this device's own (`sync-and-accounts.md` § *Sync by rows*,
  `persistence.md`).

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

**Every TYPE of window is a `HistoryWindow`** (user rule 2026-10-01 — the Search configurations window's "made in"
lists them all): the lateral-menu ones and every other window — the default sub-tree's task and category windows,
the weight table, relative priority, deep copy, the default alarm/timer and reminder, the calendar's element, block,
period and reminder editors, the "constrained in" picker, a history entry's and a task tree's detail, a notice. A
press in ANY window takes the focus for it **synchronously**, before the press commits anything:
`WindowFrameHost.onFocus` → `App.historyWindowOfFrame` (the frame id's base) → `FocusWindow`. And **a change a window
commits after the press that left it** (a Search row's rename, committed on blur) still names that window: the Search
window and the Added elements configurations window send their intents `MadeIn` themselves
(`SchedulerIntent.MadeIn` — the stamp, not the focus). A consequence: what is done in a per-object window is undone
with `Ctrl+Z` from THAT window, not from the one it was opened from.

**A History Unit for almost every user action** (user rule 2026-10-01 — the anomaly was an element added to a Search
window's list, which recorded nothing). Undoable, from the window it was made in, like every unit:

- **What `App` keeps outside the state** — a Search window's configuration (`search/<frame id>`: its added elements,
  text, types, filters, sorting, actions' filter), a window's layout (`window/<frame id>`: opened, closed, moved,
  resized, reduced, filled — its row without the configuration) and the lateral menu's buttons (`menu`) — is an
  `ExternalDelta` (`SchedulerIntent.RecordExternal`, recorded by `App.ViewHistoryRecorder`). Committing changes nothing
  in the state; undo and redo queue the value for `App` (`SchedulerState.externalRestores`, transient — never stored,
  synced or fingerprinted), which puts it back **three-way** (only while the thing is still what the unit left) and
  records nothing while it does. Nothing is recorded until the start-up has settled (the restores of the windows are not
  the user's). A text field typed into is one unit per run of keystrokes (`coalesceKey`). A window that closes hands
  the focus to the one under it, so its close is stamped with — and undone from — that window.
- **An account setting** — defining, deleting, resetting or duplicating a kind of period, its drawing and combination
  rules, creating, renaming (one unit per run of keystrokes), deleting or duplicating a category and its rules, the
  Notifications and voice switches — is a `SettingsDelta` (`SchedulerReducer.settingsUnit`): what the change moved of
  those settings, of the tree and of the panels, applied three-way piece by piece.
- **A timer's or a chrono's run the user moves** is a `TimersDelta` / `ChronosDelta` (`alarms-and-timers.md`); the ring
  is not.
- **Every other switch, number and default of the account, each pin, each task relation the user files, each task link
  and the Sleep / Work press** (user rule 2026-11-01) is a `SettingsDelta` too, through `SettingEntries`
  (`persistence/SchedulerStateCodec.kt`, `HistoryReachTest`): entries of text, diffed one by one, so a unit holds the one
  it moved. A new setting is one line in `SettingEntries.of` and one in `with`. A field typed into or a slider is one unit
  per run (`coalesceKey`), the deep-copy window's one Save one unit. A weight column added, moved or deleted carries its
  pins in its own unit, and a period laid over work carries the records it took (`withStrippedRecordsInUnit`,
  `PanelDelta.records`) — so one Ctrl+Z puts both back.
- **A unit of local-only view state alone** (`Delta.localOnly`: Day / Week, the two display switches, the mute, the plan's time
  limit) rides the next push like every unit but is no reason for one: `withLocalViewStateNeutralized` leaves it out of the
  fingerprint, or the Day / Week switch would talk to the server.
- What `App` keeps has four more keys (`ExternalKeys`): a calendar window's configuration, a Search configurations
  window's, the name a window was given and which windows are global.
- Still no unit, deliberately: what the app authors itself (the plan, the ring, a peer's pull, the wake instant lapsing),
  the calendar's transient editors and notices (they keep no layout row), where the user is looking and not what they
  changed (the page shown, the menu collapsed, a window's sections, the overlap key held), and the presses that act on
  the moment and have no before to go back to (Look away now, Switch task, I'm away).
- An older build skips a unit whose delta it cannot read (`decodeUnit`), so the new kinds are safe across devices.

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
- **What the app authored is walked by no chord, and is no branch** (anomaly 2026-11-01; `Delta.authoredByApp`,
  `RecordDelta.byUser`). The work the line banks is a Main unit, to be seen and for the debug rollback, and it stamped the
  focused window: every few minutes one landed on top, so the next `Ctrl+Z` took a banked record back instead of what the
  user did, and it dropped their redo. Neither any more, on the server either (`dropHistoryBranch` is asked from the
  newest unit that is the user's). A record the USER takes out is theirs: "Remove" on a block, a period laid over it.
- **A change made in the left-side menu or the window bar is walked by `Ctrl+Shift+Z` / `Ctrl+Shift+Y` alone** (user rule
  2026-11-12, replacing 2026-11-01's "from whichever window has the focus", whose anomaly was a "Reset"). The menu is no
  window and takes no focus, so its units are stamped `HistoryWindow.Menu` (`SchedulerIntent.MadeIn`, its controls
  included) and `changesOf` refuses them in every window (`isAppWide`); a unit of the menu's buttons stamped with a
  window, written before, counts too. `App`'s root takes `Ctrl+Shift+Z/Y` on the PREVIEW pass, always
  (`HistoryChordRoutingTest`): a focused text field would answer them with its own redo. With nothing focused no
  modifier sees a key at all, so the desktop window hands them to `AppKeyFallback`. Restoring the menu is the one restore that is not three-way: nothing can come between its units, and
  a difference the app made itself (a button's configuration frozen at a start) must not leave an undo doing nothing.
- **Without Shift a chord is relative to the focused window, with Shift it walks every window** (user rules
  2026-09-25 and 2026-11-12; `SchedulerReducer.undoIn` over a `HistoryWalk`): `Ctrl+Z`/`Ctrl+Y` walk the Main and
  Calendar units stamped with the focused window (the Edit stack while a session is open), `Ctrl+Shift+Z`/`Ctrl+Shift+Y`
  every Main and Calendar unit (`ALL_CHANGES`); `Alt+←/→` the Selection units whose delta is the focused window's (and,
  for a per-copy selection, the focused copy's) — **selections of rows only**: the day a calendar is taken to is a
  change of that calendar (`ExternalKeys.CALENDAR_DAY`, walked by its `Ctrl+Z`); `Shift+Alt+←/→` every Selection and
  WindowNav unit (`Ctrl+Alt+←/→` did too, 2026-11-11 to 2026-11-12).
- **Each copy of a window is a window of its own** (user rule 2026-11-12): a unit records the copy it was made in
  (`HistoryUnit.instance`, what `focusedInstance` held: `""` for the original, `"#2"`…), stored with its window as ONE
  text, the copy's frame id (`windowColumn`: `Search#2`), in the store's and the server's `window` column — so no
  column was added and the server's branch drop filters on it exactly. A row written before reads as the original's.
  `Ctrl+Z`, the window's pin and its redo branch are the copy's; a change made in a copy from outside it is
  `SchedulerIntent.MadeIn(window, instance, …)` with that copy's instance, never `""`.
- **One pin per window, and one for the whole app** (user rule 2026-11-12, `HistoryReachTest`). A window's pin is its
  newest applied change. The whole app's is STORED (`SchedulerHistories.wholePin`, a `deviceSeq`; persisted as the
  `history_pointer` row `WHOLE_PIN_ROW`, kept off the wire like the view state; absent, it reads as the newest applied
  change): a new change puts it there; `Ctrl+Shift+Z` undoes the newest applied change at or under it — skipping what
  a window's `Ctrl+Z` undid — and puts it on the newest applied change below; `Ctrl+Shift+Y` moves it to the next
  undone change over it and redoes that one — passing over what a window's `Ctrl+Y` redid; a window's `Ctrl+Z` brings
  it down to the newest applied change at or under it. It is stored because it can stand under changes a window redid,
  which no reading of the units alone can tell. A walk over
  two stacks orders them by `deviceSeq`, which is why `chronoId` is counted across **every** category: a press
  moves the focus and selects in one millisecond, and the two must still be walked in the order made.
- **Every window's selection is a unit** — the tree's (`SetSelectionDelta`), the projected windows'
  (`ViewSelectionDelta`, committed by `reduceInSearchSubtree` / `reduceInDefaultSubtree`),
  and `SchedulerState.windowSelections` (`WindowSelectionDelta`: the Search window's row, the Task trees window's
  open entry, per copy). A window's own reset (`SelectInWindow(record = false)`) is not one.
- **A new edit discards only this device's undone units, and of a change only its WINDOW's** (user rule 2026-11-12:
  two changes in A, `Ctrl+Z`, a change in B — A's `Ctrl+Y` is still there) — plus the menu's undone changes, which
  only the whole pin could redo and which it just passed. The other devices' redo branches stay theirs. The server's
  `dropHistoryBranch` follows the same rule: for a Main or Calendar commit it is asked per window, filtered on the
  rows' `window` column (the menu's, and the unstamped rows where that window is the category's default). Selections and
  focus moves still branch per category.
- **A unit with no device (written before this rule) is claimed by the device that loads it**, its `undone` read
  off the old pointer.
- Every unit syncs as a `history_unit` row (`sync-and-accounts.md` § *Sync by rows*); a peer's units are merged in
  by `MergePeerHistory`, which never touches this device's own.
