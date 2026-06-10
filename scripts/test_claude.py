#!/usr/bin/env python3
"""
Priority scheduler with past-schedule compensation.

Each task has:
  - a priority percentage (its target share of total time)
  - a minimum block duration

Algorithm (deficit / weighted-fair-queueing style):
  * The slot length (quantum) defaults to the largest minimum time among the
    tasks, so every task's minimum is always respected and slots are uniform.
  * Each task keeps a "deficit" = (time it deserves) - (time it received).
  * The past schedule seeds the deficits, but scaled down by `past_weight`
    (default 0.1). This means past imbalance influences the schedule
    (under-served tasks come first, over-served ones are pushed back) without
    forcing a long monotone catch-up block: the future stays interleaved and
    converges smoothly toward the target percentages.
  * For every future slot, the task with the largest deficit runs
    (ties broken by input order), then deficits are updated.

Usage:
  python3 scheduler.py --slots 8 --past "x x x x x x x x" "x 50% 45min" "y 50% 45min"

Options:
  --slots N         number of future slots to generate (default 8)
  --past "a b c"    space-separated names of already-scheduled past slots
  --past-weight W   how strongly past imbalance is compensated (0..1, default 0.1)
  --quantum M       force a slot length in minutes (default: max of min times)
"""

import argparse
import re
import sys


def parse_task(spec: str):
    m = re.match(r"^\s*(\S+)\s+([\d.]+)\s*%\s+([\d.]+)\s*min", spec)
    if not m:
        sys.exit(f"Cannot parse task spec: {spec!r} (expected: name 50% 45min)")
    return m.group(1), float(m.group(2)), float(m.group(3))


def schedule(tasks, past, slots, past_weight=0.1, quantum=None):
    """
    tasks: list of (name, priority_percent, min_minutes)
    past:  list of task names already scheduled (oldest first)
    slots: number of future slots to produce
    Returns list of (name, duration_minutes).
    """
    names = [t[0] for t in tasks]
    total_pct = sum(t[1] for t in tasks)
    share = {t[0]: t[1] / total_pct for t in tasks}          # normalized 0..1
    min_time = {t[0]: t[2] for t in tasks}

    if quantum is None:
        quantum = max(min_time.values())                      # uniform slots

    # Seed deficits from the past schedule (scaled by past_weight).
    past_alloc = {n: 0.0 for n in names}
    for p in past:
        if p not in past_alloc:
            sys.exit(f"Past entry {p!r} is not a known task")
        past_alloc[p] += quantum
    past_total = sum(past_alloc.values())
    deficit = {n: past_weight * (share[n] * past_total - past_alloc[n])
               for n in names}

    out = []
    for _ in range(slots):
        # Most deficient task wins; ties go to earlier input order.
        chosen = max(names, key=lambda n: (deficit[n], -names.index(n)))
        d = max(quantum, min_time[chosen])
        out.append((chosen, d))
        for n in names:
            deficit[n] += share[n] * d
        deficit[chosen] -= d
    return out


def main():
    ap = argparse.ArgumentParser(description="Deficit scheduler with past compensation")
    ap.add_argument("tasks", nargs="+", help='task specs like "x 50%% 45min"')
    ap.add_argument("--slots", type=int, default=8)
    ap.add_argument("--past", default="", help='space-separated past slots, e.g. "x x y"')
    ap.add_argument("--past-weight", type=float, default=0.1)
    ap.add_argument("--quantum", type=float, default=None)
    args = ap.parse_args()

    tasks = [parse_task(s) for s in args.tasks]
    past = args.past.split()
    result = schedule(tasks, past, args.slots, args.past_weight, args.quantum)

    print(" ".join(name for name, _ in result))
    print(" | ".join(f"{name} {dur:g}min" for name, dur in result))


if __name__ == "__main__":
    main()