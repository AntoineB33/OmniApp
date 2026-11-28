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


if __name__ == "__main__" and "--resolve-eval" not in sys.argv and "--layer6" not in sys.argv:
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


if __name__ == "__main__" and "--resolve-eval" not in sys.argv and "--layer6" not in sys.argv:
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


if __name__ == "__main__" and "--resolve-eval" not in sys.argv and "--layer6" not in sys.argv:
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


if __name__ == "__main__" and "--resolve-eval" not in sys.argv and "--layer6" not in sys.argv:
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


# ---------------------------------------------------------------- constraints, problems, script, watch, modes, mode_rules

LAYER5_OUT = OUT.parent / "generator-inputs-golden.json"

BREAK_RULES = ["B1 → N", "B2 → N", "B3 → N", "mode 1 → no N", "mode 2 → N"]

ZERO_ = Pos(0)


def _state_of(texts, claims_spec):
    """A state from claims [(kind, owner, region json)] on these rules (one edit each)."""
    from line import LineState, Op
    from resolve import apply_edit
    from rules import RuleSet, parse_percent_rule, parse_rule
    pct = [parse_percent_rule(t) for t in texts]
    rs = RuleSet([parse_rule(t) for t, p in zip(texts, pct) if p is None], tuple(p for p in pct if p is not None))
    st = LineState.empty()
    for k, owner, g in claims_spec:
        st = apply_edit(rs, st, [Op.put(k, _region_of(g))], owner)
    return rs, st


def _ops_text(ops):
    return " ; ".join(str(o) for o in ops)


def _mid(a, b):
    """A few positions inside a stretch, to read the compiled run there."""
    if a == b or a.x == b.x:
        return []
    return [Pos((a.x + b.x) / 2), Pos(a.x + (b.x - a.x) / 4)]


