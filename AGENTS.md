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
make assemble-beta              # build beta APK (release-signed, debug features on)
make assemble-release           # build release APK (no debug tab, no coordinates in logs)
make install                    # push debug APK to connected device
make logcat                     # tail app + crash logs (adb logcat -s BoldExplorer)
make adb-connect                # connect via Tailscale (set PHONE_IP + PHONE_PORT in env)
```

Run a single test class:
```bash
./gradlew :shared:jvmTest --tests "com.boldexplorer.shared.geo.GeoMathTest"
./gradlew :app:testDebugUnitTest --tests "com.boldexplorer.db.WaypointRepositoryTest"
```

## Building & Distribution

### Build variants

| Variant | `SHOW_DEBUG_FEATURES` | Debug tab | Coordinates in logs | Signing |
|---------|-----------------------|-----------|---------------------|---------|
| `debug` | `true` | ✓ | ✓ | debug key |
| `beta`  | `true` | ✓ | ✓ | release key |
| `release` | `false` | ✗ | ✗ | release key |

**debug** — daily development. Sideloaded via ADB. `make assemble && make install`.

**beta** — what you distribute for testing (F-Droid beta track, direct APK share). Has the debug tab and full coordinate logging so you can pull walk logs and debug navigation. Release-signed so it can be installed alongside a future production build.

**release** — production. No debug tab, no lat/lng in logs.

### Building each variant

```bash
# Debug (sideload for dev)
make assemble          # → app/build/outputs/apk/debug/app-debug.apk
make install           # build + push to connected device in one step

# Beta (for distribution / testing)
make assemble-beta     # → app/build/outputs/apk/beta/app-beta.apk

# Release (production)
make assemble-release  # → app/build/outputs/apk/release/app-release-unsigned.apk
```

### Signing setup (required for beta + release)

Create `app/keystore.properties` (not committed — add to `.gitignore`):

```properties
storeFile=/path/to/boldexplorer.jks
storePassword=...
keyAlias=boldexplorer
keyPassword=...
```

Then add to `app/build.gradle.kts`:

```kotlin
val keystoreProps = java.util.Properties().also { props ->
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile     = keystoreProps["storeFile"]?.let { file(it) }
            storePassword = keystoreProps["storePassword"] as String?
            keyAlias      = keystoreProps["keyAlias"] as String?
            keyPassword   = keystoreProps["keyPassword"] as String?
        }
    }
    buildTypes {
        getByName("release")   { signingConfig = signingConfigs.getByName("release") }
        getByName("beta")      { signingConfig = signingConfigs.getByName("release") }
    }
}
```

Generate a keystore once:
```bash
keytool -genkey -v -keystore boldexplorer.jks \
  -alias boldexplorer -keyalg RSA -keysize 2048 -validity 10000
