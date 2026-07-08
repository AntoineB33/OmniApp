-- OmniApp pause-end voice cue — make the server-side push BEST-EFFORT (PRD §15 / ARCHITECTURE.md §8).
--
-- Apply with the Supabase CLI: `supabase db push` (or scripts/deploy-supabase.bat). Idempotent.
--
-- WHY THIS EXISTS (the bug it fixes):
-- The client's `pause_cue_schedule` / `account_last_phone` writes are AUTHORITATIVE — they must succeed offline
-- of the push infrastructure. But the AFTER-write triggers (`on_pause_cue_schedule_change`, `on_last_phone_change`)
-- and the cron (`tick_pause_cues`) call `net.http_post` (pg_net) directly. When the push infra is NOT provisioned
-- — pg_net not installed, or supabase/pause-cue-setup.sql not applied so the `app.settings.*` GUCs are unset —
-- that call RAISES inside the trigger and, because the trigger runs in the writer's transaction, the client's
-- upsert is rejected (PostgREST surfaces it as `400`). This only bites once a phone has claimed the account
-- (`account_last_phone` populated, e.g. by scripts/account1-deploy-android.bat): before that the trigger's push
-- branch is unreachable and the write succeeds. The init/immediate-push migrations documented these tables as
-- "inert until provisioned" — but "inert" was silently meaning "the write fails", not "no push goes out".
--
-- THE FIX: route every push through public.omni_edge_push(), which (1) no-ops when the edge URL GUC is unset
-- (genuinely inert until provisioned) and (2) swallows any error from net.http_post inside a subtransaction, so
-- a missing pg_net / transient net failure / bad GUC can never fail the caller's authoritative write. The push
-- stays best-effort; the once-a-minute `tick_pause_cues` cron remains the backstop when a live push is dropped.

-- Best-effort fire-and-forget POST to the `pause-cue` Edge Function. Never raises: a caller in a trigger can
-- `perform` it without risking the client write it runs inside.
create or replace function public.omni_edge_push(payload jsonb)
returns void language plpgsql security definer as $$
declare base_url text;
begin
    base_url := current_setting('app.settings.edge_base_url', true);
    -- Not provisioned (supabase/pause-cue-setup.sql not applied): stay inert instead of failing the write.
    if base_url is null or base_url = '' then
        return;
    end if;
    begin
        perform net.http_post(
            url     := base_url || '/pause-cue',
            headers := jsonb_build_object(
                'Content-Type', 'application/json',
                'Authorization', 'Bearer ' || coalesce(current_setting('app.settings.service_role_key', true), '')
            ),
            body    := payload
        );
    exception when others then
        -- pg_net missing, a transient net error, or a bad GUC must NEVER fail the client's authoritative
        -- pause_cue_schedule / account_last_phone upsert. The pause-cue tick cron is the delivery backstop.
        null;
    end;
end;
$$;

-- When the last phone changes, tell the PREVIOUS phone to cancel its scheduled cue (init migration's function,
-- now routed through the best-effort helper).
create or replace function public.on_last_phone_change()
returns trigger language plpgsql security definer as $$
begin
    if tg_op = 'UPDATE' and new.device_id is distinct from old.device_id then
        perform public.omni_edge_push(
            jsonb_build_object('user_id', new.user_id, 'device_id', old.device_id, 'action', 'cancel')
        );
    end if;
    return new;
end;
$$;

-- Every minute, push the last phone to schedule any cue due within ~1 min that it did not itself set (init
-- migration's cron function, now routed through the best-effort helper).
create or replace function public.tick_pause_cues()
returns void language plpgsql security definer as $$
declare r record;
begin
    for r in
        select s.user_id, s.due_at, p.device_id as phone_device_id
        from public.pause_cue_schedule s
        join public.account_last_phone p on p.user_id = s.user_id
        where s.due_at between now() + interval '55 seconds' and now() + interval '65 seconds'
          and s.origin_device_id is distinct from p.device_id
    loop
        perform public.omni_edge_push(
            jsonb_build_object('user_id', r.user_id, 'device_id', r.phone_device_id,
                               'action', 'schedule', 'due_at', r.due_at)
        );
    end loop;
end;
$$;

-- The immediate cross-device reschedule push (20260704 migration's function, now routed through the best-effort
-- helper). Fires the instant the schedule row changes so a backgrounded phone's stale local cue is corrected.
create or replace function public.on_pause_cue_schedule_change()
returns trigger language plpgsql security definer as $$
declare phone text;
begin
    if tg_op = 'DELETE' then
        select device_id into phone from public.account_last_phone where user_id = old.user_id;
        if phone is not null and old.origin_device_id is distinct from phone then
            perform public.omni_edge_push(
                jsonb_build_object('user_id', old.user_id, 'device_id', phone, 'action', 'cancel')
            );
        end if;
        return old;
    end if;

    -- INSERT or UPDATE: only push when the cue INSTANT actually moved, and not when the change originated on
    -- the last phone (it already scheduled the cue locally).
    if tg_op = 'UPDATE' and new.due_at is not distinct from old.due_at then
        return new;
    end if;
    select device_id into phone from public.account_last_phone where user_id = new.user_id;
    if phone is not null and new.origin_device_id is distinct from phone then
        perform public.omni_edge_push(
            jsonb_build_object('user_id', new.user_id, 'device_id', phone,
                               'action', 'schedule', 'due_at', new.due_at)
        );
    end if;
    return new;
end;
$$;