def layer5_eval(case):
    import time as _time
    from constraints import (AsEarlyAsPossible, Breaks, KeepOn, MaximumGap, MinimumGap, ScreenBreaks, constraint_ops,
                             expand, pulled, push_of)
    from line import Op
    from mode_rules import compile_runs
    from modes import ModeTimeline
    from problems import MinimumLengthProblem, PresenceRatioProblem, shortfalls
    from script import ChipIn, ChipOn, EditAction, Event, PresenceAbove, Script, ScoreBelow, score
    from solver import limited
    from watch import look
    t0 = _time.monotonic()
    out = {}

    def put(name, f):
        try:
            with limited(3.0):
                out[name] = f()
        except Exception as e:  # noqa: BLE001
            out[name] = "error " + type(e).__name__ + (": " + str(e) if type(e).__name__ in ("Refused", "ValueError") else "")

    try:
        rs, st = _state_of(case["texts"], case["claims"])
    except Exception as e:  # noqa: BLE001
        return {"out": {"state": "error " + type(e).__name__}, "seconds": 0}
    c = Pos(F(case["chip"]))
    out["state"] = str(st.claims) + "\n--\n" + str(st.line)
    k = case["k"]
    if k == "constraints":
        L, P = F(case["L"]), F(case["P"])
        cons = {
            "min": lambda: MinimumGap("B1", P, after="N", at_least=L, pull="N" if case["pull"] else None),
            "early": lambda: AsEarlyAsPossible("B1", length=L),
            "max": lambda: MaximumGap("B2", P, after="N", at_least=L),
            "breaks": lambda: Breaks("B3", "N", L, P),
            "screen": lambda: ScreenBreaks((("B1", L, P), ("B2", 2 * L, 3 * P), ("B3", 3 * L, 6 * P)), "N", ("B2", "B3")),
            "keep": lambda: KeepOn(Op.put("B1", Region.of(Interval(F(1), F(1) + L, False, True)))),
        }
        ks = list(expand([cons[n]() for n in case["cons"]]))
        for x in ks:
            if hasattr(x, "bind"):
                x.bind(rs)
            if hasattr(x, "bind_all"):
                x.bind_all(ks)
        out["constraints"] = " | ".join(map(str, ks))
        for n, x in enumerate(ks):
            put(f"targets{n}", lambda x=x: " ; ".join(f"{kk}: {r}" for kk, r in sorted(x.targets(st, c).items())))
            put(f"ops{n}", lambda x=x: _ops_text(x.ops(st, c)))
            if isinstance(x, AsEarlyAsPossible) and x.length is not None:
                put(f"place{n}", lambda x=x: str(x.place(st, c)))
                put(f"until{n}", lambda x=x: str(x.place(st, c, until=c + 20)))
                put(f"obst{n}", lambda x=x: str(x.obstacles(st, c)[0]))
                put(f"pushed{n}", lambda x=x: ",".join(x.pushed_in(rs)) + "/" + ",".join(x.puts_at_chip(rs, "N")))
            if isinstance(x, MinimumGap):
                put(f"apart{n}", lambda x=x: str(x.apart(st.line.presence(x.after), x.pulls_of(st, c))))
        put("all", lambda: _ops_text(constraint_ops(rs, st, c, ks)))
        put("all_until", lambda: _ops_text(constraint_ops(rs, st, c, ks, until=F(case["chip"]) + 15)))
        put("pulled", lambda: str(pulled(st.line.presence("N").dilate(Interval(ZERO_, F(2), True, False)), st.line.presence("N"), F(3))))
    elif k == "problems":
        class Ctx:
            def __init__(self, cc):
                self.c, self.state, self.open_end = cc, st, None
                self.gen = type("G", (), {"rules": rs})()
                self.weights_full = self.weights_best = self.weights_uniform = None

            def expected(self, s, kind, weights, W):
                return s.line.integral(kind, W)
        H = F(case["H"])
        cc = F(case["chip"])
        pr = PresenceRatioProblem({"A": F(1, 2), "B": F(1, 4), "C": F(1, 4)}).with_window(H)
        ml = MinimumLengthProblem({"A": F(3), "B": F(2)}).with_window(H)
        ctx = Ctx(cc)
        for name, p in (("ratio", pr), ("length", ml)):
            put(name + "_error", lambda p=p: str(p.error(ctx, st)))
            for strat in ("left", "right", "spread3"):
                put(f"{name}_{strat}", lambda p=p, strat=strat: _ops_text(p.proposals(ctx, "none", strat)))
            put(name + "_extra", lambda p=p: " | ".join(f"{a}: {_ops_text(o)}" for a, o in p.extra(ctx)))
        put("stretch", lambda: str(pr.stretch_error(ctx, st)))
        put("short", lambda: " ; ".join(f"{iv} {d}" for iv, d in shortfalls(st.line.presence("A"), F(3), cc, H)))
        put("score", lambda: str(score([(pr, F(1)), (ml, F(1, 2))], st, cc)))
        for name, trig in (("chipin", ChipIn(F(2), F(9), "1")), ("above", PresenceAbove("A", F(100), F(1, 3), F(6), F(-5), F(30))),
                           ("scorebelow", ScoreBelow(F(1, 2)))):
            put("look_" + name, lambda trig=trig: str(look(trig, st, cc, "1", [(pr, F(1))])[0]))
        put("holds", lambda: str(ChipOn("A", within=F(2)).holds(st, cc, "1")) + str(ChipOn("A", lasted=F(1)).holds(st, cc, "1")) +
            str(PresenceAbove("A", F(100), F(1, 3), F(6), F(-5), F(30)).holds(st, cc, "1")))
    elif k == "script":
        e1 = Event(ChipOn("B1", "1"), (EditAction((Op.take("B1", Region.of(Interval(ZERO_, INF, False, False))),
                                                     Op.put("B1", Region.of(Interval(F(0), F(case["L"]), False, True)))), True),))
        e2 = Event(ChipIn(F(0), INF, "2"), (EditAction((Op.put("A", Region.point(Pos(F(1)))),)),))
        sc = Script.of([e1, e2], live=True)
        out["text"] = sc.text()
        out["push"] = str(push_of(e1))
        out["push2"] = str(push_of(e2))
        out["pushes"] = " | ".join(f"{a}: {b}" for a, b in sc.pushes().items())
        tl = ModeTimeline(F(0), "1", [(F(t), m) for t, m in case["modes"]])
        out["modes"] = ",".join(tl.mode_at(Pos(F(x))) for x in range(0, 20, 3))
        kn = tl.known(F(case["chip"]) + 5)
        out["known"] = " | ".join(f"{m}: {kn.region(m)} / {kn.possible(m)}" for m in ("1", "2", "3")) + f" {kn.current_mode()} {kn.current_start()}"
    elif k == "mode_rules":
        L, P = F(case["L"]), F(case["P"])
        cons = []
        if case["early"]:
            cons = [AsEarlyAsPossible("B1", length=L), MinimumGap("B1", P, after="N", at_least=L)]
            for x in cons:
                if hasattr(x, "bind"):
                    x.bind(rs)
                if hasattr(x, "bind_all"):
                    x.bind_all(cons)
        pushes = ()
        if case["push"]:
            e1 = Event(ChipOn("B1", "1"), (EditAction((Op.take("B1", Region.of(Interval(ZERO_, INF, False, False))),
                                                         Op.put("B1", Region.of(Interval(F(0), L, False, True)))), True),))
            pushes = (push_of(e1),)
        segs = [(Pos(F(a)), Pos(F(b)), m) for a, b, m in case["segments"]]

        def runs():
            stretches, refused = compile_runs(rs, st, segs, cons, pushes)
            lines = [f"{s.mode} [{s.a}, {s.b}]\n{s.after.claims}\n--\n{s.after.line}" +
                     ("".join(f"\n@{q}: {s.state_at(q).line}" for q in _mid(s.a, s.b)) if s.plan is not None else "")
                     for s in stretches]
            return "\n====\n".join(lines) + "\n####\n" + "\n".join(f"{x}: {w}" for x, w in refused)
        put("runs", runs)
    elif k == "script_text":
        from script import Script as _S
        from script_text import ScriptError, parse_script
        try:
            sc = parse_script(case["text"], _S(), rs)
            out["parsed"] = sc.text() + "\n--\n" + " | ".join(f"{lo}:{hi}:{m}" for lo, hi, m in sc.covered) + f" live={sc.live}"
        except ScriptError as e:
            out["parsed"] = "ScriptError: " + str(e)
        except Exception as e:  # noqa: BLE001 (a bug of the reference: `every 0 s:` then an event, KeyError)
            out["parsed"] = "error " + type(e).__name__
    return {"out": out, "seconds": _time.monotonic() - t0}


