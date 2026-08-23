# ADR 0003 — Screen breaks: sliding, serving, and cue recurrence

**Status:** active (spec settled 2026-08-05). **Invariant summary:** see `CLAUDE.md` → *Screen breaks*.

Terminology: the eye-care breaks are named **"screen breaks"** everywhere (PRD §15) — UI, docs, and the
code identifiers (`ScreenBreak`, `screenBreak*`, `showScreenBreaks`, `DEFAULT_SCREEN_BREAKS`,
`simulateScreenBreaks`, …) and the persisted JSON keys. The legacy "side task" names were fully renamed;
see `CHANGELOG.md` for the compatibility mapping.

The three breaks: the **20-s look-away**, the **5-min pose**, the **15-min pose**. Their scheduling
*shapes* (which tasks each period accepts) live in ADR 0001 §4.

## Every screen break slides

A break the now-line has reached and that no qualifying pause served is still **owed**, so
`screenBreakNextStart` is plainly `maxOf(lastRest + interval, now)` for the 20-s look-away exactly as
for the 5-/15-min poses. It sits at the now-line and moves right until a real pause releases it (an
ongoing pause advances every anchor through `screenBreaksForPlacement`).

> The look-away used to step its fixed grid past an elapsed occurrence, on the grounds that a look-away
> is not detectable. That read "not detectable" as "assume done" when it means "still owed".

## Consequently, no break has a drawn start that anything may key on

**The boundary a trigger keys on must be a fixed instant derived from the rules — never a position the
placement/projection recomputes every frame.**

All breaks key on the stable DUE `lastRest + interval`: a **level** test `now >= due` deduped on `due`
(`SchedulerDomain.reachedScreenBreakDueByTitle`; `reachedRestPoseDueByTitle` is that map filtered to the
poses).

Rejected alternatives, both shipped and both wrong:

- keying on the **panel start** — spams every frame;
- keying on "the now-line is inside a drawn panel" — still heartbeat-sampling; a leap skips the tick
  where the panel covers `now`.

### Two shadows inside it, and they are different rules

1. **The 5↔15 merge.** Among the poses the longest reached one absorbs the shorter.
2. **The dragging-pose shadow.** A look-away is dropped when its due is **at or after** the earliest
   reached pose's due — an owed pose re-anchors every shorter break to a slot that recedes as fast as
   `now`. A look-away due strictly *before* a pose's is a real earlier boundary and still fires, in
   order.

The look-away crossing is additionally bounded to the sweep window: it has no de-dupe memory of its own
and its due is fixed while owed, so an unbounded level reach would re-offer it every sweep. Consecutive
scans tile, so nothing is dropped.

Tests: `CueSweepOrderingTest`, `RestPoseNotificationRuleTest`,
`SchedulerSchedulerTest.screen_break_next_start_clamps_every_owed_break_to_the_now_line_and_slides_it_right`.
See also the `phone-missed-first-break-cue` note.

## What serves a break, and therefore how the cue recurs

User spec: *"after a ≥pause, **a look-away break or a 5min break**, the next look-away is an interval
later."*

> **Post-mortem.** "Owed until a real pause releases it" was only ever half the rule, and taken alone it
> produced the reported *one look-away cue per session, then silence forever*: nothing detects a
> look-away, so its anchor never moved, its due stayed fixed, and a cue keyed on that due and deduped on
> it could never fire twice.

**Three things serve a break, and each is an event, not an assumption.**

### 1. A look-away the app CONDUCTED serves itself

`SchedulerDomain.serveElapsedScreenBreaks`, driven from `dispatchScheduleAdvance`. The app announced the
break, waited its full `durationMillis` and said "resume your work", so `lastRestMillis` moves to that
occurrence's END.

This is NOT a return to "assume a break the grid stepped past was taken" — only an occurrence the app
actually ran counts, and **rest poses are excluded** (a 5-/15-min stop IS detectable, so a pose stays
owed and keeps sliding). Arithmetic, not iterative, so a leap over a hundred cycles costs one division.

**Stopped by the dragging-pose shadow:** from an owed pose's due onward no look-away is drawn or
announced, so those occurrences must not count either. The anchor advances only through occurrences due
before that pose's due and then freezes — which is what keeps the serving rule and the cue rule the same
rule.

### 2. A pose break that HAPPENED serves every shorter break

