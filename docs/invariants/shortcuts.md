# System-wide keyboard shortcuts

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## System-wide keyboard shortcuts

→ ADR 0011. Five chords — "I'm away" / "I'm back", "Look away now", "Switch task", "Choose the task to do
now", "Notifications on / off" — shipping as `Ctrl+Shift+Alt+A` / `+E` / `+Z` / `+T` / `+N` and claimed from
the OS because each is pressed precisely when OmniApp is **not** the focused window. Never a Compose key
handler.

- **The chord must be SWALLOWED, not merely observed.** `RegisterHotKey` is not first-come, first-served: an
  application with its own low-level hook is called before the hot-key table, so one press fired two actions
  (Google Docs' comments pane opened alongside the away toggle). The claim is a `WH_KEYBOARD_LL` hook returning
  non-zero; `RegisterHotKey` stays underneath purely as the fallback, and the two cannot double-fire.
- **Nothing that can block runs inside the hook** — it is on the critical path of every keystroke in the
  session, and Windows silently unhooks a callback that exceeds `LowLevelHooksTimeout`. Log and call the engine
  on the dispatch thread.
- The hook must handle what `RegisterHotKey` handled for us: **auto-repeat** (latch the down transition; swallow
  the up only for a down we swallowed) and **AltGr** (right-Alt arrives as synthetic left-Ctrl + right-Alt, so
  `Shift+AltGr+E` must pass through or the hook eats typed text).
- **Every press posts a RECEIPT** (`SchedulerEngine.announceShortcutReceived`): a "Shortcut received"
  notification naming the chord, raised at the `installGlobalHotkeys` seam **before** the action and whatever
  the action then does. It is a notification like any other, so the Notifications switch silences it too —
  which is why turning notifications back **on** announces itself from the far side of the flip (below). The chord is struck with another window in front, and each one can legitimately do
  nothing visible — so "the app never got it" and "the app got it and had nothing to do" are otherwise the
  same experience. It belongs to the hot-key seam, never to the engine seams behind it: the lateral-menu
  buttons drive those same seams and a click needs no confirming.
- **`GlobalShortcut` is the only list of chords.** The platform actual registers it and the keyboard-shortcuts
  window prints it; never a second copy. `GlobalHotkeys.claim` says which claim the OS granted, and the window
  shows it — "nothing happened" and "something else happened too" are otherwise undiagnosable.

### Rebinding the four (and only those four)

- **These are the ONLY rebindable shortcuts in the app**, because they are the only ones that can collide with
  anything outside it — a system-wide claim is first come, first served. Every other chord is a Compose handler
  scoped to a surface; do not make one of those rebindable.
- **`GlobalShortcut.defaultBinding` is what it SHIPS with, never what the app is listening for.** The live chord
  is `GlobalShortcutBindings.chordOf(state.shortcutBindings, …)` — the window, the receipt notification and the
  diagnostics all go through it. `GlobalShortcut.chord` is gone precisely so nothing can print the wrong one.
- **`SchedulerState.shortcutBindings` holds OVERRIDES ONLY.** An untouched shortcut is absent and follows the
  default, so a changed default reaches every account that never rebound it, and **"reset" is a removal** — never
  a write of today's default. Persisted + synced (the chords are the account's), and — unlike the settings beside
  it — **it IS an Undo/Redo unit** (`ShortcutBindingDelta`, Main), whose two sides carry the whole map because a
  reset is a removal.
- **`ShortcutKey` is a closed set** (A–Z, 0–9, F1–F12) and its **entry names are the persisted form**. No
  punctuation, no numpad, no Escape/Tab/Enter: a layout-dependent key would give an AZERTY user a chord the
  QWERTY peer sharing that account has not got.
- **Two rules, and they live once** — `GlobalShortcutBindings.rejection`: at least **two** of Ctrl/Shift/Alt (the
  claim swallows the chord session-wide, so one modifier would take Ctrl+C from every application), and no two
  shortcuts on one chord. The window shows its sentence and the reducer refuses on it; never a second predicate.
  Consequence: swapping two chords needs a third in between — do not "fix" that by stealing the other's chord.
