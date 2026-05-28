# Bold Explorer — Android KMP Rewrite

Native Android rewrite of the Bold Explorer trail navigation app, built for and by a blind user. The original Vue 3 + Ionic Capacitor app hit hard limits around audio reliability, GPS battery efficiency, and background location stability — all of which require native APIs.

The app uses **Kotlin Multiplatform** so that pure logic (algorithms, state machines, models, settings migration) lives in a `:shared` module that can target iOS later without re-porting the math.

Original Vue/Capacitor source lives at `../bold-explorer/` (sibling directory). TypeScript files there are the reference for algorithm parity — port from them, don't modify them.

## Project Structure

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
│       ├── navigation/             # TrailFollower, BearingComputer
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
        ├── gpx/                    # GpxExporter
        ├── di/                     # Hilt modules
        └── ui/                     # Jetpack Compose screens + ViewModels
```

## Key Technology Choices

| Concern | Choice | Reason |
|---|---|---|
| Database | SQLDelight 2.x | KMP-native; iOS driver available later |
| DI | Hilt (Android-only) | Compile-time verification; `:shared` has zero DI deps |
| Audio tones | AudioTrack (PCM float, stereo) | Full pan control via `setStereoVolume()`; sub-10ms latency |
| Speech | Android TextToSpeech | Offline, queue-based |
| Reactive | Coroutines + StateFlow/SharedFlow | Replaces RxJS |
| Settings | Proto DataStore | Typed, coroutine-native |
| GPS | FusedLocationProviderClient | Battery-efficient; ports gating logic from `locationStream.ts` |
| Compass | SensorManager TYPE_ROTATION_VECTOR + GeomagneticField | No third-party plugin |

## Getting Started

```bash
bash setup.sh           # install JDK 17, Android SDK, ADB (WSL/Linux)
make test-shared        # run shared module tests (Phase 1 gate)
make assemble           # build debug APK
make install            # push to device (USB or ADB-over-Tailscale)
adb logcat -s BoldExplorer
```

## Accessibility Requirements

This app is built for a blind user. Every phase must meet these before it is considered complete:

- All icon-only elements: `Modifier.semantics { contentDescription = "..." }`
- Live regions: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on announcements
- Waypoint/trail transitions announced via TTS (not just visual state changes)
- Minimum 48dp touch targets throughout
- TalkBack pass required before each phase ships
- Audio cues are the primary interface — audio quality is never a "nice to have"

## Implementation Status

| Phase | Description | Status |
|---|---|---|
| 1 | Shared algorithms, models, tests | Done |
| 2 | SQLDelight database + repository impls | Pending |
| 3 | FusedLocation + SensorCompass + foreground service | Pending |
| 4 | AudioTrack engine, TTS, AudioCueScheduler wiring | Pending |
| 5 | Jetpack Compose UI, NavGraph, TalkBack pass | Pending |
| 6 | DataStore settings, GPX export, polish | Pending |

See [PLAN.md](PLAN.md) for full architecture detail, audio design, GPS pipeline, SQLDelight schema, and phase gates.
