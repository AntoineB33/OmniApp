-- $now line$ MODE 3 (docs/scheduler_requirements.md § *$now line$ and 3 Dynamic Restrictive Period*).
-- Refines 20260724000000 -> 20260726000000 -> 20260728000000 -> 20260729000000.
--
-- THE SPEC, restated. The now-line has a third mode, and its condition is account-wide:
--
--     mode 3  <=>  at least one device has the "I'm away" button ON, and every OTHER device is locked.
--
-- Mode 2 says only "no screen is in use", and the README's rule for it is that the line may not be covered by
-- one of the three dynamic periods -- a locked screen is not a break TAKEN. Mode 3 is the account SAYING it is,
-- so a 5- or 15-minute pose elapses under the line instead of being dragged.
--
-- WHY THE SERVER HOLDS ANY OF THIS. Two things follow from that condition, and neither can be answered by one
-- device on its own:
--
--   1. The mode is a function of EVERY device's away flag, not of the flag on the device asking. So each device
--      publishes its OWN flag (`sync_device_away`) and reads back one boolean -- "is any device of this account
--      away" -- which it combines with the account-wide "is any device unlocked" it already derives locally.
--      The away device itself counts as locked for that second question (it stopped beating the moment the
--      button went on), which is exactly why the two questions compose into the condition above.
--
--   2. For the whole of a mode-3 episode every screen of the account is off, so nothing local is watching the
--      line cross a break. The `t_b` cron does it instead.
--
-- THE SERVER DOES NOT RUN THE SCHEDULER. It reads the SET OF RULES the scheduler returned. The client publishes
-- the future dynamic restrictive periods as rows -- `screen_break_rule {break_kind, start_ms, end_ms}` -- and the
-- cron's whole question is a comparison: is `now()` inside one of them? Nothing here places a period, re-derives
-- a bar or knows what a recurrence is; `PlanWalk` remains the only copy of the scheduling rules (CLAUDE.md).
-- That the rules can be read this literally is a property of mode 3 itself: nothing drags a pose there, so where
-- the scheduler put it IS where it happens.
--
-- The two dues in `device_break` are unchanged and stay the walk-away gate's input -- the client derives them
-- from the very same rule set, in one placement query, so they are a projection of it and not a second answer.
--
-- THE SECOND USE OF THE AWAY FLAG. `away_span` logs each mode-3 episode, so a WAKING app can ask
-- `away_spans(from, to)` and walk its fast-forward move of the now-line in mode 3 over those stretches instead
-- of always in mode 2 (README § *Progressive Calculation*, direct consequence). A device that was asleep for the
-- whole episode has no other way to learn it happened. The episode is opened and closed by the same signals the
-- mode is derived from, never by a device's opinion of the mode.
--
-- RECONSTRUCTIBILITY (CLAUDE.md). All three tables are re-derivable server state, never user data: the away flag
-- is re-asserted by the next edge and by every app start, the rule rows by the next publish, and the span log is
-- pruned to a week -- longer than any journey a client sweeps (its own no-screen evidence window is 24 h).
--
-- DEPLOY SURFACE: Supabase (`scripts/deploy-supabase.bat`) **and** every app (`account{1,2,3}-*deploy*.bat`) --
-- the client calls three new RPCs, and an un-deployed server answers 404 to them (every call site is
-- best-effort, so an app built against this migration still runs against an unmigrated project). Client on-disk
-- DBs need no migration: all of this is server-only state.

-- ---------------------------------------------------------------------------
-- device_away -- one row PER DEVICE: is this device's "I'm away" button on? Per device and not per account
-- because that is what the button is -- a statement about one screen -- and the account-level question is a
-- quantifier over these rows, asked in `account_any_device_away`.
-- ---------------------------------------------------------------------------
create table if not exists public.device_away (
    user_id    uuid not null references auth.users (id) on delete cascade,
    device_id  text not null,
    away       boolean not null default false,
    updated_at timestamptz not null default now(),
    primary key (user_id, device_id)
);

alter table public.device_away enable row level security;

