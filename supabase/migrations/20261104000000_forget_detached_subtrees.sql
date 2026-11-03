-- 2026-11-04 (user rule 2026-11-03: near its limit the database forgets its oldest rows, one row standing for them;
-- docs/invariants/server-quota.md § Near the limit, the server forgets). Replaces forget_old_history (migration
-- 20261103010000) with a wider reading of HISTORY: besides a task no cell holds and a calendar block that has ended,
-- that task's whole SUB-TREE -- its sub-list, the cells in it, every task held only in those cells, and so on down.
--
-- No row of the tree reaches such a sub-tree, so nothing shows it: a renamed parent keeps its sub-tree so that naming
-- it again brings it back (docs/invariants/task-tree.md § A sub-list belongs to the task id). ServerQuotaTest measured
-- those sub-trees as most of what the tree grows by in a heavy hour (three sub-lists and their cells an hour), and the
-- history trim alone left the year over its budget.
--
-- The same predicate is ForgottenRows.coveredKeys on the devices, so a device holding these rows keeps them on a full
-- read and never sends them back. The walk runs to a fixed point: the tasks no cell holds, the sub-lists they own
-- (`childListId`), the cells in those lists (`parentListId`), the tasks EVERY one of whose cells is such a cell, again.
-- The root task, list and cell are never history.
--
-- Everything else is as before: from 90 % of the limit down to 70 %, oldest first, rows untouched for 30 days, deleted
-- with no tombstone, one `forgotten` / `history` row per account in their place. A sub-tree is forgotten row by row,
-- oldest first, so the cut can fall inside one: a device that never held it then reads a task whose sub-list is gone,
-- which is the loss docs/invariants/server-quota.md accepts, and nothing it shows changes.
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
    select least(limit_bytes, coalesce(min(case when payload ~ '^[0-9]+$' then payload::bigint end), limit_bytes))
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
          and coalesce(jsonb_array_length(e.payload::jsonb -> 'occurrences'), 0) = 0;
    loop
        insert into forget_tree
            select distinct l.user_id, 'lists', l.entity_id
            from forget_tree h
            join public.scheduler_entity t on t.user_id = h.user_id and t.kind = 'tasks' and t.entity_id = h.entity_id
            join public.scheduler_entity l on l.user_id = t.user_id and l.kind = 'lists' and not l.deleted
                and l.entity_id = t.payload::jsonb ->> 'childListId'
            where h.kind = 'tasks' and l.entity_id <> all (roots)
        on conflict do nothing;
        insert into forget_tree
            select c.user_id, 'cells', c.entity_id
            from public.scheduler_entity c
            join forget_tree h on h.user_id = c.user_id and h.kind = 'lists' and h.entity_id = c.payload::jsonb ->> 'parentListId'
            where c.kind = 'cells' and not c.deleted and c.entity_id <> all (roots)
        on conflict do nothing;
        insert into forget_tree
            select t.user_id, 'tasks', t.entity_id
            from public.scheduler_entity t
            where t.kind = 'tasks' and not t.deleted and t.entity_id <> all (roots)
              and coalesce(jsonb_array_length(t.payload::jsonb -> 'occurrences'), 0) > 0
              and not exists (
                  select 1 from jsonb_array_elements_text(t.payload::jsonb -> 'occurrences') o(cell)
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
              or (e.kind = 'panels' and coalesce((e.payload::jsonb ->> 'end')::bigint, 9223372036854775807) < cut_millis)
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
