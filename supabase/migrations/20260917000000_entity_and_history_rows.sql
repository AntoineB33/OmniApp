-- SYNC BY ROWS, NOT BY DOCUMENT (docs/invariants/sync-and-accounts.md, docs/invariants/server-quota.md).
--
-- WHY. On 2026-09-14 the project filled its 500 MB free-tier database and went down. The app synced its whole
-- state as ONE row, scheduler_snapshot.payload, rewritten on every edit: 0.78 MB of state plus 80 MB of Undo/Redo
-- history on the release account. Postgres keeps every old version of an updated row until autovacuum reclaims it
-- (and replica identity full wrote each old row into the WAL too), so a few dozen edits held gigabytes.
--
-- WHAT REPLACES IT. Two tables whose rows are what an edit actually touched:
--
--   scheduler_entity  one row per synced entity -- a task, a cell, a list, a calendar panel, an alarm, a setting.
--                     An edit writes the few rows it changed; a deletion writes a tombstone (deleted = true).
--   history_unit      one row per History Unit (every category, every device). A commit inserts one row; an
--                     undo or redo flips one row's undone flag; a redo branch a new edit discards is marked
--                     dropped (and its delta emptied) so the other devices forget it; the oldest units past
--                     1000 per category are deleted by the trigger below.
--
-- Every row carries a REVISION from one sequence, bumped on every write, so a device pulls exactly what changed
-- since the highest revision it has seen (revision > cursor), written by another device (device_id <> its own).
--
-- REALTIME. Neither table is in the publication: every changed row would be one message per device, and a heavy
-- hour of edits would exhaust the free tier's two million messages a month. Instead scheduler_head holds ONE row
-- per account, bumped by a statement-level trigger whenever scheduler_entity changes, and only that table is
-- published: one tiny message per push per connected device pokes the others to pull.
--
-- THE SNAPSHOT. scheduler_snapshot is emptied and made read-only, and leaves the publication: a client still
-- running an older build then fails its pushes (logged, harmless) instead of filling the database again.
--
-- Idempotent: safe to re-run.

create sequence if not exists public.scheduler_revision_seq;

create or replace function public.bump_scheduler_revision() returns trigger
language plpgsql as $$
begin
    new.revision := nextval('public.scheduler_revision_seq');
    return new;
end
$$;

-- ---------------------------------------------------------------------------------------------------------------
create table if not exists public.scheduler_entity (
    user_id   uuid   not null references auth.users (id) on delete cascade,
    kind      text   not null,
    entity_id text   not null,
    payload   text,
    deleted   boolean not null default false,
    device_id text,
    revision  bigint not null default nextval('public.scheduler_revision_seq'),
    primary key (user_id, kind, entity_id)
);
create index if not exists scheduler_entity_user_revision on public.scheduler_entity (user_id, revision);

drop trigger if exists scheduler_entity_revision on public.scheduler_entity;
create trigger scheduler_entity_revision
    before update on public.scheduler_entity
    for each row execute function public.bump_scheduler_revision();

alter table public.scheduler_entity enable row level security;
drop policy if exists "own entities" on public.scheduler_entity;
create policy "own entities" on public.scheduler_entity
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------------------------------------------
create table if not exists public.scheduler_head (
    user_id   uuid   primary key references auth.users (id) on delete cascade,
    revision  bigint not null default 0,
    device_id text
);

alter table public.scheduler_head enable row level security;
drop policy if exists "own head" on public.scheduler_head;
create policy "own head" on public.scheduler_head for select using (auth.uid() = user_id);

create or replace function public.bump_scheduler_head() returns trigger
language plpgsql security definer set search_path = public as $$
begin
    insert into public.scheduler_head (user_id, revision, device_id)
    select c.user_id, max(c.revision), max(c.device_id) from changed c group by c.user_id
    on conflict (user_id) do update
        set revision = greatest(public.scheduler_head.revision, excluded.revision),
            device_id = excluded.device_id;
    return null;
end
$$;

drop trigger if exists scheduler_entity_head_insert on public.scheduler_entity;
create trigger scheduler_entity_head_insert
    after insert on public.scheduler_entity
    referencing new table as changed
    for each statement execute function public.bump_scheduler_head();

