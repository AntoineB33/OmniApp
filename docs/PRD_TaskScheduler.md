# Task Scheduler Module - Product Requirements

**Version:** 0.1.0

## 1. Overview

The Task Scheduler is a heavily interactive, infinitely nestable list of cells. It borrows interaction paradigms from spreadsheet applications (like Google Sheets) while applying them to a hierarchical tree structure. This version targets Windows desktop, but stays cross-platform as much as possible.

A single task can exist in multiple cells across the tree. For example: to "find a job," one must "practice English," and to "socialize abroad," one must also "practice English." Two cells sharing the same `taskId` will perfectly mirror the same sub-tree. 
* **Constraint 1:** A `taskId` cannot appear multiple times within the exact same list (this would create conflicting priority values for the same parent task).
* **Constraint 2:** A cell's `taskId` cannot match the `taskId` of any of its ancestor cells (to prevent infinite recursive cycles).
* **Constraint 3:** Multiple unique `taskIds` can share the same title string (e.g., "watch Youtube" under "learn to cook" is a different `taskId` than "watch Youtube" under "learn Spanish").

## 2. Structural UI & Aesthetics

* **Infinite Tree View:** Cells are displayed in a vertical list. The rendered UI tree is strictly synced with the underlying `Map` Tree. 
* **Nesting UI:** The left side of the cells features arrows (if there is a populated sub-tree) and guide-lines (if the cell is expanded) illustrating the parent-child hierarchy. The "root" cell conceptually exists, but the viewport only renders its children.
* **Collapsibility:** Clicking structural arrows toggles the visibility of a cell's sublist.
* **Visual Aesthetics:** Cell aesthetics strictly mirror Google Sheets. This includes standard resting states, active selection borders/highlighting, and inline editing UI.

## 3. Selection Mechanics (Spreadsheet Paradigm)

The application maintains a dual-state selection model: **Main Selection** (a single active cell or null) and a **Selected Cells List** (a collection of active cells). *Note: Hidden/collapsed cells are ignored during vertical traversal for selection calculations.*

* **Root and Main Cells:** The "root" and "main" cells cannot be selected, edited, or interacted with via mouse events. The "root" cell is only conceptual.
* **Single Click:** The *Selected Cells List* is reset and the clicked cell becomes the *Main Selection*.
* **Single Click & Drag:** * The initial click establishes the *Main Selection*.
  * While dragging without releasing the click, the *Selected Cells List* dynamically spans all visible cells from the Main Selection to the cell currently closest to the cursor.
