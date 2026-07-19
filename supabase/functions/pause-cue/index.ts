// PRD §15 / ARCHITECTURE.md §8 — the "function edge trigger" that delivers the pause-end voice cue.
//
// This is the ONLY server→device channel (there is never a WebSocket). It is invoked from Postgres via pg_net
// (see supabase/migrations/) with two actions:
//   • action:'schedule' — tells the last phone to (cancel any stale local cue and) schedule its "pause is over"
//     cue at `due_at`. Fired by BOTH (a) the `on_pause_cue_schedule_change` trigger, IMMEDIATELY when a device
//     OTHER than the last phone moves the next pause-end (scenario #2 — e.g. the desktop postpones it), so a
//     backgrounded phone holding an alarm at the OLD instant is corrected before it speaks early; and (b)
//     `tick_pause_cues()` ~1 min before the pose ends, as a backstop. Both SKIP the push when the phone itself
//     set the schedule (origin == last phone) — it already scheduled the cue locally.
//   • action:'cancel'  — telling a phone to cancel its scheduled cue. Fired by the `account_last_phone` change
//     trigger for the PREVIOUS phone (scenario #3, so only one phone ever speaks), and by
//     `on_pause_cue_schedule_change` on DELETE when the schedule is cleared (no upcoming pose).
//
// The phone receives a DATA / background push (not a display notification) and does the scheduling/cancelling
// itself: Android schedules an AlarmManager exact alarm, iOS a UNTimeIntervalNotificationTrigger.
//
// DEPLOY: `supabase functions deploy pause-cue`. Secrets it reads (set with `supabase secrets set`):
//   FCM_SERVICE_ACCOUNT   – the Firebase service-account JSON (one line), used for FCM HTTP v1 + OAuth2.
//   APNS_KEY              – the APNs auth key (.p8 PEM contents, newlines preserved or as \n).
//   APNS_KEY_ID          – the 10-char APNs Key ID.
//   APNS_TEAM_ID         – the 10-char Apple Team ID.
//   APNS_BUNDLE_ID       – the iOS app bundle id (becomes the apns-topic).
//   APNS_HOST            – api.push.apple.com (prod) or api.sandbox.push.apple.com (dev). Default: sandbox.
// Until a phone registers a row in `device_push_token`, every call is a no-op — the native token
// registration is the remaining client-side follow-up (see docs/PAUSE_CUE_DELIVERY.md).

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

interface CuePayload {
  user_id: string;
  // A concrete device id, or "*" to fan out to EVERY registered device of the account (PRD §15 — the
  // listener pushes the pause cue to all the account's phones).
  device_id: string;
  action: "schedule" | "cancel";
  due_at?: string; // ISO-8601, present for 'schedule'
  break_len_ms?: number; // PRD §15: the waiting break's length, for the phone's message
}

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

Deno.serve(async (req) => {
  const body = (await req.json()) as CuePayload;

  // Look up the target push token(s): one device, or every registered device of the account ("*").
  let query = admin
    .from("device_push_token")
    .select("platform, token")
    .eq("user_id", body.user_id);
  if (body.device_id !== "*") query = query.eq("device_id", body.device_id).limit(1);
  const { data: tokens, error } = await query;
  if (error) return new Response(error.message, { status: 500 });
  if (!tokens?.length) return new Response("no push token for device", { status: 200 }); // inert until registered

  // The data payload the phone acts on: schedule a local cue at `due_at`, or cancel the pending one.
  // `break_len_ms` (when present) is the waiting break's length so the phone can name it in its message.
  const data: Record<string, string> = {
    type: "pause_cue",
    action: body.action,
    due_at: body.due_at ?? "",
  };
  if (body.break_len_ms != null) data.break_len_ms = String(body.break_len_ms);

  // Fan out; report per-token failures without wedging the caller (a push failure must not wedge the
  // pg_net worker / the listener).
  const failures: string[] = [];
  for (const target of tokens) {
    try {
      if (target.platform === "fcm") {
        await sendFcm(target.token, data);
      } else if (target.platform === "apns") {
        await sendApns(target.token, data);
      }
    } catch (e) {
      failures.push(e instanceof Error ? e.message : String(e));
    }
  }
  if (failures.length === tokens.length) {
    return new Response(`push failed: ${failures.join("; ")}`, { status: 502 });
  }
  return new Response(failures.length ? `partial: ${failures.join("; ")}` : "ok", { status: 200 });
});

// --- FCM HTTP v1 --------------------------------------------------------------------------------------------

interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id: string;
}

// Cache the minted OAuth2 access token in module memory until shortly before it expires (the edge runtime
// keeps warm instances alive across invocations, so most calls reuse it).
let fcmToken: { value: string; expiresAt: number } | null = null;

async function sendFcm(deviceToken: string, data: Record<string, string>): Promise<void> {
  const sa = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT")!) as ServiceAccount;
  const accessToken = await fcmAccessToken(sa);
  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
    {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      // Data-only + high priority so a backgrounded/killed app is still woken to (un)schedule the alarm.
      body: JSON.stringify({
        message: { token: deviceToken, data, android: { priority: "high" } },
      }),
    },
  );
  if (!res.ok) throw new Error(`FCM ${res.status}: ${await res.text()}`);
}

async function fcmAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (fcmToken && fcmToken.expiresAt > now + 60) return fcmToken.value;

  // Service-account JWT (RS256), exchanged for an OAuth2 access token scoped to FCM.
  const claims = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const jwt = await signJwt({ alg: "RS256", typ: "JWT" }, claims, sa.private_key, "RSASSA-PKCS1-v1_5", "SHA-256");
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) throw new Error(`OAuth2 ${res.status}: ${await res.text()}`);
  const json = await res.json() as { access_token: string; expires_in: number };
  fcmToken = { value: json.access_token, expiresAt: now + json.expires_in };
  return json.access_token;
}

// --- APNs HTTP/2 --------------------------------------------------------------------------------------------

let apnsJwt: { value: string; issuedAt: number } | null = null;

async function sendApns(deviceToken: string, data: Record<string, string>): Promise<void> {
  const host = Deno.env.get("APNS_HOST") ?? "api.sandbox.push.apple.com";
  const topic = Deno.env.get("APNS_BUNDLE_ID")!;
  const jwt = await apnsAuthToken();
  // Background push (content-available:1) so the app is woken to schedule/cancel the local cue without
  // showing a banner — the phone itself decides whether to speak at the exact instant.
  const res = await fetch(`https://${host}/3/device/${deviceToken}`, {
    method: "POST",
    headers: {
      "authorization": `bearer ${jwt}`,
      "apns-topic": topic,
      "apns-push-type": "background",
      "apns-priority": "5",
    },
    body: JSON.stringify({ aps: { "content-available": 1 }, ...data }),
  });
  if (!res.ok) throw new Error(`APNs ${res.status}: ${await res.text()}`);
}

async function apnsAuthToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  // Apple rejects tokens older than 1h and throttles re-minting under ~20 min; reuse within that window.
  if (apnsJwt && now - apnsJwt.issuedAt < 30 * 60) return apnsJwt.value;
  const keyId = Deno.env.get("APNS_KEY_ID")!;
  const teamId = Deno.env.get("APNS_TEAM_ID")!;
  const key = Deno.env.get("APNS_KEY")!.replace(/\\n/g, "\n");
  const jwt = await signJwt(
    { alg: "ES256", kid: keyId, typ: "JWT" },
    { iss: teamId, iat: now },
    key,
    "ECDSA",
    "SHA-256",
  );
  apnsJwt = { value: jwt, issuedAt: now };
  return jwt;
}

// --- JWT signing (Web Crypto) -------------------------------------------------------------------------------

async function signJwt(
  header: Record<string, unknown>,
  claims: Record<string, unknown>,
  pem: string,
  algName: "RSASSA-PKCS1-v1_5" | "ECDSA",
  hash: "SHA-256",
): Promise<string> {
  const enc = (obj: unknown) => base64url(new TextEncoder().encode(JSON.stringify(obj)));
  const signingInput = `${enc(header)}.${enc(claims)}`;
  const keyData = pemToArrayBuffer(pem);
  const algorithm = algName === "ECDSA"
    ? { name: "ECDSA", namedCurve: "P-256" }
    : { name: "RSASSA-PKCS1-v1_5", hash };
  const key = await crypto.subtle.importKey("pkcs8", keyData, algorithm, false, ["sign"]);
  const signAlg = algName === "ECDSA" ? { name: "ECDSA", hash } : { name: "RSASSA-PKCS1-v1_5" };
  const sig = await crypto.subtle.sign(signAlg, key, new TextEncoder().encode(signingInput));
  return `${signingInput}.${base64url(new Uint8Array(sig))}`;
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const b64 = pem.replace(/-----BEGIN [^-]+-----/, "").replace(/-----END [^-]+-----/, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

function base64url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
