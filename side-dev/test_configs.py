#!/usr/bin/env python3
"""
test_configs.py
The scheduler test configurations, and the checks the moving-period cases owe.

Tests 1-9 are static: one call to the scheduler, one rule list.
Tests 10 and 11 have a period that *slides*, and the displayer moves it. The
rules for them come from the same scheduler as every other test -- `MovingWindow`
derives a rule list that is affine in the period's position t_p, so the display
substitutes into it instead of scheduling again.
Test 12 is three days long, with 21 tasks, a night every 24h and a grid of
breaks the line reaches one at a time -- dragging each one, as test 11 drags
its 20s window, until a long enough stretch of privileged-only time discharges
it. No single prefix+cycle describes that, so `ProgressiveWindow` derives a
CHAIN of rules and settles it link by link from t=0 outward, fast enough that
the definitive part of the schedule grows by far more than the ten minutes per
ten seconds the requirement asks for -- and the rules at the line itself are
fitted affine in t_p, one regime at a time, so following it is arithmetic.
"""

import functools
import itertools
import time
from fractions import Fraction
from math import inf

from scheduler_logic import (
    IDLE,
    ProgressiveWindow,
    MAX_RULES,
    MovingWindow,
    Placement,
    Scheduler,
    Task,
    as_blocks,
    frac,
    human,
    human_s,
    resulting_shares,
    stamp,
    truncate,
)

WINDOW = Fraction(1, 3)          # the 20 s period both moving cases carry


def AB():
    return [Task("A", priority=50, min_time=10, color="#FF9999"),
            Task("B", priority=50, min_time=10, color="#99CCFF")]


# Every builder takes the field's decay constant as a MULTIPLE of the case's own
# default tau (its minimal period), so one knob means the same thing to a
# 60-minute case and to a three-day one. A scale of 1 is the default in every
# sense: the options are left exactly as they were, so nothing on record moves.

def _tau_kw(tau_scale):
    return {} if frac(tau_scale) == 1 else {'tau_scale': tau_scale}

def _scaled(case, tau_scale):
    """One static case, with the scale folded into its scheduler options."""
    case = list(case) + [{}] * max(0, 6 - len(case))
    case[5] = dict(case[5] or {}, **_tau_kw(tau_scale))
    return tuple(case)

