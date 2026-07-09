-- Move the pause-cue push config (Edge Function base URL + service-role key) from database-level GUCs to
-- Vault secrets.
--
-- WHY: pause-cue-setup.sql provisioned the config with `alter database postgres set app.settings.* = ...`,
-- but on Postgres 15+ setting a custom GUC at the database level is superuser-only (pg_parameter_acl), and
-- Supabase gives no superuser — the deploy failed with `42501: permission denied to set parameter`. Vault is
-- the supported replacement: the `postgres` role can upsert/read `vault.secrets` over the same
-- `supabase db query --linked` connection, and the key is encrypted at rest instead of sitting in
-- pg_db_role_setting. pause-cue-setup.sql now writes the secrets `omni_edge_base_url` /
-- `omni_service_role_key`; this migration teaches the one reader, omni_edge_push, to use them.
--
-- The old GUCs remain as a fallback so an environment that had them set keeps pushing until re-provisioned.
-- Behavior is otherwise identical to the 20260708 version: best-effort, never raises into the caller's write,
-- inert (returns) while unprovisioned.

create or replace function public.omni_edge_push(payload jsonb)
returns void language plpgsql security definer as $$
declare
    base_url text;
    role_key text;
begin
    begin
        select decrypted_secret into base_url from vault.decrypted_secrets where name = 'omni_edge_base_url';
        select decrypted_secret into role_key from vault.decrypted_secrets where name = 'omni_service_role_key';
    exception when others then
        -- Vault absent (self-hosted without the extension): fall through to the GUCs.
        base_url := null;
        role_key := null;
    end;
    if base_url is null or base_url = '' then
        base_url := current_setting('app.settings.edge_base_url', true);
        role_key := current_setting('app.settings.service_role_key', true);
    end if;
    -- Not provisioned (supabase/pause-cue-setup.sql not applied): stay inert instead of failing the write.
    if base_url is null or base_url = '' then
        return;
    end if;
    begin
        perform net.http_post(
            url     := base_url || '/pause-cue',
            headers := jsonb_build_object(
                'Content-Type', 'application/json',
                'Authorization', 'Bearer ' || coalesce(role_key, '')
            ),
            body    := payload
        );
    exception when others then
        -- pg_net missing, a transient net error, or bad config must NEVER fail the client's authoritative
        -- pause_cue_schedule / account_last_phone upsert. The pause-cue tick cron is the delivery backstop.
        null;
    end;
end;
$$;
