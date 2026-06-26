"""Compare the live dev DB against an adapted "duplicate" DB by last history-unit date.

Used by dev-restart.bat (see CLAUDE.md "Dev DB scripts"). When a code change needs the
persisted DB to be adapted, the developer keeps the changed copy as a *duplicate*
(scheduler-state.duplicate.db) alongside the live DB. Before relaunching, dev-restart.bat
calls this to detect when the live DB has accumulated history units NEWER than the duplicate
-- meaning new work that the adaptation has not yet incorporated.

Exit codes:
  0  no duplicate, unreadable (fail open), or the live DB is not newer  -> proceed silently
  2  the live DB's last history unit is newer than the duplicate's      -> caller warns/prompts
"""

import os
import sqlite3
import sys


def last_unit_millis(db_path):
    """Max history_unit.time_millis in the DB, or None if missing/unreadable/empty."""
    if not os.path.exists(db_path):
        return None
    try:
        con = sqlite3.connect(db_path)
        try:
            row = con.execute("SELECT MAX(time_millis) FROM history_unit").fetchone()
            return row[0] if row else None
        finally:
            con.close()
    except Exception:
        return None


def _fmt(ms):
    from datetime import datetime, timezone

    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")


def main(argv):
    if len(argv) < 3:
        return 0
    live_db, duplicate_db = argv[1], argv[2]
    if not os.path.exists(duplicate_db):
        return 0  # no duplicate -> nothing to compare

    live = last_unit_millis(live_db)
    dup = last_unit_millis(duplicate_db)
    if live is None or dup is None:
        print("    (could not read history from one of the DBs - skipping the check)")
        return 0  # fail open: never block a launch on a read error

    if live > dup:
        print("    live last history unit: " + _fmt(live))
        print("    duplicate last unit:    " + _fmt(dup))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
