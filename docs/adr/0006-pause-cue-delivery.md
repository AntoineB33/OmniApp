# ADR 0006 — Pause-cue delivery: presence, the `t_a`/`t_b` model, two Edge Functions

**Status:** active; live path partly unverified. **Runbook:** `docs/PAUSE_CUE_DELIVERY.md`.
**Invariant summary:** see `CLAUDE.md` → *Pause cue*.

The pause cue is delivered by a pg_cron tick over a `device_heartbeat` table. The external
Realtime-presence listener (Fly.io `/listener`) was **removed 2026-07-23** — the user's reasoning was that
the listener was a "false solution" (also heartbeat + poll), so no host warranted it.

## Presence: written on a `t_a` tick, polled every `t_b`

From the moment a device is **signed in and unlocked** it calls the `publish_presence` RPC every **`t_a`**,
upserting `{ user_id, device_id }` with a **server-stamped** `beat_at` (staleness never trusts the client
clock) and, in the same call, the account's `data_payload_sent` row back to `false`.

The FIRST beat goes out at login — the engine samples its activity beat on the signed-out→signed-in edge of
the sync-moment stream, rather than waiting up to one 30-s activity beat. Driven from the engine's
active-session beat via `DeviceHeartbeatPublisher.updatePresence()`.

**The presence row is `{ account, device, time of upsert }` and NOTHING else.** Since `20260726000000` the
device id is the whole request body; the break window and the device `kind` moved to the event-driven
`device_break` row, and `20260728000000` moved the claim flag out into its own account-keyed
`data_payload_sent` table (it was always an account-level fact — "has this idle episode already been
pushed?" — stored once per device).

**So nothing that changes with the schedule rides the tick.**

`t_a` is **server-owned** (`app_config.tick_seconds`, default 10 s, per account, changeable over HTTP): the
RPC **returns** it, so a change re-paces every device within one tick at no extra request.

There is **no** presence WebSocket and no `derive_pauses` RPC. Calendar "Inactivity" bands derive **locally**
over the stored active-sessions (own + reconcile-pulled peers).

## Two detections, two Edge Functions

Migration `20260729000000`. The account stops being present in exactly two ways, and each has its own
function; they differ only in what is *known* about the walk-away instant.

Delivery (token lookup + FCM v1 / APNs) is shared in `supabase/functions/_shared/push.ts`, and so is the
cue computation (`build_pause_cue(user, anchor)`), so the two can never disagree on which break governs,
its length or what is spoken — **only the anchor differs**.

**Both must be deployed** (`deploy-supabase.bat` does both); deploying one silently disables half the
delivery while the other half keeps working.

### e1 — `pause-cue`: the CLEAN LOCK, the function that DECIDES

The app listens to the OS lock event, so at the instant the screen goes off it sends one HTTP request (its
own user JWT — e1 takes the account from the `sub` claim and rejects anything that is not an
`authenticated` token; the body is just this device's id).

e1 calls **`evaluate_pause_cue()`**: re-check account-wide liveness (excluding the caller, whose row is
necessarily still fresh), not sleeping, **claim** (`data_payload_sent = true`, conditional), overdue break,
`break_length` → push at **`now() + break_length`**.

**That request *is* the walk-away, so nothing is estimated.**

### e2 — `pause-cue-cron`: the DIRTY KILL, which the cron drives

A phone that died sends nothing. `tick_pause_cues()` runs every **`t_b`** (`'* * * * *'` = every minute; the cron job is named
`pause-cue-tick` and is scheduled in `supabase/pause-cue-setup.sql`) and runs one fast grouped query.

An account whose newest `beat_at` is as old as or older than **`2·t_a`**, with an unclaimed
`data_payload_sent` row, not sleeping (`account_state`), **and with at least one OVERDUE published break**,
is handed to e2 via `omni_edge_push` (pg_net, service role — e2 rejects any non-service-role caller, since
the account rides the body).

**The cron made the whole decision**, so e2 does not re-decide: `claim_pause_cue()` claims and *directly*
computes the length and instant, then fans the push out. The cron's `cancel` pass is e2's job too.

### Shared behaviour

Both claim with the same conditional update, so a lock report racing a tick pushes exactly **one** cue per
idle episode however many ticks run; a returning device re-arms it by clearing the flag on its next beat.

Either function fans a high-priority **data** push out to every `device_push_token` row of the account (cancel
too), carrying `due_at` + `break_kind` + `voice_cue` + `message`.

### The cron path's cue instant

`t2 + break_length`, where `t2 = <the presence row's time> + t_a/2` — the expected midpoint of the tick
interval the last device vanished in.

`t2` comes from the server-stamped `beat_at`, so `t_b` is **detection latency only** and never moves the
cue. **Do NOT re-anchor it to "now at detection time".**

On the e1 path there is nothing to estimate — detection time and walk-away time are the same instant, so
`now()` is used and the ±`t_a`/2 estimate error is avoided. That is a refinement of this rule, not a
reversal.

### What the split gives up (deliberate)

e2 does not re-test liveness between the cron's scan and pg_net's asynchronous dispatch. An account that
comes back inside that window is cued anyway; the cron's cancel pass retracts it within one `t_b` — the same
backstop that already covers "the user came back before the break ended".

## Only an OVERDUE break fires a cue

Migrations `20260725000000`, `20260726000000`.

"Your pause is over" is spoken only when the account went idle with a rest break actually DUE (the user
walked away *to take* it), never when it merely went idle (lunch, an errand).

`build_pause_cue()` (which both `evaluate_pause_cue()` and `claim_pause_cue()` call) and the `t_b` tick's
pre-filter share one predicate, `public.overdue_break_at_last_beat(user)`: either published due
`<= max(beat_at)` over the account's presence rows (+ a `t_a` clock-skew slack) — i.e. *"was this break
already due at the last moment we saw this account?"*

Reference is the account's own newest `beat_at`, **NOT** the later cron `now()` — else an *upcoming* break
whose instant merely elapsed after walk-away would fire.

Between the two dues the **longest overdue** governs (`max(5min, 15min)`) — resting 15 min discharges a
5-min pose due at the same instant.

If nothing is overdue the account is claimed once (not re-evaluated every tick) but owes no cue, and the
cron never even hands it to e2.

## `device_break` — the two dues, account-keyed, event-driven

Migrations `20260726000000` + `20260728000000`.

`publish_next_break(break_5min_due_ms, break_15min_due_ms)` holds **the account plus the scheduled time of
apparition of both screen breaks, and nothing else**. It is called **only when the app calculates a
different pair** — a device sitting at its desk writes it zero times — and is **retried with backoff** until
it lands (nothing else re-sends it, unlike a beat).

### Why account-keyed, and why no length / kind / device

Both dues are the poses' next PLACED starts over one environment (the screen-break config, the standing
restrictive periods and the tasks — all *synced* authoritative state), so **every device computes the same
pair**. A per-device row stored N copies of one account-level fact and made the server redo, across rows, a
selection each client had already done.

The kind is implied by which column the due sits in. `kind` / `device_id` were read by nothing on this path
(the calendar's device bubble reads `device_active_session.kind`). And the **length is the server's**
(`break_config.length_ms`, else the kind's 5/15-min default), since the cue instant is `t2 + length` and
length is a property of the break TYPE.

The row deliberately **persists through lock/kill and is never cleared**: the last value published while
active is exactly the state the user walked away in, which is what the cue is judged on.

> **This replaced a piggyback on the `t_a` beat, which was wrong, not merely wasteful.** The engine resamples
> the window on its 30-s active-session beat, so a schedule edit that changed the next break took up to 30 s
> to reach the server, and any walk-away inside that window was judged on the *pre-edit* break — which the
> clean screen-off path hit **by construction**, since it calls e1 with no final beat. (It is not a bandwidth
> saving either: Supabase meters **egress**, and the break fields rode the request *body*.)

### `break_due_ms` is the pose's next PLACED START

`SchedulerDomain.nextScreenBreakStartMillis` — the same instant the calendar draws the band at and the local
cue sweep fires on (ADR 0003).

**It used to be the anchored due `lastRest + interval`, and it had to be.** A pose SLID: while it was owed its
drawn start was `maxOf(due, now)`, which rides the now-line and changes at every sample, so the drawn start
could not be written event-driven and the published instant had to be a second derivation kept in step with
it. Nothing slides since the recurrence bars pinned every break to a fixed instant, so the two derivations
collapsed into one — which is also why the gate on a "never anchored" pose is gone: there is no anchor to be
missing, and a pose the environment suspends indefinitely (a night, an open-ended inactivity period) simply
has no placed occurrence inside the search window and is not published at all.

`SchedulerEngine.restPoseDueMillisByKey` returns the starts keyed by `ScreenBreak.key`. It must be asked with
the **same environment the fill was**, or the bars answer a different timeline and the server times the cue to
a break the user never sees.

- An **already-due** pose publishes the constant `SchedulerEngine.ALREADY_DUE_MILLIS` (0) — the server only
  ever asks `due <= beat_at`, so any past value answers it, and only a constant one avoids re-triggering a
  write as the now-line moves. (A placed start is never behind the now-line, so this is now a floor rather
  than the common case.)
- A still-upcoming due is converted to the REAL instant it is reached at (`realInstantFor`) so an accelerated
  debug clock never reports a sim-ahead instant against real wall-clock beats.

The 20-s look-away is `restBreak=false` and is never published — it has its own local cue.

## `break_config` owns the length and the vocal message

Per `break_kind`, HTTP-changeable.

- `length_ms` null ⇒ use the length the client published.
- `voice_cue` names a **bundled** cue (`VoiceCue`: `look_away` / `resume_work` / `pause_over`) — the phone
  plays a pre-rendered WAV, it does not synthesize arbitrary text, so `voice_message` is text for
  logging/TTS fallback only.
- The client publishes the break's stable `ScreenBreak.key` (`5min_break` / `15min_break`,
  `SchedulerDomain.*_KEY`), NOT its title or duration — those move under the debug fast-break knobs.

## Clean screen-off short-circuits the wait

The publisher stops ticking and calls e1 directly with the user JWT (`notifyScreenOff`), so the cue is armed
**and anchored** at the lock instant instead of up to `t_b + 2·t_a` later. e1 excludes that device from the
liveness check (its row is necessarily still fresh).

A dirty kill makes no such call — the stale row is what the tick + e2 exist for.

**Force-stop exercises the DETECTION+SEND half only, never the audible one:** Android's stopped state
suppresses FCM delivery until the app is launched again, so e2's push is correctly computed and correctly not
heard (it arrives, long past due, at the next launch). Verify it in `supabase functions logs pause-cue-cron`;
to hear it, kill the app the way the OS does (swipe from Recents / OEM reaper) rather than force-stopping.

## Idleness is judged ACCOUNT-WIDE

`max(beat_at)`, never per row — a deliberate deviation from a literal per-row reading of the spec. One stale
row while the user works at the other device is not a pause, and per-row firing would also fan the same push
out once per row.

## A device belongs to exactly ONE account

Migration `20260727000000`, extending `20260721000000`'s push-token rule to the presence pair.

`device_heartbeat` / `device_break` are keyed `(user_id, device_id)` and the device id is allocated once per
INSTALL (`SchedulerSyncEngine.meta()`, a random UUID in the local DB that survives a sign-out).

> **Post-mortem.** Signing out of A and into B left A's rows frozen forever: A read as permanently idle and, if
> its leftover break row was overdue at that last beat, the next `t_b` tick pushed a **spurious "your pause is
> over"** to A's phones.

A `before insert or update` trigger on `device_heartbeat` (`device_presence_unique_owner`, SECURITY DEFINER)
now deletes every presence row for the same `device_id` under a different `user_id`. Since `20260728000000`
(where the break + claim rows became account-keyed and so have no device id to evict on), any account thereby
left with **no presence rows at all** also loses its `device_break` + `data_payload_sent` rows.

That last part is hygiene, not protection: an account with no presence row can never be evaluated (the cron
groups over `device_heartbeat`; `evaluate_pause_cue()` needs `max(beat_at)`), so the eviction alone already
stops the spurious cue. Deleting is safe — neither row is user data.

**Client half:** because the break is written only on CHANGE, the client force-republishes it on every
inactive→active transition (`DeviceHeartbeatPublisher.activation`), else an account switched back to would have
a heartbeat but no break and could never be owed a cue.

**Limitation:** this keys on the device id, so it heals only a device that kept its identity. A reinstall
(`account{1,2}-deploy-android.bat` wipes local data) returns with a NEW id and its old rows survive as
orphans — the account-empty scripts are what clear those.

## Sleep/Work toggle → `account_state`

The left-menu toggle (`SchedulerState.sleepingUntilMillis`, `SetSleepMode`,
`TaskSchedulerViewModel.setSleepMode`) writes the account's mode to the `account_state` table immediately
(`publishAccountState`) so `tick_pause_cues()` suppresses the cue while the user is deliberately away.

It persists across restart until the scheduled wake passes (`SchedulerEngine.resolveSleepModeOnStartup`).

## Tests and verification status

- `PresenceTickTest` — the tick cadence, server-side `t_a` adoption, screen-off.
- `PauseCueGatewayTest` — RPC/Edge call shapes, incl. that the screen-off report goes to `/pause-cue` and
  that the break body is the two dues and nothing else.
- `RestPosePresenceWindowTest` — what the two dues are, and the unanchored-pose gate.
- `RealtimePhoenixTest`, `SleepModeTest`.

**The SQL/Edge halves have no unit tests.** The live cue path (client heartbeat UPSERT ↔ `tick_pause_cues()`
cron ↔ Edge ↔ phone) still needs on-device confirmation against a real project — see
`docs/PAUSE_CUE_DELIVERY.md`.
