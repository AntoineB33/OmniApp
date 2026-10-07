# Platform differences

The **necessary** differences between Windows desktop (the reference), Android, iOS and the browser (JS and Wasm
builds). Anything not listed here must behave the same everywhere. Before adding a platform branch, check that the
difference belongs on this list; if it does, add it here.

## The two axes: the input, and the platform

Two different questions, answered in two different places:

- **What the user is pressing with** — mouse, finger, keyboard — is decided **per pointer event**
  (`PointerType.Touch`) or **per key event**, never by asking which platform the build is. So a touchscreen laptop
  gets touch gestures for its finger and mouse behaviour for its mouse, and the browser on a phone behaves like the
  phone app while the browser on a computer behaves like the desktop app. Keyboard behaviour needs no test of its
  own: without a keyboard, no key event ever arrives.
- **What the operating system offers** — an OS alarm clock, push, session lock events, global hot keys, a native
  solver — is decided by the platform's `actual`s (`scheduler/platform/`, `ui/PlatformCursor`,
  `ui/TaskPickerOverlay`) and by what the platform's entry point injects into `SchedulerEngine`.
  `DeviceKind` (`Phone` / `Desktop` / `Other` = browser) answers only which presence layer the device feeds and who
  speaks the pause cue — never a capability. A capability is tested by whether its seam was injected (see
  `SchedulerEngine.hasOsAlarmClock`).

## Input: keyboard and mouse vs. touch

| Action | Mouse + keyboard (desktop, browser on a computer) | Touch (phone, browser on a phone, touchscreen) |
| --- | --- | --- |
| "Lock to now" | **Space** in the calendar, or the switch | The switch (no Space without a keyboard) |
| Calendar zoom | `Ctrl` + `+` / `-` / `0`, `Ctrl` + scroll | Pinch; double-tap-and-drag |
| Calendar contextual menu | Right-click | Double-tap and release (PRD §8); the menu is then headed by the panel's info |
| Panel info | Hover bubble | Top of the contextual menu |
| Move a calendar panel | Drag it | The menu's "move", then drag |
| Resize a calendar panel | Drag its edge (resize cursor) | Through "edit…" |
| Drag in the calendar grid | Moves/selects what is under it | Scrolls the grid |
| Every other contextual menu (task-tree cell, priority-percentage column, weight-column header, Search rows, lateral-menu buttons, window bar) | Right-click | **Long-press** (`ui/TouchLongPress.kt`, through `contextMenuModifier`) |
| Drag over the task tree | Drag-selects a range | **Scrolls** the tree; a tap selects |
| Move tree cells | Double-click and drag | Double-tap and drag |
| Edit a tree cell's title | Double-click on the title | Double-tap on the title |
| Shift+click (extend / shorten the range) | Shift+click | Drag one of the selection's two **dots** — top-left of the first selected row, bottom-right of the last; the other end stays put (`TaskTreeView`, `SelectionHandlesTouchTest`) |
| Ctrl+click (disjoint multi-select) | Ctrl+click | — (no equivalent on a phone) |
| Undo / Redo | `Ctrl+Z`, `Ctrl+Y` / `Ctrl+Shift+Z`, or the window bar's **⋮** menu | The window bar's **⋮** menu (right of Reset) |
| Selection & position history | `Alt+←/→`, `Shift+Alt+←/→` | — **no on-screen control yet** (see *Open gaps*) |
| Copy / cut / paste tree text, find (`Ctrl+F`), keyboard navigation (arrows, Tab, Enter, Delete) | Keyboard | Partly: "copy task id" is in the cell menu; the rest needs a keyboard (*Open gaps*) |
| Timer countdown "+10 s"-style nudge menu | Right-click a countdown field | Type the digits (the nudge is an accelerator) |
| Search filter "pick this option alone" | Right-click the option | Tick the boxes (an accelerator) |
| Hover hints (shortcut hints, resize cursors) | Shown on hover | None — touch has no hover; nothing depends on them |

## Platform capabilities

