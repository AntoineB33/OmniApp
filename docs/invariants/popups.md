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

- **The Search window is to be the only window of the app** (user rule 2026-11-25: *"Generalize this fix so that the search
  window is the only window of the app."*). Done for the account's deep copy, weight table and relative priority (a
  Search window with the row asked for pinned, `task-tree.md` § *The grouped actions*), and for the blank row ending a list
  of children, which shows a pop-up instead of a window (below). **The left-side menu's History, Sleep, Alarms, Task
  relations, Categories, Keyboard shortcuts and Online are Search windows** (`App.lateralAsSearch`): asked for by any path
  (a button, a calendar choice, a window left open before), the Search window set for what they showed opens or is
  brought back — the history units; the alarms, timers, chronos and reminders; the task relations (their "keep" and
  "strike off the list" are actions, `AddedAction.TaskRelationKeep` / `TaskRelationDrop`); the task categories; the
  keyboard shortcuts; the Sleep period with its "Sleep schedule" pinned; the Online setting with its action pinned, which
  draws what the Online window showed (`OnlineContent`, `LocalOnlineContent`). **The default alarm / timer / reminder
  window** is the Search window of that kind's creation row, its group pinned (`openDefaultsSearch`,
  `SearchDomain.groupIdOf`). **The reminder "constrained in" picker** is a drop-down's Search pop-up stuck to its button
  (`ReminderConstraintPicker`). **A double-click on a calendar block** opens
  the Search window of its element (the task, the period kind, the reminder) with the block checked and the "Checked
  blocks" group pinned (`openBlocksSearch`): "Blocks on the calendar" (its start, its end, its removal) and the rows
  the block editors asked — Task, Pins, Repeat every, Open bounds (∞), Reminder tag (done, pinned) — each written at once
  through the panel's intent (`SearchDomain.blockPanelIntent`, `CheckedBlocksActionEditor`). A band the app derives
  (a past stretch of inactivity) is no block: its double-click opens the period's Search window with "Add to the
  calendar" pinned at the band's start. The block, period, reminder-tag and "add… / edit…" editor windows are gone from
  `App`. A pinned Search window is matched by its added elements, not its search alone (`openPinnedSearch`). **The Default
  sub-tree** is an app setting (`AppSettingEntry.DefaultSubtree`) whose two actions are what its window held: "Applied
  to new tasks" (the switch) and "Template tree" (`DefaultSubtreeTree`, the template drawn as the task tree, its keyboard
  while it holds the focus); its button and a task's "Default sub-tree" open that Search window, the tree pinned.
  Still windows of their own: the per-object windows a template row opens (its weights, relative priority, edit task,
  category, deep copy — a Search window acts on the account's tree, not the template's), and the debug time-simulation
  panel, which is no window of the app.
