package dev.cvkulkarnidev.melodyvisualizer.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class BandLimitedResamplerTest {
    @Test
    fun `preserves concert A when converting to model rate`() {
        val source = tone(440.0, 0.8)

        val output = BandLimitedResampler.toFloat(source, SOURCE_RATE, 16_000)
        val crossings = (1 until output.size).count { output[it - 1] <= 0f && output[it] > 0f }
        val estimatedFrequency = crossings / (output.size / 16_000.0)

        assertTrue("Estimated frequency was $estimatedFrequency", abs(estimatedFrequency - 440.0) < 2.0)
    }

    @Test
    fun `attenuates content above target Nyquist instead of aliasing it`() {
        val source = tone(12_000.0, 0.5)

        val output = BandLimitedResampler.toFloat(source, SOURCE_RATE, 16_000)
        val rms = sqrt(output.drop(100).map { it * it }.average())

        assertTrue("Aliased RMS was $rms", rms < 0.08)
    }

    private fun tone(frequency: Double, seconds: Double): ShortArray {
        val count = (seconds * SOURCE_RATE).toInt()
        return ShortArray(count) { index ->
            (sin(2.0 * PI * frequency * index / SOURCE_RATE) * Short.MAX_VALUE * 0.65)
                .toInt()
                .toShort()
        }
    }

    private companion object {
        const val SOURCE_RATE = 44_100
    }
}