`pastScreenBreaksFromPauses` → `serveShorterBreaks`, wired into `refreshDerivedPausesNow`.

A past no-screen period at least as long as the pose the now-line was *dragging* when it began **is**
that break. Which pose was dragged is `reachedScreenBreakDueByTitle` at the pause's start — carrying the
**5↔15 merge**, so when both were owed the dragged thing was the 15-min one and the period must reach
*fifteen* minutes to be a break at all.

Evaluate it against the anchors as they stood BEFORE the pause is folded in (`seedScreenBreaksFromGaps`
moves them to its end, which would read as already served).

This is how a 5-min pose discharges the look-away though 5 min is under the look-away's own threshold.

### 3. A real pause ≥ `LOOK_AWAY_QUALIFYING_PAUSE_MILLIS` (15 min)

The look-away's `pauseThresholdMillis` in `DEFAULT_SCREEN_BREAKS`, deliberately the 15-min pose's own
length so that pose serves it through the plain `seedScreenBreaksFromGaps` rule with no special case.

> It was 0 ("as long as the break itself", i.e. 20 s), which let any brief step away restart the
> 20-minute clock.

## Every break recurs an interval after it ENDS

`simulateScreenBreaks`, `screenBreakPanelsInWindow`'s seed step — the same arithmetic the anchor uses, so
the drawn grid and the cue's due cannot drift.

> The look-away used to recur an interval after it *started* on the grounds that 20 s is negligible.
> With a self-serving look-away that left the grid 20 s ahead of the anchor.

**Consequence to accept:** the anchor moving is a signature change, so a conducted look-away re-plans.
Quantized at one fill per cycle (~20 min in production), it is a genuine rule change (the no-task period
moved), not a per-tick re-derive.

## Only a break the app CONDUCTED is drawn in the past

`takenScreenBreakPanels`, the past half of `App.kt`'s `displaySidePanels`.

Read off the ANCHORS, not the projection grid: `[anchor − duration, anchor]` is a break really taken, and
everything after the anchor is the pending occurrence the FORWARD projection draws at the now-line (drawn
by both, an owed break appears twice at once). Skips straight to the window, so cost follows the screen and
not the distance from `now`.

Which of the three that leaves is **exactly the one the app conducts**:

- a **look-away** is conducted — the app announces it, waits its full length and says "resume your work" —
  and its anchor steps one whole cycle per break that elapsed wholly (`serveElapsedScreenBreaks`). So it
  **chains backward** a cycle at a time and reproduces the occurrences that really happened;
- a **5-/15-min pose draws NOTHING in the past.** Nothing about a pose ever happens in the app: it is only
  ever RECOGNIZED after the fact, from an observed pause (`pastScreenBreaksFromPauses`).

> The pose used to vouch for exactly one occurrence, the one ending at its anchor. That was already the
> weaker half of the rule (chaining it would have invented a whole cadence of 5-min breaks nobody took), and
> even one is one too many: the pause it was read off is **already on the calendar as what it really was** —
> the two device layers, the no-screen period, the derived Inactivity band. A pose band there restates one
> fact as a second object, and swaps the pause's real extent for the break's nominal 5/15 min. It also made
> the anchor's own meaning visible as a lie — an anchor seeded from a night's sleep drew a tidy 5-min pose at
> the end of the night.

## A look-away that did not finish is erased

The anchor is an **END**, so nothing may move it at a break's start.

The manual "Look away now" (`SchedulerEngine.restartLookAway`, the lateral-menu button and the system-wide
`Ctrl+Shift+Alt+E` chord — ADR 0011) supersedes any run still in progress. It used
to stamp `lastRestMillis = now` at the press, which broke both halves at once: an END written at a START drew
a 20-s break over the 20 s *before* the manual one — the tail of the run the press had just interrupted,
offset by however late the press came — while the manual break itself, the one that actually happened, was
never drawn at all, because nothing moved the anchor when it ended.

It now dispatches on **completion**, to `resumeAt` and forward-only (20 s is long enough for a pull or a pause
to have moved the anchor further along). Both §15 rules then fall out of the single anchor:

- a run that did not finish — superseded by another press, or the app stopping mid-break — never moved
  anything, so it leaves no trace;
- one that ran its full length is served at its end, stays drawn where it happened, and the next occurrence
  recurs an interval after it (the same arithmetic as every other rest).

