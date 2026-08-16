package com.boldexplorer.db

import com.boldexplorer.shared.repository.TrailAttachment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which way an attachment goes, decided by the trail rather than by the caller (ADR 0001, S5b).
 *
 * > A trail's geometry is the points it was **built from**, in order. Anything attached to it
 * > **afterwards** is an annotation.
 *
 * A recorded trail is one with track points of its own, and its geometry is finished. A trail
 * without them is a hand-built route whose waypoints *are* its geometry. The rule is enforced in the
 * repository, not in the five view models that attach things, because a rule that five callers have
 * to remember is one that four of them will eventually forget.
 */
class TrailAttachmentRoutingTest {
    private fun latFor(m: Double) = m / 111_194.9

    private suspend fun recordedTrail(
        db: BoldExplorerDatabase,
        collectionId: Long,
        points: Int = 11,
    ): Long {
        val trailId = TrailRepositoryImpl(db).create(collectionId, "Recorded", null)
        val waypoints = WaypointRepositoryImpl(db)
        repeat(points) { i -> waypoints.createTrackPoint(trailId, "tp$i", latFor(i * 20.0), 0.0, null) }
        return trailId
    }

    @Test
    fun attachingToARecordedTrailAnnotatesIt() =
        runTest {
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid)
            val geometryBefore = waypoints.forTrail(trailId).map { it.id }

            // `dos`: created 78 minutes after the last track point, a kilometre off the end.
            val strayId = waypoints.create(cid, "dos", latFor(1200.0), 0.0, null, null)
            val outcome = waypoints.attach(trailId, strayId)

