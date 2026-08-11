package com.boldexplorer.shared.location

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.LocalFrame
import com.boldexplorer.shared.geo.Vec2
import com.boldexplorer.shared.geo.unitVec
import com.boldexplorer.shared.model.LocationSample

/**
 * Controlled degradation of GPS fixes, for field-testing behaviour that good GPS cannot falsify.
 *
 * Switchback matching is the motivating case. Under an accurate fix the nearest trail arm *is* the
 * correct arm, so even an unwindowed nearest-point scan picks correctly every time (#62). The
 * failure only appears once positional error approaches the separation between arms — a condition
 * you cannot wait for and should not have to find by accident on a cliff edge.
 *
 * **The two knobs are not interchangeable.** [accuracyOverrideM] changes only the *reported*
 * accuracy: it widens the off-trail gate and the completion radius, and is the right tool for
 * proving those clamp correctly. It leaves the fix in the right place, so the matcher never faces
 * an ambiguous choice and #62 stays invisible. [lateralBiasM] is what creates ambiguity.
 *
 * Bias is offered separately from [jitterSigmaM] because real multipath is not Gaussian. Near a
 * cliff or canyon wall the error pushes consistently to one side, which is precisely what puts a
 * fix on the wrong arm; symmetric noise tends to average out and is the gentler test.
 *
 * @property lateralBiasM constant offset applied to every fix, in metres.
 * @property biasBearingDeg true bearing of that offset. Zero is north.
 * @property jitterSigmaM standard deviation of additional per-fix noise, applied on both axes.
 * @property accuracyOverrideM replaces the fix's reported accuracy when set.
 * @property expiresAtMs wall-clock time after which this config stops applying. Degradation is
 *   deliberately self-limiting: a forgotten toggle would make correct behaviour look like a bug,
 *   and there is no visual cue a screen-reader user would notice.
 */
data class DegradationConfig(
    val lateralBiasM: Double = 0.0,
    val biasBearingDeg: Double = 0.0,
    val jitterSigmaM: Double = 0.0,
    val accuracyOverrideM: Double? = null,
    val expiresAtMs: Long = 0L,
) {
    /** True when any knob is set — independent of expiry. */
    private val hasEffect: Boolean
        get() = lateralBiasM != 0.0 || jitterSigmaM != 0.0 || accuracyOverrideM != null

    /** Whether degradation is actually applying at [nowMs]. */
    fun isActiveAt(nowMs: Long): Boolean = hasEffect && nowMs < expiresAtMs

    /**
     * Returns [sample] with degradation applied, or unchanged when inactive or expired.
     *
     * @param nextGaussian source of standard-normal samples; injected so tests are deterministic
     *   and so the caller owns the RNG.
     */
    fun apply(
        sample: LocationSample,
        nowMs: Long,
        nextGaussian: () -> Double,
    ): LocationSample {
        if (!isActiveAt(nowMs)) return sample

        val offset = offsetMetres(nextGaussian)
        val moved =
            if (offset.x == 0.0 && offset.y == 0.0) {
                sample
            } else {
                // Frame anchored at the fix itself, so the offset is exact regardless of latitude.
                val frame = LocalFrame(LatLng(sample.lat, sample.lon))
                val displaced = frame.toLatLng(offset)
                sample.copy(lat = displaced.lat, lon = displaced.lon)
            }
        return if (accuracyOverrideM != null) moved.copy(accuracy = accuracyOverrideM) else moved
    }

    private fun offsetMetres(nextGaussian: () -> Double): Vec2 {
        val bias = if (lateralBiasM != 0.0) unitVec(biasBearingDeg) else Vec2(0.0, 0.0)
        val jitterX = if (jitterSigmaM != 0.0) nextGaussian() * jitterSigmaM else 0.0
        val jitterY = if (jitterSigmaM != 0.0) nextGaussian() * jitterSigmaM else 0.0
        return Vec2(
            x = bias.x * lateralBiasM + jitterX,
            y = bias.y * lateralBiasM + jitterY,
        )
    }
}
