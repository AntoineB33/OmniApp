# Scripts reference (`scripts/`)

Operational reference for the `.bat` entry points. `CLAUDE.md` carries only the one-line index.

## State dirs

The desktop DB is a SQLite file (`scheduler-state.db`; one `app_state` row + per-unit `history_unit` rows)
under a per-run **state dir**, so accounts don't share data:

| Account | State dir |
| --- | --- |
| 1 | `~/.omniapp-acc1` |
| 2 | `~/.omniapp-acc2` |
| 3 (auto-start release) | `~/.omniapp-release` |
| no override | `~/.omniapp` |

Override with `-Pomniapp.stateDir` / `OMNIAPP_STATE_DIR`.

## Credentials and one-time setup

Credentials live in `scripts/accounts.env` (gitignored; copy `accounts.env.example`).

Create the accounts with `python scripts/internal/account_db_admin.py signup <user> <pass>`. Usernames map to
`<user>@omniapp.local`, so the Supabase project must have:

- **email confirmation disabled** — also what lets a guest account be claimed in place, since
  `PUT /auth/v1/user` would otherwise park the address as a pending change;
- **anonymous sign-ins enabled** — else no guest account can be created and a non-script launch stays
  local-only.

Then apply the schema (`deploy-supabase.bat`, or paste the migration into the SQL Editor). Its `for all` RLS
policies already grant the own-row DELETE the empty scripts need; only add a narrower DELETE policy if you
replaced them.

Auto-login is wired in `shared/.../scheduler/sync/StartupLogin.kt` + `TaskSchedulerViewModel`. See the
`per-account-scripts` note.

**Emptying covers both local and remote**, because a local-only wipe just re-pulls the old cloud data on next
sync.

## Open / empty / deploy

### `account1-empty-and-open.bat`

Three steps in order:

1. **remote-logout** account 1 (`account_db_admin.py logout`, bumps the `account_logout` marker) so any *other*
   still-running app on the account signs itself out on its next reconcile and can't re-seed the data;
2. empty account 1 (local DB **and** the remote Supabase snapshot/presence rows) — `empty` deliberately leaves
   `account_logout` intact so the running apps still see it;
3. launch logged in as account 1.

### `account2-open.bat`

Launch logged in as account 2 (data preserved).

### `account2-empty.bat`

Empty account 2 (local + remote); does not relaunch.

### `account3-deploy-windows.bat`

Build the release app image, install it outside the tree, register it to auto-start at Windows login, and
auto-sign-in as account 3. Release DB `~/.omniapp-release`, left untouched by updates.

### `account3-deploy-android.bat`

Build/sign/install the release APK and launch it auto-signed-in as account 3. `BootReceiver` keeps the scheduler
running across reboots.

### `account1-deploy-android.bat` / `account2-deploy-android.bat`

Build a **debug** APK, uninstall + reinstall it over adb to wipe local data, and launch it auto-signed-in as
account 1 / 2. Shared body: `internal\deploy-android-debug.bat`.

Both share the one app install on a phone, so each deploy wipes local data and re-signs in — the Android analog
of the desktop scripts' separate per-account state dirs. Remote data is preserved.

**Why uninstall rather than `pm clear`:** MIUI/HyperOS (and some other OEM ROMs) deny `adb shell pm clear` for
third-party packages (`SecurityException: … CLEAR_APP_USER_DATA`). Uninstall wipes the data without that
permission and also sidesteps the release-signature clash.

**Uninstall only truly wipes because `android:allowBackup="false"`.** With backup on, OS auto-restore silently
resurrected the previous install's DB at reinstall (old device id, stale rotated-out tokens, pre-empty
`device_active_session` rows), which both rendered a phantom activity gap in the Inactivity bands and re-seeded
the just-emptied server. **Do not turn it back on.**

## Fast-break variants

Background on coupled vs. decoupled poses and the debug knobs: ADR 0003.

### `account2-open-fast-break.bat [durationS] [intervalS]`

Like `account2-open.bat` but at **real** time (`omniapp.timeSim=false`) with the **5-min screen break** shrunk for
on-device pause-cue testing. First arg is the break's LENGTH, second is how long after the previous ≥5-min pause
it comes due (both default **5 seconds**).

