// OmniApp presence listener (PRD §15, Realtime-presence + external-listener model).
//
// pg_cron / the Edge Function cannot read Supabase Realtime Presence, so this small always-on process is the
// account's watcher. It holds a Realtime connection (as the service role, so it sees every account's presence)
// and, per account, when EVERY device drops out of presence AND the account is not in "sleeping" mode, it fires
// the phone's "your pause is over" cue at d − 1 s by invoking the existing `pause-cue` Edge Function, where
// d = the next ≥5-min break end that the last-active device published in its presence payload.
//
// It replaces the retired pg_cron `tick_pause_cues()` + derive_pauses machinery entirely. The only server
// objects it touches: the `presence:<user_id>` Realtime channels, and the `account_state` / `account_last_phone`
// tables (read). The actual FCM/APNs send stays in the `pause-cue` Edge Function (push credentials live there).
//
// Deploy on any always-on Node host (Render / Fly.io / Railway free tier). Env vars (see README):
//   SUPABASE_URL                – https://<ref>.supabase.co
//   SUPABASE_SERVICE_ROLE_KEY   – the service-role JWT (full access; bypasses RLS). KEEP SECRET.
//   EDGE_BASE_URL               – optional; defaults to `${SUPABASE_URL}/functions/v1`.

import { createClient } from "@supabase/supabase-js";

const SUPABASE_URL = requireEnv("SUPABASE_URL");
const SERVICE_ROLE_KEY = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
const EDGE_BASE_URL = process.env.EDGE_BASE_URL ?? `${SUPABASE_URL}/functions/v1`;

// How often to re-scan `account_last_phone` for accounts to watch (new accounts appear here on phone login).
const ACCOUNT_REFRESH_MS = 60_000;
// Fire the Edge push this long before the break end, so the phone schedules its exact local alarm at d.
const CUE_LEAD_MS = 1_000;

function requireEnv(name) {
  const v = process.env[name];
  if (!v) {
    console.error(`missing required env var ${name}`);
    process.exit(1);
  }
  return v;
}

const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

/** Per-account watch state, keyed by user_id. */
const accounts = new Map(); // user_id -> { channel, timer, pushedDueAt, lastBreakEndMs }

async function main() {
  console.log("omniapp-listener starting");
  await refreshAccounts();
  setInterval(() => refreshAccounts().catch((e) => console.error("account refresh failed:", e)), ACCOUNT_REFRESH_MS);
}

// Subscribe to any account we're not already watching. We use the service-role admin API to enumerate ALL
// accounts (not `account_last_phone`) so a desktop-only account — which never claims last-phone — is still
// watched; the account just won't get a cue until a phone has registered (account_last_phone + push token).
async function refreshAccounts() {
  let page = 1;
  while (true) {
    const { data, error } = await supabase.auth.admin.listUsers({ page, perPage: 200 });
    if (error) throw error;
    const users = data?.users ?? [];
    for (const user of users) {
      if (!accounts.has(user.id)) watchAccount(user.id);
    }
    if (users.length < 200) break;
    page += 1;
  }
}

function watchAccount(userId) {
  console.log(`watching account ${userId}`);
  const state = { channel: null, timer: null, pushedDueAt: null, lastBreakEndMs: null, lastActiveCount: -1 };
  accounts.set(userId, state);

  // Public presence channel (the clients join it with `private: false`); the topic matches the Kotlin client's
  // `realtime:presence:<user_id>`. We only READ presence — we never track ourselves.
  const channel = supabase.channel(`presence:${userId}`, {
    config: { presence: { key: "listener" } },
  });
  state.channel = channel;

  const onChange = () => evaluate(userId).catch((e) => console.error(`evaluate ${userId} failed:`, e));
  channel
    .on("presence", { event: "sync" }, onChange)
    .on("presence", { event: "join" }, onChange)
    .on("presence", { event: "leave" }, onChange)
    .subscribe((status) => console.log(`account ${userId} channel: ${status}`));
}

/**
 * Decide whether a cue is due for [userId] given the current presence. Active devices (presence keys other than
 * our own "listener") suppress/cancel the cue; when none remain and the account is working, schedule the cue at
 * the last-published break end − 1 s.
 */
