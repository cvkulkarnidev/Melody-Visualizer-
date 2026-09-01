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
    val leftSamples: ShortArray,
    val rightSamples: ShortArray,
    val sampleRate: Int,
    val durationMillis: Long,
)

/** Decodes common Android audio formats to stereo and mono 16-bit PCM in memory. */
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
                error("Please choose audio shorter than ${MAX_DURATION_US / 60_000_000} minutes.")
            }

            extractor.selectTrack(trackIndex)
            val decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            codec = decoder

            val leftPcm = ShortArrayBuilder()
            val rightPcm = ShortArrayBuilder()
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
                        appendStereoPcm(
                            buffer = outputBuffer.slice().order(ByteOrder.nativeOrder()),
                            encoding = pcmEncoding,
                            channelCount = outputChannels,
                            leftDestination = leftPcm,
                            rightDestination = rightPcm,
                        )
                        decoder.releaseOutputBuffer(outputIndex, false)
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
            }

            if (leftPcm.size == 0) error("The selected file did not contain decodable audio.")
            val leftSamples = PcmResampler.linear(leftPcm.toArray(), outputSampleRate, TARGET_SAMPLE_RATE)
            val rightSamples = PcmResampler.linear(rightPcm.toArray(), outputSampleRate, TARGET_SAMPLE_RATE)
            val monoSamples = PcmChannelMixer.mono(leftSamples, rightSamples)
            val durationMillis = monoSamples.size * 1_000L / TARGET_SAMPLE_RATE
            onProgress(0.5f)
            return DecodedAudio(
                samples = monoSamples,
                leftSamples = leftSamples,
                rightSamples = rightSamples,
                sampleRate = TARGET_SAMPLE_RATE,
                durationMillis = durationMillis,
            )
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun appendStereoPcm(
        buffer: java.nio.ByteBuffer,
        encoding: Int,
        channelCount: Int,
        leftDestination: ShortArrayBuilder,
        rightDestination: ShortArrayBuilder,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val samples = buffer.asShortBuffer()
                while (samples.remaining() >= channels) {
                    val left = samples.get()
                    val right = if (channels > 1) samples.get() else left
                    repeat((channels - 2).coerceAtLeast(0)) { samples.get() }
                    leftDestination.add(left)
                    rightDestination.add(right)
                }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                val samples = buffer.asFloatBuffer()
                while (samples.remaining() >= channels) {
                    val left = samples.get()
                    val right = if (channels > 1) samples.get() else left
                    repeat((channels - 2).coerceAtLeast(0)) { samples.get() }
                    leftDestination.add(floatToShort(left))
                    rightDestination.add(floatToShort(right))
                }
            }

            else -> error("This PCM encoding is not supported on the device ($encoding).")
        }
    }

    private fun floatToShort(value: Float): Short =
        (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()

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
