# ADR 0012 — The clipboard text is readable, carries the task id, and is deep by default

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *Copying a cell*.

One format serves every copy — the tree's `Ctrl+C` / `Ctrl+X` (PRD §4) and the cell contextual menu's **copy** /
**deep copy** (PRD §13). `SchedulerDomain.renderCopiedNodes` writes it, `SchedulerDomain.parseTreeText` reads it,
and `SchedulerReducer.reducePasteTree` is the only consumer of the parse.

## What a copy carries

Everything the cell's **edit window** holds — its **resilience** to every kind of restrictive period (which is
where the screen switch went, ADR 0001), the schedule unit, the task text — plus the cell's minimum time (§10)
and its priority-weight row and the weight columns of the sub-list it parents.
A paste has to be able to rebuild the task, not just its title; a copy that dropped the edit window's fields left
the user re-typing them at the destination, which is what the round-trip test
(`TaskCellCopyTest.deep_copy_pasted_back_restores_every_edit_window_field`) pins.

Those two weight lines are, between them, the **whole priority-weight table of every sub-list the copy walks**: a
table is a column header plus one value row per cell, and the header rides the parent node while each row rides
its own. Nothing else is needed and nothing is title-keyed, so a mirrored task carries its row at each occurrence.
`the_weight_table_of_every_copied_sub_list_travels_and_pastes_back` pins the round trip through a two-column
sub-list, header and all — a copy that restored the rows but not the header would silently re-normalize every
percentage at the destination.

## Readable, because the clipboard has two readers

2026-08-23. The first shape was written for the parser alone: per-line flags (`w=1.0,0.3`, `h=…`, `ns=1`) and
three appendices **keyed by task title**, delimited by a bare **form-feed** line, with each task's text escaped
onto a single line (`line one\nline two`). It round-tripped perfectly and was unreadable — pasted into a note or
a chat it was a wall of tokens, a control character no editor shows, and a note whose line breaks had become
backslash-n.

The clipboard's other reader is the **user**. So the format is now prose:

```
Deep work
	- minimum time: 45 min
	- resilience to no on-screen task: 100 %
	- schedule unit:
		- warm up: 5 min
		- run: 25 min
	- text:
		the note, exactly as it was typed
		second line
	Reading
		- minimum time: 30 min
```

A tab-indented **title line** per task; one level deeper, one `- <field>: <value>` line per thing the task holds;
the task text **verbatim** in its own indented block; the children after the attributes, at the title's own
indent + 1. Everything at its default value is omitted, so an ordinary task is a title and its minimum time.

Consequences that are load-bearing:

- **The attribute names are the format.** They live once, as the `ATTR_*` constants, and the parser matches those
  same constants. A second copy of the strings is exactly how a writer and a reader drift apart.
- **The per-task fields moved off the title-keyed appendices onto the node.** Title-keying meant two tasks that
  happened to share a name shared a minimum time and a text; a mirrored task now simply writes its fields at each
  occurrence, and the paste is per node.
- **A title that reads like an attribute line is escaped** (`- text:` → `\- text:`), so a task can never be
  parsed as one of its own fields. Tabs and newlines inside a title are escaped as before.
- **A text block is bounded by indentation, never by a sentinel.** Its lines sit one level deeper than the `-
  text:` marker, and a child task sits one level *shallower* — so a text can hold anything, including something
  that looks like a title, without a delimiter to collide with.

### Resilience is written as the difference from a FRESH task

`- resilience to <kind>: <n> %`, one line per kind whose value differs from `Task.DEFAULT_RESILIENCE` — what a
task the user has just created carries, which is `0 %` to `no on-screen task` and nothing else. Two defaults meet
here and they are different questions (`CLAUDE.md` → *Resilience is the whole of "where may this task run"*):
`PeriodKinds.defaultResilience` is a default for a **kind** (`1`, except `no task allowed`), and
`Task.DEFAULT_RESILIENCE` is a default for a **task**. The copy writes the difference between the two, which is
why an ordinary on-screen task's copy says nothing about a screen — exactly as its edit window reads — while the
example above, an off-screen task, says the one thing that is unusual about it.

Three consequences:

- **The kind is part of the attribute NAME**, so this is the one attribute matched by *prefix* rather than by
  equality. It is normalized (`PeriodKinds.normalize`) on both sides, so "Deep  Focus" and "Deep Focus" are one
  kind across the round trip.
