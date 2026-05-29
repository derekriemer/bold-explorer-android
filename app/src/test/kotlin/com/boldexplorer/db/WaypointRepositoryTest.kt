package com.boldexplorer.db

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaypointRepositoryTest {

    private fun repo() = WaypointRepositoryImpl(createTestDatabase())

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Test fun `create and getById round-trips`() = runTest {
        val r = repo()
        val id = r.create("Summit", 47.5, -122.3, 1200.0, "Nice view")
        val wp = r.getById(id)
        assertNotNull(wp)
        assertEquals("Summit", wp.name)
        assertEquals(47.5, wp.lat)
        assertEquals(-122.3, wp.lon)
        assertEquals(1200.0, wp.elevM)
        assertEquals("Nice view", wp.description)
    }

    @Test fun `getAll returns all inserted waypoints`() = runTest {
        val r = repo()
        r.create("A", 1.0, 2.0, null, null)
        r.create("B", 3.0, 4.0, null, null)
        assertEquals(2, r.getAll().size)
    }

    @Test fun `update partial fields`() = runTest {
        val r = repo()
        val id = r.create("Old", 10.0, 20.0, null, null)
        r.update(id, name = "New")
        val wp = r.getById(id)!!
        assertEquals("New", wp.name)
        assertEquals(10.0, wp.lat) // unchanged
    }

    @Test fun `remove deletes waypoint`() = runTest {
        val r = repo()
        val id = r.create("Gone", 0.0, 0.0, null, null)
        r.remove(id)
        assertNull(r.getById(id))
    }

    // ── Trail attachment ──────────────────────────────────────────────────────

    @Test fun `attach appends to trail`() = runTest {
        val db = createTestDatabase()
        val wRepo = WaypointRepositoryImpl(db)
        val tRepo = TrailRepositoryImpl(db)

        val trailId = tRepo.create("Loop", null)
        val w1 = wRepo.create("A", 1.0, 1.0, null, null)
        val w2 = wRepo.create("B", 2.0, 2.0, null, null)
        wRepo.attach(trailId, w1)
        wRepo.attach(trailId, w2)

        val waypoints = wRepo.forTrail(trailId)
        assertEquals(listOf("A", "B"), waypoints.map { it.name })
    }

    @Test fun `attach at specific position inserts and shifts`() = runTest {
        val db = createTestDatabase()
        val wRepo = WaypointRepositoryImpl(db)
        val tRepo = TrailRepositoryImpl(db)

        val trailId = tRepo.create("Loop", null)
        val w1 = wRepo.create("A", 1.0, 1.0, null, null)
        val w2 = wRepo.create("B", 2.0, 2.0, null, null)
        val w3 = wRepo.create("C", 3.0, 3.0, null, null)
        wRepo.attach(trailId, w1)
        wRepo.attach(trailId, w2)
        wRepo.attach(trailId, w3, position = 1) // insert C at position 1, shifts A and B

        val waypoints = wRepo.forTrail(trailId)
        assertEquals(listOf("C", "A", "B"), waypoints.map { it.name })
    }

    @Test fun `detach removes and collapses positions`() = runTest {
        val db = createTestDatabase()
        val wRepo = WaypointRepositoryImpl(db)
        val tRepo = TrailRepositoryImpl(db)

        val trailId = tRepo.create("Loop", null)
        val w1 = wRepo.create("A", 1.0, 1.0, null, null)
        val w2 = wRepo.create("B", 2.0, 2.0, null, null)
        val w3 = wRepo.create("C", 3.0, 3.0, null, null)
        wRepo.attach(trailId, w1)
        wRepo.attach(trailId, w2)
        wRepo.attach(trailId, w3)

        wRepo.detach(trailId, w2)
        val waypoints = wRepo.forTrail(trailId)
        assertEquals(listOf("A", "C"), waypoints.map { it.name })
    }

    // ── setPosition / reorder invariant ──────────────────────────────────────

    @Test fun `setPosition move forward (a less than b) keeps gapless positions`() = runTest {
        val db = createTestDatabase()
        val wRepo = WaypointRepositoryImpl(db)
        val tRepo = TrailRepositoryImpl(db)

        val trailId = tRepo.create("T", null)
        val ids = listOf("A", "B", "C", "D").map { wRepo.create(it, 0.0, 0.0, null, null) }
        ids.forEach { wRepo.attach(trailId, it) }

        // Move A (pos 1) to pos 3 → expected order B C A D
        wRepo.setPosition(trailId, ids[0], 3)

        val result = wRepo.forTrail(trailId).map { it.name }
        assertEquals(listOf("B", "C", "A", "D"), result)
        assertGaplessPositions(db, trailId)
    }

    @Test fun `setPosition move backward (a greater than b) keeps gapless positions`() = runTest {
        val db = createTestDatabase()
        val wRepo = WaypointRepositoryImpl(db)
        val tRepo = TrailRepositoryImpl(db)

        val trailId = tRepo.create("T", null)
        val ids = listOf("A", "B", "C", "D").map { wRepo.create(it, 0.0, 0.0, null, null) }
        ids.forEach { wRepo.attach(trailId, it) }

        // Move D (pos 4) to pos 2 → expected order A D B C
        wRepo.setPosition(trailId, ids[3], 2)

        val result = wRepo.forTrail(trailId).map { it.name }
        assertEquals(listOf("A", "D", "B", "C"), result)
        assertGaplessPositions(db, trailId)
    }

    @Test fun `setPosition no-op (same position) is safe`() = runTest {
        val db = createTestDatabase()
        val wRepo = WaypointRepositoryImpl(db)
        val tRepo = TrailRepositoryImpl(db)

        val trailId = tRepo.create("T", null)
        val ids = listOf("A", "B", "C").map { wRepo.create(it, 0.0, 0.0, null, null) }
        ids.forEach { wRepo.attach(trailId, it) }

        wRepo.setPosition(trailId, ids[1], 2) // B stays at 2
        assertEquals(listOf("A", "B", "C"), wRepo.forTrail(trailId).map { it.name })
        assertGaplessPositions(db, trailId)
    }

    // ── withDistanceFrom ──────────────────────────────────────────────────────

    @Test fun `withDistanceFrom returns nearest first`() = runTest {
        val r = repo()
        // All three must be within the 50km default bbox radius.
        // 47.9 lat is ~44km from 47.5 — within the bbox; 48.0 would be ~55km (outside).
        r.create("Far", 47.9, -122.0, null, null)
        r.create("Close", 47.5001, -122.3001, null, null)
        r.create("Medium", 47.6, -122.2, null, null)

        val results = r.withDistanceFrom(47.5, -122.3)
        assertEquals(3, results.size)
        assertEquals("Close", results[0].waypoint.name)
        assertTrue(results[0].distanceM < results[1].distanceM)
        assertTrue(results[1].distanceM < results[2].distanceM)
    }

    @Test fun `withDistanceFrom limit is respected`() = runTest {
        val r = repo()
        repeat(10) { i -> r.create("W$i", 47.5 + i * 0.01, -122.3, null, null) }
        val results = r.withDistanceFrom(47.5, -122.3, limit = 3)
        assertEquals(3, results.size)
    }

    @Test fun `withDistanceFrom trailId filter`() = runTest {
        val db = createTestDatabase()
        val wRepo = WaypointRepositoryImpl(db)
        val tRepo = TrailRepositoryImpl(db)

        val trailId = tRepo.create("T", null)
        val inTrail = wRepo.create("InTrail", 47.5001, -122.3001, null, null)
        wRepo.create("NotInTrail", 47.5002, -122.3002, null, null)
        wRepo.attach(trailId, inTrail)

        val results = wRepo.withDistanceFrom(47.5, -122.3, trailId = trailId)
        assertEquals(1, results.size)
        assertEquals("InTrail", results.first().waypoint.name)
    }

    @Test fun `withDistanceFrom handles anti-meridian crossing`() = runTest {
        val r = repo()
        r.create("East", 0.0, 179.95, null, null)
        r.create("West", 0.0, -179.95, null, null)
        r.create("Far", 0.0, 170.0, null, null)

        val results = r.withDistanceFrom(0.0, 179.99).map { it.waypoint.name }

        assertEquals(listOf("East", "West"), results)
    }

    @Test fun `withDistanceFrom near pole skips longitude filter and sorts by distance`() = runTest {
        val r = repo()
        r.create("Near", 89.99, 90.0, null, null)
        r.create("AlsoNear", 89.99, -90.0, null, null)
        r.create("Farther", 89.5, 0.0, null, null)

        val results = r.withDistanceFrom(89.99, 0.0).map { it.waypoint.name }

        assertTrue(results.take(2).containsAll(listOf("Near", "AlsoNear")))
    }
}

private fun assertGaplessPositions(db: BoldExplorerDatabase, trailId: Long) {
    val positions = db.trailWaypointQueries.getByTrail(trailId).executeAsList()
        .map { it.position.toInt() }.sorted()
    val expected = (1..positions.size).toList()
    assertEquals(expected, positions, "Positions must be gapless starting at 1")
}
