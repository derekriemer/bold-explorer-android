# Bold Explorer — Product & Design Specification

*Written against the Android implementation. The `:shared` KMP module is consumed directly by both the Android app and the planned iOS app — no logic is re-implemented on iOS.*

---

## 1. Product Overview

Bold Explorer is an offline trail-navigation app built for a blind user. **Audio is the primary interface.** The screen is secondary and all UI must be fully operable with a screen reader active.

Core experience:

1. User records waypoints and organises them into trails and collections.
2. User selects a trail or collection, puts the phone in a pocket, and walks.
3. Continuous audio cues (directional beacon, alignment ping, TTS announcements) guide navigation without requiring the user to look at the screen.
4. All audio runs through a single, testable scheduler so platform audio engines can be swapped without changing navigation logic.

---

## 2. Architecture

### 2.1 Shared / Platform split

```
shared/          ← pure Kotlin Multiplatform — zero Android or iOS imports
  geo/           ← math: haversine, bearing, bbox
  model/         ← domain data classes
  repository/    ← interfaces only
  navigation/    ← TrailFollower, BearingComputer, CollectionExplorer
  audio/         ← AudioCueScheduler, AudioCueEvent, AudioCueConfig
  settings/      ← AppSettings, SettingsMigration

  KMP targets: jvm (tests), androidTarget, iosArm64, iosSimulatorArm64, iosX64
  iOS artifact:  BoldExplorerShared.xcframework  (make xcframework on macOS)

platform layer   ← Android: Kotlin (:app)  |  iOS: Swift (separate Xcode project)
  database       ← SQLite impl  (SQLDelight Android driver  |  SQLDelight Native driver)
  location       ← GPS provider  (FusedLocationProviderClient  |  CLLocationManager)
  compass        ← sensor provider  (SensorManager  |  CLLocationManager.heading)
  audio engine   ← PCM sine + TTS  (AudioTrack + TextToSpeech  |  AVAudioEngine + AVSpeechSynthesizer)
  ui             ← Jetpack Compose  |  SwiftUI
  settings store ← Preferences DataStore  |  UserDefaults / small JSON file
  file i/o       ← MediaStore / SAF  |  DocumentPicker / Files app
```

**All navigation, audio scheduling, geo math, settings migration, and domain models run from the same Kotlin source on both platforms.** The iOS app calls into `BoldExplorerShared.xcframework` for everything in `:shared`; it only implements the platform layer in Swift.

#### Swift / Kotlin Flow interop

`StateFlow` and `SharedFlow` are exposed to Swift as Kotlin objects. Consuming them requires one of:

- **SKIE** (recommended) — Gradle plugin that generates Swift-native `AsyncSequence` wrappers automatically. Add `co.touchlab.skie:gradle-plugin` to `shared/build.gradle.kts`.
- **KMP-NativeCoroutines** — alternative with similar goals.
- Manual wrapping — write a thin Kotlin helper per flow that accepts a Swift callback (avoids extra dependencies but is tedious).

SKIE is the recommended path. Once added, every `Flow<T>` in `:shared` becomes an `AsyncSequence` in Swift with no extra boilerplate.

### 2.2 Data flow

```
GPS / Compass hardware
  └── LocationProvider + CompassProvider  (platform)
        └── GpsViewModel  (platform)
              ├── TrailFollower.onLocationUpdate()     (shared)
              │     └── WaypointReached / TrailComplete
              │           └── AudioCueScheduler.emitWaypointApproach / emitTrailComplete
              ├── CollectionExplorer.onLocationUpdate() (shared)
              │     └── PointReached / NearTrailEnd
              └── BearingComputer  (shared)
                    └── relativeDeg → AudioCueScheduler (beacon / ping)

AudioCueScheduler.events: SharedFlow<AudioCueEvent>   (shared)
  └── AudioCuePlayer  (platform)
        ├── AudioEngine  ← DirectionalBeacon, AccuracyBeacon, AlignmentPing
        └── TtsEngine    ← WaypointApproach, TrailComplete
```