```

Keep `boldexplorer.jks` and `keystore.properties` out of version control. Back up the keystore — losing it means you can never update the app on a device that has it installed.

### F-Droid

F-Droid builds from source, so the signing setup above is not needed for the F-Droid track (they sign with their own key). What F-Droid requires:

- A public Git repo
- A reproducible build (standard Gradle, no proprietary SDKs in the build path — play-services-location is a dependency but is only used at runtime and can be replaced with a pure GNSS provider for a fully FOSS build)
- A metadata file in their `fdroiddata` repo describing the build recipe

For now the `beta` APK can be distributed directly (shared as a file, or via a self-hosted F-Droid repo) before the app is accepted into the main F-Droid catalog.

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
- State transitions (waypoint reached, trail complete) must be announced via `TtsEngine`, not just reflected in UI state
- Live regions: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on announcement composables
- Minimum 48dp touch targets
- TalkBack pass required before any phase is considered complete

**Do not add `contentDescription` as a default reflex.** Setting it on a composable — especially a container (`Card`, `Row`, `Box`) — *replaces* the accessible name entirely; TalkBack reads only your string and ignores all child `Text()`. This codebase has repeatedly accumulated `contentDescription` strings that just restate visible text (sometimes verbatim) or a status the platform already announces natively. That is the accessibility equivalent of overusing `aria-label`, and it makes the *real* additions (the ones conveying something a screen-reader user genuinely can't get otherwise) harder to spot.

**Decision test — apply before adding any `contentDescription`:**
1. Does the composable already have visible text as a direct child that says the same thing? → don't add it (or delete it if present).
2. Is this one of several visually-identical repeated controls (e.g. a "Delete" button on every list row) where the visible text alone is ambiguous out of context? → add one, but only *visible text + the minimum disambiguator* (e.g. the item's name), nothing else.
3. Is the "extra" state you want to convey already exposed to TalkBack natively (`Switch`, `Checkbox`, `Modifier.selectable`/`toggleable(role = Role.RadioButton/Switch)`, `SegmentedButton`'s built-in selected state)? → don't add it. If the state genuinely isn't being announced, that's a missing-semantics bug — wrap the control in `Modifier.toggleable`/`selectable` so the label and native state merge into one node, instead of hand-writing "on"/"off"/"selected" into a string.
4. Is this a live-region/announcement `Text` where the description would just repeat the `Text`'s own content? → don't add it.
5. Still need one after 1–4? Add it, and add an inline `// a11y: <one-line reason>` comment next to the assignment explaining what it conveys that the visible text/native semantics don't. A detekt rule enforces that every `contentDescription` assignment carries this comment (see `detekt-rules`).

Bad (from this codebase, since fixed):
```kotlin
TextButton(
    onClick = { onAction(GpsAction.StopAlignment) },
    modifier = Modifier.semantics { contentDescription = "Stop alignment guidance" },
) { Text("Stop alignment") }
```
The button already reads "Stop alignment" via its visible text; the description just restates it.

Good:
```kotlin
TextButton(
    onClick = { onConfirm(col.id) },
    modifier = Modifier.fillMaxWidth().semantics {
        // a11y: visible text is just the collection's name; this names the action too.
        contentDescription = "Move to ${col.name}"
    },
) { Text(col.name) }
```
Here the dialog lists several collections and every button's visible text is just the name — without the description, TalkBack can't tell "tap to move here" from any other name-only button.

**When you do need to merge a label with a native control's state** (e.g. a `Checkbox`/`Switch` with a separate label `Text`), prefer wrapping both in `Modifier.toggleable(role = Role.Checkbox/Switch)` (with the control's own `onCheckedChange = null`) over hand-writing the state into a `contentDescription` string — see `TentativeCheckboxRow` (`ui/common/`) or the "Auto-advance" row in `GpsScreen.kt` for the pattern. This gets the on/off announcement from the platform for free and stays correct if the wording of "on"/"off" ever changes.

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

## Audio log format

Session logs are JSONL files (`bold_explorer_audio_log_<timestamp>.jsonl`), one JSON object per line, oldest-first. Each line maps to `AudioLogEntry`:

```
{ "ts": <epochMs>, "kind": <Kind>, "trigger": <string>, "inputs": <string>,
  "outputs": <string>, "played": <string>, "note": <string (optional)> }
```

### `kind` values

| Kind | What it records |
|------|----------------|
| `DIRECTIONAL_BEACON` | Each compass-ping cycle: bearing to target, user heading, relative angle, speed, smoothing state |
| `ACCURACY_BEACON` | Accuracy-ring audio ping |
| `ALIGNMENT_PING` | Stereo pan/earcon for heading alignment |
| `WAYPOINT_APPROACH` | Proximity ramp-up as user nears a waypoint |
| `TRAIL_COMPLETE` | Trail finished event |
| `TTS_ANNOUNCEMENT` | Any spoken text event (nav cues, alerts, collection points, recording state) |
| `DETECTION_STATE` | Internal detector decisions: `OffTrailCheck`, `NearbyPoint`, etc. |
| `USER_MARKER` | In-field note pressed by the user; `note` field contains the text |

