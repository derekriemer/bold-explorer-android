package com.boldexplorer.shared.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
                    accuracyM = MutableStateFlow<Double?>(null),
                    relativeDeg = MutableStateFlow<Double?>(null),
                    alignmentActive = MutableStateFlow(false),
                    beaconCuesEnabled = MutableStateFlow(true),
                )

            assertNotNull(job)
            job.cancel()
            advanceUntilIdle()
            assertFalse(job.isActive)
            schedulerScope.cancel()
        }

    @Test
    fun emitProgress_isGatedOnBeaconCuesEnabled() =
        runTest {
            // Every other periodic earcon (directional beacon, accuracy beacon, alignment ping) is
            // gated on this same switch; without it, absolute silence is the only way to stop a beep
            // that would otherwise run every ~5 s for the entire walk.
            val scheduler = AudioCueScheduler()
            val received = mutableListOf<AudioCueEvent>()
            val job = launch { scheduler.events.collect { received += it } }

            scheduler.emitProgress(lost = false, beaconCuesEnabled = false)
            advanceUntilIdle()
            assertTrue(received.isEmpty(), "beacon cues off must silence the progress beep too")

            scheduler.emitProgress(lost = true, beaconCuesEnabled = true)
            advanceUntilIdle()
            assertEquals(listOf<AudioCueEvent>(AudioCueEvent.Progress(true)), received)

            job.cancel()
        }
}
