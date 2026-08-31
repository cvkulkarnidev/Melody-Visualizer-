package dev.cvkulkarnidev.melodyvisualizer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridPitchReconcilerTest {
    @Test
    fun `corrects a weak neural octave error with strong acoustic evidence`() {
        val neural = event(midi = 69, start = 100, duration = 500, confidence = 0.52f)
        val acoustic = event(midi = 57, start = 120, duration = 450, confidence = 0.96f)

        val result = HybridPitchReconciler.reconcile(listOf(neural), listOf(acoustic))

        assertEquals(1, result.size)
        assertEquals(57, result.single().note.midi)
        assertTrue(result.single().confidence > neural.confidence)
    }

    @Test
    fun `keeps confident neural pitch when acoustic tracker disagrees`() {
        val neural = event(midi = 64, start = 0, duration = 600, confidence = 0.91f)
        val acoustic = event(midi = 65, start = 0, duration = 600, confidence = 0.82f)

        val result = HybridPitchReconciler.reconcile(listOf(neural), listOf(acoustic))

        assertEquals(64, result.single().note.midi)
    }

    @Test
    fun `recovers a clear acoustic note missed by neural model`() {
        val neural = event(midi = 60, start = 0, duration = 350, confidence = 0.8f)
        val acoustic = event(midi = 67, start = 600, duration = 300, confidence = 0.9f)

        val result = HybridPitchReconciler.reconcile(listOf(neural), listOf(acoustic))

        assertEquals(listOf(60, 67), result.map { it.note.midi })
    }

    private fun event(midi: Int, start: Long, duration: Long, confidence: Float) =
        DetectedNoteEvent(
            note = MusicNote.fromMidi(midi),
            startMillis = start,
            durationMillis = duration,
            confidence = confidence,
        )
}