- **A kind the destination account has never heard of is harmless.** The paste starts from the same defaults, so
  an unknown kind simply becomes an override that no period currently exercises — and it comes back if the user
  later defines that kind. Nothing is written to `SchedulerState.periodKinds` by a paste: defining a kind is a
  deliberate act in the edit window, not a side effect of pasting somebody's task.
- **A value equal to the kind's own default is dropped, not stored**, on both the write and the read, because
  `Task.resilience` holds overrides only.

The **pre-resilience attribute is still read**: `- can be done during a no-screen period: yes` is exactly a
resilience of `100 %` to `no on-screen task` (i.e. no override at all, since `1` is that kind's default) and `no`
is the `0 %` an on-screen task carries. A clipboard outlives a rebuild, so it is read and never written — the
same rule as the pre-1.6.0 form-feed shape below.

### Paste must still be a no-op for foreign text

The strictness is the only thing standing between `Ctrl+V` and a spreadsheet row becoming a task tree. Rejected
(⇒ `null`, ⇒ the reducer returns the state unchanged): an unknown attribute name, an unparseable value, a real
tab inside a title line, an attribute with no task above it, an indentation jump of more than one level. A plain
tab-indented **title tree** with no attributes at all still parses — that is a deliberate convenience, and the
missing minimum times stay `null` so paste leaves the fresh tasks at their defaults.

### The old shape is still read

`parseTreeText` dispatches on the presence of a form-feed line and hands those payloads to
`parseLegacyTreeText`. Nothing writes that shape any more. It is kept because a clipboard survives an app
restart: a user who copied under the previous build and pasted under this one would otherwise get silence, and
silence is what a rejected paste looks like.

## The copy carries the task **id**

2026-08-23, later the same day. A copy that carried everything *about* a task but not **which task it was** could
only ever produce a clone. Pasting a cut sub-tree back re-created it as new tasks: same titles, new ids, and every
mirror of those tasks elsewhere in the tree left pointing at the tasks that were cut. So each node now writes one
more attribute line, first, above the minimum time:

```
Deep work
	- id: task/user/41
	- minimum time: 45 min
```

`SchedulerReducer.pasteNodeInto` resolves that id into one of three identities (`PasteIdentity`):

- **Mirror** — the id still names a live, **titled** task, and `SchedulerDomain.canAssignTaskId` says this cell may
  hold it. The cell is pointed at that task. A sub-list belongs to the task id, so what shows under the pasted cell
  is the task's **own** sub-tree and the clipboard's children are not rebuilt — the same rule the default-subtree
  graft follows for a bound node. Its fields are not overwritten either: the clipboard's copy of them may be stale,
  and pasting a mirror of a task is not a way to rename it.
- **Restore** — the id is free: the task was cut, or the payload predates this tree. The task is rebuilt **under
  that same id**, fields and children and all, and `SchedulerState.reserveTaskId` walks the id counter past it so
  the next allocation cannot hand the id to a different task.
- **Fresh** — no id in the clipboard (a plain title tree, or a pre-1.6.0 payload), or one the tree cannot honour
  (it would put one task twice in a single sub-list). A new task carries the copied content, exactly as paste
  always did.

An id of any shape other than the `task/user/<n>` the app mints is **rejected at parse time** — `null`, so the
paste is a no-op. Without that, a hand-written clipboard could build a task over `task/root` or `task/main`, or
under an id the counter will never walk past.

The blank title is still what deletes (PRD §4), which is what makes cut → paste work: a cut blanks the titles, the
cells and their tasks are pruned, and the ids the clipboard names are free by the time the paste asks for them. A
task under the id that is **blank-titled** is treated as a husk rather than a task to mirror, so the clipboard's own
content comes back over it.

## Paste replaces the cell it lands on

Pasting onto a populated cell used to **rename** that cell's task to the copied title and then write the copied
children over its existing ones, top-down — a merge nobody asked for, and one that renamed the task at every other
place it was mirrored. A paste now *replaces*: the cell is re-pointed at the pasted task by forcing that id through
`applySetCellTitle`, which is the code that already owns re-pointing a cell. The task that was there keeps its
title and, if its sub-list is populated, stays a **detached parent** its id can bring back
(`SchedulerDomain.isDetachedParentTask`) — the ordinary consequence of a cell leaving a task, not a special case.

## The depth is the account's, and Ctrl+C is deep

2026-08-23, later the same day. Asking for a depth on every deep copy is asking the same question over and over: a
user has one answer, and it is a property of how deep *their* tree is. The number is now
`SchedulerState.deepCopyMaxDepth` — **one per account**, authoritative and synced (like the §7 switch, and like it
not an Undo/Redo unit). The deep-copy window opens on it and writes it back when it copies; cancelling leaves it
alone.

That is what lets **`Ctrl+C` never open a window**. **`Ctrl+X`** is the same copy, and then the same cells are
emptied — the PRD §4 deletion, so the ids are freed and one `Ctrl+Z` puts the sub-tree back; the history unit
reads "Cut". The menu's plain **copy** is depth 1, and **deep copy** is still the only thing that opens the
window.

### …but the chord itself is unbounded again

2026-08-24, from the spec. For one day `Ctrl+C` copied *to the account's depth*, which made the number mean two
different things at once: "how far this window copies" and "how far the chord copies". The three gestures now
divide cleanly by **how much**, and nothing else:

| gesture | takes |
| --- | --- |
| menu **copy** | the cell alone (one level) |
| menu **deep copy** | the window's number of levels |
| `Ctrl+C` / `Ctrl+X` | the **entire** sub-tree (`SchedulerDomain.FULL_SUBTREE_DEPTH`) |

A depth that silently truncated the chord is the failure this avoids: a user who set the window to 3 for one copy
would afterwards lose everything below level 3 on every `Ctrl+C`, with nothing on screen saying so. The account's
`deepCopyMaxDepth` is now purely the deep-copy window's own number.

## The window also asks *what* a copy carries

Three switches under the depth, all on by default (`SchedulerDomain.CopyOptions`):

- **copy the task ids** — the `- id:` line above.
- **copy the priority weight tables** — off, the two weight lines are replaced by the one number they exist to
  produce: `- priority in its sub-list: 37.5 %`, the cell's share of its own sub-list (`RelativePriorityDomain.cellShare`).
- **copy the task text** — off, the `- text:` block is left behind.

The deep-copy window also has an infinite-depth switch. When enabled, the finite maximum is ignored and the
whole reachable subtree is copied. Two additional switches independently control whether minimum-time fields
and the percentage representation are written. The percentage switch matters when priority tables are off;
the table switch is always labelled **copy the priority weight tables**, including when it is off. An exact
title filter can exclude matching tasks (and their copied descendants) from the output.

Three decisions there are load-bearing:

- **They are the account's, exactly like the depth.** The window edits them and writes them back on copy; every
  copy in the app — the menu's "copy", `Ctrl+C`, `Ctrl+X` — then obeys them. Scoped to the window alone they would
  be nearly unreachable, since `Ctrl+C` is the everyday gesture; and two sets of answers for "what does a copy
  carry" is exactly the drift the one-answer-per-account rule already exists to prevent. Cancelling changes
  nothing, as it does for the depth.
- **The percentage is stored as the node's single weight**, not as a fourth field. `copiedSubtree` writes
  `rowWeights = [share]` with the default one-column header, the renderer prints that number as a percentage, and
  the parser reads it back into the same single weight — so the reducer's paste path is untouched and a sub-list
  of shares rebuilds those very shares. It is rounded to two decimals of a percent **at copy time**, so a second
  round trip changes nothing.
- **Dropping the id makes the payload foreign, and that is the whole meaning of the switch.** The default-subtree
  gate is "did the app write this text?", answered by the presence of an id — so a copy taken with ids off pastes
  as *new* tasks and is seeded with the §7 template, exactly as if the titles had been typed. That is the honest
  reading of "do not carry which task this was", not a leak in the gate.

## The menu acts on the selection, not only on the cell under the cursor

2026-08-23, same day, from use: with a dozen root cells selected, right-clicking one and choosing "deep copy"
put a single line on the clipboard. PRD §13 describes the menu on "the cell", which the first implementation
took literally — but §4's `Ctrl+C` copies the **selection**, and the two are the same gesture to anyone using
them. A menu that silently drops eleven of the twelve selected cells is not a narrower feature, it is a wrong
answer.

`SchedulerDomain.contextMenuCopyTargets` now resolves what a right-click acts on: **inside** a multi-selection,
the whole ordered block — literally the one `orderedActiveSelectionInList` hands `copyTreeText`, so the menu and
the chord cannot drift; **outside** it, that cell alone. `copyCellsText` takes the list, and `copyTreeText` is
now a caller of it rather than a parallel path.

## "Deep copy" asks for its depth

The menu's **copy** is one level — the cell alone. **deep copy** used to be "the whole sub-tree", which on a deep
tree is both more than the user wanted and impossible to preview. It now opens a window first
(`DeepCopyWindow`): a **maximum depth** number (the copied cell counting as the first level), defaulting to
**20**, which the **reset** button restores; **Enter** or **copy** copies and closes; **Cancel** and the scrim
dismiss.

A number alone says nothing about a particular tree, so under it the window prints **one path** down to the
deepest level that depth reaches — `SchedulerDomain.deepCopyPathTitles`, starting from whichever of the copied
cells reaches furthest, since that is the one the depth actually bites on. Two details make it usable:

- The branch is chosen by the height measured over the **whole depth asked for**, not over the levels still to
  show. Measured over the remainder, every branch that reaches the current depth ties, and the path would jump
  between branches as the number changed; measured over the whole depth, raising the number **extends** the path.
- The line is **held at its deep end**. The levels being chosen between are the deep ones, so the parents scroll
  off to the left and a draggable horizontal scrollbar under the line brings them back. Its drag maps thumb
  travel to an **absolute** scroll position (`scrollTo`), not a raw delta, so there is no direction convention to
  get backwards.

## A paste seeds the default sub-tree only for text the app did not write

2026-08-24, from use, in two steps.

**First report.** With a default sub-tree defined and the §7 switch on, pasting foreign text onto a selected
empty cell and expanding it showed **nothing**. The graft only ever ran from `setCellTitleDelta` and from
`endEditSession` — and a paste onto a selected cell opens no Edit session, so nobody asked for the template.
That was a deliberate exclusion, and it was wrong: PRD §7 grafts under every task the user **creates**, and
pasting a title onto an empty cell creates one exactly as typing it does.

**Second report, same day.** The first fix gated on `PasteIdentity.Fresh`, which was still too wide: deep-copy
a sub-tree, paste it elsewhere, and the template turned up under the clones — because a copied id the target
list cannot honour (`canAssignTaskId` refuses a duplicate sibling, or the id belongs to another task tree)
falls back to Fresh. A copy of a sub-tree must come back **as itself**.

So the gate is the clipboard's **id**, not the identity it resolves to. An id means the app wrote this text, so
what is landing is a task's own content — Mirror, Restore and Fresh-clone alike. Only a payload with no id at
all (another app's tab-indented list, or a pre-1.6.0 clipboard) is a task the user is creating, and only that
seeds. `graftDefaultSubtree`'s own "sub-list still untouched" guard then keeps a node that brought children of
its own from being seeded over them, so it fires only on a bare new leaf — in a foreign forest, every minted
leaf, which is what typing the same titles by hand would give.

The graft does **not** expand the pasted cell, unlike the one at the end of an edit session: a paste can mint
many leaves at once, and expanding each of them turns one gesture into a wall of rows.

## "add default sub-tree" is the §13 menu's explicit answer

Narrowing the paste gate leaves a real need with no gesture behind it: a copied sub-tree that the user *does*
want the template under, or any existing cell that predates the template. `SchedulerIntent.AddDefaultSubtree`
is that gesture — a fourth entry in the cell's right-click menu, offered only where a template exists.

It is deliberately not the graft:

- the on/off switch is **not** consulted. That switch says whether *new* tasks are seeded without being asked;
  this entry is the asking.
- the cell's task need not be new;
- it applies to the **leaves** of the sub-tree the cell roots, a cell that parents nothing being its own leaf.
  The first cut appended the template beside the existing children, which reads wrong from use: a template
  says how a piece of work breaks down, so asking for it on a cell that is *already* broken down is asking for
  it on the pieces, not for a second copy of it alongside them. One rule now covers both cases.
- every cell it walked is **expanded**, since rows landing at the bottom of a collapsed sub-tree are rows the
  user cannot see.

It acts on `SchedulerDomain.contextMenuCopyTargets`, so right-clicking inside a multi-selection adds the
template under the whole block — the same rule "copy" and "deep copy" already follow, for the same reason. One
Main history unit for the whole set. The rows are built by `applyDefaultSubtreeNodes`, the graft's own builder,
so a seeded row still never seeds in turn.

`defaultSubtreeApplicationTargets` resolves the leaves, and two details there are load-bearing. It reads the
state **before anything is written**: filling a leaf gives it children, so a traversal walking the state it is
mutating would meet that same task again where it is mirrored, find it no longer a leaf, and seed the rows it
had just laid down — the cascade the graft avoids by calling the primitives directly, arriving by another
route. And it visits each **task id** once: a sub-list belongs to the id, so seeding it once is seeding every
occurrence of it, and the id set doubles as the cycle guard.
