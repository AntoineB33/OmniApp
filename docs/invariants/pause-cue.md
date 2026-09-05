# Pause cue

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Pause cue

→ ADR 0006. Runbook: `docs/PAUSE_CUE_DELIVERY.md`. **The live path still needs on-device confirmation.**

- Presence is a `t_a` `publish_presence` RPC while signed in **and unlocked**; the row is
  `{account, device, server-stamped time}` and **nothing else**. `t_a` is server-owned and returned by the RPC.
- **Nothing that changes with the schedule rides the presence tick.**
- **Two Edge Functions, both must be deployed.** `pause-cue` (e1) handles the clean lock and **decides**,
  anchoring at `now()`. `pause-cue-cron` (e2) handles the dirty kill; the cron already decided, so e2 only
  claims/computes/pushes, anchoring at `t2 = max(beat_at) + t_a/2`. Do not re-anchor the cron path to
  detection time.
- Both share `_shared/push.ts` and `build_pause_cue()`, so only the anchor may differ.
- **Only an OVERDUE break fires a cue** — judged against the account's own newest `beat_at`, never the cron's
  `now()`. Between the two dues the **longest overdue** governs.
- **Idleness is judged account-wide** (`max(beat_at)`), never per row.
- `device_break` is **account-keyed, holds only the two due instants, and is written only on change**
  (retried with backoff). The break LENGTH is the server's (`break_config.length_ms`).
- **`break_due_ms` is the pose's next DUE** (`nextScreenBreakStartMillis`) — a fixed instant the recurrence
  bars derive, so it moves only when the rules or the environment do and can still be written event-driven. It
  is the same reading the local cue keys on, so the server and the client key on one instant; publishing where
  the *period* sits would be publishing an instant that moves with the now-line whenever a dragging mode
  (1 or 2) is dragging one. An already-due pose publishes the constant `ALREADY_DUE_MILLIS`. It is also what
  the server reads as the break's START in **mode 3**, where nothing drags and `[due, due + length)` therefore
  IS where the break happens — the whole reason the server can answer "is the line inside a break" from two
  instants and a length instead of a second copy of the placement.
- A device belongs to exactly **one** account — every per-device table needs a server-side eviction trigger
  **paired with** a client re-assertion when the row is written event-driven.
- The Sleep/Work toggle writes `account_state` immediately, which suppresses the cue.
- **Two more things the client publishes, both event-driven** (migration 20260904000000): `sync_device_away`
  (this device's away flag out, the account's answer back — an edge and a sync moment) and
  `publish_break_rules` (the scheduler's SET OF RULES for the two poses, replaced whole, sent in the same call
  as the two dues it is projected into so the server can never hold half of each). `account_in_mode3` is the
  server's own reading of the mode — an away device and no beat within `2·t_a` — so nothing trusts a
  client-computed mode. `tick_pause_cues` pass (c) is the cue they feed: an account in mode 3 whose line is
  inside a published rule window is handed to **e2** with `action: 'mode3'`, and `claim_mode3_break_cue`
  anchors at the break's own END rather than at an estimate, because the rule says exactly where it ends.
  **Pass (c) is not folded into pass (a)**: (a) fires once per idle EPISODE for a break already overdue at the
  walk-away and claims `data_payload_sent`; (c) fires per break WINDOW the line is inside and claims
  `pause_cue_schedule.break_start_ms` — a different question, a different anchor, a different key. The shared
  `break_start_ms` is also what stops the two paths cueing one pose twice. `away_span` logs each mode-3 episode
  (pruned to a week, read back by a waking app through `away_spans`), opened and closed by the away flag and
  the presence beats themselves — never by a device's opinion of the mode, or two devices would write two
  episodes for one away spell.

### Traffic budget

Steady state is the `t_a` RPC while unlocked — a DB write, not an Edge invocation, and nothing while locked.
Everything else is event-driven REST (reconcile, `account_state`, push-token registration, the
`publish_next_break` + `publish_break_rules` change write, the **`sync_device_away` edge/sync-moment call** and
the **mode-3 span read a wake makes once**, the screen-off call, the logout check). **Never add a timer-driven
request.**

Free-plan metering is **egress** + Edge invocations, not request count — request count is the wrong axis to
optimize.

---