---

## 3. Domain Models

All models live in `shared/model/` and are exported to iOS via `BoldExplorerShared.xcframework`. Swift code uses them directly — no re-implementation needed.

### Waypoint
| Field | Type | Notes |
|---|---|---|
| `id` | Int64 | database PK |
| `name` | String | user-visible label |
| `lat` | Double | WGS-84 decimal degrees |
| `lon` | Double | WGS-84 decimal degrees |
| `elevM` | Double? | metres, optional |
| `description` | String? | free text |
| `createdAt` | Int64 | Unix ms |
| `kind` | String | `"waypoint"` (user-created) or `"track_point"` (imported GPX track) |

### Trail
| Field | Type | Notes |
|---|---|---|
| `id` | Int64 | |
| `name` | String | |
| `description` | String? | |
| `createdAt` | Int64 | Unix ms |

### TrailWaypoint (junction)
| Field | Type | Notes |
|---|---|---|
| `id` | Int64 | |
| `trailId` | Int64 | FK → Trail |
| `waypointId` | Int64 | FK → Waypoint |
| `position` | Int | 0-based, gapless integer order |
| `createdAt` | Int64 | Unix ms |

Constraint: `(trailId, waypointId)` is unique. Position is maintained via two-step shift transactions (see §7.2).

### AutoWaypoint
Interpolated waypoint inserted at a calculated position along a trail segment. Used for auto-recording during navigation.

| Field | Type | Notes |
|---|---|---|
| `id` | Int64 | |
| `trailId` | Int64 | FK → Trail |
| `name` | String | |
| `segmentIndex` | Int | which leg of the trail (0-based) |
| `offsetM` | Double | metres from start of segment |
| `lat` | Double | |
| `lon` | Double | |
| `createdAt` | Int64 | Unix ms |

### Collection
| Field | Type | Notes |
|---|---|---|
| `id` | Int64 | |
| `name` | String | |
| `description` | String? | |
| `createdAt` | Int64 | Unix ms |

### CollectionWaypoint / CollectionTrail (junctions)
Each has `id`, `collectionId`, `waypointId`/`trailId`, `createdAt`.

### LocationSample
```
lat, lon: Double
accuracy: Double?     metres (1σ)
altitude: Double?     metres WGS-84
heading:  Double?     degrees, device motion direction
speed:    Double?     m/s
timestamp: Int64      Unix ms
provider: String      "fused" | "gnss" | "network" | ...
```

### HeadingReading
```
magnetic: Double?    degrees clockwise from magnetic north
trueNorth: Double?   degrees clockwise from true north
timestamp: Int64
```

### Enumerations
```
Units               METRIC | IMPERIAL
CompassMode         MAGNETIC | TRUE
BearingDisplayMode  RELATIVE | CLOCK | TRUE_NORTH
```

### AppSettings
```
units:                 Units              default IMPERIAL
compassMode:           CompassMode        default MAGNETIC
bearingDisplayMode:    BearingDisplayMode default RELATIVE
spokenGuidanceEnabled: Bool               default true
beaconCuesEnabled:     Bool               default true
duckAudioEnabled:      Bool               default false
```

---

## 4. Repository Interfaces

Each interface is implemented in the platform layer. `shared` holds only the interface.

### WaypointRepository
```
observeAll()                                → Flow<List<Waypoint>>
observeForTrail(trailId)                    → Flow<List<Waypoint>>
getAll()                                    → List<Waypoint>
getById(id)                                 → Waypoint?
create(name, lat, lon, elevM?, desc?, kind) → id: Int64
update(id, name?, lat?, lon?, elevM?, desc?)
remove(id)
forTrail(trailId)                           → List<Waypoint>
withDistanceFrom(lat, lon, trailId?, limit?)→ List<WaypointWithDistance>
attach(trailId, waypointId, position?)
detach(trailId, waypointId)
setPosition(trailId, waypointId, position)
```

