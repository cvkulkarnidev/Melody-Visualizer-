package dev.cvkulkarnidev.melodyvisualizer.audio

import kotlin.math.roundToInt

internal object PcmResampler {
    fun linear(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        require(sourceRate > 0 && targetRate > 0)
        if (input.isEmpty() || sourceRate == targetRate) return input.copyOf()
        val outputSize = (input.size.toLong() * targetRate / sourceRate).toInt().coerceAtLeast(1)
        val scale = sourceRate.toDouble() / targetRate
        return ShortArray(outputSize) { outputIndex ->
            val sourcePosition = outputIndex * scale
            val leftIndex = sourcePosition.toInt().coerceIn(0, input.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(input.lastIndex)
            val fraction = sourcePosition - leftIndex
            (input[leftIndex] * (1.0 - fraction) + input[rightIndex] * fraction)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }
}

internal object PcmChannelMixer {
    fun mono(left: ShortArray, right: ShortArray): ShortArray {
        val size = minOf(left.size, right.size)
        return ShortArray(size) { index ->
            ((left[index].toInt() + right[index].toInt()) / 2).toShort()
        }
    }
}
