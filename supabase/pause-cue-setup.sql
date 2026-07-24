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

-- Pause-cue tick cron — this is **`t_b`** (migration 20260724000000): every minute, one fast grouped query over
-- the presence table (`device_heartbeat`) hands each idle account to the `pause-cue` Edge Function, which owns
-- the decision. `t_b` is the *detection* granularity only: the cue instant itself is computed from the
-- server-stamped presence time (`t2 = beat_at + t_a/2`), so it does not drift with which tick noticed — and a
-- clean screen-off short-circuits the wait by calling the Edge Function directly.
--
-- pg_cron on Supabase accepts both cron syntax and an interval string like '10 seconds' (pg_cron ≥ 1.5).
-- cron.schedule upserts by jobname, so re-running is safe; to change `t_b`, edit the interval here and re-run
-- scripts/deploy-supabase.bat. (`t_a` and the break lengths/messages are per-account rows changed over HTTP —
-- see docs/PAUSE_CUE_DELIVERY.md — no redeploy needed.)
select cron.schedule('pause-cue-tick', '1 minute', $$ select public.tick_pause_cues() $$);
