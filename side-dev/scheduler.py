#!/usr/bin/env python3
"""
scheduler.py
The self-contained implementation of `README.md`, written from the
specification itself.

What it is
----------
This file answers the README on its own terms: it was derived from the spec
rather than grown around the tests in `tests.md`. Nothing here is imported by
the displayer -- it stands alone, and `--check` is its own verification.

What the README asks for, and where each clause lives
-----------------------------------------------------
* priority percentages + the smallest granularity that respects the minimums
                                          -> `Walk._chunk` / `Walk._round`
* soft minimum execution time             -> `Walk._chunk`'s floor
* restrictive periods with a per-kind *resilience multiplier* in [0, 1]
                                          -> `Environment.weights`
* compensation by exponential decay       -> `Walk._build_field` / `Walk._boost`
* rule-state evolution (keyframes blended evenly)
                                          -> `RuleStates.at`
* the three dynamic periods and their recurrence bars
                                          -> `DynamicPlanner`
* the two t_p modes and the chain merge   -> `DynamicPlanner.periods`
* the line moving CONTINUOUSLY            -> `Scheduler.advance_to` (there is
                                             no jump: a distant position is a
                                             journey, swept a slot at a time)
* frozen past                             -> `Scheduler._commit_at`
* alternative schedules                   -> `Placement.alt`
* no idling                               -> `Walk.run` (a non-empty candidate
                                             set is always served)
* progressive calculation                 -> `Scheduler.settle`
* rules parameterised by t_p              -> `Scheduler.rules` -> `Regime`

Two properties the whole thing is built to keep, and that `--check` asserts:

* THE PAST IS FROZEN BY CONSTRUCTION. `advance_to` stores what the line passed;
  every later plan is asked only for the continuation. Nothing checks the past
  afterwards because nothing can rewrite it.
* A CHAIN OF LINKS IS ONE PLAN. `settle` carries the walk's own state from link
  to link rather than reconstructing it from the drawn timeline, so settling six
  one-hour links gives the same schedule, placement for placement, as planning
  six hours at once (`check_resume_contract`). Re-planning from a MOVED line is a
  different question -- the line drags a dynamic period, so the environment
  itself has changed, and the answer is expected to differ ahead of the line.

What it does not claim: the rules at the line are fitted over a six-hour
lookahead (`Scheduler.LOOKAHEAD`), so beyond that the answer is the chain's,
which is still being settled; and `Scheduler.rules` returns a single-position
regime rather than a range when a breakpoint is too close to fit around.

Units: one unit of time is a MINUTE, carried as a `Fraction` so that a
20-second period and a 45-minute minimum stay exact against each other.

Run it:
    uv run scheduler.py --check      every README clause, asserted
    uv run scheduler.py --demo       a small timeline, drawn as text
    uv run scheduler.py --rules      the rule list at a position of t_p
"""

from __future__ import annotations

import argparse
import bisect
import itertools
import math
import time
from collections.abc import Iterable
from dataclasses import dataclass, replace
from fractions import Fraction
from typing import Literal, overload

# --------------------------------------------------------------------------- #
# units, helpers
# --------------------------------------------------------------------------- #

SECOND = Fraction(1, 60)
MINUTE = Fraction(1)
HOUR = Fraction(60)
DAY = Fraction(1440)

IDLE = "IDLE"

KIND_NO_TASK = "no task allowed"
KIND_NO_SCREEN = "no on-screen task"

#: The resilience map of an ON-SCREEN task: the README has no mark on the task
#: any more, so "on screen" is not a flag -- it is exactly a resilience of 0 to
#: the kind "no on-screen task", read like every other resilience.
ON_SCREEN = {KIND_NO_SCREEN: Fraction(0)}

EPS = Fraction(1, 10 ** 6)


def frac(x) -> Fraction:
    if isinstance(x, Fraction):
        return x
    if isinstance(x, float):
        return Fraction(x).limit_denominator(10 ** 9)
    return Fraction(x)


def human(t) -> str:
    """A duration or an instant, in the README's own vocabulary."""
    total = float(frac(t)) * 60.0
    sign, total = ("-", -total) if total < 0 else ("", total)
    h = int(total // 3600)
    rest = total - 3600 * h
    m = int(rest // 60)
    s = rest - 60 * m
    out = []
    if h:
        out.append(f"{h}h")
    if m:
        out.append(f"{m}min")
    if s > 1e-9 or not out:
        out.append(f"{s:.6g}s")
    return sign + " ".join(out)


def merge_spans(spans):
    out = []
    for a, b in sorted(spans):
        if out and a <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], b))
        else:
            out.append((a, b))
    return out


# --------------------------------------------------------------------------- #
# model
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class TaskSpec:
    """A task in one rule state.

    `resilience` is the multiplier applied to the priority percentage inside a
    restrictive period of a given kind: 0 forbids the task there, 1 leaves it
    untouched, and anything between scales its share for as long as the period
    lasts. There is NO other way a task relates to a period -- "on screen" is
    a resilience of 0 to the kind "no on-screen task" (`ON_SCREEN`), and the
    rest of the file asks for it through `resilience_for` like any other kind.
    """
    name: str
    priority: Fraction
    min_time: Fraction
    resilience: tuple = ()          # ((kind, value), ...)
    color: str = "#DDDDDD"

    def resilience_for(self, kind) -> Fraction:
        for k, value in self.resilience:
            if k == kind:
                return value
        if kind == KIND_NO_TASK:
            return Fraction(0)      # the kind that, by its name, accepts nobody
        return Fraction(1)          # a kind this task was never told about


def task(name, priority, min_time, resilience=None, color="#DDDDDD") -> TaskSpec:
    items = tuple(sorted((str(k), frac(v)) for k, v in (resilience or {}).items()))
    return TaskSpec(str(name), frac(priority), frac(min_time), items, color)


@dataclass(frozen=True)
class Period:
    """A restrictive period.

    `open_start` / `closed_end` exist for one reason: the README's dragged
    20-second period is the half-open interval (t_p, t_p + 20s], so the instant
    t_p itself must NOT be covered while every instant after it is. Every other
    period is the ordinary [start, end).
    """
    start: Fraction
    end: Fraction
    kind: str = KIND_NO_TASK
    label: str = ""
    open_start: bool = False
    closed_end: bool = False

    @property
    def duration(self) -> Fraction:
        return self.end - self.start

    def covers(self, t) -> bool:
        after = (self.start < t) if self.open_start else (self.start <= t)
        before = (t <= self.end) if self.closed_end else (t < self.end)
        return after and before


def period(start, end, kind=KIND_NO_TASK, label="", open_start=False, closed_end=False) -> Period:
    return Period(frac(start), frac(end), kind, label, open_start, closed_end)


@dataclass(frozen=True)
class Block:
    """A pre-placed task on the starting timeline."""
    task: str
    start: Fraction
    end: Fraction

    def covers(self, t) -> bool:
        return self.start <= t < self.end


def block(name, start, duration) -> Block:
    return Block(str(name), frac(start), frac(start) + frac(duration))


@dataclass(frozen=True)
class Placement:
    """One drawn stretch of the answer.

    `alt` is the README's *alternative schedule*: the task to run from here if
    `task` turns out not to be runnable after all.
    """
    task: str
    start: Fraction
    end: Fraction
    alt: str = ""

    @property
    def duration(self) -> Fraction:
        return self.end - self.start


# What a paused walk carries from link to link: the virtual clocks, the task
# placed last, and the chunk still in progress (task, what is left of it, and
# the candidate set it was decided against).
WalkState = tuple[
    dict[str, Fraction],
    str | None,
    tuple[str, Fraction, frozenset[str]] | None,
]


@dataclass(frozen=True)
class RuleState:
    """The README's rule state: tasks + percentages + minimums, pinned at `at`."""
    at: Fraction
    tasks: tuple


def state(at, tasks) -> RuleState:
    return RuleState(frac(at), tuple(tasks))


class RuleStates:
    """Keyframes, and the even transformation between two consecutive ones.

    One state stands forever. Between two, every number travels linearly, and
    outside the outermost pair the nearer state is HELD -- extrapolating a
    percentage past the state it was pinned at takes it negative, and a share
    of a hundred cannot go there. A task missing from a keyframe is 0% there
    (the identity is what carries across, exactly as in the app's dated task
    trees), and its minimum is taken from the side that has it.
    """

    def __init__(self, states: Iterable[RuleState]):
        st = sorted(states, key=lambda s: s.at)
        if not st:
            raise ValueError("at least one rule state is required")
        self.states = st

    @property
    def single(self) -> bool:
        return len(self.states) == 1

    def at(self, t) -> tuple:
        t = frac(t)
        if len(self.states) == 1 or t <= self.states[0].at:
            return self.states[0].tasks
        if t >= self.states[-1].at:
            return self.states[-1].tasks
        i = max(i for i, s in enumerate(self.states) if s.at <= t)
        a, b = self.states[i], self.states[i + 1]
        if b.at == a.at:
            return b.tasks
        w = (t - a.at) / (b.at - a.at)
        left = {x.name: x for x in a.tasks}
        right = {x.name: x for x in b.tasks}
        out = []
        for name in sorted(set(left) | set(right)):
            x, y = left.get(name), right.get(name)
            ref = x or y
            assert ref is not None          # `name` came from one side or the other
            px = x.priority if x else Fraction(0)
            py = y.priority if y else Fraction(0)
            mx = x.min_time if x else ref.min_time
            my = y.min_time if y else ref.min_time
            out.append(replace(ref,
                               priority=px + (py - px) * w,
                               min_time=mx + (my - mx) * w))
        return tuple(out)


