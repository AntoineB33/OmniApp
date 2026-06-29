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
