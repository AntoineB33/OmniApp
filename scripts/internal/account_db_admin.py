"""Supabase account admin for the per-account scripts (see scripts/account*-*.bat).

Two subcommands, both taking a username + password (the username is an arbitrary handle, mapped to
"<user>@<LOGIN_DOMAIN>" to match the app's StartupLogin.usernameToEmail):

  signup <user> <pass>   Create the account (idempotent: "already registered" is treated as success).
  empty  <user> <pass>   Sign in, then DELETE this account's synced data so every device for it goes
                         empty -- the `scheduler_snapshot` row, all `device_presence` rows, and all
                         `device_sleep_gap` rows.

Config comes from the environment (the .bat exports it from accounts.env), falling back to the public
values baked into shared SupabaseConfig.kt:
  SUPABASE_URL, SUPABASE_ANON_KEY, LOGIN_DOMAIN

The `empty` DELETEs run under the user's own JWT, so the Supabase tables need a row-owner DELETE policy.
If yours lack one, paste this once into the Supabase SQL editor:

  create policy "own delete" on public.scheduler_snapshot for delete using (auth.uid() = user_id);
  create policy "own delete" on public.device_presence  for delete using (auth.uid() = user_id);
  create policy "own delete" on public.device_sleep_gap  for delete using (auth.uid() = user_id);

Stdlib only (urllib) -- no pip installs. Exit code 0 on success, non-zero on failure.
"""

import json
import os
import sys
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


def _request(method, url, key, body=None, token=None):
    headers = {"apikey": key, "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
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


def cmd_empty(user, password):
    base, key, domain = cfg()
    email = username_to_email(user, domain)
    token, uid = sign_in(base, key, email, password)
    for table in ("scheduler_snapshot", "device_presence", "device_sleep_gap"):
        url = "{}/rest/v1/{}?user_id=eq.{}".format(base, table, uid)
        status, payload = _request("DELETE", url, key, token=token)
        if status in (200, 204):
            print("    Cleared {} for {}.".format(table, email))
            continue
        # A missing table just means there is no such data to clear -- not a failure.
        # device_presence (PRD §15) is optional and may not exist in every project.
        code = payload.get("code") if isinstance(payload, dict) else None
        if status == 404 or code == "PGRST205":
            print("    Skipped {} (table not present; nothing to clear).".format(table))
            continue
        print("    [x] failed to clear {} ({}): {}".format(table, status, payload))
        return 1
    return 0


def main(argv):
    if len(argv) < 4:
        print("usage: account_db_admin.py <signup|empty> <user> <pass>")
        return 2
    action, user, password = argv[1], argv[2], argv[3]
    if action == "signup":
        return cmd_signup(user, password)
    if action == "empty":
        return cmd_empty(user, password)
    print("unknown action: {}".format(action))
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
