package com.m57.mboard

import java.io.IOException
import java.io.Reader

/**
 * Pure Kotlin substring rewrite rules for common English orthographic confusions.
 * Used exclusively for suggestion ranking when exact/fuzzy prefix matches are absent.
 */
class EnglishMisspellRules private constructor(
    private val rules: List<Rule>,
) {
    private data class Rule(
        val pattern: String,
        val replacement: String,
        val cost: Double,
    )

    /**
     * Generate rewrite candidates within word length bounds 5..32, applying at most two
     * finite substring rewrites. Returns candidates mapped to cumulative rewrite cost
     * (lower is better). Original word is discarded; deduplicated by minimum cost.
     */
    fun candidates(word: String): Map<String, Double> {
        if (word.length !in 5..32 || rules.isEmpty()) return emptyMap()

        val results = HashMap<String, Double>()

        fun applyLayer(
            current: String,
            baseCost: Double,
            onCandidate: (String, Double) -> Unit,
        ) {
            for (rule in rules) {
                var searchStart = 0
                while (searchStart < current.length) {
                    val idx = current.indexOf(rule.pattern, searchStart)
                    if (idx < 0) break
                    val rewritten = current.substring(0, idx) + rule.replacement + current.substring(idx + rule.pattern.length)
                    if (rewritten.length in 5..32 && rewritten != current) {
                        onCandidate(rewritten, baseCost + rule.cost)
                    }
                    searchStart = idx + 1
                }
            }
        }

        val layerOne = HashMap<String, Double>()
        applyLayer(word, 0.0) { cand, cost ->
            val prev = layerOne[cand]
            if (prev == null || cost < prev) {
                layerOne[cand] = cost
            }
        }

        for ((cand, cost) in layerOne) {
            val prev = results[cand]
            if (prev == null || cost < prev) {
                results[cand] = cost
            }
        }

        // Second layer: apply rewrites to each layer-one candidate
        for ((cand1, cost1) in layerOne) {
            applyLayer(cand1, cost1) { cand2, cost2 ->
                if (cand2 != word) {
                    val prev = results[cand2]
                    if (prev == null || cost2 < prev) {
                        results[cand2] = cost2
                    }
                }
            }
        }

        results.remove(word)
        return results
    }

    companion object {
        val EMPTY = EnglishMisspellRules(emptyList())

        /**
         * Reads tab-separated rules: pattern TAB replacement TAB positive_cost.
         * Validates data; skips comments and blank lines.
         * Throws [IOException] on malformed rows.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun read(reader: Reader): EnglishMisspellRules {
            val rules = ArrayList<Rule>()
            reader.buffered().useLines { lines ->
                var lineNumber = 0
                for (rawLine in lines) {
                    lineNumber++
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue

                    val parts = line.split('\t')
                    if (parts.size != 3) {
                        throw IOException("Malformed rule at line $lineNumber: expected 3 tab-separated fields, got ${parts.size}")
                    }
                    val pattern = parts[0].trim()
                    val replacement = parts[1].trim()
                    val costStr = parts[2].trim()

                    if (pattern.isEmpty() || replacement.isEmpty() ||
                        pattern.any { it !in 'a'..'z' } || replacement.any { it !in 'a'..'z' }
                    ) {
                        throw IOException("Malformed rule at line $lineNumber: patterns must be nonempty lowercase English letters")
                    }
                    if (pattern == replacement) {
                        throw IOException("Malformed rule at line $lineNumber: pattern equals replacement")
                    }
                    val cost =
                        costStr.toDoubleOrNull()
                            ?: throw IOException("Malformed rule at line $lineNumber: invalid cost '$costStr'")
                    if (cost <= 0.0 || cost.isNaN() || cost.isInfinite()) {
                        throw IOException("Malformed rule at line $lineNumber: cost must be positive and finite, got $cost")
                    }

                    rules.add(Rule(pattern, replacement, cost))
                }
            }
            return EnglishMisspellRules(rules)
        }
    }
}
