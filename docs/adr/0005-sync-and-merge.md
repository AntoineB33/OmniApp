# ADR 0005 — Snapshot sync and the three-way merge

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *Sync*.

The `scheduler_snapshot` document syncs **automatically in both directions** (whole-document, versioned
by `revision`). A conflict is **merged**, falling back to last-write-wins only when it cannot be. The
manual Sync button is a force-now fallback only.

> This restored the automatic push/pull that 1.6.0 had briefly removed. The old "BUTTON-ONLY / no
> `ServerPushDebounce`" model is superseded — **do not reintroduce it.**

All snapshot paths funnel through the one mutex-guarded `SchedulerSyncEngine.reconcile()`.

## Local → remote auto-push (500 ms debounce)

A local edit persists to SQLite on the ~400 ms save debounce. When it actually moves
`SchedulerStateCodec.syncFingerprint`, the VM `markDirty()`s the engine **and** emits on a
`MutableSharedFlow` (`pushRequests`) that a collector consumes with a **500 ms `debounce`** →
`reconcile()`.

So a burst of edits coalesces into one push, and only the pending (dirty) change is sent. Derived/tick
reschedules leave the fingerprint unchanged and so never enqueue a push — an idle running session does
**not** push.

The collector is launched `UNDISPATCHED` so it subscribes before an early edit can be dropped by the
replay-0 flow.

## Remote → local auto-pull (Realtime `postgres_changes`)

While signed in, `RealtimeSnapshotSubscriber` holds a Supabase Realtime **`postgres_changes`**
subscription on this account's `scheduler_snapshot` row — a Phoenix WebSocket keyed on the signed-in
`userId` and authorized by the user JWT through the row's RLS. This is the **only** live WebSocket the
client holds.

A server-side change **pokes `reconcile()`** (which pulls when the remote revision advanced); the
subscriber never applies the event body itself. Sign-in points it at the account, sign-out clears it.

Needs the table in the `supabase_realtime` publication with `replica identity full` — migration
`20260722000000_realtime_scheduler_snapshot.sql`.

**Verified live 2026-07-28** against the real project (a row UPDATE reached a subscribed desktop and
poked the reconcile, ~8 s end to end).

### Streaming is not synchronization — every (re)subscribe must CATCH UP

Realtime delivers a change only to sockets connected **at that instant**, and `postgres_changes` has **no
cursor to resume from**: anything that lands while this device is disconnected is lost to it
**permanently**.

The socket drops routinely — OS sleep, network blips, the JWT-expiry rejoin, plain Phoenix drops (the
release log shows "incoming stream closed" repeatedly). So live streaming ALONE cannot keep devices
converged, and the gap it leaves is exactly the dangerous one: the device sits stale until the user's
next edit, whose own auto-push fetch then LWW-pulls over that edit.

`RealtimeSnapshotSubscriber` therefore reconciles on the `system` frame that confirms the subscription is
streaming — `RealtimePhoenix.isPostgresSubscriptionReady`
(`"message":"Subscribed to PostgreSQL","status":"ok"`, distinct from both the rejection frame and the
earlier `phx_reply` join ack). That turns each reconnect into a catch-up, bounding the stale window by the
reconnect instead of by the next user action. Firing on a connection's first subscribe too is harmless
(reconcile is mutex-guarded and no-ops when the revision already matches).

> This is the reason the **07:58 revision-63 write was never seen** by the desktop: the machine suspended
> moments later, which both lost the push's acknowledgement *and* killed the socket carrying its own
> change event.

Test: `RealtimePhoenixTest.postgres_subscription_ready_is_detected_but_not_the_error_or_the_join_reply`.

## Every launch checks the server once — the startup reconcile

`TaskSchedulerViewModel.init` calls `engine.reconcile()` in the `engine.isSignedIn` branch (the
restored-session one), so the app never opens on a stale revision.

It is **not** covered by the account-change path: `SchedulerSyncEngine._account` is seeded from the
persisted `sync_meta` **at engine construction**, so on a restart with a cached session the account never
*changes* — `watchAccountChanges` filters its first emission out (`info?.userId == loadedAccountId`) and,
without this call, **nothing at all reconciles at startup**.

