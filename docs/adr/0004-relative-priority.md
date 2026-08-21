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

## Known deviation from the spec's wording

A chain cell is drawn as a compact chip (title + its sub-list percentage + the pin), not as a full
task-tree row. The tree's row carries selection / drag / Edit-Mode / min-time plumbing that a horizontal
chain has nowhere to put.
