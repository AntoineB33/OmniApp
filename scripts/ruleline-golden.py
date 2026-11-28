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

_ARGS = sys.argv[2:3] if sys.argv[1:2] == ["--resolve-eval"] else sys.argv[1:]  # (an evaluator: --resolve-eval REF in out)
REF = Path(_ARGS[0]) if len(_ARGS) > 0 else Path(r"C:\code\line_manager\scripts")
CASES = int(_ARGS[1]) if len(_ARGS) > 1 else 300
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


if __name__ == "__main__" and "--resolve-eval" not in sys.argv:
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


if __name__ == "__main__" and "--resolve-eval" not in sys.argv:
    main_rules()


# ---------------------------------------------------------------- solver.py, sweep.py

SOLVE_OUT = OUT.parent / "solve-golden.json"


def rclaim_region():
    r = R.random()
    if r < 0.75:
        return Region.of(rset(R.randint(1, 3), -6, 6))
    lo = Pos(R.randint(-4, 2))
    per = Pos(R.choice([2, 3, 4, 6]))
    pat = IntervalSet(rpattern(per))
    if not pat:
        return Region.of(rset(1, -6, 6))
    side = R.random()
    return Region(lo, lo, IntervalSet(), per, pat if side < 0.3 else IntervalSet(), per, pat if side >= 0.15 else IntervalSet())


def main_solve():
    import time as _time
    from rules import RuleError, RuleSet, parse_percent_rule, parse_rule
    from solver import Conflict, Interrupted, Problem, Undetermined, limited, satisfiable, solve
    texts = reference_rule_texts()
    rand = [f"{rand_lhs_text()} → {rand_rhs_text()}" for _ in range(CASES)] + [rand_percent_text() for _ in range(60)]
    pool = [t for t in texts + rand if "error" not in rule_result(t)]
    sets = []
    # rule sets the lattices solve: "or"s of single positions relative to p, rules on chosen kinds at offsets
    while len(sets) < 50:
        a, b, c, d = R.sample(KINDS, 4)
        off = lambda: R.choice(["", "", " 1 unit on the right", " 1 unit on the left", " 2 units on the right"])
        ts = [f"{a} → {b}{off()} or {c}{off()}"]
        if R.random() < 0.7:
            ts.append(f"{b} → no {c}{off()}")
        if R.random() < 0.5:
            ts.append(f"{c} → {d}{off()}")
        if R.random() < 0.3:
            ts.append(f"{b} & not {d} → no {c}{off()}")
        if R.random() < 0.3:
            ts.append(f"{b} → {d} 50%")
        if "error" in set_result(ts):
            continue
        sets.append(ts)
    while len(sets) < 170:
        ts = R.sample(pool, R.randint(1, 3))
        if "error" in set_result(ts):
            continue
        sets.append(ts)
    cases, dropped = [], 0
    for ts in sets:
        pct = [parse_percent_rule(t) for t in ts]
        rs = RuleSet([parse_rule(t) for t, p in zip(ts, pct) if p is None], tuple(p for p in pct if p is not None))
        kinds = [k for k in rs.order if not k.startswith("§")] or ["A"]
        t0 = _time.monotonic()
        try:
            with limited(1.0):
                sat = satisfiable(rs) or "satisfiable"
        except Exception as e:  # noqa: BLE001
            sat = "error " + type(e).__name__
        if _time.monotonic() - t0 > 0.8:
            sat = "skip"  # (too close to its own time limit to be an answer)
        for _ in range(3):
            pos, neg = {}, {}
            for k in R.sample(kinds, R.randint(1, len(kinds))):
                used = Region.empty()
                for v in R.sample([F(100), F(100), F(50), F(20), F(80)], R.randint(1, 2)):
                    g = rclaim_region() - used
                    if g:
                        pos.setdefault(k, {})[v] = g
                        used = used | g
            if R.random() < 0.3:
                k = R.choice(kinds)
                neg[k] = Region.of(rset(1, -6, 6))
            t0 = _time.monotonic()
            try:
                with limited(1.0):
                    out = "line\n" + str(solve(rs, Problem(pos, neg)))
            except Conflict as e:
                out = "Conflict: " + str(e)
            except Undetermined as e:
                out = "Undetermined: " + str(e)
            except IncompatiblePeriods as e:
                out = "IncompatiblePeriods"
            except (RuleError, ValueError, RecursionError) as e:
                out = "error " + type(e).__name__
            if _time.monotonic() - t0 > 0.8 or out == "Undetermined: it takes too long to compute":
                dropped += 1
                continue
            enc = lambda d: {k: {str(v): region_json(g) for v, g in by.items()} for k, by in d.items()}
            cases.append({"texts": ts, "pos": enc(pos), "neg": {k: region_json(g) for k, g in neg.items()},
                          "out": out, "satisfiable": sat})
    SOLVE_OUT.write_text(json.dumps({"cases": cases}, ensure_ascii=False, indent=0), encoding="utf-8")
    kinds_out = {}
    for c in cases:
        kinds_out[c["out"].split(":")[0].split("\n")[0]] = kinds_out.get(c["out"].split(":")[0].split("\n")[0], 0) + 1
    print(f"wrote {SOLVE_OUT} ({len(cases)} cases, {dropped} dropped as too slow): {kinds_out}")


