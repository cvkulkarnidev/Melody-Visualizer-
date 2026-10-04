package dev.cvkulkarnidev.melodyvisualizer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class SourceTuningEstimatorTest {
    @Test
    fun `consistently sharp melody estimates its source tuning`() {
        val sourceA = 448.2
        val midi = intArrayOf(69, 71, 73, 74, 76)
        val contour = contour(100) { frame ->
            sourceA * 2.0.pow((midi[(frame / 20).coerceAtMost(midi.lastIndex)] - 69) / 12.0)
        }

        val estimate = SourceTuningEstimator.estimate(contour)

        assertTrue(estimate.isReliable)
        assertEquals(sourceA, estimate.referenceAHz, 0.35)
        assertTrue(estimate.offsetCents in 31.0..33.5)
    }

    @Test
    fun `detuned source is labeled correctly before A440 playback normalization`() {
        val sourceA = 432.0
        val contour = contour(90) { frame ->
            when {
                frame < 30 -> sourceA
                frame < 60 -> sourceA * 2.0.pow(2.0 / 12.0)
                else -> sourceA * 2.0.pow(4.0 / 12.0)
            }
        }
        val tuning = SourceTuningEstimator.estimate(contour)

        val notes = SwiftF0NoteSegmenter.segment(contour, concertAHz = tuning.referenceAHz)

        assertEquals(listOf("A4", "B4", "C♯5"), notes.map { it.note.name })
        assertEquals(440.0, notes.first().note.frequencyHz, 0.001)
    }

    @Test
    fun `uncertain contour falls back to standard tuning`() {
        val pitch = DoubleArray(40) { frame -> 220.0 * 2.0.pow((frame % 12) / 12.0) }
        val contour = PitchContour(
            pitchHz = pitch,
            confidence = FloatArray(40) { 0.3f },
            loudnessDb = DoubleArray(40) { -20.0 },
            framePeriodMillis = 16.0,
        )

        val estimate = SourceTuningEstimator.estimate(contour)

        assertTrue(!estimate.isReliable)
        assertEquals(440.0, estimate.referenceAHz, 0.001)
    }

    private fun contour(frames: Int, pitchAt: (Int) -> Double) = PitchContour(
        pitchHz = DoubleArray(frames, pitchAt),
        confidence = FloatArray(frames) { 0.96f },
        loudnessDb = DoubleArray(frames) { -14.0 },
        framePeriodMillis = 16.0,
    )
}
