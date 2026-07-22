-- Bidirectional sync (PRD §5): enable Supabase Realtime `postgres_changes` on the whole-document snapshot
-- table so a device can subscribe to its own row and auto-pull a peer's push the instant it lands (previously
-- Realtime carried only presence; snapshot convergence was manual button-only).
--
-- REPLICA IDENTITY FULL so UPDATE change events carry the full row (and, with RLS, so the change is authorized
-- against the row's `user_id`). The subscription authenticates with the signed-in user's JWT and the existing
-- "own snapshot" RLS policy (auth.uid() = user_id) already restricts reads to the owner, so a peer can only
-- ever receive its own account's row — no new policy is needed.
alter table public.scheduler_snapshot replica identity full;

-- Add the table to the Realtime publication. Idempotent: re-running (or a project where it is already a member)
-- must not fail the migration, so swallow the duplicate.
do $$
begin
    alter publication supabase_realtime add table public.scheduler_snapshot;
exception
    when duplicate_object then null;
end
$$;
