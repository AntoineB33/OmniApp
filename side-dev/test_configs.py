#!/usr/bin/env python3
"""
test_configs.py
Defines the scheduler test configurations, bounds, and rule testing mechanisms.
"""

import itertools
from math import inf

from scheduler_logic import MAX_RULES, MovingWindowPlan, Task, flatten, human_s


def AB():
    return [Task("A", priority=50, min_time=10, color="#FF9999"),
            Task("B", priority=50, min_time=10, color="#99CCFF")]

def build_cases():
    return [
        (
            ("Test 1: Normal 50/50 Split (10min each)\n-> Pure periodic cycle, no prefix."),
            AB(), 180, [], []
        ),
        (
            ("Test 2: Pre-placed event owned by nobody\n-> MAINTENANCE excludes everybody equally, so it creates no field: they simply resume alternating."),
            AB(), 240, [{'name': 'MAINTENANCE', 'start': 40, 'duration': 60, 'color': '#CCCCCC'}], []
        ),
        (
            ("Test 3: Periods constraint\n-> C is banned from t=105 on, forever: it is abundantly present just before the door closes, then A and B share the timeline."),
            [Task("A", priority=40, min_time=10, color="#FF9999"), Task("B", priority=40, min_time=10, color="#99CCFF"), Task("C", priority=20, min_time=10, color="#99FF99")],
            300, [], [{'start': 0, 'end': 105, 'allowed': ['A', 'B', 'C']}, {'start': 105, 'end': inf, 'allowed': ['A', 'B']}]
        ),
        (
            ("Test 4: Three tasks (A: 50% 20m, B: 30% 10m, C: 20% 15m)\n-> Minimums force a 75min period; shares are exact."),
            [Task("A", priority=50, min_time=20, color="#FF9999"), Task("B", priority=30, min_time=10, color="#99CCFF"), Task("C", priority=20, min_time=15, color="#99FF99")],
            400, [], []
        ),
        (
            ("Test 5: Lopsided priorities (A 90% / B 10%) + a B block at the start\n-> A gets a denser, bounded catch-up around it, not the full 396min it is owed."),
            [Task("A", priority=90, min_time=10, color="#FF9999"), Task("B", priority=10, min_time=10, color="#99CCFF")],
            600, [{'name': 'B', 'start': 0, 'duration': 40, 'color': "#99CCFF"}], []
        ),
        (
            ("Test 6: 1h block of A at t=100 (tau = 20min)\n-> B's slots swell as the block approaches and shrink back after it: exponential decay of the influence, both sides."),
            AB(), 400, [{'name': 'A', 'start': 100, 'duration': 60, 'color': "#FF9999"}], []
        ),
        (
            ("Test 7: 10h block of A at t=100 - 10x longer than test 6\n-> B's presence around it is wider and denser, but only a few times bigger: log, not proportional."),
            AB(), 1000, [{'name': 'A', 'start': 100, 'duration': 600, 'color': "#FF9999"}], [], {}, 2
        ),
        (
            ("Test 8: B banned from t=100 to t=400 - a window, not a block\n-> same field, same ramps: B swells before the ban and right after it re-opens, then decays back to the cycle."),
            AB(), 700, [], [{'start': 0, 'end': 100, 'allowed': ['A', 'B']}, {'start': 100, 'end': 400, 'allowed': ['A']}, {'start': 400, 'end': inf, 'allowed': ['A', 'B']}], {}, 2
        ),
        (
            ("Test 9: same 300min ban, but split into ten consecutive windows\n-> merged into one exclusion: ten short bans in a row are one long ban, not ten small ones."),
            AB(), 700, [], [{'start': 0, 'end': 100, 'allowed': ['A', 'B']}] + [{'start': 100 + 30 * i, 'end': 130 + 30 * i, 'allowed': ['A']} for i in range(10)] + [{'start': 400, 'end': inf, 'allowed': ['A', 'B']}], {}, 2
        )
    ]

# The one knob for test 10: how long the A-only period lasts, in seconds.
# Everything else about the test follows from it (the title, the regimes, the
# marker the GUI sweeps). The closed form assumes the period is shorter than any
# task's minimum time, so with AB()'s 10min minimums anything under 600s works.
TEST10_WINDOW = 20.0
TEST10_TOTAL_MIN = 80
_T10 = MovingWindowPlan(AB(), TEST10_WINDOW)

TEST10_TITLE = (
    f"Test 10 (Algebraic O(1)): the {human_s(TEST10_WINDOW)} A-only period starts at t_p and sweeps right\n"
    "-> rules are a closed form in t_p; every plan agrees with every earlier plan on t < t_p."
)

def regime_name(tp):
    return _T10.regime_at_tp(tp).name

def get_test_10():
    return (TEST10_TITLE, _T10, TEST10_TOTAL_MIN, TEST10_WINDOW)

def timeline_of(prefix, cycle, horizon):
    out, t = [], 0.0
    for blk in flatten(prefix):
        d = float(blk['duration'])
        if d <= 0: continue
        out.append((t, t + d, blk['name']))
        t += d
        if t >= horizon: return out
    cycle = flatten(cycle)
    if not cycle: return out
    while t < horizon:
        for blk in cycle:
            d = float(blk['duration'])
            if d <= 0: continue
            out.append((t, t + d, blk['name']))
            t += d
            if t >= horizon: break
    return out

def occupant(tl, t):
    for s, e, n in tl:
        if s <= t < e: return n
    return None

def first_disagreement(t1, t2, cut, tol=1e-6):
    pts = sorted({p for s, e, _ in t1 + t2 for p in (s, e) if -tol <= p <= cut} | {0.0, cut})
    for lo, hi in itertools.pairwise(pts):
        if hi - lo <= tol: continue
        mid = (lo + hi) / 2.0
        if occupant(t1, mid) != occupant(t2, mid): return mid
    return None