def _script_texts():
    """Script texts: written by the reference's Script.text, and corrupted copies of their lines."""
    from constraints import AsEarlyAsPossible, Breaks, KeepOn, MaximumGap, MinimumGap, ScreenBreaks
    from line import Op
    from script import (ChangeAction, ChipIn, ChipOn, ConstraintEditAction, Edited, EditAction, Event, PresenceAbove,
                        RerunAction, Script, ScoreBelow, ShowLine, Start, TransitionCount, Uncovered, Viewed)
    from dataclasses import replace as _replace
    regs = [Region.of(rset(R.randint(1, 2), -6, 30)) for _ in range(6)] + [rregion() for _ in range(3)]
    def rop():
        k = R.choice(["N", "B1", "B2"])
        g = R.choice(regs)
        return R.choice([Op.put(k, g, R.choice([100, 50])), Op.put_no(k, g), Op.take(k, g), Op.take_no(k, g)])
    trigs = [Start(), ChipIn(F(R.randint(-3, 3)), F(R.randint(4, 20)), R.choice([None, "1", "2"])), ChipIn(F(2), INF),
             ChipOn("B1", "1"), TransitionCount(R.randint(1, 3)), Edited(), Edited(True), Viewed(), Uncovered(),
             PresenceAbove("B1", F(100), F(1, 3), F(10), F(0), F(40), "1"), ScoreBelow(F(2, 3))]
    texts = []
    for _ in range(40):
        evs = []
        for _ in range(R.randint(1, 4)):
            t = R.choice(trigs)
            acts = []
            for _ in range(R.randint(1, 2)):
                r = R.random()
                if r < 0.5:
                    acts.append(EditAction(tuple(rop() for _ in range(R.randint(1, 2))), R.random() < 0.3))
                elif r < 0.6:
                    acts.append(ConstraintEditAction())
                elif r < 0.7:
                    acts.append(RerunAction(R.random() < 0.3))
                elif r < 0.8:
                    acts.append(ShowLine())
                else:
                    acts.append(ChangeAction(R.random() < 0.5, R.choice(["every 5 s", "the view script"]),
                                             Event(ChipIn(F(0), F(5)), (RerunAction(),))))
            evs.append(Event(t, tuple(acts)))
        cons = R.sample([MinimumGap("B1", F(7), after="N", at_least=F(2), pull="N"), MinimumGap("B1", F(5)),
                         AsEarlyAsPossible("B1", F(2)), AsEarlyAsPossible("B2"), MaximumGap("B2", F(9), after="N", at_least=F(3)),
                         MaximumGap("B1", F(4), length=F(2)), Breaks("B3", "N", F(2), F(9)),
                         ScreenBreaks((("B1", F(1), F(4)), ("B2", F(3), F(12))), "N", ("B1", "B2")),
                         KeepOn(Op.put("B1", Region.of(Interval(F(1), F(3), False, True))))], R.randint(0, 3))
        sc = Script.of(evs)
        sc = _replace(sc, constraints=tuple(cons), covered=[(Pos(F(R.randint(0, 5))), Pos(F(R.randint(6, 12))), R.choice([None, "1"]))],
                      live=R.random() < 0.5)
        if R.random() < 0.3:
            sc = sc.changed(ChangeAction(True, "every 2 s", Event(ChipIn(F(1), F(2)), (RerunAction(),))))
        text = sc.text()
        texts.append(text)
        lines = text.splitlines()
        if lines:
            i = R.randrange(len(lines))
            bad = list(lines)
            bad[i] = R.choice([bad[i].replace("if ", "iff ", 1), bad[i].replace("%", " percent", 1), bad[i].replace("[", "(", 1),
                               bad[i] + " and then", "keep B9 as early as possible", "if the chip is in [5, 2), then run the generator again.",
                               "every 0 s:", "if 0 mode transitions happened, then show the line."])
            texts.append("\n".join(bad))
    return texts


