# Task Scheduler Module - Product Requirements

**Version:** 0.1.0

## 1. Overview

The Task Scheduler is a heavily interactive, infinitely nestable list of cells. It borrows interaction paradigms from spreadsheet applications (like Google Sheets) while applying them to a hierarchical tree structure. One task can be found in several cells of the tree. E.g.: to find a job I need to practice English, and to socialize abroad I need to practice English. When introducing priority operations in v1.0.0, practice English will get its priority as the sum of the two absolute percentages in time allocation. Two cells with the same task id have the same sub-tree. A task id can't appear several times in the same list, because it would mean two different priority values for the same task for the same goal (the parent task). The task id of a cell can't be the task id of one of the parents of its cell (to avoid infinite cycles). Several task id can have the same title (e.g., "watch Youtube" as a child of "learn to cook" and "watch Youtube" as a child of "learn Spanish").

## 2. Structural UI & Aesthetics

- **Infinite Tree View:** Cells are displayed in a vertical list. The displayed tree is always in sync with the `Map` Tree. 
- **Nesting UI:** The left side of the cells features arrows and guide-lines illustrating the parent-child hierarchy. The root cell is an execption: it conceptually exists, but the visibility window only shows its children.
- **Collapsibility:** Clicking the structural arrows toggles the visibility of a cell's sublist.
- **Visual Aesthetics:** The cell aesthetics strictly mirror Google Sheets. This includes the visual styling for normal resting states, active selection borders/highlighting, and the inline appearance when a cell is in edit mode.

## 3. Selection Mechanics (Spreadsheet Paradigm)

The application maintains a dual-state selection model: **Main Selection** (a single active cell or null) and a **Selected Cells List** (a collection of active cells). Visual indication matches standard spreadsheet software.
_Hidden/collapsed cells are ignored during vertical list traversal for selection._

- **Single Click:** The clicked cell becomes the _Main Selection_. The _Selected Cells List_ is cleared.
- **Click & Drag:** * The initially clicked cell becomes the *Main Selection\*.
  - As the mouse moves, the _Selected Cells List_ encompasses all visible cells from the Main Selection to the cell currently closest to the cursor.
  - Dynamically updates until the mouse button is released.
- **Ctrl + Click:** The clicked cell becomes the _Main Selection_ (useful for isolated multi-selection, though standard `Ctrl` behavior applies).
- **Shift + Click / Ctrl + Shift + Click:** The _Selected Cells List_ is expanded to include all visible cells sequentially from the current _Main Selection_ to the newly clicked cell.
- **Root Cell** The root can't be selected or edited. It behaves for the mouse like it is not a cell.

## 4. Editing Mechanics

- **How to enter Edit Mode:** 
  - Double-clicking a cell enters Edit Mode.
  - If a cell is the _Main Selection_ (but not in edit mode) and the user begins typing, it immediately enters Edit Mode and captures the input.
  - The "main" cell can't enter in Edit Mode.
- **In Edit Mode**
  - Two contextual menus appear until the end of the Edit Mode.
    1. The first menu has two options: "change task" and "rename". There is always one of them currently selected, "change task" is selected by default.
      - When "change task" is selected, another contextual menu appears with a list of task ids that have the text content of the cell as a title. Each task id is represented in the list by the shortest path in `Task Tree`, or the list of children task titles if there is no cells associated to this task id. If the user clicks on a task id, the cell gets this task id. Impossible ids are not suggested (if the task id is already found in the same list, or in the path of the cell). There is always one of them selected, and the cell gets the task id selected. By default. When "change task" is selected, each text change creates a new task id. This new task id is in the first place in the third contextual menu. The other task ids are sorted from shortest to longest shortest path, then in alphabetical order on the list of paths for each task id (sorted from shortest to longest path for each task id), then on the list of children task titles. When a task in Task Tree with no children task looses all its cell pointers, it is removed from Task Tree.
      - When "rename", all cells sharing this task id get the same new text content. In the model, it simply changes a title field in `Tree`. When "rename" is not selected anymore, those other cells get back their original text content.
    2. The second menu is a list of task titles already existing sorted from more to less close to the text content of the cell, then sorted alphabetically, then by the number of task id sharing this title, then by occurrence count sum over all task ids. When the user select one of them, the text content updates, but the contextual menus don't disappear and the cell doesn't exit the Edit Mode.
  - Any change made in edit mode while typing is immediately saved.
  - Changes made in a sublist of a task id updates the sublist of every cell that shares this task id and reveals its sublist.
  - `Ctrl` + `Enter` adds a new line in the cell.
  - `Delete` in Edit Mode cancels the edit, all the cells get back the last text content they had before entering this Rename Mode.
  - Changing the global selection state forcibly exits Edit Mode for any active cell.
  - The cell extends horizontally and vertically to make the whole text content visible.
- **Exiting Edit Mode:** 
  - The following shortcuts exit Edit Mode:
    - `Enter` makes the cell below in the same list the main selection.
    - `Shift` + `Enter` makes the cell above the main selection if there is one above.
    - `Tab` also displays the sub-list of the cell and makes the first cell in it the main selection.
    - `Shift` + `Tab` does the same as `Shift` + `Enter`.
  - Upon exiting Edit Mode, the system evaluates the tree.
    - Empty cells are automatically removed, _unless_ it is the absolute bottom cell of a list.
    - When the bottom cell of a list becomes populated, the system automatically:
      - Initializes a hidden sublist for that cell. The sublist only has one empty cell.

      - Appends a new empty placeholder cell directly below it at the same hierarchical level.

## 5. State Management & Undo/Redo Engine

- **Data Model:**
  - The Cell data model encapsulates a task id, the parent cell and a sublist. The pointer to the parent allows to check the path (the parents). If the sublist of the cell is hidden, the sublist field is empty. Otherwise, it gets populated from the `Map` Task Tree by cells with the right task id in the right order.
  - The `Map` Task Tree associates a task id to an object containing the title of the task, the sublist of task ids, and the list of cells that has this task id sorted from shortest to longest path.
    - At startup, if the local database is empty, Task Tree gets the "root" task as a key and a list containing the "main" task as the value. "main" represents the sub-tree of tasks that the user is actually executing in his/her daily life.
    - If the database is not empty, if the pointed history unit is labeled Edit Mode, then the result is the same as pressing `Delete`.
  - The `Map` TitleToTask Tree associates a title to a list of the task ids sharing this title.
- **History Architecture:** \* Every modification generates a History Unit.
  - **Unit properties:** Exact timestamp, a "chrono-id" (for sequential deterministic sorting of simultaneous timestamps), and a **Delta** (the change).
  - **Delta Storage:** The history unit stores only the _minimum required data_ to revert from the new state (Task Tree and selection state) back to the previous state. For example, typing a character in Edit Mode generates a Delta containing only the cell coordinates, the previous string and the mode. The mode is important for example when the user presses `Delete` in Edit Mode, and the history pointer must go to the last history unit labeled with Edit Mode.
- **Undo (Ctrl + Z) / Redo (Ctrl + Y):**
  - On Undo: The history pointer decrements, the Delta is applied, and the Delta _inside the unit_ is flipped to represent the forward-change (Redo).
  - History branching: If the user performs an Undo and then makes a _new_ change, all forward history units are discarded and replaced by the new unit.
- **Persistence:** The Tree, Occurrences and Parents objects and the history and selection states are immediately and continuously synced to the local multiplatform database.
