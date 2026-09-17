# ADR 0008 — Task trees: live alternatives and the timeline blend

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *Task trees*.

## The trees are LIVE alternatives, not frozen backups

`SchedulerState.taskTrees` is a list of named `TaskTreeEntry` (id `tree/{n}`, title, a `TreeSnapshot`, its
`expanded` set), and `activeTaskTreeId` says which one the live tree fields *are*.

| Intent | Behaviour |
| --- | --- |
| `SelectTaskTree` | **flushes** the live tree into the entry being left (`withActiveTaskTreeFlushed`), then loads the target — so each tree keeps everything done in it |
| `CreateTaskTree` | captures a **copy** of what is on screen, so the two diverge from the next edit on |
| `RenameTaskTree` | touches the name only |
| `SetTaskTreeDate` | puts the tree on the timeline (below) |
| `DeleteTaskTree` | the "All task trees" window's bin |

### Three load-bearing details

1. The stored `TreeSnapshot` **keeps the tasks' records** (`captureTreeWithRecords`, unlike `captureTree`,
   which strips them for Undo/Redo) — a task living only in an inactive tree has nowhere else for its record
   to be.
2. `applyTreeWithRecords` takes the **max** of both sides' `nextTask` / `CellCounter`, since ids are minted
   from one counter but live in every tree.
3. All the mutation intents commit ONE `TaskTreeDelta` into the **Main** history, which is what keeps a single
   history coherent across trees — undo walks units in order, so a `TreeMutationDelta` recorded under another
   tree can only be reached after the switch unit has already put that tree back.

Authoritative and synced (so a switch is account-wide, matching the live tree it moves); merged by whole object
per id, with `repair` clearing an `activeTaskTreeId` the merge didn't keep.

### Deleting

Deleting the LIVE tree keeps the live tree fields exactly as they are and only drops the name
(`activeTaskTreeId = null`), so the bin can never cost a tree's worth of work.

**Known scope limit:** the calendar **panels are not per-tree** — auto panels regenerate on the switch's
signature change, but a pinned/manual panel of another tree keeps its own title and a dangling `taskId`.

## The selector UI

The field right above the tree, built like a cell's task selection (PRD §4): a focus-gated block under the
field, in the user's order —

1. the Change/Rename **mode selector** (only once a tree is selected),
2. the **Task trees** identity menu,
3. the **Title suggestions** menu (contains-match; empty text lists them all).

The two menus differ in what a row DOES: an identity row *acts* (opens a tree, or creates one), a title
suggestion only *fills the field*.

**The identity menu is `Change task tree` mode only** (in Rename the field is naming the selected tree, so no
other tree is actionable) and, unlike the cell's Tasks menu, it is **not** limited to an exact match — it is how
a tree gets opened, so it lists every tree with a **similar** title (containment, most similar first, empty
field listing them all).

Its two leading rows are fixed (`SchedulerDomain.taskTreeMenuEntries`): **`tree-<today>` always first**, whatever
is typed, then **"New task tree"**. Rows are told apart by `TaskTreeMenuEntry.Kind`, **never by `id == null`** —
the Today row has a null id too on a day with no tree yet (it then *creates* rather than opens).

While that field holds focus it **owns the keyboard** — the tree's `onPreviewKeyEvent` returns early, or a letter
would open a cell's Edit Mode.

Tests: `TaskTreeSelectorTest`.

## `tree-YYYY-MM-DD` is also the first-startup name

`SchedulerDomain.defaultTaskTreeTitle`. `TaskSchedulerViewModel.prepareLoadedState` seeds one when the account
has no trees at all — **structurally** rather than through `CreateTaskTree`.

A default is not a user action, so it records no History Unit (like the sleep/screen-break seeding beside it) and
Ctrl+Z cannot land on it. One string for both, so on the day it was seeded the menu's first row **is** that tree
instead of offering a same-named duplicate.

It marks nothing dirty (every caller re-baselines `lastSyncedFingerprint` off the prepared state), so it rides
the user's next real edit to the server.

## The timeline: a dated tree is a KEYFRAME

`TaskTreeEntry.dateMillis` (nullable, authoritative + synced, absent on old payloads) puts a tree on the left-menu
**All task trees** window's timeline.

At `now`, `SchedulerDomain.blendedTaskPriorities(state, now)` interpolates **linearly** between the two bracketing
keyframes' `absoluteTaskPriorities`, so the plan transforms evenly and continuously from one arrangement into the
next instead of snapping over on the date.

