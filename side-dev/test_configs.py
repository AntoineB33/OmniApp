#!/usr/bin/env python3
"""
test_configs.py
The scheduler test configurations, and the checks the moving-period cases owe.

Tests 1-9 are static: one call to the scheduler, one rule list.
Tests 10 and 11 have a period that *slides*, and the displayer moves it. The
rules for them come from the same scheduler as every other test -- `MovingWindow`
derives a rule list that is affine in the period's position t_p, so the display
substitutes into it instead of scheduling again.
"""

import itertools
import time
from fractions import Fraction
from math import inf

from scheduler_logic import (
    IDLE,
    MAX_RULES,
    MovingWindow,
    Placement,
    Scheduler,
    Task,
    as_blocks,
    frac,
    human,
    human_s,
    stamp,
)

WINDOW = Fraction(1, 3)          # the 20 s period both moving cases carry


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


def get_schedule_rules(tasks, pre_placed=None, periods=None, t_now=0, **kw):
    """One static case -> its rule list: (prefix blocks, cycle blocks, plan)."""
    timeline = [Placement(p['name'], frac(p['start']), frac(p['start']) + frac(p['duration']),
                          p.get('color', '#CCCCCC'))
                for p in (pre_placed or [])]
    plan = Scheduler(tasks, **kw).plan(timeline=timeline, periods=periods or [],
                                       t_now=t_now, max_rules=MAX_RULES)
    return as_blocks(plan.prefix), as_blocks(plan.cycle), plan

def case_parts(case):
    """A case tuple -> (title, tasks, total duration, pre-placed, periods, options)."""
    options = case[5] if len(case) > 5 else {}
    return case[0], case[1], case[2], case[3], case[4], options


# --------------------------------------------------------------------------- #
#  Test 10: one 20 s period, accepting only A, sliding right
# --------------------------------------------------------------------------- #

TEST10_SPAN = 60

def test10_moving(tp):
    return [{'start': tp, 'end': tp + WINDOW, 'allowed': ['A'],
             'label': f"{human_s(20)}: only A"}]

TEST10_TITLE = (
    f"Test 10 (dynamic rule list): a {human_s(20)} period accepting only A slides right from t_p\n"
    "-> while it fits inside a slot of A nothing moves; once it reaches B, A stretches over it,\n"
    "   and once it is inside B, B is suspended and resumes on the far side."
)


# --------------------------------------------------------------------------- #
#  Test 11: the same, in a crowded timeline -- and a second period the sliding
#  one drags along with it
# --------------------------------------------------------------------------- #

TEST11_SPAN = 60
STRETCH_HOME = frac(45)          # where the five-minute stretch waits
STRETCH_HEAD = frac(1)           # its first minute accepts nobody
STRETCH_LEN = frac(5)

def tasks11():
    return [Task("A", priority=40, min_time=4, color="#FF9999"),
            Task("B", priority=20, min_time=5, color="#99CCFF"),
            Task("C", priority=20, min_time=5, color="#99FF99"),
            Task("D", priority=20, min_time=5, color="#FFCC66")]

TEST11_PRE = [{'name': 'MAINTENANCE', 'start': 18, 'duration': 6, 'color': '#CCCCCC'},
              {'name': 'A', 'start': 40, 'duration': 4, 'color': '#FF9999'}]

TEST11_PERIODS = [{'start': 8, 'end': 12, 'allowed': ['A', 'B']},
                  {'start': 30, 'end': 34, 'allowed': []},
                  {'start': 52, 'end': 56, 'allowed': ['A', 'C']}]

def reached_stretch(tp):
    """Has the sliding window reached the waiting five-minute stretch?

    Contact is the instant the window's right edge touches STRETCH_HOME, so the
    window is still whole at that position and gone from the next one on."""
    return frac(tp) + WINDOW > STRETCH_HOME

def stretch_start(tp):
    """Where the five-minute stretch sits when the window starts at tp.

    It waits at STRETCH_HOME until the sliding period reaches it, and from then
    on it starts at t_p -- so at the instant of contact it teleports one window
    width (20 s) to the left, and is dragged from there on."""
    return frac(tp) if reached_stretch(tp) else STRETCH_HOME

def test11_moving(tp):
    """The sliding periods at position tp.

    The 20s window exists only until it reaches the stretch: from contact on it
    is gone forever, and what slides is the stretch it dragged off its home."""
    s = stretch_start(tp)
    window = [] if reached_stretch(tp) else [
        {'start': frac(tp), 'end': frac(tp) + WINDOW, 'allowed': [],
         'label': f"{human_s(20)}: nothing"}]
    return window + [
        {'start': s, 'end': s + STRETCH_HEAD, 'allowed': [],
         'label': "1min: nothing"},
        {'start': s + STRETCH_HEAD, 'end': s + STRETCH_LEN, 'allowed': ['A'],
         'label': "4min: only A"}]

