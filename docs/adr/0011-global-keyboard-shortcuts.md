# ADR 0011 — System-wide keyboard shortcuts: the chord must be swallowed, not merely observed

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *System-wide keyboard shortcuts*.

Three of the app's actions are wanted at a moment when OmniApp is, by definition, **not** the focused window:

| Default chord | Action | Why the app is not in front |
| --- | --- | --- |
| `Ctrl+Shift+Alt+A` | "I'm away" / "I'm back" (`SchedulerEngine.setUserAway`) | the user is walking away from whatever they were working in |
| `Ctrl+Shift+Alt+E` | "Look away now" (`SchedulerEngine.restartLookAway`) | the user decides mid-task to rest their eyes |
| `Ctrl+Shift+Alt+Z` | "Switch task" (`SchedulerEngine.forceTaskSwitch`) | the user is inside the work they have decided to get off |

Those are the chords the app *ships* with; since 1.6.0 each of them is **rebindable** - see *Rebinding*, below.

So none of them can be a Compose `onPreviewKeyEvent` handler: a focus-scoped chord fires only in the one
situation where the lateral-menu button is already one click away. All are claimed from the OS in
`scheduler/platform/GlobalHotkey.kt` (`installGlobalHotkeys`), whose only Kotlin caller is one `LaunchedEffect`
in `App.kt`. The chords themselves live in **one** place, the `GlobalShortcut` enum, which is both what the
platform actual registers and what the keyboard-shortcuts window prints.

## `RegisterHotKey` alone was not first-come, first-served

2026-08-23. The original implementation (2026-08) was a plain `RegisterHotKey(NULL, …)` + a `WM_HOTKEY` message
loop on a dedicated daemon thread. The documented behaviour of a Win32 hot-key is that the system consumes the
keystroke — the focused window never receives it — and that is what the invariant assumed.

The reported symptom says otherwise: pressing `Ctrl+Shift+Alt+A` inside **Google Docs** flipped the away flag
*and* opened Docs' comments pane. One press, two unrelated actions.

The mechanism is hook ordering. A `WH_KEYBOARD_LL` hook runs in the raw-input path, **before** the system's
hot-key table and before the focused window's message queue. An application that watches the keyboard through
its own low-level hook (Chrome installs one) therefore sees a press that the hot-key then consumes: the
consumption is real, but it happens too late to be exclusive. `RegisterHotKey` gives you "nobody downstream of
me", not "nobody at all".

**So the claim is now a low-level keyboard hook of our own**, returning a non-zero `LRESULT` for the two chords:
the key is consumed at the head of the chain and nothing further in the session — hot-key table, focused window,
later-registered hooks — is handed it. That is the first-come, first-served rule the user asked for, and it is
as strong a claim as a user-mode process can make on Windows.

### `RegisterHotKey` is kept underneath, as the fallback

Both claims are made, and they cannot double-fire: a swallowed key never reaches the hot-key table, so exactly
one of them delivers each press. What the second one buys:

- the hook may be refused outright (`SetWindowsHookEx` returns null) — the shortcuts still work, shared;
- Windows **silently unhooks** a low-level hook whose callback exceeds `LowLevelHooksTimeout` (~300 ms). A long
  GC pause can do that. Without the fallback, the shortcuts would die for the rest of the session; with it they
  degrade to shared delivery.

One side effect worth knowing about the dev workflow: hooks are called **most-recently-installed first**, so
with two OmniApp instances running side by side (the `account1`/`account2` scripts) the chord goes to the one
started **last**, where the old `RegisterHotKey`-only claim gave it to the one started **first**. Either way one
instance answers; the loser logs `RegisterHotKey(…) refused`, which is how you tell.

`GlobalHotkeys.claim` publishes which one is in force (`Exclusive` / `Shared` / `Unavailable` / `Unsupported`),
because the two failure modes the user can actually observe — "nothing happened" and "something else happened
too" — say nothing about their cause. The keyboard-shortcuts window prints it. (It is reported once, at install
time; a hook Windows drops later still reads `Exclusive`, which is the known gap.)

## The hook is on the critical path of every keystroke in the session

That is the cost of exclusivity, and it constrains the callback: compare a virtual-key code, read the modifier
state, latch, hand the work to a single-thread executor. **No logging and no engine call inside the hook** — the
diagnostics line is written on the dispatch thread. Exceeding the timeout does not throw; it silently removes
the hook, which is exactly the failure the fallback exists for.

Two details the hook has to handle that `RegisterHotKey` did for us:

- **Auto-repeat.** `MOD_NOREPEAT` has no hook equivalent, so a `latched` set makes the key-**down** transition
  the press. The key-**up** is swallowed too, but only for a down we swallowed — no window should be handed a
  release for a key it was never told about.
