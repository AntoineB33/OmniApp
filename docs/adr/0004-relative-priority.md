# ADR 0004 — Relative priority (PRD §5)

**Status:** active (2026-08-20). **Invariant summary:** see `CLAUDE.md` → *Priorities*.

"Relative priority" is a generalization of the absolute percentage the tree already shows.

## The percentage has its own right-click menu

Right-clicking a cell's absolute percentage shows exactly **"relative priority"** and **"priority
weights"** — NOT the cell's §13 "edit / copy / deep copy" menu, which stays on the rest of the row.

Both menus go through the same `contextMenuModifier`. The percentage's copy wins because Compose
dispatches to children first and it **consumes** the press; the row's handler skips a press whose changes
are already consumed (`press.changes.none { it.isConsumed }`). Without that, both menus opened at once.

"priority weights" is the window a left-click already opened. The two top-layer windows are mutually
exclusive (`weightWindowListId` / `relativeWindowCellId` in `App.kt`, same outside-press interceptor and
the same close-on-Edit-Mode rule).

## The model

`RelativePriorityDomain`, `scheduler/domain/RelativePriority.kt`.

The priority of task `t` relative to an ancestor `t_r` is:

    Σ over occurrences of t under t_r of Π (each cell's share of its own sub-list)

With `t_r == MAIN` that is **exactly** `absoluteTaskPriorities`. The identity is what makes the window's
default view the number the tree already shows, and `RelativePriorityTest` pins it.

An occurrence outside `t_r`'s sub-tree contributes no chain. The drop-down is root + the clicked cell's
ancestors, root-most first.

## Editing scales PERCENTAGES, not weights, by one common factor

The user's rule: "they all equally change" — pinned cells excepted.

Three facts make the closed form `Σ f^k · product = target` **wrong**, which is why the solve bisects on
the *measured* priority of the scaled tree instead:

1. An **only child** holds 100 % of its parent whatever its weight.
2. **No** cell can reach exactly 100 %.
3. Two cells of ONE sub-list can both sit on chains (two occurrences under two siblings), where their
   shares sum to 1 so they cannot both grow.

In all three the guarantee that survives — and the one the tests assert — is that **the typed number is
what the tree ends up holding**. The equal-factor property holds where it is attainable.

## A PIN is "hold this percentage", not "leave this weight alone"

This is the mistake to avoid. Scaling a cell changes its SIBLINGS' shares, so a pinned cell whose sibling
is on a chain has to have its own weight **raised** to keep its share.

`setRelativePriority` therefore solves each sub-list over **every** chain cell in it, pinned ones
included, with the pinned targets set to their current share.

Test: `a_pin_holds_its_share_even_when_an_unpinned_sibling_of_the_same_list_grows`.

## The pins are authoritative state

`SchedulerState.relativePriorityPins: Map<RelativePriorityPinKey, Set<CellId>>`, keyed per
(task, ancestor) pair.

- Persisted and synced (merged per key as a whole set), decoding to empty on an older payload.
- **Not** an Undo/Redo unit — pinning changes no priority.
- `SetRelativePriority` itself IS one ordinary `priorityTreeDelta`, however many cells it moved.
- A pin naming a cell that no longer exists is ignored rather than pruned.

## The field commits every keystroke

Like the weight table. That is safe only because the edit targets an ABSOLUTE value and the scale factors
compose (`retargeting_twice_lands_where_going_straight_there_would`) — 5 % then 40 % is exactly 40 %.

## The window's chart is the sub-list, not the tree

The pie chart on the right of the **priority weights** window shows each task's percentage **within that
sub-list** (`RelativePriorityDomain.cellShare`), not its absolute priority. The chart is the readout of the
table beside it, and the table sets shares of one list; reading the legend against the whole tree put two
different denominators side by side in one window.

The slices themselves never changed: their sweeps were always normalized by the sub-list total, which for a
set of shares of one list is 1. Only the legend's numbers moved.

## Weight-table inputs can be pinned while editing optional rows

Each number input in the priority-weight table has a local pin switch. When an optional task row is edited,
the edit scales the matching column along the task's path; pinned cell inputs are left at their current value,
so a parent task already shown in the table does not move unless its field is unpinned. These pins are scoped to
the open table and are not persisted or synced. Header inputs expose the same switch for a consistent table
shape, but headers are not cells on an optional-task path and therefore do not constrain that scaling.

## Cancel is a history unit, not an escape hatch

**Cancel** puts the whole table back to what it was when the window opened — every column header and every
cell's weight row — in one step, not one edit back. The window captures that table on the composition that
opens it (`remember(listId)` in `PriorityWeightWindow`) and holds it across every edit it makes, so the
target never drifts; the button is disabled while the table still matches.

It dispatches one `RestorePriorityWeights`, reduced as an ordinary `priorityTreeDelta` labelled "Cancel
weight edits" — **the same kind of unit as any other weight edit, which is exactly what makes Ctrl+Z undo
the cancel itself.** A cancel that would change nothing returns the state unchanged, so pressing it on an
untouched table cannot leave an empty unit for Ctrl+Z to walk back over.

The restore is scoped to that one sub-list's weights: a cell listed in the snapshot that has since moved to
another list is left to its new table, and the list's membership is never rewritten. Cancel undoes weight
edits, not tree edits.

## The pairs are collected: the task-relations window

**Status:** added 2026-09-03.

A relative priority is a statement about a *pair* — a task, and the ancestor its share is measured inside —
and until now every one of those pairs was thrown away the moment the window that raised it closed. The
weight table's **optional rows** are the same pair by another route (a task, and the sub-list's own parent
task), and they at least survived, but only inside the one table that held them. The lateral menu's **Task
relations** button is where they are gathered up: one flat list of every pair the priority machinery has
raised, so the ones that carry meaning can be kept and the rest recognised for what they are.