`withDistanceFrom`: bbox SQL query (anti-meridian aware) → re-sorted in application code using haversine. No trig in SQL.

`setPosition`: two-step shift transaction that keeps positions gapless without a `UNIQUE` constraint (which would block the intermediate state of a swap).

### TrailRepository
```
observeAll()                               → Flow<List<Trail>>
observeWaypointsForTrail(trailId)          → Flow<List<Waypoint>>  (all kinds)
observeNamedWaypointsForTrail(trailId)     → Flow<List<Waypoint>>  (kind='waypoint')
observeTrackPointCountForTrail(trailId)    → Flow<Int64>
observeTrackPointsForTrail(trailId)        → Flow<List<Waypoint>>  (lazy)
getAll()                                   → List<Trail>
getById(id)                                → Trail?
create(name, desc?)                        → id: Int64
update(id, name?, desc?)
remove(id)
waypointsForTrail(trailId)                 → List<Waypoint>
trailWaypointsOrdered(trailId)             → List<TrailWaypoint>
```

### CollectionRepository
```
observeAll()                                → Flow<List<Collection>>
observeWaypointsForCollection(id)           → Flow<List<Waypoint>>
observeTrailsForCollection(id)              → Flow<List<Trail>>
getAll() / getById(id) / create / rename / remove
waypointsForCollection(id)                  → List<Waypoint>
trailsForCollection(id)                     → List<Trail>
collectionsForWaypoint(waypointId)          → List<Collection>
attachWaypoint / detachWaypoint
attachTrail / detachTrail
```

### AutoWaypointRepository
```
forTrail(trailId)         → List<AutoWaypoint>
create(trailId, name, segmentIndex, offsetM, lat, lon) → id
remove(id)
removeForTrail(trailId)
```

### SettingsRepository
```
observeSettings() → Flow<AppSettings>
load()            → AppSettings
save(settings)
```

---

## 5. Geo Algorithms (`shared/geo/GeoMath`)

All functions are exact ports of the original TypeScript `geo.ts`. An iOS re-implementation must produce identical output.

| Function | Signature | Notes |
|---|---|---|
| `haversineDistanceMeters` | `(a: LatLng, b: LatLng) → Double` | Earth radius 6_371_000 m |
| `initialBearingDeg` | `(a: LatLng, b: LatLng) → Double` | 0–360, clockwise from north |
| `deltaAngle` | `(heading, bearing) → Double` | `((heading - bearing + 540) % 360) - 180` — signed, −180..+180 |
| `computeBbox` | `(center: LatLng, radiusM) → BboxResult` | Anti-meridian + pole handling |

`BboxResult`:
```
latMin, latMax: Double
lonMin, lonMax: Double
crossesAntimeridian: Boolean
```

---

## 6. Navigation

### 6.1 BearingComputer

Stateless utility. Key outputs:

| Function | Output |
|---|---|
| `toCardinal(deg)` | "N", "NNE", "NE", … (16 points) |
| `toRelative(relativeDeg)` | "straight ahead", "slight right", "hard left", … |
| `toAlignmentRelative(relativeDeg, deadband)` | "aligned", "5 degrees right", … |
| `toClock(relativeDeg)` | "12 o'clock", "3 o'clock", … |
| `formatDistance(metres, units)` | "1.5 km", "5,280 ft", … |
| `computePan(relativeDeg)` | Float in [−1, 1]: `sin(deg * π/180)` |
| `computePitchHz(relativeDeg)` | `220 × 2^(1 + cos(deg * π/180))` → 220–880 Hz |

Pitch formula reference values:
- 0° (target straight ahead): 880 Hz (A5)
- ±90° (target to side): 440 Hz (A4)
- ±180° (target behind): 220 Hz (A3)

### 6.2 TrailFollower

State machine. Tracks progress through an ordered list of waypoints.

**States:** `Idle → Active(waypoints, currentIndex, thresholdM) → Complete`

