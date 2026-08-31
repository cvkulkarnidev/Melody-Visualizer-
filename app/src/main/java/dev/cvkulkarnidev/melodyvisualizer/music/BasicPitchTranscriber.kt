package dev.cvkulkarnidev.melodyvisualizer.music

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Runs Spotify's Basic Pitch TFLite model and decodes its note/onset activations.
 * The model is small enough to remain bundled and entirely offline.
 */
class BasicPitchTranscriber(private val context: Context) : AutoCloseable {
    private val interpreterDelegate = lazy {
        Interpreter(
            loadModel(),
            Interpreter.Options().setNumThreads(
                Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            ),
        )
    }
    private val interpreter by interpreterDelegate

    @Synchronized
    fun transcribe(
        samples: ShortArray,
        sampleRate: Int,
        onProgress: (Float) -> Unit = {},
    ): List<DetectedNoteEvent> {
        if (samples.isEmpty()) return emptyList()
        require(sampleRate > 0) { "Audio sample rate must be positive." }
        val audio = resampleTo22k(samples, sampleRate)
        val padded = FloatArray(audio.size + OVERLAP_SAMPLES / 2)
        audio.copyInto(padded, destinationOffset = OVERLAP_SAMPLES / 2)

        val signatureKey = interpreter.signatureKeys.singleOrNull()
            ?: error("Basic Pitch model signature is unavailable.")
        val inputName = interpreter.getSignatureInputs(signatureKey).singleOrNull()
            ?: error("Basic Pitch input signature is invalid.")
        val outputNames = interpreter.getSignatureOutputs(signatureKey).toSet()
        require(NOTE_OUTPUT in outputNames && ONSET_OUTPUT in outputNames) {
            "Basic Pitch note outputs are unavailable."
        }

        val inputTensor = interpreter.getInputTensorFromSignature(inputName, signatureKey)
        require(inputTensor.numElements() == AUDIO_WINDOW_SAMPLES) {
            "Unexpected Basic Pitch input size: ${inputTensor.numElements()}"
        }
        val noteTensor = interpreter.getOutputTensorFromSignature(NOTE_OUTPUT, signatureKey)
        val onsetTensor = interpreter.getOutputTensorFromSignature(ONSET_OUTPUT, signatureKey)
        val framesPerWindow = noteTensor.numElements() / NOTE_BINS
        require(framesPerWindow > OVERLAP_FRAMES && onsetTensor.numElements() == noteTensor.numElements()) {
            "Unexpected Basic Pitch output shape."
        }

        val input = directFloatBuffer(inputTensor.numElements())
        val noteOutput = directFloatBuffer(noteTensor.numElements())
        val onsetOutput = directFloatBuffer(onsetTensor.numElements())
        val noteFrames = mutableListOf<FloatArray>()
        val onsetFrames = mutableListOf<FloatArray>()
        val windowCount = ceil(padded.size.toDouble() / WINDOW_HOP_SAMPLES).toInt().coerceAtLeast(1)

        repeat(windowCount) { windowIndex ->
            val start = windowIndex * WINDOW_HOP_SAMPLES
            input.clear()
            repeat(AUDIO_WINDOW_SAMPLES) { index ->
                input.put(padded.getOrElse(start + index) { 0f })
            }
            input.rewind()
            noteOutput.clear()
            onsetOutput.clear()
            interpreter.runSignature(
                mapOf(inputName to input),
                mapOf(NOTE_OUTPUT to noteOutput, ONSET_OUTPUT to onsetOutput),
                signatureKey,
            )
            noteOutput.rewind()
            onsetOutput.rewind()

            repeat(framesPerWindow) { frameIndex ->
                val notes = FloatArray(NOTE_BINS)
                val onsets = FloatArray(NOTE_BINS)
                noteOutput.get(notes)
                onsetOutput.get(onsets)
                if (frameIndex in HALF_OVERLAP_FRAMES until framesPerWindow - HALF_OVERLAP_FRAMES) {
                    noteFrames += notes
                    onsetFrames += onsets
                }
            }
            onProgress((windowIndex + 1f) / windowCount)
        }

        val expectedFrames = (
            audio.size.toDouble() / WINDOW_HOP_SAMPLES *
                (framesPerWindow - OVERLAP_FRAMES)
            ).toInt()
            .coerceAtLeast(1)
        val frameCount = minOf(expectedFrames, noteFrames.size)
        return decodeMonophonicNotes(
            noteFrames = noteFrames.take(frameCount),
            onsetFrames = onsetFrames.take(frameCount),
        )
    }

