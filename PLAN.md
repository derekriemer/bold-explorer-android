# Bold Explorer: Android-First KMP Rewrite

## Context

The current Vue 3 + Ionic Capacitor implementation hit fundamental walls:
- **Audio**: Web Audio API is unreliable cross-platform; stereo pan guidance and accuracy beacons are core accessibility features that need to work every time
- **GPS**: Capacitor wraps `navigator.geolocation`, which is battery-hungry and doesn't expose raw FusedLocationProvider quality fixes
- **Battery**: Background location via `@capacitor-community/background-geolocation` is fragile on Android

This rewrites the app as a native Android app using Kotlin, with a Kotlin Multiplatform `:shared` module holding pure logic (algorithms, state machines, models) so iOS is viable later without re-porting the math.

Original Vue/Capacitor source lives at `../bold-explorer/` (sibling directory). The TypeScript
source files are the reference for algorithm parity — do not modify them; port from them.

---

## Module Structure

```
bold-explorer-kmp/
├── settings.gradle.kts
├── gradle/libs.versions.toml     # all pinned versions
├── Makefile                      # make test-shared, make assemble, make install
├── setup.sh                      # one-shot WSL/Linux setup (JDK, Android SDK, ADB)
│
├── shared/                       # :shared — KMP module (zero Android/iOS deps)
│   └── src/commonMain/kotlin/com/boldexplorer/shared/
│       ├── geo/                  # GeoMath.kt, LatLng.kt, BboxResult.kt
│       ├── model/                # all domain models + LocationSample, HeadingReading
│       ├── repository/           # interfaces only — no SQLite here
│       ├── navigation/           # TrailFollower.kt, BearingComputer.kt
│       ├── audio/                # AudioCueEvent, AudioCueConfig, AudioCueScheduler
│       └── settings/             # AppSettings, SettingsMigration
│
└── app/                          # :app — Android module
    └── src/main/kotlin/com/boldexplorer/
        ├── db/                   # SQLDelight .sq files + *RepositoryImpl
        ├── location/             # FusedLocationProviderImpl, LocationForegroundService
        ├── compass/              # SensorCompassProvider
        ├── audio/                # AudioEngine (AudioTrack), TtsEngine, AudioCuePlayer
        ├── settings/             # DataStoreSettingsRepository
        ├── gpx/                  # GpxExporter
        ├── di/                   # Hilt modules
        └── ui/                   # Jetpack Compose screens + ViewModels
```

---

## Key Dependency Decisions

| Concern | Choice | Reason |
|---|---|---|
| Database | SQLDelight 2.x | KMP-native; iOS driver available later; no SQLite trig needed |
| DI | Hilt (Android-only) | Compile-time verification; `@HiltViewModel`; `:shared` has zero DI deps |
| Audio tones | AudioTrack (PCM float, stereo) | Full pan control via `setStereoVolume()`; sub-10ms for 1Hz ping |
| Speech | Android TextToSpeech | Offline, queue-based |
| Reactive | Coroutines + StateFlow/SharedFlow | Replaces RxJS; Flow operators map 1:1 |
| Settings | Proto DataStore | Typed, coroutine-native |
| GPS | FusedLocationProviderClient | Battery-efficient; gating logic ports from locationStream.ts |
| Compass | SensorManager TYPE_ROTATION_VECTOR + GeomagneticField | No third-party plugin needed |

---

## Shared Module Contents

### `geo/GeoMath.kt`
Exact port of `../bold-explorer/src/utils/geo.ts`:
- `haversineDistanceMeters(a, b)`
- `initialBearingDeg(a, b)`
- `deltaAngle(heading, bearing)` — `((heading - bearing + 540) % 360) - 180`
- `computeBbox(center, radiusM)` — anti-meridian + pole handling

