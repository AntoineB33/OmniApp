# Contributing to OmniApp

Thanks for your interest. This document covers what you need to know before opening a pull request: how to get set up, the test gates a change has to pass, and a handful of project-specific rules that are easy to violate by accident and expensive to discover later.

## Getting set up

Follow [Requirements](README.md#requirements) and [Installation](README.md#installation) in the README. For most work you need nothing beyond a JDK 17+ and the repository — the desktop target builds and runs with no Android SDK, no Supabase project and no network.

## Before you open a pull request

1. **Run the shared tests.** `./gradlew :shared:jvmTest` is the gate that matters and the one to run constantly while working. Before pushing, `./gradlew :shared:check` compiles every target.
2. **Add tests for behaviour, not just for code.** This project is test-driven by policy: state changes, selection rules, scheduler decisions and history mechanics are validated against the state holders *before* any UI exists for them. A pull request that changes behaviour without a test asserting the new behaviour will be asked for one.
3. **Describe the user-visible change** in the PR body, and link the specification section it implements or changes — [`docs/PRD_TaskScheduler.md`](docs/PRD_TaskScheduler.md) for anything on the Task Scheduler page, [`PRD.md`](PRD.md) for the application shell around it (page navigation, accounts, persistence, sync). If the behaviour you are adding contradicts the spec, update the spec in the same PR — the two are meant to agree.

## Project rules that are easy to get wrong

These are not style preferences. Each one exists because violating it caused a real bug that was hard to find.

### Persisted data must stay backward-compatible

Existing installations hold databases written by older builds. Any change to the persisted state model (`SchedulerState`, `SchedulerStateCodec`, the `Persisted*` types) or to the SQLite schema must come with a test that reproduces the **previous** on-disk shape and asserts it still loads correctly, or is migrated on load.

- Adding a field to a persisted type: give it a default, and extend a decode test with a payload that lacks it.
- Changing the SQLite schema: add the migration file *and* a test that builds the old shape and asserts the upgrade keeps the data.
- Old databases can hold states that current invariants forbid. Decoding must heal them rather than surfacing them as anomalies.

### Persist only what cannot be recomputed

Anything derivable from other persisted data is recomputed, never stored or synced. The task tree, user-authored calendar blocks, reminders, alarms and the sleep schedule are authoritative. The automatic schedule is derived — it must never count as a syncable change, or a device left running all day will write to the server continuously just from time passing.

Per-device view state (window positions, selection, display toggles) is persisted locally but never synced.

### Time passing must not re-plan the schedule

The scheduler runs when its *inputs* change — the tree, priorities, minimums, pinned blocks, the sleep schedule, the screen-break configuration. It does not run because the clock advanced. A re-plan triggered per tick will churn the user's whole plan while they are away from the machine. There are two deliberate, quantized exceptions, both documented in [`CLAUDE.md`](CLAUDE.md); if you think you need a third, that is worth discussing in an issue first.

### Notification and cue triggers must be exact

A break or alarm cue fires as a function of which boundary instants the clock crossed — each exactly once, in order — never as a function of how a periodic sweep happens to line up with the calendar. Sampling "is the current time inside this block?" on a heartbeat looks correct and silently drops events whenever the process is suspended or the clock jumps. Follow the existing `BoundarySweep` pattern.

### Display code must scale with the screen, not with history

Anything recomputed on every tick must be bounded by the visible window, never by the total size of the account's history. An O(history) recomputation in the render path pegs the UI thread on a large database and the window never appears — which looks exactly like the app failing to start, and which an empty test account will never reveal. Test against a large, realistic database.

### Say what has to be redeployed

There are two independent deployment surfaces, and a change to one does nothing until *that* one is redeployed:

- **Supabase** — `supabase/migrations/`, the Edge Functions, `pause-cue-setup.sql` → `scripts/deploy-supabase.bat`
- **The client apps** — any shared, desktop or Android Kotlin → an app rebuild

When you describe a change as done, name the surface it needs, or say "no deploy needed". A passing test suite means the code compiles and its logic is right; it does not mean the change is live on a device. The most common false bug report is a client-only fix that "doesn't work" because the old binary is still running.

## The scheduler

The scheduling model has a Python reference implementation in [`side-dev/`](side-dev/), and the Kotlin scheduler is a port of it. If you change scheduling behaviour, change the reference first, run `uv run tests_displayer.py --verify` to see the diff against the frozen expected answers, and keep the two implementations in step. The Kotlin tests replay the reference's own cases slot for slot.

## Reporting bugs

Open a [GitHub issue](https://github.com/AntoineB33/OmniApp/issues). For anything involving the calendar, the schedule, break cues or sync, run `scripts/collect-diagnostics.bat` and attach the output — it merges the desktop and phone logs into one timeline and turns most such reports from guesswork into a diagnosis.

## Code style

Kotlin official style (`kotlin.code.style=official`), which the IDE applies by default. Match the surrounding code: this codebase leans on precise, explanatory comments where a rule is non-obvious, and omits them where the code says it already.

## License

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE) that covers this project.
