# ADR 0012 — The clipboard text is readable, and "deep copy" asks how deep

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *Copying a cell*.

One format serves all three copies — the tree's `Ctrl+C` (PRD §4) and the cell contextual menu's **copy** /
**deep copy** (PRD §13). `SchedulerDomain.renderCopiedNodes` writes it, `SchedulerDomain.parseTreeText` reads it,
and `SchedulerReducer.reducePasteTree` is the only consumer of the parse.

## What a copy carries

Everything the cell's **edit window** holds — the screen switch (`onScreen`), the schedule unit, the task text —
plus the cell's minimum time (§10) and its priority-weight row and the weight columns of the sub-list it parents.
A paste has to be able to rebuild the task, not just its title; a copy that dropped the edit window's fields left
the user re-typing them at the destination, which is what the round-trip test
(`TaskCellCopyTest.deep_copy_pasted_back_restores_every_edit_window_field`) pins.

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
	- can be done during a no-screen period: yes
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
