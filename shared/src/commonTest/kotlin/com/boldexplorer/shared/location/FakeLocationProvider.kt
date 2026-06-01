package com.boldexplorer.shared.location

import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Test double for LocationProvider. Emit scripted fixes via [emit] or [emitBlocking]. */
class FakeLocationProvider : LocationProvider {
    private val _flow = MutableSharedFlow<LocationSample>(replay = 1)
    override val locationFlow: Flow<LocationSample> = _flow

    suspend fun emit(sample: LocationSample) = _flow.emit(sample)

    /** Non-suspending emit for use in synchronous test setup. Returns false if buffer is full. */
    fun emitBlocking(sample: LocationSample): Boolean = _flow.tryEmit(sample)
}