drop policy if exists "own device away" on public.device_away;
create policy "own device away" on public.device_away
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- away_span -- the mode-3 HISTORY: one row per episode, `ended_at` null while it is still open. An append-and-
-- close log rather than a derivation of the current state, because the question a waking app asks is about a
-- stretch of the PAST, which no current-value row can answer.
--
-- An episode left OPEN is the right answer, not a leak: the away declaration stands until something clears it,
-- and `away_spans` clamps an open span at `now()` rather than reporting an unbounded stretch.
-- ---------------------------------------------------------------------------
create table if not exists public.away_span (
    user_id    uuid not null references auth.users (id) on delete cascade,
    started_at timestamptz not null,
    ended_at   timestamptz,
    primary key (user_id, started_at)
);

create index if not exists away_span_window on public.away_span (user_id, started_at desc);

alter table public.away_span enable row level security;

drop policy if exists "own away span" on public.away_span;
create policy "own away span" on public.away_span
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- screen_break_rule -- THE SET OF RULES, as published by the scheduler: the future 5- and 15-minute dynamic
-- restrictive periods, each as a plain half-open window on the REAL wall clock. One row per window; the
-- account's whole set is replaced atomically by `publish_break_rules`.
--
-- The 20 s look-away is deliberately absent. It is assumed taken as it falls due, so it is never cued, and its
-- 20-minute cadence would rewrite this table constantly for an answer nothing reads.
-- ---------------------------------------------------------------------------
create table if not exists public.screen_break_rule (
    user_id    uuid not null references auth.users (id) on delete cascade,
    break_kind text not null check (break_kind in ('5min_break', '15min_break')),
    start_ms   bigint not null,
    end_ms     bigint not null,
    primary key (user_id, break_kind, start_ms)
);

create index if not exists screen_break_rule_window on public.screen_break_rule (user_id, start_ms);

alter table public.screen_break_rule enable row level security;

drop policy if exists "own screen break rule" on public.screen_break_rule;
create policy "own screen break rule" on public.screen_break_rule
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- open_away_span -- opens an episode unless one is already open. SECURITY DEFINER so the cron can call it too.
-- ---------------------------------------------------------------------------
create or replace function public.open_away_span(p_user uuid)
returns void language plpgsql security definer as $$
begin
    insert into public.away_span (user_id, started_at, ended_at)
    select p_user, now(), null
     where not exists (
         select 1 from public.away_span s where s.user_id = p_user and s.ended_at is null);
end;
$$;

grant execute on function public.open_away_span(uuid) to authenticated, service_role;

-- ---------------------------------------------------------------------------
-- sync_device_away -- the away flag, both ways in one round trip: write this device's value when one is given,
-- and always return the ACCOUNT's answer, "is any device away". The client calls it on every away edge and on
-- every sync moment (`p_away => null` to read without writing), and never on a timer.
--
-- It also maintains the mode-3 episode log, because the two things it knows are exactly what opens and closes
-- one: some device is away -> open; none is any more -> close. The other two writers of that log are
-- `publish_presence` (a beating device means somebody is unlocked, so the episode is over) and the cron's own
-- pass, for the case where the beats merely stopped after the flag went on.
--
-- SECURITY INVOKER: every write goes through the rows' own-row RLS. Like `publish_next_break` it deliberately
-- does NOT touch `device_heartbeat` -- an away flag is not evidence of presence; it is the opposite.
-- ---------------------------------------------------------------------------
create or replace function public.sync_device_away(
    p_device_id text,
    p_away      boolean default null
)
returns boolean language plpgsql as $$
declare
    uid   uuid := auth.uid();
    v_any boolean;
begin
    if uid is null then
        raise exception 'sync_device_away requires an authenticated session';
    end if;

    if p_away is not null then
        insert into public.device_away as d (user_id, device_id, away, updated_at)
        values (uid, p_device_id, p_away, now())
        on conflict (user_id, device_id) do update set away = excluded.away, updated_at = now();
    end if;

    select exists (select 1 from public.device_away d where d.user_id = uid and d.away) into v_any;

    if v_any then
        perform public.open_away_span(uid);
    else
        update public.away_span s set ended_at = now()
         where s.user_id = uid and s.ended_at is null;
    end if;

    -- A week is longer than any journey a client sweeps (its own no-screen evidence window is 24 h).
    delete from public.away_span s
     where s.user_id = uid and s.ended_at is not null and s.ended_at < now() - interval '7 days';

    return v_any;
