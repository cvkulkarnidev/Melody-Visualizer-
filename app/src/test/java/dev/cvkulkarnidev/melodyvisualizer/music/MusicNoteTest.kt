package dev.cvkulkarnidev.melodyvisualizer.music

import dev.cvkulkarnidev.melodyvisualizer.audio.PitchDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MusicNoteTest {
    @Test
    fun `maps standard frequencies to piano note names`() {
        assertEquals("A4", MusicNote.fromFrequency(440.0).name)
        assertEquals("C4", MusicNote.fromFrequency(261.63).name)
        assertEquals("C♯4", MusicNote.fromFrequency(277.18).name)
    }

    @Test
    fun `smoother holds a note through small pitch movement`() {
        val smoother = PitchSmoother(windowSize = 3)

        val first = smoother.update(PitchDetection(440.0, 0.95f))
        val second = smoother.update(PitchDetection(447.0, 0.95f))

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(69, first!!.note.midi)
        assertEquals(69, second!!.note.midi)
    }

    @Test
    fun `smoother clears stale note after four invalid frames`() {
        val smoother = PitchSmoother()
        smoother.update(PitchDetection(440.0, 0.95f))

        repeat(3) { assertNotNull(smoother.update(null)) }
        assertNull(smoother.update(null))
    }
}
