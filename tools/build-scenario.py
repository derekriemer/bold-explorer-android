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


def collect_fixes(session: Session) -> list[dict]:
    seen: set[int] = set()
    out = []
    for row in session.rows:
        if row.get("userLat") is None or row.get("userLng") is None:
            continue
        ts = row.get("ts")
        if ts in seen:
            continue
        seen.add(ts)
        out.append(row)
    return out


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
        suppressed = "Suppressed" in str(row.get("played", ""))
        out.append((row["ts"] - session.start_ms, trigger, ("[suppressed] " if suppressed else "") + text))
    return out


def kotlin_fixture(
    name: str,
    package: str,
    source_label: str,
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
        f" * Recorded on a real walk ({source_label}), session {session.index}, ending in"
        f" `{session.end_reason}`.",
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
        " * absolute dates are gone; timestamps are milliseconds from the session start.",
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

    trail_ll, first_index, last_index = reconstruct_trail(session)
    if len(trail_ll) < 2:
        parser.error(f"session {args.session} targeted {len(trail_ll)} waypoint(s); too few to be a trail")

    fix_rows = collect_fixes(session)
    if not fix_rows:
        parser.error(f"session {args.session} has no positioned fixes")

    # Project about the trail's own centroid, so the numbers stay small and the origin is not a
    # place. The rotation is what removes orientation; the translation alone would not.
    lat0 = sum(p[0] for p in trail_ll) / len(trail_ll)
    lon0 = sum(p[1] for p in trail_ll) / len(trail_ll)
    rotation_deg = args.rotate_deg if args.rotate_deg is not None else rotation_for(args.name)

    trail_m = rotate(to_local(trail_ll, lat0, lon0), rotation_deg)
    fix_m = rotate(to_local([(float(r["userLat"]), float(r["userLng"])) for r in fix_rows], lat0, lon0), rotation_deg)

    # A reverse follow hands the follower a reversed waypoint list, so the reconstruction is in
    # travel order. TrailPolyline is always in recorded order, with direction carried separately.
    if session.reversed_follow:
        trail_m = list(reversed(trail_m))

    fixes = [
        Fix(
            t_ms=row["ts"] - session.start_ms,
            east_m=east,
            north_m=north,
            accuracy_m=row.get("userAccuracy_m"),
            speed_mps=row.get("userSpeed_ms"),
            # Course rotates with the frame, like every other bearing here.
            course_deg=(float(row["userHeading"]) + rotation_deg) % 360 if row.get("userHeading") is not None else None,
        )
        for row, (east, north) in zip(fix_rows, fix_m)
    ]

    source_label = f"{_dt.datetime.fromtimestamp(session.start_ms / 1000):%Y-%m-%d}"
    text = kotlin_fixture(
        name=args.name,
        package=args.package,
        source_label=source_label,
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
