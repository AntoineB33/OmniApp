-- ONE DEVICE PLANS (docs/invariants/scheduler.md section *One device plans*).
--
-- When the rules must be re-planned, the account's devices elect the one that runs the scheduler and it
-- broadcasts the set of rules it returned; the others take them in instead of searching themselves. They talk over
-- a Supabase Realtime BROADCAST channel, one per account:
--
--     topic  scheduler:<auth.uid()>      (the client joins realtime:scheduler:<userId>)
--
-- Nothing on it is stored: a broadcast is not a row, it reaches the sockets connected at that instant and is gone,
-- and a device that missed it plans for itself after a deadline. So this migration adds no table. It only makes the
-- channel PRIVATE to the account: a private channel's join, read and send are authorized against RLS on
-- realtime.messages, and the two policies below admit a signed-in user (a guest account is one too) to its own
-- account's topic and to nothing else. Without them the join is refused and every device keeps planning locally,
-- which is how the app behaved before.
--
-- Traffic: one re-plan costs a probe, one reply per unlocked device, one announcement and one message per
-- progressive stage (about ten) -- Realtime messages, not database writes or Edge Function calls.
--
-- Idempotent: safe to re-run.

drop policy if exists scheduler_peers_receive on realtime.messages;
create policy scheduler_peers_receive
    on realtime.messages
    for select
    to authenticated
    using (
        realtime.messages.extension = 'broadcast'
        and realtime.topic() = 'scheduler:' || (select auth.uid())::text
    );

drop policy if exists scheduler_peers_send on realtime.messages;
create policy scheduler_peers_send
    on realtime.messages
    for insert
    to authenticated
    with check (
        realtime.messages.extension = 'broadcast'
        and realtime.topic() = 'scheduler:' || (select auth.uid())::text
    );
