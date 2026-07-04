-- OmniApp pause-end voice cue — immediate cross-device reschedule push (PRD §15 / ARCHITECTURE.md §8).
--
-- Apply with the Supabase CLI: `supabase db push` (or scripts/deploy-supabase.bat). Idempotent, so re-applying
-- is safe. Depends on pg_net + the `app.settings.*` GUCs installed by supabase/pause-cue-setup.sql (same as the
-- `on_last_phone_change` / `tick_pause_cues` functions in 20260703000000_init.sql).
--
-- WHY THIS EXISTS (the gap it closes):
-- `tick_pause_cues()` only pushes the last phone ~1 min BEFORE a cue is due. That is too late when a NON-phone
-- device (the desktop) POSTPONES the next pause-end: a backgrounded/killed phone still holds its OS alarm at the
-- OLD, earlier instant and would speak "your pause is over" early, before the cron corrected it. So the moment
-- the `pause_cue_schedule` row changes we push the last phone immediately to cancel the stale local cue and
-- schedule the new one. A single `action:'schedule'` push does both — the phone's local cue is a single
-- replace-by-id slot (Android: one PendingIntent request code; iOS: one notification id `omniapp-pause-cue`).
--
-- This is scenario #2 ("a change on the COMPUTER that postpones the next cue → the server tells the phone to
-- cancel + reschedule"). Scenario #1 (phone-origin change) is handled locally with no server round-trip;
-- scenario #3 (a new phone becomes the active one) is handled by `on_last_phone_change` cancelling the previous
-- phone. `tick_pause_cues()` is kept as a backstop (e.g. a phone that registered its token after the write).
--
-- As with the cron, the push is SKIPPED when the schedule's origin IS the last phone — that phone already
-- scheduled the cue locally, so a server push would be redundant.

create or replace function public.on_pause_cue_schedule_change()
returns trigger language plpgsql security definer as $$
declare phone text;
begin
    if tg_op = 'DELETE' then
        -- No upcoming pose: tell the last phone to cancel its pending local cue — unless the phone itself is
        -- the origin that last set (and, being the active device, already cleared) the schedule locally.
        select device_id into phone from public.account_last_phone where user_id = old.user_id;
        if phone is not null and old.origin_device_id is distinct from phone then
            perform net.http_post(
                url     := current_setting('app.settings.edge_base_url', true) || '/pause-cue',
                headers := jsonb_build_object('Content-Type', 'application/json',
                                              'Authorization', 'Bearer ' || current_setting('app.settings.service_role_key', true)),
                body    := jsonb_build_object('user_id', old.user_id, 'device_id', phone, 'action', 'cancel')
            );
        end if;
        return old;
    end if;

    -- INSERT or UPDATE: only push when the cue INSTANT actually moved (skip a no-op re-upsert of the same
    -- due_at) and the change did NOT originate on the last phone (which already scheduled it locally).
    if tg_op = 'UPDATE' and new.due_at is not distinct from old.due_at then
        return new;
    end if;
    select device_id into phone from public.account_last_phone where user_id = new.user_id;
    if phone is not null and new.origin_device_id is distinct from phone then
        perform net.http_post(
            url     := current_setting('app.settings.edge_base_url', true) || '/pause-cue',
            headers := jsonb_build_object('Content-Type', 'application/json',
                                          'Authorization', 'Bearer ' || current_setting('app.settings.service_role_key', true)),
            body    := jsonb_build_object('user_id', new.user_id, 'device_id', phone,
                                          'action', 'schedule', 'due_at', new.due_at)
        );
    end if;
    return new;
end;
$$;

drop trigger if exists on_pause_cue_schedule_change on public.pause_cue_schedule;
create trigger on_pause_cue_schedule_change
    after insert or update or delete on public.pause_cue_schedule
    for each row execute function public.on_pause_cue_schedule_change();
