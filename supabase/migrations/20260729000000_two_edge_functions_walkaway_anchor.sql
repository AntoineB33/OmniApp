-- Pause-cue delivery: the two paths get their OWN Edge Function, and the clean-lock path anchors the cue at the
-- WALK-AWAY instant it actually knows (PRD §15 / ARCHITECTURE.md §8). Refines 20260724000000 → 20260728000000.
--
-- THE SPEC, restated. There are exactly two ways the account's phones learn a pause has begun, and they differ
-- in WHO decides:
--
--   * THE CLEAN LOCK (the app is alive). The device is listening to the OS lock event, so at the instant the
--     screen goes off it sends one HTTP request to **e1** (`pause-cue`) — "the Edge Function that decides
--     whether a data payload must be sent". e1 decides (is the account really idle? is a break overdue? has this
--     episode already been claimed?), computes `break_length`, tells the phones to schedule the vocal message at
--     **the current instant + break_length**, and sets `data_payload_sent = true`.
--
--   * THE DIRTY KILL (the phone "exploded"). No such request is ever sent. The `t_b` cron instead finds an
--     account whose presence rows are ALL as old as or older than `2 * t_a`, and hands it to **e2**
--     (`pause-cue-cron`) over pg_net. e2 does not re-decide: it directly computes `break_length`, sends the data
--     payload and sets `data_payload_sent = true`.
--
-- WHAT CHANGES, AND WHY EACH CHANGE IS SOUND.
--
-- 1. THE CLEAN-LOCK CUE IS ANCHORED AT `now()`, NOT AT `max(beat_at) + t_a/2`.
--    `t2 = <presence row time> + t_a/2` is an ESTIMATE — the expected midpoint of the tick interval a device
--    silently vanished in — and it exists precisely because the cron path never observes the walk-away. The
--    clean-lock path does observe it: the request IS the lock event, so `now()` at e1 is the walk-away instant
--    (modulo one network hop) and the estimate can only add error, up to ±t_a/2 in either direction. The cron
--    path keeps `t2` unchanged, so `t_b` remains detection latency only and never moves the cue. This is a
--    refinement of the "do NOT re-anchor to now at DETECTION time" rule, not a reversal of it: detection time
--    and walk-away time are the same instant on this path alone.
--
-- 2. THE CRON CALLS ITS OWN EDGE FUNCTION, WHICH DOES NOT RE-DECIDE (`claim_pause_cue`, below).
--    The cron's `having` clause is the decision (idle for `2 * t_a`, unclaimed, not sleeping, a break overdue at
--    the last beat), so e2 only claims + computes + pushes. The claim stays CONDITIONAL (`where not
--    data_payload_sent`) — that is not a second decision, it is the exactly-once guarantee: it is what makes an
--    e1 call that beat the tick to the punch, or two ticks racing, push a single cue.
--    DELIBERATE CONSEQUENCE: liveness is not re-tested between the cron's scan and e2's push (pg_net dispatches
--    asynchronously), so an account that comes back inside that window is cued anyway; the cron's cancel pass
--    (b) retracts such a cue within one `t_b`, which is the same backstop that has always covered "the user came
--    back before the break ended".
--
-- 3. `omni_edge_push` gains the function name. It hardcoded `/pause-cue`; the cron now has to reach
--    `/pause-cue-cron`. The 1-arg form is DROPPED rather than overloaded — a defaulted second parameter next to
--    the old single-parameter function makes `omni_edge_push(payload)` ambiguous (`function is not unique`).
--
-- 4. `evaluate_pause_cue` is now the CLEAN-LOCK path only, so its `p_exclude_device` is always the caller. It
--    keeps the full decision (liveness / claim / sleeping / overdue) because on that path nobody else made it.
--
-- The cue COMPUTATION itself is factored into `build_pause_cue(user, anchor)` so the two paths cannot drift
-- apart on which break governs, where its length comes from, or what the phone is told to say — the only thing
-- that differs between them is the anchor, which is exactly the thing that legitimately differs.
--
-- DEPLOY SURFACE: Supabase ONLY (`scripts/deploy-supabase.bat`, which now deploys BOTH functions). No client
-- signature changed — the app still POSTs `{action:'evaluate', device_id}` to `/pause-cue` — so no app rebuild
-- is required and no on-disk DB migrates.