- **AltGr.** Windows delivers right-Alt as a synthetic left-Ctrl **plus** right-Alt, so on a French/AZERTY
  layout `Shift+AltGr+E` — an ordinary way to type a character — carries exactly the modifier flags of
  `Ctrl+Shift+Alt+E`. That pattern (right Alt, no left Alt, left Ctrl, no right Ctrl) is passed through, so the
  hook cannot eat text the user meant to type. The price: the chord must be struck with the **left** Alt.

Alt is part of every chord, so the letter arrives as `WM_SYSKEYDOWN`/`WM_SYSKEYUP`, not the plain spellings —
both have to be recognised.

## Rejected

- **A Compose key handler.** Fires only when the app is focused; see above.
- **`RegisterHotKey` alone.** The post-mortem above.
- **Re-installing the hook periodically to stay at the head of the chain.** Hooks are called most-recently-installed
  first, so a later-starting application takes the head back. Chasing that is a fight with no end state, and
  installing repeatedly is exactly the behaviour anti-malware heuristics flag. One install, one fallback.
- **A separate boolean/second enum for "which chords exist".** The window would drift from what the OS was
  actually asked for. `GlobalShortcut` is the single source, and `KeyboardShortcutsCatalogTest` pins it.
- **Rebinding as a typed text field** ("Ctrl+Alt+K"). Two spellings of one chord, and a parser to keep in step
  with the printer. The capture reuses the printer and cannot disagree with it.
- **Storing the full binding table rather than the overrides.** It would freeze whatever the defaults happened to
  be on the day the user first opened the window.
- **Letting a rebinding steal the chord off another shortcut** (resetting that one to its default). Silently
  changing a shortcut the user did not ask about, to fix a conflict the message already explains.

## Not Windows

Android and iOS report `Unsupported`: a backgrounded app cannot claim a system-wide chord, and the lateral
menu's buttons are the whole surface there. A non-Windows desktop host logs the fact and runs on — a missing
shortcut must never keep the app from starting.

## The keyboard-shortcuts window (PRD §7)