async function evaluate(userId) {
  const state = accounts.get(userId);
  if (!state?.channel) return;

  const presence = state.channel.presenceState(); // { key: [ { device_id, kind, next_break_end_ms, ... } ] }
  const deviceMetas = Object.entries(presence)
    .filter(([key]) => key !== "listener")
    .flatMap(([, metas]) => metas);

  // Visible feedback: log whenever the number of active devices changes.
  if (state.lastActiveCount !== deviceMetas.length) {
    state.lastActiveCount = deviceMetas.length;
    const kinds = deviceMetas.map((m) => m.kind ?? "?").join(", ");
    console.log(`account ${userId}: ${deviceMetas.length} device(s) active${kinds ? ` [${kinds}]` : ""}`);
  }

  // Remember the earliest future break end published by any present device, so a 'leave' that has already
  // pruned the departing metas can still fall back to the last value seen while the device was here.
  const earliestFuture = deviceMetas
    .map((m) => (m.next_break_end_ms == null ? null : Number(m.next_break_end_ms)))
    .filter((d) => d != null && d > Date.now())
    .sort((a, b) => a - b)[0];
  if (earliestFuture != null) state.lastBreakEndMs = earliestFuture;

  if (deviceMetas.length > 0) {
    // A device is active → no cue. Cancel a pending timer and, if we already pushed a schedule, cancel it.
    clearScheduled(userId, /* pushCancel */ true);
    return;
  }

  // Nobody active. Suppress while the user is deliberately away (Sleep pressed and wake not yet reached).
  const { data: acct } = await supabase
    .from("account_state")
    .select("mode, wake_at")
    .eq("user_id", userId)
    .maybeSingle();
  const sleeping = acct?.mode === "sleeping" && acct?.wake_at != null && new Date(acct.wake_at).getTime() > Date.now();
  if (sleeping) {
    clearScheduled(userId, false);
    return;
  }

  // The next break end is the last one any device published (captured above while present).
  const target = state.lastBreakEndMs;
  if (target == null || target <= Date.now()) {
    clearScheduled(userId, false);
    return;
  }

  scheduleCue(userId, target);
}

function scheduleCue(userId, dueAtMs) {
  const state = accounts.get(userId);
  if (!state) return;
  if (state.pushedDueAt === dueAtMs) return; // already scheduled for this instant
  clearScheduled(userId, false);

  const fireInMs = Math.max(0, dueAtMs - CUE_LEAD_MS - Date.now());
  state.timer = setTimeout(() => fireCue(userId, dueAtMs).catch((e) => console.error(`fire ${userId} failed:`, e)), fireInMs);
  console.log(`account ${userId}: cue scheduled for ${new Date(dueAtMs).toISOString()} (in ${Math.round(fireInMs / 1000)}s)`);
}

async function fireCue(userId, dueAtMs) {
  const state = accounts.get(userId);
  if (!state) return;
  state.timer = null;

  // Re-check: a device may have become active during the wait.
  const presence = state.channel.presenceState();
  const stillIdle = Object.keys(presence).filter((k) => k !== "listener").length === 0;
  if (!stillIdle) return;

  const { data: phone } = await supabase
    .from("account_last_phone")
    .select("device_id")
    .eq("user_id", userId)
    .maybeSingle();
  if (!phone?.device_id) {
    console.log(`account ${userId}: no last phone to cue`);
    return;
  }

  await invokeEdge(userId, phone.device_id, "schedule", new Date(dueAtMs).toISOString());
  state.pushedDueAt = dueAtMs;
  console.log(`account ${userId}: pushed pause-cue schedule to ${phone.device_id} for ${new Date(dueAtMs).toISOString()}`);
}

function clearScheduled(userId, pushCancel) {
  const state = accounts.get(userId);
  if (!state) return;
  if (state.timer) {
    clearTimeout(state.timer);
    state.timer = null;
  }
  if (pushCancel && state.pushedDueAt != null && state.pushedDueAt > Date.now()) {
    supabase
      .from("account_last_phone")
      .select("device_id")
      .eq("user_id", userId)
      .maybeSingle()
      .then(({ data }) => data?.device_id && invokeEdge(userId, data.device_id, "cancel", null))
      .catch((e) => console.error(`cancel ${userId} failed:`, e));
  }
  state.pushedDueAt = null;
}

async function invokeEdge(userId, deviceId, action, dueAtIso) {
  const body = { user_id: userId, device_id: deviceId, action };
  if (dueAtIso) body.due_at = dueAtIso;
  const res = await fetch(`${EDGE_BASE_URL}/pause-cue`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) console.error(`pause-cue edge ${action} → ${res.status}: ${await res.text()}`);
}

main().catch((e) => {
  console.error("listener crashed:", e);
  process.exit(1);
});
