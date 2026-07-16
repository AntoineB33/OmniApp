-- PRD §15: re-add `device_active_session`, dropped by 20260713000000 with the retired REST side channels.
--
-- It comes back under the button-only sync model, not the old heartbeat one: rows are written ONLY inside
-- the manual Sync-button reconcile (each device pushes its own recent rows, pulls every peer's) — there is
-- no per-beat REST traffic and no server-side derivation (`derive_pauses` stays deleted; each client derives
-- pauses locally over the merged rows). The data feeds the calendar's "which devices were open" hover bubble
-- on past panels and the dashed separators where the device set changed.
--
-- New vs. the old shape: `kind` — the recording install's device kind ('desktop'/'phone'/'other'), the
-- label the bubble shows. The phone's rows are one-minute foreground leases (end_ms = lease end, up to a
-- minute past the last renewal); the desktop's are heartbeat-extended sessions.
--
-- Idempotent; apply with scripts/deploy-supabase.bat (supabase db push). The account-empty script already
-- clears this table (scripts/internal/account_db_admin.py).

create table if not exists public.device_active_session (
    user_id    uuid not null references auth.users (id) on delete cascade,
    device_id  text not null,
    start_ms   bigint not null,
    end_ms     bigint not null,
    -- Epoch millis supplied by the client (NOT timestamptz — the client reports bigints; see the 20260706
    -- note on the previous incarnation of this table).
    updated_at bigint not null,
    kind       text not null default '',
    primary key (user_id, device_id, start_ms)
);

alter table public.device_active_session enable row level security;

drop policy if exists "own active sessions" on public.device_active_session;
create policy "own active sessions" on public.device_active_session
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);