def main_layer5():
    import os
    import subprocess
    import tempfile
    cases = []

    def rclaims(kinds, n, lo=-10, hi=40):
        out = []
        for _ in range(n):
            g = Region.of(rset(R.randint(1, 2), lo, hi))
            if g.is_empty():
                continue
            out.append((R.choice(kinds), R.choice(["user", "user", "generated"]), region_json(g)))
        return out
    for _ in range(60):
        cons = R.sample(["min", "early", "max", "breaks", "screen", "keep"], R.randint(1, 3))
        cases.append({"k": "constraints", "texts": BREAK_RULES, "claims": rclaims(["N", "B1", "B2"], R.randint(0, 4)),
                      "chip": str(R.randint(-2, 10)), "L": str(R.choice([1, 2, 3])), "P": str(R.choice([4, 6, 10])), "cons": cons,
                      "pull": R.random() < 0.5})
    for _ in range(40):
        cases.append({"k": "problems", "texts": ["A → no B", "C → no A"], "claims": rclaims(["A", "B", "C"], R.randint(1, 5), -5, 30),
                      "chip": str(R.randint(-2, 8)), "H": str(R.choice([6, 10, 16]))})
    for _ in range(20):
        ts = sorted(R.sample(range(1, 20), R.randint(0, 4)))
        modes, prev = [], "1"
        for t in ts:
            m = R.choice([x for x in ("1", "2", "3") if x != prev])
            modes.append((t, m))
            prev = m
        cases.append({"k": "script", "texts": ["A → B"], "claims": [], "chip": str(R.randint(0, 12)), "L": str(R.choice([2, 5])), "modes": modes})
    mode_sets = [BREAK_RULES, BREAK_RULES, BREAK_RULES, ["mode 1 → A", "A → no B"], ["mode 2 → N or M", "N → no A"],
                 ["mode 1 & A → no A"], ["mode 1 → B 50%", "B → C 1 unit on the right"],
                 ["mode 1 → A 50% or B", "A is always 100%"], ["mode 2 → A 50%", "A is always 100%"]]
    for _ in range(90):
        texts = R.choice(mode_sets)
        segs, x = [], R.randint(-2, 3)
        for _ in range(R.randint(1, 3)):
            y = x + R.choice([0, 1, 2, 5])
            segs.append((str(x), str(y), R.choice(["1", "2"])))
            x = y + R.choice([0, 1])
        cases.append({"k": "mode_rules", "texts": texts, "claims": rclaims(["N", "B1", "A", "B"], R.randint(0, 3), -4, 14),
                      "chip": "0", "segments": segs, "L": str(R.choice([1, 2])), "P": str(R.choice([3, 5])),
                      "early": texts is BREAK_RULES and R.random() < 0.8, "push": texts is BREAK_RULES and R.random() < 0.6})
    for text in _script_texts():
        cases.append({"k": "script_text", "texts": BREAK_RULES + ["B3 → N"], "claims": [], "chip": "0", "text": text})
    tmp = Path(tempfile.mkdtemp())
    (tmp / "in.json").write_text(json.dumps(cases, ensure_ascii=False), encoding="utf-8")
    runs = []
    for seed in ("1", "2"):
        env = dict(os.environ, PYTHONHASHSEED=seed)
        subprocess.run([sys.executable, "-I", __file__, "--resolve-eval", str(REF), str(tmp / "in.json"), str(tmp / f"o{seed}.json"), "layer5"],
                       env=env, check=True, timeout=3000)
        runs.append(json.loads((tmp / f"o{seed}.json").read_text(encoding="utf-8")))
    kept, unstable, slow = [], 0, 0
    for case, a, b in zip(cases, *runs):
        if max(a["seconds"], b["seconds"]) > 8:
            slow += 1
            continue
        if a["out"] != b["out"]:
            unstable += 1
            continue
        kept.append({**case, "expected": a["out"]})
    LAYER5_OUT.write_text(json.dumps({"cases": kept}, ensure_ascii=False, indent=0), encoding="utf-8")
    errs = sum(1 for c in kept for v in c["expected"].values() if isinstance(v, str) and v.startswith("error"))
    by = {}
    for c in kept:
        by[c["k"]] = by.get(c["k"], 0) + 1
    print(f"wrote {LAYER5_OUT} ({len(kept)} cases {by}; {unstable} unstable, {slow} slow; {errs} error answers)")



