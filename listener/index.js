// OmniApp presence listener (PRD §15, Realtime-presence + external-listener model).
//
// pg_cron / the Edge Function cannot read Supabase Realtime Presence, so this small always-on process is the
// account's watcher. It holds a Realtime connection (as the service role, so it sees every account's presence)
// and, per account, when EVERY device drops out of presence AND the account is not in "sleeping" mode, it fires
// the "your pause is over" cue at d − 1 s by invoking the existing `pause-cue` Edge Function, fanned out to
// ALL the account's registered phones (device_id "*").
//
// PRD §15 server-side break computation: the cue instant is anchored to the REAL moment the last device left
// presence, not to a client projection. Each active device publishes its next rest-pose WINDOW
// (`next_break_start_ms` + `next_break_len_ms`; a start at/before "now" means the break is already waiting,
// because an overdue-unserved pose rides the client's now-line). When the account goes idle at T:
//     d = max(T, next_break_start_ms) + next_break_len_ms
// — e.g. with 15-min breaks pending, the cue lands 15 min after the start of the all-disconnected window,
// however many of them had accumulated (one real pause serves the pending pose). The legacy
// `next_break_end_ms` is kept as a fallback for clients that don't publish the window yet.
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
// A presence meta counts as a LIVE device only if its real-clock heartbeat stamp (`published_at_ms`) is within
// this window. Ghost connections — left by every reconnect and by past app runs that rejoin under a new
// device_id — keep their (frozen or absent) stamp and are ignored, so the account can actually reach "idle".
// Must comfortably exceed the client's presence re-publish interval (prod beat: 60 s phone / 30 s desktop).
const PRESENCE_STALE_MS = 150_000;
// Re-run evaluate() for every watched account on this cadence, INDEPENDENTLY of Realtime presence events. The
// staleness filter above can only age a ghost out when evaluate actually runs, but a device that stops
// heart-beating without a clean `untrack` (phone suspended on lock/close before it can leave) produces NO
// presence event — so without this tick the account would stay "active" against a frozen meta forever and the
// cue would never fire (the observed "closed both devices, no voice message" bug). The tick makes idle
// detection a function of the clock, not of whether Phoenix delivered a leave. Cheap: an already-armed account
// short-circuits before touching the DB (see evaluate()).
const EVALUATE_TICK_MS = 10_000;

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
const accounts = new Map(); // user_id -> { channel, timer, pushedDueAt, lastBreakEndMs, lastBreakWindow, disconnectStartMs }

async function main() {
  console.log("omniapp-listener starting");
  await refreshAccounts();
  setInterval(() => refreshAccounts().catch((e) => console.error("account refresh failed:", e)), ACCOUNT_REFRESH_MS);
  // Clock-driven re-evaluation: catch stale devices that never sent a leave (see EVALUATE_TICK_MS).
  setInterval(() => {
    for (const userId of accounts.keys()) evaluate(userId).catch((e) => console.error(`tick ${userId} failed:`, e));
  }, EVALUATE_TICK_MS);
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
  const state = {
    channel: null,
    timer: null,
    pushedDueAt: null,
    // The instant a one-shot timer is currently armed for (null when no timer). Lets the periodic tick detect
    // "already scheduled for this instant" and NOT re-arm the timer every tick — re-arming would reset a timer
    // whose fire time is further out than the tick interval, so a long break would never fire.
    scheduledDueAt: null,
    lastBreakEndMs: null,
    // { startMs, lenMs } — the last rest-pose window any present device published (PRD §15).
    lastBreakWindow: null,
    // The instant the account's LAST device left presence (null while any device is active).
    disconnectStartMs: null,
    lastActiveCount: -1,
  };
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
  const nowMs = Date.now();
  // Presence metas accumulate GHOST entries: every reconnect (and every past app run, which rejoins with a new
  // device_id) leaves a stale connection that lingers until its heartbeat times out. Counting them all made the
  // account look perpetually active (seen: "191 device(s) active"), so the cue never fired. Trust a meta only
  // if it carries a FRESH real-clock `published_at_ms` (live clients re-stamp every beat; a ghost's stamp is
  // frozen or, for a pre-heartbeat client, absent), then keep only the freshest meta per device_id.
  const freshByDevice = new Map();
  for (const [key, metas] of Object.entries(presence)) {
    if (key === "listener") continue;
    for (const m of metas) {
      const ts = m.published_at_ms == null ? null : Number(m.published_at_ms);
      if (ts == null || !Number.isFinite(ts) || nowMs - ts > PRESENCE_STALE_MS) continue;
      const prev = freshByDevice.get(m.device_id);
      if (prev == null || ts > Number(prev.published_at_ms)) freshByDevice.set(m.device_id, m);
    }
  }
  const deviceMetas = [...freshByDevice.values()];

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

  // PRD §15: the rest-pose WINDOW (start + length) — the soonest-ending one any present device publishes.
  // Captured while devices are here so the trailing 'leave' still has it; the fire time is computed from
  // the real disconnect instant below.
  const windows = deviceMetas
    .filter((m) => m.next_break_start_ms != null && m.next_break_len_ms != null)
    .map((m) => ({ startMs: Number(m.next_break_start_ms), lenMs: Number(m.next_break_len_ms) }))
    .filter((w) => Number.isFinite(w.startMs) && Number.isFinite(w.lenMs) && w.lenMs > 0)
    .sort((a, b) => a.startMs + a.lenMs - (b.startMs + b.lenMs));
  if (windows.length > 0) state.lastBreakWindow = windows[0];

  if (deviceMetas.length > 0) {
    // A device is active → no cue. Cancel a pending timer and, if we already pushed a schedule, cancel it.
    state.disconnectStartMs = null;
    clearScheduled(userId, /* pushCancel */ true);
    return;
  }

  // The account just went fully idle: anchor the disconnect-start to NOW (the real instant we OBSERVED the last
  // device gone — a clean leave on the same beat, else the periodic tick that first noticed the stale meta; on
  // a listener restart into an already-idle account this is conservatively the restart instant). The break end
  // is measured from here, so a fast-break cue fires `len` after we notice idle, not `len` after the frozen
  // stamp (which for a short break is already in the past → would suppress the cue entirely).
  if (state.disconnectStartMs == null) state.disconnectStartMs = Date.now();

  // PRD §15 server-side computation: pause end = max(disconnectStart, poseStart) + poseLength. Falls back
  // to the client-projected `next_break_end_ms` for clients that don't publish the window yet.
  const w = state.lastBreakWindow;
  const target = w != null
    ? Math.max(state.disconnectStartMs, w.startMs) + w.lenMs
    : state.lastBreakEndMs;
  if (target == null || target <= Date.now()) {
    clearScheduled(userId, false);
    return;
  }

  // Already armed or already pushed for this exact instant → nothing to do. Return BEFORE the account_state
  // query so the 10 s tick doesn't hammer the DB for every idle account each cycle (only the transition, and
  // any window change, hits the DB).
  if (state.pushedDueAt === target || (state.timer != null && state.scheduledDueAt === target)) return;

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

  scheduleCue(userId, target, w?.lenMs ?? null);
}

