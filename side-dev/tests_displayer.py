#!/usr/bin/env python3
"""
tests_displayer.py
The display for `scheduler`, and the checks that hold it to `README.md`.

There is ONE test, and the window is where it is written: the editor under the
bar holds its span, its rule states, its tasks, its pre-placed blocks and its
restrictive periods, and Apply hands the scheduler the test it now is. The
configuration is remembered between runs (`test_config.json`), so the window,
`--verify` and `--rules` are all asking about the same test.

A configuration says only what the timeline IS -- the tasks, the blocks, the
periods, the span -- so everything the README asks for is asked HERE, and
never read off a field the configuration could have set differently:

* the test has a t_p line and both t_p modes (the line is drawn on the bar,
  PLAY sweeps it, MODE 1/2 flips the mode);
* the test is answered progressively, by a worker that owns its scheduler;
* the test owes every check -- the partition, no idling, the minimums, the
  alternative schedule, the frozen past, the recurrence bars, the rule list,
  the pace, and the percentages -- in BOTH modes.

Two rules shaped the window:

* NOTHING THAT COSTS MORE THAN A FRAME RUNS ON THE THREAD THAT DRAWS. The test
  settles its chain on a worker which OWNS the scheduler; the window only ever
  draws snapshots that worker publishes, and asks it for things (move the
  line, jump, change the mode, copy the rules) through a queue.
* THE LINE IS MOVED HERE, and the schedule is read back for the position it
  lands on. DRAGGING sweeps it -- so the line drags the dynamic period it
  reaches; CLICKING somewhere ahead is a JUMP, which sweeps nothing.

    uv run tests_displayer.py              the window, and the test's editor
    uv run tests_displayer.py --verify     every check, no window
    uv run tests_displayer.py --rules      the rule list at the line
    uv run tests_displayer.py --no-ui      the terminal report
"""

from __future__ import annotations

import argparse
import queue
import threading
import time
from dataclasses import replace
from fractions import Fraction
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import tkinter as tk
    HAVE_TK = True
else:
    try:
        import tkinter as tk
        HAVE_TK = True
    except ImportError:                                 # headless box: CLI only
        tk = None
        HAVE_TK = False

import scheduler
from scheduler import (
    DAY,
    EPS,
    HOUR,
    IDLE,
    clip,
    coalesce,
    frac,
    human,
    resulting_shares,
    same_timeline,
)
from test_configs import (
    DEFAULT_CONFIG,
    PALETTE,
    BlockLine,
    Case,
    Config,
    PeriodLine,
    StateLine,
    TaskLine,
    build_case,
    configuration_lines,
    load_config,
    parse_percent,
    parse_resilience,
    parse_time,
    show_percent,
    show_resilience,
)

MODES = (1, 2)                   # every check is owed in both


# --------------------------------------------------------------------------- #
#  layout
# --------------------------------------------------------------------------- #

WIDTH = 1060
MARGIN = 16
BAR_H = 34
ROW_GAP = 12
IDLE_COLOR = "#EFEFEF"
GRID_COLOR = "#DDDDDD"
LINE_COLOR = "#C62828"
FRONT_COLOR = "#2E7D32"
TEXT = "#222222"
DIM = "#777777"
FONT = ("Segoe UI", 9)
FONT_B = ("Segoe UI", 10, "bold")
FONT_M = ("Consolas", 9)

#: A test this long or longer is one that "takes a long time": it is swept
#: more slowly and sampled more coarsely. That is a property of what the test
#: COSTS, measured here -- never a flag in the configuration.
HEAVY_SPAN = 2 * DAY


def is_heavy(case) -> bool:
    return case.span >= HEAVY_SPAN


def sweep_seconds(case) -> float:
    """How many seconds of wall clock a full pass of t_p takes on screen.

    Long spans are swept no faster in wall time than short ones are, or the
    line would cross a whole day between two frames and the drag would have
    nothing to show.
    """
    days = float(case.span) / float(DAY)
    return 25.0 + 8.0 * min(days, 8.0)


def tick_step(span) -> Fraction:
    """A round number of minutes that leaves eight to sixteen ticks."""
    for step in (Fraction(5), Fraction(10), Fraction(20), Fraction(30), HOUR,
                 2 * HOUR, 4 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY):
        if span / step <= 16:
            return step
    return span / 8


def clock(t) -> str:
    """An instant as a clock reading, for the long cases: day + hh:mm."""
    t = frac(t)
    if t < DAY:
        return human(t)
    d, rest = divmod(t, DAY)
    h, m = divmod(rest, HOUR)
    return f"d{int(d) + 1} {int(h):02d}:{int(m):02d}"


def task_color(case, name) -> str:
    if name == IDLE:
        return IDLE_COLOR
    for spec in case.sched.specs_at(0):
        if spec.name == name:
            return spec.color
    return "#CCCCCC"


def task_rows(case, tl):
    """Every task with what it asked for and what it got."""
    got = resulting_shares(tl)
    specs = case.sched.specs_at(case.sched.t_p)
    total = sum((s.priority for s in specs), Fraction(0)) or Fraction(1)
    rows = []
    for spec in specs:
        rows.append((spec.name, float(spec.priority / total) * 100,
                     spec.min_time, float(got.get(spec.name, 0)) * 100, spec.color))
    return rows


# --------------------------------------------------------------------------- #
#  reading a timeline
# --------------------------------------------------------------------------- #

def covers_completely(tl, lo, hi) -> str:
    """A timeline must be a partition of its span: no gap, no overlap."""
    cur = frac(lo)
    for pl in tl:
        if pl.start < cur - EPS:
            return f"overlap at {human(pl.start)}"
        if pl.start > cur + EPS:
            return f"gap at {human(cur)}..{human(pl.start)}"
        cur = pl.end
    if abs(cur - frac(hi)) > EPS:
        return f"stops at {human(cur)}, not at {human(hi)}"
    return ""


def idle_where_allowed(case, tl, t_p, mode):
    """Every idle stretch, if any, that some task was allowed in.

    The README's no-idling clause, and it is owed "for any t_p and t_p mode".
    """
    env = case.sched.environment(t_p, mode)
    specs = case.sched.specs_at(t_p)
    shares = case.sched.walk_at(t_p).p
    out = []
    for pl in tl:
        if pl.task != IDLE:
            continue
        mid = (pl.start + pl.end) / 2
        w = env.weights(specs, shares, mid)
        free = [n for n, v in w.items() if v > 0]
        if free:
            out.append(f"idle at {human(mid)} where {', '.join(sorted(free)[:3])} could run")
    return out


def short_placements(case, tl, t_p, mode):
    """Placements below their task's minimum that no edge and no horizon
    explains -- the minimum is soft, but it is only ever given up to the
    environment."""
    walk = case.sched.walk_at(t_p)
    edges = set(case.sched.environment(t_p, mode).bounds) | {case.span, case.sched.t_start}
    out = []
    for pl in tl:
        if pl.task not in walk.minimum:
            continue
        if pl.duration >= walk.minimum[pl.task]:
            continue
        if pl.start in edges or pl.end in edges:
            continue
        out.append(f"{pl.task} for {human(pl.duration)} at {human(pl.start)} "
                   f"(minimum {human(walk.minimum[pl.task])})")
    return out


def unnamed_alternatives(case, tl, t_p, mode):
    """Every rule the SCHEDULER made names an alternative -- where there is one
    to name. Two exemptions, and both are the absence of a choice rather than a
    missing answer: a pre-placed block is a fact of the starting timeline and
    not a pick, and a stretch only one task is allowed in has nothing to run
    instead."""
    env = case.sched.environment(t_p, mode)
    specs, shares = case.sched.specs_at(t_p), case.sched.walk_at(t_p).p
    if len(shares) < 2:
        return []
    for pl in tl:
        if pl.task == IDLE or pl.alt:
            continue
        mid = (pl.start + pl.end) / 2
        if case.sched.base.block_at(mid) is not None:
            continue
        if sum(1 for v in env.weights(specs, shares, mid).values() if v > 0) < 2:
            continue
        return [f"the rule at {human(pl.start)} names no alternative"]
    return []


def scheduled_shares(case, tl) -> dict:
    """What each task of the rule state actually took, over the part of the
    timeline the SCHEDULER filled -- a pre-placed block is not a pick, so it is
    left out of both the numerator and the total."""
    names = {s.name for s in case.sched.specs_at(0)}
    per, total = {}, Fraction(0)
    for pl in tl:
        if pl.task == IDLE:
            continue
        mid = (pl.start + pl.end) / 2
        if case.sched.base.block_at(mid) is not None:
            continue
        if pl.task not in names:
            continue
        per[pl.task] = per.get(pl.task, Fraction(0)) + pl.duration
        total += pl.duration
    if not total:
        return {}
    return {n: d / total for n, d in sorted(per.items())}


