-- Pause-cue: the next break moves OFF the `t_a` beat onto its own event-driven row (PRD §15 /
-- ARCHITECTURE.md §8). Refines the `t_a`/`t_b` model of 20260724000000 + 20260725000000.
--
-- WHAT WAS WRONG. The governing rest pose rode the mandatory presence beat, so the server's copy of it was only
-- as fresh as the last beat. Two consequences, both real:
--   1. STALENESS RACE. The engine resamples the window on its active-session beat (30 s in production), so a
--      schedule edit that changes which break is next was up to 30 s from reaching the server. If every device
--      then went dark inside that window, `evaluate_pause_cue()` read the OLD break — wrong kind, wrong length,
--      wrong overdue verdict — and the phone was told to speak at the wrong instant (or wrongly told nothing).
--      The clean screen-off path made it worse: it calls e1 directly WITHOUT a final beat, so the row e1 reads
--      is by construction the pre-edit one.
--   2. The presence row carried a payload that has nothing to do with presence. The beat's only job is "this
--      account was alive at time T"; everything else on it was along for the ride.
--
-- THE FIX. Two rows with two different write cadences, matching their two different rates of change:
--   * `device_heartbeat` — the PRESENCE row, and now nothing else: `{ user_id, device_id }`, a server-stamped
--     `beat_at`, and the `data_payload_sent` claim flag. Written every `t_a`. The `publish_presence` RPC drops
--     its six break/kind arguments and takes only the device id.
--   * `device_break` (new) — the NEXT BREAK, written ONLY when it actually changes (`publish_next_break`).
--     Steady state is zero writes: a device sitting at its desk for an hour writes this row when a pose is
--     served or the schedule is edited, and never otherwise.
-- The break row deliberately PERSISTS through a lock/kill (it is not cleared on screen-off) — the last value a
-- device published while active is exactly the state it walked away in, which is what the cue is judged on.
--
-- WHY THIS IS NOW POSSIBLE AT ALL: `break_due_ms` IS A FIXED INSTANT. The previous shape published the pose's
-- DRAWN start, and an overdue pose's drawn start is clamped to the now-line (`maxOf(due, now)`) — a value that
-- changes at every sample, so event-driven writes would have degenerated into per-sample writes. The client now
-- publishes the pose's mathematical DUE instant `lastRest + interval` instead, which moves only when the pose is
-- served or reconfigured. That is the same rule the client's own rest-pose cue already follows (CLAUDE.md: "a
-- SLIDING restBreak trigger keys on the mathematical `due`"), so the server and the client now key on one
-- instant. Under an accelerated debug clock the client converts it to the REAL instant it is reached at
-- (`SchedulerEngine.realInstantFor`) before publishing, so `beat_at` and the due instant stay on one timeline.
--
-- THE OVERDUE GATE IS UNCHANGED IN MEANING (20260725000000): a cue fires only if the account went idle with a
-- break actually DUE. The predicate is just re-expressed on the stable input —
--     `device_break.break_due_ms <= <that device's own beat_at> + t_a slack`
-- i.e. "was this break already due at the last moment we saw this device?". Reference stays the device's own
-- `beat_at`, never the later cron `now()`, so an upcoming break whose instant merely elapses after walk-away
-- still fires nothing. Among the overdue rows the LONGEST length governs (a single device already collapsed its
-- two poses client-side; this only matters across devices).
--
-- FREE-PLAN NOTE, for the record: what Supabase meters is EGRESS (bytes the server sends back), and the beat's
-- reply is a bare integer either way — so this change is not a bandwidth optimisation and was not made as one.
-- It is a correctness fix; the smaller beat is a side benefit.
--
-- Client on-disk DBs need no migration (server-only state). DEPLOY SURFACE: Supabase (`deploy-supabase.bat`)
-- **and** every app (`account{1,2,3}-*deploy*.bat`) — `publish_presence`'s signature changed, so an un-rebuilt
-- app's beats fail against a migrated project (and a rebuilt app's beats fail against an un-migrated one).

-- ---------------------------------------------------------------------------
-- device_break — the next 5/15-min rest pose each device is waiting on. One row per device, upserted only on a
-- change. `break_due_ms` / `break_len_ms` null ⇒ the device has no rest pose configured (the row still records
-- the device and its kind).
-- ---------------------------------------------------------------------------
create table if not exists public.device_break (
    user_id      uuid not null references auth.users (id) on delete cascade,
    device_id    text not null,
    -- 'phone' / 'desktop' / … — moved off the presence row, which is now identity + time only. Written on the
    -- same rare cadence, so the "which devices does this account have" view survives at no per-beat cost.
    kind         text,
    break_kind   text,
    -- The pose's mathematical DUE instant (`lastRest + interval`), epoch millis on the REAL wall clock. NOT the
    -- drawn start: an overdue pose's drawn start rides the now-line and would change at every sample.
    break_due_ms bigint,
    break_len_ms bigint check (break_len_ms is null or break_len_ms > 0),
    updated_at   timestamptz not null default now(),
    primary key (user_id, device_id)
);

alter table public.device_break enable row level security;

