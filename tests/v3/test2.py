#!/usr/bin/env python3
"""
Cyclic proportional-share scheduler with minimum slot durations.
=================================================================

Problem
-------
* Each task has a *minimum time* (once placed, nothing else can be placed until
  that minimum elapses) and a *priority percentage* (its share of the timeline).
* The timeline runs from t_now to +infinity, so the scheduler returns a finite
  set of RULES instead of an infinite list:

      [ fixed head (already-placed tasks) ]
      [ prefix    (one-shot catch-up slots) ]
      [ cycle     (repeats forever)         ]

* The share of every task inside the cycle is EXACTLY its priority, and the
  cycle is built at the smallest possible scale: every slot is the task's
  minimum time, and is only made longer when the task must catch up.

Algorithm
---------
Virtual-time / GPS (as in Weighted Fair Queuing), quantised by the minimums:

    v[i] = (time served by i) / p[i]        "virtual clock" of task i
                                            ideally v[i] == t for every i

    1. pick the task with the smallest v  (the most starved one)
    2. give it just enough time to catch up with the runner-up's virtual
       clock, but never less than its minimum time
    3. repeat

The state (v[i] - t) lives on a bounded rational lattice, so the walk always
falls into a loop; the first repeated state closes the cycle.  Because the
state repeats, dv[i] == dt over one cycle, i.e. served[i] == p[i] * period
*exactly*.  Everything is done with `fractions.Fraction`, so no drift.

Lookup of the schedule at any time t is O(log k) (bisect inside the cycle,
after one modulo), k being the small, constant number of rules.

Durations are unitless; the demo reads them as minutes.
"""

from __future__ import annotations

from bisect import bisect_right
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from fractions import Fraction
from itertools import accumulate, pairwise

# --------------------------------------------------------------------------- #
#  helpers
# --------------------------------------------------------------------------- #

Number = Fraction | int | float | str


def frac(x: Number) -> Fraction:
    """Exact Fraction from int / str / Fraction, and sane one from float."""
    if isinstance(x, Fraction):
        return x
    if isinstance(x, float):
        return Fraction(x).limit_denominator(10**9)
    return Fraction(x)