LAYER6_OUT = OUT.parent / "last-layer-golden.json"


def _sched_text(d):
    return " ; ".join(f"{k}: " + ",".join(f"{a}-{b}" for a, b in v) for k, v in sorted(d.items()) if v)


def _score_of(case):
    from score import Score, Settings, Task
    tasks = {}
    for name, pr, mn, mx, res in case["tasks"]:
        tasks[name] = Task(F(pr), float(mn), math.inf if mx is None else float(mx), {k: float(v) for k, v in res.items()})
    return Score(tasks, Settings(compensation=float(case["lam"])))


def _problems(case):
    from problems import MinimumLengthProblem, PresenceRatioProblem
    out = []
    if "ratio" in case["probs"]:
        out.append((PresenceRatioProblem({k: F(v) for k, v in case["ratio"].items()}), F(1)))
    if "length" in case["probs"]:
        out.append((MinimumLengthProblem({k: F(v) for k, v in case["length"].items()}), F(1, 2)))
    return out


def layer6_eval(case):
    import time as _time
    from modes import ModeTimeline
    from solver import limited
    t0 = _time.monotonic()
    out = {}

    def put(name, f):
        try:
            with limited(3.0):
                out[name] = f()
        except Exception as e:  # noqa: BLE001
            out[name] = "error " + type(e).__name__ + (": " + str(e) if type(e).__name__ in ("Refused", "ValueError", "NoBestScript") else "")
    k = case["k"]
    if k == "random":
        r = random.Random(case["seed"])
        out["floats"] = [repr(r.random()) for _ in range(5)]
        out["ints"] = [r.randint(0, case["n"]) for _ in range(5)] + [r.randrange(case["n"] + 1) for _ in range(3)] + [r.getrandbits(31) for _ in range(2)]
        return {"out": out, "seconds": 0}
    if k == "probability":
        from probability import ProbabilityFinder
        tl = ModeTimeline(F(0), "1", [(F(t), m) for t, m in case["modes"]])
        kn = tl.known(F(case["frontier"]))
        m = ProbabilityFinder().fit(kn)
        out["rules"] = m.rules()

        def sc():
            done, res = m.scenarios(kn, F(case["until"]), case["max"])
            return "\n".join(f"{s.probability}: " + " ; ".join(f"{mm} {s.cover[mm]}" for mm in sorted(s.cover)) + f" / {s.uncovered}" for s in done) + f"\nleft {res}"
        put("scenarios", sc)
        return {"out": out, "seconds": _time.monotonic() - t0}
    if k == "turns":
        from ratio_plan import turns
        free = _region_of(case["free"])

        def tu():
            r = turns(free, F(case["start"]), case["order"], {a: F(b) for a, b in case["lengths"].items()},
                      {a: F(b) for a, b in case["minimum"].items()})
            return "None" if r is None else " ; ".join(f"{a}: {g}" for a, g in sorted(r.items()))
        put("turns", tu)
        return {"out": out, "seconds": _time.monotonic() - t0}
    try:
        rs, st = _state_of(case["texts"], case["claims"])
    except Exception as e:  # noqa: BLE001
        return {"out": {"state": "error " + type(e).__name__}, "seconds": 0}
    out["state"] = str(st.claims) + "\n--\n" + str(st.line)
    c = F(case["chip"])
    if k == "plan":
        from ratio_plan import Fill, Plan, check_best_exists, exclusions
        probs = _problems(case)

        def exc():
            e = exclusions(rs)
            return "None" if e is None else " | ".join(sorted(",".join(sorted(x)) for x in e))
        put("exclusions", exc)
        put("best", lambda: str(check_best_exists(rs, probs)))
        bound = lambda: [(q.with_window(F(case["H"])), w) for q, w in probs]  # noqa: E731

        def plan():
            p = Plan(rs, bound(), (), case["seed"])
            if not p.ok:
                return "False"
            o = p.ops(st, c)
            return "True " + ("None" if o is None else _ops_text(o))
        put("plan", plan)

        def fill():
            f = Fill(bound())
            if not f.ok:
                return "False"
            o = f.ops(rs, st, c, F(case["H"]))
            return "True " + ("None" if o is None else _ops_text(o))
        put("fill", fill)
    elif k == "score":
        from instant import Instant
        score = _score_of(case)
        end = c + F(case["H"])
        pre = {i: st.claims.owned(i, "user") for i in score.tasks}

        def ev():
            r = score.evaluate(st.line, c, end, pre)
            return [r.D, r.J, r.J1, r.J2, r.J3]
        put("evaluate", ev)
        put("target", lambda: [[i, v] for i, v in sorted(score.target(st.line, c, pre).items())])
        inst = Instant(rs, score)
        put("schedule", lambda: _sched_text(inst.schedule(st, c, end)))
        put("schedule_start", lambda: _sched_text(inst.schedule(st, c, end, start=c + 1)))
    elif k in ("generator", "simulation"):
        from generator import RulesGenerator
        probs = _problems(case)
        tl = ModeTimeline(F(0), "1", [(F(t), m) for t, m in case["modes"]])

        def gen():
            return RulesGenerator(rs, probs, case["q"], constraints=(), seed=case["seed"], window=F(case["H"]), shortcuts=case["shortcuts"])
        if k == "generator":
            def run():
                s, stats = gen().run(st, c, tl.known(c))
                return s.text() + f"\n--\n{stats.chosen} | {stats.valid} | {stats.definitive} | {stats.final} | {stats.constraints}"
            put("run", run)
        else:
            from line import Op
            from simulation import Chip, Simulation, UserEdit

            def sim():
                edits = [UserEdit(F(t), [Op.put(kk, _region_of(g))]) for t, kk, g in case["edits"]]
                sm = Simulation(st, gen(), Chip([(F(a), F(b)) for a, b in case["chip_path"]]), tl, edits)
                log = sm.run(F(case["end"]))
                ch = "\n".join(f"{x.time} {x.chip} {x.source}: {x.after.line}" for x in log.changes)
                rf = "\n".join(f"{t} {s}: {w}" for t, s, w in log.refused)
                return ch + "\n####\n" + rf + "\n####\n" + "\n".join(f"{r[0]} {r[1]} {r[2]}" for r in log.runs) + f"\n{sm.script.text()}"
            put("sim", sim)
    return {"out": out, "seconds": _time.monotonic() - t0}


