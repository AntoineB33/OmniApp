# Pop-up windows

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## There is ONE sort of window

`ui/WindowFrame.kt`, `ui/PopupWindows.kt`. Every window of the app — a lateral-menu window, a task's edit
window, a period's, a category's, a notice — is the same thing with the same manners. The old split into
"sort 1, a window" and "sort 2, a transient pop-up that leaves the moment anything else takes focus" is
**gone**: nothing takes a window away any more.

- **A window stays until it is closed.** No press anywhere else dismisses one. A half-typed edit is not
  thrown away by a click aimed at something behind it, and a window put on screen is still there after the
  user has looked at what it covers.
- **The one exception is a MENU** — a right-click contextual menu or a drop-down. A menu is not a window; it
  is a question about one cell, one percentage, one id row, and it closes on the first press outside it
  (`TransientMenuHost`, below).
- **A window is still not modal.** No scrim, nothing blocked. The press that lands behind a window does its
  normal job (focusing the calendar, selecting a cell); it simply does not close anything.
- **Opening a window on another subject opens a second window; the first stays** (user rule, 2026-09-25 — it
  used to replace it). "The edit window of timer A" and "of timer B" are two windows, and asking for B says
  nothing about being done with A. **Asking again for a subject whose window is open brings that window back**
  (`WindowFrameHost.present`) rather than opening a second on it. Held by `ObjectWindows` (`ui/WindowFrame.kt`),
  one per kind in `App` (`taskEditWindows`, `weightWindows`, `alarmWindows`, …): the list of that kind's open
  windows, each under a frame id of its own (`TaskEdit#3`), cascaded off the centre. The one way to have two
  windows on ONE subject is the head's **duplicate** button (below). Nothing couples two kinds either: a click
  on a percentage no longer closes a relative-priority window. What still closes windows of a kind is a
  rule about their subject — a tree's weight and relative-priority windows when one of ITS cells enters Edit
  Mode, the calendar block editors when the focus moves.
- **A window opened from a tree names the tree** (`TreeObject`: the account's, or the default sub-tree's —
  PRD §4): several may stand open at once, from both trees, so each reads its own tree's state and sends its
  intents back into it. There is no app-wide "which tree asked" any more.

## The task tree is a window

Since 2026-09-24 the tree (`TaskSchedulerScreen`) is not the app's background but a lateral-menu window
(`TaskTreeWindow`, `FloatingWindow.TaskTree`, a "Task tree" button in the lateral menu). It opens **maximized**
the first time (`rememberWindowFrameState(defaultChrome = …)`), and as it was left after that. `App` draws it
FIRST, so the windows restored with it come back over it.

- **It does not `claimsKeyboard`**: the tree reads its own keys under its own rule (`keyboardOwned`, below), and a
  press in its window focuses it and dispatches the `Tree` focus, as a press in the bare tree did. The content
  Box behind every window claims NO scheduler focus (it only blurs the frames): it is every window's ancestor,
  so claiming one there recorded a move to the tree and back on every press in another window.
- **Reduced, it is deaf** (`TaskSchedulerScreen(keyboardEnabled = false)`): a reduced window stays composed, and a
  keystroke would otherwise rename a cell nobody can see.
- **Not duplicable**: its selection, edit session and scroll are `SchedulerState` view state; a copy would be a
  mirror. The content area behind every window is bare once the tree window is closed.

## The one surface that is NOT drawn inside the app

**The task picker** (`ui/TaskPickerOverlay.kt`, `shortcuts.md`) is an OS window of its own — undecorated,
always on top, at the pointer, holding the keyboard. It is the single exception to everything above, and it
is one because the chord that opens it (`Ctrl+Shift+Alt+T`) is struck while OmniApp is **not** the focused
window: a surface drawn in our window would appear behind whatever the user is looking at, far from their
pointer, and could not be typed into.

It is a **menu** by the rule below, not a window: no frame, no head, nothing reduces or maximizes it, no
place in `WindowFrameHost.stackOrder` (it is over every window of every application, ours included), and it
leaves on the first press outside it — which, for a window of its own, is the moment it loses the focus.
Nothing else may follow it out of the app: every other surface, per-object window included, is drawn inside
`App` where the stacking order can see it.

## The frame every window wears

