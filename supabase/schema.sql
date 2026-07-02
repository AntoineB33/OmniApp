-- OmniApp cross-device sync — Supabase setup (PRD §5, Phase 1 whole-document sync).
-- Run this once in the Supabase SQL Editor (Dashboard > SQL Editor > New query > paste > Run).
--
-- One row per user holds the whole serialized PersistedSnapshot (app_state + history) as text, versioned
-- by `revision` for optimistic-concurrency. Row-Level Security ensures a signed-in user can only read and
-- write their own row, which is why the public anon key is safe to ship in the client.

create table if not exists public.scheduler_snapshot (
    user_id    uuid primary key references auth.users (id) on delete cascade,
    payload    text not null,
    revision   bigint not null,
    updated_at timestamptz not null default now()
);

alter table public.scheduler_snapshot enable row level security;

drop policy if exists "own snapshot" on public.scheduler_snapshot;
create policy "own snapshot" on public.scheduler_snapshot
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- Keep updated_at fresh on every write (informational; the client drives `revision`).
create or replace function public.touch_scheduler_snapshot()
returns trigger language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists touch_scheduler_snapshot on public.scheduler_snapshot;
create trigger touch_scheduler_snapshot
    before update on public.scheduler_snapshot
    for each row execute function public.touch_scheduler_snapshot();

-- ---------------------------------------------------------------------------
-- PRD §15 cross-device presence (device_presence).
--
-- One row per (user, device) holds whether that device currently has an active screen, refreshed by a
-- periodic heartbeat. The phone reads peers' rows to decide, at the start of a 5/15-min pose, whether any
-- device on the account is in use; if none is, it speaks a "pause finished" cue at the pose's end.
-- `updated_at` is the freshness used to age out a device that stopped reporting (slept/crashed). Same RLS
-- model as scheduler_snapshot: a signed-in user can only see and write their own devices' rows.
-- ---------------------------------------------------------------------------

create table if not exists public.device_presence (
    user_id       uuid not null references auth.users (id) on delete cascade,
    device_id     text not null,
    kind          text not null,
    screen_active boolean not null,
    updated_at    timestamptz not null default now(),
    primary key (user_id, device_id)
);

alter table public.device_presence enable row level security;

drop policy if exists "own presence" on public.device_presence;
create policy "own presence" on public.device_presence
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- Stamp updated_at on every insert AND update (the client upserts via merge-duplicates), so freshness is
-- server-driven and never depends on the client clock.
create or replace function public.touch_device_presence()
returns trigger language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists touch_device_presence on public.device_presence;
create trigger touch_device_presence
    before insert or update on public.device_presence
    for each row execute function public.touch_device_presence();

-- ---------------------------------------------------------------------------
-- PRD §15 device-sleep gaps (device_sleep_gap).
--
-- One row per (user, device, sleep_start) holds the EXACT interval a device was asleep, read from the OS on
-- wake (Windows Kernel-Power events). It is authoritative device-sleep history — an input to the schedule (a
-- sleep is the user taking a pause), not derivable from other state — synced per-row so every device pulls
-- the exact pause times the others observed (e.g. the phone learns the desktop's exact pause). Same RLS
-- model as the other tables: a signed-in user can only see and write their own devices' rows.
-- ---------------------------------------------------------------------------

create table if not exists public.device_sleep_gap (
    user_id     uuid not null references auth.users (id) on delete cascade,
    device_id   text not null,
    sleep_start bigint not null,
    sleep_end   bigint not null,
    recorded_at bigint not null,
    primary key (user_id, device_id, sleep_start)
);

alter table public.device_sleep_gap enable row level security;

drop policy if exists "own sleep gaps" on public.device_sleep_gap;
create policy "own sleep gaps" on public.device_sleep_gap
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- PRD §15 / ARCHITECTURE.md §8 — pause-end voice cue push delivery (FCM / APNs).
--
-- The end of a 5/15-min pose is spoken by the LAST PHONE that logged in to the account. Because a
-- backgrounded phone can't reliably run an in-app timer, the cue is delivered as an OS-scheduled local
-- notification, triggered by the server ~1 min before it is due (or scheduled locally when the phone itself
-- caused the schedule). These three tables + the cron function below are the server half of that flow. There
-- is NEVER a WebSocket — the only server→device channel is the Edge-Function push in
-- `supabase/functions/pause-cue/`.
--
-- NOTE: this needs (1) the `pg_cron` and `pg_net` extensions enabled, (2) the `pause-cue` Edge Function
-- deployed with FCM_* / APNS_* secrets, and (3) each phone registering its push token (native, follow-up).
-- Until those exist the tables are inert — the desktop client writes `pause_cue_schedule` / `account_last_phone`
-- but no push goes out.
-- ---------------------------------------------------------------------------

-- One push token per device so the server can reach it (platform = 'fcm' on Android, 'apns' on iOS).
create table if not exists public.device_push_token (
    user_id    uuid not null references auth.users (id) on delete cascade,
    device_id  text not null,
    kind       text not null,
    platform   text not null,
    token      text not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, device_id)
);