def build_cases(tau_scale=1):
    cases = [
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
            300, [], [{'start': 105, 'end': inf, 'forbidden': ['C']}]
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
            AB(), 700, [], [{'start': 100, 'end': 400, 'forbidden': ['B']}], {}, 2
        ),
        (
            ("Test 9: same 300min ban, but split into ten consecutive windows\n-> merged into one exclusion: ten short bans in a row are one long ban, not ten small ones."),
            AB(), 700, [], [{'start': 100 + 30 * i, 'end': 130 + 30 * i, 'forbidden': ['B']} for i in range(10)], {}, 2
        ),
        (
            ("Test 9b: two OVERLAPPING periods, on a timeline they do not cover\n-> what an instant refuses is the SUM of the periods over it: C is out from 100, B joins it at 200, and where the two overlap A holds the timeline alone."),
            [Task("A", priority=40, min_time=10, color="#FF9999"), Task("B", priority=30, min_time=10, color="#99CCFF"), Task("C", priority=30, min_time=10, color="#99FF99")],
            700, [], [{'start': 100, 'end': 300, 'forbidden': ['C']},
                      {'start': 200, 'end': 400, 'forbidden': ['B']}], {}, 2
        )
    ]
    if frac(tau_scale) == 1: return cases
    return [_scaled(c, tau_scale) for c in cases]


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
    return [{'start': tp, 'end': tp + WINDOW, 'forbidden': ['B'],
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

TEST11_PERIODS = [{'start': 8, 'end': 12, 'forbidden': ['C', 'D']},
                  {'start': 30, 'end': 34, 'forbidden': ['A', 'B', 'C', 'D']},
                  {'start': 52, 'end': 56, 'forbidden': ['B', 'D']}]

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
    everyone = ['A', 'B', 'C', 'D']
    window = [] if reached_stretch(tp) else [
        {'start': frac(tp), 'end': frac(tp) + WINDOW, 'forbidden': everyone,
         'label': f"{human_s(20)}: nothing"}]
    return window + [
        {'start': s, 'end': s + STRETCH_HEAD, 'forbidden': everyone,
         'label': "1min: nothing"},
        {'start': s + STRETCH_HEAD, 'end': s + STRETCH_LEN, 'forbidden': ['B', 'C', 'D'],
         'label': "4min: only A"}]

TEST11_TITLE = (
    "Test 11: the same sliding 20s period accepting NOTHING, in a crowded timeline\n"
    "-> pre-placed blocks, three static periods, four tasks; plus a 1min-nothing + 4min-A-only\n"
    f"   stretch waiting at {stamp(STRETCH_HOME)}: once the window reaches it the stretch starts at t_p (a 20s\n"
    "   teleport left) and the 20s window is gone forever -- from then on the stretch is what slides."
)


def build_moving_cases(tau_scale=1):
    """The cases whose rule list is *dynamic*: instead of constant durations it
    carries durations affine in the sliding period's position, plus the range of
    positions each rule list is valid for."""
    kw = _tau_kw(tau_scale)
    return [
        (TEST10_TITLE,
         MovingWindow(AB(), span=TEST10_SPAN, moving=test10_moving, **kw),
         25),
        (TEST11_TITLE,
         MovingWindow(tasks11(), span=TEST11_SPAN, moving=test11_moving,
                      pre_placed=TEST11_PRE, periods=TEST11_PERIODS,
                      breaks=[STRETCH_HOME - WINDOW], **kw),
         35),
    ]


# --------------------------------------------------------------------------- #
#  Test 12: three days, 21 tasks, a night every 24h, and a whole GRID of breaks
#  the sliding line consumes one by one
# --------------------------------------------------------------------------- #

HOUR = frac(60)
DAY = 24 * HOUR
TEST12_SPAN = 3 * DAY
TEST12_TP_START = DAY            # the line starts at the end of the first day

PRIVILEGED = [f"P{i}" for i in range(1, 11)]
ORDINARY = [f"N{i}" for i in range(1, 11)]
TEST12_TASKS = ["A"] + PRIVILEGED + ORDINARY
UNPRIVILEGED = ["A"] + ORDINARY  # everybody the privileged-only periods refuse

def _shade(base, i, n):
    """One family of colours, so a glance tells the three groups apart."""
    r, g, b = base
    k = 0.72 + 0.28 * (i / max(n - 1, 1))
    return "#%02X%02X%02X" % tuple(min(255, int(c * k)) for c in (r, g, b))

def tasks12():
    """Task A at 50%, and twenty tasks sharing the other 50% -- half of them
    privileged. Every one of them needs 45 minutes at a time, which is what
    makes the break grid interesting: the gap between two breaks is 20min."""
    out = [Task("A", priority=50, min_time=45, color="#FF7B7B")]
    out += [Task(n, priority=2.5, min_time=45, color=_shade((110, 170, 255), i, 10))
            for i, n in enumerate(PRIVILEGED)]
    out += [Task(n, priority=2.5, min_time=45, color=_shade((255, 190, 90), i, 10))
            for i, n in enumerate(ORDINARY)]
    return out

# ---- the environment that repeats every day -------------------------------- #

def test12_nights(span=TEST12_SPAN):
    """23h-8h: only the privileged may run -- and inside it 0h-8h: nobody.

    They overlap, and overlapping periods add up, so what the night really says
    is "privileged only from 23h, nobody from midnight". The stretch before t=0
    is there too: the timeline does not begin with a blank slate."""
    out = [{'start': -HOUR, 'end': frac(0), 'forbidden': set(UNPRIVILEGED),
            'label': "23h-24h: privileged only"},
           {'start': -DAY, 'end': frac(0), 'forbidden': set(TEST12_TASKS),
            'label': "t<0: nothing"}]
    for d in range(4):
        s = d * DAY
        if s - HOUR < span and s > 0:
            out.append({'start': s - HOUR, 'end': s, 'forbidden': set(UNPRIVILEGED),
                        'label': "23h-24h: privileged only"})
        if s < span:
            out.append({'start': s, 'end': s + 8 * HOUR, 'forbidden': set(TEST12_TASKS),
                        'label': "0h-8h: nothing"})
    return [w for w in out if w['start'] < span]

# ---- the three breaks, and where the rules put them ------------------------ #

BREAK_LEN = {'20s': WINDOW, '5min': frac(5), '15min': frac(15)}
# after a stretch that discharges it, when the next one of that kind is due
BREAK_GAP = {'20s': frac(20), '5min': frac(60), '15min': frac(120)}
# how long a privileged-only stretch must be to discharge each kind ('20s' is
# discharged by any of the three periods as well, which is what makes it recur)
BREAK_NEEDS = {'20s': frac(15), '5min': frac(5), '15min': frac(15)}

def privileged_only_stretches(periods):
    """The maximal intervals where no ordinary task may run.

    "Only tasks from privileges" and "no task at all" are the same thing here:
    an instant that allows nobody allows, a fortiori, only privileged tasks. It
    is what makes the night one nine-hour stretch rather than two, and what
    makes the five-minute break (one minute of nothing, then four of privileged
    only) one five-minute stretch."""
    edges = sorted({frac(b) for w in periods for b in (w['start'], w['end'])
                    if b is not None and b != inf})
    out = []
    for lo, hi in itertools.pairwise(edges):
        mid = (lo + hi) / 2
        banned = set()
        for w in periods:
            if frac(w['start']) <= mid < frac(w['end']): banned |= set(w['forbidden'])
        if not all(n in banned for n in ORDINARY + ["A"]): continue
        if out and out[-1][1] == lo: out[-1] = (out[-1][0], hi)
        else: out.append((lo, hi))
    return out

def test12_grid(span=TEST12_SPAN):
    """Where the three periods fall -- the rules of the requirement, applied.

    Each kind is due a fixed time after the last stretch that discharged it:
    the 20s one 20min after any of the three periods or any quarter-hour of
    privileged-only time, the 5min one an hour after five privileged-only
    minutes, the 15min one two hours after fifteen. A break is itself such a
    stretch, so placing one re-arms the others; the grid that comes out is what
    the timeline must satisfy, at every t_p."""
    nights = privileged_only_stretches(test12_nights(span))
    out = []
    for i, (_lo, night_end) in enumerate(nights):
        due = {k: night_end + BREAK_GAP[k] for k in BREAK_LEN}
        stop = nights[i + 1][0] if i + 1 < len(nights) else span
        while True:
            # when two fall due together the longer one governs: resting fifteen
            # minutes has already discharged the five-minute one
            kind = min(due, key=lambda k: (due[k], -BREAK_LEN[k]))
            start, end = due[kind], due[kind] + BREAK_LEN[kind]
            if end > stop or start >= span: break
            out.append((start, end, kind))
            for k in BREAK_LEN:
                if BREAK_LEN[kind] >= BREAK_NEEDS[k] or k == '20s':
                    due[k] = end + BREAK_GAP[k]
                else:
                    due[k] = max(due[k], end)
    return sorted(out)

def test12_break_periods(entries):
    """One grid entry -> the periods it is made of.

    The 20s one accepts nobody. The 5min one is test 11's stretch: a minute of
    nothing, then four minutes the privileged may work in. The 15min one is
    fifteen minutes of privileged-only."""
    out = []
    for s, e, kind in entries:
        if kind == '20s':
            out.append({'start': s, 'end': e, 'forbidden': set(TEST12_TASKS),
                        'label': f"{human_s(20)}: nothing"})
        elif kind == '5min':
            out.append({'start': s, 'end': s + 1, 'forbidden': set(TEST12_TASKS),
                        'label': "1min: nothing"})
            out.append({'start': s + 1, 'end': e, 'forbidden': set(UNPRIVILEGED),
                        'label': "4min: privileged only"})
        else:
            out.append({'start': s, 'end': e, 'forbidden': set(UNPRIVILEGED),
                        'label': "15min: privileged only"})
    return out

TEST12_GRID = test12_grid()

def test12_static():
    """The part of the environment the sliding line never reaches: the nights,
    and the first day's breaks (t_p starts at the end of it)."""
    return test12_nights() + test12_break_periods(
        [g for g in TEST12_GRID if g[0] < TEST12_TP_START])

TEST12_SWEPT = [g for g in TEST12_GRID if g[0] >= TEST12_TP_START]

def test12_swept(_tp):
    """The environment the line leaves BEHIND it: nothing but the standing one.

    A break the line has reached is owed, and an owed break is at the line --
    never behind it. So the timeline the past is read off holds no break after
    the first day at all, which is what "the t_p line swiped them all" means and
    what keeps a break from existing twice at once."""
    return []

def test12_moving(tp):
    """The environment STANDING ahead of the line: the grid, whole.

    This is what the far end of the timeline is drawn from -- out there no break
    has been reached, so each one sits exactly where the recurrence rules put
    it. What the line itself sees is `test12_at_line`."""
    tp = frac(tp)
    return test12_break_periods([g for g in TEST12_SWEPT if g[1] > tp])

# ---- the break the line drags, and what discharges it ---------------------- #

def test12_discharges(kind):
    """The instants at which the line stops owing a break of `kind`.

    A stretch of privileged-only time discharges a break when it lasts at least
    what that kind asks for, and the next one of that kind is due its own gap
    afterwards -- so the instant that ends the owing and the instant the next
    one is measured from are THE SAME, per kind, and the grid and the drag can
    never disagree about when a timer started (`check_break_rules` holds the
    grid to exactly these)."""
    return [e for s, e in privileged_only_stretches(test12_nights())
            if e - s >= BREAK_NEEDS[kind]]

TEST12_DISCHARGES = {k: test12_discharges(k) for k in BREAK_LEN}

@functools.lru_cache(maxsize=4096)
def test12_owed(tp):
    """The breaks the line has REACHED and nothing has served, and how far the
    longest of them reaches -- test 11's rule, with three kinds of break and a
    whole grid of them instead of one window and one stretch.

    Reaching one is not taking it. The line freezes what is behind it, so a
    break it has reached never actually elapses: it is OWED, and an owed break
    is at the line and slides with it. What ends that is a stretch of
    privileged-only time long enough to discharge that kind -- here the nights.

    Contact is edge to edge, as in test 11: a break is reached when the one
    being dragged REACHES it, not when the line does -- which is what makes the
    five-minute stretch teleport 20 seconds to the left the moment the dragged
    20s window touches it. So the reach is a fixed point: absorbing a longer
    break lengthens the drag, which may bring a further one into contact."""
    tp = frac(tp)
    reach = frac(0)
    while True:
        got = []
        for s, e, k in TEST12_SWEPT:
            if s > tp + reach: continue
            served = [d for d in TEST12_DISCHARGES[k] if s < d <= tp]
            if not served: got.append((s, e, k))
        nxt = max([BREAK_LEN[k] for _s, _e, k in got], default=frac(0))
        if nxt == reach: return got, reach
        reach = nxt

def test12_pinned(tp):
    """Every owed break, at the line -- their exclusion lists ADDED, not one of
    them chosen.

    Overlapping periods sum, so absorbing is not swallowing: a fifteen-minute
    privileged-only break that has taken in a five-minute one still owes that
    one's first minute of absolute silence, and a 20s window inside either of
    them keeps its own. What "the longest governs" really names is the shape
    that comes out of the sum -- one stop, as long as the longest, whose head is
    as closed as the strictest of them asks."""
    got, _reach = test12_owed(tp)
    kinds = sorted({k for _s, _e, k in got}, key=lambda k: BREAK_LEN[k])
    tp = frac(tp)
    out = test12_break_periods([(tp, tp + BREAK_LEN[k], k) for k in kinds])
    for w in out: w['label'], w['pinned'] = w['label'] + " (owed, at the line)", True
    return out

def test12_at_line(tp):
    """The environment AT THE LINE: the grid it has not reached yet, plus every
    break it is dragging. This is what the scheduler is handed -- the drag is an
    ordinary period, not a transform applied to a plan made without it."""
    tp = frac(tp)
    _got, reach = test12_owed(tp)
    return test12_break_periods([g for g in TEST12_SWEPT if g[0] > tp + reach]) \
        + test12_pinned(tp)

TEST12_TITLE = (
    "Test 12 (progressive rule list): three days, 21 tasks of 45min, a night every 24h,\n"
    "-> and a grid of 20s / 5min / 15min breaks the line reaches one by one, DRAGS as test 11\n"
    "   drags its 20s window, and merges into the longest one it touches. No single prefix+cycle\n"
    "   describes this, so the rules are a CHAIN of links settled from t=0 outward: the\n"
    "   definitive part grows while the rest still changes."
)

def build_progressive_cases(tau_scale=1):
    """The case whose rule list is derived a link at a time, while it is shown."""
    return [
        (TEST12_TITLE,
         ProgressiveWindow(tasks12(), span=TEST12_SPAN, moving=test12_moving,
                           sliding=test12_at_line, swept=test12_swept,
                           tp_start=TEST12_TP_START, periods=test12_static(),
                           marks=[g[1] for g in TEST12_SWEPT],
                           max_rules=29, local_rules=12, **_tau_kw(tau_scale)),
         45),
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

def past_shape(tl, cut):
    """The occupancy strictly before `cut`, coalesced.

    Two timelines describe the same past when the same task is running at every
    instant of it -- where a block happens to be CUT is not part of that, and
    the break the line drags cuts the block it stands in the middle of. So the
    comparison is made on this shape rather than on the block list."""
    out = []
    for s, e, n in tl:
        if s >= cut: break
        e = min(e, cut)
        if e <= s: continue
        if out and out[-1][2] == n and out[-1][1] == s: out[-1] = (out[-1][0], e, n)
        else: out.append((s, e, n))
    return out

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
    cannot pass ends it. Everything else that shortens one is a bug.

    Periods overlap, so every one covering t has a say: the first one found
    saying nothing about `name` does not mean `name` is welcome there."""
    for p in mw.pre:
        if p.task != name and p.start <= t < p.end: return True
    for w in list(mw.sliding(frac(tp))) + list(mw.periods):
        end = w.get('end')
        if frac(w['start']) <= t and (end is None or end == inf or t < frac(end)):
            if name in w['forbidden']: return True
    return False

def service_runs(tl, tasks):
    """Consecutive service of one task.

    An interruption nobody runs in -- idling, or a block owned by nobody --
    suspends a run instead of ending it, so it is skipped here exactly as the
    scheduler's own `run_served` skips it."""
    runs = []
    for s, e, n in tl:
        if n not in tasks: continue
        if runs and runs[-1][0] == n:
            runs[-1][1] += e - s
        else:
            runs.append([n, e - s, e])
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
        periods=[{'start': 1, 'end': 3, 'forbidden': ['B']}], t_now=1,
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
            for name, served, end in service_runs(timeline_of(mw, tp, mw.span * 2), mw.minimum):
                if end > mw.span: continue
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


# --------------------------------------------------------------------------- #
#  the checks the progressive case owes
# --------------------------------------------------------------------------- #

PACE_SECONDS = 10.0              # "the right schedule for the next 10 minutes
PACE_MINUTES = frac(10)          #  must not take more than 10 seconds"

_STRETCH_CACHE = {}

def all_periods12():
    """The whole environment, breaks included -- what the timeline is judged on.

    The line consumes the breaks it passes, so at a given t_p the ones behind
    it are gone; the recurrence rules are about where they are PUT, so they are
    checked here against the grid entire, from t=0 to the end."""
    return test12_static() + test12_break_periods(
        [g for g in TEST12_GRID if g[0] >= TEST12_TP_START])

def stretches12(periods):
    """The privileged-only stretches of a fixed environment, computed once: the
    checks ask for them per break and per kind, and the answer never moves."""
    key = id(periods)
    if key not in _STRETCH_CACHE:
        _STRETCH_CACHE[key] = privileged_only_stretches(periods)
    return _STRETCH_CACHE[key]

def discharging_ends(kind, periods, grid):
    """The instants after which a break of `kind` is due `BREAK_GAP[kind]` later.

    A stretch of privileged-only time discharges a break if it lasts at least
    what that kind asks for -- and the 20s one is discharged by any of the
    three periods as well, however short."""
    out = {e for s, e in stretches12(periods) if e - s >= BREAK_NEEDS[kind]}
    if kind == '20s':
        out |= {e for _s, e, _k in grid}
    return sorted(out)

def next_night(t, periods):
    """Where the next stretch of privileged-only time begins after t -- a break
    that would not fit before it is simply not placed."""
    out = [s for s, _e in stretches12(periods) if s > t]
    return min(out) if out else None

def check_break_rules(grid=None, periods=None, verbose=True):
    """The three recurrence rules of the requirement, both ways round:
    every break sits exactly its own gap after the last stretch that
    discharged it, and every such stretch is followed by one."""
    grid = TEST12_GRID if grid is None else grid
    periods = all_periods12() if periods is None else periods
    fails = []
    for kind in BREAK_LEN:
        ends = discharging_ends(kind, periods, grid)
        mine = [(s, e) for s, e, k in grid if k == kind]
        for s, _e in mine:
            before = [x for x in ends if x <= s]
            if not before:
                fails.append(f"the {kind} period at {stamp(s)} follows nothing that could put it there")
                continue
            want = before[-1] + BREAK_GAP[kind]
            if abs(s - want) > Fraction(1, 6000):
                fails.append(f"the {kind} period at {stamp(s)} should be at {stamp(want)} "
                             f"-- {human(BREAK_GAP[kind])} after {stamp(before[-1])}")
        starts = {s for s, _e in mine}
        for e in ends:
            due = e + BREAK_GAP[kind]
            if any(e < x <= due for x in ends): continue      # a later stretch governs
            night = next_night(e, periods)
            if night is not None and due + BREAK_LEN[kind] > night: continue
            if due + BREAK_LEN[kind] > TEST12_SPAN: continue
            if not any(abs(s - due) <= Fraction(1, 6000) for s in starts):
                fails.append(f"no {kind} period at {stamp(due)}, "
                             f"{human(BREAK_GAP[kind])} after the stretch ending {stamp(e)}")
    if verbose:
        print("--- the three periods recur where the rules put them ---")
        counts = {k: sum(1 for _s, _e, x in grid if x == k) for k in BREAK_LEN}
        print("  over three days: " + ", ".join(f"{n} x {k}" for k, n in counts.items()))
        print("  PASS: every one of them sits exactly its own gap after the stretch "
              "that discharged it" if not fails else f"  FAIL ({len(fails)}):")
        for f in fails[:6]: print("    - " + f)
        print()
    return fails

def check_drag_and_merge(samples=4096, verbose=True):
    """Test 11's rule, in test 12: a break the line reaches is DRAGGED, and the
    longest one the drag touches absorbs the others.

    Four things are asserted, at every position of a fine sweep:
      * a break is drawn either where the grid put it (ahead of the line) or ON
        the line -- never behind it and never in between;
      * once the line has reached one, one is always drawn at the line: an owed
        break does not disappear until something discharges it;
      * the drag only ever gets longer while it lasts, i.e. a break never
        shrinks back into a shorter one it had already swallowed; and
      * the contact is edge to edge -- the moment the dragged 20s window
        touches the next five-minute break, that break is what is drawn at the
        line, 20 seconds to the left of where the grid had put it."""
    fails, merges = [], []
    span, start = TEST12_SPAN, TEST12_TP_START
    grid = [start + (span - start) * Fraction(i, samples) for i in range(samples + 1)]
    prev_len, prev_tp = None, None
    for tp in grid:
        shown = test12_at_line(tp)
        pinned = [w for w in shown if w.get('pinned')]
        for w in shown:
            if w.get('pinned'):
                if frac(w['start']) < tp:
                    fails.append(f"t_p={stamp(tp)}: the dragged break is drawn behind the line")
                    break
            elif frac(w['start']) <= tp:
                fails.append(f"t_p={stamp(tp)}: a break the line has reached is still "
                             f"drawn at {stamp(w['start'])}")
                break
        owed = any(not any(s < d <= tp for d in TEST12_DISCHARGES[k])
                   for s, _e, k in TEST12_SWEPT if s <= tp)
        if owed and not pinned:
            fails.append(f"t_p={stamp(tp)}: a break is owed and none is drawn at the line")
        if pinned and not owed:
            fails.append(f"t_p={stamp(tp)}: a break is drawn at the line and none is owed")
        length = max((frac(w['end']) for w in pinned), default=None)
        length = None if length is None else length - tp
        if prev_len is not None and length is not None and length < prev_len:
            fails.append(f"t_p={stamp(tp)}: the dragged break shrank from "
                         f"{human(prev_len)} to {human(length)} without being discharged")
        if prev_len is not None and length is not None and length > prev_len:
            merges.append((prev_tp, prev_len, length))
        prev_len, prev_tp = length, tp

    # the merge test 11 shows, verbatim: 20s -> 5min, and the five-minute break
    # is then drawn one window width left of where the grid had put it
    first = next(((s, e) for s, e, k in TEST12_SWEPT if k == '5min'), None)
    if first is not None:
        contact = first[0] - WINDOW
        before, after = test12_at_line(contact - Fraction(1, 600)), test12_at_line(contact)
        was = [w for w in before if w.get('pinned')]
        now = [w for w in after if w.get('pinned')]
        if not (was and max(frac(w['end']) for w in was)
                - min(frac(w['start']) for w in was) == WINDOW):
            fails.append(f"no 20s break is being dragged just before {stamp(contact)}")
        if not (now and frac(now[0]['start']) == contact
                and max(frac(w['end']) for w in now) - contact == BREAK_LEN['5min']):
            fails.append(f"the {human(BREAK_LEN['5min'])} break does not start at the line "
                         f"at {stamp(contact)}, {human_s(20)} left of {stamp(first[0])}")
    if verbose:
        print("--- the line drags a break, and the longest one absorbs the others ---")
        print(f"  {len(merges)} merges over the sweep" + ("" if first is None else
              f"; the first five-minute break is reached at {stamp(first[0] - WINDOW)} "
              f"and drawn there, {human_s(20)} left of the {stamp(first[0])} the grid gave it"))
        print("  PASS: reached breaks are dragged, never drawn behind the line, and the "
              "longest owed one governs" if not fails else f"  FAIL ({len(fails)}):")
        for f in fails[:6]: print("    - " + f)
        print()
    return fails

def forbidden_at(periods, t):
    out = set()
    for w in periods:
        end = w.get('end')
        if frac(w['start']) <= t and (end is None or end == inf or t < frac(end)):
            out |= set(w['forbidden'])
    return out

def pace_failures(pw, seconds=PACE_SECONDS, minutes=PACE_MINUTES):
    """Over any `seconds` of work, at least `minutes` of timeline must be settled.

    The requirement is a rate, not a per-link budget: a link that settles twenty
    seconds of timeline in a quarter of a second is not a breach as long as the
    ones around it keep the front moving."""
    out = []
    steps = pw.steps
    for i in range(len(steps)):
        spent, got = 0.0, Fraction(0)
        for s, d in steps[i:]:
            spent += s
            got += d
            if spent >= seconds: break
        if spent < seconds: break            # the tail: the derivation finished
        if got < minutes:
            out.append(f"only {human(got)} of timeline settled in {spent:.1f}s of work "
                       f"(needs {human(minutes)} per {seconds:.0f}s)")
            break
    return out

def check_resume_contract(pw, samples=24, verbose=True, max_report=6):
    """A chain of re-plans is the SAME schedule as one long plan.

    The chain settles a link at a time, and every link is a fresh call to the
    scheduler -- so the rules at the line, which walk straight through those
    instants from wherever t_p stands, can only agree with what is drawn if
    resuming at t reproduces the walk that passed through t. Everything the
    walk carries and the seeding has to rebuild from the history is a way for
    that to fail, and each one has failed in turn: `last` read off `_head`, the
    lookback measured in wall time, and the forgetting itself (`_replay_clocks`
    -- the anomaly this check was written for: the panel an hour to the right
    of the line changed the moment the rules there were derived, at a position
    where the line was dragging nothing at all).

    So it is asserted directly, and with the ENVIRONMENT HELD FIXED: both plans
    are shown the same periods and the same lookahead, because the drag is
    supposed to change the answer and would hide what this is looking for. What
    is left over is the resumption, and nothing else.

    The pairs are CONSECUTIVE links, and that is not a detail. Over one link the
    committed timeline is the long plan's own output, so the two really are the
    same walk cut in two; over several the chain has re-planned in between, and
    the plan resumed at the far end is seeded from a past the long plan never
    drew. Sampling therefore thins the PAIRS, never the marks between them."""
    pw.settle()
    starts = [s.start for s in pw.segments if pw.tp_start < s.start < pw.span - 2 * 60]
    pairs = list(itertools.pairwise(starts))
    step = max(1, len(pairs) // samples)
    fails, checked = [], 0
    for g0, g1 in pairs[::step]:
        periods = list(pw.periods) + [w for w in pw.moving(g0)
                                      if frac(w['start']) <= g0 + pw.lookahead]
        long = pw.sched.plan(timeline=[p for p in pw.pre if p.end > g0], periods=periods,
                             t_now=g0, history=truncate(pw.base, g0), max_rules=MAX_RULES)
        acc, walked = g0, None
        for s in long.prefix:
            if acc <= g1 < acc + s.duration: walked = s.task; break
            acc += s.duration
        if walked is None: continue        # the long plan does not reach g1: nothing to hold
        again = pw.sched.plan(timeline=[p for p in pw.pre if p.end > g1], periods=periods,
                              t_now=g1, history=truncate(pw.base, g1), max_rules=MAX_RULES)
        got = again.prefix[0].task if again.prefix else None
        checked += 1
        if got != walked:
            fails.append(f"walking from {stamp(g0)} gives {walked} at {stamp(g1)}, "
                         f"but a plan resumed there gives {got}")
    if verbose:
        print("--- a chain of re-plans is the same schedule as one long plan ---")
        print(f"  {checked} resumptions checked, the environment held fixed across each")
        print("  PASS: resuming at an instant reproduces the walk that passed through it"
              if not fails else f"  FAIL ({len(fails)}):")
        for f in fails[:max_report]: print("    - " + f)
        print()
    return fails

def verify_progressive(cases=None, verbose=True, samples=24, max_report=6):
    cases = cases if cases is not None else build_progressive_cases()
    failures = check_break_rules(verbose=verbose) + check_drag_and_merge(verbose=verbose)
    for title, pw, _sweep in cases:
        fail = lambda m, title=title: failures.append(f"{title.splitlines()[0]}: {m}")
        pw.settle()
        failures += check_resume_contract(pw, verbose=verbose)
        settled, worked = pw.pace()

        # 1. the pace it owes: 10 minutes of timeline per 10 seconds of work
        for m in pace_failures(pw): fail(m)

        grid_tp = [pw.tp_start + (pw.span - pw.tp_start) * Fraction(i, samples)
                   for i in range(samples + 1)]
        prev_tp, prev_tl = None, None
        worst = Fraction(0)
        for tp in grid_tp:
            # 2. the rules AT THE LINE are the scheduler's own answer. Fitted at
            #    a few positions, checked here at positions they were never
            #    fitted on: without this the affine rule list is a guess -- and
            #    it is what makes the dragged break a real re-plan, with the
            #    compensation field the scheduler builds around it, rather than
            #    a plan made without it and patched afterwards.
            pre, cyc = pw.rules_of(tp)
            want_pre, want_cyc = pw.blocks_at(tp)
            if ([s.task for s in pre], [s.task for s in cyc]) != \
               ([b['name'] for b in want_pre], [b['name'] for b in want_cyc]):
                fail(f"t_p={stamp(tp)}: the rules give a different sequence than the scheduler")
            else:
                for got_, want in ((pre, want_pre), (cyc, want_cyc)):
                    for x, y in zip(got_, want):
                        worst = max(worst, abs(x.duration - y['duration']))

            got = pw.timeline(tp)
            here = pw.sliding(tp) + pw.periods

            # 3. contiguity
            for (s1, e1, _), (s2, _e2, _) in itertools.pairwise(got):
                if e1 != s2 or e1 < s1:
                    fail(f"t_p={stamp(tp)}: the timeline is not contiguous at {stamp(e1)}")
                    break

            # 4. nothing is drawn where a period refuses it -- the break the
            #    line is dragging included, since it is a period like any other
            for s, e, n in got:
                if n not in pw.minimum or e <= tp: continue
                mid = (s + e) / 2
                if n in forbidden_at(here, mid):
                    fail(f"t_p={stamp(tp)}: {n} is drawn at {stamp(mid)}, where it is forbidden")
                    break

            # 5. minimum execution times, ahead of the line: a run may be cut
            #    short only by something that refuses it. Read over the whole
            #    drawn timeline, not only the part ahead of the line: a run the
            #    night suspended resumes owing just the rest of its minimum, and
            #    cutting the timeline at t_p would hide the half it already had.
            for name, served, end in service_runs(got, pw.minimum):
                if end <= tp or end >= pw.local_end(tp) or end >= pw.span: continue
                if served < pw.minimum[name] - pw.sliver and not blocked_at(pw, tp, name, end):
                    fail(f"t_p={stamp(tp)}: {name} served {human(served)} < its minimum "
                         f"{human(pw.minimum[name])} and nothing stopped it (ends {stamp(end)})")
                    break

            # 6. everything at t < t_p stays frozen. Compared by OCCUPANT, not
            #    block by block: where a block is cut is not what frozen means.
            if prev_tl is not None and past_shape(prev_tl, prev_tp) != past_shape(got, prev_tp):
                fail(f"the past moved between t_p={stamp(prev_tp)} and t_p={stamp(tp)}")
            prev_tp, prev_tl = tp, got

        if worst > pw.tol:
            fail(f"the rules deviate from the scheduler by {human(worst)}, "
                 f"over the {human(pw.tol)} epsilon")

        # 7. the rule list is finite, and 8. reading it is arithmetic: a regime
        #    is derived once and inside it the display only substitutes
        if pw.rule_count() > MAX_RULES:
            fail(f"a link carries {pw.rule_count()} rules, over the {MAX_RULES} cap")
        slow = max((s for s, _d in pw.fits), default=0.0)
        if slow > PACE_SECONDS:
            fail(f"deriving the rules at one position of the line took {slow:.1f}s, over "
                 f"the {PACE_SECONDS:.0f}s budget")
        t0 = time.perf_counter()
        for tp in grid_tp:
            pw.timeline(tp, horizon=frac(tp) + 10)
        draw = (time.perf_counter() - t0) / len(grid_tp)
        if draw > PACE_SECONDS:
            fail(f"reading the next 10 minutes off the rules takes {draw:.3f}s, over 10s")

        if verbose:
            tl = pw.timeline(pw.span)      # what the line leaves behind it
            print(f"--- {title.splitlines()[0]} ---")
            print(f"  {len(pw.segments)} links + {len(pw.past.segments)} swept links over "
                  f"[0, {stamp(pw.span)}], at most {pw.rule_count()} rules each "
                  f"(cap {MAX_RULES})")
            print(f"  settled in {worked:.1f}s of work: "
                  f"{float(settled) / max(worked, 1e-9):.0f} min of "
                  f"timeline per second of work (needs "
                  f"{float(PACE_MINUTES) / PACE_SECONDS:.0f})")
            print(f"  {len(pw.regimes)} regimes of affine rules derived at the line, "
                  f"worst {slow:.1f}s (budget {PACE_SECONDS:.0f}s); worst deviation from "
                  f"the scheduler {human(worst)} (epsilon {human(pw.tol)})")
            print(f"  the next 10 minutes read off the rules in {draw * 1000:.3f} ms "
                  f"(budget {PACE_SECONDS:.0f}s)")
            print("  " + shares_line(tl, pw))
            mine = [f for f in failures if f.startswith(title.splitlines()[0])]
            if mine:
                print(f"  FAIL ({len(mine)}):")
                for f in mine[:max_report]: print(f"    - {f.split(': ', 1)[1]}")
            else:
                print("  PASS: the rules reproduce the scheduler, the periods are respected, "
                      "the past stays frozen, minimum times hold and the pace is met")
            print()
    return failures

def shares_line(tl, pw, tp=None):
    """What the three days actually gave each group -- reported, not asserted.

    The same measure the window draws: a share of the time some task was allowed
    to run in, so the nine-hour nights that refuse everybody are not counted as
    a share anyone lost. What is missing from the sum is time that WAS offered
    and left empty -- with 21 tasks of 45 minutes each, a gap too short for any
    minimum has no taker.

    A lands a little under its 50% here, and what is missing is accounted for
    rather than lost. Two things take it, both structural. The empty time: a
    privileged-only period is scheduled with NOTHING wherever starting a
    privileged task in it would run past the instant A comes back and leave A
    a gap too short for its 45 minutes -- the run would not use the period, it
    would lengthen A's ban (`Scheduler._clears`). And the privileged surplus:
    where a privileged task is ALREADY running when such a period arrives, its
    run carries straight through, so the ten of them collect those nineteen
    minutes out of every hundred and thirty-five that A is barred from."""
    periods = list(pw.periods) + list(pw.sliding(pw.span if tp is None else tp))
    got, open_total = resulting_shares(tl, periods, list(pw.minimum),
                                       lo=frac(0), hi=pw.span)
    if not open_total: return "nothing was schedulable"
    priv = sum(v for n, v in got.items() if n in PRIVILEGED)
    mine = sum(v for n, v in got.items() if n in pw.minimum)
    return (f"of the {human(open_total)} some task is allowed in: A "
            f"{float(got.get('A', 0)) * 100:.1f}% (target 50%), the ten privileged "
            f"{float(priv) * 100:.1f}%, the ten others "
            f"{float(mine - priv - got.get('A', 0)) * 100:.1f}%, "
            f"offered and left empty {float(1 - mine) * 100:.1f}%")
