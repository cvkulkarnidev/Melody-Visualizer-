package dev.cvkulkarnidev.melodyvisualizer.audio

import kotlin.math.sqrt

data class PitchDetection(
    val frequencyHz: Double,
    val confidence: Float,
)

data class FrameAnalysis(
    val detection: PitchDetection?,
    val level: Float,
)

/**
 * A lightweight YIN fundamental-frequency detector designed for live, monophonic voice.
 * The implementation intentionally runs on raw PCM so the first app version has no ML
 * runtime or network dependency.
 */
class YinPitchDetector(
    private val sampleRate: Int = 44_100,
    private val minimumFrequencyHz: Double = 55.0,
    private val maximumFrequencyHz: Double = 1_760.0,
    private val threshold: Double = 0.14,
    private val silenceRms: Double = 0.008,
) {
    fun analyse(samples: ShortArray, length: Int = samples.size): FrameAnalysis {
        if (length < 256) return FrameAnalysis(null, 0f)

        var mean = 0.0
        for (index in 0 until length) mean += samples[index].toDouble()
        mean /= length

        var squareSum = 0.0
        for (index in 0 until length) {
            val centered = (samples[index] - mean) / Short.MAX_VALUE
            squareSum += centered * centered
        }
        val rms = sqrt(squareSum / length)
        val normalizedLevel = ((rms - silenceRms) / 0.12).coerceIn(0.0, 1.0).toFloat()
        if (rms < silenceRms) return FrameAnalysis(null, normalizedLevel)

        val minimumLag = (sampleRate / maximumFrequencyHz).toInt().coerceAtLeast(2)
        val maximumLag = (sampleRate / minimumFrequencyHz)
            .toInt()
            .coerceAtMost(length / 2)
        if (maximumLag <= minimumLag) return FrameAnalysis(null, normalizedLevel)

        val difference = DoubleArray(maximumLag + 1)
        for (lag in 1..maximumLag) {
            var sum = 0.0
            val limit = length - lag
            var index = 0
            while (index < limit) {
                val delta = samples[index].toDouble() - samples[index + lag].toDouble()
                sum += delta * delta
                index++
            }
            difference[lag] = sum
        }

        val normalizedDifference = DoubleArray(maximumLag + 1) { 1.0 }
        var runningSum = 0.0
        for (lag in 1..maximumLag) {
            runningSum += difference[lag]
            normalizedDifference[lag] = if (runningSum == 0.0) {
                1.0
            } else {
                difference[lag] * lag / runningSum
            }
        }

        var selectedLag = -1
        var lag = minimumLag
        while (lag <= maximumLag) {
            if (normalizedDifference[lag] < threshold) {
                while (
                    lag + 1 <= maximumLag &&
                    normalizedDifference[lag + 1] < normalizedDifference[lag]
                ) {
                    lag++
                }
                selectedLag = lag
                break
            }
            lag++
        }

        if (selectedLag == -1) {
            selectedLag = (minimumLag..maximumLag)
                .minByOrNull { normalizedDifference[it] }
                ?: return FrameAnalysis(null, normalizedLevel)
            if (normalizedDifference[selectedLag] > 0.30) {
                return FrameAnalysis(null, normalizedLevel)
            }
        }

        val refinedLag = parabolicInterpolation(normalizedDifference, selectedLag, maximumLag)
        val frequency = sampleRate / refinedLag
        if (frequency !in minimumFrequencyHz..maximumFrequencyHz) {
            return FrameAnalysis(null, normalizedLevel)
        }

        val confidence = (1.0 - normalizedDifference[selectedLag]).coerceIn(0.0, 1.0).toFloat()
        return FrameAnalysis(
            detection = PitchDetection(frequencyHz = frequency, confidence = confidence),
            level = normalizedLevel,
        )
    }

    private fun parabolicInterpolation(values: DoubleArray, index: Int, maximumIndex: Int): Double {
        if (index <= 0 || index >= maximumIndex) return index.toDouble()
        val left = values[index - 1]
        val center = values[index]
        val right = values[index + 1]
        val denominator = 2.0 * (2.0 * center - right - left)
        if (denominator == 0.0) return index.toDouble()
        return index + (right - left) / denominator
    }
}