    private fun decodeMonophonicNotes(
        noteFrames: List<FloatArray>,
        onsetFrames: List<FloatArray>,
    ): List<DetectedNoteEvent> {
        if (noteFrames.isEmpty()) return emptyList()
        val remaining = noteFrames.map { it.copyOf() }.toTypedArray()
        val candidates = mutableListOf<NeuralNoteCandidate>()

        for (pitchIndex in MIN_PITCH_INDEX..MAX_PITCH_INDEX) {
            for (frameIndex in noteFrames.lastIndex - 1 downTo 1) {
                val onset = onsetFrames[frameIndex][pitchIndex]
                val isPeak = onset >= ONSET_THRESHOLD &&
                    onset >= onsetFrames[frameIndex - 1][pitchIndex] &&
                    (frameIndex == noteFrames.lastIndex || onset > onsetFrames[frameIndex + 1][pitchIndex])
                if (!isPeak) continue
                traceForwardCandidate(
                    startFrame = frameIndex,
                    pitchIndex = pitchIndex,
                    original = noteFrames,
                    remaining = remaining,
                )?.let(candidates::add)
            }
        }

        // Spotify's “melodia trick”: recover stable note energy that had no strong onset.
        var recoveredCount = 0
        while (recoveredCount < MAX_MELODIA_CANDIDATES) {
            var bestFrame = -1
            var bestPitch = -1
            var bestValue = FRAME_THRESHOLD
            remaining.forEachIndexed { frameIndex, frame ->
                for (pitchIndex in MIN_PITCH_INDEX..MAX_PITCH_INDEX) {
                    if (frame[pitchIndex] > bestValue) {
                        bestValue = frame[pitchIndex]
                        bestFrame = frameIndex
                        bestPitch = pitchIndex
                    }
                }
            }
            if (bestFrame < 0) break
            traceBidirectionalCandidate(
                middleFrame = bestFrame,
                pitchIndex = bestPitch,
                original = noteFrames,
                remaining = remaining,
            )?.let(candidates::add)
            recoveredCount++
        }

        return candidatesToMonophonicEvents(candidates, noteFrames.size)
    }

    private fun traceForwardCandidate(
        startFrame: Int,
        pitchIndex: Int,
        original: List<FloatArray>,
        remaining: Array<FloatArray>,
    ): NeuralNoteCandidate? {
        var frame = startFrame + 1
        var lowEnergyFrames = 0
        while (frame < original.size && lowEnergyFrames < ENERGY_TOLERANCE_FRAMES) {
            lowEnergyFrames = if (remaining[frame][pitchIndex] < FRAME_THRESHOLD) {
                lowEnergyFrames + 1
            } else {
                0
            }
            frame++
        }
        val endFrame = (frame - lowEnergyFrames).coerceAtMost(original.size)
        if (endFrame - startFrame < MINIMUM_NOTE_FRAMES) return null
        val amplitude = averageActivation(original, startFrame, endFrame, pitchIndex)
        clearPitchRegion(remaining, startFrame, endFrame, pitchIndex)
        return NeuralNoteCandidate(startFrame, endFrame, pitchIndex + MIDI_OFFSET, amplitude)
    }