class Environment:
    """The starting timeline: pre-placed tasks and restrictive periods.

    Everything the walk needs to know about an instant is here, and it is
    always read at the MIDPOINT of a segment rather than at its left edge --
    which is what makes an open-started period (the dragged 20s) mean what it
    says.
    """

    def __init__(self, periods=(), blocks=()):
        self.periods = tuple(periods)
        self.blocks = tuple(blocks)
        bounds = set()
        for p in self.periods:
            bounds.add(p.start)
            bounds.add(p.end)
        for b in self.blocks:
            bounds.add(b.start)
            bounds.add(b.end)
        self.bounds = sorted(bounds)
        self._kinds = None

    def with_periods(self, more) -> Environment:
        return Environment(self.periods + tuple(more), self.blocks)

    def next_bound(self, t, cap):
        i = bisect.bisect_right(self.bounds, t)
        return min(self.bounds[i], cap) if i < len(self.bounds) else cap

    def segments(self, lo, hi):
        """[lo, hi) cut at every edge of the environment."""
        cur, hi = frac(lo), frac(hi)
        while cur < hi:
            nxt = self.next_bound(cur, hi)
            if nxt <= cur:
                nxt = hi
            yield cur, nxt
            cur = nxt

    def block_at(self, t):
        for b in self.blocks:
            if b.covers(t):
                return b
        return None

    def _kind_index(self):
        """Which kinds cover each segment between two edges.

        Asking every period about every instant is quadratic, and three days of
        20-second breaks run the period list into the hundreds -- so the sweep
        is done once, and a query is a binary search. Only MIDPOINT queries go
        through it; the exact-instant questions (`no_screen_at`) still ask the
        periods themselves, since that is where open and closed ends live.
        """
        if self._kinds is None:
            self._kinds = [tuple(p.kind for p in self.periods if p.covers((a + b) / 2))
                           for a, b in zip(self.bounds, self.bounds[1:])]
        return self._kinds

    def kinds_at(self, t):
        idx = self._kind_index()
        if not idx:
            return ()
        i = bisect.bisect_right(self.bounds, t) - 1
        return idx[i] if 0 <= i < len(idx) else ()

    def multiplier(self, spec: TaskSpec, t) -> Fraction:
        """The product of every covering period's resilience -- overlapping
        periods multiply, so the strictest one still forbids."""
        m = Fraction(1)
        for kind in self.kinds_at(t):
            m *= spec.resilience_for(kind)
            if m == 0:
                return Fraction(0)
        return m

    def weights(self, specs, shares, t):
        """name -> its priority share at t, after resilience and pre-placed
        blocks. Zero means "may not run here"."""
        blk = self.block_at(t)
        out = {}
        for spec in specs:
            if blk is not None and blk.task != spec.name:
                out[spec.name] = Fraction(0)
                continue
            out[spec.name] = shares[spec.name] * self.multiplier(spec, t)
        return out

    def no_screen_at(self, t) -> bool:
        """Is the instant COVERED BY the README's period "no on-screen task"?

        The two modes of t_p and the recurrence bars are both written in terms
        of that one phrase, so it is answered in one place. A period of the
        kind itself covers it; so does "no task allowed", which turns away the
        on-screen tasks a fortiori -- which is why the three dynamic periods,
        whose kind is "no task allowed", are the ones the modes govern.

        Asked at an exact instant rather than at a midpoint, because it is the
        question the dragged 20 seconds -- the half-open (t_p, t_p + 20s] --
        exists to answer.
        """
        for p in self.periods:
            if p.covers(t) and p.kind in (KIND_NO_TASK, KIND_NO_SCREEN):
                return True
        return False


# --------------------------------------------------------------------------- #
# the walk -- one rule state, one environment, one pass over the timeline
# --------------------------------------------------------------------------- #

class Walk:
    """The scheduler proper: a virtual-clock walk over the timeline.

    The two optimisation criteria of the README are answered by two different
    quantities, and keeping them apart is the whole design:

    * WHO runs next is the priority criterion. Each task carries a clock
      `v = served / p`, so the task furthest behind its share has the largest
      lag -- but a lag is a real time and a slot is a whole minimum, so the
      comparison is the lag counted in the task's OWN slots,
      `claim = (V - v) * p / m` (`_claims`). Read as a raw clock instead, a
      task at 50% against twenty at 2.5% is outranked by all twenty after a
      single slot and never catches up.
    * HOW LONG it runs is the granularity criterion. A share is a ratio and a
      ratio is satisfied at any scale, so the scale has to be named: it is one
      ROUND against the rival that would run next, `c = p*m_rival/(1 - p)`
      (`_round`), floored at the task's own minimum. That is what turns
      "A 1h, B 1h" into the README's "A 10min, B 10min".

    Compensation is the third quantity: an exclusion earns the tasks it turned
    away a boost that decays exponentially with distance from it, capped, so
    a deprived task comes back denser just before and just after the blockage
    without a disruptive catch-up (`_build_field`, `_boost`).
    """

    MAX_STEPS = 200_000

    def __init__(self, specs, tau=None, tau_scale=1, max_boost=6,
                 field_floor=Fraction(1, 10), max_reach=None):
        specs = [s for s in specs if s.priority > 0]
        if not specs:
            raise ValueError("a rule state with no positive priority schedules nothing")
        total = sum(s.priority for s in specs)
        self.specs = specs
        self.p = {s.name: s.priority / total for s in specs}
        self.minimum = {s.name: s.min_time for s in specs}
        self.min_period = max(self.minimum[n] / self.p[n] for n in self.p)
        self.tau = (frac(tau) if tau is not None else self.min_period) * frac(tau_scale)
        self.max_boost = frac(max_boost)
        self.field_floor = frac(field_floor)
        self.max_reach = frac(max_reach) if max_reach is not None else 6 * self.tau
        self.max_amp = float(self.field_floor) * (
            math.exp(float(self.max_reach) / float(self.tau)) - 1.0)

    # -- the three quantities ------------------------------------------------

    def _claims(self, v, names, p):
        """What each task is owed, counted in its own slots."""
        names = list(names)
        weight = sum(p[n] for n in names)
        if not weight:
            return {n: Fraction(0) for n in names}
        mean = sum(p[n] * v[n] for n in names) / weight
        return {n: (mean - v[n]) * p[n] / self.minimum[n] for n in names}

    def _pick(self, v, candidates, last, p):
        """The most-owed candidate, never the same task twice in a row unless
        it is the only one there is."""
        pool = [n for n in candidates if n != last] or list(candidates)
        claim = self._claims(v, candidates, p)
        return min(pool, key=lambda n: (-claim[n], -p[n], n))

    def _alternative(self, v, candidates, chosen, p):
        """The README's alternative schedule: who runs from here instead, if
        the pick turns out not to be runnable. Empty where there is nobody
        else -- an answer of "the same task again" would be no answer."""
        pool = [n for n in candidates if n != chosen]
        if not pool:
            return ""
        claim = self._claims(v, candidates, p)
        return min(pool, key=lambda n: (-claim[n], -p[n], n))

    def _round(self, name, rival, p):
        """The largest chunk that still leaves `rival` its due inside one round
        in which each of them runs once: c/(c + m_rival) = p."""
        if not rival:
            return None
        share = p[name]
        if share >= 1:
            return None
        return share * self.minimum[rival] / (1 - share)

    def _chunk(self, name, v, candidates, p, boost):
        floor = self.minimum[name]
        others = [n for n in candidates if n != name]
        target = min((v[n] for n in others), default=v[name])
        need = p[name] * (target - v[name])
        c = max(floor, need)
        # The field LIFTS and the round CAPS, and the two are asked separately:
        # a compensation is owed whether or not anybody is there to race, while
        # a round is measured against a rival and is meaningless without one.
        c = max(c, floor * boost)
        claim = self._claims(v, candidates, p)
        rival = min(others, key=lambda n: (-claim[n], -p[n], n), default=None)
        unit = self._round(name, rival, p)
        if unit is not None:
            c = min(c, max(max(floor, unit) * boost, floor))
        return c

    def _relax(self, v, dt, active):
        """Forget an imbalance older than one period.

        Without this, a long exclusion buys an equally long catch-up on the far
        side of it -- the README asks for compensation that DECAYS, not for a
        debt repaid in full. The served pool decays back towards its own floor,
        and the tasks that were not served are held within one period of it by
        TRANSLATING them as a group: clamping each of them separately would set
        every deprived task to the same clock and destroy the ranking between
        them, which is exactly what their priorities are for.
        """
        if not active or dt <= 0:
            return
        T = self.min_period
        lo = min(v[n] for n in active)
        f = frac(math.exp(-float(dt) / float(self.tau)))
        for n in active:
            over = v[n] - lo - T
            if over > 0:
                v[n] -= over * (1 - f)
        idle = [n for n in v if n not in active]
        if not idle:
            return
        low = (lo - T) - min(v[n] for n in idle)      # what the credit cap asks
        high = (lo + T) - max(v[n] for n in idle)     # what the debt cap allows
        shift = max(low, min(high, Fraction(0)))
        if low > high:                                # wider than the band: the
            shift = low                               # credit cap is the one
        if shift:                                     # that has to hold
            for n in idle:
                v[n] += shift

    # -- compensation field --------------------------------------------------

    def _build_field(self, env, lo, hi):
        """Per task, the stretches it was turned away from -- with how much of
        it it was turned away from, since a resilience of 0.4 deprives a task
        of 60% of its share and not of all of it.

        A stretch that refuses EVERYBODY creates no field: nobody is deprived
        relative to anybody there, and the clocks already repay a plain delay.
        Neither does one shorter than the deprived task's own minimum -- it
        could not have run in it anyway.
        """
        tau = float(self.tau)
        raw = {n: [] for n in self.p}
        for a, b in env.segments(lo, hi):
            mid = (a + b) / 2
            w = env.weights(self.specs, self.p, mid)
            if not any(x > 0 for x in w.values()):
                continue
            for n in self.p:
                mult = w[n] / self.p[n]
                if mult < 1:
                    raw[n].append((a, b, Fraction(1) - mult))
        field = {}
        for n, pieces in raw.items():
            spans = []
            for a, b, weight in pieces:
                if spans and spans[-1][1] == a:
                    spans[-1][1] = b
                    spans[-1][2] += weight * (b - a)
                else:
                    spans.append([a, b, weight * (b - a)])
            entries = []
            for a, b, cost in spans:
                if b - a < self.minimum[n]:
                    continue
                entries.append((a, b, min(float(cost) / tau, self.max_amp)))
            if entries:
                field[n] = entries
        return field

    def _boost(self, field, name, t) -> Fraction:
        spans = field.get(name)
        if not spans:
            return Fraction(1)
        tau = float(self.tau)
        acc = 0.0
        for a, b, amp in spans:
            if a <= t <= b:
                # inside its own exclusion a task is being deprived, not repaid:
                # a boost here would hand straight back what the period took,
                # which is the overcompensation the README rules out
                continue
            d = float(a - t) if t < a else float(t - b)
            acc += amp * math.exp(-d / tau)
        if acc <= 0.0:
            return Fraction(1)
        return frac(1.0 + min(acc, float(self.max_boost) - 1.0))

    # -- clocks seeded from what already happened ---------------------------

    def _seed(self, history, env=None):
        """The clocks a re-plan resumes on.

        A chain of re-plans has to be the same schedule as one long plan, so
        the clocks cannot simply be zeroed at t_p -- and they cannot be the raw
        totals either, since `_relax` has been forgetting all along. So the
        recent past is replayed placement by placement, relaxing where the walk
        relaxes, over a window of two periods of SCHEDULABLE time (idling does
        not age an imbalance, it only postpones it).
        """
        v = {n: Fraction(0) for n in self.p}
        if not history:
            return v, None, None
        window = 2 * self.min_period
        tail, served = [], Fraction(0)
        for pl in sorted(history, key=lambda p: p.start, reverse=True):
            tail.append(pl)
            if pl.task in self.p:
                served += pl.duration
                if served >= window:
                    break
        last = None
        for pl in reversed(tail):
            if pl.task not in self.p:
                continue
            mid = (pl.start + pl.end) / 2
            if env is None:
                weight, active = self.p[pl.task], list(self.p)
            else:
                w = env.weights(self.specs, self.p, mid)
                weight = w[pl.task] or self.p[pl.task]
                active = [n for n in w if w[n] > 0] or list(self.p)
            v[pl.task] += pl.duration / weight
            # relaxed against the set that was ACTUALLY racing then, not
            # against everybody: an excluded group is translated, and replaying
            # that wrong is what makes a resumed plan drift from the one long
            # plan it is supposed to be a continuation of
            self._relax(v, pl.duration, active)
            last = pl.task
        # The run still IN PROGRESS at the seam, and how much of it has been
        # served. A re-plan that dropped it would give a task less than its
        # minimum every time the line stopped inside a run -- and a chain of
        # re-plans has to be the same schedule as one long plan.
        head, served = None, Fraction(0)
        for pl in sorted(history, key=lambda p: p.start, reverse=True):
            if pl.task not in self.p:
                continue                      # idling suspends a run, it does
            if head is None:                  # not end it
                head = pl.task
            elif pl.task != head:
                break
            served += pl.duration
        return v, last, (head, served) if head else None

    # -- the pass ------------------------------------------------------------

    @staticmethod
    def _emit(out, name, start, end, alt):
        if end <= start:
            return
        if out and out[-1].task == name and out[-1].end == start and out[-1].alt == alt:
            out[-1] = Placement(name, out[-1].start, end, alt)
        else:
            out.append(Placement(name, start, end, alt))

    @overload
    def run(self, env: Environment, start, end, history=..., resume=..., field_span=...,
            with_state: Literal[False] = ...) -> list[Placement]: ...

    @overload
    def run(self, env: Environment, start, end, history=..., resume=..., field_span=...,
            *, with_state: Literal[True]) -> tuple[list[Placement], WalkState]: ...

    def run(self, env: Environment, start, end, history=(), resume=None, field_span=None,
            with_state=False) -> list[Placement] | tuple[list[Placement], WalkState]:
        """Walk [start, end) once and return what is placed there.

        Two rules of the README are structural here rather than checked
        afterwards:

        * NO IDLING -- wherever the candidate set is non-empty somebody is
          placed, always;
        * an interval that accepts NOBODY suspends the run in progress instead
          of ending it (`pending`), so a 20-second look-away every twenty
          minutes does not make a 45-minute minimum unschedulable.
        """
        start, end = frac(start), frac(end)
        if resume is not None:
            # A link of a progressive chain is not a new plan: it is the SAME
            # walk, paused and resumed, so it carries the clocks and the chunk
            # in progress rather than reconstructing them from the drawn past.
            # That is what makes a chain of links identical to one long plan
            # instead of merely close to it.
            v, last, head = dict(resume[0]), resume[1], None
            pending_in = resume[2]
        else:
            v, last, head = self._seed(history, env)
            pending_in = None
        # The field is built over a WIDER window than the pass: a blockage just
        # outside the stretch being planned still compensates inside it, and a
        # link of a progressive chain must not have its compensation cut off at
        # its own edge -- that would make where the links happen to fall visible
        # in the schedule.
        #
        # `field_span` is what makes that true rather than nearly true. A link
        # is asked for four hours, but it is a slice of a walk that runs from
        # the commit point to the HORIZON, and the obstacles it must compensate
        # for are the ones THAT walk can see -- not the ones inside its own
        # slice. Reading the window off the slice truncated the field at both
        # ends: the first link could not see an exclusion whose far edge lay
        # beyond it, and the last link could not see the one it had already
        # passed, whose tail still decays into it. Either way the chain parted
        # company with the single plan it is supposed to BE (measured on a
        # 300-minute ban: a boundary nearly four minutes out of place).
        near, far = (start, end) if field_span is None else field_span
        near, far = min(frac(near), start), max(frac(far), end)
        field = self._build_field(env, near - self.max_reach, far + self.max_reach)
        out = []
        cursor = start
        pending = pending_in                 # (task, what is left of its chunk)
        if head is not None and head[1] < self.minimum[head[0]]:
            pending = (head[0], self.minimum[head[0]] - head[1], frozenset(self.p))
        steps = 0
        while cursor < end:
            steps += 1
            if steps > self.MAX_STEPS:
                raise RuntimeError("walk did not terminate -- degenerate environment?")
            nxt = env.next_bound(cursor, end)
            if nxt <= cursor:
                nxt = end
            mid = (cursor + nxt) / 2
            blk = env.block_at(mid)
            if blk is not None and blk.task not in self.p:
                # a pre-placed block owned by nobody schedulable: it suspends,
                # exactly as an all-refusing period does
                self._emit(out, blk.task, cursor, nxt, "")
                cursor = nxt
                continue
            w = env.weights(self.specs, self.p, mid)
            cand = [n for n in w if w[n] > 0]
            if not cand:
                self._emit(out, IDLE, cursor, nxt, "")
                cursor = nxt
                continue
            total = sum(w[n] for n in cand)
            p_local = {n: w[n] / total for n in cand}
            if (pending is not None and pending[0] in cand and pending[1] > 0
                    and not set(cand) > pending[2]):
                # The run in progress continues -- but only while the field it
                # was decided against has not WIDENED. A task that started
                # inside a period only it was allowed in has no claim on the
                # time after the rivals come back: carrying its minimum out
                # past the window would not merely use the period, it would
                # lengthen everybody else's ban (measured in test 12: the ten
                # privileged tasks took 21 of 32 hours that way, and the ten
                # ordinary ones 23 minutes). A set that SHRANK, or one that
                # came back the same after an interval that accepted nobody,
                # is the atomic block and does resume.
                name, left = pending[0], pending[1]
            else:
                name = self._pick(v, cand, last, p_local)
                left = self._chunk(name, v, cand, p_local,
                                   self._boost(field, name, cursor))
            alt = self._alternative(v, cand, name, p_local)
            stop = min(cursor + left, nxt)
            self._emit(out, name, cursor, stop, alt)
            served = stop - cursor
            # Charged against the task's EFFECTIVE weight, not its nominal one:
            # inside a period it is only half-resilient to, an hour of service
            # costs it twice the clock, which is what makes the multiplier mean
            # "half the percentage for as long as the period lasts" rather than
            # "the same alternation, one boundary later". Where no period is in
            # force the two are the same number.
            v[name] += served / w[name]
            self._relax(v, served, cand)
            last = name
            left -= served
            pending = (name, left, frozenset(cand)) if left > EPS else None
            cursor = stop
        return (out, (v, last, pending)) if with_state else out


