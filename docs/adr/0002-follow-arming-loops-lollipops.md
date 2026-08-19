# ADR 0002 — Arming a follow: where the walk starts on loops and lollipops

- **Status:** accepted; awaiting owner review
- **Date:** 2026-08-19
- **Issues:** #80 (the bug), #93 and #94 (found while designing this, deliberately out of scope)
- **Builds on:** ADR 0001 (S5 matching, S6 cues). Nothing here changes per-fix matching.
- **Informs:** #59 (trail graph) — the fork prompt is the non-graph stand-in described below.

## Context

Pressing Follow answers one question before any GPS fix arrives: **where on this trail does the
walk begin?** Today two components answer it separately, by different rules, and neither is told
what the user asked for.

`GpsViewModel.followTrailById` (`GpsViewModel.kt:1032`):

```kotlin
if (loc != null) trailFollower.startNearest(points, loc, bearing) else trailFollower.start(points)
```

`TrailFollower.startNearest` (`TrailFollower.kt:94`) scores every waypoint by distance, discards
those more than 90° off the user's current heading, and penalises the rest by heading difference.
The `reversed` flag never reaches it. Meanwhile `TrailGuidanceCoordinator.startFollow` *does* take
the direction, and `ProgressTracker.acquire` breaks a tie with `tieBreakByDirection`
(`ProgressTracker.kt:493`): forward takes the lowest `alongTrackM`, reverse the highest, among
candidates whose cross-track distances are within `CANDIDATE_TIE_M` (5 m).

So one press produces two anchors chosen by unrelated rules, and the one that decides what the
walker *hears* is the one that ignores the direction they asked for.

### What the field walk showed

Field walk 2026-08-17 (`bold_explorer_audio_log_20260817_082235.jsonl`): five follows of the same
51-point recorded loop, all begun from the same spot at the loop junction, armed checkpoints **1,
49, 46, 1 and 51**. Two announced immediate nonsense — "Checkpoint 49 of 51 … 111 feet to go" and
"Checkpoint 51 of 51 … 1 feet", then "0 feet to go" repeatedly. Nothing about the walker changed
between presses except which way they happened to be facing.

Heading is the wrong input, and not only because it was applied without the direction flag. At
follow-start the walker is usually standing still, so course-over-ground is either absent or noise;
and where the trail passes through the same ground twice, heading cannot distinguish the two passes
at all — they are parallel or anti-parallel by construction.

### The geometry this has to survive

Measured from the owner's recorded "lake loop" (trail 20, 337 track points, in the 2026-08-16
backup; coordinates deliberately not reproduced here — see the corpus README):

| quantity | value |
| --- | --- |
| total length | 3742 m |
| gap between the two ends | 1.1 m |
| stick, walked out and back | 0 → 959 m, doubled at 2792 → 3742 m |
| loop ("the candy") | 959 → 2792 m, 1833 m around |
| distance between the two junction passes | **16.6 m** |
| separation between the stick's two passes | 2 – 15 m |

Two facts follow, and they are the whole design.

**First, the 5 m tie is the wrong tolerance for this question.** `CANDIDATE_TIE_M` compares two
candidate answers for *one fix*, where 5 m is generous. Arming asks a different question — "does the
ground I am standing on carry more than one pass of this trail?" — and the answer is recorded track
data, which never retraces itself exactly. At 5 m the junction's second pass is not even a
contender, so the anchor is settled by whichever pass GPS noise put the walker nearer to. Arming
needs its own, wider radius.

