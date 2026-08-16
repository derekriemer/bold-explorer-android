#!/usr/bin/env python3
"""Turn a field-log export into a scrubbed scenario fixture for :shared tests.

A scenario is one trail-follow session from a real walk: the trail the app was using, the raw GPS
fixes, and a note of what the app announced at the time. It exists because synthetic fixtures only
test what someone thought to imagine, and every interesting navigation failure so far was found in
field data rather than reasoned about.

## What it exports, and what it refuses to

The fixture contains **no coordinates**. Everything is converted to metres in a local east/north
frame, rotated by a per-scenario angle, and re-anchored on a synthetic origin that lives in the test
code. Every quantity the navigation core computes — distance, bearing, cross-track, along-track — is
relative, so this is exact: the replay behaves identically while absolute position and true heading
are gone.

Also stripped: trail and waypoint names, user marker text (free-form dictation, which can contain
anything), and absolute dates. Timestamps become milliseconds from the session start, preserving
every interval exactly.

## Why the trail comes from the log, not the database

Each logged fix carries the waypoint it was steering to, as `targetIndex` + `targetLat/targetLng`.
Collected over a session those reconstruct the followed trail exactly as the app then had it —
including any subsequent edit, deletion or re-record, none of which the database can tell you about
now. Reconstruction is only as complete as the indices actually targeted, so the tool reports the
span and refuses on gaps rather than quietly interpolating a straight line through a bend.

Usage:
    tools/build-scenario.py LOG.jsonl --session N --name switchbackReverse \\
        --out shared/src/commonTest/kotlin/.../scenarios/SwitchbackReverse.kt
    tools/build-scenario.py LOG.jsonl --list
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import math
import os
import re
import sys
import textwrap as _textwrap
from dataclasses import dataclass

# Metres per degree of latitude, and the reference latitude the fixtures are re-anchored at. These
# must match TrailFixtures.offsetFromOrigin on the Kotlin side or the inverse projection is wrong.
M_PER_DEG_LAT = 111_194.9
ANCHOR_LAT = 40.0
ANCHOR_LON = -105.0

TRAIL_START_TRIGGERS = {"TRAIL_STARTED", "TrailStarted", "TrailStartedReversed", "TrailStartedFromCollection"}
TRAIL_END_TRIGGERS = {"TRAIL_STOPPED", "TrailStopped", "TRAIL_COMPLETED", "TrailComplete"}

# Announcement triggers worth keeping in the baseline timeline. Per-waypoint chatter is dropped: it
# is one line per track point and says nothing a reader of the fixture needs.
INTERESTING_TRIGGERS = {
    "OFF_TRAIL_ALERT", "OffTrailAlert",
    "BACKTRACK_ALERT", "BacktrackAlert",
    "ORDINARY_GUIDANCE", "OrdinaryTrailGuidance",
    "TRAIL_COMPLETED", "TrailComplete",
    "TRAIL_STOPPED", "TrailStopped",
} | TRAIL_START_TRIGGERS


@dataclass
class Fix:
    t_ms: int
    east_m: float
    north_m: float
    accuracy_m: float | None
    speed_mps: float | None
    course_deg: float | None


@dataclass
class Session:
    index: int
    start_ms: int
    end_ms: int
    rows: list[dict]
    reversed_follow: bool
    end_reason: str


def load(path: str) -> list[dict]:
    rows = []
    with open(path) as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    rows.sort(key=lambda r: r.get("ts", 0))
    return rows


def announcement_text(row: dict) -> str:
    match = re.search(r'text="([^"]*)"', str(row.get("inputs", "")))
    return match.group(1) if match else ""


def split_sessions(rows: list[dict]) -> list[Session]:
    """Cut the log into follow sessions. A session runs from a trail-start to its stop or complete."""
    sessions: list[Session] = []
    current: dict | None = None
    for row in rows:
        trigger = str(row.get("trigger", ""))
        if trigger in TRAIL_START_TRIGGERS:
            if current is not None:  # a start with no stop — the previous session was cut short
                sessions.append(_close(current, len(sessions), row["ts"], "interrupted"))
            current = {
                "start": row["ts"],
                "rows": [],
                "reversed": "in reverse" in announcement_text(row).lower(),
            }
        if current is not None:
            current["rows"].append(row)
            if trigger in TRAIL_END_TRIGGERS:
                sessions.append(_close(current, len(sessions), row["ts"], trigger))
                current = None
    if current is not None:
        sessions.append(_close(current, len(sessions), current["rows"][-1]["ts"], "unterminated"))
    return sessions


def _close(current: dict, index: int, end_ms: int, reason: str) -> Session:
    return Session(
        index=index,
        start_ms=current["start"],
        end_ms=end_ms,
        rows=current["rows"],
        reversed_follow=current["reversed"],
        end_reason=reason,
    )


def reconstruct_trail(session: Session) -> tuple[list[tuple[float, float]], int, int]:
    """The followed trail, as (lat, lon) in follower order, from the targets the session steered to."""
    targets: dict[int, tuple[float, float]] = {}
    for row in session.rows:
        index, lat, lon = row.get("targetIndex"), row.get("targetLat"), row.get("targetLng")
        if index is None or lat is None or lon is None:
            continue
        targets.setdefault(int(index), (float(lat), float(lon)))
    if not targets:
        return [], 0, 0
    indices = sorted(targets)
    gaps = sum(1 for a, b in zip(indices, indices[1:]) if b - a > 1)
    if gaps:
        raise SystemExit(
            f"session {session.index}: {gaps} gap(s) in target indices {indices[0]}..{indices[-1]}. "
            "Interpolating across them would invent geometry the app never had; pick another session."
        )
    return [targets[i] for i in indices], indices[0], indices[-1]


def to_local(points: list[tuple[float, float]], lat0: float, lon0: float) -> list[tuple[float, float]]:
    """(lat, lon) -> (east_m, north_m) about the true origin, so real distances are preserved."""
    m_per_deg_lon = M_PER_DEG_LAT * math.cos(math.radians(lat0))
    return [((lon - lon0) * m_per_deg_lon, (lat - lat0) * M_PER_DEG_LAT) for lat, lon in points]


def rotate(points: list[tuple[float, float]], degrees: float) -> list[tuple[float, float]]:
    theta = math.radians(degrees)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    return [(e * cos_t - n * sin_t, e * sin_t + n * cos_t) for e, n in points]


def rotation_for(name: str) -> float:
    """A stable per-scenario angle. Deterministic so a rebuild reproduces the fixture byte for byte."""
    digest = hashlib.sha256(name.encode()).hexdigest()
    return int(digest[:8], 16) % 360


def fix_of(row: dict) -> dict | None:
    """One GPS fix from a log row, whichever schema wrote it, or None if the row carries no position.

    v1 puts the position at the top level on most row kinds. v2 moved it into `extra` and writes it
    on `TRAIL_MATCH` rows only, so a v2 announcement has no position of its own — which is why
    scenarios are built from the fix stream and announcements are only ever context.
    """
    if row.get("userLat") is not None and row.get("userLng") is not None:
        return {
            "lat": float(row["userLat"]),
            "lon": float(row["userLng"]),
            "acc": row.get("userAccuracy_m"),
            "speed": row.get("userSpeed_ms"),
            "course": row.get("userHeading"),
        }
    extra = row.get("extra") or {}
    if row.get("kind") == "TRAIL_MATCH" and extra.get("lat") is not None:
        return {
            "lat": float(extra["lat"]),
            "lon": float(extra["lon"]),
            "acc": extra.get("acc_m"),
            "speed": extra.get("speed_mps"),
            "course": extra.get("course_deg"),
        }
    return None


def collect_fixes(session: Session) -> list[dict]:
    seen: set[int] = set()
    out = []
    for row in session.rows:
        if fix_of(row) is None:
            continue
        ts = row.get("ts")
        if ts in seen:
            continue
        seen.add(ts)
        out.append(row)
    return out


def read_gpx(path: str) -> list[tuple[float, float]]:
    """Trail geometry from an exported GPX, in **recorded** order.

    Read at *generation* time and baked into the fixture as scrubbed metres. The fixture must stay
    self-contained: these tests run in CI and on machines that have never seen the corpus, so a
    runtime dependency on a file in someone's home directory would make them pass only here. It also
    keeps real geometry out of the repo, which is the same reason the fixes are transformed.
    """
    text = open(path).read()
    points = [(float(a), float(b)) for a, b in re.findall(r'<trkpt[^>]*lat="([-\d.]+)"[^>]*lon="([-\d.]+)"', text)]
    if not points:
        points = [(float(a), float(b)) for a, b in re.findall(r'<wpt[^>]*lat="([-\d.]+)"[^>]*lon="([-\d.]+)"', text)]
    return points


def baseline_timeline(session: Session) -> list[tuple[int, str, str]]:
    """What the app said during the session, for the fixture's header. Names scrubbed."""
    out = []
    for row in session.rows:
        if row.get("kind") != "TTS_ANNOUNCEMENT":
            continue
        trigger = str(row.get("trigger", ""))
        if trigger not in INTERESTING_TRIGGERS:
            continue
        text = announcement_text(row)
        # "Following <the trail's name> in reverse." — the name is the user's, and goes no further.
        text = re.sub(r"Following .*?( in reverse)?\.", lambda m: f"Following <trail>{m.group(1) or ''}.", text)
        # Which channel carried it. Two spellings, because the log's vocabulary changed: archived
        # logs write the live-region case as a bare "Suppressed: '<text>'", which reads as silence
        # and misled ADR 0001 for an afternoon. Newer ones say "Live region:". Only
        # "Suppressed (silence mode)" has ever meant nothing was heard.
        played = str(row.get("played", ""))
        if "silence mode" in played:
            channel = "[silenced] "
        elif played.startswith("Live region") or played.startswith("Suppressed"):
            channel = "[via live region] "
        elif played.startswith("Not spoken"):
            channel = "[screen reader only] "
        else:
            channel = ""
        out.append((row["ts"] - session.start_ms, trigger, channel + text))
    return out