def ceil_to(x: Fraction, step: Fraction) -> Fraction:
    """Smallest multiple of `step` that is >= x."""
    return Fraction(-((-x) // step)) * step


def human(d: Fraction, unit_seconds: int = 60) -> str:
    """Pretty-print a duration expressed in `unit` (default: minutes)."""
    total = d * unit_seconds
    if total.denominator != 1:
        return f"{float(d):.4g}u"
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


# --------------------------------------------------------------------------- #
#  data model
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Task:
    """A task: `priority` is a weight (percentages are fine), `min_time` > 0."""

    name: str
    priority: Fraction
    min_time: Fraction

    def __init__(self, name: str, priority: Number, min_time: Number):
        object.__setattr__(self, "name", str(name))
        object.__setattr__(self, "priority", frac(priority))
        object.__setattr__(self, "min_time", frac(min_time))
        if self.priority < 0:
            raise ValueError(f"{name}: negative priority")
        if self.min_time <= 0:
            raise ValueError(f"{name}: min_time must be > 0")


@dataclass(frozen=True)
class Slot:
    """A rule: run `task` for `duration`."""

    task: str
    duration: Fraction

    def __str__(self) -> str:
        return f"{self.task} {human(self.duration)}"


@dataclass(frozen=True)
class Placement:
    """A concrete piece of timeline."""

    task: str
    start: Fraction
    end: Fraction

    def __init__(self, task: str, start: Number, end: Number):
        object.__setattr__(self, "task", str(task))
        object.__setattr__(self, "start", frac(start))
        object.__setattr__(self, "end", frac(end))

    @property
    def duration(self) -> Fraction:
        return self.end - self.start

    def __str__(self) -> str:
        return f"[{human(self.start)} -> {human(self.end)}] {self.task}"


# --------------------------------------------------------------------------- #
#  the plan (the finite list of rules)
# --------------------------------------------------------------------------- #


@dataclass
class Plan:
    """`fixed` then `prefix`, then `cycle` forever."""

    start: Fraction                  # where the schedulable timeline begins
    prefix: list[Slot]
    cycle: list[Slot]
    fixed: list[Placement] = field(default_factory=list)
    shares: dict[str, Fraction] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.period = sum((s.duration for s in self.cycle), Fraction(0))
        self.cycle_start = self.start + sum(
            (s.duration for s in self.prefix), Fraction(0)
        )
        self._pre_off = [Fraction(0), *accumulate(s.duration for s in self.prefix)]
        self._cyc_off = [Fraction(0), *accumulate(s.duration for s in self.cycle)]

    # ---------------- queries ---------------- #

    def task_at(self, t: Number) -> str | None:
        """Which task occupies instant t?  O(log k)."""
        t = frac(t)
        for p in self.fixed:                       # tiny, fixed-size head
            if p.start <= t < p.end:
                return p.task
        if t < self.start:
            return None
        if t < self.cycle_start:
            i = bisect_right(self._pre_off, t - self.start) - 1
            return self.prefix[i].task
        if not self.period:
            return None
        k = (t - self.cycle_start) % self.period
        i = bisect_right(self._cyc_off, k) - 1
        return self.cycle[i].task

    def expand(self, t_from: Number, t_to: Number, merge: bool = True
               ) -> list[Placement]:
        """Materialise the timeline on [t_from, t_to)."""
        t_from, t_to = frac(t_from), frac(t_to)
        raw: list[Placement] = []

        def push(task: str, a: Fraction, b: Fraction) -> None:
            a, b = max(a, t_from), min(b, t_to)
            if a < b:
                raw.append(Placement(task, a, b))

        for p in self.fixed:
            push(p.task, p.start, p.end)

        cur = self.start
        for s in self.prefix:
            push(s.task, cur, cur + s.duration)
            cur += s.duration

        if self.period:
            # jump straight to the first cycle overlapping the window: O(1)
            n = max(0, (t_from - self.cycle_start) // self.period)
            cur = self.cycle_start + n * self.period
            while cur < t_to:
                for s in self.cycle:
                    push(s.task, cur, cur + s.duration)
                    cur += s.duration

        if not merge:
            return raw
        out: list[Placement] = []
        for p in raw:
            if out and out[-1].task == p.task and out[-1].end == p.start:
                out[-1] = Placement(p.task, out[-1].start, p.end)
            else:
                out.append(p)
        return out

    # ---------------- display ---------------- #

    def rules(self) -> list[str]:
        lines = []
        for p in self.fixed:
            lines.append(f"(fixed) {p}")
        for s in self.prefix:
            lines.append(f"(once)  {s}")
        for s in self.cycle:
            lines.append(f"(loop)  {s}")
        lines.append(f"(loop)  repeat  -- period {human(self.period)}")
        return lines

    def __str__(self) -> str:
        head = f"Plan (schedulable from t={human(self.start)})"
        body = "\n".join(f"  {i:>2}. {l}" for i, l in enumerate(self.rules(), 1))
        shares = "  shares: " + ", ".join(
            f"{n} {float(v) * 100:.4g}%" for n, v in sorted(self.shares.items())
        )
        return f"{head}\n{body}\n{shares}"


# --------------------------------------------------------------------------- #
#  the scheduler
# --------------------------------------------------------------------------- #


class Scheduler:
    def __init__(self, tasks: Iterable[Task], resolution: Number | None = None):
        tasks = list(tasks)
        active = [t for t in tasks if t.priority > 0]
        if not active:
            raise ValueError("no task with a positive priority")
        total = sum(t.priority for t in active)

        self.tasks = active
        self.dropped = [t.name for t in tasks if t.priority == 0]
        self.p = {t.name: t.priority / total for t in active}
        self.minimum = {t.name: t.min_time for t in active}
        self.resolution = frac(resolution) if resolution else None
        # theoretical lower bound of the period: every task needs one slot
        self.min_period = max(self.minimum[n] / self.p[n] for n in self.p)

    # ---------------- one greedy step ---------------- #

    def _pick(self, v: dict[str, Fraction]) -> str:
        # most starved task; ties -> biggest share, then name (deterministic)
        return min(v, key=lambda n: (v[n], -self.p[n], n))

    def _chunk(self, name: str, v: dict[str, Fraction]) -> Fraction:
        others = [vv for n, vv in v.items() if n != name]
        target = min(others) if others else v[name]
        need = self.p[name] * (target - v[name])   # time to catch the runner-up
        c = max(self.minimum[name], need)
        if self.resolution:
            c = ceil_to(c, self.resolution)
        return c

    # ---------------- the plan ---------------- #

    def plan(
        self,
        timeline: Sequence[Placement] = (),
        t_now: Number = 0,
        lookback: Number | None = None,
        max_steps: int = 20_000,
    ) -> Plan:
        """Build the finite rule list, honouring what is already placed."""
        t_now = frac(t_now)
        timeline = sorted(timeline, key=lambda p: p.start)

        # 1. what is already committed cannot be moved
        fixed = [p for p in timeline if p.end > t_now]
        t_start = max([t_now, *(p.end for p in timeline)]) if timeline else t_now

        # 2. debt accumulated over the lookback window
        window = frac(lookback) if lookback is not None else self.min_period
        w_start = t_start - window
        if timeline:
            w_start = max(w_start, min(p.start for p in timeline))
        w_start = min(w_start, t_start)
        elapsed = t_start - w_start

        served = {n: Fraction(0) for n in self.p}
        for p in timeline:
            if p.task in served:
                served[p.task] += max(
                    Fraction(0), min(p.end, t_start) - max(p.start, w_start)
                )

        # virtual clock; only differences matter, so renormalise to >= 0
        lag = {n: served[n] / self.p[n] - elapsed for n in self.p}
        base = min(lag.values())
        v = {n: lag[n] - base for n in self.p}
        t = Fraction(0)

        # 3. walk until a state repeats -> prefix + cycle
        slots: list[Slot] = []
        seen: dict[tuple, int] = {self._key(v, t): 0}
        prefix: list[Slot]
        cycle: list[Slot]
        while True:
            name = self._pick(v)
            c = self._chunk(name, v)
            slots.append(Slot(name, c))
            v[name] += c / self.p[name]
            t += c

            key = self._key(v, t)
            if key in seen:
                i = seen[key]
                prefix, cycle = slots[:i], slots[i:]
                break
            seen[key] = len(slots)
            if len(slots) >= max_steps:            # safety net, never hit in practice
                prefix, cycle = [], self._fallback_cycle()
                break

        prefix, cycle = self._tidy(prefix, cycle)
        period = sum((s.duration for s in cycle), Fraction(0))
        shares = {n: Fraction(0) for n in self.p}
        for s in cycle:
            shares[s.task] += s.duration / period
        return Plan(start=t_start, prefix=prefix, cycle=cycle,
                    fixed=fixed, shares=shares)

    # ---------------- internals ---------------- #

    @staticmethod
    def _key(v: dict[str, Fraction], t: Fraction) -> tuple:
        return tuple(sorted((n, vv - t) for n, vv in v.items()))

    def _fallback_cycle(self) -> list[Slot]:
        T = self.min_period
        order = sorted(self.p, key=lambda n: (-self.p[n], n))
        return [Slot(n, self.p[n] * T) for n in order]

    @staticmethod
    def _merge_run(slots: list[Slot]) -> list[Slot]:
        out: list[Slot] = []
        for s in slots:
            if out and out[-1].task == s.task:
                out[-1] = Slot(s.task, out[-1].duration + s.duration)
            else:
                out.append(s)
        return out

    def _tidy(self, prefix: list[Slot], cycle: list[Slot]
              ) -> tuple[list[Slot], list[Slot]]:
        """No two consecutive slots of the same task, wrap-around included."""
        prefix, cycle = self._merge_run(list(prefix)), self._merge_run(list(cycle))

        # cycle wraps onto itself: move the head out, merge it into the tail
        while len(cycle) > 1 and cycle[0].task == cycle[-1].task:
            head = cycle.pop(0)
            cycle[-1] = Slot(cycle[-1].task, cycle[-1].duration + head.duration)
            prefix.append(head)
        prefix = self._merge_run(prefix)

        # prefix/cycle junction: rotate the cycle once (timeline unchanged)
        for _ in range(len(cycle)):
            if not (prefix and len(cycle) > 1 and prefix[-1].task == cycle[0].task):
                break
            head = cycle.pop(0)
            cycle.append(head)
            prefix[-1] = Slot(head.task, prefix[-1].duration + head.duration)
        return prefix, cycle

    # ---------------- checking ---------------- #

    def check(self, plan: Plan) -> None:
        """Assert the rules are legal and exactly proportional."""
        for s in plan.prefix + plan.cycle:
            assert s.duration >= self.minimum[s.task], f"slot too short: {s}"
        seq = plan.cycle + plan.cycle
        for a, b in pairwise(seq):
            assert a.task != b.task, f"consecutive identical slots: {a}, {b}"
        for n, p in self.p.items():
            assert plan.shares.get(n, 0) == p, f"share mismatch on {n}"
        assert plan.period >= self.min_period, "period below the lower bound"


# --------------------------------------------------------------------------- #
#  demo
# --------------------------------------------------------------------------- #


def demo(title: str, tasks: list[Task], **kw) -> Plan:
    sch = Scheduler(tasks, resolution=kw.pop("resolution", None))
    plan = sch.plan(**kw)
    sch.check(plan)
    print(f"\n=== {title} ===")
    for t in tasks:
        print(f"  {t.name}: {float(t.priority):g}%  min {human(t.min_time)}")
    print(plan)
    print(f"  lower bound on period: {human(sch.min_period)}")
    print("  timeline t=0..:")
    for p in plan.expand(0, min(plan.cycle_start + 2 * plan.period, 480)):
        print(f"     {p}")
    return plan


if __name__ == "__main__":
    demo(
        "the reference example",
        [Task("A", 50, 10), Task("B", 50, 10)],
    )

    demo(
        "lopsided priorities",
        [Task("A", 90, 10), Task("B", 10, 10)],
    )

    demo(
        "three tasks",
        [Task("A", 70, 15), Task("B", 20, 10), Task("C", 10, 5)],
    )

    demo(
        "already-placed timeline (B ran a lot, C is committed until t=95)",
        [Task("A", 70, 15), Task("B", 20, 10), Task("C", 10, 5)],
        timeline=[
            Placement("B", 0, 40),
            Placement("A", 40, 90),
            Placement("C", 90, 95),
        ],
        t_now=92,
    )

    demo(
        "rounded to 5-minute steps",
        [Task("A", 45, 12), Task("B", 35, 7), Task("C", 20, 9)],
        resolution=5,
    )

    # O(1)-ish lookup far in the future
    sch = Scheduler([Task("A", 70, 15), Task("B", 20, 10), Task("C", 10, 5)])
    plan = sch.plan()
    print("\n=== lookup far away ===")
    for t in (0, 20, 10**6, 10**6 + 7, 10**12):
        print(f"  t={t}: {plan.task_at(t)}")