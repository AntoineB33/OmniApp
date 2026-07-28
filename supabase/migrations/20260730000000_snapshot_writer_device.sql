-- Record WHICH DEVICE wrote each `scheduler_snapshot` revision, so a device can never pull its own
-- write over its own newer local edits.
--
-- The failure this repairs (observed 2026-07-27/28 on account 3, one single device): the client PATCHes
-- the row at `revision = eq.N`; Postgres applies it (revision -> N+1) and the RESPONSE is lost — a dropped
-- connection, the machine suspending mid-request. `RemoteSnapshotClient.update` throws, so
-- `SchedulerSyncEngine` never records the new baseline: local stays at N with `dirty` still set. The next
-- reconcile fetches, sees `remote.revision (N+1) > lastKnownRevision (N)`, reads that as "a peer wrote
-- something newer", and takes the whole-document last-write-wins PULL branch — applying THIS DEVICE'S OWN
-- older push over whatever the user has edited since, silently. The user's symptom: "I deleted two task
-- cells and they came back."
--
-- `writer_device_id` is the discriminator that tells our own unacknowledged push from a genuine peer write.
-- Nullable on purpose: rows written by an older client (or before this migration) carry NULL, which the
-- client treats as "unknown writer" and falls back to the existing LWW pull — so an un-redeployed app and
-- a migrated project keep working, they just don't get the repair. The client sends the id it already has
-- (`sync_meta.device_id`, allocated once per install).
--
-- Idempotent, like every migration here. Apply with scripts/deploy-supabase.bat.

alter table public.scheduler_snapshot
    add column if not exists writer_device_id text;

comment on column public.scheduler_snapshot.writer_device_id is
    'Device id (sync_meta.device_id) that wrote this revision. Lets a device recognise its own '
    'lost-acknowledgement push instead of pulling it back over newer local edits. NULL = written by a '
    'client older than migration 20260730000000.';
