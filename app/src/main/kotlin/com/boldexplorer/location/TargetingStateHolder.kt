package com.boldexplorer.location

import com.boldexplorer.shared.navigation.ExternalTargetRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ExternalTargetCommand(
    val id: Long,
    val request: ExternalTargetRequest,
    val successMessage: String,
    val failureMessage: String,
)

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
        private val _externalTarget = MutableStateFlow<ExternalTargetCommand?>(null)
        val externalTarget: StateFlow<ExternalTargetCommand?> = _externalTarget.asStateFlow()
        private var nextTargetId = 0L

        private val _trailRequest = MutableStateFlow<TrailTargetRequest?>(null)
        val trailRequest: StateFlow<TrailTargetRequest?> = _trailRequest.asStateFlow()

        /** Request that [waypointId] become the GPS target. */
        fun requestWaypointTarget(
            waypointId: Long,
            label: String,
        ) {
            _externalTarget.value =
                command(
                    request = ExternalTargetRequest.Waypoint(waypointId),
                    successMessage = "$label set as GPS target",
                    failureMessage = "Could not target $label — its collection is not loaded",
                )
        }

        /**
         * Request that one end of [trailId] become the GPS target — point navigation, not a follow.
         *
         * Which end is not recoverable from a waypoint id, and a recorded trail's ends are track
         * points that belong to no collection, so the request has to carry both facts itself.
         */
        fun requestTrailEndTarget(
            trailId: Long,
            isStart: Boolean,
            label: String,
        ) {
            _externalTarget.value =
                command(
                    request = ExternalTargetRequest.TrailEnd(trailId, isStart),
                    successMessage = "Navigating to $label",
                    failureMessage = "Could not navigate to $label — its collection is not loaded",
                )
        }

        private fun command(
            request: ExternalTargetRequest,
            successMessage: String,
            failureMessage: String,
        ) = ExternalTargetCommand(++nextTargetId, request, successMessage, failureMessage)

        /** Consume [command] only if a newer request has not replaced it while it was applied. */
        fun clear(command: ExternalTargetCommand) {
            _externalTarget.compareAndSet(command, null)
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
