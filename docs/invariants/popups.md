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
- **At most one window per subject is open at a time.** "The edit window of task A" and "of task B" are two
  windows and the user only ever means the one they just asked for, so opening the second **replaces** the
  first. This is held by the state that opens them (`App`'s `editTaskId`, `weightWindowListId`, …), which is
  a single slot apiece — not by an outside-press rule.

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

- **A head**, which is the handle the window is dragged by, carrying — in this order — **fill width, fill
  height, reduce, maximize, close**. The order is fixed here and not per window, so every window's ✕ is
  under the same pixel.
- **Maximize is exactly "fill both axes"** (`WindowFill.Both`), never a sixth state of its own. Pressing the
  two fill buttons in turn therefore lands on the same window as pressing maximize, and un-maximizing puts
  back the size *and* position the window had before the first of them — per axis, so releasing "fill width"
  on a window that is also filling its height restores only its width.
- **Double-clicking the head** maximizes, and un-maximizes when it already is. It is detected inside the
  head's own gesture (`windowHeadGestures`) — the press is only counted as a click once the touch slop has
  been awaited and *not* crossed, so a slow drag can never read as a double-click. Two `pointerInput`s on
  one head race for the press and whichever wins decides whether the other ever sees the gesture.
- **A maximized window fills the content area**, which is the app minus the lateral menu — and grows to the
  whole app when the menu is retracted, because the content area does. Nothing in the frame knows about the
  menu: `fillMaxWidth`/`fillMaxHeight` inside the content `Box` is the entire mechanism.
- **Resized by the LEFT, RIGHT or BOTTOM edge.** Not the top: the head is there, and a window whose head
  moves under the cursor mid-drag is the one edge that cannot be made to feel right. A window is centred on
  its offset, so growing it by `d` on one edge moves the centre by `d/2` — that is what keeps the *opposite*
  edge where the user left it. The offset moves by the width **actually applied**, so a drag clamped at
  `MIN_WINDOW_WIDTH_PX` does not walk the window sideways.
- **A filled axis is inert on that axis**: it offers no resize edge and ignores the head drag there, because
  it is pinned to the container. Its drag offset is *remembered* rather than zeroed, which is what lets
  un-filling put the window back.
- **Every window has an explicit default width AND height**, and its content sits in a `weight(1f)` slot.
  Never `heightIn(max = …)` + wrap: a window whose height is its content's cannot be given a different one
  by dragging its bottom edge, which is the whole point of the edge.
- **Reduced windows go to the bar along the bottom of the app** (`MinimizedWindowBar`), drawn at the app
  root and **over the lateral menu** — a window reduced while the menu is open must not be filed behind it.
  The content area is inset by the bar's height while it has rows, so a maximized window stops above it.
  A chip restores its window; its ✕ closes it outright.
- **A reduced window is still composed, merely not placed** (`Modifier.unplaced`). Not composing it throws
  away everything half-typed in it, which is not what pressing *reduce* asks for.
- **The head is always reachable.** `WindowFrameState.clampVertical`, fed by the frame's own layout, keeps
  the head between the top of the content area and its lowest row — including for a window taller than the
  area, which centred would put its own head (and all five of its buttons) out of reach. Clamping a fixed
  point converges, so re-applying it on every layout pass is safe. This lives in the frame, not in the
  calendar window that first needed it.

## Geometry is local-only view state

- The **position and size** of a lateral-menu window persist locally (`WindowPlacement`: `x`, `y`, `width`,
  `height`, `visible`) and are **never synced** — `CLAUDE.md`'s local-only view state, unchanged. They are
  written at the END of a move or resize gesture (`onGeometryChange`), never mid-drag.
- **Reduced / filled / maximized are session-scoped**: not persisted, deliberately, so no schema migration
  rides on a window chrome change. A window comes back where and at what size it was left, un-reduced.
- The windows a per-object pop-up opens (`TaskEdit`, `CategoryEdit`, …) persist nothing at all: they live
  exactly as long as they are on screen.

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
