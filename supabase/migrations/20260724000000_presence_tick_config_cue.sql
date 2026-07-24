-- Pause-cue delivery, `t_a` / `t_b` model (PRD §15 / ARCHITECTURE.md §8). Supersedes the shape migration
-- 20260723000000 introduced, keeping the same two tables but changing WHO decides and HOW the decision is
-- de-duplicated.
--
-- THE MODEL (the vocabulary below is the spec's):
--   * `t_a` (default **10 s**, per-account, changeable over HTTP → `app_config.tick_seconds`): while a device is
--     unlocked + signed in it calls `publish_presence()` every `t_a`, which UPSERTs its row in the **presence
--     table** (`device_heartbeat`) with the server's `now()` and `data_payload_sent = false`. The RPC RETURNS the
--     account's current `t_a`, so changing it over HTTP re-paces every device within one tick — no extra call.
--   * `t_b` (default **1 min** → `cron.schedule('pause-cue-tick', ...)` in supabase/pause-cue-setup.sql): a
--     pg_cron job runs one FAST query over the presence table. An account with an unclaimed row
--     (`data_payload_sent = false`) whose newest beat is older than **`2 * t_a`** is idle → it invokes the
--     `pause-cue` Edge Function (**e1**) via pg_net. The cron itself decides nothing else.
--   * **e1 owns the decision**: it calls `evaluate_pause_cue()`, which (atomically) re-checks idleness, computes
--     the cue instant, flips `data_payload_sent = true` on the account's rows so no later tick re-pushes, and
--     returns the payload. e1 then sends the high-priority DATA push to **every** phone of the account.
--   * The cue instant is `t2 + break_length` where **`t2 = <the presence row's time> + t_a/2`** — the expected
--     midpoint of the last tick interval, i.e. the best estimate of when the device actually went away.
--     `break_length` comes from `break_config` (per break kind, changeable over HTTP), falling back to the
--     length the client published.
--   * **Screen-off short-circuit**: on a clean lock the app stops ticking and calls e1 DIRECTLY (with its own
--     user JWT and its `device_id`), so the cue is armed immediately instead of up to `t_b + 2*t_a` later. e1
--     runs the same `evaluate_pause_cue()`, excluding the caller's own (still-fresh) row. A dirty kill has no
--     such call — that is exactly what the `t_a` tick going stale is the backstop for.
--
-- DELIBERATE DEVIATION FROM A LITERAL PER-ROW READING: idleness is judged **account-wide** (`max(beat_at)` over
-- the account's rows), not per row. Firing off a single stale row would speak "your pause is over" into a room
-- where the user is sitting at the other device — the account is on a pause only when NO device is active
-- (PRD §15) — and it would also fan the same push out once per stale row.
--
-- Client on-disk DBs need no migration: everything here is server-only and never part of the synced snapshot.

-- ---------------------------------------------------------------------------
-- app_config — `t_a`. One row per account, created on demand; absent ⇒ the 10 s default. Own-row RLS, so
-- "changed by an HTTP Request" is a plain authenticated PostgREST upsert (see docs/PAUSE_CUE_DELIVERY.md).
-- ---------------------------------------------------------------------------
create table if not exists public.app_config (
    user_id      uuid primary key references auth.users (id) on delete cascade,
    tick_seconds integer not null default 10 check (tick_seconds between 1 and 3600),
    updated_at   timestamptz not null default now()
);

alter table public.app_config enable row level security;

drop policy if exists "own app config" on public.app_config;
create policy "own app config" on public.app_config
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- `t_a` for an account, defaulted. STABLE so the cron's per-row call is cheap.
create or replace function public.tick_seconds_for(p_user uuid)
returns integer language sql stable as $$
    select coalesce((select c.tick_seconds from public.app_config c where c.user_id = p_user), 10);
$$;

-- ---------------------------------------------------------------------------
-- break_config — the two break types and their spoken message. `length_ms` NULL ⇒ use the length the client
-- published for that break (the app's own drawn break, incl. the debug fast-break knobs); setting it overrides
-- the cue timing from the server alone. `voice_cue` names a cue BUNDLED in the app (the phone plays a
-- pre-rendered WAV, it does not synthesize arbitrary text); `voice_message` is that phrase's text, carried in
-- the push for logging and as the TTS fallback where a platform has one.
-- ---------------------------------------------------------------------------
create table if not exists public.break_config (
    user_id       uuid not null references auth.users (id) on delete cascade,
    break_kind    text not null check (break_kind in ('5min_break', '15min_break')),
    length_ms     bigint check (length_ms is null or length_ms > 0),
    voice_cue     text,
    voice_message text,
    updated_at    timestamptz not null default now(),
    primary key (user_id, break_kind)
);

alter table public.break_config enable row level security;

drop policy if exists "own break config" on public.break_config;
create policy "own break config" on public.break_config
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- The presence table (`device_heartbeat`, from 20260723000000) gains the spec's claim flag and the break KIND
-- the device is waiting on. `closed` and its stamping trigger go away: a clean lock now calls e1 directly
-- instead of writing a "closed" marker row, and `publish_presence()` stamps `beat_at` itself — a trigger that
-- stamped on EVERY update would reset `beat_at` when `evaluate_pause_cue()` flips `data_payload_sent`, i.e.
-- the claim would erase the very idleness it was claiming.
-- ---------------------------------------------------------------------------
alter table public.device_heartbeat add column if not exists data_payload_sent boolean not null default false;
alter table public.device_heartbeat add column if not exists next_break_kind text;
alter table public.device_heartbeat drop column if exists closed;

drop trigger if exists touch_device_heartbeat on public.device_heartbeat;
drop function if exists public.touch_device_heartbeat();

-- Only unclaimed rows are ever scanned by the cron.
create index if not exists device_heartbeat_unclaimed_idx
    on public.device_heartbeat (user_id, beat_at) where not data_payload_sent;

-- pause_cue_schedule (bookkeeping of what was pushed, so an account that comes back can be told to cancel).
alter table public.pause_cue_schedule add column if not exists break_kind text;

-- ---------------------------------------------------------------------------
-- publish_presence — THE `t_a` tick. One authenticated call per tick per device; returns the account's current
-- `t_a` so the client re-paces itself without a second request. SECURITY INVOKER: the write still goes through
-- the row's own-row RLS policy, and `auth.uid()` is the only accepted owner (a device cannot beat for another
-- account). `data_payload_sent` is reset to false on every beat, so an account coming back to life
-- automatically re-arms the next idle episode's push.
-- ---------------------------------------------------------------------------
create or replace function public.publish_presence(
    p_device_id      text,
    p_kind           text,
    p_break_kind     text default null,
    p_break_start_ms bigint default null,
    p_break_len_ms   bigint default null,
    p_break_end_ms   bigint default null
)
returns integer language plpgsql as $$
declare
    uid uuid := auth.uid();
begin
    if uid is null then
        raise exception 'publish_presence requires an authenticated session';
    end if;

    insert into public.device_heartbeat as h (
        user_id, device_id, kind, next_break_kind,
        next_break_start_ms, next_break_len_ms, next_break_end_ms,
        data_payload_sent, beat_at
    )
    values (
        uid, p_device_id, p_kind, p_break_kind,
        p_break_start_ms, p_break_len_ms, p_break_end_ms,
        false, now()
    )
    on conflict (user_id, device_id) do update set
        kind                = excluded.kind,
        next_break_kind     = excluded.next_break_kind,
        next_break_start_ms = excluded.next_break_start_ms,
        next_break_len_ms   = excluded.next_break_len_ms,
        next_break_end_ms   = excluded.next_break_end_ms,
        data_payload_sent   = false,
        beat_at             = now();

    return public.tick_seconds_for(uid);
end;
$$;

-- The account deliberately away (Sleep pressed, wake not yet reached) — the cue is suppressed there.
create or replace function public.account_is_sleeping(p_user uuid)
returns boolean language sql stable as $$
    select coalesce(
        (select s.mode = 'sleeping' and s.wake_at is not null and s.wake_at > now()
           from public.account_state s where s.user_id = p_user),
        false);
$$;

-- ---------------------------------------------------------------------------
-- evaluate_pause_cue — the decision, called by e1 (never by the cron directly, never by a client). Returns at
-- most one row; returning zero rows means "do not push". It CLAIMS in the same transaction (flipping
-- `data_payload_sent`), so the cron path and the screen-off path can race harmlessly: the loser sees nothing
-- left to claim and pushes nothing.
--
-- [p_exclude_device] is the device that just reported its own screen-off: its row is necessarily still fresh
-- (it beat < t_a ago) but it is NOT active, so it must not count toward account liveness. Its `beat_at` still
-- counts for `t2` — it is precisely the last moment the account was seen.
--
-- A `due_at` in the past is returned as-is rather than suppressed: with debug-shortened break lengths (the
-- fast-break scripts) the length can be shorter than `t_b`, and a phone arming an already-past alarm speaks
-- immediately, which is the intended "your pause is over" behaviour.
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
    -- `v_` prefixes throughout: the OUT columns above (due_at, break_kind, …) are plpgsql variables too, and an
    -- unprefixed local would be one more identifier competing with a column name inside these queries.
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

    -- (2) Already pushed for this idle episode (every row claimed) → nothing to do. A device coming back resets
    --     the flag on its next beat, which is what re-arms the next episode.
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

    -- (4) The break the account is waiting on: the soonest-ENDING window any device published. (Which pose
    --     governs when several are overdue at once is decided client-side —
    --     `SchedulerEngine.nextRestPoseWindowMillis`, the longest-overdue rule, PRD §15.)
    select h.next_break_kind, h.next_break_len_ms
      into v_win
    from public.device_heartbeat h
    where h.user_id = p_user_id and h.next_break_len_ms is not null and h.next_break_len_ms > 0
    order by (coalesce(h.next_break_start_ms, 0) + h.next_break_len_ms) asc
    limit 1;

    -- (5) Claim BEFORE deciding whether there is anything to say, and re-read the claim to settle a race: a
    --     concurrent tick / screen-off call that got there first leaves nothing to update, and this caller
    --     pushes nothing. Claiming here also covers the "no break window published" case below — such an
    --     account owes no cue and will publish nothing more until a device comes back, so leaving it unclaimed
    --     would make every subsequent tick invoke e1 for it again.
    update public.device_heartbeat h set data_payload_sent = true
     where h.user_id = p_user_id and not h.data_payload_sent;
    if not found then
        return;
    end if;

    -- No device published a break window (SELECT INTO left the record null): claimed, nothing owed.
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

-- Only the server may evaluate/claim; clients reach it only through e1 (which calls it with the service role).
-- Explicit grants rather than relying on Supabase's default privileges, so the intent survives a role reset.
revoke all on function public.evaluate_pause_cue(uuid, text) from public, anon, authenticated;
grant execute on function public.evaluate_pause_cue(uuid, text) to service_role;
grant execute on function public.publish_presence(text, text, text, bigint, bigint, bigint) to authenticated;
grant execute on function public.tick_seconds_for(uuid) to authenticated, service_role;

-- ---------------------------------------------------------------------------
-- tick_pause_cues — the `t_b` job. Deliberately does NOT decide anything: one grouped scan of the presence
-- table hands idle accounts to e1, plus a second pass that cancels a pending cue on an account that came back.
-- ---------------------------------------------------------------------------
create or replace function public.tick_pause_cues()
returns void language plpgsql security definer as $$
declare
    r record;
begin
    -- (a) Idle accounts with something unclaimed → let e1 decide, claim and push.
    for r in
        select h.user_id
          from public.device_heartbeat h
         group by h.user_id
        having bool_or(not h.data_payload_sent)
           and max(h.beat_at) <= now() - make_interval(secs => 2 * public.tick_seconds_for(h.user_id))
           and not public.account_is_sleeping(h.user_id)
    loop
        perform public.omni_edge_push(jsonb_build_object(
            'user_id', r.user_id, 'device_id', '*', 'action', 'evaluate'));
    end loop;

    -- (b) An account that became active again while a pushed cue is still in the future → cancel it, so a phone
    --     lying face-down does not speak while the user is already back at the other device.
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
