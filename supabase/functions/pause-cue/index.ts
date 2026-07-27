// PRD §15 / ARCHITECTURE.md §8 — **e1**: the Edge Function that DECIDES whether a data payload must be sent.
//
// ONE caller: the app itself, at the instant its screen goes off. The device listens to the OS lock event, so
// this request IS the walk-away — which is what lets the cue be timed from `now()` rather than estimated.
// It is authenticated with the user's own JWT; the account is the `sub` claim and nothing else (a body-supplied
// `user_id` would let any signed-in device fire cues at another account).
//
// What it does, in one round trip to Postgres (`evaluate_pause_cue`, migration 20260729000000):
//   1. is the account really idle?  — every OTHER device's presence row older than `2*t_a` (the caller's own row
//      is necessarily still fresh, so it is excluded);
//   2. is the account deliberately away? — the Sleep/Work toggle suppresses the cue;
//   3. claim the episode — `data_payload_sent = true`, conditionally, so this and a racing cron tick together
//      push exactly one cue;
//   4. is a break OVERDUE? — a cue is owed to a user who walked away WITH a break due, not to one who merely
//      went idle; and if so, `break_length` from `break_config`, and the cue instant `now() + break_length`.
// Then it fans a high-priority DATA push out to every phone of the account.
//
// The DIRTY-KILL path is a different function, `pause-cue-cron` ("e2"): when the phone dies without reporting,
// no request is ever sent from here, and the `t_b` cron detects the stale presence rows instead. Splitting them
// keeps the decision in exactly one place per path and makes the two separately deployable and separately
// readable in the function logs.
//
// DEPLOY: `supabase functions deploy pause-cue` (scripts/deploy-supabase.bat does both functions). The FCM/APNs
// secrets it needs are listed in ../_shared/push.ts.

import { adminClient, bearerClaims, CueDecision, fanOut, scheduleData } from "../_shared/push.ts";

interface CuePayload {
  /** This device's id — the one whose screen just went off, excluded from the account-liveness check. */
  device_id?: string;
  /** Present for symmetry with older clients; this function only ever evaluates. */
  action?: string;
}

const admin = adminClient();

Deno.serve(async (req) => {
  const body = (await req.json().catch(() => ({}))) as CuePayload;

  // Only a signed-in app may report its own lock, and only for its own account. `verify_jwt = false` in
  // config.toml (the platform gateway must let the cron's service-role calls through on the sibling function),
  // so this check is the real gate.
  const claims = bearerClaims(req);
  if (claims?.role !== "authenticated" || typeof claims.sub !== "string") {
    return new Response("authenticated session required", { status: 401 });
  }
  const userId = claims.sub;

  const { data, error } = await admin.rpc("evaluate_pause_cue", {
    p_user_id: userId,
    p_exclude_device: body.device_id && body.device_id !== "*" ? body.device_id : null,
  });
  if (error) return new Response(error.message, { status: 500 });
  const decision = (data as CueDecision[] | null)?.[0] ?? null;
  if (!decision) return new Response("no cue owed", { status: 200 });

  return await fanOut(admin, userId, scheduleData(decision));
});
