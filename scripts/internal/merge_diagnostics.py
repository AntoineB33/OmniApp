"""Merge diagnostics logs into one chronological timeline (see scripts/collect-diagnostics.bat).

Each input file holds lines that start with a sortable "yyyy-MM-dd HH:mm:ss.fff" stamp (written by
shared Diagnostics.kt and the .bat scripts' :diag markers). Lines without a stamp (wrapped output,
stray prints) inherit the previous stamped line's timestamp so they stay next to it. The sort is
stable, so same-timestamp lines keep their within-file order. Missing files are skipped silently --
e.g. no Android log when the phone is disconnected.

Usage: merge_diagnostics.py <log1> [<log2> ...]
"""

import re
import sys

STAMP = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ")


def read_entries(path):
    try:
        # utf-8-sig: PowerShell-written files carry a BOM; the app's lines are plain UTF-8 (with "→").
        with open(path, encoding="utf-8-sig", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return []
    entries = []
    last = "0000-00-00 00:00:00.000"
    for line in lines:
        m = STAMP.match(line)
        if m:
            last = m.group(1)
            entries.append((last, line))
        elif line.strip():
            entries.append((last, line))
    return entries


def main(argv):
    # The app's lines are UTF-8; a cp1252 console would die on "→". Replace what it can't show.
    sys.stdout.reconfigure(encoding=sys.stdout.encoding or "utf-8", errors="replace")
    if len(argv) < 2:
        print("usage: merge_diagnostics.py <log1> [<log2> ...]")
        return 2
    entries = []
    for path in argv[1:]:
        entries.extend(read_entries(path))
    if not entries:
        print("(no diagnostics lines found)")
        return 0
    entries.sort(key=lambda e: e[0])
    try:
        for _, line in entries:
            print(line)
    except OSError:
        pass  # downstream pipe closed early (e.g. piped into a head-like filter) -- not an error
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