- Outside the dated range the nearest keyframe holds (expressed as `from === to`, a *degenerate* blend, not a
  missing one).
- **No dated tree at all** returns `null` from `taskTreeBlendAt` — the "feature off" signal every caller falls back
  on, giving exactly the pre-timeline behaviour.

### Four load-bearing details

1. **Identity is the `TaskId`**, and a task absent from one keyframe is **0 % there** — that is what makes a task
   fade out (or in) across the transition. Retitling a cell *reuses* its `TaskId`, so "A renamed to C" is one task
   carrying its priority across both trees, not two tasks swapping; only a genuinely new cell is a new task.
2. **The leaves are the UNION of both keyframes** (`blendedSchedulableLeaves`). Without it a continuously-changing
   priority would still yield a *discontinuous* plan, since a task living only in the tree being transitioned TO
   could not be placed until that tree became live. Its attributes come from `blendedTaskAttributes`, where the
   live map wins **except over a blank-titled tombstone** — a task deleted from the live tree but alive in a
   keyframe must take its title from the keyframe, or its panels render "(untitled)".
3. **`datedTaskTrees` FLUSHES first.** The active tree's stored `TreeSnapshot` is stale by design between switches,
   so a keyframe that happens to be the tree on screen has to contribute what the user actually has.
4. **The dated trees are in `schedulingSignature`, content and date alike** (via the extracted `treeSignature`
   helper, so a keyframe is watched on the same fields as the live tree) — editing a dated tree that is NOT on
   screen changes the plan and must re-plan. Undated trees deliberately are not: nothing reads one until it is
   selected, at which point it *is* the live tree.

### The ONE sanctioned exception to "time passing must never re-plan"

By the user's spec the plan genuinely IS a function of time here, so a plan computed once would be wrong from the
next instant.

**Since 2026-09-13 it is boundary-driven, not quantized.** `docs/scheduler_requirements.md` § *Rule state
evolution* applies the rule state found at the now-line and changes every attribute at a constant rate, with an
example: a 0→100 % ramp over 10 min and a 0→50 % ramp over 5 min must give the same schedule while they overlap. The
100-step cursor could not honour it — its steps land at different instants on the two ramps. So a plan now holds the
EXACT rule state at the line (`RuleStateTimeline.planTasksAt`, the minimum in millis), and
`SchedulerEngine.launchTaskTreeBlendReschedule` re-plans when the line reaches the start of a run the plan placed
(`SchedulerDomain.taskTreeBlendDecisionKey`, sleeping until `nextDecisionMillis`, bounded by
`TASK_TREE_BLEND_POLL_MILLIS`). Every decision the frozen past records is then taken with the rule state at its own
instant, and the two scenarios agree (`TaskTreeTimelineTest.the_same_slope_gives_the_same_schedule_while_the_two_transitions_overlap`).

A transition costs one fill per run it spans, and nothing at all outside one: the key is 0 when nothing is dated
and constant outside a transition, so an account that never opens the window never dispatches.

(Before: `taskTreeBlendStep` sampled every 60 s, `TASK_TREE_BLEND_STEPS` = 100, a fill per step — about one every
14 h on a two-month transition. Removed.)

What the original rule protects against — a *continuous* input churning the plan every tick — is handled by
firing only at run starts, which the plan itself spaces. **Do NOT reintroduce a per-tick or a stepped form.**

### Deliberate disagreement

The tree on screen keeps showing its **own** `absoluteTaskPriorities` (that is the arrangement being edited), so
the `%` column and the blend deliberately disagree mid-transition.

`manualAddTaskId` (the §8 pick) also still reads the live tree's own priorities — it is carved out of the §9 model
already, and giving it a `now` would ripple through `CalendarUi` / `App` for a UI convenience.

## Tests and UI

- `TaskTreeTimelineTest` — the blend.
- `BreaksAndSlidingPrioritiesTest` — `side-dev` **test 13** is exactly this feature ON TOP of test 12's break grid
  ("at t_p the scheduler is done to satisfy the priorities that are at exactly t_p"). Each half being pinned
  separately does not say the two hold at once: that file is the fill with both in force, and it is what to extend
  rather than re-deriving the blend when a break rule moves.
- UI: `ui/TaskTreesWindow.kt` (timeline + tree list; clicking a tree opens a second small window with its
  `YYYY-MM-DD` date field and the bin), `FloatingWindow.TaskTrees` in `App.kt`.
