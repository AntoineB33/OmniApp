#!/usr/bin/env python3
"""
Cyclic proportional-share scheduler with minimum slot durations + Tk timeline.

The rules
---------
Each task has a minimum time (once placed, nothing else can start until it
elapses) and a priority percentage. The timeline is infinite, so the scheduler
returns a finite list of rules instead of a list of slots:

    [ prefix ]  played once (fixed blocks, constrained windows, catch-up)
    [ cycle  ]  repeated forever

Drawing t=0..x just unrolls that list, and any instant can be looked up in
O(log rules) with Plan.task_at().

The cycle
---------
Built analytically on the smallest period that can exist:

    T        = max(min_i / p_i)      <- below this, some task cannot get a slot
    budget_i = p_i * T  (>= min_i)   <- exact share, by construction

Inside the period a virtual-clock greedy (Weighted Fair Queuing) interleaves
the budgets at the finest granularity: it always serves the most starved task
(smallest v[i] = served_i / p_i), giving it just enough to catch up with the
runner-up, never less than its minimum, never more than its remaining budget,
and never leaving a remainder too small to be placed. Shares inside the cycle
are therefore exact, not approximate.

The prefix
----------
Pre-placed blocks cannot be moved, and period windows restrict which tasks are
allowed, so while any of them is still ahead the same greedy runs freely and
its output goes to the prefix. Once the context is frozen forever, the
remaining imbalance is settled and the cycle is attached -- in the phase the
greedy would have gone on with (`_phase`), because a cycle built from a blank
slate always opens with the same task, and opening there after a prefix that
ends with it would hand that task two slots in a row: one block of twice the
minimum, which is the coarse scale this model exists to avoid.

A window bounds only the tasks it turns away
--------------------------------------------
A context change is not a wall. A window that bans B does not interrupt A, so a
chunk of A runs straight through it; what bounds a chunk is the first instant
its *own* task is turned away (`_blocked_from`), never merely the next instant
something changes. Treating the two as one makes a short ban punch an
unfillable hole into somebody else's slot -- no 10min minimum fits in a 20s gap
-- and the timeline goes idle for want of anything that fits.

For the same reason a chunk is only ever *cut short* by a rival becoming
placeable again (`_next_placeable`), and never below what the slot still owes
of its minimum. Consecutive slots of one task are one slot, so a task already
running has paid that minimum and may be cut anywhere; only a slot that is
starting owes the whole of it.

The influence field (tau)
-------------------------
A task can be deprived of the timeline in exactly two ways, and they are the
same phenomenon:

  * a fixed block owned by *another* task hands that task a lump of timeline
    nobody else gets -- i.e. it forbids everyone else for the length of the
    block;
  * a period window forbids every task it does not list.

Both are therefore reduced to one object: an *exclusion*, an interval a given
task is kept out of. Around an exclusion the schedule is deliberately distorted
in favour of the deprived task, and the distortion decays exponentially with
the distance d to the interval:

    a(L)      = L / tau                     amplitude of an exclusion of length L
    boost_n(t)= 1 + min(max_boost - 1,       how much longer n's slots may be at
                        sum a(L_e) e^-d/tau) t, over the intervals e it is kept
                                             out of

Two things follow from that shape, and they are the whole point:

  * the boost is *capped*, so near an exclusion a task dominates its
    neighbourhood but never owns it; the width of the saturated zone is
    tau*ln(a/max_boost), hence the total compensation grows like tau*ln(L/tau)
    -- an exclusion 100x longer buys a few times more compensation, not 100x
    more;
  * the influence vanishes (below `field_floor`) at a finite distance, so the
    rule list stays finite and the steady cycle is reached again.

An interval that excludes *everybody* (maintenance, a holiday, a window that
allows nothing) takes the same amount from everyone: no relative distortion, no
field. An exclusion that never re-opens has no "after", so it only ramps up
before itself, and its amplitude is capped (`max_reach`): past a few tau, "very
long" and "forever" are indistinguishable anyway, because the boost saturates.

Nor does an exclusion *shorter than the deprived task's own minimum* create a
field. It cannot have cost that task a slot, only delayed it -- and a delay is
already repaid exactly by the virtual clock, which hands back every minute lost
the moment the ban lifts. Compensating it here as well would pay the same debt
twice, and would leave a 20s ban swelling the slots around it forever after.

Because the boosted time is charged to the virtual clock at the boosted rate
(v += c / (p*boost)), it is *not* considered a debt and is never clawed back
later: the local share really is higher near the exclusion. Symmetrically, an
imbalance larger than one period is forgotten exponentially (`_relax`), so an
enormous block -- or a very long ban -- is not repaid in full; it is repaid up
to the same logarithmic bound, before and after itself.

Below one period nothing is forgotten and no slot is capped, so an unperturbed
timeline behaves exactly as before and the cycle stays exact: everything that
matters in the long run is still fractions.Fraction.

A moving period: the dynamic rule list
--------------------------------------
One period may *slide* along the timeline. Re-running the scheduler at every
position would answer the question, but it would not *be* a rule list: the
point of this model is that an infinite timeline is described by finitely many
rules, so a timeline that depends on a moving parameter must be described by
finitely many rules that depend on it too.

It is. As the period slides, the plan changes shape only where it crosses a
slot boundary, and between two such positions every duration is *affine* in the
position t. So `MovingWindow` emits

    [ (range of positions, prefix rules, cycle rules) ... ]

with a rule's duration written `a + b*(t - lo)` instead of a constant. Drawing
the timeline at a given position is then a binary search and some arithmetic --
no scheduling happens, which is what lets the display follow the period
continuously.

The breakpoints are derived rather than guessed: the candidates are the slot
boundaries the plans themselves reveal, and the positions at which the period's
far edge reaches them. Each resulting regime is then *certified* against the
scheduler at positions it was not fitted on, and split further if it does not
hold (`_fit`); which side of a breakpoint the breakpoint itself falls on is
settled the same way (`_settle_edges`). `uv run test.py --verify` re-checks the
whole sweep, position by position.

Nothing forbids the plan from changing abruptly as the period slides: the
period is continuous, the plan is not, and at a few positions the whole
timeline legitimately re-phases. Only the *rules* have to be finite.
"""

import sys
import tkinter as tk
from bisect import bisect_right
from dataclasses import dataclass, field
from fractions import Fraction
from itertools import accumulate
from math import exp, inf, log

MAX_RULES = 50
IDLE_COLOR = "#F0F0F0"

# A window whose end is FOREVER never closes.  Using None rather than math.inf
# keeps every arithmetic value in the scheduler an exact Fraction.
FOREVER = None


# --------------------------------------------------------------------------- #
#  helpers
# --------------------------------------------------------------------------- #

def frac(x):
    """Exact Fraction from int / str / Fraction, sane one from float."""
    if isinstance(x, Fraction):
        return x
    if isinstance(x, float):
        return Fraction(x).limit_denominator(10 ** 9)
    return Fraction(x)


