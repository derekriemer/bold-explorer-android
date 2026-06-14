package com.boldexplorer.db

import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailRepositoryTest {
    /**
     * Pins the assumption the reactive-nav-points fix rests on: [TrailRepository.observeWaypointsForTrail]
     * must re-emit when a track point is added (createTrackPoint writes waypoint + trail_waypoint, and the
     * underlying `forTrail` query joins both). If this flow were backed by a stale snapshot path the
     * collectionNavPoints composition would just move the "invisible until restart" bug, not fix it.
     *
     * Uses runBlocking (not advanceUntilIdle): the flow maps on Dispatchers.IO, a real dispatcher, so
     * emissions cross threads and a virtual-time scheduler cannot observe them.
     */
    @Test
    fun observeWaypointsForTrail_reEmits_whenTrackPointAdded() =
        runBlocking {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val collectionId = db.defaultCollection()
            val trailId = trails.create(collectionId, "Loop", null)

            val emissions = Channel<List<Waypoint>>(Channel.UNLIMITED)
            val collector =
                launch(Dispatchers.IO) {
                    trails.observeWaypointsForTrail(trailId).collect { emissions.send(it) }
                }

            // First emission: the empty trail.
            assertEquals(emptyList(), withTimeout(5_000) { emissions.receive() })

            // Adding a track point must push a fresh emission through the same subscription.
            waypoints.createTrackPoint(trailId, "tp1", 1.0, 2.0, null, null)
            val afterFirst = withTimeout(5_000) { emissions.receive() }
            assertEquals(1, afterFirst.size)
            assertEquals(1.0, afterFirst.first().lat)

            // A second track point re-emits again — proves it is live, not a one-shot.
            waypoints.createTrackPoint(trailId, "tp2", 3.0, 4.0, null, null)
            val afterSecond = withTimeout(5_000) { emissions.receive() }
            assertEquals(2, afterSecond.size)

            collector.cancel()
        }

    @Test
    fun createUpdateAndRemove_roundTrips() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val cid = db.defaultCollection()

            val id = trails.create(cid, "Old", "desc")
            trails.update(id, name = "New")

            assertEquals("New", trails.getById(id)?.name)
            assertEquals("desc", trails.getById(id)?.description)

            trails.remove(id)
            assertNull(trails.getById(id))
        }

    @Test
    fun remove_cleansTrailWaypointAndCollectionReferences() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val collections = CollectionRepositoryImpl(db)

            val collectionId = collections.create("Favorites", null)
            val trailId = trails.create(collectionId, "Loop", null)
            val waypointId = waypoints.create(collectionId, "Point", 1.0, 2.0, null, null)
            waypoints.attach(trailId, waypointId)
            collections.attachTrail(collectionId, trailId)

            trails.remove(trailId)

            assertEquals(emptyList(), waypoints.forTrail(trailId))
            assertEquals(emptyList(), collections.trailsForCollection(collectionId))
        }
}
