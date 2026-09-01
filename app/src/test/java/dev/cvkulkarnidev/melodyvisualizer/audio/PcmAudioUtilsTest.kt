package dev.cvkulkarnidev.melodyvisualizer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioUtilsTest {
    @Test
    fun stereoChannelsAreAveragedWithoutOverflow() {
        val mono = PcmChannelMixer.mono(
            shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 1_000),
            shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, -1_000),
        )

        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0), mono)
    }

    @Test
    fun linearResamplingUsesExpectedLength() {
        val result = PcmResampler.linear(ShortArray(44_100), 44_100, 48_000)

        assertEquals(48_000, result.size)
    }
}
