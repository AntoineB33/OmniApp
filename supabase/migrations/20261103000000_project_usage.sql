-- 2026-11-03 (user rule: the Settings page shows the free tier's usage, with a refresh button): what the project's
-- database holds, and the calling account's share of it. Asked once per press of the button, never on a timer
-- (docs/invariants/server-quota.md). Egress, Realtime and Edge Function counts are not visible to Postgres.
create or replace function public.project_usage()
returns json
language sql
stable
security definer
set search_path = public
as $$
  select json_build_object(
    'database_bytes', pg_database_size(current_database()),
    'account_bytes',
      coalesce((select sum(pg_column_size(e.*)) from public.scheduler_entity e where e.user_id = auth.uid()), 0)
      + coalesce((select sum(pg_column_size(h.*)) from public.history_unit h where h.user_id = auth.uid()), 0)
      + coalesce((select sum(pg_column_size(c.*)) from public.claude_log_entry c where c.user_id = auth.uid()), 0)
  );
$$;

revoke all on function public.project_usage() from public;
grant execute on function public.project_usage() to authenticated;
