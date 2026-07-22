-- Pause-cue delivery WITHOUT an external always-on worker: pg_cron + a device-heartbeat table (PRD §15).
--
-- WHY this exists / what it replaces: the previous model watched Supabase **Realtime Presence** from a small
-- always-on Node worker (see the now-deleted /listener) because pg_cron and Edge Functions cannot read
-- presence. That worker was itself a heartbeat-and-poll design (a `published_at_ms` liveness stamp + a 10 s
-- `evaluate()` tick), so moving the SAME heartbeat off a WebSocket presence channel and onto a plain Postgres
-- table lets pg_cron do the poll centrally — and drops the Fly.io host entirely. Trade-off (accepted): each
-- active device now writes a heartbeat row every ~10 s instead of holding a presence WebSocket. On the free
-- plan that is DB write traffic (not Realtime messages, not Edge invocations), well within limits for a
-- handful of devices.
--
-- FLOW:
--   * Each active device (foreground + unlocked + signed in) UPSERTs its `device_heartbeat` row every ~10 s,
--     carrying the next rest-pose window it is waiting on. The server stamps `beat_at = now()` (a trigger), so
--     staleness never depends on the client clock.
--   * A clean lock/close sets the row `closed = true` (still stamping `beat_at`, i.e. the real lock instant) —
--     the explicit "this device got locked" signal, detected on the very next tick. A dirty lock (the app was
--     suspended before it could write) leaves the row un-closed but its `beat_at` goes stale; the ~25 s
--     staleness window catches it. Either way the row is KEPT (never deleted) so the cron can still read the
--     last-published break window to time the cue.
--   * `tick_pause_cues()` runs every ~10 s (scheduled in supabase/pause-cue-setup.sql, which owns the
--     project-level cron.schedule + Vault secrets). When an account has NO fresh, non-closed heartbeat and is
--     not sleeping, it computes the pause end server-side and invokes the `pause-cue` Edge Function (fanned out
--     to every registered phone via device_id '*'), then records what it pushed so it does not re-push each
--     tick. When a device becomes active again it cancels a still-future pushed cue.
--
-- Every statement is idempotent. Client on-disk DBs need no migration: device_heartbeat is a new server-only
-- table, never part of the synced snapshot.

-- ---------------------------------------------------------------------------
-- device_heartbeat: one row per (user, device). `beat_at` is server-stamped; `closed` marks a clean
-- lock/inactive transition. The `next_break_*` columns are the pose window the device last published (the cue
-- timing input). Same own-row RLS as scheduler_snapshot — the cron reads it as SECURITY DEFINER (RLS bypassed).
-- ---------------------------------------------------------------------------
create table if not exists public.device_heartbeat (
    user_id             uuid    not null references auth.users (id) on delete cascade,
    device_id           text    not null,
    kind                text    not null,
    next_break_start_ms bigint,
    next_break_len_ms   bigint,
    next_break_end_ms   bigint,
    closed              boolean not null default false,
    beat_at             timestamptz not null default now(),
    primary key (user_id, device_id)
);

alter table public.device_heartbeat enable row level security;

drop policy if exists "own heartbeat" on public.device_heartbeat;
create policy "own heartbeat" on public.device_heartbeat
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- Stamp beat_at on every insert AND update (the client upserts via merge-duplicates), so freshness is
-- server-driven. A clean-lock upsert sets closed = true here too, so beat_at then marks the real lock instant.
create or replace function public.touch_device_heartbeat()
returns trigger language plpgsql as $$
begin
    new.beat_at = now();
    return new;
end;
$$;

drop trigger if exists touch_device_heartbeat on public.device_heartbeat;
create trigger touch_device_heartbeat
    before insert or update on public.device_heartbeat
    for each row execute function public.touch_device_heartbeat();

-- ---------------------------------------------------------------------------
-- pause_cue_schedule: per-account cron bookkeeping so the tick does not re-push the same cue every 10 s. Holds
-- the instant we last pushed a schedule for. (Recreated fresh — an earlier shape was dropped in 20260713.)
-- The client never touches this table; the cron writes it as SECURITY DEFINER.
-- ---------------------------------------------------------------------------
create table if not exists public.pause_cue_schedule (
    user_id      uuid primary key references auth.users (id) on delete cascade,
    due_at       timestamptz not null,
    break_len_ms bigint,
    pushed_at    timestamptz
);

alter table public.pause_cue_schedule enable row level security;

