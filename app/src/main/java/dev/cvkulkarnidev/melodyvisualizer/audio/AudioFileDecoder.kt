package dev.cvkulkarnidev.melodyvisualizer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class DecodedAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val durationMillis: Long,
)

/** Decodes common Android audio formats to mono 16-bit PCM without writing a temporary file. */
class AudioFileDecoder(private val context: Context) {
    suspend fun decode(
        uri: Uri,
        onProgress: (Float) -> Unit = {},
    ): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("No audio track was found in this file.")

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("The audio format could not be identified.")
            val sourceDurationUs = inputFormat
                .takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?: 0L
            if (sourceDurationUs > MAX_DURATION_US) {
                error("Please choose a humming recording shorter than ${MAX_DURATION_US / 60_000_000} minutes.")
            }

            extractor.selectTrack(trackIndex)
            val decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            codec = decoder

            val pcm = ShortArrayBuilder()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputSampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            var outputChannels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()

                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("The decoder returned an invalid input buffer.")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0 || extractor.sampleTime > MAX_DURATION_US) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                extractor.sampleFlags,
                            )
                            if (sourceDurationUs > 0) {
                                onProgress((extractor.sampleTime.toFloat() / sourceDurationUs * 0.45f).coerceIn(0f, 0.45f))
                            }
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        outputSampleRate = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_SAMPLE_RATE,
                            outputSampleRate,
                        )
                        outputChannels = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_CHANNEL_COUNT,
                            outputChannels,
                        )
                        pcmEncoding = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
                    -> Unit

                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            ?: error("The decoder returned an invalid output buffer.")
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        appendMonoPcm(
                            buffer = outputBuffer.slice().order(ByteOrder.nativeOrder()),
                            encoding = pcmEncoding,
                            channelCount = outputChannels,
                            destination = pcm,
                        )
                        decoder.releaseOutputBuffer(outputIndex, false)
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
            }

            if (pcm.size == 0) error("The selected file did not contain decodable audio.")
            val sourceSamples = pcm.toArray()
            val outputSamples = if (outputSampleRate == TARGET_SAMPLE_RATE) {
                sourceSamples
            } else {
                resampleLinear(sourceSamples, outputSampleRate, TARGET_SAMPLE_RATE)
            }
            val durationMillis = outputSamples.size * 1_000L / TARGET_SAMPLE_RATE
            onProgress(0.5f)
            return DecodedAudio(outputSamples, TARGET_SAMPLE_RATE, durationMillis)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun appendMonoPcm(
        buffer: java.nio.ByteBuffer,
        encoding: Int,
        channelCount: Int,
        destination: ShortArrayBuilder,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val samples = buffer.asShortBuffer()
                while (samples.remaining() >= channels) {
                    var sum = 0
                    repeat(channels) { sum += samples.get().toInt() }
                    destination.add((sum / channels).toShort())
                }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                val samples = buffer.asFloatBuffer()
                while (samples.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += samples.get() }
                    destination.add(
                        ((sum / channels).coerceIn(-1f, 1f) * Short.MAX_VALUE)
                            .roundToInt()
                            .toShort(),
                    )
                }
            }

            else -> error("This PCM encoding is not supported on the device ($encoding).")
        }
    }

    private fun resampleLinear(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        require(sourceRate > 0 && targetRate > 0)
        if (input.isEmpty()) return input
        val outputSize = (input.size.toLong() * targetRate / sourceRate).toInt().coerceAtLeast(1)
        val scale = sourceRate.toDouble() / targetRate
        return ShortArray(outputSize) { outputIndex ->
            val sourcePosition = outputIndex * scale
            val leftIndex = sourcePosition.toInt().coerceIn(0, input.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(input.lastIndex)
            val fraction = sourcePosition - leftIndex
            (input[leftIndex] * (1.0 - fraction) + input[rightIndex] * fraction)
                .roundToInt()
                .toShort()
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private class ShortArrayBuilder(initialCapacity: Int = TARGET_SAMPLE_RATE * 10) {
        private var values = ShortArray(initialCapacity)
        var size: Int = 0
            private set

        fun add(value: Short) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): ShortArray = values.copyOf(size)
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 44_100
        private const val MAX_DURATION_US = 120_000_000L
        private const val TIMEOUT_US = 10_000L
    }
}
