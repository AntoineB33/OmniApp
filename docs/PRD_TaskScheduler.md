# Task Scheduler Module - Product Requirements

**Version:** 1.4.0

## 1. Overview

The Task Scheduler is a heavily interactive, infinitely nestable list of cells. It borrows interaction paradigms from spreadsheet applications (like Google Sheets) while applying them to a hierarchical tree structure. This version targets Windows desktop, but stays cross-platform as much as possible.

A single task can exist in multiple cells across the tree. For example: to "find a job," one must "practice English," and to "socialize abroad," one must also "practice English." Two cells sharing the same `taskId` will perfectly mirror the same sub-tree. 
* **Constraint 1:** A `taskId` cannot appear multiple times within the exact same list (this would create conflicting priority values for the same parent task).
* **Constraint 2:** A cell's `taskId` cannot match the `taskId` of any of its ancestor cells (to prevent infinite recursive cycles).
* **Constraint 3:** Multiple unique `taskIds` can share the same title string (e.g., "watch Youtube" under "learn to cook" is a different `taskId` than "watch Youtube" under "learn Spanish").

## 2. Structural UI & Aesthetics

* **Infinite Tree View:** Cells are displayed in a vertical list. The rendered UI tree is strictly synced with the underlying `Map` Tree. 
* **Nesting UI:** The left side of the cells features arrows (if there is a populated sub-tree) and guide-lines (if the cell is expanded) illustrating the parent-child hierarchy. The "root" cell conceptually exists, but the viewport only renders its children.
* **Collapsibility:** Clicking structural arrows toggles the visibility of a cell's sub-list.
* **Visual Aesthetics:** Cell aesthetics strictly mirror Google Sheets. This includes standard resting states, active selection borders/highlighting, and inline editing UI.
* **Priority Display:** The absolute priority percentage is displayed at the right side of the cell. In a sub-list, it is displayed at the same horizontal position. The length between the beginning of the text content and the display of the priority percentage is the maximum of the horizontal lengths of the cell text contents of the sub-list, constrained by a minimum and maximum length. If the text content is too long, the exceeding part is hidden with a little red arrow to the right. This happens even if the cell is in Edit Mode.

## 3. Selection Mechanics (Spreadsheet Paradigm)

The application maintains a dual-state selection model: **Main Selection** (a single active cell or null) and a **Selected Cells List** (a collection of active cells). *Note: Hidden/collapsed cells are ignored during vertical traversal for selection calculations.*

* **Root and Main Cells:** The "root" and "main" cells cannot be selected, edited, or interacted with via mouse events. The "root" cell is only conceptual.
* **Single Click:** The *Selected Cells List* is reset and the clicked cell becomes the *Main Selection*.
* **Single Click & Drag:** * The initial click establishes the *Main Selection*.
  * While dragging without releasing the click, the *Selected Cells List* dynamically spans all visible cells from the Main Selection to the cell currently closest to the cursor.