def main_layer6():
    import os
    import subprocess
    import tempfile
    cases = []
    R.seed(20261129)  # (the same cases whether the other layers ran first or not)

    def rclaims(kinds, n, lo=-10, hi=40):
        out = []
        for _ in range(n):
            g = Region.of(rset(R.randint(1, 2), lo, hi))
            if g.is_empty():
                continue
            out.append((R.choice(kinds), R.choice(["user", "user", "generated"]), region_json(g)))
        return out

    def rmodes(n, hi=30):
        ts = sorted(R.sample(range(1, hi), n))
        modes, prev = [], "1"
        for t in ts:
            m = R.choice([x for x in ("1", "2", "3") if x != prev])
            modes.append((t, m))
            prev = m
        return modes
    for _ in range(20):
        cases.append({"k": "random", "seed": R.randrange(2 ** 40), "n": R.choice([1, 6, 100, 2 ** 20])})
    for _ in range(40):
        cases.append({"k": "probability", "modes": rmodes(R.randint(0, 12)), "frontier": str(R.randint(0, 30)), "until": str(R.randint(31, 60)),
                      "max": R.choice([2, 8])})
    for _ in range(40):
        lengths = {"A": str(R.choice([1, 2, 3])), "B": str(R.choice([1, 2])), "C": str(R.choice([1, 4]))}
        cases.append({"k": "turns", "free": region_json(Region.of(rset(R.randint(1, 3), 0, 30))), "start": str(R.randint(-2, 10)),
                      "order": R.sample(["A", "B", "C"], R.randint(1, 3)), "lengths": lengths, "minimum": {"A": "1", "B": "1", "C": str(R.choice([1, 2]))}})
    plan_sets = [["A → no B"], ["A → no B", "A → no C", "B → no C"], ["A → B"], [], ["mode 1 → no A"]]
    for _ in range(50):
        cases.append({"k": "plan", "texts": R.choice(plan_sets), "claims": rclaims(["A", "B", "C"], R.randint(0, 3), -5, 25),
                      "chip": str(R.randint(-2, 6)), "probs": R.sample(["ratio", "length"], R.randint(1, 2)),
                      "ratio": R.choice([{"A": "1/2", "B": "1/2"}, {"A": "1/3", "B": "1/3", "C": "1/3"}, {"A": "1/4", "B": "3/4"}]),
                      "length": R.choice([{"A": "2"}, {"A": "1", "B": "3"}]), "H": str(R.choice([6, 12])), "seed": R.randrange(1000)})
    for _ in range(50):
        res = R.choice([{}, {"N": R.choice([0, 0.5])}])
        tasks = [["A", str(R.choice([1, 2, 3])), R.choice([300, 600]), R.choice([None, 1800]), res],
                 ["B", str(R.choice([1, 2])), R.choice([300, 900]), None, {}]]
        if R.random() < 0.5:
            tasks.append(["C", "1", 600, None, {"N": 0}])
        cases.append({"k": "score", "texts": ["N → no A", "A → no B"] if R.random() < 0.5 else [],
                      "claims": rclaims(["A", "B", "C", "N"], R.randint(0, 4), -3600, 4 * 3600),
                      "chip": str(R.randint(0, 3600)), "H": str(R.choice([3600, 3 * 3600])), "tasks": tasks, "lam": R.choice([3600, 4 * 3600])})
    gen_sets = [["A → no B"], ["mode 1 → A 50% or B", "A is always 100%"], ["A → no B", "mode 2 → no A"], []]
    for kk, n in (("generator", 40), ("simulation", 25)):
        for _ in range(n):
            case = {"k": kk, "texts": R.choice(gen_sets), "claims": rclaims(["A", "B"], R.randint(0, 2), -5, 20), "chip": "0",
                    "probs": R.sample(["ratio", "length"], R.randint(1, 2)),
                    "ratio": R.choice([{"A": "1/2", "B": "1/2"}, {"A": "1/3", "B": "2/3"}]), "length": R.choice([{"A": "2"}, {"B": "1"}]),
                    "H": str(R.choice([4, 8])), "seed": R.randrange(1000), "q": R.choice([0.0, 0.1, 0.3]), "shortcuts": R.random() < 0.5,
                    "modes": rmodes(R.randint(0, 3), 15)}
            if kk == "simulation":
                pts, t, x = [("0", "0")], 0, 0
                for _ in range(R.randint(1, 3)):
                    t += R.randint(1, 5)
                    x += R.choice([0, 1, 3])
                    pts.append((str(t), str(x)))
                case["chip_path"] = pts
                case["end"] = str(t + R.randint(0, 3))
                edits = [(str(R.randint(0, t)), R.choice(["A", "B"]), region_json(Region.of(rset(1, 0, 15)))) for _ in range(R.randint(0, 2))]
                case["edits"] = [e for e in edits if not _region_of(e[2]).is_empty()]
            cases.append(case)
    tmp = Path(tempfile.mkdtemp())
    (tmp / "in.json").write_text(json.dumps(cases, ensure_ascii=False), encoding="utf-8")
    runs = []
    for seed in ("1", "2"):
        env = dict(os.environ, PYTHONHASHSEED=seed)
        subprocess.run([sys.executable, "-I", __file__, "--resolve-eval", str(REF), str(tmp / "in.json"), str(tmp / f"o{seed}.json"), "layer6"],
                       env=env, check=True, timeout=3000)
        runs.append(json.loads((tmp / f"o{seed}.json").read_text(encoding="utf-8")))
    kept, unstable, slow = [], 0, 0
    for case, a, b in zip(cases, *runs):
        if max(a["seconds"], b["seconds"]) > 2:
            slow += 1
            continue
        if a["out"] != b["out"]:
            unstable += 1
            continue
        kept.append({**case, "expected": a["out"]})
    LAYER6_OUT.write_text(json.dumps({"cases": kept}, ensure_ascii=False, indent=0), encoding="utf-8")
    errs = sum(1 for c in kept for v in c["expected"].values() if isinstance(v, str) and v.startswith("error"))
    by = {}
    for c in kept:
        by[c["k"]] = by.get(c["k"], 0) + 1
    print(f"wrote {LAYER6_OUT} ({len(kept)} cases {by}; {unstable} unstable, {slow} slow; {errs} error answers)")


if __name__ == "__main__" and "--resolve-eval" in sys.argv:
    i = sys.argv.index("--resolve-eval")
    cases_in = json.loads(Path(sys.argv[i + 2]).read_text(encoding="utf-8"))
    ev = {"layer5": layer5_eval, "layer6": layer6_eval}.get((sys.argv[i + 4:i + 5] or [""])[0], resolve_eval)
    Path(sys.argv[i + 3]).write_text(json.dumps([ev(c) for c in cases_in], ensure_ascii=False), encoding="utf-8")
elif __name__ == "__main__" and "--layer6" in sys.argv:
    main_layer6()
elif __name__ == "__main__":
    main_resolve()
    main_layer5()
    main_layer6()
