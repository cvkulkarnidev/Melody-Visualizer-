package dev.cvkulkarnidev.melodyvisualizer.audio

import dev.cvkulkarnidev.melodyvisualizer.music.DetectedNoteEvent
import dev.cvkulkarnidev.melodyvisualizer.music.MusicNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.math.abs

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

    @Test
    fun `harmonium vibrato stays bounded during long notes`() {
        val samples = InstrumentWaveformGenerator.synthesize(440.0, 5_000L, InstrumentSound.Harmonium)
        val detector = YinPitchDetector(sampleRate = InstrumentWaveformGenerator.SAMPLE_RATE)
        val early = detector.analyse(samples.sliceArray(20_000 until 22_048)).detection
        val late = detector.analyse(samples.sliceArray(198_000 until 200_048)).detection

        assertTrue(early != null)
        assertTrue(late != null)
        assertTrue(abs(early!!.frequencyHz - 440.0) < 5.0)
        assertTrue(abs(late!!.frequencyHz - 440.0) < 5.0)
        assertTrue(abs(early.frequencyHz - late.frequencyHz) < 6.0)
    }

    @Test
    fun `short gaps are held for smooth legato`() {
        val notes = listOf(
            event(midi = 60, startMillis = 0L, durationMillis = 240L),
            event(midi = 64, startMillis = 350L, durationMillis = 300L),
        )

        assertEquals(350L, InstrumentWaveformGenerator.effectiveHeldDurationMillis(notes, 0))
        assertEquals(300L, InstrumentWaveformGenerator.effectiveHeldDurationMillis(notes, 1))
    }

    @Test
    fun `long intentional rests are not filled`() {
        val notes = listOf(
            event(midi = 60, startMillis = 0L, durationMillis = 200L),
            event(midi = 67, startMillis = 600L, durationMillis = 250L),
        )

        assertEquals(200L, InstrumentWaveformGenerator.effectiveHeldDurationMillis(notes, 0))
        assertEquals(133L, InstrumentWaveformGenerator.effectiveReleaseMillis(notes, 0, InstrumentSound.Piano))
        val samples = InstrumentWaveformGenerator.synthesizeSequence(notes, InstrumentSound.Piano)
        val silenceStart = (
            (InstrumentWaveformGenerator.SEQUENCE_LEAD_IN_MILLIS + 350L) *
                InstrumentWaveformGenerator.SAMPLE_RATE / 1_000L
            ).toInt()
        val silenceEnd = (
            (InstrumentWaveformGenerator.SEQUENCE_LEAD_IN_MILLIS + 560L) *
                InstrumentWaveformGenerator.SAMPLE_RATE / 1_000L
            ).toInt()
        assertTrue(samples.sliceArray(silenceStart until silenceEnd).all { it.toInt() == 0 })
    }

    @Test
    fun `complete sequence is rendered as one continuous click safe buffer`() {
        val notes = listOf(
            event(midi = 60, startMillis = 0L, durationMillis = 300L),
            event(midi = 64, startMillis = 300L, durationMillis = 300L),
            event(midi = 67, startMillis = 600L, durationMillis = 300L),
        )

        val samples = InstrumentWaveformGenerator.synthesizeSequence(notes, InstrumentSound.Harmonium)

        assertTrue(samples.isNotEmpty())
        assertEquals(0, samples.first().toInt())
        assertTrue(kotlin.math.abs(samples.last().toInt()) < 4)
        for (onsetMillis in listOf(300L, 600L)) {
            val onset = (
                (onsetMillis + InstrumentWaveformGenerator.SEQUENCE_LEAD_IN_MILLIS) *
                    InstrumentWaveformGenerator.SAMPLE_RATE / 1_000L
                ).toInt()
            val discontinuity = kotlin.math.abs(samples[onset].toInt() - samples[onset - 1].toInt())
            assertTrue("Unexpected onset discontinuity: $discontinuity", discontinuity < 6_000)
        }
    }

    private fun event(midi: Int, startMillis: Long, durationMillis: Long) = DetectedNoteEvent(
        note = MusicNote.fromMidi(midi),
        startMillis = startMillis,
        durationMillis = durationMillis,
        confidence = 0.95f,
    )

    private fun rms(samples: ShortArray, fromMillis: Int, toMillis: Int): Double {
        val start = fromMillis * InstrumentWaveformGenerator.SAMPLE_RATE / 1_000
        val end = toMillis * InstrumentWaveformGenerator.SAMPLE_RATE / 1_000
        val meanSquare = samples.sliceArray(start until end)
            .map { it.toDouble() * it }
            .average()
        return sqrt(meanSquare)
    }
}