TEST11_TITLE = (
    "Test 11: the same sliding 20s period accepting NOTHING, in a crowded timeline\n"
    "-> pre-placed blocks, three static periods, four tasks; plus a 1min-nothing + 4min-A-only\n"
    f"   stretch waiting at {stamp(STRETCH_HOME)}: once the window reaches it the stretch starts at t_p (a 20s\n"
    "   teleport left) and the 20s window is gone forever -- from then on the stretch is what slides."
)


def build_moving_cases():
    """The cases whose rule list is *dynamic*: instead of constant durations it
    carries durations affine in the sliding period's position, plus the range of
    positions each rule list is valid for."""
    return [
        (TEST10_TITLE,
         MovingWindow(AB(), span=TEST10_SPAN, moving=test10_moving),
         25),
        (TEST11_TITLE,
         MovingWindow(tasks11(), span=TEST11_SPAN, moving=test11_moving,
                      pre_placed=TEST11_PRE, periods=TEST11_PERIODS,
                      breaks=[STRETCH_HOME - WINDOW]),
         35),
    ]


# --------------------------------------------------------------------------- #
#  drawing the timeline a rule list describes
# --------------------------------------------------------------------------- #

def timeline_of(mw, tp, horizon=None):
    """What the display shows at position tp: the frozen past, then the rules.

    Nothing here schedules -- `blocks_at` is a binary search over the regimes
    and some arithmetic. This is the whole point of the dynamic rule list."""
    horizon = mw.span if horizon is None else frac(horizon)
    tp = frac(tp)
    out = [(p.start, p.end, p.task) for p in mw.history_at(tp) if p.end > p.start]
    prefix, cycle = mw.blocks_at(tp)
    t = tp
    for blk in prefix:
        if t >= horizon: return out
        out.append((t, t + blk['duration'], blk['name']))
        t += blk['duration']
    if not cycle: return out
    while t < horizon:
        for blk in cycle:
            if t >= horizon: break
            out.append((t, t + blk['duration'], blk['name']))
            t += blk['duration']
    return out

def occupant(tl, t):
    for s, e, n in tl:
        if s <= t < e: return n
    return None

def first_disagreement(t1, t2, cut, tol):
    pts = sorted({p for s, e, _ in t1 + t2 for p in (s, e) if 0 <= p <= cut} | {frac(0), cut})
    for lo, hi in itertools.pairwise(pts):
        if hi - lo <= tol: continue
        mid = (lo + hi) / 2
        if occupant(t1, mid) != occupant(t2, mid): return mid
    return None

def blocked_at(mw, tp, name, t):
    """Is `name` refused the instant t -- by a period, or by somebody's block?

    A run may legitimately be shorter than its minimum only when something it
    cannot pass ends it. Everything else that shortens one is a bug."""
    for p in mw.pre:
        if p.task != name and p.start <= t < p.end: return True
    for w in list(mw.moving(frac(tp))) + list(mw.periods):
        end = w.get('end')
        if frac(w['start']) <= t and (end is None or end == inf or t < frac(end)):
            return name not in w['allowed']
    return False

def service_runs(tl):
    """Consecutive service of one task, an idling interruption not ending it."""
    runs = []
    for s, e, n in tl:
        if runs and runs[-1][0] == n:
            runs[-1][1] += e - s
        elif (n != IDLE and len(runs) >= 2
              and runs[-1][0] == IDLE and runs[-2][0] == n):
            runs.pop()
            runs[-1][1] += e - s
        else:
            runs.append([n, e - s, e])
            continue
        runs[-1][2] = e
    return runs


# --------------------------------------------------------------------------- #
#  the checks
# --------------------------------------------------------------------------- #

def check_atomic_block(verbose=True):
    """The README's own example, verbatim.

    "if task B is scheduled at t=0 and a period p that only allows task A is at
    t=1, and task B has a minimum time of 2, then the whole period p is
    scheduled with nothing."

    It is the one rule a sliding period runs into constantly, so it is worth
    stating on its own rather than only inside test 10's regimes."""
    tasks = [Task("A", priority=50, min_time=2, color="#FF9999"),
             Task("B", priority=50, min_time=2, color="#99CCFF")]
    plan = Scheduler(tasks).plan(
        periods=[{'start': 1, 'end': 3, 'allowed': ['A']}], t_now=1,
        history=[Placement("B", frac(0), frac(1), "#99CCFF")])
    got = [(s.task, s.duration) for s in plan.prefix]
    # the whole period empty, and B resuming afterwards with at least the minute
    # of its minimum it still owes (it may get more: the ban is as long as B's
    # own minimum, so it also earns B some compensation around itself)
    ok = (got[:1] == [(IDLE, frac(2))]
          and len(got) > 1 and got[1][0] == "B" and got[1][1] >= frac(1))
    if verbose:
        print("--- the atomic block (the README's example) ---")
        print("  B runs [0,1) of its 2min minimum, then a period accepts only A:")
        print("  " + " | ".join(f"{n} {human(d)}" for n, d in got[:3]))
        print("  PASS: the period is scheduled with nothing, and B resumes after it"
              if ok else "  FAIL: expected the whole period empty, then B")
        print()
    return [] if ok else ["the atomic block example: the period was not left empty"]