`AppWindowFrame` (`ui/WindowFrame.kt`) is the whole of it, and it is one composable rather than a shared
"header" helper because the five buttons and the three edges are the *same* geometry (`WindowFrameState`)
read five ways. A second copy of that arithmetic is how two windows come to disagree about what "maximized"
means.

- **A head**, which is the handle the window is dragged by, carrying — in this order — **add to the menu, duplicate,
  fill width, fill height, reduce, maximize, close**. The order is fixed here and not per window, so every window's
  ✕ is under the same pixel. Duplicate (⧉) shows only where the window can be duplicated (below). Add to the menu
  (☆) puts a button for that very window (that copy) at the bottom of the lateral menu (`ui/CustomMenuButtons.kt`),
  and shows wherever `App` can open the window again from a button: a **lateral-menu window** or copy, by its
  frame id (`MenuButtonHost.canAdd`), and a **per-object window about an object with a stable id** — a task's edit
  window, a category's, a period kind's, a weight table, a relative priority, a deep copy, an alarm's, a timer's,
  a reminder's, and the default alarm, timer and reminder — by its `ObjectWindowKey` (the kind, the tree and the id; `ObjectWindows`' `menuKeyOf`, handed to
  the frame on `WindowInstance.menuKey`). One host for every frame, so no window is wired for it one by one.
  A window about something transient (a calendar edit draft, the elements at a spot, the constraint picker, a
  companion, a notice) has no ☆: a saved button would have nothing to come back to.
  - **The key follows what the window shows**: the alarm, timer and reminder windows move on to the element their
    "+ New …" made (`ObjectWindows.Window.retarget`), and the registration's `menuKey` is kept current
    (`WindowFrameHost.rekey`), which is what the button's "close it when it is the window being worked in" reads.
  - **A button whose object is gone is hidden, not deleted** (`ObjectWindowKey.exists`), so an undo brings it back.
- **Maximize is exactly "fill both axes"** (`WindowFill.Both`), never a sixth state of its own. Pressing the
  two fill buttons in turn therefore lands on the same window as pressing maximize, and un-maximizing puts
  back the size *and* position the window had before the first of them — per axis, so releasing "fill width"
  on a window that is also filling its height restores only its width.
- **Double-clicking the head** maximizes, and un-maximizes when it already is. It is detected inside the
  head's own gesture (`windowHeadGestures`) — a press counts as a click only if the pointer came back up
  without having travelled past the touch slop, so a slow drag can never read as a double-click. Two
  `pointerInput`s on one head race for the press and whichever wins decides whether the other ever sees the
  gesture.
