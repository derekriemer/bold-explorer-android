package com.boldexplorer.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A trail-navigation request raised from a screen other than the GPS screen (the Trails screen).
 * [GpsViewModel] observes it and starts the corresponding follow / record session.
 */
sealed interface TrailTargetRequest {
    val trailId: Long

    /** Follow the trail, optionally from its end ([reversed] = true). */
    data class Follow(
        override val trailId: Long,
        val reversed: Boolean,
    ) : TrailTargetRequest

    /** Begin auto-recording track points onto the trail. */
    data class Record(
        override val trailId: Long,
    ) : TrailTargetRequest
}

/**
 * App-wide bridge for navigation requests made from screens other than the GPS screen (Waypoints,
 * Trails). Screens write a request here; [GpsViewModel] observes it and re-points navigation, so the
 * user can keep navigating without ever leaving their current screen.
 *
 * Both channels are [MutableStateFlow] (not events): the latest request persists until consumed, so a
 * request raised before the GPS ViewModel exists is still honoured once it starts observing.
 *
 * A singleton because the producing and consuming ViewModels are distinct and must share one channel.
 */
@Singleton
class TargetingStateHolder
    @Inject
    constructor() {
        private val _waypointTargetId = MutableStateFlow<Long?>(null)
        val waypointTargetId: StateFlow<Long?> = _waypointTargetId.asStateFlow()

        private val _trailRequest = MutableStateFlow<TrailTargetRequest?>(null)
        val trailRequest: StateFlow<TrailTargetRequest?> = _trailRequest.asStateFlow()

        /** Request that [waypointId] become the GPS target. */
        fun requestWaypointTarget(waypointId: Long) {
            _waypointTargetId.value = waypointId
        }

        /** Acknowledge consumption so the same request is not re-applied on the next observation. */
        fun clear() {
            _waypointTargetId.value = null
        }

        /** Request following [trailId], from its end when [reversed]. */
        fun requestTrailFollow(
            trailId: Long,
            reversed: Boolean,
        ) {
            _trailRequest.value = TrailTargetRequest.Follow(trailId, reversed)
        }

        /** Request auto-recording onto [trailId]. */
        fun requestTrailRecord(trailId: Long) {
            _trailRequest.value = TrailTargetRequest.Record(trailId)
        }

        /** Acknowledge consumption of a trail request. */
        fun clearTrail() {
            _trailRequest.value = null
        }
    }