`TaskRelationsDomain` is the whole of the rule; `SchedulerState.taskRelations` is the only thing stored.

### The sections are a precedence, not four independent lists

`Kept` → `Edited` → `Opened` → `Broken`, and a pair is in exactly one of them. Two of the four decisions are
worth writing down:

- **Broken wins over everything, section 1 included.** It is a *status*, not an origin. A pair the user filed
  by hand is precisely the one they most need to be told has stopped resolving, and burying it under "kept"
  would hide the only thing about it that has changed. The row still shows section 1's own button, because
  the button follows `kept` and nothing else — filing a pair and un-filing it stay one gesture and its
  inverse, wherever the row is drawn.
- **`hidden` outranks every source of a pair — except a live weight-table row.** "Make it disappear from
  this list" means the list, not one of its sources, and only a real retarget lifts it: looking at a pair
  again is not working on it again. The row is the exception, and only became one when the ✕ started
  removing it (below): while the ✕ left the row standing, a pair struck off as a table row would have come
  straight back on the next composition, so the mark had to win. Now a row cannot outlive the strike-off, so
  one standing under a `hidden` mark was put back after it — re-added, or restored by the Undo of that very
  removal (the row is a history unit, the mark is not) — and that is the user asserting the pair again. The
  rule was left behind when the removal shipped, and the release account showed what that costs: a row live
  in the root table with no line in this window, and no gesture that would bring it back.

### The ✕ removes the weight-table row the pair was made of

**Status:** added 2026-09-05.

The mark above is only half the answer, and shipping only that half was wrong. An **optional row** of the
target sub-list's weight table is not a trace the pair left behind — it *is* the relation, the user's own
table saying "measure this task inside this sub-tree". Striking the pair off while the row stands leaves the
table asserting a relation the user has just disowned, with nothing but `hidden` holding it off the list; and
because no other gesture in the app removes an optional row, the row would then have been unremovable.

So `DropTaskRelation` also removes those rows. `TaskRelationsDomain.withoutWeightTableRows` is written as the
exact inverse of `weightTableRelations` — the same walk over the lists, the same `parentTaskIdOfList` reading
— so the derivation and its undoing can never disagree about which rows a pair is made of. The mark is still
written, because a pair reaches the list by two routes and only one of them is a table.

The row's own line says so before it goes. It is the second way a row leaves a table — the weight table's
own emptied row is the first (below) — and both go through the same reducer rule. It does not make the ✕ a
priority change: an optional row carries its value beside itself (`optionalTaskValues`) and no cell weight
moves, and `treeSignature` reads a list's `cellIds` and `weightColumns` and never its `optionalTaskIds`, so
nothing re-plans.

