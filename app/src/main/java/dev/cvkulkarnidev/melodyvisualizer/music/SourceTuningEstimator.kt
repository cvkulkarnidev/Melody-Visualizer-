package dev.cvkulkarnidev.melodyvisualizer.music

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

internal data class SourceTuningEstimate(
    val referenceAHz: Double,
    val offsetCents: Double,
    val confidence: Double,
    val isReliable: Boolean,
)

/** Estimates a recording's global tuning offset before notes are normalized to A4 = 440 Hz. */
internal object SourceTuningEstimator {
    fun estimate(contour: PitchContour): SourceTuningEstimate {
        val frameCount = minOf(contour.pitchHz.size, contour.confidence.size)
        var x = 0.0
        var y = 0.0
        var totalWeight = 0.0
        var voicedFrames = 0

        for (frame in 0 until frameCount) {
            val pitch = contour.pitchHz[frame]
            val confidence = contour.confidence[frame].toDouble()
            if (!pitch.isFinite() || pitch !in MIN_PITCH_HZ..MAX_PITCH_HZ || confidence < MIN_CONFIDENCE) {
                continue
            }
            val midiAtA440 = 69.0 + 12.0 * log2(pitch / STANDARD_A_HZ)
            val semitoneOffset = midiAtA440 - round(midiAtA440)
            val angle = 2.0 * PI * semitoneOffset
            val weight = confidence * confidence
            x += cos(angle) * weight
            y += sin(angle) * weight
            totalWeight += weight
            voicedFrames++
        }

        if (voicedFrames < MIN_VOICED_FRAMES || totalWeight == 0.0) return standardTuning()
        val concentration = (hypot(x, y) / totalWeight).coerceIn(0.0, 1.0)
        if (concentration < MIN_CONCENTRATION) return standardTuning(concentration)

        val offsetSemitones = atan2(y, x) / (2.0 * PI)
        val offsetCents = offsetSemitones * 100.0
        val referenceAHz = STANDARD_A_HZ * 2.0.pow(offsetSemitones / 12.0)
        return SourceTuningEstimate(
            referenceAHz = referenceAHz,
            offsetCents = offsetCents,
            confidence = concentration,
            isReliable = true,
        )
    }

    private fun standardTuning(confidence: Double = 0.0) = SourceTuningEstimate(
        referenceAHz = STANDARD_A_HZ,
        offsetCents = 0.0,
        confidence = confidence,
        isReliable = false,
    )

    private const val STANDARD_A_HZ = 440.0
    private const val MIN_PITCH_HZ = 55.0
    private const val MAX_PITCH_HZ = 1_760.0
    private const val MIN_CONFIDENCE = 0.62
    private const val MIN_VOICED_FRAMES = 12
    private const val MIN_CONCENTRATION = 0.58
}
