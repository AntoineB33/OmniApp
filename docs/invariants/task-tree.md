# Task tree, colours, clipboard and projections

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

### The task-tree timeline

- A dated tree is a **keyframe**; between two, the scheduler follows a linear blend, not the tree on screen.
- Identity is the `TaskId`; a task absent from a keyframe is **0 %** there. The leaves are the **union** of
  both keyframes.
- **The whole RULE STATE blends, not just the percentages**: `side-dev/README.md` names *"priority percentages,
  minimum execution time and resilience values"*, and all three travel (`blendedTaskAttributes`). The other two
  used to be read off the LIVE tree at every instant, so a task sitting exactly ON a keyframe was scheduled with
  a minimum that keyframe does not state. Two rules the blend needs: a resilience is read through
  `PeriodKinds.resilienceFor`, so a kind **absent** from one side is at that kind's *default* there and not at
  zero (blending the raw maps drags every untouched kind towards 0); and a task only ONE keyframe holds keeps
  that side's minimum and resilience throughout — only its **percentage** fades.
- `datedTaskTrees` **flushes** the active tree first. Dated trees are in `schedulingSignature`; undated ones
  deliberately are not.
- **The one sanctioned exception to "time never re-plans"** — and only because it is **boundary-driven**: inside a
  transition the plan holds the exact rule state at the line and is re-made when the line reaches the start of a
  run it placed (`SchedulerDomain.taskTreeBlendDecisionKey`, `SchedulerEngine.launchTaskTreeBlendReschedule`),
  so a transition costs one fill per run it spans. The former 100-step cursor is gone: its steps did not line up
  across two transitions of the same slope, which broke the requirements' two-scenario example. Do not
  reintroduce a per-tick or a stepped form.

### The tree has ONE root, and it is a real cell

- There used to be two well-known tasks — a conceptual `task/root` whose single child was `task/main`, whose
  cells lived in `list/main`. The second level existed so sibling trees could hang beside `main`; **named task
  trees are how the account actually holds several trees**, so it answered nothing and is gone. The survivor is
  `WellKnownIds.ROOT_TASK` (`task/root`, titled `root`), and its child list `ROOT_LIST` (`list/root`) is the
  tree's top-level list — the one `SchedulerState.rootListId` names.
- **`SchedulerDomain.withRoot` is the ONE definition of that shape**: `SchedulerState.empty` builds through it,
  the codec heals every decoded payload with it, `SnapshotMerge.repair` repairs with it, and
  `SchedulerState.applyTree` carries it across a whole-tree swap (`keepingRoot`). A second place that spells
  the root out is a second answer waiting to drift.
- **The trees stored BESIDE the live one carry the root task, and `withRoot` never sees them**: the §4
  template's `TreeSnapshot` and every `TaskTreeEntry.tree`. `SchedulerDomain.withRootTask` is their half of
  the same rule — the root TASK forced to its title and its child list, and no root CELL (the template's root
  list is parentless by design; a stored tree grows its row from `withRoot` when it is loaded). The codec
  runs it over both on decode, because the id migration cannot reach a *title*: the release account's
  template still said `main`, and named every template-owned Change Task row after it (2026-09-20).
- **The root is DRAWN** (PRD §2): `ROOT_CELL` is a real cell of the tree, alone in `ROOT_CELL_LIST` one level
  **above** `rootListId`, pointing at `ROOT_TASK`. Real, not a synthetic header, so exactly one thing draws a
  task row, one thing decides the visible order, and the expansion set answers for it as for any other parent —
  collapsing it collapses the tree. Every drawing starts at `displayRootListId`, never at `rootListId`.
