-- Project-level setup for the pause-end voice cue push (PRD §15 / ARCHITECTURE.md §8).
--
-- This is deliberately NOT a migration and is NOT applied by `supabase db push`, because:
--   * `create extension` / Vault secrets / `cron.schedule` are project-level, not table DDL; and
--   * the service-role key is a SECRET that must never be committed to git.
--
-- Instead, scripts/deploy-supabase.bat runs this file via `supabase db query --linked`, substituting the two
-- placeholder tokens (see below) from scripts/accounts.env (gitignored) at runtime. The committed copy
-- therefore contains only placeholders, never the real key. To run it by hand instead, replace the two tokens
-- and paste it into the Dashboard SQL Editor.
--
-- Every statement is idempotent (`if not exists`; the Vault DO-block upserts by secret name; `cron.schedule`
-- upserts by jobname), so re-running is safe. Until the pause-cue Edge Function + native push tokens exist,
-- this setup is inert.

create extension if not exists pg_cron;
create extension if not exists pg_net;
create extension if not exists supabase_vault;

-- The Edge Function base URL and the service-role key, read by public.omni_edge_push (migration 20260709020000).
-- Vault secrets, NOT `alter database set app.settings.*` GUCs: database-level custom GUCs are superuser-only on
-- Postgres 15+ (42501 on Supabase), while the postgres role can upsert Vault secrets — and they're encrypted
-- at rest.
do $$
declare
    sid uuid;
begin
    select id into sid from vault.secrets where name = 'omni_edge_base_url';
    if sid is null then
        perform vault.create_secret('__EDGE_BASE_URL__', 'omni_edge_base_url');
    else
        perform vault.update_secret(sid, '__EDGE_BASE_URL__');
    end if;

    select id into sid from vault.secrets where name = 'omni_service_role_key';
    if sid is null then
        perform vault.create_secret('__SERVICE_ROLE_KEY__', 'omni_service_role_key');
    else
        perform vault.update_secret(sid, '__SERVICE_ROLE_KEY__');
    end if;
end;
$$;

-- Pause-cue tick cron — this is **`t_b`** (migrations 20260724000000 + 20260729000000): every minute, one fast
-- grouped query over the presence table (`device_heartbeat`) finds each idle account that is owed a cue and
-- hands it to the `pause-cue-cron` Edge Function (**e2**), which claims, computes and pushes it. This is the
-- backstop for a phone that died WITHOUT reporting; a clean lock never gets here at all, because the app calls
-- the OTHER Edge Function (`pause-cue`, **e1**) itself at the lock instant.
-- `t_b` is the *detection* granularity only: on this path the cue instant is computed from the server-stamped
-- presence time (`t2 = beat_at + t_a/2`), so it does not drift with which tick noticed. (On the e1 path there
-- is nothing to estimate — the request IS the walk-away, so the cue is `now() + break_length`.)
--
-- pg_cron accepts standard cron syntax OR a sub-minute interval string, but the interval form is only
-- '[1-59] seconds' — 'N minutes' is rejected with `invalid schedule`. One minute is therefore the cron
-- form '* * * * *'; use '30 seconds' etc. only for a sub-minute `t_b`.
--
-- NOTE: this file must contain NO double-quote character anywhere, comments included. It is handed to the
-- CLI as ONE inline argument (see scripts/internal/apply-pause-cue-setup.ps1), and Windows PowerShell 5.1
-- mangles a native-command argument that embeds one — the SQL is then silently TRUNCATED, so the tail
-- statements never run while the command still reports success. apply-pause-cue-setup.ps1 hard-fails on a
-- double quote for that reason. Use backticks or single quotes when quoting in a comment.
-- pg_cron logs every run in cron.job_run_details and never purges it: at one tick a minute that is 525,600 rows a
-- year, about 120 MB -- a quarter of the free tier database on its own (docs/invariants/server-quota.md). Keep a
-- day of it. Scheduled BEFORE the tick below, which stays the last statement this file runs.
select cron.schedule('purge-cron-run-log', '17 3 * * *', $$ delete from cron.job_run_details where end_time < now() - interval '1 day' $$);
-- Entity tombstones older than a week (migration 20260917000000): every rename writes one, and a device that has not
-- pulled for six days reads the account's live rows in full instead of trusting them.
select cron.schedule('purge-scheduler-tombstones', '23 3 * * *', $$ select public.purge_scheduler_tombstones() $$);
-- cron.schedule upserts by jobname, so re-running is safe; to change `t_b`, edit the schedule here and re-run
-- scripts/deploy-supabase.bat. (`t_a` and the break lengths/messages are per-account rows changed over HTTP —
-- see docs/PAUSE_CUE_DELIVERY.md — no redeploy needed.)
select cron.schedule('pause-cue-tick', '* * * * *', $$ select public.tick_pause_cues() $$);
