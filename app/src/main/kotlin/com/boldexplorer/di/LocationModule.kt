package com.boldexplorer.di

import com.boldexplorer.location.LocationProviderRouter
import com.boldexplorer.shared.location.LocationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: LocationProviderRouter): LocationProvider
}