if __name__ == "__main__" and "--resolve-eval" not in sys.argv:
    main_solve()


# ---------------------------------------------------------------- line.py

LINE_OUT = OUT.parent / "line-state-golden.json"


def main_line_state():
    from line import GEN, MODE, USER, Claims, LineState, Op
    from solver import Line
    out = []
    owners = [USER, GEN, MODE]
    for _ in range(CASES):
        c = Claims()
        steps = []
        for _ in range(R.randint(1, 8)):
            k = R.choice(KINDS[:3])
            g = rclaim_region()
            a = R.choice(["pos", "pos", "neg", "taken", "rpos", "rneg", "rtaken"])
            o = R.choice(owners)
            v = R.choice([F(100), F(50), F(20)])
            {"pos": lambda: c.add_pos(k, g, v, o), "neg": lambda: c.add_neg(k, g, o), "taken": lambda: c.add_taken(k, g, o),
             "rpos": lambda: c.remove_pos(k, g), "rneg": lambda: c.remove_neg(k, g), "rtaken": lambda: c.remove_taken(k, g)}[a]()
            steps.append({"a": a, "k": k, "g": region_json(g), "o": o, "v": str(v)})
        other = Claims()
        other.add_pos("A", Region.of(rset(1, -6, 6)), F(100), USER)
        where = Region.of(rset(2, -6, 6))
        p = c.problem({"B": Region.of(rset(1, -6, 6))} if R.random() < 0.5 else None)
        extra = None
        st = LineState(c, Line({}))
        cut = rpos(-8, 8)
        out.append({
            "steps": steps, "other": region_json(other.pos["A"][(F(100), USER)]), "where": region_json(where),
            "cut": pos_json(cut), "neg_b": None,
            "results": {
                "str": str(c), "on": str(c.on(where)), "kinds": " ".join(c.kinds()), "empty": str(c.is_empty()),
                "merged": str(c.merged(other)), "same": str(c.same(c.copy())), "same_other": str(c.same(other)),
                "owned": " | ".join(f"{k}/{o}: {c.owned(k, o)}" for k in KINDS[:3] for o in owners),
                "by_value": " | ".join(f"{k}: " + ", ".join(f"{v}={r}" for v, r in sorted(c.by_value(k).items())) for k in KINDS[:3]),
                "memory": str(st.memory()), "starts_before": " ".join(map(str, st.starts_before(cut))),
                "op": str(Op.put("A", where, 50)) + " ; " + str(Op.take_no("B", where)),
            },
        })
    LINE_OUT.write_text(json.dumps({"cases": out}, ensure_ascii=False, indent=0), encoding="utf-8")
    print(f"wrote {LINE_OUT} ({len(out)} cases)")


