#!/usr/bin/env python3
"""
rules_snapshot.py
The rule set every test resolves to, frozen into `rules_snapshot.txt`.

The scheduler's output IS a finite rule list -- a prefix and a repeating cycle
for a static case, and for a moving one a list of regimes whose durations are
affine in the sliding period's position t_p. `test_configs.verify_moving` checks
that those rules are *self-consistent* (they reproduce the scheduler, the past
stays frozen, minimums hold, the cycle hits the target percentages). It cannot
notice that the rules changed: a different but equally consistent answer passes
every one of those checks.

This file is the missing half. It records the rules themselves, so a change to
the scheduler that changes any test's answer fails `--verify` with a diff of
what moved. Nothing here is authoritative about what is *correct*: when a diff
is intended, read it and bless it with

    uv run rules_snapshot.py --update       (or: tests_displayer.py --update-rules)

The document is a pure function of the configs plus the scheduler -- deterministic
across runs and across PYTHONHASHSEED -- so any diff is a real change in
behaviour, never noise.
"""

import difflib
import sys
from pathlib import Path

from scheduler_logic import MAX_RULES, human, rule_lines, stamp
from test_configs import (
    build_cases,
    build_moving_cases,
    build_progressive_cases,
    case_parts,
    get_schedule_rules,
)

SNAPSHOT = Path(__file__).with_name("rules_snapshot.txt")

HEADER = [
    "# The set of rules every test resolves to -- the scheduler's actual output.",
    "#",
    "# Checked by:    uv run tests_displayer.py --verify",
    "# Regenerate by: uv run rules_snapshot.py --update",
    "#",
    "# A diff here means the scheduler now answers a test differently. That is not",
    "# automatically a bug -- but it is never nothing, so read the diff before",
    "# blessing it. The checks in test_configs.py say the rules are consistent;",
    "# this file says they are still the same rules.",
    "",
]


# --------------------------------------------------------------------------- #
#  the document
# --------------------------------------------------------------------------- #

def _heading(title):
    return f"=== {title.splitlines()[0]} ==="

def static_rules_lines(case):
    """The rule list of one static case, exactly as the Copy Rules button gives it."""
    title, tasks, _span, pre_placed, periods, options = case_parts(case)
    prefix, cycle, plan = get_schedule_rules(tasks, pre_placed, periods, **options)

    out = [_heading(title), f"Start: {stamp(plan.start)}"]
    if prefix:
        out.append("Prefix:")
        out.extend(rule_lines(prefix))
    if cycle:
        out.append("Cycle:")
        out.extend(rule_lines(cycle))
        out.append("- repeat")
        out.append(f"Period: {human(plan.period)}")
        out.append("Shares: " + ", ".join(f"{n} {float(s) * 100:.4g}%"
                                          for n, s in sorted(plan.shares.items())))
    else:
        out.append(f"(No cycle found - capped at {MAX_RULES} rules or bounded timeline)")
    out.append("")
    return out

def moving_rules_lines(title, mw):
    """The dynamic rule list of one moving case: every regime, prefix and cycle."""
    return [_heading(title)] + mw.lines() + [""]

PROGRESSIVE_LINKS = 6

def progressive_regime_lines(pw, at=()):
    """The affine rules at a few positions of the line -- the dynamic half of
    the answer, in the same form tests 10-11 record theirs."""
    out = [""]
    for tp in (at or (pw.tp_start, pw.tp_start + (pw.span - pw.tp_start) / 3)):
        r = pw.regime_at(tp)
        out.append(f"Rules at the line while t_p in {r.label}:")
        out.append(f"  Prefix: {r.prefix_text}")
        out.append(f"  Cycle:  {r.cycle_text}")
    return out