drop policy if exists "own device break" on public.device_break;
create policy "own device break" on public.device_break
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- The presence row sheds everything that is not presence.
-- ---------------------------------------------------------------------------
alter table public.device_heartbeat drop column if exists next_break_kind;
alter table public.device_heartbeat drop column if exists next_break_start_ms;
alter table public.device_heartbeat drop column if exists next_break_len_ms;
alter table public.device_heartbeat drop column if exists next_break_end_ms;
alter table public.device_heartbeat drop column if exists kind;

-- ---------------------------------------------------------------------------
-- publish_next_break — the EVENT-DRIVEN write. Called only when the governing pose changes (served, rescheduled,
-- reconfigured), so an idle-but-present device makes zero of these calls. SECURITY INVOKER: the write goes
-- through the row's own-row RLS and `auth.uid()` is the only accepted owner.
--
-- Deliberately does NOT touch `device_heartbeat`: a break change is not evidence of presence (the beat alone
-- says that), and stamping `beat_at` here would let a background schedule change mask a device that went away.
-- ---------------------------------------------------------------------------
create or replace function public.publish_next_break(
    p_device_id  text,
    p_kind       text default null,
    p_break_kind text default null,
    p_break_due_ms bigint default null,
    p_break_len_ms bigint default null
)
returns void language plpgsql as $$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'publish_next_break requires an authenticated session';
    end if;

    insert into public.device_break as b (
        user_id, device_id, kind, break_kind, break_due_ms, break_len_ms, updated_at
    )
    values (uid, p_device_id, p_kind, p_break_kind, p_break_due_ms, p_break_len_ms, now())
    on conflict (user_id, device_id) do update set
        kind         = excluded.kind,
        break_kind   = excluded.break_kind,
        break_due_ms = excluded.break_due_ms,
        break_len_ms = excluded.break_len_ms,
        updated_at   = now();
end;
$$;

grant execute on function public.publish_next_break(text, text, text, bigint, bigint) to authenticated;

-- ---------------------------------------------------------------------------
-- publish_presence — THE `t_a` tick, now identity + time only. The whole request body is the device id; the
-- account comes from the JWT, the time from the server, and the reply is still `t_a` in seconds so a device
-- re-paces itself with no extra call. The old six-argument overload is dropped (see the deploy note above).
-- ---------------------------------------------------------------------------
drop function if exists public.publish_presence(text, text, text, bigint, bigint, bigint);

create or replace function public.publish_presence(p_device_id text)
returns integer language plpgsql as $$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'publish_presence requires an authenticated session';
    end if;

    insert into public.device_heartbeat as h (user_id, device_id, data_payload_sent, beat_at)
    values (uid, p_device_id, false, now())
    on conflict (user_id, device_id) do update set
        data_payload_sent = false,
        beat_at           = now();

    return public.tick_seconds_for(uid);
end;
$$;

grant execute on function public.publish_presence(text) to authenticated;

-- ---------------------------------------------------------------------------
-- The overdue predicate, factored out so the cron pre-filter and the decision cannot drift apart. True when
-- [p_user]'s account published a break that was already DUE at the publishing device's own last beat.
-- ---------------------------------------------------------------------------
create or replace function public.overdue_break_at_last_beat(p_user uuid)
returns table (break_kind text, break_len_ms bigint)
language sql stable as $$
    select b.break_kind, b.break_len_ms
      from public.device_break b
      join public.device_heartbeat h
        on h.user_id = b.user_id and h.device_id = b.device_id
     where b.user_id = p_user
       and b.break_len_ms is not null and b.break_len_ms > 0
       and b.break_due_ms is not null
       -- "Already due when we last saw this device." The `t_a` slack only absorbs client↔server clock skew.
       and b.break_due_ms <= (extract(epoch from h.beat_at) + public.tick_seconds_for(b.user_id)) * 1000
     -- Longest overdue governs: resting 15 min discharges a 5-min pose due at the same instant.
     order by b.break_len_ms desc
     limit 1;
$$;

grant execute on function public.overdue_break_at_last_beat(uuid) to service_role;

-- ---------------------------------------------------------------------------
-- evaluate_pause_cue — unchanged signature and unchanged decision; step (4) now reads the event-driven break
-- row instead of the presence row.
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

    -- (2) Already pushed for this idle episode (every row claimed) → nothing to do.
    if not exists (
        select 1 from public.device_heartbeat h
        where h.user_id = p_user_id and not h.data_payload_sent
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

    -- (4) The break the account is waiting on — from `device_break`, which holds what each device published the
    --     moment its next break last CHANGED (not what it happened to be carrying on its last beat).
    select * into v_win from public.overdue_break_at_last_beat(p_user_id);
    -- Captured now: the claim below overwrites `found`, and the claim must happen either way (step 5).
    v_has_break := found;

    -- (5) Claim BEFORE deciding whether there is anything to say, so the cron path and the screen-off path race
    --     harmlessly and an account idle with nothing due is claimed once instead of re-invoking e1 every tick.
    update public.device_heartbeat h set data_payload_sent = true
     where h.user_id = p_user_id and not h.data_payload_sent;
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
-- tick_pause_cues — the `t_b` job. Same two passes; the "did anything come due?" pre-filter now consults
-- `device_break` through the shared predicate ("if none in the past, pg_net is not used").
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
        having bool_or(not h.data_payload_sent)
           and max(h.beat_at) <= now() - make_interval(secs => 2 * public.tick_seconds_for(h.user_id))
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
