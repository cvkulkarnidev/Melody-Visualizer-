package dev.cvkulkarnidev.melodyvisualizer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class YinPitchDetectorTest {
    private val detector = YinPitchDetector(sampleRate = SAMPLE_RATE)

    @Test
    fun `detects concert A`() {
        val analysis = detector.analyse(sineWave(440.0))

        assertNotNull(analysis.detection)
        assertEquals(440.0, analysis.detection!!.frequencyHz, 1.0)
    }

    @Test
    fun `detects middle C`() {
        val analysis = detector.analyse(sineWave(261.63))

        assertNotNull(analysis.detection)
        assertEquals(261.63, analysis.detection!!.frequencyHz, 1.0)
    }

    @Test
    fun `rejects silence`() {
        val analysis = detector.analyse(ShortArray(FRAME_SIZE))

        assertNull(analysis.detection)
        assertEquals(0f, analysis.level, 0.001f)
    }

    private fun sineWave(frequencyHz: Double): ShortArray = ShortArray(FRAME_SIZE) { index ->
        (sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE) * Short.MAX_VALUE * 0.55)
            .toInt()
            .toShort()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FRAME_SIZE = 2_048
    }
}
