package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CollectionExplorerTest {
    private val home = LatLng(0.0, 0.0)
    private val north = point(1, "North", lat = 0.0009, lon = 0.0)
    private val east = point(2, "East", lat = 0.0, lon = 0.0018)
    private val west = point(3, "West", lat = 0.0, lon = -0.0027)

    @Test
    fun skipTarget_advancesToNextNearestUnvisitedPoint() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(west, east, north), exploreMode = false)

        explorer.onLocationUpdate(home)

        val firstState = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, firstState.target?.id)

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
        explorer.load(listOf(east, north), exploreMode = false)

        explorer.onLocationUpdate(home)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(north.id, state.target?.id)
    }

    @Test
    fun autoTarget_prefersFartherPointAheadOfTravelDirection() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), exploreMode = false)

        explorer.onLocationUpdate(home, travelHeadingDeg = 90.0)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertEquals(east.id, state.target?.id)
    }

    @Test
    fun reachedTarget_withExploreOff_pausesInsteadOfPickingAnotherTarget() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), exploreMode = false)

        val event = explorer.onLocationUpdate(LatLng(north.waypoint.lat, north.waypoint.lon))

        assertIs<CollectionExplorerEvent.PointReached>(event)
        assertNull(event.next)

        val reachedState = explorer.state.value as CollectionExplorerState.Active
        assertIs<CollectionTargeting.Paused>(reachedState.targeting)
        assertNull(reachedState.target)

        explorer.onLocationUpdate(home)

        val laterState = explorer.state.value as CollectionExplorerState.Active
        assertIs<CollectionTargeting.Paused>(laterState.targeting)
        assertNull(laterState.target)
    }

    @Test
    fun turningExploreOnFromPaused_resumesAutomaticTargeting() {
        val explorer = CollectionExplorer()
        explorer.load(listOf(north, east), exploreMode = false)
        explorer.onLocationUpdate(LatLng(north.waypoint.lat, north.waypoint.lon))

        explorer.setExploreMode(true)
        explorer.onLocationUpdate(home)

        val state = explorer.state.value as CollectionExplorerState.Active
        assertIs<CollectionTargeting.Auto>(state.targeting)
        assertEquals(east.id, state.target?.id)
    }

    private fun point(id: Long, name: String, lat: Double, lon: Double): CollectionPoint.Standalone =
        CollectionPoint.Standalone(
            Waypoint(
                id = id,
                name = name,
                lat = lat,
                lon = lon,
                elevM = null,
                description = null,
                createdAt = 0L,
            ),
        )
}
