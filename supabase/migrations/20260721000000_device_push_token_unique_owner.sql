-- A physical device's push token belongs to exactly ONE account at a time (PRD §15 device↔account exclusivity).
--
-- The pause-cue Edge Function fans a cue out to EVERY `device_push_token` row of the account that just went
-- no-screen (`device_id = '*'`). An FCM/APNs registration token is globally unique to one app install, so if a
-- device signs out of account A and into account B its OLD row `(A, deviceId, token)` would linger — and A would
-- keep pushing its "pause is over" cue to a phone that now belongs to B. That is the "device associated with more
-- than one account" leak.
--
-- Enforce it server-side (a client can't delete another account's row — RLS only grants own-row access). When a
-- device (re)registers its token, drop any OTHER row holding the SAME token. The token is routing data, not user
-- data: the device re-registers under whichever account it is signed into (startup / foreground), so switching
-- A → B → A simply re-creates A's row — no user data is lost (see CLAUDE.md: reconstructibility rule).
--
-- Idempotent; apply with scripts/deploy-supabase.bat (supabase db push).

create or replace function public.device_push_token_unique_owner()
returns trigger language plpgsql security definer as $$
begin
    -- SECURITY DEFINER so the cross-account cleanup bypasses RLS (the registering user can't see A's row).
    delete from public.device_push_token
    where token = new.token
      and not (user_id = new.user_id and device_id = new.device_id);
    return new;
end;
$$;

drop trigger if exists device_push_token_unique_owner on public.device_push_token;
create trigger device_push_token_unique_owner
    before insert or update on public.device_push_token
    for each row execute function public.device_push_token_unique_owner();
