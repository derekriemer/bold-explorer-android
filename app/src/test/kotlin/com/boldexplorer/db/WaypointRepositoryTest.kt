package com.boldexplorer.db

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaypointRepositoryTest {
    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Test fun `create and getById round-trips`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val id = r.create(cid, "Summit", 47.5, -122.3, 1200.0, "Nice view")
            val wp = r.getById(id)
            assertNotNull(wp)
            assertEquals("Summit", wp.name)
            assertEquals(47.5, wp.lat)
            assertEquals(-122.3, wp.lon)
            assertEquals(1200.0, wp.elevM)
            assertEquals("Nice view", wp.description)
        }

    @Test fun `create attaches waypoint to its collection`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cRepo = CollectionRepositoryImpl(db)
            val cid = cRepo.create("Trip", null)
            val id = r.create(cid, "Summit", 47.5, -122.3, null, null)
            assertEquals(listOf(id), cRepo.waypointsForCollection(cid).map { it.id })
        }

    @Test fun `getAll returns all inserted waypoints`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            r.create(cid, "A", 1.0, 2.0, null, null)
            r.create(cid, "B", 3.0, 4.0, null, null)
            assertEquals(2, r.getAll().size)
        }

    @Test fun `update partial fields`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val id = r.create(cid, "Old", 10.0, 20.0, null, null)
            r.update(id, name = "New")
            val wp = r.getById(id)!!
            assertEquals("New", wp.name)
            assertEquals(10.0, wp.lat) // unchanged
        }

    @Test fun `remove deletes waypoint`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val id = r.create(cid, "Gone", 0.0, 0.0, null, null)
            r.remove(id)
            assertNull(r.getById(id))
        }

    // ── Track points ────────────────────────────────────────────────────────

    @Test fun `createTrackPoint links to trail without a collection`() =
        runTest {
            val db = createTestDatabase()
            val wRepo = WaypointRepositoryImpl(db)
            val tRepo = TrailRepositoryImpl(db)
            val cRepo = CollectionRepositoryImpl(db)
            val cid = cRepo.create("C", null)
            val trailId = tRepo.create(cid, "Loop", null)

            val tpId = wRepo.createTrackPoint(trailId, "Track 1", 1.0, 1.0, null)

            // Track point is on the trail…
            assertEquals(listOf("Track 1"), wRepo.forTrail(trailId).map { it.name })
            // …but never a collection member.
            assertTrue(cRepo.collectionsForWaypoint(tpId).isEmpty())
        }

    // ── Trail attachment ──────────────────────────────────────────────────────

    @Test fun `attach appends to trail`() =
        runTest {
            val db = createTestDatabase()
            val wRepo = WaypointRepositoryImpl(db)
            val tRepo = TrailRepositoryImpl(db)
            val cid = db.defaultCollection()

            val trailId = tRepo.create(cid, "Loop", null)
            val w1 = wRepo.create(cid, "A", 1.0, 1.0, null, null)
            val w2 = wRepo.create(cid, "B", 2.0, 2.0, null, null)
            wRepo.attach(trailId, w1)
            wRepo.attach(trailId, w2)

            val waypoints = wRepo.forTrail(trailId)
            assertEquals(listOf("A", "B"), waypoints.map { it.name })
        }

    @Test fun `attach at specific position inserts and shifts`() =
        runTest {
            val db = createTestDatabase()
            val wRepo = WaypointRepositoryImpl(db)
            val tRepo = TrailRepositoryImpl(db)
            val cid = db.defaultCollection()

            val trailId = tRepo.create(cid, "Loop", null)
            val w1 = wRepo.create(cid, "A", 1.0, 1.0, null, null)
            val w2 = wRepo.create(cid, "B", 2.0, 2.0, null, null)
            val w3 = wRepo.create(cid, "C", 3.0, 3.0, null, null)
            wRepo.attach(trailId, w1)
            wRepo.attach(trailId, w2)
            wRepo.attach(trailId, w3, position = 1) // insert C at position 1, shifts A and B

            val waypoints = wRepo.forTrail(trailId)
            assertEquals(listOf("C", "A", "B"), waypoints.map { it.name })
        }

    @Test fun `detach removes and collapses positions`() =
        runTest {
            val db = createTestDatabase()
            val wRepo = WaypointRepositoryImpl(db)
            val tRepo = TrailRepositoryImpl(db)
            val cid = db.defaultCollection()

            val trailId = tRepo.create(cid, "Loop", null)
            val w1 = wRepo.create(cid, "A", 1.0, 1.0, null, null)
            val w2 = wRepo.create(cid, "B", 2.0, 2.0, null, null)
            val w3 = wRepo.create(cid, "C", 3.0, 3.0, null, null)
            wRepo.attach(trailId, w1)
            wRepo.attach(trailId, w2)
            wRepo.attach(trailId, w3)

            wRepo.detach(trailId, w2)
            val waypoints = wRepo.forTrail(trailId)
            assertEquals(listOf("A", "C"), waypoints.map { it.name })
        }

    // ── setPosition / reorder invariant ──────────────────────────────────────

    @Test fun `setPosition move forward (a less than b) keeps gapless positions`() =
        runTest {
            val db = createTestDatabase()
            val wRepo = WaypointRepositoryImpl(db)
            val tRepo = TrailRepositoryImpl(db)
            val cid = db.defaultCollection()

            val trailId = tRepo.create(cid, "T", null)
            val ids = listOf("A", "B", "C", "D").map { wRepo.create(cid, it, 0.0, 0.0, null, null) }
            ids.forEach { wRepo.attach(trailId, it) }

            // Move A (pos 1) to pos 3 → expected order B C A D
            wRepo.setPosition(trailId, ids[0], 3)

            val result = wRepo.forTrail(trailId).map { it.name }
            assertEquals(listOf("B", "C", "A", "D"), result)
            assertGaplessPositions(db, trailId)
        }

    @Test fun `setPosition move backward (a greater than b) keeps gapless positions`() =
        runTest {
            val db = createTestDatabase()
            val wRepo = WaypointRepositoryImpl(db)
            val tRepo = TrailRepositoryImpl(db)
            val cid = db.defaultCollection()

            val trailId = tRepo.create(cid, "T", null)
            val ids = listOf("A", "B", "C", "D").map { wRepo.create(cid, it, 0.0, 0.0, null, null) }
            ids.forEach { wRepo.attach(trailId, it) }

            // Move D (pos 4) to pos 2 → expected order A D B C
            wRepo.setPosition(trailId, ids[3], 2)

            val result = wRepo.forTrail(trailId).map { it.name }
            assertEquals(listOf("A", "D", "B", "C"), result)
            assertGaplessPositions(db, trailId)
        }

    @Test fun `setPosition no-op (same position) is safe`() =
        runTest {
            val db = createTestDatabase()
            val wRepo = WaypointRepositoryImpl(db)
            val tRepo = TrailRepositoryImpl(db)
            val cid = db.defaultCollection()

            val trailId = tRepo.create(cid, "T", null)
            val ids = listOf("A", "B", "C").map { wRepo.create(cid, it, 0.0, 0.0, null, null) }
            ids.forEach { wRepo.attach(trailId, it) }

            wRepo.setPosition(trailId, ids[1], 2) // B stays at 2
            assertEquals(listOf("A", "B", "C"), wRepo.forTrail(trailId).map { it.name })
            assertGaplessPositions(db, trailId)
        }

    // ── withDistanceFrom ──────────────────────────────────────────────────────

    @Test fun `withDistanceFrom returns nearest first`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            // All three must be within the 50km default bbox radius.
            // 47.9 lat is ~44km from 47.5 — within the bbox; 48.0 would be ~55km (outside).
            r.create(cid, "Far", 47.9, -122.0, null, null)
            r.create(cid, "Close", 47.5001, -122.3001, null, null)
            r.create(cid, "Medium", 47.6, -122.2, null, null)

            val results = r.withDistanceFrom(47.5, -122.3)
            assertEquals(3, results.size)
            assertEquals("Close", results[0].waypoint.name)
            assertTrue(results[0].distanceM < results[1].distanceM)
            assertTrue(results[1].distanceM < results[2].distanceM)
        }

    @Test fun `withDistanceFrom limit is respected`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            repeat(10) { i -> r.create(cid, "W$i", 47.5 + i * 0.01, -122.3, null, null) }
            val results = r.withDistanceFrom(47.5, -122.3, limit = 3)
            assertEquals(3, results.size)
        }

    @Test fun `withDistanceFrom trailId filter`() =
        runTest {
            val db = createTestDatabase()
            val wRepo = WaypointRepositoryImpl(db)
            val tRepo = TrailRepositoryImpl(db)
            val cid = db.defaultCollection()

            val trailId = tRepo.create(cid, "T", null)
            val inTrail = wRepo.create(cid, "InTrail", 47.5001, -122.3001, null, null)
            wRepo.create(cid, "NotInTrail", 47.5002, -122.3002, null, null)
            wRepo.attach(trailId, inTrail)

            val results = wRepo.withDistanceFrom(47.5, -122.3, trailId = trailId)
            assertEquals(1, results.size)
            assertEquals("InTrail", results.first().waypoint.name)
        }

    @Test fun `withDistanceFrom handles anti-meridian crossing`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            r.create(cid, "East", 0.0, 179.95, null, null)
            r.create(cid, "West", 0.0, -179.95, null, null)
            r.create(cid, "Far", 0.0, 170.0, null, null)

            val results = r.withDistanceFrom(0.0, 179.99).map { it.waypoint.name }

            assertEquals(listOf("East", "West"), results)
        }

    @Test fun `withDistanceFrom near pole skips longitude filter and sorts by distance`() =
        runTest {
            val db = createTestDatabase()
            val r = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            r.create(cid, "Near", 89.99, 90.0, null, null)
            r.create(cid, "AlsoNear", 89.99, -90.0, null, null)
            r.create(cid, "Farther", 89.5, 0.0, null, null)

            val results = r.withDistanceFrom(89.99, 0.0).map { it.waypoint.name }

            assertTrue(results.take(2).containsAll(listOf("Near", "AlsoNear")))
        }
}

private fun assertGaplessPositions(
    db: BoldExplorerDatabase,
    trailId: Long,
) {
    val positions =
        db.trailWaypointQueries
            .getByTrail(trailId)
            .executeAsList()
            .map { it.position.toInt() }
            .sorted()
    val expected = (1..positions.size).toList()
    assertEquals(expected, positions, "Positions must be gapless starting at 1")
}
