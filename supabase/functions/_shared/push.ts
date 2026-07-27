// PRD §15 / ARCHITECTURE.md §8 — the delivery half of the pause-end voice cue, shared by the TWO Edge
// Functions that own the two ways a pause is detected:
//
//   • `pause-cue`      ("e1") — the app's own clean-lock report. It DECIDES whether a data payload must be sent.
//   • `pause-cue-cron` ("e2") — the `t_b` pg_cron tick's backstop for a phone that died without reporting. The
//                               cron already decided; e2 computes and sends.
//
// Everything below is what the two have in common — the account's push tokens and the FCM/APNs transports —
// factored out so a change to how a cue is delivered can never apply to only one of the paths. What must NOT
// live here is anything about WHEN a cue is owed: that is precisely what distinguishes the two functions.
//
// The phone receives a DATA / background push (not a display notification) and does the scheduling/cancelling
// itself: Android schedules an AlarmManager exact alarm, iOS a UNTimeIntervalNotificationTrigger. Neither the
// push nor the spoken cue wakes the app UI.
//
// Secrets (set once with `supabase secrets set`; they are project-wide, so both functions read the same ones):
//   FCM_SERVICE_ACCOUNT   – the Firebase service-account JSON (one line), used for FCM HTTP v1 + OAuth2.
//   APNS_KEY              – the APNs auth key (.p8 PEM contents, newlines preserved or as \n).
//   APNS_KEY_ID           – the 10-char APNs Key ID.
//   APNS_TEAM_ID          – the 10-char Apple Team ID.
//   APNS_BUNDLE_ID        – the iOS app bundle id (becomes the apns-topic).
//   APNS_HOST             – api.push.apple.com (prod) or api.sandbox.push.apple.com (dev). Default: sandbox.
// Until a phone registers a row in `device_push_token`, every send is a no-op — the native token registration
// is the remaining client-side follow-up (see docs/PAUSE_CUE_DELIVERY.md).

import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

/** The service-role client both functions talk to Postgres with. */
export function adminClient(): SupabaseClient {
  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );
}

/**
 * One row of `evaluate_pause_cue()` / `claim_pause_cue()` — the cue this account is owed, already claimed. The
 * two functions return the same shape on purpose; only the instant differs (see the migration).
 */
export interface CueDecision {
  due_at: string;
  break_kind: string;
  break_len_ms: number;
  voice_cue: string;
  voice_message: string;
}

/** The data payload a decided cue becomes. */
export function scheduleData(decision: CueDecision): Record<string, string> {
  return {
    type: "pause_cue",
    action: "schedule",
    due_at: decision.due_at,
    break_kind: decision.break_kind,
    break_len_ms: String(decision.break_len_ms),
    voice_cue: decision.voice_cue,
    message: decision.voice_message,
  };
}

/** The data payload that retracts a cue the account came back before. */
export function cancelData(): Record<string, string> {
  return { type: "pause_cue", action: "cancel", due_at: "" };
}

/**
 * Fans [data] out to EVERY registered device of [userId] (PRD §15 — the cue goes to all the account's phones;
 * the device that went dark is not the target, the others are). Per-token failures are reported but never wedge
 * the caller: a dead token must not fail the pg_net worker or the app's lock report.
 */
export async function fanOut(
  admin: SupabaseClient,
  userId: string,
  data: Record<string, string>,
): Promise<Response> {
  const { data: tokens, error } = await admin
    .from("device_push_token")
    .select("platform, token")
    .eq("user_id", userId);
  if (error) return new Response(error.message, { status: 500 });
  if (!tokens?.length) return new Response("no push token for account", { status: 200 }); // inert until registered

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
}

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

// --- Caller identity ----------------------------------------------------------------------------------------

/** The `role`/`sub` claims of a bearer token, or null when it is not a decodable JWT. */
export function bearerClaims(req: Request): { role?: string; sub?: string } | null {
  const raw = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  if (!raw) return null;
  try {
    return JSON.parse(atob(raw.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
  } catch {
    return null;
  }
}
