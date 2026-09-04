// PRD §15 / ARCHITECTURE.md §8 — **e2**: the Edge Function the `t_b` pg_cron tick calls, i.e. the backstop for
// a phone that died without reporting its screen-off ("if the phone had exploded").
//
// ONE caller: `tick_pause_cues()` over pg_net, with the service-role key. The cron has ALREADY made the whole
// decision in its `having` clause — every presence row of the account as old as or older than `2*t_a`, the
// episode unclaimed, the account not sleeping, a break overdue at the last beat — so this function does not
// re-decide. It calls `claim_pause_cue()` (migration 20260729000000), which:
//   * claims the episode (`data_payload_sent = true`, conditionally — the exactly-once guarantee against a
//     clean-lock report from a device that made it out after all, not a second decision), and
//   * directly computes `break_length` and the cue instant `t2 + break_length`, where
//     `t2 = max(beat_at) + t_a/2` is the expected midpoint of the tick interval the last device vanished in.
//     `t_b` is detection latency ONLY: a tick that runs late must not push the cue later.
// Then it fans the data payload out to every phone of the account.
//
// `action: 'cancel'` is the cron's second pass: an account that came back to life while a pushed cue was still
// in the future — the user is already at another device, so the phone lying face-down must not speak.
//
// `action: 'mode3'` is the cron's THIRD pass (migration 20260904000000) — the $now line$'s third mode. The
// account has said it is away ("I'm away" with no unlocked device), so a 5- or 15-minute pose is not dragged but
// TAKEN, and moving the line over the rules the app last published puts it inside one. The cron has already
// decided; `claim_mode3_break_cue()` claims that BREAK (not the idle episode — one away spell can contain both
// poses) and anchors the cue at the break's own END, which mode 3 knows exactly rather than estimating.
//
// The CLEAN-LOCK path is a different function, `pause-cue` ("e1"): there the app reports the lock itself and the
// cue is anchored at the observed walk-away instant instead of an estimate.
//
// DEPLOY: `supabase functions deploy pause-cue-cron` (scripts/deploy-supabase.bat does both functions). The
// FCM/APNs secrets it needs are project-wide and listed in ../_shared/push.ts.

import { adminClient, bearerClaims, cancelData, CueDecision, fanOut, scheduleData } from "../_shared/push.ts";

interface CronPayload {
  user_id?: string;
  action?: "push" | "cancel" | "mode3";
}

const admin = adminClient();
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

Deno.serve(async (req) => {
  // Server-only. The account comes from the BODY here (Postgres, not a user session, is speaking), so the
  // service-role bearer is what makes that safe — an anon or user JWT must never reach this.
  const raw = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  if (raw !== serviceRoleKey && bearerClaims(req)?.role !== "service_role") {
    return new Response("service role required", { status: 401 });
  }

  const body = (await req.json().catch(() => ({}))) as CronPayload;
  const userId = body.user_id;
  if (!userId) return new Response("no account", { status: 400 });

  if (body.action === "cancel") {
    return await fanOut(admin, userId, cancelData());
  }

  // Same claim/compute/push shape as the dirty-kill path; only the RPC — and so the anchor — differs.
  const rpc = body.action === "mode3" ? "claim_mode3_break_cue" : "claim_pause_cue";
  const { data, error } = await admin.rpc(rpc, { p_user_id: userId });
  if (error) return new Response(error.message, { status: 500 });
  const decision = (data as CueDecision[] | null)?.[0] ?? null;
  // Nothing to claim: a clean-lock report beat this tick to the episode and already pushed — or, on the mode-3
  // path, this very break window has already been cued.
  if (!decision) return new Response("no cue owed", { status: 200 });

  return await fanOut(admin, userId, scheduleData(decision));
});
