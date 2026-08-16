# S6 — Follow Audio Vocabulary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-track-point `WaypointReached` firehose with a designed audio vocabulary — a progress earcon and speech, annotation approach cues, and match-state transitions — and drop the accidental detector heartbeat it was carrying.

**Architecture:** Cue *decisions* move into `:shared` as pure producers that **return** a cue rather than speaking one, so iOS inherits them and a second producer (S8's bend cue) is added beside rather than inside. `GpsViewModel` keeps only the effects: speak, beep, log. The grace-window removal ships as a strict shadow — evaluators run every fix and log their disposition, while firing stays gated as today.

**Tech Stack:** Kotlin Multiplatform (`:shared` commonMain/commonTest, JVM tests), Android app module, SQLDelight, Compose, Hilt, kotlin.test.

**Spec:** `docs/adr/0001-continuous-trail-matching.md`, section "S6 design, decided with the owner 2026-08-16". Read it before Task 1 — this plan argues from it and does not restate its reasoning.

## Global Constraints

- **No unit arithmetic in `app/`.** Metres are internal and appear in no utterance. Every spoken distance goes through the shared spoken formatter with `AppSettings.units` (default `IMPERIAL`). After Task 1, `rg '3\.28084|5280' app/src/main/kotlin` must return nothing.
- **Producers return cues, never speak them.** Anything deciding what to say lives in `:shared` and returns a value; only `GpsViewModel` performs effects.
- **Density invariance.** No decision may depend on track-point spacing. Use along-track metres, never vertex counts or indices.
- **Direction sign.** Every along-track comparison goes through `TravelDirection.sign`; a reverse follow must need no special case.
- **Shadow by default, audible on request.** Task 9 must not change when an alert is *spoken* with
  the debug switch off, which is the default. The switch exists so the owner can hear what the shadow
  would have said, having accepted the risk deliberately and while not relying on the app for support.
- **Verification:** `make test-shared` for fast feedback, `make test` and `make lint` before every commit. Both must be green.
- **Commits:** run `jj new -m "<message>"` before starting each task (this repo is Jujutsu; see the `jujutsu` skill). Do not accumulate two tasks into one change.
- **Starting constants are guesses.** Use exactly the values in the spec's constants table. Do not retune them; the field logs decide that later.

## File Structure

**Created in `shared/src/commonMain/kotlin/com/boldexplorer/shared/`:**
- `navigation/SpokenDistance.kt` — spoken distance rendering (Task 1)
- `navigation/FollowCuePolicy.kt` — straightness gate + cue constants (Task 3)
- `navigation/ProgressCue.kt` — progress cue type + producer (Task 4)
- `navigation/AnnotationCue.kt` — route annotations, approach + passed cues (Tasks 5–6)
- `navigation/MatchStateCue.kt` — lost/reacquired with sustain (Task 7)

**Modified:**
- `navigation/TrailPolyline.kt` — `alongTrackFor` (Task 2)
- `navigation/ProgressTracker.kt`, `navigation/FollowSession.kt` — expose speed and remaining (Task 2)
- `navigation/NavigationPolicy.kt` — S6 constants (Task 3)
- `navigation/TrailFollower.kt` — event vocabulary (Task 8)
- `navigation/TrailGuidanceCoordinator.kt` — shadow grace (Task 9), `isRecorded` gate (Task 10)
- `audio/AudioCueEvent.kt`, `audio/AudioCueScheduler.kt` — progress earcon (Task 11)
- `app/.../ui/gps/GpsViewModel.kt` — effects only, throughout

---

### Task 1: Spoken distance rendering moves to `:shared`

Empties `app/` of unit arithmetic. Settles the two defects the spec names: the imperial branch switches to miles above 1000 **feet** while dividing by 5280 (so 1000 ft speaks as "0.2 miles"), and metric speaks the abbreviation `"km"` against imperial's word `"miles"`.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/SpokenDistance.kt`
- Test: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/SpokenDistanceTest.kt`
- Modify: `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt` (delete private `formatDistanceM` at ~1852; update callers at ~863, ~1002, ~1324, ~1352, ~1501)

**Interfaces:**
- Produces: `fun formatSpokenDistance(meters: Double, units: Units): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenDistanceTest {
    @Test
    fun metricSpeaksWholeWordsNotAbbreviations() {
        // "km" is a coin toss on how a TTS engine renders it; imperial already speaks "miles".
        assertEquals("500 meters", formatSpokenDistance(500.0, Units.METRIC))
        assertEquals("1.3 kilometers", formatSpokenDistance(1300.0, Units.METRIC))
    }

    @Test
    fun imperialSwitchesToMilesAtAMileNotAtAThousandFeet() {
        // The defect: the old threshold was 1000 feet while the divisor was 5280, so everything
        // from 1000 ft to a mile spoke as a fraction — "0.2 miles" where "1000 feet" was meant.
        assertEquals("1000 feet", formatSpokenDistance(304.8, Units.IMPERIAL))
        assertEquals("5000 feet", formatSpokenDistance(1524.0, Units.IMPERIAL))
        assertEquals("1.2 miles", formatSpokenDistance(2000.0, Units.IMPERIAL))
    }

    @Test
    fun zeroAndTinyDistancesStillRender() {
        assertEquals("0 meters", formatSpokenDistance(0.0, Units.METRIC))
        assertEquals("0 feet", formatSpokenDistance(0.0, Units.IMPERIAL))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — `Unresolved reference 'formatSpokenDistance'`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.roundToInt

private const val FEET_PER_METRE = 3.28084
private const val FEET_PER_MILE = 5280.0
private const val METRES_PER_KM = 1000.0

/**
 * A distance as it should be *spoken*, in the user's units.
 *
 * Distinct from [BearingComputer.formatDistance], which writes "500 m" for a label. These are heard,
 * so units are words: a TTS engine's rendering of "km" is not something to leave to chance.
 *
 * Metres are the internal unit everywhere in this codebase and are spoken nowhere; this is the only
 * place the conversion happens, so iOS inherits it rather than reimplementing it.
 */
fun formatSpokenDistance(
    meters: Double,
    units: Units,
): String =
    when (units) {
        Units.METRIC ->
            if (meters < METRES_PER_KM) {
                "${meters.roundToInt()} meters"
            } else {
                "${formatOneDecimal(meters / METRES_PER_KM)} kilometers"
            }

        Units.IMPERIAL -> {
            val feet = meters * FEET_PER_METRE
            // Switches at a mile, not at a thousand feet: the threshold and the divisor have to be
            // the same quantity or the first mile speaks as a fraction of itself.
            if (feet < FEET_PER_MILE) {
                "${feet.roundToInt()} feet"
            } else {
                "${formatOneDecimal(feet / FEET_PER_MILE)} miles"
            }
        }
    }

/** One decimal place without `String.format`, which is JVM-only and this is commonMain. */
private fun formatOneDecimal(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS

- [ ] **Step 5: Move the five callers and delete the private copy**

In `GpsViewModel.kt`, delete `private fun formatDistanceM(...)` entirely and replace each call
`formatDistanceM(x, units)` with `formatSpokenDistance(x, units)`, adding
`import com.boldexplorer.shared.navigation.formatSpokenDistance`.

- [ ] **Step 6: Verify the app module holds no unit arithmetic**

Run: `rg '3\.28084|5280' app/src/main/kotlin`
Expected: no matches.
Run: `make test && make lint`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
jj describe -m "refactor(nav): spoken distances render in :shared, in whole words, and switch at a mile"
```

---

### Task 2: Along-track primitives the cues need

Three facts the producers require, none of which is currently reachable: where an annotation sits along the trail, how fast the user is going, and how much trail is left.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailPolyline.kt`
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/ProgressTracker.kt` (~line 101)
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/FollowSession.kt`
- Test: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/FollowSessionFactsTest.kt`

**Interfaces:**
- Produces: `TrailPolyline.alongTrackFor(segmentIndex: Int, offsetM: Double): Double`
- Produces: `ProgressTracker.speedMps: Double?`
- Produces: `FollowSession.speedMps: Double?`, `FollowSession.remainingM(alongTrackM: Double): Double`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals

class FollowSessionFactsTest {
    /** ~111.19 m per 0.001° of latitude. A 200 m due-north trail, 20 m spacing. */
    private fun latFor(m: Double) = m / 111_194.9

    private fun northPoints() = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }

    @Test
    fun alongTrackForConvertsASegmentAndOffset() {
        // An annotation is stored as (segment, offset). Its along-track is where it actually sits,
        // and nothing downstream should have to know how that pair is spelled.
        val poly = TrailPolyline(northPoints())

        assertEquals(0.0, poly.alongTrackFor(0, 0.0), 0.5)
        assertEquals(90.0, poly.alongTrackFor(4, 10.0), 0.5, "segment 4 starts at 80 m")
    }

    @Test
    fun remainingIsMeasuredTowardTheEndYouAreWalkingTo() {
        // The whole point of the direction sign: a reverse follow counts down to the start, and
        // "how much further" must mean the same thing to the walker either way.
        val forward = FollowSession(northPoints(), TravelDirection.Forward)
        val reverse = FollowSession(northPoints(), TravelDirection.Reverse)

        assertEquals(150.0, forward.remainingM(50.0), 0.5)
        assertEquals(50.0, reverse.remainingM(50.0), 0.5)
    }

    @Test
    fun remainingNeverGoesNegativePastTheEnd() {
        val forward = FollowSession(northPoints(), TravelDirection.Forward)
        assertEquals(0.0, forward.remainingM(500.0), 0.5, "overshooting the end is still 'arrived'")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — `Unresolved reference 'alongTrackFor'`

- [ ] **Step 3: Write the implementations**

In `TrailPolyline.kt`, beside `offsetInSegmentM`:

```kotlin
/**
 * Along-track distance of a stored `(segmentIndex, offsetM)` pair — the inverse of
 * [offsetInSegmentM].
 *
 * `trail_annotation` stores that pair, and every cue that asks "have I passed this yet" needs it as
 * a single coordinate to compare against the match. Named rather than inlined because
 * `cumulativeM[i] + offset` written at four call sites is four chances to forget the clamp.
 */
fun alongTrackFor(
    segmentIndex: Int,
    offsetM: Double,
): Double = (cumulativeM[segmentIndex.coerceIn(0, size - 1)] + offsetM).coerceIn(0.0, totalLengthM)
```

In `ProgressTracker.kt`, expose the EMA it already keeps (change `private var speedEmaMps` to keep its
backing field and add):

```kotlin
/**
 * Smoothed ground speed, or null before the first fix carrying one.
 *
 * Exposed rather than re-derived: a second speed estimate would drift from the one dead reckoning
 * uses, and there would be no way to tell which was wrong.
 */
val speedMps: Double? get() = speedEmaMps
```

In `FollowSession.kt`:

```kotlin
/** Smoothed ground speed from the tracker, or null before the first fix carrying one. */
val speedMps: Double? get() = tracker.speedMps

/**
 * Trail remaining ahead of [alongTrackM], toward the end being walked to.
 *
 * Direction-signed, so it counts down to the start on a reverse follow. Clamped at zero because
 * overshooting the end is arrival, not negative progress.
 */
fun remainingM(alongTrackM: Double): Double =
    when (direction) {
        TravelDirection.Forward -> polyline.totalLengthM - alongTrackM
        TravelDirection.Reverse -> alongTrackM
    }.coerceAtLeast(0.0)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): along-track, speed and remaining are askable from a follow session"
```

---

### Task 3: The straightness gate

`sagittaOver` already exists and is density-invariant; this is its first production caller. Suppression only — no anchoring, no already-announced set, because a wrong answer costs one skipped beep.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NavigationPolicy.kt`
- Create: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/FollowCuePolicy.kt`
- Test: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/FollowCuePolicyTest.kt`

**Interfaces:**
- Consumes: `TrailPolyline.sagittaOver(alongTrackM, lookaheadM)` (exists)
- Produces: `FollowCuePolicy.isStraightAhead(polyline: TrailPolyline, alongTrackM: Double): Boolean`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowCuePolicyTest {
    private fun latFor(m: Double) = m / 111_194.9

    private fun lonFor(m: Double) = m / 111_194.9

    @Test
    fun aStraightStretchIsStraight() {
        val poly = TrailPolyline((0..20).map { LatLng(latFor(it * 20.0), 0.0) })
        assertTrue(FollowCuePolicy.isStraightAhead(poly, 100.0))
    }

    @Test
    fun aCornerAheadIsNotStraight() {
        // 200 m north, then 200 m east. Standing 40 m before the corner, the trail ahead bends.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), lonFor(it * 20.0)) }
        val poly = TrailPolyline(north + east)

        assertFalse(FollowCuePolicy.isStraightAhead(poly, 160.0), "a corner 40 m ahead is not straight")
    }

    @Test
    fun straightnessDoesNotDependOnRecordingDensity() {
        // The trap the whole redesign exists to avoid: the same path, recorded twice as densely,
        // must not answer differently.
        fun corner(spacingM: Double): TrailPolyline {
            val n = (200.0 / spacingM).toInt()
            val north = (0..n).map { LatLng(latFor(it * spacingM), 0.0) }
            val east = (1..n).map { LatLng(latFor(200.0), lonFor(it * spacingM)) }
            return TrailPolyline(north + east)
        }

        assertEquals(
            FollowCuePolicy.isStraightAhead(corner(2.0), 160.0),
            FollowCuePolicy.isStraightAhead(corner(20.0), 160.0),
            "same corner, different densities, different answers",
        )
    }
}
```

Add `import kotlin.test.assertEquals` to the imports.

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — `Unresolved reference 'FollowCuePolicy'`

- [ ] **Step 3: Write the constants and the policy**

In `NavigationPolicy.kt`, under a new banner comment `// ── S6: follow cues ──`:

```kotlin
/** Progress earcon cadence — frequent enough to read as continuous presence. */
const val PROGRESS_EARCON_INTERVAL_MS = 5_000L

/** Progress speech cadence — one short fact, twice the rate of ordinary guidance. */
const val PROGRESS_SPEECH_INTERVAL_MS = 15_000L

/** Skip the progress cue when anything else spoke this recently. */
const val PROGRESS_YIELD_MS = 5_000L

/** Reaction budget for an annotation approach: hear it, decide, and stop. */
const val ANNOTATION_LEAD_SECONDS = 8.0

/** Floors the lead when stationary. */
const val ANNOTATION_LEAD_MIN_M = 10.0

/** Caps the lead when moving fast. */
const val ANNOTATION_LEAD_MAX_M = 40.0

/** Beyond this from the trail, an annotation is phrased as an aside rather than plainly. */
const val ANNOTATION_ASIDE_M = 25.0

/** Window `sagittaOver` is asked about when deciding whether the trail ahead is straight. */
const val STRAIGHT_LOOKAHEAD_M = 40.0

/** Bulge over that window above which the trail ahead is not called straight. */
const val STRAIGHT_SAGITTA_M = 4.0

/** Non-`Matched` fixes before a lost match is announced; stops flap chatter. */
const val MATCH_LOST_SUSTAIN = 3
```

Create `FollowCuePolicy.kt`:

```kotlin
package com.boldexplorer.shared.navigation

/**
 * Whether a cue should be offered at all, as against what it should say (ADR 0001, S6).
 *
 * The straightness test is suppression only, which is why it needs none of S8's apparatus — no
 * lookahead derived from a time budget, no anchoring to the curvature maximum, no already-announced
 * set. Being wrong on one fix costs one skipped beep, and skipping is idempotent. S8 will answer a
 * harder question with the same primitive.
 */
object FollowCuePolicy {
    /**
     * Whether the trail ahead of [alongTrackM] is straight enough for a progress cue.
     *
     * Sagitta rather than accumulated turn: summing per-vertex `|Δbearing|` grows without bound
     * with recording density, and signed net turn reads zero across an S-bend that is plainly not
     * straight.
     */
    fun isStraightAhead(
        polyline: TrailPolyline,
        alongTrackM: Double,
    ): Boolean =
        polyline.sagittaOver(alongTrackM, NavigationPolicy.STRAIGHT_LOOKAHEAD_M) <=
            NavigationPolicy.STRAIGHT_SAGITTA_M
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): the follow can ask whether the trail ahead is straight"
```

---

### Task 4: The progress cue producer

Returns a cue; speaks nothing. This is the seam the spec asks for — a second producer (S8's bend cue) is added beside it, not inside it.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/ProgressCue.kt`
- Test: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/ProgressCueProducerTest.kt`

**Interfaces:**
- Consumes: `FollowSession.remainingM`, `FollowCuePolicy.isStraightAhead`, `formatSpokenDistance`
- Produces: `data class ProgressCue(val earcon: Boolean, val speech: String?)`;
  `class ProgressCueProducer { fun onFix(nowMs: Long, polyline: TrailPolyline, alongTrackM: Double, remainingM: Double, units: Units, lastSpokeAtMs: Long): ProgressCue }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressCueProducerTest {
    private fun latFor(m: Double) = m / 111_194.9

    private val straight = TrailPolyline((0..30).map { LatLng(latFor(it * 20.0), 0.0) })

    private fun produce(
        producer: ProgressCueProducer,
        nowMs: Long,
        remainingM: Double = 400.0,
        lastSpokeAtMs: Long = Long.MIN_VALUE,
    ) = producer.onFix(nowMs, straight, alongTrackM = 100.0, remainingM = remainingM,
        units = Units.IMPERIAL, lastSpokeAtMs = lastSpokeAtMs)

    @Test
    fun theEarconRunsFasterThanTheSpeech() {
        val producer = ProgressCueProducer()

        val first = produce(producer, 0L)
        assertTrue(first.earcon, "the first fix establishes presence")
        assertNotNull(first.speech)

        assertFalse(produce(producer, 2_000L).earcon, "inside the earcon interval")
        assertTrue(produce(producer, 5_000L).earcon)
        assertNull(produce(producer, 5_000L).speech, "speech is slower than the beep")
        assertNotNull(produce(producer, 15_000L).speech)
    }

    @Test
    fun theSpeechIsRemainingDistanceInTheUsersUnits() {
        // Metres are internal and are spoken nowhere; the default user is on imperial.
        val cue = produce(ProgressCueProducer(), 0L, remainingM = 2000.0)
        assertEquals("1.2 miles to go", cue.speech)
    }

    @Test
    fun itYieldsToAnythingThatJustSpoke() {
        // The progress cue is the most frequent thing in the mix and must never talk over an alert.
        val producer = ProgressCueProducer()
        val cue = produce(producer, 20_000L, lastSpokeAtMs = 18_000L)

        assertNull(cue.speech, "something spoke 2 s ago")
        assertTrue(cue.earcon, "the beep still carries presence — it does not collide with speech")
    }

    @Test
    fun itStaysQuietWhereTheTrailIsNotStraight() {
        // A corner deserves the bend cue S8 will add, not a distance readout.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), it * 20.0 / 111_194.9) }
        val corner = TrailPolyline(north + east)
        val producer = ProgressCueProducer()

        val cue = producer.onFix(0L, corner, alongTrackM = 160.0, remainingM = 200.0,
            units = Units.IMPERIAL, lastSpokeAtMs = Long.MIN_VALUE)

        assertNull(cue.speech, "a corner is 40 m ahead")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — `Unresolved reference 'ProgressCueProducer'`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units

/**
 * What the follow should emit on this fix by way of progress.
 *
 * A value, not an effect. The producer decides; `GpsViewModel` speaks and beeps. That split is what
 * lets these decisions be JVM-tested and inherited by iOS, and it is the seam the next producer —
 * S8's "300 feet until a right turn" — is added beside rather than inside.
 */
data class ProgressCue(
    val earcon: Boolean,
    val speech: String?,
)

/**
 * The periodic "you are still following, and this much is left" cue (ADR 0001, S6).
 *
 * Replaces "Checkpoint 12 of 40", which reported an index into a vertex array at whatever rate the
 * trail happened to be recorded at. Remaining distance answers the question the walker actually has,
 * and answers it the same way whatever the recording density.
 */
class ProgressCueProducer {
    private var lastEarconAtMs = Long.MIN_VALUE
    private var lastSpeechAtMs = Long.MIN_VALUE

    fun onFix(
        nowMs: Long,
        polyline: TrailPolyline,
        alongTrackM: Double,
        remainingM: Double,
        units: Units,
        lastSpokeAtMs: Long,
    ): ProgressCue {
        val earcon = nowMs - lastEarconAtMs >= NavigationPolicy.PROGRESS_EARCON_INTERVAL_MS
        if (earcon) lastEarconAtMs = nowMs

        // The beep carries presence and never collides with speech, so it is deliberately not
        // subject to either suppression below.
        val due = nowMs - lastSpeechAtMs >= NavigationPolicy.PROGRESS_SPEECH_INTERVAL_MS
        val yielding = nowMs - lastSpokeAtMs < NavigationPolicy.PROGRESS_YIELD_MS
        val straight = FollowCuePolicy.isStraightAhead(polyline, alongTrackM)
        val speech =
            if (due && !yielding && straight) {
                lastSpeechAtMs = nowMs
                "${formatSpokenDistance(remainingM, units)} to go"
            } else {
                null
            }

        return ProgressCue(earcon = earcon, speech = speech)
    }

    /** Forget the cadence — a new follow starts its own rhythm rather than inheriting one. */
    fun reset() {
        lastEarconAtMs = Long.MIN_VALUE
        lastSpeechAtMs = Long.MIN_VALUE
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): a progress cue that says how much further, and yields"
```

---

### Task 5: Annotation approach cues

Closes the regression S5b took deliberately. The lead is a time budget because these are things a walker may want to *stop at*.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/AnnotationCue.kt`
- Test: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/AnnotationCueProducerTest.kt`

**Interfaces:**
- Produces: `data class RouteAnnotation(val id: Long, val name: String, val alongTrackM: Double, val signedCrossTrackM: Double)`
- Produces: `class AnnotationCueProducer(private val annotations: List<RouteAnnotation>, private val direction: TravelDirection) { fun onFix(alongTrackM: Double, speedMps: Double?, units: Units): List<String> }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationCueProducerTest {
    private val bench = RouteAnnotation(1L, "Bench", alongTrackM = 100.0, signedCrossTrackM = 6.0)
    private val pavilion = RouteAnnotation(2L, "Pavilion", alongTrackM = 300.0, signedCrossTrackM = -80.0)

    private fun producer(vararg a: RouteAnnotation) =
        AnnotationCueProducer(a.toList(), TravelDirection.Forward)

    @Test
    fun itAnnouncesOnApproachNotOnArrival() {
        // Being told you are level with the bench means you are past it by the time you react.
        val p = producer(bench)

        assertTrue(p.onFix(60.0, speedMps = 1.3, units = Units.IMPERIAL).isEmpty(), "still 40 m off")
        val cues = p.onFix(90.0, speedMps = 1.3, units = Units.IMPERIAL)
        assertEquals(1, cues.size)
        assertTrue(cues.single().startsWith("Bench ahead"), "got ${cues.single()}")
    }

    @Test
    fun itSaysWhichSideAndHowFar() {
        val cues = producer(bench).onFix(92.0, speedMps = 1.3, units = Units.IMPERIAL)
        assertEquals("Bench ahead, 20 feet, on your right", cues.single())
    }

    @Test
    fun aDistantOneIsPhrasedAsAnAside() {
        // A phrasing boundary, not a silence boundary: mis-tuning changes how it sounds, never
        // whether the walker learns it is there.
        val cues = producer(pavilion).onFix(292.0, speedMps = 1.3, units = Units.IMPERIAL)
        assertTrue(cues.single().startsWith("Off to your left, 262 feet: Pavilion"), "got ${cues.single()}")
    }

    @Test
    fun itAnnouncesEachAnnotationOnce() {
        // Without this, GPS jitter around the lead boundary re-announces the same mark every fix.
        val p = producer(bench)
        p.onFix(90.0, 1.3, Units.IMPERIAL)

        assertTrue(p.onFix(91.0, 1.3, Units.IMPERIAL).isEmpty())
        assertTrue(p.onFix(89.0, 1.3, Units.IMPERIAL).isEmpty(), "jitter backwards must not re-fire")
    }

    @Test
    fun theLeadGrowsWithSpeedAndIsClamped() {
        // Lead time is what the walker experiences; a runner needs more warning in metres.
        assertTrue(producer(bench).onFix(70.0, speedMps = 4.0, units = Units.IMPERIAL).isNotEmpty(),
            "at 4 m/s, 8 s of lead is past the 40 m cap, so 30 m out should fire")
        assertTrue(producer(bench).onFix(70.0, speedMps = 0.0, units = Units.IMPERIAL).isEmpty(),
            "stationary falls back to the 10 m floor, not to silence forever")
    }

    @Test
    fun aReverseFollowApproachesFromTheOtherSide() {
        val p = AnnotationCueProducer(listOf(bench), TravelDirection.Reverse)

        assertTrue(p.onFix(140.0, 1.3, Units.IMPERIAL).isEmpty(), "40 m before it, walking backwards")
        assertTrue(p.onFix(110.0, 1.3, Units.IMPERIAL).isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — `Unresolved reference 'RouteAnnotation'`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.abs

/**
 * An annotation placed on the trail being followed (ADR 0001, S5b/S6).
 *
 * [alongTrackM] comes from `TrailPolyline.alongTrackFor` over the stored `(segment, offset)` pair;
 * [signedCrossTrackM] is positive to the right of recorded order, so the *walker's* left and right
 * follow from the travel direction rather than from the recording.
 */
data class RouteAnnotation(
    val id: Long,
    val name: String,
    val alongTrackM: Double,
    val signedCrossTrackM: Double,
)

/**
 * Announces annotations as the walker comes up on them.
 *
 * Announced on approach rather than on arrival: these are benches, gates and parking areas — things
 * a walker may want to stop at — and a cue that arrives as you draw level has already cost you the
 * chance. The lead is a *time* budget because lead time is what the user experiences, and walking
 * speed varies with terrain and tiredness far more than the geometry does.
 */
class AnnotationCueProducer(
    private val annotations: List<RouteAnnotation>,
    private val direction: TravelDirection,
) {
    private val announced = mutableSetOf<Long>()

    fun onFix(
        alongTrackM: Double,
        speedMps: Double?,
        units: Units,
    ): List<String> {
        val leadM =
            ((speedMps ?: 0.0) * NavigationPolicy.ANNOTATION_LEAD_SECONDS)
                .coerceIn(NavigationPolicy.ANNOTATION_LEAD_MIN_M, NavigationPolicy.ANNOTATION_LEAD_MAX_M)

        return annotations
            .filter { it.id !in announced }
            .filter { withinLead(it, alongTrackM, leadM) }
            .sortedBy { aheadM(it, alongTrackM) }
            .map { annotation ->
                announced += annotation.id
                phrase(annotation, units)
            }
    }

    /** Distance still to walk before drawing level, negative once past. Direction-signed. */
    private fun aheadM(
        annotation: RouteAnnotation,
        alongTrackM: Double,
    ): Double = (annotation.alongTrackM - alongTrackM) * direction.sign

    private fun withinLead(
        annotation: RouteAnnotation,
        alongTrackM: Double,
        leadM: Double,
    ): Boolean {
        val ahead = aheadM(annotation, alongTrackM)
        // Fires from the lead distance right through to level. Past that it is Task 6's problem.
        return ahead in 0.0..leadM
    }

    private fun phrase(
        annotation: RouteAnnotation,
        units: Units,
    ): String {
        val distance = formatSpokenDistance(abs(annotation.signedCrossTrackM), units)
        // Right of recorded order is the walker's left on a reverse follow.
        val side = if (annotation.signedCrossTrackM * direction.sign >= 0.0) "right" else "left"
        return if (abs(annotation.signedCrossTrackM) > NavigationPolicy.ANNOTATION_ASIDE_M) {
            "Off to your $side, $distance: ${annotation.name}"
        } else {
            "${annotation.name} ahead, $distance, on your $side"
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): annotations announce on approach, with side and distance"
```

---

### Task 6: Marks passed during a dropout

Silence is ambiguous — it reads identically to "there was nothing there". Announce late and hedged, but only when the rejoin is trustworthy.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/AnnotationCue.kt`
- Modify: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/AnnotationCueProducerTest.kt`

**Interfaces:**
- Produces: `AnnotationCueProducer.onReacquired(fromAlongTrackM: Double, toAlongTrackM: Double, predictionErrorM: Double?, units: Units): List<String>`

- [ ] **Step 1: Write the failing test**

Append to `AnnotationCueProducerTest`:

```kotlin
    @Test
    fun aMarkPassedDuringADropoutIsAnnouncedLateAndHedged() {
        // The precedent is TrailComplete(hedged): say it, and say that you are unsure — rather than
        // asserting, or saying nothing, which reads the same as "there was nothing there".
        val p = producer(bench)

        val cues = p.onReacquired(fromAlongTrackM = 40.0, toAlongTrackM = 180.0,
            predictionErrorM = null, units = Units.IMPERIAL)

        assertEquals(1, cues.size)
        assertTrue(cues.single().startsWith("You passed Bench"), "got ${cues.single()}")
        assertTrue(cues.single().contains("back"), "distance behind is the actionable part")
    }

    @Test
    fun anUntrustworthyRejoinClaimsNothing() {
        // A big prediction error means we may have rejoined somewhere else entirely, and "you
        // passed the bench" may simply be false. A confident false statement about position is the
        // worst thing this app can say.
        val p = producer(bench)

        val cues = p.onReacquired(40.0, 180.0, predictionErrorM = 120.0, units = Units.IMPERIAL)

        assertTrue(cues.isEmpty())
    }

    @Test
    fun aMarkAnnouncedNormallyIsNotAnnouncedAgainOnReacquisition() {
        val p = producer(bench)
        p.onFix(90.0, 1.3, Units.IMPERIAL)

        assertTrue(p.onReacquired(40.0, 180.0, null, Units.IMPERIAL).isEmpty())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — `Unresolved reference 'onReacquired'`

- [ ] **Step 3: Write the implementation**

Add to `NavigationPolicy.kt`:

```kotlin
/**
 * Prediction error above which a rejoin is not trusted to imply what was passed during the gap.
 *
 * A rejoin elsewhere on the trail makes "you passed the bench" false rather than late.
 */
const val REJOIN_TRUSTED_ERROR_M = 30.0
```

Add to `AnnotationCueProducer`:

```kotlin
/**
 * Marks crossed while the match was lost, inferred from the along-track jump at reacquisition.
 *
 * Hedged, because the app did not see the crossing — it is reading it off the jump. Gated on the
 * rejoin being believable: [predictionErrorM] is non-null exactly when the reacquisition landed
 * somewhere unpredicted, and a rejoin elsewhere on the trail would make these claims false rather
 * than merely late.
 */
fun onReacquired(
    fromAlongTrackM: Double,
    toAlongTrackM: Double,
    predictionErrorM: Double?,
    units: Units,
): List<String> {
    if (predictionErrorM != null && predictionErrorM > NavigationPolicy.REJOIN_TRUSTED_ERROR_M) {
        return emptyList()
    }
    val lo = minOf(fromAlongTrackM, toAlongTrackM)
    val hi = maxOf(fromAlongTrackM, toAlongTrackM)

    return annotations
        .filter { it.id !in announced && it.alongTrackM in lo..hi }
        .sortedBy { aheadM(it, toAlongTrackM) }
        .map { annotation ->
            announced += annotation.id
            val behindM = abs(toAlongTrackM - annotation.alongTrackM)
            val side = if (annotation.signedCrossTrackM * direction.sign >= 0.0) "right" else "left"
            "You passed ${annotation.name}, ${formatSpokenDistance(behindM, units)} back, on your $side"
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): a mark passed in a dropout is said late and hedged, or not claimed at all"
```

---

### Task 7: Match lost and reacquired

Sound carries the continuous state; speech carries the change. There is field precedent for why unchanged sound is wrong: an accuracy gate once froze distance and bearing while the earcon carried on.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/MatchStateCue.kt`
- Test: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/MatchStateCueProducerTest.kt`

**Interfaces:**
- Produces: `sealed interface MatchStateCue { object Lost; object Reacquired }`;
  `class MatchStateCueProducer { fun onFix(state: MatchState): MatchStateCue? ; val isLost: Boolean }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchStateCueProducerTest {
    @Test
    fun aLossIsAnnouncedOnlyOnceItIsSustained() {
        // A Matched -> Uncertain -> Matched flap must not chatter.
        val p = MatchStateCueProducer()
        p.onFix(MatchState.Matched)

        assertNull(p.onFix(MatchState.Uncertain), "one bad fix is not a loss")
        assertNull(p.onFix(MatchState.Uncertain))
        assertEquals(MatchStateCue.Lost, p.onFix(MatchState.Uncertain), "three sustains it")
        assertTrue(p.isLost)
    }

    @Test
    fun aFlapNeverAnnouncesAnything() {
        val p = MatchStateCueProducer()
        repeat(5) {
            p.onFix(MatchState.Matched)
            assertNull(p.onFix(MatchState.Uncertain))
        }
        assertFalse(p.isLost)
    }

    @Test
    fun recoveryIsAnnouncedOnceAndOnlyAfterALoss() {
        val p = MatchStateCueProducer()
        repeat(3) { p.onFix(MatchState.Lost) }
        assertTrue(p.isLost)

        assertEquals(MatchStateCue.Reacquired, p.onFix(MatchState.Matched))
        assertNull(p.onFix(MatchState.Matched), "still matched is not news")
        assertFalse(p.isLost)
    }

    @Test
    fun aFollowThatStartsMatchedSaysNothing() {
        assertNull(MatchStateCueProducer().onFix(MatchState.Matched))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — `Unresolved reference 'MatchStateCueProducer'`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.boldexplorer.shared.navigation

/** A change in whether the follow knows where the walker is (ADR 0001, S6). */
sealed interface MatchStateCue {
    object Lost : MatchStateCue

    object Reacquired : MatchStateCue
}

/**
 * Turns per-fix match states into the two transitions worth saying out loud.
 *
 * [isLost] is the continuous state the earcon reads, so sound says "I do not know where you are"
 * for as long as that is true. Speech marks only the change. Both halves matter: an unchanged
 * earcon during a lost match reproduces a failure already met in the field, where audio implied
 * everything was fine while the app had stopped knowing anything.
 */
class MatchStateCueProducer {
    private var consecutiveNotMatched = 0

    /** Whether the match is currently considered lost — the state the earcon renders. */
    var isLost: Boolean = false
        private set

    fun onFix(state: MatchState): MatchStateCue? {
        if (state == MatchState.Matched) {
            consecutiveNotMatched = 0
            if (isLost) {
                isLost = false
                return MatchStateCue.Reacquired
            }
            return null
        }

        consecutiveNotMatched++
        if (!isLost && consecutiveNotMatched >= NavigationPolicy.MATCH_LOST_SUSTAIN) {
            isLost = true
            return MatchStateCue.Lost
        }
        return null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): losing and regaining the trail are said once each, not every fix"
```

---

### Task 8: The event vocabulary

`WaypointReached` dies. Track points say nothing.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailFollower.kt` (~lines 36–52, ~295–318)
- Modify: `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt` (~1243–1330)
- Modify: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/TrailFollowerTest.kt`, `TrailCompletionTest.kt`

**Interfaces:**
- Produces: `TrailFollowerEvent.TrailComplete(hedged)` (unchanged) — `WaypointReached` removed.

- [ ] **Step 1: Write the failing test**

Add to `TrailFollowerTest.kt`:

```kotlin
    @Test
    fun passingATrackPointEmitsNothing() {
        // The firehose: every 10 m of recorded trail used to speak "Checkpoint N of M", a report of
        // an index into a vertex array at whatever rate the trail happened to be recorded at.
        val f = follower(trackPointTrail())

        val events = (0..5).mapNotNull { f.onLocationUpdate(LatLng(latFor(it * 20.0), 0.0)) }

        assertTrue(
            events.all { it is TrailFollowerEvent.TrailComplete },
            "track points must be silent, got $events",
        )
    }
```

Use the file's existing `follower(...)` / `latFor(...)` helpers; if it builds followers inline,
follow the surrounding style rather than adding helpers.

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — a `WaypointReached` is emitted.

- [ ] **Step 3: Remove the event and its emission**

In `TrailFollower.kt`, delete the `WaypointReached` data class from `TrailFollowerEvent` and, at the
emission site (~line 304), return `null` where it previously returned `WaypointReached(...)`, keeping
`emitCallback()` and the `TrailComplete` branch exactly as they are.

Delete `OutputKind.WAYPOINT_REACHED` only if nothing else uses it — check with
`rg 'WAYPOINT_REACHED' --type kotlin` first, and leave it if the audio log schema references it.

- [ ] **Step 4: Update the consumer**

In `GpsViewModel.kt`, delete the `is TrailFollowerEvent.WaypointReached ->` branch and the
`buildTrailAdvanceAnnouncement` function. **Do not** re-home the `resetThrottle(sample)` call it
contained — dropping it is the point (Task 9 shadows the consequence).

- [ ] **Step 5: Run tests and fix the fallout**

Run: `make test`
Expected: several existing tests referencing `WaypointReached` fail to compile. Rewrite each to
assert the new behaviour — silence for a track point, `TrailComplete` at the end — rather than
deleting them.

- [ ] **Step 6: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): track points stop announcing themselves"
```

---

### Task 9: Shadow the grace removal

Strictly. This task must not change when any alert is *spoken*.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailGuidanceCoordinator.kt` (lines ~320, ~469)
- Test: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/GraceShadowTest.kt`

**Interfaces:**
- Produces: `OffTrailEvaluation.suppressedByGrace: Boolean`, `BacktrackEvaluation.suppressedByGrace: Boolean`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Grace stops being an early return and becomes a flag (ADR 0001, S6).
 *
 * The point is measurement without exposure: the evaluators run every fix and emit a disposition,
 * so the next walk's log says what dropping grace *would* have done — while what is spoken does not
 * change at all. The loose alternative, shipping it live and reading the log afterwards, risks
 * discovering a false-positive storm several kilometres from home, alone.
 */
class GraceShadowTest {
    @Test
    fun anEvaluationIsProducedInsideGraceRatherThanSkipped() {
        val c = coordinatorInGrace()
        val eval = c.evaluateOffTrail(fixWellOffTrail())

        assertNotNull(eval, "inside grace the evaluator used to return null and log nothing")
        assertTrue(eval.suppressedByGrace)
        assertFalse(eval.fired, "grace still gates what is spoken — that must not change")
    }

    @Test
    fun theDispositionIsRecordedInsideGrace() {
        val eval = assertNotNull(coordinatorInGrace().evaluateOffTrail(fixWellOffTrail()))
        assertTrue(eval.disposition.isNotBlank(), "the log line is the whole deliverable")
    }
}
```

Build `coordinatorInGrace()` and `fixWellOffTrail()` from the helpers already in
`OffTrailCrossTrackTest.kt` — arm grace with `resetThrottle(sample)` and evaluate at the same
timestamp. Reuse rather than reinvent them.

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — the evaluator returns null inside grace.

- [ ] **Step 3: Replace the early returns with a flag**

At line ~320, change `if (sample.timestamp < offTrailGraceUntilMs) return null` to compute
`val suppressedByGrace = sample.timestamp < offTrailGraceUntilMs`, let the rest of the function run
unchanged, and add `&& !suppressedByGrace` to the `fired` expression. Carry `suppressedByGrace` into
`OffTrailEvaluation`. Do the same at line ~469 for backtrack.

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS. Existing off-trail and backtrack tests must still pass unchanged — if any now fires
where it did not before, the flag is wired wrongly.

- [ ] **Step 5: Write the failing test for the audible override**

```kotlin
    @Test
    fun theDebugSwitchLetsTheShadowBeHeard() {
        // Measuring from a log tells you the count; it does not tell you what the walk sounds like.
        // The owner asked to be able to hear it, having judged the risk acceptable — so the switch
        // exists, defaults off, and changes nothing else.
        val c = coordinatorInGrace()
        c.shadowAlertsAudible = true

        val eval = assertNotNull(c.evaluateOffTrail(fixWellOffTrail()))

        assertTrue(eval.suppressedByGrace, "the shadow still reports that grace would have muted it")
        assertTrue(eval.fired, "but it is spoken, because the switch is on")
    }

    @Test
    fun theSwitchDefaultsOff() {
        assertFalse(TrailGuidanceCoordinator().shadowAlertsAudible)
    }
```

- [ ] **Step 6: Add the override to the coordinator**

```kotlin
/**
 * Whether alerts grace would have muted are spoken anyway (debug; ADR 0001, S6).
 *
 * Default false, which is the strict shadow: the evaluation and its disposition are recorded, and
 * nothing new is said. Turning it on is how the owner hears what dropping grace would sound like
 * before the change is made for real — a count in a log does not convey whether a walk is livable.
 * `suppressedByGrace` still reports what grace *would* have done, so the log stays readable either
 * way.
 */
var shadowAlertsAudible: Boolean = false
```

Then in both evaluators, `fired` becomes `... && (!suppressedByGrace || shadowAlertsAudible)`.

- [ ] **Step 7: Add the switch, following the existing shadow toggle**

`ShadowMatchMonitor` (`app/src/main/kotlin/com/boldexplorer/audio/ShadowMatchMonitor.kt`) is the
pattern: a `@Singleton` holding a `MutableStateFlow<Boolean>` with a `setEnabled`, read by
`DebugViewModel`, rendered as a `Switch` in `DebugScreen`. Create `ShadowAlertsMonitor` beside it
with `audible: StateFlow<Boolean>` defaulting to **false** and `setAudible(Boolean)`, expose it
through `DebugViewModel`, and add the switch to `DebugScreen` in the same card as trail-match
logging.

Label it **"Speak shadowed alerts"**, with the explanation as *visible text* rather than a
`contentDescription` — the existing card does this deliberately, so the reason reaches everyone and
the switch's own state is left to be announced natively:

> "Off-trail and wrong-way alerts are currently held back by a grace window that the old
> per-checkpoint cue kept re-arming. With this on, they are spoken as they will be once that window
> is removed. Expect more of them."

Mirror it into the coordinator from `GpsViewModel` — collect `shadowAlertsMonitor.audible` and
assign `guidanceCoordinator.shadowAlertsAudible`. The coordinator is `:shared` and must not learn
about Hilt or Android.

- [ ] **Step 8: Run the tests**

Run: `make test-shared`
Expected: PASS, including the existing off-trail and backtrack suites unchanged.

- [ ] **Step 9: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): grace becomes a flag, with a debug switch to hear what dropping it sounds like"
```

---

### Task 10: Off-trail gated on `isRecorded`

A hand-built route's polyline between waypoints is invented; cross-track against it is not evidence of leaving the path.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailGuidanceCoordinator.kt`
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/FollowSession.kt`
- Modify: `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt` (`followTrailById`)
- Test: `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/OffTrailHandBuiltRouteTest.kt`

**Interfaces:**
- Consumes: `WaypointRepository`'s recorded/hand-built distinction, surfaced at follow start
- Produces: `FollowSession(points, direction, tuning, isRecorded: Boolean = true)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OffTrailHandBuiltRouteTest {
    @Test
    fun aHandBuiltRouteDoesNotCallYouOffTrail() {
        // Nobody walked the straight line between two hand-placed waypoints, so departing from it
        // is evidence that the path is not a line — not that the walker left the path.
        val c = coordinatorFor(isRecorded = false)
        repeat(6) { c.onFixAndEvaluate(fixWellOffTrail()) }

        assertFalse(c.lastOffTrailEvaluation!!.fired)
    }

    @Test
    fun aRecordedTrailStillDoes() {
        // The polyline *is* the walked path here, so cross-track means what the detector thinks.
        val c = coordinatorFor(isRecorded = true)
        repeat(6) { c.onFixAndEvaluate(fixWellOffTrail()) }

        assertTrue(c.lastOffTrailEvaluation!!.fired)
    }
}
```

Build the helpers from `OffTrailCrossTrackTest.kt`'s existing fixtures.

- [ ] **Step 2: Run test to verify it fails**

Run: `make test-shared`
Expected: FAIL — `isRecorded` is not a parameter.

- [ ] **Step 3: Thread the flag through**

Add `val isRecorded: Boolean = true` to `FollowSession`'s constructor, defaulting true so existing
callers and tests keep today's behaviour. In the off-trail evaluator, return early — genuinely early,
this one is not a shadow — when `session?.isRecorded == false`, with disposition
`"bail:hand_built_route"`. In `GpsViewModel.followTrailById`, pass whether the trail has track points
(`waypointRepo` already answers this; reuse the query S5b's `attach` uses rather than adding one).

- [ ] **Step 4: Run test to verify it passes**

Run: `make test-shared`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
make test && make lint
jj describe -m "feat(nav): a hand-built route's invented geometry cannot put you off trail"
```

---

### Task 11: Wire the cues to sound and speech

The last task, and the only one that produces effects. Everything above returns values.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/audio/AudioCueEvent.kt`
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/audio/AudioCueScheduler.kt`
- Modify: `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/boldexplorer/shared/output/OutputEvent.kt`

**Interfaces:**
- Consumes: `ProgressCue`, `AnnotationCueProducer`, `MatchStateCueProducer`, `FollowSession.speedMps`
- Produces: `AudioCueEvent.Progress(lost: Boolean)`; `OutputKind.PROGRESS`, `ANNOTATION_PASSED`, `MATCH_STATE`

- [ ] **Step 1: Add the earcon event and output kinds**

In `AudioCueEvent.kt`:

```kotlin
/**
 * The periodic "still following" beep. [lost] renders it differently, so the sound never implies
 * confidence the tracker does not have.
 */
data class Progress(val lost: Boolean) : AudioCueEvent()
```

In `OutputEvent.kt`, add `PROGRESS`, `ANNOTATION_PASSED`, `MATCH_STATE` to `OutputKind`.

In `AudioCueScheduler.kt`, add beside `emitTrailComplete`:

```kotlin
/** Emit the periodic progress beep; [lost] selects the "I do not know where you are" variant. */
suspend fun emitProgress(lost: Boolean) {
    _events.emit(AudioCueEvent.Progress(lost))
}
```

- [ ] **Step 2: Delete the dead waypoint earcon**

`emitWaypointApproach` has no production callers — its doc comment claims `GpsViewModel` calls it on
`WaypointReached`, which was never true and is now impossible. Verify with
`rg 'emitWaypointApproach' --type kotlin`, then delete it and `AudioCueEvent.WaypointApproach`.

- [ ] **Step 3: Hold the producers on the follow session**

In `GpsViewModel`, alongside the existing follower fields, add `progressCues`, `annotationCues` and
`matchStateCues`, constructed when a follow starts (`followTrailById`) and dropped when it stops.
Build `RouteAnnotation`s there from `annotationRepo.forTrail(trailId)` and
`polyline.alongTrackFor(segmentIndex, offsetM)`, taking `signedCrossTrackM` from projecting each
waypoint onto the polyline.

- [ ] **Step 4: Drive them from the fix handler**

In the `null ->` branch of the event handler — the ordinary-fix path — after
`computeGuidance`, in this order: match-state cue, annotation cues, then the progress cue last so it
sees `lastSpokeAtMs` updated by the other two. Speak each via the existing `announce(...)` with its
new `OutputKind`, and call `scheduler.emitProgress(matchStateCues.isLost)` when
`ProgressCue.earcon` is true.

- [ ] **Step 5: Verify the whole suite and the constraint**

Run: `make test && make lint`
Expected: both BUILD SUCCESSFUL.
Run: `rg '3\.28084|5280' app/src/main/kotlin`
Expected: no matches.

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat(nav): the follow speaks progress, annotations and match state"
```

---

## Self-Review Notes

**Spec coverage.** Five cues → Tasks 4, 5, 7, 11; units rule → Task 1 plus the global constraint;
producer seam → Task 4's `ProgressCue` return type; sagitta gate → Task 3; time-budgeted lead →
Task 5; hedged dropout gated on `predictionErrorM` → Task 6; heartbeat dropped → Task 8 (removal)
and Task 9 (shadow); `isRecorded` gate → Task 10; constants table → Task 3.

**Deliberately not covered**, per the spec: the fraction phrasing ("three quarters of the way"), the
burst summary for long dropouts, and any registry or priority scheme for multiple cue producers.
Each is named in the ADR as later work, and building any of them now would be inventing a shape
nobody has seen.

**Field verification is the real gate.** Nothing here proves the constants are right. After merge,
one walk with the shadow dispositions decides whether grace can actually go, and that is a separate
change. Expect the 2026-08-16 spurious "turn around" to still be present — it is a matching defect,
and S6 does not touch matching.
