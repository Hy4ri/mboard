package com.m57.mboard

import java.io.Reader

/** Sorted newline-delimited spelling forms, kept as one string instead of a large object/hash set. */
class KnownWordForms private constructor(
    private val text: String,
) {
    val isEmpty: Boolean get() = text.isEmpty()

    operator fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = low + (high - low) / 2
            val start = text.lastIndexOf('\n', middle - 1) + 1
            val end = text.indexOf('\n', middle).let { if (it < 0) text.length else it }
            val comparison = compareRegion(word, start, end)
            when {
                comparison < 0 -> high = start
                comparison > 0 -> low = end + 1
                else -> return true
            }
        }
        return false
    }

    private fun compareRegion(
        word: String,
        start: Int,
        end: Int,
    ): Int {
        for (index in 0 until minOf(word.length, end - start)) {
            val comparison = word[index].compareTo(text[start + index])
            if (comparison != 0) return comparison
        }
        return word.length.compareTo(end - start)
    }

    companion object {
        val EMPTY = KnownWordForms("")

        /** Assets are generated in Unicode order with one undiacritized form per line. */
        fun read(reader: Reader): KnownWordForms = KnownWordForms(reader.readText())
    }
}