# --------------------------------------------------------------------------- #
# the three dynamic restrictive periods
# --------------------------------------------------------------------------- #

BAR_20S_AFTER_ANY = Fraction(20)          # after ANY dynamic period
BAR_5MIN_AFTER_STRETCH = Fraction(60)     # after a >= 5min stretch
BAR_20S_AFTER_LONG = Fraction(20)         # after a >= 15min stretch
BAR_15MIN_AFTER_LONG = Fraction(120)      # after a >= 15min stretch
STRETCH_SHORT = Fraction(5)
STRETCH_LONG = Fraction(15)


@dataclass(frozen=True)
class DynamicSpec:
    """One of the three. `segments` lets a period have a SHAPE -- a minute of
    "no task allowed" followed by four minutes of "no on-screen task", say --
    while the README's plain form is one segment of "no task allowed" over the
    whole duration."""
    label: str
    duration: Fraction
    segments: tuple = ()      # ((offset, length, kind), ...)

    def shape(self):
        return self.segments or ((Fraction(0), self.duration, KIND_NO_TASK),)


DEFAULT_DYNAMICS = (
    DynamicSpec("20s", 20 * SECOND),
    DynamicSpec("5min", Fraction(5)),
    DynamicSpec("15min", Fraction(15)),
)


@dataclass(frozen=True)
class Instance:
    spec: DynamicSpec
    start: Fraction
    open_start: bool = False

    @property
    def label(self):
        return self.spec.label

    @property
    def duration(self):
        return self.spec.duration

    @property
    def end(self):
        return self.start + self.spec.duration

    def to_periods(self):
        out = []
        for offset, length, kind in self.spec.shape():
            a = self.start + offset
            out.append(Period(a, a + length, kind, self.label,
                              open_start=self.open_start and offset == 0,
                              closed_end=self.open_start and offset + length == self.duration))
        return out

    def stretch_run(self, counts):
        """The longest contiguous part of this period that COUNTS as one of the
        recurrence rules' stretches.

        `counts(kind)` says which kinds do: under the README, a kind that
        covers the instant with "no on-screen task" AND leaves nobody able to
        run -- anywhere a task is still allowed the no-idling rule puts one
        there, so it is not a stretch "without any task".
        """
        best = Fraction(0)
        run_start = None
        run_end = None
        cur = Fraction(0)
        start = None
        for offset, length, kind in self.spec.shape():
            if counts(kind):
                if start is None:
                    start = offset
                cur += length
                if cur > best:
                    best, run_start, run_end = cur, start, offset + length
            else:
                cur, start = Fraction(0), None
        if run_start is None or run_end is None:
            return None
        return self.start + run_start, self.start + run_end