- **The head drag has no dead zone**: the window follows the pointer from its first movement. The slop is
  only the click test above, never a threshold before moving. That is affordable because the head holds
  nothing interactive but its own buttons (which consume their press); a window's toggles belong
  inside the window (the calendar's configuration section), not in the head.
- **A maximized window fills the content area**, which is the app minus the lateral menu — and grows to the
  whole app when the menu is retracted, because the content area does. Nothing in the frame knows about the
  menu: `fillMaxWidth`/`fillMaxHeight` inside the content `Box` is the entire mechanism.
- **Resized by all four edges and all four corners** (a corner: both axes at once, under the oblique double
  arrow, `diagonalResizePointerIcon`; drawn over the edges' overlap, and only while neither axis is filled).
  The TOP was ruled out until 2026-09-24 ("a head moving under the cursor cannot be made to feel right"),
  which every OS window manager contradicts; what is actually owed there: **growing upward stops at the
  content area's top** (`resizeTopBy`, from the height `clampVertical` last saw) so the head stays reachable
  and the clamp never pushes the window down instead; and **no grab zone covers a head button** — the top edge
  and top corners are only as thick as the head's top padding, and the head's end padding clears the right
  edge. A window is centred on
  its offset, so growing it by `d` on one edge moves the centre by `d/2` — that is what keeps the *opposite*
  edge where the user left it. The offset moves by the width **actually applied**, so a drag clamped at
  `MIN_WINDOW_WIDTH_PX` does not walk the window sideways.
- **A filled axis is inert on that axis**: it offers no resize edge and ignores the head drag there, because
  it is pinned to the container. Its drag offset is *remembered* rather than zeroed, which is what lets
  un-filling put the window back.
- **Every window has an explicit default width AND height**, and its content sits in a `weight(1f)` slot.
  Never `heightIn(max = …)` + wrap: a window whose height is its content's cannot be given a different one
  by dragging its bottom edge, which is the whole point of the edge.
- **The window bar along the bottom of the app — its system tray** (`WindowBar`) appears whenever a window is
  open and has a **tab for every open window, the reduced ones included** (set back in italics), in the order
  they were opened. Drawn at the app root and **over the lateral menu** — a window reduced while the menu is
  open must not be filed behind it. The content area is inset by the bar's height while it shows, so a
  maximized window stops above it. A tab brings its window back (`present`); its ✕ closes it outright.
- **The lateral menu's scrolled content ends a bar's height lower** (`LateralMenu`'s bottom padding, `+
  MINIMIZED_BAR_HEIGHT`), always — so scrolled to the end, its last button clears the bar drawn over it, and
  the menu does not jump as the bar comes and goes.
- **Close all is the bar's, at its right corner** — every registered window's own close, over a snapshot. It
  replaced the lateral menu's "Close windows" (2026-09-24), which listed the lateral-menu windows by hand and
  so never closed a per-object window, a copy or a notice.
- **A reduced window is still composed, merely not placed** (`Modifier.unplaced`). Not composing it throws
  away everything half-typed in it, which is not what pressing *reduce* asks for.
- **A WINDOW'S HIT REGION IS ITS DRAWN RECTANGLE, so nothing may hang off it outside the frame's
  `offset`.** `Modifier.offset` reports its child's size *at its own position* and merely places the child
  elsewhere, so a pointer-input (or focus) node applied around it answers for the rectangle the window
  would occupy **undragged** — an invisible region at the centre of the content area, standing at that
  window's z. Compose stops hit-testing lower siblings the moment one records a hit, so such a ghost eats
  every press aimed at the window behind it: the reminder window's "a press on bare chrome leaves Edit
  mode" `detectTapGestures` made the Alarms window under it impossible to bring forward (2026-09-21).
  `AppWindowFrame` therefore applies the caller's `modifier` **inside** the offset and the size, below
  every geometry modifier of its own — `align` is parent data and is read from anywhere in the chain, so
  a caller loses nothing by it. A window that wants a press handler of its own gets it there, never around
  the frame.
- **Nothing drawn over the windows may hide a head's title.** The one such thing is the lateral menu's
  collapse toggle (`IconMenuButton`, z 130): it hangs from the app's ceiling and is shorter than a head, so it
  can only ever stand over the head of a window at the top of the content area — and it publishes its bounds
  (`LocalHeadObstacle`), from which every head moves its title just past it, live, as the window is moved or
  resized, and back once clear. Only the shift is state, so a drag recomposes a head only when it changes.
  The title starts at the head's first point that is visible AND uncovered, measured from the head's REAL
  left edge (`positionInRoot`), never its visible bounds (`boundsInRoot` stops at the content area's edge —
  exactly where the toggle stands — and made the shift a mere nudge). A window hanging past that edge gets its
  title moved to the edge the same way.
- **The head is always reachable.** `WindowFrameState.clampVertical`, fed by the frame's own layout, keeps
  the head between the top of the content area and its lowest row — including for a window taller than the
  area, which centred would put its own head (and all five of its buttons) out of reach. Clamping a fixed
  point converges, so re-applying it on every layout pass is safe. This lives in the frame, not in the
  calendar window that first needed it.

## Duplicating a window

The head's **⧉** opens an **independent copy** of the window (2026-09-24): the same window, starting from the
same view (the Search window's query, types and filters), which then changes on its own.
The account's data is shared, of course — an alarm edited in one copy shows in the other.

- **One mechanism, not one per window.** `LocalWindowInstance` (`ui/WindowFrame.kt`) tells a window which copy
  it is. `rememberWindowFrameState` appends the copy's suffix to the frame id (`Search#2`), so a copy has its own
  place in the stacking order, the reduce bar and the chrome memory — and so do the windows nested in it, which
  inherit the suffix. `AppWindowFrame` takes a copy's **close, geometry and raise** from `WindowCopy`, never
  from the caller: the call site is the original's, and wiring it straight through would close, move or raise
  the original. The copy's title carries its number, `Search (2)`.
- **Inside the frame the copy wiring is stripped** (the suffix kept), so nothing nested picks up a copy's close.
  A companion drawn beside its window in the same wrapper (the History row info, a task tree's detail) is put
  in `CompanionWindowScope` for the same reason, and raises the pair through the copy's wiring. It has no ⧉:
  duplicating the window duplicates the pair.
- **A copy's view configuration is read by FRAME id** (`windowInstanceId`), never from one `App` variable:
  `searchConfigs`, `configSearches`. A window whose keyboard ownership was
  `focusedWindow() == X` reads `windowFrames.frontId == windowInstanceId(X)`, or no copy could ever own it.
- **Lateral-menu windows**: `App.LateralWindow` draws the one call site for the original and each copy. A copy
  is its own `window_placement` row (`Name#n`) — position, size, chrome, config — so it comes back at startup
  like the original; closing it clears the row's `visible`. The copy of a copy is a new top-level copy.
- **Per-object windows**: `ObjectWindowsHost(windows) { w -> }` draws every open window of an `ObjectWindows`;
  a copy is one more window on the same subject, titled with its number. A window must close through
  `w.close()` (its Save, bin and ✕ close that window, not another), and read its object off `w.subject`.
- **Not duplicable**: the **task tree** (above), the **Calendar** — its display pipeline in `App` (records, projections, the schedule
  horizon handed to the engine) is derived from ONE visible span, so a copy on another week would drag every
  projection to its span and leave the original blank; it needs that pipeline made per-window first
  (`display-hot-path.md` binds each to its visible window). **Notices** (`MessagePopup`) — a message has no
  second view. The debug time-sim panel wears no frame.
- **Known sharing**: the Search window's sub-tree expansion, selection and edit session, and the default
  sub-tree's tree state, are `SchedulerState` view state, not the window's — their copies share them.

## Geometry is local-only view state

- The **position and size** of a lateral-menu window persist locally (`WindowPlacement`: `x`, `y`, `width`,
  `height`, `visible`) and are **never synced** — `CLAUDE.md`'s local-only view state, unchanged. They are
  written at the END of a move or resize gesture (`onGeometryChange`), never mid-drag.
- **Reduced / filled / maximized persist too, locally** (`fill_width`, `fill_height`, `minimized` on the same
  row — 14.sqm, 2026-09-24; they were session-scoped before). A lateral-menu window comes back as it was left —
  full width, full height, full size, reduced to the bar — after a close and after a restart. The frame reads
  it on creation and writes every change (`WindowChromeMemory`, provided by `App`); nothing else touches it.
  The row keeps the NORMAL geometry, so un-filling a restored window goes back to it.
- **A CLOSED window is not reduced**: closing one clears its `minimized`, so opening it from the lateral menu
  shows it. Its filled axes are kept.
- **Every write goes through `App.updatePlacement`**, which keeps the columns it is not changing — the store's
  upsert replaces the whole row, so a geometry write that rebuilt it from scratch would wipe the chrome state
  and the window's config.
- **A window may keep its own configuration on its row** (`config`, serialized by the window's owner): the
  Search window's query and checked kinds (`SearchDomain.Config`), so it reopens with them. Local-only view
  state like the rest of the row.
- **A per-object window about an object with a stable id comes back after a restart** (2026-09-25 — they used
  to persist nothing, and two open timer windows were gone after a rebuild). Each keeps a row of its own, by frame
  id (`TaskEdit#3`, `AlarmOrTimerEdit#2`), whose `config` is its `ObjectWindowKey`: written when it opens (a fresh
  row), when it moves on to another object, at the end of a move or resize, and on close (`visible` off) —
  `ObjectWindowMemory`, implemented by `App` over the same rows. At startup `App` reopens every open row on its
  object under the same number (`ObjectWindows.restore`), so its geometry and chrome come back with it; one whose
  object is gone since closes itself as it draws. The app STOPPING closes nothing: only the user's close clears a
  row. The frame id's base is the window's own constant (`TASK_EDIT_FRAME_ID`, …), which `ObjectWindowKey.Kind`
  reads — the row and the window must agree on it.
- **The desktop app's OWN window comes back as it was left too** — its normal position and size (dp), and
  whether it was maximized — on the reserved row `AppWindow` (`rememberPersistedAppWindow` +
  `KeepAppWindowPlacement` inside the window, `ui/AppWindowPlacement.jvm.kt`, used by `desktopApp`'s `main`).
  Written once a move, resize or maximize has settled (½ s), never per pixel, never while minimized. **The normal
  bounds are read off the OS window, never off `WindowState.position`**, which stays `PlatformDefault` for a
  window never moved — so a first launch maximized at once was never kept (2026-09-25); maximized before any
  normal bounds were seen, it is kept maximized over the default size centred on its screen; the normal bounds are kept under a maximize, so un-maximizing after a restart
  goes back to them. A saved place no screen shows a grabbable stretch of its title bar on (a monitor unplugged
  since) is dropped for the platform default. Minimized is not kept.
- A per-object window about something transient (a calendar edit draft, the elements at a spot, the constraint
  picker) persists nothing: it lives exactly as long as it is on screen.

## The keyboard

- **NOT MODAL IS ABOUT THE POINTER; THE KEYBOARD IS THE WINDOW'S.** The task tree goes **deaf** while what
  the user is working in owns the keyboard (`TaskTreeView`'s `keyboardOwned`) — PRD §4's "type a letter on
  the selected cell and it starts renaming" would otherwise fire behind the window being typed into. Read in
  `TaskTreeView`, so all three drawings of the tree get it at once; never a flag threaded down by each
  surface.
- Two things take it: **a menu standing over a cell** (`TransientMenuHost.anyOpen`) and **a focused window
  that answers keystrokes itself** (`WindowFrameHost.keyboardClaimed`, from `claimsKeyboard = true`).
- **That second half follows the FOCUS, not "something is open".** It has to: a window no longer leaves on
  an outside press, so an open-based rule would hold the keyboard for as long as the window stood there and
  the tree could never be typed in again without closing it. A claiming window **takes** the focus when it
  opens (the press that opened it landed in the tree, so nothing else would hand it over) and gives it back
  on the first press in the tree, which the content `Box`'s `raiseOnPress` turns into `blur()`.
- Deaf, never blind: a press still reaches the tree, and that is exactly what hands the keyboard back.
- A window that answers keystrokes hands the keyboard on to a row's own field while one is being edited, and
  takes it back when that closes.

## Menus — the one thing that still leaves on an outside press

- **One observer, at the app root** (`transientMenuDismissRoot`), watching the **Initial** pass without
  consuming. Never a per-menu outside-press handler.
- A press inside a `DropdownMenu`/`Popup` draws in its own layer and never reaches that observer, which is
  what makes "any press the observer sees" mean "a press outside the menu" — so a menu publishes no bounds,
  and a window's own menus never close it.
- The menu's popup must be **non-focusable** (`PopupProperties(focusable = false)`): a focusable
  `DropdownMenu` consumes the outside press for its own `onDismissRequest`, and that is exactly the press
  PRD §13 needs to go on and select the next cell — right-click one cell, click another, and the second cell
  is selected in the same gesture. **Wherever a menu is given `focusable = false`, the
  `transientMenuDismissal` registration must come with it** — a non-focusable menu nobody registered never
  closes at all.
- **The field a drop-down hangs from toggles it** (`Modifier.menuToggleClickable`): a click while the menu is
  open closes it. A plain `clickable { open = true }` reopens it instead, because the root observer has
  already closed it on the press and the click lands after; the field reads the menu's state as last
  composed at the press. Every drop-down field uses it, never a hand-rolled toggle.
- **A field's edit mode that must end on a press elsewhere registers with the SAME observer**
  (`Modifier.leaveOnOutsidePress`, `TransientMenuHost.openEditor`) — never a per-field outside-press handler.
  A text field alone keeps the caret when the press lands on something that takes no focus (a window's bare
  chrome, the calendar, the tree's background), so its edit mode never ended (the timer's countdown fields,
  2026-09-26). It publishes its bounds in the window, so a press INSIDE it keeps the edit; a menu opening does
  not end it (its own right-click menu would), and it does not make the tree deaf.
- Registering the menu also makes the tree deaf while it is up, which is why the edited cell hands its caret
  back for as long as a menu stands over it (`task-tree.md`, *The selection and Edit Mode belong to the
  TREE*).

## Where a window is drawn

- A window must be drawn **where it can be on top**. The tree's `TaskEditWindow` / `DeepCopyWindow` are
  raised out of `TaskSchedulerScreen` into `App` for that reason (inside the tree they drew *under* any
  floating window stacked over it); `ReminderConstraintEditWindow` uses a `Popup` for the same reason, and
  the frame inside it is the ordinary one.
- `TransientPopupLayer` is the full-screen, deliberately inert layer a per-object window centres its frame
  in — no scrim, no pointer input, so the app behind it stays live.
- A window that opens a second window beside it (the task-trees list and its detail, the history list and
  its row info) wraps both in one `Box` and positions the second off the first's `frame.offset`.
  - **The companion is a plain frame in that `Box`, never a `TransientPopupLayer`.** The layer is
    `fillMaxSize`, so it grows the `Box` to the whole content area and the first window, placed inside it,
    jumps across the screen the moment the companion opens (the History window did, 2026-09-13).
  - **The `Box` centres its content** (`contentAlignment = Alignment.Center`): a frame's offset is "from
    centred", and the `Box` is as big as its larger child, so a top-start `Box` moves the first window
    whenever the companion is the bigger of the two.
  - **The `Box` takes the z of the TOPMOST of the two** (`windowStackZ(id, companion)` →
    `WindowFrameHost.zOf(id, companion)`), and names the companion only while it is open. A companion's own
    `zIndex` never leaves the wrapper, so reading the first window's z alone made a press in the companion
    raise a window nothing draws with: the History row-info window could not bring its pair back over
    whatever stood in front of it (2026-09-20). Naming a *closed* companion is the opposite bug — an id that
    is not in the stack reads as the top, which would pin the pair over every window.
  - **A press in the companion is a press in the pair**: it goes through the first window's `onRaise` (so
    `App` stamps `activeHistoryWindow` and raises it), and the companion then takes the focus back, being
    the innermost window the press landed in — that is what keeps the keyboard it claims.
- **Asking again for a window that is already open brings it back** (`WindowFrameHost.present`: out of the
  reduce bar, to the top, into the focus) — **the reduce bar's chip included**, which raised without
  focusing and so brought a keyboard-claiming window back with the tree still holding the keyboard. Opening raises it through `register`, but re-asking for the same
  subject changes no state of the caller's, so without it the window stays under whatever the user moved to.

## What is drawn OVER what

**There is ONE stacking order and every window is in it** — `WindowFrameHost.stackOrder`, bottom first.
It lives in the host because the host already sees the only two events that decide it: a window **opening**
(`register`) and **a press landing inside one** (`focus`). `zOf(id)` is the `zIndex` that order comes out as.

- **A window opens on top, and goes under the next window the user presses in.** Nothing else moves it.
  Restoring a reduced window from the bar counts as opening it: it comes back on top.
- **No caller may pin a window's z.** Per-object windows used to sit on a fixed `zIndex(100f)` above the
  lateral-menu windows' own 0..n stack — correct only while such a window vanished on the next press. With
  windows that stay, that fixed layer is what left the priority-weight table standing over every window
  opened after it. The order is now read from one place (`Modifier.windowStackZ`) and from nowhere else.
- **The z goes on the window's OUTERMOST element**, because `zIndex` only orders a node among its own
  siblings. `AppWindowFrame` applies it to itself; a window drawn inside a wrapper applies it *there* too —
  `TransientPopupLayer` (which takes the frame id for exactly this) and the `Box` a window pairs itself
  with a companion window in. A wrapper with no z is an invisible lid: whatever the frame inside it says
  about the order is confined to that wrapper.
- **The stack is not the reduce bar's order.** `WindowFrameHost.entries` stays in the order windows were
  *opened* in, which is what the bar lists — a chip must not jump along the bar because its window was
  raised. Two orders, two questions, deliberately not one list.
- **A window that is not in the stack yet reads as the top** (`zOf` returns `stack.size`). Its `register`
  runs after the composition that first draws it, and reading as the bottom would draw a newly opened
  window under its neighbours for one frame.
- **The lateral-menu button closes its window only when that window is the FRONT one** —
  `WindowFrameHost.frontId`, the top of the *whole* stack. Not "the topmost lateral-menu window": a
  per-object window standing over the Alarms window makes the answer null, and the button then brings
  Alarms back to the front (and to the focus) instead of reading as "you are already here" and closing it.
- **Moving to a window takes the focus**, however the move was asked for. `App`'s `focusWindow` goes through
  the same `WindowFrameHost.focus` a press inside the window would, or the menu button raises a window the
  app still believes is un-focused — and the keyboard stays with whatever was focused before it.
- `App` keeps no stacking order of its own. The one z left there is the debug time-sim panel's, which wears
  no window frame.

---
