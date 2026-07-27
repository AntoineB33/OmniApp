"""Supabase account admin for the per-account scripts (see scripts/account*-*.bat).

Three subcommands, all taking a username + password (the username is an arbitrary handle, mapped to
"<user>@<LOGIN_DOMAIN>" to match the app's StartupLogin.usernameToEmail):

  signup <user> <pass>   Create the account (idempotent: "already registered" is treated as success).
  logout <user> <pass>   Sign in, then bump this account's `account_logout` marker so every SIGNED-IN app on
                         the account signs itself out on its next reconcile (dropping the session, pushing
                         nothing). Run this BEFORE `empty` so a still-running device can't re-seed the data
                         the empty deletes. An app that logs in afterwards reads the new marker as its
                         baseline and is unaffected. Exits 3 (not 1) if the `account_logout` table is missing,
                         so the caller can deploy-supabase.bat then retry. Pass --wait on that retry: right
                         after a migration the table exists in Postgres but PostgREST answers PGRST205 until
                         it reloads its schema cache, so --wait polls through that lag instead of failing.
  empty  <user> <pass>   Sign in, then DELETE this account's synced data so every device for it goes
                         empty -- the `scheduler_snapshot` row, the `device_heartbeat` presence rows, the
                         `device_break` next-break row, the `data_payload_sent` claim row, the
                         `pause_cue_schedule` bookkeeping, all `device_active_session` rows, and the retired
                         `device_presence` / `device_sleep_gap` rows. It deliberately does NOT clear
                         `account_logout` (that marker must survive so the running apps still see it) nor
                         `app_config` / `break_config` (operator tuning set over HTTP, not account data).

  break-length <user> <pass> <break_kind> <ms>
                         Sign in, then upsert `break_config.length_ms` for one break kind ('5min_break' or
                         '15min_break'); `ms` of 0 or "default" clears it back to null. This is how long the
                         SERVER waits after the walk-away before pushing "your pause is over" -- since
                         migration 20260728000000 the client publishes only WHEN each break comes due, never
                         how long it lasts, so the debug fast-break knobs (OMNIAPP_BREAK_DURATION_MS) no
                         longer reach the server on their own. The fast-break .bat scripts call this so the
                         cue still arrives seconds after the walk-away instead of 5 minutes later. Never
                         needed in production, where the kind's own 5/15-min default is correct.

Config comes from the environment (the .bat exports it from accounts.env), falling back to the public
values baked into shared SupabaseConfig.kt:
  SUPABASE_URL, SUPABASE_ANON_KEY, LOGIN_DOMAIN

The `empty` DELETEs run under the user's own JWT, so each table needs a row-owner DELETE policy. This is
already covered by the `for all` policies in supabase/migrations/ (a Postgres `for all` policy applies to
SELECT/INSERT/UPDATE/DELETE), so a standard setup needs nothing extra. Only if you replaced those with
narrower per-command policies that omit DELETE, add one back, e.g.:

  create policy "own delete" on public.scheduler_snapshot for delete using (auth.uid() = user_id);

Stdlib only (urllib) -- no pip installs. Exit code 0 on success, non-zero on failure.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_URL = "https://itoaqbftjemovkzswiyu.supabase.co"
DEFAULT_ANON_KEY = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
    "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Iml0b2FxYmZ0amVtb3ZrenN3aXl1Iiwicm9sZSI6ImFub24i"
    "LCJpYXQiOjE3ODI0NzE1ODQsImV4cCI6MjA5ODA0NzU4NH0."
    "ruJWPDi4XAbD8brDe_-wVQP1MVTkG9eSvhhae_6dIqs"
)
DEFAULT_DOMAIN = "omniapp.local"


def cfg():
    base = os.environ.get("SUPABASE_URL", DEFAULT_URL).rstrip("/")
    key = os.environ.get("SUPABASE_ANON_KEY", DEFAULT_ANON_KEY)
    domain = os.environ.get("LOGIN_DOMAIN", DEFAULT_DOMAIN)
    return base, key, domain


def username_to_email(user, domain):
    return user if "@" in user else "{}@{}".format(user, domain)


def _request(method, url, key, body=None, token=None, prefer=None):
    headers = {"apikey": key, "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if prefer:
        headers["Prefer"] = prefer
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw}


def sign_in(base, key, email, password):
    status, payload = _request(
        "POST", base + "/auth/v1/token?grant_type=password", key, {"email": email, "password": password}
    )
    if status != 200:
        raise SystemExit("    [x] sign-in failed for {} ({}): {}".format(email, status, payload))
    return payload["access_token"], payload["user"]["id"]


def cmd_signup(user, password):
    base, key, domain = cfg()
    email = username_to_email(user, domain)
    status, payload = _request("POST", base + "/auth/v1/signup", key, {"email": email, "password": password})
    if status in (200, 201):
        print("    Created account {}.".format(email))
        return 0
    msg = json.dumps(payload) if payload else ""
    if "already" in msg.lower() or "registered" in msg.lower() or status == 422:
        print("    Account {} already exists - nothing to do.".format(email))
        return 0
    print("    [x] signup failed for {} ({}): {}".format(email, status, msg))
    return 1


# How long to keep retrying a "table missing" logout when --wait is set, and the gap between attempts.
# A freshly-applied migration exists in Postgres immediately, but PostgREST answers PGRST205 ("not in the
# schema cache") until it reloads its cache off the DDL event trigger -- normally within a few seconds.
SCHEMA_CACHE_WAIT_SECS = 90
SCHEMA_CACHE_POLL_SECS = 3


def cmd_logout(user, password, wait_for_schema=False):
    base, key, domain = cfg()
    email = username_to_email(user, domain)
    token, uid = sign_in(base, key, email, password)
    # Upsert the single per-user row; the touch_account_logout trigger stamps logout_at = now() on insert AND
    # update, so merge-duplicates makes a repeat advance the marker rather than 409-conflict.
    url = "{}/rest/v1/account_logout".format(base)
    deadline = time.time() + (SCHEMA_CACHE_WAIT_SECS if wait_for_schema else 0)
    announced_wait = False
    while True:
        status, payload = _request(
            "POST", url, key, body={"user_id": uid}, token=token,
            prefer="resolution=merge-duplicates,return=minimal",
        )
        if status in (200, 201, 204):
            print("    Signed out all apps for {} (account_logout marked).".format(email))
            return 0
        code = payload.get("code") if isinstance(payload, dict) else None
        table_missing = status == 404 or code == "PGRST205"
        if table_missing and wait_for_schema and time.time() < deadline:
            # The migration just ran; the table exists in Postgres but PostgREST hasn't reloaded its schema
            # cache yet. Poll until it does (or the deadline lapses) instead of failing the whole script.
            if not announced_wait:
                print("    [~] account_logout not in PostgREST's schema cache yet -- waiting for reload...")
                announced_wait = True
            time.sleep(SCHEMA_CACHE_POLL_SECS)
            continue
        if table_missing:
            # Distinct exit code 3 = "schema out of date"; the caller can auto-run deploy-supabase.bat and
            # retry with --wait. (A generic failure below returns 1, which must NOT trigger an auto-deploy --
            # e.g. a wrong password.)
            print("    [x] account_logout table missing -- apply supabase/migrations (deploy-supabase.bat).")
            return 3
        print("    [x] failed to set logout marker ({}): {}".format(status, payload))
        return 1


def cmd_empty(user, password):
    base, key, domain = cfg()
    email = username_to_email(user, domain)
    token, uid = sign_in(base, key, email, password)
    # device_heartbeat is the presence table, device_break the next-break table, data_payload_sent the claim
    # flag, pause_cue_schedule the cue bookkeeping (PRD §15): a stale presence row plus an unclaimed
    # data_payload_sent row left behind would make the emptied account read as "idle" on the next server tick
    # and fire a pause cue for data that no longer exists -- and a stale device_break row would tell it WHICH
    # break to speak. device_presence / device_sleep_gap are retired tables, kept here so an older project still
    # gets cleaned. app_config / break_config are deliberately NOT cleared: they are operator tuning set over
    # HTTP (t_a, break lengths/messages), not account data.
    for table in (
        "scheduler_snapshot",
        "device_heartbeat",
        "device_break",
        "data_payload_sent",
        "pause_cue_schedule",
        "device_presence",
        "device_sleep_gap",
        "device_active_session",
    ):
        url = "{}/rest/v1/{}?user_id=eq.{}".format(base, table, uid)
        status, payload = _request("DELETE", url, key, token=token)
        if status in (200, 204):
            print("    Cleared {} for {}.".format(table, email))
            continue
        # A missing table just means there is no such data to clear -- not a failure.
        code = payload.get("code") if isinstance(payload, dict) else None
        if status == 404 or code == "PGRST205":
            print("    Skipped {} (table not present; nothing to clear).".format(table))
            continue
        print("    [x] failed to clear {} ({}): {}".format(table, status, payload))
        return 1
    return 0


BREAK_KINDS = ("5min_break", "15min_break")


def cmd_break_length(user, password, break_kind, millis):
    """Set (or clear) the SERVER-side length of one break kind -- the delay between the walk-away instant and
    the pushed "your pause is over" cue.

    Since migration 20260728000000 the client's `device_break` row carries only WHEN each break comes due, so
    `break_config.length_ms` is the only way to shorten the cue delay for a debug run. Null (0/"default")
    restores the kind's built-in 5/15-min duration.
    """
    if break_kind not in BREAK_KINDS:
        print("    [x] break kind must be one of {}, got {}.".format("/".join(BREAK_KINDS), break_kind))
        return 2
    base, key, domain = cfg()
    email = username_to_email(user, domain)
    token, uid = sign_in(base, key, email, password)
    length = None if str(millis).lower() in ("0", "default", "null") else int(millis)
    row = {"user_id": uid, "break_kind": break_kind, "length_ms": length}
    status, payload = _request(
        "POST", base + "/rest/v1/break_config", key, [row],
        token=token, prefer="resolution=merge-duplicates",
    )
    if status in (200, 201, 204):
        print("    Set break_config.length_ms for {} to {}.".format(break_kind, length if length else "default"))
        return 0
    # A project that has not applied the t_a/t_b migrations yet has no break_config; the caller (a debug
    # convenience script) must not fail hard for that.
    code = payload.get("code") if isinstance(payload, dict) else None
    if status == 404 or code == "PGRST205":
        print("    Skipped break_config (table not present -- run deploy-supabase.bat to enable it).")
        return 0
    print("    [x] failed to set break_config ({}): {}".format(status, payload))
    return 1


def main(argv):
    if len(argv) < 4:
        print("usage: account_db_admin.py <signup|logout|empty|break-length> <user> <pass> [args]")
        return 2
    action, user, password = argv[1], argv[2], argv[3]
    flags = argv[4:]
    if action == "signup":
        return cmd_signup(user, password)
    if action == "logout":
        return cmd_logout(user, password, wait_for_schema="--wait" in flags)
    if action == "empty":
        return cmd_empty(user, password)
    if action == "break-length":
        if len(flags) < 2:
            print("usage: account_db_admin.py break-length <user> <pass> <break_kind> <ms|default>")
            return 2
        return cmd_break_length(user, password, flags[0], flags[1])
    print("unknown action: {}".format(action))
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