def fair_shares(case, mode=1) -> dict:
    """The share each task would take if every instant were simply split among
    the tasks allowed IN IT, in proportion to their percentages.

    This is the percentages read against the timeline the test actually has.
    A task refused for half the span cannot take its full share -- and neither
    can its RIVALS fail to take more where it is refused, which is why this is
    an integral over the whole span rather than a per-task correction. It says
    nothing about the compensation, whose whole point is to depart from it near
    a blockage; it is the level the departures are measured from.
    """
    sched = case.sched
    env = sched.environment(sched.t_start, mode)
    specs, shares = sched.specs_at(0), sched.walk_at(0).p
    per = {s.name: Fraction(0) for s in specs}
    total = Fraction(0)
    for a, b in env.segments(sched.t_start, case.span):
        mid = (a + b) / 2
        if sched.base.block_at(mid) is not None:
            continue                          # a pre-placed block is not a pick
        w = env.weights(specs, shares, mid)
        live = sum((v for v in w.values() if v > 0), Fraction(0))
        if live <= 0:
            continue                          # nobody may run: not schedulable time
        for n, v in w.items():
            if v > 0:
                per[n] += (b - a) * v / live
        total += b - a
    if total <= 0:
        return {}
    return {n: d / total for n, d in per.items()}


def shares_line(tl, limit=6) -> str:
    got = resulting_shares(tl)
    items = sorted(got.items(), key=lambda kv: (-kv[1], kv[0]))
    head = ", ".join(f"{n} {float(v) * 100:.1f}%" for n, v in items[:limit])
    return head + (f", +{len(items) - limit} more" if len(items) > limit else "")


# --------------------------------------------------------------------------- #
#  what a case IS, and what it answered -- in a form a person can read
# --------------------------------------------------------------------------- #

def configuration_text(case) -> str:
    """The test configuration: every rule state and the starting timeline.

    The copy button asks for exactly this -- the pre-placed tasks and the
    pre-placed restrictive periods, which is to say everything that is NOT one
    of the three dynamic periods, since those are placed by the scheduler and
    move with the line. It is the configuration as EDITED that is printed,
    which is what makes a copied report a thing a person can type back into
    the editor.
    """
    return "\n".join(configuration_lines(case.config))


def rules_text(case, t_p=None, mode=1, max_rules=60) -> str:
    sched = case.sched
    t_p = sched.t_p if t_p is None else frac(t_p)
    reg = sched.rules(t_p, mode)
    head = (f"valid while t_p is in [{human(reg.lo)}, {human(reg.hi)}]"
            if reg.hi > reg.lo else "valid at this position of t_p")
    out = [f"rules at t_p = {human(t_p)} (mode {mode}):", "  " + head]
    for seg in reg.segments[:max_rules]:
        out.append(seg.line())
    if len(reg.segments) > max_rules:
        out.append(f"  ... and {len(reg.segments) - max_rules} more")
    alt = sched.alternative_at(t_p, mode)
    out.append(f"  alternative schedule at t_p: {alt or '(none)'}")
    return "\n".join(out)


def report_text(case, t_p=None, mode=1) -> str:
    """What the copy button puts on the clipboard: the configuration AND the
    resulting set of rules, in a form a person can read."""
    sched = case.sched
    t_p = sched.t_p if t_p is None else frac(t_p)
    tl = sched.timeline(t_p, mode)
    parts = [configuration_text(case), "", rules_text(case, t_p, mode), "",
             f"resulting shares: {shares_line(tl, 24)}"]
    return "\n".join(parts)


# --------------------------------------------------------------------------- #
#  the checks -- every test owes every one of them, in both modes
# --------------------------------------------------------------------------- #

def _report(fails):
    if fails:
        print(f"  FAIL ({len(fails)}):")
        for f in fails[:8]:
            print("    - " + f)
        if len(fails) > 8:
            print(f"    ... and {len(fails) - 8} more")
    else:
        print("  PASS")
    print()


def positions(case, samples):
    lo, hi = case.sched.t_start, case.span
    return [lo + (hi - lo) * Fraction(i, samples) for i in range(samples + 1)]


def sample_count(case) -> int:
    """Positions of the line one sweep is checked at. A long case is answered
    over a long horizon, so it is sampled more coarsely -- the rule being
    checked does not depend on how finely."""
    return 12 if is_heavy(case) else 48


def verify_readme(verbose=True):
    """The engine's own clause-by-clause checks against README.md."""
    fails = []
    if verbose:
        print("--- README.md, clause by clause (scheduler's own checks) ---")
    for fn in scheduler.CHECKS:
        try:
            note = fn()
            if verbose:
                print(f"  {fn.__name__[6:]:<32} {note}")
        except Exception as exc:                        # noqa: BLE001 - reported
            fails.append(f"{fn.__name__}: {exc}")
    if verbose:
        _report(fails)
    return fails


def verify_timelines(case, verbose=True):
    """What the test owes at a standing line, in both modes: a partition, no
    idling where a task was allowed, the minimums, an alternative at every
    rule, the mode's own rule at the line, and the same answer twice."""
    fails = []
    if verbose:
        print("--- the test, at the origin, in both modes ---")
    sched = case.sched
    t_p = sched.t_start
    for mode in MODES:
        tl = sched.timeline(t_p, mode)
        where = f"mode {mode}"
        bad = covers_completely(tl, sched.t_start, case.span)
        if bad:
            fails.append(f"{where}: the timeline is not a partition -- {bad}")
        for line in idle_where_allowed(case, tl, t_p, mode)[:2]:
            fails.append(f"{where}: {line}")
        for line in short_placements(case, tl, t_p, mode)[:2]:
            fails.append(f"{where}: {line}")
        for line in unnamed_alternatives(case, tl, t_p, mode):
            fails.append(f"{where}: {line}")
        fails += mode_rule_at_line(case, t_p, mode)
        if verbose:
            print(f"  {where}   {shares_line(tl)}")
    # a rule list is a function of the configuration and of nothing else
    if not same_timeline(sched.timeline(t_p, 1), case.fresh().sched.timeline(t_p, 1)):
        fails.append("two builds of the same configuration disagree")
    if verbose:
        _report(fails)
    return fails


def mode_rule_at_line(case, t_p, mode):
    """The two t_p modes, at the line.

    Mode 1: no period the SCHEDULER placed covers t_p with "no on-screen
    task" -- a pre-placed period is a fact of the starting timeline and never
    moves, so it is the dynamic ones the mode governs. Mode 2: t_p IS covered
    by one, and the scheduler must have made one if the timeline had none.
    """
    sched = case.sched
    if mode == 1:
        covering = [p for p in sched.dynamic_periods(t_p, 1)
                    if p.covers(t_p)
                    and p.kind in (scheduler.KIND_NO_TASK, scheduler.KIND_NO_SCREEN)]
        if covering:
            return [f"mode 1 left {covering[0].label or covering[0].kind} over the line "
                    f"at {human(t_p)}"]
        return []
    if not sched.environment(t_p, 2).no_screen_at(t_p):
        return [f"mode 2 left the line uncovered at {human(t_p)}"]
    return []


def verify_frozen_past(case, verbose=True):
    """The frozen past, in both modes.

    The line is walked forward across the whole span, committing as it goes --
    which is what makes the past a fact rather than an intention -- and every
    position owes agreement with everything the earlier ones committed, plus a
    complete timeline and no idling at the position it now stands at.
    """
    fails = []
    if verbose:
        print("--- the frozen past: the line swept across the test, in both modes ---")
    for mode in MODES:
        line = case.fresh().sched
        seen = []
        samples = sample_count(case)
        for t_p in positions(case, samples):
            line.advance_to(t_p, mode)
            tl = line.timeline()
            where = f"mode {mode} at t_p={clock(t_p)}"
            bad = covers_completely(tl, line.t_start, case.span)
            if bad:
                fails.append(f"{where}: {bad}")
            # ...AHEAD of the line only. The no-idling clause is owed
            # against the environment in force where the stretch was
            # placed, and mode 2 deliberately covers the line with "no
            # on-screen task" -- so a line swept in mode 2 leaves a trail of
            # legitimate emptiness behind it wherever every task is on-screen.
            for text in idle_where_allowed(case, clip(tl, t_p, case.span), t_p, mode)[:1]:
                fails.append(f"{where}: {text}")
            for pos, older in seen[-3:]:
                if not same_timeline(clip(older, line.t_start, pos),
                                     clip(tl, line.t_start, pos)):
                    fails.append(f"mode {mode}: the schedule below {clock(pos)} "
                                 f"changed once the line reached {clock(t_p)}")
                    break
            seen.append((t_p, tl))
        if verbose:
            print(f"  mode {mode}   {samples + 1} positions swept")
    if verbose:
        _report(fails)
    return fails