function scheduleCue(userId, dueAtMs, breakLenMs) {
  const state = accounts.get(userId);
  if (!state) return;
  if (state.pushedDueAt === dueAtMs) return; // already pushed for this instant
  if (state.timer != null && state.scheduledDueAt === dueAtMs) return; // already armed for this instant
  clearScheduled(userId, false);

  const fireInMs = Math.max(0, dueAtMs - CUE_LEAD_MS - Date.now());
  state.scheduledDueAt = dueAtMs;
  state.timer = setTimeout(
    () => fireCue(userId, dueAtMs, breakLenMs).catch((e) => console.error(`fire ${userId} failed:`, e)),
    fireInMs,
  );
  console.log(`account ${userId}: cue scheduled for ${new Date(dueAtMs).toISOString()} (in ${Math.round(fireInMs / 1000)}s${breakLenMs != null ? `, ${Math.round(breakLenMs / 60000)}-min break` : ""})`);
}

async function fireCue(userId, dueAtMs, breakLenMs) {
  const state = accounts.get(userId);
  if (!state) return;
  state.timer = null;
  state.scheduledDueAt = null;

  // Re-check: a device may have become active during the wait.
  const presence = state.channel.presenceState();
  const stillIdle = Object.keys(presence).filter((k) => k !== "listener").length === 0;
  if (!stillIdle) return;

  // PRD §15: fan out to ALL the account's registered phones (device_id "*" — the Edge Function sends to
  // every `device_push_token` row of the user), with the waiting-break length for the message.
  await invokeEdge(userId, "*", "schedule", new Date(dueAtMs).toISOString(), breakLenMs);
  state.pushedDueAt = dueAtMs;
  console.log(`account ${userId}: pushed pause-cue schedule to all phones for ${new Date(dueAtMs).toISOString()}`);
}

function clearScheduled(userId, pushCancel) {
  const state = accounts.get(userId);
  if (!state) return;
  if (state.timer) {
    clearTimeout(state.timer);
    state.timer = null;
  }
  state.scheduledDueAt = null;
  if (pushCancel && state.pushedDueAt != null && state.pushedDueAt > Date.now()) {
    // Cancel on every phone the schedule was fanned out to.
    invokeEdge(userId, "*", "cancel", null, null)
      .catch((e) => console.error(`cancel ${userId} failed:`, e));
  }
  state.pushedDueAt = null;
}

async function invokeEdge(userId, deviceId, action, dueAtIso, breakLenMs) {
  const body = { user_id: userId, device_id: deviceId, action };
  if (dueAtIso) body.due_at = dueAtIso;
  if (breakLenMs != null) body.break_len_ms = breakLenMs;
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