-- ---------------------------------------------------------------------------
-- omni_edge_push — now takes WHICH Edge Function to invoke. Same best-effort contract as ever: never raises
-- into the caller, inert while unprovisioned.
-- ---------------------------------------------------------------------------
drop function if exists public.omni_edge_push(jsonb);

create or replace function public.omni_edge_push(payload jsonb, p_function text default 'pause-cue')
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
            url     := base_url || '/' || p_function,
            headers := jsonb_build_object(
                'Content-Type', 'application/json',
                'Authorization', 'Bearer ' || coalesce(role_key, '')
            ),
            body    := payload
        );
    exception when others then
        -- pg_net missing, a transient net error, or bad config must NEVER fail the caller's write. The pause-cue
        -- tick cron is the delivery backstop.
        null;
    end;
end;
$$;

-- ---------------------------------------------------------------------------
-- build_pause_cue — WHAT to say and WHEN, given the instant the account walked away. Shared by both paths so
-- they cannot disagree; it decides nothing about whether a cue is owed beyond "is a break overdue".
--
--   * which break — `overdue_break_at_last_beat()` (20260728000000): among the two dues the account published,
--     those already due at its last beat, longest first (resting 15 min discharges a 5-min pose due with it).
--   * how long   — `break_config.length_ms` for that kind, else the kind's own 5/15-minute duration. The client
--     has published no length since 20260728000000; the length is a property of the break TYPE.
--   * when       — [p_anchor] + that length. The caller owns the anchor: `now()` on the clean-lock path (where
--     the request IS the walk-away), `max(beat_at) + t_a/2` on the cron path (where it must be estimated).
--
-- Returns zero rows when no break was overdue — idle is not the same as "on a break" (PRD §15: the cue is owed
-- only to a user who walked away WITH a break due). Records what was pushed in `pause_cue_schedule` so the
-- cron's cancel pass can retract it if the account comes back first.
-- ---------------------------------------------------------------------------
create or replace function public.build_pause_cue(p_user uuid, p_anchor timestamptz)
returns table (
    due_at        timestamptz,
    break_kind    text,
    break_len_ms  bigint,
    voice_cue     text,
    voice_message text
)
language plpgsql security definer as $$
declare
    v_win  record;
    v_cfg  record;
    v_kind text;
    v_len  bigint;
    v_due  timestamptz;
begin
    select * into v_win from public.overdue_break_at_last_beat(p_user);
    if not found then
        return;
    end if;

    v_kind := coalesce(v_win.break_kind, '15min_break');
    select c.length_ms, c.voice_cue, c.voice_message into v_cfg
      from public.break_config c
     where c.user_id = p_user and c.break_kind = v_kind;

    v_len := coalesce(v_cfg.length_ms, v_win.break_len_ms,
                      case v_kind when '5min_break' then 300000 else 900000 end);
    v_due := p_anchor + make_interval(secs => v_len / 1000.0);

    insert into public.pause_cue_schedule (user_id, due_at, break_kind, break_len_ms, pushed_at)
    values (p_user, v_due, v_kind, v_len, now())
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

revoke all on function public.build_pause_cue(uuid, timestamptz) from public, anon, authenticated;
grant execute on function public.build_pause_cue(uuid, timestamptz) to service_role;

-- ---------------------------------------------------------------------------
-- evaluate_pause_cue — **e1's** decision, i.e. THE CLEAN-LOCK PATH. Called only by the `pause-cue` Edge
-- Function, which is called only by an app whose screen just went off. Returns at most one row; zero rows means
-- "push nothing".
--
-- [p_exclude_device] is the caller: its presence row is necessarily still fresh (it beat < t_a ago) but it is
-- NOT active, so it must not count toward account liveness. It is no longer used for `t2` — the anchor is
-- `now()`, the instant the lock was reported.
--
-- A `due_at` in the past is returned as-is rather than suppressed: with debug-shortened break lengths (the
-- fast-break scripts) the length can be shorter than the push round-trip, and a phone arming an already-past
-- alarm speaks immediately, which is the intended "your pause is over" behaviour.
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
    v_ta integer := public.tick_seconds_for(p_user_id);
