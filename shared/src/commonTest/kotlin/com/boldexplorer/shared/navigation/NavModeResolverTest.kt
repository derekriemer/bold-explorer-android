package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavModeResolverTest {
    private val recordingState = MutableStateFlow<TrailRecordingState>(TrailRecordingState.Idle)
    private val explorerState = MutableStateFlow<CollectionExplorerState>(CollectionExplorerState.Idle)
    private val selectedCollectionId = MutableStateFlow<Long?>(null)
    private val location = MutableStateFlow<LocationSample?>(null)
    private val accuracyM = MutableStateFlow<Double?>(null)

    private fun resolver(scope: CoroutineScope) =
        NavModeResolver(scope, recordingState, explorerState, selectedCollectionId, location, accuracyM)

    /** Build a resolver on [backgroundScope], keep its WhileSubscribed flow hot, settle, return it. */
    private suspend fun TestScope.settledResolver(): NavModeResolver {
        val r = resolver(backgroundScope)
        backgroundScope.launch { r.navMode.collect {} }
        advanceUntilIdle()
        return r
    }

    private fun waypoint(
        id: Long,
        lat: Double,
        lon: Double,
    ) = Waypoint(id = id, name = "wp$id", lat = lat, lon = lon, elevM = null, description = null, createdAt = 0L)

    private fun trail(id: Long) = Trail(id = id, name = "Trail$id", description = null, createdAt = 0L)

    private fun trailEnd(
        trailId: Long,
        isStart: Boolean,
        lat: Double,
        lon: Double,
    ) = CollectionPoint.TrailEnd(waypoint(trailId, lat, lon), trail(trailId), isStart)

    private fun active(
        target: CollectionPoint?,
        nearTrailEndM: Double?,
    ) = CollectionExplorerState.Active(
        points = listOfNotNull(target),
        targeting = if (target == null) CollectionTargeting.Auto() else CollectionTargeting.Manual(target),
        visitedIds = emptyList(),
        exploreMode = false,
        nearTrailEndM = nearTrailEndM,
    )

    private fun sample(
        lat: Double,
        lon: Double,
    ) = LocationSample(lat = lat, lon = lon, heading = null, speed = null, timestamp = 0L)

    // ── (a) Bug-A regression: a fresh, point-less trail is followable ──────────────────────────

    @Test
    fun atTrailEnd_offersFollow_evenWhenSelectedHasNoPoints() =
        runTest(UnconfinedTestDispatcher()) {
            val end = trailEnd(1, isStart = true, lat = 0.0, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = 5.0)
            // The unreliable flag that previously gated Follow — must be irrelevant now.
            recordingState.value = TrailRecordingState.Selected(trailId = 1, hasPoints = false)
            location.value = sample(0.0, 0.0)
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.AtTrailEnd, "expected AtTrailEnd, got $mode")
            assertTrue(TrailEndAction.Follow in mode.actions, "expected Follow action, got ${mode.actions}")
        }

    @Test
    fun loopTrail_manuallySelectedEnd_offersFollow() =
        runTest(UnconfinedTestDispatcher()) {
            // Loop: start and end share coordinates. The user manually targets the *end* (isStart=false)
            // and stands on it. Follow must appear for that end too (it follows the trail in reverse).
            val end = trailEnd(1, isStart = false, lat = 0.0, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = 4.0)
            location.value = sample(0.0, 0.0)
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.AtTrailEnd, "expected AtTrailEnd, got $mode")
            assertEquals(end, mode.trailEnd)
            assertTrue(TrailEndAction.Follow in mode.actions, "expected Follow action, got ${mode.actions}")
        }

    // ── (b) No stale trail controls after follow→stop ─────────────────────────────────────────

    @Test
    fun afterFollowStop_selectedIsIgnored_showsCurrentTarget() =
        runTest(UnconfinedTestDispatcher()) {
            val home = CollectionPoint.Standalone(waypoint(99, 10.0, 20.0))
            explorerState.value = active(home, nearTrailEndM = null)
            // stop() leaves Selected(hasPoints=true) lingering — it must NOT resurrect trail controls.
            recordingState.value = TrailRecordingState.Selected(trailId = 1, hasPoints = true)
            val r = settledResolver()

            assertEquals(NavMode.CollectionTarget(home), r.navMode.value)
        }

    // ── (c) Precedence: active session beats explorer target ──────────────────────────────────

    @Test
    fun following_winsOverTrailEndTarget() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = active(trailEnd(1, isStart = true, lat = 0.0, lon = 0.0), nearTrailEndM = 5.0)
            recordingState.value = TrailRecordingState.Following(trailId = 1)
            location.value = sample(0.0, 0.0)
            val r = settledResolver()

            assertEquals(NavMode.FollowingTrail(1), r.navMode.value)
        }

    @Test
    fun recording_winsOverTrailEndTarget() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = active(trailEnd(1, isStart = true, lat = 0.0, lon = 0.0), nearTrailEndM = 5.0)
            recordingState.value = TrailRecordingState.Recording(trailId = 1, pointCount = 7)
            val r = settledResolver()

            assertEquals(NavMode.RecordingTrail(1, 7), r.navMode.value)
        }

    // ── (d) AtTrailEnd geometry: Follow vs Extend ─────────────────────────────────────────────

    @Test
    fun trailEnd_inFollowRange_beyondExtend_offersFollowOnly() =
        runTest(UnconfinedTestDispatcher()) {
            // ~111 m north of the end → within explorer's follow gate (nearTrailEndM set) but well
            // beyond the ~15 m extend floor.
            val end = trailEnd(1, isStart = true, lat = 0.001, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = 8.0)
            location.value = sample(0.0, 0.0)
            accuracyM.value = 5.0
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.AtTrailEnd, "expected AtTrailEnd, got $mode")
            assertEquals(setOf(TrailEndAction.Follow), mode.actions)
        }

    @Test
    fun trailEnd_withinExtendFloor_offersExtend() =
        runTest(UnconfinedTestDispatcher()) {
            val end = trailEnd(1, isStart = true, lat = 0.0, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = 3.0)
            location.value = sample(0.0, 0.0) // ~0 m away → inside 15 m floor
            accuracyM.value = 2.0
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.AtTrailEnd, "expected AtTrailEnd, got $mode")
            assertTrue(TrailEndAction.Extend in mode.actions, "expected Extend, got ${mode.actions}")
        }

    @Test
    fun trailEnd_extendThreshold_scalesWithPoorAccuracy() =
        runTest(UnconfinedTestDispatcher()) {
            // ~33 m away: beyond the 15 m floor, but within 2×20 m = 40 m accuracy-scaled threshold.
            val sensor = haversineDistanceMeters(LatLng(0.0, 0.0), LatLng(0.0003, 0.0))
            assertTrue(sensor > NavModeResolver.EXTEND_FLOOR_M && sensor < 40.0, "fixture distance $sensor")
            val end = trailEnd(1, isStart = true, lat = 0.0003, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = null)
            location.value = sample(0.0, 0.0)
            accuracyM.value = 20.0
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.AtTrailEnd, "expected AtTrailEnd, got $mode")
            assertEquals(setOf(TrailEndAction.Extend), mode.actions)
        }

    @Test
    fun trailEnd_outOfBothRanges_isPlainCollectionTarget() =
        runTest(UnconfinedTestDispatcher()) {
            val end = trailEnd(1, isStart = true, lat = 0.01, lon = 0.0) // ~1.1 km away
            explorerState.value = active(end, nearTrailEndM = null)
            location.value = sample(0.0, 0.0)
            accuracyM.value = 5.0
            val r = settledResolver()

            assertEquals(NavMode.CollectionTarget(end), r.navMode.value)
        }

    // ── (e) Empty states ──────────────────────────────────────────────────────────────────────

    @Test
    fun idle_noCollection_isNoCollection() =
        runTest(UnconfinedTestDispatcher()) {
            val r = settledResolver()
            assertEquals(NavMode.NoCollection, r.navMode.value)
        }

    @Test
    fun idle_withCollection_isNoTarget() =
        runTest(UnconfinedTestDispatcher()) {
            selectedCollectionId.value = 42L
            val r = settledResolver()
            assertEquals(NavMode.NoTarget, r.navMode.value)
        }

    @Test
    fun active_withNullTarget_isNoTarget() =
        runTest(UnconfinedTestDispatcher()) {
            selectedCollectionId.value = 42L
            explorerState.value = active(target = null, nearTrailEndM = null)
            val r = settledResolver()
            assertEquals(NavMode.NoTarget, r.navMode.value)
        }

    @Test
    fun standaloneTarget_isCollectionTarget() =
        runTest(UnconfinedTestDispatcher()) {
            val wp = CollectionPoint.Standalone(waypoint(5, 1.0, 2.0))
            explorerState.value = active(wp, nearTrailEndM = null)
            val r = settledResolver()
            assertEquals(NavMode.CollectionTarget(wp), r.navMode.value)
        }
}
