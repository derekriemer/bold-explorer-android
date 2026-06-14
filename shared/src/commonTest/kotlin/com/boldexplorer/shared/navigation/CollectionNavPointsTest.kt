package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionNavPointsTest {
    private val standalones = MutableStateFlow<List<Waypoint>>(emptyList())
    private val trails = MutableStateFlow<List<Trail>>(emptyList())
    private val trailWaypointFlows = mutableMapOf<Long, MutableStateFlow<List<Waypoint>>>()

    private fun trailWaypoints(trailId: Long): Flow<List<Waypoint>> = trailWaypointFlows.getOrPut(trailId) { MutableStateFlow(emptyList()) }

    private fun pushTrailWaypoints(
        trailId: Long,
        wps: List<Waypoint>,
    ) {
        trailWaypointFlows.getOrPut(trailId) { MutableStateFlow(emptyList()) }.value = wps
    }

    /** Collect the composition into a mutable list of latest emissions on [TestScope.backgroundScope]. */
    private fun TestScope.collectLatest(): () -> List<CollectionPoint> {
        val emissions = mutableListOf<List<CollectionPoint>>()
        backgroundScope.launch {
            collectionNavPoints(standalones, trails, ::trailWaypoints).collect { emissions.add(it) }
        }
        advanceUntilIdle()
        return { emissions.last() }
    }

    private fun waypoint(
        id: Long,
        lat: Double,
        lon: Double,
    ) = Waypoint(id = id, name = "wp$id", lat = lat, lon = lon, elevM = null, description = null, createdAt = 0L)

    private fun trail(
        id: Long,
        name: String,
    ) = Trail(id = id, name = name, description = null, createdAt = 0L)

    /**
     * The headline regression for the "recorded trail invisible until restart" bug. A trail with zero
     * waypoints contributes no ends; pushing track points into that trail's waypoint flow must update
     * the ends live, through the same subscription — never requiring a re-subscribe or restart.
     */
    @Test
    fun trailEnds_updateLive_asTrackPointsArrive() =
        runTest(UnconfinedTestDispatcher()) {
            trails.value = listOf(trail(1, "Loop"))
            val latest = collectLatest()

            // Empty trail → no ends.
            assertTrue(latest().none { it is CollectionPoint.TrailEnd }, "expected no ends for empty trail")

            // One track point → a start only.
            pushTrailWaypoints(1, listOf(waypoint(10, 1.0, 1.0)))
            advanceUntilIdle()
            val afterFirst = latest().filterIsInstance<CollectionPoint.TrailEnd>()
            assertEquals(1, afterFirst.size)
            assertTrue(afterFirst.single().isStart)

            // Second track point → start + end.
            pushTrailWaypoints(1, listOf(waypoint(10, 1.0, 1.0), waypoint(11, 2.0, 2.0)))
            advanceUntilIdle()
            val afterSecond = latest().filterIsInstance<CollectionPoint.TrailEnd>()
            assertEquals(2, afterSecond.size)
            assertEquals(setOf(true, false), afterSecond.map { it.isStart }.toSet())
            assertEquals(10L, afterSecond.first { it.isStart }.waypoint.id)
            assertEquals(11L, afterSecond.first { !it.isStart }.waypoint.id)
        }

    @Test
    fun standalones_passThrough_asStandalonePoints() =
        runTest(UnconfinedTestDispatcher()) {
            standalones.value = listOf(waypoint(1, 5.0, 6.0), waypoint(2, 7.0, 8.0))
            val latest = collectLatest()

            val points = latest().filterIsInstance<CollectionPoint.Standalone>()
            assertEquals(listOf(1L, 2L), points.map { it.waypoint.id })
        }

    @Test
    fun emptyTrails_emitsOnlyStandalones_withoutHanging() =
        runTest(UnconfinedTestDispatcher()) {
            standalones.value = listOf(waypoint(1, 5.0, 6.0))
            val latest = collectLatest()

            assertEquals(1, latest().size)
            assertTrue(latest().single() is CollectionPoint.Standalone)
        }

    @Test
    fun removingTrail_dropsItsEnds() =
        runTest(UnconfinedTestDispatcher()) {
            trails.value = listOf(trail(1, "Loop"))
            pushTrailWaypoints(1, listOf(waypoint(10, 1.0, 1.0), waypoint(11, 2.0, 2.0)))
            val latest = collectLatest()
            assertEquals(2, latest().filterIsInstance<CollectionPoint.TrailEnd>().size)

            trails.value = emptyList()
            advanceUntilIdle()
            assertTrue(latest().none { it is CollectionPoint.TrailEnd })
        }

    @Test
    fun singleWaypointTrail_yieldsOnlyStart() =
        runTest(UnconfinedTestDispatcher()) {
            trails.value = listOf(trail(1, "Loop"))
            pushTrailWaypoints(1, listOf(waypoint(10, 1.0, 1.0)))
            val latest = collectLatest()

            val ends = latest().filterIsInstance<CollectionPoint.TrailEnd>()
            assertEquals(1, ends.size)
            assertTrue(ends.single().isStart)
        }
}
