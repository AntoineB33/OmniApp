-- `t_a` 10 s -> 60 s (user, 2026-10-16: "keep it but beat less often").
--
-- WHY. The presence beat is the largest share of the app's requests (ServerQuotaTest: 421 an hour of the heavy
-- scenario), and on Windows and Android it is only the BACKSTOP: a clean lock reports itself at once (e1,
-- `pause-cue`). What the beat still covers is a lock report that failed, an app that died unlocked, a device that
-- lost power. At 60 s it costs a sixth as much, and those cases are answered later and less exactly:
--   * the walk-away is estimated at <last beat> + t_a/2 -- on average 30 s after the last beat instead of 5 s;
--   * an account is idle once its newest beat is older than 2*t_a, and the cron looks every minute (`t_b`), so it is
--     caught up to 2*60 + 60 s = 3 min after the last beat instead of 80 s;
--   * a break counts as overdue when its due is <= the last beat + t_a: one coming due within 60 s of the walk-away
--     (rather than 10 s) is treated as the break the user walked away to take.
--
-- WHAT. The default of a new app_config row, the fallback for an account without one (`tick_seconds_for`), and every
-- existing row below 60 s (the old default, or a value left from debugging) -- the user asked for fewer beats, not
-- for the accounts that already beat faster to keep doing so. A row above 60 s is left as it was. The client's own
-- default (`DeviceHeartbeatPublisher.DEFAULT_TICK_SECONDS`, used until the first beat's reply) is 60 too, and
-- `PresenceTickDefaultTest` fails when the two differ.
--
-- Idempotent: safe to re-run.

alter table public.app_config alter column tick_seconds set default 60;

create or replace function public.tick_seconds_for(p_user uuid)
returns integer language sql stable as $$
    select coalesce((select c.tick_seconds from public.app_config c where c.user_id = p_user), 60);
$$;

update public.app_config set tick_seconds = 60, updated_at = now() where tick_seconds < 60;