The lateral menu's last button opens `ShortcutsWindow`, built from `KeyboardShortcutCatalog`: the system-wide
block first (derived from `GlobalShortcut` resolved against the account's bindings, plus the claim line), then
the task tree, Edit Mode, history, calendar and the tree-name field. Those per-surface blocks are prose —
nothing can read the `onPreviewKeyEvent` branches back — so a new chord and its catalogue entry belong in the
same change.

## A button that has a chord names it on hover (1.6.0)

A shortcut is invisible from where the user is sitting. The lateral menu's "Look away now" button says nothing
about `Ctrl+Shift+Alt+E`, and since the three are rebindable, even a user who once read the keyboard-shortcuts
window may be looking at a button whose chord has *moved* since. So **every control that duplicates a chord
shows it in an info bubble while the pointer rests on it** — the window's answer, brought next to the control
that fires the same action.

`ShortcutHint` (`ui/ShortcutHint.kt`) is the one place such a bubble is drawn. It wraps the control; a control
with no chord passes `null` and gets a plain `Box` with no hover node and nothing drawn. The controls that
have one today:

| Control | Chord |
| --- | --- |
| lateral menu "Look away now" / "Switch task" / "I'm away" / "I'm back" | the account's binding of the matching `GlobalShortcut` |
| find bar ↓ / ↑ / ✕ / Replace | `Enter` / `Shift + Enter` / `Escape` / `Enter` in the replace field |
| deep-copy window "copy" | `Enter` |

Two rules keep the bubble from becoming a second source of truth:

- **A rebindable chord is a live lookup, never a constant.** The three menu buttons go through
  `GlobalShortcutBindings.chordOf(state.shortcutBindings, …)`, the same lookup the window, the receipt
  notification and the diagnostics use — `App.kt` hands `LateralMenu` the very map it is handing
  `installGlobalHotkeys`. Printing `GlobalShortcut.defaultChord` there would advertise a chord the app is not
  listening for, which is why `GlobalShortcut.chord` was deleted in the first place.
- **A fixed per-surface chord is spelled once**, in `ControlChords`, read by the button *and* by
  `KeyboardShortcutCatalog`. The window lists those same chords a few lines below the button that hints them;
  two spellings is how the two start describing different chords. `KeyboardShortcutsCatalogTest` fails if a
  constant stops being listed.

### Why the bubble is placed below, with a gap

It is a `Popup` offset by the control's own height plus 6 dp, carrying no pointer input and
`focusable = false`. A tooltip the cursor can reach steals the hover, which hides it, which returns the hover —
the "catch the bubble" flicker ADR 0002 records for the calendar's own hover bubble; and a focusable popup
would eat the click the hover is leading up to. Hover itself is read with `Modifier.onPointerEventCompat`
(promoted from `private` in `CalendarUi.kt` to `internal` rather than copied), which observes the Main pass
without consuming, so the button's click and any ancestor's drag are untouched.

On a touch-only device Enter/Exit never fire and no bubble is ever shown. That is the correct no-op: Android
and iOS report `Unsupported` for the system-wide chords anyway, and have no keyboard for the rest.

### Rejected

- **A tooltip on every button.** The bubble states one fact — "there is a chord for this" — and a bubble that
  also appears where there is none is a bubble the user stops reading.
- **A hover bubble as the app's general tooltip mechanism.** The calendar's bubble is a *stack of sections*
  reported up to a viewport-level overlay (ADR 0002) because its elements are drawn across each other; a
  button is one element with one thing to say, and reusing that plumbing would drag the whole hover-zone
  tiling into the lateral menu. The two share the pointer helper and nothing else.
- **Printing the chord in the button's label** ("Look away now (Ctrl+Shift+Alt+E)"). Every label doubles in
  width for a fact that matters once; and the lateral menu is 188 dp wide.

## Rebinding (1.6.0)

**Only the system-wide three can be rebound**, and that is not an arbitrary line. A system-wide claim is first
come, first served, so a chord another application already owns is *unusable* until the user can move it —
these three are the only shortcuts in the app that can collide with anything outside it. Everything else in the
window is a Compose handler scoped to a surface: nothing to collide with, and (per the paragraph above) nothing
to read the handlers back off, so a rebindable per-surface chord would mean re-routing ~40 hardcoded branches
through a lookup for no failure it fixes.

- **The binding is the account's**: `SchedulerState.shortcutBindings`, persisted **and** synced. The user's
  keyboard follows them to every machine. Unlike the settings beside it (`deepCopyMaxDepth`, the copy switches)
  a rebinding **is** an Undo/Redo unit — one deliberate gesture, on something whose effect is invisible from
  where the user is sitting when it is struck, so Ctrl+Z is the way back.
- **Overrides only.** A shortcut nobody has touched is *absent* from the map and follows
  `GlobalShortcut.defaultBinding`, so a default changed in a later build reaches every account that never
  rebound it — and "reset" is a removal, never a write of today's default.
- **The vocabulary is closed** (`ShortcutKey`: A-Z, 0-9, F1-F12). A chord has to survive the persisted snapshot,
  the sync wire and every platform actual's own naming; and a key whose position moves with the layout would
  give an AZERTY user a chord their QWERTY peer's machine does not have, for a binding that is the *account's*.
- **Two rules, in `GlobalShortcutBindings.rejection`** — the reducer refuses on it and the window shows its
  sentence, so a refused chord is never a silent no-op:
  1. **At least two of Ctrl/Shift/Alt.** The claim swallows the chord session-wide, so one modifier would take
     Ctrl+C or Alt+F4 away from every application the user runs, and none at all would eat their typing.
  2. **No two shortcuts on one chord.** One press cannot mean two actions. (Consequence: *swapping* two chords
     needs a third chord in between. Stealing the other shortcut's chord silently would be worse.)
- **Capture, not a text field.** The user presses the chord they want — and `setGlobalHotkeyCapture(true)` stands
  the claim down while the row listens, because otherwise the chords the app already owns would be precisely the
  ones it could never hear: the hook swallows them, and `RegisterHotKey` consumes them underneath, before Compose
  is handed the key. The flag empties the hot-key table from the loop thread and short-circuits the hook; it is
  balanced on a chord taken, Escape, focus lost, or the window closing.
- **Re-registration is the seam's own idempotence.** `installGlobalHotkeys` was already "claim once, re-point the
  callback"; a later call now also rewrites the chords. `RegisterHotKey(NULL, …)` belongs to the thread that made
  it, so the UI thread posts `WM_OMNIAPP_RECONFIGURE` to the hot-key loop rather than touching the table itself.
  The hook needs no such thing — it re-reads a volatile field on the next keystroke.
- **Merging can produce what neither device had**: two shortcuts landing on one chord, because each device
  rebound a *different* shortcut onto it. `SnapshotMerge.repair` drops the collision back to the default, and
  decode heals a stored table the rules would refuse today the same way — an account whose claim silently holds
  a chord the window would not let the user set is undiagnosable from the window.

## Tests

`KeyboardShortcutsCatalogTest` — every `GlobalShortcut` is listed, in order, in the window's first block, at the
chord the *account* is bound to; the shipped chords are the documented ones; no group lists a chord twice or
carries a blank entry; a button's bubble and the window print one chord (both resolved through
`GlobalShortcutBindings.chordOf`), and every `ControlChords` constant a button hints is still listed in the
window. `GlobalShortcutRebindTest` — the two rules, overrides-only + reset-as-removal, the single
Main history unit (including undoing a reset), the codec round trip, a pre-1.6.0 payload, and decode healing a
table today's rules refuse. `SnapshotMergeTest` — per-shortcut merge, a reset surviving, and the collision
repair. `GlobalShortcutReceiptTest` — every press posts a receipt naming its chord.

The hook itself is Windows-native and is verified on the machine (`Diagnostics` logs `global hotkeys: claim=…`
at startup, `global hotkeys: rebound to …` on a rebinding, and `global hotkey pressed: …` on each press;
`scripts\collect-diagnostics.bat` collects them).
