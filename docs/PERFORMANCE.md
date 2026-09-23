# Measuring performance

How to find out what the app is actually spending time on, rather than guessing. Active invariants about
the hot path itself are in `docs/invariants/display-hot-path.md`; this file is about the instruments.

There are two tools, and they answer different questions.

| Tool | Question | Where |
| --- | --- | --- |
| **The in-app overlay** | what is it doing *right now*, while I do this | `scripts/perf-profile.bat` |
| **The headless benchmark** | what does one derivation cost, and does its cost follow the screen or the account | `./gradlew :shared:jvmTest --tests "*PerfBenchmarkTest*" -i` |

---

## The in-app overlay

```
scripts\perf-profile.bat                              (account 2's data)
scripts\perf-profile.bat %USERPROFILE%\.omniapp-perf  (a scratch state dir)
```

The script refuses the release state dir outright (CLAUDE.md: the deployed app's DB is live state) and
launches with **time simulation OFF**. That second part is not incidental: the sim clock ticks the now-line
~20x a second, which inflates every display-path cost by more than an order of magnitude over what a user
ever pays. A profile taken under the simulator measures the simulator.

**Profile a large, realistic account.** An empty one hides every cost the tool exists to show.

The panel reads, once a second:

- **frames** — fps from the median frame interval, the p95 interval, and a jank count (intervals longer than
  two 60 Hz frames, i.e. visible hitches rather than merely slow frames). It is driven by `withFrameNanos`,
  which *observes* the frame clock rather than requesting frames, so an idle app reads as idle instead of
  being pinned at 60 by its own meter.
- **runtime** — process CPU as a fraction of all cores, heap used/max, GC count, thread count.
- **sections**, ranked by **milliseconds spent per wall-clock second**. This is the number that decides
  smoothness and it is the reason the overlay diffs two snapshots rather than showing totals: a derivation
  costing 4 ms is free at 1 Hz and is 40% of a core at 100 Hz, and only the rate tells them apart. A budget
  of 1000 ms/s makes the share legible directly; anything above ~100 ms/s is coloured.

Buttons: **more** expands to counters, live collection sizes and growth trends; **reset** zeroes every
accumulator so a measurement can be scoped to one gesture ("reset, then drag the calendar for five seconds");
**gc** takes a leak sample now; **dump** writes the whole report into `diagnostics.log`, where
`collect-diagnostics.bat` merges it with the calendar-band timeline — so a stutter and the sync or derive
that caused it read as one story.

### Reading the counters

`recompose.App` is the one to look at first. Everything the App body derives runs once per pass through it,
so how often that counter ticks is half of what the derivation costs. At rest it should track the display
resample (about once per 30 s at the default zoom — see `displayResampleDelayMillis`). Anything materially
above that means a state read in that scope is being written by something other than the clock.

`recompose.DayColumn` divided by seven is how often the calendar itself recomposed — compare it against
`recompose.App` to tell a derivation problem from a drawing problem.

`recompose.TaskRow` divided by `recompose.App` is **how many task rows re-composed per pass through the app's
body**, and it is the second thing to look at. It should be about one per row the change actually altered —
one on a keystroke, none on an engine tick. One per row ON SCREEN (44 on the release account) means a row
argument Compose cannot prove unchanged is being rebuilt on every pass, which costs ~20 ms a frame and is the
shape `docs/invariants/display-hot-path.md` names. `compose.TaskSchedulerScreen` and `compose.TaskTreeView`
are the milliseconds behind that count — the screen's whole composition and the tree's. There is deliberately
no per-ROW measure: it would instrument the hottest loop in the app to say what the count already says.

`reduce.<IntentName>` is timed per intent class, deliberately. "The reducer costs 30 ms/s" says nothing;
"`RefreshSchedule` costs 30 ms/s and fires eight times a second" names both the cost and its sender.

`reduce.RefreshSchedule` and `reduce.ExtendSchedule` are the two the engine reduces on a background
dispatcher (`docs/invariants/display-hot-path.md`), so their milliseconds are the only ones in that list that
are **not** frames. Everything else there is.

`reduce.contended` counts the compare-and-set retries that off-thread reduction makes possible — a re-plan
that lost the publish to a keystroke and re-derived against it. A trickle is the mechanism working. A stream
of them means something is asking for a re-plan far too often; look at what is moving `schedulingSignature`,
not at the publish.

### Leak detection

The **gauges** (`state.panels`, `state.historyUnits`, `engine.activeSessions`, …) are the honest leak signal,
not the heap. Each of the bounded ones has a cap, so a value climbing past it is a cap that stopped being
applied — which is the shape every leak in this state has taken. The heap is sampled too, but only *after a
forced collection*: the raw heap sawtooths with allocation, so its peak says how fast the app allocates, not
what it retains, and only the post-GC floor rising sample over sample is retention.

Samples are taken every five minutes by default because each one forces two collections and is itself a
visible stall. It runs off the UI thread, so it does not show up as a dropped frame in its own meter. Trends
are a least-squares slope in units per hour, not `(last − first)`, so one GC-timing outlier cannot decide the
verdict. Fewer than two samples reports "no trend yet" rather than zero.

When a gauge and the heap agree, the diagnosis is that gauge's name. When only the heap rises, the leak is in
something no gauge covers — add one.

---

## The headless benchmark

```
./gradlew :shared:jvmTest --tests "*PerfBenchmarkTest*" -i
```

(`-i`, or Gradle swallows the printed table.)

`benchmark_report` times each heavy derivation separately, median of repeated runs after a warm-up, and
**asserts nothing about duration** — a wall-clock budget on an unknown machine is a flaky test. Its value is
the comparison between two runs of itself, before and after a change.

### What the table is and is not

Every row is **one call**. The table ranks derivations by what one costs, and a cost only becomes a
performance problem when multiplied by a rate and landed on a thread that owes somebody a frame — which is
why the overlay ranks by ms **per second** and this does not. Two rows illustrate the difference:

- `fillSchedule` is the most expensive thing in the app (25-80 ms, growing with the task count) and it is
  allowed to be: it runs on a debounced rule change and a horizon roll — never on a tick. A progressive stage
  may also spend up to `PROGRESSIVE_STAGE_SEARCH_MILLIS` on a background dispatcher reaching the best score
  (`docs/invariants/scheduler.md` § *Progressive Calculation*): that time is granted, not a cost to diagnose — the
  History window's scheduler row says how much of it a fill used (`SchedulerRunEntry.search`).
  It earned a fix anyway, because until 2026-09-06 it ran on the frame loop.
- `encodeSnapshot` runs on every save, i.e. on the 400 ms typing debounce — but on `Dispatchers.Default`,
  never on the UI thread, and the history it walks is memoized per unit (`SchedulerStateCodec.encodedDeltaOf`),
  so it is flat in the size of the Undo/Redo stack rather than linear in it.

Reading a row as a bottleneck without asking "how often, and on which thread" is how a 0.2 ms function ends
up ranked above a 60 ms one.

`display_derivation_cost_follows_the_visible_window_not_total_history` is a real gate. It asks the same
visible week of the same account twice, once with a week of stored history behind it and once with a year,
and requires the answer to cost about the same. That is CLAUDE.md's hot-path rule stated as a **ratio**, so
it needs no budget and cannot fail merely because the runner is slow; a derivation that became linear in
history fails it by a factor in the tens.

---

## Adding instrumentation

`org.example.project.perf.Perf` — `measure(name) { … }` for a span, `count(name)` for an occurrence,
`gauge(name, size)` for a live collection.

Three rules:

- **`Perf.measure` must wrap work the app does anyway.** Every entry point is `inline` and returns before
  touching anything when `Perf.enabled` is false, so a release build pays one static boolean read per site —
  but that only holds if nothing is *gated* on the flag.
- **Name a section by what it derives, not by where it lives.** The overlay is a flat ranking; `display.` /
  `scheduler.` / `persist.` / `calendar.` / `reduce.` / `recompose.` prefixes are what group it.
- **The recorder is deliberately lossy under contention.** The registry is copy-on-write and the accumulators
  are fixed `LongArray`s, so a race can lose a sample but can never corrupt a structure or spin. Do not
  "fix" that with a lock on a hot path: a dropped sample out of thousands moves no conclusion, and a
  `HashMap` resize race under two threads would hang the app being measured.

`Perf.enabled` is set once at startup and never written again, so no instrumented site needs a memory
barrier. Keep it that way.
