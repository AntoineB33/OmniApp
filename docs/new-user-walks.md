# New-user walks of the Search window's ▾

User 2026-11-28: *"I want proper tree architectures to ensure very intuitive navigation for a new user to easily and
rapidly get to the element they want. They don't know in advance where to look, and they don't necessarily read fast."*

Each walk is a request a new user makes, what they think at each step, and what they hover and set. Every walk starts
the same way: **Search** in the left-side menu (*"lists are made there"*), then the **▾** beside the bar (*"the options
must be behind the arrow"*). The root reads: Recent, Currently on, Find by text, Which elements, Order, Display, Tasks &
targets, Calendar, Alarms timers & alerts, App & history, Other pages.

`NewUserWalksTest` holds every walk below as data (the request, the categories hovered, the configuration set) and fails
when one stops leading there, or when a top category has no walk. A request the tree cannot answer is fixed in the tree
first, then added to the test and here.

## Find by text

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| tasks whose description mentions the dentist | "Typing dentist finds names only. Where do I say *description*?" | Find by text › Where to search › **Description**, then type "dentist" in the bar |
| the word run, not running | "It must be about how the text matches." | Find by text › Matching › **Match whole word** |
| tasks with gym or sport | "Two words, either one." | Find by text › **Search bars** › add a bar, formula OR |
| tasks whose subtasks say buy | "Found by what's under them." | Find by text › Where to search › In children › **Search the children** |

## Which elements

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| only the alarms and the timers | "Which elements: that's choosing what's listed." | Which elements › **Types** › alarm, timer |
| everything but one task | "Leave one out." | Which elements › Include & exclude › **Excluded elements** |
| what I changed yesterday | "A day. Date & calendar." | Which elements › Date & calendar › **Dated from** › span *yesterday* |
| what is on my calendar tomorrow at 3 pm | "The calendar, at a moment." | Which elements › Date & calendar › **Is on the calendar at** |
| the tasks with no parent task | "Parents." | Which elements › By parent or child › **Has no parent of the types** › task |
| the subtasks of Work | "Children of something." | Which elements › By parent or child › **Children of** › Work |
| the alarms changed on a day a timer ran | "Two things: first, when did the timer run?" | App & history › History › **Changed element** › the timer: its units give the day. Then a new list: Which elements › Types › alarm; Date & calendar › **Changed from** › that day (and **Changed until**) |

*Fixed 2026-11-28:* "the subtasks of Work" had no filter ("By parent" listed parents only): **Children of** lists the
direct children, here and under Tasks. A request across types had no path: **Changed from / Changed until** lists the
elements a history unit changed in a span, so the day one thing happened is read off its history, then asked of another
type.

*Fixed 2026-11-28:* "yesterday" had only **Dated on**'s weekday chips, which also list the same weekday of every other
week. **Dated from** has today, yesterday, the last 7 and 30 days in a drop-down beside its field, which fill it and
**Dated until**.

## Order

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| everything by name | "Order." | Order › **Sort by** › name |
| the alarms by their time | "Order, again." | Order › **Sort by** › *alarm: time* |
| the same, by someone who thinks "alarms" first | "Alarms have their own options." | Alarms, timers & alerts › Alarms › **Sort by** › time |

*Fixed 2026-11-28:* Order's Sort by offered the whole list's methods alone; a type's ("alarm: time", "history unit:
date") stood only under that type. Both now write the one list of methods. The ▾'s bar finds a Sort by by its methods:
"sort by date", "sort by time".

## Display

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| this window bigger when it opens | "How it looks." | Display › **Size** |
| the results opened on their children | "How it looks." | Display › **Expand the results** |

## Tasks & targets

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| the tasks I deleted | "Tasks." | Tasks & targets › Tasks › **In a task tree** › *no (deleted)* |
| the tasks the scheduler can place | "Tasks." | Tasks & targets › Tasks › **Schedulable** › yes |
| the tasks of one category | "Tasks." | Tasks & targets › Tasks › **Category** |
| the tasks with no category | "Tasks." | Tasks & targets › Tasks › **Has a category** › no |
| the subtasks of Work | "Tasks, under one." | Tasks & targets › Tasks › **Children of** › Work |

*Fixed 2026-11-28:* the category filter picks one category and has no "none": **Has a category** › no.

*Fixed 2026-11-28:* "In a task tree › no" never said *deleted*; it reads "no (deleted)", and the ▾'s bar finds it by
"deleted".

## Calendar

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| the restrictive periods I made myself | "Periods are on the calendar." | Calendar › Restrictive periods › **Default periods** › *no (yours)* |
| the periods never put on the calendar | "Same place." | Calendar › Restrictive periods › **On the calendar** › no |

*Fixed 2026-11-28:* "Default periods" read yes / no; they read "yes (the app's)" / "no (yours)".

## Alarms, timers & alerts

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| the alarms that are off | "Alarms." | Alarms, timers & alerts › Alarms › **State** › off |
| the alarms ringing at the weekend | "Alarms, their days." | Alarms, timers & alerts › Alarms › **Rings on** › Sa, Su |
| the timers running now | "Timers." | Alarms, timers & alerts › Timers › **State** › running |
| the repeating reminders | "Reminders." | Alarms, timers & alerts › Reminders › **Repeats** |
| the screen-break notifications | "Notifications are alerts." | Alarms, timers & alerts › Notifications › **From** › screen break |
| the notifications of the last 7 days | "Same place, a span." | Alarms, timers & alerts › Notifications › **Dated from** › span *last 7 days* |

## App & history

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| the history units about a window opening or closing | "History." | App & history › History › **What was done to a window** › opened, closed; **Sort by** › date |
| the same, by someone who thinks "windows" first | "It's about windows." | App & history › Windows › **What was done to a window** |
| the history, newest first | "History." | App & history › History › **Sort by** › date, descending |
| what I undid | "History." | App & history › History › **Undone** › yes |
| every change to my alarm Wake up | "History of one thing." | App & history › History › **Changed element** |
| yesterday's history | "History, a day." | App & history › History › **Dated from** › span *yesterday* |
| the windows minimized now | "Windows." | App & history › Windows › **State** › minimized |
| the keyboard shortcuts I changed | "Shortcuts." | App & history › Keyboard shortcuts › **Rebound** › yes |

*Fixed 2026-11-28:* no filter said what a unit did to a window ("Changed element types › window" also listed every move);
"Category › window navigation" looked like the answer and held only focus moves (it reads "focus moves between windows").

## Other pages

| Request | Thinking | Hovers › set |
| --- | --- | --- |
| my Claude prompts in one project | "Not the scheduler: another page." | Other pages › Claude Code logs › Prompts › **Project** |

## The task tree

User 2026-11-28: *"Start with adjusting the task tree with particular situations."* These walks start on the Task
Scheduler's tree, where a new user's first move on a task is a **right-click** on its row (*"what can I do with
this?"*). The menu reads, from the top: start this task now, **rename (enter)**, **add a sub-task**, **delete (del)**,
edit task, copy id, copy path, deep copy, collapse. **edit task** opens the task's own window, whose actions read as a
handful of categories, each a click to open: Open or remove from the list, Notes (md elements), Clicks and keys,
**Open & go to**, **Calendar**, **How long it takes**, **Priority**, **Sub-tasks & place in the tree**, **Categories**,
**Copy & create**. `TaskActionsTreeTest` holds every walk below.

| Situation and request | Thinking | Clicks |
| --- | --- | --- |
| a new tree, first task "Dentist": give it a step "Book it" | "Something *under* it. Right-click." | right-click Dentist › **add a sub-task**, type "Book it", Enter |
| a typo in "Dentsit" | "Rename. Right-click." | right-click › **rename (enter)** (also: Enter on the selected row) |
| a task no longer wanted | "Delete. Right-click." | right-click › **delete (del)** (also: Delete on the selected row) |
| the dentist takes at least 45 min | "How long… it's the task's own setting: edit it." | right-click › edit task › **How long it takes** › Minimum time |
| a 10-min warm-up, then the run | "Splitting its time." | edit task › How long it takes › **Schedule unit** |
| do it right now | "Right-click shows it first." | right-click › **start this task now** (or edit task › Calendar › Start now) |
| put it on Thursday at 6 pm | "The calendar." | edit task › **Calendar** › Add to the calendar |
| may it happen during Sleep? | "When it can be placed: the calendar." | edit task › Calendar › **Resilience** |
| move its blocks already placed | "The calendar, its blocks." | edit task › Calendar › **Checked blocks** |
| where is it on the calendar? | "Go there." | edit task › **Open & go to** › Go to calendar |
| a task found in a Search window: where is it in the tree? | "Go there." | edit task › Open & go to › **Go to task tree** |
| it should count more than its siblings | "How much it counts: priority." | edit task › **Priority** › Priority weights |
| how important is it overall? | "Priority." | edit task › Priority › **Relative priority** |
| running also counts for Health | "It counts for another task." | edit task › Priority › **Set of tasks** |
| "Groceries" also belongs under Home | "A second place in the tree." | edit task › **Sub-tasks & place in the tree** › Add under |
| a task mirrored in three places: which? | "Its places." | edit task › Sub-tasks & place in the tree › **Paths** |
| give it my usual sub-steps | "Its sub-tasks." | edit task › Sub-tasks & place in the tree › **Add default sub-tree** |
| a tree 6 levels deep, everything opened: tidy up | "Close what's under it." | right-click › **collapse** (level 0 closes all, 1 keeps its children) |
| label it urgent | "A label is a category." | edit task › **Categories** › Add a category |
| a second gym task like this one | "A copy." | edit task › **Copy & create** › Duplicate |
| send the branch as text | "Copy, with what's under it." | right-click › **deep copy** (or edit task › Copy & create › Deep copy) |
| write why I do it | "A note." | edit task › **Notes (md elements)** |
| 300 tasks: where is "dentist"? | "Lists are made in Search." | left-side menu **Search**, type "dentist": each row says its path (or Ctrl+F in the tree) |
| a word typed anywhere in the actions | "I'll type what I want." | edit task, type "minimum" in the actions' bar: its category opens on it |

*Fixed 2026-11-28:* a task's window listed its 32 actions in one column, past the 15 rows a node may hold: they are
under the categories above (`SearchDomain.TASK_ACTION_CATEGORIES`, the same funnel as "Deep copy" and "Checked
blocks", which now stand inside Copy & create and Calendar). Renaming, adding a sub-task and deleting were keys alone
(Enter, the arrow then a click, Delete): the right-click names them. "Md elements" reads "Notes (md elements)".

## Known gaps, not fixed yet

- **Conditions across types are two walks, not one.** "The alarms changed on a day a timer ran" reads the day off the
  timer's history, then asks Changed from / until of the alarms; no one configuration joins two types.
- **Children of** lists the direct children; their children are not listed (a "child distance" would).
- **The ▾'s bar reads no info bubble** (measured: too many answers); a request worded only like a description is
  found by its category instead.
- **A task's colour** is the app's (every task its own, `TaskColorSpace`): a request to "make it red" has no action.
- **"Clicks and keys"** stays a top row of a task's actions, a category of one; a new user rarely needs it first.
- **The Search window's task rows** keep their own menu (add, add and remove the others…): rename, add a sub-task and
  delete are the tree's rows' alone.