### What is stored is only what cannot be recomputed

Section 2 has two halves and they are deliberately unlike each other. The **weight-table** half is derived,
every time, from `CellList.optionalTaskIds` — which is what makes "if the user then manually deleted it, it
doesn't appear here" true with no bookkeeping at all. Section 4 is likewise a question asked of the live tree
(`breakOf` → `occurrenceChains`, the same walk the window itself opens on), so a pair naming a deleted task is
**reported**, never pruned: no tree edit has to keep this list in step.

Only the **relative-priority window's** half is stored, because nothing else can know it happened.

### `TaskRelationMark` is three flags, and its existence is the fourth

`kept`, `retargeted`, `hidden` are independent — a pair can be filed by hand *and* have been retargeted — so
this is not an enum. And the mark's **mere presence** is the section-3 fact: an all-false mark means the
window has been opened on that pair and left it alone. It is therefore never dropped as "empty", and the
codec round-trips it.

### The verdict is the last session's, judged on the displayed number

The percentage field commits every keystroke (above), so the window reports the verdict on every one of them
and a value typed and then put back leaves the pair in section 3. That is the user's rule read literally —
"changed manually but ends back to its value at the opening of the window" — and it costs nothing, because
the reducer returns the state unchanged when the verdict has not moved, so the keystrokes that decide nothing
never reach the save debounce or the wire.

The comparison is against `percentFieldText`, the string the field itself shows. Comparing the raw `Double`
would call a bisection landing one ulp from where it started a change the user never made.

### The marks are not an Undo/Redo unit; the row a ✕ removes is one

Same reason the pins are not: filing a pair changes no priority. It *is* authoritative and synced — which
pairs are worth keeping is a judgement nothing re-derives — and merges per pair as a **whole value**, since
the three flags are one statement about one pair (a field-wise merge could hand back a pair that is kept and
struck off at once).

The **weight-table row** a ✕ removes is the one thing here that is a history unit, and that is not an
exception to the rule above but the other side of it: the row is part of the *tree*, and adding it has always
committed one. An add that is undoable and a removal that is not would be the asymmetry, not the rule. A
strike-off that finds no such row commits nothing at all, so Ctrl+Z never walks back over an empty unit.

## The weight table's rows are task cells too

**Status:** added 2026-09-05, after three symptoms on the release account that were one bug each.

The priority-weight window's table has two kinds of row: the **member** rows, which are the sub-list's own
cells, and the **optional** rows, which name a task somewhere under the list's parent task whose share of
that sub-tree the table is being asked to state. Both are drawn with `TaskRow` — the tree's own row, the
fourth surface it serves — and the optional ones were configured wrongly in three separate ways.

### The add row was borrowing a cell of the tree

The trailing "add a row" placeholder was a real EMPTY CELL of the sub-list. That is two mistakes:

- **It is not the tree's placeholder.** PRD §4's auto-expansion keeps one at the bottom of every list, but
  "empty" there means *not populated* — and a cell whose task has a **blank title** is exactly what §4's
  deletion leaves behind. The borrow asked for `taskId == null` and found none: on the release account's root
  list all fourteen cells carried a task, the last one deleted, so the root table offered no way to add a row
  at all.
- **The id is not free.** It keys `TaskRow`'s per-row state and it was compared against the tree's live edit
  session, so the table's placeholder could answer for a cell the user was renaming somewhere else.

`PriorityWeightTableRow.isAddRow` is the row, `priorityWeightRowId` its synthetic id, and it is offered
whenever the list has a parent task to measure a share of — nothing about the tree can take it away.

### An optional row could not be edited, and had no colour

It was drawn `selectable = false`, which the row reads as "this is not something you can act on": it hands
the row the non-selectable fill — the one background that wins over the task's own colour (ADR 0013) — and
installs no pointer gestures at all. So the one kind of row in the table that names a task the user chose was
the one row with no colour and no way to change it. Every row is selectable now, and the table keeps a
one-row selection of its own to feed it: Compose-only, like the pins beside it, because there is no move, no
copy and no keyboard walk here for a range to serve.

