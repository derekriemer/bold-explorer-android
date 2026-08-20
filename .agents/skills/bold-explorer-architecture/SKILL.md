---
name: bold-explorer-architecture
description: Change Bold Explorer's cross-module architecture, audio/output routing, repositories, settings, or SQLDelight persistence. Do not use for isolated UI styling or ordinary test edits.
---

# Bold Explorer architecture

`:shared` is pure Kotlin Multiplatform for algorithms, domain models, state
machines, and repository interfaces; `:app` contains Android framework use and
their implementations. The original Vue/Capacitor project at
`../js-bold-explorer/` is a reference only: port from it but never modify it.

## Output and audio

`OutputManager` is the only feature-level entry point for spoken and live-region
output. ViewModels and `AlignmentController` create an `OutputEvent` and call
`OutputManager.emit`; they must not call `TtsEngine`, a live-region `StateFlow`,
or `AudioEventLog` directly.

`OutputRouter` is the singleton sole consumer, started from app creation. It
applies `OutputPolicy`, updates the app-wide live region, sends TTS only while
backgrounded when allowed, and always logs the event. The event origin is
essential: absolute silence suppresses automatic and interaction-confirmation
events but not user-requested events. It never auto-restores.

`AudioCueScheduler` remains pure scheduling in `:shared`; `AudioCuePlayer` is
the Android playback bridge. Earcons currently enforce absolute silence in
`AudioCuePlayer` directly, pending the tracked unification with `OutputPolicy`.

## Persistence and settings

Repository interfaces live in `Repositories.kt`; implementations are Hilt-bound
in `:app`. `BoldExplorerDatabase` is the SQLDelight database in package
`com.boldexplorer.db`.

`WaypointRepository.withDistanceFrom` uses an anti-meridian-aware bounding-box
query then Kotlin Haversine sorting—do not add SQLite trigonometry.
`setPosition` uses the `shiftDown`/`shiftUp` transaction pair to preserve
gapless positions. `SettingsMigration` is a pure stepwise migration over raw
DataStore values.

When adding a shared interface, output event, or audio cue event, change its
Android implementation/consumer only after the shared contract is present.
After changing `.sq` schema, run
`./gradlew :app:generateBoldExplorerDatabaseInterface` before compiling code
that uses generated queries.
