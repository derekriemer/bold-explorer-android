# ADR 0001 — Continuous trail matching: navigation core redesign

- **Status:** Accepted; amended 2026-08-12 (Amendment 1 — vertex projections; S5a — backtrack; S5b —
  geometry vs annotations), 2026-08-14 (Amendment 2 — accuracy-aware backtrack noise floor) and
  2026-08-15 (Amendment 3 — off-trail under uncertainty)
- **Date:** 2026-08-09
- **Issues:** #23, #35, #55, #56, #69
- **Supersedes:** the per-trackpoint `TrailFollower` state machine
- **Informs:** #59 (trail graph), #60 (fork sonification) — see Forward compatibility
- **Origin:** planned 2026-08-07, revised by the owner 2026-08-08/09, accepted 2026-08-09

## Context

Bold Explorer's trail following asks one question per GPS fix: *which recorded point am I trying to
reach next?* `TrailFollowerState.Active` carries `currentIndex: Int`, mutated in exactly one place
(`TrailFollower.kt:250`), only ever `+1`, never rewinding. Every downstream consumer —
`TrailGuidance`, `TrailGuidanceCoordinator`, `NavigationTargetResolver`, `NavModeResolver`,
`GpsViewModel`, `GpsScreen` — reads `waypoints[currentIndex]` directly. There is no abstraction
between "index into a list" and the UI.

That representation is the shared root of four issues:

- **#23** — fixes are hard-dropped on accuracy, leaving distance/bearing frozen while heading-driven
  audio keeps moving. Fix quality and staleness are not explicit enough for downstream navigation to
  know whether its geometric state is trustworthy.
- **#35** — passing a recorded trackpoint generates guidance, so a densely recorded trail talks more
  than a sparse one describing the same physical path.
- **#55** — proximity, acceptance, off-trail, re-entry and completion thresholds are scattered across
  four files and partly share values. Accuracy-scaled gates use `max(floor, factor × accuracy)`,
  which *expands* the acceptance region as GPS degrades.
- **#56** — progress can only advance one index at a time, so skipped points, forward jumps, and
  trail re-entry are effectively impossible to recover from.

Two defects found while tracing, both confirmed in code:

1. **Completion has no code path of its own.** `TrailComplete` is emitted by `fireAdvance` when
   `currentIndex` is already the last index (`TrailFollower.kt:247-272`). So the projection branch —
   `t >= 0.9` on the *final* segment, gated only by `d <= thresholdM * 4` — completes the trail. A
   300 m final leg completes ~30 m early, matching the field-reported ~65 ft.
2. **Off-trail never consults cross-track.** `evaluateOffTrail` decides purely from
   `abs(relativeDeg) >= 60.0` against the bearing to the current target
   (`TrailGuidanceCoordinator.kt:169-217`). A user standing *on* the trail with a stale target behind
   them satisfies this on every fix, and it repeats every 45 s.

**Intended outcome:** progress becomes a continuous scalar position along a polyline, so the core
question becomes *where am I relative to this trail, how confident are we, what geometry lies ahead,
and does the user need to hear anything right now?* The purpose is not to perfectly tune guidance
before the iOS port — it is to avoid porting the per-trackpoint state machine and coupling the iOS
audio/UI layer to abstractions we already intend to delete.

## Decisions taken (owner, 2026-08-07; reverse travel added 2026-08-09)

- **Progress speech**: distance remaining + named POIs. `kind == "waypoint"` becomes the sole speech
  anchor on a trail; track points go silent. "Checkpoint N of M" is retired.
- **Uncertainty**: preserve the last confident match and dead-reckon *along-track* in the direction
  best agreeing with trusted course. Distinct uncertain earcon; off-trail alerts and completion
  suppressed. Never jump backward onto a spatially-near opposite switchback arm — prefer continuity
  and forward progress unless there is sustained *displacement* evidence of real backtracking.
- **Completion**: `radius = min(CEILING_M, max(FLOOR_M, SIGMA_FACTOR × accuracyM))`. Good GPS
  tightens below the fixed default; bad GPS clamps and never expands. Assert when
  `accuracyM <= HEDGE_ABOVE_M`, hedge above it.
- **Pacing**: field walk gates the switchover, not the groundwork. S0–S4 land now; S5+ waits on
  shadow-mode evidence.
- **Reverse travel** (owner, 2026-08-09 — closes the former Open item): `TrailPolyline` is **always**
  in recorded order and is never reversed. The tracker carries
  `travelDirection: Forward | Reverse`, chosen once at follow-start (or at mid-trail pickup) and
  **fixed for the session**. `alongTrackM` decreases under `Reverse`.

  In scope: walking a trail end-to-beginning, and starting mid-trail and walking back toward the
  start. Explicitly **out of scope**: detecting a mid-walk turnaround and re-deriving direction from
  motion. Direction is a session parameter, not an inference — which makes this a much smaller
  feature than the first draft implied.

  Why not reverse the point list, which is what `GpsViewModel.kt:888-896` does today: under
  continuous matching the search window is `confirmedAlongM ± budget` and therefore **symmetric**, so
  it already contains candidates in both directions and reverse travel needs *no* matching changes at
  all. Only a small set of consumers is direction-aware — prediction's forward bias, distance
  remaining, which endpoint completes, tangent/chord bearing, POI ordering, and S8's lookahead — so a
  flag plus direction-aware accessors is strictly less work than reversal, which under this design
  would also mean rebuilding `cumulativeM` on a polyline declared session-immutable. The deciding
  argument is S4: two walks of the same trail in opposite directions must yield comparable
  `alongTrackM` for field logs to be overlaid, and reversal makes the coordinate session-relative.
  #59/#60 inherit an edge with an intrinsic direction, which is what a graph needs.

  Accepted behaviour if the user physically turns around mid-session: `alongTrackM` moves against the
  chosen direction, distance-remaining grows, completion does not fire, and the existing backtrack
  detector may say "wrong way." For a blind user who may have turned around *unintentionally* that is
  useful rather than broken; an intentional reversal is handled by re-selecting direction. Do not add
  turnaround inference to suppress it.

## Architecture

Continuity is **primary**, geometry is the refinement — not the reverse. Cross-track alone separates
none of the hard cases: an out-and-back's two passes sit 0 m apart, as do a lollipop's stem passes
and a loop's closure point. Only temporal continuity is reliable across all of them.

Course is a *partial* discriminator and the distinction matters (it was stated too broadly in the
first draft). On a retrace — out-and-back, lollipop stem — the two candidate along-coordinates have
**opposite** tangents, so course matches exactly one and separates them cleanly. On same-direction
parallel geometry — switchback arms A and C, or two nearby parallel trails — courses agree and course
tells you nothing. So course fails exactly where switchbacks live and succeeds over a large class
besides. That is why it is excluded from the *window* (below) but deliberately retained as logged
evidence, and is a candidate corroboration signal during `Unconfirmed`.

New types in `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/`:

- **`LocalFrame`** — an equirectangular east/north tangent frame anchored at an **explicit origin**.
  `toLocal(LatLng): Vec2` / `toLatLng(Vec2): LatLng`, with longitude unwrapped relative to the origin
  at conversion so the anti-meridian stops being a branch. `TrailPolyline` takes an optional frame
  and defaults to one anchored at its own centroid (centroid, not point 0: it halves the worst-case
  scale error) — but the origin is a parameter, not an invariant, so several polylines can be made to
  share one frame. See "Forward compatibility" below for why. Not a `GeoMath` replacement — see S0.
- **`TrailPolyline`** — wraps ordered points; converts them **once** at construction into parallel
  `xs`/`ys` `DoubleArray`s (not 10k `Vec2` objects — same rationale as `cumulativeM` being a
  `DoubleArray`, and it keeps the global scan cache-friendly). Precomputes `cumulativeM: DoubleArray`
  and `totalLengthM` **in-frame**, so `alongTrackM` and `cumulativeM` are the same coordinate system;
  computing lengths by haversine while projecting in-frame is the seam that exists today and must not
  be rebuilt one layer up. Exposes `project(point, window): TrailPosition?`,
  `positionAt(alongM): LatLng`, `chordBearingAt(alongM, baselineM)`,
  `sagittaOver(alongM, lookaheadM)`. Public API takes and returns `LatLng`; local coordinates never
  escape the polyline. Immutable for a session — a geometry change means restart.
- **`TrailPosition(segmentIndex, fraction, alongTrackM, crossTrackM, snapped)`** — `alongTrackM` is
  the canonical progress scalar. Segment indices are an implementation detail.
- **`TrailMatch(position, state, unmatchedCount, chosen, bestRejected)`** — retains the winning and
  best-rejected candidate plus a disposition string for the log. State is
  `Matched | Uncertain | Lost | Unconfirmed` — four, not two, because the reacquisition ladder below
  reads all four to decide how wide to search and whether the result may be trusted. These are
  *internal* tracker states; the public surface stays as it is until S5 (`Ambiguous`/`Lost` as
  distinct **public** states remain deferred).
- **`NavigationPolicy`** — one object, every threshold, plus a single
  `clamp(base, factor, accuracy, hardCap)` helper. One object, not two.

### Angle conventions — read before writing any `atan2`

Compass and unit-circle angles are *not* the same convention and the difference is silent: both
produce plausible numbers, so a mix-up shows up as guidance that is wrong by a reflection, not as a
crash. State it once here and hold it everywhere.

| | Zero at | Direction |
|---|---|---|
| GPS / compass bearing | **North** | **clockwise** (90° = East) |
| Unit circle / standard `atan2(y, x)` | East (+x) | counter-clockwise |

In the ENU frame (`x` = east, `y` = north) the conversion *is* an argument swap:

```
bearingDeg(v)  = atan2(v.x, v.y) normalized to [0, 360)   // x FIRST — this is the conversion
unitVec(bearing) = Vec2(x = sin(bearing), y = cos(bearing))
```

`atan2(v.y, v.x)` is the maths angle and is **wrong** for a bearing. There is no grid convergence to
correct — x is east and y is north everywhere in the frame — so `atan2(x, y)` yields a true bearing
directly.

**Signed quantities must match the repo's existing convention**: `deltaAngle`
(`GeoMath.kt:41-46`) documents *positive = target is to the right of heading*, i.e. positive =
clockwise. The naive planar cross product is left-positive and therefore has the opposite sign:

```
cross(ab, ap) = ab.x*ap.y - ab.y*ap.x     // POSITIVE = ap is LEFT of ab
crossTrackRightM = (ab.y*ap.x - ab.x*ap.y) / |ab|   // negated: positive = user is RIGHT of trail
```

