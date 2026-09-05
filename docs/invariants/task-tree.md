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
- **The one sanctioned exception to "time never re-plans"** — and only because the cursor is **quantized**
  (`TASK_TREE_BLEND_STEPS` = 100). Do not reintroduce an unquantized/per-tick form.

### A sub-list belongs to the task id, not to the cell

- Every cell pointing at a task shows the **same** sub-tree — that is what mirroring is. So re-pointing a cell at
  another id must **not** delete the task it left: a titled, cell-less task that still holds a populated sub-list
  is a **detached parent** (`SchedulerDomain.isDetachedParentTask`), kept by `purgeOrphanTasks` and seeded into
  `pruneDetachedTree`'s walk. Assigning that id back restores its sub-tree.
- **The blank title is what deletes.** Emptying a cell (PRD §4 *Deletion*) blanks its task's title, and a
  blank-titled task is never a detached parent — that single rule is what still collects an emptied parent's
  sub-tree, and what keeps a peer's deletion sticking through `SnapshotMerge.repair`. Do not make the retention
  key on anything else.
- **Expansion is keyed by the CELL but the sub-list belongs to the TASK**, so a cell's `expanded` entry goes
  stale the moment it is given a different task's (or a brand-new, empty) sub-list. `applySetCellTitle` drops
  the cell where it **mints** that sub-list — a freshly minted sub-list is never shown expanded. A rename mints
  nothing and keeps its children on screen; the graft re-adds the cell in `endEditSession` once it has rows.
- **A Change Task menu row's PATH is walked over the cells, never over `Task.childTaskIds`**
  (`shortestTaskTreePaths` — one BFS, so the first path reached is the shortest, and each LIST is entered
  once). That denormalized field only tracks freshly-typed children, so a task that arrived by a move, a
  paste or an id assignment had no link into it and the BFS died at the root: **153 of the 163 tasks in the
  release account's tree were named by their bare title**, which turned the 64 tasks called "planning" into
  64 identical rows and flattened the menu's first sort key to the constant 1. It is asked once per menu and
  handed to the sort and to every row's label — it is a whole-tree walk, and the sort asks it per comparison.
- A task **the tree does not hold** is named by its child titles, never by a path (PRD §4) — and sorts
  **last**, after every row that has one. "Does not hold" is `shortestTaskTreePaths`, the same predicate the
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

