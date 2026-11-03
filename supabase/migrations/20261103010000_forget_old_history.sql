-- 2026-11-03 (user rules: the app never uses more than a tenth of any free-tier quota; near its limit the database
-- deletes its oldest rows, and they are replaced by a single row saying they are not saved here any more).
-- docs/invariants/server-quota.md § Near the limit, the server forgets.
--
-- Once a day (pause-cue-setup.sql schedules it): when the project's live rows reach 90 % of its limit, the oldest
-- HISTORY rows untouched for 30 days are deleted, oldest first, down to 70 %. LIVE rows, never pg_database_size: Postgres
-- does not shrink its files after a delete, it reuses the space, so the file size stays up and a job reading it would
-- forget another batch every day until no history was left.
--   * a task no cell holds (its `occurrences` empty, which the client leaves out): it stays only for its past;
--   * a calendar block that ended before the cut.
-- Deleted outright, with no tombstone, so no device hears of it as a deletion. Each account that lost rows gets ONE row
-- of kind `forgotten` (id `history`) in their place: the cut, how many rows and bytes it stands for. A device keeps the
-- rows it covers on a full read and never sends them back (ForgottenRows, SchedulerSyncEngine).
--
-- The limit is the tenth of the free tier's 500 MB, or lower where an account set it lower on its Settings page
-- (its `serverDatabaseLimitBytes` field row): the project keeps to the lowest.
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
begin
    select least(limit_bytes, coalesce(min(case when payload ~ '^[0-9]+$' then payload::bigint end), limit_bytes))
        into limit_bytes
        from public.scheduler_entity
        where kind = 'field' and entity_id = 'serverDatabaseLimitBytes' and not deleted;
    if used < limit_bytes * 9 / 10 then
        return;
    end if;
    to_free := used - limit_bytes * 7 / 10;

    with candidates as (
        select e.user_id, e.kind, e.entity_id, pg_column_size(e.*)::bigint as bytes,
               sum(pg_column_size(e.*)::bigint) over (order by e.updated_at, e.revision rows between unbounded preceding and current row) as running
        from public.scheduler_entity e
        where not e.deleted
          and e.updated_at < cut
          and (
              (e.kind = 'tasks' and coalesce(jsonb_array_length(e.payload::jsonb -> 'occurrences'), 0) = 0)
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
