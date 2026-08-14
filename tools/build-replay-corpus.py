#!/usr/bin/env python3
"""Rebuild the 2026-08-12 replay corpus from the exported logs and a copy of the app database.

ADR 0001 gates S5 on field evidence, and the replay harness (`./gradlew :shared:runReplay`) needs
two things a raw export does not give you: the trail geometry, which lives in the database rather
than the log, and a split into individual follow sessions, since one log holds several and the
matcher is stateful across a session boundary.

This script encodes that derivation so it survives losing a scratch directory, and so the numbers
quoted in the ADR can be reproduced by someone who was not there.

    python3 tools/build-replay-corpus.py --out ~/replay-corpus

Note that the 2026-08-12 exports are no longer in ~/share and the app's log is session-scoped, so
those walks cannot be re-exported. The session files preserved in ~/replay-corpus are irreplaceable;
this script regenerates the GPX geometry, and would regenerate the splits again if a full export
were ever available. See ~/replay-corpus/README.md.

Inputs, both read-only:
    ~/share/bold_explorer.db                          a copy of the app database
    ~/share/bold_explorer_audio_log_2026081?_*.jsonl  the exported walks

Nothing here writes to the inputs. The database in ~/share is the owner's backup.
"""

import argparse
import json
import pathlib
import sqlite3
import sys
from datetime import datetime

# Trail 12 carries a stray waypoint at position 139, created 78 minutes after
# the trail's last track point and 1031 m from it. It makes a 1492 m trail look 2523 m long and
# adds a phantom straight segment that fixes really do project onto — it produced a false
# reacquisition candidate 2135 m along during the first analysis of this walk, and cost real time
# before the data was suspected. Excluding it is what makes the corpus mean anything.
#
# See ADR 0001 S5b, and issue #69. Remove this once the stray point is gone from the database.
TRAIL_LAST_GOOD_POSITION = {12: 138}


def trail_points(db: pathlib.Path, trail_id: int):
    limit = TRAIL_LAST_GOOD_POSITION.get(trail_id)
    clause = f"AND tw.position <= {limit}" if limit is not None else ""
    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        return conn.execute(
            f"""SELECT w.lat, w.lon
                  FROM trail_waypoint tw JOIN waypoint w ON w.id = tw.waypoint_id
                 WHERE tw.trail_id = ? {clause}
              ORDER BY tw.position""",
            (trail_id,),
        ).fetchall(), limit


def write_gpx(path: pathlib.Path, name: str, points) -> None:
    body = "\n".join(f'<trkpt lat="{lat}" lon="{lon}"></trkpt>' for lat, lon in points)
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="build-replay-corpus">\n'
        f"<trk><name>{name}</name><trkseg>\n{body}\n</trkseg></trk></gpx>\n"
    )


def read_log(path: pathlib.Path):
    """Parsed entries paired with their original lines, oldest first."""
    rows = []
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        try:
            rows.append((json.loads(line), line))
        except json.JSONDecodeError:
            # One unreadable line is a nuisance; refusing the file would lose a whole walk.
            continue
    rows.sort(key=lambda r: r[0]["ts"])
    return rows


def split_sessions(rows):
    """Split on TRAIL_STARTED/TRAIL_STOPPED, never on user markers.

    The matcher's state is per-follow, so replaying two sessions as one corrupts the ladder. The
    boundaries have to come from machine-written entries: the owner's IMPORTANT! markers sometimes
    *precede* the action they describe ("starting trail" pressed before starting), so they are
    intent, not timing.
    """
    bounds = [(d["ts"], d["trigger"]) for d, _ in rows if d.get("trigger") in ("TRAIL_STARTED", "TRAIL_STOPPED")]
    sessions, start = [], None
    for ts, trigger in bounds:
        if trigger == "TRAIL_STARTED":
            start = ts
        elif start is not None:
            sessions.append((start, ts))
            start = None
    if start is not None and rows:
        sessions.append((start, rows[-1][0]["ts"]))
    return sessions


def hhmmss(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--share", type=pathlib.Path, default=pathlib.Path.home() / "share")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--trail", type=int, action="append", default=None, help="trail id to export (repeatable)")
    args = parser.parse_args()

    db = args.share / "bold_explorer.db"
    if not db.exists():
        print(f"no database at {db}", file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)

    for trail_id in args.trail or [7, 12]:
        points, limit = trail_points(db, trail_id)
        if len(points) < 2:
            print(f"trail {trail_id}: not enough points, skipped", file=sys.stderr)
            continue
        path = args.out / f"trail_{trail_id}.gpx"
        write_gpx(path, f"trail {trail_id}", points)
        note = f" (positions <= {limit}; stray point excluded)" if limit is not None else ""
        print(f"{path.name}: {len(points)} points{note}")

    for log in sorted(args.share.glob("bold_explorer_audio_log_*.jsonl")):
        rows = read_log(log)
        sessions = split_sessions(rows)
        stem = log.stem.replace("bold_explorer_audio_log_", "walk_")
        for n, (start, end) in enumerate(sessions, 1):
            fixes = [line for d, line in rows if start <= d["ts"] <= end and d.get("kind") == "TRAIL_MATCH"]
            if not fixes:
                continue
            path = args.out / f"{stem}_session{n}.jsonl"
            path.write_text("\n".join(fixes) + "\n")
            print(f"{path.name}: {len(fixes)} fixes  {hhmmss(start)}-{hhmmss(end)}")

    print()
    print("replay with:")
    print('  ./gradlew :shared:runReplay --args="<trail.gpx> <session.jsonl> [--reverse] [--sweep]"')
    print()
    print("directions for the 2026-08-12 walks (from the TRAIL_STARTED text in each log):")
    print("  walk_20260812_063501_session1  trail 7,  --reverse")
    print("  walk_20260812_063501_session2  trail 12")
    print("  walk_20260812_063501_session3  trail 12, --reverse")
    print("  walk_20260812_101140_session1  trail 12")
    print("  walk_20260812_101140_session2  trail 12, --reverse")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
