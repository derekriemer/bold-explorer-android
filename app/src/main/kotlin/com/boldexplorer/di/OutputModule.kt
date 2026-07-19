package com.boldexplorer.di

import com.boldexplorer.shared.output.DefaultOutputPolicy
import com.boldexplorer.shared.output.OutputManager
import com.boldexplorer.shared.output.OutputPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OutputModule {
    // OutputPolicy/OutputManager live in :shared (no Android deps, no @Inject annotation),
    // so we provide them explicitly here, same pattern as AudioModule.provideAudioCueScheduler.
    @Provides
    @Singleton
    fun provideOutputPolicy(): OutputPolicy = DefaultOutputPolicy()

    @Provides
    @Singleton
    fun provideOutputManager(): OutputManager = OutputManager()
}
