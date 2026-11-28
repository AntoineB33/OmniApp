"""Writes the golden file the Kotlin port of line_manager's positions and regions is checked against
(`RuleLineGoldenTest`).

    python scripts/ruleline-golden.py [reference scripts dir] [cases]

Random positions x + kε, interval sets and regions (periodic tails, ε periods, and pairs whose tails only repeat
together after a long period, which the reference keeps lazy) are combined by the reference's own operations; each
case holds the inputs and the text of every result. The Kotlin test rebuilds the inputs, runs the same operations and
must print the same text: the canonical forms make equal sets print equal.
"""
from __future__ import annotations

import json
import math
import random
import sys
from fractions import Fraction as F
from pathlib import Path

REF = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r"C:\code\line_manager\scripts")
CASES = int(sys.argv[2]) if len(sys.argv) > 2 else 300
OUT = Path(__file__).resolve().parent.parent / "shared/src/jvmTest/resources/ruleline/positions-regions-golden.json"
sys.path.insert(0, str(REF))

from intervals import EPS, INF, Interval, IntervalSet, Pos  # noqa: E402
from region import IncompatiblePeriods, Region  # noqa: E402

R = random.Random(20261128)


def pos_json(p):
    if isinstance(p, float):
        return ["inf" if p > 0 else "-inf", 0]
    return [str(p.x), p.k]


def iv_json(iv: Interval):
    return [pos_json(iv.lo), pos_json(iv.hi)]


def set_json(s: IntervalSet):
    return [iv_json(iv) for iv in s.intervals]


def rpos(lo=-20, hi=20, den=(1, 1, 1, 2, 3)):
    x = F(R.randint(lo * 6, hi * 6), 6) if R.random() < 0.3 else F(R.randint(lo, hi), R.choice(den))
    k = R.choice([0, 0, 0, 0, 1, -1, 2])
    return Pos(x, k)


def rinterval(lo=-20, hi=20):
    r = R.random()
    if r < 0.15:
        return Interval.point(rpos(lo, hi))
    a, b = sorted([rpos(lo, hi), rpos(lo, hi)])
    if r < 0.22:
        return Interval(-INF, b, False, R.random() < 0.5)
    if r < 0.29:
        return Interval(a, INF, R.random() < 0.5, False)
    iv = Interval.make(a, b, R.random() < 0.6, R.random() < 0.6)
    return iv if iv is not None else Interval.point(a)


def rset(n=None, lo=-20, hi=20):
    n = R.randint(0, 5) if n is None else n
    return IntervalSet(rinterval(lo, hi) for _ in range(n))


def rpattern(period: Pos):
    out = []
    for _ in range(R.randint(1, 3)):
        a = Pos(F(R.randint(0, int(period.x * 4)), 4))
        b = Pos(F(R.randint(0, int(period.x * 4)), 4))
        a, b = min(a, b), max(a, b)
        iv = Interval.make(a, b, R.random() < 0.7, R.random() < 0.5)
        if iv is not None:
            out.append(iv)
    return out


def rregion():
    r = R.random()
    if r < 0.25:
        return Region.of(rset())
    lo = Pos(R.randint(-10, 5))
    hi = lo + R.randint(0, 12)
    big = R.random() < 0.12
    def per():
        if big:
            return Pos(R.choice([1220, 86400, 3600]))
        return Pos(F(R.randint(1, 8), R.choice([1, 1, 2])), R.choice([0, 0, 0, 1]))
    pl, pr = per(), per()
    core = rset(R.randint(0, 4), int(lo.x), int(hi.x) + 1)
    left = IntervalSet(rpattern(pl)) if R.random() < 0.6 else IntervalSet()
    right = IntervalSet(rpattern(pr)) if R.random() < 0.7 else IntervalSet()
    return Region(lo, hi, core, pl, left, pr, right)


def region_json(g: Region):
    return {"lo": pos_json(g.lo), "hi": pos_json(g.hi), "core": set_json(g.core), "pl": pos_json(g.pl),
            "left": set_json(g.left), "pr": pos_json(g.pr), "right": set_json(g.right)}


def safe(f):
    try:
        v = f()
    except IncompatiblePeriods:
        return "IncompatiblePeriods"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return "+inf" if v > 0 else "-inf"
    return str(v)


def interval_cases():
    out = []
    for _ in range(CASES):
        a, b = rset(), rset()
        many = rset(40)
        p = rpos()
        d = rpos()
        amount = F(R.randint(0, 20), R.choice([1, 2]))
        out.append({
            "a": set_json(a), "b": set_json(b), "many": set_json(many), "p": pos_json(p), "d": pos_json(d),
            "amount": str(amount),
            "results": {
                "a": str(a), "b": str(b),
                "or": str(a | b), "and": str(a & b), "sub": str(a - b), "comp": str(a.complement()),
                "many_or": str(many | a), "many_and": str(many & a), "many_sub": str(many - a), "sub_many": str(a - many),
                "sum": str(a + b), "neg": str(-a), "shift": str(a.shift(d)),
                "contains": safe(lambda: a.contains(p)), "subset": safe(lambda: a.issubset(b)),
                "measure": safe(lambda: a.measure()), "points": str(a.points()),
                "inf": safe(a.inf), "sup": safe(a.sup), "bounded": safe(a.is_bounded),
                "take": str(a.take(amount)) if a.is_bounded() else "",
                "take_right": str(a.take(amount, True)) if a.is_bounded() else "",
                "p_plus_d": str(p + d), "p_minus_d": str(p - d),
            },
        })
    return out