- **The blank row ending a list of children shows a POP-UP, never a window** (user rule 2026-11-25: *"instead of opening a
  new window with a tab in the system tray, open a pop-up window. Add the arrow indicating that a pop-up window will appear
  when hovered over."*; `ChildSearchPopup`, `HoverPopupRow`'s `ask`). Hovered it shows the Search window of what can be
  made its parent's child (`ElementTree.childSearchConfig`), stuck to the row on `MenuSide.SUBMENU`'s sides. **Only a
  click shows it** (user rule 2026-11-26: *"remove the arrow in the empty rows at the end of children list. The pop-up
  window must appear only when the row is clicked."*; `HoverPopupRow`'s `opensOnHover` false): a click, a double-click or
  Enter, never a hover, and the row wears no arrow.
- **A pop-up's creation row is at the top** (user rule 2026-11-25: *"In the pop-up window, the creation element must
  appear at the top. So fix the default searching configuration of this pop-up window, and fix it as well for other
  contexts."*): `childSearchConfig` sorts by `CREATION_FIRST_SORTS`; `defaultSortsOf` no longer counts the element a
  pop-up comes from (`excludedElements`) as a filter of the user's; a drop-down's "create “…”" row
  (`SearchDomain.optionResults`) leads its list, before "Select all".

## The task tree is a window — removed 2026-11-11

**User rule 2026-11-11: there is no task tree window any more.** `TaskTreeWindow`, `FloatingWindow.TaskTree` and the
lateral menu's "Task tree" button are gone (`CustomMenuButtons.decode` drops a kept button for it, `TaskTree` or a copy);
the tree is looked through in Search windows — a task row and its sub-tree. **"Go to task tree" opens the task's own
Search window** (`goToTaskTreeAt` → `openElementSearch`), and **the Default sub-tree window is opened from a task
element's actions** (`AddedAction.TaskDefaultSubtree`), where the tree window's configuration section opened it.
`TaskSchedulerScreen` is no longer composed by `App`; its keyboard went with it, so no key reaches a tree nobody sees.
What follows is the window as it was, kept for its history.

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

### The head, maximize and the drag

- **A head**, which is the handle the window is dragged by, carrying — in this order — **add to the menu, duplicate,
  fill width, fill height, reduce, maximize, close**. The order is fixed here and not per window, so every window's
  ✕ is under the same pixel. Duplicate (⧉) shows only where the window can be duplicated (below). Add to the menu
  (☆) puts a button at the bottom of the lateral menu (`ui/CustomMenuButtons.kt`) that opens a NEW window of that
  kind **with the configuration this window has now** (`CustomMenuButton.config`: the Search window's query, types,
  filters and sorting; the Configuration Search window's own) — a saved search is a button. It shows wherever `App` can open the window again from a button: a **lateral-menu window** or copy, by its
  frame id (`MenuButtonHost.canAdd`), and a **per-object window about an object with a stable id** — a task's edit
  window, a category's, a period kind's, a weight table, a relative priority, a deep copy, an alarm's, a timer's,
  a reminder's, and the default alarm, timer and reminder — by its `ObjectWindowKey` (the kind, the tree and the id; `ObjectWindows`' `menuKeyOf`, handed to
  the frame on `WindowInstance.menuKey`). One host for every frame, so no window is wired for it one by one.
  A window about something transient (a calendar edit draft, the elements at a spot, the constraint picker, a
  companion, a notice) has no ☆: a saved button would have nothing to come back to.
  - **The left-side menu is a Search window, on its own background colour** (user rule 2026-11-13: *"Make the
    left-side menu a Search window as well, with a specific background color."*; `LateralMenu`, `SearchWindow`'s
    `menuList`, `MenuSearchRows`, `LATERAL_MENU_SEARCH`, `LATERAL_MENU_ID`, `LateralMenuSearchTest`). **The page
    selector is an element of the menu** (user rule 2026-11-14: *"The feature page selector must be an element, so it
    must appear below the search bar"*; `pageSelectorAnswers`): the first of the list, under the bar, kept by the bar
    while what it holds names a page — still the one item that is not the user's (never removed, renamed or moved).
    **Its header cannot be shown**: the one Search window whose background menu has no "Show the header"
    (`SearchPartsMenu`'s `parts`). **Its id is a row of its own** (found 2026-11-14: it was `LateralMenu`, the row the
    Task Scheduler page's items are kept on, and each wrote over the other). The parts it shows are kept across a
    restart (`SearchPartsStore`, with the windows' sections), as each actions section's are (`<window>/actions`).
    The menu is the very `SearchWindow`, its search section alone, at the menu's width (never a section's least):
    its **bar** keeps the items whose name holds what it holds (`CustomMenuButtons.answering`), its **▾** opens its
    configurations, its **background menu** holds "Reset" then the window's parts. **Its items are rows of the Search
    window's own list** (anomaly 2026-11-14: *"I do shift+click to select several rows, but it doesn't work"* — the menu
    drew a list of its own, `CustomMenuSection`, with no selection; `customMenuRows`, `MenuItemRows`, `GivenRow`,
    `MenuFolderDragTest`): the list selects them (Ctrl, Shift — a Ctrl/Shift press selects without pressing the item),
    walks them by the keyboard (Enter presses the selected one), scrolls them with its bar; the menu draws each item in
    its row (its folder lines, its arrow, its own right-click menu) and keeps their order — no sorting: the order is the
    user's. While **"Arrange by dragging"** is on (it was "Customize the left-side menu", 2026-11-14) a press selects and
    presses nothing, and a held press drags **the selected rows** (`ArrangeState`, `arrangePress`): over the middle of
    a row they go into a folder with it, between two before the lower, at the depth the line shows. The same drag
    arranges **any** Search window's list, which keeps the order in `Config.arranged` (`SearchDomain.arrangedResults`,
    `arrangedAfterDrop`, `ArrangedOrderTest`) while the setting is on; not a drop-down's options nor the actions
    section. Its background is the menu's (`CalColors.menuBackground`), not the windows'. **It is built by the app's one
    builder of a Search window drawn inside something** (anomaly 2026-11-19: *"only the background color is the only
    difference in the logic"*; `App.embeddedSearchOf`, `LateralMenu`'s `search`, `EmbeddedSearchRequest.menuList`,
    `LateralMenuIsASearchWindowTest`): the account's state, the rows' openers, the actions' handlers, its gestures
    committed — it had been drawn over an empty account with openers and handlers that did nothing. **Its list is its
    items, then what its own search finds** (a type checked in its ▾ lists that type's elements under the items, opened,
    selected, expanded and added as in any window); its items are no elements and are never added. Its
    configuration is kept by `App` as any Search window's (`searchConfigs[LATERAL_MENU_ID]`, its placement row, its
    History Units), its ▾ windows are named after it (`LateralMenu▾`), and "State in each window" lists it. Which of
    its parts are shown is not kept across a restart.
  - **Every row of the menu has an item's right-click, from one list** (`menuItemActions`, anomaly 2026-11-19: the page
    selector showed the background's): the page selector's greys Rename, "all pages" (it is on every page) and Remove.
  - **The window a button opened is kept across a restart** (`menuButtonWindows`, kept on the `MenuButtonWindows`
    placement row, anomaly 2026-11-19: after a restart it was found by the tab's name alone, which the renamed "Task
    trees" button no longer matched, and "Update" was not offered). The tab's name is still the fallback. **So is the layout
    the window had when its button opened it** (`menuButtonOpenedLayouts`, `MenuButtonOpenedLayouts` row): what a button
    keeping no layout of its own is held against — lost at a restart, "Update" was offered for a window not changed.
  - **A button's right-click "Update" saves its window's state as it stands** (user rule 2026-10-07;
    `CustomMenuButtons.needsUpdate` / `updated`, `CustomMenuButtonsTest`): offered only while the window THE BUTTON
    opened or brought back is open (`App`'s `menuButtonWindows`, Compose-only — forgotten when that window closes;
    after a restart, `menuButtonWindowOf` finds it again as the open window of the button's kind that still carries
    the button's name on its tab, `WindowFrameHost.tabTitles`) and is no longer what the button holds. The state is the configuration (`config`: for a Search
    window its query, kinds, filters, sorts AND added elements, by id — `SearchDomain.sameSearchWindowState`, through
    `App.sameWindowState`, user rule 2026-11-05: the bring-back's 2026-11-04 search configurations had left the added
    elements out of the Update too) and the **layout** (`CustomMenuButton.layout`, a
    `WindowLayout`: the window's offset and size, and the lines between its sections — the Search window's two
    shares, `SearchSplits`; the calendar's configuration width — **and which sections are retracted to their arrow**,
    `WindowLayout.hidden`, anomaly 2026-10-08; **and where its lists are scrolled**, `WindowLayout.scrolls`, user rule
    2026-11-05: the Search window's results and added elements, kept with its sections and put back by the button). A
    button that never kept a layout is read against
    the one its window had when the button opened it, so nothing is offered for a window left as it opened; where
    that is not known either (the window came back at a restart), "Update" IS offered — there is a state to save. A NEW
    window a button opens is put back as its layout says (its lines at once, its place once it has a frame); one
    only brought back is left where it is. **Which window the click brings back is the button's "match the whole
    state"** (user rule 2026-11-05, `CustomMenuButton.wholeState`, a check in its right-click menu; on by default for
    a button the user made, off for a default one, `CustomMenuButtons.wholeStateOn`): on, only a window that is ALL the
    button keeps — the same test as Update's, `CustomMenuButtons.isSameWindow` (a window put back as far as the app let
    it, as it was when the button opened it, still is); off, by its search configurations alone (below). Either way
    the whole state is kept and Update offered on any change.
    **The sections of an OPEN window survive a restart** (anomaly 2026-10-08): the lines and the retracted sections
    are local view state kept on the `WindowSections` placement row (`CustomMenuButtons.encodeWindowSections`,
    frame id -> `WindowSections`), written at every change and read back at startup for the windows that come
    back. They used to live in memory only: a restart put every line back in the middle, and an "Update" after it
    kept the middle. A window that closes drops its entry, so the next window under that id opens as new.
  - **The key follows what the window shows**: the alarm, timer and reminder windows move on to the element their
    "+ New …" made (`ObjectWindows.Window.retarget`), and the registration's `menuKey` is kept current
    (`WindowFrameHost.rekey`), so a ☆ pressed after a "+ New …" saves the element shown now.
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
- **The calendar window's two sections — the grid and the configuration — are the Search window's** (user rule
  2026-10-07; `CalendarFloatingWindow`): each is retracted to its arrow and expanded again by it (`SectionArrow`; the
  grid's is in its top left corner, the configuration's before its title; each TITLE — the month label, "Configuration" —
  retracts its section too, `togglesSection`), and the line between them is dragged
  (`SectionSeparator`), up to the edges — **also beside a retracted section** (anomaly 2026-10-21): the drag brings the
  section back at the width it gives it (`CalendarSectionsTest`). The width is read at layout (`sectionWidth`) and told
  at the end of the drag, never per step (the line trailed the pointer). Compose-only, like the Search window's splits. **The grid stays composed**
  while it is retracted or squeezed — laid out at `CALENDAR_GRID_MIN_WIDTH` at least and cut (`keepsWidthAbove`): its
  scroll and its zoom are its own, and a week laid out in no width is a case nothing else asks of it. The
  configuration is cut the same way below its own width, and takes the whole window while the grid is retracted.

### The lateral menu

- **The whole lateral menu is the user's, except the page button** (user rule 2026-10-08; `ui/CustomMenuButtons.kt`,
  `LateralMenu`, `CustomMenuButtonsTest`). Under the page button there is ONE list of items (`CustomMenuButton`), and
  an item is a **window's button** (☆, as before) or a **control of the app** (`CustomMenuButton.control`, a
  `MenuControl` by name): a switch, a field, a button that acts.
  - **A control is drawn in the menu by the code that draws it where it lives** (`MenuControlItem`, fed by `App`'s
    `MenuControlHost`): never a second look for the menu. A new control is one entry of `MenuControl`, one branch
    there, and a `MenuAddable` round it where it lives.
  - **Every control is an action of the Search window** (user rule 2026-11-04: *"Every button that can appear in the
    left-side menu are links to actions that can be found in the action section of the Search window"*): each
    `MenuControl` is the action of an app setting (`ACTION_MENU_CONTROLS`), found by its name once that setting is
    added. The 13 that had no action until then (Online, Look away now, the Supabase usage…) are each a setting
    (`AppSettingEntry`) with that ONE action (`APP_SETTING_CONTROL_ACTIONS`), drawn by `MenuControlItem` there too
    (`LocalMenuControlHost`, provided over every window). A new control needs its action (`MenuControlsAreActionsTest`).
  - **What the menu held is what it starts as** (`CustomMenuButtons.DEFAULTS`: the Search button, the three sound
    switches together, Mute, Voice, Notifications (brought together once in a menu stored before), Task trees, History units
    (every history unit), Life context (user rule 2026-11-20: the md element `MdDomain.LIFE_CONTEXT_ID` alone, made at the
    press that finds it missing — those three given once to a menu stored before, above the calendar's), Calendar, Look
    away now, Switch task, Sleep / Work, I'm away; Online is the settings page's since 2026-11-04). A list stored before is given those
    ON TOP, once (`Stored.seeded`); from then on a removal is final, and an emptied menu stays empty.
  - **Every item has the same right-click menu**: Update (a window's button whose window changed), Rename (a window's
    button or a folder only — user rule 2026-11-04, `CustomMenuButtons.canRename`: a control or an action is named after
    what it is), "all pages" (named "Global" until 2026-11-04), "match the whole state" (a window's button only), Remove, Customize, Reset. A control's is asked BEFORE the control (`secondaryPressFirst`: a text field has a menu of
    its own); a right-click beside the items offers Customize and Reset too, at the pointer, so an empty menu can be filled.
  - **"Reset" puts the menu of the page it is pressed on back to that page's defaults**
    (`CustomMenuButtons.withPageReset`, `GlobalItemsTest`), one History Unit, so one `Ctrl+Z` from ANY window brings it
    all back (`persistence.md` § *One history, per-device undo*, as for all the menu does). The other
    pages lose nothing of their own: a global item the defaults do not hold goes back to the page it was made global
    from, as that page's own, or goes altogether when that is this page. The defaults' global items are the same item
    on every page, so one renamed or removed is restored on all of them.
  - **A global item's Search window opens on the page it is pressed on** (anomaly 2026-11-04,
    `CustomMenuButtons.globalSearchConfig`): a Search window is drawn on its configuration's page alone, so the
    configuration a button kept on another page is opened with this page's; the global Search button opens the page's
    own Search window where it keeps none of this page's.
  - **"Customize"** (`MenuCustomizer`, `LocalMenuCustomizer`, Compose-only — off at every start): while on, every
    control that can stand in the menu is OUTLINED where it lives, nothing is added to the menu to end it (user rule
    2026-11-01: the right-click menu alone does, as Stop customizing), and
    a right-click on one offers exactly "add in the left-side menu", at the pointer (`MenuAddable`). **The menu's own items do nothing at
    a press meanwhile** (anomaly 2026-10-08: a switch was still flipped): a press HOLDS the item, and moved, DRAGS it
    to another place of the menu (user rule 2026-10-08; `arrangePress`, `CustomMenuButtons.placed` / `dropPlace`) — the
    item follows the hand, a line says where a release would put it, and it lands before the item under it. The page
    button is not an item and does not move. Only their right-click menu answers otherwise; the controls outlined
    elsewhere in the app still work. It lands at the bottom; a control
    stands in the menu once (`addedControl`).
  - **An item dropped on another makes a folder of the two** (user rule 2026-11-01; `CustomMenuButton.folder` / `parent`,
    `CustomMenuButtons.grouped`, `CustomMenuFoldersTest`). Over the middle half of a row a release groups, and the row is
    outlined; at its edges it moves, and the line shows where. A folder's title goes in edit at once. Dropped on a folder
    it joins it. **Between two rows of different depths the item takes the depth of the one it is closer to, and the line
    starts at that depth** (`CustomMenuButtons.dropPlace`): shorter at the deeper one. So the gap under a folder is its
    first item or, closer to the folder, the next thing after what is in it, and the gap under a folder's last item is
    the end of the folder or out of it. **The list stays flat, in tree order**, so every operation on it holds. A folder emptied goes, and
    removing one removes what is in it. Its arrow answers while customizing. **A press on it presses every window button
    in it at any depth** (`windowButtonsIn`), one every `FOLDER_CLICK_FRAMES`, or each click would take the other's new
    window for its own and name its tab. A folder is not Global: its items are, each, and seen from another page such an
    item stands at the top. A thin line per level shows the tree, for the `MAX_MENU_LEVELS` deepest levels of a row: there
    is no limit to the depth, and the levels above are hidden to the left.
  - **An action of the Search window is an item too** (user rule 2026-10-08; `CustomMenuButton.action`,
    `AddableAction`, `MenuActionItem`): every row of "Actions on the added elements" is addable, WITH that window's
    configuration as it stands — its added elements included — so the item acts on those elements. It is drawn in the
    menu by the action's own editor, named after the action and its element ("Duplicate · Apple"), and **greyed and
    deaf to a press while none of its elements is left** (`SearchDomain.actionCanAct`: a deleted task resolves to
    nothing). The three actions that ARE a control (Voice, Notifications, Global volume) are added as that control.
    What `MenuActionItem` needs of `App` is made further down the composition than the menu
    (`menuActionContext`, told at every pass): never copy the handlers up, they embed windows declared there.
  - **What can be added today**: the menu's own seven; every action of a Search window; the calendar's configuration — Display, Auto schedule,
    Reminders, Screen breaks, the time limit, the minimum time weight, the sleep schedule; and an app setting's
    actions — Voice, Notifications, Global volume. NOT yet: "Lock to now" (the calendar window's own state, not the
    app's), and the controls of the other windows. A window is added by its ☆, as before.
  - **Voice and Notifications are app-setting elements** (`SearchDomain.AppSettingEntry`, beside Sound; each row says
    on / off, each has its switch among the actions): the menu can be emptied of them, so they must be findable — and
    addable back — somewhere else. The Notifications switch there writes through the ENGINE as the menu's does
    (`AddedActionHandlers.onSetNotificationsEnabled`).
- **The lateral menu's fixed part has no "Search" and no "Categories" button** (user rule 2026-10-08). The two windows
  are opened by the buttons the user made (☆) and from the windows that lead to them: a Search window from the
  calendar's "add…" / "edit…", from an element's row and from the actions that open one; the Categories window from
  a Search window's "window" rows. With no button of the user's own, a plain Search window has no way in from the
  menu — which is what was asked.
- **The sleep schedule is an ACTION OF THE SLEEP PERIOD** (user rule 2026-10-13: *"put the sleep configurations in
  the sleep period actions instead of the calendar window"*; `SearchDomain.AddedAction.PeriodSleepSchedule`,
  `SleepScheduleFields`). It was a section of the calendar's configuration from 2026-10-07 (when it left the lateral
  menu) until then; that section no longer shows it. The action is the Sleep period's own and no other period's
  (`isSleepPeriod`: listed where that period is added, in ITS group, never in the periods' group). ONE drawing of
  the fields, for the action, for the Sleep window — which the calendar's "edit… → sleep schedule" on a Sleep band
  still opens — and for the lateral menu's item (the action added to the menu IS `MenuControl.SleepSchedule`). A
  field re-reads a value written from another place, never under the hand typing it.

- **There is no life plan** (user rule 2026-11-19, *"Remove Life plan"*, its data included): its window, its menu
  button (dropped from a menu stored before, `CustomMenuButtons.REMOVED_WINDOWS`), its state and its units are gone
  (`LifePlanRemovedTest`). It was a lateral-menu window of the Schedule page from 2026-11-02.

### Maximized, resized, filled

- **Dragging a MAXIMIZED window's head un-maximizes it under the pointer** (user rule 2026-10-01;
  `WindowFrameState.unmaximizeUnder`): the window takes back its normal size — its default one if it never had
  one — with the pointer at the same fraction along the head and the head under it, and the drag carries it on.
  It happens once the drag passes the touch slop (the one exception to "no dead zone": a maximized window cannot
  move before it anyway), so a double-click's tremor never drags it out. A window filling ONE axis is not
  un-filled by a drag; it moves along the other.
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
- **That default is its CONTEXT's size, read through one funnel** (user rule 2026-11-27; `ContextDomain.sizeOf`,
  `fitted`, `AppWindowFrame`'s `sizeContext`, `frameSized`, `contextSize`). A window that is no Search window, and a
  pop-up of a fixed size, opens under a name of `ContextDomain.FRAMES` (`ContextCatalogTest` finds every one); a Search
  window under its own context, else a Search window's. The size is in dp, each side cut to its share of the room the
  app gives it (the whole room by default), never under the frame's least. **Never write a size at a call site**: it
  could not be seen or set as the context's ("Size", synced). What the user gives a window by resizing it stays
  local to the device and stands over the context's; the head's "Use this size as the default here" writes it there.

### Menus on the frame

- **EVERY MENU GOES THROUGH `AppDropdownMenu`, AND ON THE DESKTOP IT IS AN OS WINDOW OF ITS OWN, OWNED BY THE APP'S
  — NEVER ALWAYS ON TOP** (user rule 2026-11-15: *"I want the search windows to never appear above what is supposed to
  be above the app (like the Firefox's picture-in-picture)"*; `ui/AppDropdownMenu.kt`,
  `jvmMain/.../AppDropdownMenu.jvm.kt`). It stands above the app and under whatever stands above the app. It took back
  the user rule of 2026-10-13 (*"Any right-click menus must appear above all, even above Firefox's picture-in-picture
  window"*), which had made every menu always on top. Being a window of its own, it is free of the app window's edges:
  **never call material's `DropdownMenu` directly**, which is bound by them. `AppDropdownMenu` has its parameters and
  its anchoring (the layout it is declared in, `offset`, kept on the screen's work area). On the desktop it is a
  modeless, undecorated dialog owned by the window it was opened in, focusable only where the menu is; elsewhere it IS
  material's menu. A text field's own cut / copy / paste menu is one too
  (`OwnWindowContextMenuRepresentation`, set as `LocalContextMenuRepresentation` by the desktop's `main`).
  - **Nothing of the menu rules changes**: a press in a menu never reaches the root observer, a press outside it in
    the app does and still lands, a non-focusable menu leaves the keyboard where it was. What is new is the press in
    ANOTHER application, which a window of its own would otherwise outlive: the owner window losing the foreground
    closes it (a focusable menu: the menu losing the focus), a menu opened from the menu excepted — and a PINNED pop-up
    (below) excepted.
  - **The window is sized and placed from a task of its own on the event queue, never from inside a layout or a
    draw.** See the point on the two answers taken back.
  - **A menu opens BESIDE what it is about, never over it: below, else above, else at the right, else at the left**
    (user rule 2026-10-14: *"The right-click menu is hiding the field, even though there are spaces at the bottom. If
    there is no space below, it must be at the top, then at the right, then at the left. Check anywhere there is a
    right-click menu or drop-down menu."*; `menuSpot`, called by `menuPlacement`). The first side the WHOLE menu fits
    on, inside the work area of the SCREEN the anchor is on — the menu is a window of its own, so a field near the
    bottom of a small app window still has its menu below it. Along that side it slides to stay on the screen; only
    where no side has room does it cover the anchor. The anchor is the layout the menu is declared in
    (`parentCoordinates`, as `Popup` reads it), moved by the offset (the window bar's menu: the bar, at the pointer's
    x), or the pointer itself (`atPointer`) — which the calendar's two menus now use, being declared on a whole day
    column. This REPLACED the port of material's `DropdownMenuPositionProvider` (2026-10-13), whose fallback centred
    the menu on its anchor's top edge and hid the field. The global task picker (`TaskPickerOverlay`) opens by the
    same rule beside the pointer. Phones and the browser keep material's own menu and placement.
  - **No menu or pop-up appears outside the app** (user rule 2026-11-16: *"No window must appear outside the app."*;
    `appArea` in `AppDropdownMenu.jvm.kt`). The room `menuSpot` places in is the APP's window — the content of the
    window at the root of the menus it hangs from, cut to the screen's work area — and the menu window is never sized
    larger than it. This takes back "free of the app window's edges" above, for the edges only: a menu is still a window
    of its own (its z-order, its keyboard). The global task picker alone stays at the pointer, on the screen.
  - **A pop-up opened from a row never hides the list of rows its caller is in** (user rule 2026-11-16: *"When a row
    makes a window appear, it must never hide the list of rows the caller is in. The positions below and above are only
    possible when it doesn't hide."*; `LocalCallerList`, `menuSpot`'s `list`). A list gives its shown bounds — the
    Search window's result list, a column of the ▾ tree — and a pop-up opened inside it (a field's or a button's, not a
    right-click's: that one is a question about one row, at the pointer) stands off the LIST at the left or the right,
    at the row's height; below or above the list, never below or above the row inside it. A shown pop-up that would
    hide the list is placed again (`keepsSpot`). The same rule places a field's window drawn in the app
    (`fieldPopupBounds`: "List of parents", "Add a parent"). A menu's own content is in no list of the caller's.
    **The ▾ configurations tree is the exception** (user rule 2026-11-16, *"Yes, next to the ▾"*): it hangs next to its
    arrow even over the list (`ConfigurationTreeArrow` provides no list). Opened by a hover, it must be reached by the
    pointer crossing straight onto it; off the list, it stood across the whole list from the arrow and closed first.
  - **…and it is STUCK TO ITS ROW** (user rule 2026-11-21: *"Make sure every pop-up window that appears from a caller is
    stuck to the caller. Here, the caller is the row, so if it is the last row the window can appear on the left, the
    right or the bottom."*; `menuSpot`, `shownRow`, `stuckTo`, `MenuPlacementTest`). The row is the row **as the list
    shows it** — the list's width at the row's height: a menu's rows are laid wider than the menu, and "at the right of
    the row" stood off their far end, out of view. At its left or right the pop-up keeps the row within its height as it
    slides along. **Of the spots against the row that hold it whole, it takes the one hiding the least of the list**
    (same day: *"Sometimes the window appears on the caller. When the caller is a row, the window that appears must hide
    as little as possible the other rows."*): at the row's left or right (none hidden), below the last row or above the
    first (none), else below or above a middle row, whichever hides fewer rows — the sides' order breaking ties. This
    takes back the 2026-11-16 "below or above the LIST" for a row's pop-up: that spot stood away from the row, and where
    no spot held the pop-up the last resort slid it back over the row. `keepsSpot` never keeps it on the row as shown. **A pop-up opened by a hover inside
    a menu is never that menu's right-click** (`RightPresses.window === owner`): opened within 750 ms of the right-click,
    with the pointer already on its row, it was placed as a right-click's menu — no list, not stuck.
  - **…and it is MADE SMALLER rather than hide its caller's rows, the left and the top first** (user rule 2026-11-21:
    *"Make sure the pop-up window is stuck to the caller (a row or a button). Its position and sizes must be adjusted for
    the best. It must avoid as much as possible to hide the rows in the same section as the caller, even if it means
    reducing the size of the pop-up window. It must prioritize appearing to the left and to the top (I often work with a
    video playing in a picture-in-picture window on the bottom right corner of my laptop screen)."*; `menuFit`,
    `MenuSession.shrunkTo`, `MenuSurface`'s cap, `MenuPlacementTest`). Every pop-up that is not a right-click's menu is
    placed by `menuFit`: whole where `menuSpot`'s spot holds it stuck and hiding no row; else shrunk on a side of its
    caller hiding no row (a row's left or right as the list shows it, above the first row, below the last; any side of
    a field or a button), the spot keeping the most of it, the sides' order breaking ties; never under
    `POPUP_MIN_WIDTH` × `POPUP_MIN_HEIGHT` (240 × 160), below which it keeps its size and hides the fewest rows. The window
    is shown at the shrunk size and its content LAID OUT within it (it scrolls or narrows, never cut); the size it wants
    is kept apart (`size`), so a pop-up placed afresh (the app moved or resized) grows back. **The left and the top
    first**: `MenuSide.SUBMENU` is left, above, right, below (it was left, right, below, above, 2026-11-04); beside its
    row a pop-up reaches UP from it (its bottom on the row's bottom), down only where the room's top stops it; above or
    below its caller it reaches left. `MenuSide.POPUP` already led with the left, then above. A right-click's menu keeps
    `DEFAULT` (below first, 2026-10-14) at its own size.
  - **…and NEVER leaves its caller, a right-click's menu included** (user rule 2026-11-27: *"Make sure the window appears
    only stuck to the caller. Make sure of that in the whole app."*; `menuFit`, `menuPlacement`'s `stuck` always,
    `fieldPopupBounds`, `MenuPlacementTest.a_pop_up_is_always_stuck_to_its_caller`): where no spot above holds it,
    `menuFit` takes its whole spot stuck to the caller over the fewest rows, else shrinks it against its caller over the
    fewest rows (above or below a middle row included), else as large as a side holds it. Its last resort used to be
    `menuSpot`'s fallback, pushed back onto the screen off its caller; a right-click's menu was placed by `menuSpot` alone,
    and a field's window was pushed into the room after placing. A right-click's menu keeps its own size wherever a side
    of its caller holds it.
  - **In the Search window a ROW's right-click menu opens AT THE POINTER** (user rule 2026-10-13: *"for the
    elements in the result list of the Search window, the menu still appears at the left of the element instead of
    the mouse position"*; `AppDropdownMenu`'s `atPointer`, `LocalRowMenusAtPointer`, provided by `SearchWindow`).
    Its rows run the whole width of a list, so "under the row, at its left" — where material puts the menu of a
    layout, and where it always was: seen side by side on a task cell — is far from the hand. It reaches the three
    row menus: a result's, an added element's, and the task rows borrowed from the tree (`TaskRow` reads the
    local). The tree's own cells keep their menu under the cell. **The pointer is read ONCE, as the menu opens**
    (anomaly 2026-10-14: *"appears, then teleports to the new position of the mouse"*): the anchor's layout runs
    again while the menu is open (a hover, a scroll, the list recomposing), and reading the pointer there moved the
    open menu to wherever the hand had gone. Desktop only (a finger has no pointer); a dev run's
    `-Pomniapp.menusAtPointer=true` forces it everywhere, which is how it was seen.
  - **A menu opens in as few turns of the event queue as there are** (same anomaly: *"the right-click menu is slow
    to appear"*; `MenuSession`). Plain code, not Compose's `DialogWindow`, which took half a dozen turns — each
    waiting for a frame of an app window with work of its own. The anchor is known in one turn; the window is shown
    in the next at the size the same menu had last time (`MenuWindows.lastSize`, by call site), and the first time
    it is measured out of sight and shown once fitted — never seen at a wrong size, nor moved under the eye.
  - **A menu's window is KEPT and used again, never made per menu** (anomaly 2026-10-13: *"it takes a moment for
    the menu to appear after the right-click"*; `MenuWindows`). Making an OS window with its Direct3D surface was
    the moment. Each window that opens menus keeps ONE dialog — hidden and emptied between two menus, handed to the
    next — and the app's own is made ahead by `main` (`warmMenuWindows`). Timed on a dev run, throwaway account: the
    window is up about 35 ms after the press for a menu opened before (about 110 ms the first time), and it is on
    screen as soon as material's own menu is (about 110 ms either way, as a screen capture sees it).
  - **Two answers were shipped that day and taken back — do not put either back.** Holding the app's WINDOW
    always-on-top while a menu is open hid the other application's window (*"the pip disappears"*).
    `compose.layers.type=WINDOW` (Compose's own "every `Popup` is a window") **crashed the release app at the first
    menu**: in Compose 1.11.0 `WindowComposeSceneLayer.onDrawBoundsChanged` resizes the layer's window from inside
    its own draw (`setSize` → `SkiaLayer.reshape` → `Direct3DRedrawer.renderImmediately` → the draw again) without
    end, and the JVM dies in `skiko-windows-x64.dll` (`EXCEPTION_ACCESS_VIOLATION`). The release leaves no trace of
    it — no `diagnostics.log` line, no event; the `hs_err_pid*.log` is in the folder the app was started from. A dev
    run can still try a layer type: `-Pcompose.layers.type=…` (`desktopApp/build.gradle.kts`).
  - **A menu opened inside a menu hangs from THAT menu's window** (`LocalMenuWindow`, provided by the menu's surface),
    never the active window, which may be a sibling menu about to close. **A menu opened in the app's window hangs from
    that window** (`LocalAppWindow`, given by the desktop's `main`; anomaly 2026-11-04: after the configurations tree
    vanished on a hover out, the active window was one of its hidden menus, and the next tree opened elsewhere at every
    try). A focusable menu closed while it holds the keyboard gives it back to the nearest window still on screen. The sides it tries are its call site's
    (`LocalMenuSides`, `menuSpot`'s `sides`; the configurations tree's children: left, right, below, above), a menu's
    own content being given back the default.
  - **A hover pop-up vanishes once the pointer has left it and all of its descendants** (`MenuHoverTracker`,
    `LocalMenuHover`: the configurations tree's), the menus opened inside it being its descendants — a category's
    child pop-up and the arrow in a pop-up's bar each a branch of the pop-up they stand in (`parent`).
    **A menu entry that shows a pop-up when hovered goes through `HoverPopupRow`** (user rule 2026-11-21,
    `MenuAction.hoverPopup`: the calendar's "add…" and "edit…") — the category row's tracker, opener and sides, never a
    hover handler of its own. **Every row that opens a pop-up when hovered wears the arrow** (user rule 2026-11-21: *"When
    a row shows a window when hovered over, there must be an arrow indicating it."*; `HoverPopupArrow`, the one drawing):
    a tree's category at its end, a menu entry before its name (the option row's `leading` mark) — the menu's rows are
    laid wider than the menu, and an arrow at their far end stood out of view (`MenuHoverPopupTest`).
    **Only the ARROW opens it, and the arrow is its caller** (user rule 2026-11-27: *"Rows that open a window when
    hovered over must now open it only when the arrow is hovered over. The caller is the arrow, so be careful that the
    opened window always appears sticking to the caller."*; `HoverPopupCaller`, `LocalHoverPopupCaller`): the row hands
    its `HoverPopupArrow` the hover, the hover branch and the pop-up, which is composed inside the arrow — so the
    desktop's menu anchors on the arrow — with no caller list (as the ▾'s tree), so `menuFit` places it against the
    arrow itself, even over the list. A hover of the rest of the row opens nothing; a click on the row still opens it
    pinned (a finger's way). A row drawing no arrow (`opensOnHover` false: the blank child row) hangs it from itself,
    off its list, as before.
  - **A PRESS ON WHAT OPENS A POP-UP IS ITS CLICK'S TO ANSWER — never the outside press's, never the keyboard's loss**
    (anomaly 2026-11-20: *"Often when I rapidly click on a down arrow, it appears and disappears in a blink. It also
    happens for rows that show a window when hovering over it."*; `ui/PopupOpener.kt`, `PopupOpenerTest`,
    `PopupRapidClickTest`). Every control that opens a pop-up — the ▾, a category's row, a drop-down's field
    (`menuToggleClickable`) — is a `PopupOpener` (`Modifier.popupOpener`), provided to its menu as `LocalPopupOpener`
    (`OpenedBy`), reset inside the menu. A press on it while its pop-up is open was answered twice: by what closes a
    pop-up on a press outside it, and by the control's click — in either order, AWT reporting the keyboard's move to the
    control's window before the click or after it. After a click that KEPT the pop-up (a hover pop-up pinned, a
    category's pop-up clicked), the late report closed it; the 2026-11-19 fix (`LATE_PRESS_REPORT`) covered the tracker
    alone, while the menu window's own focus listener still closed it. Now ONE funnel:
    - the root observer leaves that control's menus alone (`PopupOpeners.pressAnsweredBy`, `TransientMenuHost`), and so
      does Material's own outside-press dismissal where the desktop draws a menu in the scene (no window: the tests,
      `-Domniapp.menuWindows=false`);
    - **every way the desktop's menu window hears the keyboard leave it** — its loss, another window active, its hover
      tracker told — goes through `MenuSession.keyboardLeft` → `PopupOpeners.keyboardLeft`: the pointer on its opener,
      the loss waits for the click; the click keeping the pop-up gives it the keyboard back, the click closing it drops
      the loss, and no click ([PopupOpener.SAME_GESTURE], 500 ms) or the pointer leaving the opener applies it as ever.
      A loss reported after the click is answered as that click was;
    - **a click within 500 ms of the last is the same gesture** (`PopupOpener.click`): a double click, or any run of
      quick clicks, leaves the pop-up as the first click left it.
    What a click does is the opener's to say: the ▾ opens a closed tree pinned, pins a hover one, closes a pinned one
    (the toggle); a category's row opens and pins its pop-up; a drop-down's field toggles. `takeClosedPinned` and
    `LATE_PRESS_REPORT` are gone with it. **A new control that opens a pop-up goes through `PopupOpener`**, never a
    hand-rolled toggle or a loss handler of its own.
  - **A press in a hover pop-up pins it and its ancestors** (user rule 2026-11-11, `MenuHoverTracker.pin`, read by
    `reportsHover`): the pointer leaving no longer closes them; the keyboard going to a window outside the tree does.
    **User rule 2026-11-14** (*"Clicking, right-clicking or typing pins the currently focused Search windows (clicking or
    right-clicking the button that makes the window appear also pins it)"*; `MenuHoverTrackerTest`): a key typed in it
    pins it too (the menu window's key handler), and so does a press of either button on what opens it — the ▾, a
    category's row — which opens it again, pinned, where that press had just closed it as a hover pop-up (the keyboard
    gone to the window the button stands in) — since 2026-11-20 the press does not close it at all (above). **The ▾'s own press pins nothing — its click (or right-click) does**
    (anomaly 2026-11-16, *"when I click on the down arrow, it disappears instead of getting pinned"*;
    `reportsHover(pinOnPress = false)`): the press reaches Compose before AWT tells the hover pop-up its keyboard went
    to the app's window, so a pin on the press made that loss read as a pinned pop-up left by a press outside — closed,
    and `takeClosedPinned` kept it closed. Pinning changes nothing but these two things:
    - **No hover opens or closes a pop-up outside the pinned one holding the keyboard**
      (`MenuHoverTracker.hoverActs`, asked by every `onHoverEnter` of the tree: the ▾, a category's row, an end node
      closing its neighbour's child) — only what stands in it or in its descendants answers a hover; a fresh hover
      pop-up opened from it holds the keyboard unpinned, and the pinned one above it still decides.
    - **A press anywhere else in the app closes it AND every pop-up it hangs from** (`MenuHoverTracker.windowLeft`, told
      by the menu window losing the keyboard to a window that is none of the tracker's own nor owned by one,
      `MenuHoverTracker.windows`): a press in one of its ancestors closes that ancestor too — until then the ancestor
      stayed. Not pinned, as before: its own menu leaves, its ancestors stay. A walk through the positions to an
      ancestor (`takeKeyboard`) closes what is below that ancestor alone. The button that opens a pinned pop-up is
      outside it: pressed, the pop-up closes and stays closed (`takeClosedPinned`).
    - **The keyboard going to a pop-up this one hangs from closes what is below that one, never it** (anomaly
      2026-11-15: *"I go to down arrow then click on 'task', but instead of pinning the two pop-up window, it closes the
      pop-up window"*; `MenuHoverTracker.windowLeft(goneTo)`): the press on a category's row pins the row's branch, and
      the keyboard leaving the branch's pop-up for the row's had closed the whole tree. The row's click opens the branch
      again, pinned.
    - **The keyboard coming back to the pop-up's OWN window closes nothing of it** (anomaly 2026-11-21: *"I go to down
      arrow, then in 'Currently on'. In this window A I click on the type selector field, then I click again on A, and A
      disappears"*): a drop-down opened inside a pop-up reports to its tracker, and the press back in the pop-up that
      closed it made the pop-up's window the active one; `windowLeft(goneTo)` read that against the ancestors alone and
      closed the pop-up. It reads the tracker itself first. `MenuHoverTrackerTest`.
    The keyboard's place is ONE fact of the app (`MenuHoverTracker.focused`), cleared as a pop-up closes or leaves the
    composition — a tracker left "focused and pinned" would hold every hover of the app back.
    A menu that holds the keyboard stays only while a window of ITS tree is the active one (`activeListener`, once it
    has held the keyboard). Closed, it is a hover pop-up again.
    - **Every menu stays while the user is in another application, and is there when they come back without a press in
      the app** (anomaly 2026-11-15, 4: *"I right-clicked, which opened a window, then I clicked outside the app, then
      came back on the app, but the right-click menu disappeared"*; `MenuSession.pinnedAway`, `awayPresses`) — a hover
      pop-up not pinned excepted, which the pointer leaving closes. Coming back by a press in the app is a press outside
      the menu: it closes, as ever.
    - **A pinned pop-up stays while the keyboard is in ANOTHER application** (user rule 2026-11-15: *"when I click on a
      pop-up window to pin it, make sure it doesn't disappear when click outside the app, like dragging the pip away for
      example"*; `MenuSession.pinnedAway`: AWT names no window of the app as the one the keyboard went to). Each way the
      desktop closed it then spares it: the menu or its owner losing the keyboard, the tracker's `windowLeft`, the
      active window changing. Back in the app by a press on another of its windows, it closes as a press elsewhere in
      the app closes it (`activeListener` calls `windowLeft`, which no loss of the keyboard had told it). From 2026-11-11
      to this day, going to another application closed it.
  - **The keyboard going to a pop-up of a ▾ tree is a History Unit, a position like a move of the focus** (user rule
    2026-11-11, folded into the history 2026-11-22: *"fold them in"*; `ui/PopupFocus.kt`, `PopupFocusTest`). Each pop-up
    the keyboard comes to (`MenuHoverTracker.windowFocused`, told by the desktop menu window) commits an
    `ExternalDelta` of key `ExternalKeys.POPUP` in the `WindowNav` list — before: where the keyboard was, after: the
    pop-up's **address** (`PopupAddress`: the outermost arrow's id, `Search#3▾`, and for each tree from the outermost
    down the categories it shows), or null for its window. So `Shift+Alt+←/→` walk them with the selections and the
    focus moves, under the same two rules as every position (`SchedulerReducer.Pin`), they come back after a restart,
    and a new position drops their redo like any other's. `Ctrl+Page Up/Down` walk the windows alone (`Pin.FocusMoves`
    holds `FocusDelta` only). Local to the device, never synced (`Delta.localOnly`, like a bar's keystroke).
    - **Where the keyboard is, is read off the history** (`SchedulerReducer.popupHolder`: the newest applied focus
      move, when it is a pop-up's). A pop-up already holding it records nothing — so a walk bringing one back, whose
      coming there the tree reports like any other, adds no unit; the pop-ups opening on the way to it are filtered by
      `TreeKeeper.focused` (recorded, they would cut the walk off: anomaly 2026-11-11).
    - **A move of the focus to a window first gives the keyboard back from the pop-up** (`reduceFocusWindow` commits
      the pop-up's "Back to the window" unit before its `FocusDelta`): the platform tells the pop-up lost the keyboard
      only after the press in the other window, and recorded then, the order was wrong. So the user's walk (user rule
      2026-11-22: *"History unit (window A) -> down arrow (window Aa) -> history unit (window Ab), then I click on another
      tab (window B) … shift + alt + <-, and it should bring focus to window A … again, it should bring focus to window
      Ab (with window Aa and A open of course)"*): back lands on A, then Ab, then Aa, then A without pop-up.
    - **Walked, a unit is applied by `App`** (`PopupFocus.restore`, from the external restore): to an address, the
      outermost arrow's `TreeKeeper` (registered by its id in `PopupFocus.keepers`) opens every tree along it, showing
      those categories, pinned (`TreeKeeper.wanted`), and the pop-up at its end takes the keyboard; to null, the
      keyboard goes back to the window (`TreeKeeper.closeAll`), which closes the trees. An arrow not on screen (its
      window closed) brings nothing back. A position walk closes every right-click menu first
      (`TransientMenuHost.closeMenus`).
    - A pop-up holding the keyboard is a window of its own, so it forwards those chords to the app
      (`LocalMenuKeyForward`); `App.walkPositions` is the one answer to them. They are taken from AWT for the pop-up's
      window (`MenuSession.keyDispatcher`), never from its Compose content: a pop-up brought back holds the keyboard with
      nothing of it focused, and then no key reaches its content nor the window's own key handler (measured
      2026-11-11). A window is given the keyboard through what held it last in it (`giveKeyboard`), never
      `requestFocus()` on the window itself: that made the bare frame the focus owner (measured 2026-11-11).
    - It replaced `PopupPositions` (2026-11-11 → 2026-11-22), a second timeline kept in memory beside the history,
      walked before it and joined to it by stamps: lost at a restart, outside the pins' rules, and thrown away by any
      newer position until it was keyed by place.
  - **A pop-up is placed while it opens, then NEVER MOVES** (anomaly 2026-11-27: *"in down arrow > types > types field >
    I click on one check box and the window moves. The window must not move, even if the caller doesn't stick anymore to
    the new window, which must be verified only when the window opens."*; `MenuSession.placing`, `keptInPlace`,
    `MenuPlacementTest`). For its first `SETTLE_NANOS` (600 ms) its anchor moving and its content measuring place it
    against its caller (`menuFit`, `reStuck`) — that is where "stuck to its caller" is checked. After, its anchor moving
    does nothing and a change of its content's size keeps its top-left where it stands, its size cut only where it would
    leave the app. A pop-up it hangs from changing size moves nothing; one moving moves it (that one moves only with the
    app). **The app's window moving or resized places every pop-up afresh and opens that placing time again**
    (`ownerMoves`, user rule 2026-11-15). Until this day every anchor change placed it afresh and every size change re-
    checked it against its caller: ticking a kind added "Currently on" to the ▾ tree, which moved the tree, the Types
    pop-up hanging from it, and the drop-down. The two bullets below now hold during the placing time alone.
  - **An open menu that changes size stays where it is** while it fits on the screen and keeps off its anchor
    (`keepsSpot`, anomaly 2026-11-11): placed again, it slid from under the pointer, which then closed the tree. **A
    pop-up (not a right-click's menu) stays only while it still touches its caller** (user rule 2026-11-19: *"Make sure
    every window that appears by hovering over something, the caller, is always stuck to the caller."*; `stuckTo`,
    `keepsSpot`'s `stuck`): one of its sides on the caller's, or on the edge of the caller's list at the row's height. A
    pop-up at a caller's left that grew narrower left a gap; it is placed again against it — **by the edge that touched
    its caller, the rest growing or shrinking away** (`reStuck`, in its first moments too; anomaly 2026-11-19: placed
    afresh, a ▾ pop-up moved from under the pointer that had just reached it, which closed it). Afresh only where that
    spot is off the screen or over the caller.
  - **The ▾ of a window that appears stands on its side furthest from its caller, and its tree opens on that side
    first** (user rule 2026-11-19: *"I want it for the down arrow of the appearing window"*; `farSideOf`,
    `LocalPopupFarSide` given by the desktop's `MenuSession` as it places the pop-up, `arrowTreeSides`): before the bar
    in a pop-up at its caller's left, after it at its right. Where no pop-up says it (a window of the app, a phone's
    material menu), at the bar's right as ever. **Not a row's hover arrow**: asked the same day and cancelled, it stays
    at the row's end, its pop-up on `MenuSide.SUBMENU`'s sides.
  - **A pop-up opened from a field or a button opens at its left hanging down, else above reaching left, else below
    reaching left, else at its right hanging down** (user rule 2026-11-15: *"A pop-up window opened from a field/button
    must adjust its position on four choices …, depending on the available space, then on its size, when the pop-up
    appears but also as the app gets resized"*; its order the same day, last: *"the right side to the top, then bottom
    side to the right, then top side to the right, then left side to the top"* — the pop-up's side stuck to the field
    and where on it the field is; `MenuSide.POPUP` = `LeftTop, AboveEnd, BelowEnd, RightTop`). Each spot holds it whole
    where it stands, unslid, or is passed over. Told by the press that opened it (`RightPresses.justPressed`, an AWT listener that
    hears every press before any component): a right-click's menu keeps below, above, right, left; a click, a hover or a
    key opens a pop-up; a call site that names its own sides (`MenuSide.SUBMENU`) keeps them. **Where no side holds it
    whole, it takes the side with the most room for its size** on both axes (off the anchor for its depth, the screen
    along the side for its length), slid along that side as little as it must — no longer below over its anchor.
  - **The window a menu hangs from moving or changing size places it again, afresh** (same rule, `MenuSession.ownerMoves`):
    its anchor is said in that window, so a move alone reported nothing and the menu stayed behind.
  - **Nothing reacts to the other applications' windows** (user rule 2026-11-15: *"Revert from all those adjustments to
    outside windows"*): the same day a menu kept off Firefox's picture-in-picture by its four spots, then by being made
    shorter, and the app's windows moved and shrank away from every window over the app as they opened. All of it was
    taken back for the one rule that answers the anomaly: a menu is not always on top (above).
  - **A right-click in the app's window while a menu is open is the app's to answer, never the menu window's loss of the
    keyboard** (anomaly 2026-11-21: *"the right-click must work, even if the right-click menu is already open"*;
    `MenuSession.rightPressedInApp`). The root observer closes the open menus on that press and what was right-clicked
    opens its menu, in the same event; the keyboard's loss is told after it, and closing the menu then closed the one just
    opened (the calendar's: one menu, its anchor moved). A loss to the app's window after a right press there since the
    menu opened does nothing.
  - **A right-click outside an open right-click menu that leaves it open opens it AFRESH** (user rule 2026-11-15:
    *"Right-click must open the right-click menu even if a right-click menu was already open."*; `HangingMenu`, keyed on
    `RightPresses.count`): the same row's again, another row sharing the one menu, a row of the pop-up it hangs from — a
    new session, at the new pointer. It stood where the first right-click had put it. A right-click inside the menu or a
    menu opened from it is the menu's own.
  - **A ▾ pop-up dragged by its head becomes its Search window's configurations window** (user rule 2026-11-15: *"I
    click on show the header for a pop-up window, but it doesn't show the usual header with the buttons to save,
    duplicate, resize… When the user does this, they can drag it somewhere. When a pop-up window gets the position of
    the side that is stuck to the caller change, it gets shown in the [window bar] and all its children windows
    disappear."*; `PopupDetach.kt`, `SearchPopupHead`). Its head, shown, wears the window buttons — duplicate, fill the
    width, fill the height, reduce, maximize, close. A drag of the head closes its child pop-ups, carries its own
    window (`PopupWindowControl`, the desktop's `MenuSession.control`: read off the screen, never re-placed by its
    anchor again) and, let go, closes it for **the Search configurations window** of the Search window its tree is of — opened or
    brought back there, at its size (`WindowFrameHost.placeAt`), so in the window bar. **That window IS the pop-up**
    (anomaly 2026-11-22: *"the window dragged becomes something else … a layout that was supposed to be obsolete"*):
    `ConfigurationTreeWindow` draws it as the pop-up is drawn — the same Search window over the tree, its search the
    ▾'s own (`rememberArrowSearch`), its columns and category pop-ups — in a frame. The window by group and type it
    opened (`ConfigurationSearchWindow`) is deleted. Each other window
    button does the same, then fills, reduces, maximizes or duplicates that window. Only the outermost tree of a Search
    window of the app and its category pop-ups (`PopupDetacher.canDetach`): a tree in a pop-up's bar is a configuration
    of another search, and a menu drawn in the app's window (no `PopupWindowControl`) keeps the title-and-✕ head.
  - **A field's Search pop-up becomes a Search window the same way** (anomaly 2026-11-15: *"clicking the list of parents
    opens a window, where I select 'show the header', but the header is not the usual header"*; then *"not the same as
    the header of the default search window"*): **a pop-up's head IS a window's head** — `WindowHead`, the one drawing,
    its drag and buttons going through `HeadActions` (a frame's `frameHeadActions`, a pop-up's `PopupWindowHeadBar`:
    each makes the pop-up its window first, its "Rename" naming that window, its ☆ adding that window's button). Never
    draw a head of its own beside it. **Every Search window drawn in no frame has a window form** (anomaly 2026-11-25,
    *"the header doesn't have the buttons to maximize or save the window … when I drag it it disappear. This means that
    the app is not unified enough"*, `EveryPopupHeadTest`): where its host gives no head, `SearchWindow` builds the one
    head itself (`ownPopupHead`) — a menu's or drop-down's list, a cell's picker, a Search window inside another's
    actions — becoming a Search window of the app on its configuration (`PopupDetacher.detachSearch`), a menu's carrying
    the entries it was given (`detachGiven`: the window lists them while the app runs). Its own OS window
    (`LocalPopupWindowControl`) is carried by the screen's pointer; one drawn in the app's window by the drag, hidden
    meanwhile. A window's SECTIONS (its actions, its added elements) are that window and never become one — **but an
    actions section whose head is shown drags a COPY** (anomaly 2026-11-26, *"must actually drag an exact copy of B, and
    the real B stays embedded in A"*; `ActionSearchRows.copyOf`): a Search window of the configuration of the window it is
    the section of (its `inside` carrying the section's own), that section alone shown with a head; the section stays
    drawn, nothing closes, and the pop-up it may stand in is not moved. **A pop-up made a window is always a NEW window**
    (`detachSearchPopup`: `matches = { false }`): `openWindowOnItsPage` brings back an open window of the same
    configuration, and the section's copy, its window's configuration, brought back and moved the window itself (seen on
    the desktop 2026-11-26). `SearchPopupHead` hands it its window part, given by whatever hosts the pop-up (`LocalSearchPopupWindow`; the
    Search window drawn in no frame reads it and gives its own sections null). `FieldSearchPopupLayer` gives it: the
    drag moves the pop-up in the layer, the pop-ups over it closing; let go, or asked by a window button, it is a
    Search window of the app (`PopupDetacher.detachSearch`, a copy beside the user's) with the pop-up's configuration.
    Not a picker's ("Add a parent"): its rows pick for its field. The window it becomes opens whole, **its search bar
    shown** even where the pop-up's bar was its field (user rule 2026-11-15). **Where the search bar is what opened the pop-up** (a
    field's pop-up, a cell's picker), its background menu has no "Hide the search bar" (user rule 2026-11-15).
  - **The window a pop-up's head drag makes starts under the hand** (anomaly 2026-11-25, *"the movement of the window
    [is] very glitchy when dragged"*, measured on the desktop): the moves before the drag is known (the slop) and the
    move it is known on are given to `PopupWindowHead.onDragStart` (`travelled`) — they were dropped, and the window
    stayed ~20 px behind the pointer for the whole drag. **The pop-up's own OS window, dragged, turns invisible over every
    screen** (opacity 0.01, never 0: a wholly transparent window is one the pointer goes through) — sent off the screen,
    it let the window under the pointer take the drag's moves (a row there was selected); moved along with the pointer,
    it saw the pointer never move and the drag stopped. Off the screen only where the platform cannot make it so.
    Measured 2026-11-25: hiding it any later than the drag's start — left where it stood, or carried under the pointer
    with the window it becomes — the pointer left it or stopped moving in it, and the drag stopped for good. So it hides
    at once, and for about a frame, until the app draws the window it becomes, nothing stands there. **See-through menu
    windows do not remove that frame** (tried 2026-11-25): a see-through window stretched over every screen keeps its
    content seen, but the area it grows into is wholly transparent until the next frame draws its catcher, the pointer
    goes through it, and the drag stopped for good — as with every other way tried.
    **A menu window is opaque again as it goes back to the pool and as it is taken** (`MenuWindows.shownWhole`; anomaly
    2026-11-25, *"when a pop-up window becomes a window …, the right-click doesn't work anymore anywhere"*): the windows
    are reused, and the next menu opened in the dragged one was all but transparent — every menu after a drag, unseen.
  - **A pop-up's head dragged makes it its window AT ONCE, and the drag carries that window** (anomaly 2026-11-15:
    *"when I dragged a window that previously had no tab in the [window bar], it should immediately get a tab. Right
    now, it waits that I release the click, and it jumps away from where I placed it"*; `PopupWindowHead.onDragStart`,
    `DetachedDrag`). The window opens where the pop-up stood, in the window bar; the pop-up steps out of sight (its OS
    window off screen, a field's pop-up transparent) and only finishes the drag, each move placing the window; let go,
    its geometry is kept and the pop-up closes. Nothing closes a pop-up while its head is dragged
    (`MenuSession.settling`). **A top-left asked of a window is read against ITS OWN container**
    (`WindowFrameState.placeTopLeftAt`, `WindowFrameHost.placeAt`), by the frame at its layout — it was read against
    whatever window was laid out last, whose container need not be the same, and the window jumped.
  - **A pop-up fitted to its content in its first moments is placed for the size it ends at** (anomaly 2026-11-15:
    *"some elements when hovered over open a window that blinks continuously or appear too far to the top"*): while it
    settles (600 ms), a new size places it afresh, not where its first size put it; the ▾ tree's height fit ignores a
    change of 2 px or less; and a menu never takes the keyboard back as it settles — two pop-ups did, from each other,
    without end.
  - **A Search window drawn as a pop-up wears a window's surface, its head at the top edge** (anomaly 2026-11-15: *"only
    a colored band inside the window with a margin"*; `WindowSurface`, `LocalPopupChrome`): ONE surface for a window
    frame and every Search pop-up — a field's, a cell's Edit Mode (its rename / switch task inside, under the head),
    the ▾ tree's, a drop-down's list. Each host had drawn a padded surface of its own, and the head stood inside it. A
    host gives `PopupChrome` and draws nothing around; a menu window holding one shows it bare (`LocalBareMenu`, the
    desktop's `MenuSurface`: no menu surface, no padding).
  - **EVERY menu stays open after its entry is chosen** (user rule 2026-11-15: *"Every menu stay open after you choose an
    entry"*; `OptionSearchMenu`'s `held`, `MenuActsOnReleaseTest`): the entry acts and the menu stays, whatever its caller
    does in the pick — most callers close their own menu there, which the menu outlives — until a press outside it,
    Escape, or its anchor leaving the screen. `MenuAction.keepsOpen` says nothing any more. An entry that gives the
    keyboard to something else (a window it opens, a field) closes it as the keyboard leaving any menu does.
  - **The background menu of a Search window stays open as its entries show and hide parts** (anomaly 2026-11-15: *"the
    right-click menu of the background of the search window disappears when selecting an option"*; `SearchPartsMenu`,
    `MenuAction.keepsOpen`): each entry turns into its other way round, ready for the next part. A press outside it closes
    it, as any menu.
  - **A menu entry acts on the RELEASE, of either button** (anomaly 2026-11-15, 7: *"under Parents, click in 'add a
    parent', right-click, select show the header, and the window disappears"*; `pickOnRelease`,
    `MenuActsOnReleaseTest`): acting on the press, the menu window vanished under a held button and the rest of the
    click reached what was under it — the "add a parent" pop-up's row, which picked and closed it. The 2026-10-15 fix
    had taken the right button alone.
  - **A pop-up answers no press until the click that opened it is over** (anomaly 2026-11-26: *"Right-clicking the row
    Action: Minimum time / Contexts and states opens two windows"*; `deafToTheOpeningClick`, `PopupOpeningClickTest`):
    until it has seen the mouse with no button down, its presses are consumed on the Initial pass. A row's menu is a
    pop-up Search window opening under the pointer while the right button is still held; Compose Desktop turned that held
    button into a press in the new window — no press of AWT's (`RightPresses` heard none; a `[popup]` trace showed the
    pop-up's `SearchPartsMenu` opening 90 ms after the row's menu with no right-click logged) — and the pop-up's
    background opened "Show the header", … beside the row's menu. Not reproducible headlessly: a scene keeps a held
    pointer with the owner it went down in. A finger has no hover: its first press in a pop-up is its own.
  - **The keyboard settling as a menu opens never closes it** (anomaly 2026-11-15, 8: *"Sometimes, even when there is no
    right-click menu open it needs a second right-click to open the menu"*; `MenuSession.settling`): for 600 ms after
    it opens, the menu losing the keyboard or the active window to a window of the app, with no press since, leaves it
    open. Windows activating the app's window for the right press could land after the menu took the keyboard.
  - **A Search window drawn inside another shows no row selected until the user selects one in it** (anomaly
    2026-11-15: *""root" is selected in the search section of the focused window, and "Task: root" is selected in the
    action section"*; `SearchWindow`'s `unselectedUntilChosen`): its sections and a field's window. The row a new question
    starts at is where its keys start (the first ↓ lands on it), not a selection; each section showing its own read as
    several selections in one window. A drop-down's list, the left-side menu and a picker keep theirs: Enter takes it.
  - **A field's window opened by the user is its DEFAULT window** (anomaly 2026-11-15: *"when I click on list of parents
    it must open the default window for this element"*; `PopupSearchField.openFresh`): a click or a letter typed opens
    it with what it opens with (its `openedConfig`, the bar's text kept), its default parts, and nothing of what was
    opened inside it before (`ArrowSearches.forgetInside`, `SearchPartsStore.forgetInside`, `PopupMemory`). Only a
    restart brings one back as it was left (below). **But its added elements list, while its caller is the same** (user
    rule 2026-11-21: *"The 'added elements' list section of a window must be preserved between closing and
    re-appearing, except if the caller changes"*; `SearchDomain.fieldWindowReopened`, `Config.addedFor`,
    `LocalFieldCaller`, `FieldWindowKeepsItsAddedListTest`): the caller of a field in a group of the actions section is
    that group's SET of elements (`fieldCallerOf`) — field F in the group of E1 and E2 is not field F once E3 joins the
    group, whose window opens with no added element of the first. **Lost as its group goes, not when it opens again**
    (anomaly 2026-11-21: the task taken off A and added again, B still held what was added — the same set, but a group
    made again is another caller): as a window's configuration changes (`App`'s `setSearchConfig`, `setSearchInside`),
    every field window in `Config.inside` whose caller is no longer a group of the window it stands in (`callersOf`:
    every element, each kind's, each one's, each kind's default configuration) is dropped with everything opened inside
    it (`SearchDomain.withLostCallersDropped`); the change's History Unit holds what was dropped, so Ctrl+Z brings it
    back. A field whose added elements are its caller's own state, not its window's (the tasks an evolution goes to, the
    categories it carries), says `caller = ""`: never lost.
    **Since 2026-11-27, the WHOLE window while its caller is the same** (user rule: *"The added elements list, the position
    of the section separators, wether the header/search/actions/added elements list is shown or not etc... is preserved if
    the caller is the same. The caller is not the same if it is in a window that got closed, if the caller is an action
    that is not involving the same elements … if not in an action section then the set of involved elements is always
    empty."*; `SearchDomain.fieldWindowReopened`, `sameFieldCaller`): `Config.addedFor` is the caller the window was last
    opened by, written whatever it holds; opened again by it, its configuration and its parts come back as left (the
    2026-11-15 default window above is a NEW caller's). **A pop-up inside a window that closed is another caller's**: a
    Search window of the app closing forgets every pop-up that was open inside it — fields' windows, drop-downs' lists,
    ▾ windows (`SearchDomain.isPopupInside`, `withPopupsClosed`, `App`'s `searchWindowClosed`, in the close's History
    Unit) — and a field's window opened again forgets those that were open inside it, its own sections kept
    (`ArrowSearches.forgetInside`'s `popupsOnly`, `SearchPartsStore.forgetPopupsInside`). A drop-down's list keeps its
    parts by its id (`OptionSearchMenu`'s `rememberKeptParts`), its scroll excepted.
  - **Showing or hiding a section never changes a window's size** (user rule 2026-11-27): a window of the app has the size
    the user gave it, a pop-up Search window a fixed one (a field's, a drop-down's list, a hover pop-up), and the ▾ tree's
    pop-up the one its sections OPENED with (`TreeSearchWindow`'s `rightShown`, remembered): a section shown since is laid
    out within it.
  - **The pop-ups left open come back after a restart, as the windows do** (anomaly 2026-11-15: *"I pinned the window
    …, I restart the app, and all the windows disappeared... Only the first one, the default search window,
    remains"*; `PopupMemory`, the `OpenPopups` placement row, local-only): a field's Search window left open, and a
    configurations tree left PINNED with the categories it showed (brought back as a walk brings it, `TreeKeeper.wanted`,
    pinned along them). What each showed of itself is kept by its id (`rememberKeptParts`: "Show the actions" of a
    "List of parents" included, without which the field inside it would not be there to come back). A pop-up closed
    forgets every one opened inside it (their ids start with its own); those of a window no longer open are dropped as
    the row is read. A hover pop-up not pinned is no pop-up to keep.
  - **A field's pop-up carries the composition locals of where its field stands** (anomaly 2026-11-15: *"I select
    'show the actions' …, find the list of parents field, click it, but it doesn't open a window, even though it should
    use the same code as the first list of parents field"*; `FieldSearchPopupRequest.locals`), as a desktop menu does:
    drawn in the top layer, it had lost what the Search window gives what is inside it, and a field in it opened
    nothing. One open field's pop-up per field, stacked (`FieldSearchPopupHost.requests`), a press in one counting as
    a press in the edits its field stands in (`LocalEnclosingEdits`: the pop-up it was opened from, a cell's Edit Mode).
    What a Search window of the app gives what is inside it is ONE funnel in `App`, `InsideSearchWindow` — the Search
    window and its Added elements configurations window each had a copy, and the second had lost the calendar element.
  - Not menus, and still drawn in the window: the calendar's hover bubble and the shortcut hints (`Popup`).
  - **Every hover bubble is drawn AT THE POINTER and follows it** (user rule 2026-11-20: *"The bubble info must appear
    only at the position of the mouse, even if it is moving."*): `BUBBLE_BELOW_POINTER` (16 dp) under it, at its x — the
    calendar's bubble and `InfoHint` / `ShortcutHint` alike, the one constant (`ShortcutHint.kt`). `InfoHint` hung its
    bubble under the control's left corner, far from a pointer at the other end of a wide row; it now reads the pointer
    on Enter and every Move (`PopupRapidClickTest`). Never anchor a bubble to its control. **A bubble whose element moves goes** (anomaly 2026-11-22: a list scrolled under a resting
    pointer carried a bubble up under it, the bubble took the pointer and stayed): `InfoHint` drops it when its element
    moves in its window or the wheel turns over it.
- **A right-click on a window's HEAD opens its menu, at the pointer** (user rule 2026-10-15: *"The user can modify
  the title of a window by right-clicking on the header of the window. It opens a menu with 'rename', 'remove from
  menu' and one option for each button in the right side of the header of the window."*; `WindowHead`'s `HeadMenu`, a
  menu like the others — non-focusable, `transientMenuDismissal`). **Rename** turns the title into a field, all of it
  selected: Enter or leaving it names the window (`WindowFrameHost.rename` → `tabTitles`, written down by `App` on the
  `TabTitles` row), Escape renames nothing, and a name left empty or equal to the window's own title gives the name back
  — the window follows its title again. **Remove from menu** takes off the lateral menu the button(s) that are this
  window's (`MenuButtonHost.has` / `remove`: the one that opened it or its ☆ made — `menuButtonWindows` — else, after a
  restart, the button it is named after that opens it), and the name that button gave goes with it; greyed where there
  is none. Then one entry per button on the right of the head, in their order: the window's own (`AppWindowFrame`'s
  `headMenu`, for the BUTTONS of its `headTrailing` — the History unit's "Copy all"; a status like "Calculating…" is
  none), ☆, ⧉, the two fills, Reduce, Maximize/Restore, Close. The head's buttons let a right-click through: it is
  still the head's.
- **A menu entry acts on the RELEASE, never on the press — the right button's included** (anomaly 2026-10-15: *"I
  right-clicked on the element type drop-down menu in the Search window, and it opened a menu as if I right-clicked on
  the element in the result list"*; `CheckBoxDropDown`'s right-click pick, `DropDownRightClickTest`). On the desktop a
  menu is a window of its own (`AppDropdownMenu.jvm.kt`): an entry that closes it on the press makes it vanish under a
  button still held, and the rest of the click — a move, the release — goes to the app's window beneath, where Compose
  completes it with the press it never saw and the row there answers it. So an entry consumes the whole click, press to
  release, and acts at the release, as material's own `onClick` does.
- **The calendar's right-click menu is a menu like the others: non-focusable, dismissed by the root observer** (user
  rule 2026-10-11: *"When the user right-clicks on the calendar, then right-clicks elsewhere, it must open the
  right-click menu. Right now, it simply closes the previous one."*). It was the one right-click menu left focusable,
  so it ate the press that closed it. Now the press outside closes it and still lands: a second right-click opens the
  menu where it was made. Its `transientMenuDismissal` is keyed on the anchor — the closing press and the reopening
  one are the same event, so "open" never reads false between two menus.
- **Everything that scrolls vertically shows the one scrollbar** (user rule 2026-10-11 for the drop-downs; anomaly
  2026-11-14, the ▾ tree's columns had none). A plain column scrolls by `verticalScrollWithBar` (`ListScrollbar.kt`),
  never a bare `verticalScroll`; a lazy list stands beside a `ListScrollbar`. Both draw the same thumb (`thumbOf`): at
  the right edge, gone while everything fits, dragged to scroll, the track pressed to page. The column's bar is a
  modifier, not a sibling: a menu measures its entries' intrinsic width, which a `BoxWithConstraints` cannot answer. A
  list leaves `SCROLLBAR_ROOM` at its right so the bar covers no row. `OneScrollbarTest` reads the sources for either.
  (`DropDownScrollbar`, the drop-downs' own drawing, had been unused since they became Search windows; removed.)

### Search bars

- **A search bar keeps its own text and caret** (anomaly 2026-11-15, *"when typing “paren” in the search bar it writes
  “preat”"*, the actions section of a "List of parents"; `EchoedText`, `EchoedTextTest`). A Search window drawn in a layer
  (`FieldSearchPopupLayer`, given its request by a `SideEffect`) hears what its bar typed a frame late, and a field shown
  that stale text set the caret back. What the bar typed, and what it held as the typing began, are echoes and ignored;
  any other text from its owner (the cross, Escape's revert, a configuration put back) replaces it. `SearchBar` is the
  one place: a new bar is one.
- **↑ / ↓ in a Search window's bar walk the bar's own pin** (user rule 2026-11-20, *"only those where the user did
  something else than changing the search bar content right after, like scrolling or clicking elsewhere"*;
  `SearchBarPin`, `SchedulerReducer.searchTextStep` / `SchedulerIntent.WalkSearchText`, `SearchBarPinTest`). Each
  keystroke is a History Unit of the bar's text alone (`ExternalKeys.SEARCH_TEXT`), in **a list of its own**
  (`HistoryCategory.SearchText`; user rule 2026-11-21, *"make them isolated lists. Two pins can't share history
  units"*): no chord walks it, Ctrl+Z and Ctrl+Shift+Z included — they walk the window's other changes — and typing in
  one bar drops only that bar's texts walked back over, never another bar's (each bar its own pin). Keystrokes written
  into the changes' list before move to their own on load (`SchedulerHistories.withSearchTextApart`).
  The pin walks those units only and stops only at a text the user left
  the bar at: the newest, or one followed by a keystroke marked `runStart`. A run ends at **a press or a wheel turn
  anywhere in the app** (`TransientMenuHost.onActivity`, told by the one root observer and by each pop-up's own:
  `SearchBarRunEndTest`), the bar losing the keyboard, any key that does not change the text (`typesInBar`: the caret
  moved, Ctrl+Z, Enter…), and a walk or an undo of the bar. ONE restore per walk, of the text alone, put back while the
  bar still holds what the walk left. At the newest text ↓ goes to the row below, as Enter does; at the oldest ↑ is the
  field's own.
  **Every Search window's bar, one code** (user rule 2026-11-20, *"the left-side menu is supposed to use the same code
  as any other search window"*): the window finds its pin itself (`LocalSearchBarPins`), by its own id
  (`LocalArrowOwner`) and the id of the window storing its configuration (`LocalArrowRoot`) — the bar key
  `ExternalKeys.searchText(root, owner, caller)`. Recorded the same way by both funnels of a configuration:
  `App.setSearchConfig` (the Search window, its copies, the left-side menu, and its actions section's bar, whose text is
  the window's `actionSearch`: anomaly 2026-11-21, `SearchDomain.actionsBarOf`) and `App.setSearchInside` (a ▾ window, a
  list drawn inside a window, an actions section's own actions section; put back through `ArrowSearches.setText`, which
  knows what it opens with). **The pin persists with the bar** (user rule 2026-11-21, *"the same pin persists just like
  the 'added elements' list section"*): kept with the history, it comes back when the window opens again, and a bar then
  showing another text than its pin's (emptied at the opening) is given the pin's text back by ↑, nothing walked; ↓ there
  is the list's. **In a field's window, a pin is its caller's** (user rule 2026-11-21, *"don't drop the pin if the caller
  changes but it is still the same caller"*): the caller ([`LocalFieldCaller`], `SearchDomain.fieldCallerOf`) is part
  of the bar's key, so the same caller finds its pin again — its group taken apart and made again included — and another
  caller has one of its own, empty; nothing is deleted. `App` learns each bar's caller as the bar finds its pin
  (`barCallers`). A window drawn inside another under that window's id is not given that window's pin.
  **What it costs** (user rule 2026-11-20, *"history units that are old get merged when they can"*): a keystroke is
  local-only and never pushed (`Delta.synced`): no `history_unit` row, no push. Their list has its own 1000-unit cap, so
  they never push a change out; at it the oldest keystrokes of a run are merged into the one before
  (`SchedulerReducer.mergedOldKeystrokes`) before any is evicted — never across a run's start, so the stops stay; the store rewrites a merged unit in its own row
  (`SqlDelightSchedulerStore.saveHistoryCategory` aligns by time, `SchedulerHistoryWriteTest`).
- **Every search bar has a ✕ at its right end, which empties it** (user rule 2026-10-11; `SearchBarCross`, the
  field's `trailingIcon`): the Search window's bar, "Search a configuration" in both configuration windows, the
  actions section's "Search an action", the notification list's "Filter" and the task picker's "Find a task". Always
  drawn — set back and deaf while the bar is empty — so a bar keeps its shape as it is typed in. A new search bar
  takes the same one. (The task tree's find bar has a ✕ of its own, which CLOSES the bar.)
- **Typing in a search bar brings the list under it back to its top** (user rule 2026-10-14; `ScrollsToTopWhenTyped`,
  for a `ScrollState` or a `LazyListState`): each of the bars above — the Search window's results, the actions
  section, the two configuration windows' lists, the History window's rows, the task picker's suggestions. Every change
  of the text counts, the ✕ emptying it included; the first composition does not, so a list put back with text in its
  bar keeps its place. **Deleting what was typed puts the list back where it was** (user rule 2026-10-14, the same
  day): the first change of a typing session notes the bar's text and the list's place, and the text coming back to it
  restores that place — unless the HAND scrolled the list meanwhile (a scroll this did not make, seen as it happens;
  for a plain list also the list found away from where this put it), and then it goes to the top like any change. A
  plain list's restore waits up to three frames for the longer list to be laid out, a scroll being clamped to the
  height laid out. A new search bar's list calls it too. `ScrollsToTopWhenTypedTest`.
- **What a search bar holds is highlighted in what it searches** (user rule 2026-11-02; `SearchDomain.matchRanges`, the
  one answer: every hit of every term, words or "quoted phrases", case aside, in the tree's Ctrl+F shade): the Search
  window's rows (name and detail, a task's title), and, by `HighlightsSearch` around them, the actions section and the
  two configuration windows' lists — read by the shared texts through `searchHighlighted` (a group's and a micro group's
  title, `ElementHeading`, `FrameButton`, `NoteText`, `ItemResultRow`, `HistoryTextLine`, a log entry's text). An editor
  embedded there highlights its labels with no word of its own, and draws as ever elsewhere. A value inside a text
  field is not highlighted. Not yet: the History window's "Filter", the task picker's "Find a task".

### The window bar

- **The window bar along the bottom of the app — its system tray** (`WindowBar`) appears whenever a window is
  open and has a **tab for every open window, the reduced ones included** (set back in italics), in the order
  they were opened. **A tab's fill is its window's colour** (§ *Window colours*), so no state is said by a fill:
  **the focused window's tab has the thick outline** (3 dp), **a selected tab a less thick one** (1.5 dp) — **in the
  colour of the tab's NAME**, the text colour on its fill (`TaskPalette.foreground`, as on a task panel and a task
  cell; user rule 2026-10-07 — it was the theme's text colour whatever the window's was, so the name and the outline
  could be two colours), **with a very thin line of the window's colour outside it** (`TAB_OUTER_LINE`, 1 dp, user
  rule 2026-10-07: an outline the colour of the bar must not vanish into it; always drawn, so a tab keeps its size as
  it takes the focus) — a reduced
  one oblique type (user rule 2026-10-06; it was a primary-container fill, a 2 dp border and a semi-bold title). The
  focus is read straight off `WindowFrameHost.focusedId` — no tab stands out while nothing
  framed has the focus (a press on the background), and a reduced tab never does. **A tab a button made is named
  after it** (user rule 2026-10-01): the window a lateral-menu button the user made (☆) CREATES carries that button's
  name on its tab (`WindowFrameHost.tabTitles`, `tabTitleOf`) — the window registering in the frames after the click;
  one only brought back keeps its tab, or is given the name where it has none. The name goes when the window CLOSES
  (`windowClosed`), never as it leaves the screen at a change of page (`unregister`, anomaly 2026-11-01), and is kept locally on the
  `TabTitles` placement row, read back at startup for the windows that come back. Drawn at the app root and **over the lateral menu** — a window reduced while the menu is
  open must not be filed behind it. The content area is inset by the bar's height while it shows, so a
  maximized window stops above it. **The WHOLE tab takes the press** (user rule 2026-10-11:
  the gesture is on the tab itself, outline and inner room included; it was on the name alone, so the room round the
  name was dead. The ✕ keeps its own click). **A tab is a taskbar's** (`WindowFrameHost.onTabClicked`, 2026-09-26): a
  reduced window comes back and takes the focus, a window without the focus takes it (on top), and the window
  that HAS the focus is reduced — giving the focus up, so no hidden window keeps the keyboard. Its ✕ closes it
  outright.
- **A window opened from another has its tab beside that one's, a line under the two** (user rule 2026-10-06,
  `WindowTabGroups`, `WindowTabGroupsTest`): a Search configurations window (and an Added elements configurations
  window) right of its Search window, a Search window the calendar's right-click opened right of the calendar, a
  Search window opened from a Search window right of that one (user rule 2026-10-18: a row opened, an action's "New",
  "Search every element of the kind", "Blocks on the calendar", "Search its tasks" — `Config.openedFrom`, set by `App`'s
  `openLinkedSearch` from the window that SAID it asked — its openers and handlers run inside its frame id
  (`askedThrough`); never read off the focus, which a press in the lateral menu does not move), and
  (user rule 2026-10-14) the calendar opened under an "unfocused notif" window right of that window — the pair the
  app opened while it holds it, else the notifications window whose added notifications are exactly the calendar's
  chips (`CalendarNotifications.notificationsWindowOf`, so it survives a restart). The parent is DERIVED (`WindowFrameHost.tabParentOf`, answered by `App` off `ConfigurationSearch.target`,
  `Config.openedFrom` and `Config.calendarClickMillis` — what the windows hold), never a list of its own, and is none
  once that window is closed. **A Search window opened from one that came from a calendar right-click holds that
  right-click** (`SearchDomain.linkedConfig`), and a new right-click re-pointing the first window re-points the windows
  opened from it: every time field of their actions has **"Right-click time"** (`RightClickTimeButton`, one button,
  read off `LocalCalendarClickMillis`) — the alarm's and the reminder's Time, a timer's end, the blocks' start and end,
  a quota's loop, other times and particular loops, the Sleep schedule's times and goal date, "Add to the calendar"'s
  start and end, and the Search configurations window's instants. The bar lists a window then
  the windows opened from it, in opening order, so only the opened ones leave the order of opening; one line runs
  from a window to the last of those opened from it, and the line of a deeper window is drawn ABOVE (calendar,
  Search, configurations: a line under the first two, and above it a line under the last two). Everything that
  reads "bar order" — the Shift+click range, the menu's selection — reads `tabLayout.order`, never `registrations`.
- **A tab is dragged to reorganize the bar, with all it is linked to** (user rule 2026-11-01; `WindowFrameHost.moveTab`,
  `WindowTabGroups.moved`, `WindowBarDragTest`): a press held and moved with the mouse, past the slop, is a drag and no
  click, a finger still scrolling the bar. The group of the tab, up and down its links, follows the hand with its lines, a
  vertical line says where it lands, and it lands between two groups: a group is never cut. The order
  (`WindowFrameHost.tabOrder`, the `TabOrder` row) is read before the order of opening: a new window stands at the end, a
  closed one leaves it, and a change of page keeps it. One History Unit, undone from any window. **Ctrl+Tab walks the bar's
  order**, which is no longer the order of opening.
- **A window no page owns comes to the page it is asked from** (anomaly 2026-11-04: the Online window, open on the Task
  Scheduler page, was asked for from the settings page's menu and only brought back where it was — on a page not shown).
  A Search window and its configurations windows are on their configuration's page; every other window is on the page a
  button last opened or brought it back from (`App`'s `windowsAskedOn`, read by `windowPageOf`), a copy on its original's,
  the Task Scheduler's until then. Compose-only: a launch puts them back on the Task Scheduler page.
- **A window set GLOBAL stands, and has its tab, on every feature page** (user rule 2026-10-18; the window bar menu's
  "global", over the selected tabs; `App`'s `globalWindows`, the `GlobalWindows` row): a lateral-menu window (and its
  copies) or the task tree — a per-object window is drawn on its own page only, since it would otherwise be composed
  on every page. `windowPageOf` answers "this page" for a global window, so it stays composed across a change of page
  and its tab keeps its place among the other global tabs on every page; each page's own tabs stand between them.
  Closed, it is global no more.
- **A window that closes takes the windows opened from it with it** (user rule 2026-10-06,
  `WindowFrameHost.closeOpenedFrom`, `TabSelectionTest`): a Search window its configurations windows, the calendar
  the Search windows its right-click opened — and each of those its own, by its own close. The parent is the bar's
  (`tabParentOf`), never a second reading. It is called from the ONE write every close makes
  (`App`'s `updatePlacementById`, a row going from visible to not), BEFORE that close hands the focus on, the closing
  window and those opened from it all marked *closing* — so the focus goes to a window that stays. Reducing a window
  closes nothing. `closeWindowCopy` writes the row before it takes the copy out of `windowCopies`: a configurations
  window names a Search copy only while it is one (`configTargetOf`).
- **The bar's tabs are selected the file explorer's way** (user rule 2026-10-01; `ClickSelection` — shared with the Search window's result list —
  `WindowFrameHost.onTabPressed`): a click selects its tab alone (and is still the taskbar toggle above); Shift+click
  selects every tab from the last one clicked to it — the range is `CheckRange.keysToSet`, never a second reading;
  Ctrl+click adds or takes one tab; Ctrl+Shift adds the range. **A Shift or Ctrl click only selects** — it never
  reduces, raises or focuses a window. A selected tab is filled in the secondary container, apart from the focused
  tab's look. The selection is local view state, in memory only; a closed window leaves it. `TabSelectionTest`.
  **The focused window's tab is selected** (`WindowFrameHost.focus`): alone, unless it is already among the selected
  tabs — then the selection stands, which is what lets "open selection" focus one of its own windows.
- **A right-click on the bar — anywhere, a tab included — opens the bar's menu at the pointer** (user rule 2026-10-01):
  *close selection*, *minimize selection*, *open selection* (`closeSelection` / `minimizeSelection` /
  `openSelection`), acting on the selected tabs only. **The right-click selects nothing and focuses nothing**: the tab's
  own gesture lets a secondary press through unconsumed for the bar's handler. Minimize gives the focus up if the
  focused window is among them; open restores and raises them in bar order and focuses the last — one focus change.
  The menu is a menu (`transientMenuDismissal`, non-focusable).
- **Closing several windows at once goes through `WindowFrameHost.closeWindows`** — the bar's Reset and "close
  selection" both. It marks every one of them *closing* before the first close, so the focus each close hands on
  (`focusHeirOf`, `App`'s `focusAfterClose`) goes to a window that stays, or nowhere. A closed window leaves the
  stack only when it leaves composition, a frame later; a loop of plain closes handed the focus to a window of the
  same batch, and the state's focus then named a closed window, which `App` opens again (the Shift+Alt focus walk's
  effect) — both Reset and "close selection" left a window open (anomaly 2026-10-01, `TabSelectionTest`).
- **One action on several windows is ONE History Unit, undone from any window** (anomaly 2026-11-04: Ctrl+Z after the
  bar's Reset closed the calendar Reset had reopened, instead of putting the windows back). `App`'s `ViewHistoryRecorder`
  gathers what changes windows' layouts between `beginGroup` and `endGroup` into one `ExternalKeys.WINDOWS` unit (a group
  that changed one window records that window's own): the bar's Reset — held open until the page's windows, reopened
  frames later, are written — `closeWindows` ("close selection", and a close taking the windows opened from it, grouped
  with that close in `updatePlacementById`), "minimize selection" and "open selection" (`WindowFrameHost.grouped`, ended
  a few frames late: a reduce writes its row from the frame it is drawn in). The window bar is no window: its units
  (`ExternalKeys.WINDOW_BAR`) are app-wide, as the lateral menu's are — stamped with the window the focus was on, which
  the action itself then moved, Ctrl+Z took back something else.
- **The lateral menu's scrolled content ends a bar's height lower** (`LateralMenu`'s bottom padding, `+
  MINIMIZED_BAR_HEIGHT`), always — so scrolled to the end, its last button clears the bar drawn over it, and
  the menu does not jump as the bar comes and goes.
- **Reset (it was "Close all") is the bar's, at its right corner, and it puts back the DEFAULT layout** (user rule
  2026-10-01) — every window closed (`closeWindows`), then, two frames later (once the closes have taken the windows
  out of composition — reopened in the same frame a window would keep its old frame), **the window of the first window
  button of the page's lateral menu, opened as that button opens it** (user rule 2026-11-04,
  `CustomMenuButtons.firstWindowButton`, a folder's items included; none, and no window opens; it was the calendar
  alone, and another page's Search window). The calendar's chrome is still written as `CALENDAR_RESET_CHROME`. It
  replaced the lateral menu's "Close windows" (2026-09-24), which listed the lateral-menu windows by hand and
  so never closed a per-object window, a copy or a notice. **It also reset the task tree window's placement** (until
  the window was removed, 2026-11-11)
  (user rule, 2026-09-30; `WindowBar(onReset)` in `App`): offset and size back to zero and the chrome to
  `TASK_TREE_DEFAULT_CHROME`, so the tree reopens as on a first run — maximized, and un-maximizing gives the
  default size, centred. The chrome is WRITTEN as the default, not cleared: `WindowChromeMemory.saved` answers
  for any existing row, so a cleared row would reopen un-maximized.

### Focus on closing and on coming back

- **A closing window hands the focus on in ONE place** (`ViewHistoryRecorder.focusAfterClose` →
  `WindowFrameHost.focusHeirOf`): a window closing WITHOUT the focus leaves it where it is; one closing with it hands
  it to the window that had the focus most recently (`WindowFrameHost.recent`), not reduced and not closing,
  and only without one to the front window — or nowhere. `TabSelectionTest`.
- **The window the app was left on comes back focused, A COPY INCLUDED** (anomaly 2026-10-14: "timers Search",
  `Search#3`, focused, the app rebuilt, the "unfocused notif" window closed — and "Claude quota", the Search window
  itself, got the focus, twice). The state's focus is a window AND its copy (`focusedWindow`, `focusedInstance`), both
  persisted (local, never synced); the copy was kept in memory only, so a restart handed the focus to the original
  window, which then was the window the user had been in "before" the notif window. `HistoryChordsByWindowTest`.
- The calendar's close no longer sends the focus to the tree (`SetCalendarFocus` is dispatched on
  opening only): it named the tree even when the tree was closed too, and the focus walk's effect (which opens the
  window the state's focus names) then reopened it — "close selection" over every tab left a window open
  (anomaly 2026-10-01). Anything that moves the state's focus to a window must be sure that window is open.

### What is drawn and what is hit

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
  point converges, so re-applying it on every layout pass is safe. **The clamp remembers where the window was
  put** (anomaly 2026-10-14): until something else moves it, it goes back toward that place as the content area
  grows. At launch the first layout runs at the app window's restored size, before the maximize, and a clamp that
  kept its own answer left a window put in the top half ~80 dp down for good. This lives in the frame, not in the
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
- **THERE CAN BE SEVERAL CALENDAR WINDOWS, AND THEY SHARE ONE DISPLAY PIPELINE** (user rule 2026-10-13; `App`'s
  `LateralWindow(FloatingWindow.Calendar)`, `calendarSpans`). The original and its copies — the head's ⧉, or a
  calendar asked for WITH the notifications it shows (below) — each have their own selected day, month, scroll,
  zoom, sections and notification selection, declared where the window is drawn and read by frame id. The pipeline
  in `App` (records, projections, the far-week plan, the horizon handed to the engine) is NOT per window: it runs
  over the days that cover every open calendar (`visibleFirstDay` / `visibleDayCount`, the union of what each
  reports). **Two calendars on far-apart weeks therefore pay for what lies between them** — the user's choice
  (asked 2026-10-13) over a pipeline per window, which stays the way to take that cost back
  (`display-hot-path.md` binds a cost to the visible window; here it is bound to the windows' common span).
  The now-line is resampled at the finest resolution any of them reports. What is about THE calendar stays the
  original's: "go to calendar"'s task lock, the menu button and a notification's click (`openNewWindow` with no
  configuration brings the original back; one given a configuration opens a copy, or brings back the calendar
  already showing exactly that). A copy's sections are its own, kept across a restart by its frame id (`calendarCopySections`, 2026-10-31).
- **Not duplicable**: the **task tree** (above). **Notices** (`MessagePopup`) — a message has no second view. The
  debug time-sim panel wears no frame.
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
- **An element's edit window IS a Search window holding that element** (user rule 2026-10-01). The period edit
  window, the account's task edit and category edit windows, the single alarm / timer / chrono window and the single
  reminder window are gone: every way of opening one — a Search row, "Open each", the cell menu's "edit task", a
  calendar panel's "edit task", a category's ✎, a "creation" row, a ☆ button keyed by one of those kinds
  (`ObjectWindowKey.ELEMENT_KINDS`) — opens `openElementSearch`: `openNewWindow` on
  `SearchDomain.elementSearchConfig` (that kind, that one element added), so asking again brings the same window
  back. What the window held is the Search window's actions on its added elements, drawn by the SAME components: the
  category's body (`CategoryEditor`), the Alarms window's rows (`AlarmWindow(embeddedSubjects = …)`), the reminder's
  editor (`ChoresManagerWindow(embeddedSubjects = …)`, its "constrained in" picker hoisted to `App` —
  `reminderConstraintWindows` — because a full-size layer cannot sit in a scrolling list), the period's combinations
  section and drawing swatch. **The default sub-tree's task and category windows stay**: the template's tasks are not
  the account's, so no Search window lists them, and its category rules are scoped in the template's cells. A row of
  a removed window that was open at the last stop does not come back.
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
- **The same goes for a menu opened INSIDE a pop-up** (anomaly 2026-11-11, the "▾" configurations pop-up's background
  menu): a press on that pop-up is in its layer, which the root never sees either, so the menu stayed. Such a pop-up
  reports its presses itself, `Modifier.dismissesMenusOnPress()` on its content → `TransientMenuHost.onPressInPopup`:
  the menus close, the edits do not (the pop-up is part of the edit it was opened from). It is the same funnel, fed
  from one more layer — not a per-menu handler.
- The menu's popup must be **non-focusable** (`PopupProperties(focusable = false)`): a focusable
  `DropdownMenu` consumes the outside press for its own `onDismissRequest`, and that is exactly the press
  PRD §13 needs to go on and select the next cell — right-click one cell, click another, and the second cell
  is selected in the same gesture. **Wherever a menu is given `focusable = false`, the
  `transientMenuDismissal` registration must come with it** — a non-focusable menu nobody registered never
  closes at all.
- **ONE registration per menu, never two** (anomaly 2026-11-21, *"The right-click doesn't work..."*;
  `CalendarRightClickMenuTest`). `ActionMenu` / `OptionSearchMenu` register themselves; the host closes every other menu
  as one opens, so a caller that registered the same menu again was closed by the menu's own registration in the frame
  it opened — the calendar's right-click menu and the left-side menu's background menu vanished at once. A press that
  closes a menu and opens it again (a right-click elsewhere) is registered again by the menu itself (keyed on its
  closings), which is what the calendar's per-anchor copy had been for.
- **A menu opened from a row of another keeps that one open** (anomaly 2026-11-25, *"when I right-click a row, then
  right-click a row in the right-click menu R, it doesn't open a right-click menu as well"*): `OptionSearchMenu` provides
  its registration key to what it draws (`LocalEnclosingMenus`), and `TransientMenuHost.open` closes every other menu but
  those (`keep`). An entry of a menu is a row like any: its right-click opens the one row menu, never runs the entry
  (`MenuEntryRowMenuTest`); a check-box option's former right-click, "check only this", is an entry of that menu.
- **A right-click reopens an open menu only if the click left it open** (anomaly 2026-11-25, *"right-click on A which
  opens B > right-click on B > show header > right-click on a row in B, no pop-up opens"*, checked on the desktop): the
  rule of 2026-11-15 (a right-click reopens a right-click's menu afresh at the new pointer) is read two frames after the
  press (`HangingMenu`), once it has been answered. A right-click on another row with a menu of its own closes this one
  as that one opens; reopened at once, as before, the two closed each other and neither stayed. The same row
  right-clicked again still moves its menu to the new pointer.
- **A pop-up's background is a window's background** (anomalies 2026-11-25, A's background menu B, checked on the
  desktop): its whole surface, padding included, opens its background menu (the surface's `contextMenuModifier`); a
  press in a menu's own window closes the menus opened from it, never that menu nor those it hangs from
  (`dismissesMenusOnPress` keeps `LocalEnclosingMenus`), as a press in the app's window closes the menus over it; and a
  menu losing the keyboard to a window it hangs from, by a right-click there, is not closed by that loss
  (`rightPressedInApp` reads every window it hangs from) — so a second right-click reopens it, as in the app's window.
- **The window a pop-up becomes is the pop-up's**: the sections it showed (`PopupDetacher` takes its `SearchSplits`;
  its head and bar shown), and a menu's entries live as their caller gives them (`SearchOptions.asGiven`, a `State`):
  through the menu's own wrapper, a click set that menu's "held" flag and the menu came back.
- **A section's background options join its window's** (user rule 2026-11-25): the actions section and the added
  elements list have no background menu of their own; a secondary press on their background leaves their own options
  (`SectionBackground`, `recordsSectionPress`, read before the window's handler as an inner node's), which the window's
  menu shows after its own, a separator between (`SearchPartsMenu`'s `after`, `SectionBackgroundMenuTest`). **A section
  inside a section is a group of its own** (anomaly 2026-11-26, *"Right-clicking in the background of an action section
  of an action section should at least show 2 horizontal separators"*): one press leaves each section's options in turn
  (keyed by its uptime), the outermost's shown first, each group after its separator, its entries' ids told apart
  (`sectionGroups`) — before, the outer section's overwrote the inner one's. The menu's default "Entries, in order"
  (`ContextDomain.MENU_ENTRIES`' "SearchPartsMenu") names each level's group after its separator ("section 2 …",
  "section 3 …"): an entry it does not name is not listed, which first hid the inner group (`NestedSectionMenuTest`).
  **Each row is listed once** (`orderedByEntries`): a name matching several rows (the separators) takes the next one not
  taken — "separator" named three times once put the same row twice in the list, which then barely scrolled and lost
  every later press, no menu of the app opening (anomaly 2026-11-26). The separator
  is a menu entry (`MenuAction.separator()`), drawn while the list's search configuration "Separators between groups"
  (`Config.separators`, on in a menu's list, `OPTION_SEARCH`) is on; never at an end, never doubled.
- **The one exception is a menu that holds text fields** (the quota's resilience values, `QuotaResilienceEditor`): a
  field in a popup that cannot be focused cannot be typed into, so that menu is `focusable = true`, registers nothing
  with the observer, and leaves through its own `onDismissRequest` on the first press outside — which it consumes.
  Do not make an ordinary menu focusable to save a registration.
- **The field a drop-down hangs from toggles it** (`Modifier.menuToggleClickable`): a click while the menu is
  open closes it. A plain `clickable { open = true }` reopens it instead, because the root observer has
  already closed it on the press and the click lands after; the field reads the menu's state as last
  composed at the press. Every drop-down field uses it, never a hand-rolled toggle. Given its `PopupOpener`, provided
  around its menu (`OpenedBy`), the root observer leaves the menu to the field's click, and a double click is one
  click (anomaly 2026-11-20, *A press on what opens a pop-up*, above).
- **Enter ends the edit of any single-line field** (user rule 2026-10-05; `ui/EnterLeavesField.kt`,
  `EnterLeavesFieldTest`): the field gives up the focus, as a press outside it does. The app has ONE text field —
  `org.example.project.ui.OutlinedTextField`, Material's plus `Modifier.leavesEditOnEnter` — and every window draws
  it: **never import `androidx.compose.material3.OutlinedTextField` by name**, which opts a file out of the rule. A
  bare `BasicTextField` wears the modifier itself, LAST in its chain. Last is the precedence: a preview key event
  runs from the outside in, so a field with an Enter of its own (a tree cell, the Search bar, find & replace, a
  picker) answers first. A field of several lines keeps Enter as its new line; a chord is not Enter.
- **A task cell's Edit Mode rectangle is no window and no `Popup`** (`task-tree.md`, *The selection and Edit Mode*): a
  layer of the content area, over every window, gone with the session. No frame, no tab, no place kept.
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
- **The stack survives a restart** (user rule 2026-10-31): `WindowFrameHost.rememberedOrder` is it with the windows of
  the other pages and those off screen, kept on the `AppView` row. A window the APP puts back (`claimOnOpen` off: a launch,
  a change of page) goes where it stood (`placeByMemory`), in whatever order they come back; one asked for goes on top.
- **The stack is not the reduce bar's order.** `WindowFrameHost.entries` stays in the order windows were
  *opened* in, which is what the bar lists — a chip must not jump along the bar because its window was
  raised. Two orders, two questions, deliberately not one list.
- **A window that is not in the stack yet reads as the top** (`zOf` returns `stack.size`). Its `register`
  runs after the composition that first draws it, and reading as the bottom would draw a newly opened
  window under its neighbours for one frame.
- **A window button of the lateral menu asks for ONE exact window, and never closes one** (user rules,
  2026-09-26; `App.openNewWindow`): its kind with the kind's default configuration — a ☆ button's: the one it
  saved. **When that exact window is open** (same kind, same "configurations" — `sameWindowConfigurations`, so a
  missing configuration and the default one are the same; any window of a kind that has none) it is brought
  back and focused (out of the bar included). **A Search window's "configurations" are its search configurations**
  (user rule 2026-11-04, `SearchDomain.searchConfigurations`): the search as the window holds it once opened (its
  default sorting taken), never its added elements, its actions' bars and picks, or the window it was opened from.
  Anomaly 2026-11-04: compared whole and as written, the Task Scheduler page's Search button never found its window,
  which had sorted its tasks newest first on opening, and opened a new one at every press; a ☆ button's "Update" was
  offered for the same reason. **A "match the whole state" button** (user rule 2026-11-05, above) brings back only
  the window that is all it keeps: anomaly 2026-11-05, "Claude quota" and "Gemini quota", one search with a quota added
  each, were one window, and "Gemini quota" brought back the other's. Otherwise a NEW window opens: the original when it is not open,
  else a copy (`Search#2`) cascaded off it. So a Search window whose query was typed into is no longer "the"
  Search button's, and the button opens a fresh one. It is never drawn as "open" (no blue) — the window bar
  says what is open. The **task tree and the calendar** exist once (*Not duplicable*). A ☆ button of a
  per-object window brings back the window open on its object, or opens one (`ObjectWindows.open`).
  Sleep/Work and "I'm away" are modes, not windows, and keep their on state.
- **Moving to a window takes the focus**, however the move was asked for. `App`'s `focusWindow` goes through
  the same `WindowFrameHost.focus` a press inside the window would, or the menu button raises a window the
  app still believes is un-focused — and the keyboard stays with whatever was focused before it.
- `App` keeps no stacking order of its own. The one z left there is the debug time-sim panel's, which wears
  no window frame.

---

### Window colours

- **Every window has a colour, and KEEPS it** (user rule 2026-10-06; `WindowColorSpace`, `WindowFrameHost.colors`,
  `WindowColorSpaceTest`): the background of its head and of its tab in the window bar. It is given ONCE, as the
  window opens (`WindowFrameHost.register` — the one place every framed window passes), and goes when it closes.
  Unlike a task's colour it is never worked out again because something else changed. Do not derive it per frame.
- **The default windows share the `256³` cube in equal parts** (`WindowColorSpace.DEFAULT_WINDOWS`, `parts`): one
  part per kind of window, named by what its frame id starts with — the calendar, the task tree, a Search window, the
  notifications window (a Search window by its id, so it says its kind itself: `AppWindowFrame`'s `colorKind`), each
  per-object window; a kind not listed (a notice) is in `OTHER`. A default window's colour is its part's centre. A new
  kind goes at the END of its group in the list: the list's order is where the parts lie.
- **Another window of a kind takes the colour of that kind's part furthest from every colour in use and from the
  part's edge** (`WindowColorSpace.pick`): a duplicate (⧉), a second window on another object, a second notifications
  window. A colour of another part is never nearer than the edge between them, so only the part's own count. The
  part's centre is taken back by the next window of the kind once no open window has it.
- **What is drawn on it takes the colour of highest contrast** (`TaskPalette.foreground`, the tasks' rule): the
  title, the head's buttons and whatever a window puts in its head (`LocalContentColor` — a `headTrailing` must not
  hard-code a colour), the tab's name and its ✕.
- **Local-only view state**, kept on the `WindowColors` placement row (`CustomMenuButtons.encodeWindowColors`),
  written when a colour is given and read back at startup for the windows that come back.

### The notifications window

- **(Changed 2026-10-18.) The pair opens — or comes back — at the BOTTOM of the focus rank, on every page**: under every
  other window (the notifications over their calendar), last of the windows that had the focus, and not focused
  (`WindowFrameHost.openAtBack` / `sendToBack`, `openNewWindow(atBack = true)`); both are set global. What follows of "in
  front and focused" is the rule it replaced.
- **A notification that fires while the app is NOT in focus brings up a Search window on the notifications posted since
  the app LOST the focus — in front and focused, so it is what the user comes back to** (user rule 2026-10-04; `App`,
  `SearchDomain.notificationsConfig` / `notificationsWindowOwed`, `NotificationsWindowTest`). Written, spoken or
  both: whatever `notifyUser` logs (`SchedulerState.notificationLog`, also a Search kind, `Kind.Notification`, newest
  first by its own date sort). What the user was there to see is not in it.
- **It gathers until the user closes it; the next one starts at the next loss of focus.** Each further out-of-focus
  notification brings the standing window back in front rather than opening another (`notificationAnsweredAtMillis`
  keeps one notification from doing it twice). The first form (same day) listed from a stored "last closed" marker —
  the whole log the first time, days of it — and did NOTHING when the window was already open, so a timer ending out
  of focus was answered by a window opened at launch, behind the others, holding everything. There is no stored marker:
  the instant is the window's own filter (`Filters.notificationsSinceMillis`), persisted with the window.
- **"That window" is a frame id, not a look-alike.** `App` holds the id it opened or brought back
  (`notificationsWindowId`, Compose-only); after a restart the one still open with that kind of configuration
  (`isNotificationsWindow`: the notifications, FROM an instant) is the one brought back. A Search window on the
  notifications opened by hand has no such instant and is never taken for it.
- **A window the user CHANGED stops gathering** (user rule 2026-10-06; `SearchDomain.isUntouchedNotificationsWindow`):
  once its search configuration or its added elements differ from what the app opened it with, it is theirs — the
  next notification out of focus leaves it as it is and opens a NEW notifications window beside it, in a colour of
  the same part of the cube (§ *Window colours*). An untouched one is still brought back in front.
- **Every other Search window is named after what it searches** (user rule 2026-10-15: *"Make the title of a Search
  window update with the Search configurations, except if its title is set by the user … if the configuration is
  simply only timers, it is 'all timers' … for the config only timers and can be added to the calendar before 1995,
  just name it 'all timers' as well"*; `SearchDomain.windowTitle`, `Kind.plural`, `NotificationsWindowTest`). The
  KINDS and the text typed, never a filter: "all timers", "all alarms and timers", "all tasks, timers and reminders",
  "all tasks, task categories and 2 more kinds", "everything", "everything but windows", "nothing", `timers matching
  "tea"`. Read off the configuration at every composition, so it follows each change. A name the user gave
  ([WindowFrameHost.tabTitles]: a menu button's, the head's "Rename") is shown in its place on the head and on the tab.
- **It is called "unfocused notif", not "Search"** (user rule 2026-10-06; `SearchDomain.windowTitle`), on its head and on
  its tab of the window bar. The name is read off the configuration — never stored — so it comes back with the window,
  and a window the user turns into another search is a Search window again. A tab a ☆ button named keeps that name.
- **It holds every notification it lists as an ADDED ELEMENT too, and opens over a calendar that shows them**
  (user rule 2026-10-13: *"it opens and occupies all the top half of the app, and what occupies all the lower half
  is a calendar window that comes from the button in the first macro group. The unfocused notif window has all the
  notification elements in the result list in the added elements list as well"*; `SearchDomain.
  notificationKeysSince`, `App`'s `placeInHalf`, `CalendarNotificationsTest`). A NEW notifications window is a COPY
  (`Search#n`, never the Search window's own row), filling the app's width and its top half, with every notification
  since the focus was lost added; under it, on the bottom half, a calendar COPY whose chips are those notifications —
  what the "Calendar" action of its first group of actions opens. Each further out-of-focus notification is added
  to the standing window and to that calendar (held by frame id for the session; closed, it is not reopened). The
  app adding notifications is not the user changing the window (`isUntouchedNotificationsWindow` lets added
  notifications be); adding anything else, or changing the search, still makes it theirs. The halves are set when
  the windows OPEN — a standing window brought back keeps the place the user gave it. **A half is a rule the frame
  HOLDS, not a size worked out once** (anomaly 2026-10-14; `WindowFrameHost.holdHalf`, `WindowFrameState.fitHalf`,
  `WindowFrameStateTest`): the window is fitted to the content area at every layout, so it grows with the maximize at
  launch and follows the window bar, until the hand moves, resizes, fills or maximizes it. Sized once off the area
  measured at launch, the app window's restored 800 × 600 before the maximize, the pair stayed a quarter too short and
  away from both edges. The hold is in memory only; the place it fitted is kept as the window's geometry.
- **The notif window and its calendar take the focus TOGETHER, the one asked for first** (user rule 2026-10-15: *"When
  the 'unfocused notif' window gets the focus, the linked calendar window must also get the focus, but in second
  position. The opposite is also true."*; `WindowFrameHost.focusPartnerOf`, answered by `App` off the bar's link
  `tabParentOf`; `TabSelectionTest`). In `WindowFrameHost.focus` — the one place the focus moves, so a press, a tab, a
  menu button, `Ctrl+Tab` and `Ctrl+Page Up` all do it — the partner goes just under the focused window in the stack and
  second in the recent order, so closing one hands the focus to the other. No move of the focus of its own (no
  WindowNav unit). **A reduced partner comes back** (user rule 2026-10-15: *"If one of them got minimized, it must
  open when one of them gets the focus."*).
- **The calendar under an "unfocused notif" window opens on its notifications, first to last, the now-line included**
  (user rule 2026-10-14; `CalendarNotifications.openingSpan`, `calendarSpanView`, `CalendarFloatingWindow(openingSpan)`):
  instead of the ordinary hour and a half around the line, and with "Lock to now" OFF, which would pull the view back
  onto the line alone. "Under" is the bar's link (`tabParentOf`), never a second reading. EXACTLY the stretch: the first at
  the top, the last at the bottom, no margin and no minimum (anomaly 2026-10-14, *"the window is too large"*: a 5 % margin
  and a 90-minute floor opened two notifications seconds apart on an hour and a half). Seconds included: the zoom reaches
  them since the day rows are sliced (`calendar.md`, `DaySlice`; anomaly 2026-10-14 again: a 6-second pair still opened
  on ~5 minutes, the old ceiling). Shorter than what the ceiling shows (under two seconds), it is centred at the ceiling,
  as close as the grid goes. Week mode runs it down the column of its first day, in that
  week; day mode spreads it over the columns. **It is a LOCK, not an opening** — "Lock to notifications", the third of
  the calendar's locks beside "Lock to now" and "Locked on task" (one at a time): the view is HELD on the stretch, its
  end carried on by the line, re-fitted each time the line has travelled a pixel (anomaly 2026-10-14: *"the $now line$
  is moving forward, so the window is supposed to get larger, otherwise the configurations of this calendar window are
  not satisfied"*). The span is re-read whenever the notifications the calendar shows change or its link appears, and
  that turns the lock on again (anomaly 2026-10-14: the 18:16 pair, standing since 17:10 across a restart, was given the
  18:16:42 notifications with the view still fitted to 17:10–18:16:35). A scroll, a zoom or a date pick of the hand
  releases it, like the other locks; its switch is in the configuration section, there only for a window opened on a
  stretch. A pair that stays untouched GATHERS everything since it opened, so its stretch is that long. **Brought back, it brings its
  calendar back with it** (anomaly 2026-10-14), in front of the other windows and under it, and gives it the new
  notifications. After a restart nothing holds that calendar, so it is found as the calendar copy showing what the
  window held until then — read BEFORE the window is given the new ones.
- **A notification's id is its instant and its place among those of that instant** (`SearchDomain.notificationIds`),
  not its place in the log: the log is a rolling tail, and a place in it changed for every notification each time
  the oldest was dropped — an added notification, or one a calendar shows, then named another.
- **Event-driven**: the log growing and the focus changing (`LocalWindowInfo.isWindowFocused`, the same on every
  platform) are the only things that ask. The app counts as out of focus from its start until it first has it, and the
  instant the focus was lost is kept, so a notification posted while the composition was not running (a phone's
  activity stopped) is still answered on the way back. Never a timer.
