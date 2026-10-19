-- THE CLAUDE CODE LOGS THE ACCOUNT KEEPS (shared/src/commonMain/kotlin/org/example/project/claudelogs/ClaudeLogArchive.kt).
--
-- User rule 2026-10-19: "when the program gets logs from the local folder, it stays in the account's memory. If the
-- folder got deleted, the app keeps the logs. The limit of the memory is adapted for each device or server."
--
-- One row per prompt, answer or reasoning chain a device read off its Claude Code folder. Each device keeps its own
-- archive (its limit is the platform's); THIS table is what the account's other devices pull from, and it is BOUNDED:
-- the newest rows of an account whose size_bytes add up to 409600 (400 KB) are kept, the older ones deleted by the
-- trigger below at every insert. docs/invariants/server-quota.md: three accounts at that bound, plus the room a
-- trim's deleted rows take until autovacuum, fit in what the 10 % database budget had left. ServerQuotaTest reads the
-- bound off this file.
--
-- WRITTEN ONCE. A log entry never changes: the client inserts with ignore-duplicates, so a re-send writes nothing and
-- leaves no dead row version. NOT in the Realtime publication and it does not bump scheduler_head: a device pulls the
-- logs when its logs page is shown, never because another device wrote one.
--
-- Idempotent: safe to re-run.

create sequence if not exists public.claude_log_revision_seq;

create table if not exists public.claude_log_entry (
    user_id       uuid    not null references auth.users (id) on delete cascade,
    entry_id      text    not null,
    device_id     text    not null,
    kind          text    not null,
    time_millis   bigint,
    session       text    not null,
    session_title text,
    project       text    not null,
    ordinal       integer not null,
    body          text    not null,
    -- What the row counts for against the bound: its texts plus what its other columns and its tuple cost
    -- (ClaudeLogArchive.sizeOf, ROW_OVERHEAD_BYTES), computed here so no client can say less.
    size_bytes    bigint generated always as
        (octet_length(body) + coalesce(octet_length(session_title), 0) + octet_length(project) + 300) stored,
    revision      bigint  not null default nextval('public.claude_log_revision_seq'),
    primary key (user_id, entry_id)
);
create index if not exists claude_log_entry_user_revision on public.claude_log_entry (user_id, revision);

alter table public.claude_log_entry enable row level security;
drop policy if exists "own claude logs" on public.claude_log_entry;
create policy "own claude logs" on public.claude_log_entry
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- The account keeps its newest rows up to the bound: newest first (by time, then by place in the session, an entry
-- with no time counted oldest), the first row that would pass it and every older one deleted.
create or replace function public.trim_claude_log_entries() returns trigger
language plpgsql security definer set search_path = public as $$
begin
    delete from public.claude_log_entry c
    using (
        select e.user_id, e.entry_id,
               sum(e.size_bytes) over (
                   partition by e.user_id
                   order by e.time_millis desc nulls last, e.ordinal desc
                   rows between unbounded preceding and current row
               ) as running
        from public.claude_log_entry e
        where e.user_id in (select distinct n.user_id from added n)
    ) ranked
    where ranked.running > 409600
      and c.user_id = ranked.user_id and c.entry_id = ranked.entry_id;
    return null;
end
$$;

drop trigger if exists claude_log_entry_trim on public.claude_log_entry;
create trigger claude_log_entry_trim
    after insert on public.claude_log_entry
    referencing new table as added
    for each statement execute function public.trim_claude_log_entries();
