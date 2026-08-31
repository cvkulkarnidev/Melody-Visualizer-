package dev.cvkulkarnidev.melodyvisualizer.music

data class DetectedNoteEvent(
    val note: MusicNote,
    val startMillis: Long,
    val durationMillis: Long,
    val confidence: Float,
) {
    val endMillis: Long
        get() = startMillis + durationMillis
}