### The gestures are the tree's, and so is the colour

Two follow-ups the same day, both cases of the table answering *nearly* like the tree:

- **A press selects; only a double-click on the title edits.** The add row opened on a single press for a
  while, on the argument that it carries no columns and so a press there can only mean "add a row". That is
  reasoning about this table when the whole point is that a row is a task cell — in the tree, a press on the
  empty placeholder selects it and nothing more. `TaskRow`'s own `onTitle` gate already tells a press on the
  title from one on the columns, so there was never anything to invent.
- **Delete on the selected row empties it, which removes it.** The same rule as the editor's blank exit and
  the same intent behind it — §4's blank title is what deletes, wherever a task cell is drawn. It reaches
  only the rows the table owns: a member row is a cell of the tree, so Delete on one is left unhandled rather
  than deleting a task nobody opened this window to touch.
- **Typing on the selected row opens it, which meant taking the keyboard.** The tree's third gesture is that
  a printable character on the selected cell starts renaming it — and with the weight window open that fired
  on the *tree's* selected cell, behind the pop-up. Worse, entering Edit Mode is what closes this window, so
  the keystroke meant for it dismissed it. A sort-2 pop-up is non-modal about the **pointer**; the keyboard
  belongs to whatever the user is working in. `TransientPopupHost.anyOpen` says one is open, `TaskTreeView`
  reads it (so all three drawings of the tree go deaf together, and take the keyboard back when it closes),
  and the window takes focus for itself so the character reaches the row it was typed at.
- **A row being typed into wears the colour of the task the draft resolves to.** In the tree every keystroke
  commits the title (`commitEditText`), so a cell takes its task's colour as it is named. A weight-table row
  cannot: it names an existing task and creates none. The nearest true answer is the one the tree's Change
  Task mode already gives — `selectedAssignTaskId`, the first eligible task the text matches — so the row
  shows that task's colour, and none while the text matches nothing. The row's **current** task had to be
  exempted from "already in the table" for that lookup, or opening a row's own editor blanked its colour
  before a key was pressed.

### A row's identity is one intent

`SetPriorityWeightTableRow(listId, replacing, taskId)` is add, re-point and remove — the same question a cell
of the tree answers with `SetCellTitle`/`AssignTaskId`, asked once so each gesture is one history unit.
Removing is **emptying the row's title**, PRD §4's rule read here rather than a button invented for it, and
it is what makes an optional row removable at all: before this there was no gesture anywhere that took one
back out of a table.

The commit is the identity menu's **pick**, never the typed text, and the menu is
`SchedulerDomain.eligibleWeightTableTaskIds` — the intent's own predicate (a live occurrence chain under the
list's parent) asked of the menu, so a pick the intent would refuse is never offered. Typing at it and
walking away therefore changes nothing, which is the sort-2 pop-up's rule about drafts anyway.

## Categories, and the rule that HOLDS a share

A **category** is a label a task carries; a **category rule** is a standing statement about a share of a
sub-tree: *the tasks carrying this category under that task cell always come to 33 % of it.* It is the same
quantity this record's window edits, said once and then kept.

### The scope is a cell, and what it names is a list

The scope is a **task cell**, not a task. A task can appear several times in the tree, so "under Book" is not
a place the user can point at when there are two of them — the window asks *under which task cell* and names
every offer by its own path. That is also what makes the picker readable at all: two different tasks may
share a title, and a bare title told them apart no better than it told two occurrences apart.

What a scope cell names is the **sub-list its task owns**, because a sub-list belongs to the task id. Both
halves are load-bearing and `CategoryRules.scopeKey` is where they meet: "at most one rule per scope" and the
grouping the structural contradictions are checked over are keyed on that LIST, so two cells of one mirrored
task are **one** scope. Keying them on the cell would let the user write two rules about one sub-tree — the
plainest contradiction there is, and one no scaling could even tell apart, since the two cells show the same
cells with the same weights.

The price is deliberate: a rule sleeps (`Status.ScopeGone`) when the cell it was written about is deleted,
even where the task still appears elsewhere. The user pointed at a place, and the place is gone; that is the
same softness a rule whose scope task was deleted already had, and re-pointing it is one gesture.

