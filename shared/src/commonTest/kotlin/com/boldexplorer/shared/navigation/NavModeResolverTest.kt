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
    private val nearbyTrail = MutableStateFlow<List<NearbyTrail>>(emptyList())

    private fun resolver(
        scope: CoroutineScope,
        describer: DirectionDescriber? = null,
    ) = NavModeResolver(
        scope,
        recordingState,
        explorerState,
        selectedCollectionId,
        location,
        accuracyM,
        nearbyTrail,
        describer,
    )

    /** Build a resolver on [backgroundScope], keep its WhileSubscribed flow hot, settle, return it. */
    private suspend fun TestScope.settledResolver(describer: DirectionDescriber? = null): NavModeResolver {
        val r = resolver(backgroundScope, describer)
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
        target = target,
        visitedIds = emptyList(),
        autoAdvance = false,
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
            assertTrue(sensor > NavigationPolicy.EXTEND_FLOOR_M && sensor < 40.0, "fixture distance $sensor")
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

    // ── (f) NearTrail: mid-trail follow ───────────────────────────────────────────────────────

    /** Active explorer whose points expose [trailId]'s ends, so the resolver can resolve its [Trail]. */
    private fun activeExposingTrail(
        trailId: Long,
        target: CollectionPoint?,
    ) = CollectionExplorerState.Active(
        points =
            listOfNotNull(target) +
                trailEnd(trailId, isStart = true, lat = 0.0, lon = 0.0) +
                trailEnd(trailId, isStart = false, lat = 0.001, lon = 0.0),
        target = target,
        visitedIds = emptyList(),
        autoAdvance = false,
        nearTrailEndM = null,
    )

    @Test
    fun nearTrail_emittedWhenSnapshotPresent_andNoEndTargeted() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = activeExposingTrail(trailId = 2, target = null)
            nearbyTrail.value = listOf(NearbyTrail(trailId = 2, distanceM = 4.0, nearestIndex = 1))
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.NearTrail, "expected NearTrail, got $mode")
            assertEquals(listOf(2L), mode.trails.map { it.id })
        }

    @Test
    fun nearTrail_alwaysOffersBothDirections() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = activeExposingTrail(trailId = 2, target = null)
            nearbyTrail.value = listOf(NearbyTrail(trailId = 2, distanceM = 4.0, nearestIndex = 1))
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.NearTrail, "expected NearTrail, got $mode")
            assertEquals(setOf(false, true), mode.options.map { it.reversed }.toSet())
            assertEquals(
                setOf(DirectionDescriptor.Forward, DirectionDescriptor.Backward),
                mode.options.map { it.descriptor }.toSet(),
            )
        }

    @Test
    fun nearTrail_winsOverPlainCollectionTarget() =
        runTest(UnconfinedTestDispatcher()) {
            val wp = CollectionPoint.Standalone(waypoint(5, 1.0, 2.0))
            explorerState.value = activeExposingTrail(trailId = 2, target = wp)
            nearbyTrail.value = listOf(NearbyTrail(trailId = 2, distanceM = 4.0, nearestIndex = 1))
            val r = settledResolver()

            assertTrue(r.navMode.value is NavMode.NearTrail, "NearTrail should beat a plain target")
        }

    @Test
    fun atTrailEnd_winsOverNearTrail() =
        runTest(UnconfinedTestDispatcher()) {
            val end = trailEnd(1, isStart = true, lat = 0.0, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = 5.0)
            location.value = sample(0.0, 0.0)
            nearbyTrail.value = listOf(NearbyTrail(trailId = 2, distanceM = 4.0, nearestIndex = 1))
            val r = settledResolver()

            assertTrue(r.navMode.value is NavMode.AtTrailEnd, "a targeted end keeps primacy over NearTrail")
        }

    @Test
    fun following_winsOverNearTrail() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = activeExposingTrail(trailId = 2, target = null)
            nearbyTrail.value = listOf(NearbyTrail(trailId = 2, distanceM = 4.0, nearestIndex = 1))
            recordingState.value = TrailRecordingState.Following(trailId = 9)
            val r = settledResolver()

            assertEquals(NavMode.FollowingTrail(9), r.navMode.value)
        }

    @Test
    fun nearTrail_multipleNearby_allSurfaced_nearestFirst() =
        runTest(UnconfinedTestDispatcher()) {
            // Two trails in the collection, both near the user.
            val end1a = trailEnd(1, isStart = true, lat = 0.0, lon = 0.0)
            val end1b = trailEnd(1, isStart = false, lat = 0.001, lon = 0.0)
            val end2a = trailEnd(2, isStart = true, lat = 0.0, lon = 0.001)
            val end2b = trailEnd(2, isStart = false, lat = 0.001, lon = 0.001)
            explorerState.value =
                CollectionExplorerState.Active(
                    points = listOf(end1a, end1b, end2a, end2b),
                    target = null,
                    visitedIds = emptyList(),
                    autoAdvance = false,
                    nearTrailEndM = null,
                )
            // Trail 2 is nearest (distanceM=3), trail 1 is further (distanceM=8).
            nearbyTrail.value =
                listOf(
                    NearbyTrail(trailId = 2, distanceM = 3.0, nearestIndex = 0),
                    NearbyTrail(trailId = 1, distanceM = 8.0, nearestIndex = 0),
                )
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.NearTrail, "expected NearTrail, got $mode")
            assertEquals(listOf(2L, 1L), mode.trails.map { it.id }, "nearest trail first")
        }

    // ── (g) Trail-end follow options: linear vs loop ──────────────────────────────────────────

    @Test
    fun linearTrailEnd_yieldsSingleFollowOption() =
        runTest(UnconfinedTestDispatcher()) {
            val end = trailEnd(1, isStart = true, lat = 0.0, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = 5.0)
            location.value = sample(0.0, 0.0)
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.AtTrailEnd, "expected AtTrailEnd, got $mode")
            assertEquals(1, mode.followOptions.size)
            assertEquals(false, mode.followOptions.single().reversed) // start → forward
        }

    @Test
    fun endTrailEnd_singleOption_followsReversed() =
        runTest(UnconfinedTestDispatcher()) {
            val end = trailEnd(1, isStart = false, lat = 0.0, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = 5.0)
            location.value = sample(0.0, 0.0)
            val r = settledResolver()

            val mode = r.navMode.value
            assertTrue(mode is NavMode.AtTrailEnd, "expected AtTrailEnd, got $mode")
            assertEquals(true, mode.followOptions.single().reversed) // end → reversed
        }

    @Test
    fun loopTrailEnd_viaDescriber_yieldsTwoOptions() =
        runTest(UnconfinedTestDispatcher()) {
            val end = trailEnd(1, isStart = true, lat = 0.0, lon = 0.0)
            explorerState.value = active(end, nearTrailEndM = 5.0)
            location.value = sample(0.0, 0.0)
            // A describer simulating loop detection: both directions off a coincident end.
            val describer =
                DirectionDescriber {
                    listOf(
                        FollowOption(reversed = false, descriptor = DirectionDescriptor.Forward),
                        FollowOption(reversed = true, descriptor = DirectionDescriptor.Backward),
                    )
                }
            val r = settledResolver(describer)

            val mode = r.navMode.value
            assertTrue(mode is NavMode.AtTrailEnd, "expected AtTrailEnd, got $mode")
            assertEquals(2, mode.followOptions.size)
        }
}
