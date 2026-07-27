-- Pause-cue: the two published rows are reduced to EXACTLY what the spec says they carry (PRD §15 /
-- ARCHITECTURE.md §8). Refines 20260724000000 → 20260726000000 → 20260727000000.
--
-- THE SPEC, restated. There are two client→server writes in the pause-cue path, and each row holds one thing:
--   * the BREAK row — "the scheduled time of apparition of both screen breaks": the account, plus the 5-min and
--     the 15-min pose's due instant. Written when the app calculates them (i.e. when they CHANGE), nothing else.
--   * the PRESENCE row — "the account, the time of the upsert and the device", written every `t_a` while the
--     device is unlocked + signed in; and, in the same call, the claim flag for the account, `false`.
--
-- WHAT CHANGES, AND WHY EACH CHANGE IS SOUND.
--
-- 1. `device_break` becomes ACCOUNT-KEYED and carries BOTH dues instead of one device's governing pose.
--    The dues are `lastRest + interval` over the screen-break config, and both of those inputs are part of the
--    SYNCED authoritative state — so every device of an account computes the SAME two instants. Keying the row
--    per device therefore stored N copies of one account-level fact and forced the server to re-do, across
--    rows, the "longest overdue governs" selection each client had already done locally. One row, two columns,
--    last writer wins: same information, no cross-device selection, and the row can no longer be orphaned by a
--    device that never comes back (see 5).
--    The selection rule is UNCHANGED in meaning, just relocated: among the dues that were already overdue at
--    the last beat, the LONGEST break governs (resting 15 min discharges a 5-min pose due at the same instant),
--    and the length now comes from `break_config` / the kind's default rather than from the client.
--
-- 2. The break row sheds `device_id`, `kind`, `break_kind` and `break_len_ms`.
--    * `device_id` — see 1.
--    * `kind` ('phone'/'desktop') was never read by anything here; the calendar's "which devices were open"
--      bubble reads `device_active_session.kind`, a different table on a different path.
--    * `break_kind` is now implied by WHICH column the due sits in.
--    * `break_len_ms` — the cue instant is `t2 + length`, and the length is a property of the break KIND, which
--      the server already owns (`break_config.length_ms`, HTTP-changeable, falling back to 5/15 min). The
--      client no longer gets a say. **Operational consequence, deliberate:** the debug fast-break knobs
--      (`OMNIAPP_BREAK_DURATION_MS`, `account*-fast-break.bat`) used to shrink the cue delay by riding
--      `break_len_ms`; they no longer reach the server, so those scripts now set `break_config.length_ms` for
--      the account instead (`account_db_admin.py break-length`). Production timings are unaffected.
--
-- 3. The claim flag moves OUT of the presence row into its own account-keyed table, `data_payload_sent`.
--    It was always an ACCOUNT-level fact — "has this idle episode already been pushed?" — that happened to be
--    stored once per device and had to be read/written with `bool_or(...)` / "update every unclaimed row".
--    One row per account says the same thing directly, and it keeps the presence row to the three fields the
--    spec names. `publish_presence` upserts it back to `false` in the same statement as the beat, so a
--    returning device re-arms the next episode exactly as before.
--
-- 4. `publish_presence` still does BOTH upserts in ONE call. The spec describes two rows, not two round trips;
--    doing them in one function keeps them atomic (a beat that armed the claim but lost the presence row, or
--    vice versa, would be a half-state the evaluator could read) and keeps the steady-state cost at one request
--    per `t_a` per device.
--
-- 5. The device↔account exclusivity trigger (20260727000000) adapts. `device_break` no longer has a
--    `device_id`, so it cannot be evicted by device id — instead, when a device's beat evicts another account's
--    presence rows, any account left with NO presence rows at all also loses its break + claim rows. That is
--    strictly the same protection: an account with no presence row can never be evaluated (both the cron's
--    `group by` over `device_heartbeat` and `evaluate_pause_cue`'s `max(beat_at)` find nothing), so the
--    leftovers were already inert; deleting them just stops them lingering.
--
-- RECONSTRUCTIBILITY (CLAUDE.md). Both tables are re-derivable server state, never user data: the presence row
-- is re-created by the next `t_a` beat and the break row by the next `publish_next_break` — which the client
-- force-republishes on every inactive→active transition. So this migration DROPS and re-creates `device_break`
-- rather than migrating its rows; the first beat after the deploy restores the account's state.
--
-- DEPLOY SURFACE: Supabase (`scripts/deploy-supabase.bat`) **and** every app (`account{1,2,3}-*deploy*.bat`) —
-- `publish_next_break`'s signature changed, so an un-rebuilt app's break writes fail against a migrated project
-- and vice versa. Client on-disk DBs need no migration (all of this is server-only state).

-- ---------------------------------------------------------------------------
-- device_break — one row per ACCOUNT: when each of the two screen breaks is next due. Both columns are epoch
-- millis on the REAL wall clock, and each is the pose's mathematical DUE instant (`lastRest + interval`), NOT
-- its drawn start — an overdue pose's drawn start is clamped to the now-line (`maxOf(due, now)`) and would
-- change at every sample, which is what would force this back onto the beat. A column is null when the account
-- has no such pose configured (or has never anchored one, so no due instant is known yet).
-- ---------------------------------------------------------------------------
drop table if exists public.device_break cascade;

create table public.device_break (
    user_id            uuid primary key references auth.users (id) on delete cascade,
    break_5min_due_ms  bigint,
    break_15min_due_ms bigint,
    updated_at         timestamptz not null default now()
);

alter table public.device_break enable row level security;

drop policy if exists "own device break" on public.device_break;
create policy "own device break" on public.device_break
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- data_payload_sent — the claim flag, one row per account: has the current idle episode already been handed to
-- the phone? Set to `true` by `evaluate_pause_cue()` when it claims an episode, back to `false` by every
-- presence beat (a device that is back at its screen re-arms the next episode).
-- ---------------------------------------------------------------------------
create table if not exists public.data_payload_sent (
    user_id           uuid primary key references auth.users (id) on delete cascade,
    data_payload_sent boolean not null default false,
    updated_at        timestamptz not null default now()
);

alter table public.data_payload_sent enable row level security;

drop policy if exists "own data payload sent" on public.data_payload_sent;
create policy "own data payload sent" on public.data_payload_sent
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- The presence row is now exactly {account, device, time of upsert}.
alter table public.device_heartbeat drop column if exists data_payload_sent;

-- ---------------------------------------------------------------------------
-- publish_next_break — the EVENT-DRIVEN write, called when the app (re)calculates the two breaks. The account
-- comes from the JWT, so the whole body is the two due instants. SECURITY INVOKER: the write goes through the
-- row's own-row RLS.
--
-- Deliberately does NOT touch `device_heartbeat`: a break change is not evidence of presence (the beat alone
-- says that), and stamping `beat_at` here would let a background schedule change mask a device that went away.
-- ---------------------------------------------------------------------------
drop function if exists public.publish_next_break(text, text, text, bigint, bigint);

create or replace function public.publish_next_break(
    p_break_5min_due_ms  bigint default null,
    p_break_15min_due_ms bigint default null
)
returns void language plpgsql as $$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'publish_next_break requires an authenticated session';
    end if;

    insert into public.device_break as b (user_id, break_5min_due_ms, break_15min_due_ms, updated_at)
    values (uid, p_break_5min_due_ms, p_break_15min_due_ms, now())
    on conflict (user_id) do update set
        break_5min_due_ms  = excluded.break_5min_due_ms,
        break_15min_due_ms = excluded.break_15min_due_ms,
        updated_at         = now();
end;
$$;

grant execute on function public.publish_next_break(bigint, bigint) to authenticated;

-- ---------------------------------------------------------------------------
-- publish_presence — THE `t_a` tick. Upserts the presence row {account, device, server-stamped time} and, in
-- the same statement pair, re-arms the account's claim row to `false`. The reply is still `t_a` in seconds so a
-- device re-paces itself with no extra call.
-- ---------------------------------------------------------------------------
create or replace function public.publish_presence(p_device_id text)
returns integer language plpgsql as $$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'publish_presence requires an authenticated session';
    end if;

    insert into public.device_heartbeat as h (user_id, device_id, beat_at)
    values (uid, p_device_id, now())
    on conflict (user_id, device_id) do update set beat_at = now();

    insert into public.data_payload_sent as c (user_id, data_payload_sent, updated_at)
    values (uid, false, now())
    on conflict (user_id) do update set data_payload_sent = false, updated_at = now();

    return public.tick_seconds_for(uid);
end;
$$;

grant execute on function public.publish_presence(text) to authenticated;

-- ---------------------------------------------------------------------------
-- The overdue predicate, shared by the cron pre-filter and the decision so they cannot drift apart. True when
-- one of the account's two breaks was ALREADY DUE at the last moment any of its devices was seen.
--
-- Reference is the account's own newest `beat_at`, never the later cron `now()` — otherwise an UPCOMING break
-- whose instant merely elapsed after the walk-away would fire a cue the user never earned. The `t_a` slack
-- absorbs client↔server clock skew only. Idleness itself is judged account-wide (`max(beat_at)`), so the
-- reference here is account-wide too, which is what lets the break row be account-keyed at all.
--
-- Returns the KIND and the fallback length; the longest overdue break wins.
-- ---------------------------------------------------------------------------
create or replace function public.overdue_break_at_last_beat(p_user uuid)
returns table (break_kind text, break_len_ms bigint)
language sql stable as $$
    select v.break_kind, v.default_len_ms
      from public.device_break b
     cross join lateral (values
         ('15min_break', b.break_15min_due_ms, 900000::bigint),
         ('5min_break',  b.break_5min_due_ms,  300000::bigint)
     ) as v(break_kind, due_ms, default_len_ms)
     where b.user_id = p_user
       and v.due_ms is not null
       and v.due_ms <= (
           extract(epoch from (
               select max(h.beat_at) from public.device_heartbeat h where h.user_id = p_user
           )) + public.tick_seconds_for(p_user)
       ) * 1000
     -- Longest overdue governs: resting 15 min discharges a 5-min pose due at the same instant.
     order by v.default_len_ms desc
     limit 1;
$$;

grant execute on function public.overdue_break_at_last_beat(uuid) to service_role;

-- ---------------------------------------------------------------------------
-- evaluate_pause_cue — unchanged signature, unchanged decision. Steps (2) and (5) now read/claim the account's
-- own `data_payload_sent` row instead of scanning every presence row, and step (4)'s length falls back to the
-- kind's default (the client no longer publishes one).
-- ---------------------------------------------------------------------------
create or replace function public.evaluate_pause_cue(p_user_id uuid, p_exclude_device text default null)
returns table (
    due_at        timestamptz,
    break_kind    text,
    break_len_ms  bigint,
    voice_cue     text,
    voice_message text
)
language plpgsql security definer as $$
declare
    v_ta         integer;
    v_idle_since timestamptz;
    v_t2         timestamptz;
    v_win        record;
    v_has_break  boolean;
    v_cfg        record;
    v_kind       text;
    v_len        bigint;
    v_due        timestamptz;
begin
    v_ta := public.tick_seconds_for(p_user_id);

    -- (1) Account-wide liveness: any device (other than the one reporting its own screen-off) seen within 2*t_a.
    if exists (
        select 1 from public.device_heartbeat h
        where h.user_id = p_user_id
          and (p_exclude_device is null or h.device_id <> p_exclude_device)
          and h.beat_at > now() - make_interval(secs => 2 * v_ta)
    ) then
        return;
    end if;

    -- (2) Already pushed for this idle episode → nothing to do.
    if not exists (
        select 1 from public.data_payload_sent c
        where c.user_id = p_user_id and not c.data_payload_sent
    ) then
        return;
    end if;

    -- (3) Deliberately away.
    if public.account_is_sleeping(p_user_id) then
        return;
    end if;

    select max(h.beat_at) into v_idle_since from public.device_heartbeat h where h.user_id = p_user_id;
    if v_idle_since is null then
        return;
    end if;
    v_t2 := v_idle_since + make_interval(secs => v_ta / 2.0);

    -- (4) The break the account is waiting on — from `device_break`, which holds the two due instants the app
    --     published the last time it calculated them (not whatever a beat happened to be carrying).
    select * into v_win from public.overdue_break_at_last_beat(p_user_id);
    -- Captured now: the claim below overwrites `found`, and the claim must happen either way (step 5).
    v_has_break := found;

    -- (5) Claim BEFORE deciding whether there is anything to say, so the cron path and the screen-off path race
    --     harmlessly and an account idle with nothing due is claimed once instead of re-invoking e1 every tick.
    update public.data_payload_sent c set data_payload_sent = true, updated_at = now()
     where c.user_id = p_user_id and not c.data_payload_sent;
    if not found then
        return;
    end if;

    -- No OVERDUE break (idle with nothing due): claimed above, nothing owed.
    if not v_has_break then
        return;
    end if;

    v_kind := coalesce(v_win.break_kind, '15min_break');
    select c.length_ms, c.voice_cue, c.voice_message into v_cfg
      from public.break_config c
     where c.user_id = p_user_id and c.break_kind = v_kind;

    -- The length is the server's to decide now: `break_config` if set, else the kind's own duration.
    v_len := coalesce(v_cfg.length_ms, v_win.break_len_ms,
                      case v_kind when '5min_break' then 300000 else 900000 end);
    v_due := v_t2 + make_interval(secs => v_len / 1000.0);

    insert into public.pause_cue_schedule (user_id, due_at, break_kind, break_len_ms, pushed_at)
    values (p_user_id, v_due, v_kind, v_len, now())
    on conflict (user_id) do update
        set due_at = excluded.due_at,
            break_kind = excluded.break_kind,
            break_len_ms = excluded.break_len_ms,
            pushed_at = now();

    due_at        := v_due;
    break_kind    := v_kind;
    break_len_ms  := v_len;
    voice_cue     := coalesce(v_cfg.voice_cue, 'pause_over');
    voice_message := coalesce(
        v_cfg.voice_message,
        case v_kind
            when '5min_break' then 'Your 5 minute pause is over. You can resume your work.'
            else 'Your 15 minute pause is over. You can resume your work.'
        end);
    return next;
end;
$$;

revoke all on function public.evaluate_pause_cue(uuid, text) from public, anon, authenticated;
grant execute on function public.evaluate_pause_cue(uuid, text) to service_role;

-- ---------------------------------------------------------------------------
-- tick_pause_cues — the `t_b` job. Same two passes; the "already claimed?" test now consults the account's own
-- claim row ("if none in the past, pg_net is not used" still holds via the overdue pre-filter).
-- ---------------------------------------------------------------------------
create or replace function public.tick_pause_cues()
returns void language plpgsql security definer as $$
declare
    r record;
begin
    -- (a) Idle accounts that are unclaimed AND had a break OVERDUE at the last beat → let e1 decide/claim/push.
    for r in
        select h.user_id
          from public.device_heartbeat h
         group by h.user_id
        having max(h.beat_at) <= now() - make_interval(secs => 2 * public.tick_seconds_for(h.user_id))
           and exists (
               select 1 from public.data_payload_sent c
                where c.user_id = h.user_id and not c.data_payload_sent)
           and not public.account_is_sleeping(h.user_id)
           and exists (select 1 from public.overdue_break_at_last_beat(h.user_id))
    loop
        perform public.omni_edge_push(jsonb_build_object(
            'user_id', r.user_id, 'device_id', '*', 'action', 'evaluate'));
    end loop;

    -- (b) An account that became active again while a pushed cue is still in the future → cancel it.
    for r in
        select s.user_id
          from public.pause_cue_schedule s
         where s.pushed_at is not null
           and s.due_at > now()
           and exists (
               select 1 from public.device_heartbeat h
                where h.user_id = s.user_id
                  and h.beat_at > now() - make_interval(secs => 2 * public.tick_seconds_for(s.user_id)))
    loop
        perform public.omni_edge_push(jsonb_build_object(
            'user_id', r.user_id, 'device_id', '*', 'action', 'cancel'));
        delete from public.pause_cue_schedule where user_id = r.user_id;
    end loop;
end;
$$;

-- ---------------------------------------------------------------------------
-- Device↔account exclusivity (20260727000000), adapted to the account-keyed break row. A device that beats for
-- a new account still evicts its rows under every other account; the break + claim rows, which no longer carry
-- a device id, follow any account thereby left with NO presence rows at all — such an account can never be
-- evaluated (the cron groups over `device_heartbeat`, and `evaluate_pause_cue` needs `max(beat_at)`), so this
-- is hygiene rather than a behaviour change, but it stops orphans accumulating.
-- ---------------------------------------------------------------------------
drop trigger if exists device_break_unique_owner on public.device_break;

create or replace function public.device_presence_unique_owner()
returns trigger language plpgsql security definer as $$
declare
    evicted uuid[];
    u       uuid;
begin
    -- Collected into an array rather than looped over directly: a `FOR ... IN <query>` opens a cursor, and
    -- Postgres rejects a data-modifying CTE inside one. A plain `select ... into` is an ordinary statement.
    with dropped as (
        delete from public.device_heartbeat
         where device_id = new.device_id and user_id <> new.user_id
        returning user_id
    )
    select coalesce(array_agg(distinct user_id), '{}'::uuid[]) into evicted from dropped;

    foreach u in array evicted loop
        if not exists (select 1 from public.device_heartbeat h where h.user_id = u) then
            delete from public.device_break where user_id = u;
            delete from public.data_payload_sent where user_id = u;
        end if;
    end loop;
    return new;
end;
$$;

drop trigger if exists device_heartbeat_unique_owner on public.device_heartbeat;
create trigger device_heartbeat_unique_owner
    before insert or update on public.device_heartbeat
    for each row execute function public.device_presence_unique_owner();

-- Heal projects that already accumulated orphans: any break/claim row whose account has no presence row left.
delete from public.device_break b
 where not exists (select 1 from public.device_heartbeat h where h.user_id = b.user_id);
delete from public.data_payload_sent c
 where not exists (select 1 from public.device_heartbeat h where h.user_id = c.user_id);