**Events emitted:**
- `WaypointReached(index, name, kind, total, distanceToNextM, absoluteBearingDeg)`
- `TrailComplete`

**Advancement — 3-tier fallback:**

| Tier | Name | Trigger |
|---|---|---|
| 1 | Radial | Distance to target ≤ `thresholdM` (default 15 m) |
| 2 | Incoming Projection | User ≥ 90% along the leg **and** within 4× threshold **and** cross-track ≤ 3× threshold |
| 3 | Divergence | Previously approached within 2× threshold, now moved ≥ 5 m away, heading toward next point |

Guards on tier 2/3 prevent false advances on open terrain. Tier 3 is capped by segment length (next waypoint must be within 1.5× current segment length).

**Key methods:**
```
start(waypoints, fromIndex?, thresholdM)
startNearest(waypoints, location, bearingDeg?, thresholdM)   ← picks closest start
stop()
onLocationUpdate(location, altitudeM?, bearingDeg?) → TrailFollowerEvent?
```

**3D distance:** If both the waypoint and the fix have elevation, distance is computed in 3D (Pythagorean with the elevation delta).

### 6.3 CollectionExplorer

Manages multi-point navigation within a heterogeneous collection (standalone waypoints + trail endpoints).

**Point types:**
- `Standalone(waypoint)` — navigate directly to this waypoint
- `TrailEnd(waypoint, trail, isStart: Bool)` — trail endpoint; approaching triggers a "Follow trail?" prompt

**States:** `Idle | Active(points, target?, visitedIds, exploreMode, ...)`

**Events:**
- `PointReached(reached, next?)` — user arrived at point
- `NearTrailEnd(trailEnd, distanceM)` — user within approach threshold of a trail endpoint
- `NearbyPoint(point, distanceM)` — incidental proximity announcement (once per session)

**Thresholds (metres):**
```
REACH_THRESHOLD_M  = 15     standalone waypoint reached
TRAIL_APPROACH_M   = 10     trail endpoint approached (show Follow button)
PROXIMITY_M        = 30     nearby point announced (once per session per point)
```

**Explore mode:** when enabled, auto-advances to the nearest unvisited point after each `PointReached`. When disabled, user must manually pick the next target.

**Visited cap:** last 3 visited point IDs are tracked to weight auto-selection away from recently visited points.

---

## 7. Database Schema

SQLite. All tables include a surrogate `id` integer primary key and a `created_at` Unix-ms timestamp.

### Tables

```sql
waypoint(id, name, lat, lon, elev_m, description, created_at, kind)
trail(id, name, description, created_at)
trail_waypoint(id, trail_id, waypoint_id, position, created_at)
collection(id, name, description, created_at)
collection_waypoint(id, collection_id, waypoint_id, created_at)
collection_trail(id, collection_id, trail_id, created_at)
auto_waypoint(id, trail_id, name, segment_index, offset_m, lat, lon, created_at)
```

### Indices
```sql
INDEX waypoint(lat, lon)
UNIQUE INDEX trail_waypoint(trail_id, waypoint_id)
INDEX trail_waypoint(trail_id), trail_waypoint(waypoint_id)
INDEX collection_waypoint(collection_id), collection_waypoint(waypoint_id)
INDEX collection_trail(collection_id), collection_trail(trail_id)
INDEX auto_waypoint(trail_id), auto_waypoint(trail_id, segment_index)
```

### Spatial query pattern (withDistanceFrom)

```sql
-- Step 1: bbox pre-filter (anti-meridian-aware)
SELECT * FROM waypoint
WHERE lat >= :latMin AND lat <= :latMax
  AND (CASE WHEN :crossing
            THEN (lon >= :lonMin OR lon <= :lonMax)
            ELSE (lon >= :lonMin AND lon <= :lonMax) END)
LIMIT :candidateLimit;

-- Step 2: application-layer haversine sort + slice
```

No SQLite trig functions required.

