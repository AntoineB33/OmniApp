-- 2026-11-22 ACTIVITY SESSIONS PULLED BY REVISION (docs/invariants/sync-and-accounts.md § Sync,
-- SchedulerSyncEngine.syncActiveSessions).
--
-- Seen live: every reconcile downloaded every peer session row of the last week (~120 rows) because the only filter
-- was `end_ms >= horizon`. `updated_at` cannot serve as a cursor: it is each device's own clock, and a cursor over
-- clocks that disagree skips rows. So the rows take the one server-side REVISION every synced row already takes
-- (`scheduler_revision_seq`, migration 20260917000000), bumped on every write -- an upsert's DO UPDATE included -- and a
-- device asks for `revision > what it last saw`.
--
-- Adding the column gives every existing row a revision (the default is evaluated per row). Idempotent.

alter table public.device_active_session
    add column if not exists revision bigint not null default nextval('public.scheduler_revision_seq');

create index if not exists device_active_session_user_revision on public.device_active_session (user_id, revision);

drop trigger if exists device_active_session_revision on public.device_active_session;
create trigger device_active_session_revision
    before update on public.device_active_session
    for each row execute function public.bump_scheduler_revision();
