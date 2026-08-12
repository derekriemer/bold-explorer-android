package com.boldexplorer.di

import com.boldexplorer.audio.AudioEventLog
import com.boldexplorer.audio.AudioLogSink
import com.boldexplorer.shared.audio.AudioCueScheduler
import dagger.Binds
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

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioBindingsModule {
    @Binds
    abstract fun bindAudioLogSink(impl: AudioEventLog): AudioLogSink
}