drop policy if exists "own cue schedule" on public.pause_cue_schedule;
create policy "own cue schedule" on public.pause_cue_schedule
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- tick_pause_cues(): the central poll. Runs every ~10 s (see pause-cue-setup.sql). Cheap: only accounts with a
-- heartbeat row are considered, and an already-pushed cue short-circuits. Uses public.omni_edge_push (migration
-- 20260709020000) to invoke the pause-cue Edge Function via pg_net — best-effort, never raising.
-- ---------------------------------------------------------------------------
create or replace function public.tick_pause_cues()
returns void language plpgsql security definer as $$
declare
    r          record;
    existing   record;
    idle_since timestamptz;
    win        record;
    due        timestamptz;
    sleeping   boolean;
begin
    for r in select distinct user_id from public.device_heartbeat loop
        -- ACTIVE: any non-closed heartbeat seen within the staleness window (~2.5 ticks, tolerating one dropped
        -- write). Cancel a still-future pushed cue, clear bookkeeping, and move on.
        if exists (
            select 1 from public.device_heartbeat h
            where h.user_id = r.user_id
              and h.closed = false
              and h.beat_at > now() - interval '25 seconds'
        ) then
            select * into existing from public.pause_cue_schedule s where s.user_id = r.user_id;
            if found then
                if existing.pushed_at is not null and existing.due_at > now() then
                    perform public.omni_edge_push(jsonb_build_object(
                        'user_id', r.user_id, 'device_id', '*', 'action', 'cancel'));
                end if;
                delete from public.pause_cue_schedule where user_id = r.user_id;
            end if;
            continue;
        end if;

        -- IDLE. Anchor the pause start to the real instant the LAST device was seen (max beat_at over all rows,
        -- closed or stale). This is stable once idle (no new beats arrive), so `due` does not drift tick to tick.
        select max(beat_at) into idle_since from public.device_heartbeat where user_id = r.user_id;

        -- Suppress while the user is deliberately away (Sleep pressed, wake not yet reached).
        select (mode = 'sleeping' and wake_at is not null and wake_at > now())
          into sleeping from public.account_state where user_id = r.user_id;
        if coalesce(sleeping, false) then
            delete from public.pause_cue_schedule where user_id = r.user_id;
            continue;
        end if;

        -- The soonest-ending rest-pose window any device published (fallback to the legacy end for old clients).
        select next_break_start_ms as start_ms, next_break_len_ms as len_ms, next_break_end_ms as end_ms
          into win
        from public.device_heartbeat
        where user_id = r.user_id and next_break_len_ms is not null and next_break_len_ms > 0
        order by (coalesce(next_break_start_ms, 0) + next_break_len_ms) asc
        limit 1;

        if win.len_ms is not null then
            -- PRD §15: pause end = max(disconnectStart, poseStart) + poseLength, from the real disconnect instant.
            due := greatest(idle_since, to_timestamp(coalesce(win.start_ms, 0) / 1000.0))
                   + make_interval(secs => win.len_ms / 1000.0);
        else
            select next_break_end_ms into win.end_ms from public.device_heartbeat
            where user_id = r.user_id and next_break_end_ms is not null
            order by next_break_end_ms asc limit 1;
            due := case when win.end_ms is not null then to_timestamp(win.end_ms / 1000.0) else null end;
        end if;

        if due is null or due <= now() then
            delete from public.pause_cue_schedule where user_id = r.user_id;
            continue;
        end if;

        -- Dedup: already pushed for (approximately) this instant → nothing to do.
        select * into existing from public.pause_cue_schedule where user_id = r.user_id;
        if found and existing.pushed_at is not null
           and abs(extract(epoch from (existing.due_at - due))) < 1 then
            continue;
        end if;

        -- Push EARLY (the moment idle is detected), not at d − 1 s: pg_cron's ~10 s granularity can't hit a
        -- precise lead, and the phone arms its own exact local alarm at `due_at`. A resume cancels it above.
        perform public.omni_edge_push(jsonb_build_object(
            'user_id', r.user_id,
            'device_id', '*',
            'action', 'schedule',
            'due_at', to_char(due at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
            'break_len_ms', win.len_ms));

        insert into public.pause_cue_schedule (user_id, due_at, break_len_ms, pushed_at)
        values (r.user_id, due, win.len_ms, now())
        on conflict (user_id) do update
            set due_at = excluded.due_at, break_len_ms = excluded.break_len_ms, pushed_at = now();
    end loop;
end;
$$;