**Second, "is this a loop?" is a question we never have to ask.** `TrailPolyline.candidates`
(`TrailPolyline.kt:185`) walks the trail and returns every local minimum of cross-track distance, so
two passes over the same ground already arrive as two candidates. Loops, lollipops, figure-eights and
out-and-backs need no shape detection, no loop flag, and no waypoint identity — which matters,
because identity is unavailable: `uq_trail_waypoint_pair` (`TrailWaypoint.sq:14`) makes a trail's
start and end necessarily different rows (#93).

That is a claim about *shape*, and it is worth being exact about its limit. One spatial tolerance
remains (§5), and it decides whether two candidates are near enough to each other to be
indistinguishable. It reads no along-track relationship and no topology, so trail size and loop
length cannot fool it — but it also cannot tell a second pass from a different piece of trail that
happens to lie within that distance. §5 states what that costs.

## Decision

### 1. One anchor, resolved once, before anything starts

A new file `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/FollowArming.kt`:

```kotlin
/** Why the anchor is where it is. Carried so the caller can phrase the start announcement. */
enum class ArmReason { OnTrail, NoFix, OffTrail }

data class AnchorOption(
    val anchor: TrailPosition,
    val remainingM: Double,
    val revisitsStartPoint: Boolean,
)

sealed interface ArmingResult {
    data class Resolved(val anchor: TrailPosition, val reason: ArmReason) : ArmingResult

    /**
     * Two or more continuations, ordered by `anchor.alongTrackM` ascending.
     *
     * `init { require(options.size >= 2) }` — a one-option fork is a `Resolved` that took a wrong
     * turn, and the UI generates its buttons from this list, so the invariant is the UI's contract
     * too. Three is reachable: ground a trail crosses twice more (an out-and-back-and-out, a
     * three-lobed loop) yields three, and nothing in the resolver or the dialog caps it at two.
     */
    data class Fork(val options: List<AnchorOption>) : ArmingResult
}

/** The arming constants, as a parameter so a test can vary one. Mirrors [MatchTuning]'s reason for existing. */
data class ArmingTuning(
    val samePathBaseM: Double = NavigationPolicy.SAME_PATH_BASE_M,
    val samePathAccuracyFactor: Double = NavigationPolicy.SAME_PATH_ACCURACY_FACTOR,
    val samePathCapM: Double = NavigationPolicy.SAME_PATH_CAP_M,
) {
    /** The same-ground radius for a fix of this quality. Widens with poor accuracy; never narrows. */
    fun samePathM(accuracyM: Double?): Double =
        NavigationPolicy.widenWithAccuracy(samePathBaseM, samePathAccuracyFactor, accuracyM, samePathCapM)
) {
    companion object { val DEFAULT = ArmingTuning() }
}

object FollowArming {
    fun resolve(
        polyline: TrailPolyline,
        direction: TravelDirection,
        location: LatLng?,
        accuracyM: Double?,
        tuning: ArmingTuning = ArmingTuning.DEFAULT,
    ): ArmingResult
}
```

The algorithm, in order:

1. **No fix** — `location == null` → `Resolved(traversalStart, NoFix)`, where `traversalStart` is
   `polyline.project(polyline.positionAt(0.0))` for `Forward` and the same for
   `positionAt(totalLengthM)` under `Reverse`. `project(point, window = null)` is non-null for a
   non-empty polyline — a `FollowSession` cannot exist without one — so `!!` is safe here and is the
   same assumption `GpsViewModel` already documents when projecting annotations.
2. **Candidates** — `polyline.candidates(location, window = null)`.
3. **Gate** — keep those with `abs(crossTrackM) <= gate`, where `gate` is
   `NavigationPolicy.widenWithAccuracy(MATCH_GATE_BASE_M, MATCH_GATE_ACCURACY_FACTOR, accuracyM,
   MATCH_GATE_CAP_M)` — the same gate `ProgressTracker.onFix` computes. If none survive →
   `Resolved(traversalStart, OffTrail)`.
4. **Same ground** — let `best` be the candidate with the smallest `abs(crossTrackM)`. Keep every
   candidate whose **snapped point** is within `tuning.samePathM(accuracyM)` of `best.snapped` on the
   ground; discard the rest. One test, measured between the candidates themselves rather than between
   each candidate and the walker.

   The question this asks is **"could these two strands be the same path, recorded twice?"** — a
   distance between two pieces of stored geometry, not a distance along the trail and not a statement
   about where the walker is. See §5, where that distinction decides how the constant scales.
5. **Drop dead ends** — discard any candidate whose `remainingM` in the requested direction is within
   the **completion radius**, where `remainingM` is `totalLengthM - alongTrackM` for `Forward` and
   `alongTrackM` for `Reverse`. If that empties the list, restore the single best-by-cross-track
   candidate and return it (the walker is standing at the finish; that is a true answer, not an
   ambiguity).

   The radius is the accuracy-aware one completion already uses —
   `NavigationPolicy.tightenWithAccuracy(COMPLETION_CEILING_M, COMPLETION_FLOOR_M,
   COMPLETION_SIGMA_FACTOR, accuracyM)`, 5–15 m — promoted out of `TrailFollower`'s private
   `completionRadiusM` into `NavigationPolicy` so both callers read one definition.

   Sharing it is the point, not a convenience. The test this step is really applying is *"would this
   anchor complete the follow on its first fix?"*, and completion is the authority on that. An
   arming-specific threshold would put the cliff somewhere completion does not agree with: at 25 m, a
   walker 26 m up the stick asking for reverse gets a 26 m walk and one 2 m step earlier gets a 3.5 km
   backwards loop instead. Tied to the completion radius the cliff sits exactly where "I am at the
   end" stops being true, which is where the walker's meaning genuinely changes too.
6. **One left** → `Resolved(it, OnTrail)`.
7. **Two or more** → `Fork`, one option per surviving candidate, ordered by `alongTrackM`. Always.
   There is no default pass and no test deciding whether the ambiguity is worth mentioning: if the
   ground carries two live continuations, the walker is told so and picks one. §2 is why.

### 2. Two buttons, three intents — why the answer is always to ask

The anchor says *where the walker is standing*; the declared direction says *which way they leave
it*. On a doubled segment that combination is not enough, and the shortfall is not symmetric. Take a
walker 80 m up the lake loop's stick, with candidates at along ≈ 80 (outbound pass) and ≈ 3662
(return pass). Four routes exist:

| anchor | direction | where it goes | length |
| --- | --- | --- | --- |
| 80 | Forward | up the stick, round the loop, back down | 3662 m |
| 80 | Reverse | down the stick to the trailhead | 80 m |
| 3662 | Forward | down the stick to the trailhead | 80 m |
| 3662 | Reverse | up the stick, **round the loop backwards**, back down | 3662 m |

Rows 2 and 3 are the same walk over the same ground. So the three *distinct* intents — do the loop,
go back to the car, do the loop the other way — are competing for two buttons, and start-most-anchor
assigns both buttons to the same pass: the walker gets rows 1 and 2 and can never reach row 4. No
amount of choosing a better default fixes that; a third intent needs a third answer.

The prompt is that third answer, and it is offered whenever a second continuation survives. There is
no test judging whether the ambiguity deserves raising: the question *is* the honest description of
where the walker is standing — this ground carries two ways to walk, here is each one's length, pick
one. See **Rejected** for the two gates that tried to answer it on their behalf.

This supersedes an earlier instruction (owner, 2026-08-19) to take the start-most pass silently on the
stick. It also means a forward follow begun there prompts: 80 m up, Follow offers "The loop — 3.7
kilometers" against "On to the end — 80 metres". The second is row 3, the same walk as pressing
reverse — redundant in the model, but not to a walker thinking "get me back to the car" rather than
"which of the two buttons expresses that".

Silence still covers most walks: any position the trail touches once, and the trailhead of a loop,
where step 5 drops the zero-length option and leaves one candidate standing.

**Describing an option** needs no geometry beyond the candidates already in hand. `remainingM` is the
direction-signed distance to the traversal end, and `revisitsStartPoint` is true when another
surviving candidate lies ahead of this one in the requested direction — walking that option brings you
back past where you are standing, which is what "the loop" means to the person being asked. Options
are ordered by `alongTrackM` so the order is stable between two presses at the same spot.

Out of scope and unchanged: a walker who reaches the junction *mid-walk* and takes the other arm.
That needs continuity or #59's graph, not an arming-time question.

### 3. The anchor seeds both consumers

**`ProgressTracker` gains an acquisition prior** and loses its private tie-break:

- constructor takes `acquisitionPriorM: Double`, defaulted from the parameters before it —
  `= if (travelDirection == TravelDirection.Forward) 0.0 else polyline.totalLengthM` — so a prior
  always exists and every existing test constructs the tracker unchanged.
- `FollowSession` takes `seedAlongM: Double? = null` and passes `seedAlongM ?: <that same default>`,
  so existing callers and tests keep today's behaviour without edits.
- `acquire` replaces `tieBreakByDirection(found)` with: among candidates **that pass the gate**, take
  the one minimising `abs(alongTrackM - acquisitionPriorM)`. Not a tie-break — the selection rule.
- `tieBreakByDirection` is deleted. It has no remaining caller, and a prior sitting at the traversal
  start reproduces exactly what it used to do — which is test 12 below.
- No `acquisitionTieM`. An earlier draft consulted the prior only among candidates whose cross-track
  distances were within a tolerance of each other, which needed a constant *and* left the choice to
  geometry whenever the tolerance was exceeded.

**Why the prior binds rather than nudges.** A tie-break is weaker than what the dialog promised. If
the walker picks pass A, then GPS wanders while they are answering — a dialog can stand open for
half a minute — a tie-break lets the first matched fix acquire pass B on cross-track alone, while the
follower stays armed on A. That is precisely the split-brain this ADR claims to make unrepresentable,
recreated one layer down. Making the prior the selection rule closes it: both consumers are anchored
by the same chosen pass, and the other pass can only win by being *nearer the walker's chosen
position along the trail*, which a second pass hundreds of metres away cannot be.

The prior still constrains acquisition only. It is never written to `confirmedAlongM`, never counted
as travel, and every candidate must still clear the gate — so a stale cached fix at arming time can
choose the wrong pass but never invent a position. If the walk then contradicts the choice, the
existing ladder handles it: cross-track degrades, the tracker goes Uncertain and then Lost, and the
global rescan re-decides on geometry with no prior at all. Recovery is the ladder's job, and it is
already built; what the prior removes is the *silent* disagreement at fix one.

**`TrailFollower` arms from the anchor** and loses its heading heuristic:

- `startNearest` is deleted, with its tests.
- `followTrailById` calls `trailFollower.start(points, fromIndex = followerIndexFor(anchor))`.

`points` is the traversal-ordered list (`wps` or `wps.reversed()`), while the anchor is in recorded
along-track, so the mapping is:

```kotlin
// Forward: first vertex at or ahead of the anchor.
val recordedIndex = when (direction) {
    Forward -> (0 until poly.size).firstOrNull { poly.cumulativeM[it] >= anchor.alongTrackM } ?: poly.size - 1
    Reverse -> (poly.size - 1 downTo 0).firstOrNull { poly.cumulativeM[it] <= anchor.alongTrackM } ?: 0
}
val fromIndex = if (direction == Forward) recordedIndex else poly.size - 1 - recordedIndex
```

`cumulativeM` is already public (`TrailPolyline.kt:61`), so no new polyline API is needed anywhere in
this ADR.

### 4. The fork prompt

`Fork` blocks the follow. Nothing starts — no session, no follower, no location service — until the
walker answers. This is the standing rule that the app never guesses direction on a blind walker's
behalf, applied to the one case where the direction they gave us genuinely does not settle it.

The prompt must be reachable from **both** entry points, and one of them does not navigate to the
GPS tab: the Trails-screen action fires into `TargetingStateHolder` and `GpsViewModel` picks it up in
the background (`GpsViewModel.kt:809`), leaving the walker on the Trails screen. So the dialog is
hoisted to `NavGraph`, which already holds the activity-scoped `GpsViewModel` and already renders one
app-wide element outside every screen — the live region at `NavGraph.kt:97`.

- `GpsViewModel` exposes `val followPrompt: StateFlow<FollowForkPrompt?>`, set in place of starting
  the follow, cleared by `resolveFollowFork(optionIndex: Int)` or `cancelFollowFork()`.
- `resolveFollowFork` resumes `followTrailById` from step §3 with the chosen anchor.
- `cancelFollowFork` starts nothing and announces "Follow cancelled".
- `NavGraph` renders an `AlertDialog` next to the live region: title "The trail passes here more
  than once", one button per option in `options` order, plus Cancel. The title states multiplicity
  without counting it, and the button list is generated from the list — three options render three
  buttons, and neither the title nor the tests assume two.

Button labels are built in the app layer from the geometric facts, so `shared` never learns about
phrasing:

- `revisitsStartPoint` → `"The loop — ${formatSpokenDistance(remainingM, units)}, comes back past here"`
- otherwise, `Forward` → `"On to the end — ${formatSpokenDistance(remainingM, units)}"`
- otherwise, `Reverse` → `"Back to the start — ${formatSpokenDistance(remainingM, units)}"`

Direction is named rather than left implicit because the two options can point opposite ways along
the same ground: 80 m up the stick, reverse offers "Back to the start — 80 metres" against "The loop
— 3.7 kilometers, comes back past here", and only the second walks *away* from the trailhead first.

Labels carry the whole phrase, never "Option 1" — a screen-reader user gets the choice from the
button text alone. No compass word is spoken and none is carried: there is no compass-word formatter
in the codebase, and a sampling baseline to compute an initial bearing from would be exactly the kind
of unjustified constant §2 exists to avoid.

### 5. One constant, why it widens with poor GPS, and what it cannot distinguish

| constant | value | why |
| --- | --- | --- |
| `SAME_PATH_BASE_M` | 20.0 | the two recorded passes at the measured lake loop junction are 16.6 m apart, 2–15 m along its stick; 20 is that worst case plus a little, from one junction on one trail |
| `SAME_PATH_ACCURACY_FACTOR` | 2.0 | ≈95% containment on Android's 1σ accuracy figure |
| `SAME_PATH_CAP_M` | 40.0 | `MATCH_GATE_CAP_M`; a strand the matcher would not accept is not a continuation worth offering |

Named for what it measures — the distance between two strands of stored geometry — and deliberately
not reusing `NEAR_TRAIL_*`, whose 20/2 pair is numerically identical but measures the *walker's*
distance to a trail (`NearbyTrailResolver`, "close enough to pick this trail up mid-trail"). Sharing
the value would tie fork behaviour to the nearby-trails list, which is the coupling #55 was filed to
remove.

**It widens with poor accuracy and never narrows, which is the opposite of ADR 0001's convention for
acceptance gates.** The convention holds there because a generous acceptance region lets a bad fix
commit wrong progress silently. Nothing is accepted here — the output is a question — so the costs
run the other way: too wide costs one extra option in a dialog, too narrow silently deletes a
continuation the walker wanted. A fix we trust less should produce more asking, not less.

**Why it does not narrow with a good fix, which is the intuitive move.** The 16.6 m figure is
*recording* spread: both strands were recorded through GPS error, on different days and opposite
traversals, and that separation is baked into the stored geometry. Today's 3 m fix does not shrink
it. A tie scaled purely on current accuracy — 2 × 3 m = 6 m — would discard the junction's second
strand and delete the fork this ADR exists to produce.

Step 5's dead-end test adds none: it reads the completion radius, so arming and completion cannot
disagree about where the trail ends. Step 3's gate reads the match gate for the same reason.

This is a **spatial resolution**, not a topology classifier. It answers whether two strands are close
enough to be one path recorded twice, and answers it identically for a lollipop stick, a figure-eight
crossing, a hairpin and a switchback. It performs no shape detection and reads no along-track
relationship, so trail *size* cannot fool it: a 100 m loop whose occurrences are 100 m apart along the
trail is handled exactly like a 3.7 km one.

**What it cannot do, at any value, is tell one path recorded twice from two treads that genuinely run
close together.** Two arms of a hairpin 15 m apart will fork. No floor fixes this: the junction needs
≥ 16.6 m to work at all, so any value that keeps the primary case also swallows a 15 m hairpin. The
information that would separate them — that these are two treads with vegetation between rather than
one path walked twice — is not in trail geometry at all. It arrives with #59's graph, which is what
supersedes this mechanism.

Until then the failure is a spurious option in a dialog, on a shape nobody has yet recorded in this
corpus, and the walker is told what both options are before choosing. That is the cheap direction to
be wrong in.

## Files

| file | change |
| --- | --- |
| `shared/.../navigation/FollowArming.kt` | new — `ArmingResult`, `ArmingTuning`, §1, §2 |
| `shared/.../navigation/NavigationPolicy.kt` | the three same-ground values (base/factor/cap), plus `completionRadiusM` promoted from `TrailFollower` |
| `shared/.../navigation/ProgressTracker.kt` | `acquisitionPriorM`; `acquire` uses it; delete `tieBreakByDirection` |
| `shared/.../navigation/FollowSession.kt` | `seedAlongM` parameter, passed to the tracker |
| `shared/.../navigation/TrailGuidanceCoordinator.kt` | `startFollow` takes `seedAlongM` |
| `shared/.../navigation/TrailFollower.kt` | delete `startNearest`; `completionRadiusM` moves to `NavigationPolicy` and is called from there |
| `app/.../ui/gps/GpsViewModel.kt` | resolve once; seed both; `followPrompt`; `resolveFollowFork`; `cancelFollowFork` |
| `app/.../ui/NavGraph.kt` | the fork dialog |
| `shared/.../navigation/TrailFixtures.kt` | make `offsetFromOrigin(northM, eastM)` public here and have `WalkScenario` use it, so hand-built and scrubbed fixtures share one origin |

## Verification

### The fixture

`shared/src/commonTest/.../navigation/LollipopFixture.kt`, built in metres from the fixtures' synthetic
origin and densified at 10 m (`densify`, already in `TrailFixtures.kt`). Proportions echo the real
lake loop; the numbers are round so expectations can be literals.

```
stick out:   (0, 0) → (0, 950)                                    along 0    → 950
candy:       (0, 950) → (400, 950) → (400, 1350) → (0, 1350) → (0, 950)   along 950  → 2550
stick back:  (0, 950) → (0, 0)                                    along 2550 → 3500
```

Landmarks: trailhead 0 / 3500; junction 950 / 2550; mid-stick 475 / 3025.

A second variant, `LollipopFixture.jittered`, offsets the return pass 15 m east, so that no two passes
over the same ground coincide exactly — which is the real-world case:

```
stick out:   (0, 0) → (0, 950)
candy:       (0, 950) → (400, 950) → (400, 1350) → (15, 1350) → (15, 950)
stick back:  (15, 950) → (15, 0)
```

Its landmark along-track values are not round, so its expectations are written as relations rather
than literals. This is the fixture that exercises the same-ground radius: the two junction passes are
15 m apart, which the 20 m base keeps and a 5 m tie discards.

### Cases

`FollowArmingTest`, location exactly on the line unless stated, `accuracyM = 5.0`:

| # | standing at | direction | expected |
| --- | --- | --- | --- |
| 1 | trailhead (0, 0) | Forward | `Resolved(alongTrackM ≈ 0, OnTrail)` |
| 2 | trailhead (0, 0) | Reverse | `Resolved(alongTrackM ≈ 3500, OnTrail)` — step 5 drops the 0 candidate |
| 3 | mid-stick (0, 475) | Forward | `Fork`, options `[475 (remaining ≈ 3025), 3025 (remaining ≈ 475)]` |
| 4 | mid-stick (0, 475) | Reverse | `Fork`; the two options are the walk back to the trailhead (≈ 475 m) and the loop backwards (≈ 3025 m) |
| 5 | junction (0, 950) | Forward | `Fork`, options `[950 (remaining ≈ 2550, revisits = true), 2550 (remaining ≈ 950, revisits = false)]` |
| 6 | junction (0, 950) | Reverse | `Fork`, options `[950 (remaining ≈ 950, revisits = false), 2550 (remaining ≈ 2550, revisits = true)]` |
| 7 | junction, jittered fixture | Forward | `Fork` with two options. Re-run with `tuning.samePathBaseM = 5.0` and it collapses to `Resolved` — the regression the surviving constant exists to prevent |
| 8 | 200 m east of the stick | Forward | `Resolved(0, OffTrail)` |
| 9 | no location | Reverse | `Resolved(3500, NoFix)` |
| 10 | mid-leg of a straight fixture | either | `Resolved`, one candidate — regression guard |
| 10a | 30 m up the stick (0, 30) | Reverse | `Fork`, options `[30 (remaining ≈ 30), 3470 (remaining ≈ 3470)]` — §2's row 4, otherwise unreachable |
| 10b | 30 m up the stick (0, 30) | Forward | `Fork`; the two options are the loop (≈ 3470 m) and the walk out (≈ 30 m) |
| 10c | 8 m up the stick (0, 8), `accuracyM = 6.0` | Reverse | radius is `min(15, max(5, 2 × 6)) = 12`, so the 8 m candidate is dropped and the anchor is ≈ 3500 — the follow that exists rather than the one that would complete on its first fix |
| 10d | same position, `accuracyM = 2.0` | Reverse | radius is 5, so both candidates survive and it forks — a good fix earns the choice a poor fix cannot support |
| 10e | anywhere on the candy, either direction | either | `Resolved` — one candidate, no prompt; the loop itself is not doubled ground |

**Adversarial fixtures.** These exist to attack the same-ground rule itself, not to guard behaviour
that is already agreed. Each is a case where an along-track-separation rule and a spatial rule
disagree, so a future edit that reintroduces the former fails here rather than in the field.

| fixture | shape | standing at | expected |
| --- | --- | --- | --- |
| `SmallLoop` | 30 m stick, 100 m loop, 30 m stick back (160 m total) | mid-stick | `Fork` — the two occurrences are 130 m apart along-track, which a 150 m collapse would have eaten, and 0 m apart on the ground |
| `Hairpin` | two 200 m arms 10 m apart, 400 m apart along-track | on one arm | `Fork` — **intended**: at 10 m separation no fix this app receives can say which arm the walker is on, so both are offered |
| `Switchback` | two 200 m arms 40 m apart | on one arm | `Resolved` — 40 m exceeds the tie, so the far arm is discarded and no prompt appears |
| `TightSwitchback` | the same, arms 15 m apart | on one arm | `Fork` — inside the 20 m base at any accuracy, and unfixable by tuning (§5); asserted so the limitation is visible rather than discovered |
| `Switchback`, `accuracyM = 20.0` | arms 40 m apart, poor fix | on one arm | `Fork` — the radius widens to 40 and the far arm re-enters. The accuracy-widening guard: the same geometry answers differently when the fix is worse, in the direction of asking |
| `FigureEight` | compact crossing, both lobes ≈ 300 m | at the crossing | `Fork`, exactly two options |
| `TriplePass` | a stem walked out, back, and out again | mid-stem | `Fork` with **three** options, and a dialog rendering three buttons — the arbitrary-N guard |

`ProgressTrackerArmingTest`:

11. Jittered fixture, prior 475, direction Reverse, first fix at (0, 475): acquires ≈ 475, not ≈ 3025.
    This is the case today's `tieBreakByDirection` gets wrong, and it is the reason the prior exists.
11a. **The split-brain guard.** Jittered fixture, prior on the outbound pass, first fix placed so the
    *return* pass has the smaller cross-track — the walker chose A and GPS then drifted toward B.
    Acquisition must still return A. Under a tie-break prior it returns B while the follower stays on
    A, which is the split-brain this ADR claims to make unrepresentable; under the selection-rule
    prior it cannot. Fails loudly if anyone reintroduces a tolerance around the prior.
12. Prior at the traversal start on a closed fixture reproduces today's forward/reverse acquisition,
    proving the deletion is behaviour-preserving where it should be.

`TrailFollowerArmingTest`:

13. Forward, anchor 475: `fromIndex` satisfies `cumulativeM[i] >= 475 > cumulativeM[i - 1]`.
14. Reverse, anchor 475: `fromIndex` is `size - 1 - i` for the largest `i` with `cumulativeM[i] <= 475`.
15. Anchor at the traversal end arms the last index and does not complete before travel — the
    existing `CompletionEvidence` guard still applies.

Field verification is a separate walk, on the real lake loop, at the four places above. `make
test-shared` covers everything else; no device is needed.

## Consequences

- Heading no longer influences arming anywhere. It was never a valid input to this question and was
  the direct cause of #80.
- There is exactly one arming decision, and both the matcher and the follower read it. The failure
  mode where two components anchor a walk differently is now unrepresentable.
- A fork blocks follow-start, and fires whenever the walker's position carries two live
  continuations rather than when a heuristic judges the ambiguity worth raising. On trails whose
  geometry never doubles back — the overwhelming majority — nothing changes at all.
- One tuned quantity, not six — a same-ground radius expressed as base/factor/cap in the codebase's
  existing accuracy idiom, rather than six independent knobs (see **Rejected**). §5 states what it
  cannot distinguish rather than claiming it distinguishes everything.
- The walker's choice binds both consumers. Acquisition selects by the chosen pass rather than
  preferring it, so there is no window in which the follower and the matcher can disagree about which
  pass the walk is on; disagreement is left to the reacquisition ladder, which is built to resolve it
  out loud.
- The prompt is a stand-in for #59's trail graph, not a replacement. A walker who reaches a junction
  *mid-walk* and takes the other arm is still unhandled; that needs graph-aware matching, and
  probabilistically at that (particle filter or Markov-style), which is post-iOS.
- The start announcement still says "Checkpoint N of M" (`GpsViewModel.kt:1094`) although ADR 0001
  retired that vocabulary. Arming makes the number *correct*; deleting the phrasing is S6's business
  and is left alone here.

## Implementation checklist

Ordered so every step is verifiable by `make test-shared` before the next one starts; `app` comes
last because it is the only part needing a device. Tick as you go.

**If a Verification expectation turns out not to match the implementation, stop and report it.** Do
not adjust the expected value to match the code — the expectations are the design, and one of them
being wrong is a finding about this ADR, not a test to fix.

### Shared: arming

- [ ] `NavigationPolicy` — add `SAME_PATH_BASE_M`, `SAME_PATH_ACCURACY_FACTOR`, `SAME_PATH_CAP_M`;
      move `completionRadiusM` here from `TrailFollower`'s private copy and have `TrailFollower` call it.
- [ ] `TrailFixtures.kt` — make `offsetFromOrigin(northM, eastM)` public; point `WalkScenario` at it.
- [ ] `LollipopFixture.kt` — the 3500 m lollipop and its 15 m-jittered variant (§ *The fixture*).
- [ ] `FollowArming.kt` — `ArmReason`, `AnchorOption`, `ArmingResult` (with `require(options.size >= 2)`
      on `Fork`), `ArmingTuning`, and `resolve()` implementing steps 1–7 in order.
- [ ] `FollowArmingTest` — cases 1–10e. Gate: `make test-shared`.
- [ ] Adversarial fixtures + tests — `SmallLoop`, `Hairpin`, `Switchback` (both accuracies),
      `TightSwitchback`, `FigureEight`, `TriplePass`. Gate: `make test-shared`.

### Shared: seeding both consumers

- [ ] `ProgressTracker` — `acquisitionPriorM` constructor parameter with the direction-derived default;
      `acquire` selects by nearest-to-prior among gate-passing candidates; delete `tieBreakByDirection`.
- [ ] `FollowSession` — `seedAlongM: Double? = null`, resolved to the same default and passed down.
- [ ] `TrailGuidanceCoordinator.startFollow` — accept and forward `seedAlongM`.
- [ ] `TrailFollower` — delete `startNearest` and its tests.
- [ ] `ProgressTrackerArmingTest` — 11, **11a (the split-brain guard)**, 12. Gate: `make test-shared`.
- [ ] `TrailFollowerArmingTest` — 13–15. Gate: `make test-shared` clean, whole suite.

### App

- [ ] `GpsViewModel.followTrailById` — resolve the anchor once; seed the session and the follower from
      it; `followPrompt`, `resolveFollowFork(optionIndex)`, `cancelFollowFork()`.
- [ ] `NavGraph` — the fork `AlertDialog` beside the live region, buttons generated from `options`.
- [ ] Device check with TalkBack: fork dialog reachable from the **Trails** screen entry point (the one
      that does not navigate to the GPS tab), button labels read as whole phrases, Cancel starts nothing.

### Field

- [ ] Lake loop, the four places: trailhead, mid-stick, junction forward, junction reverse.

## Rejected: gating the prompt

Two gates were designed and dropped before this ADR was accepted. Both worked; both existed to spare
the walker a question, and between them they cost four constants no field measurement supports.

- **A leg test.** Count the directions in which trail leaves the ground under the walker, by sampling
  a ray forward and back from each candidate and clustering the bearings; ask only at three or more.
  It cleanly separated a junction from a doubled stick, and it needed a sampling baseline and a merge
  angle, both invented.
- **A route-length test.** Ask only when the discarded route is *materially* longer than the chosen
  one — the reasoning being that a shorter discarded route is the "go back" option, which the other
  button already reaches. This needed a factor and a floor, also invented.
- **An along-track separation rule** (`ARMING_MIN_SEPARATION_M = 150`), collapsing candidates closer
  than that along the trail, on the reasoning that a switchback's arms are close along-track while a
  second pass is far. That relationship does not hold in general, and the constant had no measurement
  behind it while sitting in a table that claimed one: a 100 m loop revisits identical ground 100 m
  apart along-track and would have been collapsed, while a hairpin's arms can be 10 m apart on the
  ground and hundreds along it. It was covert topology classification. Step 4's spatial test replaces
  it, answers both counterexamples correctly, and costs no constant of its own.

The trade they were making is a bad one for this app: a silent wrong guess costs a blind walker a
walk in the wrong direction, and the prompt costs one tap. It is also scaffolding — #59's graph
replaces this mechanism — and tuning scaffolding spends judgement where it cannot be recovered.

## Not addressed (filed)

- **#93** — a hand-built route cannot revisit a point, so hand-built loops are impossible. Recorded
  loops are unaffected, and this design never consults waypoint identity.
- **#94** — on a lollipop, an annotation on the stick is announced on one pass only. Same
  duplicate-ground fact, different consumer.
