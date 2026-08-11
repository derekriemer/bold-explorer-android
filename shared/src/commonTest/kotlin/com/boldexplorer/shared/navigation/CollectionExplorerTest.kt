package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionExplorerTest {
    private val home = LatLng(0.0, 0.0)
    private val north = point(1, "North", lat = 0.0009, lon = 0.0)
    private val east = point(2, "East", lat = 0.0, lon = 0.0018)
    private val west = point(3, "West", lat = 0.0, lon = -0.0027)

    @Test
    fun skipTarget_advancesToNextNearestUnvisitedPoint() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(west, east, north), autoAdvance = false)

        // No target yet — auto-advance is off, so nothing was auto-picked. Skip still works: it
        // treats the nearest unvisited point as the one being rejected.
        explorer.onLocationUpdate(home)

        val firstState = explorer.state.value as CollectionExplorerState.Active
        assertNull(firstState.target)

        val event = explorer.skipTarget()

        assertIs<CollectionExplorerEvent.TargetSkipped>(event)
        assertEquals(north.id, event.skipped.id)
        assertEquals(east.id, event.next?.id)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(east.id, state.target?.id)
        assertEquals(listOf(north.id), state.visitedIds)
    }

    @Test
    fun skipTarget_returnsNullWhenIdle() {
        assertNull(CollectionExplorer().skipTarget())
    }

    @Test
    fun autoTarget_usesNearestPointWhenTravelHeadingIsMissing() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(east, north), autoAdvance = true)

        explorer.onLocationUpdate(home)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, state.target?.id)
    }

    @Test
    fun autoTarget_prefersFartherPointAheadOfTravelDirection() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = true)

        explorer.onLocationUpdate(home, travelHeadingDeg = 90.0)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(east.id, state.target?.id)
    }

    @Test
    fun withAutoAdvanceOff_noTargetIsPickedUntilOneIsChosen() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = false)

        explorer.onLocationUpdate(home)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertNull(state.target)
    }

    @Test
    fun reachedTarget_withAutoAdvanceOff_keepsTargetingSamePoint() {
        // Issue #8: reaching a manually-targeted waypoint must not clear it — the user may still
        // want to close the last stretch of distance, and only an explicit clear/re-select should
        // change the target.
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = false)
        explorer.selectTarget(north)

        val event = explorer.onLocationUpdate(LatLng(north.waypoint.lat, north.waypoint.lon))

        assertIs<CollectionExplorerEvent.PointReached>(event)
        assertNull(event.next) // no auto-pick happened — but the state still targets `north`, below.

        val reachedState = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, reachedState.target?.id)

        // Lingering at the same spot must not re-fire PointReached every fix.
        val second = explorer.onLocationUpdate(LatLng(north.waypoint.lat, north.waypoint.lon))
        assertNull(second)

        // Walking away doesn't clear it either — only an explicit clear/re-select does.
        explorer.onLocationUpdate(home)
        val laterState = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, laterState.target?.id)
    }

    @Test
    fun reachedTarget_reselectingSamePointAfterClear_firesAgain() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = false)
        explorer.selectTarget(north)
        explorer.onLocationUpdate(LatLng(north.waypoint.lat, north.waypoint.lon))

        explorer.clearTarget()
        explorer.selectTarget(north)
        val event = explorer.onLocationUpdate(LatLng(north.waypoint.lat, north.waypoint.lon))

        assertIs<CollectionExplorerEvent.PointReached>(event)
        assertEquals(north.id, event.reached.id)
    }

    @Test
    fun turningAutoAdvanceOnAfterReach_resumesAutomaticTargeting() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = false)
        explorer.selectTarget(north)
        explorer.onLocationUpdate(LatLng(north.waypoint.lat, north.waypoint.lon))

        explorer.setAutoAdvance(true)
        explorer.onLocationUpdate(home)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(east.id, state.target?.id)
    }

    // ── Visited handling (cap, eviction, exclusion, reset) ──────────────────────────

    @Test
    fun visitedQueue_capsAtThree_evictsOldest_andAllowsReselection() {
        val explorer = CollectionExplorer()
        // Five points due north at increasing distance; nearest-first is p1..p5.
        val p1 = point(1, "p1", lat = 0.0009, lon = 0.0)
        val p2 = point(2, "p2", lat = 0.0018, lon = 0.0)
        val p3 = point(3, "p3", lat = 0.0027, lon = 0.0)
        val p4 = point(4, "p4", lat = 0.0036, lon = 0.0)
        val p5 = point(5, "p5", lat = 0.0045, lon = 0.0)
        explorer.load(listOf(p5, p4, p3, p2, p1), autoAdvance = false)
        explorer.onLocationUpdate(home)

        // Skip the four nearest in turn. VISITED_CAP = 3, so the oldest (p1) is evicted.
        explorer.skipTarget() // visited [p1], target p2
        explorer.skipTarget() // visited [p1,p2], target p3
        explorer.skipTarget() // visited [p1,p2,p3], target p4
        val event = explorer.skipTarget() // visited capped → [p2,p3,p4], p1 selectable again

        val state = explorer.state.value as CollectionExplorerState.Active
        assertContentEquals(listOf(p2.id, p3.id, p4.id), state.visitedIds)
        // p1 was evicted from the visited queue, so it becomes the next nearest unvisited target.
        assertEquals(p1.id, state.target?.id)
        assertEquals(p1.id, (event as CollectionExplorerEvent.TargetSkipped).next?.id)
    }

    @Test
    fun clearVisited_resetsQueueAndResumesAutoTargeting() {
        // Reachable in the UI only with auto-advance on ("Reset visited" is gated on it) —
        // clearVisited() itself doesn't force a target, it just clears; the next GPS fix
        // repopulates it because autoAdvance is on.
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east, west), autoAdvance = true)
        explorer.onLocationUpdate(home)
        explorer.skipTarget()
        explorer.skipTarget()

        explorer.clearVisited()

        val cleared = explorer.state.value as CollectionExplorerState.Active
        assertEquals(emptyList(), cleared.visitedIds)
        assertNull(cleared.target)

        explorer.onLocationUpdate(home)
        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, state.target?.id)
    }

    // ── Arrival advancement ─────────────────────────────────────────────────────────

    @Test
    fun reachedTarget_withExploreOn_autoAdvancesToNextNearest() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = true)

        val event = explorer.onLocationUpdate(LatLng(north.waypoint.lat, north.waypoint.lon))

        assertIs<CollectionExplorerEvent.PointReached>(event)
        assertEquals(north.id, event.reached.id)
        assertEquals(east.id, event.next?.id)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(east.id, state.target?.id)
        assertContentEquals(listOf(north.id), state.visitedIds)
    }

    // ── Follow-from-end (TrailEnd never auto-reaches) ───────────────────────────────

    @Test
    fun trailEnd_withinApproach_firesNearTrailEnd_andNeverAutoReaches() {
        val explorer = CollectionExplorer()
        val end = trailEnd(trailId = 7, "Loop end", lat = 0.0009, lon = 0.0)
        explorer.load(listOf(end), autoAdvance = true)

        // Standing exactly on the trail end: within both TRAIL_APPROACH_M and REACH_THRESHOLD_M.
        val event = explorer.onLocationUpdate(LatLng(end.waypoint.lat, end.waypoint.lon))

        // A trail end is announced for Follow, never consumed as a reached point.
        assertIs<CollectionExplorerEvent.NearTrailEnd>(event)
        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(end.id, state.target?.id)
        assertEquals(emptyList(), state.visitedIds)
        assertTrue((state.nearTrailEndM ?: Double.MAX_VALUE) <= NavigationPolicy.TRAIL_APPROACH_M)
    }

    @Test
    fun trailEnd_beyondApproach_clearsNearTrailEndAndStaysTargeted() {
        val explorer = CollectionExplorer()
        val end = trailEnd(trailId = 7, "Loop end", lat = 0.0009, lon = 0.0)
        explorer.load(listOf(end), autoAdvance = true)

        // ~100 m away — well beyond TRAIL_APPROACH_M.
        val event = explorer.onLocationUpdate(home)

        assertNull(event)
        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(end.id, state.target?.id)
        assertNull(state.nearTrailEndM)
    }

    // ── Reload preserves in-progress targeting (issue #7) ────────────────────────────
    // Saving a waypoint reactively re-runs `load()` with the updated point list. That must not
    // discard an already-selected target or visited history — otherwise the freshly-saved point
    // (created at the user's current GPS fix, so it is essentially distance 0) wins the next
    // auto-target selection and silently hijacks navigation.

    @Test
    fun reload_preservesManualTarget_whenNewPointIsSaved() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = false)
        explorer.selectTarget(east)

        // A new waypoint is saved at the user's current location and the point list reloads.
        val justSaved = point(4, "Just saved", lat = home.lat, lon = home.lon)
        explorer.load(listOf(north, east, justSaved), autoAdvance = false)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(east.id, state.target?.id)

        // Confirm it sticks on the next GPS fix too, even standing right on top of the new point.
        explorer.onLocationUpdate(home)
        val afterFix = explorer.state.value as CollectionExplorerState.Active
        assertEquals(east.id, afterFix.target?.id)
    }

    @Test
    fun reload_preservesAutoTarget_whenNewPointIsSavedAtCurrentLocation() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = true)
        explorer.onLocationUpdate(home)
        val before = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, before.target?.id)

        // Save a new waypoint right at the user's current location (distance ~0) — this is the
        // scenario from issue #7: it must not steal the active target.
        val justSaved = point(4, "Just saved", lat = home.lat, lon = home.lon)
        explorer.load(listOf(north, east, justSaved), autoAdvance = true)

        val reloaded = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, reloaded.target?.id)

        explorer.onLocationUpdate(home)
        val afterFix = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, afterFix.target?.id)
    }

    @Test
    fun reload_preservesVisitedHistory_whenNewPointIsSaved() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east, west), autoAdvance = false)
        explorer.onLocationUpdate(home)
        explorer.skipTarget() // visits north

        val justSaved = point(4, "Just saved", lat = home.lat, lon = home.lon)
        explorer.load(listOf(north, east, west, justSaved), autoAdvance = false)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(listOf(north.id), state.visitedIds)
    }

    @Test
    fun reload_afterStop_startsFreshAutoTargeting() {
        // A genuine collection switch goes through stop() first, so no stale target/visited state
        // should leak into the newly loaded collection.
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), autoAdvance = false)
        explorer.selectTarget(east)

        explorer.stop()
        explorer.load(listOf(north, east), autoAdvance = false)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertNull(state.target)
        assertEquals(emptyList(), state.visitedIds)
    }

    // ── Proximity announcements (once per point per session) ────────────────────────

    @Test
    fun nearbyPoint_firesOnceForNonTargetPoint_thenStaysSilent() {
        val explorer = CollectionExplorer()
        val farTarget = point(1, "Far", lat = 0.005, lon = 0.0)
        val nearby = point(2, "Nearby", lat = 0.0001, lon = 0.0)
        explorer.load(listOf(farTarget, nearby), autoAdvance = false)
        // Force a far manual target so the close point stays a non-target proximity hit.
        explorer.selectTarget(farTarget)

        val first = explorer.onLocationUpdate(home)
        assertIs<CollectionExplorerEvent.NearbyPoint>(first)
        assertEquals(nearby.id, first.point.id)

        // Same spot again: the point was already announced this session, so no repeat.
        val second = explorer.onLocationUpdate(home)
        assertNull(second)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertTrue(nearby.id in state.proximityAnnouncedIds)
        assertEquals(farTarget.id, state.target?.id)
    }

    @Test
    fun nearbyPoint_firesWithNoTargetSelected_autoAdvanceOff() {
        val explorer = CollectionExplorer()
        val far = point(1, "Far", lat = 0.005, lon = 0.0)
        val nearby = point(2, "Nearby", lat = 0.0001, lon = 0.0)
        explorer.load(listOf(far, nearby), autoAdvance = false)
        // No selectTarget(): auto-advance is off, so target stays null — the scan must still run.

        val event = explorer.onLocationUpdate(home)

        assertIs<CollectionExplorerEvent.NearbyPoint>(event)
        assertEquals(nearby.id, event.point.id)
        assertNull((explorer.state.value as CollectionExplorerState.Active).target)
    }

    @Test
    fun nearbyPoint_withNoTarget_stillFiresOnlyOncePerPoint() {
        val explorer = CollectionExplorer()
        val nearby = point(1, "Nearby", lat = 0.0001, lon = 0.0)
        explorer.load(listOf(nearby), autoAdvance = false)

        val first = explorer.onLocationUpdate(home)
        assertIs<CollectionExplorerEvent.NearbyPoint>(first)

        val second = explorer.onLocationUpdate(home)
        assertNull(second)
    }

    private fun point(
        id: Long,
        name: String,
        lat: Double,
        lon: Double,
    ): CollectionPoint.Standalone = CollectionPoint.Standalone(waypoint(id, name, lat, lon))

    private fun trailEnd(
        trailId: Long,
        name: String,
        lat: Double,
        lon: Double,
    ): CollectionPoint.TrailEnd =
        CollectionPoint.TrailEnd(
            waypoint = waypoint(trailId * 100, name, lat, lon),
            trail = Trail(id = trailId, name = name, description = null, createdAt = 0L),
            isStart = false,
        )

    private fun waypoint(
        id: Long,
        name: String,
        lat: Double,
        lon: Double,
    ): Waypoint =
        Waypoint(
            id = id,
            name = name,
            lat = lat,
            lon = lon,
            elevM = null,
            description = null,
            createdAt = 0L,
        )
}
