# Every action a user takes — with real-life examples

What a user does, from the first launch to living in a finished schedule, including the actions for undoing a
mistake and for finding something again. Written against the 1.6.0 branch as of 2026-11-22, checked against the
code (`CustomMenuButtons.DEFAULTS`, `SearchDomain.Kind` / `AddedAction`, `KeyboardShortcuts`), not only against
`docs/PRD_TaskScheduler.md`, which lags it in places (see the end of this file).

The running example is **Léa**, a freelance developer. She has client work, wants to land a job in Spain, learns
Spanish, goes to the gym, and keeps forgetting to water her plants.

---

## 0. What she sees at first launch

- The app opens on the **Task Scheduler** page, signed in to a **guest account** already: there is no sign-up step.
- The **top-left button** is the page selector: *Task Scheduler*, *Claude Code logs*, *Settings*.
- The **lateral menu** holds, until she changes it: **Search**, **Mute all sound**, **Voice**, **Notifications**,
  **Task trees** (a Search window on the global root, i.e. the task tree), **History units**, **Life context**,
  **Calendar**, **Look away now**, **Switch task**, **Sleep / Work**, **I'm away**.
- Along the bottom, the **window bar**: a tab per open window, and **Reset** at its right.

## 1. Set the app up (once)

| Action | How | Example |
| --- | --- | --- |
| Set the sleep schedule | Calendar element → **Sleep schedule** action (or add the **Sleep** control to the menu): wake time, goal wake time (+ optional date), total sleep | Léa wakes at 08:30 but wants 07:00: wake 08:30, goal 07:00. The wake time drifts 15 min every 2 days toward 07:00; the hour before bed becomes a "before bed" period |
| Choose how she is told things | **Notifications** switch, **Voice** switch, **Global volume**, **Mute all sound** | Voice on at home, Mute all when on a call |
| Make a real account | Settings page → **Online** → *Create account* (or *Sign in* to an existing one) | She wants the phone to follow the laptop |
| Point at her own server | Settings page → **Server**: project URL + anon key (`docs/SERVER_SETUP.md`) | First time only |
| Work offline | **Online** → *Work offline* | On a plane; edits sync when she switches it back |
| Rebind a system-wide chord | Search → *keyboard shortcut* row → **Chord** | `Ctrl+Shift+Alt+A` clashes with a tool she uses |
| Tune the planner | Calendar element → **Allocated resources (q)**, **Minimum time weight**, **Auto schedule** | Laptop on battery: lower q |
| Install on the phone | Same account on Android; allow notifications | So the break-over cue reaches her when away from every screen |

## 2. Declare what matters: build the task tree

Open **Task trees** in the menu. The global root (*Task trees*) holds one task per tree; her everyday tree is one
of them.