end;
$$;

grant execute on function public.sync_device_away(text, boolean) to authenticated;

-- ---------------------------------------------------------------------------
-- account_any_device_away / account_in_mode3 -- the condition, in the two halves the spec states it in.
--
-- "Every OTHER device is locked" is read the way the whole pause-cue path already reads liveness: a device that
-- is unlocked and signed in beats every `t_a`, so no beat within `2*t_a` from ANY device means none is unlocked.
-- The away device is included in that test rather than excluded, and must be: turning the button on stops its
-- own beat, which is precisely how "the away device does not count as unlocked" is expressed.
-- ---------------------------------------------------------------------------
create or replace function public.account_any_device_away(p_user uuid)
returns boolean language sql stable as $$
    select exists (select 1 from public.device_away d where d.user_id = p_user and d.away);
$$;

create or replace function public.account_in_mode3(p_user uuid)
returns boolean language sql stable as $$
    select public.account_any_device_away(p_user)
       and not exists (
           select 1 from public.device_heartbeat h
            where h.user_id = p_user
              and h.beat_at > now() - make_interval(secs => 2 * public.tick_seconds_for(p_user)));
$$;

grant execute on function public.account_any_device_away(uuid) to authenticated, service_role;
grant execute on function public.account_in_mode3(uuid) to service_role;

-- ---------------------------------------------------------------------------
-- away_spans -- what a WAKING app asks for: the account's mode-3 stretches overlapping the window it is about
-- to sweep, clipped to it, oldest first. A still-open episode is clipped at the window's right edge, which is
-- the wake instant: the line cannot be swept past where it has got to.
-- ---------------------------------------------------------------------------
create or replace function public.away_spans(p_from_ms bigint, p_to_ms bigint)
returns table (start_ms bigint, end_ms bigint)
language sql stable as $$
    select (extract(epoch from greatest(s.started_at, to_timestamp(p_from_ms / 1000.0))) * 1000)::bigint,
           (extract(epoch from least(coalesce(s.ended_at, now()), to_timestamp(p_to_ms / 1000.0))) * 1000)::bigint
      from public.away_span s
     where s.user_id = auth.uid()
       and s.started_at < to_timestamp(p_to_ms / 1000.0)
       and coalesce(s.ended_at, now()) > to_timestamp(p_from_ms / 1000.0)
     order by s.started_at asc;
$$;

grant execute on function public.away_spans(bigint, bigint) to authenticated;

-- ---------------------------------------------------------------------------
-- publish_break_rules -- THE SET OF RULES, replaced whole. `p_rules` is a JSON array of
-- `{"k": "5min_break"|"15min_break", "s": <start ms>, "e": <end ms>}`, on the REAL wall clock, sorted or not.
-- The client sends it only when it CALCULATES A DIFFERENT SET, so a device sitting at its desk sends nothing.
--
-- Replaced whole rather than merged because it IS one answer: a rule set holding half of one placement and half
-- of another is not a schedule anybody returned.
-- ---------------------------------------------------------------------------
create or replace function public.publish_break_rules(p_rules jsonb)
returns void language plpgsql as $$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'publish_break_rules requires an authenticated session';
    end if;

    delete from public.screen_break_rule where user_id = uid;

    insert into public.screen_break_rule (user_id, break_kind, start_ms, end_ms)
    select uid, r.k, r.s, max(r.e)
      from jsonb_to_recordset(coalesce(p_rules, '[]'::jsonb)) as r(k text, s bigint, e bigint)
     where r.k in ('5min_break', '15min_break') and r.s is not null and r.e is not null and r.e > r.s
     group by r.k, r.s;
end;
$$;

grant execute on function public.publish_break_rules(jsonb) to authenticated;

-- ---------------------------------------------------------------------------
-- The de-dupe key both cue paths share: WHICH BREAK was pushed, as its own start instant. Without it the mode-3
-- pass below and the walk-away pass could each fire for the same pose -- they are two ways of noticing one
-- break, not two breaks.
-- ---------------------------------------------------------------------------
alter table public.pause_cue_schedule add column if not exists break_start_ms bigint;

