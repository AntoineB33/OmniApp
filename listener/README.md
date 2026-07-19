# OmniApp presence listener

An always-on Node process that watches the account's **Supabase Realtime Presence** and fires the pause-end
voice cue to the phone when everyone goes inactive. It replaces the retired pg_cron `tick_pause_cues()` +
`derive_pauses` machinery.

## Why it exists

pg_cron and the Edge Function **cannot read Realtime Presence** (presence lives in the Realtime service, not
Postgres). So this small worker holds a Realtime connection as the service role, sees every account's live
presence, and — when an account has **no active device** and is **not sleeping** — invokes the existing
`pause-cue` Edge Function ~1 s before the waiting break completes, fanned out to **all** the account's
registered phones. The phone then schedules an exact local alarm and speaks
(`SchedulerEngine.onPauseCuePush` / `onPauseCueFire`). The push credentials (FCM/APNs) stay in the Edge
Function; this worker only decides *when* and *who*.

## How it works

- Subscribes to `presence:<user_id>` for every account (enumerated via the service-role admin API, rescanned
  every 60 s — so a desktop-only account is watched too; it just won't get a cue until a phone registers).
  Active clients publish presence with `{ device_id, kind, next_break_end_ms, next_break_start_ms,
  next_break_len_ms }` while signed-in + unlocked.
- On each presence change, per account:
  - **≥1 device present** → cancel any pending cue (and push `cancel` to every phone if a schedule was
    already fanned out); capture the latest published break window.
  - **0 devices present** → record the disconnect instant `T` (the real moment the last device left); read
    `account_state`; if `mode = 'sleeping'` and `wake_at` is in the future, do nothing. Otherwise the pause
    end is computed **server-side**: `d = max(T, next_break_start_ms) + next_break_len_ms` (a start at/before
    "now" means the break was already waiting — e.g. with 15-min breaks pending, `d` lands 15 min after the
    start of the all-disconnected window). A one-shot timer at `d − 1 s` re-checks (still idle) and POSTs
    `pause-cue` `{ action: 'schedule', device_id: '*', due_at, break_len_ms }` — the Edge Function sends to
    every `device_push_token` row of the account. Clients that predate the window fields fall back to the
    published `next_break_end_ms`.

It reads `account_state`; it never writes the DB.

## Deploy (Render / Fly.io / Railway free tier)

Any always-on Node ≥18 host works. Set these environment variables:

| var | value |
| --- | --- |
| `SUPABASE_URL` | `https://<ref>.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | the project's **service-role** key (Dashboard → Settings → API). **Secret** — never commit it. |
| `EDGE_BASE_URL` | optional; defaults to `${SUPABASE_URL}/functions/v1` |

Then `npm install && npm start`.

- **Render**: New → Background Worker → this repo, root dir `listener/`, build `npm install`, start `npm start`,
  add the env vars.
- **Fly.io**: `fly launch --no-deploy` in `listener/`, `fly secrets set SUPABASE_URL=… SUPABASE_SERVICE_ROLE_KEY=…`, `fly deploy`.
- **Railway**: New Project → Deploy from repo, root `listener/`, add the variables.

## Prerequisites on the Supabase side

- The `account_state`, `account_last_phone`, `device_push_token` tables and the `pause-cue` Edge Function
  (applied by `scripts/deploy-supabase.bat`; the Edge Function needs its `FCM_*` / `APNS_*` secrets set).
- Phones registering their push token + claiming last-phone (the client already does this on startup/foreground).

## Status

The Kotlin client publishes presence and the toggle writes `account_state`, but the **end-to-end path
(client WS ↔ Realtime ↔ this listener ↔ Edge ↔ phone) has not yet been verified against a live project** — see
`docs/PAUSE_CUE_DELIVERY.md`. The Phoenix wire format the client sends is unit-tested (`RealtimePhoenixTest`);
the live connection needs on-device confirmation.
