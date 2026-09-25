# Priorities, task relations and categories

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Priorities

→ ADR 0004 (relative priority), ADR 0008 (the timeline blend).

- Right-clicking a cell's **percentage** opens "relative priority" / "priority weights" — not the row's §13
  menu. It consumes the press; the row's handler skips consumed presses.
- Relative priority with `t_r == MAIN` is **exactly** `absoluteTaskPriorities`. Keep that identity.
- Editing scales **percentages** by one common factor, solved by bisection on the measured tree — not by a
  closed form.
- **A factor first, an added term only where no factor lands.** `setChainsShare` is two stages. A factor
  cannot scale a **0** into a column, so a cell absent from one is capped at the absolute weight of the
  columns it does carry a value in (the release account: every root cell but two at 0 in a first column
  worth 90 %, so "50 % of root" was unreachable and came back as a refusal). Stage two adds **one common
  term to every weight value** of the same unpinned cells, which reaches the missing column and *removes*
  the cap — after it the cell has a value everywhere, so every later re-establishment is a factor again.
  The stages are ordered because only the factor leaves every ratio the user set intact, and the fallback
  is kept only when it lands **closer** than the factor did, so it can never undo one.
- **The column HEADER weights are not part of either stage**, and the header pins stay inert. Scaling them
  by the same factor is not monotone (the cascade `absₙ = hₙ·(1 − Σ preceding)` makes a *smaller* factor
  worth *more* to a later column while the same factor shrinks the cell inside it): on the release account
  it peaks at 1.6 % around `f≈0.7` and collapses to 0 by `f≈1.2` where the headers clamp at 1 — worse than
  the 10 % the cells alone reach, and no bisection can solve a hump. Adding to them is worse still: the
  clamp drives them to `[1, 1]`, which makes every later column worth **nothing** and takes every task
  living only there to 0 % (measured: eleven of the account's thirteen root tasks). The added term over the
  cells reaches every share those knobs could and touches nothing outside the chains.
- **A pin means "hold this percentage", not "leave this weight alone"** — a pinned cell's weight may have to
  rise. Solve each sub-list over every chain cell, pinned included.
- Pins are authoritative + synced, and **not** an Undo/Redo unit.
- **A chain link of the relative-priority window IS a task cell** — `TaskRow`, the tree's own row (the
  priority-weight table's rows are the same, below), so a cell
  reads the same wherever the app draws one (its colour, its border states, its columns). Two drawings of a
  cell is the drift `TaskTreeView` exists to prevent, and a chip was one. It is that row minus the three
  columns a chain has no use for: **no expansion arrow** (a chain is a path — there is nothing to open under
  a link), **no minimum time**, **no categories** (`showExpandArrow` / `showMinTime` / a null `categoryCell`
  — those three are the whole of what a row may leave out; do not grow a fourth). The **percentage column
  shows the cell's share of its OWN sub-list** in place of the tree's absolute priority — that is the factor
  the chain multiplies out — and keeps the tree's behaviour whole: clicking it opens that sub-list's weight
  window, right-clicking it opens the percentage's own two-option menu. The **pin** takes the column the
  minimum time and the categories vacate. The window has no selection, no Edit Mode and no §13 menu of its
  own, so the row's callbacks for those are given nothing to do: it is a *drawing* of the cell, and the pin
  and the percentage's two windows are the whole of what the user reaches through it.
- **The weight window's chart is the readout of the table beside it**: each task's share of THAT sub-list
  (`cellShare`), never its absolute priority. The slice sweeps were always normalized by the list total; only
  the legend's number was reading against the whole tree.
- **EVERY ROW OF THE WEIGHT TABLE THAT NAMES A TASK IS A TASK CELL** — `TaskRow` again, the tree's own row,
  the FOURTH place it is drawn (the default row below is the one row that names none). So a row is coloured by the task it names, opens the same Edit Mode, and renders its identity
  menu through the same `EditModeMenuBlock`. Configured, never re-implemented, and the configuration is three
  things: **`selectable = true` on every row** — `selectable` is what installs the row's gestures AND the one
  background that WINS over the task colour (ADR 0013), so the optional rows it was false on were both inert
  and the one kind of row that could not be told apart by its colour; **no Mode selector**, because a row
  names an existing task and naming it *is* pointing at it (the category field's reason, and there is no
  "New task" row either — a row states the share of a task that exists); and the weight fields, which ride
  `rowContent`.
- **The gestures are the TREE'S, not this table's own**: a press **selects** the row (the add row included —
  it is the tree's empty placeholder by another name), only a **double-click on the TITLE** opens Edit Mode,
  gated by `TaskRow`'s own `onTitle`, **typing a printable character on the selected row opens it seeded with
  that character** (a dead key opens it empty, the tree's own reasoning), and **Delete/Backspace empties the
  selected row, which is what removes it** — §4's blank title, raising the same one intent the editor's own
  blank exit does. The window takes the keyboard while it is open for those two (§ *Pop-up windows*). A press
  on a row that is not the one being edited closes that editor (PRD §4 Forced Exit). The selection is one
  row, Compose-only (there is no move, no copy and no keyboard walk here for a range to serve) — a way of
  looking at the table, never a fact about the account.
- **The keyboard reaches only the rows the TABLE owns**, exactly as the editing does: an optional row and the
  add row. A member row is a cell of the tree, drawn here and edited there, so Delete on one is left
  unhandled rather than deleting a task nobody opened this window to touch — and the add row holds no task,
  so Delete there is unhandled too rather than swallowed.
- **A row being typed into takes the colour of the task the draft RESOLVES to**, and none while it resolves
  to nothing. In the tree every keystroke commits the title, so a cell wears its task's colour as it is
  named; a weight-table row names an **existing** task and creates none, so the nearest true answer is the
  tree's own Change Task rule — the first eligible task the text matches, `selectedAssignTaskId` by another
  name. The row's CURRENT task is exempt from "already in the table" for that lookup
  (`eligibleWeightTableTaskIds`' `replacing`), or a row went colourless the instant its own editor opened.
  One measurement per keystroke, shared by the colour and the menu.
- **The table's identity menu is `eligibleWeightTableTaskIds`, NOT the cell's `eligibleAssignTaskIds`.** A
  row states a task's share of THIS list's parent sub-tree, so the predicate is a live occurrence chain under
  that parent (`optionalTaskPath`) — the one `SetPriorityWeightTableRow` itself enforces, asked of the menu
  so a refused pick can never be offered. A member cell (it has its own row) and an existing optional row are
  out either way.
- **THE DEFAULT ROW IS THE ONE ROW THAT IS NOT A TASK CELL**, and that is the whole reason it is drawn by
  hand (`WeightTableDefaultRow`) rather than through `TaskRow`. It names no task, so there is no title, no
  colour, no Edit Mode and no identity menu to give it — a row drawn as a task cell with all of that taken
  out is the inert row ADR 0013 already caught once. It is the **header row's counterpart at the other end
  of the table**: a label and one weight field per column, and no pin (a pin holds a share while a solve
  moves the others; this row is in no sum and no solve).
- **What the default row says is what a task ARRIVING in the table is given** — `CellList.defaultWeights`,
  1 in the first column and 0 in the rest until the user says otherwise, which is exactly what
  `SchedulerDomain.defaultWeightAt` used to hand out, so an untouched account behaves as it always did.
  "A task new to this table" is ONE question however the task got here, so **both** arrivals read the same
  row through the same funnel (`SchedulerDomain.defaultWeightRow`): a cell **named in the tree**
  (`applySetCellTitle`, at the instant a textually-empty cell stops being empty) and an **optional row**
  added here or from the task-relations list (`applySetPriorityWeightTableRow`, where the seed was a
  hard-coded zero). It is read at the **naming**, never when the placeholder cell was minted: a placeholder
  sits at the bottom of every list for as long as the list exists, so seeding it at creation would hand a
  new task whatever the row said before the user edited it. A cell that is merely renamed is not arriving.
- **It is a ROW OF THE TABLE, so every column operation carries it**: an added column is **0** in it, a
  deleted one drops its entry, a moved one takes its value along, a reset one puts `defaultWeightAt` back —
  the same four lines the cells' rows get, in the same reducer functions. And **Cancel restores it** with
  the headers and the weight rows, as part of the one table the window opened on.
- **It states nothing about what is already in the table**: no slice of the chart, no term in any priority
  sum, and **not in `treeSignature`** — editing it re-plans nothing. It is still the user's own setting, so
  it is authoritative + synced (picked whole per list by the merge, never column by column) and **one
  undo/redo unit**, with the keystroke that changes nothing recording none.
- **The trailing ADD ROW is the TABLE's placeholder, not the tree's** (`PriorityWeightTableRow.isAddRow`, and
  `priorityWeightRowId` gives it and every optional row a synthetic id). It used to borrow an empty cell of
  the sub-list and was therefore missing wherever the list had none — and "empty" was read as `taskId ==
  null`, where PRD §4's deletion leaves a cell holding a **blank-titled** task instead. Every one of the 14
  cells of the release account's root list carried a task, the last a deleted one, so the root table offered
  no way to add a row at all. A borrowed id is wrong twice over: it keys `TaskRow`'s per-row state and it was
  compared against the tree's live edit session, so the placeholder answered for a cell the user might be
  renaming elsewhere.
- **A row's identity is ONE intent, `SetPriorityWeightTableRow(listId, replacing, taskId)`** — add
  (`replacing = null`), re-point (both), remove (`taskId = null`) — so each gesture is one history unit and
  none can be half-done. **Emptying a row's title is what removes it**, PRD §4's rule read here, which is why
  the weight table is the first place an optional row can be taken back out at all; the task-relations ✕ is
  the second. A pick that changes nothing (a task the table holds, one with no chain under the parent, a
  `replacing` the table no longer has) commits **no** unit.
- **The commit is the identity menu's pick, never the typed text.** A weight-table row names an existing
  task, so a draft that matches nothing is dropped when the editor closes — and a blank one removes the row.
- **A WEIGHT-TABLE PIN IS THE ACCOUNT'S, NOT THE OPEN WINDOW'S** (`SchedulerState.priorityWeightPins`, per
  sub-list). It is authoritative + synced and **not** an Undo/Redo unit, exactly like the relative-priority
  pins above — it was Compose-only until 1.6.0, and "pin a value, close the window, open it again, the pin is
  gone" is the anomaly that ended that. It is the one part of this window that is NOT a reading of the table:
  the row selection and the row editor beside it stay in Compose for the reason stated above, and the
  distinction is whether the user is *saying* something or *looking* at something.
- **A pin names its column by INDEX, so every structural column edit has to carry the table's pins** —
  `remapPriorityWeightPins`, applied at the dispatch site of add/delete/move, reading the index each one acts
  on from the SAME helper the apply function reads (`addColumnIndex` / `deleteColumnIndex` /
  `moveColumnTarget`), so the pins can never land on a different column than the weights did. A reset moves
  no column and so moves no pin. It sits at the dispatch site and not inside the delta because
  `commitDelta` publishes `delta.redo(state)` and `TreeMutationDelta` carries the tree
  (`captureTree`: cells / lists / tasks) — a pin written inside the `mutate` lambda would be **discarded**.
  The price is the one place the two can disagree: undoing a column *move* puts the columns back and leaves
  the pins where the move carried them, which is the deliberate cost of keeping pins out of history. A pin
  naming a cell, a column or a list that is gone simply matches no field, so no tree edit prunes this map.
- **Its Cancel restores the table the window OPENED on** — every header and every weight row, in one step,
  never one edit back — as one ordinary `priorityTreeDelta`, which is what makes Ctrl+Z undo the cancel. A
  cancel that changes nothing records no unit. It rewrites that one sub-list's weights and nothing else: a cell
  that has since moved lists keeps its new table, and membership is never touched.

### The task-relations list

→ ADR 0004, PRD §5/§7. The **Task relations** window (opened from Search, no lateral-menu button since 2026-09-25); `TaskRelationsDomain` is the whole of
the rule and `ui/TaskRelationsWindow.kt` draws it.

A **relation** is a *pair*: a task, and the task its priority was expressed **relative to**. It is the pair
`RelativePriorityPinKey` already files pins under, asked as a question about the account instead of about one
open window — so `SchedulerState.taskRelations` is keyed by the deliberately separate `TaskRelationKey`.

- **Four sections, and they are a PRECEDENCE — a pair is in exactly one.** In order: **kept** (the user filed
  it by hand), **edited** (a live optional row of the target sub-list's weight table, or a percentage the user
  actually changed), **opened** (the relative-priority window was opened on it and left it as it was), and
  **broken**.
- **Broken outranks every other section, section 1 included.** It is a *status*, not an origin — the task or
  the target is gone (PRD §4's blank title), or the task has no occurrence under the target any more — and a
  pair the user kept is exactly the one they most need to be told has stopped meaning anything. `breakOf` asks
  the tree through `occurrenceChains`, so a pair is broken here exactly when the relative-priority window
  would open on "no occurrence of this task under …".
- **The row's button follows `kept` ALONE, never the section**: kept ⇒ **✕** (strike it off entirely), not
  kept ⇒ **keep**. That is what lets a kept-and-broken pair, drawn under section 4, still offer the way back
  out of section 1.
- **`TaskRelationMark` is three INDEPENDENT flags, not an enum, and its mere EXISTENCE is the fourth fact.**
  An all-false mark means "opened, never changed" — section 3 — so it is never dropped to save space, and the
  codec must round-trip it.
- **`retargeted` is the verdict of the LAST window session, not a running total.** The percentage field
  commits every keystroke, so the window re-reports the verdict on each one and typing a value then putting it
  back demotes the pair to section 3 — the user's rule is about where the number *ends up*. The comparison is
  against `percentFieldText`, the string the field shows: judging it on the raw `Double` would call a
  bisection landing one ulp away a change nobody made. The reducer returns the state **unchanged** when the
  verdict has not moved, which is what keeps the keystrokes off the save debounce and off the wire.
- **Only what cannot be recomputed is stored.** The weight-table half of section 2 is read straight off
  `CellList.optionalTaskIds` (`weightTableRelations`), so a row the user removes from a table leaves this list
  by itself, and section 4 is a question asked of the live tree. A pair naming a deleted task is **reported**,
  never pruned — no tree edit has to keep this in step.
- **`hidden` outranks every source EXCEPT a live weight-table row.** "Make it disappear from this list" means
  the list, and only a real **retarget** lifts it — merely looking at a struck-off pair again is not working
  on it again. A **row** is the exception because the ✕ now takes the row with it (below): a row standing
  under a `hidden` mark was put back *after* the strike-off — added again, or restored by the Undo of that
  very removal, the row being a history unit where the mark is not — and either way it is the user's own
  table asserting the pair again. It outranked rows too while the ✕ left them standing, because a pair struck
  off then came back on the next composition; that reason went with the removal, and keeping the rule cost a
  row on the release account's root table no line in this window at all.
- **THE ✕ ALSO REMOVES THE WEIGHT-TABLE ROW the pair was made of** (`withoutWeightTableRows`, the exact
  inverse of `weightTableRelations` — the same walk and the same `parentTaskIdOfList` reading, so the two can
  never disagree about which rows a pair is made of). An optional row of the target sub-list's table **is**
  the relation, so a mark alone would leave the user's own table asserting a pair they have just said is not
  theirs. The mark is still written: a pair reaches the list by two routes and only one of them is a table.
  It is the **second** way a row leaves a table, the weight table's own emptied row being the first, and the
  two go through the same reducer rule.
- **That row's removal is the ONE Undo/Redo unit this window has**, and it is not an exception — it is a
  **tree** change, the exact inverse of the `SetPriorityWeightTableRow` that added the row, which is a unit
  for the same reason.
  The marks stay outside history (they change no priority, exactly like a pin), and a pair with no such row
  commits **nothing** — no empty unit for Ctrl+Z to walk back over. It re-plans nothing either:
  `treeSignature` reads a list's `cellIds` and `weightColumns`, never its `optionalTaskIds`.
- Authoritative + synced (which pairs matter is a judgement nothing re-derives), merged **per pair as a whole
  value** — three flags that are one statement — and, the marks themselves, **not** an Undo/Redo unit: filing
  a pair changes no priority, exactly like a pin.

### Categories, and the rule that HOLDS a share

→ ADR 0004, PRD §5. `CategoryRules` is the whole of the rule; `ui/TaskCategoryField.kt` is the task cell's
field and `ui/CategoryEditWindow.kt` the category's own window.

A **category** is a label a task carries. A **category rule** is a standing statement about a share of a
sub-tree — *the tasks carrying this category under that task cell always come to 33 % of it* — which is the
relative-priority window's number said once and then **kept**.

- **A category is an OBJECT with an id, never a string on a task.** That is what makes the field a task cell:
  typing or picking a name the account already holds attaches **that** category rather than minting a second
  one under the same spelling, a rename writes one string and reaches every task at once, and a rule can name
  the object. `Task.categoryIds` holds ids only; the titles and the rules are `SchedulerState.categories`. An
  id naming a category the account no longer holds is **ignored**, which is what lets the merge resolve the
  categories and the tasks wearing them independently.
- **The lateral menu's *Categories* window is the account's own list, and the SECOND place a category is
  created** (`ui/CategoriesWindow.kt`, `CategoryRules.overview`). The task cell's field is *one task, every
  category* and the category edit window is *one category, every task*; this is the question neither asks —
  *which categories does this account have at all?* — and without it a category was reachable only THROUGH a
  task carrying it, so one carrying nothing (its last carrier deleted, a rule written before the tasks it is
  about) could be neither seen nor made. Four things it is: **title order**, which is the window's answer and
  not a fact about the account (the categories are stored in minting order, which the user cannot predict);
  a row saying what the category is *doing* — the carriers, the rules, and how many of those are **asleep**;
  a row's **✎** onto `CategoryEditWindow` and **no bin**, because a delete takes the label off every task and
  every rule at once and so belongs where all of them are shown — the same pair the task cell's category row
  and the resilience row both make; and the **same naming field** the "add" option is, whose identity rows
  here **open** a category (there is no task to attach one to) so that `CreateCategory` only ever mints a
  name the account has not got. A lateral-menu window like any other; the row's ✎ opens the window of ONE
  category, which is why pressing it on another row replaces it rather than stacking beside it
  (`popups.md`).
- **The "add" option is a task cell in Edit Mode, minus the Mode selector** — the same `EditModeMenuBlock`,
  with the account's categories as the **identity** rows and their titles as the **suggestions**. There is no
  Mode selector because neither of its questions exists here: naming a category IS pointing at it. The row's
  **bin** is about the TASK; the **✎** opens the category's own window, which is the one place a category is
  deleted — exactly the pair the resilience row makes with the period edit window, for the same reason.
- **The measure is the TOP-MOST carriers** (`chainsFor`): a carrier's whole sub-tree is its own, so a
  categorized task nested inside another is not counted twice — otherwise the figure is a sum that can exceed
  the sub-tree it is a share of. The downward walk descends into a task's sub-list **only from the cell that
  list names as its parent**, which makes it the exact inverse of `occurrenceChains`' upward climb; a mirrored
  list is entered once (re-walking it per occurrence is the exponential walk, arriving as a wrong number
  first), and a mirror cell that carries the category is still a chain of its own.
- **The rule is RE-ESTABLISHED after every intent, never recorded.** `reduce` is `reduceIntent` followed by
  `CategoryRules.settle`; the weights ARE the storage, so the rule and the tree cannot say two different
  things and there is no second mechanism to keep in step. Do not "fix" this by enforcing at the sites that
  might disturb a rule — there is no bounded such set. It costs nothing where there is no rule (an immediate
  return) or where every rule is met (a measure, then the same instance back).
- **The solve is `RelativePriorityDomain.setChainsShare`, which `setRelativePriority` is now a caller of.** So
  "adjust the priorities evenly" means here what it means in the window: one common factor over the cells on
  the chains, the rest of the sub-tree keeping its own proportions. Rules at nested scopes pull on each other,
  so the pass is iterated to a fixed point (deepest scope first) — there is no closed form.
- **A contradiction is REFUSED, and the refusal cannot wedge the app.** `settle` returns the state from
  *before* the intent with the reason in `categoryRuleError` (local-only view state, not even persisted,
  drawn as the app's one `MessagePopup`). Two guards: it **never refuses what was already broken** (a merge,
  an older payload, an earlier build's edit — else the user could not undo their way out), and a **dormant**
  rule is not a contradiction (the scope is gone, or nothing under it carries the category; deleting the last
  carrier is an ordinary edit). The four namable impossibilities are checked before anything is scaled, and
  all four are about rules sharing ONE scope, because that is where the arithmetic is closed. A share the
  weight COLUMNS put out of reach is deliberately **not** one of them: it bounds the factor, not the tree,
  and the added term above answers it — briefly a fifth check, removed the moment the case became possible.
- **The scope is a task CELL, and what it names is a LIST** (`CategoryRule.scopeCellId`, `null` = the whole
  tree). A task can appear several times in the tree, so "under Book" names no place when there are two of
  them: the window asks *under which task cell* and `CategoryRules.scopeEntries` offers every cell by its own
  **path** — the same walk `chainsFor` does, so a mirrored sub-tree is offered once while every mirror
  occurrence is still a row. What the cell then names is the sub-list its task owns (a sub-list belongs to the
  task id), and `CategoryRules.scopeKey` is that reading — the ONE place two scopes are compared. A rule
  sleeps once the cell it was written about is gone, even where the task still appears elsewhere: the user
  pointed at a place. A payload written when the scope was a *task* is migrated on decode through
  `firstTaskOccurrence`, and `scopeTaskId` is still written beside the cell so an older build can still read
  the rule. **The whole-tree scope is written BLANK** — a blank has always decoded as "the whole tree", in
  every build there has ever been, where the root's own id (`task/main` before the root rename, `task/root`
  after it) is a moving target a pre-rename build cannot resolve and would drop the rule over. A payload that
  still spells it `task/main` is rewritten by the root-id migration (`task-tree.md`) before it is read.
- **At most one rule per scope, and the scope is the LIST** (`scopeKey`, not the cell): `SetCategoryRule`
  replaces, so a rule written about one occurrence of a mirrored task replaces the rule written about
  another. Two statements about one sub-tree are the plainest contradiction there is — and two cells of one
  task show one sub-tree — so the window never lets one be made.
- **Which half is an Undo/Redo unit is the restrictive periods' split, not a new one**: defining, renaming,
  deleting a category and setting a rule are account settings and record **no** unit (as `AddPeriodKind` does
  not); a task **carrying** a category is a tree edit and records one (as its resilience does). The weights a
  rule moves are in no unit at all.
- **The two projections reduce through `reduceIntent`, not `reduce`.** The Search window's sub-trees and
  the §4 template are re-rooted trees, so a rule solved against one of them would be solved against the wrong root
  list; the settle they need is the one the outer `reduce` runs on the folded-back live state.
- Authoritative + synced, merged **per category as a whole value** (a title and its rules are one statement),
  with `categoryIds` merged as a membership list like `childTaskIds`. Not in `schedulingSignature`: a rule
  only ever acts through the weights, which are already in it.