Forwards them as `-Pomniapp.breakDurationMs` / `-Pomniapp.breakIntervalMs`. Only the 5-min pose is retimed — the
15-min pose and 20-20-20 look-away keep production timings — so a real device can reach a 5-min break in seconds,
sleep, and hear the phone cue `durationS` later.

The break LENGTH no longer rides the client's `device_break` row, so the script also upserts
`break_config.length_ms` for `5min_break` via `account_db_admin.py break-length` (a plain authenticated PostgREST
upsert; **no Supabase redeploy**).

Run `account2-empty.bat` first for a clean pose anchor.

**Android analog:** set `OMNIAPP_BREAK_DURATION_MS` / `OMNIAPP_BREAK_INTERVAL_MS` before
`account{1,2}-deploy-android.bat`. **The phone REMEMBERS them for the install** (`AndroidDebugFlagStore`,
SharedPreferences, debug builds only, restored at the head of `SchedulerHolder.ensure` so the Activity-less entry
points get them too).

> Without that, a **reboot** restarted the engine through `BootReceiver` → `SchedulerService` with no launch
> extras, so the phone recomputed the 5-min break at PRODUCTION timings and published `lastRest + 1 h` dues while
> the account's server-side `break_config.length_ms` was still the shrunk one — the two halves of the same break
> disagreeing.

A deploy launch is authoritative and CLEARS the remembered values when it passes none, so a plain
`account{1,2}-deploy-android.bat` is back to production timings.

### `account1-empty-open-and-deploy-android-fast-break.bat`

Chains `account1-empty-and-open.bat` then `account1-deploy-android.bat` with the 5-min break retimed via
`OMNIAPP_BREAK_DURATION_MS=5000` / `OMNIAPP_BREAK_INTERVAL_MS=5000` /
`OMNIAPP_BREAK_PAUSE_THRESHOLD_MS=7200000`, and also sets `break_config.length_ms` for `5min_break` server-side
(without that the pushed cue would still wait the default 5 minutes).

`account1-empty-and-deploy-android-fast-break.bat` is the same without opening the desktop app.

**The distinguishing knob is the pause threshold.** Unlike the account2 flavour, the drawn break (5 s) is
**decoupled** from the qualifying pause (2 h) via `ScreenBreak.pauseThresholdMillis` → `qualifyingPauseMillis`.
The rule this enforces: *the 5-min pose appears exactly `interval` (5 s) after each ≥2 h pause, and nowhere else.*
On a freshly-emptied account that is one break per day, 5 s after each night's wake, plus one at the now-line
today.

**Note:** account1's desktop launch defaults `omniapp.timeSim=true` (gradle `run`), so its now-line is
sim-accelerated — the phone (the pause-cue target) runs real time.

### `account1-empty-open-fast-break.bat`

The fast-break wrapper **without the phone step** (`account1-empty-and-open.bat` + the server-side
`break_config.length_ms` upsert), and the one script where **all three screen breaks are retimable,
independently**.

Nine editable `set` lines at the top of the file — duration / interval / pause threshold for the look-away, the
5-min pose and the 15-min pose — defaulting to production everywhere except the fast-break-shaped 5-min pose (5 s
long, due 5 s after a ≥2 h pause).

It upserts `break_config.length_ms` for **both** poses (the look-away is never pushed). Sim-accelerated like every
account1 desktop launch; for a real-time clock use `account2-open-fast-break.bat`.

## `deploy-supabase.bat`

Three steps, so nothing is copy-pasted into the Dashboard SQL Editor:

1. `supabase db push` applies `supabase/migrations/`;
2. `supabase functions deploy pause-cue` **and** `pause-cue-cron` (both halves of the cue delivery — the FCM/APNs
   secrets are project-wide, so they cover both);
3. if `SUPABASE_SERVICE_ROLE_KEY` is in `accounts.env`, `supabase db query --linked` applies
   `supabase/pause-cue-setup.sql` (pg_cron/pg_net + the `omni_*` Vault secrets + the cron job), injecting the
   secret key from `accounts.env` (never committed).

All idempotent; re-run after editing. Needs a one-time `supabase login` + `supabase link`.

### Three hard-won gotchas baked into it