→ ADR 0012. One format for the tree's Ctrl+C / Ctrl+X and both contextual-menu entries: `renderCopiedNodes`
writes it, `parseTreeText` reads it, and nothing else parses a clipboard.

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
- **It carries the task id too**, so a paste lands on the SAME task, not a clone. Three identities
  (`PasteIdentity`): the id names a live *titled* task this cell may hold ⇒ **mirror** it (a sub-list belongs to
  the task id, so its own sub-tree shows and the clipboard's children/fields are never written over it); the id is
  free ⇒ **restore** the task under that very id, and `reserveTaskId` walks the counter past it; no id, or one
  `canAssignTaskId` refuses ⇒ a **fresh** task, as before. An id that is not the `task/user/<n>` the app mints is
  rejected at parse time — never build a task over `task/root`/`task/main`.
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
- **The menu and Ctrl+C must agree about what "the cell" is**: a right-click INSIDE a multi-selection copies the
  whole block (`contextMenuCopyTargets`), exactly as Ctrl+C does; outside one, that cell alone. Copying only the
  cell under the cursor while a dozen sat selected is what shipped and was wrong.
- **The three gestures divide by how much, and nothing else**: the menu's "copy" is the cell alone (depth 1),
  "deep copy" is the window's number, **Ctrl+C is the ENTIRE sub-tree** (`FULL_SUBTREE_DEPTH`) and **Ctrl+X is
  that copy plus the §4 deletion** of the same cells (one history unit, "Cut" — which is what frees the ids a
  later Ctrl+V restores). Do not re-point the chord at the account depth: a number set for one deep copy would
  then silently truncate every later Ctrl+C.
- **The account's `deepCopyMaxDepth`** (default/reset 20, persisted + synced, not an Undo/Redo unit) is the
  **deep-copy window's** number — the window opens on it and writes it back when it copies.
- **What a copy carries is the account's too** — `CopyOptions`: `copyIncludeIds`, `copyPriorityTables`,
  `copyIncludeText` (all default on, persisted + synced, not Undo/Redo units), the deep-copy window's three
  switches. They govern **every** copy, the menu's "copy" and Ctrl+C/Ctrl+X included; scoped to the window they
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
- **The tree's tint is the row's RESTING background only.** Drag-move and non-selectable still win outright —
  a tint under either of them would be one more thing to read them against, and plain white is the strongest
  possible marker on a coloured tree.
- **SELECTION AND EDIT MODE ARE SAID IN THE OUTLINE ALONE**, and that is what keeps the tint readable where it
  matters most. A cell that is the main selection, is among the selection, or is in Edit Mode keeps its own
  background — its task colour — and is marked by its border's **weight**: the main selection and the edited
  cell take a 2 dp `activeBorder`, every other cell of the selection a 1 dp one of the same colour, and an
  unselected cell the ordinary 1 dp `grid` line. A fill repainted precisely the rows the user is working on,
  so "which task is this" was unreadable in the middle of a rename and a whole selected block lost its
  colours at once. `SheetColors.selectionFill` is now the find bar's latching-toggle fill and nothing else —
  do not put it back behind a cell.
- **The uniform §8 event blue survives as the fallback**, for a panel whose task the tree gives no colour. A
  no-screen / inactivity period takes no task colour at all: it is not a task.
- **Colours are DERIVED, never persisted or synced** — recomputed from the tree, like the percentages.
- **Grey periods are marked with LINES so every colour stays available to the tasks** (ADR 0002/0013): a wash
  over an inactivity period, a sleep window or a screen break would repaint the task panels a grey period may
  legitimately hold, and would cost the palette a corner of the circle.

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
  not: `pruneDetachedTree` seeds `WellKnownIds.MAIN_LIST` **as well as** `rootListId` (a real root cell that is
  not a first occurrence is reachable from neither the synthetic root nor a detached parent, and without that
  seed the first edit boundary here would delete it), and the **colours** are solved over the live state
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
- **The fold back keeps only what is reachable from the template's root**, stopping at a task the live tree
  owns — a mirror belongs to the live tree, and copying it in would start it going stale. The live half of the
  projection is **discarded**, which is what makes it impossible for anything dispatched in that window
  (`purgeOrphanTasks` included) to damage the real tree. The id **counters** are the one thing written back to
  both sides.
- **Every intent the window raises is wrapped in `InDefaultSubtree`** — except Undo/Redo, which belong to the
  app's stacks where the window's own `DefaultSubtreeDelta` units are waiting. One gesture is **one** Main
  unit; the inner reductions' units evaporate with the projection.
- **`defaultSubtreeIsEmpty` lives on the STATE, not on the template.** A bound row's title lives on the *live*
  task it points at, so asking the template alone calls it untitled and skips a template that is anything but
  empty.
- **A node's switch is `boundCells`.** Off ⇒ every grafted cell mirrors the row's own `taskId`; on (the
  default) ⇒ a fresh task per graft, carrying the row's title, fields, minimum time and weight row.
- **A row pointing at an existing task shows that task's OWN sub-tree** — a sub-list belongs to the task id —
  drawn by the tree as the ordinary mirror it is. Do not "fix" this by writing into the bound task's sub-list.
- The chrome still lives in **one** place — `ui/TaskSheetChrome.kt` (`SheetColors`, `INDENT_STEP_DP`,
  `taskSheetGuideLines`, `TaskSheetExpandArrow`, `TaskSheetTitleBounds`).
- **It fires once, at `endEditSession`**, and only when the session **created** the task (`taskId !in
  session.treeBefore.tasks`). Not per keystroke (each one re-runs the naming), and not when the session reused
  an existing task (its sub-tree already came with the id). A sub-list that already holds a cell is never
  re-seeded.
- **Asking for a sub-tree while a cell is being edited ends that session first** (`ToggleExpand` is a PRD §4
  Forced Exit, like clicking another cell). Otherwise the arrow opens the just-named task onto its bare
  placeholder. The toggle itself is skipped when the graft's auto-expand already answered the click.
- **The graft drives `applySetCellTitle` / `applyAssignTaskId`**, so occurrences, `childTaskIds`, the title
  index and auto-expansion stay owned by the code that already owns them. Never a second copy of those rules.
- **A seeded row must never seed in turn** — that is an unbounded cascade, not a deeper template. The graft
  calls those primitives *directly*, never the `SetCellTitle` intent, so it descends only through the
  template's own children and stops at its leaves. Never route it through the reducer's intent path.
- A binding the live tree cannot honour (a task only the template knows, deleted, another task tree, or
  `canAssignTaskId` says no) falls back to a new task.
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
- **It lands on the LEAVES of the sub-tree, never beside them** (`defaultSubtreeApplicationTargets`): a cell
  that parents nothing is its own leaf, so the plain case and the deep case are one rule. A template says how
  a piece of work breaks down, so asking for it on a cell already broken down asks for it on the pieces.
  Every cell walked is expanded, or rows landing at the bottom would be invisible.
- **The targets are read off the state BEFORE anything is written.** Filling a leaf gives it children; a
  traversal of the state it is mutating would meet that task again (mirrored elsewhere in the same sub-tree),
  find it no longer a leaf, and seed the rows it just wrote — the cascade, by another route. A task is visited
  **once, by id**: one sub-list serves every occurrence, and the id set doubles as the cycle guard.

### Task trees are live alternatives, not backups

- `SelectTaskTree` **flushes** the live tree into the entry being left before loading the target.
- Stored snapshots **keep records** (`captureTreeWithRecords`); id counters take the **max** of both sides.
- All three mutation intents commit one `TaskTreeDelta` into the **Main** history.
- Identity-menu rows are told apart by `TaskTreeMenuEntry.Kind`, **never by `id == null`**.
- The first-startup tree is seeded **structurally**, not through `CreateTaskTree` — a default is not a user
  action and must record no history unit.
- Known scope limit: panels are not per-tree.

---