if __name__ == "__main__" and "--resolve-eval" not in sys.argv:
    main_line_state()


# ---------------------------------------------------------------- resolve.py, guards.py

RESOLVE_OUT = OUT.parent / "resolve-golden.json"


def _region_of(o):
    def p(a):
        return float(a[0]) if a[0] in ("inf", "-inf") else Pos(F(a[0]), a[1])
    st = lambda e: IntervalSet(Interval._mk(p(q[0]), p(q[1])) for q in e)
    return Region(p(o["lo"]), p(o["hi"]), st(o["core"]), p(o["pl"]), st(o["left"]), p(o["pr"]), st(o["right"]))


def resolve_eval(case):
    """Every step of a case through the reference: the outcome and the state after it."""
    import time as _time
    from guards import check_mode_edit, check_script_edit
    from line import LineState, Op
    from rules import RuleSet, parse_percent_rule, parse_rule
    from solver import Interrupted, limited
    from resolve import Refused, apply_edit, flattened, mode_won
    ts = case["texts"]
    pct = [parse_percent_rule(t) for t in ts]
    rs = RuleSet([parse_rule(t) for t, p in zip(ts, pct) if p is None], tuple(p for p in pct if p is not None))
    state = LineState.empty()
    out = []
    t0 = _time.monotonic()
    for st in case["steps"]:
        ops = [Op(o["a"], o["k"], _region_of(o["g"]), F(o["v"])) for o in st["ops"]]
        chip = Pos(F(st["chip"]))
        try:
            with limited(3.0):
                after = apply_edit(rs, state, ops, st["owner"])
                check = None
                if st["owner"] == "generated":
                    check = check_script_edit(state, after, chip, ops, rs)
                elif st["owner"] == "mode":
                    after = mode_won(state, after)
                    check = check_mode_edit(state, after, Region.point(chip), chip)
            state = after
            out.append({"ok": True, "claims": str(state.claims), "line": str(state.line), "history": len(state.history),
                        "clean": state.claims.clean, "check": check})
        except Refused as e:
            out.append({"ok": False, "refused": str(e)})
        except Exception as e:  # noqa: BLE001
            out.append({"ok": False, "error": type(e).__name__ + ": " + str(e)})
    try:
        with limited(3.0):
            fl = flattened(rs, state, Pos(F(case["flat"])))
            flat = str(fl.claims) + "\n--\n" + str(fl.line) + "\n--\n" + str(len(fl.history))
    except Exception as e:  # noqa: BLE001
        flat = "error " + type(e).__name__
    return {"steps": out, "flat": flat, "seconds": _time.monotonic() - t0}


