package com.boldexplorer.location

import com.boldexplorer.shared.navigation.ExternalTargetRequest
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
        private val _externalTarget = MutableStateFlow<ExternalTargetRequest?>(null)
        val externalTarget: StateFlow<ExternalTargetRequest?> = _externalTarget.asStateFlow()

        private val _targetApplied = MutableStateFlow<Boolean?>(null)

        /**
         * Whether the last target request actually took effect — `null` until one is answered.
         *
         * Exists because the request crosses ViewModels: the screen that raised it cannot otherwise
         * know, and used to announce success the instant it asked. For a blind user that is the
         * worst failure mode available, since there is no screen to glance at and no target to
         * hear; see issue #78.
         */
        val targetApplied: StateFlow<Boolean?> = _targetApplied.asStateFlow()

        private val _trailRequest = MutableStateFlow<TrailTargetRequest?>(null)
        val trailRequest: StateFlow<TrailTargetRequest?> = _trailRequest.asStateFlow()

        /** Request that [waypointId] become the GPS target. */
        fun requestWaypointTarget(waypointId: Long) {
            _targetApplied.value = null
            _externalTarget.value = ExternalTargetRequest.Waypoint(waypointId)
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
        ) {
            _targetApplied.value = null
            _externalTarget.value = ExternalTargetRequest.TrailEnd(trailId, isStart)
        }

        /** Report whether the request could be applied, for the screen that raised it. */
        fun reportTargetApplied(applied: Boolean) {
            _targetApplied.value = applied
        }

        /** Acknowledge consumption so the same request is not re-applied on the next observation. */
        fun clear() {
            _externalTarget.value = null
        }

        /** Acknowledge that the raising screen has surfaced the outcome. */
        fun clearTargetApplied() {
            _targetApplied.value = null
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
