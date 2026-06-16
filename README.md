# Bold Explorer — Android

Offline trail navigation app built for a blind user. Audio is the primary interface.

The original Vue 3 + Ionic Capacitor prototype is at `../bold-explorer/` (sibling directory) — do not modify it; it is the algorithm reference.

**For a full product and design spec (including iOS porting guidance) see [SPEC.md](SPEC.md).**

---

## Module Structure

```
bold-explorer-android/
├── settings.gradle.kts
├── gradle/libs.versions.toml       # all pinned versions
├── Makefile                        # make test-shared, make assemble, make install
├── setup.sh                        # one-shot WSL/Linux setup (JDK, Android SDK, ADB)
│
├── shared/                         # :shared — KMP module (zero Android/iOS deps)
│   └── src/commonMain/kotlin/com/boldexplorer/shared/
│       ├── geo/                    # GeoMath, LatLng, BboxResult
│       ├── model/                  # domain models, LocationSample, HeadingReading
│       ├── repository/             # interfaces only
│       ├── navigation/             # TrailFollower, BearingComputer, CollectionExplorer
│       ├── audio/                  # AudioCueEvent, AudioCueConfig, AudioCueScheduler
│       └── settings/               # AppSettings, SettingsMigration
│
└── app/                            # :app — Android module
    └── src/main/kotlin/com/boldexplorer/
        ├── db/                     # SQLDelight .sq files + *RepositoryImpl
        ├── location/               # FusedLocationProviderImpl, LocationForegroundService
        ├── compass/                # SensorCompassProvider
        ├── audio/                  # AudioEngine (AudioTrack), TtsEngine, AudioCuePlayer
        ├── settings/               # DataStoreSettingsRepository
        ├── gpx/                    # GpxParser, GpxExporter, GpxFileWriter
        ├── di/                     # Hilt modules
        └── ui/                     # Jetpack Compose screens + ViewModels
```

## Key Technology Choices

| Concern | Choice | Reason |
|---|---|---|
| Database | SQLDelight 2.x | Typed SQLite; no trig in SQL |
| DI | Hilt | Compile-time verification; `:shared` has zero DI deps |
| Audio tones | AudioTrack (PCM float, stereo) | Full pan control; sub-10 ms latency |
| Speech | Android TextToSpeech | Offline, queue-based |
| Reactive | Coroutines + StateFlow / SharedFlow | Replaces RxJS |
| Settings | Preferences DataStore | Coroutine-native; shared migration logic in `:shared` |
| GPS | FusedLocationProviderClient | Battery-efficient; gating logic ported from `locationStream.ts` |
| Compass | SensorManager TYPE_ROTATION_VECTOR + GeomagneticField | No third-party plugin |

## Getting Started

```bash
bash setup.sh           # install JDK 17, Android SDK, ADB (WSL/Linux)
make test-shared        # run shared module tests (no device needed)
make test               # run shared + app JVM tests
make assemble           # build debug APK
make install            # push to device (USB or ADB-over-Tailscale)
adb logcat -s BoldExplorer
```

## Screens

| Screen | Purpose |
|---|---|
| GPS | Primary navigation — waypoint / trail / collection scopes, audio cues, mark waypoint |
| Waypoints | CRUD, near-me filter, attach to trails / collections, GPX import/export |
| Trails | CRUD, ordered waypoint list, reorder, GPX import/export |
| Collections | CRUD, heterogeneous grouping of waypoints + trails, auto-advance, GPX import/export |
| Settings | Units, bearing display, compass mode, audio toggles |
| Debug | GPS telemetry, raw GNSS toggle, accuracy beacon test, advancement diagnostics |

## Accessibility Requirements

This app is built for a blind user. Every release must satisfy:

- All icon-only elements: `Modifier.semantics { contentDescription = "..." }`
- Live regions: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on announcements
- Waypoint / trail transitions announced via TTS (not just visual state)
- Minimum 48 dp touch targets throughout
- TalkBack pass required before any change ships
- Audio cues are the primary interface — audio quality is never optional

**Container label rule:** setting `contentDescription` on a container silences all child `Text()` nodes. Always spell out every piece of visible information (name, distance, state) in the container description.

## Implementation Status

All six phases complete. The app is in hardening / feature completion.

| Phase | Description | Status |
|---|---|---|
| 1 | Shared algorithms, models, tests | Done |
| 2 | SQLDelight database + repository impls | Done |
| 3 | FusedLocation + SensorCompass + foreground service | Done |
| 4 | AudioTrack engine, TTS, AudioCueScheduler wiring | Done |
| 5 | Jetpack Compose UI, NavGraph, TalkBack pass | Done |
| 6 | DataStore settings, GPX import/export, polish | Done |

## Release Readiness Checklist

- `make test` passes.
- `make assemble` builds a debug APK.
- Physical device: GPS fix, compass heading, background tracking with screen off.
- Headphones: accuracy beacon pitch changes; directional beacon pans correctly.
- TTS: waypoint reached and trail complete spoken aloud.
- TalkBack: all icon-only controls labelled, live regions fire, touch targets are usable.

See [SPEC.md](SPEC.md) for full product spec, data model, audio design, GPS pipeline, database schema, and iOS platform equivalents.
See [PLAN.md](PLAN.md) for original architecture rationale and phase-by-phase implementation notes.
