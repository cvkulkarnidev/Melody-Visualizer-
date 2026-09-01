package dev.cvkulkarnidev.melodyvisualizer.audio

import android.content.Context
import kotlinx.coroutines.CancellationException

data class PreprocessedAudio(
    val samples: ShortArray,
    val vocalIsolationApplied: Boolean,
    val noiseReductionApplied: Boolean,
    val warning: String? = null,
)

/** Chooses the conservative cleanup path for microphone recordings or mixed uploads. */
class AudioPreprocessor(context: Context) : AutoCloseable {
    private val vocalSeparator = VocalSeparator(context)
    private val noiseReducer = NeuralNoiseReducer(context)

    suspend fun process(
        audio: DecodedAudio,
        isolateVocals: Boolean,
        onSeparatingProgress: (Float) -> Unit = {},
        onCleaningProgress: (Float) -> Unit = {},
    ): PreprocessedAudio {
        var workingSamples = audio.samples
        var separationApplied = false
        var noiseReductionApplied = false
        val warnings = mutableListOf<String>()

        if (isolateVocals) {
            onSeparatingProgress(0f)
            runCatching {
                vocalSeparator.isolateVocals(
                    leftSamples = audio.leftSamples,
                    rightSamples = audio.rightSamples,
                    sampleRate = audio.sampleRate,
                    onProgress = onSeparatingProgress,
                )
            }.onSuccess { separated ->
                if (separated.any { it != 0.toShort() }) {
                    workingSamples = separated
                    separationApplied = true
                } else {
                    warnings += "Vocal isolation found no usable vocal stem, so the original audio was analyzed."
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                warnings += "Vocal isolation was unavailable; the original mix was analyzed."
            }
        }

        onCleaningProgress(0f)
        runCatching {
            noiseReducer.reduce(
                samples = workingSamples,
                sampleRate = audio.sampleRate,
                onProgress = onCleaningProgress,
            )
        }.onSuccess { cleaned ->
            workingSamples = cleaned
            noiseReductionApplied = true
        }.onFailure { error ->
            if (error is CancellationException) throw error
            warnings += "Noise reduction was unavailable on this device."
        }

        return PreprocessedAudio(
            samples = workingSamples,
            vocalIsolationApplied = separationApplied,
            noiseReductionApplied = noiseReductionApplied,
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    override fun close() = vocalSeparator.close()
}
