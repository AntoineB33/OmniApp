# ADR 0017 — Rule structure: the break machine and the rule cursor

Status: accepted, 2026-09-30. Supersedes the placement half of ADR 0003 (the recurrence-bar walk).

## Context

`docs/scheduler_requirements.md` gained a § *Rule Structure* on 2026-09-30:

> **Event-Driven / Cursor-Based Evaluation:** Rules must be structured as sequential local branches and trigger
> boundaries (… an alarm/trigger for the next transition at $t$, at $now line$ mode switch, at history being rewritten
> by a program …).
> **No Global Lookups:** The runtime interpreter must never execute timeline-wide filtering, dynamic sorting, or
> interval-tree traversals. As the $now line$ moves forward, it simply evaluates the active local condition and
> advances a forward cursor to the next armed trigger point.

The app did the opposite at runtime. Its "set of rules" was the plan's panel list, and every move of the line asked
questions of it the long way:

- the current task was `panels.firstOrNull { covers(now) }`, the alternative a filter and a sort over every panel;
- every advance ran `AdvanceSchedule`, which scanned every panel;
- every advance re-ran the screen-break placement — `DynamicPeriods.instances`, a walk of the recurrence bars from a
  day-quantized origin over the environment — to bank what the line had met, and the cue sweep ran it twice more per
  scan (the poses' undragged dues and the look-aways' at-line run), plus once more for its self-delay;
- the mode was re-derived from the pause history at every tick.

The walk also carried most of the screen breaks' post-mortems. Re-deriving the past from an origin with the mode of
NOW is what needed the drag to "put down" an owed pose at the first stretch the line was not at a screen for, a chain
to have "outlasted" a break to keep it, the origin to be anchored on the banked front and quantized to the day, a
pull-back to floor its own bar, and the banked record to exist at all.

## Decision

**The three screen breaks are a forward state machine** (`BreakMachine`). Its state is local — a bar per break (the
earliest instant the rules let it start), the break the line is in, the one it drags, the stretch of "no screen" the
line is in, which continuous no-screen period took which break — and each rule of the requirements is a transition:
a break falling due, the chain rule joining or teleporting, a break ending (its own bar, the 20 s bar), a stretch
ending (the two stretch bars), the pull into a no-screen period (`max(now line, t_s)`), a mode switch (a drag entered,
a pose removed). The runtime moves it with the line; every prediction (the plan's obstacles, the calendar, the server's
pose windows) runs the same step function forward from the state the line is in. It is persisted with the banked
record's front, so a restart continues from it.

**The task side is compiled into a program read by a forward cursor** (`RuleProgram`): the timeline cut at every
boundary of a work panel, each piece naming the panel that holds and the alternative the rules name; where plan panels
end; the wind-downs; the reminder tags. It is compiled when the scheduler returns a new set of rules (a new panel list)
and read by a cursor that only steps forward.

**One interpreter** (`SchedulerEngine.interpretTo`) moves both, from the advance tick, from the cue sweep at the instant
it armed itself for, and at every journey step. Before the next armed trigger it does nothing but compare. A mode edge
and a history rewrite (a pause observed late, a record loaded) are triggers of their own. Compiling is the scheduler's
side and runs when the rules change.

Behaviour changes, each the requirements' own:

- A break is no longer pushed out of a stretch nobody can run in — *"placed everywhere in the timeline as earliest as
  possible"*; the walk's absorption was an older README's rule.
- A break falling due during a pause starts where the line meets it, never back-dated to the pause's start: the pull is
  `max(now line, t_s)` and the past is frozen.
- A line away goes on away in every prediction: one stretch of "no screen", each break taken once. The plan and the
  calendar read the same machine in that mode.
- Mode 2's dragged look-away and a pose falling due within its reach are one chain; the pose starts where the chain
  does, 20 s before its own due.
- A pose that teleports onto a drag is announced when it teleports.
- A machine with nothing to continue from starts rested at the line, its bars what the past it can see sets; nothing
  is banked behind the line's first moment.
- The wake journey walks the rules already held for its mode — the other mode class's plan, laid; the cycle, unrolled
  — and searches nothing (the user's choice between the two readings of *"similar to a case where no CPU were
  available"*).

## Rejected

- **Keep the walk and cache it.** A cache over a walk still answers the past with the mode of now, which is the root
  of the post-mortems above, and it still walks at every change of its key.
- **A per-mode precomputed branch tree** (a rule for "if the line switches to mode 2 at t"). The switch instant is
  unbounded, so the tree is infinite; the machine is its closed form, and the switch is a trigger.
- **Bank records at the cue sweep's exact instants.** It moved a record's push across the sync throttle and cost the
  heavy hour of `ServerQuotaTest` a round trip; banking waits for the tick, the trigger arms it.

## Consequences

- `DynamicPeriods` keeps only the vocabulary (modes, labels, bars, kinds). `dynamicPeriodPanels`,
  `screenBreakPanelsInWindow`, `screenBreakCueOccurrencesBetween`, `lookAwayHoldUntil`, `bankScreenBreaks` and the
  conducting-period environment are gone; `stepScreenBreaks`, `conductScreenBreak`, `absorbScreenBreakHistory`,
  `breakMachineAt`, `effectiveTpMode` replace them.
- Schema v17 (`16.sqm`): `screen_break_front.machine`.
- `ServerQuotaTest`'s heavy hour runs one sync round trip more than before (126 against 125 reconciles; egress
  511.8 MB of a 512 MB budget, from 508.7). The rows written are identical; the difference is where record pushes fall
  against the 10-s throttle.