- **Rebinding is a CAPTURE**, and the capture stands the claim down (`setGlobalHotkeyCapture`). Otherwise the
  chords the app already owns are the one set it can never hear. Balanced on take / Escape / focus lost / close.
- **`installGlobalHotkeys` re-registers on a later call** — that is how a rebinding lands without a restart. The
  hot-key table belongs to the loop thread, so the change is posted to it (`WM_OMNIAPP_RECONFIGURE`), never
  written from the UI thread.
- **Both healing paths exist because the collision is reachable without either device causing it**: merging per
  shortcut can land two shortcuts on one chord (`SnapshotMerge.repair`), and an older/hand-edited payload can
  hold one the rules refuse today (decode). Both drop back to the default.
- Desktop-only (Android/iOS report `Unsupported`), and best-effort: a refused chord leaves the app running with
  the lateral-menu buttons, never a failed start.
- The lateral menu's **Keyboard shortcuts** window lists every chord in the app (`KeyboardShortcutCatalog`). The
  per-surface entries are prose — add a chord and its entry in the same change.

### A button that has a chord names it on hover

**Every control that duplicates a keyboard shortcut shows that chord in an info bubble while the pointer rests
on it** — `ShortcutHint` is the one place a bubble is drawn, and a control with no chord passes `null` and gets
a plain `Box`. Today: the lateral menu's "Look away now" / "Switch task" / "I'm away" / **"Notifications"**
(the one *switch* that has a chord), the find bar's ↑ / ↓ / ✕ / Replace, and the deep-copy window's "copy".

- **The chord is always a LIVE lookup, never a constant, for the four that can be rebound.** The buttons read
  `GlobalShortcutBindings.chordOf(state.shortcutBindings, …)` — the same lookup the window, the receipt and the
  diagnostics go through — so a rebinding reaches the bubble at once. A bubble printing
  `GlobalShortcut.defaultChord` would advertise a chord the app is not listening for, which is exactly what
  `GlobalShortcut.chord` was deleted to prevent.
- **The fixed per-surface chords are spelled ONCE** (`ControlChords`), read by the button and by
  `KeyboardShortcutCatalog` both. A second spelling is how the bubble and the window start describing two
  different chords; `KeyboardShortcutsCatalogTest` pins that every constant is still listed.
