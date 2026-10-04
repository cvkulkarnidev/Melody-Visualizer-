package dev.cvkulkarnidev.melodyvisualizer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Windowed-sinc sample-rate conversion for pitch analysis.
 *
 * Linear interpolation is fast, but downsampling 44.1/48 kHz audio to 16 kHz without a
 * low-pass filter aliases upper harmonics back into the vocal range. Those aliases are a common
 * source of octave and semitone errors, so the accuracy path uses a small band-limited kernel.
 */
internal object BandLimitedResampler {
    fun toFloat(
        input: ShortArray,
        sourceRate: Int,
        targetRate: Int,
        halfKernel: Int = 20,
    ): FloatArray {
        require(sourceRate > 0 && targetRate > 0)
        require(halfKernel >= 4)
        if (input.isEmpty()) return FloatArray(0)

        val normalized = FloatArray(input.size) { input[it] / 32768f }
        val inputPeak = normalized.maxOfOrNull { abs(it) } ?: 0f
        if (sourceRate == targetRate) return normalizeQuietSignal(normalized, inputPeak)

        val outputSize = (input.size.toLong() * targetRate / sourceRate)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val sourceStep = sourceRate.toDouble() / targetRate
        // Leave a small transition band below the target Nyquist frequency.
        val cutoff = minOf(1.0, targetRate.toDouble() / sourceRate) * 0.94
        val output = FloatArray(outputSize)

        for (outputIndex in output.indices) {
            val sourcePosition = outputIndex * sourceStep
            val center = sourcePosition.roundToInt()
            var weighted = 0.0
            var weightSum = 0.0
            val first = maxOf(0, center - halfKernel + 1)
            val last = minOf(input.lastIndex, center + halfKernel)

            for (sourceIndex in first..last) {
                val distance = sourcePosition - sourceIndex
                val normalizedDistance = abs(distance) / halfKernel
                if (normalizedDistance >= 1.0) continue
                val window = 0.42 +
                    0.5 * cos(PI * normalizedDistance) +
                    0.08 * cos(2.0 * PI * normalizedDistance)
                val argument = PI * cutoff * distance
                val sinc = if (abs(argument) < 1e-9) 1.0 else sin(argument) / argument
                val weight = cutoff * sinc * window
                weighted += normalized[sourceIndex] * weight
                weightSum += weight
            }
            output[outputIndex] = if (abs(weightSum) > 1e-9) {
                (weighted / weightSum).toFloat().coerceIn(-1f, 1f)
            } else {
                0f
            }
        }
        return normalizeQuietSignal(output, inputPeak)
    }

    /** SwiftF0 is level-sensitive only for exceptionally quiet recordings. */
    private fun normalizeQuietSignal(signal: FloatArray, sourcePeak: Float): FloatArray {
        if (sourcePeak <= 0f || sourcePeak >= QUIET_PEAK) return signal
        val gain = (NORMALIZED_PEAK / sourcePeak).coerceAtMost(MAX_GAIN)
        for (index in signal.indices) signal[index] = (signal[index] * gain).coerceIn(-1f, 1f)
        return signal
    }

    private const val QUIET_PEAK = 0.0178f // approximately -35 dBFS
    private const val NORMALIZED_PEAK = 0.5f
    private const val MAX_GAIN = 24f
}