| Capability | Windows desktop | Android | iOS | Browser |
| --- | --- | --- | --- | --- |
| Local store | SQLite file (`FileSchedulerStore`, WAL) | SQLite | SQLite | **One `localStorage` entry** (~5 MB cap; an overflowing save is logged and skipped, the server copy is unaffected) |
| Alarms & timers ring | In-process now-line sweep while the app runs | **OS exact alarm** (`AlarmClockScheduler`), rings with the app killed | In-process sweep while the app runs (no OS seam yet); `AVAudioPlayer` | In-process sweep while the tab is open; `Audio` element, `navigator.vibrate` |
| Notifications | Tray balloon | Notification channel; click opens the target | `UNUserNotificationCenter` | Web Notifications (asks permission on first use; a phone's browser refuses page notifications) |
| Voice | Bundled Piper WAVs + TTS | Same, `AudioTrack` | Same, `AVAudioPlayer` / `AVSpeechSynthesizer` | Same WAVs via `Audio`, text via `speechSynthesis` (plays only after the page's first click/key) |
| Clipboard | System | System | `UIPasteboard` | Writes to the system clipboard; a **paste reads back what the app itself copied** (browsers read the clipboard only asynchronously, behind a prompt) |
| Presence / activity signal | Windows session lock/unlock (`WM_WTSSESSION_CHANGE`) | Unlock broadcasts + foreground lease | None (reported inactive) | None (reported inactive) |
| Pause-end cue when every device is idle | — (speaks in-app only) | FCM push → OS alarm, with the app killed | APNs local notification (unverified, no Mac build) | — |
| Background running | While the process runs (auto-start release) | Foreground service + boot receiver | Only while foregrounded | Only while the tab is open |
| OS sleep / lock history ("no computer unlocked" layer) | Windows power log | None | None | None |
| System-wide shortcuts, task picker overlay | Yes (`Ctrl+Shift+Alt+…`) | Unsupported | Unsupported | Unsupported |
| Scheduler solver | Built-in search **+ OR-Tools MIP** | Built-in search | Built-in search | Built-in search |
| "The app is in focus" (the unfocused-notif window) | **The OS's answer**: one of the app's windows is the active one (`LocalAppInFocus`, AWT `activeWindow`) | The window's own flag (`isWindowFocused`) | The window's own flag | The window's own flag |
| Window placement memory, app window chrome | Yes | — | — | — |
| Launch-script login / start offline | System properties (`account*.bat`) | Intent extras | — | — |
| Debug time link, perf platform stats | Yes | — | — | — |

## Open gaps (not yet decided)

These make a touch-only device less capable than a computer, and need a product decision rather than a port:

1. **The selection/position history has no on-screen control** (Undo / Redo do: the window bar's ⋮ menu).
2. **Tree editing commands that only a keyboard reaches**: paste, cut, find & replace, Tab/indent, Delete, arrow
   navigation, Ctrl+click (Shift+click has the selection's dots).
4. **"The app is in focus" off the desktop** is still the window's own flag, which a menu or drop-down inside the
   app turns off — so a notification firing while one is open brings the unfocused-notif window up there too
   (anomaly 2026-10-07, fixed on the desktop by `LocalAppInFocus`). Each entry point should inject its own answer
   (Android: the activity is resumed; the browser: the page has the focus).
3. **Browser presence**: a browser tab reports itself inactive, so it never counts as "someone is at a screen". A
   Page Visibility signal would fix it, but adds heartbeat traffic the server-quota budget does not yet cover
   (`docs/invariants/server-quota.md`).
4. **Browser storage**: a large account outgrows `localStorage`; IndexedDB would lift the cap.
5. **iOS**: no OS alarm seam (rings only while running), no activity signal, push path unverified — all wait on a Mac
   build.

6. **A text field's right-click menu has no touch stand-in**: a time field's "+1 h … −1 h" (`ui/TimeNudgeMenu.kt`)
   and a timer's countdown fields' steps. A long-press inside a text field is the platform's own (select, paste), so
   it cannot be taken for the menu; a button beside the field would be the port.