class DynamicPlanner:
    """Where the 20s, the 5min and the 15min periods go.

    They are placed as early as the README's bars allow, in chronological
    order, each placement barring what it has to bar:

    * after ANY dynamic period, no 20s for 20 minutes;
    * after a >= 5-minute stretch covered by "no on-screen task" without any
      task, no 5min for an hour;
    * after a >= 15-minute one, no 20s for 20 minutes and no 15min for two
      hours.

    A REST STRETCH is the README's phrase read literally, and it takes all
    three of its clauses (`_is_rest_at`):

    * COVERED BY "no on-screen task" -- a period of that kind, or "no task
      allowed", which refuses the on-screen tasks a fortiori. An emptiness of
      some other kind ("no B, C", a period nobody happens to be resilient to)
      is not one: the README names the kind, and only that kind rests eyes.
    * WITHOUT ANY TASK -- so a period that still accepts somebody makes none
      at all (no idling puts a task there), and neither does a pre-placed
      block: a pre-placed task IS a task, even one owned by nobody
      schedulable.
    * and it is a STRETCH, not a period: two periods that abut, or a dynamic
      period landing against a pre-placed one, make ONE (`_stretch`).

    `blocked` and `rested` are deliberately two different sets. Everywhere
    nothing can be placed, a dynamic period is pointless and is pushed past
    (`blocked`); only the part of that which is a rest stretch bars what comes
    after it (`rested`). Counting a pre-placed hour of MAINTENANCE as a rest
    was the one bug the README's new wording exposes: the user was at the
    screen the whole time.

    The timeline is taken to START rested: the first 20s may fall one bar
    after the timeline's own start, the first 5min one hour after it, the
    first 15min two hours after it. Placing all three at the start instead
    would be legal and useless -- the chain merge would collapse them into a
    single 15-minute period at the origin.
    """

    def __init__(self, base_env: Environment, specs, dynamics=DEFAULT_DYNAMICS,
                 t_start: Fraction | int = 0, horizon: Fraction | int = DAY):
        self.base_env = base_env
        self.specs = tuple(specs)
        self.dynamics = {d.label: d for d in dynamics}
        self.t_start = frac(t_start)
        self.horizon = frac(horizon)
        self.cadence = {
            "20s": BAR_20S_AFTER_ANY,
            "5min": BAR_5MIN_AFTER_STRETCH,
            "15min": BAR_15MIN_AFTER_LONG,
        }
        self.shares = {s.name: s.priority for s in specs}
        blocked, rested = [], []
        for a, b in base_env.segments(self.t_start, self.horizon):
            mid = (a + b) / 2
            if not self._is_empty(base_env.weights(specs, self.shares, mid)):
                continue
            blocked.append((a, b))
            if self._is_rest_at(base_env, mid):
                rested.append((a, b))
        self.blocked = merge_spans(blocked)
        self.rested = merge_spans(rested)

    @staticmethod
    def _is_empty(weights) -> bool:
        """The README's "without any task" clause: nobody may run here, so
        the no-idling rule puts nobody here either. Asked of a set of weights, so the same answer
        serves a segment of the standing environment and a segment of a
        dynamic period's shape."""
        return not any(v > 0 for v in weights.values())

    def _is_rest_at(self, env: Environment, t) -> bool:
        """The README's stretch, at one instant: covered by "no on-screen
        task", and no task there -- a pre-placed block included, since a
        pre-placed task is a task."""
        return (env.block_at(t) is None
                and env.no_screen_at(t)
                and self._is_empty(env.weights(self.specs, self.shares, t)))

    def kind_counts(self, kind) -> bool:
        """Does a stretch of this KIND count? -- the same two clauses, asked of
        one kind on its own: does it cover the instant with "no on-screen
        task", and does it leave anybody able to run?"""
        return (kind in (KIND_NO_TASK, KIND_NO_SCREEN)
                and self._is_empty({s.name: s.priority * s.resilience_for(kind)
                                    for s in self.specs}))

    # -- bars ----------------------------------------------------------------

    def _stretch(self, a, b):
        """Grow [a, b) through whatever pre-placed REST it touches -- an
        abutting night makes one long stretch with it, an abutting pre-placed
        block does not."""
        changed = True
        while changed:
            changed = False
            for x, y in self.rested:
                if x <= a <= y and x < a:
                    a, changed = x, True
                if x <= b <= y and y > b:
                    b, changed = y, True
        return a, b

    def _bar_stretch(self, bars, a, b):
        length = b - a
        if length >= STRETCH_SHORT:
            bars["5min"] = max(bars["5min"], b + BAR_5MIN_AFTER_STRETCH)
        if length >= STRETCH_LONG:
            bars["20s"] = max(bars["20s"], b + BAR_20S_AFTER_LONG)
            bars["15min"] = max(bars["15min"], b + BAR_15MIN_AFTER_LONG)

    def _bar_instance(self, bars, inst: Instance):
        bars["20s"] = max(bars["20s"], inst.end + BAR_20S_AFTER_ANY)
        bars[inst.label] = max(bars[inst.label], inst.end + self.cadence[inst.label])
        run = inst.stretch_run(self.kind_counts)
        if run is not None:
            self._bar_stretch(bars, *self._stretch(*run))

    # -- placement -----------------------------------------------------------

    def instances(self, t_p, mode=1):
        """Where the three periods fall, for this position of the line.

        THE LINE MOVES CONTINUOUSLY, so every t <= t_p is a position the line
        has already held: there is no floor on the sweep and no route to
        remember. Mode 1 DRAGS -- a period the line reached is pushed ahead of
        it and goes on being pushed, so it never happens at all -- and since
        the line has stood everywhere below itself, EVERY slot the bars put
        below t_p is one it reached. The drag re-anchors the bar at the line,
        so at most one occurrence per bar is ever swept and the chain merge
        collapses what piled up; the answer is a function of the position
        alone, never of how the line got there.
        """
        t_p = frac(t_p)
        if not self.dynamics:
            return []
        labels = sorted(self.dynamics, key=lambda l: -self.dynamics[l].duration)
        bars = {l: self.t_start + self.cadence[l] for l in labels}
        out = []
        guard = 0
        while True:
            guard += 1
            if guard > 100_000:
                raise RuntimeError("dynamic placement did not terminate")
            label = min(labels, key=lambda l: (bars[l], -self.dynamics[l].duration))
            spec = self.dynamics[label]
            start = bars[label]
            if start >= self.horizon:
                break
            # A rest stretch bars what comes AFTER it, and any emptiness at
            # all absorbs what would fall inside it -- there is nothing for a
            # break to interrupt where nothing is placed. Both are applied in
            # chronological order: a night on the third day cannot delay a
            # break on the first. (Applying every stretch up front is what
            # pushed a whole day's periods past the last night of the case.)
            moved = False
            for a, b in self.rested:
                if b <= start or a <= start < b:
                    before = dict(bars)
                    self._bar_stretch(bars, a, b)
                    if bars != before:
                        moved = True
            for a, b in self.blocked:
                if a <= start < b and bars[label] < b:
                    bars[label] = b
                    moved = True
            if moved:
                continue
            open_start = False
            # Mode 1: t_p may not be covered by "no on-screen task". A period
            # the line has swept up to is therefore pushed ahead of it, and
            # becomes the half-open (t_p, t_p + duration] -- the line goes on
            # delaying it, placing tasks where it stood.
            if mode == 1 and start <= t_p:
                start, open_start = t_p, True
            inst = Instance(spec, start, open_start)
            out.append(inst)
            self._bar_instance(bars, inst)
        return self._merge_chain(out)

    @staticmethod
    def _merge_chain(instances):
        """The README's chain rule: where the bars have made dynamic periods
        overlap (only the t_p drag can), the whole chain is replaced by its
        LONGEST member, starting at the chain's earliest point.

        Touching counts as chaining -- that is the README's own example: a 20s
        dragged until its end meets a 5min is absorbed, and the 5min teleports
        20 seconds backward, keeping the line outside it.
        """
        out = []
        for inst in sorted(instances, key=lambda i: (i.start, -i.duration)):
            if out and inst.start <= out[-1].end:
                prev = out[-1]
                longest = prev if prev.duration >= inst.duration else inst
                out[-1] = Instance(longest.spec, prev.start, prev.open_start)
            else:
                out.append(inst)
        return out

    def periods(self, t_p, mode=1):
        t_p = frac(t_p)
        inst = self.instances(t_p, mode)
        out = [p for i in inst for p in i.to_periods()]
        if mode == 2:
            env = self.base_env.with_periods(out)
            if not env.no_screen_at(t_p):
                # Mode 2 wants t_p COVERED BY "no on-screen task". The period
                # that just ended is the one the line came out of, so it is
                # extended to reach t_p -- as "no on-screen task" rather than
                # "no task allowed", which is what the README's own example
                # asks for: the gap is filled with the tasks that have a
                # non-zero resilience to that kind, and so does not idle.
                ends = [p.end for p in list(out) + list(self.base_env.periods)
                        if p.kind in (KIND_NO_TASK, KIND_NO_SCREEN) and p.end <= t_p]
                start = max(ends) if ends else t_p
                out.append(Period(start, t_p, KIND_NO_SCREEN, "mode2", closed_end=True))
        return out


# --------------------------------------------------------------------------- #
# the rules the scheduler returns
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class RuleSegment:
    """One drawn stretch, with both of its bounds affine in t_p."""
    task: str
    alt: str
    start0: Fraction
    start_slope: Fraction
    end0: Fraction
    end_slope: Fraction

    def at(self, d):
        return self.start0 + self.start_slope * d, self.end0 + self.end_slope * d

    def line(self):
        def term(a, b):
            if b == 0:
                return human(a)
            sign = "+" if b > 0 else "-"
            return f"{human(a)} {sign} {abs(float(b)):g}*(t_p - lo)"
        alt = f"   (else {self.alt})" if self.alt else ""
        return f"  {self.task:<10} {term(self.start0, self.start_slope)}  ->  " \
               f"{term(self.end0, self.end_slope)}{alt}"


@dataclass(frozen=True)
class Regime:
    """The rules over a RANGE of positions of t_p.

    Reading the schedule at a position inside the range is arithmetic, not
    scheduling: every boundary is `a + b*(t_p - lo)`. That is what the README
    asks for -- rules parameterised by t_p, not a re-run per position -- and
    it is what lets a display follow the line continuously.
    """
    lo: Fraction
    hi: Fraction
    mode: int
    past: tuple
    segments: tuple

    def covers(self, t_p, mode) -> bool:
        return mode == self.mode and self.lo <= frac(t_p) <= self.hi

    def draw(self, t_p) -> list:
        d = frac(t_p) - self.lo
        out = list(self.past)
        for seg in self.segments:
            a, b = seg.at(d)
            if b - a > EPS:
                out.append(Placement(seg.task, a, b, seg.alt))
        return out

    def lines(self) -> list:
        head = (f"t_p in [{human(self.lo)}, {human(self.hi)}]  (mode {self.mode}), "
                f"{len(self.segments)} rules")
        return [head] + [s.line() for s in self.segments]


def clip(placements, lo, hi) -> list:
    lo, hi = frac(lo), frac(hi)
    out = []
    for pl in placements:
        a, b = max(pl.start, lo), min(pl.end, hi)
        if b - a > EPS:
            out.append(Placement(pl.task, a, b, pl.alt))
    return out


def coalesce(placements) -> list:
    """Join placements that only a seam separated.

    A link boundary of the progressive chain, or the join between the committed
    past and the continuation, cuts a run in two without changing it. The two
    halves are ONE rule, and reading them as two would make where the links
    happened to fall visible in the answer.

    The alternative kept is the one named at the run's START, which is the
    answer a single uninterrupted plan would have given: the alternative is a
    function of the position it is asked at, so the resumed half names a
    slightly fresher one, and keeping that would let a link boundary show
    through in the rules even though the schedule is identical.
    """
    out = []
    for pl in placements:
        if out and out[-1].task == pl.task and out[-1].end == pl.start:
            out[-1] = Placement(pl.task, out[-1].start, pl.end, out[-1].alt)
        else:
            out.append(pl)
    return out


def resulting_shares(placements) -> dict:
    """The display's "resulting share": a task's presence as a percentage of
    the drawn timeline, excluding what no task was allowed in."""
    total = Fraction(0)
    per = {}
    for pl in placements:
        if pl.task == IDLE:
            continue
        per[pl.task] = per.get(pl.task, Fraction(0)) + pl.duration
        total += pl.duration
    if not total:
        return {}
    return {n: d / total for n, d in sorted(per.items())}


# --------------------------------------------------------------------------- #
# the scheduler: frozen past, progressive settling, rules at the line
# --------------------------------------------------------------------------- #