### `navigation/TrailFollower.kt`
Port of `../bold-explorer/src/composables/useFollowTrail.ts`:
- Vue watchers → `onLocationUpdate(location): TrailFollowerEvent?`
- Same 15m default threshold, same index-advance-then-stop logic
- `StateFlow<TrailFollowerState>` (Idle / Active / Complete)

### `audio/AudioCueScheduler.kt`
Pure coroutine — decides WHAT to emit, WHEN. No playback.
Android `AudioCuePlayer` (Phase 4) consumes the `SharedFlow<AudioCueEvent>`.
iOS can plug in its own player without touching this class.

### `settings/SettingsMigration.kt`
Port of the `PrefSpec / getOrInitWithMigrate` pattern from `../bold-explorer/src/stores/usePrefs.ts`.
Pure migration logic only — DataStore persistence is in `:app`.

---

## Audio Architecture

```
GpsViewModel
  ├── TrailFollower events → AudioCueScheduler.emitWaypointApproach()
  ├── LocationFlow accuracy → AccuracyBeacon events
  └── BearingDelta → AlignmentPing events

AudioCueScheduler (shared, pure)
  └── SharedFlow<AudioCueEvent>

AudioCuePlayer (Android, Phase 4)
  ├── AudioEngine: AudioTrack PCM sine wave
  │     0m→880Hz, 30m→220Hz; setStereoVolume() pan; 80ms alignment ping at 1Hz
  └── TtsEngine: TextToSpeech QUEUE_ADD
        "Next waypoint: [name]" / "Trail complete"

AudioAttributes: USAGE_ASSISTANCE_ACCESSIBILITY + CONTENT_TYPE_SONIFICATION
AudioFocus: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
```

---

## GPS / Compass Architecture

```kotlin
// FusedLocationProviderImpl — port of locationStream.ts gating logic
fusedClient.locationFlow()
    .filter { it.accuracy <= 15f }          // accuracy gate (50m in background)
    .throttleFirst(1000)                    // interval gate
    .distinctUntilChangedBy { ... }         // distance gate
    .map { it.toLocationSample() }
```

Background GPS: `LocationForegroundService` with `FOREGROUND_SERVICE_TYPE_LOCATION`.

Compass: `SensorManager.TYPE_ROTATION_VECTOR` + low-pass filter + `GeomagneticField.declination`
for true north — replaces the custom Capacitor Heading plugin entirely.

---

## SQLDelight Schema

Matches the 5 existing migrations from `../bold-explorer/src/db/migrations/`. Key pattern:

```sql
-- Waypoint.sq
waypointsInBbox:
SELECT * FROM waypoint
WHERE lat >= :latMin AND lat <= :latMax
  AND (CASE WHEN :crossing THEN (lon >= :lonMin OR lon <= :lonMax)
            ELSE (lon >= :lonMin AND lon <= :lonMax) END)
ORDER BY abs(lat - :centerLat),
         CASE WHEN abs(lon - :centerLon) > 180
              THEN 360 - abs(lon - :centerLon)
              ELSE abs(lon - :centerLon) END
LIMIT :candidateLimit;
-- Kotlin does haversineDistanceMeters + sort + slice (no SQLite trig needed)

shiftDown:
UPDATE trail_waypoint SET position = position - 1
WHERE trail_id = :id AND position > :from AND position <= :to;

shiftUp:
UPDATE trail_waypoint SET position = position + 1
WHERE trail_id = :id AND position >= :to AND position < :from;
```

---

## Accessibility Requirements

This app is built for and by a blind user. Every phase must meet these:

- All icon-only elements: `Modifier.semantics { contentDescription = "..." }`
- Live regions: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on announcements
- Waypoint/trail transitions announced via `TtsEngine` (not just visual state)
- Minimum 48dp touch targets throughout
- TalkBack pass required before each phase is considered complete
- Audio cues are the primary interface — audio quality is never a "nice to have"

---

## Implementation Phases

