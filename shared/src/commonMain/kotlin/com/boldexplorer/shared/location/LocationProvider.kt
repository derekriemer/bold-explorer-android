package com.boldexplorer.shared.location

import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.flow.Flow

/** Platform-agnostic location source. Implemented by FusedLocationProviderImpl on Android. */
interface LocationProvider {
    val locationFlow: Flow<LocationSample>
}