1. **Every `supabase` invocation is `call supabase ...`.** An npm-installed `supabase.cmd` shadowing the exe
   otherwise swallows the rest of the `.bat` after the first invocation, silently skipping steps 2–3.
2. **The step-3 SQL is passed inline prefixed with a block comment** — the CLI misparses an argument that starts
   with `--` as a flag.
3. **`pause-cue-setup.sql` must contain no double-quote character at all, comments included.** The whole file is
   one native-command argument and PowerShell 5.1 mangles quoting around an embedded `"`, so the CLI receives a
   TRUNCATED query, runs only the leading statements and **still exits 0**.
   > Observed 2026-07-27: the cron job silently kept its old schedule while step 3 printed `[OK]`. `rows: []`
   > instead of the `cron.schedule` jobid is the tell, since `db query` returns the last statement's rows.
   > `apply-pause-cue-setup.ps1` now hard-fails on a `"` in the template.

Also note pg_cron's interval form is only `'[1-59] seconds'` — a one-minute `t_b` must be the cron form
`'* * * * *'`, not `'1 minute'` (which errors `invalid schedule`).

## `collect-diagnostics.bat [stateDir]`

Print the merged cross-device **diagnostics timeline** (default state dir `~/.omniapp-acc1`):

- the `[script]` markers `account1-empty-and-open.bat` stamps;
- the desktop app's lines (both appended to `<stateDir>\diagnostics.log`, which an account-empty never deletes);
- the Android debug app's lines (pulled via `adb shell run-as org.example.project cat files/diagnostics.log`;
  merged by `internal\merge_diagnostics.py`).

The apps write it through `scheduler/platform/Diagnostics.kt` (`Diagnostics.log`, REAL wall clock even under the
sim clock, local-only, 2 MB rotation). What lands there:

- engine start; every sign-in/sign-out/reconcile outcome (incl. remote force-logout);
- every derived-pause refresh (source server/local, window, resulting inactivity ranges);
- every active-session push;
- every posted notification and played voice cue (with the sim instant), **plus crossings swallowed as stale** —
  which per `BoundarySweep` can only mean the crossing's REAL age exceeded the 2-s budget, i.e. the process was
  suspended when it happened. That is the "device stayed silent through a break" evidence;
- the pause-end cue gate decisions;
- the debug time-link lifecycle (phone connect/drop, adopted speed changes, ≥10 s clock re-anchor jumps, leap
  flags/acks);
- the desktop sim-panel actions (speed chips, simulate-pause, reset);
- from `App.kt`, the exact Inactivity bands / carved-sleep holes the calendar renders (re-logged only when their
  interior shape changes, so the sliding window doesn't spam).

**Use this instead of asking the user to describe a calendar anomaly.**

## `update-supabase-cli.bat`

**The** way to update the Supabase CLI **tool** (not the DB); `-Force` reinstalls regardless of version.

It updates *whichever install PATH actually resolves to*, because updating the **shadowed** one is the whole
failure mode. It classifies the winner from `where.exe` order (filtered to `.exe` / `.cmd` / `.bat`, since npm also
drops an extension-less shim Windows ignores) and either:

- runs `npm install -g supabase@latest` (npm global, `%APPDATA%\npm`), or
- downloads the latest GitHub release zip into `%LOCALAPPDATA%\supabase\bin` (standalone).

It then re-resolves PATH and **exits 1** if the copy it updated is not the one `supabase` runs, lists every
candidate with which one wins, prunes user-PATH entries whose directory no longer exists, and refuses (exit 1) on
an install it doesn't manage (scoop/choco).

> **This machine has BOTH installs.** The npm global wins; the standalone `supabase.exe` still sits in
> `%LOCALAPPDATA%\supabase\bin` (a `rmdir /s /q` of it silently failed — cmd/PowerShell is ground truth here; Git
> Bash's `where` did not list it). Both happened to be 2.109.1, so the CLI's own "new version available" nag that
> survived an update was really the *npm* copy being stale while the script updated the standalone one.

Which installer you use is **machine-local** — the project only needs *some* `supabase` on PATH, already `login` +
`link`ed. But what it forces is not: `deploy-supabase.bat` must keep `call supabase ...` everywhere, and the
`db query` leading-`--` misparse stays.
