-- Realtime-presence + external-listener sync model (PRD §15).
--
-- The client no longer polls the server for cross-device activity. Instead:
--   * device activity is Supabase **Realtime Presence** (a WebSocket the active clients hold), watched by a
--     small always-on external listener (see /listener) that fires the pause-cue Edge Function when the whole
--     account goes inactive and is not sleeping;
--   * the account's Sleep/Work mode lives in the new `account_state` table (written on every toggle), which the
--     listener reads to suppress the cue while the user is deliberately away;
--   * the snapshot syncs ONLY on the manual Sync button.
--
-- This migration ADDS `account_state` and DROPS the now-dead server-side machinery that the retired REST side
-- channels drove (device presence heartbeats, active-session rows + the derive_pauses RPC, device-sleep gaps,
-- the pause_cue_schedule row + its cron/triggers). Every statement is idempotent. Client on-disk DBs need no
-- migration: those were separate local tables / side channels, never part of the synced snapshot.

-- ---------------------------------------------------------------------------
-- NEW: the account's Sleep/Work mode (one row per user). `mode` is 'working' | 'sleeping'; `wake_at` is the
-- scheduled wake instant while sleeping (null when working). The external listener reads this to decide whether
-- to fire the pause-end cue. Same own-row RLS as scheduler_snapshot.
-- ---------------------------------------------------------------------------
create table if not exists public.account_state (
    user_id    uuid primary key references auth.users (id) on delete cascade,
    mode       text not null default 'working',
    wake_at    timestamptz,
    updated_at timestamptz not null default now()
);

alter table public.account_state enable row level security;

drop policy if exists "own account state" on public.account_state;
create policy "own account state" on public.account_state
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

create or replace function public.touch_account_state()
returns trigger language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists touch_account_state on public.account_state;
create trigger touch_account_state
    before update on public.account_state
    for each row execute function public.touch_account_state();

-- ---------------------------------------------------------------------------
-- DROP the retired activity/pause machinery. The listener + Realtime Presence replace it; the pause-cue Edge
-- Function is now invoked by the listener, not by pg_cron / DB triggers. Order matters (triggers/functions
-- before the tables they touch); all guarded with `if exists`.
-- ---------------------------------------------------------------------------
drop trigger if exists on_pause_cue_schedule_change on public.pause_cue_schedule;
drop trigger if exists on_last_phone_change on public.account_last_phone;
drop function if exists public.tick_pause_cues();
drop function if exists public.on_pause_cue_schedule_change();
drop function if exists public.on_last_phone_change();
drop function if exists public.derive_pauses(bigint, bigint);

drop table if exists public.pause_cue_schedule;
drop table if exists public.device_active_session;
drop table if exists public.device_sleep_gap;
drop table if exists public.device_presence;

-- KEPT (the listener still needs them): scheduler_snapshot, account_logout, account_last_phone,
-- device_push_token, and the pause-cue Edge Function.
