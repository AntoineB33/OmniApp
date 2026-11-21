-- 2026-11-21 END-TO-END ENCRYPTION (user rule: "any data sent to the internet is crypted -- even Supabase can read user's
-- data"; shared/src/commonMain/kotlin/org/example/project/scheduler/sync/E2ee.kt, docs/invariants/sync-and-accounts.md
-- § End-to-end encryption).
--
-- From this migration on, the devices write scheduler_entity.payload, history_unit.delta and the text of
-- claude_log_entry (session_title, project, body) as AES-256-GCM ciphertext (`e1:` + base64) under a key the server never
-- holds in clear. Three things change here:
--
--   account_key        one row per account: its content key WRAPPED by a key derived from the password on the device.
--                      Useless without the password; GoTrue is given a secret derived from it, never the password.
--   scheduler_entity.meta
--                      the bare STRUCTURE the server's own clean-up reads, in clear, written by the device beside the
--                      ciphertext: `occurrences` (cell ids), `childListId`, `parentListId`, `end` (a panel's end, epoch
--                      millis), and `value` for the serverDatabaseLimitBytes field. No title, note or text.
--   forget_old_history reads that structure through entity_meta() and never casts a payload (a ciphertext is not JSON).
--                      A row written before encryption still has a JSON payload and no meta: read from the payload.
--
-- What stays in clear on purpose (the user's choice, 2026-11-21): timestamps, device ids, push tokens and break due times
-- the pause-cue cron computes on, the history rows' category / window / flags, the row kinds and ids.
--
-- Idempotent: safe to re-run.

create table if not exists public.account_key (
    user_id    uuid primary key references auth.users (id) on delete cascade,
    wrapped    text not null,
    updated_at timestamptz not null default now()
);
alter table public.account_key enable row level security;
drop policy if exists "own key" on public.account_key;
create policy "own key" on public.account_key
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

alter table public.scheduler_entity add column if not exists meta jsonb;

-- The structure of an entity row: its meta, or -- a row written before encryption -- its JSON payload. Never a cast of
-- a ciphertext.
create or replace function public.entity_meta(meta jsonb, payload text) returns jsonb
language sql immutable as $$
    select coalesce(meta, case when payload like '{%' then payload::jsonb end)
$$;

create or replace function public.forget_old_history() returns void
language plpgsql security definer set search_path = public as $$
declare
    limit_bytes bigint := 50000000;
    used bigint :=
        coalesce((select sum(pg_column_size(e.*))::bigint from public.scheduler_entity e), 0)
        + coalesce((select sum(pg_column_size(h.*))::bigint from public.history_unit h), 0)
        + coalesce((select sum(pg_column_size(c.*))::bigint from public.claude_log_entry c), 0);
    to_free bigint;
    cut timestamptz := now() - interval '30 days';
    cut_millis bigint := (extract(epoch from (now() - interval '30 days')) * 1000)::bigint;
    roots text[] := array['task/root', 'list/root', 'cell/root', 'list/root-cell'];
    added bigint;
begin
    select least(limit_bytes, coalesce(min(case when payload ~ '^[0-9]+$' then payload::bigint when (meta ->> 'value') ~ '^[0-9]+$' then (meta ->> 'value')::bigint end), limit_bytes))
        into limit_bytes
        from public.scheduler_entity
        where kind = 'field' and entity_id = 'serverDatabaseLimitBytes' and not deleted;
    if used < limit_bytes * 9 / 10 then
        return;
    end if;
    to_free := used - limit_bytes * 7 / 10;

    -- The tree's history, walked to a fixed point.
    create temporary table forget_tree (user_id uuid, kind text, entity_id text, primary key (user_id, kind, entity_id))
        on commit drop;
    insert into forget_tree
        select e.user_id, 'tasks', e.entity_id from public.scheduler_entity e
        where e.kind = 'tasks' and not e.deleted and e.entity_id <> all (roots)
          and coalesce(jsonb_array_length(public.entity_meta(e.meta, e.payload) -> 'occurrences'), 0) = 0;
    loop
        insert into forget_tree
            select distinct l.user_id, 'lists', l.entity_id
            from forget_tree h
            join public.scheduler_entity t on t.user_id = h.user_id and t.kind = 'tasks' and t.entity_id = h.entity_id
            join public.scheduler_entity l on l.user_id = t.user_id and l.kind = 'lists' and not l.deleted
                and l.entity_id = public.entity_meta(t.meta, t.payload) ->> 'childListId'
            where h.kind = 'tasks' and l.entity_id <> all (roots)
        on conflict do nothing;
        insert into forget_tree
            select c.user_id, 'cells', c.entity_id
            from public.scheduler_entity c
            join forget_tree h on h.user_id = c.user_id and h.kind = 'lists' and h.entity_id = public.entity_meta(c.meta, c.payload) ->> 'parentListId'
            where c.kind = 'cells' and not c.deleted and c.entity_id <> all (roots)
        on conflict do nothing;
        insert into forget_tree
            select t.user_id, 'tasks', t.entity_id
            from public.scheduler_entity t
            where t.kind = 'tasks' and not t.deleted and t.entity_id <> all (roots)
              and coalesce(jsonb_array_length(public.entity_meta(t.meta, t.payload) -> 'occurrences'), 0) > 0
              and not exists (
                  select 1 from jsonb_array_elements_text(public.entity_meta(t.meta, t.payload) -> 'occurrences') o(cell)
                  where not exists (
                      select 1 from forget_tree h where h.user_id = t.user_id and h.kind = 'cells' and h.entity_id = o.cell))
        on conflict do nothing;
        -- New lists come only from new tasks: none this round, none next.
        get diagnostics added = row_count;
        exit when added = 0;
    end loop;

    with candidates as (
        select e.user_id, e.kind, e.entity_id, pg_column_size(e.*)::bigint as bytes,
               sum(pg_column_size(e.*)::bigint) over (order by e.updated_at, e.revision rows between unbounded preceding and current row) as running
        from public.scheduler_entity e
        where not e.deleted
          and e.updated_at < cut
          and (
              exists (select 1 from forget_tree h where h.user_id = e.user_id and h.kind = e.kind and h.entity_id = e.entity_id)
              or (e.kind = 'panels' and coalesce((public.entity_meta(e.meta, e.payload) ->> 'end')::bigint, 9223372036854775807) < cut_millis)
          )
    ), gone as (
        delete from public.scheduler_entity e
        using candidates c
        where c.running - c.bytes < to_free
          and e.user_id = c.user_id and e.kind = c.kind and e.entity_id = c.entity_id
        returning e.user_id, c.bytes
    ), per_account as (
        select user_id, count(*) as rows, sum(bytes) as bytes from gone group by user_id
    )
    insert into public.scheduler_entity (user_id, kind, entity_id, payload, device_id)
    select user_id, 'forgotten', 'history',
           json_build_object('untilMillis', cut_millis, 'rows', rows, 'bytes', bytes)::text, 'server'
    from per_account
    on conflict (user_id, kind, entity_id) do update
        set payload = json_build_object(
                'untilMillis', cut_millis,
                'rows', coalesce((public.scheduler_entity.payload::jsonb ->> 'rows')::bigint, 0) + (excluded.payload::jsonb ->> 'rows')::bigint,
                'bytes', coalesce((public.scheduler_entity.payload::jsonb ->> 'bytes')::bigint, 0) + (excluded.payload::jsonb ->> 'bytes')::bigint
            )::text,
            deleted = false;
end;
$$;
revoke all on function public.forget_old_history() from public, anon, authenticated;
