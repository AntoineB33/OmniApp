#!/usr/bin/env python3
"""
scheduler_logic.py
Core scheduling algorithms, fractions-based math scheduler, and algebraic models.
"""

from dataclasses import dataclass, field
from fractions import Fraction
from math import ceil, exp, inf, log

MAX_RULES = 50
IDLE_COLOR = "#F0F0F0"
FOREVER = None
DEBT_EPS = 0.5

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

    def _chunk(self, name, v, candidates, p=None, boost=None, T=None):
        p = p or self.p
        others = [v[n] for n in candidates if n != name]
        target = min(others) if others else v[name]
        need = p[name] * (target - v[name])
        c = max(self.minimum[name], need)
        if boost is not None:
            unit = max(self.minimum[name], p[name] * T)
            c = min(max(c, self.minimum[name] * boost), unit * boost)
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

    def plan(self, timeline=(), periods=(), t_now=0, lookback=None, max_rules=MAX_RULES):
        t_now = frac(t_now)
        timeline = sorted(timeline, key=lambda p: p.start)
        periods = self._normalise_periods(periods)
        self._set_field(timeline, periods)

        past = [p for p in timeline if p.end <= t_now]
        pre = [p for p in timeline if p.end > t_now]

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

        slots = []
        last = None
        free_tail = False
        steps, max_steps = 0, 200 * max_rules

        def owed(n):
            if n == last and slots and slots[-1].task == n:
                return max(Fraction(0), self.minimum[n] - slots[-1].duration)
            return self.minimum[n]

        while len(slots) < max_rules and steps < max_steps:
            steps += 1
            allowed = self._allowed_at(periods, t)
            T = self._period(allowed) if allowed else self.min_period

            block = self._active_pre(pre, t)
            if block:
                d = block.end - t
                self._push(slots, block.task, d, block.color)
                if block.task in v: v[block.task] += d / self.p[block.task]
                t = block.end
                last = block.task
                free_tail = False
                self._relax(v, 0, T, allowed)
                self._clamp(v, t)
                continue

            limit = self._next_boundary(pre, periods, t)
            if limit is None and (self.field_end is None or t >= self.field_end):
                break

            room = {n: self._blocked_from(n, pre, periods, t) for n in allowed}
            fitting = [n for n in allowed if room[n] is None or room[n] - t >= owed(n)]

            if not fitting:
                if limit is None: break
                gap = limit - t
                if free_tail and slots:
                    tail = slots[-1]
                    slots[-1] = Slot(tail.task, tail.duration + gap, tail.color)
                    v[tail.task] += gap / self.p[tail.task]
                else:
                    self._push(slots, "IDLE", gap, IDLE_COLOR)
                    last = None
                    free_tail = False
                t += gap
                continue

            name = self._pick(v, fitting, last)
            boost = self._boost(name, t)
            c = self._chunk(name, v, fitting, boost=boost, T=T)

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
        if allowed and len(slots) < max_rules:
            T = self._period(allowed)
            horizon = t + 4 * T
            while len(slots) < max_rules and t < horizon:
                spread = max(v[n] for n in allowed) - min(v[n] for n in allowed)
                if spread <= T: break
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

def flatten(segments):
    out = []
    for seg in segments:
        if 'blocks' in seg:
            for _ in range(int(seg.get('repeat', 1))):
                out.extend(seg['blocks'])
        else:
            out.append(seg)
    return out

def rule_lines(segments, indent="- "):
    lines = []
    for seg in segments:
        if 'blocks' in seg:
            lines.append(f"{indent}repeat {seg['repeat']}x:")
            for b in seg['blocks']:
                lines.append(f"{indent}    task {b['name']} {duration_text(b['duration'])}")
        else:
            lines.append(f"{indent}task {seg['name']} {duration_text(seg['duration'])}")
    return lines

def duration_text(d):
    return d.text() if isinstance(d, Aff) else human(d)

@dataclass(frozen=True)
class Aff:
    const: float = 0.0
    coef: float = 0.0
    var: str = ""

    def value(self, env):
        return self.const + (self.coef * env[self.var] if self.var else 0.0)

    def text(self):
        if not self.var or abs(self.coef) < 1e-12:
            return human_s(self.const)
        mag = self.var if abs(abs(self.coef) - 1) < 1e-12 else f"{abs(self.coef):.4g}*{self.var}"
        if abs(self.const) < 1e-12:
            return mag if self.coef > 0 else f"-{mag}"
        if self.coef > 0:
            return (f"{mag} + {human_s(self.const)}" if self.const > 0
                    else f"{mag} - {human_s(-self.const)}")
        return (f"{human_s(self.const)} - {mag}" if self.const > 0
                else f"-{human_s(-self.const)} - {mag}")

@dataclass
class Regime:
    name: str
    lo: float
    hi: float
    prefix: list
    cycle: list

def resolve(segments, env):
    out = []
    for seg in segments:
        if 'blocks' in seg:
            n = int(env[seg['repeat']]) if isinstance(seg['repeat'], str) else int(seg['repeat'])
            if n <= 0: continue
            out.append({'repeat': n,
                        'blocks': [{'name': b['name'], 'duration': b['duration'].value(env),
                                    'color': b['color']} for b in seg['blocks']]})
        else:
            out.append({'name': seg['name'], 'duration': seg['duration'].value(env),
                        'color': seg['color']})
    return out

