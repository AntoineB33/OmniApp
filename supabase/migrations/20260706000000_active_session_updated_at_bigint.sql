-- PRD §15 fix: device_active_session.updated_at was created as `timestamptz not null default now()`, but the
-- client upserts it as epoch millis (a bigint, like device_sleep_gap.recorded_at / start_ms / end_ms). Feeding
-- the integer 1783456970319 into a timestamptz column made Postgres reject every insert with SQLSTATE 22008
-- ("date/time field value out of range"), so the whole POST /rest/v1/device_active_session upsert returned 400
-- and no active-session rows were ever recorded — starving derive_pauses of its input.
--
-- Convert the column to bigint and drop the server default (the client always supplies the value). Guarded so
-- it only runs where the column is still a timestamptz: a DB created after the 20260705 migration was fixed
-- already has bigint and is skipped, making this safe to re-apply. There are no meaningful rows to preserve
-- (every prior insert 400'd), but the USING clause converts any that exist to epoch millis rather than drop
-- them. Idempotent; apply with `supabase db push` (or scripts/deploy-supabase.bat).
do $$
begin
    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'device_active_session'
          and column_name = 'updated_at'
          and data_type = 'timestamp with time zone'
    ) then
        alter table public.device_active_session
            alter column updated_at drop default,
            alter column updated_at type bigint
                using (extract(epoch from updated_at) * 1000)::bigint;
    end if;
end $$;