def positions(mw, samples):
    """Every regime edge, both sides of it, plus an even sweep."""
    grid = {mw.span * Fraction(i, samples) for i in range(samples)}
    for r in mw.regimes:
        for d in (Fraction(0), Fraction(-1, 6000), Fraction(1, 6000)):
            v = r.lo + d
            if 0 <= v < mw.span: grid.add(v)
    return sorted(grid)

def verify_moving(cases=None, samples=240, verbose=True, max_report=6):
    cases = cases if cases is not None else build_moving_cases()
    failures = check_atomic_block(verbose)
    for title, mw, _sweep in cases:
        fail = lambda m, title=title: failures.append(f"{title.splitlines()[0]}: {m}")
        grid = positions(mw, samples)
        worst = Fraction(0)

        # 1. the rules ARE the scheduler. Fitted on a few positions, checked here
        #    on positions they were never fitted on: without this the rule list
        #    is a guess.
        for tp in grid:
            pre, cyc = mw.rules_of(tp)
            want_pre, want_cyc = mw.blocks_at(tp)
            if ([s.task for s in pre], [s.task for s in cyc]) != \
               ([b['name'] for b in want_pre], [b['name'] for b in want_cyc]):
                fail(f"t_p={stamp(tp)}: the rules give a different sequence than the scheduler")
                continue
            for got, want in ((pre, want_pre), (cyc, want_cyc)):
                for x, y in zip(got, want):
                    worst = max(worst, abs(x.duration - y['duration']))
        if worst > mw.tol:
            fail(f"rules deviate from the scheduler by {human(worst)}, over the {human(mw.tol)} epsilon")

        # 2. everything at t < t_p stays frozen
        prev_tp, prev_tl = None, None
        for tp in grid:
            tl = timeline_of(mw, tp)
            if prev_tl is not None:
                bad = first_disagreement(prev_tl, tl, prev_tp, mw.sliver)
                if bad is not None:
                    fail(f"frozen past broken at t={stamp(bad)} between t_p={stamp(prev_tp)} "
                         f"and t_p={stamp(tp)} ({occupant(prev_tl, bad)} -> {occupant(tl, bad)})")
            prev_tp, prev_tl = tp, tl

        # 3. contiguity, and 4. minimum execution times
        for tp in grid:
            tl = timeline_of(mw, tp)
            for (s1, e1, _), (s2, _e2, _) in itertools.pairwise(tl):
                if e1 != s2 or e1 < s1:
                    fail(f"t_p={stamp(tp)}: the timeline is not contiguous at {stamp(e1)}")
                    break
            # drawn past the span, so that a run the display simply cuts off is
            # not mistaken for one the scheduler cut short
            for name, served, end in service_runs(timeline_of(mw, tp, mw.span * 2)):
                if name not in mw.minimum or end > mw.span: continue
                if served < mw.minimum[name] - mw.sliver and not blocked_at(mw, tp, name, end):
                    fail(f"t_p={stamp(tp)}: {name} served {human(served)} < its minimum "
                         f"{human(mw.minimum[name])} and nothing stopped it (ends {stamp(end)})")
                    break

        # 5. the cycle every regime settles into matches the target percentages
        for r in mw.regimes:
            total = sum((x.a for x in r.cycle), Fraction(0))
            if not total: continue
            got = {}
            for x in r.cycle:
                got[x.task] = got.get(x.task, Fraction(0)) + x.a / total
            for n, p in mw.p.items():
                if abs(got.get(n, Fraction(0)) - p) > Fraction(1, 1000):
                    fail(f"regime {r.label}: {n} takes {float(got.get(n, 0)) * 100:.3f}% "
                         f"of the cycle, target {float(p) * 100:.3f}%")
                    break

        # 6. the rule list is finite, and 7. reading it is arithmetic
        if mw.rule_count() > MAX_RULES:
            fail(f"a regime carries {mw.rule_count()} rules, over the {MAX_RULES} cap")
        t0 = time.perf_counter()
        for tp in grid:
            timeline_of(mw, tp, horizon=frac(tp) + 10)
        draw = (time.perf_counter() - t0) / len(grid)
        if draw > 10.0:
            fail(f"reading the next 10 minutes off the rules takes {draw:.3f}s, over 10s")

        if verbose:
            print(f"--- {title.splitlines()[0]} ---")
            print(f"  {len(mw.regimes)} regimes over [0, {stamp(mw.span)}], "
                  f"at most {mw.rule_count()} rules each (cap {MAX_RULES})")
            print(f"  checked at {len(grid)} positions of t_p; worst deviation from the "
                  f"scheduler {human(worst)} (epsilon {human(mw.tol)})")
            print(f"  the next 10 minutes read off the rules in {draw * 1000:.3f} ms "
                  f"(budget 10s)")
            mine = [f for f in failures if f.startswith(title.splitlines()[0])]
            if mine:
                print(f"  FAIL ({len(mine)}):")
                for f in mine[:max_report]: print(f"    - {f.split(': ', 1)[1]}")
            else:
                print("  PASS: rules reproduce the scheduler, frozen past, contiguity, "
                      "minimum times, exact cycle shares, O(1) rules")
            print()
    return failures
