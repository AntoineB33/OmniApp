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

The progressive rule list
-------------------------
Over three days, with a night every 24h and a break every twenty minutes, one
prefix and one cycle cannot describe the timeline at all: the cycle a plan
settles into holds only while the same tasks are allowed. `ProgressiveWindow`
answers with a CHAIN of ordinary plans, each valid over its own stretch and
seeded with the timeline the earlier ones committed -- and it settles that
chain one link at a time, so a `front` moves along the timeline with everything
behind it definitive and everything ahead still provisional. That is what lets
a display show the schedule while it is still being derived.

The break the line drags is a period like any other, handed to the same
scheduler: the rules AT THE LINE are an ordinary plan from t_p, fitted into the
same affine form `MovingWindow` uses (the fitting is shared, in `SlidingRules`)
and derived one regime at a time as the line reaches it -- three days hold far
too many to derive up front.
"""

import itertools
import time
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

    def _exclusions(self, timeline, periods):
        """Every source of exclusion, as (start, end, who it refuses).

        A pre-placed block owned by somebody else and a period are the same
        event here -- an interval where a task may not run. A period says so
        directly (its `forbidden` list); a block says so by occupying the slot.
        """
        everyone = set(self.p)
        out = []
        for p in timeline:
            if p.end > p.start: out.append((p.start, p.end, everyone - {p.task}))
        for w in periods:
            end = inf if w['end'] is FOREVER else w['end']
            if end > w['start']: out.append((w['start'], end, w['forbidden'] & everyone))
        return out

    def _sources(self, timeline, periods):
        """Per task, the intervals it is refused in while somebody else is not.

        Exclusions overlap freely -- periods with each other and with the
        pre-placed blocks -- and the timeline need not be covered by them at
        all, so what an instant refuses is the UNION of everything covering it
        and an instant nothing covers refuses nobody. Cutting at every edge
        first is what makes that union a property of the INSTANT rather than of
        one period, which is the only reading under which overlap means
        anything.

        An instant that refuses EVERYBODY is dropped, and it does not bridge
        the two exclusions it separates either: nobody is served there, so
        nobody is deprived relative to anybody, and the field is about relative
        deprivation. A wait no rival profits from is a pure delay, and the
        virtual clock already repays a delay exactly.
        """
        everyone = set(self.p)
        raw = self._exclusions(timeline, periods)
        edges = sorted({b for s, e, _ in raw for b in (s, e)})

        spans = {n: [] for n in everyone}
        for start, end in itertools.pairwise(edges):
            if end <= start: continue
            excluded = set()
            for s, e, x in raw:
                if s <= start and end <= e: excluded |= x
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
                        'forbidden': set(w['forbidden'])})
        return out

    @staticmethod
    def _active_pre(pre, t):
        for p in pre:
            if p.start <= t < p.end: return p
        return None

    def _allowed_at(self, periods, t):
        """Who may run at t: everyone, less what EVERY period over t refuses.

        Overlapping periods add up (a task one of them forbids is forbidden,
        whatever the others say) and an instant no period covers refuses
        nobody -- the timeline is not required to be covered.
        """
        banned = set()
        for w in periods:
            if w['start'] <= t and (w['end'] is FOREVER or t < w['end']):
                banned |= w['forbidden']
        return [n for n in self.p if n not in banned]

    @staticmethod
    def _next_boundary(pre, periods, t):
        best = None
        for p in pre:
            if p.start > t and (best is None or p.start < best): best = p.start
        for w in periods:
            for b in (w['start'], w['end']):
                if b is not FOREVER and b > t and (best is None or b < best): best = b
        return best

    def _blocked_from(self, name, pre, periods, t, bounds=None):
        best = None
        for p in pre:
            if p.start > t and (best is None or p.start < best): best = p.start
        if bounds is None:
            bounds = sorted({b for w in periods for b in (w['start'], w['end'])
                             if b is not FOREVER and b > t})
        for b in bounds:
            if best is not None and b >= best: break
            if name not in self._allowed_at(periods, b): return b
        return best

    def _nobody_at(self, pre, periods, t):
        """Is the instant t nobody's? -- a period refusing everyone, or a block
        owned by nobody (MAINTENANCE).

        Such an interval SUSPENDS a run instead of ending it (the README's
        atomic block: the period is scheduled with nothing and the task resumes
        on the far side), and it deprives nobody relative to anybody -- which is
        already why it creates no field.
        """
        block = self._active_pre(pre, t)
        if block is not None: return block.task not in self.p
        return not self._allowed_at(periods, t)

    def _may_run(self, name, pre, periods, t):
        block = self._active_pre(pre, t)
        if block is not None and block.task != name: return False
        return name in self._allowed_at(periods, t)

    def _fits_from(self, name, pre, periods, t, need, bounds=None):
        """Can `name` still pay `need` of service from t on?

        The room a task has is NOT simply the distance to the next boundary.
        An interval nobody may run in only suspends the run, so it costs the
        task nothing but time; an interval somebody ELSE may run in ends it,
        and starting there would blockade that somebody out of the period. So
        "does the minimum fit?" counts the instants the task may actually run
        and steps over the ones that belong to nobody.

        This is what keeps a short all-refusing period from making a long
        minimum unschedulable: a 20s look-away every 20min would otherwise
        forbid every 45min task from ever starting.
        """
        if need <= 0: return True
        got = Fraction(0)
        cur = t
        for b in (bounds if bounds is not None else self._bounds_after(pre, periods, t)):
            if b <= cur: continue
            if self._nobody_at(pre, periods, cur):
                pass                                   # suspended, not stopped
            elif self._may_run(name, pre, periods, cur):
                got += b - cur
                if got >= need: return True
            else:
                return False
            cur = b
        # past the last boundary nothing changes any more
        return (not self._nobody_at(pre, periods, cur)
                and self._may_run(name, pre, periods, cur))

    @staticmethod
    def _bounds_after(pre, periods, t):
        return sorted({b for w in periods for b in (w['start'], w['end'])
                       if b is not FOREVER and b > t}
                      | {b for p in pre for b in (p.start, p.end) if b > t})

    def _next_placeable(self, missing, pre, periods, t, bounds=None):
        if not missing: return None
        after = [b for b in (bounds if bounds is not None
                             else self._bounds_after(pre, periods, t)) if b > t]
        for b in after:
            for n in missing:
                if not self._may_run(n, pre, periods, b): continue
                if self._fits_from(n, pre, periods, b, self.minimum[n], after): return b
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
        # every instant the environment can change at, sorted once: the walk asks
        # about them constantly, and with a three-day timeline there are many
        all_bounds = self._bounds_after(pre, periods, t_now)

        slots = []
        last = head_task
        free_tail = False
        steps, max_steps = 0, 200 * max_rules

        def run_served(n):
            """How much of its minimum the current run of n has already paid.

            Anything that is not one of the tasks -- idling, or a block owned by
            nobody -- suspends the run rather than ending it, exactly as `_head`
            reads it off the history."""
            total = Fraction(0)
            for s in reversed(slots):
                if s.task == n: total += s.duration
                elif s.task not in self.p: continue
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
                if s.task not in self.p: continue
                return s.task if owed(s.task) > 0 else None
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
                # forbidden lists speak about, and no period suspends it.
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

            ahead = [b for b in all_bounds if b > t]
            room = {n: self._blocked_from(n, pre, periods, t, ahead) for n in candidates}
            # "does the minimum fit?" is a question for a task about to *start*.
            # A task already running has started, and refusing it the room it has
            # left would idle the timeline and still leave its run short: it may
            # be cut anywhere, by whatever it cannot pass.
            fitting = [n for n in candidates
                       if run_served(n) > 0
                       or self._fits_from(n, pre, periods, t, owed(n), ahead)]

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

            back = self._next_placeable([n for n in self.p if n not in fitting], pre, periods, t, ahead)
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
        if not self.cycle: return "(none)"
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

class SlidingRules:
    """Fitting a rule list that is AFFINE in a sliding period's position t_p.

    The scheduler is asked for its answer at a handful of positions; between
    two breakpoints every duration turns out to vary linearly with t_p, so two
    positions determine the rules and the rest check them. What a subclass owes
    is three things: `sliding(tp)` -- the periods that move with t_p --,
    `plan_at(tp)` -- the scheduler's own answer there -- and `_fixed_bounds(tp)`
    -- the boundaries a sliding edge can reach, which is where the plan may
    change shape.

    `MovingWindow` fits every regime of a short timeline up front.
    `ProgressiveWindow` fits them one at a time, around the position asked for:
    over three days there are far too many to derive before anything is shown.
    """

    # A regime is accepted only if the affine fit reproduces the scheduler at
    # these positions inside it (as fractions of the regime's width). The ones
    # crowding the two edges are the ones that matter: a breakpoint a hair
    # inside a range is exactly what a comfortable spread of probes misses.
    PROBES = (Fraction(1, 64), Fraction(1, 8), Fraction(1, 3), Fraction(1, 2),
              Fraction(5, 8), Fraction(7, 8), Fraction(15, 16), Fraction(63, 64))
    SAMPLES = 8
    MAX_ROUNDS = 8

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
        out = {e - tp for w in self.sliding(tp) for e in self._edges(w)}
        for d in (Fraction(1, 7), Fraction(3, 7), Fraction(5, 7)):
            out &= {e - (tp + d) for w in self.sliding(tp + d) for e in self._edges(w)}
        return {Fraction(0)} | out

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
    SPLIT_ROUNDS = 32                   # bisection rounds when locating one

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
                out.add(self._bisect(a, b, lambda x, s=sa: self._shape(x) == s,
                                     rounds=self.SPLIT_ROUNDS)[1])
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


class MovingWindow(SlidingRules):
    """The rule list of a schedule disturbed by periods that slide with t_p.

    `moving(tp)` returns the periods at position tp -- the sliding ones. They
    are ordinary periods, handed to the ordinary scheduler; only their position
    is a parameter. `periods` and `pre_placed` are the static environment, and
    they are part of the committed timeline the past is read off.
    """

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
            periods=self.sliding(tp) + self.periods,
            t_now=tp,
            history=self.history_at(tp))
        self._cache[tp] = plan
        return plan

    # ---------------- breakpoints ---------------- #

    def sliding(self, tp):
        return list(self.moving(tp))

    def _fixed_bounds(self, tp):
        """Boundaries a sliding edge can reach: the committed timeline's own
        slots, the static periods, and the plan the scheduler draws from t_p."""
        out = {p.start for p in self.base} | {p.end for p in self.base}
        out |= {p.start for p in self.pre} | {p.end for p in self.pre}
        for w in self.periods: out |= set(self._edges(w))
        for w in self.sliding(tp): out |= set(self._edges(w))
        plan = self.plan_at(tp)
        acc = plan.start
        for s in list(plan.prefix) + list(plan.cycle):
            acc += s.duration
            out.add(acc)
        return out

    # ---------------- fitting one regime ---------------- #

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


# --------------------------------------------------------------------------- #
#  the progressive rule list: a chain of links, settled from t=0 outward
# --------------------------------------------------------------------------- #

@dataclass
class Segment:
    """One link of the chain: the rules that hold from `start` until `end`.

    A single prefix + cycle describes a timeline whose environment eventually
    stops changing. Over three days with a night every 24h and a break every
    20min it does not: the cycle a plan settles into is valid only until the
    next period edge. So the rule list becomes a CHAIN -- each link an ordinary
    plan, valid over its own stretch, seeded with the timeline the previous
    links committed.
    """
    start: Fraction
    end: Fraction
    prefix: list
    cycle: list

    @property
    def label(self):
        return f"[{stamp(self.start)}, {stamp(self.end)})"

    @property
    def prefix_text(self):
        return " | ".join(f"{s.task} {human(s.duration)}" for s in self.prefix) or "(none)"

    @property
    def cycle_text(self):
        if not self.cycle: return "(none)"
        return " | ".join(f"{s.task} {human(s.duration)}" for s in self.cycle) + " | repeat"

    def unroll(self, frm, to):
        """The placements these rules draw over [frm, to)."""
        out, t = [], self.start
        def emit(task, d):
            nonlocal t
            s, e = t, t + d
            t = e
            if d <= 0 or e <= frm or s >= to: return
            out.append((max(s, frm), min(e, to), task))
        for s in self.prefix:
            if t >= to: return out
            emit(s.task, s.duration)
        period = sum((s.duration for s in self.cycle), Fraction(0))
        if period > 0:
            while t < to:
                for s in self.cycle:
                    if t >= to: break
                    emit(s.task, s.duration)
        return out


class ProgressiveWindow(SlidingRules):
    """A timeline too long, and an environment too crowded, for one rule list.

    Two things make the three-day case differ from tests 1-11 in kind, not
    merely in size:

    * one prefix + one cycle cannot describe it. The cycle a plan settles into
      holds only while the same tasks are allowed, and here that changes every
      20 minutes (a break) and every night. The rule list is therefore a CHAIN
      of `Segment`s, cut at the instants the environment changes.
    * the whole chain is not derived before anything can be shown. `advance`
      settles one link at a time and moves `front`: everything at t < front is
      DEFINITIVE and will not move again, and past the front the display draws
      the last link's cycle instead -- so the schedule out there goes on
      changing while the definitive part grows from t=0. What it owes is a
      PACE: the front must settle at least 10 minutes of timeline per 10
      seconds of work, and `steps` records every link so the checks can hold it
      to it.

    The sliding period is tests 10-11's idea taken to its other extreme:
    instead of one period dragged along by t_p, a whole grid of them stands
    ahead of the line and each is REACHED in turn. Reached is not passed: a
    break nothing has served is still owed, so -- exactly as test 11's 20s
    window is dragged and then swallowed by the five-minute stretch -- it is
    drawn at the line and slides with it. `sliding(tp)` is the environment at
    the line: the grid the line has not reached yet, plus what it drags.

    Three timelines, because the line's passage really does change the
    environment, and each question deserves its own answer:

    * `past` -- the chain of the environment the line has SWEPT (`swept`, the
      static periods alone). This is what t < t_p is read off, and it is one
      fixed timeline, which is what "everything at t < t_p stays frozen" means.
      A break the line reached is at the line, never behind it, so nothing here
      holds a second copy of one.
    * the chain of this class itself -- the environment STANDING (`moving`, the
      grid where the recurrence rules put the breaks). It is what the display
      draws far ahead of the line, where the breaks have not been reached.
    * `plan_at(tp)` -- the scheduler's own answer from the line, with the
      dragged break in it as an ordinary period. It is a real plan, so the
      compensation field and the atomic block are the scheduler's own and not a
      display trick, and `regime_at(tp)` fits it into rules AFFINE in t_p so
      that following the line is arithmetic rather than scheduling.

    The regimes are fitted one at a time, around the position asked for, and
    cached: three days hold far too many to derive before anything is shown,
    and this is the "cache the state at anchors, recompute only the local
    window" shape. `steps` records every derivation so the checks can hold the
    whole thing to its PACE.
    """

    MAX_SEGMENT = frac(4 * 60)      # no link describes more than this at once
    SPLIT_ROUNDS = 18               # enough to place a breakpoint inside a bracket
    FIT_BUDGET = 3.0                # seconds spent looking for one regime's range

    def __init__(self, tasks, span, moving, marks=(), tp_start=0, pre_placed=(),
                 periods=(), sliding=None, swept=None, lookahead=None,
                 max_rules=MAX_RULES, local_rules=None, tol=Fraction(1, 120), **kw):
        self.tasks = list(tasks)
        self.span = frac(span)
        self.moving = moving
        self._sliding = sliding
        self.periods = list(periods)
        self.pre = [Placement(p['name'], frac(p['start']), frac(p['start']) + frac(p['duration']),
                              p.get('color', '#CCCCCC')) for p in pre_placed]
        self.tp_start = frac(tp_start)
        self.lookahead = frac(lookahead) if lookahead is not None else 6 * frac(60)
        self.max_rules = max_rules
        # The rules AT THE LINE are asked for one regime at a time, while the
        # user waits, and they only have to be sound as far as the next
        # boundary -- so they are capped shorter than a link's, which is what
        # keeps a fit inside its budget.
        self.local_rules = max_rules if local_rules is None else local_rules
        self.tol = frac(tol)
        self.sliver = self.tol / 4
        self.kw = kw
        self.sched = Scheduler(self.tasks, **kw)
        self.minimum = dict(self.sched.minimum)
        self.p = dict(self.sched.p)
        self.breaks = ()

        self.marks = self._marks(marks)
        self.base = []                  # the committed timeline the chain drew
        self.segments = []              # the chain itself
        self.front = frac(0)            # definitive up to here
        self.steps = []                 # (seconds, front settled) per link
        self._plans = {}                # plan_at, per position
        self.regimes = []               # the affine rules, fitted as they are asked for
        self.fits = []                  # (seconds, range of t_p covered) per regime

        # the timeline the line leaves behind it: the same chain, over the
        # environment the line has swept clean
        self.past = None if swept is None else ProgressiveWindow(
            tasks, span, moving=swept, tp_start=tp_start, pre_placed=pre_placed,
            periods=periods, lookahead=lookahead, max_rules=max_rules,
            local_rules=local_rules, tol=tol, **kw)

    # ---------------- where the chain is cut ---------------- #

    def _marks(self, extra):
        """Every position a link may start at: the edges of the standing
        environment, the positions at which the sliding line consumes a period
        (`extra`), and a cap so no link is asked to describe more than
        `MAX_SEGMENT` at once.

        A period's own edges are NOT marks. A link's prefix walks every edge in
        its way, and `_commit_end` stops it where it stops seeing them, so the
        standing environment needs no cut of its own: what forces a new link is
        the line CONSUMING a period, which changes what the rules are derived
        from."""
        out = {self.tp_start, self.span}
        out |= {frac(b) for b in extra}
        for p in self.pre:
            out |= {p.start, p.end}
        out = sorted(b for b in out if 0 <= b <= self.span)
        full = []
        for lo, hi in itertools.pairwise(out):
            full.append(lo)
            while hi - lo > self.MAX_SEGMENT:
                lo += self.MAX_SEGMENT
                full.append(lo)
        full.append(out[-1])
        return sorted(set(full))

    # ---------------- one link at a time ---------------- #

    @property
    def done(self):
        return self.front >= self.span and (self.past is None or self.past.done)

    def advance(self, budget=None):
        """Settle links for at most `budget` seconds; return the new front."""
        t0 = time.perf_counter()
        while not self.done:
            self.step()
            if budget is not None and time.perf_counter() - t0 >= budget: break
        return self.front

    def settle(self):
        while not self.done: self.step()
        return self

    def step(self):
        """One more link -- of whichever of the two chains is further behind.

        The swept one first where they are level: it is the frozen past, and
        the past is what the line needs before anything else."""
        t0, was = time.perf_counter(), self.settled
        if self.past is not None and not self.past.done and self.past.front <= self.front:
            self.past.step()
        else:
            self._extend_chain()
        self.steps.append((time.perf_counter() - t0, self.settled - was))

    @property
    def settled(self):
        """How much timeline the two chains have made definitive between them."""
        return self.front if self.past is None else min(self.front, self.past.front)

    def _commit_end(self, plan, t, limit, periods=None):
        """How far a plan may be used.

        Its prefix walked every boundary in its way, so it is sound wherever it
        reaches. Its cycle is a steady state -- sound only while nothing about
        the environment changes -- so it may fill only up to the next edge. A
        rule list is never stretched over an edge it has not seen: that is what
        would draw a task inside a period that refuses it."""
        prefix_end = t + sum((s.duration for s in plan.prefix), Fraction(0))
        nxt = self.sched._next_boundary(
            [p for p in self.pre if p.end > t],
            self.sched._normalise_periods(self.periods if periods is None else periods), t)
        far = limit if nxt is None else max(prefix_end, frac(nxt))
        # and never past what the rules actually describe: a plan that found no
        # cycle says nothing at all beyond the end of its prefix, and stretching
        # it to the next edge would leave a hole there rather than a schedule
        if not plan.cycle: far = min(far, prefix_end)
        return max(min(limit, far), t)

    def _extend_chain(self):
        """One more link, and the stretch of timeline it commits.

        It ends at the next mark, or earlier if its own rules do not reach that
        far -- a link is never asked to describe a stretch its prefix has not
        walked, which is what would draw a task inside a period refusing it."""
        g = self.front
        mark = next((m for m in self.marks if m > g), self.span)
        seg, periods = self._segment(g, mark)
        end = self._commit_end(Plan(g, seg.prefix, seg.cycle), g, mark, periods)
        seg.end = end if end > g else mark
        self.segments.append(seg)
        self.base += [Placement(n, s, e, self.sched.color.get(n, IDLE_COLOR))
                      for s, e, n in seg.unroll(g, seg.end)]
        self.front = seg.end

    def _segment(self, g, end):
        """The rules from g on: the ordinary scheduler, handed the periods that
        survive the line standing at g and the timeline already committed."""
        periods = list(self.periods) + [w for w in self.moving(g)
                                        if frac(w['start']) <= g + self.lookahead]
        plan = self.sched.plan(timeline=[p for p in self.pre if p.end > g],
                               periods=periods, t_now=g,
                               history=truncate(self.base, g),
                               max_rules=self.max_rules)
        return Segment(g, end, list(plan.prefix), list(plan.cycle)), periods

    # ---------------- reading it: no scheduling, ever ---------------- #

    def segment_at(self, tp):
        tp = frac(tp)
        lo, hi = 0, len(self.segments) - 1
        if hi < 0: return None
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if self.segments[mid].start <= tp: lo = mid
            else: hi = mid - 1
        s = self.segments[lo]
        return s if s.start <= tp else None

    def history_at(self, tp):
        """The frozen past: the timeline the line has already swept.

        Read off the `past` chain, whose environment is the one the line leaves
        behind it -- so a break it dragged is at the line and nowhere else, and
        no position of t_p can produce a different past."""
        src = self.past if self.past is not None else self
        return truncate(src.base, min(frac(tp), src.front))

    def sliding(self, tp):
        """The environment AT THE LINE: the grid it has not reached, plus the
        break it drags. Defaults to `moving`, for a case with nothing to drag."""
        return list(self._sliding(tp) if self._sliding else self.moving(tp))

    def plan_at(self, tp):
        """The scheduler's own answer from the line.

        An ordinary plan over an ordinary environment -- the dragged break is a
        period in it like any other, so the deficit it creates is compensated by
        the same influence field as everything else and no task is ever
        interrupted inside its minimum by anything but idling. This is what the
        affine rules are fitted to; the display never calls it."""
        tp = frac(tp)
        if tp not in self._plans:
            self._plans[tp] = self.sched.plan(
                timeline=[p for p in self.pre if p.end > tp],
                periods=[w for w in self.sliding(tp)
                         if frac(w['start']) <= tp + self.lookahead] + self.periods,
                t_now=tp, history=self.history_at(tp), max_rules=self.local_rules)
        return self._plans[tp]

    def _fixed_bounds(self, tp):
        """Boundaries a sliding edge can reach, and so positions of t_p where
        the plan may change shape: the standing environment's own edges, the
        frozen past's last slots, and the plan the line draws from tp."""
        out = {p.start for p in self.pre} | {p.end for p in self.pre}
        for p in self.history_at(tp)[-4:]:
            out |= {p.start, p.end}
        for w in self.periods: out |= set(self._edges(w))
        for w in self.sliding(tp): out |= set(self._edges(w))
        plan = self.plan_at(tp)
        acc = plan.start
        for s in list(plan.prefix) + list(plan.cycle):
            acc += s.duration
            out.add(acc)
        return out

    def local_end(self, tp):
        """How far the rules at the line describe the timeline.

        The same answer `_commit_end` gives a link: the prefix walked every
        boundary in its way, the cycle is sound only until the next edge. And
        never past the LOOKAHEAD, whatever its prefix reached -- out there the
        plan was not shown the standing periods at all, and a rule list is never
        stretched over an edge it has not seen. Past it the display draws the
        standing chain, which is the provisional part the requirement allows to
        keep changing."""
        tp = frac(tp)
        far = self._commit_end(self.plan_at(tp), tp, self.span,
                               self.sliding(tp) + self.periods)
        return min(far, tp + self.lookahead)

    # ---------------- the rules at the line, fitted one at a time ---------- #

    def regime_at(self, tp, budget=None):
        """The affine rules covering tp, fitted on demand and kept.

        Following the line is then arithmetic: inside a regime every duration is
        a linear function of t_p, so the display substitutes rather than
        schedules. Deriving one is the only work the sweep can trigger, it is
        bounded by the local bracket rather than by the three days, and it is
        done once."""
        tp = frac(tp)
        got = self._regime_cached(tp)
        if got is not None: return got
        t0 = time.perf_counter()
        # Is tp the breakpoint itself? A regime covers [lo, hi), so if the plan
        # already has a different shape a hair to the right, no range starting
        # here can hold it and the answer is this one position. Asking outright
        # costs one plan; discovering it by narrowing costs a bisection all the
        # way down to the epsilon, which is the one derivation that ran long.
        if self._shape(tp) != self._shape(tp + self.MIN_RANGE):
            return self._point_regime(tp, t0)
        lo, hi = self._bracket(tp)
        while True:
            fit = self._fit(lo, hi)
            if fit is not None: break
            # A range that keeps refusing to fit is narrowed by bisection, and
            # chasing it all the way down to the epsilon is the one derivation
            # that ever ran long. Past the budget the answer is given for THIS
            # position exactly -- the rules are the scheduler's own there either
            # way; all that is given up is the range they are claimed for.
            if time.perf_counter() - t0 > self.FIT_BUDGET:
                return self._point_regime(tp, t0)
            if hi - lo <= self.MIN_RANGE:
                pre, cyc = self.rules_of(tp)
                const = lambda slots: [Rule(s.task, s.duration, Fraction(0), s.color)
                                       for s in slots]
                fit = Regime(lo, hi, const(pre), const(cyc), eps=self.sliver)
                break
            cut = min((c for c in self._split(lo, hi) if lo < c < hi), default=None)
            if cut is None: cut = lo + (hi - lo) / 2
            lo, hi = (lo, cut) if tp < cut else (cut, hi)
        if not self._reproduces(fit, tp): return self._point_regime(tp, t0)
        return self._keep(fit, t0)

    def _point_regime(self, tp, t0):
        """Rules for one position only: tp is a breakpoint obeying neither
        neighbour, so it gets a rule list of its own rather than being rounded
        to one of them."""
        pre, cyc = self.rules_of(tp)
        const = lambda slots: [Rule(s.task, s.duration, Fraction(0), s.color) for s in slots]
        return self._keep(Regime(tp, tp, const(pre), const(cyc), eps=self.sliver), t0)

    def _keep(self, fit, t0):
        self.regimes = sorted(self.regimes + [fit], key=lambda r: r.lo)
        self.fits.append((time.perf_counter() - t0, fit.hi - fit.lo))
        return fit

    def rules_at(self, tp):
        """The rules already derived for this position, or None.

        A display asks with this rather than with `regime_at` so that drawing a
        frame can never trigger a derivation -- and reads the answer off the
        regime itself, which costs nothing, rather than off `plan_at`, which
        would schedule."""
        return self._regime_cached(frac(tp))

    def has_rules_at(self, tp):
        return self.rules_at(tp) is not None

    def _regime_cached(self, tp):
        for r in self.regimes:
            if r.lo <= tp < r.hi or (r.lo == r.hi == tp): return r
        return None

    def _bracket(self, tp):
        """The range a regime is looked for in: the nearest positions on either
        side of tp at which a sliding edge reaches a boundary, clipped to what
        is already fitted so two regimes can never overlap."""
        lo, hi = self.tp_start, self.span
        for r in self.regimes:
            if r.hi <= tp: lo = max(lo, r.hi)
            elif r.lo > tp: hi = min(hi, r.lo)
        cuts = self._edges_of(tp, lo, hi)
        lo = max([c for c in cuts if c <= tp], default=lo)
        hi = min([c for c in cuts if c > tp], default=hi)
        return lo, max(hi, lo + self.MIN_RANGE)

    def blocks_at(self, tp, fit=True):
        r = self.regime_at(tp) if fit else self._regime_cached(frac(tp))
        return None if r is None else r.blocks(frac(tp))

    def timeline(self, tp=None, horizon=None, fit=True):
        """What the display shows, from the line outward.

        Three pieces, in the order the line meets them: the frozen past; the
        rules at the line, substituted for t_p and unrolled as far as they are
        sound; and past that the standing chain, whose far end is still being
        settled and is what goes on changing while the definitive part grows."""
        horizon = self.span if horizon is None else frac(horizon)
        tp = self.tp_start if tp is None else frac(tp)
        slots = lambda bs: [Slot(b['name'], b['duration'], b.get('color', IDLE_COLOR))
                            for b in bs]
        out = [(p.start, min(p.end, tp), p.task) for p in self.history_at(tp)
               if p.start < min(tp, horizon)]
        far = tp
        blocks = self.blocks_at(tp, fit=fit) if tp < horizon else None
        if blocks is not None:
            far = min(self.local_end(tp), horizon)
            pre, cyc = blocks
            local = Segment(tp, far, slots(pre), slots(cyc)).unroll(tp, far)
            out += local
            # where the rules stop is where the rules stop: a slot that has
            # shrunk below the rounding epsilon is dropped from them, so the
            # last block may fall a sliver short of the plan's own reach, and
            # the standing chain takes over exactly there rather than leaving a
            # sliver of nothing between the two
            far = local[-1][1] if local else tp
        if far < horizon:
            tail = [(p.start, p.end, p.task) for p in self.base]
            if self.segments and self.front < horizon:
                tail += self.segments[-1].unroll(self.front, horizon)
            out += [(max(s, far), min(e, horizon), n) for s, e, n in tail
                    if e > far and s < horizon]
        return self._join([b for b in out if b[1] > b[0]])

    @staticmethod
    def _join(tl):
        out = []
        for s, e, n in tl:
            if e <= s: continue
            if out and out[-1][2] == n and out[-1][1] == s:
                out[-1] = (out[-1][0], e, n)
            else:
                out.append((s, e, n))
        return out

    # ---------------- the pace it owes ---------------- #

    def pace(self):
        """(timeline settled, seconds spent) over the work done so far."""
        return (sum((d for _s, d in self.steps), Fraction(0)),
                sum(s for s, _d in self.steps))

    def worst_step(self):
        """The link that settled the least timeline per second of work."""
        worst = None
        for s, d in self.steps:
            if d <= 0: continue
            if worst is None or float(d) / max(s, 1e-9) < float(worst[1]) / max(worst[0], 1e-9):
                worst = (s, d)
        return worst

    def rule_count(self):
        if not self.segments: return 0
        return max(len(s.prefix) + len(s.cycle) for s in self.segments)

    def lines(self, max_segments=None):
        out = [("The rule list is a CHAIN: link i holds from its own start until the next "
                "one, and the display reads it by binary search."),
               (f"Timeline span {stamp(self.span)}, {len(self.segments)} links from "
                f"{stamp(self.tp_start)} on, at most {self.rule_count()} rules each "
                f"(cap {MAX_RULES})."),
               f"Everything at t < {stamp(self.front)} is definitive.",
               ""]
        shown = self.segments if max_segments is None else self.segments[:max_segments]
        for i, s in enumerate(shown, 1):
            out.append(f"Link {i}  from {s.label}:")
            out.append(f"  Prefix: {s.prefix_text}")
            out.append(f"  Cycle:  {s.cycle_text}")
        if max_segments is not None and len(self.segments) > max_segments:
            out.append(f"... {len(self.segments) - max_segments} more links")
        return out
