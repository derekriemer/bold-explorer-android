package com.boldexplorer.di

import com.boldexplorer.shared.audio.AudioCueScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    // AudioCueScheduler lives in :shared (no Android deps, no @Inject annotation),
    // so we provide it explicitly here with the default config.
    @Provides
    @Singleton
    fun provideAudioCueScheduler(): AudioCueScheduler = AudioCueScheduler()
}
