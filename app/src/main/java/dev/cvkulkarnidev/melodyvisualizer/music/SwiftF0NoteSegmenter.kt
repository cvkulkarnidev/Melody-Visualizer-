package dev.cvkulkarnidev.melodyvisualizer.music

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Converts a complete F0 contour to discrete playable notes with global dynamic programming.
 *
 * A note has a fixed pitch and a start penalty. The decoder can therefore ignore isolated pitch
 * glitches, vibrato, consonants, and brief unvoiced frames instead of committing frame by frame.
 */
internal object SwiftF0NoteSegmenter {
    fun segment(
        contour: PitchContour,
        pitchHoldMillis: Double = 90.0,
        concertAHz: Double = CONCERT_A_HZ,
    ): List<DetectedNoteEvent> {
        require(pitchHoldMillis >= 0.0)
        require(concertAHz > 0.0)
        val frameCount = minOf(contour.pitchHz.size, contour.confidence.size, contour.loudnessDb.size)
        if (frameCount == 0) return emptyList()

        val midi = DoubleArray(frameCount)
        val weight = DoubleArray(frameCount)
        val candidates = sortedSetOf<Double>()
        for (frame in 0 until frameCount) {
            val pitch = contour.pitchHz[frame]
            val isValid = pitch.isFinite() && pitch > 0.0
            if (isValid) {
                midi[frame] = 69.0 + 12.0 * log2(pitch / concertAHz)
                weight[frame] = contour.confidence[frame].coerceIn(0f, 1f).toDouble()
                candidates += round(midi[frame] * 100.0) / 100.0
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val candidateMidi = candidates.toDoubleArray()
        val quietOrUnvoicedCost = observationCosts(contour, weight, frameCount)
        val noteCost = DoubleArray(candidateMidi.size) { Double.POSITIVE_INFINITY }
        val noteStart = IntArray(candidateMidi.size)
        val backPointer = IntArray(frameCount + 1)
        val selectedCandidate = IntArray(frameCount + 1) { -1 }
        val work = DoubleArray(candidateMidi.size)
        val startPenalty = pitchHoldMillis / contour.framePeriodMillis
        var bestGapCost = 0.0

        for (frame in 0 until frameCount) {
            val startCost = bestGapCost + startPenalty
            for (candidate in candidateMidi.indices) {
                if (startCost < noteCost[candidate]) {
                    noteCost[candidate] = startCost
                    noteStart[candidate] = frame
                }
                val pitchError = abs(candidateMidi[candidate] - midi[frame]).coerceAtMost(2.0)
                work[candidate] = pitchError * weight[frame] + quietOrUnvoicedCost[frame]
                noteCost[candidate] += work[candidate]
            }

            var bestCandidate = 0
            for (candidate in 1 until noteCost.size) {
                if (noteCost[candidate] < noteCost[bestCandidate]) bestCandidate = candidate
            }
            if (noteCost[bestCandidate] < bestGapCost) {
                bestGapCost = noteCost[bestCandidate]
                backPointer[frame + 1] = noteStart[bestCandidate]
                selectedCandidate[frame + 1] = bestCandidate
            } else {
                backPointer[frame + 1] = frame
            }
        }

        val reversed = mutableListOf<DetectedNoteEvent>()
        var end = frameCount
        while (end > 0) {
            val start = backPointer[end]
            val candidate = selectedCandidate[end]
            if (candidate >= 0) {
                val startMillis = (start * contour.framePeriodMillis).roundToInt().toLong()
                val endMillis = (end * contour.framePeriodMillis).roundToInt().toLong()
                val duration = endMillis - startMillis
                if (duration >= MINIMUM_NOTE_MILLIS) {
                    // The source grid may be detuned; MusicNote playback is then normalized to A440.
                    val tunedMidi = candidateMidi[candidate].roundToInt().coerceIn(0, 127)
                    reversed += DetectedNoteEvent(
                        note = MusicNote.fromMidi(tunedMidi),
                        startMillis = startMillis,
                        durationMillis = duration,
                        confidence = averageConfidence(contour.confidence, start, end),
                    )
                }
            }
            end = start
        }
        reversed.reverse()
        return normalize(reversed)
    }

    private fun observationCosts(
        contour: PitchContour,
        weight: DoubleArray,
        frameCount: Int,
    ): DoubleArray = DoubleArray(frameCount) { frame ->
        val confidence = weight[frame].coerceIn(0.01, 0.99)
        var leftPeak = Double.NEGATIVE_INFINITY
        var rightPeak = Double.NEGATIVE_INFINITY
        for (index in max(0, frame - 4)..frame) leftPeak = max(leftPeak, contour.loudnessDb[index])
        for (index in frame..min(frameCount - 1, frame + 4)) rightPeak = max(rightPeak, contour.loudnessDb[index])
        val quietPenalty = max(
            0.0,
            min(leftPeak, rightPeak) - contour.loudnessDb[frame] - THREE_DB,
        )
        -ln(confidence / (1.0 - confidence)) + quietPenalty
    }

    private fun averageConfidence(confidence: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var sum = 0.0
        for (frame in start until end.coerceAtMost(confidence.size)) sum += confidence[frame]
        return (sum / (end - start)).toFloat().coerceIn(0f, 1f)
    }

    private fun normalize(events: List<DetectedNoteEvent>): List<DetectedNoteEvent> {
        if (events.isEmpty()) return events
        val normalized = mutableListOf<DetectedNoteEvent>()
        for (event in events) {
            val previous = normalized.lastOrNull()
            if (previous != null && previous.note.midi == event.note.midi &&
                event.startMillis - previous.endMillis <= MERGE_GAP_MILLIS
            ) {
                val combinedDuration = event.endMillis - previous.startMillis
                val confidence = (
                    previous.confidence * previous.durationMillis +
                        event.confidence * event.durationMillis
                    ) / (previous.durationMillis + event.durationMillis).coerceAtLeast(1L)
                normalized[normalized.lastIndex] = previous.copy(
                    durationMillis = combinedDuration,
                    confidence = confidence,
                )
            } else {
                normalized += event
            }
        }
        return normalized
    }

    private const val CONCERT_A_HZ = 440.0
    private const val THREE_DB = 10.0 * 0.3010299956639812
    private const val MINIMUM_NOTE_MILLIS = 64L
    private const val MERGE_GAP_MILLIS = 96L
}
