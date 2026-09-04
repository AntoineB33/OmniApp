# 0013 — Task colours: the leaves own the circle

**Status:** active. Supersedes the *one colour space handed down the tree* rule (2026-08-27 → 2026-08-28).

`TaskColorSpace` is the whole of the rule; `TaskHueMemo` holds the answer between two edits; `TaskPalette` is
the only place a hue becomes something to paint with. The tree's cell and the calendar's panel read the same
hue for a task — a second derivation is how the two surfaces start disagreeing about what colour a task is.

## What a task colour is for

Two things, and they pull in opposite directions:

1. **Telling tasks apart.** Two panels side by side on the calendar, two rows in the tree — if their colours
   are close, the colour has told the user nothing.
2. **Saying where in the tree a task lives.** A branch should read as a family, so that the calendar answers
   "what kind of work is this afternoon" before it answers "which task exactly".

The rule below spends the circle on (1) for the tasks that need it most, and spends the *order* around the
circle on (2), which costs nothing.

## The rule

1. **The tasks with an empty sub-tree come first, and they are spread as far apart as they can be.** `n` of
   them take the `n` hues `i/n`, which is exactly the arrangement maximising the smallest distance between
   any two of them. They are the tasks the user actually works on, they are the many, and they are the ones
   that appear on the calendar — so the circle is theirs.
2. **Their ORDER is the tree's own depth-first order.** That is where "the closer in the tree, the closer in
   the colour space" comes from, and it is free: any order spreads them equally well, so the order can be
   spent on the tree without costing any separation at all. A branch is a contiguous run of the circle.
3. **Then every other task takes what is left, as far from all the others as it can get.** One at a time,
   most constrained first (narrowest sub-tree arc, ties by walk order), each parent lands at the point of its
   own sub-tree's arc furthest from every colour already given out.
4. **Where several answers are equally good, the one closest to the PREVIOUS answer wins.**

### Why a parent is confined to its own sub-tree's arc

Rule 3 says "as far as possible from everything" and rule 2 says "close in the tree, close in the circle".
Left unconstrained, rule 3 would fling every parent to the emptiest part of the circle — which is by
construction the part its own children are *not* in. The arc is where the two are reconciled: a parent is
free to be as far from everything as it likes, **within the stretch its own descendants occupy**. Its
territory is the smallest arc holding every leaf below it, widened by half a ring step at each end.

That half-step is load-bearing: without it, the parent of a single leaf would have an arc of exactly that
leaf's point, and the two would have to share a colour.

### Why the search is exact and not a sample

The distance to the nearest already-taken hue is piecewise linear along the arc, so its maxima are exactly
the **midpoints of the gaps** between consecutive taken hues, plus the arc's own two ends. Enumerating those
is the whole search. Do not replace it with a scan, a grid or a repulsion/annealing loop: there is nothing
between those points to find, and an iterative solver would also destroy rule 4 (below).

### Why the ties matter more than the placements

The circle has no privileged origin, and a gap has two equally distant halves. **Ties are the normal case,
not an edge case** — and broken arbitrarily they would repaint the whole tree on every edit, which is worse
than any colour choice. So the freedom is spent on staying put:

- **The ring's rotation.** Every rotation is equally good. The one chosen is the one that moves the childless
  tasks least from where the previous answer had them. A best rotation always puts at least one of them
  exactly back where it was, so the offsets that do that are the only candidates worth trying.
- **Each parent's pick.** Among the placements tied for "furthest from everything", the one nearest that
  task's previous hue.

`hues(state, previous)` is a **fixed point of itself**: feeding an answer back in reproduces it exactly
(`TaskColorSpaceTest.feedingAnAnswerBackInChangesNothing`). Without that, the debounced recompute would drift
the palette even on a tree that had not moved.

## Superseded: one colour space handed down the tree

The rule this replaces gave the root list the whole circle, split a list's arc between its cells **in
proportion to the childless tasks each sub-tree held**, and gave each cell the **average of its own arc** —
that same arc being the space its sub-list then divided.

