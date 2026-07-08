-- OmniApp — remote force-logout marker (account_logout).
--
-- One row per user. `logout_at` is the instant every app on the account should sign itself out at: the
-- account-empty script (scripts/account1-empty-and-open.bat) upserts this row BEFORE it clears the
-- snapshot, so a still-running device on that account cannot re-seed the data the empty just deleted.
--
-- Each signed-in client records the current `logout_at` as a per-login baseline (sync_meta) and, on its
-- next reconcile, signs itself out (dropping the session locally, pushing nothing) when the server value
-- has advanced past that baseline. A device that logs in AFTER a logout reads the new value as its baseline
-- and so is unaffected. Same own-row RLS as the other tables, so the public anon key stays safe to ship.
--
-- Apply with the Supabase CLI (supabase db push) or scripts/deploy-supabase.bat. Idempotent.

create table if not exists public.account_logout (
    user_id    uuid primary key references auth.users (id) on delete cascade,
    logout_at  timestamptz not null default now()
);

alter table public.account_logout enable row level security;

drop policy if exists "own logout" on public.account_logout;
create policy "own logout" on public.account_logout
    for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- Stamp logout_at server-side on every insert AND update (the client upserts via merge-duplicates), so the
-- marker advances on each logout regardless of the caller's clock — mirrors touch_device_presence.
create or replace function public.touch_account_logout()
returns trigger language plpgsql as $$
begin
    new.logout_at = now();
    return new;
end;
$$;

drop trigger if exists touch_account_logout on public.account_logout;
create trigger touch_account_logout
    before insert or update on public.account_logout
    for each row execute function public.touch_account_logout();
