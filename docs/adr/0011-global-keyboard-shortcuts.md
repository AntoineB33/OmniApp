# ADR 0011 — System-wide keyboard shortcuts: the chord must be swallowed, not merely observed

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *System-wide keyboard shortcuts*.

Two of the app's actions are wanted at a moment when OmniApp is, by definition, **not** the focused window:

| Chord | Action | Why the app is not in front |
| --- | --- | --- |
| `Ctrl+Shift+Alt+A` | "I'm away" / "I'm back" (`SchedulerEngine.setUserAway`) | the user is walking away from whatever they were working in |
| `Ctrl+Shift+Alt+E` | "Look away now" (`SchedulerEngine.restartLookAway`) | the user decides mid-task to rest their eyes |

So neither can be a Compose `onPreviewKeyEvent` handler: a focus-scoped chord fires only in the one situation
where the lateral-menu button is already one click away. Both are claimed from the OS in
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

Alt is part of both chords, so the letter arrives as `WM_SYSKEYDOWN`/`WM_SYSKEYUP`, not the plain spellings —
both have to be recognised.

## Rejected

- **A Compose key handler.** Fires only when the app is focused; see above.
- **`RegisterHotKey` alone.** The post-mortem above.
- **Re-installing the hook periodically to stay at the head of the chain.** Hooks are called most-recently-installed
  first, so a later-starting application takes the head back. Chasing that is a fight with no end state, and
  installing repeatedly is exactly the behaviour anti-malware heuristics flag. One install, one fallback.
- **A separate boolean/second enum for "which chords exist".** The window would drift from what the OS was
  actually asked for. `GlobalShortcut` is the single source, and `KeyboardShortcutsCatalogTest` pins it.

## Not Windows

Android and iOS report `Unsupported`: a backgrounded app cannot claim a system-wide chord, and the lateral
menu's buttons are the whole surface there. A non-Windows desktop host logs the fact and runs on — a missing
shortcut must never keep the app from starting.

## The keyboard-shortcuts window (PRD §7)

The lateral menu's last button opens `ShortcutsWindow`, a plain reference list built from
`KeyboardShortcutCatalog`: the system-wide block first (derived from `GlobalShortcut`, plus the claim line),
then the task tree, Edit Mode, history, calendar and the tree-name field. Those per-surface blocks are prose —
nothing can read the `onPreviewKeyEvent` branches back — so a new chord and its catalogue entry belong in the
same change.

## Tests

`KeyboardShortcutsCatalogTest` — every `GlobalShortcut` is listed, in order, in the window's first block; the two
chords are the documented ones; no group lists a chord twice or carries a blank entry. The hook itself is
Windows-native and is verified on the machine (`Diagnostics` logs `global hotkeys: claim=…` at startup and
`global hotkey pressed: …` on each press; `scripts\collect-diagnostics.bat` collects both).
