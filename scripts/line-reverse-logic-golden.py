"""Writes the golden file the Kotlin port of reverse logic is checked against (`LineGoldenTest`).

    python scripts/line-reverse-logic-golden.py [reference dir] [histories] [timeout s] [unbounded cases]

The random histories are quick and run here. Each unbounded case (rays and the whole line under ordered
disjunctions) runs alone in a process of its own, killed at the timeout: the reference's infinite search can take
minutes and gigabytes on some of them, and such a case is left out (listed in the file's `left_out`).

Each case is a rule set and a sequence of steps (apply, reverse, reverse_all, drag) run through the Python
reference, with the outcome of every step (accepted, or the error it raised) and the line after it. The Kotlin
test replays the same steps and must reach the same outcomes and the same lines, region for region: the canonical
form makes equal sets equal regions, so any divergence in a tie-break or a periodic tail shows.
"""
from __future__ import annotations

import json
import math
import multiprocessing as mp
import random
import sys
import time
from fractions import Fraction as F
from pathlib import Path

REF = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r"C:\code\line_manager\reverse_logic")
CASES = int(sys.argv[2]) if len(sys.argv) > 2 else 160
TIMEOUT = float(sys.argv[3]) if len(sys.argv) > 3 else 60.0
UNBOUNDED = int(sys.argv[4]) if len(sys.argv) > 4 else 10
OUT = Path(__file__).resolve().parent.parent / "shared/src/jvmTest/resources/line/reverse-logic-golden.json"
sys.path.insert(0, str(REF))

from engine import (  # noqa: E402
    Add, ContradictionError, DependencyError, Extend, LineSystem, MissingElementError, Move, Remove, Retract, inverse,
)
from region import FULL, Region  # noqa: E402
from rules import Forbids, Implies, ImpliesAny, forbids, implies, implies_any  # noqa: E402
from test_reverse_logic import KINDS, rand_edit, rand_region, rand_rules  # noqa: E402


def num(x) -> str:
    if isinstance(x, float) and math.isinf(x):
        return "inf" if x > 0 else "-inf"
    return str(F(x))


def parts(ps):
    return [[num(lo), lc, num(hi), hc] for lo, lc, hi, hc in ps]


def region(r: Region):
    out = {"parts": parts(r.parts)}
    for name, t in (("left", r.left), ("right", r.right)):
        if t is not None:
            out[name] = {"at": num(t.at), "period": num(t.period), "pattern": parts(t.pattern)}
    return out


def state(st):
    return {k: region(v) for k, v in sorted(st.items())}


def atom(a):
    return [a[0], num(a[1])]


def rule(r):
    if isinstance(r, Implies):
        return {"t": "implies", "premises": [atom(a) for a in r.premises], "head": atom(r.head)}
    if isinstance(r, Forbids):
        return {"t": "forbids", "premises": [atom(a) for a in r.premises], "head": atom(r.head)}
    assert isinstance(r, ImpliesAny)
    return {"t": "any", "premises": [atom(a) for a in r.premises], "options": [atom(a) for a in r.options]}


def op(o):
    t = {Add: "add", Remove: "remove", Move: "move", Extend: "extend", Retract: "retract"}[type(o)]
    out = {"t": t, "kind": o.kind, "region": region(o.region)}
    if isinstance(o, Move):
        out["delta"] = num(o.delta)
    return out


def outcome(fn):
    try:
        return "ok", fn()
    except ContradictionError:
        return "contradiction", None
    except MissingElementError:
        return "missing", None
    except DependencyError:
        return "dependency", None


class Recorder:
    def __init__(self, rules):
        self.line = LineSystem(rules)
        self.case = {"rules": [rule(r) for r in self.line.ruleset.rules], "steps": []}

    def apply(self, ops):
        res, eid = outcome(lambda: self.line.apply(ops))
        self.case["steps"].append({"do": "apply", "ops": [op(o) for o in ops], "result": res, "id": eid,
                                   "state": state(self.line.state())})
        return eid

    def reverse(self, eid):
        res, _ = outcome(lambda: self.line.reverse(eid))
        self.case["steps"].append({"do": "reverse", "id": eid, "result": res, "state": state(self.line.state())})
        return res

    def reverse_all(self, ids):
        res, gone = outcome(lambda: self.line.reverse_all(ids))
        self.case["steps"].append({"do": "reverse_all", "ids": ids, "result": res, "reversed": gone,
                                   "state": state(self.line.state())})

    def drag(self, block, deltas, drop):
        drag = self.line.drag(block)
        steps = []
        for d in deltas:
            res, _ = outcome(lambda: drag.step(d))
            steps.append({"delta": num(d), "result": res, "state": state(self.line.state())})
        eid = drag.drop() if drop else drag.cancel()
        self.case["steps"].append({"do": "drag", "block": [[k, region(r)] for k, r in block], "steps": steps,
                                   "drop": drop, "id": eid, "state": state(self.line.state())})