Sanity check to encode as a test: a north-pointing segment `ab = (0, 1)` with the user due east
`ap = (1, 0)` is a user on the **right**, and must yield a **positive** `crossTrackRightM`.

The same sign rule governs S8's signed net turn (`atan2(cross, dot)` — negate for clockwise-positive)
so that a right-hand bend and a right-of-trail offset agree in sign with `deltaAngle`. Once every
angle is produced through these two helpers the convention question stops recurring; the risk is
entirely in hand-rolled `atan2` calls at individual call sites.

**The matching rule** (this is the mechanism, not a safety check bolted on):

```
budget    = maxSpeedMps × min(uncertainSec, T_CAP) + K × accuracyM
window    = confirmedAlongM ± budget           // centred on CONFIRMED, never on predicted
candidate = polyline.project(fix, window)      // scan only segments intersecting window
            ties broken toward predictedAlongM // ranking only, overridable by corroboration
Matched     if candidate != null && candidate.crossTrackM <= clamp(...)
              → confirmedAlongM = candidate.alongTrackM; predictedAlongM = confirmedAlongM
else          Uncertain(count++)  — confirmedAlongM FROZEN; only predictedAlongM and
              the budget move, so the window widens around the last known-good position
Uncertain → Lost once the reckoning horizon expires (bounded in BOTH metres and seconds)
Lost      → global scan over the whole polyline, no window → Unconfirmed(candidate)
Unconfirmed → Matched only after D metres of corroborating displacement
            → back to Lost if corroboration fails, and no further global scan
              until RESCAN_COOLDOWN_S has elapsed
```

This single rule rejects switchback teleports (large Δalong, ~1 s elapsed), rejects the out-and-back
and loop-closure double-matches that no *purely geometric* test can, accepts a genuine
leave-and-reenter-ahead after minutes of walking, and handles slow walking for free.

**Initial acquisition is the one case with no prior**, and it is where a loop bites. At follow-start
there is no `confirmedAlongM` to centre a window on, so the first match is a global scan — and on a
loop (or any trail whose ends are within GPS accuracy of each other) the fix projects onto **two**
candidates with ~0 cross-track: `alongTrackM ≈ 0` and `alongTrackM ≈ totalLengthM`. Cross-track
cannot choose, continuity does not exist yet, and choosing the far end means the trail completes
before the user has walked anywhere.

Two independent guards, because either alone is insufficient:

1. **Tie-break by `travelDirection`.** `Forward` prefers the candidate nearer `0`, `Reverse` prefers
   the one nearer `totalLengthM`. The session's declared direction is exactly the information the
   geometry lacks, and it is already available (see Decisions). This resolves the intended case.
2. **A travel guard on completion** (S2), because the tie-break is a preference and can still be
   wrong — a user genuinely starting mid-loop, or standing 3 m from the end of a straight trail.
   Completion may not fire until the session has accumulated a minimum of *confirmed* along-track
   travel. This one is not loop-specific and protects the general case.

Mid-walk loop closure needs neither guard: by then `confirmedAlongM` exists and the window is
centred on it, so jumping from `along ≈ 10` to `along ≈ totalLengthM − 10` is already rejected.

**Course does not constrain the window — and is not therefore worthless.** It is excluded from the
window for one specific reason: on a switchback, arms A and C are parallel and same-direction, so
course passes for exactly the confusion most needing rejection, and a signal that endorses the worst
case must not be allowed to widen or bias the search. That is an argument about the *window*, not
about the evidence.

During `Unconfirmed` the calculus inverts. There the question is no longer "constrain the search" but
"does sustained evidence support this candidate over its rival" — and course is discriminating across
the whole retrace class, where it is close to decisive. So S4 logs course/vector evidence from the
start (see its field list) without letting it constrain anything, so that the corroboration rule can
be designed against real data rather than assumption. Promoting it from logged to load-bearing is an
explicit post-S4 decision, gated on field logs showing it separates the cases it should and abstains
on the cases it cannot.

The window is load-bearing twice — it is the switchback safety mechanism *and* the performance
mechanism. GPX import applies no decimation (`GpxParser.kt`, `CollectionsViewModel.applyGpxImport`),
so trails can carry 10k+ points; a full per-fix scan is not viable. Full scan happens only on
re-acquire.

**The reckoning horizon is a hard bound, not a soft preference.** The widening window is the
*preferred* mechanism precisely because continuity is the only signal that separates switchback arms
and out-and-back passes — but a window that widens without limit silently becomes a full scan while
still claiming continuity it no longer has. Past the horizon the tracker must say plainly *"I no
longer have enough confidence in prior progress to constrain candidates by it"* and drop to `Lost`.
Global reacquisition at that point is correct and its cost is negligible: `TrailPolyline` is already
in memory, and a 10k-segment scan is `segmentFraction` + one haversine per segment — sub-millisecond,
and by construction rare. The expensive thing was never the scan; it is trusting the scan's result.
Hence `Unconfirmed`: a global match constrains nothing and must earn `Matched` through D metres of
corroborating displacement.

`RESCAN_COOLDOWN_S` exists because the failure mode is *frequency*, not cost. A user genuinely off
the trail fails corroboration every time; without a cooldown the tracker global-scans on every fix
at 1 Hz forever. The cooldown is a state transition, not a counter reset — `unmatchedCount` must not
be the thing gating the rescan, or a single stray `Matched` re-arms it.

**Scope note — the rescan is in-memory, not a DB query.** `TrailPolyline` holds the followed trail's
points, ordered, with `cumulativeM` precomputed; every ladder rung above searches that object.
`NavPointsRepository.trailPointsInBbox` (`NavPointsRepositoryImpl.kt:55`) is a *different* mechanism
answering a *different* question — "which trail in this collection am I near," collection-scoped with
no `trail_id` parameter, feeding `NearbyTrailResolver` for mid-trail pickup. Do not wire the rescan
to it. Note also that its `TrailPointRow` projection omits `kind`, so it cannot distinguish named
waypoints from track points; any future speech-facing consumer needs `kind` added to the SELECT
(`NavPoints.sq:34,45`).

A lat/lng spatial index (geohash bucket column) was considered and rejected for this rescan. It
narrows candidates but does not disambiguate them — both arms of a switchback share a cell, and an
out-and-back's two passes are the same rows — so it optimizes a cost we do not have while leaving the
ambiguity that actually motivates `Unconfirmed` untouched. It would also add a schema migration,
a backfill, an insert-path invariant, and a cell-boundary edge case that the fix-centred
`computeBbox` does not have.

**Dead reckoning is prediction, never progress commitment.** The tracker holds two scalars and they
are not interchangeable:

- **`confirmedAlongM`** — the last geometry-corroborated position. Advances *only* when a fix
  actually projects onto the polyline within gate. This is the single value every consumer reads:
  distance remaining, trail progress, completion, telemetry, speech.
- **`predictedAlongM`** — 1-D extrapolation (`+= speed × dt`, forward-biased), reset to
  `confirmedAlongM` on every confirmed match. Never read by any consumer, never persisted as
  progress.

Prediction **ranks**, geometry **constrains**, displacement **confirms**. Concretely: the search
window stays centred on `confirmedAlongM` with a radius that widens as uncertainty accumulates —
centring on the prediction instead would let prediction error walk the window off the user's true
position and exclude it. `predictedAlongM` is consulted only to break ties when a scan returns
several plausible candidates (switchback arms, out-and-back passes), and it is a *weak* signal that
corroborating displacement overrides. Past the reckoning horizon it is worthless as a tiebreaker and
stops being consulted at all.

This is what protects the user who stops to look at rocks, pet a dog, or walk down to the lake for
lunch. Reckoning-as-progress advances them past a fork they never reached, and geometry then
"reacquires" onto the wrong branch — with a corrupted prior that makes the wrong branch look
corroborated. Under prediction-only, `confirmedAlongM` simply stays where geometry last saw them,
and the widening window still contains the truth when they wander back.

Invariant worth enforcing in the type system: **`TrailPosition` is only ever produced by
`project()`.** Nothing constructs one from reckoning. If a `TrailPosition` exists, geometry made it.

A switchback apex is smooth in along-track coordinates, so rounding the corner re-acquires on the far
arm without a jump — the widening window reaches it whether or not prediction pointed there.

Accepted cost: a long no-fix stretch (canyon, tunnel, dense canopy) freezes distance-remaining rather
than coasting it. That is the intended trade — hedged and frozen beats confident and wrong, it is the
honest reading of #23, and the horizon bounds how long it can persist.

### Forward compatibility (#59 trail network, #60 fork sonification)

Both are explicitly post-iOS and neither is being built here. This section exists because two of the
decisions above happen to be the load-bearing ones for them, and one small API shape is cheap now and
expensive later.

**What already transfers.** #60's hardest constraints — *"do not switch after one noisy heading
sample"*, *"any automatic switch requires sustained position and vector agreement"*, *"logs explain
both accepted and rejected switches"* — are the `Unconfirmed → corroborating displacement → Matched`
ladder plus `chosen`/`bestRejected`/`disposition`, unchanged. A fork is structurally the case where
the scan returns plausible candidates on *different polylines* rather than different parts of one;
the decision rule is the same and only the candidate set widens. Likewise, prediction-not-progress is
what keeps `confirmedAlongM` parked at a junction until geometry disambiguates, instead of committing
to a branch the user never took — the fork case is the general form of the "stopped to pet a dog"
case that motivated it. And `predictionErrorM` from S4 tells us whether the prediction prior is
trustworthy enough to influence branch choice *before* anything depends on it.

Sign conventions become load-bearing rather than incidental: #60 keeps direction on pitch and
stereo-pan, and pan is a signed left/right quantity. The `crossTrackRightM` negation and the
`deltaAngle`-agreement tests are the prerequisite for panning a branch earcon to the correct ear,
where a sign error is a wrong-direction cue rather than a cosmetic bug.

**What does not transfer.** `TrailPolyline` is single-trail and session-immutable; #60 requires that
"the fork model must not depend only on the currently followed trail," so the graph layer sits above
it holding several. `alongTrackM` as a single scalar does not survive a graph — position becomes
`(edge, alongM)`. Candidate *trails* (as opposed to candidate positions on one trail) come from
`trailPointsInBbox`, which is collection-scoped and returns `trail_id`: the right tool for fork
candidate generation even though it is the wrong tool for the rescan. Its missing `kind` column
applies there too.

