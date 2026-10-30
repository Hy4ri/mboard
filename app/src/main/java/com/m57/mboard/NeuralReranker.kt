package com.m57.mboard

import java.io.DataInputStream
import java.io.InputStream
import java.util.Locale

/** Experimental tied-embedding feed-forward language scorer. It cannot create candidates. */
internal class NeuralReranker private constructor(
    private val vocabulary: Map<String, Int>,
    private val dim: Int,
    private val hidden: Int,
    private val embedding: ByteArray,
    private val inputWeights: ByteArray,
    private val outputWeights: ByteArray,
    private val embeddingScale: Float,
    private val inputScale: Float,
    private val outputScale: Float,
    private val hiddenBias: FloatArray,
    private val outputBias: FloatArray,
    private val wordBias: ByteArray,
    private val biasScale: Float,
) {
    /** One immutable projection per lookup. Softmax cancels when comparing the same candidate pool. */
    fun scorer(context: List<String>): (String) -> Double? {
        val last = context.lastOrNull()?.let { vocabulary[key(it)] ?: 0 } ?: 1
        val previous = if (context.isEmpty()) 2 else context.dropLast(1).lastOrNull()?.let { vocabulary[key(it)] ?: 0 } ?: 1
        val x = FloatArray(dim * 2)
        for (j in 0 until dim) {
            x[j] = embedding[previous * dim + j] * embeddingScale
            x[dim + j] = embedding[last * dim + j] * embeddingScale
        }
        val h = FloatArray(hidden)
        for (i in 0 until hidden) {
            var sum = hiddenBias[i]
            for (j in x.indices) sum += inputWeights[i * x.size + j] * inputScale * x[j]
            h[i] = maxOf(0f, sum)
        }
        val output = FloatArray(dim)
        for (i in 0 until dim) {
            var sum = outputBias[i]
            for (j in h.indices) sum += outputWeights[i * hidden + j] * outputScale * h[j]
            output[i] = sum
        }
        return { word ->
            val index = vocabulary[key(word)]
            // Unknown candidates retain their original score. Never prefer the shared UNK logit.
            if (index == null) {
                null
            } else {
                var score = wordBias[index] * biasScale
                for (j in 0 until dim) score += embedding[index * dim + j] * embeddingScale * output[j]
                score.toDouble()
            }
        }
    }

    companion object {
        // OFF until the complete held-out, autocorrect, size and latency contract passes.
        val enabled: Boolean get() = java.lang.Boolean.getBoolean("mboard.neural")
        val weight: Double get() = System.getProperty("mboard.neural.weight", "0.1").toDouble().coerceIn(0.0, 1.0)

        private fun key(word: String): String =
            word.lowercase(Locale.ROOT).filterNot {
                it in '\u064B'..'\u065F' || it == '\u0670' || it == '\u0640' || it == '\'' || it == '\u2019'
            }

        fun read(stream: InputStream): NeuralReranker =
            DataInputStream(stream.buffered()).use { input ->
                require(input.readInt() == 0x4d524b31) { "Unsupported reranker format" }
                val size = input.readInt()
                val dim = input.readInt()
                val hidden = input.readInt()
                require(size in 3..20000 && dim in 1..128 && hidden in 1..256)
                require(input.readInt() == size - 3)
                val vocabulary = HashMap<String, Int>(size)
                repeat(size - 3) { vocabulary[input.readUTF()] = it + 3 }
                val embeddingScale = input.readFloat()
                val embedding = ByteArray(size * dim).also(input::readFully)
                val inputScale = input.readFloat()
                val inputWeights = ByteArray(hidden * 2 * dim).also(input::readFully)
                val outputScale = input.readFloat()
                val outputWeights = ByteArray(dim * hidden).also(input::readFully)
                val hiddenBias = FloatArray(hidden) { input.readFloat() }
                val outputBias = FloatArray(dim) { input.readFloat() }
                val biasScale = input.readFloat()
                val wordBias = ByteArray(size).also(input::readFully)
                require(input.read() == -1)
                NeuralReranker(
                    vocabulary,
                    dim,
                    hidden,
                    embedding,
                    inputWeights,
                    outputWeights,
                    embeddingScale,
                    inputScale,
                    outputScale,
                    hiddenBias,
                    outputBias,
                    wordBias,
                    biasScale,
                )
            }
    }
}