-- Which phone is the account's current "last logged in" phone — the single device that voices the cue.
create table if not exists public.account_last_phone (
    user_id    uuid primary key references auth.users (id) on delete cascade,
    device_id  text not null,
    updated_at timestamptz not null default now()
);

-- The next pause-end cue instant, and which device's change last set it. `origin_device_id` lets the cron
-- job skip the push when the schedule is due to the phone itself (it already scheduled the cue locally).
create table if not exists public.pause_cue_schedule (
    user_id          uuid primary key references auth.users (id) on delete cascade,
    due_at           timestamptz not null,
    origin_device_id text not null,
    updated_at       timestamptz not null default now()
);

alter table public.device_push_token   enable row level security;
alter table public.account_last_phone  enable row level security;
alter table public.pause_cue_schedule  enable row level security;

drop policy if exists "own push tokens" on public.device_push_token;
create policy "own push tokens" on public.device_push_token
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own last phone" on public.account_last_phone;
create policy "own last phone" on public.account_last_phone
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "own cue schedule" on public.pause_cue_schedule;
create policy "own cue schedule" on public.pause_cue_schedule
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- When the last phone changes, tell the PREVIOUS phone to cancel its scheduled cue (so only one phone ever
-- speaks). Fires the `pause-cue` Edge Function with action='cancel' for the old device via pg_net.
create or replace function public.on_last_phone_change()
returns trigger language plpgsql security definer as $$
begin
    if tg_op = 'UPDATE' and new.device_id is distinct from old.device_id then
        perform net.http_post(
            url     := current_setting('app.settings.edge_base_url', true) || '/pause-cue',
            headers := jsonb_build_object('Content-Type', 'application/json',
                                          'Authorization', 'Bearer ' || current_setting('app.settings.service_role_key', true)),
            body    := jsonb_build_object('user_id', new.user_id, 'device_id', old.device_id, 'action', 'cancel')
        );
    end if;
    return new;
end;
$$;

drop trigger if exists on_last_phone_change on public.account_last_phone;
create trigger on_last_phone_change
    after update on public.account_last_phone
    for each row execute function public.on_last_phone_change();

-- Every minute, for accounts whose next pause-end cue is due within ~1 min AND was NOT scheduled by the
-- current last phone, push that last phone to schedule the cue at the exact instant. Register with:
--   select cron.schedule('pause-cue-tick', '* * * * *', $$ select public.tick_pause_cues() $$);
create or replace function public.tick_pause_cues()
returns void language plpgsql security definer as $$
declare r record;
begin
    for r in
        select s.user_id, s.due_at, p.device_id as phone_device_id
        from public.pause_cue_schedule s
        join public.account_last_phone p on p.user_id = s.user_id
        where s.due_at between now() + interval '55 seconds' and now() + interval '65 seconds'
          and s.origin_device_id is distinct from p.device_id
    loop
        perform net.http_post(
            url     := current_setting('app.settings.edge_base_url', true) || '/pause-cue',
            headers := jsonb_build_object('Content-Type', 'application/json',
                                          'Authorization', 'Bearer ' || current_setting('app.settings.service_role_key', true)),
            body    := jsonb_build_object('user_id', r.user_id, 'device_id', r.phone_device_id,
                                          'action', 'schedule', 'due_at', r.due_at)
        );
    end loop;
end;
$$;