**Migration.** A payload written while the scope was a task is read through `firstTaskOccurrence` — the cell
"go to task" lands on — and `task/main` becomes the whole tree. `scopeTaskId` is still WRITTEN beside the
cell, so a build made before the change reads a rule it understands rather than a payload it cannot decode.

### A category is an object, not a string on a task

The field that names one is a task cell in Edit Mode: an identity menu and title suggestions. That is the
whole argument for the id. If a category were the string on each task, two tasks typing the same word would
carry two labels that look identical, a rename would have to walk the tree, and a rule could only ever govern
one of the spellings. With an id: the identity row attaches the category that exists, renaming writes one
string, and the rule names the object.

What the field has NOT got is the Mode selector. A cell's two modes are "rename my task" and "point at
another task"; naming a category *is* pointing at it, so there is one answer and no choice to offer.

### The measure is the top-most carriers

A category's share of a scope is `Σ chains Π cell shares`, over the chains that reach the **top-most**
carrying cells under that scope. The walk stops at a carrier, so a carrier's whole sub-tree is counted once
as its own and a categorized task nested inside another categorized one is not counted twice. Without that
rule the figure is a sum that can exceed the sub-tree it is a share of, and "33 % of it" stops meaning
anything.

The downward walk descends into a task's sub-list **only from the cell that list names as its parent**, which
makes it the exact inverse of the upward climb `occurrenceChains` does. A mirrored sub-list belongs to the
task and is entered once; a mirror cell that carries the category is still a chain of its own. Re-walking a
mirror per occurrence would be exponential (CLAUDE.md), and would arrive as a wrong number first.

### The rule is re-established after every intent, not recorded

`SchedulerReducer.reduce` is `reduceIntent` followed by `CategoryRules.settle`. The weights ARE the storage:
nothing about the adjustment is written anywhere, so the rule and the tree cannot say two different things
and there is no second mechanism to keep in step (CLAUDE.md § *State*). Undoing to an older tree simply
settles again.

The solve is the one this record already describes — `setChainsShare`, which `setRelativePriority` is now a
one-line caller of. So "adjust the priorities evenly" means what it means in the window: one common factor
over the cells on the chains, the rest of the sub-tree keeping its own proportions.

**Rejected: enforcing at the sites that might disturb a rule.** There is no bounded such set — every tree
edit, every weight, every paste, every undo, a peer's merge. One pass after every intent is the only
formulation with no hole in it, and it costs nothing on an account with no rule (an immediate return) or one
whose rules are already met (a measure, then the same instance back).

**Rejected: a closed form for nested scopes.** A rule at an outer scope scales cells that lie inside an inner
scope's list, so the two pull on each other. The pass is iterated to a fixed point (deepest scope first) and
reports a contradiction if it has not landed — an empirical answer, because no closed form exists.

### A contradiction is refused, and the refusal cannot wedge the app

An edit whose result no scaling can satisfy is not applied at all: `settle` returns the state from **before**
the intent, carrying the reason in `SchedulerState.categoryRuleError` — local-only view state, neither
persisted nor synced, drawn as the app's one `MessagePopup`.

Two guards make refusing safe:

- **it never refuses what was already broken.** A state can arrive contradictory from a merge, from an older
  payload, or from an edit an earlier build allowed. If `before` was already unsatisfiable, `after` is let
  through untouched — otherwise the user could not even undo their way out;
- **a dormant rule is not a contradiction.** A rule whose scope is gone, or that nothing under the scope
  carries, sleeps. Deleting the last carrier of a category is an ordinary edit, and refusing it would be the
  app holding a promise hostage.

The four impossibilities that *can* be named exactly are checked before anything is scaled, so the message
says which two rules disagree rather than "it did not converge". All four are about rules sharing one scope,
because that is where the arithmetic is closed (the shares of one sub-tree sum to 1): overlapping categories,
more than 100 % asked, 100 % asked while something else is under the scope, and less than 100 % asked while
nothing else is.

### The account's own list of them (the categories window)