def ceil_to(x, step):
    return Fraction(-((-x) // step)) * step


def human(d, unit_seconds=60):
    """Pretty-print a duration expressed in minutes."""
    total = frac(d) * unit_seconds
    if total.denominator != 1:
        return f"{float(d):.4g}min"
    s = int(total)
    sign, s = ("-", -s) if s < 0 else ("", s)
    h, r = divmod(s, 3600)
    m, sec = divmod(r, 60)
    out = []
    if h:
        out.append(f"{h}h")
    if m:
        out.append(f"{m}min")
    if sec or not out:
        out.append(f"{sec}s")
    return sign + " ".join(out)


def stamp(t):
    """Absolute time label, e.g. 1h 20min."""
    return human(t)


# --------------------------------------------------------------------------- #
#  data model
# --------------------------------------------------------------------------- #

class Task:
    def __init__(self, name, priority, min_time, color):
        self.name = str(name)
        self.priority = frac(priority)
        self.min_time = frac(min_time)
        self.color = color
        if self.priority < 0:
            raise ValueError(f"{name}: negative priority")
        if self.min_time <= 0:
            raise ValueError(f"{name}: min_time must be > 0")


@dataclass(frozen=True)
class Slot:
    """A rule: run `task` for `duration`."""
    task: str
    duration: Fraction
    color: str = "#DDDDDD"

    def __str__(self):
        return f"{self.task} {human(self.duration)}"


@dataclass(frozen=True)
class Placement:
    """A concrete piece of timeline."""
    task: str
    start: Fraction
    end: Fraction
    color: str = "#DDDDDD"

    @property
    def duration(self):
        return self.end - self.start


@dataclass
class Plan:
    """`prefix` once, then `cycle` forever, starting at `start`."""
    start: Fraction
    prefix: list
    cycle: list
    shares: dict = field(default_factory=dict)

    def __post_init__(self):
        self.period = sum((s.duration for s in self.cycle), Fraction(0))
        self.cycle_start = self.start + sum((s.duration for s in self.prefix),
                                            Fraction(0))
        self._pre_off = [Fraction(0), *accumulate(s.duration for s in self.prefix)]
        self._cyc_off = [Fraction(0), *accumulate(s.duration for s in self.cycle)]

    def task_at(self, t):
        """Which task occupies instant t?  O(log rules)."""
        t = frac(t)
        if t < self.start:
            return None
        if t < self.cycle_start:
            return self.prefix[bisect_right(self._pre_off, t - self.start) - 1].task
        if not self.period:
            return None
        k = (t - self.cycle_start) % self.period
        return self.cycle[bisect_right(self._cyc_off, k) - 1].task


# --------------------------------------------------------------------------- #
#  the scheduler
# --------------------------------------------------------------------------- #

class Scheduler:
    """Builds the finite rule list."""

    def __init__(self, tasks, resolution=None, max_lag=None,
                 tau=None, max_boost=6, field_floor=Fraction(1, 10),
                 max_reach=None):
        tasks = list(tasks)
        active = [t for t in tasks if t.priority > 0]
        if not active:
            raise ValueError("no task with a positive priority")
        total = sum(t.priority for t in active)

        self.tasks = active
        self.p = {t.name: t.priority / total for t in active}
        self.minimum = {t.name: t.min_time for t in active}
        self.color = {t.name: t.color for t in active}
        self.resolution = frac(resolution) if resolution else None
        # smallest period able to give every task one slot >= its minimum
        self.min_period = max(self.minimum[n] / self.p[n] for n in self.p)

        # --- influence field -------------------------------------------- #
        # tau        : how far (in time) an exclusion is felt, and how fast an
        #              abnormal imbalance is forgotten. One period by default.
        # max_boost  : how much longer than usual a slot may get at the very
        #              edge of an exclusion. Caps the local dominance, and turns
        #              the linear amplitude L/tau into a logarithmic
        #              compensation.
        # field_floor: influence below this is dropped, so the field has a
        #              finite reach and the rule list stays finite.
        # max_reach  : hard bound on that reach. An exclusion that never ends
        #              would otherwise carry an infinite amplitude; since the
        #              boost saturates at max_boost, only the width of the
        #              saturated zone would grow, and it grows like tau*ln(a).
        #              Capping it just states that past a few tau, "very long"
        #              and "forever" are the same thing.
        self.tau = frac(tau) if tau is not None else self.min_period
        self.max_boost = frac(max_boost)
        self.field_floor = frac(field_floor)
        self.max_reach = frac(max_reach) if max_reach is not None else 6 * self.tau
        self.max_amp = float(self.field_floor) * (
            exp(float(self.max_reach) / float(self.tau)) - 1.0)
        self.field = {}
        self.field_end = None

        # optional hard bound on the virtual clock; `_relax` normally does the
        # job on its own, so this stays off unless asked for
        self.max_lag = frac(max_lag) if max_lag is not None else None

    # ---------------- shares, restricted to a set of tasks ---------------- #

    def _shares(self, allowed):
        total = sum(self.p[n] for n in allowed)
        return {n: self.p[n] / total for n in allowed}

    def _period(self, allowed):
        p = self._shares(allowed)
        return max(self.minimum[n] / p[n] for n in allowed)

    # ---------------- the influence field ---------------- #

    def _sources(self, timeline, periods):
        """Every interval during which a task cannot be scheduled.

        A fixed block owned by A excludes every *other* task for its whole
        length; a period window excludes every task it does not allow. These
        are the same event and are treated as one, so a long ban distorts the
        schedule around itself exactly like a long block does.

        An interval that excludes *everybody* (maintenance, a holiday, a window
        allowing nothing) takes the same amount from everyone, creates no
        relative distortion, and is dropped."""
        everyone = set(self.p)
        raw = []
        for p in timeline:
            if p.end > p.start:
                raw.append((p.start, p.end, everyone - {p.task}))
        for w in periods:
            end = inf if w['end'] is FOREVER else w['end']
            if end > w['start']:
                raw.append((w['start'], end, everyone - set(w['allowed'])))

        spans = {n: [] for n in everyone}
        for start, end, excluded in raw:
            if len(excluded) == len(everyone):      # hits everybody equally
                continue
            for n in excluded:
                spans[n].append((start, end))
        return {n: self._merge_spans(s) for n, s in spans.items() if s}

    @staticmethod
    def _merge_spans(spans):
        """Overlapping or touching exclusions are one exclusion: what matters
        is how long a task is kept out, not how many separate rules keep it
        out. A block of A followed by a window that bans B is, for B, a single
        ban of the combined length."""
        out = []
        for start, end in sorted(spans):
            if out and start <= out[-1][1]:
                out[-1] = (out[-1][0], max(out[-1][1], end))
            else:
                out.append((start, end))
        return out

    def _set_field(self, timeline=(), periods=()):
        """Turn the exclusions into the field: for each task, the intervals it
        is kept out of and the amplitude a = L/tau of the compensation owed
        around each of them."""
        tau = float(self.tau)
        self.field = {}
        self.field_end = None
        for name, spans in self._sources(timeline, periods).items():
            entries = []
            for start, end in spans:
                length = inf if end == inf else float(end - start)
                if length < self.minimum[name]:
                    # Shorter than one slot of its own: it cannot have cost the
                    # task a slot, only delayed it -- and a delay is already
                    # repaid exactly by the virtual clock, which hands the task
                    # back every minute it lost the moment the ban lifts.
                    # Compensating it here as well would pay the same debt
                    # twice, and would make a 20s ban swell the neighbouring
                    # slots forever after.
                    continue
                amp = min(length / tau, self.max_amp)
                entries.append((start, end, amp))
                if end == inf:
                    continue        # never re-opens: nothing to compensate after
                reach = tau * log(1.0 + amp / float(self.field_floor))
                stop = end + frac(reach)
                if self.field_end is None or stop > self.field_end:
                    self.field_end = stop
            self.field[name] = entries

    def _boost(self, name, t):
        """How much longer than usual a slot of `name` may be at instant t.

        1 + sum over the intervals it is *excluded from* of (L/tau) e^(-d/tau),
        clipped to max_boost. Symmetric in d: the same ramp before the
        exclusion and after it."""
        spans = self.field.get(name)
        if not spans:
            return Fraction(1)
        tau = float(self.tau)
        acc = 0.0
        for start, end, amp in spans:
            if start <= t <= end:
                d = 0.0
            elif t < start:
                d = float(start - t)
            else:
                d = float(t - end)
            acc += amp * exp(-d / tau)
        if acc <= 0.0:
            return Fraction(1)
        return frac(1.0 + min(acc, float(self.max_boost) - 1.0))

    def _relax(self, v, dt, T, active):
        """Exponential forgetting.

        Whatever sits inside one period is normal scheduling pressure and is
        left untouched. Anything beyond -- the debt a big exclusion creates,
        which no bounded compensation could ever repay -- loses a factor
        e^(-dt/tau) of itself for every dt of freely scheduled time. That is
        what makes the influence of an exclusion decay instead of turning into
        an equally long block of the deprived task.

        Tasks that are currently not allowed keep at most one period of credit,
        otherwise they would come back from a long exclusion and monopolise the
        timeline."""
        if not active:
            return
        lo = min(v[n] for n in active)
        if dt > 0:
            f = frac(exp(-float(dt) / float(self.tau)))
            for n in active:
                over = v[n] - lo - T
                if over > 0:
                    v[n] -= over * (1 - f)
        for n in v:
            if n not in active:
                v[n] = min(max(v[n], lo - T), lo + T)

    # ---------------- one greedy step ---------------- #

    def _pick(self, v, candidates, last=None):
        # most starved task; ties -> biggest share, then name (deterministic).
        # `last` is never picked twice in a row unless it is the only one left:
        # a task that is owed a lot gets a *denser* presence, not one long
        # block that would swallow the whole compensation at once.
        pool = [n for n in candidates if n != last] or list(candidates)
        return min(pool, key=lambda n: (v[n], -self.p[n], n))

    def _chunk(self, name, v, candidates, p=None, boost=None, T=None):
        p = p or self.p
        others = [v[n] for n in candidates if n != name]
        target = min(others) if others else v[name]
        need = p[name] * (target - v[name])     # time to catch the runner-up
        c = max(self.minimum[name], need)
        if boost is not None:                   # local, field-aware sizing
            unit = max(self.minimum[name], p[name] * T)
            c = min(max(c, self.minimum[name] * boost), unit * boost)
        if self.resolution:
            c = ceil_to(c, self.resolution)
        return c

    def _clamp(self, v, t):
        if self.max_lag is None:
            return
        lo, hi = t - self.max_lag, t + self.max_lag
        for n in v:
            v[n] = min(max(v[n], lo), hi)

    @staticmethod
    def _push(slots, task, duration, color):
        """Append a slot, merging it into the previous one when it belongs to
        the same task. Two consecutive slots of one task are a single rule, so
        they must not each cost one: inside a window where only one task is
        allowed, the greedy still steps by `minimum`, and without this the rule
        budget would be spent on invisible seams instead of real alternations."""
        if slots and slots[-1].task == task:
            slots[-1] = Slot(task, slots[-1].duration + duration, color)
        else:
            slots.append(Slot(task, duration, color))

    # ---------------- the repeating cycle ---------------- #

    def steady_cycle(self, allowed):
        """
        The cycle, built on the smallest possible period:

            T          = max(min_i / p_i)          <- provably minimal
            budget_i   = p_i * T  (>= min_i)       <- exact share, by construction

        Inside the period the same greedy runs, with two extra rules: a slot
        never exceeds the task's remaining budget, and it never leaves a
        remainder smaller than the minimum (that crumb could not be placed).

        No field, no forgetting here: this is the undisturbed regime.
        """
        allowed = sorted(allowed)
        p = self._shares(allowed)
        T = self._period(allowed)
        rem = {n: p[n] * T for n in allowed}
        v = {n: Fraction(0) for n in allowed}

        slots = []
        while any(rem[n] > 0 for n in allowed):
            live = [n for n in allowed if rem[n] > 0]
            name = self._pick(v, live)
            c = min(self._chunk(name, v, live, p), rem[name])
            if rem[name] - c < self.minimum[name]:      # no unplaceable crumb
                c = rem[name]
            slots.append(Slot(name, c, self.color[name]))
            v[name] += c / p[name]
            rem[name] -= c
        return slots

    def coarse_cycle(self, allowed):
        """One slot per task: same minimal period and exact shares, but the
        coarsest possible interleaving. Used when the fine cycle needs too
        many rules to stay a practical, finite list."""
        p = self._shares(allowed)
        T = self._period(allowed)
        order = sorted(allowed, key=lambda n: (-p[n], n))
        return [Slot(n, p[n] * T, self.color[n]) for n in order]

    # ---------------- context (pre-placed blocks / periods) ---------------- #

    @staticmethod
    def _normalise_periods(periods):
        """Windows with exact Fraction bounds; an infinite end becomes FOREVER."""
        out = []
        for w in periods:
            end = w.get('end', FOREVER)
            out.append({'start': frac(w['start']),
                        'end': FOREVER if end is FOREVER or end == inf else frac(end),
                        'allowed': set(w['allowed'])})
        return out

    @staticmethod
    def _active_pre(pre, t):
        for p in pre:
            if p.start <= t < p.end:
                return p
        return None

    def _allowed_at(self, periods, t):
        for w in periods:
            if w['start'] <= t and (w['end'] is FOREVER or t < w['end']):
                return [n for n in self.p if n in w['allowed']]
        return list(self.p)

    @staticmethod
    def _next_boundary(pre, periods, t):
        """Next instant where the context changes, or None if it never does."""
        best = None
        for p in pre:
            if p.start > t and (best is None or p.start < best):
                best = p.start
        for w in periods:
            for b in (w['start'], w['end']):
                if b is not FOREVER and b > t and (best is None or b < best):
                    best = b
        return best

    def _blocked_from(self, name, pre, periods, t):
        """First instant after t at which `name` itself can no longer run.

        Not every context change stops every task. A window that bans B does
        not interrupt A, so a chunk of A must be allowed to run straight
        through it: what bounds a chunk is where *its own* task is turned away,
        not where the context happens to change. Treating the two as one is
        what makes a 20s ban of B punch an unfillable hole into A's slot,
        since no 10min minimum fits in 20s."""
        best = None
        for p in pre:                                   # any block stops everyone
            if p.start > t and (best is None or p.start < best):
                best = p.start
        bounds = sorted({b for w in periods for b in (w['start'], w['end'])
                         if b is not FOREVER and b > t})
        for b in bounds:
            if best is not None and b >= best:
                break
            if name not in self._allowed_at(periods, b):
                return b
        return best

    def _next_placeable(self, missing, pre, periods, t):
        """Earliest instant after t at which a task that cannot be placed now
        could be -- because it is let back in, or because from there it has
        room for its whole minimum.

        This is the only reason to end a chunk early: a context change that
        makes no new task placeable changes nothing about the decision, and
        cutting there would just split a slot in two for nothing."""
        if not missing:
            return None
        bounds = sorted({b for w in periods for b in (w['start'], w['end'])
                         if b is not FOREVER and b > t}
                        | {p.end for p in pre if p.end > t})
        for b in bounds:
            for n in missing:
                if n not in self._allowed_at(periods, b):
                    continue
                room = self._blocked_from(n, pre, periods, b)
                if room is None or room - b >= self.minimum[n]:
                    return b
        return None

    # ---------------- the plan ---------------- #

    def plan(self, timeline=(), periods=(), t_now=0, lookback=None,
             max_rules=MAX_RULES):
        """
        Phase 1: walk the disturbed part of the timeline (pre-placed blocks,
                 period windows, and the tail of the influence field) with the
                 field-aware greedy  -> prefix.
        Phase 2: once nothing is left to distort the schedule, settle what is
                 still owed -> prefix, then attach the analytic cycle -> cycle.
        """
        t_now = frac(t_now)
        timeline = sorted(timeline, key=lambda p: p.start)
        periods = self._normalise_periods(periods)
        self._set_field(timeline, periods)

        past = [p for p in timeline if p.end <= t_now]
        pre = [p for p in timeline if p.end > t_now]     # committed / still ahead

        # --- debt inherited from the already-placed past ---------------- #
        window = frac(lookback) if lookback is not None else self.min_period
        w_start = t_now - window
        if past:
            w_start = max(w_start, min(p.start for p in past))
        w_start = min(w_start, t_now)
        elapsed = t_now - w_start

        served = {n: Fraction(0) for n in self.p}
        for p in past:
            if p.task in served:
                served[p.task] += max(Fraction(0),
                                      min(p.end, t_now) - max(p.start, w_start))

        lag = {n: served[n] / self.p[n] - elapsed for n in self.p}
        base = min(lag.values())
        t = t_now
        v = {n: t + lag[n] - base for n in self.p}      # only differences matter
        self._clamp(v, t)

        slots = []
        last = None
        free_tail = False
        steps = 0
        max_steps = 200 * max_rules                     # safety valve: slots are
                                                        # merged, so their count
                                                        # no longer bounds the walk

        def owed(n):
            """How much of its minimum the slot of `n` would still owe.

            Consecutive slots of one task are one slot, so a task that is
            already running has paid its minimum and may be cut anywhere; only
            a slot that is *starting* owes the whole of it."""
            if n == last and slots and slots[-1].task == n:
                return max(Fraction(0), self.minimum[n] - slots[-1].duration)
            return self.minimum[n]

        # --- phase 1: disturbed part of the timeline --------------------- #
        while len(slots) < max_rules and steps < max_steps:
            steps += 1
            allowed = self._allowed_at(periods, t)
            T = self._period(allowed) if allowed else self.min_period

            block = self._active_pre(pre, t)
            if block:                                   # cannot be moved
                d = block.end - t
                self._push(slots, block.task, d, block.color)
                if block.task in v:
                    v[block.task] += d / self.p[block.task]
                t = block.end
                last = block.task
                free_tail = False
                self._relax(v, 0, T, allowed)           # no forgetting here:
                self._clamp(v, t)                       # the block is not ours
                continue

            limit = self._next_boundary(pre, periods, t)
            if limit is None and (self.field_end is None or t >= self.field_end):
                break                                   # nothing left to disturb

            # each task is bounded by where *it* is turned away, not by the
            # next context change
            room = {n: self._blocked_from(n, pre, periods, t) for n in allowed}
            fitting = [n for n in allowed
                       if room[n] is None or room[n] - t >= owed(n)]

            if not fitting:                             # nothing can be placed here
                if limit is None:
                    break
                gap = limit - t
                if free_tail and slots:                 # stretch the last slot
                    tail = slots[-1]
                    slots[-1] = Slot(tail.task, tail.duration + gap, tail.color)
                    v[tail.task] += gap / self.p[tail.task]
                else:                                   # or leave a hole
                    self._push(slots, "IDLE", gap, IDLE_COLOR)
                    last = None
                    free_tail = False
                t += gap                                # == limit, but keeps t a Fraction
                continue

            name = self._pick(v, fitting, last)
            boost = self._boost(name, t)
            c = self._chunk(name, v, fitting, boost=boost, T=T)
            if room[name] is not None:                  # forced cut
                c = min(c, room[name] - t)
            back = self._next_placeable([n for n in self.p if n not in fitting],
                                        pre, periods, t)
            if back is not None:                        # optional cut: hand the
                c = min(c, max(back, t + owed(name)) - t)   # timeline back as soon
                                                        # as a rival can take it,
                                                        # once the minimum is paid
            self._push(slots, name, c, self.color[name])
            # charged at the boosted rate: the extra time is a genuinely higher
            # local share, not a debt to be taken back once the field is gone
            v[name] += c / (self.p[name] * boost)
            t += c
            last = name
            free_tail = True
            self._relax(v, c, T, allowed)
            self._clamp(v, t)

        # --- phase 2: settle what is still owed, then repeat forever ----- #
        cycle = []
        allowed = self._allowed_at(periods, t)
        if allowed and len(slots) < max_rules:
            T = self._period(allowed)
            horizon = t + 4 * T                         # settling is bounded
            while len(slots) < max_rules and t < horizon:
                spread = (max(v[n] for n in allowed)
                          - min(v[n] for n in allowed))
                if spread <= T:
                    break                               # square again
                name = self._pick(v, allowed, last)
                boost = self._boost(name, t)
                c = self._chunk(name, v, allowed, boost=boost, T=T)
                self._push(slots, name, c, self.color[name])
                v[name] += c / (self.p[name] * boost)
                t += c
                last = name
                self._relax(v, c, T, allowed)
                self._clamp(v, t)
            cycle = self.steady_cycle(allowed)
            if len(cycle) > max_rules:
                cycle = self.coarse_cycle(allowed)
            cycle = self._phase(cycle, v, allowed, last)

        prefix, cycle = self._tidy(slots, cycle)
        period = sum((s.duration for s in cycle), Fraction(0))
        shares = {}
        if period:
            for s in cycle:
                shares[s.task] = shares.get(s.task, Fraction(0)) + s.duration / period
        return Plan(start=t_now, prefix=prefix, cycle=cycle, shares=shares)

    # ---------------- internals ---------------- #

    def _phase(self, cycle, v, allowed, last):
        """Attach the cycle in the phase the walk would have gone on with.

        `steady_cycle` starts from a blank slate, so it always opens with the
        same task; if the prefix left another one starved, opening there hands
        the first task its slot twice in a row. They merge into one block of
        twice the minimum -- the coarse scale the model exists to avoid --
        even though simply starting the cycle one slot later costs nothing."""
        if not cycle:
            return cycle
        first = self._pick(v, allowed, last)
        for i, s in enumerate(cycle):
            if s.task == first:
                return cycle[i:] + cycle[:i]
        return cycle

    @staticmethod
    def _merge_run(slots):
        out = []
        for s in slots:
            if out and out[-1].task == s.task:
                out[-1] = Slot(s.task, out[-1].duration + s.duration, s.color)
            else:
                out.append(s)
        return out

    def _tidy(self, prefix, cycle):
        """No two consecutive slots of the same task, wrap-around included."""
        prefix, cycle = self._merge_run(list(prefix)), self._merge_run(list(cycle))

        # the cycle wraps onto itself: move the head out, merge it into the tail
        while len(cycle) > 1 and cycle[0].task == cycle[-1].task:
            head = cycle.pop(0)
            cycle[-1] = Slot(cycle[-1].task,
                             cycle[-1].duration + head.duration, head.color)
            prefix.append(head)
        prefix = self._merge_run(prefix)

        # prefix/cycle junction: rotate the cycle once (timeline unchanged)
        for _ in range(len(cycle)):
            if not (prefix and len(cycle) > 1 and prefix[-1].task == cycle[0].task):
                break
            head = cycle.pop(0)
            cycle.append(head)
            prefix[-1] = Slot(head.task,
                              prefix[-1].duration + head.duration, head.color)
        return prefix, cycle


# --------------------------------------------------------------------------- #
#  a moving period: the dynamic rule list
# --------------------------------------------------------------------------- #
#
# One period slides along the timeline. Re-running the scheduler at every
# position would answer the question, but it would not *be* a rule list: the
# whole point is that the timeline is described by finitely many rules, so a
# timeline that depends on a moving parameter must be described by finitely
# many rules that depend on it too.
#
# It is: as the window slides, the plan changes shape only at the instants
# where the window crosses a slot boundary of the undisturbed schedule, and
# between two such instants every duration is *affine* in the window position
# (the disturbed slot simply grows with it). So the dynamic rule list is
#
#     [ (window range, prefix rules, cycle rules) ... ]
#
# where a rule's duration is `a + b*(t - lo)` instead of a constant. Drawing at
# a given window position is then pure arithmetic -- no scheduling is done.
#
# The breakpoints are derived, not guessed: the candidates are the slot
# boundaries `b` of the undisturbed plan and the positions `b - width` at which
# the window's far edge reaches them, and each resulting regime is *certified*
# against the scheduler itself before it is accepted (see `_fit`).


@dataclass(frozen=True)
class Rule:
    """A slot whose duration is affine in the window position t."""
    task: str
    a: Fraction                 # duration when the window sits at `lo`
    b: Fraction                 # how much of t it absorbs (0 or 1 in practice)
    color: str = "#DDDDDD"

    def at(self, t, lo):
        return self.a + self.b * (t - lo)

    def text(self, lo):
        if not self.b:
            return f"{self.task} {human(self.a)}"
        sign = "+" if self.b > 0 else "-"
        factor = "" if abs(self.b) == 1 else f"{float(abs(self.b)):g}*"
        return (f"{self.task} {human(self.a)} {sign} "
                f"{factor}(t - {human(lo)})")


@dataclass
class Regime:
    """The rule list while the window start lies in [lo, hi) -- or in
    (lo, hi) when `lo_open`, i.e. when the breakpoint instant itself still
    obeys the previous rules."""
    lo: Fraction
    hi: Fraction
    prefix: list
    cycle: list
    lo_open: bool = False

    @property
    def label(self):
        return f"{'(' if self.lo_open else '['}{stamp(self.lo)}, {stamp(self.hi)})"

    @property
    def prefix_text(self):
        return " | ".join(r.text(self.lo) for r in self.prefix) or "(none)"

    @property
    def cycle_text(self):
        return " | ".join(r.text(self.lo) for r in self.cycle) + " | repeat"

    def holds_at(self, t):
        return (self.lo < t or (self.lo == t and not self.lo_open)) and t < self.hi

    def blocks(self, t):
        """The rules evaluated at window position t -> (prefix, cycle)."""
        as_blocks = lambda rules: [{'name': r.task, 'duration': r.at(t, self.lo),
                                    'color': r.color} for r in rules]
        return as_blocks(self.prefix), as_blocks(self.cycle)


class MovingWindow:
    """The dynamic rule list of a schedule disturbed by one sliding window.

    `width`   how long the window lasts
    `allowed` which tasks it accepts
    `span`    the displayed timeline -- the range of positions the rules cover,
              which the window sweeps and then starts over from
    """

    # a regime is accepted only if the affine fit reproduces the scheduler at
    # these positions inside it (as fractions of the regime's width)
    PROBES = (Fraction(1, 8), Fraction(1, 3), Fraction(1, 2), Fraction(7, 8))
    SAMPLES = 12
    MAX_ROUNDS = 6

    def __init__(self, tasks, width, allowed, span, **kw):
        self.tasks = list(tasks)
        self.width = frac(width)
        self.allowed = list(allowed)
        self.span = frac(span)
        self.kw = kw
        self.regimes = self._build()

    # ---------------- the scheduler, at one window position ---------------- #

    def plan_at(self, t):
        t = frac(t)
        periods = [{'start': 0, 'end': t, 'allowed': [x.name for x in self.tasks]},
                   {'start': t, 'end': t + self.width, 'allowed': self.allowed},
                   {'start': t + self.width, 'end': inf,
                    'allowed': [x.name for x in self.tasks]}]
        return Scheduler(self.tasks, **self.kw).plan(periods=periods, t_now=0)

    # ---------------- breakpoints ---------------- #

    def _edges_of(self, t, lo=None, hi=None):
        """The breakpoints the plan at window position t reveals: its own slot
        boundaries, and the positions at which the window's far edge would
        reach them."""
        plan = self.plan_at(t)
        lo = frac(0) if lo is None else lo
        hi = self.span if hi is None else hi
        out, acc = set(), plan.start
        for s in list(plan.prefix) + list(plan.cycle):
            acc += s.duration
            for c in (acc, acc - self.width):
                if lo < c < hi:
                    out.add(frac(c))
        return out

    def _candidates(self, lo, hi, n):
        """Sample the span and let the plans themselves say where they break."""
        out = set()
        for i in range(1, n):
            out |= self._edges_of(lo + Fraction(i, n) * (hi - lo), lo, hi)
        return out

    # ---------------- fitting one regime ---------------- #

    @staticmethod
    def _shape(plan):
        return ([s.task for s in plan.prefix], [s.task for s in plan.cycle])

    def _fit(self, lo, hi):
        """Affine rules valid on [lo, hi), or None if none are.

        Two positions determine the affine form; the others check it. A regime
        is never accepted on the strength of the samples that built it -- if
        the plan does not in fact vary linearly with the window across the
        whole range, the fit is rejected and the range is split further."""
        t1, t2 = lo + (hi - lo) / 4, lo + 3 * (hi - lo) / 4
        p1, p2 = self.plan_at(t1), self.plan_at(t2)
        if self._shape(p1) != self._shape(p2):
            return None

        def rules(s1, s2):
            out = []
            for x, y in zip(s1, s2):
                b = (y.duration - x.duration) / (t2 - t1)
                out.append(Rule(x.task, x.duration + b * (lo - t1), b, x.color))
            return out

        regime = Regime(lo, hi, rules(p1.prefix, p2.prefix),
                        rules(p1.cycle, p2.cycle))
        for f in self.PROBES:
            got = self.plan_at(lo + f * (hi - lo))
            want_pre, want_cyc = regime.blocks(lo + f * (hi - lo))
            if (self._shape(got) != self._shape(p1)
                    or [s.duration for s in got.prefix] != [b['duration'] for b in want_pre]
                    or [s.duration for s in got.cycle] != [b['duration'] for b in want_cyc]):
                return None
        return regime

    def _build(self):
        edges = sorted({frac(0), self.span}
                       | self._candidates(frac(0), self.span, self.SAMPLES))
        for _ in range(self.MAX_ROUNDS):
            out, failed = [], []
            for lo, hi in zip(edges, edges[1:]):
                if hi <= lo:
                    continue
                fit = self._fit(lo, hi)
                if fit is None:
                    failed.append((lo, hi))
                else:
                    out.append(fit)
            if not failed:
                return self._settle_edges(self._merge(out))
            # the plans *inside* a range that would not fit are the ones that
            # know where it breaks
            extra = set()
            for lo, hi in failed:
                extra |= self._candidates(lo, hi, self.SAMPLES)
            if extra <= set(edges):
                raise ValueError(
                    "no affine rule describes the window on "
                    + ", ".join(f"[{human(a)}, {human(b)})" for a, b in failed)
                    + "; the plan does not vary linearly with the window there")
            edges = sorted(set(edges) | extra)
        raise ValueError("the dynamic rule list did not settle")

    @staticmethod
    def _merge(regimes):
        """Adjacent ranges that turn out to obey the same rules are one rule.

        A candidate breakpoint is only ever a *guess* that the plan might
        change there; most of them turn out not to be breakpoints at all, and
        keeping them would pad the rule list with copies of the same rule."""
        out = []
        for r in regimes:
            prev = out[-1] if out else None
            same = (prev is not None and prev.hi == r.lo
                    and len(prev.prefix) == len(r.prefix)
                    and len(prev.cycle) == len(r.cycle)
                    and all(x.task == y.task and x.b == y.b
                            and x.at(r.lo, prev.lo) == y.a
                            for x, y in zip(prev.prefix + prev.cycle,
                                            r.prefix + r.cycle)))
            if same:
                out[-1] = Regime(prev.lo, r.hi, prev.prefix, prev.cycle)
            else:
                out.append(r)
        return out

    def _timeline(self, prefix, cycle):
        return [(b['name'], b['start'], b['duration'])
                for b in generate_schedule(prefix, cycle, 2 * self.span)]

    def _reproduces(self, regime, t):
        plan = self.plan_at(t)
        as_blocks = lambda slots: [{'name': s.task, 'duration': s.duration,
                                    'color': s.color} for s in slots]
        return (self._timeline(*regime.blocks(t))
                == self._timeline(as_blocks(plan.prefix), as_blocks(plan.cycle)))

    def _settle_edges(self, regimes):
        """Decide which side of a breakpoint the breakpoint itself is on.

        A regime is fitted from positions strictly inside it, so nothing yet
        says whether the instant the plan changes at obeys the new rules or
        the old ones. Where it does not obey the new ones, it belongs to the
        previous regime and the range opens instead of closing."""
        for r in regimes[1:]:
            r.lo_open = not self._reproduces(r, r.lo)
        return regimes

    # ---------------- lookup ---------------- #

    def regime_at(self, t):
        t = frac(t) % self.span                     # the window starts anew
        lo, hi = 0, len(self.regimes) - 1
        while lo < hi:                              # O(log regimes), no scheduling
            mid = (lo + hi + 1) // 2
            r = self.regimes[mid]
            if r.lo < t or (r.lo == t and not r.lo_open):
                lo = mid
            else:
                hi = mid - 1
        return self.regimes[lo]

    def blocks_at(self, t):
        r = self.regime_at(t)
        return r.blocks(frac(t) % self.span)

    def text_at(self, t):
        r = self.regime_at(t)
        lines = [f"while the period starts in {r.label}:"]
        if r.prefix:
            lines.append("Prefix:")
            lines += [f"- task {x.text(r.lo)}" for x in r.prefix]
        if r.cycle:
            lines.append("Cycle:")
            lines += [f"- task {x.text(r.lo)}" for x in r.cycle]
            lines.append("- repeat")
        return "\n".join(lines)

    def rules_text(self):
        """The whole dynamic rule list -- every regime, not just the one the
        window currently sits in. This is what the case *is*: the timeline is
        read off these without scheduling again."""
        lines = []
        for r in self.regimes:
            lines.append(f"While the period starts in {r.label}:")
            lines.append(f"  Prefix: {r.prefix_text}")
            lines.append(f"  Cycle:  {r.cycle_text}")
        return "\n".join(lines)


# --------------------------------------------------------------------------- #
#  presentation
# --------------------------------------------------------------------------- #

class ToolTip:
    """Manages floating info bubbles for canvas elements."""

    def __init__(self, canvas):
        self.canvas = canvas
        self.tooltip_window = None
        self.data = {}

        self.canvas.tag_bind("task_panel", "<Enter>", self.show_tooltip)
        self.canvas.tag_bind("task_panel", "<Leave>", self.hide_tooltip)
        self.canvas.tag_bind("task_panel", "<Motion>", self.move_tooltip)

    def register(self, item_id, text):
        self.data[item_id] = text

    def show_tooltip(self, event):
        current = self.canvas.find_withtag("current")
        if not current:
            return

        text = self.data.get(current[0])
        if not text:
            return

        x, y = event.x_root + 15, event.y_root + 15
        self.tooltip_window = tk.Toplevel(self.canvas)
        self.tooltip_window.wm_overrideredirect(True)
        self.tooltip_window.wm_geometry(f"+{x}+{y}")

        label = tk.Label(self.tooltip_window, text=text, background="#ffffe0",
                         relief="solid", borderwidth=1, justify="left",
                         font=("Arial", 9))
        label.pack()

    def hide_tooltip(self, event):
        if self.tooltip_window:
            self.tooltip_window.destroy()
            self.tooltip_window = None

    def move_tooltip(self, event):
        if self.tooltip_window:
            x, y = event.x_root + 15, event.y_root + 15
            self.tooltip_window.wm_geometry(f"+{x}+{y}")


def get_schedule_rules(tasks, pre_placed=None, periods=None, t_now=0, **kw):
    """Finite rule list: (prefix_blocks, cycle_blocks). Caps at MAX_RULES."""
    timeline = [Placement(p['name'], frac(p['start']),
                          frac(p['start']) + frac(p['duration']),
                          p.get('color', '#CCCCCC'))
                for p in (pre_placed or [])]
    plan = Scheduler(tasks, **kw).plan(timeline=timeline, periods=periods or [],
                                       t_now=t_now, max_rules=MAX_RULES)
    as_blocks = lambda slots: [{'name': s.task, 'duration': s.duration,
                                'color': s.color} for s in slots]
    return as_blocks(plan.prefix), as_blocks(plan.cycle), plan


def generate_schedule(prefix_blocks, cycle_blocks, total_duration,
                      start=Fraction(0)):
    """Generates the timeline up to 'total_duration' by unrolling the rules."""
    schedule = []
    time_now = frac(start)

    def append_block(block_template):
        nonlocal time_now
        if schedule and schedule[-1]['name'] == block_template['name']:
            schedule[-1]['duration'] += block_template['duration']
        else:
            schedule.append({'name': block_template['name'],
                             'start': time_now,
                             'duration': block_template['duration'],
                             'color': block_template['color']})
        time_now += block_template['duration']

    for block in prefix_blocks:
        if time_now >= total_duration:
            break
        append_block(block)

    if not cycle_blocks:
        return schedule

    while time_now < total_duration:
        for block in cycle_blocks:
            if time_now >= total_duration:
                break
            append_block(block)

    return schedule


def copy_to_clipboard(root, title, prefix_blocks, cycle_blocks, plan):
    lines = [title, "Rules:"]
    if prefix_blocks:
        lines.append("Prefix:")
        for block in prefix_blocks:
            lines.append(f"- task {block['name']} {human(block['duration'])}")
    if cycle_blocks:
        lines.append("Cycle:")
        for block in cycle_blocks:
            lines.append(f"- task {block['name']} {human(block['duration'])}")
        lines.append("- repeat")
        lines.append(f"Period: {human(plan.period)}")
        lines.append("Shares: " + ", ".join(
            f"{n} {float(s) * 100:.4g}%" for n, s in sorted(plan.shares.items())))
    else:
        lines.append("(No cycle found - capped by rule limit or bounded timeline)")

    root.clipboard_clear()
    root.clipboard_append("\n".join(lines))


def draw_schedules(root, canvas, test_cases, window_width=900):
    y_offset = 20
    px_per_min = 4
    margin_left = 90
    margin_right = 30
    row_duration = (window_width - margin_left - margin_right) // px_per_min
    row_height = 40
    row_spacing = 20

    tooltip = ToolTip(canvas)

    for case in test_cases:
        title, tasks, total_duration, pre_placed, periods = case[:5]
        options = case[5] if len(case) > 5 else {}
        px = case[6] if len(case) > 6 else px_per_min
        row_duration = (window_width - margin_left - margin_right) // px

        prefix_blocks, cycle_blocks, plan = get_schedule_rules(
            tasks, pre_placed, periods, **options)
        schedule = generate_schedule(prefix_blocks, cycle_blocks, total_duration,
                                     start=plan.start)

        btn = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                        command=lambda t=title, p=prefix_blocks, c=cycle_blocks,
                        pl=plan: copy_to_clipboard(root, t, p, c, pl))
        canvas.create_window(15, y_offset, window=btn, anchor="nw")

        if cycle_blocks:
            summary = (f"period {human(plan.period)}   |   " + ", ".join(
                f"{n} {float(s) * 100:.4g}%" for n, s in sorted(plan.shares.items())))
        else:
            summary = f"no cycle found (capped at {MAX_RULES} rules)"

        txt_id = canvas.create_text(margin_left, y_offset, text=title,
                                    font=("Arial", 11, "bold"), anchor="nw")
        bbox = canvas.bbox(txt_id)
        sub_id = canvas.create_text(margin_left, bbox[3] + 2, text=summary,
                                    font=("Arial", 9), fill="#555555", anchor="nw")
        y_offset = canvas.bbox(sub_id)[3] + 18

        max_row_idx = 0
        for block in schedule:
            block_start = block['start']
            remaining = block['duration']

            original_start = block['start']
            original_end = original_start + block['duration']
            hover_info = (f"Task {block['name']}\n"
                          f"Start: {stamp(original_start)}\n"
                          f"End: {stamp(original_end)}\n"
                          f"Duration: {human(block['duration'])}")

            while remaining > 0:
                row_idx = int(block_start // row_duration)
                start_in_row = block_start % row_duration
                time_in_row = min(remaining, row_duration - start_in_row)

                x1 = margin_left + float(start_in_row) * px
                x2 = margin_left + float(start_in_row + time_in_row) * px
                y1 = y_offset + row_idx * (row_height + row_spacing)
                y2 = y1 + row_height

                rect_id = canvas.create_rectangle(x1, y1, x2, y2, fill=block['color'],
                                                  outline="black", tags="task_panel")
                tooltip.register(rect_id, hover_info)

                if (x2 - x1) > 30:
                    text_id = canvas.create_text((x1 + x2) / 2, (y1 + y2) / 2,
                                                 text=block['name'],
                                                 font=("Arial", 10), tags="task_panel")
                    tooltip.register(text_id, hover_info)

                remaining -= time_in_row
                block_start += time_in_row
                max_row_idx = max(max_row_idx, row_idx)

        for row_idx in range(max_row_idx + 1):
            y1 = y_offset + row_idx * (row_height + row_spacing)
            canvas.create_text(margin_left - 8, y1 + row_height / 2,
                               text=stamp(row_idx * row_duration),
                               font=("Arial", 8), fill="#777777", anchor="e")

        y_offset += (max_row_idx + 1) * (row_height + row_spacing) + 40

    return y_offset


class MovingWindowPanel:
    """A timeline whose one period slides, redrawn from the dynamic rules.

    Every frame evaluates the rule list at the new window position -- affine
    arithmetic and a binary search over the regimes. The scheduler is not run
    again; it ran once, when the rule list was built."""

    ROW_H = 44
    FPS = 25
    BUTTON_H = 45          # the two-line button, so a one-line title still
    TITLE_FONT = ("Arial", 10, "bold")   # leaves the gutter room for it

    def __init__(self, parent, x, y, width, title, mw, sweep_seconds=25,
                 start=0):
        self.mw = mw
        self.title = title
        self.t = frac(start)
        self.step_size = mw.span / (sweep_seconds * self.FPS)
        self.margin = 12
        # the same left button column the static cases use, so "Copy Rules" is
        # in the one place the eye already looks for it
        self.gutter = 75
        self.px = float(width - self.gutter - self.margin) / float(mw.span)
        self.width = width
        self.canvas = tk.Canvas(parent, width=width, height=self.ROW_H + 108,
                                bg="white", highlightthickness=0)
        parent.create_window(x, y, window=self.canvas, anchor="nw")
        self._measure_header()
        self.canvas.configure(height=self.height)
        # outside the redrawn frame: `_draw` deletes only what it tagged, so
        # the button survives every tick instead of being rebuilt 25x a second
        self.button = tk.Button(self.canvas, text="Copy\nRules", cursor="hand2",
                                command=self._copy_rules)
        self.canvas.create_window(0, 4, window=self.button, anchor="nw")
        self._tick()

    def _copy_rules(self):
        c = self.canvas
        c.clipboard_clear()
        c.clipboard_append("\n".join([
            self.title,
            "",
            f"Rules ({human(self.mw.width)} period accepting only "
            f"{'/'.join(self.mw.allowed)}; each duration is affine in the "
            f"period's position t):",
            self.mw.rules_text(),
            "",
            f"Period now at {stamp(self.t)} -> "
            f"{self.mw.regime_at(self.t).label}",
        ]))

    def _measure_header(self):
        """Stack the header lines under the title, whatever height it comes to.

        The title is as many lines as the case wrote it in, so the rule lines
        below it cannot sit at fixed offsets without overlapping it."""
        c = self.canvas
        probe = c.create_text(self.gutter, 4, text=self.title, anchor="nw",
                              font=self.TITLE_FONT)
        title_bottom = c.bbox(probe)[3]
        c.delete(probe)
        self.y_rules = title_bottom + 6
        self.y_prefix = self.y_rules + 18
        self.y_cycle = self.y_prefix + 16
        self.top = max(self.y_cycle + 24, self.BUTTON_H + 12)
        self.height = self.top + self.ROW_H + 26

    def _x(self, t):
        return self.gutter + float(t) * self.px

    def _tick(self):
        self._draw()
        self.t += self.step_size
        if self.t + self.mw.width >= self.mw.span:  # the period itself -- not
            self.t = frac(0)                        # merely its start -- reached
                                                    # the end of the displayed
                                                    # timeline: start anew
        self.canvas.after(int(1000 / self.FPS), self._tick)

    def _draw(self):
        c = self.canvas
        c.delete("frame")
        regime = self.mw.regime_at(self.t)
        prefix, cycle = self.mw.blocks_at(self.t)
        schedule = generate_schedule(prefix, cycle, self.mw.span)

        c.create_text(self.gutter, 4, text=self.title, anchor="nw",
                      font=self.TITLE_FONT, tags="frame")
        c.create_text(self.gutter, self.y_rules, anchor="nw", font=("Arial", 9),
                      fill="#333333", tags="frame",
                      text=f"period at {stamp(self.t)}   ->   rules for "
                           f"[{stamp(regime.lo)}, {stamp(regime.hi)})")
        c.create_text(self.gutter, self.y_prefix, anchor="nw",
                      font=("Courier", 9), fill="#7A0000", tags="frame",
                      text="Prefix: " + regime.prefix_text)
        c.create_text(self.gutter, self.y_cycle, anchor="nw",
                      font=("Courier", 9), fill="#00407A", tags="frame",
                      text="Cycle:  " + regime.cycle_text)

        top = self.top
        for block in schedule:
            x1, x2 = self._x(block['start']), self._x(block['start']
                                                      + block['duration'])
            x2 = min(x2, self._x(self.mw.span))
            if x2 <= x1:
                continue
            c.create_rectangle(x1, top, x2, top + self.ROW_H,
                               fill=block['color'], outline="black",
                               tags="frame")
            if x2 - x1 > 26:
                c.create_text((x1 + x2) / 2, top + self.ROW_H / 2,
                              text=block['name'], font=("Arial", 9),
                              tags="frame")

        # the sliding period itself, drawn over the timeline it is bending
        wx1, wx2 = self._x(self.t), self._x(self.t + self.mw.width)
        c.create_rectangle(wx1, top - 7, max(wx2, wx1 + 2), top + self.ROW_H + 7,
                           fill="#222222", outline="#222222", tags="frame")
        c.create_text(min(wx1 + 6, self.width - 90), top + self.ROW_H + 10,
                      anchor="nw", font=("Arial", 8), fill="#222222",
                      tags="frame",
                      text=f"{human(self.mw.width)}, only "
                           f"{'/'.join(self.mw.allowed)}")


AB = lambda: [Task("A", priority=50, min_time=10, color="#FF9999"),
              Task("B", priority=50, min_time=10, color="#99CCFF")]


def build_cases():
    return [
        (
            (
                "Test 1: Normal 50/50 Split (10min each)\n"
                "-> Pure periodic cycle, no prefix."
            ),
            AB(), 180, [], []
        ),
        (
            (
                "Test 2: Pre-placed event owned by nobody\n"
                "-> MAINTENANCE excludes everybody equally, so it creates no "
                "field: they simply resume alternating."
            ),
            AB(), 240,
            [{'name': 'MAINTENANCE', 'start': 40, 'duration': 60, 'color': '#CCCCCC'}],
            []
        ),
        (
            (
                "Test 3: Periods constraint\n"
                "-> C is banned from t=105 on, forever: it is abundantly "
                "present just before the door closes, then A and B share the "
                "timeline."
            ),
            [
                Task("A", priority=40, min_time=10, color="#FF9999"),
                Task("B", priority=40, min_time=10, color="#99CCFF"),
                Task("C", priority=20, min_time=10, color="#99FF99")
            ],
            300, [],
            [{'start': 0, 'end': 105, 'allowed': ['A', 'B', 'C']},
             {'start': 105, 'end': inf, 'allowed': ['A', 'B']}]
        ),
        (
            (
                "Test 4: Three tasks (A: 50% 20m, B: 30% 10m, C: 20% 15m)\n"
                "-> Minimums force a 75min period; shares are exact."
            ),
            [
                Task("A", priority=50, min_time=20, color="#FF9999"),
                Task("B", priority=30, min_time=10, color="#99CCFF"),
                Task("C", priority=20, min_time=15, color="#99FF99")
            ],
            400, [], []
        ),
        (
            (
                "Test 5: Lopsided priorities (A 90% / B 10%) + a B block at the "
                "start\n-> A gets a denser, bounded catch-up around it, not the "
                "full 396min it is owed."
            ),
            [
                Task("A", priority=90, min_time=10, color="#FF9999"),
                Task("B", priority=10, min_time=10, color="#99CCFF")
            ],
            600,
            [{'name': 'B', 'start': 0, 'duration': 40, 'color': "#99CCFF"}],
            []
        ),
        (
            (
                "Test 6: 1h block of A at t=100 (tau = 20min)\n"
                "-> B's slots swell as the block approaches and shrink back "
                "after it: exponential decay of the influence, both sides."
            ),
            AB(), 400,
            [{'name': 'A', 'start': 100, 'duration': 60, 'color': "#FF9999"}],
            []
        ),
        (
            (
                "Test 7: 10h block of A at t=100 - 10x longer than test 6\n"
                "-> B's presence around it is wider and denser, but only a few "
                "times bigger: log, not proportional."
            ),
            AB(), 1000,
            [{'name': 'A', 'start': 100, 'duration': 600, 'color': "#FF9999"}],
            [], {}, 2
        ),
        (
            (
                "Test 8: B banned from t=100 to t=400 - a window, not a block\n"
                "-> same field, same ramps: B swells before the ban and right "
                "after it re-opens, then decays back to the cycle."
            ),
            AB(), 700, [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B']},
             {'start': 100, 'end': 400, 'allowed': ['A']},
             {'start': 400, 'end': inf, 'allowed': ['A', 'B']}],
            {}, 2
        ),
        (
            (
                "Test 9: same 300min ban, but split into ten consecutive "
                "windows\n-> merged into one exclusion: ten short bans in a row "
                "are one long ban, not ten small ones."
            ),
            AB(), 700, [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B']}]
            + [{'start': 100 + 30 * i, 'end': 130 + 30 * i, 'allowed': ['A']}
               for i in range(10)]
            + [{'start': 400, 'end': inf, 'allowed': ['A', 'B']}],
            {}, 2
        ),
    ]


def build_moving_cases():
    """Example tests where one period slides and the timeline follows it.

    These are the only cases whose rule list is *dynamic*: instead of constant
    durations it carries durations affine in the period's position, plus the
    range of positions each rule list is valid for."""
    return [
        (
            ("Test 10: a 20s period accepting only A, sliding (A 50% 10min, "
             "B 50% 10min)\n"
             "-> while it fits inside a slot of A nothing moves; once it "
             "reaches B's slot, A's slot stretches with it."),
            MovingWindow(AB(), width=Fraction(1, 3), allowed=["A"], span=60),
            25,
        ),
        (
            ("Test 11: the same sliding 20s period, three tasks "
             "(A 50%, B 30%, C 20%, 10min each)\n"
             "-> the rule list is derived the same way, and certified against "
             "the scheduler regime by regime."),
            MovingWindow([Task("A", priority=50, min_time=10, color="#FF9999"),
                          Task("B", priority=30, min_time=10, color="#99CCFF"),
                          Task("C", priority=20, min_time=10, color="#99FF99")],
                         width=Fraction(1, 3), allowed=["A"], span=100),
            35,
        ),
    ]


def draw_moving_windows(canvas, cases, y_offset, window_width=900):
    panels = []
    for title, mw, sweep in cases:
        panel = MovingWindowPanel(canvas, 15, y_offset, window_width - 60,
                                  title, mw, sweep_seconds=sweep)
        panels.append(panel)
        y_offset += panel.height + 26
    return y_offset, panels


def verify(samples=600):
    """`uv run test.py --verify`

    Unroll the dynamic rules at many window positions and compare the timeline
    they produce with the one the scheduler produces at that same position.
    The rules are only worth having if they agree everywhere, not just at the
    positions they were fitted on."""
    ok = True
    for title, mw, _ in build_moving_cases():
        print(title.splitlines()[0])
        for r in mw.regimes:
            print(f"  {r.label:>24}  prefix: {r.prefix_text}")
            print(f"  {'':>26}cycle : {r.cycle_text}")

        horizon = 2 * mw.span
        bad = []
        for i in range(samples):
            t = mw.span * Fraction(i, samples)
            prefix, cycle = mw.blocks_at(t)
            plan = mw.plan_at(t)
            got = generate_schedule(prefix, cycle, horizon)
            want = generate_schedule(
                [{'name': s.task, 'duration': s.duration, 'color': s.color}
                 for s in plan.prefix],
                [{'name': s.task, 'duration': s.duration, 'color': s.color}
                 for s in plan.cycle], horizon)
            if [(b['name'], b['start'], b['duration']) for b in got] != \
               [(b['name'], b['start'], b['duration']) for b in want]:
                bad.append(t)
        ok &= not bad
        print(f"  -> {samples - len(bad)}/{samples} positions reproduce the "
              f"scheduler exactly"
              + ("" if not bad else f"   MISMATCH at {[stamp(x) for x in bad[:5]]}")
              + "\n")
    print("OK" if ok else "FAILED")
    return 0 if ok else 1


def main():
    root = tk.Tk()
    root.title("Task Scheduler Timeline with Constraints")
    root.geometry("950x700")

    canvas = tk.Canvas(root, bg="white")
    vbar = tk.Scrollbar(root, orient=tk.VERTICAL, command=canvas.yview)
    canvas.configure(yscrollcommand=vbar.set)

    vbar.pack(side=tk.RIGHT, fill=tk.Y)
    canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    def _on_mousewheel(event):
        if getattr(event, 'num', 0) == 4 or event.delta > 0:
            canvas.yview_scroll(-1, "units")
        elif getattr(event, 'num', 0) == 5 or event.delta < 0:
            canvas.yview_scroll(1, "units")

    canvas.bind_all("<MouseWheel>", _on_mousewheel)
    canvas.bind_all("<Button-4>", _on_mousewheel)
    canvas.bind_all("<Button-5>", _on_mousewheel)

    y = draw_schedules(root, canvas, build_cases(), window_width=900)
    y, _panels = draw_moving_windows(canvas, build_moving_cases(), y,
                                     window_width=900)
    canvas.config(scrollregion=(0, 0, 900, y))
    root.mainloop()


if __name__ == "__main__":
    if "--verify" in sys.argv:
        raise SystemExit(verify())
    main()