            assertIs<TrailAttachment.Annotation>(outcome)
            assertEquals(geometryBefore, waypoints.forTrail(trailId).map { it.id }, "the trail's geometry moved")
            assertEquals(listOf("dos"), annotations.forTrail(trailId).map { it.waypoint.name })
        }

    @Test
    fun theStrayPointNoLongerLengthensTheTrail() =
        runTest {
            // The number that mattered in the field: trail 12 read 2523 m when it is 1492 m, because
            // one appended point added a phantom kilometre-long segment.
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid)

            suspend fun lengthM(): Double =
                waypoints
                    .forTrail(trailId)
                    .zipWithNext { a, b ->
                        com.boldexplorer.shared.geo.haversineDistanceMeters(
                            com.boldexplorer.shared.geo.LatLng(a.lat, a.lon),
                            com.boldexplorer.shared.geo.LatLng(b.lat, b.lon),
                        )
                    }.sum()

            val before = lengthM()
            waypoints.attach(trailId, waypoints.create(cid, "dos", latFor(1200.0), 0.0, null, null))

            assertTrue(abs(lengthM() - before) < 0.001, "attaching changed the trail's length")
        }

    @Test
    fun attachingToAHandBuiltRouteExtendsItsGeometry() =
        runTest {
            // No track points: the waypoints *are* the route, so attaching one is a legitimate
            // vertex. Refusing here would break the only way to build a trail by hand.
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = TrailRepositoryImpl(db).create(cid, "Hand built", null)

            val a = waypoints.create(cid, "A", 0.0, 0.0, null, null)
            val b = waypoints.create(cid, "B", latFor(50.0), 0.0, null, null)
            assertIs<TrailAttachment.Vertex>(waypoints.attach(trailId, a))
            assertIs<TrailAttachment.Vertex>(waypoints.attach(trailId, b))

            assertEquals(listOf("A", "B"), waypoints.forTrail(trailId).map { it.name })
        }

    @Test
    fun anExplicitPositionIsAlwaysHonoured() =
        runTest {
            // Passing a position *is* the claim that the point belongs in the geometry there. It is
            // how a route is assembled, so it stays available — and stays the only way to say it.
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid)

            val id = waypoints.create(cid, "Deliberate", latFor(1200.0), 0.0, null, null)
            val outcome = waypoints.attach(trailId, id, position = 1)

            assertIs<TrailAttachment.Vertex>(outcome)
            assertEquals("Deliberate", waypoints.forTrail(trailId)[0].name)
        }

    @Test
    fun aRecordingThatHasBarelyBegunTakesAVertex() =
        runTest {
            // One point is a position, not a path: there is nothing to project onto yet, and a
            // vertex is the honest answer rather than a failure.
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid, points = 1)

            val id = waypoints.create(cid, "Marked", latFor(5.0), 0.0, null, null)

            assertIs<TrailAttachment.Vertex>(waypoints.attach(trailId, id))
        }

    @Test
    fun detachAndReattachDemotesAnExistingStrayPoint() =
        runTest {
            // How trail 12 gets fixed, and the reason no data migration is needed: the two gestures
            // the app already has do it. Detach removes the vertex and collapses the gap; attaching
            // again lands on the annotate path, because the trail has track points. Nothing is
            // moved, projected or deleted — `dos` keeps the coordinates it was recorded at, and
            // stops being geometry.
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid)

            suspend fun lengthM(): Double =
                waypoints
                    .forTrail(trailId)
                    .zipWithNext { a, b ->
                        com.boldexplorer.shared.geo.haversineDistanceMeters(
                            com.boldexplorer.shared.geo.LatLng(a.lat, a.lon),
                            com.boldexplorer.shared.geo.LatLng(b.lat, b.lon),
                        )
                    }.sum()

            val trueLength = lengthM()
            // The database as it stands today: the stray point is already a vertex at the end.
            val strayId = waypoints.create(cid, "dos", latFor(1200.0), 0.0, null, null)
            waypoints.attach(trailId, strayId, position = 12)
            assertTrue(lengthM() > trueLength + 900.0, "precondition: the phantom segment is there")

            waypoints.detach(trailId, strayId)
            val outcome = waypoints.attach(trailId, strayId)

            assertIs<TrailAttachment.Annotation>(outcome)
            assertTrue(abs(lengthM() - trueLength) < 0.001, "the trail did not return to its real length")
            assertEquals(listOf("dos"), annotations.forTrail(trailId).map { it.waypoint.name })
            // Kept, not corrected: 1200 m north is exactly where it was recorded.
            assertEquals(latFor(1200.0), annotations.forTrail(trailId).single().waypoint.lat)
        }

    @Test
    fun theTrailsOwnListShowsAnnotationsAndVerticesTogether() =
        runTest {
            // The user made one gesture — "attach this to that trail" — and gets one list. Which
            // table the row landed in is the app's problem, not theirs. Without this the waypoint
            // someone marks mid-walk would simply vanish from the trail they marked it on.
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid)

            waypoints.attach(trailId, waypoints.create(cid, "Far gate", latFor(150.0), 0.0, null, null))
            waypoints.attach(trailId, waypoints.create(cid, "Near gate", latFor(40.0), 0.0, null, null))

            // Ordered by where they fall along the trail, not by when they were attached.
            assertEquals(
                listOf("Near gate", "Far gate"),
                db.waypointQueries
                    .namedWaypointsForTrail(trailId)
                    .executeAsList()
                    .map { it.name },
            )
        }

    @Test
    fun theListSaysWhichRowsAreGeometryAndWhichAreNot() =
        runTest {
            // One list to the user, two kinds underneath, and the difference is not cosmetic: only
            // a vertex can be reordered, so the editor offers "Move up"/"Move down" for vertices
            // alone — `setPosition` finds no row for an annotation and returns having done nothing,
            // which a TalkBack user would hear as a success that never happened.
            //
            // It is also why the trail's ends cannot come from this list at all: as the last
            // assertion shows, a recorded trail has *no* named vertices, so there is nothing here
            // to read them from. They come from the geometry (`trailEndsForCollection`).
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid)

            waypoints.attach(trailId, waypoints.create(cid, "Bench", latFor(90.0), 0.0, null, null))

            val rows = TrailRepositoryImpl(db).observeNamedWaypointsForTrail(trailId).first()

            assertEquals(listOf("Bench"), rows.map { it.waypoint.name })
            assertTrue(rows.single().isAnnotation, "an annotation was presented as geometry")
            assertEquals(emptyList(), rows.filterNot { it.isAnnotation }, "a recorded trail has no named vertices here")
        }

    @Test
    fun aWaypointOnlyLoopKeepsBothOfItsEnds() =
        runTest {
            // A hand-built loop — "test loop" — is geometry all the way through: no track points,
            // so nothing about it is ever an annotation, and both of its ends stay real vertices.
            // Its two ends sit at the same place, which is what makes it a loop, but they are
            // distinct waypoints (`uq_trail_waypoint_pair` forbids attaching one point twice), so
            // the screen still offers Follow and Reverse rather than collapsing them into one.
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = trails.create(cid, "test loop", null)

            val names = listOf("Gate out" to 0.0, "North corner" to 100.0, "East corner" to 60.0, "Gate back" to 0.0)
            names.forEach { (name, m) ->
                val outcome = waypoints.attach(trailId, waypoints.create(cid, name, latFor(m), 0.0, null, null))
                assertIs<TrailAttachment.Vertex>(outcome, "$name should be geometry on a hand-built loop")
            }

            val rows = trails.observeNamedWaypointsForTrail(trailId).first()
            val vertices = rows.filterNot { it.isAnnotation }.map { it.waypoint }

            assertEquals(names.map { it.first }, vertices.map { it.name }, "vertices in the order they were built")
            assertEquals("Gate out", vertices.first().name)
            assertEquals("Gate back", vertices.last().name)
            assertTrue(vertices.first().id != vertices.last().id, "two ends, so Follow and Reverse are both offered")
        }

    @Test
    fun detachingRemovesAnAnnotationToo() =
        runTest {
            // Detach is one gesture to the user; which table the point was in is not their problem.
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid)

            val id = waypoints.create(cid, "Bench", latFor(90.0), 0.0, null, null)
            waypoints.attach(trailId, id)
            assertEquals(1, annotations.forTrail(trailId).size)

            waypoints.detach(trailId, id)

            assertEquals(emptyList(), annotations.forTrail(trailId))
        }

    @Test
    fun deletingTheWaypointRemovesItsAnnotations() =
        runTest {
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid)

            val id = waypoints.create(cid, "Bench", latFor(90.0), 0.0, null, null)
            waypoints.attach(trailId, id)
            waypoints.remove(id)

            assertEquals(emptyList(), annotations.forTrail(trailId), "an annotation outlived its waypoint")
        }

    @Test
    fun recordingFurtherReprojectsWhatIsAlreadyAnnotated() =
        runTest {
            // An annotation past the old end clamps to it. Extend the recording and it has to move,
            // or the trail grows underneath an annotation that keeps pointing at the old terminus.
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val annotations = TrailAnnotationRepositoryImpl(db)
            val cid = db.defaultCollection()
            val trailId = recordedTrail(db, cid, points = 6) // 0 m … 100 m

            val aheadId = waypoints.create(cid, "Ahead", latFor(180.0), 0.0, null, null)
            waypoints.attach(trailId, aheadId)
            assertEquals(4, annotations.forTrail(trailId).single().segmentIndex, "clamped to the last segment")

            // Keep walking: the trail now reaches 200 m, and the annotation is interior.
            repeat(5) { i -> waypoints.createTrackPoint(trailId, "tp${6 + i}", latFor(120.0 + i * 20.0), 0.0, null) }

            assertEquals(8, annotations.forTrail(trailId).single().segmentIndex, "180 m along, 20 m spacing")
        }
}