**The one affordance taken now:** `LocalFrame`'s origin is an explicit parameter and `TrailPolyline`
accepts an optional frame. Scalars and true bearings compare correctly across differently-anchored
frames, so *independent* projection onto two branches works either way — but anything mixing
*positions* from two polylines does not, and computing where two recordings intersect is #59's entire
job. Three lines of API now; a constructor change across every test fixture later.

**Bearing on the Open item below:** a graph is a second, independent argument for resolving reverse
travel by keeping the polyline in recorded order and carrying `travelDirection` separately. An edge
has an intrinsic direction and traversal direction is a separate fact; reversing the point list
cannot express that, and does not generalize.

## Migration sequence

Each step lands independently with `make test-shared` green.

**S0 — `LocalFrame`, `TrailPolyline`, `TrailPosition`, `project()`.** Pure geometry, zero call sites.
Establish the repo's first shared test fixture helper (`densify(path, spacingM)`) here.

This step deliberately **stops reusing** `segmentFraction` and `distanceToSegmentMeters`
(`GeoMath.kt:55-83`), which the first draft planned to lean on. The reason is that they are already
inconsistent with each other in a way the new code would inherit: `segmentFraction` works in a flat
frame (lon scaled by `cosLat` at the segment midpoint, then a dot product), while
`distanceToSegmentMeters` takes that flat result, converts the projected point *back* to lat/lng, and
measures it with haversine — planar projection, spherical distance, re-derived per segment. Fine at
today's usage; not something to build `alongTrackM` on when the density-equivalence test below has to
distinguish real bugs from coordinate-system seams.

Everything the plan needs downstream is a vector primitive that is a one-liner in-frame and a special
case in lat/lng: `project` (dot), **signed** `crossTrackM` (perp dot), `chordBearingAt` (atan2),
`sagittaOver` (cross magnitude), and S8's signed net turn. The signed cross-track is a genuine new
capability rather than a refactor — `distanceToSegmentMeters` returns unsigned today, so "you are
12 m off trail" cannot become "the trail is 12 m to your right" without it.

API surface to land in this step, stated concretely because the frame-origin parameter is the piece
that is cheap now and costly to retrofit (see Forward compatibility):

```kotlin
data class Vec2(val x: Double, val y: Double)          // x = east, y = north, metres

class LocalFrame(val origin: LatLng) {                  // origin explicit, never implicit
    fun toLocal(p: LatLng): Vec2
    fun toLatLng(v: Vec2): LatLng
    companion object {
        fun centredOn(points: List<LatLng>): LocalFrame // centroid anchor — the default
    }
}

fun bearingDeg(v: Vec2): Double                         // atan2(v.x, v.y) — see Angle conventions
fun unitVec(bearingDeg: Double): Vec2

class TrailPolyline(
    points: List<LatLng>,
    frame: LocalFrame = LocalFrame.centredOn(points),   // shareable across polylines
)
```

**Scope limit — this is not a `GeoMath` rewrite.** `GeoMath`, `computeBbox`, `NearbyTrailResolver`,
and every existing caller stay exactly as they are. The frame lives inside `TrailPolyline`.

**Where haversine still belongs.** User-facing *point-to-point* distances (straight-line distance to
a waypoint or target) may keep using `haversineDistanceMeters` — the frame is an internal geometry
convenience, not a mandate. But *along-trail* distance remaining must come from the in-frame
`cumulativeM`, because it has to stay self-consistent with `alongTrackM`; sourcing one from haversine
and the other from the frame reintroduces precisely the seam this step removes. The difference is
~0.1% at trail scale, far below both GPS accuracy and the rounding in any announcement.

**S0b — retrofit `NearbyTrailResolver.nearestForTrail`** (`NearbyTrailResolver.kt:56-82`) to return a
`TrailPosition`. It already loops every segment and discards which one won; its own KDoc calls the
result "indicative only". Proves the type against a real consumer, no behavior change.

**S1 — `NavigationPolicy`.** Move constants by *reference only*, values unchanged: `TrailFollower`'s
eight companion constants, `CollectionExplorer.kt:333-337`, `NavModeResolver.EXTEND_*`,
`NearbyTrailResolver.NEAR_TRAIL_*`. Add the bounded clamp. This alone satisfies #55's stated pre-iOS
scope ("just centralize the actual thresholds in shared KMP code").

**S2 — endpoint completion policy.** Explicit check before the projection branch; the projection
branch refuses to fire on the final segment. Uses the `min()` radius above. Existing
`TrailFollowerTest` completion cases will need editing — that is the intended behavior change.

Completion is `(radius OR passed) AND travelled`, and all three parts are load-bearing:

- **`passed`** — also fire when `alongTrackM` reaches the direction's terminal endpoint
  (`totalLengthM` under `Forward`, `0` under `Reverse`). Radius alone is skippable: at 8 m/s with
  1 Hz fixes a user goes from 5 m short to 3 m past the endpoint without ever being inside any sane
  completion radius, and the trail silently never completes. See the high-speed traversal fixture.
- **`travelled`** — completion may not fire until the session has accumulated at least
  `MIN_TRAVEL_FOR_COMPLETION_M` of *confirmed* along-track travel. Without this, `passed` makes the
  loop-start ambiguity **worse than radius-only did**: on a loop the initial global scan can pick
  `alongTrackM ≈ totalLengthM`, which satisfies `passed` on the very first fix and completes the
  trail before the user has moved. The direction tie-break in Architecture reduces how often that
  choice is made; this guard is what makes it harmless when it happens. It also covers the
  non-loop case of starting a few metres from a trail's end.

  **The guard must scale to the trail, not be a flat constant** — otherwise it reintroduces exactly
  the bug class this ADR exists to remove. A trail shorter than `MIN_TRAVEL_FOR_COMPLETION_M` could
  never accumulate enough confirmed travel to satisfy it and would never complete: a 20 m connector
  under a 30 m guard latches forever. Use
  `min(MIN_TRAVEL_FOR_COMPLETION_M, totalLengthM × TRAVEL_FRACTION)` so the guard degrades on short
  trails instead of blocking them. Fixture: a trail shorter than the constant must still complete
  exactly once, at the end.
- **`radius`** — the `min()` policy from Decisions, unchanged.

Accumulate `travelled` from confirmed movement only. Reckoned or `Unconfirmed` movement must not
count toward it, for the same reason prediction never becomes progress.

**S3 — off-trail becomes cross-track-necessary, angle-corroborating.**

The first draft said `evaluateOffTrail` requires *both* `crossTrackM > clamp(...)` **and** the
existing angle condition. That is wrong, and it fails in the direction that matters most: someone
walking a wrong parallel path 30 m off the trail has a small bearing delta to the target, so the
angle condition never fires and they are never told. Requiring both trades one class of false
negative for another. Angle earned a veto only because it was historically the *sole* signal; once
cross-track exists, angle should be demoted to evidence, not promoted to gatekeeper.

The rule instead:

1. **`crossTrackM > clamp(...)` is necessary.** This alone fixes the defect in Context — a user
   standing on the line can never trip off-trail regardless of what any angle says.
2. **Sustain, not veto.** Alert requires N consecutive qualifying fixes. Corroborating evidence
   shortens N rather than gating it: `N_FAST` when diverging, `N_SLOW` when parallel or converging.
   Parallel-and-far therefore still alerts — later, which is correct, since it is the ambiguous case.
3. **Primary corroborator is `crossTrackRateMps`**, not the bearing angle. Signed cross-track rate is
   density-independent and needs no target — and the target is precisely what made the original
   defect possible, since a *stale* target is what put `relativeDeg` over 60° for a user standing
   still on the trail. Divergence evidence should not be routed through the thing that was broken.
4. **The existing angle condition is retained as additional evidence** — it can select `N_FAST`, it
   can never suppress. Cheap to keep, and S4's logs will show whether it adds anything over
   cross-track rate.
5. **Hard ceiling.** Beyond `OFF_TRAIL_FAR_M`, alert at `N_FAST` regardless of convergence. Being
   very far off while walking back is still worth one utterance.

Convergence may delay an alert but must never cancel one while cross-track stays over gate. Add
disposition strings in the established style (`bail:on_line_xt_4m`, `fire:xt_31m_diverging`,
`hold:xt_31m_converging_2of5`) matching `TrailGuidanceCoordinator.kt:190-207`. Note this is no longer
a strict false-alert reduction as the first draft claimed: it removes the on-line false positives
**and adds** true positives for parallel-path cases that fire today never. Existing
`TrailGuidanceCoordinator` tests asserting silence in the parallel case will need editing — that is
the intended behaviour change, and it should be verified in the field walk rather than assumed.

**S4 — shadow matcher + reacquisition ladder.** `ProgressTracker` runs beside the existing follower
on the same fixes, consumed by nobody, logged under a new `AudioLogEntry.Kind.TRAIL_MATCH`.

The ladder is the substance of this step, and it ships in shadow mode specifically so its constants
can be tuned against field data before anything depends on them:

1. **`Matched`** — windowed projection around `prior.alongTrackM`. Normal operation.
2. **`Uncertain`** — no candidate inside the window, or cross-track over gate. `confirmedAlongM`
   freezes; only `predictedAlongM` advances (`+= speed × dt`, forward-biased) and only `budget`
   grows, so the window widens progressively around the last known-good position rather than
   sliding off it. Off-trail alerts and completion stay suppressed here (already a decision at
   line 48). Consumers keep reading the frozen `confirmedAlongM`, hedged.
3. **`Lost`** — reached when reckoning exceeds `RECKONING_HORIZON_M` **or** `RECKONING_HORIZON_S`,
   whichever comes first. Both bounds are required: a stationary user with bad GPS burns seconds
   without metres, a fast-moving one burns metres without seconds. On entry the tracker discards
   `confirmedAlongM` as a constraint, stops consulting `predictedAlongM` as a tiebreaker (a
   prediction that old carries no information), and emits a `reckoning_expired` disposition.

   **"Discards as a constraint" means it stops bounding the search, not that the value is cleared.**
   `confirmedAlongM` retains its last geometry-corroborated value through `Lost` and `Unconfirmed`,
   and consumers keep reading it (hedged, per the uncertainty decision). Only its role in computing
   the search window ends. The dog fixture depends on precisely this: after four minutes stopped,
   `confirmedAlongM` must still read 200 m at resume. Clearing it on entry to `Lost` would satisfy a
   plausible reading of "discards" and silently break that fixture's premise, so the distinction is
   stated rather than implied.
