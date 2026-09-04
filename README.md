# OmniApp

**OmniApp is a multi-platform workspace made of feature pages**, switched between from a persistent menu in the top-left corner. Today it ships **one** page — the **Task Scheduler** — plus the shell the next ones will slot into: accounts, offline-first persistence, cross-device sync, undo/redo and a floating-window system, none of which are specific to scheduling.

**The Task Scheduler plans your day for you.** You tell it what you want to work on and how much each thing matters; it lays out a calendar that keeps every task at its intended share of your time, reminds you to switch, and makes you take eye-care breaks away from the screen.

The problem it solves: keeping a long list of competing commitments in proportion. Deciding "I should spend 50% on job hunting and 20% on Spanish" is easy; *actually* dividing your weeks that way is not — you drift toward whatever is loudest, and rebalancing by hand means re-planning every time something moves. The scheduler does the arithmetic continuously. It also refuses to let you sit at a screen for hours, which is the other half of the same problem.

It runs on Windows, macOS, Linux, Android, iOS and the web, and every device signed in to the same account stays in sync.

> **Status:** version 1.6.0, actively developed, desktop-first. The Windows desktop build is the reference target; Android is functional; iOS and web build but lag behind.

---

## Table of contents

- [What the Task Scheduler does](#what-the-task-scheduler-does)
- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)
- [Development](#development)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)
- [Contact](#contact)

---

## What the Task Scheduler does

Everything in this section belongs to the Task Scheduler page. Additional pages are planned and reach the app through the same top-left navigation menu; see [`PRD.md`](PRD.md) §6 for the roadmap.

**A hierarchical task tree.** An infinitely nestable list of cells with spreadsheet interaction — click, shift-click, drag to select, type to edit, `Ctrl+C`/`Ctrl+V` to copy whole sub-trees. One task can appear in several branches at once (learning English serves both "find a job" and "socialize abroad"), and the branches mirror each other.

**Priorities as percentages.** Every task carries a weight; the app turns the tree of weights into an absolute percentage of your time per task, shown next to each cell. A weight table with a pie chart opens when you click a percentage.

**An automatic schedule.** The scheduler fills your calendar so that, over time, each task receives its target share — using a proportional-share model that always serves the most starved task and gives it just enough to catch up with the runner-up. Two 50% tasks with a 45-minute minimum alternate every 45 minutes rather than every eight hours. It compensates for time you lost to fixed commitments, with the compensation bounded so one busy day doesn't distort the next month. It re-plans only when something it depends on actually changes, never merely because time passed.

**A calendar.** A week view in the style of Google Calendar showing what is scheduled and what you actually did. Drag panels to move them, grab an edge to resize, pin a block so the scheduler works around it, and mark periods as away-from-screen or inactive.

**Screen breaks for eye health.** Three recurring breaks — a 20-second look-away every 20 minutes, a 5-minute pose every hour, a 15-minute pose every two hours — placed on the calendar as real time, announced by notification and by voice. The app knows from your devices' activity whether you actually took a pose, so an untaken one stays owed instead of silently expiring — while the 20-second look-away, which costs you no working time, is simply assumed done as it comes up and stays on the calendar where it happened. If you walk away from every device with a break due, your phone tells you when the break is over, even with the app closed.

**Reminders.** Recurring check-off items at any cadence (including formulas like `31/21` and rates like "2.5 per week"), which stack on the current-time line when overdue and can be constrained to occur only on another reminder's days.

**Alarms.** Ordinary wall-clock alarms that ring on every device of the account, with per-day scheduling, a configurable ring length and vibration.

**A sleep schedule.** A nightly window the scheduler leaves empty, with a wind-down hour before bed — covered by a period of its own kind, "before bed", so a task you mark resilient to it can still run there — and a wake time that can drift gradually toward a goal.

**Full undo/redo and cross-device sync.** Every user change is a history unit you can walk with `Ctrl+Z`/`Ctrl+Y`, browsable in a History window. Data is offline-first: the local database is the source of truth and everything keeps working with no network. When two devices edit at once, their changes are *merged* rather than one overwriting the other. (These last are shell capabilities rather than scheduler ones — they will cover future pages too.)

---

## Requirements

To **build and run** OmniApp you need:

| | Requirement | Notes |
| --- | --- | --- |
| **All targets** | **JDK 17 or newer** (21 recommended) | Gradle 9.1 and AGP 9 both need 17+. Compiled bytecode targets Java 11. The Gradle wrapper (`./gradlew`) downloads Gradle itself — don't install it separately. |
| **Desktop** | Nothing further | Windows, macOS or Linux. |
| **Android** | Android SDK, **compile SDK 36**, min SDK 24 (Android 7.0) | Point `sdk.dir` at your SDK in `local.properties` (see below). `adb` on `PATH` for the deploy scripts. |
| **iOS** | macOS with Xcode | Open `iosApp/` in Xcode. |
| **Web** | A modern browser | Wasm target for current browsers, JS target for older ones. |

**No Supabase account is needed to run the app.** A public project is built in, and the app is offline-first — it opens and works with no network at all. You only need your own Supabase project if you want to run the sync server yourself or use the development account scripts; see [Cross-device sync](#cross-device-sync-optional).

### `local.properties`

This file is machine-local and not committed. Create it in the repository root:

```properties
# Required for the Android target only.
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk

# Required only to build a SIGNED release APK (account3-deploy-android.bat).
omniapp.releaseKeystore=C\:\\path\\to\\release.jks
omniapp.releaseStorePassword=...
omniapp.releaseKeyAlias=...
omniapp.releaseKeyPassword=...
```

Desktop-only development needs no `local.properties` at all.

---

## Installation

```bash
git clone https://github.com/AntoineB33/OmniApp.git
cd OmniApp
```

Then run whichever target you want. The first build downloads Gradle and the dependency graph and takes several minutes; later builds are incremental.

```bash
# Desktop — the primary target
./gradlew :desktopApp:run

# Desktop with hot reload, for UI work
./gradlew :desktopApp:hotRun --auto

# Android (debug APK in androidApp/build/outputs/apk/)
./gradlew :androidApp:assembleDebug

# Web
./gradlew :webApp:wasmJsBrowserDevelopmentRun   # Wasm — faster, modern browsers
./gradlew :webApp:jsBrowserDevelopmentRun       # JS — slower, older browsers
```

On Windows use `gradlew.bat` instead of `./gradlew`. For **iOS**, open the `iosApp` directory in Xcode and run from there.

To produce a **standalone desktop application** with a bundled JRE (no JDK needed to run it):

```bash
./gradlew :desktopApp:createDistributable
```

Or, on Windows, use `scripts/account3-deploy-windows.bat`, which additionally installs it outside the project tree and registers it to start at login.

---

## Usage

Launch the desktop app. It opens on the **Task Scheduler** page with an empty tree and a guest account already created — there is no sign-up step and no signed-out mode. The button in the **top-left corner** is the page selector; with one page shipped it currently has one destination.

### Build your task tree

1. **Click a cell and type.** Typing starts editing immediately; a new empty cell appears below and a sub-list opens beneath, so the tree grows as you fill it.
2. **`Tab`** moves into a cell's children, **`Enter`** moves down, **`Escape`** cancels the edit. Selection follows spreadsheet conventions: click and drag, `Shift+Click`, `Ctrl+A`.
3. While editing, two menus appear under the cell: **Change Task** reuses an existing task (so the same task can live in several branches and mirror itself), and **Rename** retitles it everywhere.
4. **Right-click a cell → "edit"** to set whether the task needs a screen, split it into timed sub-steps, and attach free-form notes.

### Set priorities and minimums

- **Click the percentage** at the right of a cell to open the weight table with its pie chart. Weights are relative within a sub-list; the percentage shown is the absolute share of your whole day. Columns can be added, reordered by dragging their handles, and reset.
- **Click the minimum time** next to it (default 45 minutes) to set the shortest block that task is ever scheduled in. This is what stops the plan from shredding your day into ten-minute fragments.

### Read and steer the calendar

- Open the **calendar** from the lateral menu on the left. It fills automatically from your tree — you never press "schedule".
- **Drag** a panel to move it, **grab an edge** to resize, **right-click** for a contextual menu (edit, remove, add a task, add a no-screen period, add an inactivity period, add a reminder).
- In a panel's edit window, the **pin switches** control what the scheduler may change when it re-plans: keep this occurrence, fix its position, fix its duration, fix its distance to another.
- **Zoom** with `Ctrl` + `+`/`-`/scroll, reset with `Ctrl+0`. The default zoom fits your waking day.
- Toggle **screen breaks** and **reminders** on or off in the calendar window — a purely cosmetic choice that never silences their notifications.
- `Ctrl+Z` / `Ctrl+Y` undo and redo within whichever window has focus; `Alt+←` / `Alt+→` walk selection history in the tree.

### Live in it

Once the tree is set up the app runs itself: it notifies you when it is time to switch tasks, when a screen break comes due and when to stop work before bed, and it records what you actually did. The **"Look away now"** button in the lateral menu takes a 20-second break on demand, and **"Switch task"** refuses whatever you are on right now so the schedule starts something else — without costing that task any of its share, so it comes back around as usual.

Four of the lateral menu's controls also answer to a **system-wide chord**, so they work while you are in another application — which is the only moment any of them is wanted: `Ctrl+Shift+Alt+E` takes the 20-second look-away, `Ctrl+Shift+Alt+Z` switches you off the current task, `Ctrl+Shift+Alt+A` toggles **"I'm away" / "I'm back"** as you leave the machine and come back, and `Ctrl+Shift+Alt+N` turns **notifications** off — silencing every one of them and clearing what is still on screen, while the History window keeps the record of what was said. (If you lock the machine on your way out, unlocking it on your return turns "I'm away" off for you — you are visibly back.) **"I'm away" does not silence anything**: it says only that nobody is at this screen, which is what the calendar's no-screen periods and the screen breaks are judged on, and you go on getting your notifications — the machine is still unlocked, and "task to do now" is exactly what you can still act on with a program left running. **Locking** it is what silences the device: there is nobody at a lock screen to tell, so the app says nothing there and your phone gets the end-of-break message from the server instead. Alarms still ring — that is what they are for. All are claimed exclusively where Windows allows it, so the application in front never sees the keystroke. The **Keyboard shortcuts** button at the bottom of the menu lists every shortcut in the app, and says which claim those chords actually got.

Other lateral-menu windows: **Reminders**, **Alarms**, **Sleep** (wake time, goal wake time, total sleep), **History** (every recorded change), and **All task trees** (named alternative arrangements of your tree; give them dates and the scheduler interpolates smoothly from one to the next). **All tasks** lists every task of the tree flat, sorted by how many occurrences it has or by its priority percentage, either way round.

### Cross-device sync (optional)

Sign in with the same account on another device and both stay current automatically — pushes are debounced, pulls arrive over a live subscription, and simultaneous edits on two devices are merged rather than one clobbering the other. Pressing **Sync** is only a force-now fallback.

Running the server half yourself requires your own Supabase project, the Supabase CLI (`supabase login` + `supabase link`), and `scripts/deploy-supabase.bat`. The project must have **email confirmation disabled** and **anonymous sign-ins enabled**. See [`docs/PAUSE_CUE_DELIVERY.md`](docs/PAUSE_CUE_DELIVERY.md) for the full runbook, including Firebase/APNs setup for the phone push cue.

---

## Development

### Tests

The shared business logic — state holders, MVI intents, the scheduler, the undo/redo engine — is tested headlessly, with no UI involved. Run these before touching Compose code:

```bash
./gradlew :shared:jvmTest                  # Desktop (JVM) — the fastest, run this one first
./gradlew :shared:testAndroidHostTest      # Android target
./gradlew :shared:wasmJsTest               # Wasm
./gradlew :shared:jsTest                   # JS
./gradlew :shared:iosSimulatorArm64Test    # iOS (Native), macOS only
./gradlew :shared:check                    # Compile + verify every target
```

### Project structure

- **`/shared/src`** — all the shared logic and UI.
  - `commonMain` — platform-agnostic domain, data, MVI state holders and Compose UI. Nearly everything lives here, both the application shell (page navigation, accounts, persistence, sync, history, floating windows) and the Task Scheduler page itself.
  - `androidMain` / `jvmMain` / `iosMain` / `wasmJsMain` — platform integrations (filesystem, notifications, audio, OS session tracking).
- **`/androidApp`, `/desktopApp`, `/webApp`, `/iosApp`** — thin per-platform entry points.
- **`/supabase`** — the server half of sync: SQL migrations (applied by the CLI, never pasted by hand), the two pause-cue Edge Functions (`pause-cue` for a device reporting its own clean lock, `pause-cue-cron` for the cron backstop when a device died without reporting), and `pause-cue-setup.sql` (the pg_cron job).
- **`/scripts`** — Windows development and deployment scripts (below).
- **`/side-dev`** — the Python reference implementation of the scheduling model, with its test cases and a GUI runner. The Kotlin scheduler is a port of it; `--verify` diffs the two.

Architecture principles worth knowing before changing anything: **MVI** with an immutable state object, **SQLDelight** for local persistence, and a strict rule that only data which cannot be recomputed is persisted and synced. Both are explained in [`ARCHITECTURE.md`](ARCHITECTURE.md) and [`CLAUDE.md`](CLAUDE.md).

### Development scripts (Windows)

> **Accounts:** the app is **always connected to an account**. Started normally it creates a **guest account** — a real account with no email/password, so only the device that created it can use it — and "Create account" turns *that* account into a normal one by giving it credentials, keeping all its data. Signing out lands on a fresh guest account; signing in to another account switches which data is shown and deletes nothing. The scripts below skip guest creation and open the app already signed in.

Each account uses its **own isolated state dir** (a separate local SQLite database) so accounts never share data — set by `-Pomniapp.stateDir` / `OMNIAPP_STATE_DIR`; with no override the default is `%USERPROFILE%\.omniapp`:

| Account | State dir | Used by |
| --- | --- | --- |
| Account 1 | `%USERPROFILE%\.omniapp-acc1` | `account1-empty-and-open.bat` |
| Account 2 | `%USERPROFILE%\.omniapp-acc2` | `account2-open.bat` / `account2-empty.bat` |
| Account 3 (release) | `%USERPROFILE%\.omniapp-release` | `account3-deploy-windows.bat` / `account3-deploy-android.bat` |

- **`account1-empty-and-open.bat`** — three things in order: signs **every** app on account 1 out server-side (so a still-running peer can't re-seed the data), empties account 1 (**local DB and the remote Supabase rows**), then launches signed in as account 1.
- **`account2-open.bat`** — launches signed in as account 2, **preserving** its data.
- **`account2-empty.bat`** — empties account 2 (local + remote); does **not** relaunch.
- **`account2-open-fast-break.bat [durationS] [intervalS]`** — account 2 at real time with the 5-minute screen break shrunk to seconds, for exercising the break and pause-cue paths without waiting an hour.
- **`account1-empty-open-fast-break.bat`** — the same idea with **all three** breaks independently retimable (nine editable `set` lines at the top of the file).
- **`account1-deploy-android.bat`** / **`account2-deploy-android.bat`** — build a **debug** APK, wipe the phone's local app data (uninstall + reinstall over adb, not `pm clear`, which some OEM ROMs deny), and launch it auto-signed-in. Remote data is preserved.
- **`account3-deploy-windows.bat`** — build a self-contained release app image, install it outside the project tree, register it to start at Windows login, and auto-sign-in as account 3 against the release DB (left untouched by updates). Time simulation is off in this build.
- **`account3-deploy-android.bat`** — build, sign, install and launch the release APK auto-signed-in as account 3 (a BootReceiver keeps the scheduler running across reboots).
- **`deploy-supabase.bat`** — apply `supabase/migrations/`, deploy **both** pause-cue Edge Functions, and run `supabase/pause-cue-setup.sql`. Idempotent; re-run after any schema edit.
- **`collect-diagnostics.bat [stateDir]`** — print the merged cross-device diagnostics timeline (script markers + desktop log + Android log pulled over adb). The first tool to reach for on a calendar or sync anomaly.
- **`update-supabase-cli.bat`** — update the Supabase CLI **tool** (never the database), whichever way it was installed; it detects what `PATH` actually resolves and fails loudly if it updated a shadowed copy.
- **`setup-piper.ps1`** — install the local Piper neural text-to-speech voice used for the spoken break cues; without it the app falls back to the system speech voice.

Credentials for the scripts live in **`scripts/accounts.env`** (gitignored — copy `scripts/accounts.env.example` and fill it in). Create the accounts once with `python scripts/internal/account_db_admin.py signup <user> <pass>`.

Helpers in `scripts/internal/` are **not run by hand**: `account_db_admin.py` (create / remote-logout / empty an account), `deploy-android-debug.bat`, `merge_diagnostics.py`, `kill-app-by-match.bat`, `load-accounts-env.bat` and `release-launch-acc3.bat`.

---

## Documentation

| File | What's in it |
| --- | --- |
| [`PRD.md`](PRD.md) | **Core** product requirements — the application framework itself: page navigation, platform strategy, accounts, sync model, and the roadmap of future pages. |
| [`docs/PRD_TaskScheduler.md`](docs/PRD_TaskScheduler.md) | The full behavioural specification of the **Task Scheduler page** — every interaction, in detail. The authority on what that page should do. |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | How it is built: state model, persistence, history engine, sync. |
| [`CLAUDE.md`](CLAUDE.md) | The active invariants — what you must not break. Read before changing the scheduler, the sync engine or anything persisted. |
| [`docs/adr/`](docs/adr/README.md) | Architecture decision records: *why* each invariant exists, what was tried first, and the post-mortems behind it. |
| [`CHANGELOG.md`](CHANGELOG.md) | Dated log of what changed when, including every Supabase and SQLite migration. |
| [`docs/SCRIPTS.md`](docs/SCRIPTS.md) | The `scripts/` entry points: state dirs, fast-break variants, deploy gotchas. |
| [`docs/PAUSE_CUE_DELIVERY.md`](docs/PAUSE_CUE_DELIVERY.md) | Runbook for the cross-device break-cue push chain. |
| [`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md) | Manual test procedures for what unit tests can't cover. |
| [`side-dev/README.md`](side-dev/README.md) | The scheduling model itself, with its reference implementation. |

---

## Contributing

Contributions are welcome. Please read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request — it covers the test gates every change has to pass, the persisted-data compatibility rule, and which parts of the system need redeploying for a change to take effect.

The short version: run `./gradlew :shared:jvmTest` before you push, and if you touched anything that gets written to disk or synced, add a test that loads the *previous* on-disk shape.

## License

Released under the [MIT License](LICENSE) — you may use, copy, modify and distribute this software, including commercially, provided the copyright notice and license text are kept. It comes with no warranty.

## Contact

Questions, bug reports and feature requests: **[GitHub Issues](https://github.com/AntoineB33/OmniApp/issues)**.

When reporting a scheduling or sync problem, please run `scripts/collect-diagnostics.bat` and attach the timeline — it makes almost every such report diagnosable.

---

_Built with [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html) and [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)._