    private fun traceBidirectionalCandidate(
        middleFrame: Int,
        pitchIndex: Int,
        original: List<FloatArray>,
        remaining: Array<FloatArray>,
    ): NeuralNoteCandidate? {
        var frame = middleFrame + 1
        var lowEnergyFrames = 0
        while (frame < original.size && lowEnergyFrames < ENERGY_TOLERANCE_FRAMES) {
            lowEnergyFrames = if (remaining[frame][pitchIndex] < FRAME_THRESHOLD) lowEnergyFrames + 1 else 0
            clearPitchAtFrame(remaining, frame, pitchIndex)
            frame++
        }
        val endFrame = (frame - lowEnergyFrames).coerceAtMost(original.size)

        frame = middleFrame - 1
        lowEnergyFrames = 0
        while (frame >= 0 && lowEnergyFrames < ENERGY_TOLERANCE_FRAMES) {
            lowEnergyFrames = if (remaining[frame][pitchIndex] < FRAME_THRESHOLD) lowEnergyFrames + 1 else 0
            clearPitchAtFrame(remaining, frame, pitchIndex)
            frame--
        }
        val startFrame = (frame + 1 + lowEnergyFrames).coerceAtLeast(0)
        clearPitchRegion(remaining, startFrame, endFrame, pitchIndex)
        if (endFrame - startFrame < MINIMUM_NOTE_FRAMES) return null
        return NeuralNoteCandidate(
            startFrame,
            endFrame,
            pitchIndex + MIDI_OFFSET,
            averageActivation(original, startFrame, endFrame, pitchIndex),
        )
    }

    private fun candidatesToMonophonicEvents(
        candidates: List<NeuralNoteCandidate>,
        frameCount: Int,
    ): List<DetectedNoteEvent> {
        if (candidates.isEmpty()) return emptyList()
        val selectedMidi = IntArray(frameCount) { -1 }
        val selectedConfidence = FloatArray(frameCount)

        for (frame in 0 until frameCount) {
            val active = candidates.filter { frame in it.startFrame until it.endFrame }
            if (active.isEmpty()) continue
            var best = active.maxBy { it.confidence }
            val octaveLower = active
                .filter { best.midi - it.midi == 12 && it.confidence >= best.confidence * 0.78f }
                .maxByOrNull { it.confidence }
            if (octaveLower != null) best = octaveLower
            selectedMidi[frame] = best.midi
            selectedConfidence[frame] = best.confidence
        }

        val events = mutableListOf<DetectedNoteEvent>()
        var start = 0
        while (start < frameCount) {
            val midi = selectedMidi[start]
            if (midi < 0) {
                start++
                continue
            }
            var end = start + 1
            var confidenceSum = selectedConfidence[start]
            while (end < frameCount && selectedMidi[end] == midi) {
                confidenceSum += selectedConfidence[end]
                end++
            }
            if (end - start >= MINIMUM_NOTE_FRAMES) {
                events += DetectedNoteEvent(
                    note = MusicNote.fromMidi(midi),
                    startMillis = frameToMillis(start),
                    durationMillis = frameToMillis(end) - frameToMillis(start),
                    confidence = confidenceSum / (end - start),
                )
            }
            start = end
        }
        return mergeMatchingNeighbors(events)
    }

    private fun mergeMatchingNeighbors(events: List<DetectedNoteEvent>): List<DetectedNoteEvent> {
        if (events.isEmpty()) return events
        val merged = mutableListOf(events.first())
        events.drop(1).forEach { event ->
            val previous = merged.last()
            if (previous.note.midi == event.note.midi && event.startMillis - previous.endMillis <= 80L) {
                merged[merged.lastIndex] = previous.copy(
                    durationMillis = event.endMillis - previous.startMillis,
                    confidence = (previous.confidence + event.confidence) / 2f,
                )
            } else {
                merged += event
            }
        }
        return merged
    }

    private fun averageActivation(
        frames: List<FloatArray>,
        start: Int,
        end: Int,
        pitch: Int,
    ): Float {
        var sum = 0f
        for (frame in start until end) sum += frames[frame][pitch]
        return sum / (end - start).coerceAtLeast(1)
    }