class Scheduler:
    """One instance per test, as `tests.md` asks.

    It owns three things the walk knows nothing about:

    * the FROZEN PAST -- `advance_to` commits everything below the line, and
      nothing ever rewrites it;
    * the PROGRESSIVE front -- `settle` extends the definitive part of the
      schedule a link at a time, so a definitive answer grows out of the line
      far faster than the README's ten minutes per ten seconds;
    * the RULES -- `rules` fits the plan into a range of positions of t_p and
      certifies the fit against the walk itself before returning it.
    """

    LINK = 4 * HOUR                 # how much timeline one settling step adds
    LOOKAHEAD = 6 * HOUR            # how far ahead the rules at the line reach

    def __init__(self, states, env=None, dynamics=DEFAULT_DYNAMICS, t_start=0,
                 horizon=DAY, tau_scale=1, **walk_kw):
        self.states = states if isinstance(states, RuleStates) else RuleStates(states)
        self.base = env or Environment()
        self.dynamics = tuple(dynamics)
        self.t_start = frac(t_start)
        self.horizon = frac(horizon)
        self.tau_scale = tau_scale
        self.walk_kw = walk_kw
        self.mode = 1
        self.t_p = self.t_start
        self.committed = []
        self.commit_point = self.t_start
        self.front = self.t_start
        self._chain = []
        self._chain_key = None
        self._chain_state = None
        self._walks = {}
        self._planners = {}
        self._period_cache = {}

    # -- the pieces a position needs ----------------------------------------

    def specs_at(self, t):
        return self.states.at(t)

    def walk_at(self, t) -> Walk:
        specs = self.specs_at(t)
        w = self._walks.get(specs)
        if w is None:
            w = Walk(specs, tau_scale=self.tau_scale, **self.walk_kw)
            self._walks[specs] = w
        return w

    def planner_at(self, t) -> DynamicPlanner:
        specs = self.specs_at(t)
        p = self._planners.get(specs)
        if p is None:
            p = DynamicPlanner(self.base, specs, self.dynamics,
                               self.t_start, self.horizon)
            self._planners[specs] = p
        return p

    def dynamic_periods(self, t_p, mode):
        key = (frac(t_p), mode)
        out = self._period_cache.get(key)
        if out is None:
            out = tuple(self.planner_at(t_p).periods(t_p, mode))
            if len(self._period_cache) > 4096:
                self._period_cache.clear()
            self._period_cache[key] = out
        return out

    def environment(self, t_p, mode) -> Environment:
        return self.base.with_periods(self.dynamic_periods(t_p, mode))

    # -- drawing -------------------------------------------------------------

    def _future(self, t_p, mode, end=None):
        """What the walk places from the commit point on, for this position."""
        end = self.horizon if end is None else frac(end)
        return self.walk_at(t_p).run(self.environment(t_p, mode),
                                     self.commit_point, end,
                                     history=self.committed)

    def timeline(self, t_p=None, mode=None, upto=None) -> list:
        t_p = self.t_p if t_p is None else frac(t_p)
        mode = self.mode if mode is None else mode
        end = self.horizon if upto is None else frac(upto)
        key = (t_p, mode, self.commit_point)
        if self._chain_key == key and self._chain:
            drawn = list(self._chain)
            if end > self.front:
                drawn += self.walk_at(t_p).run(self.environment(t_p, mode),
                                               self.front, end,
                                               history=self.committed + drawn,
                                               resume=self._chain_state)
        else:
            drawn = self._future(t_p, mode, end)
        return coalesce(clip(self.committed + drawn, self.t_start, end))

    def alternative_at(self, t_p=None, mode=None):
        """The README's alternative schedule AT the line: what to run from
        here if the scheduled task cannot be run now."""
        t_p = self.t_p if t_p is None else frac(t_p)
        mode = self.mode if mode is None else mode
        future = self._future(t_p, mode, min(self.horizon, t_p + self.LOOKAHEAD))
        # The line in mode 1 sits at the very edge of the period it is dragging,
        # so the instant t_p itself is often inside a stretch that accepts
        # nobody. The question ("what do I run instead?") is then about the next
        # thing scheduled, not about the emptiness the line is standing in.
        for pl in future:
            if pl.alt and pl.start <= t_p < pl.end:
                return pl.alt
        for pl in future:
            if pl.task != IDLE and pl.alt and pl.end > t_p:
                return pl.alt
        return ""

    # -- the frozen past -----------------------------------------------------

    def advance_to(self, t_p, mode=None):
        """Move the line to `t_p`, CONTINUOUSLY, committing everything it passes.

        The README's line takes every value below itself on the way, so a
        caller that asks for a distant position is asking for a journey, not a
        landing: the line is walked there a slot at a time, freezing what it
        passes as it passes it. Sampling that motion more coarsely is not the
        same schedule -- in mode 1 the dragged chain rides
        immediately ahead of the line, and a stretch planned once with that
        obstacle parked at the far end is not the stretch the line would have
        written on its way through.

        THE STEP IS ONE MINIMUM EXECUTION TIME, and that is the whole of the
        granularity question: the finest thing the walk can place is one task's
        minimum, so a line that never skips a whole minimum never skips a
        placement it should have entered. Stepping at that bound reproduces a
        four-times-finer sampling exactly, on every case here; stepping at
        twice it does not, on four of the five. Stepping ON the placement edges
        instead is the tempting alternative and it is wrong: it never freezes a
        partial slot, and it is ENTERING a placement -- not landing on its
        edge -- that settles the picks after it.

        An ordinary tick -- a frame, a second -- is far inside the first step
        and costs exactly one commit, so nothing is spent except where a caller
        asks the line to cover ground.
        """
        t_p = frac(t_p)
        mode = self.mode if mode is None else mode
        if t_p < self.commit_point - EPS:
            raise ValueError("t_p only ever moves forward")
        guard = 0
        while True:
            guard += 1
            if guard > 1_000_000:
                raise RuntimeError("the line did not reach its target")
            step = self._sweep_step()
            stop = t_p if step is None else min(t_p, self.t_p + step)
            self._commit_at(stop, mode)
            if stop >= t_p:
                return self.committed

    def _sweep_step(self):
        """The coarsest step the line may take without skipping a slot: the
        smallest minimum execution time the rules hold at the line."""
        mins = [s.min_time for s in self.specs_at(self.t_p) if s.min_time > 0]
        return min(mins) if mins else None

    def _commit_at(self, t_p, mode):
        """One step of the journey: draw at this position and freeze the past.

        The past is frozen BY CONSTRUCTION rather than by a check: what is
        below the line is stored, and every later plan is asked only for the
        continuation from there. This is the line at ONE position; `advance_to`
        is the line MOVING, and it is the only thing that should be called from
        outside -- a caller that steps this one itself is choosing its own
        sampling of a motion that has none.
        """
        old_periods = self.dynamic_periods(self.t_p, self.mode)
        drawn = self.timeline(t_p, mode)
        self.committed = clip(drawn, self.t_start, t_p)
        self.commit_point = t_p
        # Keep the settled continuation where the environment ahead of the line
        # did not move; a dynamic period that has shifted invalidates it.
        new_periods = self.dynamic_periods(t_p, mode)
        ahead = lambda ps: tuple(p for p in ps if p.end > t_p)
        if self._chain and ahead(old_periods) == ahead(new_periods) and self.front > t_p:
            self._chain = [pl for pl in self._chain if pl.end > t_p]
            self._chain = clip(self._chain, t_p, self.front)
        else:
            self._chain, self.front, self._chain_state = [], t_p, None
        self.t_p, self.mode = t_p, mode
        self._chain_key = (t_p, mode, self.commit_point) if self._chain else None
        return self.committed

    def set_mode(self, mode):
        return self.advance_to(self.t_p, mode)

    # -- progressive calculation --------------------------------------------

    def settle(self, budget_seconds=0.5):
        """Extend the definitive schedule for as long as the budget lasts.

        Each link is planned with the timeline the earlier ones committed, so
        the chain is the same schedule one long plan would have been -- which
        is the only thing that makes a partial answer honest.
        """
        key = (self.t_p, self.mode, self.commit_point)
        if self._chain_key != key:
            self._chain, self._chain_key, self._chain_state = [], key, None
            self.front = self.commit_point
        deadline = time.perf_counter() + budget_seconds
        env = self.environment(self.t_p, self.mode)
        walk = self.walk_at(self.t_p)
        while self.front < self.horizon:
            end = min(self.front + self.LINK, self.horizon)
            link, self._chain_state = walk.run(env, self.front, end,
                                               history=self.committed + self._chain,
                                               resume=self._chain_state,
                                               field_span=(self.commit_point, self.horizon),
                                               with_state=True)
            self._chain.extend(link)
            self.front = end
            if time.perf_counter() >= deadline:
                break
        return self.front

    # -- the rules -----------------------------------------------------------

    def rules(self, t_p=None, mode=None, span=None, budget=3.0) -> Regime:
        """The rule list at the line: one regime, affine in t_p, certified.

        The shape is fitted from two positions and then CHECKED at positions it
        was not fitted on -- a rule list that reproduces the scheduler only
        where it was measured is not a rule list. Where the check fails the
        range is halved, which is how a breakpoint (a period edge the plan
        crosses as the line moves) is found without knowing where it is.
        """
        t_p = self.t_p if t_p is None else frac(t_p)
        mode = self.mode if mode is None else mode
        lo = t_p
        span = frac(span) if span is not None else frac(20)
        end = min(self.horizon, lo + self.LOOKAHEAD)
        past = tuple(clip(self.committed, self.t_start, lo))
        deadline = time.perf_counter() + budget
        a = self._future(lo, mode, end)
        while span > SECOND:
            hi = lo + span
            seg = self._fit(a, self._future(hi, mode, end), lo, hi)
            if seg is not None and all(
                    self._agrees(seg, lo, lo + span * Fraction(k, 4), mode, end)
                    for k in (1, 2, 3)):
                return Regime(lo, hi, mode, past, tuple(seg))
            span /= 2
            if time.perf_counter() > deadline:
                break
        # A regime of one position: still a rule list, just not claimed for a
        # range. The line is never left without an answer.
        seg = tuple(RuleSegment(pl.task, pl.alt, pl.start, Fraction(0),
                                pl.end, Fraction(0)) for pl in a)
        return Regime(lo, lo, mode, past, seg)

    @staticmethod
    def _align(a, b):
        """Pair the two drawn timelines up, segment by segment.

        They are not the same length, and that is not a difference of SHAPE: a
        moving period cuts the run it crosses in two, so a run that is whole at
        one position is a sliver, a gap and a remainder at the next -- and the
        line itself creates a panel as it passes. Every one of those is a rule
        whose length is zero at one end of the range, so the missing side is
        paired with a DEGENERATE segment at the boundary it collapses onto,
        and the fit stays affine instead of being refused.
        """
        out = []
        i = j = 0
        same = lambda x, y: x.task == y.task and x.alt == y.alt
        while i < len(a) and j < len(b):
            if same(a[i], b[j]):
                out.append((a[i], b[j]))
                i, j = i + 1, j + 1
            elif j + 1 < len(b) and same(a[i], b[j + 1]):
                x = a[i].start
                out.append((Placement(b[j].task, x, x, b[j].alt), b[j]))
                j += 1
            elif i + 1 < len(a) and same(a[i + 1], b[j]):
                y = b[j].start
                out.append((a[i], Placement(a[i].task, y, y, a[i].alt)))
                i += 1
            else:
                return None
        if i != len(a) or j != len(b):
            return None
        return out

    @classmethod
    def _fit(cls, a, b, lo, hi):
        pairs = cls._align(a, b)
        if pairs is None:
            return None
        d = hi - lo
        return [RuleSegment(x.task, x.alt,
                            x.start, (y.start - x.start) / d,
                            x.end, (y.end - x.end) / d)
                for x, y in pairs]

    def _agrees(self, seg, lo, x, mode, end):
        drawn = Regime(lo, x, mode, (), tuple(seg)).draw(x)
        actual = self._future(x, mode, end)
        if len(drawn) != len(actual):
            return False
        for p, q in zip(drawn, actual):
            if p.task != q.task or p.alt != q.alt:
                return False
            if abs(p.start - q.start) > EPS or abs(p.end - q.end) > EPS:
                return False
        return True


# --------------------------------------------------------------------------- #
# cases -- the README's own examples, plus the shapes `tests.md` describes
# --------------------------------------------------------------------------- #

def case_alternation(horizon=3 * HOUR) -> Scheduler:
    """The README's granularity example, two tasks: 50/50 with a 10-minute
    minimum has to alternate in 10-minute slots, not in hour-long blocks."""
    tasks = (task("A", 50, 10, resilience=ON_SCREEN, color="#FF9999"),
             task("B", 50, 10, resilience=ON_SCREEN, color="#99CCFF"))
    return Scheduler([state(0, tasks)], dynamics=(), horizon=horizon)