* **Double Click & Drag:** * If the cell the user double-clicks on is selected and all the selected cells are sequential and in the same sub-list, the whole selection is moved. Otherwise, the double-click empties the *Selected Cells List* then establishes the *Main Selection*.
  * While dragging without releasing the click, the cell gets a grey background and a horizontal blue line between cells indicates where the selection will be placed if the user releases the click. If the changes happen in sub-trees that are mirrored elsewhere (because of sharing `taskIds`), then the change is synced only when the move is finished (the blue line and grey background aren't mirrored).
* **Ctrl + Click:** The clicked cell becomes the *Main Selection* (standard OS `Ctrl` behavior applies for isolated multi-selection).
* **Shift + Click / Ctrl + Shift + Click:** The *Selected Cells List* expands to include all visible cells sequentially from the current *Main Selection* to the newly clicked cell.
* **Up / Left:** Resets selection, and if there is a selectable cell above the main selection, makes it the main selection. Doesn't work when in Editing Mode.
* **Down / Right:** Resets selection, and if there is a selectable cell below the main selection, makes it the main selection. Doesn't work when in Editing Mode.
* **Shift + Direction:** Pressing `Shift` while navigating with the direction keyboard keys allow to select a range of cells. `Right` is `Down` and `Left`. If several cells were selected before pressing `Shift`, it is reset. 
* **Enter or Tab:** If there are several selected cells, moves the main selection among them, from top to bottom then back on top. With `Shift` pressed, it moves backward.
* **Ctrl + A:** Ctrl + A selects all visible cells.


## 4. Editing Mechanics

### Entering Edit Mode
* **Double-click:** Enters Edit Mode on the target cell.
* **Enter or Shift + Enter:** Enters Edit Mode on the target cell if there is only one selected cell.
* **Typing:** If a cell is the *Main Selection* (and not the "root" or "main" cell), typing immediately enters Edit Mode and captures the keystroke.
  * *Dead keys:* A dead key (accent composition such as `^`, `¨`, `~`) enters Edit Mode on an empty cell so the following letter composes into it (e.g. `^` then `e` yields `ê`).

### Active Edit Mode Behavior
While in Edit Mode, *Selected Cells List* resets with only the *Main Selection* and two contextual menus remain visible until editing concludes:

**Menu 1: Mode Selector ("Change Task" vs. "Rename")**
* **Change Task (Default):** A task menu displays existing `taskIds` matching the cell's current text. The menu doesn't appear if there is only one element. There is always one of the elements that is selected. 
  * *Presentation:* The first `taskId` is represented by "New task". The other `taskIds` are represented by their shortest path in the Task Tree (or a list of child titles if no cells point to it).
  * *Filtering:* Impossible IDs (already in the same list, or in the cell's ancestor path) are hidden.
  * *Creation:* Typing dynamically creates a *new* `taskId` and the cell gets assigned to it. If another element than the first one (labelled "New task") is selected in the task menu, then selecting the first one creates a new `taskId`.
  * *Default selection:* When entering Edit Mode, the default selection is the current task of the cell. This means that typing automatically selects the first element (labelled "New task").
  * *Sorting:* Shortest to longest path length -> alphabetical by path -> alphabetical by child titles.
  * *Cleanup:* If a task with no children loses all cell pointers and has no task record (section 8), it is purged from the Task Tree.
* **Rename:** Modifying the text updates the title field in the global `Tree`. *All* cells sharing this `taskId` will temporarily reflect the new text. If the mode is switched back or canceled, other cells revert to their original state.

**Menu 2: Title Suggestions**
* Displays a list of existing task titles similar to the current input (except the exact same title).
* *Sorting:* String similarity -> alphabetical -> number of `taskIds` sharing the title -> total occurrence count.
* *Action:* Selecting a suggestion updates the cell text, but keeps the cell in Edit Mode with menus active.

### Editor Interactions & UI
* **Auto-Save:** Every keystroke/change is immediately committed to the state.
* **Sub-list Syncing:** Edits to a sub-list immediately update all expanded instances of that `taskId` across the UI.
* **Line Breaks:** `Ctrl + Enter` adds a new line. The cell dynamically expands horizontally and vertically to fit the content. 
* **Up and Down:** `Up` goes to the beginning if there is no line above in the cell, and `Down` to the end if there is no line below in the cell.
* **Cancel:** Pressing `Escape` inside Edit Mode cancels the session, reverting all affected cells to their pre edit mode text.
* **Forced Exit:** Changing global selection via mouse click outside the cell forcibly exits Edit Mode.
* **Auto-Expansion:** When the bottom cell of a list receives text, the system automatically:
  1. Initializes a hidden sub-list for it (containing one empty cell).
  2. Appends a new empty placeholder cell directly below it at the current hierarchical level.

### Exiting Edit Mode (Keyboard Navigation)
* `Enter`: moves *Main Selection* down one cell.
* `Shift + Enter`: moves *Main Selection* up one cell.
* `Tab`: If the current cell is populated, opens its sub-list and moves *Main Selection* to its first child. Otherwise, behaves identically to `Enter`.
* `Shift + Tab`: Behaves identically to `Shift + Enter`.
* `Escape`: Cancels the session, reverting all affected cells to their pre edit mode text (see **Cancel**).

### Empty cells management
* **Cleanup:** Empty cells are automatically removed upon exit, *unless* it is the absolute bottom cell of a sub-list.

### Edition without Edition Mode
* **Deletion:** When the user presses `Backspace` or `Delete` and no cell is in Edition Mode, all selected cells get emptied.
* **Copy/paste:** If the selection is consecutive cells, then Ctrl + C copy the selection. It convert the tree structure of the selection and everything under the selected cells into a text. The copied text also contains the priority weight table values, and at the end the minimum time of each task present in the task tree. Ctrl + V with a single cell selected allows to paste the copied tree structure only if the text has the right format. In Edit Mode, Ctrl + C and Ctrl + V have the usual behavior.

## 5. Priority assignment
* **priority percentage:** The priority percentage displayed at the right side of a cell is the absolute priority percentage of the `taskId`. It is the sum of the absolute priority percentage of all the cells sharing this `taskId`. The priority percentage of a cell in a list is the priority weight of the cell divided by the sum of the priority weights of this sub-list. Its absolute priority percentage is this fraction multiplied by the absolute priority of the parent cell. If the parent cell is the "root" cell, then its absolute priority percentage is 100%.
* **priority weight table:** 
  * When the user clicks on the absolute priority percentage, it toggles the display of the priority weight table. This table is placed at the right of the percentage, and has its rows aligned to the cells of each cells of the sub-list. It is a table of input fields that only accepts numbers and comma and has increment and decrement buttons to add or remove 1 to the number (0.1 for the header row). The two buttons are placed one above the other in a vertical column at the right side of the number. The header row shows the weight of each column. The weight of each column can only span from 0 to 1 included, the others from 0 to infinity.
  * The priority weight of each cell is calculated by dividing the cell's value by the column's total sum, and then multiplying that result by the column's absolute weight. The absolute weight of the n-th column is its nominal header weight multiplied by a factor, A, where A is one minus the sum of the absolute weights of all preceding columns. This means that reordering columns changes values dynamically.
  * Right above the header row, handles allow to grab the whole column and drag it to place it somewhere else in the table when the click is released. While the user drags, the column gets a grey background and a vertical blue line appears between two consecutive columns to indicate where the column will be placed when the user releases the click. Right-clicking the handle shows a contextual menu with three options: "Add column to the right", "Reset to default" and "Delete column". By default, there is one column with every field set to 1. Each added column has by default every field set to 0. If there is only one column the "Delete Column" option doesn't appear.
  * Selecting a cell outside of the sub-list, or any cell entering Edit Mode makes the table disappear.
* **priority weight:** If the priority weight field is displayed for the selected cell and the user types something, the cell doesn't enter Edit Mode (to avoid conflict with the priority weight typing). The priority weight can be 0.

## 6. State Management & Undo/Redo Engine

* **Data Model (MVI State):**
  * **Cell Model (UI State):** Encapsulates `taskId`, a parent pointer (for ancestor validation), and an optional `sub-list`. If expanded, the `sub-list` populates from the `Map` Task Tree. A cell object only has final fields, and update with Task Tree. If the cell is empty, `taskId` is null and `sub-list` empty. When a cell is created, its `sub-list` field is null and gets populated only when the user manually expands it.
  * **Task Tree (`Map`):** Associates a `taskId` to a domain object containing: Title, absolute priority percentage (see section 5), period ranges of tasks done and scheduled (see section 9), minimum time for the task (see section 10), a schedule unit (section 13), list of child `taskIds`, and an occurrence list of cells utilizing this `taskId` (sorted by shortest path).
  * **TitleToTask Tree (`Map`):** Associates a string title to a list of `taskIds` sharing that exact title.
* **Initialization:**
  * *Empty DB:* Task Tree initializes with a "root" key pointing to a "main" task (representing the user's active daily life).
  * *Existing DB:* Loads from local persistence. If the last history unit is an incomplete "Edit Mode" state, it evaluates as a canceled edit (`Escape` behavior).
* **History Architecture (Deltas):**
  * Every mutation generates a History Unit containing: Exact Timestamp, Chrono-ID (for deterministic sorting of simultaneous events), and a **Delta**. There are four categories of History Units: change in Edition Mode, change in the calendar (manual and dynamic), change of selection state and the rest. Each category has a list of History Units and a history pointer. The oldest History Units are removed when the length of the list is over 1000.
  * **Delta Storage:** Stores *only* the minimum data required to revert the Task Tree and Selection State. (e.g., keystrokes in Edit Mode generate a Delta with cell coordinates, previous string, and current edit mode). There are several types: change in a list of child `taskIds` in Task Tree, selection change, expansion change, task title change, priority weight column moved, priority weight value changed, manual calendar record edition...
* **Undo / Redo:**
  * **Undo:** The history pointer decrements. The Delta is applied to the state, and the Delta *inside the unit* is mathematically inverted to represent the forward-change (Redo). For selection history, undo is `Alt + Left` and redo is `Alt + Right`. For the other history categories, undo is `Ctrl + Z` and redo is `Ctrl + Y`.
  * **Branching:** If an Undo is performed followed by a *new* mutation, all forward (Redo) history units are orphaned/discarded.
* **Persistence:** State changes (Tree, occurrences, parent objects, selection, and history) are continuously streamed to the local multi-platform database.

## 7. Lateral menu
* **Page Navigation:** The page navigation button that is persistent through all feature pages is the first element of the left side menu (the button is at the same place in all feature pages but not necessarily in a lateral menu).
* **Calendar:** A button allows to toggle the display of the calendar in a floating window over the tree (not over the left side menu).
* **Automatic Schedule Switch:** If the switch is set to off, the triggered events to update the schedule (section 9) wait for when the switch is set to on. On by default, and persists between sessions.
* **Chores Manager:** A button allows to toggle the display of the chores manager in a floating window over the tree (not over the left side menu).

## 8. Calendar
* **Current Week:** The calendar window shows the week with the same style as Google Calendar.
* **Day Selection:** When the calendar is displayed, the lateral menu shows the days in the current month and a way to select other day and navigate to other months. This mirrors what is found in Google Calendar.
* **Task panels:** The calendar shows the periods the user did a task, using the task record saved in `Task Tree`. The title of the `taskId` of the task panel is written on it, and shows on hover (useful if the task panel is too short to display the task title). Task record is not saved in the history state. Two task panels with the same task are automatically merged unless one is pinned and the other not pinned.
* **Manual add:** Right-click anywhere on the calendar outside of task panels opens a contextual menu with the option "add a task" that opens the calendar edit window with a "save" button that adds a task at the right-click position in the calendar. By default it is the task with the biggest absolute priority percentage, and first in the alphabetic order if there is a tie, and the spanning time is the minimum time of the task.
* **task contextual menu:** Right-clicking on a task in the calendar shows a contextual menu with two options: "Edit" and "Remove".
* **calendar edit window:** The "Edit" option opens a floating window over the calendar floating window and the task tree. This window has an input field that is similar to the cells in `Change Task` mode in the tree task: same Edit Mode with the task menu and title suggestion menu. Unlike in the task tree, "New task" is not selected by default but the first task of the task menu. Unlike in the task tree, the title suggestion menu and the task suggestion menu only suggest tasks that don't have child tasks. Creating a new task in the calendar doesn't create one in the task tree. There are two input fields for the beginning time and the ending time. A button "pin" allows to pin this task panel in the calendar (on by default).
* **Manual drags:** The user can drag a task in the calendar by clicking on it and dragging while still holding the click. When dragging the task over other task periods, there must not be overlaps, so as the mouse come over consecutive tasks, the dragged task stay stick to the end of this group, and as soon as the mouse gets closer to the start of the group than its end, the dragged task appears before the group. When it appears in gaps that are shorter than the dragged task, the dragged task shorten to fit, but remembers its original side for when it appears in wider gaps. The calendar is considered to have changed and the move is saved in a history unit only when the user releases the click.
* **extend or shorten:** The user can also grab the beginning or the end of the task to extend or shorten it. When it is going to overlaps with other tasks, it can't be dragged any further. The mouse gets the standard shape on hover that indicates the user can grab the edge.
* **Overlap Mode:** When the user moves a task panel or drags its ends, pressing `O` toggles the overlap mode where the task panel can overlap with others. When a task overlaps with others, they share the time period horizontally. By default, the task panel that is dragged occupies 1/n of the width, with n the number of task panels in the overlap. Only the newly-dropped panel takes 1/n; the existing panels split the remaining (n-1)/n in their current proportions. Preserves prior manual adjustments. The user can then drag the vertical edges in the overlap to adjust the width occupation of each task panel. When the task panel is partly in an overlapping area, only the overlapped part has the width reduction.
* **undo:** The user can undo and redo (Ctrl + Z and Ctrl + Y) the state of the calendar when the calendar is in focus. Every changes in the calendar is stored at the same place as the other history units. 
* **focus:** When the calendar is in focus, the task tree doesn't catch letter typing (the main selection doesn't enter Edit Mode).

## 9. Scheduler
* **Two Calculation Events:** 
  * Every time the calendar changes, with a 1 second debounce, the calculation event is placed 24 hours before the first moment free of task.
  * Every time the task tree changes, with a 1 second debounce
* **Scheduling:** From the first point in time there is no scheduled task to 24 hours from now, all the not pinned task panels are removed and replaced by a new calculated schedule. If 24 hours from now is in a task panel that extends further, then the extension is also removed unless the last task panel added by the new scheduling is the same task. Each scheduling is saved in a History Unit.
* **task choice:** When the app must find a task to add at a time t, it iterates over every task with no children from highest to lowest absolute priority percentage until the correct task is found. For a task i, t1 is the closest time in the past such as i has a spanning time equal to its minimum time m between t1 and t. The fraction f is this minimum time divided by the working time between t1 and t. If t1 doesn't exist, f is 0. The working time is the periods where at least one task is scheduled. If f is the absolute priority percentage i or lower, then i is the correct task. It is then scheduled in the calendar with the minimum time of the task (see section 10), or less to not overlap with a pinned task panel.

## 10. Minimum time for a task
* **Definition:** Every task has a minimum time defined. It is 45 minutes by default.
* **New Task:** The spanning time of the new task is the minimum time associated to the task, minus the accumulated spanning time of this same task recorded in the past if separated by less than 10 minutes between each session. If the result is negative, then the new task gets the minimum time associated to the task. The task panel must be reduced to avoid overlapping with pinned task panels.
* **Display:** The minimum time of the task is shown at the right side of the priority weight display. When the user clicks on it, it behaves similarly to the absolute priority display. It becomes an input field with increment and decrement arrows on its right side. When selecting other cells, it gets back to a simple display. When in input field mode, typing won't enter Edit Mode. When the priority weight table shows up, the minimum time is moved to give it space. It moves as well when the number of weight columns changes. 

## 11. Notifications
* **Task switches:** As soon as the current task to do changes, a notification is sent with a written message telling what is the new task

## 12. Device sleep
* **When The Device Wakes Up:** The app checks the time periods where the device was sleeping, which means the user wasn't doing the scheduled task during those periods. The task panel on the calendar then gets the holes corresponding to the sleeping periods.

## 13. Schedule Unit
* **Schedule:** A task can have an sequential list of objects containing a title and a spanning time.
* **Notification:** If the task has a schedule unit, then the notification that tells the user to switch to this task must also tell him the deadlines of each elements of the schedule unit.
* **Cell Contextual Menu:** If the task has no child task, right-clicking a cell in the task tree shows a contextual menu with the option "define schedule unit".
* **Edition Window:** "define schedule unit" opens a floating edition window showing the list vertically. The titles are input fields and the spanning time are also input fields but with increment and decrement buttons. At the right of a pair, a bin button (to remove the pair) and a plus button (to insert a pair above). At the end of the list, a single plus button. At the bottom right, a Cancel and a Save buttons. If the sum of the spanning times is higher than the minimum time of the task, the Save button is not clickable.

## 14. Chores Manager
* **Window:** The window is a vertical list of elements containing a title, a spanning time and a time in the day. The three elements of a row are input fields, and the spanning time has days as a unit (a floating point number superior to 1).
* **Task Panels:** The calendar updates each time the chores manager window changes. Every chore is set as a 5 minutes panel on the calendar at the defined time of the day every n days, with n also defined in the chores manager window. If n is a floating point number, the number is accumulated at each iteration, and for every iteration the closest integer indicates the chosen day. If there were no iterations before or too far in the past, the accumulated counter starts from today. If several chores are overlapping, they share the width evenly. The chore panels behave like pinned task panels in relation to the task scheduler. Chore panels have the same pin system in relation to the chore scheduler (not removed when it updates the chores).