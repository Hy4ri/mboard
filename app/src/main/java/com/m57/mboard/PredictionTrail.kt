package com.m57.mboard

import java.util.Locale

/**
 * Avoid repeating a four-word transition during a run of suggestion taps, including runs longer
 * than the editor's surrounding-text window. This transient history is never learned or saved.
 * Manual edits and field changes reset it; ordinary typed repetition is unaffected.
 */
class PredictionTrail {
    private val transitions = LinkedHashSet<List<String>>()
    private var expected: List<String>? = null

    fun reset() {
        transitions.clear()
        expected = null
    }

    fun record(
        context: List<String>,
        word: String,
    ) {
        val prefix = context.takeLast(3).map(::key)
        // The editor moved or its text changed outside this chain.
        if (expected != null && prefix != expected) reset()
        val transition = prefix + key(word)
        if (transition.size == 4) {
            transitions.remove(transition)
            transitions.add(transition)
            // Bound memory even if somebody taps for hours. This covers the observed 200-tap
            // cascades, not a promise of preventing every possible infinitely long cycle.
            if (transitions.size > 512) transitions.remove(transitions.first())
        }
        expected = transition.takeLast(3)
    }

    /** Prefer unseen continuations; if the finite pool is exhausted, offer the least recent
     * repeated transition rather than blanking the strip or discarding the entire trail.
     */
    fun filter(
        context: List<String>,
        candidates: List<String>,
    ): List<String> {
        val fresh = candidates.filter { allows(context, it) }
        if (fresh.isNotEmpty() || candidates.isEmpty()) return fresh
        val prefix = context.takeLast(3).map(::key)
        val oldestFirst = transitions.toList()
        return candidates.sortedBy { oldestFirst.indexOf(prefix + key(it)) }
    }

    private fun allows(
        context: List<String>,
        word: String,
    ): Boolean {
        val prefix = context.takeLast(3).map(::key)
        // Some editors expose the old context briefly after a successful commit. Don't discard
        // history on that read; only a subsequent accepted pick can start a different chain.
        if (prefix != expected) return true
        return prefix + key(word) !in transitions
    }

    private fun key(word: String): String =
        word.lowercase(Locale.ROOT).filterNot {
            it in '\u064B'..'\u065F' || it == '\u0670' || it == '\u0640' || it == '\'' || it == '\u2019'
        }
}