def case_granularity(horizon=6 * HOUR) -> Scheduler:
    """The README's three-task example: A 30min 33%, B 15min 33%, C 15min 33%
    -> A 30, B 15, C 15, B 15, C 15, ..."""
    tasks = (task("A", 1, 30, resilience=ON_SCREEN),
             task("B", 1, 15, resilience=ON_SCREEN),
             task("C", 1, 15, resilience=ON_SCREEN))
    return Scheduler([state(0, tasks)], dynamics=(), horizon=horizon)


def case_block(horizon=10 * HOUR) -> Scheduler:
    """A pre-placed hour of A: B is deprived, and is compensated around the
    blockage with an influence that decays exponentially away from it."""
    tasks = (task("A", 50, 10, resilience=ON_SCREEN),
             task("B", 50, 10, resilience=ON_SCREEN))
    env = Environment(blocks=[block("A", 100, 60)])
    return Scheduler([state(0, tasks)], env, dynamics=(), horizon=horizon)


def case_resilience(horizon=12 * HOUR) -> Scheduler:
    """A restrictive period B is only half-resilient to: inside it B keeps
    half of its percentage, so it runs there -- just less."""
    tasks = (task("A", 50, 10, resilience=ON_SCREEN),
             task("B", 50, 10, resilience={**ON_SCREEN, "noisy": Fraction(1, 2)}))
    env = Environment(periods=[period(200, 500, "noisy", "noisy")])
    return Scheduler([state(0, tasks)], env, dynamics=(), horizon=horizon)


def case_dynamics(horizon=6 * HOUR) -> Scheduler:
    """The three dynamic periods over a plain rule state, with one task that
    is NOT on-screen -- so a "no on-screen task" stretch is not empty: C is
    placed in it, and only C."""
    tasks = (task("A", 40, 10, resilience=ON_SCREEN, color="#FF9999"),
             task("B", 40, 10, resilience=ON_SCREEN, color="#99CCFF"),
             task("C", 20, 10, color="#99FF99"))
    return Scheduler([state(0, tasks)], horizon=horizon)


def _many_tasks(main_share=50):
    rest = Fraction(100 - main_share, 20)
    tasks = [task("A", main_share, 45, resilience=ON_SCREEN, color="#FF9999")]
    for i in range(20):
        # half of them on-screen, half of them not: the "evening" periods below
        # are "no on-screen task", so the other ten still run inside them.
        tasks.append(task(f"T{i:02d}", rest, 45,
                          resilience=ON_SCREEN if i >= 10 else None))
    return tuple(tasks)


def case_three_days() -> Scheduler:
    """`tests.md` test 12's shape: 21 tasks, three days, a night every day, and
    the three dynamic periods on top."""
    tasks = _many_tasks()
    nights = []
    for day in range(4):
        base = day * DAY
        nights.append(period(base, base + 8 * HOUR, KIND_NO_TASK, "night"))
        nights.append(period(base + 23 * HOUR, base + DAY, KIND_NO_SCREEN, "evening"))
    return Scheduler([state(0, tasks)], Environment(periods=nights), horizon=3 * DAY)


def case_sliding() -> Scheduler:
    """`tests.md` test 13: the same, with the percentages sliding from the
    state at 24h to the state at 48h."""
    first = _many_tasks(50)
    second = _many_tasks(25)
    nights = []
    for day in range(4):
        base = day * DAY
        nights.append(period(base, base + 8 * HOUR, KIND_NO_TASK, "night"))
    return Scheduler([state(DAY, first), state(2 * DAY, second)],
                     Environment(periods=nights), horizon=3 * DAY)


CASES = {
    "alternation": case_alternation,
    "granularity": case_granularity,
    "block": case_block,
    "resilience": case_resilience,
    "dynamics": case_dynamics,
    "three-days": case_three_days,
    "sliding": case_sliding,
}


# --------------------------------------------------------------------------- #
# text output
# --------------------------------------------------------------------------- #

def draw_text(placements, limit=40) -> list:
    out = []
    for pl in placements[:limit]:
        alt = f"   else {pl.alt}" if pl.alt else ""
        out.append(f"  {human(pl.start):>12} .. {human(pl.end):<12} "
                   f"{pl.task:<8} ({human(pl.duration)}){alt}")
    if len(placements) > limit:
        out.append(f"  ... {len(placements) - limit} more")
    return out


def share_lines(placements) -> list:
    return [f"  {n:<8} {float(v) * 100:5.1f}%" for n, v in resulting_shares(placements).items()]


# --------------------------------------------------------------------------- #
# the checks -- one per README clause
# --------------------------------------------------------------------------- #

CHECKS = []


def check(fn):
    CHECKS.append(fn)
    return fn


def require(cond, msg):
    if not cond:
        raise AssertionError(msg)


def same_timeline(a, b) -> bool:
    if len(a) != len(b):
        return False
    for p, q in zip(a, b):
        if p.task != q.task:
            return False
        if abs(p.start - q.start) > EPS or abs(p.end - q.end) > EPS:
            return False
    return True


def share_in(placements, lo, hi) -> dict:
    return resulting_shares(clip(placements, lo, hi))


@check
def check_alternation():
    """Two 50% tasks with a 10-minute minimum alternate in 10-minute slots."""
    s = case_alternation()
    tl = [p for p in s.timeline(0, 1) if p.task != IDLE]
    require(len(tl) >= 17, f"only {len(tl)} slots over three hours")
    require(all(p.duration == 10 for p in tl[:-1]),
            f"slot sizes: {sorted({human(p.duration) for p in tl[:-1]})}")
    require(all(x.task != y.task for x, y in itertools.pairwise(tl)),
            "a task ran twice in a row with a rival waiting")
    return f"{len(tl)} slots, all {human(tl[0].duration)}, strictly alternating"


@check
def check_granularity():
    """The README's own three-task example: A 30min 33%, B 15min 33%,
    C 15min 33% -> A 30, B 15, C 15, B 15, C 15."""
    s = case_granularity()
    tl = [p for p in s.timeline(0, 1) if p.task != IDLE]
    sizes = {}
    for p in tl[:-1]:
        sizes.setdefault(p.task, set()).add(p.duration)
    require(sizes["A"] == {30}, f"A's slots: {sizes['A']}")
    require(sizes["B"] == {15} and sizes["C"] == {15},
            f"B {sizes['B']}, C {sizes['C']}")
    sh = resulting_shares(tl)
    for n in "ABC":
        require(abs(sh[n] - Fraction(1, 3)) < Fraction(4, 100),
                f"{n} got {float(sh[n]):.3f} of the timeline")
    return "A in 30min slots, B and C in 15min ones, a third each"


@check
def check_minimums_hold():
    """Every placement is at least its task's minimum, unless a period edge or
    the horizon cut it -- the minimum is soft, but it is only ever given up to
    the environment."""
    s = case_dynamics()
    tl = s.timeline(0, 1)
    env = s.environment(0, 1)
    edges = set(env.bounds)
    bad = [p for p in tl
           if p.task != IDLE
           and p.duration < s.walk_at(0).minimum[p.task]
           and p.end not in edges and p.start not in edges
           and p.end != s.horizon]
    require(not bad, f"{len(bad)} placements below their minimum, e.g. {bad[:2]}")
    return f"{len(tl)} placements, none short of its minimum by choice"


@check
def check_no_idling():
    """Nothing is idle where a task is allowed."""
    s = case_dynamics()
    tl = s.timeline(0, 1)
    env = s.environment(0, 1)
    specs = s.specs_at(0)
    shares = s.walk_at(0).p
    for pl in tl:
        if pl.task != IDLE:
            continue
        mid = (pl.start + pl.end) / 2
        w = env.weights(specs, shares, mid)
        require(not any(x > 0 for x in w.values()),
                f"idle at {human(mid)} where {[n for n, x in w.items() if x > 0]} were allowed")
    idle = sum((p.duration for p in tl if p.task == IDLE), Fraction(0))
    return f"{human(idle)} idle over {human(s.horizon)}, all of it inside all-refusing periods"


@check
def check_resilience_multiplier():
    """A resilience of 1/2 halves the percentage for as long as the period
    lasts -- it does not forbid, and it does not leave the share untouched."""
    s = case_resilience()
    tl = s.timeline(0, 1)
    inside = share_in(tl, 200, 500)
    outside = share_in(tl, 600, 12 * HOUR)
    require(Fraction(1, 5) < inside["B"] < Fraction(45, 100),
            f"B took {float(inside['B']):.3f} of the half-resilient period")
    require(inside["B"] < outside["B"],
            "B was no worse off inside the period than outside it")
    require(abs(outside["B"] - Fraction(1, 2)) < Fraction(8, 100),
            f"B settled at {float(outside['B']):.3f} away from the period")
    return (f"B {float(inside['B']) * 100:.1f}% inside (target 33.3%), "
            f"{float(outside['B']) * 100:.1f}% outside")


@check
def check_resilience_zero_forbids():
    """A resilience of 0 is exactly the forbidding period."""
    tasks = (task("A", 50, 10, resilience=ON_SCREEN),
             task("B", 50, 10, resilience={**ON_SCREEN, "noisy": Fraction(0)}))
    env = Environment(periods=[period(200, 500, "noisy", "noisy")])
    s = Scheduler([state(0, tasks)], env, dynamics=(), horizon=10 * HOUR)
    tl = s.timeline(0, 1)
    inside = share_in(tl, 200, 500)
    require("B" not in inside, f"B ran inside a period it has no resilience to: {inside}")
    require(abs(inside.get("A", 0) - 1) < EPS, f"A did not hold the window: {inside}")
    return "B absent from the window, A holds all of it"


@check
def check_compensation_decays():
    """A deprived task comes back denser next to the blockage, and the
    compensation decays away from it -- it is not a full catch-up."""
    s = case_block()
    tl = s.timeline(0, 1)
    near = share_in(tl, 160, 260)["B"]
    far = share_in(tl, 460, 560)["B"]
    require(near > far, f"B near {float(near):.3f} vs far {float(far):.3f}")
    require(far < Fraction(60, 100), f"B still at {float(far):.3f} far from the block")
    require(near < 1, "B took the whole stretch after the block -- that is a catch-up, not a decay")
    return f"B {float(near) * 100:.0f}% just after the block, {float(far) * 100:.0f}% five hours later"


@check
def check_rule_state_blend():
    """Between two rule states every number travels evenly; outside the pair
    the nearer state is held."""
    a = (task("A", 50, 45), task("B", 50, 45))
    b = (task("A", 25, 45), task("B", 75, 45))
    rs = RuleStates([state(DAY, a), state(2 * DAY, b)])
    at = lambda t: {x.name: x.priority for x in rs.at(t)}
    require(at(DAY)["A"] == 50 and at(2 * DAY)["A"] == 25, "the keyframes moved")
    require(at(Fraction(3, 2) * DAY)["A"] == Fraction(75, 2),
            f"halfway A = {at(Fraction(3, 2) * DAY)['A']}")
    require(at(0)["A"] == 50 and at(3 * DAY)["A"] == 25, "a state was extrapolated past its pin")
    single = RuleStates([state(0, a)])
    require(single.at(10 * DAY)[0].priority == 50, "one state must stand forever")
    return "linear between the pins, held outside them"