def main_resolve():
    import os
    import subprocess
    import tempfile
    texts = reference_rule_texts()
    rand = [f"{rand_lhs_text()} → {rand_rhs_text()}" for _ in range(CASES)] + [rand_percent_text() for _ in range(60)]
    pool = [t for t in texts + rand if "error" not in rule_result(t)]
    sets = []
    while len(sets) < 40:
        a, b, c, d = R.sample(KINDS, 4)
        ts = [f"{a} → {b} or {c}", f"{b} → no {c} 1 unit on the right"] if R.random() < 0.5 else [f"{a} → {b}", f"{b} & {c} → no {d}"]
        if R.random() < 0.4:
            ts.append(f"{a} → {d} on [0, 2) of l")
        if "error" not in set_result(ts):
            sets.append(ts)
    while len(sets) < 140:
        ts = R.sample(pool, R.randint(1, 3))
        if "error" not in set_result(ts):
            sets.append(ts)
    cases = []
    for ts in sets:
        pct = [__import__("rules").parse_percent_rule(t) for t in ts]
        kinds = sorted({k for t in ts for k in KINDS if k in t.replace("→", " ").split()}) or ["A"]
        steps = []
        for n in range(R.randint(1, 5)):
            owner = R.choice(["user", "user", "user", "generated", "mode"])
            chip = R.randint(-3, 3)
            ops = []
            for _ in range(R.randint(1, 2)):
                k = R.choice(kinds)
                a = R.choice(["put", "put", "put_no", "take", "take_no"])
                g = Region.point(Pos(chip)) if owner == "mode" else (Region.of(rset(1, chip, chip + 8)) if owner == "generated" else Region.of(rset(R.randint(1, 2), -6, 6)))
                if g.is_empty():
                    g = Region.point(Pos(chip))
                ops.append({"a": a, "k": k, "g": region_json(g), "v": str(R.choice([100, 100, 50]) if a == "put" else 100)})
            steps.append({"owner": owner, "ops": ops, "chip": str(chip)})
        cases.append({"texts": ts, "steps": steps, "flat": str(R.randint(-4, 4))})
    # conflicts on purpose: older elements whose consequences a newer edit forbids (the fewest to remove)
    shapes = [["A → B"], ["A & C → B"], ["A → B on [0, 2) of l"], ["A → B 1 unit on the right", "C → B"],
              ["A or C → B"], ["A → B", "B → D"], ["A → B 50%", "C → B 100%"]]
    for _ in range(60):
        ts = R.choice(shapes)
        if "error" in set_result(ts):
            continue
        steps = []
        for _ in range(R.randint(2, 4)):
            k = R.choice(["A", "A", "C", "D"])
            steps.append({"owner": "user", "ops": [{"a": "put", "k": k, "g": region_json(Region.of(rset(1, -6, 6))), "v": "100"}], "chip": "0"})
        g = Region.of(rset(1, -6, 6))
        steps.append({"owner": R.choice(["user", "user", "generated"]), "ops": [{"a": R.choice(["put_no", "take"]), "k": "B", "g": region_json(g), "v": "100"}], "chip": "-9"})
        if R.random() < 0.5:
            steps.append({"owner": "user", "ops": [{"a": "take_no", "k": "B", "g": region_json(g), "v": "100"}], "chip": "0"})
        cases.append({"texts": ts, "steps": steps, "flat": str(R.randint(-4, 4))})
    tmp = Path(tempfile.mkdtemp())
    (tmp / "in.json").write_text(json.dumps(cases, ensure_ascii=False), encoding="utf-8")
    runs = []
    for seed in ("1", "2"):
        env = dict(os.environ, PYTHONHASHSEED=seed)
        subprocess.run([sys.executable, "-I", __file__, "--resolve-eval", str(REF), str(tmp / "in.json"), str(tmp / f"out{seed}.json")],
                       env=env, check=True, timeout=3000)
        runs.append(json.loads((tmp / f"out{seed}.json").read_text(encoding="utf-8")))
    kept, unstable, slow = [], 0, 0
    for case, a, b in zip(cases, *runs):
        if max(a["seconds"], b["seconds"]) > 4:
            slow += 1
            continue
        a2, b2 = dict(a), dict(b)
        a2.pop("seconds"), b2.pop("seconds")
        if a2 != b2 or any("error" in s and "Undetermined" in s["error"] for s in a2["steps"]):
            unstable += 1
            continue
        kept.append({**case, "expected": a2})
    RESOLVE_OUT.write_text(json.dumps({"cases": kept}, ensure_ascii=False, indent=0), encoding="utf-8")
    outcomes = {}
    for c in kept:
        for s_ in c["expected"]["steps"]:
            key = "ok" if s_["ok"] else ("refused" if "refused" in s_ else "error")
            outcomes[key] = outcomes.get(key, 0) + 1
    print(f"wrote {RESOLVE_OUT} ({len(kept)} cases; {unstable} unstable across hash seeds, {slow} slow left out): {outcomes}")


if __name__ == "__main__" and "--resolve-eval" in sys.argv:
    i = sys.argv.index("--resolve-eval")
    cases_in = json.loads(Path(sys.argv[i + 2]).read_text(encoding="utf-8"))
    Path(sys.argv[i + 3]).write_text(json.dumps([resolve_eval(c) for c in cases_in], ensure_ascii=False), encoding="utf-8")
elif __name__ == "__main__":
    main_resolve()