def region_cases():
    out = []
    for _ in range(CASES):
        a, b = rregion(), rregion()
        p, d = rpos(), rpos()
        w = (rpos(-30, 0), rpos(1, 30))
        iv = rinterval(-3, 3)
        if iv.lo == -INF and iv.hi == INF:
            iv = Interval.point(Pos(0))
        res = {
            "a": safe(lambda: str(a)), "b": safe(lambda: str(b)),
            "or": safe(lambda: a | b), "and": safe(lambda: a & b), "sub": safe(lambda: a - b),
            "comp": safe(a.complement), "shift": safe(lambda: a.shift(d)),
            "edges": safe(a.left_edges), "dilate": safe(lambda: a.dilate(iv)),
            "contains": safe(lambda: a.contains(p)), "eq": safe(lambda: a == b), "subset": safe(lambda: a.issubset(b)),
            "on": safe(lambda: a.on(*w)), "inf": safe(a.inf), "sup": safe(a.sup), "everything": safe(a.is_everything),
            "components": safe(lambda: " ; ".join(str(c) for c in a.components())),
            "meeting": safe(lambda: a.components_meeting(b)),
            "lazy_chain": safe(lambda: ((a | b) - a.shift(d)) & b.complement()),
            "lazy_on": safe(lambda: ((a | b) - a.shift(d)).on(*w)),
        }
        if max(len(v) for v in res.values()) > 20000:  # (a 61-day tail written out: kept out of the file)
            continue
        out.append({"a": region_json(a), "b": region_json(b), "p": pos_json(p), "d": pos_json(d),
                    "w": [pos_json(w[0]), pos_json(w[1])], "iv": iv_json(iv), "results": res})
    return out


def main():
    data = {"intervals": interval_cases(), "regions": region_cases()}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=0), encoding="utf-8")
    print(f"wrote {OUT} ({len(data['intervals'])} interval cases, {len(data['regions'])} region cases)")


if __name__ == "__main__":
    main()


# ---------------------------------------------------------------- rules.py

RULES_OUT = OUT.parent / "rules-golden.json"


def reference_rule_texts():
    """Every rule written as a plain string in the reference's tests and examples."""
    import re as _re
    out = []
    for d in ("tests", "UI/examples", "generated_scripts", "UI"):
        for f in sorted((REF.parent / d).glob("*.py")):
            src = f.read_text(encoding="utf-8", errors="replace")
            for m in _re.finditer(r'(?<![fFrRbB])"([^"\{}\n]*(?:→|->|is always|can be)[^"\{}\n]*)"', src):
                out.append(m.group(1))
    return list(dict.fromkeys(out))


KINDS = ["A", "B", "C", "D", "E"]


def rand_lit_text():
    k = R.choice(KINDS)
    s = ("no " if R.random() < 0.15 else "") + k
    if not s.startswith("no ") and R.random() < 0.3:
        s += f" {R.choice([0, 20, 45, 50, 80, 100])}%"
    r = R.random()
    if r < 0.2:
        s += f" {R.choice([1, 2, 3, '1/2'])} unit{'s' if R.random() < 0.5 else ''} on the {R.choice(['left', 'right'])}"
    elif r < 0.3:
        s += f" at {R.choice([-2, -1, 1, 2, '3/2'])}"
    elif r < 0.45:
        s += " on " + R.choice(["[0, 2)", "(0, 1]", "[-1, 1]", "(0, inf)", "[1, 3)"])
    if R.random() < 0.3:
        s += " of " + R.choice(["p", "l"])
    return s


def rand_lhs_text():
    atoms = []
    for _ in range(R.randint(1, 3)):
        k = R.choice(KINDS)
        r = R.random()
        a = k if r < 0.6 else f"{k} {R.choice(['>=', '<', '>', '<=', ''])} {R.choice([0, 20, 50, 100])}%".replace("  ", " ")
        if R.random() < 0.15:
            a = "not " + a
        atoms.append(a)
    t = (" " + R.choice(["&", "and", "|", "or"]) + " ").join(atoms)
    if R.random() < 0.1:
        t = f"mode {R.choice(['1', '2', 'away'])} & " + t
    return t


def rand_rhs_text():
    items = []
    for _ in range(R.randint(1, 2)):
        if R.random() < 0.35:
            ops = [rand_lit_text() for _ in range(R.randint(2, 3))]
            if R.random() < 0.2:
                ops[0] = f"({ops[0]} & {rand_lit_text()})"
            items.append(" or ".join(ops))
        else:
            items.append(rand_lit_text())
    return " & ".join(items)


