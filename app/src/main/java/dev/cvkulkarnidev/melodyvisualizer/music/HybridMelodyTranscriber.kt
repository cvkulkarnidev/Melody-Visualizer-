package dev.cvkulkarnidev.melodyvisualizer.music

import android.content.Context
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Combines neural note/onset evidence with YIN-based acoustic pitch tracking. */
class HybridMelodyTranscriber(context: Context) : AutoCloseable {
    private val basicPitch = BasicPitchTranscriber(context)

    @Synchronized
    fun transcribe(
        samples: ShortArray,
        sampleRate: Int,
        onProgress: (Float) -> Unit = {},
    ): List<DetectedNoteEvent> {
        val acousticEvents = MelodyTranscriber(sampleRate = sampleRate).transcribe(samples) {
            onProgress(it * 0.35f)
        }
        val neuralEvents = runCatching {
            basicPitch.transcribe(samples, sampleRate) { onProgress(0.35f + it * 0.65f) }
        }.getOrElse {
            onProgress(1f)
            return acousticEvents
        }
        onProgress(1f)
        if (neuralEvents.isEmpty()) return acousticEvents
        return HybridPitchReconciler.reconcile(neuralEvents, acousticEvents)
    }

    override fun close() = basicPitch.close()
}

internal object HybridPitchReconciler {
    fun reconcile(
        neuralEvents: List<DetectedNoteEvent>,
        acousticEvents: List<DetectedNoteEvent>,
    ): List<DetectedNoteEvent> {
        val fused = neuralEvents.map { neural ->
            val overlaps = acousticEvents.mapNotNull { acoustic ->
                val overlap = min(neural.endMillis, acoustic.endMillis) -
                    max(neural.startMillis, acoustic.startMillis)
                if (overlap > 0L) acoustic to overlap else null
            }
            val acoustic = overlaps.maxByOrNull { (event, overlap) ->
                overlap * event.confidence
            }?.first

            when {
                acoustic == null -> neural
                acoustic.note.midi == neural.note.midi -> neural.copy(
                    confidence = (neural.confidence * 0.62f + acoustic.confidence * 0.38f)
                        .coerceIn(0f, 1f),
                )
                abs(acoustic.note.midi - neural.note.midi) == 12 &&
                    acoustic.confidence >= 0.92f && neural.confidence < 0.58f -> neural.copy(
                    note = acoustic.note,
                    confidence = (neural.confidence + acoustic.confidence) / 2f,
                )
                abs(acoustic.note.midi - neural.note.midi) <= 1 &&
                    acoustic.confidence > neural.confidence + 0.22f -> neural.copy(
                    note = acoustic.note,
                    confidence = (neural.confidence + acoustic.confidence) / 2f,
                )
                else -> neural
            }
        }.toMutableList()

        acousticEvents
            .filter { acoustic ->
                acoustic.confidence >= 0.86f &&
                    acoustic.durationMillis >= 170L &&
                    fused.none { neural ->
                        val center = acoustic.startMillis + acoustic.durationMillis / 2L
                        center in neural.startMillis until neural.endMillis
                    }
            }
            .forEach(fused::add)

        return normalizeTimeline(fused.sortedBy { it.startMillis })
    }

    private fun normalizeTimeline(events: List<DetectedNoteEvent>): List<DetectedNoteEvent> {
        if (events.isEmpty()) return events
        val normalized = mutableListOf<DetectedNoteEvent>()
        for (event in events) {
            val previous = normalized.lastOrNull()
            if (previous == null) {
                normalized += event
                continue
            }
            if (previous.note.midi == event.note.midi && event.startMillis - previous.endMillis <= 120L) {
                normalized[normalized.lastIndex] = previous.copy(
                    durationMillis = max(previous.endMillis, event.endMillis) - previous.startMillis,
                    confidence = (previous.confidence + event.confidence) / 2f,
                )
                continue
            }
            if (event.startMillis < previous.endMillis) {
                val trimmedDuration = event.startMillis - previous.startMillis
                if (trimmedDuration >= 100L) {
                    normalized[normalized.lastIndex] = previous.copy(durationMillis = trimmedDuration)
                } else {
                    normalized.removeAt(normalized.lastIndex)
                }
            }
            normalized += event
        }
        return normalized.filter { it.durationMillis >= 100L }
    }

}