@check
def check_sliding_priorities():
    """The percentages are a property of the POSITION the plan is made from:
    the same stretch of timeline is scheduled differently depending on where
    the line stands, because the rule state at the line has moved."""
    s = case_sliding()
    at = lambda t: {x.name: x.priority for x in s.specs_at(t)}
    require(at(DAY)["A"] == 50 and at(2 * DAY)["A"] == 25, "the keyframes moved")
    require(at(Fraction(3, 2) * DAY)["A"] == Fraction(75, 2), "the blend is not even")
    window = (DAY + 8 * HOUR, DAY + 16 * HOUR)
    early = resulting_shares(clip(s.timeline(DAY, 1), *window))
    late = resulting_shares(clip(s.timeline(2 * DAY, 1), *window))
    require(early["A"] > late["A"] + Fraction(10, 100),
            f"A took {float(early['A']):.3f} from the first state and "
            f"{float(late['A']):.3f} from the second -- the line's state was not read")
    return (f"same eight hours: A {float(early['A']) * 100:.0f}% planned from 24h, "
            f"{float(late['A']) * 100:.0f}% planned from 48h")


@check
def check_frozen_past():
    """The schedule below the line never changes as the line advances."""
    s = case_dynamics(horizon=2 * HOUR)
    seen = []
    for x in (5, 12, 19, 25, 33, 41):
        s.advance_to(x, 1)
        tl = s.timeline()
        for pos, older in seen:
            require(same_timeline(clip(older, 0, pos), clip(tl, 0, pos)),
                    f"the schedule below {human(pos)} changed once the line reached {human(x)}")
        seen.append((frac(x), tl))
    return f"{len(seen)} positions, every earlier past reproduced exactly"


@check
def check_dynamic_recurrence():
    """The bars of the README, read off the placement itself."""
    s = case_dynamics(horizon=12 * HOUR)
    inst = s.planner_at(0).instances(0, 1)
    require(len(inst) > 20, f"only {len(inst)} dynamic periods over twelve hours")
    for i, cur in enumerate(inst):
        for prev in inst[:i]:
            if prev.end > cur.start:
                continue
            gap = cur.start - prev.end
            if cur.label == "20s":
                require(gap >= BAR_20S_AFTER_ANY - EPS,
                        f"a 20s only {human(gap)} after the {prev.label} at {human(prev.start)}")
            if prev.duration >= STRETCH_SHORT and cur.label == "5min":
                require(gap >= BAR_5MIN_AFTER_STRETCH - EPS,
                        f"a 5min only {human(gap)} after a {human(prev.duration)} stretch")
            if prev.duration >= STRETCH_LONG and cur.label == "15min":
                require(gap >= BAR_15MIN_AFTER_LONG - EPS,
                        f"a 15min only {human(gap)} after a {human(prev.duration)} stretch")
    require(not any(a.end > b.start for a, b in itertools.pairwise(inst)),
            "two dynamic periods overlap")
    kinds = {i.label for i in inst}
    return f"{len(inst)} periods ({', '.join(sorted(kinds))}), every bar respected"


@check
def check_mode1_keeps_the_line_clear():
    """Mode 1: no period covers t_p with "no on-screen task", at any position
    -- and the period the line is dragging is the half-open (t_p, t_p + 20s]."""
    s = case_dynamics(horizon=3 * HOUR)
    dragged = 0
    x = Fraction(0)
    while x < 100:
        env = s.environment(x, 1)
        require(not env.no_screen_at(x),
                f"mode 1 left the line covered by \"no on-screen task\" at {human(x)}")
        for inst in s.planner_at(x).instances(x, 1):
            if inst.open_start:
                dragged += 1
                require(inst.start == x, f"a dragged period starts at {human(inst.start)}, not at the line")
                require(not inst.to_periods()[0].covers(x), "the dragged period covers t_p")
                require(inst.to_periods()[-1].covers(inst.end), "the dragged period is not closed at its end")
        x += 20 * SECOND
    return f"300 positions swept, the line always clear, {dragged} of them dragging a period"


@check
def check_chain_absorbs():
    """The README's own consequence: a 20s dragged until its end touches the
    5min is absorbed -- the chain becomes the 5min, starting where the 20s
    did, and the line stays outside it."""
    s = case_dynamics(horizon=3 * HOUR)
    t_p = Fraction(60) - 20 * SECOND
    inst = s.planner_at(t_p).instances(t_p, 1)
    here = [i for i in inst if i.start <= t_p + 5 <= i.end or i.start == t_p]
    require(any(i.label == "5min" and i.start == t_p and i.open_start for i in here),
            f"no absorbed 5min at the line: {[(i.label, human(i.start)) for i in inst[:4]]}")
    require(not any(i.label == "20s" and i.start == t_p for i in inst),
            "the 20s survived the merge")
    env = s.environment(t_p, 1)
    require(not env.no_screen_at(t_p), "the merged period swallowed the line")
    require(env.no_screen_at(t_p + 1), "the merged period is not where the 5min was")
    return f"20s absorbed, the 5min now runs ({human(t_p)}, {human(t_p + 5)}]"


@check
def check_mode2_covers_the_line():
    """Mode 2: t_p IS covered by "no on-screen task", and the gap between the
    period that just ended and the line is covered by one -- so the tasks with
    a non-zero resilience to that kind are placed in it, and nothing idles."""
    s = case_dynamics(horizon=3 * HOUR)
    inst = s.planner_at(0).instances(0, 1)
    fifteen = next(i for i in inst if i.label == "15min")
    t_p = fifteen.end + 4
    env = s.environment(t_p, 2)
    require(env.no_screen_at(t_p), "mode 2 left the line uncovered")
    gap = [p for p in env.periods if p.kind == KIND_NO_SCREEN and p.label == "mode2"]
    require(gap and gap[0].start == fifteen.end and gap[0].end == t_p,
            f"the covering period is {[(human(p.start), human(p.end)) for p in gap]}")
    tl = clip(s.timeline(t_p, 2), fifteen.end, t_p)
    require(tl, "the gap was left empty")
    require(all(p.task == "C" for p in tl),
            f"an on-screen task ran inside the gap: {[p.task for p in tl]}")
    return f"gap {human(fifteen.end)}..{human(t_p)} covered, filled with C alone"


@check
def check_rest_is_the_no_screen_stretch():
    """The README's stretch, in all three of its clauses.

    "a >= 5 / >= 15-minute stretch covered by the period 'no on-screen task'
    without any task" -- so an hour of that kind rests only when nobody is
    left to run in it, and an emptiness of some OTHER kind is not a rest at
    all, however empty it is.
    """
    hour = (2 * HOUR, 3 * HOUR)

    def planner(tasks, kind):
        env = Environment(periods=[period(*hour, kind, "the hour")])
        return Scheduler([state(0, tasks)], env, horizon=8 * HOUR).planner_at(0)

    on_screen = (task("A", 50, 10, resilience=ON_SCREEN),
                 task("B", 50, 10, resilience=ON_SCREEN))
    with_off = on_screen + (task("C", 50, 10),)

    # (1) covered by the kind, and nobody may run: a rest stretch, and the
    #     three bars are owed from its END.
    rests = planner(on_screen, KIND_NO_SCREEN)
    require(rests.rested == [hour],
            f"an empty 'no on-screen task' hour is not a rest: {rests.rested}")
    end = hour[1]
    for inst in rests.instances(0, 1):
        gap = inst.start - end
        if gap < 0:
            continue
        bar = {"20s": BAR_20S_AFTER_LONG, "5min": BAR_5MIN_AFTER_STRETCH,
               "15min": BAR_15MIN_AFTER_LONG}[inst.label]
        require(gap >= bar - EPS,
                f"a {inst.label} only {human(gap)} after the hour (bar {human(bar)})")

    # (2) covered by the kind, but C runs there: no idling, so no stretch --
    #     and nothing about the placement changes.
    busy = planner(with_off, KIND_NO_SCREEN)
    require(busy.rested == [],
            f"an hour C runs in was counted as a rest: {busy.rested}")
    plain = Scheduler([state(0, with_off)], horizon=8 * HOUR).planner_at(0)
    require([(i.label, i.start) for i in busy.instances(0, 1)]
            == [(i.label, i.start) for i in plain.instances(0, 1)],
            "a 'no on-screen task' hour somebody runs in moved the periods")

    # (3) empty, but of another kind: not the README's stretch. It still
    #     absorbs (nothing can be placed there), it just bars nothing after.
    deaf = tuple(task(s.name, s.priority, s.min_time,
                      resilience={**ON_SCREEN, "noisy": Fraction(0)})
                 for s in on_screen)
    other = planner(deaf, "noisy")
    require(other.blocked == [hour],
            f"an hour nobody may run in is not blocked: {other.blocked}")
    require(other.rested == [],
            f"an empty hour of another kind was counted as a rest: {other.rested}")
    first = min((i.start for i in other.instances(0, 1) if i.start >= end),
                default=None)
    require(first is not None and first - end < BAR_20S_AFTER_LONG,
            f"the 'noisy' hour barred what came after it (next at {human(first)})")
    return ("the hour rests when it is that kind and empty, and only then "
            f"(next period after the 'noisy' one: {human(first - end)} later)")


@check
def check_pre_placed_task_is_not_a_rest():
    """A pre-placed task is a TASK: an hour of it is not a stretch "without
    any task", however little the scheduler had to say about it. Nothing is
    placed inside it either -- there is no room."""
    tasks = (task("A", 50, 10, resilience=ON_SCREEN),
             task("B", 50, 10, resilience=ON_SCREEN))
    env = Environment(blocks=[block("MAINTENANCE", HOUR, 60)])
    pl = Scheduler([state(0, tasks)], env, horizon=8 * HOUR).planner_at(0)
    require(pl.rested == [],
            f"a pre-placed hour was counted as a rest: {pl.rested}")
    require(pl.blocked == [(HOUR, 2 * HOUR)],
            f"a pre-placed hour nobody schedulable owns is not blocked: {pl.blocked}")
    inst = pl.instances(0, 1)
    inside = [i for i in inst if i.start < 2 * HOUR and i.end > HOUR]
    require(not inside,
            f"a dynamic period landed inside the block: "
            f"{[(i.label, human(i.start)) for i in inside]}")
    after = min(i.start for i in inst if i.start >= 2 * HOUR)
    require(after - 2 * HOUR < BAR_20S_AFTER_LONG,
            f"the block barred what came after it (next at {human(after)})")
    return (f"the hour bars nothing, holds nothing, and the next period is "
            f"{human(after - 2 * HOUR)} after it")


