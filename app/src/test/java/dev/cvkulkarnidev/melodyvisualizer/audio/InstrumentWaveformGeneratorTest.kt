package dev.cvkulkarnidev.melodyvisualizer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class InstrumentWaveformGeneratorTest {
    @Test
    fun `waveform contains held note and release tail`() {
        val duration = 1_000L
        val samples = InstrumentWaveformGenerator.synthesize(440.0, duration, InstrumentSound.Piano)
        val expectedMillis = duration + InstrumentWaveformGenerator.releaseMillis(InstrumentSound.Piano)

        assertEquals(
            (InstrumentWaveformGenerator.SAMPLE_RATE * expectedMillis / 1_000L).toInt(),
            samples.size,
        )
        assertTrue(samples.last().toInt() == 0 || kotlin.math.abs(samples.last().toInt()) < 4)
    }

    @Test
    fun `harmonium remains sustained through a held note`() {
        val samples = InstrumentWaveformGenerator.synthesize(261.63, 1_200L, InstrumentSound.Harmonium)
        val early = rms(samples, 200, 300)
        val late = rms(samples, 900, 1_000)

        assertTrue("Expected a steady harmonium envelope: early=$early late=$late", late > early * 0.82)
    }

    private fun rms(samples: ShortArray, fromMillis: Int, toMillis: Int): Double {
        val start = fromMillis * InstrumentWaveformGenerator.SAMPLE_RATE / 1_000
        val end = toMillis * InstrumentWaveformGenerator.SAMPLE_RATE / 1_000
        val meanSquare = samples.sliceArray(start until end)
            .map { it.toDouble() * it }
            .average()
        return sqrt(meanSquare)
    }
}
