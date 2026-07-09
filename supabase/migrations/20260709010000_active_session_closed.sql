-- PRD §15 server-derived pauses, take 2: "inactivity unless a device reported activity".
--
-- The original derive_pauses dropped the TRAILING gap (last activity -> p_until) unconditionally, presuming
-- a device whose last upload is an open session is still active. That presumption fabricated activity: a
-- desktop that went inactive right after a sync moment (its finalize is persisted locally but uploads only
-- at the NEXT sync moment) kept "covering" the account, so a phone starting later saw no pause over a window
-- when genuinely no device was active — and durably adopted it (the "Inactivity band stops an hour before
-- the now-line" incident). The spec is the opposite default: a pause is a window when NO device REPORTED
-- being active (app running + signed in + screen unlocked); unknown time is a pause.
--
-- Two changes, paired with the client now pushing when a session ENDS (it already pushed within seconds of
-- one OPENING — that asymmetry was the enabler):
--   1. `closed` column: a finalized session counts exactly its recorded [start_ms, end_ms]. Only a row the
--      device itself marked still-open may be extended by presumption. Default TRUE so pre-migration rows
--      (all stale) count only what they recorded.
--   2. Bounded presumption + trailing gap emitted: an OPEN session whose `updated_at` is fresh (within
--      PRESUMED_ACTIVE_GRACE = 3 h) is treated as active through p_until — an active signed-in device
--      refreshes its row at least once per pose-coalesced burst (poses recur <= ~2 h apart) and on every
--      session open/finalize, so a fresher-than-3h open row really is a live device. An open row STALER than
--      the grace means the device vanished (crash, power loss, offline) — it counts only its recorded
--      interval, and the pause after it appears retroactively once the grace lapses. With that, the trailing
--      gap IS returned as a pause whenever no fresh open session covers it. (`updated_at` is the writing
--      device's wall clock vs the caller's p_until — minutes of skew are noise against a 3 h grace.)
--
-- Every statement is idempotent so re-applying is safe. Apply with `supabase db push` (or
-- scripts/deploy-supabase.bat).

alter table public.device_active_session
    add column if not exists closed boolean not null default true;

create or replace function public.derive_pauses(p_since bigint, p_until bigint)
returns table (start_ms bigint, end_ms bigint)
language sql
stable
security invoker
set search_path = public
as $$
    with effective as (
        -- A closed (or vanished: open but stale beyond the 3 h grace) session contributes exactly what it
        -- recorded; a fresh open session is presumed active through p_until (its end_ms lags `now` by the
        -- push cadence, and the reporting device is demonstrably alive).
        select start_ms as s0,
               case when closed or updated_at + 10800000 < p_until then end_ms else p_until end as e0
        from public.device_active_session
        where user_id = auth.uid()
    ),
    clipped as (
        select greatest(s0, p_since) as s,
               least(e0, p_until)    as e
        from effective
        where e0 > p_since
          and s0 < p_until
    ),
    valid as (
        -- Keep zero-length sessions as boundary points (e >= s), matching SchedulerDomain.derivePauses: a
        -- just-opened session (e.g. right after a wake) reads as an active point that bounds the prior pause.
        select s, e from clipped where e >= s
    ),
    flagged as (
        select s, e,
               max(e) over (order by s, e rows between unbounded preceding and 1 preceding) as max_prev
        from valid
    ),
    grouped as (
        select s, e,
               sum(case when max_prev is null or s > max_prev then 1 else 0 end)
                   over (order by s, e rows unbounded preceding) as island
        from flagged
    ),
    islands as (
        select min(s) as island_start, max(e) as island_end
        from grouped
        group by island
    ),
    -- The gap before each island: from the previous island's end, or from p_since for the FIRST island
    -- (the leading gap). No islands -> no rows here; the zero-activity case is the whole-window union below.
    gaps as (
        select coalesce(lag(island_end) over (order by island_start), p_since) as start_ms,
               island_start as end_ms
        from islands
    ),
    -- The TRAILING gap (last island -> p_until) is now a pause too: nothing fresh-open covers it, so no
    -- device reported (or is presumed) active there. A live device's open session extends to p_until in
    -- `effective`, which makes this gap empty exactly when someone is still active. (`trailing` itself is a
    -- reserved word — TRIM(TRAILING ...) — so the CTE is named trailing_gap.)
    trailing_gap as (
        select max(island_end) as start_ms, p_until as end_ms
        from islands
    )
    select start_ms, end_ms
    from gaps
    where end_ms > start_ms
    union all
    select start_ms, end_ms
    from trailing_gap
    where start_ms is not null and end_ms > start_ms
    union all
    select p_since, p_until
    where not exists (select 1 from islands)
    order by start_ms;
$$;

grant execute on function public.derive_pauses(bigint, bigint) to authenticated;