4. **`Unconfirmed`** — result of the global scan. Carries a candidate but constrains nothing
   downstream; drives no guidance, no completion, no off-trail. Promotes to `Matched` after
   `CORROBORATION_M` of displacement consistent with it.
5. **Failed corroboration** returns to `Lost`, and no further global scan runs until
   `RESCAN_COOLDOWN_S` has elapsed. Implement the cooldown as a timestamp on the state, not as a
   counter — see the architecture note on why `unmatchedCount` must not gate it.

New constants (`RECKONING_HORIZON_M`, `RECKONING_HORIZON_S`, `CORROBORATION_M`, `RESCAN_COOLDOWN_S`)
land in `NavigationPolicy` here, not in S1 — S1 is a by-reference move of existing values only.

Log fields: along/cross/segment/window/budget/chosen/rejected/disposition, plus `state`,
`uncertainSec`, `scanKind` (`windowed | global`), and — kept separate, since conflating them is the
bug this step exists to avoid — `confirmedAlongM` and `predictedAlongM`. On every reacquisition also
log `predictionErrorM` (`predictedAlongM − confirmedAlongM` at the moment geometry returns): that
single field is what tells us from field data whether prediction deserves to stay a tiebreaker at
all, or whether it should be dropped. Together these let a log be replayed to answer "how often did
it go global, was it right, and would the prediction have misled us."

**Course/vector evidence — logged, never constraining.** Recorded so the `Unconfirmed` corroboration
rule can be designed against data (see Architecture). The design point is that course alone answers
nothing on replay; what answers the question is course agreement logged for **both** the winning and
the best-rejected candidate, so we can ask retroactively "would course have picked the right one?"

- `speedMps`, `courseDeg`, `courseValid` — GPS course over ground is noise below roughly 1 m/s, so
  validity must be logged explicitly or the analysis will read jitter as evidence. That gate is also
  why course can never become the *sole* discriminator: it vanishes exactly when someone stops.
- `tangentDeg` and `courseAgreementDeg` (`deltaAngle(course, tangent)`, right-positive per Angle
  conventions) for the **chosen** candidate.
- `rejectedTangentDeg` and `rejectedCourseAgreementDeg` for `bestRejected`. **The load-bearing pair.**
  A large split between the two means course would have discriminated; a small split means it would
  have abstained. That distribution is the entire basis for the later promote-or-drop decision.
- `crossTrackRateMps` — signed `d(crossTrackM)/dt`. Divergence evidence that needs no target and is
  density-independent, unlike a bearing against a possibly-stale target. Feeds S3 as well as this step.

**Ship and walk trails.** Add `"v": 2` and
nest new fields under one key while touching the schema — and note `parseLine`
(`AudioEventLog.kt:125-138`) silently drops `extra` on read-back, so the Debug screen shows less than
the file after a restart. Verify the ~3600 lines/hour append rate is acceptable before shipping.

**S4c — vertex projections become a distinct answer.** Found by the field walk, and a prerequisite
for S5 rather than a follow-up: S5 makes `TrailMatch` the source of truth for guidance, and a
`Matched` that means "somewhere in a wedge behind an apex" must not become the thing the app steers
by. See **Amendment 1**.

*— field walk gate —*

**S5 — switch the source of truth.** `Active` gains `match: TrailMatch?` as an **additive field**;
`currentIndex` stays and keeps being advanced by the old path. Do **not** derive `currentIndex` from
`TrailPosition`: that makes the target the far vertex of the current segment, so `distanceM` becomes
"always 0–8 m" and corrupts `distToTarget_m` / `trailProgress_pct` / `alongTrackDist_m`
(`GpsViewModel.kt:1483-1502`) in the exact increment where telemetry is the point. It would also let
`currentIndex` decrease for the first time, tripping `evaluateBacktrack` with a false "wrong way"
alert. Shadow-mode duplication is the cost of attributable field results. `desiredTrailCourseDeg`
moves to a chord/lookahead bearing — a single segment bearing at 2 m spacing is nearly random and
makes off-trail alerts density-dependent.

**Guidance half implemented 2026-08-15.** The chord bearing had already landed — but it centred
itself on an **unwindowed** `polyline.project()`, so it was a third consumer of the defect S5a
names, in the one place where being wrong steers the user directly. Measured by replaying the corpus
against the shipped rule:

```
                                    real GPS          injected degradation
  walk1_session1_trail7_reverse       0 / 106            —
  walk1_session2_trail12_forward      0 / 984            —
  walk1_session3_trail12_reverse     15 / 213  = 7.0%    0 / 56
  walk2_session1_trail12_forward      0 / 120            —
  walk2_session2_trail12_reverse      0 /  22           18 / 83  = 21.7%
                                                        (worst cases 162°)
```