def whole_cycles_cut(prefix, cycle, horizon):
    """Largest instant <= horizon that ends a whole number of steady cycles.

    The share check has to land on a cycle boundary: cutting mid-cycle credits
    whichever task straddles the cut with up to a full slot, which reads as a
    share error when it is only the truncation. That remainder is the prefix's
    own overrun, so it grows with the window and a fixed tolerance would
    silently bound how long the A-only period may be.
    """
    lead = sum(float(b['duration']) for b in flatten(prefix))
    period = sum(float(b['duration']) for b in flatten(cycle))
    if period <= 0 or lead >= horizon: return horizon
    return lead + period * int((horizon - lead) // period)

def service_runs(tl):
    runs = []
    for s, e, n in tl:
        if runs and runs[-1][0] == n:
            runs[-1][1] += e - s
        elif (n != "IDLE" and len(runs) >= 2
              and runs[-1][0] == "IDLE" and runs[-2][0] == n):
            runs.pop()
            runs[-1][1] += e - s
        else:
            runs.append([n, e - s, e])
            continue
        runs[-1][2] = e
    return runs

def verify_test10(plan=None, horizon_sec=None, verbose=True, max_report=10):
    plan = plan or _T10
    horizon_sec = horizon_sec or TEST10_TOTAL_MIN * 60
    T, W = plan.period, plan.window
    failures = []

    def fail(msg):
        if len(failures) < 500: failures.append(msg)

    grid = {round(x * 0.5, 6) for x in range(int(horizon_sec * 2) + 1)}
    for k in range(int(horizon_sec // T) + 2):
        for off in (plan.stretch_from, plan.t_1, T):
            for d in (-1e-3, -1e-6, 0.0, 1e-6, 1e-3, 0.25, 1.0):
                v = k * T + off + d
                if 0 <= v <= horizon_sec: grid.add(round(v, 6))
    grid = sorted(grid)

    prev_tp, prev_tl = None, None
    for tp in grid:
        tl = timeline_of(*plan(tp, to_minutes=False), horizon=horizon_sec + 2 * T)
        if prev_tl is not None:
            bad = first_disagreement(prev_tl, tl, prev_tp)
            if bad is not None:
                fail(f"frozen past broken at t={bad:.6f}s between t_p={prev_tp}s and "
                     f"t_p={tp}s ({occupant(prev_tl, bad)} -> {occupant(tl, bad)})")
        prev_tp, prev_tl = tp, tl

    for tp in grid:
        tl = timeline_of(*plan(tp, to_minutes=False), horizon=horizon_sec + 2 * T)
        for name, served, end in service_runs(tl):
            if name == "IDLE" or end > horizon_sec: continue
            if served < plan.minimum[name] - 1e-6:
                fail(f"t_p={tp}s: {name} served {served:.3f}s < minimum "
                     f"{plan.minimum[name]:.0f}s")

    for tp in grid[::13]:
        tl = timeline_of(*plan(tp, to_minutes=False), horizon=horizon_sec)
        for (s1, e1, _), (s2, _e2, _) in itertools.pairwise(tl):
            if abs(e1 - s2) > 1e-9 or e1 < s1:
                fail(f"t_p={tp}s: timeline is not contiguous at {e1:.6f}s")
                break

    H = 20 * T
    for tp in grid[::7]:
        prefix, cycle = plan(tp, to_minutes=False)
        cut = whole_cycles_cut(prefix, cycle, H)
        tl = timeline_of(prefix, cycle, horizon=cut)
        served = {}
        for s, e, n in tl:
            served[n] = served.get(n, 0.0) + max(0.0, min(e, cut) - min(s, cut))
        idle = served.pop("IDLE", 0.0)
        total = sum(served.values())
        for n, d in served.items():
            if abs(d / total - 0.5) > 2e-3:
                fail(f"t_p={tp}s: {n} share {d / total:.5f} off 50%")
                break
        if idle > W + 1e-6:
            fail(f"t_p={tp}s: idle {idle:.3f}s exceeds one window")

    for tp in grid:
        prefix, _cycle = plan(tp, to_minutes=False)
        served = {}
        for blk in flatten(prefix):
            served[blk['name']] = served.get(blk['name'], 0.0) + float(blk['duration'])
        gap = served.get(plan.a, 0.0) - served.get(plan.b, 0.0)
        if abs(gap) > 1e-6:
            fail(f"t_p={tp}s: prefix leaves the debt open, A-B = {gap:.6f}s")

    worst_sym = plan.rule_count()
    worst = 0
    for tp in grid:
        prefix, cycle = plan(tp)
        worst = max(worst, len(prefix) + len(cycle))
    if worst > MAX_RULES or worst_sym > MAX_RULES:
        fail(f"rule list reaches {max(worst, worst_sym)} entries, over the {MAX_RULES} cap")

    if verbose:
        print("--- Test 10 invariants ---")
        print(f"  grid: {len(grid)} values of t_p over [0, {horizon_sec}]s")
        print(f"  t_1 = {plan.t_1:.0f}s, stretch begins at {plan.stretch_from:.0f}s, "
              f"{len(plan.weights)} decay terms "
              f"({', '.join(f'{w:.4f}' for w in plan.weights)})")
        print(f"  symbolic rules: {worst_sym} entries; largest instantiation: "
              f"{worst} (cap {MAX_RULES})")
        if failures:
            print(f"  FAIL ({len(failures)}):")
            for f in failures[:max_report]: print(f"    - {f}")
        else:
            print("  PASS: frozen past, minimum times, contiguity, exact shares, "
                  "exact settlement, bounded idle, O(1) rules")
        print()
    return failures