While it runs, the automatic occurrence it stands in for must not announce itself as well — the anchor has
not moved yet, so that due is still a crossable boundary — so the cue sweep swallows look-away starts for as
long as the manual job is active.

Tests: `SchedulerSchedulerTest.a_conducted_look_away_break_serves_itself_so_the_cue_recurs` /
`…_a_pause_under_a_dragging_pose_…` / `…_when_both_poses_are_owed_…` /
`…_an_owed_pose_stops_the_look_away_counting_breaks_it_never_announced`;
`ScreenBreakWindowTest.the_past_side_of_the_calendar_draws_the_breaks_that_were_taken_and_not_the_owed_one`;
`ManualLookAwayTest`.

## The END of a break is a notification, not only a voice cue

`SchedulerEngine.announceResumeWork`. The History window's Notifications column lists what the app
POSTED, and a voice-only resume left every break in it starting and none ever finishing.

The spoken half stays gated on the look-away voice switch; the notification does not, exactly as the
break's start doesn't. Test: `NotificationLogTest.the_end_of_a_screen_break_is_logged_not_only_spoken`.

## A screen-break panel has no Edit

User-confirmed 2026-07-19: a break has no editable object behind it (hardcoded `DEFAULT_SCREEN_BREAKS`),
and PRD §8 was reworded to match. A **sleep band**'s menu does lead with Edit (opens the §17
sleep-schedule window, no Remove/move).

## Decoupled poses (debug fast-break shapes)

A pose is **decoupled** when `qualifyingPauseMillis > durationMillis` — e.g. the fast-break scripts' 5-s
break that requires a ≥2 h pause. Such a pose **cannot self-recur** (a 5-s break is not a 2 h pause), so
it is **never** placed on the `simulateScreenBreaks` grid.

`screenBreakPanels` / `screenBreakPanelsInWindow` partition it out and project it via
`SchedulerDomain.decoupledPoseOccurrences`, which anchors one break to the **live now-anchor** (the most
recent past pause, `lastRestMillis`) **plus each future qualifying-pause window** passed in
`qualifyingPauseWindows`. Those windows are the **scheduled sleep windows** (`SchedulerDomain.sleepRegions`,
threaded from `App.kt` display + `fillSchedule`).

So on a freshly-emptied account the calendar shows **one 5-min break per day, 5 s after each night's
wake**, plus one at the now-line today — the only places a ≥2 h pause occurs.

This is distinct from the *coupled* shape (`qualifyingPauseMillis == durationMillis`, no threshold), which
legitimately grid-recurs and is held to one-at-a-time by the dense-interval cap
(`DENSE_SCREEN_BREAK_INTERVAL_FLOOR_MILLIS`).

> **Regression history.** The grid recurred every 5 s → "lots of 5-min breaks in a single day". A first
> fix suppressed the grid but drew nothing after future sleeps. The correct fix is anchoring to future
> sleep windows.

Tests: `SchedulerSchedulerTest.a_decoupled_pose_appears_once_per_qualifying_pause_5s_after_each_sleep`,
`ScreenBreakWindowTest`.

## Debug retiming knobs

`DebugFlags.screenBreakOverrides` is a `Map<ScreenBreak.key, ScreenBreakOverride>` applied in
`SchedulerDomain.effectiveDefaultScreenBreaks`. A break with no entry, and each `null` field of an entry,
keeps its production rule.

- Desktop reads `-Pomniapp.break.{lookAway,pose5,pose15}.{durationMs,intervalMs,pauseThresholdMs}`.
- The older unprefixed `-Pomniapp.break{Duration,Interval,PauseThreshold}Ms` trio (and Android's
  remembered debug flags / `omniapp_break_*` extras) still work and are a **named view onto the
  `5min_break` entry**, read first so an explicit `pose5` property wins.
- **Only the 5-min pose is retimable on the phone** — the Android extras were deliberately left alone.

The knobs only **retime**; they can never move a break's `shape` (see ADR 0001 §4).

Since the `device_break` row carries only *when* each break is due, these knobs shrink only what the app
**draws and speaks locally**. The pushed cue's delay is `break_config.length_ms`, which the fast-break
scripts set server-side via `account_db_admin.py break-length`.

Tests: `SchedulerSchedulerTest.the_debug_override_retimes_each_of_the_three_screen_breaks_independently` /
`…the_legacy_5min_break_properties_are_a_view_onto_the_per_break_override_map`.