    private fun clearPitchRegion(frames: Array<FloatArray>, start: Int, end: Int, pitch: Int) {
        for (frame in start until end) clearPitchAtFrame(frames, frame, pitch)
    }

    private fun clearPitchAtFrame(frames: Array<FloatArray>, frame: Int, pitch: Int) {
        if (frame !in frames.indices) return
        frames[frame][pitch] = 0f
        if (pitch > 0) frames[frame][pitch - 1] = 0f
        if (pitch < NOTE_BINS - 1) frames[frame][pitch + 1] = 0f
    }

    private fun resampleTo22k(input: ShortArray, inputSampleRate: Int): FloatArray {
        if (inputSampleRate == MODEL_SAMPLE_RATE * 2) {
            return FloatArray((input.size + 1) / 2) { outputIndex ->
                val sourceIndex = outputIndex * 2
                val first = input[sourceIndex].toInt()
                val second = input.getOrElse(sourceIndex + 1) { input[sourceIndex] }.toInt()
                ((first + second) * 0.5f / Short.MAX_VALUE).coerceIn(-1f, 1f)
            }
        }

        val outputSize = (input.size.toLong() * MODEL_SAMPLE_RATE / inputSampleRate)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val sourceStep = inputSampleRate.toDouble() / MODEL_SAMPLE_RATE
        return FloatArray(outputSize) { outputIndex ->
            val sourcePosition = outputIndex * sourceStep
            val leftIndex = floor(sourcePosition).toInt().coerceIn(input.indices)
            val rightIndex = (leftIndex + 1).coerceAtMost(input.lastIndex)
            val fraction = sourcePosition - leftIndex
            val value = input[leftIndex] * (1.0 - fraction) + input[rightIndex] * fraction
            (value / Short.MAX_VALUE).toFloat().coerceIn(-1f, 1f)
        }
    }

    private fun directFloatBuffer(elements: Int): FloatBuffer =
        ByteBuffer.allocateDirect(elements * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun loadModel(): ByteBuffer {
        val descriptor = context.assets.openFd(MODEL_ASSET_PATH)
        return FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
            channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength,
            )
        }.also { descriptor.close() }
    }

    override fun close() {
        if (interpreterDelegate.isInitialized()) interpreter.close()
    }

    private data class NeuralNoteCandidate(
        val startFrame: Int,
        val endFrame: Int,
        val midi: Int,
        val confidence: Float,
    )

    private companion object {
        const val MODEL_ASSET_PATH = "models/basic_pitch.tflite"
        const val MODEL_SAMPLE_RATE = 22_050
        const val NOTE_OUTPUT = "note"
        const val ONSET_OUTPUT = "onset"
        const val AUDIO_WINDOW_SAMPLES = 43_844
        const val OVERLAP_SAMPLES = 7_680
        const val WINDOW_HOP_SAMPLES = AUDIO_WINDOW_SAMPLES - OVERLAP_SAMPLES
        const val NOTE_BINS = 88
        const val OVERLAP_FRAMES = 30
        const val HALF_OVERLAP_FRAMES = OVERLAP_FRAMES / 2
        const val ANNOTATIONS_PER_SECOND = 86
        const val MIDI_OFFSET = 21
        const val MIN_PITCH_INDEX = 12 // A1 / MIDI 33
        const val MAX_PITCH_INDEX = 75 // C7 / MIDI 96
        const val MINIMUM_NOTE_FRAMES = 9
        const val ENERGY_TOLERANCE_FRAMES = 9
        const val MAX_MELODIA_CANDIDATES = 256
        const val ONSET_THRESHOLD = 0.48f
        const val FRAME_THRESHOLD = 0.30f

        fun frameToMillis(frame: Int): Long = frame * 1_000L / ANNOTATIONS_PER_SECOND
    }
}
