-- THE CLAUDE CODE LOGS, SENT AS THEY ARE WRITTEN (user rule 2026-11-01): what an account keeps goes from 400 KB to 300 KB.
--
-- A device now inserts while Claude Code writes, so the trim below runs at every burst and its deleted rows take room
-- until autovacuum. docs/invariants/server-quota.md: with the bound at 400 KB the heavy month came to more than the 10 %
-- database budget; ClaudeLogArchive.SERVER_LIMIT_BYTES says the same figure and ServerQuotaTest reads it off this file.
-- The rows over the new bound go at the account's next insert.
--
-- Idempotent: safe to re-run.

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
    where ranked.running > 307200
      and c.user_id = ranked.user_id and c.entry_id = ranked.entry_id;
    return null;
end
$$;
