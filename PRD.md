# Product Requirements Document (Core)

**Product:** OmniApp  
**Version:** 1.5.0  
**Phase:** Core Framework & Initial Module (Task Scheduler)

## 1. Executive Summary
OmniApp is a unified, multi-platform workspace application. The initial release focuses on establishing the application framework, navigation scaffolding, and the first core module: a hierarchical Task Scheduler featuring advanced, spreadsheet-like interaction mechanics.

## 2. Platform Strategy
* **One Codebase:** Leverage Compose Multi-platform to write once and deploy everywhere.
* **Desktop-First Execution:** All interaction models (mouse events, keyboard modifiers, complex selections) must be perfectly executed on Windows Desktop before adapting the UX for Web, Android, and iOS.

## 3. Global UI/UX Requirements
* **Page Navigation:** The top left corner of the application must feature a persistent, accessible button triggering a drop-down menu. This menu serves as the primary routing mechanism to switch between different application pages (e.g., "Task Scheduler", and future modules).
* **Responsive Layout:** While optimized for desktop, the UI must gracefully scale to mobile viewports using Compose layout constraints.

## 4. Technical Constraints & Foundations
* **Database:** Must integrate a SQLDelight for local data persistence. Data must be saved locally and remain fully available offline.
* **Quality Assurance:** Behavior-Driven Development (BDD) / Test-Driven Development (TDD). No UI code is to be merged without corresponding ViewModel state tests passing.

## 5. Accounts, Persistence & Cross-Device Sync
* **The app is always connected to an account.** There is no signed-out mode. On its very first start — and again whenever the user logs out (or is logged out) — the app creates a **guest account** automatically and connects to it. When the UI has ever said "not connected", it means a guest account: the app is on one all the same.
* **A guest account works exactly like any other account.** Its data is stored and synced the same way, it takes part in presence/screen-break delivery the same way, it can be emptied the same way. The only difference is that it has **no email and no password**, so no *other* device can sign in to it — a guest account belongs to the device that created it (told apart from other guest accounts by that device's id).
* **Creating an account claims the guest account.** A button lets the user create an account by giving an email and a password. When the app is on a guest account, that guest account **becomes** the new account — it takes the email and password, keeping its id, its data and its devices. No second account is created and nothing is copied.
* **Signing in switches accounts; nothing is deleted.** Signing in to an existing account connects the device to it and shows that account's data. The account the device leaves keeps everything — its data on the server and its own local copy on this device — and gets it back untouched if it signs in again. Device-level facts (this device's identity and its recorded screen time) are the device's, not the account's, and are unaffected by a switch.
* **The launch scripts skip guest creation.** The per-account entry points in `scripts/` start the app already signed in to the account they name, so no guest account is created for them.
* **Offline-first:** the local database stays the source of truth and remains fully usable while the server is unreachable, including on a first launch that cannot yet create the guest account (that work is attached to the guest account as soon as one can be created).

## 6. Future Roadmap
* **v1.5.0:** Priority assignment (absolute percentage, relative percentage, hours per day).
* **v1.6.0+:** Automatically notifies the user when he/she need to switch to which task to satisfy the task priorities.
* **v2.0.0+:** Additional specialized pages accessible via the top-left navigation dropdown.
* **v3.0.0:** Cloud synchronization and real-time collaboration.

*See `docs/PRD_TaskScheduler.md` for specific requirements regarding the Task Scheduler page.*