### `played` field semantics

The `played` field records the *audio outcome*, not intent:

- `"Spoke: '<text>'"` — TTS was actually spoken aloud (app was **backgrounded**)
- `"Suppressed: '<text>'"` — TTS was skipped because the app was **foregrounded**; the UI live region announced it via TalkBack instead. This is **normal and expected** — do not treat it as a missing announcement.
- `"Wrong-vector earcon"` / `"Accuracy earcon"` / similar — a non-TTS audio tone played
- `"bail:<reason>"` — the detector ran but decided not to act (e.g. `bail:no_relative_deg`, `bail:count_1_of_2`)
- `""` (empty) — no audio output for this event (informational log only)

### `inputs` / `outputs` fields

Both are free-form `key=value, key=value` strings. Common fields in `DIRECTIONAL_BEACON` inputs:

- `relativeDeg` — bearing from user's heading to target (null when heading is unavailable)
- `userSpeed_ms` — GPS speed in m/s
- `smoothedHeadingDeg` / `rawHeadingDeg` — smoothed and raw GPS course
- `smoothedConfidence` — 0–1 confidence in the smoothed heading
- `courseIsSmoothed` — whether the beacon used the smoothed course

### Reading a log

1. **USER_MARKERs first** — grep for `"kind":"USER_MARKER"` to see the user's in-field notes; these are the highest-signal observations.
2. **TTS_ANNOUNCEMENTs** — `"ttsDelivered":true` means the app was backgrounded and spoke aloud. `"Suppressed"` in `played` means foregrounded (normal).
3. **DETECTION_STATE bail clusters** — repeated `bail:no_relative_deg` means off-trail detection was blind (user nearly stationary, heading unknown).
4. **Duplicate-event clusters** — same `trigger` + same target within a few seconds = proximity threshold being crossed repeatedly.
5. **Timestamps** — `ts` is Unix epoch milliseconds. Gaps > 30s with no `DIRECTIONAL_BEACON` entries usually mean the app was paused or the screen was off and GPS duty-cycled.

## Rules
### Commit guidelines

- Write commit messages in imperative mood, present tense (e.g. "Add punctuation delegation layer", not "Added" or "Adding").
- One commit per logical unit of work — roughly corresponding to a phase in a plan, or a discrete feature/fix that someone might want to inspect or revert independently.
- Commit message format:
  - **Subject line**: 50–72 characters, summarizing what the commit does
  - **Body** (optional but encouraged for non-trivial work): explain *why*, not just *what*. Note any tradeoffs, alternatives considered, or follow-up work needed.
- Before committing, run `jj diff` and confirm the change matches the intended scope. If unrelated changes crept in, split them with `jj split` before committing.
- Do not bundle unrelated fixes or features into a single commit even if they are small.
- If work spans multiple phases of a plan, each phase gets its own commit unless a phase is trivially small (e.g. a one-line config change directly enabling the next phase — squash that in).


### Version control

- Use jujutsu over git when available. When possible use jujutsu commands if we are in a jujutsu repository.
- When using jujutsu, always start work by jj new, unless we are 
    1. On a new commit already.
    2. squashing a quick fix into existing work.
    3. performing work on the graph, such as resolving merge conflicts, investigating the app without changes.
    4. Editing a commit that is not a head.

- Commit work when ideally starting a new jj commit with `jj describe`, or `jj commit` if making new work. Use `jj describe` again if needed as things change with the commit message based on the work completed.
- If an unrelated refactor is done during other work, split it out with `jj split`, rebase it onto a separate bookmark off of the parent commit, and note it to the user for review or integration later.

### Shell tool preferences
Always prefer `rg` over `grep` and `fd` over `find` when available. 
Never use bare `grep -r` for code search unless there are no other options, and tell the user you had to do so. performance is horrid with grep -R.
