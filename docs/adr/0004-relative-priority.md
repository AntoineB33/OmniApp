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
- **`hidden` outranks every source of a pair, the weight table's live rows included.** "Make it disappear
  from this list" means the list, not one of its sources; a pair struck off while it is a table row would
  otherwise come straight back on the next composition. Only a real retarget lifts it — looking at it again
  is not working on it again.

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

### It is not an Undo/Redo unit

Same reason the pins are not: filing a pair changes no priority. It *is* authoritative and synced — which
pairs are worth keeping is a judgement nothing re-derives — and merges per pair as a **whole value**, since
the three flags are one statement about one pair (a field-wise merge could hand back a pair that is kept and
struck off at once).

## Known deviation from the spec's wording

A chain cell is drawn as a compact chip (title + its sub-list percentage + the pin), not as a full
task-tree row. The tree's row carries selection / drag / Edit-Mode / min-time plumbing that a horizontal
chain has nowhere to put.