> **Post-mortem (2026-07-28, account 3).** With no launch check, the first thing that reconciles is the
> user's **first authoritative edit** (via the auto-push debounce) — which fetches, finds the remote
> ahead, takes the LWW `pull` branch and **silently destroys that very edit**. Observed: an
> `account3-deploy-windows.bat` restart left the device on revision 62 while the remote held 63; the user
> deleted two task cells and the pull put them straight back.

The startup reconcile also **flushes** anything a previous session left unpushed (`dirty`), and is a no-op
when offline (`reconcile()` catches, logs and keeps local state). The other two entry paths (sign-in,
guest creation) already reconcile through `watchAccountChanges`, so nothing double-fires.

Test: `BidirectionalSyncTest.a_restored_session_checks_the_server_once_at_startup`. The "must NOT
reconcile" tests in that file assert a **delta** against the startup count, not an absolute 0.

## An account CHANGE reconciles once

Guest created, sign-in, sign-out, force-logout: the device must load what that account holds, so
`TaskSchedulerViewModel.watchAccountChanges` swaps the local partition, re-points the Realtime
subscription and calls `reconcile()` (which pulls, or seeds a brand-new account's empty remote).

Sign-in / `createAccount` themselves no longer poke the subscription — that would double-fire it.

## Echo prevention (layered — the anti-loop requirement)

1. The `revision` guard no-ops a device's own just-pushed change (the Realtime event for our write finds
   remote `revision == lastKnownRevision`, `dirty == false`).
2. A pulled snapshot is applied straight to `_state` via `applyRemoteSnapshot` (NOT through
   `dispatch` / `scheduleSave`) and resets `lastSyncedFingerprint`, so an incoming pull never enqueues a
   push-back.
3. The 500 ms debounce coalesces bursts.

Tests: `BidirectionalSyncTest` (VM wiring, deterministic virtual-time with a recording engine double —
`SchedulerSyncEngine` is `open` for this), `SchedulerSyncEngineTest` (reconcile push/pull/conflict),
`RealtimePhoenixTest` (the `postgres_changes` join frame + change detection).

## The three-way merge (`SnapshotMerge`, schema v10 / `9.sqm`)

When a reconcile finds `remote.revision > lastKnownRevision` **and** `dirty` is set, both sides changed
since they last agreed. Dropping either one's work is not acceptable now that the snapshot syncs
automatically both ways — two devices left open diverge on nothing more than a phone edit landing inside
the desktop's 500 ms push debounce.

### The common ancestor

The merge needs one — that is the whole reason `SyncMeta.baseSnapshot` / `account_sync.base_payload`
exists. With only the two current sides, "absent locally" cannot be told from "the peer added it".

It is written by **everything that advances the revision** (the first-device seed, a successful push, a
pull, the lost-ack adoption, the phantom-push skip), so the ancestor and the revision can never drift
apart. It is per ACCOUNT for exactly the reason the revision is.

### The rules

