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
