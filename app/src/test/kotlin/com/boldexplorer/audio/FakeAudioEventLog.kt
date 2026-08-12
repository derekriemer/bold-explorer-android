package com.boldexplorer.audio

/** Records appends in memory so log writers can be tested without a Context or a file. */
class FakeAudioEventLog(
    private val onAppend: (AudioLogEntry) -> Unit,
) : AudioLogSink {
    override fun append(entry: AudioLogEntry) = onAppend(entry)
}
