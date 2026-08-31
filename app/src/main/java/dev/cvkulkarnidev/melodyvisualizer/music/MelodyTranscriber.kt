package dev.cvkulkarnidev.melodyvisualizer.music

import dev.cvkulkarnidev.melodyvisualizer.audio.YinPitchDetector

/** Converts an entire monophonic PCM recording into cleaned, timed piano-note events. */
class MelodyTranscriber(
    sampleRate: Int = 44_100,
    private val frameSize: Int = 2_048,
    private val hopSize: Int = 1_024,
) {
    private val detector = YinPitchDetector(sampleRate = sampleRate)
    private val sampleRate = sampleRate

    fun transcribe(
        samples: ShortArray,
        onProgress: (Float) -> Unit = {},
    ): List<DetectedNoteEvent> {
        if (samples.size < frameSize) return emptyList()

        val smoother = PitchSmoother(windowSize = 5, minimumConfidence = 0.70f)
        val frame = ShortArray(frameSize)
        val rawEvents = mutableListOf<DetectedNoteEvent>()
        var activeMidi: Int? = null
        var activeStartMillis = 0L
        var lastVoicedMillis = 0L
        var confidenceSum = 0f
        var confidenceFrames = 0
        val totalFrames = ((samples.size - frameSize) / hopSize + 1).coerceAtLeast(1)
        var frameIndex = 0
        var offset = 0

        fun finishActive(endMillis: Long) {
            val midi = activeMidi ?: return
            val duration = (endMillis - activeStartMillis).coerceAtLeast(0L)
            if (duration >= MINIMUM_NOTE_MILLIS) {
                rawEvents += DetectedNoteEvent(
                    note = MusicNote.fromMidi(midi),
                    startMillis = activeStartMillis,
                    durationMillis = duration,
                    confidence = if (confidenceFrames == 0) 0f else confidenceSum / confidenceFrames,
                )
            }
            activeMidi = null
            confidenceSum = 0f
            confidenceFrames = 0
        }

        while (offset + frameSize <= samples.size) {
            samples.copyInto(frame, destinationOffset = 0, startIndex = offset, endIndex = offset + frameSize)
            val timeMillis = offset * 1_000L / sampleRate
            val analysis = detector.analyse(frame)
            val pitch = smoother.update(analysis.detection)

            if (pitch != null && pitch.confidence > 0f) {
                if (activeMidi == null) {
                    activeMidi = pitch.note.midi
                    activeStartMillis = timeMillis
                } else if (activeMidi != pitch.note.midi) {
                    finishActive(timeMillis)
                    activeMidi = pitch.note.midi
                    activeStartMillis = timeMillis
                }
                lastVoicedMillis = timeMillis + hopSize * 1_000L / sampleRate
                confidenceSum += pitch.confidence
                confidenceFrames++
            } else if (pitch == null) {
                finishActive(lastVoicedMillis)
            }

            frameIndex++
            if (frameIndex % 8 == 0 || frameIndex == totalFrames) {
                onProgress(frameIndex.toFloat() / totalFrames)
            }
            offset += hopSize
        }
        finishActive(lastVoicedMillis)
        return mergeNearbyMatchingNotes(rawEvents)
    }

    private fun mergeNearbyMatchingNotes(events: List<DetectedNoteEvent>): List<DetectedNoteEvent> {
        if (events.isEmpty()) return events
        val merged = mutableListOf(events.first())
        for (event in events.drop(1)) {
            val previous = merged.last()
            val gap = event.startMillis - previous.endMillis
            if (event.note.midi == previous.note.midi && gap <= MERGE_GAP_MILLIS) {
                val previousWeight = previous.durationMillis.coerceAtLeast(1L)
                val eventWeight = event.durationMillis.coerceAtLeast(1L)
                merged[merged.lastIndex] = previous.copy(
                    durationMillis = event.endMillis - previous.startMillis,
                    confidence = (
                        previous.confidence * previousWeight + event.confidence * eventWeight
                        ) / (previousWeight + eventWeight),
                )
            } else {
                merged += event
            }
        }
        return merged
    }

    private companion object {
        const val MINIMUM_NOTE_MILLIS = 115L
        const val MERGE_GAP_MILLIS = 160L
    }
}
