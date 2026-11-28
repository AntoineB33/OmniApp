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
| the tasks with no parent task | "Parents." | Which elements › By parent › **Has no parent of the types** › task |

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

## Known gaps, not fixed yet

- **The children of one element as a list.** "Subtasks of Work" is answered by typing "Work" and opening its row
  (Display › Expand the results), not by a filter of its own; "By parent" lists parents only.
- **The tasks with no category.** Tasks › Category picks one category; "none" is not a choice.
- **Conditions across types** ("the alarms changed on a day a timer ran") have no path.
- **The ▾'s bar reads no info bubble** (measured: too many answers); a request worded only like a description is
  found by its category instead.
