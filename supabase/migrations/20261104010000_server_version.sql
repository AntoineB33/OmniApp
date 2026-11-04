-- 2026-11-04 (user rule: OmniApp is shared with a few technical people, each on a free Supabase project of their own,
-- docs/SERVER_SETUP.md): the latest migration this project has applied, so the app can say "this server needs
-- scripts\deploy-supabase.bat" instead of failing feature by feature. Read from the table the Supabase CLI keeps
-- (`supabase db push` records each migration's timestamp there). Asked once per start and when the device goes back
-- online, never on a timer (docs/invariants/server-quota.md). A project without this function answers 404, which the
-- app reads the same way: not deployed.
create or replace function public.server_version()
returns text
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(max(version), '') from supabase_migrations.schema_migrations;
$$;

revoke all on function public.server_version() from public;
grant execute on function public.server_version() to anon, authenticated;
