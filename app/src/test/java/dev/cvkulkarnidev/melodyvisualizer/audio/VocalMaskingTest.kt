package dev.cvkulkarnidev.melodyvisualizer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VocalMaskingTest {
    @Test
    fun maskUsesPredictedToMixtureRatioAndClampsIt() {
        assertEquals(0.5f, VocalMasking.mask(2f, 4f), 0.0001f)
        assertEquals(1f, VocalMasking.mask(6f, 4f), 0.0001f)
        assertEquals(0f, VocalMasking.mask(1f, 0f), 0.0001f)
    }
}