| Situation | Resolution |
| --- | --- |
| Added on one side | kept (both devices' new rows survive) |
| Deleted on one side, untouched on the other | deleted |
| Different fields of one task edited on each side | **both applied** (field-wise for `Task` / `Cell` / `CellList`) |
| Same field edited differently on both | **remote wins** — the value the account already agreed on |
| Deleted on one side, EDITED on the other | the **edit** wins |
| `Task.record` | **unioned** |
| `TaskPanel` / `ChoreEntry` / `AlarmEntry` | resolved as WHOLE objects |
| Id counters | **max** of both sides (ids are never reused) |
| Ordered id lists (`cellIds`, `childTaskIds`, `occurrences`) | membership by attribution, then the remote's order, each side's additions spliced after the neighbour they followed there |

Rationale for "same field ⇒ remote wins": that is exactly what the old whole-doc LWW did, so the merge can
only ever be a strict improvement over it.

Rationale for "edit beats delete": a deletion is one gesture to repeat; a lost edit is unrecoverable. For
tree cells it is weaker than it sounds — a cell resurrected in `cells` but absent from its list's
`cellIds` is unreachable and `repair` prunes it anyway.

Records are unioned because each device banks the periods its own now-line crossed. A `RemoveRecordPeriod`
still sticks, which is what the ancestor is for.

Panels resolve whole because a panel's start and end are one gesture — field-wise could invent an inverted
block.

Ordered lists splice rather than append so a row inserted mid-list does not jump to the end.

### The result is REPAIRED, not trusted

A per-entry merge can produce references neither side ever held (a list naming a cell the peer deleted).
`SnapshotMerge.repair` drops dangling ids and then runs the editor's own
`SchedulerDomain.pruneDetachedTree`, so a merged tree is always as well-formed as an edited one.

### The merge is applied LOCALLY AND PUSHED on top of the remote revision

The push is not optional bookkeeping — it is what makes the peer end up with the merge instead of keeping
its own half. Skipped when the merge turns out identical to the remote (the existing `localMatchesRemote`
phantom-push guard: a revision that says nothing is pure churn).

### NOT merged: the Undo/Redo history — the LOCAL stack is kept

A history unit carries whole before/after tree snapshots, so interleaving two devices' units would build a
timeline whose Ctrl+Z restores a tree that never existed and silently throws the merge away.

Keeping this device's stack is the only choice that leaves Ctrl+Z meaning what its user just did. (The old
pull adopted the *remote's* stack wholesale, which was strictly more surprising.)

Per-device view state is not merged either — it is neutralized in all three snapshots anyway.

### LWW survives ONLY as the fallback

For the two cases a merge cannot be attempted:

- **no ancestor on record** (a DB upgraded from v9, or an account this device has never completed a sync
  with), or
- a snapshot that will not decode.

Both log to `Diagnostics` and are self-healing — the pull they perform records an ancestor for next time.
**Do not treat the fallback as the policy.**

Tests: `SnapshotMergeTest` (every rule above),
`SchedulerSyncEngineTest.concurrent_edits_on_two_devices_are_merged_and_the_merge_is_pushed` /
`…_falls_back_to_the_last_write_wins_pull` /
`a_successful_push_and_a_pull_both_record_the_merge_base_they_agreed_on`,
`SchedulerStoreTest.upgrades_pre_merge_base_v9_db_…`.

## A device never pulls its OWN write back over its own newer edits

Migration `20260730000000`.

A push is not atomic from the client side: `RemoteSnapshotClient.update` PATCHes the row and only then
reads the response, so a dropped connection / an OS suspend mid-request leaves the server one revision
ahead while this device kept the old baseline with `dirty` still set. The next reconcile then read that as
"a peer wrote something newer" and took the LWW `pull` branch — silently applying **this device's own
older push** over everything edited since.

> **Observed 2026-07-28 on a single-device account.** Two task cells were deleted and reappeared seconds
> later. The pull also restores the remote's history units, so even the undo entry for the deletion
> vanished.

`scheduler_snapshot.writer_device_id` (stamped in the SAME statement as the payload, by `insert` /
`update`) is the discriminator. `SchedulerSyncEngine.isOwnUnacknowledgedPush` — **exactly one revision
ahead + still dirty + our `sync_meta.device_id`** — adopts the revision instead of pulling, then pushes the
current state on top.

Deliberately narrow: a peer's write at the same revision is a genuine conflict and LWW still applies, and a
**null** writer (row written by an older client, or a project without the migration) falls back to the
plain pull.

> **That fallback is not theoretical — it fired the day after the fix shipped.** The row this device had to
> judge (revision 63) was written at 07:58 by the PRE-fix binary, so its writer was NULL, all three other
> clauses matched, and the repair correctly could not engage. A revision stamped before the fix is
> permanently unprotected; the guarantee only starts at the first push a post-fix binary makes. This is
> also why the **startup reconcile** matters as an independent net.

A lost ack can only ever be one revision, because the retry PATCHes against the now-stale `revision=eq.N`
guard, matches no row, and takes the ordinary pull path.

Test: `SchedulerSyncEngineTest.a_push_whose_response_was_lost_is_adopted_not_pulled_back_over_newer_local_edits`
(+ the peer / unattributed counter-cases).

**The client `select`s `writer_device_id`, so the migration must be applied BEFORE (or with) the app
rebuild** — a new app against an unmigrated project gets a PostgREST 400 on every fetch.

Two supporting changes shipped with it: an `HttpTimeout` on the snapshot client (a response that never
arrives used to park the call — and with it the whole mutex-guarded reconcile — indefinitely; observed
~40 min across an OS sleep), and **every reconcile failure + every LWW drop is now logged** to
`Diagnostics` (previously a failed reconcile wrote *nothing*, which is what made this episode invisible in
`collect-diagnostics.bat`).

## Device activity history rides EVERY reconcile

`SchedulerSyncEngine.syncActiveSessions` sits at the tail of `reconcile()`, so active sessions travel on
*every* trigger of it (startup, account change, the 500 ms auto-push after an edit, a Realtime poke, a
(re)subscribe catch-up, the button) — never on a timer.

It pushes this device's own rows and pulls every peer's into the local `device_active_session` table;
skipped entirely on a remote force-logout. After every reconcile the engine re-derives (`syncMoments` →
`refreshDerivedPausesNow`), so pulled peer activity shows at once.

A device's active sessions are a physical fact (not reconstructible), so they are authoritative. What they
never get is a push of their own: peer activity is **reconcile-bounded**, and only two apps that are both
idle and never restarted stay stale (the button is the manual collapse for that case).

### What "active" means per platform

- **Phone:** the app is in the **foreground** (`AndroidForegroundTracker`, resumed-Activity count —
  `PowerManager.isInteractive` is gone). Each beat claims a one-minute lease `[now, now+1 min]`
  (`PHONE_SESSION_LEASE_MILLIS`, renewed every minute in prod), so a backgrounded/killed app reads inactive
  within a minute and activity may over-report by ≤1 min. That is the spec's granularity.
- **Desktop:** observed-only heartbeat extension.

Rows carry a `kind` column (schema v8, local `7.sqm`; remote migration `20260716000000`) so the calendar's
hover bubble on a past task panel names **which devices were open**, and a **dashed separator** splits the
panel where the device set changed (`deviceActivitySegments` — hoisted per frame into `DeviceActivityIndex`,
ADR 0009 — plus `deviceHoverZones` in `CalendarUi`, fed by `SchedulerEngine.activeSessions`). Tests: `DeviceActivitySegmentsTest`, `ActiveSessionSyncTest`.

## Retired: adopted remote-activity rows

The startup adoption recorded *presumed* peer activity (the old trailing-drop `derive_pauses` refused to
call the window after the last upload a pause) and fabricated activity over genuine pauses.

The server-side `closed` session flag made it unnecessary — an Inactivity band is now *inactivity unless a
device reported activity*, with only a fresh **open** session presumed active to the now-line. A session
**end** finalizes locally and rides the next sync moment.

`SchedulerEngine.purgeLegacyAdoptedRows` deletes what older builds wrote at startup (rows under the
reserved id `SchedulerEngine.REMOTE_ACTIVITY_DEVICE_ID`). The Sync-button session push excludes any
not-yet-purged rows anyway — it pushes only rows under this install's own device id, which also keeps the
signed-out-era `local` rows off the server.

## Remaining event-driven REST

- the reconcile (startup, account change, the debounced auto-push after an edit, a Realtime poke or
  (re)subscribe catch-up, the Sync button — **never a timer**);
- the Sleep/Work `account_state` write;
- the phone's push-token registration + last-phone claim (startup/foreground);
- the `publish_next_break` write when either break's due instant changes;
- the clean screen-off Edge-Function call;
- the login force-logout check inside a reconcile.

**Steady-state traffic** is the `t_a` `publish_presence` RPC while the device is actively unlocked — a DB
write, not an Edge invocation, and nothing at all while locked. The Edge-Function push (server→phone) is
the only server→device channel besides the snapshot WebSocket.

**Free-plan note:** the metered resource is **egress** (bytes the server sends back) plus the 500 K Edge
invocations — *not* the number of PostgREST/RPC calls, whose replies here are a bare scalar. Request-count
is the wrong axis to optimize.
