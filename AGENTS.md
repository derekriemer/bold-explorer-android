# AGENTS.md

This file is the canonical guidance for all AI coding agents in this repository.
`CLAUDE.md` defers to this file and adds only Claude Code–specific notes.

## Commands

```bash
bash setup.sh                   # one-shot WSL/Linux setup — JDK 17, Android SDK, ADB
make test-shared                # run :shared jvmTest (Phase 1 gate; no device needed)
make test-shared-watch          # same, continuous mode
make test                       # all unit tests (:shared:jvmTest + :app:testDebugUnitTest)
make test-db                    # database repository tests only
make assemble                   # build debug APK (requires ANDROID_HOME)
make install                    # push debug APK to connected device
make logcat                     # tail app + crash logs (adb logcat -s BoldExplorer)
make adb-connect                # connect via Tailscale (set PHONE_IP + PHONE_PORT in env)
```

Run a single test class:
```bash
./gradlew :shared:jvmTest --tests "com.boldexplorer.shared.geo.GeoMathTest"
./gradlew :app:testDebugUnitTest --tests "com.boldexplorer.db.WaypointRepositoryTest"
```

## Architecture

### Two-module KMP split

`:shared` — pure Kotlin, zero Android or iOS deps. Targets `jvm` (for fast local tests), `androidTarget`, `iosArm64`, `iosSimulatorArm64`, and `iosX64`. All algorithms, state machines, domain models, and repository interfaces live here. Tests run on the JVM without an emulator. The iOS artifact is `BoldExplorerShared.xcframework` (built via `make xcframework` on macOS).

`:app` — Android-only. Consumes `:shared`. Contains all Android API usage: SQLDelight driver, FusedLocationProvider, SensorManager, AudioTrack, TextToSpeech, DataStore, Hilt DI, Jetpack Compose UI.

### Original Vue/Capacitor source as reference

`../js-bold-explorer/` (sibling directory) is the reference implementation. Port from it; never modify it. Key files:

- `src/utils/geo.ts` → `shared/.../geo/GeoMath.kt`
- `src/composables/useFollowTrail.ts` → `shared/.../navigation/TrailFollower.kt`
- `src/composables/useBearingAlignment.ts` → `shared/.../navigation/BearingComputer.kt`
- `src/data/repositories/waypoints.repo.ts` → `app/.../db/WaypointRepositoryImpl.kt` (Phase 2)
- `src/stores/usePrefs.ts` → `shared/.../settings/SettingsMigration.kt` + `app/.../settings/DataStoreSettingsRepository.kt`
- `src/db/migrations/provider.ts` → SQLDelight `.sq` files (Phase 2)

### Data flow

<!--
```
GPS/Sensor hardware
  └── FusedLocationProviderImpl / SensorCompassProvider  (:app, Phase 3)
        └── LocationViewModel  (:app)
              ├── TrailFollower.onLocationUpdate()  (:shared)
              │     └── TrailFollowerEvent → AudioCueScheduler.emitWaypointApproach()
              └── BearingComputer  (:shared)
                    └── AudioCueScheduler (alignment ping loop)  (:shared)

AudioCueScheduler.events: SharedFlow<AudioCueEvent>
  └── AudioCuePlayer  (:app, Phase 4)
        ├── AudioEngine (AudioTrack PCM sine)
        └── TtsEngine (TextToSpeech)
```
-->

### Key patterns

**Repository interfaces in `:shared`, impls in `:app`**: `Repositories.kt` defines all five interfaces (`WaypointRepository`, `TrailRepository`, `CollectionRepository`, `AutoWaypointRepository`, `SettingsRepository`). Hilt binds the Android impls.

**`AudioCueScheduler` is pure scheduling, not playback**: It decides what to emit and when via `SharedFlow<AudioCueEvent>`. `AudioCuePlayer` (:app) owns the active scheduler job, consumes the flow, and drives `AudioTrack` + TTS.

**`WaypointRepository.withDistanceFrom`**: Does a bbox SQL query (anti-meridian aware, via `computeBbox()`) then re-sorts in Kotlin with `haversineDistanceMeters`. No SQLite trig required.

**`WaypointRepository.setPosition`**: Uses `shiftDown`/`shiftUp` SQL transactions to maintain gapless integer positions — port of the two-step reorder in `waypoints.repo.ts`.

**`SettingsMigration`**: `PrefSpec<T>` + `migrateStoredValue()` — a pure stepwise migration chain. DataStore holds the raw strings; this class does version-detection and migration logic only.

**SQLDelight database name**: `BoldExplorerDatabase`, package `com.boldexplorer.db`.

### Current status

Phases 1–6 are implemented. Treat new work as hardening or feature completion, not initial scaffolding. Prototype data migration from the old Vue/Capacitor app is not required.

### Release readiness checklist

- `make test` passes.
- `make assemble` builds a debug APK.
- Physical device pass: GPS fix, compass heading, background tracking with screen off.
- Headphones pass: accuracy beacon pitch changes and alignment pan points left/right correctly.
- TTS pass: waypoint reached and trail complete announcements are spoken.
- TalkBack pass: all icon-only controls have labels, live announcements are polite, touch targets are usable.

### Accessibility constraints (non-negotiable)

This app is built for a blind user. Audio cues are the primary interface.
- Every icon-only element needs `Modifier.semantics { contentDescription = "..." }`
- State transitions (waypoint reached, trail complete) must be announced via `TtsEngine`, not just reflected in UI state
- Live regions: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on announcement composables
- Minimum 48dp touch targets
- TalkBack pass required before any phase is considered complete

**`contentDescription` on containers overrides all child text** — when you set `contentDescription` in `Modifier.semantics { }` on a container (Card, Row, Box, etc.), TalkBack reads *only* that string and ignores every child `Text()` composable inside it. Always include every piece of visible information (name, distance, state) explicitly in the container's `contentDescription`. Never assume child text will be read automatically once a container-level description is set.

### Version pins

All dependency versions are in `gradle/libs.versions.toml`. Do not add version strings inline in `build.gradle.kts` files — add to the TOML and reference via `libs.*` accessors.

## Multi-agent coordination

When multiple agents work on this repo simultaneously, use the module boundary as the natural partition:

- **`:shared` work** (algorithms, models, navigation, audio scheduling, settings migration) is self-contained and testable with `make test-shared`. Agents working here never need Android tooling.
- **`:app` work** (database impls, location service, audio engine, Compose UI) depends on `:shared` interfaces but not `:shared` internals.

Safe to parallelize across agents:
- One agent on `:shared` (pure logic) while another is on `:app` (Android layer) — no file overlap.
- Multiple agents on different `:app` packages (e.g., `db/` vs `audio/` vs `ui/`) — packages are independent until wired together in Hilt modules and ViewModels.

Coordination points that require sequencing:
- Repository interface changes in `shared/.../repository/Repositories.kt` must land before `:app` impls are written or modified.
- New `AudioCueEvent` variants in `:shared` must land before `AudioCuePlayer` handling in `:app`.
- SQLDelight schema changes (`.sq` files) must be followed by `./gradlew :app:generateBoldExplorerDatabaseInterface` before any Kotlin that references the generated queries.
- When architecture or commands change, update only `AGENTS.md`. `CLAUDE.md` defers to it and needs no sync.

## Rules

- Use jujutsu over git when available. When possible use jujutsu commands if we are in a jujutsu repository.