### Position reorder (setPosition)

Two-step shift keeps positions gapless during reorder without a UNIQUE constraint on `position` (which would block intermediate states):

```sql
-- Moving waypoint from position :from to :to (moving forward, :to < :from):
UPDATE trail_waypoint SET position = position + 1
WHERE trail_id = :id AND position >= :to AND position < :from;
-- Then set the moved row to :to.

-- Moving backward (:to > :from):
UPDATE trail_waypoint SET position = position - 1
WHERE trail_id = :id AND position > :from AND position <= :to;
-- Then set the moved row to :to.
```

---

## 8. Audio System

### 8.1 AudioCueEvent (shared)

Sealed type. Platform audio engine must handle all five variants.

| Variant | Fields | Delivery |
|---|---|---|
| `DirectionalBeacon` | `pan: Float, pitchHz: Double` | PCM sine tone |
| `AccuracyBeacon` | `accuracyM: Double` | PCM sine tone (debug use) |
| `AlignmentPing` | `pan: Float, pitchHz: Double` | PCM sine tone |
| `WaypointApproach` | `waypointName: String` | TTS: "Next waypoint: \[name\]" |
| `TrailComplete` | — | TTS: "Trail complete" |

### 8.2 AudioCueConfig (shared)

```
enabled:                       Bool    default true
directionalBeaconEnabled:      Bool    default true
directionalBeaconIntervalMs:   Int64   default 5000
alignmentPingEnabled:          Bool    default true
waypointApproachEnabled:       Bool    default true
alignmentPingHz:               Double  default 1.0
alignmentDeadbandDeg:          Double  default 3.0
```

### 8.3 AudioCueScheduler (shared)

Pure scheduling — no platform API calls. Inputs are observable states; output is a stream of `AudioCueEvent`.

```
inputs:
  accuracyM:        Observable<Double?>
  relativeDeg:      Observable<Double?>   bearing delta, −180..+180
  alignmentActive:  Observable<Bool>
  beaconCuesEnabled:Observable<Bool>

outputs:
  events: Stream<AudioCueEvent>
  accuracyBeaconEnabled: MutableObservable<Bool>   (debug toggle)

methods:
  start(scope, ...)
  emitWaypointApproach(name, spokenGuidanceEnabled)
  emitTrailComplete(spokenGuidanceEnabled)
```

**Scheduling rules:**
- `DirectionalBeacon`: emitted every `directionalBeaconIntervalMs` **unless** `alignmentActive == true`.
- `AlignmentPing`: emitted at `alignmentPingHz` (1 Hz) when `|relativeDeg| > deadbandDeg`, else at ⅓ Hz.
- All beacon events suppressed when `beaconCuesEnabled == false`.
- `WaypointApproach` / `TrailComplete` suppressed when `spokenGuidanceEnabled == false`.

### 8.4 Platform audio requirements

**Tone engine:**
- PCM stereo float buffer, low latency (target < 10 ms)
- Stereo pan via per-channel volume: `left = 1 − max(pan, 0)`, `right = 1 + min(pan, 0)` (or equivalent)
- Frequency synthesised at runtime (sine wave)
- Audio routing category: accessibility / sonification (not media)

**TTS engine:**
- Offline capable
- Queue mode: add to queue (do not interrupt current speech)
- Language: system locale or user-configured

**Audio focus (Android pattern → iOS equivalent):**
- Android uses `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
- iOS equivalent: `AVAudioSession` category `.playback`, mode `.default`, options `.duckOthers`
- Request focus per-tone; release immediately after playback ends
- Controlled by `duckAudioEnabled` setting (when false, skip focus management entirely)

---

## 9. GPS & Compass

### 9.1 Location pipeline

```
Raw fix
  └── accuracy gate:  foreground ≤ 15 m, background ≤ 50 m
        └── interval gate: ≥ 1 000 ms between updates
              └── distance gate: ≥ 1 m moved
                    └── LocationSample → ViewModel