drop trigger if exists scheduler_entity_head_update on public.scheduler_entity;
create trigger scheduler_entity_head_update
    after update on public.scheduler_entity
    referencing new table as changed
    for each statement execute function public.bump_scheduler_head();

do $$
begin
    alter publication supabase_realtime add table public.scheduler_head;
exception
    when duplicate_object then null;
end
$$;

-- ---------------------------------------------------------------------------------------------------------------
create table if not exists public.history_unit (
    user_id     uuid    not null references auth.users (id) on delete cascade,
    device_id   text    not null,
    category    text    not null,
    device_seq  bigint  not null,
    time_millis bigint  not null,
    chrono_id   bigint  not null default 0,
    tainted     boolean not null default false,
    undone      boolean not null default false,
    dropped     boolean not null default false,
    unit_window text,
    delta       text    not null,
    revision    bigint  not null default nextval('public.scheduler_revision_seq'),
    primary key (user_id, device_id, category, device_seq)
);
create index if not exists history_unit_user_revision on public.history_unit (user_id, revision);
create index if not exists history_unit_user_category_time on public.history_unit (user_id, category, time_millis);

drop trigger if exists history_unit_revision on public.history_unit;
create trigger history_unit_revision
    before update on public.history_unit
    for each row execute function public.bump_scheduler_revision();

alter table public.history_unit enable row level security;
drop policy if exists "own history" on public.history_unit;
create policy "own history" on public.history_unit
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- The account keeps the newest 1000 units of each category, like each device does.
create or replace function public.trim_history_units() returns trigger
language plpgsql security definer set search_path = public as $$
begin
    delete from public.history_unit h
    using (
        select u.user_id, u.category, u.device_id, u.device_seq,
               row_number() over (partition by u.user_id, u.category order by u.time_millis desc, u.chrono_id desc) as rank
        from public.history_unit u
        where (u.user_id, u.category) in (select distinct n.user_id, n.category from added n)
    ) ranked
    where ranked.rank > 1000
      and h.user_id = ranked.user_id and h.category = ranked.category
      and h.device_id = ranked.device_id and h.device_seq = ranked.device_seq;
    return null;
end
$$;

drop trigger if exists history_unit_trim on public.history_unit;
create trigger history_unit_trim
    after insert on public.history_unit
    referencing new table as added
    for each statement execute function public.trim_history_units();

-- ---------------------------------------------------------------------------------------------------------------
-- TOMBSTONES DO NOT STAY. Every rename retires a task row (a task is its title), so tombstones are written at the
-- pace of editing; kept forever they alone would grow an account by tens of megabytes a year. A tombstone only has
-- to reach the devices that still hold the entity, and every device pulls whenever it syncs, so one older than a
-- week is deleted (pause-cue-setup.sql schedules this daily). A device that has not pulled for six days or more
-- does not trust its cursor: it reads the account's live rows in full, and an entity absent from them is gone.
alter table public.scheduler_entity add column if not exists updated_at timestamptz not null default now();

create or replace function public.touch_scheduler_entity() returns trigger
language plpgsql as $$
begin
    new.updated_at := now();
    return new;
end
$$;

drop trigger if exists scheduler_entity_touch on public.scheduler_entity;
create trigger scheduler_entity_touch
    before update on public.scheduler_entity
    for each row execute function public.touch_scheduler_entity();

create index if not exists scheduler_entity_tombstones on public.scheduler_entity (updated_at) where deleted;

create or replace function public.purge_scheduler_tombstones() returns void
language sql security definer set search_path = public as $$
    delete from public.scheduler_entity where deleted and updated_at < now() - interval '7 days';
$$;
revoke all on function public.purge_scheduler_tombstones() from public, anon, authenticated;

-- ---------------------------------------------------------------------------------------------------------------
-- The snapshot row: emptied, read-only, unpublished.
truncate public.scheduler_snapshot;
drop policy if exists "own snapshot" on public.scheduler_snapshot;
drop policy if exists "own snapshot read" on public.scheduler_snapshot;
create policy "own snapshot read" on public.scheduler_snapshot for select using (auth.uid() = user_id);
do $$
begin
    alter publication supabase_realtime drop table public.scheduler_snapshot;
exception
    when undefined_object then null;
end
$$;
