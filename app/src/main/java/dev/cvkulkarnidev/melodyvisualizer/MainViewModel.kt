package dev.cvkulkarnidev.melodyvisualizer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cvkulkarnidev.melodyvisualizer.audio.AudioFileDecoder
import dev.cvkulkarnidev.melodyvisualizer.audio.AudioPreprocessor
import dev.cvkulkarnidev.melodyvisualizer.audio.HummingRecorder
import dev.cvkulkarnidev.melodyvisualizer.audio.InstrumentSound
import dev.cvkulkarnidev.melodyvisualizer.audio.PianoSynth
import dev.cvkulkarnidev.melodyvisualizer.music.DetectedNoteEvent
import dev.cvkulkarnidev.melodyvisualizer.music.HybridMelodyTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AnalysisStage {
    Idle,
    Decoding,
    Separating,
    Cleaning,
    Transcribing,
    Complete,
    Error,
}

data class MelodyUiState(
    val isRecording: Boolean = false,
    val recordingDurationMillis: Long = 0L,
    val recordingLevel: Float = 0f,
    val stage: AnalysisStage = AnalysisStage.Idle,
    val progress: Float = 0f,
    val fileName: String? = null,
    val audioDurationMillis: Long = 0L,
    val notes: List<DetectedNoteEvent> = emptyList(),
    val selectedNoteIndex: Int? = null,
    val isPlaying: Boolean = false,
    val instrument: InstrumentSound = InstrumentSound.Piano,
    val vocalIsolationApplied: Boolean = false,
    val noiseReductionApplied: Boolean = false,
    val processingWarning: String? = null,
    val errorMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val recorder = HummingRecorder(appContext)
    private val decoder = AudioFileDecoder(appContext)
    private val preprocessor = AudioPreprocessor(appContext)
    private val transcriber = HybridMelodyTranscriber(appContext)
    private val pianoSynth = PianoSynth()

    private val _uiState = MutableStateFlow(MelodyUiState())
    val uiState: StateFlow<MelodyUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var analysisJob: Job? = null
    private var recordingStartedAt = 0L

    fun startRecording() {
        if (_uiState.value.isRecording) return
        analysisJob?.cancel()
        pianoSynth.stop()
        runCatching { recorder.start() }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        stage = AnalysisStage.Error,
                        errorMessage = error.message ?: "The recording could not be started.",
                    )
                }
                return
            }

        recordingStartedAt = System.currentTimeMillis()
        _uiState.value = MelodyUiState(isRecording = true)
        recordingTimerJob = viewModelScope.launch {
            while (isActive && _uiState.value.isRecording) {
                _uiState.update {
                    it.copy(
                        recordingDurationMillis = System.currentTimeMillis() - recordingStartedAt,
                        recordingLevel = recorder.currentLevel(),
                    )
                }
                delay(60L)
            }
        }
    }

    fun finishRecordingAndAnalyze() {
        if (!_uiState.value.isRecording) return
        recordingTimerJob?.cancel()
        val duration = System.currentTimeMillis() - recordingStartedAt
        if (duration < MINIMUM_RECORDING_MILLIS) {
            recorder.cancel()
            _uiState.update {
                it.copy(
                    isRecording = false,
                    recordingDurationMillis = 0L,
                    recordingLevel = 0f,
                    stage = AnalysisStage.Error,
                    errorMessage = "Please record for at least one second.",
                )
            }
            return
        }

        runCatching { recorder.stop() }
            .onSuccess { recording ->
                _uiState.update { it.copy(isRecording = false, recordingLevel = 0f) }
                analyzeAudio(recording.uri, recording.displayName, isolateVocals = false)
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        recordingLevel = 0f,
                        stage = AnalysisStage.Error,
                        errorMessage = error.message ?: "The recording could not be completed.",
                    )
                }
            }
    }

    fun cancelRecording() {
        recordingTimerJob?.cancel()
        recorder.cancel()
        _uiState.value = MelodyUiState()
    }

    fun analyzeUploadedAudio(uri: Uri) {
        analyzeAudio(uri, resolveDisplayName(uri), isolateVocals = true)
    }

    fun selectNote(index: Int) {
        val note = _uiState.value.notes.getOrNull(index) ?: return
        pianoSynth.play(
            note = note.note,
            instrument = _uiState.value.instrument,
            durationMillis = note.durationMillis,
        )
        _uiState.update { it.copy(selectedNoteIndex = index) }
    }

    fun selectInstrument(instrument: InstrumentSound) {
        pianoSynth.stop()
        _uiState.update { it.copy(instrument = instrument, isPlaying = false) }
    }

    fun playMelody() {
        val notes = _uiState.value.notes
        if (notes.isEmpty()) return
        _uiState.update { it.copy(isPlaying = true, selectedNoteIndex = 0) }
        pianoSynth.playSequence(
            notes = notes,
            instrument = _uiState.value.instrument,
            onNote = { index ->
                _uiState.update { it.copy(isPlaying = true, selectedNoteIndex = index) }
            },
            onComplete = {
                _uiState.update { it.copy(isPlaying = false) }
            },
        )
    }

    fun stopPlayback() {
        pianoSynth.stop()
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun reset() {
        analysisJob?.cancel()
        recordingTimerJob?.cancel()
        recorder.cancel()
        pianoSynth.stop()
        _uiState.value = MelodyUiState()
    }

    fun clearError() {
        _uiState.update {
            it.copy(
                stage = if (it.notes.isEmpty()) AnalysisStage.Idle else AnalysisStage.Complete,
                errorMessage = null,
            )
        }
    }

    private fun analyzeAudio(uri: Uri, displayName: String, isolateVocals: Boolean) {
        analysisJob?.cancel()
        pianoSynth.stop()
        analysisJob = viewModelScope.launch {
            _uiState.value = MelodyUiState(
                stage = AnalysisStage.Decoding,
                fileName = displayName,
                progress = 0.02f,
            )
            runCatching {
                val decodeEnd = if (isolateVocals) 0.15f else 0.24f
                val decoded = withContext(Dispatchers.IO) {
                    decoder.decode(uri) { progress ->
                        _uiState.update {
                            it.copy(
                                stage = AnalysisStage.Decoding,
                                progress = (progress / 0.5f).coerceIn(0f, 1f) * decodeEnd,
                            )
                        }
                    }
                }
                val cleaned = withContext(Dispatchers.Default) {
                    preprocessor.process(
                        audio = decoded,
                        isolateVocals = isolateVocals,
                        onSeparatingProgress = { progress ->
                            _uiState.update {
                                it.copy(
                                    stage = AnalysisStage.Separating,
                                    progress = decodeEnd + progress.coerceIn(0f, 1f) * 0.43f,
                                    audioDurationMillis = decoded.durationMillis,
                                )
                            }
                        },
                        onCleaningProgress = { progress ->
                            val cleaningStart = if (isolateVocals) 0.58f else decodeEnd
                            val cleaningEnd = if (isolateVocals) 0.70f else 0.45f
                            _uiState.update {
                                it.copy(
                                    stage = AnalysisStage.Cleaning,
                                    progress = cleaningStart +
                                        progress.coerceIn(0f, 1f) * (cleaningEnd - cleaningStart),
                                    audioDurationMillis = decoded.durationMillis,
                                )
                            }
                        },
                    )
                }
                val transcribeStart = if (isolateVocals) 0.70f else 0.45f
                _uiState.update {
                    it.copy(
                        stage = AnalysisStage.Transcribing,
                        progress = transcribeStart,
                        audioDurationMillis = decoded.durationMillis,
                    )
                }
                val notes = withContext(Dispatchers.Default) {
                    transcriber.transcribe(cleaned.samples, decoded.sampleRate) { progress ->
                        _uiState.update {
                            it.copy(
                                stage = AnalysisStage.Transcribing,
                                progress = transcribeStart +
                                    progress.coerceIn(0f, 1f) * (1f - transcribeStart),
                            )
                        }
                    }
                }
                AnalysisResult(notes, decoded.durationMillis, cleaned)
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        stage = AnalysisStage.Complete,
                        progress = 1f,
                        audioDurationMillis = result.durationMillis,
                        notes = result.notes,
                        selectedNoteIndex = result.notes.indices.firstOrNull(),
                        vocalIsolationApplied = result.cleaned.vocalIsolationApplied,
                        noiseReductionApplied = result.cleaned.noiseReductionApplied,
                        processingWarning = result.cleaned.warning,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _uiState.update {
                    it.copy(
                        stage = AnalysisStage.Error,
                        errorMessage = error.message ?: "This audio file could not be analyzed.",
                    )
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val cursor = runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index)
            }
        }
        return "Uploaded humming audio"
    }

    override fun onCleared() {
        recorder.release()
        preprocessor.close()
        transcriber.close()
        pianoSynth.release()
        super.onCleared()
    }

    private companion object {
        const val MINIMUM_RECORDING_MILLIS = 1_000L
    }

    private data class AnalysisResult(
        val notes: List<DetectedNoteEvent>,
        val durationMillis: Long,
        val cleaned: dev.cvkulkarnidev.melodyvisualizer.audio.PreprocessedAudio,
    )
}
