package com.boldexplorer.db

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.TrailEndRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavPointsRepositoryTest {
    // --- trailEndsForCollection -------------------------------------------------------------

    @Test
    fun trailEndsForCollection_emptyTrail_noRows() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val navPoints = NavPointsRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = trails.create(cid, "Empty", null)

            val ends = navPoints.observeTrailEndsForCollection(cid).first()
            assertEquals(emptyList(), ends)
        }

    @Test
    fun trailEndsForCollection_singlePoint_startOnly() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val navPoints = NavPointsRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = trails.create(cid, "Solo", null)
            waypoints.createTrackPoint(trailId, "tp1", 1.0, 2.0, null, null)

            val ends = navPoints.observeTrailEndsForCollection(cid).first()
            assertEquals(1, ends.size)
            assertTrue(ends.single().isStart)
            assertEquals(1.0, ends.single().waypoint.lat)
        }

    @Test
    fun trailEndsForCollection_multiPoint_startAndEnd_withCorrectIsStart() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val navPoints = NavPointsRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = trails.create(cid, "Path", null)
            waypoints.createTrackPoint(trailId, "first", 1.0, 1.0, null, null)
            waypoints.createTrackPoint(trailId, "mid", 2.0, 2.0, null, null)
            waypoints.createTrackPoint(trailId, "last", 3.0, 3.0, null, null)

            val ends = navPoints.observeTrailEndsForCollection(cid).first()
            assertEquals(2, ends.size)
            val start = ends.first { it.isStart }
            val end = ends.first { !it.isStart }
            assertEquals(1.0, start.waypoint.lat) // min position
            assertEquals(3.0, end.waypoint.lat) // max position
            assertEquals("Path", start.trail.name)
        }

    @Test
    fun trailEndsForCollection_scopedToCollection() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val navPoints = NavPointsRepositoryImpl(db)
            val cidA = db.defaultCollection("A")
            val cidB = db.defaultCollection("B")
            val trailA = trails.create(cidA, "InA", null)
            val trailB = trails.create(cidB, "InB", null)
            waypoints.createTrackPoint(trailA, "a1", 1.0, 1.0, null, null)
            waypoints.createTrackPoint(trailA, "a2", 2.0, 2.0, null, null)
            waypoints.createTrackPoint(trailB, "b1", 9.0, 9.0, null, null)

            val endsA = navPoints.observeTrailEndsForCollection(cidA).first()
            assertEquals(setOf("InA"), endsA.map { it.trail.name }.toSet())
        }

    /**
     * The regression that the SQL-pushdown rewrite must preserve: adding a track point shifts the
     * trail's MAX position, so the observed ends flow must re-emit a fresh end. Uses runBlocking
     * (real Dispatchers.IO in mapToList) like TrailRepositoryTest.
     */
    @Test
    fun trailEndsForCollection_reEmits_whenTrackPointAdded() =
        runBlocking {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val navPoints = NavPointsRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = trails.create(cid, "Growing", null)
            waypoints.createTrackPoint(trailId, "p1", 1.0, 1.0, null, null)
            waypoints.createTrackPoint(trailId, "p2", 2.0, 2.0, null, null)

            val emissions = Channel<List<TrailEndRow>>(Channel.UNLIMITED)
            val collector =
                launch(Dispatchers.IO) {
                    navPoints.observeTrailEndsForCollection(cid).collect { emissions.send(it) }
                }

            val initial = withTimeout(5_000) { emissions.receive() }
            assertEquals(2.0, initial.first { !it.isStart }.waypoint.lat)

            // Append a further track point — MAX position moves to it; end must update.
            waypoints.createTrackPoint(trailId, "p3", 5.0, 5.0, null, null)
            // Drain until the end reflects the new last point (an earlier stale emission may arrive).
            var latestEnd = withTimeout(5_000) { emissions.receive() }.first { !it.isStart }
            while (latestEnd.waypoint.lat != 5.0) {
                latestEnd = withTimeout(5_000) { emissions.receive() }.first { !it.isStart }
            }
            assertEquals(5.0, latestEnd.waypoint.lat)

            collector.cancel()
        }

    // --- trailPointsInBbox ------------------------------------------------------------------

    @Test
    fun trailPointsInBbox_returnsOnlyInBoxPoints() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val navPoints = NavPointsRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = trails.create(cid, "Line", null)
            // Two points near origin, one far away.
            waypoints.createTrackPoint(trailId, "near1", 0.0000, 0.0000, null, null)
            waypoints.createTrackPoint(trailId, "near2", 0.0003, 0.0003, null, null)
            waypoints.createTrackPoint(trailId, "far", 1.0, 1.0, null, null)

            val near = navPoints.trailPointsInBbox(cid, LatLng(0.0, 0.0), radiusM = 100.0)
            assertEquals(2, near.size)
            assertTrue(near.all { it.lat < 0.001 })
            assertEquals(setOf(trailId), near.map { it.trailId }.toSet())
        }

    @Test
    fun trailPointsInBbox_scopedToCollection() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val navPoints = NavPointsRepositoryImpl(db)
            val cidA = db.defaultCollection("A")
            val cidB = db.defaultCollection("B")
            val trailA = trails.create(cidA, "InA", null)
            val trailB = trails.create(cidB, "InB", null)
            // Both trails have a point at the same spot; only collection A's should return.
            waypoints.createTrackPoint(trailA, "a", 0.0, 0.0, null, null)
            waypoints.createTrackPoint(trailB, "b", 0.0, 0.0, null, null)

            val near = navPoints.trailPointsInBbox(cidA, LatLng(0.0, 0.0), radiusM = 100.0)
            assertEquals(setOf(trailA), near.map { it.trailId }.toSet())
        }

    @Test
    fun trailPointsInBbox_denseTrail_returnsOnlyNearSlice() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val navPoints = NavPointsRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = trails.create(cid, "Dense", null)
            // A long line of points marching north; only the first few are within 100 m of origin.
            for (i in 0 until 200) {
                waypoints.createTrackPoint(trailId, "p$i", i * 0.0005, 0.0, null, null)
            }

            val near = navPoints.trailPointsInBbox(cid, LatLng(0.0, 0.0), radiusM = 100.0)
            assertTrue(near.isNotEmpty())
            assertTrue(near.size < 10, "expected a small near slice, got ${near.size}")
            assertTrue(near.all { it.lat < 0.001 })
        }
}