begin
    -- (1) Account-wide liveness: any device OTHER than the one reporting its own screen-off seen within 2*t_a.
    --     One stale row while the user works at the other device is not a pause.
    if exists (
        select 1 from public.device_heartbeat h
        where h.user_id = p_user_id
          and (p_exclude_device is null or h.device_id <> p_exclude_device)
          and h.beat_at > now() - make_interval(secs => 2 * v_ta)
    ) then
        return;
    end if;

    -- (2) Deliberately away (Sleep pressed, wake not yet reached) — checked BEFORE the claim, so the episode
    --     stays unclaimed and is evaluated properly once the account wakes.
    if public.account_is_sleeping(p_user_id) then
        return;
    end if;

    -- (3) Claim the episode. Conditional, so a cron tick that got here first (or a second lock report) leaves
    --     nothing to claim and pushes nothing — this is the exactly-once guarantee, not a second decision. A
    --     missing row (the device never beat) equally yields no claim. Claiming BEFORE knowing whether anything
    --     is owed is deliberate: an account idle with no break due is claimed once instead of re-invoking e1 on
    --     every subsequent tick.
    update public.data_payload_sent c set data_payload_sent = true, updated_at = now()
     where c.user_id = p_user_id and not c.data_payload_sent;
    if not found then
        return;
    end if;

    -- (4) The cue itself, anchored at the walk-away instant this path actually observed.
    return query select * from public.build_pause_cue(p_user_id, now());
end;
$$;

revoke all on function public.evaluate_pause_cue(uuid, text) from public, anon, authenticated;
grant execute on function public.evaluate_pause_cue(uuid, text) to service_role;

-- ---------------------------------------------------------------------------
-- claim_pause_cue — **e2's** entry point, i.e. THE DIRTY-KILL PATH. Called only by the `pause-cue-cron` Edge
-- Function, which is called only by `tick_pause_cues()` over pg_net for an account the cron has ALREADY judged
-- idle, unclaimed, awake and owed an overdue break. It therefore decides nothing: claim, compute, return.
--
-- The anchor is the estimate this path is stuck with: `t2 = max(beat_at) + t_a/2`, the expected midpoint of the
-- tick interval the last device vanished in. It is taken from the SERVER-stamped `beat_at`, never from `now()`
-- at detection — otherwise `t_b` (and every minute the tick was late) would push the cue later and later.
-- ---------------------------------------------------------------------------
create or replace function public.claim_pause_cue(p_user_id uuid)
returns table (
    due_at        timestamptz,
    break_kind    text,
    break_len_ms  bigint,
    voice_cue     text,
    voice_message text
)
language plpgsql security definer as $$
declare
    v_ta         integer := public.tick_seconds_for(p_user_id);
    v_idle_since timestamptz;
begin
    -- Exactly-once, as in e1: the loser of a race with a clean-lock report finds nothing to claim.
    update public.data_payload_sent c set data_payload_sent = true, updated_at = now()
     where c.user_id = p_user_id and not c.data_payload_sent;
    if not found then
        return;
    end if;

    select max(h.beat_at) into v_idle_since from public.device_heartbeat h where h.user_id = p_user_id;
    if v_idle_since is null then
        return;
    end if;

    return query select * from public.build_pause_cue(
        p_user_id, v_idle_since + make_interval(secs => v_ta / 2.0));
end;
$$;

revoke all on function public.claim_pause_cue(uuid) from public, anon, authenticated;
grant execute on function public.claim_pause_cue(uuid) to service_role;

-- ---------------------------------------------------------------------------
-- tick_pause_cues — the `t_b` job, unchanged in what it detects; it now hands its accounts to e2
-- (`pause-cue-cron`) instead of to e1. e1 is reserved for the app's own lock report, so the two paths are
-- separately deployable and separately observable in the function logs.
-- ---------------------------------------------------------------------------
create or replace function public.tick_pause_cues()
returns void language plpgsql security definer as $$
declare
    r record;
begin
    -- (a) Idle accounts that are unclaimed AND had a break OVERDUE at the last beat → e2 claims/computes/pushes.
    --     Every condition of the decision lives here, which is what lets e2 push "directly".
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
        perform public.omni_edge_push(
            jsonb_build_object('user_id', r.user_id, 'action', 'push'),
            'pause-cue-cron');
    end loop;

    -- (b) An account that became active again while a pushed cue is still in the future → cancel it, so a phone
    --     lying face-down does not speak while the user is already back at another device. This is also the
    --     backstop for a cue e2 pushed for an account that came back between the scan above and pg_net's
    --     dispatch.
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
        perform public.omni_edge_push(
            jsonb_build_object('user_id', r.user_id, 'action', 'cancel'),
            'pause-cue-cron');
        delete from public.pause_cue_schedule where user_id = r.user_id;
    end loop;
end;
$$;