def verify_jump_sweeps_nothing(case, verbose=True):
    """A click that lands ahead is a JUMP, and a jump sweeps nothing: every
    dynamic period BELOW the landing point is where the bars put it, none
    dragged -- because the line was never inside any of them.

    A period that begins exactly AT the landing point is the one exception,
    and it is mode 1 rather than the sweep: the line may not be covered, so
    that one is pushed off it like any other the line stands on.
    """
    fails = []
    if verbose:
        print("--- a jump sweeps nothing ---")
    sched = case.fresh().sched
    target = sched.t_start + (case.span - sched.t_start) / 2
    before = sched.planner_at(target).instances(target, 1, sweep_from=target)
    sched.teleport_to(target, 1)
    after = sched.planner_at(target).instances(target, 1, sweep_from=sched.sweep_from)
    below = [i for i in after if i.end <= target]
    if any(i.open_start for i in below):
        fails.append("the jump dragged a period")
    if len(before) != len(after):
        fails.append("the jump changed where the periods are")
    if verbose:
        print(f"  landed at {clock(target)}, {len(below)} period(s) standing "
              f"below it, none dragged")
        _report(fails)
    return fails


def rest_stretches(planner, inst):
    """Every stretch the recurrence rules bar from, over the whole test.

    The README's own list: "whether caused by dynamic periods, pre-placed
    restrictive periods, or a combination" -- so the pre-placed rest the
    planner found and the counting part of each dynamic period go into one
    pot, and abutting ones are MERGED. A 20s laid against the end of a night
    is not a 20-second stretch and a 15-minute one: it is one stretch, which
    is why the merge is the last step and not a special case.
    """
    spans = list(planner.rested)
    for i in inst:
        run = i.stretch_run(planner.kind_counts)
        if run is not None:
            spans.append(run)
    return scheduler.merge_spans(spans)


def verify_bars(case, verbose=True):
    """The three recurrence rules, read off the grid the test actually places:
    every period sits at least its own bar after everything that bars it, and
    no two of them overlap.

    Read off the STRETCHES rather than off the periods, because that is what
    the README bars from -- a pre-placed night bars exactly as a 15-minute
    dynamic period does. A stretch that reaches past the period being judged
    is the one it is standing in, and bars nothing.
    """
    fails = []
    if verbose:
        print("--- the recurrence bars of the three dynamic periods ---")
    planner = case.sched.planner_at(0)
    inst = planner.instances(case.sched.t_start, 1)
    stretches = rest_stretches(planner, inst)
    for i, cur in enumerate(inst):
        for prev in inst[:i]:
            if prev.end > cur.start:
                fails.append(f"{prev.label} at {clock(prev.start)} "
                             f"overlaps {cur.label} at {clock(cur.start)}")
                continue
            if cur.label == "20s" and cur.start - prev.end < scheduler.BAR_20S_AFTER_ANY - EPS:
                fails.append(f"a 20s only {human(cur.start - prev.end)} after the "
                             f"{prev.label} at {clock(prev.start)}")
        for a, b in stretches:
            if b > cur.start:
                continue                      # the stretch this one is inside
            gap, length = cur.start - b, b - a
            if length >= scheduler.STRETCH_SHORT and cur.label == "5min" \
                    and gap < scheduler.BAR_5MIN_AFTER_STRETCH - EPS:
                fails.append(f"a 5min at {clock(cur.start)}, only {human(gap)} "
                             f"after a {human(length)} stretch")
            if length >= scheduler.STRETCH_LONG and cur.label == "20s" \
                    and gap < scheduler.BAR_20S_AFTER_LONG - EPS:
                fails.append(f"a 20s at {clock(cur.start)}, only {human(gap)} "
                             f"after a {human(length)} stretch")
            if length >= scheduler.STRETCH_LONG and cur.label == "15min" \
                    and gap < scheduler.BAR_15MIN_AFTER_LONG - EPS:
                fails.append(f"a 15min at {clock(cur.start)}, only {human(gap)} "
                             f"after a {human(length)} stretch")
    if verbose:
        counts = {}
        for i in inst:
            counts[i.label] = counts.get(i.label, 0) + 1
        print(f"  over {human(case.span)}: "
              + (", ".join(f"{n} x {k}" for k, n in sorted(counts.items()))
                 or "no dynamic period fits")
              + f", barred from {len(stretches)} stretch(es)")
        _report(fails)
    return fails


def _drawn(reg, x, end):
    """The rules drawn at a position, in the form a timeline is read in.

    `Regime.draw` emits one rule per SEGMENT of the fit, and a segment ends
    wherever the plan the fit was measured from had a boundary -- a period
    edge a task runs straight through, a link seam of the progressive chain.
    `Scheduler.timeline` coalesces those away, because the two halves are one
    rule. Comparing one against the other unjoined reports a seam as a
    disagreement, which is a difference of FORM and not of schedule.
    """
    return coalesce(clip(reg.draw(x), x, end))


def verify_rules(case, verbose=True):
    """The rule list at the line.

    What the README asks is that the rules the scheduler returns be
    PARAMETERISED by t_p -- so what is checked is that a claimed range is
    TRUE: the rules must reproduce the scheduler at positions of the line they
    were never fitted on.

    A range is not always claimable, and that is not a failure. A rule's bound
    is affine in t_p only where a period edge sets it; where the influence
    field sets it the bound follows an exponential, and over any range the
    chord misses the curve by more than the certification tolerance. The
    scheduler then falls back to a regime of ONE position -- still a complete
    rule list, still naming its alternative, just not claimed for a range --
    so the line is never left without an answer. Which of the two this one
    configuration gets is not a verdict on it, so it is printed rather than
    failed; that the parameterisation is alive at all is `verify_readme`'s
    `check_rules_are_affine_in_tp`, over the scheduler's own fixed cases.
    """
    fails = []
    if verbose:
        print("--- the rule list at the line, and the range of t_p it is claimed for ---")
    line = case.fresh().sched
    t_p = line.t_start + (case.span - line.t_start) / 3
    line.advance_to(t_p, 1)
    reg = line.rules(span=frac(2))
    end = min(line.horizon, reg.lo + line.LOOKAHEAD)
    if reg.hi > reg.lo:
        for k in (1, 3, 5, 7):
            x = reg.lo + (reg.hi - reg.lo) * Fraction(k, 8)
            drawn = _drawn(reg, x, end)
            actual = clip(line.timeline(x, 1, upto=end), x, end)
            if not same_timeline(drawn, actual):
                fails.append(f"the rules claim {human(reg.hi - reg.lo)} of t_p but "
                             f"disagree with the scheduler at {clock(x)}")
                break
    else:
        # a regime of one position still has to BE the answer there
        drawn = _drawn(reg, reg.lo, end)
        actual = clip(line.timeline(reg.lo, 1, upto=end), reg.lo, end)
        if not same_timeline(drawn, actual):
            fails.append("the rule list does not reproduce the scheduler at its "
                         "own position")
    if not reg.segments:
        fails.append(f"the rule list at {clock(t_p)} is empty")
    if verbose:
        moving = sum(1 for s in reg.segments if s.start_slope or s.end_slope)
        width = (human(reg.hi - reg.lo) if reg.hi > reg.lo
                 else "this position only (a bound the field curves)")
        print(f"  at {clock(t_p)}: {len(reg.segments)} rules, {moving} moving "
              f"with the line, claimed for {width}")
        _report(fails)
    return fails


PACE_SECONDS = 10.0              # "the right schedule for the next 10 minutes
PACE_MINUTES = frac(10)          #  must not take more than 10 seconds"


