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
