package com.boldexplorer.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide bridge for "set this waypoint as the GPS target" requests made from screens other than the
 * GPS screen (Waypoints, Trails). Screens write a waypoint id here; [GpsViewModel] observes it and
 * re-points the collection explorer, so the user can keep navigating without ever leaving their
 * current screen.
 *
 * A singleton because the producing and consuming ViewModels are distinct and must share one channel.
 */
@Singleton
class TargetingStateHolder
    @Inject
    constructor() {
        private val _waypointTargetId = MutableStateFlow<Long?>(null)
        val waypointTargetId: StateFlow<Long?> = _waypointTargetId.asStateFlow()

        /** Request that [waypointId] become the GPS target. */
        fun requestWaypointTarget(waypointId: Long) {
            _waypointTargetId.value = waypointId
        }

        /** Acknowledge consumption so the same request is not re-applied on the next observation. */
        fun clear() {
            _waypointTargetId.value = null
        }
    }