def progressive_rules_lines(title, pw):
    """The progressive rule list of one chained case.

    Its chain runs to hundreds of links, and printing all of them would bury
    every other test in the file, so what is recorded is the shape of the whole
    -- how many links, how many rules each, where they were cut -- the first few
    in full, and what the three days actually gave each task. Any change to the
    scheduler moves the digest.

    The rules AT THE LINE are recorded too, at the position the sweep starts
    from and at the first one where a break is being dragged: they are what the
    display substitutes t_p into, and a slope changing there is exactly the kind
    of change this file exists to catch.

    `settle` is deterministic (the front is advanced link by link until it is
    done), so nothing about the machine's speed leaks in here."""
    pw.settle()
    out = [_heading(title)] + pw.lines(max_segments=PROGRESSIVE_LINKS)
    if pw.blend is not None:
        # the two states the percentages slide between: they are an INPUT to
        # every link, so a change to them belongs in the diff beside the rules
        for at, what in ((0, "from"), (pw.span, "to")):
            out.append(f"Percentages {what} t_p={stamp(at)}: " + ", ".join(
                f"{t.name} {float(t.priority):g}%" for t in pw.tasks_at(at)))
    out += progressive_regime_lines(pw)
    served, busy = {}, 0
    for s, e, n in pw.timeline(pw.span):
        if n not in pw.minimum: continue
        served[n] = served.get(n, 0) + (e - s)
        busy += e - s
    out.append(f"Cuts: " + ", ".join(stamp(s.start) for s in pw.segments[:8]) + ", ...")
    out.append(f"Scheduled: {human(busy)} of {stamp(pw.span)}")
    out.append("Served: " + ", ".join(f"{n} {human(d)}" for n, d in sorted(served.items())))
    out.append("")
    return out

def rules_document(cases=None, moving_cases=None, progressive_cases=None):
    """The whole snapshot as text. Deterministic: same configs -> same bytes."""
    cases = build_cases() if cases is None else cases
    moving_cases = build_moving_cases() if moving_cases is None else moving_cases
    progressive_cases = (build_progressive_cases() if progressive_cases is None
                         else progressive_cases)

    lines = list(HEADER)
    for case in cases:
        lines += static_rules_lines(case)
    for title, mw, _sweep in moving_cases:
        lines += moving_rules_lines(title, mw)
    for title, pw, _sweep in progressive_cases:
        lines += progressive_rules_lines(title, pw)
    return "\n".join(lines).rstrip("\n") + "\n"


# --------------------------------------------------------------------------- #
#  the check
# --------------------------------------------------------------------------- #

def write_rules_snapshot(cases=None, moving_cases=None, progressive_cases=None,
                         path=SNAPSHOT):
    text = rules_document(cases, moving_cases, progressive_cases)
    path.write_text(text, encoding="utf-8", newline="\n")
    return text

def check_rules_snapshot(cases=None, moving_cases=None, progressive_cases=None,
                         path=SNAPSHOT, verbose=True, max_report=40):
    """Do the tests still give the rules recorded in `path`? -> list of failures."""
    got = rules_document(cases, moving_cases, progressive_cases)

    if not path.exists():
        if verbose:
            print(f"--- the recorded rule sets ({path.name}) ---")
            print(f"  FAIL: no snapshot on record. Write it with "
                  f"`uv run {Path(__file__).name} --update`.\n")
        return [f"{path.name}: no snapshot on record"]

    want = path.read_text(encoding="utf-8")
    if got == want:
        if verbose:
            print(f"--- the recorded rule sets ({path.name}) ---")
            print(f"  PASS: every test still gives the rules on record "
                  f"({len(want.splitlines())} lines).\n")
        return []

    diff = list(difflib.unified_diff(want.splitlines(), got.splitlines(),
                                     fromfile=f"{path.name} (on record)",
                                     tofile="scheduler now", lineterm=""))
    changed = [d for d in diff if d[:1] in "+-" and d[:3] not in ("---", "+++")]
    if verbose:
        print(f"--- the recorded rule sets ({path.name}) ---")
        print(f"  FAIL: the rules changed ({len(changed)} lines differ). "
              f"Read the diff; bless it with `uv run {Path(__file__).name} --update`.")
        for line in diff[:max_report]:
            print("    " + line)
        if len(diff) > max_report:
            print(f"    ... {len(diff) - max_report} more diff lines")
        print()
    return [f"{path.name}: the rules changed ({len(changed)} lines differ)"]


def main():
    if "--update" in sys.argv:
        text = write_rules_snapshot()
        print(f"wrote {SNAPSHOT.name} ({len(text.splitlines())} lines)")
        return
    if "--print" in sys.argv:
        print(rules_document(), end="")
        return
    raise SystemExit(1 if check_rules_snapshot() else 0)


if __name__ == "__main__":
    main()