def kotlin_fixture(
    name: str,
    package: str,
    session: Session,
    trail_m: list[tuple[float, float]],
    fixes: list[Fix],
    rotation_deg: float,
    index_span: tuple[int, int],
    description: str,
) -> str:
    def num(value: float | None) -> str:
        return "null" if value is None else f"{value:.2f}"

    timeline = baseline_timeline(session)
    wrapped = _textwrap.wrap(description, width=105)
    lines = [
        f"package {package}",
        "",
        "import com.boldexplorer.shared.navigation.TravelDirection",
        "",
        "/**",
    ] + [f" * {line}" for line in wrapped] + [
        " *",
        f" * Recorded on a real walk, session {session.index}, ending in `{session.end_reason}`.",
        f" * {len(fixes)} fixes over {(session.end_ms - session.start_ms) / 60000:.1f} minutes;"
        f" trail reconstructed from",
        f" * targets {index_span[0]}..{index_span[1]} with no gaps.",
        " *",
        " * **Scrubbed.** There are no coordinates here. Positions are metres in a local frame,"
        " rotated by",
        f" * {rotation_deg:.0f}° and re-anchored on the synthetic origin the other fixtures use, which"
        " preserves every",
        " * relative quantity exactly and destroys absolute position and heading. Names, marker text"
        " and",
        " * absolute dates are gone — including the walk's own, which this header used to print —"
        " and",
        " * timestamps are milliseconds from the session start. The corpus README, outside the repo,"
        " maps a",
        " * scenario back to the log it came from.",
        " *",
        " * ## What the build of the day announced",
        " *",
        " * Context, **not** an expectation: these came from the pre-redesign code, and where they were",
        " * wrong is exactly why the redesign exists. Assertions live in the test, derived from what the",
        " * walk is known to have been.",
        " *",
        " * ```",
    ]
    for offset_ms, trigger, text in timeline:
        lines.append(f" * {offset_ms // 1000:>5}s  {trigger}: {text}")
    lines += [
        " * ```",
        " *",
        " * Regenerate with `tools/build-scenario.py`.",
        " */",
        f"object {name} : WalkScenario {{",
        f'    override val label = "{name}"',
        f"    override val direction = TravelDirection."
        f"{'Reverse' if session.reversed_follow else 'Forward'}",
        "",
        "    /** Trail vertices in **recorded** order, as metres (east, north) from the origin. */",
        "    override val trailMetres =",
        "        listOf(",
    ]
    for east, north in trail_m:
        lines.append(f"            {east:.2f} to {north:.2f},")
    lines += [
        "        )",
        "",
        "    /** `tMs, east, north, accuracy, speed, course` per fix; nulls where the fix carried none. */",
        "    override val fixes =",
        "        listOf(",
    ]
    for fix in fixes:
        lines.append(
            f"            ScenarioFix({fix.t_ms}L, {fix.east_m:.2f}, {fix.north_m:.2f}, "
            f"{num(fix.accuracy_m)}, {num(fix.speed_mps)}, {num(fix.course_deg)}),"
        )
    lines += ["        )", "}", ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log")
    parser.add_argument("--list", action="store_true", help="show the sessions in the log and exit")
    parser.add_argument("--session", type=int, help="session index to export")
    parser.add_argument("--name", help="Kotlin object name for the fixture")
    parser.add_argument("--out", help="path to write the .kt fixture to")
    parser.add_argument("--package", default="com.boldexplorer.shared.navigation.scenario")
    parser.add_argument("--description", default="A recorded walk, replayed against the navigation core.")
    parser.add_argument("--rotate-deg", type=float, help="override the derived rotation")
    parser.add_argument(
        "--trail-gpx",
        help="take trail geometry from this GPX instead of the log's targets. Required for v2 logs "
        "(20260812 onwards), which record no targetIndex. Read at generation time and baked into "
        "the fixture as scrubbed metres, so the fixture stays self-contained and the GPX stays out "
        "of the repo.",
    )
    args = parser.parse_args()

    rows = load(args.log)
    sessions = split_sessions(rows)

    if args.list or args.session is None:
        print(f"{len(sessions)} sessions in {args.log}\n")
        for session in sessions:
            fixes = collect_fixes(session)
            alerts = sum(
                1
                for r in session.rows
                if r.get("kind") == "DETECTION_STATE" and str(r.get("played", "")).startswith(("fire", "FIRING"))
            )
            start = _dt.datetime.fromtimestamp(session.start_ms / 1000)
            print(
                f"  {session.index:3d}  {start:%Y-%m-%d %H:%M}  "
                f"{(session.end_ms - session.start_ms) / 60000:5.1f} min  "
                f"{len(fixes):4d} fixes  "
                f"{'reverse' if session.reversed_follow else 'forward'}  "
                f"ends {session.end_reason:12s}  detector firings {alerts}"
            )
        return

    if not (args.name and args.out):
        parser.error("--name and --out are required when exporting a session")

    session = next((s for s in sessions if s.index == args.session), None)
    if session is None:
        parser.error(f"no session {args.session}")

    if args.trail_gpx:
        # v2 logs carry no targetIndex, so the geometry cannot come from the log. The GPX is the
        # trail in recorded order — not follower order — so it is never reversed below.
        trail_ll = read_gpx(os.path.expanduser(args.trail_gpx))
        first_index, last_index = 0, len(trail_ll) - 1
        trail_from_gpx = True
    else:
        trail_ll, first_index, last_index = reconstruct_trail(session)
        trail_from_gpx = False
    if len(trail_ll) < 2:
        where = "the GPX" if trail_from_gpx else f"session {args.session}'s targets"
        parser.error(f"{where} yielded {len(trail_ll)} point(s); too few to be a trail")

    fix_rows = collect_fixes(session)
    if not fix_rows:
        parser.error(f"session {args.session} has no positioned fixes")

    # Project about the trail's own centroid, so the numbers stay small and the origin is not a
    # place. The rotation is what removes orientation; the translation alone would not.
    lat0 = sum(p[0] for p in trail_ll) / len(trail_ll)
    lon0 = sum(p[1] for p in trail_ll) / len(trail_ll)
    rotation_deg = args.rotate_deg if args.rotate_deg is not None else rotation_for(args.name)

    trail_m = rotate(to_local(trail_ll, lat0, lon0), rotation_deg)
    fix_m = rotate(to_local([(fix_of(r)["lat"], fix_of(r)["lon"]) for r in fix_rows], lat0, lon0), rotation_deg)

    # A reverse follow hands the follower a reversed waypoint list, so the reconstruction is in
    # travel order. TrailPolyline is always in recorded order, with direction carried separately.
    # A reverse follow hands the follower a reversed waypoint list, so a target-reconstructed trail
    # comes out in travel order and has to be flipped back. A GPX is already in recorded order.
    if session.reversed_follow and not trail_from_gpx:
        trail_m = list(reversed(trail_m))

    fixes = []
    for row, (east, north) in zip(fix_rows, fix_m):
        f = fix_of(row)
        fixes.append(
            Fix(
                t_ms=row["ts"] - session.start_ms,
                east_m=east,
                north_m=north,
                accuracy_m=f["acc"],
                speed_mps=f["speed"],
                # Course rotates with the frame — but *opposite* in sign to the coordinates.
                # `rotate()` turns the world counter-clockwise by theta; a bearing is measured
                # clockwise from north, so the same physical direction reads as `bearing - theta`
                # afterwards. Adding it instead left every fixture claiming a heading 2*theta away
                # from the direction its own positions move — 114 degrees in one of them — which
                # feeds relativeDeg and so the off-trail sustain path.
                course_deg=(float(f["course"]) - rotation_deg) % 360 if f["course"] is not None else None,
            )
        )

    text = kotlin_fixture(
        name=args.name,
        package=args.package,
        session=session,
        trail_m=trail_m,
        fixes=fixes,
        rotation_deg=rotation_deg,
        index_span=(first_index, last_index),
        description=args.description,
    )
    with open(args.out, "w") as handle:
        handle.write(text)
    print(
        f"wrote {args.out}: {len(trail_m)} trail vertices, {len(fixes)} fixes, "
        f"{'reverse' if session.reversed_follow else 'forward'}, rotated {rotation_deg:.0f}°"
    )


if __name__ == "__main__":
    sys.exit(main())