* **Double Click & Drag:** * If the cell the user double-clicks on is selected and all the selected cells are sequential and in the same sublist, the whole selection is moved. Otherwise, the double-click empties the *Selected Cells List* then establishes the *Main Selection*.
  * While dragging without releasing the click, the cell gets blurred and a horizontal blue line between cells indicates where the selection will be placed if the user releases the click. If the changes happen in sub-trees that are mirrored elsewhere (because of sharing `taskIds`), then the change is synced only when the move is finished (the blue line and blur aren't mirrored).
* **Ctrl + Click:** The clicked cell becomes the *Main Selection* (standard OS `Ctrl` behavior applies for isolated multi-selection).
* **Shift + Click / Ctrl + Shift + Click:** The *Selected Cells List* expands to include all visible cells sequentially from the current *Main Selection* to the newly clicked cell.
* **Up / Left:** Resets selection, and if there is a selectable cell above the main selection, makes it the main selection. Doesn't work when in Editing Mode.
* **Down / Right:** Resets selection, and if there is a selectable cell below the main selection, makes it the main selection. Doesn't work when in Editing Mode.
* **Shift + Direction:** Pressing `Shift` while navigating with the direction keyboard keys allow to select a range of cells. `Right` is `Down` and `Left`. If several cells were selected before pressing `Shift`, it is reset. 
* **Enter or Tab:** If there are several selected cells, moves the main selection among them, from top to bottom then back on top. With `Shift` pressed, it moves backward.


## 4. Editing Mechanics

### Entering Edit Mode
* **Double-click:** Enters Edit Mode on the target cell.
* **Enter or Shift + Enter:** Enters Edit Mode on the target cell if there is only one selected cell.
* **Typing:** If a cell is the *Main Selection* (and not the "root" or "main" cell), typing immediately enters Edit Mode and captures the keystroke.

### Active Edit Mode Behavior
While in Edit Mode, *Selected Cells List* resets with only the *Main Selection* and two contextual menus remain visible until editing concludes:

**Menu 1: Mode Selector ("Change Task" vs. "Rename")**
* **Change Task (Default):** A task menu displays existing `taskIds` matching the cell's current text. The menu doesn't appear if there is only one element. There is always one of the elements that is selected. 
  * *Presentation:* The first `taskId` is represented by "New task". The other `taskIds` are represented by their shortest path in the Task Tree (or a list of child titles if no cells point to it).
  * *Filtering:* Impossible IDs (already in the same list, or in the cell's ancestor path) are hidden.
  * *Creation:* Typing dynamically creates a *new* `taskId` and the cell gets assigned to it. If another element than the first one (labelled "New task") is selected in the task menu, then selecting the first one creates a new `taskId`.
  * *Default selection:* When entering Edit Mode, the default selection is the current task of the cell. This means that typing automatically selects the first element (labelled "New task").
  * *Sorting:* Shortest to longest path length -> alphabetical by path -> alphabetical by child titles.
  * *Cleanup:* If a task with no children loses all cell pointers, it is purged from the Task Tree.
* **Rename:** Modifying the text updates the title field in the global `Tree`. *All* cells sharing this `taskId` will temporarily reflect the new text. If the mode is switched back or canceled, other cells revert to their original state.

**Menu 2: Title Suggestions**
* Displays a list of existing task titles similar to the current input (except the exact same title).
* *Sorting:* String similarity -> alphabetical -> number of `taskIds` sharing the title -> total occurrence count.
* *Action:* Selecting a suggestion updates the cell text, but keeps the cell in Edit Mode with menus active.

### Editor Interactions & UI
* **Auto-Save:** Every keystroke/change is immediately committed to the state.
* **Sublist Syncing:** Edits to a sublist immediately update all expanded instances of that `taskId` across the UI.
* **Line Breaks:** `Ctrl + Enter` adds a new line. The cell dynamically expands horizontally and vertically to fit the content. 
* **Up and Down:** `Up` goes to the beginning if there is no line above in the cell, and `Down` to the end if there is no line below in the cell.
* **Cancel:** Pressing `Delete` inside Edit Mode cancels the session, reverting all affected cells to their pre edit mode text.
* **Forced Exit:** Changing global selection via mouse click outside the cell forcibly exits Edit Mode.
* **Auto-Expansion:** When the bottom cell of a list receives text, the system automatically:
  1. Initializes a hidden sublist for it (containing one empty cell).
  2. Appends a new empty placeholder cell directly below it at the current hierarchical level.

### Exiting Edit Mode (Keyboard Navigation)
* `Enter`: moves *Main Selection* down one cell.
* `Shift + Enter`: moves *Main Selection* up one cell.
* `Tab`: If the current cell is populated, opens its sublist and moves *Main Selection* to its first child. Otherwise, behaves identically to `Enter`.
* `Shift + Tab`: Behaves identically to `Shift + Enter`.
* `Escape`: Simply exits Edit Mode.

### Empty cells management
* **Cleanup:** Empty cells are automatically removed upon exit, *unless* it is the absolute bottom cell of a sublist.

### Edition without Edition Mode
* **Deletion:** When the user presses `Return` or `Delete` and no cell is in Edition Mode, all selected cells get emptied.
* **Copy/paste:** Ctrl + C allows the user to copy the whole selection (if it is only consecutive cells in the same sublist) or only the main selection. Ctrl + V allows to paste it in a cell (if several cells were copied, new cells are added below). The user can copy/paste cells between Google Sheets and the app.


## 5. State Management & Undo/Redo Engine

* **Data Model (MVI State):**
  * **Cell Model (UI State):** Encapsulates `taskId`, a parent pointer (for ancestor validation), and an optional `sublist`. If expanded, the `sublist` populates from the `Map` Task Tree. A cell object only has final fields, and update with Task Tree. If the cell is empty, `taskId` is null and `sublist` empty. When a cell is created, its `sublist` field is null and gets populated only when the user manually expands it.
  * **Task Tree (`Map`):** Associates a `taskId` to a domain object containing: Title, list of child `taskIds`, and an occurrence list of cells utilizing this `taskId` (sorted by shortest path).
  * **TitleToTask Tree (`Map`):** Associates a string title to a list of `taskIds` sharing that exact title.
* **Initialization:**
  * *Empty DB:* Task Tree initializes with a "root" key pointing to a "main" task (representing the user's active daily life).
  * *Existing DB:* Loads from local persistence. If the last history unit is an incomplete "Edit Mode" state, it evaluates as a canceled edit (`Delete` behavior).
* **History Architecture (Deltas):**
  * Every mutation generates a History Unit containing: Exact Timestamp, Chrono-ID (for deterministic sorting of simultaneous events), and a **Delta**. There are three categories of History Units: change in Edition Mode, change of selection state and the rest. Each category has a list of History Units and a history pointer.
  * **Delta Storage:** Stores *only* the minimum data required to revert the Task Tree and Selection State. (e.g., keystrokes in Edit Mode generate a Delta with cell coordinates, previous string, and current edit mode). There are several types: change in a list of child `taskIds` in Task Tree, selection change, expantion change, task title change...
* **Undo / Redo:**
  * **Undo:** The history pointer decrements. The Delta is applied to the state, and the Delta *inside the unit* is mathematically inverted to represent the forward-change (Redo). For selection history, undo is `Alt + Left` and redo is `Alt + Right`. For the other history categories, undo is `Ctrl + C` and redo is `Ctrl + Y`.
  * **Branching:** If an Undo is performed followed by a *new* mutation, all forward (Redo) history units are orphaned/discarded.
* **Persistence:** State changes (Tree, occurrences, parent objects, selection, and history) are continuously streamed to the local multiplatform database.