#!/usr/bin/env python3
"""
scheduler_logic.py
The scheduler -- one walk, used by every test -- and the derivation of the
dynamic (moving-period) rule list on top of it.

The static rule list
--------------------
`Scheduler.plan` walks the timeline once and returns a finite `Plan`: a prefix
of placements followed by a cycle that repeats forever. Reading the schedule at
any instant is then arithmetic, not scheduling.

The dynamic rule list
---------------------
One period may *slide*. Re-running the scheduler at every position of it would
answer the question but would not *be* a rule list, so `MovingWindow` derives
one: the plan changes shape only where the sliding period crosses a boundary,
and between two such positions every duration is affine in the position t_p.
The output is therefore

    [ (range of positions, prefix rules, cycle rules) ... ]

with a duration written `a + b*(t_p - lo)`. Drawing the timeline at a given
position is a binary search and some arithmetic -- no scheduling happens, which
is what lets the display follow the period continuously.

Two things make that derivation honest:

* it calls the *same* `Scheduler.plan` every other test calls -- the moving
  period is an ordinary period, and the rules are fitted to, then certified
  against, the scheduler itself;
* the past is frozen by construction. Everything at t < t_p is the timeline the
  schedule already committed to (`MovingWindow.base`), and the scheduler is
  asked only for the continuation from t_p on, with that past handed to it as
  history. So no position of the period can rewrite an earlier one's past, and
  the sliding period cannot drag the whole timeline along with it.
"""

import itertools
from dataclasses import dataclass, field
from fractions import Fraction
from math import exp, inf, log

MAX_RULES = 50
IDLE = "IDLE"
IDLE_COLOR = "#F0F0F0"
FOREVER = None

def frac(x):
    if isinstance(x, Fraction): return x
    if isinstance(x, float): return Fraction(x).limit_denominator(10 ** 9)
    return Fraction(x)