| Action | How | Example |
| --- | --- | --- |
| Add a task | Select an empty cell and type; `Enter` commits and moves down | "Client work", "Job in Spain", "Health", "Home" |
| Break a task down | `Tab` into the child list and type | Job in Spain → "Spanish", "Portfolio", "Applications" |
| Reuse one task in two places (mirror) | While typing a title, pick the existing task in the **Tasks** menu (Change Task mode) | "Spanish" under *Job in Spain* and under *Social life*: one task, its children shown in both |
| Make a new task with a title that already exists | Pick **New task** in the Tasks menu | "Read" under *Spanish* and a different "Read" under *Portfolio* |
| Rename a task everywhere | In Edit Mode, switch the mode selector to **Rename** | "Gym" → "Strength training" |
| Accept a title suggestion | Click a row of **Title suggestions** | Typed "spanihs", suggestion "Spanish" |
| Multi-line title | `Ctrl + Enter` | |
| Reorder / move cells | Double-click-drag the selected block; the blue line shows the drop | Move "Applications" above "Portfolio" |
| Add a ready-made breakdown | Build the **Default sub-tree** (task tree window's configuration section) and switch it on; or right-click a cell → **add default sub-tree** | Every new project gets "Plan / Do / Review" |
| Copy / paste sub-trees | `Ctrl+C` (task ids: mirrors), `Ctrl+X` (whole sub-tree as readable text), right-click → **deep copy** (choose depth, ids, weights, minimum, text, excluded titles); `Ctrl+V` on one cell | Duplicate last quarter's client template |
| Fold a branch | Expand arrows; right-click → **collapse sub-trees** | |
| Keep an alternative arrangement | Add another task directly under the global root (a second tree), weight 0 until wanted | "Summer plan" tree, switched on in July by giving it weight |
| Find & replace titles | `Ctrl + F` in the tree; chevron for Replace / Replace All | "Applications" → "Job applications" everywhere |
| Delete a task | Empty its title (`Backspace` / `Delete` on the selected cells) | Drop "Portfolio" |

## 3. Say how much each thing matters

| Action | How | Example |
| --- | --- | --- |
| Set weights in a sub-list | Click a percentage → priority weight window (table + pie); weights are relative within the sub-list | Client work 3, Job in Spain 2, Health 1, Home 1 |
| Add / reorder / reset weight columns, set the default row | Same window | A second column "energy", a default for newly arrived rows |
| Say "this as a share of that" | Right-click a percentage → **relative priority** | "Spanish = 30 % of Job in Spain" |
| Keep or discard relations | Search → *task relation* rows | Keep the Spanish one; drop the noise |
| Label tasks | **Categories** field on a cell, or Search → *task category* → New | "deep work", "outdoor" |
| Make a category hold a share | Category → **Rules** → Add a rule | "every *deep work* task is 33 % of its parent" |
| Make a target move with time | Search → task → **Presence ratio target** / **Minimum length target**: formula of `t` (`ramp`, `step`, `date(...)`) | Spanish ramps from 10 % to 30 % between 1 Dec and 1 Mar |
| Let one task count for another | Task → **Set of tasks** / **Fulfilled by** | "Watch chemistry videos in Spanish" fulfils "Spanish" without being in the tree |

## 4. Say how time may be spent

| Action | How | Example |
| --- | --- | --- |
| Shortest block | Click the minimum time beside the percentage (default 45 min) | Client work 90 min, Duolingo 15 min |
| Where a task may run | Right-click → **edit task** → resilience per kind of period (0 % forbids, 100 % untouched) | "Gym" 100 % in *no on-screen task*, 0 % in *no task allowed* |
| Define a kind of period | In the resilience section: name + `+` (new kind starts at 0 % for every task); a kind's own window sets every task at once | "Commute": only podcasts and reading allowed |
| Steps inside a task | **edit task** → schedule unit: titled steps with lengths (sum ≤ minimum) | Gym: warm-up 10, lifts 40, stretch 10 |
| Notes on a task | Task → **Md elements** (markdown, `[[Name]]` links) | Spanish: list of resources |

## 5. Read the plan

Open **Calendar**. The planner has already filled the week; nothing is pressed.

| Action | How | Example |
| --- | --- | --- |
| Change day / week | The calendar's **Day** and **Display** actions | Look at next Tuesday |
| Zoom | `Ctrl` + `+`/`−`/wheel, `Ctrl+0` resets to the waking day | |
| Inspect a spot | Hover (desktop) — the info bubble lists every layer there; on phone, double-tap | "Why is nothing scheduled at 14:00?" → a *no screen* period |
| Show / hide drawings | **Screen breaks**, **Reminders** switches (drawing only, never notifications) | |
| See what she actually did | The past is task panels, grey (nothing scheduled) and hatch layers (no computer / no phone unlocked) | Friday: 2 h of "Client work" actually done |

## 6. Steer the plan

| Action | How | Example |
| --- | --- | --- |
| Move a block | Drag it (phone: menu → **move**); placing by hand pins it | Gym moved to 18:00 |
| Resize | Drag an edge; `O` toggles side-by-side overlap while dragging | Client call runs 30 min longer |
| Add something at a time | Right-click → **add…** → task panel / restrictive period / reminder | Dentist 10:00–11:00 as *no task allowed* |
| Edit what is at a spot | Right-click → **edit…** → task / task panel / period / reminder / alarm / timer / sleep | Change the dentist period's end |
| Pins | Panel's editor: keep occurrence, fix position, fix duration, fix distance | Keep Monday's gym even if priorities change |
| Mark time with no screen / inactivity | add… → restrictive period of that kind, bounds as date-time, "now" or ∞ | Hiking Sunday |
| Delete a block or period | Its editor → **Delete** | |
| Do this task now | Tree cell right-click → **start this task now**; anywhere: `Ctrl+Shift+Alt+T` picker | A client email just arrived |
| Not this task now | **Switch task** / `Ctrl+Shift+Alt+Z` (costs the task nothing) | Too tired for Spanish |
| Freeze the plan | **Auto schedule** off | While restructuring the tree |
| Jump from a block to its task | Panel menu → **go to task tree** | |

## 7. Live in it

| Action | How | Example |
| --- | --- | --- |
| Follow switches | "Task to do now" notification + voice | "Spanish — step: vocabulary 15 min" |
| Take screen breaks | 20 s look-away every 20 min (assumed done); 5-min and 15-min poses stay owed until she actually leaves every screen | |
| Look away on demand | **Look away now** / `Ctrl+Shift+Alt+E` | Eyes tired mid-meeting |
| Leave this device | **I'm away** / `Ctrl+Shift+Alt+A` (unlocking turns it off); locking silences the device | Going to read a paper book next to the laptop |
| Silence everything | **Notifications** / `Ctrl+Shift+Alt+N` | Client demo |
| Go to bed / start the day | **Sleep / Work** (account-wide) | Finishes late: Sleep stops the break-over cue on the phone |
| Walk away from all screens | Nothing: the phone speaks when the break is over | 15-min pose, coffee in the kitchen |

## 8. Things that are not tasks

| Action | How | Example |
| --- | --- | --- |
| Reminder | Search → *reminder* → New, or calendar add… → reminder; cadence (`31/21`, "2.5 per week"), time, **Constrained in** another reminder's days, alert block | Water plants every 3 days; overdue ones stack on the now-line |
| Check a reminder off | Click its tag | |
| Alarm | Search → *alarm* → New: time, days, rings for, repeat, alert; rings on every device | 07:00 weekdays |
| Timer | Search → *timer* → New → **Run** | Pasta, 9 min |
| Chrono | Search → *chrono* → New → **Run** | How long the tax form takes |
| Quota | Search → *quota* → New: amount, loop, restarts, resilience; read the target progression | 100 km of running per month |
| Workplace setup | Search → *workplace setup*: which windows to arrange → **Apply** | "Coding" layout on the laptop |
| Life context | **Life context** button: one md element about her situation | "Moving to Valencia in March" |

## 9. Windows and the menu

| Action | How |
| --- | --- |
| Arrange windows | Drag the head; fill width / fill height / reduce / maximize / close; **duplicate** for an independent copy |
| Pin a window to the menu | **add to the menu** in its head (e.g. her "Spanish" task's window) |
| Customize the menu | Right-click → Customize: rename, reorder by dragging, folders, Remove, Update, Reset |
| Walk the windows | Window bar tabs; `Ctrl+Tab` / `Ctrl+Shift+Tab` along the tabs; `Ctrl+Page Up` / `Ctrl+Page Down` undo / redo a move between windows; `Shift+Alt+←/→`; `Ctrl+W` closes the focused window |
| Clean up | Window bar **Reset** (closes all, calendar maximized); right-click tabs → close / minimize / open selection |

## 10. When she makes a mistake

| Mistake | Recovery |
| --- | --- |
| Typo while editing | `Escape` reverts every cell the session touched |
| Wrong edit in a window | `Ctrl+Z` / `Ctrl+Y` in that window (each window has its own) |
| Changed something in another window and does not remember which | `Ctrl+Shift+Z` / `Ctrl+Shift+Y` walk every window |
| Deleted a task by emptying its title | `Ctrl+Z`; much later, type its title and pick its `[dead]` row in the Tasks menu (it lives while its past does); Search shows it with its **last path** |
| Picked the wrong task in the Tasks menu | Re-enter Edit Mode and pick the right row; "New task" gives a fresh id |
| Dragged a block wrongly | `Ctrl+Z` in the calendar; mid-drag the menu offers **cancel** |
| Laid a period over the past by mistake | `Ctrl+Z` in the calendar (a period over the past also clears the work recorded under it, so undo rather than delete) |
| Bad formula | Refused as typed, with the reason under the field |
| Closed a window | `Shift+Alt+←` brings a closed pop-up back; the menu button reopens it |
| Lost the window layout | Window bar **Reset** |
| Edited on two devices at once | Nothing to do: both edits are merged |
| Edited under the debug time simulation | Those units are reverted when real time resumes |
| Pressed I'm away by mistake | Press it again ("I'm back") |

## 11. When she needs to find something

| Question | Where |
| --- | --- |
| Where is task X in the tree? | Search (kind *task*): title + path; right-click → **go to task tree**; or `Ctrl+F` in the tree |
| What is this block on the calendar? | Hover bubble; edit… → task |
| When is task X planned next / where did it go? | Task → **Go to calendar**, **Blocks on the calendar** |
| What did I do last week? | Calendar, past days |
| What changed, and when? | **History units** button (filter by window, text, chord) |
| What did the app tell me? | Search kind *notification* (also the window opened by an unfocused notification) |
| Why is my share of X that number? | Click its percentage (pie), right-click → relative priority, Search → task relations, category rules |
| Which tasks are allowed in a kind of period? | The period kind's window, or **Search its tasks** |
| Which shortcuts exist? | Search kind *keyboard shortcut* |
| Is sync healthy? | **Online** label and window; Settings → *Supabase free tier* |
| Which windows are open? | Window bar; Search kind *window* |

---

## Smells found while writing this (2026-11-22)

Fixed in the same commit:

1. **The README's Usage section described a menu that no longer exists** — "Task tree", "Reminders", "Alarms",
   "History", "All task trees", "All tasks" buttons, a calendar *window*, a right-click "remove", task notes in "edit
   task". A new user following it would not find half of it. Rewritten from the code's defaults.
2. **"Four system-wide chords"** in the README and `CLAUDE.md`: there are five (`+T`, the task picker, is the fifth).
3. **Deep copy's "three switches"** (`DeepCopyWindow` KDoc, PRD §13): the window has five switches and an
   excluded-title field; reset restores them all.

Open, for the user to decide:

4. **Long task titles are clipped with a red ▸ in the tree** (`TaskSchedulerScreen.kt`, PRD §2), while the
   user rule 2026-11-04 says no text is cut, a long text wraps. `NoTextCutByAnEllipsisTest` only catches
   `TextOverflow.Ellipsis`, so `Clip` + arrow passes it.
5. **`docs/PRD_TaskScheduler.md` lags the code**: §7 still has a calendar toggle button, a "Task tree" button, a
   Categories button and a lateral-menu button rule naming reminders/history; §7's Search kinds list misses chrono,
   quota, calendar, calendar block, notification, app setting, workplace setup, md element, the two evolutions and
   window, and still lists "task tree" (gone since 2026-11-16); §13's "text" section was replaced by md elements;
   PRD scope still lists dated task trees.
