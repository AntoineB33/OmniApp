#!/usr/bin/env python3
"""
test_configs.py
THE test -- its shape, the tests already configured, and the file the custom
one is remembered in.

There is exactly ONE test and it is CUSTOM: the test window is where it is
written, and `test_config.json` is where it is remembered between runs. What
lives here is the SHAPE a configuration has, so that a person can edit it with
a mouse and the scheduler can be asked about it:

    span            how far the question reaches
    rule states     the tasks, their percentages, their minimums and their
                    resilience to "no on-screen task", pinned at an instant --
                    one state stands forever, several are keyframes the
                    scheduler blends linearly between
    blocks          stretches already on the timeline before the scheduler is
                    asked anything
    periods         what the timeline REFUSES, and to whom

Everything the README asks of the scheduler is deliberately absent from a
configuration:

* the three dynamic restrictive periods (20s / 5min / 15min) -- `scheduler.py`
  places them, under the README's recurrence bars, its chain rule and its
  mode-1 drag;
* t_p, its two modes, the frozen past, no idling, the alternative schedule and
  the progressive pace -- `scheduler.py` answers for them and
  `tests_displayer.py` holds the test to them.

A knob here that switched one of those off, or re-stated it, would make the
test a test of something the README does not describe. That is why a
configuration says what the timeline IS, and never how the scheduler must
behave on it.

The other half of this file is `PRESETS`: the tests that are ALREADY
configured, which the window offers in a drop-down and applies to the custom
test the moment one is chosen. A preset is an ordinary `Config` -- so choosing
one leaves every field editable, and editing any of them makes the test custom
again.

    uv run tests_displayer.py              the window: the test, and its editor
    uv run test_configs.py --list          the current configuration, as text
    uv run test_configs.py --presets       the tests already configured
    uv run test_configs.py --use NAME      make one of them the test
    uv run test_configs.py --reset         forget the customisation
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path

from scheduler import (
    DAY,
    HOUR,
    KIND_NO_SCREEN,
    KIND_NO_TASK,
    MINUTE,
    SECOND,
    Environment,
    Scheduler,
    block,
    frac,
    human,
    period,
    state,
    task,
)

#: Where the one test is remembered between runs. It is the test, so it is a
#: fact of the repository and not a scratch file: the window writes it on every
#: Apply, and `--verify` reads the same thing the window is showing.
CONFIG_PATH = Path(__file__).with_name("test_config.json")


# --------------------------------------------------------------------------- #
#  times, as a person types them
# --------------------------------------------------------------------------- #
#
#  The base unit is ONE MINUTE, held as a `Fraction` so nothing is ever
#  rounded. A bare number is minutes; `s`, `min`, `h` and `d` are the named
#  multiples and may be strung together ("1h 30min", "2d 6h", "20s").
#  `human()` writes exactly what `parse_time()` reads back.

_UNITS = (("min", MINUTE), ("m", MINUTE), ("s", SECOND), ("h", HOUR), ("d", DAY))


def parse_time(text) -> Fraction:
    """A duration or an instant, from the text a person typed."""
    if isinstance(text, (int, Fraction)):
        return frac(text)
    out, number, unit, seen = Fraction(0), "", "", False
    for ch in str(text).strip().lower() + " ":
        if ch.isdigit() or ch in ".,":
            if unit:
                out += _apply(number, unit)
                number, unit, seen = "", "", True
            number += "." if ch == "," else ch
        elif ch.isalpha():
            unit += ch
        elif ch.isspace():
            if number or unit:
                out += _apply(number, unit)
                number, unit, seen = "", "", True
        else:
            raise ValueError(f"{text!r} is not a time")
    if not seen:
        raise ValueError(f"{text!r} is not a time")
    return out


def _apply(number, unit) -> Fraction:
    if not number:
        raise ValueError(f"{unit!r} has no number in front of it")
    value = Fraction(number)
    if not unit:
        return value * MINUTE
    for name, mult in _UNITS:
        if unit == name:
            return value * mult
    raise ValueError(f"{unit!r} is not one of s, min, h, d")


def parse_percent(text) -> Fraction:
    """A percentage, with or without its sign."""
    t = str(text).strip().rstrip("%").strip().replace(",", ".")
    if not t:
        raise ValueError("a percentage is missing")
    return Fraction(t)


def show_percent(value) -> str:
    return f"{float(Fraction(value)):g}"


def parse_resilience(text) -> Fraction:
    """A resilience, typed as a percentage: 0 forbids the task in a period of
    that kind, 100 leaves it untouched, and anything between scales its share
    for as long as the period lasts."""
    value = parse_percent(text) / 100
    if not 0 <= value <= 1:
        raise ValueError("a resilience must be between 0% and 100%")
    return value


def show_resilience(value) -> str:
    return show_percent(Fraction(value) * 100)


# --------------------------------------------------------------------------- #
#  what a period refuses
# --------------------------------------------------------------------------- #
#
#  The README puts resilience on the TASK: a task's resilience to a kind is
#  the multiplier on its percentage inside a period of that kind, 0 forbidding
#  it outright. That is the right place for it and the wrong place to EDIT it,
#  because a person laying a period down is thinking about the period. So a
#  period here says who it turns away, and `Config.build()` translates that
#  into the kind + the resiliences the scheduler reads -- two periods that
#  refuse the same set share one kind, which is what makes ten consecutive
#  windows one long ban rather than ten small ones.

EVERYBODY = ("", "everybody", "everyone", "all", "nobody", "nothing")
ON_SCREEN_TASKS = ("screen", "on screen", "on-screen", "on screen tasks",
                   "on-screen tasks", "no on-screen task", "the on-screen tasks")


def refusal(refuses: str):
    """`(kind, {task: resilience})` for what a period's "refuses" column says.

    "everybody" ("no task allowed") and "on-screen" ("no on-screen task") are
    the README's own two kinds -- and neither carries per-task resiliences,
    because a task's resilience to "no on-screen task" is a fact of the TASK
    and is typed in its own row. Anything else is a list of task names, each
    optionally with the resilience it keeps ("B:0.5", "B:50%") rather than the
    0 that forbids it outright.
    """
    text = str(refuses).strip()
    low = text.lower()
    if low in EVERYBODY:
        return KIND_NO_TASK, {}
    if low in ON_SCREEN_TASKS:
        return KIND_NO_SCREEN, {}
    out = {}
    for item in text.split(","):
        item = item.strip()
        if not item:
            continue
        name, _, value = item.partition(":")
        name = name.strip()
        if not name:
            raise ValueError(f"{item!r} names no task")
        out[name] = parse_percent(value) / 100 if value.strip() else Fraction(0)
        if not 0 <= out[name] <= 1:
            raise ValueError(f"{name}'s resilience must be between 0 and 1")
    if not out:
        raise ValueError(f"{refuses!r} refuses nobody")
    kind = "no " + ", ".join(n if v == 0 else f"{n}@{float(v) * 100:g}%"
                             for n, v in sorted(out.items()))
    return kind, out


def refusal_label(refuses: str) -> str:
    low = str(refuses).strip().lower()
    if low in EVERYBODY:
        return "nothing may run"
    if low in ON_SCREEN_TASKS:
        return "no on-screen task"
    return "no " + ", ".join(part.strip() for part in str(refuses).split(",")
                             if part.strip())


# --------------------------------------------------------------------------- #
#  the shape of the configuration
# --------------------------------------------------------------------------- #

#: What a task added in the window is coloured. The colour changes no answer;
#: it is how a person tells one stretch from another on the bar.
PALETTE = ("#FF9999", "#99CCFF", "#99FF99", "#FFCC66", "#CC99FF", "#FF99CC",
           "#99FFEE", "#DDBB88", "#AACCAA", "#FFAA77")


@dataclass(frozen=True)
class TaskLine:
    """One task of one rule state.

    `percent` is the target PERCENTAGE (the README's first optimisation
    criterion), read against the sum of them all -- so "50, 50" and
    "40, 40, 20" mean what they say. `min_time` is the smallest stretch the
    task may be given in one go.

    `screen` is the task's RESILIENCE to the kind "no on-screen task", the one
    kind the README names in its own rules: 0 is an on-screen task -- turned
    away by such a period, and by the three dynamic ones -- and 1 is a task
    such a period leaves alone. It is typed here rather than on the period
    because the README puts every resilience on the task; a period of that
    kind says nothing about who it turns away, it IS the kind.
    """
    name: str
    percent: Fraction
    min_time: Fraction
    screen: Fraction = Fraction(0)
    color: str = PALETTE[0]

    def as_json(self):
        return {"name": self.name, "percent": show_percent(self.percent),
                "min": human(self.min_time), "screen": show_resilience(self.screen),
                "color": self.color}

    @staticmethod
    def from_json(d) -> TaskLine:
        # "s" is what this column was called while the README had a mark on the
        # task: the marked ones are exactly the on-screen ones, resilience 0.
        screen = (d["screen"] if "screen" in d
                  else ("0" if bool(d.get("s", True)) else "100"))
        return TaskLine(str(d["name"]), parse_percent(d["percent"]),
                        parse_time(d["min"]), parse_resilience(screen),
                        str(d.get("color", PALETTE[0])))


@dataclass(frozen=True)
class StateLine:
    """One rule state -- percentages and minimums -- pinned at `at`.

    A single state stands over the whole timeline (the README's "when there is
    one defined rule state, it stays the same forever"); several are keyframes
    the scheduler blends linearly between, holding the nearer one outside the
    outermost pair.
    """
    at: Fraction
    tasks: tuple

    def as_json(self):
        return {"at": human(self.at), "tasks": [t.as_json() for t in self.tasks]}

    @staticmethod
    def from_json(d) -> StateLine:
        return StateLine(parse_time(d.get("at", 0)),
                         tuple(TaskLine.from_json(t) for t in d["tasks"]))


@dataclass(frozen=True)
class BlockLine:
    """A pre-placed stretch of that task, covering [start, start + duration).

    A name no task carries ("MAINTENANCE") is a stretch owned by nobody: it
    displaces everybody equally, and so deprives nobody relative to anybody.
    """
    task: str
    start: Fraction
    duration: Fraction

    def as_json(self):
        return {"task": self.task, "start": human(self.start),
                "duration": human(self.duration)}

    @staticmethod
    def from_json(d) -> BlockLine:
        return BlockLine(str(d["task"]), parse_time(d["start"]),
                         parse_time(d["duration"]))


@dataclass(frozen=True)
class PeriodLine:
    """A restrictive period over [start, end), and who it turns away.

    Periods may overlap, and overlapping periods MULTIPLY -- what an instant
    refuses is the sum of the periods over it.
    """
    start: Fraction
    end: Fraction
    refuses: str = "everybody"

    def as_json(self):
        return {"start": human(self.start), "end": human(self.end),
                "refuses": self.refuses}

    @staticmethod
    def from_json(d) -> PeriodLine:
        return PeriodLine(parse_time(d["start"]), parse_time(d["end"]),
                          str(d.get("refuses", "everybody")))


@dataclass(frozen=True)
class Config:
    """The one test: what the timeline IS, and how far the question reaches."""
    title: str
    span: Fraction
    states: tuple
    blocks: tuple = ()
    periods: tuple = ()

    # -- reading it ----------------------------------------------------------

    @property
    def tasks(self) -> tuple:
        """The tasks of the first rule state -- the identities the rest of the
        configuration is written in terms of."""
        return self.states[0].tasks if self.states else ()

    @property
    def summary(self) -> str:
        n = len(self.tasks)
        parts = [f"{n} task{'' if n == 1 else 's'}"]
        if len(self.states) > 1:
            parts.append(f"{len(self.states)} rule states")
        parts.append(f"{len(self.blocks)} pre-placed block(s)")
        parts.append(f"{len(self.periods)} period(s)")
        parts.append(f"span {human(self.span)}")
        return ", ".join(parts)

    def problems(self) -> list:
        """Everything that would make this configuration unanswerable.

        The window refuses to apply a configuration with any of these, so the
        scheduler is never handed one -- a test that cannot be built is a
        broken window, not a failing test.
        """
        out = []
        if self.span <= 0:
            out.append("the span must be more than nothing")
        if not self.states:
            out.append("there must be at least one rule state")
        seen_at = set()
        for st in self.states:
            if st.at in seen_at:
                out.append(f"two rule states are pinned at {human(st.at)}")
            seen_at.add(st.at)
            if not st.tasks:
                out.append(f"the rule state at {human(st.at)} has no task")
            names = set()
            for t in st.tasks:
                if not t.name.strip():
                    out.append("a task has no name")
                elif t.name in names:
                    out.append(f"two tasks are called {t.name}")
                names.add(t.name)
                if t.percent < 0:
                    out.append(f"{t.name}'s percentage is negative")
                if t.min_time <= 0:
                    out.append(f"{t.name}'s minimum time must be more than nothing")
                if not 0 <= t.screen <= 1:
                    out.append(f"{t.name}'s resilience to \"no on-screen task\" "
                               f"must be between 0% and 100%")
            if sum((t.percent for t in st.tasks), Fraction(0)) <= 0:
                out.append(f"the rule state at {human(st.at)} gives every task 0%")
        known = {t.name for t in self.tasks}
        for b in self.blocks:
            if not b.task.strip():
                out.append("a pre-placed block names nobody")
            if b.duration <= 0:
                out.append(f"the block of {b.task} lasts no time at all")
            if b.start < 0:
                out.append(f"the block of {b.task} starts before the timeline does")
        for p in self.periods:
            if p.end <= p.start:
                out.append(f"the period at {human(p.start)} ends before it starts")
            try:
                _, refused = refusal(p.refuses)
            except ValueError as exc:
                out.append(f"the period at {human(p.start)}: {exc}")
                continue
            for name in refused:
                if name not in known:
                    out.append(f"the period at {human(p.start)} refuses {name}, "
                               f"which is not a task")
        return out

    # -- building it ---------------------------------------------------------

    def build(self) -> Case:
        """The configuration, as the four things the scheduler is asked about.

        The refusals are collected FIRST, because a resilience belongs to the
        TASK: every rule state's copy of a task must carry the same one, or a
        blend between two keyframes would quietly lift a ban half-way. The
        one resilience a task carries in its own right -- the one to "no
        on-screen task" -- is added to that same map, so the scheduler is
        handed exactly one kind of thing.
        """
        bans, periods = {}, []
        for p in self.periods:
            kind, refused = refusal(p.refuses)
            for name, value in refused.items():
                bans.setdefault(name, {})[kind] = value
            periods.append(period(p.start, p.end, kind, refusal_label(p.refuses)))
        states = tuple(
            state(st.at, tuple(task(t.name, t.percent, t.min_time,
                                    resilience={**bans.get(t.name, {}),
                                                KIND_NO_SCREEN: t.screen},
                                    color=t.color)
                               for t in st.tasks))
            for st in self.states)
        blocks = tuple(block(b.task, b.start, b.duration) for b in self.blocks)
        return Case(self, states, frac(self.span), blocks, tuple(periods))

    # -- remembering it ------------------------------------------------------

    def as_json(self):
        return {"title": self.title, "span": human(self.span),
                "states": [s.as_json() for s in self.states],
                "blocks": [b.as_json() for b in self.blocks],
                "periods": [p.as_json() for p in self.periods]}

    @staticmethod
    def from_json(d) -> Config:
        return Config(str(d.get("title", "the test")), parse_time(d["span"]),
                      tuple(StateLine.from_json(s) for s in d["states"]),
                      tuple(BlockLine.from_json(b) for b in d.get("blocks", ())),
                      tuple(PeriodLine.from_json(p) for p in d.get("periods", ())))

    def save(self, path=CONFIG_PATH):
        path.write_text(json.dumps(self.as_json(), indent=2) + "\n", encoding="utf-8")
        return path


# --------------------------------------------------------------------------- #
#  the test, once it is built
# --------------------------------------------------------------------------- #

@dataclass
class Case:
    """The one test: its rule states, its starting timeline, and its span.

    `sched` is the ONE instance of the scheduler this test is answered by, and
    `fresh()` builds the case again -- from the same configuration -- for a
    check that needs a clean line.
    """
    config: Config
    states: tuple
    span: Fraction
    blocks: tuple = ()
    periods: tuple = ()
    _sched: Scheduler | None = field(default=None, repr=False, compare=False)

    @property
    def name(self):
        return "the test"

    @property
    def title(self):
        return self.config.title

    @property
    def note(self):
        return self.config.summary

    @property
    def heading(self):
        return self.config.title

    @property
    def environment(self) -> Environment:
        return Environment(periods=self.periods, blocks=self.blocks)

    @property
    def sched(self) -> Scheduler:
        if self._sched is None:
            self._sched = Scheduler(list(self.states), self.environment,
                                    horizon=self.span)
        return self._sched

    def fresh(self) -> Case:
        return self.config.build()


# --------------------------------------------------------------------------- #
#  the already-configured tests
# --------------------------------------------------------------------------- #
#
#  The test is CUSTOM -- the window's editor is where it is written, and
#  `test_config.json` is where it is remembered. What is written down HERE is
#  the other half: the tests that are already configured, offered in the
#  window's drop-down and applied to the custom test the moment one is chosen.
#
#  A preset is an ordinary `Config` and nothing else. It says what the timeline
#  IS, exactly as an edited one does, so choosing one leaves every field
#  editable and no configuration here can ask for anything a hand could not
#  have typed into the editor. In particular none of them says a word about the
#  three dynamic periods, t_p, its modes or the pace: those are the README's,
#  `scheduler.py` answers for them, and a knob switching one of them off would
#  make the test a test of something the README does not describe.
#
#  The numbers in the names are the ones these tests have always been called by.

CUSTOM = "custom"


def _rows(rows, screen=()) -> tuple:
    """`(name, percentage, minimum)` triples as task lines, coloured in turn.

    `screen` names the tasks a period of the kind "no on-screen task" leaves
    alone (resilience 100%); everybody else is an on-screen task, which is what
    every task of the numbered tests below has always been.
    """
    return tuple(TaskLine(name, parse_percent(percent), parse_time(minimum),
                          Fraction(1) if name in screen else Fraction(0),
                          PALETTE[i % len(PALETTE)])
                 for i, (name, percent, minimum) in enumerate(rows))


def _shade(base, i, n) -> str:
    """One family of colours, so a glance tells one group of tasks from another."""
    k = 0.72 + 0.28 * (i / max(n - 1, 1))
    return "#%02X%02X%02X" % tuple(min(255, int(c * k)) for c in base)


def _family(names, percent, minimum, base, screen=False) -> tuple:
    """One group of tasks sharing a percentage, a minimum and a family of colour."""
    return tuple(TaskLine(name, parse_percent(percent), parse_time(minimum),
                          Fraction(1) if screen else Fraction(0),
                          _shade(base, i, len(names)))
                 for i, name in enumerate(names))


def _nights(span) -> tuple:
    """A night every 24 hours: nobody at all from midnight to 8h, and from 23h
    on only the tasks "no on-screen task" leaves alone.

    The two overlap on purpose -- overlapping periods add up, so what the night
    really says is "the off-screen tasks only from 23h, nobody from midnight".
    """
    out, day = [], 0
    while day * DAY - HOUR < span:
        start = day * DAY
        if 0 < start - HOUR < span:
            out.append(PeriodLine(start - HOUR, min(start, span), "on-screen"))
        if start < span:
            out.append(PeriodLine(start, min(start + 8 * HOUR, span), "everybody"))
        day += 1
    return tuple(out)


#: The two tasks half the numbered tests are written in terms of.
AB = (("A", 50, "10min"), ("B", 50, "10min"))

#: Test 12's twenty-one tasks: A at 50%, and twenty sharing the other 50% --
#: half of them off-screen, so a period of the kind "no on-screen task" (the
#: night's first hour, and the two longer dynamic ones) leaves them alone.
PRIVILEGED = tuple(f"P{i}" for i in range(1, 11))
ORDINARY = tuple(f"N{i}" for i in range(1, 11))


def _twenty_one(share_a, share_p, share_n) -> tuple:
    return ((TaskLine("A", parse_percent(share_a), parse_time("45min"),
                      Fraction(0), "#FF7B7B"),)
            + _family(PRIVILEGED, share_p, "45min", (110, 170, 255), screen=True)
            + _family(ORDINARY, share_n, "45min", (255, 190, 90)))


PRESETS = {

    "the default": Config(
        title="three tasks and an empty timeline -- nothing is pre-placed and "
              "nothing is refused but the three dynamic periods",
        span=6 * HOUR,
        states=(StateLine(Fraction(0), _rows(
            (("A", 50, "10min"), ("B", 30, "10min"), ("C", 20, "15min")),
            screen=("C",))),),
    ),

    "1: 50/50, ten minutes each": Config(
        title="50/50, ten minutes each -- a pure cycle, and nothing in its way",
        span=3 * HOUR,
        states=(StateLine(Fraction(0), _rows(AB)),),
    ),

    "2: a block owned by nobody": Config(
        title="an hour owned by nobody -- it displaces everybody equally, so it "
              "deprives nobody: they simply resume alternating",
        span=4 * HOUR,
        states=(StateLine(Fraction(0), _rows(AB)),),
        blocks=(BlockLine("MAINTENANCE", Fraction(40), HOUR),),
    ),

    "3: C refused for good": Config(
        title="C is refused from t = 1h45 on, for the rest of the timeline -- so "
              "it is abundantly present just before the door closes",
        span=5 * HOUR,
        states=(StateLine(Fraction(0), _rows(
            (("A", 40, "10min"), ("B", 40, "10min"), ("C", 20, "10min")))),),
        periods=(PeriodLine(Fraction(105), 5 * HOUR, "C"),),
    ),

    "4: three tasks, three minimums": Config(
        title="A 50% at 20min, B 30% at 10min, C 20% at 15min -- the minimums "
              "force the window, and the shares are exact inside it",
        span=Fraction(400),
        states=(StateLine(Fraction(0), _rows(
            (("A", 50, "20min"), ("B", 30, "10min"), ("C", 20, "15min")))),),
    ),

    "5: 90/10, B pre-placed": Config(
        title="90/10, with forty minutes of B at the start -- A gets a denser, "
              "bounded catch-up around it, never everything it is owed",
        span=10 * HOUR,
        states=(StateLine(Fraction(0), _rows(
            (("A", 90, "10min"), ("B", 10, "10min")))),),
        blocks=(BlockLine("B", Fraction(0), Fraction(40)),),
    ),

    "6: an hour of A": Config(
        title="an hour of A pre-placed at t = 1h40 -- B's slots swell as it "
              "approaches and shrink back after it: the decay, on both sides",
        span=Fraction(400),
        states=(StateLine(Fraction(0), _rows(AB)),),
        blocks=(BlockLine("A", Fraction(100), HOUR),),
    ),

    "7: ten hours of A": Config(
        title="ten hours of A, ten times longer than the hour -- B's presence "
              "around it is wider and denser, but only a few times bigger",
        span=Fraction(1000),
        states=(StateLine(Fraction(0), _rows(AB)),),
        blocks=(BlockLine("A", Fraction(100), 10 * HOUR),),
    ),

    "8: B banned for five hours": Config(
        title="B is banned from t = 1h40 to t = 6h40 -- a window, not a block: B "
              "swells before the ban and right after it re-opens",
        span=Fraction(700),
        states=(StateLine(Fraction(0), _rows(AB)),),
        periods=(PeriodLine(Fraction(100), Fraction(400), "B"),),
    ),

    "9: the same ban, in ten windows": Config(
        title="the same five-hour ban on B, cut into ten consecutive windows -- "
              "ten short bans in a row are ONE long ban, not ten small ones",
        span=Fraction(700),
        states=(StateLine(Fraction(0), _rows(AB)),),
        periods=tuple(PeriodLine(Fraction(100 + 30 * i), Fraction(130 + 30 * i), "B")
                      for i in range(10)),
    ),

    "9b: two overlapping bans": Config(
        title="two overlapping bans, on a timeline they do not cover -- what an "
              "instant refuses is the SUM of the periods over it, so where the "
              "two overlap A holds the timeline alone",
        span=Fraction(700),
        states=(StateLine(Fraction(0), _rows(
            (("A", 40, "10min"), ("B", 30, "10min"), ("C", 30, "10min")))),),
        periods=(PeriodLine(Fraction(100), Fraction(300), "C"),
                 PeriodLine(Fraction(200), Fraction(400), "B")),
    ),

    "12: three days, 21 tasks, nights": Config(
        title="three days, twenty-one tasks of 45 minutes and a night every 24h -- "
              "A at 50%, twenty sharing the rest, ten of them off-screen",
        span=3 * DAY,
        states=(StateLine(Fraction(0), _twenty_one(50, "2.5", "2.5")),),
        periods=_nights(3 * DAY),
    ),

    "13: 12, with the percentages sliding": Config(
        title="the three days again, with the PERCENTAGES sliding -- one "
              "arrangement pinned at 24h, another at 48h, and the nearer one "
              "held outside the pair",
        span=3 * DAY,
        states=(StateLine(DAY, _twenty_one(50, "2.5", "2.5")),
                StateLine(2 * DAY, _twenty_one(10, 6, 3))),
        periods=_nights(3 * DAY),
    ),

    "14: two tasks, three days, nights": Config(
        title="the three days stripped to the bone: two tasks of 45 minutes at "
              "50% each, and nothing in the way but the nights",
        span=3 * DAY,
        states=(StateLine(Fraction(0), _rows(
            (("A", 50, "45min"), ("B", 50, "45min")))),),
        periods=_nights(3 * DAY),
    ),

}


def preset_names() -> tuple:
    """The already-configured tests, in the order the drop-down offers them."""
    return tuple(PRESETS)


def preset(name):
    """One already-configured test, by name or by a unique prefix of it -- so
    "14" and "9b" are enough to ask for one at a prompt, and "1" (which four of
    them start with) is not enough and asks for nothing.

    The matching lives here, once, because the names do: a second copy of it in
    the window or in the terminal is how two ways of asking for the same test
    start disagreeing about which one that is.
    """
    text = str(name).strip()
    if text in PRESETS:
        return PRESETS[text]
    hits = [c for n, c in PRESETS.items() if n.lower().startswith(text.lower())]
    return hits[0] if len(hits) == 1 else None


def preset_name_of(config) -> str:
    """The name of the already-configured test this configuration IS, or
    `CUSTOM`.

    A configuration is the test it is EQUAL to and nothing else, so a preset
    edited in any field is custom again and one edited back is that test
    again -- which is the whole of what the drop-down has to remember.
    """
    for name, other in PRESETS.items():
        if other == config:
            return name
    return CUSTOM


# --------------------------------------------------------------------------- #
#  the default: what the window opens on before anybody has edited anything
# --------------------------------------------------------------------------- #

DEFAULT_CONFIG = PRESETS["the default"]


def load_config(path=CONFIG_PATH, default=None) -> Config:
    """The configuration the window was last left on, or the default.

    A file that cannot be read is not worth stopping for -- the test falls
    back to the default, which the window says in its own status line, where a
    person is already looking.
    """
    default = DEFAULT_CONFIG if default is None else default
    try:
        return Config.from_json(json.loads(path.read_text(encoding="utf-8")))
    except (OSError, ValueError, KeyError, TypeError):
        return default


def build_case(config=None) -> Case:
    return (config or load_config()).build()


def configuration_lines(config: Config) -> list:
    """The configuration in the form the report and `--list` print it."""
    out = [config.title, f"  {config.summary}", "", "rule states:"]
    for st in config.states:
        total = sum((t.percent for t in st.tasks), Fraction(0)) or Fraction(1)
        out.append(f"  at t = {human(st.at)}:")
        for t in st.tasks:
            out.append(f"    - {t.name} {float(t.percent / total) * 100:g}% "
                       f"min {human(t.min_time)}, "
                       f"{show_resilience(t.screen)}% resilient to "
                       f"\"no on-screen task\""
                       + (" (an on-screen task)" if t.screen == 0 else ""))
    out += ["", f"starting timeline (span {human(config.span)}):"]
    if not config.blocks and not config.periods:
        out.append("  (empty: no pre-placed task, no pre-placed period)")
    for b in config.blocks:
        out.append(f"  - pre-placed {b.task}: {human(b.start)} .. "
                   f"{human(b.start + b.duration)}")
    for p in config.periods:
        out.append(f"  - period [{refusal(p.refuses)[0]}] {refusal_label(p.refuses)}: "
                   f"{human(p.start)} .. {human(p.end)}")
    return out


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description="the one test's configuration")
    ap.add_argument("--list", action="store_true", help="print the configuration")
    ap.add_argument("--presets", action="store_true",
                    help="name the tests that are already configured")
    ap.add_argument("--use", metavar="NAME",
                    help="make one of those tests the test (a unique prefix of "
                         "its name will do)")
    ap.add_argument("--reset", action="store_true",
                    help="forget the customisation and go back to the default")
    args = ap.parse_args(argv)
    if args.presets:
        print("the tests already configured:")
        for name in preset_names():
            print(f"  {name:42s} {PRESETS[name].summary}")
        print()
    if args.use:
        chosen = preset(args.use)
        if chosen is None:
            print(f"no test is called {args.use!r}; --presets names them all")
            return 1
        chosen.save()
        print(f"{preset_name_of(chosen)} is now the test, "
              f"in {CONFIG_PATH.name}\n")
    elif args.reset:
        DEFAULT_CONFIG.save()
        print(f"the default is back in {CONFIG_PATH.name}\n")
    config = load_config()
    print(f"[{preset_name_of(config)}]")
    print("\n".join(configuration_lines(config)))
    for bad in config.problems():
        print(f"  PROBLEM: {bad}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
