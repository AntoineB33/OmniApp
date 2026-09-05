# Pop-up windows

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Pop-up windows

`ui/PopupWindows.kt`. There are **two sorts, and the sort is not a choice** — it follows from what the
pop-up is about.

- **Sort 1, a window.** Opens on the top layer, then behaves like every other window: whatever is focused
  next stacks on top of it, and it stays open until it is closed. `App`'s `windowStack`.
- **Sort 2, a transient pop-up.** Opens on the top layer and **leaves the moment anything else takes
  focus**.

**The test is whether it could have several instances open at once.** A pop-up about ONE object — a task,
a cell, a sub-list, a calendar block, a period, a *kind* of period, a **category**, a reminder, a history unit,
a tree entry — is sort 2,
because "the edit window of task A" and "the edit window of task B" are two different windows and the user
only ever means the one they just asked for. A pop-up there is exactly one of is sort 1. So sort 1 is
precisely `windowStack` (Calendar, Reminders, History, Sleep, Alarms, TaskTrees, TaskList, TaskRelations,
Categories, DefaultSubtree, Shortcuts, TimeSim) and every other pop-up in the app is sort 2.

- **A notice the app says back to a gesture is sort 2 too** (`MessagePopup` — today only the calendar's "go
  to task tree" on a task no cell holds): "the error about this panel" and "the error about that one" are two
  different notices and only the latest is ever meant. It therefore leaves when anything else takes focus,
  and needs no timer and no scrim of its own.
- **At most one sort-2 pop-up is open at a time** — `TransientPopupHost.open` dismisses the others, so the
  invariant holds by construction and not by every opener remembering to close its predecessor.
- **A sort-2 pop-up is NOT modal: no scrim, blocks nothing.** The press that dismisses it still does its
  normal job (focusing the calendar, selecting a cell). The full-screen scrims that shipped before ate that
  press, which cost a second click and made "it leaves when something else is focused" unobservable.
- **NOT MODAL IS ABOUT THE POINTER; THE KEYBOARD IS THE POP-UP'S** (`TransientPopupHost.anyOpen`, read by
  `TaskTreeView` as `keyboardOwned`). A pop-up is what the user is working in, so the tree behind it goes
  **deaf** while one is open — and takes the keyboard back when it closes, which is why `keyboardOwned` is
  one of that effect's keys. PRD §4's "type a letter on the selected cell and it starts renaming" otherwise
  fires behind the pop-up, and since entering Edit Mode is exactly what closes the weight and
  relative-priority windows, the keystroke aimed at the pop-up **dismissed** it. Deaf, never blind: a press
  still reaches the tree and still dismisses the pop-up. Read in `TaskTreeView`, so all three drawings of
  the tree get it at once — never a flag threaded down by each surface.
- **A pop-up that answers keystrokes must therefore TAKE the focus** (the priority-weight window is today's
  one: its rows are task cells, so a letter typed on the selected row opens its Edit Mode). Taking focus
  does not dismiss anything — dismissal is a press, watched at the app root — and the pop-up hands the
  keyboard on to a row's own field while one is being edited, and takes it back when that closes.
- **Dismissal discards** whatever was half-typed in it. That is the sort's price, not an oversight — the
  old scrim click did the same.
- **One observer, at the app root** (`transientPopupDismissRoot`), watching the **Initial** pass without
  consuming. Never a per-pop-up outside-press handler. Presses inside a `DropdownMenu`/`Popup` draw in their
  own layer and never reach it, which is what keeps a pop-up's own menus from closing it.
- **A sort-2 pop-up must be drawn where it can be on top.** The tree's `TaskEditWindow` / `DeepCopyWindow`
  are raised out of `TaskSchedulerScreen` into `App` for that reason (inside the tree they drew *under* any
  floating window stacked over it); `ReminderConstraintEditWindow` uses a `Popup` for the same reason.

---

