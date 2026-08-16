package com.boldexplorer.db

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An annotation is a point attached to a trail that is not part of its geometry (ADR 0001, S5b).
 *
 * The defect this closes, observed rather than hypothesised: waypoint `dos` was attached to trail 12
 * 78 minutes after its last track point and appended at position 139, 1031 m from position 138. The
 * app then believed that trail was 2523 m long and ended 50 m from its own start. Nothing flagged
 * it, because `trail_waypoint.position` means both "vertex order" and "which checkpoint is this",
 * and a point attached after recording has no legitimate answer for the first.
 *
 * Stored here, the same attachment cannot move the trail's end by a millimetre.
 */
class TrailAnnotationRepositoryTest {
    /** ~111.19 m per 0.001° of latitude, so a metre count converts to a due-north offset. */
    private fun latFor(m: Double) = m / 111_194.9

    /** A 200 m due-north trail of track points, 20 m apart. */
    private suspend fun northTrail(
        db: BoldExplorerDatabase,
        collectionId: Long,
    ): Long {
        val trails = TrailRepositoryImpl(db)
        val waypoints = WaypointRepositoryImpl(db)
        val trailId = trails.create(collectionId, "North", null)
        var m = 0.0
        while (m <= 200.0) {
            waypoints.createTrackPoint(trailId, "tp$m", latFor(m), 0.0, null)
            m += 20.0
        }
        return trailId
    }

    @Test
    fun annotatingDoesNotTouchTheTrailsGeometry() =
        runTest {
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = northTrail(db, cid)
            val pointsBefore = waypoints.forTrail(trailId)

            // The `dos` case: a kilometre off the end of the trail.
            val strayId = waypoints.create(cid, "dos", latFor(1200.0), 0.0, null, null)
            val fix = annotations.annotate(trailId, strayId)

            assertNotNull(fix, "a trail with geometry can be annotated")
            assertEquals(
                pointsBefore.map { it.id },
                waypoints.forTrail(trailId).map { it.id },
                "annotating changed the trail's geometry",
            )
            assertEquals(listOf("dos"), annotations.forTrail(trailId).map { it.waypoint.name })
        }

    @Test
    fun aDistantAnnotationIsReportedNotRefused() =
        runTest {
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = northTrail(db, cid)

            val strayId = waypoints.create(cid, "dos", latFor(1200.0), 0.0, null, null)
            val fix = assertNotNull(annotations.annotate(trailId, strayId))

            // 1200 m north of the start on a trail that ends at 200 m: 1000 m past the end.
            assertTrue(abs(fix.crossTrackM - 1000.0) < 5.0, "reported ${fix.crossTrackM} m off the trail")
            assertEquals(1, annotations.forTrail(trailId).size, "the attachment still happened")
        }

    @Test
    fun theProjectionLandsWhereTheWaypointIs() =
        runTest {
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = northTrail(db, cid)

            // 10 m east of the trail, 90 m along it: halfway through the segment 80 m → 100 m.
            val benchId = waypoints.create(cid, "Bench", latFor(90.0), latFor(10.0), null, null)
            val fix = assertNotNull(annotations.annotate(trailId, benchId))

            assertEquals(4, fix.segmentIndex, "80 m in on a 20 m spacing is segment 4")
            assertTrue(abs(fix.offsetM - 10.0) < 1.0, "offset within the segment was ${fix.offsetM}")
            assertTrue(abs(fix.crossTrackM - 10.0) < 1.0, "cross-track was ${fix.crossTrackM}")
        }

    @Test
    fun aTrailWithoutGeometryCannotBeAnnotated() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()

            // A trail whose recording has only just begun: one point is a position, not a path.
            val trailId = trails.create(cid, "Fresh", null)
            waypoints.createTrackPoint(trailId, "tp0", 0.0, 0.0, null)
            val wpId = waypoints.create(cid, "Marked", latFor(5.0), 0.0, null, null)

            assertNull(annotations.annotate(trailId, wpId), "nothing to project onto yet")
            assertEquals(emptyList(), annotations.forTrail(trailId))
        }

    @Test
    fun reprojectFollowsTheWaypointWhenItMoves() =
        runTest {
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = northTrail(db, cid)

            val benchId = waypoints.create(cid, "Bench", latFor(30.0), 0.0, null, null)
            annotations.annotate(trailId, benchId)
            assertEquals(1, annotations.forTrail(trailId).first().segmentIndex)

            // The waypoint row is the truth, so correcting it moves the annotation with it.
            waypoints.update(benchId, lat = latFor(150.0), lon = 0.0)
            annotations.reproject(trailId)

            assertEquals(7, annotations.forTrail(trailId).first().segmentIndex, "150 m along, on a 20 m spacing")
            assertEquals("Bench", annotations.forTrail(trailId).first().waypoint.name)
        }

    @Test
    fun theWaypointRowStaysTheTruthForItsName() =
        runTest {
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = northTrail(db, cid)

            val id = waypoints.create(cid, "Gate", latFor(60.0), 0.0, null, null)
            annotations.annotate(trailId, id)
            waypoints.update(id, name = "North gate")

            // A copy would still say "Gate" here. This is why the annotation is a link.
            assertEquals("North gate", annotations.forTrail(trailId).single().waypoint.name)
        }
}
