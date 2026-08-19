package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng

/**
 * Shapes that attack the same-ground rule in ADR 0002 §4, rather than exercising agreed behaviour.
 *
 * Each is a case where a rule based on **along-track** separation and one based on **spatial**
 * separation disagree. They exist so that reintroducing the former — which an earlier draft of the
 * ADR did, as `ARMING_MIN_SEPARATION_M = 150` — fails here instead of in the field.
 */
object ArmingShapes {
    /**
     * A 160 m lollipop: 30 m stick, 100 m loop, 30 m stick back.
     *
     * The counterexample to along-track separation. Standing mid-stick, the two occurrences of that
     * ground are 130 m apart *along the trail* and 0 m apart *on* it — a 150 m collapse eats one of
     * them and silently deletes a continuation.
     */
    val smallLoop: List<LatLng> =
        densify(
            listOf(
                offsetFromOrigin(northM = 0.0, eastM = 0.0),
                offsetFromOrigin(northM = 30.0, eastM = 0.0),
                offsetFromOrigin(northM = 30.0, eastM = 25.0),
                offsetFromOrigin(northM = 55.0, eastM = 25.0),
                offsetFromOrigin(northM = 55.0, eastM = 0.0),
                offsetFromOrigin(northM = 30.0, eastM = 0.0),
                offsetFromOrigin(northM = 0.0, eastM = 0.0),
            ),
            spacingM = 5.0,
        )

    /** Mid-stick on [smallLoop], where the trail's two passes over that ground are 130 m apart along it. */
    const val SMALL_LOOP_MID_STICK_M = 15.0

    /**
     * Two 200 m arms [gapM] apart, traversed in opposite directions.
     *
     * Built on [switchbackShape], which already exists for the continuity tests. At a small gap this
     * is the shape the same-ground rule *cannot* tell from one path recorded twice — see ADR 0002 §5.
     */
    fun switchback(gapM: Double): List<LatLng> = densify(switchbackShape(legM = 200.0, gapM = gapM), spacingM = 10.0)

    /**
     * A figure-eight: two lobes meeting at one crossing, each ~300 m round.
     *
     * The crossing is ground the trail covers exactly twice, so a walker standing on it has two
     * continuations in either direction.
     */
    val figureEight: List<LatLng> =
        densify(
            listOf(
                offsetFromOrigin(northM = 0.0, eastM = 0.0), // the crossing
                offsetFromOrigin(northM = 75.0, eastM = 75.0),
                offsetFromOrigin(northM = 150.0, eastM = 0.0),
                offsetFromOrigin(northM = 75.0, eastM = -75.0),
                offsetFromOrigin(northM = 0.0, eastM = 0.0), // the crossing again
                offsetFromOrigin(northM = -75.0, eastM = 75.0),
                offsetFromOrigin(northM = -150.0, eastM = 0.0),
                offsetFromOrigin(northM = -75.0, eastM = -75.0),
                offsetFromOrigin(northM = 0.0, eastM = 0.0),
            ),
            spacingM = 10.0,
        )

    /**
     * A stem walked out, back, and out again — 300 m of ground covered three times.
     *
     * The arbitrary-N case: a walker on the stem has three places on this trail to be, so arming owes
     * them three options rather than a choice between the first two it happens to find.
     */
    val triplePass: List<LatLng> =
        densify(
            listOf(
                offsetFromOrigin(northM = 0.0, eastM = 0.0),
                offsetFromOrigin(northM = 300.0, eastM = 0.0),
                offsetFromOrigin(northM = 0.0, eastM = 0.0),
                offsetFromOrigin(northM = 300.0, eastM = 0.0),
            ),
            spacingM = 10.0,
        )

    const val TRIPLE_PASS_MID_STEM_M = 150.0
}