def _resume_divergence(whole, chain) -> str:
    """WHERE a chain and the single plan part company, and by how much.

    A different TASK is a different schedule; a boundary that has moved is the
    same schedule drawn slightly differently, and the size of the move is what
    says whether the walk was resumed or only approximately resumed.
    """
    if len(whole) != len(chain):
        return f"{len(chain)} placements against the plan's {len(whole)}"
    worst, where = Fraction(0), ""
    for x, y in zip(whole, chain):
        if x.task != y.task:
            return f"{y.task} where the plan plays {x.task}, at {clock(x.start)}"
        moved = max(abs(x.start - y.start), abs(x.end - y.end))
        if moved > worst:
            worst, where = moved, clock(x.start)
    return (f"every task agrees, but a boundary has moved by "
            f"{float(worst) * 60:.3f}s at {where}")


def verify_progressive(case, verbose=True, settle_seconds=1.0):
    """The progressive calculation: the pace, and the resume contract -- a
    chain of links must be the same schedule as one long plan, or a partial
    answer is not an answer."""
    fails = []
    if verbose:
        print("--- the pace, and the chain IS the plan ---")
    sched = case.fresh().sched
    t0 = time.perf_counter()
    start = sched.front
    sched.settle(budget_seconds=settle_seconds)
    elapsed = time.perf_counter() - t0
    gained = sched.front - start
    rate = float(gained) / max(elapsed, 1e-9)
    if rate < float(PACE_MINUTES) / PACE_SECONDS:
        fails.append(f"{float(gained):.0f} timeline-minutes in {elapsed:.1f}s -- "
                     f"the pace asks for one a second")
    chain = coalesce(clip(sched.committed + sched._chain, sched.t_start, sched.front))
    whole = clip(case.fresh().sched.timeline(sched.t_start, 1, upto=sched.front),
                 sched.t_start, sched.front)
    if not same_timeline(chain, whole):
        fails.append("the settled chain is not the single plan -- "
                     + _resume_divergence(whole, chain))
    bad = covers_completely(chain, sched.t_start, sched.front)
    if bad:
        fails.append(f"the settled part is not a partition -- {bad}")
    if verbose:
        print(f"  settled {human(gained)} in {elapsed:.1f}s "
              f"({rate:.0f} timeline-minutes per second), chain == plan")
        _report(fails)
    return fails


def verify_percentages(case, verbose=True):
    """The first optimisation criterion, measured.

    What each task took, against what the timeline allowed it to take
    (`fair_shares`). It is a BAND and not an equality, deliberately: the
    compensation clause exists precisely to depart from the fair share near a
    blockage, and the granularity criterion trades against the minimums. So
    what is checked is that nobody is starved or hogging -- a factor of two
    either way of the level the environment sets -- and the gap is printed, for
    a person to read.
    """
    fails = []
    if verbose:
        print("--- the percentages, against what the timeline allowed each task ---")
    sched = case.sched
    tl = sched.timeline(sched.t_start, 1)
    got = scheduled_shares(case, tl)
    fair = fair_shares(case)
    worst, worst_name = Fraction(0), ""
    for name, want in sorted(fair.items()):
        share = got.get(name, Fraction(0))
        if want <= 0:
            continue
        if share < want / 2 - EPS:
            fails.append(f"{name} took {float(share) * 100:.1f}%, starved -- the "
                         f"timeline allowed it {float(want) * 100:.1f}%")
        if share > min(Fraction(1), want * 2) + EPS:
            fails.append(f"{name} took {float(share) * 100:.1f}%, hogging -- the "
                         f"timeline allowed it {float(want) * 100:.1f}%")
        if abs(share - want) > worst:
            worst, worst_name = abs(share - want), name
    if verbose:
        print(f"  {len(fair)} tasks, worst departure from the fair share: "
              f"{worst_name} {float(worst) * 100:.1f} points -- {shares_line(tl, 4)}")
        _report(fails)
    return fails


def verify_reports(case, verbose=True):
    """What the copy button puts on the clipboard: the configuration AND the
    resulting rules, in a form a person can read -- a copy button that quietly
    copied an empty rule list would look exactly like one that worked."""
    fails = []
    if verbose:
        print("--- the copy button's text: configuration + rules ---")
    text = report_text(case, case.sched.t_start, 1)
    for wanted in ("rule states:", "starting timeline", "rules at t_p",
                   "alternative schedule", "resulting shares"):
        if wanted not in text:
            fails.append(f"the report has no \"{wanted}\" section")
    if not [ln for ln in text.splitlines() if ln.startswith("  ") and "->" in ln]:
        fails.append("the report names no rule at all")
    for st in case.sched.states.states:
        if human(st.at) not in text:
            fails.append(f"the rule state at {human(st.at)} is not in the report")
    if verbose:
        print(f"  the report is complete, {len(text.splitlines())} lines")
        _report(fails)
    return fails


def verify_all(case=None, verbose=True):
    """Every check the README asks for, against the test as it now is."""
    t0 = time.perf_counter()
    case = build_case() if case is None else case
    if verbose:
        print(f"--- the test: {case.title} ---")
        print(f"  {case.note}\n")
    bad = case.config.problems()
    if bad:
        for line in bad:
            print(f"  PROBLEM: {line}")
        return bad
    fails = verify_readme(verbose)
    fails += verify_timelines(case, verbose)
    fails += verify_bars(case, verbose)
    fails += verify_frozen_past(case, verbose)
    fails += verify_jump_sweeps_nothing(case, verbose)
    fails += verify_rules(case, verbose)
    fails += verify_progressive(case, verbose)
    fails += verify_percentages(case, verbose)
    fails += verify_reports(case, verbose)
    if verbose:
        print(f"{'FAILED: ' + str(len(fails)) if fails else 'all checks pass'} "
              f"({time.perf_counter() - t0:.1f}s)")
    return fails


# --------------------------------------------------------------------------- #
#  a snapshot: everything the window needs in order to draw one case
# --------------------------------------------------------------------------- #

class Snapshot:
    __slots__ = ("front", "mode", "note", "periods", "placements", "t_p")

    def __init__(self, t_p, mode, front, placements, periods, note=""):
        self.t_p, self.mode, self.front = t_p, mode, front
        self.placements, self.periods, self.note = placements, periods, note


def snapshot_of(case, t_p=None, mode=1, note="") -> Snapshot:
    sched = case.sched
    t_p = sched.t_p if t_p is None else frac(t_p)
    return Snapshot(t_p, mode, sched.front, sched.timeline(t_p, mode),
                    sched.dynamic_periods(t_p, mode), note)


# --------------------------------------------------------------------------- #
#  the worker that owns a case
# --------------------------------------------------------------------------- #

class Deriver(threading.Thread):
    """Owns one case's scheduler.

    The window never touches that scheduler: it posts commands here and draws
    the snapshots that come back. Settling a link of a three-day chain is a
    fraction of a second against a frame of a twentieth, so a window that did
    it between frames would stutter -- and one that did it while the line moved
    would stop answering the mouse. Every case gets one, because every case is
    answered progressively.
    """

    def __init__(self, case: Case):
        super().__init__(daemon=True)
        self.case = case
        self.commands = queue.Queue()
        self.snapshots = queue.Queue()
        self.mode = 1
        self._stop = threading.Event()

    # -- the window's side ---------------------------------------------------

    def send(self, kind, value=None):
        self.commands.put((kind, value))

    def latest(self):
        snap = None
        while True:
            try:
                snap = self.snapshots.get_nowait()
            except queue.Empty:
                return snap

    def stop(self):
        self._stop.set()

    # -- the worker's side ---------------------------------------------------

    def run(self):
        sched = self.case.sched
        self._publish()
        while not self._stop.is_set():
            moved = self._drain(sched)
            settled = False
            if sched.front < sched.horizon:
                sched.settle(budget_seconds=0.25)
                settled = True
            if moved or settled:
                self._publish()
            if not settled and not moved:
                time.sleep(0.05)

    def _drain(self, sched) -> bool:
        moved = False
        while True:
            try:
                kind, value = self.commands.get_nowait()
            except queue.Empty:
                return moved
            if kind == "t_p":                # a drag: the line sweeps
                target = max(frac(value), sched.t_p)
                if target != sched.t_p:
                    sched.advance_to(target, self.mode)
                    moved = True
            elif kind == "jump":             # a click ahead: it sweeps nothing
                target = max(frac(value), sched.t_p)
                sched.teleport_to(target, self.mode)
                moved = True
            elif kind == "mode":
                self.mode = int(value)
                sched.advance_to(sched.t_p, self.mode)
                moved = True
            elif kind == "copy":
                value(report_text(self.case, sched.t_p, self.mode))

    def _publish(self, note=""):
        sched = self.case.sched
        if not note:
            done = sched.front >= sched.horizon
            note = ("settled whole" if done
                    else f"definitive to {clock(sched.front)} and growing")
        self.snapshots.put(snapshot_of(self.case, sched.t_p, self.mode, note))


