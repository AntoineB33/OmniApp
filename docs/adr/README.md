# Architecture Decision Records

`CLAUDE.md` states the active invariants — what you must not break. These records say **why**, what was tried
first, and which post-mortems produced each rule. Read the relevant one before changing a subsystem; the
"rejected" and "post-mortem" sections exist because those mistakes were made and are tempting to remake.

`CHANGELOG.md` (repo root) carries the dated log of what changed when, including the Supabase and SQLite
migration history.

| # | Record | Covers |
| --- | --- | --- |
| [0001](0001-scheduler-model.md) | The scheduler is a cyclic proportional-share model | claims, chunk scale, the resilience model, influence field, atomic block, resume contract, re-plan triggers |
| [0002](0002-calendar-layers-and-grey.md) | The calendar's two layers, and what GREY means | OS lock history as the layer source, assumed-unlocked default, derived grey bands, a panel's jump to its task |
| [0003](0003-screen-breaks.md) | Screen breaks: the three dynamic restrictive periods | one kind end to end, the three recurrence bars, rest stretches, the derived anchors, the two `t_p` modes |
| [0004](0004-relative-priority.md) | Relative priority (PRD §5) | the model, percentage-scaling solve, pin semantics, the task-relations list, categories and the rules that hold a share |
| [0005](0005-sync-and-merge.md) | Snapshot sync and the three-way merge | auto push/pull, catch-up on resubscribe, merge rules, lost-ack repair |
| [0006](0006-pause-cue-delivery.md) | Pause-cue delivery | presence `t_a`/`t_b`, e1 vs e2, the overdue gate, `device_break` |
| [0007](0007-accounts-and-persistence.md) | Accounts, persistence, authoritative vs. derived | guest accounts, per-account partitions, the reconstructibility rule, sleep carving |
| [0008](0008-task-trees-and-timeline.md) | Task trees and the timeline blend | live alternatives, keyframes, the quantized re-plan exception |
| [0009](0009-display-hot-path-and-horizon.md) | Display hot path, horizon, rolling calendar | O(screen) rule, horizon clamping, the rolling grid and its layout trap |
| [0010](0010-alarms.md) | Alarms and timers (PRD §18) | phone arming vs. desktop sweep, the synthesized tone, a timer as an alarm at an absolute instant |
| [0011](0011-global-keyboard-shortcuts.md) | System-wide keyboard shortcuts | the swallow rule, hook vs. `RegisterHotKey`, the shortcuts window |
| [0012](0012-clipboard-format.md) | The clipboard text is readable | what a copy carries, the prose format, the resilience attribute, paste strictness, the deep-copy depth window |
| [0013](0013-task-colours.md) | Task colours: the leaves own the circle | maximal spread for the childless tasks, depth-first order, a parent in its own arc, ties settled toward the previous answer |

## Related docs

- `docs/SCRIPTS.md` — the `scripts/` entry points, state dirs, deploy gotchas.
- `docs/PAUSE_CUE_DELIVERY.md` — the live runbook for the cue path (Firebase/APNs setup, on-device test steps).
- `docs/MANUAL_TESTING.md` — manual test procedures.
- `docs/PRD_TaskScheduler.md` — the product spec the section numbers (§5, §8, §9, §15, §17, §18) refer to.
- `ARCHITECTURE.md` — module layout and §8 the sync architecture.

## Writing a new record

Add a numbered file, link it from the table above, and put the one-line invariant in `CLAUDE.md`. Keep the
invariant and the record in sync: if `CLAUDE.md` says "never X", the record says why X was tried and what broke.