It was elegant, and it had one defect it could not be rid of: **a parent's colour is the average of the arc
its children divide, so a child sitting in the middle of that arc has the very same average.** In
`TaskColorSpaceTest`'s fixture, `Book` and `Draft` were the identical hue. Siblings never collided, so the
pairs the hue could not separate were exactly the ancestor/descendant ones — which is why `TaskHue` carried a
**depth** and the palette spent it on lightness. That was a patch on the partition, not a fix: two tasks that
differ only in lightness are two tasks the user has to measure against each other.

It also spread the *leaves* only as evenly as the tree's shape allowed rather than as evenly as possible, and
it had no notion of a previous answer at all — every edit re-derived the palette from scratch and any change
to the tree's proportions moved every colour in it.

**Do not go back to averaging an arc**, and do not "fix" a collision by perturbing a hue: the placement is
the rule.

## The depth survives, for a different reason

`TaskHue` still carries the depth and `TaskPalette` still spends it on a step of lightness per level. It is
no longer what tells two tasks apart — the placement does that, and the hue alone always separates them now.
It is kept because a parent and the leaf it was placed beside are *neighbouring* hues by design, and a step
of lightness is what makes that read as a branch rather than as two rows the eye has to measure.

## Where the previous answer lives, and the debounce

`TaskHueMemo` (`ui/TaskHueMemo.kt`) holds it, and there is **one instance per tree**:

- `TaskHueMemo.account` — the account's own tree, read by the task tree **and** the calendar.
- a private instance for the PRD §4 default-sub-tree template, which is a different tree entirely. Sharing
  the account's would make each of the two trees the "previous answer" the other's ties are settled against.

The memo also **caches** the answer for the tree it last computed, keyed on `cells`/`lists`/`tasks`. That is
what makes the tree/calendar identity hold *by construction*: the second surface to ask about a given tree is
handed the identical map, not an independent derivation of it. The key is also the ADR 0009 guard — the
advance tick replaces the state object every second (records live on the tasks) and re-walking the tree on
each of those is exactly the per-tick cost the display hot path forbids.

`rememberTaskHues` adds the **debounce** (`TaskHueMemo.DEBOUNCE_MILLIS`, 400 ms): the first composition is
answered at once, and every later tree change waits for the tree to settle. A structural edit is rarely a
single event — typing a title, pasting a sub-tree or dragging a block of cells walks through a dozen
intermediate trees, and repainting every row at each of them is both a flicker and a walk of the whole tree
per keystroke.

## What stays true from before

- **The walk visits each LIST once and a colour belongs to the TASK.** A sub-list belongs to the task id, so
  a mirrored task is one list under many parents; re-walking it per occurrence is exponential *and* would
  leave the calendar panel, which knows only the task, with several colours to pick from. The first
  occurrence reached names the task and walks its sub-tree; a later one adds nothing. The visited set doubles
  as the cycle guard. (A mirror is still counted as one of its second parent's own when that parent's arc is
  measured, which is why an arc can wrap round the circle.)
- **Only populated cells take part** — an empty placeholder row and a blank-titled (deleted) task take no
  colour and no room.
- **The tree's tint is the row's RESTING background only.** Drag-move and non-selectable win outright — plain
  white is the strongest possible marker on a coloured tree.
- **Selection and Edit Mode are said in the OUTLINE alone**, so a cell that is the main selection, is among
  the selection, or is being edited keeps its task colour. They are told apart by the border's weight: 2 dp
  `activeBorder` for the main selection and the edited cell, 1 dp of the same colour for the rest of the
  selection, the ordinary 1 dp `grid` line otherwise. A selection fill repainted exactly the rows the user is
  working on — the one place the colours earn their keep — which is why it was removed rather than lightened.
- **The uniform §8 event blue is the fallback**, for a panel whose task the tree gives no colour. A no-screen
  or inactivity period takes no task colour at all: it is not a task.
- **Colours are DERIVED, never persisted or synced.**

## Why the calendar's grey periods are lines and not a fill

Recorded here as well as in ADR 0002 § *How grey is MARKED*, because it is the constraint that decides how
much of the colour space is usable: an inactivity period, a sleep window and all three screen breaks are
marked with **vertical lines, delimited**, and never with a wash. A filled band repaints whatever it covers,
so a task panel inside one (§17 projects the plan through a sleep window; a resilient task works through a
break) would lose its own colour to the marking — and the palette would have to reserve a corner of the
circle that no grey period could be confused with. Lines leave **every** colour available to the tasks.
