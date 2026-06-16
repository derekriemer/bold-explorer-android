package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.TrailEndRow
import com.boldexplorer.shared.model.Waypoint
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
    private val trailEnds = MutableStateFlow<List<TrailEndRow>>(emptyList())

    /** Collect the composition into a mutable list of latest emissions on [TestScope.backgroundScope]. */
    private fun TestScope.collectLatest(): () -> List<CollectionPoint> {
        val emissions = mutableListOf<List<CollectionPoint>>()
        backgroundScope.launch {
            collectionNavPoints(standalones, trailEnds).collect { emissions.add(it) }
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

    private fun end(
        wpId: Long,
        trailId: Long,
        trailName: String,
        isStart: Boolean,
    ) = TrailEndRow(waypoint = waypoint(wpId, wpId.toDouble(), wpId.toDouble()), trail = trail(trailId, trailName), isStart = isStart)

    /**
     * The "recorded trail invisible until restart" regression now lives at the SQL layer (the
     * reactive trailEndsForCollection query, covered by NavPointsRepositoryTest). Here we verify the
     * fold reflects whatever ends the query reports: an empty trail contributes none; a growing trail
     * surfaces start, then start+end, through the same subscription.
     */
    @Test
    fun trailEnds_passThrough_andUpdateLive() =
        runTest(UnconfinedTestDispatcher()) {
            val latest = collectLatest()

            // No ends → no TrailEnd points.
            assertTrue(latest().none { it is CollectionPoint.TrailEnd }, "expected no ends initially")

            // Query reports a start only (single-point trail).
            trailEnds.value = listOf(end(wpId = 10, trailId = 1, trailName = "Loop", isStart = true))
            advanceUntilIdle()
            val afterFirst = latest().filterIsInstance<CollectionPoint.TrailEnd>()
            assertEquals(1, afterFirst.size)
            assertTrue(afterFirst.single().isStart)

            // Query now reports start + end.
            trailEnds.value =
                listOf(
                    end(wpId = 10, trailId = 1, trailName = "Loop", isStart = true),
                    end(wpId = 11, trailId = 1, trailName = "Loop", isStart = false),
                )
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
    fun emptyEnds_emitsOnlyStandalones_withoutHanging() =
        runTest(UnconfinedTestDispatcher()) {
            standalones.value = listOf(waypoint(1, 5.0, 6.0))
            val latest = collectLatest()

            assertEquals(1, latest().size)
            assertTrue(latest().single() is CollectionPoint.Standalone)
        }

    @Test
    fun removingTrailEnds_dropsThem() =
        runTest(UnconfinedTestDispatcher()) {
            trailEnds.value =
                listOf(
                    end(wpId = 10, trailId = 1, trailName = "Loop", isStart = true),
                    end(wpId = 11, trailId = 1, trailName = "Loop", isStart = false),
                )
            val latest = collectLatest()
            assertEquals(2, latest().filterIsInstance<CollectionPoint.TrailEnd>().size)

            trailEnds.value = emptyList()
            advanceUntilIdle()
            assertTrue(latest().none { it is CollectionPoint.TrailEnd })
        }

    @Test
    fun standalonesAndEnds_combine() =
        runTest(UnconfinedTestDispatcher()) {
            standalones.value = listOf(waypoint(1, 5.0, 6.0))
            trailEnds.value = listOf(end(wpId = 10, trailId = 1, trailName = "Loop", isStart = true))
            val latest = collectLatest()

            assertEquals(1, latest().filterIsInstance<CollectionPoint.Standalone>().size)
            assertEquals(1, latest().filterIsInstance<CollectionPoint.TrailEnd>().size)
        }
}
