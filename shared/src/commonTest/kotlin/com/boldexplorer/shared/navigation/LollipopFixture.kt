package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng

/**
 * A lollipop: a stick walked out, a loop at the far end, then the same stick walked back.
 *
 * The shape ADR 0002 is about. Proportions echo the owner's recorded "lake loop" — a ~950 m stick
 * and a ~1.8 km loop — with round numbers so a test can assert along-track landmarks as literals
 * rather than approximations of a recorded walk.
 *
 * The stick is the point: its ground is covered twice, at `0 → 950` and again at `2550 → 3500`, so
 * a walker standing on it projects onto two places on the same trail. That is what arming has to
 * resolve, and no other fixture in this repo has it.
 *
 * Densified at 10 m so candidates behave as they do on a recorded trail rather than on a four-vertex
 * sketch — [TrailPolyline.candidates] finds local minima, and vertex spacing changes where they land.
 */
object LollipopFixture {
    /** Stick length, and so the along-track position of the junction on the way out. */
    const val JUNCTION_OUT_M = 950.0

    /** The junction again, on the way back: stick + loop. */
    const val JUNCTION_BACK_M = 2550.0

    const val TOTAL_M = 3500.0

    /** Half way along the stick, outbound. The return pass of the same ground is `TOTAL_M - this`. */
    const val MID_STICK_M = 475.0

    private val shape =
        listOf(
            offsetFromOrigin(northM = 0.0, eastM = 0.0), // trailhead
            offsetFromOrigin(northM = 950.0, eastM = 0.0), // junction
            offsetFromOrigin(northM = 950.0, eastM = 400.0),
            offsetFromOrigin(northM = 1350.0, eastM = 400.0),
            offsetFromOrigin(northM = 1350.0, eastM = 0.0),
            offsetFromOrigin(northM = 950.0, eastM = 0.0), // junction, closing the loop
            offsetFromOrigin(northM = 0.0, eastM = 0.0), // trailhead again
        )

    /**
     * The return pass offset 15 m east of the outbound one, so no two passes coincide exactly.
     *
     * The realistic case: a walker does not retrace their own GPS track, and the recorded strands at
     * the measured lake loop junction are 16.6 m apart. Landmarks here are not round — assert
     * relations, not literals.
     */
    private val jitteredShape =
        listOf(
            offsetFromOrigin(northM = 0.0, eastM = 0.0),
            offsetFromOrigin(northM = 950.0, eastM = 0.0),
            offsetFromOrigin(northM = 950.0, eastM = 400.0),
            offsetFromOrigin(northM = 1350.0, eastM = 400.0),
            offsetFromOrigin(northM = 1350.0, eastM = 15.0),
            offsetFromOrigin(northM = 950.0, eastM = 15.0),
            offsetFromOrigin(northM = 0.0, eastM = 15.0),
        )

    val points: List<LatLng> = densify(shape, spacingM = 10.0)

    val jittered: List<LatLng> = densify(jitteredShape, spacingM = 10.0)
}
