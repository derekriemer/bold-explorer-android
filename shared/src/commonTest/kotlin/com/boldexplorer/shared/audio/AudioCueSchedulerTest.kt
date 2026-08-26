package com.boldexplorer.shared.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AudioCueSchedulerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun start_returnsCancellableJob() =
        runTest {
            val scheduler = AudioCueScheduler()
            val schedulerScope = TestScope(testScheduler)
            val job =
                scheduler.start(
                    scope = schedulerScope,
                    relativeDeg = MutableStateFlow<Double?>(null),
                    frequentCuesActive = MutableStateFlow(false),
                    beaconCuesEnabled = MutableStateFlow(true),
                )

            assertNotNull(job)
            job.cancel()
            advanceUntilIdle()
            assertFalse(job.isActive)
            schedulerScope.cancel()
        }
}