class MovingWindowPlan:
    def __init__(self, tasks, window_sec=20.0, eps=DEBT_EPS):
        sched = Scheduler(tasks)
        names = [t.name for t in tasks]
        if len(names) != 2:
            raise ValueError("the closed form covers exactly two tasks")
        a, b = names
        if sched.p[a] != sched.p[b]:
            raise ValueError("the closed form covers equal shares only")

        self.sched = sched
        self.a, self.b = a, b
        self.color = {a: tasks[0].color, b: tasks[1].color, "IDLE": IDLE_COLOR}

        self.period = float(sched.min_period) * 60.0                     
        self.block = {n: float(sched.p[n]) * self.period for n in names}  
        self.minimum = {n: float(sched.minimum[n]) * 60.0 for n in names}  
        self.tau = float(sched.tau) * 60.0
        self.window = float(window_sec)

        if self.window >= min(self.minimum.values()):
            raise ValueError("the closed form assumes the window is shorter than any minimum")

        self.T_A = self.block[a]
        self.stretch_from = self.T_A - self.window
        self.t_1 = self.T_A

        self.weights = self._weights(eps)
        self.regimes = self._build()

    def _weights(self, eps):
        decay = self.period / self.tau
        if decay <= 0:
            return [1.0]
        k = ceil(log(max(self.window / eps, 1.0)) / decay)
        k = max(1, min(k, MAX_RULES // 4))
        raw = [exp(-i * decay) for i in range(k)]
        total = sum(raw)
        return [r / total for r in raw]

    def _blk(self, name, duration):
        return {'name': name, 'duration': duration, 'color': self.color[name]}

    def _cycle(self):
        return [self._blk(self.a, Aff(self.block[self.a])),
                self._blk(self.b, Aff(self.block[self.b]))]

    def _frozen_past(self):
        return {'repeat': 'n', 'blocks': self._cycle()}

    def _build(self):
        a, b, W, T = self.a, self.b, self.window, self.period
        T_A, T_B = self.block[a], self.block[b]

        r1 = Regime("window inside A -> untouched cycle", 0.0, self.stretch_from,
                    [self._frozen_past()], self._cycle())

        p2 = [self._frozen_past(), self._blk(a, Aff(T_A, 1.0, 'e'))]
        for k, w in enumerate(self.weights):
            p2.append(self._blk(b, Aff(T_B, w, 'e')))
            if k != len(self.weights) - 1:
                p2.append(self._blk(a, Aff(T_A)))
        r2 = Regime("window on the A/B seam -> A absorbs it, B repaid",
                    self.stretch_from, self.t_1, p2, self._cycle())

        p3 = [self._frozen_past(),
              self._blk(a, Aff(T_A)),
              self._blk(b, Aff(-T_A, 1.0, 't')),      
              self._blk("IDLE", Aff(W)),
              self._blk(b, Aff(T_A + T_B, -1.0, 't'))]  
        r3 = Regime("window inside B -> B pauses and resumes", self.t_1, T, p3, self._cycle())

        return [r1, r2, r3]

    def phase(self, tp_sec):
        tp = max(0.0, float(tp_sec))
        n = int(tp // self.period)
        return n, tp - n * self.period

    def regime_at(self, t):
        for r in self.regimes:
            if t <= r.hi: return r
        return self.regimes[-1]

    def regime_at_tp(self, tp_sec):
        return self.regime_at(self.phase(tp_sec)[1])

    def instantiate(self, tp_sec, to_minutes=True):
        n, t = self.phase(tp_sec)
        env = {'n': n, 't': t, 'e': t + self.window - self.T_A}
        r = self.regime_at(t)

        prefix = resolve(r.prefix, env)
        cycle = resolve(r.cycle, env)

        prefix = [s for s in prefix if 'blocks' in s or s['duration'] > 1e-12]
        if prefix and 'blocks' not in prefix[-1] and cycle and prefix[-1]['name'] == cycle[0]['name']:
            cycle = cycle[1:] + cycle[:1]

        if to_minutes:
            conv = lambda blocks: [dict(x, duration=frac(x['duration'] / 60.0)) for x in blocks]
            prefix = [{'repeat': s['repeat'], 'blocks': conv(s['blocks'])} if 'blocks' in s
                      else conv([s])[0] for s in prefix]
            cycle = conv(cycle)
        return prefix, cycle

    __call__ = instantiate

    def lines(self):
        out = ["Parameters (all read from the scheduler):",
               (f"  T = {human_s(self.period)}   W = {human_s(self.window)}   "
                f"tau = {human_s(self.tau)}   T_A = T_B = {human_s(self.T_A)}"),
               "  n = floor(t_p / T)          number of frozen cycles before the window",
               "  t = t_p - n*T               phase of the window inside its cycle",
               "  e = t + W - T_A             overrun the window forces on A",
               f"  decay weights w_k = exp(-k*T/tau) normalised, k < {len(self.weights)}: "
               + ", ".join(f"{w:.4f}" for w in self.weights),
               ""]
        for i, r in enumerate(self.regimes, 1):
            lo = "0" if r.lo == 0 else human_s(r.lo)
            rel = "<=" if r.lo == 0 else "<"
            out.append(f"Regime {i}  ({lo} {rel} t <= {human_s(r.hi)}): {r.name}")
            out.append("  Prefix:")
            out.extend(rule_lines(r.prefix, "    "))
            out.append("  Cycle:")
            out.extend(rule_lines(r.cycle, "    "))
            out.append("    repeat")
            out.append("")
        return out

    def rule_count(self):
        return max(len(r.prefix) + len(r.cycle) for r in self.regimes)