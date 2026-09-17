# 0015 — One device plans, and the others take its rules

Status: **implemented 2026-09-16, live path unverified** (the election, the adoption and the wire shape are
unit-tested; no two real devices have run it yet). Invariants: `docs/invariants/scheduler.md` § *One device plans*.

## The request

> When the app knows there are other devices unlocked (with the app open and logged to the same account), then it
> must determine which one must run the scheduler engine (for example each device could know the amount of CPU
> available in each device). Then, each time the scheduler returns a better set of rules, the websocket notifies it
> and every device gets the set of rules.

And, asked how a dead leader is noticed: *"When an app does a change in the database that require the scheduler
engine to run again, the server asks if each device is still present at the same time as pushing the update data
to them."*

## What was built

- **The question "who is here?" is asked only when a re-plan is due** — never on a timer. Every device notices the
  same re-plan through its own rule-change watcher (the change reaches it by the ordinary snapshot sync) and asks
  `ScheduleCoordinator.requestPlan`. The first to ask **probes** the account's channel; every device somebody is
  using **replies** with its `PeerCapability`; after one second the asker **ranks** them and **announces** the leader.
- **The ranking**: in use first, then the kind of device (desktop, other, phone), then the device's own measured
  speed (hours of plan per second of its fills, bucketed by powers of two so noise never swaps two leaders), then
  the device id. Every device ranks the same candidates the same way.
- **The leader plans in progressive stages and broadcasts each one**; every other device takes the newest rules for
  its own rule state in (`AdoptScheduleRules`). The follower's fill regenerates its own sleep windows, breaks and
  periods and lays the leader's runs through them — only the search is skipped. With a repeating part
  (`scheduleCycle`, ADR 0009) the follower's later extensions unroll it and never search either.
- **A leader that never answers costs one deadline** (10 s, the requirement's own pace): the waiting devices plan for
  themselves.
- **Offline, signed out, or the migration not applied**: the channel is not joined, and every device plans for
  itself at once — the previous behaviour, unchanged.

## Why not the literal proposals

- **"The server asks each device."** Supabase has no server-to-device request that waits for an answer: a database
  function cannot wait on sockets, and an Edge Function held open per re-plan costs an invocation and a wall-clock
  wait each time and a deploy to change. The device that makes the change is already on the socket and already
  knows a re-plan is due, so it asks — the same question at the same moment, with one decider so two devices can
  never pick different leaders.
- **"The amount of CPU available."** It is not portable (no common API on the JVM, Android and iOS) and it changes
  every second, so a leader chosen on it would change between elections for noise. What matters is how fast a
  device produces a plan, and each device measures exactly that on its own fills.
- **Presence with a heartbeat** (Realtime Presence, a presence row refreshed on a timer). Rejected in 2026-07-23 for
  the pause cue and for the same reason here: CLAUDE.md forbids timer-driven traffic, and the deadline gives the
  same guarantee — no device without a plan — at the only moment it is needed.
- **Sending the panels themselves.** A follower's breaks, sleep windows and periods are derived on the follower, and
  a copy of the leader's would overwrite this device's own observations (its live pause, what it saw unlocked).
  Sending the runs and laying them through the follower's own environment keeps one derivation of each.

## Known limits

- **The leader plans with its own observations.** The live pause, the no-screen evidence and the `t_p` mode near
  the line are the leader's. The follower never runs a task through a stretch its OWN environment refuses, but a
  stretch only the leader refused is left without a run on the follower until its next extension.
- **The presses stay local.** §7 "Switch task", §13 "start this task now", a sleep-schedule edit and a record
  removal re-plan synchronously inside the reducer on the device pressed, as before; the other devices learn of the
  press only if it changed their rule state.
- **Realtime broadcast has a message size limit.** A published set carries at most 2 000 runs; a longer plan is
  published only as far as it fits, and the followers extend the rest themselves.
- **The live path is unverified.** Before relying on it: apply migration 20260916000000 (`deploy-supabase.bat`),
  run two devices of one account, and check `Diagnostics.log` for "scheduler peers: channel joined", the election
  line and "adopting … runs".

Tests: `ScheduleCoordinatorTest` (the election over an in-memory bus), `SchedulerPeerProtocolTest` (the wire, and
what a follower lays).