### Phase 1 — Shared Algorithms + Models ✅ DONE
- [x] KMP project structure, `libs.versions.toml`, Gradle wrapper, Makefile, `setup.sh`
- [x] `GeoMath.kt` — all 5 functions, exact parity with `geo.ts`
- [x] `LatLng.kt`, `BboxResult.kt`, all model data classes
- [x] `TrailFollower.kt`, `BearingComputer.kt`
- [x] `AudioCueEvent`, `AudioCueConfig`, `AudioCueScheduler` skeleton
- [x] `SettingsMigration` framework, `AppSettings`
- [x] Repository interfaces
- [x] `GeoMathTest`, `TrailFollowerTest`, `SettingsMigrationTest`
- **Gate**: `make test-shared` (requires JDK 17 — run `bash setup.sh` first)

### Phase 2 — Database + Repositories
- [ ] SQLDelight `.sq` files for all 7 tables (DDL matching migrations 001–005)
- [ ] `WaypointRepositoryImpl`: `withDistanceFrom` (bbox query + Kotlin haversine sort), `setPosition` (shift transactions)
- [ ] `TrailRepositoryImpl`, `CollectionRepositoryImpl`, `AutoWaypointRepositoryImpl`
- [ ] Hilt `DatabaseModule`
- **Gate**: `./gradlew :app:testDebugUnitTest` — repo integration tests + position-reorder invariant

### Phase 3 — Location + Compass
- [ ] `FusedLocationProviderImpl`: accuracy/interval/distance gates as Flow pipeline
- [ ] `LocationForegroundService`: bound + foreground, background accuracy relaxation
- [ ] `SensorCompassProvider`: rotation vector + low-pass + declination
- [ ] `LocationViewModel`
- [ ] Runtime permissions (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`)
- **Gate**: physical device — GPS updates, compass heading, background survives screen off

### Phase 4 — Audio Engine
- [ ] `AudioEngine`: AudioTrack PCM sine, accuracy→frequency, `setStereoVolume` pan
- [ ] `TtsEngine`: TextToSpeech with queue management
- [ ] `AudioCueScheduler`: wire location/bearing/alignment → `SharedFlow<AudioCueEvent>`
- [ ] `AudioCuePlayer`: dispatch to engine + TTS, audio focus
- [ ] `AudioModule` (Hilt)
- **Gate**: headphones — accuracy beacon frequency varies; stereo pan audible off-bearing; TTS names waypoints

### Phase 5 — Compose UI
- [ ] `NavGraph` with 5-tab `NavigationBar`
- [ ] `GpsScreen`, `WaypointsScreen`, `TrailsScreen`, `CollectionsScreen`, `SettingsScreen`
- [ ] Full TalkBack pass on all screens
- **Gate**: full UI navigation with TalkBack enabled, screen off

### Phase 6 — Settings + GPX + Polish
- [ ] `DataStoreSettingsRepository` with `SettingsMigration` framework
- [ ] `GpxExporter` (full GPX 1.1 XML)
- [ ] Debug/diagnostics screen
- **Gate**: full trail walk, audio-only navigation, screen off

---

## Verification Commands

```bash
make test-shared              # Phase 1 gate
make test                     # all unit tests
make assemble                 # build debug APK
make install                  # push to device (USB or ADB-over-Tailscale)
adb logcat -s BoldExplorer    # filter app logs
```

---

## Reference Source Files (original Vue project)

These are the TypeScript originals that Kotlin files were ported from.
Do not modify them; they live in the sibling `bold-explorer/` directory.

- `src/utils/geo.ts` — all 5 geo algorithm functions
- `src/composables/useFollowTrail.ts` — TrailFollower state machine
- `src/composables/useBearingAlignment.ts` — pan computation
- `src/data/repositories/waypoints.repo.ts` — setPosition + withDistanceFrom
- `src/stores/usePrefs.ts` — migration framework
- `src/db/migrations/provider.ts` — canonical DDL sequence (001–005)
