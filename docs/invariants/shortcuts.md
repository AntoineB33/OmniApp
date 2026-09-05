# System-wide keyboard shortcuts

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## System-wide keyboard shortcuts

→ ADR 0011. Four chords — "I'm away" / "I'm back", "Look away now", "Switch task", "Notifications on / off" —
shipping as `Ctrl+Shift+Alt+A` / `+E` / `+Z` / `+N` and claimed from the OS because each is pressed precisely
when OmniApp is **not** the focused window. Never a Compose key handler.

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

