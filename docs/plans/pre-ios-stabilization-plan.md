# Pre-iOS Stabilization Plan

Status: draft for review. Written 2026-08-06 against commit `d39189c`.

This plan is grounded in the repository as it stands today. Statements under "Observed" are things
verified by reading the code or by field reports recorded in the tracker. Statements under
"Recommendation" are proposals that have not been implemented and are open to being overruled.

No implementation code was changed in producing this document.

## Scope

The goal is a stable behavioral contract before the major iOS push, so that shared semantics get
ported rather than re-derived. The pre-iOS gate is:

- alignment sessions have correct start, stop, dismissal and periodic announcement behavior;
- audio focus is acquired and released correctly;
- audio ducking settings affect real output behavior;
- background services stop or persist according to an explicit documented policy;
- recording controls reflect actual recording capability;
- trail completion and proximity thresholds are bounded and evidence-based;
- skipped waypoints and trail re-entry work reliably;
- following, recording and waypoint targeting are independent state dimensions;
- the active trail and recording destination cannot become ambiguous.

## 1. Current-state findings

### 1.1 Alignment

**Code.** `app/src/main/kotlin/com/boldexplorer/ui/gps/AlignmentController.kt` holds the whole
alignment domain: `active`, `bearingDeg`, and a derived `relativeDeg`. It is audio-agnostic by
design. `app/src/main/kotlin/com/boldexplorer/ui/gps/AlignmentDialog.kt` renders the controls.
`app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt` owns start/stop
(`startAlignment`/`stopAlignment`, lines 1005-1021) and hands the audio side to
`app/src/main/kotlin/com/boldexplorer/location/GpsBackgroundSession.kt`
(`startAlignment`/`stopAlignment`, lines 84-93).

**Lifecycle ownership.** `AlignmentController` is constructed inline in `GpsViewModel`
(`GpsViewModel.kt:555`), so alignment state is scoped to the ViewModel and does not survive process
recreation. The alignment audio session, by contrast, runs through the `@Singleton`
`GpsBackgroundSession` and `AudioCuePlayer`.

**Observed contradictions.**