@check
def check_alternative_schedule():
    """Every rule names the task to run instead, and running it really is a
    schedule the scheduler can continue from."""
    s = case_dynamics(horizon=4 * HOUR)
    tl = [p for p in s.timeline(0, 1) if p.task != IDLE]
    require(all(p.alt and p.alt != p.task for p in tl[:20]),
            "a rule named no alternative")
    t_p = Fraction(37)
    alt = s.alternative_at(t_p, 1)
    require(alt, "no alternative at the line")
    drawn = s.timeline(t_p, 1)
    # the line in mode 1 stands at the edge of the period it drags, so what is
    # scheduled "now" is the next thing scheduled
    nxt = next(p for p in drawn if p.task != IDLE and p.end > t_p)
    require(alt != nxt.task, "the alternative is the task it replaces")
    # the README's own use of it: run the alternative from here instead, and
    # hand the new timeline back to the scheduler
    env = Environment(s.base.periods, list(s.base.blocks) + [block(alt, nxt.start, 10)])
    again = Scheduler([state(0, s.specs_at(0))], env, dynamics=s.dynamics, horizon=s.horizon)
    over = [p for p in clip(again.timeline(t_p, 1), nxt.start, nxt.start + 10)
            if p.task != IDLE]
    require(over and all(p.task == alt for p in over),
            f"the re-run did not honour the alternative: {[p.task for p in over]}")
    return f"alternative at {human(t_p)} is {alt} (scheduled: {nxt.task}), and it re-plans"


@check
def check_rules_are_affine_in_tp():
    """The rules at the line hold over a RANGE of positions, and reproduce the
    scheduler at positions they were never fitted on."""
    s = case_dynamics(horizon=4 * HOUR)
    s.advance_to(30, 1)
    reg = s.rules(span=5)
    require(reg.hi > reg.lo, "the rules could not be claimed for any range at all")
    end = min(s.horizon, reg.lo + s.LOOKAHEAD)
    for k in (1, 3, 5, 7):
        x = reg.lo + (reg.hi - reg.lo) * Fraction(k, 8)
        drawn = clip(reg.draw(x), x, end)
        actual = clip(s.timeline(x, 1, upto=end), x, end)
        require(same_timeline(drawn, actual),
                f"the rules disagree with the scheduler at t_p = {human(x)}")
    moving = [seg for seg in reg.segments if seg.start_slope or seg.end_slope]
    return (f"one regime over {human(reg.hi - reg.lo)} of t_p, {len(reg.segments)} rules, "
            f"{len(moving)} of them moving with the line")


@check
def check_progressive_pace():
    """The README's pace: ten minutes of definitive schedule per ten seconds
    of work. Measured on the three-day, 21-task case."""
    s = case_three_days()
    start = s.front
    t0 = time.perf_counter()
    s.settle(budget_seconds=1.0)
    elapsed = time.perf_counter() - t0
    gained = float(s.front - start)
    rate = gained / max(elapsed, 1e-9)
    require(rate >= 1.0,
            f"only {gained:.0f} timeline-minutes in {elapsed:.2f}s ({rate:.2f}/s, need 1/s)")
    require(s.front > start, "settling gained nothing")
    return (f"{human(s.front - start)} of definitive schedule in {elapsed:.2f}s "
            f"= {rate:.0f} timeline-minutes per second (need 1)")


@check
def check_resume_contract():
    """A chain of re-plans is the same schedule as one long plan: settling in
    one-hour links must not move the shares."""
    whole = case_dynamics(horizon=6 * HOUR).timeline(0, 1)
    chained = case_dynamics(horizon=6 * HOUR)
    chained.LINK = HOUR
    links = int(chained.horizon / chained.LINK)
    while chained.front < chained.horizon:
        chained.settle(budget_seconds=10)
    linked = coalesce(clip(chained.committed + chained._chain, 0, chained.horizon))
    diff = [f"{human(x.start)}: {x.task} vs {y.task}"
            for x, y in zip(whole, linked)
            if x.task != y.task or abs(x.start - y.start) > EPS]
    require(same_timeline(whole, linked),
            f"the chain is not the plan ({len(diff)} places), first: {diff[:2]}")
    return f"{len(whole)} placements over {links} link(s), identical to the single plan"


@check
def check_shares_match_targets():
    """The first optimisation criterion, over three days: a task's presence is
    its percentage."""
    tasks = (task("A", 50, 45, resilience=ON_SCREEN),
             task("B", Fraction(25, 2), 45, resilience=ON_SCREEN),
             task("C", Fraction(25, 2), 45, resilience=ON_SCREEN),
             task("D", Fraction(25, 2), 45, resilience=ON_SCREEN),
             task("E", Fraction(25, 2), 45, resilience=ON_SCREEN))
    s = Scheduler([state(0, tasks)], dynamics=(), horizon=3 * DAY)
    sh = resulting_shares(s.timeline(0, 1))
    target = {"A": Fraction(1, 2), "B": Fraction(1, 8), "C": Fraction(1, 8),
              "D": Fraction(1, 8), "E": Fraction(1, 8)}
    for n, want in target.items():
        require(abs(sh[n] - want) < Fraction(3, 100),
                f"{n} got {float(sh[n]):.3f}, wanted {float(want):.3f}")
    return ", ".join(f"{n} {float(sh[n]) * 100:.1f}%" for n in sorted(sh))


@check
def check_many_tasks_share():
    """The case where a raw virtual clock goes wrong: one task at 50% against
    twenty at 2.5%, all with the same 45-minute minimum. Read as a clock, all
    twenty outrank A the moment it has taken one slot, and they take twenty
    slots in a row -- the README's monolithic block, assembled out of twenty
    tasks. Counted in each task's OWN slots, they do not."""
    s = Scheduler([state(0, _many_tasks())], dynamics=(), horizon=8 * DAY)
    sh = resulting_shares(s.timeline(0, 1))
    require(abs(sh["A"] - Fraction(1, 2)) < Fraction(2, 100),
            f"A took {float(sh['A']) * 100:.1f}% of eight days, wanted 50%")
    others = [v for n, v in sh.items() if n != "A"]
    require(len(others) == 20, f"{len(others)} of the twenty ever ran")
    worst = max(abs(v - Fraction(25, 1000)) for v in others)
    require(worst < Fraction(7, 1000),
            f"one of the twenty is {float(worst) * 100:.2f} points off its 2.5%")
    return (f"A {float(sh['A']) * 100:.1f}%, the twenty between "
            f"{float(min(others)) * 100:.2f}% and {float(max(others)) * 100:.2f}% "
            f"(target 2.5%)")


@check
def check_line_moves_continuously():
    """The README's line: every t <= t_p is a position the line has ALREADY
    held, so it moves continuously forward and can never land somewhere it did
    not travel through.

    Two consequences, and together they are the whole of the clause:

    * MODE 1 LEAVES NOTHING BEHIND. The line may not be covered, so a period it
      reaches is pushed ahead of it -- and it has stood on every instant below
      itself, so no dynamic period can be left standing below the line. There
      is no floor on the sweep to be told where the line "started".
    * THE ROUTE IS NOT A VARIABLE. One move to a position and a four-times
      finer sampling of the same journey are the same journey, so they must
      leave the same frozen past.

    The route half is asked of a case with the three dynamic periods and NO two
    tasks alike: the drag rides an obstacle immediately ahead of the line, which
    is what makes a coarse move differ in the first place, and tasks that tie
    would flip on any perturbation and prove nothing either way.
    """
    tasks = (task("A", 55, 10, resilience=ON_SCREEN),
             task("B", 25, 15, resilience=ON_SCREEN),
             task("C", 20, 20))
    make = lambda: Scheduler([state(0, tasks)], horizon=6 * HOUR)
    target = 3 * HOUR
    step = min(t.min_time for t in tasks)

    one = make()
    one.advance_to(target, 1)

    fine, t = make(), Fraction(0)
    while t < target:                       # four stops inside every slot
        t = min(target, t + step / 4)
        fine._commit_at(t, 1)

    below = [i for i in one.planner_at(target).instances(target, 1) if i.start < target]
    require(not below, f"{len(below)} dynamic period(s) left standing below the line")
    on_line = [i for i in one.planner_at(target).instances(target, 1) if i.start == target]
    require(on_line, "the swept hours owed nothing at all")
    require(all(i.open_start for i in on_line),
            "a period sits ON the line instead of the half-open (t_p, t_p + d]")
    require(same_timeline(one.committed, fine.committed),
            f"the route changed the frozen past ({len(one.committed)} placements "
            f"against {len(fine.committed)})")
    return (f"one move over {human(target)} = a {human(step / 4)} sampling of it, "
            f"{len(one.committed)} placements, nothing left below the line")



# --------------------------------------------------------------------------- #
# command line
# --------------------------------------------------------------------------- #

def run_checks(only=None) -> int:
    width = max(len(f.__name__) for f in CHECKS)
    failed = []
    print(f"scheduler -- {len(CHECKS)} checks against README.md\n")
    for fn in CHECKS:
        if only and only not in fn.__name__:
            continue
        t0 = time.perf_counter()
        try:
            note = fn()
            print(f"  ok    {fn.__name__:<{width}}  {note}"
                  f"   [{time.perf_counter() - t0:.2f}s]")
        except AssertionError as exc:
            failed.append(fn.__name__)
            print(f"  FAIL  {fn.__name__:<{width}}  {exc}")
        except Exception as exc:                      # noqa: BLE001 - reported, not swallowed
            failed.append(fn.__name__)
            print(f"  ERROR {fn.__name__:<{width}}  {type(exc).__name__}: {exc}")
    print()
    if failed:
        print(f"{len(failed)} failed: {', '.join(failed)}")
        return 1
    print("all checks pass")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[1])
    ap.add_argument("--check", nargs="?", const="", metavar="NAME",
                    help="run the checks (optionally only those matching NAME)")
    ap.add_argument("--demo", nargs="?", const="dynamics", metavar="CASE",
                    help=f"draw a case: {', '.join(CASES)}")
    ap.add_argument("--rules", nargs="?", const="dynamics", metavar="CASE",
                    help="print the rule list at the line for a case")
    ap.add_argument("--tp", default="0", help="where the line stands (minutes)")
    ap.add_argument("--mode", type=int, default=1, choices=(1, 2))
    ap.add_argument("--settle", type=float, default=0.0,
                    help="seconds of progressive settling before drawing")
    ap.add_argument("--limit", type=int, default=40)
    args = ap.parse_args(argv)

    if args.check is not None:
        return run_checks(args.check or None)

    name = args.demo or args.rules
    if not name:
        ap.print_help()
        return 0
    if name not in CASES:
        print(f"unknown case {name!r}; pick one of {', '.join(CASES)}")
        return 2
    s = CASES[name]()
    t_p = frac(Fraction(args.tp))
    if t_p > s.t_start:
        s.advance_to(t_p, args.mode)
    else:
        s.mode = args.mode
    if args.settle:
        front = s.settle(budget_seconds=args.settle)
        print(f"settled up to {human(front)} in {args.settle}s\n")

    if args.rules:
        reg = s.rules(t_p, args.mode)
        print("\n".join(reg.lines()))
        print(f"\nalternative at the line: {s.alternative_at(t_p, args.mode) or '(none)'}")
        return 0

    tl = s.timeline(t_p, args.mode)
    print(f"{name}: t_p = {human(t_p)}, mode {args.mode}, horizon {human(s.horizon)}")
    periods = s.dynamic_periods(t_p, args.mode)
    if periods:
        shown = ", ".join(f"{p.label}@{human(p.start)}" for p in periods[:8])
        print(f"dynamic periods ({len(periods)}): {shown}"
              + (" ..." if len(periods) > 8 else ""))
    print("\n".join(draw_text(tl, args.limit)))
    print("\nresulting shares")
    print("\n".join(share_lines(tl)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