Two of the three questions about categories had a surface: *which categories does this task carry* (the cell's
field) and *which tasks carry this category* (the edit window). The third — **which categories does this
account have at all** — had none, and the gap was not cosmetic: a category was reachable only THROUGH a task
carrying it, so one that nothing carried was invisible and unreachable, and the only way to define one was to
give it to a task first. The lateral menu's **Categories** button is that third surface
(`ui/CategoriesWindow.kt` over `CategoryRules.overview`).

- **Title order is the WINDOW's answer, not the account's.** `state.categories` is in minting order, which is
  what the merge and the codec preserve and what `menuEntries` deliberately ignores (it ranks by how well a
  row matches what is typed). This list is where a category is looked up by the name it is known by, so it
  sorts by title; nothing else reads that order.
- **A row says what the category is DOING** — its carriers, its rules, and how many of those are asleep —
  because that is what the tree cannot show. `ruleRows` is the same reading its own window prints, so the two
  cannot disagree about a rule's status.
- **No bin, and that is the same rule the task cell's row follows.** Deleting a category takes the label off
  every task carrying it and every rule about it; it belongs where all of them are on screen, which is the
  edit window the **✎** opens. The pair (a row about the object, a **✎** onto the object's own window) is the
  resilience row's pair with `PeriodKindEditWindow`, for the same reason.
- **Creating here is the field's create-or-attach with the attach half missing.** There is no task, so the
  identity rows **open** the category they name instead of attaching it, and `CreateCategory` is a no-op on a
  title the account already holds — a second object under one spelling being precisely what the id exists to
  prevent. It records no history unit, like every other account setting below.

### Which half is an Undo/Redo unit

The same split the restrictive periods already make, so there is one rule and not two: **defining, renaming,
deleting a category and setting a rule are account settings** and record no unit (as `AddPeriodKind` does
not), while **a task carrying a category is a tree edit** and records one (as the resilience a task is given
does). The weights a rule moves are in no unit at all — see above.

## A chain link is a task cell

**Status:** 2026-09-03. It used to be a compact chip — title, sub-list percentage, pin — on the grounds that
"the tree's row carries selection / drag / Edit-Mode / min-time plumbing that a horizontal chain has nowhere
to put". That argued about the row's *callbacks* and decided the wrong thing: the callbacks are parameters,
and what the user reads is the drawing. A chip carried none of what makes a cell recognisable — the task's
own colour (ADR 0013), the border states, the title/percentage columns — so one cell looked like two
different things depending on which window was showing it. That is exactly the drift `TaskTreeView` exists
to prevent, one level down.

`RelativePriorityChainCell` now calls **`TaskRow`**, the tree's own row, with the window's own answers:

- **Three columns are left out**, and they are the whole of what a row may leave out: the **expansion arrow**
  (`showExpandArrow = false` — a chain is a path, so there is nothing to open under a link, and the arrow's
  fixed 20 dp box has nothing to align), the **minimum time** (`showMinTime = false`) and the **categories**
  (no `categoryCell`, which the row already treats as "this cell has none"). A chain is about the priorities
  the window multiplies out; the rest is the tree's business.
- **The percentage column shows the cell's share of its own sub-list**, in place of the absolute priority the
  tree's rows show — the factor this link contributes to the number at the top of the window. Its behaviour
  is the tree's, unchanged: a click opens that sub-list's weight window, a right-click the percentage's own
  two-option menu. Both are hoisted to `App.kt` (`onOpenWeightWindow` / `onOpenRelativePriority`) because the
  two windows are sort-2 pop-ups sharing the top layer, so opening either closes the other.
- **The pin takes the column the minimum time and the categories vacate**, and the *active border* — the row
  already has one — is what says "pinned". The window has no selection of its own for it to be confused with.
- **The selection, drag-move and Edit-Mode callbacks are given nothing to do.** This is a drawing of the
  cell: the pin and the percentage's two windows are the whole of what the user reaches through it, and the
  §13 contextual menu stays with the surfaces that draw the *tree*.
- **The title column is sized to the link's own text** (clamped like the tree's). The tree sizes it per
  sub-list so that list's percentages line up; a chain link's neighbours are cells of *other* sub-lists, so
  there is no column here to line up with.
