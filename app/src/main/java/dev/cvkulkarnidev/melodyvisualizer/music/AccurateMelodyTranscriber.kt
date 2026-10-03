package dev.cvkulkarnidev.melodyvisualizer.music

import android.content.Context
import kotlin.math.abs
import kotlin.math.log2

/** High-accuracy batch melody path: neural F0 contour -> global notes -> A440 tuning. */
class AccurateMelodyTranscriber(context: Context) : AutoCloseable {
    private val pitchTracker = SwiftF0PitchTracker(context)

    @Synchronized
    fun transcribe(
        samples: ShortArray,
        sampleRate: Int,
        onProgress: (Float) -> Unit = {},
    ): List<DetectedNoteEvent> = transcribeCandidates(listOf(samples), sampleRate, onProgress)

    /** Chooses between the original/separated signal and the denoised version by contour quality. */
    @Synchronized
    fun transcribeCandidates(
        candidates: List<ShortArray>,
        sampleRate: Int,
        onProgress: (Float) -> Unit = {},
    ): List<DetectedNoteEvent> {
        val usable = candidates.filter { it.isNotEmpty() }.distinctBy { System.identityHashCode(it) }
        if (usable.isEmpty()) return emptyList()

        var best: CandidateResult? = null
        usable.forEachIndexed { index, samples ->
            val contour = pitchTracker.detect(samples, sampleRate) { progress ->
                onProgress((index + progress * 0.88f) / usable.size)
            }
            val notes = SwiftF0NoteSegmenter.segment(
                contour = contour,
                pitchHoldMillis = 90.0,
                concertAHz = 440.0,
            )
            val result = CandidateResult(notes, contourQuality(contour, notes))
            if (best == null || result.quality > best!!.quality) best = result
            onProgress((index + 1f) / usable.size)
        }
        onProgress(1f)
        return best?.notes.orEmpty()
    }

    private fun contourQuality(contour: PitchContour, notes: List<DetectedNoteEvent>): Double {
        if (notes.isEmpty()) return Double.NEGATIVE_INFINITY
        var voicedFrames = 0
        var confidenceSum = 0.0
        var continuityPenalty = 0.0
        var previousMidi: Double? = null
        for (frame in contour.pitchHz.indices) {
            val confidence = contour.confidence.getOrElse(frame) { 0f }
            val pitch = contour.pitchHz[frame]
            if (confidence < 0.5f || !pitch.isFinite() || pitch <= 0.0) continue
            voicedFrames++
            confidenceSum += confidence
            val midi = 69.0 + 12.0 * log2(pitch / 440.0)
            previousMidi?.let { previous ->
                val jump = abs(midi - previous)
                if (jump > 4.0) continuityPenalty += (jump - 4.0).coerceAtMost(12.0)
            }
            previousMidi = midi
        }
        if (voicedFrames == 0) return Double.NEGATIVE_INFINITY
        val meanConfidence = confidenceSum / voicedFrames
        val jumpPenalty = continuityPenalty / voicedFrames
        val shortNoteRatio = notes.count { it.durationMillis < 100L }.toDouble() / notes.size
        return meanConfidence - jumpPenalty * 0.025 - shortNoteRatio * 0.10
    }

    private data class CandidateResult(
        val notes: List<DetectedNoteEvent>,
        val quality: Double,
    )

    override fun close() = pitchTracker.close()
}
