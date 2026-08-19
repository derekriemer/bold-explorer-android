package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0002 §3 — [followerIndexFor] maps a recorded along-track anchor onto the traversal-order
 * index [TrailFollower.start] arms from.
 */
class TrailFollowerArmingTest {
    private fun latFor(m: Double) = m / 111_194.9

    private val straightPoints = listOf(TrailPoint(1, "Start", 0.0, 0.0), TrailPoint(2, "End", latFor(1000.0), 0.0))
    private val straightPoly = TrailPolyline(straightPoints.map { LatLng(it.lat, it.lon) })

    @Test
    fun forward_anchorSitsAtOrJustAheadOfTheReturnedIndex() {
        val anchor = straightPoly.project(LatLng(latFor(475.0), 0.0))!!

        val fromIndex = followerIndexFor(straightPoly, anchor, TravelDirection.Forward)

        assertTrue(
            straightPoly.cumulativeM[fromIndex] >= anchor.alongTrackM,
            "the armed vertex must be at or ahead of the anchor",
        )
        assertTrue(
            fromIndex == 0 || straightPoly.cumulativeM[fromIndex - 1] < anchor.alongTrackM,
            "must be the *first* such vertex, not merely one of them",
        )
    }

    @Test
    fun reverse_recordedIndexIsTheLargestAtOrBehindTheAnchor() {
        val anchor = straightPoly.project(LatLng(latFor(475.0), 0.0))!!

        val fromIndex = followerIndexFor(straightPoly, anchor, TravelDirection.Reverse)
        val recordedIndex = straightPoly.size - 1 - fromIndex

        assertTrue(
            straightPoly.cumulativeM[recordedIndex] <= anchor.alongTrackM,
            "the recorded vertex behind fromIndex must be at or behind the anchor",
        )
        assertTrue(
            recordedIndex == straightPoly.size - 1 || straightPoly.cumulativeM[recordedIndex + 1] > anchor.alongTrackM,
            "must be the *largest* such vertex, not merely one of them",
        )
    }

    @Test
    fun anchorAtTheTraversalEnd_armsTheLastIndexAndDoesNotCompleteBeforeTravel() {
        // Standing at the far end with no travel yet — the completion guard the radius sits behind
        // (TrailCompletionTest.coldFollowAtTheEndDoesNotComplete) must still hold when the follower
        // was armed there directly by an anchor, not by walking to it.
        val anchor = straightPoly.project(LatLng(latFor(1000.0), 0.0))!!
        val fromIndex = followerIndexFor(straightPoly, anchor, TravelDirection.Forward)
        assertEquals(straightPoints.size - 1, fromIndex, "the anchor is the trail's far end")

        val f = TrailFollower().apply { start(straightPoints, fromIndex = fromIndex) }

        assertNull(
            f.onLocationUpdate(LatLng(latFor(998.0), 0.0), accuracyM = 3.0, completion = CompletionEvidence.None),
            "completed on the first fix of a follow armed at the end, before a step was taken",
        )
    }
}
