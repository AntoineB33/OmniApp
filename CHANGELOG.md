# Changelog

Dated history extracted from `CLAUDE.md`. `CLAUDE.md` holds only the active invariants; the *why* behind each
decision lives in `docs/adr/`. This file answers "when did this change, and what did it replace?"

Newest first within each section.

---

## 1.6.0 — spec deltas and their status

Check here before assuming the code matches the docs.

### The whole tab of the window bar is clickable — 2026-10-11

The user: "Make the whole tab in the system tray clickable. Right now, the paddings are not." The press was on the
tab's name alone; it is on the tab (`MinimizedChip`). The ✕ still closes. Client only. Not tried on screen.

### The window bar's tabs are walked from the keyboard — 2026-10-11

The user: "Create a shortcut to easily navigate through the tabs in the system tray in the recent order (one for each
direction), and a shortcut for the order of the tabs in the system tray (one for each direction)."
`Ctrl + Tab` / `Ctrl + Shift + Tab` walk the windows in the order they last had the focus (the walk lasts while Ctrl is
down); `Ctrl + Page Down` / `Ctrl + Page Up` the tabs in the bar's order. Fixed chords, listed in the keyboard-shortcuts
window under "Window bar" (`TabWalk`, `docs/invariants/shortcuts.md`). The recent order is in memory only. Client only.
Not tried on screen.
- Anomaly the same day ("I type ctrl + 9/3, with or without num lk, but it doesn't do anything"): the Page chords
  compared `Key.PageDown` / `Key.PageUp`, which on the desktop are the keys of the block above the arrows only. The
  numeric pad's 3 and 9 are now taken with Num Lock off (Page keys at the pad's location) and on (its digits).

### The Search configurations are grouped by reach; one button and a switch replace two — 2026-10-11

The user: "In the Search configurations window, with the same logic as in the action section of the Search window, the
first group gather all the configurations that can be applied to every element in a set of n element, where n is as
big as possible, followed by other groups with n decreasing. Also, remove the 'Only the types in the search resul' and
'Show the filters that are on' buttons. Replace them by a button that shows only the search configurations that can be
applied to at least one element in the result list, and a switch button to stop hiding or showing new search
configurations as the result list changes."
- `SearchDomain.configurationGroups`: "Every element · n" first, then the kinds by their rows in the results.
- "Only what applies to the results" + "Keep this list as the results change" (`ConfigurationSearch.frozenKinds`).
- Anomaly the same day ("I don't see 'Every box from' in the 'Every element' group"): a configuration a task and a
  restrictive period share is now listed once where it applies to all the rows — "Every element", or a group of the
  two kinds — as one field writing both (`SHARED_SETTINGS`). And each group has an expansion arrow.
- Anomaly after the rebuild ("still no 'Every box from' … there is no period group"): the window counted the result
  rows without the calendar's layer bands, so a period that is on the timeline only as one had no row. It now reads
  the list with what the Search window reads it with.
- Two filters of a period's own, which the user had named as existing ("filter to only periods that have ...
  resilience to ..., filter to only periods that have the drawing ..."): "Drawing" and "Resilience of a task"
  (`Filters.periodDrawing`, `periodResilience`, `periodResilienceTask`; stored, absent = not asked).
- **Persisted shape** (local view state): `frozenKinds` and `collapsedGroups` added (absent = follows the results,
  all open); `showFiltersOn` is read past and unused.
Client only. Not looked at on screen.

### "Every box from / until" are the right-click's instant, and can be picked on the calendar — 2026-10-11

The user: "In Calendar > right-click > edit… > All configurations: the fields 'Every box from' and 'Every box until'
should be both set to the date and time of the right-click. Add a button for both fields that brings focus to the
calendar and allows the user to click somewhere, which brings focus back to the Search configurations window and set
the field to the date and time of the click."
- The four fields (a task's, a period's) hold a date AND a time (they held a day); "edit…" sets them to the
  right-click.
- **Their meaning changed with it**: a row is kept when it has a box TOUCHING the window, where it was "every box of
  the element lies within the two days" — two bounds at one instant would otherwise keep nothing. A row the "is on the
  calendar at" filter keeps counts as in the window where that instant is.
- "Pick on the calendar" beside each field (`CalendarElementDrag.instantPick`).
- **Persisted shape** (local view state): four `…Millis` fields; an older build's day strings are still read.
Client only. Not looked at on screen.

### The calendar's right-click menu loses "edit task" and "go to task tree" — 2026-10-10

The user: "Remove the 'edit task' and 'go to task tree' options from the calendar right-click menu." Both are gone
from the menu (`CALENDAR_SIDE_EDIT_LABELS` no longer holds the task row; the "go to task tree" item is removed). The
task's edit window and "Go to task tree" are still actions of the Search window "edit…" opens on the task; a
double-click on a block is unchanged. Client only.

### A task's title is not written again after a 20-second cut — 2026-10-10

The user: "In the calendar, if a panel is cut somewhere by only 20 seconds, then the title isn't shown again right
after the cut." Two blocks of one task separated by at most 20 s (the work on either side of a look-away) each wrote
their title; the second no longer did (`blocksContinuingAfterShortCut`). **Reverted the same day** at the user's
request ("Revert the rule saying that only a >20s cut would display the title again (which actually wasn't strictly
applied)"): the function, its use and its test are gone, and every block writes its title as before. Client only.

### The actions section is grouped from every element down to each one — 2026-10-10

The user: "Rethink the placement of the configurations in the action section. For example, when the added elements
list only has one quota element, the first displayed thing should be the progression percentage, then the title and
maybe after the 'new' button… When there is only one added element, there shouldn't be the 'Remove all' button… when
there are several added elements, there should be the action groups that include all the actions that can be applied to
all the elements, then a group for less elements and so on…"
- `SearchDomain.actionGroups` replaces the one-group-per-kind listing (`sortedByReach` over `actionsFor`): every
  element, each kind holding several, then each element; one element alone is one group, its progression (or run, or
  state) first, then its title, its settings, and the buttons last, with no "Remove all" and no "Open each".
- The editors are unchanged: each is drawn over its group's elements. "Remove all" in a kind's group removes that
  kind's elements from the list.
- The group of several elements of a kind keeps one field writing to all of them at once (title, amount, time…),
  beside each element's own, and holds the kind's default configuration under a heading of its own (the user, after
  the first version dropped the shared fields: "the first group should have the configurations for the default
  configurations of the quota elements. There must also be a title field to rename every added quota elements at the
  same time, among other things...").
- Each group has an expansion arrow (the user: "In the action section, add an expansion arrow button to each
  group"). **Persisted shape**: the Search window's stored configuration gains `collapsedActionGroups` (default
  empty; local view state, never synced) — `SearchAddedElementsTest`.
Client only. Not looked at on screen: the grouping is tested, the drawing is not.

### `docs/scheduler_input_requirements.md`: the hidden timeline levels, and a dragged occurrence is an exception — 2026-10-10

The user asked that the project strictly satisfy `docs/scheduler_input_requirements.md` and
`docs/scheduler_requirements.md`; an audit found two clauses the code contradicted or did not have. (The audit's other
findings, and what is still open, are in `docs/invariants/calendar.md` § *tm_levels* — "Open".)
- **A dragged occurrence leaves its pattern** (*"the configuration that created this orange outlined block/chip gets
  an 'exception' info"*). Dragging or editing one occurrence of a repeating panel used to move the WHOLE pattern
  (`reduceUpdateTaskPanel`); it now skips that occurrence (`PanelRepeat.skipped`) and lays the moved block as a panel
  of its own, in one history unit. Another cadence said from an occurrence still edits the pattern. **Persisted shape**: `PersistedPanel.repeatSkipped`, default empty —
  `RepeatingPanelsTest`. It is in `schedulingSignature` through the pattern.
- **The `tm_levels`** (*"what was there before is added at t_r to the lowest tm_level where nothing is at t_r"*). A
  block placed over another block of the user's used to TRIM it for good (`resolveScreenOverrides`), and two
  overlapping periods of one kind were fused. Now every placed block stands on a level (`TaskPanel.tmLevel`), the
  positioned one above what it overlaps, and what cannot share the stretch is HIDDEN (`SchedulerState.hiddenPanels`)
  and given back when the block above moves, shrinks or is removed (`TimelineLevels`, settled at every calendar
  commit). **Persisted shape**: `PersistedState.hiddenPanels`, `PersistedPanel.tmLevel` / `tmOrigin`,
  `PersistedDelta.Panels.hiddenBefore` / `hiddenAfter`, all defaulted — `TimelineLevelsTest`. Synced by rows (a new
  entity kind, `hiddenPanels`: no migration, the row tables are generic), merged piece by piece, bounded at 90 days
  and 500 pieces.
- **The same day, after the user's answers and their edit of the document:**
  - *Deleting an occurrence* is a removal exception (it deleted the whole pattern).
  - *Work under a period laid over the past* is hidden with the levels and given back, never stripped
    (`HiddenPanel.record`, `PersistedHiddenPanel.record`, default false).
  - *"Dragging task A into task B hides task B, unless shift is pressed"*: a move no longer always shares the width
    (`TaskPanel.tmShare`, `PersistedPanel.tmShare`, default false; `blockGestureOverlaps`).
  - *"All blocks can be dragged, except screen breaks at t > now line"*: a break ahead of the line is no longer held.
- **Anomaly, the same day**: a past 15-minute break dragged away left a BLUE "Inactivity" block where it had been.
  The fill is the hole rule's (`vacatedPastFill`), stored as a period with `auto = false`, so it read as hand-placed.
  It now carries `TaskPanel.tmFill` (`PersistedPanel.tmFill`, default false) and wears no outline until edited.
- **Anomaly, the same day**: "there is an Inactivity period stopping at 16:28:48 and another starting at this exact
  time". Read off a copy of the release DB: `panel/330`, the fill of a dragged 15-minute break (16:13:48 → 16:28:48),
  next to the DERIVED Inactivity of the idle stretch after it. An Inactivity fill is now drawn as part of the derived
  band (`isDerivedInactivityFill`). Display only.
- **Randomized tests against a model of the document** (the user: "The tests must make it extremely unlikely that the
  user gets a situation where those requirements are not strictly satisfied"): `TimelineLevelsFuzzTest` and
  `RecordLevelsFuzzTest`, in `:shared:jvmTest`. They found five faults in the levels as first written, all fixed the
  same day and listed in `docs/invariants/calendar.md` § *tm_levels* — among them a fill laid over hidden work, which
  kept it from coming back, and same-task panels fused across levels.
- `BreakMachineFuzzTest`: the same for the screen-break rules of `docs/scheduler_requirements.md` (random line
  motion and mode switches). No fault in the machine; one edge of no length noted in
  `docs/invariants/screen-breaks.md` (two breaks touching when the mode switches at the instant a pose ends).
- Also: replacing a merged block (`ReplaceTaskPanels`) recorded its unit from the panels AFTER the removal, so Ctrl+Z
  did not give the replaced panels back; the unit now starts from the panels as they were.
Client only. An older build on another device ignores the new fields and writes panels without them: a block it
edits comes back on level 0, and it trims where this build hides.

### The sleep fields froze under the hand; one time field with a drop-down; the period before bed has a length — 2026-10-09

The user: "In the sleep configurations in the calendar, when editing the fields, it freezes a lot. The scheduler engine
should not freeze what the user does. Also, add a drop-down list to select which field to edit between wake up, go to
bed or stop screens. Add a field for the duration of the no screen before bed period. Rename the hour before bed
period that way."
- **The freeze.** `reduceSetSleepSchedule` ran `fillSchedule` with its search on the dispatching thread, once per text
  that read as a time. Removed: the engine re-plans for the schedule anyway (it is in `schedulingSignature`), off the
  UI thread. Read in the code, NOT measured: no profile was taken before or after.
- **The fields** (`SleepScheduleFields`): a drop-down — Wake up / Go to bed / Stop screens — says which time of the
  night the two time fields are about: that time now, and the one it drifts toward ("Goal stop screens time" while
  "Stop screens" is chosen). Stating either moves the WHOLE night and changes neither length
  (`SchedulerDomain.NightTime`, `withNightTime`, `withGoalNightTime`). Then Total sleep time and the new **No screen
  before bed** length. (As first written the same day, "Go to bed" changed the sleep time and "Stop screens" the
  period's length, and the goal was always the wake time's; the user: "When modifying the time to wake up, it should
  update the time to go to sleep and stops screens. Right now, it updates the total sleep time… if stop screens is
  selected, then it must not be goal wake time but goal stop screens time".)
- **The length**: `SleepSchedule.beforeBedMinutes` (60 by default, 0 = none, 12 h at most). **Persisted shape**:
  `PersistedSleep.beforeBedMinutes`, default 60, absent from an older payload — `SleepScheduleEditTest`. Synced with
  the schedule; an older build on another device does not know the field.
- **The name**: the period's title is "No screen before bed" (it was "Before bed"), and a break's follow-on reads
  "followed by no screen before bed". The KIND id stays `before bed` (stored keys), so a period's edit row, the
  resilience lists and the rules output still show `before bed`; the spoken cue is a recorded file and still says
  "the hour before bed".
Client only.

### A switch to mode 3 no longer changes the schedule — 2026-10-08

The user: "Switching to $now line$ mode 3 doesn't change the input in itself. However, if the $now line$ was in a task
panel with 0 resilience to 'no screen', it means the $now line$ cuts this task panel in half and continuously retracts
the lower half by moving forward … The previous set of rules output is therefore still applied and the schedule in the
future doesn't change." Until now modes 2 and 3 were ONE plan class (2026-09-28): "I'm away" pressed at a screen was a
class flip, which laid the covered plan (or re-planned where none was held) and so rewrote every run ahead.
`SchedulerDomain.planModeAfter` + `SchedulerEngine.heldPlanMode`: mode 3 keeps the plan in force, live and inside a
journey; a plan of the other class is laid only on an arrival in mode 1 or 2 under the other's plan.
`AwayVersusLockedCueTest.an_away_device_is_not_told_to_start_an_on_screen_task` asserted "the plan holds no on-screen
task at an away line": REWRITTEN to the rule (the plan is unchanged by the press; what the line holds in its mode is
no on-screen task). `PlanModeTest`. Known gap: the History row of a run made while the line is in mode 3 states the
plan's mode (1 or 2) in its head, not 3. Client only.

### The set of rules output read as four violations of the requirements: its wording — 2026-10-08

The user pasted the output into Gemini, which found: overlapping intervals (a run `+0:59:54 → +1:30:41` engulfing the
break `+1:00:01 → +1:05:01`), a mode-1 line inside a 20s break, a 20s break 19 s long, and "if planning is refused,
run planning". All four are `describeScheduleRules`' wording, rewritten:
- a run is one panel across its breaks and was listed whole; the output is now cut into sequential pieces (the
  runtime already reads it so: `RuleProgram` for the tasks, `BreakMachine` for the breaks);
- the head named the mode CLASS the plan was found for (at a screen); it now names the line's mode, 3 inside a 20s
  break;
- offsets were truncated toward zero on each side of the line (−18.5 s → `-0:00:18`, +1.5 s → `+0:00:01`); floored;
- the release account has 91 tasks titled "planning": a title shared by two named tasks now carries the id.
NOT established: that no run ever names ITS OWN task as its alternative — the persisted state holds no rule-laid run
to check, and the optimizer was not read for it. With ids in the output the next paste shows it either way.
`ScheduleRulesOutputTest`. Client only; nothing the scheduler answers changed.

### The calendar's configuration keeps its width and stands on the left of its section — 2026-10-08

The user: "In the calendar, when the calendar section gets reduced, the day selector should keep its length and all
configurations would stick to the left side of the configuration section." The section's column filled whatever
width the dragged line (or a retracted grid) gave it, so the month grid's seven columns and every switch stretched
across it. It is now laid out at `CALENDAR_CONFIGURATION_WIDTH` whatever the room, on the section's left; the
scrollbar stays on the section's right edge. Client only. Not seen on screen.

### A history unit's long text is one line, with an arrow to expand it — 2026-10-08

The user: "In the action section of a history unit, if a text element stored in the history unit is long it must still
be shown in a single line, with an arrow button to expand the text and a button to copy it. When expanded, the expand
button is always visible on the right even if the user scrolls on the action section to read the whole text."
`HistoryTextLine` (`ui/CalendarUi.kt`) replaces the body of `HistoryInfoLine` and the "Set of rules" block's own
text: one line with an ellipsis, `▸` / `▾` where something is cut, "copy"; expanded, the buttons are offset by what is
scrolled away above the line (`stickyOffsetPx`, `HistoryTextLineTest`). The History window's information lines are
the same function, so they read the same way. Client only. The drawing itself was NOT seen on screen: only the offset
rule is tested.

### A quota's progression at other times — 2026-10-08

The user: "In the action section of the quota element, add the action to define other times to look at the
progression of the quota at those times. They can be defined in absolute date/time, or relative to today, or both
(e.g. tomorrow at 10AM)." New action **"Progression at other times"** (`AddedAction.QuotaLookTimes`,
`QuotaLookTimesEditor`), under "Target progression": a list per added quota with "+ Add a time"; each element has a
day (a date, or days from today — a button turns one into the other, the instant kept) and a time (a time of day, or
the time it is now), and reads the percentage and the amount due by then. Kept on the quota
(`QuotaEntry.lookTimes: List<QuotaLookTime>`), so it is authoritative, synced with the quota's row and undone with
it. **Persisted shape**: `PersistedQuota.lookTimes` (default empty; a quota with none writes nothing new) —
`QuotaTest.the_look_times_are_stored_healed_and_absent_from_a_payload_written_before_them`. No SQLite or Supabase
migration: the quota is stored as one JSON object. Client only. Not checked: what an older build on another device does to
the list when it rewrites that quota (it does not know the field). The editor was not seen on screen.

### A held block remembers its length: across the line, and out of the far side of a break — 2026-10-08

The user: "When dragging a block on the $now line$ but the $now line$ can't be on this block (e.g., a task panel when
$now line$ is in mode 3, or a 'no screen' block when $now line$ is in mode 1…), then the block being held is in …,
$now line$[ U ]$now line$, … This is because when held the block always remembers its length while avoiding appearing
where it would break the requirements … If the user drags a task panel into a 15min screen break that is in the future,
then the 15min break retracts the held task panel, which comes out on the other side to keep its length."

- **The line.** A period that is or carries "no screen", held across a mode-1 line, was cut to `]line; its end]`,
  shortened as it was carried across and was gone as its end reached the line (user rule 2026-10-05,
  `SchedulerDomain.periodAtLine`, `PeriodAtLineTest`). REPLACED: the function and its test are deleted,
  `LocalPeriodAtLine` hands the period back whole, and the release stores the span the hand chose.
- **A break.** A task panel moved into a screen break that refuses its task was holed there and lost that much; carried
  wholly inside it was drawn with no height (the entry of this morning). A move now takes
  `SchedulerDomain.spanKeepingLength(start, standing length, the breaks refusing the task)`: the panel comes out on
  the other side by what the break holds. `draggedBlockBounds` gained `refused`; all three move gestures and
  `CalendarElementDrag.targets` pass `refusingBreaks`. The zero-height slice stays for a block a break covers at rest.
- **Not done, and why.** The user's text says a block surrounding the line changes the scheduler's input continuously,
  so the engine re-runs without end and the timeline stays on the previous set of rules output. The block is stored
  as ONE span that holds the line's instant, so in the app the input does not change as the line moves and no such
  loop starts; nothing was added to produce one. And only screen breaks are counted as "where it would break the
  requirements" for a task panel: a held panel is not lengthened across a period laid by hand that refuses its task
  (there the PERIOD gives way, user rule 2026-10-02).

`HeldBlockKeepsLengthTest`. `docs/scheduler_requirements.md` itself changed only in wording (commit 2c2c1a8: "set of
rules output", "the future part of the timeline"). Client only. NOT seen on screen.

### A window's section lines did not survive a restart, so a button's "Update" kept the middle — 2026-10-08

The user: "clicking on the button 'Claude quota' opens the window but the positions of the lines that separate the
sections are not restored from when I clicked on 'update'". The release DB's button holds `splits: [0.5, 0.5]` with
two sections hidden (the "Gemini quota" one holds real shares), so the click restored what the button kept. The lines
and the retracted sections of an open window lived in memory only (`searchSplits`, `calendarConfigurationWidth`,
`calendarSectionsHidden`): each restart put them back at their defaults, and the window that came back was then
updated from. They are now local view state on a `WindowSections` placement row, written at every change, read at
startup for the windows that come back, dropped when a window closes. No schema change (a placement row, like
`TabTitles`); a database without the row reads as before. Test: `WindowFrameHostTest`. NOT reproduced on screen: the
diagnosis rests on the stored button. Client only; the "Claude quota" button must be updated once more after its lines
are set.

### A task panel carried wholly into a break came back entire — 2026-10-08

The user: "When I drag the task panel past the $now line$ when it gets completely retracted, it suddenly appear wholy
on the other side, but in the 15min break, the same 15min break that was supposed to be the cause of the
retractation". `layoutWithBreakHoles` kept the slices of a block a break covered end to end ("nothing left to
grab"), so the retraction stopped exactly where it became total. Such a block is now one slice of no height at its
start (still the hand's: a resting slice has a least grab height), and `placedPanelSpans` counts it nowhere. Tests in
`CalendarDragPreviewTest`. Client only.

### The layers were cut under a break the held task panel was retracted from — 2026-10-08

The user: "the held task panel retracts to $now line$, but strangely both "no computer unlocked" and "no phone
unlocked" are retracted in the 15min break right after the $now line$". The layers give way to a task panel placed by
hand that "no screen" refuses (2026-10-02), and the cut was taken over the panel's WHOLE span — the drag preview's
bounds — while the drawing of the panel is holed by the break. So over the pose the panel was not drawn and the two
hatches were not either. `placedPanelSpans` now takes the screen breaks and returns each panel where it stands (its
span less the breaks that refuse its task: the question `layoutWithBreakHoles` asks); the column's resting bands, the
drag's `shownLayerBands` and the Search filter's `CalendarLayersHolder.placed` (through
`SchedulerDomain.placedTasksOutsideBreaks`) all read it so. Test: `CalendarDragPreviewTest
.the_layers_stay_under_a_break_that_holes_the_held_panel`. The `placedTasks` argument given to `statedKindRegions`
earlier the same day was NOT the cause (a probe showed the break already laid its layers there); it is kept, as it
makes the stated Sleep window follow the same rule. Client only.

### A break retracts a placed task panel: the exemption of the entry below is withdrawn — 2026-10-08

The user, on the entry below: "If it is a 15min screen break, then it is my task panel that must get retracted,
otherwise docs\scheduler_requirements.md would be violated. So the problem I had was simply the 15min break not
showing." The entry below found the right cause (the owed pose riding the line) and drew the wrong conclusion from
it: it stopped the break cutting a placed panel. **That change is reverted** — a break cuts every task block it
refuses, placed or not, as since 2026-10-02.

What was left wrong is the user's "strange" half: the Sleep band gave way over the panel's whole STORED span, the
part under the break included, where the panel is not — so the hole stood empty of everything but the break's thin
grey outline. The band now gives way only where the panel stands (`SchedulerDomain.placedTasksOutsideBreaks`); behind
the break it is drawn as it is anywhere else. `PlacedTaskAtTheLineTest`.

**Not changed, and the user's remaining point**: how the break itself is drawn. On the first frame of the headless
app it IS there at the line — an empty box with a one-pixel grey outline and no name (fifteen minutes is 12 dp at
that zoom, the name needs 16) — which over the Sleep hatching is very hard to see. Whether it should be made more
visible, and how, is asked of the user. Not verified on screen. Client only.

### A task panel dragged onto the line was cut at the line: the real cause, an owed pose riding the line — 2026-10-08

The user, after the entry below: "It still gets retracted by the now line, and strangely the sleep block retracts even
though it is not replaced by the task panel I am dragging." The entry below was read off the code and its first
cause was NOT what the user was seeing.

Established this time on a copy of the release database (the whole app run headless and offline on it, and the drop's
own edit applied): the dropped panel is STORED whole, and the re-plan keeps it whole. The cut is in the drawing. An
owed 15-minute pose was riding the now-line — `]line; line + 15 min]` — and `layoutWithBreakHoles` cuts a hole in
every task block under a break. So anything put on the line by hand was drawn up to the line and holed for the next
fifteen minutes; and since the entry below made the Sleep band give way to the (whole) panel, the band drew back from
a panel that looked cut — the "strange" half.

Fix: a block the USER placed is holed only by breaks the line has passed (`aheadFromHour`); a run the rules laid gives
way to every break, as before. `CalendarDragPreviewTest`.

The headless app shows its first frame only (its display does not follow later edits there) and does not take the
calendar's drag gesture, so the corrected DRAWING was not seen — only the stored state was. Not verified on screen.
The two changes of the entry below stay: both are right on their own. Client only.

### A task panel dragged onto the line was cut at the line; the Sleep band did not give way to it — 2026-10-08

Anomaly: "when dragging a task panel from the past to the now line, it gets retracted by the now line. The now line is
in mode 1, so it has no reason to retract the task panel, and the sleep block right after the now line must retract to
the task panel."

- **The cut.** A run the fill lays at the line inside the schedule's Sleep window carries the stretches it holds only
  there (`heldAtLine`). Dragged by hand it kept them, so on the line it was drawn up to the line and no further. A
  panel placed by hand holds none now (`reduceUpdateTaskPanel`), and `atLine` leaves every placed panel whole — which
  also heals the ones already stored.
- **The band.** A rule-laid period that carries "no screen" gave way to the past the line crossed at a screen, never
  to a task panel the user placed. It does now (`retractOverAtScreenPast`): the Sleep band starts where that panel
  ends, on the calendar, in its layers and for the record bank.

Found by reading the code — the first cause fits the report exactly, but it was NOT reproduced on the release
account, and neither is verified on screen. `PlacedTaskAtTheLineTest`. `:shared:longTest` not run. Client only.

### Work done past bedtime was never recorded: the past stood empty behind the line — 2026-10-08

Anomaly: "why in the calendar there are hours of inactivity before the now line?" Read off the release account
(its diagnostics, the Windows power log through the app's own reader, a copy of its database): the machine was
unlocked and the app's sessions continuous, the plan had a task panel on the line — and NO task record had been
banked since 22:14 the evening before, the start of the hour before bed. The same on each of the four evenings the
database still showed.

The record bank refuses an on-screen task's work over a "no screen" stretch, and read the schedule's stored Sleep
window (and the hour before bed) WHOLE as one — while the fill and the calendar both treat that window as given up
where the line crosses it at a screen. So the work was planned, shown on the line, then dropped as it elapsed.

Fix: the bank reads those rule-laid periods as the line left them, off the same evidence the calendar cuts the Sleep
band by — where this device is known unlocked (`SchedulerReducer.atScreenEvidence`,
`SchedulerEngine.atScreenEvidenceNow`). A period the user drew still holds; a failed scan changes nothing.

Not a regression of this week: the gap is older than the four days checked. **The records already lost are not
restored** — nothing kept them. `NoScreenEvidenceTest`. This changes what the advance banks: `:shared:longTest` NOT
run. Not verified in the live app. Client only.

### Customize mode: a Search window's actions can be added to the menu — 2026-10-08

The user: "I set in customize mode, went to a Search window and right-clicked on an action configuration, but it didn't
do anything. It must open a menu to add the button or field to the left-side menu of the app. If then the button or
field can't do anything, for example it is the button 'duplicate' for a task that doesn't exist anymore, then the
button is grayed in the left-side menu." The actions were not wired (the limit stated two entries below).

- Every row of "Actions on the added elements" is outlined in customize mode and offers "add in the left-side menu".
- The item keeps the action AND the elements it acted on there (`CustomMenuButton.action`, a new field with a default,
  with that window's configuration), is drawn by the action's own editor, and is named "<action> · <element>".
- It is greyed, and does nothing at a press, while none of those elements is left (`SearchDomain.actionCanAct`).

Seen on the Search window rendered headlessly beside the menu, with real mouse events: the menu entry on an action;
"Remove all" and "Add a category" added for the task "Apple"; both greyed once that task was deleted. An editor made
for the wide actions section is cut at the menu's width — the larger ones ("Add to the calendar") will not fit.
`SearchAddedElementsTest`, `CustomMenuButtonsTest`. Not verified in the live app. Client only.

### Customize mode: the menu's items are dragged to another place — 2026-10-08

User rule: "in customization mode, the user can drag the buttons or fields in the left-side menu, except for the top
button that switch between pages." A press on an item holds it; moved, the item follows the hand and a line shows
where it would land; released, it stands before the item under it (`heldDrag`, `CustomMenuButtons.moved`). The order
is the stored list's, one Undo unit like any change of the menu. `CustomMenuButtonsTest` (the order). Seen on the menu
rendered headlessly with a real mouse drag: a plain click on a switch and on a button did nothing; "Voice" held and
moved followed the hand with the line under "Sleep", and was dropped there. Not verified in the live app. Client only.

### Customize mode: the menu's items still acted at a click — 2026-10-08

Anomaly: "In the customize mode, I can still click the switch button in the left-side menu of the app." While the menu
is being customized its items now do nothing at a press — a switch is not flipped, a button opens nothing — and only
their right-click menu answers (`inertToPrimaryPress`). The controls outlined in the rest of the app still work: only
the menu was reported. No test (a gesture); not verified on screen. Client only.

### The whole lateral menu is customizable — 2026-10-08

User rule: "Make the entire left-side menu of the app customizable, except for the top button that allows the user to
switch between pages. There can be any kind of button in it, like switch buttons or fields, and they all have the same
right-click menu. When the user selects the customize option from this right-click menu, the user can right-click on
any configuration field or button in the app and the right-click menu will have one option: add in the left-side menu,
just like the star button in the header of a window. The default voice and notification switch button must then be
present in an 'app setting' element."

- **One list under the page button.** What the menu's fixed part held (Task tree, Calendar, Voice, Notifications, Look
  away now, Switch task, Sleep / Work, I'm away, Online) are now items of the user's list, above the buttons they had
  made; a stored list is given them once (`CustomMenuButtons.DEFAULTS`, `seeded`). `CustomMenuButton.control` is a new
  field with a default: a list written before still reads.
- **An item is a window's button or a control** (`MenuControl`): a switch, a field, a button. Drawn by the code that
  draws it where it lives (`MenuControlItem`).
- **One right-click menu for all**: Update (where it applies), Rename, Remove, Customize.
- **Customize**: outlines what can be added, and a right-click on one offers "add in the left-side menu"
  (`MenuAddable`). Ended by "Done" at the top of the menu, or the same menu entry.
- **Voice and Notifications are app-setting elements** in the Search window, each with its switch.

**NOT "any field or button in the app" yet.** What can be added: the menu's own seven controls, the calendar's
configuration (Display, Auto schedule, Reminders, Screen breaks, time limit, minimum time weight, sleep schedule), and
the app settings' Voice, Notifications and Global volume. The other windows' controls are not wired — each needs its
state reachable from the menu — and "Lock to now" is the calendar window's own state. Items cannot be reordered.

Seen on the menu rendered headlessly beside the calendar, with real mouse events: an item's menu; Customize (the line,
the outlines); "add in the left-side menu" on a switch and on a text field, each then in the menu; Done.
`CustomMenuButtonsTest`, `SearchAddedElementsTest`. Not verified in the live app. Client only.

### Lateral menu: the "Search" and "Categories" buttons are gone from its fixed part — 2026-10-08

User rule. The two `MenuButton`s and their parameters are removed (`LateralMenu`, `App`). The windows themselves are
untouched: the user's own buttons (☆) open them, the calendar's "add…" / "edit…" opens a Search window, and the
Categories window is a "window" row of a Search window. Not verified on screen. Client only.

### "Update" was not offered after hiding a section, nor for a window that came back at a restart — 2026-10-08

Anomaly: "I hide one section in the 'Claude quota' Search window in account3, but the option 'update' doesn't appear.
It worked for 'Gemini quota'." Two holes in the entry below, both stated there as limits:
- **A hidden section was not part of the state.** It is now (`WindowLayout.hidden`; the Search window's three
  sections, the calendar's two), saved by "Update" and put back when the button opens a new window.
- **Which window a button opened was forgotten at a restart** — so after a deploy the option could not appear for a
  window that had simply come back, until its button was pressed again; the likely difference between the two
  buttons. The window is now found again by the button's name on its tab (`menuButtonWindowOf`), and a button that
  never kept a layout offers "Update" for such a window.

`CustomMenuButtonsTest`. Which of the two holes the user hit was not established, and none of it is verified on screen.
Client only.

### The menu's own buttons: "Update" — 2026-10-07

User rule: "In the custom buttons in the left-side menu of the app, add the right-click option 'update', that will save
the current state of the window that the user opened by clicking this button. The option doesn't appear when the state
hasn't changed or the window is closed. The state includes the search configurations and the added elements list for
the Search window, the position of the window and its lines that separate the sections."

- A button now keeps a **layout** beside its configuration (`CustomMenuButton.layout`, a new field with a default: a
  button written before reads with none): the window's offset and size, and the lines between its sections — the
  Search window's two, the calendar's one. Those lines were each window's own; they are told to `App` now
  (`SearchSplits`, the calendar's `configurationWidthDp`), still Compose-only.
- **"Update"** is in the button's menu while the window that button opened or brought back is open and its
  configuration or layout is no longer the button's. It then saves both.
- A new window a button opens is put back where, and shared as, the button kept it.

Limits: which window a button opened is remembered for the session only — after a restart, press the button again
before "Update" can be offered; a window's retracted sections (the arrows) are not part of the state; and a click
still finds "the same window" by its configuration alone, so an updated button brings back the window it was updated
from. `CustomMenuButtonsTest` (the rule and the old stored shape). The menu entry, the window put back in place and the
lines restored are NOT verified on screen. Client only.

### Calendar: the sleep schedule in its configuration; the two sections retract and their line is dragged — 2026-10-07

Two user rules.
- **"Move the sleep configuration from the left-side menu of the app to being a section in the configuration section
  in the calendar."** The wake time, the goal wake time, the total sleep time and the bedtime they make are the last
  section of the calendar window's configuration (`SleepScheduleFields`). The lateral menu's "Sleep schedule" button
  is removed. NOT removed: the Sleep window itself — the calendar's "edit… → sleep schedule" on a Sleep band still
  opens it, with the same fields; removing it means retiring a window the placements, the history units and the
  user's own menu buttons can name, which was not asked.
- **"In the calendar, like in the Search window, add expansion arrows to hide for the two sections, and the ability
  to move the vertical line separating them."** `SectionArrow` on the grid (top left corner) and on the
  configuration (before a new "Configuration" title); `SectionSeparator` between the two, dragged up to the edges.
  The grid stays composed while hidden or squeezed, so its scroll and zoom are kept.

Seen on the calendar window rendered headlessly with real mouse events: the Sleep section under "Scheduler engine";
the line dragged to the middle, to the left edge and back to the right edge; each arrow retracting and restoring its
section. Not verified in the live app; the grid's arrow is small and sits just above the month's name. No test kept
(no Compose UI test in the project). Client only.

### Search window: a result row's blank columns go to the path — 2026-10-07

The user: a task element reads "task", then lots of space, the arrow, the title, the path, the priority, lots of
space, the minimum time, the category, lots of space — "it is weird that there is so much space, but the paths
section is shrinked because it lacks space". The kind section was 104 dp whatever the list held: it is now as wide as
the widest kind listed. The percentage, the minimum time and the categories stood in the tree's column widths: in a
result row they are as wide as what they show (`LocalTightRowColumns`). A long title still prevails over the path —
that rule is unchanged. The tree and the sub-trees keep their columns.

Seen on the headless render (800 px wide): "task" is followed at once by the arrow, and the gaps round the minimum
time are gone. No test kept. Not verified in the live app. Client only.

### Window bar: a tab's outline is the colour of its name — 2026-10-07

User rule: "In the system tray, the outline must have the color opposite to the background color of the window" —
meaning, as the user then put it, "the color chosen for the text on the background color, just like the task panels
and task cells … the outline of the tab is not the same color as the window title". The focused tab's and a selected
tab's outlines were the theme's text colour; they are now the tab's own text colour on its fill
(`TaskPalette.foreground`), so the name and the outline are one colour. A first reading took "opposite" for the
inverted colour and was withdrawn the same day. **Outside the outline, a very thin line (1 dp) of the window's
colour** (user, the same day: "so it doesn't disappear on the system tray color"). Not verified on screen. Client
only.

### Search window: a narrowed section is cut, its title and its lists included — 2026-10-07

The user, on the entry below: "The title of the three sections of the Search window are still getting squished when
moving the vertical line to the edge." The three titles wrapped as their section narrowed; they are one line now, cut
at the edge. The render showed the same of the result list and of the added elements list — their rows stacked their
text letter by letter — so both are laid out at the sections' least width and cut (`keepsWidthAbove`), like the
header and the actions.

Seen on the headless render with the vertical separator 48 px from each edge: every part of the narrow section is cut,
none is squeezed. No test kept. Not verified in the live app. Client only.

### Search window: each section can be resized up to the edges of the window — 2026-10-07

User rule. The separators stopped 220 dp (sideways) and 90 dp (up and down) short of the edges; they now go all the
way. Each section is clipped, so one given no room shows nothing instead of spilling over its neighbour — which the
first headless render showed it doing.

Seen on the Search window rendered headlessly with a real mouse drag: the vertical separator to the left edge, taken
back from there to the right edge, then to the middle; the horizontal one to the top, then to the bottom. No test kept
(no Compose UI test in the project). Not verified in the live app — in particular, a separator lying on the window's
edge sits beside the window's own resize border; in the render it could be taken back 8 px in. Client only.

### Search window: a cell of a sub-tree and its row were both drawn selected — 2026-10-07

Anomaly: "When I click on a task cell in the sub-tree, then on the root task cell (in the result list), both task cells
are selected (even though I didn't press ctrl or shift)." Two selections, each drawn by its own rule: the sub-trees'
(in the state) and the rows' (the window's). Visible since the same day's fix that made a first-level cell's
selection drawn at all. Now one surface shows a selection at a time (`selectionSurface`): the sub-tree the last
gesture went to, or the rows. The same in the added elements list. Drawing only — nothing stored changes.

Seen on the Search window rendered headlessly with real mouse events (a cell pressed: it alone is grey; then its row:
the row alone). No test kept (no Compose UI test in the project). Not verified in the live app. Client only.

### Search sub-trees: a pinned first-level cell pressed stopped under the list's pinned row; the added list's arrow had no colour — 2026-10-07

Two anomalies.
- "internship" pinned and pressed, then "master everything…": "it scrolled smoothly in two times, and not fully
  visible but a bit hidden by the top task cell 'ability to learn'". The reveal left one TREE row's band above a
  cell whose parent is the LIST's row — a taller one — and scrolled the sub-tree, then the list, one after the other.
  A first-level cell now lands under the list's own band (`TaskTreeView.outerBandPx`), and the two scrolls run
  together.
- The expansion arrow of a task in the added elements list had no background: it now sits on the task's colour, as
  in the tree and in its own children.

Both seen on the Search window rendered headlessly with real mouse events (the arrows coloured; "internship" then
"master everything…" pressed, each landing whole under its parent) — with the list's own row still in view, NOT with
it scrolled off and pinned, which is the user's exact case. No test kept: the project has no Compose UI test and the
render harness is flaky (a background thread of the window races it). Not verified in the live app. Client only.

### The first-level cells of an expanded result row could not be selected or edited: the real cause — 2026-10-07

The user, after the entry below: "I still get the problem." The entry below was a guess read off the code, and wrong
as a diagnosis. Reproduced this time, on the Search window rendered headlessly with real mouse events: the press
reached the cell and the state said "selected" and "editing", and nothing was drawn. A Search sub-tree draws its
first-level cells under no via; `SchedulerDomain.resolveSelectionRenderVia` named the result row's own cell as their
via, so the selection and the edit session matched no drawn row. Deeper cells have a real via and worked — "it works
for the other task cells". The rule dates from 2026-05-31, not from this week's changes; the scroll had nothing to do
with it.

Fix: a cell of the drawing's top-level list has no via. `SearchWindowTest` (the press, Edit Mode, and a stale
selection healing). Seen fixed on the rendered window (the edit field is drawn); not verified in the live app. The
change below — the list scrolled only where it cuts the sub-tree — stays: it is right on its own. Client only.

### The first child of an expanded result row could not be selected or edited — 2026-10-07

Anomaly: "ability to learn" expanded at the top of the result list, the list a few pixels down, a click on its first
child did nothing and Edit Mode could not be entered; the other cells worked. A regression of the entry below, a few
hours old: the sub-tree's reveal handed the outer list whatever its own scroll could not give of a reveal upwards, and
the first row of a sub-tree is ALWAYS within one row of its top — so every press on it scrolled the list back under
the pointer, between the press and its release. The list is now scrolled only where it really cuts the sub-tree
(`TaskTreeView`). Diagnosed by reading the code, NOT reproduced on screen, and no test (no Compose UI test in the
project): to be confirmed on the next build. Client only.

### Search window: expanded task elements — arrow in the added list, pinned parent row, "add" on the children — 2026-10-07

Three user rules.
- **The added elements list**: a task element has its expansion arrow and shows its sub-tree, as a result row does
  (`SearchSubtree`).
- **The pinned parent row** of the task tree, in the result list and in the added list: the head of an expanded
  element stays over the top of the list once its row has scrolled off and its sub-tree shows there
  (`pinnedItemIndex`); a sub-tree cut by the list pins its own deeper parent at the cut (`TaskTreeView.clipTopWindowY`)
  and the list then pins nothing over it.
- **The children cells** of an expanded element have "add" and "add and remove the others" on their right-click menu
  (`TaskTreeView.onAddTasks`).

**A press on the pinned copy scrolls the list up to its real row**, as in the task tree (user, the same day): an
element's own copy in either list, and a sub-tree's copy too — the list scrolls for what the sub-tree's own scroll
cannot give (`TaskTreeView.scrollOuterBy`).

No test: there is no Compose UI test in the project and all three are drawing and gestures. Not verified on screen —
the pinned row in particular relies on the rows reporting their window position again as the outer list scrolls.
Client only.

### Search window: the search header is compact and narrows like the actions section — 2026-10-07

User rule. The header of the search section (the bar, the kinds, Reset, "All configurations", "Select all", "Add") is
drawn in `CompactFields` — one task cell tall, two rows instead of three and a divider — and laid out at
`COMPACT_SECTION_MIN_WIDTH` at least, cut at the section's edge (`keepsWidthAbove`): narrowing the section hides its
right end instead of squeezing the bar or wrapping the buttons. `SearchWindow.kt`. No test (layout only); not verified
on screen. Client only.

### No "Open each" for a single added element — 2026-10-07

User rule: "When there is only one element in the added elements list, in the action section, there should not be a
section 'Open each'." `SearchDomain.actionsFor` drops it while the list holds exactly one. The Added elements
configurations window lists every action whatever is added, and still shows it. `CalendarBlocksSearchTest`. Not
verified on screen. Client only.

### "Add to the calendar" offered "New alarm here" beside a lone task — 2026-10-07

Anomaly: with only a task among the added elements, the action still showed a "New alarm here" button. It had been
added (2026-10-01) when "add…" stopped opening the element window, as the one way left to lay a NEW alarm — an alarm
row of the list is that alarm, edited — and it was drawn whatever the list held. Removed: the alarms' "creation" row,
once added, is what lays a new alarm (`SearchDomain.calendarDrafts`), counted by "Add" with the rest. The other
"creation" rows still lay nothing there. `SearchCalendarFilterTest`. Not verified on screen. Client only.

### "Add to the calendar": "Right-click time", and both buttons on a dated end — 2026-10-07

User rule: beside "Now", a "Right-click time" button sets the time to the position of the right-click on the calendar,
offered only in a Search window that came from one (`Config.calendarClickMillis`); and when the end is a day and a
time, it has the two buttons too. `CalendarPlacementEditor`. No test (two buttons writing existing fields); not
verified on screen. Client only.

### "add…" on a Sleep period: "Can be added" listed no task — 2026-10-07

Anomaly (account 3): right-click on a Sleep period, "add…", only tasks checked, the filter on "Can be added" — the list
held the task creation row alone. The user: "all the schedulable tasks (those without children, except the root) must
appear in the result list. If the filter was 'can be added without removing anything', then the result list would only
show the task creation element, since the current configurations don't allow any task during a sleep period."

"Can be added" (`SearchDomain.calendarAddable`) also asked that the task's resilience let it run in the periods at the
instant — the stricter state's question. It now asks only that the task is schedulable; the resilience test moved to
"without removing anything" (`calendarTaskStandsIn`). The entry "'add…' at night listed no task" below fixed another
cause (the stored window read instead of the drawn one) and its test pinned the wrong rule ("where it still stands,
they cannot"): rewritten. Consequence: "Add to the calendar" now lays a task over a period that refuses it, since it
lays what "can be added" lists — what the reducer then does with the two was not examined.

`CalendarAddFilterTest`, `SearchCalendarFilterTest`. Not verified on screen. Client only.

### The input changes, the scheduler runs from scratch: one rule instead of three special cases — 2026-10-07

The user, on the entry below: "My previous prompt was only a use case … If each movement of the mouse when dragging a
task panel triggers a rerun of the scheduler from scratch repeatedly, it is only because each change of the input
triggers a new run of the scheduler engine, and not because the logic literally says each movement of the mouse
triggers a rerun. Make sure that docs\scheduler_requirements.md is strictly satisfied."

**Withdrawn** (the three entries below describe them): the reducer laying ten seconds for three calendar intents
(`withFirstSecondsLaid`), the restart of the held plan at every pointer movement (`onMotion`, `restartHeldPlan`), the
quiet wait before a held plan, the second loop of stages that planned a held state.

**In their place**, requirements § *Rule state input evolution* and § *Progressive Calculation* as written:
- **One loop of stages** (`runProgressiveStages` over a `PlanInput`) for the stored state and for a held block's
  state alike: the ten definitive seconds first where the line is bare, then the pace.
- **A change of the stored rules made on this device**: the ten-second check is made at the change. Where ten seconds
  are owed the run starts at once, on this device; the devices agree on who plans a second later and the run is not
  restarted for it (`runSignature`, `runLead`).
- **A held block's state is an input**: `App` hands it to the engine when it changes (`planHeld`), each change
  abandons the run before it and starts one from scratch, immediately; nothing is saved.
- **Limits**, both the server traffic budget's (`ServerQuotaTest`, measured: a run at every change on every
  device was over the 512 MB egress budget; as shipped 507.75, before these changes 496): a change that leaves the next ten seconds covered waits a second and is
  planned by ONE device; a change pulled from another device is the elected device's.
- **The held state is not rounded to the minute any more** (user, the same day: "If the user is dragging a task
  panel, pauses seeing the schedule updating, then moves a pixel, they will see the schedule update again"): a
  release never rounded, so the rounding made a held block that had moved an input that had not. The released
  calendar is therefore derived at every pixel of travel on the composition thread — cost not measured.
- **Found on the way**: a run of one task was banked (a server write) once per STAGE of the rules — now once, when the
  run ends (`advanceSchedule`); the breaks' dues were published each time the plan's panels were rewritten — now when
  what places the breaks changed (`breakInputsAt`).

**Only § *Rule state input evolution*'s definition and § *Progressive Calculation* were audited here.** The rest of
the requirements (the rule structure, the score, the breaks, the modes) was last audited on 2026-10-06 and was not
re-read. Not verified on screen. `:shared:longTest` not run. Client only.

### The drag and the scheduler, clause by clause — 2026-10-07

The user restated the rule and asked that it be strictly satisfied: *"When dragging a task panel, it modifies the
scheduler … the scheduler engine doesn't do anything because restarted repeatedly while I move the mouse and doesn't
have time to do anything. When I don't move the mouse, or when I release the mouse click, the scheduler engine stops
being restarted repeatedly and can run normally. At first, it does the extremely fast computing to secure the first 10
seconds … Then, 10 seconds later, at least the 10 next minutes of the scheduler are definitive as well."* Three
clauses were not met by the two entries below.

- **"While I move the mouse … doesn't have time to do anything"**: the held calendar laid ten seconds itself at every
  step, moving or not, and the engine was restarted only when the block crossed a minute. `calendarMovePreview` plans
  nothing any more, and every movement of the pointer restarts the engine's held plan
  (`CalendarElementDrag.onMotion` → `SchedulerEngine.restartHeldPlan`); it finds something only once the hand has
  rested 100 ms.
- **"When I release … can run normally"** and **"10 seconds later, at least the 10 next minutes"**: after the ten
  seconds the reducer lays at the drop, the engine still waited its 1 s debounce and — with a peer online — the
  election. With those ten seconds laid, the plan now goes on at once and locally
  (`requestReschedule(local = true)`), so the pace runs from the release.

`FirstTenSecondsTest`: movements restart it and nothing is found; at rest the ten seconds come first, then ten more
minutes within ten seconds; at a release the plan is past the ten minutes without any wait; the stored state is not
written while holding. Not verified on screen; the cost of planning at every rest of the hand is not measured.
Client only. `:shared:longTest` not run.

### A held block modifies the scheduler — 2026-10-07

The user, on "nothing is saved during a hold … the held calendar shows only the first ten seconds" in the entry below:
"Did you fix this?" It had been reported as a limit instead of fixed. The rule: *"When dragging this task panel, it
modifies the scheduler … the scheduler engine doesn't do anything because restarted repeatedly while I move the mouse.
When I don't move the mouse, or when I release the mouse click, the scheduler engine stops being restarted repeatedly
and can run normally."*

`SchedulerEngine.planHeld` runs the scheduler on the state the held block's release would leave: restarted at every
step of the hand, and once it rests (`HELD_PLAN_QUIET_MILLIS`, 150 ms) the ten definitive seconds first and then the
stages of the pace, through the reducer's own plan intents on a copy. `App` draws what it has found so far
(`engine.heldPlan`), for the blocks held in the calendar and for those held from the Search window. The stored state is
not written — it is what remembers what the held block removed — and a held plan elects nobody, publishes nothing and
records no run. The release plans the stored state afresh (its ten seconds laid in the reducer, entry below).

Cost, not measured on a large account: a full progressive plan starts each time the hand rests while holding.
`FirstTenSecondsTest`. Client only. `:shared:longTest` not run.

### First 10 s: a block dragged off the line is answered as it is dropped — 2026-10-07

The user, after building the fix below: "it still takes time. The moment I dragged the task panel out, a new task panel
should appear almost instantly … At first, it does the extremely fast computing to secure the first 10 seconds of the
schedule and make it definitive. Then, 10 seconds later, at least the 10 next minutes of the scheduler are definitive
as well."

The check was right after the fix below, but the ten-second stage itself ran inside the engine's re-plan: after the
1 s rule-change debounce, and — with another device of the account online — after the election of who plans (a 1 s
probe window, then up to 5 s for the elected device's rules).

- **The reducer answers the press** (`SchedulerReducer.withFirstSecondsLaid`): a block moved, pinned or removed by
  hand that leaves the next ten seconds with a fillable gap gets them filled in the same reduction, no search. The
  engine is told (`firstSecondsLaid`) and its re-plan extends them instead of planning the line again — they are
  definitive, as the requirement says.
- **The held calendar shows them too** (`calendarMovePreview`): while the block is held away from the line, the
  preview lays the same ten seconds, since a release would.
- A first attempt moved the engine's ten-second stage ahead of the debounce for EVERY rule change. It was withdrawn:
  it re-planned inside every burst, changed the plans `PlanOffTheFrameLoopTest` pins, and put the realtime messages
  and egress over the quota (`ServerQuotaTest`: 217 800 of 200 000 messages a month).

Not changed: a rule change made anywhere else (the tree, a priority, a setting) still gets its ten seconds from the
engine after the debounce. `FirstTenSecondsTest` (the drag, end to end: the line has a panel as the drag is reduced,
ten more minutes within ten seconds, the head not rewritten).

**Open, found on the way**: `ManualLookAwayTest` is intermittent on this machine — it failed for some twenty minutes,
at the last commit too and with or without these changes, and passed before and after. Not investigated.

Client only. `:shared:longTest` not run.

### First 10 s: a bare line inside a Sleep window waited for the whole first stage — 2026-10-07

Anomaly: "As the now line retracts the sleep period, it creates in its way a task panel that was growing. I dragged it
to the past, then observed the now line that moved forward without creating a new task panel. It only did after some
times. This violates the **first 10s** part in docs\scheduler_requirements.md."

The mechanism was there (`dispatchProgressivePlan` → `SchedulerDomain.firstSecondsGapFillable` → a ten-second
`RefreshSchedule` with no search), but its check asked whether a task could run in the gap against the periods AS
STORED: inside the schedule's Sleep window every task's resilience is 0, so it answered "nothing to schedule" and the
quick stage was skipped. At a screen that window gives way to the line — which is why there was a panel to drag in the
first place. `firstSecondsGapFillable` now takes the line's mode and leaves out the periods that give way to a mode-1
line (`retractsAtLine`, the fill's own predicate); the engine passes `tpModeNow()`.

Not changed: the 1 s rule-change debounce still comes before the ten-second stage. `FirstTenSecondsTest`. Client
only. `:shared:longTest` not run (the fill, the score and the search are untouched).

### A power cut made the computer "away" from the next boot on — 2026-10-07

Anomaly: "there is a sleep period right before the now line, even though I've been on the screen. Does it mean the
tests didn't catch such anomaly?" Measured, not read for: the engine's no-screen evidence grew by exactly the time
elapsed between four launches (877, 886, 901, 919 min); the app's own reading of the Windows power log on the release
machine (`deviceLockedIntervals`) ended on a span **21:09:53 → now**; and Windows itself said the machine had been up
since its boot at 21:09:42.

The computer lost power at 21:07 on 2026-10-06. Windows records that as event `6008`, written at the NEXT boot with
the real instant inside it. `WindowsPowerLog` parsed that instant in the PowerShell script from the record's date and
time STRINGS, which are locale text — and on this machine (`fr-FR`) the date carries invisible left-to-right marks
(U+200E), so `[datetime]::Parse` threw, the `catch` swallowed it, and the power cut kept the boot's stamp (21:09:53),
eleven seconds AFTER the boot's own event. The log then read "up, then down, and nothing since": the computer away for
as long as the user sat at it. Hence the Sleep band not retracted behind a line at a screen (the stretch was not
"known unlocked"), the same stretch counted as no-screen evidence (on-screen work not banked over it), and the
"add…" anomaly below reading a Sleep window where the calendar should have cut it.

- The instant is now read in Kotlin from the record's BINARY time (`WindowsPowerLog.powerLossMillis`, a local
  `SYSTEMTIME`, no locale); the script only prints the bytes.
- A `6008` whose instant cannot be read is put a millisecond BEFORE its stamp, never after: at the boot it is a flip
  the debouncer cancels, not an absence nothing closes.
- Verified against the release machine's real log: the open span is gone, and an earlier power cut the same day
  (16:45) is now read where it happened.

**Why no test caught it**: the tests fed `transitions` synthetic `<millis>,<id>` lines — the Kotlin half. The half
that failed was the PowerShell parse of a real Windows record, which no test ran, and its failure was swallowed by an
empty `catch`. The decoding is now in Kotlin, where `WindowsPowerLogTest` holds this machine's own record. It predates
today's changes; last night's power cut was what exposed it.

Not repaired: work done at the screen between the boot (21:09) and the fix was treated as away time and may not have
been recorded. Client only.

### "add…" at night listed no task — 2026-10-07

Anomaly: "right-click > add... > type selector > only task: there is only the task creation element in the result
list … It means that there is no schedulable tasks, which is not true." Read off a copy of the release state: the
right-click was at 03:14, inside the schedule's Sleep window (23:14 → 07:45), with the user at the screen. The filter
read the periods off the STORED panels, where that window covers the whole night — `kindsAt = [sleep, no screen]`, and
none of the 123 schedulable tasks is resilient to it — while the rules, and the calendar, cut the window where the line
crosses it at a screen (the plan itself had a task panel over that instant).

`SearchDomain.calendarKindsAt` now takes the periods as the calendar draws them where it shows the instant
(`SearchDomain.drawnPeriodKindsAt`, injected by `App` from the display derivation: `CalendarLayersHolder.periods`), and
the stored panels only outside that. The filter, its "without removing anything" state and "Add to the calendar"
all read it. Not changed: an instant AHEAD of the line inside a window that still stands there keeps refusing the
tasks that window refuses.

Client only. `CalendarAddFilterTest`. Not verified on screen.

### Search: the calendar filter has three states — 2026-10-07

User request: "When the user right-clicks on the calendar, clicks 'add...', opens the Search configurations window,
there must be a filter for three states: what can be added without removing anything where the user right-clicked,
what can be added, or no filter." The filter was a switch (what can be added / nothing).

- **`SearchDomain.CalendarAddFilter`**: `KeepingEverything`, `Addable`, `None` — the switch `Filters.calendarAddOn`
  and the new `calendarAddKeeping` (a stored field with a default: a configuration written before it keeps whatever
  can be added, as it did). The Search configurations window shows the three in a drop-down; "add…" still opens on
  "can be added".
- **`calendarAddKeepsEverything`**: the add simulated by the reducer that performs it, over the span "Add to the
  calendar" would lay (`Config.placement`), on the state cut down to the span's surroundings.

Client only. `CalendarAddFilterTest`.

### The unfocused-notif window opened while the app was in use — 2026-10-07

Anomaly: "I was on the calendar, when a notification happened and opened the unfocused notif, even though it is only
meant for notifications when the app is not in focus." Read off the release state (a copy): the app started at
03:01:04, the window that opened says the app lost the focus at 03:01:13.620, and the notification of 03:01:20 brought
it up — the user was in the app throughout. "In focus" was `LocalWindowInfo.isWindowFocused`, which is the main
content's keyboard focus, not the application's: it turns false while a menu or a drop-down inside the app holds the
keyboard. (That a menu was open at 03:01:13 is inferred, not logged: nothing recorded focus changes.)

- **`LocalAppInFocus`** (new seam, `docs/PLATFORMS.md`): the entry point says whether the app has the focus. The
  desktop injects the OS's answer — one of the app's windows is AWT's `activeWindow` (`main.kt`,
  `rememberAppInFocus`, event-driven). Where nothing is injected (Android, iOS, the browser) the window's own flag is
  still read: the same anomaly is possible there, listed as an open gap.
- The window's opening is now logged with the notification it answers and since when the app was out of focus
  (`collect-diagnostics.bat`).

Not verified on screen. Client only; no test covers an OS focus change.

### Held and released look the same, in the calendar too — 2026-10-07

The user, on the audit below leaving "only task panels retract under a held period; two periods that would merge on
release are shown as two boxes until then": *"The only difference there must be between keeping the mouse click and
having released it is that whatever got removed when the dragged element got there is remembered if the mouse click is
not released. If period A is dragged to period B and period B doesn't have to retract, then it doesn't. If it must
retract, it retracts."*

The drags made IN the calendar now draw the released calendar too, by the release's own code — the mechanism the
Search window's drag got in the entry below. A day column tells `App` what it holds
(`CalendarElementDrag.heldInCalendar`, replacing the layers-only `heldPeriod` of a few hours earlier), `App` derives the
whole calendar from the state that release would leave (`LocalHeldCalendarRecords`), and the week view draws it in a
second column per day under the column that holds the press, which is not seen while it does. The gesture's nodes
never move under the press. The column's own drawing-only previews remain underneath, unseen; they are to be removed.

`HeldDragPreviewTest` (a period held over a period of its kind, of another kind, and moved on: equal to the release at
every step). Not verified on screen; the cost of the preview on a large account is not measured. Client only.

### The drag specification, audited point by point — 2026-10-07

The user: "Make sure the project strictly satisfies all this." Three points were not strictly met.

- **The blocks held from the Search window did not behave as dragged blocks** — they were blue outlines over an
  unchanged calendar. Now the calendar is the one a release would leave, computed by the release's own code:
  `calendarMoveOf` says what a release does (used by `onCommitBounds`), `calendarMovePreview` runs the same moves
  through the reducers on a copy of the stored state, and `deriveCalendarDisplay` draws that state
  (`shownState`, `shownFrozenBreaks`). What a held block makes disappear disappears and is back when it moves on;
  what comes with it moves alongside; a period is cut by a mode-1 line, in the preview and at the release
  (`CalendarElementDrag.targets`). `HeldDragPreviewTest`.
- **"A field to select which block"** was an instant. It is the check-box drop-down over the blocks the added
  elements have on the calendar, checked by default on the ones right-clicked (`CalendarDragEditor`).
- **The "no screen" a dragged sleep period comes with stayed where it was until the release** (the 2026-10-05 entry's
  own "not done"). A held box tells `App` where it is (`CalendarElementDrag.heldPeriod`) and the layer records are
  the released calendar's (`shownCalendarRecords`).
- **The edges rule ignored the layers**, which the user's example names. `vacatedPastFill` takes `layerKindsAt`
  (`SchedulerReducer.layerKindsAt`, injected by `App`).

Checked and already met: the mode-1 retraction of a dragged sleep period and its reappearing whole on the other side
(`PeriodAtLineTest`, now also for the schedule's Sleep band and the breaks, through the one gesture); the double click
taking the top of the priority rank (the drawing order is the rank's; the chip was the one exception, fixed earlier
today).

Not verified on screen: every gesture here is tested through its logic only. Performance of the held preview on a
large account (a reduce and a calendar derivation per minute of pointer travel) was not measured.

Client only. `HeldDragPreviewTest`, `CalendarElementDragTest`, `VacatedPeriodFillTest`.

### What stands where a dragged period was; a reminder's chip can be dragged — 2026-10-07

The last two points of the user's drag specification.

- **The edges rule** (*"what appears at its original place can't be period A … the program looks at what is at the
  edges … If it is different at the start and at the end, then the simplest solution is … only 'inactivity'"*):
  `SchedulerDomain.vacatedPastFill`, applied by the reducer where a stored period is moved, where a Sleep window or
  hour before bed is dragged, and — through `SchedulerIntent.FillVacatedBreak` — where a screen break is. Behind the
  line only. The same thing at both edges is what is put (its periods' kinds, its task's work); anything else is an
  `inactivity` period; bare edges put nothing. In the move's own unit for the periods. A resize is not a drag
  elsewhere. This replaces "the work cut out where a moved break used to stand is not brought back".
- **The chip** (*"the block (or chip) being dragged is the one at the top of the priority rank"*): the press already
  went to the top of the rank except on a reminder's tag, which had no move. `ReminderTag` takes
  `clickOrDoubleClickHeldDrag`; its release saves the tag as its edit window does, pinned. **The check-off now waits
  for the double-tap window** (a mouse click; a touch tap is immediate), so a drag does not check the tag. The Search
  window's "Drag on the calendar" holds tags too.

Client only. `VacatedPeriodFillTest`, `DraggedReminderTagTest`, `CalendarElementDragTest`.

### Search: "Drag on the calendar" — 2026-10-07

From the user's drag specification: *"In the action section of the Search window, add the action to drag … they click
on the 'drag' button while keeping the click pressed, which puts focus on the calendar and the chosen blocks are
following the mouse until the mouse click is released."*

- **`AddedAction.DragOnCalendar`**, among the calendar's general actions (`CALENDAR_ACTIONS`): a "the block at" day
  and time (`CalendarDragEditor`; it starts on the right-click the window was opened from,
  `SearchDomain.calendarDragDefaultAt`; in memory only) and a **Drag** button that is HELD.
- **`CalendarElementDrag`** (new, provided by `App` as `LocalCalendarElementDrag`): what the button and the calendar
  share. The press holds the blocks the added elements have at that instant (`calendarDragBlocks`,
  `SearchDomain.calendarDragTargets`) and brings the calendar to the front; they follow the pointer, all moved by the
  same time; the release commits each through `onCommitBounds`.
- Limits: only blocks on the days the calendar shows are held; the field's choice is not kept across a restart.
  (The outline-only preview and the instant field of this first version were replaced the same day — entry above.)

Client only. `CalendarElementDragTest`.

### Any block can be dragged, and it then gets a blue outline — 2026-10-07

Anomaly 2026-10-06: "I tried to drag a past 15min screen break but I couldn't." A first answer the same day gave the
past break band a gesture and a commit route of its own — an exception beside the rule. The user: "Did you add an
exception on the logic instead of changing the logic? Any block in the calendar is supposed to be draggable", then the
rule: "Any block can be dragged, it then gets a blue outline." The blocks with no object behind them — a screen
break, the Sleep band, the hour before bed — were an exception list; the first answer is replaced.

- **One gesture, one preview, one release.** `Modifier.periodBoxGesture` (the period box's gesture, extracted) is
  worn by the period boxes, the Sleep band and the break bands; a held band is a `PeriodSegment` of its kind, so the
  line's cut (`periodAtLine`), the retraction of what it refuses and the release are a period box's. The ring keeps
  its own (`doubleClickHeldDrag`, extracted from `AlarmMarker`, unchanged).
- **A Sleep window or an hour before bed dragged** (`SchedulerIntent.PlaceDerivedPeriod`, `commitBoundsIntent`'s
  `derivedPeriodKind`): the night leaves the schedule (`SleepSchedule.skippedWakeEpochDays`,
  `skippedBeforeBedEpochDays` — new persisted fields with defaults) and stands as a period the user placed. Two
  History Units in the calendar history. A recorded past Sleep window moved becomes the user's `sleep` period
  (`reduceUpdateTaskPanel`). Restating the schedule's hours keeps the skipped nights.
- **A screen break dragged, behind the line or ahead of it** (`SchedulerDomain.placeBreakByHand`,
  `SchedulerEngine.placeBreakByHand`): banked `byHand` behind the line; `BreakMachine.HandPlaced` ahead of it (the
  machine lays none of that role where it was and takes it where it was put). `BankedBreak.byHand`,
  `screen_break_history.by_hand` (**SQLite schema v18, `17.sqm`**), `BreakMachine.State.placed` (JSON, default
  empty). Blue through `HAND_BREAK_ID_MARK`. `SchedulerDomain.moveBankedBreak` and `SchedulerEngine.moveBankedBreak`
  (2026-10-06) are gone.
- Open: a dragged Sleep window has no hour before bed of its own; a break's move is not an Undo unit.

Client only (no Supabase change: the sleep schedule rides the state's JSON). `MovedPastBreakTest`,
`DraggedSleepWindowTest`, `SchedulerStoreTest` (v17 -> v18). `:shared:longTest` not run.

### A window that closes takes the windows opened from it — 2026-10-06

User request: "When the user closes a window which another window originates from, then this window gets closed too."

- **`WindowFrameHost.closeOpenedFrom`**: the windows whose `tabParentOf` is the closing one are closed by their own
  close (so theirs follow), all marked *closing* first so the focus is handed to a window that stays.
- Called from `App`'s `updatePlacementById` when a row goes from visible to not — the ✕, a tab's ✕, the lateral
  menu, "close selection", Reset and an undone opening all pass there. Reducing closes nothing.
- `closeWindowCopy` writes the row before leaving `windowCopies`, so a Search copy's configurations window still
  names it when it closes.

Client only. `TabSelectionTest`.

### The window bar groups a window with the one it was opened from — 2026-10-06

User request: the tab of a Search configurations window stands right of its Search window's, a line under the two;
the same for a Search window the calendar's right-click opened, beside the calendar; with all three, a line under the
first two and, above it, a line under the last two.

- **`WindowTabGroups`** (new, pure): the bar's order (a window, then those opened from it, in opening order) and its
  lines (from a window to the last of those opened from it, told by depth; the deeper one drawn nearer the tabs).
- **`WindowFrameHost.tabParentOf`**, answered by `App`: a configurations window's target Search window, the calendar
  for a Search window holding a `calendarClickMillis`. Derived, nothing stored. The Added elements configurations
  window follows its Search window alike.
- The Shift+click range and the bar menu's selection follow the order the bar draws (`tabLayout.order`).

Client only. `WindowTabGroupsTest`.

### Each Search window has its own configurations window — 2026-10-06

Anomaly (user): "All configurations" pressed in two distinct Search windows led to one and the same Search
configurations window. There was one, and the button re-pointed it (`ConfigurationSearch.target`) at whichever Search
window pressed last. The button now brings back the configurations window that is on ITS Search window, else opens
one — the original where it is not open, else a copy (`App.openConfigurationsOf`). Same for the Added elements
configurations window. Client only; no test (the rule lives in `App`'s composition).

### The settings sections are as compact as the task tree — 2026-10-06

User request: the Search configurations window and the Search window's actions section were not compact enough —
Material's 56 dp text fields beside 28 dp task cells, wide gaps, a 48 dp Reset — and narrowing a section wrapped its
rows, so it grew downwards as it shrank.

- **`CompactFields`** (`ui/CompactSection.kt`, new): inside it the app's one text field (`OutlinedTextField`) draws
  as `CompactTextField` — one task cell tall (`TASK_ROW_MIN_HEIGHT`), the cell's text size, the drop-down faces'
  outline, its label in the placeholder's place — and the drop-down faces, `ResetButton`, `FrameButton` and
  `ToggleChip` take the same height (`fieldFaceVerticalPadding`, `buttonVerticalPadding`). Material's 48 dp minimum
  touch target is off there. Read by the controls, so the editors embedded in the actions (a quota's, a reminder's,
  a timer's) are compact there and unchanged in their own windows.
- **Wrapped**: the Search configurations window, the Added elements configurations window and the Search window's
  actions section. Rows 2 dp apart (`COMPACT_ROW_GAP`), a setting's name on one line.
- **Shrinking hides, never wraps** (`Modifier.keepsWidthAbove(COMPACT_SECTION_MIN_WIDTH)`, 440 dp): narrower than
  that, the content keeps its layout and is cut at the section's edge. Buttons, chips and explanations
  (`NoteText`) are one line whatever the width.

Client only. No test: layout only (`:shared:jvmTest` unchanged).

### Every window has a colour — 2026-10-06

*"Give a color to every window. A window also keeps the same color (not updated when there is a change like for task
cells). Take the 256\*256\*256 color space. For all the default windows, separate the cube into equivalent parts. When a
new window is created by duplication, it gets the color that is in the furthest position from all currently used
colors and the edge of the part of the cube of the default window."*

- **`WindowColorSpace`** (new): the cube cut into one equal part per kind of window (33 kinds, `DEFAULT_WINDOWS`); a
  kind's own colour is its part's centre, and another window of the kind takes the point of the part furthest from
  the colours in use and from the part's edge (`pick`, exact over the part).
- **`WindowFrameHost.colors`**: given once in `register`, dropped in `unregister`, kept on the local `WindowColors`
  placement row so a window comes back in its colour. Never synced.
- **The head and the tab are filled with it**, their text and buttons in `TaskPalette.foreground` of it. The tab's
  states lost their fills: the focused tab has a 3 dp outline, a selected one 1.5 dp, a reduced one oblique type
  (replaces the primary-container fill, 2 dp border and semi-bold title of 2026-10-01).
- **A changed notifications window stops gathering** (`SearchDomain.isUntouchedNotificationsWindow`): the next
  notification out of focus opens a new one beside it — it used to bring the changed one back in front.

Client only. `WindowColorSpaceTest`.

### The notifications window is called "unfocused notif" — 2026-10-06

The Search window the app opens on what fired out of focus read "Search" like any other. Its head and its tab now
read "unfocused notif" (`SearchDomain.windowTitle`, off `isNotificationsWindow` — derived from the window's
configuration, nothing stored; `NotificationsWindowTest`). A Search window opened by hand is unchanged.

### Search: the scheduler engine's sets of rules are listed with the history units — 2026-10-06

The user asked for the set of rules behind "notifications from the scheduler engine", then corrected it: *"I was wrong
in my previous prompt, the notifications should not lead to the set of rules output. Filtering for only history units
from scheduler engine should only show the history units of new set of rules found. Dragging blocks in the calendar
should be considered history unit from calendar."* (The first reading — runs listed among the notifications, with an
"Information" action there — was built and is removed.)

- **Each set of rules the scheduler engine found is a row of the HISTORY UNIT kind** (`SchedulerRunEntry` — a re-plan,
  a horizon extension, rules adopted from a peer, a mode switch; `SearchDomain.itemResults`' `schedulerRuns`, threaded
  from `TaskSchedulerViewModel.schedulerRuns`, which keeps them in memory), named by the event it was.
- **"Scheduler engine" is a choice of "Made in"** (`SearchDomain.MadeIn`, `MADE_IN_CHOICES`; `Filters.historyEngine`
  behind it), the first of the list, beside the windows: it lists those rows alone. It was a filter of its own for an
  hour (*"Why isn't scheduler engine in 'made in'?"*) — the engine is where those rows were made, so that is where
  the user looks for it. It is not added to `HistoryWindow`: nothing focuses it, and the History window lists it as a
  source of its own. A unit's other filters (category, undone, changed element) and a window picked in "Made in" leave
  the engine's rows out — a set of rules is in no stack and is never undone.
- **The history units' "Information" action** shows a run's facts — the now line and its mode, the horizon, the rule
  state it read and the set of rules it returned — from the History window's own list (`historyEntryInfos`).
- **A block dragged or resized on the calendar is made in the calendar** (`App.dispatchFromCalendar`, the existing
  `SchedulerIntent.MadeIn`): its unit was stamped with whichever window had the focus when the press landed.
- **Notifications are the posted ones alone.** Their source filter no longer has "scheduler engine" (it had meant "task
  to do now", then the runs); the "Task to do now" notifications keep their own source.

A run's row id is what the run is, never its place in the list; runs live for the session, so an added one lists
nothing after a restart. `NotificationsWindowTest`.

Anomaly the same day: *"I filtered for history units from scheduler engine and got nothing."* The app had just been
restarted. A launch that keeps the plan it was closed with runs nothing (`planHoldsAtLaunch` — a restart is not a rule
change), and the runs are kept in memory, so the list was empty until the plan was next extended or re-made. The launch
now records the set of rules IN FORCE as a row of its own (`SchedulerRunEntry.Kind.KeptAtLaunch`, "Plan in force at
launch", `SchedulerDomain.planInForceRun`): dated when the plan was made, its rules read at the launch. The runs of
earlier sessions are still not kept (they were never persisted, by design: hundreds of KB of text per save).

Then: *"I don't see the set of rules input in the history unit from the scheduler engine. According to
docs\\scheduler_requirements.md, it must be a text with if...then...else clauses and instructions, so easy to read."*
- **The set of rules is written as the requirements' § *Rule Structure* asks** (`SchedulerDomain.describeScheduleRules`).
  It read `+0:00:00 → +0:45:00  run A  else B`: a table to decode. Each rule is now its interval (whose end is the
  trigger for the next), `if A is accepted, then run A until …`, and `else run B on [now-line; now-line + 0:10:00],
  then the scheduler runs again` — one `else` per stretch where the alternative changes inside the run, `else nothing`
  where no other task may run, and the mode-1 clause of a run held at the line. A dynamic period is `restrict [kind]`
  under its own interval. The head carries the triggers that are no instant of the timeline: a mode switch, a rewrite
  of history or of the rule state input.
- **The "Plan in force at launch" row carries its rule state input** — the state's own, read as a fill reads it
  (`planTasksOf` + `describePlanRule`); it was a placeholder line.
`SchedulerRuleSetTest`.

Then: *"I don't see it in the action section of the history unit made from the scheduler engine."* Not reproduced; the
one cause the code shows is that the set of rules was the LAST line of "Information", under the whole rule state — one
line per task of the account. Two changes: **"Set of rules" is an action of its own** (`AddedAction.HistoryRules`),
listed first for a history unit: the rules text and "Copy the set of rules" (and a line saying so when the run is no
longer in memory, or when no scheduler-engine unit is added); and in a run's information the set of rules now comes
before the rule state, in the History window too.

### Search: an empty list of one kind offers to create one — 2026-10-06

Asked for as: *"In the Search window, when only one element type is selected and that the result list is empty, the
corresponding creation element must appear in the result list."* `SearchDomain.withCreationWhenEmpty`, applied where
the window builds its list: one kind checked, nothing found, and the kind is one the user can make (`CREATABLE`) — the
list is that kind's "creation" row, the same row the "creation" kind lists, so it opens and is added like it. Several
kinds, or a kind nobody makes by hand (history unit, notification, shortcut, calendar block…), still list nothing.
`CalendarBlocksSearchTest`.

### A period the user stated gives way to a mode-1 line, without ever being rewritten — 2026-10-06

The first of the audit's open findings (entry below), on the user's go-ahead. The requirements: *"When the $now line$
is in mode 1 and reaches a 'no screen' period that extends to [t1;t2], I want the 'no screen' period to become
]$now line$;t2] when $now line$ is in [t1;t2[. When $now line$ >= t2, then this 'no screen' period is removed."* That
held for the periods a rule lays (the sleep schedule's, cut where they are drawn) and not for one the user drew, which
stayed whole behind a line at a screen.

- **The statement is never rewritten.** Trimming the stored panel as the line crosses it would change the rules
  (`schedulingSignature`) and re-plan because time passed. What the line crossed at a screen is kept BESIDE the period
  (`SchedulerState.periodCrossings`, a `PeriodCrossing` per panel id — the line's own history on this device, like the
  breaks it banked: persisted locally, never synced), and the period every reader sees is the statement less that:
  `SchedulerDomain.afterCrossings` / `statedPanels`, the ONE reading the plan's environment, the break machine's, the
  plan-mismatch check, the calendar (and the layers a period lays) and the Search window's calendar readers take.
- **Banked at two armed triggers, never per tick** (`domain/AtScreenWalk.kt`, called by the engine's one interpreter
  beside `freezeSleepBehindLine`): the end of the next period that gives way, and the edge out of mode 1
  (`SchedulerIntent.RecordAtScreenCrossing`). The trigger is armed again where it fired and where the panels changed.
  Between two triggers the calendar takes the stretch being walked live (`SchedulerEngine.atScreenSince`).
- **Stating a period again starts it afresh** (`PeriodCrossing.sinceMillis`, set where the reducer resolves a drawn,
  moved or resized period): what the line crossed before is about a period that is no longer this one, so a period put
  over the past — rewriting history, which the requirements allow — stands whole.
- Which periods: the ones the user placed that are or carry "no screen" (`SchedulerDomain.retractsAtScreen`, the plan's
  own predicate) — a hand-drawn `sleep` period as well as a "No screen" one.

Known limits: a process that ends in mode 1 loses the stretch it had not banked yet; a period crossed in its middle
stands in two pieces, and the second (id `…~1`) cannot be edited by itself.

`PeriodCrossingTest`, `AtScreenWalkTest`. A new persisted field with a default (a payload without it has no crossing;
a crossing of a panel the state no longer holds is dropped on load). No Supabase change.

**The tolerances** (the audit's third finding), read one by one:
- `BREAK_EDGE_TOLERANCE_MILLIS` 1 s → **1 ms**: the exact amount the half-open form of a dragged break moves an edge
  by, which is the one way two readings of that edge differ. The whole suite passes with the exact figure.
- `LOOK_AWAY_START_FRESH_MILLIS` (2 s) was wrongly listed: it is how late a spoken cue may still be worth saying, a
  rule about notifications, not a tolerance on the schedule.
- `CARRIED_MACHINE_LEAD_MILLIS` (60 s) and `MIN_INACTIVITY_BAND_MILLIS` (90 s) are unchanged. The first needs the
  display and the engine to read the line off one clock; the second is the definition of how short an absence still
  counts, used by five derivations. Neither is a one-line change, and neither was attempted.

### Scheduler audit against the requirements: a lag is counted in the task's own minimum time — 2026-10-06

Asked for as: *"In the logic of the scheduler and the display of the schedule, make sure that everything strictly
satisfies the docs\\scheduler_requirements.md file. Make sure that the logic doesn't contain mendings added to solve
specific anomalies to avoid changing the fundamental logic and solving more global issues."*

**Changed — the score's definition** (`ScoreModel.lagWeight`, `docs/scheduler_score.md` § *Criterion 1*). The
simulations of the entry below had found the plan leaving up to 31 % of the schedulable time to nobody and
short-minimum tasks at 0.63× their share (about half on sixty tasks). The cause was fundamental, not a search
shortfall: criterion 1 summed lags in bare minutes, so a task under-served by a given FRACTION of its share cost
`M_i²` — 81 times less for a 5-minute minimum than for a 45-minute one. Each task's lag (and its shortfall, derived
from it) is now counted in units of its own minimum: the same relative miss costs the same for every task. Same
accounts after: 0 % to 5 % left to nobody, 0.88× at worst. The whole suite kept passing but two tests that pinned the
old unit (`ScheduleImproverTest`'s shortfall formula; `ScheduleCycleTest`, where the requirements' A30 B15 C15 example
now comes out with the two identical tasks B and C the other way round). `ScheduleSimulation.TOLERATED` tightened to
15 % / 0.75× / 1.6× from 40 % / 0.5× / 2×; the desktop solver's objective carries the same unit.

**Verified against the text, unchanged:** the three bars (20 min after a 20 s break only; 1 h after ≥ 5 min of "no
screen"; 2 h after ≥ 15 min), the 5-min break's first minute, the alternative's `d` of 10 minutes, the pace (10 s →
10 min) and the first 10 s. Two comments that still described rules since removed were corrected (the 20 s bar
"after ANY dynamic period"; the fill's "once an hour" staleness bound).

**Found and NOT changed — each needs a decision or its own piece of work:**
- *A "no screen" period the user drew is not retracted by a mode-1 line.* **Done the same day — the entry above.**
- *The runtime is not purely cursor-driven.* § *Rule Structure* forbids timeline-wide scans as the line moves; the
  engine still has a 30 s advance tick, a cue sweep that scans a window behind the line
  (`LOOK_AWAY_SWEEP_CAP_MILLIS`) and a 200 ms poll while a manual look-away counts down.
- *Tolerances that stand where an exact rule would*: `BREAK_EDGE_TOLERANCE_MILLIS` (1 s), `CARRIED_MACHINE_LEAD_MILLIS`
  (60 s), `MIN_INACTIVITY_BAND_MILLIS` (90 s), `LOOK_AWAY_START_FRESH_MILLIS` (2 s). Each papers over two clocks or
  two derivations of one fact disagreeing by a little; the global answer is one reading of the fact.
- *"Nobody" in the rollout.* With "no idling" gone from the requirements, leaving time to nobody is a priced choice;
  but on one account offering it at every decision made the plan's own score WORSE (7.50e19 against 7.01e19): the
  rollout's window closes on an optimistic bound for a lag that is behind. A search-quality fault, allowed as
  degradation, not yet fixed.

Not audited line by line: the break machine's chain and drag rules, the mode switching around a 20 s break, and the
rule-state transitions — their constants and tests were checked, their code paths were not re-derived from the text.

### Scheduler simulations in two tiers, and the balance of the two criteria as a setting — 2026-10-06

Asked for as: *"Are there automatic tests for very complex situations, like a chaotic timeline with lots of pre-placed
tasks and restrictive periods with hundreds of different task and period ids? Do they test if all those hundreds of
tasks all reach their target when simulating lots of weeks with or without minor or major changes to the state rules
inputs? There should be rapid tests and very long tests…"* — there were none (the largest scheduler fixture held
twelve tasks) — then *"Build it. The balance between the two optimization problems is for you to decide, and if
possible to be adjusted in the configuration section of the calendar."*

- **The simulations** (`shared/src/jvmTest/.../simulation/`): a seeded generator of chaotic accounts, lived through
  for days through the reducer, measured off the banked records against targets computed from the requirements alone.
  The **fast tier** (`ScheduleSimulationTest`) is in every `jvmTest`; the **long tier** (`ScheduleSimulationLongTest`:
  60 / 120 / 200 tasks over two to four weeks, with minor and major rule changes) is `./gradlew :shared:longTest`
  (`-PlongScale`, `-PlongSeeds`). `docs/invariants/scheduler.md` § *Simulations* says which changes owe the long one.
- **What they found, the day they were written.** No hard constraint is broken on any scenario run. But the plan
  leaves 3 % to 31 % of the schedulable time to nobody (15 % on a bare eight-task account, one three-hour gap among
  them), and a task with a short minimum time and a large share gets down to about half its lower target among what
  is served. Neither moves with search time or with the balance below. Both are recorded as the gap between
  `ScheduleSimulation.TOLERATED` (asserted: today's behaviour and a margin) and `GOAL` (printed by every long run);
  the scheduler itself is NOT changed here.
- **The balance** — `J = J_1 + w·J_2`, `SchedulerState.minimumTimeWeight`, "Minimum time weight" under *Scheduler
  engine* in the calendar's configuration section: 0 follows the percentages alone, 1 is the default, more holds the
  minimum times harder (0…100). Decided: the default stays 1 — between 0.1 and 10 the plan barely moves, and only
  towards 0 are panels cut short (38 of 193 on the eight-task account, against none). An ACCOUNT setting (persisted,
  synced, merged like the other settings; not undoable), because plans made on two devices compete on one score, and
  part of `schedulingSignature` (an account at the default hashes as before). It reaches the score in one place
  (`ScoreModel.shortfallCost`) and the desktop solver's three copies of that term. `MinimumTimeWeightTest`.

No migration: the new field has a default, and a payload without it loads at 1.

### Calendar: a dragged task panel no longer jumps back at the release — 2026-10-06

Anomaly (user): *"when I drag a task panel, it glitches a microsecond when releasing the mouse click."* The release
dispatched the move and dropped the drag's preview together, but the records the column draws come out of the state
later (the reduce, then `App`'s derivation): for a frame or more the block was drawn back where it had been picked up,
then jumped to where it was dropped. The preview now stays until the records show the commit (`ReleasedDrag`,
`endDragPreview` in the day column): the block no longer stands at its bounds at rest under its key — moved, or
re-keyed. Asked when the records change, never polled; a release that changed nothing lets the preview go after
`RELEASED_DRAG_WAIT_MILLIS`. The mouse drag and the phone's "move" both. A dragged PERIOD box has the same hand-over
and is not changed here. `CalendarEditChoicesTest`; the hand-over itself is not render-tested.

### Calendar: a sleep period dragged onto a mode-1 line is retracted by it — 2026-10-05

Asked for as: *"If the user drags a sleep period and the block reaches the $now line$ in mode 1, then the dragged block
gets retracted, and when it gets completely erased and the mouse continues, the block appears in full size on the other
side of the $now line$. This is supposed to be a consequence of the requirements: $now line$ in mode 1 is not in 'no
screen'; if a period is added because of a period rule ('no screen' when there is 'sleep'), then if the 'sleep' period
gets dragged away, the 'no screen' doesn't stay there and expand to cover the new position of 'sleep', but moves
alongside it."* A held period box was drawn, and stored on release, wherever the hand had it — across the line whole.

`SchedulerDomain.periodAtLine` says where a period put at a span stands: over a mode-1 line, one that is or carries
"no screen" is ]line; its end] (the requirements' own sentence for a "no screen" period the line is in), nothing once
under a minute is left, and whole when the line is not in it. It is `retractedAtLineSpans` — the plan's one predicate
of "gives way to a mode-1 line" — asked of the period where it is being put, not a rule of the drag. The calendar asks
it at each step of a period's drag and at its release (`LocalPeriodAtLine`, provided by `App` over the live clock and
`engine.tpModeNow()`): the box is drawn over what is left (`HeldPeriodCut`), the task panels it refuses retract under
that, and the release stores that. Released while nothing is left, the period stays where it was. Modes 2 and 3 cut
nothing. `PeriodAtLineTest`.

Not done: a period the user ADDS across a mode-1 line (the menu, "Add to the calendar") is still stored whole, and the
layer hatches a dragged period lays are still drawn where it was until the release.

### Calendar: overlapping periods are each their own box — 2026-10-05

Asked for as: *"If the user adds period A 10h-12h and there was already a period A 11h-13h, there is a blue outline
surrounding the 10h-12h block, and the black outline of the 11h-13h period is still there. Get rid of the current
logic that would make it visually three blocks."* `periodSegments` cut the periods at every boundary and drew each
stretch once (A, then "A, B", then B — the user's own rule of 2026-09), the shared stretch in the strongest hand's
outline: the period just added drew as two boxes, and the outline of the one already there was broken at 12.

It now answers ONE box per period, over its own hours, with its own outline and its own title (`periodSegments`,
`periodSegmentOutline`, `periodSegmentLabel`; the `PeriodSegment` type and its readers are unchanged, each box holding
one period). Two overlapping periods are two boxes overlapping. **This reverses the three-box rule for periods of
different kinds too**, and with it the gesture's: a box moves ITS period (it moved every period in force over a
shared box); where two overlap, the later-starting one is on top and takes the press. A period still never shares the
column's width. `CalendarEditChoicesTest`.

### Calendar: the blue and the orange outlines are above every other outline — 2026-10-05

Asked for as: *"The blue and orange outlines must be above all other outlines in the calendar."* A period box's and a
sleep window's outline were drawn UNDER the task panels (`zIndex(-1f)`): a panel covered the blue or the orange and
redrew it in its own contrast colour, and its plain border cut across it. A hand-placed panel's blue outline was
itself under the grey outline of a screen break drawn over it.

The day column now ends its drawing with an **outline pass** (`CalendarUi.kt`, `outlineOnTop`): every blue (`User`) and
orange (`Pattern`) outline once more, over the panels and the breaks — a sleep window's, a period box's (the box being
dragged too), each slice of a hand-placed panel — as a bare border with no pointer input. The under-panel pass no
longer draws those two (only a grey one), and a panel no longer redraws them in a contrast colour. Grey outlines and a
block's plain border are where they were. Labels, rings and reminder tags stay above. `CalendarPanelOutlineTest` pins
which outlines are the top ones; the stacking itself is not render-tested.

### Search: the notifications are filtered by what they came from — 2026-10-05

Anomaly (user): *"In the search window, I can't filter to only notifications of the scheduler engine."* A logged
notification is a title, a message and an instant: nothing said where it came from, so the only filter the
notifications had was the app's own "since". They now have **"From"** (`Setting.NotificationSourceSetting`,
`Filters.notificationSources`, a check box per `NotificationSource`): scheduler engine (the plan's "Task to do now"),
screen break (its start and its end), sleep schedule (the wind-down), alarm, timer, reminder, keyboard shortcut
(a chord's receipt, "Notifications on"), other.

The source is READ OFF THE TITLE (`NotificationSource.of`), not stored: the notifications already in the log have one
too, and nothing persisted changed shape. The titles are stated once (`NotificationTitles`), which the engine now posts
under (they were literals in `SchedulerEngine`, `RingKind.label` included). Stored with the Search configuration; an
older payload has none, and a source a build does not know is dropped. `NotificationsWindowTest`.

### Enter ends the edit of any field — 2026-10-05

Asked for as: *"Pressing enter in edit mode must exit edit mode for any field."* Enter (the keypad's too) makes a
single-line field give up the focus, as a press outside it does — so what a field does when it is left (reads its
stored value again, closes its typing session) it does on Enter. Stated ONCE: `ui/EnterLeavesField.kt` holds the app's
own `OutlinedTextField` (Material's, plus `Modifier.leavesEditOnEnter`), and the 13 files that drew Material's field
now draw that one; the three bare number fields of the priority windows (weight, minimum time, percentage) wear the
modifier themselves.

- A field that gives Enter a meaning of its own answers first and keeps it (the modifier is the LAST of the chain,
  and a preview key event runs from the outside in): the tree's cells, the Search bar, the find & replace bar, a task
  picker, a lateral-menu button's name.
- A field of several lines (a task's text) keeps Enter as its new line. A chord (Shift+Enter…) is not Enter.

`EnterLeavesFieldTest` (jvmTest: real key events on a rendered field).

### A time field's right-click menu: add or remove 1 h, 10 min, 1 min — 2026-10-05

Asked for as: *"In a field where the user can configure a time in the format hh:mm, add a right-click menu with the
option to add or remove 1h, 10min, or 1min."* ONE menu (`ui/TimeNudgeMenu.kt`: `TimeNudgeMenu`, `TIME_NUDGE_MINUTES`,
`nudgedTimeOfDay`), wrapped round every `HH:MM` field: a quota's start and end times and a particular loop's end
(`TimeOfDayField`), "Add to the calendar"'s start and end, an alarm's time (the Alarms window's row and the Search
action), a reminder's (its window's row and the Search action) and the sleep schedule's three. It stays open, like a
timer's countdown fields' menu, and leaves on the first press outside it.

- A time of day steps round the clock (23:30 + 1 h = 00:30). "Add to the calendar"'s start and end are instants, so
  they carry the day; the sleep DURATION stops at 0 and at 24 h.
- The Search actions step every added alarm / reminder from its OWN time, so the menu works where the field reads
  nothing because they differ. A reminder whose time is not defined has nothing to step from.
- An alarm row's step is a History Unit of its own (never absorbed into the typing session before it).
- Not given the menu: the `YYYY-MM-DD HH:MM` fields (the calendar filters' positions, a quota's instants), which are
  a date and a time in one text. Touch has no stand-in for this right-click yet (`docs/PLATFORMS.md`).

### Search: "Add to the calendar" has a start and an end, and the blocks of an element are a search — 2026-10-05

Asked for as: *"In the actions for an element that can appear in the calendar, add configurations for the start and end
and a button to add in the calendar. It is like in the actions for quota where the user can configure the start and end
of a loop. A button opens a Search window showing in the search result all the blue outlined blocks of this element in
the calendar."*

- **"Add to the calendar"** (`AddedAction.PlaceOnCalendar`, `ui/CalendarPlacementEditor.kt`) is the quota loop's own
  fields (`DayField`, `TimeOfDayField`, `LengthField`, made `internal`): a START (a day, a time of day, "Now") and an
  END stated by a switch as a LENGTH after the start (1 hour by default, see the follow-up below) or as a day and a
  time of its own. They are the Search window's (`Config.placement`, local-only, stored with
  the configuration; an older payload has none). Until a start is given it is the calendar filter's position
  (`SearchDomain.placementStart`), so the calendar's "add…" lays where it did. It replaced *"Give the calendar filter a
  position first"*: the action no longer needs the filter. An end not after the start is shown as an error and nothing
  is laid (`placementRefused`). A panel and a period take the end (`calendarDrafts(endMillis)`); a tag, an alarm's ring
  and a timer's end are an instant, at the start.
- **"Blocks on the calendar"** (`AddedAction.CalendarBlocks`) opens a Search window on a new kind of row, **calendar
  block** (`Kind.CalendarBlock`, `SearchDomain.calendarBlocks`): every block the calendar outlines in blue — a panel
  `SchedulerDomain.panelOutline` calls `User`, a reminder's tag, an isolated alarm, a timer's ring moved there — of the
  added elements (`Filters.blocksOf`, their Search keys; `blocksSearchConfig`), in the timeline's order
  (`SortKey.BlockStart`). The filter is listed in the Configuration Search window ("Blocks of", with its ✕). Opening a
  block's row opens what is on the calendar at its start (`calendarAtConfig`, the calendar's own "edit…").
- Both are GENERAL actions, listed in the top right quarter only while the added list holds an element that can be on
  the calendar (`SearchDomain.actionsFor`, `CALENDAR_ACTIONS` over `CALENDAR_ADD_KINDS`): a list of categories no
  longer shows "Add to the calendar".

- Follow-up (same day, *"for the end configuration, add a button with a drop-down field with 'days', 'hours'
  etc"*): the length is a NUMBER and a unit's drop-down (`QuotaDomain.LengthUnit`: minutes, hours, days, weeks;
  `lengthUnitOf` / `lengthIn` / `parseLengthIn`) instead of a typed `1d 12h`. `LengthField` is the one field, so a
  quota's loop length reads the same way. A stored length shows in the largest unit it is a whole number of, and the
  unit never changes under the hand typing the number.

- Anomaly (user, same day): the start read `20:30`; a backspace at its end and it read `20:03`. `TimeOfDayField` kept
  its text keyed on the stored time, and `20:3` IS a time (20:03): written at once, it came back as the field's whole
  text. Typing `2` on the way to `20:45` did the same (`02:00`). A field now keeps what is typed while it still says
  the stored value, and reads the stored value's own text only when that was written from elsewhere
  (`draftAfterStoreChange`, `rememberFieldDraft`) or when it is left. The quota's own time fields had the fault too
  (the same field), and so had a resilience percentage (`5.` on its way to `5.5` lost its point).

- Follow-up (user, same day: *"Why is the default … ends its own hours after the start? What does it mean? It should
  be ends 1 hours after the start"*): the length is 1 hour until the user says otherwise
  (`DEFAULT_PLACEMENT_LENGTH_MILLIS`). It was blank, read "its own" and meant each element's own length (a task's
  minimum time, a period's hour) — a placeholder that read as a value. A task's panel laid by the action is now an hour
  long, not its minimum time; `placementEnd` always has an answer.

`CalendarBlocksSearchTest`, `QuotaTest`. No migration: nothing of the account's state changed.

### Screen breaks: only a 20 s break bars the next 20 s break — 2026-10-05

`docs/scheduler_requirements.md` (user edit, f73ee9d): *"After the end of a **20s** break, no 20s break in the next
20 minutes"* (was: of a screen break), and a ≥15-minute "no screen" bars the 15 min break only (it barred the 20 s
break for 20 minutes too). `BreakMachine.endActive` / `absorbHistory` set the 20 s bar only from a 20 s break (one
the app conducted included); `stretchBars` no longer touches it; `BAR_20S_AFTER_ANY_MILLIS` →
`BAR_20S_AFTER_20S_MILLIS`, `BAR_20S_AFTER_LONG_MILLIS` deleted.

**What the user will see:** coming back to the screen after any absence longer than the 20 s break's remaining bar —
a night included — the 20 s break is taken at once (it was barred for 20 minutes after an absence of 15 minutes or
more). A 20 s break may also follow a pose directly.

Two faults the old rule was hiding, fixed with it:
- The 20 s break entered at the return kept the stretch open, so the poses the stretch had taken fell due inside it
  and grew it into a 15-minute break holding the line in mode 3 (a wake would have landed in one). The stretch's
  bars now hold from the line, on the machine and on one rebuilt from history.
- A journey (wake, restart catch-up) told the break machine each stretch's mode at the stretch's END: a locked
  stretch followed by an unlocked one was walked the other way round, and the first stride of every wake at a
  screen. It is told where the stretch starts.

`BreakMachineTest` (two new), and eleven tests re-stated under the new rules.


Question (user): "why is there no sleep periods in the past?" It was written by a step apart from the line
(`maybeMaterializePastSleep`): a scheduled window, where the active sessions showed no device, "while this session
ran". On account 3 the computer is off every night (release log: "the app was not running for 495min", an engine
start at every wake), so the session always starts after the night and nothing was ever recorded.

Now the requirements' own rule (§ *frozen past*, user: "do it if it allows to strictly satisfy" them): the line
freezes the part of a Sleep period it crosses in mode 2 or 3; the part crossed in mode 1 is not (its "no screen"
retracts there). `SchedulerEngine.freezeSleepBehindLine`, in `interpretTo`, armed at the Sleep period's end and at the
mode edge back to 1 — so the wake's fast move and the restart catch-up record the night as they walk it.
`maybeMaterializePastSleep` and its session anchor are deleted. Behaviour changes: a lock history that cannot be read
is walked in mode 2 and so freezes the night; a declared "I'm away" inside the window (mode 3) freezes it too. Not
retroactive. A process ended while the line was away from a screen (a locked computer that restarts) goes on with
that stretch at the next start: the persisted break machine holds where it began (`stretchStart`), so the part of the
Sleep period crossed before the process ended is frozen too. `PastSleepAfterShutdownTest`.

### Quota: every resilience value in one drop-down — 2026-10-04

User request: the quota's resilience action is a button opening a drop-down that lists every period with a field for
its resilience value (it was one period picker, one field and "Apply"). `QuotaResilienceEditor`: a field per period
of `SearchDomain.resilienceKinds`, over every added quota (shared value or "mixed"), written when it parses as
0…100 %; the button's caption counts the periods not at 100 %. The menu is focusable, since its fields are typed into.

### Quota: the progression counted the nights to come and not the nights gone — 2026-10-04

Anomaly (user): "claude limit" read 64.5 % where a script removing sleep gave 43 %. The pace profile read the
restrictive panels of the state, which hold the nights, the wind-down hours and the breaks only from the now-line on
(the last fill's): the loop's past ran at full rate, its future had them taken out. `SchedulerDomain.quotaPeriods`
gives a quota the periods of its WHOLE loop — nights and wind-down hours projected from the sleep schedule, the
user's own periods with their repeats — and no machine-placed or conducted screen break (one-sided too). On a copy of
the release DB: 64.5 % before (reproduced to the digit), 42.8 % after. `QuotaTest`.

### Quota: a refusal that outlived its cause, and fields that kept the caret — 2026-10-04

(Same day, third report: after a REFUSED date the "regular end" button stayed grey — it was enabled only by a stored
end, and a refusal stores none. It is now enabled by a refusal on screen too, and clears it.)

Anomalies (user): a particular loop's end typed one minute past the regular end, then typed back — the red message
stayed; and a click outside the field did not leave edit mode. `TimeOfDayField` reported only a time different from
the stored one, so the real end typed back cleared nothing: it now says so (`onSettled`), and is red only for what is
on screen. The quota's text fields end their edit on the first outside press (`endsEditOnOutsidePress`, the app's
`leaveOnOutsidePress`) and read the stored value again when left.

### Quota: no amount factor, and a refused ending time no longer reads as entered — 2026-10-04

- User: "drop the × quota". `QuotaLoop.amountFactor` is gone from the model, the progression, the row and the stored
  shape; an older payload's factor is ignored (a loop that held nothing else is no longer particular).
- Anomaly (user): a particular loop took an ending time later than the loop's regular end. It was refused and NOT
  stored (checked on the release DB: the loop held its restarts and no end) — but the time field went on showing what
  was typed, with a small line of text under it. Now the field is in error while refused, the row says NOT SAVED, and
  the field reads the real end again once it is left. `QuotaTest`.

### Quota: restarts for the quota, and the particular loops as a list — 2026-10-04

User request: the form under "Particular loops" is removed; a field says how many times the percentage restarts in a
period (2 = 100 % where it would have reached 50 %, then back to 0 %); a button adds a loop to the list of particular
loops, and each element has its own actions — its restarts, its ending time (inside the loop: the quota is at 100 %
from that end to the start of the next loop), its amount factor, ✕.
- `QuotaEntry.renewals` (1) + the "Restarts" action (`AddedAction.QuotaRestarts`, also a default-configuration
  action). `QuotaLoop.renewals` is now nullable (null = the quota's).
- `QuotaDomain.regularBounds` / `particular` / `withParticular` / `endInsidePeriod`; `healed` accepts an end alone
  inside its period.
- `PersistedQuota.renewals`, `PersistedQuotaLoop.ownRenewals`; older payloads read as they meant. `QuotaTest`.

### Quota: the 20 s break (and inactivity) can be given a resilience — 2026-10-04

Anomaly (user): the 20 s break was not among the periods of the quota's resilience drop-down. The list and the reading
were the TASKS' (`SearchDomain.resilienceKinds`, `PeriodKinds.resilienceFor`), which leave out — and refuse any value
for — the kinds that "allow no task". A quota is not a task: `QuotaDomain.resilienceFor` / `multiplier` honour the
value the quota was given for every kind (defaults unchanged: 0 for those two), the progression profile reads them,
and the drop-down lists `state.allPeriodKinds`. `QuotaTest`.

### Quota: the period's start, its end as a length or a date, and a repeat count — 2026-10-04

User request, on the quota's "Loop" action (`QuotaLoopEditor`): the start is a day field — its drop-down a row of the
seven weekdays, and below it a calendar to pick a date instead — and a time field; a switch says whether the end is a
length of time after the start (the default, 7 days) or a date and time of its own; a field says how many times it
repeats, without end by default.
- `QuotaEntry.endByDelta` (default true) and `QuotaEntry.repeatCount` (null = without end; 0 heals into "does not
  repeat"); `QuotaDomain.withStart` / `withLength` / `withEnd` / `withRepeatCount` / `lastLoopIndex` / `latestWeekday` /
  `formatLength` / `parseLength`. `PersistedQuota` gains both fields, absent = the earlier behaviour.
- The calendar's month grid (`MiniMonth`) is shared with the day picker. `QuotaTest`.

### The notifications window — 2026-10-04

User request: when a notification (written, spoken or both) fires while the app is not in focus, a Search window
comes up on the notifications posted since then.
- `SearchDomain.Kind.Notification` lists `notificationLog` (title, message, date, what was said); filter
  `Filters.notificationsSinceMillis`, sort `SortKey.NotificationDate`; `notificationsConfig(since)`.
- `App`: out of focus (`LocalWindowInfo.isWindowFocused`) + the log growing = the window, opened on what fired since
  the focus was lost, or brought back in front if it is already standing; held by its frame id. Never a timer.
- **Anomaly, same day** (user: a timer ended out of focus, and no focused window with that one notification): the
  first form listed from a stored "last closed" marker — never set, so the WHOLE log — and did nothing when the window
  was already open; the release DB showed it had opened at launch (two start-up notifications, the app not yet in
  focus) and sat behind. Now the list starts where the focus was lost, every further out-of-focus notification brings
  the window in front, and the marker (`notificationsClearedAtMillis`, `SetNotificationsCleared`) is removed.
  `NotificationsWindowTest`.

### Timer: leaving a countdown field dropped the minutes it had held — 2026-10-04

Anomaly (user): the minutes field holds the caret, the seconds read down through 0 to 59, a click outside leaves the
field — and the minutes drop by one, "as if I never prevented the minutes to decrease". The held fields stood still on
screen only; with nothing typed nothing was written, so dropping the draft snapped to the live countdown. Leaving now
moves the time left by what drifted (`TimerDomain.heldDriftMillis` → `NudgeTimerRemaining`, the row keeps running);
moving straight to another field does the same and that field holds what was shown. `TimerTest`.

### Search window: a creation row leads to every element of its kind — 2026-10-04

User request: the actions of an added creation row ("New quota", "New alarm"…) hold a button opening a Search window
on all the elements of that kind. `AddedAction.CreationSearchAll` (group `Kind.Creation`), one "Every <kind>" button
per kind among the added rows (`creationKinds`), opening `kindSearchConfig(kind)` in a new Search window
(`AddedActionHandlers.onOpenKindSearch`). The section's kinds are now `actionKindsOf` (acted-on kind + own kind), so
the "New quota" row, acted on as a quota, shows it too. `QuotaTest`.

### Search window: a timer's countdown and its reverse were on no screen — 2026-10-04

Anomaly (user): the actions section showed neither a timer's countdown nor Elapsed (the countdown in reverse). When the
timer's settings became one shared field each, "Run" kept only start / pause / reset, and the embedded editor that drew
the countdown (`AddedActionHandlers.alarmEditor`) was still built by `App` but never called. "Run" now draws, per added
timer, the Alarms window's own row in a run-only form (`AlarmWindow(embeddedRunOnly)`, `TimerRowEditor(runOnly)`): the
three countdown fields, start / pause / reset and Elapsed — no setting, so nothing is editable by two routes. With
several timers added, "start all / pause all / reset all" stay under them.

### The task behind a "no screen" period is a rule held at the line, not a hidden panel — 2026-10-04

User rule: *"the task must be found by reading the set of rules input and not by a strategy of hiding a task behind a
'no screen' period."* The mode-1 plan laid full task panels across every no-screen period ahead of the line and the
display cut them out (`clipPlanForRetractedPeriod`). Now `fillSchedule` lays those runs **line-bound**
(`TaskPanel.lineBound`): the requirements' *"if mode = 1 … task B at $now line$"*, whose extent is the line itself —
absent ahead, `[start, line]` while the line is in it, whole once passed. `SchedulerDomain.atLine` is the one reader of
that extent (the calendar's drawing and its lock-on-task); the forward cursor reads the run like any other. The clip
is deleted. `LineBoundRunTest`; the six rewritten tests and `SleepWindowNoIdlingTest` now read `atLine`.
- **Screen breaks too** (user: "convert it too"): the stretches of a run under a break the fill lays ahead, for a task
  the break refuses, hold at the line as well; and the break the line DRAGS — at `]line, line + d]` at every position
  of the line, so no stored span can say where it is — is read with the rules by `atLine`'s overload taking the breaks
  ahead, which replaces `clipPlanForPinnedScreenBreak`.
- **One run, not pieces**: the first form cut a run into separate panels at every period edge, which fragmented the
  plan (a run through a 20 s look-away became three panels). The stretches are recorded ON the run
  (`TaskPanel.heldAtLine`, `PersistedPanel.heldAtLine`: local, absent = none); `lineBound` is whether it has any.

### Mode 1 pushes "no screen" forward on a task; the calendar draws it the same for every no-screen period — 2026-10-04

User clarification of `docs/scheduler_requirements.md`: in mode 1 the $now line$ is in a task even with a "no screen"
period right after it, and the rules (if/then on the line and its mode) name that task. So the mode-1 plan holding a
task across a no-screen period ahead is required, and six tests asserting the opposite on the mode-1 PLAN were wrong.
- They now assert both halves: the calendar DRAWS no on-screen task inside the period ahead (`drawnAtAScreen`), and
  the rules name the task the line is on. The zero-priority and peer-protocol tests, which were about a period's
  refusal and not about screens, use a kind of the account's own, which no mode retracts.
- Display fix found on the way: `clipPlanForRetractedPeriod` read the bands alone, so the no-screen period a wind-down
  hour carries, or two declared layers make, hid nothing — an on-screen task was DRAWN inside a wind-down hour still
  ahead. It now cuts the fill's own retracted spans, companions included.

### A finger's Shift+click: the selection's two dots — 2026-10-04

User rule: on a phone there is no Ctrl+click, but there is a Shift+click — the tree's selection wears a dot on its
top-left corner and one on its bottom-right, and dragging one extends or shortens the range (`TaskTreeView`; through
`DragSelectCells`). Shown while the last press in the tree was a finger's; a mouse press hides them. The dot snaps to
the nearest row boundary and stays inside the tree at a horizontally scrolled edge. `SelectionHandlesTouchTest` drives
it with real touch events on a rendered tree.

### Rule state input evolution: every stretch under the rule state in force there — 2026-10-04

User rewrote `docs/scheduler_requirements.md` § *Rule state input evolution*. What the code did against it: a plan
held the rule state at the line, `R(x)`, for its whole reach (only its FIRST run was cut where a moving `R` turned
against it), and the engine re-planned at every run start inside a transition. So the far side of a switch ahead was
planned under the near side's rules, and became right only by rewriting a definitive schedule as the line arrived.
- `ScheduleFill.run` walks the timeline (`ScheduleFill.RuleStates`, implemented by `RuleStateTimeline`): a held rule
  state is searched ALONE past its next change and emitted up to it; from the change the next one plans with
  everything before it frozen (the catch-up); a moving one is decided run by run, each under `R` at its own instant,
  one decision window ahead.
- `taskTreeBlendAt` is right-continuous: of keyframes on one date, the last is in force from that instant (it was
  the first) — a discrete switch.
- Removed: `SchedulerEngine.launchTaskTreeBlendReschedule`, `SchedulerDomain.taskTreeBlendDecisionKey` /
  `nextTaskTreeBlendWakeMillis` / `nextDecisionMillis`, and the key's part in `planHoldsAtLaunch` — the last
  exception to "time passing must never re-plan".
- A placement of a task only a LATER keyframe holds takes its title from the timeline.
- Tests: four new in `TaskTreeTimelineTest`; the two that pinned the re-plan trigger are gone;
  `BreaksAndSlidingPrioritiesTest`'s ratio test now measures one plan at each position it places (it pinned the old
  "holds the line's percentages for its whole reach"). Cost: a 1-day transition inside a plan is ~4x a plain fill
  (2.2 s vs 0.5 s in the test), once, instead of one re-plan per run start.

### Undoing a dragged past block made it vanish — 2026-10-04

Anomaly (user, confirmed on the release DB: a "Pin record" unit undone 4 s after it was made): a past task panel
dragged elsewhere, then Undo, and the block disappeared. `reducePinRecord` lifted the record period with a plain
state copy, outside the history, and only the new panel was the unit — Undo removed the panel and nothing restored
the record. `PanelDelta` now carries an optional `records` half (`RecordChanges`), committed, undone and redone with
the panels; persisted as `recordRemoved` / `recordAdded` on the `panels` unit, both absent (null) on every unit that
moved no record, so older units decode unchanged. `SchedulerCalendarTest`. Still open: a period laid over the past
strips records outside the history the same way (`stripRecordsUnderPeriod`).

### Every platform: touch menus, tree scrolling, iOS alarms, web outputs — 2026-10-04

User request: the whole app working on every platform, and the list of necessary differences (`docs/PLATFORMS.md`).
- **iOS rang nothing**: the engine skipped its now-line alarm sweep on every `DeviceKind.Phone`, but only Android
  injects an OS alarm armer. Gated on `hasOsAlarmClock` (a phone WITH that seam); iOS now rings in-process, and
  `ringAlarmPlatform` plays the ring (`AlarmTone.ringWav`) instead of being a no-op. `AlarmEngineTest`, `RingWavTest`.
- **Touch reached no right-click menu outside the calendar grid**: a long-press now opens them
  (`ui/TouchLongPress.kt`, through `contextMenuModifier`, the weight-column header and the window bar).
- **A finger could not scroll the task tree** (the row's gesture consumed the drag as a drag-select): a touch drag
  scrolls, a tap selects.
- **Browser**: clipboard writes, Web Notifications, voice (bundled WAVs + `speechSynthesis`) and the alarm ring were
  empty stubs; now one `webMain` implementation for JS and Wasm. An over-quota `localStorage` save no longer throws.
- iOS clipboard via `UIPasteboard`; the iOS voice actual no longer fails to compile (`AVSpeechBoundary`).
- **Undo / Redo on screen**: a "⋮" right of the window bar's Reset opens a menu with "undo" and "redo" — the
  chords' own intents, so they walk the focused window's changes; the menu stays open between clicks.

### Calendar configuration: a scrollbar, and the scheduler engine's time limit — 2026-10-04

User request: the calendar's configuration section shows a vertical scrollbar (`ColumnScrollbar`; it scrolled with none),
and holds a "Scheduler engine" setting: the **time limit after a change**, in seconds — the wall time one progressive
fill may spend, a fixed 2 min until now. `SchedulerState.planCalculationLimitSeconds` (default 120, 1–3600),
`SetPlanCalculationLimit`; a resource of THIS device: persisted, never synced (`withLocalViewStateNeutralized` /
`withLocalViewStateFrom`), absent in an older payload = 120. The engine reads it as a fill starts. The first stage's
length (1 h) is not a setting. `PlanCalculationLimitTest`.

### Search window: sections retract; no "All configurations" button in the actions — 2026-10-04

User request: the actions section's "⚙ All configurations" button is removed (every action is already listed there),
and each of the three sections has a little arrow that retracts it to its head and expands it again (`SectionArrow`,
Compose-only). The search section's own "⚙ All configurations" (its filters) is unchanged.

### Search window: "New" opens the new element in a Search window of its own — 2026-10-04

User request: the "New quota" button must open a new Search window with the new quota as its only added element — and
the same for the other kinds. Only "New task" did; a new category, period, alarm, timer, chrono, quota and reminder
joined the asking window's added elements. `App.createElement` always opens (`open` parameter gone), and
`AddedActionHandlers.onCreate` returns nothing. "Duplicate" still adds its copies to the window they were made from.

### Search window: a creation row's right-click opens its menu — 2026-10-03

Anomaly (account3): right-clicking a "creation" row made its element and opened a Search window instead of the
drop-down menu, so the row could not be added from it. `ItemResultRow`'s `opensOnRightClick` is gone: every row's
right-click opens the menu ("add", "add and remove others"), and a creation row's has "create" under them.

### The quota element — 2026-10-03

User request: a quota to reach over a loop (every week from a day and time, for example), whose **target progression**
shows in the Search window's actions as a percentage; a resilience to the restrictive periods like a task's; a loop with
two times more quota; a loop renewed (the progression twice as fast, back to 0 % at 100 %); a loop starting and ending
elsewhere; a quota that does not repeat. Decided with the user: a time-based pace line (nothing recorded as done), an
amount + unit, loops by a start and an end repeating by their length, in the Search window only. New `QuotaEntry` /
`QuotaLoop`, `SchedulerState.quotas`, `SetQuotas`, `QuotasDelta`, `QuotaDomain`, `SearchDomain.Kind.Quota` (results,
creation, title/new/duplicate/delete, "Repeats" filter, sort) and its actions (`ui/QuotaEditors.kt`: Target
progression, Amount, Loop, Resilience, Particular loops). Persisted `PersistedQuota` (list defaulted empty: an older
payload loads with none), synced one row per quota, merged whole. No SQLite or Supabase migration.
`docs/invariants/alarms-and-timers.md` § *Quotas*. `QuotaTest`.
Follow-up (user rule, the same day): adding the **"New quota" creation row** to the added elements shows the quota
actions, which then edit the **default configuration** a new quota starts with (`SchedulerState.newQuotaDefaults`,
`SetNewQuotaDefaults`, `NewElementDefaults.newQuota`, `SearchDomain.actionKindOf`; persisted `newQuotaDefaults`, absent
= the built-in one).
Anomaly 2026-10-04: an added "New quota" row alone showed Delete, Duplicate, the progression and the particular loops —
it now shows only what edits the default, and "New", which creates one from it (`SearchDomain.actionsFor`).
Anomaly 2026-10-04: with six quotas added the "Delete" action was not found — it was the section's last, under one
"Target progression" block and one "Particular loops" form per quota; it now stands beside "New" and "Duplicate".
Anomaly after it: the Title action was disabled with only that row added — `addedTitles` and the `Titles` command now
carry the default's title too (`DEFAULT_CONFIGURATION_ID`).

### Task cells: the colour under the expansion arrow is outlined in the opposite colour — 2026-10-03

User request: as the calendar's task panels are. `Modifier.taskSwatch` (`ui/TaskSheetChrome.kt`): the task's colour with
a 1 dp border in the colour of highest contrast with it (`TaskPalette.foreground`), for the arrow's box
(`TaskSheetExpandArrow`) and the bare swatch a row without an arrow shows. `TaskSwatchRenderTest` (the rendered pixels).

### Fields that select an id or a task cell: one cell, one row — 2026-10-03

User request: wherever an id or a task cell is selected, the same code, configured per field. The category rule's task
cell field lists task elements (`TaskIdentityRow`) each with ITS path — one task id once per path
(`EditMenuItem.taskPath`, `ScopeEntry.parentPath`); "Add under…" rows are task elements; the calendar's "Edit task"
Task field and the calendar elements window's element field are `NamingCell`s now (they were text boxes with the menus
always under them), their typed text mirrored live (`NamingCell.onDraftChange`). Not converted, said in
`docs/invariants/task-tree.md`: "Pick a task", the three reminder editors and the task-tree name field.

### Id suggestion lists: a task row is the Search window's task row — 2026-10-03

User request: the id rows of an edit-mode menu (a task cell's, for example) must be the Search window's result
elements, with only the expansion arrow, the title and the path; expandable, but one click picks the task id. New
`TaskIdentityRow` (the result row's `TaskRow` + `TaskPathBox` without its path list or menu + a read-only
`SearchSubtree`), drawn for every `EditMenuItem` carrying a `taskId` through `LocalTaskIdentityRow` (provided in
`App`). Marked at the tree cell's and the weight table's menus, the calendar's "Edit task" and elements windows, the
task picker, and the "Changed element" / "Set of tasks" naming fields. `docs/invariants/task-tree.md`.
Follow-up anomaly (account3, the same evening): typing in a tree cell and picking a title raised *"Can't represent a
width of 2147483563 and height of 0 in Constraints"* — a tree cell's edit menus are sized by their content's INTRINSIC
width, and `TitleThenSection` read the unbounded maximum as a width, making the path box "infinity − the title" wide.
It now lays out title + the section's own width when no maximum is offered. `TaskIdentityRowRenderTest` (headless
render under an intrinsic-width, a sideways-scrolling and a narrow parent).

### Categories: a rule relative to a chosen task cell or a parent distance; task cell categories — 2026-10-03

User requests (three, the same day): the Search window's "add a rule" let a rule be written relative to a task cell no
carrier sits under; then "root" was missing from its picker; then a switch between the two ways of knowing the cell.
A rule is `CategoryRule(relativeToCellId, distance, share)`: a **task cell rule** (`distance` null — every rule written
before, which loads unchanged) counts every top-most carrier under its cell, whose picker offers "root" and ONLY the
cells a carrier sits under, by path, once per path (`CategoryRules.taskCellEntries`); a **parent distance rule** holds
each carrier at the share of the cell `distance` levels above it (1 = its parent), along every path. One form
(`RuleAdder`) with a switch showing one field. **"Share of its sub-list" is removed** — a parent distance rule at 1
says it and holds it (`SetCategorySubListShare` / `forceSubListShare` gone). New **"Task cell category"** switch
(`Category.kind`, `SetCategoryKind`, which moves the carriers between `Task.categoryIds` and the new `Cell.categoryIds`)
and **"Task cell categories"** action on added tasks (`SetCellCategory`, `AddCellCategory`). A rule holding a cell pinned
in a priority weights table shows a warning in the task tree window's configuration section
(`CategoryRules.pinnedRuleCells`). Persisted: `PersistedCategoryRule.distance`, `PersistedCategory.kind`,
`PersistedCell.categoryIds` (all defaulted); the merge takes a cell's `categoryIds` as a membership list. No migration.
`CategoryRulesTest`, `TaskCellCategoryTest`, `RootTaskMigrationTest`. Follow-up: the task cell field, and both
"Add under…" fields, had no title suggestions; they now list every task title the draft appears in
(`SchedulerDomain.titleSuggestions`, the tree cell's own), a pick filling the field.

### Scheduler: the first 10 s of a re-plan from scratch — 2026-10-03

Requirement added to `docs/scheduler_requirements.md` § *Progressive Calculation* (**first 10s**): a change that makes
the engine re-plan from scratch first checks whether the next 10 s hold a gap with no task that a task could fill, and
if so returns a set of rules at once whose first 10 s are definitive. `SchedulerDomain.firstSecondsGapFillable` +
`FIRST_DEFINITIVE_MILLIS`; `SchedulerEngine.dispatchProgressivePlan` publishes a search-free `RefreshSchedule` capped at
now + 10 s before the doubling stages, which extend it. A derived panel of a task the edit made unschedulable (deleted)
counts as a gap. `FirstTenSecondsTest`.

### Search window: a history unit's row is keyed by its identity, not its index — 2026-10-03

Anomaly (account3): double-clicking the only row of a history-unit Search window again and again grew the added
elements. A row's id was `Category#index`; account3's stacks are full (`MAX_HISTORY_UNITS`), so every new unit — adding
an element records one — evicted the front and shifted every index: the same row came back under a new key (so "each
key once" never matched) and the old key named another unit. The id is now `Category#deviceId#deviceSeq`
(`SearchDomain.historyUnitId`), resolved by `historyUnitPlace` (one map per histories value) for the filters, the sort
and the Information action. A stored configuration drops the old index-shaped keys on decode (they no longer name what
was added). `HistoryUnitSearchIdTest`.

### Every field naming an element is the "Changed element" field — 2026-10-03

User request: the Search window's "Changed element" filter is right; find the selection fields that must behave the
same (e.g. the category actions' "Add a rule"). Most drew their identity/suggestion menus under a plain text field, so
the menus showed before the field was entered (or were drop-downs). Now `NamingCell` (one press enters Edit Mode,
menus only while editing, outside press leaves): "Under which task cell" (Search action + category edit window),
"Add under…" (Search action + task edit window, one `AddUnderField`), the Search window's Add/Remove category actions
and its category filter, the task cell's categories field, the Categories window's "Add a category", and "Kind of
period". Creating a category or a kind is a "Create …" identity row (`namingCreateRow`), replacing the buttons.
`docs/invariants/task-tree.md` § *A field that names an element is a configured task cell*.

### A task's set of tasks; tasks with no path — 2026-10-03

User request: a task can fulfil other tasks while it is on the calendar, each at a percentage ("watch videos explaining
chemistry in Spanish" → "watch videos explaining chemistry" 80%, "listen to Spanish" 80%). New authoritative field
`Task.fulfilment` (persisted `PersistedTask.fulfilment`, default empty; decode heals blank/self/≤0/NaN entries and
clamps to 100%) — no SQLite or Supabase migration, it rides the task row. Score: a run serves each task of its set as
if on a period of that resilience, filling a deficit only, never past the share; transitive; a task with no priority
and a set is unscored (`docs/scheduler_score.md` § *Sets of tasks*). `ScheduleImprover` now re-scores the credited
tasks of a move (it re-scored only the two moved tasks and accepted plans that took the 100%-set task off). Search
window: *Set of tasks* and *Fulfilled by* actions; creating a task makes one with no path and opens it alone in a new
Search window. Such a task is kept while its set holds a kept task; a task's children inherit its set.
`SetOfTasksTest`, `SetOfTasksScoreTest`.

### Search window: every result row adds; a history unit's information moved into the actions — 2026-10-03

Anomaly: a history unit's right-click menu in the results had no "add" / "add and remove others", only "open in History"
(an obsolete window for it). The same was true of task trees, task relations, keyboard shortcuts and windows. Every row's
menu now has the two; those four keep their "open in …" / "show window" under them (their lateral windows are still the
app's); a history unit's is gone, and opening one ("Open each", Enter) opens its own Search window. Its new
**Information** action (`AddedAction.HistoryInformation`) shows everything the History window did — the row's category,
position, current/applied/undone, window and time, and the info window's label, chrono id, debug clock and detail lines —
each with "copy" and a "Copy all", through the History window's own `historyEntryInfos` (now holding the row's facts too)
and `historyUnitEntry`. `HistoryUnitInformationTest`. Still to decide: task trees, task relations, shortcuts and windows
have no action of their own in the action section.

### Notifications: a click brings the app up, and the schedule's open the calendar — 2026-10-03

User request: clicking a notification did nothing (desktop: a bare tray balloon; Android: no content intent). Now every
click brings the app to the front, and the task to do now and the screen-break notifications also open the calendar or
bring it back and focus it (`openNewWindow(Calendar)`). `sendSystemNotification` takes a `NotificationTarget`, set at the
`notifyUser` call; clicks reach `App` through `NotificationClicks`. Desktop: the tray icon's action listener +
`DesktopAppWindow.bringToFront` (set by `main.kt`); Android: a content intent to the launch activity, read in
`MainActivity.onCreate`/`onNewIntent`; iOS: the target is carried in `userInfo` but not yet handed over (needs the Swift
delegate). `NotificationClickTargetTest`.

### Search window: history units filtered by the elements they changed — 2026-10-03

User request: two filters for the history-unit kind, in the Configuration Search window beside Category / Made in /
Undone. **Changed element types** — check boxes (the app's `CheckBoxDropDown`) over task, category, restrictive period,
alarm, timer, chrono, reminder, task tree, keyboard shortcut and window — and **Changed element**, a field named the way
a task cell names a task: the one `EditModeMenuBlock`, with an identity menu (elements whose title or id is the text)
and title suggestions, for the checked types only. Both read the unit's delta (`Delta.changedElements`, each element
keyed like a Search row); nothing new is stored on a unit. A Search window's configuration change is a change to that
window, whichever window it was made from. The external-change keys moved to `ExternalKeys` so `App` and the filter
share one spelling. Stored with the Search configuration (local-only); an older configuration reads both as "any".
(An earlier version the same day had a single-choice "Changes" subject list and a task-only "Changed task" field;
replaced before release.) Anomaly the same day: the "Changed element" field drew its title suggestions while not in
Edit Mode — it was a plain text field with the menu block always under it, not a cell. It is now the tree's own
`TaskRow`, configured like the priority-weight table's rows: Edit Mode on ONE press (user rule, the same day — a
filter field has nothing to select), leave on Enter / Tab / Escape or a press outside, menus only in Edit Mode.

### Screen breaks: a "Look away now" break is a 20 s screen break — 2026-10-03

Anomaly (account 3, 11:27:36): the look-away the app conducted on `Ctrl+Shift+Alt+E` was drawn with a task panel inside
it and no "no screen", "no computer unlocked" or "not on a computer" over it. `RecordConductedBreak` still recorded it
as an `inactivity` period (which carries nothing) after the automatic look-away became the "20s screen break" kind that
morning, and recorded work was only cut out of the BANKED breaks, which a conducted one is not. Now: the conducted
panel's kind is `BREAK_20S` (an older one is healed by `TaskPanel.restrictiveKind`); `statedKindRegions` lays its "no
screen" like a drawn break's, so the layers follow; every banking cuts work out of it (`appendRecordOutsideNoScreen`,
via `breakRefusedRanges`), work banked across it before it ended is cut when it is recorded
(`SchedulerDomain.withWorkOutOfBreaks`, after the vanished look-away is bridged), and decode heals the records an older
build wrote through one. `VanishedBreakBridgeTest` had pinned one record running straight through the conducted break;
it now expects the record to stop at it. `ConductedBreakIsABreakTest`.

### Screen breaks: a break a re-plan makes due starts at once — 2026-10-03

Anomaly (account 3, 11:07): after start-up the now-line sat in a 20 s gap of the plan; the look-away block and its voice
only came 20 s later. The start-up re-plan's rebuild (`rebuildBreaksFromHistory`) dropped the 20 s bar into the past —
the look-away was due at the line, and the plan left its gap — but the rebuild only cleared the machine's armed
trigger and woke nothing, so the break was entered when the next panel edge woke the cue sweep. And when something did
wake it, the step entered the break where the machine last stood (it does not move while nothing is armed), i.e. in
the past, with its cue swallowed as stale. The rebuild now moves the machine to the clock under the old bars first,
then wakes the cue sweep (`breakMachineRearmed`), which enters the break at the rebuild instant and announces it.
`RebuiltBarStepsAtOnceTest` (virtual time; `ReplanHealsDeducedBarTest` stepped the line by hand, which hid it).

### Notifications: the Notifications switch no longer announces itself — 2026-10-03

User rule: turning the lateral menu's Notifications switch back on posted (and spoke) "Notifications on". The user
is looking at the switch, whose position and title already say it worked. `setNotificationsEnabled` now announces
only for the `Ctrl+Shift+Alt+N` chord (`fromChord = true`), whose own receipt the mute swallows on the un-mute press.

Same day, same reasoning: the task-picker chord (`Ctrl+Shift+Alt+T`) no longer posts its "Shortcut received" receipt —
the picker opening at the pointer already shows the press landed. Nor does "Look away now" (`Ctrl+Shift+Alt+E`)
while a look-away break exists: the break's own "Screen break" notification follows at once, so the receipt was a
second pop-up for one press (with no look-away break the press does nothing else, and its receipt stays). Every other
chord keeps its receipt.

### Calendar: a calendar focused from its tab answers Space — 2026-10-03

Anomaly: the calendar's tab was purple (focused), Space did nothing, and a click inside made it work. The calendar took
the keyboard only when it opened and on a press inside it (`onRaise`); focused any other way (its window-bar tab, the
lateral menu, `Shift+Alt` navigation, the launch) the app said "focused" while the keys went elsewhere. `AppWindowFrame`
now takes a `keyboardFocus` requester and gives it the focus every time the frame host makes the window the focused one.

### Calendar: Space only turns "Lock to now" on — 2026-10-03

Anomaly: Space flipped the last switch clicked in the configuration section instead. A press gave that switch the
keyboard, and a focused `clickable`/`Switch` consumes Space as a press of itself, so it never reached the calendar's
`onKeyEvent`. Every control of the section (the switches, the Day/Week field, the mini-month's arrows and days) is now
non-focusable (`leavesKeyboardToCalendar`); the mouse presses them as before.

### Scheduler: a restart no longer re-plans unchanged rules — 2026-10-03

Every launch re-planned: the rule-change watcher (`launchRuleChangeReschedule`) took the first value of its
`distinctUntilChanged` flow — the rules loaded from the DB — for an edit, and ~1 s after start a full
`RefreshSchedule` rewrote a schedule `docs/scheduler_requirements.md` § *Progressive Calculation* had made definitive.
Every re-plan reduction (`RefreshSchedule`, the in-reducer re-plans, `AdoptScheduleRules`) now records
`SchedulerState.planBasis` (the `schedulingSignature` and instant the plan was made for; persisted locally, never on
the wire, not carried across a pull), and the launch re-plans only when `SchedulerDomain.planHoldsAtLaunch` is false:
no basis, rules changed since (an edit closed inside the debounce, a pull, a debug rollback), or a task-tree decision
boundary crossed while closed. A payload written before decodes with no basis, so the first launch of this build
re-plans once. `RestartKeepsPlanTest`.

### Search window: a vertical scrollbar on the actions section — 2026-10-03

The top right section (the actions on the added elements) has the window's scrollbar on its right: `ColumnScrollbar`,
the `ListScrollbar` thumb and track for a plain scrolling column (`ScrollState`), sharing its drawing.

### Screen breaks: the 20 s break is a kind of its own, "20s screen break" — 2026-10-03

"A 20s screen break is a restrictive period like any other." It was an `inactivity` period, so the Search window
listed "inactivity" for it. `PeriodKinds.BREAK_20S` is now the kind `DynamicPeriods.breakKind` gives it, listed with
the two others (`BREAK_KINDS`, so in every list of kinds), drawn like them; it still *"allows no task"* (resilience 0,
not editable). It carries "no screen" by no default rule, as `inactivity` did not: a rule would let a line at a screen
retract it. A break banked by an older build keeps the label it was banked under and so takes the new kind on load.

### Calendar: "edit…" on a screen break lists the break — 2026-10-03

Anomaly: right-click → "edit…" on a past 20 s break opened a Search window with no break in it. The breaks are not
panels of the state, and the right-click's instant is rounded to the minute (a 20 s break is shorter). The calendar
now hands the drawn breaks to the "is on the calendar at" filter (`SearchDomain.calendarBreaksAt`): the break's kind
("inactivity" for the 20 s one), "no screen", and the layers it lays. Display only.

### Task tree: Ctrl+click and Shift+click across sub-lists are drawn — 2026-10-03

Anomaly: they "did not work" when the two cells were not in the same sub-list. The selection did hold both cells;
the highlight asked every selected cell for the main selection's render-via, which only the main's own sub-list has.
A selected cell of another sub-list is now highlighted under its own parent
(`SchedulerDomain.shouldShowSelectionHighlight`). Drawing only; no state change. Same day, second anomaly: a cell of a
MIRRORED sub-list inside a Shift range stayed undrawn (its list names a parent other than the occurrence it is
shown under) — the row is now read off the visible order, inside the range (`selectionHighlightVias`).

### Search window: the added elements are selected like the results, and their menu has "remove" — 2026-10-03

The added-elements rows take the result list's selection (`ClickSelection`: click, Ctrl+click, Shift+click; a
right-click keeps a selection it lands in). Their right-click menu acts on it: "remove" (new) and "remove the others".
The selection is Compose-only; the list is local view state.

### Search window: the category group's settings are shared controls; one Combinations list — 2026-10-02

The categories' per-element "Name and rules" editor is replaced by Name (the one added category's — names are
unique), Rules (one row per scope over every added category, `CategoryRules.sharedRuleRows`) and Add a rule (given
to each). The periods' Combinations is one list of every rule naming an added period, instead of a section per
period. A task's Paths stay per task. No state shape change.

### Search window: a category's "Share of its sub-list" action — 2026-10-02

A new action on the added categories: a percentage and "Force on N tasks". Every task carrying the category is given
that share of its own sub-list by adjusting its row of the sub-list's priority weight table — one common factor over
the row's values, a term added only where no factor lands (`CategoryRules.forceSubListShare`, the existing
`setChainsShare` solve; `SchedulerIntent.SetCategorySubListShare`, one Undo/Redo unit). A one-shot edit, not a
standing rule. No state shape change.

### Calendar: no period drawing behind a block's title — 2026-10-02

A task panel redraws the periods' patterns, the layer hatches and the hour lines over itself (`panelDecor`), and they
ran through its title. The title's own box (as wide as the words) is now painted the block's colour over them
(`CalendarBlockBody`). Drawing only.

### Search window: timers, chronos and reminders get shared fields and a bin too — 2026-10-02

"Don't do that just for alarms." The per-element "Edit" of the timer, chrono and reminder groups is replaced by one
control per setting over every added element (timers: Duration, Rings for, Below zero, Alert; reminders: Every,
Time, Constrained in, Alert; chronos already had Title and Run), and each of those groups — and the categories' —
has a "Delete" bin (`AddedCommand.Delete(kind)`). A task's Paths, a category's Name and rules and a period's
Combinations stay per element. No state shape change.

### Search window: the alarm group has its bin back — 2026-10-02

Removing the per-alarm editor took the bin with it. "Delete" (`AddedAction.AlarmDelete`) deletes every added alarm
in one `SetAlarms` — undoable — and takes them off the added list.

### Search window: the alarm group's settings are one field each, not one editor per alarm — 2026-10-02

Anomaly: with several alarms added, the top right section drew the Alarms window's row once per alarm. The "Edit"
action is replaced by Time, Days, Rings for, Repeat and Alert, each one control over every added alarm
(`AddedCommand.AlarmsEdit`, one `SetAlarms`), beside Title, State and "Set to the current time". Timers, chronos and
reminders still embed one editor per element.

### Search window: action groups listed by reach, and one title field per group — 2026-10-02

The groups of the actions on the added elements are listed by how many added elements their actions apply to, the
most first (`SearchDomain.sortedByReach`). The alarm, timer, chrono and reminder groups get a "Title" field: empty
unless every added one shares a title, typing gives them all what is typed (`AddedCommand.Titles`), Escape gives each
back the title it had. No state shape change.

### Search window: an added element's right-click offers "remove the others" — 2026-10-02

A right-click on a row of the added elements opens a menu of one entry, "remove the others"
(`SearchDomain.keepingOnly`); none on the only element left. No state change (the list is local view state).

### Screen breaks: two 15-min breaks drawn back to back right after the line — 2026-10-02

Anomaly on account 3 at 22:14, the line dragging an owed 15-min break inside the hour before bed. The calendar's
prediction (`BreakMachine.predict`, `TAKE_POSES`) takes the owed pose at the line without recording that it took the
no-screen period's occurrence (`State.taken`), so the "break starts at max(now line, t_s)" pull brought the next
15-min break onto its end. The shortcut now records it, as `enter` does. Prediction only: the machine the line
drives, the banked record and the cues were not affected. `BreakMachineTest` pins it.

### Calendar: a screen break lays the two layers' oblique lines — 2026-10-02

A break is accompanied by "no screen", and by the account's default rule "no screen" brings ("no computer unlocked"
or "not on a computer") and ("no phone unlocked" or "not on a phone") — but only for a period the user stated, so a
break had the bubble's "No screen" and no hatch. The drawn breaks' "no screen" now counts as stated for the layers
(`statedKindRegions(breaks = …)`): both obliques over every break, dotted "not on a …" where the OS saw the device
unlocked. Display only; no state, SQLite or Supabase change.

### Calendar: a screen break's hover bubble names the "No screen" it is accompanied by — 2026-10-02

Anomaly: the 15-min break ahead of the line read as the break alone. Every break band now stacks its kind's
companions in the bubble over its own span, "No screen" always among them (`docs/scheduler_requirements.md`
§ *screen breaks*: "always accompanied by the 'no screen' period"). Display only.

### Search window: the "app setting" element, "Sound setting" and its global volume — 2026-10-02

A new kind in the element selector, `Kind.AppSetting` ("app setting"), listing `SearchDomain.AppSettingEntry` —
"Sound setting" for now. Its action "Global volume" is a slider over the new `SchedulerState.soundVolume` (`0..1`,
default 1; `SetSoundVolume`, persisted as `soundVolume`, merged like the other scalar settings, absent in an older
payload = full volume). The engine mirrors it to `AppVolume`; the desktop voice (bundled cues, Piper, SAPI) and
ring, and the phone's cue track, synthesizer and alarm track, play under it. No SQLite or Supabase schema change
(one more scalar in the synced settings).

### Calendar: Space turns "Lock to now" on — 2026-10-02

While the calendar holds the keyboard, a plain Space sets "Lock to now" on (`setLockNowLine(true)`, which releases
the task lock). Read on the bubble pass (`onKeyEvent`), so a field being edited in the calendar keeps its space.
No state change.

### Calendar: "No screen" under both layers is named, and the layers retract under a placed panel — 2026-10-02

Two anomalies at one instant carrying "no computer unlocked" and "no phone unlocked". (1) The hover bubble did not
name the "no screen" period the account's rules derive there: it now does (`SchedulerDomain.kindsDerivedFromLayers`,
`derivedLayerPeriods`), and the Search window's "is on the calendar at" lists it. (2) A task panel dragged there,
with a resilience of 0 to "no screen", left the layers standing: the layer bands are now cut under a task panel the
user placed that a derived kind refuses (`layerRetractionCuts`, `layerBandsAroundPlaced`) — live while it is
dragged, and at rest. Display only. No state, SQLite or Supabase change.

### Calendar: a hand-placed panel is no longer cut where the devices observed no screen — 2026-10-02

Anomaly: a past task panel dragged onto a stretch both layers cover was drawn whole during the drag and CUT on
release, an "Inactivity" band in the hole. The saved panel was whole; the display clip
(`SchedulerDomain.clipPanelsForObservedNoScreen`) cut every on-screen task panel there, the user's own included.
It now leaves `isUserPlaced` panels alone, so the panel keeps its length and the derived band gives way — live in
the preview (already) and at rest. The fill's own panels and banked records are still cut. No state, SQLite or
Supabase change.

### Calendar: the "Auto schedule" switch moved from the lateral menu to the configuration section — 2026-10-02

The same switch on the same intent (`SetAutomaticSchedule`), now drawn by `CalendarConfigurationSection` under the
Display field; `LateralMenu` no longer has it. No state, SQLite or Supabase change.

### Calendar: inactivity, sleep, no screen and before bed have no drawing by default — 2026-10-02

`PeriodKinds.defaultStyle` gave them `|`, `—`, `(` and zig-zags; it now gives all four `PeriodDrawing.None`. An
account that chose a drawing for one keeps it (the override is stored); one that never did follows the new default,
and can now choose and store any of the four old patterns. A derived Inactivity stretch (no outline) is therefore
its label alone. No state shape, SQLite or Supabase change.

### Search window: "Set to the current time" on the added alarms and reminders — 2026-10-02

A new action in the alarm and the reminder sections of the actions on the added elements (button "Now"): every added
alarm's / reminder's time of day becomes the clock's, to the minute (`AddedCommand.AlarmsTimeNow`,
`RemindersTimeNow`), one `SetAlarms` / `SetChores`. An alarm's days and an isolated ring's date are kept. No state,
SQLite or Supabase change.

### Search window: Reset goes back to the opening configuration, then to the default one — 2026-10-02

Reset cleared the search text and unticked every type, leaving the filters and the added elements. It now restores
the WHOLE configuration the window opened with while it has changed since (`SearchDomain.resetConfig`, `App`'s
`searchOpenedConfigs`) — a window the calendar's "edit…" opened goes back to the elements at the right-click — and
puts the default configuration (tasks, no filter, nothing added) when it has not. The Configuration Search window's
"Reset" setting (was "Reset text and types") is the same rule. The opening configuration is Compose-only. No state,
SQLite or Supabase change.

### Engine: the activity beat is gone off the phone, and the OS lock history is read on screen edges — 2026-10-02

Second step of the same audit. **The beat** (`launchActiveSessionTracking`, every 30 s) re-sampled the lock signal,
rewrote the open session row, re-published the whole row list (re-keying the calendar's derived-gap memo) and
recomputed the break dues for the server. Lock/unlock already reach the engine as OS events
(`onPlatformActivityChanged`), so off the phone the loop is removed: one sample at launch; a session slept through is
closed by the advance tick's wake detection; the open session's stored end (the bound on an unclean stop, and what a
sync pushes to peers) is written by the advance tick (`boundOpenSession`, no list re-publish); the dues are published
when the break machine steps (`presenceDirty`). The phone keeps its one-minute lease beat — foreground has no reliable
closing event. **The scans**: `launchNoScreenEvidenceScan` and `App`'s layer scan each launched a PowerShell process
every ten minutes; both are now keyed on the new `SchedulerEngine.screenEdges` (launch, lock, unlock, wake, "I'm
away"), and the layer scan's floor is quantized to a day. Verified live on a copy of the release DB: one scan of each
kind at launch, the session end advancing every tick. (That run caught `lastSessionBoundRealMillis = Long.MIN_VALUE`
overflowing the age test so the row was never written — initialised to 0.) Still on a timer: the 30 s advance tick.
No state, SQLite or Supabase change.

### Engine: the task-tree blend watch and the horizon watcher sleep until their armed instant — 2026-10-02

Audit against `docs/scheduler_requirements.md` § *Rule Structure* ("No Global Lookups", "advances a forward cursor to
the next armed trigger point"). Two engine loops woke on a timer whether or not anything was due:
`launchTaskTreeBlendReschedule` at least every 60 s (`TASK_TREE_BLEND_POLL_MILLIS`, removed), scanning all of
`state.panels` each time even with no dated tree; `launchHorizonReschedule` every 30 s to ask whether its due instant
had come. Both now sleep until the instant itself (new `SchedulerEngine.sleepUntil`, exact in the clock's own time
base and woken by a clock reconfiguration; new `SchedulerDomain.nextTaskTreeBlendWakeMillis`) and are re-armed by
what can move it. Left as it was, and named as such: the 30 s advance tick (it also detects device sleep, bounds the persisted
line position and kicks the cue sweep). The activity beat and the OS lock-log scan are the entry above. No state, SQLite or Supabase change.

### Windows: the window the app was left on has the focus again after a relaunch — 2026-10-02

Anomaly: *"I open the calendar, then click on account3-deploy-windows-offline.bat, but the calendar is not in focus"*.
`SchedulerState.focusedWindow` is persisted, but at launch two things took the focus away from the window it named
before the user did anything. Every restored window with `claimsKeyboard` called `WindowFrameHost.focus` as it
registered, so the last one composed won (the Search window on the release account, which has four full-size windows
open) and `onFocus` recorded that as a WindowNav move; and `LaunchedEffect(calendarOpen)` dispatched `SetCalendarFocus`
for a calendar that was merely found open. A first fix that focused the named window only "if nothing has the focus
yet" therefore never fired. Now: new `WindowFrameHost.claimOnOpen`, off while the launch puts the windows back; `App`
captures the window the state names at launch (`leftOn`), gives it the frame focus one frame in, then turns the claims
on; and the calendar's claim is skipped for a calendar restored open. No move is recorded, a reduced window stays
reduced, a closed one stays closed.

### Calendar: the Day / Week display mode is persisted — 2026-10-02

Anomaly: *"The day/week configuration doesn't seem to persist between app closes/launches."* It was Compose state in
`App` (`remember { mutableStateOf(Week) }`), so every launch came back in Week. Now `SchedulerState.calendarDayMode`
(`PersistedSchedulerState.calendarDayMode`, default false) set by `SchedulerIntent.SetCalendarDayMode`: local-only view
state beside `showScreenBreaks` / `showReminders` — persisted, carried across a pull, neutralized in the fingerprint,
never synced. No SQLite or Supabase migration (a field of the local `app_state` payload).

### Calendar: a drag is drawn as its release would leave it; double click to move; right-click suspends — 2026-10-02

User request: *"When the user drags a block, the block always keeps its height [...] When right-clicking during a drag,
it opens a menu with the options "cancel" and "resume drag"."*, then, correcting a first reading that made task panels
retract: *"The user must not be able to drag a block in the calendar with a simple click"*, *"When dragging, it must be
displayed at any moment like it would be if the user releases the mouse click (sharing the width if dragged to another
task panel, retracting a period it has 0 resilience with...)"*, and the anomaly *"When I dragged a past task panel, its
background color got clearer, and it acted like a ghost."*

- The no-overlap snap (`SchedulerDomain.placeDraggedEntry`) is gone: a move keeps the block's length and always commits
  `allowOverlap`, so it shares the width with the task panels it lands on. `seedOverlapWeight` now counts task panels
  only (a period under the drop skewed the seeded width).
- The preview reads the reducer's rules: `SchedulerDomain.periodRefuses` (moved out of the reducer, carried to the
  column by `LocalPeriodRefusal`) and `SchedulerDomain.retractAround` (now also what `resolveScreenOverrides` trims
  with). A block dragged over a period that refuses it retracts the period live; a dragged period box retracts the task
  panels it refuses.
- Ghost: the preview overlay drew its own copy of a block (30 % wash, no outline, no decor). One `CalendarPanelFace`
  for the block at rest and the overlay.
- A block and a period box are moved only by a double click whose second press is held and dragged; a resize still
  starts at the first press.
- A right-click during a block drag sets the column's `HeldBlockDrag` and opens "cancel" / "resume drag"; a resumed
  drag follows the pointer with no button held and is dropped by a click. Period boxes and rings have no such menu.

Follow-up anomalies the same day (*"when dragging a task panel, the area where it was has no inactivity period, and
the inactivity period I am dragging the task panel to doesn't retract. It does so only when the mouse click is
released"*; *"when I drag it to a 20s screen break, this screen break should make a hole on the task panel, but this
hole is not saved [...] if I drag a task panel cut by a 20s break, the whole task panel is dragged"*):

- The derived Inactivity bands are computed in `App` from stored state, so the preview never moved them. New
  `SchedulerDomain.inactivityBandsAfterMove`, read by `periodsForBlockDrag`.
- New `layoutWithBreakHoles`: a break cuts the drawn slices of every task block whose task has no resilience to the
  break's kind (new display-only `breakKind` on `CalendarRecord` / `PlacedRecord`), at rest and in the preview. The
  panel itself is untouched, so the hole follows the break and any piece drags the whole panel. The 5- and 15-minute
  breaks follow the same rule through their own kinds (a task resilient to one is drawn through it).

No state, SQLite or Supabase change.

### Search: a right-click in the type selector picks that type alone — 2026-10-02

User request: *"right-click on a type element in the type selector in the Search window means only this type is
selected and the drop-down menu disappears."* `CheckBoxDropDown(soloOnRightClick)`, on for `KindsDropDown` (so the same
selector in the Search configurations and Added elements configurations windows too): a secondary press on an option
checks it alone and closes the list. A left click still ticks and leaves the list open.

### Calendar: an alarm's or a timer's ring can be dragged, and is then outlined in blue — 2026-10-02

Anomaly: *"I double-clicked an alarm box in the calendar and drag it, but it didn't do anything. It should also outline
it in blue when dragged."* A ring was inert: `AlarmMarker` held no gesture at all (only task panels and period boxes
did), and `ringOutline` was orange with no blue case. Now a mouse press-and-drag moves it within its day, committed on
release through `onCommitBounds` → `SearchDomain.calendarRingMoveIntent`: an alarm's `timeOfDayMinutes` is restated (to
the minute; its days kept, so every ring of that alarm moves), a timer is put on the clock to end there (left alone in
the past or beyond its longest run). New authoritative field `calendarPlaced` on `AlarmEntry` / `TimerEntry`
(`PersistedAlarm` / `PersistedTimer`, default false, in the row's JSON — no Supabase or SQLite migration);
`ringOutline(calendarPlaced)` is blue for it, and the ring is blue while held. Follow-up the same day (*"it should be
dragged only after a double click then drag while keeping the mouse click pressed"*): the gesture is the second press
of a double click, held and dragged; a plain press-and-drag no longer moves the ring.

Third follow-up (*"there is a radius around the double-click where the drag doesn't move the block"*): the drag waited
for the touch slop before following the pointer, and dropped the distance travelled inside it. The double click already
says the press is a drag, so the ring follows from the first pixel.

Second follow-up (*"outlined in blue means this is an isolated block that got edited by the user, so it should not move
the other alarms in the other days. Outlined in orange means it is created from an automatic pattern"*): the first
version restated the alarm's `timeOfDayMinutes`, which moved every day's ring and painted them all blue. Now the rule is
untouched but for one skipped date (`AlarmEntry.skippedEpochDays`, pruned past a year) and the dragged ring becomes an
ISOLATED alarm row on that date (`AlarmEntry.onlyOnEpochDay`), blue; `AlarmEntry.calendarPlaced` (never shipped) is
gone, `TimerEntry.calendarPlaced` stays. `AlarmEntry.ringsOn` takes the DATE and is the one test `AlarmDomain` and the
Search "is at" filter read. Also fixed on the way: the Alarms window rebuilt each `AlarmEntry` / `TimerEntry` from its
row and would have erased these fields on any edit — its rows now carry them. The test is now
`a_ring_dragged_on_the_calendar_leaves_its_rule_and_becomes_an_isolated_blue_ring`.
`SearchCalendarFilterTest.a_ring_dragged_on_the_calendar_restates_its_time_and_is_outlined_blue_from_then_on` (a
payload lacking the field included).

### Search: the "calendar bubble order" sort, on for the calendar's "edit…" — 2026-10-02

User request: *"In the Search configurations window, add the sorting method that sorts with the priority ranking used
to know in which order the elements are shown in the hovering info bubble of the calendar. When the user right-clicks
in the calendar and clicks edit…, this sorting method is on."* `SearchDomain.SortKey.CalendarBubble`, a whole-list key
(`sortKeysOf(null)`): reminder, alarm/timer, task = screen-break kinds, any other period, the layer kinds; rows of a
kind the bubble never names go last. The ranks moved to the domain (`CalendarBubbleRank`) so the bubble and the sort
read one declaration. `calendarAtConfig` opens with it dominant over relevance; an "edit…" re-using an open Search
window puts it on top of that window's sorts (`withCalendarBubbleSort`, `App.openCalendarSearch`); "add…" leaves the
sorts alone. Stored by name like every sort key, so no migration.
`SearchCalendarFilterTest.the_is_at_filter_keeps_only_what_is_on_the_timeline_there`.

### Periods: "No drawing" in the drawing selector — 2026-10-02

User request: *"In the actions for periods, in the drawing selector, add the option for no drawing (since the user can
create lots of periods)."* `PeriodDrawing.None`, first in the selector (`AddedAction.PeriodDrawing` lists the enum's
entries). The one renderer paints nothing for it (`Modifier.periodDrawing`; an empty tile for a panel's redraw). Only
ever chosen: a new kind still gets the least-worn of `PeriodDrawing.patterns`. No migration — a drawing is stored by
name, and an older build reading `None` falls back to the kind's default.
`PeriodCompanionsAndDrawingsTest.no_drawing_is_only_ever_chosen_and_it_round_trips`.

### Calendar: no text is overlapped by anything — 2026-10-02

User rule: *"In the calendar, text must never be overlapped by anything. If the title of a task panel and the title
of a period overlap, one of them should actually be placed below. If they are both supposed to appear at the exact
same position, then the one that must get placed below is the one that is lower in the priority ranking used […] in
the hovering info bubble. When moving, the text moves to always be placed right."* `calendarLabelSlots` places every
panel title, period / sleep label and break name of a column in one pass, clear of each other and of the date badge,
the §14 tags and the §18 rings (the rings are new here); it replaces `panelLabelTopInset` and
`reminderStackOverlapAt`, which each label asked separately and which knew nothing of the other labels. A wrapping
panel title is capped at the lines that fit above the next text. During a move/resize preview the placement reads the
live layout. `CalendarLabelSlotsTest`. `calendar.md` § *No two texts share a point*.

Follow-up the same day (*"When there is not enough available height, it must not be shown […] Inactivity at 06:49:00
in account3 doesn't do it. Also, if the bottom line of a period is in the way, the title must get placed further
below."*): an un-pushed label was still written whatever its element's height (the old inset-0 rule, carried over) —
a label is now written only where its whole line fits inside its element; and the bottom line of every outlined
period, sleep band and break is an obstacle like a tag or a ring.

### Calendar: a hover bubble of several sections is as wide as its text — 2026-10-02

Anomaly: *"The info bubble when hovering over the calendar are often too wide even though the text isn't."* The
`HorizontalDivider` between two sections fills the width it is offered, so any bubble with more than one section
stretched across the viewport. `CalendarTitleBubble`'s column is now `width(IntrinsicSize.Max)`.

### Calendar: the configuration section moves to the right side — 2026-10-02

User request: *"Put the calendar configurations at the right side instead of the left side."* In
`CalendarFloatingWindow`, `CalendarConfigurationSection` now follows the grid in the row (grid, divider, section) — the
same side as the task tree window's. Layout only; nothing else changed.

### A sleep window lays no "not on a computer" where the line crossed it in mode 1 — 2026-10-01

Anomaly: *"I see the 'not on computer' period behind the now line. […] I didn't hit the I'm away button, so the now
line must be in mode 1 and the sleep period must retract to the now line."* The same day's "the Sleep schedule's
windows count as user-stated" fired the `or` placement over the window's whole span, the mode-1 past included.
`SchedulerDomain.statedKindRegions(atScreenPast)`: an orange period that is or carries "no screen" gives up the past
this device was unlocked for with the button off (`App.kt`: `ownKnownUnlocked` minus `declaredAwayRegions`). A drawn
period is unchanged. Follow-up (*"Sleep is still just behind now line"*): the band itself was still drawn whole (the
2026-09-28 "only the companion lifts" display); `SchedulerDomain.retractOverAtScreenPast` now cuts the Sleep bands and
the wind-down hour in `App.kt` by the same regions, and `statedKindRegions` reads through it.
`PeriodCombinationsTest.a_sleep_window_lays_no_layer_where_the_line_crossed_it_at_a_screen`
(was `a_past_sleep_window_lays_not_on_a_computer_where_the_computer_was_unlocked`). `calendar.md` § *Combination rules*.

### Calendar: day / week display mode — 2026-10-01

User request: *"add a field in the calendar configurations to select between day and week display mode. In day mode,
the timeline is vertical but when reaching the bottom, it resumes at the top at the right. When lock to now is on, the
now-line is locked to the vertical timeline that is the furthest to the left, and vertically in the middle. When the
user scrolls down, the timeline moves forward […]. When the user zooms, the lines still don't move but the timeline on
it zooms."* `CalendarDisplayMode` + the configuration section's **Display** field (`App.calendarDisplayMode`,
session view state). The grid is unchanged in kind: each column reads the timeline one step past its neighbour
(`columnStepPx` — a day, or the viewport's height), through `columnDayShift` / `columnOffsetPx`; per-column gutters,
cull windows and day boundaries in day mode; `centerOnNowLine` and `applyZoom` each grew a day-mode branch.
`CalendarDisplayModeTest`. `calendar.md` § *Day and week display modes*.

### Task tree: the task's colour under the expand arrow; the selection as two greys — 2026-10-01

User request: *"Color background for tasks is great for the calendar, but for the task tree it becomes a bit of a
nuisance. Also I want the indication of main selection and selection to be a grayed and less grayed background instead
of outlined. Maybe put the color of the task cell under the expansion arrow only?"* `TaskRow` no longer paints the
task's colour behind the row: `TaskSheetExpandArrow(background)` paints it under the arrow's box (a bare swatch on the
arrow-less relative-priority chain rows). `TaskCellOutline.fill` — `SheetColors.selectedFill` / `mainSelectionFill` —
replaces the 1 dp / 2 dp blue outlines (Edit Mode keeps its purple one); `moveDragFill` darkened to stay apart from
both. The cell provides a null `LocalTaskCellForeground`, so every `onTaskCell` reader is back on the sheet's colours.
The Search window's rows follow (same cell code, and `resultRowModifier`). Reverses the same day's "the outline alone"
and "the tint is the row's resting background". The calendar is untouched.

### Calendar "add…": the Search window opens with the type selector deployed — 2026-10-01

User request: *"The add… option from a right-click in the calendar opens the Search window with the type selector
already deployed for the user to select the element types right away."* `App.searchKindsToDeploy` (one-shot, by frame
id; `openNewWindow` now returns the id) → `SearchWindow(deployKinds)` → `KindsDropDown` / `CheckBoxDropDown(deploy)`.

### Search window: "add and remove the others"; a double-click adds — 2026-10-01

User request: *"In the right-click menu of an element of the result list in a Search window, add the option to add it
while removing all the others from the selection list. Also, double-clicking an element should either make it enter
edit mode (if a task cell), or add it, not open a new search window."* (The "others" are the added list's — the user's
answer.) New entry `ADD_REPLACING_LABEL` in both row menus (`TaskCellMenuActions.onAddReplacing`); `addKeys` /
`addSelected(replacing)`. `ItemResultRow` takes `onDoubleClick` (adds; a creation row still makes its element). Enter
still opens the row.

### Search window: a click far down the list did not move the main selection — 2026-10-01

Anomaly: *"When I click on an element, then click on another far away in the list, instead of making the last element
the main selection and only selection, the main selection stays the first clicked element and the last element becomes
selected."* The rows' gestures are keyed and started once, so they called the handlers of the composition that started
them; `select` skipped the dispatch when the clicked key equalled THAT composition's selected key (a row composed while
it was the selected one, e.g. after scrolling back to it). Fix: `TaskRow`'s gesture, the result rows' and the path
box's call their handlers through `rememberUpdatedState`; `select` no longer pre-checks, the reducer does.

### Search window: the row menu's "add" adds the selected rows; Add adds the checked ones — 2026-10-01

User request: *"In the result list, elements can be added in the added elements list either by clicking on the add
button to add the element with a checked box, or by right-clicking it which opens a menu with the option add, which
will add all the elements selected (with ctrl+click and shift+click), not the ones checked."* The result list has a
Ctrl/Shift multi-selection (`ClickSelection`, moved out of `WindowFrame.kt` as the window bar's `TabSelection`, now the
one funnel for both); a right-click keeps a selection it lands in; the menu's "add" → `addSelected`. The Add button no
longer falls back on the selected row.

### Window bar: Reset = only the calendar, maximized; "close selection" of every tab closes everything — 2026-10-01

User correction: *"Reset must reset the default, which is having only the calendar open and maximized. When
right-clicking the system tray and clicking close selection when all tabs are selected, it must close everything and
not have the calendar open."* Reset: `closeWindows`, then (two frames later) the calendar opened with
`CALENDAR_RESET_CHROME`. Close-all reopening a window, second path: closing the calendar dispatched
`SetCalendarFocus(false)` → the focus named the (closed) tree → the focus walk's effect reopened it. Now
`SetCalendarFocus` is dispatched on opening only; a close's focus goes through `focusAfterClose` alone.

### Window bar: "close selection" and Reset left a window open — 2026-10-01

Anomaly: *"I selected all tabs and clicked on close selection, but it did like the reset button, instead of having
every window closed."* Both looped over each window's close; each close hands the focus to the window under it at
once, but closed windows leave the stack only a frame later, so the focus went to a window of the same batch and `App`
reopened the window the focus named. Fix: `WindowFrameHost.closeWindows` marks the batch closing first and
`frontIdExcluding` skips it. Test: `TabSelectionTest.a_batch_close_hands_the_focus_only_to_a_window_that_stays`.

### Window bar: a right-click menu over the selected tabs — 2026-10-01

User request: *"Right-clicking on the system tray opens a menu with the options close selection, minimize selection,
open selection. When a window has the focus, its tab is selected. Right-clicking an unselected tab doesn't select it."*
`WindowBar`'s menu → `WindowFrameHost.closeSelection` / `minimizeSelection` / `openSelection`; `focus` selects the
focused tab (alone, unless already selected); the tab's gesture ignores a right-click. Tests: `TabSelectionTest`.

### Window bar: Shift+click and Ctrl+click select tabs — 2026-10-01

User request: *"The user must be able to click on a tab in the system tray, then shift+click on another, which selects
all the tabs in between. The user can also do ctrl+click on the tabs."* `TabSelection` + `WindowFrameHost.selectedTabs`
/ `onTabPressed` (`ui/WindowFrame.kt`); the range is `CheckRange.keysToSet`. Shift/Ctrl clicks only select; a plain
click selects and keeps its taskbar toggle. In memory only. Tests: `TabSelectionTest`.

### Task cell: every text takes the contrast colour — 2026-10-01

Anomaly: *"In the task cell, the font color chosen to be opposite to the background color is not applied to all the
text in the task cell."* Only the title, percentage, arrows and caret read `onTaskColor`; the minimum time, the
categories, the weight fields and the slots other windows add (Search's kind, check box, path box and logo, the
relative-priority pin) kept fixed sheet colours. Fix: the cell provides `LocalTaskCellForeground` (`TaskColorPalette.kt`)
and every one of them reads `onTaskCell(default)`.

### Search window: the three sections are resized by dragging — 2026-10-01

User request: *"In the Search window, the user must be able to resize the 3 sections by dragging the separators."*
The vertical line (search | right half) and the horizontal one (actions | added elements) are `SectionSeparator`s
(`ui/SectionSeparator.kt`, new): a grab strip under the OS resize arrow; `draggedSplit` keeps each section above its
minimum. The splits are Compose-only (they start at half each time the window opens), never persisted.
Follow-up: *"At the joint of the vertical line with the horizontal line, the user must be able to resize the three
sections at the same time."* `SectionJoint` (same file): a square over the crossing, under the four-way arrow
(`jointResizePointerIcon`, new expect — the OS move cursor on desktop, the crosshair elsewhere), moving both splits.

### Task colours: every task, placed in the cube — 2026-10-01

User correction: *"Make sure that only at more than 256*256*256 tasks there will be tasks with the same background
color. Not just schedulable tasks. In the color space (a cube), the program first places the schedulable tasks as far
away from each other as possible in the cube, then the not schedulable tasks. Another optimization (that never
prevails on the first one) is that the more two tasks are close in the task tree, the more they are close in the
cube."* `TaskColorCube` (new): the tasks to schedule on the widest `k³` lattice holding them, along a neighbour-to-
neighbour path in tree order; the others at its cell centres near their sub-tree; the rest at the nearest free colour
of the whole cube. `TaskColorSpace.TaskHue.leaf` (new, defaulted). `TaskPalette` colours all tasks together. Tests:
`TaskColorCubeTest`.

### Task colours: one per task to schedule up to 256³, and WCAG contrast on them — 2026-10-01

User request: *"Make sure that only at more than 256*256*256 tasks to schedule there will be tasks with the same
background color. Elements that appear above this background color … must use the right color to maximize
readability … the color contrast ratio … (WCAG). If those elements continue outside of the background color (e.g.,
dotted horizontal lines), this color is only applied to where it overlaps with it."*

- `TaskColorCurve` (new): the circle position → a 3-D Hilbert curve of order 8 through all 256³ sRGB colours;
  WCAG relative luminance / contrast ratio / `bestForeground` (black or white).
- `TaskPalette`: one opaque colour (`color`; `sheet` = `accent`), no depth lightness, `foreground`.
- Tree row, name chips, menu rows: text and marks in the contrast colour. Task picker: the restriction colour moved
  to a mark beside the name.
- Calendar: task panels opaque; the period / sleep / layer markings drawn under them; `PanelDecor` redraws markings,
  outlines, hour and graduation lines over each panel in its contrast colour, aligned.
- Tests: `TaskColorCurveTest`.

### Calendar "edit…": a layer band counts — 2026-10-01

User anomaly: *"I right-clicked in the calendar where there is a task and a 'no phone unlocked' period, and I didn't get
the 'edit...' option, as if there was only one element to edit."* The menu counted the element window's drafts, which
leave out the layer bands (and those are in no hit list). Now `calendarThingsAt(hits, layerHits)` counts the distinct
things there, layer bands included; the Search filter gets the same bands (`CalendarLayersHolder`,
`SearchDomain.results(layerKindsAt)`). Tests: `CalendarEditChoicesTest`, `SearchCalendarFilterTest`.

### Calendar "edit…" → the Search window's "Is on the calendar at" filter — 2026-10-01

User request: *"When right-clicking on the calendar, the edit... option (or edit [element] if there is only one element
where the right-click is) opens a Search window filtered to only what is where the right-click is in the timeline."*
`Filters.calendarAtOn` / `calendarAtMillis` (new, defaulted), `Setting.CalendarAt`, `SearchDomain.calendarElementsAt`
(marks with no length within 15 min), `calendarAtConfig`; `App.openCalendarSearch` serves both entries with one window.
`CalendarElementsWindow` is no longer opened from the menu (still in the code). Tests: `SearchCalendarFilterTest`.

### Calendar "add…": alarms and timers too — 2026-10-01

User request: *"In the calendar, the add... option from the right-click menu filters also for alarms and timers."* The
calendar filter keeps every alarm and every timer that can still end at the instant (ahead of the clock, within 24 h);
`CALENDAR_ADD_KINDS` lists both. "Add to the calendar": an alarm rings at that time of day (weekday added, switched on,
through the element window's alarm edit); a timer is started to end there (`SearchDomain.calendarTimerIntents`).
`SearchDomain.results(nowMillis)`. Tests: `SearchCalendarFilterTest`.

### A History Unit for almost every user action — 2026-10-01

User report: *"I added an item in a Search window filtered for history unit, and it didn't create a history unit for
this add. There must be history units for almost every user action."* Scope chosen by the user: the Search window's
configuration, the account settings, the run state, the window layout — all undoable.

- `ExternalDelta` + `SchedulerIntent.RecordExternal` + `SchedulerState.externalRestores` (transient): Search
  configurations, window layouts and the menu buttons, recorded and put back by `App.ViewHistoryRecorder`.
- `SettingsDelta` + `SchedulerReducer.settingsUnit`: period kinds (add, delete, reset, duplicate, drawing, combinations),
  categories (create, rename, delete, duplicate, rules), the Notifications and voice switches.
- Timer and chrono run-state writes are units (`TimerRang` is not) — reverses the earlier "not units" rule.
- `commitDelta(committed = …)`; `WindowFrameState.applyLayout`; `WindowFrameHost.frontIdExcluding`; a closing window
  hands the focus to the one under it.
- Persisted: `PersistedDelta.External` / `Settings` (an older build skips them). Tests: `UserActionHistoryTest`; five
  older tests that asserted "no unit" now assert the unit.

### A tab created by a menu button is named after it — 2026-10-01

User request: *"When clicking on a button in the left-side menu, the tab that is created gets the name of the button."*
The built-in buttons already read as their windows do; a button the user made (☆, renamable) now names the window it
creates on the window bar (`WindowFrameHost.tabTitles`, `App.onMenuButtonClicked`), kept on the local `TabTitles`
placement row (no schema change). Test: `WindowFrameHostTest`.

### "Made in": every type of window, and a Search rename stamped Search — 2026-10-01

User request: *"In the Search Configurations window, for history units, in the 'made in' selector, I must see all the
types of window. For example, if the user renames a task in the Search window, then the history unit must be associated
with the Search window."*

- `HistoryWindow` gains every non-lateral window type (stored by name; an older build reads an unknown one as null).
- `WindowFrameHost.onFocus` + `App.historyWindowOfFrame`: a press in any window moves the focus to it before the press
  commits. Behaviour change: a per-object window's edits are undone from that window.
- `SchedulerIntent.MadeIn`: the Search and Added elements configurations windows' intents carry their window, so a rename
  committed on blur (after the focus left) is Search's.
- Tests: `HistoryChordsByWindowTest` (two new).

### Calendar "add…" → the Search window's calendar filter — 2026-10-01

User request: *"In the calendar, the add... option in the right-click menu must open the Search window with a filter for
only element that can be added in the calendar at the exact position of the right-click. It is a global filter
configuration in the Search configurations window, with a field to define the position in the calendar, a button to set
this field to the right-click mouse (this button appears only if the Search window comes from a right-click in the
calendar), and a switch button to turn the filter off/on."*

- `Filters.calendarAddOn` / `calendarAddAtMillis`, `Config.calendarClickMillis` (new, defaulted — an older stored
  configuration decodes to off); `Setting.CalendarAdd` (General section); `SearchDomain.calendarAddable`,
  `calendarKindsAt`, `calendarAddConfig`.
- Added so that "add…" still adds: the action "Add to the calendar" (`calendarDrafts`, `calendarAlarmDraft`) through
  the element window's seeding, now `CalendarElements.seeded` (one funnel), and its Save.
- Tests: `SearchCalendarFilterTest`.

### Search: "New" and "Duplicate" actions — 2026-10-01

User request: *"In the actions configurations section of the Search window, add buttons to create a new element of the
same type, and to duplicate it, with the title of the element getting ' copy' at the end."* `AddedAction.*New` /
`*Duplicate` per kind; new intents `DuplicateTasks`, `DuplicateCategory`, `DuplicatePeriodKind`;
`SearchDomain.duplicateIntents`, `newElementKeys`; `App.createElement(open = false)`. Created elements join the added
list. Tests: `SearchTaskActionsTest` (three new).

### Search: a result row's right-click "add" — 2026-10-01

User request: *"In the Search window, in the right-click menu of elements in the result list, replace the option to open
the edit window by the option 'add' to add to the selection list in the bottom right section. In the task tree, the
right-click menu of a task cell has the option 'edit' that opens the Search window with this task in the selection
list."* `TaskCellMenuActions.onAdd` (Search rows) in place of `onEdit`; `ItemResultRow`'s "edit …" → "add", and
alarm / timer / chrono / reminder rows open the menu on a right-click instead of their window. The tree's "edit task"
already opened the Search window holding the task (previous entry).

### The voice switch mutes the queued vocal messages too — 2026-10-01

User request: *"When there is a stack of notifications with vocal messages, if the user turns the vocal messages
switch button off, the voice must be mute even if the notification was already in the waiting room."*
`SchedulerEngine.launchVoiceSwitchMute` cuts the speaker (`stopSpeaking`, now the injectable `stopSpeech` seam) at the
switch's on→off edge. Test: `AlertSettingsTest.turning_the_voice_switch_off_cuts_what_is_already_waiting_to_be_said`.

### Dragging a maximized window's head un-maximizes it — 2026-10-01

User request: *"When dragging the header of a maximized window, it must bring it to its previous size and get dragged by
the mouse."* `WindowFrameState.unmaximizeUnder`, called by `windowHeadGestures`' new `onDragStart` once the drag passes
the touch slop: normal size (else the window's default), the pointer at the same fraction along the head, the head
under it. Tests: `WindowFrameStateTest`.

### The element edit windows are Search windows — 2026-10-01

User request: *"Now all the actions in the period edit window, put them in the added elements configurations window, and
remove the period edit window. Do the same with all the other edit windows."* Scope chosen by the user: the element
edit windows (period, task, category, alarm/timer/chrono, reminder); every entry point opens a Search window with the
element added (`SearchDomain.elementSearchConfig`, `App.openElementSearch`).

- Removed: `PeriodKindEditWindow`, and the account's use of the task / category / alarm / reminder per-object windows
  (`periodKindWindows`, `alarmWindows`, `reminderWindows` deleted). Kept for the default sub-tree's own tasks and
  categories, which no Search window lists.
- New actions: period Drawing / Combinations / Search its tasks / Delete; category "Name and rules"
  (`CategoryEditor`, extracted from `CategoryEditWindow`); alarm, timer, chrono and reminder "Edit"
  (`AlarmWindow` / `ChoresManagerWindow` gained `embeddedSubjects`; the reminder's constraint picker is hosted by
  `App`); task "Paths". `AddedTaskHandlers` → `AddedActionHandlers`, built once in `App`.
- `ObjectWindowKey.ELEMENT_KINDS`: their ☆ buttons open the Search window; their windows are no longer listed as window
  types, and one open at the last stop does not come back.
- Tests: `SearchTaskActionsTest` (element configs, drawing, delete, paths).

### Search: the tasks' "Schedulable" filter — 2026-10-01

User request: *"Add the 'schedulable' filter in the Search configurations window."* `Filters.taskSchedulable` (yes /
no / any) reads `SchedulerDomain.isPlaceableTask`: a leaf still in the tree. Stored with the configuration; an older
one decodes to any. The period edit window's resilience Search window opens with it on yes
(`resilienceSearchConfig`). Test: `SearchTaskActionsTest.the_schedulable_filter_keeps_the_leaves_the_scheduler_may_place_or_the_others`.

### Period edit window → Search; the task actions; the actions' filter — 2026-10-01

User request: *"In the period edit window, replace the drawing section by a field with a drop-down menu (using existing
code). Replace the section for task resilience by a button that opens a Search window where task is the only selected
element type. In the actions section of a Search window, for the tasks, add the actions available from the right-click
menu of a task cell, and those available in the task edit window. In the actions elements configurations of the Search
window from the resilience section of the period edit window, a text in the search bar filters for only the resilience
action. The action filter is applied to the actions elements configurations window, but also to the top right section
of the Search window. This action configuration has two fields: one for the period, and one for the resilience value.
The period field is set to the period of the origin period edit window."*

- Period edit window: the drawing is one `ChoiceDropDown` (the single-choice sibling of `CheckBoxDropDown`, same
  face) with swatches; the task list, its check boxes and the bulk field are gone, replaced by "Search the tasks"
  (`SearchDomain.resilienceSearchConfig`). `BulkPercentField` removed.
- `SearchDomain.Config.actionQuery` / `resiliencePeriod` (new, defaulted; older stored configs decode to none). The
  Added elements configurations window's bar now edits the target Search window's `actionQuery`.
- Task actions `TaskStartNow`…`TaskText`; new intents `SetTasksText`, `SetTasksScheduleUnit`, `AddTasksPath`;
  `TaskPathsDomain.candidatesForAll`; `ScheduleUnitEditor` extracted from the task edit window.
- Tests: `SearchTaskActionsTest`.

### Search: the "Default periods" filter and Reset of a default period — 2026-10-01

User request: *"In the Search configurations window, for restrictive periods, add the filters 'default periods'. In the
actions section of the Search window, for default restrictive periods, add the button reset."*

- The restrictive periods' "Origin" filter (built-in / yours) is now **"Default periods"** (yes / no); stored
  configurations still decode (the entry names are unchanged). A row's detail says "default period".
- New added-elements action **"Reset the default periods"** → `SchedulerIntent.ResetPeriodKinds`: drawing and
  combination rules back to `PeriodKinds.defaultStyle` / `DEFAULT_COMBINATIONS` (`PeriodKinds.combinationsReset`),
  keyed by the kind's id. Tasks' resiliences untouched. No history unit. A kind has no editable title today, so there
  is none to reset.
- Tests: `SearchAddedElementsTest.reset_puts_an_added_default_period_back_as_the_app_ships_it_and_leaves_the_others`,
  `the_default_periods_filter_keeps_the_shipped_kinds_or_the_accounts_own`.

### The window bar shows which window has the focus — 2026-10-01

User request: *"In the system tray, add a visual indication for which window has the focus."* The window bar's tab of
the window `WindowFrameHost.focusedId` names is drawn in the primary container colour with a 2 dp primary border and a
semi-bold title (`MinimizedChip`). Display only — no state, nothing persisted or synced.

### A period that carries "no screen" retracts at a mode-1 line — 2026-10-01

User rule: *"When the user is still on the computer when 'sleep' was scheduled, then the 'sleep' period retracts at now
line, because when there is sleep there is 'no screen', and if now line is in mode 1, the now line must not be in 'no
screen'."* Replaces the 2026-09-28 rule (only the `no screen` companion lifted; the window stayed over the line).

- `SchedulerDomain.retractsAtLine` is now `PeriodKindConfig.isOrImpliesNoScreen`: a `sleep` window, a `before bed` hour
  and any kind a single-kind rule gives `no screen` give up `[line, end)` with their companion — **only the one the line
  is IN**: a window ahead stays an obstacle in the plan (user's choice, the same day), since retracting it planned every
  future night as work. A line reaching bedtime at a screen gets one fill via `planMismatchAtLine`. The band is still
  laid whole; `clipPlanForRetractedPeriod` hides the plan ahead of the line inside it.
- Tests: `SleepWindowNoIdlingTest` (the four mode-1 cases failing since 09-28 pass again;
  `the_wind_down_hour_keeps_its_own_period_when_the_line_is_in_it` → `the_wind_down_hour_retracts_at_a_line_still_at_a_screen`).

### Combination rules: `not` in "When", `or` in "then", no screen → layers by default — 2026-10-01

User rules: *"Add the 'not' button in 'When' and 'then'. Add 'or' in 'then'. By default, when 'no screen' then ('no
computer unlocked' or 'not on a computer') and ('no phone unlocked' or 'not on a phone'). The period to the left of 'or'
is placed automatically when the user manually adds the period in the calendar, except when the 'or' condition is
already verified on the timeline."* Then, on asking: "then" is always derived, never stored; no `not` in "then"; `not X`
holds wherever there is no X on the whole infinite timeline; keep "layers ⇒ no screen" beside the new default.

- `PeriodFormulaToken.Not` (When only) and `PeriodCombination.then` is a formula too (`and`/`or`/brackets).
- A "then" with `or` (`isPlacement`) fires only from the periods the user drew (`closeRegions(present, manual)`,
  `RestrictivePeriod.manual`, `SchedulerDomain.statedKindRegions`), laying the left of each `or` where neither side is.
- New default `PeriodKinds.NO_SCREEN_LAYERS_RULE`; the calendar's layer hatch over the user's periods now reads the
  closure (with the "I'm away" spells as the fake layer) instead of `kindsOf`.
- Persistence: `PersistedPeriodCombination.thenFormula` and the `not` op; yesterday's `then` (and-joined fields) still
  written where it fits and read when `thenFormula` is absent. An account at the defaults gets the new rule; an edited
  list is left as it is. Pinned by `PeriodCombinationsTest`, `LayerPeriodKindTest.by_default_only_a_drawn_no_screen_…`.
- **OS exception** (follow-up rule the same day): in the past, where this device's OS log knows it was unlocked, a
  derived `no computer unlocked or not on a computer` lays `not on a computer` (same for the phone) —
  `closeRegions(…, knownAbsent)`, fed by `SchedulerDomain.knownUnlockedRegions` in the calendar's layer derivation.
  Not fed to the scheduler's own closure (it has no lock history; both layer kinds restrict nobody by default).
- **Sleep schedule counts as the user's** (user report the same day: *"there should be a 'not on a computer' derived
  from the 'sleep' period behind the now line"*): the `or` placement fires from every period the user STATED
  (`SchedulerDomain.isUserStated` = blue or orange outline), read with what it carries (a sleep window carries no
  screen). Sleep windows and wind-down hours therefore hatch both layers (the fake one behind the line where this
  device was seen unlocked), replacing the 2026-09-18 "a sleep window carries no layer". Breaks (grey) still don't.
- **Dotted obliques are "not on a computer"** (user, same day): the stretches `declaredLayerRegions` finds (the user
  stated the real layer while the OS saw the device unlocked) are drawn as the fake band — "Not on a computer" /
  "Not on a phone", the fake kind's drawing — instead of a dotted copy of the real layer under its name.
  `CalendarRecord.layerDeclared` and `periodDrawing(dotted = …)` deleted.
- **Not built**: turning a derived period into a stored blue one by editing it.
- **Deploy**: client rebuild only.

### Period edit window: companions folded into combinations, "When" formulas — 2026-10-01

User rule: *"remove the 'present with it' config, because it can be done by the 'combinations' config"*; *"only show a
field that opens a drop-down list with check boxes"*; *"add the buttons 'or', 'and', '(' and ')' to make a formula with
period selector fields"*.

- **"Always present with it" is gone.** A companion set is the rule `when <kind> then <set>`
  (`PeriodKinds.companionRule`); `PeriodKindStyle` holds the drawing only, and `SetPeriodCompanions` is deleted.
  `PeriodKindConfig.kindsOf` is now the closure of every rule a kind's period satisfies alone. The old default sets
  (sleep, before bed, both breaks → no screen) are the tail of `PeriodKinds.DEFAULT_COMBINATIONS`.
- **`PeriodCombination(id, condition, then)`**: `condition` is a token formula (`PeriodFormulaToken`: a field of kinds,
  `and`, `or`, `(`, `)`), read and edited only through `PeriodFormula`; `then` is fields joined by `and` only (user
  choice: a "then A or B" says nothing about which period to put there).
- **The default layer rule is one formula** (user rule): `(no computer unlocked or not on a computer) and (no phone
  unlocked or not on a phone)` → no screen (`PeriodKinds.LAYERS_RULE`), replacing the four one-field rules
  (`LEGACY_LAYER_COMBINATIONS`, same meaning). An older edited list holding all four untouched has them collapsed into
  it on `decode`; one with any of them edited or removed is kept as is.
- **UI**: every field is the shared check-box drop-down (`CheckBoxDropDown`, `KindsDropDown` now routes through it);
  under "When" the buttons or / and / ( / ) / ⌫, under "then" and / ⌫.
- **Persistence**: `PersistedPeriodCombination` gains `condition`/`then` (a one-field rule still writes `kinds`/`implies`
  for older builds); `PersistedPeriodKindStyle` and `PersistedPeriodCombinations` gain `folded`. A payload without
  `folded` has its companion sets (stored, or the old defaults) folded into rules on `decode`; an account at the
  defaults decodes to exactly the new defaults. Pinned by `PeriodCombinationsTest` (`an_older_payload_…`).
- **Deploy**: client rebuild only.

### "Fake no computer/phone unlocked" renamed "not on a computer/phone" — 2026-09-30

The two "I'm away" kinds below are now `not on a computer` / `not on a phone` (`PeriodKinds.NOT_ON_A_COMPUTER` /
`NOT_ON_A_PHONE`, titled "Not on a computer" / "Not on a phone"). The old stored names are healed on load by
`PeriodKinds.migrateStoredKind` (periods, the account's kind list, resilience maps, combinations, the clipboard) and
the old period titles by `migrateStoredTitle`; pinned by `RestrictivePeriodKindTest`'s
`a_payload_written_before_the_away_kinds_rename_…` and `PeriodKindNamingTest`.

### "Fake no computer/phone unlocked" and the combination rules — 2026-09-30

`docs/scheduler_requirements.md` § *$now line$ 3 modes* defines modes 2 and 3 by real and fake "no … unlocked" periods,
and the user asked for the period edit window to hold combinations: *"select a combination of periods, and select which
periods appear when this combination is present"*, with every real/fake computer layer and phone layer bringing "no
screen" unless the user changes it.

- **Two built-in kinds**, `fake no computer unlocked` / `fake no phone unlocked` (layer kinds, resilience 1 by default,
  drawn as the real slope dotted). The "I'm away" stretches are now this device's fake layer instead of being merged
  into its real one; a fake stretch never overlaps the real layer (`SchedulerDomain.fakeLayerRegions`). The calendar
  draws the fake layer as its own band (`CalendarRecord.layerFake`); the dotted "user's word" marking of the real
  layer is left to drawn periods.
- **Combination rules** (`PeriodCombination`, `SchedulerState.periodCombinations`, `SetPeriodCombinations`): one
  closure with the companions (`PeriodKindConfig.closeRegions`) now answers `companionPeriods`,
  `assertedNoScreenRanges` and `observedNoScreenRegions`; "both layers ⇒ no screen" was hard-coded in all three and is
  now the first of four default rules. At the defaults every answer is what it was.
- **Period edit window**: a *Combinations* section lists the rules naming the kind, each with the kinds that must all
  be present and the kinds they bring, plus *Add a combination* / *Remove combination*.
- **Persistence and sync**: `periodCombinations` in the payload, written only once edited (one `field` row), decoded to
  the defaults when absent and healed of unknown kinds. No SQLite or Supabase change. Removing a user kind drops it
  from every rule.
- Tests: `PeriodCombinationsTest` (defaults, a removed default, chained rules, the scheduler's view, the fake layer,
  reducer, codec incl. a payload without the field and an emptied list); the built-in kind list pins in
  `PeriodKindNamingTest` and `TaskResilienceTest` gained the two kinds.
- Not done: a peer's fake layer is not drawn (no peer's layers are transmitted; the mode already reads the account's
  away flag).

### Requirements 2026-09-30: a re-run of the scheduler rebuilds the breaks from history; a lock ends "I'm away"

`docs/scheduler_requirements.md` gained § *Use of the set of rules output* (a re-run drops what the previous rules
deduced but history does not hold) and the definitions of modes 2 and 3 (real vs. fake "no computer/phone unlocked",
which cannot coexist).

- **Every set of rules the scheduler finds rebuilds the break machine's bars from history**
  (`SchedulerEngine.rebuildBreaksFromHistory` → `SchedulerDomain.rebuildScreenBreaksFromHistory` →
  `BreakMachine.rebuildFromHistory`): banked and conducted breaks, observed "no screen", and the stretches the line lived
  through in this process (`lineStretches`). Bars may go DOWN here, unlike `absorbHistory`. A label with no history keeps
  its carried bar; a drag still owed keeps its due. This heals the account-3 bar of the entry below at the next re-run
  (a restart's first re-plan included).
- **A lock ends "I'm away"** (`noteScreenSignal`: a locked sample clears the flag and closes the declared-away stretch
  at the lock), so a line with every device really locked is in mode 2, not 3. An unlock still clears it too.
- Tests: `BreaksRebuiltFromHistoryTest`, `ReplanHealsDeducedBarTest` (the account-3 state healed by the start-up
  re-plan); `UserAwayUnlockTest` and `AccountAwayModeTest` rewritten for the lock rule.
- `ServerQuotaTest` stays at 511.77 MB of 512. Rebuilding from the observed evidence alone put it at 515 MB, which is
  why the line's own stretches count as history.

### A restart at an unlocked computer barred the 5-min break for an hour — 2026-09-30 (account 3)

The app was closed 15:52:21 → 16:29:49 while the computer stayed unlocked (no screen-off event since 12:41). The restart
catch-up walked that stretch in mode 2 and noted it as "no screen", so the break machine applied *"after a ≥ 5-minute of
'no screen', no 5min break in the next 1 hour"* at the landing: the next 5-min break was 17:29:49 (restart + 1 h) instead
of dragged by the line.

- `catchUpAfterNotRunning` asks the OS lock history of the stretch (`lockedIntervalsQuery`, a new engine seam the
  no-screen evidence scan reads too) and walks the unlocked parts in mode 1; only the rest is mode 2 and swept "no
  screen". `ReportDeviceSleep` is dispatched only when the walk starts with the device locked or asleep. A history that
  cannot be read in 8 s walks all of it in mode 2, as before.
- `start()` waits for that answer off the caller's thread before launching anything that can bank (`startRunning`).
- Tests: `CatchUpAtUnlockedScreenTest` (fails without the fix). `StartupOnRealDbTest` injects an asleep night, since its
  clock is faked twelve hours ahead where the OS has recorded nothing.
- Healed by the next re-run of the scheduler (see the entry above), not by this fix alone.

### The task tree pins the parent row at the top of its viewport — 2026-09-30

In the tree, the default sub-tree window and a Search window's expanded task, the direct parent of the row
appearing right below a band of one normal row height is drawn over that band once its own row has started to
leave the top. "Right below" is the first row whose bottom is past the band, half-covered rows included. The first
version used the first *fully* visible row. So when a parent row straddled the band, it pinned that parent itself
instead of the parent's own parent. A multi-line parent shows its bottom 28 dp only. Selecting the pinned copy scrolls its real row back so one
normal row height stays above it, and the tree's reveal now leaves that same gap for every row it scrolls into view
from above (it was 24 px), so a revealed row is never under the pinned copy.

- `SchedulerDomain.visibleRows` (new) is the visible-order walk with each row's parent row, depth and path.
  `visibleOccurrences` is now a view of it.
- **Row bands are keyed by path, not by occurrence.** On account 3 this pinned "writing" over "eye incision".
  The mirrored "english" draws "writing"'s rows twice with the same (cell, via), so the twins overwrote each
  other's bands. The empty row under the first "writing" read as sitting further down, where its twin was. This
  was a pre-existing collision, and the drag-move's drop resolution had it too. It now reads bands by path.
- The copy is `CellListSection` with `pinnedCellId`: the same row drawing, with no Edit Mode, min-time input,
  contextual menus or bounds of its own.
- Every `TaskRow` now reports its band, including the root strip, placeholders and the row being edited. Before,
  only selectable rows did, and a row stopped reporting while it was edited.

### § *Rule Structure*: the break machine, the rule cursor, a strict wake journey — 2026-09-30

`docs/scheduler_requirements.md` gained § *Rule Structure* (event-driven, cursor-based rules; no timeline-wide
filtering, sorting or search at runtime). The runtime did all three at every move of the line. ADR 0017.

- **The three screen breaks are a forward state machine** (`BreakMachine`, new): bars, the break the line is in, the
  one it drags, the stretch of "no screen"; each requirement is a transition, each transition an armed trigger. The
  runtime moves it (`SchedulerDomain.stepScreenBreaks`); the plan's obstacles, the calendar and the server's pose
  windows run it forward (`BreakMachine.predict`). The recurrence-bar walk (`DynamicPeriods.instances`,
  `dynamicPeriodPanels`, `screenBreakPanelsInWindow`, `screenBreakCueOccurrencesBetween`, `lookAwayHoldUntil`,
  `bankScreenBreaks`, the conducting-period environment) is gone, and with it the put-down, the "outlasted" chain,
  the day-quantized origin and the pull-back floor.
- **The task side is compiled for a forward cursor** (`RuleProgram`, new): the current task, the alternative, where a
  plan panel ends, wind-downs and reminders. One interpreter (`SchedulerEngine.interpretTo`) moves both from the tick,
  the cue sweep and every journey step. `AdvanceSchedule` runs only once a plan panel has ended or the tree changed;
  the cue sweep announces the machine's transitions and self-delays to the next armed trigger; the base mode is
  cached on its inputs; `Ctrl+Shift+Alt+Z` reads the alternative off the cursor (`ForceTaskSwitch.rules`).
- Behaviour, each the requirements' own: no break pushed out of a stretch nobody can run in; a break during a pause
  starts where the line meets it (never back-dated); a line away takes each break once; mode 2's dragged look-away and
  a pose due within its reach are one chain; a teleported pose is announced when it teleports; a machine with nothing
  to continue from starts rested at the line with its bars from the past it can see; an owed pose publishes its own
  window alone (so the published rules hold still).
- **The wake journey searches nothing** (user choice): it lays the plan held for its mode class and unrolls the
  cycle (`ExtendSchedule.unrollOnly`); the landing re-plans. `planJourney` is gone.
- Found by `:shared:startupCheck` on account 3's DB and fixed before shipping: the swept cover's evidence advanced the
  machine to the landing before the catch-up walk, so the whole walk was skipped; and a line back at a screen inside
  the night it had walked into owed a 15-min pose again, announced twice.
- **SQLite schema v17** (`16.sqm`): `screen_break_front.machine` (the machine at the front, JSON; NULL on older rows).
  Migration test `upgrades_pre_break_machine_v16_db_and_keeps_the_banked_record`.
- Docs: `docs/invariants/scheduler.md` § *The rules are read by a forward cursor*; `screen-breaks.md` rewritten on
  the machine; the stale bullets on the sleep retraction (only no-screen retracts since 2026-09-28) and on
  `Ctrl+Shift+Alt+Z` (the alternative on `[now line, now line + 10 min]` since 2026-09-29) corrected.
- `ServerQuotaTest` heavy hour: 126 reconciles against 125, egress 511.8 MB of the 512 MB budget (was 508.7).
- Client rebuild needed (desktop and Android); no Supabase change.

### The code brought to `docs/scheduler_requirements.md` (audit list, items 1–5 and 7) + OR-Tools packaging — 2026-09-29

An audit of the requirements file against the code listed eight gaps; these were fixed. Item 6 (the exhaustive search
tries a fixed set of run lengths, so the optimum is not guaranteed) and the O(1) rule-reading requirement are open.

- **OR-Tools never ran in the release** (`OR-Tools unavailable (ProviderNotFoundException: Provider "jar" not found)`):
  the jlink runtime lacked `jdk.zipfs`, which OR-Tools' loader needs to unpack its native library.
  `desktopApp/build.gradle.kts` adds it; checked on a runtime built the same way.
- **Rest stretch** (item 4): *"≥5-minute of 'no screen'"* counts whether or not a task could run there
  (`DynamicPeriods.isRestAt`); the older README's *without any task* is gone.
- **Mode 2 drags the look-away** (item 1): `dragsAtLine(label)` → `dragsAt(label, mode)` — poses in mode 1, the 20 s
  in mode 2, as `]now line; now line + 20s]`.
- **Automatic mode switch** (item 2): a mode-1 line inside a look-away is in mode 3 until it leaves it
  (`SchedulerDomain.lookAwayHoldUntil`, read by the engine and the calendar); no return to mode 1 meanwhile; mode 2
  never held. No re-plan for those twenty seconds.
- **Break kinds** (item 3): the 20 s is `no task allowed`; the 5 min is one minute of `no task allowed` then four of
  the new kind "5min screen break"; the 15 min is the new kind "15min screen break" (`PeriodKinds.BREAK_5MIN`,
  `BREAK_15MIN`, `DynamicPeriods.breakKind`). Both start at resilience 0 and appear in the resilience editor. Sleep
  resilience is now editable (the requirements call its 0 a default).
- **Switch task** (item 5): the refused task is replaced by the alternative the rules name, SET at
  `[now line, now line + 10 min]` (`SchedulerDomain.ALTERNATIVE_SCHEDULE_MILLIS`), then the scheduler re-runs; no
  refusal marker stands beside it. Where the rules name none, the old refusal marker still applies.
- **No re-plan at the line** (item 7): `guardPlanAtLine` reports a plan/break mismatch to `diagnostics.log` once and
  applies the rules (the break wins; an empty stretch stays empty) instead of rewriting a definitive schedule.
- Tests: `ForcedTaskSwitchTest` (the switch now asserts the placed block), `ScreenBreakKindTest`,
  `ScreenBreakCueRuleTest`, the long-wake and overdue look-away tests re-checked (the first now leaves "Walk" idle up
  to about an hour where the score prefers it). ADR 0003 § *Each break is its own kind again*; `screen-breaks.md`.
  Client rebuild needed (desktop and Android); no Supabase change.

### A 15-min pose appeared in the past: the calendar re-derived it — 2026-09-29 11:32 (account 3)

The banked record (`screen_break_history`) held two look-aways that morning; the calendar drew a 15-min pose at
11:32 behind the line. The calendar's past was never read off the record: `takenScreenBreakPanels` re-ran the walk
over the elapsed window with the mode and environment of NOW (a lock, the away chord, a restart or a failed load
redrew it). Behind that, the banking front waited at the start of any "no screen" chain and of any break in
progress, and re-derived everything from there at every advance — both built on an older wording of the chain rule
(*"now starts at the start of this chain. If it means starting in the past, this is the only exception to the frozen
past rule"*). `docs/scheduler_requirements.md` now says `max($now line$, $t_s$)` and has no such exception.

- The calendar draws the past from the banked record ONLY (bisected to the visible window); no record, no past
  breaks; a failed load is logged.
- A break is banked the moment the line reaches it, whole; the front is the line. `FrozenScreenBreaks.pending` and
  `DynamicPeriods.chainReaching` are gone.
- The chain rule never places a break behind the banked front (`max(front, t_s)`); re-deriving a past no record
  holds still gives the chain's start, which is what a continuous line meets.
- The one removal: a pose the line is inside when it is in mode 1 (the requirements' exception), persisted with a new
  `deleteScreenBreak` query (no schema change) and logged.
- A chain merge led by the pose the mode-1 line drags stays on the line (the requirements' "right after $now line$");
  brought back to a look-away's start it flipped banked ↔ removed every advance (`ServerQuotaTest` caught the extra
  `screen_break_rule` writes).
- Tests: `FrozenScreenBreaksTest` (four new), `BankedBreaksAndRecordsTest`, `SchedulerStoreTest`; tests that asked the
  WALK over an elapsed window now say so (`walkedAtLine`). `docs/invariants/screen-breaks.md`. Client rebuild needed.

### No idling dropped from the requirements: time left to nobody is a decision the score prices — 2026-09-29

`docs/scheduler_requirements.md` removed § *No idling* (commit `4a07113`). The rule had been enforced as a hard
constraint in the score's candidate definition, the rollout, the exhaustive search, the legality check and the MIP,
which threw away continuations scoring lower.

- Every decision now tries nobody (`ScoreModel.IDLE`, `ScoreModel.choicesAt`) beside every task; the improver can
  reassign a run to nobody; the MIP asks for at most one task per free slot. `J` is unchanged — the targets add up to
  100 %, so time left to nobody puts every lag behind and ends the panel it interrupts. It wins in practice on a
  stretch before an edge that every task would fill with a panel cut far short.
- A run of nobody is an explicit `Run(task = IDLE)`, placed as nothing, naming no alternative, and reported as
  `ScheduleFill.Result.idle` → `SchedulerState.plannedIdle` (in memory only).
- `planMismatchAtLine` does not re-plan inside `plannedIdle`: a hole the plan chose is the plan, a hole a moved break
  left is still a mismatch.
- A follower still never lays a peer's runs with a gap (`isLegalContinuation`); as a seed a gap reads as nobody's.
- Tests: `IdleDecisionFillTest` (new), `ScheduleScoreTest` (three new), `PlanMismatchAtLineTest` (one new); the
  week-long `SchedulerFillTest` check now accepts a decided hole. Every other "§ *No idling*" citation reworded.
- ADR 0001 § 14; `scheduler.md` (the rule that replaced *NO IDLING is the hard constraint*), `scheduler_score.md`,
  `screen-breaks.md`, PRD §10. Client rebuild needed (desktop and Android); no Supabase change.

### A lock and an unlock rewrote the definitive schedule — 2026-09-28 (audit, no incident)

`docs/scheduler_requirements.md` makes a schedule definitive *for every now-line mode*; a mode flip re-planned from the
line with a search whose answer depends on the time it is given, so a flip and a flip back could hand the line a
different schedule from the one already published.

- Every plan reduction also finds the plan for the other mode class (`SchedulerDomain.otherModePlan` →
  `SchedulerState.otherModePlan`, in memory only), extended stage by stage like the plan; a class flip lays it
  (`SchedulerIntent.SwitchTpMode`) and keeps the plan left as the other class's. Illegal on the timeline it lands on,
  it falls back to planning from the line (hard constraints win).
- A 2↔3 flip no longer re-plans (the two place everything identically).
- `SchedulerDomain.isPlanRun` is the one reading of "the plan's own runs" (the fill's seed, the peers' rules).
- Tests: `TpModeSwitchTest` (its control fails the old behaviour); `PerfBenchmarkTest` rows `otherModePlan …`.
- ADR 0001 § 13; `scheduler.md` § *The rules are parameterized by the mode*. Client rebuild needed.

### "Look 20 feet away" every thirty seconds; a 20 s hole with no break — 2026-09-28 09:27 (account 3)

An overdue look-away was placed at the banked front (the line). The at-line check re-planned around it; the re-plan
recorded the task under it up to the instant it read the clock, a few milliseconds into the look-away, and the rule
"never bank a break over recorded work" then refused that look-away for good. The bars never moved past it, so each
tick placed it again at the new front, re-planned, and announced it; the plan's hole for the next one (20 min on) was
left where the rules no longer put a break.

- The record wins only over a past no line saw (a first or stale banking); at the line the break wins, and work is
  recorded minus the breaks the line has started but not banked yet (`FrozenScreenBreaks.pending`).
- The start-up heal of 2026-09-27 (already run on account 3) is removed: at every start it would drop history.
- Tests: `OverdueLookAwayLoopTest` (fails without the fix); replayed on a copy of account 3's DB, the 09:27:20
  look-away is banked once and the next stays at 09:47:40.

### A 20 s break drawn over a task panel in the past — 2026-09-27 21:09 (account 3)

The app had not run for seven hours; the build that introduced banked breaks started and (1) did not walk that stretch
— its restart catch-up keyed on a banked record that did not exist yet — so the first advance banked the old plan as
work; (2) its first banking re-derived the whole day's breaks, which landed on that work; (3) while the line was inside
a no-screen chain the front waited at its start, so the breaks after it moved as the lock evidence landed.

- `catchUpAfterNotRunning` reads the last line off the banked record, this device's last active session and the last
  recorded work, and runs in `start()` before anything can bank.
- Work is recorded minus every banked break refusing its task; breaks are banked before the tick's records, never over
  recorded work, and a start-up heal drops the overlaps already stored (`BankedBreaksAndRecordsTest`). (Revised
  2026-09-28: the break wins at the line; the heal ran once and is removed — see the entry above.)
- Not healed: the work the old plan banked across the seven hours the app did not run is still in the record (nothing
  can tell it from real work); delete it from the calendar if it is wrong.

### Nothing in `docs/scheduler_requirements.md` violated or left unguaranteed — 2026-09-27

An audit of the requirements against the code, and the fixes. Client apps only: SQLite schema v15 → v16 (15.sqm),
no Supabase change.

- **Frozen past of the three dynamic periods.** The breaks behind the line are BANKED as it passes them
  (`SchedulerDomain.bankScreenBreaks`, `FrozenScreenBreaks`, engine-held, `screen_break_history` / `screen_break_front`,
  local-only, pruned at 90 days); every placement continues from the record. They were re-derived at each reading from
  an origin a day behind the line: a week view showed no break older than ~1.5 days, and the rest moved with the origin
  and with any later change to the environment, tasks, configuration or mode (`FrozenScreenBreaksTest`).
- **One break environment** (`SchedulerDomain.breakEnvironment`) for the fill, the calendar, the cue sweep, the
  published rules and the banking. A future week is drawn by the walk from the line (it was a grid restarted at the
  week's edge, which put breaks where the plan had not cut its holes); past the 168 h ceiling the breaks are the
  far fill's. The far fill is re-keyed on the rules, the mode and the environment, and given the engine's inputs.
- **Modes are parameters of the rules.** Away modes cover the whole continuation with "no on-screen task" (the cover
  was zero-wide, and the line walked into on-screen work the display clipped: an idle away stretch); a mode flip
  re-plans at once, locally; this device's ongoing pause is no rest of the account's in mode 1.
- **The plan is built around the breaks the line will meet** (`breaksTheLineWillMeet`): in mode 1 a pose is dragged and
  never happens, so it no longer leaves a hole the line sweeps empty; the calendar clips every break ahead out of the
  plan it draws. An idle check at the line (`planMismatchAtLine`) re-plans once per mismatch.
- **Wake and restart journeys** plan for mode 2 from their first instant, extend the plan as the line reaches its front
  and re-plan on landing (`WakeJourneyNoIdlingTest`); an app that was not running is walked the same way.
- **Definitive across devices**: adoption keeps a head already planned for the same rules, a counter is folded in as a
  seeded extension, and the rules deadline is 5 s so the wait fits the 10 s pace (`DefinitiveAcrossDevicesTest`).
- **"Look away now" is a dynamic period from the press** (in the break environment), so a look-away due during it is
  absorbed instead of overlapping it.
- **The start-up retroactive no-screen strip is removed**: it rewrote recorded work on late evidence, which is neither
  the dynamic-period rule nor a user action (the strip on a hand-laid period stays).
- **Repeating pre-placed tasks and restrictive periods** (`TaskPanel.repeat`, `PanelRepeats`, "Repeat every (days)" in
  both editors) — *"can be in infinite patterns"* (`RepeatingPanelsTest`).
- `ScheduleFill.unroll` rebases a cycle whose span is an exact number of repetitions (it kept the old anchor).
- Kept as it was, on review: inside a task-tree transition a plan made at a position holds `R` of that position and is
  re-made at every run start the line reaches (the requirements' parameterized rules; `BreaksAndSlidingPrioritiesTest`).

### A look-away made to vanish by "Look away now" gives its span to the task around it — 2026-09-27

User spec: when a 20 s screen break disappears because the user pressed "Look away now", and the same task touches
its start and end edges, the break is replaced by that task panel.

- The vanish is the first bar re-anchoring off the conducted break: pressed ~10 s before a look-away falls due, that
  look-away is gone and the plan's hole around it (`Write` up to it, `Write` after it) was left empty.
- `SchedulerDomain.vanishedPastBreaks` / `taskTouchingBothEdges`; the reducer's `withVanishedBreaksBridged` runs in
  `RecordConductedBreak` (record changes committed as the advance commits them). `calendarBoxesOfTask` is the one
  statement of a task's boxes. Tests: `VanishedBreakBridgeTest` (real fill, press, re-plan).

### The Search window's added elements; the calendar filters — 2026-09-27

User spec: three sections in the Search window — the search on the left half, the actions on the added elements
top right, the list of added elements bottom right; a check box on every result row, Select all / Deselect all, and
an Add button (the checked rows, else the selected one, else greyed); a button opening an "Added elements
configurations" window built like the Search configurations window; calendar filters in the Search
configurations window (on the calendar at all, every box from / until a day).

- The spec named the right quarters both ways round; built as its detailed half says: actions top right, added list
  bottom right.
- `Config.added` (result keys, local-only, on the placement row like the rest of the configuration);
  `SearchDomain.keyOf` / `resolve` / `withAdded`; `SearchRowOpeners` is now the one row-to-window mapping, shared by
  the result list and "Open each".
- Actions: `SearchDomain.AddedAction` / `addedActions` / `addedIntents` — categories and minimum time as ONE unit
  over every task (`SetTasksCategory`, `SetTasksMinimumTime`, `SetPeriodResilience`'s rule), alarms through
  `SetAlarms`, timers and chronos through their rows' own run-state intents.
- New lateral window `FloatingWindow.AddedConfig` / `HistoryWindow.AddedConfig` (appended; its own configuration is
  a `ConfigurationSearch`).
- Calendar filters for tasks and restrictive-period kinds: `Filters.taskOnCalendar` / `taskBoxesFrom` /
  `taskBoxesUntil` and the `period…` three; a task's boxes are `SchedulerDomain.calendarBoxesOfTask` (records + its
  panels), which `pastPeriodsForTask` now reads too. Gathered only while one is on.
- No sync or schema change; the stored configuration gains optional fields (older ones decode to none / any).
  Tests: `SearchAddedElementsTest`.

### "Go to calendar" and the calendar's "Locked on task" — 2026-09-26

User spec: a task cell's menu entry "go to calendar" focusing the calendar centred on the task's panel closest to
the now-line, turning on a new "locked on task" option (and "lock to now" off) that follows that panel's middle;
with no panel yet but a schedulable task of non-null priority, a loading logo and the lock on the last definitive
time until the task appears; not offered when no panel can appear.

- `CalendarLockDomain` (closest panel's middle, `reach`, `lockCenterMillis`), `LocalCalendarGoTo` +
  `TaskCellMenuActions.calendarTaskId`, `WeekView(lockTaskMillis)`, the configuration section's switch.
- Compose-only state in `App`; no persistence or sync change. Tests: `CalendarLockTest`.
- Same day, user spec: the provisional panels (the far-week plan past the definitive front, by the rules in force)
  count in "closest panel" — for the lock and for the menu's loading mark (`provisionalPanels`).

### The "creation" type; a task's paths; window rows one per type — 2026-09-26

User spec: a "creation" option in the Search window's type selector — a creation row for every other element type,
opened by a double- or right-click as that element's own "+ New …" (a task in its edit window, which gains a
configuration of its paths; a window as another Search window listing every window type, for which the Search
Configuration window gains a filter against duplicates of one window — the default timer's window not being a
timer's).

- `SearchDomain.Kind.Creation`, `CREATABLE` (task, category, restrictive period, alarm, timer, chrono, reminder,
  task tree, window — not history units, task relations, shortcuts), `App.createElement`.
- `SchedulerIntent.CreateTask`, `AddTaskPath`, `RemoveTaskPath`; `TaskPathsDomain`; the task edit window's Paths.
- `Filters.windowDuplicates` ("Duplicates": shown / hidden), `WindowEntry.type` / `typeTitle` / `placeholder`,
  `WINDOW_TYPES_CONFIG`. The stored Search configuration gains an optional `windowDuplicates` (absent = shown).
- Tests: `TaskPathsAndCreationTest`. No persistence shape change beyond that optional field; no sync change.

### A menu button focuses its exact window when it is open — 2026-09-26

User spec: clicking a button of the lateral menu, if the exact window is already open, focuses it instead of
creating a new instance. `App.openNewWindow` first looks among the open windows of the kind (the original and its
copies) for one whose configuration equals the one it would open with (`normalizedWindowConfig`: the ☆ snapshot,
else the kind's default; any window of a kind without one), and brings it back — out of the bar, to the front,
focused. A per-object ☆ button uses `ObjectWindows.open` again (`openNew` removed). No state change.
- **Anomaly, same day:** a ☆ "timers" button made before buttons kept a configuration (release DB: `b4`,
  `window = Search`, no `config`) read its window's configuration at every click, so after the types were changed
  in the Search window it had opened, it still "found" that window. Such a button is now given its window's
  configuration ONCE, at load (`CustomMenuButtons.withConfigsFrozen`), and a click never reads the live window.

### Spoken messages say their own sentence; the window bar's "Close all" is "Reset" — 2026-09-26

User spec: replace "Close all" in the system tray by "Reset"; improve the vocal messages — the chord for "I'm
away" says "I'm away" / "I'm back", not the whole shortcut; "Current task: <title>", the voice needing not say what
the notification writes, which may carry details such as the task's shortest path, its start hidden when too long.

- The window bar's right-corner button reads **Reset**; it still closes every window (label only).
- The Ctrl+Shift+Alt+A receipt WRITES which way it went too — "<chord> — I'm away" or "— I'm back"
  (`shortcutReceiptAction`), no longer "I'm away / I'm back".
- `engine/SpokenMessages.kt`: one builder for every spoken sentence (chord receipts, current task, a rest pose,
  the rings, wind-down, "Notifications on"); `notifyUser(spoken = …)`; `SpokenMessages.SILENT` for "Look away
  now", whose look-away speaks at once. `VoiceUtterance.forNotification(title, message, cue, spoken)` is the one
  decision, for the engine and the History replay.
- The written "task to do now" carries the task's shortest path below the tree's root
  (`SearchDomain.shortenedPathLabel`, ≤ 48 characters, start dropped first).
- `NotificationLogEntry.spoken` (persisted, optional — an older entry replays its text read out, as spoken
  then). Local-only diagnostics; no sync or migration.

### The Configuration Search window lists the Search window's Reset; one type selector — 2026-09-26

User spec: the search configurations window includes every configuration of the Search window, its Reset
included (distinct from its own top-section Reset), and the type selector looks the same in both: a field with a
drop-down. `SearchDomain.Setting.ResetSearch` in the General section; the Types setting draws `KindsDropDown`
instead of a column of check boxes. No state change.

### Lateral-menu buttons open a new window; ☆ keeps a window's configuration; taskbar tabs — 2026-09-26

User spec: the lateral menu's buttons only open a new window at each click and are no longer drawn blue; the ☆
creates a button that always opens a new window with the exact configuration the window had (e.g. a Search);
a window-bar tab focuses its window if it did not have the focus, reduces it otherwise, and brings back a reduced
one.

- `App.openNewWindow` is the one funnel: the original when closed (its configuration reset to the default, or to
  a ☆ button's), else a copy `Kind#n`. The task tree and the calendar are not duplicable: opened or brought back.
- `CustomMenuButton.config` (new, optional — a button stored before it opens with what its window has now);
  `ObjectWindows.openNew`; `WindowFrameHost.onTabClicked`. Local-only view state; no persistence or sync change.

### The calendar window's configuration section; the day selector leaves the lateral menu — 2026-09-26

User spec: a section in the calendar view holding the day selector and the other configurations; the day
selector removed from the lateral menu.

- `CalendarConfigurationSection`, down the calendar window's left side: the month grid (`MiniMonth`, moved out of
  `LateralMenu`), then "Lock to now", "Reminders" and "Screen breaks" as switches — the "View ▾" drop-down that
  held them is gone. The window's default size grew to 920 × 560 dp to make room.
- The day selector's state (`selectedDate`, `monthAnchor`, the jump nonce) stays in `App`, handed to the window.
  No state, persistence or sync change.

### Chronos, and timers that count below zero — 2026-09-26

User spec: add the chrono type; and an option in a timer's configuration to let it go into the negatives — a timer
that reached the end with the option off and has it turned on afterwards counts as if it had always been on (from
the exact instant it reached 0).

- **Chronos**: `ChronoEntry` / `ChronoDomain`, `SchedulerState.chronos` (authoritative, persisted + synced — the
  row sync splits it generically, no sync code), a third "Chronos" section in the Alarms window, a per-object
  window per chrono (`AlarmWindowSubject.Kind` replaced its `isAlarm` flag), and a "chrono" Search type with a
  State filter and a Sort by. `SetChronos` is a Main History Unit (`ChronosDelta`, persisted as
  `PersistedDelta.Chronos`); start / pause / reset are not.
- **Below zero**: `TimerEntry.goesNegative` (a setting, in the default timer configuration too) and
  `endedAtMillis` (the instant a run reached zero, kept on the rung-and-reset row). The engine's ring dispatches
  `TimerRang` (was `ResetTimer`). `TimerDomain.withGoesNegative` is the one setting that moves the run.
- Persisted-DB compatibility: a payload without `chronos`, `goesNegative` or `endedAtMillis` decodes to none /
  off / none (`ChronoTest`, `TimerBelowZeroTest`). No SQLite or Supabase migration.

### The Search window finds windows — 2026-09-26

User spec: a "window" option in the Search window's type selector lists every window, open or not; several open
instances of one window are listed individually, and each row says whether it is open, not open, or in the
system tray — worded "minimized" (2026-09-26, user).

- `SearchDomain.Kind.Window`, fed by `App` (`searchWindowEntries`): every registered frame of
  `WindowFrameHost` (so every copy, per-object window and notice, each its own row), plus each lateral-menu window
  that is not open. A window reduced to the window bar along the bottom of the app reads "minimized".
- The status is the row's detail section; a "State" filter (`Filters.windowStatus`) and a "state" sort key go with
  it. Opening a row opens the window, or brings it back (out of the bar, to the front, focused) — never closes it.
- Local-only view state only (the stored Search configuration gains an optional `windowStatus`); no SQLite or
  Supabase migration.

### Default configurations of a new alarm, timer and reminder; the default sub-tree leaves the menu — 2026-09-25

User spec: at the bottom of a timer's window (opened from Search), a button defining the default configuration a
new timer is created with; the same for alarms and reminders; and the "Default sub-tree" button moves from the
lateral menu to the top of the task tree window's configuration section (settled with the user: the task tree
window, not the calendar).

- `SchedulerState.newAlarmDefaults` / `newTimerDefaults` / `newReminderDefaults` (settings only), set by
  `SetNew{Alarm,Timer,Reminder}Defaults` (no history unit), persisted with defaults (a payload without them
  decodes to the built-ins; a stored one carrying an identity is healed), merged whole in `SnapshotMerge`, synced
  as one field row each (`EntityRows`, no sync code). No SQLite or Supabase migration.
- `NewElementDefaults` is the one funnel a new alarm/timer/reminder is built through — "+ New …", "+ Add …", the
  calendar's new alarm draft (`seedDraft`) and its save (`applyAlarmDrafts`).
- The default windows are the element's own editor in a settings-only mode (`AlarmWindow`/`ChoresManagerWindow`
  `defaults = true`), per-object windows of their own (`ObjectWindowKey.Kind.*Defaults`: ☆, kept across restarts),
  opened by "Default … configuration" under "+ New …".
- `DefaultSubtreeControl` (switch + button) is drawn atop `TaskSchedulerScreen`'s configuration, above the
  task-tree name field; `LateralMenu` lost it. `NewElementDefaultsTest`.
- **Deploy:** client apps only (`account{1,2,3}-*deploy*.bat`).

### The desktop app's own window keeps its size — 2026-09-25

User ask: preserve the size of the whole app. The OS window's normal position + size and its maximized state are
kept on the reserved placement row `AppWindow` (`rememberPersistedAppWindowState`, written ½ s after a change
settles) and restored at launch, falling back to the platform default where no screen shows its title bar.
`main` now opens the store ONCE, before the window (it was opened inside the window's content). No migration.
Then (anomaly: built, maximized, rebuilt — not maximized): a window never moved kept `WindowState.position` at
`PlatformDefault`, so no normal bounds were known and a maximize saved nothing. The bounds are now read off the OS
window inside it (`KeepAppWindowPlacement`), and a maximize with none known yet keeps the default size centred.
`AppWindowPlacementTest`. **Deploy:** client apps only (`account3-deploy-windows.bat` for the desktop release).

### Per-object windows: one per object, and a ☆ on every one with a stable id — 2026-09-25

User spec: every window has the ☆ in its head (the timer window opened from Search had none), and opening
another timer's window must not close the one already open. Settled with the user: the "no replace" rule for
EVERY per-object window, and the ☆ on the ones whose object has a stable id (not on calendar drafts, the
elements at a spot, the constraint picker, companions or notices).

- `DuplicableWindows` (one slot per kind) → `ObjectWindows` + `ObjectWindowsHost` (`ui/WindowFrame.kt`): the
  list of a kind's open windows, each its own frame id (`TaskEdit#3`); re-asking for an open object presents its
  window (`WindowInstance.presentRequests`). The tree's percentage click no longer closes a relative-priority
  window (and vice versa); a tree's cell entering Edit Mode still closes that tree's weight/relative windows.
- The global `popupFromDefaultSubtree` → `TreeObject(id, template)` per window, since windows from both trees
  can now stand open together.
- ☆: `ObjectWindowKey` (`object:<Kind>:<live|template>:<id>`, `ui/ObjectWindowKey.kt`), handed to the frame
  through `WindowInstance.menuKey` and kept on `WindowFrameHost.Registration.menuKey` (`rekey` when an alarm,
  timer or reminder window moves on — `AlarmWindow(onShownChange)`, `ChoresManagerWindow(onSubjectChange)`).
  A button whose object is gone is hidden, not deleted. Local-only, like every user-made button.
- Then (same day, anomaly: two open timer windows gone after a rebuild): a per-object window with a key is kept
  across restarts on a placement row of its own, by frame id (`AlarmOrTimerEdit#2`), `config` = its key —
  `ObjectWindowMemory` (App over the rows), `ObjectWindows.restore` at startup, frame-id bases as constants the
  windows own and `ObjectWindowKey.Kind.frameBase` reads. No SQLite migration: the `window_placement` row
  already had `config`. Calendar drafts still live for the session.
- A per-object window's button is named "<object name> <noun>" by default ("Tea timer", "Writing task"; "Timer"
  when unnamed) — `ObjectWindowKey.buttonTitle`.
  `ObjectWindowsTest`.
- **Deploy:** client apps only (`account{1,2,3}-*deploy*.bat`).

### Lateral-menu buttons trimmed; the "All tasks" window removed — 2026-09-25

User spec: remove the Reminders, Alarms, History, All task trees, All tasks, Task relations and Keyboard shortcuts
buttons from the left menu, and remove the windows that can no longer be opened — the Search window and the
per-object windows its rows open replace them.

- The six other windows are still opened from Search (a row's double-click / Enter / "open in …"), so they stay.
  **All tasks** had no other way in and is deleted with everything only it used: `TaskListWindow`,
  `TaskListProjection`, `SchedulerIntent.InTaskList` / `CollapseTaskListRows`, `reduceInTaskList`, the
  `taskList*` view state, `HistoryWindow.TaskList`, the sorter (`TaskListSort`, `taskListEntries`) and its
  "similar titles" figure (`TitleSimilarity`), and the tree view's `rootRenameOnly` / `allowRootDrop` /
  `hideModeSelector`. Its membership rule survives as `SchedulerDomain.tasksInTree` (`TasksInTreeTest`), which
  `periodKindTaskRows` reads.
- Healing: the codec reads the retired window name `TaskList` as the tree's (focused window, a unit's window, a
  focus move, a selection unit — `HistoryChordsByWindowTest`); a user-made menu button for it is not shown.
- Then (same day): in the Search window, a double-click or `Enter` on an alarm, a timer or a reminder row opens
  its own window, as the right-click already did — so the "open in Alarms / Reminders" menu entries, which could
  never show, and Search's `onOpenAlarms` / `onOpenReminders` are gone. That left the **list of every
  reminder** unreachable, and it is deleted: `ChoresManagerWindow` is now only the one-reminder window
  (`subject` required; reminders are created by its "+ New reminder" and by the calendar's "add reminder"),
  with `FloatingWindow.Reminders`, `AccountRemindersWindow` and `HistoryWindow.Reminders`. The codec reads the
  retired `Reminders` window as the Search window's, where a reminder is opened now. The full Alarms window
  stays: the calendar's alarm/timer edit entry opens it.
- **Deploy:** client apps only (`account{1,2,3}-*deploy*.bat`).

### Buttons the user makes in the lateral menu — 2026-09-25

User spec: a section at the bottom of the left menu for custom buttons, and in every window's head, left of
duplicate, a button creating a menu button for that window, whose title opens in edit mode with its default name
all selected. Settled with the user: the lateral-menu windows (the ones `App` reopens by frame id, their copies
and Search configurations included) — not the per-object windows; a right-click renames or removes one.

- `ui/CustomMenuButtons.kt` (`CustomMenuButton`, `CustomMenuButtons` pure list ops + JSON, `CustomMenuSection`),
  `MenuButtonHost` / `LocalMenuButtonHost` (`WindowFrame.kt`, the head's ☆), `LateralMenu(customSection)`,
  `App.addMenuButton` / `onMenuButtonClicked` (reopens a closed copy from its row). `MenuButton` is shared now.
- Adding one opens the menu if it was retracted and scrolls it to its very end (the scroll is held by `App`,
  `LateralMenu(scrollState)`), a frame after the button is laid out.
- Local-only: the list is the `config` of a reserved `LateralMenu` placement row — no SQLite migration, no sync.
  `CustomMenuButtonsTest`.
- **Deploy:** client apps only (`account{1,2,3}-*deploy*.bat`).

### "Show the filters that are on" in the Configuration Search window — 2026-09-25

User spec: a button showing the filters that are on even when their type is not in the results and "Only the
types in the Search results" is on, so a filter that removes its own type can be set off right after.
`ConfigurationSearch.showFiltersOn` (local JSON, absent = off, tested), `SearchDomain.configurations(filtersOn =)`,
`Filters.isOn` (now also what `activeCount` counts). Client apps only.

### Select all / Deselect all in every drop-down of check boxes — 2026-09-25

User spec: an option without a check box at the start of every such drop-down, checking every box and turning
into "Deselect all", then back. `SelectAllMenuItem` (`ui/SearchWindow.kt`), used by the two there are: the kind
drop-down (Search and Search configurations) and each section's Sort by (select all appends the unchecked methods
at the bottom in the menu's order). Its label follows the boxes, not its last press. Client apps only.

### Every window takes the focus; the history chords are relative to it — 2026-09-25

Anomaly (account 3, release app): the Search window listing history units, sorted by date, showed no new unit when
the user clicked from window to window. Cause: only five windows (tree, calendar, reminders, history, alarms)
could take the focus — a press in any other recorded nothing. User rule: *"ctrl+z or ctrl+y or ctrl+shift+z
navigates in changes relative to the focused window, alt+arrow keys navigates in positions/selections relative to
the focused window, and shift+alt+arrow keys navigates in all positions/selections."* Settled with the user: a
position is **the focused window and what is selected in it**; every window with a selection records it.

- **`AppWindow` deleted**: `SchedulerState.focusedWindow` is a `HistoryWindow` (+ `ConfigSearch`, `Online`), with
  `focusedInstance` for a copy. Every window's raise dispatches `FocusWindow` (a WindowNav unit). `App`'s
  Compose-side `activeHistoryWindow` is gone: a unit is stamped with the focused window (`stampsWindow`).
- **Routing** (`SchedulerReducer.undoIn`/`redoIn` over a `HistoryWalk`): Ctrl+Z = Main+Calendar units made in the
  focused window; Alt+arrows = the focused window's selections (window read off the delta); Shift+Alt+arrows
  (`UndoPosition`/`RedoPosition`, new) = every selection and focus move, by `deviceSeq`. `chronoId` is now
  counted across every category so one device's units are totally ordered.
- **New selection units**: `ViewSelectionDelta` (All tasks, Default sub-tree, Search sub-trees) and
  `WindowSelectionDelta` (`SelectInWindow`: the Search row — moved from Compose into the state, per copy — and the
  Task trees window's open entry). Walking back a focus move re-presents that window (`App`, never dispatching).
- **One reading of the chords**: `undoRedoIntentFor` takes Alt; the tree's own Alt-arrow branch is gone; an
  app-root `onKeyEvent` answers the chords in every window that does not itself.
- History window: the chord field gains `Shift+Alt+arrows`; "both" is now "Ctrl+Z or Alt+arrows".
- **Second half of the anomaly** (still seen after the deploy above): a probe of a copy of the release DB showed the
  focus moves WERE recorded (922 WindowNav units, the newest at the click) and ranked first by the saved
  configuration — the Search window's keyed `LazyColumn` anchors its scroll on the first VISIBLE row, so each new
  row sorted first landed just above the view. A list read at its top now stays at its top.
- Persisted: `focus` units gain two optional instances; `viewSelection` / `windowSelection` are new delta types
  (an older build skips a unit it cannot read). Focused-window names of the old enum all decode. No SQLite
  migration, no Supabase change.
- **Deploy:** client apps only (`account{1,2,3}-*deploy*.bat`).

### Sorting in the Configuration Search window — 2026-09-25

User spec: *"In the Search configurations window, add sorting configurations"*, then: a list of sorting
methods *"from dominant to less dominant"* at *"the top of the second section"* of the window (above the
scrolling configurations), fed by a **Sort by** per type section — *"a drop-down menu with check box for each
sorting methods"*, a checked one added *"to the bottom of the list"* — reorderable by **drag**, each with an
**✕** removing it.

- `SearchDomain.SortKey` / `sortKeysOf` / `SortMethod` / `DEFAULT_SORTS` / `withSortMethod` /
  `movedSortMethod`; `Config.sorts` (a list); `Setting.*Sort`; `results(sorts=)`. A method of one type reorders
  that type's rows within their own places. Kept a per-method ascending/descending toggle in the list (not in
  the spec; a key without a direction would halve what each method can say).
- The default list is relevance alone, reproducing the previous order exactly. Stored in the Search window's
  local JSON config (`sortMethods`). A config written before sorting decodes to the default; one written by the
  first sorting build (`14e11c8`: `sort` + `kindSorts`) becomes the equivalent list (both tested). No SQLite
  migration, nothing synced.
- **Deploy:** client apps only (`account{1,2,3}-*deploy*.bat`).

### Search task rows are the tree's own cells — 2026-09-24

User spec: task rows of the Search results use *"the same code used in the task tree"* — the task's background
colour, the expansion arrow between the type section and the title, the absolute priority, minimum time and
category between the path section and the dead logo; Edit Mode stuck to Rename, entered by typing; no row
selected while the user is in the search bar. Settled with the user: the arrow **expands the sub-tree** (real
cells); a task with no cell **looks the same and can be renamed**, and a task **cut from the tree and kept by the
timeline** cannot have its sub-tree modified.

- `TaskRow` gains `rowLeading` / `afterTitle`; `SearchTaskRow` draws a row through it.
- `SchedulerIntent.RenameTask` (task-level; `SchedulerDomain.withTaskRenamed` — live tree + stored trees holding
  the id; `TreeMutationDelta`, or `TaskTreeDelta` when a stored tree changes) and `SchedulerIntent.InSearchSubtree`
  (`projectSearchSubtree`, `readOnly` refused by the reducer). `SchedulerState.searchExpanded` /
  `searchSelection` / `searchEditSession`: in memory only, like "All tasks"'s — no codec change, no migration.

### The task tree is a window; the window bar and Close all — 2026-09-24

User spec: the task tree *"must be a window too, maximized by default"* with a lateral-menu button; the menu's
"Close windows" becomes a **close all** button at the right corner of the app's system tray, which *"appears
when there are opened windows, with a tab for opened windows (minimized included)"*.

- `ui/TaskTreeWindow.kt`, `FloatingWindow.TaskTree` (placement row `TaskTree`, open on a first run),
  `rememberWindowFrameState(defaultChrome)`, `TaskSchedulerScreen(keyboardEnabled)` (deaf while reduced).
- `MinimizedWindowBar` → `WindowBar`: every registered window, Close all. `LateralMenu` loses
  `anyWindowOpen`/`onCloseAllWindows`, gains `taskTreeOpen`/`onToggleTaskTree`.

### Duplicating a window — 2026-09-24

User spec: *"a duplication button to the header of any window of the app, at the left of the full width
button"*. Settled with the user: an **independent copy** (same view to start, then its own), **kept across
restarts** like the original, on **every window** (lateral-menu and per-object).

- `LocalWindowInstance` / `WindowCopy` / `DuplicableWindows` / `CompanionWindowScope` (`ui/WindowFrame.kt`);
  `App.LateralWindow`, `duplicateWindow`, `windowCopies`; per-copy `searchConfigs`, `configSearches`,
  `taskListSorts`; `ConfigurationSearch.target` (which Search window it edits). Copies of lateral-menu windows are
  `window_placement` rows `Name#n` — no migration.
- **Replaces** "at most one window per subject" with "opening another subject replaces the window; copies are
  asked for". **Not done**: the Calendar (its display pipeline is one visible span — see `popups.md`), and
  notices.

### The Configuration Search window and the Search filters — 2026-09-24

User spec: a button in the Search window's configuration opening a window that *"shows all the configurations
of every possible element, separated in sections"*, itself a configuration section (search bar + type selector)
above a result section, with a button keeping *"only those related to the elements appearing in the result of
the Search window"*. Settled with the user: the configurations are **search filters** that narrow the Search
window live, and the window's bar searches the configurations' **names**.

- `SearchDomain.Filters` (per kind, each with an "any"), `Setting`/`configurations`, `kindsInResults`,
  `ConfigurationSearch`; `ui/ConfigurationSearchWindow.kt`; `FloatingWindow.ConfigSearch` (placement, chrome and
  its own configuration kept locally like the Search window's).
- The Search window no longer holds its query and kinds: `App`'s `searchConfig` is the one copy, edited by both
  windows. The stored config became JSON; the first shape still decodes (tested). No SQLite migration.

### A window comes back as it was left; Search keeps its configuration — 2026-09-24

User spec: the Search window's configuration *"must be preserved locally"*, restored when the window comes back
from the bottom bar, from full width / full height / full size, from closed to open, and when the app starts;
and *"the states full width, full height, full size, in the system tray and the position"* are preserved
locally too.

- SQLite migration **`14.sqm`**: `window_placement` gains `fill_width`, `fill_height`, `minimized` and
  `config`. Local-only, never synced. Test: `upgrades_pre_window_chrome_v14_db_and_preserves_placements`.
- **Reverses** the earlier *"reduced / filled / maximized are session-scoped"* rule for every lateral-menu
  window, not only Search — one frame, one rule (`WindowChromeMemory`). A closed window is not reduced.
- The Search window's query and checked kinds (`SearchDomain.Config`) are kept by `App` on the Search row. Being
  reduced or filled never lost them (a reduced window stays composed); closing and restarting did.

### A Search window, and a task keeps its last path — 2026-09-23

User spec: a lateral-menu button opening a window with *"a configuration section and a result list section
below"* — a search bar and a drop-down of **task, task category, restrictive period, alarm, timer,
reminder** — whose rows are *"always presented in the same height and width"*, a task row being its title, its
path in a rectangle, and a logo for a task *"not in a task tree, but still referenced"*. The title prevails
over the path when the row runs out of width, several paths get an expansion arrow listing them all, and
*"if the task is not in a task tree anymore, it keeps its last path"*.

Settled with the user before building: the last path is **kept when a task id ceases to be in a task tree**,
the **shortest** one when several occurrences are cut at once, and **forgotten when nothing references the task
any more** (the history cap removing its last panel, for example). A row has **the behaviour of a task cell in
its fixed-height form** — select, walk, open, right-click menu — not an inline Edit Mode. The other kinds show
their name and one detail. (A sentence of the first draft about the Change Task menu's paths was withdrawn by
the user as unrelated.)

- **`Task.lastTreePath`** (new, authoritative, persisted + synced, absent ⇒ none). Titles from the tree's root
  — named after the tree — to the task's parent, **not ids**: the ancestors are usually purged in the gesture
  that cuts the task. A payload from before the field decodes to no path, and such a tombstone is a result with
  an empty path box (`SearchWindowTest`).
- **`SearchDomain`** (new, `scheduler/domain/`): membership across **every** task tree (the live one and the
  stored entries, never the stale active entry), all paths per task (bounded), the stamp, and the results.
  `SchedulerReducer.reduce` runs `withLastTreePathsStamped` after the category settle — on the account's own
  states only, and **only at edit boundaries**, measured from the session's `treeBefore` when one closes.
- **Only what cannot be derived is stamped.** The first cut stamped every task that left, and
  `ServerQuotaTest` caught it: Change Task is the default mode, so the heavy user's renames detach parents, and
  each rename rewrote the parent AND its whole sub-tree (egress 519.6 MB against a 512 MB budget; 510.6 before
  the feature). A task stranded under a stamped task now derives its path from it (`strandedPaths`), so a
  detached parent costs one row: 511.0 MB. **The budget has ~1 MB/month of headroom left at HEAD** — the next
  synced feature will need it cut elsewhere.
- **`ui/SearchWindow.kt`** (new), `FloatingWindow.Search` / `HistoryWindow.Search` (the History window's
  decode already drops a name it does not know, so an older build reading a unit from here is unaffected), the
  lateral-menu **Search** button (between "Task relations" and "Categories"), and the "✕ Close windows" set.
- Docs: PRD §7 *Search*, `task-tree.md` § *The Search window, and a task's last path*, the CLAUDE.md
  authoritative-state row.
- **Deploy:** client apps only (`account{1,2,3}-*deploy*.bat`). No Supabase change: the field rides the
  existing task rows, omitted when empty.

### The calendar’s "add…" and "edit…" became ONE window over a SET of elements — 2026-09-23

User spec: *"the option calendar → right-click menu → add… directly opens the add window"*, whose first
section selects the elements to add (a task-cell search bar with its id and title menus, a drop-down of the
kind — **task, restrictive period, alarm, reminder** — an *add* button, and the list of what has been
chosen), whose second holds *"all the configurations that are shared by all the elements"*, and whose third
holds the rest, *"grouped by sharing as much as possible"* and titled by the names and kinds of the elements
that share each one. *"edit… does the same thing, but in the selection section the only suggested and
selectable elements are the ones that are at the location of the mouse."*

What it replaced: `CalendarAddWindow`, a **router** — it asked *what do you want to add?* and handed off to
the one editor that owned that object, each of which asked for its own bounds again. Fine for one element and
wrong for several: laying a task panel and the period that must cover it meant two windows and the same two
instants typed twice, and there was no way at all to say "these three things all start here".

- **`CalendarElements`** (new, `scheduler/domain/`) is the whole model and it is pure: the four `Kind`s, the
  `Field`s each one owns (`fieldsOf`), the bound modes each one can express (`boundsOf`), the `Draft`, and
  `calendarConfigSections` — which **partitions the FIELDS by their owner set**. Two fields are one section
  exactly when the same elements own both; the section whose owners are *everybody* is the spec’s §2. It is
  not a partition of the elements, and cannot be: a panel shares `Start` with an alarm and `End` with a
  period, so an element belongs to as many sections as it has distinct owner sets.
- **One element needs no special case**: every field is then shared by all, so the window is one untitled
  section and reads exactly like the single-object editor it replaces.
- **An alarm’s `Start` is the instant it starts ringing and its `End` is where the ring stops** (the user’s
  answer). An `AlarmEntry` is a time of day on a set of weekdays, so a start writes `timeOfDayMinutes` and an
  end writes `soundSeconds` — **never a date**, which is only where the occurrence was drawn. The weekdays
  are the `Rings on` field beside it, so a start moved onto another day cannot quietly add one.
- **Mixed values.** Three things at one point hold three different starts; a field seeded with the first
  would have moved the other two to it on a Save nobody typed into. `sharedValue` returns null for "they
  disagree" and the field shows blank; each draft keeps its own value, and `applyToSection` — writing by the
  section’s **indices**, because a window may hold two equal drafts — is the only thing that copies one
  across. An untouched window writes back what it read.
- **`SchedulerIntent.AddCalendarElements`** (new) lays every PANEL element (task panels, periods, reminder
  tags) as **one calendar history unit**, folding them through `resolveScreenOverrides` one at a time so each
  resolves against the calendar the ones before it already changed — a period and the panel inside it laid
  together leave what drawing them one after the other would — and carrying the id allocator so two new
  panels cannot share an id. **Alarms stay outside it**: they are rows of `SetAlarms`, a *Main* unit. So a
  Save costs **one Ctrl+Z per undo stack it touched, at most two**, and a ring never lands on the calendar’s
  stack where only a calendar-aimed Ctrl+Z could reach it.
- **The menu.** One element under the cursor still reads "edit ‹its name›" and opens its own editor, and the
  double-click funnel (`calendarBlockEditChoice` → `calendarEditChoices`) is untouched; **two or more** open
  the element window on all of them, in place of the chooser. The three rows that name something the calendar
  does not LAY — `task` (§13’s window), `sleep schedule` (§17’s rule), `timer` (a countdown whose remaining
  time is derived state) — are entries of their own beside "go to task tree", for its reason:
  `CALENDAR_SIDE_EDIT_LABELS`.
- `PeriodBound` moved out of `CalendarUi.kt` into `CalendarElements.Bound`, so the period editor and the
  element window read one enum. `CalendarAddWindow`/`CalendarAddChoice` are deleted, and with them the two
  now-unreachable "adding" halves of `ManualEntryEditWindow` and `ReminderEditWindow` (both are edit-only
  now, and always carry their bin).
- Tests: `CalendarElementsTest` (the grouping, the mixed rule, the bound intersection, the alarm arithmetic,
  and which drawn things are elements at all), `CalendarElementsSaveTest` (one unit per Save, the fold, the
  id allocator, the zero-duration tag, the dropped stale id).
- **Not verified on device**: `:shared:jvmTest` is green and `:shared:compileCommonMainKotlinMetadata`
  passes, but no build has been run. Client rebuild needed (`account{1,2,3}-*deploy*.bat`); no Supabase
  change.

### The id menu stops offering the cell its own task back — 2026-09-23

Account 3, a cell in Edit Mode at `root / planning / AI / most of the AI / writing`: the Tasks menu held "New
task" and one row, `root / planning / AI / most of the AI / writing (planning)` — the path the user was looking
straight at, which read as a *second*, dead task of the same name kept alive by the calendar. A read-only probe
of the release DB said otherwise: one task `task/user/330` titled "writing", alive, mirrored under two parents,
`(planning)` being its child titles. The row was the edited cell's **own** task, listed because the menu listed
every title match and only ever hid the in-progress "New task" draft.

The user's rule: *"the id suggestion list must appear only if another task than the current one has the same
title, or if the current task exists in the past of the timeline"*.

- `SchedulerDomain.changeTaskMenuEntries` now decides **whether there is a menu at all** and says so by
  returning an **empty list**; `EditModeMenus` renders whatever it gets and no longer reads a row count
  (`entries.size > 1` could not tell a lone "New task" that must show from one that must not).
- No rival ⇒ the cell's own task is not a row. With a rival it still is, and still renders selected (purple).
- No rival, but the cell's task has a past — `taskHasTimelineHistory`: a `Task.record`, or a panel
  `isUserPlaced` — ⇒ the menu is the lone **"New task"** row, nothing highlighted. No past ⇒ no menu.
- Choosing "New task" then brings the abandoned id back as an ordinary row, by no rule of its own: the cell
  holds the draft, so the previous task is a rival again, and `purgeOrphanTasks` keeps it alive on the very
  past that opened the menu.
- **"The current one" is read against the session's `treeBefore`**: only the task the cell *arrived with and
  still holds* is hidden. An id the session passes through is a choice and stays visible — the row that says a
  typed title was reused (PRD §4 *Creation*, the only route to a second task of an existing title), the row a
  pick keeps to go purple, and the previous id after "New task".
- `taskHasTimelineHistory` takes no `nowMillis` on purpose: it sits on the per-keystroke edit path. So auto
  panels (the schedule's own future, which nearly every scheduled leaf has — the menu would always show) do
  not count, and a user-placed block ahead of the now-line does.
- **A row the tree does not hold now says so**: `DEAD_TASK_ROW_PREFIX`, `[dead] ` before the child titles it
  was already named by. The absence of a path was the whole statement before, and it is not one anybody
  reads — `planning` beside `root / planning` looks like a shorter path, not like a task that is gone. Same
  rows whose "go to task" is greyed; they still sort last, and assigning one still brings its sub-tree back.

PRD §4 *Appearance* / *Coming back*, `docs/invariants/task-tree.md`. Three new `SchedulerReducerTest` cases;
the seven that asserted a "collapsed" menu now assert an empty one. Client rebuild only.

### A re-plan nobody waits for any more stops where it stands — 2026-09-22

The user's rule: *"if the scheduler was already running, then it stops abruptly and runs again with the new
data"*. It did not: `progressivePlan?.cancel()` cancelled the coroutine, but a fill is straight-line CPU, so the
superseded fill ran to the end, lost the compare-and-set, and burned a core on an answer about data nobody held —
one per burst of edits.

- Every engine re-plan now carries a **generation**; `SchedulerEngine.abandonRunningPlan` bumps it at the
  signature edge (ahead of the 1 s debounce) and on every new dispatch.
- The fill asks `SearchBudget.checkAbandoned` at the checkpoints it already had — each `ScheduleFill.run` entry,
  the rollout and the search every 64 steps, the improver every 64 moves — and unwinds with `PlanAbandoned`.
- The reducer catches it and hands back the same state instance, so the ViewModel publishes, saves and retries
  nothing. Generation 0 (the in-reducer re-plans answering a press) is never abandoned.

`PlanAbandonedTest`. Nothing about what a completed fill produces changed.

### A superseded re-plan now stops where it stands — 2026-09-23

The user's rule: *"the scheduler must be triggered each time a relevant change happens, with a debounce or not
… if the scheduler was already running, then it stops abruptly and runs again with the new data"*. The first
half held; the second did not, at all.

- `dispatchProgressivePlan` cancelled the previous job, but a fill is straight-line CPU with **no cancellation
  check anywhere in `scheduler/domain/`** — so the cancel only took effect at the next suspension point,
  *after* the fill had run to the end (1.6 s on the release account), published its answer about data nobody
  held any more, and, having lost the compare-and-set to the keystroke that superseded it, re-run the whole
  fill against the new state. A typed title could leave several of those grinding at once.
- Now every plan intent carries the **generation** it was asked under, the engine answers whether that
  generation is still current, and the fill asks at the checkpoints it already had: the rollout loop, the
  improver's moves, the exhaustive search's clock check, and every entry to `ScheduleFill.run` — plus once
  before the advance, so a fill already stale when it reaches the CPU does no work at all. An abandoned fill
  unwinds and the reducer returns **the same state instance**, so nothing partial is published, nothing is
  saved, and `dispatch` does not retry.
- **Generation 0 is never abandoned** — the in-reducer re-plans that answer a press must be in the state
  before the press returns — and the reducer holds that rule rather than the seam.
- The rules moving abandons **at the edge**: `launchRuleChangeReschedule` stops the running fill the instant
  `schedulingSignature` moves, and only then waits out the 1 s debounce before asking for the new one.

`PlanAbandonedTest` pins all of it, including that an abandoned fill costs a small fraction of a whole one.
1800 tests green. Client rebuild to take effect; no Supabase change.

### A rename is not a re-plan, and the calendar shows it at the keystroke — 2026-09-22

The user's rule: *"the scheduler must run each time the data for the schedule changes … except if it is in
rename mode, which only renames the task panels in the schedule"*, and *"each time a title is renamed with a
new keystroke, the titles in the calendar must update at the same time"*. Neither held.

- `schedulingSignature` **hashed every task's title**, so every letter of a rename was a rule change and the
  account re-planned (debounced) for a plan that could not come out different. What the plan really reads off
  a title is two things, and both stay: whether it is BLANK (a blank title deletes, so the task leaves the
  schedulable set) and the ORDER the titles put the tasks in (`docs/scheduler_score.md` § *Ties*: "higher
  priority first, then title"). Typing "meeting notes for the week" over an existing name now moves the
  signature **2 times instead of 26** — the two are real, the letters that carried the task past another
  title. `RenameIsNotARuleTest`.
- And because the fill is then not what rewrites the panels, **the rename does**: `applySetCellTitle` renames
  the task's panels, so every block already on the timeline carries the new name on the frame the letter lands
  in, instead of keeping the old one until some unrelated edit re-planned. (Green record blocks already read
  the live task title; it was the auto/pinned panels that went stale.)
- The display then re-labels instead of re-deriving: a change that moves nothing but task titles re-labels the
  held reading's records from the tasks (`CalendarDisplayCache`), pinned against deriving again by
  `CalendarRelabelEquivalenceTest`.

Measured on the same account (231 tasks, 967 panels; a task carrying 45 panels renamed): a rename keystroke
**~95 ms → 16 ms**, and the inert state change from the entry below **79 ms → 14 ms**. A change-task keystroke
— where a draft task really is created and the tree changes — is 47 ms, and what is left there is the
derivation the new task genuinely needs plus the tree rows that genuinely changed.

Nothing about what the scheduler considers changed: every task still reaches it, and the fill, the score and
the search are untouched.

### Typing in a cell no longer re-derives and re-draws the whole app — 2026-09-21

Anomaly: letters typed into a task cell appeared late and in blocks. Measured on a **copy** of the release
account (231 tasks, 967 panels, 2856 history units), by composing the real `App` into a headless scene and
timing each frame: an idle frame cost **5 ms**, one keystroke **95 ms** — and *any* state change at all cost
**79 ms**, including one that drew nothing (an appended diagnostics row). At ~8 letters a second the frame
budget was gone twice over, so the letters queued.

Four causes, each measured and fixed separately:

- **The calendar's whole derivation ran on every recomposition of `App`'s body**, i.e. for every state change
  there is — an engine tick, a sync, a log row, a moved selection. It is now held on the values it actually
  reads (`CalendarDisplayMemo`, two slots because the calendar reads it at the line and one millisecond
  later), so a change that touches none of them costs nothing and the calendar's own subtree stops
  recomposing with it.
- **The heavy halves that do not read a task's TITLE are held separately** — the recurrence bars' environment,
  the screen-break placement past and future, the reminder regeneration, the derived pauses. A rename rewrites
  `tasks`, so the whole-derivation memo misses on every letter, but none of those four is a function of a
  title (`planTasksOf` carries priority, minimum and resilience, and no title).
- **Every visible task row re-composed on every state change** — ~44 of them, ~20 ms a frame — because
  arguments Compose compares by instance were rebuilt on each pass: the row's contextual-menu holder, and
  three callbacks that closed over the whole `SchedulerState`, the visible order or a `Cell`. The state is now
  read through `rememberUpdatedState` holders and the menu is `remember`ed on what it offers, so rows skip:
  ~44 recompositions per change became ~6.
- **`CellListSection` re-measured every cell's title text on every pass** (`cellTextPx`), and
  **`commitEditText` applied each keystroke twice** (once for the state, once to build the same delta).

Result on the same account: an inert state change **79 ms → ~19 ms**, a keystroke **95 ms → ~60 ms**, idle
frames unchanged at ~5 ms. Still outstanding: a keystroke's remaining cost is the title-dependent half of the
derivation (`display.baseCalendarRecords`, ~10 ms) plus the composition of the rows that genuinely changed.

New perf counters for this: `compose.TaskSchedulerScreen`, `compose.TaskTreeView`, `compose.TaskRow`,
`compose.DefaultSubtreeWindow` and `recompose.TaskRow` / `recompose.TaskTreeView` /
`recompose.TaskSchedulerScreen` — `recompose.TaskRow` divided by `recompose.App` is the row-skipping number
above, and the one to watch. No deploy needed for the measurement; the fix needs a client rebuild.

### The §4 window can edit a live task's sub-tree again — 2026-09-21

Anomaly: in the Default sub-tree window, a row pointed at a task the tree already holds could not have its
sub-tree changed. Reproduced: typing under such a row left the row empty, the tree unchanged, and a cell id
burned. Silent.

- Cause: `withDefaultSubtreeCapturedFrom` stopped its walk at a task the live tree owns and then discarded
  the projection's whole live half, so the edit — which the reducer really had applied — had nowhere to land.
- That discard was load-bearing, and for a sharper reason than the note it carried. The projection re-roots
  at the template, so the live tree's own top level is **unreachable** inside it and `pruneDetachedTree`
  deletes the entire account tree in there. Writing the live half back wholesale would have written that
  deletion to disk.
- Fix: the fold's walk **splits** instead of stopping. What the template owns is captured into
  `defaultSubtree` as before; a live-owned task and everything below it is written **back to the live tree**,
  and nothing the walk did not reach is read at all — so the wreckage is still never looked at. A task minted
  under such a row belongs to the live tree: which side of the walk reached it decides.
- One gesture is still one unit, so `DefaultSubtreeDelta` gained a `live: TreeDiff` half and the persisted
  unit two optional fields. Absent = template-only, which is every unit already on disk; covered by a
  round-trip test and a stripped-payload test per the persisted-DB rule.
- Two consequences, both now pinned by tests: **renaming** such a row renames the task (it was a silent
  no-op, and the docs called it an open question), and **emptying** one still only unbinds the cell — a blank
  title is what deletes, so that one must never reach the account's task.
- `docs/invariants/task-tree.md`.
- **Client rebuild only** (`account3-deploy-windows.bat`) — no Supabase change.

### A bound template row the list already holds is skipped, not cloned — 2026-09-21

A bound (switch) template row carries a task id, and Constraint 1 forbids the same task twice in one list —
so such a row can never be mirrored into a sub-list that already holds its task. It used to take the general
fallback and **mint a fresh task with the row's title**, leaving two same-titled rows over one real task.

- It now **skips**: there is nothing to add. Asked as `templateTaskId in siblingTaskIds(working, target)`,
  not as the whole of `canAssignTaskId`, because the other refusals mean the opposite — a task only the
  template knows, one deleted or one belonging to another tree is *absent* from this list, so the row must
  still mint or it would vanish silently. Constraint 2 (the bound task's sub-tree holds one of the cell's
  ancestors) stays a mint for the same reason, and has a test of its own now.
- Found while answering "the same task id can't appear in the same sub-list, right?" — it could not, and
  never could; what was wrong was the shape of the refusal. `docs/invariants/task-tree.md`.
- Noted in passing: the release template has **no** bound rows, so nothing in account 3 takes either path
  today.
- **Client rebuild only** (`account3-deploy-windows.bat`) — no Supabase change.

### "add default sub-tree" now lands on the cell it was opened on — 2026-09-21

Anomaly, reported straight after the fix below: **add default sub-tree** on
`why / how to measure improvement` put the rows under that cell's child `planning`, two levels from the
right-click.

- Not a regression — `defaultSubtreeApplicationTargets` walked down to the sub-tree's **leaves** by design,
  on the reading that a template describes how a piece of work breaks down, so asking for it on a cell that
  is already broken down asks for it on the pieces. Confirmed by probing the release DB: the three template
  rows sat under `… / how to measure improvement / planning`, the only titled child of the clicked cell.
- The user's call: the entry acts on **what was clicked**, like every other §13 entry. The walk is gone —
  the template lands in the clicked cell's own sub-list, beside the children it already had, and to seed a
  piece you right-click the piece.
- What that deletes with it: `DefaultSubtreeTargets`, the "targets read off the state before anything is
  written" cascade guard (there is no traversal left to re-enter what it wrote) and the leaves/branches
  tests. What survives is the fill-once-by-id guard, which now bites when two *selected* cells point at one
  task, and the empty-cell skip. `docs/invariants/task-tree.md`.
- **Client rebuild only** (`account3-deploy-windows.bat`) — no Supabase change.

### "add default sub-tree" did nothing on a row whose only child had been emptied — 2026-09-21

Anomaly: in the Default sub-tree window (account 3), right-clicking `why / how to measure improvement /
planning` and choosing **add default sub-tree** left the (already expanded) row exactly as it was.

- A probe of the release DB found the shape: that row's sub-list held **one** cell, pointing at a
  blank-titled task with no `childListId` at all — a row typed once and then **emptied**. Emptying keeps the
  cell on its now blank-titled task, and `applySetCellTitle` drops the list's real trailing placeholder
  (the inverse of Auto-Expansion), so the emptied cell *becomes* the placeholder.
- Four places asked "is this row populated?" as `cell.taskId != null` — the very shorthand
  `isTitledDefaultSubtreeRow` exists to replace. Two of them made the no-op, and they compounded:
  `defaultSubtreeApplicationTargets` (then still a walk) called the row a **branch** and took the blank cell
  for the leaf, whose task has no sub-list to fill; and had the walk got the right leaf,
  `applyDefaultSubtreeTemplate` looks for the trailing row to type into with the same test and would have
  found **none**, returning before writing a thing. Either one alone is a silent no-op. (The walk itself was
  replaced hours later — see the entry above — which leaves the second one carrying this fix.)
- The other two are the same question asked by the automatic graft — `graftDefaultSubtree`'s "the user
  already built this sub-list, so there is nothing to add" and `materializeDefaultSubtree`'s drop of an
  unpaid promise. An emptied row is not something built, so both were refusing the template over a blank.
- Fix: all four now ask `SchedulerDomain.isTextuallyEmptyCell`, which resolves the title through the tasks
  exactly as `isTitledDefaultSubtreeRow` does for the template's own rows. Replayed against a copy of the
  release DB, the gesture now lands the template's three rows under `planning`.
  `docs/invariants/task-tree.md`.
- **Client rebuild only** (`account3-deploy-windows.bat`) — no Supabase change.

### A window behind another could not be clicked — 2026-09-21

Anomaly: in the account-3 Windows app, pressing the **Alarms** window where it stood behind the
**Reminders** window did nothing — it neither came forward nor took the focus.

- Not the stacking order and not `WindowFrameHost.focus`: the press never reached the Alarms window at all.
- `AppWindowFrame` composed its `Box` as `modifier` *then* its own geometry —
  `windowStackZ → unplaced → offset → requiredWidth/Height`. `Modifier.offset` reports its child's size **at
  its own position** and merely places the child elsewhere, so everything the caller hung on `modifier` kept
  the bounds the window would have had **undragged**: a rectangle of the window's size at the centre of the
  content area, carrying that window's z and drawing nothing.
- The Reminders window is the one window that hangs a pointer handler there — PRD §14's "a press on bare
  chrome leaves Edit mode" `detectTapGestures`. Compose stops hit-testing lower siblings as soon as one
  records a hit, so that ghost swallowed every press landing in the centre of the app, the visible parts of
  the Alarms window included.
- Fix: the frame now applies the caller's `modifier` **inside** the offset and the size, immediately before
  its own `raiseOnPress`. `align` is parent data and is read from anywhere in the chain, so no caller loses
  anything; every caller passes only `align`, a `FocusRequester`/`focusable` or a key handler, and those now
  answer for the window's real rectangle too. `docs/invariants/popups.md`.
- **Client rebuild only** (`account3-deploy-windows.bat`) — no Supabase change.

### The default sub-tree is OWED, not written — 2026-09-21

Anomaly: in the Default sub-tree window (account 3), the `planning / AI` row's id menu listed
`Default sub-tree / planning / AI (how to measure improvement, planning, why)` **and**
`Default sub-tree / planning / AI / planning / AI` — a second task of that title the user never wrote.

- A read-only probe of `~/.omniapp-release/scheduler-state.db` found the template holding **41 tasks** where
  the user had typed about four, nested `planning / AI / planning / AI / …` five levels deep, with
  `task/user/393` and `task/user/395` both titled `AI`. The second menu row was real, its path was right, and
  the tree it named was the corrupt one.
- The cause: the template window dispatches against `projectDefaultSubtree()`, where the template **is** the
  tree. A row typed there is a new task id, so `endEditSession` grafted the whole template under it — and
  that copy became part of the template the *next* row pulled in. Not the cascade inside one graft (calling
  the primitives directly has always ruled that out) but a fractal **across gestures**, doubling with every
  row typed, and grafted whole under every task created in the real tree.
- **The rule is now the one the user asked for, and it is universal**: the template appears under every new
  `taskId` while the switch is on — the task tree, "All tasks" and the template's own window alike, the rows
  the application itself writes included. That has no bottom, so **nothing is written at creation any more**.
  A new task records a **promise** (`Task.pendingDefaultSubtree`: the template lists whose rows it owes), and
  `materializeDefaultSubtree` pays it the first time a gesture **opens** the cell — the expand arrow, `Tab`
  into the child. Each row written owes its own next round: its template row's child list, then the
  template's root. One gesture writes one round; the account stores what has been looked at.
- Three consequences worth knowing:
  - **a task nobody has opened is still a leaf**, so it is still schedulable — a tree whose every task were
    born a parent would have no leaves at all, and the scheduler places leaves;
  - the **switch is read when the rows appear**, not when they were promised (PRD §7's "currently applied"),
    and a sub-list the user has built drops the promise unpaid;
  - **opening is two Main units** — the rows, then the toggle — because `ToggleExpandDelta` undoes by
    expanding again and so can carry no tree mutation.
- §13's **"add default sub-tree"** still writes its round immediately: that one is the asking.
- Persisted-DB compatibility: `pendingDefaultSubtree` is a new field on the task (authoritative, synced by
  row like the rest of it). Absent — every payload written before it — decodes to "owes nothing", which is
  right for tasks whose rows the eager graft already wrote. Both are tested.
- Why no test caught the fractal: `DefaultSubtreeTest.withTemplate` builds its rows with the policy switch
  still off and only turns it on at the end. The window's own gestures are now covered with it **on**.
- **The 41 tasks are not healed on load**: which of those rows are the user's and which are eager copies is
  not a question the data answers, so the template has to be pruned by hand in its window. Nothing prunes it
  automatically, and nothing will write rows like them again.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`), no Supabase deploy.

### The first letters are kept for real: the edit FIELD is composed off the draft — 2026-09-21

Anomaly: the same one, still reported after 2026-09-20's two fixes. Those put the letters in the state
correctly; the field the user is looking at simply never asked for them.

- `BasicTextField` keeps an internal buffer seeded from the `value` it was **composed** with, and re-seeds it
  only when a later composition hands it different text. `TaskRow` composed the field with an **empty**
  `TextFieldValue` and filled it in from a `SideEffect` — which can only publish a frame later, and the field
  is focusable in that frame (the effect requesting its caret runs on the same one). So the first key to
  reach the focused field was applied to an empty buffer and `onValueChange` reported *just that key*: the
  letter that opened the session, plus every letter `reduceBeginEdit` had absorbed after it, were overwritten
  in one go. A whole burst, not one letter — and invisible to the reducer, which had them all along.
- The field now owns its **caret and nothing else**. `displayTitle` *is* `editSession.draftText` for the row
  being edited, so the remembered `TextFieldValue` is seeded from the draft and a mismatch is repaired
  **during composition**, before the value reaches `BasicTextField` — never from a `SideEffect`. That also
  carries a keystroke the tree absorbed while the caret was in flight, which the field's buffer had not seen.
- Named residue: a letter absorbed in the gap between a composition being applied and that composition's
  focus request running is still one the field can type over. It costs one letter, not a burst, and closing
  it would mean a second `requestFocus` call site, which `TaskRow`'s one focus effect refuses.
- No test: the project has no Compose UI test harness, and the rule is a composition-order one that the
  reducer-level `EditModeKeystrokeRaceTest` cannot see. It is written down in `docs/invariants/task-tree.md`
  and at the call site instead.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`), no Supabase deploy.

### The first letters typed onto a cell are no longer lost on a slow frame — 2026-09-20

Anomaly: selecting a task cell and typing very often dropped the first letter(s) — "Plan" arriving as "lan"
or "n" — and it got worse the busier the app was.

- The cause is a race, not slowness itself. `onPreviewKeyEvent` reads the state of the last **composition**,
  and Compose delivers key events without recomposing between them: on a frame the app owes elsewhere the
  second and third letters of a burst are typed against a snapshot that still says "no session open", so each
  reaches the reducer as its own `BeginEdit` with its own `initialText` — and `reduceBeginEdit` started a
  fresh session for every one of them, throwing away everything typed before it. The burst kept only its last
  letter. Slowness only widened the window; the loss was unconditional once two keys landed in one frame.
- `reduceBeginEdit` now **absorbs** a keystroke offered to a session already live on that cell — reduced as
  the `UpdateEditText` it actually is, so the id menu, the committed title and the single coalesced Edit unit
  are answered in one place — and makes a text-less re-entry (a stale second Enter or double-click) a
  **no-op**, so a duplicate can no longer recapture `treeBefore` over a half-typed title.
- Second window, in the UI: between the session appearing and the cell's field taking the caret, a printable
  key fell through to the tree's own `Column` and was dropped. `TaskTreeView` now tracks whether that Column
  is itself the focused thing (`treeSelfFocused`, false the moment any child — the edit field included —
  takes over) and dispatches such a key as one more `BeginEdit` on the live session.
- `EditModeKeystrokeRaceTest` pins both: a burst typed entirely as `BeginEdit`s is the same state, histories
  included, as the same letters typed into the field.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`), no Supabase deploy.

### An emptied template sub-list is a placeholder, switch and all — 2026-09-20

Anomaly: in the Default sub-tree window, under `how to measure improvement / planning`, the user selected
every titled row and pressed Delete — and the sub-list came back holding one EMPTY row that still carried the
"new task" switch. Probed read-only against the release DB: the row is `cell/task/user/339/children/378`,
pointing at `task/user/343`, whose title is blank. It is the list's only cell.

- The cause is a rule of the tree, not of the template: emptying the cell directly ABOVE a list's trailing
  placeholder drops that placeholder (`applySetCellTitle`, the inverse of Auto-Expansion), so the last row
  emptied becomes the list's bottom cell — and an emptied cell goes on pointing at its now blank-titled task.
  The live tree has always left exactly that behind and nothing shows it, because every reader there asks
  `SchedulerDomain.isTextuallyEmptyCell`. The template window asked `cell.taskId != null` instead.
- `SchedulerState.isTitledDefaultSubtreeRow` is now the one answer to "does this row carry a switch?", read
  by the window that draws it, by `SetDefaultSubtreeCellBound` and by `settleDefaultSubtree`. It resolves the
  title through the live tasks, as `defaultSubtreeIsEmpty` must, because a bound row's title lives on the
  task it mirrors.
- Two more things the shorthand got wrong. The settle read such a list as "ending in a titled row" for ever —
  `ensureTrailingPlaceholder` correctly declines to add a second empty cell — so every reduction and every
  decode re-projected and re-folded the whole template, against a documented early-exit. And the rows drawn
  *under* a bound row are the LIVE tree's cells: they carried a switch whose flip wrote a `boundCells` entry
  keyed by a live cell id, which the next fold silently dropped.

No migration: the state the delete leaves is what the live tree leaves in the same case, and it now reads as
the placeholder it is. Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`), no Supabase deploy.

### A Change Task row that names a template task says so — 2026-09-20

Anomaly: in the Default sub-tree window, the id menu under `planning / write good prompt` offered
`main / planning / write good prompt (how to measure improvement, planning, why)` — but the account's own
tree has an EMPTY `planning` at its root. Probed read-only against the release DB (`app_state.payload`
decoded with `SchedulerStateCodec`): the row is real and correct — `task/user/359` is one of the template's
own 59 tasks, named, as PRD §4 says, from the tree it is drawn in. What was wrong is the name.

- The template's `TreeSnapshot` carries the root task too, and `SchedulerDomain.withRoot` — the ONE
  definition of the root shape — only ever sees the LIVE tree. So the pre-1.6.0 title `main` survived there
  (the codec's id migration cannot reach it: a title is not an id), and every stored task tree's did too.
  `SchedulerDomain.withRootTask(TreeSnapshot)` heals the task alone — a snapshot must not grow a root CELL —
  and the codec runs it over `defaultSubtree.tree` and every `taskTrees[].tree` on decode.
- Healed, the label would have read `root / planning / write good prompt`: still an account path, for a row
  the account has nowhere. So `taskPathLabel` names the root of a `isDefaultSubtreeProjection` state
  `Default sub-tree` (`SchedulerDomain.DEFAULT_SUBTREE_ROOT_LABEL`) instead of the root task's own title. A
  live task keeps being named from the account's tree, as it already was (`namingSource`).

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`), no Supabase deploy. The heal runs on load,
so the stale `main` leaves the DB at the next save.

### A press in a companion window brings its pair forward — 2026-09-20

Anomaly: on some part of a window, a press did not change the focus between windows. The part was a
**companion** window — the History window's row-info window, opened by a double click on a row. A pair is
drawn in one wrapper `Box` and `zIndex` only orders a node among its own siblings, so the companion's own z
never left that `Box` and the wrapper carried the FIRST window's z alone: pressing the info window raised
`HistoryEntryInfo` in `WindowFrameHost.stackOrder`, which nothing on screen was drawn with, and the pair
stayed under whatever the user had moved to. Pressing the History half worked, which is what made it read as
"some part of the window".

- `WindowFrameHost.zOf(id, companion)` + `Modifier.windowStackZ(id, companion)`: a pair stands where its
  **topmost** half does. The companion is named only while it is open (a closed one is not in the stack, and
  an id that is not in the stack reads as the top).
- `HistoryEntryInfoWindow` now takes an `onRaise` and forwards it, as `TaskTreeDetailWindow` already did —
  so the press is stamped as the History window (PRD §6) — then takes the focus back, keeping the keyboard
  it claims. The task-trees list/detail pair reads the pair z too, and its detail id has one spelling
  (`taskTreeDetailWindowId`).
- Found beside it: the reduce bar's chip restored and **raised** a window without focusing it, so a window
  that answers keystrokes came back from the bar with the tree still holding the keyboard. It goes through
  `WindowFrameHost.present` now, like every other way of asking for an open window.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`), no Supabase deploy.

### An alarm, a timer and a reminder each choose their own channels and their own sound — 2026-09-20

The user's spec: *"For any alarm, timer or reminder, the user can define if there is a sound alarm, a voice, a
notification, a vibration for the phones… The sound alarm can be selected among a small available set of
sounds."*

One new type, `AlertSettings` (sound + which sound + voice + notification + vibrate), on `AlarmEntry`,
`TimerEntry` and `ChoreEntry` alike — it replaces each row's lone `vibrate` flag, and `ArmedAlarm` carries the
whole block into the phone's OS intent so what rings is what was armed. The four channels are **independent**
and **narrow** the account's own switches (PRD §11) rather than competing with them: `notifyUser` gained an
`alert` argument and asks each half separately, and the ring seam is not called at all for a row that neither
sounds nor buzzes. An alarm with every channel off is not `schedulable` — a silenced row, like one with no days.

The **small set of sounds** is `AlertSound` — Guitar (the original arpeggio, still the default), Chime, Bell,
Marimba, Beeps — all synthesized in `AlarmTone` for the reason the guitar always was: the choice is account data
that must sound identical on every device, offline, with nothing to load. The three new struck sounds share one
additive renderer (inharmonic partials over an exponential decay); the beeps are a gated tone. The ring seam is
`ringAlarmPlatform(label, soundSeconds, sound, vibrate)`, with a **null sound** meaning "vibration only".

**Reminders now announce themselves**, which they never did: the ordered cue sweep gained
`CueKind.ReminderDue`, whose occurrences are the **tags the calendar draws** (`reminderCueOccurrencesBetween`
over `state.panels`, checked ones excluded) rather than a second reading of the recurrence arithmetic, de-duped
on the tag's stable id (a reminder with no time of day is re-placed at "now" on every regeneration, so an
instant key would re-announce it). The sweep self-delays to the next tag, the alarms' 60 s real-age budget
decides a slept-through one, and a reminder is **not** OS-armed — the one alarm slot belongs to the alarms and
the timers. A reminder defaults to `AlertSettings.REMINDER`: said and posted, neither rung nor buzzed.

One editor for all three rows (`ui/AlertSettingsEditor.kt`), shown in both Alarms sections and in the reminders
manager. The History Unit details name every channel that moved (`alertChanges`). Decode heals a payload
written before any of it: an alarm/timer keeps its stored vibration and rings as it did, a reminder takes the
reminder default, and a sound name this build does not have falls back to the default rather than to silence.
`AlertSettingsTest`. Client rebuild (desktop + Android); no Supabase deploy.

### The frozen past is replayed over Θ, not over a week of wall time — 2026-09-19

`fillSchedule` read the already-placed past from a flat `now - 168 h`, and `ScheduleFill.replay` starts every lag
at zero there. A lag forgets over its own window `τ_i = max(M_i, 1min)/π_i`, which grows without bound as a task
gets rarer, so every task with a share under about `M_i/168h` (0.3 % for a 30-minute minimum — a task that runs
once a fortnight) began each re-plan from a lag of zero. A three-day pre-placed block ending nine days ago was
invisible: the task read as starved by twelve minutes instead of over-served by thirty-three hours, and was laid
at the now-line instead of not for weeks. Reported by the user for a task due once every twenty days coming late.

The span is now `4 × Θ` of SCHEDULABLE time (`ScheduleFill.pastLookbackMillis`), floored at the old 168 h — which
is what every ordinary account still gets, its `Θ` being hours — and capped at 90 days, the backward counterpart
of the forward 168 h limit and the one approximation this side still carries. `ScoreModel.sharesOf`/`windowsOf`/
`thetaOf` are the one home of the window formula the model and the lookback both read. The compensation
coefficients now stop walking `depr` at `COMP_REACH_LENGTHS`, which a months-long window would otherwise have made
O(pieces²) (`fillSchedule 168h` 103.5 → 94.7 ms in `PerfBenchmarkTest`). `SchedulePastLookbackTest`;
`docs/scheduler_score.md` § *How far back the frozen past is read*. Client rebuild.

### A sleep window's bubble names its "No screen" companion — 2026-09-19

Hovering a sleep window named only "Sleep". Its "No screen" line depended on a `noScreenRegions` parameter of
`mergePanelsForDisplay` that no caller named. `displayInactivityGaps` landed in it by position, so the line
showed only when an inactivity gap covered the band's midpoint, and then with the gap's span. That never happens
for a future night. The line now comes from the kind's companions (`companionBubbleSections`), the same
`PeriodKindConfig` the drawings read, over the period's own span, for every period box too (the wind-down hour
names its "No screen" as well). The `noScreenRange` field is deleted. `CalendarBubbleSectionTest` covers it.

### The away cover is zero wide, `[now, now]`, not one millisecond — 2026-09-19

In modes 2 and 3 the fill covered the line with a "no screen" period `[now, now + 1)`. The search decides at every
window edge, so the resilient task got one millisecond and an on-screen task got the rest. Time passing never
re-plans, so the away line (and the mode-2 sweep after a device sleep) then walked over on-screen work that the
display clips and the bank refuses. That left idle a stretch where off-screen tasks could have run (§ *No idling*).
User fix: the cover is a zero-width, closed-end period at the line. `ScheduleFill.firstAmong` reads it as a rule on
the first free run only (`ScheduleOptimizer.firstOptions`, the same funnel as §7/§13's first-run rules): that run
must be a task resilient to "no screen" when one exists, and it keeps its full length. With no resilient task, the
README's "no task" now has zero length, so the plan is no longer cut at the line. `AwayCoverFirstRunTest` is new;
`TpModeTest.both_away_modes_cover_the_line_with_no_on_screen_task` now asserts this.

### The compensation fades over 4 hours, not over the task's own window — 2026-09-19

`docs/scheduler_requirements.md` now states that the compensation decays on both sides of a blockage and saturates
(a 48-hour pre-placed task buys more presence than a 24-hour one, but not twice as much). The score already had that
shape, but faded it over `τ_i = M_i/π_i`, which both capped it far too low (a whole day of deprivation bought a 50%
task with a 30-min minimum ~7.5 min in the next 4 hours) and made it depend on the minimum time. User decision: one
constant fade length `λ` = 4 h of schedulable time (`ScoreModel.COMPENSATION_LENGTH_MILLIS`), so a long blockage now
buys up to `π(1−π)·λ` (1 h for a 50% task) of extra presence on each side. The lag's own forgetting stays on `τ_i`.
`ScheduleScoreTest.a_longer_pre_placed_task_saturates_the_compensation_on_both_sides` pins the requirement's example
on the planned schedule; `compensation_decays_with_distance_and_is_bounded` and
`SchedulerFillTest.a_block_committed_ahead_swells_the_other_task_around_it` were moved to the 4-hour scale.

### "no screen" means only "refuses the tasks at 0 to it" — 2026-09-19

User rule: *"'no screen' period only means it forbids tasks that have 0 resilience with 'no screen' period. It
doesn't mean no computer unlocked anymore."* The converse is kept on purpose: where both layers fall, the stretch
still counts as no-screen time (plan, bars, record bank). The calendar's "edit…" chooser no longer folds a
"no computer unlocked" + "no phone unlocked" overlap into a "no screen" row that edited both at once
(`periodEditChoices`, `CalendarEditChoicesTest.noScreenRowIsTheNoScreenPeriodAlone`); every period is its own row.

### Period companions and drawings, set in the period edit window — 2026-09-18

→ `docs/invariants/calendar.md` § *Period companions and drawings*, `docs/invariants/scheduler.md`, PRD §8/§13/§17.

User rule: *"In the period edit window, the user can define a set of periods that are always present when this
period is present. By default, the inactivity period is not accompanied by the 'no screen' period, and the 'no
screen' period is not accompanied by the periods 'no computer unlocked' or 'no phone unlocked'. The user can also
define the drawing of each period among a predefined set of drawings that are very distinguishable from one
another, even when they overlap."*

- **Companions replace the hard-coded implication.** `PeriodKinds.impliedKind` (before bed / sleep ⇒ no screen)
  and `PeriodKinds.assertedLayers` (no screen ⇒ both layers) are gone; `PeriodKindConfig` (transitive, off
  `SchedulerState.periodKindStyles`) answers both, and `SchedulerDomain.companionPeriods` replaces
  `impliedNoScreenPeriods` as the one funnel to the scheduler (it still adds "both layers ⇒ no screen", which is
  the layers' definition, not a setting). `restrictivePeriodsOf`, `dynamicPeriodBase`, `assertedLayerRanges`,
  `assertedNoScreenRanges`, `retractedAtLineSpans`/`retractAtLine` and `clipPlanForRetractedPeriod` take the config.
  New `SchedulerDomain.projectedSleepPeriods` for the calendar's projected §17 windows, which were mapped by hand
  and so dropped the sleep window's no-screen companion.
- **Behaviour change by default: "no screen" no longer asserts the two layers.** A drawn "no screen" period, a
  sleep window and a wind-down hour no longer hatch `/` and `\`; each wears its own drawing. They still count as
  no-screen time for the plan, the bars, the mode-1 retraction and the record bank (by kind). The future sleep
  windows and the screen breaks likewise hatch a layer only if their kind carries it (by default, neither).
- **Drawings**: `PeriodDrawing` (8 patterns), rendered only by `ui/PeriodDrawings.kt` as a repeated tile (constant
  cost at any box height). `Modifier.obliqueHatch`/`verticalHatch` deleted. Defaults: `|` inactivity, `—` sleep,
  `/` no computer unlocked, `\` no phone unlocked, `(` no screen, zig-zags before bed; a new account kind is given
  the least-worn drawing at creation and stores it.
- **Persistence/sync**: new `periodKindStyles` field (overrides only, one row per kind via `EntityRows`, no
  Supabase change). A payload without it decodes to every default; decode heals styles for unknown kinds, unknown
  or self companions and unknown drawings. `RemovePeriodKind` drops the kind's style and every reference to it.
  Intents `SetPeriodCompanions` / `SetPeriodDrawing` — account settings, no history unit. Companion changes are in
  `schedulingSignature`; drawing changes are not. Tests: `PeriodCompanionsAndDrawingsTest`, plus the updated
  `LayerPeriodKindTest`, `BeforeBedPeriodTest`, `SleepWindowNoIdlingTest`.

### Requirements audit: the pace per stage, and a follower's rules checked on its own timeline — 2026-09-18

→ `docs/scheduler_requirements.md` § *Progressive Calculation*, § *No idling*, § *Restrictive Period*;
`docs/invariants/scheduler.md`.

- **Progressive stages are capped by the pace** (`progressiveStageCapMillis`, `ProgressivePaceTest`). Pure doubling
  held the "10 minutes every 10 seconds" pace only on average; the long stages (32 h, 64 h) arrived more than 10 s
  after the one before on any device filling an hour in more than ~0.1 s. Each stage after the first now reaches
  no further past the definitive front than the device fills in 7 s at its measured rate (never less than 10 min).
- **A follower lays the leader's runs only when they are legal on its own timeline**
  (`ScheduleOptimizer.isLegalContinuation`, `SchedulerPeerProtocolTest`). A period only the leader had left a hole
  the follower left to nobody, and a period only the follower had could hold a task its resilience of `0`
  forbids there. The follower now plans for itself then, with the leader's runs as a seed.
- **The calculation time limit is documented as a deviation**, not as the requirement's: the requirement stops
  only at $t_{goal}$.

### $t_{goal}$ gains the current week, and a calculation time limit that actually stops — 2026-09-18

→ `docs/scheduler_requirements.md` § *Progressive Calculation*, `docs/invariants/scheduler.md`, PRD §9.

The user's statement of the stopping rule: *"The scheduler stops when the schedule is definitive up to the end
of the current week, the last displayed time in the calendar, and 10 minutes after now, or if the set of rules
is too heavy or if the calculation time limit for on task tree change is reached."* Three of those five clauses
were already in force. Two were not:

- **The end of the current week is now a term of $t_{goal}$** (`SchedulerDomain.currentWeekEndMillis`, read by
  `scheduleGoalEndMillis` / `scheduleHorizonEndMillis` / `horizonRefillDueMillis`), and it holds **with the
  calendar closed**. Under the 2026-09-16 rule the goal was the calendar's end alone, so a rule change on a
  headless device planned twenty minutes and opening the calendar on the current week was what made that week
  get planned. The goal now steps forward once per rollover instead of drifting with the line, which leaves the
  ten-minute floor governing only a week's last ten minutes — the regime `HorizonRefillRuleTest`'s anti-spin
  obligation is restated in. The week is a WALL-CLOCK week, so the goal is no longer a pure function of `now`
  and the scroll: every caller passes a zone (`App.kt` its own `tz`, `SchedulerEngine` its `tz` — which also
  makes the engine's goal deterministic in tests).
- **`PLAN_CALCULATION_LIMIT_MILLIS` (2 min of real time per progressive fill)**, and, the half that makes it a
  stop rather than a pause, `SchedulerEngine.extensionStoodDown`: the shortfall a stopped fill leaves is exactly
  what the rolling-horizon watcher exists to close, so without a latch the stages resumed one poll later with a
  fresh budget, for ever. The latch is keyed on the `schedulingSignature` it stopped under and on the goal it
  gave up on — the two things the rule names as asking the question again (a rule change; a goal grown past it).
  `stageSearchMillis` gives up the SEARCH before the reach, which is the requirement's own order of degradation.
  Two minutes because the limit must not be what stops a healthy account from reaching its week: nine doubling
  stages at the pace is ~90 s. `PlanCalculationLimitTest`.

**The cost the week term brings back is real and measured.** ADR 0009 records the 2026-09-04 weekly floor being
removed on 2026-09-16 partly because it *"made every rule change on a closed calendar plan a week — seconds of
work on a large account"*. That is back: `ServerQuotaTest`'s simulated month on a 224-task account went from
minutes to over twenty of them, purely in fills. It now runs its devices with `calculationLimitMillis = 0` (one
stage per fill) — a plan is derived state, stripped from the wire, so it cannot move a byte of the quota that
test measures. What answers the cost in production is the calculation limit itself, which the 2026-09-04 version
never had.

`SchedulerReducer.scheduleHorizonEndMillis`'s default is now the bare rolling floor, documented as the seam
UNSET rather than as the goal: the week term would otherwise have every fill in a test plan seven days in
whatever zone the machine is in. Every host installs the real provider at start. Client rebuild
(`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### A task panel past the definitive-schedule front is drawn with blurred edges — 2026-09-18

→ `docs/scheduler_requirements.md` § *Progressive Calculation*, `docs/invariants/calendar.md`.

The calendar drew every task panel identically, whether or not the scheduler had settled it. § *Progressive
Calculation* guarantees a schedule only below a front $t_1$ — *"for all the next set of rules the scheduler
will return until it is done, they will all indicate the same schedule rules for any t < $t_1$"* — and the
front is exactly how far a fill materializes into `state.panels`. Past it (a calendar scrolled beyond the
168 h ceiling) the blocks come from `App.kt`'s far-week fill: computed off the UI thread for display, never
retained, recomputed from scratch on the next visit. They were indistinguishable from a settled plan.

- `SchedulerDomain.definitiveScheduleFrontMillis` names that instant (it *is* `scheduleHorizonEndMillis` — the
  same front the derived inactivity bands already stopped at, now said once) and `isProvisionalPanel` decides
  which panels reach past it.
- `CalendarRecord.provisional` / `PlacedRecord.provisional` carry it to `CalendarBlockBody`, which draws such a
  block's **paint** — tint and outline both — through `Modifier.blur(2.dp, BlurredEdgeTreatment.Unbounded)`.
  The title stays crisp: which task is planned is the answer that holds, where it starts and ends is not.
  A definitive block is still the single un-layered `Box` it always was (display hot path, ADR 0009); on a host
  with no blur (Android < 12) it degrades to the plain block.
- Only the fill's own picks blur — a pinned or hand-drawn panel is § *Starting timeline* input, as fixed past
  the front as before it. A merged block asks every panel it fused, so a run straddling the front blurs whole.
- `ProvisionalPanelTest`. Client rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### A sleep window implies a no-screen period, and a mode-1 line retracts it — 2026-09-18

→ `docs/scheduler_requirements.md` § *$now line$ 3 modes* (mode 1) + § *No idling*, PRD §17 (both its
*Scheduler avoidance* and *Carved by activity* bullets amended), `docs/invariants/scheduler.md` § *A mode-1
line retracts the period that says nobody is at a screen*.

Two halves of one report from account 3 (00:43 on 2026-09-18, awake inside a 23:15→07:45 window):

1. **`PeriodKinds.impliedKind(sleep) = no screen`.** A §17 window asserted no calendar layer at all, so it
   carried no hatch and no no-screen period reached the scheduler or the record bank over it — while the
   wind-down hour leading into it always had. A night with no other evidence (a cold start across it: the
   process is down, so nothing notes the swept stretch and the OS lock scan has no span either) therefore
   stood as the one grey kind that says nothing about screens.
2. **A period covering the line that is or implies `no screen` gives up `[now, its end)` in mode 1**
   (`SchedulerDomain.retractedAtLineSpans` / `retractAtLine` / `retractsAtLine`, read once in
   `fillScheduleUninstrumented`). PRD §17's *"carved by activity"* rule shipped **display-only**, so a night
   worked through showed the Sleep band retracting to the now-line while the fill went on treating the whole
   window as an obstacle admitting nobody: the line sat in a stretch with no band and no task at all. Same
   shape as the dragged pose fixed 2026-09-05, same answer. `sleep` retracts wholly (no resilience can ever
   be written against it, so nothing else could satisfy the clause); `before bed` keeps its hour and lifts
   only its implied no-screen period, or the wind-down would be deleted outright; `inactivity` and the
   account's own kinds are outside the clause and never retract.

The plan is searched **and materialized** across the retracted span — it must name which task holds and until
when, and the fill runs at a rule change rather than on time passing, so the line needs panels to be swept
into between two fills. What is still ahead of the line is hidden by a display clip
(`SchedulerDomain.clipPlanForRetractedPeriod`, beside `clipPlanForPinnedScreenBreak` in `App.kt`), forward
only, so the band ahead stays whole and the swept stretch reads as *"task A from 00:40 to $now line$"*.

Tests: new `SleepWindowNoIdlingTest` (11). `BeforeBedPeriodTest` updated — the hatch over a wind-down hour and
the window it runs into is now ONE stretch per night. `PlanOffTheFrameLoopTest` updated: it compared a single
fill against the engine's two (the edit's own and the throttled one), which only matched while nothing in the
plan depended on where the line was. No deploy needed for the tests; the fix needs a **client rebuild**
(`account3-deploy-windows.bat`) — no Supabase change.

### A task is named the same way everywhere — 2026-09-18

→ `docs/invariants/task-tree.md` § *Task colours*, ADR 0013 § *Where a task colour is worn*. `shared`:
`SchedulerDomain.taskTitleLabel` (+ `UNTITLED_LABEL` / `ROOT_LABEL` moved there, `TaskRelationsDomain` and
`CategoryRules.scopeLabel` now delegate); new `ui/TaskTitleLabel.kt`; `EditMenuItem`/`EditMenuRow` take a
`taskColor`; `CalendarBubbleSection` carries its `taskId`; `taskSheetColors` threaded `App.kt` →
`CalendarUi` → `WeekView` → `DayColumn`; `PeriodKindEditWindow` and `PriorityChart` take a colour map. Tests:
`TaskTitleLabelTest` (new, 6). **Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase
deploy.** No persisted or synced state changed — colours are derived and were never on the wire.

Thirteen sites printed a task's title and each held its own copy of the rule: six spellings of `(untitled)`
(`UNTITLED_LABEL`, `.ifBlank {}`, `.ifEmpty {}`, `.orEmpty().ifBlank {}`), and nine of them dropped the task's
colour altogether — the hover bubble, the phone touch menu, the task picker's list and its id rows, the
Change Task menu, both ends of a task-relations pair, the resilience dialog and the pie legend. The tree and
the calendar had obeyed ADR 0013 from the start, so the rule read as "the tree and the calendar" rather than
as what it is: wherever the app names a task, it names it in that task's colour.

What made it a funnel rather than a config object is that the tint is a **background**. The foreground stays
free, so the task picker keeps saying in red that the now-line's periods forbid a task while the background
still says which task it is — the objection that killed an earlier attempt at this, and it was wrong. The one
genuine specificity left is which reading of the hue a surface needs (`sheet` on light, `accent` on the
bubble's `inverseSurface`), which is the split `TaskPalette` already had. The one exception is the pie
legend's swatch: rule 2 of ADR 0013 puts one sub-list's leaves in a contiguous arc, so slices keyed by task
colour would be a smear — the swatch stays a chart colour, the name beside it is tinted like every other name.

### Emptying a bound template row empties the ROW — 2026-09-17

→ `docs/invariants/task-tree.md` § *The default sub-tree*. `shared`: `SchedulerReducer.applySetCellTitle`
(the `keepAsTombstone` branch), `settleDefaultSubtree` (now heals a sub-list that lost its placeholder),
`mirrorsLiveTaskInDefaultSubtree` + `SchedulerState.isDefaultSubtreeProjection` (both new). Tests:
`DefaultSubtreeTest` (+2). **Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase
deploy.** No persisted shape changed; a template whose sub-list lost its trailing placeholder is healed on
load (and the healed template syncs like any template edit).

The user's report: selecting `planning / writing` in the Default sub-tree window and pressing Delete left the
row where it was and removed the trailing empty cell instead. Read off account 3's DB — history unit
Main/2982 records the gesture, and its only effect was dropping the placeholder. That row's switch was off, so
its title belongs to the LIVE task it mirrors: emptying it renamed that task to blank, `applySetCellTitle`'s
inverse-of-auto-expansion then dropped the placeholder beneath it (the emptied cell becomes the list's bottom
one), and `withDefaultSubtreeCapturedFrom` — which keeps the binding and discards the task — put the live
title straight back. Emptying such a row now unbinds the CELL, the branch a §8 tombstone already took. The
predicate cannot be read off the cell id (the two trees share `cell/root/0` on a fresh account, so every
Delete in the real tree hit it), hence the projection flag. Renaming a bound row is still a silent no-op —
the same evaporation, but what it *should* do is a question, not a bug with one answer.

### An id row is named from the tree the task lives in — 2026-09-17

→ `docs/invariants/task-tree.md` § *The default sub-tree*. `shared`: `SchedulerDomain.changeTaskMenuEntries`
(new `namingSource`), threaded through `TaskTreeView` / `CellListSection` / `EditModeMenus` and passed by
`DefaultSubtreeWindow` and `TaskListWindow`. Tests: `DefaultSubtreeTest` (+2). **Client only — an app rebuild
(`account{1,2,3}-*deploy*.bat`); no Supabase deploy.** No persisted or synced state changed.

The user's report: in the Default sub-tree window, the id menu under the `planning / writing` row did not show
`long term / socialize / english / writing`. Read off account 3's DB: it did offer that task — labelled
`main / planning / writing`, its place in the TEMPLATE. Both projections re-root the state, so
`shortestTaskTreePaths` never reaches the live tree from them: every live task came out pathless and was named
by its child titles or its bare title (the same menu, asked for "planning", returned **sixty-odd rows all
reading "planning"**), and one a template row pointed at was named by the template. The menu now takes the
state it NAMES from — the account's — while what it offers and filters stays about the tree on screen.

### A template row whose task vanished is removed like any empty cell — 2026-09-17

→ `docs/invariants/task-tree.md` § *The default sub-tree*. `shared`: `SchedulerReducer.settleDefaultSubtree` (new,
run after every reduction and in `SchedulerStateCodec`'s decode heal). Tests: `DefaultSubtreeTest` (+3). **Client
only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.** No persisted shape changed; a stored
template holding such a row is healed on load (and the healed template syncs like any template edit).

The user's report: *"in the sub list under 'planning', there are two empty task cells, and the first one has a switch
button."* Read off account 3's DB: the row pointed at `task/user/325`, a task that existed in neither the template
nor the live tree — a live "New task" draft (history unit 2892 is only the template's id counter catching up with the
live one) that a template row was pointed at, switch off (2893, 2895), and that the tree then dropped. The template ran
the tree's post-edit cleanup only at its own edit boundaries, and a live-side loss is none. Rule, per the user: every
template sub-list is titled cells ending in one empty cell, kept by the tree's own code.

### Everything the now-line drags moves as continuously as the line — 2026-09-17

→ ADR 0009 § *Everything that follows the line moves continuously* (new), `docs/invariants/display-hot-path.md`
(the resample rules, the continuity rule rewritten), `docs/invariants/calendar.md` (now-line). `shared`:
`ui/CalendarLineMotion.kt` (new: `withLineMotion`, `displayBoundsOf`, `advancedAlongLine`, `lineCompositionMillis`,
`Modifier.timelineSpan`), `CalendarRecord`/`PlacedRecord.startFollowsLine`/`endFollowsLine`, `recordsForDay`,
`WeekView` (`rememberFrameNowMillis` replaces `rememberNowLineHour`; the lock re-centres on the line clock),
`DayColumn` (records advanced along the line; every block slice, screen-break band, period box, sleep outline,
layer band and band label placed by `timelineSpan`), `SchedulerDomain.displayResampleDelayMillis` (`lineOffsets`:
meeting and midnight-crossing boundaries), `App.kt` (the calendar derivation is one local function,
`deriveCalendarDisplay`, read at the line and 1 ms later). Tests: `CalendarLineMotionTest` (new, 9),
`TimelineSpanRenderTest` (new, 5 — renders headlessly and measures the edges between pixels). Build: `shared` jvmTest
gains `compose.desktop.currentOs` (Skia natives; test classpath only). **Client only —
an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.** No persisted or synced shape changed (the two
new flags are display-only and never stored).

The user's report: *"the panels don't move as smoothly as the current now line, which makes situations where the now
line must drag a panel visually incorrect."* A mode-1 dragged pose `(t_p, t_p + d]` and the panels around it were
re-derived once per pixel while the line glided between pixels. Now the motion of every edge is read once from the
rules and drawn on the frame clock, with the screen's pixels the only rounding; the app re-derives only at the
instants the rules name (including where a moving edge meets a fixed one), instead of once per pixel while
something was pinned.

### Reaching the best score when it is reachable, and the best score wins between devices — 2026-09-17

→ ADR 0001 § 12 (new), ADR 0015 § *The best score wins* (new), `docs/scheduler_score.md` § *Degradation* and § *The
score*, `docs/invariants/scheduler.md` (§ *The best score*, § *One device plans*, § *Progressive Calculation*, § *When
the plan is recomputed*), `server-quota.md`, `CLAUDE.md`. `shared`: `domain/ScheduleSearch.kt` (new: `SearchBudget`,
`SearchReport`, `ExternalScheduleSolver`, `platformScheduleSolver`), `ScheduleOptimizer` (seeds, wall-time exhaustive
search, solver pass, `legalPrefix`/`accepted`, alternatives to 1 s, `searchMarginMillis`), `ScoreModel.nextFixedStart`,
`ScheduleFill` (`searchUntilMillis`, `seeds`, `budget`, `ruleStateAt` + `ruleStateSwitch`), `SchedulerDomain.fillSchedule`
(`searchBudget`, `extraSeeds`, `searchSink`, environment past the horizon, the plan being replaced as a seed,
`PROGRESSIVE_FIRST_STAGE_MILLIS`, `INLINE_REPLAN_SEARCH_MILLIS`), `SchedulerIntent.RefreshSchedule/ExtendSchedule`
(`searchMillis`, `seeds`), `SchedulerRunEntry.search/score`, `SchedulerReducer` (`planSearchSink`, `reduceInlineReplan`,
in-reducer fills capped to a first stage), `SchedulerEngine` (`planSearch`, `stageSearchMillis`, the staleness bound
**removed**, counter wiring), `ScheduleCoordinator` (`ownPlan`, `replanWithSeeds`, `PeerMessage.Counter`); jvmMain
`MipScheduleSolver.kt` + `ScheduleSolver.jvm.kt` (OR-Tools 9.15 SCIP, Windows native only); null actuals on Android,
iOS, JS, Wasm; `App.kt` / `SchedulerHolder` pass `planSearch = true`. Build: `ortools-java` (natives excluded) +
`ortools-win32-x86-64` in `shared` jvmMain. Tests: `MipScheduleSolverTest` (new, 5), `ProgressiveStageBoundaryTest`
(new, 2), `ScheduleScoreTest` (+3), `SchedulerRunLogTest` (+1), `TaskTreeTimelineTest` (+1), `ScheduleCoordinatorTest`
(+2), `SchedulerPeerProtocolTest` (the counter on the wire), `ScheduleStalenessRuleTest` (rewritten: time never
re-plans), `PlanConcurrencyTest` (expected plan computed from the pre-plan state). **Client only — an app rebuild
(`account{1,2,3}-*deploy*.bat`); no Supabase deploy** (the counter rides the existing private broadcast channel).

An audit of whether the scheduler strictly satisfies `docs/scheduler_requirements.md`, and the user's rule that its
answer need not be deterministic across devices: the best score wins, and a MIP must be used where it is better.
- "The best score must be reached when reachable in the time" was never attempted: the exhaustive search now runs on
  the wall time each progressive stage's pace leaves, and certifies the result when it finishes.
- The hourly staleness re-plan rewrote definitive schedules with no rule change: removed.
- A stage's last runs were bent by where it stopped: a fill now searches a decision window past its horizon.
- In-reducer re-plans filled a week on the UI thread: now a first stage the engine extends.
- Alternatives were located to a minute with shallow probes; a run inside a rule-state transition kept its first
  instant's rules: both fixed.
- Plans made apart now compete on the score under the merged rules (seeds + `PeerMessage.Counter`).
- The desktop MIP was built and measured: it is NOT better than the exhaustive search given wall time (which found
  plans up to 27 % better than the step-bounded passes); it is kept behind it, with the time it leaves.

### The score's cell walk could loop forever — 2026-09-17

`shared`: `ScoreModel.advance` (`ScheduleScore.kt`). Test: `ScoreAdvanceTerminatesTest` (new; hangs on the old loop).
**Client only — an app rebuild (`account3-deploy-windows-offline.bat` / `account{1,2,3}-*deploy*.bat`).**

The first release build of the score-based scheduler never drew a window: the EDT (in the retroactive no-screen
strip's fill) and two workers each spent minutes in `ScoreModel.advance` replaying the week of history. The cell
index was re-derived with `floor((u − pieceStart) / cellLen)` from a `u` that had just landed on a cell end; at real
epoch offsets that rounds back into the same cell about a quarter of the time, so `u` never advanced. The index is now
derived once and stepped. `ScheduleScoreTest` builds models from 0 in whole hours, where that arithmetic is exact —
the bug only exists at real timestamps, and only a launch against the real DB showed it.

### Working offline — 2026-09-17

→ `docs/invariants/sync-and-accounts.md` § *Working offline* (new), `CLAUDE.md` state table. `shared`:
`persistence/NetworkMode.kt` (new, `NetworkModeStore`), `SqlDelightSchedulerStore`, `RemoteSnapshotClient.offline` +
`WorkingOfflineException`, `SchedulerSyncEngine.offline` / `setOffline` / `startOffline`,
`TaskSchedulerViewModel.offline` / `setOffline` / `connect`, `SyncUi.WorkOfflineButton`, `startOfflineRequested`
(all platforms); SQLite migration **`13.sqm`** (`network_mode`). `desktopApp` forwards `-Pomniapp.startOffline`;
new script **`account3-deploy-windows-offline.bat`** (`account3-deploy-windows.bat offline` writes
`OMNIAPP_START_OFFLINE=1` into `acc3.cred`, which `release-launch-acc3.bat` exports). Tests: `OfflineModeTest` (new, 4),
`SchedulerStoreTest.upgrades_pre_network_mode_v13_db_and_preserves_data`. **Client only — an app rebuild
(`account3-deploy-windows-offline.bat` for the offline-starting install, `account{1,2,3}-*deploy*.bat` elsewhere); no
Supabase deploy.**

Asked for while the Supabase project was down: a button that stops every request, and an account-3 desktop install
that starts offline.

### Sync by rows, history as diffs, and the server-quota test — 2026-09-17

→ ADR 0016 (new, with the outage post-mortem), `docs/invariants/server-quota.md` (new), `sync-and-accounts.md`
§ *Sync by rows*, `persistence.md` § *A History Unit is what changed* / § *One history, per-device undo*,
`scheduler.md` § *One device plans*, `CLAUDE.md`. `shared`: `state/HistoryDiff.kt` (new) and the diff-based deltas in
`SchedulerReducer` (`Delta.commit`, three-way undo/redo, per-device undo/redo, `claimUnownedUnits`,
`MergePeerHistory`); `HistoryUnit.deviceId/deviceSeq/undone/changedAtMillis`; `SchedulerStateCodec` (compact JSON,
diff deltas, unit owner keys, `encodeUnit`/`decodeUnit`, legacy units re-encoded); `sync/EntityRows.kt` (new);
`RemoteSnapshotClient` entity/history row calls; `SchedulerSyncEngine.rowReconcile` (pull by revision, merge, push
diffs + tombstones, `RowBase.pending`, full read after six days, history push/pull; the document sync and its
lost-ack repair removed; unchanged active sessions not re-pushed); `RealtimeSnapshotSubscriber` on `scheduler_head`,
ignoring its own writes (`RealtimePhoenix.changeWriterDeviceId`); `ScheduleCoordinator` silent when alone or not
present, announcing itself on (re)connect; `CalendarUi` history entry reads `undone`. Supabase: migration
**`20260917000000_entity_and_history_rows.sql`** (`scheduler_entity`, `history_unit` + trim, `scheduler_head`
published, revision sequence, `purge_scheduler_tombstones`; `scheduler_snapshot` truncated, read-only, unpublished)
and **`pause-cue-setup.sql`** (daily purges of `cron.job_run_details` older than a day and of week-old tombstones).
Tests: `ServerQuotaTest` + `FakeSupabase` + `QuotaScenario` + `FreeTierQuota` (new), `RowSyncTest` (new, 7),
`HistoryUnitSizeTest` (new), `HistoryPerDeviceTest` (new); `SchedulerSyncEngineTest` keeps its account tests (its
whole-document tests are replaced by `RowSyncTest`); `ActiveSessionSyncTest`, `SchedulerSyncTokenRefreshTest`,
`SyncPayloadTest`, `ScheduleCoordinatorTest`, `DefaultSubtreeTest`, `CategoryRulesTest` adapted.
**Both surfaces, once the project is back: `deploy-supabase.bat` (migrations 20260916000000 and 20260917000000, then
`pause-cue-setup.sql`), and an app rebuild on every device.** An older build keeps running but can no longer push.
**Not deployed; the Supabase project is still down.**

Asked for: *"I want an app that passes tests that checks that it doesn't even nearly exceed quota once it will use a
real server"*, with 10 % of each limit as the margin, rows per entity, and *"all history unit must be synced […] ctrl+z,
ctrl+y, ctrl+shift+z and alt+arrow keys act only for the history units associated to this device"*.

- Measured before: 302 MB/year of database, 107 GB/month of egress. After: 33 MB (budget 50), 497 MB (512),
  178 000 Realtime messages/month (200 000), largest message under 1 KB, 6 connections, 2 700 Edge invocations.
- The heavy scenario adds a calendar block every 20 minutes (every 3 would be 73 000 a year per account).

### One device plans, the others take its rules — 2026-09-16

→ ADR 0015 (new), `docs/invariants/scheduler.md` § *One device plans* (new), `sync-and-accounts.md`, `CLAUDE.md`
state table. `shared` (`engine/ScheduleCoordinator.kt`, `sync/SchedulerPeerProtocol.kt` — new;
`sync/RealtimePhoenix.kt` broadcast frames; `RealtimeSnapshotSubscriber` implements `SchedulerPeerChannel` on the same
socket; `SchedulerEngine` `schedulerPeers` param, `replan()` funnel, stage publishing, `adoptRules`, the became-present
edge; `SchedulerIntent.AdoptScheduleRules` + reducer; `ScheduleFill.Input.adopted`; `model.RulePlacement`;
`SchedulerRunEntry.Kind.Adopted`; `TaskSchedulerViewModel.schedulerPeers`; `App.kt`, `androidApp` `SchedulerHolder`).
Supabase: migration **`20260916000000_scheduler_peer_broadcast.sql`** (RLS on `realtime.messages` for the private
topic `scheduler:<uid>`; no table). Tests: `ScheduleCoordinatorTest` (new, 11), `SchedulerPeerProtocolTest` (new, 5).
**Both surfaces: `deploy-supabase.bat` for the migration, and an app rebuild (`account{1,2,3}-*deploy*.bat`) on every
device.** Without the migration the channel join is refused and every device keeps planning locally. **Live path
unverified.**

Asked for: *"it must determine which one must run the scheduler engine […] each time the scheduler returns a better
set of rules, the websocket notifies it and every device gets the set of rules"*, and, for a dead leader, *"the
server asks if each device is still present at the same time as pushing the update data"*. (Piece 3 of 3.)

- **Who is present is asked when a re-plan is due**, by the device that asks for it (Supabase cannot hold a
  server-to-device question open), over a private broadcast channel on the existing Realtime socket: probe, replies
  within 1 s, one decider announces the leader.
- **Ranking**: in use, kind of device, measured plan speed (power-of-two buckets), device id — not CPU load.
- **The leader broadcasts every progressive stage**; followers take the RUNS in and lay them through their own
  environment (`AdoptScheduleRules`), skipping only the search.
- **No rules within 10 s ⇒ plan locally**; channel down ⇒ plan locally at once. A device becoming present asks the
  last leader for the rules in force.
- Known limits (ADR 0015): the leader plans with its own live observations; the in-reducer presses stay local; a
  published set is capped at 2 000 runs.

### The rules repeat: a cycle on the schedulable clock, a 168 h search limit — 2026-09-16

→ `docs/scheduler_score.md` § *The rules repeat* (new), ADR 0009 § *Beyond the 168 h ceiling*,
`docs/invariants/scheduler.md` § *The rules repeat* (new), `display-hot-path.md`, `CLAUDE.md` state table. `shared`
(`ScheduleFill.kt`: `settle`, `unroll`, `Input.cycle` / `repeatBeyondMillis`, `Result`; `model/TaskModels.kt`:
`ScheduleCycle`, `CycleRun`; `SchedulerState.scheduleCycle`; `SchedulerDomain.fillSchedule`'s `cycleSink`; the five
reducer fills; `App.kt`'s far-week fill is now an extension). Tests: `ScheduleCycleTest` (new, 9). **Client only —
an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no SQLite migration, no persisted-shape change**
(the cycle is in memory only).

Asked for: *"The set of rules resulting from the scheduler can make a pattern that repeats to infinity. A limit
should prevent the set of rules to become too heavy."* (Piece 2 of 3.)

- **What repeats is the run sequence on the schedulable clock**, not a calendar day (measured: no two days alike on
  the requirements' own example, while its 5-run rotation repeats exactly). Three identical copies over a uniform
  environment make a cycle; the horizon-bent tail of the fill is replaced by it.
- **Extensions unroll the cycle** instead of searching, so the plan past detection equals one long fill, and a far
  week is a walk. Refused (and searched) under another rule state, over a pre-placed task or a period that treats
  tasks differently.
- **The search stops at now + 168 h.** A fill reaching it without an exact repetition repeats the window of its last
  runs closest to the target shares (`exact = false`) — the common case on a real account: six tasks with
  minimums 45/30/20/60/15/25 min never repeat within 8 days. Days 10–30 stayed within ~3 points of every share; a
  30-day view went from ~165 ms to ~40 ms.

### $t_{goal}$ = the calendar's end, floored at now + 10 min; a fill drops the old plan past its end — 2026-09-16

→ ADR 0009 § *The schedule horizon is $t_{goal}$*, `docs/invariants/display-hot-path.md`, `scheduler.md`.
`shared` (`SchedulerDomain.kt`: `scheduleGoalEndMillis`, `scheduleHorizonEndMillis`, `horizonRefillDueMillis`,
new `SCHEDULE_GOAL_FLOOR_MILLIS`, `fillSchedule`'s cut; `endOfFirstDayOfNextWeekMillis` / `endOfDayAfterMillis`
deleted; `SchedulerEngine.kt` horizon watchers; `App.kt`). Tests: `ScheduleHorizonTest`, `HorizonRefillRuleTest`
rewritten; `SchedulerFillTest` +1; `CalendarHorizonFixture` (new) gives the switch/sync/concurrency tests the open
calendar they assumed. **Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no
SQLite migration, no persisted-shape change.**

Asked for: *"the scheduler creates sets of rules until it definitely covers all the end of the timeline shown in
the calendar (or 10 minutes after now if the end is less than that)."* (Piece 1 of 3; the repeating rule pattern
and the multi-device scheduler leader follow.)

- **The goal** was `max(one day past the grid, end of the first day of next week)`, floored at 24 h. It is now the
  displayed end or `now + 10 min`. A closed calendar plans twenty minutes and is extended every ten.
- **The floor rolls**, so fills aim at twice it and the refill is due at one floor of coverage left — aiming at the
  rolling instant itself is the 2026-07-28 hot loop. The horizon watcher now sleeps until the due instant (capped
  at one poll) instead of polling blind.
- **Bug fixed on the way:** a fill kept the previous plan's AUTO panels past its own horizon. A progressive first
  stage (1 h) left the old week in place, and when an old panel abutted the new tail the next extension kept the
  old rules' plan as definitive. Now only panels the fill does not own survive past its horizon.

### The scheduler optimizes a defined score; progressive stages; exact rule-state blend — 2026-09-13 → 2026-09-16

→ ADR 0001 §11, ADR 0008, `docs/scheduler_score.md` (new). `shared` (`scheduler/domain/ScheduleScore.kt`,
`ScheduleOptimizer.kt`, `ScheduleImprover.kt`, `ScheduleFill.kt` — all new; `SchedulerPlan.kt` reduced to the input
types; `SchedulerProgressive.kt` deleted; `SchedulerDomain.kt`, `model/TaskModels.kt`,
`state/SchedulerIntent.kt` + `SchedulerReducer.kt`, `engine/SchedulerEngine.kt`); `docs/invariants/scheduler.md`,
`task-tree.md`, `screen-breaks.md`, `CLAUDE.md`. Tests: `ScheduleScoreTest` (new), `ScheduleImproverTest` (new, 4),
`SchedulerFillTest` (new, +1 progressive), `SchedulerPlanTest` deleted with the walk, `PlanOffTheFrameLoopTest`,
`TaskTreeTimelineTest`, `SchedulerSchedulerTest`, `SwitchTaskEntryTest`, `DegeneratePlanScaleTest` updated.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no SQLite migration, no
persisted-shape change** (`TaskPanel.alternativeSpans` is derived, never persisted or synced; the intents'
`horizonCapMillis` is not stored).

Asked for (2026-09-13): *"The requirements only says to achieve 'best possible score' for its two optimization
goals, but doesn't define it. […] So define it and satisfy the requirements. No shortcut that violates the
requirements […] are allowed."*

- **The score is defined** in `docs/scheduler_score.md` — first written into `docs/scheduler_requirements.md`, and
  moved out on the user's request (that file is theirs). Criterion 1: discounted squared lag against a target share
  carrying resilience and a bounded, exponentially decaying compensation. Criterion 2: every panel short of its
  minimum, `τ_i·s·(2M_i + s)`. All on the schedulable clock.
- **The walk (virtual clocks, claims, chunk rounds, influence field) is gone.** `ScheduleOptimizer.plan` builds the
  continuation with a rollout policy, `ScheduleImprover` lowers the score of the whole continuation (monotone by
  construction), and the alternative is named on the final runs, for every position of the line
  (`alternativeSpans`).
- **Progressive Calculation is enforced**: the engine fills in doubling stages (1 h, 2 h, 4 h, … to $t_{goal}$),
  each an extension of the last, instead of one fill to the goal.
- **The rule-state blend is exact**: the 100-step cursor is gone; inside a transition the plan re-makes itself at
  every run start the line reaches, and the two-scenario example is a test.
- **Rejected on the way** (ADR 0001 §11): a per-decision branch and bound (worse whole score, 13–42 s a day); a
  squared shortfall (the improver shaved most panels below their minimum).
- **Measured** (desktop): 13-task realistic account 94 ms for 168 h (`PerfBenchmarkTest`); 30 tasks 80 ms a day /
  1.4 s for 8 days; 60 tasks 0.2 s / 5.4 s.

### History rows get an info button; a notification's window replays its voice — 2026-09-13

→ PRD §6. `shared` (`ui/CalendarUi.kt`, `scheduler/state/SchedulerState.kt` + `SchedulerIntent.kt` +
`SchedulerReducer.kt`, `scheduler/engine/SchedulerEngine.kt`, `scheduler/persistence/SchedulerStateCodec.kt`);
`docs/PRD_TaskScheduler.md`, `docs/invariants/screen-breaks.md`, `docs/MANUAL_TESTING.md`. Tests:
`HistoryEntryInfoTest` (new, 3), `NotificationLogTest` (+1, two extended).
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no SQLite migration** (the
notification log rides the JSON payload; the field is optional).

Asked for: *"every element should have a button to display a window for more information, except if there is
nothing more to show"* and *"the notification history units must have in their info window a button to play the
vocal message that plays when this notification happens"*.

- **The button:** `historyEntryHasMoreInfo` is the one answer, read by both the new **info** chip and the
  existing double click. Units, scheduler runs and notifications: yes. Supabase calls: no — and their resource
  line is no longer ellipsized, so "nothing more to show" is literally true.
- **The replay:** `(title, message)` could not say which voice a notification used — the look-away's start (WAV)
  and a rest pose's (synthesized) are both "Screen break". `NotificationLogEntry` / `RecordNotification` now
  carry the `VoiceCue` `notifyUser` passed, persisted as `cue` (default `null`; an unknown name decodes to `null`).
  The window adds a **Voice** info and **▶ Play the voice message**, through `NotificationLogEntry.utterance`
  (= `VoiceUtterance.forNotification`). It ignores both switches: it is an explicit request.
- **Follow-up, same day — three anomalies on the button:**
  1. *"doesn't look like a button, and the mouse is the text-field one"* — it was a bare clickable text inside
     the list's `SelectionContainer`. Now `HistorySmallButton` (also the info window's copy buttons): bordered
     and filled, `PointerIcon.Hand` overriding the descendants' I-beam, label in `DisableSelection`.
  2. *"clicking it moves the History window"* — the info window was drawn in a `TransientPopupLayer`
     (`fillMaxSize`) inside the History window's pair `Box`, which grew the `Box` to the whole area and put the
     History frame at its top-left. Double click had the same bug. The info window is now a plain frame beside
     it, opened off `frame.offset`, and the `Box` centres its content. `docs/invariants/popups.md` records both.
  3. *"clicking info again doesn't bring the focus back to the info window"* — the same row changes no state.
     New `WindowFrameHost.present(id)` (restore + raise + focus), called on every open. Tests:
     `WindowFrameHostTest` (+2).
- **Limit:** entries logged before this build have no cue, so a pre-existing look-away/resume row replays
  synthesized from its text rather than the WAV. Not healed on decode — guessing from the text is exactly the
  ambiguity the field removes.

### A Mode pick puts the caret back in the edited cell — 2026-09-13

→ PRD §4. `shared` (`scheduler/ui/TaskSchedulerScreen.kt`); `docs/invariants/task-tree.md`,
`docs/MANUAL_TESTING.md`. Tests: `EditModeRefocusTest` (new, 4).
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for: *"after selecting one of the two modes, the cursor must reappear on the text field"*, the same mode
included.

- **Why it didn't:** the Mode drop-down is a focusable popup, so opening it took the focus off the field, and
  the cell's focus effect was keyed only on `isEditing`/`keyboardOwned` — neither changes on a pick. Re-picking
  the current mode is a reducer no-op, so no state change could have carried it either.
- **Fix:** the options are built by `cellEditModeOptions`, every one of which ends in `onModePicked`; the row
  turns that into a counter keyed into its existing focus effect (one frame after the popup leaves), so the
  `keyboardOwned` gate still decides. The `editMenus` slot now receives that refocus callback.
- The focus itself is not machine-verified (no Compose UI test harness in the project); the test pins the
  wiring. Checked by hand via the new `MANUAL_TESTING.md` §2 item.

### Every "before bed" period is also a "no screen" period — 2026-09-13

→ PRD §17, ADR 0001. `shared` (`scheduler/domain/PeriodKinds.kt`, `scheduler/domain/SchedulerDomain.kt`,
`App.kt`, `ui/CalendarUi.kt`); `docs/invariants/scheduler.md`, `docs/invariants/calendar.md`,
`docs/MANUAL_TESTING.md`. Tests: `BeforeBedPeriodTest` (+4, one rewritten), `LayerPeriodKindTest`,
`CalendarEditChoicesTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"the next before bed period doesn't have the 'no screen' period (it doesn't have the 'no computer
unlocked' and 'no phone unlocked')"*, then the rule: *"Everytime there is 'before bed' restrictive period,
there is also 'no screen' period. Meaning that 'before bed' is always at least 4 restrictive periods at the
same time t."*

- **Not a regression — the old spec said the opposite.** Since 2026-08-29 the wind-down "said nothing about
  screens" (PRD §17 let the breaks fall in it), and `PeriodKinds.assertedLayers` answered none for it. The
  spec is now the user's rule.
- **An implication of the kind, not a companion panel.** `PeriodKinds.impliedKind(before bed) = no screen`,
  folded into `assertedLayers`. Everything that already reads "which layers does this stretch carry" answers
  for the hour from there: both hatches (`assertedLayerRanges`), the scheduler and the recurrence bars
  (`impliedNoScreenPeriods` — so the hour is now a no-screen REST, and an on-screen task given a resilience to
  `before bed` still stays out), the record bank. An explicit "No screen" period over the hour is subtracted
  as before, so a fractional resilience is not squared.
- **What the period IS did not change**: `PeriodKinds.isLayerKind` (by name) keeps its default resilience at
  `0`, keeps its box orange and grey-kinded rather than repainted as a no-screen period, and keeps it out of
  `unifyNoScreenPeriods`.
- **Two places built wind-down periods by hand and would have dropped the no-screen half**: the fill's
  `dynamicBase` (which passed only the KEPT panels to `impliedNoScreenPeriods`, and the wind-down hours are
  laid by that very fill) and the calendar's display environment (which mapped `beforeBedRegions` straight to
  `RestrictivePeriod`s). Both now route through the funnel; `beforeBedRegions` is gone.

### A §18 ring is the OTHER top-most marker — it was left under the §15 bands — 2026-09-12

→ `shared` (`ui/CalendarUi.kt` — `DayColumn`'s emission order, the new `CalendarOverlayLayer` /
`overlaysUnder`); `docs/invariants/calendar.md`, `docs/MANUAL_TESTING.md`. Tests: a new
`CalendarOverlayLayerTest` (5).
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"it seems like 20s screen breaks appear above the reminders"* — and the marker under the break
turned out to be a §18 **alarm**, not a §14 reminder: *"the 'claude' alarm in the current day column, when
zoomed out enough, a 20s break appears over the 'claude' alarm panel."*

- **The 2026-09-04 z-order fix lifted ONE of the two zero-duration markers.** It moved the §14 tags to the
  end of `DayColumn`'s emission and left the §18 rings exactly where they were — before the §15 screen-break
  bands and before the band labels. PRD §8 and the invariants had already written the rule for **both**
  ("the two zero-duration markers lead… each is drawn *on top* of the panels"), so the docs were describing
  a calendar the code did not draw.
- **It takes ZOOMING OUT to see, which is why it survived eight days.** A ring is a fixed
  `ALARM_MARKER_HEIGHT` whatever the zoom, so the further out the calendar goes the more minutes its
  rectangle spans — and a 20-s look-away recurring every 20 min is then near-certain to fall inside one. Zoomed
  in, the band is a hairline somewhere else entirely.
- **A ring is INERT, so only the paint complained.** A tag is a pointer-input node, and that is how the same
  defect announced itself in September: the click that checks a reminder off stopped landing. A ring
  registers no click, so nothing broke — meanwhile `CalendarBubbleSection.Kind` went on ranking
  `alarm/timer ring` **above** `break`, so the hover bubble kept saying the ring was on top of the thing
  painted over it. The bubble and the paint were answering one question two ways.
- **`CalendarOverlayLayer` is now that one answer**: `ScreenBreak < Ring < Tag`, the bottom-to-top order of
  everything the column draws over its panels. The emission order follows it, and `overlaysUnder(layer, …)`
  derives what each one stacks under its own bubble section from the same declaration — so a band stacks the
  panels only, a ring stacks the bands, a tag stacks both. Those three lists used to be written out by hand at
  the three emissions, which is precisely how they drifted apart. `screenBreakOverlays` is hoisted beside
  `alarmOverlays` for the same reason the placements are: two readers now, one derivation.
- **Emission order is `bands → band labels → rings → tags`**, and nothing goes after the tags.

### An outline says WHICH SURFACE stated the thing — so an alarm ring is orange, not blue — 2026-09-12

→ `shared` (`scheduler/domain/SchedulerDomain.kt`, `App.kt`, `ui/CalendarUi.kt`);
`docs/PRD_TaskScheduler.md`, `docs/invariants/calendar.md`, `docs/MANUAL_TESTING.md`. Tests:
`CalendarPanelOutlineTest` gains one pinning the two families with no panel behind them (a ring is outlined
like a §17 sleep window, a tag like a period drawn on the grid).
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"things placed by rules defined in windows accessible via the left side menu of the app (Sleep
schedule, Alarm) are outlined in orange. The ones that are placed or moved through a right-click menu in the
calendar are outlined in blue. Anomaly: I see that all daily alarms are outlined in blue."*

- **The outline's question was written down as *who put this here*, and that is not the rule.** Both surfaces
  are the USER'S hand, so "who" cannot separate them; what separates them is WHERE the thing was stated — a
  rule in a window off the left menu (orange) against a placement or a drag on the calendar itself (blue).
  `PanelOutline`'s own doc says it that way now, and `panelOutline`'s four answers are unchanged: they were
  already right for panels, because every left-menu rule it can see is a §17 one.
- **The regression was the entry below**, which gave the §18 ring the blue border under *"the whole added
  period/panel/reminder/alarm must be outlined in blue"*, read as *the user added it*. Every daily alarm then
  drew as something placed on the calendar — the one thing an alarm can never be: **the calendar's menu
  cannot add an alarm or a timer at all**, it only EDITS one by opening the §18 window that owns it.
- **The two instant families get their answers from the same funnel** — `SchedulerDomain.ringOutline()`
  (orange: the Alarms window is a left-menu rule window, exactly like the sleep schedule) and
  `reminderTagOutline()` (blue: a reminder is added from the calendar's own menu). Asked in `App.kt` beside
  every other record's `outline`, so the two drawings read `outlineColor(record.outline)` like every other
  block and **neither picks a colour of its own** — picking one at the drawing site is precisely how the
  ring came to wear the accent.
- **Not a per-object provenance question**, which is why `ringOutline()` takes no argument: nothing records
  where an alarm was created, and nothing needs to. `repeats`/`days` are not consulted either — a one-shot
  alarm is stated in the same window as a daily one.

### A hand-drawn no-screen period is DOTTED over hours the machine was unlocked, and OUTLINED like everything else the user adds — 2026-09-12

→ `shared` (`scheduler/domain/SchedulerDomain.kt`, `App.kt`, `ui/CalendarUi.kt`);
`docs/PRD_TaskScheduler.md`, `docs/invariants/calendar.md`, `docs/adr/0002-calendar-layers-and-grey.md`,
`docs/MANUAL_TESTING.md`. Tests: `CalendarLayerTest` gains three (the drawn period is dotted and splits at a
lock, the future half of a straddling one stays solid, the app's own promises dot nothing);
`CalendarEditChoicesTest.aNoScreenPeriodIsAMenuTargetButNotABox` becomes
`everyPeriodKindIsABoxExceptTheSleepBandAndTheUsersIsBlue`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: *"When the user adds a 'no screen' period on a past time period where some computers were
unlocked, the oblique lines for the 'no computer unlocked' restrictive period must be dotted there. Also, the
whole added period/panel/reminder/alarm must be outlined in blue."*

- **The dots are no longer the "I'm away" button's alone — same funnel, second source.**
  `declaredLayerRegions` takes `declaredRegions` (the away spells **plus** `assertedLayerRanges`, the value
  the hatch is already built from) instead of `declaredAway`. Both are the user stating a layer where the OS
  lock log disagrees, and the dots say exactly that; the two would have been one rule written twice.
- **A declaration is clipped to the OBSERVED window first** (`[since, until]`, the caller's now-line). Ahead
  of the line there is no evidence to contradict, so "declared minus evidence" would have dotted every
  projected hatch in the future. A period straddling the line now dots only its elapsed half.
- **The app's own promises stay out of it.** A projected sleep window and a screen break hatch the layers
  too, but neither is anybody's statement about what happened, and each already wears the outline (orange,
  grey) that says which rule laid it.
- **A `no screen` period draws its box again** (`isDrawnPeriodRecord`, which is now "every period but the
  sleep band"). It was excluded that morning because it asserts both layers and the slopes are already
  painted over it — but the hatch and the box answer different questions, and the app derives that same
  hatch out of the lock log all day. With no box, the user's own no-screen period was the ONE thing they
  could add to the calendar that left no trace of having been added.
- **THREE MARKS, THREE QUESTIONS**, which is the rule that keeps the dots and the outline from being deleted
  as each other every time one of them is looked at (it happened twice on 2026-09-12): the hatch says what
  is **claimed**, the blue outline says a **hand** placed it, the dots say the machine's **log disagrees**. A
  declared absence has dots and no outline; a no-screen period over a locked night has an outline and no
  dots; one over an evening at the keyboard has both.
- **The §14 reminder tag and the §18 alarm/timer ring wear an outline too**, in their own drawings rather
  than through `panelOutline` (a tag is a chip with a check box, a ring is an instant — neither is a panel),
  at the same `USER_PLACED_BORDER_DP`. The load-bearing case is a **checked** tag: its fill goes muted, so
  the outline is the only thing left saying whose it is. ⚠️ Both were drawn BLUE here, which was wrong for
  the ring — corrected the same day by the entry above (*an alarm is a rule off the left menu*).

### A kind of restrictive period is named by the USER'S word, everywhere — and `no task allowed` is two kinds — 2026-09-12

→ `shared` (`scheduler/domain/PeriodKinds.kt`, `scheduler/persistence/SchedulerStateCodec.kt`,
`scheduler/domain/SchedulerDomain.kt`, `ui/CalendarUi.kt`); `docs/invariants/calendar.md`. Tests: new
`PeriodKindNamingTest` (4) and `RestrictivePeriodKindTest.a_payload_written_before_the_rename…`, new
`CalendarEditChoicesTest.aPeriodRowIsNamedByItsKind` + `aSleepBandIsItsPeriodAndTheScheduleBehindIt`, new
`TaskCellCopyTest.a_clipboard_naming_a_kind_by_its_old_spelling…`, and `TaskResilienceTest` extended.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy. No `.sqm`: the payload is
JSON in `app_state` and is migrated ON LOAD.**

Reported as: the "add…" window's kind picker listed the kinds under their STORED names, so the user looking
for *inactivity* — the word the "edit…" chooser uses, and the word the editor that picker opens is headed
with — found "no task allowed" and read it as missing.

- **Fixed by RENAMING the kinds, not by adding a label table.** `no on-screen task` → `no screen`, and
  `no task allowed` → [`inactivity`]. The stored name IS the menu row now, so no surface can pick its own
  spelling because there is no second spelling to pick; the `periodChoiceLabel` that used to translate one
  into the other was DELETED rather than left standing as an identity. `PeriodKinds.periodTitle` stays a
  separate answer and differs by CASE alone ("Inactivity") — it is the title a period CARRIES on the grid,
  where it heads a box rather than a menu line.
- **`no task allowed` became TWO kinds, `inactivity` and `sleep`**, because the calendar has always drawn and
  named them apart and the user says them apart — one name could not be offered as either word. Both refuse
  everybody (`defaultResilience` 0, `isResilienceEditable` false), so the split renames and divides without
  changing what a grey period does. A sleep band is therefore a `sleep` PERIOD row plus the §17 `sleep
  schedule` row — two objects, two rows, exactly as `task` and `task panel` are over one block — and it still
  draws no period box over itself, because §17 already draws it.
- **Every name a kind has resolves BACK to it** (`periodKindNamed`): its own word, its grid title, and the
  pre-rename spellings. Without that, an old account typing the word its own payload stores would be offered
  "Create and use" and would mint a second kind differing from a built-in only in spelling.
- **Three stored paths are migrated on load** (`migrateStoredKind`), and each fails differently if missed:
  the RESILIENCE MAP (a task is on-screen exactly when it holds a `0` against `no screen`, so left alone
  every task in an existing account would read as OFF-screen — free to be placed inside the very periods it
  was being kept out of); a PANEL's kind (which of the two grey kinds it means is read off its own `sleep`
  flag, since the name alone cannot say); and the ACCOUNT'S KIND LIST, where the old name passes
  `isUserDefined` and would give the account a user-defined kind DUPLICATING a built-in, offered twice in
  every picker.
- **The clipboard parser was the funnel that had been missed** — found while finishing this, and a real bug:
  `parseTreeText` normalized the kind in `- resilience to <kind>: <n> %` but never migrated it, so a
  clipboard cut before the rename pasted `no on-screen task` as a USER-DEFINED kind of that name. That
  kind's default is `0`, so the `0 %` that made the task ON-SCREEN read as redundant, was dropped, and the
  task came back off-screen — the codec's own failure mode arriving by the other door. It reads through
  `migrateStoredKind` now, like every other stored kind name.
- **The period chooser's table gained `sleep`** (`PERIOD_CHOOSER_KIND_ORDER`, second), and the kinds the
  README names are still its vocabulary rather than the calendar's: `docs/invariants/calendar.md` records
  that the two vocabularies are allowed to differ — what is not allowed is a SURFACE picking its own.

### A right-click after a zoom hit-tested the wrong hour ("add…" alone on a task panel) — 2026-09-12

→ `shared` (`ui/CalendarUi.kt`); `docs/invariants/calendar.md`, `docs/adr/0002-calendar-layers-and-grey.md`,
`docs/MANUAL_TESTING.md`. Tests: new `CalendarPressScaleTest` (4). **Client only — an app rebuild
(`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"I clicked on a task panel with a no phone unlocked layer, and the only option I got was
add…"* — on the release account, right after the chooser reshape above. Neither the chooser nor the layer
kinds were involved.

- **`Modifier.pointerInput(day)` outlives a ZOOM.** A `pointerInput` whose key has not changed keeps running
  the lambda it started with, captures and all. The column guarded its record LISTS with
  `rememberUpdatedState` for exactly that reason and then read `hourHeight` directly, so after a zoom in
  (without a scroll, which would have changed `day` and restarted the coroutine) every press was divided by
  the old hour height — landing past the end of the day, where there is nothing. Empty hits ⇒ no chooser rows
  ⇒ "add…" alone. `millisAt` had it too, so that "add…" was anchored at 23:59.
- **Fixed by reading the scale live**: `currentHourHeightPx`, `currentReminderHeightPx`, `currentRingHeightPx`
  and `currentAllBlocks` in the column; `currentHourHeightPx` and `currentOthers` in `CalendarBlock`, whose
  `millisDelta` had the same staleness — so a drag or resize after a zoom moved a panel by the wrong amount.
  (`currentEdgePx`/`currentSliceHeightPx` were already guarded "because the gesture coroutine outlives a
  zoom": the rule had been found once and applied to two values instead of to the closure.)
- **Re-keying the modifier on `hourHeight` was rejected**: it cancels the gesture in flight, leaving
  `dragPreview` set and the scroll lock held.
- **The conversion is now pure** (`pressHour` / `pressSpans`, scale as a parameter) because there is no
  Compose UI test harness in the project: `CalendarPressScaleTest` pins that one pixel is two different hours
  at two zooms and that the stale scale yields the empty chooser that was reported.
- **Found by probing the release DB read-only**, not by reading the chooser: it said the account holds NO
  layer-kind periods (so the hatch was evidence, covering the whole past — a description of where the click
  was, not a participant) and that a task record really did cover the instant clicked.

### "edit task" is a row of the "edit…" chooser, and the order is the user's six — 2026-09-12

→ `shared` (`ui/CalendarUi.kt`, `App.kt`); `docs/PRD_TaskScheduler.md` §8, `docs/invariants/calendar.md`,
`docs/adr/0002-calendar-layers-and-grey.md`, `docs/MANUAL_TESTING.md`. Tests: `CalendarEditChoicesTest` (four
new — the task row, one row per TASK rather than per panel, the double-click's exclusion, the new order — and
three updated). **Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema
migration.**

Asked for as: *"All the edit options of the right-click menu in the calendar must be reduced to 'edit…' …
They must appear in this order: task / task panel / restrictive period / reminder / alarm / timer. When there
is only one of them, it replaces 'edit…' directly."*

- **`edit task` was the last edit standing outside the chooser** — offered beside it on a task panel. It is
  the chooser's **first row** now (`EDIT_LABEL_TASK`), routed by `App.kt`'s `onEditChoice` to the same §13
  window; the calendar's `onEditTask` callback is gone, so there is one way in and not two. What stayed
  beside the chooser is what is not an edit: `go to task tree` (navigates), `move` (a phone gesture), `add…`.
- **`CALENDAR_EDIT_ROW_ORDER` is now the user's six** — task, task panel, restrictive period, reminder,
  alarm, timer — and `editRowRank` ranks the ROW rather than its label, so a lone period keeps its kind's
  NAME while sitting in the restrictive-period slot. The old table was one mixed list that also ranked every
  kind, which put a lone `before bed` row last and a lone `inactivity` row third.
- **The period chooser got its own table**, `PERIOD_CHOOSER_KIND_ORDER` (inactivity, no computer unlocked, no
  phone unlocked, no screen, before bed, then the account's). Two tables that rank disjoint sets — families at
  the top level, kinds inside the one family that has them — so neither can contradict the other; ADR 0002
  records why that is not the drift the single mixed table was built to prevent.
- **The double-click drops the `task` row** (`calendarBlockEditChoice`): it still goes through the one table,
  but a double-click is a gesture ON the block, and with `task` ranked first it would otherwise have started
  opening the §13 window instead of the panel's editor.
- **`sleep` ranks after the six.** Its editable object is the §17 schedule rather than anything the calendar
  lays, so it is not one of the things the user ordered, and an unlisted row ranks last.
- **No change to the two layer kinds**: `no computer unlocked` / `no phone unlocked` have been restrictive
  periods since 2026-09-12's earlier `PeriodKinds` work (`LayerPeriodKindTest`) — the only doc gap was the
  add-window's list of kinds in PRD §8 and `MANUAL_TESTING.md`, which named three of the five built-ins.

### The declared-away hatch is DOTTED again — 2026-09-12

→ `shared` (`scheduler/domain/SchedulerDomain.kt`, `ui/CalendarUi.kt`, `App.kt`);
`docs/invariants/calendar.md`, `docs/adr/0002-calendar-layers-and-grey.md`, `docs/MANUAL_TESTING.md`.
Tests: `CalendarLayerTest`'s five dotted-hatch tests restored, plus one pinning that the REGION is still
unsplit. **Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema
migration.**

Asked for as: *"now the oblique lines must be dotted if at least one of the corresponding devices was
unlocked but the I'm away button was clicked"* — the same requirement `declaredLayerRegions` was written
for on 2026-09-05, reinstated hours after that morning's commit deleted it.

- **The deletion's reasoning was right but too broad.** *One drawing per statement* said the dots were a
  second answer to "who said this", the first being a period's blue outline. That holds for everything an
  outline can reach — and the **"I'm away" button lays no period.** It is a derived declaration going
  straight into the layer's asserted slot, so there is no panel to outline, and with the dots gone a
  declared stretch and an observed one drew identically.
- **Restored unchanged**: `declaredLayerRegions` (declaration MINUS this kind's lock evidence, intersected
  with the band drawn), `CalendarRecord.layerDeclared`, `PlacedRecord.layerDeclared`, and
  `obliqueHatch(dotted = …)`'s dash effect. Evidence wins where it overlaps, an asserted region does not,
  a `null` lock history dots nothing (every peer layer).
- **The REGION is still unsplit**, which is what the deletion got right: a declaration still rides the
  asserted slot and still merges with the evidence beside it into one region. The dots split the region's
  *drawing*, which is why the `∞` marker is still asked of the merged list.

### "Task to do now" is no longer announced at an away line — 2026-09-12

→ `shared` (`scheduler/domain/SchedulerDomain.kt`, `scheduler/engine/SchedulerEngine.kt`);
`docs/invariants/screen-breaks.md`. Tests: new `CurrentTaskAtLineModeTest`; `AwayVersusLockedCueTest`
rebuilt as a true pair (an off-screen task IS still announced to an away device, an on-screen one is not).
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"it is impossible for a task to be scheduled between these two notifications, as it is a no
screen period and no task has a resilience to no screen periods. However, at 15:08:40, the app did a
notification for a new task to do"* — away since 14:54:15, back at 15:16:26 (account 3).

- **`SchedulerDomain.currentPanel` did not read the mode.** `docs/scheduler_requirements.md` § *$now line$
  3 modes* binds the line at every instant it is in mode 2 or 3; `DynamicPeriods.awayCover` expresses that
  to the fill as one millisecond at the `t_p` the fill was built for, and time passing never re-plans — so
  the line walked out of it into the task the plan had put after it, and the cue read that stored panel.
- **The mode is now applied where the question is asked**: in either away mode an on-screen task is not at
  the line. Who survives is `Task.onScreen`, the SAME predicate `clipPanelsForObservedNoScreen` already cut
  the display with and `clipRecordsForObservedNoScreen` already refused to bank a record with — the cue was
  the third reading of one rule and the only one never written, which is why the calendar drew no task over
  the stretch, the §9 bank stored none, and the app still spoke one.
- **This is not the away flag silencing the device.** A task resilient to `no on-screen task` is still
  scheduled at an away line and still announced — the case the button exists for. A LOCK is the other
  thing, and still gates the output outright.
- **Suppressed, not spent**: `lastNotifiedTaskId` is untouched (the diagnostics line dedupes on its own
  `lastAwaySuppressedTaskId`), so coming back — which flips the mode and re-plans — announces the task the
  user came back to.
- The cue sweep now takes **one** reading of `tpModeNow` for the whole pass, shared by the break crossings
  and the task cue, so the two can never disagree about the same instant.

### An "I'm away" episode survives a restart (schema v13) — 2026-09-12

→ `shared` (`scheduler/persistence/DeclaredAwaySpan.kt` (new), `SqlDelightSchedulerStore.kt`,
`sqldelight/.../Scheduler.sq` + `12.sqm`, `scheduler/engine/SchedulerEngine.kt`, `App.kt`);
`androidApp/SchedulerHolder.kt`; `docs/invariants/calendar.md`, `docs/invariants/screen-breaks.md`.
Tests: new `DeclaredAwayPersistenceTest`; `SchedulerStoreTest` gains a round-trip, a
"local-only, does not touch the snapshot" and the v12→v13 migration test.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy. SQLite schema
migration v12 → v13, applied on first open.**

Reported as: *"I don't see a blue outlined no screen period right before now line"* (account 3), after an
away spell from 14:21:08 to 14:38:23 that the app had drawn correctly while it ran — the diagnostics'
no-screen evidence grew with it (11 spans/886 min at 14:03, 13/899 at 14:34) and collapsed back the moment
the 14:38 redeploy restarted the process (10/880 at 14:39).

- **`SchedulerEngine._declaredAwaySpans` was memory-only**, and the "I'm away" button is the ONE no-screen
  fact the OS can never re-supply: the machine stays UNLOCKED while it is on, so no Windows session log will
  ever show the stretch. A restart therefore erased it from the calendar layer, the hatch built out of it
  and the §9 record-bank evidence at once — over a stretch `t_p` had just been in mode 3 for.
- **`device_away_span` (12.sqm) is that record**: LOCAL-ONLY, never synced, never a History Unit. One row
  per episode keyed by its START, so the press that opens it, every extension and the "I'm back" that
  closes it are one row. The server's `away_spans` is not a substitute — it is the ACCOUNT's record, it is
  best-effort (every `syncDeviceAway` on the day 504'd), and nothing on the display path reads it back.
- **Extended on the 30-s active-session beat, never on a timer of its own** (CLAUDE.md's rule), so a kill
  mid-away lands the episode closed at its last beat, exactly as a live `device_active_session` row does.
- **The FLAG is deliberately NOT restored.** It is a live declaration, and only a lock→unlock edge clears
  it: an app that re-asserted it at startup on an unlocked machine would claim an absence it can never see
  the end of.

### The calendar menu names THINGS, and overlapping periods share one box — 2026-09-12

→ `shared` (`ui/CalendarUi.kt`, `App.kt`, `scheduler/domain/SchedulerDomain.kt`);
`docs/invariants/calendar.md`, `docs/adr/0002-calendar-layers-and-grey.md`, `docs/MANUAL_TESTING.md`.
Tests: new `CalendarEditChoicesTest`; `CalendarLayerTest`'s five dotted-hatch tests replaced by one that
pins the merged band.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: *"All the edit options of the right-click menu in the calendar must be reduced to 'edit…',
which then asks what to edit among the choices deduced by the position of the mouse"*, and *"when there are
many restrictive periods in the same time period, then instead of sharing the width of the day column, it
shows only one restrictive period with the title of all the restrictive periods at the top left"*.

- **`Edit` / `edit task`'s panel half → one `edit…` chooser** (`calendarEditChoices`, fed by the column's new
  `menuHitsAt`, which collects EVERYTHING under the cursor instead of the top-most block). The rows are in
  one order for both levels (`CALENDAR_EDIT_ROW_ORDER`): task panel, restrictive period, inactivity,
  reminder, alarm, timer, no computer unlocked, no phone unlocked, no screen, sleep, before bed. A chooser
  of one is not a chooser — the row replaces `edit…` in the menu, and a lone period is named by its kind.
  The double-click goes through the same table.
- **`Remove` is gone; each editor has a BIN** (`EditorBinButton`), absent where nothing is stored to delete.
- **A `reminder` row edits a tag** — `ReminderEditWindow` grew a `ReminderEditSeed` and a bin; a tag could
  only be added and checked off from the calendar before.
- **Every restrictive period leaves the block pipeline** and is drawn as one full-width box per stretch
  (`periodSegments` / `periodSegmentLabel` / `periodSegmentOutline`): A 10–12 with B 11–13 is three boxes.
  Dragging or resizing a shared box moves every period in it; the gesture is emitted UNDER the panels and
  the marking OVER them.
- **A `no screen` period draws no box** (`isDrawnPeriodRecord`) — both layer hatches are its whole drawing.
  Its chooser row also stands for a `no computer unlocked` + `no phone unlocked` pair, and its Save writes
  to every record behind it.
- **Inactivity is marked by VERTICAL LINES again** (`Modifier.verticalHatch`) — a third slope beside the two
  oblique layers, not the grey wash deleted on 2026-09-11.
- **The dotted layer hatch is gone**, with `SchedulerDomain.declaredLayerRegions` and
  `CalendarRecord.layerDeclared`: a stretch a hand stated is a period, and a period is outlined blue.
- **Derived inactivity now runs to the definitive-schedule front**, not to the now-line. It stays derived on
  both sides (ADR 0002); **editing one MATERIALIZES it**, under the band's own kind.

### The per-panel device bubble is gone — the layers already say it — 2026-09-12

→ `shared` (`ui/CalendarUi.kt`, `App.kt`); `docs/adr/0002-calendar-layers-and-grey.md`,
`docs/adr/0005-sync-and-merge.md`, `docs/MANUAL_TESTING.md`. Tests: `DeviceActivitySegmentsTest` deleted,
`CalendarDisplayEquivalenceTest` loses its session-index equivalence.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: *"If all the computers are down, asleep or unlocked, there is a no computer unlocked layer.
Remove this dotted horizontal line and Open:… in the info bubble."*

A past task panel used to be cut into one segment per **set of open devices**, drawn with a dashed
horizontal separator at each change and named by an `Open: Desktop` / `Open: no device` line in the hover
bubble (2026-07-16, ADR 0005). It is removed: the two oblique layers answer the same question already —
"no computer unlocked" *is* every computer being down, asleep or locked — so the segmentation was a second
reading of "was anybody at a screen", drawn more quietly and per install, over a panel the hatch already
covers.

Deleted whole: `DeviceActivitySegment` / `PlacedDeviceSegment`, `deviceLabels`, `deviceActivitySegments`,
`DeviceActivityIndex` (its ADR 0009 hoist with it), `deviceHoverZones` and the dashed separator.
`blockBubbleOverlays` now contributes one overlay over the whole slice, so a block's hover tiling is cut by
its covering sections and its resize strips alone.

**The active sessions themselves stay.** They are what `derivePauses`, the Inactivity bands and the rest
stretches are made of, they still sync with their `kind` column, and nothing about the bands moves — only
the drawing on top of the panels is gone.

### A period is an empty outlined box, and no block wears a check box — 2026-09-11

→ `shared` (`scheduler/domain/PeriodKinds.kt`, `SchedulerDomain.kt`, `scheduler/state/SchedulerReducer.kt` +
`SchedulerIntent.kt`, `ui/CalendarUi.kt`, `App.kt`); `docs/PRD_TaskScheduler.md` §8,
`docs/invariants/calendar.md`, `docs/invariants/scheduler.md`, `docs/invariants/shortcuts.md`,
`docs/MANUAL_TESTING.md`. Tests: `LayerPeriodKindTest` (new), `CalendarPanelOutlineTest` (was
`CalendarPinBoxTest`), `SwitchTaskEntryTest`, `TaskResilienceTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: *"Remove the check box at the top right of blue outlined boxes… A restrictive period that is
not a 'no screen' period is shown in the calendar as an empty panel outlined in blue… A restrictive period
that is placed in the calendar by a repeating pattern must be orange outlined… Sleep and before bed are both
restrictive periods."*, then *"dynamic restrictive periods must be grey outlined, without a grey background.
Inactivity must be nothing but a title, except if modified by the user directly on the calendar (blue
outlined) or created by a rule (orange outlined, currently not possible)."*

Four changes, and the first three are one rule:

- **The outline is now the whole of "who put this here"** (`SchedulerDomain.panelOutline`, three answers).
  Blue is the user's, as before; **orange** is a restrictive period a REPEATING rule laid — the §17 sleep
  windows and the wind-down hours measured back from them — and it is asked as the complement of the first
  question rather than as a list of families, so a fill-laid period family added later is orange for the same
  reason these are. A §15 screen break is not reached by it and needs no exception: a break is not drawn as a
  block at all.
- **A restrictive period of any kind is drawn EMPTY** — outline, label, no fill — and the three DYNAMIC
  periods (§15) are the third colour: **grey**, the recurrence bars being neither a hand nor a standing rule.
  A break the app *conducted* is one of them (`conductedBreak`), which also fixes it reading as a hand-drawn
  inactivity period — it is `auto = false`, so it used to wear the user's blue outline. The §17 sleep windows
  and wind-down hours, which used to be grey bands with no box of their own, are now orange-outlined boxes.
- **`greyPeriodMarks` is gone and nothing replaced it.** A DERIVED "Inactivity" band is now its label and
  nothing else: no outline (nobody placed it) and no marking (the app is reporting a stretch it derived, not
  asserting one). With it goes the last drawing painted ACROSS the timeline to say "nothing is scheduled
  here" — the thing a task legitimately working through a period kept colliding with.
- **The pin box is gone**, and with it `SchedulerIntent.SetPanelPinned` (its only intent). On a period it
  could only ever state a fact — a period is reached by *being a period of its kind*, never by a pin — and on
  a task panel it was a second control on a surface whose marks are otherwise read-only. `pins.existence` is
  untouched and keeps its two ways in: the calendar edit window, and the drag/resize gesture.
- **The two calendar layers became period KINDS** — `no computer unlocked` and `no phone unlocked`. A layer
  was evidence only (the OS lock history, plus "I'm away" for the device the app runs on), so *nobody was at
  a computer here* could not be stated unless the user also claimed the phone was down. Each new kind asserts
  its own layer (`PeriodKinds.assertedLayers`, the one reading) and **restricts nothing on its own** — one
  locked screen is not "no screen". What restricts is the OVERLAP: `assertedNoScreenRanges` intersects the
  two layers' *assertions* exactly as `observedNoScreenRegions` intersects their *evidence*, so the two kinds
  feed the rule `no on-screen task` already has instead of growing one of their own. The implied period is
  subtracted where an explicit "No screen" period already covers, because the plan multiplies the resilience
  of every covering kind and counting a stretch twice would square it.

**One thing deliberately not done:** the override rule (`resolveScreenOverrides`) still asks what the period
the user is *holding* refuses, so laying a one-sided period never evicts on-screen task panels — holding a
"no computer unlocked" period is not holding a no-screen one. The implied no-screen stretch still stops the
fill placing on-screen work there and still stops the bank recording it (including retroactively, at the next
`StripNoScreenRecords`).

### Every notification has a voice — 2026-09-11

→ `shared` (`scheduler/platform/Voice.kt` + the five actuals, `scheduler/engine/SchedulerEngine.kt`,
`scheduler/state/` + `SchedulerStateCodec.kt` + `SnapshotMerge.kt`, `ui/CalendarUi.kt`, `App.kt`);
`docs/PRD_TaskScheduler.md` §11/§15, `docs/invariants/screen-breaks.md`. Tests: `NotificationVoiceTest`
(new), `NotificationLogTest`, `NotificationMuteTest`, `GlobalShortcutReceiptTest`, `BidirectionalSyncTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration**
(the persisted key is deliberately unchanged, see below).

Asked for as: *"Every notification must have a voice now."*

**The voice moved INTO `notifyUser`, which is the whole change.** It used to be two mechanisms: the one
funnel every notification goes through, and — beside two of the call sites — a `speakCue` that played a
pre-rendered WAV. Everything else the app posts (the task to do now, a pose falling due, the wind-down, an
alarm or timer, a chord's receipt, the un-mute receipt) was silent, and would have stayed silent by
omission at every notification site added later. Now the funnel posts **and** speaks, from one text and at
one instant, so the app cannot say one thing and show another and a new site cannot forget the voice.

**A phrase with a recording is named; everything else is synthesized.** `VoiceCue` cannot grow to cover a
notification carrying a task's title, an alarm's label or a chord, so the seam's currency is now a
`VoiceUtterance` — a phrase, plus the cue whose bundled WAV records it when one does. The two phrases PRD
§15 fixes word for word (the look-away's "look 20 feet away" and its "resume your work") pass their cue and
keep the shared Piper voice; the rest go to the platform synthesizer: Piper → SAPI on the desktop (the live
path that was already there as the bundled asset's fallback), `TextToSpeech` on Android (new, and the only
thing on a phone that can say a phrase nobody pre-rendered), `AVSpeechSynthesizer` on iOS. One worker, one
queue, one `stopSpeaking` as before. `spokenNotificationText` turns the two read-me fields into a sentence
— line breaks end sentences, a spaced em dash becomes the comma a reader hears there, a message already
opening with its title does not say it twice.

**The two switches keep their jobs, and the mute grew the louder half.** `notificationsEnabled` silences
**both** halves — a mute that went on talking would not be one — while the voice switch silences the voice
alone, leaving the notifications posting silently. That switch is no longer the look-away cue's: it is the
app's, renamed `notificationVoiceEnabled` and relabelled **"Voice"** in the lateral menu. Its **persisted
and wire key stays `lookAwayVoiceEnabled`**: renaming it would make an older build on the same account read
a new payload as "voice on" and hand nothing back, which is a real regression for no gain a comment cannot
give. The History column is untouched by any of it — it still records what the app decided to say, muted or
not, which is why it was never proof of delivery.

The one voice with no notification behind it is unchanged: the pause-over cue an OS alarm fires on a phone
whose user has walked away from every screen (ADR 0006), where there is nobody to read anything and the app
is not even running.

### A resize cursor that names the SIDE it would take — 2026-09-11

→ `shared` (`ui/PlatformCursor.kt` + the five actuals, `ui/CalendarUi.kt`); `docs/PRD_TaskScheduler.md` §8,
`docs/invariants/calendar.md`, `docs/MANUAL_TESTING.md`. Tests: `CalendarResizeEdgeTest` (common),
`PanelResizeCursorTest` (jvm).
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration**
(nothing persisted or synced changed).

Asked for as: *"when the user hovers over the side of a panel (top, bottom, right, left), the mouse gets the
form of a double arrow, vertical or horizontal, with a line perpendicular to the arrow at the side of the
panel that will be resized or is resized. When a panel is at the furthest in the left of its day column, the
user shouldn't be able to resize from the left."*

**The shape now says WHICH edge, so there are four of them.** The OS offers two — a plain `↕` and a plain
`↔` — and neither can tell a top strip from a bottom one, which is exactly the question the user is asking
when hovering there. So the four glyphs are **drawn** (`PlatformCursor.jvm.kt`): one canonical path — a
double arrow with a perpendicular bar hanging off one end — rotated and mirrored about the hot spot, built
once and cached because this is read from a hover path. The hot spot is the glyph's centre, which is what
lands the bar **on** the edge: a grab strip is measured *inwards* from the edge it belongs to. Off desktop
the fallback is the crosshair it always was. `verticalResizePointerIcon` / `horizontalResizePointerIcon`
keep the plain OS arrows and are now **only** the window frame's, where there is no second panel across the
line for a bar to point at.

**The shape is held for the whole press, not just the hover.** "…that will be resized **or is resized**" is
the second half of the sentence and it did not hold: a resize drag leaves the 6 dp strip within the first
millimetre, so the tile stopped showing the shape exactly when it was saying what was moving. Only an
ancestor of every tile can keep it up, and only with `overrideDescendants` — `resizingEdge` in `WeekView`,
fed by the block's gesture and the `WeightHandle`'s. Nothing is added at rest (null state ⇒ no modifier at
all), so the "a cursor shape rides the hover tile, never a lid over it" rule is untouched.

**Each strip carries its own shape, through one reading per side.** `CalendarResizeStrip` pairs a span with
the edge it takes, so `CalendarHoverTiles` can no longer be handed one cursor for all of them;
`panelResizeEdgeOf` turns the gesture's `CalendarEdge` into a shape and `weightHandleEdge` turns *which half
of a handle* into one. Both readings existed twice before — the tiles split a `WeightHandle` by layout order
and the drag splits it by pointer x — and a half wearing the other half's arrow is precisely the lie the
"the strip the cursor promises is the strip the press grabs" rule already forbids for spans.

**On the left edge: the rule already held, and now it is pinned.** `weightHandles` emits a boundary only
**between** two panels, so a panel at the far left of its day column has never had a strip on its left (nor
the far-right one on its right, nor a lone full-width panel on either) — there is nothing across a column
border to take width from. No code changed for it; what was missing was that nothing said so and nothing
tested it. `CalendarResizeEdgeTest` now holds it across every shape of overlap, weight and partial cover,
including the case that would make the sweep vacuous.

### One "add…" entry on the calendar, and the kind of period is CHOSEN — 2026-09-11

→ `shared` (`ui/CalendarUi.kt`, `App.kt`, `scheduler/state/SchedulerIntent.kt`, `SchedulerReducer.kt`,
`scheduler/domain/PeriodKinds.kt`, `SchedulerDomain.kt`, `scheduler/persistence/SchedulerStateCodec.kt`);
`docs/PRD_TaskScheduler.md` §8, `docs/invariants/calendar.md`, `docs/invariants/scheduler.md`,
`docs/MANUAL_TESTING.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration**
(the new `periodKind` field rides the existing `app_state` payload and defaults to blank on an older one).

Asked for as: *"In the right-click menu on the calendar, all the options to add something are now reduced in
one option. It opens a window that lets the user choose between a task panel and a restrictive period. If
choosing a restrictive period, the user can choose which period (with the same kind of field with a drop-down
menu that is in a task cell to select a category)."*

**The menu's four "add" entries became one `add…`, opening `CalendarAddWindow`.** Three choices — task
panel, **restrictive period**, reminder — each of which opens the editor that already owns that object, so
the chooser itself lays nothing and "nothing is placed until Save" stays one rule. The reminder is the third
choice because it was the fourth entry: it is not a panel of any sort (§14), so it is a peer of the two
panel families rather than a kind of period, and one entry that could not reach it would simply have lost it.

**The point of the change is the kind.** Two of the four entries named a KIND of restrictive period by hand,
which is a funnel with an exception list — and `side-dev/README.md`'s model says a period is a start, an end
and a kind, so `before bed` and every kind the account defines are periods exactly as those two are and had
**no way onto the calendar at all**. The kind is now picked in a `PeriodKindField`: the task cell's categories
drop-down read for a single value, down to creating a new kind through the task edit window's own
`AddPeriodKind`.

Four things had to follow it, and each one deleted an enumeration rather than adding a case:

- **One intent, `AddRestrictivePeriod(kind, start, end)`**, replacing `AddNoScreenPeriod` /
  `AddInactivityPeriod`. The reducer branches on nothing: the title is `PeriodKinds.periodTitle`, and the two
  legacy flags are written only for the two kinds that HAVE one (`legacyNoScreenFlag` /
  `legacyInactivityFlag`) — setting a flag standing for another kind would be a second, disagreeing statement
  of what the period is.
- **"May these two share a stretch" is ONE question:** does the period refuse the task, i.e. is its resilience
  to the kind `0` (`SchedulerReducer.periodRefuses`). `resolveScreenOverrides` trims by it in both directions
  and `stripRecordsUnderPeriod` clears the record by it, so the four cases the code enumerated ("an on-screen
  task panel overrides no-screen periods", "grey overrides every task panel", …) collapse into a sentence
  that also answers for a kind with no flag. It brought an answer the flags could not give: **a period a task
  is resilient to leaves that task's panel alone** — a period scales a share, it does not evict whoever it
  admits. The periods a hand-placed panel may take are now the **user's** (`isUserPlaced`): a break or a sleep
  band is `no task allowed` too, and a panel is placed *through* those (§15/§17).
- **`TaskPanel.periodKind` is persisted and synced** (`PersistedPanel.periodKind`, blank on an older payload,
  `restrictiveKind` still healing the flagged kinds out of the flags). Without it a hand-drawn `before bed` or
  account-defined period decoded as **a block of work** on the next load — it is the only statement of what it
  is that a kind with no flag carries. It is in `schedulingSignature` too, in place of the two flags.
- **A period's paint is derived from its kind, once**, in `calendarRecords`: `noScreen` (no fill + both
  hatches) and `inactivity` (grey) are a DRAWING, and read off the panel's flags a period of any other kind
  carried neither and was drawn as a task panel. `restrictiveKind` rides `CalendarRecord`/`PlacedRecord`
  beside them, and is what "Edit" hands `PeriodEditWindow` — which now takes the kind as a **name** rather
  than an enum of two, shows it, and describes it out of `PeriodKinds.defaultResilience`. The derived
  §17 wind-down bands are split off by panel **id**, never by kind, or a hand-drawn `before bed` period
  would lose its Edit and Remove with them.

`RestrictivePeriodKindTest` (16 tests) pins all of it; the existing 1512 pass unchanged, now speaking through
the one intent. Not done: **the period editor shows the kind but cannot change it** — re-kinding a period is
"Remove" plus a fresh add.

### Anomaly: the task-picker menu would not close — 2026-09-11

→ `shared` (`ui/TaskPickerOverlay.jvm.kt`, `ui/TaskPickerMenu.kt`); `docs/invariants/shortcuts.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"when the user does Ctrl+Shift+Alt+T, the only way to get rid of the menu is to click outside,
then click on the menu, then click outside or press Escape."*

**Two independent bugs, both of them "the menu never got the focus it looks like it has".** Diagnosed by
driving the chord from outside the process and asking Windows — `GetForegroundWindow` + `EnumWindows` —
which window was actually in front, rather than by reading the dismiss path.

1. **`SetForegroundWindow` was asked once and assumed.** Compose does not show the window from the
   composition that builds it (`AwtWindow` sets `isVisible` from a coroutine of its own), so the request
   could land on a window that was not on screen yet; and the call is refused outright unless the caller
   received the last input event, which swallowing the chord in a low-level hook is not. Either way AWT then
   believed itself focused while Windows had never made it foreground — so the real keystrokes went to the
   other application (Escape dead) *and* no deactivation was ever delivered for an activation that never
   happened (`windowLostFocus` never fired, so a press outside was invisible). **No way out at all** until a
   click on the menu handed it the real focus — exactly the three-step sequence reported. Now: wait for
   `isShowing`, ask, and check `GetForegroundWindow` — and if it is refused for good, dismiss rather than
   strand.
2. **Escape was dead even with the foreground.** `fieldFocus.requestFocus()` was keyed on `Unit`, so it ran
   while the window was still taking the foreground, and a Compose focus request made before the window is
   focused is dropped. With nothing focused inside the window there is no path for a keystroke to reach the
   `onPreviewKeyEvent` that reads Escape/Enter/↑/↓ — and the field the invariants say the user can "type
   straight away" into was not taking text either. Keyed on `LocalWindowInfo.isWindowFocused` now.

Found on the way, and the reason the first fix made things worse before it made them better: **activation is
asynchronous**, so the foreground reading taken straight after the request still names the *old* foreground.
A verify-and-retry loop that believes it re-asks forty times a second, each with its own `AttachThreadInput`
either side, and the thrashing handed back the focus it had just won — `windowLostFocus` read the hand-back
as a press outside and closed the menu ~300 ms after it opened. So: re-ask at most every ~200 ms, confirm
twice before believing it, and **arm the dismiss listener only once the foreground is confirmed** — a focus
lost on the way *to* the foreground is not a press outside.

Verified live against a dev build on its own state dir: the menu takes the real foreground within ~150-600
ms, stays up, accepts typing, and closes on Escape and on losing the foreground, across four runs. Not
covered by a `jvmTest` — every part of it is the OS's answer, not the domain's.

### Anomaly: the Notifications column stopped at 2026-09-07 17:25:37 — 2026-09-11

→ `shared` (`scheduler/state/SchedulerReducer.kt`, `SchedulerState.kt`, `SchedulerIntent.kt`);
`NotificationLogTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"in the History window filtered for notifications, the last notification is from
2026-09-07 17:25:37."*

**Nothing had stopped firing.** `diagnostics.log` recorded notifications right through 2026-09-10 (21:36
*Screen break over*, 21:55 the 5-min pose, 22:07 *Task to do now*). What stopped was the **record**: a
read-only probe of the release DB decoded `notificationLog.size = 1000` — exactly `MAX_NOTIFICATION_LOG` —
with `last = 2026-09-07 17:25:37.707`, the reported instant to the millisecond.

The log was a **frozen first-N audit**: `reduceRecordNotification` returned the same state instance once the
log was full, so the ViewModel skipped the persist and every notification after was dropped for good. That
is not a cap, it is an **expiry date** — the column had two months of stale July rows and could never list
another cue. It is now a **rolling tail** (keep the most recent 1000, drop the oldest), the same shape as the
`supabaseUsageLog` beside it, which is what makes it answer the only question it is ever asked: *was the cue
I just missed one the app decided to send?* A DB the old build saturated heals on its own, one eviction per
notification. `RecordNotification` still never requests a server push (per-device diagnostic, unchanged).

Found on the way: the `notificationLog` field's KDoc was **orphaned** — it sat above
`globalShortcutBindings`' own KDoc, documenting nothing, while the field itself carried none. Moved onto the
field.

### Anomaly: every picker row red under a dragged pose — 2026-09-11

→ `docs/invariants/screen-breaks.md`, `docs/invariants/shortcuts.md`. `shared`
(`scheduler/domain/SchedulerDomain.kt`); `TaskPickerTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"the $now line$ is currently dragging a 15min break, which means the $now line$ is not on a
no screen period … However, when doing ctrl+shift+alt+T, all the tasks are in red."*

`restrictiveKindsAt` (new the same day, for the picker's colours) asked "which panels cover the line" —
a **second reading** of what restricts the timeline, and it did not ask the predicate that is documented as
the ONE reading of the drag, `SchedulerDomain.isDraggedScreenBreak`. A dragged pose is materialized as
`[t_p + 1, t_p + d + 1)` at the instant of the fill, so the line sweeps into it long before the next
re-plan pushes it forward; the kind it carries is `no task allowed`, to which every task's default
resilience is `0` — hence every row red, while the fill itself was placing tasks straight through it
(`obstructingSidePanels`, 2026-09-05). The display read now makes the same drop, and
`screen-breaks.md` names both readers so a third cannot be written without it.

Known limit, deliberately not closed: the read does not see mode 2's `no on-screen task` cover
(`DynamicPeriods.awayCover`), which the fill builds for itself and never draws — a restriction the colours
miss while the user is AWAY, which is not a state this chord is struck in.

### The switch entry becomes an epsilon seed, and the picker colours what the line forbids — 2026-09-11

→ `docs/invariants/scheduler.md`, `docs/invariants/shortcuts.md`, `docs/invariants/calendar.md`,
`docs/adr/0011-global-keyboard-shortcuts.md`. `shared` (`scheduler/domain/SchedulerDomain.kt`,
`ui/TaskPickerMenu.kt`, `ui/CalendarUi.kt` — one optional row colour, `App.kt`); `SwitchTaskEntryTest`,
`TaskPickerTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: *"it must simply add in the timeline an epsilon large period with the selected task right
under $now line$, and run the scheduler … all task that have a zero resilience value for the periods the
$now line$ is currently in are red, and orange if the resilience is between 0 and 1."*

- **The entry is epsilon** (`SWITCH_ENTRY_MILLIS`, 2 min → 1 s). The press says which task and when; how
  long is the scheduler's answer. **It does not grow on its own** — a pre-placed block is committed service
  the walk steps over (which also sets its `last`), and the resume rule that continues an unfinished chunk
  reads the recorded past, which a block at the line is not in. The run reaches the task's minimum because
  the `ForcedTaskStart` riding with the seed takes the first slot after it and `PlanWalk.chunkMillis` floors
  that slot — the soft *Minimum Execution Time* goal, yielding to whatever the timeline restricts. Pinned by
  `the_seed_alone_would_hand_the_line_straight_back` and `a_restrictive_period_is_what_keeps_the_run_short`.
- **The picker colours its rows by resilience at the line**: `0` red, `0 < r < 1` orange, `1` uncoloured —
  `PeriodKinds.multiplier` over `SchedulerDomain.restrictiveKindsAt`, the same number the walk races on. The
  id rows under the search field carry it too; title suggestions do not (a title may name several tasks).
  The colours are read at `App`'s **display instant**, which is resampled at the boundaries the panels name,
  so they change as the line enters a new period with no timer of their own — while the list's ORDER stays
  frozen at the press, so it cannot re-order under the pointer.

### Both switch chords leave a two-minute block on the calendar — 2026-09-11

→ `docs/invariants/scheduler.md`, `docs/invariants/calendar.md`, `docs/invariants/shortcuts.md`,
`docs/adr/0011-global-keyboard-shortcuts.md`. `shared` (`scheduler/state/SchedulerReducer.kt`,
`scheduler/domain/SchedulerDomain.kt`, `scheduler/model/TaskModels.kt`, `scheduler/state/SchedulerIntent.kt`);
`SwitchTaskEntryTest` (new).
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: *"When selecting another task to do now with ctrl+shift+alt+Z/T, it must add the period
[now; now+2min] with this selected task, then run the scheduler. This task panel must be blue outlined with a
check box at the top right corner since it is manually added by the user."*

Both presses now lay `[now, now + 2min]` on the task selected (`SchedulerReducer.placeSwitchEntry`,
`SchedulerDomain.SWITCH_ENTRY_MILLIS`) and re-plan around it. The block is an **ordinary hand-placed panel** —
`auto = false` + the existence pin, built through the same two helpers the calendar's own "add" uses — so the
blue outline and the check box come from `SchedulerDomain.isUserPlaced` / `panelPinBoxSpec` unchanged, and
nothing in `CalendarUi` was touched. Pinned, so the immediate re-plan works around it; a Calendar history
unit, so a mis-struck chord is one Ctrl+Z away.

Two corrections the existing tests forced:

- **`+Z` names no task**, so the block had nobody to be about. It reads the one the model already gives —
  `TaskPanel.alternativeTaskId` (`side-dev/README.md` § *Alternative Schedules*, whose documented use is this
  press) — and falls back to re-planning with the refusal standing and reading the line, since that field is
  derived and never persisted.
- **The block defeated the switch**: two minutes counts as a turn taken, so the fill handed the line back to
  the starved task at `now + 2min` (`ForcedTaskSwitchTest` caught it). The entry now always rides with a
  `ForcedTaskStart` on the same task — the block says what was started, the marker carries it past the block —
  and both chords converge on one `startTaskNow`.

### A fifth system-wide chord: the task picker at the pointer — 2026-09-11

→ `docs/invariants/shortcuts.md` (*The task picker*), `docs/invariants/popups.md` (*The one surface that is
NOT drawn inside the app*), `docs/adr/0011-global-keyboard-shortcuts.md`. `shared`
(`scheduler/platform/GlobalHotkey.kt`, `scheduler/domain/SchedulerDomain.kt`, `ui/TaskPickerMenu.kt` and
`ui/TaskPickerOverlay.kt` + its five actuals, `ui/KeyboardShortcuts.kt`, `App.kt`); `TaskPickerTest` (new),
`GlobalShortcutRebindTest`, `KeyboardShortcutsCatalogTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: *"Global shortcut must open a menu at my mouse with a list of tasks, sorted from most recently
touched by now line to less recently … below it is a search bar (a task cell, with the id and title
suggestion lists in edit mode). The task selected here replaces the currently scheduled task. If the user
does the shortcut and presses enter, it selects the first one in the list."*

`GlobalShortcut.PickTask` ships on `Ctrl+Shift+Alt+T` and is rebindable like the other four.
`Ctrl+Shift+Alt+Z` ("Switch task", the blind refusal) is untouched — the two are different questions, and the
lateral-menu button that hints `+Z` still does what `+Z` does.

- The menu is an **OS window of its own** at the pointer (undecorated, always on top, focusable), and it
  **takes the keyboard** from the application in front. It has to: the chord only exists because OmniApp is
  not the focused window when it is struck, and the menu is meant to be typed into and answered with Enter.
  The first surface of the app not drawn inside the app; it is a *menu* by `popups.md`'s rule and leaves on
  Escape, on a pick, or on losing the focus.
- The list is `SchedulerDomain.taskPickerEntries`: every task the plan can be started on, ordered by when the
  now-line was last **on** it (records + real-work panels, a straddling panel counted at the line, a future
  panel not at all), tasks never run last in the identity menus' own order. **The task the line is on is left
  out** — it would otherwise lead the list and make chord-then-Enter re-ask for the task being left; with it
  gone the first row is the task worked before this one.
- The search field is a cell in Edit Mode: the same `EditModeMenuBlock`, its **Tasks** id rows over its
  **Title suggestions**, no Mode selector and no "New task" row. Enter takes the highlighted row while the
  field is empty and the task the field *names* once it holds text (`SchedulerDomain.taskPickerCommit`, the
  one place that rule lives).
- The pick dispatches the **existing** `SchedulerIntent.ForceTaskStart` — PRD §13's "start this task now" —
  so the picker adds no scheduling lever. `isPlaceableTask` (a leaf still in the tree) is now the one
  predicate the reducer and all three menus offering that intent ask, and `calendarTitleSuggestions` /
  `calendarTaskIdForTitle` were renamed `placeableTask*` to say so.

### The pause a screen break was taken in IS the break — 2026-09-10

→ `docs/invariants/screen-breaks.md`, `docs/adr/0003-screen-breaks.md` (post-mortem at the end), PRD §15.
`shared` (`scheduler/domain/DynamicPeriods.kt`); `ScreenBreakTakenWhileAwayTest` (new),
`ScreenBreakChainPullBackTest`, `DynamicPeriodsTest`, `RestPosePresenceWindowTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: *"I got a notification for a 5min screen break, did the 5min break, woke the app up, and saw in
the calendar an inactivity period instead of the 5min break."* Confirmed off the release account's own
diagnostics: `17:36:20 notification [Screen break] take a 5min pose`, then `device sleep 6min (17:36:16 →
17:42:55): now-line swept in mode 2`, and nothing drawn over those six minutes but the grey band.

The requirements' pull-back (*"when a 'no on-screen task' period touches the start of a dynamic restrictive
period … the period now starts at the start of this chain"*) shipped for exactly this case in ADR 0003 and was
reaching almost nothing. Three rules each erased the break on their own:

- **A rest stretch was barring the break it was the taking of.** *"After any ≥ 5-minute stretch … no 5min
  period in the next 1 hour"* — and a five-minute pause is exactly long enough both to BE a 5-min pose and to
  bar one, so the occurrence was pushed an hour past the pause and nothing touched its start any more. The bar
  is about what comes **after** a stretch; the break placed at the stretch's own start is not after it
  (`barStretch`'s `spared`, asked per label against every label's current bar).
- **The mode-1 drag carried the owed pose over the pause to the now-line.** The past-side re-derivation asks
  with the mode the account is in NOW, and the user is back at the screen — the same "the mode is the
  JOURNEY's, not the arrival's" mistake `sweepMode` exists to prevent, in the display path. The drag now puts
  an owed pose **down** at the first `no on-screen task` chain it meets, and picks it back up where that chain
  is too short to have taken it.
- **Coming back undid the pull-back.** "The chain ends in `[now line, +∞)`" was read as a question about where
  the line is now, so the break sat at the pause's start while the user was away and left it the instant they
  returned — the **frozen past** broken by a mode flip. It is now *the chain reaches the line, **or** it
  outlasted the break*, the second being a fact of the past that never changes.

`chainStartTouching` → **`chainTaking`**, which is where all three answers and both refusals now live. Falling
out of it: **a chain gives each of the three one occurrence** and is an ordinary rest stretch to it afterwards
— without that the break's re-anchor lands back in the chain that just took it and the walk crawls a
millisecond at a time until `MAX_STEPS` stops it.

Visible consequence, stated because it is new on screen: a break falling due inside **any** `no on-screen
task` chain is now drawn at that chain's start — a night, a long pause, a period the user drew.

### The calendar's info bubble writes its times to the second — 2026-09-10

→ `docs/invariants/calendar.md`, PRD §8. `shared` (`ui/CalendarUi.kt`); `CalendarBubbleSectionTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: in the calendar, in the info bubble, the times must include the seconds.

- **Every section's times are now `HH:MM:SS`** — the start–end range of a task panel, a screen break, a
  no-screen or inactivity band, a sleep window, a layer, and the single instant of the two zero-duration
  markers (a §14 reminder tag, a §18 alarm/timer ring). The phone's contextual-menu panel info follows,
  being the same content by spec.
- **Why it was wrong at the minute.** The bubble is the one surface that answers *when exactly is this*. A
  20-second look-away (§15) truncated to a range with two equal ends, and every derived band is cut at the
  millisecond a device locked or a session opened — so two abutting bands read as overlapping.
- **One funnel, `bubbleTimeRange`.** The five call sites that each built their own `"$a – $b"` (the block
  overlays, the break band, a sleep window's wider no-screen line, `placedTimeRange`, the phone menu) now
  read it, and it is where the "∞" open-bound rule lives — an absent bound stays "∞" at any precision.
- **The editors keep `formatHm`.** Their fields parse `H:mm` and commit on the minute, so a hand-edited
  bound lands on `:00` seconds; that is a statement about the edit, not about the bubble.

### Overlapping "No screen" periods unify instead of splitting the column — 2026-09-10

→ `docs/invariants/calendar.md`, PRD §8. `shared` (`scheduler/domain/SchedulerDomain.kt`,
`scheduler/state/SchedulerReducer.kt`, `scheduler/persistence/SchedulerStateCodec.kt`); new
`NoScreenPeriodUnifyTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: a manually added no-screen period that overlaps another must not share the width of the day
column — they simply unify.

- **The bug was that two periods reached `overlapLayout` at all.** Nothing merged them: `resolveScreenOverrides`
  only ever trims across the *screen* boundary (a period vs. the task panels), and the display merge
  (`groupSameTaskPanelsForDisplay`) requires a non-null `taskId`, which no period has. So two overlapping
  periods were two blocks, and Overlap Mode's width split — the answer for panels genuinely competing for
  the same hours — was applied to two statements of the same fact.
- **`SchedulerDomain.unifyNoScreenPeriods` is the rule, and it is one funnel.** It fuses every strictly
  overlapping run of `noScreen` panels into the union (identity-stable when there is nothing to fuse, so the
  common edit pays nothing). `resolveScreenOverrides` runs it **first**, whatever panel changed, so the
  override and the record strip act on the fused span; `SchedulerStateCodec.toHealedState` runs it on decode,
  so a DB an older build wrote is healed rather than surfaced (CLAUDE.md § *Persisted-DB compatibility*).
- **Deliberately not fused:** periods that only **abut** (they already draw full-width, and each stays
  separately removable) and a no-screen period against an **inactivity** one (different kinds, different
  statements). Everything generated is out of reach by construction — `AddNoScreenPeriod` is the only
  producer of `noScreen = true`, so no sleep band, screen break or conducted break can be swallowed.
- Within a run the survivor is the panel named by `keepId` — the one the user is holding — so a drag onto
  another period does not vanish under them; it keeps its id, pins and layout weight, and only its bounds grow.
  The whole fuse is one Calendar history unit, so Undo restores both periods.

### The user's own blocks say so: a blue outline and a pin box — 2026-09-10

→ `docs/invariants/calendar.md`, `docs/invariants/scheduler.md`, PRD §8/§9. `shared`
(`ui/CalendarUi.kt`, `App.kt`, `scheduler/domain/SchedulerDomain.kt`, `scheduler/state/`); new
`CalendarPinBoxTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Asked for as: everything manually placed by the user is outlined in blue with a pinned check box at the top
right; resizing a scheduler panel makes it a pre-placed block; unpinning it hands the stretch back.

- **`SchedulerDomain.isUserPlaced` is the one question**, asked as the COMPLEMENT of what the app lays down
  itself (`!auto && !chore && !isRegeneratedPanel`) rather than as a list of what qualifies — the list would
  be the fourth copy of the same enumeration, and a new family of generated panel would quietly acquire an
  outline. It drives `CalendarBlockBody`'s accent border (`USER_PLACED_BORDER_DP`, a step thicker so it reads
  on a task whose own colour is already blue) and the new `PanelPinBox` at the top right.
- **A drag or a resize IS the existence pin** (`pinsAfterHandPlacement`). It was not: `onCommitBounds` handed
  the reducer the panel's own pins, so dragging one of the fill's panels made it user-authored and
  **unpinned** — exactly the shape `fillSchedule` deletes. The re-plan the edit itself triggers then wiped it,
  so a resize on a scheduler panel had no lasting effect at all.
- **The pin box is the edit window's Existence switch from the other side** — `SetPanelPinned` writes the same
  `pins.existence` through the same `derivePinned`, as one undoable Calendar delta. Unpinning re-plans because
  `pinned` is in `schedulingSignature`; nothing dispatches a fill of its own.
- **The frozen past no longer reads `auto`.** `fillSchedule` kept the elapsed head of a straddling **auto**
  panel only, so the other panel the cut takes — one the user has just unpinned — lost its elapsed half. The
  head is now kept for any task panel and becomes an ordinary auto panel, which is what lets
  `mergeSameTaskPanels` fuse the re-planned tail back onto it.
- **A restrictive period is pinned in the box and never in the scheduler.** `derivePinned` gained a
  period-aware overload: a hand-drawn period carries `pins.existence` but never `pinned`, or `isSchedulerFixed`
  would enter it in the walk's pre-placed blocks — a block owned by no task — on top of the period it already
  is. A period has no "still drawn, no longer obeyed" state, and "Remove" is how it goes away, so its box
  cannot be a switch: an **inactivity** period shows it checked and inert, and a **no-screen** period shows
  **none at all** — it is a decorative panel with no body of its own for a mark to sit on, so the box would be
  two reasons for the same nothing. Both keep the outline, which still says who drew them.
- **`panelPinBoxSpec` is the one place that decides which blocks wear a box**, off the record alone; the
  composable decides only whether there is ROOM for it. A rule the drawing owns is a rule no test can reach.
- **A hand-drawn no-screen period loses its fill**: outline only, with both layers' oblique lines over it.
  Those are *asserted* regions, so `layerRegions` does not clip them to the now-line and a future period is
  hatched exactly like a past one. The stale PRD line giving a **screen break** a blue outline of its own is
  gone with it — a break is the app's period, not the user's.

### A ring names itself in the hover bubble — 2026-09-10

→ `docs/invariants/calendar.md`, `docs/invariants/alarms-and-timers.md`, PRD §8/§18. `shared`
(`ui/CalendarUi.kt`); new cases in `CalendarBubbleSectionTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Reported as: hovering a timer's end on the calendar shows no information about it at the top of the bubble.

- **An INERT element owes the bubble what it hides just as much as a clickable one, and it is the case that
  goes unnoticed.** A §14 reminder tag is a pointer-input node, so leaving it silent showed up at once as a
  bubble naming *nothing at all* — which is how that got fixed. A §18 ring registers no input, so the hover
  tiles under it went on reporting: the bubble named the task panel the marker was sitting on and never the
  ring, which looks like a working bubble rather than a broken one. The test is opacity, not interactivity.
- **`alarmBubbleSection`, and the marker carries its own `CalendarHoverTiles`** over `underPanelOverlays` —
  the same funnel a `ScreenBreakBand` and a `ReminderTag` read, not a fourth reading of "what is under the
  cursor here". The three elements drawn over the panels now each stack what is below them: the markers add
  nothing, the bands add `alarmOverlays`, the tags add both.
- **The section names the INSTANT; the tiles ride the DRAWN rectangle.** Rings coinciding within a marker's
  height stack downward, so a marker can sit below its own time — the same split as a tag's (an overdue one
  rides the now-line). The stacking sweep moved out of the drawing pass into `alarmPlacements`, derived once
  and read by both, rather than being repeated for the overlays.
- **The section carries the icon** (⏳ / ⏰), through the one `alarmMarkerIcon` the marker itself reads. It is
  the only thing that tells an alarm's ring from a timer's — the labels fall back to a time of day and a
  duration, which do not reliably differ — so a bubble without it would say less than the marker it stands in
  for.
- **The order gains one rank**: `reminder > alarm/timer ring > task = break > inactivity = sleep > layers`.
  The two zero-duration markers lead it, the tag over the ring, which is the order they are drawn in.
- Also corrects PRD §18's stale *"Not drawn on the calendar"* bullet, left behind by *A running timer marks
  the calendar* (2026-08-29).

### Modes 2 and 3 are one placement; the difference is the cue — 2026-09-10

`scheduler/domain/DynamicPeriods.kt` (`lineIsCoveredAt`, `breaksAreNotifiedAt`, `chainStartTouching`,
`instances`), `scheduler/domain/SchedulerDomain.kt` (`cueCrossings`, `fillSchedule`).
`ScreenBreakChainPullBackTest` (new), `DynamicPeriodsTest`, `TpModeTest`, `DraggedPoseNoIdlingTest`.
`docs/scheduler_requirements.md` § *$now line$ and 3 Dynamic Restrictive Period*, restated by the user.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Three deltas, all from the restated requirements:

1. **Mode 2's placement is mode 3's, verbatim.** The requirements now state them in one clause — *"Mode 2 &
   3: $now line$ must be covered by the period 'no on-screen task'"* — where mode 2 used to read *"covered
   ... but not one of the three dynamic periods"*. A dynamic period's kind is `no task allowed`, which covers
   "no on-screen task" a fortiori, so the second predicate (`breaksAreTakenAt`, mode 3 only) had mode 2
   placing the three where mode 3 did not. It is deleted; `lineIsCoveredAt` is the one predicate the
   placement reads the mode through, and **mode 2 no longer drags an owed pose onto the line**.

2. **What tells the two apart is the CUE** (`breaksAreNotifiedAt`, read once, in `cueCrossings`): in mode 2 a
   screen break is placed and drawn but **never announced** — every screen of the account is locked and
   nobody has said they are taking a break. Modes 1 and 3 announce. The crossing is dropped rather than
   swallowed downstream, so nothing marks it announced. The wind-down is not a screen break and is unaffected.

3. **A dynamic period is pulled back onto the start of the `no on-screen task` chain that touches it**
   (`chainStartTouching`) — the requirements' new last bullet, and their one sanctioned exception to the
   frozen past. The reported symptom was the other side of it: with the line moving in mode 2 or 3, a break
   *disappeared* instead of staying in the past. Any emptiness absorbs a period, so a break falling due inside
   a running pause was pushed to the end of the stretch — which IS the now-line, and goes on being the
   now-line for as long as the user stays away, so the break rode the line and never happened. Pulled back, the
   minutes already spent away count towards it, it is over and frozen where it began, and what reaches from its
   end to the line is the ordinary cover — an **Inactivity** band, or **Sleep** inside a §17 window. The break
   is never STRETCHED. Refused in one case, which is mode 1's own rule and not an exception to this one: a
   pose pulled back far enough to cover `t_p` keeps the drag instead.

Nothing was needed for the two oblique layers over that stretch: in mode 2 this device's OS lock scan hatches
its own layer and a peer that cannot be asked is assumed locked, and in mode 3 the away button feeds its own
layer, drawn dotted.

### A task's sub-list placeholder comes off the shared cell counter — 2026-09-10

`scheduler/state/SchedulerReducer.kt` (`applySetCellTitle`, where a task is minted its sub-list).
`SchedulerReducerTest`: `re_minting_a_sub_list_never_re_mints_a_live_cell_id`. PRD §1 *Constraint 1*.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Anomaly: *the user can put the same task id several times in the same sub-list.*

The sub-**list** id is derived from the task id (`<task>/children`) — one sub-list per task, for the life of
the account, which is right. Its placeholder **cell** was derived the same way (`cell/<task>/children/0`)
— which is not: a cell id must be minted once, ever, and `SchedulerState.allocateCellId` is the counter that
says so. A cell **keeps its id when it is dragged elsewhere**, so the two rules collided:

1. name a cell → its task gets `T/children` holding `cell/T/children/0`;
2. name that placeholder and **drag it out** into another list — it keeps its id;
3. empty the parent (a blank title is what deletes), so `pruneDetachedTree` collects `T/children` and puts
   `T.childListId` back to `null`;
4. name `T` again through any surviving cell ⇒ the sub-list is re-minted, and `cells[cell/T/children/0] =`
   **overwrote the dragged-away cell where it now lived**.

That cell was left in two lists' `cellIds` at once, blanked, with `parentListId` naming the new one. Every
rule that asks *what is already in this cell's list* reads `parentListId` — `siblingTaskIds`, and through it
`canAssignTaskId` and `eligibleAssignTaskIds` — so it answered about the wrong list and Constraint 1 stopped
being enforced for the list the cell was actually drawn in: the same task id could then be assigned to
several cells of one sub-list. (It also silently threw away that cell's task binding.)

Found by fuzzing the reducer against the invariant rather than by reading: a randomized intent walk over
`SchedulerState.empty()`, checking after every step that no list holds one task twice and that every cell in
a list names that list as its `parentListId`. The mismatch always came first, and always from
`SetCellTitle`. A read-only probe of `~/.omniapp-release/scheduler-state.db` (and of accounts 1 and 2)
found **no persisted damage** — 420 cells, 0 duplicates, 0 mismatches — so no healing migration is needed.

Still open, and **older than both** of these entries (it reproduces identically with the pre-2026-09-09
rule): the cycle Constraint 2 forbids can still be closed in two moves, because each assignment is only
checked against the ancestor path **as it stands at that instant**. Point a cell inside `T`'s sub-list at
`T` while that sub-list's parent cell holds something else (legal — `T` is not an ancestor yet), then point
the parent cell back at `T` (also legal — that cell's own ancestor path is empty). The tree is then
reachable from itself and `visibleOccurrences` recurses until the stack overflows.

### The Change Task id menu filters on the ancestor path, not the ancestors' whole sub-trees — 2026-09-09

`scheduler/domain/SchedulerDomain.kt` (`assignCollisionScope`, and the two readers of it —
`canAssignTaskId` / `eligibleAssignTaskIds`). `SchedulerReducerTest`:
`eligible_assign_task_ids_hide_shared_descendant_parents_set` rewritten as
`eligible_assign_task_ids_offer_a_shared_descendant_parent`, plus a new
`typing_existing_title_in_an_empty_cell_offers_the_id_row_under_a_mirroring_ancestor`. PRD §1 *Constraints*,
PRD §4 *Filtering*.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Anomaly: *in an empty cell I type an existing task title and I don't get the id suggestion list.*

The filter was the "parents set" / shared-descendant rule (added 2026-06-21, `f9e2b07`): a candidate whose
own sub-tree shared **any** task with the union of the ancestors' whole sub-trees was hidden. That is a
fourth constraint the PRD never states — §4 *Filtering* is "already in the same list, or in the cell's
ancestor path", Constraint 1 forbids a repeat within one **list** (two sub-lists under one parent are not
one), and recurring under many parents is Constraint 3's mirroring, which is what the tree is for. It was
also a second answer to a question the tree already answered: `canMoveTaskIntoList` asks the target's
ancestors against the moving task's descendants, so the very layout the menu refused could be built by
**dragging** the cell there.

Measured on the release account (a read-only probe of `~/.omniapp-release/scheduler-state.db`): of the
24 168 (empty cell, existing title) pairs in the tree, **7 503 — a third — were hidden by that rule alone**,
which is why the menu so often had nothing under "New task" and, with the exact title typed, no menu at all
(Menu 2 drops the exact match by design). The scope is now the ancestor **path**, read against the
candidate's own structural sub-tree, so Constraint 2's cycle (a candidate that holds one of the cell's
ancestors) stays refused — 855 pairs — as do the 93 same-list ones. Nothing else moved.

### A weight-table pin is the account's, not the open window's — 2026-09-09

`scheduler/model/TaskModels.kt` (new `PriorityWeightPin`), `scheduler/state/SchedulerState.kt`
(`priorityWeightPins`, per sub-list), `SchedulerIntent.TogglePriorityWeightPin`,
`scheduler/state/SchedulerReducer.kt` (`reduceTogglePriorityWeightPin`, `remapPriorityWeightPins`, and the
column-index helpers `addColumnIndex` / `deleteColumnIndex` / `moveColumnTarget` the applies and the remap
now share), `SchedulerStateCodec` (`PersistedPriorityWeightPins`), `sync/SnapshotMerge.kt` (merged per table
as a whole value), `scheduler/ui/TaskSchedulerScreen.kt` (the window reads the state instead of a
`remember`). New `PriorityWeightPinTest`. PRD §5, `docs/invariants/priorities.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Anomaly: *pin a value in the priority weights table, close the window and open it again — the value is not
pinned any more.*

That was the spec ("pins belong to this open table and are discarded when it closes") and the code was a
`remember(listId)` keyed on the table. The spec is now the opposite one the relative-priority window already
followed: a pin is a statement about how the next solve distributes, so it is authoritative user data —
persisted, synced, and still **not** an Undo/Redo unit (it moves no priority). Because a pin names its
column by index, the three structural column edits carry the table's pins with them; a reset does not (it
moves no column). The one asymmetry, stated in the invariants: undoing a column *move* puts the columns back
and leaves the pins where the move carried them, which is what keeping pins out of history costs.

### The lateral-menu button no longer closes a window that something is standing over — 2026-09-09

`ui/WindowFrame.kt` (`WindowFrameHost.frontId`), `App.kt` (`focusedWindow()` reads it; `focusWindow` also
takes the host focus). New `WindowFrameHostTest` cases. `docs/invariants/popups.md`,
`docs/MANUAL_TESTING.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Anomaly: *when the Alarm window is open and the priority weights window is in focus, clicking the Alarm
button on the left-side menu closes it instead of bringing it back to the top.*

The button's three branches (open it / close it / bring it to the front) turned on "is this the topmost
**lateral-menu** window?", and a per-object window is not one — so with the weight table over the Alarms
window, Alarms was still topmost by that reading and the button took the "you are already here" branch. It
now asks `WindowFrameHost.frontId`, the top of the *whole* stack: the table on top makes the answer null and
the button brings Alarms back. `focusWindow` also moves the host focus now, so a window raised from the menu
is the focused window — otherwise the second click could never read as "close", and the keyboard stayed with
whatever had been focused before.

### One stacking order for every window: the priority weights table no longer sits on top — 2026-09-09

`ui/WindowFrame.kt` (`WindowFrameHost.stackOrder` / `zOf` / `raise`, `Modifier.windowStackZ`, the frame
applies its own z, a restored chip raises its window), `ui/PopupWindows.kt` (`TransientPopupLayer` takes the
frame id and carries the z), `App.kt` (its own `windowStack` deleted, every `zIndex(100f)` top-layer wrapper
removed, `focusedWindow()` read off the one stack), `ui/CalendarUi.kt` + `ui/TaskTreesWindow.kt` (the `Box`
pairing a window with its companion window carries the z), `ui/CategoryEditWindow.kt`,
`scheduler/ui/TaskSchedulerScreen.kt`. New stacking tests in `WindowFrameHostTest`.
`docs/invariants/popups.md` (§ *What is drawn OVER what*), `docs/adr/0014-window-frame.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

Anomaly: *the priority weights table is in the top layer, even when another popup window is in focus.*

The frame refactor above deleted outside-press dismissal but left the z-order the old model justified: `App`
kept a stacking order for the twelve lateral-menu windows and pinned **every per-object window above all of
it** on a fixed `zIndex(100f)`. While such a window vanished on the next press, "on top" and "focused" were
the same sentence; for a window that stays they are not, so the weight table stood over every window opened
after it. There is now one order (`WindowFrameHost.stackOrder`) holding lateral-menu and per-object windows
alike, raised when a window opens, when a press lands inside it, and when a reduced window is picked back up
off the bar. The two implementation details worth remembering: `zIndex` orders a node only among its own
siblings, so the z goes on a window's **outermost** element (the full-screen `TransientPopupLayer` and the
companion-window `Box` were invisible lids over the frame's own z); and a window whose `register` has not run
yet reads as the **top**, or a newly opened window flashes under its neighbours for a frame. The reduce bar
deliberately keeps its own order — the one windows were opened in — so no chip jumps when a window is raised.

### Every window keeps the same frame, and no window vanishes on a click outside — 2026-09-09

New `ui/WindowFrame.kt` (`WindowFill`, `WindowFrameState`, `WindowFrameHost`, `AppWindowFrame`,
`MinimizedWindowBar`), `ui/PopupWindows.kt` rewritten (`TransientPopupHost` → `TransientMenuHost`, menus
only; `transientPopupCard` deleted; `MessagePopup` is an ordinary window), every window migrated to the
frame — `ui/CalendarUi.kt` (calendar, reminders, history + its row-info window, the entry / period /
reminder / constraint editors), `ui/AlarmWindow.kt`, `ui/CategoriesWindow.kt`, `ui/CategoryEditWindow.kt`,
`ui/DefaultSubtreeWindow.kt`, `ui/ShortcutsWindow.kt`, `ui/SleepWindow.kt`, `ui/TaskListWindow.kt`,
`ui/TaskRelationsWindow.kt`, `ui/TaskTreesWindow.kt` (+ its detail window),
`scheduler/ui/TaskSchedulerScreen.kt` (priority weights, relative priority, task edit, period kind, deep
copy) — plus `App.kt` (both hosts, the reduce bar, geometry persistence), `scheduler/ui/TaskTreeView.kt`
(focus-based keyboard ownership), `scheduler/persistence/WindowPlacement.kt` (its `width`/`height` columns
are now written). `TransientPopupHostTest` → `TransientMenuHostTest`, new `WindowFrameStateTest` and
`WindowFrameHostTest`. PRD §6/§7/§13, `docs/invariants/popups.md` rewritten, `docs/adr/0014-window-frame.md`,
`docs/MANUAL_TESTING.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no schema migration.**

User spec: *no pop-up window disappears when clicking outside, except right-click or drop-down menus; every
window has a head to drag it with the three usual buttons plus one to fill the width and one to fill the
height; reduced windows appear at the bottom of the app, over the left-side menu; a maximized window fills
the app except the left-side menu, and the whole app when the menu is retracted; every window is resizable
by its left, right or bottom edge; double-clicking the head maximizes and un-maximizes.*

What that ends is the **two sorts of pop-up**. "Sort 2" — about ONE object, therefore gone the moment
anything else took focus — covered most of the app's windows and threw away whatever was half-typed in them.
The half of it worth keeping (only one window per subject) was never the outside-press rule: each opener
already holds a single slot, so opening the second window replaces the first by construction. Only menus
still close on an outside press, and that press still does its normal job (PRD §13).

The frame itself is one composable rather than twelve copies of a title bar. Consequences worth knowing:

- **maximize is `WindowFill.Both`**, not a state of its own, so the two fill buttons in turn land on the same
  window as the maximize button, and un-maximizing restores the normal geometry per axis;
- **every window now declares a default width AND height** and puts its content in a `weight(1f)` slot. The
  `heightIn(max = …)` caps had to go: a window whose height is its content's cannot be given another height
  by dragging its bottom edge;
- the calendar's private `clampOffsetY` became `WindowFrameState.clampVertical` and now keeps EVERY window's
  head between the top of the content area and its lowest row;
- **the keyboard rule follows the focus, not "something is open"** (`WindowFrameHost.keyboardClaimed`): a
  window that stays open would otherwise hold the keyboard off the task tree forever;
- a **reduced** window is measured and not placed, so it keeps everything typed into it;
- window **sizes** now persist (the `window_placement` columns existed already, unused); reduced/filled do
  not — they are facts about the session.

### A scheduler run shows the rule state it READ and the set of rules it RETURNED — 2026-09-09

`domain/SchedulerDomain.kt` (new `SchedulerRunRules`, new `describeScheduleRules`, `fillSchedule`'s
`rulesSink` now reports both halves and the inner fill's sink is `ruleStateSink`),
`domain/DynamicPeriods.kt` (new `modeLabel`), `state/SchedulerState.kt` (`SchedulerRunEntry` gained
`ruleState`, `nowMillis`, `tpMode`; `rules` changed meaning), `state/SchedulerReducer.kt` (`recordRun`
carries the two parameters), `ui/CalendarUi.kt` (the row's counts, and two info sections where there was
one), `SchedulerReducerTest`, new `SchedulerRuleSetTest`, PRD §6, `docs/MANUAL_TESTING.md`,
`docs/invariants/scheduler.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.**

Reported anomaly: the History window's scheduler rows called their one list *the set of rules*, and what it
held was the **rule state** — the tasks, their priority percentages, their minimums, their resilience values.
That is the scheduler's INPUT. `docs/scheduler_requirements.md` names the two separately (§ *Rule State
Definition* against § *System Overview*), and the window was showing the question in the place reserved for
the answer.

So the run now carries both, as two things:

- **`ruleState`** — unchanged text, `describePlanRule`, one line per schedulable task. Renamed to what it is.
- **`rules`** — new: what the fill RETURNED, one line per instruction. `+h:mm:ss → +h:mm:ss  run <task>  else
  <alternative>` for a pick, `restrict [<kind>] <label>` for one of the three dynamic periods.

The offsets are **from the now-line**, and the mode is stated on the list's first line, because that is what
the requirements mean by rules *"parameterized by $now line$ and $now line$ mode"*: the same list read at
another position of the line is the same list, naming another schedule. `SchedulerRunEntry` records both
parameters for the same reason — an instruction list that did not name them would name no schedule. The row
now counts both (`N tasks · M rules · K panels`) and the information window has a **Rule state** section and a
**Set of rules** section, each copyable on its own.

What counts as a returned rule: the picks the fill made (`TaskPanel.auto`) and the dynamic periods it placed
(`screenBreak`) — the two things the fill *decides*. Pre-placed blocks, user-drawn periods, sleep windows and
reminder tags are the § *Starting timeline*: input, already in the rule state or authored by hand, and
repeating them would make the answer indistinguishable from the question again. Only the future is listed
(§ *frozen past*), and the list is capped at `MAX_DESCRIBED_RULES` = 500 with a `… N more rules` tail, since
this is a RAM-only log of the last 50 runs and a week of horizon is a few thousand instructions.

The description is taken off what `fillSchedule` RETURNED, in the wrapper, rather than collected inside the
fill: the fill has several exits and a list assembled at one of them would be a second, partial reading of the
same answer.

### The History window filters by undo chord — 2026-09-09

`state/SchedulerState.kt` (new `HistoryChord` enum + `HistoryCategory.chord`), `ui/CalendarUi.kt`
(`HistoryFilterConfig.chords`, a third drop-down), new `HistoryChordFilterTest`, PRD §6,
`docs/MANUAL_TESTING.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.**

A third field beside the two origin fields: **any** (default), **`Ctrl+Z`**, **`Alt+←`/`Alt+→`**, or **both**.
It narrows the History-Unit half, so it greys out with the window field when the check box hands the filter to
the other sources — nothing the app produces itself is walked by a chord.

The mapping is stated once, on `HistoryCategory.chord`: Edit / Calendar / Main answer `Ctrl+Z`, Selection
answers `Alt+arrows`, **WindowNav answers neither** — it is recorded for this window and no undo/redo command
reaches it (PRD §7). That last one is why **"both" is the union of the two chords and not "everything"**, and
why the default entry is *any* rather than *both*: a window-navigation unit would otherwise vanish from the
default view. `SchedulerReducer.contentCategory` picks WHICH `Ctrl+Z` category a keystroke lands on and has to
stay inside that mapping; `HistoryChordFilterTest` pins both halves — what the property says, and what the two
chords actually move.

### The History window filters by WINDOW, and rows open on a double click — 2026-09-09

`state/SchedulerState.kt` (new `HistoryWindow` / `HistorySource` enums, `HistoryUnit.window`, new
`SchedulerRunEntry`), `state/SchedulerReducer.kt` (new `activeWindow` and `recordSchedulerRun` seams,
`commitDelta` stamps the window, the two plan reductions report a run), `domain/SchedulerDomain.kt`
(`fillSchedule` gained an optional `rulesSink`, new `describePlanRule`), `ui/TaskSchedulerViewModel.kt`
(the RAM-only `schedulerRuns` log), `App.kt` (`historyWindowOf` + `activeHistoryWindow`, fed by
`bringWindowToFront` and the content Box's raise-on-press), `ui/CalendarUi.kt` (the whole filter menu and
information window), `persistence/SchedulerStore.kt` + `SchedulerStateCodec.kt` +
`SqlDelightSchedulerStore.kt`, **`Scheduler.sq` + new `11.sqm` (schema v11 → v12)**,
`SchedulerReducerTest`, new `SchedulerRunLogTest`, `SchedulerHistoryWriteTest`, `AlarmHistoryTest`,
PRD §6, `docs/invariants/persistence.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.**

The filter was seven source chips (five history categories + notifications + Supabase usage), each toggled on
its own. It is now the two ORIGIN fields the spec asks for, each a drop-down, with a check box saying which of
them filters:

- **Window** — where the change was made; default "all windows", every window of the app in the list.
- **Other source** — what the app itself produced: the scheduler engine, notifications, the Supabase API.

A unit could not answer the first question. `HistoryCategory` is the Ctrl+Z stack it walks, not the window it
was authored in — `Main` alone holds tree mutations, alarms, timers, categories, relations and rebound chords
— and `SchedulerState.focusedWindow` is the §7 focus target, which only five of the twelve windows claim, so
reading either would have filed a Categories edit under the task tree. So the unit now RECORDS its window
(`HistoryUnit.window`, `history_unit.window`), stamped once at commit from an injected
`SchedulerReducer.activeWindow` the shell feeds from the one funnel every window's press already goes through.
NULL on the rows an older build wrote, and those stay listed under "all windows".

The two fields **partition** the list rather than intersecting it, which is what "the check box selects one of
the two fields" means and what keeps the default view from being drowned by the Supabase log (one row per HTTP
call — the reason that chip used to start off).

**Scheduler-engine rows are new.** A re-plan is still not a History Unit (§9: a schedule is derived, so nothing
undoes it), but the two plan reductions now report the run *and the set of rules it read* — one line per
schedulable task with its priority share, §10 minimum and resilience, straight out of the `PlanTask` list the
fill builds, via an optional `rulesSink` the display fills never pass. It is a RAM-only, capped, per-session
log on the ViewModel: the rules are derivable from the state by definition, and a rolling tail of them in
`app_state` would put hundreds of KB of text on the save path ADR 0007 exists to keep short.

**Double click, not click**, opens a row's information window, and every kind of row now has one (a scheduler
run's rules are the thing worth copying). Each stored info carries its own clipboard button plus a "Copy all";
the single click is left alone because the list is inside a `SelectionContainer` and a click has to stay the
start of a text selection.

`11.sqm` is an `ALTER TABLE ADD COLUMN`, so `window` lands AFTER `delta` — the one column allowed to, because
no save reads it (a unit is immutable, so its window takes no part in the digest that decides row reuse) and
the only reader is the full load, which walks `delta` regardless. A rebuild in 10.sqm's style would have
rewritten the release account's whole history on first launch to buy nothing.

### Ctrl+Shift+Z redoes — one reading of the undo/redo chords — 2026-09-09

`ui/KeyboardShortcuts.kt` (new `undoRedoIntentFor`, and the catalogue entry split in two),
`scheduler/ui/TaskTreeView.kt`, `ui/CalendarUi.kt`, `ui/AlarmWindow.kt` (all three route through it),
new `UndoRedoChordTest`, `docs/invariants/shortcuts.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.**

Reported as: delete a timer with a title, Ctrl+Z brings it back, Ctrl+Z takes the title off, **Ctrl+Shift+Z
deletes the timer again** — a third undo where every other application redoes.

The three surfaces that own the keyboard each spelled the chord test out for themselves, and all three wrote
`ctrl && key == Z -> undo` without looking at Shift, so Ctrl+Shift+Z fell into the undo branch. It was never
an Alarms-window bug: the tree and the calendar had answered Ctrl+Shift+Z with a second undo for as long as
they have had the handler. Three copies of one rule agreeing on something wrong is the failure mode
`CLAUDE.md` calls *one rule, one funnel*, so the fix is the funnel and not three edits: `undoRedoIntentFor`
now reads **Ctrl+Z → undo, Ctrl+Shift+Z → redo, Ctrl+Y → redo** (Cmd counting as Ctrl), and the three
surfaces call it. The rule proper takes the four facts that decide it and the `KeyEvent` overload is only the
adapter, so the contract is pinned by an ordinary unit test rather than by a synthetic key event.

### The Alarms window's two lists are undoable — 2026-09-09

`state/SchedulerState.kt` (`Delta.coalesceKey` / `Delta.coalesceOnto`, `AppWindow.Alarms`),
`state/SchedulerIntent.kt` (`SetAlarms` / `SetTimers` carry an `editKey`), `state/SchedulerReducer.kt`
(new `AlarmsDelta` / `TimersDelta`, `reduceSetAlarms` / `reduceSetTimers` commit them, `commitDelta`
merges a unit onto the one at the pointer when both carry the same key),
`persistence/SchedulerStateCodec.kt` (`PersistedDelta.Alarms` / `.Timers` + shared row converters),
`ui/AlarmWindow.kt` (re-seeds its local rows from an outside change, reports each field's focus session,
and catches Ctrl+Z / Ctrl+Y itself), `App.kt` (`appWindowOf(Alarms)`), new `AlarmHistoryTest`,
`AlarmTest` / `TimerTest` (the two tests asserting the old rule now assert the new one),
`docs/invariants/alarms-and-timers.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.**

Reported as: the bin button on a timer row was pressed by mistake, Ctrl+Z did not bring it back, and the
History window showed nothing. Both were true and neither was a bug in Undo — `reduceSetAlarms` and
`reduceSetTimers` said in their own KDoc that they were "not routed through the Undo/Redo history" and
committed no unit at all, so there was nothing to walk and nothing to list.

**What is now a unit** (all on `Main`, so Ctrl+Z / Ctrl+Y; nothing in this window is a *selection*, so
Alt+←/→ walks none of it): adding a row, striking one off with the bin, and every settings field of both
sections — an alarm's time, label, weekdays, ring length, vibrate, repeat and its on/off switch; a timer's
duration, label, ring length and vibrate.

**What is deliberately not**: the five timer run-state writes (start/resume, pause, reset, a countdown
component typed, a ± nudge). Their currency is an absolute due instant measured against a moving now-line,
so a delta replayed later does not mean what it meant when recorded — undoing a pause would restore an
instant now in the past and the timer would ring on the spot. They also already carry their own inverses on
the row, which the bin does not. Nor is anything the app authors itself: the engine disarming a one-off that
has rung (`SetAlarmEnabled`), the reset after a ring, a healed row, a peer's pull.

**One unit per field-focus session, not per keystroke.** The window pushes its whole list on every keystroke,
so a five-letter label would otherwise be five units of the 1000-unit cap for Ctrl+Z to walk back one
character at a time. `Delta.coalesceKey` names the gesture (`"{rowId}/{field}@{epoch}"`, minted when the
field takes the focus) and `commitDelta` merges a unit onto the one at the pointer when the keys match,
keeping that one's `before` side. A structural change carries no key and so can never be absorbed into the
text edit before it. The key is **not persisted**: a unit reloaded from the DB has closed its gesture, so a
key minted in a later session cannot collide with it.

**Follow-up, same day — the chord still reached nobody.** The first cut made the window focusable only while
it was the front one and requested the focus once, when that flag flipped. The bin defeats it: the user
clicks the new row's label field (the focus goes into that `TextField`), then clicks the bin, which removes
the row **and the focused field with it** — Compose clears the focus, nothing re-requests it, and Ctrl+Z
reaches no handler. Diagnosed off a read-only copy of the release DB: the `timers` units were all there
(`timer-11` added, labelled, removed — twice, 110 s apart) with the Main pointer never moving, `editSession`
null and `focusedWindow` `Alarms` throughout, so the routing was right and the keystroke simply was not
delivered. The fix is the calendar's rule, which this window should have copied in the first place:
unconditionally `focusable()`, focus claimed on open, and **reclaimed on every press inside the window**
through the same `raiseOnPress` that raises it (the Initial pass, which does not consume the press, so a
field under it still takes the caret). `keyboardActive` is gone.

Two things had to be fixed with it, or the units would have been unreachable:

- **The window's local row copies never re-seeded.** They were `remember {}` with no key, seeded once, so an
  undo (or a sync pull, or the engine's disarm) changed `state.alarms` without the window ever hearing —
  the struck-off row would have stayed on screen and been pushed back at the next keystroke. They now re-seed
  exactly when the incoming list is not the one this window last pushed (for timers: not the one it last
  pushed *the settings of*, so a start never reformats a half-typed duration).
- **Ctrl+Z could not reach the window.** The chord lives on the task tree's and the calendar's key handlers
  and there is no app-level one, so a keystroke aimed at a floating window reached nobody. The window now
  previews it itself, and is focusable while it is the front window — which is also why `AppWindow` grew an
  `Alarms` entry: `contentCategory` only lands on `Main` when the focus is on neither an Edit session nor the
  calendar, so without it a Ctrl+Z after touching the calendar would have walked the calendar's stack and
  skipped these units entirely.

### One root, drawn as a row — `main` folded into `root` — 2026-09-09

`model/WellKnownIds.kt` (`ROOT_TASK` / `ROOT_LIST` / `ROOT_CELL` / `ROOT_CELL_LIST`; `MAIN_TASK` and
`MAIN_LIST` deleted), `domain/SchedulerDomain.kt` (`withRoot` — the one definition of the shape —
`rootCellId`, `displayRootListId`, `renderViaOf`, `isMainTask` deleted, `ancestorTaskIds` and
`absoluteTaskPriorities` fenced off from the root, `pruneDetachedTree` seeding `ROOT_CELL_LIST`),
`domain/RelativePriority.kt` (`ancestorCells` stops at the root), `state/SchedulerState.kt` (`empty`,
`keepingRoot` on all three tree swaps), `state/DefaultSubtreeProjection.kt`,
`persistence/SchedulerStateCodec.kt` (the JSON id migration + `toHealedState`), `sync/SnapshotMerge.kt`
(`repair` heals), `ui/TaskTreeView.kt` + `ui/TaskSchedulerScreen.kt` (the drawing starts at the root row),
new `RootCellTest` and `RootTaskMigrationTest`, `SchedulerReducerTest`, `GoToTaskTreeTest`,
`docs/PRD_TaskScheduler.md` §2/§3/§4/§5/§6/§13, `docs/invariants/task-tree.md`,
`docs/invariants/priorities.md`, ADR 0004 and ADR 0012.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy. It DOES migrate the local
DB and the synced payload on load: every device must be rebuilt, and a device left on an older build will not
understand the new ids (see *Downgrading* below).**

Two changes that had to be made together.

**The tree is drawn under one inert `root` row.** It is drawn **compact** — the expand arrow and nothing
else, wrapping its content rather than spanning the tree's width: no title, no percentage, no minimum time,
no categories. It names nothing the user wrote, so a title (`root`) and a percentage (always 100 %) would
only restate what the row's position already says, and a full-width band would read as a separator. What is
left is the arrow, which collapses the whole tree, and enough of a target to right-click — because it is
also **the one cell that is not selectable and still has a contextual menu** (`TaskCellMenuActions.onEdit`
is nullable for it), holding the entries that are about the tree as a whole. "Edit task" is not among them:
that window is a task's screen switch, schedule unit and text document, and the root has none of those.

Underneath, it is a real cell of the tree (`ROOT_CELL`, alone in `ROOT_CELL_LIST` one level above
`rootListId`), not a synthetic header — so exactly one thing draws a task
row, one thing decides the visible order, and the expansion set answers for the root as it does for every
other parent: collapsing it collapses the tree. `rootListId` deliberately did **not** move onto it; it still
names the list of the tree's *top-level tasks*, which is what the priority walk, the colour ring, the category
scopes and the path labels all mean by "the root". The root cell is reached the other way round — it is that
list's `parentCellId` — and a null there is exactly what says "this drawing has no root row", which is how
PRD §4's template and PRD §7's "All tasks" window keep drawing without one.

**And there is now only one root.** There used to be a conceptual `task/root` whose single child was
`task/main`, whose cells lived in `list/main`. That second level existed so sibling trees could hang beside
`main` — and named task trees (PRD §6) are how the account actually holds several trees, so it answered
nothing. `task/main` and `list/main` are gone; the survivor is `task/root` / `list/root`, titled `root`.

The hazard was never the rename, it was that **a real cell at the top of the tree is one more level whose
sub-tree is the whole tree**. Three answers had to be fenced off from it, and each was a real bug for the
length of one test run:

- `ancestorTaskIds` / `ancestorCells` now **stop** at the root cell. Counting it made every task an ancestor
  of every cell, so `assignCollisionScope` refused **every** assignment — no cell could be pointed at any task
  again — and every category scope label grew a `root / ` prefix.
- `absoluteTaskPriorities` **leaves the root out** of its map. It answers `1.0` so the walk terminates, but it
  is what the percentages are a share *of*, not a row holding one: returning it put a second `1.0` beside the
  tasks dividing that `1.0` up, and every caller summing the map counted the tree twice (the leaves summed
  to 2.0).
- The root cell is **not a render-via** (`renderViaOf`). A render-via names which occurrence of a *mirrored*
  parent a row is drawn under, and the root can never be mirrored. Left as one, the top-level rows would have
  taken a non-null via in the tree while the "All tasks" window and the template still drew them with none —
  one selection, highlighted in one drawing and invisible in the other two.

**The migration runs on the parsed JSON, not on the typed model** — because `task/main` is named from far
more places than the tree: relative-priority pin keys, task-relation keys, a category rule's legacy
`scopeTaskId`, every stored task tree, the default sub-tree, and the whole tree snapshot inside **each history
unit**, which is its own row and its own decode call. One pass over the JSON at every decode entry point is
the only shape that reaches all of them (one rule, one funnel). It rewrites an id only where the string is the
whole value or the whole map key: cell ids embed their list's id (`cell/list/main/3`) and survive byte for
byte, or every expansion, selection and rule scope in the payload would stop resolving. The one accepted false
positive is a task *titled* `task/main`, which is retitled. `SchedulerDomain.withRoot` then heals the shape
itself, and is called from the four places a tree can arrive: `empty`, the codec, `SnapshotMerge.repair`, and
`applyTree` — that last one because an undo into pre-upgrade history replaces the cells, lists and tasks
wholesale and would otherwise take the root away with them.

**Downgrading.** The whole-tree category scope is written **blank** rather than as the root's id: a blank has
decoded as "the whole tree" in every build there has ever been, where `task/root` would be unresolvable to a
pre-rename build, which drops the rule instead of keeping it account-wide. Nothing else is downgrade-safe —
an older build reading a migrated payload finds no `task/main` and rebuilds an empty root — so this is a
one-way migration for the account, and every device should be rebuilt together.

### The hover bubble names a reminder — 2026-09-06

`ui/CalendarUi.kt` (`CalendarBubbleSection.Kind.Reminder` + the re-numbered ranks, `reminderBubbleSection`,
`underPanelOverlays` hoisted out of the screen-break block, `underReminderOverlays`, `ReminderTag` rebuilt
around its own `CalendarHoverTiles`), `CalendarBubbleSectionTest`, `CalendarHoverTilingTest`,
`docs/PRD_TaskScheduler.md` §8/§14, `docs/invariants/calendar.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy, no DB migration, no
state change of any kind (this is drawing).**

Hovering a §14 reminder tag now pops the info surface, naming the reminder's title and the time it is FOR —
and, under it, the panel / band / layer the tag is drawn over, the reminder first. Before this the tag was
the one element on the calendar that reported no hover at all: it is emitted last (it is the one marker that
has to stay clickable) and it is a pointer-input node, so it won the hit test against the tiles beneath it
and they stopped receiving Enter/Move — the bubble simply went blank over a tag.

So the tag owes the bubble what it hides, the same debt a `ScreenBreakBand` already pays through its
`underOverlays`, and it now reads the same list (`underPanelOverlays`: every task/period panel by its span,
then the grey periods and the layers) plus the screen breaks, which are under a tag too. The section order
gains a rank above everything: `reminder > task = break > inactivity = sleep > no computer unlocked = no
phone unlocked`.

Two things it must not become. The click lives on the tag's OUTER Box, an **ancestor** of the hover tiles —
a sibling tile layer would be the "lid over the tile" mistake with the roles swapped, eating the one click on
the calendar that has to land. And the reminder's own line is its **due** time, never where the tag sits: an
overdue tag rides the now-line and a checked one freezes at the instant it was ticked off, and neither
answers "when is this reminder for". What it *hides* is read at where it is drawn, off the quantized display
instant like every other derivation.

### The priority-weight table's default row — 2026-09-06

`model/TaskModels.kt` (`CellList.defaultWeights`), `domain/SchedulerDomain.kt`
(`defaultWeightRow`, the one place the row is read), `state/SchedulerIntent.kt`
(`SetPriorityDefaultWeight`; `RestorePriorityWeights` carries the row), `state/SchedulerReducer.kt`
(the four column operations carry it, `applySetCellTitle` and `applySetPriorityWeightTableRow` seed from it,
Cancel restores it), `ui/TaskSchedulerScreen.kt` (`WeightTableDefaultRow`, `WEIGHT_WINDOW_LEADING_WIDTH`, the
pin made optional), `persistence/SchedulerStateCodec.kt`, `sync/SnapshotMerge.kt`, new
`PriorityWeightDefaultRowTest`, `PriorityWeightTableRowTest`, `SchedulerReducerTest`,
`docs/PRD_TaskScheduler.md` §5, `docs/invariants/priorities.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration
(the field rides the existing JSON payload and defaults to the built-in row on every older one).**

Under the blank add row at the bottom of every priority-weight table there is now one more row, **default**,
with a weight field per column and no pin. It says what a task **arriving** in that table is given — named in
the task tree, or added to the table by hand as an optional row: both arrivals now read the same row through
one funnel, where the first got a hard-coded `[1, 0, …]` and the second a hard-coded zero. It is read at the
**naming**, not when the placeholder cell was minted, because a placeholder sits at the bottom of a list for
as long as the list exists and would otherwise hand out whatever the row said before the user edited it.

Its own default is 1 in the first column and 0 in the rest — exactly what a new task used to be given — so an
account that never touches it behaves as it always did, and every payload written before the row existed
decodes to it. An added column is 0 in it like everywhere else, and a moved / deleted / reset column carries
it like any other row. It states nothing about what is already in the table: no chart slice, no priority sum,
not in `treeSignature`, so editing it re-plans nothing — but it is one undo/redo unit, Cancel puts it back
with the headers and the weight rows, and it is persisted and synced per sub-list.

While drawing it: the column headers were sitting 72 dp left of the weight fields under them (the rows draw
an expansion-arrow column and a percentage column the header row did not account for). Both the header and
the new row now read one `WEIGHT_WINDOW_LEADING_WIDTH`, so the table lines up down every column.

### Three outlines, a selection that stays put, and a menu that lets the next click through — 2026-09-06

`ui/TaskSheetChrome.kt` (`SheetColors.editBorder`, `TaskCellOutline` + `taskCellOutline` /
`borderWidth` / `borderColor`), `TaskSchedulerScreen.kt` (the cell's border reads that one function; both cell
menus are non-focusable and register with the sort-2 host; the edit field's caret follows
`LocalTreeKeyboardOwned`; the screen title's click handler removed), `TaskTreeView.kt` (the tree's empty-space
tap removed, `LocalTreeKeyboardOwned` published), `ui/PopupWindows.kt` (`transientMenuDismissal`),
`SchedulerReducer.kt` + `SchedulerIntent.kt` (`ClearSelection` and `reduceClearSelection` deleted;
`reduceFocusWindow` moves focus and nothing else), `SchedulerReducerTest`, new `TaskCellOutlineTest`,
`TransientPopupHostTest`, `docs/PRD_TaskScheduler.md` §3/§4/§7/§13,
`docs/invariants/task-tree.md`, `docs/invariants/popups.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Three changes to what a task cell says and what a click means, all in the same corner:

**Edit Mode has its own outline.** The three states were meant to be told apart by the border alone, but the
main selection and the edited cell shared one 2 dp blue, so only two of the three were ever visible. Edit Mode
now takes a 2 dp `editBorder` instead — its own hue, not a third weight, because it is not a third degree of
selection but the state where the keyboard writes into the cell. The ranking that turns three overlapping
flags into one drawing is now a single function (`taskCellOutline`), because three surfaces draw task cells.

**Nothing but another task cell moves the selection.** Clicking beside the tree, on the screen's title, or
into another window used to clear the selection and force Edit Mode to exit (PRD §4's old "Forced Exit" read
that widely). It no longer does any of it: the selection is where the user left it until they put it
somewhere else, and a rename survives a glance at the calendar. What follows the keyboard instead is the
**caret**: the field gives it up while another window owns the keyboard and takes it back on return, so
keystrokes aimed at that window never land in the rename left open behind it. `ClearSelection` is deleted
rather than left unused — an intent that clears the selection is how the old behaviour comes back.

**The right-click menu no longer eats the click that closes it.** A focusable `DropdownMenu` consumes the
outside press for its own dismissal, so clicking a second cell while the menu stood only closed the menu. Both
cell menus are non-focusable now and dismiss through the app-root observer every other sort-2 pop-up already
uses — which never consumes — so that press goes on to select the cell it landed on, as PRD §13 asks.

### A timer's countdown is editable before it is started — 2026-09-06

`TimerDomain.withRemaining` (an idle row banks instead of returning unchanged) + `withCountdownField` (the
idle guard dropped; only a *running* row is stopped by a typed seconds value), `AlarmWindow.kt`
(`countdownEditable` is now "the row exists" rather than "it is running or paused"; *Resume* is enabled on a
paused row whatever the Duration field says), `SchedulerIntent` docs, `TimerTest` (three domain tests
rewritten/added, one reducer test added, the no-op test re-pointed at an edit that really changes nothing),
`docs/PRD_TaskScheduler.md` §18, `docs/invariants/alarms-and-timers.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Dialling in how long *this* run is to be before pressing anything is the ordinary way to use a timer, and the
three countdown fields and the ± buttons were inert until it was running. They are live in all three states
now. An idle row edited here **banks the amount and so becomes paused** — no fourth state was invented for it,
because a countdown set up but not started *is* a held one — which is why the button then reads **Resume**:
what is about to run is no longer the Duration, so *Start* would be misnaming it.

The Duration stays the row's one setting: no countdown edit writes it, `reset` still returns to it, and a start
from a genuinely idle row still takes it — the "two fields writing one number by two routes" that the old
read-only idle row was avoiding is avoided by keeping the two numbers apart, not by refusing the edit. Two
edges fall out of `withRemaining` being the single primitive: an idle row retyped as the number it already
showed banks nothing and stays idle (so *Start* only becomes *Resume* once something really moved), and a
paused row is never normalised back to idle by a nudge that lands on its duration, because a paused row is
always written in its own currency.

### A right-click selects the cell it lands on — 2026-09-06

`TaskSchedulerScreen.kt` (`contextMenuModifier` gains `key` + `onSelect`; `TaskRow`'s
`selectOnSecondaryPress`, and its `selectionPointerModifier` now returns on a secondary press),
`docs/PRD_TaskScheduler.md` §13, `docs/invariants/task-tree.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Right-clicking a cell opened its §13 menu without selecting it, so the menu named a cell the tree still drew as
unselected — and with another cell as the sole selection, the entries that act on "the cell" and the block the
user could see disagreed. The press now runs the ordinary `ClickCell` intent (no ctrl, no shift, no
`forceClearMulti`) before the menu opens, which is what makes both cases fall out of the one existing rule: a
cell outside the selection becomes the new main selection alone, a cell inside a multi-selection keeps the
block and only moves *main*, which is exactly what `contextMenuCopyTargets` then copies.

It fires from `contextMenuModifier` because that is the handler certainly reached — it is dispatched first and
consumes the press — and the percentage column, which consumes the press itself, carries its own `onSelect`.
The paired half is the early return in `selectionPointerModifier`: a right-click must not run the left-button
machinery, whose deferred single-click reset would have collapsed the multi-selection 300 ms after the menu
opened over it. Cells with no menu of their own (empty placeholders, root/main) select too — the rule is about
the click, not the menu — so `contextMenuModifier` no longer returns a no-op merely because `enabled` is false.

### The plan is reduced off the frame loop (ADR 0009) — 2026-09-06

`SchedulerEngine` (`planDispatcher`, `dispatchPlan`), `TaskSchedulerViewModel.dispatch` (compare-and-set),
`App.kt` + `SchedulerHolder` (both pass `Dispatchers.Default`), `CalendarUi` (`remember` around the four
per-column `overlapLayout`/`weightHandles` calls), new `PlanOffTheFrameLoopTest` (3) and `PlanConcurrencyTest`
(2), three new `PerfBenchmarkTest` rows, `docs/invariants/display-hot-path.md`,
`docs/invariants/scheduler.md`, `docs/PERFORMANCE.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

One `fillSchedule` measures **25-80 ms** (`PerfBenchmarkTest`, growing with the task count) and both hosts run
the engine on a **main-thread** scope — the composition's on desktop, the foreground service's on Android — so
every re-plan spent four visible frames on the frame loop, at exactly the moment the user had stopped typing
and the 1 s rule-change debounce fired. The engine now reduces its two expensive plan intents
(`RefreshSchedule`, `ExtendSchedule`) on a `planDispatcher`. Nothing about the *rule* moves: the same intents
go through the same reducer, so there is still one definition of a re-plan. The in-reducer re-plans
(`ForceTaskSwitch`, `ForceTaskStart`, `SetSleepSchedule`, `RemoveRecordPeriod`, the no-screen strip) stay
synchronous — they answer a press and must land before it returns — and `planDispatcher` defaults to `null`
(reduce inline), so every existing test keeps its ordering.

That makes two threads read-modify-write `SchedulerState`, which is why `dispatch` now publishes with
`MutableStateFlow.compareAndSet` and re-reduces when it loses. Without it a 60 ms plan would publish a state
snapshotted from *before* a keystroke that landed inside it, and the keystroke would vanish a second after it
was typed — indistinguishable from the sync clobbers in ADR 0007, and it would have been blamed on them.
Retries are counted (`reduce.contended`), not bounded: a bound has to choose between dropping the intent and
clobbering the winner and both are wrong.

Separately, the calendar's per-column `overlapLayout` / `weightHandles` are now `remember`ed on their block
lists. They are small (0.24 ms for a typical day, 1.2 ms for 100 blocks) but were recomputed three times per
`DayColumn` on every recomposition `App`'s body causes, a task-tree keystroke included.

**Two of the four items in the report that prompted this were already handled** and are recorded here so they
are not "fixed" again: `encodeSnapshot` (2.2 ms) already runs on `Dispatchers.Default`, never on the UI
thread, and its history walk is already memoized per unit; and the `App` body's ~45 display derivations
measure **0.7 ms per recomposition** in total, so memoizing them would trade a documented staleness risk
(`docs/invariants/display-hot-path.md`) for a fraction of one frame. The lesson is in `docs/PERFORMANCE.md`
under *What the table is and is not*: a per-call cost is not a bottleneck until it is multiplied by a rate and
placed on a thread that owes somebody a frame.

### Ctrl+C and the cell menu copy the TASK ID (PRD §4/§13, ADR 0012) — 2026-09-06

`SchedulerDomain.taskIdReferenceText` / `TASK_ID_REFERENCE_PREFIX` / `CopiedNode.reference`, `parseTreeText`,
`SchedulerReducer` (`reduceCopySelection`, `pasteNodeInto`), `TaskTreeView`, `TaskSchedulerScreen`,
six new `TaskCellCopyTest` cases, PRD §4/§13, `docs/invariants/task-tree.md`, ADR 0012.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

The cell contextual menu's **"copy"** row is now **"copy task id (ctrl c)"**, and `Ctrl+C` is the same gesture.
Both write the identity and nothing else — one `OmniApp task id: task/user/41` line per selected cell, a
sentence no other application writes — and pasting it onto a cell **puts that task id there** (the ordinary
mirror: the task's own sub-list is what shows under it). A reference the tree cannot honour is a **no-op**, not
a blank-titled husk: the shape is settled before the ids are read, so a malformed one cannot fall through to
the title-tree parse and paste a task *titled* after the reference line, and such a node may only ever Mirror.
A title that reads like a reference is escaped, as an attribute-like title already was.

**`Ctrl+X` is unchanged** — still the entire sub-tree plus the §4 deletion, because a cut must be able to put
back everything it deleted. The consequence: the only non-destructive whole-sub-tree copy is now **"deep copy"**
(unlimited switch on), which is the window a sub-tree copy was always asked for. The three copy switches govern
the sub-tree copies only; an identity has no fields to leave out.

### The priority solve ADDS where no factor can reach — 2026-09-06

`RelativePriorityDomain.setChainsShare` split into `scaleChainsShare` + `shiftChainsShare` (+ `maxShiftFor`),
`CategoryRulesTest` (four tests), PRD §5, `docs/invariants/priorities.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Setting the "motivation" category to **50 % of root** on account 3 was refused. It was not a conflict with
any other rule: the root list's weight columns are `[0.9, 1.0]`, so the first is worth 90 % of the list and
the second the remaining 10 %, and every root cell but `find a job` and `health` sits at **0** in the first.
A factor multiplies, so a 0 stays a 0 — `motivation`'s share was capped at 10 % however large its weight
grew (measured: 0.1 landed, 0.11 did not). The cap is a fact about how the cell happens to be written down,
not about what was asked, so the refusal gave the user nothing to act on.

`setChainsShare` is now two stages. **A factor first**, unchanged, because it is the only move that leaves
every ratio the user set intact. **Where no factor lands, one common term is added** to every weight value
of the same unpinned chain cells (never below 0, pins re-held at their percentage, bounded by `maxShiftFor`
the way `maxScaleFor` bounds the factor). Adding reaches the column the cell was absent from and *removes*
the cap rather than raising it — afterwards the cell carries a value in every column, so every later
re-establishment is an ordinary factor again. The fallback is kept only when it lands closer than the factor
did, so it can never undo one. Verified on the account itself: 50 % now lands exactly (`motivation`'s row
becomes `[2.27, 3.27]`, `find a job` 45→21 %, `health` 46→22 %), the nested `"a" at 50 % of motivation` rule
is still held, and every target from 5 % to 95 % is reachable where only ≤10 % was.

This **replaces** the same day's fifth structural contradiction (a `maxChainsShare` ceiling that named the
refusal precisely): the case it named is no longer impossible, so the check and the bound are gone.

**Not done — the column header weights are still never touched**, and the header pins stay inert. Both ways
of moving them are worse than the added term, measured on the account: scaling them by the same factor is
**not monotone** (the cascade `absₙ = hₙ·(1 − Σ preceding)` makes a smaller factor worth more to a later
column while that same factor shrinks the cell inside it) — it peaks at **1.6 %** near `f≈0.7` and collapses
to 0 by `f≈1.2`, where the headers clamp at 1, which is *worse* than the 10 % the cells alone reach and is a
hump no bisection can solve; and adding to them drives the headers to `[1, 1]`, making every later column
worth nothing and taking **eleven of the thirteen root tasks to 0 %**. The added term over the cells reaches
every share those knobs could and touches nothing outside the chains.

### Performance measurement tooling — 2026-09-06

`shared` (new `perf/` package + `ui/PerfOverlay.kt`), `desktopApp/main.kt`, `scripts/perf-profile.bat`,
`docs/PERFORMANCE.md`, new `PerfBenchmarkTest`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**
Off in every release build: the recorder is gated on `DebugFlags.PERF` (`-Pomniapp.perf`, unset by
`createDistributable`), and every instrumented site is an `inline` that returns on one static boolean read.

The app had **no performance instrumentation at all**, so "it is not smooth" could only be answered by
reading code. Two tools now answer it by measuring:

- **In-app overlay** (`scripts/perf-profile.bat`) — fps / p95 frame interval / jank from `withFrameNanos`
  (which *observes* the frame clock rather than requesting frames, so an idle app reads as idle), process
  CPU / heap / GC / threads, and every instrumented section ranked by **milliseconds per wall-clock second**.
  Rates, not totals: a 4 ms derivation is free at 1 Hz and is 40% of a core at 100 Hz, and only the rate tells
  them apart. Plus recomposition counters (`recompose.App`, `recompose.DayColumn`), per-intent reducer timings
  (`reduce.<Intent>`), live collection gauges, and a post-GC leak sampler whose verdict is a least-squares
  slope per hour — the raw heap sawtooths, so only the floor after a forced collection is retention.
- **Headless benchmark** (`PerfBenchmarkTest`) — per-derivation medians, plus one real gate:
  `display_derivation_cost_follows_the_visible_window_not_total_history` asks the same visible week with a
  week and then a year of stored history behind it and requires the same cost. That is CLAUDE.md's hot-path
  rule as a **ratio**, so it needs no wall-clock budget and cannot flake on a slow runner.

The script keeps two rules the measurement depends on: **time simulation OFF** (the sim clock's ~20 Hz
now-line inflates every display-path cost by an order of magnitude, so a profile under it measures the
simulator) and **never the release state dir**.

Instrumented: the ~12 unmemoized display derivations in `App.kt`, `SchedulerDomain.fillSchedule`,
`recordsForDay` / `overlapLayout`, `SchedulerStateCodec.encodeSnapshot`, `SqlDelightSchedulerStore.save`,
and every reducer dispatch.

### Creating a task no longer expands its cell — 2026-09-06

`shared` (`state/SchedulerReducer.kt`) + `DefaultSubtreeTest`, PRD §4, `docs/invariants/task-tree.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Anomaly: with the default sub-tree switch on, every task typed into a cell unfolded the template under it.
`endEditSession` force-added the seeded cell to `SchedulerState.expanded` ("show what was just created rather
than leaving it folded away"), so the row the user had just written jumped down the screen behind a block of
rows they had not — on every single creation.

Creating a task is not asking to see the template. The force-expand is gone; `applySetCellTitle` already drops
a cell from `expanded` where it **mints** the sub-list, so a created cell is simply left collapsed and nothing
puts it back. The gestures that mean to open it are unaffected: the expand arrow, `Tab` into the child, and
§13's **"add default sub-tree"** — which is the *asking*, and still expands every cell it walked.

One consequence, and it is the honest one: clicking the arrow mid-session (a PRD §4 *Forced Exit*) is now the
session's "Edit" unit **plus** the toggle's own unit, exactly as a forced exit followed by any other expand
arrow already was. It used to be one unit only because the graft had already answered the click.

### A mode-3 layer is hatched with DOTTED lines — 2026-09-05

`shared` (`domain/SchedulerDomain.kt`, `ui/CalendarUi.kt`, `App.kt`) + `CalendarLayerTest`, PRD §8,
`docs/invariants/calendar.md`, `docs/MANUAL_TESTING.md`, ADR 0002.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

New spec sentence: *"the periods where $now line$ mode goes to 3, the oblique lines of no computer unlocked are
dotted if there was at least one computer unlocked with the app having the 'I'm away' button clicked. Same
thing for the oblique lines of no phone unlocked."*

Since 2026-09-05 the "I'm away" button hatches its own device kind's layer (ADR 0002), which is what makes a
mode-3 stretch carry both layers and read as a no-screen period. It also made the calendar draw *"no computer
unlocked"* over a stretch where a computer was demonstrably unlocked — the user had to leave one running to
press the button on it. The dots say which of the two it is without changing anything else: same slope, same
spacing, same colour, same span, same bubble section, so `intersect(layerA, layerB)` is still exactly a
no-screen period. **The dots are a drawing, not a classification.**

`SchedulerDomain.declaredLayerRegions` decides the sub-stretches — the declaration MINUS this kind's lock
evidence, intersected with the band drawn — and `App.kt` emits one layer record per stretch of each kind
(`CalendarRecord.layerDeclared` → `obliqueHatch(dotted = …)`). Two rules inside it:

- the **lock evidence wins** where it overlaps (the button survives a lock — only an unlock clears it — so a
  declaration routinely runs on into a real standby, and nothing is unlocked there). It reads the same
  `layerEvidence` funnel `layerRegions` draws from, clipping and sub-minute seam filter included, or a standby
  flicker too short to hatch would still slice a dotted band into hairlines of solid line;
- an **asserted region does not** — a sleep window, a screen break or a hand-added no-screen period is a promise
  about every screen, and a promise cannot un-unlock the machine the button was pressed on. `null` (a peer's
  assumed-locked layer) dots nothing, and a peer carries no declaration anyway: no channel brings one.

### A period the now-line DRAGS obstructs nothing — 2026-09-05

`shared` (`domain/SchedulerDomain.kt`) + `DraggedPoseNoIdlingTest` (new), `BreaksAndSlidingPrioritiesTest`,
`SchedulerSchedulerTest`, `ScreenBreakKindTest`, `docs/invariants/screen-breaks.md`, `docs/MANUAL_TESTING.md`,
ADR 0003.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Reported on account 3: the now-line was dragging the 15-min pose and a grey **"Inactivity" band grew behind
it** — on an unlocked machine, with tasks free to run and no period saying otherwise.
`docs/scheduler_requirements.md` forbids that twice over: § *No idling* (*"anywhere that is not covered by
restrictive periods which would prevent any task from being scheduled, the scheduler must schedule a task, for
any $now line$ and $now line$ mode"*) and mode 1's own clause, which says what the line does instead in as many
words — it *"would continuously delay that period (while creating task panels in its passing)"*.

The fill was planning around the dragged pose as though it were a fixed block. It is not: a dragged pose is
ahead of the line at **every** position of the line, so no instant of the timeline is ever inside it. Two
things followed, and the second is the visible one:

- the half-open `(t_p, t_p + d]` leaves exactly `[t_p, t_p + 1)` free, so the fill placed **one millisecond**
  of work at the line and then idled for the pose's whole length; and
- every later re-plan regenerated the pose at the NEW line, so the whole stretch the line had swept since the
  pose fell due came out with no panel at all — and the calendar draws whatever the past leaves uncovered as a
  derived grey band, so it grew at exactly the rate the line moved.

So a period the line drags is now **drawn but not planned around** (`SchedulerDomain.isDraggedScreenBreak`,
marked on the panel id via `DRAGGED_BREAK_ID_SUFFIX` — a dynamic period's panel is derived and regenerated by
every fill, so nothing persisted carries it). Only the fill's `restrictions` lose it: it is still drawn at
`(t_p, t_p + d]`, still announced on its due, and still re-anchors the recurrence bars off itself, so "you owe
a break" is unchanged. **Modes 2 and 3 keep the drag as an obstacle** — mode 3 does not drag at all (the
account said the break is being taken, so the pose really happens), and mode 2's rule is that `t_p` IS covered
by "no on-screen task", so the passing there creates coverage rather than task panels and the grey band behind
the line is correct. `DraggedPoseNoIdlingTest` pins all three, plus the swept stretch coming out solid across
two fills ten minutes apart. The three existing "nothing runs inside a break" assertions now exclude the
dragged instance, which is the only behaviour change they saw.

### A mode-3 period is drawn as one: "I'm away" hatches a layer — 2026-09-05

`shared` (`domain/SchedulerDomain.kt`, `domain/SchedulerProgressive.kt`, `engine/SchedulerEngine.kt`,
`App.kt`) + `CalendarLayerTest`, `AccountAwayModeTest`, `docs/scheduler_requirements.md`,
`docs/invariants/{calendar,screen-breaks}.md`, ADR 0002, ADR 0003.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

The requirement: *the periods where some devices have the "I'm away" button clicked and the others are locked
must be covered by a "no phone" layer and a "no computer" layer, and the now-line is in mode 3 on that kind of
period and only there.* Half of it was missing, and the missing half is the button itself.

**A layer had exactly one source, the device's OS lock history — and the button's whole point is that the
machine stays UNLOCKED.** So the OS was silent over precisely the stretch the app was calling mode 3, and the
calendar drew no hatch across a period it had itself decided nobody was at a screen for. The button now feeds
the layer of **its own device's kind**: `SchedulerEngine` keeps the episodes
(`declaredAwaySpans`/`declaredAwaySince`, closed at the flag's two edges — the button and the unlock that
clears it), and `SchedulerDomain.declaredAwayRegions` reads them the way `displayInactivityGaps` reads the
live pause (the closed ones plus the open one growing with the now-line and stopping there). The other devices
need nothing: a peer's layer is already hatched whole ("a device that cannot be asked was locked"), so an away
press with every other device locked comes out as BOTH layers — which by ADR 0002's identity *is* a no-screen
period. Mode 3 and "a stretch carrying both layers" are now the same set.

Two details that had to be right rather than convenient:

* **The declaration is a CLAIM, not evidence** (`layerRegions`' asserted slot). The seam filter drops readings
  under a minute, and a 40-second away spell is 40 seconds the mode was 3 for; and a failed lock query — which
  legitimately silences the scan — must not silence the user's own statement.
* **It belongs to ONE layer.** A press on the computer says nothing about the phone, so
  `observedNoScreenRegions` takes `computerAway`/`phoneAway` beside the two histories. That is also the one
  exception to *"the asserted regions are not evidence"*: a screen break is not time the user was absent for
  and a declared absence is, so the same stretch cuts the on-screen panels over it and banks no record — the
  rule the hatch and the bank already shared, reached from the third source.

**The display was reading only its own button** (`App.kt`'s `t_p` mode), so a peer holding the account away
left the calendar in mode 2 dragging a pose the fill had let elapse. `SchedulerEngine.accountAway` is exposed
and the display now reads the engine's own `own || account`.

**Two stale bits of vocabulary went with it.** `docs/scheduler_requirements.md`'s 20 s exception still said the
line is *"in mode 2"* while it crosses the look-away — wording from when there were two modes, and
unsatisfiable against the mode-2 the same section now defines (*"covered by 'no on-screen task' **but not one
of the three dynamic periods**"*). It says mode 3, which is what the code has done since 2026-09-04.
`SchedulerProgressive` kept its own `MODE_AT_SCREEN`/`MODE_AWAY` constants — a second copy of the vocabulary,
which is how it never learned there was a third — and now uses `DynamicPeriods`'.

### The now-line slides: sub-second bands, sub-pixel line — 2026-09-05

`shared` (`ui/CalendarUi.kt`) + `NowLineSecondsTest`, ADR 0009, `docs/invariants/{calendar,display-hot-path}.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Reopened report: the line still advanced *"one step at a time"*, and so did the pose it drags — *"when zoomed
in enough, it is not just one pixel"*. Both true; the earlier "the lag is one pixel by construction" was
about the display RESAMPLE and quietly answered a different question. Two quantizations sat under it:

- **`recordsForDay` floored every block to the whole second.** A bound PINNED to the line moves with it (the
  pose is `(t_p, t_p + d]`, so its top edge *is* the line), so it lurched one second at a time — ~1.7 dp at
  the zoom ceiling — however finely the display resampled. Blocks now read `hourOfDayExact`; the residual is
  the `Float`'s own step (~7 ms ≈ 0.01 dp there), so `PlacedRecord` stayed `Float` and the block pipeline
  (`overlapLayout`, the slices, the hover tiling, the gestures) was not touched.
- **The line was rounded to the pixel grid.** `Modifier.offset { IntOffset(…) }` cannot carry a fraction, so a
  clock sampled at 60 Hz reached the screen as *hold still, then jump a whole pixel* — every ~75 s at zoom 1,
  about twice a second at the ceiling. The line, its dot and the overdue-reminder stack are now placed by
  `graphicsLayer { translationY = nowLineOffsetPx(…) }` (a `Float`, product taken in `Double`), so Skia
  anti-aliases the crossing. Draw-phase, so it is strictly cheaper than the layout read it replaces.

Still quantized, deliberately: a BLOCK's edge is composition-phase `Dp` geometry recomputed on the quantized
display instant, so the dragged pose now steps by exactly one pixel rather than by a second of time. ADR 0009
records what closing that last pixel would cost and why doing it for only half the pinned bands is worse.

### The priority-weight table's rows are task cells, and its add row is its own — 2026-09-05

`shared` (`ui/TaskSchedulerScreen.kt`, `domain/SchedulerDomain.kt`, `state/SchedulerIntent.kt`,
`state/SchedulerReducer.kt`) + `PriorityWeightTableRowTest`, `CLAUDE.md`, PRD §5, ADR 0004.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Reported on the release account (account 3): the **root** sub-list's weight table offered no way to add a
row, the optional row added earlier could not be typed into, and it was the one row with no task colour. One
cause each, and all three in how the table configured `TaskRow`.

**The add row was borrowing an empty cell of the sub-list.** It asked for a cell with `taskId == null`, but
PRD §4's deletion leaves a cell holding a **blank-titled** task — which is what a placeholder often is. A
read-only probe of the release DB: the root list holds 14 cells, every one of them carrying a task, the last
`task/user/241` with an empty title. No `taskId == null` cell ⇒ no add row. It is now the table's own
placeholder (`PriorityWeightTableRow.isAddRow`), offered whenever the list has a parent task, with a
synthetic id (`priorityWeightRowId`) — a borrowed one also keyed `TaskRow`'s per-row state and was compared
against the tree's live edit session, so the placeholder could answer for a cell being renamed elsewhere.

**An optional row was `selectable = false`.** That is the one background that wins over the task's own colour
(ADR 0013) *and* it installs no pointer gestures, so the row was grey and inert. Every row is selectable now,
and the table keeps a one-row selection of its own to feed it (Compose-only, like the pins beside it).

**The gestures and the live colour are the tree's** (a same-day follow-up, from three more reports): a press
**selects** the row and only a **double-click on the title** opens Edit Mode — the add row included, which
had briefly opened on a single press — and a row being typed into wears **the colour of the task the draft
resolves to**, the tree's own Change Task rule (`selectedAssignTaskId`, the first eligible task the text
matches) said for a row that names an existing task instead of creating one. The row's current task is exempt
from "already in the table" for that lookup (`eligibleWeightTableTaskIds`' `replacing`), or opening a row's
editor blanked its colour before a key was pressed.

**Delete empties the selected row**, which removes it — §4's blank title read here, raising the same one
intent the editor's blank exit does. It answers only on the rows the table owns: a member row is a cell of
the tree, so Delete on one is left unhandled rather than deleting a task nobody opened this window to touch.

**And the keyboard gestures took the focus with them.** Typing a letter on the selected row must open it
seeded with that letter (PRD §4). With the window open that letter reached the TREE instead: a sort-2 pop-up
is non-modal, so the tree kept the keyboard, began renaming its own selected cell — and entering Edit Mode is
exactly what closes this window, so the keystroke aimed at the pop-up dismissed it. Non-modal is now stated
as being about the **pointer**: `TransientPopupHost.anyOpen` (observable) says a sort-2 pop-up is open,
`TaskTreeView` reads it as `keyboardOwned` so all three drawings of the tree go deaf together and take the
keyboard back when it closes, and the weight window takes focus for itself. `TransientPopupHostTest` pins the
flag against every way a pop-up leaves.

**A row's identity is one intent.** `AddPriorityWeightTableTask` is replaced by
`SetPriorityWeightTableRow(listId, replacing, taskId)` — add, re-point, remove — so each gesture is one
history unit ("Add table row" / "Change table row" / "Remove table row") and a pick that changes nothing
commits none. Removing is **emptying the row's title**, §4's rule read here, which is what makes an optional
row removable in the table at all; the task-relations ✕ (entry below) is now the second way rather than the
only one. The identity menu is `SchedulerDomain.eligibleWeightTableTaskIds` — the intent's own predicate (a
live occurrence chain under the list's parent) asked of the menu, so a pick the intent would refuse is never
offered; the cell's `eligibleAssignTaskIds` was answering a different question and offering rows the reducer
then dropped on the floor.

### A struck-off pair comes back when its weight-table row does — 2026-09-05

`shared` (`domain/TaskRelations.kt`) + `TaskRelationsTest`, `CLAUDE.md`, PRD §5, ADR 0004.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Reported on the release account: a row added to the root sub-list's weight table never appeared in the Task
relations window. A read-only probe of the release DB found the pair exactly as suspected — `wc > root`
carrying `hidden = true` **and** a live optional row of `list/main`, with the window's own `rows()` skipping
it.

`hidden` outranked every source of a pair, weight-table rows included, and the reason it had to was that the
✕ left the row standing: a pair struck off while it was a table row would have come back on the next
composition. The entry below gave the ✕ the row's removal and left that rule in place, which made it wrong in
exactly the two ways it can now be reached — the row is re-added, or **Ctrl+Z of the strike-off** restores it
(the row is a history unit, the mark is not). Both are the user's own table asserting the pair again, and
neither had any way to say so.

So a **live weight-table row now outranks `hidden`**. The mark still holds every other source off the list,
which is the whole of what it is for now that a row cannot outlive it. Nothing is written to heal the stale
mark: the row is derived, and this is the derivation reading it.

### Striking a task relation off takes its weight-table row with it — 2026-09-05

`shared` (`domain/TaskRelations.kt`, `state/SchedulerReducer.kt`, `state/SchedulerIntent.kt`,
`ui/TaskRelationsWindow.kt`) + `TaskRelationsTest`, `CLAUDE.md`, PRD §5.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Section 1's **✕** used to write a `hidden` mark and nothing else. Where the pair had reached the list by
being an **optional row** of the target sub-list's priority-weight table, that left the user's own table
still asserting the relation they had just struck off, the only thing keeping the pair off the list being the
mark hiding it. (The entry above then gave the table a removal of its own; both go through the same reducer
rule.)

`DropTaskRelation` now also removes those rows (`TaskRelationsDomain.withoutWeightTableRows`, written as the
exact inverse of `weightTableRelations`: the same walk over the lists and the same `parentTaskIdOfList`
reading, so the two cannot disagree about which rows a pair is made of). The mark is still written — a pair
reaches the list by two routes and only one of them is a table.

The removal is a **tree** change, so it commits one history unit, exactly as the row's creation does: the add
and its inverse are undoable the same way.
The marks stay outside history as before, and a pair with no such row commits nothing at all — no empty unit
for Ctrl+Z to walk over. Nothing re-plans: `treeSignature` reads a list's `cellIds` and `weightColumns`,
never its `optionalTaskIds`. The row's own line says what the ✕ will do while it can still be read.

### The display is a piecewise function of the now-line, so it is no longer polled — 2026-09-05

`shared` (`App.kt`, `ui/CalendarUi.kt`, `domain/SchedulerDomain.kt`) + `DisplayResampleBoundaryTest`,
`CLAUDE.md`, ADR 0009.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Follow-up to the entry below, and it replaces the fixed 250 ms sample it introduced. The observation that
prompted it: **nothing in the past is affected by the now-line moving** — a 20 s break is removed by pressing
"look away now", a period is changed by hand, and both are EVENTS — and the future panels simply follow the
set of rules the scheduler returned, which says what happens between t1 and t2. So a display that recomputes
on a timer is answering the wrong question; the trigger should be t1.

`SchedulerDomain.displayResampleDelayMillis` is that rule, and `App` sleeps on its answer. The boundaries are
the derived model's **own bounds** — every panel, band, marker and layer region is built out of instants, so
the model cannot change before the first one still ahead of the line (sound by construction; it can name a
boundary that changes nothing, it cannot miss one) — plus the next local midnight, the one boundary no panel
carries. A bound sitting **on** the line is a **pin**, not a boundary (`NOW_LINE_ANCHOR_SLACK`, 2 ms: a
dragged pose starts at `t_p + 1`, a taken break is drawn to `t_p − 1`); counted as a boundary it answers "one
millisecond" and the sleep becomes a busy loop. A pin follows the line **affinely**, so it is re-derived at
the display's own **resolution**: the calendar reports how long the line takes to cross one pixel at the zoom
in force (`onNowLineResolutionChanged`) — ~75 s at the default zoom, ~0.6 s at the ceiling — the same
principle as `visibleHourWindow`'s quantization, the temporal resolution following the spatial one. A floor
(250 ms; 50 ms accelerated) and a ceiling (30 s, the engine's own production cadence) bound the answer, so a
boundary this gets wrong costs a late redraw and never a wrong answer.

Net: an idle calendar with a break owed and dragging used to recompute 4×/s, and 60×/s before that. It now
recomputes twice a minute at the default zoom, and at the zoom ceiling only as often as one pixel of movement.
The now-line itself is unaffected — it is placed in the layout phase off its own frame sampler, so it glides
however seldom the model behind it is re-derived.

### The now-line moved in one-second steps, and cost a full recompose per frame — 2026-09-05

`shared` (`ui/CalendarUi.kt`, `App.kt`) + `NowLineSecondsTest`, `CLAUDE.md`, ADR 0009.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Reported: zoomed far enough in, the now-line advances in visible steps rather than gliding — with the
question behind it, *does a continuously moving line have to be expensive?*

One value was doing two jobs. `App.kt` sampled `nowMillis` on the Compose frame clock (`withFrameNanos`,
2026-09-01) so the line would follow the clock, but `nowMillis` is what the whole of `App`'s body derives
from — the sleep and screen-break projections, the derived grey bands, the layer regions, the reminder
horizon, $t_{goal}$, the cull windows — so every frame re-ran that entire O(visible window) pass, sixty times
a second, to move one line. And the line still stepped: its placement read `LocalTime.hourOfDay`, which
floors at the second, so at the zoom ceiling (6144 dp per hour) it jumped ~1.7 dp once a second.

The two are split. **Derived from the clock ⇒ quantized**: `DISPLAY_NOW_STEP_MILLIS`, 250 ms while a calendar
is open (~0.4 dp of lag at the zoom ceiling, ~0.003 dp at the default zoom), the engine's own 30 s cadence
when none is, 50 ms under acceleration. **The line ⇒ per frame, in the LAYOUT phase**:
`CalendarUi.rememberNowLineHour` samples the exact clock into a state read only from `Modifier.offset { … }`,
placed with the new `hourOfDayExact` (a `Double` — a `Float` around 24 quantizes at ~10 ms by itself), so
re-placing it recomposes nothing and re-measures nothing. The sampler is gated on the same cull window the
rest of the column uses, so a column that is not today's, a grid scrolled to another week and a closed
calendar ask for no frames at all. The overdue reminder tags ride the same state — CLAUDE.md's rule is that
the stack's anchor and the line read one instant.

**The engine was already right and is untouched.** `docs/scheduler_requirements.md`'s *"the $now line$ moves
continuously forward in time"* is about the scheduler's now-line, which is walked one
`SchedulerDomain.sweepStepMillis` at a time by `SchedulerEngine.sweepNowLineTo` — the only route to
`advanceTo` — and pinned by `NowLineSweepTest`. What changed here is only how the calendar draws it.

### The lock-history query deadlocked once its answer passed 4 KB — 2026-09-05

`shared` (`jvmMain/scheduler/platform/WindowsPowerLog.kt`) + `WindowsPowerLogTest`, `CLAUDE.md`.
**Client only — an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

Reported on the release account: **past task panels of tasks that are not resilient to a no-screen period
were drawn straight across a stretch hatched with BOTH layers** — "no computer unlocked" and "no phone
unlocked" — which is exactly the reading `SchedulerDomain.clipPanelsForObservedNoScreen` exists to deny.

The clip was right; it was never given anything to clip with. `WindowsPowerLog.run` waited for the
PowerShell process to exit **before** reading a word of its stdout, and a child's stdout is an OS pipe with a
4 KB buffer on Windows: a process that fills it blocks on its next write until somebody reads. So the query
finished and the process could not, `waitFor` hit its 20 s timeout, and `run` returned null — which
`transitions` reads through its `OK` sentinel as *the log cannot be read at all*. That null then means two
different things by design (ADR 0002): assumed-**locked** to the calendar layer, and **no evidence** to
`observedNoScreenRegions`. Both layers therefore hatched the whole displayed past while the clip set stayed
empty, and the panels underneath survived.

It is a threshold, not a regression, which is why it started mid-life and only got worse: the answer grows
with the machine's power-event log. Measured on the release machine — 6 h = 480 B, 24 h = 879 B, 72 h =
1.6 KB all answered in ~1.2 s; the 168 h window's 4.2 KB hung for the full 20 s. `diagnostics.log` dates the
first failure to **2026-08-30 01:01** and the last success to 2026-08-30 12:30; every scan since 2026-08-31
15:15 failed (`device lock history unavailable — NoComputerUnlocked assumed locked over the whole window`,
230 consecutive lines).

`run` now drains the stream on a thread while the process runs and joins it after the exit. All three
`SleepHistory` actuals go through it, so the calendar layer, the record-bank evidence, the screen-break seed
and the exact pause recorder are fixed together. `WindowsPowerLogTest` pins it against a ~64 KB answer —
sixteen times the buffer — and fails against the old code.

### A break's start notification says what the break runs out into — 2026-09-05

`shared` (`domain/SchedulerDomain.kt`, `engine/SchedulerEngine.kt`) + `ScreenBreakFollowOnTest`,
`CLAUDE.md`, `docs/PRD_TaskScheduler.md` §15. **Client only — an app rebuild
(`account{1,2,3}-*deploy*.bat`); no Supabase deploy and no DB migration.**

A screen break whose start is **outside** a period the user is away from the screen for but whose end is
**inside** one no longer reads as a break to come back from: its start notification now adds *"followed by a
no screen period"* (the period is one the **user** drew) or *"followed by the hour before bed"* (§17's
wind-down hour). Before this the user learned it only by reaching the end of the break and finding nothing
scheduled.

`SchedulerDomain.screenBreakFollowOn` is the whole rule and
`screenBreakStartNotificationMessage` the one wording, so the automatic cue sweep and the manual
"Look away now" cannot word one break two ways. A break already inside such a period when it began adds
nothing; a dynamic period, a conducted break and a derived sleep window do not qualify (the reasons are in
`CLAUDE.md` § *Notification / voice-cue triggers*). The window asked about is the break's own in the run its
cue keys on — a look-away's placed `[start, end]`, a pose's **due** and one duration after it.

### The now-line has a THIRD mode — 2026-09-04

`shared` (`domain/DynamicPeriods.kt`, `domain/SchedulerDomain.kt`, `engine/SchedulerEngine.kt`,
`sync/PauseCueGateway.kt` + `DeviceHeartbeatPublisher.kt` + `RemoteSnapshotClient.kt` +
`SchedulerSyncEngine.kt`, `App.kt`) +
`supabase/migrations/20260904000000_now_line_mode_3.sql` + `supabase/functions/pause-cue-cron` +
`docs/scheduler_requirements.md`, `CLAUDE.md`, ADR 0003, ADR 0006, `docs/PAUSE_CUE_DELIVERY.md`.
**Both surfaces: `deploy-supabase.bat` AND an app rebuild (`account{1,2,3}-*deploy*.bat`).** No client DB
migration — the new state is server-only and the client's own record of it is in memory.

The spec added **mode 3**: no computer and no phone of the account unlocked **and** the "I'm away" button on.
Its rule is *"$now line$ must be covered by the period 'no on-screen task'"* with nothing else attached, where
mode 2 says *"…but not one of the three dynamic periods"* — so mode 3 is the one mode in which the line may be
covered by a 5- or 15-minute pose.

**What actually changed, and it is not only an added case.** Mode 2 used to behave the way mode 3 does now:
nothing was dragged there, so a locked screen meant every pose the line reached simply elapsed. Reading the two
definitions apart is what makes mode 3 mean anything, and the reading is right on its own terms — a locked
screen says *no screen is in use*, which is not *a break is being taken*; the user may be reading at their
desk. So **mode 2 now drags an owed pose exactly as mode 1 does** (`DynamicPeriods.breaksAreTakenAt`, the one
predicate telling the modes apart), and what makes that pose go away while the machine is locked is the
ordinary bar rule — a locked stretch is a rest stretch, and a rest stretch bars the breaks after it. The
practical difference is therefore confined to the first minutes of a lock and to accounts with off-screen
tasks; the deliberate new behaviour is that pressing "I'm away" now lets the poses actually happen.

**The mode is an ACCOUNT-wide condition**: *at least one device with the "I'm away" button clicked and every
other device locked*. So the flag leaves the device it was pressed on — `sync_device_away` writes it and
returns "is any device of this account away", read on every away edge and every sync moment — and
`SchedulerDomain.tpMode`'s second argument is that account answer, not a local flag. A device reading only its
own would put a merely-locked peer in mode 2 while the machine the button was pressed on is in mode 3, and the
two would place the three dynamic periods differently. The away device drops out of the *first* question by
itself (the button stops its beat and finalizes its session), which is what makes the conjunction come out
right without a third question. The engine reads `own || account`, so the button still works at the press,
offline.

Three consequences that needed code of their own:

* **`awayCover` may no longer answer null for want of a preceding period.** Mode 2 drags the pose onto the line
  as `(t_p, t_p + d]`, which leaves `t_p` itself uncovered by construction, so there is often nothing behind
  the line to measure the README's gap from; the cover is then the line's own instant. `fillSchedule`
  re-expresses whatever comes back as `[now, now + 1)` regardless, so what was lost was the *existence* of
  mode 2's cover, not its reach.
* **The scheduler's SET OF RULES is published, and the server READS it** (`SchedulerDomain.poseWindowsBetween`
  → `publish_break_rules` → `screen_break_rule`). For the whole of a mode-3 episode every screen of the account
  is off, so nothing local is watching the line cross a break: `tick_pause_cues()` pass (c) moves the line over
  the published rules, asks the one comparison `start <= now < end`, and hands an account whose line is inside
  a rule to **e2** with `action: 'mode3'`. `claim_mode3_break_cue` anchors the cue at the break's own END,
  which the rule states exactly. The server **never runs the scheduler** — it places nothing and knows no
  recurrence, and reading the rules this literally is legitimate *because* it is mode 3, where nothing drags a
  pose. `account_in_mode3()` is the server's own reading of the mode (an away device, no beat within `2·t_a`),
  so nothing trusts a client-computed one. The two `device_break` dues are unchanged and come out of the SAME
  placement query as the rule set — a projection, not a second derivation — so the walk-away gate and this pass
  can never name different breaks. Pass (c) is deliberately not folded into pass (a): different question,
  different anchor, and its own de-dupe key `pause_cue_schedule.break_start_ms`, which is also what stops the
  two paths cueing one pose twice.
* **A wake asks the server for the account's mode-3 stretches and walks them in mode 3**
  (`away_spans` → `SchedulerEngine.awaySpansFor` → `sweepNowLineTo`, the log kept by the away flag and the
  presence beats themselves rather than by any device's opinion of the mode), the spec's own amendment to the
  fast-forward move. The ask belongs to the tick loop, which is already a coroutine, so the journey stays the
  single synchronous walk it was; a step never straddles a span's bound. Two records are merged because neither
  alone will do: this device's own covers a journey the clock made while the app ran, the server's covers the
  episode the app slept through — which is exactly the journey a wake has to walk. Time-bounded and
  best-effort: an unreachable server leaves the whole journey in mode 2, as before.

### A reminder tag is the TOP-MOST thing the day column draws — 2026-09-04

`shared` (`ui/CalendarUi.kt` — `DayColumn`'s emission order) + `CLAUDE.md`. **Client rebuild only** — no
Supabase deploy, no migration, no payload change: this is a z-order rule.

A reminder could not be checked off on the calendar. Every marker the column drew after the tags sat over
them, and one of those kinds is not merely decorative: a `ScreenBreakBand`'s hover tiles are pointer-input
nodes, so a band covering a tag won the hit test and the click that would check the reminder off never
reached the chip — the same "a lid over the tile" mistake `CalendarHoverTiles` exists to prevent, from the
other side. It bit hardest at exactly the position that matters most, the now-line: that is where the
overdue stack accumulates AND where mode 1 parks an owed pose. The bubble said as much — hovering a tag
named the break and the two "nobody unlocked" LAYERS instead of the reminder.

The tags are now emitted last, so nothing is drawn or hit-tested over them: the grey marks, the layers, the
now-line, the alarm markers, the screen-break bands and the "Sleep"/"Inactivity" labels all go under. A
reminder is the one marker on the calendar the user has to be able to HIT; every other element there is
decorative or reports only hover.

### A selected / edited cell is marked by its OUTLINE, never by a fill — 2026-09-04

`shared` (`scheduler/ui/TaskSchedulerScreen.kt` — `TaskRow`'s `cellBackground` / `cellBorder`;
`ui/TaskSheetChrome.kt`, `ui/TaskTreeFindBar.kt` — comments only) + `CLAUDE.md` + ADR 0013. **Client rebuild
only** — no Supabase deploy, no migration, no payload change: this is a paint rule, and colours are derived.

The main selection, every other cell of the selection and the cell in Edit Mode used to be painted
`SheetColors.selectionFill`, which meant the tree's task colours were repainted on exactly the rows the user
was working on — a selected block went uniformly pale blue, and a rename hid the colour of the very task
being renamed. The fill is gone; the state is now said in the border alone, told apart by its weight:

| State | Border |
| --- | --- |
| main selection, or in Edit Mode | 2 dp `activeBorder` |
| among the selection | 1 dp `activeBorder` |
| neither | 1 dp `grid` |

The background is now the task's own colour (or `cellBackground` where it has none) in all three. Drag-move
(`moveDragFill`) and non-selectable (`nonSelectableFill`) still win the background outright — those two are
about whether the row can be acted on at all, not about what the user has picked. `selectionFill` survives as
the find bar's latching-toggle fill and nothing else.

Applies to all three drawings of the tree — the task tree, the "All tasks" window and the default sub-tree
template — because `TaskRow` is the one row.

### The schedule horizon IS $t_{goal}$ — 2026-09-04

`docs/scheduler_requirements.md` § *Progressive Calculation*'s stopping clause, answered with a concrete
instant. `shared` (`scheduler/domain/SchedulerDomain.kt` — new `weekStartDate`,
`endOfFirstDayOfNextWeekMillis`, `endOfDayAfterMillis`, `scheduleGoalEndMillis`, and
`scheduleHorizonEndMillis` rebuilt over them;
`scheduler/domain/SchedulerProgressive.kt` — `goalMillis` / `setGoal` / `isSettled` and `settle` stopping
there; `scheduler/engine/SchedulerEngine.kt`, `scheduler/state/SchedulerReducer.kt`, `App.kt`,
`ui/CalendarUi.kt` — `weekAnchorDay` now delegates so "which day a week starts on" exists once) +
`CLAUDE.md` + PRD §9 + ADR 0001 + ADR 0009 + `ScheduleHorizonTest` rewritten. **Client rebuild only** — no
Supabase deploy, no SQLite migration, no payload change: the horizon is a local runtime bound, never
persisted and never synced.

The requirement gained a line (*"The scheduler can have a time $t goal$ such as when definitive schedule is
found for any t < $t goal$ the scheduler can stop"*) and names it:

> $t_{goal}$ = **max(** end of the first day that does not appear in the calendar, end of the first day of
> the week after the current week **)**

So the calendar half is **one day past the bottom of the grid** and follows the SCROLL, not the week the
scroll is in: open the calendar on the current week, scroll down until the next week's Monday is on screen,
and the goal becomes the end of that week's Tuesday. (An earlier draft that day read the calendar half as
"the week after the week shown"; it moved a week at a time where the user's rule moves a day at a time.)

It replaces "the displayed day span, clamped into [24 h, 168 h]". Three things change with it, and each is
why it is better than what it replaces:

- **It is a MAX, so the calendar can only ever push it out.** The old horizon *followed* the span, so
  scrolling into the past collapsed the plan to the 24 h floor and closing the calendar did the same — the
  headless §11/§13/§15 paths ran on a day of schedule. They now always get the current week's own goal.
- **Neither half moves with the CLOCK.** `now + 24 h` moved with every tick, so a fill was short again the
  instant it finished — the self-feeding shape `HORIZON_REFILL_MARGIN_MILLIS` exists to damp (2026-07-28's
  hot loop). The current week's goal is an absolute staircase that steps once a week; the calendar's moves
  only on a scroll, which is an event the grid reports.
- **The 168 h ceiling now caps the CALENDAR half alone.** The current week's goal is up to eight days out (a
  Monday's is the end of the following Monday) and clipping it would leave the app short of the very instant
  the requirement names. A calendar-driven goal past the ceiling is still computed to the goal — off the UI
  thread, for display, never retained (`App.kt`'s far-week `LaunchedEffect`, which now fills to the goal
  rather than to the visible span's end; the goal is never nearer, so it reaches at least as far as the
  screen).

The rest of § *Progressive Calculation* was already met and is unchanged: one fill computes the whole span far
inside the ten-minutes-per-ten-seconds pace, and reaching further out **extends** (`ExtendSchedule`) rather
than re-planning, so everything below the front stays definitive.

### The 20 s look-away's cue keys on the AT-LINE run, not on the undragged due — 2026-09-04

`shared` (`scheduler/domain/SchedulerDomain.kt` — new `screenBreakCueOccurrencesBetween`, `cueCrossings` gains
a `mode`; `scheduler/engine/SchedulerEngine.kt` — the sweep and its self-delay) + `CLAUDE.md` + ADR 0003 +
tests (`DynamicPeriodsTest`, `ScreenBreakCueRuleTest`, `CueSweepOrderingTest`). **Client rebuild only** — no
Supabase deploy, no SQLite migration, no payload change (both readings are derived).

Reported on account 3: the calendar drew a **20 s look-away at 12:52–12:53** that was never announced — the
last notification in the History window was the **15-min pose at 12:51**, still owed and dragging at the line.
Confirmed against the release DB (read-only probe) and `~/.omniapp-release/diagnostics.log`: over 12:30–12:56
the undragged run held one 5-min pose and **no look-away at all**, while the at-line run the calendar draws
held look-aways at 12:33:40 and 12:54:00.

Cause: **the two runs of the recurrence bars are not the same sequence of look-aways**, and the cue read the
wrong one. The drag re-anchors the bar it fires on, so an owed pose is a *placed dynamic period* in the
undragged run (barring the 20 s for twenty minutes after itself) and has been dragged onto the now-line in the
at-line run (barring nothing where it was owed). `cueCrossings` asked the undragged run for all three, so it
drew look-aways it never announced — and would have announced one where it draws none as soon as a dragged
pose landed on the line and barred the 20 s ahead of it.

Fix: each of the three is announced from the run its own placement rule makes crossable — a **pose** on its
undragged **due** (it is dragged, so its place rides the line and is never crossed), the **20 s look-away** on
its **at-line placement** (nothing drags it, so that start is already a fixed instant, and it is the one the
calendar draws). `screenBreakCueOccurrencesBetween` is the single reading; `cueCrossings` and the cue sweep's
self-delay both go through it, and the sweep now passes the `t_p` mode as well as the environment and the
anchor. Both runs are still asked with the *whole* break list and the answer selected afterwards: the chain
merge collapses a look-away that touches a pose into the pose, so a spec dropped from the list would move the
starts of the ones left behind.

### The 20 s look-away is assumed taken — the now-line crosses it instead of dragging it — 2026-09-04

`shared` (`scheduler/domain/DynamicPeriods.kt`, KDoc in `scheduler/domain/SchedulerDomain.kt` and `App.kt`) +
`CLAUDE.md` + `ARCHITECTURE.md` + `docs/PRD_TaskScheduler.md` §15 + ADR 0003 + `side-dev/README.md` +
`README.md` + `docs/MANUAL_TESTING.md`. **Client rebuild only** — no Supabase deploy, no SQLite migration, no
payload change (the placement is derived; nothing new is persisted or synced).

`t_p` mode 1 says the now-line may not be covered, and the app answered that for all three dynamic periods by
**dragging** the one the line reached: it parked on the line and went on being pushed until a real rest
happened. That is right for a **pose** — five or fifteen minutes away from the screen is something the user
has to actually do, so an untaken one is owed, not spent — and wrong for the **20-second look-away**, which
costs no working time, needs no decision, and which the app already assumed was done everywhere else (the
voice cue advances to the next one regardless, PRD §15). So a look-away could sit parked on the line for
hours, refusing to schedule anything under it, and the stretch behind the line showed no break at all.

- **`DynamicPeriods.dragsAtLine` is the one predicate**, keyed on the positional bar label (`LABEL_20S` — the
  shortest of the three), never on a title: the two poses are dragged, the look-away is not. It gates both the
  drag and the half-open `(t_p, t_p + d]` form the drag produces.
- **The line walks THROUGH a look-away, in mode 2** — covered for its twenty seconds, which is exactly what
  mode 1 forbids of a pose — and comes out the other side. Nothing is scheduled under it while it does.
- **It stays on the calendar afterwards**, drawn where it happened: `takenScreenBreakPanels` asks the same
  bars over the recorded past, so the past stays frozen for the reason it always did (the environment behind
  the line is a fact). It is still **derived, never recorded**, so it can still move when that environment
  does — the everyday case being "Look away now" pressed less than twenty minutes later, which re-anchors the
  20-second bar off the break actually taken (`RecordConductedBreak`).
- **The due and the place are one instant** for this one of the three, so its cue, its band and the fill it
  obstructs cannot drift. The two readings stay separate functions anyway: that split is what keeps a *pose's*
  cue keyed on a boundary that is crossed exactly once.
- `side-dev/README.md` § *$now line$ 2 modes* carries the exception now, so the model and the app still say
  one thing. Tests: `DynamicPeriodsTest` (the pose is dragged, the look-away is crossed and stays where it
  fell due, and the past draws it at its due), `TpModeTest`, `SchedulerCalendarTest`.

### "I'm away" declares an empty screen, not a silent app — a LOCK is what silences the device — 2026-09-04

`shared` (`scheduler/engine/SchedulerEngine.kt`) + `CLAUDE.md` + `ARCHITECTURE.md` §8/§9 +
`docs/PRD_TaskScheduler.md` §15 + ADR 0003 + `README.md` + `docs/MANUAL_TESTING.md`. **Client rebuild only** —
no Supabase deploy, no SQLite migration, no payload change (the away flag was already local runtime state).

One flag was answering two different questions. `effectiveScreenActive()` masked the platform sensor with
both the debug leap and the "I'm away" button, and every §11/§15 cue gated on it — so pressing the button
silenced this device. That is not what the button means: it says *nobody is at this screen*, which is a
statement about the no-screen periods, the `t_p` mode and the account-wide idleness the break-over cue is
judged on. Its user is routinely still sitting at the machine, which has to stay unlocked for a program to
keep running, and the "task to do now" notification is precisely what they are still able to act on.

- **Two readings, split.** `effectiveScreenActive()` keeps the presence question (active session, `t_a`
  heartbeat, no-screen evidence, `t_p` mode) and keeps the away mask; the new `deviceUnlocked()` answers *may
  this device say anything* off the raw lock alone. The debug leap masks both — it simulates a machine that
  went to sleep, which is a lock.
- **A LOCKED device now says nothing at all**, which the app only half did before: the look-away's start and
  its resume were gated, while the task-switch, rest-pose-due and wind-down notifications fired at a lock
  screen nobody was reading. All of them now gate on `deviceUnlocked()`. The end of a break on a locked
  device is the **server's** push, exactly as before (ADR 0006) — that path is untouched, and
  `onPauseCueFire`'s own re-check now reads the lock too, so a phone in the user's hand stays silent even
  while "I'm away" says nobody is working at it.
- **Suppressed is not spent.** The task switch and a pose due leave their level/de-dupe untouched while
  locked, so what could not be said is said at the unlock; the look-away start and the wind-down are marked
  either way (a crossing worth nothing late).
- **An ALARM keeps ringing** — a locked machine is what an alarm is for (ADR 0010). It is the one deliberate
  exception, and it is about alarms, never about a cue.
- A break due while the user is *away* still does not fire, and needs no gate to: the pause covers the line,
  so the bars place no period inside it (`AwayVersusLockedCueTest` pins the pair — away still announces the
  task, a lock announces nothing and then announces it at the unlock).

### The Alarms window's countdown is three input fields, and editing it does not stop the timer — 2026-09-04

`shared` (`scheduler/domain/TimerDomain.kt`, `scheduler/state/SchedulerIntent.kt`,
`scheduler/state/SchedulerReducer.kt`, `ui/AlarmWindow.kt`, `App.kt`) + `docs/PRD_TaskScheduler.md` §18 +
`docs/MANUAL_TESTING.md` + ADR 0010. **Client rebuild only** — no Supabase deploy, no SQLite migration, **no
payload change**: the new intents write fields `TimerEntry` already has.

PRD §18's countdown was a read-only readout, so changing how much was left meant Reset + retype the duration +
Start — which discards what has elapsed and answers "how long is this timer" when the user asked "how much
longer". The countdown is now **hours / minutes / seconds as three fields**, plus six ± second buttons, and
editing it leaves the timer running.

- **An edit is a SHIFT by that component's own unit, never a rewrite of the countdown**
  (`TimerDomain.withCountdownField`, behind `SchedulerIntent.SetTimerCountdownField`). That one choice is the
  feature: setting the **hours** moves the due instant by `(value − hours) × 1 h`, so the minutes and seconds
  underneath go on reading down without a jump; setting the **minutes** leaves the seconds running. A rewrite
  would have restarted the finer component at zero — "restart it at 2 hours" rather than "make it 2 hours".
  Each keystroke is measured against the **live** value, so typing `12` into the minutes (committing `1`, then
  `12`) lands on 12, not 13.
- **`SECONDS` is the one edit that stops it, and the ± buttons are why that is acceptable.** The seconds are the
  digit that is itself reading down, so a typed value would be consumed by the next tick; that edit therefore
  **pauses** the row (Pause becomes Resume) and snaps the countdown to the whole second typed, which is what
  makes it stick. `SchedulerIntent.NudgeTimerRemaining` — `−10s / −5s / −1s / +1s / +5s / +10s`,
  `TimerDomain.nudged` — is how the seconds move **without** stopping. The two are a pair: a silently
  non-stopping seconds field would not hold its value, and without the buttons the seconds would be unreachable
  on a running timer. A nudge past zero leaves it due now, so it rings.
- **Both write through `withRemaining`, in each state's own currency** — a running row's `endsAtMillis` moves and
  it stays running, a paused row's banked `remainingMillis` is rewritten and it stays paused. Neither crosses
  into the other's field, which keeps the three-state invariant true without `healed` catching it.
- **An idle row is unchanged, the fields are `readOnly` and the buttons disabled there.** Its countdown *is* its
  `durationSeconds`, which the Duration field on the same row edits; two fields writing one number by two routes
  is the drift this codebase keeps deleting.
- **One draft per row, naming the field it belongs to** (only one field can hold the focus). The live countdown
  changes four times a second, so a field bound straight to it cannot be typed into — every tick would overwrite
  the keystroke. Seeded on focus, dropped on focus lost (guarded on still being that field's, since Compose may
  report the gain before the loss). **The fields not holding it go on reading down**, which is what "editing the
  hours does not stop the minutes and seconds" looks like. `parseCountdownComponent` is its own parser and
  deliberately not `parseDurationSeconds`, which still reads the whole `H:MM:SS` of the row's **Duration**.
- **Nothing downstream needed a change**: `launchAlarmArming` already re-runs on every `state.timers` change, so
  a retyped or nudged countdown re-arms the phone's one OS slot by itself, and the desktop sweep reads the moved
  instant like any other.

### Alternative Schedules: every rule the fill makes now names who runs instead — 2026-09-03

`shared` (`scheduler/model/TaskModels.kt`, `scheduler/domain/SchedulerDomain.kt`) + `CLAUDE.md` + ADR 0001.
**Client rebuild only** — no Supabase deploy, no SQLite migration, **no payload change**: the answer is
derived and deliberately not persisted (it is recomputed in full by every fill, like the panel carrying it).

A fifth README audit fix, in the same sweep as the four below. `side-dev/README.md` § *Alternative Schedules*
asks for something the app's own scheduler was not answering: *"The returned set of rules must also give for
every $now line$ the task that must be scheduled if the task scheduled by the scheduler can't be scheduled
now."*

- **The reference port had it; the app's driver did not.** `SchedulerPlanner.runRange` already read
  `PlanWalk.alternative` beside each pick and handed it to a `PlacementCollector` (`ProgressiveSchedule`'s
  `PlannedRun.alternativeId`, `alternativeAt`) — a faithful port of `side-dev/scheduler.py`'s `Placement.alt`
  and `Scheduler.alternative_at`. But `ProgressiveSchedule` is reference-comparison machinery no app code
  calls, and `SchedulerDomain.fillSchedule` — the driver the running app actually answers with — never asked
  the question at all. An unsanctioned divergence between the two drivers, and the README's requirement was
  simply unmet on the surface that matters.
- **`TaskPanel.alternativeTaskId`** is where the answer lives now, because a panel IS one of the rules the
  scheduler returns. `fillSchedule`'s single `emit` funnel carries it, read **before** the clocks are charged
  (`side-dev/scheduler.py`: `alt = self._alternative(v, cand, name, p_local)`), so it answers "who instead?"
  at the same instant and against the same claims as the pick it stands in for. Both phases name it: phase 1
  from the walk; phase 2's settle likewise, and its analytic cycle from **the next task the rotation reaches**
  (the cycle is a converged rotation the walk is not advanced through, so the answer is said once for the
  whole repeat).
- **`SchedulerDomain.alternativeTaskAt(panels, millis)`** reads it back for *every* position of the line —
  `Scheduler.alternative_at`'s two passes: the rule covering the line, then the first rule ahead of it,
  because in mode 1 the line sits at the edge of the period it is dragging and the instant itself is often
  inside a stretch nobody may run in.
- **Null is a real answer, not a gap**: a stretch only one task was allowed in has nobody to name, and "the
  same task again" would be no answer at all.
- **The README's own use of it was already built** — PRD §7 "Switch task" is exactly *"set this new task
  starting at $now line$, and run the scheduler again"*. The two agree by construction: `ForcedTaskSwitch`
  hands the refused task to `PlanWalk.setLast`, and `pickNeediest(…, last = refused)` and
  `PlanWalk.alternative(…, chosen = refused)` are the same ordering over the same claims. `AlternativeScheduleTest`
  pins that end to end — refuse the scheduled task and the schedule that comes back really does start the
  named alternative — plus `tests_displayer.py`'s `unnamed_alternatives` invariant (every rule names one,
  where there is one to name), the merge keeping the alternative named at a run's **start**, and the cycle
  path.

### Four README audit fixes: No idling, mode 2's cover, the frozen past, and the whole rule state — 2026-09-03

`shared` (`scheduler/domain/SchedulerDomain.kt`) + `CLAUDE.md` + PRD §8/§9/§15 + ADR 0001/0003. **Client
rebuild only** — no Supabase deploy, no SQLite migration, no payload change: all four are derivations.

Found by auditing the running app against `side-dev/README.md` clause by clause.

- **No idling had two exceptions the README does not allow.** *"Anywhere that is not covered by restrictive
  periods which would prevent any task from being scheduled, the scheduler must schedule a task"* is a hard
  constraint; reaching the minimum execution time is *"another optimization goal"*, i.e. soft. `fillSchedule`
  had the priority backwards: a `_fits_from` filter dropped every task whose minimum did not fit the room
  ahead, and a sub-minute `crumb` rule (with a `free_tail` stretch beside it) emptied anything shorter. Both
  cited `scheduler_logic.py` — a reference file that no longer exists — and both were divergences from
  `SchedulerPlanner.runRange`, which always idled only on an empty candidate set, as `side-dev/scheduler.py`
  does. Measured before: a 45-minute task and a pinned block twenty minutes out left `[now, now + 20 min)`
  empty with nothing restricting it. After: 168 h filled with **zero** unexplained gaps, and the only panel
  shorter than a minute is the 1 ms at `t_p` itself — which is the README's, mode 1 leaving the line's own
  instant uncovered so that *"the passing of the $now line$ creates task panels not covered by the period"*.
  **PRD §9 now states the soft/hard split explicitly**, and the three other places that stated the old rule
  (§8's calendar edit window, §9's "two consequences", §15's screen breaks) were corrected with it, as were
  CLAUDE.md and ADR 0001. Two tests that pinned the removed rule were rewritten: the gap before a pinned block
  is now worked (and the obstacle still *cuts* the chunk — the block after it is a fresh whole minimum, not
  the remainder a screen break would give back), and the week-long fill now asserts the No-idling property
  itself instead of "no panel shorter than a minute".

- **Mode 2's cover reached nothing.** *"Mode 2: $now line$ must be covered by the period 'no on-screen
  task'"*. `DynamicPeriods.awayCover` was split out of `periods()` so the calendar would stop drawing a
  synthetic **Away** band, and the split (2026-08-31, in a commit about clipping records) dropped it from the
  **scheduler** too: nothing called it but tests, `dynamicPeriodPanels` returned only the three periods, and
  `tpMode` reached `screenBreakPanels` and nowhere else. Measured: one on-screen task, mode 2, `RefreshSchedule`
  ⇒ the task was scheduled AT the line and nothing covered it. The cover is now built in `fillSchedule`
  straight into `restrictions` — an environment period, never a panel, so the band stays gone. Its forward
  reach is `[now, now + 1)`: the README's end is CLOSED, so in discrete time it covers the line's own instant.
- **The frozen past was not frozen.** *"The schedule at t < $now line$ never changes as $now line$ increases."*
  A re-plan cut the whole straddling auto panel and regenerated from `now`, and the advance banks a panel only
  once it has *wholly* elapsed — so the elapsed head was neither a panel nor a record. Measured: plan, advance
  ten minutes, change a minimum ⇒ `t ∈ [now-10min, now)` went from a task to nothing, and the walk's clock
  replay lost the service with it. The head is now kept, truncated at the line, and the chunk the line is in
  the middle of **resumes** (`resumedHead` → `pending`, the reference's `Walk.run`) instead of being re-picked:
  the block is one minimum long where a fresh pick made it a minimum plus the elapsed head, and `lastRun` no
  longer refuses the very task that is running.
- **Only the percentages blended.** The rule state is *"tasks and their associated priority percentages,
  minimum execution time and resilience values"*, all of which *"transforms evenly"*. `blendedTaskAttributes`
  took whole `Task` objects with the LIVE map preferred, so a task on a keyframe stating a 30-minute minimum
  was scheduled with the 90 the tree on screen happened to hold. Both now interpolate. A resilience is read
  through `PeriodKinds.resilienceFor`, so an absent kind is at its *default* on that side, not at zero; a task
  only one keyframe holds keeps that side's values throughout and only its percentage fades — the reference's
  `RuleStates.at`.

Four tests that pinned the old behaviour were rewritten (`SchedulerSchedulerTest`, `SchedulerPlanTest`,
`ForcedTaskStartTest`, `ForcedTaskSwitchTest`): three read "the first slot the fill places" as "the first auto
panel", which the retained head is now; the fourth asserted outright that nothing survives across the line.
Two control cases used a recorded effort *shorter* than the task's minimum — which is now a chunk that resumes
— and were given a whole minimum so the contrast they draw is real again.

### A relative-priority chain link is a task cell — 2026-09-03

`shared` (`scheduler/ui/TaskSchedulerScreen.kt`, `App.kt`) + `CLAUDE.md` + ADR 0004 + PRD §5. **Client
rebuild only** — no Supabase deploy, no SQLite migration, no payload change: this is drawing.

A link of an occurrence chain was a compact chip — a bordered row holding a title, a percentage and the pin —
while the same cell in the tree is a `TaskRow` carrying the task's own colour (ADR 0013), the active/grid
border and the shared title and percentage columns. One cell therefore looked like two different things
depending on which window was showing it, which is the drift `TaskTreeView` exists to prevent, one level
down. The old reason for the chip argued about the row's *callbacks*; those are parameters, and what the
user reads is the drawing.

- **The chain link now IS the tree's row.** `RelativePriorityChainCell` calls `TaskRow`, so a cell reads the
  same wherever the app draws one.
- **Minus the three columns a chain has no use for**: the expansion arrow (a chain is a path — nothing opens
  under a link), the minimum time and the categories. `TaskRow` grew `showExpandArrow` and `showMinTime` for
  the first two; the third was already "no `categoryCell`". Those three are the whole of what a row may
  leave out.
- **The percentage is the link's share of its OWN sub-list**, in place of the tree's absolute priority — the
  factor the chain multiplies out — and keeps the tree's behaviour whole: a click opens that sub-list's
  weight window, a right-click the percentage's own two-option menu. Both are hoisted to `App.kt`
  (`onOpenWeightWindow` / `onOpenRelativePriority`), since the two windows are sort-2 pop-ups sharing the top
  layer and opening either closes the other.
- **The pin takes the column the minimum time and the categories vacate**, and the row's own active border is
  what says "pinned" — the window has no selection for it to be confused with. Selection, drag-move and Edit
  Mode are given nothing to do, and the §13 menu stays with the surfaces that draw the tree.

### A resize edge keeps its hover bubble, and a shared-width edge gets its own cursor — 2026-09-03

`shared` (`ui/CalendarUi.kt`, `ui/PlatformCursor.kt` + its five actuals, new
`commonTest/CalendarHoverTilingTest.kt`) + `CLAUDE.md` + ADR 0002. **Client rebuild only** — no Supabase
deploy, no SQLite migration, no payload change: this is drawing and pointer input.

Hovering a panel's top or bottom edge showed the resize cursor and the info bubble **disappeared**. The strip
was a second layer — a 6 dp `Box` holding nothing but `pointerHoverIcon` — and a `pointerHoverIcon` node is a
pointer-input node, so it won the hit test and the hover tile beneath it stopped receiving `Enter`/`Move`. The
bubble was not flickering there; it was never reported. The same layer, with the same effect, was the
Overlap-Mode **width handle**: a 10 dp `Box` over the boundary between two width-sharing panels, blanking both
their bubbles along every shared edge, and carrying **no** cursor at all — the one edge in the calendar that is
dragged sideways was the one that never said so.

- **The cursor now rides the hover tile itself.** `bubbleHoverZones` takes `extraCuts`, so the grab strip is a
  tile of the element's OWN tiling (same sections as the rest of it) with `pointerHoverIcon` on it;
  `CalendarHoverTiles` is the single drawing of that, used by the block's slices and by the handle's two
  halves alike, and `blockBubbleOverlays` is the one reading of what a panel says about itself — so a handle
  lying over a panel reports exactly what the panel would have.
- **A shared-width edge shows the horizontal resize cursor** (`horizontalResizePointerIcon`, desktop
  `E_RESIZE`), the counterpart of the vertical one; the platforms with no OS resize cursor keep the crosshair.
- **The strip the cursor promises is the strip the press grabs**: one `edgePx`, read by the gesture and by the
  tiles through `rememberUpdatedState` (the gesture coroutine outlives a zoom, and used to keep a stale one).
- **The drag/resize gesture and the right-click menu are unchanged and never were at risk** — both live on
  ancestors of these tiles (the block's slice, the day column), which stay on the hit path of whatever tile is
  hit, and `calendarTitleHover` consumes nothing.

### Waking from device sleep is a JOURNEY of the now-line, walked in mode 2 — 2026-09-03

`side-dev/README.md` § *$now line$* + § *Progressive Calculation*'s direct consequence. `shared`
(`scheduler/domain/SchedulerDomain.kt`, `scheduler/engine/SchedulerEngine.kt`, new
`commonTest/NowLineSweepTest.kt`) + `CLAUDE.md`. **Client rebuild only** — no Supabase deploy, no SQLite
migration and no payload change: everything added here is derived, local and recomputed.

The README gained the consequence in as many words: *"If the device bearing the running process is put to
sleep, then when the program wakes up, the $now line$ does a fast move forward (in epsilon time) in mode 2 to
the current date. If the current date is beyond the definitive schedule, then it is similar to a case where no
CPU were available during this period and the current set of rules, parameterized by $now line$ and $now line$
mode, is used to define the schedule as the $now line$ does its fast move, while no better set of rules was
found."* The app did none of the three.

- **The line TELEPORTED.** `reportTimeGap` was `dispatch(ReportDeviceSleep) ; advanceTo(sleepEnd)` — one
  commit for a whole night, which *"the $now line$ moves continuously forward in time"* forbids and which
  `DynamicPeriods.instances`' own doc already said could not happen (*"never read it as the line having
  jumped — under the README the line cannot"*). It is now `sweepNowLineTo`, walking **one minimum execution
  time at a time** (`SchedulerDomain.sweepStepMillis`, the reference's `Walk._sweep_step`). The ordinary tick
  goes through the same walk and is one commit, so nothing on the hot path changed (ADR 0009); a debug clock
  leap is walked too, in the live mode, since only the clock moved there.
- **The mode was read at the ARRIVAL.** A woken machine is unlocked, so `tpModeNow` answered mode 1 for the
  entire suspension — the one mode it was not in. The journey now holds its own mode (`sweepMode`, read
  through the same single reading), which is mode 2 for a suspension because that is what a suspension *is*.
- **Mode 2's cover waited on the OS lock scan, and a break fell due the instant the user came back.** Mode 2
  is *"the $now line$ must be covered by the period 'no on-screen task'"*, so a stretch swept in mode 2 is
  covered by one — a fact about the mode, owing nothing to any device observation. The app instead learned
  about the night only from `launchNoScreenEvidenceScan`, on a **10-minute** bucket behind a PowerShell
  process launch, and a pause that has merely *ended* reaches the recurrence bars by no other route
  (`liveRestPeriod` only ever holds the one this device is in the middle of). So for up to ten minutes after
  every wake the bars still counted from the last recorded break, and the owed chain was dragging at the
  line. `noteSweptNoScreen` puts the swept stretch into the same `noScreenEvidence` funnel at once
  (`publishNoScreenEvidence` publishes the union, so a scan that sees nothing can no longer un-say the mode),
  and the night bars the 20 s for 20 min and the 15 min for 2 h exactly as the README says.
- **The journey does not re-plan**, which is the README's own answer for a date beyond the definitive
  schedule: every step is an ordinary `AdvanceSchedule` (the plan in force writes the past it passes) and the
  re-plan belongs to the landing, through `requestReschedule`.
- The one approximation is the stride widening to keep a very long journey inside `MAX_SWEEP_STEPS` (2 000) —
  the README's *"if exact schedules cannot be found in time, approved approximation strategies must be
  used"* — and it is logged in the diagnostics timeline when it bites. 8 h at a 15-minute minimum is 32 steps.
- `ProgressiveSchedule.advanceTo` has been the faithful port of the reference's continuous walk all along, but
  nothing calls it; the live path is the engine's tick, and that is what had to satisfy the README.

### A break the app CONDUCTED bars the next 20 s period, like every other dynamic period — 2026-09-03

`side-dev/README.md` § *$now line$ and 3 Dynamic Restrictive Period*, PRD §15. `shared`
(`scheduler/model/TaskModels.kt`, `scheduler/domain/DynamicPeriods.kt`,
`scheduler/domain/SchedulerDomain.kt`, `scheduler/state/SchedulerReducer.kt`,
`scheduler/engine/SchedulerEngine.kt`, `scheduler/persistence/SchedulerStateCodec.kt`) + `CLAUDE.md`.
**Client rebuild only** — no Supabase deploy and no SQLite migration; the payload gains one panel flag that
defaults to `false`, so an older build's DB loads unchanged.

Reported: at 12:40 the user saw the two voice cues for a 20 s break due at 12:39, pressed **"Look away
now"**, sat through it — and the instant it finished the now-line was dragging *another* 20 s break. (The
12:39 occurrence correctly showed nothing: mode 1 pushes a break the line reaches ahead of it, so it never
happened.)

- **The README's FIRST bar keys on a dynamic restrictive PERIOD; the other two key on a rest STRETCH.**
  *"After any dynamic restrictive period, no 20 s period in the next 20 minutes"* — and twenty seconds is far
  short of either stretch threshold (>= 5 min, >= 15 min), so a conducted look-away triggered neither of them.
  The first bar was fired only from `barInstance`, i.e. only for the occurrences the walk was placing itself.
  A break that had already HAPPENED therefore barred **nothing at all**, and the owed occurrence went straight
  back to dragging at the line.
- **`TaskPanel.conductedBreak` is what says a recorded period was one of the three.** Nothing else on the
  panel could: `RecordConductedBreak` writes an ordinary `no task allowed` span, indistinguishable from a
  20-second Inactivity the user drew by hand — and that distinction is real, since the README bars nothing
  after a pre-placed period this short. It is **not** `screenBreak`, which means *regenerated by the
  placement* and is exactly why `restrictivePeriodsOf` drops those (a break is never an input to its own
  placement); this is the opposite — a fact about the past, authoritative and synced.
- **It reaches the bars as `RestrictivePeriod.dynamic`**, through the one funnel (`dynamicPeriodBase` →
  `restrictivePeriodsOf`), and `DynamicPeriods.instances` applies the 20-minute bar for each such span in
  chronological order — the same guard the rest stretches get, so a conducted break tomorrow cannot bar a
  period today.
- **Only the first bar needs this.** A conducted 5- or 15-minute pose is already a rest stretch of its own
  length, so `barStretch` gives it both its own cadence and the long-stretch bars; the 20 s look-away is the
  one period too short to bar itself.
- An older payload's conducted break decodes with the flag `false` and so bars nothing — bounded and
  self-healing: the first break *this* build records carries the mark.

### The lateral menu's "Categories": the account's own list of them — 2026-09-03

PRD §5/§7, ADR 0004. `shared` (new `ui/CategoriesWindow.kt`; `CategoryRules.kt`, `SchedulerIntent.kt`,
`SchedulerReducer.kt`, `CalendarUi.kt`, `App.kt`) + `docs/PRD_TaskScheduler.md` +
`docs/adr/0004-relative-priority.md` + `CLAUDE.md`. **Client rebuild only** — no Supabase deploy, no SQLite
migration and no payload change: a category created here is the same object the task cell's field mints, and
it rides the `categories` field that already existed.

Asked for: *"a button in the left-side menu to open the categories window that displays all the categories
the user created"*, the user being able to create one *"through the edit mode of the drop-down menu of a task
cell in the task tree, but also in the categories window"*.

- **It is the third question about categories, and the only one that had no surface.** The cell's field is
  *one task, every category*; the edit window is *one category, every task*; nothing asked *which categories
  does this account have at all*. That gap was not cosmetic — a category was reachable only THROUGH a task
  carrying it, so one whose last carrier had been deleted (or one defined for a rule before the tasks it is
  about exist) was invisible and unreachable, and the only way to define one was to give it to a task first.
- **Title order is the window's answer, not the account's.** `state.categories` is in minting order — what
  the codec and the merge preserve, and what `menuEntries` deliberately ignores in favour of match quality.
  A list you look a name up in sorts by the name; nothing else reads this order (`CategoryRules.overview`).
- **A row says what the category is doing**: its carriers, its rules, and how many of those are **asleep** —
  the figure the tree cannot show at all. The rules come from `ruleRows`, the same reading the category's own
  window prints, so the two cannot disagree.
- **No bin here.** Deleting a category takes the label off every task carrying it and every rule about it, so
  it stays in the window the row's **✎** opens — the same pair the task cell's category row makes, and the
  one the resilience row makes with `PeriodKindEditWindow`.
- **Creating is the field's create-or-attach minus the attach half.** New intent `CreateCategory`, a no-op on
  a title the account already holds (one name, one object — the whole argument for the id) and on a blank
  one; the naming field's identity rows **open** the category they name rather than attaching it, there being
  no task here to attach it to. An account setting, so it records **no** Undo/Redo unit, exactly as renaming
  and deleting one do not.
- Sort 1 (`ui/PopupWindows.kt`): there is one of it, so it stacks like every other lateral-menu window, and
  the sort-2 category window a row opens is replaced — not stacked — when the ✎ of another row is pressed.
- `CategoryRulesTest` (+5): the create with nothing carrying it, the duplicate/blank no-ops, the missing
  history unit, the title order with a rule-less and carrier-less category still listed, and a dormant rule
  counted as asleep.

### "collapse subtree" becomes "collapse sub-trees" — 2026-09-03

PRD §13. `shared` (`SchedulerIntent.kt`, `SchedulerReducer.kt`, `TaskSchedulerScreen.kt`) +
`docs/PRD_TaskScheduler.md`. **Client rebuild only** — no Supabase deploy, no migration, and the expansion set
it writes is the same `SetExpandedDelta` it always was.

The cell's menu entry is now **"collapse sub-trees"**, and it leaves the cell the user right-clicked
**expanded**: only the cells *beneath* it are dropped from the expansion set. Collapsing the clicked cell too
made the entry a slower way of clicking its own arrow — the row the user was reading disappeared along with
everything under it. Trimming back to that row is what the gesture is for. `CollapseSubtree` is renamed
`CollapseSubtrees` so the intent reads as the menu does. A cell with nothing open below it now records no
history unit at all.

### Task categories, and the rule that HOLDS a share of a sub-tree — 2026-09-03

PRD §5, ADR 0004. `shared` (new `scheduler/domain/CategoryRules.kt`, `ui/TaskCategoryField.kt`,
`ui/CategoryEditWindow.kt`; `TaskModels.kt`, `SchedulerState.kt`, `SchedulerIntent.kt`, `SchedulerReducer.kt`,
`SchedulerStateCodec.kt`, `SnapshotMerge.kt`, `RelativePriority.kt`, `SchedulerDomain.kt`,
`TaskSchedulerScreen.kt`, `TaskTreeView.kt`, `TaskListWindow.kt`, `DefaultSubtreeWindow.kt`, `App.kt`) +
`docs/PRD_TaskScheduler.md` + `docs/adr/0004-relative-priority.md` + `CLAUDE.md`. **Client rebuild only** —
no Supabase deploy and no SQLite migration (the new fields ride inside the existing `app_state.payload` /
`scheduler_snapshot` document). Persisted and synced: a payload written by this build is read by an older one
as if the fields were absent, and one written by an older build decodes to an account with no category at all.

Asked for: *"On a task cell, add a field that contains all the categories of the task"*, with a drop-down of
the task's categories each carrying a bin and an edit button, an add option that *"acts like a task cell that
enters in edit mode (without a mode selector) with the title and id suggestion lists"*; and an edit category
window where *"all tasks with this category in the sub-tree under a specific task cell always represent 33% of
priority"*, which *"would automatically adjust the priorities evenly when the user makes changes"*, with
*"an error message"* and *"no contradiction allowed"*.

- **A category is an OBJECT with an id, not a string on each task** — which is exactly what the add option
  being a task cell demands. Without the id, two tasks typing the same word carry two labels that look
  identical, a rename has to walk the tree, and a rule can only ever govern one of the spellings. What the
  field drops is the **Mode selector**: a cell's two modes are "rename my task" and "point at another task",
  and naming a category *is* pointing at it.
- **A rule is the relative-priority window's number said once and then kept.** Same quantity, same solve —
  `RelativePriorityDomain.setChainsShare`, which `setRelativePriority` is now a one-line caller of, so
  "adjust the priorities evenly" means the same thing in both places (one common factor over the cells on the
  chains; the rest of the sub-tree keeps its own proportions). The difference is *when*: the window answers
  when asked, a rule answers after every edit for ever.
- **The share is measured over the TOP-MOST carriers.** A carrier's whole sub-tree is its own, so a
  categorized task nested inside another categorized one is not counted twice — otherwise the figure is a sum
  that can exceed the sub-tree it is a share of, and "33 % of it" stops meaning anything. The downward walk
  enters a task's sub-list only from the cell that list names as its parent, making it the exact inverse of
  `occurrenceChains`' upward climb (a mirrored list is entered once — the exponential walk would have arrived
  as a wrong number first).
- **The rule is re-established after every intent, never recorded**: `reduce` is now `reduceIntent` followed
  by `CategoryRules.settle`. The weights ARE the storage, so the rule and the tree cannot say two different
  things. Enforcing at "the sites that might disturb a rule" was rejected — there is no bounded such set
  (every tree edit, weight, paste, undo, and a peer's merge). It costs nothing on an account with no rule, or
  one whose rules are already met.
- **A contradiction is refused outright** — the state comes back from *before* the intent with the reason in
  `SchedulerState.categoryRuleError`, drawn as the app's one `MessagePopup`. Two guards keep that from
  wedging the app: it never refuses what was **already** broken (a merge, an older payload, an earlier
  build's edit — else the user could not undo their way out), and a **dormant** rule (scope gone, or nothing
  under it carries the category) is not a contradiction, because deleting the last carrier is an ordinary
  edit. The four namable impossibilities are checked before anything is scaled, so the message says which two
  rules disagree; all four are about rules sharing one scope, where the arithmetic is closed.
- **Which half is undoable is the restrictive periods' split, not a new one**: defining/renaming/deleting a
  category and setting a rule are account settings and record no unit (as `AddPeriodKind` does not); a task
  **carrying** a category is a tree edit and records one (as its resilience does).
- **The clipboard carries the categories by NAME** (`- category: <title>`, one line each so a title may hold
  a comma), so a paste lands on the category of that name where the account has one and mints it where it
  has not — the same create-or-attach the row's field does.
- **The scope of a rule is a task CELL, not a task** (corrected the same day, on the user's report: *"it
  asks me under which task, but it should ask me under which task cell, since a task can appear several times
  in the task tree"* — which is what PRD §5's own example had said all along). `CategoryRule.scopeCellId`
  (`null` = the whole tree) replaces `scopeTaskId`; the window's field reads "Under which task cell" and its
  identity menu offers every cell by its own **path** ("Notes / Book"), walked exactly as `chainsFor` walks —
  each list entered once, and only from the cell that owns it, so a mirrored sub-tree is offered once while
  every mirror occurrence is a row of its own. A rule row prints that path back.
  - **What the cell names is still a LIST**, because a sub-list belongs to the task id. `CategoryRules.
    scopeKey` is the one place two scopes are compared, and it answers with that list — so "at most one rule
    per scope" and the structural-contradiction grouping treat two cells of one mirrored task as ONE scope.
    Keying them on the cell would have let the user write two rules about one sub-tree, which is the plainest
    contradiction there is and one no scaling could even tell apart.
  - Deliberate price: a rule **sleeps** when the cell it was written about is deleted, even where the task
    still appears elsewhere — the user pointed at a place, and the place is gone.
  - **Migration**: a payload written when the scope was a task resolves through `firstTaskOccurrence` (the
    cell "go to task" lands on), `task/main` becomes the whole tree, and a rule about a task no cell points
    at is dropped. `scopeTaskId` is still WRITTEN beside the cell, so a build made before this change reads a
    rule it understands instead of a payload it cannot decode.
- `CategoryRulesTest` (26 cases): the field's create-or-attach, the bin vs. Delete split, the rule being held
  through an unrelated edit, the untouched siblings keeping their ratio, the nested-carrier measure, all four
  refusals leaving the state byte-identical, both dormant statuses, the decode round trip, an older payload,
  the clipboard — and, for the cell scope, the picker telling two occurrences apart, two mirrored cells being
  one scope, a rule sleeping when its own cell goes while the task stays, the row's path label, and both
  legacy task-scoped payloads.

### The lateral menu's "Task relations": every (task, relational target) pair, in four sections — 2026-09-03

PRD §5/§7, ADR 0004. `shared` (new `scheduler/domain/TaskRelations.kt` + `ui/TaskRelationsWindow.kt`;
`TaskModels.kt`, `SchedulerState.kt`, `SchedulerIntent.kt`, `SchedulerReducer.kt`, `SchedulerStateCodec.kt`,
`SnapshotMerge.kt`, `TaskSchedulerScreen.kt`, `CalendarUi.kt`, `App.kt`) + `docs/PRD_TaskScheduler.md` +
`docs/adr/0004-relative-priority.md` + `CLAUDE.md`. **Client rebuild only** — no Supabase deploy and no
SQLite migration (the new field rides inside the existing `app_state.payload` / `scheduler_snapshot`
document), but it **is** persisted and synced, so a payload written by this build is read by an older one as
if the field were absent, and one written by an older build decodes to no pairs at all.

Asked for: *"a button in the left-side menu that opens a window showing a vertical list of pairs of a task
and its relational target, sorted by 4 sections"* — section 1 the pairs put there by hand, section 2 the
weight-table rows and the relative priorities actually changed, section 3 the ones opened and left alone,
section 4 the ones an external change has broken; *"each pair that is not in section 1 has a button to set it
in section 1. Each pair in section 1 has a button to make it disappear from this list."*

- **A relation is the pair `RelativePriorityPinKey` already existed for, asked about the ACCOUNT rather than
  about one open window** — hence the deliberately separate `TaskRelationKey`. Both of the app's two ways in
  raise it: a weight table's **optional row** (the task, and the sub-list's own parent task) and the
  relative-priority window's **`t_r` drop-down**. Until now both threw the pair away when the window closed.
- **The four sections are a PRECEDENCE, and "broken" outranks even section 1.** Broken is a *status*, not an
  origin: a pair the user filed by hand is precisely the one that most needs to say it has stopped resolving.
  The row's button therefore follows `kept` alone and never the section, so a kept-and-broken pair drawn under
  section 4 still offers the way back out of section 1.
- **Only the relative-priority window's half is STORED.** The weight-table half is derived from
  `CellList.optionalTaskIds` every time — which is what makes *"if the user then manually deleted it, it
  doesn't appear here"* true with no bookkeeping — and section 4 is a question asked of the live tree
  (`breakOf` → `occurrenceChains`, the same walk the window itself opens on). A pair naming a deleted task is
  **reported**, never pruned, so no tree edit has to keep the list in step.
- **`retargeted` is the verdict of the LAST window session, judged on the DISPLAYED number.** The percentage
  field commits every keystroke, so the window re-reports on every one of them and a value typed then put back
  demotes the pair to section 3 — the rule is about where the number ends up. The comparison goes through
  `percentFieldText` (spelled once, shared with the field) because judging the raw `Double` would call a
  bisection landing one ulp away a change nobody made; and the reducer returns the state **unchanged** when the
  verdict has not moved, so those keystrokes reach neither the save debounce nor the wire.
- **`TaskRelationMark` is three independent flags and its mere EXISTENCE is the fourth fact** (an all-false
  mark is "opened, never changed"), so it is never dropped as empty and the codec round-trips it. `hidden`
  outranks every source, live weight-table rows included — "disappear from this list" means the list — and only
  a real retarget lifts it.
- Authoritative + synced, merged **per pair as a whole value** (three flags, one statement), and **not** an
  Undo/Redo unit — filing a pair changes no priority, exactly like a relative-priority pin.

### Edit Mode's id menu shows six rows at a time and scrolls — 2026-09-02

PRD §4. `shared` (`ui/CalendarUi.kt`'s `EditModeMenuBlock` / `EditMenuSection`) +
`docs/PRD_TaskScheduler.md` + `CLAUDE.md`. Client rebuild only — no Supabase deploy, no migration, no
persisted or synced state (a menu's scroll offset is Compose-only state, like the calendar's zoom), no
history unit.

Asked for: *"when the id suggestion list has something to show, it currently shows all of them, even if the
title suggestion list has something to show. There must be a limit on the number of elements the list shows
at a time, so that the user can still see the list below, without having to scroll for a long time if there
is a lot of id suggestions. The user can naturally scroll through the id list."*

- **The bound is a VIEWPORT, not a `take`.** The identity menu keeps every row it was given and scrolls past
  `EDIT_MENU_IDENTITY_VISIBLE_ROWS` (6) of them, where the **title suggestions** are still truncated at
  `EDIT_MENU_SUGGESTION_LIMIT` (8). The two are different questions and the difference is deliberate: a
  suggestion is a guess the field offers, so eight of them is the whole offer, while an id row names one task
  the user may be looking for — dropping the ninth would hide it with no way back. (On the release account,
  typing `planning` produces **60**+ reachable rows, so this is the everyday case, not an extreme one.)
- **It lives in `EditModeMenuBlock`, so no caller decides it** — the same place the suggestion cap already
  lived. That makes it the answer for every naming field in the app at once: the cell's **Tasks** menu (§4),
  the task-tree selector's **Task trees** menu, the calendar edit window's **Tasks** menu (§8) and the three
  reminder editors' **Reminders** menu (§14).
- **`heightIn` bounds nothing until the rows exceed it**, so a short menu lays out exactly as before and the
  inner scroll is only ever reached by a list long enough to have somewhere to go — everything else falls
  through to the parent's scroll, which is what keeps the wheel over a two-row menu scrolling the tree.
- **The viewport is keyed on the row count**, so narrowing the list by typing another character starts it
  back at the top — the rows now on offer — rather than at the offset the longer list held.
- The row height is a plain `EDIT_MENU_ROW_HEIGHT` (28 dp, its inter-row spacing included) rather than a
  measurement: the bound is a comfort bound, and a row landing half-cut at the bottom edge is exactly what
  says "there is more below".

### An id row of Edit Mode's task menu right-clicks to "go to task" — 2026-09-02

PRD §4. `shared` (`ui/CalendarUi.kt`'s `EditMenuRow`/`EditMenuItem` + the new `EditMenuRowActions`,
`scheduler/ui/TaskSchedulerScreen.kt`'s `EditModeMenus` and `contextMenuModifier`) +
`shared/src/commonTest/.../GoToTaskTreeTest.kt` + `docs/PRD_TaskScheduler.md` + `CLAUDE.md`. Client rebuild
only — no Supabase deploy, no migration, no persisted or synced state, no history unit of its own (the reveal
commits the ordinary `SetExpandedDelta` / `SetSelectionDelta` the find bar's jump does).

Asked for: *"In the edit mode of a task cell, in the id suggestion, if the user right-click on a suggestion,
it would open a menu with the option 'go to task'. If the task doesn't exist in the task tree, then the
option is grayed."*

- **The entry is greyed, not dropped, when the task is nowhere in the tree.** That case is the normal one,
  not an error: the menu exists to offer tasks the tree does not show — a **detached parent**, a task kept
  alive only by its records, and the "New task" row, which names no task yet — and dropping the entry would
  read as "this row has no contextual menu". `SchedulerDomain.firstTaskOccurrence` returning `null` is that
  answer, said as a disabled row here and as the `MessagePopup` notice where the calendar asks it (PRD §8).
  A new test pins that the case is reachable *from the menu itself*: a detached parent is still listed by
  `changeTaskMenuEntries` while `firstTaskOccurrence` answers `null`.
- **Only the id rows have it.** `EditMenuRowActions` is the carrier and its absence is the third state: a
  **title suggestion** names a string several tasks may share, so there is no one task to go to, and the Mode
  selector names none at all.
- **It goes through `RevealCell`** — the find bar's primitive, and the very one §8's "go to task tree" uses —
  so the way in is expanded as one unit and the reveal ends the edit session first (§4's *Forced Exit*).
- **The name is "go to task", not "go to task tree", because the reveal follows the surface's own
  state/intents.** `EditModeMenus` is drawn by all three drawings of the tree, so in the "All tasks" window
  and the §4 template the jump lands on *that* window's rows — which is what "go to task" can mean there, and
  what "go to task tree" would have been wrong about.
- **The secondary press is consumed** by the tree cell's own `contextMenuModifier`, reused here rather than
  copied, so opening the menu never also *picks* the id underneath it.

**Follow-up the same day — "grayed but the task looks like it IS in the tree".** Reported as: right-clicking
a suggested id gives a greyed "go to task", yet sorting "All tasks" by occurrences shows no task below 1.
The greying was **correct**, and the cross-check could not have shown otherwise. Read off the release
account's own DB (a read-only copy — the live app was untouched): 4 **detached parents** (`m`, `jjj`,
`intelligence`, `master everything I am supposed to master given my background`) hold sub-trees containing
**5 titled tasks** (`f`, and four different tasks all titled `planning`). Each has exactly one cell — inside a
detached sub-list, which nothing reachable from the root descends into — so the tree can show it nowhere. The
account also holds **60** reachable tasks titled `planning`, which is why typing that word produces an id
menu with several greyed rows. Picking such a row still works and is the rescue path: assigning the id
re-points a visible cell at it and the sub-tree comes back, which is exactly why the entry is greyed rather
than the row hidden.

What that exposed, and what changed: **`SchedulerDomain.taskListEntries` counted "has a populated cell"
while `TaskListWindow` asked `firstTaskOccurrences` for each row's cell** and `mapNotNull`ed away what it
could not find — so those 5 tasks were counted by the sort and then silently given no row (168 entries, 163
rows on that account), and `periodKindTaskRows`, built on the same list, offered them a resilience row.
"In the tree" is now **one predicate**, `firstTaskOccurrences`, for the rows, the period-kind window and the
greying alike. The occurrence **count** is deliberately unchanged — still every populated cell, unreachable
ones included — because that is the occurrence the percentage divides over, and changing it would make the
two columns disagree (one task on that account, `personal project`, has 2 cells of which 1 is stranded; it
stays listed with 2). `shared` (`SchedulerDomain.taskListEntries`, `ui/TaskListWindow.kt`) +
`TaskListWindowTest.aTaskStrandedUnderADetachedParentIsNotInTheList` + `docs/PRD_TaskScheduler.md` §7 +
`CLAUDE.md`. Client rebuild only.

### The Change Task menu's rows get their paths back — 2026-09-02

PRD §4 *Presentation* / *Sorting*. `shared` (`SchedulerDomain.shortestTaskTreePaths` + the menu's sort and
label) + `GoToTaskTreeTest` + `docs/PRD_TaskScheduler.md` + `CLAUDE.md`. Client rebuild only.

Found while chasing a second report of the greyed "go to task" (above). The greying was right both times;
what was wrong is that the user could not tell **which** row they had right-clicked, and kept landing on a
greyed one. Read off the release account: the id menu for "planning" is **64 rows, every one of them
labelled just `planning`** — no path — and 4 of those are the unreachable ones, sitting at positions #2, #6,
#12, #63.

- **`shortestTaskTreePath` BFS'd `Task.childTaskIds`**, the denormalized field the codebase already
  documents as stale ("only tracks freshly-typed children", which is why `childTitlesLabel` reads the
  structure instead). `MAIN_TASK` held 8 of ~60 root tasks, so the walk died almost immediately and
  everything fell back to `listOf(taskId)` — the bare title. Measured on that account: **10 of 172 titled
  tasks got a path, 162 got a bare title, and 153 of those were perfectly reachable in the tree.** PRD §4's
  *Presentation* rule had effectively not been in force for the life of the account. It also flattened the
  menu's first sort key (path length) to the constant 1.
- **It now walks the cells/lists**, the same structure `firstTaskOccurrences` does and with the same rules
  (a blank-titled cell is §4's deleted one, each LIST entered once). Breadth-first, so the first path reached
  is genuinely the *shortest* — a mirrored task is named by its shallowest occurrence, where
  `firstTaskOccurrences` answers with its first in reading order. The two agree on what is *in* the tree and
  are allowed to differ on which occurrence to name.
- **One walk per menu.** `shortestTaskTreePaths` returns every task's path; `changeTaskMenuEntries` computes
  it once and hands it to the sort and to each row's label. The old per-task front end asked a whole-tree
  walk *per comparison* — tolerable only because it was returning after one step (ADR 0009).
- **Not-in-the-tree rows sort LAST.** They have no path to be short, and ranking them at a nominal length of
  1 put them first — under the cursor, which is exactly why both reports landed on a greyed entry. On the
  release account the menu now reads `root / main / find a job / planning`, `root / main / motivation /
  planning`, … for 59 rows, then the 4 bare-titled unreachable ones at the end.
- `changeTaskMenuLabel`'s "no cells point to it" is now **"the tree does not hold it"** — the same predicate
  the "All tasks" rows and the greying use, so a task stranded inside a detached parent (which `taskHasCells`
  calls present) is named by what it holds, exactly like the detached parent above it.

### "All tasks" can be sorted by similar titles — 2026-09-02

PRD §7. `shared` (`scheduler/domain/TitleSimilarity.kt`, `SchedulerDomain.taskListEntries`,
`ui/TaskListWindow.kt`) + `docs/PRD_TaskScheduler.md` + `CLAUDE.md`. Client rebuild only — no Supabase deploy,
no migration, nothing persisted or synced (the sorter stays Compose-only state).

Asked for: *"In the all tasks window, add the configuration to sort by number of tasks with similar titles.
The similarity would be a score. If for all task x, the maximum score task a has is greater than the maximum
for task b, then task a is placed before task b. If the maximums are the same, then they are sorted by the
number of tasks x that share a similar title with this maximum score."*

- **A third sorter chip, "Similar titles".** The figure is a fact about the *list*, not about a task: every
  pair of listed titles is scored, and each task carries the **best** score it reaches against any other task
  plus **how many other tasks it reaches that same best against**. The order is those two, in that order —
  which is exactly the spec's "greater maximum first, then the number of tasks sharing that maximum". Both
  follow the direction toggle; the title-then-id fallback beneath them still does not, so flipping the arrow
  never re-shuffles a block that ties on both.
- **The score is Sørensen–Dice over character bigrams**, on the case-folded, alphanumerics-only,
  single-spaced title, expressed as a **whole percent**. Bigrams because the duplicates this is for are
  near-*spellings* (`Write report` / `Write reports` = 96 %), which a word-set measure calls strangers; Dice
  rather than an edit distance because it is symmetric, needs no matrix and does not care about word order.
  The quantization is load-bearing rather than cosmetic: "the same maximum" has to be a real answer, and two
  `Double` ratios alike in exactly the same way would still differ at the seventeenth digit, so the tie-break
  would never have fired.
- **A zero is "not alike", never a tie at zero** — `matches` is 0 exactly when `best` is 0, or an account of
  strangers would have every task reporting a match against every other one.
- **Measured only when it is the sort asked for.** `TaskListEntry.similarity` is `null` under the other two,
  because it is a pass over every pair of titles and `taskListEntries` is also what `periodKindTaskRows`
  walks (ADR 0009). `TitleSimilarity.of` fills both sides of each pair from one measurement.
- **The row prints both halves** (`≈96 % (2)`), beside the occurrence count: percentages alone would leave a
  block of equally-alike tasks looking arbitrarily ordered when the bracket is what ranks them.

`TitleSimilaritySortTest` pins the metric (normalization, symmetry, the short-title and no-alphanumeric edge
cases) and the two-deep order in both directions.

### The History window lists its sources, and a row says what it is — 2026-09-02

PRD §6/§7. `shared` (`ui/CalendarUi.kt`) + `docs/PRD_TaskScheduler.md`. Client rebuild only — no Supabase
deploy, no migration.

Asked for: *"In the history window, the configuration menu must allow the user to filter only the
notifications. Each history unit must display information (it currently only shows a title)."*

The filter refactor that turned the window's per-category columns into one list took the **notification** and
**Supabase-usage** columns with them: their composables were left in the file with nothing calling them, so
PRD §7's "the History window still lists every notification the app decided to send" had quietly stopped
being true. It also dropped everything a row said beyond the label — the single list called
`HistoryUnitRow(position = 0, applied = true, isCurrent = false)`, so no row carried its position, its
category, its timestamp or the pointer.

- **The list is one merged timeline over three kinds of source.** `FilteredHistoryEntry` is a sealed
  interface (`Unit` / `Notification` / `SupabaseUsage`) ordered on `(timeMillis, chronoId)`, and
  `filteredHistoryUnits` takes the two logs beside the histories. The free-text query matches everything a
  row *shows*, a notification's title and message included.
- **The configuration menu selects sources, not just categories.** `HistoryFilterConfig` gains
  `notifications` (on) and `supabaseUsage` (off — one row per HTTP call would drown the units beside it),
  each a chip like the five categories, plus **All / None** so isolating one source is a single gesture
  rather than a click per source the user does not want. Neither log is a sixth `HistoryCategory`: neither is
  a History Unit, nothing undoes them, and nothing in the app walks them.
- **A unit's row shows what the unit is.** Its category tag, its timestamp, its 1-based position in that
  category's stack, its label, and the first three of its `Delta.details` lines with a `+N more` for the
  rest; undone units are dimmed and the pointer is marked `● current`. Those five facts are list-derived, so
  they are computed in `filteredHistoryUnits` and carried on the row — the unit still knows none of them, and
  the information window still shows nothing but the unit's own data.
- **The list draws its newest 200 matching rows** and says how many older ones matched. ADR 0009: it cannot
  be a `LazyColumn` (selection holds the composed nodes) and every floating window shares one Compose scene,
  so with 1000 units per category an unbounded merged list of multi-line rows is tens of thousands of text
  nodes redrawn on every animated frame. The filter, not the scrollbar, is how the user reaches what is below
  the bound.

### Adding a History Unit is one INSERT — 2026-09-02

→ ADR 0007. `shared` (`scheduler/persistence/SqlDelightSchedulerStore.kt`, `HistoryDigest.kt`,
`SchedulerStateCodec.kt`, `SchedulerStore.kt`, `scheduler/state/SchedulerState.kt`, `Scheduler.sq` +
**`10.sqm`**). Client rebuild + a release redeploy — no Supabase deploy.

Asked for: *"Each typing must trigger a save and update message on the websocket (with a debounce timing).
The simple addition of a history unit must be efficient (for example insert of a new row in a DB)."*

The typing → save → push chain already existed (400 ms to SQLite, then 500 ms to `reconcile()`, whose row
write each peer hears about over the `postgres_changes` websocket). What did not was the *efficiency*:
`save()` deleted every `history_unit` row of the account and re-inserted the whole list, because the row key
was the unit's **dense index** and the cap evicts from the **front** — one new unit renumbered all 1000, so
no row could be reused. ~70 MB rewritten per keystroke burst on the release account.

Four changes, all four needed:

- **`seq` replaces `ordinal` as the row key** — allocated once per unit, never renumbered.
  `HistoryRow.ordinal` stays the dense index the app speaks in; `load()` derives it from the seq order.
- **A save diffs the digest** (`selectHistoryDigests` + `HistoryDigest`: length + FNV-1a 64), never the
  deltas — reading those back to compare them is what the rewrite existed to avoid.
- **`delta` becomes the LAST column**, which is why 10.sqm rebuilds the table rather than altering it: with
  the digest columns after it, SQLite walked the delta's overflow pages to reach them and the scan cost
  36 ms per save instead of 3 ms.
- **`SchedulerStateCodec` memoizes each unit's JSON on the unit** (seeded on load), because `encodeSnapshot`
  runs twice per save (once for the write, once for `syncFingerprint`) and re-serialized all 1000 deltas
  each time. It also stops building the histories into a payload it then strips.

Measured over 1000 units / 54 MB, adding one unit: encode **267 ms → ~2 ms**, DB write **~500–870 ms →
~25–40 ms**.

Rehearsed against a copy of the real 106 MB release DB: the v10 -> v11 table rebuild costs **842 ms** at the
first launch after the deploy, the first save **1.4 s** (it heals 2 223 carried-up digests), and every save
after that **81 ms**.

The digest columns are deliberately **not backfilled** by the migration (SQL cannot compute the hash, and
its `length()` counts code points where Kotlin counts UTF-16 units): a carried-up row matches on the rest
and is healed — two integers, no delta rewritten — by the first save that reuses it. So no account pays a
full rewrite at upgrade.

Still whole-document: the **wire** payload (ADR 0005) — a push ships the entire snapshot, history included.

### The desktop database is opened WAL, with a busy timeout — 2026-09-02

→ ADR 0007. `shared` (`scheduler/persistence/FileSchedulerStore.kt`),
`scripts/internal/release-launch-acc3.bat`. Client rebuild + a release redeploy
(`account3-deploy-windows.bat`) — no Supabase deploy.

Reported as: the release app on Windows crashing with a fatal `Error` box reading
`[SQLITE_BUSY] The database file is locked (database is locked)`.

A file-backed `JdbcSqliteDriver` opens **one connection per thread** (SQLDelight's
`ThreadedConnectionManager`; only an in-memory URL gets a single shared connection), so the app is a
multi-connection SQLite client of its own file — the save debounce, `applyRemoteSnapshot`, the UI thread's
`flush()` and the engine's `saveActiveSessions`/`saveSleepGaps`/`saveSyncMeta`/`saveCheckpoint` all write on
connections of their own. The driver's defaults are `journal_mode=delete` (a writer takes an EXCLUSIVE lock
on the whole file and turns away every other connection) and a **3 s** `busy_timeout` that THROWS rather
than waits. `save()` rewrites the entire Undo/Redo history in one transaction — **~72 MB** on the release
account (1.4 MB `app_state` + 70.5 MB over 2 219 `history_unit` rows) — which outlives 3 s, and the throw
escapes an unguarded save site into the packaged launcher's error box. So the crash was a function of how
long the account had been used.

`FileSchedulerStore.connectionProperties()` now opens every connection with **`journal_mode=WAL`**,
**`busy_timeout=30000`** and **`synchronous=NORMAL`**. `release-launch-acc3.bat` additionally WAITS for the
old instance to leave the process list instead of starting the replacement immediately after `taskkill` —
the schema create/migrate the driver runs at construction is a write transaction and was racing the dying
instance's file handle.

`DesktopStoreConcurrencyTest` pins both halves (WAL through a second connection; a rival holding the write
lock past 3 s while the store's write still lands). It is also the only test that exercises the threaded
connection manager at all — every other jvmTest store is `IN_MEMORY`, which is why this shipped.

**Still open:** `save()` is O(whole history) and rewrites all of it on every quiet 400 ms of editing. WAL
stops the crash; it does not make that write cheap.

### "All tasks" is the task tree, drawn a third time — 2026-08-29

→ PRD §7. `shared` (`ui/TaskListWindow.kt`, `scheduler/state/TaskListProjection.kt`,
`scheduler/state/SchedulerIntent.kt` + `SchedulerReducer.kt` + `SchedulerState.kt`,
`scheduler/ui/TaskTreeView.kt` + `TaskSchedulerScreen.kt`, `scheduler/domain/SchedulerDomain.kt`,
`App.kt`). Client rebuild — no Supabase deploy.

Asked for: *"In the All tasks window, it must be task cells."* The window listed each task as a plain
`Text` row with two numbers beside it — a second, much poorer implementation of a row the app already has,
with no menu, no Edit Mode, no sub-tree and no keyboard. It is now the **same `TaskTreeView`** the account's
tree and the §4 template are drawn by, over `projectTaskList(rootCells)`: the **live** state re-rooted at a
synthetic list (`TASK_LIST_ROOT_ID`) holding, in the sorter's order, the first occurrence cell of every
listed task, with its intents wrapped in `SchedulerIntent.InTaskList(inner, rootCells)`.

The rows are **real cells of the live tree**, never synthetic ones — that is what makes an edit there an
edit to the tree with no translation, and what keeps the occurrence counts and percentages honest (both are
read off `state.cells`). Re-rooting is the whole of the projection, so the visible order, `Ctrl+A`, the
arrow keys and Ctrl+F's walk follow the window's rows for free.

New in the window, and each one a consequence of the root being the *sorter's* order rather than the tree's:

- **"go to task tree"** on every row's §13 menu — the calendar panel's entry, under its own name and through
  the same `RevealCell` primitive. `App.kt`'s handler was hoisted so both surfaces call one function;
  `TaskCellMenuActions.onGoToTaskTree` is null in the tree (you are already there) and in the template.
- **"Collapse all"**, beside the sorter, closing every row the window has open.
- **"collapse subtree"** in a task cell's right-click menu, closing the clicked row and every expanded descendant in its own branch.
- **Nothing may be moved into the root.** The blue drop line never appears at root level
  (`TaskTreeView`'s `allowRootDrop`), and `reduceInTaskList` refuses such a `MoveSelectedCells` as the
  backstop.
- **A root row has no Mode selector — it is always renaming.** `SchedulerIntent.BeginEdit` gained an
  optional `mode`, set by `reduceInTaskList` (the one place that knows which cells are roots);
  `EditModeMenus` hides the selector to match. A cell that is not one of the rows still opens on §4's
  default.
- **"Update order"**, which appears exactly while an edit has moved a row's figure enough to re-sort the
  list. The displayed order is pinned (Compose-only, like the sorter), so editing a row cannot re-sort the
  list from under the cursor; a task created since is appended and one deleted drops out.

Three things the re-rooting could have broken, and how each is held:

- `pruneDetachedTree` now seeds `WellKnownIds.MAIN_LIST` **as well as** `state.rootListId`. A real root cell
  that is not the first occurrence of its task is reachable from neither the synthetic root nor a detached
  parent, so the first edit boundary in the window would have pruned it out of the tree. Every other caller
  is unaffected — for them the two ids are the same list.
- The **colours** are solved over the live state (`TaskTreeView`'s new `colorSource`), not the projection,
  whose ring would be ordered by the sorter. ADR 0013's identity — one hue per task across the tree, this
  list and the calendar — holds by construction, and the shared `TaskHueMemo.account` cache is not thrashed.
- The **synthetic list never escapes**: `withTaskListCapturedFrom` drops it, so it is in no history delta,
  no persisted payload and nothing on the wire.

The window's expansion, selection and edit session are its own (`taskListExpanded` / `taskListSelection` /
`taskListEditSession`) — local view state, never persisted or synced, so a row open here is not a row open
in the tree and moving the caret here can never enqueue a push. One gesture is **one** Main history unit
(`TreeMutationDelta`, "All tasks"), like the template window's. `SchedulerDomain.firstTaskOccurrences` is
the new one-walk plural of `firstTaskOccurrence` (one walk, not one per row — ADR 0009's display hot path).

Pinned by `TaskListRowsTest`; `TaskListWindowTest` still pins the domain's ordering unchanged.

---

### A grey period is never manufactured from evidence — 2026-08-29

→ ADR 0002. `shared` (`scheduler/state/SchedulerReducer.kt`, `scheduler/platform/WindowsPowerLog.kt`).
Client rebuild — no Supabase deploy.

Reported as: *"I run `scriptsccount3-deploy-windows.bat`, and I see a gray panel in the calendar as an
inactivity layer."* The deploy stops the running app, builds for minutes, reinstalls and relaunches; the app
banks nothing while it is down, and on restart `materializePastInactivity` wrote every elapsed span where
scheduled on-screen work met a no-screen period into `state.panels` as a real `no task allowed` period.
218 of them had accumulated on the release account.

Deleted, with its two call-site helpers. Grey appears for exactly three reasons — a covering period every
task has 0 resilience to, a period the user drew, or past beyond the app's memory — and "the devices
observed no screen" is none of them. It is evidence, so it stays derived: the calendar already paints the
vacated stretch as a derived grey band, and the display is unchanged. Existing materialized panels are left
in place (a hand-added inactivity period is the same object, so decode cannot tell them apart);
`materializePastSleep` stays, because a sleep session is a fact the user asserted with the toggle.

Alongside it, `WindowsPowerLog.debounce` made its cancellation **provisional**. A bounce cancelled as jitter
used to leave a genuine later wake with nothing to pair against, losing the absence outright — two wakes
with no sleep between them is not something the machine can do, so the earlier one is the spurious half.
Observed the same day: `506`@15:12:40, `507`@15:12:41, `507`@15:26:53 lost a real 14-minute standby, which
the §9 record bank then banked straight through as time at the desk. The restored pair is still re-tested
against the minimum dwell, so brief flips stay jitter. This reverses
`a_bounce_that_leaves_an_unmatched_wake_claims_nothing`.

---

### The now-line carries its seconds — 2026-08-29

→ ADR 0002, PRD §8. `shared` (`ui/CalendarUi.kt`). Client rebuild to see it — no Supabase deploy.

Three reported calendar anomalies, one cause. Zoomed in far enough for a 20-second look-away to be visible,
the calendar showed: a "no phone unlocked" hatch running past the now-line (*"how can it know in advance
there won't be any phone unlocked?"*), an Inactivity band after the now-line, and an "Open: Device" line on
a panel that looked entirely future.

- **The current-time indicator was drawn at `hour + minute / 60`**, i.e. at the top of the current minute,
  while every band, panel, layer region and device segment around it is placed to the second
  (`recordsForDay`). At zoom 1 a minute is 0.7 dp and the gap never showed; at the zoom `MAX_CALENDAR_ZOOM`
  exists for, a minute is well over a hundred pixels, so the line sat up to 59 s above where it belonged and
  everything the calendar had placed truthfully read as being on the wrong side of it. In the report the
  line was drawn at 16:41:00 with the real instant at 16:41:47: the layer ended exactly at `now`, the grey
  band ended at 16:41:16 (before it), and 30 s of the "planning" panel really had elapsed.
- **`LocalTime.hourOfDay()` is now the one reading**, used by the indicator, by the lock-to-now centring
  fraction (which must centre on the line the user can see) and by the reminder stack's now-line anchor.
  Nothing about the layers, the bands or the record bank changed — they were already right.

### A device is named by the first session row that states a kind — 2026-08-29

→ ADR 0002, PRD §8. `shared` (`ui/CalendarUi.kt`). Client rebuild to see it — no Supabase deploy.

The hover bubble's "Open: …" line called a month-old desktop install **"Device"**. `ActiveSessionRecord.kind`
post-dates the earliest sessions (schema v8), and the label was taken from the device's *oldest* row — blank
on any account older than that column — so an install that has been recording `"desktop"` ever since stayed
anonymous forever. A kind is a fact about the DEVICE, not about one session: `deviceLabels` now takes the
first row that actually states one, keeping the first-appearance order so the `Phone 2` / `Phone 3` numbering
never shifts. Spelled once and shared by `deviceActivitySegments` and `DeviceActivityIndex`, which
`CalendarDisplayEquivalenceTest` already pins together.

### The hour before bed is covered by the period "before bed" — 2026-08-29

→ ADR 0001, PRD §17. `shared` (`scheduler/domain/PeriodKinds.kt`, `scheduler/domain/SchedulerDomain.kt`,
`scheduler/engine/SchedulerEngine.kt`, `scheduler/state/SchedulerReducer.kt`, `ui/CalendarUi.kt`, `App.kt`).
Client rebuild to see it — no Supabase deploy.

User spec: *"The hour before bed is covered by the period 'before bed'."*

- **`PeriodKinds.BEFORE_BED` (`"before bed"`) is a third BUILT-IN kind.** The §17 wind-down was a hard-coded
  extension of the sleep obstacle — a second mechanism for "where may this task run", which is exactly what
  the resilience model exists to prevent. It is now one period of one kind:
  `SchedulerDomain.beforeBedPanels` lays `[bedtime − 1 h, bedtime)` for every §17 sleep window, and the hour
  is empty for the one reason any period empties a stretch — every task's default resilience to the kind is
  `0`. **A task given a value above zero now works through the wind-down**, which nothing could do before.
- **Derived from `sleepPanels`, never from a second reading of the schedule**, so the hour drifts with the
  wake time it is measured back from. `before-bed/{wake day}` is cut and regenerated by every fill, is an
  `isRegeneratedPanel` (out of the sync fingerprint and out of `schedulingSignature`), and is drawn as a
  grey band labelled **"Before bed"** with no Edit and no Remove — the "Sleep" band beside it is where the
  schedule that produces both is edited.
- **The wind-down cue keys on the period's own start.** `SchedulerEngine`'s `windDownInstants` reads the
  panels the fill laid instead of recomputing `sleep.start − 1 h`, so the notification and the band cannot
  drift apart.
- Built in, so it is neither definable again nor deletable (`isUserDefined` gates both), and an older
  payload holding a *user-defined* kind of that name collapses into it on decode with every task's override
  intact. Editable, unlike `no task allowed`: the task edit window shows it a row, and it has its own
  `PeriodKindEditWindow` (minus Delete).
- **Not `coversNoScreen`**: the user is still at a screen in that hour (PRD §17 lets the screen breaks fall
  in it), so the wind-down absorbs a dynamic period like any other emptiness but never counts as a **rest**
  that bars the breaks after it.
- Two general readings were tightened on the way: the reducer's `isTaskPanel` is now
  `!panel.isRestrictivePeriod` (a period of a kind with no legacy flag is a period too),
  `clipPlanForPinnedScreenBreak` never cuts a restrictive period of any kind, and a derived grey band now
  names itself (`decorativeBandLabel`) instead of always reading "Inactivity".
- Behaviour change on the plan: work now stops at **bedtime − 1 h** and the hour is a suspending region, so a
  chunk that meets it resumes after the night with its minimum intact.
  `SchedulerSleepTest.the_work_plan_stops_at_the_wind_down_hour_and_resumes_after_the_sleep_window` and the
  new `BeforeBedPeriodTest` pin it.

### A period is an object: its own edit window, and a new one now restricts — 2026-08-29

→ ADR 0001. `shared` (`scheduler/domain/PeriodKinds.kt`, `scheduler/domain/SchedulerDomain.kt`,
`scheduler/state/SchedulerIntent.kt`, `scheduler/state/SchedulerReducer.kt`,
`scheduler/ui/TaskSchedulerScreen.kt`, `App.kt`). Client rebuild to see it — no Supabase deploy.

User spec: *"a `+` button must allow the user to add a new period, which adds it to every other task with the
default value 0. Every displayed period must have a button to edit the period. This button opens a type 2
window with a button to delete this period, and a list of all the tasks with boxes to check, and their
resilience value to this period. A button allows the user to select all. When at least one task is selected, a
field appears at the top. If all selected tasks have the same resilience value, the field shows this value,
otherwise it is blank."*

- **`PeriodKinds.defaultResilience` is now `0` for every kind but `no on-screen task`** (it was `1` for
  everything but `no task allowed`). A restrictive period restricts: a kind the user has just defined turns
  **everybody** away until its own window lets somebody back in. Still nothing written to any task — absence
  *is* the default, so defining a kind stays free and a task created later carries the same answer.
  `no on-screen task` keeps its `1` because "on screen" is a `0` against it.
  - **Behaviour change on existing data, deliberately un-migrated:** a task that never overrode a
    user-defined kind was unaffected by periods of that kind and is now forbidden in them. Nothing decodes
    wrong (a stored `1.0` is now the override and is honoured; a stored `0.0` is dropped as redundant) — the
    plan simply follows the new default.
- **The task edit window's resilience section**: the "Add" button is a **`+`**, and each row's `×` is
  replaced by a **`✎`** onto the period's own window. Deleting a period moved into that window.
- **`PeriodKindEditWindow`** (sort 2, raised in `App`): the kind's name, **Delete** for a user-defined kind,
  every schedulable **leaf** with a check box and a percentage (`SchedulerDomain.periodKindTaskRows`),
  **Select all / Select none**, and — while anything is checked — a **bulk field** holding the shared value or
  **blank** where the selection disagrees (`SchedulerDomain.commonResilience`, `null` being a real answer).
- **`SchedulerIntent.SetPeriodResilience(taskIds, kind, value)`** is the one write for both the bulk field and
  each row: one gesture, **one** Undo/Redo unit, and no unit at all when it moves nobody. A fan-out of
  `SetTaskResilience` would have cost twenty `Ctrl+Z` to put back one edit.
- Opening the period window dismisses the task edit window it came from — the sort-2 price, not a bug.
- Pinned by `PeriodKindWindowTest` (rows, the blank field, the single history unit, the clamp/overrides-only
  rule, delete) and by the updated `TaskResilienceTest` (the new default, and the decode heal).

### An observed pause barred no screen break — FIXED 2026-08-29

→ ADR 0003. `shared` (`scheduler/domain/SchedulerDomain.kt`, `scheduler/engine/SchedulerEngine.kt`,
`scheduler/state/SchedulerReducer.kt`, `App.kt`). Client rebuild to see it — no Supabase deploy.

Reported anomaly: both calendar layers said nobody was unlocked from 12:15 to 12:28, and a **5-minute pose
was owed at 12:40** — thirteen minutes into the hour `side-dev/README.md` bars it in (*"after any ≥ 5-minute
stretch covered by 'no on-screen task' without any task, no 5min period in the next 1 hour"*).

- Cause: the recurrence bars read their rest stretches out of the **timeline** (ADR 0003 — there is no stored
  `lastRest`), and only two things ever put a pause on that timeline: a period the user drew, and
  `liveRestPeriod` — the pause **this device is in the middle of**, off `inactiveSince`/`activeSince`. A pause
  that had *ended* was in neither: a derive retires the live tail, and the 12:38 restart cleared it outright.
  So the bars walked a timeline in which the user had been at the screen without interruption since the
  placement origin.
- Fix: **what the devices observed is the third source**, `SchedulerDomain.observedNoScreenPeriods` over the
  `observedNoScreenRegions` the §9 record bank and the calendar's panel clipping already read — one reading of
  "nobody was at a screen here", now with three consumers instead of two. The kind is `no on-screen task`, not
  `no task allowed`: that is the whole of what the evidence says, so an account with an off-screen task
  correctly gets no rest stretch out of the same span.
- `SchedulerDomain.dynamicPeriodBase` is the one funnel the three parts are assembled in, and the **cue sweep,
  the published pause-cue due and the calendar now go through it too** — they asked with
  `restrictivePeriodsOf(state.panels)` alone, so the instant the app announced a break at and the instant the
  fill placed one at were answers to two different timelines.
- `SchedulerEngine.noScreenEvidence` exposes the engine's single cached scan (24 h window, coarse bucket, off
  the tick — ADR 0009) so the display shares it rather than deriving a second answer.
- Pinned by `DynamicPeriodsTest`: the observed pause bars the 5 min period for an hour, an off-screen task
  keeps the same span from being a rest, `fillSchedule` is actually handed the evidence, and the funnel
  carries all three sources.

### A ring lasts 3 s by default — CHANGED 2026-08-29

→ ADR 0010. `shared` (`scheduler/model/TaskModels.kt`). Client rebuild — no Supabase deploy.

- The Alarms window's **Rings for** field now opens on **3 s** instead of 30 s, for alarms and timers alike.
  A timer's ring length is the alarm's field unchanged (`AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS` is the one
  constant both read), so the two could not have been given different defaults without splitting it.
- Existing rows are untouched — `soundSeconds` is persisted per row; only a new row, and a legacy payload
  written before the field existed, take the new default.

### A panel's label was written over the day's date — FIXED 2026-08-29

→ ADR 0002. `shared` (`ui/CalendarUi.kt`). Client rebuild to see it — no Supabase deploy.

Reported anomaly: the in-grid day-date badge ("Sat 30", drawn at every day boundary scrolled into view)
and the "Inactivity" label of the big band opening at that boundary were two texts drawn at the same
point, on top of each other.

- **The panel's label gives way, the badge does not move and no panel is stretched** — the same rule the
  short screen-break band already followed for its own name (`SCREEN_BREAK_LABEL_MIN_HEIGHT`). A panel
  opening within `DAY_DATE_BADGE_HEIGHT` of midnight writes its label BELOW the badge where it has the room
  for a whole label line there, and writes none where it has not: the zoom is what grows a short panel until
  it names itself, exactly as it is for a 20-second look-away.
- `panelLabelTopInset` is the one place that answers it, applied by the grey bands ("Sleep"/"Inactivity"),
  the screen-break bands and the task panels alike; `showsDayDate` is false for the grid's top row, whose
  date is written in the header above the viewport and overlaps nothing.

### The dragged screen break drew over the task panel — FIXED 2026-08-29

→ ADR 0003/0001. `shared` (`scheduler/domain/SchedulerDomain.kt`); new case in `SchedulerSchedulerTest`.
Client rebuild to see it — no Supabase deploy.

Reported anomaly: a 20 s look-away drawn on top of a task panel. `SchedulerDomain.clipPlanForPinnedScreenBreak`
— the display clip that keeps "a period accepting no task" true between fills — was a **no-op in mode 1**, i.e.
whenever any device of the account is unlocked.

- **An off-by-one against the half-open dragged period.** Mode 1 leaves the instant $t_p$ itself free, so the
  period the line drags is `(t_p, t_p + duration]`, which in the app's discrete time starts at `t_p + 1`
  (`DynamicPeriods.Instance.coveredFromMillis`). The clip's chain walk seeded its cursor at `t_p` and asked
  for a band with `start <= t_p`, so it never found the one band it exists to cut, returned the plan
  untouched, and the owed look-away parked at the now-line was drawn straight over the auto panel the fill had
  placed there. The walk now seeds one millisecond past the line; the transitive hop is unchanged.
- **Known, still open:** the drag also **re-anchors the 20 s bar at the line**, so every *later* occurrence of
  that bar slides with the now-line while the materialized plan does not. The clip is scoped to the chain
  covering the line ("the disturbed slot is the one the cursor is in"), which is why a second overlap can show
  up one bar ahead until the next re-plan. Reproduced on account 3's state at 2026-08-29 11:50:52: the display
  grid put a 20 s at 12:11:12 where the stored plan ran `planning` 11:56:00 → 12:13:50.

### A running timer marks the calendar — SHIPPED 2026-08-29

→ ADR 0010. `shared` (`scheduler/domain/TimerDomain.kt`, `ui/CalendarUi.kt`, `ui/AlarmWindow.kt`, `App.kt`);
new cases in `TimerTest`. Client rebuild to see it — no Supabase deploy.

Reverses ADR 0010's *"deliberately not on the calendar"*. A timer's whole content is an instant it will go off
at, and the calendar is where the app says when things go off.

- **The alarms' marker, one bit apart.** `CalendarRecord.alarm` stays "this is a ring"; the new
  `CalendarRecord.timer` / `PlacedRecord.timer` beside it says which sort — mirroring `ArmedAlarm.timer`, and
  deciding the icon (⏳ / ⏰) and nothing else. Everything else the alarm marker has (fixed height, the
  stacking sweep, inertness, and the exclusions from `blockRecords` / `allBlocks` / the drag snap set / the
  task-panel menu entries) is inherited rather than re-stated.
- **Drawn at most once, and only while running.** `TimerDomain.occurrencesInWindow` bounds the projection by
  the displayed span like the alarms' (ADR 0009) and is a filter, not a per-day walk: the instant is stored,
  so an idle or paused row has none, and the ring resets the row so nothing is left behind.
- **A nameless timer is named by its DURATION**, where a nameless alarm falls back to its time of day.
  `TimerDomain.formatDuration` / `formatCountdown` is that one spelling; the Alarms window's countdown column
  now delegates to it instead of holding a private copy.

### The two `t_p` modes, wired through — SHIPPED 2026-08-28

→ `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period* / ADR 0003. `shared`
(`scheduler/domain/DynamicPeriods.kt`, `scheduler/domain/SchedulerDomain.kt`,
`scheduler/state/SchedulerReducer.kt`, `scheduler/engine/SchedulerEngine.kt`, `App.kt`); new `TpModeTest`,
rewritten assertions in `DynamicPeriodsTest`, `ScreenBreakWindowTest`, `RestPosePresenceWindowTest`,
`SchedulerCalendarTest`, `NotificationLogTest`. Client rebuild to see it — no Supabase deploy.

Both modes were implemented in `DynamicPeriods` and tested there, and **neither reached the app**. This wires
them.

- **The mode is which devices are unlocked**: mode 1 while any device of the account is unlocked, mode 2
  otherwise. `SchedulerDomain.tpMode` decides it once, `anyDeviceUnlockedAt` reads the input once — off the
  account-wide pause the calendar already draws (`displayInactivityGaps`, right edge inclusive because an
  ongoing pause's tail ends *at* the line), so the mode and the Inactivity band cannot disagree. **Replaces**
  the read of `SchedulerState.sleepingUntilMillis` (the Sleep/Work toggle), which said "gone to bed", not "no
  screen in use", and was the only thing either mode had ever been keyed on in the app. The reducer reads it
  through a new injected `SchedulerReducer.tpMode` seam, beside `liveRestGap` / `noScreenEvidence`; the
  display reads the same function over the same three engine flows.
- **A mode flip re-plans** (`SchedulerEngine.launchTpModeReschedule` → `requestReschedule`). Not a tick: the
  flip is an edge the OS announces. It cannot go in `schedulingSignature` — the mode is a fact about the
  devices, not about the account's data, and so is never synced.
- **Mode 1 drags again.** `SchedulerDomain.dynamicPeriodPanels` passed `sweepFromMillis = tpMillis`, which
  made the drag fire only on exact equality — the line swept nothing, ever. It now passes the placement
  origin wherever `tpMillis` really is the line (a new `atLine` flag; every other caller — the cue's dues, the
  pause cue's next break, a navigated week — keeps the bars' undragged answer). So an owed period sits on the
  line as `(t_p, t_p + duration]`, realized in discrete time as `[t_p + 1, t_p + duration + 1)`
  (`Instance.coveredFromMillis`) so it stays an ordinary `TaskPanel`. **Consequence, deliberately accepted:**
  while a device stays unlocked and no rest happens, the owed chain parks at the now-line and no task is
  scheduled under it, and a stretch the line crossed in mode 1 draws task panels rather than breaks.
- **The drag is bounded by the bars themselves**, which is why the fear that motivated `sweepFromMillis =
  tpMillis` was unfounded: pushing a period onto the line re-anchors its own bar there, so at most one
  occurrence per bar is swept and the chain merge collapses what piled up into the longest. A drag is also a
  *move like any other* — it goes back through the loop, so the ordinary rules still refuse to place it inside
  a stretch nobody can run in.
- **The cue keys on the DUE, not on where the period sits** — `screenBreakOccurrencesBetween`, the placement
  asked with the line left out, and the one reading of it. This is a partial reversal of "nothing slides, so
  the cue may key on the drawn start" (2026-08-27): nothing slides *on its own*, but a period the line is
  pushing is always "starting now", is never crossed, and a sweep keyed on it fires at every scan — the
  2026-07-12 "spammed every frame" failure. `nextScreenBreakStartMillis` (the pause cue's `break_due_ms`) is
  the same reading, so the server and the client still key on one instant.
- **Mode 2's cover reaches the app**: `DynamicPeriods.awayCover` split out of `periods()`, emitted by
  `dynamicPeriodPanels` as an `Away` panel of kind `no on-screen task` — so a task resilient to that kind may
  work through it and an on-screen one may not. Where the app already has live evidence (this device's
  ongoing pause) that evidence *is* the cover: `liveRestPeriod` is now `closedEnd` while the pause is ongoing,
  so it covers the now-line and `awayCover` finds nothing left to do.
- **Bars are measured from the last instant a period COVERS** (`coveredUntilMillis`), not from
  `start + duration`. A dragged period ends one millisecond later than its nominal end, and a bar read off the
  nominal end put the next 20-second period at 19 min 59.999 s.

### Timers, in the Alarms window — SHIPPED 2026-08-28

→ PRD §18 / ADR 0010. `shared` (`scheduler/model/TaskModels.kt`, new
`scheduler/domain/TimerDomain.kt`, `scheduler/engine/SchedulerEngine.kt`, `scheduler/state/*`,
`scheduler/persistence/SchedulerStateCodec.kt`, `scheduler/sync/SnapshotMerge.kt`, `ui/AlarmWindow.kt`,
`App.kt`) and `androidApp` (`AlarmClockScheduler`, `AlarmClockReceiver`, `AlarmRingService`,
`SchedulerHolder`); new `TimerTest` + `TimerEngineTest`. Client rebuild to see it — no Supabase deploy.

- **The Alarms window has a second section: Timers.** A row is a duration (`SS` / `M:SS` / `H:MM:SS`, default
  5:00, max 24 h), a label, a live countdown, start / pause / reset, and the same *Rings for* and *Vibrate*
  fields an alarm has. It rings the same acoustic-guitar loop for the same length, through the same
  notification funnel, titled *Timer*.
- **A timer is an alarm at an ABSOLUTE instant, and that is the whole difference.** An alarm's due instant is
  derived from the local calendar per ringing day; a timer's is stored, fixed the moment it was started. So
  everything downstream — the phone's OS arming, the desktop's now-line sweep, `onAlarmFire`, the ring — is
  the alarms' machinery unchanged rather than a parallel one. `TimerDomain` carries no time zone at all.
- **One OS alarm slot, so one arming loop and one sweep.** `launchAlarmArming` now combines both lists and
  arms the soonest of the two; `launchAlarmSweep` merges both crossing streams in boundary order
  (`ringCrossingsBetween`). A second arming loop would not have added a ring — it would have overwritten the
  first's. `ArmedAlarm.timer` says which list a ring came from, and travels with it into the phone's OS intent.
- **`endsAtMillis` is authoritative and synced; the remaining time is derived and stored nowhere.** Starting a
  timer on the desktop is therefore what makes the phone ring at its end — the same promise PRD §18 already
  made for alarms — while a counting-down timer writes nothing and cannot move the sync fingerprint on a tick.
  Three run states live in two nullable fields of which at most one is non-null, healed in one place
  (`TimerDomain.healed`) from decode, from the merge and from the reducer.
- **No on/off switch and no repeat switch**: a timer that is not running is already not due, and a timer is a
  one-off by nature — having rung it *resets* to its full duration, where a one-off alarm *disarms* itself
  because it has a switch to leave off.
- The countdown reads down off the window's own 250 ms poll of the app clock (only while the window is open
  and something is running), because the engine's now-line advances once per 30 s production tick. Editing a
  row's settings mid-countdown cannot disturb the instant it is due at. A timer draws nothing on the calendar,
  deliberately.

### A Notifications switch, and a chord to silence them — SHIPPED 2026-08-28

→ PRD §7/§11. `shared` only (`scheduler/platform/GlobalHotkey.kt`, `scheduler/platform/SystemNotifier*.kt`,
`scheduler/engine/SchedulerEngine.kt`, `scheduler/state/*`, `scheduler/persistence/SchedulerStateCodec.kt`,
`scheduler/sync/SnapshotMerge.kt`, `ui/CalendarUi.kt`, `App.kt`); new `NotificationMuteTest`, two new
`KeyboardShortcutsCatalogTest` cases. Client rebuild to see it — no Supabase deploy.

- **The lateral menu has a "Notifications" switch, and `Ctrl+Shift+Alt+N` is the same lever from the
  keyboard.** Off silences every notification the app posts — a break's start and end, "task to do now", the
  wind-down, an alarm, and the system-wide chords' own receipts. There is no exempt caller: they all funnel
  through `SchedulerEngine.notifyUser`, which is the one place the switch is read, and a mute that let one
  class through would not be a mute.
- **It silences the interruption, never the record.** The notification log is appended before the platform
  call, so the History window's Notifications column still lists everything the app decided to say while it
  was muted (which is also why that column was never proof of delivery).
- **Switching off also withdraws what the OS is already showing** (`cancelSystemNotifications`) — Android's
  shade and iOS's Notification Centre hold a notification until it is dismissed, so "cancel every
  notification" has to answer the pile already on screen as well as the ones still to come. The desktop
  actual is a deliberate no-op: a tray balloon cannot be recalled once shown.
- **Switching back ON posts one notification saying so.** The chord's ordinary receipt is raised *before* the
  action, so on the un-mute press it is still muted and swallowed — which would leave the one press whose
  whole subject is notifications as the only one the user cannot see landing. This is that press's receipt,
  posted from the far side of the flip.
- The switch is the fourth **rebindable** system-wide chord and the first *switch* to name a chord on hover;
  it is persisted + synced (an account preference, like the look-away voice switch beside it) and is not an
  Undo/Redo unit. It says nothing about the voice cues, and nothing about the schedule: a break still starts
  and ends where it did, silently.

### An unlock turns "I'm away" off — SHIPPED 2026-08-28

→ PRD §15, ARCHITECTURE.md §8. `shared` only (`scheduler/engine/SchedulerEngine.kt`); new
`UserAwayUnlockTest`. Client rebuild to see it — no deploy otherwise.

- **"I'm away" now clears itself when this device is unlocked.** The toggle overrides the platform screen
  sensor, so until now nothing but a second press ever took it off: leaving with the button pressed, locking
  the machine and coming back to it left the app declaring an absence that had visibly ended — the active
  session stayed finalized and the presence heartbeat stayed closed at a desk somebody was sitting at, which
  is exactly the state the server's cue gate reads.
- **The trigger is an EDGE, and it needed no polling.** The lock and the unlock are notifications the OS
  already sends and the engine already receives (`WM_WTSSESSION_CHANGE` on Windows, `ACTION_SCREEN_OFF` /
  `ACTION_USER_PRESENT` on Android, both arriving at `onPlatformActivityChanged`) — the same signal the button
  is overriding. `SchedulerEngine.noteScreenSignal` reads the lock→unlock transition of the RAW
  `screenActive()` sample off it; the active-session beat calls it too, but only as a backstop for a missed
  notification, not as a cadence of its own. No timer was added.
- Only that edge clears. A lock while away leaves the flag on (locking the machine is not coming back to it),
  an unlock with no lock behind it is not a return, and the first sample after start is no edge — an engine
  that starts on a locked device has no phantom transition to answer. A host with no session signal (a
  non-Windows JVM, a failed native install, iOS) never flips it and keeps the flag as the user set it, the
  same degradation `isScreenActive` already has there.

### "no task allowed" leaves the edit window; every screen break names itself; both layers cut the panel — SHIPPED 2026-08-28

→ `side-dev/README.md` § *Restrictive Period*, PRD §8/§9/§15, ADR 0002/0003. `shared` only
(`scheduler/domain/PeriodKinds.kt`, `scheduler/domain/SchedulerDomain.kt`, `scheduler/ui/TaskSchedulerScreen.kt`,
`ui/CalendarUi.kt`, `App.kt`); new `ObservedNoScreenPanelClipTest`, two new `TaskResilienceTest` cases. Client
rebuild to see it — no deploy otherwise.

- **The task edit window no longer offers a resilience to "no task allowed".** It is the one kind whose value
  is not the task's to pick: by its own name it accepts nobody, so its multiplier is always `0`. The rule is
  one predicate, `PeriodKinds.isResilienceEditable`, read by the window's row loop (and by App's save loop, so
  the intent can never be raised for it). It governs the **window** only — `resilienceFor` still answers for
  the kind everywhere, and an override an older payload wrote still decodes, syncs and is obeyed by the walk.
- **Anomaly: a screen break showed no name, on the calendar or in the hover bubble.** Both halves were one
  cause. The band's title was gated on a 13 dp height and the band is drawn at its true duration — a
  20-second look-away is a third of a device pixel, a 5-minute pose about five — so no break ever reached the
  gate; and the hover zones are mapped onto that same rendered height, so the hairline was too thin to put a
  cursor on and the bubble named the sleep band underneath instead. `SCREEN_BREAK_MIN_HEIGHT` is now one label
  line (16 dp) and the title is unconditional, ellipsised when the column is too narrow.
  `SCREEN_BREAK_LABEL_MIN_HEIGHT` is gone.
- **Anomaly: an on-screen task's panel crossed a stretch where neither a computer nor a phone was unlocked.**
  ADR 0002 already says a stretch carrying BOTH layers *is* a "no on-screen task" period, and a no-screen
  period overrides the on-screen task panels it covers — but only the record half of that was implemented
  (§9 refuses to bank there; the panel went on being drawn). `SchedulerDomain.clipPanelsForObservedNoScreen`
  is the other half, cutting on-screen task panels out of `observedNoScreenRegions` — the same set the record
  bank asks, so the two can never disagree. Off-screen tasks are exempt (§9 lets them run in a no-screen
  period), a restrictive period is never cut, and a **failed** own lock scan is not evidence (no regions, no
  cut) — the calendar's own "assumed locked" default would otherwise erase a whole displayed past on one
  PowerShell hiccup. Display-side, like the §15 plan clip: the regions are the past, the fill only places
  ahead of the now-line. What the cut vacates draws as a derived "Inactivity" band.
- The calendar's OS lock-history scan moved **above** the record assembly in `App.kt` (it is now an input to
  the panels, not only to the hatching). Same query, same quantized window, same off-UI-thread dispatch.

### A calendar task panel reaches its task: "edit task" / "go to task tree" — SHIPPED 2026-08-28

→ PRD §8 / §13, ADR 0002. `shared` only (`SchedulerDomain.kt`, `ui/CalendarUi.kt`, `ui/PopupWindows.kt`,
`scheduler/ui/TaskSchedulerScreen.kt`, `scheduler/ui/TaskTreeView.kt`, `App.kt`); new `GoToTaskTreeTest`.
Client rebuild to see it — no deploy otherwise.

- **A task panel's right-click menu now has two entries about the TASK**, beside the "Edit" that is about the
  panel: **"edit task"** opens the §13 edition window, and **"go to task tree"** selects the task's first cell
  in the tree. Offered on a task panel only — a period, a reminder, an alarm, a sleep band, a screen break and
  a layer region are not tasks.
- **The tree cell menu's "edit" was RENAMED "edit task"** — the calendar opens the very same window, and two
  names for one window is how two surfaces start reading as two features.
- **"First occurrence" is the tree's own reading order**, decided once in `SchedulerDomain.firstTaskOccurrence`:
  depth-first, each **list** visited once (a mirrored sub-tree is one list under many parents), skipping
  blank-titled cells — §4's deleted ones, which the reveal could not expand anyway. It returns the cell **and
  the ancestor chain**, which is exactly what `RevealCell` takes, so the jump reuses the find bar's primitive
  (expand the way in as ONE history unit, then select) rather than growing a second selection path. The tree
  is then focused, which is what "going to" it means and what re-arms its keyboard.
- **`null` is a real answer**: a panel outlives the cell that laid it (panels are not per-tree), so it may name
  a detached parent, a task §4's blank title deleted, a task another named tree owns, or no task at all. All
  four say the same thing — **an error message**, in the app's first `MessagePopup`, a sort-2 pop-up (one
  notice at a time, gone when anything else takes focus, no scrim and no timer).
- **The tree's bring-the-row-into-view effect is now keyed on the SELECTION, not on the find bar's current
  match.** A match is no longer the only thing that reveals a row, and the calendar cannot reach into that
  composable; ordinary keyboard navigation lands there too and wanted the same thing.

### A button that has a keyboard shortcut names it on hover — SHIPPED 2026-08-28

→ ADR 0011 § *A button that has a chord names it on hover*. `shared` only (new `ui/ShortcutHint.kt`,
`ui/CalendarUi.kt`, `ui/KeyboardShortcuts.kt`, `ui/TaskTreeFindBar.kt`, `scheduler/ui/TaskSchedulerScreen.kt`,
`App.kt`); `KeyboardShortcutsCatalogTest` extended. Client rebuild to see it — no deploy otherwise.

- **Hovering a control that duplicates a chord shows that chord in an info bubble.** `ShortcutHint` is the one
  place such a bubble is drawn; a control with no chord passes `null` and is a plain `Box`. Today: the lateral
  menu's "Look away now" / "Switch task" / "I'm away" / "I'm back", the find bar's ↓ / ↑ / ✕ / Replace, and the
  deep-copy window's "copy".
- **The three system-wide chords are read LIVE off the account's bindings** —
  `GlobalShortcutBindings.chordOf(state.shortcutBindings, …)`, the same lookup the keyboard-shortcuts window,
  the receipt notification and the diagnostics use. `App.kt` hands `LateralMenu` the very map it hands
  `installGlobalHotkeys`, so a rebinding reaches the bubble with no second resolution and a bubble can never
  print a chord the app is not claiming.
- **The fixed per-surface chords are spelled once**, in the new `ControlChords`, read by the button and by
  `KeyboardShortcutCatalog` both; the test fails if a hinted constant stops being listed in the window.
- **The bubble is placed below the control with a 6 dp gap, non-interactive and non-focusable** — a tooltip the
  cursor can reach steals the hover and flickers (ADR 0002's "catch the bubble"), and a focusable one would eat
  the click. Hover is read with `Modifier.onPointerEventCompat`, promoted from private in `CalendarUi.kt` to
  internal rather than copied. Touch-only devices get no bubble, which is the correct no-op.

### Task colours: the LEAVES own the circle — SHIPPED 2026-08-28

→ ADR 0013. Replaces the *one colour space handed down the tree* rule shipped the day before.
`shared` only (`TaskColorSpace.kt`, new `ui/TaskHueMemo.kt`, `TaskColorPalette.kt`, the three call sites);
`TaskColorSpaceTest` rewritten. Client rebuild to see it.

- **The tasks with an empty sub-tree are placed first and spread as far apart as they can be** — `n` of them
  take the `n` hues `i/n`, the arrangement maximising the smallest distance between any two. Their order
  around the circle is the tree's depth-first order, so "the closer in the tree, the closer in the colour
  space" costs nothing: every order spreads them equally well.
- **Every other task then takes the point of its OWN sub-tree's arc furthest from every colour already given
  out**, one at a time, most constrained first. The arc is the smallest stretch holding the leaves below it,
  widened by half a ring step at each end (without which the parent of a single leaf has nowhere to go). The
  maxima are exactly the gap midpoints plus the arc's ends, so the search is an enumeration, not a sample.
- **The collision the old rule had by construction is gone.** A cell's colour used to be the *average of its
  own arc*, so a child sitting in the middle of that arc got the parent's very hue (`Book` and `Draft` in the
  test fixture). `TaskHue` still carries the depth and the palette still spends it on lightness, but it is now
  a legibility cue for neighbouring hues, not the thing that separates two tasks.
- **Ties are settled toward the PREVIOUS answer** — the ring's rotation and each parent's pick both. The
  circle has no origin and a gap has two equally distant halves, so ties are the normal case; broken
  arbitrarily they repaint the whole tree on every edit. `hues(state, previous)` is a fixed point of itself.
- **New `TaskHueMemo`, one per tree**: `TaskHueMemo.account` is shared by the task tree and the calendar (so
  the two cannot derive different colours for one task), the PRD §4 template gets its own. It caches on
  `cells`/`lists`/`tasks` — the ADR 0009 per-tick guard, unchanged in effect — and `rememberTaskHues` adds a
  **400 ms debounce**, the first composition still answered at once.
- Nothing persisted or synced changed: colours stay derived.

### Grey periods are marked with VERTICAL LINES, delimited — SHIPPED 2026-08-28

→ ADR 0002 § *How grey is MARKED*. `ui/CalendarUi.kt` only; no logic change, no test change.

- **`greyPeriodMarks` is the one place a grey period becomes something to paint.** Inactivity periods, sleep
  windows and all three screen breaks go through it, so all three are marked identically. The screen break's
  blue outline, its `●` and its accent-coloured title are gone (`SCREEN_BREAK_CLOSED_ALPHA` deleted): they said
  a break was a different sort of period from the inactivity band beside it, and it is not — all three are
  `no task allowed`.
- **Vertical lines, not a filled tint** (`SLEEP_BAND_ALPHA` deleted). A grey period can legitimately hold a
  task panel — §17 projects the plan straight through a sleep window, and a task with a non-zero resilience to
  `no task allowed` works through a break — and a wash repainted those panels in grey, taking their task
  colour. Lines leave every possible task colour readable through the gaps.
- **The marking moved OVER the panels**, alongside the layers and for the same reason: behind them, a stretch
  with a block on it showed no marking at all. That is what had forced `CalendarBlock`'s per-block
  `sleepHourRanges` grey overlay, which is deleted with its parameter and its plumbing.
- **Each band is DELIMITED** — an edge line across its top and bottom, drawn half a stroke inside the band —
  so an inactivity period ending exactly where a sleep window starts still reads as two periods rather than
  one merged stretch.
- The bands stay non-interactive (no pointer input), the visible-window culling (ADR 0009) is unchanged, and
  the hover bubble's sections still come from `contextOverlays`.

### The scheduler and the calendar are rebuilt on `side-dev/README.md`'s RESILIENCE model — 2026-08-28

The README says a restrictive period is a start, an end and a **kind**, and that each task has a **resilience**
to each kind: a multiplier in `[0, 1]` on its priority percentage for as long as a period of that kind lasts.
The app had a boolean world instead — an on-screen flag, a *doable during a break* flag, a per-break accepted
set — and this replaces all of it. → ADR 0001 §4, ADR 0003, ADR 0012.

- **`Task.resilience` (overrides only) replaces `onScreen` / `doableDuringBreak`.** An absent kind takes
  `PeriodKinds.defaultResilience` — `1` for every kind except `no task allowed`. Two README sentences fall out
  of that: a kind the user has just defined restricts nobody (defining one writes nothing to any task), and
  "on screen" is exactly a `0` against `no on-screen task`. `Task.onScreen` survives as a derived reading.
  A payload written by an older build is migrated on decode from its `onScreen` flag; encode still writes that
  flag, so an older build reads the new payload.
- **The user defines period kinds in the task edit window** (`SchedulerState.periodKinds`, authoritative +
  synced). The two built-ins are never in that list; `state.allPeriodKinds` is the one reading of "every kind a
  task can be resilient to". Removing a kind takes every task's override and every panel laid with it.
- **`weightsAt` / `localSharesOf` / `serveWeighted`.** The walk races on the effective weights and charges
  service against them (the reference's `v += served / w[name]`), which is what makes a multiplier mean "half
  the percentage for as long as the period lasts". The steady cycle inherits those effective shares; built on
  the nominal ones a standing period halving one side still answered a flat 50/50. The influence field became
  fractional to match (`deprivationsOf`: `Σ (1 − mult)·length`).
- **A behaviour change worth knowing: an off-screen task is no longer CONFINED to no-screen periods.** A period
  multiplies what it covers and says nothing about the timeline it does not, so "only tasks that need a screen
  may run everywhere else" is not expressible in this model — and the README never asks for it.
- **`SchedulerPlanner.plan()` phase 1 is now a literal port of `scheduler.py`'s `Walk.run`**, checked
  slot-for-slot. The sanctioned divergences are named in ADR 0001 §6: zero-priority tasks stay last-resort
  candidates, `Fraction` → `Double` millis, and `fillSchedule` keeps PRD §15's suspension. The atomic block is
  no longer one of them.

### Screen breaks: three recurrence bars replace the `lastRest` anchor engine — 2026-08-28

`side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period*, ported as `DynamicPeriods`. → ADR 0003.

- **All three breaks are `no task allowed`, end to end.** `ScreenBreakPeriod`, `ScreenBreak.shape`,
  `screenBreakOpenStartMillis`, `SCREEN_BREAK_CLOSED_HEAD_MILLIS` and the calendar's **hollow** band are gone
  with the shapes; a break draws as one grey span. A task works through one exactly when it has a non-zero
  resilience to that kind.
- **Where they fall is the three bars**: after any dynamic period no 20 s for 20 min; after a ≥ 5-min rest
  stretch no 5 min for 1 h; after a ≥ 15-min one no 20 s for 20 min and no 15 min for 2 h. Overlapping ones
  collapse to the chain's longest member at its earliest point.
- **The anchors are DERIVED.** `ScreenBreak.lastRestMillis` is deleted, and with it `seedScreenBreaksFromGaps`,
  `serveElapsedScreenBreaks`, `serveShorterBreaks`, `pastScreenBreaksFromPauses`, `advanceRestsForward`,
  `screenBreakNextStart`, `isScreenBreakOverdue`, `reachedRestPoseDueByTitle`, `simulateScreenBreaks`, the
  decoupled-pose case, the dense-projection cap, the engine's two seeders and `reduceReportDeviceSleep`'s
  anchor advance. Rest stretches are read out of the timeline itself; a live pause reaches the placement as
  the period it is (`liveRestPeriod`).
- **Nothing slides, so every cue keys on the DRAWN start** (`cueCrossings` → `screenBreakOccurrencesBetween`).
  What is announced and what is drawn are one instant by construction. The sweep must be handed the same
  environment the fill was, and its self-delay reads the next placed start.
- **The placement origin is anchored on the now-line, quantized to the day** (`dynamicPlacementOriginMillis`),
  never on each query window's own left edge — otherwise the fill, the cue sweep and the calendar walk
  different grids whenever one straddles a midnight.
- **A materialized break is never an input to its own placement**: `restrictivePeriodsOf` drops `screenBreak`
  panels, or each break becomes a blocked stretch absorbing the next.
- **A conducted break is recorded as a period** (`SchedulerIntent.RecordConductedBreak`), at its exact span —
  not through `AddInactivityPeriod`, which rounds up to a minute. Only on completion.
- **`device_break.break_due_ms` is now the pose's next placed START** (`nextScreenBreakStartMillis`) instead of
  the anchored due `lastRest + interval`. The anchored form existed *because* the drawn start rode the now-line
  and could not be written event-driven; the bars pin it, so server and client key on one instant. No Supabase
  change: the column's meaning is unchanged ("when does this break next come due"), only the client's
  derivation of it.
- **The debug fast-break knobs lose `pauseThresholdMs`** (`ScreenBreak.pauseThresholdMillis` /
  `qualifyingPauseMillis` / `DebugFlags.breakPauseThresholdMillisOverride`, the `OMNIAPP_*_PAUSE_THRESHOLD_MS`
  script variables and the `omniapp_break_pause_threshold_ms` Android extra). It decoupled a break from the
  pause that *anchored* it, and nothing is anchored any more. Duration and interval remain.
- **Tests:** `DynamicPeriodsTest` and `TaskResilienceTest` are new; `ScreenBreakHollowTest` →
  `ScreenBreakKindTest` and `RestPoseNotificationRuleTest` → `ScreenBreakCueRuleTest` (same rules, new
  mechanism); `SchedulerScreenFlagsTest` and `LiveRestPlacementTest` are deleted with what they pinned.

**Deploy:** client rebuild only (`account{1,2,3}-*deploy*.bat`). No migration, no Edge Function change.

### The Windows lock-history query reads the whole power history (`WindowsPowerLog`) — FIXED 2026-08-27

- **The ask:** is the app's detection of the Windows lock/unlock history as good as
  `computer/Get-SleepCycles.ps1`? It was not. Same technique — non-elevated `Get-WinEvent`, one merged
  timeline, pair down→up — but on a strict subset of the events, with no debouncing and one unhandled
  window edge. All three `SleepHistory.jvm.kt` actuals now share `WindowsPowerLog`, so the layer, the
  record-bank evidence, the screen-break seed and the exact pause recorder can no longer disagree about
  whether the user was there.
- **Shutdown and boot are now absences.** The old query watched Kernel-Power `42`/`506` → `1`/`131`/`507`
  and nothing else, so a machine switched OFF overnight — which writes `109`/`13`/`6006` and `12`/`6005`,
  on two other providers — produced no pair at all and read as time at the desk. `6008` (power loss) is
  stamped at the next boot, so its real instant is taken from the record's own properties.
- **Each provider is asked for its OWN ids**, because `1` is Kernel-Power's "resumed" *and*
  Kernel-General's "the system time has changed". Verified against the author's own log: with a flat id
  list a sleep at 01:19:44 pairs with the 01:19:45 clock resync into a one-second absence and the genuine
  09:18 resume has nothing left to close — the eight-hour night vanishes. `Get-SleepCycles.ps1` has this
  bug; the app now does not. The reference script's `6008` correction is also inert, as it reads
  `ReplacementStrings` (a `Get-EventLog` property) off a `Get-WinEvent` record.
- **A 60-second debounce**, as in the reference script: a flip that did not hold cancels the transition it
  undid, a repeat of the state already held is dropped, and the timeline strictly alternates. Kills the
  three-second "locked" slivers a standby bounce used to emit. Sub-minute locks become invisible —
  accepted, being the scale the derived grey bands already drop.
- **The window's opening state is asked for.** `StartTime`/`EndTime` still bound the query (ADR 0009), but
  `PRIOR_EVENTS` events from before it now establish what state it opens in; a window beginning
  mid-absence used to drop its unmatched wake and report the whole lead-in as present. The trailing edge
  was already clipped.
- **The `OK` sentinel got stricter**: printed only when every error was `NoMatchingEventsFound`, so a log
  this process may not read now answers "cannot tell" (`null`) instead of "never locked". That
  distinction is load-bearing — a failed query must never become evidence in the record bank.
- Measured on a 7-day window: 740 ms for six queries. `WindowsPowerLogTest` pins the vocabulary, the
  debounce, both window edges, the sentinel and the provider partition. **No deploy surface but the client
  apps** — rebuild via `account{1,2,3}-*deploy*.bat`.

### The keyboard-shortcuts window can rebind the system-wide chords — SHIPPED 2026-08-26

- **The ask:** make the keyboard-shortcuts window able to customize the shortcuts.
- **Scope, and why it stops where it does.** Only the **three system-wide chords** are rebindable. They are
  the only shortcuts in the app that can collide with anything *outside* it — a system-wide claim is first
  come, first served, so a chord another application already owns is simply unusable until the user can move
  it (the window's own claim line has been reporting exactly that failure). Every other entry in the window is
  a Compose `onPreviewKeyEvent` branch scoped to a surface: nothing to collide with, and nothing that reads
  the branches back, so making those rebindable would mean re-routing ~40 hardcoded branches through a lookup
  for a failure that cannot happen there. The rest of the window stays a reference list.
- **`GlobalShortcut` now carries a `defaultBinding`, not a `chord`.** The live chord is
  `GlobalShortcutBindings.chordOf(state.shortcutBindings, shortcut)`, and the window, the receipt notification
  and the diagnostics all print *that*. The old `chord` property is gone so nothing can advertise a chord the
  app is not claiming.
- **`ShortcutBinding` = a `ShortcutKey` + Ctrl/Shift/Alt.** `ShortcutKey` is a **closed** set (A–Z, 0–9,
  F1–F12) whose entry names are the persisted form: a chord has to survive the snapshot, the sync wire and
  every platform actual's own naming, and a layout-dependent key would give an AZERTY user a chord the QWERTY
  peer sharing that account has not got. No Win/Meta flag — Windows reserves it.
- **`SchedulerState.shortcutBindings` holds the OVERRIDES only.** An untouched shortcut is absent and follows
  its shipped chord, so a default changed in a later build still reaches every account that never rebound it,
  and **"Reset" removes the entry** rather than writing today's default in. Persisted **and synced** (the
  chords are the account's, so they follow the user to every machine).
- **A rebinding IS an Undo/Redo unit** — `ShortcutBindingDelta`, one Main unit, unlike the account settings
  beside it (`deepCopyMaxDepth`, the copy switches). It is one deliberate gesture on something whose effect is
  invisible from where the user is sitting when the chord is struck. Both sides carry the whole override map,
  because a reset is a *removal* and a delta saying only "X is now Y" could not put one back.
- **Two rules, stated once** (`GlobalShortcutBindings.rejection`, which the reducer refuses on and the window
  quotes): **at least two of Ctrl/Shift/Alt** — the claim swallows the chord session-wide, so one modifier
  would take Ctrl+C or Alt+F4 away from every application the user runs and none at all would eat their
  typing — and **no two shortcuts on one chord**. Consequence, accepted: swapping two chords needs a third in
  between; stealing the other shortcut's chord silently would be worse.
- **Rebinding is a capture, not a text field**, and the capture **stands the OS claim down**
  (`setGlobalHotkeyCapture`, a new expect/actual). Without it the chords the app already owns would be the one
  set of chords it could never hear: the hook swallows them and `RegisterHotKey` consumes them underneath,
  before Compose is handed the key. On Windows the flag short-circuits the hook and empties the hot-key table;
  it is balanced on a chord taken, Escape, focus lost, or the window closing.
- **`installGlobalHotkeys(bindings, onShortcut)`** — the seam was already "claim once, re-point the callback";
  a later call now also **re-registers the chords**, which is how a rebinding lands with no restart
  (`App.kt`'s `LaunchedEffect` is keyed on the bindings). `RegisterHotKey(NULL, …)` belongs to the thread that
  made it, so the UI thread posts `WM_OMNIAPP_RECONFIGURE` to the hot-key loop instead of touching the table;
  the hook just re-reads a volatile field. The hook's modifier check is now an **exact** match against the
  binding (a modifier the chord does not ask for must be up), with the AltGr pass-through preserved.
- **Two healing paths, because the collision is reachable without either device causing it.** Merging per
  shortcut can land two shortcuts on one chord when each device rebound a *different* one onto it —
  `SnapshotMerge.repair` drops the collision back to the default; and decode drops any stored row this build
  cannot name or that today's rules refuse, so the claim never holds a chord the window would not let the user
  set.
- Tests: `GlobalShortcutRebindTest` (new — the rules, overrides-only, the history unit, the codec round trip,
  a pre-1.6.0 payload, decode healing), plus new cases in `SnapshotMergeTest` and updates to
  `KeyboardShortcutsCatalogTest`. **Redeploy:** client app rebuild (`account{1,2,3}-*deploy*.bat`); no
  Supabase change (the bindings ride the existing whole-document snapshot).

### Every system-wide chord posts a receipt notification — SHIPPED 2026-08-26

- **The ask:** pressing `Ctrl+Shift+Alt+<letter>` should raise a notification, so the user can tell the app
  actually received the press.
- **Why it was needed.** The three chords are struck precisely while OmniApp is *not* the focused window, so
  the app shows the user nothing; and each of them can legitimately do nothing visible — "Look away now" with
  no look-away break configured returns silently, "I'm away" is a no-op on a same-value call, "Switch task"
  only announces if the re-plan starts a different task. That made "the hook never saw the press" (another
  application swallowing it underneath us, Windows dropping a hook that overran `LowLevelHooksTimeout`, a
  claim that came back `Unavailable`) indistinguishable from "received, nothing to do".
- **`SchedulerEngine.announceShortcutReceived(shortcut)`** posts `Shortcut received` / `<chord> — <action>`
  through the ordinary `notifyUser` path, so it also lands in the Diagnostics timeline and the History
  window's Notifications column. It names the **chord** so two presses in quick succession are tellable
  apart.
- **A receipt for the PRESS, not the effect:** called at the `installGlobalHotkeys` seam in `App.kt`, first
  and unconditionally, before the `when` that dispatches the action. Deliberately *not* inside the engine
  seams themselves — the lateral-menu buttons drive those same seams, and a click in a window the user is
  looking at needs no confirming (`GlobalShortcutReceiptTest` pins both halves).
- The keyboard-shortcuts window's System-wide note now says the receipt is expected.
- Desktop-only, like the chords. **Redeploy:** client app rebuild (`account{1,2,3}-*deploy*.bat`).

### The default sub-tree window IS the task tree (`scheduler/ui/TaskTreeView.kt`) — SHIPPED 2026-08-26

- **Reported anomaly:** right-clicking a row in the "Default sub-tree" window opened no contextual menu. The
  cause was not a broken handler but the absence of one: the window was a **hand-rolled re-implementation** of
  the task tree (its own row composable, its own title field, its own selection/edit/collapse state), and the
  tree's `contextMenuModifier` + `DropdownMenu` had never been copied into it. The same gap silently cost it
  multi-selection, drag-move, Ctrl+C/X/V, Ctrl+F and the min-time field.
- **The tree is now ONE composable, drawn twice.** `CellListSection` / `TaskRow` / `contextMenuModifier` /
  `EditModeMenus` moved out of `TaskSchedulerScreen.kt` into `TaskTreeView.kt`, which both the account's tree
  and the template window call. The window gets the **full five-entry §13 menu** — *start this task now*,
  *edit*, *copy*, *deep copy*, *add default sub-tree* — and every other tree gesture, by construction.
- **What made that possible: the template became a real tree.**
  `SchedulerState.defaultSubtree` changed from `List<DefaultSubtreeNode>` (a tree of *titles*) to
  `DefaultSubtreeTemplate` — a `TreeSnapshot` in the same shape a `TaskTreeEntry` stores, plus its expansion
  and the per-cell switch set. Four of the five menu entries need a real `Task` to act on, and "edit" writes a
  screen switch / schedule unit / text that the old node type had nowhere to put.
- **`state/DefaultSubtreeProjection.kt` is the seam.** `projectDefaultSubtree()` hands the tree component a
  state whose tree IS the template, with the live tree merged underneath so a bound row resolves and the
  ordinary Change Task menu can offer live tasks. `defaultSubtreePriorities()` deliberately computes the
  percentages on the template's cells **alone** — `absoluteTaskPriorities` iterates every cell it is given.
  `withDefaultSubtreeCapturedFrom()` folds the reduced projection back, keeping only what is reachable from
  the template's root and **discarding the live half**, so nothing dispatched in that window can reach the
  real tree.
- **New intents:** `InDefaultSubtree(inner)` wraps every tree intent the window raises (Undo/Redo excepted —
  they belong to the app's stacks) and lands as **one** `DefaultSubtreeDelta` Main history unit;
  `SetDefaultSubtreeCellBound` flips a row's switch. `SetDefaultSubtree` is gone.
- **Visible changes.** The percentage and minimum-time columns, previously suppressed as meaningless, are now
  shown and meaningful: the percentage is the row's share *within the template*, and the graft carries the
  minimum time, the task fields and each sub-list's weight table across. The switch takes a column of its own
  after them and now toggles **both** ways (a row always has a task to point at). A bound row's borrowed
  sub-tree is drawn by the tree as the ordinary mirror it is, rather than by a bespoke greyed renderer.
- **Migration.** A payload holding the pre-1.6.0 `defaultSubtree` node array is built into the real tree on
  decode (`migrateDefaultSubtreeNodes`), minting ids past the account's own counters; a bound node keeps its
  binding and joins `boundCells`. That shape is still read and never written again. An **empty** template is
  written as nothing at all, so "written before the feature existed" and "empty" decode the same way.
  `SnapshotMerge` still resolves the template as one whole value.
- Known consequence, and it is the tree's own rule: a bound row's title lives on the task it points at, so
  deleting that task empties the row — and the blank title then deletes it, exactly as it would in the tree.

### Two sorts of pop-up window (`ui/PopupWindows.kt`) — SHIPPED 2026-08-26

- **The sort of a pop-up is no longer a per-call-site decision.** Sort 1 opens on the top layer and then lets
  whatever is focused next stack on top of it, staying open — that is `App`'s `windowStack`. Sort 2 opens on
  the top layer and leaves the moment anything else takes focus.
- **The test is whether it could have several instances open at once**: a pop-up about ONE object (a task, a
  cell, a sub-list, a calendar block, a period, a reminder, a history unit, a tree entry) is sort 2. Which
  makes sort 1 exactly the ten lateral-menu windows, and every other pop-up in the app sort 2.
- **Replaces three ad-hoc tiers**: the managed `windowStack`; the two priority windows at `zIndex(50f)` with a
  bespoke outside-press interceptor in `App`; and eight full-screen-scrim modals at `zIndex(100f)`. The
  interceptor is now the one app-root `transientPopupDismissRoot` (Initial pass, consumes nothing) feeding a
  `TransientPopupHost`, and every scrim is gone.
- **What visibly changes.** A sort-2 pop-up no longer blocks the app behind it, so the press that dismisses it
  also does its normal job — one click to close the task-edit window *and* focus the calendar, where the scrim
  cost two. At most one sort-2 pop-up is open at a time, by construction (`open` dismisses the others) rather
  than by each opener remembering to close its predecessor — the priority pair's hand-written mutual exclusion
  was the only place that had ever been done. Dismissal still discards a half-typed edit, exactly as clicking
  the old scrim did.
- **`TaskEditWindow` / `DeepCopyWindow` are raised out of `TaskSchedulerScreen` into `App`** (hoisted like the
  priority windows already were, via `onSetEditTask` / `onSetDeepCopyCell`). Declared inside the tree they drew
  *under* any floating window stacked over it, so "appears at the top layer" was simply false for them.
- `TransientPopupHostTest` pins the rules: outside press dismisses, inside press does not, a pop-up that has
  not laid out yet is not "inside", opening one closes the one already open, and `close` (Save/Cancel) never
  calls back into a composable that is already gone.
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### "start this task now" on a task cell (PRD §13) — SHIPPED 2026-08-26

- **New entry at the top of the task cell's right-click menu**: "start this task now" asks the schedule to put
  that task at the now-line. It is the mirror image of §7's "Switch task" button — one refuses the task the
  now-line is on, the other names the one it must be on — and both are the same lever read from opposite ends.
- **The model shape is the switch's, deliberately.** A `ForcedTaskStart(task, at)` is recorded (authoritative:
  persisted + synced, merged as one whole value, not an Undo/Redo unit) and the fill puts that task in the
  **first slot it places**, charged through `PlanWalk.serve` exactly like a slot the walk had chosen — so only
  that first slot is the user's answer and the schedule after it is the one the walk would have gone on with.
  No new scheduling rule, and nothing in `schedulingSignature`: the press re-plans inside its own reducer
  (`reduceForceTaskStart`), for the same reason the refusal does.
- **Liveness is the refusal's own predicate** (`SchedulerDomain.liveForcedStartTask`): outstanding until some
  *other* task has been served past `at` — for a refusal that means "the plan started something else", for a
  request "the plan has moved on". So a re-plan in between (a rule change, the hourly staleness refresh) keeps
  the user on the task they asked for, and the advance tick drops the marker once it is spent, exactly as it
  already did for `forcedSwitch`.
- **Answered in phase 1 and in phase 2.** A timeline nothing disturbs (no screen breaks, no fixed blocks)
  freezes before phase 1 places anything and builds the plan from the analytic cycle, so the request is placed
  there too — before the settle loop, which then squares up from the walk state it left.
- Offered only on a **schedulable leaf** (a parent task is a grouping §9 never places), and it names ONE task
  however many cells are selected — unlike "copy", "start *this* task" has no meaning for a block. Asking for a
  task also clears an outstanding refusal *of that same task*.
- `ForcedTaskStartTest` pins the whole contract (the pick, the one-slot scope, the two no-ops, the liveness,
  the advance-tick drop, the interaction with an outstanding refusal, and the decode of a payload written
  before the entry existed).
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### A freshly minted sub-list is never shown expanded (PRD §4) — FIXED 2026-08-26

- **Anomaly**: typing a title into an empty task cell and pressing Enter unfolded the new task onto nothing but
  its bare placeholder child row.
- **Cause**: `SchedulerState.expanded` is keyed by **cell** id, while a sub-list belongs to the **task**. A cell
  that had been expanded and was then emptied (PRD §4 *Deletion*, which takes its task's sub-list with it) kept
  its stale `expanded` entry — nothing prunes it, because the cell itself is still there and still reachable —
  so the next task typed into that same cell inherited an expansion the user never asked for. Not the default
  sub-tree: with the template off, the graft is a no-op and `endEditSession` adds nothing.
- **Fix**: `applySetCellTitle` drops the cell from `expanded` where it mints the task's sub-list — the one
  place the entry can go stale is the one place it is cleared. A rename does not mint a sub-list, so it still
  keeps its children on screen; the default-subtree graft still re-adds the cell in `endEditSession` once it
  has rows to show, and `AddDefaultSubtree`, Tab-into-child and the arrow are unaffected.
- `SchedulerReducerTest` pins both halves (the retyped cell stays folded, the renamed one stays open).
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### "All tasks" — the flat, sortable list of every task in the tree (PRD §7) — SHIPPED 2026-08-26

- **New lateral-menu button, "All tasks"**, opening a floating window (`ui/TaskListWindow.kt`) that lists every
  task of the LIVE tree with two columns: its **number of occurrences** and its **absolute priority
  percentage**. A mirrored task is **one row** — the tree's shape is exactly what the window is there to see
  past — carrying the cell count and the priority summed over all its chains.
- **A sorter configuration at the top**: which of the two figures orders the list (`TaskListSort.Occurrences` /
  `TaskListSort.Priority`) and which direction (highest first, top to bottom / lowest first, bottom to top).
- **The ordering lives in the domain** (`SchedulerDomain.taskListEntries`), not in the composable, so the rows
  are testable and the tie-break is one rule: equal figures fall back to the title then the id, so the order is
  total and cannot shuffle between recompositions — and the tie-break is **not** reversed with the direction.
- **The rows are counted off `state.cells`**, exactly as `absoluteTaskPriorities` and
  `RelativePriority.occurrenceChains` count them, so the two columns can never disagree about what an
  occurrence is. A task deleted by blanking its title (§4) is therefore gone from the list even while its
  records keep it alive, and a detached parent is not listed either — it is not in the tree.
- **The percentage is `absoluteTaskPriorities`, not `blendedTaskPriorities`** — the same identity the tree's own
  percentage column keeps: this window reads the arrangement on screen, which is what the user is editing, not
  the keyframe blend the scheduler is following. `formatPriorityPercent` was made `internal` and reused rather
  than copied, so the two readouts round identically.
- **The sorter is Compose-only state**, like the calendar's zoom and the §4 find bar: how a list is ordered on
  screen is a way of looking at the tree, never a fact about it — not persisted, not synced, no history unit.
  Only the window's own placement/visibility persists, like every other floating window's.
- `TaskListWindowTest` pins the mirrored row, the agreement with the tree's percentages, both sort keys, both
  directions, the tie-break and the deleted-task exclusion.
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### "Switch task" — the button that refuses the task the now-line is on (PRD §7, ADR 0011) — SHIPPED 2026-08-26

- **New lateral-menu button, "Switch task", with the system-wide chord `Ctrl+Shift+Alt+Z`.** It refuses the
  task the now-line is sitting on, so the plan starts a different one from that instant. The chord is claimed
  the same way the other two are (`GlobalShortcut.SwitchTask`, one more `Chord` row in the Windows actual, one
  more branch in `App.kt`'s `installGlobalHotkeys`) — it is wanted precisely while the user is inside the work
  they have decided to get off, which is when OmniApp is not the focused window.
- **It is expressed as the walk's `last`, not as a ban.** `SchedulerState.forcedSwitch`
  (`ForcedTaskSwitch(taskId, atMillis)`) is handed to `PlanWalk.setLast` by `fillSchedule`. Reusing the
  never-twice-in-a-row rule is what keeps the refusal cheap and correct: the refused task's virtual clock is
  untouched (so it loses none of its share and returns at the second slot), and it inherits the rule's own
  escape — a task nothing else can replace still runs rather than the period being left empty. A now-line on
  no task at all is a no-op.
- **It is honoured until it is granted, and read off the recorded past.**
  `SchedulerDomain.liveForcedSwitchTask` keeps the refusal live until some *other* task has actually been
  served past `atMillis`, so the re-plans that happen in between (a rule change, the hourly staleness bound, a
  pulled snapshot) cannot quietly hand the same task back, and a chain of re-plans still equals one long plan.
  `advanceSchedule` drops the marker at the tick that banks that other task's work.
- **Deliberately NOT in `schedulingSignature`.** `SchedulerIntent.ForceTaskSwitch` re-plans inside its own
  reducer (the press *is* the calculation event, like `RemoveRecordPeriod`). In the signature, the tick that
  drops the spent marker would fire a second, un-refused re-plan.
- Authoritative: persisted (two flat scalars in the codec, absent ⇒ no refusal) and synced (whole-value
  `pickNullable` in `SnapshotMerge` — never a task from one device paired with an instant from the other);
  not an Undo/Redo unit. `ForcedTaskSwitchTest` pins the pick, the sole-candidate escape, the no-op, how long
  the refusal lives, the advance-tick GC and the codec compatibility.
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### The period editor, and what a grey period clears (PRD §8, ADR 0002) — SHIPPED 2026-08-24

- **"Add a no-screen period" / "add an inactivity period" open an editor instead of laying a fixed hour.**
  `ui/CalendarUi.kt`'s `PeriodEditWindow` (one window for both kinds, `CalendarPeriodKind`) gives each bound
  three forms: a **date and time**, **"now"** (resolved at Save, not at open), or **"∞"**. Save is disabled
  while a field is half-typed or the period runs backwards. The same window is now a period's **"Edit"** — a
  hand-added inactivity period has one at last (a *derived* grey band still has none: no panel behind it) —
  which retired `ManualEntryEditWindow`'s `timesOnly` mode.
- **"∞" is `SchedulerDomain.OPEN_PAST_MILLIS` / `OPEN_FUTURE_MILLIS`** (1900 / 2200), real instants rather
  than `Long.MIN_VALUE`/`MAX_VALUE`: every consumer does ordinary arithmetic on a panel's bounds and a
  saturating sentinel would overflow the first `end - start`. `isOpenPast` / `isOpenFuture` recognize them,
  and the hover bubble prints "∞" for either end.
- **A grey period now overrides everything it covers.** An inactivity period trims/deletes **every** task
  panel under it (a no-screen period still only the on-screen ones), and any task panel trims it in turn.
- **A laid or dragged period clears the RECORDS under its elapsed part** — the on-screen tasks' for a
  no-screen period, everybody's for a grey one. This is `StripNoScreenRecords`' §9 rule (`stripRecords`,
  now parameterized by `onScreenOnly`) applied at once instead of at the next engine start. Outside
  Undo/Redo, like every write to the record.
- The point of all four: **an inactivity period from ∞ to now** declares the whole recorded past empty and
  clears it in one gesture. Tests: `CalendarPeriodEditTest`.

### The clipboard: whole-sub-tree Ctrl+C, and three switches on the deep-copy window (PRD §4/§13, ADR 0012) — SHIPPED 2026-08-24

Four deltas, one gesture family.

- **The weight TABLE of every copied sub-list is pinned as carried.** The parent node writes the sub-list's
  `- sub-list weight columns:` header and each child writes its own `- priority weights:` row, so a pasted
  sub-tree rebuilds the tables rather than only the titles. New round-trip test through a two-column sub-list
  (`the_weight_table_of_every_copied_sub_list_travels_and_pastes_back`) — restoring the rows without the header
  would silently re-normalize every percentage at the destination.
- **`Ctrl+C` / `Ctrl+X` copy the ENTIRE sub-tree again** (`SchedulerDomain.FULL_SUBTREE_DEPTH`). For one day the
  chord copied to the account's `deepCopyMaxDepth`, which made that number mean two things: a depth set for one
  deep copy afterwards truncated every later `Ctrl+C`, with nothing on screen saying so. The three gestures now
  divide purely by how much — menu "copy" = the cell, "deep copy" = the window's number, the chord = all of it —
  and `deepCopyMaxDepth` is the window's own number and nothing else's.
- **The deep-copy window gained three switches** (`SchedulerDomain.CopyOptions`, `SchedulerIntent.SetCopyOptions`,
  `SchedulerState.copyIncludeIds` / `copyPriorityTables` / `copyIncludeText`): copy the task **ids**, copy the
  **priority weight tables** (off ⇒ the cell's **percentage of its sub-list** instead), copy the task **text**.
  Like the depth they are **one answer for the whole account** — persisted + synced, not Undo/Redo units, written
  back when the window copies — so the menu's "copy" and the chords obey them. `reset` restores all three.
- **The percentage form is stored as the node's single weight.** `copiedSubtree` writes `rowWeights = [share]`
  with the default one-column header and the renderer prints `- priority in its sub-list: 37.5 %`; the parser
  reads it back into that same weight. So the reducer's paste path is untouched, a sub-list of shares rebuilds
  those shares, and the copy-time rounding (two decimals of a percent) makes a second round trip a no-op.

One consequence worth stating: **ids off makes the payload foreign by construction**. The default-subtree paste
gate is "did the app write this text?", answered by the id — so a copy taken with ids off pastes as new tasks and
*is* seeded with the §7 template, exactly as typing those titles would be. That is the switch's meaning, not a
leak in the gate.

Client rebuild only (`account{1,2,3}-*deploy*.bat`); the three new fields are ordinary scalars in the snapshot,
so nothing server-side changed and a payload written before them decodes with all three on.

### Find & replace in the task tree (PRD §4) — SHIPPED 2026-08-24

`Ctrl + F` opens a VS Code-shaped bar in the tree's top-right corner: query field, match counter, **Match
Case** / **Match Whole Word**, `↑` / `↓`, close — and, behind the chevron, the replacement field with
**Replace** / **Replace All**. New `TaskTreeSearch` (pure), `SchedulerIntent.RevealCell` /
`ReplaceTaskTitles`, `SetExpandedDelta` (+ its `PersistedDelta.SetExpanded` mirror), `TaskTreeFindBar`.

Three decisions worth keeping:

- **The walk covers the whole tree, and visits each *list* once.** A search over
  `selectableVisibleOccurrences` would have missed every collapsed row — most of the account. Visiting each
  list once is what keeps a mirrored sub-tree (one list, many parents) from being re-walked per occurrence.
  Each match carries the path that reached it, which is what the reveal expands.
- **Revealing a match is ONE history unit** (`SetExpandedDelta` over the whole expansion set), not one
  `ToggleExpandDelta` per level. Typing in the query field deliberately does not jump to the first hit
  either: every jump is a selection unit, and `Alt + ←` would otherwise have to walk back one per keystroke.
- **Replace is a rename, keyed by task.** It runs through `applySetCellTitle` — the primitive Rename mode
  uses — so occurrences, the title index and the tombstone rule behave identically, and a task mirrored under
  three parents is renamed once, not three times. A replacement that empties a title deletes by §4's ordinary
  rule.

No deploy needed beyond a client rebuild (`account{1,2,3}-*deploy*.bat`); nothing server-side changed.

### The default sub-tree, the clipboard, and a menu entry to ask for it (PRD §4/§7/§13, ADR 0012) — SHIPPED 2026-08-24

Two reports, same day.

**1. Pasting foreign text seeded nothing.** With a template defined and the §7 switch on: copy text from
another app, select an empty task cell *without* entering Edit Mode, Ctrl+V, expand — empty sub-tree.
`graftDefaultSubtree` was only ever called from `setCellTitleDelta` and `endEditSession`, and a paste onto a
selected cell opens no Edit session. Documented at the time as deliberate ("paste … deliberately never
graft"), which was the wrong call: §7 grafts under every task the user **creates**, and pasting a title onto
an empty cell creates one exactly as typing it does.

**2. A deep-copied sub-tree pasted elsewhere seeded too.** The first fix gated on `PasteIdentity.Fresh`, which
still caught a copied id the target list cannot honour (`canAssignTaskId` refuses a duplicate sibling) — so a
pasted clone came back carrying the template. The gate is now the clipboard's **id**, not the identity:

- **no id** (another app's tab-indented list, or a pre-1.6.0 clipboard) ⇒ seeded. `graftDefaultSubtree`'s
  existing empty-sub-list guard means only a bare new leaf gets it — in a forest, every minted leaf, the same
  as typing those titles by hand.
- **any id** (Mirror, Restore, or a Fresh clone) ⇒ never. A copy of a sub-tree comes back as itself, and
  `Ctrl+X` → `Ctrl+V` still returns a leaf exactly as it was cut.

The pasted cell is **not** auto-expanded (unlike the end-of-session graft) — one paste can mint many leaves.

**New: "add default sub-tree" in the cell's right-click menu** (`SchedulerIntent.AddDefaultSubtree`), the
explicit gesture the narrowed gate leaves room for. Unlike the automatic graft it ignores the on/off switch and
does not care whether the task is new. It acts on `contextMenuCopyTargets` (the whole block inside a
multi-selection, as "copy" does), commits one Main history unit, and expands every cell it walked. Shown only
where a template exists.

**3. It applied beside the existing children, not to them.** Third report, same day. The first cut appended
the template after whatever the cell already parented. Corrected: it lands on the **leaves** of the sub-tree
the cell roots — a cell that parents nothing being its own leaf, so the plain and deep cases are one rule. A
template says how a piece of work breaks down, so asking for it on a cell already broken down asks for it on
the pieces. `defaultSubtreeApplicationTargets` resolves them off the state **before** anything is written (a
filled leaf gains children, and re-walking the mutated state would seed the rows just written where the task is
mirrored) and visits each **task id** once (one sub-list serves every occurrence; the id set is also the cycle
guard).

Covered by eleven `DefaultSubtreeTest` cases. No Supabase deploy; **client rebuild required**
(`account{1,2,3}-*deploy*.bat`).

### The no-screen record rule reads the OS, not just hand-drawn panels (PRD §9/§12, ADR 0002) — SHIPPED 2026-08-24

**Diagnosis (account 3).** Past task panels for on-screen tasks sat under BOTH calendar layers — which is, by the
layers' own identity, a no-screen period. Both hatches were individually right: the account has one desktop and
no phone, so the phone layer is `null` ⇒ assumed locked over the whole past; and the Kernel-Power 42/506 record
put the machine in Modern Standby for **98 h of the 168 h window**. What was wrong was underneath them —
**43.4 h of recorded "work" across 206 records**, banked while the OS said the screen was off. Modern Standby is
S0: the process keeps running and the advance tick keeps banking, so nothing but the OS log knows.

**Root cause.** §9's "assume nothing happened" guard (`appendRecordOutsideNoScreen`) took its no-screen ranges
from `state.panels.filter { it.noScreen }`, and the ONLY producer of such a panel is the §8 contextual-menu
action `AddNoScreenPeriod`. Account 3 had zero, so the guard short-circuited on `noScreenRanges.isEmpty()` and
every elapsed auto panel banked unconditionally. The bank read neither the OS lock history (which the layers
read) nor the derived pauses (which the engine reads) — only what the user had drawn by hand.

**The fix, in three parts:**

1. `SchedulerDomain.observedNoScreenRegions` — the two layers' EVIDENCE halves intersected, i.e. the same
   "a stretch carrying both layers is a no-screen period" identity the calendar draws, read for the scheduler.
   Asserted regions (sleep windows, screen breaks) are deliberately NOT folded in: a break *suspends* a chunk
   rather than cutting it (§15), so including them would silently stop recording across every break.
2. `SchedulerReducer.noScreenEvidence`, a seam beside `liveRestGap` — the engine scans the OS lock history on a
   coarse 10-minute bucket over a bounded 24 h window and injects the result; every banking path unions it with
   the hand-drawn panels (`noScreenRangesFor`). The panels are an assertion and still hold; the evidence is what
   fires when nobody drew one.
3. `StripNoScreenRecords` — a one-shot pass at engine start that applies the same rule retroactively over the
   displayed 168 h, carving the covered spans out of every ON-SCREEN task's record and materializing them as
   "Inactivity" panels. Off-screen tasks are untouched (§9 allows them to run in a no-screen period, so their
   records are true). Idempotent: once carved there is nothing left to subtract, and it returns the same state
   instance. On account 3 it removes 43.4 h spread across 20 tasks, so relative priorities barely move.

Unlike the tick that banks records, the strip **syncs**: `Task.record` is authoritative and the three-way merge
UNIONS it, so a deletion that stayed local would be resurrected by the next peer that still had the span.

**Two traps found while building it, both worth keeping in mind:**

- **A failed query is not evidence.** `null` from `deviceLockedIntervals` means "assumed locked throughout" —
  the right default for the calendar, the exact opposite of what the bank needs, where one PowerShell timeout
  would blanket the window as no-screen and suppress every record. The OWN scan must SUCCEED to say anything;
  the PEER's null keeps its assumed-locked meaning. Silence about a device we cannot reach is a rule; silence
  from the one we can reach is a failure.
- **The read must not run on the engine's dispatcher.** It spawns a PowerShell process and waits up to 20 s;
  calling it inline stalled the advance tick and every sweep behind it (it broke `ScheduleStalenessRuleTest`).
  It is now `withContext(Dispatchers.Default)`, as `App.kt` already did for its own layer scan.

Still open, and now sharper: the engine's pause derivation still reads `device_active_session` while the layers
and this guard read the OS. Three sources answered "was the user away?"; this makes it two.

Tests: `ObservedNoScreenRegionsTest` (the intersection, the assumed-locked null, empty ≠ null, the 90 s seam
filter, clipping at the now-line), `NoScreenEvidenceTest` (banking against evidence with no panel drawn, the
union with a drawn panel, off-screen tasks exempt, the empty-seam default, and the strip's carve/idempotence).

### The weight window charts the sub-list, and has a Cancel (PRD §5, ADR 0004) — SHIPPED 2026-08-24

**The pie chart on the right now shows each task's percentage *within the sub-list*** — the share the table on
the left actually hands out (`RelativePriorityDomain.cellShare`) — instead of the task's absolute priority (its
share of the whole tree). The slices never moved: their sweeps were already normalized by the sub-list total, so
only the legend's numbers were reading against a different denominator than the table they sat next to. The
heading says "Priorities in this list".

**A Cancel button puts the whole table back to what it was when the window opened** — every column header and
every cell's weight row, in one step, not one edit back. The window captures that table on the composition that
opens it (`remember(listId)`) and keeps it across every edit it makes, so Cancel always returns to the start; the
button is disabled while the table still matches. It dispatches one `RestorePriorityWeights` intent, reduced as
an ordinary `priorityTreeDelta` labelled "Cancel weight edits", **which is what makes Ctrl+Z undo the cancel**.
A cancel that would change nothing is a no-op and records no empty history unit. Only that one sub-list's weights
are rewritten: a cell that has since moved to another list is left to its new table, and list membership is never
touched (Cancel undoes weight edits, not tree edits).

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy. Verified by
`:shared:jvmTest` (`SchedulerReducerTest.cancel_restores_the_weight_table_the_window_opened_on`,
`a_cancel_that_changes_nothing_records_no_history_unit`,
`a_cells_share_of_its_own_sub_list_is_independent_of_its_parents`).

### The clipboard carries the task id, Ctrl+C is deep, Ctrl+X cuts (PRD §4/§13, ADR 0012) — SHIPPED 2026-08-23

Four changes to the copy/paste seam, the same day the format above shipped.

**Every copied node now writes its task id** (`- id: task/user/41`, the first attribute line), so a paste lands on
the **same task** instead of a clone of it. `SchedulerReducer.pasteNodeInto` resolves it three ways: the id names a
live titled task this cell may hold ⇒ the cell is pointed at **that** task (a mirror — its own sub-tree shows under
it, and the clipboard's children and fields are not applied over it); the id is free (it was cut, or the payload
predates this tree) ⇒ the task is rebuilt **under that id**, with `SchedulerState.reserveTaskId` walking the id
counter past it; no id, or one the tree cannot honour (it would duplicate a task inside one sub-list) ⇒ a fresh
task, as before. An id of any other shape (`task/root`, anything not `task/user/<n>`) is rejected at parse time, so
paste stays a no-op for foreign text.

**Ctrl+V replaces the cell it lands on.** It used to rename the target cell's task to the copied title and write
the copied children over the existing ones. The cell is now re-pointed at the pasted task; the task that was there
keeps its title and, with a populated sub-list, stays a detached parent its id brings back.

**Ctrl+C is a deep copy and never opens a window**, and **Ctrl+X** is that copy plus the PRD §4 deletion of the
same cells (one history unit, labelled "Cut", so one Ctrl+Z puts the sub-tree back — and the ids it freed are
exactly what a later Ctrl+V restores).

**The deep-copy depth is one number for the whole account** (`SchedulerState.deepCopyMaxDepth`, default 20,
persisted **and synced**, healed into 1..999 on decode; a payload written before it decodes to 20). The deep-copy
window opens on it and saves it when it copies — cancelling leaves it alone — and Ctrl+C / Ctrl+X then use it
without asking. The menu's plain "copy" is still depth 1.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy. Verified by
`:shared:jvmTest` (`TaskCellCopyTest`, `SchedulerReducerTest`, `DefaultSubtreeTest`); the depth window is UI,
checked by running the desktop app.

### The clipboard text is readable, and "deep copy" asks how deep (PRD §4/§13, ADR 0012) — SHIPPED 2026-08-23

**The copy format was rewritten for a human reader.** A copy already carried everything the cell's edit window
holds, but in a shape written for the parser alone: per-line flags (`w=1.0,0.3`, `h=…`, `ns=1`), three appendices
**keyed by task title**, a bare **form-feed** line between them, and each task's text escaped onto one line as
`line one\nline two`. Pasted anywhere but back into OmniApp it was unreadable. It is now prose — a tab-indented
title line per task, one `- <field>: <value>` line per thing it holds a level deeper, the schedule unit as one
`- <step>: <n> min` line per step, and the task text **verbatim** in its own indented block:

```
Deep work
	- minimum time: 45 min
	- can be done during a no-screen period: yes
	- schedule unit:
		- warm up: 5 min
		- run: 25 min
	- text:
		the note, exactly as it was typed
	Reading
		- minimum time: 30 min
```

Everything at its default is omitted, so an untouched task is a title and its minimum time. The fields moved off
the title-keyed appendices **onto the node** (two tasks sharing a name no longer share a minimum time or a text),
a title that reads like an attribute line is escaped (`\- text:`), and paste stays as strict as before — an
unknown attribute, an unparseable value, a real tab in a title or an indent jump is still a no-op, while a plain
tab-indented title tree still pastes. The pre-1.6.0 form-feed shape is still **read** (a clipboard outlives a
rebuild), never written.

**The contextual copies act on the selection.** Right-clicking one of a dozen selected root cells and choosing
"deep copy" copied a single line: PRD §13 describes the menu on "the cell" and the first implementation took
that literally, while §4's Ctrl+C copies the selection — the same gesture to anyone using them.
`SchedulerDomain.contextMenuCopyTargets` now resolves a right-click **inside** a multi-selection to the whole
ordered block (the very one `copyTreeText` uses, so the menu and the chord cannot drift) and a right-click
outside it to that cell alone; the depth window carries the block and names how many cells are going.

**"deep copy" now asks for a maximum depth first.** A new window carries the depth (default **20**, restored by
its **reset** button, the copied cell counting as the first level) and prints **one path** from that cell down to
the deepest level the depth reaches, so the number reads as a place in the tree. The branch is picked by height
measured over the *whole* depth asked for, so raising the number extends the path instead of switching branches;
the line is held at its deep end with a draggable horizontal scrollbar for the parents that fall off the left.
**Enter** or **copy** copies and closes. The menu's plain "copy" is now simply depth 1.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy. Verified by
`:shared:jvmTest` (`TaskCellCopyTest`, `SchedulerReducerTest`); the window itself is UI, checked by running the
desktop app.

### System-wide chords are swallowed, `Ctrl+Shift+Alt+E`, and a shortcuts window (PRD §7/§15, ADR 0011) — SHIPPED 2026-08-23

Three changes to the same seam (`scheduler/platform/GlobalHotkey.kt`):

**The chord is now claimed first come, first served.** Pressing `Ctrl+Shift+Alt+A` in Google Docs flipped the
away flag *and* opened Docs' comments pane — one press, two actions. The claim was a plain `RegisterHotKey`,
which consumes the keystroke only for the message queue: an application with its own `WH_KEYBOARD_LL` hook is
called **before** the hot-key table and can act on a press the hot-key then eats. The app now installs a
low-level keyboard hook of its own and returns non-zero for its chords, so the key is consumed at the head of
the chain and nothing else in the session is handed it. `RegisterHotKey` is kept underneath as the fallback (a
swallowed key never reaches the hot-key table, so the two cannot both fire); which claim is in force is
published as `GlobalHotkeys.claim` and logged at startup. The hook has to do two things `RegisterHotKey` did
for free — suppress auto-repeat, and pass **AltGr** through so `Shift+AltGr+E` still types its character on an
AZERTY layout — and it must never block, being on the path of every keystroke in the session.

**`Ctrl+Shift+Alt+E` takes the 20-second look-away**, from wherever the user is working. The seam went from one
`installGlobalAwayHotkey(onPressed)` callback to `installGlobalHotkeys { shortcut -> … }` over a `GlobalShortcut`
enum, which is now the only list of chords in the codebase.

**A "Keyboard shortcuts" window** (the lateral menu's last button) lists every chord the app answers to,
grouped by surface (`KeyboardShortcutCatalog`, `ui/ShortcutsWindow.kt`). The system-wide block is derived from
`GlobalShortcut` — so the window can never advertise a chord the app does not claim — and carries the claim
line, because "nothing happened" and "something else happened too" are otherwise undiagnosable.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy. The hook itself is
Windows-native, so it is verified on the machine, not by a test.

### The hover bubble is a stack of sections (PRD §8, ADR 0002) — SHIPPED 2026-08-23

Hovering a **layer** now names it. The bubble was one title + one optional "under" line, which meant the
elements the calendar draws across each other overwrote one another's reports — and a layer, being a
non-interactive overlay, reported nothing at all.

It is now a list of sections, one per thing true at the instant under the cursor, ordered by the user's
rule, top to bottom: `task = break > inactivity = sleep > no computer unlocked = no phone unlocked`. Equal
ranks are ties kept in collection order. One exclusion, also the user's: **when there is a break, there
can't be a task** — a break suspends the chunk it lands in, so the panel spans it, but the user is not on
that task.

The layer itself still registers no pointer input. Its section rides whatever the cursor is over, plus a
new bottom-most hover pickup under every panel and band for the stretches nothing else claims. The grey
sleep/inactivity bands became pure drawing (their hover children are gone), and `decorativeHoverZones` was
replaced by the general `bubbleHoverZones` tiler.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy.

### A device that cannot be asked was LOCKED (PRD §8, ADR 0002) — SHIPPED 2026-08-23

The layer default is **reversed**. `deviceLockedIntervals` returning `null` — "no device of this kind can tell"
— now hatches the whole asked past `[displayFloor, now]` for that layer, where it used to draw nothing.

The original spec sentence recorded in ADR 0002 said the unavailable device is *"considered to have been always
**unlocked** in the past"*; the user corrected the word after noticing that a desktop with no app installed on
the phone left the calendar largely unhatched. So the default now matches `derivePauses` rather than opposing
it: a device nobody can vouch for was not in use. On a one-device account the `\` phone layer therefore covers
the whole displayed past, and the "both layers ⇒ no-screen period" identity collapses to the computer's own
locked stretches.

`null` and an **empty list** stay different answers — an empty list is the OS saying "never locked" and still
draws nothing. What is new is a **third** state: "not asked yet". The first scan spawns a PowerShell process,
so treating pending as "cannot be asked" would flash a full-window hatch at every launch; `App.kt` gates the
own layer on `lockHistoryScanned` and draws nothing until the first answer lands (a later re-scan keeps
showing the previous answer while it runs).

Display-only: nothing in the scheduler reads a layer. Tests: `CalendarLayerTest`. Client rebuild
(`account{1,2,3}-*deploy*.bat`) — no Supabase surface.

### Only a conducted break is drawn in the past (PRD §15, ADR 0003) — SHIPPED 2026-08-22

The calendar's past side now shows **only the 20-second look-away**, and only when it ran whole.

**The 5- and 15-minute poses draw nothing in the past.** A pose used to vouch for exactly one occurrence, the
one ending at its anchor. But nothing about a pose ever happens in the app — it is only recognized after the
fact from an observed pause — and that pause is already on the calendar as what it really was: the two device
layers, the no-screen period, the derived "Inactivity" band. The pose band restated one fact as a second
object and gave it the break's nominal 5/15 min in place of the pause's real extent (an anchor seeded from a
night's sleep drew a tidy 5-min pose at the end of the night).

**A look-away that started but did not finish is erased.** `lastRestMillis` is an END, so nothing may move it
at a break's start — and the manual "Look away now" did exactly that, stamping the anchor at the press. That
drew a 20-s break over the 20 s *before* the manual one (the tail of the run the press had just interrupted,
offset by however late the press came), while the manual break itself — the one that actually happened — was
never drawn at all, since nothing moved the anchor when it ended. `SchedulerEngine.restartLookAway` now
dispatches on **completion**, to `resumeAt`, forward-only. A superseded run leaves no trace; a completed one
stays drawn where it happened and pushes the next occurrence an interval past its end. While the manual break
runs, the cue sweep swallows the automatic look-away start it stands in for (that due is still a crossable
boundary until the anchor moves).

Tests: `ManualLookAwayTest`, `ScreenBreakWindowTest`. No deploy needed beyond a client rebuild
(`account{1,2,3}-*deploy*.bat`) — client-only display + engine change, no Supabase surface.

### Default sub-tree under every newly created task (PRD §4/§7) — SHIPPED 2026-08-22

A new lateral-menu button, **"Default sub-tree"**, opens a floating window holding one per-account template
tree; the **switch to its left** says whether the policy is currently applied. While it is on, typing a title
into an empty cell no longer produces a bare leaf: the template is grafted under the task that naming just
created.

**A template node's `taskId` IS the row's switch** — `null` means "New id" (mint a brand-new task every time
the template is applied), a value means "point at this one task". There is no second boolean, and that is the
point: "picking an existing task turns the switch off", "turning the switch on re-selects New id", and "the
switch cannot be turned off while New id is selected" all fall out of the single field instead of being three
rules something has to keep consistent. The row's menu is the ordinary §4 naming block
(`SchedulerDomain.defaultSubtreeTaskMenuEntries`), so the window looks and behaves like every other field in
the app that names an object.

**A bound row contributes the bound task's own sub-tree, not the template's children.** A sub-list belongs to
the task id, not to the cell (that is what mirroring is), so the template cannot give a mirrored task different
children. Its template children are *kept* rather than deleted — turning the switch back on brings them back,
the same retention rule detached parents got the day before.

**The graft fires once, at `endEditSession`**, and only when the session actually **created** a task
(`taskId !in session.treeBefore.tasks`). Two rejected placements: inside `applySetCellTitle`, which the paste
path and the edit-session's own per-keystroke re-naming both call (a template would have been re-grafted on
every letter, and pasted trees would have been seeded); and gated on "the cell was empty", which cannot tell
creating a task from *reusing* one — reuse mirrors a task whose sub-tree already comes with the id, so there is
nothing to seed. It builds the rows by driving `applySetCellTitle` / `applyAssignTaskId` rather than writing
cells itself, so occurrences, `childTaskIds`, the title index and PRD §4 auto-expansion stay owned by the code
that already owns them. Riding the session's single "Edit" unit means one `Ctrl + Z` takes the seeded sub-tree
back with the title that pulled it in.

A binding the live tree cannot honour — the task was deleted, belongs to another task tree, or would duplicate
a task inside the sub-tree (`canAssignTaskId`) — falls back to a new task with the row's title, so a row never
silently disappears. A template is account-wide while a task id lives in one task tree, so this is the ordinary
case, not an edge one.

State: `SchedulerState.defaultSubtree` + `defaultSubtreeEnabled`, authoritative (persisted **and** synced, JSON
payload only — no SQLite schema change), resolved as one whole value by `SnapshotMerge` (interleaving two
devices' node insertions would produce a template neither of them drew). Both decode to "no template, switch
off" for payloads written before the feature, and `decode` runs `normalizeDefaultSubtree` so a blank-titled or
oddly-bound node from an older/hand-edited payload is healed rather than reaching the graft. Deliberately
**not** in `schedulingSignature`: a template schedules nothing until it is applied to a real cell.
Tests: `DefaultSubtreeTest`. Deploy: client rebuild only.

**The window is the task tree, plus one little switch per non-empty cell** (revised 2026-08-22, same day). The
first cut drew the template as its own thing — a column of always-on `OutlinedTextField`s, a bin button per row,
a caption naming the bound task — which read as a different feature from the tree it is a template *of*. It now
renders through the task sheet's own chrome, extracted to `ui/TaskSheetChrome.kt` (`SheetColors`,
`INDENT_STEP_DP`, `taskSheetGuideLines`, `TaskSheetExpandArrow`) and imported by both `TaskSchedulerScreen` and
`DefaultSubtreeWindow`, so there is one copy of the look rather than two that drift. The gestures came with it:
click to select, double-click **or simply typing** to open Edit Mode in place, `Enter`/`Shift+Enter`/`Tab`
navigation, `Ctrl+Enter` for a line break, `Backspace`/`Delete` to empty a row. The bin button is gone — the
blank title is what deletes, here as in the tree — and so is the caption: a bound row now **draws** the task's
own sub-tree beneath it (`SchedulerDomain.taskSubtreeOutline`, depth-capped), greyed and uneditable, the way the
tree draws a cell nothing may be done to. Only two columns are dropped, because a template has nothing to put in
them: the priority percentage (§5 — no tree, so no absolute priority) and the minimum time (§10 — no real task
yet); the switch takes the percentage's column at its width so both trees line up. The switch itself is drawn
compact rather than as a Material `Switch`, which measures taller than a 28 dp task-sheet row and would have
made the template's rows a different height from the tree's. Deploy: client rebuild only.

**Asking for a sub-tree ends the edit session** (fixed 2026-08-22, same day). Seeding at `endEditSession` has
a visible corner: the expand arrow of the cell you are *still typing in* opened the freshly named task onto
nothing but its empty placeholder, and the template only turned up after a click elsewhere had ended the
session for it. `SchedulerIntent.ToggleExpand` now forces the exit first (PRD §4 *Forced Exit*, as clicking
another cell already did) and applies the toggle only where the graft's own auto-expand did not already leave
the cell in the state the click asked for. Seeding per keystroke was rejected again for the same reason as
before, plus a new one: mid-session the "New task" draft can still be swapped for an existing id, and a draft
that had already been seeded would survive that swap as a **detached parent** — a titled task with a populated
sub-list no cell points at — leaving one junk sub-tree behind per abandoned draft. Tests: `DefaultSubtreeTest`
(`expanding_the_cell_being_edited_seeds_it_instead_of_opening_onto_nothing`, plus the arrow of *another* cell
and the collapse case). Deploy: client rebuild only.

Known scope limit: the template is one per account and shared by every task tree (§6); there is no per-tree
template, and no way to re-apply it to tasks that already exist.

### Calendar display indexes (PRD §8, ADR 0009) — SHIPPED 2026-08-22

The two remaining per-frame derivations named by the culling entry below were the ones it did *not* land:
`CalendarDisplayEquivalenceTest` was committed against a `recordsByDay` / `DeviceActivityIndex` that did not
exist, so `:shared:jvmTest` had not compiled since. Both now exist and are used.

`recordsByDay(records, firstDay, dayCount, tz)` places the whole visible span in one pass: each record's date
range is read once and it is dropped into the buckets of the days it touches, clipped to the span, so nothing
off-screen is built. It used to be one `recordsForDay` scan of every record in the account **per column**
(`DAY_COLUMNS × rowCount` of them). `DeviceActivityIndex(sessions)` builds the label table, the "known since"
floor and the start-ordered sessions once, and answers each panel by walking only the sessions that can
overlap it (binary search + a prefix maximum of the end instants); the per-panel form rebuilt the whole table
for every record on every observed now-line.

Cost only — both are pinned against the previous definitions (`recordsForDay`, `deviceActivitySegments`, kept
as the readable references) over randomized histories by `CalendarDisplayEquivalenceTest`. No scheduler,
state, persistence or wire change. Deploy: client rebuild only.

### Detached parent tasks survive a task-id change (PRD §4) — SHIPPED 2026-08-21

Re-pointing a cell at another task id used to **delete** the task it left the moment it lost its last cell
(`purgeOrphanTasks`), and the next edit boundary then collected its whole sub-tree (`pruneDetachedTree`), so
"change the id, then set the previous id back" came back with an empty sub-list — the sub-tree was gone, with
Undo as the only way back. The sub-list belongs to the **task id**, not to the cell (that is what makes mirrored
sub-trees work), so a titled task that keeps a populated sub-list is now retained cell-less as a **detached
parent** (`SchedulerDomain.isDetachedParentTask`): `purgeOrphanTasks` keeps it and `pruneDetachedTree` seeds its
reachability walk with its sub-list. Assigning that id back to any cell restores the sub-tree — the same thing
that already happened when the task kept a second occurrence elsewhere.

**Deletion is unchanged and is what bounds the retention:** emptying a cell (PRD §4 *Deletion*) blanks its task's
title, and a blank-titled task is never a detached parent, so the sub-tree still goes. That is also what keeps a
peer's deletion sticking through `SnapshotMerge.repair` (the merged task is either absent or blank-titled). A
*childless* task reassigned away is still purged.

Also PRD §4 *Presentation*, which the label had never implemented: a task **no cell points at** is now named in
the Change Task menu by its child titles instead of a path off the denormalized `Task.childTaskIds` — that path
survived the detachment and read as a live location the task no longer had. `childTitlesLabel` reads the shared
child list structurally (the source of truth `isLeafTask` uses), so a sub-tree that arrived by paste or by a move
is named too. No state, persistence or wire change (detached lists/cells were already persisted as whole maps and
`decode` does not prune). Tests: `SchedulerReducerTest`.

Known scope limit: a detached parent is reachable only through the Change Task menu — there is no view listing
them and no way to delete one without re-attaching it to a cell first.

### Calendar viewport culling (PRD §8, ADR 0009) — SHIPPED 2026-08-21

`DayColumn` emits UI nodes only for the hours inside the scroll viewport (`visibleHourWindow` → `HourWindow`,
quantized to one viewport-height of travel so the scroll does not recompose per pixel); the hour gutter is culled
the same way. Fixes "the app is sluggish while the calendar is open" — all the floating windows share one Compose
scene, so the calendar's ~1,700 composed records were being redrawn on every frame any other window animated.
2.2× / 3.7× / 11.6× fewer records composed at zoom 1 / 2.5 / 8 on a real account. Display-only: no scheduler,
state, persistence or wire change. Tests: `RollingCalendarTest`.

### No-screen / inactivity calendar entities (PRD §8) — SHIPPED 2026-07-19

`TaskPanel.noScreen` / `TaskPanel.inactivity` user-authored panels (authoritative, persisted + synced, old
payloads decode with the flags defaulted); the "add a no-screen period" / "add an inactivity period" contextual
menu options (1-hour default span at the click, then drag/resize); a no-screen period gets **Edit** too — a
times-only edit window, `ManualEntryEditWindow(timesOnly)` — while an inactivity period stays Remove-only (no task
behind it); the automatic **override/trim** both ways (`SchedulerReducer.resolveScreenOverrides`, wired into
add/update/move/resize/replace). Off-screen tasks and inactivity periods conflict with nothing and may overlap.
Tests: `NoScreenInactivityPanelTest`.

**Rendering revised 2026-08-20 to the two-LAYER model** (ADR 0002): the derived "No screen" band is gone and the
derived "Inactivity" band became the PAST-GAP grey band. A hand-added no-screen panel is now "a period asserting
both layers" (a faint outlined region, no pattern of its own); a hand-added inactivity panel is a solid GREY block,
and grey now means the scheduler places nothing there.

### Screen-switch enforcement (PRD §9) — SHIPPED 2026-07-19

`SchedulerDomain.fillSchedule` classifies the timeline by the no-screen periods (they are *not* occupancy
obstacles): on-screen tasks only outside them, off-screen tasks only inside them (none ⇒ never scheduled). A chunk
crossing a screen-zone edge is truncated like a pinned obstacle, unlike the screen-break resume.

- **Revised 2026-08-04.** The break's accepted set was `doableDuringBreak` alone, over the break's WHOLE length,
  which let an ON-screen task be scheduled inside a screen break and made the 20-s look-away an exclusion of every
  non-break-doable task (a spurious influence field every 20 min, forever). Replaced by the periods mapping in
  ADR 0001 §4.
- **Revised again 2026-08-09** to the three test-11 shapes: look-away accepts nobody; the 5-min pose is a closed
  first minute then `!onScreen && doableDuringBreak`; the 15-min pose is one open period accepting every
  `!onScreen` task.

Tests: `NoScreenInactivityPanelTest` (the closed head, the look-away, the on-screen refusal,
`the_15min_pose_accepts_every_off_screen_task_from_its_very_first_second`,
`the_5min_pose_still_refuses_an_off_screen_task_that_is_not_break_doable`),
`SchedulerPlanTest.a_15min_pose_at_the_now_line_is_one_open_period_accepting_the_off_screen_tasks`,
`SchedulerScreenFlagsTest`.

### Past no-screen ⇒ past inactivity (PRD §9/§12) — SHIPPED 2026-07-19

The schedule-advance (and `ReportDeviceSleep`) bank **no record** over a no-screen period for an on-screen task
(`appendRecordOutsideNoScreen` — the app assumes nothing happened), and the covered span is **materialized as a
real "Inactivity" panel** (`materializePastInactivity` in `SchedulerReducer` — outside Undo/Redo like the record
bank, never a syncable change on its own, skips spans an inactivity panel already covers, drops sub-minute
slivers).

Also: the calendar menu on a **sleep band** leads with Edit (opens the §17 sleep-schedule window, no Remove/move);
a **screen-break panel** deliberately has NO Edit (user-confirmed 2026-07-19; PRD §8 reworded to match).

### Phone activity = lock/unlock-gated heartbeat (PRD §15 / ARCHITECTURE §8) — SHIPPED 2026-07-19

Heartbeat replaced the WebSocket 2026-07-23; on-device verification pending.

`isScreenActive()` on Android is `AndroidUnlockTracker.unlocked` (SCREEN_OFF / SCREEN_ON / USER_PRESENT dynamic
receiver + Keyguard/PowerManager initial state; no keyguard ⇒ SCREEN_ON is the unlock). The device runs its `t_a`
presence tick while unlocked and, at lock, stops it and reports the screen-off straight to the Edge Function
(`notifyScreenOff`).

An unlock/lock flip and every app-foreground call `SchedulerEngine.onPlatformActivityChanged()` (an immediate beat
sample) so the tick resumes / the screen-off report goes out within moments, not at the next minute beat. Same on
the desktop via `DesktopSessionTracker` (JNA session lock/unlock); a restart after an abrupt kill resumes the tick
iff `isScreenActive()` says the device is unlocked.

One-time first-startup prompt (`MainActivity.maybePromptKeepAliveOnce`, flag in SharedPreferences): Doze exemption
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, added to the manifest) + best-effort OEM autostart settings
(MIUI/Huawei/ColorOS/Vivo). `AndroidForegroundTracker` remains only for the resume poke.

**iOS gap:** `isScreenActive()` there is still a hardcoded `false`. Wiring it to
`UIApplication.isProtectedDataAvailable` needs the Mac build that the iOS push code is already waiting on.

### Server-side break push (PRD §15) — SHIPPED 2026-07-19

Moved off the listener onto pg_cron 2026-07-23; moved off the `t_a` beat onto the event-driven `device_break` row
2026-07-26; live verification pending. See ADR 0006.

### Phone calendar gestures (PRD §8) — SHIPPED 2026-07-19

On-device verification pending. Pinch zoom already existed; added **double-tap-and-drag** zoom (exponential in drag
distance, anchored at the tap; a clean double-tap-release is left unconsumed), the **double-tap-and-release
contextual menu** (day column; opens on the block under the tap or empty space), the **panel info at the top** of
the touch menu (title + times — no hover bubble on a phone), and the **"move" menu option** (arms a move; the next
touch drag previews via the shared `dragPreview` overlay with the desktop snap rules and commits on release; a bare
tap cancels).

Touch presses on blocks are ignored entirely (`PointerType.Touch` early-return in `CalendarBlock`), so a
single-finger drag scrolls the grid and all block interaction goes through the menu.

### Terminology rename: "side task" → "screen break"

The eye-care breaks are named **"screen breaks"** everywhere (PRD §15) — UI, docs, code identifiers (`ScreenBreak`,
`screenBreak*`, `showScreenBreaks`, `DEFAULT_SCREEN_BREAKS`, `simulateScreenBreaks`, …) and the persisted JSON keys
(`screenBreak` panel flag, `showScreenBreaks`).

**Persisted-DB compatibility:** old on-disk/synced DBs still load — the codec maps the legacy keys onto the new
fields with `@JsonNames("sideTask")` / `@JsonNames("showSideTasks")` (encode writes the new keys; decode accepts
either). Covered by
`NoScreenInactivityPanelTest.codec_decodes_old_screen_break_key_names_as_screen_breaks`.

The screen-break config list itself is not persisted (hardcoded `DEFAULT_SCREEN_BREAKS`), so it needed no
migration.

The internal panel-id slug `side/{i}/{start}` was **deliberately left as-is** — it is a derived id, regenerated
every fill and stripped from the wire, so renaming it would gain nothing and only risk id-matching breakage.

---

## Scheduler model — the chain of corrections

Full rationale in ADR 0001. Each entry replaced the one above it.

| Date | Change |
| --- | --- |
| 2026-08-21 | **A claim is the lag counted in the task's own slots** (`(V−v)·p/m`), not the raw virtual clock. Test 14's 50 % task was getting 35 % (5 % of day one). Tests 1–10 byte-identical, so it only bites where the raw clock was wrong. Left open: a seeding defect at resumptions in a pool of interchangeable tasks (3 of 46 fail `check_resume_contract`). |
| 2026-08-20 | **The chunk scale is one ROUND** (`p·m_rival/(1−p)`), not one period (`p·T`). The old cap was a two-task coincidence and permitted a 15 h monolith of A. The lift (boost) and the cap (round) must be asked separately, or the atomic block loses its boost. `steady_cycle` had the same bug by another route. |
| 2026-08-20 | **Kotlin port caught up** — `SchedulerPlan.kt` was ~5 reference changes behind `scheduler_logic.py` (CLAUDE.md claimed 3 of them shipped, with tests that did not exist). Full backlog ported and pinned slot-for-slot against dumped reference output. |
| 2026-08-19 | **The forgetting is replayed over the past** (`_replay_clocks`), edge by edge. The seeding replayed `served/p` flat while the walk relaxes, so a re-plan never continued the walk. Now enforced by `check_resume_contract`. |
| 2026-08-18 | **The lookback window is measured in SCHEDULABLE time**, not wall time. Test 12's 50 % task was getting 2 %. |
| 2026-08-18 | **`last` reads `_last_run`, not `_head`.** A resumed plan refused the very task the timeline left off with, so the rightful pick lost a slot at every break (21 % instead of 39 %). |
| 2026-08-05 | Ported the reference's rewrite: a window bounds only the tasks it turns away; the atomic block (`_head`/`run_served`/`pending`). |
| 2026-08-04 | **The debt+decay model lasted 3 days.** `test.py` was rewritten and the app ported its WFQ virtual clock + capped exponential influence field (`SchedulerPlan.kt`); `SchedulerDebt.kt` DELETED. Trigger became a debounced `schedulingSignature` change, plus (later that day) an hourly staleness bound. |
| earlier | An EDF fill (`deadline = m/p`); helpers `edfPeriodMillis` / `nextTask` deleted. |

Also removed along the way: the per-tick `screenBreakDue → RefreshSchedule` in `dispatchScheduleAdvance` (it
churned the whole plan continuously while the user was away).

---

## Screen breaks

| Date | Change |
| --- | --- |
| 2026-08-05 | **Every screen break slides.** The 20-s look-away now pins to the now-line like a rest pose — an untaken break is OWED, not "assumed done". All cues key on the fixed due. |
| 2026-08-05 | **Three things serve a break** (a conducted look-away serves itself; a pose that happened serves every shorter break; a real pause ≥ 15 min). Fixes the reported *one look-away cue per session, then silence forever*. |
| 2026-08-05 | The look-away's `pauseThresholdMillis` went from 0 (i.e. 20 s, so any brief step away restarted the 20-minute clock) to 15 min. |
| 2026-08-05 | Every break recurs an interval after it **ENDS**, not after it starts. |
| 2026-08-05 | `DebugFlags.screenBreakOverrides` — all three breaks retimable independently on desktop; the legacy unprefixed properties became a named view onto the `5min_break` entry. |

---

## Sync

| Date | Change |
| --- | --- |
| 2026-07-30 | `scheduler_snapshot.writer_device_id` — the lost-acknowledgement repair. A push whose response was lost left the remote +1 revision, and the next reconcile pulled the device's OWN write over newer edits. Shipped with an `HttpTimeout` on the snapshot client and `Diagnostics` logging of every reconcile failure + LWW drop. |
| 2026-07-28 | **Startup reconcile.** A restored session reconciled nothing at launch, so the first edit's own auto-push fetch LWW-pulled over it. Also: `writer_device_id` is NULL on pre-fix revisions, so those are permanently unprotected. |
| 2026-07-28 | Realtime auto-pull **verified live** — but it never replays what it missed while disconnected, so every (re)subscribe now reconciles too. |
| 2026-07-22 | **Reversal of button-only:** local→remote auto-push (500 ms debounce) + remote→local auto-pull (`RealtimeSnapshotSubscriber` `postgres_changes`, migration `20260722000000`). `SchedulerSyncEngine` made `open` for the deterministic `BidirectionalSyncTest` double. |
| — | **Three-way merge** (`SnapshotMerge`, schema v10 / `9.sqm`) replaced whole-doc LWW; LWW survives only as the no-ancestor / undecodable fallback. |

Retired along the way: the five-sync-moments model, the startup remote-activity adoption
(`purgeLegacyAdoptedRows` heals old DBs), and the external Realtime-presence listener.

---

## Supabase migrations

| Migration | What it did |
| --- | --- |
| `20260713000000` | Dropped `pause_cue_schedule` / `derive_pauses` / `device_*`; added `account_state` for the listener era. |
| `20260716000000` | `kind` column on the remote active-session rows. |
| `20260721000000` | Device↔account exclusivity for **push tokens**. |
| `20260722000000` | `scheduler_snapshot` into the `supabase_realtime` publication with `replica identity full`. |
| `20260723000000` | `device_heartbeat` table + re-added `pause_cue_schedule`. The pg_cron pivot; the Fly.io `/listener` and the presence WebSocket were deleted. |
| `20260724000000` | The `t_a`/`t_b` model: `app_config` (t_a) + `break_config` (per-break length + vocal message) + `publish_presence()` (returns t_a) + `evaluate_pause_cue()` (decide + claim) + a `tick_pause_cues()` that only detects. |
| `20260725000000` | The **overdue gate** — a cue fires only when the account went idle with a break DUE. |
| `20260726000000` | Split the presence row in two: `device_heartbeat` keeps `{user_id, device_id, beat_at, data_payload_sent}` (`kind` / `next_break_*` dropped); new **`device_break`** table + `publish_next_break()` RPC. `overdue_break_at_last_beat()` factored out. **Changes `publish_presence`'s signature — Supabase AND every app must be redeployed.** |
| `20260727000000` | Extended device↔account exclusivity to `device_heartbeat` + `device_break`. |
| `20260728000000` | `device_break` became **account-keyed with just the two due instants** (`device_id` / `kind` / `break_kind` / `break_len_ms` dropped); the claim flag moved out into an account-keyed **`data_payload_sent`** table; break LENGTH moved server-side. **`publish_next_break`'s signature changed — redeploy Supabase AND every app.** |
| `20260729000000` | Split delivery in two: `pause-cue` (**e1**, clean lock, decides, anchors at `now()`) and `pause-cue-cron` (**e2**, cron decided, anchors at `t2`). `omni_edge_push` gained the function name (1-arg form dropped); shared `_shared/push.ts`. **Supabase-only redeploy.** |
| `20260730000000` | `scheduler_snapshot.writer_device_id` (nullable). **Apply before/with the app rebuild, or every fetch 400s.** |

`account_logout` (the remote force-logout marker) is applied by `deploy-supabase.bat`.

**Remaining follow-ups:** the Edge Functions' `FCM_` / `APNS_` secrets (`supabase secrets set`, project-wide so one
set covers both) and native phone push-token registration. Full runbook: `docs/PAUSE_CUE_DELIVERY.md`.

---

## Local SQLite schema

| Version | File | What it did |
| --- | --- | --- |
| v7 | — | `sleep_scan_checkpoint` table (local-only OS-sleep scan progress, in its own table so `sync_meta` writes can't clobber it). |
| v8 | `7.sqm` | `kind` column on `device_active_session`. |
| v9 | `8.sqm` | **Per-account partitioning** of `app_state` / `history_unit` / `history_pointer`; `account_sync` table (per-account revision baseline / `dirty` / logout baseline). Pre-v9 rows are filed under the account that was signed in when written, or into the `''` partition the first guest account adopts. |
| v10 | `9.sqm` | `account_sync.base_payload` — the merge's common ancestor. |

---

## Other dated decisions

| Date | Change |
| --- | --- |
| 2026-08-20 | **The calendar's two layers** replaced the "Inactivity" + "No screen" band pair; GREY became "the scheduler places nothing here". Sleep and hand-added inactivity periods now block the fill (previously sleep deliberately did not). Third iteration of the layer source — the first two readings shipped and were both wrong (ADR 0002). |
| 2026-08-20 | **No "focused week" any more** — the calendar scrolls endlessly and the schedule horizon follows the displayed day span in both directions (ADR 0009). |
| 2026-08-20 | The day-row sizing bug: `requiredHeight` silently centred every row, showing the wrong hours (≈8 h off) and hiding the now-line. |
| 2026-08-20 | **Relative priority** — the percentage's own right-click menu, the pin semantics, `RelativePriorityDomain` (ADR 0004). |
| 2026-08-06 | An alarm's **days** became part of the alarm (synced), not of the device; `repeatDaily` → `repeats`. |
| 2026-08-01 | **The desktop rings alarms too**, off the now-line rather than an armed OS alarm. PRD §18 used to say it never rings. |
| 2026-07-28 | **Horizon refill self-retrigger** — the release app was a tray icon with no window; `launchHorizonReschedule` re-fired with zero delay forever, pegging the EDT. Fixed with a refill margin + rate floor. |
| 2026-07-24 | The presence model reshaped to the user's `t_a`/`t_b` spec (ADR 0006). |
| 2026-07-23 | The Fly.io `/listener` and the presence WebSocket removed; the pause cue moved onto a pg_cron tick. The user reasoned the listener was a "false solution" (also heartbeat + poll), so no host warranted it. |
| 2026-07-19 | The whole 1.6.0 delta batch above went code-complete. |
| 2026-07-09 | The startup remote-activity adoption retired — it fabricated activity over genuine pauses. |
| — | `android:allowBackup="false"` — OS auto-restore silently resurrected a "wiped" install's DB. |
