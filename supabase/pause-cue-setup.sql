-- Project-level setup for the pause-end voice cue push (PRD §15 / ARCHITECTURE.md §8).
--
-- This is deliberately NOT a migration and is NOT applied by `supabase db push`, because:
--   * `create extension` / `alter database ... set` / `cron.schedule` are project-level, not table DDL; and
--   * the service-role key is a SECRET that must never be committed to git.
--
-- Instead, scripts/deploy-supabase.bat runs this file via `supabase db query --linked`, substituting the two
-- placeholder tokens (see below) from scripts/accounts.env (gitignored) at runtime. The committed copy
-- therefore contains only placeholders, never the real key. To run it by hand instead, replace the two tokens
-- and paste it into the Dashboard SQL Editor.
--
-- Every statement is idempotent (`if not exists`; `alter set` overwrites; `cron.schedule` upserts by jobname),
-- so re-running is safe. Until the pause-cue Edge Function + native push tokens exist, this setup is inert.

create extension if not exists pg_cron;
create extension if not exists pg_net;

-- The Edge Function base URL and the service-role key the trigger/cron functions read via current_setting().
alter database postgres set app.settings.edge_base_url    = '__EDGE_BASE_URL__';
alter database postgres set app.settings.service_role_key = '__SERVICE_ROLE_KEY__';

-- Run the pause-cue tick every minute (upserts by the 'pause-cue-tick' jobname if already scheduled).
select cron.schedule('pause-cue-tick', '* * * * *', $$ select public.tick_pause_cues() $$);
