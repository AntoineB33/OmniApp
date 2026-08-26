#!/usr/bin/env python3
"""
test_configs.py
The `scheduler` test configurations, and the checks each family owes.

The fourteen cases of `tests.md`, stated in `scheduler`'s vocabulary (tasks
with a resilience per kind of restrictive period, periods with a kind, one
`Scheduler` per case), and the checks that say whether the answers are the ones
`tests.md` and `README.md` describe.

Three families, and the difference between them is what the answer IS:

* Tests 1-9b are STATIC: one environment, one walk, one timeline. What they owe
  is the two optimisation criteria (percentages, granularity), the minimums,
  no idling, and an alternative named at every rule.
* Tests 10 and 11 SLIDE: a period of the case's own moves with t_p
  (`Scheduler(sliding=...)`), so the answer is a rule list parameterised by the
  line's position. What they owe on top is the frozen past, and -- test 11 --
  the collision rule: the moment the sliding 20s window's right edge touches
  the waiting five-minute stretch, the window is gone for good and the stretch
  is what slides, having teleported one window-width to the left.
* Tests 12-14 are PROGRESSIVE: three days (eight, for test 14) that no single
  pass answers in one go, so the schedule is settled link by link with a front
  moving along it. What they owe on top is the pace (ten minutes of definitive
  schedule per ten seconds of work), the recurrence bars of the three dynamic
  periods, and the resume contract -- a chain of links must be the same
  schedule as one long plan, or a partial answer is not an answer.

Run the checks:  uv run test_configs.py --verify
The display is `tests_displayer.py`, which draws exactly these cases.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field, replace
from fractions import Fraction

import scheduler
from scheduler import (
    DAY,
    HOUR,
    IDLE,
    KIND_NO_S,
    KIND_NO_TASK,
    SECOND,
    DynamicSpec,
    Environment,
    Scheduler,
    block,
    clip,
    coalesce,
    frac,
    human,
    period,
    resulting_shares,
    same_timeline,
    state,
    task,
)

WINDOW = 20 * SECOND             # the 20 s period both sliding cases carry


# --------------------------------------------------------------------------- #
#  the shape of a case
# --------------------------------------------------------------------------- #

@dataclass
class Case:
    """One test: what it is, the scheduler that answers it, and how it moves.

    `sweep` is how many seconds a full pass of t_p takes in the display; a
    static case has none. `tp_start` / `tp_sweep` are test 12's two positions:
    the line waits at the first while the chain settles and teleports to the
    second when the day under it is definitive.
    """
    number: str
    title: str
    note: str
    sched: Scheduler
    span: Fraction
    kind: str = "static"                 # static | moving | progressive
    sweep: float = 0.0
    tp_start: Fraction = Fraction(0)
    tp_sweep: Fraction | None = None
    targets: dict = field(default_factory=dict)

    @property
    def name(self):
        return f"Test {self.number}"

    @property
    def heading(self):
        return f"{self.name}: {self.title}"

    def fresh(self):
        """A case is one scheduler instance (`tests.md`: "one instance of the
        same scheduler per test"), so a check that needs a clean line asks for
        the case to be built again rather than reaching into this one."""
        return CASES_BY_NUMBER[self.number]()


# --------------------------------------------------------------------------- #
#  building blocks
# --------------------------------------------------------------------------- #

def banned(specs, kind, names, value=0):
    """The same tasks, with `names` given a resilience of `value` to `kind`.

    A "period that forbids C" is, in this model, a period of a kind C has no
    resilience to -- so a case states its bans by naming a kind and handing it
    to the tasks it bites.
    """
    out = []
    for spec in specs:
        if spec.name in names:
            res = dict(spec.resilience)
            res[kind] = frac(value)
            out.append(replace(spec, resilience=tuple(sorted(res.items()))))
        else:
            out.append(spec)
    return tuple(out)


def AB():
    return (task("A", 50, 10, s=True, color="#FF9999"),
            task("B", 50, 10, s=True, color="#99CCFF"))


def _static(number, title, note, tasks, span, blocks=(), periods=(), **kw):
    sched = Scheduler([state(0, tasks)],
                      Environment(periods=periods, blocks=blocks),
                      dynamics=(), horizon=frac(span), **kw)
    return Case(number, title, note, sched, frac(span))


# --------------------------------------------------------------------------- #
#  Tests 1-9b: static
# --------------------------------------------------------------------------- #

def case1():
    return _static("1", "normal 50/50 split, 10min minimums",
                   "a pure alternation at the smallest granularity the minimums allow: "
                   "10min each, never one 90-minute block apiece",
                   AB(), 180)


def case2():
    return _static("2", "a pre-placed hour owned by nobody",
                   "MAINTENANCE excludes everybody equally, so it deprives nobody "
                   "relative to anybody and creates no field: A and B resume alternating",
                   AB(), 240, blocks=[block("MAINTENANCE", 40, 60)])


def case3():
    tasks = banned((task("A", 40, 10, s=True, color="#FF9999"),
                    task("B", 40, 10, s=True, color="#99CCFF"),
                    task("C", 20, 10, s=True, color="#99FF99")), "no C", ["C"])
    return _static("3", "C is banned from t=105 on, for good",
                   "C is abundantly present just before the door closes -- the field "
                   "reaches ahead of a blockage as well as behind it -- then A and B share the rest",
                   tasks, 300, periods=[period(105, 300, "no C", "no C")])


def case4():
    tasks = (task("A", 50, 20, s=True, color="#FF9999"),
             task("B", 30, 10, s=True, color="#99CCFF"),
             task("C", 20, 15, s=True, color="#99FF99"))
    return _static("4", "three tasks: A 50%/20min, B 30%/10min, C 20%/15min",
                   "the minimums force a 75-minute period and the shares come out exact",
                   tasks, 400)


def case5():
    tasks = (task("A", 90, 10, s=True, color="#FF9999"),
             task("B", 10, 10, s=True, color="#99CCFF"))
    return _static("5", "lopsided 90/10, with 40 minutes of B pre-placed at the start",
                   "A gets a denser, BOUNDED catch-up around the block -- not the "
                   "396 minutes a full repayment would owe it",
                   tasks, 600, blocks=[block("B", 0, 40)])


def case6():
    return _static("6", "an hour of A pre-placed at t=100",
                   "B's slots swell as the block approaches and shrink back after it: "
                   "the influence decays exponentially on both sides",
                   AB(), 400, blocks=[block("A", 100, 60)])


def case7():
    return _static("7", "ten hours of A at t=100 -- ten times test 6",
                   "B's presence around it is wider and denser, but only a few times "
                   "bigger: the amplitude is logarithmic in the length, not proportional",
                   AB(), 1000, blocks=[block("A", 100, 600)], tau_scale=2)


def case8():
    tasks = banned(AB(), "no B", ["B"])
    return _static("8", "B banned from t=100 to t=400 -- a window, not a block",
                   "same field, same ramps: B swells before the ban and again as soon "
                   "as it re-opens, then decays back into the cycle",
                   tasks, 700, periods=[period(100, 400, "no B", "no B")], tau_scale=2)


def case9():
    tasks = banned(AB(), "no B", ["B"])
    windows = [period(100 + 30 * i, 130 + 30 * i, "no B", "no B") for i in range(10)]
    return _static("9", "the same 300-minute ban, split into ten consecutive windows",
                   "ten short bans in a row are ONE long ban: they merge into a single "
                   "exclusion rather than ten small ones",
                   tasks, 700, periods=windows, tau_scale=2)


def case9b():
    tasks = (task("A", 40, 10, s=True, color="#FF9999"),
             task("B", 30, 10, s=True, color="#99CCFF"),
             task("C", 30, 10, s=True, color="#99FF99"))
    tasks = banned(banned(tasks, "no C", ["C"]), "no B", ["B"])
    return _static("9b", "two OVERLAPPING periods, on a timeline they do not cover",
                   "what an instant refuses is the SUM of the periods over it: C is out "
                   "from 100, B joins it at 200, and where they overlap A holds the timeline alone",
                   tasks, 700,
                   periods=[period(100, 300, "no C", "no C"),
                            period(200, 400, "no B", "no B")], tau_scale=2)


# --------------------------------------------------------------------------- #
#  Test 10: one 20 s period accepting only A, sliding right with the line
# --------------------------------------------------------------------------- #

TEST10_SPAN = frac(60)


def test10_sliding(t_p, _mode):
    return [period(t_p, t_p + WINDOW, "no B", "20s: only A")]


def case10():
    tasks = banned(AB(), "no B", ["B"])
    sched = Scheduler([state(0, tasks)], Environment(), dynamics=(),
                      sliding=test10_sliding, horizon=TEST10_SPAN)
    return Case("10", "a 20s period accepting only A slides right from t_p",
                "while it fits inside a slot of A nothing moves; once it reaches B, A "
                "stretches over it; once it is inside B, B is suspended and resumes on the far side",
                sched, TEST10_SPAN, kind="moving", sweep=25.0)


# --------------------------------------------------------------------------- #
#  Test 11: the same window, accepting NOTHING, in a crowded timeline -- and a
#  five-minute stretch it drags off its home the moment it touches it
# --------------------------------------------------------------------------- #

TEST11_SPAN = frac(60)
STRETCH_HOME = frac(45)          # where the five-minute stretch waits
STRETCH_HEAD = frac(1)           # its first minute accepts nobody
STRETCH_LEN = frac(5)


def reached_stretch(t_p) -> bool:
    """Has the sliding window reached the waiting stretch?

    Contact is the instant the window's right edge touches STRETCH_HOME, so the
    window is still whole at that position and gone from the next one on."""
    return frac(t_p) + WINDOW > STRETCH_HOME


def stretch_start(t_p) -> Fraction:
    """Where the stretch sits when the window starts at t_p: at home until the
    window reaches it, and at the line from then on -- so at the instant of
    contact it teleports one window-width (20 s) to the left."""
    return frac(t_p) if reached_stretch(t_p) else STRETCH_HOME


def test11_sliding(t_p, _mode):
    s = stretch_start(t_p)
    out = []
    if not reached_stretch(t_p):
        out.append(period(t_p, t_p + WINDOW, KIND_NO_TASK, "20s: nothing"))
    out.append(period(s, s + STRETCH_HEAD, KIND_NO_TASK, "1min: nothing"))
    out.append(period(s + STRETCH_HEAD, s + STRETCH_LEN, "only A", "4min: only A"))
    return out


def case11():
    tasks = (task("A", 40, 4, s=True, color="#FF9999"),
             task("B", 20, 5, s=True, color="#99CCFF"),
             task("C", 20, 5, s=True, color="#99FF99"),
             task("D", 20, 5, s=True, color="#FFCC66"))
    tasks = banned(tasks, "only A", ["B", "C", "D"])
    tasks = banned(tasks, "no CD", ["C", "D"])
    tasks = banned(tasks, "no BD", ["B", "D"])
    env = Environment(
        periods=[period(8, 12, "no CD", "no C, D"),
                 period(30, 34, KIND_NO_TASK, "nothing"),
                 period(52, 56, "no BD", "no B, D")],
        blocks=[block("MAINTENANCE", 18, 6), block("A", 40, 4)])
    sched = Scheduler([state(0, tasks)], env, dynamics=(),
                      sliding=test11_sliding, horizon=TEST11_SPAN)
    return Case("11", "the sliding 20s period accepts NOTHING, in a crowded timeline",
                "pre-placed blocks, three static periods, four tasks, and a "
                f"1min-nothing + 4min-only-A stretch waiting at {human(STRETCH_HOME)}: the moment "
                "the window touches it the window is gone for good and the stretch is what slides",
                sched, TEST11_SPAN, kind="moving", sweep=35.0)


# --------------------------------------------------------------------------- #
#  Tests 12-14: progressive
# --------------------------------------------------------------------------- #

PRIVILEGED = [f"P{i}" for i in range(1, 11)]
ORDINARY = [f"N{i}" for i in range(1, 11)]

# The privileged-only periods refuse everybody else, and "everybody else" is
# what the README marks "s" -- so KIND_NO_S IS "privileged only" here, and the
# three dynamic periods need no kind of their own.
TEST12_SPAN = 3 * DAY
TEST12_TP_START = Fraction(0)    # where the line waits while the chain settles
TEST12_TP_SWEEP = DAY            # ...and where it teleports to, and sweeps from
TEST14_SPAN = 8 * DAY


def _shade(base, i, n):
    r, g, b = base
    k = 0.72 + 0.28 * (i / max(n - 1, 1))
    return "#" + "".join(f"{min(255, int(c * k)):02X}" for c in (r, g, b))


def tasks12(main_share=50, privileged_share=Fraction(5, 2)):
    """A at 50%, twenty others sharing the rest, half of them privileged.

    Everybody needs 45 minutes at a time, which is what makes the break grid
    interesting: the gap between two 20-second periods is twenty minutes.
    """
    ordinary_share = (frac(100) - main_share - 10 * privileged_share) / 10
    out = [task("A", main_share, 45, s=True, color="#FF7B7B")]
    out += [task(n, privileged_share, 45, s=False, color=_shade((110, 170, 255), i, 10))
            for i, n in enumerate(PRIVILEGED)]
    out += [task(n, ordinary_share, 45, s=True, color=_shade((255, 190, 90), i, 10))
            for i, n in enumerate(ORDINARY)]
    return tuple(out)


def nights12(span=TEST12_SPAN):
    """23h-8h: only the privileged may run -- and inside it, 0h-8h: nobody.

    They overlap, and overlapping periods multiply, so what the night says is
    "privileged only from 23h, nobody from midnight"."""
    out = []
    for d in range(int(span / DAY) + 2):
        s = d * DAY
        if 0 < s <= span:
            out.append(period(s - HOUR, s, KIND_NO_S, "23h-24h: privileged only"))
        if s < span:
            out.append(period(s, s + 8 * HOUR, KIND_NO_TASK, "0h-8h: nothing"))
    return [p for p in out if p.start < span]


#: test 12's dynamic periods have a SHAPE: the last four minutes of the
#: five-minute one, and the whole of the fifteen-minute one, accept the
#: privileged tasks. The README's plain form (all three accept nobody) is
#: `DEFAULT_DYNAMICS`, which tests 10-11 and `scheduler --check` use.
DYNAMICS_12 = (
    DynamicSpec("20s", WINDOW),
    DynamicSpec("5min", frac(5), ((Fraction(0), Fraction(1), KIND_NO_TASK),
                                  (Fraction(1), Fraction(4), KIND_NO_S))),
    DynamicSpec("15min", frac(15), ((Fraction(0), Fraction(15), KIND_NO_S),)),
)


def privileged_only(weights, specs) -> bool:
    """Test 12's reading of a "stretch": one where no unprivileged task may
    run, whether or not a privileged one is running in it.

    The README's own reading is stricter (nobody at all runs there) and is the
    default in `DynamicPlanner`; this is the variant `tests.md` states, and the
    two differ exactly on a privileged-only stretch.
    """
    return all(weights[s.name] == 0 for s in specs if s.s)


def case12():
    sched = Scheduler([state(0, tasks12())], Environment(periods=nights12()),
                      dynamics=DYNAMICS_12, horizon=TEST12_SPAN,
                      stretch_when=privileged_only)
    targets = {"A": Fraction(1, 2), "privileged": Fraction(1, 4), "ordinary": Fraction(1, 4)}
    return Case("12", "three days, 21 tasks, a night a day, and the three dynamic periods",
                "the line waits at the origin while the chain settles the three days from "
                "t=0, teleports to 24h the moment the first day is definitive, and sweeps "
                "from there -- so the first day's periods are the ones still standing when "
                "the line has dragged away every other one",
                sched, TEST12_SPAN, kind="progressive", sweep=60.0,
                tp_start=TEST12_TP_START, tp_sweep=TEST12_TP_SWEEP, targets=targets)


def case13():
    first = tasks12(50, Fraction(5, 2))
    second = tasks12(25, Fraction(5))
    sched = Scheduler([state(DAY, first), state(2 * DAY, second)],
                      Environment(periods=nights12()), dynamics=DYNAMICS_12,
                      horizon=TEST12_SPAN, stretch_when=privileged_only)
    return Case("13", "test 12 with the PERCENTAGES sliding as well",
                "A hands half its share to the ten privileged tasks between t_p=24h and "
                "t_p=48h; outside that span the nearer state is held, and the plan at the "
                "line satisfies the percentages of exactly the position it is made from",
                sched, TEST12_SPAN, kind="progressive", sweep=60.0,
                tp_start=TEST12_TP_START, tp_sweep=TEST12_TP_SWEEP)


def tasks14():
    out = [task("A", 50, 45, s=True, color="#FF7B7B")]
    out += [task(f"T{i:02d}", Fraction(5, 2), 45, s=True,
                 color=_shade((255, 190, 90), i, 20)) for i in range(20)]
    return tuple(out)


def nights14(span=TEST14_SPAN):
    out = []
    for d in range(int(span / DAY) + 1):
        s = d * DAY + 23 * HOUR
        if s < span:
            out.append(period(s, min(s + 9 * HOUR, span), KIND_NO_TASK, "23h-8h: nothing"))
    return out


def case14():
    sched = Scheduler([state(0, tasks14())], Environment(periods=nights14()),
                      dynamics=(), horizon=TEST14_SPAN)
    targets = {"A": Fraction(1, 2), "others": Fraction(1, 2)}
    return Case("14", "eight days, 21 tasks, nothing in the way but the nights",
                "nothing slides, so what is left is the ARRANGEMENT: A every other slot "
                "with the twenty taking turns in between -- not one block of A as long as "
                "all twenty of theirs together -- each morning resuming the run the night "
                "interrupted. Eight days is four whole cycles, so the percentages are a "
                "measurement and not a cold start",
                sched, TEST14_SPAN, kind="progressive", sweep=90.0, targets=targets)


BUILDERS = [case1, case2, case3, case4, case5, case6, case7, case8, case9, case9b,
            case10, case11, case12, case13, case14]
CASES_BY_NUMBER = {b().number if False else n: b for n, b in
                   zip(["1", "2", "3", "4", "5", "6", "7", "8", "9", "9b",
                        "10", "11", "12", "13", "14"], BUILDERS)}


def build_cases():
    """Tests 1-9b: one environment, one walk, one timeline."""
    return [b() for b in BUILDERS[:10]]


def build_moving_cases():
    """Tests 10 and 11: a period of the case's own slides with the line."""
    return [case10(), case11()]


def build_progressive_cases():
    """Tests 12-14: settled link by link, with a front moving along them."""
    return [case12(), case13(), case14()]


def build_all():
    return build_cases() + build_moving_cases() + build_progressive_cases()


# --------------------------------------------------------------------------- #
#  reading a timeline
# --------------------------------------------------------------------------- #

def timeline_of(case, t_p=None, mode=1, upto=None):
    t_p = case.tp_start if t_p is None else frac(t_p)
    return case.sched.timeline(t_p, mode, upto=upto)


def covers_completely(tl, lo, hi) -> str:
    """A timeline must be a partition of its span: no gap, no overlap."""
    cur = frac(lo)
    for pl in tl:
        if pl.start < cur - scheduler.EPS:
            return f"overlap at {human(pl.start)}"
        if pl.start > cur + scheduler.EPS:
            return f"gap at {human(cur)}..{human(pl.start)}"
        cur = pl.end
    if abs(cur - frac(hi)) > scheduler.EPS:
        return f"stops at {human(cur)}, not at {human(hi)}"
    return ""


def idle_where_allowed(case, tl, t_p, mode):
    """Every idle stretch, if any, that some task was allowed in."""
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


def short_placements(case, tl, t_p):
    """Placements below their task's minimum that no edge and no horizon
    explains -- the minimum is soft, but it is only ever given up to the
    environment."""
    walk = case.sched.walk_at(t_p)
    edges = set(case.sched.environment(t_p, 1).bounds) | {case.span, case.sched.t_start}
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


def group_shares(tl, groups):
    got = resulting_shares(tl)
    out = {}
    for label, names in groups.items():
        out[label] = sum((got.get(n, Fraction(0)) for n in names), Fraction(0))
    return out


def shares_line(tl, limit=6) -> str:
    got = resulting_shares(tl)
    items = sorted(got.items(), key=lambda kv: (-kv[1], kv[0]))
    head = ", ".join(f"{n} {float(v) * 100:.1f}%" for n, v in items[:limit])
    return head + (f", +{len(items) - limit} more" if len(items) > limit else "")


# --------------------------------------------------------------------------- #
#  what a case IS, and what it answered -- in a form a person can read
# --------------------------------------------------------------------------- #

def state_text(rule_state) -> str:
    total = sum((t.priority for t in rule_state.tasks), Fraction(0)) or Fraction(1)
    rows = [f"{t.name} {float(t.priority / total) * 100:g}% min {human(t.min_time)}"
            + ("" if t.s else " (not marked s)")
            for t in rule_state.tasks]
    return f"  at t = {human(rule_state.at)}:\n" + "".join(f"    - {r}\n" for r in rows)


def configuration_text(case) -> str:
    """The test configuration: every rule state and the starting timeline.

    `tests.md` asks the copy button for exactly this -- the pre-placed tasks and
    the restrictive periods that are NOT the three dynamic ones, since those are
    the scheduler's own answer and not part of the question.
    """
    out = [f"{case.heading}", f"  ({case.note})", "",
           f"span: {human(case.sched.t_start)} .. {human(case.span)}",
           f"line starts at: {human(case.tp_start)}"
           + (f", teleports to {human(case.tp_sweep)}" if case.tp_sweep else ""),
           "", "rule states:"]
    for st in case.sched.states.states:
        out.append(state_text(st).rstrip("\n"))
    out += ["", "starting timeline:"]
    if case.sched.base.blocks:
        for b in case.sched.base.blocks:
            out.append(f"  - pre-placed {b.task}: {human(b.start)} .. {human(b.end)}")
    if case.sched.base.periods:
        for p in case.sched.base.periods:
            label = p.label or p.kind
            out.append(f"  - period {label}: {human(p.start)} .. {human(p.end)}  [{p.kind}]")
    if not case.sched.base.blocks and not case.sched.base.periods:
        out.append("  - nothing pre-placed")
    if case.sched.dynamics:
        names = ", ".join(f"{d.label} ({human(d.duration)})" for d in case.sched.dynamics)
        out.append(f"  + the three dynamic periods, placed by the scheduler: {names}")
    if case.sched.sliding is not None:
        out.append("  + one period of the case's own, sliding with t_p")
    return "\n".join(out)


def rules_text(case, t_p=None, mode=1, max_rules=60) -> str:
    """The set of rules the scheduler returns at this position of the line."""
    sched = case.sched
    t_p = sched.t_p if t_p is None else frac(t_p)
    reg = sched.rules(t_p, mode)
    lines = reg.lines()
    head, rules = lines[0], lines[1:]
    out = [f"rules at t_p = {human(t_p)} (mode {mode}):", "  " + head]
    out += rules[:max_rules]
    if len(rules) > max_rules:
        out.append(f"  ... {len(rules) - max_rules} more")
    alt = sched.alternative_at(t_p, mode)
    out.append(f"  alternative schedule at the line: {alt or '(nothing else may run)'}")
    return "\n".join(out)


def report_text(case, t_p=None, mode=1) -> str:
    """Configuration + rules + what the schedule measured: the whole of what
    the display's copy button puts on the clipboard."""
    sched = case.sched
    t_p = sched.t_p if t_p is None else frac(t_p)
    tl = sched.timeline(t_p, mode)
    parts = [configuration_text(case), "", rules_text(case, t_p, mode), "",
             f"resulting shares (excluding what no task was allowed in): {shares_line(tl, 24)}"]
    if case.kind == "progressive":
        parts.append(f"definitive up to: {human(sched.front)} of {human(case.span)}")
    return "\n".join(parts)


# --------------------------------------------------------------------------- #
#  the checks the static cases owe
# --------------------------------------------------------------------------- #

def verify_static(cases=None, verbose=True):
    cases = build_cases() if cases is None else cases
    fails = []
    if verbose:
        print("--- tests 1-9b: one environment, one walk ---")
    for case in cases:
        tl = timeline_of(case)
        bad = covers_completely(tl, case.sched.t_start, case.span)
        if bad:
            fails.append(f"{case.name}: the timeline is not a partition -- {bad}")
        for line in idle_where_allowed(case, tl, case.tp_start, 1):
            fails.append(f"{case.name}: {line}")
        for line in short_placements(case, tl, case.tp_start)[:3]:
            fails.append(f"{case.name}: {line}")
        # Every rule the SCHEDULER made names an alternative -- where there is
        # one to name. Two exemptions, and both are the absence of a choice
        # rather than a missing answer: a pre-placed block is a fact of the
        # starting timeline and not a pick, and a stretch only one task is
        # allowed in has nothing to run instead.
        env = case.sched.environment(case.tp_start, 1)
        specs, shares = case.sched.specs_at(0), case.sched.walk_at(0).p
        if len(shares) > 1:
            for pl in tl:
                if pl.task == IDLE or pl.alt:
                    continue
                mid = (pl.start + pl.end) / 2
                if case.sched.base.block_at(mid) is not None:
                    continue
                if sum(1 for v in env.weights(specs, shares, mid).values() if v > 0) < 2:
                    continue
                fails.append(f"{case.name}: the rule at {human(pl.start)} names no alternative")
                break
        # the same case built twice is the same answer: a rule list is a
        # function of the configuration and of nothing else
        if not same_timeline(tl, timeline_of(case.fresh())):
            fails.append(f"{case.name}: two builds of the same case disagree")
        if verbose:
            print(f"  {case.name:<8} {shares_line(tl)}")
    if verbose:
        _report(fails)
    return fails


# --------------------------------------------------------------------------- #
#  the checks the sliding cases owe
# --------------------------------------------------------------------------- #

def positions(case, samples):
    lo, hi = case.sched.t_start, case.span
    return [lo + (hi - lo) * Fraction(i, samples) for i in range(samples + 1)]


def verify_moving(cases=None, samples=90, verbose=True):
    """The frozen past, the sliding rule of the case, and the rule list.

    The line is walked forward across the whole span, committing as it goes --
    which is what makes the past a fact rather than an intention -- and every
    position owes a complete timeline, no idling where a task was allowed, and
    agreement with everything the earlier positions committed.
    """
    cases = build_moving_cases() if cases is None else cases
    fails = []
    if verbose:
        print("--- tests 10-11: the sliding period, and the frozen past ---")
    for case in cases:
        seen = []
        line = case.sched
        for t_p in positions(case, samples):
            line.advance_to(t_p, 1)
            tl = line.timeline()
            bad = covers_completely(tl, line.t_start, case.span)
            if bad:
                fails.append(f"{case.name} at t_p={human(t_p)}: {bad}")
            for text in idle_where_allowed(case, tl, t_p, 1)[:1]:
                fails.append(f"{case.name} at t_p={human(t_p)}: {text}")
            for pos, older in seen[-4:]:
                if not same_timeline(clip(older, line.t_start, pos), clip(tl, line.t_start, pos)):
                    fails.append(f"{case.name}: the schedule below {human(pos)} changed "
                                 f"once the line reached {human(t_p)}")
                    break
            seen.append((t_p, tl))
        fails += _check_case_specific(case)
        if verbose:
            tl = case.fresh().sched.timeline(case.span / 2, 1)
            print(f"  {case.name:<8} {samples + 1} positions swept, "
                  f"shares at mid-sweep: {shares_line(tl)}")
    if verbose:
        _report(fails)
    return fails


def _check_case_specific(case):
    """What a sliding case owes beyond the family's rules."""
    if case.number == "10":
        return _check_test10(case.fresh())
    if case.number == "11":
        return _check_test11(case.fresh())
    return []


def _check_test10(case):
    """The window accepts only A, so B is never inside it -- and A is never cut
    by it, since the window forbids A nothing."""
    fails = []
    for t_p in positions(case, 60):
        tl = case.sched.timeline(t_p, 1)
        for pl in tl:
            if pl.task != "B":
                continue
            if pl.start < t_p + WINDOW and pl.end > t_p:
                fails.append(f"Test 10: B runs at {human(pl.start)} inside the "
                             f"only-A window at {human(t_p)}")
                break
    return fails


def _check_test11(case):
    """`tests.md`'s own sentence: as soon as the moving 20-second period
    collides with the start of the five-minute stretch at t_p, the 20-second
    period disappears permanently and the stretch shifts 20 seconds left."""
    fails = []
    contact = STRETCH_HOME - WINDOW
    before = case.sched.dynamic_periods(contact - Fraction(1, 100), 1)
    after = case.sched.dynamic_periods(contact + Fraction(1, 100), 1)
    if not any(p.label.startswith("20s") for p in before):
        fails.append("Test 11: the 20s window is already gone before contact")
    if any(p.label.startswith("20s") for p in after):
        fails.append("Test 11: the 20s window survived the collision")
    heads = [p for p in after if p.label == "1min: nothing"]
    if not heads or abs(heads[0].start - (contact + Fraction(1, 100))) > scheduler.EPS:
        fails.append(f"Test 11: after contact the stretch starts at "
                     f"{human(heads[0].start) if heads else '(nowhere)'}, not at the line")
    home = [p for p in before if p.label == "1min: nothing"]
    if not home or home[0].start != STRETCH_HOME:
        fails.append("Test 11: before contact the stretch is not at home")
    # ...and the 20 seconds the stretch vacated are filled with tasks, not left
    # empty: the gap at its far end is scheduled like anywhere else
    tl = case.sched.timeline(contact + Fraction(1, 100), 1)
    tail = [p for p in clip(tl, contact + STRETCH_LEN, STRETCH_HOME + STRETCH_LEN)
            if p.task != IDLE]
    if not tail:
        fails.append("Test 11: the 20s the stretch vacated was left empty")
    return fails


def verify_rules(cases=None, verbose=True):
    """The rule list itself: at a position the line is standing at, the rules
    must hold over a RANGE of positions and reproduce the scheduler at
    positions they were never fitted on."""
    cases = build_moving_cases() if cases is None else cases
    fails = []
    if verbose:
        print("--- the rule list at the line is affine in t_p ---")
    for case in cases:
        line = case.fresh().sched
        t_p = case.span / 3
        line.advance_to(t_p, 1)
        reg = line.rules(span=frac(2))
        end = min(line.horizon, reg.lo + line.LOOKAHEAD)
        if reg.hi <= reg.lo:
            fails.append(f"{case.name}: no range of positions could be claimed at "
                         f"t_p={human(t_p)}")
        for k in (1, 3, 5, 7):
            if reg.hi <= reg.lo:
                break
            x = reg.lo + (reg.hi - reg.lo) * Fraction(k, 8)
            drawn = clip(reg.draw(x), x, end)
            actual = clip(line.timeline(x, 1, upto=end), x, end)
            if not same_timeline(drawn, actual):
                fails.append(f"{case.name}: the rules disagree with the scheduler at "
                             f"t_p={human(x)}")
                break
        if verbose:
            moving = sum(1 for s in reg.segments if s.start_slope or s.end_slope)
            print(f"  {case.name:<8} one regime over {human(reg.hi - reg.lo)} of t_p, "
                  f"{len(reg.segments)} rules, {moving} moving with the line")
    if verbose:
        _report(fails)
    return fails


# --------------------------------------------------------------------------- #
#  the checks the progressive cases owe
# --------------------------------------------------------------------------- #

PACE_SECONDS = 10.0              # "the right schedule for the next 10 minutes
PACE_MINUTES = frac(10)          #  must not take more than 10 seconds"


def verify_progressive(cases=None, verbose=True, settle_seconds=2.0):
    cases = build_progressive_cases() if cases is None else cases
    fails = []
    if verbose:
        print("--- tests 12-14: the pace, the bars, and the resume contract ---")
    for case in cases:
        sched = case.sched
        # 1. the pace
        t0 = time.perf_counter()
        start = sched.front
        sched.settle(budget_seconds=settle_seconds)
        elapsed = time.perf_counter() - t0
        gained = sched.front - start
        rate = float(gained) / max(elapsed, 1e-9)
        if rate < float(PACE_MINUTES) / PACE_SECONDS:
            fails.append(f"{case.name}: {float(gained):.0f} timeline-minutes in "
                         f"{elapsed:.1f}s -- the pace asks for one a second")
        # 2. the chain IS the plan
        chain = coalesce(clip(sched.committed + sched._chain, sched.t_start, sched.front))
        whole = clip(case.fresh().sched.timeline(case.tp_start, 1, upto=sched.front),
                     sched.t_start, sched.front)
        if not same_timeline(chain, whole):
            diff = next((human(x.start) for x, y in zip(whole, chain) if x.task != y.task),
                        "(a boundary)")
            fails.append(f"{case.name}: the settled chain is not the single plan "
                         f"-- first at {diff}")
        # 3. what it owes as a schedule
        bad = covers_completely(chain, sched.t_start, sched.front)
        if bad:
            fails.append(f"{case.name}: the settled part is not a partition -- {bad}")
        for text in idle_where_allowed(case, chain, case.tp_start, 1)[:1]:
            fails.append(f"{case.name}: {text}")
        if verbose:
            print(f"  {case.name:<8} settled {human(gained)} in {elapsed:.1f}s "
                  f"({rate:.0f} timeline-minutes per second), "
                  f"chain == plan over {human(sched.front - sched.t_start)}")
    fails += verify_break_grid(verbose=verbose)
    fails += verify_teleport(verbose=verbose)
    fails += verify_targets(cases, verbose=verbose)
    if verbose:
        _report(fails)
    return fails


def verify_break_grid(case=None, verbose=True):
    """The three recurrence rules, read off the grid test 12 actually places.

    Both directions: every period sits at least its own bar after everything
    that bars it, and a stretch long enough to arm one is followed by one
    before the next night swallows it.
    """
    case = case12() if case is None else case
    planner = case.sched.planner_at(0)
    inst = planner.instances(TEST12_TP_START, 1)
    fails = []
    for i, cur in enumerate(inst):
        for prev in inst[:i]:
            if prev.end > cur.start:
                fails.append(f"Test 12: {prev.label} at {human(prev.start)} overlaps "
                             f"{cur.label} at {human(cur.start)}")
                continue
            gap = cur.start - prev.end
            run = prev.stretch_run(planner.kind_counts)
            length = (run[1] - run[0]) if run else Fraction(0)
            if cur.label == "20s" and gap < scheduler.BAR_20S_AFTER_ANY - scheduler.EPS:
                fails.append(f"Test 12: a 20s only {human(gap)} after the "
                             f"{prev.label} at {human(prev.start)}")
            if length >= scheduler.STRETCH_SHORT and cur.label == "5min" \
                    and gap < scheduler.BAR_5MIN_AFTER_STRETCH - scheduler.EPS:
                fails.append(f"Test 12: a 5min only {human(gap)} after a "
                             f"{human(length)} stretch")
            if length >= scheduler.STRETCH_LONG and cur.label == "15min" \
                    and gap < scheduler.BAR_15MIN_AFTER_LONG - scheduler.EPS:
                fails.append(f"Test 12: a 15min only {human(gap)} after a "
                             f"{human(length)} stretch")
    # ...and the night arms them: the first period of the first full day sits
    # its own bar after the night that precedes it
    night_end = 8 * HOUR
    first = min((i for i in inst if i.start >= night_end), key=lambda i: i.start, default=None)
    if first is None:
        fails.append("Test 12: no dynamic period at all after the first night")
    elif first.start < night_end + scheduler.BAR_20S_AFTER_ANY - scheduler.EPS:
        fails.append(f"Test 12: the first period of the day is at {human(first.start)}, "
                     f"less than its bar after the night")
    if verbose:
        counts = {}
        for i in inst:
            counts[i.label] = counts.get(i.label, 0) + 1
        print("  Test 12  the grid over three days: "
              + ", ".join(f"{n} x {k}" for k, n in sorted(counts.items())))
    return fails


def verify_teleport(case=None, verbose=True):
    """The line waits at the origin and JUMPS to 24h. A jump sweeps nothing:
    every period below it is where the bars put it, none dragged."""
    case = case12() if case is None else case
    sched = case.sched
    fails = []
    before = sched.planner_at(0).instances(TEST12_TP_SWEEP, 1, sweep_from=TEST12_TP_SWEEP)
    sched.settle(budget_seconds=1.0)
    sched.teleport_to(TEST12_TP_SWEEP, 1)
    after = sched.planner_at(TEST12_TP_SWEEP).instances(
        TEST12_TP_SWEEP, 1, sweep_from=sched.sweep_from)
    below = [i for i in after if i.end <= TEST12_TP_SWEEP]
    if not below:
        fails.append("Test 12: the swept-over day holds no dynamic period at all")
    if any(i.open_start for i in below):
        fails.append("Test 12: the jump dragged a period")
    if len(before) != len(after):
        fails.append("Test 12: the jump changed where the periods are")
    # ...and the sweep from there DOES drag: one step past the landing point,
    # a period is being carried by the line
    # ...and the sweep from there DOES drag -- once it is past the night the
    # line lands in front of, and has reached the first period beyond it
    sched.advance_to(TEST12_TP_SWEEP + 8 * HOUR + scheduler.BAR_20S_AFTER_ANY + 1, 1)
    carried = [i for i in sched.planner_at(sched.t_p).instances(
        sched.t_p, 1, sweep_from=sched.sweep_from) if i.open_start]
    if not carried:
        fails.append("Test 12: the line swept past a period without dragging it")
    if verbose:
        print(f"  Test 12  {len(below)} periods still standing in the swept-over day, "
              f"{len(carried)} dragged once the sweep begins")
    return fails


def verify_targets(cases=None, verbose=True, span=None):
    """The first optimisation criterion, measured: what each group of tasks
    actually took, against what it was aiming at."""
    cases = build_progressive_cases() if cases is None else cases
    fails = []
    for case in cases:
        if not case.targets:
            continue
        sched = case.fresh().sched
        upto = frac(span) if span else min(case.span, 4 * DAY)
        tl = sched.timeline(case.tp_start, 1, upto=upto)
        if case.number == "12":
            groups = {"A": ["A"], "privileged": PRIVILEGED, "ordinary": ORDINARY}
        else:
            groups = {"A": ["A"], "others": [f"T{i:02d}" for i in range(20)]}
        got = group_shares(tl, groups)
        for label, want in case.targets.items():
            if abs(got[label] - want) > Fraction(12, 100):
                fails.append(f"{case.name}: {label} took {float(got[label]) * 100:.1f}%, "
                             f"aiming at {float(want) * 100:.0f}%")
        if verbose:
            print(f"  {case.name:<8} over {human(upto)}: "
                  + ", ".join(f"{k} {float(v) * 100:.1f}% "
                              f"(target {float(case.targets[k]) * 100:.0f}%)"
                              for k, v in got.items()))
    return fails


# --------------------------------------------------------------------------- #
#  running them
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


def verify_readme(verbose=True):
    """The engine's own clause-by-clause checks against README.md, run here so
    one command covers both halves."""
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


def verify_reports(cases=None, verbose=True):
    """What the display's copy button puts on the clipboard.

    `tests.md` asks for the configuration AND the resulting rules, in a form a
    person can read, so the text is checked for both halves -- a copy button
    that quietly copied an empty rule list would look exactly like one that
    worked.
    """
    cases = build_all() if cases is None else cases
    fails = []
    if verbose:
        print("--- the copy button's text: configuration + rules ---")
    for case in cases:
        text = report_text(case, case.tp_start, 1)
        for wanted in ("rule states:", "starting timeline:", "rules at t_p",
                       "alternative schedule", "resulting shares"):
            if wanted not in text:
                fails.append(f"{case.name}: the report has no \"{wanted}\" section")
        rules = [ln for ln in text.splitlines() if ln.startswith("  ") and "->" in ln]
        if not rules:
            fails.append(f"{case.name}: the report names no rule at all")
        for st in case.sched.states.states:
            if human(st.at) not in text:
                fails.append(f"{case.name}: the rule state at {human(st.at)} is not in the report")
    if verbose:
        widest = max(len(report_text(c, c.tp_start, 1).splitlines()) for c in cases)
        print(f"  {len(cases)} cases, every report complete, longest {widest} lines")
        _report(fails)
    return fails


def verify_all(verbose=True):
    t0 = time.perf_counter()
    fails = verify_readme(verbose)
    fails += verify_static(verbose=verbose)
    fails += verify_moving(verbose=verbose)
    fails += verify_rules(verbose=verbose)
    fails += verify_progressive(verbose=verbose)
    fails += verify_reports(verbose=verbose)
    if verbose:
        print(f"{'FAILED: ' + str(len(fails)) if fails else 'all checks pass'} "
              f"({time.perf_counter() - t0:.1f}s)")
    return fails


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[1])
    ap.add_argument("--verify", action="store_true", help="run every check")
    ap.add_argument("--list", action="store_true", help="list the cases")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    if args.list:
        for case in build_all():
            print(f"{case.name:<9} [{case.kind:<11}] {case.title}")
        return 0
    fails = verify_all(verbose=not args.quiet)
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