def random_history(seed):
    rng = random.Random(seed)
    rules = rand_rules(rng)
    if rng.random() < 0.4:
        rules.append(implies_any(KINDS[0], [KINDS[1], (KINDS[2], rng.choice([-1, 1]))]))
        rules.append(forbids(KINDS[rng.randrange(1, 5)], (KINDS[rng.randrange(1, 5)], rng.choice([-1, 0, 1]))))
    rec = Recorder(rules)
    ids = []
    for _ in range(rng.randint(3, 6)):
        ops = rand_edit(rng, rec.line.state())
        if not ops:
            continue
        eid = rec.apply(ops)
        if eid is None:
            continue
        ids.append(eid)
        st = rec.line.state()
        if st and rng.random() < 0.3:
            k = rng.choice(sorted(st))
            rec.drag([(k, st[k])], [F(rng.randint(-6, 6), 2) for _ in range(3)], rng.random() < 0.5)
        if rng.random() < 0.4:
            inv = rec.apply(inverse(ops))
            if inv is not None:
                ids.append(inv)
    active = list(ids)
    rng.shuffle(active)
    for eid in active[: rng.randint(0, len(active))]:
        rec.reverse(eid)
    if rng.random() < 0.5:
        live = [e.id for e in rec.line._edits if e.active and not e.internal]
        if live:
            rec.reverse_all(rng.sample(live, rng.randint(1, len(live))))
    return rec.case


def unbounded(seed):
    """Rays and the whole line under ordered disjunctions: the periodic tails of SEMANTICS.md, Theorems 3.4/3.5."""
    rng = random.Random(seed)
    o = rng.choice([F(-1), F(1)])
    off = rng.choice([F(1), F(2), F(1, 2), F(3, 2)])
    rules = [implies_any("A", ["B", ("C", o), "D"]), forbids("B", ("B", off)),
             forbids("C", ("C", rng.choice([F(1), F(-2)]))), forbids("X", "B"),
             forbids("X", ("C", rng.choice([o, o, F(0)]))), forbids("X", ("D", rng.choice([F(0), F(1)])))]
    if rng.random() < 0.5:
        rules.append(implies("D", ("E", rng.choice([F(1), F(-1)]))))
        rules.append(forbids("E", "B"))
    ray = Region(((F(0), True, math.inf, False),))
    a = rng.choice([ray, FULL, Region(((-math.inf, False, F(rng.randint(3, 8)), True),)), Region.closed(-3, 9)])
    rec = Recorder(rules)
    first = rec.apply([Add("A", a)])
    x = rec.apply([Add("X", Region.points(*rng.sample(range(-5, 6), rng.randint(1, 4))))])
    if x is not None and rng.random() < 0.5:
        rec.reverse(x)
    if first is not None and rng.random() < 0.3:
        rec.reverse_all([first])
    return rec.case


def examples():
    """The three examples of requirements.md, and the conjunction examples."""
    cases = []
    rec = Recorder([forbids("D", "C")])
    rec.apply([Add("A", Region.closed(1, 2)), Add("B", Region.closed(1, 4)), Add("D", Region.closed(6, 7))])
    a = rec.apply([Move.to("A", Region.interval(1, 2, True, False), 3), Move.to("B", Region.point(4), 5),
                   Add("C", Region.closed(6, 7))])
    b = rec.apply([Add("D", Region.closed(6, 7))])
    rec.reverse(b)
    rec.reverse(a)
    rec.apply([Add("C", Region.point(8)), Add("D", Region.point(8))])
    cases.append(rec.case)
    for rules, start, block in (([forbids("A", "B")], ("A", Region.closed(0, 2)), ("B", Region.closed(0, 1))),
                                ([forbids("D", "C")], ("D", Region.point(2)), ("C", Region.closed(0, 1)))):
        rec = Recorder(rules)
        rec.apply([Add(*start)])
        rec.apply([Add(*block)])
        rec.drag([block], [F(i, 8) for i in range(25)], True)
        cases.append(rec.case)
    x = Region.point(0)
    for rules, steps in (
            ([implies(["A", "B", "C"], "D"), implies(["A", "B"], "C")],
             [[Add("A", x)], [Add("B", x)], [Remove("A", x)], [Remove("B", x)]]),
            ([implies(["A", "B", "C"], "D"), forbids("X", "D"), implies(["A", "B"], "C")],
             [[Add("A", x), Add("B", x)], [Add("X", x)], [Remove("A", x)], [Remove("X", x)], [Add("A", x)]])):
        rec = Recorder(rules)
        for ops in steps:
            rec.apply(ops)
        cases.append(rec.case)
    return cases


def _unbounded_worker(seed, conn):
    conn.send(json.dumps(unbounded(seed)))
    conn.close()


def unbounded_within(seed):
    """The unbounded case, or None if the reference does not finish it within TIMEOUT (its process is killed)."""
    recv, send = mp.Pipe(duplex=False)
    p = mp.get_context("spawn").Process(target=_unbounded_worker, args=(seed, send))
    p.start()
    send.close()
    try:
        if recv.poll(TIMEOUT):
            return json.loads(recv.recv())
        return None
    finally:
        if p.is_alive():
            p.kill()
        p.join()


def main():
    cases = examples()
    for s in range(CASES):
        cases.append(random_history(20000 + s))
    print(f"{CASES} histories", flush=True)
    skipped = []
    for s in range(UNBOUNDED):
        t = time.monotonic()
        case = unbounded_within(30000 + s)
        if case is None:
            skipped.append(30000 + s)
            print(f"unbounded {30000 + s}: over {TIMEOUT}s, left out", flush=True)
        else:
            cases.append(case)
            print(f"unbounded {30000 + s}: {time.monotonic() - t:.1f}s", flush=True)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"reference": "line_manager/reverse_logic", "cases": cases,
                               "left_out": [f"unbounded {s}" for s in skipped]}, separators=(",", ":")),
                   encoding="utf-8")
    print(f"{len(cases)} cases -> {OUT} ({len(skipped)} left out: {skipped})")


if __name__ == "__main__":
    main()
