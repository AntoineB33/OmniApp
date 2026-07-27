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
-- pg_cron accepts standard cron syntax OR a sub-minute interval string, but the interval form is only
-- '[1-59] seconds' — 'N minutes' is rejected with `invalid schedule`. One minute is therefore the cron
-- form '* * * * *'; use '30 seconds' etc. only for a sub-minute `t_b`.
--
-- NOTE: this file must contain NO double-quote character anywhere, comments included. It is handed to the
-- CLI as ONE inline argument (see scripts/internal/apply-pause-cue-setup.ps1), and Windows PowerShell 5.1
-- mangles a native-command argument that embeds one — the SQL is then silently TRUNCATED, so the tail
-- statements never run while the command still reports success. apply-pause-cue-setup.ps1 hard-fails on a
-- double quote for that reason. Use backticks or single quotes when quoting in a comment.
-- cron.schedule upserts by jobname, so re-running is safe; to change `t_b`, edit the schedule here and re-run
-- scripts/deploy-supabase.bat. (`t_a` and the break lengths/messages are per-account rows changed over HTTP —
-- see docs/PAUSE_CUE_DELIVERY.md — no redeploy needed.)
select cron.schedule('pause-cue-tick', '* * * * *', $$ select public.tick_pause_cues() $$);