# --------------------------------------------------------------------------- #
#  one case on the canvas
# --------------------------------------------------------------------------- #

class Panel:
    """A case drawn at a fixed y on the shared canvas.

    Everything above the bar (the heading, the tasks, the periods) is drawn
    once; the bar, the line and the status are redrawn from a snapshot. Every
    panel has a line, both modes and a worker -- the README asks the same of
    every test, so the window offers the same on every test.
    """

    def __init__(self, app, case: Case, y: int, width=WIDTH):
        self.app, self.case, self.y0, self.width = app, case, y, width
        self.canvas = app.canvas
        self.items = []                      # what a redraw clears
        self.playing = False
        self.mode = 1
        self.last_frame = None
        self.deriver = None
        self.snapshot = snapshot_of(case, case.sched.t_start, 1)
        self.x0 = MARGIN + 4
        self.x1 = width - MARGIN - 4
        self.rows = task_rows(case, self.snapshot.placements)
        self.height = self._static_height()
        self.bar_y = self.y0 + self.height - BAR_H - 26
        self._draw_static()
        self.redraw()
        self.deriver = Deriver(case)
        self.deriver.start()

    # -- geometry ------------------------------------------------------------

    def _static_height(self):
        lines = 2 + (len(self.rows) + 2) // 3 + min(len(self.case.periods), 3)
        return 26 + 14 * lines + BAR_H + 40

    def x_of(self, t):
        span = self.case.span - self.case.sched.t_start
        f = float((frac(t) - self.case.sched.t_start) / span)
        return self.x0 + (self.x1 - self.x0) * min(max(f, 0.0), 1.0)

    def t_of(self, x):
        span = self.case.span - self.case.sched.t_start
        f = (x - self.x0) / max(self.x1 - self.x0, 1)
        return self.case.sched.t_start + span * frac(min(max(f, 0.0), 1.0))

    def hit(self, x, y) -> bool:
        return self.bar_y - 4 <= y <= self.bar_y + BAR_H + 4 and self.x0 - 6 <= x <= self.x1 + 6

    # -- the parts that never move -------------------------------------------

    def _draw_static(self):
        c, y = self.canvas, self.y0 + 6
        c.create_text(MARGIN, y, anchor="nw", text=self.case.heading, fill=TEXT, font=FONT_B)
        y += 16
        c.create_text(MARGIN, y, anchor="nw", text="-> " + self.case.note, fill=DIM,
                      font=FONT, width=self.width - 2 * MARGIN)
        y += 14 * (1 + len(self.case.note) // 130)
        for i in range(0, len(self.rows), 3):
            x = MARGIN
            for name, pct, mn, _got, color in self.rows[i:i + 3]:
                c.create_rectangle(x, y + 2, x + 9, y + 11, fill=color, outline="#999999")
                c.create_text(x + 13, y, anchor="nw", font=FONT_M, fill=TEXT,
                              text=f"{name} {pct:g}% min {human(mn)}")
                x += 250
            y += 14
        for p in self.case.periods[:3]:
            c.create_text(MARGIN, y, anchor="nw", font=FONT_M, fill=DIM,
                          text=f"period {p.label or p.kind}: {clock(p.start)} .. {clock(p.end)}")
            y += 14
        if len(self.case.periods) > 3:
            c.create_text(MARGIN, y - 14, anchor="nw", font=FONT_M, fill=DIM,
                          text=f"    (+{len(self.case.periods) - 3} more periods)")
        self.tasks_bottom = y

    # -- the parts a snapshot redraws ----------------------------------------

    def redraw(self, snap: Snapshot | None = None):
        if snap is not None:
            self.snapshot = snap
        c = self.canvas
        for item in self.items:
            c.delete(item)
        self.items = []
        snap = self.snapshot
        y, h = self.bar_y, BAR_H
        self.items.append(c.create_rectangle(self.x0, y, self.x1, y + h,
                                             fill="#FFFFFF", outline="#AAAAAA"))
        px = 0.0
        for pl in snap.placements:
            a, b = self.x_of(pl.start), self.x_of(pl.end)
            if b - a < 0.6 and a < px:
                continue                     # narrower than a pixel and already covered
            px = b
            color = IDLE_COLOR if pl.task == IDLE else task_color(self.case, pl.task)
            self.items.append(c.create_rectangle(a, y + 1, max(b, a + 0.8), y + h - 1,
                                                 fill=color, outline=""))
            if b - a > 26:
                self.items.append(c.create_text((a + b) / 2, y + h / 2, text=pl.task,
                                                font=FONT, fill="#333333"))
        step = tick_step(self.case.span - self.case.sched.t_start)
        t = self.case.sched.t_start
        while t <= self.case.span:
            x = self.x_of(t)
            self.items.append(c.create_line(x, y + h, x, y + h + 4, fill="#999999"))
            self.items.append(c.create_text(x, y + h + 6, anchor="n", text=clock(t),
                                            font=("Segoe UI", 7), fill=DIM))
            t += step
        if snap.front < self.case.span:
            fx = self.x_of(snap.front)
            self.items.append(c.create_line(fx, y - 3, fx, y + h + 3, fill=FRONT_COLOR, width=2))
            self.items.append(c.create_text(fx + 3, y - 12, anchor="nw", text="definitive",
                                            font=("Segoe UI", 7), fill=FRONT_COLOR))
        lx = self.x_of(snap.t_p)
        self.items.append(c.create_line(lx, y - 8, lx, y + h + 3, fill=LINE_COLOR, width=2))
        self.items.append(c.create_polygon(lx - 4, y - 8, lx + 4, y - 8, lx, y - 2,
                                           fill=LINE_COLOR, outline=""))
        text = (f"t_p = {clock(snap.t_p)}   mode {snap.mode}   "
                f"alternative here: {self._alternative(snap)}   "
                f"shares: {shares_line(snap.placements, 6)}")
        if snap.note:
            text += f"   [{snap.note}]"
        self.items.append(c.create_text(MARGIN, y + h + 20, anchor="nw", text=text,
                                        font=FONT_M, fill=TEXT))

    def _alternative(self, snap):
        for pl in snap.placements:
            if pl.alt and pl.start <= snap.t_p < pl.end:
                return pl.alt
        for pl in snap.placements:
            if pl.alt and pl.end > snap.t_p:
                return pl.alt + " (next)"
        return "(none)"

    # -- interaction ---------------------------------------------------------

    def set_line(self, t_p):
        """A drag: the line travels, so it sweeps what it passes."""
        if self.deriver:
            self.deriver.send("t_p", frac(t_p))

    def jump(self, t_p):
        """A click: the line lands somewhere it never travelled through."""
        if self.deriver:
            self.deriver.send("jump", frac(t_p))

    def set_mode(self, mode):
        self.mode = int(mode)
        if self.deriver:
            self.deriver.send("mode", self.mode)

    def play(self, on):
        self.playing = on
        self.last_frame = time.perf_counter() if on else None

    def copy(self):
        if self.deriver and self.deriver.is_alive():
            self.deriver.send("copy", lambda text: self.app.queue_clipboard(text, self.case.name))
        else:
            self.app.to_clipboard(report_text(self.case, self.snapshot.t_p,
                                              self.snapshot.mode), self.case.name)

    def tick(self):
        snap = self.deriver.latest() if self.deriver else None
        if snap is not None:
            self.redraw(snap)
        if not self.playing:
            return
        now = time.perf_counter()
        dt = now - (self.last_frame or now)
        self.last_frame = now
        span = float(self.case.span - self.case.sched.t_start)
        step = frac(dt * span / max(sweep_seconds(self.case), 1e-6)).limit_denominator(10 ** 6)
        if self.snapshot.t_p + step >= self.case.span:
            self.playing = False
            self.app.sync_buttons()
            return
        self.set_line(self.snapshot.t_p + step)

    def stop(self):
        if self.deriver:
            self.deriver.stop()
            self.deriver = None




# --------------------------------------------------------------------------- #
#  the editor: the one test, as widgets
# --------------------------------------------------------------------------- #

EDITOR_BG = "#F7F7F7"
BAD = "#B00020"


def _entry(parent, value, width, **kw):
    e = tk.Entry(parent, width=width, font=FONT_M, **kw)
    e.insert(0, value)
    return e


def _head(parent, text, col=0, row=0, **kw):
    tk.Label(parent, text=text, font=FONT, fg=DIM, bg=EDITOR_BG).grid(
        column=col, row=row, sticky="w", padx=2, **kw)


class Editor:
    """The configuration of the one test, as things a person can click.

    The widgets are the draft and `self.model` is the last configuration that
    was READ out of them: a structural change (a row added, a row deleted)
    harvests the widgets into the model first, mutates the model, and rebuilds
    every widget from it, so there is never a second place a half-typed value
    could be hiding. Nothing here touches the scheduler -- Apply hands the
    finished configuration to the window, which is the only thing that builds
    a test out of it.

    A value that cannot be read is not silently dropped: it is kept as it was
    and named in the status line, and Apply refuses a configuration with any
    problem in it (`Config.problems`) rather than handing the scheduler a test
    it cannot answer.
    """

    def __init__(self, parent, config, on_apply, on_status):
        self.model = config
        self.on_apply = on_apply
        self.on_status = on_status
        self.errors = []
        self.frame = tk.Frame(parent, bg=EDITOR_BG)
        top = tk.Frame(self.frame, bg=EDITOR_BG)
        top.pack(side="top", fill="x", padx=6, pady=(4, 2))
        tk.Label(top, text="the test:", font=FONT_B, bg=EDITOR_BG).pack(side="left")
        self.w_title = _entry(top, config.title, 52)
        self.w_title.pack(side="left", padx=6)
        tk.Label(top, text="span", font=FONT, fg=DIM, bg=EDITOR_BG).pack(side="left")
        self.w_span = _entry(top, human(config.span), 9)
        self.w_span.pack(side="left", padx=4)
        tk.Button(top, text="Apply", font=FONT_B, width=7,
                  command=self.apply).pack(side="left", padx=(12, 4))
        tk.Button(top, text="Reset", font=FONT, width=6,
                  command=self.reset).pack(side="left")

        columns = tk.Frame(self.frame, bg=EDITOR_BG)
        columns.pack(side="top", fill="both", expand=True, padx=6, pady=2)
        self.col_states = tk.Frame(columns, bg=EDITOR_BG)
        self.col_blocks = tk.Frame(columns, bg=EDITOR_BG)
        self.col_periods = tk.Frame(columns, bg=EDITOR_BG)
        for col in (self.col_states, self.col_blocks, self.col_periods):
            col.pack(side="left", anchor="n", padx=(0, 18))
        self.rebuild()

    # -- laying it out -------------------------------------------------------

    def rebuild(self):
        for col in (self.col_states, self.col_blocks, self.col_periods):
            for child in col.winfo_children():
                child.destroy()
        self.w_title.delete(0, "end")
        self.w_title.insert(0, self.model.title)
        self.w_span.delete(0, "end")
        self.w_span.insert(0, human(self.model.span))
        self.w_states, self.w_blocks, self.w_periods = [], [], []
        self._states()
        self._blocks()
        self._periods()

    def _states(self):
        parent, row = self.col_states, 0
        for i, st in enumerate(self.model.states):
            head = tk.Frame(parent, bg=EDITOR_BG)
            head.grid(column=0, row=row, sticky="w", pady=(6 if i else 0, 0))
            row += 1
            tk.Label(head, text="rule state at", font=FONT_B, bg=EDITOR_BG).pack(side="left")
            at = _entry(head, human(st.at), 8)
            at.pack(side="left", padx=4)
            if len(self.model.states) > 1:
                tk.Button(head, text="x", font=FONT, width=2,
                          command=lambda i=i: self._drop_state(i)).pack(side="left")
            grid = tk.Frame(parent, bg=EDITOR_BG)
            grid.grid(column=0, row=row, sticky="w")
            row += 1
            for c, text in enumerate(("task", "%", "min time", "screen")):
                _head(grid, text, col=c, row=0)
            tasks = []
            for j, t in enumerate(st.tasks):
                cells = {"name": _entry(grid, t.name, 10),
                         "percent": _entry(grid, show_percent(t.percent), 6),
                         "min": _entry(grid, human(t.min_time), 9),
                         "screen": _entry(grid, show_resilience(t.screen), 6),
                         "color": t.color}
                cells["name"].grid(column=0, row=j + 1, padx=1, pady=1)
                cells["percent"].grid(column=1, row=j + 1, padx=1)
                cells["min"].grid(column=2, row=j + 1, padx=1)
                cells["screen"].grid(column=3, row=j + 1, padx=1)
                tk.Label(grid, text="  ", bg=t.color, relief="solid",
                         borderwidth=1).grid(column=4, row=j + 1, padx=3)
                tk.Button(grid, text="x", font=FONT, width=2,
                          command=lambda i=i, j=j: self._drop_task(i, j)).grid(
                              column=5, row=j + 1)
                tasks.append(cells)
            tk.Button(grid, text="+ task", font=FONT,
                      command=lambda i=i: self._add_task(i)).grid(
                          column=0, row=len(st.tasks) + 1, sticky="w", pady=2)
            # The one resilience typed on the TASK, because it is the one kind
            # the README's own rules name: 0 is an on-screen task.
            tk.Label(grid, font=FONT, fg=DIM, bg=EDITOR_BG, justify="left",
                     text=('"screen" = resilience % to "no on-screen task" '
                           "(0 = an on-screen task)")).grid(
                column=0, row=len(st.tasks) + 2, columnspan=6, sticky="w")
            self.w_states.append({"at": at, "tasks": tasks})
        tk.Button(parent, text="+ rule state", font=FONT,
                  command=self._add_state).grid(column=0, row=row, sticky="w", pady=4)

    def _blocks(self):
        parent = self.col_blocks
        tk.Label(parent, text="pre-placed blocks", font=FONT_B, bg=EDITOR_BG).grid(
            column=0, row=0, columnspan=3, sticky="w")
        for c, text in enumerate(("task", "start", "for")):
            _head(parent, text, col=c, row=1)
        for j, b in enumerate(self.model.blocks):
            cells = {"task": _entry(parent, b.task, 12),
                     "start": _entry(parent, human(b.start), 9),
                     "duration": _entry(parent, human(b.duration), 9)}
            cells["task"].grid(column=0, row=j + 2, padx=1, pady=1)
            cells["start"].grid(column=1, row=j + 2, padx=1)
            cells["duration"].grid(column=2, row=j + 2, padx=1)
            tk.Button(parent, text="x", font=FONT, width=2,
                      command=lambda j=j: self._drop_block(j)).grid(column=3, row=j + 2)
            self.w_blocks.append(cells)
        tk.Button(parent, text="+ block", font=FONT, command=self._add_block).grid(
            column=0, row=len(self.model.blocks) + 2, sticky="w", pady=2)

    def _periods(self):
        parent = self.col_periods
        tk.Label(parent, text="restrictive periods", font=FONT_B, bg=EDITOR_BG).grid(
            column=0, row=0, columnspan=3, sticky="w")
        for c, text in enumerate(("from", "to", "refuses")):
            _head(parent, text, col=c, row=1)
        for j, p in enumerate(self.model.periods):
            cells = {"start": _entry(parent, human(p.start), 9),
                     "end": _entry(parent, human(p.end), 9),
                     "refuses": _entry(parent, p.refuses, 16)}
            cells["start"].grid(column=0, row=j + 2, padx=1, pady=1)
            cells["end"].grid(column=1, row=j + 2, padx=1)
            cells["refuses"].grid(column=2, row=j + 2, padx=1)
            tk.Button(parent, text="x", font=FONT, width=2,
                      command=lambda j=j: self._drop_period(j)).grid(column=3, row=j + 2)
            self.w_periods.append(cells)
        tk.Button(parent, text="+ period", font=FONT, command=self._add_period).grid(
            column=0, row=len(self.model.periods) + 2, sticky="w", pady=2)
        tk.Label(parent, font=FONT, fg=DIM, bg=EDITOR_BG, justify="left",
                 text=("\"everybody\", \"on-screen\", or the tasks it turns\n"
                       "away: \"B\", \"B, C\", \"B:50%\" for a resilience it keeps")).grid(
            column=0, row=len(self.model.periods) + 3, columnspan=4, sticky="w")

    # -- reading the widgets back -------------------------------------------

    def harvest(self) -> Config:
        """The configuration the widgets now hold.

        A field that cannot be read keeps the value the model already had and
        is named in `self.errors` -- a typo must not throw away the rest of
        what was typed.
        """
        self.errors = []
        states = []
        for i, w in enumerate(self.w_states):
            old = self.model.states[i]
            tasks = []
            for j, cells in enumerate(w["tasks"]):
                was = old.tasks[j]
                tasks.append(TaskLine(
                    cells["name"].get().strip() or was.name,
                    self._percent(cells["percent"], was.percent, f"{was.name}'s percentage"),
                    self._time(cells["min"], was.min_time, f"{was.name}'s minimum time"),
                    self._resilience(cells["screen"], was.screen,
                                     f"{was.name}'s screen resilience"),
                    cells["color"]))
            states.append(StateLine(self._time(w["at"], old.at, "a rule state's instant"),
                                    tuple(tasks)))
        blocks = []
        for j, cells in enumerate(self.w_blocks):
            was = self.model.blocks[j]
            blocks.append(BlockLine(
                cells["task"].get().strip() or was.task,
                self._time(cells["start"], was.start, f"the block of {was.task}"),
                self._time(cells["duration"], was.duration, f"the block of {was.task}")))
        periods = []
        for j, cells in enumerate(self.w_periods):
            was = self.model.periods[j]
            periods.append(PeriodLine(
                self._time(cells["start"], was.start, "a period's start"),
                self._time(cells["end"], was.end, "a period's end"),
                cells["refuses"].get().strip()))
        return Config(self.w_title.get().strip() or self.model.title,
                      self._time(self.w_span, self.model.span, "the span"),
                      tuple(states), tuple(blocks), tuple(periods))

    def _time(self, entry, fallback, what):
        try:
            return parse_time(entry.get())
        except ValueError:
            self.errors.append(f"{what}: {entry.get()!r} is not a time, "
                               f"kept {human(fallback)}")
            return fallback

    def _percent(self, entry, fallback, what):
        try:
            return parse_percent(entry.get())
        except (ValueError, ZeroDivisionError):
            self.errors.append(f"{what}: {entry.get()!r} is not a percentage, "
                               f"kept {show_percent(fallback)}")
            return fallback

    def _resilience(self, entry, fallback, what):
        try:
            return parse_resilience(entry.get())
        except (ValueError, ZeroDivisionError):
            self.errors.append(f"{what}: {entry.get()!r} is not a resilience "
                               f"between 0% and 100%, kept "
                               f"{show_resilience(fallback)}%")
            return fallback

    # -- the structural buttons ---------------------------------------------

    def _mutate(self, fn):
        """Harvest, change the shape, draw it again. Errors are reported but
        never block a structural change: a row must be removable even while
        another row holds a typo."""
        self.model = self.harvest()
        self.model = fn(self.model)
        self.rebuild()
        self.on_status("; ".join(self.errors) if self.errors
                       else "edited -- Apply hands it to the scheduler",
                       bad=bool(self.errors))

    def _next_name(self, config) -> str:
        taken = {t.name for st in config.states for t in st.tasks}
        for ch in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
            if ch not in taken:
                return ch
        i = 1
        while f"T{i}" in taken:
            i += 1
        return f"T{i}"

    def _add_task(self, i):
        def fn(config):
            name = self._next_name(config)
            n = len(config.states[i].tasks)
            row = TaskLine(name, Fraction(10), Fraction(10), Fraction(0),
                           PALETTE[n % len(PALETTE)])
            states = list(config.states)
            states[i] = StateLine(states[i].at, states[i].tasks + (row,))
            return replace(config, states=tuple(states))
        self._mutate(fn)

    def _drop_task(self, i, j):
        def fn(config):
            states = list(config.states)
            tasks = list(states[i].tasks)
            del tasks[j]
            states[i] = StateLine(states[i].at, tuple(tasks))
            return replace(config, states=tuple(states))
        self._mutate(fn)

    def _add_state(self):
        def fn(config):
            at = config.span
            taken = {st.at for st in config.states}
            while at in taken:
                at += HOUR
            last = config.states[-1]
            return replace(config, states=config.states + (StateLine(at, last.tasks),))
        self._mutate(fn)

    def _drop_state(self, i):
        def fn(config):
            states = list(config.states)
            del states[i]
            return replace(config, states=tuple(states))
        self._mutate(fn)

    def _add_block(self):
        def fn(config):
            name = config.tasks[0].name if config.tasks else "MAINTENANCE"
            return replace(config, blocks=config.blocks
                           + (BlockLine(name, config.span / 4, HOUR),))
        self._mutate(fn)

    def _drop_block(self, j):
        def fn(config):
            blocks = list(config.blocks)
            del blocks[j]
            return replace(config, blocks=tuple(blocks))
        self._mutate(fn)

    def _add_period(self):
        def fn(config):
            start = config.span / 2
            return replace(config, periods=config.periods
                           + (PeriodLine(start, start + HOUR, "everybody"),))
        self._mutate(fn)

    def _drop_period(self, j):
        def fn(config):
            periods = list(config.periods)
            del periods[j]
            return replace(config, periods=tuple(periods))
        self._mutate(fn)

    # -- Apply ---------------------------------------------------------------

    def apply(self):
        config = self.harvest()
        problems = self.errors + config.problems()
        if problems:
            self.on_status("cannot answer this test: " + "; ".join(problems[:3]), bad=True)
            return
        self.model = config
        self.rebuild()
        self.on_apply(config)

    def reset(self):
        self.model = DEFAULT_CONFIG
        self.rebuild()
        self.on_apply(DEFAULT_CONFIG)


# --------------------------------------------------------------------------- #
#  the window
# --------------------------------------------------------------------------- #

CANVAS_H = 300


class Workbench:
    """The whole display: ONE test, the editor that writes it, and the bar it
    resolves to.

    The test on screen is whatever the editor last applied, and that
    configuration is written to `test_config.json` as it is applied -- so the
    window, `--verify` and `--rules` are asking about the same test, and the
    window opens where it was left.

    Applying a configuration builds a NEW case and a new worker: a scheduler
    owns a frozen past and a settled chain, both of which are answers to the
    test it was built for, so a changed test cannot inherit them.
    """

    def __init__(self, root, width=WIDTH, config=None, on_ready=None):
        self.root = root
        self.width = width
        self.panel = None
        self.saving = True
        self.config = config or load_config()
        self._clip_queue = queue.Queue()
        self._check_queue = queue.Queue()
        self._dragging = None
        self._checker = None

        bar = tk.Frame(root)
        bar.pack(side="top", fill="x")
        tk.Label(bar, text="scheduler", font=FONT_B).pack(side="left", padx=(8, 12))
        self.play_btn = tk.Button(bar, text="PLAY", font=FONT, width=6,
                                  command=self.toggle_play)
        self.play_btn.pack(side="left")
        self.mode_btn = tk.Button(bar, text="mode 1", font=FONT, width=7,
                                  command=self.toggle_mode)
        self.mode_btn.pack(side="left", padx=4)
        tk.Button(bar, text="copy", font=FONT, width=6,
                  command=self.copy).pack(side="left")
        self.check_btn = tk.Button(bar, text="check", font=FONT, width=6,
                                   command=self.check)
        self.check_btn.pack(side="left", padx=4)
        self.status = tk.Label(bar, text="building the test...", font=FONT, fg=DIM,
                               anchor="w")
        self.status.pack(side="left", fill="x", expand=True, padx=8)

        frame = tk.Frame(root)
        frame.pack(side="top", fill="x")
        self.canvas = tk.Canvas(frame, bg="white", width=width, height=CANVAS_H,
                                highlightthickness=0)
        sb = tk.Scrollbar(frame, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.canvas.bind("<Button-1>", self._press)
        self.canvas.bind("<B1-Motion>", self._drag)
        self.canvas.bind("<ButtonRelease-1>", self._release)
        self.canvas.bind("<MouseWheel>", self._wheel)
        root.protocol("WM_DELETE_WINDOW", self.close)

        self.editor = Editor(root, self.config, self.apply_config, self.set_status)
        self.editor.frame.pack(side="top", fill="both", expand=True)

        self.on_ready = on_ready
        # the window is on screen before anything is derived
        root.after(20, self._build_panel)
        root.after(40, self._tick)

    # -- the test on screen --------------------------------------------------

    def _build_panel(self):
        self.panel = Panel(self, self.config.build(), 0)
        # the bar takes the room the test needs and no more: what is left over
        # belongs to the editor, which is what grows when a task is added
        room = self.panel.height + 12        # the status line sits under the bar
        self.canvas.configure(scrollregion=(0, 0, self.width, room),
                              height=min(max(room, 140), CANVAS_H))
        self.sync_buttons()
        self.set_status("drag the bar to sweep t_p, click ahead to jump; PLAY sweeps it, "
                        "the editor below is the test")
        if self.on_ready:
            self.on_ready(self)
            self.on_ready = None

    def apply_config(self, config: Config):
        """A new configuration: a new case, and a new worker for it."""
        self.config = config
        if self.saving:
            try:
                config.save()
            except OSError as exc:                      # noqa: BLE001 - reported
                self.set_status(f"the test could not be written down: {exc}", bad=True)
        if self.panel is not None:
            self.panel.stop()
            self.panel = None
        self.canvas.delete("all")
        self.set_status(f"building: {config.summary}")
        self.root.after(10, self._build_panel)

    # -- interaction ---------------------------------------------------------

    def toggle_play(self):
        if self.panel:
            self.panel.play(not self.panel.playing)
            self.sync_buttons()

    def toggle_mode(self):
        if self.panel:
            self.panel.set_mode(2 if self.panel.mode == 1 else 1)
            self.sync_buttons()

    def sync_buttons(self):
        if not self.panel:
            return
        self.play_btn.config(text="PAUSE" if self.panel.playing else "PLAY")
        self.mode_btn.config(text=f"mode {self.panel.mode}")

    def set_status(self, text, bad=False):
        self.status.config(text=text, fg=BAD if bad else DIM)

    def _panel_at(self, x, y):
        return self.panel if self.panel and self.panel.hit(x, y) else None

    def _canvas_xy(self, event):
        return self.canvas.canvasx(event.x), self.canvas.canvasy(event.y)

    def _press(self, event):
        """A press is a JUMP: the line lands where it was clicked without
        travelling through anything in between, so it sweeps nothing."""
        x, y = self._canvas_xy(event)
        panel = self._panel_at(x, y)
        if panel is None:
            return
        panel.play(False)
        self.sync_buttons()
        self._dragging = panel
        panel.jump(panel.t_of(x))

    def _drag(self, event):
        """A drag MOVES the line continuously, so it sweeps -- and drags the
        dynamic period it reaches."""
        x, _y = self._canvas_xy(event)
        if self._dragging is not None:
            self._dragging.set_line(self._dragging.t_of(x))

    def _release(self, _event):
        self._dragging = None

    def _wheel(self, event):
        self.canvas.yview_scroll(int(-event.delta / 60), "units")

    # -- the checks, off the drawing thread ----------------------------------

    def check(self):
        """Every README check, against the test as it now is.

        On a worker, and against a case of its OWN: a check sweeps the line and
        commits as it goes, which would trample the scheduler the panel is
        drawing.
        """
        if self._checker and self._checker.is_alive():
            self.set_status("the checks are still running", bad=True)
            return
        config = self.config
        self.set_status("checking (the report is on the terminal)...")

        def work():
            try:
                fails = verify_all(config.build())
                self._check_queue.put(f"{len(fails)} failure(s)" if fails
                                      else "every check passes")
            except Exception as exc:                    # noqa: BLE001 - reported
                self._check_queue.put(f"the checks stopped: {exc}")

        self._checker = threading.Thread(target=work, daemon=True)
        self._checker.start()

    # -- clipboard -----------------------------------------------------------

    def copy(self):
        if self.panel:
            self.panel.copy()

    def to_clipboard(self, text, what=""):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.set_status(f"{what} copied: {len(text.splitlines())} lines on the clipboard")

    def queue_clipboard(self, text, what=""):
        """A worker asked for a copy; the clipboard belongs to the UI thread."""
        self._clip_queue.put((text, what))

    # -- the frame -----------------------------------------------------------

    def _tick(self):
        try:
            while True:
                text, what = self._clip_queue.get_nowait()
                self.to_clipboard(text, what)
        except queue.Empty:
            pass
        try:
            while True:
                self.set_status(self._check_queue.get_nowait())
        except queue.Empty:
            pass
        if self.panel:
            self.panel.tick()
        self.root.after(50, self._tick)

    def report(self):
        """What the window is showing -- the self-test's way of saying that it
        really drew, swept and settled something."""
        if not self.panel:
            return "  (nothing on screen)"
        snap = self.panel.snapshot
        return (f"  {self.config.summary}\n"
                f"  {len(snap.placements)} placements, {len(self.panel.items)} canvas "
                f"items, t_p={clock(snap.t_p)}, mode {snap.mode}, "
                f"definitive to {clock(snap.front)}")

    def close(self):
        if self.panel:
            self.panel.stop()
        self.root.destroy()


# --------------------------------------------------------------------------- #
#  the terminal
# --------------------------------------------------------------------------- #

def print_terminal_results(case=None, settle_seconds=1.0):
    case = build_case() if case is None else case
    print(configuration_text(case))
    case.sched.settle(budget_seconds=settle_seconds)
    print(f"\n  settled to {human(case.sched.front)} in {settle_seconds}s")
    tl = case.sched.timeline(case.sched.t_start, 1)
    print(f"resulting shares: {shares_line(tl, 24)}")


def print_rules(case=None, max_rules=24):
    case = build_case() if case is None else case
    sched = case.sched
    sched.settle(budget_seconds=0.5)
    t_p = sched.t_start
    # a timeline that opens inside a period refusing everybody has nothing but
    # "nobody may run" to say there; the rules worth printing are the ones the
    # line meets once something is allowed again
    nxt = next((pl.start for pl in sched.timeline(t_p, 1)
                if pl.task != IDLE and pl.start >= t_p), None)
    if nxt is not None and nxt > t_p:
        sched.advance_to(nxt, 1)
        t_p = nxt
    print(case.heading)
    print(rules_text(case, t_p, 1, max_rules=max_rules))


def main(argv=None):
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[2])
    ap.add_argument("--verify", action="store_true", help="run every check, no window")
    ap.add_argument("--rules", action="store_true", help="print the rule list at the line")
    ap.add_argument("--no-ui", action="store_true", help="the terminal report only")
    ap.add_argument("--default", action="store_true",
                    help="the default test, ignoring the saved customisation")
    ap.add_argument("--self-test", type=float, default=0.0, metavar="SECONDS",
                    help="open the window, exercise it, then close it")
    args = ap.parse_args(argv)

    config = DEFAULT_CONFIG if args.default else load_config()
    bad = config.problems()
    if bad:
        print("the saved test cannot be answered:")
        for line in bad:
            print(f"  - {line}")
        print("  (uv run test_configs.py --reset puts the default back)")
        return 1

    if args.verify:
        return 1 if verify_all(config.build()) else 0
    if args.rules:
        print_rules(config.build())
        return 0
    if args.no_ui or not HAVE_TK:
        if not HAVE_TK and not args.no_ui:
            print("tkinter is not available: printing the terminal report instead.\n")
        print_terminal_results(config.build())
        return 0

    print("--- the window ---")
    print("  There is ONE test, and the editor at the bottom is where it is written:")
    print("  its span, its rule states and their tasks, the blocks already on the")
    print("  timeline and the periods that refuse them. Apply builds the test and")
    print("  writes it to test_config.json, so the window opens where you left it")
    print("  and --verify asks about the same test; Reset puts the default back.")
    print("  The bar is the answer: CLICK it to jump the line there (a jump sweeps")
    print("  nothing), DRAG to sweep it (a sweep drags the dynamic period the line")
    print("  reaches), PLAY sweeps it for you, mode 1/2 flips the two t_p modes,")
    print("  copy puts the configuration and the rules on the clipboard, and check")
    print("  runs every README check against the test on screen.\n", flush=True)

    root = tk.Tk()
    root.title("scheduler -- the test, and the rules it resolves to")
    root.geometry(f"{WIDTH + 20}x820")
    bench = Workbench(root, width=WIDTH, config=config)
    if args.self_test:
        bench.saving = False                 # a self-test must not rewrite the test

        def sweep():
            if bench.panel:
                bench.panel.play(True)
                bench.sync_buttons()

        def exercise():
            # the paths a hand would take: flip the mode, jump the line, take a
            # copy, then edit the test and apply it
            panel = bench.panel
            if panel:
                panel.set_mode(2)
                panel.jump(panel.case.span / 2)
                panel.copy()
            bench.editor._add_task(0)
            bench.editor.w_span.delete(0, "end")
            bench.editor.w_span.insert(0, "2h")
            bench.editor.apply()
            bench.sync_buttons()

        def finish():
            print("--- what the window drew ---")
            print(bench.report(), flush=True)
            bench.close()
        root.after(300, sweep)
        root.after(max(int(args.self_test * 500), 800), exercise)
        root.after(int(args.self_test * 1000), finish)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