Read the split, not the total. **The defect is real on clean GPS**: 7.0% of `Matched` fixes on
walk 1 session 3, at ~3.5 m reported accuracy, had the spoken direction 45° or more from the truth.
The larger-looking 21.7% is entirely inside a span where accuracy was *injected* at 25 m by the debug
degradation feature (#62, canyon preset), which the owner's marker at 16:07:13 declares — "fixes from
here are deliberately degraded; readings are not real". Quoting the combined figure would have
credited simulated conditions with finding a defect that clean GPS shows on its own.

`desiredTrailCourseDeg` and `evaluateOffTrail` now both read the match and never re-derive position.
Three things this forced that the decision above did not anticipate:

- **The coordinator needs the trail in recorded order**, adopted once per session by `startFollow`
  and shared with the matcher. It had been building its own polyline from the follower's waypoint
  list, which is *reversed in place* for a reverse follow — a second, session-relative along-track
  coordinate, and exactly what "always in recorded order" exists to prevent.
- **Direction is not only a tie-break.** The chord is measured over the trail *ahead of the user*,
  which is toward increasing along-track only under `Forward`; cross-track's "right of travel" flips
  for the same reason. Both are now derived from the declared direction rather than from whichever
  way the point list happened to run.
- **Off-trail under `Uncertain`.** See the amendment below.

Not done, and still the substance of S5: distance, checkpoint counting and completion all still come
from `currentIndex`.

**Amendment 3 — off-trail is not suppressed by uncertainty alone (2026-08-15).** The Decisions
section says "distinct uncertain earcon; off-trail alerts and completion suppressed". Implementing
the suppression *without* the earcon, which does not exist, would replace an alert with silence at
the moment it matters most — and `Uncertain` most often means "the best candidate is past the match
gate", which is the off-trail condition itself rather than ignorance of it. Owner-confirmed. When the
uncertain earcon lands, revisit: the original pairing is still the better end state.

**Corrected in review, 2026-08-15.** The first implementation read `chosen.crossTrackM` under
`Matched`/`Uncertain` only and bailed under `Lost`/`Unconfirmed`, reasoning that a global candidate
might be a different part of the trail. That silenced the alert permanently for the person most
off-trail: leave the trail and keep walking, and the reckoning horizon puts the tracker in `Lost`
within 90 s, where the rescan-and-fail cycle keeps it. The previous angle-based rule kept firing
every 45 s indefinitely, so this was a regression in exactly the case the alert exists for.

The candidate is now read in **every** state, because what it means changes with the state and both
readings are right for their state:

| state | the candidate is | why it is safe to act on |
| --- | --- | --- |
| `Matched` / `Uncertain` | windowed, near where the user was | the switchback case: the near arm is not the user's arm |
| `Lost` / `Unconfirmed` | the nearest point on the whole trail | a *lower bound* on the distance off — over gate means over gate |

Only a fix carrying no candidate at all — a `Lost` fix inside the rescan cooldown — bails, and it
bails because there is genuinely nothing to measure rather than because the state is distrusted.

**Known gap, found while doing this and deliberately left open:** a follow started while the user is
already past the match gate never acquires, so the tracker sits in `Lost` and off-trail stays quiet.
Same family as the `acquire()` question below, and it wants the same field walk.

**S5a — `evaluateBacktrack` must read the windowed match, not project for itself.**
`TrailGuidanceCoordinator.evaluateBacktrack` computes along-track with its own **unwindowed**
`polyline.project()` and fires when that value regresses by `BACKTRACK_NOISE_FLOOR_M` (2 m) on
`BACKTRACK_CONSECUTIVE_THRESHOLD` (3) consecutive fixes. That is a second consumer of unwindowed
projection, distinct from the `currentIndex` hazard noted in S5, and unlike that one it is
mis-firing **today**.

Field evidence, 2026-08-12, walk 2 at 16:07. The owner was ~120 m along a trail that doubles back,
leaving him physically 11–18 m from the trail's own **start**.

**Read the conditions honestly** (corrected 2026-08-15, when the full export was recovered). The 25 m
accuracy here was **injected**, not observed: the debug degradation feature (#62) was switched on at
16:07:14, one second after the owner's marker saying "fixes from here are deliberately degraded;
readings are not real". That does not weaken the mechanism — an unwindowed projection snapping to the
trail head is a property of the geometry, and 25 m accuracy is an ordinary canyon reading, which is
what the preset simulates — but it does mean this table shows a hazard *reproduced on demand* rather
than one caught in the wild. The same defect does appear on clean GPS: see the 7.0% figure under S5's
guidance half, from walk 1 session 3 at ~3.5 m accuracy.

```
              live (unwindowed)     shadow (windowed)
  16:07:46         113.6 m            113.6 m   window 59..169
  16:07:48           0.0 m  <--       112.3 m   window 59..169
  16:07:50           0.0 m  <--       114.5 m   window 57..167
  16:07:52         118.3 m            118.3 m   window 59..169
  16:07:56           0.0 m  <--       113.3 m   window 78..183
```

The unwindowed projection snapped to the trail head, read as a 113 m regression, counted to three and
announced "you may be going the wrong way" at 16:07:54. The windowed matcher, given the identical
fixes, never left ~113 m — 0 was not in its window. This is the switchback teleport the window exists
to prevent, observed on real geometry, and the clearest demonstration in this document that the
redesign is load-bearing rather than tidier.

The owner heard it. Worth stating explicitly, because the log's own wording says otherwise and
briefly fooled this document: the entry reads `Suppressed: 'You may be going the wrong way.'`, and
all 38 announcements in the session read the same way. `Suppressed` there is
`OutputDisposition.LIVE_REGION_ONLY` (`OutputRouter.kt:108`) — the app skipped its *own* TTS because
the text went to a Compose live region for TalkBack to speak, which is what it deliberately does
while in the foreground so it does not talk over the screen reader. Only
`Suppressed (silence mode)` means nothing was heard.

Note what it is *not*: two other "wrong way" alerts on the same day had different causes — one was a
genuine backtrack correctly reported, and one was an artifact of the stray waypoint described in S5b,
where fixes projected onto a phantom 1 km segment. Three alerts, three mechanisms. Do not assume a
single fix addresses all of them.

**Decision.** `evaluateBacktrack` consumes `TrailMatch.confirmedAlongM` rather than projecting
independently, and so inherits the window, the gate and the ladder's states. While it is being
touched, two logging defects that made this incident far harder to diagnose than it should have been:

- `BacktrackEvaluation.prevDistanceToTargetM` always equals `distanceToTargetM`, because
  `prevDistToTargetM` is reassigned before the evaluation is constructed. Every logged line in both
  walks shows the two as identical.
- The logged line (`GpsViewModel.kt:1373-1380`) records `distToTarget` — the input to the rule this
  detector **deliberately abandoned** — and omits `alongTrackM` / `prevAlongTrackM`, the values the
  decision is actually made on, even though `BacktrackEvaluation` already carries them. Diagnosing
  the incident above required re-projecting the raw fixes offline against the trail geometry, which
  is only possible because S4b logs raw positions.

**Implemented 2026-08-14.** Three things the decision above did not say, found while doing it:

- **Regression has a sign.** `alongTrackM` is always in recorded order, so under `Reverse` correct
  progress counts *down*. Reading a rise as regression would have announced wrong way for an entire
  reverse follow — a regression this step would have *introduced*, since the old unwindowed
  projection ran against the follower's already-reversed waypoint list and so happened to agree.
- **Only a fresh, contiguous `Matched` confirmation is evidence.** A value frozen under
  `Uncertain`/`Lost` is not movement, and the fix where geometry returns after an absence — the one
  carrying `predictionErrorM` — re-baselines instead of comparing, because a rejoin elsewhere is not
  a reversal.
- **The matcher stops being optional.** It ran *after* the follower on each fix, and the Debug switch
  gated the matching as well as the logging. Both had to change: the match must exist before the
  detectors run, and a switch that silently disables a navigation alert is not a logging switch.

See **Amendment 2** for the noise floor, which the corpus forced on the way.

**S5b — separate trail geometry from trail annotations.** Lands with S5 because it needs
`TrailPolyline.project`, and is needed by S6, which cannot announce a named waypoint as *passed*
without knowing where along the trail it sits.

The invariant that is currently missing:

> A trail's geometry is its **track points**, in recorded order. Everything else attached to it is an
> **annotation** with a projected position.

`trail_waypoint.position` presently does two jobs at once — polyline vertex order, and "which
checkpoint is this". A point attached *after* recording has no legitimate vertex position, so
appending one fabricates geometry. This is not a validation gap; the data model invites the error.

Observed, not hypothesised: waypoint `dos` on trail 12 was created 78 minutes after that trail's last
track point and appended at position 139, 1031 m from position 138. The app consequently believed the
trail was 2523 m long and ended 50 m from its own start; it is really 1492 m and ends 1060 m away.
Following it to completion would have routed the user down a kilometre-long straight line across
whatever is actually there. Nothing flagged it, and it silently corrupted offline replay of a field
walk — a global scan produced a candidate 2135 m along, on the phantom segment, which cost real
analysis time before the data was suspected. 16 named points across 7 trails sit in this position
today; the ones recorded *during* a walk are harmless as vertices only by luck, because they happen
to lie on the path.

**Decision.** An annotation stores **lat/lon as the truth and along-track as a derived value**. The
dormant `auto_waypoint(trail_id, name, segment_index, offset_m, lat, lon, created_at)` table already
has exactly this shape and is the home for it — generalised from the bend anchors it was reserved
for in Deferred. Storing along-track as the truth instead would silently move every annotation
whenever the trail geometry was edited or re-recorded; keeping the real position means a geometry
change is a re-projection, and loses nothing.

**Attach-time guard.** A point that projects absurdly far from the trail is refused or flagged, never
silently placed — the check from **#69**, moved from a post-hoc smoke alarm onto the path that
creates the problem. Migration of the existing 16 points needs the same rule, and `dos` is precisely
the case that must *not* be quietly projected: a kilometre off the trail is not an annotation, it is
a mistake, and the honest outcome is to say so.

Out of scope: automatic repair of existing bad data. Whether a stray point was an error or a
deliberate extension is not knowable from the data, so it is a question for the human.

**S6 — stop firing `WaypointReached` per trackpoint.** Replace with `NamedWaypointPassed` /
`TrailComplete` / `MatchLost` / `MatchReacquired`. **Critical:** `resetThrottle` has exactly one
production caller — `GpsViewModel.kt:1207`, inside the `WaypointReached` handler. It re-arms both
grace windows and zeroes both consecutive counters (`TrailGuidanceCoordinator.kt:118-128`). Killing
the event without re-homing that call means grace arms once at follow-start and never again — a
silent regression in off-trail and backtrack detection.

**S7 — delete `currentIndex`.** Migrate `NavigationTargetResolver` to endpoint/POI targets, retire
the "Checkpoint N of M" utterances (`GpsViewModel.kt:958,1243,1268`,
`TrailGuidanceCoordinator.kt:156`), migrate the telemetry field set.

**S8 — bend detection and earcon cadence** (separate track, after S4 data exists). Use **signed** net
turn (telescopes to `deltaAngle(end, start)`, density-invariant) plus **sagitta** as the primary
detector — summing `|Δbearing|` per vertex grows without bound with density and is a trap. Needs two
lengths, not one: a smoothing baseline `B` (~10–20 m) and a lookahead `L` derived from a *time*
budget (`clamp(warningSeconds × speed, Lmin, Lmax)`), since lead time is what the user experiences.
Anchor each bend to the along-track coordinate of its curvature maximum with an "already announced"
set, or a hairpin re-announces on every fix while the window straddles it. Do **not** share a constant
between the bend window and the matching window — they serve opposite purposes at a switchback.

## Files

Primary (`shared/src/commonMain/kotlin/com/boldexplorer/shared/`): new `geo/LocalFrame.kt` (frame +
`Vec2` + `bearingDeg`/`unitVec`, sited beside `GeoMath.kt` as a coordinate primitive — adding a file
there is not the same as rewriting `GeoMath`), `navigation/TrailPolyline.kt`,
`TrailPosition.kt`, `TrailMatch.kt`, `ProgressTracker.kt`, `NavigationPolicy.kt`; modified
`navigation/TrailFollower.kt`, `TrailGuidance.kt`, `TrailGuidanceCoordinator.kt`,
`NearbyTrailResolver.kt`, `NavModeResolver.kt`, `CollectionExplorer.kt`, `NavigationTargetResolver.kt`.

Android (`app/src/main/kotlin/com/boldexplorer/`): `ui/gps/GpsViewModel.kt` (telemetry, event
handling, announcement builders), `audio/AudioLogEntry.kt` + `audio/AudioEventLog.kt` (new `Kind`,
schema version), `audio/AudioCueScheduler.kt` in `:shared` (S8 cadence only).

## Verification

- `make test-shared` after every step — JVM, no device.
- **`LocalFrame` pinning** (S0) — the test that *justifies* the flat approximation rather than
  assuming it:
  - In-frame distance agrees with `haversineDistanceMeters` within tolerance at realistic trail
    extents. Parameterize over extent (1 km / 10 km / 50 km) and latitude (0°, 40°, 60°, 70°) and
    assert the error stays well under GPS accuracy — the point is to know where it breaks, not to
    discover it in the field. Record the measured error in the test name or message.
  - Round-trip `toLocal → toLatLng` is identity within tolerance, including for a trail spanning the
    anti-meridian — the case the `crossing`/`noCrossing` query split exists to handle today and that
    longitude unwrapping should make disappear.
  - Frame origin at the trail centroid beats point 0 on worst-case error for a long north-south
    trail. Cheap to assert, and it pins the choice against a well-meaning future simplification.
  - Degenerate inputs: a single-point trail, a zero-length segment, and a near-polar latitude
    (`EPS_COS_LAT_POLE` has precedent at `GeoMath.kt:11`).
- **Angle conventions** (S0) — one test per row of the table in Architecture, because these fail
  silently by reflection rather than loudly:
  - `bearingDeg` of the four cardinal unit vectors returns 0 / 90 / 180 / 270, N/E/S/W. Catches the
    `atan2` argument swap.
  - `bearingDeg` and `unitVec` round-trip.
  - The sanity check from Architecture as a literal test: north-pointing segment, user due east,
    `crossTrackRightM` **positive**.
  - Signed cross-track and `deltaAngle` (`GeoMath.kt:41-46`) agree in sign for the same geometry —
    a right-hand offset and a right-hand bearing delta must not disagree.
  - A right-hand bend yields a positive signed net turn (S8), same convention as the above.
- **Density equivalence** (new pattern, no precedent in the repo): the same physical path at 2 m /
  10 m / 30 m vertex spacing must produce equal `alongTrackM` / `crossTrackM` within tolerance, and
  from S5 equal guidance decisions. This is the executable form of the #35 requirement.
- Synthetic fixtures per the plan's section 5: straight dense, straight sparse, gradual bend, sharp
  bend, 3-point skip, 30-point skip, leave-and-reenter-ahead, switchback, parallel segments, loop,
  reverse traversal, final segments of very different lengths, degraded/stale GPS, **and the
  high-speed traversal below** (absent from the first draft's list).
- **High-speed traversal** — a *characterization* test: it documents where the machinery stops
  working rather than asserting that it works. Run the same physical path at 1.4 m/s (walking),
  8 m/s (bicycle) and 25 m/s (vehicle), each at 1 Hz and 5 Hz fix rates.

  It does not take a car to break this. `budget = maxSpeedMps × min(elapsedSec, T_CAP) + K × accuracy`
  with `maxSpeedMps` tuned for walking (~2.5 m/s) puts a **cyclist** outside the window on every fix:
  permanent `Uncertain` → `Lost` → global scan → cooldown, forever. The naive repair — raise the
  constant to 30 m/s — is worse, because window size *is* the switchback safety mechanism, and a 30 m/s
  constant with a 10 s cap admits candidates 300 m away for a walker who has moved four metres.

  So the fixture exists to force the right fix, which is that **`maxSpeedMps` must derive from
  observed speed** (a recent-speed EMA plus margin) rather than being a global constant. Walking then
  keeps a tight window, faster travel widens it only while actually moving fast, and safety degrades
  only in the regime where switchback footpaths are not the concern anyway.

  Note the two axes push opposite ways and must be tested separately: higher **speed** hurts (more
  metres between fixes), higher **fix rate** helps (fewer metres between fixes, so a 5 Hz vehicle can
  behave better than a 1 Hz walker). Do not conflate them.

  Assert explicitly: (a) at 1.4 m/s the window stays tight enough that the switchback fixture still
  rejects; (b) at 8 m/s tracking survives without entering `Lost`; (c) at 25 m/s the test records
  whatever happens without claiming support. If (c) is unsupportable, say so in the disposition and
  in the docs rather than leaving it to be discovered outdoors.

  Two related gaps this fixture surfaces, both worth fixing regardless of whether fast travel is ever
  supported: **completion must also fire on `alongTrackM` passing `totalLengthM`, not on radius
  alone** — at 8 m/s with 1 Hz fixes a user can jump from 5 m short of the endpoint to 3 m past it
  without ever being inside any sane completion radius (S2) — and `RECKONING_HORIZON_M` expires in
  seconds at speed, so the metres bound and the seconds bound trade places (S4).
- **Loop start** — the case the `passed` completion rule would otherwise break:
  - Start a follow standing at the start/end point of a closed loop. Assert completion does **not**
    fire on the first fix, nor at any point before `MIN_TRAVEL_FOR_COMPLETION_M` is accumulated.
  - Under `Forward`, initial acquisition resolves to `alongTrackM ≈ 0`, not `≈ totalLengthM`.
  - Under `Reverse` from the same physical position, it resolves the other way — the tie-break must
    follow declared direction, not a hardcoded preference for zero.
  - Walk the loop fully and assert completion fires exactly once, at the end.
  - Same assertions for a *non*-loop trail whose start and end happen to be within GPS accuracy of
    each other, and for a user starting 3 m from a straight trail's end — the travel guard, not the
    tie-break, is what has to carry these.
  - Mid-walk loop closure: passing near the start point at `alongTrackM ≈ totalLengthM − 10` must not
    snap to `≈ 0`. This should pass on continuity alone, with no loop-specific code.
- **Reverse traversal** — direction as a session parameter, not an inference:
  - The same physical walk recorded `Forward` and followed `Reverse` produces `alongTrackM` values
    that are comparable between the two logs (`reverse ≈ totalLengthM − forward`). This is the
    property list-reversal would destroy and the reason the flag exists — assert it directly.
  - Mid-trail pickup under `Reverse` acquires correctly and counts down toward `0`.
  - Distance remaining, completion endpoint, tangent/chord bearing, and POI ordering all flip;
    matching and window logic do **not** — assert the matcher is byte-identical in both directions.
  - A user who turns around mid-session sees `alongTrackM` move against the declared direction and
    completion not fire. Assert the documented behaviour rather than suppressing it; this is the
    explicitly accepted degradation, and a test pins it so nobody "fixes" it into turnaround
    inference later.
- Completion regression: a 300 m final leg must not complete 30 m out; assert the announcement hedges
  above the accuracy threshold.
- Off-trail regression: a user standing on the line with a stale target behind them must produce a
  `bail:on_line_*` disposition, not `FIRING`.
- **Reacquisition ladder** (S4):
  - Horizon expires on *distance* with time to spare, and on *time* with distance to spare — two
    separate cases, both must reach `Lost`.
  - A global scan result is never `Matched` on the fix that produced it; assert `Unconfirmed` and
    assert it drives no guidance.
  - Switchback: force `Lost` while standing on arm C, let the global scan pick arm A, and assert
    corroborating displacement rejects it rather than promoting it.
  - Cooldown: a user walking away from the trail for five minutes must produce at most
    `elapsed / RESCAN_COOLDOWN_S` global scans — the regression test for scanning every fix. Include
    a case where a single spurious `Matched` lands mid-cooldown and assert it does not re-arm.
- **Prediction never becomes progress** (S4) — the invariant with the most ways to regress:
  - `confirmedAlongM` is byte-for-byte unchanged across an entire `Uncertain` run, however long.
    Assert on the value, not on a state label.
  - **The dog fixture**: walk to 200 m along a trail with a fork at 260 m, stop for four minutes with
    noisy near-zero-speed fixes, resume. `confirmedAlongM` must still read 200 m at resume and the
    reacquisition must land on the near side of the fork — never past it.
  - **The lake fixture**: leave the trail at 200 m, walk 150 m off-trail and back, rejoin at 205 m.
    Assert the tracker does not report ~350 m at any point, and that rejoin confirms near 205 m.
  - Prediction is a tiebreaker, not a constraint: construct a scan where the candidate nearest
    `predictedAlongM` is the *wrong* arm and assert corroborating displacement still overrides it.
  - Past the horizon, `predictedAlongM` is not consulted — assert two scans differing only in
    prediction produce identical `Lost`-state results.
  - No consumer reads `predictedAlongM`. Worth a structural test, not just a behavioural one.
- Field: walk trails on the S4 shadow build, export the JSONL, confirm every match decision carries
  window, budget, chosen, best-rejected, and disposition. Tune constants only against that data.
  **Done, 2026-08-12** — two walks, 2112 shadow-matched fixes. The gate is satisfied. Outcomes:
  `MATCH_GATE_CAP_M` lowered 60 → 40 m on swept evidence; Amendment 1 (vertex projections) and S5a
  (backtrack) both found in the data rather than reasoned about. Replay the logs with
  `./gradlew :shared:runReplay --args="trail.gpx walk.jsonl [--reverse] [--sweep]"`; run under
  default tuning it reproduces the live shadow run exactly, fix for fix, which is the check that the
  corpus is trustworthy before any constant is changed against it.
- Caution learned the hard way: two apparent findings from the first walk — a false reacquisition
  candidate that corroboration rejected, and a wrong `NEARBY_POINT` announcement — were both
  artifacts of one bad waypoint in the trail data (see S5b), not behaviour of the matcher. Check the
  trail geometry before concluding anything about the algorithm from a field log.

## Deferred (file as issues)

Multi-hypothesis/HMM map matching; junctions and branch identity (**#60**, depends on **#59**'s trail
network — see Forward compatibility above for what this plan already gives it and what it still
needs); multi-trail matching; the dormant
`auto_waypoint(trail_id, segment_index, offset_m)` table as the persistent home for bend anchors;
`Ambiguous`/`Lost` as distinct **public** states (they exist internally from S4; exposing them to the
UI/audio layer is the deferred part); a lat/lng spatial-index column, should cross-collection or
whole-device trail search ever need candidate generation that `collection_id` scoping cannot provide;
final tuning of bend thresholds, earcon cadence, and switchback disambiguation constants.

## Rejected: adopting a third-party geometry library

Asked during review, since #59/#60 will need substantially more geometry than haversine and vector
math. Decision: **grow `shared/geo` as a first-class internal package; do not take a dependency.**

- **KMP eliminates the mature options.** JTS, GeoTools and S2 are JVM-only; `:shared` must build for
  `jvm`, `androidTarget`, `iosArm64`, `iosSimulatorArm64` and `iosX64`. What survives that filter is
  largely single-maintainer projects, which is a larger risk than a few hundred lines we own and test.
- **`LocalFrame` shrinks the problem to the point where a library stops paying.** In a flat ENU frame
  the geometry that sounds hard — polyline intersection, sagitta, signed cross-track, turn angle,
  Douglas-Peucker simplification — is plain 2-D vector algebra. The genuinely error-prone part,
  geodesy, stays confined to `LocalFrame`'s two conversions plus haversine.
- **The structure to hold is a two-layer rule, not a dependency.** `geo/` holds spherical primitives
  (`LatLng`, `haversineDistanceMeters`, `initialBearingDeg`), the bridge (`LocalFrame`, `Vec2`), and
  planar vector ops. Navigation works in-frame and never mixes layers. Land a package-level KDoc
  stating this invariant at S0 — it is what stops #59/#60 re-deriving coordinate handling.

Revisit only if trail search across thousands of trails needs a real spatial index (an R-tree is the
one piece genuinely worth not writing). Geodesic-grade calculation (Vincenty/Karney) is not a
motivation here: GPS accuracy is 3–30 m, far above where the flat approximation's error appears.

## Amendment 1 — vertex projections are a distinct answer (2026-08-12)

Accepted 2026-08-12, after the S4 field walk. Amends **Architecture** and adds **S4c** to the
migration sequence. Nothing in the original decisions is reversed.

### What the walk found

Replaying the walk of 2026-08-12 (1886 fixes, `ProgressTracker` in shadow mode), the tracker reported
`Matched` across **36 consecutive fixes carrying the byte-identical `alongTrackM` value**
(`53.64335742617432`, segment 5) while the raw position moved 20–35 m between fixes. `travelledM`
was frozen at 413 m throughout. Across the walk, 8.8% of all `Matched` fixes shared an exact
along-track value with another fix.

This is not a defect in the projection maths. It is the correct answer to the question being asked,
and the question is wrong.

### Why it happens

At a vertex where the trail changes direction, both adjacent segments lead *away* from the corner.
Every position in the exterior wedge — the region on the **outside** of the turn — therefore has the
vertex itself as its nearest point on the polyline.

Two properties of that wedge decide when this bites, and a second walk on 2026-08-12 measured both.
They are not what the first draft of this amendment assumed.

- **You only enter the wedge from off-trail.** It lies outside the turn, so walking the trail never
  puts you in it. A clean pass over two bends of 64° and 82° (GPS accuracy 3.3 m, 99 fixes, all
  `Matched`) produced **zero** pinned fixes at either bend. A degraded pass over the same ground
  (25 m reported accuracy) produced six, with a 32 m run. The hazard is not rounding a bend; it is
  being — or appearing to be — off-trail near one. Positional error is what puts you there, which is
  why this shows up exactly when the fix is least trustworthy.
- **The wedge's width on the ground grows with distance from the vertex**, as roughly
  `distance × turn angle`. At 30 m out, even a 21° bend spans some 11 m — ample for a jittering fix
  to land in repeatedly. The degraded pass pinned at vertices of 21° and 64°, not only at the sharp
  one, and the first walk's 36-fix run was at an 82° vertex rather than a true switchback.

So the risk scales with **turn angle × distance**, and switchbacks are the extreme rather than the
category. Shallow bends pin too, once positional error is large enough — which makes an
accuracy-scaled accept radius the right shape of answer, and makes this matter on ordinary trails
rather than only on mountain switchbacks. A genuine 170°+ apex remains unmeasured in the field.

Consequences, all already true of `TrailPosition` and all currently invisible to its consumers:

- **`alongTrackM` is degenerate.** An entire region of ground maps to one scalar. It cannot advance
  while the user walks through that region, and it cannot distinguish "at the apex" from "50 m past
  it". Progress does not merely lag; it is undefined.
- **`crossTrackM` changes meaning.** For an interior projection it is perpendicular offset from the
  path. At a vertex clamp it is *radial distance to the corner* — the `TrailPosition` KDoc already
  says so.

  Note carefully what is and is not broken by this. For **off-trail detection** the vertex-clamped
  magnitude is exactly right: "how far am I from the nearest point of the trail" is the question
  off-trail is asking, and the answer does not care whether that point is a vertex.
  `TrailGuidanceCoordinator` takes `abs()` of it and is correct as it stands. The confusion arises
  only where the same number is used to *trust an along-track position* — which is what the match
  gate does. There, "50 m beyond an apex" and "50 m to the side of a straight stretch" are scored
  identically, and only one of them tells you where you are.
- **The sign of `crossTrackM` is an artifact.** "Right of travel" is undefined in the wedge; the sign
  reports which side of one arbitrarily-chosen adjacent segment the point fell on. Latent rather than
  live: nothing outside `TrailGuidanceCoordinator` reads `OffTrailEvaluation`, and no announcement
  derives a side from it today. It becomes a real bug the moment one does.
- **`candidates()` cannot see the arms.** It returns one position per local minimum of distance. When
  the apex is the nearest point, the two arms are not distinct candidates, so the direction tie-break
  never gets the chance to choose between them. The switchback safety mechanism is bypassed, not
  overruled.

### Decision

**A projection is one of three kinds, and `TrailPosition` must say which.**

1. **Interior** — `0 < fraction < 1`. `alongTrackM` is well-defined; `crossTrackM` is signed
   perpendicular offset. Everything the ADR says today applies unchanged.
2. **Vertex-clamped** — clamped at an interior vertex. `alongTrackM` is degenerate and `crossTrackM`
   is unsigned radial distance.
3. **Endpoint-clamped** — clamped at the first or last vertex. Also degenerate, but *meaningful*:
   "before the start" and "past the end" are real states the app already wants to talk about. It must
   not be lumped in with (2); a trail's ends are not apexes.

Detecting (2) versus (3) is structural, not a threshold: a clamp is an interior-vertex clamp when the
vertex has a segment on both sides.

**Policy: a vertex-clamped candidate may confirm progress only within GPS accuracy of the vertex.**

Inside that radius, "you are at the apex" is true and useful — the degeneracy is smaller than the
measurement error, so no better answer exists. Beyond it, the tracker does not know where along the
trail the user is, and the ladder already has the honest word for that: `Uncertain`. Progress freezes
(it was frozen anyway — the difference is that the tracker now *says so*), the window widens, and
reacquisition can do its job.

Scaling the radius by accuracy rather than fixing it is deliberate and follows the existing
`widenWithAccuracy` pattern: the question is whether the degeneracy is distinguishable from noise,
which is a question about the fix, not about the trail.

**This is not the gate cap.** `MATCH_GATE_CAP_M` (lowered 60 m → 40 m on the same evidence) decides
how far off-trail a fix may be and still match; it applies everywhere. This decides that in one
particular place the distance being measured is not the distance being tested. Tightening the cap
filtered out the far end of the wedge — vertex-pinned fixes fell 45 → 30 and the longest pinned run
fell from 192 m to 37 m of walking — but the fixes *near* the apex still receive a confident wrong
answer. Filtering is not fixing; both changes are needed and neither substitutes for the other.

### Consequences

- `TrailPosition` grows a kind. Its `crossTrackM` KDoc already documents the vertex case in prose;
  this makes it something a consumer can branch on rather than something a reader must remember.
- One new constant, of the same untuned character as the rest of S4 — but unlike the original
  thirteen it can be swept against a recorded walk before it ships, using the replay harness.
- No change to off-trail detection, which is correct as it stands (see above). S4c must not "fix"
  it into using a perpendicular-only distance, which would make walking into an apex wedge stop
  raising off-trail at exactly the moment it should.
- The sign of a vertex-clamped `crossTrackM` remains meaningless. Anything later added that speaks a
  side must branch on the projection kind.
- Expected behaviour change: walking a trail normally is unaffected, because the wedge is never
  entered from on-trail — measured, not assumed. What changes is that drifting off-trail near a bend
  now goes `Uncertain` instead of silently claiming a frozen position. That is the intended trade — hedged and frozen beats confident
  and wrong, which is the same principle that demoted dead reckoning from progress to prediction.

### Verification

- A bend fixture where the walker leaves the trail into the wedge: assert the tracker does not
  report `Matched` with a frozen `alongTrackM` while `travelledM` fails to advance. Use a *moderate*
  bend, not a switchback — the field data pinned at 21° and 64°, and a fixture built only from an
  extreme apex would pass while the common case regressed.
- A clean walk around the same bend must be unaffected, since on-trail travel never enters the wedge.
- Endpoint clamps keep their current behaviour — a walk that starts 10 m before the trail head must
  still acquire.
- `TrailReplay.vertexPinnedFixes` on the 2026-08-12 corpus should fall substantially from 30. That
  number is a regression check on real data, not a target to optimise against.

## Amendment 2 — the backtrack noise floor scales with accuracy (2026-08-14)

Accepted 2026-08-14, while implementing **S5a**. Amends S5a only; nothing else is reversed.

### What replaying the corpus found

S5a was written from the 16:07:46–56 sequence, where an unwindowed projection snapped to the trail
head. Re-running the corpus against the *implemented* S5a rule confirms that sequence is fixed — and
shows a second false positive eleven seconds earlier that windowing does not touch:

```
  16:07:32   along=103.4 m   acc=25 m   spd=0.9 m/s   n=1
  16:07:33   along=115.9 m   acc=25 m   spd=0.9 m/s   n=2
  16:07:35   along=122.6 m   acc=25 m   spd=0.8 m/s   n=3  → "you may be going the wrong way"
```

Every one of those is `Matched` on a windowed scan. The user was standing still. At 25 m reported
accuracy the *confirmed* along-track wanders 4–12 m between fixes, and three consecutive wanders in
the same direction is an ordinary outcome of noise, not evidence of travel.

**Provenance, corrected 2026-08-15** when the full export was recovered: this window is inside the
span where accuracy was **injected** at 25 m by the debug degradation feature (#62, canyon preset),
switched on at 16:07:14. So the one false positive this amendment removes was produced under
simulated conditions, not observed in the wild — and across the whole corpus it is the *only* alert
the change removes. Two consequences worth stating rather than burying:

- The reasoning still holds on its own terms. A flat 2 m floor is a walking pace at 1 Hz, so it
  cannot survive any regime where the position noise exceeds that, simulated or not; and 25 m is an
  ordinary canyon reading, which is precisely what the preset exists to reproduce.
- But `BACKTRACK_NOISE_ACCURACY_FACTOR` is tuned against a **degradation model**, not against
  measured GPS. Nothing in the corpus tests it at genuinely-observed poor accuracy, because no walk
  yet recorded has any. Treat 0.5 as provisional until a walk produces real degraded fixes, and do
  not quote the sweep as evidence that it is *right* — only that it changes nothing else.

`BACKTRACK_NOISE_FLOOR_M` was the only threshold left in this rule that did not scale with
conditions — a flat 2 m, which is a walking pace at 1 Hz and well inside the noise of a poor fix.
That is the same "constant that does not scale to the situation" failure this ADR exists to remove,
surviving in the one place the redesign had not yet touched.

### Decision

The floor widens with reported accuracy at **half rate**, capped at 15 m
(`widenWithAccuracy(2.0, 0.5, accuracy, 15.0)`) — the shape already used by the match gate, the
off-trail gate and the vertex accept radius. Half rather than whole: two fixes at accuracy *a* can
disagree by more than *a* between them, so a factor of 1 is generous enough to swallow a slow
genuine reversal. The cap is there for the same reason as everywhere else: an implausible accuracy
report must not be able to switch wrong-way detection off.

The floor actually used is written into the `BacktrackCheck` log line, so a field log states the
threshold a decision was made against instead of requiring the constant to be reconstructed from a
remembered accuracy.

### Verification

Swept over all 2110 corpus fixes, with the 45 s alert cooldown applied:

| session | before | after |
| --- | --- | --- |
| walk1 s1 trail 7 reverse | — | — |
| walk1 s2 trail 12 forward | 01:08:22, 01:10:18 | unchanged |
| walk1 s3 trail 12 reverse | 01:32:12 | unchanged |
| walk2 s1 trail 12 forward | — | — |
| walk2 s2 trail 12 reverse | **16:07:35** | — |

The three surviving alerts are all sustained regression at walking pace under 2.5–3.4 m accuracy —
ten or more seconds of consistent backwards movement, which is what the announcement is for, and all
three are on **real** GPS. The one removed is the injected-degradation case above.

**Both 2026-08-12 exports were recovered on 2026-08-15**, with the owner's 27 markers, which settles
most of what this section previously had to leave open. Against walk 1's three wrong-way alerts:

| the old build | today's rule | the owner's marker |
| --- | --- | --- |
| 01:10:19, real GPS at 2.6 m | fires 01:10:18 | — |
| 01:37:27 + 01:37:46, injected 25 m | **silent** | 01:37:40 *"weird wrong way fired"* |
| — | fires 01:32:12, real GPS at 3.4 m | — |

The middle row is the one that matters: the owner flagged those two as wrong *while walking*, and the
rule no longer produces them. The first is reproduced unchanged. The third is new and unlabelled —
nobody remarked on that moment either way, so it stays an open question for a walk.

The eleven off-trail alerts from 01:19:16 to 01:29:36 are **correct**, and labelled so at the time:
01:19:33, *"making a big old loop off trail. Will be back on earlier stretch eventually"*. Any change
that silences those is a regression, which makes them the most useful off-trail fixture available.

One trap the markers set and then sprang: three of them read "fixes from here are deliberately
degraded", at 01:08:20, 01:29:27 and 01:30:34, yet reported accuracy never left 2.5–3.8 m across any
of those spans — and at 01:10:40 the owner writes *"Not degrading."* Accuracy is actually pinned at
25 m for exactly one span, 01:36:36–01:38:08. **Trust the accuracy values, not the marker text**, for
where degradation was really in effect; the marker records an intent, sometimes one that was
immediately reversed.

## Revisions since first draft

Owner edits made outside plan mode, 2026-08-08. Listed so the planning agent can diff intent rather
than re-derive it.

1. **Rescan replaced by a bounded reacquisition ladder.** First draft had
   `after K unmatched → full rescan`. Now `Matched → Uncertain (widening window) → Lost (horizon
   expired) → Unconfirmed (global scan) → Matched (corroborated)`. The widening window is preferred
   but explicitly *not* allowed to become an excuse never to admit continuity is gone — past a
   horizon bounded in **both** metres and seconds the tracker declares `Lost` and scans globally.
   Global scan cost is negligible; the discipline is that its result stays `Unconfirmed`.
2. **`RESCAN_COOLDOWN_S` added.** Failed reacquisition must not trigger a global scan every fix.
   Implemented as a timestamp on the state, not a counter — `unmatchedCount` must not gate it.
3. **`TrailMatch.state` widened** from `Matched | Unmatched` to four internal states, because the
   ladder reads all four. Public state surface unchanged; that split stays deferred.
4. **Dead reckoning demoted from progress to prediction.** The largest change. Two scalars now:
   `confirmedAlongM` (geometry-only, the sole value any consumer reads) and `predictedAlongM`
   (extrapolation, used only to rank tied candidates, never persisted as progress, ignored past the
   horizon). The search window is centred on `confirmedAlongM`, not on the prediction. Motivating
   case: a user who stops for a dog or walks to a lake must not be advanced past a fork they never
   reached, then "reacquired" onto the wrong branch with a corrupted prior. Accepted cost: distance
   remaining freezes (hedged) through long no-fix stretches instead of coasting.
5. **Rescan scope clarified.** It is in-memory over `TrailPolyline`. `trailPointsInBbox` is a
   separate, collection-scoped query for mid-trail pickup and is not the rescan; its `TrailPointRow`
   projection omits `kind`. This was ambiguous in the first draft and caused a misread.
6. **Spatial-index (geohash) approach evaluated and rejected** for this rescan, with reasoning
   recorded in Architecture and the surviving use case moved to Deferred.
7. **Local ENU coordinate frame added to S0.** `TrailPolyline` now converts points once into an
   east/north frame anchored at the trail centroid and does all projection, cross-track, chord
   bearing, sagitta and turn maths as plain vector algebra. This **reverses** the first draft's "reuse
   `segmentFraction` and `distanceToSegmentMeters`" instruction: those two are mutually inconsistent
   (planar projection, spherical distance, re-derived per segment), and inheriting that seam would
   make the density-equivalence test fail for coordinate-system reasons rather than real ones.
   Explicitly *not* a `GeoMath` rewrite — existing callers are untouched, and user-facing
   point-to-point distances may still use haversine. Along-trail distance remaining may not; it must
   stay self-consistent with `alongTrackM`. Adds a `LocalFrame` pinning test that measures rather
   than assumes the flat approximation's error.
8. **Angle conventions documented as a first-class section.** GPS bearings are zero-at-north and
   clockwise; unit-circle angles are zero-at-east and counter-clockwise. In the ENU frame the
   conversion is an `atan2` argument swap (`atan2(x, y)`, not `atan2(y, x)`), and the naive planar
   cross product is left-positive where the repo's `deltaAngle` is right-positive, so signed
   cross-track must be negated to match. These fail silently by reflection, so each row of the
   convention table gets a test.

9. **`LocalFrame` origin made an explicit parameter**, with `TrailPolyline` accepting an optional
   frame (default: centroid-anchored). Sole reason is forward compatibility with #59/#60, which need
   several polylines in one frame to compute junction geometry; concrete API surface written into S0.
   No behaviour change for anything in this plan. A new "Forward compatibility" section in
   Architecture records which decisions here already satisfy #60's constraints (the corroboration
   ladder, prediction-not-progress, the signed-quantity conventions that stereo-pan depends on) and
   which do not carry (`TrailPolyline` is single-trail; `alongTrackM` does not survive a graph).

10. **Course/vector evidence logged in S4, still excluded from the window.** Also **corrected an
    error in the first draft's premise**: it claimed course "cannot separate an out-and-back's two
    passes... courses matching on the return leg." A retrace has *opposite* tangents at the two
    candidate coordinates, so course separates that case cleanly. Course fails only on
    same-direction parallel geometry (switchback arms, parallel trails) — narrower than claimed, and
    it makes course a strong candidate corroborator during `Unconfirmed`. Logging is designed so the
    question is answerable on replay: agreement is recorded for the **chosen and the best-rejected**
    candidate, since course alone proves nothing without its rival. Promotion to load-bearing is an
    explicit post-S4 decision.
11. **S3 rewritten from AND to necessary-plus-corroborating.** The first draft required both excessive
    cross-track *and* the angle condition, which silently drops the case that matters most: a user on
    a wrong parallel path 30 m off trail has a small bearing delta and would never be told. Now
    cross-track is necessary, corroboration shortens the sustain window rather than gating it, and the
    primary corroborator is signed `crossTrackRateMps` rather than bearing-to-target — a stale target
    is what caused the original defect, so divergence evidence should not be routed through it. Angle
    is retained as additional evidence that can accelerate but never suppress. Consequence: this is
    **not** the "strictly removes false alerts" change the first draft called it; it also adds true
    positives, and existing tests asserting silence in the parallel case must be edited.
12. **High-speed traversal fixture added** (walking / bicycle / vehicle × 1 Hz / 5 Hz). Framed as
    characterization, not as a support claim. Its purpose is to force `maxSpeedMps` to derive from
    observed speed rather than be a global constant — a walking-tuned constant strands a cyclist in
    permanent `Lost`, while a vehicle-tuned one destroys the switchback protection that window size
    exists to provide. Surfaced two consequent gaps: completion must also fire on `alongTrackM`
    passing `totalLengthM` rather than radius alone (S2), and the reckoning horizon's metres and
    seconds bounds trade places at speed (S4).

13. **Reverse travel resolved; Open section now empty.** `TrailPolyline` is always in recorded order,
    never reversed; the tracker carries `travelDirection: Forward | Reverse`, chosen at follow-start
    and fixed for the session. Owner scoped mid-walk turnaround *detection* out, which shrinks this
    considerably — direction is a session parameter, not an inference. The key realisation is that the
    matching window is symmetric (`confirmedAlongM ± budget`), so reverse travel requires **no**
    matcher changes at all; only a handful of consumers are direction-aware. Turning around
    mid-session is a documented, tested, accepted degradation rather than a supported mode.
14. **Loop start identified as a real defect — and one that revision 12 made worse.** Adding
    `passed` to completion means a loop's initial global scan, which sees `alongTrackM ≈ 0` and
    `≈ totalLengthM` as equally good, can complete the trail on the first fix. Fixed with two
    independent guards: initial acquisition tie-breaks toward the endpoint implied by
    `travelDirection` (the two open questions turned out to answer each other), and completion now
    requires `MIN_TRAVEL_FOR_COMPLETION_M` of confirmed along-track travel. Completion is therefore
    `(radius OR passed) AND travelled`. The travel guard is the general fix and also covers
    non-loop trails whose ends are within GPS accuracy of each other.

15. **Completion travel guard scaled to trail length** (review pass, 2026-08-09). Rev 14's
    `MIN_TRAVEL_FOR_COMPLETION_M` was a flat constant, so any trail shorter than it could never
    complete — the same "constant that does not scale to the geometry" failure this ADR exists to
    remove, reintroduced by the fix for a different bug. Now
    `min(MIN_TRAVEL_FOR_COMPLETION_M, totalLengthM × TRAVEL_FRACTION)`, with a short-trail fixture.

16. **`Lost` clarified to preserve `confirmedAlongM`'s value** (review pass, 2026-08-09). Rev 1 said
    `Lost` "discards `confirmedAlongM` as a constraint," which admits two readings. The dog fixture
    requires the value to survive; only its role in bounding the search ends. Stated explicitly in
    S4 so the ambiguity cannot be resolved the wrong way during implementation.

Not yet reflected: whether S4's new constants shift the field-walk gate. **No open items remain.**

## Open

*(Reverse travel resolved 2026-08-09 — see Decisions taken. The S0 coordinate system is fully
specified.)*

- **S5a is done** (2026-08-14). `evaluateBacktrack` consumes `TrailMatch.confirmedAlongM`, signed by
  declared direction; the matcher runs ahead of the follower on every fix and is no longer gated by
  the Debug logging switch; both logging defects are fixed. See **Amendment 2** for the accuracy-aware
  noise floor the corpus forced on the way. **Not yet field-verified** — the corpus can show which
  fixes would fire, not what the user hears.
- **S5 is half done** (2026-08-15). Every consumer of an unwindowed projection is gone: wrong-way
  (S5a), the desired course, and off-trail cross-track. Distance, checkpoint counting and completion
  still come from `currentIndex`, which is the rest of S5. **Not field-verified.**
  - Deviation, deliberate: the match is passed as a parameter rather than added to
    `TrailFollowerState.Active`. `TrailFollower` does not produce a match and should not carry one,
    and a parameter cannot go stale.
  - Density equivalence now covers guidance decisions, not only geometry — the executable form of
    #35, and the half of it that only became assertable at S5.
- **S5b is open** — the geometry/annotation split. Blocks S6, and #69 is its attach-time guard.
- **`ProgressTracker.acquire()` still uses the plain match gate** (raised by S4c, undecided). A follow
  started while standing in an apex wedge can commit to a degenerate first position. Applying the
  vertex accept radius there would mean silence at follow-start, which is its own harm for a blind
  user. Owner's call, and **safe to defer**, on this reasoning:
  - All five acquisitions in the corpus are unambiguous — cross-track 0–8 m with the runner-up
    34–45 m away. The wedge case is real but unobserved, so it needs a deliberate walk (start a
    follow standing inside a bend apex), not more replay.
  - A degenerate acquisition self-heals: the next fix falls outside the window, the tracker goes
    `Uncertain` → `Lost`, rescans, and reacquires correctly. The cost is seconds of `Uncertain`.
  - S5a made that recovery quieter rather than louder: the reacquiring fix re-baselines, so the
    correction cannot read as a backtrack.
  - It becomes load-bearing when distance and completion move off `currentIndex` — the rest of S5 —
    because a wrong first position would then be *spoken*. Decide before that, not before the field
    walk.
  - Same family: a follow started already past the match gate never acquires at all, so off-trail
    stays quiet. Worth testing on the same walk.
- **The vertex accept radius is unset.** Same untuned status as the original S4 constants, with the
  difference that it can now be swept against the 2026-08-12 corpus before it ships.
