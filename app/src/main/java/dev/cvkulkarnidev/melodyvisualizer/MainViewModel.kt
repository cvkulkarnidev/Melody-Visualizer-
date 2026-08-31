package dev.cvkulkarnidev.melodyvisualizer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.cvkulkarnidev.melodyvisualizer.audio.PianoSynth
import dev.cvkulkarnidev.melodyvisualizer.audio.PitchMonitor
import dev.cvkulkarnidev.melodyvisualizer.music.MusicNote
import dev.cvkulkarnidev.melodyvisualizer.music.PitchSmoother
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PitchTrailPoint(
    val midi: Int,
    val timestampMillis: Long,
    val confidence: Float,
)

data class MelodyUiState(
    val isListening: Boolean = false,
    val note: MusicNote? = null,
    val frequencyHz: Double? = null,
    val cents: Int = 0,
    val confidence: Float = 0f,
    val inputLevel: Float = 0f,
    val pianoEnabled: Boolean = false,
    val trail: List<PitchTrailPoint> = emptyList(),
    val recentNotes: List<MusicNote> = emptyList(),
    val errorMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val pitchMonitor = PitchMonitor(application.applicationContext)
    private val pitchSmoother = PitchSmoother()
    private val pianoSynth = PianoSynth()

    private val _uiState = MutableStateFlow(MelodyUiState())
    val uiState: StateFlow<MelodyUiState> = _uiState.asStateFlow()

    private var lastPlayedMidi: Int? = null

    fun startListening() {
        if (_uiState.value.isListening) return
        pitchSmoother.reset()
        lastPlayedMidi = null
        _uiState.update { it.copy(isListening = true, errorMessage = null) }

        pitchMonitor.start(
            onAnalysis = { analysis ->
                val smoothed = pitchSmoother.update(analysis.detection)
                val now = System.currentTimeMillis()
                val previousState = _uiState.value

                if (smoothed != null && smoothed.confidence > 0f) {
                    val noteChanged = previousState.note?.midi != smoothed.note.midi
                    val nextRecentNotes = if (noteChanged) {
                        (previousState.recentNotes + smoothed.note).takeLast(MAX_RECENT_NOTES)
                    } else {
                        previousState.recentNotes
                    }
                    val nextTrail = (
                        previousState.trail.filter { now - it.timestampMillis <= TRAIL_WINDOW_MILLIS } +
                            PitchTrailPoint(smoothed.note.midi, now, smoothed.confidence)
                        ).takeLast(MAX_TRAIL_POINTS)

                    _uiState.value = previousState.copy(
                        note = smoothed.note,
                        frequencyHz = smoothed.frequencyHz,
                        cents = smoothed.cents,
                        confidence = smoothed.confidence,
                        inputLevel = analysis.level,
                        trail = nextTrail,
                        recentNotes = nextRecentNotes,
                    )

                    if (
                        previousState.pianoEnabled &&
                        noteChanged &&
                        lastPlayedMidi != smoothed.note.midi
                    ) {
                        pianoSynth.play(smoothed.note)
                        lastPlayedMidi = smoothed.note.midi
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            note = smoothed?.note,
                            frequencyHz = null,
                            cents = 0,
                            confidence = 0f,
                            inputLevel = analysis.level,
                            trail = it.trail.filter { point ->
                                now - point.timestampMillis <= TRAIL_WINDOW_MILLIS
                            },
                        )
                    }
                    if (smoothed == null) lastPlayedMidi = null
                }
            },
            onError = { message ->
                _uiState.update {
                    it.copy(isListening = false, note = null, errorMessage = message)
                }
            },
        )
    }

    fun stopListening() {
        pitchMonitor.stop()
        pianoSynth.stop()
        pitchSmoother.reset()
        lastPlayedMidi = null
        _uiState.update {
            it.copy(
                isListening = false,
                note = null,
                frequencyHz = null,
                cents = 0,
                confidence = 0f,
                inputLevel = 0f,
            )
        }
    }

    fun setPianoEnabled(enabled: Boolean) {
        _uiState.update { it.copy(pianoEnabled = enabled) }
        if (!enabled) pianoSynth.stop()
        lastPlayedMidi = if (enabled) null else lastPlayedMidi
    }

    fun playCurrentNote() {
        _uiState.value.note?.let(pianoSynth::play)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        pitchMonitor.release()
        pianoSynth.release()
        super.onCleared()
    }

    private companion object {
        const val TRAIL_WINDOW_MILLIS = 6_000L
        const val MAX_TRAIL_POINTS = 150
        const val MAX_RECENT_NOTES = 10
    }
}