def ceil_to(x, step):
    return Fraction(-((-x) // step)) * step

def human(d, unit_seconds=60):
    total = float(frac(d) * unit_seconds)
    sign, total = ("-", -total) if total < 0 else ("", total)
    h = int(total // 3600)
    rest = total - 3600 * h
    m = int(rest // 60)
    sec = rest - 60 * m
    out = []
    if h: out.append(f"{h}h")
    if m: out.append(f"{m}min")
    if sec > 1e-9 or not out:
        out.append(f"{round(sec)}s" if abs(sec - round(sec)) < 1e-9 else f"{sec:.4g}s")
    return sign + " ".join(out)

def human_s(seconds):
    return human(Fraction(0) if seconds == 0 else frac(seconds) / 60)

def stamp(t):
    return human(t)

class Task:
    def __init__(self, name, priority, min_time, color):
        self.name = str(name)
        self.priority = frac(priority)
        self.min_time = frac(min_time)
        self.color = color

@dataclass(frozen=True)
class Slot:
    task: str
    duration: Fraction
    color: str = "#DDDDDD"

@dataclass(frozen=True)
class Placement:
    task: str
    start: Fraction
    end: Fraction
    color: str = "#DDDDDD"
    @property
    def duration(self): return self.end - self.start

@dataclass
class Plan:
    start: Fraction
    prefix: list
    cycle: list
    shares: dict = field(default_factory=dict)

    def __post_init__(self):
        self.period = sum((s.duration for s in self.cycle), Fraction(0))

class Scheduler:
    def __init__(self, tasks, resolution=None, max_lag=None, tau=None, max_boost=6, field_floor=Fraction(1, 10), max_reach=None):
        tasks = list(tasks)
        active = [t for t in tasks if t.priority > 0]
        total = sum(t.priority for t in active)
        self.tasks = active
        self.p = {t.name: t.priority / total for t in active}
        self.minimum = {t.name: t.min_time for t in active}
        self.color = {t.name: t.color for t in active}
        self.resolution = frac(resolution) if resolution else None
        self.min_period = max(self.minimum[n] / self.p[n] for n in self.p)

        self.tau = frac(tau) if tau is not None else self.min_period
        self.max_boost = frac(max_boost)
        self.field_floor = frac(field_floor)
        self.max_reach = frac(max_reach) if max_reach is not None else 6 * self.tau
        self.max_amp = float(self.field_floor) * (exp(float(self.max_reach) / float(self.tau)) - 1.0)
        self.field = {}
        self.field_end = None
        self.max_lag = frac(max_lag) if max_lag is not None else None

    def _shares(self, allowed):
        total = sum(self.p[n] for n in allowed)
        return {n: self.p[n] / total for n in allowed}

    def _period(self, allowed):
        p = self._shares(allowed)
        return max(self.minimum[n] / p[n] for n in allowed)

    def _sources(self, timeline, periods):
        everyone = set(self.p)
        raw = []
        for p in timeline:
            if p.end > p.start: raw.append((p.start, p.end, everyone - {p.task}))
        for w in periods:
            end = inf if w['end'] is FOREVER else w['end']
            if end > w['start']: raw.append((w['start'], end, everyone - set(w['allowed'])))

        spans = {n: [] for n in everyone}
        for start, end, excluded in raw:
            if len(excluded) == len(everyone): continue
            for n in excluded: spans[n].append((start, end))
        return {n: self._merge_spans(s) for n, s in spans.items() if s}

    @staticmethod
    def _merge_spans(spans):
        out = []
        for start, end in sorted(spans):
            if out and start <= out[-1][1]: out[-1] = (out[-1][0], max(out[-1][1], end))
            else: out.append((start, end))
        return out

    def _set_field(self, timeline=(), periods=()):
        tau = float(self.tau)
        self.field = {}
        self.field_end = None
        for name, spans in self._sources(timeline, periods).items():
            entries = []
            for start, end in spans:
                length = inf if end == inf else float(end - start)
                if length < self.minimum[name]: continue
                amp = min(length / tau, self.max_amp)
                entries.append((start, end, amp))
                if end == inf: continue
                reach = tau * log(1.0 + amp / float(self.field_floor))
                stop = end + frac(reach)
                if self.field_end is None or stop > self.field_end:
                    self.field_end = stop
            self.field[name] = entries

    def _boost(self, name, t):
        spans = self.field.get(name)
        if not spans: return Fraction(1)
        tau, acc = 0.0, 0.0
        tau = float(self.tau)
        for start, end, amp in spans:
            if start <= t <= end: d = 0.0
            elif t < start: d = float(start - t)
            else: d = float(t - end)
            acc += amp * exp(-d / tau)
        if acc <= 0.0: return Fraction(1)
        return frac(1.0 + min(acc, float(self.max_boost) - 1.0))

    def _relax(self, v, dt, T, active):
        if not active: return
        lo = min(v[n] for n in active)
        if dt > 0:
            f = frac(exp(-float(dt) / float(self.tau)))
            for n in active:
                over = v[n] - lo - T
                if over > 0: v[n] -= over * (1 - f)
        for n in v:
            if n not in active:
                v[n] = min(max(v[n], lo - T), lo + T)

    def _pick(self, v, candidates, last=None):
        pool = [n for n in candidates if n != last] or list(candidates)
        return min(pool, key=lambda n: (v[n], -self.p[n], n))

    def _chunk(self, name, v, candidates, p=None, boost=None, T=None, floor=None):
        p = p or self.p
        floor = self.minimum[name] if floor is None else floor
        others = [v[n] for n in candidates if n != name]
        target = min(others) if others else v[name]
        need = p[name] * (target - v[name])
        c = max(floor, need)
        if boost is not None:
            unit = max(self.minimum[name], p[name] * T)
            c = min(max(c, floor * boost), max(unit * boost, floor))
        if self.resolution: c = ceil_to(c, self.resolution)
        return c

    def _clamp(self, v, t):
        if self.max_lag is None: return
        lo, hi = t - self.max_lag, t + self.max_lag
        for n in v: v[n] = min(max(v[n], lo), hi)

    @staticmethod
    def _push(slots, task, duration, color):
        if slots and slots[-1].task == task:
            slots[-1] = Slot(task, slots[-1].duration + duration, color)
        else:
            slots.append(Slot(task, duration, color))

    def steady_cycle(self, allowed):
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
            if rem[name] - c < self.minimum[name]: c = rem[name]
            slots.append(Slot(name, c, self.color[name]))
            v[name] += c / p[name]
            rem[name] -= c
        return slots

    def coarse_cycle(self, allowed):
        p = self._shares(allowed)
        T = self._period(allowed)
        order = sorted(allowed, key=lambda n: (-p[n], n))
        return [Slot(n, p[n] * T, self.color[n]) for n in order]

    @staticmethod
    def _normalise_periods(periods):
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
            if p.start <= t < p.end: return p
        return None

    def _allowed_at(self, periods, t):
        for w in periods:
            if w['start'] <= t and (w['end'] is FOREVER or t < w['end']):
                return [n for n in self.p if n in w['allowed']]
        return list(self.p)

    @staticmethod
    def _next_boundary(pre, periods, t):
        best = None
        for p in pre:
            if p.start > t and (best is None or p.start < best): best = p.start
        for w in periods:
            for b in (w['start'], w['end']):
                if b is not FOREVER and b > t and (best is None or b < best): best = b
        return best

    def _blocked_from(self, name, pre, periods, t):
        best = None
        for p in pre:
            if p.start > t and (best is None or p.start < best): best = p.start
        bounds = sorted({b for w in periods for b in (w['start'], w['end']) if b is not FOREVER and b > t})
        for b in bounds:
            if best is not None and b >= best: break
            if name not in self._allowed_at(periods, b): return b
        return best

    def _next_placeable(self, missing, pre, periods, t):
        if not missing: return None
        bounds = sorted({b for w in periods for b in (w['start'], w['end']) if b is not FOREVER and b > t}
                        | {p.end for p in pre if p.end > t})
        for b in bounds:
            for n in missing:
                if n not in self._allowed_at(periods, b): continue
                room = self._blocked_from(n, pre, periods, b)
                if room is None or room - b >= self.minimum[n]: return b
        return None

    def _head(self, past, t_now):
        """The run still in progress at t_now: (task, how long it has served).

        A run survives an idling interruption -- the README's atomic block is
        about a task's *service*, and a period that accepts nobody suspends it
        rather than ending it -- so the scan skips IDLE and stops at the first
        different task.
        """
        total, name = Fraction(0), None
        for p in sorted(past, key=lambda p: p.start, reverse=True):
            if p.task not in self.p: continue          # IDLE, or somebody else's block
            if name is None: name = p.task
            elif p.task != name: break
            total += min(p.end, t_now) - p.start
        return name, total

    def plan(self, timeline=(), periods=(), t_now: Fraction | float = 0,
             lookback=None, max_rules=MAX_RULES, history=()):
        t_now = frac(t_now)
        timeline = sorted(timeline, key=lambda p: p.start)
        periods = self._normalise_periods(periods)

        past = [p for p in timeline if p.end <= t_now] + [p for p in history if p.start < t_now]
        pre = [p for p in timeline if p.end > t_now]
        # Only obstacles still ahead bend the plan: what already happened is
        # history, not a blockage the timeline has to be compensated around.
        self._set_field(pre, periods)

        window = frac(lookback) if lookback is not None else self.min_period
        w_start = t_now - window
        if past: w_start = max(w_start, min(p.start for p in past))
        w_start = min(w_start, t_now)
        elapsed = t_now - w_start

        served = {n: Fraction(0) for n in self.p}
        for p in past:
            if p.task in served:
                served[p.task] += max(Fraction(0), min(p.end, t_now) - max(p.start, w_start))

        lag = {n: served[n] / self.p[n] - elapsed for n in self.p}
        base = min(lag.values())
        t = t_now
        v = {n: t + lag[n] - base for n in self.p}
        self._clamp(v, t)

        head_task, head_served = self._head(past, t_now)

        slots = []
        last = head_task
        free_tail = False
        steps, max_steps = 0, 200 * max_rules

        def run_served(n):
            """How much of its minimum the current run of n has already paid."""
            total = Fraction(0)
            for s in reversed(slots):
                if s.task == n: total += s.duration
                elif s.task == IDLE: continue
                else: return total
            return total + (head_served if n == head_task else Fraction(0))

        def owed(n):
            return max(Fraction(0), self.minimum[n] - run_served(n))

        def pending():
            """The task no other task may interrupt: the one running, short of
            its minimum. This is the README's atomic block, and it is what the
            sliding period runs into -- a period it is not allowed in suspends
            it, it does not replace it."""
            for s in reversed(slots):
                if s.task == IDLE: continue
                return s.task if s.task in self.p and owed(s.task) > 0 else None
            if head_task is not None and owed(head_task) > 0: return head_task
            return None

        while len(slots) < max_rules and steps < max_steps:
            steps += 1
            allowed = self._allowed_at(periods, t)
            T = self._period(allowed) if allowed else self.min_period

            block = self._active_pre(pre, t)
            if block:
                # A pre-placed block is locked to its own coordinates, but a
                # period still dictates what may RUN there: where the block's
                # own task is refused, the block is suspended and resumes on the
                # far side -- exactly as a scheduled run is -- instead of
                # running through the period or being moved off its slot. So the
                # block is walked edge by edge, not swallowed whole. A block
                # owned by nobody (MAINTENANCE) is not one of the tasks the
                # allow-lists speak about, and no period suspends it.
                nxt = self._next_boundary((), periods, t)
                stop = block.end if nxt is None else min(block.end, nxt)
                d = stop - t
                if block.task not in self.p or block.task in allowed:
                    self._push(slots, block.task, d, block.color)
                    if block.task in v: v[block.task] += d / self.p[block.task]
                    last = block.task
                else:
                    self._push(slots, IDLE, d, IDLE_COLOR)
                t = stop
                free_tail = False
                self._relax(v, 0, T, allowed)
                self._clamp(v, t)
                continue

            limit = self._next_boundary(pre, periods, t)
            # a run suspended by a period it is banned from is not finished with
            # the timeline, so the walk may not stop at the last boundary while
            # it still owes its minimum
            if (limit is None and (self.field_end is None or t >= self.field_end)
                    and pending() is None):
                break

            held = pending()
            candidates = ([held] if held in allowed else []) if held is not None else allowed

            room = {n: self._blocked_from(n, pre, periods, t) for n in candidates}
            # "does the minimum fit?" is a question for a task about to *start*.
            # A task already running has started, and refusing it the room it has
            # left would idle the timeline and still leave its run short: it may
            # be cut anywhere, by whatever it cannot pass.
            fitting = [n for n in candidates
                       if room[n] is None or room[n] - t >= owed(n) or run_served(n) > 0]

            if not fitting:
                if limit is None: break
                gap = limit - t
                # the tail may only absorb a gap it is itself allowed to run in:
                # a period that bans it suspends the run, it does not extend it
                if free_tail and slots and slots[-1].task in allowed:
                    tail = slots[-1]
                    slots[-1] = Slot(tail.task, tail.duration + gap, tail.color)
                    v[tail.task] += gap / self.p[tail.task]
                else:
                    self._push(slots, IDLE, gap, IDLE_COLOR)
                    last = None
                    free_tail = False
                t += gap
                continue

            name = self._pick(v, fitting, last)
            boost = self._boost(name, t)
            c = self._chunk(name, v, fitting, boost=boost, T=T,
                            floor=owed(name) or self.minimum[name])

            room_limit = room[name]
            if room_limit is not None:
                c = min(c, room_limit - t)

            back = self._next_placeable([n for n in self.p if n not in fitting], pre, periods, t)
            if back is not None:
                c = min(c, max(back, t + owed(name)) - t)

            self._push(slots, name, c, self.color[name])
            v[name] += c / (self.p[name] * boost)
            t += c
            last = name
            free_tail = True
            self._relax(v, c, T, allowed)
            self._clamp(v, t)

        cycle = []
        allowed = self._allowed_at(periods, t)
        if allowed and len(slots) < max_rules and pending() is None:
            T = self._period(allowed)
            horizon = t + 4 * T
            while len(slots) < max_rules and t < horizon:
                spread = max(v[n] for n in allowed) - min(v[n] for n in allowed)
                if spread <= T: break
                name = self._pick(v, allowed, last)
                boost = self._boost(name, t)
                c = self._chunk(name, v, allowed, boost=boost, T=T,
                                floor=owed(name) or self.minimum[name])
                self._push(slots, name, c, self.color[name])
                v[name] += c / (self.p[name] * boost)
                t += c
                last = name
                self._relax(v, c, T, allowed)
                self._clamp(v, t)
            cycle = self.steady_cycle(allowed)
            if len(cycle) > max_rules: cycle = self.coarse_cycle(allowed)
            cycle = self._phase(cycle, v, allowed, last)

        prefix, cycle = self._tidy(slots, cycle)
        period = sum((s.duration for s in cycle), Fraction(0))
        shares = {}
        if period:
            for s in cycle:
                shares[s.task] = shares.get(s.task, Fraction(0)) + s.duration / period
        return Plan(start=t_now, prefix=prefix, cycle=cycle, shares=shares)

    def _phase(self, cycle, v, allowed, last):
        if not cycle: return cycle
        first = self._pick(v, allowed, last)
        for i, s in enumerate(cycle):
            if s.task == first: return cycle[i:] + cycle[:i]
        return cycle

    @staticmethod
    def _merge_run(slots):
        out = []
        for s in slots:
            if out and out[-1].task == s.task:
                out[-1] = Slot(s.task, out[-1].duration + s.duration, s.color)
            else: out.append(s)
        return out

    def _tidy(self, prefix, cycle):
        prefix, cycle = self._merge_run(list(prefix)), self._merge_run(list(cycle))
        while len(cycle) > 1 and cycle[0].task == cycle[-1].task:
            head = cycle.pop(0)
            cycle[-1] = Slot(cycle[-1].task, cycle[-1].duration + head.duration, head.color)
            prefix.append(head)
        prefix = self._merge_run(prefix)
        for _ in range(len(cycle)):
            if not (prefix and len(cycle) > 1 and prefix[-1].task == cycle[0].task): break
            head = cycle.pop(0)
            cycle.append(head)
            prefix[-1] = Slot(head.task, prefix[-1].duration + head.duration, head.color)
        return prefix, cycle

def rule_lines(blocks, indent="- "):
    return [f"{indent}task {b['name']} {human(b['duration'])}" for b in blocks]

def as_blocks(slots):
    return [{'name': s.task, 'duration': s.duration, 'color': s.color} for s in slots]

def materialise(plan, horizon):
    """Unroll a plan into placements, prefix then cycle, up to `horizon`."""
    out, t = [], plan.start
    def add(task, d, color):
        nonlocal t
        if d <= 0: return
        if out and out[-1].task == task:
            out[-1] = Placement(task, out[-1].start, out[-1].end + d, color)
        else:
            out.append(Placement(task, t, t + d, color))
        t += d
    for s in plan.prefix:
        if t >= horizon: break
        add(s.task, s.duration, s.color)
    if plan.cycle and plan.period > 0:
        while t < horizon:
            for s in plan.cycle:
                if t >= horizon: break
                add(s.task, s.duration, s.color)
    return out

def truncate(placements, t):
    """The part of a timeline strictly before t -- i.e. the frozen past."""
    out = []
    for p in placements:
        if p.end <= t: out.append(p)
        elif p.start < t: out.append(Placement(p.task, p.start, t, p.color))
        else: break
    return out


# --------------------------------------------------------------------------- #
#  the dynamic rule list: one period slides, the rules follow it
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class Rule:
    """A slot whose duration is affine in the sliding period's position."""
    task: str
    a: Fraction                 # the duration when the period starts at `lo`
    b: Fraction                 # how much of the position it absorbs
    color: str = "#DDDDDD"

    def at(self, tp, lo):
        return self.a + self.b * (tp - lo)

    def text(self, lo):
        if not self.b: return f"{self.task} {human(self.a)}"
        sign = "+" if self.b > 0 else "-"
        factor = "" if abs(self.b) == 1 else f"{float(abs(self.b)):.4g}*"
        return f"{self.task} {human(self.a)} {sign} {factor}(t_p - {stamp(lo)})"

@dataclass
class Regime:
    """The rules while the sliding period starts in [lo, hi) -- or in (lo, hi)
    when `lo_open`, i.e. when the breakpoint itself still obeys the previous
    rules."""
    lo: Fraction
    hi: Fraction
    prefix: list
    cycle: list
    lo_open: bool = False
    eps: Fraction = Fraction(0)

    @property
    def label(self):
        if self.hi == self.lo: return f"exactly {stamp(self.lo)}"
        return f"{'(' if self.lo_open else '['}{stamp(self.lo)}, {stamp(self.hi)})"

    @property
    def prefix_text(self):
        return " | ".join(r.text(self.lo) for r in self.prefix) or "(none)"

    @property
    def cycle_text(self):
        return " | ".join(r.text(self.lo) for r in self.cycle) + " | repeat"

    def blocks(self, tp):
        """The rules evaluated at position tp -> (prefix blocks, cycle blocks).

        A rule whose duration has shrunk to nothing at this position is dropped
        on the same epsilon the fit used, so the rules and the scheduler agree
        on when a slot has stopped existing rather than on a hair's width -- and
        the neighbours it was separating then become one slot, exactly as the
        scheduler's own run of the same task is one slot."""
        def out(rules):
            blocks = []
            for r in rules:
                d = max(Fraction(0), r.at(tp, self.lo))
                if d <= self.eps: continue
                if blocks and blocks[-1]['name'] == r.task: blocks[-1]['duration'] += d
                else: blocks.append({'name': r.task, 'duration': d, 'color': r.color})
            return blocks
        return out(self.prefix), out(self.cycle)

class MovingWindow:
    """The rule list of a schedule disturbed by periods that slide with t_p.

    `moving(tp)` returns the periods at position tp -- the sliding ones. They
    are ordinary periods, handed to the ordinary scheduler; only their position
    is a parameter. `periods` and `pre_placed` are the static environment, and
    they are part of the committed timeline the past is read off.
    """

    # A regime is accepted only if the affine fit reproduces the scheduler at
    # these positions inside it (as fractions of the regime's width). The ones
    # crowding the two edges are the ones that matter: a breakpoint a hair
    # inside a range is exactly what a comfortable spread of probes misses.
    PROBES = (Fraction(1, 64), Fraction(1, 8), Fraction(1, 3), Fraction(1, 2),
              Fraction(5, 8), Fraction(7, 8), Fraction(15, 16), Fraction(63, 64))
    SAMPLES = 8
    MAX_ROUNDS = 8

    def __init__(self, tasks, span, moving, pre_placed=(), periods=(), breaks=(),
                 tol=Fraction(1, 120), **kw):
        self.tasks = list(tasks)
        self.span = frac(span)
        self.moving = moving
        self.periods = list(periods)
        self.pre = [Placement(p['name'], frac(p['start']), frac(p['start']) + frac(p['duration']),
                              p.get('color', '#CCCCCC')) for p in pre_placed]
        self.breaks = sorted(frac(b) for b in breaks)
        self.tol = frac(tol)
        # A slot this short is not a slot, it is rounding: the schedule may pass
        # through one on its way from "A ends here" to "A is gone", and forcing
        # a regime of its own on every one of them would describe the rounding
        # rather than the schedule. This is the README's rounding epsilon.
        self.sliver = self.tol / 4
        self.kw = kw
        self.horizon = 2 * self.span
        self.sched = Scheduler(self.tasks, **kw)
        self.minimum = dict(self.sched.minimum)
        self.p = dict(self.sched.p)
        self._cache = {}
        # how far past t_p the sliding periods reach, anywhere in the sweep: the
        # display starts the sweep over when the period itself -- not merely its
        # start -- has reached the end of the timeline
        self.reach = max(max(self._offsets(self.span * Fraction(i, 8)))
                         for i in range(8))
        self.base = self._base()
        self.regimes = self._build()

    # ---------------- the committed timeline, and the plan from t_p ---------- #

    def _base(self):
        """The timeline the schedule committed to before the period reached it.

        This is what "everything at t < t_p stays frozen" *is*: the past is read
        off one fixed timeline, so no position of the sliding period can rewrite
        an earlier one's past.
        """
        plan = Scheduler(self.tasks, **self.kw).plan(
            timeline=self.pre, periods=self.periods, t_now=0)
        return materialise(plan, self.horizon)

    def history_at(self, tp):
        return truncate(self.base, frac(tp))

    def plan_at(self, tp):
        tp = frac(tp)
        if tp in self._cache: return self._cache[tp]
        plan = Scheduler(self.tasks, **self.kw).plan(
            timeline=[p for p in self.pre if p.end > tp],
            periods=list(self.moving(tp)) + self.periods,
            t_now=tp,
            history=self.history_at(tp))
        self._cache[tp] = plan
        return plan

    # ---------------- breakpoints ---------------- #

    @staticmethod
    def _edges(w):
        end = w.get('end', FOREVER)
        out = [frac(w['start'])]
        if end is not FOREVER and end != inf: out.append(frac(end))
        return out

    def _offsets(self, tp):
        """Where the *sliding* edges sit relative to t_p.

        A period listed as moving may still be standing still at this position
        (test 11's five-minute stretch waits at home until the window reaches
        it), and its distance to t_p is then not an offset at all -- it is just
        the distance to a fixed boundary. Sampling and keeping what every sample
        agrees on is what tells the two apart; three odd spacings, because a
        standing edge whose distance happens to shift by exactly the spacing
        would pass a single one (a 1min spacing is fooled by a 1min period)."""
        out = {e - tp for w in self.moving(tp) for e in self._edges(w)}
        for d in (Fraction(1, 7), Fraction(3, 7), Fraction(5, 7)):
            out &= {e - (tp + d) for w in self.moving(tp + d) for e in self._edges(w)}
        return {Fraction(0)} | out

    def _fixed_bounds(self, tp):
        """Boundaries a sliding edge can reach: the committed timeline's own
        slots, the static periods, and the plan the scheduler draws from t_p."""
        out = {p.start for p in self.base} | {p.end for p in self.base}
        out |= {p.start for p in self.pre} | {p.end for p in self.pre}
        for w in self.periods: out |= set(self._edges(w))
        for w in self.moving(tp): out |= set(self._edges(w))
        plan = self.plan_at(tp)
        acc = plan.start
        for s in list(plan.prefix) + list(plan.cycle):
            acc += s.duration
            out.add(acc)
        return out

    def _edges_of(self, tp, lo, hi):
        """The positions at which a sliding edge reaches a boundary of the
        timeline -- the only places the plan can change shape."""
        out = set()
        for o in self._offsets(tp):
            for b in self._fixed_bounds(tp):
                c = b - o
                if lo < c < hi: out.add(c)
        return out

    def _candidates(self, lo, hi, n):
        out = {b for b in self.breaks if lo < b < hi}
        for i in range(1, n):
            out |= self._edges_of(lo + Fraction(i, n) * (hi - lo), lo, hi)
        return out

    # ---------------- fitting one regime ---------------- #

    def _norm(self, slots):
        out = []
        for s in slots:
            if s.duration <= self.sliver: continue
            if out and out[-1].task == s.task:
                out[-1] = Slot(s.task, out[-1].duration + s.duration, s.color)
            else: out.append(s)
        return out

    def rules_of(self, tp):
        """The scheduler's own answer at tp, as (prefix, cycle) slot lists."""
        plan = self.plan_at(tp)
        return self._norm(plan.prefix), self._norm(plan.cycle)

    def _shape(self, tp):
        pre, cyc = self.rules_of(tp)
        return ([s.task for s in pre], [s.task for s in cyc])

    def _close(self, got, want):
        return len(got) == len(want) and all(abs(x - y) <= self.tol for x, y in zip(got, want))

    def _fit(self, lo, hi):
        """Affine rules valid on [lo, hi), or None if none are.

        Two positions determine the affine form; the others check it. A regime
        is never accepted on the strength of the samples that built it -- if the
        plan does not in fact vary affinely with the period across the whole
        range, the fit is rejected and the range is split further."""
        if hi <= lo: return None
        t1, t2 = lo + (hi - lo) / 4, lo + 3 * (hi - lo) / 4
        pre1, cyc1 = self.rules_of(t1)
        pre2, cyc2 = self.rules_of(t2)
        if ([s.task for s in pre1], [s.task for s in cyc1]) != \
           ([s.task for s in pre2], [s.task for s in cyc2]): return None

        def rules(s1, s2):
            out = []
            for x, y in zip(s1, s2):
                b = (y.duration - x.duration) / (t2 - t1)
                out.append(Rule(x.task, x.duration + b * (lo - t1), b, x.color))
            return out

        regime = Regime(lo, hi, rules(pre1, pre2), rules(cyc1, cyc2), eps=self.sliver)
        probes = [lo + f * (hi - lo) for f in self.PROBES]
        # and the top edge itself: a breakpoint in the last hundredth of a range
        # is invisible to any fixed spread of probes, and a range is claimed
        # right up to its `hi`
        if hi - lo > 2 * self.MIN_RANGE: probes.append(hi - self.MIN_RANGE)
        for tp in probes:
            if not self._reproduces(regime, tp): return None
        return regime

    def _reproduces(self, regime, tp):
        pre, cyc = self.rules_of(tp)
        want_pre, want_cyc = regime.blocks(tp)
        return (([s.task for s in pre], [s.task for s in cyc])
                == ([b['name'] for b in want_pre], [b['name'] for b in want_cyc])
                and self._close([s.duration for s in pre], [b['duration'] for b in want_pre])
                and self._close([s.duration for s in cyc], [b['duration'] for b in want_cyc]))

    MIN_RANGE = Fraction(1, 6000)       # 0.01 s: below this a range is a point

    def _split(self, lo, hi, n=6):
        """The breakpoints inside a range that would not fit.

        They are found, not guessed. Two kinds exist and both have to be looked
        for: the plan may change *shape* (a task drops out of the prefix, two
        swap), which sampling and bisecting on the shape locates exactly; or it
        may keep its shape and change *slope*, when a cap or a boundary starts
        binding a duration that was free before. The second kind is invisible to
        a shape test, so it is bisected on the fit itself."""
        if hi - lo <= self.MIN_RANGE: return set()
        # strictly inside: hi belongs to the next regime, so sampling it would
        # "find" a breakpoint at the range's own edge in every failing range
        xs = [lo + Fraction(i, n) * (hi - lo) for i in range(n)]
        shapes = [self._shape(x) for x in xs]
        out = set()
        for (a, sa), (b, sb) in zip(zip(xs, shapes), zip(xs[1:], shapes[1:])):
            if sa != sb:
                out.add(self._bisect(a, b, lambda x, s=sa: self._shape(x) == s)[1])
        if out: return out
        m, _ = self._bisect(lo, hi, lambda x: self._fit(lo, x) is not None, rounds=14)
        return {m} if lo < m < hi else {lo + (hi - lo) / 2}

    def _bisect(self, lo, hi, holds, rounds=32):
        """(last position where `holds` is true, first where it is not)."""
        for _ in range(rounds):
            mid = (lo + hi) / 2
            if holds(mid): lo = mid
            else: hi = mid
        return lo, hi

    def _build(self):
        edges = sorted({frac(0), self.span} | self._candidates(frac(0), self.span, self.SAMPLES))
        for _ in range(self.MAX_ROUNDS):
            out, failed = [], []
            for lo, hi in itertools.pairwise(edges):
                if hi <= lo: continue
                fit = self._fit(lo, hi)
                if fit is None: failed.append((lo, hi))
                else: out.append(fit)
            if not failed:
                return self._settle_edges(self._merge(out))
            extra = set()
            for lo, hi in failed:
                extra |= self._split(lo, hi)
            if extra <= set(edges):
                raise ValueError(
                    "no affine rule describes the sliding period on "
                    + ", ".join(f"[{stamp(a)}, {stamp(b)})" for a, b in failed[:4]))
            edges = sorted(set(edges) | extra)
        raise ValueError(
            f"the dynamic rule list did not settle: {len(failed)} range(s) still "
            f"unfitted, e.g. [{stamp(failed[0][0])}, {stamp(failed[0][1])})")

    def _merge(self, regimes):
        """Adjacent ranges that turn out to obey the same rules are one rule.

        A candidate breakpoint is only ever a *guess* that the plan might change
        there; most turn out not to be breakpoints at all, and keeping them
        would pad the rule list with copies of the same rule. The test is
        analytic -- same tasks, same slopes, and the earlier rule already
        evaluating to the later one's constant at the junction -- rather than
        another round of probes, which would happily merge across a breakpoint
        sitting in the last hundredth of a range."""
        out = []
        for r in regimes:
            prev = out[-1] if out else None
            same = (prev is not None and prev.hi == r.lo
                    and len(prev.prefix) == len(r.prefix)
                    and len(prev.cycle) == len(r.cycle)
                    and all(x.task == y.task and x.b == y.b
                            and abs(x.at(r.lo, prev.lo) - y.a) <= self.tol
                            for x, y in zip(prev.prefix + prev.cycle, r.prefix + r.cycle)))
            if same:
                assert prev is not None
                out[-1] = Regime(prev.lo, r.hi, prev.prefix, prev.cycle, eps=self.sliver)
            else:
                out.append(r)
        return out

    def _settle_edges(self, regimes):
        """Decide which side of a breakpoint the breakpoint itself is on.

        A regime is fitted from positions strictly inside it, so nothing yet
        says whether the instant the plan changes at obeys the new rules or the
        old ones. Occasionally it obeys neither -- the period landing exactly on
        a boundary is its own case -- and then that single position gets a rule
        list of its own rather than being quietly rounded to a neighbour."""
        out = [regimes[0]]
        for prev, r in itertools.pairwise(regimes):
            if self._reproduces(r, r.lo):
                out.append(r)
                continue
            r.lo_open = True
            if not self._reproduces(prev, r.lo):
                pre, cyc = self.rules_of(r.lo)
                const = lambda slots: [Rule(s.task, s.duration, Fraction(0), s.color)
                                       for s in slots]
                out.append(Regime(r.lo, r.lo, const(pre), const(cyc), eps=self.sliver))
            out.append(r)
        return out

    # ---------------- lookup: no scheduling, ever ---------------- #

    def regime_at(self, tp):
        tp = frac(tp) % self.span
        lo, hi = 0, len(self.regimes) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            r = self.regimes[mid]
            if r.lo < tp or (r.lo == tp and not r.lo_open): lo = mid
            else: hi = mid - 1
        return self.regimes[lo]

    def blocks_at(self, tp):
        return self.regime_at(tp).blocks(frac(tp) % self.span)

    def rule_count(self):
        return max(len(r.prefix) + len(r.cycle) for r in self.regimes)

    def lines(self):
        out = ["The sliding period's position is t_p; every duration below is affine in it.",
               (f"Timeline span {stamp(self.span)}, {len(self.regimes)} regimes, "
               f"at most {self.rule_count()} rules each (cap {MAX_RULES})."),
               "Everything at t < t_p is frozen and is not restated here.",
               ""]
        for i, r in enumerate(self.regimes, 1):
            out.append(f"Regime {i}  while t_p in {r.label}:")
            out.append(f"  Prefix: {r.prefix_text}")
            out.append(f"  Cycle:  {r.cycle_text}")
        return out
