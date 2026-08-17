package com.boldexplorer.location

import com.boldexplorer.shared.navigation.ExternalTargetRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Tests the persistent, correlated cross-screen targeting channel (issue #78). */
class TargetingStateHolderTest {
    @Test
    fun aTrailEndRequestCarriesItsIdentityEndAndMessages() {
        val holder = TargetingStateHolder()

        holder.requestTrailEndTarget(trailId = 7L, isStart = false, label = "the end of Loop")

        val command = requireNotNull(holder.externalTarget.value)
        assertEquals(ExternalTargetRequest.TrailEnd(7L, isStart = false), command.request)
        assertEquals("Navigating to the end of Loop", command.successMessage)
        assertEquals(
            "Could not navigate to the end of Loop — its collection is not loaded",
            command.failureMessage,
        )
    }

    @Test
    fun waypointAndTrailEndRequestsAreDistinguishable() {
        val holder = TargetingStateHolder()

        holder.requestWaypointTarget(42L, "Gate")
        assertEquals(ExternalTargetRequest.Waypoint(42L), holder.externalTarget.value?.request)

        holder.requestTrailEndTarget(trailId = 42L, isStart = true, label = "the start of Loop")
        assertEquals(
            ExternalTargetRequest.TrailEnd(42L, isStart = true),
            holder.externalTarget.value?.request,
        )
    }

    @Test
    fun aNewRequestGetsANewCorrelationIdentity() {
        val holder = TargetingStateHolder()
        holder.requestWaypointTarget(1L, "First")
        val first = requireNotNull(holder.externalTarget.value)

        holder.requestWaypointTarget(2L, "Second")

        assertNotEquals(first.id, holder.externalTarget.value?.id)
    }

    @Test
    fun completingAnOlderRequestDoesNotClearItsReplacement() {
        val holder = TargetingStateHolder()
        holder.requestWaypointTarget(1L, "First")
        val first = requireNotNull(holder.externalTarget.value)
        holder.requestWaypointTarget(2L, "Second")
        val second = requireNotNull(holder.externalTarget.value)

        holder.clear(first)

        assertEquals(second, holder.externalTarget.value)
    }

    @Test
    fun completingTheCurrentRequestClearsIt() {
        val holder = TargetingStateHolder()
        holder.requestWaypointTarget(1L, "First")
        val command = requireNotNull(holder.externalTarget.value)

        holder.clear(command)

        assertNull(holder.externalTarget.value)
    }
}
