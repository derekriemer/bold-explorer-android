package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationTargetResolverTest {
    private val trailFollowState = MutableStateFlow<TrailFollowerState>(TrailFollowerState.Idle)
    private val explorerState = MutableStateFlow<CollectionExplorerState>(CollectionExplorerState.Idle)
    private val location = MutableStateFlow<LocationSample?>(null)
    private val navHeadingDeg = MutableStateFlow<Double?>(null)

    private fun resolver(scope: CoroutineScope) = NavigationTargetResolver(scope, trailFollowState, explorerState, location, navHeadingDeg)

    /**
     * Build a resolver whose flows live on [backgroundScope] (so `runTest` cancels them), keep them
     * hot (they use `WhileSubscribed`, so they only compute while collected), let the scheduler
     * settle, then return it ready for `.value` reads.
     */
    private suspend fun TestScope.settledResolver(): NavigationTargetResolver {
        val r = resolver(backgroundScope)
        listOf(r.targetCoord, r.targetName, r.bearingDeg, r.distanceM, r.relativeDeg).forEach { f ->
            backgroundScope.launch { f.collect {} }
        }
        advanceUntilIdle()
        return r
    }

    private fun waypoint(
        id: Long,
        name: String,
        lat: Double,
        lon: Double,
    ) = Waypoint(id = id, name = name, lat = lat, lon = lon, elevM = null, description = null, createdAt = 0L)

    private fun explorerActive(target: CollectionPoint) =
        CollectionExplorerState.Active(
            points = listOf(target),
            target = target,
            visitedIds = emptyList(),
            autoAdvance = false,
            nearTrailEndM = null,
        )

    private fun sample(
        lat: Double,
        lon: Double,
    ) = LocationSample(lat = lat, lon = lon, heading = null, speed = null, timestamp = 0L)

    // ── Target precedence ─────────────────────────────────────────────────────────────

    @Test
    fun target_isNull_whenIdle() =
        runTest(UnconfinedTestDispatcher()) {
            val r = settledResolver()
            assertNull(r.targetCoord.value)
            assertNull(r.targetName.value)
        }

    @Test
    fun target_isExplorerTarget_whenNotFollowing() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = explorerActive(CollectionPoint.Standalone(waypoint(1, "Cabin", 10.0, 20.0)))
            val r = settledResolver()

            assertEquals(LatLng(10.0, 20.0), r.targetCoord.value)
            assertEquals("Cabin", r.targetName.value)
        }

    @Test
    fun target_prefersTrailWaypoint_overExplorerTarget() =
        runTest(UnconfinedTestDispatcher()) {
            // Explorer points at the cabin…
            explorerState.value = explorerActive(CollectionPoint.Standalone(waypoint(1, "Cabin", 10.0, 20.0)))
            // …but an active trail follow wins.
            trailFollowState.value =
                TrailFollowerState.Active(
                    waypoints =
                        listOf(
                            TrailPoint(1, "Start", 0.0, 0.0),
                            TrailPoint(2, "Bend", 30.0, 40.0),
                        ),
                    currentIndex = 1,
                    thresholdM = 10.0,
                )
            val r = settledResolver()

            assertEquals(LatLng(30.0, 40.0), r.targetCoord.value)
            assertEquals("Bend", r.targetName.value)
        }

    @Test
    fun target_usesTrailEndDisplayName() =
        runTest(UnconfinedTestDispatcher()) {
            val trail = Trail(id = 7, name = "Ridge", description = null, createdAt = 0L)
            val end = CollectionPoint.TrailEnd(waypoint(9, "wp", 1.0, 2.0), trail, isStart = false)
            explorerState.value = explorerActive(end)
            val r = settledResolver()

            assertEquals("Ridge (end)", r.targetName.value)
        }

    // ── Geometry ───────────────────────────────────────────────────────────────────────

    @Test
    fun bearingAndDistance_nullWhenNoLocationOrTarget() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = explorerActive(CollectionPoint.Standalone(waypoint(1, "A", 0.0, 0.0)))
            val r = settledResolver()
            // No location yet.
            assertNull(r.bearingDeg.value)
            assertNull(r.distanceM.value)
        }

    @Test
    fun bearing_pointsNorth_andDistanceIsPositive() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = explorerActive(CollectionPoint.Standalone(waypoint(1, "North", 0.001, 0.0)))
            location.value = sample(0.0, 0.0)
            val r = settledResolver()

            val bearing = r.bearingDeg.value
            assertNotNull(bearing)
            // Target is due north (same lon, higher lat) → ~0°.
            assertTrue(abs(bearing) < 1.0 || abs(bearing - 360.0) < 1.0, "expected ~0°, got $bearing")
            val dist = r.distanceM.value
            assertNotNull(dist)
            assertTrue(dist > 100.0 && dist < 120.0, "expected ~111 m, got $dist")
        }

    @Test
    fun relativeDeg_isCourseMinusBearing() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = explorerActive(CollectionPoint.Standalone(waypoint(1, "North", 0.001, 0.0)))
            location.value = sample(0.0, 0.0)
            // Travelling due east (course 90°) toward a target due north (bearing ~0°) → ~ -90°.
            navHeadingDeg.value = 90.0
            val r = settledResolver()

            val rel = r.relativeDeg.value
            assertNotNull(rel)
            assertTrue(abs(rel - (-90.0)) < 1.0, "expected ~ -90°, got $rel")
        }

    @Test
    fun relativeDeg_nullWhenNoCourse() =
        runTest(UnconfinedTestDispatcher()) {
            explorerState.value = explorerActive(CollectionPoint.Standalone(waypoint(1, "North", 0.001, 0.0)))
            location.value = sample(0.0, 0.0)
            val r = settledResolver()
            // navHeadingDeg stays null (stationary / no fix).
            assertNull(r.relativeDeg.value)
        }
}
