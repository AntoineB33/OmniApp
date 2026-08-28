# ADR 0003 — Screen breaks: the three dynamic restrictive periods

**Status:** active (rewritten 2026-08-27 to `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive
Period*). **Invariant summary:** see `CLAUDE.md` → *Screen breaks*.

Terminology: the eye-care breaks are named **"screen breaks"** everywhere (PRD §15) — UI, docs, code
identifiers (`ScreenBreak`, `screenBreak*`, `showScreenBreaks`, `DEFAULT_SCREEN_BREAKS`, …) and persisted
JSON keys. The legacy "side task" names were fully renamed; see `CHANGELOG.md` for the mapping.

The three breaks: the **20-s look-away**, the **5-min pose**, the **15-min pose**.

---

## What they are

**All three are restrictive periods of the kind `no task allowed`, end to end.** That is the README's own
sentence, and it settles what used to be three separate questions:

| Was | Is |
| --- | --- |
| the look-away accepted nobody | it accepts whoever is resilient to `no task allowed` — by default, nobody |
| the 5-min pose was a closed first minute, then the `doableDuringBreak` tasks | one span of one kind |
| the 15-min pose accepted every `!onScreen` task from its first second | the same one span of the same kind |

So there is no *shape* to read (`ScreenBreakPeriod` is gone, and with it
`screenBreakOpenStartMillis` and the "hollow tail" the calendar drew off it), and no `doableDuringBreak`
switch for a shape to read. A task works through a break exactly when it has been given a non-zero
resilience to `no task allowed` — the same sentence, and the same code path, as any other kind (ADR 0001).

**Do not reintroduce a per-break accepted set.** Every rule that needed one is now a resilience, and a
second mechanism for "where may this task run" is the thing the resilience model exists to prevent.

---

## Where they fall: the three recurrence bars

`DynamicPeriods` is the whole of it — a port of `side-dev/scheduler.py`'s `DynamicPlanner`.

- after **any** dynamic period, no 20 s period for **20 minutes**;
- after any **≥ 5-minute** stretch covered by "no on-screen task" *without any task*, no 5 min period for
  **1 hour**;
- after a **≥ 15-minute** such stretch, no 20 s period for **20 minutes** and no 15 min period for
  **2 hours**.

Where the bars would make two periods overlap — only the `t_p` drag can — **the whole chain is replaced by
its longest member starting at the chain's earliest point**. Touching counts as chaining: that is the
README's own example, a 20 s dragged until its end meets a 5 min is absorbed and the 5 min teleports 20
seconds backward.

### A rest stretch takes all three of its clauses

1. **covered by "no on-screen task"** — a period of that kind, or `no task allowed`, which turns the
   on-screen tasks away a fortiori. An emptiness of some *other* kind (a period nobody happens to be
   resilient to) is not one: the README names the kind, and only that kind rests eyes.
2. **without any task** — a period that still accepts somebody makes no stretch at all (the no-idling rule
   puts a task there), and neither does a pre-placed block: **a pre-placed task IS a task**, so an hour of
   maintenance bars nothing. The user was at the screen the whole time.
3. **a stretch, not a period** — two that abut make one (`growStretch`).

`blocked` and `rested` are deliberately two different sets. Everywhere nothing can be placed a dynamic
period is pointless and is pushed past (`blocked`); only the part of that which is a rest stretch bars what
comes after it (`rested`).

The timeline is taken to **start rested**, so the first 20 s may fall one bar after the origin, the first
5 min an hour after it and the first 15 min two hours after it.

---

## What this replaced, and why none of it comes back

The retired engine was a per-break `lastRest` anchor plus a grid simulation, carrying:

- a **cadence grid** per break, re-derived from its anchor;
- the **5↔15 merge** (the longer pose absorbs the shorter);
- **"a pause re-anchors shorter pauses"**, so a look-away always landed 20 min after a pose ended;
- **absorption** of an occurrence falling inside an already-placed longer one;
- a **decoupled-pose** special case for the fast-break debug shape, which could not self-recur;
- an explicit **cap** so a sub-minute interval could not flood the projection.

Every one of those is a way of saying *a rest bars the breaks that follow it*, which the bars say **once**.
The merge and the absorption are the chain rule; the re-anchoring and the decoupled pose are the rest-stretch
clause; the cap is the 20-minute bar, which no configuration can undercut.

It also makes the anchors **derived rather than stored** (CLAUDE.md's authoritative-vs-derived rule): the
placement reads the rest stretches out of the timeline itself, so the recorded past reaches it as periods and
a live pause reaches it as the period it is (`liveRestPeriod`) rather than as an overlay on every break's
anchor.

---

## Nothing slides, and that is what lets the cue key on the drawn start

A break's start is now a **fixed instant derived from the rules**, so it is a crossable boundary. The cue
therefore keys on it (`cueCrossings` → `screenBreakOccurrencesBetween`), and *what is announced and what is
drawn are one instant by construction* rather than two derivations that have to be kept in step.

This reverses the older rule in this ADR — "no break has a drawn start anything may key on" — and it is worth
being precise about why that rule existed. Breaks used to **slide**: an owed break sat at the now-line and
moved right with it (`screenBreakNextStart = maxOf(lastRest + interval, now)`), so its drawn start was never
crossed and the cue had to key on a separate anchored due. The reasoning was sound for a sliding period; it
does not apply to one the bars pin.

The rule that *does* survive, unchanged and load-bearing:

> **The boundary a trigger keys on must be a fixed instant derived from the rules** — never a position the
> placement recomputes every frame.

Two consequences the implementation must honour:

- **The sweep must be handed the same environment the fill was** — the standing restrictive periods and the
  tasks — and the same now-line anchor. Asked without them the bars answer a different timeline, and the app
  announces a break at an instant the calendar does not draw one at.
- **The sweep's self-delay reads the next placed start too.** Read off an anchor, it went looking for
  `lastRest + interval`, which the bars no longer put anything at: the sweep found no next boundary and
  stopped altogether.

### The placement origin is anchored on the now-line

The bars are a walk from an origin forward, so the grid they produce is a **function of that origin**: two
questions asked with origins ten minutes apart get two different grids. The fill asks from `now`, the cue
sweep from its scan floor ten minutes behind it, the calendar from the visible span's start.

`dynamicPlacementOriginMillis` therefore quantizes to the start of the UTC day before **an anchor every
caller shares — the now-line**, not the window's own left edge. Quantizing each window separately puts them
in different days whenever one straddles a midnight, which is precisely when the two grids part company.

And a **materialized break is never an input to its own placement** (`restrictivePeriodsOf` drops
`screenBreak` panels): feeding last fill's output back in makes each break a blocked stretch that absorbs the
next, and the grid walks away from itself on every pass.

---

## The two `t_p` modes

The README's `t_p` modes land on a control the app already has — the **"I'm away" toggle**.

- **Mode 1 (at the screen).** `t_p` may not be covered by "no on-screen task", so a period the line has
  reached is pushed ahead of it and becomes the half-open `(t_p, t_p + duration]` — the instant `t_p` itself
  is free while every instant after it is covered. The line goes on delaying it, placing tasks where it stood.
- **Mode 2 (away).** `t_p` must be covered, so the gap between the last period's end and the line is covered
  as **`no on-screen task`** — not `no task allowed` — which is what the README's own example asks for: the
  gap is filled with the tasks that have a non-zero resilience to that kind, and left empty if none have.

`sweepFromMillis` is where the line's continuous motion began, and the app sets it to `t_p` itself: the
lookback is a device for reading the rest stretches behind the now-line, not a claim that the line travelled
through them. Told otherwise, mode 1 would drag every period in the lookback onto the now-line and the chain
merge would collapse them into one.

---

## The past is a recorded fact

A break the app **conducted** — only the 20-s look-away is ever conducted — is recorded as a period
(`SchedulerIntent.RecordConductedBreak`) where it happened. Only on completion: a manual "Look away now" that
was superseded by a second press, or that the app stopped mid-run, leaves no trace at all. That asymmetry is
now structural rather than a property of an anchor that only moves on completion.

Everything else in the past is simply the placement asked about a window that has already gone by — the bars
are deterministic over the recorded environment, so the past placement *is* the placement.

---

## What is still true

- **Every break recurs after it ENDS**, not after it starts — the bars are all measured from a period's end.
- The **end** of a break is a notification, not only a voice cue.
- A screen-break panel has **no Edit** (there is no editable object behind it). A sleep band's menu leads
  with Edit.
- Staleness is judged only by a crossing's REAL age (`BoundarySweep`, 2-s budget), never by sim distance or
  scan-window position, and consecutive scans tile the timeline (`scanFloorMillis`) so no crossing can be
  clipped by a clock jump.
- A dynamic period **suspends** a chunk rather than cutting it (PRD §15), and does not count against "does
  the minimum fit?".
