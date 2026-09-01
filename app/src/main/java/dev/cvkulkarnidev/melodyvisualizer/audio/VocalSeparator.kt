package dev.cvkulkarnidev.melodyvisualizer.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jtransforms.fft.FloatFFT_1D
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Extracts a vocal-focused mono signal with the Spleeter 2-stem vocal mask model. */
class VocalSeparator(private val context: Context) : AutoCloseable {
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private val inferenceMutex = Mutex()

    suspend fun isolateVocals(
        leftSamples: ShortArray,
        rightSamples: ShortArray,
        sampleRate: Int,
        onProgress: (Float) -> Unit = {},
    ): ShortArray = inferenceMutex.withLock {
        isolateVocalsLocked(leftSamples, rightSamples, sampleRate, onProgress)
    }

    private suspend fun isolateVocalsLocked(
        leftSamples: ShortArray,
        rightSamples: ShortArray,
        sampleRate: Int,
        onProgress: (Float) -> Unit,
    ): ShortArray {
        require(sampleRate == SAMPLE_RATE) { "Vocal separation requires 44.1 kHz audio." }
        if (leftSamples.isEmpty() || rightSamples.isEmpty()) return ShortArray(0)

        val sampleCount = minOf(leftSamples.size, rightSamples.size)
        val frameCount = if (sampleCount <= FFT_SIZE) {
            1
        } else {
            1 + ceil((sampleCount - FFT_SIZE).toDouble() / HOP_SIZE).toInt()
        }
        val splitCount = ceil(frameCount.toDouble() / FRAMES_PER_SPLIT).toInt()
        val overlapOutput = FloatArray(sampleCount + FFT_SIZE)
        val fft = FloatFFT_1D(FFT_SIZE.toLong())
        val window = FloatArray(FFT_SIZE) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / FFT_SIZE)).toFloat()
        }
        val localSession = getOrCreateSession()
        val inputName = localSession.inputNames.first()

        repeat(splitCount) { splitIndex ->
            currentCoroutineContext().ensureActive()
            val magnitudes = FloatArray(INPUT_ELEMENT_COUNT)
            val realParts = FloatArray(INPUT_ELEMENT_COUNT)
            val imaginaryParts = FloatArray(INPUT_ELEMENT_COUNT)
            val inputBuffer = directFloatBuffer(INPUT_ELEMENT_COUNT)

            for (channel in 0 until CHANNELS) {
                val source = if (channel == 0) leftSamples else rightSamples
                for (localFrame in 0 until FRAMES_PER_SPLIT) {
                    if (localFrame % 32 == 0) currentCoroutineContext().ensureActive()
                    val globalFrame = splitIndex * FRAMES_PER_SPLIT + localFrame
                    val complex = FloatArray(FFT_SIZE * 2)
                    if (globalFrame < frameCount) {
                        val sampleOffset = globalFrame * HOP_SIZE
                        for (index in 0 until FFT_SIZE) {
                            val sourceIndex = sampleOffset + index
                            val sample = if (sourceIndex < sampleCount) {
                                source[sourceIndex] / 32768f
                            } else {
                                0f
                            }
                            complex[index] = sample * window[index]
                        }
                        fft.realForwardFull(complex)
                    }

                    for (bin in 0 until MODEL_BINS) {
                        val elementIndex = tensorIndex(channel, localFrame, bin)
                        val real = complex[bin * 2]
                        val imaginary = complex[bin * 2 + 1]
                        val magnitude = hypot(real.toDouble(), imaginary.toDouble()).toFloat()
                        realParts[elementIndex] = real
                        imaginaryParts[elementIndex] = imaginary
                        magnitudes[elementIndex] = magnitude
                        inputBuffer.put(magnitude)
                    }
                }
            }
            inputBuffer.rewind()

            OnnxTensor.createTensor(
                environment,
                inputBuffer,
                longArrayOf(CHANNELS.toLong(), 1L, FRAMES_PER_SPLIT.toLong(), MODEL_BINS.toLong()),
            ).use { inputTensor ->
                localSession.run(mapOf(inputName to inputTensor)).use { result ->
                    val predictedMagnitudes = (result[0] as OnnxTensor).floatBuffer
                    synthesizeSplit(
                        splitIndex = splitIndex,
                        frameCount = frameCount,
                        sampleCount = sampleCount,
                        magnitudes = magnitudes,
                        predictedMagnitudes = predictedMagnitudes,
                        realParts = realParts,
                        imaginaryParts = imaginaryParts,
                        fft = fft,
                        window = window,
                        output = overlapOutput,
                    )
                }
            }
            onProgress((splitIndex + 1f) / splitCount)
        }

        return ShortArray(sampleCount) { index ->
            val weight = overlapWeight(index, frameCount, window)
            val value = if (weight > 1e-7f) {
                overlapOutput[index] / weight
            } else {
                0f
            }
            (value.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                .roundToInt()
                .toShort()
        }
    }

    private fun synthesizeSplit(
        splitIndex: Int,
        frameCount: Int,
        sampleCount: Int,
        magnitudes: FloatArray,
        predictedMagnitudes: FloatBuffer,
        realParts: FloatArray,
        imaginaryParts: FloatArray,
        fft: FloatFFT_1D,
        window: FloatArray,
        output: FloatArray,
    ) {
        for (localFrame in 0 until FRAMES_PER_SPLIT) {
            val globalFrame = splitIndex * FRAMES_PER_SPLIT + localFrame
            if (globalFrame >= frameCount) break
            val sampleOffset = globalFrame * HOP_SIZE

            for (channel in 0 until CHANNELS) {
                val complex = FloatArray(FFT_SIZE * 2)
                for (bin in 0 until MODEL_BINS) {
                    val index = tensorIndex(channel, localFrame, bin)
                    val mask = VocalMasking.mask(predictedMagnitudes.get(index), magnitudes[index])
                    val real = realParts[index] * mask
                    val imaginary = imaginaryParts[index] * mask
                    complex[bin * 2] = real
                    complex[bin * 2 + 1] = imaginary
                    if (bin > 0) {
                        val mirrorBin = FFT_SIZE - bin
                        complex[mirrorBin * 2] = real
                        complex[mirrorBin * 2 + 1] = -imaginary
                    }
                }
                fft.complexInverse(complex, true)
                for (index in 0 until FFT_SIZE) {
                    val outputIndex = sampleOffset + index
                    if (outputIndex >= sampleCount) break
                    output[outputIndex] += complex[index * 2] * window[index] * 0.5f
                }
            }
        }
    }

    private fun overlapWeight(sampleIndex: Int, frameCount: Int, window: FloatArray): Float {
        val earliestFrame = ((sampleIndex - FFT_SIZE + 1).coerceAtLeast(0) + HOP_SIZE - 1) / HOP_SIZE
        val latestFrame = minOf(frameCount - 1, sampleIndex / HOP_SIZE)
        var weight = 0f
        for (frame in earliestFrame..latestFrame) {
            val windowIndex = sampleIndex - frame * HOP_SIZE
            weight += window[windowIndex] * window[windowIndex]
        }
        return weight
    }

    private fun getOrCreateSession(): OrtSession {
        session?.let { return it }
        val modelFile = copyModelToInternalStorage()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return options.use {
            environment.createSession(modelFile.absolutePath, it).also { created -> session = created }
        }
    }

    private fun copyModelToInternalStorage(): File {
        val modelDirectory = File(context.filesDir, "models").apply { mkdirs() }
        val destination = File(modelDirectory, MODEL_ASSET.substringAfterLast('/'))
        val assetLength = context.assets.openFd(MODEL_ASSET).use { it.length }
        if (destination.isFile && destination.length() == assetLength) return destination

        val temporary = File(modelDirectory, "${destination.name}.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output) }
        }
        check(temporary.length() == assetLength) { "The bundled vocal model is incomplete." }
        if (destination.exists()) check(destination.delete()) { "The previous vocal model could not be replaced." }
        check(temporary.renameTo(destination)) { "The vocal model could not be prepared." }
        return destination
    }

    override fun close() {
        session?.close()
        session = null
    }

    private fun tensorIndex(channel: Int, frame: Int, bin: Int): Int =
        (channel * FRAMES_PER_SPLIT + frame) * MODEL_BINS + bin

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private companion object {
        const val MODEL_ASSET = "models/spleeter_vocals_fp16.onnx"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val FFT_SIZE = 4_096
        const val HOP_SIZE = 1_024
        const val FRAMES_PER_SPLIT = 512
        const val MODEL_BINS = 1_024
        const val INPUT_ELEMENT_COUNT = CHANNELS * FRAMES_PER_SPLIT * MODEL_BINS
    }
}

internal object VocalMasking {
    fun mask(predictedMagnitude: Float, mixtureMagnitude: Float): Float {
        if (!predictedMagnitude.isFinite() || mixtureMagnitude <= 1e-8f) return 0f
        return (predictedMagnitude / mixtureMagnitude).coerceIn(0f, 1f)
    }
}
