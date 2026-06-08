# Product Requirements Document (Core)

**Product:** OmniApp  
**Version:** 1.3.0  
**Phase:** Core Framework & Initial Module (Task Scheduler)

## 1. Executive Summary
OmniApp is a unified, multi-platform workspace application. The initial release (v0.1.0) focuses on establishing the application framework, navigation scaffolding, and the first core module: a hierarchical Task Scheduler featuring advanced, spreadsheet-like interaction mechanics.

## 2. Platform Strategy
* **One Codebase:** Leverage Compose Multi-platform to write once and deploy everywhere.
* **Desktop-First Execution:** All interaction models (mouse events, keyboard modifiers, complex selections) must be perfectly executed on Windows Desktop before adapting the UX for Web, Android, and iOS.

## 3. Global UI/UX Requirements
* **Page Navigation:** The top left corner of the application must feature a persistent, accessible button triggering a drop-down menu. This menu serves as the primary routing mechanism to switch between different application pages (e.g., "Task Scheduler", and future modules).
* **Responsive Layout:** While optimized for desktop, the UI must gracefully scale to mobile viewports using Compose layout constraints.

## 4. Technical Constraints & Foundations
* **Database:** Must integrate a SQLDelight for local data persistence. Data must be saved locally and remain fully available offline.
* **Quality Assurance:** Behavior-Driven Development (BDD) / Test-Driven Development (TDD). No UI code is to be merged without corresponding ViewModel state tests passing.

## 5. Future Roadmap
* **v0.5.0:** Priority assignment (absolute percentage, relative percentage, hours per day).
* **v1.0.0+:** Automatically notifies the user when he/she need to switch to which task to satisfy the task priorities.
* **v2.0.0+:** Additional specialized pages accessible via the top-right navigation dropdown.
* **v3.0.0:** Cloud synchronization and real-time collaboration.

*See `docs/PRD_TaskScheduler.md` for specific requirements regarding the Task Scheduler page.*