- Back and outside-tap both route through `onDismissRequest = onDismiss`
  (`AlignmentDialog.kt:112`), which does not stop alignment. This is documented as deliberate
  (`AlignmentDialog.kt:41`, and the Close button's `contentDescription = "Close, keep alignment
  running"`), but it means Back leaves an audio session and a compass loop running with no visible
  surface. Tracked as #49.
- The five-second readout exists but is opt-in (`var autoRead by remember { mutableStateOf(false) }`,
  `AlignmentDialog.kt:52`), lives in a `LaunchedEffect` inside the dialog composable
  (`AlignmentDialog.kt:66-77`) so it dies on dismissal, resets on reopen, and writes to a hidden
  live-region `Text` rather than through `OutputManager` - so it cannot speak when backgrounded and
  is invisible to `OutputPolicy` and `AudioEventLog`. Tracked as #50.
- `_audioStartedForAlignment` (`GpsViewModel.kt:614`) is written in three places and never read.

**Tests.** None. There is no test file for `AlignmentController` in either module.
`shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/BearingComputerTest.kt` covers the
phrasing helper only.

**Tracker.** #49, #50, #51 (new); #45 (fixed in `d11a92e`, closed 2026-08-06).

### 1.2 Audio focus and ducking

**Code.** `app/src/main/kotlin/com/boldexplorer/audio/AudioCuePlayer.kt` is the only place that
requests audio focus. `app/src/main/kotlin/com/boldexplorer/audio/AudioEngine.kt` owns the
`AudioTrack`. `app/src/main/kotlin/com/boldexplorer/audio/TtsEngine.kt` requests no focus at all.
The user-facing toggle is in `app/src/main/kotlin/com/boldexplorer/ui/settings/SettingsScreen.kt`
(around line 157) backed by `duckAudioEnabled` in
`shared/src/commonMain/kotlin/com/boldexplorer/shared/settings/AppSettings.kt` and persisted via
`app/src/main/kotlin/com/boldexplorer/settings/DataStoreSettingsRepository.kt`.

**Observed.**

- There is no long-lived audio focus request anywhere in the app. The field report of "retains audio
  focus indefinitely" describes a real symptom with a different mechanism.
- What is long-lived is the output stream: `AudioEngine.start()` opens one streaming `AudioTrack`
  (`USAGE_ASSISTANCE_ACCESSIBILITY` / `CONTENT_TYPE_SONIFICATION`) and runs a `while (isActive)`
  keepalive coroutine writing silence for the entire navigation session, to stop Bluetooth A2DP
  from idling (`AudioEngine.kt:63-95`).
- Ducking is requested at `AudioCuePlayer.kt:119` and abandoned at line 304, in the same
  synchronous function. Every `AudioEngine.play*` call in between is `scope.launch { ... }` and then
  waits on `toneMutex`, so focus is released before the tone starts. The duck window never overlaps
  the audio it exists to duck. This is a complete explanation for "the setting does nothing".
- `AudioCuePlayer.dispatch()` calls `runBlocking { settingsRepo.load() }` on every cue
  (`AudioCuePlayer.kt:117`).
- Absolute silence mode gates the `play*` calls but not `audioEngine.start()`, so the keepalive
  stream runs even when the app is meant to be silent.

**Tests.** `shared/src/commonTest/kotlin/com/boldexplorer/shared/audio/AudioCueSchedulerTest.kt`
covers scheduling. Nothing covers `AudioCuePlayer`, `AudioEngine` or focus behavior.

**Tracker.** #53 (new, ownership and dictation), #41 (reused and updated with the root cause), #22
(pre-existing: route earcons through `OutputManager`).

### 1.3 Foreground and background service lifecycle

**Code.** `app/src/main/kotlin/com/boldexplorer/location/LocationForegroundService.kt`,
`app/src/main/kotlin/com/boldexplorer/location/GpsBackgroundSession.kt`,
`app/src/main/kotlin/com/boldexplorer/location/LocationViewModel.kt` (start/stop intents),
`app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/boldexplorer/BoldExplorerApp.kt`,
`app/src/main/kotlin/com/boldexplorer/audio/AppForegroundState.kt` (process lifecycle observer).

**Observed.**

- No `onTaskRemoved()` override; `START_STICKY` (`LocationForegroundService.kt:63`); no
  `android:stopWithTask` on the service element in the manifest, which defaults to false. Swiping
  away therefore leaves the foreground service and the process alive.
- `ACTION_STOP` is a no-op while any mode is still registered (`LocationForegroundService.kt:57`),
  and modes are only cleared by explicit user actions in `GpsViewModel`.
- `GpsViewModel.onCleared()` (`GpsViewModel.kt:1740`) stops beacon navigation but nothing else. That
  is why audio stops on swipe-away while GPS, the notification and the session modes persist - the
  subsystems disagree by construction.
- The notification is static ("GPS tracking active") with no stop action
  (`LocationForegroundService.kt:119-126`).
- `app/src/main/kotlin/com/boldexplorer/location/AccuracyHapticMonitor.kt` is a `@Singleton` whose
  `init` launches two coroutines on a scope that is never cancelled, including a `while (true)`
  5-second tick that vibrates whenever `_enabled` is true. `_enabled` is in-memory only, toggled
  from `app/src/main/kotlin/com/boldexplorer/ui/debug/DebugViewModel.kt:66`. This is the reported
  post-swipe haptic.

**Tests.** None. There are no instrumented or Robolectric tests in the repository; `app/src/test`
contains only database and GPX parser tests.

**Tracker.** #54 (new).

### 1.4 GNSS and debug haptics

**Code.** `app/src/google/kotlin/com/boldexplorer/location/LocationProviderRouter.kt` (and the
`foss` counterpart), `app/src/main/kotlin/com/boldexplorer/location/GnssLocationProviderImpl.kt`,
`app/src/google/kotlin/com/boldexplorer/location/FusedLocationProviderImpl.kt`,
`app/src/main/kotlin/com/boldexplorer/location/RawFixEvent.kt`,
`shared/src/commonMain/kotlin/com/boldexplorer/shared/location/AccuracyGate.kt`.

**Observed.**

- The GNSS upstream is held by `shareIn(scope, SharingStarted.WhileSubscribed(5_000))` in both the
  provider and the router. Subscription ownership is therefore implicit: any `collect` anywhere in
  the process keeps GPS alive, and the foreground service deliberately holds one purely as a keeper
  (`LocationForegroundService.kt:82-86`).
- `LocationProviderRouter.lastRawFix` uses `SharingStarted.Eagerly` on an app-scoped scope
  (`LocationProviderRouter.kt:66`), so it is always collecting.
- The accuracy gate is movement-relative in the foreground (10 m base, 50 m absolute ceiling) and a
  flat 50 m in background mode (`GnssLocationProviderImpl.kt` companion object).

**Tests.** `shared/src/commonTest/kotlin/com/boldexplorer/shared/location/AccuracyGateTest.kt`,
`LocationStalenessTest.kt`, `FakeLocationProvider.kt`.

**Tracker.** #23 and #48 (pre-existing GPS work); #54 covers the subscription-ownership change.

### 1.5 Trail recording controls

**Code.** `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailRecordingMachine.kt`,
`shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NavMode.kt`,
`shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NavModeResolver.kt`,
`app/src/main/kotlin/com/boldexplorer/ui/gps/GpsScreen.kt` (`ContextualTrailActions`, lines
702-787), `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt`
(`startAutoRecord`/`stopAutoRecord`/`extendTrailFromCollectionEnd`/`maybeRecordTrackPoint`),
`app/src/main/kotlin/com/boldexplorer/db/WaypointRepositoryImpl.kt` (`createTrackPoint`).

**Observed.**

- "Start Auto-Record" is rendered on `recordingState is TrailRecordingState.Selected`
  (`GpsScreen.kt:771`), outside the `when (navMode)` block, with no positional gate.
  `Selected` has no transition back to `Idle` (#3) and `_selectedTrailId` is never cleared (#25),
  so the control persists indefinitely after a trail is touched once.
- `extendTrailFromCollectionEnd()` (`GpsViewModel.kt:1584`) is `selectTrail(...)` +
  `startAutoRecord()`. "Extend" is not a distinct capability.
- `NavModeResolver.canExtend()` gates on distance only and ignores `TrailEnd.isStart`, while
  `createTrackPoint` with `position = null` always appends at `nextPosition(trailId)`
  (`WaypointRepositoryImpl.kt:99-105`). Extending from a trail's start end therefore appends the
  walk to the trail's tail. This is a data-integrity defect, not only a labelling problem.
- Auto-record captures a point every 10 m (`AUTO_RECORD_DISTANCE_M`, `GpsViewModel.kt:1746`).

**Tests.**
`shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/TrailRecordingMachineTest.kt`,
`NavModeResolverTest.kt`,
`app/src/test/kotlin/com/boldexplorer/db/WaypointRepositoryTest.kt`,
`app/src/test/kotlin/com/boldexplorer/db/WaypointOwnershipInvariantTest.kt`. None cover the
start-end append case.

**Tracker.** #52 (new); #3, #25, #27, #28 (pre-existing, cross-linked).

### 1.6 Trail progress and completion

**Code.** `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailFollower.kt`,
`shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailGuidance.kt`,
`shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailGuidanceCoordinator.kt`,
`shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/CollectionExplorer.kt`,
`shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NearbyTrailResolver.kt`,
`shared/src/commonMain/kotlin/com/boldexplorer/shared/geo/GeoMath.kt`.

**Observed thresholds.** Fixed, no accuracy scaling: `REACH_THRESHOLD_M = 15.0`,
`TRAIL_APPROACH_M = 10.0`, `PROXIMITY_M = 30.0` (`CollectionExplorer.kt:333-335`);
`TrailFollower(defaultThresholdM = 15.0)`; `MIN_MOVEMENT_SINCE_ADVANCE_M = 5.0`. Accuracy-scaled
with a floor and no upper cap: `max(15, 2 * accuracy)` (`NavModeResolver.kt:138-141`) and
`max(20, 2 * accuracy)` (`NearbyTrailResolver.kt:85-88`).

**Observed.** The reported 50-60 ft false arrival matches `REACH_THRESHOLD_M = 15.0` exactly. The
reported "about 30 m" early completion is not a constant: `TrailFollower`'s projection branch
completes when the user is 90 percent along the final segment and within `4 x threshold` (60 m)
(`TrailFollower.kt:184-202`), so a 300 m final leg completes at 30 m out. The divergence branch
cannot fire on the last waypoint, so it is not the cause.

**Tests.** `shared/src/commonTest/kotlin/com/boldexplorer/shared/navigation/TrailFollowerTest.kt`,
`TrailGuidanceTest.kt`, `TrailGuidanceCoordinatorTest.kt`, `CollectionExplorerTest.kt`,
`NearbyTrailResolverTest.kt`, `NavigationTargetResolverTest.kt`, `GpsHeadingSmootherTest.kt`.

**Tracker.** #55 (new); #35, #23 (pre-existing, cross-linked).

### 1.7 Skipped waypoints and re-entry

**Observed.** `TrailFollower.fireAdvance()` only ever moves `currentIndex` forward by one
(`TrailFollower.kt:247`). There is no re-match, forward jump or re-entry path. Combined with the
5 m anti-cascade guard from #9, catching up 30 skipped track points is effectively impossible.

Separately, `TrailGuidanceCoordinator.evaluateOffTrail()` (`TrailGuidanceCoordinator.kt:169`)
decides off-trail purely from `TrailGuidance.isMajorCorrection(relativeDeg)`, i.e.
`abs(relativeDeg) >= 60.0` against the bearing to the current target waypoint
(`TrailGuidance.kt:94`). Cross-track distance to the trail line is never consulted, although
`distanceToSegmentMeters` exists and is used elsewhere. A user standing on the trail with a stale
target 300 m behind them satisfies the off-trail condition on every fix, and the alert repeats every
45 s (`OFF_TRAIL_ALERT_INTERVAL_MS`). That is a complete explanation of the reported symptom.

**Tracker.** #56 (new); #35 (pre-existing).

### 1.8 Navigation versus recording state

**Observed.** The mutual exclusion is explicit and enforced in three places: the
`TrailRecordingMachine` KDoc and its `reject()` transitions; `GpsViewModel.enterFollowing()`
(`GpsViewModel.kt:935`), which calls `recordingMachine.stop()` first; and `NavModeResolver.fold()`
(`NavModeResolver.kt:75-79`), which collapses everything into one `NavMode` branch that `GpsScreen`
renders one at a time.

All state holders are constructed inline in `GpsViewModel`: `TrailGuidanceCoordinator` (:379),
`TrailRecordingMachine` (:402), `TrailFollower` (:476), `CollectionExplorer` (:491),
`NavModeResolver` (:537), `AlignmentController` (:555). None survive process recreation, while
`GpsBackgroundSession` and the foreground service do. That mismatch is the shared root of the
"partially active session" and "ambiguous recording destination" risks.

**Tracker.** #57 (new); #3 (pre-existing, partly superseded).

### 1.9 KMP shared-domain boundaries

`:shared` currently holds geometry, navigation state machines, audio cue scheduling, the output
model and policy, settings migration, and repository interfaces (see the file list under
`shared/src/commonMain/kotlin/com/boldexplorer/shared/`). `:app` holds Android APIs and all
ViewModels.

The boundary is mostly clean, with these gaps relevant to this plan:

- audio focus and output-stream ownership are entirely in `:app` and have no shared policy object;
- foreground/background session policy is entirely in `:app` (`GpsBackgroundSession`);
- the alignment session lives in `:app` (`AlignmentController`) even though it is pure logic;
- threshold constants are spread across four `:shared` files with no single policy object.

Everything a second platform has to re-implement should be named before the port starts.

### 1.10 Current iOS implementation state

There is no iOS application in this repository. `shared/build.gradle.kts` declares
`iosArm64()`, `iosSimulatorArm64()` and `iosX64()` targets and `make xcframework` produces
`BoldExplorerShared.xcframework` on macOS. `shared/src` contains only `commonMain` and `commonTest`
- there is no `iosMain` source set and no Xcode project or `Package.swift` anywhere in the tree.

The practical consequence: nothing has been copied to iOS yet, so every item in section 2 is still
cheap to fix. That is the argument for doing them now.

## 2. Confirmed pre-iOS blockers

| Issue | Title | Affected code | Why it blocks iOS | Depends on | Validation |
|---|---|---|---|---|---|
| #49 | Back must stop alignment | `ui/gps/AlignmentDialog.kt`, `ui/gps/GpsViewModel.kt`, `ui/gps/AlignmentController.kt` | Dismissal semantics have to be one documented contract; iOS has no Back button and will invent its own if none exists | - | Unit tests on the controller; manual TalkBack script |
| #50 | Five-second alignment announcements by default | `ui/gps/AlignmentDialog.kt`, `ui/gps/AlignmentController.kt`, `shared/src/commonMain/kotlin/com/boldexplorer/shared/output/` | Periodic announcement ownership and interval belong in shared code, not in a Compose effect | #49 | Unit tests for timer ownership and no-duplicate-timer |
| #52 | Contextual recording control | `ui/gps/GpsScreen.kt`, `ui/gps/GpsViewModel.kt`, `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailRecordingMachine.kt`, `db/WaypointRepositoryImpl.kt` | Recording semantics get ported verbatim; the start-end append defect would be ported with them | #57 | Unit tests over all nine trail states; DB test for attachment point |
| #53 | Audio ownership and dictation | `audio/AudioEngine.kt`, `audio/AudioCuePlayer.kt` | The chosen model becomes the `AVAudioSession` category and options on iOS | - | Device test with recognizer and Bluetooth; focus/keepalive events in `AudioEventLog` |
| #41 | Duck setting has no effect | `audio/AudioCuePlayer.kt` | A setting that does nothing on Android will be replicated as a setting that does nothing on iOS | investigate with #53 | Manual A/B with music playing; log which mode applied |
| #54 | Swipe-away lifecycle | `location/LocationForegroundService.kt`, `location/GpsBackgroundSession.kt`, `location/AccuracyHapticMonitor.kt`, `AndroidManifest.xml` | The persistence policy is a product decision that iOS must mirror deliberately, and background-location capability must be declared rather than accidental | - | Manual swipe-away matrix; subscription-registry unit tests |
| #55 | Threshold policy | `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/CollectionExplorer.kt`, `TrailFollower.kt`, `NavModeResolver.kt`, `NearbyTrailResolver.kt` | Thresholds are shared-domain constants; porting them before they are evidence-based bakes in the wrong numbers twice | telemetry first | Field logs across four terrain types; unit tests for the clamp |
| #56 | Skipped waypoints and re-entry | `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailFollower.kt`, `TrailGuidanceCoordinator.kt`, `TrailGuidance.kt` | Progress matching is shared-domain logic and is currently wrong | #55 | Synthetic track fixtures: 3-skip, 30-skip, switchback, loop, parallel |
| #57 | Independent state dimensions | `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailRecordingMachine.kt`, `NavMode.kt`, `NavModeResolver.kt`, `ui/gps/GpsViewModel.kt` | This is the model the iOS app would be built on; getting it wrong is the most expensive item on the list | #54 for ownership | Unit tests over the valid and invalid combination matrix |

#51 (alignment polish) is P1: preferred before the push, may slip if what remains is cosmetic.

## 3. Recommended implementation phases

Phases are ordered so that no downstream heuristic is tuned before the state it depends on is
stable. Phases A and B can overlap: A is `:app` lifecycle and audio, B is `:shared` navigation
logic, and they touch disjoint files (see AGENTS.md, "Multi-agent coordination").

### Phase A: lifecycle and audio correctness

Issues: #49, #50, #53, #41, #54.

Exit criteria:

- Back stops alignment; the keep-running path is a separate, labelled control.
- Alignment announces every five seconds by default, through `OutputManager`, and keeps doing so
  outside the dialog; no duplicate timers across two open/close cycles.
- One documented audio-ownership policy exists, written to be portable to `AVAudioSession`.
- Dictation works while the app is idle, verified on device with and without a Bluetooth headset.
- The duck setting produces an audible difference.
- One documented swipe-away policy exists and is implemented; the debug haptic cannot survive task
  removal; the notification reflects reality and offers a stop action.

### Phase B: trail-following correctness

Issues: #55, then #56.

Exit criteria:

- Every threshold decision emits the telemetry listed in section 6 before any constant is changed.
- Completion no longer depends on an unbounded fraction of the final segment.
- Every accuracy-scaled gate has an upper cap.
- Off-trail consults cross-track distance, not only the angle to a possibly stale target.
- Re-entry after a 30-point skip advances progress within a bounded confirmation window, verified
  against recorded field logs and synthetic fixtures.

### Phase C: shared state correction

Issues: #57, then #52.

Exit criteria:

- Following and recording can be active simultaneously on different trails, with independent
  start/stop.
- UI actions derive from capabilities; no `NavMode`-style single-branch gate on recording controls.
- Exactly one contextual recording control; no separate Extend.
- Recording from a start endpoint writes geometry in the correct order, covered by a DB test.
- Process restoration either preserves both dimensions or refuses to resume ambiguously.

### Phase D: alignment polish and regression hardening

Issue: #51, plus the child issues it produces.

Exit criteria:

- The alignment audit is complete and its findings are filed as child issues or fixed.
- A TalkBack pass over alignment, recording controls and the swipe-away notification is recorded.
- The manual test scripts in section 5 exist and have been run once end to end.

### Phase E: major iOS push

Entry criteria (this is what "stable enough" means):

- All Phase A, B and C exit criteria are met.
- The audio-ownership policy, the background-persistence policy and the threshold policy are each
  written down in one place, in `:shared` or in `docs/`, and are the source of truth.
- The state model in `:shared` expresses navigation target, recording session, alignment session,
  output/mute state and selection as independent dimensions, with tests for the invalid
  combinations.
- `make test-shared` is green and the shared module carries the tests listed in section 5.

Phase D may still be open at this point if only cosmetic items remain.

### Phase F: shared KMP feature backlog

Issues: #58 (breadcrumbs), #59 (trail graph), #60 (fork sonification), #61 (branch-identity
research), #47 (exploratory annotation and repair). None are required before Phase E.

## 4. File-level change map

Only paths that exist in the repository today are listed.

### Phase A

- `app/src/main/kotlin/com/boldexplorer/ui/gps/AlignmentDialog.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/gps/AlignmentController.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsScreen.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/output/OutputEvent.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/output/OutputManager.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/output/OutputPolicy.kt`
- `app/src/main/kotlin/com/boldexplorer/audio/OutputRouter.kt`
- `app/src/main/kotlin/com/boldexplorer/audio/AudioEngine.kt`
- `app/src/main/kotlin/com/boldexplorer/audio/AudioCuePlayer.kt`
- `app/src/main/kotlin/com/boldexplorer/audio/TtsEngine.kt`
- `app/src/main/kotlin/com/boldexplorer/audio/AudioEventLog.kt`
- `app/src/main/kotlin/com/boldexplorer/audio/AudioLogEntry.kt`
- `app/src/main/kotlin/com/boldexplorer/di/AudioModule.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/settings/SettingsScreen.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/settings/SettingsViewModel.kt`
- `app/src/main/kotlin/com/boldexplorer/location/LocationForegroundService.kt`
- `app/src/main/kotlin/com/boldexplorer/location/GpsBackgroundSession.kt`
- `app/src/main/kotlin/com/boldexplorer/location/AccuracyHapticMonitor.kt`
- `app/src/main/kotlin/com/boldexplorer/location/LocationViewModel.kt`
- `app/src/google/kotlin/com/boldexplorer/location/LocationProviderRouter.kt`
- `app/src/foss/kotlin/com/boldexplorer/location/LocationProviderRouter.kt`
- `app/src/main/kotlin/com/boldexplorer/di/LocationModule.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/debug/DebugViewModel.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/debug/DebugScreen.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/com/boldexplorer/BoldExplorerApp.kt`

### Phase B

- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailFollower.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailGuidance.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailGuidanceCoordinator.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/CollectionExplorer.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NearbyTrailResolver.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NavModeResolver.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/GpsHeadingSmoother.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/geo/GeoMath.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/location/AccuracyGate.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt` (telemetry emission)

New file expected in Phase B: a single threshold-policy object under
`shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/`.

### Phase C

- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/TrailRecordingMachine.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NavMode.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NavModeResolver.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/NavigationTargetResolver.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/navigation/CollectionExplorer.kt`
- `shared/src/commonMain/kotlin/com/boldexplorer/shared/repository/Repositories.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsViewModel.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/gps/GpsScreen.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/trails/TrailsViewModel.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/trails/TrailsScreen.kt`
- `app/src/main/kotlin/com/boldexplorer/location/TargetingStateHolder.kt`
- `app/src/main/kotlin/com/boldexplorer/location/SelectedCollectionHolder.kt`
- `app/src/main/kotlin/com/boldexplorer/db/WaypointRepositoryImpl.kt`
- `app/src/main/kotlin/com/boldexplorer/db/TrailRepositoryImpl.kt`

### Phase D

- `app/src/main/kotlin/com/boldexplorer/ui/gps/AlignmentDialog.kt`
- `app/src/main/kotlin/com/boldexplorer/ui/gps/AlignmentController.kt`
- `docs/plans/pre-ios-stabilization-plan.md` (this file, updated with outcomes)
- `AGENTS.md` (if architecture or commands change)

### Phase E

- `shared/build.gradle.kts`
- `Makefile` (`xcframework` target)
- `SPEC.md` (the Android/iOS mapping table already sketches `AVAudioSession` equivalents)

## 5. Test plan

### Unit tests (`:shared`, `make test-shared`)

- Alignment: start/stop/dismiss transitions; timer ownership; no duplicate emissions across two
  open/close cycles; announcement suppression when nothing useful can be said.
- Thresholds: the clamp function at accuracy 3 m, 10 m, 30 m, 100 m; completion does not fire early
  on a long final leg; nearby and completion use different policies.
- Progress matching: 3-point skip, 30-point skip, switchback, loop, parallel path, backward travel;
  re-entry requires bounded confirmation; off-trail does not fire while the user is on the line.
- State dimensions: the valid combination list and the invalid combination list from #57, each
  asserted.
- Recording capability: all nine trail states from #52 map to the expected control label and
  enablement.

### Integration tests (`:app`, `make test`)

- `createTrackPoint` attachment order for start-endpoint continuation (extends
  `app/src/test/kotlin/com/boldexplorer/db/WaypointRepositoryTest.kt`).
- Trail geometry invariants after a continuation session (extends
  `app/src/test/kotlin/com/boldexplorer/db/WaypointOwnershipInvariantTest.kt`).

### Android lifecycle tests

There are no Android instrumented or Robolectric tests today; this is the first thing to add. Cover:
`GpsViewModel.onCleared` clears the session modes it is responsible for; `GpsBackgroundSession`
state transitions; `AccuracyHapticMonitor` stops when its owning debug mode ends.

### Manual scripts (hardware or OS dependent)

**M1 - audio focus and dictation coexistence.**
1. Start audio navigation. 2. Background the app. 3. Open a dictation field in another app and
speak. 4. Record whether recognition starts and completes. 5. Repeat with wired headphones, with a
Bluetooth headset, and with music playing. 6. Repeat with navigation stopped as the control case.
Pass: dictation behaves the same with navigation stopped and with navigation idle-but-enabled.

**M2 - duck verification.**
1. Play music. 2. Toggle "Duck Music During Beacons" on. 3. Navigate until several beacons fire.
4. Repeat with the setting off. Pass: an audible level difference during the tone, and
`AudioEventLog` shows which mode was applied for each cue.

**M3 - removal from recents.**
Run the matrix: navigation inactive / navigation active / recording active / debug haptics active.
For each: swipe the app away, then wait five minutes. Record whether the notification persists,
whether haptics fire, whether GPS is still held (Android's location indicator), and whether battery
stats show ongoing use. Pass: behavior matches the documented policy in every cell, and no cell
leaves invisible work running.

**M4 - process recreation.**
Enable "Don't keep activities", then repeat M3's four cases plus alignment active. Pass: state is
either preserved or safely refused; no recording resumes into an unknown trail.

**M5 - field diagnostics for thresholds.**
Walk the same route under open sky, tree cover, an urban edge, and at slow walking pace. Export the
session JSONL each time (see the audio log format in AGENTS.md) and check that every
nearby/acceptance/advance/completion decision carries the section 6 fields.

**M6 - TalkBack.**
Alignment dialog (focus order, labels, hints, custom actions, Back behavior); the single recording
control (does its label describe what will happen?); the foreground-service notification and its
stop action. Follow the `contentDescription` decision test in AGENTS.md.

### KMP shared-domain tests

Everything in the unit-test list above must live in `shared/src/commonTest` so it runs on the JVM
and continues to cover the iOS targets.

## 6. Instrumentation needed before tuning

All of this should land in `AudioEventLog` (`app/src/main/kotlin/com/boldexplorer/audio/AudioEventLog.kt`,
entries defined in `AudioLogEntry.kt`) so a single exported session answers the question. Add new
`Kind` values where the existing ones do not fit.

| Signal | What to record | Why |
|---|---|---|
| Audio focus | request, gain type, result, abandon, with timestamps | Prove the duck window overlaps the tone (#41) |
| Active audio usage | keepalive start/stop, usage and content type, active route | Identify what actually blocks dictation (#53) |
| Duck setting evaluation | the value read per cue and the branch taken | Distinguish "not persisted" from "not effective" |
| Service lifecycle | onStartCommand action, startForeground, onTaskRemoved, onDestroy, stopSelf | Make the swipe-away matrix readable (#54) |
| GNSS subscription ownership | who subscribed, when, and when the last subscriber left | Prove GPS stops when nothing needs it |
| Haptics subscription ownership | enable/disable and the owning component | Prove the debug haptic cannot outlive its owner |
| Alignment timer ownership | timer start/stop with an owner id | Prove no duplicate timers (#50) |
| Trail matching decisions | chosen segment, candidates considered and rejected, reason | Explain re-entry and false jumps (#56) |
| Completion decisions | which rule fired, distance, segment fraction, final-leg length | Explain early completion (#55) |
| Reported accuracy | horizontal accuracy on every decision, not only on fixes | The clamp input has to be visible |
| Along-track and cross-track | both, on every guidance evaluation | Already partly present at `GpsViewModel.kt:1492-1502`; extend to `CollectionExplorer` |

Existing instrumentation to build on rather than replace: `AdvancementReason`
(`TrailFollower.kt:51`), `RawFixEvent` (`app/src/main/kotlin/com/boldexplorer/location/RawFixEvent.kt`),
and the `DETECTION_STATE` bail strings in `TrailGuidanceCoordinator`.

## 7. Risk register

| Risk | Why it is plausible here | Mitigation |
|---|---|---|
| Copying the wrong state model into iOS | The mutual-exclusion model is enforced in three separate places and reads as intentional | Phase C precedes Phase E; #57 carries an explicit migration warning |
| Retaining audio output and blocking dictation | The keepalive `AudioTrack` exists for a real reason (Bluetooth dropout); removing it naively regresses that | #53 requires the Bluetooth case to be re-tested, not just the dictation case |
| Invisible battery drain | GPS plus a continuous audio stream can both survive swipe-away today | M3 and M4 include battery observation; the notification must reflect reality |
| Duplicate timers or collectors | The alignment ticker currently lives in a composable; app-scoped `@Singleton` init blocks already leak two coroutines | Owner ids in the logs; explicit no-duplicate tests |
| Service and UI state divergence | Already happening: `onCleared` stops audio but not the service | One documented policy and one owner per dimension (#54, #57) |
| False trail jumps at switchbacks | Any forward-jump mechanism trades a stuck follower for a wrong jump | #56 requires bounded confirmation and switchback/parallel-path tests before the mechanism ships |
| Overfitting thresholds to one field location | The current constants appear to be round numbers, not measurements | M5 requires four distinct terrain types before constants change |
| Combining research features with stabilization | #58 through #61 and #47 are attractive and adjacent | Phase F is explicitly after Phase E; none of them are gate items |
| Assuming a fixed bug is a closed issue | #8, #37, #45 and #46 were fixed in commits but stayed open in the tracker until 2026-08-06 | Those four are now closed with the verifying commit and test named in each. #23 remains open on purpose (partly fixed, field verification outstanding) |

## 8. Deferred work

Not required before the iOS push:

- **Breadcrumb recording (#58).** Depends on #57: a breadcrumb session is a second recording
  dimension, and building it on today's mutually exclusive machine would have to be rewritten
  immediately afterward.
- **Automatic graph extraction and segmentation (#59).** Depends on #55, #56 and #57. Extracting a
  graph from geometry whose matching rules are still being tuned would bake the current thresholds
  into the data model.
- **Generated segment names (#59).** Part of the graph work; nothing depends on them.
- **Branch instruments (#60, #61).** #61 is research with no committed outcome; assigning production
  timbres before it completes is exactly what its acceptance criteria forbid.
- **Automatic route switching (#60).** Depends on trustworthy segment matching from #56. Switching
  routes on bad matching is worse than not switching.
- **Wake-word notes (#47).** The comparison against explicit push-to-talk has not been measured;
  the battery cost is unknown and the app already has an in-app recognizer to build the cheap
  version on.
- **Automatic geometry repair (#47).** Depends on #56. Repair proposals are only as good as the
  matching that decides which stretch was exploratory.

## Open questions

1. **Swipe-away policy (blocking for #54).** Which of the four candidate policies is intended?
   The code currently implements none of them deliberately. Recommendation: navigation and
   recording survive removal from recents only when the user explicitly started a foreground
   session, debug-only outputs always stop, and the notification always carries a stop action.
   This is a product decision and should be confirmed before #54 is implemented.
2. **Keepalive versus dictation (open for #53).** Whether the continuous `AudioTrack` is genuinely
   what blocks dictation is a hypothesis consistent with the code, not a measured fact. M1 settles
   it. If it is not the cause, #53's scope narrows to focus hygiene.
3. **Completion policy shape (open for #55).** Whether to bound the projection rule by absolute
   distance on the final leg or to require a separate completion test is not decided; the field
   data from M5 should decide it.
4. **Start-endpoint continuation storage (open for #52).** Prepending requires either negative or
   renumbered positions in `trail_waypoint`, or storing the continuation as a separate segment.
   The choice interacts with #59 and should be made with that in mind.
5. **Fate of #3.** Once #52 and #57 land, #3 is either closed or narrowed to machine-internal
   cleanup. Owner's call.
