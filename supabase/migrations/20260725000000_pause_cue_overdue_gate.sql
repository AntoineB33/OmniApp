-- Pause-cue OVERDUE gate (PRD §15 / ARCHITECTURE.md §8). Refines the `t_a`/`t_b` model of migration
-- 20260724000000: the pause-end cue must fire ONLY when the account went idle with a rest break actually DUE
-- (the user walked away *to take* the break), never when it merely went idle with no break owed (a lunch break,
-- an errand). Before this, `evaluate_pause_cue()` picked the soonest-ENDING published window and fired
-- `t2 + length` unconditionally, so leaving the desk with the next pose still 40 min out spoke "your pause is
-- over" anyway.
--
-- WHY THIS IS PURELY A SERVER CHANGE. The client already publishes exactly what is needed on every `t_a` beat:
--   * an OVERDUE / dragged pose rides the now-line — `nextRestPoseWindowMillis` clamps its start to the REAL
--     wall clock, so the row's `next_break_start_ms ≈ beat_at`;
--   * a not-yet-due pose keeps its FIXED future start, so `next_break_start_ms > beat_at` by its lead time;
--   * when several poses are overdue at once the LONGEST already governs the published length (client-side
--     "longest-overdue governs", PRD §15 / RestPosePresenceWindowTest) — so the published `next_break_len_ms`
--     is already `max(5min_break_length, 15min_break_length)` for that device.
-- The row also PERSISTS after the screen goes off (the publisher stops ticking but does not delete it), so the
-- last active beat's window is still there for the cron to read. Hence the whole decision is: "was the published
-- window OVERDUE at the device's last beat?" — a server-only predicate.
--
-- THE OVERDUE PREDICATE. `next_break_start_ms <= <the row's own beat_at> (+ t_a slack)`. Reference is the
-- device's last-seen instant (`beat_at`), NOT the later cron `now()`: "dragged by the now-line" means the client
-- was actively pinning the start to now WHILE STILL BEATING. Comparing to cron `now()` would additionally fire
-- for an upcoming break whose fixed start merely elapsed after walk-away (never dragged). The `+ t_a` slack only
-- absorbs client↔server clock skew (phones are NTP-synced; the client stamps its real clock, the server stamps
-- `beat_at` a hair later, so `start <= beat_at` already holds — the slack is belt-and-suspenders and, at worst,
-- treats a break due within `t_a` AFTER walk-away as taken, a negligible boundary).
--
-- THE `max(5min,15min)` RULE, server side. `evaluate_pause_cue()` now selects the LONGEST overdue window across
-- ALL the account's rows (so two devices each overdue for a different pose still yield the longer). A single
-- device already collapsed its two poses to the longer one client-side, so this only matters multi-device.
--
-- Client on-disk DBs need no migration: everything here is server-only and never part of the synced snapshot.
-- Deploy surface: Supabase only (`deploy-supabase.bat`); no app rebuild.

-- ---------------------------------------------------------------------------
-- evaluate_pause_cue — unchanged signature; only step (4) (window selection) gains the overdue gate. The atomic
-- claim (step 5) stays, so the cron path and the screen-off path still race harmlessly and an idle account with
-- NO overdue break is claimed once (never re-invoking e1 every tick) yet owes no cue.
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

    -- (4) The break the account is waiting on: among the OVERDUE windows only (drawn start already in the past
    --     at that device's last beat = "still dragged by the now-line"), the LONGEST governs. A device that went
    --     idle with NO break due published only a future-start (upcoming) window, which is excluded here — that
    --     is not a pause the user is taking, so it yields no cue.
    select h.next_break_kind, h.next_break_len_ms
      into v_win
    from public.device_heartbeat h
    where h.user_id = p_user_id
      and h.next_break_len_ms is not null and h.next_break_len_ms > 0
      and h.next_break_start_ms is not null
      and h.next_break_start_ms <= (extract(epoch from h.beat_at) + v_ta) * 1000
    order by h.next_break_len_ms desc
    limit 1;

    -- (5) Claim BEFORE deciding whether there is anything to say (so a concurrent tick / screen-off call that got
    --     there first leaves nothing to update and pushes nothing), and so an overdue-less idle account is
    --     claimed once instead of re-invoking e1 on every tick.
    update public.device_heartbeat h set data_payload_sent = true
     where h.user_id = p_user_id and not h.data_payload_sent;
    if not found then
        return;
    end if;

    -- No OVERDUE break window (the account went idle with nothing due, or published only upcoming poses):
    -- claimed above, nothing owed.
    if v_win.next_break_len_ms is null then
        return;
    end if;

    v_kind := coalesce(v_win.next_break_kind, '15min_break');
    select c.length_ms, c.voice_cue, c.voice_message into v_cfg
      from public.break_config c
     where c.user_id = p_user_id and c.break_kind = v_kind;

    v_len := coalesce(v_cfg.length_ms, v_win.next_break_len_ms,
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
-- tick_pause_cues — the `t_b` job. Adds the "if none in the past, pg_net is not used" optimisation: an idle
-- account is handed to e1 only when at least one of its rows published an OVERDUE break window. (e1 →
-- evaluate_pause_cue re-checks and claims, so this is a cheap pre-filter, not the authority.) Cancel pass (b) is
-- unchanged.
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
           and bool_or(
                   h.next_break_start_ms is not null
                   and h.next_break_start_ms <=
                       (extract(epoch from h.beat_at) + public.tick_seconds_for(h.user_id)) * 1000)
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
