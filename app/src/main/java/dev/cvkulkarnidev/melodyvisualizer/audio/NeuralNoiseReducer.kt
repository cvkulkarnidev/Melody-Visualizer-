package dev.cvkulkarnidev.melodyvisualizer.audio

import android.content.Context
import com.kaleyra.noise_filter.DeepFilterNet
import com.rikorose.deepfilternet.NativeDeepFilterNet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Applies speech-and-vocal-aware noise suppression without sending audio off device. */
class NeuralNoiseReducer(private val context: Context) {
    suspend fun reduce(
        samples: ShortArray,
        sampleRate: Int,
        onProgress: (Float) -> Unit = {},
    ): ShortArray {
        if (samples.isEmpty()) return samples
        val modelSamples = PcmResampler.linear(samples, sampleRate, MODEL_SAMPLE_RATE)
        val filter = NativeDeepFilterNet(context, attenuationLimit = ATTENUATION_LIMIT_DB)
        try {
            val loaded = CompletableDeferred<DeepFilterNet>()
            filter.onModelLoaded { loaded.complete(it) }
            withTimeout(MODEL_LOAD_TIMEOUT_MILLIS) { loaded.await() }

            val frameBytes = filter.frameLength.toInt()
            check(frameBytes > 0 && frameBytes % Short.SIZE_BYTES == 0) {
                "The noise-reduction model returned an invalid frame size."
            }
            val samplesPerFrame = frameBytes / Short.SIZE_BYTES
            val frameCount = (modelSamples.size + samplesPerFrame - 1) / samplesPerFrame
            val output = ShortArray(modelSamples.size)
            val buffer = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)

            repeat(frameCount) { frameIndex ->
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val sourceOffset = frameIndex * samplesPerFrame
                repeat(samplesPerFrame) { localIndex ->
                    val sourceIndex = sourceOffset + localIndex
                    buffer.putShort(
                        if (sourceIndex < modelSamples.size) modelSamples[sourceIndex] else 0.toShort(),
                    )
                }
                buffer.rewind()
                filter.processFrame(buffer)
                buffer.rewind()
                val processed = buffer.asShortBuffer()
                val copyCount = minOf(samplesPerFrame, output.size - sourceOffset)
                processed.get(output, sourceOffset, copyCount)
                onProgress((frameIndex + 1f) / frameCount)
            }

            return PcmResampler.linear(output, MODEL_SAMPLE_RATE, sampleRate).copyOf(samples.size)
        } finally {
            filter.release()
        }
    }

    private companion object {
        const val MODEL_SAMPLE_RATE = 48_000
        const val ATTENUATION_LIMIT_DB = 18f
        const val MODEL_LOAD_TIMEOUT_MILLIS = 45_000L
    }
}