- **It is drawn COMPACT** (`TaskRow`'s `compact`): the expand arrow and nothing else — no title column, no
  percentage, no minimum time, no categories, and no `fillMaxWidth`. It names nothing the user wrote, so a
  title and a percentage would restate what the row's position says, and a full-width band would read as a
  separator. Do not give it a column back "for alignment": the tree's columns are shared by a **sub-list**,
  and the root row is the only member of its own.
- **It is the one cell that is not selectable and still has a contextual menu** (PRD §13), which is most of
  what the row is for. `TaskCellMenuActions.onEdit` is therefore **nullable** — the root has no task to edit
  — and the cell menu is gated on `selectable || isRootRow`, never on `selectable` alone.
- **`rootListId` did not move onto it.** That field names the list of the tree's *top-level tasks*, and it is
  what the priority walk, the colour ring, the category scopes and the path labels all mean by "the root".
  The root cell is reached the other way round — it is the root list's `parentCellId`, and a **null** there is
  exactly what says "this drawing has no root row" (PRD §4's template and PRD §7's "All tasks", both re-rooted
  at parentless lists of their own).
- **A real cell at the top is the whole danger**: every walk that climbs to the top of the tree now finds one
  more level whose sub-tree is *the whole tree*. Three answers must not change because of it, and each is a
  bug that shipped for the length of one test run:
  - `ancestorTaskIds` and `RelativePriorityDomain.ancestorCells` **stop** at it. Counting it made every task an
    ancestor of every cell, so `assignCollisionScope` refused **every** assignment — no cell could be pointed
    at any task again — and every category scope label grew a `root / ` prefix.
  - `absoluteTaskPriorities` **leaves it out** of the map. It answers `1.0` so the walk terminates, but it is
    what the percentages are a share *of*, not a row holding one: returning it puts a second `1.0` beside the
    tasks dividing that `1.0` up, and everything summing the map counts the tree twice.
  - It is **not a render-via** (`renderViaOf`). A render-via names *which occurrence of a mirrored parent* a row
    is drawn under, and the root can never be mirrored; the top-level rows keep the `null` via they always had,
    which is what lets the tree, the "All tasks" window and the template highlight one selection alike.
- `pruneDetachedTree` seeds `ROOT_CELL_LIST` as well: the root cell hangs above `rootListId` and off every
  detached parent, so without that seed the first edit boundary prunes the row the tree is drawn under.
- **The id rename is a load-time migration, and it runs on the parsed JSON** (`SchedulerStateCodec`), not on
  the typed model — because `task/main` is named from relative-priority pin keys, task-relation keys, a
  category rule's legacy `scopeTaskId`, every stored task tree, the default sub-tree, and the **whole tree
  snapshot inside each history unit**, which is a separate row and a separate decode call. It rewrites an id
  only where the string is the whole value or the whole map key: cell ids embed their list's id
  (`cell/list/main/3`) and must survive byte for byte, or every expansion, selection and rule scope stops
  resolving.
- **The whole-tree category scope is written blank, never as the root's id.** `legacy.isBlank()` has always
  meant "the whole tree", so a blank survives a downgrade; `task/root` would be unresolvable to a pre-rename
  build, which would drop the rule.

### A sub-list belongs to the task id, not to the cell

- Every cell pointing at a task shows the **same** sub-tree — that is what mirroring is. So re-pointing a cell at
  another id must **not** delete the task it left: a titled, cell-less task that still holds a populated sub-list
  is a **detached parent** (`SchedulerDomain.isDetachedParentTask`), kept by `purgeOrphanTasks` and seeded into
  `pruneDetachedTree`'s walk. Assigning that id back restores its sub-tree.
- **The blank title is what deletes.** Emptying a cell (PRD §4 *Deletion*) blanks its task's title, and a
  blank-titled task is never a detached parent — that single rule is what still collects an emptied parent's
  sub-tree, and what keeps a peer's deletion sticking through `SnapshotMerge.repair`. Do not make the retention
  key on anything else.
- **A sub-list's id is derived from the task id; its placeholder CELL is not.** `<task>/children` names one
  list for the life of the account, but every cell — the placeholder a freshly minted sub-list carries
  included — comes off `SchedulerState.allocateCellId`, because **a cell id is minted once, ever**. A cell
  keeps its id when it is dragged elsewhere, so a hand-built `cell/<task>/children/0` re-minted with the
  sub-list overwrote that cell where it now lived (drag a child out, empty the parent so `pruneDetachedTree`
  takes the sub-list, name the task again): one cell id in two lists, blanked, its `parentListId` naming the
  wrong one — and `siblingTaskIds`, so `canAssignTaskId` and `eligibleAssignTaskIds`, then answered about
  the other list and let the same task id be put twice in one sub-list (Constraint 1).
- **Naming a cell is also its ARRIVAL in its sub-list's weight table**, so `applySetCellTitle` seeds the
  cell's weight row from that list's **default row** (`priorities.md`) at the instant the cell stops being
  textually empty — never when the placeholder was minted, and never on a rename.
- **Expansion is keyed by the CELL but the sub-list belongs to the TASK**, so a cell's `expanded` entry goes
  stale the moment it is given a different task's (or a brand-new, empty) sub-list. `applySetCellTitle` drops
  the cell where it **mints** that sub-list — a freshly minted sub-list is never shown expanded. A rename mints
  nothing and keeps its children on screen. Nothing puts the cell back: **creating a task never expands it**,
  the default-subtree graft included (below).
- **Whether there IS a Change Task menu is `changeTaskMenuEntries`' own answer, handed over as an empty
  list** — no caller may re-derive it from a row count. A lone "New task" row is a real menu (the cell's task
  has a past to abandon) and a lone "New task" row is also exactly what the old `entries.size > 1` guard read
  as "collapsed", so the two are indistinguishable from outside. The rule it applies: the menu appears only
  when some task **other than the cell's own unchanged one** is offered, or — with no such rival — when that
  unchanged task `taskHasTimelineHistory`, and then as the "New task" row alone. "Unchanged" is read against
  the session's `treeBefore`, not just `state.cells[cellId]`, and that is the whole of the rule: the id a cell
  *passes through* mid-session is a choice, and hiding it would take away the row that says a typed title was
  **reused** (PRD §4 *Creation* — the only way to a second task of an existing title, and the release tree
  holds five "planning"s), the purple row a **pick** must keep to render as selected, and the previous id that
  has to come back after "New task". What the row count hid instead was the cell's own path, drawn under a
  cursor that was pointing straight at it — read on the release account as a second, dead task with the same
  path (2026-09-23).
- **What the Change Task menu hides is exactly PRD §4 *Filtering*: the cell's own list, and its ancestor
  PATH** (`assignCollisionScope`). Not the ancestors' whole **sub-trees** — a task already recurring
  elsewhere under the same ancestor is *mirroring* (Constraint 3), not a collision, and Constraint 1 forbids
  a repeat within one **list**, which two sub-lists under one parent are not. The wider rule shipped for two
  and a half months and hid the id menu for a **third** of the (empty cell, existing title) pairs of the
  release tree, so typing a title the account has offered nothing to point at. What survives is Constraint
  2: the candidate's own structural sub-tree must not hold one of the cell's ancestors, or it would become
  its own descendant. `canMoveTaskIntoList` asks that same question of a drop — a menu that refuses what
  dragging the cell there performs is the second copy of a rule, and it is the copy that was wrong.
- **A Change Task menu row's PATH is walked over the cells, never over `Task.childTaskIds`**
  (`shortestTaskTreePaths` — one BFS, so the first path reached is the shortest, and each LIST is entered
  once). That denormalized field only tracks freshly-typed children, so a task that arrived by a move, a
  paste or an id assignment had no link into it and the BFS died at the root: **153 of the 163 tasks in the
  release account's tree were named by their bare title**, which turned the 64 tasks called "planning" into
  64 identical rows and flattened the menu's first sort key to the constant 1. It is asked once per menu and
  handed to the sort and to every row's label — it is a whole-tree walk, and the sort asks it per comparison.
- **A path is read against a named root, and on the §4 template's projection that name is the template**
  (`DEFAULT_SUBTREE_ROOT_LABEL`, keyed on `SchedulerState.isDefaultSubtreeProjection`). A template-owned task
  is named from the drawing it lives in, so with the root task's own title its row read exactly like a path
  through the account's tree — `planning / write good prompt`, which the account's tree has nowhere — and the
  user went looking for it there (2026-09-20). A task the ACCOUNT holds is still named from the account
  (`namingSource`), so the two kinds of row now say which tree they are about.
- A task **the tree does not hold** is named by its child titles under the `[dead]` mark
  (`DEAD_TASK_ROW_PREFIX`), never by a path (PRD §4) — and sorts **last**, after every row that has one. The
  mark carries the whole statement: "no path" is not one a reader makes, and a row reading `planning` next to
  one reading `root / planning` looks like a shorter path, not like a task that is not in the tree — which is
  how a live row came to be read as a dead one (2026-09-23). "Does not hold" is `shortestTaskTreePaths`, the same predicate the
  "All tasks" rows and the greying of "go to task" use, so it also covers a task stranded *inside* a detached
  parent (which `taskHasCells` calls present). Those are precisely the rows whose "go to task" is greyed, and
  ranking them by a nominal path length of 1 put them FIRST — under the cursor, offering the one answer that
  cannot work.
- **An id row of the Change Task menu right-clicks to "go to task", and it is GREYED, never dropped, when the
  task is nowhere in the tree.** The whole point of the menu is that it offers tasks the tree does not show —
  a detached parent, a task kept alive only by its records — so "there is nothing to go to" is a normal answer
  about a perfectly real row, and hiding the entry would read as "this row has no menu". `null` from
  `firstTaskOccurrence` is that answer, said as a disabled row here and as the `MessagePopup` notice on the
  calendar (`EditMenuRowActions`, the one carrier: no actions ⇒ no menu at all, which is every **title
  suggestion** — a title several tasks share names no one task to go to). Three things it must not become: it
  goes through **`RevealCell`** like §8's "go to task tree" and the find bar, never a fresh selection path; the
  secondary press is **consumed** by `contextMenuModifier`, so opening the menu never also *picks* the id under
  it; and the reveal follows the surface's own `state`/`onIntent`, so in the "All tasks" window and the §4
  template it lands on that window's rows — which is why it is named "go to task" and not "go to task tree".
- **Neither edit-mode menu may bury the one under it, and the two answer that differently**
  (`EditModeMenuBlock`, the one block every naming field renders through — so no caller decides this). The
  **identity** menu keeps every row it is given and **SCROLLS** past `EDIT_MENU_IDENTITY_VISIBLE_ROWS` of
  them; the **title suggestions** are **truncated** at `EDIT_MENU_SUGGESTION_LIMIT` by a plain `take`. That
  is not an inconsistency: a suggestion is a guess the field offers, so eight of them is the whole offer,
  while an id row names a task the user may be looking for — dropping the ninth would hide it with no way
  back. So never `take` the identity rows, and never make the suggestions scroll. The sections are stacked in
  a fixed order, which is the whole reason a bound is needed at all: an id menu that listed a hundred matching
  tasks pushed **Title suggestions** off the screen entirely, and a user with no reason to scroll that far
  never learned it was there.

### Copying a cell

→ ADR 0012. Two shapes, **one reader**: the readable sub-tree text `renderCopiedNodes` writes (Ctrl+X and
the menu's "deep copy") and the bare **task-id reference** `taskIdReferenceText` writes (Ctrl+C and the menu's
"copy task id (ctrl c)"). `parseTreeText` reads both, and nothing else parses a clipboard.

- **The clipboard text is for a PERSON to read**, not only for the app to parse: a tab-indented title line per
  task, its fields as named `- <field>: <value>` lines one level deeper, the task text **verbatim** in its own
  indented block. Do not re-pack it into flags and appendices for a shorter payload — that shape shipped, and it
  put a form-feed and a `\n`-escaped note in the user's clipboard.
- **A copy carries everything the cell's Edit window holds** (the screen switch, the schedule unit, the text)
  plus its minimum time and its weight row, so Ctrl+V restores the task and not just its title.
- **Its CATEGORIES travel by NAME** (`- category: <title>`, one line each so a title may hold a comma). A
  paste therefore lands on the category of that name where the account has one and mints it where it has not
  — the same create-or-attach the row's own field does, never a second rule about what a category name means.
- **The priority-weight TABLE of every sub-list the copy walks travels with it** — the parent node carries the
  sub-list's weight columns, each child carries its own value row. A copy that restored the rows without the
  header would re-normalize every percentage at the destination.
- **The task-id reference is the id and nothing else** — one `OmniApp task id: task/user/41` line per task
  (`TASK_ID_REFERENCE_PREFIX`), deliberately a sentence no other application writes, so a paste can tell it from
  text the user copied elsewhere. Three rules hold it together. The shape is decided **before** the ids are read
  (`isTaskIdReferenceText`): a payload the app plainly wrote but whose id it never mints is a **no-op**, never a
  task *titled* after the reference line, which is what falling through to the title-tree parse made of it. Such a
  node may only **Mirror** (`CopiedNode.reference`) — Restore would rebuild the task under the blank title that
  deletes and Fresh would mint an untitled clone, so an unknown or unassignable id changes nothing at all. And a
  title that reads like one is **escaped** (`escapeTitleField`), exactly as a title that reads like an attribute
  line is.
- **The three copy switches do not reach it.** They say what a copy of a *task* carries; an identity has no
  fields to leave out (and `copyIncludeIds` off would leave nothing to write at all).
- **It carries the task id too**, so a paste lands on the SAME task, not a clone. Three identities
  (`PasteIdentity`): the id names a live *titled* task this cell may hold ⇒ **mirror** it (a sub-list belongs to
  the task id, so its own sub-tree shows and the clipboard's children/fields are never written over it); the id is
  free ⇒ **restore** the task under that very id, and `reserveTaskId` walks the counter past it; no id, or one
  `canAssignTaskId` refuses ⇒ a **fresh** task, as before. An id that is not the `task/user/<n>` the app mints is
  rejected at parse time — never build a task over `task/root`, the tree's own root.
- **Ctrl+V REPLACES the cell it lands on**: the cell is re-pointed at the pasted task (never a rename of the task
  that was there), which leaves that task a detached parent its id can bring back.
- **The attribute names ARE the format**: they exist once (the `ATTR_*` constants) and the parser matches those
  same constants. A second copy is how a writer and a reader drift apart.
- Fields belong to the **node**, never to the title — two tasks sharing a name must not share a minimum time.
  A title that reads like an attribute line is escaped (`\- text:`).
- **Paste stays a no-op for foreign text**: an unknown attribute, an unparseable value, a real tab inside a
  title, or an indent jump ⇒ `null` ⇒ the reducer returns the state unchanged. A plain tab-indented title tree
  still pastes, with its min-times left null.
- The pre-1.6.0 form-feed shape is still **read** (a clipboard outlives a rebuild), never written.
- **The cell menus are the one thing an outside press still closes, through the app-root observer**
  (`transientMenuDismissal`,
  `popups.md`), never through their own `DropdownMenu`. A focusable popup CONSUMES the outside press for its
  own `onDismissRequest`, which is exactly the press that had to go on and select the next cell — so both cell
  menus (the row's and the percentage column's) pass `PopupProperties(focusable = false)` and register with
  the host instead. They publish no bounds: a press inside a `Popup` never reaches the observer, so every
  press it sees is already a press outside the menu.
- **A right-click SELECTS, through the ordinary `ClickCell`** (no ctrl, no shift, no `forceClearMulti`) — one
  rule, so the outside-the-selection case collapses onto the clicked cell and the inside-a-multi-selection case
  keeps the block, with no second selection path to drift. It fires from `contextMenuModifier` (the one handler
  that certainly sees the press: it is dispatched first and CONSUMES it), **before** `onOpen` — the menu's
  entries read the selection as they are built, so a menu opened over a stale one offers the wrong block. The
  percentage column consumes the press itself, so it carries its own `onSelect`; and `selectionPointerModifier`
  must **return on a secondary press** — its deferred single-click reset would otherwise collapse, 300 ms later,
  the very multi-selection the menu was opened to act on.
- **The menu and Ctrl+C must agree about what "the cell" is**: a right-click INSIDE a multi-selection copies the
  whole block (`contextMenuCopyTargets`), exactly as Ctrl+C does; outside one, that cell alone. Copying only the
  cell under the cursor while a dozen sat selected is what shipped and was wrong.
- **The gestures divide by WHAT and by how much, and nothing else**: the menu's **"copy task id (ctrl c)"** and
  **Ctrl+C are one gesture** — the identity alone; "deep copy" is the tasks themselves down to the window's
  number (1 = the cells alone, which is what the old menu "copy" was); and **Ctrl+X is the ENTIRE sub-tree**
  (`FULL_SUBTREE_DEPTH`) **plus the §4 deletion** of the same cells (one history unit, "Cut" — which is what
  frees the ids a later Ctrl+V restores). Do not re-point Ctrl+X at the account depth: a number set for one deep
  copy would then silently truncate every later cut.
- **The account's `deepCopyMaxDepth`** (default/reset 20, persisted + synced, not an Undo/Redo unit) is the
  **deep-copy window's** number — the window opens on it and writes it back when it copies.
- **What a copy carries is the account's too** — `CopyOptions`: `copyIncludeIds`, `copyPriorityTables`,
  `copyIncludeText` (all default on, persisted + synced, not Undo/Redo units), the deep-copy window's three
  switches. They govern **every** copy of a task, Ctrl+X included; scoped to the window they
  would be unreachable from the everyday gesture.
  - Tables off ⇒ the weight lines are replaced by `- priority in its sub-list: <n> %`, `cellShare` stored as the
    node's **single weight** (so the paste path is untouched and the shares rebuild themselves), rounded at copy
    time so a second round trip changes nothing.
  - Ids off ⇒ the payload is foreign **by construction**: it pastes as new tasks and IS seeded with the §7
    template. That is the switch's meaning, not a leak in the gate.
- **only "deep copy" opens the window**, which prints one path down to the depth. That path
  follows the deepest branch measured over the **whole** depth asked for — measured over the remainder, every
  branch ties and the path jumps around as the number changes — and, over several copied cells, starts from
  whichever of them reaches furthest.

### The selection and Edit Mode belong to the TREE, not to the pointer

→ PRD §3/§4/§7. `reduceClick` and `reduceFocusWindow` (`state/SchedulerReducer.kt`).

- **A press on another task CELL is the only thing that moves the selection, and the only thing that forces
  Edit Mode to exit.** There is no "deselect" gesture: no tap handler on the tree's empty space, none on the
  screen's title, and `reduceFocusWindow` moves focus and nothing else. The old `ClearSelection` intent and the
  three call sites that raised it are gone — an intent that clears the selection is how "clicking somewhere
  harmless" starts throwing a rename away again, so do not reintroduce one. `Escape` (`CancelEdit`) and the
  keyboard exits are how a session ends without naming another cell.
- **The session survives; the FOCUS follows the keyboard.** `LocalTreeKeyboardOwned` (published by
  `TaskTreeView` from its own `keyboardOwned`) is what the edited cell reads: it takes the caret while this
  tree owns the keyboard and gives it back when it does not, so keystrokes aimed at the window the user just
  went to never land in the rename they left open — and coming back puts the caret straight back in it. A
  composition local, like `LocalTransientPopupHost`, so all three drawings of the tree get it at once; never a
  flag each surface has to remember to pass on. Losing focus must never end the session — that is the
  behaviour this replaced.
- **A Mode pick hands the caret back to the field — every pick, the current mode included**
  (`cellEditModeOptions`, which ends each option in `onModePicked`). The drop-down is a focusable popup, so it
  took the focus, and the state cannot give it back: re-picking the current mode is a reducer no-op. So the
  pick bumps a counter that is a key of the cell's ONE focus effect (the `LocalTreeKeyboardOwned` one above),
  which waits a frame for the popup to leave and requests focus. Do not add a second `requestFocus` call
  site for it — it would skip the `keyboardOwned` gate.
- **Clicking the cell that is being edited is not "another cell"**: `applySelectionChange` ends the session
  only for a *different* `clickedCellId`, which is what lets a click back into the field resume the rename.
- **The first letters typed onto a selected cell are never lost, however late the frame is — and the REDUCER
  is what guarantees it.** A key handler reads the state of the last *composition*, and Compose delivers key
  events without recomposing between them, so on a frame the app owes elsewhere (a fill, a derivation, a
  save) the second and third letters of a burst are typed against a snapshot that still says "no session
  open" and arrive as further `BeginEdit`s carrying their own `initialText`. Each used to start a FRESH
  session, so a burst kept only its last letter. `reduceBeginEdit` therefore **absorbs** a keystroke offered
  to a session already live on that cell — it is `UpdateEditText` by another route, and is reduced as exactly
  that — and treats a text-less re-entry (a stale second Enter or double-click) as a **no-op**, because
  restarting would recapture `treeBefore`, the baseline `Escape` and a Rename switch revert to, over the
  half-typed title. Do not answer this in the UI by comparing snapshots or by throttling: only the reducer
  sees the true current state, which is the whole reason the rule lives there (`EditModeKeystrokeRaceTest`).
- **The same race has a second window, and it is the only thing `treeSelfFocused` is for**: between the
  session appearing in the state and the cell's field taking the caret, a printable key falls through to
  whatever is focused — still `TaskTreeView`'s own `Column`, which writes no text — and was simply dropped.
  `Modifier.onFocusChanged { it.isFocused }` on that Column is exactly that window (any child holding the
  focus, the edit field included, makes it false), and a printable key inside it is dispatched as one more
  `BeginEdit` on the live session rather than swallowed. It must stay a dispatch of that same intent: a
  second way to write into a session is a second copy of the rule above.
- **The race has a THIRD window, and it is the edit FIELD's own buffer: the field is composed off the
  draft, during composition, never synced into from a `SideEffect`.** `BasicTextField` keeps an internal
  buffer seeded from the `value` it was COMPOSED with, and re-seeds it only when a later composition hands
  it different text — so anything a `SideEffect` publishes arrives a frame late, and the field is focusable
  in that frame (the effect that requests its caret runs on the same one). The field used to be composed
  EMPTY and filled in afterwards, so on a late frame the first key to reach the focused field overwrote the
  letter that opened the session *and* every letter the reducer had absorbed after it — the whole burst, not
  one letter. The two rules above cannot reach this: they put the letters in the state correctly, and the
  field simply never asked for them. `displayTitle` **is** `editSession.draftText` for the row being edited,
  so the field owns its CARET and nothing else: seed the remembered `TextFieldValue` from the draft and
  repair a mismatch *before* the value reaches `BasicTextField`. A second writer of the same text with its
  own idea of when to publish is exactly the shape this keeps out.
- Residue, named so it is not rediscovered as a new bug: a letter the `treeSelfFocused` branch absorbs in
  the gap between a composition being applied and that composition's focus request running is one the
  field's buffer has not seen, and the field can overwrite it if the user types again before the next
  frame. It costs one letter, not a burst, and closing it means a second `requestFocus` call site — which
  the ONE focus effect in `TaskRow` deliberately refuses.

### Find & replace (Ctrl+F)

→ PRD §4. `TaskTreeSearch` is the whole of it; the bar (`ui/TaskTreeFindBar.kt`) is Compose-only state, like
the calendar's zoom — a search is a way of looking at the tree, never a fact about it.

- **The walk covers the WHOLE tree, and visits each LIST once.** A find over the visible rows would miss
  every collapsed one; and a sub-list belongs to the task id, so a mirrored sub-tree is *one* list under
  many parents — re-walking it per occurrence is exponential. Each match carries the path that reached it.
- **A match is a range inside one title**, and a mirrored task is a row of its own — but **Replace All is
  keyed by TASK**, once each: replacing means renaming (`applySetCellTitle`, the Rename-mode primitive), so
  every cell pointing at the task follows. A replacement that empties a title deletes by §4's ordinary rule.
- **Revealing a match is ONE history unit** (`SetExpandedDelta` over the whole expansion set), never one
  `ToggleExpandDelta` per level. And **typing does not jump** — every jump is a selection unit, and
  `Alt+←` would have to walk back one per keystroke. The shading is the live feedback.
- The bar is a **sibling** of the tree, not a child, so the tree's `onPreviewKeyEvent` never sees what is
  typed in it — and the tree's selection-keyed refocus effect must skip while the bar holds the keyboard.

### Task colours

→ ADR 0013. `TaskColorSpace` is the whole of the rule, `TaskHueMemo` holds the previous answer and the
debounce, and `TaskPalette` is the only place a hue becomes something to paint with. Both the tree's cell and
the calendar's panel read the **same** hue for a task — a second derivation is how the two surfaces start
disagreeing about what colour a task is.

- **The tasks with an empty sub-tree own the circle, spread as far apart as they can be.** `n` of them take
  the `n` hues `i/n` — the arrangement maximising the smallest distance between any two. They are the many,
  and they are what the calendar shows.
- **Their ORDER around the circle is the tree's own depth-first order**, which is what makes "the closer two
  tasks are in the tree, the closer their colours" true. It is free: every order spreads them equally well, so
  the order can be spent on the tree at no cost to the separation. A branch is a contiguous run of the circle.
- **Every other task then takes what is left, as far from all the others as it can get** — one at a time, most
  constrained first (narrowest sub-tree arc, ties by walk order), each landing at the point of **its own
  sub-tree's arc** furthest from every colour already given out. The arc is the smallest stretch holding every
  leaf below it, widened by **half a ring step** at each end — without that half-step the parent of a single
  leaf would have nowhere to go but that leaf's own hue. The maxima are exactly the gap midpoints plus the
  arc's two ends, so the search is an enumeration: never a scan, a grid or a repulsion loop.
- **Where several answers tie, the one closest to the PREVIOUS answer wins.** Ties are the normal case (the
  circle has no origin; a gap has two equally distant halves) and breaking them arbitrarily repaints the whole
  tree on every edit. Both the ring's **rotation** and each parent's **pick** are settled that way, and
  `hues(state, previous)` is a **fixed point of itself** — feed an answer back in and it comes back unchanged.
- **One `TaskHueMemo` per tree, and it CACHES.** `TaskHueMemo.account` serves the task tree and the calendar
  both, so the identity above holds by construction rather than by two call sites agreeing; the PRD §4 template
  gets its own (sharing one would make each tree the other's "previous answer"). The cache key is
  `cells`/`lists`/`tasks` alone — the advance tick replaces the state object every second (records live on the
  tasks), and re-walking the tree on each one is the per-tick cost ADR 0009 forbids.
- **The colours follow the tree with a DEBOUNCE** (`rememberTaskHues`, 400 ms; the first composition is
  answered at once). Typing a title or pasting a sub-tree walks through a dozen intermediate trees.
- **The walk visits each LIST once and a colour belongs to the TASK** — a sub-list belongs to the task id, so
  re-walking a mirror per occurrence is exponential *and* would leave the calendar panel, which knows only the
  task, with several colours to pick from. The **first** occurrence reached names the task and walks its
  sub-tree; a later one adds nothing, though the branch it is mirrored into still counts it as one of its own
  when that branch's arc is measured (which is why an arc can wrap round the circle). The visited set doubles
  as the cycle guard.
- **Only populated cells take part** — an empty placeholder takes no colour and no room on the circle.
- **The depth is no longer what tells two tasks apart** — the placement is, and a parent is kept off every hue
  its own sub-tree holds. `TaskHue` still carries it and the palette still spends it on lightness, because a
  parent and the leaf it was placed beside are *neighbouring* hues by design. Do not go back to averaging an
  arc (that made `Book` and `Draft` the identical hue), and do not "fix" a collision by perturbing a hue.
- **WHEREVER A TASK IS NAMED, IT IS NAMED THE SAME WAY** — the string from
  `SchedulerDomain.taskTitleLabel`, the tint from `TaskPalette` (`ui/TaskTitleLabel.kt`). There is no surface
  that prints a task's title and leaves it uncoloured; the rule is not "the tree and the calendar", it is
  every place the app says a task's name. Thirteen sites had drifted into six spellings of `(untitled)` and
  two of them had lost the colour entirely.
  - **The tint is a BACKGROUND, never the text colour**, and that is the only reason the rule can be
    universal. The foreground stays free for what a surface has to say about the row *here* — PRD §7's task
    picker writes a task the now-line forbids in red and one it merely scales in orange — so "which task is
    this" and "what is true of it here" never compete for one channel.
  - **The one thing a caller configures is WHICH READING of the hue**, and it follows the surface's own
    background and nothing else: `TaskPalette.sheet` on a light surface (the tree, the menus, the windows),
    `TaskPalette.accent` on a dark one (the calendar's hover bubble is drawn on `inverseSurface`, where a
    sheet tint is invisible). A third reading is a third answer to what colour a task is.
  - **Two drawings, one rule**: the **block** form tints the whole row, because the row *is* the task — the
    tree's `TaskRow`, an `EditMenuItem` row. The **chip** form (`TaskTitleLabel`) tints a name sitting among
    other things — a bubble, a list row, the two ends of a task-relations pair. A third drawing that leaves a
    name uncoloured is the drift the file exists to stop.
  - **The one sanctioned exception is the priority-weight window's pie legend**, whose SWATCH is its slice's
    colour and not its task's. The slices cannot be keyed by task: the rule above spreads childless tasks in
    depth-first order, so the leaves of one sub-list — exactly what that chart draws — are a contiguous run of
    neighbouring hues, and the pie would be a single smear. The legend's **name** is still tinted; only the
    swatch answers the other question.
- **The tree's tint is the row's RESTING background only.** Drag-move and non-selectable still win outright —
  a tint under either of them would be one more thing to read them against, and plain white is the strongest
  possible marker on a coloured tree.
- **SELECTION AND EDIT MODE ARE SAID IN THE OUTLINE ALONE**, and that is what keeps the tint readable where it
  matters most. A cell that is the main selection, is among the selection, or is in Edit Mode keeps its own
  background — its task colour — and is marked by its border alone. A fill repainted precisely the rows the
  user is working on, so "which task is this" was unreadable in the middle of a rename and a whole selected
  block lost its colours at once. `SheetColors.selectionFill` is now the find bar's latching-toggle fill and
  nothing else — do not put it back behind a cell.
- **THE THREE STATES ARE THREE OUTLINES, and the ranking is one function**: `taskCellOutline` (in
  `ui/TaskSheetChrome.kt`, beside the colours) turns the three overlapping flags into one `TaskCellOutline`,
  and `borderWidth`/`borderColor` draw it — 1 dp `grid` unselected, 1 dp `activeBorder` for a cell of the
  selection, 2 dp `activeBorder` for the main selection, 2 dp `editBorder` for Edit Mode. The flags are
  **nested** in the state (the edited cell is also main, which is also in range), so the ranking, never the
  caller, is what keeps them apart: read `isEditing` first. Edit Mode gets its own **hue** and not merely a
  third weight because it is not a third degree of selection but the state where the keyboard writes into the
  cell — it used to share the main selection's 2 dp blue, and the two were indistinguishable. Three surfaces
  draw task cells, so the rule may not be re-answered at a call site; `TaskCellOutlineTest` holds it,
  including that the four drawings are pairwise different.
- **The uniform §8 event blue survives as the fallback**, for a panel whose task the tree gives no colour. A
  no-screen / inactivity period takes no task colour at all: it is not a task.
- **Colours are DERIVED, never persisted or synced** — recomputed from the tree, like the percentages.
- **A period is drawn as an OUTLINE so every colour stays available to the tasks** (ADR 0002/0013): a wash —
  or the vertical-line marking that preceded the outline — over an inactivity period, a sleep window or a
  screen break repaints the task panels such a period may legitimately hold, and costs the palette a corner of
  the circle.

### The "All tasks" list

→ PRD §7. `SchedulerDomain.taskListEntries` decides which tasks and in what order; `ui/TaskListWindow.kt`
draws them **as task cells**.

- **It is a readout of the LIVE tree**: `absoluteTaskPriorities` (the identity the tree's own percentage column
  keeps), never `blendedTaskPriorities`. `formatPriorityPercent` is shared with the tree — a second copy is how
  two readouts of one number start disagreeing at the first decimal.
- **A mirrored task is ONE row.** Occurrences are counted off `state.cells` through `isPopulatedCell`, exactly
  as `absoluteTaskPriorities` and `RelativePriority.occurrenceChains` count them, so the two columns can never
  disagree about what an occurrence is. A blank-titled (deleted) task and a detached parent are not in the list.
- **"In the tree" is `firstTaskOccurrences`, NOT "has a cell" — one predicate, for the rows here, for
  `periodKindTaskRows`, and for what "go to task" is greyed on.** They are different answers, and the gap is
  not exotic: a **detached parent keeps its whole sub-tree**, so the tasks inside it still have cells the tree
  can reach from nowhere. Counting cells listed those, and `TaskListWindow`'s `mapNotNull` — asking this very
  walk for their row cell — then dropped them again: the sort counted a task the window could not show (5 of
  them on the release account, all inside 4 detached parents). Membership is the walk; the **count** is still
  every populated cell, unreachable ones included, because that is the occurrence the percentage divides over
  and the two columns must agree. Do not "fix" the count to match the walk.
- **Ties fall back to the title then the id, and the tie-break is NOT reversed with the direction** — otherwise
  a block of tasks sharing one percentage re-shuffles every time the arrow is flipped.
- **The sorter is Compose-only state**, like the calendar's zoom and the find bar: an ordering is a way of
  looking at the tree, never a fact about it. Not persisted, not synced, no history unit.

#### "Similar titles" is a figure about the LIST, not about a task

→ PRD §7, `TitleSimilarity`. The third sorter figure answers *what have I written down twice?* — so what
matters about a task is its **single closest** neighbour, never its average distance from the tree.

- **The order is two figures deep, and the second is part of the FIGURE, not of the alphabetical fallback**:
  the **best** score a task reaches against any other listed task leads, and tasks sharing one best score are
  ranked by **how many other tasks they reach it against**. Both follow the direction toggle; the
  title-then-id fallback below them still does not.
- **The score is a whole percent, and that is load-bearing.** The order is defined by "the same maximum", and
  a Dice ratio is a `Double` — two pairs alike in exactly the same way would compare unequal at the
  seventeenth digit, so the tie-break would never fire and `matches` would always be 1. Quantizing is what
  makes "the same maximum" a real answer.
- **The metric is Sørensen–Dice over character bigrams** of the case-folded, alphanumerics-only, single-spaced
  title. Bigrams because the near-duplicates this is for are near-*spellings* (`Write report` / `Write
  reports`), which a word-set measure calls strangers; Dice rather than an edit distance because it is
  symmetric, needs no matrix, and does not care about the word order two writings of one task rarely share.
  Titles that normalize to the same non-empty text score `PERFECT`; one with no bigram left matches only its
  own twin, and one with nothing alphanumeric at all matches nothing.
- **A zero is "not alike", never a tie at zero.** `matches` is 0 exactly when `best` is 0 — otherwise every
  task in an account of strangers would report a match against every other one.
- **It is measured ONLY when it is the sort asked for** (`TaskListEntry.similarity` is `null` otherwise). It
  is a pass over every PAIR of titles, and `taskListEntries` is also what `periodKindTaskRows` walks — ADR
  0009: nothing that size belongs on a path something else gets for free. `TitleSimilarity.of` fills both
  sides of each pair from one measurement, because the relation is symmetric.
- **The row prints both halves** (`≈96 % (2)`), because both order the list: percentages alone would leave a
  block of equally-alike tasks looking arbitrarily ordered when the bracket is exactly what ranks them.

#### The rows ARE task cells — the THIRD drawing of the tree

**The window is the task tree — the same code, not the same look**, exactly as the default sub-tree window is
(`TaskTreeView` is now drawn three times). `projectTaskList(rootCells)` hands it the **live** state re-rooted at
a synthetic list holding, in the sorter's order, the **first occurrence cell** of every listed task, and
`SchedulerIntent.InTaskList` is what points its intents there. So the rows carry the tree's chrome, its
percentage and minimum-time columns, Edit Mode, the selection and keyboard, drag-move, Ctrl+C/X/V, Ctrl+F and
the full §13 contextual menu — plus **"go to task tree"**, the calendar panel's own entry under its own name and
through the same `RevealCell` primitive. Expanding a row shows that task's sub-tree, because a sub-list belongs
to the task id. Do not add a second row implementation: the flat one this replaced is exactly what drifts.

- **A root row is a REAL cell of the live tree, never a synthetic one.** That is what makes an edit here an
  edit to the tree with no translation — and what keeps every count honest: occurrences and percentages are
  read off `state.cells`, and a synthetic cell per task would silently double all of them. A task no cell
  reachable from the root holds has no row, which is the same answer "go to task tree" gives.
  `firstTaskOccurrences` is the one walk that finds them all (one walk, not one per row — ADR 0009), and it is
  the *same* walk as `firstTaskOccurrence`.
- **Re-rooting is the whole of the projection**, so every navigation the tree does — visible order, `Ctrl+A`,
  the arrows, Ctrl+F's walk — follows the window's rows for free. Two root walks must NOT follow it, and do
  not: `pruneDetachedTree` seeds `WellKnownIds.ROOT_LIST` and `ROOT_CELL_LIST` **as well as** `rootListId` (a
  real root cell that is not a first occurrence is reachable from neither the synthetic root nor a detached
  parent, and without that seed the first edit boundary here would delete it), and the **colours** are solved
  over the live state
  (`TaskTreeView`'s `colorSource`) so a task is one colour in the list, the tree and the calendar (ADR 0013).
- **The synthetic list never escapes the projection.** `withTaskListCapturedFrom` drops it, which is what keeps
  it out of every history delta, out of the persisted payload and off the wire.
- **The window's expansion, selection and edit session are its own** (`taskListExpanded` /
  `taskListSelection` / `taskListEditSession`) — local view state, not persisted, not synced, no history unit.
  A row open here is not a row open in the tree, and an edit here never moves the tree's caret. **"Collapse
  all"** is the button that closes them; the flat list is what the window is for.
- **One gesture is ONE Main history unit** (a `TreeMutationDelta` labelled "All tasks"), like the template
  window's; the inner reduction's units evaporate with the projection. It edits the live tree, so it re-plans
  and syncs like any other tree edit.
- **Nothing may be moved into the root**: the order is the sorter's, so a drop there would be a reordering the
  next re-sort silently undoes. The blue line never appears at root level (`allowRootDrop = false`) and
  `reduceInTaskList` refuses such a move as the backstop.
- **A root row has NO Mode selector — it is always renaming.** The row IS the task, so "change task" there
  could only re-point a cell the user is not looking at. `reduceInTaskList` opens the session in
  `CellEditMode.Rename` (the one place that knows which cells are roots) and the window hides the selector; a
  cell that is not one of the rows opens on §4's default, as anywhere else.
- **The order is HELD STILL while it is edited, and "update order" is what re-sorts it.** Editing a row is
  what the rows being cells is for, so the list must not re-sort from under the cursor: the displayed order is
  pinned (Compose-only, like the sorter itself), a task created since is appended and one deleted drops out,
  and the button appears exactly while the pinned order and the fresh one differ.

### The Search window, and a task's last path

→ PRD §7 *Search*. `scheduler/domain/SearchDomain.kt` decides what is found and keeps the last path;
`ui/SearchWindow.kt` draws it.

- **"In a task tree" means ANY task tree** — the live one and every stored `TaskTreeEntry` — with the live
  tree's own membership predicate (a title-bearing cell reachable from the root, `shortestTaskTreePaths`'s
  walk). The **active** entry's snapshot is never read: it is stale by design and the live fields are that
  tree. A task only a stored tree holds is a result, named from that tree.
- **A path is the titles from the root down to the PARENT**, and its first segment is the **tree's name** (the
  active tree's, else the root task's title). The task's own title is the row's first section; repeating it
  would spend the width the two sections fight over.
- **`Task.lastTreePath` is AUTHORITATIVE** — once the cells are gone nothing can recompute it (the history is
  capped) — so it is persisted and synced with the task. It holds **titles, not ids**: the ancestors are
  routinely purged in the very gesture that cuts the task.
- **It is stamped in `SchedulerReducer.reduce` and nowhere else** (`SearchDomain.withLastTreePathsStamped`),
  on the account's own before/after states — never inside `reduceInTaskList`/`reduceInDefaultSubtree`, whose
  re-rooted trees read as every task leaving. A task in some tree before and in none after gets the
  **shortest** path it had (so several occurrences cut in one gesture keep the shortest); a task back in a tree
  loses its stamp; a blank-titled task (a cell being emptied, which the boundary purges) is never stamped.
- **Only what cannot be derived is stamped.** A task stranded under a stamped task — the sub-tree of a
  detached parent — reads its path off that ancestor (`SearchDomain.strandedPaths`: the ancestor's stamp, its
  title, the titles between), so the parent is stamped and the tasks under it are not, unless one's own
  shortest path was shorter. Stamping them all rewrote every task under a renamed parent — Change Task is the
  default mode, so a rename detaches — and put `ServerQuotaTest` over its egress budget (519.6 MB against 512;
  510.6 before the feature, 511.0 with this rule). A stranded task whose derivation breaks later (its
  ancestor purged or its cell moved) is stamped with the path it could be told at, at that boundary.
- **Only at edit boundaries.** While `editSession` or `taskListEditSession` is open nothing is stamped, and
  the reduction that closes one is measured from the session's `treeBefore`. Renaming a parent passes through
  a blank title between keystrokes; read mid-session, its whole sub-tree would leave and come back, and every
  task in it would be rewritten — and pushed — twice.
- **Forgetting is the ordinary purge.** No rule of its own: a task nothing references is removed by
  `purgeOrphanTasks`, and its path with it. Do not add a second retention rule for the path.
- **Cheap when nothing structural moved**: the tree fields are compared by identity, the tasks by title and
  sub-list only, and the membership walk is memoized on the identity of what it reads (each boundary's
  "after" is the next one's "before"). It runs on every reduction, the off-thread re-plans included.
- **Listing every path is bounded, membership is not.** `allPathsInAnyTree` walks a shared list once per path
  reaching it (that is what a mirrored parent's paths are), capped per task and by a visit budget, level by
  level so a cut drops the longest paths first. It is display-only; the stamp and the "not in any tree" logo
  read the exact walk. The window holds it on the tree fields, never per keystroke or per tick.
- **The rows are not cells.** Every row has one fixed height and the list's full width, so a row answers a
  cell's gestures in the form that fits — select, walk, open (`Enter`/double-click), `Ctrl+C`, the §13 menu's
  task entries — and never grows into an Edit Mode. Every action goes through the handler the rest of the app
  already has (`editTaskId`, the one `goToTaskTree`, `deepCopyCellId`, `editCategoryId`, `editPeriodKind`, the
  Alarms/Reminders windows): the window adds no second path to any of them.
- **The title prevails over the path** (`TaskResultRowLayout`): the title is measured first against
  everything but the logo and the thinnest path box, and the path box gets the rest. A `Row` cannot express
  this — it measures unweighted children first, which is the opposite priority.
- **The query, the checked kinds and the filters are local-only view state** (`SearchDomain.Config`, kept by
  `App` on the window's placement row — `popups.md`): the window reopens with them after a close and a
  restart, and they never sync. The selection is Compose-only, like the "All tasks" sorter.
- **ONE configuration, held by `App`, edited by two windows.** The Search window shows its text and types;
  the **Configuration Search** window (`ui/ConfigurationSearchWindow.kt`, opened from the Search window) lists
  every configuration of it — those two and the per-kind **filters** (`SearchDomain.Filters`) — in a General
  section and one section per kind (`SearchDomain.configurations`). Neither window keeps a copy, so a filter set
  in one narrows the other's list at once. A filter applies to its own kind's rows only, and every one has an
  "any" that filters nothing; the Search window's button counts the ones that are on.
- **The Configuration Search window has its own configuration** (`SearchDomain.ConfigurationSearch`: a bar
  over the configurations' NAMES, a kind selector over the sections, and "only the types in the Search
  results", `SearchDomain.kindsInResults`), local-only like the rest. The General section is never cut by kind.
- **The stored configuration is JSON with every field optional**; `decode` still reads the first shape (a line
  of kind names, then the query), and an unknown value falls back to "any".

### The default sub-tree

PRD §4/§7: one per account, grafted under every task the user **creates**. Off by default
(`defaultSubtreeEnabled`), authoritative, and outside `schedulingSignature` — a template schedules nothing
until it is applied to a real cell.

- **The template IS a real task tree** (`DefaultSubtreeTemplate`: a `TreeSnapshot` in the same shape a
  `TaskTreeEntry` stores, rooted at the same `WellKnownIds`). Do not turn it back into a tree of titles — a
  template row must be a real `Task`, or four of the five §13 menu entries have nothing to act on and "edit
  task" has nowhere to write.
- **The window IS the task tree — the same code, not the same look.** `scheduler/ui/TaskTreeView.kt` is the
  ONE tree, drawn **three times**: by `TaskSchedulerScreen` over the account's state, by
  `ui/DefaultSubtreeWindow.kt` over `projectDefaultSubtree()`, and by `ui/TaskListWindow.kt` over
  `projectTaskList()`. So it has every gesture, Ctrl+F included, and the **full five-entry §13 menu**. A second
  implementation is what shipped before, and it silently lacked the menu entirely. Add a tree feature in
  `TaskTreeView` and all three get it.
- **Nothing is dropped but the switch is added**: the percentage (the row's share **within the template**) and
  the minimum time are both shown and both meaningful, and the switch is one more column after them. Do not
  add a bin button: **the blank title is what deletes**, here as in the tree.
- **Two projections, and the split is the point** (`state/DefaultSubtreeProjection.kt`):
  `projectDefaultSubtree()` merges the live tree UNDER the template so a bound row resolves and the ordinary
  Change Task menu can offer live tasks; `defaultSubtreePriorities()` uses the template's cells/lists **alone**
  because `absoluteTaskPriorities` iterates every cell it is given and would otherwise divide the template's
  shares by the whole account. Ids cannot collide (child lists are `{taskId}/children`, cells come off a shared
  counter) except at the root, which the template shadows.
- **The fold back keeps only what is reachable from the template's root**, and that reachability is the whole
  safety property. The projection re-roots at the template, so the live tree's own top level is
  **unreachable** inside it and `pruneDetachedTree` deletes the account's entire tree in there; what protects
  the tree is that `withDefaultSubtreeCapturedFrom` never *reads* that wreckage. Do not replace this with a
  promise that some cleanup behaves — the guarantee is "only what the template's rows reach is written back",
  nothing weaker.
- **The walk splits at a task the live tree owns, and the live side is written BACK to the live tree.** A row
  pointing at an existing task draws that task's own sub-list — one task id, one sub-list — so editing under
  it is editing the account's tree, exactly as editing under a mirrored cell in the tree is. The template
  still copies **no part** of such a task (that is what would go stale); it keeps the binding, and the task
  plus everything below it crosses to the live half. A task minted under such a row is the **live tree's**:
  which side of the walk reached it is what decides ownership, and the live side never consults
  `ownedByLive`. The id **counters** are written back to both sides.
- **Every intent the window raises is wrapped in `InDefaultSubtree`** — except Undo/Redo, which belong to the
  app's stacks where the window's own `DefaultSubtreeDelta` units are waiting. One gesture is **one** Main
  unit; the inner reductions' units evaporate with the projection. That unit carries **both halves**
  (`DefaultSubtreeDelta.live`, usually empty): a gesture that reached the account's tree through a row
  pointing at a live task moved two things at once, and undoing one of them alone would leave the other
  standing. It is a plain `TreeDiff` applied by the tree's own machinery, and it is absent from every unit
  written before it existed — which is exactly what a template-only unit means.
- **`defaultSubtreeIsEmpty` lives on the STATE, not on the template.** A bound row's title lives on the *live*
  task it points at, so asking the template alone calls it untitled and skips a template that is anything but
  empty.
- **"Does this row carry a switch?" is `SchedulerState.isTitledDefaultSubtreeRow`, never `cell.taskId !=
  null`** — the window that draws the switch, the intent that flips it and the settle's "ends in a titled row"
  all ask that one function. Two rows answer no that the shorthand answered yes about. A row **emptied** keeps
  pointing at its now blank-titled task, and when it was the cell directly above the list's trailing
  placeholder it *becomes* the bottom cell (`applySetCellTitle` drops that placeholder — the inverse of
  Auto-Expansion), so deleting every row of a sub-list leaves one cell that is a placeholder to the tree and
  drew a switch here (2026-09-20, account 3); and the rows under a **bound** row are the LIVE tree's, whose
  cell ids `boundCells` is not keyed by — a switch flipped there wrote an entry the next fold dropped. The
  predicate resolves the title through the live tasks, exactly as `defaultSubtreeIsEmpty` has to.
- **A node's switch is `boundCells`.** Off ⇒ every grafted cell mirrors the row's own `taskId`; on (the
  default) ⇒ a fresh task per graft, carrying the row's title, fields, minimum time and weight row.
- **Every template sub-list is titled cells ending in ONE empty cell**, kept by the tree's own
  `evaluatePostEditCleanup` — never a template-only rule. The window runs it at its edit boundaries, but a
  switch-off row's task belongs to the LIVE tree, which can lose it with no template edit at all (a cancelled
  "New task" draft, a task-tree switch). `SchedulerReducer.settleDefaultSubtree` — after every reduction and on
  decode — empties a cell whose task resolves nowhere and runs that cleanup over the projection, so such a row is
  removed (with its switch), never drawn as an empty row in the middle of its list. The **ending** is healed
  there too: a sub-list whose last row is titled (`isTitledDefaultSubtreeRow` — a blank-titled task is not a
  title, and reading it as one re-folded the whole template on every reduction while
  `ensureTrailingPlaceholder` rightly did nothing) has nowhere left to type, so a placeholder is put back — on
  load as well as after any reduction, because the build that let a bound row eat one wrote that state to disk.
  It returns the same instance when nothing dangles and nothing ends titled; keep both checks to one lookup per
  template cell and per template list, they run on every tick.
- **An id row is NAMED from the tree the task LIVES in, never from the projection being drawn**
  (`changeTaskMenuEntries`' `namingSource`, `TaskTreeView`'s parameter of the same name — the shape
  `colorSource` already has, for the same kind of reason). A path answers *which* task of this title this is,
  which is a fact about the account; both projections re-root the state, so read off the drawing every live
  task is pathless — PRD §7's "All tasks" roots at its synthetic list, and the template shadows `ROOT_LIST`
  — and falls back to its child titles or its bare title. On the release account that is **sixty-odd rows all
  reading "planning"** (2026-09-17), the very flattening the path exists to prevent, and the row bound to the
  user's own task was labelled `main / planning / writing` after its place in the TEMPLATE instead of
  `root / long term / socialize / english / writing`, where it lives. So the path **and the titles along it**
  are read from the naming source — sharing `WellKnownIds.ROOT_TASK` means the root segment is otherwise
  titled by whichever tree is drawn — and only a task the account's tree does not hold (a template-owned row)
  is named from the drawing, which is where it lives. What is **offered** and what is **filtered** stay
  questions about the drawn tree: the cell, its siblings and its ancestors are the ones the user can see.
- **Renaming such a row renames the TASK** — the row draws the live task's title because it *is* that task,
  so the rename lands wherever the task is drawn. It was a silent no-op until 2026-09-21, for the same reason
  the sub-tree edit was, and that is now settled rather than open.
- **Emptying one is the exception, and must stay one.** A blank title is what deletes (PRD §4), so blanking it
  through the mirror would delete the user's task out of the account. Emptying **unbinds the cell** instead,
  the same branch a §8 tombstone takes (`applySetCellTitle`'s `keepAsTombstone`,
  `mirrorsLiveTaskInDefaultSubtree`). It used to do the damage the other way round: it blanked the live task's
  title, the cleanup then dropped the list's trailing placeholder (an emptied cell becomes its list's bottom
  one), and the fold put the live title back — the row was still there reading "writing", and the placeholder
  it had eaten was not (2026-09-17, account 3).
- **"Is this cell one of the template's?" is `SchedulerState.isDefaultSubtreeProjection`, never the cell id.**
  The template and the live tree can hold the SAME cell id — both start from the same bare tree, so
  `cell/root/0` is in each of them on a fresh account — and asking `defaultSubtree.tree.cells` alone says yes
  about the account tree's very first cell, which changed what Delete did to the real tree. The flag is set by
  `projectDefaultSubtree()` and by nothing else; it is never encoded, synced or persisted.
- **A row pointing at an existing task shows that task's OWN sub-tree** — a sub-list belongs to the task id —
  drawn by the tree as the ordinary mirror it is, and **edited** as one (above). What the GRAFT must not do is
  write the template's children into that task's sub-list: the row mirrors, so the template's own children are
  not applied under it.
- The chrome still lives in **one** place — `ui/TaskSheetChrome.kt` (`SheetColors`, `INDENT_STEP_DP`,
  `taskSheetGuideLines`, `TaskSheetExpandArrow`, `TaskSheetTitleBounds`).
- **The promise is made once, at `endEditSession`**, and only when the session **created** the task
  (`taskId !in session.treeBefore.tasks`). Not per keystroke (each one re-runs the naming), and not when the
  session reused an existing task (its sub-tree already came with the id). A sub-list that already holds a
  cell is never seeded.
- **Asking for a sub-tree while a cell is being edited ends that session first** (`ToggleExpand` is a PRD §4
  Forced Exit, like clicking another cell). Otherwise the arrow opens the just-named task onto its bare
  placeholder. The promise is made in the forced exit and paid by the toggle that follows it.
- **The graft leaves the cell it promised COLLAPSED.** Creating a task is not asking to see the template unfold
  under it: the row just typed would jump down the screen behind a block of rows the user did not write, on
  every single creation. `applySetCellTitle` already dropped the cell from `expanded` where it minted the
  sub-list, so `endEditSession` adds nothing back — only the gestures that *mean* to open it do (the arrow,
  Tab into the child, "add default sub-tree", which is the asking and therefore still expands).
- **The graft drives `applySetCellTitle` / `applyAssignTaskId`**, so occurrences, `childTaskIds`, the title
  index and auto-expansion stay owned by the code that already owns them. Never a second copy of those rules.
- **THE TEMPLATE IS OWED, NOT WRITTEN — and that is the whole of why the rule can be universal.** It
  appears under **every** new task id, in all three drawings of the tree and including the rows the graft
  itself lays down, so what it describes has no bottom. What a task stores is therefore a **promise**
  (`Task.pendingDefaultSubtree`): the template lists whose rows are owed here.
  `materializeDefaultSubtree` pays it the first time a gesture **opens** the cell (the expand arrow, Tab
  into the child), so an account holds exactly what has been looked at, and looking is what makes the next
  round exist.
  - Written eagerly it does not terminate. The §4 window dispatches against `projectDefaultSubtree()`,
    where the template **is** the tree, so a row typed there is a new task id and the whole template landed
    under it — and that copy was part of the template the *next* row pulled in. Four rows had become 41
    tasks nested `planning / AI / planning / AI / …`, and the `AI` row's id menu offered a second `AI`
    nobody had written (2026-09-21, account 3). The fixtures never caught it because `withTemplate` builds
    its rows with the switch still off.
  - **One gesture writes one round.** `applyDefaultSubtreeTemplate` lays the owed lists' rows and descends
    no further; each row it writes owes, in order, **its own template row's child list and then the
    template's root** — the copy of that row's sub-tree, and the "it is a new task id too" part. It calls
    the editing primitives *directly*, never the `SetCellTitle` intent, so nothing re-enters the reducer.
  - **A task that owes the template is still a LEAF**, which is what keeps it schedulable. A tree whose
    every task were born a parent would have no leaves at all, and the scheduler places leaves.
  - **The switch is read when the promise is PAID**, not when it was made — PRD §7's "whether the policy is
    *currently* applied". The promise itself waits. It is dropped **unpaid** once the sub-list holds a row
    of its own: the user built that sub-tree, and the template has nothing to add to it.
  - **Opening is two history units**: the rows (`TreeMutationDelta`, "Default sub-tree") and then the
    toggle. `ToggleExpandDelta` undoes by expanding again, so it can carry no tree mutation — the same
    shape a forced exit followed by the expand arrow already had.
  - §13's **"add default sub-tree"** writes its round there and then: that one is the asking, so it does
    not wait to be opened. It is one round like any other.
- A binding the live tree cannot honour (a task only the template knows, deleted, another task tree, or
  Constraint 2) falls back to a new task — the task is not in this list, so dropping the row would lose it
  silently. **Constraint 1 is the one exception and it SKIPS**: the list already holds that very task, so the
  row has nothing to add, and a clone would put its title in twice over a task already sitting there. Asked
  as `templateTaskId in siblingTaskIds(working, target)`, never as the whole of `canAssignTaskId` — the two
  refusals mean opposite things here.
- **Only a paste of FOREIGN text seeds** — the gate is the clipboard's **id**, not `PasteIdentity`. An id
  means the app wrote that text, so what is landing is a task's own content: a copied sub-tree comes back as
  itself whether it lands as a Mirror, a Restore, or a Fresh clone (`canAssignTaskId` refused the id here).
  Only a payload with **no id at all** — another app's tab-indented list, or a pre-1.6.0 clipboard — is a task
  the user is creating. `graftDefaultSubtree`'s empty-sub-list guard then keeps the clipboard's own children
  from being seeded over. The other internal `applySetCellTitle` callers still never graft.
- **The §13 menu's "add default sub-tree" is the explicit answer** (`AddDefaultSubtree`), and it is
  deliberately unlike the graft: it ignores the on/off switch (that switch governs the *automatic* graft, and
  this is the asking) and it does not care whether the task is new. It acts on `contextMenuCopyTargets` — the
  whole block inside a multi-selection, exactly as "copy" does — as one Main history unit. Offered only where a
  template exists.
- **"Is this row populated?" is `SchedulerDomain.isTextuallyEmptyCell`, never `cell.taskId != null`** —
  the whole-tree half of the rule `isTitledDefaultSubtreeRow` states for the template, and it binds every
  step of the graft: which cell is skipped as empty (`defaultSubtreeApplicationTargets`), which trailing row
  the template is typed into (`applyDefaultSubtreeTemplate`), and whether a sub-list counts as already built
  (`graftDefaultSubtree`, `materializeDefaultSubtree`). A row **emptied** keeps pointing at its now
  blank-titled task and *becomes* its list's trailing placeholder, so the shorthand found no row to type into
  in a sub-list whose last one had been emptied — and the whole reduction wrote nothing (2026-09-21,
  account 3: "add default sub-tree" did nothing at all).
- **It lands under the CELL THE MENU WAS OPENED ON, beside whatever that cell already parents — never on
  its descendants** (`defaultSubtreeApplicationTargets`, which is now a filter and no longer a walk). It used
  to fill the sub-tree's *leaves* instead, on the reading that a template says how a piece of work breaks
  down, so asking for it on a cell already broken down asks for it on the pieces. That is not how the gesture
  reads: right-clicking `why / how to measure improvement` wrote the rows into a grandchild two levels below
  it, a row the user never named (2026-09-21, account 3). To seed a piece, right-click the piece — this entry
  acts on what was clicked, like every other §13 entry. Each target is expanded, or rows landing at the
  bottom of its sub-list would be invisible.
- **A task is filled once, by id.** One sub-list serves every occurrence, so two selected cells pointing at
  one task are one sub-list: filling it twice would write the template in twice.

### Task trees are live alternatives, not backups

- `SelectTaskTree` **flushes** the live tree into the entry being left before loading the target.
- Stored snapshots **keep records** (`captureTreeWithRecords`); id counters take the **max** of both sides.
- All three mutation intents commit one `TaskTreeDelta` into the **Main** history.
- Identity-menu rows are told apart by `TaskTreeMenuEntry.Kind`, **never by `id == null`**.
- The first-startup tree is seeded **structurally**, not through `CreateTaskTree` — a default is not a user
  action and must record no history unit.
- Known scope limit: panels are not per-tree.

---

