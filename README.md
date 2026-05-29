# Bold Explorer — Android

Native Android trail navigation app, built for and by a blind user. The original Vue 3 + Ionic Capacitor app was a prototype; this repo is the production Android implementation.

Pure logic (algorithms, state machines, models, settings migration, repository interfaces) lives in `:shared`; Android APIs and UI live in `:app`.

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
| Database | SQLDelight 2.x | Typed SQLite access and generated query APIs |
| DI | Hilt (Android-only) | Compile-time verification; `:shared` has zero DI deps |
| Audio tones | AudioTrack (PCM float, stereo) | Full pan control via `setStereoVolume()`; sub-10ms latency |
| Speech | Android TextToSpeech | Offline, queue-based |
| Reactive | Coroutines + StateFlow/SharedFlow | Replaces RxJS |
| Settings | Preferences DataStore | Coroutine-native persistence with shared migration parsing |
| GPS | FusedLocationProviderClient | Battery-efficient; ports gating logic from `locationStream.ts` |
| Compass | SensorManager TYPE_ROTATION_VECTOR + GeomagneticField | No third-party plugin |

## Getting Started

```bash
bash setup.sh           # install JDK 17, Android SDK, ADB (WSL/Linux)
make test-shared        # run shared module tests
make test               # run shared + app JVM tests
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
| 2 | SQLDelight database + repository impls | Done |
| 3 | FusedLocation + SensorCompass + foreground service | Done |
| 4 | AudioTrack engine, TTS, AudioCueScheduler wiring | Done |
| 5 | Jetpack Compose UI, NavGraph, TalkBack pass | Done |
| 6 | DataStore settings, GPX export, polish | Done |

## Release Readiness Checklist

- `make test` passes.
- `make assemble` builds a debug APK.
- Physical device pass: GPS fix, compass heading, background tracking with screen off.
- Headphones pass: accuracy beacon changes pitch, alignment pan points left/right correctly.
- TTS pass: waypoint reached and trail complete announcements are spoken.
- TalkBack pass: all icon-only controls have labels, live announcements are polite, touch targets are usable.

See [PLAN.md](PLAN.md) for architecture detail, audio design, GPS pipeline, SQLDelight schema, and hardening notes.
