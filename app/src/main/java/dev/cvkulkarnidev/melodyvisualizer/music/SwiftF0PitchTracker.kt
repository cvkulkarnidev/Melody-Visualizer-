package dev.cvkulkarnidev.melodyvisualizer.music

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.cvkulkarnidev.melodyvisualizer.audio.BandLimitedResampler
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

internal data class PitchContour(
    val pitchHz: DoubleArray,
    val confidence: FloatArray,
    val loudnessDb: DoubleArray,
    val framePeriodMillis: Double,
)

/** Runs SwiftF0 in bounded windows to produce one globally segmentable vocal pitch contour. */
internal class SwiftF0PitchTracker(private val context: Context) : AutoCloseable {
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    @Synchronized
    fun detect(
        samples: ShortArray,
        sampleRate: Int,
        onProgress: (Float) -> Unit = {},
    ): PitchContour {
        require(sampleRate > 0)
        if (samples.isEmpty()) return PitchContour(DoubleArray(0), FloatArray(0), DoubleArray(0), FRAME_MILLIS)

        val signal = BandLimitedResampler.toFloat(samples, sampleRate, MODEL_SAMPLE_RATE)
        val totalFrames = max(1, signal.size / HOP_SIZE)
        val pitch = DoubleArray(totalFrames)
        val confidence = FloatArray(totalFrames)
        val localSession = getOrCreateSession()
        val windowCount = (totalFrames + WINDOW_FRAMES - 1) / WINDOW_FRAMES

        for (windowIndex in 0 until windowCount) {
            val startFrame = windowIndex * WINDOW_FRAMES
            val endFrame = minOf(startFrame + WINDOW_FRAMES, totalFrames)
            val leftFrame = maxOf(0, startFrame - LEFT_CONTEXT_FRAMES)
            val lastSample = if (endFrame < totalFrames) {
                minOf(signal.size, (endFrame + LOOKAHEAD_FRAMES) * HOP_SIZE)
            } else {
                signal.size
            }
            val window = signal.copyOfRange(leftFrame * HOP_SIZE, lastSample)
            val output = runWindow(localSession, window)
            val sourceOffset = startFrame - leftFrame
            val copyCount = minOf(endFrame - startFrame, output.first.size - sourceOffset)
            if (copyCount > 0) {
                output.first.copyInto(pitch, startFrame, sourceOffset, sourceOffset + copyCount)
                output.second.copyInto(confidence, startFrame, sourceOffset, sourceOffset + copyCount)
            }
            onProgress((windowIndex + 1f) / windowCount)
        }

        applySilenceGate(signal, confidence)
        return PitchContour(
            pitchHz = pitch,
            confidence = confidence,
            loudnessDb = calculateLoudness(signal, totalFrames),
            framePeriodMillis = FRAME_MILLIS,
        )
    }

    private fun runWindow(session: OrtSession, audio: FloatArray): Pair<DoubleArray, FloatArray> {
        val audioBuffer = ByteBuffer.allocateDirect(audio.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(audio); rewind() }
        val scalarBuffer = { value: Float ->
            ByteBuffer.allocateDirect(Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(value); rewind() }
        }

        OnnxTensor.createTensor(environment, audioBuffer, longArrayOf(1, audio.size.toLong())).use { audioTensor ->
            OnnxTensor.createTensor(environment, scalarBuffer(MIN_VOCAL_HZ), longArrayOf()).use { minTensor ->
                OnnxTensor.createTensor(environment, scalarBuffer(MAX_VOCAL_HZ), longArrayOf()).use { maxTensor ->
                    session.run(
                        mapOf("audio" to audioTensor, "fmin" to minTensor, "fmax" to maxTensor),
                    ).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val pitch = ((result[0] as OnnxTensor).value as Array<DoubleArray>)[0].copyOf()
                        @Suppress("UNCHECKED_CAST")
                        val confidence = ((result[1] as OnnxTensor).value as Array<FloatArray>)[0].copyOf()
                        return pitch to confidence
                    }
                }
            }
        }
    }

    private fun applySilenceGate(signal: FloatArray, confidence: FloatArray) {
        confidence.indices.forEach { frame ->
            val start = frame * HOP_SIZE
            val end = minOf(start + HOP_SIZE, signal.size)
            var peak = 0f
            for (index in start until end) peak = maxOf(peak, abs(signal[index]))
            if (peak < SILENCE_PEAK) confidence[frame] = 0f
        }
    }

    private fun calculateLoudness(signal: FloatArray, frameCount: Int): DoubleArray {
        val power = DoubleArray(frameCount)
        for (frame in 0 until frameCount) {
            val start = frame * HOP_SIZE
            val end = minOf(start + HOP_SIZE, signal.size)
            var sum = 0.0
            for (index in start until end) sum += signal[index] * signal[index]
            power[frame] = sum
        }
        return DoubleArray(frameCount) { frame ->
            val previous = if (frame == 0) 0.0 else power[frame - 1]
            20.0 * log10(max(sqrt((previous + power[frame]) / (HOP_SIZE * 2.0)), 1e-7))
        }
    }

    private fun getOrCreateSession(): OrtSession {
        session?.let { return it }
        val model = copyModelToInternalStorage()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return options.use {
            environment.createSession(model.absolutePath, it).also { created -> session = created }
        }
    }

    private fun copyModelToInternalStorage(): File {
        val directory = File(context.filesDir, "models").apply { mkdirs() }
        val destination = File(directory, MODEL_ASSET.substringAfterLast('/'))
        val assetLength = context.assets.openFd(MODEL_ASSET).use { it.length }
        if (destination.isFile && destination.length() == assetLength) return destination

        val temporary = File(directory, "${destination.name}.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output) }
        }
        check(temporary.length() == assetLength) { "The bundled pitch model is incomplete." }
        if (destination.exists()) check(destination.delete()) { "The previous pitch model could not be replaced." }
        check(temporary.renameTo(destination)) { "The pitch model could not be prepared." }
        return destination
    }

    override fun close() {
        session?.close()
        session = null
    }

    private companion object {
        const val MODEL_ASSET = "models/swift_f0.onnx"
        const val MODEL_SAMPLE_RATE = 16_000
        const val HOP_SIZE = 256
        const val FRAME_MILLIS = 16.0
        const val LEFT_CONTEXT_FRAMES = 11
        const val LOOKAHEAD_FRAMES = 10
        const val WINDOW_FRAMES = 1_875
        const val MIN_VOCAL_HZ = 55f
        const val MAX_VOCAL_HZ = 1_200f
        const val SILENCE_PEAK = 1e-3f
    }
}