-- `overdue_break_at_last_beat` gains the due instant it was already selecting on, so `build_pause_cue` can
-- record it. Dropped rather than replaced: the return type changes.
drop function if exists public.overdue_break_at_last_beat(uuid);

create or replace function public.overdue_break_at_last_beat(p_user uuid)
returns table (break_kind text, break_len_ms bigint, due_ms bigint)
language sql stable as $$
    select v.break_kind, v.default_len_ms, v.due_ms
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

-- `build_pause_cue` is unchanged except that it records which break it was about.
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

    insert into public.pause_cue_schedule (user_id, due_at, break_kind, break_len_ms, break_start_ms, pushed_at)
    values (p_user, v_due, v_kind, v_len, v_win.due_ms, now())
    on conflict (user_id) do update
        set due_at = excluded.due_at,
            break_kind = excluded.break_kind,
            break_len_ms = excluded.break_len_ms,
            break_start_ms = excluded.break_start_ms,
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

-- ---------------------------------------------------------------------------
-- mode3_break_window_at -- "MOVE THE NOW LINE AND READ THE RULES": is the account, right now, inside one of the
-- published 5/15-minute periods? One comparison against `screen_break_rule` -- the server evaluates the rule
-- set, it does not compute it.
--
-- Longest first, for the same reason the overdue gate has always preferred it: a 15-minute pose discharges a
-- 5-minute one falling inside it.
-- ---------------------------------------------------------------------------
create or replace function public.mode3_break_window_at(p_user uuid)
returns table (break_kind text, break_len_ms bigint, start_ms bigint, ends_at timestamptz)
language sql stable as $$
    select r.break_kind,
           r.end_ms - r.start_ms,
           r.start_ms,
           to_timestamp(r.end_ms / 1000.0)
      from public.screen_break_rule r
     where r.user_id = p_user
       and (extract(epoch from now()) * 1000)::bigint >= r.start_ms
       and (extract(epoch from now()) * 1000)::bigint <  r.end_ms
     order by (r.end_ms - r.start_ms) desc
     limit 1;
$$;

grant execute on function public.mode3_break_window_at(uuid) to service_role;

-- ---------------------------------------------------------------------------
-- claim_mode3_break_cue -- e2's mode-3 entry point. The cron has already decided (mode 3, not sleeping, inside a
-- published window, that window not yet pushed), so this claims and computes, exactly as `claim_pause_cue` does
-- for the dirty-kill path.
--
-- The anchor is the BREAK'S OWN END, taken from the rule itself: mode 3 means the pose is being taken where the
-- scheduler placed it, so the instant the user should be told to resume is known exactly rather than estimated.
-- `t_b` is detection latency only here too -- a tick that runs late must not move the cue.
--
-- The claim is `break_start_ms`, not `data_payload_sent`: the latter is the walk-away EPISODE's flag, and one
-- away spell can legitimately contain several poses. Sharing the break instant as the key is also what stops
-- this and the walk-away path cueing the same pose twice.
-- ---------------------------------------------------------------------------
create or replace function public.claim_mode3_break_cue(p_user_id uuid)
returns table (
    due_at        timestamptz,
    break_kind    text,
    break_len_ms  bigint,
    voice_cue     text,
    voice_message text
)
language plpgsql security definer as $$
declare
    v_win record;
    v_cfg record;
begin
    select * into v_win from public.mode3_break_window_at(p_user_id);
    if not found then
        return;
    end if;

    -- Exactly-once per BREAK. The update is conditional, so two ticks racing (or a walk-away cue that already
    -- covered this pose) leave exactly one push.
    insert into public.pause_cue_schedule as p
        (user_id, due_at, break_kind, break_len_ms, break_start_ms, pushed_at)
    values (p_user_id, v_win.ends_at, v_win.break_kind, v_win.break_len_ms, v_win.start_ms, now())
    on conflict (user_id) do update
        set due_at = excluded.due_at,
            break_kind = excluded.break_kind,
            break_len_ms = excluded.break_len_ms,
            break_start_ms = excluded.break_start_ms,
            pushed_at = now()
      where p.break_start_ms is distinct from excluded.break_start_ms;
    if not found then
        return;
    end if;

    select c.voice_cue, c.voice_message into v_cfg
      from public.break_config c
     where c.user_id = p_user_id and c.break_kind = v_win.break_kind;

    due_at        := v_win.ends_at;
    break_kind    := v_win.break_kind;
    break_len_ms  := v_win.break_len_ms;
    voice_cue     := coalesce(v_cfg.voice_cue, 'pause_over');
    voice_message := coalesce(
        v_cfg.voice_message,
        case v_win.break_kind
            when '5min_break' then 'Your 5 minute pause is over. You can resume your work.'
            else 'Your 15 minute pause is over. You can resume your work.'
        end);
    return next;
