-- PRD §15 server-derived pauses (device_active_session + derive_pauses).
--
-- A pause is a window when NO device on the account was active — active meaning the app was running,
-- signed in, and the screen unlocked/interactive. Each device reports the exact intervals it WAS active
-- (heartbeated), and the server deduces the account-wide pauses as the complement of the UNION of every
-- device's active intervals over a window. The client reports its own activity and reads the derived
-- pauses back (rendered as greyed "Inactivity" calendar bands + folded into the §15 rest-pose seeding).
--
-- This supersedes the earlier per-device `device_sleep_gap`-as-band approach: a raw sleep on one device
-- while another device was in use is NOT an account-wide pause and must not show. Active sessions are
-- authoritative activity history (an input the server needs, not derivable from other state), so — like
-- device_sleep_gap / device_presence — the table is synced per-row, is NOT part of the PersistedSnapshot,
-- and never produces a History Unit. The derived pauses themselves are NOT stored (reconstructibility
-- rule): they are computed on demand by the function below and only cached client-side for display.
--
-- Every statement is idempotent so re-applying is safe. Apply with `supabase db push` (or
-- scripts/deploy-supabase.bat).

create table if not exists public.device_active_session (
    user_id    uuid not null references auth.users (id) on delete cascade,
    device_id  text not null,
    start_ms   bigint not null,
    end_ms     bigint not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, device_id, start_ms)
);

alter table public.device_active_session enable row level security;

drop policy if exists "own active sessions" on public.device_active_session;
create policy "own active sessions" on public.device_active_session
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- Deduce the account-wide pauses over [p_since, p_until] (epoch millis) for the calling user: clip each
-- active interval to the window, UNION the overlapping/adjacent ones into maximal active spans (a classic
-- gaps-and-islands sweep), and return the gaps NOT covered by any span. The LEADING gap (window start ->
-- first activity) IS returned, so an account with no activity yet (freshly emptied) shows the whole window
-- as one pause and a short-running device shows the long stretch before it started. Only the TRAILING gap
-- (last activity -> p_until = now) is dropped: the tail is the still-open current session (its end lags
-- `now`) or an ongoing rather than past pause. Mirrors SchedulerDomain.derivePauses.
--
-- `security invoker` (the default) + the table's RLS scope it to the caller's own rows; the explicit
-- `user_id = auth.uid()` predicate also lets the primary-key index serve the scan. Exposed as a PostgREST
-- RPC (`POST /rest/v1/rpc/derive_pauses`).
create or replace function public.derive_pauses(p_since bigint, p_until bigint)
returns table (start_ms bigint, end_ms bigint)
language sql
stable
security invoker
set search_path = public
as $$
    with clipped as (
        select greatest(start_ms, p_since) as s,
               least(end_ms, p_until)       as e
        from public.device_active_session
        where user_id = auth.uid()
          and end_ms   > p_since
          and start_ms < p_until
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
    )
    select start_ms, end_ms
    from gaps
    where end_ms > start_ms
    union all
    select p_since, p_until
    where not exists (select 1 from islands)
    order by start_ms;
$$;

grant execute on function public.derive_pauses(bigint, bigint) to authenticated;