def rand_percent_text():
    k = R.choice(KINDS)
    if R.random() < 0.3:
        return f"{k} is always {R.choice([100, 50, 20])}%"
    opts = []
    for _ in range(R.randint(1, 3)):
        v = R.choice([100, 20, 80, 50])
        c = R.random()
        if c < 0.3:
            opts.append(f"{v}% with others {100 - v}%")
        elif c < 0.5:
            others = [o for o in KINDS if o != k]
            opts.append(f"{v}% with only {R.choice(others)}" + (f" {100 - v}%" if R.random() < 0.5 else ""))
        else:
            opts.append(f"{v}%")
    return f"{k} can be " + " | ".join(opts)


def rule_result(text):
    from rules import RuleError, parse_percent_rule, parse_rule
    try:
        pr = parse_percent_rule(text)
        if pr is not None:
            return {"percent": str(pr), "options": [str(o) for o in pr.options]}
        r = parse_rule(text)
        return {"rule": str(r), "mode": r.mode, "lhs": str(r.lhs), "items": [str(i) for i in r.rhs],
                "lits": [str(o) for o in r.lits()], "written": [str(o) for o in r.written_lits()],
                "internal": [str(i) for i in r.internal], "kinds": r.lhs.kinds()}
    except (RuleError, ValueError) as e:
        return {"error": type(e).__name__ if not isinstance(e, RuleError) else "RuleError: " + str(e)}


def set_result(texts):
    from rules import RuleError, RuleSet, parse_percent_rule, parse_rule
    try:
        pct = [parse_percent_rule(t) for t in texts]
        rs = RuleSet([parse_rule(t) for t, p in zip(texts, pct) if p is None], tuple(p for p in pct if p is not None))
    except (RuleError, ValueError) as e:
        return {"error": type(e).__name__ if not isinstance(e, RuleError) else "RuleError: " + str(e)}
    b = lambda v: bool(v)
    return {
        "rank": list(rs.rank), "order": rs.order, "blocks": [list(x) for x in rs.blocks],
        "linked": {k: v for k, v in sorted(rs.linked.items())}, "dep": sorted(rs.choice_dependent),
        "mixed": b(rs.mixed), "lattice": b(rs.lattice), "searched": b(rs.searched), "settled": b(rs.settled),
        "causal": b(rs.causal), "or_items": [list(x) for x in rs.or_items], "reach": str(rs.reach),
        "finite": b(rs.finite), "ruled": sorted(rs.ruled), "monotone": b(rs.monotone), "pointwise": b(rs.pointwise),
        "local": b(rs.local), "one_way": b(rs.one_way), "free": sorted(rs.free), "blocked": sorted(rs.blocked),
        "eps_reach": rs.eps_reach, "smallest": None if rs.smallest_offset is None else str(rs.smallest_offset),
        "split": [[list(a), list(n), str(f)] for a, n, f in rs.split],
        "edges": {k: sorted(v) for k, v in sorted(rs.edges.items())},
    }


def lhs_regions(texts):
    """Each element rule's left side read on random values: region and region2."""
    from rules import RuleError, parse_rule
    out = []
    for t in texts:
        try:
            r = parse_rule(t)
        except (RuleError, ValueError):
            continue
        if r.is_mode_rule or not r.lhs.kinds():
            continue
        vals, pres = {}, {}
        for k in r.lhs.kinds():
            used = Region.empty()
            by = {}
            for v in R.sample([F(20), F(50), F(100), F(80)], R.randint(0, 2)):
                g = Region.of(rset(2, -8, 8)) - used
                if g:
                    by[v] = g
                    used = used | g
            if by:
                vals[k] = by
                pres[k] = used
        u, e = Region.universe(), Region.empty()
        enc = {k: {str(v): region_json(g) for v, g in by.items()} for k, by in vals.items()}
        out.append({"text": t, "vals": enc, "region": str(r.lhs.region(vals, u, e)),
                    "region2": str(r.lhs.region2((vals, pres), ({}, {}), u, e)),
                    "holds": [r.lhs.holds(lambda k, v=v: v) for v in (F(0), F(20), F(100))]})
    return out


def main_rules():
    texts = reference_rule_texts()
    rand = [f"{rand_lhs_text()} → {rand_rhs_text()}" for _ in range(CASES)] + [rand_percent_text() for _ in range(60)]
    singles = [{"text": t, "result": rule_result(t)} for t in texts + rand]
    sets = []
    pool = [t for t in texts + rand if "error" not in rule_result(t)]
    for _ in range(CASES):
        ts = R.sample(pool, R.randint(1, 4))
        sets.append({"texts": ts, "result": set_result(ts)})
    data = {"singles": singles, "sets": sets, "lhs": lhs_regions(texts + rand)}
    RULES_OUT.write_text(json.dumps(data, ensure_ascii=False, indent=0), encoding="utf-8")
    print(f"wrote {RULES_OUT} ({len(singles)} texts, {len(sets)} sets, {len(data['lhs'])} left sides)")


if __name__ == "__main__":
    main_rules()