```

### 9.2 Background GPS

A foreground service (Android) / background location capability (iOS) must keep GPS active with screen off. Background accuracy threshold relaxes to 50 m to save battery.

iOS requires `UIBackgroundModes: location` in `Info.plist` and `CLLocationManager.allowsBackgroundLocationUpdates = true`.

### 9.3 Raw GNSS mode (debug)

Optional: bypass sensor fusion and use raw GNSS chip fixes directly. Useful outdoors for better accuracy in clear-sky conditions. Toggled from the Debug screen.

### 9.4 Compass

Source: device rotation-vector sensor (sensor fusion, not raw magnetometer).
Correction: apply magnetic declination (`GeomagneticField` on Android; `CLLocationManager.heading.trueHeading` on iOS).

Output: `HeadingReading(magnetic, trueNorth, timestamp)`.

Low-pass filter recommended to reduce jitter (α ≈ 0.1–0.2).

---

## 10. Settings & Migration

`AppSettings` is persisted as versioned JSON. Migration logic lives in `shared/settings/SettingsMigration` and is platform-agnostic.

Migration pattern (`PrefSpec<T>`):
1. Each preference has a key, a version it was introduced, and a migration function from the previous raw value.
2. On load, check the stored version; apply migrations in sequence up to current.
3. On save, write current version number alongside the JSON.

Android stores in Preferences DataStore. iOS equivalent: `UserDefaults` (key/value) or a small JSON file in the app's Documents directory.

---

## 11. GPX Import / Export

### 11.1 Export

Three export modes:

| Mode | GPX output |
|---|---|
| Waypoints | `<wpt>` elements |
| Trail | `<trk><trkseg><trkpt>` for track points; named waypoints get `<name>` |
| Collection | `<metadata><name>` + combined waypoints and trails |

All exports:
- GPX 1.1
- `<ele>` when elevation present
- `<time>` in ISO 8601 (from `createdAt`)
- `<desc>` when description present
- XML-escaped text fields

### 11.2 Import

Parser handles:
- `<wpt>` → standalone waypoints (`kind='waypoint'`)
- `<trk>` → trail with track points (`kind='track_point'`)
- `<rte>` → trail with route points (treated as track points)
- `<metadata><name>` → collection name hint

Fallback names when absent:
- Waypoint: "Waypoint"
- Track point: "Point N"
- Trail: "Imported Trail"
- Route: "Imported Route"

Parser must handle namespace prefixes gracefully (strip prefixes on element lookup).

### 11.3 File I/O

Android writes to the system Downloads folder via `MediaStore` (API 29+) or legacy storage. iOS should write to the app's Documents directory (iCloud-visible) or offer a share sheet.

---

## 12. Screens

### 12.1 GPS Screen (primary navigation)

Three scopes selectable via segmented control:

**WAYPOINT scope**
- Pick a single waypoint as target
- Shows: distance, bearing display (relative / clock / cardinal — per `bearingDisplayMode`)
- Directional beacon and alignment ping active when navigating

**TRAIL scope**
- Pick a trail
- TrailFollower tracks progress through ordered waypoints
- Shows: current target waypoint, index / total, distance, bearing
- TTS announces each waypoint reached and trail complete
- "Start nearest" option: begins from the waypoint closest to current position

**COLLECTION scope**
- Pick a collection
- CollectionExplorer navigates through waypoints and trail endpoints
- Shows: current target, distance
- Explore mode toggle: auto-advance vs. manual

**Shared elements (all scopes):**
- Telemetry card: latitude, longitude, altitude, accuracy, speed (auto-hidden when screen reader off)
- Heading: magnetic or true (per `compassMode` setting)
- Mark Waypoint FAB: creates a waypoint at current location
- Auto-record toggle: saves waypoints automatically while moving
- Alignment mode: tap compass heading to enter alignment on that bearing; alignment ping activates
- Live region announcements: TalkBack reads state changes automatically

### 12.2 Waypoints Screen

- List of all waypoints (kind='waypoint' only)
- Search / filter by name
- Near-me mode: filter by distance radius from current location; distance-sorted
- Radius presets: 100 m, 500 m, 1 km, 5 km, custom
- Create: name, coordinates (or use current location), elevation, description
- Edit: same fields
- Delete: with confirmation
- Attach to trail (pick from list; inserts at end by default)
- Assign to / remove from collection
- GPX import (creates standalone waypoints)
- GPX export (selected waypoints)

### 12.3 Trails Screen

- List of all trails
- Expandable trail detail: named waypoints list + track point count
- Create / rename / delete trail
- Add named waypoint to trail (pick from waypoint list)
- Reorder waypoints: move up / move down
- Detach waypoint from trail
- GPX import: creates trail + track points from `<trk>` or `<rte>`
- GPX export: single trail or all trails

### 12.4 Collections Screen

- List of all collections
- Expandable detail: member waypoints + member trails
- Create / rename / delete collection
- Add waypoint to collection (pick from list)
- Add trail to collection (pick from list)
- Remove member waypoint or trail
- Explore mode: start exploring directly from this screen
- GPX import: creates collection named from file or metadata
- GPX export: full collection

### 12.5 Settings Screen

| Setting | Type | Options |
|---|---|---|
| Units | Segmented | Metric / Imperial |
| Bearing display | Segmented | Relative / Clock / True North |
| Compass mode | Segmented | Magnetic / True North |
| Spoken guidance | Toggle | TTS for waypoint and trail events |
| Beacon cues | Toggle | Directional beacon and alignment ping |
| Duck audio | Toggle | Lower background audio during each tone |

### 12.6 Debug Screen

- Real-time GPS telemetry (provider, lat/lon/alt, accuracy, speed)
- Raw GNSS provider toggle
- Compass: magnetic bearing and true-north bearing
- Accuracy beacon test toggle (maps GPS accuracy to tone frequency live)
- Advancement diagnostic: last advancement reason (mechanism, distances, fractions)
- Database export / reset

---

## 13. Accessibility Requirements

**These are non-negotiable.** The app is built for a blind user; every interactive element must be fully operable with a screen reader.

| Requirement | Android | iOS |
|---|---|---|
| Invisible labels on icon-only buttons | `contentDescription` semantics | `accessibilityLabel` |
| Live state announcements | `liveRegion = Polite` on containers | `UIAccessibility.post(.announcement, ...)` |
| Waypoint / trail events via TTS | `TtsEngine.speak(text)` | `AVSpeechSynthesizer` |
| Minimum touch target | 48 × 48 dp | 44 × 44 pt |
| Container label overrides children | Set description explicitly — do not rely on child text being read | Same: `accessibilityLabel` on container replaces children |
| Screen-reader pass required | TalkBack | VoiceOver |

**Critical rule on container labels:** When a `contentDescription` / `accessibilityLabel` is set on a container (card, row, list item), the screen reader reads *only* that string. It does **not** read child text nodes. Always include every piece of visible information (name, distance, state) explicitly in the container label.

Audio cues are the primary interface. Visual display is for sighted bystanders. If audio breaks, navigation breaks.

---

## 14. Permissions

| Permission | Purpose |
|---|---|
| Fine location (foreground) | GPS fixes while app is visible |
| Background location | GPS while screen off / app backgrounded |
| Post notifications | Foreground service notification |
| (iOS) `NSLocationWhenInUseUsageDescription` | Foreground GPS |
| (iOS) `NSLocationAlwaysAndWhenInUseUsageDescription` | Background GPS |
| (iOS) `UIBackgroundModes: location` | Keep GPS alive in background |

---

## 15. Platform Technology Map

Shared Kotlin logic (everything in `:shared`) is **identical** on both platforms — consumed as `BoldExplorerShared.xcframework` on iOS. Only the platform layer differs.

| Concern | Shared (Kotlin, both platforms) | Android platform layer | iOS platform layer |
|---|---|---|---|
| Geo math | `GeoMath`, `BearingComputer` | — | — |
| Navigation | `TrailFollower`, `CollectionExplorer` | — | — |
| Audio scheduling | `AudioCueScheduler` | — | — |
| Domain models | all `model/` classes | — | — |
| Settings migration | `SettingsMigration` | — | — |
| Repository interfaces | `Repositories.kt` | — | — |
| UI framework | — | Jetpack Compose | SwiftUI |
| Database | — | SQLDelight Android driver | SQLDelight Native driver (or GRDB) |
| Flow → UI bridge | — | `StateFlow` in ViewModel | SKIE `AsyncSequence` in `@Observable` |
| DI | — | Hilt | Swift struct init / environment objects |
| Settings persistence | — | Preferences DataStore | `UserDefaults` or JSON file |
| PCM audio | — | `AudioTrack` (stereo float PCM) | `AVAudioEngine` + `AVAudioPlayerNode` |
| TTS | — | Android `TextToSpeech` | `AVSpeechSynthesizer` |
| GPS | — | `FusedLocationProviderClient` | `CLLocationManager` |
| Compass | — | `SensorManager.TYPE_ROTATION_VECTOR` | `CLLocationManager.heading` |
| Background GPS | — | `LocationForegroundService` | Background location capability |
| GPX file write | — | `MediaStore` (Downloads) | Documents directory / share sheet |
| GPX file read | — | `ActivityResultContracts.OpenDocument` | `UIDocumentPickerViewController` |
| Audio focus (duck) | — | `AudioFocusRequest` TRANSIENT_MAY_DUCK | `AVAudioSession` `.duckOthers` |

### iOS project setup checklist

- [ ] Add `BoldExplorerShared.xcframework` (built via `make xcframework` on macOS) to the Xcode project
- [ ] Add SKIE Gradle plugin to `shared/build.gradle.kts` for ergonomic Swift async/await Flow consumption
- [ ] Implement `WaypointRepository`, `TrailRepository`, `CollectionRepository`, `AutoWaypointRepository` in Swift (SQLDelight Native driver is the closest drop-in; GRDB is a strong alternative)
- [ ] Implement `SettingsRepository` backed by `UserDefaults`
- [ ] Implement `LocationProvider` wrapping `CLLocationManager` with the same accuracy / interval / distance gates as the Android `FusedLocationProviderImpl`
- [ ] Implement `CompassProvider` from `CLLocationManager.heading` + magnetic declination (`CLLocationManager` provides both)
- [ ] Implement `AudioEngine` (PCM sine, stereo pan) using `AVAudioEngine`
- [ ] Implement `TtsEngine` using `AVSpeechSynthesizer`
- [ ] Wire `AudioCuePlayer` equivalent: collect `AudioCueScheduler.events` (via SKIE) and dispatch to `AudioEngine` / `TtsEngine`
- [ ] Enable background location in `Info.plist` (`UIBackgroundModes: location`) and request always-authorization
- [ ] Add `NSLocationWhenInUseUsageDescription` and `NSLocationAlwaysAndWhenInUseUsageDescription` to `Info.plist`

---

## 16. Release Readiness Checklist

- [ ] All unit tests pass (geo math, trail follower, settings migration, repository CRUD)
- [ ] Build succeeds
- [ ] Physical device — GPS fix acquired, compass heading displayed
- [ ] Background tracking — GPS continues with screen off
- [ ] Headphones — accuracy beacon pitch changes with simulated accuracy variation
- [ ] Headphones — directional beacon pans left/right as heading changes relative to target
- [ ] TTS — waypoint reached and trail complete spoken aloud
- [ ] Screen reader pass — all icon-only controls labelled, live regions fire on state changes, all touch targets ≥ minimum size
- [ ] Settings persist across app restart
- [ ] GPX round-trip — export a trail, re-import it, waypoints match