- **The bubble sits BELOW the control with a gap, and holds no pointer input or focus.** A bubble the cursor
  can reach steals the hover, hides itself and flickers (ADR 0002's "catch the bubble" bug); one that takes
  focus would eat the click the hover is leading up to. Hover is read with `onPointerEventCompat`, the same
  non-consuming helper the calendar's bubble uses — on a touch-only device Enter/Exit never fire and nothing
  is ever drawn, which is right: those platforms report `Unsupported` anyway.

---

## The task picker — the one chord that puts a surface on screen

→ `ui/TaskPickerOverlay.kt`, `ui/TaskPickerMenu.kt`, `SchedulerDomain.taskPickerEntries`.

`Ctrl+Shift+Alt+T` opens a menu **at the pointer**: the tasks the now-line has been on, most recent first,
and a search field under them. The row the user takes is dispatched as `SchedulerIntent.ForceTaskStart` —
PRD §13's "start this task now", the intent the task cell's own menu raises. The picker adds no lever of its
own; it is a second way of naming the task that one already places.

**Both switch chords then lay the epsilon switch entry** at the now-line on the task selected — an ordinary
hand-placed block, so it draws with the blue outline and the check box and the calendar is told nothing
about chords (`scheduler.md`, `calendar.md`). `+Z` selects its task by asking the plan who runs instead;
`+T` by the row the user took. From there the two presses are the same press. The seed says which task and
when, never for how long — see `scheduler.md` for why it does not grow on its own.

- **What the now-line forbids is written in the rows.** A task whose resilience to the periods covering the
  line is `0` is **red** — the plan cannot place it there however it is asked — and one between `0` and `1`
  is **orange**, its share merely scaled while the period lasts (`SchedulerDomain.taskResilienceAt`, over
  `restrictiveKindsAt`; `ui/TaskPickerMenu.restrictionColor`). **Which periods count is the fill's own
  answer, not "whatever panel covers the line"**: a period the line is DRAGGING restricts nothing
  (`screen-breaks.md`), and forgetting that painted every row red under a dragged 15-minute pose. A `1` wears no colour: a menu where most rows
  are coloured says nothing. The id rows under the search field carry it too — they name one task — while a
  title *suggestion* does not, because it names a string several tasks may share.
- **The colours follow the LIVE instant, the order does not.** The list is ordered at the press (it must not
  re-order under the pointer) but what the timeline forbids is a fact about *now*, and the menu stands open
  across boundaries — so the rows are coloured at `App`'s display instant, which is resampled at exactly the
  boundaries the panels name (`display-hot-path.md`). No timer of the menu's own, and none is needed.

- **It is a MENU, in an OS window of its own.** Every other surface of the app is drawn inside the app
  (`popups.md`), and this one cannot be: the chord is struck while OmniApp is *not* in front, so a menu in
  our window would open behind what the user is looking at and nowhere near their pointer. So it is an
  undecorated, always-on-top, focusable window — and it **takes the keyboard from the application in
  front**. That is the feature, not a side effect: the menu exists to be typed into and answered with Enter.
  It leaves on Escape, on a pick, and on losing the focus — which is what "closes on the first press outside
  it" means for a window nothing else of ours can observe.
- **The pointer is read at the PRESS, not at the composition** — they are a frame or more apart and the hand
  does not stop moving in between. The instant is captured with it, so the list cannot re-order itself under
  the pointer while it is being read, and a second press re-anchors the menu where the pointer is now.
- **The task the now-line is on is not in the list.** The list is what to switch *to*; left in, it would lead
  the list (it is being touched at this instant) and chord-then-Enter would re-ask for the task being left.
  With it gone the first row is the task worked before this one — chord, Enter, back to it.
- **"Touched" is read off the recorded past only**: the records, and the panels that stand for real work
  (`SchedulerDomain.isWorkPanel`, the same predicate `taskAtNowLine` uses — a break, a sleep band or a grey
  period is not a task). A panel straddling the line counts **at the line**; one wholly ahead of it is the
  plan's intention, not history, and counts not at all.
- **Every row it offers is a row the intent honours.** The list, the id rows, the calendar's block editor and
  `reduceForceTaskStart` all ask `SchedulerDomain.isPlaceableTask` — a leaf still in the tree. A menu that can
  offer a task the reducer then drops is a press the user cannot tell from a lost keystroke.
- **The search field is a task cell in Edit Mode and nothing else**: the same `EditModeMenuBlock`, its
  **Tasks** id rows (exact title match — those *act*) over its **Title suggestions** (which only fill the
  field). No Mode selector and **no "New task" row** — a task that does not exist is nothing to start.
- **One Enter, one rule** (`SchedulerDomain.taskPickerCommit`): the highlighted row while the field is empty;
  the task the field *names* once it holds text; nothing at all when that text names no task. It lives in the
  domain so it can be pinned without a screen — the UI only clamps the highlight and draws it.

---

## Undo/redo chords are read in ONE place

- **`undoRedoIntentFor` (`ui/KeyboardShortcuts.kt`) is the only reading of Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y in
  the app.** Every surface that owns the keyboard answers them — the task tree, the calendar, the Alarms
  window — and each used to spell the test out for itself. Three copies of one rule is exactly how they came
  to agree on something wrong: none looked at Shift, so **Ctrl+Shift+Z fell into the `Ctrl+Z` branch and
  undid a third time** where every other application redoes. A fourth surface calls this function; it does
  not re-spell it.
- The rule proper takes the four facts that decide it (`key`, `keyDown`, `ctrlOrMeta`, `shift`) and the
  `KeyEvent` overload is only the adapter, so `UndoRedoChordTest` can hold it to the contract without a
  synthetic key event. **Cmd counts as Ctrl**, as everywhere else.
- Which *stack* the intent then walks is not decided here: that is `SchedulerReducer.contentCategory`, off
  whichever surface has the focus.
- `Alt + ← / →` (selection history) is a separate rule and lives on the tree, which is the only surface with
  a selection to walk.

---
