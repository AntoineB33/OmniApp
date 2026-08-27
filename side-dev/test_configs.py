#!/usr/bin/env python3
"""
test_configs.py
THE test -- its shape, its default, and the file it is remembered in.

There is exactly ONE test, and this file does not decide what is in it: the
test window does. What lives here is the SHAPE a configuration has, so that a
person can edit it with a mouse and the scheduler can be asked about it:

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

    uv run tests_displayer.py              the window: the test, and its editor
    uv run test_configs.py --list          the current configuration, as text
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
#  the default: what the window opens on before anybody has edited anything
# --------------------------------------------------------------------------- #

DEFAULT_CONFIG = Config(
    title="three tasks, half an hour owned by nobody, and an hour C is refused",
    span=6 * HOUR,
    states=(StateLine(Fraction(0), (
        TaskLine("A", Fraction(50), Fraction(10), Fraction(0), PALETTE[0]),
        TaskLine("B", Fraction(30), Fraction(10), Fraction(0), PALETTE[1]),
        TaskLine("C", Fraction(20), Fraction(15), Fraction(1), PALETTE[2]),
    )),),
    blocks=(BlockLine("MAINTENANCE", HOUR, Fraction(30)),),
    periods=(PeriodLine(3 * HOUR, 4 * HOUR, "C"),),
)


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
    ap.add_argument("--reset", action="store_true",
                    help="forget the customisation and go back to the default")
    args = ap.parse_args(argv)
    if args.reset:
        DEFAULT_CONFIG.save()
        print(f"the default is back in {CONFIG_PATH.name}\n")
    config = load_config()
    print("\n".join(configuration_lines(config)))
    for bad in config.problems():
        print(f"  PROBLEM: {bad}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
