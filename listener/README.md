# OmniApp presence listener

An always-on Node process that watches the account's **Supabase Realtime Presence** and fires the pause-end
voice cue to the phone when everyone goes inactive. It replaces the retired pg_cron `tick_pause_cues()` +
`derive_pauses` machinery.

## Why it exists

pg_cron and the Edge Function **cannot read Realtime Presence** (presence lives in the Realtime service, not
Postgres). So this small worker holds a Realtime connection as the service role, sees every account's live
presence, and — when an account has **no active device** and is **not sleeping** — invokes the existing
`pause-cue` Edge Function ~1 s before the next ≥5-min break ends. The phone then schedules an exact local alarm
and speaks (`SchedulerEngine.onPauseCuePush` / `onPauseCueFire`). The push credentials (FCM/APNs) stay in the
Edge Function; this worker only decides *when* and *who*.

## How it works

- Subscribes to `presence:<user_id>` for every account that has a row in `account_last_phone` (rescanned every
  60 s). Active clients publish presence with `{ device_id, kind, next_break_end_ms }` while signed-in + screen-on.
- On each presence change, per account:
  - **≥1 device present** → cancel any pending cue (and push `cancel` to the phone if one was already scheduled).
  - **0 devices present** → read `account_state`; if `mode = 'sleeping'` and `wake_at` is in the future, do
    nothing. Otherwise schedule a one-shot timer at `next_break_end_ms − 1 s` that re-checks (still idle) and
    POSTs `pause-cue` `{ action: 'schedule', device_id: <account_last_phone>, due_at }`.

It reads `account_state` and `account_last_phone`; it never writes the DB.

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
