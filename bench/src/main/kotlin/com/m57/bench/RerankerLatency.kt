package com.m57.bench

import com.m57.mboard.NeuralReranker
import java.io.DataInputStream
import java.io.File

/** Standalone warmed desktop inference probe, not Android main-thread timing. */
fun main(args: Array<String>) {
    val assets = File(args.firstOrNull() ?: "../app/src/main/assets")
    for (language in listOf("en", "ar", "jo")) {
        val model = NeuralReranker.read(File(assets, "rerank_$language.bin").inputStream())
        val contexts =
            if (language == "en") {
                listOf(listOf("I", "want"), listOf("in", "the"), listOf("have", "a"), emptyList())
            } else {
                listOf(listOf("أنا", "لا"), listOf("من", "هذا"), listOf("بدي", "اروح"), emptyList())
            }
        // 64 known candidates, two projections per lookup. Production prediction makes at most
        // two projections (context sources + common fallback); completion makes one.
        val candidates =
            DataInputStream(File(assets, "rerank_$language.bin").inputStream().buffered()).use { input ->
                repeat(4) { input.readInt() }
                val size = input.readInt()
                List(minOf(64, size)) { input.readUTF() }
            }
        var checksum = 0.0
        val times = LongArray(20000)
        repeat(30000) { iteration ->
            val start = System.nanoTime()
            repeat(2) {
                val score = model.scorer(contexts[iteration % contexts.size])
                val logits = candidates.associateWith(score)
                val known = logits.values.filterNotNull()
                val mean = known.average()
                val sorted = candidates.sortedByDescending { (logits[it] ?: mean) - mean }
                checksum += logits[sorted.first()] ?: 0.0
            }
            if (iteration >= 10000) times[iteration - 10000] = System.nanoTime() - start
        }
        times.sort()
        println(
            "$language calls=${times.size} p50_ms=${times[times.size / 2] / 1e6} p95_ms=${times[times.size * 95 / 100] / 1e6} p99_ms=${times[times.size * 99 / 100] / 1e6} checksum=$checksum",
        )
    }
}
