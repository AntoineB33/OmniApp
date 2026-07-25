-- A device is associated to exactly ONE account — the last one it signed into (PRD §15 device↔account
-- exclusivity). Extends migration 20260721000000 (which enforced this for `device_push_token`) to the presence
-- pair, `device_heartbeat` + `device_break`, which it never covered.
--
-- THE LEAK. Both tables are keyed `(user_id, device_id)`, and the device id is allocated once per install
-- (`SchedulerSyncEngine.meta()` — a random UUID stored in the local DB, so it SURVIVES a sign-out). Signing out
-- of account A and into account B therefore leaves A's rows behind: nothing on the client deletes them
-- (`signOut()` only drops the cached session locally) and nothing on the server ever did either. Consequences,
-- both real:
--   * A now owns a phantom device whose `beat_at` is frozen at the sign-out instant — so A reads as permanently
--     idle. If A's leftover `device_break` row was OVERDUE at that last beat, the very next `t_b` tick hands A
--     to e1 and a **spurious "your pause is over"** is pushed to A's phones, for a device that is now B's.
--   * Even once claimed, the stale break row keeps answering `overdue_break_at_last_beat()` for A forever, so
--     the length that governs A's future cues can come from a device A no longer has.
--
-- THE FIX, server-side, exactly as for the push token: when a device writes either of its rows, drop every row
-- for the SAME `device_id` under a DIFFERENT `user_id`. SECURITY DEFINER because the cross-account cleanup has
-- to bypass RLS (the signing-in user cannot see — let alone delete — account A's row).
--
-- WHY DELETING IS SAFE (reconstructibility rule, CLAUDE.md). Neither row is user data: the presence row is
-- re-created by the next `t_a` beat, and the break row by the next `publish_next_break`. Switching B → A simply
-- re-creates A's pair. For the break row that re-creation is not automatic — it is written only on a CHANGE —
-- so the client now force-republishes it on every inactive→active transition (which includes every sign-in);
-- see `DeviceHeartbeatPublisher`, whose `PresenceTickTest` covers it. Without that the account switched back to
-- would have a presence row but no break row, and would never be owed a cue until the pose next moved.
--
-- LIMITATION (deliberate, and unfixable at this layer). This keys on the device id, so it only heals a device
-- that KEPT its identity — i.e. a sign-out/sign-in inside one install. A device whose local DB was wiped
-- (`account{1,2}-deploy-android.bat` reinstalls to wipe data) comes back with a NEW random device id, so its
-- old rows are no longer attributable to it and survive as orphans under whichever account they were written
-- for. Those are what the account-empty scripts clear (`account_db_admin.py empty` deletes both tables).
--
-- Idempotent; apply with scripts/deploy-supabase.bat (supabase db push). Server-only: no client rebuild is
-- required for THIS migration (the republish-on-activation change ships with the app either way).

create or replace function public.device_presence_unique_owner()
returns trigger language plpgsql security definer as $$
begin
    -- Both tables are cleaned from either trigger, so a device that beats before it publishes a break (or vice
    -- versa) still evicts the whole of its previous account's pair on the first write.
    delete from public.device_heartbeat
     where device_id = new.device_id and user_id <> new.user_id;
    delete from public.device_break
     where device_id = new.device_id and user_id <> new.user_id;
    return new;
end;
$$;

drop trigger if exists device_heartbeat_unique_owner on public.device_heartbeat;
create trigger device_heartbeat_unique_owner
    before insert or update on public.device_heartbeat
    for each row execute function public.device_presence_unique_owner();

drop trigger if exists device_break_unique_owner on public.device_break;
create trigger device_break_unique_owner
    before insert or update on public.device_break
    for each row execute function public.device_presence_unique_owner();

-- Heal projects that already accumulated cross-account duplicates: for any device id held by more than one
-- account, keep only the account whose heartbeat is the most recent (that IS "the last account it got logged
-- to") and drop the rest, break rows included.
with newest as (
    select distinct on (device_id) device_id, user_id
      from public.device_heartbeat
     order by device_id, beat_at desc
)
delete from public.device_heartbeat h
 using newest n
 where h.device_id = n.device_id and h.user_id <> n.user_id;

with newest as (
    select distinct on (device_id) device_id, user_id
      from public.device_heartbeat
     order by device_id, beat_at desc
)
delete from public.device_break b
 using newest n
 where b.device_id = n.device_id and b.user_id <> n.user_id;
