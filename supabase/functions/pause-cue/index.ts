// PRD §15 / ARCHITECTURE.md §8 — the "function edge trigger" that delivers the pause-end voice cue.
//
// This is the ONLY server→device channel (there is never a WebSocket). It is invoked two ways, both from
// Postgres via pg_net (see supabase/migrations/):
//   • action:'schedule' — from `tick_pause_cues()`, ~1 min before a pose ends, when the next cue was set by a
//     device OTHER than the last phone. Tells the last phone to schedule its local "pause is over" cue at
//     `due_at`. (When the phone itself set the schedule, the cron job skips this — the phone already
//     scheduled it locally, so no push is sent.)
//   • action:'cancel'  — from the `account_last_phone` change trigger, telling the PREVIOUS phone to cancel
//     its scheduled cue so only one phone ever speaks.
//
// The phone receives a DATA message (not a display notification) and does the scheduling/cancelling itself.
//
// DEPLOY: `supabase functions deploy pause-cue`, and set the secrets it reads below
// (FCM service-account JSON, APNs auth key). Until a phone registers a row in `device_push_token`, every call
// is a no-op — the native token registration is the remaining client-side follow-up.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

interface CuePayload {
  user_id: string;
  device_id: string;
  action: "schedule" | "cancel";
  due_at?: string; // ISO-8601, present for 'schedule'
}

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

Deno.serve(async (req) => {
  const body = (await req.json()) as CuePayload;

  // Look up the target device's push token.
  const { data: tokens, error } = await admin
    .from("device_push_token")
    .select("platform, token")
    .eq("user_id", body.user_id)
    .eq("device_id", body.device_id)
    .limit(1);
  if (error) return new Response(error.message, { status: 500 });
  const target = tokens?.[0];
  if (!target) return new Response("no push token for device", { status: 200 }); // inert until registered

  // The data payload the phone acts on: schedule a local cue at `due_at`, or cancel the pending one.
  const data = {
    type: "pause_cue",
    action: body.action,
    due_at: body.due_at ?? "",
  };

  if (target.platform === "fcm") {
    await sendFcm(target.token, data);
  } else if (target.platform === "apns") {
    await sendApns(target.token, data);
  }
  return new Response("ok", { status: 200 });
});

// --- Push transports (fill in with your provider credentials) ---------------------------------------------

async function sendFcm(token: string, data: Record<string, string>): Promise<void> {
  // FCM HTTP v1: POST https://fcm.googleapis.com/v1/projects/<project>/messages:send
  // with an OAuth2 bearer minted from the FCM service-account key (Deno.env "FCM_SERVICE_ACCOUNT").
  // Send `{ message: { token, data, android: { priority: "high" } } }` so a backgrounded app still wakes.
  // Intentionally left as a stub — wire it when the Firebase project + service account exist.
  console.log("FCM push (stub)", token, data);
}

async function sendApns(token: string, data: Record<string, string>): Promise<void> {
  // APNs HTTP/2: POST https://api.push.apple.com/3/device/<token> with a JWT signed by the APNs auth key
  // (Deno.env "APNS_KEY" / "APNS_KEY_ID" / "APNS_TEAM_ID"). Use a background push
  // (`{"aps":{"content-available":1}, ...data}`, apns-push-type: background) so the app schedules the cue.
  // Intentionally left as a stub — wire it when the Apple push credentials exist.
  console.log("APNs push (stub)", token, data);
}
