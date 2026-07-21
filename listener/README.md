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

## Deploy — step by step

The listener must run on a machine that stays on. **It cannot run on your desktop if the whole
point is that you closed your desktop** — the moment that machine sleeps, the listener dies and no cue
fires. So deploy it to an always-on host. These steps use **Fly.io**: it's built for a tiny always-on
worker like this, its free allowance covers one, and the config lives in this repo (`Dockerfile`) so the
build is reproducible. Do it once; it then runs 24/7.

You will need two values (get them once, use them in step 4):

- **`SUPABASE_URL`** — `https://itoaqbftjemovkzswiyu.supabase.co` (your project URL).
- **`SUPABASE_SERVICE_ROLE_KEY`** — Supabase Dashboard → **Project Settings → API Keys → `service_role`**
  (the long `eyJ...` JWT, under the *Legacy API keys* tab if shown; **not** an `sb_secret_...` key). This
  key bypasses all security — **never commit it or paste it into a file in this repo.** It may already be in
  `scripts/accounts.env`.

### 1. Install the Fly CLI (one time, on your desktop)

In Windows PowerShell (the built-in one — this does **not** need PowerShell 7 / `pwsh`):

```powershell
iwr https://fly.io/install.ps1 -useb | iex
```

Close and reopen the terminal so `flyctl` is on your PATH. Verify with `flyctl version`.

### 2. Create a Fly account and log in

Run **one** of these (not both):

```powershell
flyctl auth signup   # first time — no Fly account yet. Opens the browser, creates the account, AND logs you in.
flyctl auth login    # only later — an existing account on a new machine or an expired session.
```

If this is your first time, run `flyctl auth signup`, finish in the browser, then go to step 3. **Fly
requires a card on file to launch anything** (a new org can't create machines without one). That's fine:
deployed at 256 MB in step 5 this worker costs ~$2/mo, and **Fly does not invoice under $5/mo**, so in
practice you pay nothing. You'll also see a `no payment method → high availability off` note before you add
the card — harmless.

### 3. Create the app (does not deploy yet)

From this `listener/` folder, **naming a region explicitly** (`cdg` = Paris; otherwise Fly may fail with
`Error: region any not found`):

```powershell
cd listener
flyctl launch --no-deploy --region cdg
```

Answer the prompts: accept the generated app name (or pass `--name <something-unique>` if it's taken) and
say **No** to any database/Redis. It detects the `Dockerfile` and writes a `fly.toml`. The
`no payment method → high availability off` warning is expected and fine — one instance is all this worker
needs. **Do not deploy yet** — secrets come first.

### 4. Set the two secrets

```powershell
flyctl secrets set SUPABASE_URL="https://itoaqbftjemovkzswiyu.supabase.co" SUPABASE_SERVICE_ROLE_KEY="eyJhbGciOi...paste-the-service-role-key..."
```

Fly stores these encrypted and injects them as env vars — they never touch this repo.

### 5. Deploy at 256 MB and confirm it's alive

Deploy with **`--vm-memory 256`** — the launch default is 1 GB (~$5.70/mo, over Fly's waived threshold),
but this worker uses ~50 MB, so 256 MB (~$2/mo) keeps you under the **$5/mo Fly does not invoice** → it's
effectively free:

```powershell
flyctl deploy --vm-memory 256
flyctl logs
```

You should see `omniapp-listener starting` in the logs. It now runs 24/7. To update it later (after pulling
new code), just run `flyctl deploy --vm-memory 256` again from `listener/`.

> **Just want a quick local sanity check first?** Run it in the foreground on your desktop — but remember
> this dies when the machine sleeps, so it only proves the code connects, not the closed-computer scenario:
> ```powershell
> cd listener
> npm install
> $env:SUPABASE_URL="https://itoaqbftjemovkzswiyu.supabase.co"; $env:SUPABASE_SERVICE_ROLE_KEY="eyJ..."
> npm start
> ```

## The rest of the chain (the listener alone does not make a phone speak)

The listener only decides **when** and **who**; the actual push comes from the Edge Function, to a phone that
registered a token. All of the following must also be true, or you'll deploy the listener and still hear
nothing:

1. **The `pause-cue` Edge Function is deployed with its push credentials set.** Run
   `scripts/deploy-supabase.bat` (creates the `account_state`, `account_last_phone`, `device_push_token`
   tables + deploys the function), then set the send credentials once:
   ```powershell
   supabase secrets set FCM_PROJECT_ID=... FCM_CLIENT_EMAIL=... FCM_PRIVATE_KEY="..."   # Android; APNS_* for iOS
   ```
   See `docs/PAUSE_CUE_DELIVERY.md` for exactly which FCM/APNs values these are and where to get them.
2. **Your phone is signed in to the account and has registered a push token.** Deploy the app to the phone
   (`scripts/account2-deploy-android.bat` for account 2) and open it once — the client claims last-phone and
   registers `device_push_token` on startup/foreground automatically.
3. **During the test the phone is locked/backgrounded and you have NOT pressed Sleep.** An unlocked phone
   counts as an active device, so the listener stays silent by design; Sleep mode suppresses the cue on
   purpose.

End-to-end test: run `scripts\account2-open-fast-break.bat 5 5` on the desktop, wait for a 5-min screen
break to come due, **close the desktop**, and keep the phone locked — the phone should speak ~5 s later.

## Status

The Kotlin client publishes presence and the toggle writes `account_state`, but the **end-to-end path
(client WS ↔ Realtime ↔ this listener ↔ Edge ↔ phone) has not yet been verified against a live project** — see
`docs/PAUSE_CUE_DELIVERY.md`. The Phoenix wire format the client sends is unit-tested (`RealtimePhoenixTest`);
the live connection needs on-device confirmation.