end;
$$;

revoke all on function public.claim_mode3_break_cue(uuid) from public, anon, authenticated;
grant execute on function public.claim_mode3_break_cue(uuid) to service_role;

-- ---------------------------------------------------------------------------
-- publish_presence gains one statement: a beating device is an unlocked device, so the account is not in mode 3
-- and any open episode ends here. That is the third writer of the log, and the one that closes an episode the
-- user ended by coming back to a DIFFERENT device than the one they had declared away.
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

    update public.away_span s set ended_at = now()
     where s.user_id = uid and s.ended_at is null;

    return public.tick_seconds_for(uid);
end;
$$;

grant execute on function public.publish_presence(text) to authenticated;

-- ---------------------------------------------------------------------------
-- tick_pause_cues -- the `t_b` job. Passes (a) and (b) are unchanged; pass (c) is mode 3.
--
-- Pass (c) is deliberately NOT folded into (a). (a) fires once per idle EPISODE for a break that was already
-- overdue when the last device vanished -- "the user walked away with a break owed" -- and is gated on
-- `data_payload_sent`. (c) fires per BREAK WINDOW the line is actually inside while the account has SAID it is
-- on a break, which is a different question with a different anchor (the break's own end) and a different
-- de-dupe key (`break_start_ms`). Merging them would make one of the two answer wrongly.
-- ---------------------------------------------------------------------------
create or replace function public.tick_pause_cues()
returns void language plpgsql security definer as $$
declare
    r record;
begin
    -- (a) Idle accounts that are unclaimed AND had a break OVERDUE at the last beat -> let e1 decide/claim/push.
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

    -- (b) An account that became active again while a pushed cue is still in the future -> cancel it.
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

    -- (c) MODE 3: an away device, every other one locked. Keep the episode log honest (the beats may merely
    --     have stopped since the flag went on), then read the published RULES at the now-line: if the line is
    --     inside a 5- or 15-minute period, tell the phone when that period ends.
    for r in
        select distinct d.user_id
          from public.device_away d
         where d.away
           and public.account_in_mode3(d.user_id)
    loop
        perform public.open_away_span(r.user_id);
        if not public.account_is_sleeping(r.user_id)
           and exists (select 1 from public.mode3_break_window_at(r.user_id))
           and not exists (
               select 1 from public.pause_cue_schedule s
                where s.user_id = r.user_id
                  and s.break_start_ms is not distinct from
                      (select w.start_ms from public.mode3_break_window_at(r.user_id) w))
        then
            perform public.omni_edge_push(
                jsonb_build_object('user_id', r.user_id, 'device_id', '*', 'action', 'mode3'),
                'pause-cue-cron');
        end if;
    end loop;
end;
$$;

-- ---------------------------------------------------------------------------
-- Device<->account exclusivity (20260727000000 / 20260728000000), extended to the new per-device row. A device
-- that beats for a new account evicts its rows under every other account; an account thereby left with NO
-- presence rows loses its break + claim rows already, and now its rule and span rows too -- same hygiene, same
-- reason (such an account can never be evaluated, so the leftovers were inert).
-- ---------------------------------------------------------------------------
create or replace function public.device_presence_unique_owner()
returns trigger language plpgsql security definer as $$
declare
    evicted uuid[];
    u       uuid;
begin
    delete from public.device_away
     where device_id = new.device_id and user_id <> new.user_id;

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
            delete from public.screen_break_rule where user_id = u;
            delete from public.away_span where user_id = u;
        end if;
    end loop;
    return new;
end;
$$;
